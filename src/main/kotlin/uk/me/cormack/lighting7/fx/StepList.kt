package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/** Palette Steps' registry id (effects plan D8, §3.6). */
const val PALETTE_STEPS_ID = "PaletteSteps"

/** Slot Steps' registry id (effects plan D8, §3.6). */
const val SLOT_STEPS_ID = "SlotSteps"

/**
 * The category the Steps FX, Palette Steps and Slot Steps, register under: the library's Steps
 * family (terminology plan D23). It names no attribute family on purpose, since each sits in the
 * family of what it steps, and a stored one carries that family's category ([stepsFxFamily]).
 */
const val STEPS_CATEGORY = "steps"

/**
 * The family a Steps FX is stored and stamped under, or null for any other FX, whose own family
 * stands: Slot Steps steps a setting, so Beam; Palette Steps the family of its palettes, which only
 * the caller can name ([paletteFxFamily], null when it cannot).
 */
fun stepsFxFamily(fxId: String, paletteFxFamily: String?): String? = when {
    fxId.equals(SLOT_STEPS_ID, ignoreCase = true) -> "beam"
    fxId.equals(PALETTE_STEPS_ID, ignoreCase = true) -> paletteFxFamily
    else -> null
}

/**
 * The property sentinels an effect registration may declare and the client offers an effect
 * through (`fxTargetProperties.ts`): `slider` and `setting` name a kind of property, never one.
 */
val FX_PROPERTY_SENTINELS: Set<String> = setOf("slider", "setting")

/**
 * What a step effect steps through (effects plan §3.6), resolved **per head** by the target that
 * applies an [FxOutput.Steps]: no single value of a step is right for every head — a position
 * palette lands on each head's own pan and tilt, a slot name on each head's own wheel level.
 */
interface StepList {
    /** How many steps there are. An empty list steps through nothing and every head holds. */
    val size: Int

    /**
     * Step [index]'s value for [propertyName] on [fixture], or null where this head cannot take it —
     * a head without that slot, a palette that names no value for the property — which then holds
     * what is underneath for that step. Called from the tick loop: never opens a transaction.
     */
    fun valueFor(index: Int, fixture: GroupableFixture, propertyName: String): CueAssignmentResolver.PropertyValue?

    /**
     * Step [index] as a colour on [fixture], or null where the head holds: what the colour target
     * reads. A step may name only part of a colour — an emitter-only palette — and [StepColour.over]
     * fills the rest from what is underneath.
     */
    fun colourFor(index: Int, fixture: GroupableFixture): StepColour? =
        (valueFor(index, fixture, PaletteProperty.COLOUR.propertyName) as? CueAssignmentResolver.PropertyValue.Colour)
            ?.value?.let(StepColour::whole)

    companion object {
        /** Steps through nothing. */
        val EMPTY: StepList = object : StepList {
            override val size = 0
            override fun valueFor(index: Int, fixture: GroupableFixture, propertyName: String) = null
        }
    }
}

/**
 * A colour step as far as it goes: the RGB a colour row mixed for this head (its emitters with it,
 * as the row resolved them), and any emitter an explicit row names. What it leaves null is the
 * head's own underneath — a UV-only palette moves the UV and nothing else, as a layer of it does.
 */
data class StepColour(
    val rgb: ExtendedColour?,
    val white: UByte? = null,
    val amber: UByte? = null,
    val uv: UByte? = null,
) {
    /** This step on a head that holds [base]: what the step does not name stays as it is. */
    inline fun over(base: () -> ExtendedColour): ExtendedColour {
        val under = rgb ?: base()
        return ExtendedColour(
            under.color,
            white = white ?: under.white,
            amber = amber ?: under.amber,
            uv = uv ?: under.uv,
        )
    }

    companion object {
        /** A colour named whole. */
        fun whole(colour: ExtendedColour) = StepColour(colour)
    }
}

/**
 * Slot Steps' list: option names of a setting (a gobo wheel's *Gobo 3*, a colour wheel's *Red*),
 * looked up in **each head's own** setting options, so two models with *Gobo 3* at different levels
 * each land on their own. A name matches an option's name or its display form, ignoring case,
 * spaces and underscores — `GOBO_3`, `Gobo 3` and `gobo3` are one name. A head with no such option
 * holds what is underneath for that step.
 */
