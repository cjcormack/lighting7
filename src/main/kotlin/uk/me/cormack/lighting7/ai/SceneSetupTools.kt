package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.routes.blankElementFields
import uk.me.cormack.lighting7.routes.forgetElement
import uk.me.cormack.lighting7.routes.seatResolves
import uk.me.cormack.lighting7.routes.seatingOf
import uk.me.cormack.lighting7.routes.sceneryOwnersChanged
import uk.me.cormack.lighting7.routes.stageElementsOf
import uk.me.cormack.lighting7.routes.stageViewpointsOf
import uk.me.cormack.lighting7.routes.store
import uk.me.cormack.lighting7.routes.toFields
import uk.me.cormack.lighting7.state.SceneImageException
import uk.me.cormack.lighting7.state.State
import java.util.UUID

/** `upload_scene_image`'s cap, decoded: smaller than the REST upload's 25 MB, since it arrives as text. */
internal const val MAX_SCENE_IMAGE_TOOL_BYTES = 16 * 1024 * 1024

/**
 * `set_scene` and `get_scene` (stage-view plan session 2, D2): the scene document as a model writes
 * and reads it. Split from [SetupTools] only for length; the rules are that class's — validate the
 * whole request, answer every problem at once, write nothing unless everything passes, and write
 * through the same validators and broadcasts the REST routes use.
 *
 * The shape follows `set_stage`: rows upserted **by name**, fields sent overwrite and fields omitted
 * keep, plus `removeElements` / `removeViewpoints`. A row's `params` is replaced whole, not merged,
 * because what it may hold depends on the kind beside it.
 *
 * **The template** (`template: "proscenium-hall"` with `templateParams`) expands server-side into
 * named elements — the hall, the stage house, the deck, the proscenium, the stalls and a balcony —
 * which are then upserted exactly as if they had been sent. An explicit element of the same name
 * lays its fields over the template's, so "the template, but the hall is panelled" is one call.
 * The research the design record cites is why: a model filling in a template's numbers does far
 * better than one writing free-form geometry.
 */
internal class SceneSetupTools(private val state: State) {

