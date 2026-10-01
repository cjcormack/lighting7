package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.lantern.LanternLibrary
import uk.me.cormack.lighting7.models.StageElementKind
import uk.me.cormack.lighting7.models.StageElementLayer
import uk.me.cormack.lighting7.models.StageViewpointKind
import uk.me.cormack.lighting7.models.SurfacePattern

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

/** LEDGE (stage-view plan session 2): stood on rather than hung from — a balcony front, a wall shelf. */
internal val RIGGING_KINDS = listOf("TRUSS", "BAR", "BOOM", "PIPE", "FLOOR_STAND", "LEDGE", "OTHER")

/**
 * The rigging kinds a fixture **stands on** rather than hangs from (stage-view plan session 6):
 * the Stage view draws a body on one base down with no hanger, and `describe_rig` says so and flags
 * a moving head whose base orientation disagrees. It decides only how a body is carried — a moving
 * head's mount is its own `basePitchDeg` (0 stands, 180 hangs), which [aimAt] solves from, so the
 * kind never turns an aim. Mirrored by the Stage view's `bodies/mount.ts`; the two are pinned
 * against `src/test/resources/stage/standingRiggingKinds.json`.
 */
internal val STANDING_RIGGING_KINDS: Set<String> = setOf("FLOOR_STAND", "LEDGE")

/** Whether a rigging of [kind] is stood on. Case-blind, as `set_stage` stores kinds upper-case. */
internal fun standsOn(kind: String?): Boolean = kind != null && kind.uppercase() in STANDING_RIGGING_KINDS

private const val COORDINATES =
    "Stage coordinates are metres, FOH-relative, Z-up: origin = centre of the downstage edge at deck level; " +
        "+x = audience-right (actor's stage left), +y = upstage, +z = up."

/** One framing shutter or barn door. */
private val bladeSchema = objectSchema {
    prop("depth", "number", "0 is out, 1 closes the whole beam: the fraction of the field's diameter it covers, so 0.5 reaches the centre.")
    prop("angleDeg", "number", "The blade's turn about the middle of its edge, −30–30.")
}

/** The library's lanterns, one line each, for the schema text: the id to send and what it is. */
private val LANTERN_CHOICES: String by lazy {
    LanternLibrary.all.joinToString("; ") { l ->
        val field = l.zoom?.let { "zoom ${deg(it.minDeg)}–${deg(it.maxDeg)}°" }
            ?: l.oval?.let { "oval ${deg(it.wideDeg)}×${deg(it.narrowDeg)}°" }
            ?: "${deg(l.fieldDeg)}°"
        "${l.id} (${l.name}, $field)"
    }
}

/** Which lantern a generic dimmer that names none is drawn as, by kind. */
private val LANTERN_DEFAULTS: String by lazy {
    LanternLibrary.all.flatMap { l -> l.defaultFor.map { "$it → ${l.id}" } }.joinToString(", ")
}

