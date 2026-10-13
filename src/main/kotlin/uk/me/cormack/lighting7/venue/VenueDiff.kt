package uk.me.cormack.lighting7.venue

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import uk.me.cormack.lighting7.models.ParkAddress
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.sync.canonicalJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * What changed between two states of one venue — two versions, or a version and the draft — and
 * whether each change **changes output** or is **drawing only** (venue plan §3.1, D7). The rule,
 * which `docs/fixtures-engineering.md` §"Venue versions" states for the record:
 *
 * - **Output**: a universe added, removed or re-addressed (its subnet, universe or controller type),
 *   a patch added, removed, re-addressed (its universe or start channel) or re-typed (its type
 *   key, which names the mode), or a **park** added, removed or changed in value — a venue's parks
 *   reach its shows with the version, and hold a channel above everything they send.
 * - **Drawing**: everything else — positions, focus, media, names, groups, riggings, the scene,
 *   viewpoints, regions, the stage's size.
 *
 * Both sides are read through [ProjectExporter], so the diff sees exactly what a version freezes and
 * what sync carries, field for field — and every table it carries, so whatever a publish would freeze
 * and a discard would undo is counted. The rig — universes, patches and their placements, groups,
 * riggings, regions, scene elements, viewpoints and the stage's size — is classified and named; any
 * other table (a Look made in a venue through the API) is `OTHER`, drawing only. Parks are matched by
 * **channel** (universe and channel), not by row uuid: a venue that unparks a channel and parks it again
 * at the same value has changed nothing a show would see.
 * The speed masters are the one table left out ([IGNORED_FOLDERS]).
 * A controller's IP is machine-local and never exported, so it is never a change. A row's
 * `sortOrder` alone is not one either: order is the list's, not the rig's.
 */
object VenueDiff {

    /** A record's identity in an export: its folder and its uuid (`project` for `project.json`). */
    data class Key(val folder: String, val uuid: String)

    /** Every record of one export, by [Key]. */
    class Records(val byKey: Map<Key, JsonObject>) {
        fun folder(name: String): Map<String, JsonObject> =
            byKey.filterKeys { it.folder == name }.mapKeys { it.key.uuid }

        companion object {
            val EMPTY = Records(emptyMap())
        }
    }