    fun setScene(input: JsonObject): ToolExecutionResult {
        val project = state.projectManager.currentProject
        val problems = mutableListOf<String>()

        for ((name, isArray) in listOf("elements" to true, "viewpoints" to true, "templateParams" to false)) {
            val e = input[name] ?: continue
            if (e is JsonNull) continue
            if (isArray && e !is JsonArray) problems += "$name must be an array"
            if (!isArray && e !is JsonObject) problems += "$name must be an object"
        }
        problems += unknownFields(
            input,
            listOf("template", "templateParams", "elements", "viewpoints", "removeElements", "removeViewpoints", "dryRun"),
            "set_scene",
        )
        val dryRun = input.bool("dryRun") ?: false

        // The template's rows first, then every explicit row laid over the template row it names.
        val template = input.string("template")?.trim()
        val templateRows: List<Pair<String, JsonObject>> = when {
            template == null -> {
                if (input["templateParams"] is JsonObject) problems += "templateParams needs a template"
                emptyList()
            }
            template != PROSCENIUM_HALL -> {
                problems += "template must be '$PROSCENIUM_HALL'"
                emptyList()
            }
            else -> prosceniumHall(
                input["templateParams"] as? JsonObject ?: JsonObject(emptyMap()),
                currentRegionNames(project),
                problems,
            )
        }
        val explicit = (input["elements"] as? JsonArray).orEmpty().mapIndexedNotNull { i, e ->
            val row = e as? JsonObject ?: run { problems += "elements[$i]: not an object"; return@mapIndexedNotNull null }
            val name = row.string("name")?.trim().orEmpty()
            if (name.isEmpty() || name.length > 100) problems += "elements[$i]: name must be 1–100 characters"
            Triple("elements[$i] ('$name')", name, row)
        }
        explicit.groupBy { it.second }.filter { it.value.size > 1 }.keys.forEach { problems += "element '$it' is listed twice" }
        val rows = LinkedHashMap<String, Pair<String, JsonObject>>()
        for ((name, row) in templateRows) rows[name] = "template '$name'" to row
        for ((where, name, row) in explicit) {
            val under = rows[name]
            rows[name] = if (under == null) where to row else where to overlay(under.second, row)
        }

        val viewpointRows = (input["viewpoints"] as? JsonArray).orEmpty().mapIndexedNotNull { i, e ->
            val row = e as? JsonObject ?: run { problems += "viewpoints[$i]: not an object"; return@mapIndexedNotNull null }
            val name = row.string("name")?.trim().orEmpty()
            if (name.isEmpty() || name.length > 100) problems += "viewpoints[$i]: name must be 1–100 characters"
            Triple("viewpoints[$i] ('$name')", name, row)
        }
        viewpointRows.groupBy { it.second }.filter { it.value.size > 1 }.keys.forEach { problems += "viewpoint '$it' is listed twice" }
        val removeElements = input.stringList("removeElements")
        val removeViewpoints = input.stringList("removeViewpoints")
        if (rows.isEmpty() && viewpointRows.isEmpty() && removeElements.isEmpty() && removeViewpoints.isEmpty() && template == null) {
            problems += "nothing to change: send template, elements, viewpoints, removeElements or removeViewpoints"
        }

        var swept = 0
        val plan = transaction(state.database) {
            val stored = stageElementsOf(project).associateBy { it.name }
            val storedViews = stageViewpointsOf(project).associateBy { it.name }
            val regions = DaoStageRegion.find { DaoStageRegions.project eq project.id }.toList()
            val regionUuidByName = regions.associate { it.name to it.uuid.toString() }
            val regionUuids = regionUuidByName.values.toSet()
            val projectUuid = project.uuid.toString()

            removeElements.filter { it !in stored }.forEach { problems += "removeElements: no element named '$it'" }
            removeViewpoints.filter { it !in storedViews }.forEach { problems += "removeViewpoints: no viewpoint named '$it'" }
            removeElements.filter { it in rows }.forEach { problems += "element '$it' is both set and removed" }
            removeViewpoints.filter { r -> viewpointRows.any { it.second == r } }.forEach { problems += "viewpoint '$it' is both set and removed" }

            // The scene as it will stand: every element kept, set or created, with its uuid.
            data class Planned(val uuid: UUID, val fields: StageElementFields, val params: ElementParams?)
            val planned = LinkedHashMap<String, Planned>()
            for ((name, element) in stored) {
                if (name in removeElements) continue
                planned[name] = Planned(element.uuid, element.toFields(), readElementParams(element.toFields().kind, element.params))
            }
            val written = mutableListOf<Planned>()
            for ((name, entry) in rows) {
                val (where, row) = entry
                val existing = stored[name]
                val fields = elementFields(row, existing?.toFields(), name, regionUuidByName, where, problems) ?: continue
                val storedRegion = (planned[name]?.params as? PlatformParams)?.regionUuid.takeIf { existing != null }
                val params = validateStageElement(
                    fields, regionUuids, where, problems,
                    storedRegionUuid = storedRegion,
                    imageStored = { state.sceneImages.exists(projectUuid, it) },
                    storedPaint = existing?.let { paintHashesOf(it.params) }.orEmpty(),
                )
                val p = Planned(existing?.uuid ?: UUID.randomUUID(), fields, params)
                planned[name] = p
                written += p
            }
            val seatingByUuid = planned.values.mapNotNull { p ->
                val seating = p.params as? SeatingParams ?: return@mapNotNull null
                if (p.fields.kind != StageElementKind.SEATING) return@mapNotNull null
                p.uuid to SeatingElement(p.fields.name, ElementPose(p.fields.positionX, p.fields.positionY, p.fields.positionZ, p.fields.yawDeg), seating)
            }.toMap()
            val seatingByName = seatingByUuid.entries.associate { it.value.name to it.key }

            val viewWrites = mutableListOf<StageViewpointFields>()
            for ((where, name, row) in viewpointRows) {
                val storedView = storedViews[name]
                val fields = viewpointFields(row, storedView?.toFields(), name, seatingByName, where, problems) ?: continue
                validateStageViewpoint(fields, { seatingByUuid[it] }, where, problems, storedSeat = danglingSeatOf(storedView, stored.values))
                viewWrites += fields
            }
            // A seat view this call leaves alone must still find its seat in the scene it leaves — if it
            // found it before: one a forced REST write already left dangling is not this call's doing.
            val setViews = viewpointRows.map { it.second }.toSet()
            for ((name, view) in storedViews) {
                if (name in removeViewpoints || name in setViews) continue
                val uuid = view.seatElementUuid ?: continue
                val before = stored.values.firstOrNull { it.uuid == uuid } ?: continue
                val beforeFields = before.toFields()
                if (!seatResolves(seatingOf(beforeFields, readElementParams(beforeFields.kind, before.params)), view.seatId)) continue
                if (!seatResolves(seatingByUuid[uuid], view.seatId)) {
                    problems += "viewpoint '$name' would lose its seat ${view.seatId} in '${before.name}': " +
                        "change or remove it (removeViewpoints) in the same call"
                }
            }
            if (problems.isNotEmpty() || dryRun) return@transaction Triple(written.size, viewWrites.size, false)

            var order = (stored.values.maxOfOrNull { it.sortOrder } ?: -1) + 1
            for (p in written) {
                // What it painted before this write; a new element painted nothing.
                val released = stored[p.fields.name]?.let { paintHashesOf(it.params) }.orEmpty()
                val element = stored[p.fields.name] ?: DaoStageElement.new {
                    this.project = project
                    name = p.fields.name
                    kind = p.fields.kind.name
                    layer = p.fields.layer.name
                    uuid = p.uuid
                    sortOrder = order++
                }
                element.store(p.fields, p.params!!)
                // Every image it names now, and every one it let go, starts the prune's week again.
                state.sceneImages.touch(projectUuid, released + paintHashesOf(element.params))
            }
            var viewOrder = (storedViews.values.maxOfOrNull { it.sortOrder } ?: -1) + 1
            for (fields in viewWrites) {
                val view = storedViews[fields.name] ?: DaoStageViewpoint.new {
                    this.project = project
                    name = fields.name
                    kind = fields.kind.name
                    sortOrder = viewOrder++
                }
                view.store(fields)
            }
            for (name in removeViewpoints) storedViews[name]?.delete()
            for (name in removeElements) {
                val element = stored[name] ?: continue
                swept += deleteSceneryForElements(listOf(element.id))
                forgetElement(state, project, element)
                element.delete()
            }
            Triple(written.size, viewWrites.size, true)
        }
        if (problems.isNotEmpty()) return rejected(problems)
        val (elementsSet, viewpointsSet, wrote) = plan
        if (wrote) {
            if (elementsSet > 0 || removeElements.isNotEmpty()) state.show.fixtures.stageElementListChanged()
            if (viewpointsSet > 0 || removeViewpoints.isNotEmpty()) state.show.fixtures.stageViewpointListChanged()
            if (swept > 0) sceneryOwnersChanged(state)
        }
        return success(
            "Scene ${if (dryRun) "checked" else "updated"}: $elementsSet element(s) and $viewpointsSet viewpoint(s) set, " +
                "${removeElements.size} element(s) and ${removeViewpoints.size} viewpoint(s) removed",
            buildJsonObject {
                put("dryRun", dryRun)
                put("elementsSet", elementsSet)
                put("viewpointsSet", viewpointsSet)
                put("elementsRemoved", removeElements.size)
                put("viewpointsRemoved", removeViewpoints.size)
                if (template != null) {
                    putJsonArray("templateElements") { templateRows.forEach { add(it.first) } }
                }
                put("note", "get_scene reads the document back. The Stage view draws each element by its kind, lit by the rig.")
            },
        )
    }

