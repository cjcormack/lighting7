package uk.me.cormack.lighting7.dmx

/**
 * Mock DMX controller for testing.
 * Stores channel values in memory without sending to any hardware.
 */
class MockDmxController(
    override val universe: Universe = Universe(0, 0),
    private val parkSource: ParkSource? = null,
) : DmxController {
    private val values = mutableMapOf<Int, UByte>()
    private val _parkedChannels = mutableMapOf<Int, UByte>()
    private val _transmitModifiers = mutableListOf<TransmitModifier>()

    /** Modifiers registered on this controller. Mock lets tests inspect them without forcing transmit. */
    val transmitModifiers: List<TransmitModifier> get() = _transmitModifiers.toList()

    var transmitRequests: Int = 0
        private set

    private val _writeLog = mutableListOf<Pair<Int, UByte>>()

    /**
     * Ordered log of every (channel, value) write the controller has received since the last
     * [reset]. Lets tests assert that no intermediate flash value appeared between two
     * expected endpoints — e.g. a crossfade-start republish that writes the outgoing value
     * without first writing the incoming value.
     */
    val writeLog: List<Pair<Int, UByte>> get() = _writeLog.toList()

    /** Returns the writes to [channelNo] only, in order. */
    fun writesTo(channelNo: Int): List<UByte> = _writeLog.filter { it.first == channelNo }.map { it.second }

    override val currentValues: Map<Int, UByte>
        get() = values.toMap()

    override val parkedChannels: Map<Int, UByte>
        get() = _parkedChannels.toMap()

    override fun setValues(valuesToSet: List<Pair<Int, ChannelChange>>) {
        valuesToSet.forEach { (channel, change) ->
            values[channel] = change.newValue
            _writeLog.add(channel to change.newValue)
        }
    }

    override fun setValue(channelNo: Int, channelChange: ChannelChange) {
        values[channelNo] = channelChange.newValue
        _writeLog.add(channelNo to channelChange.newValue)
    }

    override fun setValue(channelNo: Int, channelValue: UByte, fadeMs: Long) {
        values[channelNo] = channelValue
        _writeLog.add(channelNo to channelValue)
    }

    override fun getValue(channelNo: Int): UByte {
        return parkSource?.getParkedValue(universe.universe, channelNo)
            ?: _parkedChannels[channelNo]
            ?: values[channelNo]
            ?: 0u
    }

    override fun restoreState(values: Map<Int, UByte>) {
        for ((channelNo, value) in values) {
            this.values[channelNo] = value
        }
    }

    override fun parkChannel(channelNo: Int, value: UByte) {
        _parkedChannels[channelNo] = value
    }

    override fun unparkChannel(channelNo: Int) {
        _parkedChannels.remove(channelNo)
    }

    override fun unparkAll() {
        _parkedChannels.clear()
    }

    override fun addTransmitModifier(modifier: TransmitModifier) {
        if (!_transmitModifiers.contains(modifier)) _transmitModifiers.add(modifier)
    }

    override fun removeTransmitModifier(modifier: TransmitModifier) {
        _transmitModifiers.remove(modifier)
    }

    override fun requestTransmit() {
        transmitRequests++
    }

    /**
     * Get the effective output value for a channel, applying modifiers. For test assertions.
     * Park takes absolute precedence (ParkSource first, then per-controller cache); then
     * modifiers run over the raw value in registration order.
     */
    fun getEffectiveValue(channelNo: Int): UByte {
        parkSource?.getParkedValue(universe.universe, channelNo)?.let { return it }
        _parkedChannels[channelNo]?.let { return it }
        var v = values[channelNo] ?: 0u
        for (mod in _transmitModifiers) v = mod.modify(universe, channelNo, v)
        return v
    }

    /**
     * Reset all channels to 0.
     */
    fun reset() {
        values.clear()
        _parkedChannels.clear()
        _transmitModifiers.clear()
        _writeLog.clear()
        transmitRequests = 0
    }
}
