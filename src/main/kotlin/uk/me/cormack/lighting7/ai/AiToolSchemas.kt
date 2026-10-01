package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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

private val lookEffectSchema = buildJsonObject {
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
        put("speedMasterUuid", buildJsonObject {
            put("type", "string")
            put("description", "Speed master whose tempo this effect follows, named by the uuid from the Speed Masters list. Omit for master 1, the global tempo.")
        })
        put("rateSpeedMasterUuid", buildJsonObject {
            put("type", "string")
            put("description", "Speed master scaling this effect's wall-clock rate, named by uuid. Only wall-clock effects read it. Omit to leave the effect unscaled.")
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

internal val createLookTool = AnthropicToolDef(
    name = "create_look",
    description = "Create a new look (a named, reusable bundle of beat-synced effects and static values) and optionally apply it immediately to targets. Returns the look ID.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("name", buildJsonObject { put("type", "string"); put("description", "Look name") })
            put("description", buildJsonObject { put("type", "string"); put("description", "Optional notes") })
            put("effects", buildJsonObject {
                put("type", "array")
                put("items", lookEffectSchema)
            })
            put("scenery", buildJsonObject {
                put("type", "array")
                put("items", sceneryItemSchema(forCue = false))
                put("description", "Scene elements this Look shows while it is live — layered in a live cue, or pressed on a busk pad — above every cue (the Night look flies the moon in).")
            })
            put("applyToTargets", buildJsonObject {
                put("type", "array")
                put("description", "Optional: immediately apply to these targets")
                put("items", targetSchema)
            })
        })
        put("required", buildJsonArray { add("name") })
    }
)

internal val applyLookTool = AnthropicToolDef(
    name = "apply_look",
    description = "Apply an existing look to targets. If already active on all targets, it will be removed (toggle).",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("lookId", buildJsonObject { put("type", "integer") })
            put("targets", buildJsonObject {
                put("type", "array")
                put("items", targetSchema)
            })
            put("beatDivision", buildJsonObject {
                put("type", "number")
                put("description", "Optional beat division override for all effects")
            })
        })
        put("required", buildJsonArray { add("lookId"); add("targets") })
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
    description = "Set a speed master's BPM. Omitting speedMasterUuid retunes master 1 — the global tempo every unassigned effect follows.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("bpm", buildJsonObject { put("type", "number"); put("minimum", 20); put("maximum", 300) })
            put("speedMasterUuid", buildJsonObject {
                put("type", "string")
                put("description", "Which master to retune, by uuid from the Speed Masters list. Omit for master 1.")
            })
        })
        put("required", buildJsonArray { add("bpm") })
    }
)

internal val createSpeedMasterTool = AnthropicToolDef(
    name = "create_speed_master",
    description = "Add a speed master — an independent tempo clock effects can follow instead of the global one. Use it when part of the rig should run at its own speed. Returns the new master's uuid, which effects and cue layers reference.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("name", buildJsonObject {
                put("type", "string")
                put("description", "Master name. Must be unique in the project; defaults to 'Master {index}'.")
            })
            put("bpm", buildJsonObject {
                put("type", "number"); put("minimum", 20); put("maximum", 300)
                put("description", "Starting tempo. Defaults to the desk default.")
            })
            put("notes", buildJsonObject { put("type", "string"); put("description", "Optional notes") })
        })
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

/** The DMX address both park tools take — the desk's universe number and a channel in it — plus [extra]. */
private fun parkAddressProperties(extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
    put("universe", buildJsonObject {
        put("type", "integer")
        put("minimum", 0)
        put("description", "The desk's DMX universe number (counted from 0, as get_patch lists them).")
    })
    put("channel", buildJsonObject {
        put("type", "integer"); put("minimum", 1); put("maximum", 512)
        put("description", "DMX channel within the universe, 1–512. get_patch gives each fixture's startChannel–endChannel.")
    })
    extra()
}

internal val parkChannelTool = AnthropicToolDef(
    name = "park_channel",
    description = "Park a DMX channel: lock its output at a fixed value above everything else — cues, looks, effects, " +
            "the programmer and manual channel writes all stop reaching it until it is unparked. For holding a light " +
            "that must not move whatever the show does (a work light, a hazer's level, a channel with a faulty lamp " +
            "parked at 0). Parking an already parked channel changes its value. Park persists with the project, " +
            "across restarts. The answer names the fixture channel it drives, when it is patched.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", parkAddressProperties {
            put("value", buildJsonObject {
                put("type", "integer"); put("minimum", 0); put("maximum", 255)
                put("description", "The DMX value to hold, 0–255.")
            })
        })
        put("required", buildJsonArray { add("universe"); add("channel"); add("value") })
    }
)

