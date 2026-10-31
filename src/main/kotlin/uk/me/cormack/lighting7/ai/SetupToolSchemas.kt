package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.lantern.LanternLibrary
import uk.me.cormack.lighting7.fixture.media.FittedMedia
import uk.me.cormack.lighting7.fixture.media.GelLibrary
import uk.me.cormack.lighting7.models.FixtureColours
import uk.me.cormack.lighting7.models.PatchFacet
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
// tool here takes the structured result. One tool takes file bytes: `upload_scene_image`, an image
// to paint on a cloth (scrim plan D11), which a model may have made itself. The prompt-book PDF
// still comes in only through the desk's own Prompt Book import, which hashes it and counts its
// pages. See `docs/mcp-engineering.md` §"Show-setup tools".

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

private fun JsonObjectBuilder.stringListProp(name: String, description: String) {
    put(name, buildJsonObject {
        put("type", "array")
        put("items", buildJsonObject { put("type", "string") })
        put("description", description)
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

/** The gel library's codes, for the schema text — what a fitted gel may name. */
private val GEL_CHOICES: String by lazy { GelLibrary.all.joinToString(", ") { "${it.code} ${it.name}" } }

/**
 * Fitted media (fixture optics plan session 3): what is loaded in a unit's loadable settings — the
 * same object on a fixture and on each `alsoAt` entry, which layers over the fixture's per slot.
 */
private fun JsonObjectBuilder.mediaProp(whose: String) {
    prop(
        "media", "object",
        "What is loaded in $whose's loadable settings — only for a fixture type list_fixture_types lists with `loadable` (a gel scroller, a module wheel, a media frame). " +
            "Shape: {\"slots\": {<setting>: {<slot option>: {\"gel\": <code>} or {\"gobo\": <pattern>} or {} for an empty slot}}}. " +
            "Name only the slots that differ from the type's stock; every other keeps its stock content. A setting that takes GEL takes gels, GOBO gobos, GOBO_OR_GEL either. " +
            "Gels: $GEL_CHOICES. Gobos: ${FittedMedia.GOBO_NAMES.joinToString()}. " +
            "The object replaces the stored media; null clears it. Every problem is reported at once.",
    )
}

/** What every tool that takes a `colour` says it is. */
private val COLOUR_DESCRIPTION =
    "The fixture's colour on the desk, ChamSys-style: one of ${FixtureColours.KEYS.joinToString()}, or null for none. " +
        "Identity only — it marks the name in lists, tiles and labels and never changes output. " +
        "${FixtureColours.RESERVED.keys.joinToString()} are reserved and refused, as is anything else (COLOUR_UNKNOWN)."

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
    prop("lampWatts", "integer", "The lamp's power in watts (1–10000), only for a fixture type hung with a lantern (generic-dimmer): how bright the Stage view draws it, with the lantern's lamp kind. Null takes the lantern's. One lamp for every lantern the fixture drives.")
    prop("lampKelvin", "integer", "The lamp's colour temperature at full in kelvin (1000–10000), only for a fixture type hung with a lantern: 3200 is a theatre tungsten lamp, 2700–3000 a warm house lamp. A gel filters it, and a tungsten lamp warms as it is dimmed. Null takes the lantern's.")
    enumProp("kind", FixtureKind.entries.map { it.name }, "Override the drawn fixture kind. For a generic dimmer name its lanternType instead: the kind is derived from the lantern, and a different kind beside one is refused.")
    prop("lengthM", "number", "Installed length in metres along the unit's long axis (0.01–100), only for fixture types that take one (list_fixture_types marks them acceptsLength — a lightstrip, cut to its run). Refused for every other type; null returns to the type's default. A run laid round several sides (a ring round the stage edge) is one fixture: its own placement is one side, and each other side is an `alsoAt` entry with its own lengthM.")
    prop("stageHidden", "boolean", "Hide from the Stage view (a patch that is DMX but not a stage object: a dimmer on hard power, a hazer's fan).")
    prop("colour", "string", COLOUR_DESCRIPTION + " Placing a venue fixture in a show, it is this show's recolour of it, and null takes the venue's back.")
    focusProps("the fixture's own lantern")
    mediaProp("the fixture")
    put("locate", buildJsonObject {
        put("type", "array")
        put(
            "description",
            "Where Locate points this moving fixture (its stored locate, as get_patch reports it): one entry per unit that pans and tilts — " +
                "`head` left out for the fixture's own pan and tilt, else the head's index from 0 — with `pan` and `tilt` as DMX bytes 0–255 " +
                "(128 is the centre of travel) and `panFine` / `tiltFine` where the unit has fine channels. Locate still opens the beam white at full; " +
                "only pan and tilt move. The list replaces the stored one; null or [] clears it, so Locate goes back to its default: the centre of travel, or the pan front its type declares. " +
                "Refused on a unit that does not pan and tilt. For a venue fixture in a show it is part of the Focus facet, so it needs Focus to be this show's first.",
        )
        put("items", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("head", buildJsonObject { put("type", "integer") })
                put("pan", buildJsonObject { put("type", "integer") })
                put("tilt", buildJsonObject { put("type", "integer") })
                put("panFine", buildJsonObject { put("type", "integer") })
                put("tiltFine", buildJsonObject { put("type", "integer") })
            })
            putJsonArray("required") { add("pan"); add("tilt") }
        })
    })
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
    mediaProp("this unit (its own, layered over the fixture's slot by slot)")
}


internal val listProjectsTool = ToolDef(
    name = "list_projects",
    description = "List the desk's projects (id, name, description, which one is current). A project is one show — its patch, stage, looks, cues and prompt book — or a VENUE (kind), a hall and its house rig that shows are built on. " +
        "A show on a venue carries `venue`: which venue, the version it is built against, the venue's installed version, and `behind` (DRAWING or OUTPUT) when the venue has published changes the show has not taken. " +
        "Tools act on the current project unless given a projectId.",
    inputSchema = objectSchema {},
)

internal val createProjectTool = ToolDef(
    name = "create_project",
    description = "Create a new project (a show). Given venueId, the show is built on that venue: it reads the venue's room, scene, viewpoints and rig at the venue's installed version, and starts with no set, cues or prompt book of its own. " +
        "It is not current until switched to; build it without switching by passing its projectId to the setup tools (or copy_to_project), and switch_project only when the desk is free. Stage dimensions can also be set later with set_stage.",
    inputSchema = objectSchema(required = listOf("name")) {
        prop("name", "string", "Project name, unique on the desk, at most 50 characters.")
        prop("description", "string", "Optional, at most 255 characters.")
        prop("venueId", "integer", "Build the show on this venue (list_venues). The venue must have published a version. The stage size is then the venue's, so leave the stage fields out.")
        prop("seatingPreset", "string", "With venueId: the name of one of the venue's seating presets (list_venues) to copy in as this show's own seating. Fixed seating comes with the venue regardless.")
        prop("stageWidthM", "number", "Stage width along x, metres.")
        prop("stageDepthM", "number", "Stage depth along y, metres.")
        prop("stageHeightM", "number", "Trim height along z, metres.")
        prop("switchTo", "boolean", "Also make it the current project (see switch_project for what that does to the live output). Default false.")
    },
)

