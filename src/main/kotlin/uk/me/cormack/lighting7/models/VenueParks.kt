package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq

/** One DMX channel, as parks name it: the universe number and the channel, 1–512. */
data class ParkAddress(val universe: Int, val channel: Int) : Comparable<ParkAddress> {
    override fun compareTo(other: ParkAddress): Int =
        compareValuesBy(this, other, { it.universe }, { it.channel })

    /** `1-005`, as the Patch list and the venue diff write an address. */
    override fun toString(): String = "$universe-${channel.toString().padStart(3, '0')}"
}

/**
 * A project's parks as its rig sees them — the one answer to "which channels does this project park,
 * and at what", as [RigScope] is for the rig's rows.
 *
 * For a standalone show, a venue and a version, that is exactly its own `parked_channels` rows. For a
 * show **on a venue** it is the pinned version's parks overlaid by the show's own (a re-park at another
 * value replaces the venue's for this show), less the show's releases (`show_park_releases`):
 *
 * ```
 * effective(c) = own(c) ?: (c ∈ releases ? none : venue(c))
 * ```
 *
 * Read inside the caller's transaction. The loader (`ParkManager.loadFromDatabase`), a switch's
 * hand-over (`ParkManager.storedParks`, so a channel both shows park — two shows on one venue —
 * keeps its level) and Detach's flatten all read it.
 */
class VenueParks private constructor(
    /** The project's own rows. */
    val own: Map<ParkAddress, UByte>,
    /** The pinned version's rows; null when the project is on no venue (or its version is not here). */
    val venue: Map<ParkAddress, UByte>?,
    /** The show's releases, dormant ones (no venue park on the channel) included. */
    val releases: Set<ParkAddress>,
) {
    /** Whether the project is a show on a venue this desk holds: only then can it release a park. */
    val onVenue: Boolean get() = venue != null

    /** The effective parks, by address. */
    val effective: Map<ParkAddress, UByte> by lazy {
        val out = sortedMapOf<ParkAddress, UByte>()
        venue?.forEach { (address, value) -> if (address !in releases) out[address] = value }
        out.putAll(own)
        out
    }

    /** Whose the effective park on [address] is, or null when it is not parked. */
    fun ownerOf(address: ParkAddress): RigOwner? = when {
        address in own -> RigOwner.SHOW
        address in effective -> RigOwner.VENUE
        else -> null
    }

    companion object {
        /** [projectId]'s parks. Inside a transaction. */
        fun of(projectId: Int): VenueParks = of(EntityID(projectId, DaoProjects))

        fun of(projectId: EntityID<Int>): VenueParks {
            val layer = VenueLayer.resolve(projectId)
            return VenueParks(
                own = rowsOf(projectId),
                venue = layer?.let { rowsOf(it.snapshotId) },
                releases = if (layer == null) emptySet() else releasesOf(projectId),
            )
        }

        /** [projectId]'s own `parked_channels` rows, by address. Inside a transaction. */
        fun rowsOf(projectId: EntityID<Int>): Map<ParkAddress, UByte> =
            DaoParkedChannel.find { DaoParkedChannels.project eq projectId }
                .associate { ParkAddress(it.universe, it.channel) to it.value.toUByte() }

        /** [projectId]'s releases, dormant or not. Inside a transaction. */
        fun releasesOf(projectId: EntityID<Int>): Set<ParkAddress> =
            DaoShowParkRelease.find { DaoShowParkReleases.project eq projectId }
                .map { ParkAddress(it.universe, it.channel) }
                .toSet()
    }
}
