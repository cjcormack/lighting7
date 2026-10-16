package uk.me.cormack.lighting7.show

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fixture.locate.UnitLocate
import uk.me.cormack.lighting7.fixture.media.FittedMedia
import uk.me.cormack.lighting7.dmx.ArtNetController
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.OutputHold
import uk.me.cormack.lighting7.dmx.ParkSource
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureCommands
import uk.me.cormack.lighting7.fixture.FixtureTriggers
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.sync.Overrides
import uk.me.cormack.lighting7.venue.VenuePins
import org.slf4j.LoggerFactory

/**
 * Loads fixtures, controllers, and groups from DB records into the runtime Fixtures registry.
 * Uses the same Fixtures.register {} DSL as scripts, so everything downstream
 * (FX engine, cues, channel mapping, WebSocket) works identically.
 */
object DbFixtureLoader {
    private val logger = LoggerFactory.getLogger(DbFixtureLoader::class.java)

    /**
     * The universes [projectId]'s rig would build controllers for, read without building them —
     * [loadFixtures]'s own reading, so a reload can tell which of the outgoing show's universes its
     * successor will not cover (and leave those dark rather than holding their last look).
     */
    fun universesOf(projectId: Int, database: Database): Set<Universe> = transaction(database) {
        val project = DaoProject.findById(projectId) ?: return@transaction emptySet()
        if (VenuePins.isMissing(project)) return@transaction emptySet()
        RigScope.of(project).universeConfigs().mapTo(mutableSetOf()) { Universe(it.subnet, it.universe) }
    }