private fun deg(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else v.toString()

/**
 * A lantern and its focus (stage-view plan session 7): the same seven fields on a fixture and on
 * each `alsoAt` entry. Only a type that takes a lantern (list_fixture_types marks it
 * acceptsLantern — generic-dimmer) carries them.
 */
private fun JsonObjectBuilder.focusProps(whose: String) {
    prop(
        "lanternType", "string",
        "Which lantern this is, for $whose — only for a fixture type marked acceptsLantern (generic-dimmer). " +
            "One of: $LANTERN_CHOICES. Null draws the library's default for the fixture's kind ($LANTERN_DEFAULTS). " +
            "It sets the drawn body and field, and the fixture's kind follows it.",
    )
    prop("zoomDeg", "number", "The field angle the lantern is zoomed (or a fresnel spot–flooded) to — only within its zoom range above; refused for a fixed lantern. Null returns to its default field.")
    prop("lampRotationDeg", "number", "A PAR lamp's turn in its can, −180–180, which turns its oval beam about the axis; 0 lays the oval's wide axis across the unit.")
    arrayProp(
        "shutters", bladeSchema,
        "Exactly four blades, in the order top, bottom, left, right — named for the edge of the light each cuts as seen from behind the lantern along its beam, not for its place in the gate. " +
            "A profile's shutters; a fresnel's barn doors in the same four slots. Null pulls them all out.",
    )
    prop("gateRotationDeg", "number", "The gate's (or the barn doors') turn about the beam, −180–180, which turns every blade with it.")
    prop("iris", "number", "The iris's open fraction, 0–1 (1 open), for a lantern with an iris.")
    prop("focusSoftness", "number", "The focus knob, 0 sharp to 1 soft. Null is the lantern's own edge.")
}

/** The fields a fixture's physical placement shares between patch_fixtures and place_fixtures. */
private fun JsonObjectBuilder.placementProps() {
    prop("rigging", "string", "Name of the rigging (truss, bar, boom…) it hangs from, as set_stage created it. When set, x/y/z are offsets along that rigging's own frame (x along its length) rather than world coordinates.")
    prop("x", "number", "Metres. World x, or offset along the rigging when `rigging` is set.")
    prop("y", "number", "Metres. World y, or offset in the rigging's frame.")
    prop("z", "number", "Metres. World z (height above deck), or offset in the rigging's frame (usually 0 or slightly negative for a hung fixture).")
    prop("yawDeg", "number", "Body rotation about Z. 0 = pointing at the audience (−y); 180 = pointing upstage (a backlight); +yaw turns toward audience-right.")
    prop("pitchDeg", "number", "Body rotation about X. 0 = horizontal, +pitch aims down. A typical FOH or overhead unit is 30–60.")
    prop("rollDeg", "number", "Body roll, −180–180: tips the body sideways, lifting its own x (length) axis toward vertical; applied before pitch and yaw. A long body (a lightstrip, a bar) runs along its own x: pitch turns it about that length and yaw swings it round, so neither lifts it off level — roll 90 stands it on end (a strip running up a wall, a ring's upright side). For a moving head it lays the unit on its side; it is not a spin about the beam.")
    prop("beamAngleDeg", "integer", "Beam angle 2–120, for fixture types that accept one (profiles, generic dimmers).")
    prop("gelCode", "string", "Gel as the plot writes it, e.g. 'L201', 'R80'.")
    enumProp("kind", FixtureKind.entries.map { it.name }, "Override the drawn fixture kind. For a generic dimmer name its lanternType instead: the kind is derived from the lantern, and a different kind beside one is refused.")
    prop("lengthM", "number", "Installed length in metres along the unit's long axis (0.01–100), only for fixture types that take one (list_fixture_types marks them acceptsLength — a lightstrip, cut to its run). Refused for every other type; null returns to the type's default. A run laid round several sides (a ring round the stage edge) is one fixture: its own placement is one side, and each other side is an `alsoAt` entry with its own lengthM.")
    prop("stageHidden", "boolean", "Hide from the Stage view (a patch that is DMX but not a stage object: a dimmer on hard power, a hazer's fan).")
    focusProps("the fixture's own lantern")
    arrayProp(
        "alsoAt",
        alsoAtSchema,
        "Other places this same fixture hangs — a paired (or ganged) dimmer driving several lanterns from one address, e.g. an SL and an SR unit on one bar. " +
            "Patch the circuit once and list its other lanterns here; never patch a second fixture at the same address. " +
            "Each is drawn in the Stage view, lit from the fixture's one channel. The list replaces the fixture's existing ones; [] or null removes them. At most 16.",
    )
}

/** One of a fixture's other placements (`alsoAt`) — the geometry fields of a placement plus a label. */
private val alsoAtSchema = objectSchema {
    prop("label", "string", "Short name for this lantern on the plot, e.g. 'SR'. At most 40 characters.")
    prop("rigging", "string", "Name of the rigging it hangs from; x/y/z are then offsets along that rigging's frame.")
    prop("x", "number", "Metres. World x, or offset along the rigging when `rigging` is set.")
    prop("y", "number", "Metres. World y, or offset in the rigging's frame.")
    prop("z", "number", "Metres. World z, or offset in the rigging's frame.")
    prop("yawDeg", "number", "Body rotation about Z, as for the fixture.")
    prop("pitchDeg", "number", "Body rotation about X, as for the fixture.")
    prop("rollDeg", "number", "Body roll, as for the fixture — 90 stands this segment on end.")
    prop("lengthM", "number", "This segment's length in metres, for a fixture type that takes one (acceptsLength); absent takes the fixture's own lengthM.")
    focusProps("this lantern — a pair on one dimmer are two lanterns, each focused separately")
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
    description = "List the fixture types this desk can patch — each a manufacturer, model and DMX mode with its channel count. Match every fixture in a patch list to a typeKey from here by manufacturer, model and mode/channel count; a mode is a separate typeKey. Conventional (dimmer-driven) lanterns — profiles, fresnels, PARs, cyc floods, practicals — all patch as 'generic-dimmer', one channel each. A fixture with no match cannot be patched: tell the operator which ones, since adding a fixture type is a code change. A type marked acceptsLength (a lightstrip) has no fixed size: give each such fixture its installed lengthM when patching or placing it; defaultLengthM is only what is drawn until then. A type marked acceptsLantern (generic-dimmer) is hung with a lantern from the desk's library: give each its lanternType when the plot names one.",
    inputSchema = objectSchema {
        prop("query", "string", "Optional case-insensitive filter on manufacturer, model, mode or typeKey.")
    },
)

internal val getPatchTool = AnthropicToolDef(
    name = "get_patch",
    description = "The current project's patch and stage as stored: stage dimensions, stage regions, riggings (with names), DMX universes, every patched fixture (key, name, head number where set, type, universe/address, groups, rigging and position — and, for a paired dimmer, `alsoAt`: the other lanterns it drives, each with its label, rigging and position) and groups. " +
        "Each placed fixture and `alsoAt` lantern also carries `world`: where it actually is on stage, in world coordinates — x/y/z are offsets along the rigging when `rigging` is set, and `world` composes them with the rigging's position and rotation. It is absent for one with no x or y, which the Stage view does not draw. " +
        "Read it before changing the patch or stage, and after, to check the result. " + COORDINATES,
    inputSchema = objectSchema {},
)

private val patchRowSchema = objectSchema(required = listOf("name", "fixtureTypeKey", "universe", "startChannel")) {
    prop("key", "string", "Stable identifier, unique in the project, used by every other tool (e.g. 'foh-1', 'lx1-spot-3'). Derived from name when omitted. A key that is already patched updates that fixture in place instead of adding one.")
    prop("name", "string", "Display name, e.g. 'FOH 1' or the source console's channel label.")
    prop("headNumber", "integer", "The operator's number for this head, 1–99999, unique in the project — what the source console calls a head number (ChamSys MagicQ), fixture number or channel number (ETC Eos). Carry it across from a migrated patch list so the operator can keep calling fixtures by it. Omit to leave an existing fixture's number as it is; null removes it.")
    prop("fixtureTypeKey", "string", "From list_fixture_types.")
    prop("universe", "integer", "The desk's DMX universe number. The desk counts from 0, so another console's 'universe 1' is usually 0 here — check get_patch for the universes already set up, and ask the operator if unsure.")
    prop("startChannel", "integer", "DMX start address within the universe, 1–512. An address written '2/101' is universe 2, address 101 on the source console.")
    put("groups", buildJsonObject {
        put("type", "array")
        put("items", buildJsonObject { put("type", "string") })
        put("description", "Group names to add this fixture to (created on demand), e.g. positions like 'FOH' or 'LX1', or roles like 'Front wash'. Groups are how looks, cues and effects address many fixtures at once.")
    })
    prop("infrastructure", "boolean", "Patched but not a lighting fixture — a dimmer channel switching hard power, a relay, a hazer's fan. Hidden from every desk view but Patches and Channels, and never offered as a target; don't put it in groups. Omit to leave an existing fixture's flag as it is.")
    placementProps()
}

internal val patchFixturesTool = AnthropicToolDef(
    name = "patch_fixtures",
    description = "Patch fixtures into the current project in one go — the tool for turning a patch list (a PDF or CSV exported from another console, or a paperwork plot) into the desk's patch. " +
        "The whole list is checked first — known types, addresses inside 1–512, no two fixtures overlapping on a universe, unique keys and head numbers, riggings that exist — and nothing is written if any row fails; the answer lists every problem, so fix them and send the list again. " +
        "Use dryRun first on a big list. Rows whose key is already patched are updated, so re-sending a corrected list is safe. New universes are created with no node address — the operator sets the Art-Net node IPs in the desk's universe settings. " + COORDINATES,
    inputSchema = objectSchema(required = listOf("fixtures")) {
        arrayProp("fixtures", patchRowSchema)
        prop("dryRun", "boolean", "Validate and report what would change without writing anything. Default false.")
    },
)

internal val deleteGroupsTool = AnthropicToolDef(
    name = "delete_groups",
    description = "Delete fixture groups from the current project by name — groups left over from an old patch, or ones patch_fixtures created under a wrong name. " +
        "The fixtures stay patched; they only leave the group. A group that still has members is refused unless force is set, so a typo cannot take apart a group that looks and cues address — check get_patch's `groups` first. " +
        "Everything is validated before anything is deleted.",
    inputSchema = objectSchema(required = listOf("names")) {
        put("names", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
            put("description", "Group names exactly as get_patch lists them.")
        })
        prop("force", "boolean", "Also delete groups that still have members. Default false.")
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

private val pointSchema = objectSchema(required = listOf("x", "y", "z")) {
    prop("x", "number")
    prop("y", "number")
    prop("z", "number")
}

private val sceneElementSchema = objectSchema(required = listOf("name")) {
    prop("name", "string", "Unique name: 'Hall', 'Proscenium', 'Stalls', 'SR wall', 'Sofa'. An existing name updates that element — fields you send overwrite, fields you omit keep.")
    enumProp("kind", StageElementKind.entries.map { it.name }, "Required for a new element. ROOM: an inward-facing shell. PROSCENIUM: a wall with an opening. FLAT: a panel with doors and windows. DRAPE: soft goods. PLATFORM: a deck, rostrum or balcony. SEATING: rows of seats (viewpoints sit in them). OBJECT: furniture, plants, signs, a flown piece.")
    enumProp("layer", StageElementLayer.entries.map { it.name }, "VENUE outlives a production (the hall, the proscenium, the seating); SET is this show's. Default VENUE.")
    prop("x", "number", "Metres. The element's origin: the centre of its footprint (for SEATING, the centre of the first row).")
    prop("y", "number", "Metres.")
    prop("z", "number", "Metres. The element's base — except a PLATFORM, whose z is its top surface (the deck hangs down from it), as a region's centerZ is. A hall floor below a raised stage is negative.")
    prop("yawDeg", "number", "Rotation about Z at the origin, −360–360; +yaw turns anticlockwise seen from above (0 = square to the audience).")
    prop("widthM", "number", "Size along the element's own x. Every kind but SEATING needs all three sizes; SEATING's comes from its rows and seats.")
    prop("depthM", "number", "Size along its own y.")
    prop("heightM", "number", "Size along z. A PLATFORM's thickness below its top.")
    put("finish", objectSchema {
        prop("colour", "string", "#rrggbb.")
        enumProp("pattern", SurfacePattern.entries.map { it.name })
        prop("emissive", "boolean", "Glows by itself (an exit sign, a lamp shade).")
    })
    put("params", buildJsonObject {
        put("type", "object")
        put(
            "description",
            "Per kind, replaced whole when sent. ROOM: {omit: [DOWNSTAGE|UPSTAGE|STAGE_LEFT|STAGE_RIGHT|FLOOR|CEILING], floor: {colour, pattern}, ceiling: {colour, pattern}}. " +
                "PROSCENIUM: {openingWidthM, openingHeightM, openingSillM, surroundM}. " +
                "FLAT: {openings: [{kind: DOOR|WINDOW|FRENCH_WINDOW|ARCH, fromM (from the stage-right end), widthM, heightM, sillM}]}. " +
                "DRAPE: {role: LEG|BORDER|TABS|CYC|BACKCLOTH, operation: DEAD|DRAW|FLY}. " +
                "PLATFORM: {railHeightM and railEdge together, region: a stage region's name when the platform is that region's deck}. " +
                "SEATING: {rows (≤26), seatsPerRow, rowPitchM, seatPitchM, firstRow ('A'), rakeM (rise per row)} — rows run away from the stage (−y), seat 1 at the stage-right end. " +
                "OBJECT: {shape: BOX|CYLINDER|SHADE|DISC, flies}. " +
                "Any kind: states: {visible}, a DRAW drape's {open: 0–1}, a flown piece's {trimM}.",
        )
    })
    prop("hidden", "boolean", "Stored but not drawn.")
}

private val sceneViewpointSchema = objectSchema(required = listOf("name")) {
    prop("name", "string", "Unique name as the operator would say it: 'Balcony · desk', 'Row F centre', 'Centre stage'. An existing name updates that viewpoint.")
    enumProp("kind", StageViewpointKind.entries.map { it.name }, "Required for a new viewpoint. ORBIT: the turntable camera placed at eye, circling target. EYE: a person standing at eye, looking at target. SEAT: sitting in a seat — the eye is the seat's, at seated height.")
    put("eye", pointSchema)
    put("target", pointSchema)
    prop("fovDeg", "number", "Lens, 15–90. EYE and SEAT only; default 50 standing, 52 seated.")
    prop("seating", "string", "SEAT: the seating element's name. May be omitted when the scene has one.")
    prop("seat", "string", "SEAT: row letter and number, e.g. 'F6'.")
}

internal const val PROSCENIUM_HALL_TEMPLATE = "proscenium-hall"

internal val setSceneTool = AnthropicToolDef(
    name = "set_scene",
    description = "Model the venue and the set for the Stage view — the room, the proscenium, masking, platforms, seating, furniture — from photos, a ground plan or a video frame, and save viewpoints (a seat, the desk's position, an actor's eye line). " +
        "Elements and viewpoints are upserted by name, and everything is validated before anything is written. " +
        "Start a hall with template '$PROSCENIUM_HALL_TEMPLATE' and its templateParams, which expand into named elements (Hall, Stage house, Main stage, Proscenium, Stalls, Balcony) you then correct with elements rows of the same names; the Stage view draws them, and get_scene reads the document back. " +
        "This is presentational: it changes no DMX output. Regions and riggings stay set_stage's. " + COORDINATES,
    inputSchema = objectSchema {
        enumProp("template", listOf(PROSCENIUM_HALL_TEMPLATE))
        put("templateParams", objectSchema {
            prop("hallWidthM", "number", "Required. Wall to wall.")
            prop("hallDepthM", "number", "Required. From the stage edge to the back wall.")
            prop("hallHeightM", "number", "Required. Floor to ceiling.")
            prop("stageWidthM", "number", "Required. The deck's width.")
            prop("stageDepthM", "number", "Required. From the stage edge to the back wall of the stage.")
            prop("prosWidthM", "number", "Required. The proscenium opening's width.")
            prop("prosHeightM", "number", "Required. The opening's height above the deck.")
            prop("deckHeightM", "number", "The deck above the hall floor; the opening's sill. Default 0.")
            prop("apronM", "number", "The proscenium wall's distance upstage of the edge. Default 0.3.")
            prop("stageHouseHeightM", "number", "Default: the hall's height above the deck.")
            prop("rows", "integer", "Rows of stalls seating, 0–26. Default 0 (none).")
            prop("seatsPerRow", "integer", "Required with rows.")
            prop("rowPitchM", "number", "Default 0.9.")
            prop("seatPitchM", "number", "Default 0.5.")
            prop("firstRowM", "number", "Row A's distance from the stage edge. Default 2.")
            prop("balconyDepthM", "number", "A balcony across the back wall; 0 (the default) for none.")
            prop("balconyHeightM", "number", "The balcony's floor above the hall floor. Default 2.5.")
            prop("railHeightM", "number", "The balcony's front rail. Default 1.")
        })
        arrayProp("elements", sceneElementSchema)
        arrayProp("viewpoints", sceneViewpointSchema)
        put("removeElements", stringArray)
        put("removeViewpoints", stringArray)
        prop("dryRun", "boolean", "Validate and report without writing. Default false.")
    },
)

internal val getSceneTool = AnthropicToolDef(
    name = "get_scene",
    description = "Read the current project's scene document: every venue and set element (in set_scene's shape, so a row can be corrected and sent back) and every saved viewpoint, a seat view with the eye it resolves to. " + COORDINATES,
    inputSchema = objectSchema {},
)

internal const val RENDER_DEFAULT_WIDTH = 1280
internal const val RENDER_DEFAULT_HEIGHT = 720
internal const val RENDER_MIN_SIDE = 160
internal const val RENDER_MAX_SIDE = 1920
/**
 * 1920 × 1080: a long side of 1920, but not a 1920 square. The frame a window may upload is capped
 * at 4 MB (`StageRenderService.MAX_RENDER_BYTES`), and a stage render — dark, mostly flat — is a few
 * hundred KB at 1280 × 720; the pixel cap keeps even a hazy frame well inside the byte cap.
 */
internal const val RENDER_MAX_PIXELS = 1920 * 1080

/** Declared before [renderViewTool], which reads it as it initialises. The Stage view's vis sources (`hooks/useVisSource.ts` in the frontend), in its order. */
internal val RENDER_SOURCES = listOf("output", "outputProgrammer", "programmer", "nextGo")

/**
 * `render_view` (stage-view plan session 4, D4): what a desk window draws for a viewpoint, as a PNG.
 * The viewpoint is `set_scene`'s vocabulary — a built-in camera, a saved view by name (or uuid), or
 * a seat `{seating, seat}` — so a view saved there, or a seat read off `get_scene`, renders as named.
 */
internal val renderViewTool = AnthropicToolDef(
    name = "render_view",
    description = "See the Stage view: render a viewpoint of the current project's stage — the venue and set set_scene built, the rig, and the light the fixtures are putting out now — and answer it as a PNG image. " +
        "Use it to check a model against the operator's photo or video frame and correct it with set_scene. " +
        "A signed-in desk window draws it offscreen (any window on the desk's own network, whatever view it is on; nothing on its screen changes), so it needs one open: with none it answers RENDER_NO_WINDOW, and asking the operator to open the desk in a browser is the fix. " +
        "It draws what a fresh Stage window shows on that viewpoint: every scene layer, haze on, and no labels. orbit and eye are the default views a fresh window opens on; plan, front and side are sections. " +
        "Read-only: it changes no DMX, no programmer value and no window's view. Errors are named: RENDER_NO_WINDOW, RENDER_BUSY, RENDER_TIMEOUT, RENDER_WINDOW_CLOSED, RENDER_UNKNOWN_VIEWPOINT, RENDER_UNKNOWN_SEAT, RENDER_FAILED, RENDER_INVALID_REQUEST.",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("viewpoint", buildJsonObject {
                put("description", "Where to look from. A string: a built-in camera ('orbit', 'eye', 'plan', 'front', 'side'), or a saved viewpoint's name (get_scene lists them) or uuid. Or an object {seating, seat}: a seat, e.g. {\"seating\": \"Stalls\", \"seat\": \"F6\"} — seating may be left out when the scene has one.")
                put("anyOf", buildJsonArray {
                    add(buildJsonObject { put("type", "string") })
                    add(objectSchema(required = listOf("seat")) {
                        prop("seating", "string", "The seating element's name, as set_scene named it.")
                        prop("seat", "string", "Row letter and seat number, e.g. 'F6'. Seat 1 is at the stage-right end of its row.")
                    })
                })
            })
            prop("width", "integer", "Pixels, ${RENDER_MIN_SIDE}–${RENDER_MAX_SIDE}, at most $RENDER_MAX_PIXELS pixels in all (1920 × 1080). Default ${RENDER_DEFAULT_WIDTH}; with only height given, 16:9 to it.")
            prop("height", "integer", "Pixels, ${RENDER_MIN_SIDE}–${RENDER_MAX_SIDE}, at most $RENDER_MAX_PIXELS pixels in all. Default ${RENDER_DEFAULT_HEIGHT}; with only width given, 16:9 to it.")
            enumProp("source", RENDER_SOURCES, "Which light to draw: 'output' (default) is what the desk is transmitting; 'outputProgrammer' lays the programmer over it (differs only in Blind); 'programmer' is the programmer alone; 'nextGo' the look the next GO would produce.")
        })
        put("required", buildJsonArray { add("viewpoint") })
    },
)

