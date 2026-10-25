package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fx.FxDirection
import uk.me.cormack.lighting7.fx.FxDto
import uk.me.cormack.lighting7.fx.FxSpawner
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxTargetFactory
import uk.me.cormack.lighting7.fx.lookFxSettings
import uk.me.cormack.lighting7.models.CueAdHocFxDto
import uk.me.cormack.lighting7.models.LookFxDto
import uk.me.cormack.lighting7.models.LookFxSpec
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Effects plan session 1 on the wire: Size (D2), Direction (D5) and Runs (D9) through the add and
 * update routes (the `updateFx` frame shares the update's parse), the stored effect homes, Record
 * and Include. The engine's arithmetic is `FxAmountDirectionRunsTest`'s.
 */
class FxAmountDirectionRoutesTest : RouteIntegrationTest() {

    private val engine get() = state.show.fxEngine

    private suspend fun HttpClient.add(request: AddFxRequest) =
        post("/api/rest/fx/add") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.update(id: Long, request: UpdateFxRequest) =
        put("/api/rest/fx/$id") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private fun effect(id: Long): FxInstance = assertNotNull(engine.getFx(id))

    @Test
    fun `an added effect takes its size, direction and runs, and reports them`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)

        val id = jsonClient().add(
            AddFxRequest(
                fxType = "Pulse", fixtureKey = "hex-1", attribute = "dimmer",
                amount = 0.4, direction = "bounce", runs = 3,
            ),
        ).body<AddFxResponse>().fxId

        val dyn = effect(id).dynamics
        assertEquals(0.4, dyn.amount)
        assertEquals(FxDirection.BOUNCE, dyn.direction)
        assertEquals(3, dyn.runs)
        val dto = engine.fxDto(effect(id))
        assertEquals(0.4, dto.amount)
        assertEquals("BOUNCE", dto.direction)
        assertEquals(3, dto.runs)
        assertEquals(1.0, dto.intensityMultiplier, "a fresh effect's envelope is full")
    }

    @Test
    fun `a size outside 0 to 1, an unknown direction or a count below one is refused on add`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        for (request in listOf(
            AddFxRequest(fxType = "Pulse", fixtureKey = "hex-1", attribute = "dimmer", amount = 1.5),
            AddFxRequest(fxType = "Pulse", fixtureKey = "hex-1", attribute = "dimmer", amount = -0.1),
            AddFxRequest(fxType = "Pulse", fixtureKey = "hex-1", attribute = "dimmer", direction = "SIDEWAYS"),
            AddFxRequest(fxType = "Pulse", fixtureKey = "hex-1", attribute = "dimmer", runs = 0),
        )) {
            assertEquals(HttpStatusCode.BadRequest, client.add(request).status, request.toString())
        }
        assertTrue(engine.getActiveFx().isEmpty(), "nothing was added")
    }

    @Test
    fun `an update edits size, direction and runs in place, and runForever clears the count`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val id = client.add(AddFxRequest(fxType = "Pulse", fixtureKey = "hex-1", attribute = "dimmer"))
            .body<AddFxResponse>().fxId
        val before = effect(id)

        val updated = client.update(id, UpdateFxRequest(amount = 0.25, direction = "BACKWARD", runs = 2)).body<FxDto>()
        assertEquals(0.25, updated.amount)
        assertEquals("BACKWARD", updated.direction)
        assertEquals(2, updated.runs)
        assertSame(before, effect(id), "a dynamics edit, not a swap — the phase is kept")

        val forever = client.update(id, UpdateFxRequest(runForever = true)).body<FxDto>()
        assertNull(forever.runs)
        assertEquals(0.25, forever.amount, "an absent field keeps its value")

        for (refused in listOf(
            UpdateFxRequest(amount = 2.0),
            UpdateFxRequest(direction = "UP"),
            UpdateFxRequest(runs = -1),
            UpdateFxRequest(runs = 2, runForever = true),
        )) {
            val response = client.update(id, refused)
            assertEquals(HttpStatusCode.BadRequest, response.status, refused.toString())
            assertEquals(CODE_FX_UPDATE_REFUSED, response.body<ErrorResponse>().code)
        }
        assertEquals(0.25, effect(id).dynamics.amount, "a refused update moves nothing")
    }

    @Test
    fun `a Look stores its effect's size, direction and runs, and refuses a bad one`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val base = "/api/rest/projects/$projectId/looks"
        fun look(name: String, effect: LookFxDto) = CreateLookRequest(name = name, fx = listOf(effect))
        val pulse = LookFxDto(
            targetType = "fixture", targetKey = "hex-1", fxType = "Pulse", family = "intensity",
            attribute = "dimmer", cycle = 0.5, blendMode = "OVERRIDE", order = "NORMAL",
        )

        val created = client.post(base) {
            contentType(ContentType.Application.Json)
            setBody(look("Lightning", pulse.copy(amount = 0.6, direction = "backward", runs = 1)))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val stored = created.body<LookDetails>().fx.single()
        assertEquals(0.6, stored.amount)
        assertEquals("BACKWARD", stored.direction, "stored by its name, whatever the casing it arrived in")
        assertEquals(1, stored.runs)

        for ((name, bad) in listOf(
            "Too Big" to pulse.copy(amount = 1.2),
            "Sideways" to pulse.copy(direction = "SIDEWAYS"),
            "Never" to pulse.copy(runs = 0),
        )) {
            val response = client.post(base) {
                contentType(ContentType.Application.Json)
                setBody(look(name, bad))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, name)
        }
    }

    @Test
    fun `a stored effect spawns with its row's size, direction and runs`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val spec = LookFxSpec(
            fxType = "Pulse", family = "intensity", attribute = "dimmer", cycle = 1.0,
            blendMode = "OVERRIDE", order = "NORMAL", amount = 0.3, direction = "BOUNCE", runs = 4,
        )
        val target = FxTargetFactory.forFixture("hex-1", "dimmer", null, state.show.fixtures.untypedGroupableFixture("hex-1"))
        val instance = FxSpawner.createFxInstance(spec, target, state)
        assertEquals(0.3, instance.dynamics.amount)
        assertEquals(FxDirection.BOUNCE, instance.dynamics.direction)
        assertEquals(4, instance.dynamics.runs)

        // Record into a Look reads them back off the instance (`lookFxSettings`), so the two meet.
        val settings = instance.lookFxSettings()
        assertEquals(0.3, settings.amount)
        assertEquals("BOUNCE", settings.direction)
        assertEquals(4, settings.runs)

        // A stored value outside the range — a hand edit, a newer desk's — is survived, not refused.
        val lenient = FxSpawner.createFxInstance(spec.copy(amount = 3.0, direction = "??", runs = 0), target, state)
        assertEquals(1.0, lenient.dynamics.amount)
        assertEquals(FxDirection.FORWARD, lenient.dynamics.direction)
        assertNull(lenient.dynamics.runs)
    }

    @Test
    fun `Include carries a cue effect's size, direction and runs`() {
        val child = CueAdHocFxDto(
            targetType = "fixture", targetKey = "hex-1", fxType = "Pulse", family = "intensity",
            cycle = 2.0, blendMode = "OVERRIDE", order = "NORMAL",
            amount = 0.5, direction = "BACKWARD", runs = 2,
        )
        val spec = child.toFxSpec()
        assertEquals(0.5, spec.amount)
        assertEquals("BACKWARD", spec.direction)
        assertEquals(2, spec.runs)
    }

    @Test
    fun `a recorded busk keeps its size, direction and runs`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-a")
        val added = client.add(
            AddFxRequest(
                fxType = "Pulse", fixtureKey = "hex-1", attribute = "dimmer",
                amount = 0.7, direction = "BACKWARD", runs = 5, programmerOwned = true,
            ),
        )
        assertEquals(HttpStatusCode.OK, added.status)

        val response: ProgrammerRecordResponse = client.post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId, name = "shot"))
        }.body()

        val recorded = response.cue.adHocFx.single()
        assertEquals(0.7, recorded.amount)
        assertEquals("BACKWARD", recorded.direction)
        assertEquals(5, recorded.runs)
    }
}