    /**
     * `upload_scene_image` (scrim plan D11): an image for a cloth's paint, as base64, into the
     * current project's scene-image store — the REST upload's check and answer, through the same
     * [SceneImageStore.store], capped at [MAX_SCENE_IMAGE_TOOL_BYTES] decoded (a bigger image goes in
     * through the desk's own sheet). Stored data only, like `set_scene`, so no remote-access gate:
     * an image does nothing until an element names it.
     */
    fun uploadSceneImage(input: JsonObject): ToolExecutionResult {
        val problems = unknownFields(input, listOf("mediaType", "base64"), "upload_scene_image").toMutableList()
        val mediaType = input.string("mediaType")?.trim()
        if (mediaType == null) problems += "mediaType is required: image/png or image/jpeg"
        // A pasted data URL is the same bytes behind a prefix.
        val encoded = input.string("base64")?.trim()?.substringAfter(";base64,")?.filterNot { it.isWhitespace() }
        if (encoded.isNullOrEmpty()) problems += "base64 is required: the image's bytes, base64-encoded"
        // Refused by length before anything is decoded: four characters carry three bytes.
        if (encoded != null && encoded.length.toLong() * 3 / 4 > MAX_SCENE_IMAGE_TOOL_BYTES + 2) {
            problems += "the image is over ${MAX_SCENE_IMAGE_TOOL_BYTES / (1024 * 1024)} MB decoded; upload a larger one from the element's sheet on the desk"
        }
        if (problems.isNotEmpty()) return rejected(problems)
        val bytes = runCatching { java.util.Base64.getDecoder().decode(encoded) }.getOrElse {
            return rejected(listOf("base64 does not decode: ${it.message}"))
        }
        if (bytes.size > MAX_SCENE_IMAGE_TOOL_BYTES) {
            return rejected(listOf("the image is over ${MAX_SCENE_IMAGE_TOOL_BYTES / (1024 * 1024)} MB decoded; upload a larger one from the element's sheet on the desk"))
        }
        val project = state.projectManager.currentProject
        val info = try {
            state.sceneImages.store(project.uuid.toString(), bytes, mediaType)
        } catch (e: SceneImageException) {
            return rejected(listOf(e.message ?: "not a scene image"))
        }
        return success(
            "Stored a ${info.width} × ${info.height} ${info.mediaType} as ${info.hash}",
            buildJsonObject {
                put("hash", info.hash)
                put("width", info.width)
                put("height", info.height)
                put("hasAlpha", info.hasAlpha)
                put("mediaType", info.mediaType)
                put(
                    "note",
                    "Paint a DRAPE or a FLAT with it through set_scene: params.paint {front, back} names this hash. " +
                        "Size the cloth to the image's aspect (${info.width}:${info.height})" +
                        (if (info.hasAlpha) "; its transparent pixels cut holes in the cloth." else "."),
                )
            },
        )
    }