internal val unparkChannelTool = AnthropicToolDef(
    name = "unpark_channel",
    description = "Unpark a parked DMX channel, handing it back to the show. The output does not jump: the parked " +
            "value is handed down as the channel's value underneath, where the next cue, effect or programmer " +
            "write takes over from it. get_current_state's `parked` (and describe_rig) list what is parked.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", parkAddressProperties())
        put("required", buildJsonArray { add("universe"); add("channel") })
    }
)

internal val aimFixturesTool = AnthropicToolDef(
    name = "aim_fixtures",
    description = "Point moving heads at a spot on the stage: give a point in stage coordinates and the desk works out " +
            "each head's pan and tilt from where it hangs (get_patch's `world` position), how its body is mounted " +
            "(its pitchDeg / yawDeg — for a moving head, pitchDeg 0 is standing on the deck and 180 is hung from a bar) " +
            "and its pan/tilt travel, and writes them into the programmer. The programmer is scratch: clearing it " +
            "loses the aim, so keep it — record_cue or update_from_programmer puts it in a cue, and saveAsTemplate " +
            "also records it as a position template (a focus palette) that cues, looks and busk pads can use. " +
            "Coordinates are metres, the frame get_patch and set_stage use: origin the centre of the downstage " +
            "edge at deck level, +x audience-right, +y upstage, +z up. A performer standing in a region is its " +
            "centerX / centerY with z its centerZ plus head height (about 1.7). A head that cannot be aimed is skipped " +
            "with the reason — a fixed lantern, an unplaced fixture, a type with no pan/tilt degree range, a point " +
            "outside its travel. dryRun reports what would land without writing it.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("targets", buildJsonObject {
                put("type", "array")
                put("items", targetSchema)
                put("description", "The fixtures to aim, or groups (every member is aimed).")
            })
            for (axis in listOf("x", "y", "z")) {
                put(axis, buildJsonObject {
                    put("type", "number")
                    put("minimum", -500); put("maximum", 500)
                    put("description", when (axis) {
                        "x" -> "Metres audience-right of centre (negative is audience-left)."
                        "y" -> "Metres upstage of the downstage edge (negative is in front of it)."
                        else -> "Metres above the deck."
                    })
                })
            }
            put("fadeMs", buildJsonObject {
                put("type", "integer"); put("minimum", 0)
                put("description", "Time for the heads to move there, in milliseconds. Omit to snap.")
            })
            put("dryRun", buildJsonObject {
                put("type", "boolean")
                put("description", "Work out and report each head's pan and tilt without writing anything.")
            })
            put("saveAsTemplate", buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "Also save the aims as a new position template with this name: one row per aimed head, " +
                        "in pan/tilt degrees, so it outlives the programmer. The name must be unused — a taken " +
                        "name refuses the call before any head moves. For heads aimed at different points in " +
                        "one template, dryRun each aim and pass the positions to create_template " +
                        "(value 'deg:<panDeg>,<tiltDeg>', targetKey the fixture).",
                )
            })
        })
        put("required", buildJsonArray { add("targets"); add("x"); add("y"); add("z") })
    }
)

internal val getCurrentStateTool = AnthropicToolDef(
    name = "get_current_state",
    description = "Get the current state of the lighting system. Use to check what's running before making changes. " +
            "`speed_masters` lists the tempo clocks and the uuids every effect-authoring tool names them by; " +
            "`cue_run` says what each running stack's next GO will fire; " +
            "`programmer` is the manual overlay this surface itself writes through apply_look; " +
            "`selection` is the desk's shared selection (what a selection-relative surface control acts on), " +
            "reported for context — the tools on this surface take explicit targets; " +
            "`windows` lists the browser windows signed in to the desk, which is how a `selection.source` " +
            "id reads back to a screen name; " +
            "`parked` lists the parked DMX channels (park_channel / unpark_channel), which hold their value above everything else.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("include", buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("active_effects"); add("bpm"); add("speed_masters"); add("fixtures")
                        add("groups"); add("looks"); add("templates"); add("cues")
                        add("cue_stacks"); add("cue_run"); add("programmer"); add("selection")
                        add("windows"); add("parked")
                    })
                })
                put("description", "What to include. Defaults to all.")
            })
        })
    }
)

