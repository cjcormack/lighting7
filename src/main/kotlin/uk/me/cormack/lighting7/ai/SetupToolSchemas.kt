package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uk.me.cormack.lighting7.fixture.FixtureKind

// ─── Show-setup tool schemas (MCP only) ─────────────────────────────────
//
// The tools a remote Claude uses to *build* a show rather than run one: a project, its patch, the
// stage and its rigging, and the cue stacks and prompt-book markup of the show itself. Their
// source material — another console's patch export, a plot, a photo of the rig, a script and a
// designer's notes — is a PDF or an image in the conversation, which the model reads itself; every
// tool here takes the structured result. Nothing here takes file bytes: the prompt-book PDF is the
// one file the desk must hold, and it comes in through the desk's own Prompt Book import, which
// already hashes it and counts its pages. See `docs/mcp-engineering.md` §"Show-setup tools".

private fun JsonObjectBuilder.prop(name: String, type: String, description: String? = null) {
    put(name, buildJsonObject {
        put("type", type)
        description?.let { put("description", it) }
    })
}

private fun JsonObjectBuilder.enumProp(name: String, values: List<String>, description: String? = null) {
    put(name, buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray { values.forEach { add(it) } })
        description?.let { put("description", it) }
    })
}

private fun JsonObjectBuilder.arrayProp(name: String, items: JsonObject, description: String? = null) {
    put(name, buildJsonObject {
        put("type", "array")
        put("items", items)
        description?.let { put("description", it) }
    })
}

private fun objectSchema(required: List<String> = emptyList(), properties: JsonObjectBuilder.() -> Unit) =
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject(properties))
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(it) } })
    }

private val stringArray = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject { put("type", "string") })
}

internal val RIGGING_KINDS = listOf("TRUSS", "BAR", "BOOM", "PIPE", "FLOOR_STAND", "OTHER")

private const val COORDINATES =
    "Stage coordinates are metres, FOH-relative, Z-up: origin = centre of the downstage edge at deck level; " +
        "+x = audience-right (actor's stage left), +y = upstage, +z = up."

/** The fields a fixture's physical placement shares between patch_fixtures and place_fixtures. */
private fun JsonObjectBuilder.placementProps() {
    prop("rigging", "string", "Name of the rigging (truss, bar, boom…) it hangs from, as set_stage created it. When set, x/y/z are offsets along that rigging's own frame (x along its length) rather than world coordinates.")
    prop("x", "number", "Metres. World x, or offset along the rigging when `rigging` is set.")
    prop("y", "number", "Metres. World y, or offset in the rigging's frame.")
    prop("z", "number", "Metres. World z (height above deck), or offset in the rigging's frame (usually 0 or slightly negative for a hung fixture).")
    prop("yawDeg", "number", "Body rotation about Z. 0 = pointing at the audience (−y); 180 = pointing upstage (a backlight); +yaw turns toward audience-right.")
    prop("pitchDeg", "number", "Body rotation about X. 0 = horizontal, +pitch aims down. A typical FOH or overhead unit is 30–60.")
    prop("beamAngleDeg", "integer", "Beam angle 2–120, for fixture types that accept one (profiles, generic dimmers).")
    prop("gelCode", "string", "Gel as the plot writes it, e.g. 'L201', 'R80'.")
    enumProp("kind", FixtureKind.entries.map { it.name }, "Override the drawn fixture kind — e.g. PROFILE or FRESNEL for a generic dimmer.")
    prop("stageHidden", "boolean", "Hide from the Stage view (a patch that is DMX but not a stage object: a dimmer on hard power, a hazer's fan).")
}

internal val listProjectsTool = AnthropicToolDef(
    name = "list_projects",
    description = "List the desk's projects (id, name, description, which one is current). A project is one show: its patch, stage, looks, cues and prompt book. Every other tool acts on the current project.",
    inputSchema = objectSchema {},
)

internal val createProjectTool = AnthropicToolDef(
    name = "create_project",
    description = "Create a new, empty project (a show). It is not current until switched to, and every other tool acts on the current project — so to build it, create it and then switch_project (or pass switchTo). Stage dimensions can also be set later with set_stage.",
    inputSchema = objectSchema(required = listOf("name")) {
        prop("name", "string", "Project name, unique on the desk, at most 50 characters.")
        prop("description", "string", "Optional, at most 255 characters.")
        prop("stageWidthM", "number", "Stage width along x, metres.")
        prop("stageDepthM", "number", "Stage depth along y, metres.")
        prop("stageHeightM", "number", "Trim height along z, metres.")
        prop("switchTo", "boolean", "Also make it the current project (see switch_project for what that does to the live output). Default false.")
    },
)

