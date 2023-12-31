package uk.me.cormack.lighting7.dmx

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

    fun apply(): Map<Universe, Map<Int, UByte>> {
        universeState.filterValues { it.valuesToSet.isNotEmpty() }.forEach { (_, state) ->
            state.controller.setValues(state.valuesToSet.toList())
        }

        return universeState.mapValues { it.value.valuesToSet.mapValues { valueToSet -> valueToSet.value.newValue } }
    }

    fun <T: Any> use(block: (it: ControllerTransaction) -> T) {
        block(this)
        apply()
    }
}
