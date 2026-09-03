package uk.me.cormack.lighting7.ai

import uk.me.cormack.lighting7.models.CueTargetDto

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.fixture.group.detectCapabilities
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.fx.CueStackManager
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.routes.*
import uk.me.cormack.lighting7.fx.TemplateProperty
import uk.me.cormack.lighting7.state.State

/**
 * Defines the tools available to Claude and dispatches their execution.
 *
 * Each tool maps to existing backend functionality. Adding a new tool
 * requires adding a schema to [allTools] and a handler branch in [executeTool].
 */
class AiTools(private val state: State) {

    val allTools: List<AnthropicToolDef> = listOf(
        createLookTool,
        applyLookTool,
        runLightingScriptTool,
        setBpmTool,
        createSpeedMasterTool,
        clearEffectsTool,
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
        createTemplateTool,
    )

    /**
     * Execute a tool by name and return a JSON result string.
     */
    suspend fun executeTool(name: String, input: JsonObject): ToolExecutionResult {
        return try {
            when (name) {
                "create_look" -> executeCreateLook(input)
                "apply_look" -> executeApplyLook(input)
                "run_lighting_script" -> executeRunLightingScript(input)
                "set_bpm" -> executeSetBpm(input)
                "create_speed_master" -> executeCreateSpeedMaster(input)
                "clear_effects" -> executeClearEffects(input)
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
                "create_template" -> executeCreateTemplate(input)
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
                result = """{"error": "${e.message?.replace("\"", "\\\"") ?: "Unknown error"}"}"""
            )
        }
    }

    // ─── Tool Executors ────────────────────────────────────────────────────

    private fun executeCreateLook(input: JsonObject): ToolExecutionResult {
        val name = input["name"]?.jsonPrimitive?.content ?: return errorResult("Missing 'name'")
        val notes = input["description"]?.jsonPrimitive?.contentOrNull
        val effectsArray = input["effects"]?.jsonArray ?: return errorResult("Missing 'effects'")

        val effects = effectsArray.map { parseLookEffect(it.jsonObject) }

        val project = state.projectManager.currentProject
        val look = transaction(state.database) {
            val created = DaoLook.new {
                this.name = name
                this.notes = notes
                this.project = project
                this.sortOrder = (DaoLook.find { DaoLooks.project eq project.id }
                    .maxOfOrNull { it.sortOrder } ?: -1) + 1
            }
            // Authored through the AI surface, so every effect is deferred: the tool describes the
            // effects, and the targets come from whatever applies the look. (The tool authors no
            // rows — a Look row would have to name a fixture, which the model has no business
            // picking here.)
            effects.forEachIndexed { index, effect ->
                DaoLookEffect.new {
                    this.look = created
                    targetType = DEFERRED_TARGET_TYPE
                    targetKey = ""
                    effectType = effect.effectType
                    category = effect.category
                    propertyName = effect.propertyName
                    beatDivision = effect.beatDivision
                    blendMode = effect.blendMode
                    distribution = effect.distribution
                    phaseOffset = effect.phaseOffset
                    elementMode = effect.elementMode
                    elementFilter = effect.elementFilter
                    stepTiming = effect.stepTiming
                    parameters = effect.parameters
                    speedMasterUuid = speedMasterUuidOrNull(effect.speedMasterUuid)
                    rateSpeedMasterUuid = speedMasterUuidOrNull(effect.rateSpeedMasterUuid)
                    sortOrder = index
                }
            }
            created
        }
        state.show.fixtures.lookListChanged()

        val lookId = look.id.value

        // Optionally apply to targets
        val targets = input["applyToTargets"]?.jsonArray
        var appliedCount = 0
        if (targets != null && targets.isNotEmpty()) {
            val toggleTargets = targets.map { t ->
                val obj = t.jsonObject
                CueTargetDto(
                    type = obj["type"]!!.jsonPrimitive.content,
                    key = obj["key"]!!.jsonPrimitive.content,
                )
            }
            val outcome = state.show.programmerLayerStack.toggle(
                source = LayerSource.look(
                    lookId,
                    transaction(state.database) { look.uuid },
                    name,
                ),
                targets = toggleTargets.map { CueTargetDto(it.type, it.key) },
            )
            appliedCount = outcome.effectCount
        }

        return ToolExecutionResult(
            success = true,
            description = "Created look '$name' (id=$lookId, ${effects.size} effects)" +
                    if (appliedCount > 0) ", applied $appliedCount effects" else "",
            result = buildJsonObject {
                put("lookId", lookId)
                put("name", name)
                put("effectCount", effects.size)
                put("appliedEffectCount", appliedCount)
            }.toString()
        )
    }