internal val switchProjectTool = AnthropicToolDef(
    name = "switch_project",
    description = "Make another project the current one. This stops the running show: every effect and cue stops and all DMX output goes to zero before the new project's patch loads. Only do it when the operator has said the desk is not in use for a show, or asked for the switch. Call describe_rig afterwards.",
    inputSchema = objectSchema(required = listOf("projectId")) {
        prop("projectId", "integer", "From list_projects.")
    },
)

internal val listFixtureTypesTool = AnthropicToolDef(
    name = "list_fixture_types",
    description = "List the fixture types this desk can patch — each a manufacturer, model and DMX mode with its channel count. Match every fixture in a patch list to a typeKey from here by manufacturer, model and mode/channel count; a mode is a separate typeKey. Conventional (dimmer-driven) lanterns — profiles, fresnels, PARs, cyc floods, practicals — all patch as 'generic-dimmer', one channel each. A fixture with no match cannot be patched: tell the operator which ones, since adding a fixture type is a code change.",
    inputSchema = objectSchema {
        prop("query", "string", "Optional case-insensitive filter on manufacturer, model, mode or typeKey.")
    },
)

internal val getPatchTool = AnthropicToolDef(
    name = "get_patch",
    description = "The current project's patch and stage as stored: stage dimensions, stage regions, riggings (with names), DMX universes, every patched fixture (key, name, type, universe/address, groups, rigging and position) and groups. Read it before changing the patch or stage, and after, to check the result.",
    inputSchema = objectSchema {},
)

private val patchRowSchema = objectSchema(required = listOf("name", "fixtureTypeKey", "universe", "startChannel")) {
    prop("key", "string", "Stable identifier, unique in the project, used by every other tool (e.g. 'foh-1', 'lx1-spot-3'). Derived from name when omitted. A key that is already patched updates that fixture in place instead of adding one.")
    prop("name", "string", "Display name, e.g. 'FOH 1' or the source console's channel label.")
    prop("fixtureTypeKey", "string", "From list_fixture_types.")
    prop("universe", "integer", "The desk's DMX universe number. The desk counts from 0, so another console's 'universe 1' is usually 0 here — check get_patch for the universes already set up, and ask the operator if unsure.")
    prop("startChannel", "integer", "DMX start address within the universe, 1–512. An address written '2/101' is universe 2, address 101 on the source console.")
    put("groups", buildJsonObject {
        put("type", "array")
        put("items", buildJsonObject { put("type", "string") })
        put("description", "Group names to add this fixture to (created on demand), e.g. positions like 'FOH' or 'LX1', or roles like 'Front wash'. Groups are how looks, cues and effects address many fixtures at once.")
    })
    placementProps()
}

internal val patchFixturesTool = AnthropicToolDef(
    name = "patch_fixtures",
    description = "Patch fixtures into the current project in one go — the tool for turning a patch list (a PDF or CSV exported from another console, or a paperwork plot) into the desk's patch. " +
        "The whole list is checked first — known types, addresses inside 1–512, no two fixtures overlapping on a universe, unique keys, riggings that exist — and nothing is written if any row fails; the answer lists every problem, so fix them and send the list again. " +
        "Use dryRun first on a big list. Rows whose key is already patched are updated, so re-sending a corrected list is safe. New universes are created with no node address — the operator sets the Art-Net node IPs in the desk's universe settings. " + COORDINATES,
    inputSchema = objectSchema(required = listOf("fixtures")) {
        arrayProp("fixtures", patchRowSchema)
        prop("dryRun", "boolean", "Validate and report what would change without writing anything. Default false.")
    },
)

private val regionSchema = objectSchema(required = listOf("name")) {
    prop("name", "string", "Unique name, e.g. 'Main stage', 'Thrust', 'Rostrum SL'. An existing name updates that region.")
    prop("centerX", "number", "Centre x, metres.")
    prop("centerY", "number", "Centre y, metres (a stage whose downstage edge is at y=0 and depth D has centreY = D/2).")
    prop("centerZ", "number", "Height of the top surface; 0 = deck, >0 = raised, <0 = pit.")
    prop("widthM", "number", "Extent along x.")
    prop("depthM", "number", "Extent along y.")
    prop("heightM", "number", "Platform thickness.")
    prop("yawDeg", "number", "Rotation about its centre.")
}

