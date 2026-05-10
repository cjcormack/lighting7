package uk.me.cormack.lighting7.ai

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.fx.CueStackManager
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.routes.*
import uk.me.cormack.lighting7.state.State

/**
 * Defines the tools available to Claude and dispatches their execution.
 *
 * Each tool maps to existing backend functionality. Adding a new tool
 * requires adding a schema to [allTools] and a handler branch in [executeTool].
 */
class AiTools(private val state: State) {

    val allTools: List<AnthropicToolDef> = listOf(
        createFxPresetTool,
        applyPresetTool,
        runLightingScriptTool,
        setBpmTool,
        clearEffectsTool,
        getCurrentStateTool,
        setPaletteTool,
        createCueTool,
        applyCueTool,
        stopCueTool,
        createCueStackTool,
        activateCueStackTool,
        deactivateCueStackTool,
        advanceCueStackTool,
        addCueToStackTool,
    )

    /**
     * Execute a tool by name and return a JSON result string.
     */
    suspend fun executeTool(name: String, input: JsonObject): ToolExecutionResult {
        return try {
            when (name) {
                "create_fx_preset" -> executeCreateFxPreset(input)
                "apply_preset" -> executeApplyPreset(input)
                "run_lighting_script" -> executeRunLightingScript(input)
                "set_bpm" -> executeSetBpm(input)
                "clear_effects" -> executeClearEffects(input)
                "get_current_state" -> executeGetCurrentState(input)
                "set_palette" -> executeSetPalette(input)
                "create_cue" -> executeCreateCue(input)
                "apply_cue" -> executeApplyCue(input)
                "stop_cue" -> executeStopCue(input)
                "create_cue_stack" -> executeCreateCueStack(input)
                "activate_cue_stack" -> executeActivateCueStack(input)
                "deactivate_cue_stack" -> executeDeactivateCueStack(input)
                "advance_cue_stack" -> executeAdvanceCueStack(input)
                "add_cue_to_stack" -> executeAddCueToStack(input)
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

    private fun executeCreateFxPreset(input: JsonObject): ToolExecutionResult {
        val name = input["name"]?.jsonPrimitive?.content ?: return errorResult("Missing 'name'")
        val description = input["description"]?.jsonPrimitive?.contentOrNull
        val fixtureType = input["fixtureType"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing 'fixtureType'")
        if (fixtureType.isBlank()) return errorResult("'fixtureType' cannot be blank")
        val effectsArray = input["effects"]?.jsonArray ?: return errorResult("Missing 'effects'")

        val effects = effectsArray.map { parsePresetEffect(it.jsonObject) }

        val project = state.projectManager.currentProject
        val preset = transaction(state.database) {
            DaoFxPreset.new {
                this.name = name
                this.description = description
                this.fixtureType = fixtureType
                this.project = project
                this.effects = effects
            }
        }
        state.show.fixtures.presetListChanged()

        val presetId = preset.id.value

        // Optionally apply to targets
        val targets = input["applyToTargets"]?.jsonArray
        var appliedCount = 0
        if (targets != null && targets.isNotEmpty()) {
            val toggleTargets = targets.map { t ->
                val obj = t.jsonObject
                TogglePresetTarget(
                    type = obj["type"]!!.jsonPrimitive.content,
                    key = obj["key"]!!.jsonPrimitive.content,
                )
            }
            val result = togglePresetOnTargets(
                state, presetId, effects,
                presetPropertyAssignments = emptyList(),
                toggleTargets, null,
            )
            appliedCount = result.effectCount
        }

        return ToolExecutionResult(
            success = true,
            description = "Created preset '$name' (id=$presetId, ${effects.size} effects)" +
                    if (appliedCount > 0) ", applied $appliedCount effects" else "",
            result = buildJsonObject {
                put("presetId", presetId)
                put("name", name)
                put("effectCount", effects.size)
                put("appliedEffectCount", appliedCount)
            }.toString()
        )
    }

    private fun executeApplyPreset(input: JsonObject): ToolExecutionResult {
        val presetId = input["presetId"]?.jsonPrimitive?.int ?: return errorResult("Missing 'presetId'")
        val targetsArray = input["targets"]?.jsonArray ?: return errorResult("Missing 'targets'")
        val beatDivision = input["beatDivision"]?.jsonPrimitive?.doubleOrNull

        val presetData = transaction(state.database) {
            val preset = DaoFxPreset.findById(presetId) ?: return@transaction null
            Triple(
                preset.effects,
                preset.toPropertyAssignmentDtos(),
                preset.palette.toPaletteColours(),
            )
        } ?: return errorResult("Preset not found: $presetId")
        val (presetEffects, presetAssignments, presetPalette) = presetData

        val targets = targetsArray.map { t ->
            val obj = t.jsonObject
            TogglePresetTarget(
                type = obj["type"]!!.jsonPrimitive.content,
                key = obj["key"]!!.jsonPrimitive.content,
            )
        }

        val result = togglePresetOnTargets(
            state, presetId, presetEffects, presetAssignments,
            targets, beatDivision, presetPalette = presetPalette,
        )

        return ToolExecutionResult(
            success = true,
            description = "${result.action} ${result.effectCount} effects (preset $presetId)",
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
        state.show.fxEngine.masterClock.setBpm(bpm)
        return ToolExecutionResult(
            success = true,
            description = "Set BPM to $bpm",
            result = """{"bpm": $bpm}"""
        )
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
            ?: setOf("active_effects", "bpm", "fixtures", "groups", "presets", "palette", "cues", "cue_stacks")

        val result = buildJsonObject {
            if ("bpm" in include) {
                put("bpm", state.show.fxEngine.masterClock.bpm.value)
                put("clockRunning", state.show.fxEngine.masterClock.isRunning.value)
            }

            if ("active_effects" in include) {
                put("activeEffects", buildJsonArray {
                    for (effect in state.show.fxEngine.getActiveEffects()) {
                        addJsonObject {
                            put("id", effect.id)
                            put("effectType", effect.effect.name)
                            put("targetKey", effect.target.targetKey)
                            put("propertyName", effect.target.propertyName)
                            put("isGroupTarget", effect.isGroupEffect)
                            put("beatDivision", effect.timing.beatDivision)
                            put("blendMode", effect.blendMode.name)
                            put("isRunning", effect.isRunning)
                            effect.presetId?.let { put("presetId", it) }
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

            if ("presets" in include) {
                val project = state.projectManager.currentProject
                val presets = transaction(state.database) {
                    DaoFxPreset.find { DaoFxPresets.project eq project.id }
                        .map { it.id.value to it.name }
                }
                put("presets", buildJsonArray {
                    for ((id, name) in presets) {
                        addJsonObject {
                            put("id", id)
                            put("name", name)
                        }
                    }
                })
            }

            if ("palette" in include) {
                val palette = state.show.fxEngine.getPalette()
                put("palette", buildJsonArray {
                    for ((i, colour) in palette.withIndex()) {
                        addJsonObject {
                            put("index", i + 1)
                            put("ref", "P${i + 1}")
                            put("colour", colour.toSerializedString())
                        }
                    }
                })
            }

            if ("cues" in include) {
                val project = state.projectManager.currentProject
                val cues = transaction(state.database) {
                    DaoCue.find { DaoCues.project eq project.id }
                        .map { cue ->
                            buildJsonObject {
                                put("id", cue.id.value)
                                put("name", cue.name)
                                put("paletteSize", cue.palette.size)
                                put("presetApplicationCount", cue.presetApplications.count())
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
        }

        return ToolExecutionResult(
            success = true,
            description = "Retrieved current state",
            result = result.toString()
        )
    }

    private fun executeSetPalette(input: JsonObject): ToolExecutionResult {
        val coloursArray = input["colours"]?.jsonArray ?: return errorResult("Missing 'colours'")
        val colours = coloursArray.map { parseExtendedColour(it.jsonPrimitive.content) }
        state.show.fxEngine.setPalette(colours)

        val paletteStr = colours.mapIndexed { i, c -> "P${i + 1}=${c.toSerializedString()}" }.joinToString(", ")
        return ToolExecutionResult(
            success = true,
            description = "Set palette to ${colours.size} colours: $paletteStr",
            result = buildJsonObject {
                put("paletteSize", colours.size)
                put("palette", buildJsonArray {
                    for ((i, colour) in colours.withIndex()) {
                        addJsonObject {
                            put("ref", "P${i + 1}")
                            put("colour", colour.toSerializedString())
                        }
                    }
                })
            }.toString()
        )
    }

    private fun executeCreateCue(input: JsonObject): ToolExecutionResult {
        val name = input["name"]?.jsonPrimitive?.content ?: return errorResult("Missing 'name'")
        val paletteArray = input["palette"]?.jsonArray
        val presetAppsArray = input["presetApplications"]?.jsonArray
        val adHocArray = input["adHocEffects"]?.jsonArray

        val updateGlobalPalette = input["updateGlobalPalette"]?.jsonPrimitive?.booleanOrNull ?: false
        val palette = paletteArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val presetApplications = presetAppsArray?.map { app ->
            val obj = app.jsonObject
            CuePresetApplicationDto(
                presetId = obj["presetId"]!!.jsonPrimitive.int,
                targets = obj["targets"]!!.jsonArray.map { t ->
                    val tObj = t.jsonObject
                    CueTargetDto(
                        type = tObj["type"]!!.jsonPrimitive.content,
                        key = tObj["key"]!!.jsonPrimitive.content,
                    )
                }
            )
        } ?: emptyList()
        val adHocEffects = adHocArray?.map { parseAdHocEffectFromJson(it.jsonObject) } ?: emptyList()

        val project = state.projectManager.currentProject
        val cue = transaction(state.database) {
            val newCue = DaoCue.new {
                this.name = name
                this.project = project
                this.palette = palette
                this.updateGlobalPalette = updateGlobalPalette
            }
            createCueChildren(newCue, presetApplications, adHocEffects)
            newCue
        }
        state.show.fixtures.cueListChanged()

        val cueId = cue.id.value
        return ToolExecutionResult(
            success = true,
            description = "Created cue '$name' (id=$cueId, ${palette.size} palette colours, ${presetApplications.size} preset applications, ${adHocEffects.size} ad-hoc effects)",
            result = buildJsonObject {
                put("cueId", cueId)
                put("name", name)
                put("paletteSize", palette.size)
                put("presetApplicationCount", presetApplications.size)
                put("adHocEffectCount", adHocEffects.size)
            }.toString()
        )
    }

    private fun executeApplyCue(input: JsonObject): ToolExecutionResult {
        val cueId = input["cueId"]?.jsonPrimitive?.int ?: return errorResult("Missing 'cueId'")
        val replaceAll = input["replaceAll"]?.jsonPrimitive?.booleanOrNull ?: false

        val cueData = transaction(state.database) {
            val cue = DaoCue.findById(cueId) ?: return@transaction null
            CueApplyData(
                cueId = cue.id.value,
                cueName = cue.name,
                palette = cue.palette,
                updateGlobalPalette = cue.updateGlobalPalette,
                presetApplications = cue.presetApplications.map { app ->
                    CuePresetApplicationDto(
                        presetId = app.preset.id.value,
                        targets = app.targets,
                    )
                },
                adHocEffects = cue.adHocEffects.map { effect ->
                    CueAdHocEffectDto(
                        targetType = effect.targetType,
                        targetKey = effect.targetKey,
                        effectType = effect.effectType,
                        category = effect.category,
                        propertyName = effect.propertyName,
                        beatDivision = effect.beatDivision,
                        blendMode = effect.blendMode,
                        distribution = effect.distribution,
                        phaseOffset = effect.phaseOffset,
                        elementMode = effect.elementMode,
                        elementFilter = effect.elementFilter,
                        stepTiming = effect.stepTiming,
                        parameters = effect.parameters,
                    )
                },
                stomp = cue.stomp,
                cueStackId = cue.cueStack?.id?.value,
                sortOrder = cue.sortOrder,
            )
        } ?: return errorResult("Cue not found: $cueId")

        val result = applyCue(state, cueData, replaceAll)

        return ToolExecutionResult(
            success = true,
            description = "Applied cue '${result.cueName}' (${result.effectCount} effects)" +
                if (replaceAll) " [replaced all other cues]" else "",
            result = buildJsonObject {
                put("cueName", result.cueName)
                put("effectCount", result.effectCount)
                put("replaceAll", replaceAll)
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
        val paletteArray = input["palette"]?.jsonArray
        val palette = paletteArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val loop = input["loop"]?.jsonPrimitive?.booleanOrNull ?: false

        val project = state.projectManager.currentProject
        val stack = transaction(state.database) {
            DaoCueStack.new {
                this.name = name
                this.project = project
                this.palette = palette
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
        val targetCueId = cueId ?: transaction(state.database) {
            DaoCue.find { DaoCues.cueStack eq stackId }
                .orderBy(DaoCues.sortOrder to SortOrder.ASC)
                .firstOrNull()?.id?.value
        } ?: return errorResult("Stack $stackId has no cues")

        val result = manager.activateCueInStack(state, stackId, targetCueId)

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
        val removedCount = manager.deactivateStack(stackId)

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

        if (result == null) {
            return ToolExecutionResult(
                success = true,
                description = "Stack $stackId reached end — deactivated (not looping)",
                result = buildJsonObject {
                    put("stackId", stackId)
                    put("deactivated", true)
                }.toString()
            )
        }

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

    // ─── Helpers ───────────────────────────────────────────────────────────

    private fun parsePresetEffect(obj: JsonObject): FxPresetEffectDto {
        return FxPresetEffectDto(
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
        )
    }

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
        )
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