internal val switchProjectTool = ToolDef(
    name = "switch_project",
    description = "Make another project the current one. This stops the running show: every FX and cue stops and all DMX output goes to zero before the new project's patch loads, except channels the new project parks, which hold their level into its park. Choosing the project that is already current is the same clean restart. Only do it when the operator has said the desk is not in use for a show, or asked for the switch. Call describe_rig afterwards.",
    inputSchema = objectSchema(required = listOf("projectId")) {
        prop("projectId", "integer", "From list_projects.")
    },
)

internal val listFixtureTypesTool = ToolDef(
    name = "list_fixture_types",
    description = "List the fixture types this desk can patch — each a manufacturer, model and DMX mode with its channel count. Match every fixture in a patch list to a typeKey from here by manufacturer, model and mode/channel count; a mode is a separate typeKey. Conventional (dimmer-driven) lanterns — profiles, fresnels, PARs, cyc floods, practicals — all patch as 'generic-dimmer', one channel each. A fixture with no match cannot be patched: tell the operator which ones, since adding a fixture type is a code change. A type marked acceptsLength (a lightstrip) has no fixed size: give each such fixture its installed lengthM when patching or placing it; defaultLengthM is only what is drawn until then. A type marked acceptsLantern (generic-dimmer) is hung with a lantern from the desk's library: give each its lanternType when the plot names one. A type listed with `loadable` has settings whose slots hold fitted media — a gel scroller's string, a module wheel's gobos or dichroics, a media frame's gel — each listed with its slots and their stock content: give a unit its `media` when the plot names what is loaded.",
    inputSchema = objectSchema {
        prop("query", "string", "Optional case-insensitive filter on manufacturer, model, mode or typeKey.")
        prop("attributes", "boolean", "Also list each type's settable attributes — name, kind (slider, colour, position, setting), category, and a setting's option names — and, for a multi-head type, the attributes of each element (cellAttributes). These are the names create_look's values take. An element attribute the type has no fixture-level attribute for is set on every element by naming the fixture, or on one element by the element's own key (describe_rig lists each fixture's elements). Long: narrow it with query.")
    },
)

internal val getPatchTool = ToolDef(
    name = "get_patch",
    description = "A project's patch and stage as stored (the current one unless projectId names another): stage dimensions, stage regions, riggings (with names), DMX universes, every patched fixture (key, name, head number where set, type, universe/address, groups, rigging and position — and, for a paired dimmer, `alsoAt`: the other lanterns it drives, each with its label, rigging and position) and groups. " +
        "A moving fixture with a stored locate (set on the desk with *Set locate from current*, or with patch_fixtures / place_fixtures) carries `locate`: per unit — `head` absent for the fixture's own pan and tilt, else the head's index from 0 — the pan and tilt Locate points it at, as DMX bytes (`pan`, `tilt`, and `panFine` / `tiltFine` where it has fine channels); a unit with none locates to the centre of its travel, or to the pan front its type declares. " +
        "Each placed fixture and `alsoAt` lantern also carries `world`: where it actually is on stage, in world coordinates — x/y/z are offsets along the rigging when `rigging` is set, and `world` composes them with the rigging's position and rotation. It is absent for one with no x or y, which the Stage view does not draw. " +
        "Read it before changing the patch or stage, and after, to check the result. " + COORDINATES,
    inputSchema = objectSchema {
        prop("projectId", "integer", PROJECT_ID_DESCRIPTION)
    },
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
        put("description", "Group names to add this fixture to (created on demand), e.g. positions like 'FOH' or 'LX1', or roles like 'Front wash'. Groups are how looks, cues and FX address many fixtures at once.")
    })
    prop("infrastructure", "boolean", "Patched but not a lighting fixture — a dimmer channel switching hard power, a relay, a hazer's fan. Hidden from every desk view but Patches and Channels, and never offered as a target; don't put it in groups. Omit to leave an existing fixture's flag as it is.")
    placementProps()
}

internal val patchFixturesTool = ToolDef(
    name = "patch_fixtures",
    description = "Patch fixtures into a project (the current one unless projectId names another) in one go — the tool for turning a patch list (a PDF or CSV exported from another console, or a paperwork plot) into the desk's patch. " +
        "The whole list is checked first — known types, addresses inside 1–512, no two fixtures overlapping on a universe, unique keys and head numbers, riggings that exist — and nothing is written if any row fails; the answer lists every problem, so fix them and send the list again. " +
        "Use dryRun first on a big list. Rows whose key is already patched are updated, so re-sending a corrected list is safe. New universes are created with no node address — the operator sets the Art-Net node IPs in the desk's universe settings. " + COORDINATES,
    inputSchema = objectSchema(required = listOf("fixtures")) {
        arrayProp("fixtures", patchRowSchema)
        prop("dryRun", "boolean", "Validate and report what would change without writing anything. Default false.")
        prop("projectId", "integer", PROJECT_ID_DESCRIPTION)
    },
)

internal val deleteGroupsTool = ToolDef(
    name = "delete_groups",
    description = "Delete fixture groups from a project (the current one unless projectId names another) by name — groups left over from an old patch, or ones patch_fixtures created under a wrong name. " +
        "The fixtures stay patched; they only leave the group. A group that still has members is refused unless force is set, so a typo cannot take apart a group that looks and cues address — check get_patch's `groups` first. " +
        "Everything is validated before anything is deleted.",
    inputSchema = objectSchema(required = listOf("names")) {
        put("names", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
            put("description", "Group names exactly as get_patch lists them.")
        })
        prop("force", "boolean", "Also delete groups that still have members. Default false.")
        prop("projectId", "integer", PROJECT_ID_DESCRIPTION)
    },
)

/** What a patch edit leaves naming a key, said once for every tool that reports it. */
private const val NAMED_BY_NOTE =
    "The answer's `namedBy` lists every cue, Look, palette, surface binding and script that names a removed key or group. " +
        "Nothing is refused for it: those rows stay and show as a missing fixture, as a fixture deleted on the desk does."

private val universeRefSchema = objectSchema(required = listOf("universe")) {
    prop("universe", "integer", "The universe number as get_patch lists it.")
    prop("subnet", "integer", "Default 0.")
}

