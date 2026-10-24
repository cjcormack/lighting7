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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fx.FxDto
import uk.me.cormack.lighting7.fx.FxSpawner
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxTargetFactory
import uk.me.cormack.lighting7.fx.lookFxSettings
import uk.me.cormack.lighting7.fx.toLookFxSpec
import uk.me.cormack.lighting7.models.CueAdHocFxDto
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.LookFxDto
import uk.me.cormack.lighting7.models.LookFxSpec
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
            setBody(AddGroupFxRequest(fxType = "Pulse", propertyName = "dimmer", spread = 0.5, wings = 2, segments = 2, parts = 3))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val id = response.body<AddGroupFxResponse>().fxId
        val dyn = effect(id).dynamics
        assertEquals(0.5, dyn.spread)
        assertEquals(2, dyn.wings)
        assertEquals(2, dyn.segments)
        assertEquals(3, dyn.parts)
        val dto = engine.fxDto(effect(id))
        assertEquals(0.5, dto.spread)
        assertEquals(2, dto.wings)
        assertEquals(2, dto.segments)
        assertEquals(3, dto.parts)
    }

    @Test
    fun `wings under Centre out, or a field out of range, is refused on add`() = testApplication {
        mountTestApp(state)
        seedQuad()
        val client = jsonClient()
        for (request in listOf(
            AddGroupFxRequest(fxType = "Pulse", propertyName = "dimmer", order = "CENTRE_OUT", wings = 2),
            AddGroupFxRequest(fxType = "Pulse", propertyName = "dimmer", order = "CENTRE_IN", wings = 2),
            AddGroupFxRequest(fxType = "Pulse", propertyName = "dimmer", spread = 2.5),
            AddGroupFxRequest(fxType = "Pulse", propertyName = "dimmer", segments = 0),
            AddGroupFxRequest(fxType = "Pulse", propertyName = "dimmer", parts = -1),
        )) {
            val response = client.post("/api/rest/groups/quad/fx") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, request.toString())
        }
        LocateTestSupport.seedHex(state, projectId, "hex-9", 101)
        val fixture = client.add(
            AddFxRequest(fxType = "Pulse", fixtureKey = "hex-9", propertyName = "dimmer", order = "CENTRE_IN", wings = 3),
        )
        assertEquals(HttpStatusCode.BadRequest, fixture.status)
        assertTrue(engine.getActiveFx().isEmpty(), "nothing was added")
    }

    @Test
    fun `an update edits the shape in place, phase kept, and refuses wings under a mirrored order`() = testApplication {
        mountTestApp(state)
        seedQuad()
        val client = jsonClient()
        val id = client.post("/api/rest/groups/quad/fx") {
            contentType(ContentType.Application.Json)
            setBody(AddGroupFxRequest(fxType = "Pulse", propertyName = "dimmer"))
        }.body<AddGroupFxResponse>().fxId
        val before = effect(id)

        val updated = client.update(id, UpdateFxRequest(spread = 0.25, wings = 2, parts = 2)).body<FxDto>()
        assertEquals(0.25, updated.spread)
        assertEquals(2, updated.wings)
        assertEquals(2, updated.parts)
        assertEquals(1, updated.segments, "a field not sent keeps its value")
        assertSame(before, effect(id), "a dynamics edit, not a swap — the phase is kept")

        val off = client.update(id, UpdateFxRequest(parts = 0)).body<FxDto>()
        assertEquals(0, off.parts, "groups 0 turns them off")

        for (refused in listOf(
            // Moving a winged effect onto Centre out is refused as surely as sending wings to one.
            UpdateFxRequest(order = "CENTRE_OUT"),
            UpdateFxRequest(order = "CENTRE_IN", wings = 3),
            UpdateFxRequest(spread = -1.0),
            UpdateFxRequest(wings = 0),
            UpdateFxRequest(segments = 600),
        )) {
            val response = client.update(id, refused)
            assertEquals(HttpStatusCode.BadRequest, response.status, refused.toString())
            assertEquals(CODE_FX_UPDATE_REFUSED, response.body<ErrorResponse>().code)
        }
        assertEquals(2, effect(id).dynamics.wings, "a refused update moves nothing")

        // Taking the wings off first lets the order move.
        val moved = client.update(id, UpdateFxRequest(order = "CENTRE_OUT", wings = 1)).body<FxDto>()
        assertEquals("CENTRE_OUT", moved.order)
        assertEquals(1, moved.wings)
    }

    @Test
    fun `a Look stores its effect's shape, and refuses wings under Centre out`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val base = "/api/rest/projects/$projectId/looks"
        fun look(name: String, effect: LookFxDto) = CreateLookRequest(name = name, fx = listOf(effect))
        val pulse = LookFxDto(
            targetType = "fixture", targetKey = "hex-1", fxType = "Pulse", family = "intensity",
            propertyName = "dimmer", cycle = 0.5, blendMode = "OVERRIDE", order = "NORMAL",
        )

        val created = client.post(base) {
            contentType(ContentType.Application.Json)
            setBody(look("Ripple", pulse.copy(spread = 0.5, wings = 2, segments = 3, parts = 4)))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val stored = created.body<LookDetails>().fx.single()
        assertEquals(0.5, stored.spread)
        assertEquals(2, stored.wings)
        assertEquals(3, stored.segments)
        assertEquals(4, stored.parts)

        for ((name, bad) in listOf(
            "Winged Bloom" to pulse.copy(order = "CENTRE_OUT", wings = 2),
            "Too Wide" to pulse.copy(spread = 2.1),
            "No Blocks" to pulse.copy(segments = 0),
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
        val spec = LookFxSpec(
            fxType = "Pulse", family = "intensity", propertyName = "dimmer", cycle = 1.0,
            blendMode = "OVERRIDE", order = "NORMAL", spread = 0.75, wings = 2, segments = 1, parts = 2,
        )
        val target = FxTargetFactory.forGroup("quad", "dimmer", null, null)
        val instance = FxSpawner.createFxInstance(spec, target, state)
        assertEquals(0.75, instance.dynamics.spread)
        assertEquals(2, instance.dynamics.wings)
        assertEquals(2, instance.dynamics.parts)

        val settings = instance.lookFxSettings()
        assertEquals(0.75, settings.spread)
        assertEquals(2, settings.wings)
        assertEquals(1, settings.segments)
        assertEquals(2, settings.parts)

        // A stored value outside the range is survived, not refused.
        val lenient = FxSpawner.createFxInstance(spec.copy(spread = 9.0, segments = 0, parts = -3), target, state)
        assertEquals(2.0, lenient.dynamics.spread)
        assertEquals(1, lenient.dynamics.segments)
        assertEquals(0, lenient.dynamics.parts)
    }

    @Test
    fun `Include carries a cue effect's shape`() {
        val child = CueAdHocFxDto(
            targetType = "group", targetKey = "quad", fxType = "Pulse", family = "intensity",
            cycle = 2.0, blendMode = "OVERRIDE", order = "NORMAL",
            spread = 0.5, wings = 3, segments = 2, parts = 4,
        )
        val spec = child.toFxSpec()
        assertEquals(0.5, spec.spread)
        assertEquals(3, spec.wings)
        assertEquals(2, spec.segments)
        assertEquals(4, spec.parts)
        // The cue path's one helper (R1.2) carries it too.
        val held = child.toLookFxSpec()
        assertEquals(spec.spread, held.spread)
        assertEquals(spec.wings, held.wings)
        assertEquals(spec.segments, held.segments)
        assertEquals(spec.parts, held.parts)
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
                    fxType = "Pulse", propertyName = "dimmer",
                    spread = 0.6, wings = 2, segments = 2, parts = 2, programmerOwned = true,
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, added.status, added.bodyAsText())

        val response: ProgrammerRecordResponse = client.post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId, name = "ripple"))
        }.body()

        val recorded = response.cue.adHocFx.single()
        assertEquals(0.6, recorded.spread)
        assertEquals(2, recorded.wings)
        assertEquals(2, recorded.segments)
        assertEquals(2, recorded.parts)
    }

    /**
     * A cue holding a group Pulse is included, its shape alone edited through the update route
     * ([edit], none when null), and Update pressed: answers `fxWritten` and the stored child's shape.
     */
    private fun updateAfterShapeEdit(edit: UpdateFxRequest?, check: (fxWritten: Int, stored: List<Number>) -> Unit) =
        testApplication {
            mountTestApp(state)
            seedQuad()
            val client = jsonClient()
            val pulse = CueAdHocFxDto(
                // Every field as Record reads it back, so an Update that edited nothing writes nothing.
                targetType = "group", targetKey = "quad", fxType = "Pulse",
                family = categoryFromPropertyName("dimmer"), propertyName = "dimmer",
                cycle = 1.0, blendMode = "OVERRIDE", order = "NORMAL", elementMode = "PER_FIXTURE",
            )
            val created = client.post("/api/rest/projects/$projectId/cues") {
                contentType(ContentType.Application.Json)
                setBody(NewCue(name = "Ripple", adHocFx = listOf(pulse)))
            }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val cueId = created.body<CueDetails>().id

            val included = client.post("/api/rest/programmer/include") {
                contentType(ContentType.Application.Json)
                setBody(ProgrammerIncludeRequest(projectId = projectId.toString(), cueId = cueId))
            }
            assertEquals(HttpStatusCode.OK, included.status, included.bodyAsText())
            if (edit != null) {
                val edited = client.update(engine.getActiveFx().single().id, edit)
                assertEquals(HttpStatusCode.OK, edited.status, edited.bodyAsText())
            }

            val response = client.post("/api/rest/programmer/update") {
                contentType(ContentType.Application.Json)
                setBody(ProgrammerUpdateRequest(projectId = projectId.toString()))
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val stored = transaction(state.database) {
                DaoCue.findById(cueId)!!.adHocFx.single().let { listOf(it.spread, it.wings, it.segments, it.parts) }
            }
            check(response.body<ProgrammerUpdateResponse>().results.single().fxWritten, stored)
        }

    @Test
    fun `Update of an included cue whose effect kept its shape writes nothing`() =
        updateAfterShapeEdit(edit = null) { fxWritten, stored ->
            assertEquals(0, fxWritten)
            assertEquals(listOf(1.0, 1, 1, 0), stored)
        }

    @Test
    fun `Update writes a cue effect's edited Spread`() =
        updateAfterShapeEdit(UpdateFxRequest(spread = 0.5)) { fxWritten, stored ->
            assertEquals(1, fxWritten)
            assertEquals(listOf(0.5, 1, 1, 0), stored)
        }

    @Test
    fun `Update writes a cue effect's edited Wings`() =
        updateAfterShapeEdit(UpdateFxRequest(wings = 2)) { fxWritten, stored ->
            assertEquals(1, fxWritten)
            assertEquals(listOf(1.0, 2, 1, 0), stored)
        }

    @Test
    fun `Update writes a cue effect's edited Segments`() =
        updateAfterShapeEdit(UpdateFxRequest(segments = 2)) { fxWritten, stored ->
            assertEquals(1, fxWritten)
            assertEquals(listOf(1.0, 1, 2, 0), stored)
        }

    @Test
    fun `Update writes a cue effect's edited Parts`() =
        updateAfterShapeEdit(UpdateFxRequest(parts = 2)) { fxWritten, stored ->
            assertEquals(1, fxWritten)
            assertEquals(listOf(1.0, 1, 1, 2), stored)
        }
}
