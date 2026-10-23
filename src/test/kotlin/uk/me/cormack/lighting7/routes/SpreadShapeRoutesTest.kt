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
import uk.me.cormack.lighting7.fx.EffectDto
import uk.me.cormack.lighting7.fx.EffectSpawner
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxTargetFactory
import uk.me.cormack.lighting7.fx.lookEffectSettings
import uk.me.cormack.lighting7.fx.toLookEffectSpec
import uk.me.cormack.lighting7.models.CueAdHocEffectDto
import uk.me.cormack.lighting7.models.LookEffectDto
import uk.me.cormack.lighting7.models.LookEffectSpec
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Effects plan session 2 on the wire: the spread's shape (D4) — Spread, Wings, Blocks, Groups — through
 * the add and update routes (the `updateFx` frame shares the update's parse), the group add route, a
 * Look's stored effects, the spawner, Record and Include. The shape's arithmetic is `SpreadShapeTest`'s.
 */
class SpreadShapeRoutesTest : RouteIntegrationTest() {

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

    private fun seedQuad() {
        for (i in 0 until 4) LocateTestSupport.seedHex(state, projectId, "hex-$i", 1 + 12 * i)
        LocateTestSupport.seedGroup(state, projectId, "quad", "hex-0", "hex-1", "hex-2", "hex-3")
    }

    @Test
    fun `a group effect takes its spread shape on add, and reports it`() = testApplication {
        mountTestApp(state)
        seedQuad()
        val response = jsonClient().post("/api/rest/groups/quad/fx") {
            contentType(ContentType.Application.Json)
            setBody(AddGroupFxRequest(effectType = "Pulse", propertyName = "dimmer", spread = 0.5, wings = 2, blocks = 2, groups = 3))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val id = response.body<AddGroupFxResponse>().effectId
        val dyn = effect(id).dynamics
        assertEquals(0.5, dyn.spread)
        assertEquals(2, dyn.wings)
        assertEquals(2, dyn.blocks)
        assertEquals(3, dyn.groups)
        val dto = engine.effectDto(effect(id))
        assertEquals(0.5, dto.spread)
        assertEquals(2, dto.wings)
        assertEquals(2, dto.blocks)
        assertEquals(3, dto.groups)
    }

    @Test
    fun `wings under Centre out, or a field out of range, is refused on add`() = testApplication {
        mountTestApp(state)
        seedQuad()
        val client = jsonClient()
        for (request in listOf(
            AddGroupFxRequest(effectType = "Pulse", propertyName = "dimmer", distribution = "CENTRE_OUT", wings = 2),
            AddGroupFxRequest(effectType = "Pulse", propertyName = "dimmer", distribution = "CENTRE_IN", wings = 2),
            AddGroupFxRequest(effectType = "Pulse", propertyName = "dimmer", spread = 2.5),
            AddGroupFxRequest(effectType = "Pulse", propertyName = "dimmer", blocks = 0),
            AddGroupFxRequest(effectType = "Pulse", propertyName = "dimmer", groups = -1),
        )) {
            val response = client.post("/api/rest/groups/quad/fx") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, request.toString())
        }
        LocateTestSupport.seedHex(state, projectId, "hex-9", 101)
        val fixture = client.add(
            AddEffectRequest(effectType = "Pulse", fixtureKey = "hex-9", propertyName = "dimmer", distributionStrategy = "CENTRE_IN", wings = 3),
        )
        assertEquals(HttpStatusCode.BadRequest, fixture.status)
        assertTrue(engine.getActiveEffects().isEmpty(), "nothing was added")
    }

    @Test
    fun `an update edits the shape in place, phase kept, and refuses wings under a mirrored order`() = testApplication {
        mountTestApp(state)
        seedQuad()
        val client = jsonClient()
        val id = client.post("/api/rest/groups/quad/fx") {
            contentType(ContentType.Application.Json)
            setBody(AddGroupFxRequest(effectType = "Pulse", propertyName = "dimmer"))
        }.body<AddGroupFxResponse>().effectId
        val before = effect(id)

        val updated = client.update(id, UpdateEffectRequest(spread = 0.25, wings = 2, groups = 2)).body<EffectDto>()
        assertEquals(0.25, updated.spread)
        assertEquals(2, updated.wings)
        assertEquals(2, updated.groups)
        assertEquals(1, updated.blocks, "a field not sent keeps its value")
        assertSame(before, effect(id), "a dynamics edit, not a swap — the phase is kept")

        val off = client.update(id, UpdateEffectRequest(groups = 0)).body<EffectDto>()
        assertEquals(0, off.groups, "groups 0 turns them off")

        for (refused in listOf(
            // Moving a winged effect onto Centre out is refused as surely as sending wings to one.
            UpdateEffectRequest(distributionStrategy = "CENTRE_OUT"),
            UpdateEffectRequest(distributionStrategy = "CENTRE_IN", wings = 3),
            UpdateEffectRequest(spread = -1.0),
            UpdateEffectRequest(wings = 0),
            UpdateEffectRequest(blocks = 600),
        )) {
            val response = client.update(id, refused)
            assertEquals(HttpStatusCode.BadRequest, response.status, refused.toString())
            assertEquals(CODE_FX_UPDATE_REFUSED, response.body<ErrorResponse>().code)
        }
        assertEquals(2, effect(id).dynamics.wings, "a refused update moves nothing")

        // Taking the wings off first lets the order move.
        val moved = client.update(id, UpdateEffectRequest(distributionStrategy = "CENTRE_OUT", wings = 1)).body<EffectDto>()
        assertEquals("CENTRE_OUT", moved.distributionStrategy)
        assertEquals(1, moved.wings)
    }

    @Test
    fun `a Look stores its effect's shape, and refuses wings under Centre out`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val base = "/api/rest/projects/$projectId/looks"
        fun look(name: String, effect: LookEffectDto) = CreateLookRequest(name = name, effects = listOf(effect))
        val pulse = LookEffectDto(
            targetType = "fixture", targetKey = "hex-1", effectType = "Pulse", category = "intensity",
            propertyName = "dimmer", beatDivision = 0.5, blendMode = "OVERRIDE", distribution = "NORMAL",
        )

        val created = client.post(base) {
            contentType(ContentType.Application.Json)
            setBody(look("Ripple", pulse.copy(spread = 0.5, wings = 2, blocks = 3, groups = 4)))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val stored = created.body<LookDetails>().effects.single()
        assertEquals(0.5, stored.spread)
        assertEquals(2, stored.wings)
        assertEquals(3, stored.blocks)
        assertEquals(4, stored.groups)

        for ((name, bad) in listOf(
            "Winged Bloom" to pulse.copy(distribution = "CENTRE_OUT", wings = 2),
            "Too Wide" to pulse.copy(spread = 2.1),
            "No Blocks" to pulse.copy(blocks = 0),
        )) {
            val response = client.post(base) {
                contentType(ContentType.Application.Json)
                setBody(look(name, bad))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, name)
        }
    }

    @Test
    fun `a stored effect spawns with its row's shape, and Record reads it back`() = testApplication {
        mountTestApp(state)
        seedQuad()
        val spec = LookEffectSpec(
            effectType = "Pulse", category = "intensity", propertyName = "dimmer", beatDivision = 1.0,
            blendMode = "OVERRIDE", distribution = "NORMAL", spread = 0.75, wings = 2, blocks = 1, groups = 2,
        )
        val target = FxTargetFactory.forGroup("quad", "dimmer", null, null)
        val instance = EffectSpawner.createEffectInstance(spec, target, state)
        assertEquals(0.75, instance.dynamics.spread)
        assertEquals(2, instance.dynamics.wings)
        assertEquals(2, instance.dynamics.groups)

        val settings = instance.lookEffectSettings()
        assertEquals(0.75, settings.spread)
        assertEquals(2, settings.wings)
        assertEquals(1, settings.blocks)
        assertEquals(2, settings.groups)

        // A stored value outside the range is survived, not refused.
        val lenient = EffectSpawner.createEffectInstance(spec.copy(spread = 9.0, blocks = 0, groups = -3), target, state)
        assertEquals(2.0, lenient.dynamics.spread)
        assertEquals(1, lenient.dynamics.blocks)
        assertEquals(0, lenient.dynamics.groups)
    }

    @Test
    fun `Include carries a cue effect's shape`() {
        val child = CueAdHocEffectDto(
            targetType = "group", targetKey = "quad", effectType = "Pulse", category = "intensity",
            beatDivision = 2.0, blendMode = "OVERRIDE", distribution = "NORMAL",
            spread = 0.5, wings = 3, blocks = 2, groups = 4,
        )
        val spec = child.toEffectSpec()
        assertEquals(0.5, spec.spread)
        assertEquals(3, spec.wings)
        assertEquals(2, spec.blocks)
        assertEquals(4, spec.groups)
        // The cue path's one helper (R1.2) carries it too.
        val held = child.toLookEffectSpec()
        assertEquals(spec.spread, held.spread)
        assertEquals(spec.wings, held.wings)
        assertEquals(spec.blocks, held.blocks)
        assertEquals(spec.groups, held.groups)
    }

    @Test
    fun `a recorded busk keeps its shape`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedQuad()
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-a")
        val added = client.post("/api/rest/groups/quad/fx") {
            contentType(ContentType.Application.Json)
            setBody(
                AddGroupFxRequest(
                    effectType = "Pulse", propertyName = "dimmer",
                    spread = 0.6, wings = 2, blocks = 2, groups = 2, programmerOwned = true,
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, added.status, added.bodyAsText())

        val response: ProgrammerRecordResponse = client.post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId, name = "ripple"))
        }.body()

        val recorded = response.cue.adHocEffects.single()
        assertEquals(0.6, recorded.spread)
        assertEquals(2, recorded.wings)
        assertEquals(2, recorded.blocks)
        assertEquals(2, recorded.groups)
    }
}
