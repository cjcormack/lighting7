package uk.me.cormack.lighting7.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// ─── The scene document's vocabulary (stage-view plan D2, D6) ─────────────────────────────────
//
// Every enumeration is stored and answered by its upper-case name, and read case-insensitively,
// so `"kind": "room"` from a model and `ROOM` from the desk's own form are the same write.

/** What an element is. The RP-2 minimum and nothing more (design record §"A scene document"). */
enum class StageElementKind { ROOM, PROSCENIUM, FLAT, DRAPE, PLATFORM, SEATING, OBJECT }

/** Venue elements outlive a production; set elements belong to this one. */
enum class StageElementLayer { VENUE, SET }

enum class SurfacePattern { PLAIN, PANELS, TILES, BOARDS }

/**
 * A side of a box, in theatre terms: downstage is −Y (towards the house), stage left is +X
 * (audience right, per the desk's axis table in `docs/fixtures-engineering.md`).
 */
enum class StageSide { DOWNSTAGE, UPSTAGE, STAGE_LEFT, STAGE_RIGHT, FLOOR, CEILING }

enum class DrapeRole { LEG, BORDER, TABS, CYC, BACKCLOTH }

/** How a drape moves, which decides the state it may carry: `open` for a draw, `trimM` for a fly. */
enum class DrapeOperation { DEAD, DRAW, FLY }

/**
 * What a drape is woven from (scrim plan D1): a cloth's, not a role's, so a flown gauze is
 * `BACKCLOTH` + `FLY` + `SHARKSTOOTH`. Absent is velour, the only fabric that pleats (D2). Canvas is
 * the flat painted cloth; muslin is translucent (D6); the two nets are scrims (D3). Nothing reads it
 * on the desk: the Stage view draws it, and until scrim session 2 draws every fabric as velour.
 */
enum class DrapeFabric { CANVAS, MUSLIN, SHARKSTOOTH, BOBBINET }

enum class OpeningKind { DOOR, WINDOW, FRENCH_WINDOW, ARCH }

enum class ObjectShape { BOX, CYLINDER, SHADE, DISC }

enum class StageViewpointKind { ORBIT, EYE, SEAT }

/**
 * What a seating's seats are drawn as. THEATRE is a fixed auditorium seat on a post, sized to the
 * pitch; BANQUET is a stacking banquet chair at its real size — a padded seat and a round-topped
 * back on a metal frame.
 */
enum class ChairStyle { THEATRE, BANQUET }

/**
 * The images painted on a drape's two sides or a flat's two faces (scrim plan D4): each the
 * SHA-256 of an image in this project's scene-image store (`SceneImageStore`), 64 lower-case hex
 * characters, stretched over the face. `front` is the downstage face. An empty paint is written as
 * absent ([parseElementParams]), so `{}` never reaches the column.
 */
@Serializable
data class ScenePaint(val front: String? = null, val back: String? = null) {
    val hashes: List<String> get() = listOfNotNull(front, back)
}

/** A surface's finish where an element has more than one surface (a room's floor and ceiling). */
@Serializable
data class SurfaceFinish(val colour: String? = null, val pattern: SurfacePattern? = null)

/**
 * The base values of an element's states — what it shows when nothing on the show says otherwise
 * (session 8's scenery tracks from these). `visible` is every kind's; `open` (0 closed … 1 drawn)
 * is a drawn drape's; `trimM` is a flown piece's height, which replaces its Z while it is set.
 */
@Serializable
data class ElementStates(
    val visible: Boolean? = null,
    val open: Double? = null,
    val trimM: Double? = null,
)

/**
 * What only one kind of element means (plan D2). One subclass per [StageElementKind]; the kind is
 * the element's column, so the JSON carries no discriminator. Written canonically by
 * [encodeElementParams] and validated at the write boundary by [parseElementParams] — the importer
 * alone stores a document without reparsing it, as it stores every other record verbatim.
 */
sealed interface ElementParams {
    val states: ElementStates?
}

@Serializable
data class RoomParams(
    /** Sides not drawn — the one the proscenium stands in, a stage house's floor the platform is. */
    val omit: List<StageSide> = emptyList(),
    val floor: SurfaceFinish? = null,
    val ceiling: SurfaceFinish? = null,
    override val states: ElementStates? = null,
) : ElementParams

@Serializable
data class ProsceniumParams(
    val openingWidthM: Double,
    val openingHeightM: Double,
    /** The opening's bottom above the wall's base — the deck's height above the house floor. */
    val openingSillM: Double = 0.0,
    /** The black surround's width round the opening. */
    val surroundM: Double = 0.0,
    override val states: ElementStates? = null,
) : ElementParams

