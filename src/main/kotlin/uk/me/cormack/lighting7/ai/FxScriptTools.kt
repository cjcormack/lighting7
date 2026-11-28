package uk.me.cormack.lighting7.ai

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fx.CompiledFxScript
import uk.me.cormack.lighting7.fx.FxContext
import uk.me.cormack.lighting7.fx.FxMode
import uk.me.cormack.lighting7.fx.FxOutput
import uk.me.cormack.lighting7.fx.FxOutputType
import uk.me.cormack.lighting7.fx.FxSource
import uk.me.cormack.lighting7.fx.MasterClock
import uk.me.cormack.lighting7.fx.ParameterInfo
import uk.me.cormack.lighting7.fx.ScriptFxAdapter
import uk.me.cormack.lighting7.fx.StatefulFx
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoFxDefinition
import uk.me.cormack.lighting7.models.DaoFxDefinitions
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.Timing
import uk.me.cormack.lighting7.routes.FxPreviewEntry
import uk.me.cormack.lighting7.routes.FxPreviewOutcome
import uk.me.cormack.lighting7.routes.FxPreviewRequest
import uk.me.cormack.lighting7.routes.FxPreviewSeries
import uk.me.cormack.lighting7.routes.FxPreviewWindow
import uk.me.cormack.lighting7.routes.normaliseCompatibleAttributes
import uk.me.cormack.lighting7.routes.parametersProblem
import uk.me.cormack.lighting7.routes.previewFx
import uk.me.cormack.lighting7.routes.registerUserFx
import uk.me.cormack.lighting7.routes.retiredIdProblem
import uk.me.cormack.lighting7.routes.validateCompatibleAttributes
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.LegacyNames
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** The tools that compile or store a script, refused while Remote access's *Allow scripts* is off. */
internal val FX_SCRIPT_GATED_TOOLS = setOf("check_fx_script", "save_fx_script")

internal const val SCRIPTS_REMOTE_REFUSAL =
    "Scripts are turned off for remote access (REMOTE_SCRIPTS_DISABLED): a script is code that runs inside the desk. " +
        "An admin at the desk can allow them in Install settings → Cloud → Remote access → Allow scripts. Reading scripts, " +
        "the guides, apply_fx and preview_fx work without it."

private val FX_ID = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
private val FAMILIES = listOf("intensity", "colour", "position", "beam", "controls")
private val OUTPUT_TYPES = listOf(FxOutputType.SLIDER, FxOutputType.COLOUR, FxOutputType.POSITION)
private val PARAMETER_TYPES = listOf("ubyte", "int", "double", "float", "boolean", "string", "choice", "colour", "colourList", "easingCurve")

/** How long a save's trial run may take before the script is refused as too slow for the output loop. */
private const val TRIAL_TIMEOUT_MS = 2_000L
private const val TRIAL_STOP_GRACE_MS = 200L

/** A trial run that outlived its deadline and ignored the interrupt; while it lives, no other trial starts. */
private val stuckTrial = AtomicReference<Thread?>(null)

private val PARAMETERS_JSON = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

private val PREVIEW_ONLY = setOf("fxId", "targets", "heads", "cycles", "samples")

private fun JsonObjectBuilder.string(name: String, description: String) =
    put(name, buildJsonObject { put("type", "string"); put("description", description) })

internal val getFxScriptGuideTool = ToolDef(
    name = "get_fx_script_guide",
    description = "How to write a custom FX for this desk: the script's scope (phase, context, params; tick, deltaMs and " +
        "state for a stateful FX), its outputs, the parameter types and their editor metadata, the definition's fields, " +
        "and a worked example of each mode. Read it before check_fx_script or save_fx_script.",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    },
)

internal val getFxScriptTool = ToolDef(
    name = "get_fx_script",
    description = "Without fxType: every FX type in this show's library — built-in and custom — with its family, " +
        "outputType, mode and source. With fxType: that FX's whole definition, its parameters and its script, which " +
        "save_fx_script takes back (a built-in's under an fxId of your own, to fork it).",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            string("fxType", "The FX's id, as the library lists it. Omit to list the library.")
        }
    },
)