// `set_palette` stood here. It wrote the global positional colour list that effects indexed as
// `P1` / `P2` — a single mutable list of unnamed slots, and the only thing the word "palette" still
// meant. Its successor is `create_template` below, and the difference is the point: a colour an
// effect should follow is a **template**, which has a name and a uuid, so retuning one moves every
// effect referencing it without a stage-wide mutation. `get_current_state` lists them under
// `templates`.
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
        put("speedMasterUuid", buildJsonObject {
            put("type", "string")
            put("description", "Speed master whose tempo this effect follows, by uuid. Omit for master 1.")
        })
        put("rateSpeedMasterUuid", buildJsonObject {
            put("type", "string")
            put("description", "Speed master scaling this effect's wall-clock rate, by uuid. Omit to leave it unscaled.")
        })
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

internal val cueLayerSchema = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("lookId", buildJsonObject { put("type", "integer") })
        put("targets", buildJsonObject {
            put("type", "array")
            put("items", targetSchema)
        })
        put("sortOrder", buildJsonObject {
            put("type", "integer")
            put("description", "Position in the cue's layer stack. Later layers override earlier ones for the same fixture and property, whatever the attribute.")
        })
        put("propertyMask", buildJsonObject {
            put("type", "string")
            put("description", "Optional comma-separated attribute families to include: INTENSITY, POSITION, COLOUR, BEAM. Omit for every property.")
        })
        put("blendMode", buildJsonObject {
            put("type", "string")
            put("description", "How this layer combines with the layers beneath: OVERRIDE (default), MAX, MIN, MULTIPLY, ADDITIVE")
        })
        put("speedMasterUuid", buildJsonObject {
            put("type", "string")
            put("description", "Speed master override for every effect this layer brings in, by uuid. Omit to let each effect keep its own master.")
        })
        put("rateSpeedMasterUuid", buildJsonObject {
            put("type", "string")
            put("description", "Wall-clock rate-master override for this layer's effects, by uuid. Omit to let each effect keep its own.")
        })
        put("amount", buildJsonObject {
            put("type", "number")
            put("description", "How much of this layer to mix over what is beneath, 0..1. Default 1.")
        })
    })
    put("required", buildJsonArray { add("lookId"); add("targets") })
}

/** One cue event in the tools' shape: a fixture by key, its trigger, an offset from GO. */
internal val cueEventItemSchema = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("fixture", buildJsonObject { put("type", "string"); put("description", "A patched fixture with one-shot triggers (a confetti cannon), by key or name. describe_rig lists them.") })
        put("trigger", buildJsonObject { put("type", "string"); put("description", "The tube: its trigger name ('output1') or label ('A').") })
        put("offsetSeconds", buildJsonObject { put("type", "number"); put("description", "How long after GO it fires, 0–600 s. Default 0.") })
    })
    put("required", buildJsonArray { add("fixture"); add("trigger") })
}

internal val createCueTool = AnthropicToolDef(
    name = "create_cue",
    description = "Create a named cue as an ordered stack of look layers plus its own local values and ad-hoc effects. Cues allow recalling a complete state with a single action. Use apply_cue to activate it later.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("name", buildJsonObject { put("type", "string"); put("description", "Unique cue name") })
            put("layers", buildJsonObject {
                put("type", "array")
                put("items", cueLayerSchema)
                put("description", "Looks to layer, with their targets, in sortOrder. Looks are read fresh at apply time, so editing one moves every cue that layers it.")
            })
            put("adHocEffects", buildJsonObject {
                put("type", "array")
                put("items", adHocEffectSchema)
                put("description", "Ad-hoc effects not from a Look layer, stored as full effect definitions")
            })
            put("scenery", buildJsonObject {
                put("type", "array")
                put("items", sceneryItemSchema(forCue = true))
                put("description", "Scene elements this cue moves on GO — tabs, flown pieces, pieces shown or hidden. $SCENERY_TRACKS_NOTE")
            })
            put("events", buildJsonObject {
                put("type", "array")
                put("items", cueEventItemSchema)
                put("description", CUE_EVENTS_NOTE)
            })
        })
        put("required", buildJsonArray { add("name") })
    }
)