internal val unpatchFixturesTool = ToolDef(
    name = "unpatch_fixtures",
    description = "Remove fixtures from a project's patch (the current one unless projectId names another) by key, and optionally remove whole universes with every fixture patched on them. " +
        "A removed fixture leaves its groups, its busk rig tiles, its extra lantern placements and its cue events; the groups themselves stay (delete_groups removes them). " +
        "On a show on a venue only the show's own fixtures and universes can be removed: a venue fixture (owner=venue) is hidden in this show with override_venue_fixtures instead. " +
        "Everything is checked first and nothing is removed if anything fails; dryRun answers what would go. $NAMED_BY_NOTE",
    inputSchema = objectSchema {
        stringListProp("keys", "Fixture keys exactly as get_patch lists them.")
        arrayProp("universes", universeRefSchema)
        prop("dryRun", "boolean", "Answer what would be removed and remove nothing. Default false.")
        prop("projectId", "integer", PROJECT_ID_DESCRIPTION)
    },
)

private val renameRowSchema = objectSchema(required = listOf("key", "newKey")) {
    prop("key", "string", "The fixture's key now, from get_patch.")
    prop("newKey", "string", "Its new key: unique in the project, at most 100 characters, no '.' (a fixture's elements are named <key>.<element>).")
}

internal val renameFixturesTool = ToolDef(
    name = "rename_fixtures",
    description = "Change patched fixtures' keys in a project (the current one unless projectId names another) — every other tool names a fixture by its key, so this is how a key typed wrong at patch time is put right. " +
        "patch_fixtures cannot do it: there the key is the fixture's identity, and a new key patches a new fixture. " +
        "The project's cues, Looks, palettes, surface bindings and busk rig tiles that name the old key (or one of its elements) are rewritten to the new one; scripts are Kotlin source and are not — the answer's `scriptsNotRewritten` names any that mention an old key. " +
        "Keys can be swapped in one call. On a show on a venue only the show's own fixtures can be renamed, and a new key may not be a venue fixture's. " +
        "On the current project a cue or Look already running is recomposed under the new key; the programmer's own values and FX already running are not, so clear and re-apply them. " +
        "Everything is checked first and nothing is written if anything fails; dryRun answers what would change.",
    inputSchema = objectSchema(required = listOf("fixtures")) {
        arrayProp("fixtures", renameRowSchema)
        prop("dryRun", "boolean", "Answer what would change and write nothing. Default false.")
        prop("projectId", "integer", PROJECT_ID_DESCRIPTION)
    },
)

private val groupEditSchema = objectSchema(required = listOf("name")) {
    prop("name", "string", "The group's name now, as get_patch lists it. A name the project has no group by creates one, when the row sends members or add.")
    prop(
        "newName", "string",
        "Rename the group: unique in the project, at most 100 characters. The project's cues, Looks and surface bindings that name it are rewritten to the new name; " +
            "scripts are not — the answer's `scriptsNotRewritten` names any that mention the old name.",
    )
    stringListProp(
        "members",
        "The group's whole membership, in order — fixture keys from get_patch. Members not listed leave the group; the fixtures stay patched. " +
            "Order is how a group's FX and fans run across it. Not with add or remove. " +
            "On a show on a venue, a venue group takes only its own fixtures in another order: this show's order of it.",
    )
    stringListProp("add", "Fixture keys to append to the group, in order. One already in it is left where it is.")
    stringListProp("remove", "Fixture keys to take out of the group. The fixtures stay patched.")
    prop(
        "colour", "string",
        COLOUR_DESCRIPTION + " A group's colour marks the group only — its fixtures keep their own. " +
            "On a show on a venue, a row that sends only colour recolours a venue group for this show; null takes the venue's back.",
    )
}