    fun getScene(): ToolExecutionResult {
        val project = state.projectManager.currentProject
        // What each painted face's image is, read before the transaction: an image with no info
        // sidecar is decoded to answer, and that must never hold the one pooled connection.
        val paintHashes = transaction(state.database) {
            stageElementsOf(project).flatMap { paintHashesOf(it.params) }.toSet()
        }
        val imageInfo = paintHashes.associateWith { state.sceneImages.info(project.uuid.toString(), it) }
        val body = transaction(state.database) {
            val elements = stageElementsOf(project)
            val regionNames = DaoStageRegion.find { DaoStageRegions.project eq project.id }.associate { it.uuid.toString() to it.name }
            val nameByUuid = elements.associate { it.uuid to it.name }
            val seating = elements.mapNotNull { e ->
                val f = e.toFields()
                val p = readElementParams(f.kind, e.params) as? SeatingParams ?: return@mapNotNull null
                if (f.kind != StageElementKind.SEATING) return@mapNotNull null
                e.uuid to SeatingElement(f.name, ElementPose(f.positionX, f.positionY, f.positionZ, f.yawDeg), p)
            }.toMap()
            buildJsonObject {
                putJsonArray("elements") {
                    for (e in elements) add(buildJsonObject {
                        put("name", e.name)
                        put("kind", e.kind)
                        put("layer", e.layer)
                        put("x", e.positionX)
                        put("y", e.positionY)
                        put("z", e.positionZ)
                        if (e.yawDeg != 0.0) put("yawDeg", e.yawDeg)
                        if (e.kind != StageElementKind.SEATING.name) {
                            put("widthM", e.widthM)
                            put("depthM", e.depthM)
                            put("heightM", e.heightM)
                        }
                        if (e.finishColour != null || e.finishPattern != null || e.emissive) {
                            putJsonObject("finish") {
                                e.finishColour?.let { put("colour", it) }
                                e.finishPattern?.let { put("pattern", it) }
                                if (e.emissive) put("emissive", true)
                            }
                        }
                        val params = storedParamsObject(e.params)
                        // A platform's region by name — what set_scene takes — where the link resolves.
                        val region = (params["regionUuid"] as? JsonPrimitive)?.contentOrNull
                        val shown = if (region != null && region in regionNames) {
                            JsonObject(params - "regionUuid" + ("region" to JsonPrimitive(regionNames[region])))
                        } else {
                            params
                        }
                        if (shown.isNotEmpty()) put("params", shown)
                        // What each painted face's image is, so a cloth can be sized to its picture.
                        val paint = params["paint"] as? JsonObject
                        if (paint != null) {
                            putJsonObject("paintImages") {
                                for (side in listOf("front", "back")) {
                                    val hash = (paint[side] as? JsonPrimitive)?.contentOrNull ?: continue
                                    val info = imageInfo[hash]
                                    if (info == null) {
                                        put(side, "missing on this machine")
                                    } else {
                                        putJsonObject(side) {
                                            put("width", info.width)
                                            put("height", info.height)
                                            put("hasAlpha", info.hasAlpha)
                                        }
                                    }
                                }
                            }
                        }
                        seating[e.uuid]?.let { s ->
                            val last = s.params.firstRow.first() + (s.params.rows - 1)
                            val aisles = s.params.aisles.takeIf { it.isNotEmpty() }
                                ?.joinToString(prefix = "; aisles after seat ") { it.afterSeat.toString() }.orEmpty()
                            put("seats", "${s.params.rows * s.params.seatsPerRow} (rows ${s.params.firstRow}–$last, seats 1–${s.params.seatsPerRow}; seat 1 at the stage-right end$aisles)")
                        }
                        if (e.hidden) put("hidden", true)
                    })
                }
                putJsonArray("viewpoints") {
                    for (v in stageViewpointsOf(project)) add(buildJsonObject {
                        put("name", v.name)
                        put("kind", v.kind)
                        v.eyeX?.let { putPoint("eye", StagePoint(it, v.eyeY ?: 0.0, v.eyeZ ?: 0.0)) }
                        v.targetX?.let { putPoint("target", StagePoint(it, v.targetY ?: 0.0, v.targetZ ?: 0.0)) }
                        v.fovDeg?.let { put("fovDeg", it) }
                        v.seatElementUuid?.let { uuid ->
                            val seatingName = nameByUuid[uuid]
                            if (seatingName != null) put("seating", seatingName) else put("note", "its seating was deleted")
                            v.seatId?.let { put("seat", it) }
                            val s = seating[uuid]
                            val seat = v.seatId?.let { id -> s?.params?.seat(s.pose, id) }
                            if (s != null && seat != null) putPoint("seatedEye", seat.eye(s.pose))
                        }
                    })
                }
                putJsonArray("builtInViewpoints") { listOf("Orbit", "Plan", "Front", "Side").forEach { add(it) } }
            }
        }
        val count = (body["elements"] as JsonArray).size
        return success("The scene: $count element(s)", body)
    }

    // ─── Rows ───────────────────────────────────────────────────────────

