package uk.me.cormack.lighting7.dmx

/**
 * The last word on what a controller puts on the wire (venue plan P5, D7; session 6): a show's
 * **hold**, and the channels a hold leaves **reserved**. One per show, shared by every controller the
 * show's loader builds, so the hold engages on every universe in one write.
 *
 * - **Held** — a show on a venue whose pinned version is behind the installed one *on output*: its
 *   addresses would drive whatever the hall has hung there since. Every controller then sends zero on
 *   every channel **but a parked one**: a park is the operator's hand on the channel — often a
 *   hard-powered fixture's dimmer held up — and zeroing it would power-cycle the fixture because its
 *   show opened behind its venue. A parked channel goes out at its park (after the reserve below).
 *   Earlier the hold sat above park, on the argument that a v3 park on a v5 rig is as wrong as a v3
 *   cue; the operator chose otherwise. Nothing else changes: the show composes
 *   as normal, and a controller still reports the value it would have sent to its channel listeners,
 *   so the Stage view, the channel views and Preview keep drawing what the show is doing.
 * - **Reserved** — while the show *runs against* its older pin (the hold lifted for the session), the
 *   installed version's one-shot trigger, arm and fixture-command channels are held idle whatever the
 *   old patch writes there: a v3 dimmer patched where v5 hung the cannon must not fire it. A dedicated
 *   channel goes out at its idle level; a command channel the installed rig shares with a property (a
 *   MAC 250's shutter) goes out at idle only while the value is in a command's band. This one *is*
 *   what the wire carries, so the listeners see it too.
 *
 * Read by the transmit loop once per frame per universe ([isHeld], [reservedView]); written rarely
 * (a load, *Run against*, an Update), each write a whole replacement. Universe numbers are the
 * controllers' `universe.universe`, as park's are.
 */
class OutputHold {

    /** What a reserved channel goes out as. */
    sealed interface Reserve {
        fun guard(value: UByte): UByte

        /** Always [idle]: a trigger, an arm, a dedicated command channel. */
        data class Fixed(val idle: UByte) : Reserve {
            override fun guard(value: UByte): UByte = idle
        }

        /** [idle] while the value falls in one of [bands]; anything else passes. */
        data class Band(val bands: List<UIntRange>, val idle: UByte) : Reserve {
            override fun guard(value: UByte): UByte = if (bands.any { value.toUInt() in it }) idle else value
        }
    }

    @Volatile
    private var held: Boolean = false

    /** Per universe, indexed by channel (1..512); a channel with no entry is not reserved. */
    @Volatile
    private var reserved: Map<Int, Array<Reserve?>> = emptyMap()

    /** Whether every controller sends zero on every channel but a parked one. */
    val isHeld: Boolean get() = held

    fun setHeld(value: Boolean) {
        held = value
    }

    /** Replace every reserved channel at once: universe → channel → what it goes out as. */
    fun setReserved(channels: Map<Int, Map<Int, Reserve>>) {
        reserved = channels.filterValues { it.isNotEmpty() }.mapValues { (_, byChannel) ->
            arrayOfNulls<Reserve>(513).also { array ->
                byChannel.forEach { (channel, reserve) -> if (channel in 1..512) array[channel] = reserve }
            }
        }
    }

    /** Every reserved channel, by universe: what a test, a log or a later window reads. */
    fun reservedChannels(): Map<Int, Map<Int, Reserve>> = reserved.mapValues { (_, array) ->
        array.withIndex().mapNotNull { (channel, reserve) -> reserve?.let { channel to it } }.toMap()
    }

    /** One lookup per frame: [universe]'s reserved channels, or null when it has none. */
    fun reservedView(universe: Int): Array<Reserve?>? = reserved[universe]

    /** [value] on [universe] [channel] after the reserve — what the wire carries unless [isHeld] and the channel is not parked. */
    fun guard(universe: Int, channel: Int, value: UByte): UByte =
        reserved[universe]?.getOrNull(channel)?.guard(value) ?: value
}