internal val editGroupsTool = ToolDef(
    name = "edit_groups",
    description = "Change a project's fixture groups (the current one unless projectId names another): take fixtures out of a group, set its whole membership and order, append to it, rename it, or make a new one. " +
        "patch_fixtures only ever adds a fixture to a group; this is how one leaves. delete_groups deletes a group. " +
        "On a show on a venue only the show's own groups can be edited (a show's group may hold the venue's fixtures); a venue group is the venue's, " +
        "except that a row sending only colour recolours it for this show, and one sending members as the same fixtures in another order is this show's order of it (members in the venue's order takes the venue's back). " +
        "On the current project a renamed group's cues and Looks already running are recomposed under the new name; the programmer's own values and FX already running are not. " +
        "Everything is checked first and nothing is written if any row fails; dryRun answers what would change.",
    inputSchema = objectSchema(required = listOf("groups")) {
        arrayProp("groups", groupEditSchema)
        prop("dryRun", "boolean", "Answer what would change and write nothing. Default false.")
        prop("projectId", "integer", PROJECT_ID_DESCRIPTION)
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

internal val setStageTool = ToolDef(
    name = "set_stage",
    description = "Set up the Stage view of a project (the current one unless projectId names another) from a ground plan, section, lighting plot or photo: the stage's bounding box, the playable regions (main stage, thrust, rostra, pit) and the riggings fixtures hang from (FOH bar, LX bars, trusses, booms, floor positions). " +
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
        prop("projectId", "integer", PROJECT_ID_DESCRIPTION)
    },
)

private val pointSchema = objectSchema(required = listOf("x", "y", "z")) {
    prop("x", "number")
    prop("y", "number")
    prop("z", "number")
}

/**
 * What `get_scene` answers beside a row's fields and `set_scene` ignores, so a row read back can be
 * corrected and resent as it stands: a seating's seat count, a seat view's resolved eye, a note that
 * its seating has gone, a painted face's image size, the owner, a model's own size. Declared on both
 * row schemas, or the undeclared-argument check would refuse the resent row.
 */
internal val SCENE_READ_ONLY_FIELDS = listOf("seats", "seatedEye", "note", "paintImages", "owner", "modelSize")

private fun JsonObjectBuilder.sceneReadOnlyProps() {
    SCENE_READ_ONLY_FIELDS.forEach { name ->
        put(name, buildJsonObject { put("description", "Read-only: get_scene answers it and set_scene ignores it.") })
    }
}

private val sceneElementSchema = objectSchema(required = listOf("name")) {
    prop("name", "string", "Unique name: 'Hall', 'Proscenium', 'Stalls', 'SR wall', 'Sofa'. An existing name updates that element — fields you send overwrite, fields you omit keep.")
    enumProp("kind", StageElementKind.entries.map { it.name }, "Required for a new element. ROOM: an inward-facing shell. PROSCENIUM: a wall with an opening. FLAT: a panel with doors and windows. DRAPE: soft goods. PLATFORM: a deck, rostrum or balcony. SEATING: rows of seats (viewpoints sit in them). OBJECT: furniture, plants, signs, a flown piece — or, with shape MODEL, any shape built from parts.")
    enumProp("layer", StageElementLayer.entries.map { it.name }, "VENUE outlives a production (the hall, the proscenium, the seating); SET is this show's. Default VENUE.")
    prop("x", "number", "Metres. The element's origin: the centre of its footprint (for SEATING, the centre of the first row).")
    prop("y", "number", "Metres.")
    prop("z", "number", "Metres. The element's base — except a PLATFORM, whose z is its top surface (the deck hangs down from it), as a region's centerZ is. A hall floor below a raised stage is negative.")
    prop("yawDeg", "number", "Rotation about Z at the origin, −360–360; +yaw turns anticlockwise seen from above (0 = square to the audience).")
    prop("widthM", "number", "Size along the element's own x. Every kind but SEATING needs all three sizes; SEATING's comes from its rows and seats. A MODEL OBJECT is fitted to its three sizes; a row that sends its parts and leaves a size out takes that size from the parts (get_scene's modelSize).")
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
                "FLAT paint: {front, back} — each an image's hash from upload_scene_image, stretched over the flat's downstage (front) or upstage (back) face; the openings still cut through it. " +
                "DRAPE: {role: LEG|BORDER|TABS|CYC|BACKCLOTH, operation: DEAD|DRAW|FLY, travelS, drawFrom: STAGE_LEFT|STAGE_RIGHT (a DRAW drape that travels one way, stacking at that side when open; omit for a bi-parting pair meeting in the middle), fabric: CANVAS|MUSLIN|SHARKSTOOTH|BOBBINET (omit for velour, the only fabric that pleats; canvas is the flat painted cloth, muslin translucent, the two nets scrims), paint: {front, back} (image hashes from upload_scene_image, front the downstage side)}. A flown gauze is BACKCLOTH + FLY + SHARKSTOOTH; a traveller scrim TABS + DRAW. " +
                "PLATFORM: {railHeightM and railEdge together, region: a stage region's name when the platform is that region's deck}. " +
                "SEATING: {rows (≤26), seatsPerRow, rowPitchM, seatPitchM, firstRow ('A'), rakeM (rise per row), aisles: [{afterSeat, widthM}] (a gap in every row, seats keep their numbers), chair: THEATRE|BANQUET, frameColour, block: FIXED|PRESET (a venue's seating only: FIXED is the venue's own, in every show with its seat views; PRESET, the default, a layout a show copies)} — rows run away from the stage (−y), seat 1 at the stage-right end, the row centred on x. THEATRE (the default) is a fixed auditorium seat; BANQUET a stacking banquet chair (padded seat, round-topped back, metal frame — gold unless frameColour says otherwise; finish colour is the upholstery), about 0.45 m wide, so a seatPitchM of 0.5 sets them nearly touching. " +
                "OBJECT: {shape: BOX|CYLINDER|SHADE|DISC|MODEL, flies, travelS, parts (MODEL only)}. $MODEL_PARTS_NOTE " +
                "Any kind: states: {visible}, a DRAW drape's {open: 0–1}, a flown piece's {trimM}. " +
                "travelS (0.1–600, a DRAW or FLY drape's, or a flown OBJECT's only): seconds for the piece's whole travel — closed to drawn, in (its z) to out (its trimM). Every move not on a cue's own clock (a Look pressed, a stack stopping, move_scenery) runs at it, scaled by how far it goes; unset, those moves snap.",
        )
    })
    prop("hidden", "boolean", "Stored but not drawn.")
    prop(
        "modelFrom", "string",
        "Copy another element's model: the named OBJECT's MODEL parts, and its size and finish wherever this row leaves them out — so {name: 'Tree 4', x: 2, y: 5, modelFrom: 'Tree 1'} is another tree. " +
            "params may still add flies, travelS or states, but not parts. The source is the scene as it stands at this row: a stored element, or one an earlier row of this call sets. A copy is independent: changing the source later does not change it.",
    )
    sceneReadOnlyProps()
}

/**
 * How a `MODEL` object's parts are written, for `set_scene`'s params description: the vocabulary of
 * `models/sceneModel.kt`, which validates every key named here.
 */
private const val MODEL_PARTS_NOTE =
    "MODEL builds a shape from parts — set pieces a primitive cannot draw (an arch, a tree, a column, a cut-out, a statue, a fireplace): " +
        "parts: [{shape, x, y, z, yawDeg, pitchDeg, rollDeg, name, colour, pattern, emissive, texture, shadow, …the shape's sizes}], 1–200 of them, in the model's own frame (x across towards stage left, y upstage, z up; metres recommended, within ±100). " +
        "BOX {widthM, depthM, heightM}, SPHERE {widthM, depthM?, heightM?} (an ellipsoid; depth and height default to width) and CYLINDER {widthM (base diameter), heightM, topWidthM? (0 a cone)} are centred on x/y/z; " +
        "EXTRUDE {outline: [[x, z], …] (≥3 points, a polygon in the part's x–z plane, relative to x/z), holes?: [[[x, z], …], …], depthM (thickness along y, centred on y)} is a cut-out or an arch; " +
        "LATHE {profile: [[radius, z], …] (≥2 points, relative to x/y/z), segments? (3–96, default 32)} revolves a profile about the part's z axis — a column, an urn, a lamp base, a bottle. " +
        "Turns apply about the part's point: roll (about y; + leans the top towards stage left), then pitch (about x; + leans it downstage), then yaw (about z; + anticlockwise from above). At most 4000 outline and profile points per model. " +
        "colour (#rrggbb) defaults to the element's finish colour. texture: {image: a hash from upload_scene_image, mode: STRETCH (the default: the whole image on every face) | TILE, tileM (TILE: one repeat's size in the model's units, default 1)} — " +
        "transparent pixels cut holes in the surface, but the part still casts a whole shadow. shadow: false draws a part without a shadow box — use it for small details, since every shadowing part is a box each light tests. " +
        "The whole model is fitted to the element's box: its footprint centred on the element's x/y and its lowest point at its z, scaled to widthM × depthM × heightM. " +
        "Every part's shadow is the box round it, so a model casts a blocky shadow. To place copies of a model, use modelFrom."

private val sceneViewpointSchema = objectSchema(required = listOf("name")) {
    prop("name", "string", "Unique name as the operator would say it: 'Balcony · desk', 'Row F centre', 'Centre stage'. An existing name updates that viewpoint.")
    enumProp("kind", StageViewpointKind.entries.map { it.name }, "Required for a new viewpoint. ORBIT: the turntable camera placed at eye, circling target. EYE: a person standing at eye, looking at target. SEAT: sitting in a seat — the eye is the seat's, at seated height.")
    put("eye", pointSchema)
    put("target", pointSchema)
    prop("fovDeg", "number", "Lens, 15–90. EYE and SEAT only; default 50 standing, 52 seated.")
    prop("seating", "string", "SEAT: the seating element's name. May be omitted when the scene has one.")
    prop("seat", "string", "SEAT: row letter and number, e.g. 'F6'.")
    sceneReadOnlyProps()
}

