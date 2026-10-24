package uk.me.cormack.lighting7.sync

import uk.me.cormack.lighting7.fx.FxDirection
import uk.me.cormack.lighting7.fx.PALETTE_COLOUR_REF_PREFIX
import uk.me.cormack.lighting7.models.TIMING_CYCLE
import uk.me.cormack.lighting7.models.TIMING_HEAD
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.PALETTE_STEPS_ID
import uk.me.cormack.lighting7.fx.PaletteStep
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.SpreadShape
import uk.me.cormack.lighting7.fx.PaletteResolver
import uk.me.cormack.lighting7.fx.genericColourRows
import uk.me.cormack.lighting7.fx.isPaletteColourRef
import uk.me.cormack.lighting7.fx.normalizeFxType
import uk.me.cormack.lighting7.fx.paletteStepsShape
import uk.me.cormack.lighting7.fx.parsePaletteColourRefUuid
import uk.me.cormack.lighting7.fx.parsePaletteIntent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * The names an archive or an older database may still hold, mapped by field to today's
 * (terminology plan P3, P5). This is the one place an old name lives: the registry, the REST layer
 * and the client know only today's. An old name never returns with a new meaning in the same
 * field — a user FX definition may not take a retired id ([retiredFxIds]) — so every map here
 * applies to any archive, whatever its `formatVersion`, and to a tree that mixes two.
 *
 * [record] puts one archive record into today's words: its folder ([folder]), its fields and the
 * values §4.3 and §4.4 renamed. [rewriteFx] is §4.2's rewrite of one stored FX, shared by the
 * importer and database step 4 (`state/FxLibraryMigration.kt`), so an archive and a database
 * written before the FX library changed arrive at the same rows. Database step 5
 * (`state/TerminologyMigration.kt`) renames a database's values through the same maps.
 */
object LegacyNames {
    /**
     * The FX library an archive's FX are written against. 2 is the terminology plan's session 3; a
     * v37 archive says so as `ProjectJson.fxLibrary`, one without it is 1, and from
     * [GLOSSARY_FORMAT_VERSION] every archive is 2.
     */
    const val FX_LIBRARY = 2

    /**
     * The `formatVersion` that put the archive into the glossary's words (terminology plan session
     * 4). It implies [FX_LIBRARY], so `project.json` no longer says it.
     */
    const val GLOSSARY_FORMAT_VERSION = 38

    /**
     * Whether a bouncing FX that names no `pingPong` ran with it on: one in an archive written before
     * [GLOSSARY_FORMAT_VERSION] that says no [FX_LIBRARY] (see [Context.absentPingPongIsOn]).
     */
    fun absentPingPongIsOn(formatVersion: Int, fxLibrary: Int?): Boolean =
        formatVersion < GLOSSARY_FORMAT_VERSION && (fxLibrary ?: 1) < FX_LIBRARY

    /** `effectType` / `fxType`: FX ids renamed for their names (D10–D13). */
    private val FX_IDS = mapOf(
        "Triangle" to "Ramp",
        "ColourPulse" to "ColourWave",
        "Strobe" to "DimmerStrobe",
        "ZoomBreathe" to "ZoomWave",
    )

    /**
     * `distribution` / `order`: Rig is Normal (D8), Split gave Edges in's offsets exactly and both are
     * Centre in (D18), and lighting7's own identifiers spell centre the UK way (D20).
     */
    private val ORDERS = mapOf(
        "LINEAR" to "NORMAL",
        "CENTER_OUT" to "CENTRE_OUT",
        "EDGES_IN" to "CENTRE_IN",
        "SPLIT" to "CENTRE_IN",
    )

    /** `category` / `family`, a speed master's `usage` among them: Intensity (D25) and Steps (D23). */
    private val FAMILIES = mapOf("dimmer" to "intensity", "palette" to "steps")

    /** A surface binding's `type` (§4.3). */
    private val BINDINGS = mapOf("pressTemplate" to "pressPalette", "stackEffectSize" to "stackFxAmount")

    /** A palette reference's old prefix, `tmpl:{uuid}`, today [PALETTE_COLOUR_REF_PREFIX]. */
    private val OLD_PALETTE_REF = Regex("(?<![A-Za-z0-9_])tmpl:", RegexOption.IGNORE_CASE)

