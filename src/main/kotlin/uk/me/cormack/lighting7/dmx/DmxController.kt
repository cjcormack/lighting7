package uk.me.cormack.lighting7.dmx

sealed interface DmxController {
    val universe: Universe

    val currentValues: Map<Int, UByte>

    /**
     * Channels parked at a fixed output value. Parked values override
     * all other sources in the final DMX output.
     */
    val parkedChannels: Map<Int, UByte>

    fun setValues(valuesToSet: List<Pair<Int, ChannelChange>>)
    fun setValue(channelNo: Int, channelChange: ChannelChange)
    fun setValue(channelNo: Int, channelValue: UByte, fadeMs: Long = 0)
    fun getValue(channelNo: Int): UByte

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
}