private val placementSchema = objectSchema(required = listOf("key")) {
    prop("key", "string", "Patched fixture key, from get_patch or describe_rig.")
    placementProps()
}

internal val placeFixturesTool = AnthropicToolDef(
    name = "place_fixtures",
    description = "Position patched fixtures in the Stage view: which rigging each hangs on, where along it, its orientation, beam angle and gel — and for a generic dimmer its lantern and how it is focused (shutters, gate, iris, focus, zoom). Only the fields you send change; send rigging as null to take a fixture off its rigging. " +
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
    arrayProp("scenery", sceneryItemSchema(forCue = true), "Scene elements this cue moves on GO — the same shape as create_cue's scenery. $SCENERY_TRACKS_NOTE")
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
        arrayProp("scenery", sceneryItemSchema(forCue = false), "The stack's set: the states its elements hold while it is live, under its cues ('the Act 2 set appears with Act 2'). Replaces the stack's set when given.")
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
    deleteGroupsTool,
    setStageTool,
    setSceneTool,
    getSceneTool,
    renderViewTool,
    placeFixturesTool,
    getPromptBookTool,
    buildCueStackTool,
    markUpPromptBookTool,
)

internal val readOnlySetupToolNames: Set<String> =
    setOf(
        listProjectsTool.name,
        listFixtureTypesTool.name,
        getPatchTool.name,
        getPromptBookTool.name,
        getSceneTool.name,
        renderViewTool.name,
    )
