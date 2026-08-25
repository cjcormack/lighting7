package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.runBlocking

/**
 * Synchronous drivers for the two tick passes, for tests that want to step the engine a tick
 * at a time instead of waiting on the real-time loops.
 *
 * These live in test source on purpose. Production never blocks a thread on a pass — the loops
 * in [FxEngine.start] call the suspend forms directly, so that the transaction commit doesn't
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

/** Wall-clock counterpart of [processBeatTick]. */
internal fun FxEngine.processWallClockTick() = runBlocking {
    processWallClockTickSuspend()
}
