package uk.me.cormack.lighting7.fixture.lantern

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.BodyArchetype
import uk.me.cormack.lighting7.fixture.FixtureKind

/**
 * A family of conventional lantern, and the [FixtureKind] a patch hung with one of them is — so
 * `kind_override` is **derived** from the lantern (stage-view plan §3.1) rather than set beside it.
 * A PC is a fresnel to every reader that asks only for a kind; the view tells them apart by the
 * lantern (a PC has no barn doors and a harder edge).
 */
@Serializable
enum class LanternFamily(val kind: FixtureKind) {
    PROFILE(FixtureKind.PROFILE),
    FRESNEL(FixtureKind.FRESNEL),
    PC(FixtureKind.FRESNEL),
    PAR(FixtureKind.PAR),
    FLOOD(FixtureKind.WASH),
    CYC(FixtureKind.WASH),
    DOWNLIGHT(FixtureKind.GENERIC),
}

/** A lantern's field-angle range, where it has one: a zoom profile's, or a fresnel's spot–flood. */
@Serializable
data class LanternZoom(val minDeg: Double, val maxDeg: Double)

/**
 * A PAR lamp's oval field, set by turning the lamp in the can (`lamp_rotation_deg`). [wideDeg] is
 * the lantern's field angle; [narrowDeg] is across it. GDTF cannot say this (the design record's
 * research), so the library does.
 */
@Serializable
data class LanternOval(val wideDeg: Double, val narrowDeg: Double)

/** What hangs on a lantern: which parts of the focus data it can take. */
@Serializable
data class LanternAccessories(
    /** Four framing shutters in the gate — a profile's. */
    val shutters: Boolean = false,
    /** Four barn doors in the colour-frame runners — a fresnel's. The same four slots as shutters. */
    val barnDoors: Boolean = false,
    /** An iris in the gate. */
    val iris: Boolean = false,
    val colourFrame: Boolean = false,
)

/**
 * One entry of the **lantern library** (stage-view plan session 7; D8): a conventional unit a
 * generic dimmer can be hung with, seeded from its maker's datasheet. Angles in degrees, sizes in
 * metres, and [lengthM] is along the barrel.
 */
@Serializable
data class Lantern(
    val id: String,
    val name: String,
    val maker: String,
    val family: LanternFamily,
    /** The body the Stage view builds it as — a disc-lensed archetype for all but a flood. */
    val archetype: BodyArchetype,
    val watts: Int? = null,
    val beamDeg: Double? = null,
    /** The field angle, and on a lantern with [zoom] the one drawn until a focus sets its own. */
    val fieldDeg: Double,
    val zoom: LanternZoom? = null,
    val oval: LanternOval? = null,
    val lensDiameterM: Double,
    val frameSizeM: Double? = null,
    val lengthM: Double,
    val widthM: Double,
    val heightM: Double,
    val accessories: LanternAccessories = LanternAccessories(),
    /** The kinds a generic dimmer with no lantern named is drawn as this one for. */
    val defaultFor: List<FixtureKind> = emptyList(),
    val discontinued: Boolean = false,
    /** Where the numbers came from: the datasheet to check them against. */
    val source: String,
) {
    /** The kind a patch hung with this lantern is — its [family]'s. */
    val kind: FixtureKind get() = family.kind
}

/**
 * The **lantern library**: a catalogue shipped with the desk as a resource
 * (`src/main/resources/lanterns/library.json`), the way the built-in effects are — so a typo fixed
 * in it is fixed for every show at once (D8). Per-project custom lanterns come later
 * (`FU-LANTERNS-CUSTOM`).
 *
 * A generic dimmer names one by [Lantern.id] (`fixture_patches.lantern_type`, and each extra
 * placement's own); one that names none is drawn as the library's default for its kind
 * ([defaultFor]). An id the library does not hold — an archive from a desk whose library was
 * newer — is kept as stored and drawn by kind, as if it named none.
 */
object LanternLibrary {
    private val logger = LoggerFactory.getLogger(LanternLibrary::class.java)
    private const val RESOURCE = "lanterns/library.json"

    /** Every lantern, in the resource's order (grouped by family). */
    val all: List<Lantern> by lazy { load() }

    private val byId: Map<String, Lantern> by lazy { all.associateBy { it.id } }

