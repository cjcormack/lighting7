package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import uk.me.cormack.lighting7.fx.ElementMode
import uk.me.cormack.lighting7.fx.SpreadShape

/** What `get_current_state` can `include`; anything else is refused by name. */
internal val CURRENT_STATE_PARTS: Set<String> = linkedSetOf(
    "active_fx", "bpm", "speed_masters", "fixtures", "groups", "looks", "palettes", "cues",
    "cue_stacks", "cue_run", "cue_holds", "programmer", "selection", "windows", "parked",
)

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

/**
 * [targetSchema] for a static value — a Look's or a cue's values and `set_programmer`'s targets, the
 * paths that resolve a cell by its own key and fan a value only the cells have out to them
 * (`ai/LookValues.kt`). Kept off the shared schema: not every tool taking a target reaches a cell
 * (`aim_fixtures` skips one, `clear_fx` matches a fixture's own effects), so the hint would be
 * a promise those break.
 */
private val valueTargetSchema = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("type", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("group"); add("fixture") })
        })
        put("key", buildJsonObject {
            put("type", "string")
            put(
                "description",
                "A fixture's or group's key — or, with type 'fixture', one element (head) of a multi-head fixture by its own key, " +
                    "as describe_rig lists its elements (e.g. 'shehds-1.zone-2').",
            )
        })
    })
    put("required", buildJsonArray { add("type"); add("key") })
}

/** An effect's cycle, where the tools describe one. One bar is venue-visit plan D16's default. */
private const val CYCLE_DESCRIPTION =
    "FX cycle length in beats: 0.25=16th, 0.5=8th, 1.0=one beat, 2.0=half a bar, 4.0=one bar, 8.0=two bars. " +
        "With seconds: true, the cycle in seconds instead. Omit for one bar (4.0)."

/** Timing (terminology plan D22), the same on every FX schema. */
private val timingSchema = buildJsonObject {
    put("type", "string")
    put("enum", buildJsonArray { add("CYCLE"); add("HEAD") })
    put(
        "description",
        "CYCLE: cycle is the length of the whole FX. HEAD: cycle is each head's step, so the whole FX takes cycle × steps. " +
            "Omit for the FX type's own: HEAD for a step chase, CYCLE for a continuous FX (every built-in).",
    )
}

/** What an FX's spread counts on multi-element fixtures, the same on every FX schema. */
private val elementModeSchema = buildJsonObject {
    put("type", "string")
    put("enum", buildJsonArray { ElementMode.entries.forEach { add(it.name) } })
    put(
        "description",
        "Only where the FX lands on fixtures' elements (a bar's cells). WHOLE: each fixture is one unit, its elements alike. " +
            "FLAT: every element of every fixture one strip. PER_FIXTURE: the FX runs within each fixture, every fixture alike. " +
            "Omit for WHOLE.",
    )
}

/** The speed-master field's words, shared by every effect schema. */
private const val SPEED_MASTER_DESCRIPTION =
    "Speed master whose tempo this FX follows, by uuid (describe_rig lists them). Omit for the master whose family " +
        "matches the FX's family (intensity, colour, position or beam), else master 1, the global tempo. Leave it out with seconds."

/** D7's Seconds, in the tools' words. */
private const val SECONDS_DESCRIPTION =
    "Run on Seconds: cycle is the cycle in seconds and the FX follows no speed master (for a slow breathe " +
        "that must not move with the tempo). Not with speedMasterUuid or rateSpeedMasterUuid."

/**
 * Amount, Direction, Runs (effects plan D2, D5, D9) and the spread's shape (D4), the same on every
 * effect schema — added where each schema lists its timing.
 */
private fun kotlinx.serialization.json.JsonObjectBuilder.putFxDynamics() {
    put("amount", buildJsonObject {
        put("type", "number")
        put(
            "description",
            "Amount, 0.0–1.0: how much of the FX reaches the stage over what is underneath (0.5 = halfway between " +
                "the level underneath and the FX; a movement FX's radius halves about the position underneath). " +
                "Omit for 1.0, the whole FX.",
        )
    })
    put("direction", buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray { add("FORWARD"); add("BACKWARD"); add("BOUNCE") })
        put("description", "Which way each head runs the cycle: BACKWARD runs it end to start, BOUNCE forward then back. Omit for FORWARD.")
    })
    put("runs", buildJsonObject {
        put("type", "integer")
        put("minimum", 1)
        put(
            "description",
            "Run this many cycles and stop, each head handing back to what is underneath after its own — a flash on cue " +
                "is 1. Omit to run forever.",
        )
    })
    put("spread", buildJsonObject {
        put("type", "number")
        put("minimum", 0)
        put("maximum", 2)
        put(
            "description",
            "How much of the cycle the heads cover, 0.0–2.0, over the order: 1.0 spreads them over one " +
                "whole cycle, 0.5 over half (a tighter ripple), 0 puts them all in step. Omit for 1.0. Spread, wings, " +
                "segments and parts shape a spreading order only — send one (NORMAL for a chase), since an omitted " +
                "order is UNIFIED, under which every head is in step and the shape does nothing.",
        )
    })
    put("wings", buildJsonObject {
        put("type", "integer")
        put("minimum", 1)
        put("maximum", SpreadShape.MAX_COUNT)
        put(
            "description",
            "Split the heads into this many wings, alternate wings mirrored (2 = a ripple from both ends meeting in the " +
                "middle). Only with NORMAL, REVERSE, RANDOM, PING_PONG or POSITIONAL: CENTRE_OUT and CENTRE_IN are two " +
                "wings already and refuse it. Omit for 1.",
        )
    })
    put("segments", buildJsonObject {
        put("type", "integer")
        put("minimum", 1)
        put("maximum", SpreadShape.MAX_COUNT)
        put("description", "This many neighbouring heads move as one (3 = the heads in threes). Omit for 1.")
    })
    put("parts", buildJsonObject {
        put("type", "integer")
        put("minimum", 0)
        put("maximum", SpreadShape.MAX_COUNT)
        put(
            "description",
            "Repeat the spread every this many heads (2 = alternate heads, odd against even). Omit or 0 for off.",
        )
    })
}

/** Where an effect runs, the same on a look's effects and a cue's ad-hoc ones: one target, or a selection. */
private fun kotlinx.serialization.json.JsonObjectBuilder.putFxTarget() {
    put("targetType", buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray { add("group"); add("fixture") })
    })
    put("targetKey", buildJsonObject { put("type", "string") })
    put("targets", buildJsonObject {
        put("type", "array")
        put("items", targetSchema)
        put(
            "description",
            "Instead of targetType and targetKey: one FX over these fixtures and groups, spread across them in this " +
                "order as a group's members are (a group contributes its members in its own order; a fixture named twice " +
                "counts once).",
        )
    })
}

