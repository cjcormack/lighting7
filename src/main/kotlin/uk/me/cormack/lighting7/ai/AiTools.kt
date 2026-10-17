package uk.me.cormack.lighting7.ai

import uk.me.cormack.lighting7.fixture.TriggerIndex
import uk.me.cormack.lighting7.fixture.CommandIndex
import uk.me.cormack.lighting7.fx.stackEntryPriorities
import uk.me.cormack.lighting7.routes.runFixtureCommand
import uk.me.cormack.lighting7.routes.FixtureCommandResult
import uk.me.cormack.lighting7.dmx.UnparkOutcome
import uk.me.cormack.lighting7.models.CueTargetDto

import kotlinx.serialization.json.*
import kotlinx.serialization.json.JsonNull
import uk.me.cormack.lighting7.fx.toMaskNames
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import uk.me.cormack.lighting7.fixture.group.detectCapabilities
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import uk.me.cormack.lighting7.fx.CueStackManager
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.routes.*
import uk.me.cormack.lighting7.fx.TemplateProperty
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State
import org.jetbrains.exposed.v1.core.isNotNull

/**
 * The show-running tools the MCP server offers, and their dispatch.
 *
 * Each tool maps to existing backend functionality. Adding a new tool
 * requires adding a schema to [toolDefs] and a handler branch in [executeTool].
 *
 * There is no script tool. MCP adds a new actor — a model reading fixture names, cue notes and
 * synced content it did not write — and a literal-script tool is the step that turns a prompt
 * injection into code running as the desk process. Dropped by decision (see
 * `docs/mcp-engineering.md` §"No script tool").
 */
class AiTools(private val state: State) {

    private val programmer = ProgrammerTools(state)

    val toolDefs: List<ToolDef> = listOf(
        createLookTool,
        updateLookTool,
        deleteLookTool,
        applyLookTool,
        setBpmTool,
        createSpeedMasterTool,
        clearEffectsTool,
        parkChannelTool,
        unparkChannelTool,
        aimFixturesTool,
        getCurrentStateTool,
        createCueTool,
        applyCueTool,
        stopCueTool,
        createCueStackTool,
        activateCueStackTool,
        deactivateCueStackTool,
        advanceCueStackTool,
        addCueToStackTool,
        setStandbyTool,
        goCueStackTool,
        recordCueTool,
        includeIntoProgrammerTool,
        updateFromProgrammerTool,
        setProgrammerTool,
        clearProgrammerTool,
        setBlindTool,
        applyTemplateTool,
        recordLookTool,
        createTemplateTool,
        setSceneryTool,
        moveSceneryTool,
        setCueEventsTool,
        runFixtureCommandTool,
    )

