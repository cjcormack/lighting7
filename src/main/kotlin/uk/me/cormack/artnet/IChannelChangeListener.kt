package uk.me.cormack.artnet

interface IChannelChangeListener {
    fun channelsChanged(changes: Map<Int, UByte>)
}
