package uk.me.cormack.lighting7.dmx

/**
 * Two [ParkSource]s as one: [top] wins on any channel it holds, [under] answers the rest. What the
 * controllers are given in place of the bare [ParkManager], so that a second owner can hold
 * channels above composition without becoming a park.
 *
 * Today that owner is the one-shot trigger output (`state/TriggerOutput.kt`, stage-view plan
 * session 9), **under** park: a park is the operator's hand on the channel and beats everything,
 * a fire included — a cannon parked at 0 is locked out.
 */
class LayeredParkSource(
    private val top: ParkSource,
    private val under: ParkSource,
    /**
     * Whether [top]'s value on a channel may stand. One [under] refuses is passed over as if [top]
     * held nothing there: a park at a firing level on a trigger or arm channel would be a fire, or
     * an arm, the desk could not drop (`TriggerOutput.admitsPark`).
     */
    private val admitTop: (universe: Int, channel: Int, value: UByte) -> Boolean = { _, _, _ -> true },
) : ParkSource {
    override fun getParkedValue(universe: Int, channel: Int): UByte? =
        top.getParkedValue(universe, channel)?.takeIf { admitTop(universe, channel, it) }
            ?: under.getParkedValue(universe, channel)

    /** Only [top]'s parks are parks: [under] holds channels, it does not park them. */
    override fun isParked(universe: Int, channel: Int): Boolean =
        top.isParked(universe, channel) &&
            top.getParkedValue(universe, channel)?.let { admitTop(universe, channel, it) } != false

    /**
     * One lookup per frame, as [ParkSource.universeView] promises: either side alone when the other
     * holds nothing on [universe], otherwise a view that asks [top] then [under] per channel. The
     * transmit loop only ever calls `get`, so the view is a thin read-through, not a copy.
     */
    override fun universeView(universe: Int): Map<Int, UByte>? {
        val t = top.universeView(universe)
        val u = under.universeView(universe)
        return when {
            u.isNullOrEmpty() -> t
            t.isNullOrEmpty() -> u
            else -> Layered(universe, t, u, admitTop)
        }
    }

    private class Layered(
        private val universe: Int,
        private val t: Map<Int, UByte>,
        private val u: Map<Int, UByte>,
        private val admit: (Int, Int, UByte) -> Boolean,
    ) : AbstractMap<Int, UByte>() {
        private fun topAt(key: Int): UByte? = t[key]?.takeIf { admit(universe, key, it) }
        override fun get(key: Int): UByte? = topAt(key) ?: u[key]
        override fun containsKey(key: Int): Boolean = topAt(key) != null || u.containsKey(key)
        override val entries: Set<Map.Entry<Int, UByte>>
            get() = (u + t.filter { (k, v) -> admit(universe, k, v) }).entries
    }
}
