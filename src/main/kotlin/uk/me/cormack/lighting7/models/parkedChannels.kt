package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID

/**
 * A project's parked channels: universe + channel held at a fixed value above every layer
 * (`dmx/ParkManager.kt`). **Channel-level**, never following a fixture. Portable, as
 * `parkedChannels/{uuid}.json`.
 *
 * A **venue**'s rows are part of its rig and versioned with it: a version's snapshot holds the parks
 * its draft held when it was published, and a show on the venue inherits its **pinned** version's
 * ([VenueParks]), overlaid by its own rows here (a re-park at another value) and less its
 * [DaoShowParkReleases].
 */
object DaoParkedChannels : IntIdTable("parked_channels") {
    val project = reference("project_id", DaoProjects)
    val universe = integer("universe")
    val channel = integer("channel")
    val value = integer("value")
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(project, universe, channel)
    }
}

class DaoParkedChannel(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoParkedChannel>(DaoParkedChannels)

    var project by DaoProject referencedOn DaoParkedChannels.project
    var universe by DaoParkedChannels.universe
    var channel by DaoParkedChannels.channel
    var value by DaoParkedChannels.value
    var uuid by DaoParkedChannels.uuid
}

/**
 * A show's **releases** of venue parks: the channels its pinned version parks that the show leaves
 * unparked for its run (rule 3 of the venue-parks plan). Keyed by **universe and channel**, as parks
 * themselves are, not by the venue park's uuid — a venue that unparks a channel and parks it again
 * mints a new row, and the show's release must still stand. A release with no venue park on its
 * channel at the pinned version is **dormant**: it releases nothing, and is kept (an Update to a
 * version that parks the channel again honours it), as a hidden patch's overrides are.
 *
 * One word per channel: the show parking a released channel deletes the release, so unparking the
 * show's park brings back the venue's (`ParkManager.park`).
 *
 * **Portable** with the show, as `showParkReleases/{uuid}.json` (`formatVersion` 27); a clone remaps
 * only the release's own [uuid].
 */
object DaoShowParkReleases : IntIdTable("show_park_releases") {
    val project = reference("project_id", DaoProjects)
    val universe = integer("universe")
    val channel = integer("channel")
    /** The release's own identity in the show's sync export. */
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(project, universe, channel)
    }
}

class DaoShowParkRelease(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoShowParkRelease>(DaoShowParkReleases)

    var project by DaoProject referencedOn DaoShowParkReleases.project
    var universe by DaoShowParkReleases.universe
    var channel by DaoShowParkReleases.channel
    var uuid by DaoShowParkReleases.uuid
}
