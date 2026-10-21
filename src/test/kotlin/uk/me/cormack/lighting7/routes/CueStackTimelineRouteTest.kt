package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CueAdHocEffectDto
import uk.me.cormack.lighting7.models.CueLayerDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCueAdHocEffects
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.TemplateRowDto
import uk.me.cormack.lighting7.models.speedMastersOf
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /cue-stacks/{id}/timeline` (stack-timeline plan §3.5): every entry of a stack in list order,
 * markers included, with each cue's layers, ad-hoc effects, scenery and tracked scenery, and the
 * heads, families and comparable settings the client derives the grid from.
 */
class CueStackTimelineRouteTest : RouteIntegrationTest() {

    private val base get() = "/api/rest/projects/$projectId"

    private fun seedRig() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        LocateTestSupport.seedGroup(state, projectId, "wash", "hex-1", "hex-2")
        state.show.fixtures.patchListChanged()
    }

    private suspend fun HttpClient.palette(name: String): Int =
        post("$base/palettes") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = name, rows = listOf(TemplateRowDto(DEFERRED_TARGET_TYPE, "", "rgbColour", "#8f7bff"))))
        }.also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.body<TemplateDto>().id

    private suspend fun HttpClient.look(name: String): Int =
        post("$base/looks") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = name,
                    rows = listOf(LookRowDto("fixture", "hex-1", "dimmer", "200"), LookRowDto("fixture", "hex-1", "rgbColour", "#00ff00")),
                ),
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.body<LookDetails>().id

    private suspend fun HttpClient.stack(name: String): Int =
        post("$base/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    private suspend fun HttpClient.cue(
        stackId: Int,
        name: String,
        layers: List<CueLayerDto> = emptyList(),
        effects: List<CueAdHocEffectDto> = emptyList(),
    ): Int = post("$base/cues") {
        contentType(ContentType.Application.Json)
        setBody(NewCue(name = name, cueStackId = stackId, cueNumber = name.removePrefix("Q"), layers = layers, adHocEffects = effects))
    }.also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.body<CueDetails>().id

    private fun pulse(targetType: String, targetKey: String, speedMasterUuid: String? = null) = CueAdHocEffectDto(
        targetType = targetType, targetKey = targetKey, effectType = "Pulse", category = "dimmer", propertyName = "dimmer",
        beatDivision = 1.0, blendMode = "OVERRIDE", distribution = "LINEAR", speedMasterUuid = speedMasterUuid,
    )

    private fun keyRun(cueId: Int, effectType: String, run: UUID) = transaction(state.database) {
        DaoCueAdHocEffect.find { DaoCueAdHocEffects.cue eq cueId }.single { it.effectType == effectType }.runUuid = run
    }

    @Test
    fun `a stack's timeline carries every entry with its layers, effects and scenery`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val lavender = client.palette("Lavender")
        val warm = client.look("Warm")
        val tabs = client.post("$base/stage-elements") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("name", "House tabs"); put("kind", "DRAPE"); put("layer", "VENUE")
                put("widthM", 8.0); put("depthM", 0.1); put("heightM", 5.0)
                putJsonObject("params") { put("role", "TABS"); put("operation", "DRAW"); putJsonObject("states") { put("open", 1.0) } }
            })
        }.body<StageElementDto>().uuid
        val master1 = transaction(state.database) { speedMastersOf(DaoProject.findById(projectId)!!).first().uuid.toString() }

        val act = client.stack("Act 1")
        val q1 = client.cue(
            act, "Q1",
            layers = listOf(
                CueLayerDto(lookId = warm, sortOrder = 0),
                CueLayerDto(templateId = lavender, sortOrder = 1, targets = listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "hex-2")), amount = 0.6),
                CueLayerDto(templateId = lavender, sortOrder = 2, targets = listOf(CueTargetDto("group", "wash")), delayMs = 500),
            ),
            effects = listOf(pulse("group", "wash")),
        )
        client.put("$base/cues/$q1/scenery") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                putJsonArray("scenery") { add(buildJsonObject { put("elementUuid", tabs); putJsonObject("state") { put("open", 0.0) }; put("transitionMs", 4000) }) }
            })
        }.also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        client.post("$base/cue-stacks/$act/markers") {
            contentType(ContentType.Application.Json)
            setBody(MarkerEntryRequest(label = "Scene 2"))
        }.also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }
        // Master 1 by its uuid here, by null in Q1: one run's settings either way.
        val q2 = client.cue(
            act, "Q2",
            effects = listOf(
                pulse("group", "wash", speedMasterUuid = master1),
                pulse("fixture", "hex-1").copy(effectType = "SineWave", propertyName = "dimmer"),
            ),
        )
        // Q1 again, a second place of the same cue.
        client.post("$base/cue-stacks/$act/add-cue") {
            contentType(ContentType.Application.Json)
            setBody(AddCueToStackRequest(cueId = q1))
        }.also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        val run = UUID.randomUUID()
        keyRun(q1, "Pulse", run)
        keyRun(q2, "Pulse", run)

        val resp = client.get("$base/cue-stacks/$act/timeline")
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val timeline = resp.body<CueStackTimelineDto>()
        assertEquals(act, timeline.stackId)
        assertEquals(listOf("CUE", "MARKER", "CUE", "CUE"), timeline.entries.map { it.kind })
        assertEquals(listOf(q1, null, q2, q1), timeline.entries.map { it.cueId })
        val (first, marker, second, again) = timeline.entries
        assertEquals("Scene 2", marker.label)
        assertTrue(marker.layers.isEmpty() && marker.effects.isEmpty())
        assertTrue(first.entryId != again.entryId)

        // Layers, bottom first: the Look on its own fixtures, a palette over two targets, one over a group.
        assertEquals(listOf("Warm", "Lavender", "Lavender"), first.layers.map { it.source.name })
        val (look, twoTargets, onGroup) = first.layers
        assertEquals(listOf("INTENSITY", "COLOUR"), look.source.families)
        assertEquals(emptyList(), look.targets)
        assertEquals(listOf("hex-1"), look.heads)
        assertEquals(listOf("COLOUR"), twoTargets.source.families)
        assertEquals(2, twoTargets.targets.size)
        assertEquals(listOf("hex-1", "hex-2"), twoTargets.heads)
        assertEquals(0.6, twoTargets.amount)
        assertEquals(false, twoTargets.timed)
        assertEquals(listOf("hex-1", "hex-2"), onGroup.heads)
        assertEquals(true, onGroup.timed)
        assertEquals(first.layers, again.layers, "a cue's two places carry the same layers")

        // Effects: the keyed Pulse in Q1 and Q2 one run with one settings; the SineWave unkeyed.
        val pulse1 = first.effects.single()
        val pulse2 = second.effects.single { it.effectType == "Pulse" }
        val sine = second.effects.single { it.effectType == "SineWave" }
        assertEquals(run.toString(), pulse1.runUuid)
        assertEquals(pulse1.runUuid, pulse2.runUuid)
        assertTrue(pulse1.uuid != pulse2.uuid)
        assertEquals(pulse1.settings, pulse2.settings, "master 1 by null and by uuid compare alike")
        assertEquals(master1, pulse1.settings.speedMasterUuid)
        assertEquals("INTENSITY", pulse1.family)
        assertEquals(listOf("hex-1", "hex-2"), pulse1.heads)
        assertEquals("group" to "wash", pulse1.targetType to pulse1.targetKey)
        assertNull(sine.runUuid)
        assertEquals(listOf("hex-1"), sine.heads)
        assertTrue(sine.settings != pulse2.settings)

        // Scenery: Q1's own change, tracked into Q2 across the marker; Q1's second place sets it again.
        assertEquals(listOf(tabs), first.scenery.map { it.elementUuid })
        assertEquals(4000L, first.scenery.single().transitionMs)
        assertEquals(0.0, (first.scenery.single().state["open"] ?: error("no open")).jsonPrimitive.content.toDouble())
        assertTrue(first.trackedScenery.isEmpty())
        val tracked = second.trackedScenery.single()
        assertEquals(tabs, tracked.elementUuid)
        assertEquals(q1, tracked.fromCueId)
        assertTrue(second.scenery.isEmpty())
        assertTrue(again.trackedScenery.isEmpty(), "Q1 sets the tabs itself at its second place")
    }

    @Test
    fun `a layer's heads are the heads it lands on, not every head its targets name`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.look("Warm")
        // A per-fixture palette: its rows name hex-2 alone.
        val throne = client.post("$base/palettes") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "Throne", rows = listOf(TemplateRowDto("fixture", "hex-2", "dimmer", "pct:100"))))
        }.also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.body<TemplateDto>().id
        val lavender = client.palette("Lavender")
        val wash = listOf(CueTargetDto("group", "wash"))
        val act = client.stack("Act 1")
        client.cue(
            act, "Q1",
            layers = listOf(
                CueLayerDto(lookId = warm, sortOrder = 0, targets = wash),
                CueLayerDto(templateId = throne, sortOrder = 1, targets = wash),
                CueLayerDto(templateId = throne, sortOrder = 2),
                CueLayerDto(templateId = lavender, sortOrder = 3, targets = wash),
            ),
        )

        val layers = client.get("$base/cue-stacks/$act/timeline").body<CueStackTimelineDto>().entries.single().layers
        // Warm binds hex-1 only: the wash narrows its rows, it does not reach hex-2.
        assertEquals(listOf("hex-1"), layers[0].heads)
        assertEquals(listOf("hex-2"), layers[1].heads)
        assertEquals(listOf("hex-2"), layers[2].heads)
        // A generic palette lands on every head it is pressed onto.
        assertEquals(listOf("hex-1", "hex-2"), layers[3].heads)
    }

    @Test
    fun `another project's stack, or none, is not found`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val elsewhere = transaction(state.database) {
            val other = DaoProject.new { name = "other"; isCurrent = false }
            DaoCueStack.new { name = "Theirs"; project = other }.id.value
        }
        assertEquals(HttpStatusCode.NotFound, client.get("$base/cue-stacks/$elsewhere/timeline").status)
        assertEquals(HttpStatusCode.NotFound, client.get("$base/cue-stacks/9999/timeline").status)
    }

    @Test
    fun `a cue placed twice tracks scenery at each of its places`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val moon = client.post("$base/stage-elements") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("name", "Moon"); put("kind", "OBJECT"); put("layer", "SET")
                put("positionZ", 3.0); put("widthM", 1.0); put("depthM", 0.05); put("heightM", 1.0)
                putJsonObject("params") { put("shape", "DISC"); put("flies", true); putJsonObject("states") { put("trimM", 7.0) } }
            })
        }.body<StageElementDto>().uuid
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1")
        val q2 = client.cue(act, "Q2")
        client.put("$base/cues/$q2/scenery") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                putJsonArray("scenery") { add(buildJsonObject { put("elementUuid", moon); putJsonObject("state") { put("trimM", 2.0) } }) }
            })
        }.also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        client.post("$base/cue-stacks/$act/add-cue") {
            contentType(ContentType.Application.Json)
            setBody(AddCueToStackRequest(cueId = q1))
        }

        val entries = client.get("$base/cue-stacks/$act/timeline").body<CueStackTimelineDto>().entries
        assertEquals(listOf(q1, q2, q1), entries.map { it.cueId })
        assertTrue(entries[0].trackedScenery.isEmpty(), "nothing before Q1's first place moves the moon")
        val later = assertNotNull(entries[2].trackedScenery.singleOrNull())
        assertEquals(q2, later.fromCueId)
        assertEquals(2.0, later.state["trimM"]!!.jsonPrimitive.content.toDouble())
    }
}
