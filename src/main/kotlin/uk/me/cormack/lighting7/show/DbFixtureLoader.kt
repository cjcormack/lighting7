package uk.me.cormack.lighting7.show

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.dmx.ArtNetController
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.sync.Overrides

/**
 * Loads fixtures, controllers, and groups from DB records into the runtime Fixtures registry.
 * Uses the same Fixtures.register {} DSL as scripts, so everything downstream
 * (FX engine, cues, channel mapping, WebSocket) works identically.
 */
object DbFixtureLoader {

    /**
     * Load all fixture configuration from the database for the given project.
     * Clears existing fixtures and rebuilds from DB state.
     */
    fun loadFixtures(projectId: Int, fixtures: Fixtures, database: Database) {
        val (universeConfigs, patches, groups) = transaction(database) {
            val project = DaoProject.findById(projectId)
                ?: throw IllegalArgumentException("Project $projectId not found")

            val configs = DaoUniverseConfig.find { DaoUniverseConfigs.project eq project.id }
                .orderBy(DaoUniverseConfigs.universe to SortOrder.ASC)
                .map {
                    val address = Overrides.resolveUniverseAddress(project.id.value, it.uuid)
                    UniverseConfigData(it.id.value, it.subnet, it.universe, it.controllerType, address)
                }

            val patches = DaoFixturePatch.find { DaoFixturePatches.project eq project.id }
                .orderBy(DaoFixturePatches.sortOrder to SortOrder.ASC)
                .map {
                    PatchData(
                        key = it.key,
                        displayName = it.displayName,
                        fixtureTypeKey = it.fixtureTypeKey,
                        startChannel = it.startChannel,
                        universeConfigId = it.universeConfig.id.value,
                        stageX = it.stageX,
                        stageY = it.stageY,
                        riggingPosition = it.riggingPosition,
                        beamAngleDeg = it.beamAngleDeg,
                        gelCode = it.gelCode,
                    )
                }

            val groups = DaoFixtureGroup.find { DaoFixtureGroups.project eq project.id }
                .map { group ->
                    val members = group.members
                        .sortedBy { it.sortOrder }
                        .map { member ->
                            GroupMemberData(member.fixturePatch.key, member.panOffset, member.tiltOffset, member.sortOrder)
                        }
                    GroupData(group.name, members)
                }

            Triple(configs, patches, groups)
        }

        // Build universe lookup: configId → Universe
        val universeByConfigId = mutableMapOf<Int, Universe>()

        fixtures.register(removeUnused = true) {
            // 1. Create controllers
            for (config in universeConfigs) {
                val universe = Universe(config.subnet, config.universe)
                universeByConfigId[config.id] = universe

                val controller = when (config.controllerType.uppercase()) {
                    "ARTNET" -> ArtNetController(universe, config.address)
                    "MOCK" -> MockDmxController(universe)
                    else -> ArtNetController(universe, config.address) // default to ArtNet
                }
                addController(controller)
            }

            // 2. Create fixtures
            val fixtureByKey = mutableMapOf<String, DmxFixture>()
            for (patch in patches) {
                val universe = universeByConfigId[patch.universeConfigId]
                    ?: throw IllegalStateException("Universe config ${patch.universeConfigId} not found for patch ${patch.key}")

                val fixture = FixtureTypeRegistry.instantiateByTypeKey(
                    typeKey = patch.fixtureTypeKey,
                    universe = universe,
                    key = patch.key,
                    fixtureName = patch.displayName,
                    firstChannel = patch.startChannel,
                )
                addFixture(fixture)
                fixtureByKey[patch.key] = fixture

                setPatchMetadata(
                    patch.key,
                    Fixtures.FixturePatchMetadata(gelCode = patch.gelCode),
                )
            }

            // 3. Create groups
            for (group in groups) {
                createGroup<GroupableFixture>(group.name) {
                    for (member in group.members) {
                        val fixture = fixtureByKey[member.fixtureKey]
                            ?: continue // skip if fixture not found
                        add(fixture, panOffset = member.panOffset, tiltOffset = member.tiltOffset)
                    }
                }
            }
        }
    }

    // Data classes for transporting DB data outside the transaction
    private data class UniverseConfigData(
        val id: Int,
        val subnet: Int,
        val universe: Int,
        val controllerType: String,
        val address: String?,
    )

    private data class PatchData(
        val key: String,
        val displayName: String,
        val fixtureTypeKey: String,
        val startChannel: Int,
        val universeConfigId: Int,
        val stageX: Double?,
        val stageY: Double?,
        val riggingPosition: String?,
        val beamAngleDeg: Int?,
        val gelCode: String?,
    )

    private data class GroupMemberData(
        val fixtureKey: String,
        val panOffset: Double,
        val tiltOffset: Double,
        val sortOrder: Int,
    )

    private data class GroupData(
        val name: String,
        val members: List<GroupMemberData>,
    )
}
