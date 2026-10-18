package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.plugins.toMessage
import uk.me.cormack.lighting7.state.BulkCommandRun
import uk.me.cormack.lighting7.state.RigSafetyService
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bulk commands, *Make rig safe*, *Undo* and *Quit desk* (venue-visit plan S6) over a real desk with
 * patched fixtures on a MOCK universe. Holds are cut to 50 ms through `CommandOutput.holdMsFor`, a
 * lamp's cool-down to nothing (but in the test of cooling), and the service's clock is moved past a
 * lamp's restrike time rather than waiting it out.
 *
 * Patched: two MAC 250s (lamp on, lamp off, reset — 8-minute restrike) at 1 and 20, a Varytec (a
 * reset, no lamp) at 40.
 */
class RigSafetyRoutesTest : RouteIntegrationTest() {

    private val rig get() = state.rigSafety

    private suspend fun HttpClient.patch(typeKey: String, key: String, start: Int) {
        val resp = post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(CreatePatchRequest(universe = 0, fixtureTypeKey = typeKey, key = key, name = key.uppercase(), startChannel = start))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
    }

    private suspend fun HttpClient.rig() {
        patch("martin-mac-250-mode-4", "mac1", 1)
        patch("martin-mac-250-mode-4", "mac2", 20)
        patch("varytec-easymove-xl-60-spot-11ch", "vary", 40)
        state.show.commandOutput.holdMsFor = { 50 }
        rig.coolDownMsFor = { 0 }
    }

    private suspend fun HttpClient.json(path: String, body: String = "{}"): HttpResponse =
        post("/api/rest/$path") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun HttpResponse.code(): String? = runBlocking {
        Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content
    }

    private suspend fun awaitSnapshot(what: String, test: (RigSafetyService.Snapshot) -> Boolean): RigSafetyService.Snapshot =
        withTimeout(5_000) {
            while (!test(rig.snapshot.value)) delay(10)
            rig.snapshot.value
        }.also { assertTrue(test(it), what) }

    private suspend fun awaitRunFinished() = awaitSnapshot("the run finished") { it.run?.finished == true }

    /** What [key]'s dimmer channel sends at full, after the scalers. */
    private fun dimmerOut(key: String): Int {
        val fixture = state.show.fixtures.untypedFixture(key) as DmxFixture
        val dimmer = fixture.fixtureProperties.first { it.category == PropertyCategory.DIMMER }.classProperty.call(fixture) as DmxSlider
        return state.show.globalScalerState.modify(Universe(0, 0), dimmer.channelNo, 255u).toInt()
    }

    @Test
    fun `make rig safe lamps off every lamped unit, blacks out, and is ready to switch off`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.rig()

        val resp = client.json("projects/$projectId/rig-safe")
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val run = assertNotNull(resp.body<RigSafetyResponse>().run)
        assertEquals("LAMP_OFF", run.role)
        assertEquals("MAKE_SAFE", run.purpose)
        assertEquals(listOf("mac1", "mac2"), run.units.map { it.fixture }, "the Varytec has no lamp")

        val safe = awaitSnapshot("ready to switch off") { it.ready }
        assertTrue(safe.blackout)
        assertEquals(listOf("mac1", "mac2"), safe.undo.map { it.first })
        assertEquals(setOf("mac1", "mac2"), safe.lampsOff.map { it.fixtureKey }.toSet())
        assertEquals(480_000, safe.lampsOff.first().let { it.restrikeAtMs - it.offAtMs }, "the MAC's restrike")
        assertEquals(0, dimmerOut("mac1"), "blacked out")
        assertFalse(state.scalerHolderFor(projectId).blackoutEnabled.value, "the persisted blackout is untouched")