@Serializable
data class FlatOpening(
    val kind: OpeningKind,
    /** From the flat's stage-right end (its local −X end) to the opening's nearer edge. */
    val fromM: Double,
    val widthM: Double,
    val heightM: Double,
    val sillM: Double = 0.0,
)

@Serializable
data class FlatParams(
    val openings: List<FlatOpening> = emptyList(),
    override val states: ElementStates? = null,
    /** Images on its faces (scrim plan D4); the openings still cut through them. */
    val paint: ScenePaint? = null,
) : ElementParams

@Serializable
data class DrapeParams(
    val role: DrapeRole,
    val operation: DrapeOperation? = null,
    override val states: ElementStates? = null,
    /** A drawn or flown drape's full travel, in seconds ([elementTravelS]); null snaps. */
    val travelS: Double? = null,
    /** What it is woven from (scrim plan D1); null is velour. */
    val fabric: DrapeFabric? = null,
    /** Images on its two sides (scrim plan D4). */
    val paint: ScenePaint? = null,
) : ElementParams

@Serializable
data class PlatformParams(
    val railHeightM: Double? = null,
    val railEdge: StageSide? = null,
    /** The region this platform is the deck of (D5), by uuid; a dangling one reads as none. */
    val regionUuid: String? = null,
    override val states: ElementStates? = null,
) : ElementParams

/** A gap in every row of a seating: [widthM] more between seat [afterSeat] and the next. */
@Serializable
data class SeatingAisle(val afterSeat: Int, val widthM: Double)

/**
 * Rows of seats, row [firstRow] nearest the stage at the element's origin, each further row a
 * [rowPitchM] further from the stage (local −Y) and [rakeM] higher. Seat 1 is at the stage-right
 * end of its row, and the row — its [aisles] included — is centred on the origin. An aisle moves
 * the seats past it without renumbering them, so a seat id names the same chair either way.
 * [frameColour] is the chair's frame; absent, the [chair] style's own.
 */
@Serializable
data class SeatingParams(
    val rows: Int,
    val seatsPerRow: Int,
    val rowPitchM: Double,
    val seatPitchM: Double,
    val firstRow: String = "A",
    val rakeM: Double = 0.0,
    val aisles: List<SeatingAisle> = emptyList(),
    val chair: ChairStyle = ChairStyle.THEATRE,
    val frameColour: String? = null,
    override val states: ElementStates? = null,
) : ElementParams

@Serializable
data class ObjectParams(
    val shape: ObjectShape = ObjectShape.BOX,
    /** A flown piece: it may carry `trimM`. */
    val flies: Boolean = false,
    override val states: ElementStates? = null,
    /** A flown piece's full travel, in seconds ([elementTravelS]); null snaps. */
    val travelS: Double? = null,
) : ElementParams

/** The fewest and most seconds a piece's full travel may take (scenery-programmer plan D6). */
const val MIN_TRAVEL_S = 0.1
const val MAX_TRAVEL_S = 600.0

/**
 * How long [params]' piece takes to travel its whole way — a drawn drape closed to drawn, a flown
 * piece in to out — or null where it has no travel or none is set (scenery-programmer plan D6).
 * Every move not on a cue's own clock runs at it, scaled by the share of the travel moved
 * (`SceneryService.durationFor`); with none the move snaps. `visible` never travels.
 */
fun elementTravelS(params: ElementParams?): Double? = when (params) {
    is DrapeParams -> params.travelS?.takeIf { params.operation == DrapeOperation.DRAW || params.operation == DrapeOperation.FLY }
    is ObjectParams -> params.travelS?.takeIf { params.flies }
    else -> null
}

@Suppress("UNCHECKED_CAST")
private fun serializerFor(kind: StageElementKind): KSerializer<ElementParams> = when (kind) {
    StageElementKind.ROOM -> RoomParams.serializer()
    StageElementKind.PROSCENIUM -> ProsceniumParams.serializer()
    StageElementKind.FLAT -> FlatParams.serializer()
    StageElementKind.DRAPE -> DrapeParams.serializer()
    StageElementKind.PLATFORM -> PlatformParams.serializer()
    StageElementKind.SEATING -> SeatingParams.serializer()
    StageElementKind.OBJECT -> ObjectParams.serializer()
} as KSerializer<ElementParams>

private val paramsJson = Json {
    explicitNulls = false
    encodeDefaults = false
    ignoreUnknownKeys = true
}

private fun sortKeys(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { (k, v) -> k to sortKeys(v) })
    is JsonArray -> JsonArray(element.map(::sortKeys))
    else -> element
}