private val riggingSchema = objectSchema(required = listOf("name")) {
    prop("name", "string", "Unique name as the plot labels it: 'FOH', 'LX1', 'Boom SL 1', 'Floor'. An existing name updates that rigging.")
    enumProp("kind", RIGGING_KINDS)
    prop("x", "number", "World x of the rigging's origin (the middle of a bar or truss), metres.")
    prop("y", "number", "World y, metres. FOH positions are in front of the stage, so negative.")
    prop("z", "number", "Trim height, metres.")
    prop("yawDeg", "number", "Rotation about Z; 0 = running across the stage (along x), 90 = running up/downstage (a side truss or boom arm).")
    prop("pitchDeg", "number")
    prop("rollDeg", "number")
    prop("lengthM", "number", "Length along its own x axis, 0.01–100 m.")
}

internal val setStageTool = AnthropicToolDef(
    name = "set_stage",
    description = "Set up the Stage view of the current project from a ground plan, section, lighting plot or photo: the stage's bounding box, the playable regions (main stage, thrust, rostra, pit) and the riggings fixtures hang from (FOH bar, LX bars, trusses, booms, floor positions). " +
        "Regions and riggings are upserted by name — fields you send overwrite, fields you omit keep their value — so it is safe to call repeatedly while refining. Everything is validated before anything is written. " +
        "Then hang fixtures on the riggings with place_fixtures (or the placement fields of patch_fixtures). " + COORDINATES,
    inputSchema = objectSchema {
        put("stage", objectSchema {
            prop("widthM", "number", "Overall width, metres.")
            prop("depthM", "number", "Overall depth, metres.")
            prop("heightM", "number", "Overall trim height, metres.")
        })
        arrayProp("regions", regionSchema)
        arrayProp("riggings", riggingSchema)
        put("removeRegions", stringArray)
        put("removeRiggings", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
            put("description", "Rigging names to delete. Fixtures hung on them are kept, but lose their rigging (their offsets become world coordinates).")
        })
    },
)

private val placementSchema = objectSchema(required = listOf("key")) {
    prop("key", "string", "Patched fixture key, from get_patch or describe_rig.")
    placementProps()
}

internal val placeFixturesTool = AnthropicToolDef(
    name = "place_fixtures",
    description = "Position patched fixtures in the Stage view: which rigging each hangs on, where along it, its orientation, beam angle and gel. Only the fields you send change; send rigging as null to take a fixture off its rigging. " +
        "The whole list is validated first and nothing is written if any row fails. Placement is presentational — it changes no DMX output. " + COORDINATES,
    inputSchema = objectSchema(required = listOf("placements")) {
        arrayProp("placements", placementSchema)
    },
)

internal val getPromptBookTool = AnthropicToolDef(
    name = "get_prompt_book",
    description = "The current project's prompt book: the script PDF's page count and cover pages, every cue anchored on it (with its stack and number) and every note. If there is no prompt book yet, the answer says how the operator imports the PDF — the desk must hold the file itself, and a tool cannot upload it.",
    inputSchema = objectSchema {},
)

/** Where something sits on the script: one rectangle on one page of the PDF. */
private val scriptPlaceSchema = objectSchema(required = listOf("pdfPage", "y")) {
    prop("pdfPage", "integer", "Page of the PDF file, counting its first page as 1 (covers included; not the number printed on the page).")
    prop("y", "number", "Top of the marked line as a fraction of the page height, 0 = top edge, 1 = bottom edge. Aim at the line the cue is called on.")
    prop("x", "number", "Left edge as a fraction of the page width. Default 0.06.")
    prop("width", "number", "Width as a fraction of the page width. Default 0.88 (the text column).")
    prop("height", "number", "Height as a fraction of the page height. Default 0.03 (about one line).")
}