internal val checkFxScriptTool = ToolDef(
    name = "check_fx_script",
    description = "Compile a custom FX script without saving it, and answer its errors and warnings, each at " +
        "line:column of the script as given. Needs Remote access's Allow scripts.",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            string("script", "The script body, as get_fx_script_guide describes it.")
            putJsonObject("fxMode") {
                put("type", "string")
                put("enum", buildJsonArray { FxMode.entries.forEach { add(it.name) } })
                put("description", "STANDARD (a function of phase) or STATEFUL (ticks and state). Default STANDARD.")
            }
        }
        put("required", buildJsonArray { add("script") })
    },
)

internal val saveFxScriptTool = ToolDef(
    name = "save_fx_script",
    description = "Create a custom FX in the current show, or replace the one with this fxId: the whole definition. " +
        "The script is compiled and run on a few phases with its default parameters first; if it does not compile, " +
        "throws, runs too slowly or answers the wrong output for its outputType, nothing is saved and the answer says " +
        "why. Once saved it is in the library at once — apply_fx, preview_fx, Looks, cues and FX palettes name it by " +
        "fxId — and it syncs with the show. An FX already running keeps its old code until applied again. Needs " +
        "Remote access's Allow scripts. get_fx_script_guide explains every field.",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            string("fxId", "The FX's id: a letter, then letters, digits, '-' or '_'. Not a built-in's.")
            string("name", "What the operator sees in the library.")
            putJsonObject("family") {
                put("type", "string")
                put("enum", buildJsonArray { FAMILIES.forEach { add(it) } })
                put("description", "The library section it is filed under.")
            }
            putJsonObject("outputType") {
                put("type", "string")
                put("enum", buildJsonArray { OUTPUT_TYPES.forEach { add(it.name) } })
                put("description", "SLIDER (a level), COLOUR or POSITION: what the script's last expression is.")
            }
            putJsonObject("fxMode") {
                put("type", "string")
                put("enum", buildJsonArray { FxMode.entries.forEach { add(it.name) } })
                put("description", "Default STANDARD.")
            }
            putJsonObject("timingSource") {
                put("type", "string")
                put("enum", buildJsonArray { TimingSource.entries.forEach { add(it.name) } })
                put("description", "BEAT (follows a speed master, cycle in beats) or WALL_CLOCK (seconds). Default BEAT.")
            }
            putJsonObject("defaultTiming") {
                put("type", "string")
                put("enum", buildJsonArray { Timing.entries.forEach { add(it.name) } })
                put("description", "CYCLE (the cycle is the whole FX) or HEAD (each head's step). Default CYCLE.")
            }
            putJsonObject("compatibleAttributes") {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", "The attributes it is offered on: slider attributes (dimmer, uv, zoom…) for SLIDER, [rgbColour] for COLOUR, [position] for POSITION.")
            }
            putJsonObject("parameters") {
                put("type", "array")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("description", "{name, type, defaultValue, label?, description?, min?, max?, step?, unit?, options?, role?, pair?, shownWhen?}")
                })
                put("description", "The parameters the script reads, by name. Default none.")
            }
            string("script", "The script body.")
        }
        put("required", buildJsonArray {
            add("fxId"); add("name"); add("family"); add("outputType"); add("compatibleAttributes"); add("script")
        })
    },
)

internal val deleteFxScriptTool = ToolDef(
    name = "delete_fx_script",
    description = "Delete a custom FX from the current show and the library. Looks, cues and palettes naming it keep " +
        "their rows but run nothing for it; one already running keeps running until stopped. Built-in FX cannot be deleted.",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            string("fxId", "The custom FX's id.")
        }
        put("required", buildJsonArray { add("fxId") })
    },
)

