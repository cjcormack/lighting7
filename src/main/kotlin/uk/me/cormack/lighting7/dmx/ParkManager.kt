package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.models.DaoParkedChannel
import uk.me.cormack.lighting7.models.DaoParkedChannels
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoShowParkRelease
import uk.me.cormack.lighting7.models.DaoShowParkReleases
import uk.me.cormack.lighting7.models.ParkAddress
import uk.me.cormack.lighting7.models.RigOwner
import uk.me.cormack.lighting7.models.VenueParks
import java.util.concurrent.ConcurrentHashMap

/**
 * One parked channel as the rig sees it: its address, its value, and whose park it is — the
 * project's own ([RigOwner.SHOW], which in a venue is the venue's draft), or the pinned venue
 * version's ([RigOwner.VENUE], inherited by a show on that venue). [venueValue] is the venue's park
 * under a show's own on the same channel (a re-park at another value), which unparking the show's
 * brings back; null when there is none.
 */
data class ParkedChannel(
    val universe: Int,
    val channel: Int,
    val value: UByte,
    val owner: RigOwner = RigOwner.SHOW,
    val venueValue: UByte? = null,
)

/**
 * A venue park a show on that venue has **released** for its run: the channel is not parked in this
 * show. [venueValue] is the pinned version's park on it — what restoring it brings back — or null when
 * the version parks nothing there (the release is dormant).
 */
data class ReleasedPark(
    val universe: Int,
    val channel: Int,
    val venueValue: UByte?,
)

/** The whole park list a client draws: the effective parks, and the venue parks the show released. */
data class ParkSnapshot(
    val parked: List<ParkedChannel> = emptyList(),
    val released: List<ReleasedPark> = emptyList(),
)

/** What an [ParkManager.unpark] did to its channel. */
enum class UnparkOutcome {
    /** Nothing was parked there. */
    NOT_PARKED,
    /** The channel is no longer parked: its value was handed down to the layers below. */
    UNPARKED,
    /** The show's own park went and the venue's park on the channel stands again. */
    VENUE_RESTORED,
    /** A venue park was released in this show: its value was handed down, and a release stored. */
    RELEASED,
}

/**
 * Hand-off sink invoked when park is released on a channel.
 *
 * Park sits above every other layer, so the value underneath it is whatever the
 * layers below happened to leave there — usually 0, or a stale value from before the
 * channel was parked. Dropping the override without doing anything else therefore
 * snaps the output from the parked value to that unrelated one. On a hard-powered
 * fixture hung off a dimmer that snap is the hazard park exists to prevent, so the
 * parked value is written *down* into the layers below before the override goes away:
 * unparking hands control back at the value the rig is already emitting, and
 * park → unpark → park is a no-op on the wire.
 *
 * Implementations must make the handed-off value the resolved output of the layers
 * below park — in production that means both the direct-write store (Layer 2) and the
 * controller's channel buffer, i.e. exactly what a manual `updateChannel` would do.
 */
fun interface UnparkValueSink {
    suspend fun handOff(values: List<ParkedChannel>)
}

/**
 * Manages parked DMX channels — channels locked at a fixed output value
 * that overrides all other sources (scenes, scripts, effects, manual control).
 *
 * Park is the highest-priority output layer, matching the behaviour of
 * professional lighting consoles (ETC Eos, ChamSys MagicQ, grandMA3).
 *
 * **Whose parks.** A project's parks are its own `parked_channels` rows — and, for a show on a
 * venue, its pinned version's parks too, overlaid by its own and less its releases ([VenueParks]).
 * Every write here is the project's own: a park or a re-park writes the project's row (and drops a
 * release on the channel — one word per channel), and unparking a **venue** park in a show stores a
 * release rather than touching the version, which is read-only. Unparking the show's own park over a
 * venue park brings the venue's back: the channel goes from one park to the other, so nothing is
 * handed down. Parks stay channel-level, never following a fixture.
 */
