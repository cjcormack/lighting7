package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.CommandNotStorableException
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.stripCommandRows
import uk.me.cormack.lighting7.plugins.ProgrammerErrorOutMessage
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.plugins.UpdateChannelInMessage
import uk.me.cormack.lighting7.plugins.handleUpdateChannel
import uk.me.cormack.lighting7.state.CommandOutput
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixture commands (fixture optics plan session 7, D13), end to end over a real desk with patched
 * fixtures on a MOCK universe: the command output that owns a dedicated channel and guards a shared
 * one, the route and its refusals, the write boundaries that refuse a command by name, and the
 * stored-row strip.
 *
 * Patched: a Revolution at 1 (its reset on 12, dedicated), a MAC 250 at 40 (shutter 40, shared with
 * the strobe; colour 42, gobo 43, prism 46), a Varytec at 60 (reset on 70, dedicated), a Fusion
 * 15ch at 80 (motor mode 94, shared) and an Orbit at 100 (program 112, shared).
 */
class FixtureCommandsRoutesTest : RouteIntegrationTest() {

    private suspend fun patch(client: HttpClient, typeKey: String, key: String, start: Int): Int {
        val resp = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(CreatePatchRequest(universe = 0, fixtureTypeKey = typeKey, key = key, name = key.uppercase(), startChannel = start))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<FixturePatchDto>().id
    }

    private suspend fun rig(client: HttpClient): Map<String, Int> = mapOf(
        "rev" to patch(client, "etc-source4-revolution-base-frame", "rev", 1),
        "mac" to patch(client, "martin-mac-250-mode-4", "mac", 40),
        "vary" to patch(client, "varytec-easymove-xl-60-spot-11ch", "vary", 60),
        "fusion" to patch(client, "fusion-100-spot-mkii-15ch", "fusion", 80),
        "orbit" to patch(client, "gear4music-orbit-70-13ch", "orbit", 100),
    )

    private suspend fun command(client: HttpClient, patchId: Int, name: String): HttpResponse =
        client.post("/api/rest/projects/$projectId/patches/$patchId/commands/$name")

    private suspend fun json(client: HttpClient, path: String, body: String): HttpResponse =
        client.post("/api/rest/projects/$projectId/$path") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun HttpResponse.code(): String? = runBlocking {
        Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content
    }

    /** What a channel puts out, as transmit reads it: park and the held outputs over the buffer, then the band guard. */
    private fun out(channel: Int): Int {
        state.show.outputSource.getParkedValue(0, channel)?.let { return it.toInt() }
        val buffered = state.show.fixtures.controllers.first().currentValues[channel] ?: 0u
        return state.show.commandOutput.modify(Universe(0, 0), channel, buffered).toInt()
    }

    private suspend fun awaitOut(channel: Int, value: Int) {
        withTimeout(5_000) { while (out(channel) != value) delay(10) }
    }

    @Test
    fun `a dedicated channel is the desk's, held idle - a raw write is dropped and a park that is a command refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        rig(client)
        val output = state.show.commandOutput

        assertEquals(0, out(12))
        assertEquals("rev", output.ownerOf(0, 12)?.fixtureKey)
        assertEquals("vary", output.ownerOf(0, 70)?.fixtureKey)
        assertNull(output.ownerOf(0, 40), "the MAC's shutter is the strobe property's too")

        handleUpdateChannel(state, UpdateChannelInMessage(universe = 0, id = 12, level = 187u, fadeTime = 0))
        assertTrue(state.show.programmerStore.channelEntries().isEmpty())
        assertEquals(0, out(12))

        // On a dedicated channel anything but idle would be a command; on a shared one, its band.
        assertNotNull(output.parkRefusal(0, 12, 149u))
        assertNotNull(output.parkRefusal(0, 12, 1u))
        assertNull(output.parkRefusal(0, 12, 0u))
        assertNotNull(output.parkRefusal(0, 40, 210u))
        assertNull(output.parkRefusal(0, 40, 35u))
        assertNull(output.parkRefusal(0, 41, 255u))
    }

    @Test
    fun `a band value on a shared channel goes out as idle, whatever wrote it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        rig(client)
        val programmer = ProgrammerHandler.set(state, TargetRef.Fixture("orbit"), "program", "200", 0)
        assertFalse(programmer is ProgrammerErrorOutMessage, "$programmer")
        awaitOut(112, 0)
        ProgrammerHandler.set(state, TargetRef.Fixture("orbit"), "program", "100", 0)
        awaitOut(112, 100)

