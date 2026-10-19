package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import java.util.UUID

/**
 * A show's **recolours** of its venue's patches and groups (tables-colours plan D10): one row per venue
 * record whose colour the show draws differently, named by the record's uuid (its identity across the
 * venue's versions, as a hide's is). `RigScope.colourOf` lays [colour] over the pinned snapshot's row.
 *
 * Unlike a facet override there is no base and no drift: a colour is identity, never output, so a show's
 * recolour simply stands until it is cleared, whatever the venue does. Hiding the record leaves its row
 * dormant. A show's own rows hold their colour in their own column; only a venue record is recoloured.
 *
 * **Portable** with the show, as `showColours/{uuid}.json` (`formatVersion` 36): a clone keeps them,
 * remapping only the row's own [uuid] — [recordUuid] names a venue record.
 */
object DaoShowColours : IntIdTable("show_colours") {
    val project = reference("project_id", DaoProjects)
    val recordUuid = javaUUID("record_uuid")
    /** One of [FixtureColours.KEYS]; a key this desk does not know reads as none. */
    val colour = varchar("colour", FixtureColours.MAX_LENGTH)
    /** The recolour's own identity in the show's sync export. */
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(project, recordUuid)
    }
}

class DaoShowColour(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoShowColour>(DaoShowColours)

    var project by DaoProject referencedOn DaoShowColours.project
    var recordUuid by DaoShowColours.recordUuid
    var colour by DaoShowColours.colour
    var uuid by DaoShowColours.uuid
}

/** Whose a venue record's colour is in a show (D10): the show's recolour, or the venue's own. */
enum class ColourOwner { SHOW, VENUE }

object ShowColours {
    /**
     * Whether a write naming [keys] sends a colour and nothing outside [alsoAllowed]: on a venue patch or
     * group in a show, the show's recolour of it rather than a change to the venue's row.
     */
    fun isRecolour(keys: Collection<String>, alsoAllowed: Set<String> = emptySet()): Boolean =
        FixtureColours.FIELD in keys && keys.all { it == FixtureColours.FIELD || it in alsoAllowed }

    /**
     * Set [project]'s recolour of the venue record [recordUuid] to [colour] (a key), or clear it with
     * null — which takes the venue's colour back. Inside the caller's transaction. Answers whether a
     * row changed.
     */
    fun put(project: EntityID<Int>, recordUuid: UUID, colour: String?): Boolean {
        val row = DaoShowColour.find { (DaoShowColours.project eq project) and (DaoShowColours.recordUuid eq recordUuid) }.firstOrNull()
        if (colour == null) {
            row ?: return false
            row.delete()
            return true
        }
        if (row != null) {
            if (row.colour == colour) return false
            row.colour = colour
            return true
        }
        DaoShowColour.new {
            this.project = DaoProject[project]
            this.recordUuid = recordUuid
            this.colour = colour
        }
        return true
    }
}
