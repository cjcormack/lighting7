package uk.me.cormack.lighting7.dmx

interface IChannelChangeListener {
    fun channelsChanged(changes: Map<Int, UByte>)
}