        val output = state.show.commandOutput
        assertEquals(0u.toUByte(), output.modify(Universe(0, 0), 40, 210u), "the MAC's reset band")
        assertEquals(0u.toUByte(), output.modify(Universe(0, 0), 40, 250u), "its lamp-off band")
        assertEquals(35u.toUByte(), output.modify(Universe(0, 0), 40, 35u), "its open band passes")
        assertEquals(0u.toUByte(), output.modify(Universe(0, 0), 94, 253u), "the Fusion's reset band")
        assertEquals(151u.toUByte(), output.modify(Universe(0, 0), 94, 151u))
    }

    @Test
    fun `reset scroller holds its band past the manual's three seconds, then drops, and a second command waits its turn`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val ids = rig(client)
        coroutineScope {
            val started = System.currentTimeMillis()
            val run = async { command(client, ids.getValue("rev"), "resetScroller") }
            awaitOut(12, 149)

            val busy = command(client, ids.getValue("rev"), "reset")
            assertEquals(HttpStatusCode.Conflict, busy.status, busy.bodyAsText())
            assertEquals("COMMAND_BUSY", busy.code())
            assertEquals("resetScroller", state.show.commandOutput.runningOn("rev")?.command?.name, "the first command still holds")

            val done = run.await()
            assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
            val body = done.body<FixtureCommandResponse>()
            assertTrue(body.completed)
            assertEquals("Reset scroller", body.label)
            assertTrue(System.currentTimeMillis() - started >= 3_500, "held for the manual's three seconds, and the margin over them")
            assertEquals(0, out(12))
            assertNull(state.show.commandOutput.runningOn("rev"))
        }
    }

    @Test
    fun `the MAC's reset sets the preconditions for its hold, and a repatch interrupts it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val ids = rig(client)
        val outcome = state.show.commandOutput.run("mac", "reset")
        val running = assertIs<CommandOutput.Outcome.Started>(outcome).running
        assertEquals(208, out(40))
        assertEquals(200, out(42), "CTC filter in")
        assertEquals(0, out(43), "open gobo")
        assertEquals(80, out(46), "prism in, not rotating")

        val deleted = client.delete("/api/rest/projects/$projectId/patches/${ids.getValue("mac")}")
        assertTrue(deleted.status.value in 200..299, deleted.bodyAsText())
        assertEquals(CommandOutput.Ended.INTERRUPTED, withTimeout(3_000) { running.done.await() })
        assertNull(state.show.outputSource.getParkedValue(0, 42), "the precondition goes back with the command")
    }

    @Test
    fun `a park landing on a held channel mid-hold cuts the hold short`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        rig(client)
        // A precondition's channel: the MAC's colour wheel, parked under a running reset.
        val mac = assertIs<CommandOutput.Outcome.Started>(state.show.commandOutput.run("mac", "reset")).running
        assertEquals(200, out(42))
        state.show.parkManager.park(0, 42, 0u)
        assertEquals(CommandOutput.Ended.INTERRUPTED, withTimeout(2_000) { mac.done.await() })
        assertNull(state.show.commandOutput.runningOn("mac"))
        assertEquals(0, out(40), "the shutter is handed back, not left in the reset band")

        // And the command's own channel: a lock-out park at idle on the Revolution's dedicated channel.
        val rev = assertIs<CommandOutput.Outcome.Started>(state.show.commandOutput.run("rev", "resetPanTilt")).running
        awaitOut(12, 127)
        state.show.parkManager.park(0, 12, 0u)
        assertEquals(CommandOutput.Ended.INTERRUPTED, withTimeout(2_000) { rev.done.await() })
        assertEquals(0, out(12))
    }

    @Test
    fun `blind, a parked channel, an unknown command and an unknown patch are refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val ids = rig(client)

        val unknown = command(client, ids.getValue("rev"), "lampOn")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertEquals("COMMAND_UNKNOWN", unknown.code())
        assertEquals(HttpStatusCode.NotFound, command(client, 99_999, "reset").status)

        state.show.programmerStore.blind = true
        try {
            val blind = command(client, ids.getValue("vary"), "reset")
            assertEquals(HttpStatusCode.Conflict, blind.status)
            assertEquals("COMMAND_BLIND", blind.code())
        } finally {
            state.show.programmerStore.blind = false
        }

        // A lock-out park at idle is allowed, and then the command cannot reach the fixture.
        state.show.parkManager.park(0, 70, 0u)
        val parked = command(client, ids.getValue("vary"), "reset")
        assertEquals(HttpStatusCode.Conflict, parked.status)
        assertEquals("COMMAND_PARKED", parked.code())
        // The MAC's reset holds the colour wheel too: parked there, it is refused as well.
        state.show.parkManager.park(0, 42, 0u)
        assertIs<CommandOutput.Outcome.Parked>(state.show.commandOutput.run("mac", "reset"))
        assertEquals(0, out(70))
    }

    @Test
    fun `a park that would hold a command is passed over and dropped at show start`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        state.show.parkManager.park(0, 12, 187u)
        state.show.parkManager.park(0, 40, 210u)
        state.show.parkManager.park(0, 41, 200u)
        rig(client)
        assertEquals(0, out(12))
        assertFalse(state.show.outputSource.isParked(0, 12))
        state.show.dropRefusedParks()
        assertEquals(listOf(41), state.show.parkManager.getAllParked().filter { it.universe == 0 && it.channel in listOf(12, 40, 41) }.map { it.channel })
    }

    @Test
    fun `no Look, cue, effect, programmer value or binding can hold a command`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        rig(client)

        val look = json(client, "looks", """{"name":"Oops","rows":[
            {"targetType":"fixture","targetKey":"vary","propertyName":"reset","value":"255"},
            {"targetType":"fixture","targetKey":"mac","propertyName":"lampOff","value":"248"}]}""")
        assertEquals(HttpStatusCode.BadRequest, look.status, look.bodyAsText())
        assertEquals(CommandNotStorableException.CODE, look.code())
        assertTrue("rows[0]: 'reset'" in look.bodyAsText() && "rows[1]: 'lampOff'" in look.bodyAsText(), look.bodyAsText())
        assertEquals(0L, transaction(state.database) { DaoLook.count() })

        val stack = client.post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "Act 1"))
        }.body<CueStackDetails>().id
        val cue = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Q1", cueStackId = stack, propertyAssignments = listOf(
                uk.me.cormack.lighting7.models.CuePropertyAssignmentDto(targetType = "fixture", targetKey = "rev", propertyName = "resetScroller", value = "149"),
            )))
        }
        assertEquals(HttpStatusCode.BadRequest, cue.status, cue.bodyAsText())
        assertEquals(CommandNotStorableException.CODE, cue.code())

        val template = json(client, "templates", """{"name":"T","rows":[{"targetType":"deferred","targetKey":"","propertyName":"reset","value":"255"}]}""")
        assertEquals(HttpStatusCode.BadRequest, template.status, template.bodyAsText())
        assertEquals(CommandNotStorableException.CODE, template.code())

        val effect = client.post("/api/rest/fx/add") {
            contentType(ContentType.Application.Json)
            setBody("""{"effectType":"StaticValue","fixtureKey":"vary","propertyName":"reset","beatDivision":1.0}""")
        }
        assertEquals(HttpStatusCode.BadRequest, effect.status, effect.bodyAsText())
        assertTrue("fixture command" in effect.bodyAsText(), effect.bodyAsText())

        val programmer = ProgrammerHandler.set(state, TargetRef.Fixture("mac"), "reset", "208", 0)
        assertTrue(programmer is ProgrammerErrorOutMessage && "fixture command" in programmer.message, "$programmer")

        for (target in listOf(
            uk.me.cormack.lighting7.midi.BindingTarget.FixtureProperty("vary", "reset"),
            uk.me.cormack.lighting7.midi.BindingTarget.SelectionProperty("lampOff"),
        )) {
            val refused = assertFailsWith<uk.me.cormack.lighting7.midi.BindingRefused> {
                state.controlSurfaceBindingService.create(
                    projectId = projectId, deviceTypeKey = "x-touch-compact-standard", controlId = "fader-1", bank = null, target = target,
                )
            }
            assertEquals(CommandNotStorableException.CODE, refused.code)
        }

        // And the AI's create_look, whose deferred effects land on whatever is selected.
        val ai = AiTools(state).executeTool(
            "create_look",
            Json.parseToJsonElement(
                """{"name":"X","effects":[{"effectType":"Pulse","category":"dimmer","propertyName":"reset","beatDivision":1.0,"blendMode":"OVERRIDE"}]}""",
            ).jsonObject,
        )
        assertFalse(ai.success, ai.description)
        assertTrue("fixture command" in ai.description, ai.description)
    }

    @Test
    fun `a stored row holding a level inside a shared command's band is refused, by the strip's rule`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        rig(client)
        // A group of the Orbit and the Revolution: judged by every member's bands, the Orbit's here.
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val group = DaoFixtureGroup.new { this.project = project; name = "Movers" }
            listOf("orbit", "rev").forEachIndexed { i, key ->
                DaoFixtureGroupMember.new {
                    this.group = group
                    fixturePatch = DaoFixturePatch.find { DaoFixturePatches.key eq key }.first()
                    sortOrder = i
                }
            }
        }

        // The MAC's strobe at 210 is its reset; the Fusion's motor mode at 251 is its reset.
        val look = json(client, "looks", """{"name":"Bad","rows":[
            {"targetType":"fixture","targetKey":"mac","propertyName":"strobe","value":"210"},
            {"targetType":"fixture","targetKey":"fusion","propertyName":"motorMode","value":"251"}]}""")
        assertEquals(HttpStatusCode.BadRequest, look.status, look.bodyAsText())
        assertEquals(CommandNotStorableException.CODE, look.code())
        assertTrue("rows[0]: strobe = 210" in look.bodyAsText() && "'reset' command's band (208–217)" in look.bodyAsText(), look.bodyAsText())
        assertTrue("rows[1]: motorMode = 251" in look.bodyAsText(), look.bodyAsText())
        assertEquals(0L, transaction(state.database) { DaoLook.count() })

        // A level outside the band, and the same level on a property that shares nothing, are kept.
        val ok = json(client, "looks", """{"name":"Good","rows":[
            {"targetType":"fixture","targetKey":"mac","propertyName":"strobe","value":"100"},
            {"targetType":"fixture","targetKey":"orbit","propertyName":"dimmer","value":"210"}]}""")
        assertTrue(ok.status.isSuccess(), ok.bodyAsText())

        val stack = client.post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "Act 1"))
        }.body<CueStackDetails>().id
        val cue = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Q1", cueStackId = stack, propertyAssignments = listOf(
                uk.me.cormack.lighting7.models.CuePropertyAssignmentDto(targetType = "fixture", targetKey = "orbit", propertyName = "program", value = "200"),
            )))
        }
        assertEquals(HttpStatusCode.BadRequest, cue.status, cue.bodyAsText())
        assertEquals(CommandNotStorableException.CODE, cue.code())
        assertTrue("propertyAssignments[0]: program = 200 on 'ORBIT'" in cue.bodyAsText(), cue.bodyAsText())

        val groupLook = json(client, "looks", """{"name":"Group","rows":[
            {"targetType":"group","targetKey":"Movers","propertyName":"program","value":"230"}]}""")
        assertEquals(HttpStatusCode.BadRequest, groupLook.status, groupLook.bodyAsText())
        assertTrue("program = 230 on a member of group 'Movers'" in groupLook.bodyAsText(), groupLook.bodyAsText())
    }

    @Test
    fun `the strip pass removes stored commands by name and by band, and keeps the rest`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        rig(client)
        val stack = client.post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "Act 1"))
        }.body<CueStackDetails>().id
        val q1 = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Q1", cueStackId = stack))
        }.body<CueDetails>().id
        val stripped = transaction(state.database) {
            val project = DaoProject[projectId]
            val look = DaoLook.new { this.project = project; name = "Old" }
            // The Varytec's reset was a two-option setting: stored by name.
            DaoLookRow.new { this.look = look; targetType = "fixture"; targetKey = "vary"; propertyName = "reset"; value = "255" }
            // The Fusion's and the Orbit's lost RESET options: stored by level.
            DaoLookRow.new { this.look = look; targetType = "fixture"; targetKey = "fusion"; propertyName = "motorMode"; value = "251" }
            DaoLookRow.new { this.look = look; targetType = "fixture"; targetKey = "orbit"; propertyName = "program"; value = "200" }
            // Kept: an ordinary option on the same channels.
            DaoLookRow.new { this.look = look; targetType = "fixture"; targetKey = "fusion"; propertyName = "motorMode"; value = "151" }
            DaoLookRow.new { this.look = look; targetType = "fixture"; targetKey = "orbit"; propertyName = "program"; value = "100" }
            // A typed MAC strobe row past its clamp, in the reset band — and an open one, kept.
            DaoCuePropertyAssignment.new { cue = DaoCue[q1]; targetType = "fixture"; targetKey = "mac"; propertyName = "strobe"; value = "210" }
            DaoCuePropertyAssignment.new { cue = DaoCue[q1]; targetType = "fixture"; targetKey = "mac"; propertyName = "strobe"; value = "35" }
            stripCommandRows(project)
        }
        assertEquals(4, stripped)
        assertEquals(listOf("151", "100"), transaction(state.database) { DaoLookRow.all().map { it.value } })
        assertEquals(listOf("35"), transaction(state.database) { DaoCuePropertyAssignment.all().map { it.value } })
    }
}