internal val applyCueTool = AnthropicToolDef(
    name = "apply_cue",
    description = "Apply a saved cue by ID. By default, adds the cue's effects alongside other running cues. Set replaceAll=true to stop all other running cues first. If this cue is already running, its effects are refreshed.",
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
    description = "Activate a cue stack, starting playback from the first cue (or a specific cue). The cue's effects are started. If the cue has auto-advance configured, the stack will automatically advance to the next cue after the delay.",
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

internal val setStandbyTool = AnthropicToolDef(
    name = "set_standby",
    description = "Put a cue on deck: arm the cue the stack's next GO will fire, or omit cueId to disarm and " +
            "leave the positional next on deck. Arming changes no lights — it only decides what GO fires next, " +
            "which is why \"stand by cue 5\" and \"go\" are two separate gestures. `cue_run` in get_current_state " +
            "reports what is currently on deck.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("stackId", buildJsonObject { put("type", "integer"); put("description", "The cue stack ID") })
            put("cueId", buildJsonObject {
                put("type", "integer")
                put("description", "The cue to arm. Must belong to this stack. Omit to disarm.")
            })
        })
        put("required", buildJsonArray { add("stackId") })
    }
)

internal val goCueStackTool = AnthropicToolDef(
    name = "go_cue_stack",
    description = "Press GO on a cue stack — fire whatever is on deck. On a stopped stack that starts it, at the " +
            "armed standby if one is set and at its first cue otherwise; on a running stack it fires the armed " +
            "standby, else the next cue in order. This is the tool to use for running a show; advance_cue_stack " +
            "is for stepping BACKWARD, and activate_cue_stack for jumping to a named cue.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("stackId", buildJsonObject { put("type", "integer"); put("description", "The cue stack ID") })
        })
        put("required", buildJsonArray { add("stackId") })
    }
)

// ─── Programmer: Record / Include / Update ─────────────────────────

private val maskSchema = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray { add("INTENSITY"); add("POSITION"); add("COLOUR"); add("BEAM") })
    })
    put("description", "Attribute families to act on. Omit (or name all four) for no mask.")
}

internal val recordCueTool = AnthropicToolDef(
    name = "record_cue",
    description = "Record the programmer — the manual overlay busked on top of whatever is running — into a cue. " +
            "CREATE makes a new cue in a stack; MERGE adds the recorded values to an existing cue; " +
            "UPDATE_EXISTING replaces that cue's in-mask content; REMOVE deletes the rows the recording names. " +
            "Check `programmer` in get_current_state first: an empty programmer records an empty cue.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("mode", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add("CREATE"); add("MERGE"); add("REMOVE"); add("UPDATE_EXISTING") })
                put("description", "Defaults to CREATE.")
            })
            put("source", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add("TOUCHED"); add("ALL"); add("STAGE_SNAPSHOT") })
                put("description", "TOUCHED (default) records what was edited this session; STAGE_SNAPSHOT records everything on stage, running effects included.")
            })
            put("cueStackId", buildJsonObject { put("type", "integer"); put("description", "Required for CREATE.") })
            put("cueId", buildJsonObject { put("type", "integer"); put("description", "Required for every mode but CREATE.") })
            put("mask", maskSchema)
            put("includeFx", buildJsonObject {
                put("type", "boolean")
                put("description", "Record the running effects as well as the static values. Default true.")
            })
            put("name", buildJsonObject { put("type", "string"); put("description", "New cue's name (CREATE). Defaults to the next 'Cue N'.") })
            put("cueNumber", buildJsonObject { put("type", "string"); put("description", "New cue's number (CREATE). Omit to let the stack number it.") })
            put("sortOrder", buildJsonObject { put("type", "integer"); put("description", "Position in the stack (CREATE). Omit to append.") })
            put("cueType", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add("STANDARD"); add("MARKER") })
                put("description", "Defaults to STANDARD.")
            })
            put("targets", buildJsonObject {
                put("type", "array")
                put("items", targetSchema)
                put("description", "Record only these fixtures (groups are expanded). Omit to record the whole programmer.")
            })
        })
    }
)

internal val includeIntoProgrammerTool = AnthropicToolDef(
    name = "include_into_programmer",
    description = "Load a cue or a look back into the programmer as an edit buffer — the desk's Include. " +
            "Name exactly one of cueId or lookId. The included thing becomes the update target, so the " +
            "follow-up is: include, change what you want, then update_from_programmer with no targets, which " +
            "writes back only what changed and leaves everything else alone.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("cueId", buildJsonObject { put("type", "integer") })
            put("lookId", buildJsonObject { put("type", "integer") })
            put("mask", maskSchema)
            put("fadeMs", buildJsonObject {
                put("type", "integer")
                put("description", "Fade the staged values in over this many milliseconds. Default 0 (snap).")
            })
        })
    }
)