internal val previewFxTool = ToolDef(
    name = "preview_fx",
    description = "What an FX would output, sampled by the desk without touching the stage: per head, its values " +
        "across the window — levels 0–255, colours as #rrggbb (with ;wN;aN;uvN), positions as pan,tilt with 128 the " +
        "centre. Draw a draft (fxType, attribute and the apply_fx fields) on notional heads, or on real targets as it " +
        "would land on them; or a running FX by fxId. Use it to check a custom FX's shape before apply_fx.",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            string("fxType", "The FX type to draw, built-in or custom. Not with fxId.")
            string("attribute", "The attribute it drives (dimmer, rgbColour, position…). With fxType.")
            putJsonObject("parameters") {
                put("type", "object")
                put("additionalProperties", buildJsonObject { put("type", "string") })
            }
            for (key in listOf("cycle", "spread", "amount", "phaseOffset")) putJsonObject(key) { put("type", "number") }
            for (key in listOf("order", "direction", "blendMode", "elementMode", "timing", "spreadUnit", "speedMasterUuid")) {
                putJsonObject(key) { put("type", "string") }
            }
            for (key in listOf("wings", "segments", "parts", "runs")) putJsonObject(key) { put("type", "integer") }
            putJsonObject("ownBpm") { put("type", "number") }
            putJsonObject("fxId") {
                put("type", "integer")
                put("description", "A running FX to draw instead of a draft (get_current_state's active_fx).")
            }
            putJsonObject("targets") {
                put("type", "array")
                put("items", buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("type") { put("type", "string"); put("enum", buildJsonArray { add("group"); add("fixture") }) }
                        putJsonObject("key") { put("type", "string") }
                    }
                    put("required", buildJsonArray { add("type"); add("key") })
                })
                put("description", "Real fixtures and groups to draw it on. Omit to draw a draft on notional heads.")
            }
            putJsonObject("heads") {
                put("type", "integer")
                put("description", "How many notional heads to draw a draft on, 1–32, when no targets are given. Default 8.")
            }
            putJsonObject("cycles") {
                put("type", "number")
                put("description", "The window, in cycles of the FX. Default 1.")
            }
            putJsonObject("samples") {
                put("type", "integer")
                put("description", "Values per head across the window, 2–48. Default 12.")
            }
        }
    },
)

/**
 * The custom-FX tools: reading the library's scripts and the authoring guide, compiling, saving and
 * deleting an FX definition, and sampling any FX without output. A definition is the same row the FX
 * library's editor writes (`routes/fxDefinitions.kt`), checked by the same rules and registered by the
 * same [registerUserFx], and goes to sync, history and the library as one does. Compiling and saving are
 * refused by `McpProtocol` while *Allow scripts* is off ([FX_SCRIPT_GATED_TOOLS]); the rest run code that
 * is already in the library, as applying a built-in does.
 */
internal class FxScriptTools(private val state: State) {

    val toolDefs: List<ToolDef> = listOf(
        getFxScriptGuideTool, getFxScriptTool, checkFxScriptTool, saveFxScriptTool, deleteFxScriptTool, previewFxTool,
    )

    fun handles(name: String): Boolean = toolDefs.any { it.name == name }

    fun executeTool(name: String, input: JsonObject): ToolExecutionResult {
        toolDefs.firstOrNull { it.name == name }?.let { def -> undeclaredArgumentRefusal(def, input)?.let { return failure(it) } }
        return try {
            when (name) {
                getFxScriptGuideTool.name -> ToolExecutionResult(true, "The custom FX guide", FxScriptGuide.text)
                getFxScriptTool.name -> getFxScript(input)
                checkFxScriptTool.name -> checkFxScript(input)
                saveFxScriptTool.name -> saveFxScript(input)
                deleteFxScriptTool.name -> deleteFxScript(input)
                previewFxTool.name -> previewFx(input)
                else -> failure("Unknown tool: $name")
            }
        } catch (e: IllegalArgumentException) {
            failure(e.message ?: "Bad arguments")
        }
    }