private val lookFxSchema = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        putFxTarget()
        put("fxType", buildJsonObject { put("type", "string") })
        put("family", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("intensity"); add("colour"); add("position"); add("beam"); add("controls") })
        })
        put("attribute", buildJsonObject {
            put("type", "string")
            put("description", "Target attribute. Usually inferred from family: intensity→dimmer, colour→colour, position→position, beam→the one attribute the beam FX drives. Required for the controls family.")
        })
        put("cycle", buildJsonObject {
            put("type", "number")
            put("description", CYCLE_DESCRIPTION)
        })
        put("blendMode", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("OVERRIDE"); add("ADDITIVE"); add("MULTIPLY"); add("MAX"); add("MIN") })
        })
        put("order", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray {
                add("NORMAL"); add("UNIFIED"); add("CENTRE_OUT"); add("CENTRE_IN")
                add("RANDOM"); add("PING_PONG"); add("REVERSE"); add("POSITIONAL")
            })
        })
        put("phaseOffset", buildJsonObject { put("type", "number"); put("description", "0.0-1.0") })
        put("elementMode", elementModeSchema)
        put("timing", timingSchema)
        put("elementFilter", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("ALL"); add("ODD"); add("EVEN"); add("FIRST_HALF"); add("SECOND_HALF") })
        })
        put("speedMasterUuid", buildJsonObject {
            put("type", "string")
            put("description", SPEED_MASTER_DESCRIPTION)
        })
        put("rateSpeedMasterUuid", buildJsonObject {
            put("type", "string")
            put("description", "Speed master scaling this FX's wall-clock rate, named by uuid. Only wall-clock FX read it. Omit to leave the FX unscaled.")
        })
        put("seconds", buildJsonObject {
            put("type", "boolean")
            put("description", SECONDS_DESCRIPTION)
        })
        putFxDynamics()
        put("parameters", buildJsonObject {
            put("type", "object")
            put("additionalProperties", buildJsonObject { put("type", "string") })
            put("description", "FX-specific parameters as string key-value pairs")
        })
    })
    put("required", buildJsonArray {
        add("fxType"); add("family"); add("blendMode"); add("parameters")
    })
}

/** One static value of a Look or a cue: a fixture or group, a property, and what it is set to. */
internal val lookValueSchema = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("target", valueTargetSchema)
        put("attribute", buildJsonObject {
            put("type", "string")
            put("description", "The attribute, as list_fixture_types (attributes: true) names it: dimmer, rgbColour, position, strobe, zoom, gobo, fanSpeed…")
        })
        put("value", buildJsonObject {
            put("type", "string")
            put("description", "What it is set to: $LOOK_VALUE_GRAMMAR.")
        })
    })
    put("required", buildJsonArray { add("target"); add("attribute"); add("value") })
}

internal val createLookTool = ToolDef(
    name = "create_look",
    description = "Create a look — a named, reusable bundle of static values, beat-synced FX and scenery — and optionally apply it now. " +
            "'values' are the plain states (front wash at 70%, the cyc deep blue, the hazer's fan at 20%): each names its own fixture or group, " +
            "and is resolved per head the way a palette is, so 'pct:70' is 70% of each head's own range and a colour lands on a wheel head's nearest slot. " +
            "A value a head cannot take skips that head and is reported; a value no head of its target can take refuses the call. " +
            "A fixture's own value beats its group's, so 'the wash at 70%, hex-1 at 20%' is two values; the same target and attribute twice is refused. " +
            "A multi-head fixture's elements (heads) are targets too, each by its own key as describe_rig lists them; an attribute only the elements have " +
            "(the colour of a fixture whose colour is per zone — list_fixture_types' cellAttributes) set on the fixture or a group of them lands on every element, " +
            "and an element's own value beats it. " +
            "'fx' each name their own target too, as create_cue's ad-hoc FX do: targetType and targetKey, or targets for one FX over several fixtures and groups. " +
            "A look always lands whole — its own values, FX and scenery, never narrower and never wider — whether pressed (apply_look) or layered in a cue; " +
            "to point something at a selection, use a palette. Returns the look ID and the values as stored.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("name", buildJsonObject { put("type", "string"); put("description", "Look name, unique in the project") })
            put("description", buildJsonObject { put("type", "string"); put("description", "Optional notes") })
            put("values", buildJsonObject {
                put("type", "array")
                put("items", lookValueSchema)
                put("description", "Static values, each on its own fixture or group. A group stays one row while its members agree on the value, so the look follows the group.")
            })
            put("fx", buildJsonObject {
                put("type", "array")
                put("items", lookFxSchema)
            })
            put("scenery", buildJsonObject {
                put("type", "array")
                put("items", sceneryItemSchema(forCue = false))
                put("description", "Scene elements this Look shows while it is live — layered in a live cue, or pressed on a busk pad — above every cue (the Night look flies the moon in).")
            })
            put("apply", buildJsonObject {
                put("type", "boolean")
                put("description", "Apply it now, as apply_look would. Default false.")
            })
        })
        put("required", buildJsonArray { add("name") })
    }
)

internal val updateLookTool = ToolDef(
    name = "update_look",
    description = "Change an existing look: rename it, rewrite its notes, or replace its values, FX or scenery. " +
            "Each list given replaces that whole part of the look, in create_look's shape ([] empties it); a part left out is kept as it is. " +
            "Every cue layering the look moves with it, live ones included, without being re-fired. Everything is checked before anything is written.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("lookId", buildJsonObject { put("type", "integer") })
            put("name", buildJsonObject { put("type", "string"); put("description", "New name, unique in the project") })
            put("description", buildJsonObject {
                put("type", buildJsonArray { add("string"); add("null") })
                put("description", "New notes; null clears them")
            })
            put("values", buildJsonObject {
                put("type", "array")
                put("items", lookValueSchema)
                put("description", "The look's static values, replacing all of them — create_look's values.")
            })
            put("fx", buildJsonObject {
                put("type", "array")
                put("items", lookFxSchema)
                put("description", "The look's FX, replacing all of them — create_look's fx, each naming its target.")
            })
            put("scenery", buildJsonObject {
                put("type", "array")
                put("items", sceneryItemSchema(forCue = false))
                put("description", "The look's scenery, replacing all of it — create_look's scenery.")
            })
        })
        put("required", buildJsonArray { add("lookId") })
    }
)

internal val deleteLookTool = ToolDef(
    name = "delete_look",
    description = "Delete a look. Refused while a cue layers it — the answer names those cues — unless force is set, which deletes those layers too. " +
            "Its busk pads, cue slots and scenery go with it, and it is taken out of the programmer.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("lookId", buildJsonObject { put("type", "integer") })
            put("force", buildJsonObject {
                put("type", "boolean")
                put("description", "Delete it even though cues layer it, removing those layers. Default false.")
            })
        })
        put("required", buildJsonArray { add("lookId") })
    }
)