internal val updateFromProgrammerTool = AnthropicToolDef(
    name = "update_from_programmer",
    description = "Write the programmer back into what it came from — the desk's Update. With no targets it " +
            "writes only what changed since the last include_into_programmer, into that same cue or look. " +
            "With targets it writes each named cue exactly the keys the programmer is currently overriding it " +
            "on. With preview=true it writes nothing and returns the checklist of which cues the programmer is " +
            "sitting on top of, which is the safe thing to call first.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("targets", buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "integer") })
                put("description", "Cue ids to write. Omit to write back into the included cue or look.")
            })
            put("mask", maskSchema)
            put("preview", buildJsonObject {
                put("type", "boolean")
                put("description", "Return the checklist without writing anything. Default false.")
            })
            put("includeFx", buildJsonObject {
                put("type", "boolean")
                put("description", "Write the running effects as well as the static values. Default true.")
            })
        })
    }
)

/**
 * The effect an *effect template* holds — [adHocEffectSchema] minus `targetType` / `targetKey`.
 *
 * A template effect names no target (fx-templates D3): it fans over whatever applies it, so
 * offering the model a target field would invite it to write one the write boundary then refuses.
 */
private val templateEffectSchema = buildJsonObject {
    put("type", "object")
    put("description", "One effect. It has no target — an effect template runs on whatever the layer names.")
    put("properties", buildJsonObject {
        adHocEffectSchema["properties"]!!.jsonObject.forEach { (key, value) ->
            if (key != "targetType" && key != "targetKey" && key != "category") put(key, value)
        }
        // Narrowed from the ad-hoc enum: `controls` and `composite` belong to no attribute family
        // and the library has no beam effects, so the write boundary refuses all three (D4).
        // Offering them here only buys a rejected call and a retry.
        put("category", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("dimmer"); add("colour"); add("position") })
        })
    })
    put("required", buildJsonArray { add("effectType"); add("category"); add("beatDivision"); add("blendMode") })
}

internal val createTemplateTool = AnthropicToolDef(
    name = "create_template",
    description = "Create a template — a named, referenceable value (a colour, a position, an intensity) " +
            "or a named effect, that effects and cue layers point at instead of restating it. This is what " +
            "replaced the old positional colour palette: retuning the template moves everything referencing " +
            "it. Returns the uuid, which a colour parameter names as 'tmpl:{uuid}'. A template holds one " +
            "attribute family, and holds values OR one effect, never both — for both together, record a " +
            "look. Pass 'rows' for a value template (leave every row deferred, the default, to make a " +
            "generic one that can be aimed at any fixture) or 'effect' for an effect template, which always " +
            "runs on whatever the applying layer or selection names. An effect template's category must be " +
            "dimmer, colour or position: 'controls' and 'composite' belong to no attribute family, and the " +
            "library has no beam effects.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("name", buildJsonObject { put("type", "string"); put("description", "Unique template name in this project") })
            put("notes", buildJsonObject { put("type", "string"); put("description", "Optional notes") })
            put("fadeDurationMs", buildJsonObject {
                put("type", "integer")
                put("description", "Default fade when the template is applied. Omit for a snap.")
            })
            put("rows", buildJsonObject {
                put("type", "array")
                put("description", "The values. All rows must be the same attribute family (colour, position, intensity or beam).")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("propertyName", buildJsonObject {
                            put("type", "string")
                            put("description", "e.g. colour, dimmer, pan, tilt. Slotted properties (gobo, colour wheel, macros) belong in a look, not a template.")
                        })
                        put("value", buildJsonObject {
                            put("type", "string")
                            put("description", "The value as an intent, not a DMX literal: '#ff8800' for rgbColour, 'pct:75' for dimmer, strobe, zoom, focus, iris or frost, 'deg:<pan>,<tilt>' for position (travel degrees, what aim_fixtures reports), 'dmx:180' for white, amber or uv, 'on' / 'off' for prism.")
                        })
                        put("targetKey", buildJsonObject {
                            put("type", "string")
                            put("description", "Fixture key to bind this row to. Omit for a deferred row — the usual case, and the only kind an effect can reference by uuid.")
                        })
                    })
                    put("required", buildJsonArray { add("propertyName"); add("value") })
                })
            })
            put("effect", templateEffectSchema)
        })
        // `rows` is no longer required: exactly one of rows / effect is, which JSON Schema cannot
        // say in a way this API's validator enforces — so `executeCreateTemplate` says it, and the
        // description above tells the model which field to reach for.
        put("required", buildJsonArray { add("name") })
    }
)


