package uk.me.cormack.lighting7.ai

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.transactions.transaction
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
            val result = togglePresetOnTargets(state, presetId, effects, toggleTargets, null)
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

        val presetEffects = transaction(state.database) {
            val preset = DaoFxPreset.findById(presetId) ?: return@transaction null
            preset.effects
        } ?: return errorResult("Preset not found: $presetId")

        val targets = targetsArray.map { t ->
            val obj = t.jsonObject
            TogglePresetTarget(
                type = obj["type"]!!.jsonPrimitive.content,
                key = obj["key"]!!.jsonPrimitive.content,
            )
        }

        val result = togglePresetOnTargets(state, presetId, presetEffects, targets, beatDivision)

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
            scriptResult = state.show.runLiteralScript(script, emptyList())
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
            val type = obj["type"]!!.jsonPrimitive.content
            val key = obj["key"]!!.jsonPrimitive.content
            totalRemoved += if (type == "group") {
                state.show.fxEngine.removeEffectsForGroup(key)
            } else {
                state.show.fxEngine.removeEffectsForFixture(key)
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
            ?: setOf("active_effects", "bpm", "fixtures", "groups", "presets", "palette")

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

    private fun errorResult(message: String) = ToolExecutionResult(
        success = false,
        description = message,
        result = """{"error": "$message"}"""
    )

    companion object {
        // ─── Tool Schema Definitions ───────────────────────────────────────

        private val targetSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("group"); add("fixture") })
                })
                put("key", buildJsonObject { put("type", "string") })
            })
            put("required", buildJsonArray { add("type"); add("key") })
        }

        private val presetEffectSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("effectType", buildJsonObject { put("type", "string") })
                put("category", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("dimmer"); add("colour"); add("position"); add("controls") })
                })
                put("propertyName", buildJsonObject {
                    put("type", "string")
                    put("description", "Target property. Usually inferred from category: dimmer→dimmer, colour→colour, position→position. Required for controls category.")
                })
                put("beatDivision", buildJsonObject {
                    put("type", "number")
                    put("description", "Effect cycle length in beats: 0.25=16th, 0.5=8th, 1.0=quarter, 2.0=half, 4.0=bar, 8.0=2bars")
                })
                put("blendMode", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("OVERRIDE"); add("ADDITIVE"); add("MULTIPLY"); add("MAX"); add("MIN") })
                })
                put("distribution", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("LINEAR"); add("UNIFIED"); add("CENTER_OUT"); add("EDGES_IN")
                        add("RANDOM"); add("PING_PONG"); add("REVERSE"); add("SPLIT"); add("POSITIONAL")
                    })
                })
                put("phaseOffset", buildJsonObject { put("type", "number"); put("description", "0.0-1.0") })
                put("elementMode", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("PER_FIXTURE"); add("FLAT") })
                })
                put("stepTiming", buildJsonObject {
                    put("type", "boolean")
                    put("description", "When true, beat division controls per-step time (total cycle = beatDivision × steps). When false, beat division controls total cycle time. Defaults to true for static effects (chase), false for continuous effects.")
                })
                put("elementFilter", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("ALL"); add("ODD"); add("EVEN"); add("FIRST_HALF"); add("SECOND_HALF") })
                })
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", buildJsonObject { put("type", "string") })
                    put("description", "Effect-specific parameters as string key-value pairs")
                })
            })
            put("required", buildJsonArray {
                add("effectType"); add("category"); add("beatDivision"); add("blendMode"); add("parameters")
            })
        }

        val createFxPresetTool = AnthropicToolDef(
            name = "create_fx_preset",
            description = "Create a new FX preset (a named collection of beat-synced effects) and optionally apply it immediately to targets. Returns the preset ID.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("name", buildJsonObject { put("type", "string"); put("description", "Preset name") })
                    put("description", buildJsonObject { put("type", "string"); put("description", "Optional description") })
                    put("fixtureType", buildJsonObject { put("type", "string"); put("description", "Optional typeKey to restrict preset to a fixture type") })
                    put("effects", buildJsonObject {
                        put("type", "array")
                        put("items", presetEffectSchema)
                    })
                    put("applyToTargets", buildJsonObject {
                        put("type", "array")
                        put("description", "Optional: immediately apply to these targets")
                        put("items", targetSchema)
                    })
                })
                put("required", buildJsonArray { add("name"); add("effects") })
            }
        )

        val applyPresetTool = AnthropicToolDef(
            name = "apply_preset",
            description = "Apply an existing FX preset to targets. If already active on all targets, it will be removed (toggle).",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("presetId", buildJsonObject { put("type", "integer") })
                    put("targets", buildJsonObject {
                        put("type", "array")
                        put("items", targetSchema)
                    })
                    put("beatDivision", buildJsonObject {
                        put("type", "number")
                        put("description", "Optional beat division override for all effects")
                    })
                })
                put("required", buildJsonArray { add("presetId"); add("targets") })
            }
        )

        val runLightingScriptTool = AnthropicToolDef(
            name = "run_lighting_script",
            description = "Run a Kotlin lighting script for direct fixture control. Scripts have access to: fixture<T>(key), group<T>(name), fxEngine, masterClock, coroutines. Use for setting fixture state, colours, positions, etc.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("script", buildJsonObject {
                        put("type", "string")
                        put("description", "Kotlin script body. Context: fixtures, fxEngine. Implicit imports for fixture types, Color, coroutines.")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Describe what this script does")
                    })
                })
                put("required", buildJsonArray { add("script"); add("description") })
            }
        )

        val setBpmTool = AnthropicToolDef(
            name = "set_bpm",
            description = "Set the master clock BPM for beat-synced effects.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("bpm", buildJsonObject { put("type", "number"); put("minimum", 20); put("maximum", 300) })
                })
                put("required", buildJsonArray { add("bpm") })
            }
        )

        val clearEffectsTool = AnthropicToolDef(
            name = "clear_effects",
            description = "Clear active effects. Omit targets to clear ALL effects globally.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("targets", buildJsonObject {
                        put("type", "array")
                        put("description", "Specific targets to clear. Omit for global clear.")
                        put("items", targetSchema)
                    })
                })
            }
        )

        val getCurrentStateTool = AnthropicToolDef(
            name = "get_current_state",
            description = "Get the current state of the lighting system. Use to check what's running before making changes.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("include", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("active_effects"); add("bpm"); add("fixtures"); add("groups"); add("presets"); add("palette")
                            })
                        })
                        put("description", "What to include. Defaults to all.")
                    })
                })
            }
        )

        val setPaletteTool = AnthropicToolDef(
            name = "set_palette",
            description = "Set the colour palette. Palette colours are shared across all running effects that use palette references (P1, P2, etc.). Changes take effect immediately on all running effects. Use colour names ('red'), hex ('#FF0000'), or extended format ('#ff0000;w128;a64;uv200').",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("colours", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Ordered list of palette colours. Each can be a colour name, hex code, or extended colour string.")
                    })
                })
                put("required", buildJsonArray { add("colours") })
            }
        )
    }
}

data class ToolExecutionResult(
    val success: Boolean,
    val description: String,
    val result: String,
)