internal val applyLookTool = ToolDef(
    name = "apply_look",
    description = "Apply an existing look in the programmer as a layer, or take it off again (a toggle: already on, every layer of it is removed). " +
            "A look lands whole, on the fixtures its own values and FX name, whatever is selected. " +
            "asValues true instead writes its values into the programmer and starts its FX as the programmer's own, with no layer — not a toggle.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("lookId", buildJsonObject { put("type", "integer") })
            put("cycle", buildJsonObject {
                put("type", "number")
                put("description", "Optional cycle override for the Look's FX that follow a speed master; an FX on Seconds keeps its own cycle")
            })
            put("asValues", buildJsonObject {
                put("type", "boolean")
                put("description", "Write the look's values and start its FX with no layer. Default false.")
            })
        })
        put("required", buildJsonArray { add("lookId") })
    }
)

internal val setBpmTool = ToolDef(
    name = "set_bpm",
    description = "Set a speed master's BPM. Omitting speedMasterUuid retunes master 1 — the global tempo every unassigned FX follows.",
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

internal val createSpeedMasterTool = ToolDef(
    name = "create_speed_master",
    description = "Add a speed master — an independent tempo clock FX can follow instead of the global one. Use it when part of the rig should run at its own speed. Returns the new master's uuid, which FX and cue layers reference.",
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

internal val clearFxTool = ToolDef(
    name = "clear_fx",
    description = "Clear active FX. Omit targets to clear ALL FX globally. Naming targets clears " +
        "their own FX, and an FX over a selection only when every item it names is among them.",
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

internal val parkChannelTool = ToolDef(
    name = "park_channel",
    description = "Park a DMX channel: lock its output at a fixed value above everything else — cues, looks, FX, " +
            "the programmer and manual channel writes all stop reaching it until it is unparked. For holding a light " +
            "that must not move whatever the show does (a work light, a hazer's level, a channel with a faulty lamp " +
            "parked at 0). Parking an already parked channel changes its value. Park persists with the project, " +
            "across restarts. The answer names the fixture channel it drives, when it is patched. On a show on a venue, " +
            "a channel the venue parks (owner VENUE in get_current_state's `parked`) is re-parked for this show only — " +
            "the venue keeps its value, and unparking this show's park brings the venue's back. In a venue, parks are " +
            "the venue's rig and reach its shows when it publishes a version.",
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

internal val unparkChannelTool = ToolDef(
    name = "unpark_channel",
    description = "Unpark a parked DMX channel, handing it back to the show. The output does not jump: the parked " +
            "value is handed down as the channel's value underneath, where the next cue, FX or programmer " +
            "write takes over from it. get_current_state's `parked` (and describe_rig) list what is parked and whose " +
            "each park is. On a show on a venue, unparking a venue park releases it for this show only (the venue keeps " +
            "it, and the answer's outcome is RELEASED); unparking this show's park over a venue park brings the venue's " +
            "back (VENUE_RESTORED).",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", parkAddressProperties())
        put("required", buildJsonArray { add("universe"); add("channel") })
    }
)

internal val aimFixturesTool = ToolDef(
    name = "aim_fixtures",
    description = "Point moving heads at a spot on the stage: give a point in stage coordinates and the desk works out " +
            "each head's pan and tilt from where it hangs (get_patch's `world` position), how its body is mounted " +
            "(its pitchDeg / yawDeg — for a moving head, pitchDeg 0 is standing on the deck and 180 is hung from a bar) " +
            "and its pan/tilt travel, and writes them into the programmer. The programmer is scratch: clearing it " +
            "loses the aim, so keep it — record_cue or update_from_programmer puts it in a cue, and saveAsPalette " +
            "also records it as a position palette that cues, looks and busk pads can use. " +
            "Coordinates are metres, the frame get_patch and set_stage use: origin the centre of the downstage " +
            "edge at deck level, +x audience-right, +y upstage, +z up. A performer standing in a region is its " +
            "centerX / centerY with z its centerZ plus head height (about 1.7). A head that cannot be aimed is skipped " +
            "with the reason — a fixed lantern, an unplaced fixture, a type with no pan/tilt degree range, a point " +
            "outside its travel. focus: true also focuses each aimed head on the point, for a type whose focus " +
            "declares a range (the same solve as the Stage view's Focus here); a head it cannot focus, or that " +
            "was not aimed, is listed under focusSkipped with the reason. dryRun reports what would land without " +
            "writing it.",
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
            put("focus", buildJsonObject {
                put("type", "boolean")
                put(
                    "description",
                    "Also focus each aimed head on the point: its focus channel set for the distance from its " +
                        "lens, written into the programmer beside the aim (dryRun reports it instead); a head aim " +
                        "skipped is not focused. " +
                        "saveAsPalette still saves the position only — a palette is one family, and focus " +
                        "is beam — so keep a focus with record_cue.",
                )
            })
            put("saveAsPalette", buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "Also save the aims as a new position palette with this name: one row per aimed head, " +
                        "centre-relative (pos:), so it outlives the programmer. The name must be unused — a taken " +
                        "name refuses the call before any head moves. For heads aimed at different points in " +
                        "one palette, dryRun each aim and pass the positions to create_palette " +
                        "(value 'pos:<pan>,<tilt>' from each written value 'pan,tilt' as pan − 128, tilt − 128; " +
                        "targetKey the fixture).",
                )
            })
        })
        put("required", buildJsonArray { add("targets"); add("x"); add("y"); add("z") })
    }
)

internal val getCurrentStateTool = ToolDef(
    name = "get_current_state",
    description = "Get the current state of the lighting system. Use to check what's running before making changes. " +
            "`speed_masters` lists the tempo clocks and the uuids every FX-authoring tool names them by; " +
            "`cues` lists each cue's entries — its places in stacks, each with its entryId and number (a cue may be in several, or none: loose); " +
            "`cue_run` says what each running stack's next GO will fire, by entry and by cue; " +
            "`cue_holds` lists every live cue and what holds it — a stack's entry, or a loose hold (apply_cue, a pad); " +
            "`programmer` is the manual overlay this surface writes through set_programmer, apply_palette, apply_look, aim_fixtures and move_scenery, clears with clear_programmer and takes off stage with set_blind (`blind`; its `scenery` lists the elements held); " +
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
                    put("enum", buildJsonArray { CURRENT_STATE_PARTS.forEach { add(it) } })
                })
                put("description", "What to include. Defaults to all.")
            })
        })
    }
)

