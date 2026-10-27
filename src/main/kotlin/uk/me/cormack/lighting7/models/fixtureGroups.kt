package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID

object DaoFixtureGroups : IntIdTable("fixture_groups") {
    val project = reference("project_id", DaoProjects)
    val name = varchar("name", 100)
    /** The group's colour, one of [FixtureColours.KEYS]; null is none. Its members never inherit it. */
    val colour = varchar("colour", FixtureColours.MAX_LENGTH).nullable()
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(project, name)
    }
}

class DaoFixtureGroup(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoFixtureGroup>(DaoFixtureGroups)

    var project by DaoProject referencedOn DaoFixtureGroups.project
    var name by DaoFixtureGroups.name
    var colour by DaoFixtureGroups.colour
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
 * member, and a repeat, are ignored ([GroupOrder.merge]).
 */
fun reorderGroupMembers(group: DaoFixtureGroup, ordered: List<Int>) {
    GroupOrder.merge(storedMemberOrder(group.members), ordered) { it.patchId }.forEachIndexed { i, member ->
        if (member.sortOrder != i) member.sortOrder = i
    }
}

/** [members] in their stored order, ties broken by row id: the order they were added in. */
fun storedMemberOrder(members: Iterable<DaoFixtureGroupMember>): List<DaoFixtureGroupMember> =
    members.sortedWith(compareBy({ it.sortOrder }, { it.id.value }))

/** The member's patch id, read without loading the patch. */
val DaoFixtureGroupMember.patchId: Int get() = readValues[DaoFixtureGroupMembers.fixturePatch].value