class SlotStepList(val names: List<String>) : StepList {
    override val size: Int get() = names.size

    private val wanted = names.map(::normaliseSlotName)

    /**
     * Each option set's level per step, keyed by the class of its options — a setting's options are
     * its model's enum, so the key is stable across the tick's fresh fixture objects and the map
     * holds one entry per wheel model the effect has met.
     */
    private val levelsByOptions = ConcurrentHashMap<Class<*>, List<UByte?>>()

    override fun valueFor(index: Int, fixture: GroupableFixture, propertyName: String): CueAssignmentResolver.PropertyValue? {
        if (index !in names.indices) return null
        val setting = PropertyChannelWriter.resolveProperty(fixture, propertyName)?.value as? DmxFixtureSetting<*>
            ?: return null
        return levelFor(index, setting)?.let(CueAssignmentResolver.PropertyValue::Setting)
    }

    /** Step [index]'s level on [setting], a head's own — what the setting target asks, setting in hand. */
    fun levelFor(index: Int, setting: DmxFixtureSetting<*>): UByte? {
        if (index !in names.indices) return null
        return levelsFor(setting)[index]
    }

    private fun levelsFor(setting: DmxFixtureSetting<*>): List<UByte?> =
        levelsByOptions.getOrPut(optionsKey(setting)) {
            val byName = HashMap<String, UByte>()
            for (option in setting.sortedValues) byName.putIfAbsent(normaliseSlotName(option.name), option.level)
            wanted.map { byName[it] }
        }

    /** How many option sets are cached — one per wheel model met; a test reads it. */
    internal val cachedOptionSets: Int get() = levelsByOptions.size

    override fun equals(other: Any?): Boolean = other is SlotStepList && other.names == names
    override fun hashCode(): Int = names.hashCode()
    override fun toString(): String = "SlotStepList($names)"
}

/** The class a setting's options are — an enum's declaring class, so a value with a body counts as its enum. */
private fun optionsKey(setting: DmxFixtureSetting<*>): Class<*> {
    val first: Any = setting.sortedValues.first()
    return (first as? Enum<*>)?.declaringJavaClass ?: first.javaClass
}

/** A slot name as [SlotStepList] compares it: lower case, no spaces, underscores or dashes. */
internal fun normaliseSlotName(name: String): String =
    name.lowercase().filterNot { it == ' ' || it == '_' || it == '-' }

/**
 * One step of Palette Steps: a generic value palette's rows, as intents, read once off the palette
 * when the effect resolved its parameters (`TypedParams.paletteSteps`).
 */
data class PaletteStep(
    val paletteUuid: java.util.UUID,
    val name: String,
    val rows: List<Pair<String, PaletteIntent>>,
)

/**
 * Palette Steps' list: generic value palettes, each resolved **for the head in hand** through
 * [PaletteResolver] — the one answer to what a palette does on a head, which the cook, *Apply as
 * values* and the editor's panel also ask. A position palette lands on each head's own pan and tilt
 * whether it is centre-relative (`pos:`) or in degrees (`deg:`); a beam role on its own range; a
 * colour mixed for that head's emitters, so an RGBW head and an RGB one each get their own.
 *
 * The target's property picks the row: the colour target reads the palette's colour family (the hex
 * and any explicit emitter), the position target its position row, any other target the row naming
 * its property. A palette with no such row holds what is underneath for that step.
 *
 * Resolved per head once and kept: a resolution walks the head's properties by reflection, and the
 * tick asks every head every frame. The tick builds fresh fixture objects each frame, so a head is
 * kept by its key and property and re-resolved only when the class under that key changes (a
 * repatch to another model); what a palette resolves to on a head is its model's, not its object's.
 * The list is rebuilt whenever a palette it names is edited (the parameters' version moves).
 */
class PaletteStepList(val steps: List<PaletteStep>) : StepList {
    override val size: Int get() = steps.size

    private class Cached(val model: Class<*>, val values: Array<Any?>)

    private val cache = ConcurrentHashMap<String, Cached>()