    /**
     * Load all fixture configuration from the database for the given project.
     * Clears existing fixtures and rebuilds from DB state.
     *
     * The optional [parkSource] is wired into freshly-constructed controllers so
     * parked channels survive the rebuild — controllers consult the source at
     * transmit time rather than holding their own park cache. Pass `null` from
     * tests that don't exercise the park subsystem.
     *
     * The optional [hold] is the show's (venue plan P5, session 6): judged **first**, before a
     * controller exists — so a show that is behind its venue on output never puts a frame on the rig
     * — and then shared by every controller built here. Every load judges it afresh, so an Update or a
     * pulled pin move lifts or re-applies it at the reload that follows. Pass `null` from tests that
     * don't exercise the hold.
     *
     * [seedValues] seeds a controller this registry does not already have — a reload's fresh show
     * carrying the replaced show's last frame (`ProjectManager`'s same-project reload), so the rig
     * starts where it was rather than at zero. A controller the registry has already seeds from
     * itself, as before.
     */
    fun loadFixtures(
        projectId: Int,
        fixtures: Fixtures,
        database: Database,
        parkSource: ParkSource? = null,
        hold: RigHold? = null,
        seedValues: Map<Universe, Map<Int, UByte>> = emptyMap(),
    ) {
        hold?.judge(projectId)
        val (universeConfigs, patches, groups) = transaction(database) {
            val project = DaoProject.findById(projectId)
                ?: throw IllegalArgumentException("Project $projectId not found")
            // A show whose pinned venue version is not on this desk loads no rig at all — no fixture,
            // no controller — rather than its own additions without the venue's (venue plan §3.4). The
            // switch refuses such a show; this is the desk starting with one current, which it must
            // survive so the operator can switch away.
            if (VenuePins.isMissing(project)) {
                logger.error("Project {} is on a venue version this desk does not have; loading no rig. {}", projectId, VenuePins.missing(project).message)
                return@transaction Triple(emptyList<UniverseConfigData>(), emptyList<PatchData>(), emptyList<GroupData>())
            }
            val rig = RigScope.of(project)

            val configs = rig.universeConfigs()
                .orderBy(DaoUniverseConfigs.universe to SortOrder.ASC)
                .map {
                    // A venue's universe resolves its machine-local address against the venue's id
                    // (venue plan P4), so a node is where it is in the draft, every version and every show.
                    val overrideProject = rig.overrideProjectId(it.project.id)
                    val address = Overrides.resolveUniverseAddress(overrideProject, it.uuid)
                    val refreshIntervalMs =
                        Overrides.resolveUniverseRefreshIntervalMs(overrideProject, it.uuid)
                            ?: ArtNetController.DEFAULT_REFRESH_INTERVAL_MS
                    UniverseConfigData(
                        it.id.value, it.subnet, it.universe, it.controllerType, address, refreshIntervalMs,
                    )
                }

            // Each patch as the rig sees it: a venue patch's Address and Media overrides (venue plan
            // session 5) are what the fixture is built at and carries.
            val patches = rig.effective(rig.patches().orderBy(DaoFixturePatches.sortOrder to SortOrder.ASC))
                .map {
                    PatchData(
                        key = it.key,
                        displayName = it.displayName,
                        fixtureTypeKey = it.fixtureTypeKey,
                        startChannel = it.startChannel,
                        universeConfigId = it.universeConfig.id.value,
                        stageX = it.stageX,
                        stageY = it.stageY,
                        beamAngleDeg = it.beamAngleDeg,
                        gelCode = it.gelCode,
                        infrastructure = it.infrastructure,
                        media = it.fittedMedia,
                        locate = it.locate,
                    )
                }

            val groups = rig.groups()
                .map { group ->
                    val members = rig.members(group)
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

        // Snapshot existing controllers' channel state so unrelated channels survive
        // a fixture-and-controller rebuild. Without this, patching or editing one
        // fixture would zero every channel on the rig — a fresh ArtNetController
        // initialises all 512 entries of currentValues to 0u. Channels owned by a
        // since-removed fixture stay at their last value here, which matches what
        // the rig is actually outputting; the next write (fixture, FX, manual) will
        // overwrite them.
        val priorValuesByUniverse: Map<Universe, Map<Int, UByte>> =
            seedValues + fixtures.controllers.associate { it.universe to it.currentValues.toMap() }

        fixtures.register(removeUnused = true) {
            // 1. Create controllers
            for (config in universeConfigs) {
                val universe = Universe(config.subnet, config.universe)
                universeByConfigId[config.id] = universe

                // Seeded at construction rather than restored afterwards: an ArtNet
                // controller starts streaming immediately, so a post-construction restore
                // races the bootstrap frame and can put one all-zero frame on the rig.
                val priorValues = priorValuesByUniverse[universe] ?: emptyMap()

                val controller = when (config.controllerType.uppercase()) {
                    "MOCK" -> MockDmxController(universe, parkSource = parkSource, hold = hold?.output)
                        .also { it.restoreState(priorValues) }
                    // ARTNET, and anything unrecognised, defaults to ArtNet.
                    else -> ArtNetController(
                        universe,
                        config.address,
                        refreshIntervalMs = config.refreshIntervalMs,
                        initialValues = priorValues,
                        parkSource = parkSource,
                        hold = hold?.output,
                    )
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
                    Fixtures.FixturePatchMetadata(
                        gelCode = patch.gelCode, infrastructure = patch.infrastructure, media = patch.media, locate = patch.locate,
                    ),
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

        // A reload seeds every one-shot trigger and its arm idle (stage-view plan session 9). The
        // snapshot above carried the old buffer across, and a trigger channel is never something a
        // carried value may hold: the trigger output owns it from here (`state/TriggerOutput.kt`),
        // and the buffer under it is zeroed so nothing raised survives even where no output is wired.
        for (fixture in fixtures.fixtures) {
            val dmx = fixture as? DmxFixture ?: continue
            val triggers = FixtureTriggers.of(dmx)
            if (triggers.isEmpty()) continue
            val idle = triggers.flatMap { listOf(it.channelNo, it.armChannelNo) }.associateWith { FixtureTriggers.IDLE_LEVEL }
            fixtures.controllerOrNull(dmx.universe)?.restoreState(idle)
        }

        // Likewise every fixture command's dedicated channel (fixture optics plan session 7): the command
        // output owns it from here (`state/CommandOutput.kt`), and the buffer under it is set idle so a
        // carried value — the Varytec's `reset` was a setting until then — never survives underneath.
        for (fixture in fixtures.fixtures) {
            val dmx = fixture as? DmxFixture ?: continue
            val dedicated = FixtureCommands.of(dmx).filter { it.dedicated }
            if (dedicated.isEmpty()) continue
            fixtures.controllerOrNull(dmx.universe)?.restoreState(dedicated.associate { it.channelNo to it.idleLevel })
        }
    }

    // Data classes for transporting DB data outside the transaction
    private data class UniverseConfigData(
        val id: Int,
        val subnet: Int,
        val universe: Int,
        val controllerType: String,
        val address: String?,
        /** Machine-local Art-Net transmit interval, already defaulted. */
        val refreshIntervalMs: Int,
    )

    private data class PatchData(
        val key: String,
        val displayName: String,
        val fixtureTypeKey: String,
        val startChannel: Int,
        val universeConfigId: Int,
        val stageX: Double?,
        val stageY: Double?,
        val beamAngleDeg: Int?,
        val gelCode: String?,
        val infrastructure: Boolean,
        val media: FittedMedia?,
        val locate: List<UnitLocate>,
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