internal const val PROSCENIUM_HALL_TEMPLATE = "proscenium-hall"

internal val setSceneTool = ToolDef(
    name = "set_scene",
    description = "Model the venue and the set for the Stage view — the room, the proscenium, masking, platforms, seating, furniture — from photos, a ground plan or a video frame, and save viewpoints (a seat, the desk's position, an actor's eye line). " +
        "Elements and viewpoints are upserted by name, and everything is validated before anything is written. " +
        "Start a hall with template '$PROSCENIUM_HALL_TEMPLATE' and its templateParams, which expand into named elements (Hall, Stage house, Main stage, Proscenium, Stalls, Balcony) you then correct with elements rows of the same names; the Stage view draws them, and get_scene reads the document back. " +
        "This is presentational: it changes no DMX output. Regions and riggings stay set_stage's. " +
        "In a show built on a venue, the venue's elements and viewpoints (owner=venue in get_scene) are read-only, but a row of only {name, hidden: true} hides a venue element for this show's whole run, and {name, hidden: false} brings it back. " + COORDINATES,
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
        prop("projectId", "integer", PROJECT_ID_DESCRIPTION)
    },
)

internal val uploadSceneImageTool = ToolDef(
    name = "upload_scene_image",
    description = "Store an image to paint on a cloth — a backcloth, a frontcloth, a gauze, a cut cloth, a flat — and answer its hash, width, height and whether it has transparent pixels. " +
        "Then name the hash in set_scene: a DRAPE's or a FLAT's params.paint {front, back}, or a MODEL part's texture.image. On a cloth the image stretches over the face, so size the element to the image's aspect; transparent pixels cut holes. " +
        "PNG or JPEG only, at most 16 MB decoded and 8192 px a side (a larger one goes in through the element's sheet on the desk). Content-addressed: the same bytes always answer the same hash. Stored data only — it changes no DMX output and nothing on stage until an element names it. " +
        "Images are stored per project: the hash paints that project's elements only (a show on a venue also sees the venue's own images). copy_to_project carries an element's images with it.",
    inputSchema = objectSchema(required = listOf("mediaType", "base64")) {
        enumProp("mediaType", listOf("image/png", "image/jpeg"))
        prop("base64", "string", "The image file's bytes, base64-encoded (a data: URL's prefix is ignored).")
        prop("projectId", "integer", PROJECT_ID_DESCRIPTION)
    },
)

internal val getSceneTool = ToolDef(
    name = "get_scene",
    description = "Read a project's scene document: every venue and set element (in set_scene's shape, so a row can be corrected and sent back) and every saved viewpoint, a seat view with the eye it resolves to. " +
        "For a show on a venue it says which venue and version the show is built on, whether the venue has published changes since (behind), and which venue elements the show hides. " + COORDINATES,
    inputSchema = objectSchema {
        prop("projectId", "integer", PROJECT_ID_DESCRIPTION)
    },
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
internal val renderViewTool = ToolDef(
    name = "render_view",
    description = "See the Stage view: render a viewpoint of the current project's stage — the venue and set set_scene built, the rig, and the light the fixtures are putting out now — and answer it as a PNG image. " +
        "Use it to check a model against the operator's photo or video frame and correct it with set_scene. " +
        "A signed-in desk window draws it offscreen (any window on the desk's own network, whatever view it is on; nothing on its screen changes), so it needs one open: with none it answers RENDER_NO_WINDOW, and asking the operator to open the desk in a browser is the fix. " +
        "It draws what a fresh Stage window shows on that viewpoint: every scene layer, haze on, and no labels. orbit and eye are the default views a fresh window opens on; plan, front and side are sections. " +
        "By default it draws the room as lit, so a scene with nothing lit comes back nearly black; set workLights true to lift the dark and check the geometry — the venue, the set and the rig — with the pools left as they are. " +
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
            prop("workLights", "boolean", "Lift the dark, as the Stage view's Work lights do: unlit surfaces show their shape while every pool keeps its exposure. Default false — the room as lit. Turn it on to check geometry; it changes no window's own setting.")
        })
        put("required", buildJsonArray { add("viewpoint") })
    },
)

private val placementSchema = objectSchema(required = listOf("key")) {
    prop("key", "string", "Patched fixture key, from get_patch or describe_rig.")
    placementProps()
}

internal val placeFixturesTool = ToolDef(
    name = "place_fixtures",
    description = "Position patched fixtures in the Stage view: which rigging each hangs on, where along it, its orientation, beam angle and gel — and for a generic dimmer its lantern and how it is focused (shutters, gate, iris, focus, zoom). Only the fields you send change; send rigging as null to take a fixture off its rigging. " +
        "The whole list is validated first and nothing is written if any row fails. Placement is presentational — it changes no DMX output. " +
        "On a show on a venue, a venue fixture (owner=venue in get_patch) is placed through this show's facet overrides: its rigging and x/y/z/yaw/pitch/roll/length are its Position, " +
        "its lantern, focus fields, beamAngleDeg, lampWatts, lampKelvin and locate its Focus, its gelCode and media its Media — each facet the row touches must already be this show's (override_venue_fixtures), " +
        "and only the fields sent change; its colour is this show's recolour of it, needing no facet. Its kind and stageHidden are the venue's; its `alsoAt` is the venue's lanterns, one entry each in get_patch's order, each changing only the fields it sends. " + COORDINATES,
    inputSchema = objectSchema(required = listOf("placements")) {
        arrayProp("placements", placementSchema)
        prop("projectId", "integer", PROJECT_ID_DESCRIPTION)
    },
)

