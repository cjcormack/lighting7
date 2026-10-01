package uk.me.cormack.lighting7.state

import uk.me.cormack.lighting7.dmx.ParkSource
import uk.me.cormack.lighting7.fixture.FixtureTriggers
import uk.me.cormack.lighting7.fixture.ResolvedTrigger
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.show.FixturesChangeListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Who owns every one-shot trigger channel and every arm channel on the patched rig, and what each is
 * putting out (stage-view plan session 9, D15, D16). One per show.
 *
 * **The desk owns these channels outright.** Nothing composes them — a trigger is not a property —
 * and nothing below may raise one: a direct channel write, a stale value carried across a reload, a
 * script poking the buffer. So this holds every one of them at a value of its own, **above
 * composition and under park**, through the controllers' [ParkSource] (`dmx/LayeredParkSource.kt`):
 *
 * - a trigger channel at idle, except for the one backend-timed pulse a fire raises it for;
 * - an arm channel at the desk's arm (`EffectsService` decides it: armed and not rehearsing).
 *
 * A park still wins — the operator's hand on the channel is the one thing above this — which is why
 * a park at or above the fire threshold is refused on these channels (`ParkSocket`, `park_channel`).
 *
 * The per-universe view the transmit loop reads is rebuilt on every change, never per frame: it is a
 * handful of channels, and the loop reads it 40 times a second per universe.
 */
class TriggerOutput(private val fixtures: Fixtures) : ParkSource, FixturesChangeListener {

    /** One channel this owns: the trigger it belongs to, and whether it is that trigger's arm. */
    data class Owned(val fixtureKey: String, val fixtureName: String, val trigger: ResolvedTrigger, val arm: Boolean)

    private val lock = Any()

    @Volatile
    private var armHigh = false

    /** (fixture key, trigger name) of every pulse in flight. */
    private val pulsing = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    @Volatile
    private var views: Map<Int, Map<Int, UByte>> = emptyMap()

    @Volatile
    private var owned: Map<Long, Owned> = emptyMap()

    init {
        rebuild()
    }

    // ─── ParkSource ──────────────────────────────────────────────────────────────────────────

    override fun getParkedValue(universe: Int, channel: Int): UByte? = views[universe]?.get(channel)

    /** Held, never parked: a trigger channel is not the operator's park. */
    override fun isParked(universe: Int, channel: Int): Boolean = false

    override fun universeView(universe: Int): Map<Int, UByte>? = views[universe]

    // ─── What the effects service drives ─────────────────────────────────────────────────────

    /** Raise or drop every arm channel. */
    fun setArmHigh(high: Boolean) {
        if (armHigh == high) return
        armHigh = high
        rebuild()
    }

    /** Raise [trigger] on [fixtureKey] until [endPulse]. False when this show has no such trigger. */
    fun startPulse(fixtureKey: String, trigger: String): Boolean {
        if (owned.values.none { it.fixtureKey == fixtureKey && it.trigger.name == trigger && !it.arm }) return false
        pulsing += fixtureKey to trigger
        rebuild()
        return true
    }

    fun endPulse(fixtureKey: String, trigger: String) {
        if (pulsing.remove(fixtureKey to trigger)) rebuild()
    }

    /** Drop every pulse in flight — a disarm is a stop. */
    fun endAllPulses() {
        if (pulsing.isEmpty()) return
        pulsing.clear()
        rebuild()
    }

    /** Which trigger owns this channel, if any — what a raw channel write and a park are checked against. */
    fun ownerOf(universe: Int, channel: Int): Owned? = owned[key(universe, channel)]

    /**
     * Whether a park of [value] on this channel may reach the wire — the output layer's half of
     * [parkRefusal], read per channel at transmit time ([uk.me.cormack.lighting7.dmx.LayeredParkSource]).
     * A live park write is refused before it is stored; this catches the parks that never met that
     * check: rows stored while the Twin Shot's channels were sliders, a sync import, a clone, or a
     * cannon patched onto channels already parked.
     */
    fun admitsPark(universe: Int, channel: Int, value: UByte): Boolean =
        value < FixtureTriggers.FIRE_THRESHOLD || ownerOf(universe, channel) == null

    /**
     * Why a park of [value] on this channel is refused, or null. A park beats the trigger output, so a
     * park at or above the fire threshold on a trigger channel *is* a fire, held — and on an arm it is
     * an arm the desk cannot drop. Below the threshold it is a lock-out that holds the channel idle,
     * which is the one park worth having here, so it stays allowed.
     */
    fun parkRefusal(universe: Int, channel: Int, value: UByte): String? {
        val owner = ownerOf(universe, channel) ?: return null
        if (value < FixtureTriggers.FIRE_THRESHOLD) return null
        val what = if (owner.arm) "the arm of '${owner.fixtureName}'s triggers" else "'${owner.trigger.spec.description}' on '${owner.fixtureName}', a one-shot trigger"
        return "Universe $universe channel $channel is $what: a park at $value would hold it live. " +
            "Park it below ${FixtureTriggers.FIRE_THRESHOLD} to lock it out, or fire it from the cannon's panel."
    }

    /** Every trigger on the rig, by fixture key — the cannon panels and a cue event's lookup. */
    fun triggersOf(fixtureKey: String): List<ResolvedTrigger> =
        owned.values.filter { it.fixtureKey == fixtureKey && !it.arm }.map { it.trigger }.distinctBy { it.name }

    // ─── Rebuild ─────────────────────────────────────────────────────────────────────────────

    override fun fixturesChanged() = rebuild()

    private fun rebuild() {
        synchronized(lock) {
            val nextViews = HashMap<Int, HashMap<Int, UByte>>()
            val nextOwned = HashMap<Long, Owned>()
            val armValue = if (armHigh) FixtureTriggers.ARM_LEVEL else FixtureTriggers.IDLE_LEVEL
            for (fixture in fixtures.fixtures) {
                for (t in FixtureTriggers.of(fixture)) {
                    val channels = nextViews.getOrPut(t.universe) { HashMap() }
                    val fire = (fixture.key to t.name) in pulsing
                    channels[t.channelNo] = if (fire) FixtureTriggers.FIRE_LEVEL else FixtureTriggers.IDLE_LEVEL
                    channels[t.armChannelNo] = armValue
                    nextOwned[key(t.universe, t.channelNo)] = Owned(fixture.key, fixture.fixtureName, t, arm = false)
                    nextOwned.putIfAbsent(key(t.universe, t.armChannelNo), Owned(fixture.key, fixture.fixtureName, t, arm = true))
                }
            }
            // A pulse whose fixture went (a repatch mid-pulse) has nothing left to end.
            pulsing.removeIf { (k, name) -> nextOwned.values.none { it.fixtureKey == k && it.trigger.name == name } }
            owned = nextOwned
            views = nextViews
        }
    }

    private fun key(universe: Int, channel: Int): Long = (universe.toLong() shl 32) or channel.toLong()
}
