package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.models.LayerSource
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.LookFxDto
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.routes.CreateLookRequest
import uk.me.cormack.lighting7.routes.LookDetails
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The effect half of the programmer's layer stack, which needs a real `State`: both
 * `resolveTargetForCue` and `createFxInstance` take one, so unlike the value rules in
 * `ProgrammerLayerStackTest` this cannot run against a map-backed registry.
 *
 * The headline is `a reorder re-ranks the running effects without respawning them`. Layer order is
 * expressed as an offset within the programmer priority band precisely so that dragging a layer
 * doesn't have to recreate anything — a respawn would restart every effect's phase, which is a
 * visible hitch on the drag and the whole reason for the scheme.
 */
class ProgrammerLayerStackFxTest : RouteIntegrationTest() {

    private val stack get() = state.show.programmerLayerStack
    private val engine get() = state.show.fxEngine

    /** Programmer-band instances, oldest id first. */
    private fun bandFx() = engine.getActiveFx()
        .filter { FxEngine.isProgrammerFxPriority(it.priority) }
        .sortedBy { it.id }

    /** A Look with a row and an effect, both on `hex-1` — every test here seeds exactly that. */
    private suspend fun io.ktor.client.HttpClient.createLook(name: String, dimmer: String): LookDetails =
        post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = name,
                    rows = listOf(
                        LookRowDto("fixture", "hex-1", "dimmer", dimmer),
                    ),
                    fx = listOf(
                        LookFxDto(
                            targetType = "fixture", targetKey = "hex-1",
                            fxType = "Pulse", family = "intensity", attribute = "dimmer",
                            cycle = 0.5, blendMode = "OVERRIDE", order = "NORMAL",
                        ),
                    ),
                )
            )
        }.body()

    private fun add(look: LookDetails, vararg fixtureKeys: String) = stack.add(
        source = LayerSource.look(look.id, UUID.fromString(look.uuid), look.name),
        targets = fixtureKeys.map { CueTargetDto("fixture", it) },
    ).first

    @Test
    fun `adding a layer spawns its effects into the programmer band, tagged by layer`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
            val look = client.createLook("Pulse", "200")

            val layer = add(look, "hex-1")

            val effects = bandFx()
            assertEquals(1, effects.size)
            assertEquals(layer.layerId, effects.single().programmerLayerId)
            assertEquals(look.id, effects.single().lookId)
            // The registration id, not the display name: the two coincide for a built-in and
            // diverge for every user-defined FX definition.
            assertEquals("Pulse", effects.single().registrationId)
        }

    @Test
    fun `a reorder re-ranks the running effects without respawning them`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val warm = client.createLook("Warm", "200")
        val cool = client.createLook("Cool", "40")

        val warmLayer = add(warm, "hex-1")
        add(cool, "hex-1")
        val before = bandFx().associate { it.programmerLayerId to it.id }
        val warmPriorityBefore = bandFx().single { it.programmerLayerId == warmLayer.layerId }.priority

        stack.move(warmLayer.layerId, 1)

        val after = bandFx().associate { it.programmerLayerId to it.id }
        assertEquals(before, after, "the very same instances — no respawn, so no phase restart")
        val warmPriorityAfter = bandFx().single { it.programmerLayerId == warmLayer.layerId }.priority
        assertNotEquals(warmPriorityBefore, warmPriorityAfter, "but its rank moved")
        assertTrue(
            FxEngine.isProgrammerFxPriority(warmPriorityAfter),
            "and it stays inside the programmer band, which every consumer tests by predicate",
        )
    }

    @Test
    fun `removing a layer retracts its effects and leaves the others running`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val warm = client.createLook("Warm", "200")
        val cool = client.createLook("Cool", "40")
        val warmLayer = add(warm, "hex-1")
        val coolLayer = add(cool, "hex-1")
        assertEquals(2, bandFx().size)

        stack.remove(warmLayer.layerId)

        val remaining = bandFx()
        assertEquals(1, remaining.size)
        assertEquals(coolLayer.layerId, remaining.single().programmerLayerId)
    }

    @Test
    fun `disabling a layer retracts its effects, and re-enabling spawns them again`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
            val look = client.createLook("Pulse", "200")
            val layer = add(look, "hex-1")

            stack.patch(layer.layerId, enabled = false)
            assertTrue(bandFx().isEmpty())

            stack.patch(layer.layerId, enabled = true)
            assertEquals(1, bandFx().size)
        }

    @Test
    fun `an amount change re-ranks rather than respawning`() = testApplication {
        // Amount is a per-frame mix on the *values*; it must not disturb the effects at all. This
        // is the case a naive "rebuild everything on any mutation" implementation gets wrong most
        // visibly, because the operator is dragging.
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val look = client.createLook("Pulse", "200")
        val layer = add(look, "hex-1")
        val before = bandFx().single().id

        stack.patch(layer.layerId, amount = 0.5)

        assertEquals(before, bandFx().single().id)
    }

    @Test
    fun `an effect removed behind the stack's back is not resurrected`() = testApplication {
        // The FX sheet can remove a band effect directly, and `removeProgrammerBandFx` sweeps
        // the whole band. Either way the instance is simply absent from the engine's band record
        // (`programmerLayerFx`), so the next recook must respawn it rather than treat the
        // effect as still running.
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val look = client.createLook("Pulse", "200")
        val layer = add(look, "hex-1")
        engine.removeFx(bandFx().single().id)
        assertTrue(bandFx().isEmpty())

        // Any recook: the layer is unchanged, so this is the "nothing to do" path.
        stack.patch(layer.layerId, amount = 0.9)

        assertEquals(1, bandFx().size, "respawned exactly once, back in the band record")
        assertEquals(layer.layerId, bandFx().single().programmerLayerId)
    }

    @Test
    fun `clear-all takes the stack and its effects together`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val look = client.createLook("Pulse", "200")
        add(look, "hex-1")

        uk.me.cormack.lighting7.routes.clearProgrammerCompletely(state, 0)

        assertTrue(bandFx().isEmpty())
        assertTrue(state.show.programmerStore.layers.isEmpty())
        assertTrue(state.show.programmerStore.isEmpty)
    }

    @Test
    fun `a stomping programmer layer publishes suppression for the layers below it`() =
        testApplication {
            // The programmer's half of FU-LOOK-STOMP-WITHIN-CUE. Worth its own test because the
            // publish happens in `materialise`, not beside the instances in `syncFx` — and
            // because a programmer-layer effect sits in the reserved band, which is exempt from
            // *programmer* suppression, so stomp is the only thing that can quiet it.
            mountTestApp(state)
            val client = jsonClient()
            LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
            val below = client.createLook("Chase", "80")
            val stomper = client.createLook("Hold", "200")

            val lower = add(below, "hex-1")
            val upper = add(stomper, "hex-1")

            assertTrue(
                engine.cueLayer.programmerStompSuppressionForTest().isEmpty(),
                "nothing stomps until a layer says so",
            )

            stack.patch(upper.layerId, stomp = true)
            assertEquals(
                mapOf(lower.layerId to mapOf("hex-1" to setOf("dimmer"))),
                engine.cueLayer.programmerStompSuppressionForTest(),
            )

            // And it retracts: an order change alone moves the suppression, which is why it is
            // published from the cook rather than from the spawn/retract classifier.
            stack.move(upper.layerId, 0)
            assertTrue(
                engine.cueLayer.programmerStompSuppressionForTest().isEmpty(),
                "the stomper is now the bottom layer, so there is nothing below it to stomp",
            )
        }
}
