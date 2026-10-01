package uk.me.cormack.lighting7.fixture.lantern

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatchPlacement
import uk.me.cormack.lighting7.models.MAX_LANTERN_ID_LENGTH

/**
 * One framing shutter (or barn door): how far in it is and how it is angled. The four are always
 * in the order [BLADE_NAMES] — named for the edge of the **light** they cut, not their place in the
 * gate, where a profile's image is upside down (the design record's item 10).
 */
@Serializable
data class ShutterBlade(
    /** 0 is out; 1 closes the whole beam. The fraction of the field's diameter the blade covers,
     *  so 0.5 reaches the centre. */
    val depth: Double = 0.0,
    /** The blade's turn about its own edge's middle, ±[MAX_BLADE_ANGLE_DEG]. */
    val angleDeg: Double = 0.0,
)

/** The blades' order on the wire and in the column. */
val BLADE_NAMES = listOf("top", "bottom", "left", "right")

const val MAX_BLADE_ANGLE_DEG = 30.0

/**
 * A lantern's **focus data** (stage-view plan D9, D14): which lantern it is and how it was focused.
 * The same seven fields sit on a patch and on each of its extra placements, so a pair on one dimmer
 * is two lanterns focused separately. Physical, portable, and never in a look — a fixture whose
 * optics are on channels drives them from its looks instead.
 *
 * Every field null is an unfocused lantern: the library's default for its kind, blades out, iris
 * open, its own edge and field. A placement's null [lanternType] takes its patch's lantern; its
 * other six are its own.
 */
