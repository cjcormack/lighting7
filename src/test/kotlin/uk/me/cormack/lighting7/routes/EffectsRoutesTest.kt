package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.fixture.FixtureTriggers
import uk.me.cormack.lighting7.fixture.TriggerNotStorableException
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoEffectTubeStates
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.stripTriggerRows
import uk.me.cormack.lighting7.models.CueEventDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.plugins.ProgrammerErrorOutMessage
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.plugins.UpdateChannelInMessage
import uk.me.cormack.lighting7.plugins.handleUpdateChannel
import uk.me.cormack.lighting7.state.EffectsService
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One-shot triggers, cue events and the arm (stage-view plan session 9), end to end over a real
 * desk with a patched Twin Shot on a MOCK universe: the write boundaries that refuse a trigger by
 * name, the trigger output that owns its channels, arm / fire / reload over REST, and cue events
 * on GO — unarmed, armed, GO TO, GO BACK, blind and the AI's `apply_cue`.
 */
class EffectsRoutesTest : RouteIntegrationTest() {

    /** Universe 0, channels 1 (tube A), 2 (tube B), 3 (master). */
    private suspend fun cannon(client: HttpClient, key: String = "cannon", start: Int = 1): Int {
        val resp = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(CreatePatchRequest(universe = 0, fixtureTypeKey = "equinox-twin-shot-mkii", key = key, name = "Cannon $key", startChannel = start))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<FixturePatchDto>().id
    }

