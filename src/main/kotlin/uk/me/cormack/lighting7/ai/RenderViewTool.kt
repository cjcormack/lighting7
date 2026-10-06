package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.SeatingElement
import uk.me.cormack.lighting7.models.StageViewpointKind
import uk.me.cormack.lighting7.models.seat
import uk.me.cormack.lighting7.routes.seatingElementsOf
import uk.me.cormack.lighting7.routes.stageViewpointsOf
import uk.me.cormack.lighting7.state.StageRenderService
import uk.me.cormack.lighting7.state.State
import java.util.Base64
import java.util.UUID
import kotlin.math.roundToInt

/**
 * `render_view` (stage-view plan session 4, D4): a PNG of a stage viewpoint, drawn offscreen by a
 * signed-in desk window ([StageRenderService]) — Claude's way of seeing the model `set_scene`
 * built, to compare it with the operator's photo and correct it.
 *
 * Beside `set_scene` / `get_scene`. Read-only in every sense: the window draws on a canvas of its own and
 * writes nothing — no DMX, no programmer, not even its own view. It answers in the **current**
 * project only, the one the desk's windows are drawing.
 *
 * The viewpoint is resolved and checked **here**, against the project's rows, and sent to the
 * window already in the Stage view's own vocabulary — a camera, a saved view's uuid, or
 * `seat:<uuid>:<id>` — so a name the model got wrong is answered at once, with the names it could
 * have used, rather than after a window has loaded the scene to find nothing there.
 *
 * `workLights` (stage-view menu plan D9) lifts the dark for this capture alone: off by default, so
 * a capture means the room as lit, and never read from or written to the drawing window's own.
 */
internal class RenderViewTool(private val state: State) {

