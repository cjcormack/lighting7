package uk.me.cormack.lighting7.dmx

/**
 * Mock DMX controller for testing.
 * Stores channel values in memory without sending to any hardware.
 */
class MockDmxController(
    override val universe: Universe = Universe(0, 0)
) : DmxController {
    private val values = mutableMapOf<Int, UByte>()

    override val currentValues: Map<Int, UByte>
        get() = values.toMap()

    override fun setValues(valuesToSet: List<Pair<Int, ChannelChange>>) {
        valuesToSet.forEach { (channel, change) ->
            values[channel] = change.newValue
        }
    }

    override fun setValue(channelNo: Int, channelChange: ChannelChange) {
        values[channelNo] = channelChange.newValue
    }

    override fun setValue(channelNo: Int, channelValue: UByte, fadeMs: Long) {
        values[channelNo] = channelValue
    }

    override fun getValue(channelNo: Int): UByte = values[channelNo] ?: 0u

    /**
     * Reset all channels to 0.
     */
    fun reset() {
        values.clear()
    }
}