// `set_palette` stood here. It wrote the global positional colour list that effects indexed as
// `P1` / `P2` — a single mutable list of unnamed slots. Its successor is `create_palette` below, and
// the difference is the point: a colour an effect should follow is a named **palette** (stored as a
// template) with a uuid, so retuning one moves every effect referencing it without a stage-wide
// mutation. `get_current_state` lists them under `palettes`.
private val adHocFxSchema = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        putFxTarget()
        put("fxType", buildJsonObject { put("type", "string") })
        put("family", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("intensity"); add("colour"); add("position"); add("beam"); add("controls") })
        })
        put("attribute", buildJsonObject { put("type", "string") })
        put("cycle", buildJsonObject {
            put("type", "number")
            put("description", CYCLE_DESCRIPTION)
        })
        put("blendMode", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("OVERRIDE"); add("ADDITIVE"); add("MULTIPLY"); add("MAX"); add("MIN") })
        })
        put("order", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray {
                add("NORMAL"); add("UNIFIED"); add("CENTRE_OUT"); add("CENTRE_IN")
                add("RANDOM"); add("PING_PONG"); add("REVERSE"); add("POSITIONAL")
            })
        })
        put("phaseOffset", buildJsonObject { put("type", "number") })
        put("elementMode", elementModeSchema)
        put("elementFilter", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("ALL"); add("ODD"); add("EVEN"); add("FIRST_HALF"); add("SECOND_HALF") })
        })
        put("timing", timingSchema)
        put("speedMasterUuid", buildJsonObject {
            put("type", "string")
            put("description", SPEED_MASTER_DESCRIPTION)
        })
        put("rateSpeedMasterUuid", buildJsonObject {
            put("type", "string")
            put("description", "Speed master scaling this FX's wall-clock rate, by uuid. Omit to leave it unscaled.")
        })
        put("seconds", buildJsonObject {
            put("type", "boolean")
            put("description", SECONDS_DESCRIPTION)
        })
        putFxDynamics()
        put("parameters", buildJsonObject {
            put("type", "object")
            put("additionalProperties", buildJsonObject { put("type", "string") })
        })
    })
    put("required", buildJsonArray {
        add("fxType"); add("family")
        add("blendMode"); add("parameters")
    })
}

/** What a cue's own `values` are, for create_cue and build_cue_stack. */
internal const val CUE_VALUES_NOTE =
    "the cue's own values beat every layer of the cue, whatever its order; a value no look carries, or a touch on top of one. " +
        "Resolved per head as create_look's are."

internal val cueLayerSchema = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("lookId", buildJsonObject {
            put("type", "integer")
            put("description", "The look this layer applies. Give exactly one of lookId and paletteId.")
        })
        put("paletteId", buildJsonObject {
            put("type", "integer")
            put("description", "The palette this layer applies instead of a look (create_palette's id). A generic palette — an FX, or values for whatever it is pointed at — needs targets; one naming its own fixtures does not.")
        })
        put("targets", buildJsonObject {
            put("type", "array")
            put("items", targetSchema)
            put("description", "What the layer applies a palette to: a generic palette's FX or values land on these, so it needs them. A look's layer names none — a look always lands whole — so targets given for one are refused (LOOK_LAYER_TARGETED).")
        })
        put("sortOrder", buildJsonObject {
            put("type", "integer")
            put("description", "Position in the cue's layer stack. Later layers override earlier ones for the same fixture and attribute, whatever the attribute is.")
        })
        put("attributeMask", buildJsonObject {
            put("type", "string")
            put("description", "Optional comma-separated attribute families to include: INTENSITY, POSITION, COLOUR, BEAM. Omit for every attribute.")
        })
        put("blendMode", buildJsonObject {
            put("type", "string")
            put("description", "How this layer combines with the layers beneath: OVERRIDE (default), MAX, MIN, MULTIPLY, ADDITIVE")
        })
        put("speedMasterUuid", buildJsonObject {
            put("type", "string")
            put("description", "Speed master override for every FX this layer brings in, by uuid. Omit to let each FX keep its own master.")
        })
        put("rateSpeedMasterUuid", buildJsonObject {
            put("type", "string")
            put("description", "Wall-clock rate-master override for this layer's FX, by uuid. Omit to let each FX keep its own.")
        })
        put("amount", buildJsonObject {
            put("type", "number")
            put("description", "How much of this layer to mix over what is beneath, 0..1. Default 1.")
        })
    })
}

/** One speed master a cue sets on GO: the master by index or uuid, and its BPM. */
internal val cueSpeedMasterItemSchema = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("masterIndex", buildJsonObject { put("type", "integer"); put("description", "The master by its number in the project — 1 for Master 1. Works in any project; give this or speedMasterUuid.") })
        put("speedMasterUuid", buildJsonObject { put("type", "string"); put("description", "The master by uuid — get_current_state's speed_masters lists the current project's.") })
        put("bpm", buildJsonObject { put("type", "number"); put("description", "The tempo it is set to, 20–300 BPM.") })
    })
    put("required", buildJsonArray { add("bpm") })
}

internal const val CUE_SPEED_MASTERS_NOTE =
    "Speed masters this cue sets as it comes on: on every GO into it (GO TO and GO BACK included, and when another stack already holds it) and every apply_cue or pad press, never on a reload. A tap or set_bpm afterwards wins until the next cue that sets that master; it never tracks. A master that follows another is skipped at GO, as it refuses set_bpm."

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

/** A new entry's `number`, for create_cue and add_cue_to_stack. */
internal const val NEW_ENTRY_NUMBER_NOTE =
    "The new entry's cue number in the stack ('14', '14A'), unique there. Omit to let the stack number it from its neighbours. Needs stackId."

/** Where create_cue and add_cue_to_stack put a new entry. */
private val afterEntrySchema = buildJsonObject {
    put("type", "object")
    put("description", "Put the new entry just after this one: a cue by entryId or number, or a marker by entryId. Omit to append at the end of the stack. Needs stackId.")
    put("properties", buildJsonObject { putEntryRefProps("The entry to follow") })
}

/** A cue's place on the stack tools: the `stackId` it is in and the entry. */
private fun stackEntryInput(stackDescription: String, what: String, required: Boolean = true) = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("stackId", buildJsonObject { put("type", "integer"); put("description", stackDescription) })
        putEntryRefProps(what)
    })
    if (required) put("required", buildJsonArray { add("stackId") })
}