    suspend fun renderView(input: JsonObject): ToolExecutionResult {
        val problems = mutableListOf<String>()
        input.keys.filter { it !in FIELDS }.forEach { problems += "unknown field '$it' (known: ${FIELDS.joinToString()})" }
        val width = side(input, "width", problems)
        val height = side(input, "height", problems)
        // One side alone is 16:9 to it — and a side that would put the other out of range is refused
        // rather than clamped, which would quietly give a frame of another shape than asked.
        val (w, h) = when {
            width == null && height == null -> RENDER_DEFAULT_WIDTH to RENDER_DEFAULT_HEIGHT
            height == null -> width!! to (width * 9.0 / 16.0).roundToInt()
            width == null -> (height * 16.0 / 9.0).roundToInt() to height
            else -> width to height
        }
        if ((width == null) != (height == null) && problems.isEmpty() && (w !in SIDES || h !in SIDES)) {
            problems += "at 16:9, width must be ${sixteenNine(RENDER_MIN_SIDE)}–$RENDER_MAX_SIDE and height $RENDER_MIN_SIDE–${RENDER_MAX_SIDE * 9 / 16}; send both for another shape"
        }
        if (problems.isEmpty() && w.toLong() * h > RENDER_MAX_PIXELS) {
            problems += "a render may be at most $RENDER_MAX_PIXELS pixels (1920 × 1080); $w × $h is ${w.toLong() * h}"
        }
        val source = when (val s = input["source"]) {
            null, JsonNull -> RENDER_SOURCES.first()
            else -> (s as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it in RENDER_SOURCES }
                ?: run { problems += "source must be one of ${RENDER_SOURCES.joinToString()}"; RENDER_SOURCES.first() }
        }
        val workLights = when (val l = input["workLights"]) {
            null, JsonNull -> false
            else -> (l as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: run { problems += "workLights must be true or false"; false }
        }
        if (input["viewpoint"] == null || input["viewpoint"] is JsonNull) problems += "viewpoint is required"
        if (problems.isNotEmpty()) return refused(CODE_INVALID, "The request was not rendered: ${problems.joinToString("; ")}")

        val project = state.projectManager.currentProject
        val resolution = transaction(state.database) {
            val views = stageViewpointsOf(project).map {
                SavedView(it.uuid, it.name, enumValueOf(it.kind), it.seatElementUuid, it.seatId)
            }
            resolveRenderViewpoint(input["viewpoint"], views, seatingElementsOf(project))
        }
        val viewpoint = when (resolution) {
            is ViewpointResolution.Refused -> return refused(resolution.code, resolution.message)
            is ViewpointResolution.Resolved -> resolution
        }

        return when (val outcome = state.stageRender.render(project.id.value, viewpoint.ref, w, h, source, workLights)) {
            is StageRenderService.Outcome.Rendered -> ToolExecutionResult(
                success = true,
                description = "Rendered ${viewpoint.label}",
                result = buildJsonObject {
                    put("viewpoint", viewpoint.label)
                    put("width", w)
                    put("height", h)
                    put("source", source)
                    put("workLights", workLights)
                    put("renderedBy", outcome.window.name)
                }.toString(),
                images = listOf(ToolImage("image/png", Base64.getEncoder().encodeToString(outcome.png))),
            )
            StageRenderService.Outcome.NoWindow -> refused(
                CODE_NO_WINDOW,
                "No desk window is open to render this. Ask the operator to open the desk in a browser on the desk's own network " +
                    "(http://localhost:8413/ on the desk machine) and sign in — any view will do, and nothing on its screen changes.",
            )
            is StageRenderService.Outcome.TimedOut -> refused(
                CODE_TIMEOUT,
                "The window '${outcome.window.name}' did not answer within ${outcome.after.inWholeSeconds} s. It may be asleep or " +
                    "hidden, or its graphics may be busy; try again, or ask the operator to bring a desk window to the front.",
            )
            is StageRenderService.Outcome.Busy -> refused(
                CODE_BUSY,
                "Another render was still in progress after ${outcome.waited.inWholeSeconds} s. Try again in a moment.",
            )
            is StageRenderService.Outcome.WindowClosed -> refused(
                CODE_WINDOW_CLOSED,
                "The window '${outcome.window.name}' closed before it answered. Try again: another open window will be asked.",
            )
            is StageRenderService.Outcome.Failed -> refused(
                CODE_FAILED,
                "The window '${outcome.window.name}' could not render it: ${outcome.reason}",
            )
        }
    }

    private fun side(input: JsonObject, name: String, problems: MutableList<String>): Int? {
        val e = input[name] ?: return null
        if (e is JsonNull) return null
        val v = (e as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (v == null || v !in RENDER_MIN_SIDE..RENDER_MAX_SIDE) {
            problems += "$name must be a whole number of pixels, $RENDER_MIN_SIDE–$RENDER_MAX_SIDE"
            return null
        }
        return v
    }

    /** The width that is 16:9 to [height], rounded up so the height it gives back is in range. */
    private fun sixteenNine(height: Int) = kotlin.math.ceil(height * 16.0 / 9.0).toInt()

    private fun refused(code: String, message: String) = ToolExecutionResult(
        success = false,
        description = message,
        result = buildJsonObject {
            put("error", code)
            put("message", message)
        }.toString(),
    )

    companion object {
        val FIELDS = listOf("viewpoint", "width", "height", "source", "workLights")
        private val SIDES = RENDER_MIN_SIDE..RENDER_MAX_SIDE
        const val CODE_INVALID = "RENDER_INVALID_REQUEST"
        const val CODE_NO_WINDOW = "RENDER_NO_WINDOW"
        const val CODE_TIMEOUT = "RENDER_TIMEOUT"
        const val CODE_WINDOW_CLOSED = "RENDER_WINDOW_CLOSED"
        const val CODE_BUSY = "RENDER_BUSY"
        const val CODE_FAILED = "RENDER_FAILED"
    }
}

/** A saved viewpoint as `render_view` needs it: enough to name it and, for a seat, to land it. */
internal data class SavedView(
    val uuid: UUID,
    val name: String,
    val kind: StageViewpointKind,
    val seatElementUuid: UUID?,
    val seatId: String?,
)

internal sealed interface ViewpointResolution {
    /** [ref] is the Stage view's vocabulary; [label] is how the answer names it. */
    data class Resolved(val ref: String, val label: String) : ViewpointResolution
    data class Refused(val code: String, val message: String) : ViewpointResolution
}

internal const val CODE_UNKNOWN_VIEWPOINT = "RENDER_UNKNOWN_VIEWPOINT"
internal const val CODE_UNKNOWN_SEAT = "RENDER_UNKNOWN_SEAT"

/** The built-in cameras, as the Stage view spells them (`lib/stageViewpoint.ts`'s `STAGE_CAMERAS`). */
internal val RENDER_CAMERAS = listOf("orbit", "eye", "plan", "front", "side")

/**
 * [arg] — `render_view`'s `viewpoint` — as the Stage view's vocabulary, or the named reason it is
 * not one. A string is a camera (any case), else a saved view by exact name, else by uuid; so a saved
 * view named like a camera is reached by its uuid. An object `{seating?, seat}` is a seat, the
 * seating omissible when the scene has exactly one. A saved seat view whose seat has gone (its
 * seating deleted or reshaped) is refused as the Stage view's picker disables it.
 */
internal fun resolveRenderViewpoint(
    arg: JsonElement?,
    views: List<SavedView>,
    seatings: Map<UUID, SeatingElement>,
): ViewpointResolution {
    fun unknown(what: String) = ViewpointResolution.Refused(
        CODE_UNKNOWN_VIEWPOINT,
        "No viewpoint $what. Use a camera (${RENDER_CAMERAS.joinToString()})" +
            (if (views.isEmpty()) ", or save a view with set_scene" else ", or a saved view: ${views.joinToString { "'${it.name}'" }}") +
            (if (seatings.isEmpty()) "." else ", or a seat as {seating, seat}."),
    )
    fun seatIn(seatingUuid: UUID, seating: SeatingElement, seatId: String, label: String): ViewpointResolution {
        val seat = seating.params.seat(seating.pose, seatId)
            ?: return ViewpointResolution.Refused(CODE_UNKNOWN_SEAT, "'${seating.name}' has no seat '$seatId'. ${seatRange(seating)}")
        return ViewpointResolution.Resolved("seat:$seatingUuid:${seat.id}", label.ifEmpty { "${seating.name} ${seat.id}" })
    }

    return when (arg) {
        is JsonPrimitive -> {
            val text = arg.contentOrNull?.trim().orEmpty()
            if (!arg.isString || text.isEmpty()) return ViewpointResolution.Refused(
                RenderViewTool.CODE_INVALID,
                "viewpoint must be a camera, a saved view's name or uuid, or {seating, seat}",
            )
            val camera = RENDER_CAMERAS.firstOrNull { it.equals(text, ignoreCase = true) }
            if (camera != null) return ViewpointResolution.Resolved(camera, camera)
            val view = views.firstOrNull { it.name == text }
                ?: runCatching { UUID.fromString(text) }.getOrNull()?.let { u -> views.firstOrNull { it.uuid == u } }
                ?: return unknown("named '$text'")
            if (view.kind != StageViewpointKind.SEAT) return ViewpointResolution.Resolved(view.uuid.toString(), view.name)
            val seating = view.seatElementUuid?.let { seatings[it] }
            val seatId = view.seatId
            if (seating == null || seatId == null) return ViewpointResolution.Refused(
                CODE_UNKNOWN_SEAT,
                "The saved view '${view.name}' names a seating that is no longer in the scene; re-save it with set_scene.",
            )
            when (seatIn(view.seatElementUuid, seating, seatId, view.name)) {
                // Sent as the saved row, not the seat, so the window lands the row's own target and lens.
                is ViewpointResolution.Resolved -> ViewpointResolution.Resolved(view.uuid.toString(), view.name)
                is ViewpointResolution.Refused -> ViewpointResolution.Refused(
                    CODE_UNKNOWN_SEAT,
                    "The saved view '${view.name}' names seat '$seatId', which '${seating.name}' no longer has; re-save it with set_scene.",
                )
            }
        }
        is JsonObject -> {
            val extra = arg.keys.filter { it !in listOf("seating", "seat") }
            val seatId = (arg["seat"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.uppercase()
            val seatingName = arg["seating"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull?.trim() }
            if (extra.isNotEmpty() || seatId.isNullOrEmpty() || ("seating" in arg && arg["seating"] !is JsonNull && seatingName == null)) {
                return ViewpointResolution.Refused(
                    RenderViewTool.CODE_INVALID,
                    "A seat viewpoint is {seating, seat}: seat a string like 'F6', seating a seating element's name (omissible when the scene has one)",
                )
            }
            val (uuid, seating) = when {
                seatingName != null -> seatings.entries.firstOrNull { it.value.name == seatingName }?.toPair()
                    ?: return ViewpointResolution.Refused(
                        CODE_UNKNOWN_SEAT,
                        "No seating element named '$seatingName'" +
                            (if (seatings.isEmpty()) "; the scene has no seating." else " (seating: ${seatings.values.joinToString { "'${it.name}'" }})."),
                    )
                seatings.size == 1 -> seatings.entries.single().toPair()
                seatings.isEmpty() -> return ViewpointResolution.Refused(CODE_UNKNOWN_SEAT, "The scene has no seating to sit in; add one with set_scene.")
                else -> return ViewpointResolution.Refused(
                    RenderViewTool.CODE_INVALID,
                    "Name the seating: the scene has ${seatings.values.joinToString { "'${it.name}'" }}.",
                )
            }
            seatIn(uuid, seating, seatId, "")
        }
        else -> ViewpointResolution.Refused(
            RenderViewTool.CODE_INVALID,
            "viewpoint must be a camera, a saved view's name or uuid, or {seating, seat}",
        )
    }
}

private fun seatRange(seating: SeatingElement): String {
    val p = seating.params
    val last = p.firstRow.first() + (p.rows - 1)
    return "Its seats are rows ${p.firstRow.first()}–$last, seats 1–${p.seatsPerRow}."
}
