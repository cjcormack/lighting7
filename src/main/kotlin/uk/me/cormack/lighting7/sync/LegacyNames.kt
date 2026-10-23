package uk.me.cormack.lighting7.sync

import uk.me.cormack.lighting7.fx.EffectDirection
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.PALETTE_STEPS_ID
import uk.me.cormack.lighting7.fx.PaletteStep
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.SpreadShape
import uk.me.cormack.lighting7.fx.TemplateResolver
import uk.me.cormack.lighting7.fx.genericColourRows
import uk.me.cormack.lighting7.fx.isTemplateColourRef
import uk.me.cormack.lighting7.fx.normalizeEffectType
import uk.me.cormack.lighting7.fx.paletteStepsShape
import uk.me.cormack.lighting7.fx.parseTemplateColourRefUuid
import uk.me.cormack.lighting7.fx.parseTemplateIntent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * The names an archive or an older database may still hold, mapped by field to today's
 * (terminology plan P3, P5). This is the one place an old name lives: the registry, the REST layer
 * and the client know only today's. An old name never returns with a new meaning in the same
 * field — a user FX definition may not take a retired id ([retiredFxIds]) — so every map here
 * applies to any archive, whatever its `formatVersion`.
 *
 * [rewriteFx] is §4.2's rewrite of one stored FX, shared by the importer and database step 4
 * (`state/FxLibraryMigration.kt`), so an archive and a database written before the FX library
 * changed arrive at the same rows.
 */
object LegacyNames {
    /**
     * The FX library an archive's FX are written against, which the exporter stamps on
     * `project.json` (`ProjectJson.fxLibrary`). 2 is the terminology plan's session 3; an archive
     * without it is 1.
     */
    const val FX_LIBRARY = 2

    /** `effectType`: FX ids renamed for their names (D10–D13). */
    private val FX_IDS = mapOf(
        "Triangle" to "Ramp",
        "ColourPulse" to "ColourWave",
        "Strobe" to "DimmerStrobe",
        "ZoomBreathe" to "ZoomWave",
    )

    /** `distribution`: Split gave Edges in's offsets exactly (D18). */
    private val ORDERS = mapOf("SPLIT" to "EDGES_IN")

    /** `effectMode` of a script FX definition: the composite mode went (D27). */
    private val EFFECT_MODES = mapOf("COMPOSITE" to "STANDARD")

    /** `scriptType`: the composite calculate body went with its mode (D27). */
    private val SCRIPT_TYPES = mapOf("FX_CALC_COMPOSITE" to "FX_CALC")

    private const val COMPOSITE = "composite"

    /** The FX that took a `pingPong` parameter and now bounce by Direction (D24). */
    val bouncedFxIds: Set<String> = setOf("ColourFade", "FocusPull", "Sweep", "PanSweep", "TiltSweep")

    private const val BREATHE = "Breathe"
    private const val SINE_WAVE = "SineWave"
    private const val COLOUR_CYCLE = "ColourCycle"
    private const val LIGHTNING_STRIKE = "LightningStrike"
    private const val PING_PONG = "pingPong"
    private const val COLOURS = "colours"
    private const val PALETTES = "palettes"
    private const val FADE_RATIO = "fadeRatio"

    /** What a list of colours that names none is written as: black, the empty list's output. */
    private const val NO_COLOUR = "#000000"

    /** Lightning Strike's colour parameters, computed and never applied while it was a composite. */
    private val LIGHTNING_COLOURS = setOf("flashColour", "decayColour", "ambientColour")

    /** Every FX id the registry no longer knows: a script may still name one, and no FX definition may take one. */
    val retiredFxIds: Set<String> get() = FX_IDS.keys + BREATHE

    /** Whether [effectId] is a retired FX id, matched as the registry matches one. */
    fun isRetiredFxId(effectId: String): Boolean = retiredFxIds.any { same(it, effectId) }

    /**
     * Why an FX being written names an old name, or null: a retired id, or a `pingPong` on an FX that
     * bounces by Direction. Every write boundary refuses one, so no row written today holds a name
     * the importer would rewrite (P3).
     */
    fun writeProblem(effectType: String, parameters: Map<String, String>): String? = when {
        same(effectType, BREATHE) -> "'$effectType' is gone: Sine Wave a quarter-cycle on (phase offset + 0.75) draws it"
        isRetiredFxId(effectType) -> "'$effectType' is now '${fxId(effectType)}'"
        bouncedFxIds.any { same(it, effectType) } && PING_PONG in parameters ->
            "'$effectType' takes no pingPong: it bounces by Direction (BOUNCE)"
        else -> null
    }

