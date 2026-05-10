package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

internal val createFxPresetTool = AnthropicToolDef(
    name = "create_fx_preset",
    description = "Create a new FX preset (a named collection of beat-synced effects) and optionally apply it immediately to targets. Returns the preset ID.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("name", buildJsonObject { put("type", "string"); put("description", "Preset name") })
            put("description", buildJsonObject { put("type", "string"); put("description", "Optional description") })
            put("fixtureType", buildJsonObject { put("type", "string"); put("description", "Required typeKey scoping the preset to a single fixture type") })
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
        put("required", buildJsonArray { add("name"); add("fixtureType"); add("effects") })
    }
)

internal val applyPresetTool = AnthropicToolDef(
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

internal val runLightingScriptTool = AnthropicToolDef(
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

internal val setBpmTool = AnthropicToolDef(
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

internal val clearEffectsTool = AnthropicToolDef(
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

internal val getCurrentStateTool = AnthropicToolDef(
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
                        add("active_effects"); add("bpm"); add("fixtures"); add("groups"); add("presets"); add("palette"); add("cues"); add("cue_stacks")
                    })
                })
                put("description", "What to include. Defaults to all.")
            })
        })
    }
)

internal val setPaletteTool = AnthropicToolDef(
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
private val adHocEffectSchema = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("targetType", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("group"); add("fixture") })
        })
        put("targetKey", buildJsonObject { put("type", "string") })
        put("effectType", buildJsonObject { put("type", "string") })
        put("category", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("dimmer"); add("colour"); add("position"); add("controls") })
        })
        put("propertyName", buildJsonObject { put("type", "string") })
        put("beatDivision", buildJsonObject { put("type", "number") })
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
        put("phaseOffset", buildJsonObject { put("type", "number") })
        put("elementMode", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("PER_FIXTURE"); add("FLAT") })
        })
        put("elementFilter", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("ALL"); add("ODD"); add("EVEN"); add("FIRST_HALF"); add("SECOND_HALF") })
        })
        put("stepTiming", buildJsonObject { put("type", "boolean") })
        put("parameters", buildJsonObject {
            put("type", "object")
            put("additionalProperties", buildJsonObject { put("type", "string") })
        })
    })
    put("required", buildJsonArray {
        add("targetType"); add("targetKey"); add("effectType"); add("category")
        add("beatDivision"); add("blendMode"); add("parameters")
    })
}

private val cuePresetApplicationSchema = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("presetId", buildJsonObject { put("type", "integer") })
        put("targets", buildJsonObject {
            put("type", "array")
            put("items", targetSchema)
        })
    })
    put("required", buildJsonArray { add("presetId"); add("targets") })
}

internal val createCueTool = AnthropicToolDef(
    name = "create_cue",
    description = "Create a named cue that bundles a colour palette with preset applications and ad-hoc effects. Cues allow recalling a complete look with a single action. Use apply_cue to activate it later.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("name", buildJsonObject { put("type", "string"); put("description", "Unique cue name") })
            put("palette", buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", "Colour palette as ordered colour strings (hex, names, extended format, or palette refs)")
            })
            put("updateGlobalPalette", buildJsonObject {
                put("type", "boolean")
                put("description", "When true, applying this cue also sets the global palette (affecting ad-hoc effects). Default false.")
            })
            put("presetApplications", buildJsonObject {
                put("type", "array")
                put("items", cuePresetApplicationSchema)
                put("description", "Presets to apply with their targets. Presets are read fresh at apply time.")
            })
            put("adHocEffects", buildJsonObject {
                put("type", "array")
                put("items", adHocEffectSchema)
                put("description", "Ad-hoc effects not from a preset, stored as full effect definitions")
            })
        })
        put("required", buildJsonArray { add("name") })
    }
)

internal val applyCueTool = AnthropicToolDef(
    name = "apply_cue",
    description = "Apply a saved cue by ID. By default, adds the cue's effects alongside other running cues. Set replaceAll=true to stop all other running cues first. The cue's palette is used for its own effects (isolated from the global palette unless updateGlobalPalette is set). If this cue is already running, its effects are refreshed.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("cueId", buildJsonObject { put("type", "integer"); put("description", "The cue ID to apply") })
            put("replaceAll", buildJsonObject {
                put("type", "boolean")
                put("description", "If true, stop all other running cues before applying this one. Default false.")
            })
        })
        put("required", buildJsonArray { add("cueId") })
    }
)

internal val stopCueTool = AnthropicToolDef(
    name = "stop_cue",
    description = "Stop a running cue by ID, removing all its effects. Other running cues are unaffected.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("cueId", buildJsonObject { put("type", "integer"); put("description", "The cue ID to stop") })
        })
        put("required", buildJsonArray { add("cueId") })
    }
)

internal val createCueStackTool = AnthropicToolDef(
    name = "create_cue_stack",
    description = "Create a cue stack — an ordered container of cues for sequential playback. Stacks support looping, auto-advance, and crossfade transitions between cues. After creating, use add_cue_to_stack to add cues, then activate_cue_stack to start playback.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("name", buildJsonObject { put("type", "string"); put("description", "Stack name") })
            put("palette", buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", "Stack-level base palette. Cue palettes override this when set.")
            })
            put("loop", buildJsonObject {
                put("type", "boolean")
                put("description", "Loop back to start after last cue. Default false.")
            })
        })
        put("required", buildJsonArray { add("name") })
    }
)

internal val activateCueStackTool = AnthropicToolDef(
    name = "activate_cue_stack",
    description = "Activate a cue stack, starting playback from the first cue (or a specific cue). The stack's palette is applied, and the cue's effects are started. If the cue has auto-advance configured, the stack will automatically advance to the next cue after the delay.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("stackId", buildJsonObject { put("type", "integer"); put("description", "The cue stack ID to activate") })
            put("cueId", buildJsonObject { put("type", "integer"); put("description", "Optional: start at a specific cue instead of the first") })
        })
        put("required", buildJsonArray { add("stackId") })
    }
)

internal val deactivateCueStackTool = AnthropicToolDef(
    name = "deactivate_cue_stack",
    description = "Deactivate a cue stack, stopping all its effects and cancelling auto-advance.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("stackId", buildJsonObject { put("type", "integer"); put("description", "The cue stack ID to deactivate") })
        })
        put("required", buildJsonArray { add("stackId") })
    }
)

internal val advanceCueStackTool = AnthropicToolDef(
    name = "advance_cue_stack",
    description = "Advance an active cue stack forward or backward to the next/previous cue. If at the end and looping is enabled, wraps around. If not looping, deactivates the stack.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("stackId", buildJsonObject { put("type", "integer"); put("description", "The cue stack ID to advance") })
            put("direction", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add("FORWARD"); add("BACKWARD") })
                put("description", "Direction to advance. Default FORWARD.")
            })
        })
        put("required", buildJsonArray { add("stackId") })
    }
)

internal val addCueToStackTool = AnthropicToolDef(
    name = "add_cue_to_stack",
    description = "Add an existing cue to a cue stack. The cue is moved into the stack (a cue can only belong to one stack). If sortOrder is omitted, the cue is appended to the end.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("stackId", buildJsonObject { put("type", "integer"); put("description", "The cue stack ID") })
            put("cueId", buildJsonObject { put("type", "integer"); put("description", "The cue ID to add") })
            put("sortOrder", buildJsonObject { put("type", "integer"); put("description", "Position in the stack (0-based). Omit to append.") })
        })
        put("required", buildJsonArray { add("stackId"); add("cueId") })
    }
)