private val venueFixtureOverrideSchema = objectSchema(required = listOf("key")) {
    prop("key", "string", "A venue fixture's key (owner=venue in get_patch, or one listed under venue.hiddenFixtures).")
    put("facets", buildJsonObject {
        put("type", "object")
        put(
            "description",
            "Facet → SHOW | VENUE | KEEP_MINE, for any of ADDRESS, POSITION, FOCUS, MEDIA — the fixture sheet's Venue | This show switch. " +
                "SHOW makes the facet this show's own, starting from the venue's value (nothing drawn or sent changes until you edit it; one already this show's keeps its value). " +
                "VENUE drops this show's value and takes the venue's again — also the answer to a drifted facet you do not want to keep. " +
                "KEEP_MINE answers a drifted facet (the venue changed it since this show took it) by keeping this show's value.",
        )
        put("properties", buildJsonObject {
            PatchFacet.entries.forEach { f -> enumProp(f.name, listOf("SHOW", "VENUE", "KEEP_MINE")) }
        })
        // A facet's name is read in any case (describe_rig prints them lower-case) and the tool
        // refuses an unknown one itself, so the keys are a map's rather than fixed properties.
        put("additionalProperties", buildJsonObject { put("type", "string") })
    })
    put("address", buildJsonObject {
        put("type", "object")
        put(
            "description",
            "Re-address the fixture in this show only — an Address override (implies facets.ADDRESS = SHOW). This changes DMX output. " +
                "An overlap with another unit is allowed and answered as a warning.",
        )
        put("properties", buildJsonObject {
            prop("universe", "integer", "A universe of this show's rig (get_patch lists them).")
            prop("subnet", "integer", "Default 0.")
            prop("startChannel", "integer", "1–512; the whole fixture must fit in the universe.")
        })
        put("required", buildJsonArray { add("universe"); add("startChannel") })
    })
    prop("colour", "string", COLOUR_DESCRIPTION + " This show's recolour of the venue fixture; null takes the venue's colour back.")
    prop(
        "hidden", "boolean",
        "true hides the venue fixture in this show (it leaves the show's rig, its channels free for the show's own units; refused while something in the show still uses it); " +
            "false brings a hidden one back. A hidden fixture keeps its overrides, dormant, and takes no new ones.",
    )
}

internal val overrideVenueFixturesTool = ToolDef(
    name = "override_venue_fixtures",
    description = "On a show on a venue, change how this show takes the venue's fixtures — the fixture sheet's Venue | This show switches, Keep mine and Hide in this show. " +
        "A venue fixture has four facets — Address, Position, Focus, Media — each the venue's until the show makes it its own; its name, key, type, head and kind stay the venue's. " +
        "Make a facet this show's here, then edit Position, Focus and Media with place_fixtures (an Address is set here, with `address`). " +
        "get_patch lists each venue fixture's `overrides` (a `drifted` one the venue has changed since) and the hidden ones. " +
        "Every row is checked first and nothing is written if any fails; dryRun answers what would change. To change a fixture for every show, edit the venue itself and publish_venue.",
    inputSchema = objectSchema(required = listOf("fixtures")) {
        arrayProp("fixtures", venueFixtureOverrideSchema)
        prop("dryRun", "boolean", "Check and answer what would change, writing nothing. Default false.")
        prop("projectId", "integer", PROJECT_ID_DESCRIPTION)
    },
)

internal val getPromptBookTool = ToolDef(
    name = "get_prompt_book",
    description = "A project's prompt book: the script PDF's page count and cover pages, every entry anchored on it (its entryId, cue, stack and number — an anchor marks one place of a cue, so a cue in two stacks can be anchored twice) and every note. If there is no prompt book yet, the answer says how the operator imports the PDF — the desk must hold the file itself, and a tool cannot upload it.",
    inputSchema = objectSchema {
        prop("projectId", "integer", PROJECT_ID_DESCRIPTION)
    },
)

/** Where something sits on the script: one rectangle on one page of the PDF. */
private val scriptPlaceSchema = objectSchema(required = listOf("pdfPage", "y")) {
    prop("pdfPage", "integer", "Page of the PDF file, counting its first page as 1 (covers included; not the number printed on the page).")
    prop("y", "number", "Top of the marked line as a fraction of the page height, 0 = top edge, 1 = bottom edge. Aim at the line the cue is called on.")
    prop("x", "number", "Left edge as a fraction of the page width. Default 0.06.")
    prop("width", "number", "Width as a fraction of the page width. Default 0.88 (the text column).")
    prop("height", "number", "Height as a fraction of the page height. Default 0.03 (about one line).")
}

private val showCueSchema = objectSchema {
    prop("cueId", "integer", "Share an existing cue of this project instead of defining one: it plays here too, and editing it changes it in every place. Such a row takes only number, followSeconds and at, which are this place's own.")
    prop("number", "string", "Cue number as the book calls it: '1', '12.5', '14A', 'S1-3'. Unique in the stack. Omit to have the desk number it from its neighbours.")
    prop("name", "string", "Short name — the stand-by line or the look: 'House to half', 'Dawn'. Required unless cueId is given.")
    prop("notes", "string", "The designer's note for the cue: what it looks like, what it's called on, anything the operator needs. This is where lighting notes go when there is no look to layer yet.")
    prop("fadeSeconds", "number", "Crossfade time in seconds. Omit for a snap.")
    prop("fadeCurve", "string", "LINEAR (default), SINE_IN_OUT, CUBIC_IN_OUT, … — an EasingCurve name.")
    prop("followSeconds", "number", "Auto-follow: the stack fires the next cue this many seconds after this one fires.")
    prop("marker", "boolean", "A section divider ('Act 1', 'Interval') rather than a cue: name is its label; GO skips it.")
    arrayProp("layers", cueLayerSchema, "Looks or palettes this cue applies, with a palette's targets — the same shape as create_cue's layers.")
    arrayProp("values", lookValueSchema, "The cue's own values, in create_look's values shape — $CUE_VALUES_NOTE Current project only: they are resolved against the live rig.")
    arrayProp("scenery", sceneryItemSchema(forCue = true), "Scene elements this cue moves on GO — the same shape as create_cue's scenery. $SCENERY_TRACKS_NOTE")
    arrayProp("events", cueEventItemSchema, "One-shot triggers this cue fires after GO — the same shape as create_cue's events. $CUE_EVENTS_NOTE")
    arrayProp("speedMasters", cueSpeedMasterItemSchema, "The same shape as create_cue's speedMasters. $CUE_SPEED_MASTERS_NOTE")
    put("moveInDark", cueMoveInDarkSchema)
    put("at", buildJsonObject {
        put("type", "object")
        put("description", "Where the cue is called in the prompt book; anchors it there. Needs a prompt book (get_prompt_book).")
        put("properties", scriptPlaceSchema["properties"]!!)
        put("required", scriptPlaceSchema["required"]!!)
    })
}