    /** Today's id for a stored `effectType`, matched as the registry matches one. */
    fun fxId(stored: String): String = FX_IDS.entries.firstOrNull { same(it.key, stored) }?.value ?: stored

    /** Today's Order for a stored `distribution`. */
    fun distribution(stored: String): String = ORDERS[stored.trim().uppercase()] ?: stored

    /**
     * Today's family category for a stored `category`: `composite` became the family of the output
     * the FX applied, which was its `outputType` — Lightning Strike's, a slider's, is Intensity's.
     */
    fun category(stored: String, outputType: String?): String =
        if (stored.trim().equals(COMPOSITE, ignoreCase = true)) {
            when (outputType?.uppercase()) {
                "COLOUR" -> "colour"
                "POSITION" -> "position"
                else -> "dimmer"
            }
        } else {
            stored
        }

    /** Today's mode for a stored `effectMode`, by name. */
    fun effectMode(stored: String): String = EFFECT_MODES[stored.trim().uppercase()] ?: stored

    /** Today's script type for a stored `scriptType`, by name. */
    fun scriptType(stored: String): String = SCRIPT_TYPES[stored.trim().uppercase()] ?: stored

    /** The fields of a stored FX that §4.2's rewrites read or move, whatever home it lives in. */
    data class StoredFx(
        val effectType: String,
        val category: String,
        val beatDivision: Double,
        val distribution: String,
        val phaseOffset: Double,
        val parameters: Map<String, String>,
        val direction: String,
        val nShot: Int?,
        val spread: Double,
    )

    /** A rewritten FX, and what changed about it in words for the log; [notes] say what output moved. */
    data class Rewrite(val fx: StoredFx, val changes: List<String>, val notes: List<String>)

    /** A palette as [rewriteFx] reads it, by its uuid: its rows, and whether it holds an FX instead. */
    data class Palette(val name: String, val rows: List<PaletteRow>, val holdsFx: Boolean)

    /** One palette row: its property, its intent as stored, and whether it is generic. */
    data class PaletteRow(val propertyName: String, val value: String, val deferred: Boolean)

    /** A script FX definition as [rewriteFx] reads one: its output type and its `colourList` parameters. */
    data class Definition(val outputType: String, val colourLists: Set<String>)

    /**
     * What [rewriteFx] reads beside the FX: the project's palettes by uuid, its script FX definitions
     * by effect id, and how a bouncing FX naming no `pingPong` reads ([absentPingPongIsOn]).
     *
     * [absentPingPongIsOn] is the one rewrite that is not by name. A bouncing FX written before
     * `pingPong` went and naming none ran with it on, its default; one written since names none and
     * means what its Direction says. Database step 4 runs once, before the database can hold the
     * second kind, so passes true; the importer passes true for an archive without
     * `ProjectJson.fxLibrary` [FX_LIBRARY], which was written before the parameter went.
     */
    class Context(
        val palettes: (UUID) -> Palette?,
        val definitions: (String) -> Definition?,
        val absentPingPongIsOn: Boolean,
    )

