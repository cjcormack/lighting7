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
import uk.me.cormack.lighting7.fx.EffectDirection
import uk.me.cormack.lighting7.fx.EffectDto
import uk.me.cormack.lighting7.fx.EffectSpawner
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxTargetFactory
import uk.me.cormack.lighting7.fx.lookEffectSettings
import uk.me.cormack.lighting7.models.CueAdHocEffectDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.LookEffectDto
import uk.me.cormack.lighting7.models.LookEffectSpec
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
 * and Include. The engine's arithmetic is `EffectSizeDirectionNShotTest`'s.
 */
class EffectSizeDirectionRoutesTest : RouteIntegrationTest() {

    private val engine get() = state.show.fxEngine

    private suspend fun HttpClient.add(request: AddEffectRequest) =
        post("/api/rest/fx/add") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.update(id: Long, request: UpdateEffectRequest) =
        put("/api/rest/fx/$id") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private fun effect(id: Long): FxInstance = assertNotNull(engine.getEffect(id))

    @Test
    fun `an added effect takes its size, direction and runs, and reports them`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)

        val id = jsonClient().add(
            AddEffectRequest(
                effectType = "Pulse", fixtureKey = "hex-1", propertyName = "dimmer",
                size = 0.4, direction = "bounce", nShot = 3,
            ),
        ).body<AddEffectResponse>().effectId

        val dyn = effect(id).dynamics
        assertEquals(0.4, dyn.size)
        assertEquals(EffectDirection.BOUNCE, dyn.direction)
        assertEquals(3, dyn.nShot)
        val dto = engine.effectDto(effect(id))
        assertEquals(0.4, dto.size)
        assertEquals("BOUNCE", dto.direction)
        assertEquals(3, dto.nShot)
        assertEquals(1.0, dto.intensityMultiplier, "the envelope stays 1 until session 4's fades")
    }

    @Test
    fun `a size outside 0 to 1, an unknown direction or a count below one is refused on add`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        for (request in listOf(
            AddEffectRequest(effectType = "Pulse", fixtureKey = "hex-1", propertyName = "dimmer", size = 1.5),
            AddEffectRequest(effectType = "Pulse", fixtureKey = "hex-1", propertyName = "dimmer", size = -0.1),
            AddEffectRequest(effectType = "Pulse", fixtureKey = "hex-1", propertyName = "dimmer", direction = "SIDEWAYS"),
            AddEffectRequest(effectType = "Pulse", fixtureKey = "hex-1", propertyName = "dimmer", nShot = 0),
        )) {
            assertEquals(HttpStatusCode.BadRequest, client.add(request).status, request.toString())
        }
        assertTrue(engine.getActiveEffects().isEmpty(), "nothing was added")
    }

    @Test
    fun `an update edits size, direction and runs in place, and runForever clears the count`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val id = client.add(AddEffectRequest(effectType = "Pulse", fixtureKey = "hex-1", propertyName = "dimmer"))
            .body<AddEffectResponse>().effectId
        val before = effect(id)

        val updated = client.update(id, UpdateEffectRequest(size = 0.25, direction = "BACKWARD", nShot = 2)).body<EffectDto>()
        assertEquals(0.25, updated.size)
        assertEquals("BACKWARD", updated.direction)
        assertEquals(2, updated.nShot)
        assertSame(before, effect(id), "a dynamics edit, not a swap — the phase is kept")

        val forever = client.update(id, UpdateEffectRequest(runForever = true)).body<EffectDto>()
        assertNull(forever.nShot)
        assertEquals(0.25, forever.size, "an absent field keeps its value")

        for (refused in listOf(
            UpdateEffectRequest(size = 2.0),
            UpdateEffectRequest(direction = "UP"),
            UpdateEffectRequest(nShot = -1),
            UpdateEffectRequest(nShot = 2, runForever = true),
        )) {
            val response = client.update(id, refused)
            assertEquals(HttpStatusCode.BadRequest, response.status, refused.toString())
            assertEquals(CODE_FX_UPDATE_REFUSED, response.body<ErrorResponse>().code)
        }
        assertEquals(0.25, effect(id).dynamics.size, "a refused update moves nothing")
    }

    @Test
    fun `a Look stores its effect's size, direction and runs, and refuses a bad one`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val base = "/api/rest/projects/$projectId/looks"
        fun look(name: String, effect: LookEffectDto) = CreateLookRequest(name = name, effects = listOf(effect))
        val pulse = LookEffectDto(
            targetType = DEFERRED_TARGET_TYPE, targetKey = "", effectType = "Pulse", category = "dimmer",
            propertyName = "dimmer", beatDivision = 0.5, blendMode = "OVERRIDE", distribution = "LINEAR",
        )

        val created = client.post(base) {
            contentType(ContentType.Application.Json)
            setBody(look("Lightning", pulse.copy(size = 0.6, direction = "backward", nShot = 1)))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val stored = created.body<LookDetails>().effects.single()
        assertEquals(0.6, stored.size)
        assertEquals("BACKWARD", stored.direction, "stored by its name, whatever the casing it arrived in")
        assertEquals(1, stored.nShot)

        for ((name, bad) in listOf(
            "Too Big" to pulse.copy(size = 1.2),
            "Sideways" to pulse.copy(direction = "SIDEWAYS"),
            "Never" to pulse.copy(nShot = 0),
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
        val spec = LookEffectSpec(
            effectType = "Pulse", category = "dimmer", propertyName = "dimmer", beatDivision = 1.0,
            blendMode = "OVERRIDE", distribution = "LINEAR", size = 0.3, direction = "BOUNCE", nShot = 4,
        )
        val target = FxTargetFactory.forFixture("hex-1", "dimmer", null, state.show.fixtures.untypedGroupableFixture("hex-1"))
        val instance = EffectSpawner.createEffectInstance(spec, target, state)
        assertEquals(0.3, instance.dynamics.size)
        assertEquals(EffectDirection.BOUNCE, instance.dynamics.direction)
        assertEquals(4, instance.dynamics.nShot)

        // Record into a Look reads them back off the instance (`lookEffectSettings`), so the two meet.
        val settings = instance.lookEffectSettings()
        assertEquals(0.3, settings.size)
        assertEquals("BOUNCE", settings.direction)
        assertEquals(4, settings.nShot)

        // A stored value outside the range — a hand edit, a newer desk's — is survived, not refused.
        val lenient = EffectSpawner.createEffectInstance(spec.copy(size = 3.0, direction = "??", nShot = 0), target, state)
        assertEquals(1.0, lenient.dynamics.size)
        assertEquals(EffectDirection.FORWARD, lenient.dynamics.direction)
        assertNull(lenient.dynamics.nShot)
    }

    @Test
    fun `Include carries a cue effect's size, direction and runs`() {
        val child = CueAdHocEffectDto(
            targetType = "fixture", targetKey = "hex-1", effectType = "Pulse", category = "dimmer",
            beatDivision = 2.0, blendMode = "OVERRIDE", distribution = "LINEAR",
            size = 0.5, direction = "BACKWARD", nShot = 2,
        )
        val spec = child.toEffectSpec()
        assertEquals(0.5, spec.size)
        assertEquals("BACKWARD", spec.direction)
        assertEquals(2, spec.nShot)
    }

    @Test
    fun `a recorded busk keeps its size, direction and runs`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-a")
        val added = client.add(
            AddEffectRequest(
                effectType = "Pulse", fixtureKey = "hex-1", propertyName = "dimmer",
                size = 0.7, direction = "BACKWARD", nShot = 5, programmerOwned = true,
            ),
        )
        assertEquals(HttpStatusCode.OK, added.status)

        val response: ProgrammerRecordResponse = client.post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId, name = "shot"))
        }.body()

        val recorded = response.cue.adHocEffects.single()
        assertEquals(0.7, recorded.size)
        assertEquals("BACKWARD", recorded.direction)
        assertEquals(5, recorded.nShot)
    }
}