internal val buildCueStackTool = ToolDef(
    name = "build_cue_stack",
    description = "Create a cue stack for a show — or append to one — with its cues in running order, from a prompt book and the designer's lighting notes. Each cue carries its number, name, notes, fade and follow times, optional look layers, and optionally the place in the prompt book it is called on. " +
        "Cues can be created before their looks exist: put the intended state in notes, then program them later (record_cue with UPDATE_EXISTING, or create_cue-style layers and values). " +
        "A row may instead name an existing cue by cueId to share it: the same cue then plays from this stack too, with its own number and follow here. " +
        "Everything is validated before anything is written, and a numbered cue cannot be added twice, so a retry after an error is safe. Returns the cue ids and each row's entryId.",
    inputSchema = objectSchema(required = listOf("cues")) {
        prop("stackName", "string", "Name for a new stack (e.g. 'Act 1', or the show's name). Required unless stackId is given.")
        prop("stackId", "integer", "Append to this existing stack instead of creating one.")
        prop("loop", "boolean", "New stack only: wrap to the first cue after the last. Default false.")
        put("moveInDark", JsonObject(moveInDarkSchema + ("description" to JsonPrimitive("New stack only. " + moveInDarkSchema["description"]!!.jsonPrimitive.content))))
        arrayProp("scenery", sceneryItemSchema(forCue = false), "The stack's set: the states its elements hold while it is live, under its cues ('the Act 2 set appears with Act 2'). Replaces the stack's set when given.")
        arrayProp("cues", showCueSchema, "In running order.")
        prop("projectId", "integer", "$PROJECT_ID_DESCRIPTION Cues built in a project that is not current cannot carry 'at' or 'values': anchor them with mark_up_prompt_book, and give values, once it is current.")
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

private val anchorSchema = objectSchema(required = listOf("at")) {
    prop("entryId", "integer", "The entry anchored — one place of a cue in a stack (get_prompt_book and get_current_state list them). Give this, or stackId and number.")
    prop("stackId", "integer", "With number: the stack of the entry.")
    prop("number", "string", "With stackId: the entry's cue number in that stack.")
    put("at", buildJsonObject {
        put("type", "object")
        put("properties", scriptPlaceSchema["properties"]!!)
        put("required", scriptPlaceSchema["required"]!!)
    })
}

internal val markUpPromptBookTool = ToolDef(
    name = "mark_up_prompt_book",
    description = "Mark up the current project's prompt book: anchor cues' entries — each place a cue has in a stack, by entryId or by stackId and number — to the line they are called on (moving any existing anchor), add notes for the operator, and set how many cover pages precede the script's page 1. " +
        "Notes are added, never replaced — check get_prompt_book first so a note is not written twice. Everything is validated before anything is written.",
    inputSchema = objectSchema {
        prop("coverPages", "integer", "PDF pages before the script's printed page 1 (title page, cast list…), so page labels match the script.")
        arrayProp("anchors", anchorSchema)
        arrayProp("notes", scriptNoteSchema)
    },
)

internal val listVenuesTool = ToolDef(
    name = "list_venues",
    description = "List the desk's venues — halls whose room, scene, viewpoints and (optionally) house rig shows are built on. Each answers its id, its installed (latest published) version and that version's note, what that version holds (elements, viewpoints, patches, riggings, groups, Fixed seating), whether it carries a rig, its seating presets, its unpublished draft changes, and every show built on it with the version each is on.",
    inputSchema = objectSchema {},
)

internal val extractVenueTool = ToolDef(
    name = "extract_venue",
    description = "Turn the room and house rig of an existing show into a venue, publish it as v1, and put that show on it — in one step, all or nothing. " +
        "Moved into the venue (each record keeps its identity, so nothing the show's cues and Looks name breaks): the VENUE-layer scene elements, Fixed seating, camera viewpoints and seat views in Fixed seating, and — unless include leaves them out — the universes, patch, riggings, regions and groups. " +
        "The show keeps its SET-layer elements, cues, Looks, palettes and prompt book, and draws and outputs exactly as before, now reading the room from the venue. Preset seating is copied into the venue and also stays the show's. " +
        "Admin only. Run it with dryRun first: it answers exactly what would move. Extracting the current project reloads it — the rig holds its levels and the live cues, programmer and hand-run FX come back, restarting every FX — so that needs reloadLiveShow.",
    inputSchema = objectSchema(required = listOf("fromProjectId", "name")) {
        prop("fromProjectId", "integer", "The show whose room and rig become the venue (list_projects). It must not already be on a venue.")
        prop("name", "string", "The venue's name: unique on the desk, so not the show's own name. At most 50 characters.")
        arrayProp(
            "include",
            buildJsonObject { put("type", "string"); put("enum", buildJsonArray { listOf("UNIVERSES", "PATCHES", "RIGGINGS", "REGIONS", "ELEMENTS", "SEATING", "GROUPS", "VIEWPOINTS", "PARKS").forEach { add(it) } }) },
            "The parts to move; absent moves all of them. PARKS moves the show's parked channels, which then reach it (and every show on the venue) from the version. Leave out UNIVERSES, PATCHES, RIGGINGS and GROUPS for a venue with no house rig. Parts that point at each other must move together (a patch with its universe and rigging); the refusal says which.",
        )
        prop("dryRun", "boolean", "Answer what would move and keep nothing. Default false.")
        prop("reloadLiveShow", "boolean", "Required to extract from the current project: it reloads, restarting every running FX. Only when the operator has said the desk is free.")
    },
)

internal val attachToVenueTool = ToolDef(
    name = "attach_to_venue",
    description = "Put a standalone show onto a venue version — Detach in reverse. The show's records that are the venue's records go (the show reads the venue's), " +
        "what it changed becomes what a show on a venue holds (facet overrides of venue fixtures, hidden venue records, released venue parks), and what is only the show's stays its own. " +
        "Records pair by identity, then by key (fixture key, universe number, name); a show detached from this venue is matched three ways against the version it left, so its changes and the venue's are told apart. " +
        "Run it with dryRun first: the answer is the review — output and drawing changes, what is inherited, overridden, kept, hidden, brought in and renamed, the choices it offers (`decisions`, each with a default) " +
        "and the address `overlaps` it introduces, which must be answered — then send the same call with decisions and without dryRun. Blockers refuse it (ATTACH_BLOCKED) until the show changes. " +
        "Either role may attach. Attaching the current project reloads it — the rig holds its levels and the live cues, programmer and hand-run FX come back, except the programmer's values and FX on a fixture the attach renames, which name its old key — so that needs reloadLiveShow.",
    inputSchema = objectSchema(required = listOf("projectId", "venueId")) {
        prop("projectId", "integer", "The standalone show to attach (list_projects). Not a venue, and not a show already on one.")
        prop("venueId", "integer", "The venue to attach it to (list_venues).")
        prop("version", "integer", "The version to attach to; absent is the venue's installed version. An older one leaves the show behind, as any show on it is.")
        put("decisions", buildJsonObject {
            put("type", "array")
            put("description", "Answers by id, as the review lists them: a decision's `answers`, an overlap's KEEP_BOTH | READDRESS_MINE (with an optional startChannel) | HIDE_VENUES | REMOVE_MINE. An unanswered decision takes its default; an unanswered overlap refuses the attach.")
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("id", buildJsonObject { put("type", "string") })
                    put("answer", buildJsonObject { put("type", "string") })
                    put("startChannel", buildJsonObject { put("type", "integer") })
                })
                put("required", buildJsonArray { add("id"); add("answer") })
            })
        })
        prop("dryRun", "boolean", "Answer the review and keep nothing. Default false.")
        prop("reloadLiveShow", "boolean", "Required to attach the current project: it reloads. Only when the operator has said the desk is free.")
    },
)

