package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.javatime.duration
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import uk.me.cormack.lighting7.fixture.FixtureTriggers
import uk.me.cormack.lighting7.fixture.TriggerSpec
import java.time.Duration
import java.util.UUID

// ─── Cue events (stage-view plan session 9, D15, D16) ───────────────────────────────────────────
//
// A **cue event** fires one one-shot trigger — a cannon's tube — a fixed time after GO into its cue.
// It is the cannons' door into a show, and it is deliberately unlike everything else a cue carries:
//
// - it fires on GO **into this cue only** — never tracked, so GO TO a later cue fires nothing, and
//   never previewed, so Next GO fires nothing;
// - it fires only while the desk is **armed** — unarmed, the GO goes and the event is skipped,
//   logged and announced, never queued for later;
// - it is not a channel value: nothing composes, crossfades or records it.
//
// Firing is `state/EffectsService.kt`'s; this file is the record. One row per (cue, patch, trigger):
// a tube fires once per cue. The patch is a foreign key here and a uuid in sync; deleting a patch or
// a cue sweeps its events ([deleteCueEventsForPatches], [deleteCueEvents]).

object DaoCueEvents : IntIdTable("cue_events") {
    val cue = reference("cue_id", DaoCues)
    val patch = reference("patch_id", DaoFixturePatches)
    /** The trigger's name on its fixture type (`output1`), as `@FixtureTrigger` declares it. */
    val trigger = varchar("trigger", 64)
    /** How long after GO it fires. Nanoseconds on disk. */
    val offset = duration("offset")
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(cue, patch, trigger)
    }
}

class DaoCueEvent(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCueEvent>(DaoCueEvents)

    var cue by DaoCue referencedOn DaoCueEvents.cue
    var patch by DaoFixturePatch referencedOn DaoCueEvents.patch
    var trigger by DaoCueEvents.trigger
    var offset by DaoCueEvents.offset
    var sortOrder by DaoCueEvents.sortOrder
    var uuid by DaoCueEvents.uuid
}

/** The longest an event may wait after GO: ten minutes, far past any curtain call. */
val MAX_CUE_EVENT_OFFSET: Duration = Duration.ofMinutes(10)

/** One patch as an event write checks it. */
class CueEventPatchInfo(
    val id: Int,
    val uuid: UUID,
    val key: String,
    val name: String,
    val triggers: List<TriggerSpec>,
)

/** An event that has passed [parseCueEventList]. */
data class CueEventWrite(
    val patch: CueEventPatchInfo,
    val trigger: TriggerSpec,
    val offset: Duration,
)

/** The project's patches as an event write checks them, by id. Must run inside a transaction. */
fun cueEventPatchesOf(project: DaoProject): Map<Int, CueEventPatchInfo> =
    DaoFixturePatch.find { DaoFixturePatches.project eq project.id }.associate { p ->
        p.id.value to CueEventPatchInfo(p.id.value, p.uuid, p.key, p.displayName, FixtureTriggers.specsForTypeKey(p.fixtureTypeKey))
    }

/**
 * A whole event list in the REST shape — `[{patchId, trigger, offsetMs}]` — checked against the
 * project's [patches], every problem at once. A patch must carry triggers and [trigger] must be one
 * of them, by name (`output1`) or by label (`A`); a tube named twice is a problem, since it fires
 * once. [where] prefixes every message.
 */
fun parseCueEventList(
    items: List<JsonElement>,
    patches: Map<Int, CueEventPatchInfo>,
    where: String,
    problems: MutableList<String>,
): List<CueEventWrite> = parseEvents(items, where, problems, REST_KEYS, patchOf = { obj, at ->
    val raw = obj["patchId"]?.takeIf { it !is JsonNull }
    val id = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    when {
        raw == null -> { problems += "$at.patchId is required"; null }
        id == null -> { problems += "$at.patchId must be a patch id"; null }
        else -> patches[id] ?: run { problems += "$at.patchId names no patch in this project"; null }
    }
}) offset@{ obj, label ->
    val raw = obj["offsetMs"]?.takeIf { it !is JsonNull } ?: return@offset Duration.ZERO
    val ms = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    when {
        ms == null -> { problems += "$label.offsetMs must be a whole number of milliseconds"; null }
        ms < 0 || ms > MAX_CUE_EVENT_OFFSET.toMillis() ->
            { problems += "$label.offsetMs must be between 0 and ${MAX_CUE_EVENT_OFFSET.toMillis()}"; null }
        else -> Duration.ofMillis(ms)
    }
}