internal val createCueTool = ToolDef(
    name = "create_cue",
    description = "Create a named cue as an ordered stack of look and palette layers, its own values, and ad-hoc FX. Its static states come from the looks it layers — make them with create_look's values first — and from its own values. Cues allow recalling a complete state with a single action. The new cue is loose — in no stack — unless stackId places it in one; add_cue_to_stack places it later, apply_cue holds it live.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("name", buildJsonObject { put("type", "string"); put("description", "Unique cue name") })
            put("layers", buildJsonObject {
                put("type", "array")
                put("items", cueLayerSchema)
                put("description", "Looks or palettes to layer, with a palette's targets, in sortOrder. They are read fresh at apply time, so editing one moves every cue that layers it.")
            })
            put("values", buildJsonObject {
                put("type", "array")
                put("items", lookValueSchema)
                put("description", "The cue's own values, in create_look's values shape: $CUE_VALUES_NOTE")
            })
            put("adHocFx", buildJsonObject {
                put("type", "array")
                put("items", adHocFxSchema)
                put("description", "Ad-hoc FX not from a Look layer, stored as full FX definitions")
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
            put("speedMasters", buildJsonObject {
                put("type", "array")
                put("items", cueSpeedMasterItemSchema)
                put("description", CUE_SPEED_MASTERS_NOTE)
            })
            put("stackId", buildJsonObject { put("type", "integer"); put("description", "Also place the new cue in this stack, as an entry. Omit for a loose cue.") })
            put("number", buildJsonObject { put("type", "string"); put("description", NEW_ENTRY_NUMBER_NOTE) })
            put("after", afterEntrySchema)
        })
        put("required", buildJsonArray { add("name") })
    }
)

internal val applyCueTool = ToolDef(
    name = "apply_cue",
    description = "Hold a saved cue live by ID, outside any stack (a loose hold): it composes above every running stack, the newest applied cue on top, and starts its timed FX, triggers and events as a GO does. A cue already applied moves back to the top. A stack that also holds it is unaffected. Set replaceAll=true to let go of every other applied cue first (running stacks are never touched).",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("cueId", buildJsonObject { put("type", "integer"); put("description", "The cue ID to apply") })
            put("replaceAll", buildJsonObject {
                put("type", "boolean")
                put("description", "If true, let go of every other cue applied with apply_cue (or pressed on a pad, slot or binding) first. Running stacks are untouched. Default false.")
            })
        })
        put("required", buildJsonArray { add("cueId") })
    }
)

internal val stopCueTool = ToolDef(
    name = "stop_cue",
    description = "Let go of a cue applied with apply_cue (or pressed on a pad, slot or binding), by ID. The cue comes off the stage unless a running stack also holds it; stop a stack with deactivate_cue_stack.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("cueId", buildJsonObject { put("type", "integer"); put("description", "The cue ID to stop") })
        })
        put("required", buildJsonArray { add("cueId") })
    }
)

internal val createCueStackTool = ToolDef(
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

internal val activateCueStackTool = ToolDef(
    name = "activate_cue_stack",
    description = "Activate a cue stack, starting playback from the first cue (or the armed standby), or GO to one entry of it: name the entry by entryId or by its number in the stack. The cue's FX are started. If the entry has auto-advance configured, the stack will automatically advance to the next cue after the delay.",
    inputSchema = stackEntryInput("The cue stack ID to activate", "Optional: the entry to start at instead of the first"),
)

internal val deactivateCueStackTool = ToolDef(
    name = "deactivate_cue_stack",
    description = "Deactivate a cue stack, stopping all its FX and cancelling auto-advance.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("stackId", buildJsonObject { put("type", "integer"); put("description", "The cue stack ID to deactivate") })
        })
        put("required", buildJsonArray { add("stackId") })
    }
)

internal val advanceCueStackTool = ToolDef(
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

internal val addCueToStackTool = ToolDef(
    name = "add_cue_to_stack",
    description = "Add an existing cue to a cue stack as a new entry, and answer the entry. A cue already in another stack (or in this one) is shared: it plays from both, each entry with its own number and follow, and editing the cue changes it in both. To take a cue's place from one stack to another instead, use move_cue_to_stack.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("stackId", buildJsonObject { put("type", "integer"); put("description", "The cue stack ID") })
            put("cueId", buildJsonObject { put("type", "integer"); put("description", "The cue ID to add") })
            put("number", buildJsonObject { put("type", "string"); put("description", NEW_ENTRY_NUMBER_NOTE) })
            put("after", afterEntrySchema)
        })
        put("required", buildJsonArray { add("stackId"); add("cueId") })
    }
)

internal val removeCueFromStackTool = ToolDef(
    name = "remove_cue_from_stack",
    description = "Take one entry out of a stack — the cue's place there, with its number, follow and prompt-book anchor. The cue itself is never deleted: it stays in its other stacks, or is left loose (in no stack) if this was its last; delete_cues deletes cues. Removing the live entry changes nothing on stage until the stack's next GO, which fires the entry that followed it.",
    inputSchema = stackEntryInput("The cue stack ID", "The entry to remove"),
)

internal val moveCueToStackTool = ToolDef(
    name = "move_cue_to_stack",
    description = "Move one entry to the end of another stack, whole: the same entry, so its explicit number, its follow and its prompt-book anchor go with it (an auto number is renumbered where it lands). Refused when the other stack already holds that number. A live entry leaves the stage alone, as remove_cue_from_stack does. Answers the moved entry.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("stackId", buildJsonObject { put("type", "integer"); put("description", "The stack the entry is in now") })
            putEntryRefProps("The entry to move")
            put("toStackId", buildJsonObject { put("type", "integer"); put("description", "The stack to move it to") })
        })
        put("required", buildJsonArray { add("stackId"); add("toStackId") })
    }
)

internal val setStandbyTool = ToolDef(
    name = "set_standby",
    description = "Put a cue on deck: arm the entry the stack's next GO will fire (by entryId or by number), or name neither to disarm and " +
            "leave the positional next on deck. Arming changes no lights — it only decides what GO fires next, " +
            "which is why \"stand by cue 5\" and \"go\" are two separate gestures. `cue_run` in get_current_state " +
            "reports what is currently on deck.",
    inputSchema = stackEntryInput("The cue stack ID", "The entry to arm, in this stack; name none to disarm"),
)

