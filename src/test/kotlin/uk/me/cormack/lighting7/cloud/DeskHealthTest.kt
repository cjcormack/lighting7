package uk.me.cormack.lighting7.cloud

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.cloud.protocol.Capability
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.testsupport.FakeCloud
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import uk.me.cormack.lighting7.cloud.protocol.CloudJson
import uk.me.cormack.lighting7.cloud.protocol.Envelope
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoPalettes
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.DaoUsers
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.RICH_PROJECT_NAME
import uk.me.cormack.lighting7.testsupport.seedRichProject
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import uk.me.cormack.lighting7.update.BuildInfo
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The full heartbeat from a running desk (cloud-p3 plan D2), and what it never carries (FR-FLEET-5). */
class DeskHealthTest {

    private lateinit var state: State
    private var projectId = 0

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig("dmx.mock" to "true"))
        seedUser(state, "operator-with-a-name")
        projectId = seedRichProject(state)
        // The rich project's universes are mock controllers, and its hex fixtures a type the desk lacks:
        // made Art-Net universes and a type it has, so the rig loads and the heartbeat has universes.
        transaction(state.database) {
            DaoUniverseConfigs.update({ DaoUniverseConfigs.project eq projectId }) { it[controllerType] = "ARTNET" }
            DaoFixturePatches.update({ DaoFixturePatches.fixtureTypeKey eq "hex-fixture" }) { it[fixtureTypeKey] = "generic-dimmer" }
        }
        state.initializeShow()
        state.show.start()
    }

    private var fake: FakeCloud? = null
    private var service: CloudService? = null

    @After
    fun tearDown() {
        service?.let { runCatching { it.close() } }
        fake?.let { runCatching { it.close() } }
        runCatching { state.shutdown() }
    }

    @Test
    fun `an adopted running desk sends the full heartbeat and announces health v2`() {
        val cloud = FakeCloud().apply { heartbeatS = 1 }.also { fake = it }
        val adminId = seedUser(state, "admin").userId
        val store = InMemoryCredentialStore()
        val s = CloudService(
            database = state.database,
            config = CloudConfig(cloud.baseUrl, true),
            credentialStore = { store },
            apiFactory = { CloudApi(it, Duration.ofSeconds(5)) },
            health = state.deskHealth::payload,
        ).also { service = it }
        s.pollDelay = { 20.milliseconds }
        s.start()
        cloud.decision = FakeCloud.Decision.APPROVE
        runBlocking {
            s.startAdoption(adminId)
            withTimeout(15.seconds) { s.state.first { it.connection == CloudConnectionStatus.ONLINE } }
            withTimeout(15.seconds) { while (cloud.received.none { it.second.body is Message.Health }) kotlinx.coroutines.delay(20) }
        }

        val hello = cloud.received.map { it.second.body }.filterIsInstance<Message.Hello>().first()
        assertTrue(Capability.HEALTH_V2 in hello.capabilities)
        val health = cloud.received.map { it.second.body }.filterIsInstance<Message.Health>().first()
        assertEquals(RICH_PROJECT_NAME, health.project?.name)
        assertEquals(listOf(0, 1), health.universes?.map { it.universe })
        assertNotNull(health.diskFreeMb)
        assertEquals(DeskPlatform.current, health.platform)
    }

    @Test
    fun `the heartbeat reports the live project, its universes, disk and platform`() {
        val health = state.deskHealth.payload()
        val uuid = transaction(state.database) { DaoProject.findById(projectId)!!.uuid.toString() }

        assertEquals(BuildInfo.current.version, health.deskVersion)
        assertEquals(DeskPlatform.current, health.platform)
        assertEquals(uuid, health.project?.uuid)
        assertEquals(RICH_PROJECT_NAME, health.project?.name)
        val universes = assertNotNull(health.universes)
        assertEquals(listOf(0, 1), universes.map { it.universe })
        assertEquals("10.0.0.1", universes.first().address)
        assertTrue(universes.all { it.framesPerS != null && it.reachable == null }, "frame rates, and reachability unknown: $universes")
        assertTrue((health.diskFreeMb ?: -1) >= 0, "disk free: ${health.diskFreeMb}")
        assertNull(health.sync, "no project has synced")
        assertNull(health.update, "a dev build runs no update client")
        assertNull(health.backup)
    }

    @Test
    fun `a rename of the live project reaches the heartbeat`() {
        transaction(state.database) { DaoProject.findById(projectId)!!.name = "Renamed show" }
        state.show.fixtures.projectDetailsChanged(projectId)
        assertEquals("Renamed show", state.deskHealth.payload().project?.name)
    }

    @Test
    fun `after a switch the heartbeat follows the new show, and the old show moves nothing`() {
        val oldShow = state.show
        val other = transaction(state.database) {
            DaoProject.new { name = "Empty show"; description = null; isCurrent = false; inProduction = false }.id.value
        }
        runBlocking { state.projectManager.switchProject(other) }

        val health = state.deskHealth.payload()
        assertEquals("Empty show", health.project?.name)
        assertNull(health.universes, "no Art-Net controllers: absent, not empty")

        transaction(state.database) { DaoProject.findById(projectId)!!.name = "Old renamed" }
        oldShow.fixtures.projectDetailsChanged(projectId)
        oldShow.fixtures.projectDetailsChanged(other)
        assertEquals("Empty show", state.deskHealth.payload().project?.name)
        assertEquals(emptyList(), state.projectManager.liveControllers.value)
    }

    @Test
    fun `a reader that throws leaves only its own field out`() {
        val health = DeskHealth(
            project = { error("broken") },
            controllers = { emptyList() },
            sync = { error("also broken") },
            update = { null },
            dataDir = java.nio.file.Path.of(System.getProperty("java.io.tmpdir")),
        ).payload()
        assertNull(health.project)
        assertNull(health.sync)
        assertNull(health.universes)
        assertEquals(DeskPlatform.current, health.platform)
        assertNotNull(health.diskFreeMb)
        assertEquals(BuildInfo.current.version, health.deskVersion)
    }

    @Test
    fun `nothing a show holds, and no user, is in the heartbeat`() {
        val text = Envelope(id = "1", body = state.deskHealth.payload()).encode()
        val strings = buildSet { collectStrings(CloudJson.parseToJsonElement(text), this) }

        val showStrings = transaction(state.database) {
            DaoCues.selectAll().map { it[DaoCues.name] } +
                DaoCueStacks.selectAll().map { it[DaoCueStacks.name] } +
                DaoLooks.selectAll().map { it[DaoLooks.name] } +
                DaoPalettes.selectAll().map { it[DaoPalettes.name] } +
                DaoFixtureGroups.selectAll().map { it[DaoFixtureGroups.name] } +
                DaoFixturePatches.selectAll().flatMap { listOf(it[DaoFixturePatches.key], it[DaoFixturePatches.displayName]) } +
                DaoUsers.selectAll().flatMap { listOf(it[DaoUsers.username], it[DaoUsers.displayName]) }
        }.filter { it.isNotBlank() }.toSet()
        assertTrue(showStrings.size > 10, "the rich project names plenty: $showStrings")
        val leaked = strings.filter { s -> showStrings.any { it == s || (it.length > 3 && s.contains(it)) } }
        assertEquals(emptyList(), leaked, "a heartbeat carried a show's or a user's name")

        val body = (CloudJson.parseToJsonElement(text) as JsonObject)["body"] as JsonObject
        assertEquals(
            emptySet(),
            body.keys - setOf("uptimeS", "deskVersion", "platform", "project", "universes", "sync", "diskFreeMb", "update", "backup"),
            "a heartbeat field outside FR-FLEET-1's",
        )
    }

    private fun collectStrings(element: JsonElement, into: MutableSet<String>) {
        when (element) {
            is JsonPrimitive -> if (element.isString) into += element.content
            is JsonArray -> element.forEach { collectStrings(it, into) }
            is JsonObject -> element.values.forEach { collectStrings(it, into) }
        }
    }
}