    /**
     * One `elements` row laid over [existing] (or a blank of the row's kind), as the fields a write
     * would store; null when the row cannot be read far enough to check (no kind for a new name).
     */
    private fun elementFields(
        row: JsonObject,
        existing: StageElementFields?,
        name: String,
        regionUuidByName: Map<String, String>,
        where: String,
        problems: MutableList<String>,
    ): StageElementFields? {
        problems += unknownFields(row, ELEMENT_FIELDS + READ_ONLY_FIELDS, where)
        problems += malformedBooleans(row, listOf("hidden"), where)
        problems += malformedStrings(row, listOf("kind", "layer"), where)
        problems += malformedNumbers(row, ELEMENT_NUMBERS, where)
        val kindRaw = row.string("kind")
        val kind = kindRaw?.let { enumOrNull<StageElementKind>(it) }
        if (kindRaw != null && kind == null) problems += "$where: kind must be one of ${enumNames<StageElementKind>()}"
        if (existing == null && kindRaw == null) problems += "$where: kind is required for a new element (one of ${enumNames<StageElementKind>()})"
        val layerRaw = row.string("layer")
        val layer = layerRaw?.let { enumOrNull<StageElementLayer>(it) }
        if (layerRaw != null && layer == null) problems += "$where: layer must be one of ${enumNames<StageElementLayer>()}"
        val start = existing ?: blankElementFields(name, kind ?: return null)
        val finish = row["finish"]?.takeIf { it !is JsonNull }?.let {
            it as? JsonObject ?: run { problems += "$where: finish must be an object"; null }
        }
        var colour = start.finishColour
        var pattern = start.finishPattern
        var emissive = start.emissive
        if (finish != null) {
            problems += unknownFields(finish, listOf("colour", "pattern", "emissive"), "$where.finish")
            problems += malformedBooleans(finish, listOf("emissive"), "$where.finish")
            problems += malformedStrings(finish, listOf("colour", "pattern"), "$where.finish")
            if ("colour" in finish) colour = normaliseFinishColour(finish.string("colour"), "$where.finish.colour", problems)
            if ("pattern" in finish) {
                val raw = finish.string("pattern")
                pattern = raw?.let { enumOrNull<SurfacePattern>(it) }
                if (raw != null && pattern == null) problems += "$where.finish.pattern must be one of ${enumNames<SurfacePattern>()}"
            }
            if ("emissive" in finish) emissive = finish.bool("emissive") ?: false
        } else if (row["finish"] is JsonNull) {
            colour = null
            pattern = null
            emissive = false
        }
        val params = when (val p = row["params"]) {
            null -> start.params
            is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> withRegionUuid(p, regionUuidByName, where, problems)
            else -> {
                problems += "$where: params must be an object"
                start.params
            }
        }
        fun num(key: String, current: Double) = if (key in row) row.double(key) ?: 0.0 else current
        return start.copy(
            name = name,
            kind = kind ?: start.kind,
            layer = layer ?: start.layer,
            positionX = num("x", start.positionX),
            positionY = num("y", start.positionY),
            positionZ = num("z", start.positionZ),
            yawDeg = num("yawDeg", start.yawDeg),
            widthM = num("widthM", start.widthM),
            depthM = num("depthM", start.depthM),
            heightM = num("heightM", start.heightM),
            finishColour = colour,
            finishPattern = pattern,
            emissive = emissive,
            params = params,
            hidden = if ("hidden" in row) row.bool("hidden") ?: false else start.hidden,
        )
    }

    /**
     * An explicit row over the template's row of the same name: field by field, and `finish` one
     * level down too, since its colour, pattern and glow are independent — "the hall, panelled"
     * keeps the template's colour. `params` is replaced whole, as it is on any upsert.
     */
    private fun overlay(template: JsonObject, row: JsonObject): JsonObject {
        val finish = row["finish"]
        val under = template["finish"]
        val merged = if (finish is JsonObject && under is JsonObject) JsonObject(under + finish) else finish
        return JsonObject(template + row + (merged?.let { mapOf("finish" to it) } ?: emptyMap()))
    }