        val message = safe.toMessage(quitAllowed = true)
        assertTrue(message.ready && message.blackout && message.quitAllowed)
        assertTrue(message.lampsOff.all { it.restrikeRemainingMs in 470_000..480_000 })
    }

    @Test
    fun `made safe, the rig is cooling until its last lamp has cooled, and only then ready`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.rig()
        val exited = kotlinx.coroutines.CompletableDeferred<Unit>()
        state.exitDesk = { exited.complete(Unit) }
        val t0 = System.currentTimeMillis()
        var now = t0
        rig.clock = { now }
        rig.coolDownMsFor = { 600_000 }

        client.json("projects/$projectId/rig-safe")
        val cooling = awaitSnapshot("cooling") { it.coolingUntilMs != null }
        assertFalse(cooling.ready)
        assertTrue(cooling.blackout)
        assertEquals(t0 + 600_000, cooling.coolingUntilMs)
        val message = cooling.toMessage(quitAllowed = true, nowMs = t0 + 100_000)
        assertEquals(500_000, message.coolingRemainingMs)
        assertTrue(message.lampsOff.all { it.coolRemainingMs == 500_000L })

        val quit = client.json("install/quit")
        assertEquals(HttpStatusCode.Accepted, quit.status, quit.bodyAsText())
        assertTrue(quit.body<QuitDeskResponse>().rigWasSafe, "quitting cuts no fixture's power")
        withTimeout(5_000) { exited.await() }

        now = t0 + 600_000
        val ready = awaitSnapshot("ready once cooled") { it.ready }
        assertNull(ready.coolingUntilMs)
        assertEquals(t0 + 600_000, ready.readySinceMs)
    }

    @Test
    fun `output during the cool-down drops it, and making safe again keeps each lamp's cool time`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.rig()
        val t0 = System.currentTimeMillis()
        var now = t0
        rig.clock = { now }
        rig.coolDownMsFor = { 600_000 }

        client.json("projects/$projectId/rig-safe")
        awaitSnapshot("cooling") { it.coolingUntilMs != null }
        now = t0 + 240_000
        ProgrammerHandler.set(state, TargetRef.Fixture("vary"), "dimmer", "200", 0)
        val after = awaitSnapshot("a programmer change ends the rig's cool-down") { it.coolingUntilMs == null }
        assertFalse(after.ready)
        assertTrue(
            after.toMessage(quitAllowed = true, nowMs = now).lampsOff.all { it.coolRemainingMs == 360_000L },
            "each lamp still says when its mains can be cut",
        )

        client.json("projects/$projectId/rig-safe")
        val again = awaitSnapshot("cooling again") { it.coolingUntilMs != null && it.run?.finished == true }
        assertEquals(t0 + 600_000, again.coolingUntilMs, "the lamps were already off and cooling")
        assertTrue(again.lampsOff.all { it.offAtMs == t0 })
        assertEquals(listOf("mac1", "mac2"), again.undo.map { it.first }, "the first run's lamps, remembered once")
    }

    @Test
    fun `anything that sends output clears ready, and the blackout stays until released`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.rig()
        client.json("projects/$projectId/rig-safe")
        awaitSnapshot("ready") { it.ready }

        ProgrammerHandler.set(state, TargetRef.Fixture("vary"), "dimmer", "200", 0)
        val after = awaitSnapshot("a programmer change clears it") { !it.ready }
        assertTrue(after.blackout, "the blackout is not the programmer's to lift")

        val release = client.json("projects/$projectId/rig-safe/release")
        assertEquals(HttpStatusCode.OK, release.status, release.bodyAsText())
        assertFalse(rig.snapshot.value.blackout)
        assertEquals(255, dimmerOut("mac1"))
        assertEquals(listOf("mac1", "mac2"), rig.snapshot.value.undo.map { it.first }, "Undo still knows what to strike")
    }

    @Test
    fun `a command other than a lamp-off clears ready`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.rig()
        client.json("projects/$projectId/rig-safe")
        awaitSnapshot("ready") { it.ready }
        val reset = client.json("projects/$projectId/commands", """{"role":"RESET","targets":[{"type":"fixture","key":"vary"}]}""")
        assertEquals(HttpStatusCode.OK, reset.status, reset.bodyAsText())
        awaitSnapshot("a reset sends output") { !it.ready }
    }

    @Test
    fun `undo strikes the lamps it turned off once their restrike time is up, then releases the blackout`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.rig()
        client.json("projects/$projectId/rig-safe", """{"targets":[{"type":"fixture","key":"mac1"}]}""")
        val selection = awaitRunFinished()
        assertEquals(listOf("mac1"), selection.undo.map { it.first }, "only the selection's lamp")
        assertTrue(selection.blackout)
        assertFalse(selection.ready, "MAC2's lamp was never turned off")
        assertEquals(listOf("MAC2"), selection.notOff)

        val later = System.currentTimeMillis() + 481_000
        rig.clock = { later }
        val undo = client.json("projects/$projectId/rig-safe/undo")
        assertEquals(HttpStatusCode.OK, undo.status, undo.bodyAsText())
        assertEquals("UNDO", undo.body<RigSafetyResponse>().run?.purpose)
        assertFalse(rig.snapshot.value.ready, "asking for the show back is not ready to switch off")

        val done = awaitSnapshot("the blackout released") { it.run?.finished == true && !it.blackout }
        assertEquals(BulkCommandRun.Status.DONE, done.run!!.units.single().status)
        assertTrue(done.undo.isEmpty(), "a struck lamp is forgotten")
        assertTrue(done.lampsOff.isEmpty(), "a struck lamp leaves the restrike ledger")
        assertEquals(255, dimmerOut("mac1"))
    }

    @Test
    fun `a strike inside a lamp's restrike time waits, is reported, and can be cancelled`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.rig()
        client.json("projects/$projectId/commands", """{"role":"LAMP_OFF"}""")
        awaitRunFinished()
        assertFalse(rig.snapshot.value.ready, "a bulk lamp-off is not Make rig safe")
        assertTrue(rig.snapshot.value.undo.isEmpty())

        val strike = client.json("projects/$projectId/commands", """{"role":"LAMP_ON"}""")
        assertEquals(HttpStatusCode.OK, strike.status, strike.bodyAsText())
        val waiting = awaitSnapshot("both wait") { s -> s.run?.units?.all { it.status == BulkCommandRun.Status.WAITING } == true }
        val wire = waiting.toMessage(quitAllowed = false).run!!.units
        assertTrue(wire.all { (it.remainingMs ?: 0) in 470_000..480_000 }, "$wire")

        val busy = client.json("projects/$projectId/commands", """{"role":"RESET"}""")
        assertEquals(HttpStatusCode.Conflict, busy.status)
        assertEquals(RigSafetyService.CODE_RUN_BUSY, busy.code())

        assertEquals(HttpStatusCode.NoContent, client.json("projects/$projectId/commands/cancel").status)
        val cancelled = awaitRunFinished()
        assertTrue(cancelled.run!!.units.all { it.status == BulkCommandRun.Status.CANCELLED })
        assertEquals(HttpStatusCode.Conflict, client.json("projects/$projectId/commands/cancel").status)
    }

    @Test
    fun `a bulk run is refused whole when nothing could run, and per unit when one is busy`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.rig()

        val none = client.json("projects/$projectId/commands", """{"role":"LAMP_ON","targets":[{"type":"fixture","key":"vary"}]}""")
        assertEquals(HttpStatusCode.Conflict, none.status)
        assertEquals(RigSafetyService.CODE_NO_UNITS, none.code())
        val empty = client.json("projects/$projectId/commands", """{"role":"LAMP_ON","targets":[]}""")
        assertEquals(HttpStatusCode.BadRequest, empty.status)
        assertEquals(CODE_NEEDS_SELECTION, empty.code())
        val role = client.json("projects/$projectId/commands", """{"role":"OTHER"}""")
        assertEquals(RigSafetyService.CODE_ROLE_UNKNOWN, role.code())

        state.show.commandOutput.holdMsFor = { 2_000 }
        assertTrue(state.show.commandOutput.run("mac1", "reset") is uk.me.cormack.lighting7.state.CommandOutput.Outcome.Started)
        state.show.commandOutput.holdMsFor = { 50 }
        val resp = client.json("projects/$projectId/commands", """{"role":"LAMP_OFF"}""")
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val finished = awaitRunFinished().run!!.units.associateBy { it.unit.fixtureKey }
        assertEquals(BulkCommandRun.Status.SKIPPED, finished.getValue("mac1").status)
        assertEquals("COMMAND_BUSY", finished.getValue("mac1").code)
        assertEquals(BulkCommandRun.Status.DONE, finished.getValue("mac2").status)
    }

    @Test
    fun `make rig safe that cannot turn every lamp off blacks out but is not ready`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.rig()
        state.show.commandOutput.holdMsFor = { 2_000 }
        state.show.commandOutput.run("mac2", "reset")
        state.show.commandOutput.holdMsFor = { 50 }
        client.json("projects/$projectId/rig-safe")
        val after = awaitRunFinished()
        assertTrue(after.blackout)
        assertFalse(after.ready)
        assertEquals(listOf("MAC2"), after.notOff)
    }

    @Test
    fun `quit is refused while the rig is not safe unless forced, and exits once answered`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val exited = kotlinx.coroutines.CompletableDeferred<Unit>()
        state.exitDesk = { exited.complete(Unit) }

        val refused = client.json("install/quit")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(CODE_RIG_NOT_SAFE, refused.code())
        assertFalse(exited.isCompleted)

        val forced = client.json("install/quit", """{"force":true}""")
        assertEquals(HttpStatusCode.Accepted, forced.status, forced.bodyAsText())
        assertFalse(forced.body<QuitDeskResponse>().rigWasSafe)
        withTimeout(5_000) { exited.await() }
    }

    @Test
    fun `a project switch drops the run and ready, and Undo is kept per project`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.rig()
        client.json("projects/$projectId/rig-safe")
        awaitSnapshot("ready") { it.ready }

        val other = seedMinimalProject(state, projectName = "Other", universe = 1)
        state.projectManager.switchProject(other)
        awaitSnapshot("another show") { it.projectId == other }
        assertFalse(rig.snapshot.value.ready)
        assertFalse(rig.snapshot.value.blackout, "the other show is not blacked out")
        assertTrue(rig.snapshot.value.undo.isEmpty())
        assertNull(rig.snapshot.value.run)

        state.projectManager.switchProject(projectId)
        val back = awaitSnapshot("back") { it.projectId == projectId }
        assertTrue(back.blackout, "the blackout is this project's")
        assertEquals(listOf("mac1", "mac2"), back.undo.map { it.first })
    }

    @Test
    fun `bulk commands and make rig safe are runtime gestures on a venue`() {
        for (rest in listOf(listOf("commands"), listOf("commands", "cancel"), listOf("rig-safe"), listOf("rig-safe", "undo"), listOf("rig-safe", "release"))) {
            assertNull(projectWriteRefusal(uk.me.cormack.lighting7.models.ProjectKind.VENUE, isAdmin = false, rest = rest), "$rest")
        }
    }

    @Test
    fun `a selection's run is ready when the lamps outside it are already off, and Undo leaves those alone`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.rig()
        client.json("projects/$projectId/commands", """{"role":"LAMP_OFF","targets":[{"type":"fixture","key":"mac2"}]}""")
        awaitRunFinished()

        client.json("projects/$projectId/rig-safe", """{"targets":[{"type":"fixture","key":"mac1"}]}""")
        val ready = awaitSnapshot("ready") { it.ready }
        assertEquals(listOf("mac1"), ready.undo.map { it.first })

        // A whole-rig run re-sends MAC2's lamp-off, but MAC2 was off before it: Undo must not strike it.
        client.json("projects/$projectId/rig-safe/release")
        client.json("projects/$projectId/rig-safe")
        val again = awaitSnapshot("ready again") { it.ready && it.run?.finished == true }
        assertEquals(listOf("mac1"), again.undo.map { it.first }, "MAC1 is remembered from the first run; MAC2 was already off")
    }

    @Test
    fun `undo and release are refused while a run is in flight, and a refused undo forgets nothing`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.rig()
        client.json("projects/$projectId/rig-safe")
        awaitSnapshot("ready") { it.ready }

        state.show.commandOutput.holdMsFor = { 2_000 }
        assertEquals(HttpStatusCode.OK, client.json("projects/$projectId/commands", """{"role":"RESET","targets":[{"type":"fixture","key":"vary"}]}""").status)
        val undo = client.json("projects/$projectId/rig-safe/undo")
        assertEquals(HttpStatusCode.Conflict, undo.status)
        assertEquals(RigSafetyService.CODE_RUN_BUSY, undo.code())
        val release = client.json("projects/$projectId/rig-safe/release")
        assertEquals(HttpStatusCode.Conflict, release.status)
        assertEquals(RigSafetyService.CODE_RUN_BUSY, release.code())
        assertEquals(listOf("mac1", "mac2"), rig.snapshot.value.undo.map { it.first })
        assertTrue(rig.snapshot.value.blackout)
    }
}
