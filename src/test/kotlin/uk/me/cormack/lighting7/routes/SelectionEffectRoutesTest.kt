package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fx.FxTargetRef
import uk.me.cormack.lighting7.models.CueAdHocEffectDto
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.EffectTargetRef
import uk.me.cormack.lighting7.models.LookEffectDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Effects plan session 3 on the wire: one effect over a selection (D3) through `POST /fx/add`'s
 * `targets`, the refusal of a selection anywhere but an effect, Record into a cue and a Look, and
 * Include bringing a recorded selection effect back as one instance. The expansion itself is
 * `SelectionEffectTest`'s.
 */
class SelectionEffectRoutesTest : RouteIntegrationTest() {

    private val engine get() = state.show.fxEngine

    private fun seedRig() {
        for (i in 0 until 6) LocateTestSupport.seedHex(state, projectId, "hex-$i", 1 + 12 * i)
        LocateTestSupport.seedGroup(state, projectId, "quad", "hex-0", "hex-1", "hex-2", "hex-3")
    }

    private val selectionTargets = listOf(
        CueTargetDto(TargetRef.Fixture.TYPE, "hex-4"),
        CueTargetDto(TargetRef.Fixture.TYPE, "hex-5"),
        CueTargetDto(TargetRef.Group.TYPE, "quad"),
    )

    private val selectionKey =
        """[{"key":"hex-4","type":"fixture"},{"key":"hex-5","type":"fixture"},{"key":"quad","type":"group"}]"""

    private suspend fun HttpClient.add(request: AddEffectRequest) =
        post("/api/rest/fx/add") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.addOverSelection(): Long {
        val response = add(
            AddEffectRequest(
                effectType = "Pulse", propertyName = "dimmer", targets = selectionTargets, programmerOwned = true,
            ),
        )
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body<AddEffectResponse>().effectId
    }

    @Test
    fun `an effect added over targets is one instance spreading over them in order`() = testApplication {
        mountTestApp(state)
        seedRig()
        val id = jsonClient().addOverSelection()

        val instance = assertNotNull(engine.getEffect(id))
        assertIs<FxTargetRef.SelectionRef>(instance.target.targetRef)
        assertEquals(listOf("hex-4", "hex-5", "hex-0", "hex-1", "hex-2", "hex-3"), engine.fixtureKeysCoveredBy(instance))
        val dto = engine.effectDto(instance)
        assertEquals("selection", dto.targetType)
        assertEquals(selectionKey, dto.targetKey)
        assertEquals(selectionTargets, dto.targets)
    }

    @Test
    fun `one target is that fixture or group alone`() = testApplication {
        mountTestApp(state)
        seedRig()
        val response = jsonClient().add(
            AddEffectRequest(effectType = "Pulse", propertyName = "dimmer", targets = listOf(CueTargetDto("group", "quad"))),
        )
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val instance = assertNotNull(engine.getEffect(response.body<AddEffectResponse>().effectId))
        assertEquals(FxTargetRef.GroupRef("quad"), instance.target.targetRef)
    }

