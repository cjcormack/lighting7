package uk.me.cormack.lighting7.show

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.dmx.ArtNetController
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.models.*

/**
 * Imports the current runtime fixture configuration into the database.
 * Used when switching a project from SCRIPT_BASED to DB_BASED mode —
 * snapshots the live fixture registry into DB records.
 */
object FixtureImporter {

    /**
     * Import the current runtime fixtures, controllers, and groups into DB records.
     * Deletes any existing patch data for the project before importing.
     */
    fun importFromRuntime(projectId: Int, fixtures: Fixtures, database: Database) {
        val controllers = fixtures.controllers
        val runtimeFixtures = fixtures.fixtures
        val groups = fixtures.groups

        transaction(database) {
            val project = DaoProject.findById(projectId)
                ?: throw IllegalArgumentException("Project $projectId not found")

            // Clear existing patch data (delete in dependency order)
            val existingGroups = DaoFixtureGroup.find { DaoFixtureGroups.project eq project.id }
            for (group in existingGroups) {
                group.members.forEach { it.delete() }
                group.delete()
            }
            DaoFixturePatch.find { DaoFixturePatches.project eq project.id }.forEach { it.delete() }
            DaoUniverseConfig.find { DaoUniverseConfigs.project eq project.id }.forEach { it.delete() }

            // 1. Import controllers as universe configs
            val universeConfigMap = mutableMapOf<String, DaoUniverseConfig>() // "subnet:universe" → config
            for (controller in controllers) {
                val key = "${controller.universe.subnet}:${controller.universe.universe}"
                val config = DaoUniverseConfig.new {
                    this.project = project
                    this.subnet = controller.universe.subnet
                    this.universe = controller.universe.universe
                    this.controllerType = controllerTypeName(controller)
                    this.address = (controller as? ArtNetController)?.address
                }
                universeConfigMap[key] = config
            }

            // 2. Import fixtures as patches
            val patchMap = mutableMapOf<String, DaoFixturePatch>() // fixtureKey → patch
            var sortOrder = 0
            for (fixture in runtimeFixtures) {
                if (fixture !is DmxFixture) continue

                val typeKey = FixtureTypeRegistry.typeKeyForClass(fixture::class) ?: continue
                val configKey = "${fixture.universe.subnet}:${fixture.universe.universe}"
                val universeConfig = universeConfigMap[configKey] ?: continue

                val patch = DaoFixturePatch.new {
                    this.project = project
                    this.universeConfig = universeConfig
                    this.fixtureTypeKey = typeKey
                    this.key = fixture.key
                    this.displayName = fixture.fixtureName
                    this.startChannel = fixture.firstChannel
                    this.sortOrder = sortOrder++
                }
                patchMap[fixture.key] = patch
            }

            // 3. Import groups
            for (group in groups) {
                val dbGroup = DaoFixtureGroup.new {
                    this.project = project
                    this.name = group.name
                }

                var memberOrder = 0
                for (member in group.allMembers) {
                    val patch = patchMap[member.key] ?: continue
                    DaoFixtureGroupMember.new {
                        this.group = dbGroup
                        this.fixturePatch = patch
                        this.sortOrder = memberOrder++
                        this.panOffset = member.metadata.panOffset
                        this.tiltOffset = member.metadata.tiltOffset
                    }
                }
            }
        }
    }

    private fun controllerTypeName(controller: DmxController): String {
        return when (controller) {
            is ArtNetController -> "ARTNET"
            else -> "MOCK"
        }
    }
}
