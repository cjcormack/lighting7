package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * A venue's published versions (venue plan §3.2, P2): one row per publish, naming the frozen
 * `VENUE_VERSION` project that holds it. Versions are immutable, numbered densely from 1 per venue.
 *
 * **Machine-local**: an index of this desk's snapshots, never exported. Session 4 rebuilds it
 * from the venue repo's tags (§3.4), so what travels is the tagged commit, not this row.
 */
object DaoVenueVersions : IntIdTable("venue_versions") {
    val venueProject = reference("venue_project_id", DaoProjects)
    val number = integer("number")
    val note = varchar("note", VENUE_VERSION_NOTE_MAX)
    val publishedAt = utcInstant("published_at")
    /** The publishing account's display name, kept as text: a version outlives an account. */
    val publishedBy = varchar("published_by", 100).nullable()
    val snapshotProject = reference("snapshot_project_id", DaoProjects)

    init {
        uniqueIndex(venueProject, number)
    }
}

/** The longest note a version may carry. */
const val VENUE_VERSION_NOTE_MAX = 500

class DaoVenueVersion(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoVenueVersion>(DaoVenueVersions)

    var venueProject by DaoProject referencedOn DaoVenueVersions.venueProject
    var number by DaoVenueVersions.number
    var note by DaoVenueVersions.note
    var publishedAt by DaoVenueVersions.publishedAt
    var publishedBy by DaoVenueVersions.publishedBy
    var snapshotProject by DaoProject referencedOn DaoVenueVersions.snapshotProject
}
