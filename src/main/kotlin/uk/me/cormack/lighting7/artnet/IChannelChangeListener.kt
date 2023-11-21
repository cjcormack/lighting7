package uk.me.cormack.lighting7.artnet

interface IChannelChangeListener {
    fun channelsChanged(changes: Map<Int, UByte>)
}
