package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.Layer3Resolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.programmerValue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `POST /api/rest/programmer/include`. */
class ProgrammerIncludeRouteTest : RouteIntegrationTest() {

    @Test
    fun `include writes INCLUDE slots and returns the fixtures to select`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        val cueId = createCue(
            client, "look",
            rows = listOf(
                assignment("fixture", "hex-1", "dimmer", "200"),
                assignment("fixture", "hex-2", "dimmer", "120"),
            ),
        )

        val response: ProgrammerIncludeResponse = client.include(cueId).body()

        assertEquals(2, response.entriesWritten)
        assertEquals(listOf("hex-1", "hex-2"), response.fixtureKeys)

        val slot = state.show.programmerStore.get("hex-1", "dimmer")!!
        assertEquals(ProgrammerOwner.INCLUDE, slot.owner)
        assertTrue(slot.touched, "included content must be recordable")
        assertEquals(Layer3Resolver.PropertyValue.Slider(200u), slot.value.resolved)

        val target = state.show.programmerStore.lastIncludedTarget
        assertNotNull(target)
        assertEquals(cueId, target.cueId)
    }

    @Test
    fun `a group row includes with its group hint, so a re-record round-trips the shape`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            seedHex("hex-1", 1)
            seedHex("hex-2", 13)
            LocateTestSupport.seedGroup(state, projectId, "front-wash", "hex-1", "hex-2")
            val cueId = createCue(
                client, "wash",
                rows = listOf(assignment("group", "front-wash", "dimmer", "150")),
            )

            val included: ProgrammerIncludeResponse = client.include(cueId).body()
            assertEquals(2, included.entriesWritten, "the group row fans out to both members")
            assertEquals(listOf("front-wash"), included.groupKeys)
            assertEquals("front-wash", state.show.programmerStore.get("hex-1", "dimmer")!!.sourceGroup)

            // Include → (no edits) → Record must give the cue back in the shape it went in.
            val rerecorded: ProgrammerRecordResponse = client.post("/api/rest/programmer/record") {
                contentType(ContentType.Application.Json)
                setBody(
                    ProgrammerRecordRequest(
                        projectId = projectId.toString(),
                        mode = "UPDATE_EXISTING",
                        cueId = cueId,
                    )
                )
            }.body()

            val row = rerecorded.cue.propertyAssignments.single()
            assertEquals("group", row.targetType)
            assertEquals("front-wash", row.targetKey)
            assertEquals("150", row.value)
        }

    @Test
    fun `a fixture override beats the group-expanded row it shadows`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        LocateTestSupport.seedGroup(state, projectId, "front-wash", "hex-1", "hex-2")
        // The builders emit both rows for hex-2 and leave the resolver to apply specificity at
        // compose time. The programmer has no such pass, so Include must apply it itself —
        // otherwise list order would decide, and hex-2 could land on 150.
        val cueId = createCue(
            client, "wash+override",
            rows = listOf(
                assignment("group", "front-wash", "dimmer", "150"),
                assignment("fixture", "hex-2", "dimmer", "40"),
            ),
        )

        client.include(cueId)

        assertEquals(
            Layer3Resolver.PropertyValue.Slider(150u),
            programmerValue(state, "hex-1", "dimmer"),
        )
        assertEquals(
            Layer3Resolver.PropertyValue.Slider(40u),
            programmerValue(state, "hex-2", "dimmer"),
            "the direct fixture row is the more specific statement",
        )
    }

    @Test
    fun `include spawns the cue's FX into the programmer band, untagged by cue`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val cueId = createCue(
            client, "chase",
            adHoc = listOf(
                CueAdHocEffectSpec(
                    targetType = "fixture", targetKey = "hex-1",
                    effectType = "SineWave", propertyName = "dimmer",
                ),
            ),
        )

        val response: ProgrammerIncludeResponse = client.include(cueId).body()
        assertEquals(1, response.fxSpawned)

        val instance = state.show.fxEngine.getActiveEffects().single()
        assertTrue(FxEngine.isProgrammerFxPriority(instance.priority))
        // Untagged on purpose: a cue tag would let removeEffectsForCue sweep the operator's
        // programmer contents out from under them when the cue stops.
        assertNull(instance.cueId)
        assertEquals(cueId, instance.programmerOrigin?.cueId)

        assertEquals(0, state.show.fxEngine.removeEffectsForCue(cueId), "not swept with the cue")
        assertEquals(1, state.show.fxEngine.getActiveEffects().size)
    }

    @Test
    fun `including a live cue reports its running FX rather than duplicating them`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val cueId = createCue(
            client, "chase",
            adHoc = listOf(
                CueAdHocEffectSpec(
                    targetType = "fixture", targetKey = "hex-1",
                    effectType = "SineWave", propertyName = "dimmer",
                ),
            ),
        )
        client.post("/api/rest/project/$projectId/cues/$cueId/apply")
        assertEquals(1, state.show.fxEngine.getActiveEffects().size)

        val response: ProgrammerIncludeResponse = client.include(cueId).body()

        // There is no FX-vs-FX suppression, so a band duplicate would compose on top and
        // visibly double the effect under any non-OVERRIDE blend.
        assertEquals(0, response.fxSpawned)
        assertEquals(1, response.fxAlreadyRunning)
        assertEquals(1, state.show.fxEngine.getActiveEffects().size)
    }

    @Test
    fun `mask scopes what include pulls in`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val cueId = createCue(
            client, "look",
            rows = listOf(
                assignment("fixture", "hex-1", "dimmer", "200"),
                assignment("fixture", "hex-1", "rgbColour", "#ff0000"),
            ),
        )

        client.post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(
                ProgrammerIncludeRequest(
                    projectId = projectId.toString(), cueId = cueId, mask = listOf("COLOUR"),
                )
            )
        }

        assertNull(programmerValue(state, "hex-1", "dimmer"))
        assertNotNull(programmerValue(state, "hex-1", "rgbColour"))
    }

    @Test
    fun `clear-all releases the include and forgets the target`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val cueId = createCue(
            client, "look", rows = listOf(assignment("fixture", "hex-1", "dimmer", "200")),
        )
        client.include(cueId)
        assertNotNull(state.show.programmerStore.lastIncludedTarget)

        client.post("/api/rest/programmer/clear-all")

        assertEquals(0, state.show.programmerStore.size)
        assertNull(
            state.show.programmerStore.lastIncludedTarget,
            "nothing is staged, so Update has nothing to write back",
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private suspend fun HttpClient.include(cueId: Int) =
        post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerIncludeRequest(projectId = projectId.toString(), cueId = cueId))
        }

    private fun assignment(type: String, key: String, property: String, value: String) =
        CuePropertyAssignmentDto(
            targetType = type, targetKey = key, propertyName = property, value = value,
        )

    private suspend fun createCue(
        client: HttpClient,
        name: String,
        rows: List<CuePropertyAssignmentDto> = emptyList(),
        adHoc: List<CueAdHocEffectSpec> = emptyList(),
    ): Int = ProgrammerRouteTestSupport.createCue(client, projectId, name, rows, adHoc)

    private fun seedHex(key: String, startChannel: Int) =
        LocateTestSupport.seedHex(state, projectId, key, startChannel)
}
