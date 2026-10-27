package uk.me.cormack.lighting7.models

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import java.util.UUID

/**
 * A show's **order** of a venue group's members (group-order plan D2): one row per venue group the show
 * chases in its own order, named by the group's uuid, holding the venue patch uuids in the show's order.
 * `RigScope.members` merges it over the pinned version's order ([GroupOrder.merge]), so a member the venue
 * added since keeps its venue place and one it removed is skipped. Never restates the venue's order
 * ([ShowGroupOrders.put]). Hiding the group leaves its row dormant, as a recolour's is.
 *
 * **Portable** with the show, as `showGroupOrders/{uuid}.json` (`formatVersion` 44): a clone remaps only
 * the row's own [uuid] — [groupUuid] and [members] name venue records.
 */
object DaoShowGroupOrders : IntIdTable("show_group_orders") {
    val project = reference("project_id", DaoProjects)
    val groupUuid = javaUUID("group_uuid")
    /** A JSON list of venue patch uuids, in the show's order ([ShowGroupOrders.encode]). */
    val members = text("members")
    /** The order's own identity in the show's sync export. */
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(project, groupUuid)
    }
}

class DaoShowGroupOrder(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoShowGroupOrder>(DaoShowGroupOrders)

    var project by DaoProject referencedOn DaoShowGroupOrders.project
    var groupUuid by DaoShowGroupOrders.groupUuid
    var members by DaoShowGroupOrders.members
    var uuid by DaoShowGroupOrders.uuid
}

/** Whose a venue group's member order is in a show (D8): the show's, or the venue's own. */
enum class OrderOwner { SHOW, VENUE }

object ShowGroupOrders {
    /** The request field a show's order is written through, on the group PUT (D5). */
    const val FIELD = "memberOrder"

    private val listSerializer = ListSerializer(String.serializer())

    fun encode(members: List<UUID>): String = Json.encodeToString(listSerializer, members.map { it.toString() })

    /** A stored list; an entry that is not a uuid is left out, and a value that is no list reads as empty. */
    fun decode(text: String): List<UUID> =
        runCatching { Json.decodeFromString(listSerializer, text) }.getOrDefault(emptyList())
            .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }

    /**
     * Put [wanted] — members of the venue group [group], in the show's order — onto [rig]'s order of it
     * (group-order plan D5), or take the venue's back with null. The merge runs over every member, the
     * patches the show hides included, so a hidden member keeps its place in the show's order and comes
     * back to it (D6); an order that comes out as the venue's clears the row. Answers whether a row changed.
     */
    fun reorder(rig: RigScope, group: DaoFixtureGroup, wanted: List<DaoFixtureGroupMember>?): Boolean {
        val all = storedMemberOrder(group.members)
        val current = rig.groupOrders[group.uuid]?.let { order -> GroupOrder.merge(all, order) { it.fixturePatch.uuid } } ?: all
        val merged = wanted?.let { GroupOrder.merge(current, it.map { m -> m.id }) { m -> m.id } }
        return put(rig.projectId, group.uuid, merged?.map { it.fixturePatch.uuid }, all.map { it.fixturePatch.uuid })
    }

    /**
     * Set [project]'s order of the venue group [groupUuid] to [members] (venue patch uuids), or clear it
     * with null — *Take venue's*. An order equal to [venueOrder], the venue's, clears it
     * too, so no row restates the venue. Inside the caller's transaction. Answers whether a row changed.
     */
    fun put(project: EntityID<Int>, groupUuid: UUID, members: List<UUID>?, venueOrder: List<UUID>): Boolean {
        val row = DaoShowGroupOrder.find {
            (DaoShowGroupOrders.project eq project) and (DaoShowGroupOrders.groupUuid eq groupUuid)
        }.firstOrNull()
        if (members == null || members == venueOrder) {
            row ?: return false
            row.delete()
            return true
        }
        val text = encode(members)
        if (row != null) {
            if (row.members == text) return false
            row.members = text
            return true
        }
        DaoShowGroupOrder.new {
            this.project = DaoProject[project]
            this.groupUuid = groupUuid
            this.members = text
        }
        return true
    }
}