private val showCueSchema = objectSchema(required = listOf("name")) {
    prop("number", "string", "Cue number as the book calls it: '1', '12.5', '14A', 'S1-3'. Unique in the stack. Omit to have the desk number it from its neighbours.")
    prop("name", "string", "Short name — the stand-by line or the look: 'House to half', 'Dawn'.")
    prop("notes", "string", "The designer's note for the cue: what it looks like, what it's called on, anything the operator needs. This is where lighting notes go when there is no look to layer yet.")
    prop("fadeSeconds", "number", "Crossfade time in seconds. Omit for a snap.")
    prop("fadeCurve", "string", "LINEAR (default), SINE_IN_OUT, CUBIC_IN_OUT, … — an EasingCurve name.")
    prop("followSeconds", "number", "Auto-follow: the stack fires the next cue this many seconds after this one fires.")
    prop("marker", "boolean", "A section divider ('Act 1', 'Interval') rather than a cue: name is its label; GO skips it.")
    arrayProp("layers", cueLayerSchema, "Looks this cue applies, with their targets — the same shape as create_cue's layers.")
    put("at", buildJsonObject {
        put("type", "object")
        put("description", "Where the cue is called in the prompt book; anchors it there. Needs a prompt book (get_prompt_book).")
        put("properties", scriptPlaceSchema["properties"]!!)
        put("required", scriptPlaceSchema["required"]!!)
    })
}

internal val buildCueStackTool = AnthropicToolDef(
    name = "build_cue_stack",
    description = "Create a cue stack for a show — or append to one — with its cues in running order, from a prompt book and the designer's lighting notes. Each cue carries its number, name, notes, fade and follow times, optional look layers, and optionally the place in the prompt book it is called on. " +
        "Cues can be created before their looks exist: put the intended state in notes, then program them later (record_cue with UPDATE_EXISTING, or create_cue-style layers). " +
        "Everything is validated before anything is written, and a numbered cue cannot be added twice, so a retry after an error is safe. Returns the new cue ids.",
    inputSchema = objectSchema(required = listOf("cues")) {
        prop("stackName", "string", "Name for a new stack (e.g. 'Act 1', or the show's name). Required unless stackId is given.")
        prop("stackId", "integer", "Append to this existing stack instead of creating one.")
        prop("loop", "boolean", "New stack only: wrap to the first cue after the last. Default false.")
        arrayProp("cues", showCueSchema, "In running order.")
    },
)

private val scriptNoteSchema = objectSchema(required = listOf("at")) {
    enumProp("kind", listOf("NOTE", "FREETEXT", "STRIKETHROUGH"), "NOTE (default): a callout the operator reads. FREETEXT: text written on the page. STRIKETHROUGH: marks a cut passage (text ignored).")
    enumProp("tone", listOf("NOTE", "WARN", "SAFETY"), "NOTE only: blue (NOTE, default), amber (WARN — a tricky call or a standby), red (SAFETY — pyro, strobe, haze, flying).")
    prop("text", "string")
    put("at", buildJsonObject {
        put("type", "object")
        put("properties", scriptPlaceSchema["properties"]!!)
        put("required", scriptPlaceSchema["required"]!!)
    })
}

private val anchorSchema = objectSchema(required = listOf("cueId", "at")) {
    prop("cueId", "integer")
    put("at", buildJsonObject {
        put("type", "object")
        put("properties", scriptPlaceSchema["properties"]!!)
        put("required", scriptPlaceSchema["required"]!!)
    })
}

internal val markUpPromptBookTool = AnthropicToolDef(
    name = "mark_up_prompt_book",
    description = "Mark up the current project's prompt book: anchor existing cues to the line they are called on (moving any existing anchor), add notes for the operator, and set how many cover pages precede the script's page 1. " +
        "Notes are added, never replaced — check get_prompt_book first so a note is not written twice. Everything is validated before anything is written.",
    inputSchema = objectSchema {
        prop("coverPages", "integer", "PDF pages before the script's printed page 1 (title page, cast list…), so page labels match the script.")
        arrayProp("anchors", anchorSchema)
        arrayProp("notes", scriptNoteSchema)
    },
)

internal val setupToolDefs: List<AnthropicToolDef> = listOf(
    listProjectsTool,
    createProjectTool,
    switchProjectTool,
    listFixtureTypesTool,
    getPatchTool,
    patchFixturesTool,
    setStageTool,
    placeFixturesTool,
    getPromptBookTool,
    buildCueStackTool,
    markUpPromptBookTool,
)

internal val readOnlySetupToolNames: Set<String> =
    setOf(listProjectsTool.name, listFixtureTypesTool.name, getPatchTool.name, getPromptBookTool.name)