/**
 * An event list in the MCP tools' shape — `[{fixture, trigger, offsetSeconds?}]`, the
 * fixture by **key** or display name — checked exactly as [parseCueEventList] checks the REST shape.
 */
fun parseToolCueEventList(
    items: List<JsonElement>,
    patches: Map<Int, CueEventPatchInfo>,
    where: String,
    problems: MutableList<String>,
): List<CueEventWrite> {
    val byKey = patches.values.associateBy { it.key.lowercase() }
    val byName = patches.values.associateBy { it.name.lowercase() }
    return parseEvents(items, where, problems, TOOL_KEYS, patchOf = { obj, at ->
        val ref = (obj["fixture"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        when {
            ref.isNullOrEmpty() -> { problems += "$at.fixture is required: a patched fixture's key"; null }
            else -> byKey[ref.lowercase()] ?: byName[ref.lowercase()] ?: run {
                val cannons = patches.values.filter { it.triggers.isNotEmpty() }.map { it.key }.sorted()
                problems += "$at: no patched fixture '$ref' (fixtures with triggers: ${cannons.joinToString().ifEmpty { "none" }})"
                null
            }
        }
    }) offset@{ obj, label ->
        val raw = obj["offsetSeconds"]?.takeIf { it !is JsonNull } ?: return@offset Duration.ZERO
        val seconds = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
        when {
            seconds == null -> { problems += "$label.offsetSeconds must be a number"; null }
            seconds < 0 || seconds * 1000 > MAX_CUE_EVENT_OFFSET.toMillis() ->
                { problems += "$label.offsetSeconds must be between 0 and ${MAX_CUE_EVENT_OFFSET.seconds}"; null }
            else -> Duration.ofMillis(Math.round(seconds * 1000))
        }
    }
}

private val REST_KEYS = setOf("patchId", "trigger", "offsetMs", "uuid", "sortOrder", "patchUuid", "fixtureKey", "fixtureName", "triggerLabel")
private val TOOL_KEYS = setOf("fixture", "trigger", "offsetSeconds")

private fun parseEvents(
    items: List<JsonElement>,
    where: String,
    problems: MutableList<String>,
    known: Set<String>,
    patchOf: (JsonObject, String) -> CueEventPatchInfo?,
    offsetOf: (JsonObject, String) -> Duration?,
): List<CueEventWrite> {
    val out = mutableListOf<CueEventWrite>()
    val seen = mutableSetOf<Pair<Int, String>>()
    items.forEachIndexed { i, raw ->
        val at = "$where[$i]"
        val obj = raw as? JsonObject ?: run { problems += "$at must be an object"; return@forEachIndexed }
        for (key in obj.keys) {
            if (key !in known) problems += "$at: unknown field '$key' (known: ${known.sorted().joinToString()})"
        }
        val before = problems.size
        val patch = patchOf(obj, at)
        val label = patch?.let { "$at ('${it.name}')" } ?: at
        val triggerRef = (obj["trigger"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        val trigger = when {
            patch == null -> null
            patch.triggers.isEmpty() -> { problems += "$label has no one-shot trigger to fire"; null }
            triggerRef.isNullOrEmpty() -> {
                problems += "$label.trigger is required: one of ${patch.triggers.joinToString { "'${it.name}' (${it.label})" }}"
                null
            }
            else -> patch.triggers.firstOrNull { it.name.equals(triggerRef, ignoreCase = true) || it.label.equals(triggerRef, ignoreCase = true) }
                ?: run {
                    problems += "$label.trigger '$triggerRef' is not one of its triggers (${patch.triggers.joinToString { "'${it.name}' (${it.label})" }})"
                    null
                }
        }
        if (patch != null && trigger != null && !seen.add(patch.id to trigger.name)) {
            problems += "$label fires ${trigger.label} twice: a tube fires once per cue"
        }
        val offset = offsetOf(obj, label)
        if (problems.size == before && patch != null && trigger != null && offset != null) out += CueEventWrite(patch, trigger, offset)
    }
    return out
}

// ─── The wire ────────────────────────────────────────────────────────────────────────────────

/** One cue event on the wire, as `CueDetails.events` carries it and `PUT cues/{id}/events` answers it. */
@Serializable
data class CueEventDto(
    val uuid: String,
    val patchId: Int,
    val patchUuid: String,
    val fixtureKey: String,
    val fixtureName: String,
    val trigger: String,
    /** The trigger's short name (`A`), or the stored name again when the type no longer declares it. */
    val triggerLabel: String,
    val offsetMs: Long,
    val sortOrder: Int,
)

@Serializable
data class CueEventListRequest(val events: List<JsonElement>? = null)

internal fun DaoCueEvent.toDto(): CueEventDto {
    val p = patch
    val spec = FixtureTriggers.specsForTypeKey(p.fixtureTypeKey).firstOrNull { it.name == trigger }
    return CueEventDto(
        uuid = uuid.toString(),
        patchId = p.id.value,
        patchUuid = p.uuid.toString(),
        fixtureKey = p.key,
        fixtureName = p.displayName,
        trigger = trigger,
        triggerLabel = spec?.label ?: trigger,
        offsetMs = offset.toMillis(),
        sortOrder = sortOrder,
    )
}

fun cueEventsOf(cueId: EntityID<Int>): List<DaoCueEvent> =
    DaoCueEvent.find { DaoCueEvents.cue eq cueId }.sortedWith(compareBy({ it.sortOrder }, { it.id.value }))

/**
 * Replace [cue]'s whole event list, keeping the row — and so the uuid — of a tube the list names
 * again, so re-saving an unchanged list exports byte-for-byte as before. Must run inside a
 * transaction.
 */
fun replaceCueEvents(cue: DaoCue, writes: List<CueEventWrite>) {
    val existing = DaoCueEvent.find { DaoCueEvents.cue eq cue.id }.associateBy { it.patch.id.value to it.trigger }
    val kept = writes.map { it.patch.id to it.trigger.name }.toSet()
    existing.filterKeys { it !in kept }.values.forEach { it.delete() }
    writes.forEachIndexed { i, w ->
        val row = existing[w.patch.id to w.trigger.name] ?: DaoCueEvent.new {
            this.cue = cue
            this.patch = DaoFixturePatch[w.patch.id]
            this.trigger = w.trigger.name
            this.offset = w.offset
        }
        row.offset = w.offset
        row.sortOrder = i
    }
}

/** A cue's events, swept with the cue. Not part of `deleteCueChildren`, which also runs on a cue *edit*. */
fun deleteCueEvents(cueId: EntityID<Int>): Int = DaoCueEvents.deleteWhere { DaoCueEvents.cue eq cueId }

/** Every event naming [patchIds], swept with the patch — a plain FK with no cascade. */
fun deleteCueEventsForPatches(patchIds: Collection<EntityID<Int>>): Int {
    if (patchIds.isEmpty()) return 0
    return DaoCueEvents.deleteWhere { DaoCueEvents.patch inList patchIds }
}

/** [from]'s events written onto [to] — a copied cue fires what its original fires. */
fun copyCueEvents(from: DaoCue, to: DaoCue) {
    for (row in cueEventsOf(from.id)) {
        DaoCueEvent.new {
            cue = to
            patch = row.patch
            trigger = row.trigger
            offset = row.offset
            sortOrder = row.sortOrder
        }
    }
}