    override fun valueFor(index: Int, fixture: GroupableFixture, propertyName: String): CueAssignmentResolver.PropertyValue? {
        if (index !in steps.indices) return null
        if (isColourProperty(propertyName)) {
            return colourFor(index, fixture)?.let { CueAssignmentResolver.PropertyValue.Colour(it.over { ExtendedColour.BLACK }) }
        }
        return resolved(fixture, propertyName) { resolveStep(it, fixture, propertyName) }[index]
            as CueAssignmentResolver.PropertyValue?
    }

    override fun colourFor(index: Int, fixture: GroupableFixture): StepColour? {
        if (index !in steps.indices) return null
        return resolved(fixture, COLOUR_KEY) { resolveColour(it, fixture) }[index] as StepColour?
    }

    private inline fun resolved(fixture: GroupableFixture, property: String, resolve: (PaletteStep) -> Any?): Array<Any?> {
        val key = "${fixture.targetKey}|$property"
        val model = fixture.javaClass
        cache[key]?.takeIf { it.model == model }?.let { return it.values }
        val values = Array(steps.size) { resolve(steps[it]) }
        cache[key] = Cached(model, values)
        return values
    }

    /** How many heads (by key and property) are cached; a test reads it. */
    internal val cachedHeads: Int get() = cache.size

    override fun equals(other: Any?): Boolean = other is PaletteStepList && other.steps == steps
    override fun hashCode(): Int = steps.hashCode()
    override fun toString(): String = "PaletteStepList(${steps.map { it.name }})"

    companion object {
        private const val COLOUR_KEY = "#colour"

        private fun isColourProperty(propertyName: String): Boolean {
            val lower = propertyName.lowercase()
            return lower == "colour" || lower == "color" || lower == "rgbcolour"
        }

        /**
         * One palette on one head, for the property a target applies. Fitted media is not consulted:
         * it decides only which **wheel** slot a colour snaps to, and a colour target drives a mixed
         * colour — a wheel head is no colour target's member.
         */
        internal fun resolveStep(
            step: PaletteStep,
            fixture: GroupableFixture,
            propertyName: String,
        ): CueAssignmentResolver.PropertyValue? {
            if (isColourProperty(propertyName)) {
                return resolveColour(step, fixture)?.let { CueAssignmentResolver.PropertyValue.Colour(it.over { ExtendedColour.BLACK }) }
            }
            val property = if (propertyName.equals("position", ignoreCase = true)) {
                PaletteProperty.POSITION
            } else {
                PaletteProperty.ofOrNull(propertyName)
            }
            val row = step.rows.firstOrNull { (name, _) ->
                if (property != null) PaletteProperty.ofOrNull(name) == property else name.equals(propertyName, ignoreCase = true)
            } ?: return null
            return PaletteResolver.resolve(fixture, propertyName, row.second, null).value
        }

        /**
         * The palette's colour family on this head, as a layer of it would put it there: refused
         * whole where the head lacks an emitter the palette names ([PaletteResolver.unmetColourRequirement],
         * the cook's own rule — half a colour is a wrong colour), else the hex mixed for its
         * emitters and each explicit emitter row over the emitter it names. What the palette does
         * not name is left to the head underneath ([StepColour.over]).
         */
        internal fun resolveColour(step: PaletteStep, fixture: GroupableFixture): StepColour? {
            val colourRows = step.rows.filter { PaletteProperty.ofOrNull(it.first)?.family == PropertyMaskGroup.COLOUR }
            if (colourRows.isEmpty()) return null
            if (PaletteResolver.unmetColourRequirement(fixture, colourRows, null) != PaletteResolver.ColourRequirement.Met) {
                return null
            }
            var rgb: ExtendedColour? = null
            var white: UByte? = null
            var amber: UByte? = null
            var uv: UByte? = null
            for ((name, intent) in colourRows) {
                val property = PaletteProperty.ofOrNull(name) ?: continue
                val value = PaletteResolver.resolve(fixture, property.propertyName, intent, null).value
                when (property) {
                    PaletteProperty.COLOUR -> rgb = (value as? CueAssignmentResolver.PropertyValue.Colour)?.value ?: rgb
                    PaletteProperty.WHITE -> white = stepByte(value) ?: white
                    PaletteProperty.AMBER -> amber = stepByte(value) ?: amber
                    PaletteProperty.UV -> uv = stepByte(value) ?: uv
                    else -> Unit
                }
            }
            if (rgb == null && white == null && amber == null && uv == null) return null
            return StepColour(rgb, white, amber, uv)
        }
    }
}