    @Test
    fun `targets naming what the rig lacks are refused, every one at once`() = testApplication {
        mountTestApp(state)
        seedRig()
        val response = jsonClient().add(
            AddEffectRequest(
                effectType = "Pulse", propertyName = "dimmer",
                targets = listOf(CueTargetDto("fixture", "nope"), CueTargetDto("group", "gone"), CueTargetDto("selection", "[]")),
            ),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertContains(body, "no fixture 'nope'")
        assertContains(body, "no group 'gone'")
        assertContains(body, "targets[2]")
        assertEquals(0, engine.getActiveEffects().size)
    }

    @Test
    fun `a value row naming a selection is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-a")
        val response = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(
                NewCue(
                    name = "bad", cueStackId = stackId,
                    propertyAssignments = listOf(
                        CuePropertyAssignmentDto(
                            targetType = EffectTargetRef.Selection.TYPE, targetKey = selectionKey,
                            propertyName = "dimmer", value = "200",
                        ),
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "expected 'fixture' or 'group'")
    }

    @Test
    fun `a cue's ad-hoc effect may name a selection, and spawns as one instance`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-a")
        val effect = CueAdHocEffectDto(
            targetType = EffectTargetRef.Selection.TYPE, targetKey = selectionKey, effectType = "Pulse",
            category = "dimmer", propertyName = "dimmer", beatDivision = 1.0, blendMode = "OVERRIDE", distribution = "LINEAR",
        )
        val created = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "ripple", cueStackId = stackId, adHocEffects = listOf(effect)))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val cue = created.body<CueDetails>()
        assertEquals(selectionKey, cue.adHocEffects.single().targetKey)

        val bad = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "bad", cueStackId = stackId, adHocEffects = listOf(effect.copy(targetKey = "not json"))))
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status, bad.bodyAsText())
    }

    @Test
    fun `a recorded cue brings the effect back as one`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-a")
        client.addOverSelection()

        val recorded: ProgrammerRecordResponse = client.post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId, name = "ripple"))
        }.body()
        val child = recorded.cue.adHocEffects.single()
        assertEquals(EffectTargetRef.Selection.TYPE, child.targetType)
        assertEquals(selectionKey, child.targetKey)

        engine.clearAllEffects()
        val include = client.post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerIncludeRequest(projectId = projectId.toString(), cueId = recorded.cue.id))
        }
        assertEquals(HttpStatusCode.OK, include.status, include.bodyAsText())
        val back = engine.getActiveEffects().single()
        assertEquals(selectionKey, back.target.targetKey)
        assertEquals(listOf("hex-4", "hex-5", "hex-0", "hex-1", "hex-2", "hex-3"), engine.fixtureKeysCoveredBy(back))
    }

    @Test
    fun `a selection effect recorded into a Look keeps its selection`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val id = client.addOverSelection()

        val response = client.post("/api/rest/programmer/record-look") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerRecordLookRequest(projectId.toString(), "CREATE", name = "Ripple", effectIds = listOf(id)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val look = response.body<ProgrammerRecordLookResponse>().look
        val stored = look.effects.single()
        assertEquals(EffectTargetRef.Selection.TYPE, stored.targetType)
        assertEquals(selectionKey, stored.targetKey)
    }

    @Test
    fun `a selection longer than any fixed-width column records and comes back`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val keys = (1..12).map { "front-of-house-par-$it" }
        keys.forEachIndexed { i, key -> LocateTestSupport.seedHex(state, projectId, key, 1 + 12 * i) }
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-a")
        val added = client.add(
            AddEffectRequest(
                effectType = "Pulse", propertyName = "dimmer", programmerOwned = true,
                targets = keys.map { CueTargetDto(TargetRef.Fixture.TYPE, it) },
            ),
        )
        assertEquals(HttpStatusCode.OK, added.status, added.bodyAsText())
        assertTrue(engine.getActiveEffects().single().target.targetKey.length > 255)

        val recorded: ProgrammerRecordResponse = client.post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId, name = "long"))
        }.body()
        assertEquals(keys, EffectTargetRef.Selection.parseKey(recorded.cue.adHocEffects.single().targetKey)!!.map { it.key })
    }

    @Test
    fun `a hand-written selection is stored in the canonical spelling, and one item as that item`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val effect = LookEffectDto(
            targetType = EffectTargetRef.Selection.TYPE,
            targetKey = """[ {"type":"fixture", "key":"hex-4"}, {"type":"group","key":"quad"} ]""",
            effectType = "Pulse", category = "dimmer", propertyName = "dimmer", beatDivision = 1.0,
            blendMode = "OVERRIDE", distribution = "LINEAR",
        )
        val one = effect.copy(targetKey = """[{"type":"fixture","key":"hex-5"}]""", sortOrder = 1)
        val created = client.post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = "Ripple", effects = listOf(effect, one)))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val stored = created.body<LookDetails>().effects.sortedBy { it.sortOrder }
        assertEquals(EffectTargetRef.Selection.TYPE to """[{"key":"hex-4","type":"fixture"},{"key":"quad","type":"group"}]""", stored[0].targetType to stored[0].targetKey)
        assertEquals(TargetRef.Fixture.TYPE to "hex-5", stored[1].targetType to stored[1].targetKey)
    }

    @Test
    fun `a Look effect may name a selection, and a malformed one is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val effect = LookEffectDto(
            targetType = EffectTargetRef.Selection.TYPE, targetKey = selectionKey, effectType = "Pulse",
            category = "dimmer", propertyName = "dimmer", beatDivision = 1.0, blendMode = "OVERRIDE", distribution = "LINEAR",
        )
        val created = client.post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = "Ripple", effects = listOf(effect)))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        assertEquals(selectionKey, created.body<LookDetails>().effects.single().targetKey)

        val bad = client.post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = "Bad", effects = listOf(effect.copy(targetKey = "[]"))))
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status, bad.bodyAsText())
    }
}
