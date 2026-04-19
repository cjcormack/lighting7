package uk.me.cormack.lighting7.dmx

/**
 * Pack `(universe, channel)` into a single `Long` for allocation-free lookup keys in hot-path
 * maps. Universe numbers occupy the high bits (15-bit ArtNet range); the 20-bit channel slot
 * is room to spare for 1..512 DMX channels plus headroom for subnet if we ever need it.
 *
 * All channel-indexed lookup maps that live on the transmit / MIDI hot paths use this encoding
 * so that callers can share indexes without re-implementing the packing contract:
 * [uk.me.cormack.lighting7.fx.DirectWriteStore], [uk.me.cormack.lighting7.midi.GlobalScalerState],
 * [uk.me.cormack.lighting7.midi.SurfaceFeedbackPublisher].
 */
fun packChannelKey(universe: Int, channel: Int): Long =
    (universe.toLong() shl 20) or (channel.toLong() and 0xFFFFFL)