    /** Archive folders renamed at [GLOSSARY_FORMAT_VERSION], by their old name. */
    private val FOLDERS = mapOf(
        "templates" to "palettes",
        "cueAdHocEffects" to "cueAdHocFx",
        "cuePropertyAssignments" to "cueAttributeAssignments",
    )

    /** A stored FX's fields, in any of its three homes, by old name. */
    private val FX_FIELDS = mapOf(
        "effectType" to "fxType",
        "category" to "family",
        "propertyName" to "attribute",
        "beatDivision" to "cycle",
        "distribution" to "order",
        "stepTiming" to "timing",
        "size" to "amount",
        "nShot" to "runs",
        "blocks" to "segments",
        "groups" to "parts",
    )

    /** A Look's, a palette's or a cue's value row. */
    private val ROW_FIELDS = mapOf("propertyName" to "attribute")

    /** A cue layer's. */
    private val LAYER_FIELDS = mapOf("templateUuid" to "paletteUuid", "propertyMask" to "attributeMask")

    /** A script FX definition's. */
    private val DEFINITION_FIELDS = mapOf(
        "effectId" to "fxId",
        "category" to "family",
        "effectMode" to "fxMode",
        "compatibleProperties" to "compatibleAttributes",
        "defaultStepTiming" to "defaultTiming",
    )

    /** A Look's FX list and a palette's one FX. */
    private val FX_HOLDERS = mapOf("effects" to "fx", "effect" to "fx")

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

    /** Whether [fxId] is a retired FX id, matched as the registry matches one. */
    fun isRetiredFxId(fxId: String): Boolean = retiredFxIds.any { same(it, fxId) }

    /**
     * Why an FX being written names an old name, or null: a retired id, a `pingPong` on an FX that
     * bounces by Direction, a palette named by its old prefix in a parameter, or an old [family].
     * Every write boundary refuses one, so no row written today holds a name the importer would
     * rewrite (P3).
     */
    fun writeProblem(fxType: String, parameters: Map<String, String>, family: String? = null): String? = when {
        same(fxType, BREATHE) -> "'$fxType' is gone: Sine Wave a quarter-cycle on (phase offset + 0.75) draws it"
        isRetiredFxId(fxType) -> "'$fxType' is now '${fxId(fxType)}'"
        bouncedFxIds.any { same(it, fxType) } && PING_PONG in parameters ->
            "'$fxType' takes no pingPong: it bounces by Direction (BOUNCE)"
        else -> parameters.entries.firstOrNull { OLD_PALETTE_REF.containsMatchIn(it.value) }
            ?.let { "'${it.key}' names a palette as tmpl:{uuid}; a palette reference is pal:{uuid}" }
            ?: familyProblem(family)
    }

    /** Why [family], being written, is an old name, or null. */
    fun familyProblem(family: String?): String? =
        family?.let { stored -> FAMILIES[stored.trim().lowercase()]?.let { "family '$stored' is now '$it'" } }

    /** Today's id for a stored `effectType`, matched as the registry matches one. */
    fun fxId(stored: String): String = FX_IDS.entries.firstOrNull { same(it.key, stored) }?.value ?: stored

    /** Today's Order for a stored `distribution` or `order`. */
    fun order(stored: String): String = ORDERS[stored.trim().uppercase()] ?: stored

    /** Today's name for a stored `category`, `family` or speed master `usage`. */
    fun family(stored: String): String = FAMILIES[stored.trim().lowercase()] ?: stored

    /**
     * Today's family for a stored FX's or FX definition's: [family], and `composite` as the family of
     * the output the FX applied, which was its [outputType] — Lightning Strike's, a slider's, is
     * Intensity's.
     */
    fun familyOf(stored: String, outputType: String?): String =
        if (stored.trim().equals(COMPOSITE, ignoreCase = true)) {
            when (outputType?.uppercase()) {
                "COLOUR" -> "colour"
                "POSITION" -> "position"
                else -> "intensity"
            }
        } else {
            family(stored)
        }

    /** [value] with every palette reference's old prefix at today's, `tmpl:{uuid}` as `pal:{uuid}`. */
    fun paletteRefs(value: String): String =
        if (value.contains("tmpl:", ignoreCase = true)) OLD_PALETTE_REF.replace(value, PALETTE_COLOUR_REF_PREFIX) else value

    /** Today's name for a stored surface binding `type`. */
    fun bindingType(stored: String): String = BINDINGS[stored] ?: stored