    /**
     * Execute a tool by name and return a JSON result string.
     */
    suspend fun executeTool(name: String, input: JsonObject): ToolExecutionResult {
        return try {
            when (name) {
                "create_look" -> executeCreateLook(input)
                "update_look" -> executeUpdateLook(input)
                "delete_look" -> executeDeleteLook(input)
                "apply_look" -> executeApplyLook(input)
                "set_bpm" -> executeSetBpm(input)
                "create_speed_master" -> executeCreateSpeedMaster(input)
                "clear_effects" -> executeClearEffects(input)
                "park_channel" -> executeParkChannel(input)
                "unpark_channel" -> executeUnparkChannel(input)
                "aim_fixtures" -> executeAimFixtures(input)
                "get_current_state" -> executeGetCurrentState(input)
                "create_cue" -> executeCreateCue(input)
                "apply_cue" -> executeApplyCue(input)
                "stop_cue" -> executeStopCue(input)
                "create_cue_stack" -> executeCreateCueStack(input)
                "activate_cue_stack" -> executeActivateCueStack(input)
                "deactivate_cue_stack" -> executeDeactivateCueStack(input)
                "advance_cue_stack" -> executeAdvanceCueStack(input)
                "add_cue_to_stack" -> executeAddCueToStack(input)
                "set_standby" -> executeSetStandby(input)
                "go_cue_stack" -> executeGoCueStack(input)
                "record_cue" -> executeRecordCue(input)
                "include_into_programmer" -> executeInclude(input)
                "update_from_programmer" -> executeUpdate(input)
                "set_programmer" -> programmer.setProgrammer(input)
                "clear_programmer" -> programmer.clearProgrammer(input)
                "set_blind" -> programmer.setBlind(input)
                "apply_palette" -> programmer.applyTemplate(input)
                "record_look" -> programmer.recordLook(input)
                "create_palette" -> executeCreateTemplate(input)
                "set_scenery" -> executeSetScenery(input)
                "move_scenery" -> executeMoveScenery(input)
                "set_cue_events" -> executeSetCueEvents(input)
                RUN_FIXTURE_COMMAND -> executeRunFixtureCommand(input)
                else -> ToolExecutionResult(
                    success = false,
                    description = "Unknown tool: $name",
                    result = """{"error": "Unknown tool: $name"}"""
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                description = "Error executing $name: ${e.message}",
                // Built rather than interpolated: a message holding a backslash or a newline made
                // the hand-escaped string invalid JSON.
                result = buildJsonObject { put("error", e.message ?: "Unknown error") }.toString(),
            )
        }
    }

    // ─── Tool Executors ────────────────────────────────────────────────────

    private fun executeCreateLook(input: JsonObject): ToolExecutionResult {
        val name = input["name"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: return errorResult("Missing 'name'")
        val notes = input["description"]?.jsonPrimitive?.contentOrNull
        val effectsArray = input["effects"]?.jsonArray ?: JsonArray(emptyList())
        val valuesArray = input["values"]?.jsonArray ?: JsonArray(emptyList())
        val sceneryArray = input["scenery"]?.jsonArray
        if (effectsArray.isEmpty() && valuesArray.isEmpty() && sceneryArray.isNullOrEmpty()) {
            return errorResult("Give 'values', 'effects', 'scenery', or any mix of them")
        }

        val effects = parseLookEffects(effectsArray)

        val applyTargets = input["applyToTargets"]?.jsonArray?.let(::parseTargets).orEmpty()
        val apply = input["apply"]?.jsonPrimitive?.booleanOrNull ?: applyTargets.isNotEmpty()
        if (apply && applyTargets.isEmpty() && effects.isNotEmpty()) {
            return errorResult("A look with effects needs applyToTargets to apply it on: its effects run on a selection")
        }

        val values = resolveLookValues(state.show.fixtures, valuesArray)
        if (values.problems.isNotEmpty()) return errorResult(values.problems.joinToString("; "))
        validateLookRows(values.rows)?.let { return errorResult(it) }

        val project = state.projectManager.currentProject
        val scenery = sceneryArray?.let { items ->
            val problems = mutableListOf<String>()
            val writes = transaction(state.database) {
                parseToolSceneryList(items, sceneryElementsOf(project), SceneryOwnerKind.LOOK, "scenery", problems)
            }
            if (problems.isNotEmpty()) return errorResult(problems.joinToString("; "))
            writes
        }.orEmpty()
        val look = transaction(state.database) {
            if (!DaoLook.find { (DaoLooks.project eq project.id) and (DaoLooks.name eq name) }.empty()) {
                return@transaction null
            }
            val created = DaoLook.new {
                this.name = name
                this.notes = notes
                this.project = project
            }
            // Every effect is deferred: the tool describes the effects, and the targets come from
            // whatever applies the look. Values are bound rows, each naming its own fixture or group.
            writeLookChildren(created, values.rows, effects, created = true)
            replaceLookScenery(created, scenery)
            created
        } ?: return errorResult("A look named '$name' already exists in this project")
        state.show.fixtures.lookListChanged()

        val lookId = look.id.value

        val press = if (apply) pressLook(lookId, applyTargets, beatDivision = null) else null
        val pressed = press as? LookPress.Pressed

        return ToolExecutionResult(
            success = true,
            description = "Created look '$name' (id=$lookId, ${values.rows.size} values, ${effects.size} effects)" +
                (pressed?.let { ", ${it.action} it" } ?: "") +
                ((press as? LookPress.Refused)?.let { ", not applied: ${it.message}" } ?: ""),
            result = buildJsonObject {
                put("lookId", lookId)
                put("name", name)
                put("valueCount", values.rows.size)
                put("effectCount", effects.size)
                putLookValues(values)
                pressed?.let {
                    put("action", it.action)
                    put("appliedEffectCount", it.effectCount)
                }
                (press as? LookPress.Refused)?.let { put("notApplied", it.message) }
            }.toString()
        )
    }

    /**
     * `update_look`: the REST `PUT /looks/{id}`'s edit from the tool's shape. Each list given
     * replaces that part of the Look and an omitted one is left alone; every part is checked before
     * anything is written.
     */
    private fun executeUpdateLook(input: JsonObject): ToolExecutionResult {
        val lookId = input["lookId"]?.jsonPrimitive?.intOrNull ?: return errorResult("Missing 'lookId'")
        val name = input.present("name")?.let { raw ->
            (raw as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: return errorResult("'name' must not be blank")
        }
        val notesGiven = "description" in input
        val notes = input.present("description")?.jsonPrimitive?.contentOrNull
        val valuesArray = input.present("values")?.let { it as? JsonArray ?: return errorResult("'values' must be an array") }
        val effectsArray = input.present("effects")?.let { it as? JsonArray ?: return errorResult("'effects' must be an array") }
        val sceneryArray = input.present("scenery")?.let { it as? JsonArray ?: return errorResult("'scenery' must be an array") }
        if (name == null && !notesGiven && valuesArray == null && effectsArray == null && sceneryArray == null) {
            return errorResult("Give 'name', 'description', 'values', 'effects' or 'scenery' to change")
        }

        val values = valuesArray?.let { resolveLookValues(state.show.fixtures, it) }
        if (values != null) {
            if (values.problems.isNotEmpty()) return errorResult(values.problems.joinToString("; "))
            validateLookRows(values.rows)?.let { return errorResult(it) }
        }
        val effects = effectsArray?.let { parseLookEffects(it) }
        effects?.let { validateLookEffects(it) }?.let { return errorResult(it) }

        val project = state.projectManager.currentProject
        val scenery = sceneryArray?.let { items ->
            val problems = mutableListOf<String>()
            val writes = transaction(state.database) {
                parseToolSceneryList(items, sceneryElementsOf(project), SceneryOwnerKind.LOOK, "scenery", problems)
            }
            if (problems.isNotEmpty()) return errorResult(problems.joinToString("; "))
            writes
        }

        var refusal: String? = null
        val written = transaction(state.database) {
            val look = DaoLook.findById(lookId)?.takeIf { it.project.id == project.id }
                ?: run { refusal = "Look not found: $lookId"; return@transaction null }
            if (name != null && name != look.name) {
                if (!DaoLook.find { (DaoLooks.project eq project.id) and (DaoLooks.name eq name) }.empty()) {
                    refusal = "A look named '$name' already exists in this project"
                    return@transaction null
                }
                look.name = name
            }
            if (notesGiven) look.notes = notes
            if (values != null || effects != null) writeLookChildren(look, values?.rows, effects)
            scenery?.let { replaceLookScenery(look, it) }
            look.name to look.uuid
        }
        val (lookName, uuid) = written ?: return errorResult(refusal ?: "Look not found: $lookId")
        // Stored as the REST edit stores it; the layer create_cue would now refuse is named instead.
        val untargetedCues = if (effects.isNullOrEmpty()) emptyList() else transaction(state.database) {
            DaoCueLayer.find { DaoCueLayers.look eq lookId }.filter { it.targets.isEmpty() }.map { it.cue.name }.distinct().sorted()
        }
        val contentsChanged = values != null || effects != null
        // A contents change moves every live cue layering the Look, as the REST edit does. The list
        // signal is what the library, the busk pads and the live scenery read a rename, new notes or
        // new scenery from.
        val republished = if (contentsChanged) republishForLookEdit(state, uuid) else null
        if (name != null || notesGiven || scenery != null) state.show.fixtures.lookListChanged()

        val changed = listOfNotNull(
            "name".takeIf { name != null },
            "description".takeIf { notesGiven },
            "values".takeIf { values != null },
            "effects".takeIf { effects != null },
            "scenery".takeIf { scenery != null },
        )
        return ToolExecutionResult(
            success = true,
            description = "Updated look '$lookName' (id=$lookId): ${changed.joinToString(", ")}" +
                (republished?.cuesRepublished?.takeIf { it.isNotEmpty() }?.let { ", ${it.size} live cue(s) moved" } ?: "") +
                (if (untargetedCues.isEmpty()) "" else "; its effects run on nothing in ${untargetedCues.joinToString()}, which layer it with no targets"),
            result = buildJsonObject {
                put("lookId", lookId)
                put("name", lookName)
                put("changed", buildJsonArray { changed.forEach { add(it) } })
                values?.let { put("valueCount", it.rows.size); putLookValues(it) }
                effects?.let { put("effectCount", it.size) }
                scenery?.let { put("sceneryCount", it.size) }
                republished?.let { r -> put("cuesRepublished", buildJsonArray { r.cuesRepublished.forEach { add(it) } }) }
                putStrings("warnings", untargetedCues.map { "cue '$it' layers this look with no targets, so its effects run on nothing there — give that layer targets" })
            }.toString(),
        )
    }

    /** `delete_look`: the REST delete, refused while a cue layers the Look unless `force`. */
    private fun executeDeleteLook(input: JsonObject): ToolExecutionResult {
        val lookId = input["lookId"]?.jsonPrimitive?.intOrNull ?: return errorResult("Missing 'lookId'")
        val force = input["force"]?.jsonPrimitive?.booleanOrNull ?: false
        return when (val outcome = performLookDelete(state, state.projectManager.currentProject, lookId, force)) {
            LookDeleteOutcome.NotFound -> errorResult("Look not found: $lookId")
            is LookDeleteOutcome.InUse -> {
                val message = "Look $lookId is still used by ${outcome.usage.describe()} — pass force: true to delete it " +
                    "and those layers with it"
                ToolExecutionResult(
                    success = false,
                    description = message,
                    result = buildJsonObject {
                        put("error", message)
                        put("code", CODE_LOOK_IN_USE)
                        put("layerCount", outcome.usage.layerCount)
                        put("cueIds", buildJsonArray { outcome.usage.cueIds.forEach { add(it) } })
                        put("cueNames", buildJsonArray { outcome.usage.cueNames.forEach { add(it) } })
                    }.toString(),
                )
            }
            is LookDeleteOutcome.Deleted -> ToolExecutionResult(
                success = true,
                description = "Deleted look '${outcome.name}' (id=$lookId)",
                result = buildJsonObject {
                    put("lookId", lookId)
                    put("deleted", true)
                }.toString(),
            )
        }
    }

    /** A Look's stored values and the heads they skipped, as create_look and update_look answer them. */
    private fun JsonObjectBuilder.putLookValues(values: ResolvedLookValues) {
        putJsonArray("values") {
            values.rows.forEach { row ->
                addJsonObject {
                    put("target", buildJsonObject { put("type", row.targetType); put("key", row.targetKey) })
                    row.elementKey?.let { put("elementKey", it) }
                    put("propertyName", row.propertyName)
                    put("value", row.value)
                }
            }
        }
        putValueSkips(values.skipped)
    }

    private fun executeApplyLook(input: JsonObject): ToolExecutionResult {
        val lookId = input["lookId"]?.jsonPrimitive?.int ?: return errorResult("Missing 'lookId'")
        val targets = input["targets"]?.jsonArray?.let(::parseTargets).orEmpty()
        val beatDivision = input["beatDivision"]?.jsonPrimitive?.doubleOrNull
        if (input["asValues"]?.jsonPrimitive?.booleanOrNull == true) return applyLookValues(lookId, targets, beatDivision)

        return when (val outcome = pressLook(lookId, targets, beatDivision)) {
            is LookPress.Refused -> errorResult(outcome.message)
            is LookPress.Pressed -> ToolExecutionResult(
                success = true,
                description = "${outcome.action} look $lookId (${outcome.effectCount} effects)",
                result = buildJsonObject {
                    put("action", outcome.action)
                    put("effectCount", outcome.effectCount)
                }.toString()
            )
        }
    }

    /** `asValues`: the Look's values and effects into the programmer's own, as `POST /looks/{id}/apply`. */
    private fun applyLookValues(lookId: Int, requested: List<CueTargetDto>, beatDivision: Double?): ToolExecutionResult {
        val project = state.projectManager.currentProject
        val look = transaction(state.database) {
            DaoLook.findById(lookId)?.takeIf { it.project.id == project.id }?.toggleSource(state.show.fixtures)
        } ?: return errorResult("Look not found: $lookId")
        return when (val outcome = applyLookAsValues(state, look, requested, families = null, fadeMs = null, beatDivision)) {
            is LookApplyOutcome.Refused -> errorResult(outcome.message)
            is LookApplyOutcome.Applied -> ToolExecutionResult(
                success = true,
                description = "Applied look $lookId as values: ${outcome.response.written} value(s), " +
                    "${outcome.response.effectIds.size} effect(s)",
                result = buildJsonObject {
                    put("written", outcome.response.written)
                    put("effectIds", buildJsonArray { outcome.response.effectIds.forEach { add(JsonPrimitive(it)) } })
                }.toString(),
            )
        }
    }

    private sealed interface LookPress {
        data class Pressed(val action: String, val effectCount: Int) : LookPress
        data class Refused(val message: String) : LookPress
    }

    /**
     * Toggle a Look as a programmer layer — the layer the busking pads add, so the AI and the pads
     * toggle the *same* thing rather than two mechanisms that each think they own the Look. No
     * targets means the Look's own fixtures, by the REST toggle's rule ([resolveLookToggleTargets]).
     */
    private fun pressLook(lookId: Int, requested: List<CueTargetDto>, beatDivision: Double?): LookPress {
        val project = state.projectManager.currentProject
        val look = transaction(state.database) {
            DaoLook.findById(lookId)?.takeIf { it.project.id == project.id }?.toggleSource(state.show.fixtures)
        } ?: return LookPress.Refused("Look not found: $lookId")
        val targets = when (val resolved = resolveLookToggleTargets(requested, look)) {
            is LookTargetResolution.Refused -> return LookPress.Refused(resolved.message)
            is LookTargetResolution.Targets -> resolved.targets
        }
        val outcome = state.show.programmerLayerStack.toggle(
            source = look.source,
            targets = targets,
            beatDivisionOverride = beatDivision,
        )
        return LookPress.Pressed(outcome.action, outcome.effectCount)
    }

    private fun parseTargets(array: JsonArray): List<CueTargetDto> = array.map { t ->
        val obj = t.jsonObject
        CueTargetDto(
            type = obj["type"]!!.jsonPrimitive.content,
            key = obj["key"]!!.jsonPrimitive.content,
        )
    }

    private fun executeSetBpm(input: JsonObject): ToolExecutionResult {
        val bpm = input["bpm"]?.jsonPrimitive?.double ?: return errorResult("Missing 'bpm'")
        // Omitting the reference still means the global tempo — master 1 — which is what the
        // tool meant before the bank existed. Routed through the bank either way so the change
        // is tracked, pushed, and written through like any other tempo write.
        val requested = input["speedMasterUuid"]?.jsonPrimitive?.contentOrNull
        val master = resolveSpeedMaster(requested)
            ?: return errorResult(unknownSpeedMasterMessage(requested))
        // The bank looks the uuid up again, so a master deleted between the two lookups makes
        // this a dropped write — report that rather than the success the resolve implied. A
        // follower is refused distinctly: the fix is to unlink it or retune master 1, not to
        // retry with another uuid.
        when (val outcome = state.show.fxEngine.speedMasters.setBpm(
            master.uuid, bpm, uk.me.cormack.lighting7.models.SpeedMasterSource.MANUAL
        )) {
            is uk.me.cormack.lighting7.fx.SpeedMasterBank.TempoWriteOutcome.Applied -> {}
            is uk.me.cormack.lighting7.fx.SpeedMasterBank.TempoWriteOutcome.UnknownMaster ->
                return errorResult(unknownSpeedMasterMessage(requested))
            is uk.me.cormack.lighting7.fx.SpeedMasterBank.TempoWriteOutcome.RefusedFollower ->
                // The shared phrasing plus this surface's own advice, per
                // RefusedFollower.describe's contract — a wording change lands in one place.
                return errorResult(
                    "${outcome.describe}. From here, retune Master 1 instead: its followers " +
                        "move with it."
                )
        }
        return ToolExecutionResult(
            success = true,
            description = "Set ${master.name} to $bpm BPM",
            result = buildJsonObject {
                put("bpm", bpm)
                put("speedMasterIndex", master.index)
                put("name", master.name)
                master.uuid?.let { put("speedMasterUuid", it.toString()) }
            }.toString()
        )
    }

    private fun executeCreateSpeedMaster(input: JsonObject): ToolExecutionResult {
        val request = CreateSpeedMasterRequest(
            name = input["name"]?.jsonPrimitive?.contentOrNull,
            bpm = input["bpm"]?.jsonPrimitive?.doubleOrNull,
            notes = input["notes"]?.jsonPrimitive?.contentOrNull,
        )
        // Same helper the REST route uses, so an AI-created master is indistinguishable from a
        // UI-created one — including the bank reload that makes its uuid resolvable straight away
        // by the effect-authoring tools below.
        return when (val outcome = createSpeedMaster(state, state.projectManager.currentProject, request)) {
            is CreateSpeedMasterOutcome.Invalid -> errorResult(outcome.error)
            is CreateSpeedMasterOutcome.Conflict -> errorResult(outcome.error)
            is CreateSpeedMasterOutcome.Created -> ToolExecutionResult(
                success = true,
                description = "Created speed master '${outcome.dto.name}' " +
                        "(index ${outcome.dto.masterIndex}, ${outcome.dto.bpm} BPM)",
                result = buildJsonObject {
                    put("speedMasterId", outcome.dto.id)
                    put("speedMasterUuid", outcome.dto.uuid)
                    put("masterIndex", outcome.dto.masterIndex)
                    put("name", outcome.dto.name)
                    put("bpm", outcome.dto.bpm)
                }.toString()
            )
        }
    }

    private fun executeClearEffects(input: JsonObject): ToolExecutionResult {
        val targets = input["targets"]?.jsonArray

        if (targets == null || targets.isEmpty()) {
            state.show.fxEngine.clearAllEffects()
            return ToolExecutionResult(
                success = true,
                description = "Cleared all effects",
                result = """{"cleared": "all"}"""
            )
        }

        var totalRemoved = 0
        val refs = targets.mapNotNull { target ->
            val obj = target.jsonObject
            TargetRef.ofOrNull(obj["type"]!!.jsonPrimitive.content, obj["key"]!!.jsonPrimitive.content)
        }
        for (ref in refs) {
            totalRemoved += when (ref) {
                is TargetRef.Group -> state.show.fxEngine.removeEffectsForGroup(ref.key)
                is TargetRef.Fixture -> state.show.fxEngine.removeEffectsForFixture(ref.key)
            }
        }
        totalRemoved += state.show.fxEngine.removeSelectionEffectsWithin(refs)

        return ToolExecutionResult(
            success = true,
            description = "Cleared $totalRemoved effects",
            result = """{"removedCount": $totalRemoved}"""
        )
    }

    private fun executeGetCurrentState(input: JsonObject): ToolExecutionResult {
        val include = input["include"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
            ?: setOf(
                "active_effects", "bpm", "speed_masters", "fixtures", "groups", "looks",
                "palettes", "cues", "cue_stacks", "cue_run", "programmer", "selection", "windows",
                "parked",
            )

        val result = buildJsonObject {
            if ("bpm" in include) {
                put("bpm", state.show.fxEngine.masterClock.bpm.value)
                put("clockRunning", state.show.fxEngine.masterClock.isRunning.value)
            }

            if ("speed_masters" in include) {
                // The uuids belong here as well as in `describe_rig`: every effect-authoring
                // tool takes a `speedMasterUuid`, and a master created mid-conversation (by
                // `create_speed_master`, or by the operator at the desk) is unnameable until the
                // model can read its uuid back. A `describe_rig` answer is a snapshot of when it
                // was called.
                put("speedMasters", buildJsonArray {
                    for (master in state.show.fxEngine.speedMasters.masterStates()) {
                        addJsonObject {
                            // Absent before the bank has loaded its rows, and only for master 1.
                            // Omitting the reference is exactly what names master 1 anyway, so
                            // there is nothing the model loses by the key not being there.
                            master.uuid?.let { put("uuid", it.toString()) }
                            put("index", master.index)
                            put("name", master.name)
                            put("bpm", master.bpm)
                            put("isRunning", master.isRunning)
                            // Routing/follow settings, so the model knows a follower's tempo
                            // is derived (set_bpm refuses it) and which master an unassigned
                            // effect of each category will land on.
                            master.usage?.let { put("usage", it) }
                            master.followNum?.let { put("followNum", it) }
                            master.followDen?.let { put("followDen", it) }
                            master.followTargetUuid?.let { put("followTargetUuid", it.toString()) }
                        }
                    }
                })
            }

            if ("active_effects" in include) {
                put("activeEffects", buildJsonArray {
                    for (effect in state.show.fxEngine.getActiveEffects()) {
                        addJsonObject {
                            put("id", effect.id)
                            // The registration id, not the display name: the model echoes this
                            // straight back into add_effect, and a user-defined effect's name
                            // resolves to nothing in the registry.
                            put("effectType", effect.effectTypeId)
                            put("targetKey", effect.target.targetKey)
                            put("propertyName", effect.target.propertyName)
                            put("isGroupTarget", effect.isGroupEffect)
                            put("targetType", effect.target.targetRef.discriminator)
                            (effect.target.targetRef as? FxTargetRef.SelectionRef)?.let { ref ->
                                put("targets", buildJsonArray {
                                    for (item in ref.items) addJsonObject {
                                        put("type", item.discriminator)
                                        put("key", item.key)
                                    }
                                })
                            }
                            put("beatDivision", effect.timing.beatDivision)
                            // D7: the division is seconds and no master is followed.
                            if (effect.onSeconds) put("seconds", true)
                            // Effects plan D2, D5, D9 — each said only where it is not the default.
                            val dyn = effect.dynamics
                            if (dyn.size != 1.0) put("size", dyn.size)
                            if (dyn.direction != EffectDirection.FORWARD) put("direction", dyn.direction.name)
                            dyn.nShot?.let { put("nShot", it) }
                            // D4's shape, likewise.
                            if (dyn.spread != 1.0) put("spread", dyn.spread)
                            if (dyn.wings != 1) put("wings", dyn.wings)
                            if (dyn.blocks != 1) put("blocks", dyn.blocks)
                            if (dyn.groups != 0) put("groups", dyn.groups)
                            put("blendMode", effect.blendMode.name)
                            put("isRunning", effect.isRunning)
                            if (effect.leaving) put("leaving", true)
                            effect.cueId?.let { put("cueId", it) }
                            effect.cueStackId?.let { put("cueStackId", it) }
                        }
                    }
                })
            }

            if ("fixtures" in include) {
                put("fixtures", buildJsonArray {
                    val infrastructureKeys = state.show.fixtures.infrastructureKeys()
                    for (fixture in state.show.fixtures.fixtures) {
                        addJsonObject {
                            put("key", fixture.key)
                            put("name", fixture.fixtureName)
                            put("typeKey", fixture.typeKey)
                            put("groups", buildJsonArray {
                                state.show.fixtures.groupsForFixture(fixture.key).forEach { add(it) }
                            })
                            // Listed, so a cue or group that names it still reads, but marked the way
                            // `describe_rig` sets it apart: not lighting, and never a target to offer.
                            if (fixture.key in infrastructureKeys) put("infrastructure", true)
                        }
                    }
                })
            }

            if ("groups" in include) {
                put("groups", buildJsonArray {
                    for (group in state.show.fixtures.groups) {
                        addJsonObject {
                            put("name", group.name)
                            put("memberCount", group.allMembers.size)
                            put("capabilities", buildJsonArray {
                                group.detectCapabilities().forEach { add(it) }
                            })
                        }
                    }
                })
            }

            if ("looks" in include) {
                val project = state.projectManager.currentProject
                val looks = transaction(state.database) {
                    DaoLook.find { DaoLooks.project eq project.id }
                        .map { it.id.value to it.name }
                }
                put("looks", buildJsonArray {
                    for ((id, name) in looks) {
                        addJsonObject {
                            put("id", id)
                            put("name", name)
                        }
                    }
                })
            }

            if ("palettes" in include) {
                // Generic colour templates only: they are the ones a `tmpl:` colour parameter can
                // name, and the uuid is what it names them by.
                val project = state.projectManager.currentProject
                val templates = transaction(state.database) {
                    DaoTemplate.find { DaoTemplates.project eq project.id }.mapNotNull { template ->
                        // [genericColourRows] owns this rule — see its doc for why the row count is
                        // not one of its clauses any more.
                        val rows = genericColourRows(
                            template.rows.toList(), { it.propertyName }, { it.isDeferred },
                        ) ?: return@mapNotNull null
                        buildJsonObject {
                            put("name", template.name)
                            put("ref", "tmpl:${template.uuid}")
                            put("intent", rows.joinToString(" ") { it.value })
                        }
                    }
                }
                put("palettes", buildJsonArray { templates.forEach { add(it) } })
            }

            if ("cues" in include) {
                val project = state.projectManager.currentProject
                val cues = transaction(state.database) {
                    DaoCue.find { DaoCues.project eq project.id }
                        .map { cue ->
                            buildJsonObject {
                                put("id", cue.id.value)
                                put("name", cue.name)
                                put("layerCount", cue.layers.count())
                                put("adHocEffectCount", cue.adHocEffects.count())
                            }
                        }
                }
                put("cues", buildJsonArray { cues.forEach { add(it) } })
            }

            if ("cue_stacks" in include) {
                val project = state.projectManager.currentProject
                val manager = state.show.cueStackManager
                val stacks = transaction(state.database) {
                    DaoCueStack.find { DaoCueStacks.project eq project.id }
                        .orderBy(DaoCueStacks.name to SortOrder.ASC)
                        .map { stack ->
                            val activeCueId = manager.getActiveCueId(stack.id.value)
                            val cueCount = DaoCueStackEntry.find {
                                (DaoCueStackEntries.stack eq stack.id) and (DaoCueStackEntries.cue.isNotNull())
                            }.count()
                            buildJsonObject {
                                put("id", stack.id.value)
                                put("name", stack.name)
                                put("cueCount", cueCount)
                                put("loop", stack.loop)
                                put("isActive", activeCueId != null)
                                activeCueId?.let { put("activeCueId", it) }
                            }
                        }
                }
                put("cueStacks", buildJsonArray { stacks.forEach { add(it) } })
            }

            if ("cue_run" in include) {
                // What the *next* GO will fire, which `cue_stacks` above cannot say: it reports
                // the live cue, and "next" is server-owned (an armed standby, else the positional
                // successor). A model asked to "hold, then go" has to be able to read the arming
                // it just made. Only stacks that have run state at all appear — the same set the
                // WebSocket connect snapshot walks — and the whole walk shares one transaction,
                // since `runStateFor` would otherwise open one per stack.
                val runState = state.show.cueStackManager.runState
                val frames = transaction(state.database) {
                    runState.stacksWithRunState().sorted().map { runState.runStateFor(state, it) }
                }
                put("cueRun", buildJsonArray {
                    for (frame in frames) {
                        addJsonObject {
                            put("stackId", frame.stackId)
                            frame.activeCueId?.let { put("activeCueId", it) }
                            frame.nextCueId?.let { put("nextCueId", it) }
                            put("nextIsArmed", frame.nextIsArmed)
                            frame.fadeDurationMs?.let { put("fadeDurationMs", it) }
                            // Only present while a fade is actually running.
                            frame.fadeElapsedMs?.let { put("fadeElapsedMs", it) }
                            put("autoAdvance", frame.autoAdvance)
                            frame.autoAdvanceDelayMs?.let { put("autoAdvanceDelayMs", it) }
                        }
                    }
                })
            }

            if ("programmer" in include) {
                // The AI mutates the programmer itself — and `apply_look` and `apply_palette` (unless
                // `asValues`) and `create_look`'s `applyToTargets` go through `programmerLayerStack.toggle`,
                // which *toggles*: without being able to read the stack back, a model asked to "add
                // the wash" a second time takes it off stage instead.
                val store = state.show.programmerStore
                put("programmer", buildJsonObject {
                    put("blind", store.blind)
                    put("layers", buildJsonArray {
                        for (layer in store.layers) {
                            addJsonObject {
                                put("layerId", layer.layerId)
                                // A palette layer's stored kind is TEMPLATE; Claude reads it as PALETTE.
                                put("sourceKind", if (layer.source.kind == LayerSourceKind.TEMPLATE) "PALETTE" else layer.source.kind.name)
                                put("sourceId", layer.source.id)
                                put("sourceName", layer.source.name)
                                put("sortOrder", layer.sortOrder)
                                put("enabled", layer.enabled)
                                put("blendMode", layer.blendMode)
                                put("amount", layer.amount)
                                put("targets", buildJsonArray {
                                    for (target in layer.targets) {
                                        addJsonObject {
                                            put("type", target.type)
                                            put("key", target.key)
                                        }
                                    }
                                })
                            }
                        }
                    })
                    // The winning slot only. The per-owner stack underneath it is provenance for
                    // the operator's UI; a model reading it could do nothing but re-derive the
                    // top, which is the value on stage.
                    put("entries", buildJsonArray {
                        val entries = store.entries()
                            .sortedWith(compareBy({ it.fixtureKey }, { it.propertyName }))
                        for (entry in entries) {
                            val top = entry.slots.first()
                            addJsonObject {
                                put("targetKey", entry.fixtureKey)
                                put("propertyName", entry.propertyName)
                                put("value", top.value.resolved.serialize())
                                put("owner", top.owner.id)
                                put("touched", top.touched)
                            }
                        }
                    })
                    // The raw-channel sideband is deliberately absent: no tool on this surface
                    // writes or clears a channel, so it would be state the model can read and
                    // never act on.
                    store.lastIncludedTarget?.let { target ->
                        put("lastIncluded", buildJsonObject {
                            put("kind", target.kind.name)
                            put("targetId", target.targetId)
                            target.entryId?.let { put("entryId", it) }
                        })
                    }
                    // The scenery the programmer holds (move_scenery, the rail's Scenery band): each element
                    // by name with the states held. Staged rather than shown while `blind`.
                    put("scenery", programmerSceneryJson())
                })
            }
            if ("selection" in include) {
                // The desk's one shared selection (state.DeskSelection): what the busk view and a
                // selection-relative surface control act on. Read-only here — every tool on this
                // surface passes explicit targets, so the model reads it for context, not to act
                // through it.
                val selection = state.deskSelection
                val snapshot = selection.state.value
                put("selection", buildJsonObject {
                    put("targets", buildJsonArray {
                        for (target in snapshot.targets) {
                            addJsonObject {
                                put("type", target.type)
                                put("key", target.key)
                            }
                        }
                    })
                    // The attribute mask a press on this selection is narrowed to, and who moved
                    // it last (multi-screen plan D2/D7). Both null when unmasked / unmoved, and
                    // written as null rather than omitted so the model sees the two keys exist.
                    put("families", snapshot.families?.let { families ->
                        buildJsonArray { families.toMaskNames().forEach { add(it) } }
                    } ?: JsonNull)
                    put("source", snapshot.source?.let { source ->
                        buildJsonObject {
                            put("kind", source.kind)
                            put("id", source.id)
                            put("name", source.name)
                        }
                    } ?: JsonNull)
                    put("coverage", buildJsonArray {
                        for (target in selection.coverage()) {
                            addJsonObject {
                                put("type", target.type)
                                put("key", target.key)
                            }
                        }
                    })
                })
            }
            if ("windows" in include) {
                // Every browser window signed in to the desk (state.WindowRegistry). Read-only,
                // like `selection`: no tool on this surface moves a window, so the model reads it
                // to say *which screen* something is on — and to read a `selection.source.id`
                // back to a name, which is the one cross-reference between the two objects.
                put("windows", buildJsonArray {
                    for (window in state.windowRegistry.windows.value) {
                        addJsonObject {
                            put("id", window.id)
                            put("name", window.name)
                            put("view", window.view)
                            put("fullscreen", window.fullscreen)
                            put("followsDeskSelection", window.follows)
                            put("user", window.user)
                        }
                    }
                })
            }
            if ("parked" in include) {
                // Park sits above every layer this surface writes, so a model that cannot see it
                // would apply a look, watch nothing change on a parked head, and have no way to
                // say why. Always present (empty when nothing is parked), like `windows`.
                put("parked", buildJsonArray {
                    for (parked in parkedChannelReports(state)) {
                        addJsonObject {
                            putParkedChannel(parked.universe, parked.channel, parked.value, parked.mapping)
                            put("owner", parked.owner.name)
                            parked.venueValue?.let { put("venueValue", it) }
                        }
                    }
                })
                // A show on a venue: the venue parks it has released for its run (unpark_channel on a
                // venue park), so a model can tell a channel the venue parks from one this show freed.
                if (state.show.parkManager.isOnVenue) {
                    put("releasedVenueParks", buildJsonArray {
                        for (released in releasedParkReports(state)) {
                            addJsonObject {
                                put("universe", released.universe)
                                put("channel", released.channel)
                                released.venueValue?.let { put("venueValue", it) }
                                released.mapping?.let { putChannelMapping(it) }
                            }
                        }
                    })
                }
            }
        }

        return ToolExecutionResult(
            success = true,
            description = "Retrieved current state",
            result = result.toString()
        )
    }

    /**
     * The same write as the WebSocket's `parkChannel` (`plugins/ParkSocket.kt`): [ParkManager.park]
     * persists and broadcasts `parkState`, and the provenance refresh is what moves the Channels
     * view's "parked" marker. Nothing to nudge on the controllers — they consult the park source
     * at transmit time, so the park lands on the next frame.
     *
     * The universe must be one the show outputs (the Channels view's list): a park on any other
     * address is stored and held but reaches no fixture, which is exactly the silent miss a model
     * counting universes from 1 would make.
     */
    private fun executeAimFixtures(input: JsonObject): ToolExecutionResult {
        val targetsArray = input["targets"] as? JsonArray ?: return errorResult("Missing 'targets'")
        val targets = targetsArray.map { t ->
            val obj = t as? JsonObject ?: return errorResult("Each target is an object with 'type' and 'key'")
            CueTargetDto(
                type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return errorResult("A target is missing 'type'"),
                key = obj["key"]?.jsonPrimitive?.contentOrNull ?: return errorResult("A target is missing 'key'"),
            )
        }
        val x = input["x"]?.jsonPrimitive?.doubleOrNull ?: return errorResult("Missing 'x'")
        val y = input["y"]?.jsonPrimitive?.doubleOrNull ?: return errorResult("Missing 'y'")
        val z = input["z"]?.jsonPrimitive?.doubleOrNull ?: return errorResult("Missing 'z'")
        val fadeMs = input["fadeMs"]?.jsonPrimitive?.longOrNull
        val dryRun = input["dryRun"]?.jsonPrimitive?.booleanOrNull ?: false
        val focus = input["focus"]?.jsonPrimitive?.booleanOrNull ?: false
        val saveAs = when (val raw = input["saveAsPalette"]) {
            null, JsonNull -> null
            else -> (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
                ?: return errorResult("saveAsPalette must be a palette name")
        }

        val project = state.projectManager.currentProject
        // Resolved without writing first, so a template that cannot be made (a name already taken)
        // refuses the whole call before any head moves — the template is the reason it was made.
        val aimed = when (val outcome = aimIntoProgrammer(state, project, targets, x, y, z, fadeMs, write = false)) {
            is AimOutcome.Invalid -> return errorResult(outcome.message)
            is AimOutcome.Done -> outcome
        }
        val resolved = aimed.response
        // Aims into a position template: one fixture-specific row per aimed head, centre-relative
        // (`pos:`, the unit every new position write takes) — each head's own coarse bytes, so it
        // resolves back to the aim's coarse channels exactly and outlives the programmer. The fine
        // channel is not part of the scale; a palette never wrote it.
        val templateRows = resolved.written.mapNotNull { w ->
            val value = aimed.paletteValues[w.target.key] ?: return@mapNotNull null
            TemplateRowDto(
                targetType = TargetRef.Fixture.TYPE,
                targetKey = w.target.key,
                propertyName = TemplateProperty.POSITION.propertyName,
                value = value,
            )
        }
        var savedTemplate: TemplateDto? = null
        if (saveAs != null && !dryRun && templateRows.isNotEmpty()) {
            when (val created = performTemplateCreate(state, project, TemplateInput(name = saveAs, rows = templateRows))) {
                is TemplateCreateResult.Invalid -> return errorResult(created.message)
                is TemplateCreateResult.Duplicate -> return errorResult(
                    "${created.message} — nothing was aimed. Pick another name, or combine several aims in one " +
                        "palette by running each with dryRun and passing their positions to create_palette.",
                )
                is TemplateCreateResult.Ok -> savedTemplate = created.template
            }
        }
        val response = if (dryRun) resolved else when (val outcome = aimIntoProgrammer(state, project, targets, x, y, z, fadeMs)) {
            is AimOutcome.Invalid -> return errorResult(outcome.message)
            is AimOutcome.Done -> outcome.response
        }
        // The same solve as the Stage view's Focus here, on the same point — for the heads aim
        // pointed there only: a head aim skipped is not pointing at the point, so focusing it there
        // would be a focus for nowhere. Aim has already refused anything focus would (a point off
        // the stage, a negative fade), and an empty list of aimed heads skips the call.
        val focused = if (!focus) null else {
            val notAimed = response.skipped.map { SpreadSkipDto(it.target, "not aimed, so not focused — ${it.reason}") }
            val aimed = response.written.map { it.target }
            if (aimed.isEmpty()) FocusResponse(skipped = notAimed) else when (
                val outcome = focusIntoProgrammer(state, project, aimed, FocusPointDto(x, y, z), fadeMs, write = !dryRun)
            ) {
                is FocusOutcome.Invalid -> return errorResult(outcome.message)
                is FocusOutcome.Done -> outcome.response.copy(skipped = outcome.response.skipped + notAimed)
            }
        }

        val verb = if (dryRun) "Would aim" else "Aimed"
        return ToolExecutionResult(
            success = true,
            description = "$verb ${response.written.size} fixture(s) at ($x, $y, $z)" +
                (if (response.skipped.isEmpty()) "" else ", skipped ${response.skipped.size}") +
                (focused?.let { ", ${if (dryRun) "would focus" else "focused"} ${it.written.size}" } ?: "") +
                (savedTemplate?.let { " and saved them as palette '${it.name}'" } ?: ""),
            result = buildJsonObject {
                put("dryRun", dryRun)
                focused?.let { f ->
                    put("focused", buildJsonArray {
                        f.written.forEach { w ->
                            addJsonObject {
                                put("fixture", w.target.key)
                                put("focus", w.value)
                                put("distanceM", w.distanceM)
                            }
                        }
                    })
                    put("focusSkipped", buildJsonArray {
                        f.skipped.forEach { s ->
                            addJsonObject {
                                put("target", s.target.key)
                                put("reason", s.reason)
                            }
                        }
                    })
                }
                savedTemplate?.let { t ->
                    putJsonObject("palette") {
                        put("paletteId", t.id)
                        put("uuid", t.uuid)
                        put("name", t.name)
                        put("rowCount", t.rows.size)
                    }
                }
                if (saveAs != null && savedTemplate == null) {
                    put(
                        "paletteNote",
                        if (dryRun) "dryRun: palette '$saveAs' was not created"
                        else "no head could be aimed, so palette '$saveAs' was not created",
                    )
                }
                put("aimed", buildJsonArray {
                    response.written.forEach { w ->
                        addJsonObject {
                            put("fixture", w.target.key)
                            put("panDeg", w.panDeg)
                            put("tiltDeg", w.tiltDeg)
                            put("position", w.value)
                        }
                    }
                })
                put("skipped", buildJsonArray {
                    response.skipped.forEach { s ->
                        addJsonObject {
                            put("target", s.target.key)
                            put("reason", s.reason)
                        }
                    }
                })
            }.toString(),
        )
    }

    private suspend fun executeParkChannel(input: JsonObject): ToolExecutionResult {
        val universe = (input["universe"] as? JsonPrimitive)?.intOrNull
            ?: return errorResult("universe must be an integer")
        val channel = (input["channel"] as? JsonPrimitive)?.intOrNull
            ?: return errorResult("channel must be an integer")
        val value = (input["value"] as? JsonPrimitive)?.intOrNull
            ?: return errorResult("value must be an integer")
        if (channel !in 1..512) return errorResult("channel must be between 1 and 512, got $channel")
        if (value !in 0..255) return errorResult("value must be between 0 and 255, got $value")
        val universes = state.show.fixtures.controllers.map { it.universe.universe }.distinct().sorted()
        if (universe !in universes) {
            return errorResult(
                "universe $universe is not output by this show" +
                    if (universes.isEmpty()) " (it has no universes)" else " (its universes: ${universes.joinToString()})",
            )
        }

        state.show.triggerOutput.parkRefusal(universe, channel, value.toUByte())?.let { return errorResult(it) }
        state.show.commandOutput.parkRefusal(universe, channel, value.toUByte())?.let { return errorResult(it) }
        val parkManager = state.show.parkManager
        val previous = parkManager.getParkedValue(universe, channel)?.toInt()
        val previousOwner = parkManager.ownerOf(universe, channel)
        parkManager.park(universe, channel, value.toUByte())
        state.show.fxEngine.provenance.emitUpdate()
        // On a show on a venue, the venue's park on the channel (inherited from its version, or one the
        // show had released) is what unpark_channel brings back.
        val venueValue = parkManager.venueParkOf(universe, channel)?.toInt()

        val mapping = channelMappingAt(state, universe, channel)
        return ToolExecutionResult(
            success = true,
            description = "Parked universe $universe channel $channel at $value" +
                (mapping?.let { " (${it.fixtureName} — ${it.description})" } ?: "") +
                (previous?.let { " (was parked at $it${if (previousOwner == RigOwner.VENUE) " by the venue" else ""})" } ?: "") +
                (venueValue?.let { " — this show's park, over the venue's at $it, which unpark_channel brings back" } ?: ""),
            result = buildJsonObject {
                putParkedChannel(universe, channel, value, mapping)
                put("owner", RigOwner.SHOW.name)
                venueValue?.let { put("venueValue", it) }
                previous?.let { put("previousValue", it) }
                previousOwner?.let { put("previousOwner", it.name) }
            }.toString(),
        )
    }

    /** The WebSocket's `unparkChannel`: [ParkManager.unpark] hands the parked value down first. */
    private suspend fun executeUnparkChannel(input: JsonObject): ToolExecutionResult {
        val universe = (input["universe"] as? JsonPrimitive)?.intOrNull
            ?: return errorResult("universe must be an integer")
        val channel = (input["channel"] as? JsonPrimitive)?.intOrNull
            ?: return errorResult("channel must be an integer")

        val parkManager = state.show.parkManager
        // Not an error: the outcome the caller wanted — the channel not parked — already holds.
        // Said plainly rather than as a silent success, so a model that got the address wrong
        // can tell it unparked nothing.
        val parkedValue = parkManager.getParkedValue(universe, channel)?.toInt()
            ?: return ToolExecutionResult(
                success = true,
                description = "Universe $universe channel $channel was not parked",
                result = buildJsonObject {
                    put("universe", universe)
                    put("channel", channel)
                    put("wasParked", false)
                }.toString(),
            )
        val owner = parkManager.ownerOf(universe, channel)
        val outcome = parkManager.unpark(universe, channel)
        state.show.fxEngine.provenance.emitUpdate()
        val venueValue = parkManager.getParkedValue(universe, channel)?.toInt()?.takeIf { outcome == UnparkOutcome.VENUE_RESTORED }

        val mapping = channelMappingAt(state, universe, channel)
        val what = when (outcome) {
            UnparkOutcome.VENUE_RESTORED -> "Removed this show's park on universe $universe channel $channel (was $parkedValue): the venue's park at $venueValue stands again"
            UnparkOutcome.RELEASED -> "Released the venue's park on universe $universe channel $channel (was $parkedValue) for this show; the venue keeps it"
            else -> "Unparked universe $universe channel $channel (was $parkedValue)"
        }
        return ToolExecutionResult(
            success = true,
            description = what + (mapping?.let { " (${it.fixtureName} — ${it.description})" } ?: ""),
            result = buildJsonObject {
                put("universe", universe)
                put("channel", channel)
                put("wasParked", true)
                put("parkedValue", parkedValue)
                owner?.let { put("owner", it.name) }
                put("outcome", outcome.name)
                venueValue?.let { put("venueValue", it) }
                mapping?.let { putChannelMapping(it) }
            }.toString(),
        )
    }

    private fun JsonObjectBuilder.putParkedChannel(universe: Int, channel: Int, value: Int, mapping: Fixtures.ChannelMapping?) {
        put("universe", universe)
        put("channel", channel)
        put("value", value)
        mapping?.let { putChannelMapping(it) }
    }

    private fun JsonObjectBuilder.putChannelMapping(mapping: Fixtures.ChannelMapping) {
        put("fixtureKey", mapping.fixtureKey)
        put("fixtureName", mapping.fixtureName)
        put("channelDescription", mapping.description)
    }

    private fun executeCreateCue(input: JsonObject): ToolExecutionResult {
        val name = input["name"]?.jsonPrimitive?.content ?: return errorResult("Missing 'name'")
        val adHocArray = input["adHocEffects"]?.jsonArray

        val project = state.projectManager.currentProject
        val layers = parseCueLayers(input["layers"]?.jsonArray)
        (layerSourceProblems(project, layers) + untargetedEffectLayerProblems(layers))
            .takeIf { it.isNotEmpty() }?.let { return errorResult(it.joinToString("; ")) }
        val adHocEffects = adHocArray?.mapIndexed { index, effect -> parseAdHocEffectFromJson(effect.jsonObject, "adHocEffects[$index]") }
            ?: emptyList()
        val values = input.present("values")?.let { raw ->
            val items = raw as? JsonArray ?: return errorResult("'values' must be an array")
            resolveLookValues(state.show.fixtures, items).also { resolved ->
                if (resolved.problems.isNotEmpty()) return errorResult(resolved.problems.joinToString("; "))
            }
        }
        val assignments = values?.asCueAssignments().orEmpty()

        val scenery = input["scenery"]?.jsonArray?.let { items ->
            val problems = mutableListOf<String>()
            val writes = transaction(state.database) {
                parseToolSceneryList(items, sceneryElementsOf(project), SceneryOwnerKind.CUE, "scenery", problems)
            }
            if (problems.isNotEmpty()) return errorResult(problems.joinToString("; "))
            writes
        }.orEmpty()
        val events = input["events"]?.jsonArray?.let { items ->
            val problems = mutableListOf<String>()
            val writes = transaction(state.database) {
                parseToolCueEventList(items, cueEventPatchesOf(project), "events", problems)
            }
            if (problems.isNotEmpty()) return errorResult(problems.joinToString("; "))
            writes
        }.orEmpty()
        val cue = transaction(state.database) {
            // A loose cue (cue model plan D1): add_cue_to_stack places it.
            val newCue = DaoCue.new {
                this.name = name
                this.project = project
            }
            writeCueChildren(newCue, adHocEffects, assignments, layers = layers, created = true)
            replaceCueScenery(newCue, scenery)
            replaceCueEvents(newCue, events)
            newCue
        }
        state.show.fixtures.cueListChanged()
        state.show.fixtures.cueStackListChanged()

        val cueId = cue.id.value
        return ToolExecutionResult(
            success = true,
            description = "Created cue '$name' (id=$cueId, ${layers.size} layers, ${assignments.size} values, " +
                "${adHocEffects.size} ad-hoc effects, ${scenery.size} scenery changes, ${events.size} events)",
            result = buildJsonObject {
                put("cueId", cueId)
                put("name", name)
                put("layerCount", layers.size)
                put("valueCount", assignments.size)
                put("adHocEffectCount", adHocEffects.size)
                values?.let { putValueSkips(it.skipped) }
            }.toString()
        )
    }

    /**
     * A tool call's cue `layers` array, in the [cueLayerSchema] shape. Shared by `create_cue` and
     * the MCP setup surface's `build_cue_stack` (`SetupTools`), so the two cannot disagree about
     * what a layer says. Throws on a malformed layer or an unknown speed master; the dispatchers
     * turn that into a failed tool result.
     */
    internal fun parseCueLayers(layersArray: JsonArray?): List<CueLayerDto> =
        layersArray?.mapIndexed { index, layer ->
            val obj = layer as? JsonObject ?: throw IllegalArgumentException("layers[$index] must be an object")
            val lookId = obj.present("lookId")?.let { it.jsonPrimitive.intOrNull ?: throw IllegalArgumentException("layers[$index]: lookId must be an integer") }
            val templateId = obj.present("paletteId")?.let { it.jsonPrimitive.intOrNull ?: throw IllegalArgumentException("layers[$index]: paletteId must be an integer") }
            if ((lookId == null) == (templateId == null)) {
                throw IllegalArgumentException("layers[$index]: give exactly one of lookId or paletteId")
            }
            CueLayerDto(
                lookId = lookId,
                templateId = templateId,
                targets = obj["targets"]?.jsonArray?.let(::parseTargets).orEmpty(),
                // Declaration order is the stack order unless the caller says otherwise, so a tool
                // call that lists layers bottom-to-top composes the way it reads.
                sortOrder = obj["sortOrder"]?.jsonPrimitive?.int ?: index,
                propertyMask = obj["propertyMask"]?.jsonPrimitive?.contentOrNull,
                // Unrecognised blends are rejected by `writeCueChildren` rather than here, so this
                // surface and the cue routes cannot disagree about what a valid blend is.
                blendMode = obj["blendMode"]?.jsonPrimitive?.contentOrNull ?: "OVERRIDE",
                amount = obj["amount"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
                speedMasterUuid = checkedSpeedMasterUuid(obj["speedMasterUuid"]),
                rateSpeedMasterUuid = checkedSpeedMasterUuid(obj["rateSpeedMasterUuid"]),
            )
        } ?: emptyList()

    /**
     * A layer naming no targets lands its Look's values, but a deferred effect has nothing to run
     * on — refused here, where apply_look refuses the same press, rather than left silent at GO. A
     * generic template (an effect, or values for whatever it is pointed at) is refused for the same
     * reason, as the busk pad refuses it with nothing selected.
     */
    internal fun untargetedEffectLayerProblems(layers: List<CueLayerDto>): List<String> {
        val untargeted = layers.filter { it.targets.isEmpty() }
        val lookIds = untargeted.mapNotNull { it.lookId }.toSet()
        val templateIds = untargeted.mapNotNull { it.templateId }.toSet()
        if (lookIds.isEmpty() && templateIds.isEmpty()) return emptyList()
        val (lookNeeding, templateNeeding) = transaction(state.database) {
            val looks = if (lookIds.isEmpty()) emptySet() else
                DaoLookEffect.find { (DaoLookEffects.look inList lookIds) and (DaoLookEffects.targetType eq DEFERRED_TARGET_TYPE) }
                    .mapTo(HashSet()) { it.look.id.value }
            val templates = if (templateIds.isEmpty()) emptySet() else
                DaoTemplate.forIds(templateIds.toList()).filter { it.isGenericTemplate() }.mapTo(HashSet()) { it.id.value }
            looks to templates
        }
        return layers.mapIndexedNotNull { index, layer ->
            when {
                layer.targets.isNotEmpty() -> null
                layer.lookId in lookNeeding ->
                    "layers[$index]: look ${layer.lookId} has effects that run on the layer's targets — give targets"
                layer.templateId in templateNeeding ->
                    "layers[$index]: palette ${layer.templateId} is generic — its effect or values land on the layer's targets, so give targets"
                else -> null
            }
        }
    }

    /**
     * A layer naming a Look or template [project] does not have. `writeCueChildren` drops such a
     * layer, by design for a record deleted under a stored cue; from a tool it would be a typo
     * answered with success, so it is refused before anything is written.
     */
    internal fun layerSourceProblems(project: DaoProject, layers: List<CueLayerDto>): List<String> {
        val lookIds = layers.mapNotNull { it.lookId }.toSet()
        val templateIds = layers.mapNotNull { it.templateId }.toSet()
        if (lookIds.isEmpty() && templateIds.isEmpty()) return emptyList()
        val (looks, templates) = transaction(state.database) {
            val looks = if (lookIds.isEmpty()) emptySet() else
                DaoLook.find { (DaoLooks.project eq project.id) and (DaoLooks.id inList lookIds) }.mapTo(HashSet()) { it.id.value }
            val templates = if (templateIds.isEmpty()) emptySet() else
                DaoTemplate.find { (DaoTemplates.project eq project.id) and (DaoTemplates.id inList templateIds) }
                    .mapTo(HashSet()) { it.id.value }
            looks to templates
        }
        return (lookIds - looks).sorted().map { "layers: no look $it in this project (see describe_rig)" } +
            (templateIds - templates).sorted().map { "layers: no palette $it in this project (see describe_rig)" }
    }

    private fun executeApplyCue(input: JsonObject): ToolExecutionResult {
        val cueId = input["cueId"]?.jsonPrimitive?.int ?: return errorResult("Missing 'cueId'")
        val replaceAll = input["replaceAll"]?.jsonPrimitive?.booleanOrNull ?: false

        val project = state.projectManager.currentProject
        val exists = transaction(state.database) { DaoCue.findById(cueId)?.project?.id == project.id }
        if (!exists) return errorResult("Cue not found: $cueId")

        // A loose hold (cue model plan D6, D10), the same one a pad or `/cues/{id}/apply` takes: built
        // as a GO builds it, so its events fire and its timed effects and triggers start.
        val holds = state.show.cueStackManager.holds
        if (replaceAll) holds.dropAllLoose(state, exceptCueId = cueId)
        val press = holds.takeLooseCue(state, cueId) ?: return errorResult("Cue not found: $cueId")

        return ToolExecutionResult(
            success = true,
            description = "Applied cue '${press.cueName}' (${press.acquired.effectCount} effects)" +
                (if (replaceAll) " [let go of every other applied cue]" else "") +
                (if (press.acquired.first) "" else " — it was already live, and now composes on top"),
            result = buildJsonObject {
                put("cueName", press.cueName)
                put("effectCount", press.acquired.effectCount)
                put("replaceAll", replaceAll)
            }.toString()
        )
    }

    /**
     * `set_scenery`: one owner's whole scenery list, checked against each element's kind before
     * anything is written — the REST `PUT …/scenery` routes' rule, from the tool's shape.
     */
    private fun executeSetScenery(input: JsonObject): ToolExecutionResult {
        val items = input["scenery"] as? JsonArray ?: return errorResult("Missing 'scenery' (send [] to clear)")
        val cueId = input["cueId"]?.jsonPrimitive?.intOrNull
        val stackId = input["stackId"]?.jsonPrimitive?.intOrNull
        val lookId = input["lookId"]?.jsonPrimitive?.intOrNull
        if (listOfNotNull(cueId, stackId, lookId).size != 1) return errorResult("Give exactly one of cueId, stackId, lookId")
        val project = try {
            state.toolProject(input).project
        } catch (e: ToolProjectException) {
            return errorResult(e.message ?: "No such project")
        }
        val problems = mutableListOf<String>()
        val written = transaction(state.database) {
            val elements = sceneryElementsOf(project)
            when {
                cueId != null -> {
                    val cue = DaoCue.findById(cueId)?.takeIf { it.project.id == project.id }
                        ?: run { problems += "no cue $cueId in project ${project.id.value}"; return@transaction null }
                    val writes = parseToolSceneryList(items, elements, SceneryOwnerKind.CUE, "scenery", problems)
                    if (problems.isNotEmpty()) null else { replaceCueScenery(cue, writes); "cue '${cue.name}'" to writes.size }
                }
                stackId != null -> {
                    val stack = DaoCueStack.findById(stackId)?.takeIf { it.project.id == project.id && it.type == CueStackType.STACK.name }
                        ?: run { problems += "no cue stack $stackId in project ${project.id.value}"; return@transaction null }
                    val writes = parseToolSceneryList(items, elements, SceneryOwnerKind.STACK, "scenery", problems)
                    if (problems.isNotEmpty()) null else { replaceStackScenery(stack, writes); "stack '${stack.name}'" to writes.size }
                }
                else -> {
                    val look = DaoLook.findById(lookId!!)?.takeIf { it.project.id == project.id }
                        ?: run { problems += "no Look $lookId in project ${project.id.value}"; return@transaction null }
                    val writes = parseToolSceneryList(items, elements, SceneryOwnerKind.LOOK, "scenery", problems)
                    if (problems.isNotEmpty()) null else { replaceLookScenery(look, writes); "Look '${look.name}'" to writes.size }
                }
            }
        }
        if (written == null) return errorResult(problems.joinToString("; "))
        when {
            cueId != null -> {
                state.show.fixtures.cueListChanged()
                // The stack list carries every cue's own changes (`CueStackCueEntry.scenery`).
                state.show.fixtures.cueStackListChanged()
            }
            stackId != null -> state.show.fixtures.cueStackListChanged()
            else -> state.show.fixtures.lookListChanged()
        }
        return ToolExecutionResult(
            success = true,
            description = "Set the scenery of ${written.first}: ${written.second} change(s)",
            result = buildJsonObject {
                cueId?.let { put("cueId", it) }
                stackId?.let { put("stackId", it) }
                lookId?.let { put("lookId", it) }
                put("changes", written.second)
            }.toString(),
        )
    }

    /** The programmer's scenery for `get_current_state`: `[{element, elementUuid, visible?, open?, trimM?}]`. */
    private fun programmerSceneryJson(): JsonArray {
        val snapshot = state.programmerScenery.flow.value
        if (snapshot.elements.isEmpty()) return JsonArray(emptyList())
        // Scoped to the overlay's project: element uuids are not unique across projects.
        val names = snapshot.projectId?.let { projectId ->
            transaction(state.database) {
                RigScope.of(projectId).stageElements(DaoStageElements.uuid inList snapshot.elements.keys)
                    .associate { it.uuid to it.name }
            }
        }.orEmpty()
        return buildJsonArray {
            for ((uuid, held) in snapshot.elements) {
                addJsonObject {
                    put("element", names[uuid] ?: uuid.toString())
                    put("elementUuid", uuid.toString())
                    for ((k, v) in sceneryStateObject(held.state)) put(k, v)
                }
            }
        }
    }

    /**
     * `move_scenery` (scenery-programmer plan D15): one element's states held in the programmer, or
     * let go — the same overlay the rail's Scenery band and the Stage view write, checked against the
     * element's kind. Current project only. Scenery is drawn and never output, so unlike arming,
     * firing and fixture commands this needs no remote-access gate.
     */
    private fun executeMoveScenery(input: JsonObject): ToolExecutionResult {
        val ref = (input["element"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        if (ref.isNullOrEmpty()) return errorResult("Missing 'element': an element's name (get_scene lists them) or uuid")
        val project = state.projectManager.currentProject
        if (state.programmerScenery.flow.value.projectId != project.id.value) return errorResult("The project is still loading; try again")
        val problems = mutableListOf<String>()
        // A misspelt key (`trim` for `trimM`) is refused rather than dropped, or the call would
        // answer success having held nothing — the scenery lists' rule (`TOOL_ITEM_KEYS`).
        for (key in input.keys) {
            if (key !in MOVE_SCENERY_KEYS) problems += "unknown field '$key' (known: ${MOVE_SCENERY_KEYS.sorted().joinToString()})"
        }
        val release = input["release"]?.takeIf { it !is JsonNull }?.let { r ->
            (r as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: run { problems += "release must be true or false"; false }
        } ?: false
        val fadeMs = input["fadeSeconds"]?.takeIf { it !is JsonNull }?.let { f ->
            val seconds = (f as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
            when {
                seconds == null -> { problems += "fadeSeconds must be a number"; null }
                seconds < 0 || seconds * 1000 > MAX_SCENERY_TRANSITION.toMillis() ->
                    { problems += "fadeSeconds must be between 0 and ${MAX_SCENERY_TRANSITION.seconds}"; null }
                else -> Math.round(seconds * 1000)
            }
        }
        val stateObj = JsonObject(input.filterKeys { it in MOVE_SCENERY_STATES && input[it] !is JsonNull })
        if (release && stateObj.isNotEmpty()) problems += "give states or release, not both"
        if (!release && stateObj.isEmpty()) problems += "give visible, open or trimM — or release: true to let the element go"
        val elements = transaction(state.database) { sceneryElementsOf(DaoProject[project.id]) }
        val element = runCatching { java.util.UUID.fromString(ref) }.getOrNull()?.let { elements[it] }
            ?: elements.values.firstOrNull { it.name.equals(ref, ignoreCase = true) }
        if (element == null) {
            problems += "no stage element named '$ref' (elements: ${elements.values.map { it.name }.sorted().joinToString().ifEmpty { "none" }})"
        }
        if (problems.isNotEmpty() || element == null) return errorResult(problems.joinToString("; "))

        val blind = state.show.programmerStore.blind
        if (release) {
            val released = state.programmerScenery.release(element.uuid, fadeMs)
            return ToolExecutionResult(
                success = true,
                description = if (released == 0) "The programmer was not holding '${element.name}'"
                    else "Released '${element.name}': it returns to what the show holds",
                result = buildJsonObject {
                    put("element", element.name)
                    put("released", released > 0)
                }.toString(),
            )
        }
        val refused = state.programmerScenery.set(element.uuid, stateObj, fadeMs)
        if (refused.isNotEmpty()) return errorResult(refused.joinToString("; "))
        val held = state.programmerScenery.flow.value.elements[element.uuid]?.state ?: ElementStates()
        return ToolExecutionResult(
            success = true,
            description = "Holding '${element.name}' in the programmer" +
                (if (blind) " — staged: the programmer is blind, so it moves on stage when Blind is left" else ""),
            result = buildJsonObject {
                put("element", element.name)
                put("held", sceneryStateObject(held))
                fadeMs?.let { put("fadeMs", it) }
                put("blind", blind)
            }.toString(),
        )
    }

    /**
     * `set_cue_events`: one cue's whole event list, every event checked before anything is written —
     * the REST `PUT cues/{id}/events` rule, from the tool's shape. Authoring only: arming and firing
     * are refused to MCP by default, and no tool offers them.
     */
    private fun executeSetCueEvents(input: JsonObject): ToolExecutionResult {
        val cueId = input["cueId"]?.jsonPrimitive?.intOrNull ?: return errorResult("Missing 'cueId'")
        val items = input["events"] as? JsonArray ?: return errorResult("Missing 'events' (send [] to clear)")
        val project = state.projectManager.currentProject
        val problems = mutableListOf<String>()
        val written = transaction(state.database) {
            val cue = DaoCue.findById(cueId)?.takeIf { it.project.id == project.id }
                ?: run { problems += "no cue $cueId in project ${project.id.value}"; return@transaction null }
            val writes = parseToolCueEventList(items, cueEventPatchesOf(project), "events", problems)
            if (problems.isNotEmpty()) null else { replaceCueEvents(cue, writes); cue.name to writes.size }
        } ?: return errorResult(problems.joinToString("; "))
        state.show.fixtures.cueListChanged()
        return ToolExecutionResult(
            success = true,
            description = "Set the events of cue '${written.first}': ${written.second} event(s)",
            result = buildJsonObject {
                put("cueId", cueId)
                put("events", written.second)
            }.toString(),
        )
    }

    private fun executeStopCue(input: JsonObject): ToolExecutionResult {
        val cueId = input["cueId"]?.jsonPrimitive?.int ?: return errorResult("Missing 'cueId'")

        // Its loose hold only (§3.4): a running stack that holds it keeps it on stage.
        val removedCount = state.show.cueStackManager.holds.dropLoose(state, cueId).effectsRemoved

        return ToolExecutionResult(
            success = true,
            description = "Stopped cue $cueId ($removedCount effects removed)",
            result = buildJsonObject {
                put("cueId", cueId)
                put("removedCount", removedCount)
            }.toString()
        )
    }

    // ─── Cue Stack Executors ────────────────────────────────────────────────

    private fun executeCreateCueStack(input: JsonObject): ToolExecutionResult {
        val name = input["name"]?.jsonPrimitive?.content ?: return errorResult("Missing 'name'")
        val loop = input["loop"]?.jsonPrimitive?.booleanOrNull ?: false

        val project = state.projectManager.currentProject
        val stack = transaction(state.database) {
            DaoCueStack.new {
                this.name = name
                this.project = project
                this.loop = loop
            }
        }
        state.show.fixtures.cueStackListChanged()

        val stackId = stack.id.value
        return ToolExecutionResult(
            success = true,
            description = "Created cue stack '$name' (id=$stackId, loop=$loop)",
            result = buildJsonObject {
                put("stackId", stackId)
                put("name", name)
                put("loop", loop)
            }.toString()
        )
    }

    private fun executeActivateCueStack(input: JsonObject): ToolExecutionResult {
        val stackId = input["stackId"]?.jsonPrimitive?.int ?: return errorResult("Missing 'stackId'")
        val cueId = input["cueId"]?.jsonPrimitive?.intOrNull

        val manager = state.show.cueStackManager
        // No cue named: `activateAtFirstCue`, not a hand-rolled "first row" query. It skips
        // marker cues and fires an armed standby when there is one, so arming cue 5 pre-show and
        // then activating starts at 5 — which the local query silently got wrong.
        val result = if (cueId != null) {
            // The tool still names a cue (cue model plan §3.10 moves it to an entry); the GO lands on
            // its first entry in the stack it names.
            val entryId = transaction(state.database) { entryInStack(stackId, cueId)?.id?.value }
                ?: return errorResult("Cue $cueId is not in stack $stackId")
            try {
                manager.activateEntry(state, stackId, entryId)
            } catch (e: IllegalArgumentException) {
                return errorResult(e.message ?: "Failed to activate stack $stackId")
            }
        } else {
            try {
                manager.activateAtFirstCue(state, stackId)
            } catch (e: IllegalArgumentException) {
                return errorResult(e.message ?: "Stack $stackId has no cues")
            }
        }

        return ToolExecutionResult(
            success = true,
            description = "Activated stack $stackId at cue '${result.cueName}' (${result.effectCount} effects)",
            result = buildJsonObject {
                put("stackId", result.stackId)
                put("cueId", result.cueId)
                put("cueName", result.cueName)
                put("effectCount", result.effectCount)
            }.toString()
        )
    }

    private fun executeDeactivateCueStack(input: JsonObject): ToolExecutionResult {
        val stackId = input["stackId"]?.jsonPrimitive?.int ?: return errorResult("Missing 'stackId'")

        val manager = state.show.cueStackManager
        val removedCount = manager.deactivateStack(stackId, state)

        return ToolExecutionResult(
            success = true,
            description = "Deactivated stack $stackId ($removedCount effects removed)",
            result = buildJsonObject {
                put("stackId", stackId)
                put("removedCount", removedCount)
            }.toString()
        )
    }

    private fun executeAdvanceCueStack(input: JsonObject): ToolExecutionResult {
        val stackId = input["stackId"]?.jsonPrimitive?.int ?: return errorResult("Missing 'stackId'")
        val directionStr = input["direction"]?.jsonPrimitive?.contentOrNull ?: "FORWARD"
        val direction = try {
            CueStackManager.AdvanceDirection.valueOf(directionStr)
        } catch (_: Exception) {
            return errorResult("Invalid direction: $directionStr (must be FORWARD or BACKWARD)")
        }

        val manager = state.show.cueStackManager
        val result = manager.advanceStack(state, stackId, direction)

        // Not "reached the end": `advanceStack` returns null only for a stack with no STANDARD
        // cues, and it deactivates nothing — at a boundary it stays on the live cue.
        if (result == null) return errorResult("Stack $stackId has no standard cues to fire")

        return ToolExecutionResult(
            success = true,
            description = "Advanced stack $stackId ${directionStr.lowercase()} to cue '${result.cueName}' (${result.effectCount} effects)",
            result = buildJsonObject {
                put("stackId", result.stackId)
                put("cueId", result.cueId)
                put("cueName", result.cueName)
                put("effectCount", result.effectCount)
            }.toString()
        )
    }

    /**
     * Arm (or disarm) the cue a stack's next GO fires.
     *
     * The arming is deliberately separate from firing it: "stand by cue 5" is a rehearsal
     * gesture that must not move a light, and the model has to be able to make it before the
     * operator — or [executeGoCueStack] — presses GO.
     */
    private fun executeSetStandby(input: JsonObject): ToolExecutionResult {
        val stackId = input["stackId"]?.jsonPrimitive?.int ?: return errorResult("Missing 'stackId'")
        val cueId = input["cueId"]?.jsonPrimitive?.intOrNull

        val manager = state.show.cueStackManager
        val runState = manager.runState
        try {
            if (cueId != null) {
                // The tool names a cue (§3.10 moves it to an entry): its first entry in the stack.
                val entryId = transaction(state.database) { entryInStack(stackId, cueId)?.id?.value }
                    ?: return errorResult("Cue $cueId is not in stack $stackId")
                runState.setStandby(state, stackId, entryId)
            } else {
                runState.clearStandby(state, stackId)
            }
        } catch (e: IllegalArgumentException) {
            return errorResult(e.message ?: "Failed to change the standby on stack $stackId")
        }

        // The effective next, not the arming, is what the model needs back: disarming leaves the
        // positional successor on deck, and that is the cue the next GO actually fires.
        val next = runState.effectiveNext(state, stackId)
        val nextCueId = next?.cueId
        val nextCueName = nextCueId?.let { id -> transaction(state.database) { DaoCue.findById(id)?.name } }

        return ToolExecutionResult(
            success = true,
            description = if (cueId != null) {
                // Arming the cue that is already live is not an error, but it does not do what
                // "stand by cue 5" sounds like: `nextFrom` ignores a standby equal to the
                // active entry, so the next GO advances past it. Say so rather than promising a
                // fire the run state then contradicts.
                if (cueId == manager.getActiveCueId(stackId)) {
                    "Cue $cueId is already live on stack $stackId — the next GO advances to " +
                        (nextCueName?.let { "'$it' (id=$nextCueId)" } ?: "nothing")
                } else {
                    "Armed cue $cueId on stack $stackId — the next GO fires it"
                }
            } else {
                "Disarmed stack $stackId — the next GO fires " +
                    (nextCueName?.let { "'$it' (id=$nextCueId)" } ?: "nothing")
            },
            result = buildJsonObject {
                put("stackId", stackId)
                manager.getActiveCueId(stackId)?.let { put("activeCueId", it) }
                cueId?.let { put("standbyCueId", it) }
                nextCueId?.let { put("nextCueId", it) }
                nextCueName?.let { put("nextCueName", it) }
            }.toString()
        )
    }

    /**
     * Press GO: fire whatever is on deck, starting the stack if it is stopped.
     *
     * The branch itself lives in [CueStackManager.go], which `SurfaceActions.cueStackGo` also
     * calls — the model, the tablet and the physical GO button press one button, not three
     * implementations of it.
     */
    private fun executeGoCueStack(input: JsonObject): ToolExecutionResult {
        val stackId = input["stackId"]?.jsonPrimitive?.int ?: return errorResult("Missing 'stackId'")

        val manager = state.show.cueStackManager
        val wasActive = manager.isStackActive(stackId)
        val result = try {
            manager.go(state, stackId)
        } catch (e: IllegalArgumentException) {
            return errorResult(e.message ?: "GO failed on stack $stackId")
        }

        // Null means the stack holds no STANDARD cues — see [CueStackManager.go]. It is not
        // "reached the end", and nothing has been deactivated.
        if (result == null) return errorResult("Stack $stackId has no standard cues to fire")

        state.show.fixtures.cueStackListChanged()

        return ToolExecutionResult(
            success = true,
            description = "GO on stack $stackId — " +
                (if (wasActive) "fired" else "started at") +
                " cue '${result.cueName}' (${result.effectCount} effects)",
            result = buildJsonObject {
                put("stackId", result.stackId)
                put("cueId", result.cueId)
                put("cueName", result.cueName)
                put("effectCount", result.effectCount)
                put("started", !wasActive)
            }.toString()
        )
    }

    private fun executeAddCueToStack(input: JsonObject): ToolExecutionResult {
        val stackId = input["stackId"]?.jsonPrimitive?.int ?: return errorResult("Missing 'stackId'")
        val cueId = input["cueId"]?.jsonPrimitive?.int ?: return errorResult("Missing 'cueId'")
        val sortOrder = input["sortOrder"]?.jsonPrimitive?.intOrNull

        var priorities = emptyMap<Int, Int>()
        val result = transaction(state.database) {
            val stack = DaoCueStack.findById(stackId)
                ?: return@transaction null to "Cue stack not found: $stackId"
            val cue = DaoCue.findById(cueId)
                ?: return@transaction null to "Cue not found: $cueId"

            if (stack.project.id != state.projectManager.currentProject.id || cue.project.id != stack.project.id) {
                return@transaction null to "Cue $cueId and stack $stackId must both be in the current project"
            }
            if (stack.type == CueStackType.SEPARATOR.name) return@transaction null to "Stack $stackId is a separator"
            // A new entry: a cue in another stack is shared, never moved (cue model plan D1).
            placeCueInStack(stack, cue, position = sortOrder)
            // The positions after it moved, and a live cue's priority is its position.
            priorities = stackEntryPriorities(stack)

            cue.name to null
        }

        val (cueName, error) = result
        if (error != null) return errorResult(error)
        state.show.cueStackManager.holds.restampEntries(priorities)

        state.show.fixtures.cueStackListChanged()
        state.show.fixtures.cueListChanged()

        return ToolExecutionResult(
            success = true,
            description = "Added cue '$cueName' (id=$cueId) to stack $stackId",
            result = buildJsonObject {
                put("stackId", stackId)
                put("cueId", cueId)
                put("cueName", cueName)
            }.toString()
        )
    }

    // ─── Programmer: Record / Include / Update ─────────────────────────────
    //
    // All four go through the same `perform*` cores the REST routes call — see
    // `routes/programmerSurface.kt`. These executors only translate JSON in and JSON out; the
    // decisions about which rows get overwritten are made once, in one place.

    private fun executeRecordCue(input: JsonObject): ToolExecutionResult {
        val mode = parseEnumOrNull<RecordMode>(input["mode"]?.jsonPrimitive?.content ?: "CREATE")
            ?: return errorResult("Unknown record mode '${input["mode"]?.jsonPrimitive?.content}'")
        val source = parseEnumOrNull<RecordSource>(input["source"]?.jsonPrimitive?.content ?: "TOUCHED")
            ?: return errorResult("Unknown record source '${input["source"]?.jsonPrimitive?.content}'")
        val cueType = parseEnumOrNull<CueType>(input["cueType"]?.jsonPrimitive?.content ?: "STANDARD")
            ?: return errorResult("Unknown cue type '${input["cueType"]?.jsonPrimitive?.content}'")
        val mask = input.maskGroups().getOrElse { return errorResult(it.message ?: "Bad mask") }

        val result = performProgrammerRecord(
            state,
            state.projectManager.currentProject,
            mode = mode,
            source = source,
            cueType = cueType,
            cueStackId = input["cueStackId"]?.jsonPrimitive?.intOrNull,
            cueId = input["cueId"]?.jsonPrimitive?.intOrNull,
            mask = mask,
            includeFx = input["includeFx"]?.jsonPrimitive?.booleanOrNull ?: true,
            name = input["name"]?.jsonPrimitive?.contentOrNull,
            cueNumber = input["cueNumber"]?.jsonPrimitive?.contentOrNull,
            sortOrder = input["sortOrder"]?.jsonPrimitive?.intOrNull,
            targets = input.targetList("targets").getOrElse { return errorResult(it.message ?: "Bad targets") },
            scenery = input["scenery"]?.jsonPrimitive?.booleanOrNull ?: true,
        )

        return when (result) {
            is RecordCoreResult.Failure -> errorResult(result.message)
            is RecordCoreResult.Ok -> ToolExecutionResult(
                success = true,
                description = (if (result.outcome.created) "Recorded new cue" else "Recorded into cue") +
                    " '${result.details.name}' (id=${result.outcome.cueId}) — " +
                    "${result.outcome.assignmentsWritten} value(s), ${result.outcome.fxWritten} effect(s)" +
                    (if (result.outcome.scenery.written > 0) ", ${result.outcome.scenery.written} scenery change(s)" else ""),
                result = buildJsonObject {
                    put("cueId", result.outcome.cueId)
                    put("cueName", result.details.name)
                    put("created", result.outcome.created)
                    result.stackId?.let { put("cueStackId", it) }
                    put("assignmentsWritten", result.outcome.assignmentsWritten)
                    put("assignmentsRemoved", result.outcome.assignmentsRemoved)
                    put("fxWritten", result.outcome.fxWritten)
                    put("sceneryWritten", result.outcome.scenery.written)
                    put("sceneryRemoved", result.outcome.scenery.removed)
                    put("sceneryAlreadyTracked", result.outcome.scenery.alreadyTracked)
                    put("republishedLive", result.republishedLive)
                    putSkips(result.skipped)
                    putStrings("warnings", result.outcome.warnings)
                }.toString()
            )
        }
    }

    private fun executeInclude(input: JsonObject): ToolExecutionResult {
        val mask = input.maskGroups().getOrElse { return errorResult(it.message ?: "Bad mask") }

        val result = performProgrammerInclude(
            state,
            state.projectManager.currentProject,
            cueId = input["cueId"]?.jsonPrimitive?.intOrNull,
            lookId = input["lookId"]?.jsonPrimitive?.intOrNull,
            mask = mask,
            fadeMs = input["fadeMs"]?.jsonPrimitive?.longOrNull ?: 0L,
            includeFx = input["includeFx"]?.jsonPrimitive?.booleanOrNull ?: true,
            targets = input["targets"]?.jsonArray?.let(::parseTargets),
        )

        return when (result) {
            is IncludeCoreResult.Failure -> errorResult(result.message)
            is IncludeCoreResult.Cue -> ToolExecutionResult(
                success = true,
                description = "Included cue '${result.cueData.cueName}' — ${result.outcome.entriesWritten} " +
                    "value(s), ${result.outcome.fxSpawned} effect(s) and ${result.sceneryIncluded} scenery change(s) staged in the programmer",
                result = buildJsonObject {
                    put("kind", "CUE")
                    put("cueId", result.cueData.cueId)
                    put("name", result.cueData.cueName)
                    put("entriesWritten", result.outcome.entriesWritten)
                    put("fxSpawned", result.outcome.fxSpawned)
                    put("sceneryIncluded", result.sceneryIncluded)
                    putStrings("fixtureKeys", result.outcome.fixtureKeys)
                    putSkips(result.outcome.skipped)
                    putStrings("warnings", result.outcome.warnings)
                }.toString()
            )
            is IncludeCoreResult.Look -> ToolExecutionResult(
                success = true,
                description = "Included look '${result.lookName}' — ${result.outcome.entriesWritten} " +
                    "value(s), ${result.outcome.fxSpawned} effect(s) and ${result.sceneryIncluded} scenery change(s) staged in the programmer",
                result = buildJsonObject {
                    put("kind", "LOOK")
                    put("lookId", result.lookId)
                    put("name", result.lookName)
                    put("entriesWritten", result.outcome.entriesWritten)
                    put("fxSpawned", result.outcome.fxSpawned)
                    put("sceneryIncluded", result.sceneryIncluded)
                    putStrings("fixtureKeys", result.outcome.fixtureKeys)
                    putSkips(result.outcome.skipped)
                    putStrings("warnings", lookIncludeWarnings(result.lookName, result.outcome, result.sceneryIncluded))
                }.toString()
            )
        }
    }

    private fun executeUpdate(input: JsonObject): ToolExecutionResult {
        val mask = input.maskGroups().getOrElse { return errorResult(it.message ?: "Bad mask") }
        val targets = input["targets"]?.jsonArray?.map { it.jsonPrimitive.int }

        val result = performProgrammerUpdate(
            state,
            state.projectManager.currentProject,
            targets = targets,
            mask = mask,
            preview = input["preview"]?.jsonPrimitive?.booleanOrNull ?: false,
            includeFx = input["includeFx"]?.jsonPrimitive?.booleanOrNull ?: true,
        )

        return when (result) {
            is UpdateCoreResult.Failure -> errorResult(result.message)
            // Not an error the model can retry its way out of, but not a write either: say what
            // happened and let it re-include rather than reporting a successful update of nothing.
            is UpdateCoreResult.IncludeTargetGone -> errorResult(result.message)
            is UpdateCoreResult.Checklist -> ToolExecutionResult(
                success = true,
                description = "Nothing written — the programmer is sitting on " +
                    "${result.checklist.totalKeys} key(s) across ${result.checklist.stacks.size} stack(s)",
                result = buildJsonObject {
                    put("mode", "CHECKLIST")
                    put("applied", false)
                    put("totalKeys", result.checklist.totalKeys)
                    put("cues", buildJsonArray {
                        for (stack in result.checklist.stacks) {
                            for (cue in stack.cues) {
                                addJsonObject {
                                    put("cueId", cue.cueId)
                                    put("cueName", cue.cueName)
                                    stack.cueStackName?.let { put("stackName", it) }
                                    put("isActive", cue.isActive)
                                    put("keyCount", cue.keyCount)
                                }
                            }
                        }
                    })
                    put("unattributedKeys", result.checklist.unattributed.size)
                }.toString()
            )
            is UpdateCoreResult.LookUpdated -> ToolExecutionResult(
                success = true,
                description = "Updated look '${result.result.lookName}' — ${result.result.rowsWritten} row(s), " +
                    "${result.result.effectsWritten} effect(s) and ${result.result.sceneryWritten} scenery change(s) written",
                result = buildJsonObject {
                    put("mode", "A")
                    put("applied", result.result.rowsWritten + result.result.effectsWritten + result.result.sceneryWritten > 0)
                    put("lookId", result.result.lookId)
                    put("lookName", result.result.lookName)
                    put("rowsWritten", result.result.rowsWritten)
                    put("effectsWritten", result.result.effectsWritten)
                    put("sceneryWritten", result.result.sceneryWritten)
                    putSkips(result.skipped)
                }.toString()
            )
            is UpdateCoreResult.CuesUpdated -> ToolExecutionResult(
                success = true,
                description = if (result.results.isEmpty()) {
                    "Nothing written"
                } else {
                    "Updated " + result.results.joinToString {
                        "'${it.cueName}' (${it.assignmentsWritten} value(s)" +
                            (if (it.sceneryWritten > 0) ", ${it.sceneryWritten} scenery change(s)" else "") + ")"
                    }
                },
                result = buildJsonObject {
                    put("mode", result.mode)
                    put("applied", result.results.isNotEmpty())
                    put("results", buildJsonArray {
                        for (r in result.results) {
                            addJsonObject {
                                put("cueId", r.cueId)
                                put("cueName", r.cueName)
                                put("assignmentsWritten", r.assignmentsWritten)
                                put("fxWritten", r.fxWritten)
                                put("sceneryWritten", r.sceneryWritten)
                                put("republishedLive", r.republishedLive)
                            }
                        }
                    })
                    putSkips(result.skipped)
                    putStrings("warnings", result.warnings)
                }.toString()
            )
        }
    }

    private fun executeCreateTemplate(input: JsonObject): ToolExecutionResult {
        val rows = input["rows"]?.jsonArray?.map { row ->
            val obj = row.jsonObject
            val targetKey = obj["targetKey"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            TemplateRowDto(
                // A row with no fixture key is *deferred*, which is the generic template an
                // effect can reference by uuid — so the absent key is the meaningful default,
                // not a missing field.
                targetType = if (targetKey == null) DEFERRED_TARGET_TYPE else TargetRef.Fixture.TYPE,
                targetKey = targetKey.orEmpty(),
                propertyName = obj["propertyName"]?.jsonPrimitive?.contentOrNull
                    ?: return errorResult("A palette row is missing 'propertyName'"),
                value = obj["value"]?.jsonPrimitive?.contentOrNull
                    ?: return errorResult("A palette row is missing 'value'"),
            )
        } ?: emptyList()

        // An effect template instead of rows (D1). Reuses `parseLookEffect`, which is where the
        // enum checks live, then drops the fields a template effect has no place for — the target
        // (D3) and the sort order (D2).
        val effect = input["effect"]?.jsonObject?.let { obj ->
            val spec = parseLookEffect(obj, "effect")
            TemplateEffectDto(
                effectType = spec.effectType,
                category = spec.category,
                propertyName = spec.propertyName,
                beatDivision = spec.beatDivision,
                blendMode = spec.blendMode,
                distribution = spec.distribution,
                phaseOffset = spec.phaseOffset,
                elementMode = spec.elementMode,
                elementFilter = spec.elementFilter,
                stepTiming = spec.stepTiming,
                parameters = spec.parameters,
                speedMasterUuid = spec.speedMasterUuid,
                rateSpeedMasterUuid = spec.rateSpeedMasterUuid,
                seconds = spec.seconds,
                size = spec.size,
                direction = spec.direction,
                nShot = spec.nShot,
                spread = spec.spread,
                wings = spec.wings,
                blocks = spec.blocks,
                groups = spec.groups,
            )
        }
        // Said here rather than left to the write boundary only because the model reads this
        // message and retries: "must hold at least one value" would not tell it which field to add.
        if (rows.isEmpty() && effect == null) return errorResult("Missing 'rows' or 'effect'")

        val result = performTemplateCreate(
            state,
            state.projectManager.currentProject,
            TemplateInput(
                name = input["name"]?.jsonPrimitive?.contentOrNull,
                notes = input["notes"]?.jsonPrimitive?.contentOrNull,
                fadeDurationMs = input["fadeDurationMs"]?.jsonPrimitive?.longOrNull,
                rows = rows,
                effect = effect,
            ),
        )

        return when (result) {
            is TemplateCreateResult.Invalid -> errorResult(result.message)
            is TemplateCreateResult.Duplicate -> errorResult(result.message)
            is TemplateCreateResult.Ok -> ToolExecutionResult(
                success = true,
                description = "Created palette '${result.template.name}' " +
                    (result.template.effect
                        ?.let { "(effect: ${it.effectType})" }
                        ?: "(${result.template.rows.size} row(s))") +
                    " — reference it as tmpl:${result.template.uuid}",
                result = buildJsonObject {
                    put("paletteId", result.template.id)
                    put("uuid", result.template.uuid)
                    put("name", result.template.name)
                    put("family", result.template.family)
                    put("kind", result.template.kind)
                    put("isGeneric", result.template.isGeneric)
                    put("rowCount", result.template.rows.size)
                }.toString()
            )
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    /**
     * One Look effect out of a tool call.
     *
     * The enum-valued fields are checked before the spec is returned: this path writes
     * `look_effects` rows directly, and `executeTool`'s catch is the AI surface's 400 — the model
     * is told the valid set and can retry, rather than the desk storing a blend it will not play.
     */
    private fun parseLookEffect(obj: JsonObject, at: String): LookEffectSpec {
        val timing = parseEffectTiming(obj, at)
        val dynamics = parseEffectDynamics(obj, at)
        val spec = LookEffectSpec(
            effectType = obj.requiredString("effectType", at),
            category = obj.requiredString("category", at),
            propertyName = obj["propertyName"]?.jsonPrimitive?.contentOrNull,
            beatDivision = timing.beatDivision,
            blendMode = obj.requiredString("blendMode", at),
            distribution = obj["distribution"]?.jsonPrimitive?.content ?: "UNIFIED",
            phaseOffset = obj["phaseOffset"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            stepTiming = obj["stepTiming"]?.jsonPrimitive?.booleanOrNull,
            elementMode = obj["elementMode"]?.jsonPrimitive?.contentOrNull,
            elementFilter = obj["elementFilter"]?.jsonPrimitive?.contentOrNull,
            parameters = obj["parameters"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
            speedMasterUuid = timing.speedMasterUuid,
            rateSpeedMasterUuid = timing.rateSpeedMasterUuid,
            seconds = timing.seconds,
            size = dynamics.size,
            direction = dynamics.direction,
            nShot = dynamics.nShot,
            spread = dynamics.spread,
            wings = dynamics.wings,
            blocks = dynamics.blocks,
            groups = dynamics.groups,
        )
        EffectSpecCoercion.Strict.problem(
            blendMode = spec.blendMode,
            distribution = spec.distribution,
            elementMode = spec.elementMode,
            elementFilter = spec.elementFilter,
            direction = spec.direction,
            size = spec.size,
            nShot = spec.nShot,
            spread = spec.spread,
            wings = spec.wings,
            blocks = spec.blocks,
            groups = spec.groups,
        )?.let { throw IllegalArgumentException(it) }
        return spec
    }

    /**
     * One ad-hoc cue effect out of a tool call.
     *
     * No enum check here, unlike [parseLookEffect]: everything this returns goes through
     * `writeCueChildren`, which rejects an unrecognised value itself, and `executeTool`'s catch
     * turns that message into a failed result the same way.
     */
    private fun parseAdHocEffectFromJson(obj: JsonObject, at: String): CueAdHocEffectDto {
        val timing = parseEffectTiming(obj, at)
        val dynamics = parseEffectDynamics(obj, at)
        val target = parseAdHocEffectTarget(obj, at)
        return CueAdHocEffectDto(
            targetType = target.discriminator,
            targetKey = target.key,
            effectType = obj.requiredString("effectType", at),
            category = obj.requiredString("category", at),
            propertyName = obj["propertyName"]?.jsonPrimitive?.contentOrNull,
            beatDivision = timing.beatDivision,
            blendMode = obj.requiredString("blendMode", at),
            distribution = obj["distribution"]?.jsonPrimitive?.content ?: "UNIFIED",
            phaseOffset = obj["phaseOffset"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            elementMode = obj["elementMode"]?.jsonPrimitive?.contentOrNull,
            elementFilter = obj["elementFilter"]?.jsonPrimitive?.contentOrNull,
            stepTiming = obj["stepTiming"]?.jsonPrimitive?.booleanOrNull,
            parameters = obj["parameters"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
            speedMasterUuid = timing.speedMasterUuid,
            rateSpeedMasterUuid = timing.rateSpeedMasterUuid,
            seconds = timing.seconds,
            size = dynamics.size,
            direction = dynamics.direction,
            nShot = dynamics.nShot,
            spread = dynamics.spread,
            wings = dynamics.wings,
            blocks = dynamics.blocks,
            groups = dynamics.groups,
        )
    }

    /**
     * Size, Direction, Runs and the spread's shape as a tool call gives them (effects plan D2, D5, D9,
     * D4), defaults filled in.
     */
    private data class ParsedEffectDynamics(
        val size: Double,
        val direction: String,
        val nShot: Int?,
        val spread: Double = 1.0,
        val wings: Int = 1,
        val blocks: Int = 1,
        val groups: Int = 0,
    )

    /**
     * Those out of one effect in a tool call, each refused by name when malformed — a size outside
     * 0–1, an unknown direction, a count below one, a spread outside 0–2 or a count of wings, blocks
     * or groups out of range — so the model is told which and can retry. Wings under a mirrored order
     * are refused by the spec's own check ([EffectSpecCoercion.Strict.problem]), which has the order.
     */
    private fun parseEffectDynamics(obj: JsonObject, at: String): ParsedEffectDynamics {
        val size = obj.present("size")?.let {
            (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull
                ?: throw IllegalArgumentException("$at: 'size' must be a number from 0 to 1")
        } ?: 1.0
        EffectSpecCoercion.Strict.sizeProblem(size)?.let { throw IllegalArgumentException("$at: $it") }
        val direction = obj.present("direction")?.let {
            val raw = (it as? JsonPrimitive)?.contentOrNull
                ?: throw IllegalArgumentException("$at: 'direction' must be FORWARD, BACKWARD or BOUNCE")
            EffectSpecCoercion.Names.direction(raw)?.name
                ?: throw IllegalArgumentException("$at: 'direction' must be FORWARD, BACKWARD or BOUNCE, got '$raw'")
        } ?: EffectDirection.FORWARD.name
        val nShot = obj.present("nShot")?.let {
            (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull
                ?: throw IllegalArgumentException("$at: 'nShot' must be a whole number of cycles")
        }
        nShot?.let { n -> EffectSpecCoercion.Strict.nShotProblem(n)?.let { throw IllegalArgumentException("$at: $it") } }
        val spread = obj.present("spread")?.let {
            (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull
                ?: throw IllegalArgumentException("$at: 'spread' must be a number from 0 to 2")
        } ?: 1.0
        fun count(field: String, default: Int): Int = obj.present(field)?.let {
            (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull
                ?: throw IllegalArgumentException("$at: '$field' must be a whole number")
        } ?: default
        val wings = count("wings", 1)
        val blocks = count("blocks", 1)
        val groups = count("groups", 0)
        EffectSpecCoercion.Strict.spreadProblem(spread, wings, blocks, groups, DistributionStrategy.LINEAR)
            ?.let { throw IllegalArgumentException("$at: $it") }
        return ParsedEffectDynamics(size, direction, nShot, spread, wings, blocks, groups)
    }

    /** An effect's timing as a tool call gives it, with venue-visit plan D16's defaults filled in. */
    private data class ParsedEffectTiming(
        val beatDivision: Double,
        val speedMasterUuid: String?,
        val rateSpeedMasterUuid: String?,
        val seconds: Boolean,
    )

    /**
     * The timing fields of one effect out of a tool call (venue-visit plan D7, D16).
     *
     * *Seconds* beside a master is refused — two answers to one question. An omitted `beatDivision`
     * is one bar, and a **beat** effect that names no master and is not on Seconds is stamped with
     * the master whose `usage` matches its family — the library's category for its type, else the
     * call's — as the effect editor stamps a new effect. Null keeps meaning master 1 everywhere: the
     * stamp is a uuid written at creation, never a rule resolved later. A wall-clock effect is left
     * unscaled, its own Seconds, since "one bar" means nothing to an effect timed in seconds.
     */
    private fun parseEffectTiming(obj: JsonObject, at: String): ParsedEffectTiming {
        val seconds = obj.present("seconds")?.let {
            it.jsonPrimitive.booleanOrNull ?: throw IllegalArgumentException("$at: 'seconds' must be a boolean")
        } ?: false
        val speed = checkedSpeedMasterUuid(obj["speedMasterUuid"])
        val rate = checkedSpeedMasterUuid(obj["rateSpeedMasterUuid"])
        secondsTimingConflict(seconds, speed, rate)?.let { throw IllegalArgumentException("$at: $it") }
        val beatDivision = if (obj.present("beatDivision") == null) BeatDivision.ONE_BAR else obj.requiredDouble("beatDivision", at)
        if (beatDivision <= 0.0) throw IllegalArgumentException("$at: 'beatDivision' must be greater than 0")
        val stamped = speed ?: if (seconds) null else usageMasterFor(obj)
        return ParsedEffectTiming(beatDivision, stamped, rate, seconds)
    }

    /** D16's default master for a beat effect named in [obj]: the one tagged for its family, or null. */
    private fun usageMasterFor(obj: JsonObject): String? {
        val effectType = obj["effectType"]?.jsonPrimitive?.contentOrNull ?: return null
        return usageMasterUuidFor(
            state.show.fxRegistry, state.show.fxEngine.speedMasters, effectType,
            obj["category"]?.jsonPrimitive?.contentOrNull,
        )?.toString()
    }

    /**
     * A Look's `effects` out of a tool call, every one deferred: the tool describes the effects, and
     * the targets come from whatever applies the Look. A deferred effect lands on whatever applies
     * it, so it may name no one-shot trigger and no fixture command at all — the REST write
     * boundary's rule, every problem at once.
     */
    private fun parseLookEffects(items: JsonArray): List<LookEffectDto> {
        val effects = items.mapIndexed { index, item ->
            val at = "effects[$index]"
            val spec = parseLookEffect(item as? JsonObject ?: throw IllegalArgumentException("$at must be an object"), at)
            LookEffectDto(
                targetType = DEFERRED_TARGET_TYPE,
                targetKey = "",
                effectType = spec.effectType,
                category = spec.category,
                propertyName = spec.propertyName,
                beatDivision = spec.beatDivision,
                blendMode = spec.blendMode,
                distribution = spec.distribution,
                phaseOffset = spec.phaseOffset,
                elementMode = spec.elementMode,
                elementFilter = spec.elementFilter,
                stepTiming = spec.stepTiming,
                parameters = spec.parameters,
                speedMasterUuid = spec.speedMasterUuid,
                rateSpeedMasterUuid = spec.rateSpeedMasterUuid,
                seconds = spec.seconds,
                size = spec.size,
                direction = spec.direction,
                nShot = spec.nShot,
                spread = spec.spread,
                wings = spec.wings,
                blocks = spec.blocks,
                groups = spec.groups,
                sortOrder = index,
            )
        }
        val refs = effects.mapIndexed { i, e -> TriggerIndex.RowRef(DEFERRED_TARGET_TYPE, "", e.propertyName, "effects[$i]") }
        TriggerIndex.EMPTY.check(refs)
        CommandIndex.EMPTY.check(refs)
        return effects
    }

    /** An ad-hoc effect's `targets` list (effects plan D3), else its `targetType` and `targetKey`. */
    private fun parseAdHocEffectTarget(obj: JsonObject, at: String): EffectTargetRef {
        val list = obj["targets"] as? JsonArray
            ?: return EffectTargetRef.ofOrNull(obj.requiredString("targetType", at), obj.requiredString("targetKey", at))
                ?: throw IllegalArgumentException("$at: targetType must be 'fixture' or 'group' (or send targets)")
        if (obj["targetType"] != null || obj["targetKey"] != null) {
            throw IllegalArgumentException("$at: send targets, or targetType and targetKey — not both")
        }
        if (list.isEmpty()) throw IllegalArgumentException("$at: targets names no fixture or group")
        val refs = list.mapIndexed { i, element ->
            val item = element as? JsonObject ?: throw IllegalArgumentException("$at.targets[$i] must be an object")
            TargetRef.ofOrNull(item.requiredString("type", "$at.targets[$i]"), item.requiredString("key", "$at.targets[$i]"))
                ?: throw IllegalArgumentException("$at.targets[$i]: type must be 'fixture' or 'group'")
        }
        return EffectTargetRef.of(refs)
    }

    /** [field] of an effect at [at], refused by name when it is absent, null or blank. */
    private fun JsonObject.requiredString(field: String, at: String): String =
        (this[field] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$at is missing '$field'")

    private fun JsonObject.requiredDouble(field: String, at: String): Double {
        val raw = this[field] as? JsonPrimitive
        if (raw == null || raw is JsonNull) throw IllegalArgumentException("$at is missing '$field'")
        return raw.doubleOrNull ?: throw IllegalArgumentException("$at: '$field' must be a number")
    }

    /** [field], or null when it is absent or JSON null. */
    private fun JsonObject.present(field: String): JsonElement? = this[field]?.takeIf { it !is JsonNull }

    /**
     * Resolve a tool-supplied speed-master reference against the live bank, or null when the
     * uuid names no master. A null [raw] resolves to master 1, the global tempo — that is what
     * every "no master given" surface means.
     */
    private fun resolveSpeedMaster(raw: String?): SpeedMasterBank.MasterState? {
        val masters = state.show.fxEngine.speedMasters.masterStates()
        val wanted = speedMasterUuidOrNull(raw)
        if (raw != null && wanted == null) return null
        return if (wanted == null) masters.first() else masters.firstOrNull { it.uuid == wanted }
    }

    /**
     * A speed-master reference on its way into a stored row, checked against the live bank.
     *
     * Unknown uuids are rejected rather than written: `SpeedMasterBank.slotFor` resolves a
     * dangling reference to master 1, so a mistyped uuid would run at the global tempo forever
     * while looking like it had been accepted. Throwing here reaches the model as a failed tool
     * result it can retry — the same bargain [parseLookEffect] strikes over blend modes.
     */
    private fun checkedSpeedMasterUuid(element: JsonElement?): String? {
        val raw = element?.jsonPrimitive?.contentOrNull ?: return null
        resolveSpeedMaster(raw) ?: throw IllegalArgumentException(unknownSpeedMasterMessage(raw))
        return raw
    }

    /** Names what the model got wrong *and* the masters it could have picked, so a retry lands. */
    private fun unknownSpeedMasterMessage(raw: String?): String {
        val known = state.show.fxEngine.speedMasters.masterStates()
            .joinToString(", ") { "${it.name}=${it.uuid ?: "(unsaved)"}" }
        return "Unknown speed master '$raw'. Known masters: $known"
    }

    /**
     * The parsed `mask` argument, or null when it names a family that does not exist.
     *
     * Doubly-wrapped on purpose: the outer null is "reject this call", the inner one is the
     * legitimate "no mask at all" that [parseMaskGroups] returns for an absent or complete mask.
     */
    private fun JsonObject.maskGroups(): Result<Set<PropertyMaskGroup>?> = runCatching {
        parseMaskGroups(this["mask"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    /**
     * The target list under [key], or a failure naming the field a model left out — the same
     * "say which field is missing" treatment every other required argument here gets, rather
     * than an NPE surfacing through the dispatcher's generic handler.
     */
    private fun JsonObject.targetList(key: String): Result<List<CueTargetDto>?> = runCatching {
        this[key]?.jsonArray?.map {
            val obj = it.jsonObject
            CueTargetDto(
                type = obj["type"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("A '$key' entry is missing 'type'"),
                key = obj["key"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("A '$key' entry is missing 'key'"),
            )
        }
    }

    /** Skips and warnings are omitted when empty — an empty array is noise in a tool result. */
    private fun JsonObjectBuilder.putSkips(skips: List<RecordSkip>) {
        if (skips.isEmpty()) return
        put("skipped", buildJsonArray {
            // Through `toDto()`, so a skip reads the same here as in the REST response and picks
            // up any field `ProgrammerSkipDto` gains — `universe` and `channel` included, which a
            // hand-picked mapping was already dropping.
            for (dto in skips.map { it.toDto() }) {
                addJsonObject {
                    dto.targetKey?.let { put("targetKey", it) }
                    dto.propertyName?.let { put("propertyName", it) }
                    dto.universe?.let { put("universe", it) }
                    dto.channel?.let { put("channel", it) }
                    put("reason", dto.reason)
                }
            }
        })
    }

    /** The heads a value could not land on; omitted when every head took it. */
    internal fun JsonObjectBuilder.putValueSkips(skips: List<LookValueSkip>) {
        if (skips.isEmpty()) return
        putJsonArray("skipped") {
            skips.forEach { skip ->
                addJsonObject {
                    put("fixture", skip.target)
                    put("propertyName", skip.propertyName)
                    put("reason", skip.reason)
                }
            }
        }
    }

    private fun JsonObjectBuilder.putStrings(key: String, values: List<String>) {
        if (values.isEmpty()) return
        put(key, buildJsonArray { values.forEach { add(it) } })
    }

    /**
     * `run_fixture_command` (fixture optics plan session 7). Whether the caller may is decided before
     * this is reached, by `McpProtocol`, exactly as the REST route's `requireCommandsAccess` decides it.
     */
    private suspend fun executeRunFixtureCommand(input: JsonObject): ToolExecutionResult {
        val fixtureKey = input["fixtureKey"]?.jsonPrimitive?.contentOrNull ?: return errorResult("Missing 'fixtureKey'")
        val command = input["command"]?.jsonPrimitive?.contentOrNull ?: return errorResult("Missing 'command'")
        return when (val outcome = runFixtureCommand(state, fixtureKey, command)) {
            is FixtureCommandResult.Refused -> ToolExecutionResult(
                success = false,
                description = outcome.message,
                result = buildJsonObject { put("error", outcome.message); put("code", outcome.code) }.toString(),
            )
            is FixtureCommandResult.Done -> {
                val r = outcome.response
                ToolExecutionResult(
                    success = r.completed,
                    description = if (r.completed) "Ran '${r.label}' on '${r.fixtureName}' (${r.holdMs} ms)"
                        else "'${r.label}' on '${r.fixtureName}' was interrupted before its hold ended",
                    result = buildJsonObject {
                        put("fixture", r.fixture)
                        put("command", r.command)
                        put("holdMs", r.holdMs)
                        put("completed", r.completed)
                    }.toString(),
                )
            }
        }
    }

    private fun errorResult(message: String) = ToolExecutionResult(
        success = false,
        description = message,
        result = buildJsonObject { put("error", message) }.toString(),
    )

}

data class ToolExecutionResult(
    val success: Boolean,
    val description: String,
    val result: String,
    /** Images to answer beside [result] — MCP's image content (`render_view`'s PNG). */
    val images: List<ToolImage> = emptyList(),
)

/** One image in a tool's answer: its bytes, base64, and their media type. */
class ToolImage(val mimeType: String, val base64: String)