/**
 * One scenery change in a tool call (stage-view plan session 8): an element of the scene document by
 * name, and the states it takes. [forCue] adds the cue's own clock. Shared by `create_cue`,
 * `create_look`, `set_scenery` and the MCP `build_cue_stack`, so they cannot disagree.
 */
internal fun sceneryItemSchema(forCue: Boolean): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("element", buildJsonObject {
            put("type", "string")
            put("description", "The scene element's name, as set_scene / get_scene give it (or its uuid).")
        })
        put("visible", buildJsonObject {
            put("type", "boolean")
            put("description", "Any element: shown (true) or hidden (false).")
        })
        put("open", buildJsonObject {
            put("type", "number")
            put("description", "A drawn drape (a DRAPE with operation DRAW — house tabs) only: 0 closed … 1 drawn fully open.")
        })
        put("trimM", buildJsonObject {
            put("type", "number")
            put("description", "A flown piece (an OBJECT with flies, or a DRAPE with operation FLY) only: its height in metres. Its own Z is where it plays (in); its stored trimM is usually out.")
        })
        if (forCue) {
            put("transitionSeconds", buildJsonObject {
                put("type", "number")
                put("description", "How long the move takes, on its own clock (tabs 4 s whatever the lighting fade). Omit to move with the cue's fade.")
            })
        }
    })
    put("required", buildJsonArray { add("element") })
}

/** The words every scenery list's description shares. */
internal const val SCENERY_TRACKS_NOTE =
    "Scenery tracks, unlike lighting: a change stays put until a later cue, the stack's set or a live Look moves it, so list only what changes."

/** The words every event list's description shares. */
internal const val CUE_EVENTS_NOTE =
    "Cue events: one-shot triggers (a confetti cannon's tubes) fired a set time after GO into this cue. They fire on GO into this cue only — never tracked, never on GO TO a later cue, never previewed — and only while the desk is armed: unarmed, the GO goes and the events are skipped and announced, never queued. Each tube fires once per cue. Arming and firing are the operator's, at the desk; you only author the events."

internal val setCueEventsTool = AnthropicToolDef(
    name = "set_cue_events",
    description = "Set one cue's events — the one-shot triggers (confetti cannon tubes) it fires after GO. $CUE_EVENTS_NOTE Replaces the whole list: send [] to clear it. Every event is checked before anything is written.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("cueId", buildJsonObject { put("type", "integer"); put("description", "The cue whose events to set.") })
            put("events", buildJsonObject {
                put("type", "array")
                put("items", cueEventItemSchema)
                put("description", "The whole list.")
            })
        })
        put("required", buildJsonArray { add("cueId"); add("events") })
    },
)

internal val setSceneryTool = AnthropicToolDef(
    name = "set_scenery",
    description = "Set the scenery of one cue, cue stack or Look — the scene elements it moves (tabs open or close, a flown piece flies, a piece appears). " +
        "A cue's changes move on GO into it, each on its own clock; a stack's are its set, held while the stack is live, under its cues; a Look's show while it is live (layered in a live cue, or pressed), above every cue. " +
        SCENERY_TRACKS_NOTE + " Replaces the whole list: send [] to clear it. Everything is checked against each element's kind before anything is written. Templates carry no scenery.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("cueId", buildJsonObject { put("type", "integer"); put("description", "The cue whose scenery to set. Give exactly one of cueId, stackId, lookId.") })
            put("stackId", buildJsonObject { put("type", "integer"); put("description", "The cue stack whose set to set.") })
            put("lookId", buildJsonObject { put("type", "integer"); put("description", "The Look whose scenery to set.") })
            put("scenery", buildJsonObject {
                put("type", "array")
                put("items", sceneryItemSchema(forCue = true))
                put("description", "The whole list. transitionSeconds is a cue's only.")
            })
        })
        put("required", buildJsonArray { add("scenery") })
    },
)
