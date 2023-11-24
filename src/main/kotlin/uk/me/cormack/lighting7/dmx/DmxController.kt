package uk.me.cormack.lighting7.dmx

sealed interface DmxController {
    val subnet: Int
    val universe: Int

    val currentValues: Map<Int, UByte>

    fun setValues(valuesToSet: List<Pair<Int, ChannelChange>>)
    fun setValue(channelNo: Int, channelChange: ChannelChange)
    fun setValue(channelNo: Int, channelValue: UByte, fadeMs: Long = 0)
    fun getValue(channelNo: Int): UByte
}
