package uk.me.cormack.lighting7.fx

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.CueSpeedMasterDto
import uk.me.cormack.lighting7.models.SpeedMasterSource
import java.util.UUID

private val logger = LoggerFactory.getLogger("CueTempo")

/**
 * Set the masters a cue sets as it comes on (venue-visit plan S16, D20): every GO into one of its
 * entries and every loose press, whoever already holds it. A master that follows another, or that
 * this bank no longer has, is skipped and named in the log; nothing here fails the GO. It moves each
 * master's live tempo (fx-speed plan D9): a master the programmer holds keeps running its hold. A
 * master whose live tempo is already the cue's is left alone, so a GO that changes nothing sends no
 * tempo frame.
 * Answers how many it set.
 */
internal fun SpeedMasterBank.setCueTempos(cueLabel: String, tempos: List<CueSpeedMasterDto>): Int {
    if (tempos.isEmpty()) return 0
    val current = masterStates().associateBy { it.uuid }
    var set = 0
    for (pair in tempos) {
        val uuid = runCatching { UUID.fromString(pair.speedMasterUuid) }.getOrNull()
        val state = uuid?.let { current[it] }
        if (state != null && state.followNum == null && state.liveBpm == pair.bpm) continue
        when (val outcome = uuid?.let { setLiveBpm(it, pair.bpm, SpeedMasterSource.MANUAL) } ?: SpeedMasterBank.TempoWriteOutcome.UnknownMaster) {
            SpeedMasterBank.TempoWriteOutcome.Applied -> set++
            SpeedMasterBank.TempoWriteOutcome.UnknownMaster ->
                logger.warn("{} sets speed master {}, which no longer exists; skipped", cueLabel, pair.speedMasterUuid)
            is SpeedMasterBank.TempoWriteOutcome.RefusedFollower ->
                logger.warn("{} sets {}, which follows {}; skipped", cueLabel, outcome.name, outcome.leaderName)
            // Only a scale answers it; a cue's tempo is clamped instead.
            is SpeedMasterBank.TempoWriteOutcome.OutOfRange -> {}
        }
    }
    return set
}
