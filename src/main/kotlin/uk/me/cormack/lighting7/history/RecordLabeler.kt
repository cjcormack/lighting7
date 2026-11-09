package uk.me.cormack.lighting7.history

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import uk.me.cormack.lighting7.sync.RecordImage
import uk.me.cormack.lighting7.sync.RecordKey
import uk.me.cormack.lighting7.sync.SyncDispositions
import uk.me.cormack.lighting7.sync.canonicalJson
import java.util.UUID

/**
 * An entry's name when its door gave none (undo plan §3.4): read from the records it changed. A cue's
 * values, layers, FX and triggers are their cue's (*Cue “Rose garden”: 4 values*), and so is the stack
 * entry of a cue added or deleted with it; other stack entries are their stack's, and a record's embedded rows — a Look's values, a group's members — are counted as
 * the record's (*Look “Warm wash”: 4 values*). Several subjects are counted by kind
 * (*3 cues, 1 Look changed*).
 */
object RecordLabeler {

    private class Kind(val noun: String, val plural: String = noun + "s")

    private val KINDS = mapOf(
        "project" to Kind("project"),
        "cues" to Kind("cue"),
        "cueStacks" to Kind("stack"),
        "cueStackEntries" to Kind("stack entry", "stack entries"),
        "looks" to Kind("Look"),
        "palettes" to Kind("palette"),
        "cueSlots" to Kind("cue slot"),
        "buskPages" to Kind("busk page"),
        "buskRig" to Kind("rig row"),
        "fixturePatches" to Kind("fixture"),
        "fixtureGroups" to Kind("group"),
        "universeConfigs" to Kind("universe"),
        "showHides" to Kind("hide"),
        "showOverrides" to Kind("override"),
        "showColours" to Kind("colour"),
        "showGroupOrders" to Kind("group order"),
        "riggings" to Kind("rigging"),
        "stageRegions" to Kind("region"),
        "stageElements" to Kind("scene element"),
        "stageViewpoints" to Kind("viewpoint"),
        "parkedChannels" to Kind("park"),
        "showParkReleases" to Kind("park release"),
        "speedMasters" to Kind("speed master"),
        "scripts" to Kind("script"),
        "fxDefinitions" to Kind("FX definition"),
        "promptBooks" to Kind("prompt book"),
        "promptBookAnchors" to Kind("prompt-book anchor"),
        "promptBookAnnotations" to Kind("prompt-book note"),
        "controlSurfaceBindings" to Kind("binding"),
        "surfaceJobs" to Kind("surface job"),
    )

    /** A child folder's records, named as their owner's: the folder that owns them and the field naming it. */
    private val OWNED = mapOf(
        "cueAttributeAssignments" to Owned("cues", "cueUuid", Kind("value")),
        "cueLayers" to Owned("cues", "cueUuid", Kind("layer")),
        "cueAdHocFx" to Owned("cues", "cueUuid", Kind("FX", "FX")),
        "cueTriggers" to Owned("cues", "cueUuid", Kind("trigger")),
        "cueStackEntries" to Owned("cueStacks", "cueStackUuid", Kind("entry", "entries")),
    )

    private class Owned(val folder: String, val field: String, val kind: Kind)

    /** What an embedded array is counted as, by its field. */
    private val EMBEDDED = mapOf(
        "rows" to Kind("value"),
        "fx" to Kind("FX", "FX"),
        "scenery" to Kind("scenery change"),
        "events" to Kind("event"),
        "members" to Kind("member"),
        "extraPlacements" to Kind("placement"),
        "columns" to Kind("column"),
        "banks" to Kind("bank"),
        "pads" to Kind("pad"),
        "tiles" to Kind("tile"),
    )

    /** [changes]' name; [recordOf] reads a record they do not hold — a child's owner — as it stands. */
    fun label(changes: Collection<Change>, recordOf: (RecordKey) -> RecordImage?): String {
        if (changes.isEmpty()) return "No change"
        // Each change's subject: the record it belongs to, which for a child is its owner.
        val keys = changes.mapTo(HashSet()) { it.key }
        val bySubject = LinkedHashMap<RecordKey, MutableList<Change>>()
        for (change in changes) {
            val subject = placeOf(change, keys) ?: subjectOf(change) ?: change.key
            bySubject.getOrPut(subject) { mutableListOf() }.add(change)
        }
        if (bySubject.size == 1) {
            val (subject, own) = bySubject.entries.single()
            return labelOne(subject, own, recordOf)
        }
        val verb = when {
            changes.all { it.kind == Change.Kind.ADDED } -> "added"
            changes.all { it.kind == Change.Kind.REMOVED } -> "deleted"
            else -> "changed"
        }
        val counts = bySubject.keys.groupingBy { it.tableName }.eachCount()
        return counts.entries.joinToString(", ") { (folder, n) -> count(n, kindOf(folder)) } + " " + verb
    }