/**
 * The step a phase is on (effects plan §3.6): [steps] in equal segments of the cycle, each held for
 * `1 − fadeRatio` of its segment and crossing to the next over the rest — Colour Cycle's rule, so a
 * fade ratio means the same on both. The last step crosses back to the first. A list of one is that
 * step throughout; an empty one is [FxOutput.Steps] over nothing, which every head holds through.
 *
 * Direction needs nothing here: a Backward or Bounce phase runs the segments back, and the blend
 * with them, so a reversed cycle crosses from each step into the one before it.
 */
fun stepsAt(phase: Double, steps: StepList, fadeRatio: Double = 0.0): FxOutput.Steps {
    val n = steps.size
    if (n <= 1) return FxOutput.Steps(0, 0, 0.0, steps)
    val p = phase - floor(phase)
    val scaled = p * n
    val from = scaled.toInt().coerceIn(0, n - 1)
    val within = scaled - from
    val fade = fadeRatio.coerceIn(0.0, 1.0)
    val hold = 1.0 - fade
    val blend = if (fade <= 0.0 || within < hold) 0.0 else ((within - hold) / fade).coerceIn(0.0, 1.0)
    return FxOutput.Steps(from, (from + 1) % n, blend, steps)
}

/**
 * What a Palette Steps list is as one effect (effects plan §3.6, §4.4): the one family of its
 * palettes, the category it is stored and offered under, and the property it drives when the row
 * that holds it names none — an effect palette's, or any other stored without one.
 */
data class PaletteStepsShape(val family: PropertyMaskGroup, val fxFamily: String, val property: String)

/** The FX family an attribute family is offered under — [maskGroupForFxFamily] read backwards. */
fun fxFamilyForMaskGroup(family: PropertyMaskGroup): String = when (family) {
    PropertyMaskGroup.INTENSITY -> "intensity"
    PropertyMaskGroup.COLOUR -> "colour"
    PropertyMaskGroup.POSITION -> "position"
    PropertyMaskGroup.BEAM -> "beam"
}

/**
 * [steps] as one effect, or the reason they cannot be: every palette in one family, and — but for
 * colour, whose palettes are one colour whatever rows they spell it with — a property every one of
 * them names, the first in the palette vocabulary's order where they share several (a Level and a
 * Strobe in every Intensity palette step the level).
 */
fun paletteStepsShape(steps: List<PaletteStep>): Result<PaletteStepsShape> {
    if (steps.isEmpty()) return Result.failure(IllegalArgumentException("Palette Steps needs at least one palette"))
    val families = steps.map { step ->
        step.rows.mapNotNull { PaletteProperty.ofOrNull(it.first)?.family }.distinct().singleOrNull()
            ?: return Result.failure(IllegalArgumentException("Palette '${step.name}' is in no one family"))
    }
    val family = families.distinct().singleOrNull()
        ?: return Result.failure(
            IllegalArgumentException(
                "Palette Steps steps through palettes of one family — these are " +
                    families.distinct().joinToString(" and ") { it.name.lowercase() },
            ),
        )
    if (family == PropertyMaskGroup.COLOUR) {
        return Result.success(PaletteStepsShape(family, fxFamilyForMaskGroup(family), "colour"))
    }
    val property = PaletteProperty.forFamily(family).firstOrNull { property ->
        steps.all { step -> step.rows.any { PaletteProperty.ofOrNull(it.first) == property } }
    } ?: return Result.failure(
        IllegalArgumentException("These palettes name no ${family.name.lowercase()} property in common, so the steps have nothing to step"),
    )
    return Result.success(PaletteStepsShape(family, fxFamilyForMaskGroup(family), property.propertyName))
}

/** Every entry of a Palette Steps `palettes` parameter through [source], an entry that cannot be a step dropped. */
fun PaletteSource.paletteStepsOf(parameter: String?): List<PaletteStep> =
    parameter.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull(::paletteStep)