    private fun getFxScript(input: JsonObject): ToolExecutionResult {
        val registry = state.show.fxRegistry
        val fxType = input.text("fxType")
        if (fxType == null) {
            val listed = registry.allRegistrations.filter { !it.legacy }.sortedWith(compareBy({ it.source }, { it.id }))
            return success("${listed.size} FX type(s)", buildJsonObject {
                putJsonArray("fx") {
                    listed.forEach { reg ->
                        addJsonObject {
                            put("fxType", reg.id)
                            put("name", reg.name)
                            put("family", reg.family)
                            put("outputType", reg.outputType.name)
                            put("fxMode", reg.fxMode.name)
                            put("source", if (reg.source == FxSource.USER) "CUSTOM" else "BUILT_IN")
                        }
                    }
                }
            })
        }
        val reg = registry.getRegistration(fxType) ?: return failure("No FX type '$fxType' in the library")
        return success("${reg.id}: ${reg.name}", buildJsonObject {
            put("fxId", reg.id)
            put("name", reg.name)
            put("source", if (reg.source == FxSource.USER) "CUSTOM" else "BUILT_IN")
            put("family", reg.family)
            put("outputType", reg.outputType.name)
            put("fxMode", reg.fxMode.name)
            put("timingSource", reg.timingSource.name)
            put("defaultTiming", reg.defaultTiming.name)
            put("defaultCycle", reg.defaultCycle)
            putJsonArray("compatibleAttributes") { reg.compatibleAttributes.forEach { add(it) } }
            put("parameters", PARAMETERS_JSON.encodeToJsonElement(ListSerializer(ParameterInfo.serializer()), reg.parameters))
            reg.script?.let { put("script", it) }
            if (reg.source == FxSource.BUILT_IN) {
                put(
                    "note",
                    if (reg.outputType == FxOutputType.STEPS) "A step FX is the desk's own and cannot be forked."
                    else "Built in and read only: save_fx_script under an fxId of your own to make a variant.",
                )
            }
        })
    }

    private fun checkFxScript(input: JsonObject): ToolExecutionResult {
        val script = input.text("script")?.takeIf { it.isNotBlank() } ?: return failure("Missing 'script'")
        val mode = input.enumOf("fxMode", FxMode.entries) ?: FxMode.STANDARD
        val checked = state.show.fxScriptCompiler.compileCheck(script, mode)
        return ToolExecutionResult(
            success = checked.success,
            description = if (checked.success) "Compiles" else "Does not compile",
            result = buildJsonObject {
                put("compiles", checked.success)
                putDiagnostics(checked.messages.map { Triple(it.severity, it.message, it.location) })
            }.toString(),
        )
    }