class ParkManager(
    private val database: Database,
    private val projectId: Int,
    private val unparkValueSink: UnparkValueSink? = null,
) : ParkSource {
    // The effective parks, the transmit loop's view: universe -> (channel -> value). Recomputed per
    // channel from the three layers below on every write, under [lock].
    private val parkedChannels = ConcurrentHashMap<Int, ConcurrentHashMap<Int, UByte>>()

    // The layers. [own] is the project's rows; [venue] the pinned version's (empty for a project on no
    // venue); [releases] the show's releases. [suppressed] holds venue parks [forget] dropped: a
    // version is read-only, so a refused venue park is left out in memory only, for this show's life —
    // and stays out whatever the show parks or restores on the channel, since it is still refused.
    private val own = HashMap<ParkAddress, UByte>()
    private var venue: Map<ParkAddress, UByte> = emptyMap()
    private var onVenue = false
    private val releases = HashSet<ParkAddress>()
    private val suppressed = HashSet<ParkAddress>()
    private val lock = Any()

    // Flow for notifying WebSocket clients of park state changes. A [MutableStateFlow] rather
    // than a replay-1 [kotlinx.coroutines.flow.MutableSharedFlow] so that "the park list is
    // empty" is a value a subscriber can observe, not the absence of one — that is what makes a
    // new WebSocket connection's park snapshot unconditional (see the one-snapshot rule in
    // docs/websocket-engineering.md) instead of contingent on something having parked first.
    private val _parkStateFlow = MutableStateFlow(ParkSnapshot())
    val parkStateFlow: StateFlow<ParkSnapshot> = _parkStateFlow.asStateFlow()

    /** Whether this project is a show on a venue, whose venue parks it may release. */
    val isOnVenue: Boolean get() = synchronized(lock) { onVenue }

    /**
     * Load the project's parks from the database — its own, and a show on a venue's pinned version's
     * and releases. Call once after construction.
     */
    fun loadFromDatabase() {
        val layers = transaction(database) { VenueParks.of(projectId) }
        synchronized(lock) {
            own.clear(); own.putAll(layers.own)
            venue = layers.venue.orEmpty()
            onVenue = layers.onVenue
            releases.clear(); releases.addAll(layers.releases)
            suppressed.clear()
            parkedChannels.clear()
            (own.keys + venue.keys).forEach(::recompute)
        }
        emitState()
    }

    /**
     * Park a channel at the given value: the project's own park, which in a show on a venue replaces
     * the venue's value on the channel (and any release of it) for this show.
     */
    suspend fun park(universe: Int, channel: Int, value: UByte) {
        require(channel in 1..512) { "Channel must be between 1 and 512, got $channel" }
        val address = ParkAddress(universe, channel)

        // Persist first, then publish: the row and its release go in one transaction.
        transaction(database) {
            val existing = ownRow(address)
            if (existing != null) {
                existing.value = value.toInt()
            } else {
                DaoParkedChannel.new {
                    this.project = DaoProject.findById(projectId)!!
                    this.universe = universe
                    this.channel = channel
                    this.value = value.toInt()
                }
            }
            releaseRow(address)?.delete()
        }
        synchronized(lock) {
            own[address] = value
            releases.remove(address)
            recompute(address)
        }

        emitState()
    }

    /**
     * Unpark a channel, leaving the output where it was — or, where the show's own park sat over a
     * venue park, going back to the venue's.
     *
     * - The project's own park, with no venue park standing under it: the parked value is handed down
     *   to the layers below park *before* the override is removed, so no transmit frame can land in
     *   the window where park is gone but the old value underneath is still in place
     *   ([UnparkValueSink]). [UnparkOutcome.UNPARKED].
     * - The show's own park over a venue park: the venue's park stands again, so the channel goes
     *   from one park to the other with nothing handed down. [UnparkOutcome.VENUE_RESTORED].
     * - A venue park, in a show: **released** — its value handed down as above, and a release stored
     *   for the channel; the version is not touched. [UnparkOutcome.RELEASED].
     */
    suspend fun unpark(universe: Int, channel: Int): UnparkOutcome {
        val address = ParkAddress(universe, channel)
        val (ownValue, venueValue) = synchronized(lock) { own[address] to venueUnder(address) }
        return when {
            ownValue != null && venueValue != null -> {
                transaction(database) { ownRow(address)?.delete() }
                synchronized(lock) { own.remove(address); recompute(address) }
                emitState()
                UnparkOutcome.VENUE_RESTORED
            }
            ownValue != null -> {
                unparkValueSink?.handOff(listOf(ParkedChannel(universe, channel, ownValue)))
                transaction(database) { ownRow(address)?.delete() }
                synchronized(lock) { own.remove(address); recompute(address) }
                emitState()
                UnparkOutcome.UNPARKED
            }
            venueValue != null -> {
                unparkValueSink?.handOff(listOf(ParkedChannel(universe, channel, venueValue, RigOwner.VENUE, venueValue)))
                transaction(database) { storeRelease(address) }
                synchronized(lock) { releases.add(address); recompute(address) }
                emitState()
                UnparkOutcome.RELEASED
            }
            else -> UnparkOutcome.NOT_PARKED
        }
    }

    /**
     * Take the venue's park back on a channel, in a show on a venue: drop the show's release of it,
     * and the show's own park over it. Answers whether anything changed. A release with no venue park
     * under it (dormant) just goes.
     */
    fun restoreVenuePark(universe: Int, channel: Int): Boolean {
        val address = ParkAddress(universe, channel)
        // Only over a venue park that stands for this show: one [forget] dropped is still refused, and
        // taking the show's park away there would unpark the channel with nothing handed down.
        val venueStands = synchronized(lock) { address in venue && address !in suppressed }
        val changed = transaction(database) {
            val release = releaseRow(address)
            val ownOverVenue = ownRow(address)?.takeIf { venueStands }
            release?.delete()
            ownOverVenue?.delete()
            release != null || ownOverVenue != null
        }
        if (!changed) return false
        synchronized(lock) {
            releases.remove(address)
            if (venueStands) own.remove(address)
            recompute(address)
        }
        emitState()
        return true
    }

    /**
     * Drop a park outright — no hand-off to the layers below, unlike [unpark]. For a park that may
     * not stand at all (a firing level on a one-shot trigger's channel, or a level that would hold a
     * fixture command, `Show.dropRefusedParks`),
     * where handing its value down would only carry it somewhere else. The project's own row is
     * deleted; a venue park (a version is read-only) is left out for this show's life.
     */
    fun forget(universe: Int, channel: Int) {
        val address = ParkAddress(universe, channel)
        val ownValue = synchronized(lock) {
            if (parkedChannels[universe]?.containsKey(channel) != true) return
            own[address]
        }
        if (ownValue != null) {
            transaction(database) { ownRow(address)?.delete() }
        }
        synchronized(lock) {
            own.remove(address)
            // Only the venue's park, when it was the one standing: forgetting the show's own refused
            // re-park leaves the venue's under it to be judged on its own (`Show.dropRefusedParks`
            // takes another pass).
            if (ownValue == null && address in venue) suppressed.add(address)
            recompute(address)
        }
        emitState()
    }

    /**
     * Unpark all channels, leaving every output where it was. Same hand-off ordering as
     * [unpark]: every effective park is handed down, the project's own rows go, and in a show on a
     * venue every venue park left standing is released.
     */
    suspend fun unparkAll() {
        getAllParked().takeIf { it.isNotEmpty() }?.let { unparkValueSink?.handOff(it) }

        val toRelease = synchronized(lock) { venue.keys.filter { it !in releases && it !in suppressed } }
        transaction(database) {
            DaoParkedChannel.find { DaoParkedChannels.project eq projectId }
                .forEach { it.delete() }
            if (onVenue) toRelease.forEach(::storeRelease)
        }
        synchronized(lock) {
            own.clear()
            if (onVenue) releases.addAll(toRelease)
            parkedChannels.clear()
        }

        emitState()
    }

    /**
     * Check if a channel is parked.
     */
    override fun isParked(universe: Int, channel: Int): Boolean {
        return parkedChannels[universe]?.containsKey(channel) == true
    }

    /**
     * Get the parked value for a channel, or null if not parked.
     */
    override fun getParkedValue(universe: Int, channel: Int): UByte? {
        return parkedChannels[universe]?.get(channel)
    }

    override fun universeView(universe: Int): Map<Int, UByte>? = parkedChannels[universe]

    /** Whose the park on a channel is, or null when it is not parked. */
    fun ownerOf(universe: Int, channel: Int): RigOwner? = synchronized(lock) {
        val address = ParkAddress(universe, channel)
        when {
            address in own -> RigOwner.SHOW
            isParked(universe, channel) -> RigOwner.VENUE
            else -> null
        }
    }

    /** The venue's park on a channel as it stands in this show (not released or dropped), or null. */
    fun venueParkOf(universe: Int, channel: Int): UByte? = synchronized(lock) { venueUnder(ParkAddress(universe, channel)) }

    /** Whether the show has released the venue park on a channel. */
    fun isReleased(universe: Int, channel: Int): Boolean = synchronized(lock) { ParkAddress(universe, channel) in releases }

    /**
     * Get all parked channels as a flat list, each with whose it is.
     */
    fun getAllParked(): List<ParkedChannel> = synchronized(lock) {
        parkedChannels.flatMap { (universe, channels) ->
            channels.map { (channel, value) ->
                val address = ParkAddress(universe, channel)
                if (address in own) {
                    ParkedChannel(universe, channel, value, RigOwner.SHOW, venueUnder(address))
                } else {
                    ParkedChannel(universe, channel, value, RigOwner.VENUE)
                }
            }
        }.sortedWith(compareBy({ it.universe }, { it.channel }))
    }

    /** The venue parks this show has released, each with the venue's value (null when dormant). */
    fun getReleased(): List<ReleasedPark> = synchronized(lock) {
        releases.sorted().map { ReleasedPark(it.universe, it.channel, venue[it]) }
    }

    /** The park list as a client draws it. */
    fun snapshot(): ParkSnapshot = synchronized(lock) { ParkSnapshot(getAllParked(), getReleased()) }

    // ── Internals ───────────────────────────────────────────────────────────────

    /** The venue's park on [address] as it stands under this show's own: not released, not dropped. Under [lock]. */
    private fun venueUnder(address: ParkAddress): UByte? =
        if (address in releases || address in suppressed) null else venue[address]

    /** Recompute the effective park on [address] from the layers. Under [lock]. */
    private fun recompute(address: ParkAddress) {
        val value = own[address] ?: venueUnder(address)
        if (value != null) {
            parkedChannels.getOrPut(address.universe) { ConcurrentHashMap() }[address.channel] = value
        } else {
            parkedChannels[address.universe]?.remove(address.channel)
        }
    }

    private fun ownRow(address: ParkAddress): DaoParkedChannel? = DaoParkedChannel.find {
        (DaoParkedChannels.project eq projectId) and
            (DaoParkedChannels.universe eq address.universe) and
            (DaoParkedChannels.channel eq address.channel)
    }.firstOrNull()

    private fun releaseRow(address: ParkAddress): DaoShowParkRelease? = DaoShowParkRelease.find {
        (DaoShowParkReleases.project eq projectId) and
            (DaoShowParkReleases.universe eq address.universe) and
            (DaoShowParkReleases.channel eq address.channel)
    }.firstOrNull()

    private fun storeRelease(address: ParkAddress) {
        if (releaseRow(address) != null) return
        DaoShowParkRelease.new {
            this.project = DaoProject.findById(projectId)!!
            this.universe = address.universe
            this.channel = address.channel
        }
    }

    /**
     * Under [lock] (reentrant), so the snapshot is one reading of the layers and two writers on two
     * threads cannot publish the older one last.
     */
    private fun emitState() = synchronized(lock) {
        _parkStateFlow.value = snapshot()
    }

    companion object {
        /**
         * The parks [projectId] would load, read without loading it — its **effective** parks, so a
         * show on a venue's include its pinned version's (less its releases): what a project switch
         * keeps out of the outgoing show's blackout, so a channel both projects park — two shows on
         * one venue that both inherit it — goes from one park to the other without a frame at zero in
         * between.
         */
        fun storedParks(database: Database, projectId: Int): List<ParkedChannel> = transaction(database) {
            val layers = VenueParks.of(projectId)
            layers.effective.map { (address, value) ->
                val owner = layers.ownerOf(address) ?: RigOwner.SHOW
                ParkedChannel(address.universe, address.channel, value, owner)
            }
        }
    }
}
