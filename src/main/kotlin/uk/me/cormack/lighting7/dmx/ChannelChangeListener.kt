package uk.me.cormack.lighting7.dmx

interface ChannelChangeListener {
    fun channelsChanged(changes: Map<Int, UByte>)
}