    /**
     * §4.2's rewrites of [fx], or null when it holds no old name.
     *
     * - A renamed id, `SPLIT` and the `composite` category take today's names; the category the
     *   family of the FX's output ([category]). A script FX definition's own id is never renamed.
     * - Breathe becomes Sine Wave a quarter-cycle on, Forward: Breathe is `sin²(πφ)`, Sine Wave at
     *   `φ + ¾` the same curve, and Breathe was symmetric, so its Direction never showed.
     * - `pingPong` goes. Blank or anything but `false` it was on (`TypedParams.boolean` reads it
     *   so), and absent as [Context.absentPingPongIsOn] says: the FX bounces by Direction at half the
     *   cycle, its phase offset, spread and runs doubled, since all three count in cycles. The phase
     *   offset is kept modulo 2, not 1: Bounce counts cycles, so its parity is part of where each head
     *   is. A spread above 1 stops at 2, and the Ping-pong Order reshapes the cycle it is given; both
     *   are noted, as their output moves.
     * - A `colourList` parameter takes literal colours only (D14) — Colour Cycle's `colours`, or a
     *   script FX's. Colour Cycle over two palettes or more becomes Palette Steps over them with the
     *   same `fadeRatio`, landing each palette on each head for itself, which is the point of D14;
     *   any other list has each palette replaced by the colour it resolves to now, one that resolves
     *   to none dropped as the list dropped it, and a list left with none is black, as an empty one
     *   played.
     * - Lightning Strike loses the colour parameters it computed and never applied (D27).
     */
    fun rewriteFx(fx: StoredFx, context: Context): Rewrite? {
        var out = fx
        val changes = ArrayList<String>()
        val notes = ArrayList<String>()
        val definition = context.definitions(fx.effectType)

        if (definition == null) {
            fxId(fx.effectType).takeIf { it != fx.effectType }?.let {
                changes += "${fx.effectType} is now $it"
                out = out.copy(effectType = it)
            }
        }
        distribution(fx.distribution).takeIf { it != fx.distribution }?.let {
            changes += "Order ${fx.distribution} is now $it"
            out = out.copy(distribution = it)
        }
        category(fx.category, definition?.outputType).takeIf { it != fx.category }?.let {
            changes += "category ${fx.category} is now $it"
            out = out.copy(category = it)
        }

        val builtIn = definition == null
        when {
            builtIn && same(out.effectType, BREATHE) -> {
                out = out.copy(
                    effectType = SINE_WAVE,
                    phaseOffset = mod(out.phaseOffset + 0.75, 1.0),
                    direction = EffectDirection.FORWARD.name,
                )
                changes += "Breathe is now Sine Wave a quarter-cycle on"
            }
            builtIn && bouncedFxIds.any { same(it, out.effectType) } && PING_PONG in out.parameters -> {
                val pingPong = out.parameters.getValue(PING_PONG).takeIf { it.isNotBlank() }?.toBooleanStrictOrNull() ?: true
                out = out.copy(parameters = out.parameters - PING_PONG)
                if (pingPong) out = bounced(out, changes, notes) else changes += "pingPong off is dropped"
            }
            builtIn && bouncedFxIds.any { same(it, out.effectType) } && context.absentPingPongIsOn ->
                out = bounced(out, changes, notes)
            builtIn && same(out.effectType, COLOUR_CYCLE) -> out = literalCycle(out, context, changes, notes)
            builtIn && same(out.effectType, LIGHTNING_STRIKE) && out.parameters.keys.any { it in LIGHTNING_COLOURS } -> {
                out = out.copy(parameters = out.parameters - LIGHTNING_COLOURS)
                changes += "Lightning Strike's unused colours are dropped"
            }
            definition != null -> definition.colourLists.forEach { name ->
                out = literalColours(out, name, context, changes, notes)
            }
        }
        return if (changes.isEmpty()) null else Rewrite(out, changes, notes)
    }

    /** [fx] with `pingPong` on, as Direction Bounce: see [rewriteFx]. */
    private fun bounced(fx: StoredFx, changes: MutableList<String>, notes: MutableList<String>): StoredFx {
        val doubled = fx.spread * 2.0
        if (doubled > SpreadShape.MAX_SPREAD) {
            notes += "spread ${percent(fx.spread)} would be ${percent(doubled)}, so it stops at ${percent(SpreadShape.MAX_SPREAD)}"
        }
        if (fx.distribution.trim().uppercase() == "PING_PONG") {
            notes += "the Ping-pong Order now sweeps the halved cycle"
        }
        changes += "pingPong is now Direction Bounce at half the cycle"
        return fx.copy(
            beatDivision = fx.beatDivision / 2.0,
            phaseOffset = mod(fx.phaseOffset * 2.0, 2.0),
            spread = doubled.coerceAtMost(SpreadShape.MAX_SPREAD),
            nShot = fx.nShot?.let { it * 2 },
            direction = EffectDirection.BOUNCE.name,
        )
    }

    /** [fx], a Colour Cycle, on literal colours or as Palette Steps: see [rewriteFx]. */
    private fun literalCycle(fx: StoredFx, context: Context, changes: MutableList<String>, notes: MutableList<String>): StoredFx {
        val entries = entriesOf(fx.parameters[COLOURS])
        if (entries.size >= 2 && entries.all { isTemplateColourRef(it) }) {
            val steps = entries.mapNotNull { entry ->
                parseTemplateColourRefUuid(entry)?.let { uuid -> context.palettes(uuid)?.let { step(uuid, it) } }
            }
            val shape = steps.takeIf { it.size == entries.size }?.let { paletteStepsShape(it).getOrNull() }
            if (shape?.family == PropertyMaskGroup.COLOUR) {
                changes += "Colour Cycle over ${steps.joinToString { "'${it.name}'" }} is now Palette Steps"
                notes += "each palette now lands on each head for itself"
                return fx.copy(
                    effectType = PALETTE_STEPS_ID,
                    category = shape.category,
                    parameters = buildMap {
                        put(PALETTES, entries.joinToString(","))
                        fx.parameters[FADE_RATIO]?.let { put(FADE_RATIO, it) }
                    },
                )
            }
        }
        return literalColours(fx, COLOURS, context, changes, notes)
    }