/** [params] as the object the column stores and the wire carries: keys sorted, defaults omitted. */
fun elementParamsObject(kind: StageElementKind, params: ElementParams): JsonObject =
    sortKeys(paramsJson.encodeToJsonElement(serializerFor(kind), params)) as JsonObject

/** [params] as the column's text. */
fun encodeElementParams(kind: StageElementKind, params: ElementParams): String =
    paramsJson.encodeToString(JsonElement.serializer(), elementParamsObject(kind, params))

/** An object as the column's text, keys sorted — what the importer stores a synced document as. */
fun storedParamsText(obj: JsonObject): String = paramsJson.encodeToString(JsonElement.serializer(), sortKeys(obj))

/** A stored document as an object for the wire; `{}` for text that is not one. */
fun storedParamsObject(text: String): JsonObject =
    runCatching { paramsJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

/**
 * A stored document read back as its kind's params, or null where it cannot be — an imported
 * document a later writer shaped differently. Readers treat null as the kind's nothing (no seats).
 */
fun readElementParams(kind: StageElementKind, text: String): ElementParams? =
    runCatching { paramsJson.decodeFromString(serializerFor(kind), text) }.getOrNull()

// ─── Reading enumerations ───────────────────────────────────────────────────────────────────

/** [value] as an [E] by name, ignoring case; null when it names none. */
inline fun <reified E : Enum<E>> enumOrNull(value: String?): E? {
    val wanted = value?.trim()?.uppercase()?.replace(' ', '_') ?: return null
    return enumValues<E>().firstOrNull { it.name == wanted }
}

inline fun <reified E : Enum<E>> enumNames(): String = enumValues<E>().joinToString(", ") { it.name }

// ─── Validation ───────────────────────────────────────────────────────────────────────────────

private val HEX_COLOUR = Regex("^#[0-9a-fA-F]{6}$")

/** `#rrggbb`, normalised to lower case; null for a blank one. Adds a problem for anything else. */
fun normaliseFinishColour(value: String?, where: String, problems: MutableList<String>): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!HEX_COLOUR.matches(trimmed)) {
        problems += "$where must be a colour as #rrggbb"
        return null
    }
    return trimmed.lowercase()
}

/**
 * Reads one params object field by field, so every problem is found at once — the MCP surface's
 * rule (`ai/SetupTools.kt`), and why this is not a strict `decodeFromJsonElement`: that stops at
 * the first. A key the kind does not have is a problem too, since a misspelt one would otherwise
 * be dropped and the write still answer success.
 */