    private fun saveFxScript(input: JsonObject): ToolExecutionResult {
        val problems = mutableListOf<String>()
        val fxId = input.text("fxId")?.trim()
        when {
            fxId.isNullOrEmpty() -> problems += "Missing 'fxId'"
            !FX_ID.matches(fxId) -> problems += "fxId '$fxId' must be a letter, then letters, digits, '-' or '_' (at most 64)"
            else -> {
                retiredIdProblem(fxId)?.let { problems += it }
                state.show.fxRegistry.getRegistration(fxId)?.let { existing ->
                    when {
                        existing.source == FxSource.BUILT_IN -> problems += "'$fxId' is the built-in ${existing.name}'s id; give the FX another"
                        existing.id != fxId -> problems += "fxId '$fxId' clashes with the custom FX '${existing.id}'; save under '${existing.id}' to replace it"
                    }
                }
            }
        }
        val name = input.text("name")?.trim()
        if (name.isNullOrEmpty()) problems += "Missing 'name'"
        val family = input.text("family")?.trim()?.lowercase()
        when {
            family == null -> problems += "Missing 'family'"
            family !in FAMILIES -> problems += (LegacyNames.familyProblem(family) ?: "family '$family' is not one of ${FAMILIES.joinToString()}")
        }
        val outputType = input.enumOf("outputType", OUTPUT_TYPES)
        if (outputType == null) problems += "Missing 'outputType': ${OUTPUT_TYPES.joinToString()}"
        val mode = input.enumOf("fxMode", FxMode.entries) ?: FxMode.STANDARD
        val timingSource = input.enumOf("timingSource", TimingSource.entries) ?: TimingSource.BEAT
        val defaultTiming = input.enumOf("defaultTiming", Timing.entries) ?: Timing.CYCLE
        val script = input.text("script")
        if (script.isNullOrBlank()) problems += "Missing 'script'"

        val parameters = when (val raw = input.present("parameters")) {
            null -> emptyList()
            else -> try {
                PARAMETERS_JSON.decodeFromJsonElement(ListSerializer(ParameterInfo.serializer()), raw)
            } catch (e: Exception) {
                problems += "parameters: ${e.message?.lineSequence()?.first() ?: "unreadable"}"
                emptyList()
            }
        }
        parameters.filter { it.type !in PARAMETER_TYPES }.forEach {
            problems += "parameter '${it.name}': type '${it.type}' is not one of ${PARAMETER_TYPES.joinToString()}"
        }
        parametersProblem(parameters)?.let { problems += it }

        val compatible = when (val raw = input.present("compatibleAttributes")) {
            is JsonArray -> raw.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("compatibleAttributes must be strings") }
            null -> emptyList()
            else -> throw IllegalArgumentException("compatibleAttributes must be an array of attribute names")
        }.let { if (outputType != null) normaliseCompatibleAttributes(outputType, it) else it }
        if (compatible.isEmpty()) problems += "compatibleAttributes: name at least one attribute it runs on"
        if (outputType != null) validateCompatibleAttributes(outputType, compatible)?.let { problems += it }
        if (problems.isNotEmpty()) return failure(problems.joinToString("; "))
        fxId!!; name!!; family!!; outputType!!; script!!

        val compiled = state.show.fxScriptCompiler.compile(script, mode)
        val warnings = compiled.diagnostics.filter { it.severity == "WARNING" }
        if (!compiled.isSuccess) {
            return ToolExecutionResult(false, "The script does not compile", buildJsonObject {
                put("error", "The script does not compile; nothing was saved")
                putDiagnostics(compiled.diagnostics.map { Triple(it.severity, it.message, it.location) })
            }.toString())
        }
        val trial = when (val run = trialRun(compiled, parameters, name, outputType, defaultTiming)) {
            is Trial.Refused -> return failure("${run.message}; nothing was saved")
            is Trial.Ran -> run.samples
        }

