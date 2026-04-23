package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ControllerTransaction(controllers: List<DmxController>) {
    private data class UniverseState(
        val controller: DmxController,
        val currentValues: MutableMap<Int, UByte>,
        val valuesToSet: MutableMap<Int, ChannelChange>,
    )

    private val universeState = controllers.associate {
        it.universe to UniverseState(it, it.currentValues.toMutableMap(), mutableMapOf())
    }

    fun setValues(universe: Universe, valuesToSet: List<Pair<Int, ChannelChange>>) {
        valuesToSet.forEach {
            setValue(universe, it.first, it.second)
        }
    }

    fun setValue(universe: Universe, channelNo: Int, channelChange: ChannelChange) {
        checkNotNull(universeState[universe]).valuesToSet[channelNo] = channelChange
        checkNotNull(universeState[universe]).currentValues[channelNo] = channelChange.newValue
    }

    fun setValue(universe: Universe, channelNo: Int, channelValue: UByte, fadeMs: Long = 0L) {
        setValue(universe, channelNo, ChannelChange(channelValue, fadeMs))
    }

    fun getValue(universe: Universe, channelNo: Int): UByte {
        return checkNotNull(universeState[universe]).currentValues[channelNo] ?: 0u
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
        val pending = universeState.values.filter { it.valuesToSet.isNotEmpty() }
        if (pending.isNotEmpty()) {
            coroutineScope {
                for (state in pending) {
                    launch { state.controller.setValuesSuspend(state.valuesToSet.toList()) }
                }
            }
        }

        return universeState.mapValues { it.value.valuesToSet.mapValues { valueToSet -> valueToSet.value.newValue } }
    }

    fun <T: Any> use(block: (it: ControllerTransaction) -> T) {
        block(this)
        apply()
    }
}