    private suspend fun stack(client: HttpClient, name: String): Int =
        client.post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    private suspend fun cue(client: HttpClient, stackId: Int, number: String): Int =
        client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Q$number", cueStackId = stackId, cueNumber = number))
        }.body<CueDetails>().id

    private suspend fun json(client: HttpClient, path: String, body: String, put: Boolean = false): HttpResponse {
        val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return if (put) client.put("/api/rest/projects/$projectId/$path", block) else client.post("/api/rest/projects/$projectId/$path", block)
    }

    private fun HttpResponse.code(): String? = runBlocking {
        Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content
    }

    /** What the cannon's channel puts out: the trigger output's held value over the buffer, as transmit reads it. */
    private fun out(channel: Int): Int =
        (state.show.outputSource.getParkedValue(0, channel) ?: state.show.fixtures.controllers.first().getValue(channel)).toInt()

    private fun awaitOut(channel: Int, value: Int) = runBlocking {
        withTimeout(3_000) { while (out(channel) != value) delay(10) }
    }

    @Test
    fun `the cannon's outputs are triggers and its master their arm, owned by the desk and idle`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        cannon(client)

        val fixture = state.show.fixtures.untypedFixture("cannon")
        assertTrue(fixture.fixtureProperties.isEmpty(), "no trigger or arm is a @FixtureProperty")
        assertEquals(listOf("output1", "output2"), FixtureTriggers.of(fixture).map { it.name })
        assertEquals(listOf(0, 0, 0), (1..3).map { out(it) })

        // A raw channel write on a trigger or its arm is dropped, not parked in the sideband.
        handleUpdateChannel(state, UpdateChannelInMessage(universe = 0, id = 1, level = 255u, fadeTime = 0))
        handleUpdateChannel(state, UpdateChannelInMessage(universe = 0, id = 3, level = 255u, fadeTime = 0))
        assertTrue(state.show.programmerStore.channelEntries().isEmpty())
        assertEquals(listOf(0, 0, 0), (1..3).map { out(it) })

        // A park at a firing level would be a held fire; below it, a lock-out, which is allowed.
        assertNotNull(state.show.triggerOutput.parkRefusal(0, 1, 255u))
        assertNotNull(state.show.triggerOutput.parkRefusal(0, 3, 51u))
        assertNull(state.show.triggerOutput.parkRefusal(0, 1, 0u))
        assertNull(state.show.triggerOutput.parkRefusal(0, 4, 255u))
    }

    @Test
    fun `extending a held arm keeps the events scheduled under it, and a disarm drops them`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        cannon(client)
        val svc = state.effectsService
        svc.arm(30)
        runBlocking {
            val fires = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3_000) { svc.fired.first() } }
            svc.onCueGo(1, "Q1", listOf(EffectsService.CueEventFire("cannon", "output1", 300)))
            svc.arm(60) // an extension, not a drop
            assertEquals("output1", fires.await().trigger)
        }
        svc.reload("cannon", null)
        runBlocking {
            val skips = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3_000) { svc.skipped.first() } }
            svc.onCueGo(1, "Q1", listOf(EffectsService.CueEventFire("cannon", "output1", 300)))
            svc.disarm("test")
            svc.arm(60) // armed again, but the arm the event was scheduled under has dropped
            assertEquals(EffectsService.SkipReason.ARM_DROPPED, skips.await().reason)
        }
        svc.disarm("test")
    }

    @Test
    fun `a park at a firing level that never met the live check is passed over and dropped`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        // Stored before the channels were a cannon's — what a pre-session-9 row, an import or a
        // clone looks like: a held fire on tube A, a held arm, and a lock-out on tube B.
        runBlocking {
            state.show.parkManager.park(0, 1, 255u)
            state.show.parkManager.park(0, 3, 255u)
            state.show.parkManager.park(0, 2, 0u)
        }
        cannon(client)

        // The output passes the two firing-level parks over; the lock-out stands.
        assertEquals(listOf(0, 0, 0), (1..3).map { out(it) })
        assertFalse(state.show.outputSource.isParked(0, 1))
        assertTrue(state.show.outputSource.isParked(0, 2))

        // And the start-of-show pass drops the stored rows, leaving the lock-out.
        state.show.dropRefusedParks()
        assertEquals(listOf(2), state.show.parkManager.getAllParked().filter { it.universe == 0 && it.channel <= 3 }.map { it.channel })
    }

    @Test
    fun `no Look, cue, effect, programmer value or binding can hold a trigger`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        cannon(client)
        val s = stack(client, "Act 1")

        val look = json(client, "looks", """{"name":"Bang","rows":[
            {"targetType":"fixture","targetKey":"cannon","propertyName":"output1","value":"255"},
            {"targetType":"fixture","targetKey":"cannon","propertyName":"master","value":"255"}]}""")
        assertEquals(HttpStatusCode.BadRequest, look.status, look.bodyAsText())
        assertEquals(TriggerNotStorableException.CODE, look.code())
        val text = look.bodyAsText()
        assertTrue("rows[0]: 'output1'" in text && "rows[1]: 'master'" in text, text)
        assertEquals(0L, transaction(state.database) { DaoLook.count() })

        val cueRefused = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Q9", cueStackId = s, propertyAssignments = listOf(
                uk.me.cormack.lighting7.models.CuePropertyAssignmentDto(targetType = "fixture", targetKey = "cannon", propertyName = "output2", value = "255"),
            )))
        }
        assertEquals(HttpStatusCode.BadRequest, cueRefused.status, cueRefused.bodyAsText())
        assertTrue("propertyAssignments[0]: 'output2'" in cueRefused.bodyAsText())
        assertEquals(0L, transaction(state.database) { DaoCue.count() })

        val template = json(client, "templates", """{"name":"T","rows":[{"targetType":"deferred","targetKey":"","propertyName":"output1","value":"255"}]}""")
        assertEquals(HttpStatusCode.BadRequest, template.status, template.bodyAsText())
        // Named as a trigger, not as a slotted property a recorded Look could hold.
        assertTrue("one-shot trigger" in template.bodyAsText(), template.bodyAsText())
        assertEquals("TRIGGER_NOT_STORABLE", template.code())

        val effect = client.post("/api/rest/fx/add") {
            contentType(ContentType.Application.Json)
            setBody("""{"effectType":"StaticValue","fixtureKey":"cannon","propertyName":"output1","beatDivision":1.0}""")
        }
        assertEquals(HttpStatusCode.BadRequest, effect.status, effect.bodyAsText())
        assertTrue("one-shot trigger" in effect.bodyAsText(), effect.bodyAsText())

        val programmer = ProgrammerHandler.set(state, TargetRef.Fixture("cannon"), "output1", "255", 0)
        assertTrue(programmer is ProgrammerErrorOutMessage && "one-shot trigger" in programmer.message, "$programmer")

        for (target in listOf(
            uk.me.cormack.lighting7.midi.BindingTarget.FixtureProperty("cannon", "output1"),
            uk.me.cormack.lighting7.midi.BindingTarget.SelectionProperty("output2"),
        )) {
            val refused = assertFailsWith<uk.me.cormack.lighting7.midi.BindingRefused> {
                state.controlSurfaceBindingService.create(
                    projectId = projectId, deviceTypeKey = "x-touch-compact-standard", controlId = "fader-1", bank = null, target = target,
                )
            }
            assertEquals(TriggerNotStorableException.CODE, refused.code)
        }
        // The surface's door to a tube is FireTrigger, a button.
        val fire = state.controlSurfaceBindingService.create(
            projectId = projectId, deviceTypeKey = "x-touch-compact-standard", controlId = "btn-1", bank = null,
            target = uk.me.cormack.lighting7.midi.BindingTarget.FireTrigger("cannon", "A"),
        )
        assertEquals(uk.me.cormack.lighting7.models.AssignmentHealth.Ok, fire.health)
    }

    @Test
    fun `arm drives the master, a fire pulses its tube once, a spent tube sends nothing, and reload clears it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val patchId = cannon(client)

        val unarmed = json(client, "patches/$patchId/fire", """{"trigger":"output1"}""")
        assertEquals(HttpStatusCode.Conflict, unarmed.status)
        assertEquals("TRIGGER_NOT_ARMED", unarmed.code())
        assertEquals(0, out(1))

        val armed = json(client, "effects/arm", """{"on":true,"seconds":30}""")
        assertEquals(HttpStatusCode.OK, armed.status, armed.bodyAsText())
        assertTrue(armed.body<EffectsStateDto>().armed)
        assertEquals(255, out(3))

        val fired = json(client, "patches/$patchId/fire", """{"trigger":"A"}""")
        assertEquals(HttpStatusCode.OK, fired.status, fired.bodyAsText())
        assertEquals("output1", fired.body<FireResponse>().fired.trigger)
        assertEquals(255, out(1))
        awaitOut(1, 0)
        assertEquals(0, out(2))

        val again = json(client, "patches/$patchId/fire", """{"trigger":"output1"}""")
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("TRIGGER_SPENT", again.code())
        assertEquals(0, out(1))
        assertEquals(listOf("output1"), state.effectsService.armed.value.spent.map { it.trigger })
        awaitSpentRows(1)

        val unknown = json(client, "patches/$patchId/fire", """{"trigger":"output9"}""")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)

        val reloaded = json(client, "patches/$patchId/reload", """{}""")
        assertEquals(HttpStatusCode.OK, reloaded.status)
        assertTrue(reloaded.body<EffectsStateDto>().spent.isEmpty())
        awaitSpentRows(0)

        val off = json(client, "effects/arm", """{"on":false}""")
        assertFalse(off.body<EffectsStateDto>().armed)
        assertEquals(0, out(3))
    }

    @Test
    fun `a stack stop and the lapse both drop the arm`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        cannon(client)
        val s = stack(client, "Act 1")
        cue(client, s, "1")
        state.effectsService.arm(30)
        state.show.cueStackManager.activateAtFirstCue(state, s)
        assertTrue(state.effectsService.armed.value.armed)
        state.show.cueStackManager.deactivateStack(s, state)
        assertFalse(state.effectsService.armed.value.armed)
        assertEquals(0, out(3))

        state.effectsService.arm(5)
        assertTrue(state.effectsService.armed.value.armed)
        // The minimum arm is five seconds; the clamp means a shorter ask still holds for five.
        assertTrue((state.effectsService.armed.value.armedUntilMs ?: 0) - System.currentTimeMillis() in 3_000..5_000)
    }

    @Test
    fun `cue events are a whole-list write, every problem at once`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val patchId = cannon(client)
        val other = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(CreatePatchRequest(universe = 0, fixtureTypeKey = "generic-dimmer", key = "dim", name = "Dim", startChannel = 10))
        }.body<FixturePatchDto>().id
        val s = stack(client, "Act 1")
        val q5 = cue(client, s, "5")

        val refused = json(client, "cues/$q5/events", """{"events":[
            {"patchId":$patchId,"trigger":"output3","offsetMs":0},
            {"patchId":$other,"trigger":"output1"},
            {"patchId":99999,"trigger":"A"},
            {"patchId":$patchId,"trigger":"A","offsetMs":-1},
            {"patchId":$patchId,"trigger":"B"},
            {"patchId":$patchId,"trigger":"output2"}]}""", put = true)
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        val text = refused.bodyAsText()
        for (expected in listOf("is not one of its triggers", "has no one-shot trigger", "names no patch", "offsetMs must be between", "fires B twice")) {
            assertTrue(expected in text, "'$expected' in $text")
        }

        val ok = json(client, "cues/$q5/events", """{"events":[{"patchId":$patchId,"trigger":"A","offsetMs":600},{"patchId":$patchId,"trigger":"output2","offsetMs":750}]}""", put = true)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val events = ok.body<List<CueEventDto>>()
        assertEquals(listOf("output1" to 600L, "output2" to 750L), events.map { it.trigger to it.offsetMs })
        assertEquals(listOf("A", "B"), events.map { it.triggerLabel })

        // Re-saving the same list keeps each row's uuid, so an unchanged save is no sync change.
        val again = json(client, "cues/$q5/events", """{"events":[{"patchId":$patchId,"trigger":"A","offsetMs":600},{"patchId":$patchId,"trigger":"output2","offsetMs":750}]}""", put = true)
        assertEquals(events.map { it.uuid }, again.body<List<CueEventDto>>().map { it.uuid })

        // Deleting the patch sweeps them.
        client.delete("/api/rest/projects/$projectId/patches/$patchId")
        assertEquals(0, transaction(state.database) { uk.me.cormack.lighting7.models.cueEventsOf(DaoCue[q5].id).size })
    }

    @Test
    fun `an unarmed GO skips and announces its events, an armed one fires each tube once at its offset`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val patchId = cannon(client)
        val s = stack(client, "Act 1")
        val q4 = cue(client, s, "4")
        val q5 = cue(client, s, "5")
        val q6 = cue(client, s, "6")
        json(client, "cues/$q5/events", """{"events":[{"patchId":$patchId,"trigger":"A","offsetMs":50},{"patchId":$patchId,"trigger":"B","offsetMs":250}]}""", put = true)

        runBlocking {
            // Unarmed: one announcement for the whole cue, nothing sent, nothing spent.
            val skipped = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3_000) { state.effectsService.skipped.first() } }
            state.show.cueStackManager.goToCue(state, s, q5)
            val skip = skipped.await()
            assertEquals(EffectsService.SkipReason.UNARMED, skip.reason)
            assertEquals("Q5", skip.cueLabel)
            assertEquals(2, skip.tubes.size)
            delay(400)
            assertEquals(listOf(0, 0), listOf(out(1), out(2)))
            assertTrue(state.effectsService.armed.value.spent.isEmpty())

            // Arming afterwards does not bring them back: never queued.
            state.effectsService.arm(30)
            delay(400)
            assertTrue(state.effectsService.armed.value.spent.isEmpty())

            // GO TO a later cue fires nothing (events never track); GO BACK into Q5 fires nothing.
            state.show.cueStackManager.goToCue(state, s, q6)
            state.show.cueStackManager.advanceStack(state, s, uk.me.cormack.lighting7.fx.CueStackManager.AdvanceDirection.BACKWARD)
            delay(400)
            assertTrue(state.effectsService.armed.value.spent.isEmpty())

            // Armed GO into Q5 from Q4: both tubes, in order, once each.
            state.show.cueStackManager.goToCue(state, s, q4)
            val fires = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3_000) { state.effectsService.fired.take(2).toList() } }
            state.show.cueStackManager.go(state, s)
            val fired = fires.await()
            assertEquals(listOf("output1", "output2"), fired.map { it.trigger })
            assertTrue(fired.none { it.rehearsed })
            assertTrue(fired.all { it.cueId == q5 })
            awaitOut(1, 0)
            awaitOut(2, 0)
            assertEquals(setOf("output1", "output2"), state.effectsService.armed.value.spent.map { it.trigger }.toSet())

            // The same GO again: both spent, so announced and nothing sent.
            state.show.cueStackManager.goToCue(state, s, q4)
            val spent = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3_000) { state.effectsService.skipped.take(2).toList() } }
            state.show.cueStackManager.go(state, s)
            assertEquals(listOf(EffectsService.SkipReason.SPENT, EffectsService.SkipReason.SPENT), spent.await().map { it.reason })
        }
    }

    @Test
    fun `in blind a fire is rehearsed - announced, never sent, never spent, no arm needed`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val patchId = cannon(client)
        val s = stack(client, "Act 1")
        val q1 = cue(client, s, "1")
        json(client, "cues/$q1/events", """{"events":[{"patchId":$patchId,"trigger":"A"}]}""", put = true)
        state.show.fxEngine.programmer.setBlind(true)
        state.effectsService.onBlindChanged()
        assertTrue(state.effectsService.armed.value.rehearsal)

        runBlocking {
            val fires = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3_000) { state.effectsService.fired.first() } }
            state.show.cueStackManager.goToCue(state, s, q1)
            val fired = fires.await()
            assertTrue(fired.rehearsed)
            assertEquals(0, out(1))

            // Armed in blind: the master stays down, and a panel fire rehearses too.
            state.effectsService.arm(30)
            assertEquals(0, out(3))
            val panel = json(client, "patches/$patchId/fire", """{"trigger":"A"}""")
            assertEquals(HttpStatusCode.OK, panel.status)
            assertTrue(panel.body<FireResponse>().fired.rehearsed)
            assertEquals(0, out(1))
            assertTrue(state.effectsService.armed.value.spent.isEmpty())
        }

        // A window can ask for a rehearsal outside blind (a Programmer vis source).
        state.show.fxEngine.programmer.setBlind(false)
        state.effectsService.onBlindChanged()
        assertEquals(255, out(3))
        val rehearsed = state.effectsService.fire("cannon", "output2", EffectsService.Source.PANEL, rehearse = true)
        assertTrue((rehearsed as EffectsService.FireOutcome.Done).fired.rehearsed)
        assertEquals(0, out(2))
    }

    @Test
    fun `the AI's apply_cue fires its events through the same hook, and set_cue_events authors them`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        cannon(client)
        val s = stack(client, "Act 1")
        val q5 = cue(client, s, "5")
        val ai = AiTools(state)
        val set = ai.executeTool("set_cue_events", buildJsonObject {
            put("cueId", q5)
            put("events", Json.parseToJsonElement("""[{"fixture":"cannon","trigger":"A","offsetSeconds":0.1}]"""))
        })
        assertTrue(set.success, set.description)
        state.effectsService.arm(30)
        runBlocking {
            val fires = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3_000) { state.effectsService.fired.first() } }
            val applied = ai.executeTool("apply_cue", buildJsonObject { put("cueId", q5) })
            assertTrue(applied.success, applied.description)
            assertEquals("output1", fires.await().trigger)
        }
        val bad = ai.executeTool("set_cue_events", buildJsonObject {
            put("cueId", q5)
            put("events", Json.parseToJsonElement("""[{"fixture":"nope","trigger":"A"}]"""))
        })
        assertFalse(bad.success)

        // create_look's effects are deferred, so a trigger's name is refused whatever applies it.
        val looksBefore = transaction(state.database) { DaoLook.all().count() }
        val look = ai.executeTool("create_look", buildJsonObject {
            put("name", "Bang")
            put("effects", Json.parseToJsonElement(
                """[{"effectType":"Pulse","category":"dimmer","propertyName":"master","beatDivision":1.0,"blendMode":"OVERRIDE"}]""",
            ))
        })
        assertFalse(look.success, look.description)
        assertTrue("one-shot trigger" in look.description, look.description)
        assertEquals(looksBefore, transaction(state.database) { DaoLook.all().count() })
    }

    @Test
    fun `the strip pass removes stored rows naming a trigger and keeps the rest`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        cannon(client)
        val s = stack(client, "Act 1")
        val q1 = cue(client, s, "1")
        val stripped = transaction(state.database) {
            val project = DaoProject[projectId]
            val look = DaoLook.new { this.project = project; name = "Old" }
            DaoLookRow.new { this.look = look; targetType = "fixture"; targetKey = "cannon"; propertyName = "output1"; value = "255" }
            DaoLookRow.new { this.look = look; targetType = "fixture"; targetKey = "cannon"; propertyName = "master"; value = "255" }
            DaoLookRow.new { this.look = look; targetType = "fixture"; targetKey = "dimmer-x"; propertyName = "dimmer"; value = "255" }
            DaoCuePropertyAssignment.new { cue = DaoCue[q1]; targetType = "fixture"; targetKey = "cannon"; propertyName = "output2"; value = "255" }
            stripTriggerRows(project)
        }
        assertEquals(3, stripped)
        assertEquals(1L, transaction(state.database) { DaoLookRow.count() })
    }

    private fun awaitSpentRows(n: Int) = runBlocking {
        withTimeoutOrNull(3_000) {
            while (transaction(state.database) { DaoEffectTubeStates.selectAll().count() } != n.toLong()) delay(10)
        } ?: error("effect_tube_state never held $n row(s)")
    }
}