    /** [fx] with each palette in its colour list [name] replaced by the colour it resolves to now. */
    private fun literalColours(
        fx: StoredFx,
        name: String,
        context: Context,
        changes: MutableList<String>,
        notes: MutableList<String>,
    ): StoredFx {
        val entries = entriesOf(fx.parameters[name])
        if (entries.none { isTemplateColourRef(it) }) return fx
        val literal = entries.mapNotNull { entry ->
            if (!isTemplateColourRef(entry)) return@mapNotNull entry
            val colour = parseTemplateColourRefUuid(entry)?.let { uuid -> context.palettes(uuid)?.let(::colourOf) }
            if (colour == null) notes += "$entry names no colour, so it is dropped"
            colour?.toSerializedString()
        }
        changes += "'$name''s palettes are now the colours they resolve to"
        if (literal.isEmpty()) notes += "'$name' is left with no colour, so it is black, as it played"
        return fx.copy(parameters = fx.parameters + (name to literal.ifEmpty { listOf(NO_COLOUR) }.joinToString(",")))
    }

    private fun entriesOf(raw: String?): List<String> = raw.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** A palette as one Palette Steps step, or null where Palette Steps would drop it (`TemplateSource.paletteStep`). */
    private fun step(uuid: UUID, palette: Palette): PaletteStep? {
        if (palette.holdsFx || palette.rows.isEmpty() || palette.rows.any { !it.deferred }) return null
        val rows = palette.rows.map { row -> row.propertyName to (parseTemplateIntent(row.value) ?: return null) }
        return PaletteStep(uuid, palette.name, rows)
    }

    /** The one colour a palette reference resolved to in a colour FX (`TemplateColourSource`'s resolver), or null. */
    private fun colourOf(palette: Palette): ExtendedColour? =
        genericColourRows(palette.rows, { it.propertyName }, { it.deferred })
            ?.map { row -> row.propertyName to (parseTemplateIntent(row.value) ?: return null) }
            ?.let { TemplateResolver.resolveColourGeneric(it) }

    /**
     * [json], a record file written before [FX_LIBRARY], with every built-in bouncing FX that names no
     * `pingPong` given an explicit `pingPong: "true"`, as it ran; null when there is none. For a merged
     * tree that takes this desk's [FX_LIBRARY] marker over records an older desk wrote, so the import
     * still reads them as written. [definitionIds] are the project's own FX, never touched.
     */
    fun spellOutAbsentPingPong(json: String, definitionIds: Set<String>): String? {
        var changed = false
        fun walk(element: JsonElement): JsonElement = when (element) {
            is JsonArray -> JsonArray(element.map(::walk))
            is JsonObject -> {
                val fields = element.mapValues { (_, value) -> walk(value) }
                val type = (fields["effectType"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val parameters = fields["parameters"] as? JsonObject ?: JsonObject(emptyMap())
                if (type != null && "beatDivision" in fields && PING_PONG !in parameters &&
                    bouncedFxIds.any { same(it, type) } && definitionIds.none { same(it, type) }
                ) {
                    changed = true
                    JsonObject(fields + ("parameters" to JsonObject(parameters + (PING_PONG to JsonPrimitive("true")))))
                } else {
                    JsonObject(fields)
                }
            }
            else -> element
        }
        val out = walk(canonicalJson.parseToJsonElement(json))
        return if (changed) canonicalEncode(JsonElement.serializer(), out) else null
    }

    /** The FX ids [definitionsJson] (`fxDefinitions/` files) define. */
    fun definitionIds(definitionsJson: Sequence<String>): Set<String> = definitionsJson.mapNotNull {
        runCatching { (canonicalJson.parseToJsonElement(it) as JsonObject)["effectId"]?.jsonPrimitive?.content }.getOrNull()
    }.toSet()

    private fun same(a: String, b: String) = normalizeEffectType(a) == normalizeEffectType(b)

    private fun mod(value: Double, by: Double) = ((value % by) + by) % by

    private fun percent(spread: Double) = "${Math.round(spread * 100)} %"
}
