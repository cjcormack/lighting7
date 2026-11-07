package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.fx.BUILT_IN_WALL_CLOCK_FX
import uk.me.cormack.lighting7.fx.FxRegistration
import uk.me.cormack.lighting7.fx.FxRegistry
import uk.me.cormack.lighting7.fx.FxSource
import uk.me.cormack.lighting7.fx.StoredWriteClock
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.fx.storedClockProblem
import uk.me.cormack.lighting7.fx.storedWriteClock
import uk.me.cormack.lighting7.models.CueAdHocFxDto
import uk.me.cormack.lighting7.models.LookFxDto
import uk.me.cormack.lighting7.models.PaletteFxDto

// The stored-effect writes' clock (fx-speed plan D3–D5): a Look's, a palette's and a cue's effects
// are written with their Own tempo, a request's old `seconds` converted by D5, and no master beside
// an own clock. Each write boundary checks [clockProblem] and writes what [withStoredClock] answers.

private fun FxRegistry.kindOf(fxType: String): TimingSource? = getRegistration(fxType)?.timingSource

/** What a stored effect's clock may not say, or null; see [storedClockProblem]. */
internal fun FxRegistry.clockProblem(fxType: String, ownBpm: Double?): String? {
    val registration = getRegistration(fxType)
    return storedClockProblem(registration?.timingSource, registration?.let(::namedByStep) ?: false, ownBpm)
}

/**
 * Whether database step 12 and the importer can tell [registration]'s loop by name: a built-in, or a
 * project's FX definition (`fx_definitions`, whose registration carries its script). One an
 * `FX_DEFINITION` script registers is `USER` too, but carries none, and neither can see it.
 */
internal fun namedByStep(registration: FxRegistration): Boolean =
    registration.id in BUILT_IN_WALL_CLOCK_FX || (registration.source == FxSource.USER && registration.script != null)

/**
 * The row a stored write keeps for an effect of [fxType]: its clock by [storedWriteClock], and its
 * masters unless that clock is free of them, handed to [copy] — each FX home's DTO its own copy.
 */
private inline fun <T> FxRegistry.storedClock(
    fxType: String,
    ownBpm: Double?,
    seconds: Boolean,
    cycle: Double,
    speedMasterUuid: String?,
    rateSpeedMasterUuid: String?,
    copy: (clock: StoredWriteClock, speedMasterUuid: String?, rateSpeedMasterUuid: String?) -> T,
): T {
    val clock = storedWriteClock(kindOf(fxType), ownBpm, seconds, cycle)
    return copy(clock, speedMasterUuid.takeUnless { clock.free }, rateSpeedMasterUuid.takeUnless { clock.free })
}

internal fun LookFxDto.withStoredClock(registry: FxRegistry): LookFxDto =
    registry.storedClock(fxType, ownBpm, seconds, cycle, speedMasterUuid, rateSpeedMasterUuid) { clock, speed, rate ->
        copy(ownBpm = clock.ownBpm, cycle = clock.cycle, seconds = false, speedMasterUuid = speed, rateSpeedMasterUuid = rate)
    }

internal fun PaletteFxDto.withStoredClock(registry: FxRegistry): PaletteFxDto =
    registry.storedClock(fxType, ownBpm, seconds, cycle, speedMasterUuid, rateSpeedMasterUuid) { clock, speed, rate ->
        copy(ownBpm = clock.ownBpm, cycle = clock.cycle, seconds = false, speedMasterUuid = speed, rateSpeedMasterUuid = rate)
    }

internal fun CueAdHocFxDto.withStoredClock(registry: FxRegistry): CueAdHocFxDto =
    registry.storedClock(fxType, ownBpm, seconds, cycle, speedMasterUuid, rateSpeedMasterUuid) { clock, speed, rate ->
        copy(ownBpm = clock.ownBpm, cycle = clock.cycle, seconds = false, speedMasterUuid = speed, rateSpeedMasterUuid = rate)
    }
