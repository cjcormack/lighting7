package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

object DaoFixtureGroups : IntIdTable("fixture_groups") {
    val project = reference("project_id", DaoProjects)
    val name = varchar("name", 100)
    val uuid = uuid("uuid").autoGenerate()

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
    val uuid = uuid("uuid").autoGenerate()

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
