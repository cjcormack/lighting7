package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.models.CueSpeedMasterDto
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.state.State
import java.util.UUID

/*
 * **Record, Include and Update for the programmer's tempos** (fx-speed plan D10) — the holds of
 * `state/ProgrammerTempo.kt` written into a cue's *Tempo on GO* (`cues.speed_masters`) and read back
 * out of it, beside `programmerSceneryRecord.kt`.
 *
 * A tempo names a master, never a fixture, so neither Record's attribute mask nor *Selected fixtures
 * only* narrows it: what is held is what is recorded. A new cue takes the held list; a merge replaces
 * the rows of the held masters and keeps the rest; a Remove deletes them; a Replace is a merge, as
 * the scenery Record's is. Record into a Look or a palette records no tempo.
 */

/**
 * The programmer's held tempos, by master, when they belong to [projectId]; empty otherwise. Only
 * masters the live bank still has and that follow none, so no row names a dead master or a follower.
 */
internal fun heldTemposOf(state: State, projectId: Int): Map<UUID, Double> {
    val snapshot = state.programmerTempo.flow.value
    if (snapshot.projectId != projectId) return emptyMap()
    val known = state.show.speedMasterBank.masterStates().mapNotNullTo(HashSet()) { m -> m.uuid?.takeIf { m.followNum == null } }
    return snapshot.holds.filterKeys { it in known }
}

/** What a tempo write did to one cue: rows written or replaced, and rows removed. */
data class TempoRecordOutcome(val written: Int = 0, val removed: Int = 0)

/**
 * Write [held] into [cue]'s *Tempo on GO* under [mode]. A row already saying the held tempo is left
 * alone and not counted. Must run inside a transaction.
 */
internal fun writeHeldTemposIntoCue(cue: DaoCue, held: Map<UUID, Double>, mode: RecordMode): TempoRecordOutcome {
    if (held.isEmpty()) return TempoRecordOutcome()
    val names = held.keys.mapTo(HashSet()) { it.toString() }
    val rows = cue.speedMasters
    if (mode == RecordMode.REMOVE) {
        val kept = rows.filter { it.speedMasterUuid !in names }
        if (kept.size != rows.size) cue.speedMasters = kept
        return TempoRecordOutcome(removed = rows.size - kept.size)
    }
    var written = 0
    val out = rows.map { row ->
        val bpm = row.speedMasterUuid.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { held[it] }
        if (bpm == null || bpm == row.bpm) row else CueSpeedMasterDto(row.speedMasterUuid, bpm).also { written++ }
    }.toMutableList()
    val present = rows.mapTo(HashSet()) { it.speedMasterUuid }
    for ((uuid, bpm) in held) {
        if (uuid.toString() in present) continue
        out += CueSpeedMasterDto(uuid.toString(), bpm)
        written++
    }
    if (written > 0) cue.speedMasters = out
    return TempoRecordOutcome(written = written)
}

/** A cue's *Tempo on GO* as Include holds it, by master. Must run inside a transaction. */
internal fun cueTemposForInclude(cue: DaoCue): Map<UUID, Double> =
    cue.speedMasters.mapNotNull { row ->
        runCatching { UUID.fromString(row.speedMasterUuid) }.getOrNull()?.let { it to row.bpm }
    }.toMap(LinkedHashMap())