    private fun executeApplyLook(input: JsonObject): ToolExecutionResult {
        val lookId = input["lookId"]?.jsonPrimitive?.int ?: return errorResult("Missing 'lookId'")
        val targetsArray = input["targets"]?.jsonArray ?: return errorResult("Missing 'targets'")
        val beatDivision = input["beatDivision"]?.jsonPrimitive?.doubleOrNull

        val look = transaction(state.database) {
            DaoLook.findById(lookId)?.let { it.uuid to it.name }
        } ?: return errorResult("Look not found: $lookId")

        val targets = targetsArray.map { t ->
            val obj = t.jsonObject
            CueTargetDto(
                type = obj["type"]!!.jsonPrimitive.content,
                key = obj["key"]!!.jsonPrimitive.content,
            )
        }

        // Same programmer layer the busking pads add — so the AI and the pads toggle the *same*
        // thing, rather than two mechanisms that each think they own the Look.
        val result = state.show.programmerLayerStack.toggle(
            source = LayerSource.look(lookId, look.first, look.second),
            targets = targets,
            beatDivisionOverride = beatDivision,
        )

        return ToolExecutionResult(
            success = true,
            description = "${result.action} ${result.effectCount} effects (look $lookId)",
            result = buildJsonObject {
                put("action", result.action)
                put("effectCount", result.effectCount)
            }.toString()
        )
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private suspend fun executeRunLightingScript(input: JsonObject): ToolExecutionResult {
        val script = input["script"]?.jsonPrimitive?.content ?: return errorResult("Missing 'script'")
        val description = input["description"]?.jsonPrimitive?.contentOrNull ?: "Run lighting script"

        var scriptResult: uk.me.cormack.lighting7.show.ScriptResult? = null
        val job = GlobalScope.launch {
            scriptResult = state.show.runLiteralScript(script)
        }
        job.join()

        val result = scriptResult?.toRunResult()
        val success = result?.status == "success"

        return ToolExecutionResult(
            success = success,
            description = if (success) description else "Script error: ${result?.result ?: result?.status}",
            result = buildJsonObject {
                put("status", result?.status ?: "unknown")
                if (result?.result != null) put("result", result.result)
                if (result?.messages?.isNotEmpty() == true) {
                    put("messages", buildJsonArray {
                        result.messages.forEach { msg ->
                            addJsonObject {
                                put("severity", msg.severity)
                                put("message", msg.message)
                            }
                        }
                    })
                }
            }.toString()
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
        for (target in targets) {
            val obj = target.jsonObject
            val ref = TargetRef.ofOrNull(
                obj["type"]!!.jsonPrimitive.content,
                obj["key"]!!.jsonPrimitive.content,
            ) ?: continue
            totalRemoved += when (ref) {
                is TargetRef.Group -> state.show.fxEngine.removeEffectsForGroup(ref.key)
                is TargetRef.Fixture -> state.show.fxEngine.removeEffectsForFixture(ref.key)
            }
        }

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
                "templates", "cues", "cue_stacks", "cue_run", "programmer",
            )

        val result = buildJsonObject {
            if ("bpm" in include) {
                put("bpm", state.show.fxEngine.masterClock.bpm.value)
                put("clockRunning", state.show.fxEngine.masterClock.isRunning.value)
            }

            if ("speed_masters" in include) {
                // The uuids belong here as well as in the system prompt: every effect-authoring
                // tool takes a `speedMasterUuid`, and a master created mid-conversation (by
                // `create_speed_master`, or by the operator at the desk) is unnameable until the
                // model can read its uuid back. The prompt is built once per conversation.
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
                            put("beatDivision", effect.timing.beatDivision)
                            put("blendMode", effect.blendMode.name)
                            put("isRunning", effect.isRunning)
                            effect.cueId?.let { put("cueId", it) }
                            effect.cueStackId?.let { put("cueStackId", it) }
                        }
                    }
                })
            }

            if ("fixtures" in include) {
                put("fixtures", buildJsonArray {
                    for (fixture in state.show.fixtures.fixtures) {
                        addJsonObject {
                            put("key", fixture.key)
                            put("name", fixture.fixtureName)
                            put("typeKey", fixture.typeKey)
                            put("groups", buildJsonArray {
                                state.show.fixtures.groupsForFixture(fixture.key).forEach { add(it) }
                            })
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

            if ("templates" in include) {
                // Generic colour templates only: they are the ones a `tmpl:` colour parameter can
                // name, and the uuid is what it names them by.
                val project = state.projectManager.currentProject
                val templates = transaction(state.database) {
                    DaoTemplate.find { DaoTemplates.project eq project.id }.mapNotNull { template ->
                        val row = template.rows.toList().singleOrNull()
                            ?.takeIf { TemplateProperty.ofOrNull(it.propertyName) == TemplateProperty.COLOUR }
                            ?.takeIf { it.targetType == DEFERRED_TARGET_TYPE }
                            ?: return@mapNotNull null
                        buildJsonObject {
                            put("name", template.name)
                            put("ref", "tmpl:${template.uuid}")
                            put("intent", row.value)
                        }
                    }
                }
                put("templates", buildJsonArray { templates.forEach { add(it) } })
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
                            val cueCount = DaoCue.find { DaoCues.cueStack eq stack.id }
                                .count()
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
                // The AI mutates the programmer itself — `apply_look` and `create_look`'s
                // `applyToTargets` both go through `programmerLayerStack.toggle`, which *toggles*:
                // without being able to read the stack back, a model asked to "add the wash" a
                // second time takes it off stage instead.
                val store = state.show.programmerStore
                put("programmer", buildJsonObject {
                    put("blind", store.blind)
                    put("layers", buildJsonArray {
                        for (layer in store.layers) {
                            addJsonObject {
                                put("layerId", layer.layerId)
                                put("sourceKind", layer.source.kind.name)
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
                            target.cueStackId?.let { put("cueStackId", it) }
                        })
                    }
                })
            }
        }

        return ToolExecutionResult(
            success = true,
            description = "Retrieved current state",
            result = result.toString()
        )
    }

    private fun executeCreateCue(input: JsonObject): ToolExecutionResult {
        val name = input["name"]?.jsonPrimitive?.content ?: return errorResult("Missing 'name'")
        val layersArray = input["layers"]?.jsonArray
        val adHocArray = input["adHocEffects"]?.jsonArray

        val layers = layersArray?.mapIndexed { index, layer ->
            val obj = layer.jsonObject
            CueLayerDto(
                lookId = obj["lookId"]!!.jsonPrimitive.int,
                targets = obj["targets"]!!.jsonArray.map { t ->
                    val tObj = t.jsonObject
                    CueTargetDto(
                        type = tObj["type"]!!.jsonPrimitive.content,
                        key = tObj["key"]!!.jsonPrimitive.content,
                    )
                },
                // Declaration order is the stack order unless the caller says otherwise, so a tool
                // call that lists layers bottom-to-top composes the way it reads.
                sortOrder = obj["sortOrder"]?.jsonPrimitive?.int ?: index,
                propertyMask = obj["propertyMask"]?.jsonPrimitive?.contentOrNull,
                // Unrecognised blends are rejected by `createCueChildren` below rather than here,
                // so this surface and the cue routes cannot disagree about what a valid blend is.
                blendMode = obj["blendMode"]?.jsonPrimitive?.contentOrNull ?: "OVERRIDE",
                amount = obj["amount"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
                speedMasterUuid = checkedSpeedMasterUuid(obj["speedMasterUuid"]),
                rateSpeedMasterUuid = checkedSpeedMasterUuid(obj["rateSpeedMasterUuid"]),
            )
        } ?: emptyList()
        val adHocEffects = adHocArray?.map { parseAdHocEffectFromJson(it.jsonObject) } ?: emptyList()

        val project = state.projectManager.currentProject
        val cue = transaction(state.database) {
            // Every cue must belong to a stack; land AI-created cues in "Unsorted" (created on
            // demand) so the operator can move them afterwards. Without this the NOT NULL FK on
            // cue_stack_id fails and the tool call blows up.
            val stack = getOrCreateUnsortedStack(project)
            val newCue = DaoCue.new {
                this.name = name
                this.project = project
                this.cueStack = stack
                this.sortOrder = stack.cues.count().toInt()
            }
            createCueChildren(newCue, adHocEffects, layers = layers)
            newCue
        }
        state.show.fixtures.cueListChanged()
        state.show.fixtures.cueStackListChanged()

        val cueId = cue.id.value
        return ToolExecutionResult(
            success = true,
            description = "Created cue '$name' (id=$cueId, ${layers.size} layers, ${adHocEffects.size} ad-hoc effects)",
            result = buildJsonObject {
                put("cueId", cueId)
                put("name", name)
                put("layerCount", layers.size)
                put("adHocEffectCount", adHocEffects.size)
            }.toString()
        )
    }

    private fun executeApplyCue(input: JsonObject): ToolExecutionResult {
        val cueId = input["cueId"]?.jsonPrimitive?.int ?: return errorResult("Missing 'cueId'")
        val replaceAll = input["replaceAll"]?.jsonPrimitive?.booleanOrNull ?: false

        val cueData = transaction(state.database) {
            val cue = DaoCue.findById(cueId) ?: return@transaction null
            // The one builder — this was a third hand-rolled construction, and it silently
            // dropped the cue's own property assignments and its triggers, plus every timing and
            // speed-master field on an ad-hoc effect. See `buildCueApplyData`.
            buildCueApplyData(cue)
        } ?: return errorResult("Cue not found: $cueId")

        val result = applyCue(state, cueData, replaceAll)

        // `applyCue` is the standalone apply path: it fires the immediate half and leaves the
        // timed half alone (a timed layer is excluded from its cook, a timed ad-hoc effect by
        // `delayMs == null && intervalMs == null`). Only a stack GO owns the timers, and their
        // teardown. Reported rather than dropped in silence: until this builder collapse the
        // hand-rolled DTO omitted the timing fields, so a delayed effect fired *instantly* here —
        // an operator who relied on that needs to see why it stopped.
        val skippedTimed = cueData.adHocEffects.count { it.delayMs != null || it.intervalMs != null } +
            cueData.layers.count { it.enabled && it.amount > 0.0 && it.isTimed }
        val timedNote = if (skippedTimed == 0) "" else
            "; $skippedTimed timed item(s) skipped — timed content only runs from a stack GO"

        return ToolExecutionResult(
            success = true,
            description = "Applied cue '${result.cueName}' (${result.effectCount} effects)" +
                (if (replaceAll) " [replaced all other cues]" else "") + timedNote,
            result = buildJsonObject {
                put("cueName", result.cueName)
                put("effectCount", result.effectCount)
                put("replaceAll", replaceAll)
                put("skippedTimed", skippedTimed)
            }.toString()
        )
    }

    private fun executeStopCue(input: JsonObject): ToolExecutionResult {
        val cueId = input["cueId"]?.jsonPrimitive?.int ?: return errorResult("Missing 'cueId'")

        val removedCount = state.show.fxEngine.removeEffectsForCue(cueId)

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
            manager.activateCueInStack(state, stackId, cueId)
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
            if (cueId != null) runState.setStandby(state, stackId, cueId) else runState.clearStandby(state, stackId)
        } catch (e: IllegalArgumentException) {
            return errorResult(e.message ?: "Failed to change the standby on stack $stackId")
        }

        // The effective next, not the arming, is what the model needs back: disarming leaves the
        // positional successor on deck, and that is the cue the next GO actually fires.
        val (nextCueId, nextCueName) = transaction(state.database) {
            val next = runState.effectiveNextCueId(state, stackId)
            next to next?.let { DaoCue.findById(it)?.name }
        }

        return ToolExecutionResult(
            success = true,
            description = if (cueId != null) {
                // Arming the cue that is already live is not an error, but it does not do what
                // "stand by cue 5" sounds like: `nextCueIdFrom` ignores a standby equal to the
                // active cue, so the next GO advances past it. Say so rather than promising a
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

        val result = transaction(state.database) {
            val stack = DaoCueStack.findById(stackId)
                ?: return@transaction null to "Cue stack not found: $stackId"
            val cue = DaoCue.findById(cueId)
                ?: return@transaction null to "Cue not found: $cueId"

            val order = sortOrder ?: run {
                val maxOrder = DaoCue.find { DaoCues.cueStack eq stackId }
                    .maxByOrNull { it.sortOrder }?.sortOrder ?: -1
                maxOrder + 1
            }

            cue.cueStack = stack
            cue.sortOrder = order

            cue.name to null
        }

        val (cueName, error) = result
        if (error != null) return errorResult(error)

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
        )

        return when (result) {
            is RecordCoreResult.Failure -> errorResult(result.message)
            is RecordCoreResult.Ok -> ToolExecutionResult(
                success = true,
                description = (if (result.outcome.created) "Recorded new cue" else "Recorded into cue") +
                    " '${result.details.name}' (id=${result.outcome.cueId}) — " +
                    "${result.outcome.assignmentsWritten} value(s), ${result.outcome.fxWritten} effect(s)",
                result = buildJsonObject {
                    put("cueId", result.outcome.cueId)
                    put("cueName", result.details.name)
                    put("created", result.outcome.created)
                    result.stackId?.let { put("cueStackId", it) }
                    put("assignmentsWritten", result.outcome.assignmentsWritten)
                    put("assignmentsRemoved", result.outcome.assignmentsRemoved)
                    put("fxWritten", result.outcome.fxWritten)
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
        )

        return when (result) {
            is IncludeCoreResult.Failure -> errorResult(result.message)
            is IncludeCoreResult.Cue -> ToolExecutionResult(
                success = true,
                description = "Included cue '${result.cueData.cueName}' — ${result.outcome.entriesWritten} " +
                    "value(s) and ${result.outcome.fxSpawned} effect(s) staged in the programmer",
                result = buildJsonObject {
                    put("kind", "CUE")
                    put("cueId", result.cueData.cueId)
                    put("name", result.cueData.cueName)
                    put("entriesWritten", result.outcome.entriesWritten)
                    put("fxSpawned", result.outcome.fxSpawned)
                    putStrings("fixtureKeys", result.outcome.fixtureKeys)
                    putSkips(result.outcome.skipped)
                    putStrings("warnings", result.outcome.warnings)
                }.toString()
            )
            is IncludeCoreResult.Look -> ToolExecutionResult(
                success = true,
                description = "Included look '${result.lookName}' — ${result.outcome.entriesWritten} " +
                    "value(s) staged in the programmer",
                result = buildJsonObject {
                    put("kind", "LOOK")
                    put("lookId", result.lookId)
                    put("name", result.lookName)
                    put("entriesWritten", result.outcome.entriesWritten)
                    putStrings("fixtureKeys", result.outcome.fixtureKeys)
                    putSkips(result.outcome.skipped)
                    putStrings("warnings", lookIncludeWarnings(result.lookName, result.outcome))
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
                description = "Updated look '${result.result.lookName}' — ${result.result.rowsWritten} row(s) written",
                result = buildJsonObject {
                    put("mode", "A")
                    put("applied", result.result.rowsWritten > 0)
                    put("lookId", result.result.lookId)
                    put("lookName", result.result.lookName)
                    put("rowsWritten", result.result.rowsWritten)
                    putSkips(result.skipped)
                }.toString()
            )
            is UpdateCoreResult.CuesUpdated -> ToolExecutionResult(
                success = true,
                description = if (result.results.isEmpty()) {
                    "Nothing written"
                } else {
                    "Updated " + result.results.joinToString { "'${it.cueName}' (${it.assignmentsWritten} value(s))" }
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
                    ?: return errorResult("A template row is missing 'propertyName'"),
                value = obj["value"]?.jsonPrimitive?.contentOrNull
                    ?: return errorResult("A template row is missing 'value'"),
            )
        } ?: emptyList()

        // An effect template instead of rows (D1). Reuses `parseLookEffect`, which is where the
        // enum checks live, then drops the fields a template effect has no place for — the target
        // (D3) and the sort order (D2).
        val effect = input["effect"]?.jsonObject?.let { obj ->
            val spec = parseLookEffect(obj)
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
            is TemplateCreateResult.Refused -> errorResult(result.message)
            is TemplateCreateResult.Ok -> ToolExecutionResult(
                success = true,
                description = "Created template '${result.template.name}' " +
                    (result.template.effect
                        ?.let { "(effect: ${it.effectType})" }
                        ?: "(${result.template.rows.size} row(s))") +
                    " — reference it as tmpl:${result.template.uuid}",
                result = buildJsonObject {
                    put("templateId", result.template.id)
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
    private fun parseLookEffect(obj: JsonObject): LookEffectSpec {
        val spec = LookEffectSpec(
            effectType = obj["effectType"]!!.jsonPrimitive.content,
            category = obj["category"]!!.jsonPrimitive.content,
            propertyName = obj["propertyName"]?.jsonPrimitive?.contentOrNull,
            beatDivision = obj["beatDivision"]!!.jsonPrimitive.double,
            blendMode = obj["blendMode"]!!.jsonPrimitive.content,
            distribution = obj["distribution"]?.jsonPrimitive?.content ?: "UNIFIED",
            phaseOffset = obj["phaseOffset"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            stepTiming = obj["stepTiming"]?.jsonPrimitive?.booleanOrNull,
            elementMode = obj["elementMode"]?.jsonPrimitive?.contentOrNull,
            elementFilter = obj["elementFilter"]?.jsonPrimitive?.contentOrNull,
            parameters = obj["parameters"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
            speedMasterUuid = checkedSpeedMasterUuid(obj["speedMasterUuid"]),
            rateSpeedMasterUuid = checkedSpeedMasterUuid(obj["rateSpeedMasterUuid"]),
        )
        EffectSpecCoercion.Strict.problem(
            blendMode = spec.blendMode,
            distribution = spec.distribution,
            elementMode = spec.elementMode,
            elementFilter = spec.elementFilter,
        )?.let { throw IllegalArgumentException(it) }
        return spec
    }

    /**
     * One ad-hoc cue effect out of a tool call.
     *
     * No enum check here, unlike [parseLookEffect]: everything this returns goes through
     * `createCueChildren`, which rejects an unrecognised value itself, and `executeTool`'s catch
     * turns that message into a failed result the same way.
     */
    private fun parseAdHocEffectFromJson(obj: JsonObject): CueAdHocEffectDto {
        return CueAdHocEffectDto(
            targetType = obj["targetType"]!!.jsonPrimitive.content,
            targetKey = obj["targetKey"]!!.jsonPrimitive.content,
            effectType = obj["effectType"]!!.jsonPrimitive.content,
            category = obj["category"]!!.jsonPrimitive.content,
            propertyName = obj["propertyName"]?.jsonPrimitive?.contentOrNull,
            beatDivision = obj["beatDivision"]!!.jsonPrimitive.double,
            blendMode = obj["blendMode"]!!.jsonPrimitive.content,
            distribution = obj["distribution"]?.jsonPrimitive?.content ?: "UNIFIED",
            phaseOffset = obj["phaseOffset"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            elementMode = obj["elementMode"]?.jsonPrimitive?.contentOrNull,
            elementFilter = obj["elementFilter"]?.jsonPrimitive?.contentOrNull,
            stepTiming = obj["stepTiming"]?.jsonPrimitive?.booleanOrNull,
            parameters = obj["parameters"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
            speedMasterUuid = checkedSpeedMasterUuid(obj["speedMasterUuid"]),
            rateSpeedMasterUuid = checkedSpeedMasterUuid(obj["rateSpeedMasterUuid"]),
        )
    }

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

    private fun JsonObjectBuilder.putStrings(key: String, values: List<String>) {
        if (values.isEmpty()) return
        put(key, buildJsonArray { values.forEach { add(it) } })
    }

    private fun errorResult(message: String) = ToolExecutionResult(
        success = false,
        description = message,
        result = """{"error": "$message"}"""
    )

}

data class ToolExecutionResult(
    val success: Boolean,
    val description: String,
    val result: String,
)
