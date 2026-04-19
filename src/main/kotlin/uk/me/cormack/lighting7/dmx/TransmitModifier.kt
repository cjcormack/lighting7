package uk.me.cormack.lighting7.dmx

/**
 * Transmit-time hook into [DmxController] output. Implementations get a chance to transform
 * each channel's value right before it goes out on the wire.
 *
 * Modifiers fire **after** park resolution — parked channels output their literal parked
 * value regardless of what modifiers say, matching the console convention where park is
 * the highest-priority output layer.
 *
 * Used by [uk.me.cormack.lighting7.midi.GlobalScalerState] for Blackout / Grand Master.
 * The park pattern in [ArtNetController] is a hard-coded built-in; modifiers are the
 * extensible sibling for layered transforms that *should* interact with upstream state
 * (fixtures, bindings, etc.) rather than just an opaque channel map.
 *
 * Thread-safety: modifiers are invoked from the ArtNet transmission thread. Implementations
 * must be non-blocking and either stateless or internally thread-safe. The [universe] +
 * [channel] pair is 1-indexed (DMX convention).
 */
fun interface TransmitModifier {
    /**
     * Transform the given channel value. Return the input unchanged for a no-op.
     *
     * Called on every channel, every frame. Must be cheap.
     */
    fun modify(universe: Universe, channel: Int, value: UByte): UByte
}
