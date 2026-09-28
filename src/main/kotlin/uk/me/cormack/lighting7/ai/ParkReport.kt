package uk.me.cormack.lighting7.ai

import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State

/**
 * One parked DMX channel as the model-facing surfaces report it: the address and held value, plus
 * the fixture channel it drives when the address is patched. A model parks by address but reasons
 * about fixtures, so a bare "universe 0 channel 7 at 255" would make it re-derive the patch.
 */
internal data class ParkedChannelReport(
    val universe: Int,
    val channel: Int,
    val value: Int,
    /** Null when nothing patched drives this address. */
    val mapping: Fixtures.ChannelMapping?,
)

/** Every parked channel in the current show, in address order. */
internal fun parkedChannelReports(state: State): List<ParkedChannelReport> {
    val mappings = state.show.fixtures.getChannelMappings()
    return state.show.parkManager.getAllParked()
        .sortedWith(compareBy({ it.universe }, { it.channel }))
        .map { ParkedChannelReport(it.universe, it.channel, it.value.toInt(), mappings[it.universe]?.get(it.channel)) }
}

/** What a patched address drives, or null when unpatched: the channel-mapping entry for it. */
internal fun channelMappingAt(state: State, universe: Int, channel: Int): Fixtures.ChannelMapping? =
    state.show.fixtures.getChannelMappings()[universe]?.get(channel)