internal val publishVenueTool = ToolDef(
    name = "publish_venue",
    description = "Publish a venue's draft — its own rows, as edited with the setup tools and projectId = the venue's id — as its next version, with a note saying what changed. " +
        "Shows built on the venue stay on the version they were built against until the operator updates each one on the desk, which reviews what changes for that show first; a show running now keeps running. Admin only.",
    inputSchema = objectSchema(required = listOf("venueId", "note")) {
        prop("venueId", "integer", "From list_venues.")
        prop("note", "string", "What changed, as the version history will read it. At most 500 characters.")
    },
)

internal val copyToProjectTool = ToolDef(
    name = "copy_to_project",
    description = "Copy show content from one project into another, without switching to either: scene elements by name, cue stacks by id with all their cues, and Looks by id. " +
        "References are found in the target: between two projects on one venue a cue's scenery and events find the venue's element and fixture by identity, whatever version each pins; otherwise a scenery change finds the element of the same name (or one copied in the same call) and an event the fixture of the same key and type. " +
        "A cue layer's Look or palette, and a palette an FX parameter names (pal:), is the target's of the same name — copied along when the target has none. Speed masters map by number, scripts by name. " +
        "Painted cloths bring their images. Copies get new ids and keep their names; a name the target already uses is refused. " +
        "Anything that does not resolve in the target is listed in `unresolved` rather than guessed: rows naming a fixture or group the target lacks are kept (they read as missing), a scenery change, event or script trigger that cannot resolve is left out. Prompt-book anchors are not copied. " +
        "All or nothing; run it with dryRun first. Stored data only — it changes no output.",
    inputSchema = objectSchema(required = listOf("fromProjectId", "toProjectId")) {
        prop("fromProjectId", "integer", "The project to copy from (list_projects).")
        prop("toProjectId", "integer", "The project to copy into.")
        put("sceneElements", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
            put("description", "Element names, as get_scene lists them in the source.")
        })
        put("cueStacks", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "integer") })
            put("description", "Cue stack ids in the source; each comes with its cues, its set and every cue's scenery and events.")
        })
        put("looks", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "integer") })
            put("description", "Look ids in the source, beyond those the copied cues layer (which come anyway).")
        })
        prop("dryRun", "boolean", "Answer what would be copied and what would not resolve, and keep nothing. Default false.")
    },
)

internal val deleteCueStacksTool = ToolDef(
    name = "delete_cue_stacks",
    description = "Delete cue stacks with their entries, their set and their prompt-book anchors. The cues stay: a cue no other stack holds is left in no stack. A running stack is refused: deactivate it first. " +
        "Everything is checked before anything is deleted; use dryRun to see what would go.",
    inputSchema = objectSchema(required = listOf("stackIds")) {
        put("stackIds", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "integer") })
        })
        prop("dryRun", "boolean", "Answer what would be deleted and delete nothing. Default false.")
        prop("projectId", "integer", PROJECT_ID_DESCRIPTION)
    },
)

internal val deleteCuesTool = ToolDef(
    name = "delete_cues",
    description = "Delete cues — which nothing else does: removing a cue from a stack, or deleting a stack, leaves the cue. " +
        "Without force it is refused while anything names one of them, and the answer lists every use: its stack entries, busk pads, cue slots, " +
        "prompt-book anchors and surface bindings (code CUE_IN_USE). With force each cue goes with its entries (their stacks renumbered), anchors, " +
        "pads and slots, and lets go of the stage wherever it is live; a binding is left to report its cue missing. All of them or none; dryRun " +
        "answers the uses and deletes nothing.",
    inputSchema = objectSchema(required = listOf("cueIds")) {
        put("cueIds", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "integer") })
        })
        prop("force", "boolean", "Delete them with everything that names them. Default false. Only once the operator has agreed to lose those uses.")
        prop("dryRun", "boolean", "Answer what names each cue and delete nothing. Default false.")
        prop("projectId", "integer", PROJECT_ID_DESCRIPTION)
    },
)

internal val copyCuesTool = ToolDef(
    name = "copy_cues",
    description = "Copy cues from another project into this one (projectId, else the current project): each copy loose, or a new entry at the end of stackId, unnumbered so the stack numbers it. " +
        "References are found here as copy_to_project finds them: a Look or palette by name (copied along when this project has none), speed masters by number, scripts by name, a cue event's fixture and a scenery change's element by identity when both projects are on one venue, else by key and type and by name. " +
        "A fixture or group target this project lacks is kept and reads as missing; an event or scenery change that cannot resolve is left out. Everything that did not come across is listed in `unresolved`. Prompt-book anchors are never copied. " +
        "Run it with dryRun first: the same copy, rolled back.",
    inputSchema = objectSchema(required = listOf("fromProjectId", "cueIds")) {
        prop("fromProjectId", "integer", "The project to copy from (list_projects).")
        put("cueIds", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "integer") })
            put("description", "Cue ids in the source, in the order they are placed.")
        })
        prop("stackId", "integer", "A stack of this project to place each copy in. Omit to leave them loose.")
        prop("dryRun", "boolean", "Answer what would be copied and what would not resolve, and keep nothing. Default false.")
        prop("projectId", "integer", "The project to copy into. $PROJECT_ID_DESCRIPTION")
    },
)

internal val setupToolDefs: List<ToolDef> = listOf(
    listProjectsTool,
    createProjectTool,
    switchProjectTool,
    listVenuesTool,
    extractVenueTool,
    attachToVenueTool,
    publishVenueTool,
    copyToProjectTool,
    deleteCueStacksTool,
    deleteCuesTool,
    copyCuesTool,
    listFixtureTypesTool,
    getPatchTool,
    patchFixturesTool,
    deleteGroupsTool,
    unpatchFixturesTool,
    renameFixturesTool,
    editGroupsTool,
    setStageTool,
    setSceneTool,
    getSceneTool,
    uploadSceneImageTool,
    renderViewTool,
    placeFixturesTool,
    overrideVenueFixturesTool,
    getPromptBookTool,
    buildCueStackTool,
    setMoveInDarkTool,
    markUpPromptBookTool,
)

internal val readOnlySetupToolNames: Set<String> =
    setOf(
        listProjectsTool.name,
        listVenuesTool.name,
        listFixtureTypesTool.name,
        getPatchTool.name,
        getPromptBookTool.name,
        getSceneTool.name,
        renderViewTool.name,
    )