internal val goCueStackTool = ToolDef(
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

internal val recordCueTool = ToolDef(
    name = "record_cue",
    description = "Record the programmer — the manual overlay busked on top of whatever is running — into a cue, " +
            "which then becomes what a bare update_from_programmer writes back to. " +
            "CREATE makes a new cue, placed in cueStackId when given and loose (in no stack) otherwise; MERGE adds the recorded values to an existing cue; " +
            "UPDATE_EXISTING replaces that cue's in-mask content; REMOVE deletes the rows the recording names. " +
            "Scenery the programmer holds (move_scenery; `programmer.scenery` in get_current_state) is recorded too unless " +
            "scenery is false: a change row for each held piece the cue would not show anyway — a held state equal to what " +
            "the cue tracks from earlier cues, its stack's set or the piece's base writes nothing, and a row the cue already " +
            "has for that piece is replaced. REMOVE deletes the cue's rows for the held pieces. The mask and targets never " +
            "narrow scenery. " +
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
                put("description", "TOUCHED (default) records what was edited this session; STAGE_SNAPSHOT records everything on stage, running FX included.")
            })
            put("cueStackId", buildJsonObject { put("type", "integer"); put("description", "CREATE's stack. Omit to record a cue in no stack (add_cue_to_stack places it later).") })
            put("cueId", buildJsonObject { put("type", "integer"); put("description", "Required for every mode but CREATE.") })
            put("mask", maskSchema)
            put("includeFx", buildJsonObject {
                put("type", "boolean")
                put("description", "Record the running FX as well as the static values. Default true.")
            })
            put("name", buildJsonObject { put("type", "string"); put("description", "New cue's name (CREATE). Defaults to the next 'Cue N'.") })
            put("cueNumber", buildJsonObject { put("type", "string"); put("description", "New cue's number in cueStackId (CREATE). Omit to let the stack number it.") })
            put("sortOrder", buildJsonObject { put("type", "integer"); put("description", "Position in cueStackId (CREATE). Omit to append.") })
            // No `cueType`: a marker is a stack entry, not a cue, and records nothing (cue model
            // plan D12); an old caller naming one is refused as an undeclared argument.
            put("targets", buildJsonObject {
                put("type", "array")
                put("items", targetSchema)
                put("description", "Record only these fixtures (groups are expanded). Omit to record the whole programmer.")
            })
            put("scenery", buildJsonObject {
                put("type", "boolean")
                put("description", "Record the scenery the programmer holds as well. Default true.")
            })
        })
    }
)

internal val includeIntoProgrammerTool = ToolDef(
    name = "include_into_programmer",
    description = "Load a cue or a look back into the programmer as an edit buffer — the desk's Include. " +
            "Name exactly one of cueId or lookId; a cue may also name the entry it is included from (entryId, or stackId and number), " +
            "which the desk's programmer indicator then shows. The included thing becomes the update target, so the " +
            "follow-up is: include, change what you want, then update_from_programmer with no targets, which " +
            "writes back only what changed and deletes the row of any value you cleared since (clear_programmer on a " +
            "target), leaving everything else alone. The source's own scenery rows are held " +
            "in the programmer too (a cue row keeps its own transition time), replacing what the programmer held on " +
            "those pieces — never the scenery the cue only tracks from earlier cues. The mask does not apply to scenery. " +
            "A look's FX run in the programmer too, each on its own target, where the operator can change them in the desk's " +
            "FX editor, and update_from_programmer writes those changes back onto the look's FX (to change them yourself, " +
            "update_look's fx).",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("cueId", buildJsonObject { put("type", "integer") })
            put("lookId", buildJsonObject { put("type", "integer") })
            put("stackId", buildJsonObject { put("type", "integer"); put("description", "With number: the stack of the entry the cue is included from.") })
            putEntryRefProps("Optional: the entry of cueId it is included from")
            put("mask", maskSchema)
            put("includeFx", buildJsonObject {
                put("type", "boolean")
                put("description", "Run a look's FX in the programmer too. Default true.")
            })
            put("fadeMs", buildJsonObject {
                put("type", "integer")
                put("description", "Fade the staged values in over this many milliseconds. Default 0 (snap).")
            })
        })
    }
)

internal val updateFromProgrammerTool = ToolDef(
    name = "update_from_programmer",
    description = "Write the programmer back into what it came from — the desk's Update. With no targets it " +
            "writes into the cue or look last loaded by include_into_programmer or written by record_cue / record_look, " +
            "only what changed since then, and " +
            "deletes the rows of any value an include staged and the programmer has since cleared. " +
            "With targets it writes each named cue exactly the keys the programmer is currently overriding it " +
            "on. With preview=true it writes nothing and returns the checklist of which cues the programmer is " +
            "sitting on top of, which is the safe thing to call first. With no targets, the scenery the programmer " +
            "holds is written back too — into the cue as change rows (an included row with its own transition time, " +
            "a held piece the cue already shows skipped), into a look as its scenery; with targets it is not.",
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
                put("description", "Write the running FX as well as the static values. Default true.")
            })
        })
    }
)

/**
 * The effect an *effect template* holds — [adHocFxSchema] minus `targetType`, `targetKey` and
 * `targets`.
 *
 * A template effect names no target (fx-templates D3): it fans over whatever applies it, so
 * offering the model a target field would invite it to write one the write boundary then refuses.
 */
private val paletteFxSchema = buildJsonObject {
    put("type", "object")
    put("description", "One FX. It has no target — an FX palette runs on whatever the layer names.")
    put("properties", buildJsonObject {
        adHocFxSchema["properties"]!!.jsonObject.forEach { (key, value) ->
            if (key !in setOf("targetType", "targetKey", "targets", "family")) put(key, value)
        }
        // Narrowed from the ad-hoc enum: `controls` belongs to no attribute family, so the write
        // boundary refuses it (D4). Offering it here only buys a rejected call and a retry.
        put("family", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("intensity"); add("colour"); add("position"); add("beam") })
        })
    })
    put("required", buildJsonArray { add("fxType"); add("family"); add("blendMode") })
}

internal val createPaletteTool = ToolDef(
    name = "create_palette",
    description = "Create a palette — a named, referenceable value (a colour, a position, an intensity) " +
            "or a named FX, that FX and cue layers point at instead of restating it: retuning the palette moves " +
            "everything referencing it. Returns the uuid, which a colour parameter names as 'pal:{uuid}'. A palette holds one " +
            "attribute family, and holds values OR one FX, never both — for both together, record a " +
            "look. Pass 'rows' for a value palette (leave every row deferred, the default, to make a " +
            "generic one that can be aimed at any fixture) or 'fx' for an FX palette, which always " +
            "runs on whatever the applying layer or selection names. An FX palette's family must be " +
            "intensity, colour, position or beam: 'controls' belongs to no attribute family.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("name", buildJsonObject { put("type", "string"); put("description", "Unique palette name in this project") })
            put("notes", buildJsonObject { put("type", "string"); put("description", "Optional notes") })
            put("fadeDurationMs", buildJsonObject {
                put("type", "integer")
                put("description", "Default fade when the palette is applied. Omit for a snap.")
            })
            put("rows", buildJsonObject {
                put("type", "array")
                put("description", "The values. All rows must be the same attribute family (colour, position, intensity or beam).")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("attribute", buildJsonObject {
                            put("type", "string")
                            put("description", "e.g. colour, dimmer, pan, tilt. Slotted attributes (gobo, colour wheel, macros) belong in a look, not a palette.")
                        })
                        put("value", buildJsonObject {
                            put("type", "string")
                            put("description", "The value as an intent, not a DMX literal: '#ff8800' for rgbColour, 'pct:75' for dimmer, strobe, zoom, focus, iris or frost, 'pos:<pan>,<tilt>' for position (centre-relative DMX, -128 to 127 on each axis, 0 the centre of travel at DMX 128: the same fraction of travel on every head; 'deg:<pan>,<tilt>', travel degrees, is what older palettes hold and is still read), 'dmx:180' for white, amber or uv, 'on' / 'off' for prism.")
                        })
                        put("targetKey", buildJsonObject {
                            put("type", "string")
                            put("description", "Fixture key to bind this row to. Omit for a deferred row — the usual case, and the only kind an FX can reference by uuid.")
                        })
                    })
                    put("required", buildJsonArray { add("attribute"); add("value") })
                })
            })
            put("fx", paletteFxSchema)
        })
        // `rows` is no longer required: exactly one of rows / fx is, which JSON Schema cannot
        // say in a way this API's validator enforces — so `executeCreatePalette` says it, and the
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

