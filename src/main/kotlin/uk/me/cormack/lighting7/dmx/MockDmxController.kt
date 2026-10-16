package uk.me.cormack.lighting7.dmx

/**
 * Mock DMX controller for testing.
 * Stores channel values in memory without sending to any hardware.
 */
class MockDmxController(
    override val universe: Universe = Universe(0, 0),
    private val parkSource: ParkSource? = null,
    /** The show's hold (venue plan P5); see [transmittedValue]. */
    private val hold: OutputHold? = null,
) : DmxController {
    private val values = mutableMapOf<Int, UByte>()
    private val _transmitModifiers = mutableListOf<TransmitModifier>()

    /** Modifiers registered on this controller. Mock lets tests inspect them without forcing transmit. */
    val transmitModifiers: List<TransmitModifier> get() = _transmitModifiers.toList()

    // Both logs are appended from whatever thread writes — a GO's, the FX tick's, a crossfade's —
    // while a test reads them from its own, so every access holds the list's monitor.
    private val _writeLog = mutableListOf<Pair<Int, UByte>>()

    /**
     * Ordered log of every (channel, value) write the controller has received since the last
     * [reset]. Lets tests assert that no intermediate flash value appeared between two
     * expected endpoints — e.g. a crossfade-start republish that writes the outgoing value
     * without first writing the incoming value.
     */
    val writeLog: List<Pair<Int, UByte>> get() = synchronized(_writeLog) { _writeLog.toList() }

    /** Returns the writes to [channelNo] only, in order. */
    fun writesTo(channelNo: Int): List<UByte> = writeLog.filter { it.first == channelNo }.map { it.second }

    private val _changeLog = mutableListOf<Pair<Int, ChannelChange>>()

    /**
     * Ordered log of every (channel, [ChannelChange]) the controller has received —
     * unlike [writeLog] this keeps the change's `fadeMs`, so tests can assert that a
     * publish requested a ramp rather than a snap.
     */
    val changeLog: List<Pair<Int, ChannelChange>> get() = synchronized(_changeLog) { _changeLog.toList() }

    /** Returns the [ChannelChange]s for [channelNo] only, in order. */
    fun changesTo(channelNo: Int): List<ChannelChange> =
        changeLog.filter { it.first == channelNo }.map { it.second }

    override val currentValues: Map<Int, UByte>
        get() = values.toMap()

    override val held: Boolean get() = hold?.isHeld == true

    override fun setValues(valuesToSet: List<Pair<Int, ChannelChange>>) {
        valuesToSet.forEach { (channel, change) ->
            values[channel] = change.newValue
            log(channel, change)
        }
    }

    override fun setValue(channelNo: Int, channelChange: ChannelChange) {
        values[channelNo] = channelChange.newValue
        log(channelNo, channelChange)
    }

    private fun log(channelNo: Int, change: ChannelChange) {
        synchronized(_writeLog) { _writeLog.add(channelNo to change.newValue) }
        synchronized(_changeLog) { _changeLog.add(channelNo to change) }
    }

    override fun setValue(channelNo: Int, channelValue: UByte, fadeMs: Long) {
        values[channelNo] = channelValue
        log(channelNo, ChannelChange(channelValue, fadeMs))
    }

    override fun getValue(channelNo: Int): UByte {
        return parkSource?.getParkedValue(universe.universe, channelNo)
            ?: values[channelNo]
            ?: 0u
    }

    override fun restoreState(values: Map<Int, UByte>) {
        for ((channelNo, value) in values) {
            this.values[channelNo] = value
        }
    }

    override fun addTransmitModifier(modifier: TransmitModifier) {
        if (!_transmitModifiers.contains(modifier)) _transmitModifiers.add(modifier)
    }

    override fun removeTransmitModifier(modifier: TransmitModifier) {
        _transmitModifiers.remove(modifier)
    }

    /**
     * Get the effective output value for a channel, applying modifiers. For test assertions.
     * Park takes absolute precedence via the [ParkSource]; then modifiers run over the raw
     * value in registration order; then a reserved channel of the show's [OutputHold] is guarded.
     * Not the hold itself: this is what the channel listeners would hear ([transmittedValue] is the wire).
     */
    fun getEffectiveValue(channelNo: Int): UByte = effectiveValueOf(channelNo, values[channelNo] ?: 0u)

    private fun effectiveValueOf(channelNo: Int, buffered: UByte): UByte {
        val composed = parkSource?.getParkedValue(universe.universe, channelNo) ?: run {
            var v = buffered
            for (mod in _transmitModifiers) v = mod.modify(universe, channelNo, v)
            v
        }
        return hold?.guard(universe.universe, channelNo, composed) ?: composed
    }

    /**
     * What [ArtNetController] would put on the wire for this channel: [getEffectiveValue] (from the
     * [frozen] frame once there is one), or zero while the show is held (venue plan P5) — unless the
     * channel is parked, which gets through the hold. For test assertions.
     */
    fun transmittedValue(channelNo: Int): UByte {
        val frame = frozen
        val composed = if (frame == null) getEffectiveValue(channelNo) else effectiveValueOf(channelNo, frame[channelNo] ?: 0u)
        return when {
            !held -> composed
            parkSource?.isParked(universe.universe, channelNo) == true -> composed
            else -> 0u
        }
    }

    /** The frame [freeze] pinned, or null: what a test reads to see what a teardown left on the wire. */
    var frozen: Map<Int, UByte>? = null
        private set

    override fun freeze(values: Map<Int, UByte>?) {
        frozen = values?.toMap()
    }

    /**
     * Reset all channels to 0.
     */
    fun reset() {
        values.clear()
        _transmitModifiers.clear()
        synchronized(_writeLog) { _writeLog.clear() }
    }
}
