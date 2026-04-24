package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ControllerTransaction(controllers: List<DmxController>) {
    // Per-universe staging. Reads fall through to the controller's own `currentValues` for any
    // channel not yet staged, so we don't pay an O(channels-per-universe) `toMutableMap()` copy
    // per transaction — a hot-path win since the FX engine opens one transaction per tick.
    private class UniverseState(
        val controller: DmxController,
        val valuesToSet: MutableMap<Int, ChannelChange>,
    )

    private val universeState = controllers.associate {
        it.universe to UniverseState(it, mutableMapOf())
    }

    fun setValues(universe: Universe, valuesToSet: List<Pair<Int, ChannelChange>>) {
        valuesToSet.forEach {
            setValue(universe, it.first, it.second)
        }
    }

    fun setValue(universe: Universe, channelNo: Int, channelChange: ChannelChange) {
        checkNotNull(universeState[universe]).valuesToSet[channelNo] = channelChange
    }

    fun setValue(universe: Universe, channelNo: Int, channelValue: UByte, fadeMs: Long = 0L) {
        setValue(universe, channelNo, ChannelChange(channelValue, fadeMs))
    }

    fun getValue(universe: Universe, channelNo: Int): UByte {
        val state = checkNotNull(universeState[universe])
        state.valuesToSet[channelNo]?.let { return it.newValue }
        return state.controller.getValue(channelNo)
    }

    /**
     * Blocking commit. Delegates to [applySuspend] via `runBlocking`. Retained for
     * callers that aren't in a coroutine context (e.g. tests, legacy script code).
     * Hot writer paths should prefer [applySuspend] directly.
     */
    fun apply(): Map<Universe, Map<Int, UByte>> = runBlocking { applySuspend() }

    /**
     * Suspend commit — runs each universe's `setValuesSuspend` concurrently and
     * returns once all have acknowledged. Callers in a coroutine context pay no
     * `runBlocking` cost and can compose this with other suspend work.
     */
    suspend fun applySuspend(): Map<Universe, Map<Int, UByte>> {
        if (universeState.values.any { it.valuesToSet.isNotEmpty() }) {
            coroutineScope {
                for (state in universeState.values) {
                    if (state.valuesToSet.isEmpty()) continue
                    launch { state.controller.setValuesSuspend(state.valuesToSet.toList()) }
                }
            }
        }

        return universeState.mapValues { entry ->
            val staged = entry.value.valuesToSet
            if (staged.isEmpty()) emptyMap() else staged.mapValues { it.value.newValue }
        }
    }

    fun <T: Any> use(block: (it: ControllerTransaction) -> T) {
        block(this)
        apply()
    }
}