    /**
     * A stored surface binding's JSON payload at today's names — its `type`, and a palette press's
     * `templateUuid` as `paletteUuid` — or [payload] itself when it holds no old name.
     */
    fun bindingPayload(payload: String): String {
        val target = runCatching { canonicalJson.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return payload
        val type = (target["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return payload
        val renamed = bindingType(type)
        if (renamed == type) return payload
        val fields = target.entries.associate { (key, value) ->
            when (key) {
                "type" -> key to JsonPrimitive(renamed)
                "templateUuid" -> "paletteUuid" to value
                else -> key to value
            }
        }
        return Json.encodeToString(JsonElement.serializer(), JsonObject(fields))
    }

    /** Today's mode for a stored `effectMode` or `fxMode`, by name. */
    fun fxMode(stored: String): String = EFFECT_MODES[stored.trim().uppercase()] ?: stored

    /** Today's name for an archive folder. */
    fun folder(stored: String): String = FOLDERS[stored] ?: stored

    /** The old names of today's archive folder [folder], which an older archive keeps its records in. */
    fun oldFolders(folder: String): List<String> = FOLDERS.filterValues { it == folder }.keys.toList()

    /**
     * [json], one record from today's archive folder [folder] (or its old name), with every old field
     * and value at today's: the folder's own fields, a stored FX's ([FX_FIELDS]) wherever the record
     * holds one, the Order, family and binding names, `tmpl:` references and a `stepTiming` as a
     * Timing. Answers [json] itself when nothing in it is old, so a record already in today's words
     * keeps its bytes.
     */
    fun record(folder: String, json: String): String {
        if (folder !in RECORD_FOLDERS) return json
        val record = runCatching { canonicalJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return json
        val out = when (folder) {
            "palettes", "looks" -> JsonObject(
                rename(record, FX_HOLDERS).mapValues { (key, value) ->
                    when (key) {
                        "rows" -> mapArray(value, ::row)
                        "fx" -> if (value is JsonArray) mapArray(value, ::fx) else (value as? JsonObject)?.let(::fx) ?: value
                        else -> value
                    }
                },
            )
            "cueAdHocFx" -> fx(record)
            "cueAttributeAssignments" -> row(record)
            "cueLayers" -> rename(record, LAYER_FIELDS)
            "buskPages" -> renameEverywhere(record, LAYER_FIELDS)
            "speedMasters" -> rename(record, mapOf("usage" to "family")).mapString("family", ::family)
            "fxDefinitions" -> rename(record, DEFINITION_FIELDS).mapString("family", ::family).timing("defaultTiming")
                .let { d -> (d["parameters"] as? JsonArray)?.let { JsonObject(d + ("parameters" to definitionParameters(it))) } ?: d }
            "controlSurfaceBindings" -> record.mapString("targetType", ::bindingType).mapString("targetPayload", ::bindingPayload)
            else -> record
        }
        return if (out == record) json else canonicalEncode(JsonElement.serializer(), out)
    }

    /** The archive folders [record] has old names to move in. */
    private val RECORD_FOLDERS = setOf(
        "palettes", "looks", "cueAdHocFx", "cueAttributeAssignments", "cueLayers", "buskPages", "speedMasters",
        "fxDefinitions", "controlSurfaceBindings",
    )

    /**
     * A script FX definition's parameters — `ParameterInfo`s, in a database column as text or in an
     * archive as an array — with a palette reference in any `defaultValue` at today's prefix.
     */
    fun definitionParameters(stored: String): String {
        if (!stored.contains("tmpl:", ignoreCase = true)) return stored
        val parameters = runCatching { canonicalJson.parseToJsonElement(stored) }.getOrNull() as? JsonArray ?: return stored
        return Json.encodeToString(JsonElement.serializer(), definitionParameters(parameters))
    }

    private fun definitionParameters(parameters: JsonArray): JsonArray =
        JsonArray(parameters.map { parameter -> (parameter as? JsonObject)?.mapString("defaultValue", ::paletteRefs) ?: parameter })

    /** A stored FX object at today's names. */
    private fun fx(stored: JsonObject): JsonObject =
        rename(stored, FX_FIELDS)
            .mapString("order", ::order)
            .mapString("family", ::family)
            .timing("timing")
            .let { fx ->
                val parameters = fx["parameters"] as? JsonObject ?: return@let fx
                JsonObject(fx + ("parameters" to JsonObject(parameters.mapValues { (_, value) -> value.mapString(::paletteRefs) })))
            }

    /** A value row at today's names: its attribute, and a palette reference in its value. */
    private fun row(stored: JsonElement): JsonElement =
        (stored as? JsonObject)?.let { rename(it, ROW_FIELDS).mapString("value", ::paletteRefs) } ?: stored

    private fun rename(record: JsonObject, names: Map<String, String>): JsonObject =
        if (record.keys.none { it in names }) record
        else JsonObject(record.entries.associate { (key, value) -> (names[key] ?: key) to value })

    private fun renameEverywhere(element: JsonElement, names: Map<String, String>): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map { renameEverywhere(it, names) })
        is JsonObject -> JsonObject(rename(element, names).mapValues { (_, value) -> renameEverywhere(value, names) })
        else -> element
    }

    private fun mapArray(element: JsonElement, transform: (JsonObject) -> JsonElement): JsonElement =
        (element as? JsonArray)?.let { array -> JsonArray(array.map { (it as? JsonObject)?.let(transform) ?: it }) } ?: element

    private fun JsonElement.mapString(transform: (String) -> String): JsonElement =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.let { JsonPrimitive(transform(it.content)) } ?: this

    private fun JsonObject.mapString(key: String, transform: (String) -> String): JsonObject {
        val value = this[key] ?: return this
        val mapped = value.mapString(transform)
        return if (mapped == value) this else JsonObject(this + (key to mapped))
    }

    /** A `stepTiming`-shaped boolean at [key] as today's Timing, `HEAD` or `CYCLE`. */
    private fun JsonObject.timing(key: String): JsonObject {
        val value = this[key] as? JsonPrimitive ?: return this
        if (value.isString) return this
        val stepTiming = value.booleanOrNull ?: return JsonObject(this - key)
        return JsonObject(this + (key to JsonPrimitive(if (stepTiming) TIMING_HEAD else TIMING_CYCLE)))
    }

    /** Today's script type for a stored `scriptType`, by name. */
    fun scriptType(stored: String): String = SCRIPT_TYPES[stored.trim().uppercase()] ?: stored

    /** The fields of a stored FX that §4.2's rewrites read or move, whatever home it lives in. */
    data class StoredFx(
        val fxType: String,
        val family: String,
        val cycle: Double,
        val order: String,
        val phaseOffset: Double,
        val parameters: Map<String, String>,
        val direction: String,
        val runs: Int?,
        val spread: Double,
    )

    /** A rewritten FX, and what changed about it in words for the log; [notes] say what output moved. */
    data class Rewrite(val fx: StoredFx, val changes: List<String>, val notes: List<String>)

    /** A palette as [rewriteFx] reads it, by its uuid: its rows, and whether it holds an FX instead. */
    data class Palette(val name: String, val rows: List<PaletteRow>, val holdsFx: Boolean)

    /** One palette row: its attribute, its intent as stored, and whether it is generic. */
    data class PaletteRow(val attribute: String, val value: String, val deferred: Boolean)

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
        val definition = context.definitions(fx.fxType)

        if (definition == null) {
            fxId(fx.fxType).takeIf { it != fx.fxType }?.let {
                changes += "${fx.fxType} is now $it"
                out = out.copy(fxType = it)
            }
        }
        order(fx.order).takeIf { it != fx.order }?.let {
            changes += "Order ${fx.order} is now $it"
            out = out.copy(order = it)
        }
        familyOf(fx.family, definition?.outputType).takeIf { it != fx.family }?.let {
            changes += "family ${fx.family} is now $it"
            out = out.copy(family = it)
        }

        val builtIn = definition == null
        when {
            builtIn && same(out.fxType, BREATHE) -> {
                out = out.copy(
                    fxType = SINE_WAVE,
                    phaseOffset = mod(out.phaseOffset + 0.75, 1.0),
                    direction = FxDirection.FORWARD.name,
                )
                changes += "Breathe is now Sine Wave a quarter-cycle on"
            }
            builtIn && bouncedFxIds.any { same(it, out.fxType) } && PING_PONG in out.parameters -> {
                val pingPong = out.parameters.getValue(PING_PONG).takeIf { it.isNotBlank() }?.toBooleanStrictOrNull() ?: true
                out = out.copy(parameters = out.parameters - PING_PONG)
                if (pingPong) out = bounced(out, changes, notes) else changes += "pingPong off is dropped"
            }
            builtIn && bouncedFxIds.any { same(it, out.fxType) } && context.absentPingPongIsOn ->
                out = bounced(out, changes, notes)
            builtIn && same(out.fxType, COLOUR_CYCLE) -> out = literalCycle(out, context, changes, notes)
            builtIn && same(out.fxType, LIGHTNING_STRIKE) && out.parameters.keys.any { it in LIGHTNING_COLOURS } -> {
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
        if (fx.order.trim().uppercase() == "PING_PONG") {
            notes += "the Ping-pong Order now sweeps the halved cycle"
        }
        changes += "pingPong is now Direction Bounce at half the cycle"
        return fx.copy(
            cycle = fx.cycle / 2.0,
            phaseOffset = mod(fx.phaseOffset * 2.0, 2.0),
            spread = doubled.coerceAtMost(SpreadShape.MAX_SPREAD),
            runs = fx.runs?.let { it * 2 },
            direction = FxDirection.BOUNCE.name,
        )
    }

    /** [fx], a Colour Cycle, on literal colours or as Palette Steps: see [rewriteFx]. */
    private fun literalCycle(fx: StoredFx, context: Context, changes: MutableList<String>, notes: MutableList<String>): StoredFx {
        val entries = entriesOf(fx.parameters[COLOURS])
        if (entries.size >= 2 && entries.all { isPaletteColourRef(it) }) {
            val steps = entries.mapNotNull { entry ->
                parsePaletteColourRefUuid(entry)?.let { uuid -> context.palettes(uuid)?.let { step(uuid, it) } }
            }
            val shape = steps.takeIf { it.size == entries.size }?.let { paletteStepsShape(it).getOrNull() }
            if (shape?.family == PropertyMaskGroup.COLOUR) {
                changes += "Colour Cycle over ${steps.joinToString { "'${it.name}'" }} is now Palette Steps"
                notes += "each palette now lands on each head for itself"
                return fx.copy(
                    fxType = PALETTE_STEPS_ID,
                    family = shape.fxFamily,
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
        if (entries.none { isPaletteColourRef(it) }) return fx
        val literal = entries.mapNotNull { entry ->
            if (!isPaletteColourRef(entry)) return@mapNotNull entry
            val colour = parsePaletteColourRefUuid(entry)?.let { uuid -> context.palettes(uuid)?.let(::colourOf) }
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
        val rows = palette.rows.map { row -> row.attribute to (parsePaletteIntent(row.value) ?: return null) }
        return PaletteStep(uuid, palette.name, rows)
    }

    /** The one colour a palette reference resolved to in a colour FX (`TemplateColourSource`'s resolver), or null. */
    private fun colourOf(palette: Palette): ExtendedColour? =
        genericColourRows(palette.rows, { it.attribute }, { it.deferred })
            ?.map { row -> row.attribute to (parsePaletteIntent(row.value) ?: return null) }
            ?.let { PaletteResolver.resolveColourGeneric(it) }

    /**
     * [json], a record file written before [FX_LIBRARY], with every built-in bouncing FX that names no
     * `pingPong` given an explicit `pingPong: "true"`, as it ran; null when there is none. For a merged
     * tree that takes this desk's `formatVersion` over records an older desk wrote, so the import still
     * reads them as written. [definitionIds] are the project's own FX, never touched.
     */
    fun spellOutAbsentPingPong(json: String, definitionIds: Set<String>): String? {
        var changed = false
        fun walk(element: JsonElement): JsonElement = when (element) {
            is JsonArray -> JsonArray(element.map(::walk))
            is JsonObject -> {
                val fields = element.mapValues { (_, value) -> walk(value) }
                val type = ((fields["fxType"] ?: fields["effectType"]) as? JsonPrimitive)?.takeIf { it.isString }?.content
                val parameters = fields["parameters"] as? JsonObject ?: JsonObject(emptyMap())
                if (type != null && ("cycle" in fields || "beatDivision" in fields) && PING_PONG !in parameters &&
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
        runCatching { (canonicalJson.parseToJsonElement(it) as JsonObject).let { d -> d["fxId"] ?: d["effectId"] }?.jsonPrimitive?.content }.getOrNull()
    }.toSet()

    private fun same(a: String, b: String) = normalizeFxType(a) == normalizeFxType(b)

    private fun mod(value: Double, by: Double) = ((value % by) + by) % by

    private fun percent(spread: Double) = "${Math.round(spread * 100)} %"
}