internal val setCueEventsTool = ToolDef(
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

internal val setSceneryTool = ToolDef(
    name = "set_scenery",
    description = "Set the scenery of one cue, cue stack or Look — the scene elements it moves (tabs open or close, a flown piece flies, a piece appears). " +
        "A cue's changes move on GO into it, each on its own clock; a stack's are its set, held while the stack is live, under its cues; a Look's show while it is live (layered in a live cue, or pressed), above every cue. " +
        SCENERY_TRACKS_NOTE + " Replaces the whole list: send [] to clear it. Everything is checked against each element's kind before anything is written. Palettes carry no scenery.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("cueId", buildJsonObject { put("type", "integer"); put("description", "The cue whose scenery to set. Give exactly one of cueId, stackId, lookId.") })
            put("stackId", buildJsonObject { put("type", "integer"); put("description", "The cue stack whose set to set.") })
            put("lookId", buildJsonObject { put("type", "integer"); put("description", "The Look whose scenery to set.") })
            put("projectId", buildJsonObject { put("type", "integer"); put("description", PROJECT_ID_DESCRIPTION) })
            put("scenery", buildJsonObject {
                put("type", "array")
                put("items", sceneryItemSchema(forCue = true))
                put("description", "The whole list. transitionSeconds is a cue's only.")
            })
        })
        put("required", buildJsonArray { add("scenery") })
    },
)

/** The states `move_scenery` reads beside its element. */
internal val MOVE_SCENERY_STATES = setOf("visible", "open", "trimM")

/** Every key `move_scenery` reads; anything else is refused by name. */
internal val MOVE_SCENERY_KEYS = MOVE_SCENERY_STATES + setOf("element", "fadeSeconds", "release")

/**
 * Move scenery now, through the programmer (scenery-programmer plan D15): the same overlay the
 * programmer rail's Scenery band and the Stage view write. Not held to a remote-access setting — scenery is drawn, never
 * output, so moving it reaches nothing physical.
 */
internal val moveSceneryTool = ToolDef(
    name = "move_scenery",
    description = "Move one scene element now — fly a piece in or out, draw or close the tabs, show or hide a piece — by holding it in the programmer, above every cue, stack set and Look until it is released or the programmer is cleared. " +
        "It moves at the element's own travel time (set_scene's travelS) scaled by how far it goes, or over fadeSeconds when given; with neither it snaps. " +
        "While the programmer is blind the move is staged and lands when Blind is left. Writes nothing stored: to make a cue or Look move it, use set_scenery. " +
        "Give states to hold (merged over what the programmer already holds on the element), or release: true to let it go back to what the show holds. Current project only. " +
        "record_cue (and update_from_programmer after include_into_programmer) writes what the programmer holds into a cue or look.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("element", buildJsonObject {
                put("type", "string")
                put("description", "The scene element's name, as set_scene / get_scene give it (or its uuid).")
            })
            put("visible", buildJsonObject { put("type", "boolean"); put("description", "Any element: shown (true) or hidden (false).") })
            put("open", buildJsonObject { put("type", "number"); put("description", "A drawn drape (a DRAPE with operation DRAW) only: 0 closed … 1 drawn fully open; anything between.") })
            put("trimM", buildJsonObject { put("type", "number"); put("description", "A flown piece (an OBJECT with flies, or a DRAPE with operation FLY) only: its height in metres. Its own Z is in; its stored trimM is usually out.") })
            put("fadeSeconds", buildJsonObject { put("type", "number"); put("description", "How long the move takes, overriding the element's travel time. 0 or omitted: its travel time.") })
            put("release", buildJsonObject { put("type", "boolean"); put("description", "true: let the element go instead of holding states, so it returns to what the cues, sets and Looks hold — over fadeSeconds when given.") })
        })
        put("required", buildJsonArray { add("element") })
    },
)

/**
 * Run one fixture command (fixture optics plan session 7, D13). Held to Remote access's "allow
 * fixture commands" by MCP (`McpProtocol`), which is always remote.
 */
internal val runFixtureCommandTool = ToolDef(
    name = RUN_FIXTURE_COMMAND,
    description = "Run one fixture command — a reset, a lamp strike or a lamp off — on one fixture. The desk holds the command's level for its declared time (a few seconds) and answers when it ends. " +
        "describe_rig lists each fixture's commands by name. A command is never a stored value: no look, cue, palette or programmer value can hold one, and this is the only way to run one besides the fixture panel. " +
        "Commands move heads and can leave a discharge lamp dark for minutes, in a room you cannot see: run one only when the operator asked for that command on that fixture. " +
        "Refused while the programmer is blind, while a channel it holds is parked, while the fixture is running another command, and over remote access unless an admin has allowed fixture commands.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("fixtureKey", buildJsonObject { put("type", "string"); put("description", "The fixture's key, as describe_rig lists it.") })
            put("command", buildJsonObject { put("type", "string"); put("description", "The command's name, as describe_rig lists it (e.g. 'reset', 'lampOn').") })
        })
        put("required", buildJsonArray { add("fixtureKey"); add("command") })
    },
)

internal const val RUN_FIXTURE_COMMAND = "run_fixture_command"

/** What the tool answers when a caller may not run one. */
internal const val FIXTURE_COMMANDS_REMOTE_REFUSAL =
    "run_fixture_command is not available: fixture commands are turned off for remote access. An admin can allow them in Install settings → Remote access."

// ─── The programmer: set, clear, Blind, template apply, Record into a Look ──

private val fadeSecondsSchema = buildJsonObject {
    put("type", "number")
    put("description", "Fade over this many seconds (0–600). Default 0, a snap.")
}