    private fun labelOne(subject: RecordKey, changes: List<Change>, recordOf: (RecordKey) -> RecordImage?): String {
        val kind = kindOf(subject.tableName)
        val self = changes.firstOrNull { it.key == subject }
        val image = self?.after ?: self?.before ?: recordOf(subject)
        val title = title(kind.noun, image)
        if (self != null && changes.size == 1) {
            return when (self.kind) {
                Change.Kind.ADDED -> "$title added"
                Change.Kind.REMOVED -> "$title deleted"
                Change.Kind.CHANGED -> embeddedCount(self)?.let { "$title: $it" } ?: "$title changed"
            }
        }
        if (self == null) {
            // Only its children: count them by kind.
            val byKind = changes.groupBy { OWNED[it.key.tableName]?.kind?.noun ?: kindOf(it.key.tableName).noun }
            val parts = byKind.map { (_, list) ->
                val childKind = OWNED[list.first().key.tableName]?.kind ?: kindOf(list.first().key.tableName)
                count(list.size, childKind)
            }
            return "$title: ${parts.joinToString(", ")}"
        }
        return when (self.kind) {
            Change.Kind.ADDED -> "$title added"
            Change.Kind.REMOVED -> "$title deleted"
            Change.Kind.CHANGED -> "$title changed"
        }
    }

    /** How many of a changed record's embedded rows changed, by kind, or null when only its own fields did. */
    private fun embeddedCount(change: Change): String? {
        val before = change.before?.parsed() ?: return null
        val after = change.after?.parsed() ?: return null
        val ownChanged = (before.keys + after.keys).any { it !in EMBEDDED && before[it] != after[it] }
        if (ownChanged) return null
        val parts = EMBEDDED.mapNotNull { (field, kind) ->
            val was = (before[field] as? JsonArray).orEmpty().map { it.toString() }
            val now = (after[field] as? JsonArray).orEmpty().map { it.toString() }
            if (was == now) return@mapNotNull null
            val wasSet = was.toSet()
            val nowSet = now.toSet()
            val moved = maxOf((nowSet - wasSet).size, (wasSet - nowSet).size).takeIf { it > 0 } ?: now.size
            count(moved, kind)
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /** A stack entry changed with its cue is the cue's place, named as the cue: *Cue “Rose garden” added*. */
    private fun placeOf(change: Change, keys: Set<RecordKey>): RecordKey? {
        if (change.key.tableName != "cueStackEntries") return null
        val image = change.after ?: change.before ?: return null
        val cue = (image.parsed()?.get("cueUuid") as? JsonPrimitive)?.content ?: return null
        return runCatching { RecordKey("cues", UUID.fromString(cue)) }.getOrNull()?.takeIf { it in keys }
    }

    private fun subjectOf(change: Change): RecordKey? {
        val owned = OWNED[change.key.tableName] ?: return null
        val image = change.after ?: change.before ?: return null
        val owner = (image.parsed()?.get(owned.field) as? JsonPrimitive)?.content ?: return null
        return runCatching { RecordKey(owned.folder, UUID.fromString(owner)) }.getOrNull()
    }

    private fun title(noun: String, image: RecordImage?): String {
        val obj = image?.parsed()
        val capital = noun.replaceFirstChar { it.uppercase() }
        val name = obj?.let {
            sequenceOf("name", "label", "key", "cueNumber").mapNotNull { field -> (it[field] as? JsonPrimitive)?.content }
                .firstOrNull { text -> text.isNotBlank() }
        }
        if (name != null) return "$capital “$name”"
        // A park or a release is its channel.
        val universe = (obj?.get("universe") as? JsonPrimitive)?.content
        val channel = (obj?.get("channel") as? JsonPrimitive)?.content
        return if (universe != null && channel != null) "$capital $universe/$channel" else capital
    }

    /** One record by its kind and name, *Look “Warm wash”*, read from [image]. */
    fun name(key: RecordKey, image: RecordImage?): String = title(kindOf(key.tableName).noun, image)

    /** What one record of [folder] is called, and several: `"cue" to "cues"`. */
    fun nouns(folder: String): Pair<String, String> = kindOf(folder).let { it.noun to it.plural }

    private fun kindOf(folder: String): Kind = KINDS[folder] ?: OWNED[folder]?.kind ?: Kind(folder)

    private fun count(n: Int, kind: Kind) = "$n ${if (n == 1) kind.noun else kind.plural}"

    private fun RecordImage.parsed(): JsonObject? =
        runCatching { canonicalJson.parseToJsonElement(json).jsonObject }.getOrNull()

    /** Every folder the labeler names, for the test that holds it to [SyncDispositions]. */
    internal val namedFolders: Set<String> get() = KINDS.keys + OWNED.keys
}
