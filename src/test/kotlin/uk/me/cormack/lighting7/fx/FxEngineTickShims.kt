package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.runBlocking

/**
 * Synchronous drivers for the compose pass, woken by a beat, the ticker or both, for tests that
 * want to step the engine a pass at a time instead of waiting on the real-time loops.
 *
 * These live in test source on purpose. Production never blocks a thread on a pass — the driver
 * in [FxEngine.start] calls [FxEngine.composePass] directly, so that the transaction commit doesn't
 * pin the calling thread inside a `runBlocking` — and a non-suspend entry point on the engine
 * is an invitation to call it from one of the places that must not.
 *
 * `runBlocking` here is what makes a test's `engine.processBeatTick(tick(0))` followed by an
 * assertion on channel values correct: the pass, including its `applySuspend`, has completed by
 * the time the call returns.
 */
internal fun FxEngine.processBeatTick(tick: MasterClock.ClockTick) = runBlocking {
    processBeatTickSuspend(tick)
}

/** A beat-woken pass over [tick] at [nowMs], for a test that steps wall-clock time too. */
internal fun FxEngine.processBeatTick(tick: MasterClock.ClockTick, nowMs: Long) = runBlocking {
    processBeatTickSuspend(SpeedMasterBank.Frame.uniform(tick), nowMs)
}

/** Wall-clock counterpart of [processBeatTick]: a pass the ticker woke, at [nowMs]. */
internal fun FxEngine.processWallClockTick(nowMs: Long = System.currentTimeMillis()) = runBlocking {
    processWallClockTickSuspend(nowMs)
}

/** A pass both clocks woke, as the driver runs when their wakes coalesce. */
internal fun FxEngine.processCoalescedTick(tick: MasterClock.ClockTick, nowMs: Long) = runBlocking {
    composePass(beat = true, ticker = true, frame = SpeedMasterBank.Frame.uniform(tick), nowMs = nowMs)
}
