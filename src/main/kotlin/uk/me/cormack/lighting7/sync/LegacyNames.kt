package uk.me.cormack.lighting7.sync

import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.fx.FxDirection
import uk.me.cormack.lighting7.fx.WAVE_FAMILY
import uk.me.cormack.lighting7.fx.WAVE_ID
import uk.me.cormack.lighting7.fx.toEasingCurveParam
import uk.me.cormack.lighting7.fx.toUByteParam
import uk.me.cormack.lighting7.fx.PALETTE_COLOUR_REF_PREFIX
import uk.me.cormack.lighting7.models.TIMING_CYCLE
import uk.me.cormack.lighting7.models.TIMING_HEAD
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.PALETTE_STEPS_ID
import uk.me.cormack.lighting7.fx.PaletteStep
import uk.me.cormack.lighting7.fx.AttributeFamily
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
     * The `formatVersion` that put a surface binding's attribute targets and the position FX's centre
     * parameters into the glossary's words (terminology plan session 6).
     */
    const val ATTRIBUTE_FORMAT_VERSION = 39

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

    /** A surface binding's `type` (§4.3; the attribute targets, session 6). */
    private val BINDINGS = mapOf(
        "pressTemplate" to "pressPalette",
        "stackEffectSize" to "stackFxAmount",
        "fixtureProperty" to "fixtureAttribute",
        "groupProperty" to "groupAttribute",
        "selectionProperty" to "selectionAttribute",
    )

    /** A surface binding payload's fields, by old name: a palette press's, and an attribute target's. */
    private val BINDING_FIELDS = mapOf("templateUuid" to "paletteUuid", "propertyName" to "attribute")

    /** The built-in FX whose centre parameters took the UK spelling (D20), and the old names of those parameters. */
    private val CENTRED_FX = setOf("Circle", "Figure8", "RandomPosition")
    internal val CENTRE_PARAMETERS = mapOf("panCenter" to "panCentre", "tiltCenter" to "tiltCentre")

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
    val retiredFxIds: Set<String> get() = FX_IDS.keys + BREATHE + WAVE_ROWS.keys

    /** Whether [fxId] is a retired FX id, matched as the registry matches one. */
    fun isRetiredFxId(fxId: String): Boolean = retiredFxIds.any { same(it, fxId) }

    /**
     * Why an FX being written names an old name, or null: a retired id, a `pingPong` on an FX that
     * bounces by Direction, a palette named by its old prefix in a parameter, or an old [family].
     * Every write boundary refuses one, so no row written today holds a name the importer would
     * rewrite (P3).
     */
    fun writeProblem(fxType: String, parameters: Map<String, String>, family: String? = null): String? = when {
        same(fxType, BREATHE) -> "'$fxType' is gone: Wave a quarter-cycle on (phase offset + 0.75) draws it"
        isWaveFx(fxType) -> "'$fxType' is now Wave, shape ${waveRow(fxId(fxType))!!.second.shape}"
        isRetiredFxId(fxType) -> "'$fxType' is now '${fxId(fxType)}'"
        bouncedFxIds.any { same(it, fxType) } && PING_PONG in parameters ->
            "'$fxType' takes no pingPong: it bounces by Direction (BOUNCE)"
        isCentred(fxType) && parameters.keys.any { it in CENTRE_PARAMETERS } ->
            parameters.keys.first { it in CENTRE_PARAMETERS }.let { "'$it' is now '${CENTRE_PARAMETERS.getValue(it)}'" }
        else -> parameters.entries.firstOrNull { OLD_PALETTE_REF.containsMatchIn(it.value) }
            ?.let { "'${it.key}' names a palette as tmpl:{uuid}; a palette reference is pal:{uuid}" }
            ?: familyProblem(family)
    }

    private fun isCentred(fxType: String) = CENTRED_FX.any { same(it, fxType) }

    /**
     * [parameters] of a stored FX of type [fxType] with a position FX's centre at today's names, or
     * [parameters] itself when it holds no old one. Only the built-in position FX are renamed: a
     * script FX's own parameters are its author's words.
     */
    fun centreParameters(fxType: String, parameters: Map<String, String>): Map<String, String> =
        if (!isCentred(fxType) || parameters.keys.none { it in CENTRE_PARAMETERS }) parameters
        else parameters.entries.associate { (key, value) -> (CENTRE_PARAMETERS[key] ?: key) to value }

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
     * A stored surface binding's JSON payload at today's names — its `type`, a palette press's
     * `templateUuid` as `paletteUuid` and an attribute target's `propertyName` as `attribute`, a
     * flash's nested target included — or [payload] itself when it holds no old name.
     */
    fun bindingPayload(payload: String): String {
        val target = runCatching { canonicalJson.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return payload
        val renamed = bindingTarget(target)
        return if (renamed == target) payload else Json.encodeToString(JsonElement.serializer(), renamed)
    }

    private fun bindingTarget(target: JsonObject): JsonObject {
        if ((target["type"] as? JsonPrimitive)?.isString != true) return target
        val renamed = rename(target, BINDING_FIELDS).mapString("type", ::bindingType)
        val nested = renamed["target"] as? JsonObject ?: return renamed
        return JsonObject(renamed + ("target" to bindingTarget(nested)))
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
                val type = (fx["fxType"] as? JsonPrimitive)?.takeIf { it.isString }?.let { fxId(it.content) }
                val centred = if (type != null && isCentred(type)) rename(parameters, CENTRE_PARAMETERS) else parameters
                JsonObject(fx + ("parameters" to JsonObject(centred.mapValues { (_, value) -> value.mapString(::paletteRefs) })))
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

    /** Wave's id (fx-settings plan D2). */
    const val WAVE = WAVE_ID

    /** One old parameter as its FX read it: its type, its declared default, and its name on Wave. */
    private class OldParameter(val type: String, val default: String, val wave: String)

    /**
     * One level wave Wave replaced (fx-settings plan §3): the [shape] it is, its [parameters] by old
     * name, and the one [attribute] a beam FX drove, which a stored row naming none ran on.
     */
    private class WaveRow(
        val shape: String,
        val parameters: Map<String, OldParameter>,
        val attribute: String? = null,
        /** The old FX's own new-instance cycle (beats) and Direction, where they were not Wave's. */
        val cycle: Double? = null,
        val direction: String? = null,
    )

    private fun levels(low: String, high: String, lowName: String = "min", highName: String = "max") = mapOf(
        lowName to OldParameter("ubyte", low, "low"),
        highName to OldParameter("ubyte", high, "high"),
    )

    private fun curve(default: String) = mapOf("curve" to OldParameter("easingCurve", default, "curve"))

    private val PULSE_TIMES = mapOf(
        "attackRatio" to OldParameter("double", "0.1", "attack"),
        "holdRatio" to OldParameter("double", "0.3", "hold"),
    )

    /** §3's Wave block: each old id as Wave, every parameter as its old script read it. */
    private val WAVE_ROWS: Map<String, WaveRow> = mapOf(
        "SineWave" to WaveRow("SINE", levels("0", "255")),
        "RampUp" to WaveRow("RAMP_UP", levels("0", "255") + curve("LINEAR")),
        "RampDown" to WaveRow("RAMP_DOWN", levels("0", "255") + curve("LINEAR")),
        "Ramp" to WaveRow("TRIANGLE", levels("0", "255") + curve("LINEAR")),
        "SquareWave" to WaveRow("SQUARE", levels("0", "255") + mapOf("dutyCycle" to OldParameter("double", "0.5", "width"))),
        "Pulse" to WaveRow("PULSE", levels("0", "255") + PULSE_TIMES + curve("QUAD_OUT")),
        "DimmerStrobe" to WaveRow(
            "SQUARE",
            levels("0", "255", "offValue", "onValue") + mapOf("onRatio" to OldParameter("double", "0.1", "width")),
        ),
        "ZoomWave" to WaveRow("SINE", levels("64", "192"), attribute = "zoom"),
        "IrisPulse" to WaveRow("PULSE", levels("0", "160") + PULSE_TIMES + curve("QUAD_OUT"), attribute = "iris"),
        "FrostPulse" to WaveRow("PULSE", levels("0", "160") + PULSE_TIMES + curve("QUAD_OUT"), attribute = "frost"),
        "FocusPull" to WaveRow(
            "RAMP_UP", levels("64", "192", "start", "end") + curve("SINE_IN_OUT"),
            attribute = "focus", cycle = 2.0, direction = FxDirection.BOUNCE.name,
        ),
    )

    /**
     * The cycle (beats) and Direction a new FX of the old level wave [fxType] started at when its
     * author named none, where they differ from Wave's: Focus Pull's half-bar Bounce. Null otherwise.
     */
    fun waveTiming(fxType: String): Pair<Double, String>? =
        waveRow(fxId(fxType))?.second?.let { row -> row.cycle?.let { it to (row.direction ?: FxDirection.FORWARD.name) } }

    /** [fxType]'s row in [WAVE_ROWS] and its canonical id, matched as the registry matches one, or null. */
    private fun waveRow(fxType: String): Pair<String, WaveRow>? =
        WAVE_ROWS.entries.firstOrNull { same(it.key, fxType) }?.toPair()

    /** The ids of the level waves Wave replaced, as they were spelled. */
    val waveIds: Set<String> get() = WAVE_ROWS.keys

    /** Whether [fxType] is one of the level waves Wave replaced. */
    fun isWaveFx(fxType: String): Boolean = waveRow(fxId(fxType)) != null

    /**
     * [parameters] of an FX of the old type [fxType] as Wave's: its shape, and each parameter it
     * declared under Wave's name at the value its script read — a blank or absent one at its old
     * default, a level as `TypedParams.ubyte` clamps it, an unknown curve as `LINEAR` — so a default
     * that differs from Wave's (Zoom Wave's 64–192, Pulse's curve) is written out. A parameter the old
     * FX never declared is dropped, as it never read one. Null when [fxType] is no level wave.
     *
     * What a script's `effect("SineWave", …)` and MCP's tools run, and what [consolidateFx] stores.
     */
    fun waveParameters(fxType: String, parameters: Map<String, String>): Map<String, String>? {
        val (_, row) = waveRow(fxId(fxType)) ?: return null
        return buildMap {
            put("shape", row.shape)
            for ((old, p) in row.parameters) {
                val raw = parameters[old]?.takeIf { it.isNotBlank() } ?: p.default
                put(
                    p.wave,
                    when (p.type) {
                        "ubyte" -> (raw.toUByteParam() ?: 0u).toString()
                        "double" -> raw.takeIf { it.toDoubleOrNull() != null } ?: p.default
                        else -> (raw.toEasingCurveParam() ?: EasingCurve.LINEAR).name
                    },
                )
            }
        }
    }

    /**
     * Session 2's rewrite of [fx] onto Wave (fx-settings plan D3), or null when it is no level wave:
     * its id Wave, its family Wave's (a beam attribute makes it Beam's, D5), its parameters by
     * [waveParameters], and — for a beam FX stored naming no attribute, which ran on its one — that
     * attribute. Nothing else moves: the cycle, Direction, spread and phase run the same shape the
     * same way, which `LegacyEquivalenceTest` proves for every row. A script FX definition's own id
     * is never rewritten ([definitions]).
     *
     * One output moves, and says so in [Rewrite.notes] (fx-settings plan session 2, an exception to D3
     * Chris accepted): a beam FX that named no attribute ran on a group only where every head had its
     * attribute, and on none of that group otherwise; named, it runs on the heads that have it, as a
     * Wave authored on that attribute does.
     *
     * Separate from [rewriteFx] because database step 4 must not take it: its rows have no attribute
     * column to write, and step 7 (`state/FxConsolidationMigration.kt`) is this alone.
     */
    fun consolidateFx(fx: StoredFx, definitions: (String) -> Definition?): Rewrite? {
        if (definitions(fx.fxType) != null) return null
        val (id, row) = waveRow(fxId(fx.fxType)) ?: return null
        val parameters = waveParameters(id, fx.parameters)!!
        val changes = arrayListOf("${fx.fxType} is now Wave, shape ${row.shape}")
        val dropped = fx.parameters.keys - row.parameters.keys
        if (dropped.isNotEmpty()) changes += "${dropped.joinToString { "'$it'" }} named nothing it read, so went"
        val notes = ArrayList<String>()
        val attribute = fx.attribute?.takeIf { it.isNotBlank() } ?: row.attribute?.also {
            changes += "it names '$it', which it drove"
            notes += "on a group where not every head has '$it' it now runs on those that do; before, it ran on none of that group"
        }
        return Rewrite(
            fx.copy(fxType = WAVE, family = WAVE_FAMILY, parameters = parameters, attribute = attribute),
            changes,
            notes,
        )
    }

    /**
     * An FX a script or a tool names, as the registry runs it today: a level wave as Wave with its
     * parameters ([waveParameters]), any other renamed id at its new id; [fxType] and [parameters]
     * themselves otherwise. The one door an old id still opens (fx-settings plan D3) — every stored
     * write refuses one ([writeProblem]).
     */
    fun runnableFx(fxType: String, parameters: Map<String, String>): Pair<String, Map<String, String>> {
        val renamed = fxId(fxType)
        return waveParameters(renamed, parameters)?.let { WAVE to it } ?: (renamed to parameters)
    }

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
        /** The attribute it drives, where it names one; [consolidateFx] names a beam FX's. */
        val attribute: String? = null,
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
            if (shape?.family == AttributeFamily.COLOUR) {
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
