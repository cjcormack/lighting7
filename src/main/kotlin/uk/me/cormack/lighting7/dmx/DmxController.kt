package uk.me.cormack.lighting7.dmx

/**
 * Authoritative source of parked-channel state. Controllers consult a
 * [ParkSource] at transmit time so park overrides survive controller
 * reconstruction (fixture reloads, patch edits) without depending on any
 * explicit re-apply step. The per-controller `_parkedChannels` cache remains
 * as a fallback for tests / call sites that have no central park manager.
 */
interface ParkSource {
    fun getParkedValue(universe: Int, channel: Int): UByte?
    fun isParked(universe: Int, channel: Int): Boolean
}

sealed interface DmxController {
    val universe: Universe

    val currentValues: Map<Int, UByte>

    /**
     * Channels parked at a fixed output value. Parked values override
     * all other sources in the final DMX output.
     */
    val parkedChannels: Map<Int, UByte>

    fun setValues(valuesToSet: List<Pair<Int, ChannelChange>>)

    /**
     * Suspend variant of [setValues]. Sends each channel update and awaits its ack
     * without blocking the calling thread. Prefer this on hot writer paths (FX ticks,
     * surface input, WebSocket bursts) so converging writers don't pile up on a
     * `runBlocking` primitive.
     *
     * The default delegates to the blocking [setValues] for implementations that have
     * no asynchronous work to defer (tests, fakes). The production ArtNet path overrides
     * with a real non-blocking implementation.
     */
    suspend fun setValuesSuspend(valuesToSet: List<Pair<Int, ChannelChange>>) {
        setValues(valuesToSet)
    }

    fun setValue(channelNo: Int, channelChange: ChannelChange)
    fun setValue(channelNo: Int, channelValue: UByte, fadeMs: Long = 0)
    fun getValue(channelNo: Int): UByte

    /**
     * Bulk-replace channel state with a snapshot, bypassing fades and write logs.
     * Used by fixture-reload paths to carry channel values across a controller swap
     * (so patching one fixture doesn't zero every other channel on the universe).
     * Channels absent from the snapshot are left at their current value.
     */
    fun restoreState(values: Map<Int, UByte>)

    /**
     * Park a channel at a fixed value. The parked value will override
     * normal output in the DMX transmission.
     */
    fun parkChannel(channelNo: Int, value: UByte)

    /**
     * Remove a channel's parked value, returning it to normal output.
     */
    fun unparkChannel(channelNo: Int)

    /**
     * Remove all parked channels.
     */
    fun unparkAll()

    /**
     * Register a [TransmitModifier] that will transform channel values at transmit time.
     * Modifiers are applied in registration order, after park resolution.
     *
     * No-op if the modifier is already registered.
     */
    fun addTransmitModifier(modifier: TransmitModifier)

    /**
     * Remove a previously-registered [TransmitModifier]. No-op if not registered.
     */
    fun removeTransmitModifier(modifier: TransmitModifier)

    /**
     * Notify the controller that a transmit modifier's internal state has changed and an
     * immediate re-transmission is needed so the UI / hardware sees the scaled output.
     * Without this hook, modifiers like Blackout would only take effect on the next
     * periodic transmit tick (up to 25 ms delay).
     */
    fun requestTransmit()
}