    private val defaults: Map<FixtureKind, Lantern> by lazy {
        buildMap { all.forEach { l -> l.defaultFor.forEach { putIfAbsent(it, l) } } }
    }

    fun byId(id: String?): Lantern? = id?.let { byId[it] }

    /** The lantern a generic dimmer of [kind] that names none is drawn as, or null (drawn by kind). */
    fun defaultFor(kind: FixtureKind): Lantern? = defaults[kind]

    /** The lantern a patch or placement is drawn as: the one it names, else its kind's default. */
    fun effective(lanternType: String?, kind: FixtureKind): Lantern? = byId(lanternType) ?: defaultFor(kind)

    /** Parse and check [text]; answers the lanterns or throws naming every problem. */
    internal fun parse(text: String): List<Lantern> {
        val lanterns = Json.decodeFromString(ListSerializer(Lantern.serializer()), text)
        val problems = lanterns.flatMap { problemsOf(it) }.toMutableList()
        lanterns.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { problems += "id '$it' appears twice" }
        lanterns.flatMap { l -> l.defaultFor.map { it to l.id } }.groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .forEach { (kind, ids) -> problems += "$kind is the default of ${ids.joinToString(" and ")}" }
        require(problems.isEmpty()) { "Invalid lantern library: ${problems.joinToString("; ")}" }
        return lanterns
    }

    private fun problemsOf(l: Lantern): List<String> = buildList {
        val at = "lantern '${l.id}'"
        if (!ID_PATTERN.matches(l.id)) add("$at: id must be lower-case letters, digits and dashes")
        if (l.name.isBlank()) add("$at: name is blank")
        if (l.fieldDeg !in 1.0..180.0) add("$at: fieldDeg ${l.fieldDeg} is outside 1–180")
        l.beamDeg?.let { if (it <= 0.0 || it > l.fieldDeg) add("$at: beamDeg $it must be within the field") }
        l.zoom?.let { z ->
            if (z.minDeg <= 0.0 || z.maxDeg > 180.0 || z.minDeg >= z.maxDeg) add("$at: zoom ${z.minDeg}–${z.maxDeg} is not a range")
            if (l.fieldDeg !in z.minDeg..z.maxDeg) add("$at: fieldDeg ${l.fieldDeg} is outside its zoom ${z.minDeg}–${z.maxDeg}")
        }
        l.oval?.let { o ->
            if (l.family != LanternFamily.PAR) add("$at: only a PAR has an oval beam")
            if (o.wideDeg != l.fieldDeg) add("$at: an oval's wideDeg must be its fieldDeg")
            if (o.narrowDeg <= 0.0 || o.narrowDeg > o.wideDeg) add("$at: an oval's narrowDeg must be within its wideDeg")
            if (l.zoom != null) add("$at: an oval lamp has no zoom")
        }
        if (l.archetype !in STATIC_ARCHETYPES) add("$at: archetype ${l.archetype} is not a conventional's")
        listOf("lensDiameterM" to l.lensDiameterM, "lengthM" to l.lengthM, "widthM" to l.widthM, "heightM" to l.heightM)
            .filter { (_, v) -> v <= 0.0 || v > 3.0 }
            .forEach { (name, v) -> add("$at: $name $v is outside 0–3 m") }
        if (l.accessories.shutters && l.accessories.barnDoors) add("$at: shutters and barn doors share four slots; pick one")
        if (l.defaultFor.any { it == FixtureKind.MOVING_HEAD || it == FixtureKind.SCANNER }) {
            add("$at: a conventional cannot be a mover's default")
        }
    }

    private fun load(): List<Lantern> {
        val text = LanternLibrary::class.java.classLoader.getResource(RESOURCE)?.readText()
        if (text == null) {
            logger.error("{} not found — the lantern library is empty and every lantern is drawn by kind", RESOURCE)
            return emptyList()
        }
        return parse(text)
    }

    private val ID_PATTERN = Regex("[a-z0-9]+(-[a-z0-9]+)*")

    private val STATIC_ARCHETYPES = setOf(
        BodyArchetype.PROFILE, BodyArchetype.BOX_PROFILE, BodyArchetype.FRESNEL, BodyArchetype.PAR,
        BodyArchetype.FLOOD, BodyArchetype.DOWNLIGHT,
    )
}
