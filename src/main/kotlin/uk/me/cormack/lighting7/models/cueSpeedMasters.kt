package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.fx.MasterClock
import java.util.UUID

// ─── A cue's speed masters (venue-visit plan S16, D15, D20) ──────────────────────────────────────
//
// A cue may set speed masters' tempo when it comes on: a list of (master, BPM) pairs, stored on the
// cue as `cues.speed_masters`. It is the cue's, not its entries', so a shared cue sets the same tempo
// from every stack. It never tracks: a later cue sets nothing an earlier one set.
//
// It applies on every GO into one of the cue's entries — GO TO and GO BACK included, and when another
// stack already holds the cue — and on every loose press, but never on a reload's re-GO
// (`CueStackManager.activateEntry`'s and `takeLooseCue`'s `setTempo`). A tap afterwards wins until
// the next cue that sets that master. A master that follows another, or no longer exists, is skipped
// at GO and named in the log (`fx/CueTempo.kt`).

/** One master a cue sets on GO, by uuid, and the tempo it sets it to. */
@Serializable
data class CueSpeedMasterDto(
    val speedMasterUuid: String,
    val bpm: Double,
)

private val KEYS = setOf("speedMasterUuid", "bpm")

/**
 * A whole list in the wire shape `[{speedMasterUuid, bpm}]`, checked against [masters] (the
 * project's), every problem at once. A uuid must name one of them, or one of [kept] — the uuids the
 * cue already stores, so a list that still names a master deleted under it can be re-saved and the
 * dead row removed. [bpm] is 20–300, a master's range, and a master may be named once. [where]
 * prefixes every message. Must run inside a transaction.
 */
fun parseCueSpeedMasters(
    items: List<JsonElement>,
    masters: List<DaoSpeedMaster>,
    kept: Collection<String>,
    where: String,
    problems: MutableList<String>,
): List<CueSpeedMasterDto> {
    val byUuid = masters.associateBy { it.uuid.toString() }
    val known = kept.toSet()
    val out = mutableListOf<CueSpeedMasterDto>()
    val seen = mutableSetOf<String>()
    items.forEachIndexed { i, raw ->
        val at = "$where[$i]"
        val obj = raw as? JsonObject ?: run { problems += "$at must be an object"; return@forEachIndexed }
        for (key in obj.keys) {
            if (key !in KEYS) problems += "$at: unknown field '$key' (known: ${KEYS.sorted().joinToString()})"
        }
        val before = problems.size
        val ref = (obj["speedMasterUuid"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        val uuid = when {
            ref.isNullOrEmpty() -> { problems += "$at.speedMasterUuid is required: a speed master's uuid"; null }
            runCatching { UUID.fromString(ref) }.isFailure -> { problems += "$at.speedMasterUuid '$ref' is not a uuid"; null }
            else -> UUID.fromString(ref).toString().takeIf { it in byUuid || it in known }
                ?: run { problems += "$at.speedMasterUuid names no speed master in this project"; null }
        }
        val label = uuid?.let { byUuid[it] }?.let { "$at ('${it.name}')" } ?: at
        if (uuid != null && !seen.add(uuid)) problems += "$label names a master twice: a cue sets each master once"
        val bpmRaw = obj["bpm"]?.takeIf { it !is JsonNull }
        val bpm = (bpmRaw as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
        when {
            bpmRaw == null -> problems += "$label.bpm is required"
            bpm == null -> problems += "$label.bpm must be a number"
            bpm < MasterClock.MIN_BPM || bpm > MasterClock.MAX_BPM ->
                problems += "$label.bpm must be between ${MasterClock.MIN_BPM.toInt()} and ${MasterClock.MAX_BPM.toInt()}"
        }
        if (problems.size == before && uuid != null && bpm != null) out += CueSpeedMasterDto(uuid, bpm)
    }
    return out
}

/** [project]'s masters, as [parseCueSpeedMasters] checks a list against them. Must run inside a transaction. */
fun speedMastersOf(project: DaoProject): List<DaoSpeedMaster> =
    DaoSpeedMaster.find { DaoSpeedMasters.project eq project.id }.sortedBy { it.masterIndex }

/**
 * [list] for a cue in [target], copied from one in [source]: within one project as it is, across
 * projects each master mapped by its index, as `copy_to_project`'s masters are (cue model plan
 * §3.8), the target's default bank seeded first if it has none. A pair whose master the target lacks is left out and passed to [unmapped] with the
 * master's index (null when the source no longer has it). Must run inside a transaction.
 */
fun mapCueSpeedMasters(
    list: List<CueSpeedMasterDto>,
    source: DaoProject,
    target: DaoProject,
    unmapped: (index: Int?, CueSpeedMasterDto) -> Unit = { _, _ -> },
): List<CueSpeedMasterDto> {
    if (list.isEmpty() || source.id == target.id) return list
    val sourceIndex = speedMastersOf(source).associate { it.uuid.toString() to it.masterIndex }
    val targetByIndex = ensureDefaultSpeedMasters(target).associate { it.masterIndex to it.uuid.toString() }
    return list.mapNotNull { pair ->
        val index = sourceIndex[pair.speedMasterUuid]
        val mapped = index?.let { targetByIndex[it] }
        if (mapped == null) { unmapped(index, pair); null } else pair.copy(speedMasterUuid = mapped)
    }
}
