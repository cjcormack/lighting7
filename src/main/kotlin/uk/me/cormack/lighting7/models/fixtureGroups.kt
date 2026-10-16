package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID

object DaoFixtureGroups : IntIdTable("fixture_groups") {
    val project = reference("project_id", DaoProjects)
    val name = varchar("name", 100)
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(project, name)
    }
}

class DaoFixtureGroup(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoFixtureGroup>(DaoFixtureGroups)

    var project by DaoProject referencedOn DaoFixtureGroups.project
    var name by DaoFixtureGroups.name
    var uuid by DaoFixtureGroups.uuid

    val members by DaoFixtureGroupMember referrersOn DaoFixtureGroupMembers.group
}

object DaoFixtureGroupMembers : IntIdTable("fixture_group_members") {
    val group = reference("group_id", DaoFixtureGroups)
    val fixturePatch = reference("fixture_patch_id", DaoFixturePatches)
    val sortOrder = integer("sort_order").default(0)
    val panOffset = double("pan_offset").default(0.0)
    val tiltOffset = double("tilt_offset").default(0.0)
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(group, fixturePatch)
    }
}

class DaoFixtureGroupMember(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoFixtureGroupMember>(DaoFixtureGroupMembers)

    var group by DaoFixtureGroup referencedOn DaoFixtureGroupMembers.group
    var fixturePatch by DaoFixturePatch referencedOn DaoFixtureGroupMembers.fixturePatch
    var sortOrder by DaoFixtureGroupMembers.sortOrder
    var panOffset by DaoFixtureGroupMembers.panOffset
    var tiltOffset by DaoFixtureGroupMembers.tiltOffset
    var uuid by DaoFixtureGroupMembers.uuid
}

/**
 * Put the members [ordered] names (patch ids) in that order within [group], inside the caller's
 * transaction, and number every member `0…n-1` (venue-visit plan S7). The members named fill, in the
 * order given, the places the members named held; a member not named **keeps its place** — a venue
 * patch the show hides, which no view of the show lists, or an infrastructure head the Groups views
 * leave out — so a view reorders what it shows without knowing what it doesn't. An id that is not a
 * member, and a repeat, are ignored. Ties in a stored order are broken by row id, the order they
 * were added in.
 */
fun reorderGroupMembers(group: DaoFixtureGroup, ordered: List<Int>) {
    val all = group.members.sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
    val byPatch = all.associateBy { it.readValues[DaoFixtureGroupMembers.fixturePatch].value }
    val named = ordered.distinct().mapNotNull { byPatch[it] }
    val namedSet = named.toSet()
    val next = named.iterator()
    all.map { if (it in namedSet) next.next() else it }.forEachIndexed { i, member ->
        if (member.sortOrder != i) member.sortOrder = i
    }
}