        var previous: (DaoFxDefinition.() -> Unit)? = null
        val (definition, created) = transaction(state.database) {
            val project = state.show.project
            val existing = DaoFxDefinition.find {
                (DaoFxDefinitions.project eq project.id) and (DaoFxDefinitions.fxId eq fxId)
            }.singleOrNull()
            previous = existing?.restorer()
            val row = existing ?: DaoFxDefinition.new {
                this.fxId = fxId
                this.project = project
                this.script = script
                this.name = name
                this.family = family
                this.outputType = outputType
            }
            row.name = name
            row.family = family
            row.outputType = outputType
            row.fxMode = mode
            row.parameters = parameters
            row.compatibleAttributes = compatible
            row.script = script
            row.defaultTiming = defaultTiming.name
            row.timingSource = timingSource
            row to (existing == null)
        }
        val registered = registerUserFx(state, definition)
        if (!registered.success) {
            transaction(state.database) {
                val restore = previous
                if (restore == null) definition.delete() else definition.restore()
            }
            return failure("The library could not load it, so nothing was saved: ${registered.diagnostics.joinToString { it.message }}")
        }
        state.show.fixtures.fxDefinitionListChanged()
        return success("${if (created) "Created" else "Replaced"} custom FX $fxId", buildJsonObject {
            put("fxId", fxId)
            put("created", created)
            put("outputType", outputType.name)
            put("fxMode", mode.name)
            putJsonArray("trialRun") {
                trial.forEach { (at, output) -> addJsonObject { put("at", at); put("output", output) } }
            }
            if (warnings.isNotEmpty()) putDiagnostics(warnings.map { Triple(it.severity, it.message, it.location) }, "warnings")
        })
    }

    /** Puts this row's definition fields back as they are now. */
    private fun DaoFxDefinition.restorer(): DaoFxDefinition.() -> Unit {
        val name = name; val family = family; val outputType = outputType; val fxMode = fxMode
        val parameters = parameters; val compatibleAttributes = compatibleAttributes; val script = script
        val defaultTiming = defaultTiming; val timingSource = timingSource
        return {
            this.name = name; this.family = family; this.outputType = outputType; this.fxMode = fxMode
            this.parameters = parameters; this.compatibleAttributes = compatibleAttributes; this.script = script
            this.defaultTiming = defaultTiming; this.timingSource = timingSource
        }
    }

    private fun deleteFxScript(input: JsonObject): ToolExecutionResult {
        val fxId = input.text("fxId")?.trim()?.takeIf { it.isNotEmpty() } ?: return failure("Missing 'fxId'")
        val deleted = transaction(state.database) {
            DaoFxDefinition.find {
                (DaoFxDefinitions.project eq state.show.project.id) and (DaoFxDefinitions.fxId eq fxId)
            }.singleOrNull()?.also { it.delete() }
        }
        if (deleted == null) {
            val builtIn = state.show.fxRegistry.getRegistration(fxId)?.source == FxSource.BUILT_IN
            return failure(if (builtIn) "'$fxId' is built in and cannot be deleted" else "No custom FX '$fxId' in this show")
        }
        state.show.fxRegistry.unregister(fxId)
        state.show.fixtures.fxDefinitionListChanged()
        return success("Deleted custom FX $fxId", buildJsonObject { put("deleted", fxId) })
    }

    private fun previewFx(input: JsonObject): ToolExecutionResult {
        val running = input.present("fxId")?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.content?.toLongOrNull() ?: throw IllegalArgumentException("fxId must be an integer") }
        val draftKeys = input.keys - PREVIEW_ONLY
        if (running != null && draftKeys.isNotEmpty()) return failure("Give fxId or a draft (${draftKeys.joinToString()}), not both")
        if (running == null && (input.text("fxType") == null || input.text("attribute") == null)) {
            return failure("Give fxType and attribute for a draft, or fxId for a running FX")
        }
        val targets = (input.present("targets") as? JsonArray)?.mapIndexed { i, element ->
            val obj = element as? JsonObject
            val type = (obj?.get("type") as? JsonPrimitive)?.content
            val key = (obj?.get("key") as? JsonPrimitive)?.content
            if (type == null || key == null || TargetRef.ofOrNull(type, key) == null) throw IllegalArgumentException("targets[$i] must be {type: 'fixture' | 'group', key}")
            CueTargetDto(type, key)
        }.orEmpty()
        val heads = input.integer("heads")
        if (heads != null && targets.isNotEmpty()) return failure("Give targets or heads, not both")
        if (heads != null && running != null) return failure("heads draws a draft; a running FX (fxId) is drawn on the heads it runs on")
        val cycles = input.present("cycles")?.let { (it as? JsonPrimitive)?.doubleOrNull ?: throw IllegalArgumentException("cycles must be a number") } ?: 1.0
        val wanted = input.integer("samples") ?: 12
        if (wanted !in 2..48) return failure("samples must be from 2 to 48")

        val draft = if (running == null) fxBodyAsToday(buildJsonObject {
            for (key in draftKeys) {
                val value = input.getValue(key)
                if (key == "parameters") put(key, stringParameters(value)) else if (value !is JsonNull) put(key, value)
            }
        }) else null
        val notional = if (targets.isEmpty() && running == null) heads ?: 8 else null
        val request = FxPreviewRequest(
            targets = targets,
            fx = listOf(FxPreviewEntry(fxId = running, draft = draft)),
            include = "ONLY",
            window = FxPreviewWindow(cycles = cycles),
            notional = notional,
        )
        val response = when (val outcome = previewFx(state, request)) {
            is FxPreviewOutcome.Invalid -> return failure(outcome.problems.joinToString("; "))
            is FxPreviewOutcome.Done -> outcome.response
        }
        val n = response.window.samples
        val picks = (0 until minOf(wanted, n)).map { i -> if (wanted >= n) i else (i.toLong() * (n - 1) / (wanted - 1)).toInt() }
        return success("Sampled ${response.rows.size} head(s) over ${"%.2f".format(response.window.seconds)} s", buildJsonObject {
            putJsonObject("window") {
                put("beats", response.window.beats)
                put("seconds", response.window.seconds)
            }
            putJsonArray("atMs") { picks.forEach { add((it * response.window.sampleMs).toLong()) } }
            putJsonArray("heads") {
                for (lane in response.lanes) for (attribute in lane.attributes) for (row in attribute.rows) {
                    val head = response.rows[row.row]
                    addJsonObject {
                        put("head", head.key)
                        put("attribute", attribute.attribute)
                        putJsonArray("values") { readable(row.result, picks).forEach { add(it) } }
                    }
                }
            }
            if (response.truncated.isNotEmpty()) putJsonArray("truncated") { response.truncated.forEach { add(it) } }
        })
    }

    /** [series]' values at [picks], as the guide spells them: a level, a colour, or `pan,tilt`. */
    private fun readable(series: FxPreviewSeries, picks: List<Int>): List<String> {
        val planes = series.planes.mapValues { Base64.getDecoder().decode(it.value) }
        fun at(plane: String, i: Int) = planes[plane]?.getOrNull(i)?.toInt()?.and(0xff)
        return picks.map { i ->
            when {
                "level" in planes -> "${at("level", i)}"
                "pan" in planes -> "${at("pan", i)},${at("tilt", i)}"
                else -> buildString {
                    append("#%02x%02x%02x".format(at("red", i), at("green", i), at("blue", i)))
                    at("white", i)?.takeIf { it > 0 }?.let { append(";w$it") }
                    at("amber", i)?.takeIf { it > 0 }?.let { append(";a$it") }
                    at("uv", i)?.takeIf { it > 0 }?.let { append(";uv$it") }
                }
            }
        }
    }

    private sealed interface Trial {
        data class Ran(val samples: List<Pair<String, String>>) : Trial
        data class Refused(val message: String) : Trial
    }

    /**
     * Run [compiled] as the engine would on four heads, with its default parameters, off the calling
     * thread and against a deadline: a script that throws, loops or answers the wrong output is refused
     * before the output loop ever runs it. A STANDARD FX is asked at eight phases of head 1 and once on
     * each head; a STATEFUL one steps sixteen 20 ms ticks over the four heads.
     */
    private fun trialRun(
        compiled: CompiledFxScript,
        parameters: List<ParameterInfo>,
        name: String,
        outputType: FxOutputType,
        defaultTiming: Timing,
    ): Trial {
        val factory = ScriptFxAdapter.createFactory(compiled, parameters, name, outputType, defaultTiming)
        fun context(member: Int) = FxContext(groupSize = 4, memberIndex = member, distributionOffset = member / 4.0, hasDistributionSpread = true)
        val task = FutureTask {
            val fx = factory(parameters.associate { it.name to it.defaultValue }, null, null)
            val samples = mutableListOf<Pair<String, FxOutput>>()
            if (fx is StatefulFx) {
                fx.initialize()
                for (i in 0 until 16) {
                    val tick = MasterClock.ClockTick(i.toLong(), i / 24L, i % 24, (i % 24) / 24.0, 1_000_000L + i * 20L)
                    val member = i % 4
                    samples += "tick $i, head ${member + 1}" to fx.calculateStateful(tick, if (i < 4) 0L else 20L, context(member))
                }
            } else {
                for (i in 0 until 8) samples += "phase ${i / 8.0}, head 1" to fx.calculate(i / 8.0, context(0))
                for (member in 1 until 4) samples += "phase 0.5, head ${member + 1}" to fx.calculate(0.5, context(member))
            }
            samples
        }
        val samples = synchronized(stuckTrial) {
            if (stuckTrial.get()?.isAlive == true) {
                return Trial.Refused(
                    "The desk is still running a stuck script from an earlier save's trial run, which only a restart of " +
                        "the desk reclaims; no FX can be saved until then",
                )
            }
            val thread = Thread(task, "fx-script-trial").apply { isDaemon = true }
            thread.start()
            try {
                task.get(TRIAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                task.cancel(true)
                thread.join(TRIAL_STOP_GRACE_MS)
                if (thread.isAlive) stuckTrial.set(thread)
                return Trial.Refused("The script took over ${TRIAL_TIMEOUT_MS / 1000} s on its trial run, far too slow for the output loop")
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                return Trial.Refused("The script threw ${cause::class.simpleName}: ${cause.message ?: "no message"} on its trial run with its default parameters")
            }
        }
        val expected = when (outputType) {
            FxOutputType.SLIDER -> FxOutput.Slider::class
            FxOutputType.COLOUR -> FxOutput.Colour::class
            FxOutputType.POSITION -> FxOutput.Position::class
            FxOutputType.STEPS -> FxOutput.Steps::class
        }
        samples.firstOrNull { !expected.isInstance(it.second) }?.let { (at, output) ->
            return Trial.Refused(
                "At $at the script answered ${output::class.simpleName}, but its outputType $outputType needs FxOutput.${expected.simpleName}",
            )
        }
        return Trial.Ran(samples.map { (at, output) -> at to describe(output) })
    }

    private fun describe(output: FxOutput): String = when (output) {
        is FxOutput.Slider -> "${output.value}"
        is FxOutput.Colour -> output.color.toSerializedString()
        is FxOutput.Position -> "${output.pan},${output.tilt}"
        is FxOutput.Steps -> "steps"
    }

    private fun JsonObjectBuilder.putDiagnostics(
        diagnostics: List<Triple<String, String, String?>>,
        key: String = "messages",
    ) {
        putJsonArray(key) {
            diagnostics.forEach { (severity, message, location) ->
                addJsonObject {
                    put("severity", severity)
                    put("message", message)
                    location?.let { put("at", it) }
                }
            }
        }
    }

    private fun stringParameters(value: JsonElement): JsonObject =
        (value as? JsonObject)?.let { params ->
            JsonObject(params.mapValues { (name, v) ->
                (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { JsonPrimitive(it.content) }
                    ?: throw IllegalArgumentException("parameters.$name must be a string")
            })
        } ?: throw IllegalArgumentException("'parameters' must be an object of strings")

    private fun JsonObject.present(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

    private fun JsonObject.text(key: String): String? = present(key)?.let {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("'$key' must be a string")
    }

    private fun JsonObject.integer(key: String): Int? = present(key)?.let {
        (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull ?: throw IllegalArgumentException("'$key' must be an integer")
    }

    private fun <E : Enum<E>> JsonObject.enumOf(key: String, values: List<E>): E? = text(key)?.let { raw ->
        values.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("$key '$raw' is not one of ${values.joinToString()}")
    }

    private fun success(description: String, result: JsonObject) = ToolExecutionResult(true, description, result.toString())

    private fun failure(message: String) = ToolExecutionResult(
        success = false,
        description = message,
        result = buildJsonObject { put("error", message) }.toString(),
    )
}