    /** A platform's `region` by name, turned into the `regionUuid` the document stores. */
    private fun withRegionUuid(
        params: JsonObject,
        regionUuidByName: Map<String, String>,
        where: String,
        problems: MutableList<String>,
    ): JsonObject {
        val name = params["region"] ?: return params
        val rest = params - "region"
        if (name is JsonNull) return JsonObject(rest)
        val text = (name as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        val uuid = text?.let { regionUuidByName[it] }
        if (uuid == null) {
            problems += "$where.params.region: no stage region named '${text ?: name}' (set_stage makes regions)"
            return JsonObject(rest)
        }
        if ("regionUuid" in rest && (rest["regionUuid"] as? JsonPrimitive)?.contentOrNull != uuid) {
            problems += "$where.params: region and regionUuid name different regions — send one"
        }
        return JsonObject(rest + ("regionUuid" to JsonPrimitive(uuid)))
    }

    /**
     * The seat [view] names when it was **already** dangling before this call — its seating deleted
     * or reshaped by a forced REST write — so a row that leaves it as it was (a new lens, a new
     * target) is let through, as `PUT stage-viewpoints/{id}` lets it. A seat that resolved in the
     * scene as stored is not passed: a call that deletes or reshapes the seating and keeps the view
     * must still be refused, which is the loop over untouched views' rule too.
     */
    private fun danglingSeatOf(view: DaoStageViewpoint?, before: Collection<DaoStageElement>): Pair<UUID, String>? {
        val uuid = view?.seatElementUuid ?: return null
        val id = view.seatId ?: return null
        val element = before.firstOrNull { it.uuid == uuid }
        if (element != null) {
            val fields = element.toFields()
            if (seatResolves(seatingOf(fields, readElementParams(fields.kind, element.params)), id)) return null
        }
        return uuid to id
    }

    private fun viewpointFields(
        row: JsonObject,
        existing: StageViewpointFields?,
        name: String,
        seatingByName: Map<String, UUID>,
        where: String,
        problems: MutableList<String>,
    ): StageViewpointFields? {
        problems += unknownFields(row, VIEWPOINT_FIELDS + READ_ONLY_FIELDS, where)
        problems += malformedStrings(row, listOf("kind", "seating", "seat"), where)
        problems += malformedNumbers(row, listOf("fovDeg"), where)
        val kindRaw = row.string("kind")
        val kind = kindRaw?.let { enumOrNull<StageViewpointKind>(it) }
        if (kindRaw != null && kind == null) problems += "$where: kind must be one of ${enumNames<StageViewpointKind>()}"
        if (existing == null && kindRaw == null) problems += "$where: kind is required for a new viewpoint (one of ${enumNames<StageViewpointKind>()})"
        val resolvedKind = kind ?: existing?.kind ?: return null
        fun point(key: String, current: StagePoint?): StagePoint? {
            val e = row[key] ?: return current
            if (e is JsonNull) return null
            val o = e as? JsonObject
            val values = listOf("x", "y", "z").map { o?.double(it) }
            if (o == null || values.any { it == null } || o.keys.any { it !in listOf("x", "y", "z") }) {
                problems += "$where: $key must be {x, y, z} in metres"
                return current
            }
            return StagePoint(values[0]!!, values[1]!!, values[2]!!)
        }
        var seatElement = existing?.seatElementUuid
        var seatId = existing?.seatId
        if ("seating" in row) {
            val n = row.string("seating")?.trim()
            seatElement = n?.let { seatingByName[it] ?: run { problems += "$where: no seating element named '$it'"; null } }
        } else if ("seat" in row && seatElement == null && seatingByName.size == 1) {
            // One seating in the scene: a seat is unambiguous without naming it.
            seatElement = seatingByName.values.single()
        }
        if ("seat" in row) seatId = row.string("seat")?.trim()?.uppercase()
        // A view that stops being a seat view forgets its seat.
        if (resolvedKind != StageViewpointKind.SEAT && "seat" !in row && "seating" !in row) {
            seatElement = null
            seatId = null
        }
        val eye = point("eye", existing?.eye.takeIf { resolvedKind != StageViewpointKind.SEAT })
        return StageViewpointFields(
            name = name,
            kind = resolvedKind,
            eye = eye,
            target = point("target", existing?.target),
            fovDeg = if ("fovDeg" in row) row.double("fovDeg") else existing?.fovDeg.takeIf { resolvedKind != StageViewpointKind.ORBIT },
            seatElementUuid = seatElement,
            seatId = seatId,
        )
    }

    // ─── The proscenium-hall template ───────────────────────────────────

    /**
     * The template's rows, named as the design record names them. The origin is the desk's: the
     * centre of the stage's downstage edge at deck level, so the hall floor sits at −[deckHeightM]
     * and the hall runs from the edge (y = 0) back to −[hallDepthM].
     */
    private fun prosceniumHall(
        p: JsonObject,
        regionNames: Set<String>,
        problems: MutableList<String>,
    ): List<Pair<String, JsonObject>> {
        val where = "templateParams"
        problems += unknownFields(p, TEMPLATE_NUMBERS, where)
        problems += malformedNumbers(p, TEMPLATE_NUMBERS, where)
        val before = problems.size
        fun need(key: String, min: Double, max: Double): Double {
            val v = p.double(key)
            if (v == null) {
                if (key !in p) problems += "$where.$key is required"
                return 1.0
            }
            if (v < min || v > max) problems += "$where.$key must be between $min and $max"
            return v
        }
        fun opt(key: String, default: Double, min: Double, max: Double): Double {
            val v = p.double(key) ?: return default
            if (v < min || v > max) problems += "$where.$key must be between $min and $max"
            return v
        }
        val hallW = need("hallWidthM", 2.0, 200.0)
        val hallD = need("hallDepthM", 2.0, 200.0)
        val hallH = need("hallHeightM", 2.0, 100.0)
        val stageW = need("stageWidthM", 1.0, 200.0)
        val stageD = need("stageDepthM", 1.0, 200.0)
        val prosW = need("prosWidthM", 1.0, 200.0)
        val prosH = need("prosHeightM", 1.0, 100.0)
        val deck = opt("deckHeightM", 0.0, 0.0, 5.0)
        val apron = opt("apronM", 0.3, 0.0, 20.0)
        val houseH = opt("stageHouseHeightM", hallH - deck, 1.0, 100.0)
        val rows = opt("rows", 0.0, 0.0, 26.0)
        val perRow = opt("seatsPerRow", 0.0, 0.0, 200.0)
        val rowPitch = opt("rowPitchM", 0.9, 0.5, 3.0)
        val seatPitch = opt("seatPitchM", 0.5, 0.4, 3.0)
        val firstRowM = opt("firstRowM", 2.0, 0.0, 100.0)
        val balconyD = opt("balconyDepthM", 0.0, 0.0, 50.0)
        val balconyH = opt("balconyHeightM", 2.5, 1.0, 50.0)
        val railH = opt("railHeightM", 1.0, 0.3, 2.0)
        if (rows != Math.floor(rows)) problems += "$where.rows must be a whole number"
        if (perRow != Math.floor(perRow)) problems += "$where.seatsPerRow must be a whole number"
        if (problems.size > before) return emptyList()

        if (stageW > hallW + FIT_TOLERANCE_M) problems += "$where: the stage (stageWidthM $stageW) is wider than the hall (hallWidthM $hallW)"
        if (prosW > hallW + FIT_TOLERANCE_M) problems += "$where: the proscenium opening (prosWidthM $prosW) is wider than the hall"
        if (deck + prosH > hallH + FIT_TOLERANCE_M) problems += "$where: the opening's top (deckHeightM + prosHeightM) is above the hall's ceiling"
        if (stageD <= apron) problems += "$where: stageDepthM must be greater than apronM, where the proscenium stands"
        if (rows > 0 && perRow < 1) problems += "$where: seatsPerRow is required with rows"
        if (rows > 0 && firstRowM + (rows - 1) * rowPitch >= hallD) {
            problems += "$where: $rows rows from firstRowM $firstRowM at rowPitchM $rowPitch run past the back of the hall (hallDepthM $hallD)"
        }
        if (perRow > 0 && perRow * seatPitch > hallW) problems += "$where: ${perRow.toInt()} seats at seatPitchM $seatPitch are wider than the hall"
        if (balconyD >= hallD) problems += "$where: balconyDepthM must be less than hallDepthM"
        if (balconyD > 0 && balconyH >= hallH) problems += "$where: balconyHeightM must be below the hall's ceiling"
        if (problems.size > before) return emptyList()

        val out = mutableListOf<Pair<String, JsonObject>>()
        // Rounded to the millimetre, so `-hallD + balconyD / 2` stores -17.3 and not its float dust.
        fun JsonObjectBuilder.put(key: String, value: Double) = put(key, JsonPrimitive(round(value)))
        fun element(name: String, build: JsonObjectBuilder.() -> Unit) {
            out += name to buildJsonObject { put("name", name); put("layer", "VENUE"); build() }
        }
        element("Hall") {
            put("kind", "ROOM")
            put("x", 0.0); put("y", -hallD / 2); put("z", -deck)
            put("widthM", hallW); put("depthM", hallD); put("heightM", hallH)
            putJsonObject("finish") { put("colour", "#4a4540"); put("pattern", "PLAIN") }
            putJsonObject("params") {
                putJsonArray("omit") { add("UPSTAGE") }
                putJsonObject("floor") { put("colour", "#2b2724"); put("pattern", "BOARDS") }
                putJsonObject("ceiling") { put("colour", "#8d8b84"); put("pattern", "TILES") }
            }
        }
        element("Stage house") {
            put("kind", "ROOM")
            put("x", 0.0); put("y", (apron + stageD) / 2); put("z", 0.0)
            put("widthM", hallW); put("depthM", stageD - apron); put("heightM", houseH)
            putJsonObject("finish") { put("colour", "#161617"); put("pattern", "PLAIN") }
            putJsonObject("params") { putJsonArray("omit") { add("DOWNSTAGE"); add("FLOOR") } }
        }
        if (deck > 0) {
            element(MAIN_STAGE) {
                put("kind", "PLATFORM")
                put("x", 0.0); put("y", stageD / 2); put("z", 0.0)
                put("widthM", stageW); put("depthM", stageD); put("heightM", deck)
                putJsonObject("finish") { put("colour", "#4a443d"); put("pattern", "BOARDS") }
                // The deck of the region the stage already has, where it has one by that name (D5).
                if (MAIN_STAGE in regionNames) putJsonObject("params") { put("region", MAIN_STAGE) }
            }
        }
        element("Proscenium") {
            put("kind", "PROSCENIUM")
            put("x", 0.0); put("y", apron); put("z", -deck)
            put("widthM", hallW); put("depthM", 0.3); put("heightM", hallH)
            putJsonObject("finish") { put("colour", "#6a5d52"); put("pattern", "PANELS") }
            putJsonObject("params") {
                put("openingWidthM", prosW); put("openingHeightM", prosH)
                put("openingSillM", deck); put("surroundM", 0.18)
            }
        }
        if (rows > 0) {
            element("Stalls") {
                put("kind", "SEATING")
                put("x", 0.0); put("y", -firstRowM); put("z", -deck)
                putJsonObject("finish") { put("colour", "#6a2733") }
                putJsonObject("params") {
                    put("rows", rows.toInt()); put("seatsPerRow", perRow.toInt())
                    put("rowPitchM", rowPitch); put("seatPitchM", seatPitch); put("firstRow", "A")
                }
            }
        }
        if (balconyD > 0) {
            element("Balcony") {
                put("kind", "PLATFORM")
                put("x", 0.0); put("y", -hallD + balconyD / 2); put("z", -deck + balconyH)
                put("widthM", hallW); put("depthM", balconyD); put("heightM", 0.3)
                putJsonObject("finish") { put("colour", "#2e2b28") }
                putJsonObject("params") { put("railHeightM", railH); put("railEdge", "UPSTAGE") }
            }
        }
        return out
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private fun currentRegionNames(project: DaoProject): Set<String> = transaction(state.database) {
        DaoStageRegion.find { DaoStageRegions.project eq project.id }.map { it.name }.toSet()
    }

    private fun JsonObjectBuilder.putPoint(name: String, p: StagePoint) {
        putJsonObject(name) {
            put("x", round(p.x))
            put("y", round(p.y))
            put("z", round(p.z))
        }
    }

    private fun round(v: Double): Double = Math.round(v * 1000) / 1000.0

    private fun success(description: String, result: JsonObject) =
        ToolExecutionResult(success = true, description = description, result = result.toString())

    private fun rejected(problems: List<String>): ToolExecutionResult {
        val shown = problems.distinct().take(MAX_PROBLEMS)
        return ToolExecutionResult(
            success = false,
            description = "Nothing was written: ${problems.size} problem(s)",
            result = buildJsonObject {
                put("error", "Nothing was written: fix these ${problems.size} problem(s) and send the request again")
                put("problems", buildJsonArray { shown.forEach { add(it) } })
                if (problems.size > shown.size) put("moreProblems", problems.size - shown.size)
            }.toString(),
        )
    }

    private fun unknownFields(row: JsonObject, known: List<String>, where: String): List<String> =
        row.keys.filter { it !in known }.map { "$where: unknown field '$it' (known: ${known.joinToString()})" }

    /** Present, not `null`, and not a boolean: refused, never read as false. */
    private fun malformedBooleans(row: JsonObject, names: List<String>, where: String): List<String> =
        names.filter { name ->
            val element = row[name] ?: return@filter false
            element !is JsonNull && (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == null
        }.map { "$where: $it must be true or false" }

    /** Present, not `null`, and not a string: refused, never read as absent. */
    private fun malformedStrings(row: JsonObject, names: List<String>, where: String): List<String> =
        names.filter { name ->
            val element = row[name] ?: return@filter false
            element !is JsonNull && (element as? JsonPrimitive)?.isString != true
        }.map { "$where: $it must be a string" }

    private fun malformedNumbers(row: JsonObject, names: List<String>, where: String): List<String> =
        names.filter { name ->
            val element = row[name] ?: return@filter false
            element !is JsonNull && (element as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull == null
        }.map { "$where: $it must be a number" }

    private companion object {
        const val PROSCENIUM_HALL = PROSCENIUM_HALL_TEMPLATE
        const val MAIN_STAGE = "Main stage"
        const val MAX_PROBLEMS = 100
        val ELEMENT_NUMBERS = listOf("x", "y", "z", "yawDeg", "widthM", "depthM", "heightM")
        val ELEMENT_FIELDS = ELEMENT_NUMBERS + listOf("name", "kind", "layer", "finish", "params", "hidden")
        val VIEWPOINT_FIELDS = listOf("name", "kind", "eye", "target", "fovDeg", "seating", "seat")
        /**
         * What `get_scene` answers beside a row's fields and `set_scene` ignores, so a row read back
         * can be corrected and resent as it stands: a seating's seat count, a seat view's resolved
         * eye, a note that its seating has gone, a painted face's image size.
         */
        val READ_ONLY_FIELDS = listOf("seats", "seatedEye", "note", "paintImages")
        val TEMPLATE_NUMBERS = listOf(
            "hallWidthM", "hallDepthM", "hallHeightM", "stageWidthM", "stageDepthM", "prosWidthM", "prosHeightM",
            "deckHeightM", "apronM", "stageHouseHeightM", "rows", "seatsPerRow", "rowPitchM", "seatPitchM",
            "firstRowM", "balconyDepthM", "balconyHeightM", "railHeightM",
        )

        fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun JsonObject.double(name: String): Double? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
        fun JsonObject.stringList(name: String): List<String> =
            (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
    }
}