internal val setProgrammerTool = ToolDef(
    name = "set_programmer",
    description = "Set plain values in the programmer — the manual overlay above whatever is running, as the desk's " +
            "faders and pickers set it: a level, a colour, a position, a gobo or any other attribute a fixture has. " +
            "This is how a fixed state is set; there is no static FX. " +
            "A colour sets a head's white, amber and UV with it (none unless the value names them or asks for " +
            "';policy=extract' / ';policy=additive'), so give those in the colour ('#ff8000;w120') or as their own " +
            "values in the same call, where they win over the colour's. " +
            "Every value goes to every head of every target; a group's heads are held " +
            "as the group's, so record_cue and record_look write them back as one group row while they agree. " +
            "A value lands only on heads that can take it: the answer lists what was written and every head " +
            "skipped, with why; a value no selected head can take refuses the whole call. " +
            "The values stay until clear_programmer — record_cue or record_look keeps them.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("targets", buildJsonObject {
                put("type", "array")
                put("items", valueTargetSchema)
                put("description", "The fixtures and groups to set.")
            })
            put("values", buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("attribute", buildJsonObject {
                            put("type", "string")
                            put("description", "The attribute, as list_fixture_types (attributes: true) names it: dimmer, rgbColour, position, strobe, zoom, gobo, fanSpeed…")
                        })
                        put("value", buildJsonObject {
                            put("type", "string")
                            put("description", "What it is set to: $LOOK_VALUE_GRAMMAR.")
                        })
                    })
                    put("required", buildJsonArray { add("attribute"); add("value") })
                })
            })
            put("fadeSeconds", fadeSecondsSchema)
        })
        put("required", buildJsonArray { add("targets"); add("values") })
    }
)

internal val clearProgrammerTool = ToolDef(
    name = "clear_programmer",
    description = "Clear the programmer, as the desk's Clear does. With no targets: every value, every Look and " +
            "palette layer, every programmer FX and the scenery it holds, so the stage falls back to what " +
            "is running. With targets: everything the programmer holds on those fixtures (their heads included) " +
            "and groups, and the FX on just them — an FX that also drives heads outside them is left " +
            "running and named in `partial`. Layers are not taken off by targets; apply_look or apply_palette " +
            "pressed again takes one off.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("targets", buildJsonObject {
                put("type", "array")
                put("items", targetSchema)
                put("description", "Clear only these. Omit to clear everything.")
            })
            put("fadeSeconds", fadeSecondsSchema)
        })
    }
)

internal val setBlindTool = ToolDef(
    name = "set_blind",
    description = "Turn Blind on or off. In Blind the programmer keeps everything it holds and every tool that " +
            "writes it still works, but none of it reaches the stage — build a state, record it, then clear. " +
            "Its FX are held too: they keep running unseen and paint, in phase, when Blind goes off. " +
            "Its scenery is staged rather than moved, and a cue's one-shot events are rehearsed, not fired. " +
            "Turning Blind off puts the programmer back on stage. `programmer.blind` in get_current_state reads it.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("blind", buildJsonObject { put("type", "boolean") })
            put("fadeSeconds", fadeSecondsSchema)
        })
        put("required", buildJsonArray { add("blind") })
    }
)

internal val applyPaletteTool = ToolDef(
    name = "apply_palette",
    description = "Press a palette (by paletteId or paletteName) onto targets, as the desk's palette chips and pads do. " +
            "By default it puts on a programmer layer that tracks the palette, and toggles: pressing again on targets it " +
            "already covers takes it off them (get_current_state's `programmer.layers`). A palette pressed on heads " +
            "that hold another palette of its family and kind (value or FX) takes them off that one; pressed on " +
            "more heads straight after, its layer widens rather than stacking. asValues true instead writes the values " +
            "it resolves to into the programmer — later edits to the palette do not move them — or, for an FX " +
            "palette, starts a copy of its FX per target. A generic palette takes its heads from targets and " +
            "refuses with none (PALETTE_NEEDS_SELECTION); a per-fixture one lands on its own heads when targets are " +
            "omitted. A mask refuses a palette outside it (PALETTE_OUTSIDE_MASK).",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("paletteId", buildJsonObject { put("type", "integer") })
            put("paletteName", buildJsonObject { put("type", "string") })
            put("targets", buildJsonObject {
                put("type", "array")
                put("items", targetSchema)
            })
            put("asValues", buildJsonObject {
                put("type", "boolean")
                put("description", "Write the palette's values (or a copy of its FX) instead of a tracking layer. Default false.")
            })
            put("mask", maskSchema)
            put("fadeSeconds", buildJsonObject {
                put("type", "number")
                put("description", "asValues only: fade the values in over this many seconds (0–600). Default the palette's own fade.")
            })
        })
    }
)

internal val recordLookTool = ToolDef(
    name = "record_look",
    description = "Record the programmer into a Look, as the desk's Record to Look does. CREATE makes a new Look " +
            "named `name`; MERGE adds the recorded values to the Look `lookId`; UPDATE_EXISTING replaces its " +
            "in-mask rows for the recorded heads; REMOVE deletes the rows the recording names. A group's heads " +
            "that agree are recorded as one group row. Name targets — a Look recorded from the whole programmer " +
            "takes every head it holds. Scenery the programmer holds is recorded too unless scenery is false. " +
            "FX are moved into the Look only by fxIds (get_current_state's `activeFx`). The Look then " +
            "becomes what a bare update_from_programmer writes back to, as after record_cue. Returns the Look's rows.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("mode", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add("CREATE"); add("MERGE"); add("REMOVE"); add("UPDATE_EXISTING") })
                put("description", "Defaults to CREATE.")
            })
            put("name", buildJsonObject { put("type", "string"); put("description", "The new Look's name (CREATE).") })
            put("notes", buildJsonObject { put("type", "string"); put("description", "The new Look's notes (CREATE).") })
            put("lookId", buildJsonObject { put("type", "integer"); put("description", "Required for every mode but CREATE.") })
            put("source", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add("TOUCHED"); add("ALL"); add("STAGE_SNAPSHOT") })
                put("description", "TOUCHED (default) records what was edited this session; STAGE_SNAPSHOT records everything on stage.")
            })
            put("mask", maskSchema)
            put("targets", buildJsonObject {
                put("type", "array")
                put("items", targetSchema)
                put("description", "Record only these fixtures (groups are expanded). Omit to record the whole programmer.")
            })
            put("fxIds", buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "integer") })
                put("description", "Programmer FX to move into the Look; they stop running in the programmer and run through the Look instead.")
            })
            put("scenery", buildJsonObject {
                put("type", "boolean")
                put("description", "Record the scenery the programmer holds as well. Default true.")
            })
        })
    }
)