    /** Export [projectId]'s records (no blobs) and read them back. */
    fun recordsOf(state: State, projectId: Int): Records {
        val staging = Files.createTempDirectory("venue-diff-")
        try {
            ProjectExporter(state).export(projectId, staging, blobs = false)
            return read(staging)
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    /**
     * Every record under an export folder [dir] — its `project.json`, and each folder but
     * [IGNORED_FOLDERS] — scripts' bodies folded into their meta.
     */
    fun read(dir: Path): Records {
        val out = mutableMapOf<Key, JsonObject>()
        out[Key(PROJECT, PROJECT)] = canonicalJson.parseToJsonElement(Files.readString(dir.resolve("project.json"))) as JsonObject
        Files.list(dir).use { it.toList() }.filter { it.isDirectory() && it.name !in IGNORED_FOLDERS }.forEach { sub ->
            val folder = sub.name
            Files.list(sub).use { it.toList() }.forEach { file ->
                val fileName = file.name
                when {
                    folder == "scripts" && fileName.endsWith(".meta.json") -> {
                        val uuid = fileName.removeSuffix(".meta.json")
                        val meta = canonicalJson.parseToJsonElement(Files.readString(file)) as JsonObject
                        val body = sub.resolve("$uuid.kts").takeIf { Files.exists(it) }?.let { Files.readString(it) }
                        out[Key(folder, uuid)] = JsonObject(meta + ("body" to JsonPrimitive(body)))
                    }
                    folder != "scripts" && file.extension == "json" ->
                        out[Key(folder, fileName.removeSuffix(".json"))] =
                            canonicalJson.parseToJsonElement(Files.readString(file)) as JsonObject
                }
            }
        }
        return Records(out)
    }

    /** What changed from [before] to [after], output first, each list in a stable order. */
    fun compare(beforeRecords: Records, afterRecords: Records): Diff {
        val before = beforeRecords.parksByChannel()
        val after = afterRecords.parksByChannel()
        val context = Context(before, after)
        val changes = (before.byKey.keys + after.byKey.keys)
            .filter { it.folder != PROJECT && it.folder !in IGNORED_FOLDERS }
            .mapNotNull { key -> changeOf(key, before.byKey[key], after.byKey[key], context) } +
            listOfNotNull(stageChange(before.byKey[Key(PROJECT, PROJECT)], after.byKey[Key(PROJECT, PROJECT)]))
        val ordered = changes.sortedWith(
            compareBy<VenueChangeDto>({ KIND_ORDER.indexOf(it.kind) }, { it.address ?: "" }, { it.name.lowercase() }, { it.uuid ?: "" }),
        )
        return Diff(output = ordered.filter { it.output }, drawing = ordered.filterNot { it.output })
    }

    data class Diff(val output: List<VenueChangeDto>, val drawing: List<VenueChangeDto>) {
        val size: Int get() = output.size + drawing.size
    }

    /** The channel a [VenueChangeKind.PARK] change is to, read back from its [VenueChangeDto.address]; null for any other. */
    fun parkAddressOf(change: VenueChangeDto): ParkAddress? {
        if (change.kind != VenueChangeKind.PARK) return null
        val (universe, channel) = change.address?.split('-')?.takeIf { it.size == 2 } ?: return null
        return ParkAddress(universe.toIntOrNull() ?: return null, channel.toIntOrNull() ?: return null)
    }

    /** [this] with its parks keyed by channel (`universe:channel`) rather than row uuid. */
    private fun Records.parksByChannel(): Records = Records(
        byKey.mapKeys { (key, record) ->
            if (key.folder != PARKS) key
            else Key(PARKS, "${record.int("universe") ?: "?"}:${record.int("channel") ?: "?"}")
        },
    )

    // ── One record ──────────────────────────────────────────────────────────────

    /** The universes and riggings of each side, to name what a patch points at. */
    private class Context(before: Records, after: Records) {
        val universesBefore = before.folder("universeConfigs")
        val universesAfter = after.folder("universeConfigs")
        val riggingsBefore = before.folder("riggings")
        val riggingsAfter = after.folder("riggings")
    }

    private fun changeOf(key: Key, old: JsonObject?, new: JsonObject?, context: Context): VenueChangeDto? {
        val kind = KIND_BY_FOLDER[key.folder] ?: VenueChangeKind.OTHER
        val record = new ?: old ?: return null
        val name = nameOf(kind, key.folder, record)
        // A park's key is its channel; its uuid is the row's (the new side's where both are there).
        val uuid = if (kind == VenueChangeKind.PARK) record.text("uuid") else key.uuid
        val isPatch = kind == VenueChangeKind.PATCH
        val addressOf = { r: JsonObject?, universes: Map<String, JsonObject> -> r?.let { patchAddress(it, universes) } }
        val address = when (kind) {
            VenueChangeKind.PATCH -> addressOf(new, context.universesAfter) ?: addressOf(old, context.universesBefore)
            VenueChangeKind.UNIVERSE -> universeAddress(record)
            VenueChangeKind.PARK -> parkAddress(record)
            else -> null
        }
        if (old == null || new == null) {
            val added = old == null
            return VenueChangeDto(
                kind = kind,
                table = key.folder,
                uuid = uuid,
                name = name,
                change = if (added) VenueChangeType.ADDED else VenueChangeType.REMOVED,
                output = kind == VenueChangeKind.PATCH || kind == VenueChangeKind.UNIVERSE || kind == VenueChangeKind.PARK,
                address = address,
            )
        }

        val fields = mutableListOf<VenueFieldChangeDto>()
        val facets = linkedSetOf<VenueFacet>()
        val keys = (old.keys + new.keys).filter { it !in IGNORED_FIELDS }.sorted()
        if (isPatch) {
            val from = patchAddress(old, context.universesBefore)
            val to = patchAddress(new, context.universesAfter)
            if (from != to) {
                fields += VenueFieldChangeDto("address", from, to)
                facets += VenueFacet.ADDRESS
            }
        }
        for (field in keys) {
            if (isPatch && field in PATCH_ADDRESS_FIELDS) continue
            val a = old[field] ?: JsonNull
            val b = new[field] ?: JsonNull
            if (a == b) continue
            when {
                isPatch && field == "extraPlacements" -> {
                    val inner = placementFacets(a, b)
                    facets += inner.ifEmpty { setOf(VenueFacet.POSITION) }
                    fields += VenueFieldChangeDto(field, placementsLabel(a), placementsLabel(b))
                }
                isPatch && field == "riggingUuid" -> {
                    facets += VenueFacet.POSITION
                    fields += VenueFieldChangeDto(
                        "rigging",
                        riggingName(a, context.riggingsBefore),
                        riggingName(b, context.riggingsAfter),
                    )
                }
                kind == VenueChangeKind.GROUP && field == "members" -> {
                    val from = membersLabel(a)
                    val to = membersLabel(b)
                    fields += VenueFieldChangeDto(field, from, if (from == to) "$to, changed" else to)
                }
                else -> {
                    facets += if (isPatch) PATCH_FACETS[field] ?: VenueFacet.OTHER else VenueFacet.OTHER
                    fields += VenueFieldChangeDto(field, display(a), display(b))
                }
            }
        }
        if (fields.isEmpty()) return null
        val output = when (kind) {
            VenueChangeKind.PATCH -> VenueFacet.ADDRESS in facets || VenueFacet.TYPE in facets
            VenueChangeKind.UNIVERSE -> fields.any { it.field in UNIVERSE_OUTPUT_FIELDS }
            // Keyed by channel, so the value is all a park can change.
            VenueChangeKind.PARK -> true
            else -> false
        }
        return VenueChangeDto(
            kind = kind,
            table = key.folder,
            uuid = uuid,
            name = name,
            change = VenueChangeType.CHANGED,
            output = output,
            facets = if (isPatch) facets.toList() else emptyList(),
            fields = fields,
            address = address,
        )
    }

    /** The stage's own size, the one part of `project.json` a venue versions. */
    private fun stageChange(old: JsonObject?, new: JsonObject?): VenueChangeDto? {
        if (old == null && new == null) return null
        val fields = STAGE_FIELDS.mapNotNull { field ->
            val a = old?.get(field) ?: JsonNull
            val b = new?.get(field) ?: JsonNull
            if (a == b) null else VenueFieldChangeDto(field, display(a), display(b))
        }
        if (fields.isEmpty()) return null
        return VenueChangeDto(
            kind = VenueChangeKind.STAGE,
            table = PROJECT,
            uuid = null,
            name = "Stage",
            change = VenueChangeType.CHANGED,
            output = false,
            fields = fields,
        )
    }

    private fun nameOf(kind: VenueChangeKind, folder: String, record: JsonObject): String = when (kind) {
        VenueChangeKind.PATCH -> record.text("displayName") ?: record.text("key") ?: "Fixture"
        VenueChangeKind.UNIVERSE -> "Universe ${record.int("universe") ?: "?"}"
        VenueChangeKind.PARK -> "Park ${parkAddress(record) ?: "?"}"
        // A record with no name of its own (a cue's layer) is named by its table.
        VenueChangeKind.OTHER -> record.text("name") ?: record.text("label") ?: folder
        else -> record.text("name") ?: record.text("label") ?: kind.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    /** `1-005`: the patch's universe number and start channel, as the Patch list writes it. */
    private fun patchAddress(patch: JsonObject, universes: Map<String, JsonObject>): String? {
        val universe = patch.text("universeConfigUuid")?.let { universes[it] }?.int("universe") ?: return null
        val channel = patch.int("startChannel") ?: return null
        return "$universe-${channel.toString().padStart(3, '0')}"
    }

    /** `1-005`: a park's universe number and channel, as a patch's address is written. */
    private fun parkAddress(park: JsonObject): String? {
        val universe = park.int("universe") ?: return null
        val channel = park.int("channel") ?: return null
        return ParkAddress(universe, channel).toString()
    }

    /** `ARTNET 0:3` — the controller type, where the universe names one, then subnet and universe. */
    private fun universeAddress(universe: JsonObject): String =
        listOfNotNull(universe.text("controllerType"), "${universe.int("subnet") ?: 0}:${universe.int("universe") ?: 0}")
            .joinToString(" ")

    private fun riggingName(uuid: JsonElement, riggings: Map<String, JsonObject>): String =
        (uuid as? JsonPrimitive)?.contentOrNull?.let { riggings[it]?.text("name") ?: "a rigging" } ?: "free"

    private fun membersLabel(element: JsonElement): String {
        val n = (element as? JsonArray)?.size ?: 0
        return if (n == 1) "1 member" else "$n members"
    }

    private fun placementsLabel(element: JsonElement): String {
        val n = (element as? JsonArray)?.size ?: 0
        return if (n == 1) "1 placement" else "$n placements"
    }

    /** The facets an extra placement's changes touch, placements matched by uuid. */
    private fun placementFacets(a: JsonElement, b: JsonElement): Set<VenueFacet> {
        val before = placementsByUuid(a)
        val after = placementsByUuid(b)
        val facets = mutableSetOf<VenueFacet>()
        if (before.keys != after.keys) facets += VenueFacet.POSITION
        for ((uuid, old) in before) {
            val new = after[uuid] ?: continue
            for (field in old.keys + new.keys) {
                if ((old[field] ?: JsonNull) != (new[field] ?: JsonNull)) {
                    facets += if (field == "riggingUuid" || field == "label") VenueFacet.POSITION else PATCH_FACETS[field] ?: VenueFacet.OTHER
                }
            }
        }
        return facets
    }

    private fun placementsByUuid(element: JsonElement): Map<String, JsonObject> =
        (element as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .associateBy { it.text("uuid") ?: "" }

    /** A value as one short line: a primitive's content, `—` for none, anything else as JSON. */
    private fun display(element: JsonElement): String? = when (element) {
        JsonNull -> null
        is JsonPrimitive -> element.contentOrNull
        else -> element.toString()
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private const val PROJECT = "project"
    private const val PARKS = "parkedChannels"

    /**
     * The speed masters every project is made with (`ensureDefaultSpeedMasters`): an empty venue holds
     * them before anyone has drawn a thing, and a tempo is the show's, not the hall's. Never a change.
     */
    private val IGNORED_FOLDERS = setOf("speedMasters")
    private val IGNORED_FIELDS = setOf("uuid", "sortOrder")
    private val STAGE_FIELDS = listOf("stageWidthM", "stageDepthM", "stageHeightM")
    private val PATCH_ADDRESS_FIELDS = setOf("universeConfigUuid", "startChannel")
    private val UNIVERSE_OUTPUT_FIELDS = setOf("subnet", "universe", "controllerType")

    /** A patch field's facet (venue plan §3.2's table, plus Type and Identity, which are the venue's). */
    private val PATCH_FACETS: Map<String, VenueFacet> = buildMap {
        put("fixtureTypeKey", VenueFacet.TYPE)
        listOf("key", "displayName", "headNumber").forEach { put(it, VenueFacet.IDENTITY) }
        listOf("stageX", "stageY", "stageZ", "baseYawDeg", "basePitchDeg", "baseRollDeg", "lengthM")
            .forEach { put(it, VenueFacet.POSITION) }
        listOf(
            "lanternType", "beamAngleDeg", "lampWatts", "lampKelvin", "zoomDeg", "lampRotationDeg", "shutters", "gateRotationDeg",
            "iris", "focusSoftness",
        ).forEach { put(it, VenueFacet.FOCUS) }
        listOf("gelCode", "media").forEach { put(it, VenueFacet.MEDIA) }
    }

    private val KIND_BY_FOLDER = mapOf(
        "universeConfigs" to VenueChangeKind.UNIVERSE,
        "fixturePatches" to VenueChangeKind.PATCH,
        "fixtureGroups" to VenueChangeKind.GROUP,
        "riggings" to VenueChangeKind.RIGGING,
        "stageRegions" to VenueChangeKind.REGION,
        "stageElements" to VenueChangeKind.ELEMENT,
        "stageViewpoints" to VenueChangeKind.VIEWPOINT,
        PARKS to VenueChangeKind.PARK,
    )
    private val KIND_ORDER = listOf(
        VenueChangeKind.UNIVERSE, VenueChangeKind.PATCH, VenueChangeKind.PARK, VenueChangeKind.GROUP, VenueChangeKind.RIGGING,
        VenueChangeKind.REGION, VenueChangeKind.ELEMENT, VenueChangeKind.VIEWPOINT, VenueChangeKind.STAGE,
        VenueChangeKind.OTHER,
    )
}

/**
 * What a [VenueChangeDto] is a change to: a rig table, a parked channel ([PARK], output), the stage's
 * size, or `OTHER` — any other table a venue project holds (a Look, a script), named by
 * [VenueChangeDto.table].
 */
@Serializable
enum class VenueChangeKind { UNIVERSE, PATCH, PARK, GROUP, RIGGING, REGION, ELEMENT, VIEWPOINT, STAGE, OTHER }

@Serializable
enum class VenueChangeType { ADDED, REMOVED, CHANGED }

/**
 * Which part of a patch a change touches: the plan's four facets (§3.2) — Address, Position, Focus,
 * Media — plus Type and Identity, which no show may override but a venue may change, and Other.
 */
@Serializable
enum class VenueFacet { ADDRESS, TYPE, IDENTITY, POSITION, FOCUS, MEDIA, OTHER }

/** One field that changed, each side as one short line (null for none). */
@Serializable
data class VenueFieldChangeDto(val field: String, val from: String?, val to: String?)

/**
 * One record's change. [table] is the export folder it lives in (`fixturePatches`); [address] is a
 * patch's or a park's `1-005` (the new side's, or the old side's for a removal) or a universe's
 * `ARTNET 0:3`; [facets] is a patch's only.
 */
@Serializable
data class VenueChangeDto(
    val kind: VenueChangeKind,
    val table: String,
    val uuid: String?,
    val name: String,
    val change: VenueChangeType,
    val output: Boolean,
    val facets: List<VenueFacet> = emptyList(),
    val fields: List<VenueFieldChangeDto> = emptyList(),
    val address: String? = null,
)