private class ParamReader(
    private val obj: JsonObject,
    private val where: String,
    val problems: MutableList<String>,
) {
    private val read = mutableSetOf<String>()

    private fun element(name: String): JsonElement? {
        read += name
        return obj[name]?.takeIf { it !is JsonNull }
    }

    fun number(name: String, min: Double, max: Double, required: Boolean = false, unit: String = "metres"): Double? {
        val e = element(name)
        if (e == null) {
            if (required) problems += "$where.$name is required"
            return null
        }
        val v = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        if (v == null || !v.isFinite()) {
            problems += "$where.$name must be a number"
            return null
        }
        if (v < min || v > max) {
            problems += "$where.$name must be between $min and $max $unit"
            return null
        }
        return v
    }

    fun int(name: String, min: Int, max: Int, required: Boolean = false): Int? {
        val e = element(name)
        if (e == null) {
            if (required) problems += "$where.$name is required"
            return null
        }
        val v = (e as? JsonPrimitive)?.takeIf { !it.isString }?.let { p ->
            p.intOrNull ?: p.doubleOrNull?.takeIf { it == floor(it) && it in min.toDouble()..max.toDouble() }?.toInt()
        }
        if (v == null) {
            problems += "$where.$name must be a whole number"
            return null
        }
        if (v < min || v > max) {
            problems += "$where.$name must be between $min and $max"
            return null
        }
        return v
    }

    fun bool(name: String): Boolean? {
        val e = element(name) ?: return null
        val v = (e as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (v == null) problems += "$where.$name must be true or false"
        return v
    }

    fun string(name: String): String? {
        val e = element(name) ?: return null
        val v = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (v == null) problems += "$where.$name must be a string"
        return v
    }

    inline fun <reified E : Enum<E>> enum(name: String, required: Boolean = false): E? {
        val raw = string(name)
        if (raw == null) {
            if (required && problems.none { it.startsWith("$where.$name ") }) problems += "$where.$name is required (one of ${enumNames<E>()})"
            return null
        }
        return enumOrNull<E>(raw) ?: run {
            problems += "$where.$name must be one of ${enumNames<E>()}"
            null
        }
    }

    fun obj(name: String): JsonObject? {
        val e = element(name) ?: return null
        return e as? JsonObject ?: run {
            problems += "$where.$name must be an object"
            null
        }
    }

    fun array(name: String): JsonArray? {
        val e = element(name) ?: return null
        return e as? JsonArray ?: run {
            problems += "$where.$name must be an array"
            null
        }
    }

    fun rejectUnknown() {
        for (key in obj.keys) if (key !in read) problems += "$where: unknown field '$key' (known: ${read.sorted().joinToString()})"
    }
}

private fun readFinish(r: ParamReader, name: String, where: String): SurfaceFinish? {
    val obj = r.obj(name) ?: return null
    val inner = ParamReader(obj, "$where.$name", r.problems)
    val colour = normaliseFinishColour(inner.string("colour"), "$where.$name.colour", r.problems)
    val pattern = inner.enum<SurfacePattern>("pattern")
    inner.rejectUnknown()
    return SurfaceFinish(colour, pattern)
}

/** A scene image's identity: the SHA-256 of its bytes, lower-case hex (scrim plan §3.1). */
val SCENE_IMAGE_HASH = Regex("^[0-9a-f]{64}$")

/**
 * A `paint` object read side by side, every problem at once. A hash is normalised to lower case and
 * must name an image [imageStored] holds — this project's store, or the element's own stored paint
 * (a hash a partial import left without its file still lets an unrelated edit through).
 */
private fun readPaint(r: ParamReader, where: String, imageStored: (String) -> Boolean): ScenePaint? {
    val obj = r.obj("paint") ?: return null
    val inner = ParamReader(obj, "$where.paint", r.problems)
    fun side(name: String): String? {
        val raw = inner.string(name)?.trim()?.lowercase() ?: return null
        if (!SCENE_IMAGE_HASH.matches(raw)) {
            r.problems += "$where.paint.$name must be an image's SHA-256: 64 hex characters"
            return null
        }
        if (!imageStored(raw)) {
            r.problems += "$where.paint.$name names no stored image"
            return null
        }
        return raw
    }
    val front = side("front")
    val back = side("back")
    inner.rejectUnknown()
    return ScenePaint(front, back).takeIf { it.front != null || it.back != null }
}

/**
 * Every scene-image hash a stored params document names, read leniently — a document a later
 * writer shaped differently still gives up the hashes it holds, so the prune and the exporter never
 * drop an image an element still shows.
 */
fun paintHashesOf(params: JsonObject): Set<String> {
    val paint = params["paint"] as? JsonObject ?: return emptySet()
    return listOf("front", "back").mapNotNull { side ->
        (paint[side] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.lowercase()
            ?.takeIf { SCENE_IMAGE_HASH.matches(it) }
    }.toSet()
}

/** [paintHashesOf] over a stored column's text. */
fun paintHashesOf(paramsText: String): Set<String> = paintHashesOf(storedParamsObject(paramsText))

/**
 * How far past its wall a fit may run and still fit: a tenth of a millimetre, well under anything
 * built, and well over a double's rounding — 1.1 + 2.2 is 3.3000000000000003, and an opening that
 * exactly fills its wall must not be refused for it.
 */
const val FIT_TOLERANCE_M = 1e-4

/**
 * [obj] validated as a [kind]'s params, or null with [problems] saying why. [where] prefixes every
 * message (`params`, `elements[3] ('Hall').params`). Checks against the element's own size — an
 * opening wider than its flat, a proscenium opening taller than its wall — use [widthM] and
 * [heightM] as the write will store them. A platform's `regionUuid` is checked by the caller,
 * which holds the project's regions.
 */
fun parseElementParams(
    kind: StageElementKind,
    obj: JsonObject,
    widthM: Double,
    heightM: Double,
    where: String,
    problems: MutableList<String>,
    imageStored: (String) -> Boolean = { false },
): ElementParams? {
    val before = problems.size
    val r = ParamReader(obj, where, problems)
    val statesObj = r.obj("states")
    // Read for every kind so a misplaced one is refused by name below, not as an unknown field.
    val travelS = r.number("travelS", MIN_TRAVEL_S, MAX_TRAVEL_S, unit = "seconds")
    val fabric = r.enum<DrapeFabric>("fabric")
    val paintSent = obj["paint"].let { it != null && it !is JsonNull }
    val paint = readPaint(r, where, imageStored)
    val params: ElementParams? = when (kind) {
        StageElementKind.ROOM -> {
            val omit = r.array("omit")?.mapIndexedNotNull { i, e ->
                val side = enumOrNull<StageSide>((e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull)
                if (side == null) problems += "$where.omit[$i] must be one of ${enumNames<StageSide>()}"
                side
            }.orEmpty()
            if (omit.size != omit.distinct().size) problems += "$where.omit lists a side twice"
            RoomParams(omit, readFinish(r, "floor", where), readFinish(r, "ceiling", where))
        }
        StageElementKind.PROSCENIUM -> {
            val w = r.number("openingWidthM", 0.1, 100.0, required = true)
            val h = r.number("openingHeightM", 0.1, 100.0, required = true)
            val sill = r.number("openingSillM", 0.0, 100.0) ?: 0.0
            val surround = r.number("surroundM", 0.0, 10.0) ?: 0.0
            if (w != null && w > widthM + FIT_TOLERANCE_M) problems += "$where.openingWidthM ($w) is wider than the proscenium (widthM $widthM)"
            if (h != null && sill + h > heightM + FIT_TOLERANCE_M) problems += "$where: the opening's top (openingSillM + openingHeightM = ${sill + h}) is above the proscenium (heightM $heightM)"
            if (w != null && h != null) ProsceniumParams(w, h, sill, surround) else null
        }
        StageElementKind.FLAT -> {
            val openings = r.array("openings")?.mapIndexedNotNull { i, e ->
                val o = e as? JsonObject ?: run { problems += "$where.openings[$i] must be an object"; return@mapIndexedNotNull null }
                val inner = ParamReader(o, "$where.openings[$i]", problems)
                val k = inner.enum<OpeningKind>("kind", required = true)
                val from = inner.number("fromM", 0.0, 100.0, required = true)
                val w = inner.number("widthM", 0.05, 100.0, required = true)
                val h = inner.number("heightM", 0.05, 100.0, required = true)
                val sill = inner.number("sillM", 0.0, 100.0) ?: 0.0
                inner.rejectUnknown()
                if (from != null && w != null && from + w > widthM + FIT_TOLERANCE_M) problems += "$where.openings[$i] runs past the flat's end (fromM + widthM = ${from + w}, widthM $widthM)"
                if (h != null && sill + h > heightM + FIT_TOLERANCE_M) problems += "$where.openings[$i] is taller than the flat (sillM + heightM = ${sill + h}, heightM $heightM)"
                if (k != null && from != null && w != null && h != null) FlatOpening(k, from, w, h, sill) else null
            }.orEmpty()
            FlatParams(openings, paint = paint)
        }
        StageElementKind.DRAPE -> {
            val role = r.enum<DrapeRole>("role", required = true)
            val operation = r.enum<DrapeOperation>("operation")
            role?.let { DrapeParams(it, operation, fabric = fabric, paint = paint) }
        }
        StageElementKind.PLATFORM -> {
            val rail = r.number("railHeightM", 0.1, 3.0)
            val edge = r.enum<StageSide>("railEdge")
            val region = r.string("regionUuid")?.trim()
            if (edge == StageSide.FLOOR || edge == StageSide.CEILING) problems += "$where.railEdge must be an edge: DOWNSTAGE, UPSTAGE, STAGE_LEFT or STAGE_RIGHT"
            if ((rail == null) != (edge == null)) problems += "$where: railHeightM and railEdge go together — send both, or neither"
            if (region != null && runCatching { UUID.fromString(region) }.isFailure) problems += "$where.regionUuid must be a uuid"
            PlatformParams(rail, edge, region)
        }
        StageElementKind.SEATING -> {
            val rows = r.int("rows", 1, 26, required = true)
            val perRow = r.int("seatsPerRow", 1, 200, required = true)
            val rowPitch = r.number("rowPitchM", 0.3, 5.0, required = true)
            val seatPitch = r.number("seatPitchM", 0.3, 5.0, required = true)
            val first = r.string("firstRow")?.trim()?.uppercase() ?: "A"
            val rake = r.number("rakeM", -1.0, 1.0) ?: 0.0
            val chair = r.enum<ChairStyle>("chair") ?: ChairStyle.THEATRE
            val frameColour = normaliseFinishColour(r.string("frameColour"), "$where.frameColour", problems)
            if (first.length != 1 || first[0] !in 'A'..'Z') {
                problems += "$where.firstRow must be one letter, A–Z"
            } else if (rows != null && first[0] + (rows - 1) > 'Z') {
                problems += "$where: $rows rows from row $first run past row Z"
            }
            val aisles = r.array("aisles")?.mapIndexedNotNull { i, e ->
                val o = e as? JsonObject ?: run { problems += "$where.aisles[$i] must be an object"; return@mapIndexedNotNull null }
                val inner = ParamReader(o, "$where.aisles[$i]", problems)
                val after = inner.int("afterSeat", 1, 199, required = true)
                val w = inner.number("widthM", 0.1, 10.0, required = true)
                inner.rejectUnknown()
                if (after != null && perRow != null && after >= perRow) {
                    problems += "$where.aisles[$i].afterSeat ($after) must be before the row's last seat ($perRow)"
                }
                if (after != null && w != null) SeatingAisle(after, w) else null
            }.orEmpty().sortedBy { it.afterSeat }
            if (aisles.size != aisles.distinctBy { it.afterSeat }.size) problems += "$where.aisles has two aisles after the same seat"
            if (rows != null && perRow != null && rowPitch != null && seatPitch != null) {
                SeatingParams(rows, perRow, rowPitch, seatPitch, first, rake, aisles, chair, frameColour)
            } else {
                null
            }
        }
        StageElementKind.OBJECT -> {
            val shape = r.enum<ObjectShape>("shape") ?: ObjectShape.BOX
            ObjectParams(shape, r.bool("flies") ?: false)
        }
    }
    val states = statesObj?.let {
        val inner = ParamReader(it, "$where.states", problems)
        val s = ElementStates(
            visible = inner.bool("visible"),
            open = inner.number("open", 0.0, 1.0, unit = "(0 closed, 1 open)"),
            trimM = inner.number("trimM", 0.0, 100.0),
        )
        inner.rejectUnknown()
        s
    }
    r.rejectUnknown()
    if (states?.open != null && (params as? DrapeParams)?.operation != DrapeOperation.DRAW) {
        problems += "$where.states.open is a drawn drape's (a DRAPE with operation DRAW)"
    }
    val flies = (params as? ObjectParams)?.flies == true || (params as? DrapeParams)?.operation == DrapeOperation.FLY
    if (states?.trimM != null && !flies) {
        problems += "$where.states.trimM is a flown piece's (an OBJECT with flies, or a DRAPE with operation FLY)"
    }
    val travels = flies || (params as? DrapeParams)?.operation == DrapeOperation.DRAW
    if (travelS != null && params != null && !travels) {
        problems += "$where.travelS is a moving piece's (a DRAPE with operation DRAW or FLY, or an OBJECT with flies); this $kind${
            (params as? DrapeParams)?.let { " with operation ${it.operation ?: DrapeOperation.DEAD}" }.orEmpty()
        } does not travel"
    }
    if (fabric != null && kind != StageElementKind.DRAPE) {
        problems += "$where.fabric is a drape's (a DRAPE); this $kind has none"
    }
    if (paintSent && kind != StageElementKind.DRAPE && kind != StageElementKind.FLAT) {
        problems += "$where.paint is a drape's or a flat's (a DRAPE or a FLAT); this $kind takes none"
    }
    if (problems.size > before || params == null) return null
    val kept = states?.takeIf { it.visible != null || it.open != null || it.trimM != null }
    return when (params) {
        is RoomParams -> params.copy(states = kept)
        is ProsceniumParams -> params.copy(states = kept)
        is FlatParams -> params.copy(states = kept)
        is DrapeParams -> params.copy(states = kept, travelS = travelS)
        is PlatformParams -> params.copy(states = kept)
        is SeatingParams -> params.copy(states = kept)
        is ObjectParams -> params.copy(states = kept, travelS = travelS)
    }
}

/**
 * One element as a write proposes to store it — after a partial update has been laid over the
 * stored row — checked whole. The REST routes and `set_scene` both build one and ask
 * [validateStageElement], so the two surfaces refuse exactly the same things.
 */
data class StageElementFields(
    val name: String,
    val kind: StageElementKind,
    val layer: StageElementLayer,
    val positionX: Double,
    val positionY: Double,
    val positionZ: Double,
    val yawDeg: Double,
    val widthM: Double,
    val depthM: Double,
    val heightM: Double,
    val finishColour: String?,
    val finishPattern: SurfacePattern?,
    val emissive: Boolean,
    val params: JsonObject,
    val hidden: Boolean,
)

/**
 * [fields] checked, answering the params it parsed (null when [problems] grew). [regionUuids] is
 * the project's regions, for a platform's link; [storedRegionUuid] is the link the element already
 * holds, which may dangle — its region deleted, or a peer's sync that deleted it — and is let stand,
 * since a dangling link reads as none and an edit that does not touch it must not be refused for it.
 * [imageStored] answers whether this project's scene-image store holds a hash, and [storedPaint] is
 * the paint the element already carries, let stand on the same terms: an image a partial import left
 * missing on this machine reads as unpainted, and must not refuse a rename.
 * Every message is prefixed with [where], and names the pose by [positionNames] — the surface's own
 * spelling (`positionX` over REST, `x` in a `set_scene` row).
 */
fun validateStageElement(
    fields: StageElementFields,
    regionUuids: Set<String>,
    where: String,
    problems: MutableList<String>,
    storedRegionUuid: String? = null,
    positionNames: List<String> = listOf("x", "y", "z"),
    imageStored: (String) -> Boolean = { false },
    storedPaint: Set<String> = emptySet(),
): ElementParams? {
    val before = problems.size
    val p = if (where.isEmpty()) "" else "$where: "
    if (fields.name.isEmpty() || fields.name.length > 100) problems += "${p}name must be 1–100 characters"
    for ((n, v) in positionNames.zip(listOf(fields.positionX, fields.positionY, fields.positionZ))) {
        if (!v.isFinite() || v < -500.0 || v > 500.0) problems += "${p}$n must be between -500.0 and 500.0 metres"
    }
    if (!fields.yawDeg.isFinite() || fields.yawDeg < -360.0 || fields.yawDeg > 360.0) problems += "${p}yawDeg must be between -360.0 and 360.0 degrees"
    val sizes = listOf("widthM" to fields.widthM, "depthM" to fields.depthM, "heightM" to fields.heightM)
    for ((n, v) in sizes) {
        if (!v.isFinite() || v < 0.0 || v > 500.0) problems += "${p}$n must be between 0.0 and 500.0 metres"
    }
    if (fields.kind == StageElementKind.SEATING) {
        if (sizes.any { it.second != 0.0 }) {
            problems += "${p}a SEATING element's size comes from its rows and seats: leave widthM, depthM and heightM out"
        }
    } else {
        sizes.filter { it.second == 0.0 }.forEach { (n, _) -> problems += "${p}$n must be greater than 0: every kind but SEATING has a size" }
    }
    val params = parseElementParams(
        fields.kind, fields.params, fields.widthM, fields.heightM,
        if (where.isEmpty()) "params" else "$where.params", problems,
        imageStored = { it in storedPaint || imageStored(it) },
    )
    val region = (params as? PlatformParams)?.regionUuid
    if (region != null && region != storedRegionUuid && region !in regionUuids) {
        problems += "${p}params.regionUuid names no stage region in this project"
    }
    return params.takeIf { problems.size == before }
}

// ─── Seats ────────────────────────────────────────────────────────────────────────────────────

/** A seated eye, above the seat's base. The prototype's number, and roughly a seated adult's. */
const val SEATED_EYE_HEIGHT_M = 1.15

/**
 * Where a seat view looks when its row carries no target: the stage's centre line, 2.4 m upstage of
 * the edge and 0.9 m above the deck — where the action is in a proscenium hall.
 */
val DEFAULT_SEAT_TARGET = StagePoint(0.0, 2.4, 0.9)

const val DEFAULT_SEAT_FOV_DEG = 52.0
const val DEFAULT_EYE_FOV_DEG = 50.0
val VIEWPOINT_FOV_RANGE_DEG = 15.0..90.0

data class StagePoint(val x: Double, val y: Double, val z: Double)

/** One seat of a seating element: its id (`F6`) and its base, in stage coordinates. */
data class SeatPoint(val id: String, val row: Char, val number: Int, val base: StagePoint)

/** A seating element's pose, for placing its seats. */
data class ElementPose(val x: Double, val y: Double, val z: Double, val yawDeg: Double)

private val SEAT_ID = Regex("^([A-Za-z])([1-9][0-9]{0,2})$")

/** A seat id's row letter and number, or null when it is not one (`F6`, `a12`). */
fun parseSeatId(id: String): Pair<Char, Int>? {
    val m = SEAT_ID.matchEntire(id.trim()) ?: return null
    return m.groupValues[1].uppercase()[0] to m.groupValues[2].toInt()
}

/** Seat [row][number]'s base, or null when this seating has no such seat. */
fun SeatingParams.seat(pose: ElementPose, row: Char, number: Int): SeatPoint? {
    val r = row - firstRow.first()
    if (r !in 0 until rows || number !in 1..seatsPerRow) return null
    val lx = seatOffsetM(number)
    val ly = -r * rowPitchM
    val yaw = Math.toRadians(pose.yawDeg)
    val base = StagePoint(
        pose.x + lx * cos(yaw) - ly * sin(yaw),
        pose.y + lx * sin(yaw) + ly * cos(yaw),
        pose.z + r * rakeM,
    )
    return SeatPoint("$row$number", row, number, base)
}

/** Seat [number]'s offset along its row (local X) from the row's centre, its aisles counted. */
fun SeatingParams.seatOffsetM(number: Int): Double {
    val span = (seatsPerRow - 1) * seatPitchM + aisles.sumOf { it.widthM }
    val before = aisles.filter { it.afterSeat < number }.sumOf { it.widthM }
    return (number - 1) * seatPitchM + before - span / 2
}

fun SeatingParams.seat(pose: ElementPose, id: String): SeatPoint? =
    parseSeatId(id)?.let { (row, number) -> seat(pose, row, number) }

/** Where a seated eye is: above the seat, a hand's width behind its front edge. */
fun SeatPoint.eye(pose: ElementPose): StagePoint {
    val yaw = Math.toRadians(pose.yawDeg)
    // 5 cm towards the back of the seat, which is local −Y.
    return StagePoint(base.x + 0.05 * sin(yaw), base.y - 0.05 * cos(yaw), base.z + SEATED_EYE_HEIGHT_M)
}

/**
 * One viewpoint as a write proposes to store it, checked whole by [validateStageViewpoint] — shared
 * by the REST routes and `set_scene` for [StageElementFields]'s reason.
 */
data class StageViewpointFields(
    val name: String,
    val kind: StageViewpointKind,
    val eye: StagePoint?,
    val target: StagePoint?,
    val fovDeg: Double?,
    val seatElementUuid: UUID?,
    val seatId: String?,
)

/** A seating element as a viewpoint's check needs it: its seats. */
class SeatingElement(val name: String, val pose: ElementPose, val params: SeatingParams)

/**
 * [fields] checked whole. [storedSeat] is the seat the view already names, which may dangle — its
 * seating force-deleted (`?force=true`) or reshaped since. It is let stand while the write leaves it
 * unchanged, as [validateStageElement] lets a platform's stored region stand: a dangling seat reads as
 * no seat, and a rename or a new lens must not be refused for a reference the write does not touch.
 * Naming a different seat is checked as any new one is.
 */
fun validateStageViewpoint(
    fields: StageViewpointFields,
    seating: (UUID) -> SeatingElement?,
    where: String,
    problems: MutableList<String>,
    storedSeat: Pair<UUID, String>? = null,
) {
    val p = if (where.isEmpty()) "" else "$where: "
    if (fields.name.isEmpty() || fields.name.length > 100) problems += "${p}name must be 1–100 characters"
    fun point(label: String, v: StagePoint?) {
        if (v == null) return
        if (listOf(v.x, v.y, v.z).any { !it.isFinite() || it < -500.0 || it > 500.0 }) {
            problems += "${p}$label must lie within ±500 m"
        }
    }
    point("eye", fields.eye)
    point("target", fields.target)
    fields.fovDeg?.let {
        if (!it.isFinite() || it !in VIEWPOINT_FOV_RANGE_DEG) {
            problems += "${p}fovDeg must be between ${VIEWPOINT_FOV_RANGE_DEG.start} and ${VIEWPOINT_FOV_RANGE_DEG.endInclusive} degrees"
        }
    }
    when (fields.kind) {
        StageViewpointKind.ORBIT, StageViewpointKind.EYE -> {
            if (fields.eye == null) problems += "${p}an ${fields.kind} view needs an eye"
            if (fields.target == null) problems += "${p}an ${fields.kind} view needs a target"
            if (fields.eye != null && fields.eye == fields.target) problems += "${p}the eye and the target are the same point"
            if (fields.seatElementUuid != null || fields.seatId != null) problems += "${p}only a SEAT view names a seat"
            if (fields.kind == StageViewpointKind.ORBIT && fields.fovDeg != null) {
                problems += "${p}an ORBIT view uses the orbit camera's lens: leave fovDeg out"
            }
        }
        StageViewpointKind.SEAT -> {
            if (fields.eye != null) problems += "${p}a SEAT view's eye is its seat's: leave eye out"
            val uuid = fields.seatElementUuid
            val id = fields.seatId
            if (uuid == null || id == null) {
                problems += "${p}a SEAT view names a seating element and a seat"
            } else if (storedSeat != null && storedSeat.first == uuid && storedSeat.second.equals(id.trim(), ignoreCase = true)) {
                // The seat it already names, unchanged: let stand even if its seating has gone.
            } else {
                val element = seating(uuid)
                if (element == null) {
                    problems += "${p}the seat's element is not a seating element in this project"
                } else if (element.params.seat(element.pose, id) == null) {
                    val last = element.params.firstRow.first() + (element.params.rows - 1)
                    problems += "${p}'${element.name}' has no seat '$id' (rows ${element.params.firstRow}–$last, " +
                        "seats 1–${element.params.seatsPerRow})"
                }
            }
        }
    }
}