data class LanternFocus(
    val lanternType: String? = null,
    val zoomDeg: Double? = null,
    val lampRotationDeg: Double? = null,
    /** Exactly four when set, in [BLADE_NAMES] order. */
    val shutters: List<ShutterBlade>? = null,
    val gateRotationDeg: Double? = null,
    val iris: Double? = null,
    val focusSoftness: Double? = null,
) {
    val isEmpty: Boolean
        get() = lanternType == null && zoomDeg == null && lampRotationDeg == null && shutters == null &&
            gateRotationDeg == null && iris == null && focusSoftness == null

    /**
     * This focus with every key in [sent] replaced by [parsed]'s value — a write's merge: an absent
     * key is kept, a sent null clears. The one overlay every write path uses (`resolvePatchFocus`).
     */
    fun overlay(sent: Set<String>, parsed: LanternFocus): LanternFocus = LanternFocus(
        lanternType = if ("lanternType" in sent) parsed.lanternType else lanternType,
        zoomDeg = if ("zoomDeg" in sent) parsed.zoomDeg else zoomDeg,
        lampRotationDeg = if ("lampRotationDeg" in sent) parsed.lampRotationDeg else lampRotationDeg,
        shutters = if ("shutters" in sent) parsed.shutters else shutters,
        gateRotationDeg = if ("gateRotationDeg" in sent) parsed.gateRotationDeg else gateRotationDeg,
        iris = if ("iris" in sent) parsed.iris else iris,
        focusSoftness = if ("focusSoftness" in sent) parsed.focusSoftness else focusSoftness,
    )

    companion object {
        /** The keys, as a PUT body, a bulk entry and a placement carry them. */
        val KEYS = listOf(
            "lanternType", "zoomDeg", "lampRotationDeg", "shutters", "gateRotationDeg", "iris", "focusSoftness",
        )

        /**
         * The focus fields of a JSON object — every key read plainly, absent and null both null —
         * with their ranges checked. The type-dependent rules ([typeProblems]) need the patch and run
         * where it is known. A wrong JSON type is a problem named, never a 500.
         */
        fun parse(obj: JsonObject, where: String = ""): Result<LanternFocus> {
            val problems = mutableListOf<String>()
            fun at(name: String) = if (where.isEmpty()) name else "$where.$name"
            fun number(name: String): Double? {
                val e = obj[name] ?: return null
                if (e is JsonNull) return null
                val p = e as? JsonPrimitive
                val v = p?.takeIf { !it.isString }?.doubleOrNull
                if (v == null || !v.isFinite()) {
                    problems += "${at(name)} must be a number"
                    return null
                }
                return v
            }
            val lanternType = obj["lanternType"]?.let { e ->
                if (e is JsonNull) return@let null
                val p = e as? JsonPrimitive
                if (p == null || !p.isString) {
                    problems += "${at("lanternType")} must be a string"
                    null
                } else p.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }
            val shutters = obj["shutters"]?.let { parseShutters(it, at("shutters"), problems) }
            val focus = LanternFocus(
                lanternType = lanternType,
                zoomDeg = number("zoomDeg"),
                lampRotationDeg = number("lampRotationDeg"),
                shutters = shutters,
                gateRotationDeg = number("gateRotationDeg"),
                iris = number("iris"),
                focusSoftness = number("focusSoftness"),
            )
            problems += focus.rangeProblems().map { if (where.isEmpty()) it else "$where.$it" }
            return if (problems.isEmpty()) Result.success(focus) else Result.failure(IllegalArgumentException(problems.joinToString("; ")))
        }

        private fun parseShutters(e: JsonElement, where: String, problems: MutableList<String>): List<ShutterBlade>? {
            if (e is JsonNull) return null
            val array = e as? JsonArray ?: run { problems += "$where must be an array of four {depth, angleDeg}"; return null }
            val out = mutableListOf<ShutterBlade>()
            array.forEachIndexed { i, b ->
                val blade = b as? JsonObject ?: run { problems += "$where[$i] must be an object"; return null }
                val unknown = blade.keys - setOf("depth", "angleDeg")
                if (unknown.isNotEmpty()) problems += "$where[$i] has unknown field(s) ${unknown.sorted().joinToString()}"
                fun n(name: String): Double {
                    val v = blade[name] ?: return 0.0
                    if (v is JsonNull) return 0.0
                    return (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
                        ?: run { problems += "$where[$i].$name must be a number"; 0.0 }
                }
                out += ShutterBlade(depth = n("depth"), angleDeg = n("angleDeg"))
            }
            return out
        }

        private val SHUTTERS_JSON = Json { encodeDefaults = true }
        private val SHUTTER_LIST = ListSerializer(ShutterBlade.serializer())

        /** The column's text for [blades]: compact JSON, every field written. Null for none. */
        fun shuttersToText(blades: List<ShutterBlade>?): String? =
            blades?.let { SHUTTERS_JSON.encodeToString(SHUTTER_LIST, it) }

        /**
         * The blades a column holds. Tolerant, as every reader of stored JSON here is: text that will
         * not parse, or is not four blades, reads as none rather than failing the patch.
         */
        fun shuttersFromText(text: String?): List<ShutterBlade>? {
            if (text.isNullOrBlank()) return null
            return runCatching { Json.decodeFromString(SHUTTER_LIST, text) }.getOrNull()?.takeIf { it.size == BLADE_NAMES.size }
        }
    }

    /** Problems with the ranges alone — nothing here needs to know the patch. */
    fun rangeProblems(): List<String> = buildList {
        lanternType?.let { if (it.length > MAX_LANTERN_ID_LENGTH) add("lanternType is longer than $MAX_LANTERN_ID_LENGTH characters") }
        zoomDeg?.let { if (it !in 1.0..180.0) add("zoomDeg must be between 1 and 180") }
        lampRotationDeg?.let { if (it !in -180.0..180.0) add("lampRotationDeg must be between -180 and 180") }
        gateRotationDeg?.let { if (it !in -180.0..180.0) add("gateRotationDeg must be between -180 and 180") }
        iris?.let { if (it !in 0.0..1.0) add("iris must be between 0 (closed) and 1 (open)") }
        focusSoftness?.let { if (it !in 0.0..1.0) add("focusSoftness must be between 0 (sharp) and 1 (soft)") }
        shutters?.let { blades ->
            if (blades.size != BLADE_NAMES.size) {
                add("shutters must be four blades — ${BLADE_NAMES.joinToString(", ")} — or null")
            }
            blades.forEachIndexed { i, b ->
                val name = BLADE_NAMES.getOrNull(i) ?: "#$i"
                if (b.depth !in 0.0..1.0) add("shutters[$i] ($name): depth must be between 0 (out) and 1 (closed)")
                if (b.angleDeg !in -MAX_BLADE_ANGLE_DEG..MAX_BLADE_ANGLE_DEG) {
                    add("shutters[$i] ($name): angleDeg must be between -${MAX_BLADE_ANGLE_DEG.toInt()} and ${MAX_BLADE_ANGLE_DEG.toInt()}")
                }
            }
        }
    }

    /**
     * What the patch's type and lantern refuse. A type that takes no lantern refuses every field
     * (it carries its own body, and its optics are its channels'); an id the library does not hold
     * is refused, since the write boundary is where a typo is cheapest; and a zoom must lie within
     * the lantern's range — the named one, else [inheritedLantern] (a placement's patch's), else
     * the default for [kind].
     */
    fun typeProblems(typeKey: String, kind: FixtureKind, inheritedLantern: String? = null): List<String> {
        if (isEmpty) return emptyList()
        val info = FixtureTypeRegistry.typeInfoForKey(typeKey)
        if (info?.acceptsLantern != true) {
            return listOf(
                "lanternType and the focus fields are only for a type hung with a lantern (such as generic-dimmer); " +
                    "'$typeKey' carries its own body, and its optics are its channels'",
            )
        }
        return buildList {
            if (lanternType != null && LanternLibrary.byId(lanternType) == null) {
                add("unknown lanternType '$lanternType' (the library holds ${LanternLibrary.all.joinToString { it.id }})")
                return@buildList
            }
            zoomProblem(kind, inheritedLantern)?.let { add(it) }
        }
    }

    /** Why [zoomDeg] does not fit the lantern it would be drawn on, or null when it fits (or is null). */
    fun zoomProblem(kind: FixtureKind, inheritedLantern: String? = null): String? {
        val zoom = zoomDeg ?: return null
        val lantern = LanternLibrary.effective(lanternType ?: inheritedLantern, kind)
            ?: return "zoomDeg needs a lantern with a zoom range; this unit names none"
        val range = lantern.zoom
            ?: return "zoomDeg needs a lantern with a zoom range; ${lantern.name} has a fixed ${fmt(lantern.fieldDeg)}° field"
        if (zoom !in range.minDeg..range.maxDeg) {
            return "zoomDeg ${fmt(zoom)} is outside ${lantern.name}'s ${fmt(range.minDeg)}–${fmt(range.maxDeg)}°"
        }
        return null
    }

    private fun fmt(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else v.toString()
}

/** The kind a patch is drawn as and classified by: its lantern's, then its override, then its type's. */
fun effectiveKind(typeKey: String, kindOverride: String?, lanternType: String?): FixtureKind? {
    LanternLibrary.byId(lanternType)?.let { return it.kind }
    kindOverride?.let { k -> FixtureKind.entries.firstOrNull { it.name == k } }?.let { return it }
    return FixtureTypeRegistry.typeInfoForKey(typeKey)?.kind
}

var DaoFixturePatch.focus: LanternFocus
    get() = LanternFocus(
        lanternType, zoomDeg, lampRotationDeg, LanternFocus.shuttersFromText(shutters), gateRotationDeg, iris, focusSoftness,
    )
    set(f) {
        lanternType = f.lanternType
        zoomDeg = f.zoomDeg
        lampRotationDeg = f.lampRotationDeg
        shutters = LanternFocus.shuttersToText(f.shutters)
        gateRotationDeg = f.gateRotationDeg
        iris = f.iris
        focusSoftness = f.focusSoftness
    }

var DaoFixturePatchPlacement.focus: LanternFocus
    get() = LanternFocus(
        lanternType, zoomDeg, lampRotationDeg, LanternFocus.shuttersFromText(shutters), gateRotationDeg, iris, focusSoftness,
    )
    set(f) {
        lanternType = f.lanternType
        zoomDeg = f.zoomDeg
        lampRotationDeg = f.lampRotationDeg
        shutters = LanternFocus.shuttersToText(f.shutters)
        gateRotationDeg = f.gateRotationDeg
        iris = f.iris
        focusSoftness = f.focusSoftness
    }
