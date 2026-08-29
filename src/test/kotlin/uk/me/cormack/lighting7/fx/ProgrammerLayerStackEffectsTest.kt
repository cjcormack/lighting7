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
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.LookEffectDto
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
 * `resolveTargetForCue` and `createEffectInstance` take one, so unlike the value rules in
 * `ProgrammerLayerStackTest` this cannot run against a map-backed registry.
 *
 * The headline is `a reorder re-ranks the running effects without respawning them`. Layer order is
 * expressed as an offset within the programmer priority band precisely so that dragging a layer
 * doesn't have to recreate anything — a respawn would restart every effect's phase, which is a
 * visible hitch on the drag and the whole reason for the scheme.
 */
class ProgrammerLayerStackEffectsTest : RouteIntegrationTest() {

    private val stack get() = state.show.programmerLayerStack
    private val engine get() = state.show.fxEngine

    /** Programmer-band instances, oldest id first. */
    private fun bandEffects() = engine.getActiveEffects()
        .filter { FxEngine.isProgrammerFxPriority(it.priority) }
        .sortedBy { it.id }

    /**
     * A Look with one **bound** row and one deferred effect.
     *
     * The row is bound to `hex-1` — every test here seeds exactly that — because a Look row may no
     * longer be deferred: session 3 moved the deferred half of the entity out to templates, and the
     * write boundary refuses one. The *effect* stays deferred, which is the shape these tests are
     * actually about: it fans over whatever targets the layer names.
     */
    private suspend fun io.ktor.client.HttpClient.createLook(name: String, dimmer: String): LookDetails =
        post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = name,
                    rows = listOf(
                        LookRowDto("fixture", "hex-1", "dimmer", dimmer),
                    ),
                    effects = listOf(
                        LookEffectDto(
                            targetType = DEFERRED_TARGET_TYPE, targetKey = "",
                            effectType = "Pulse", category = "dimmer", propertyName = "dimmer",
                            beatDivision = 0.5, blendMode = "OVERRIDE", distribution = "LINEAR",
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

            val effects = bandEffects()
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
        val before = bandEffects().associate { it.programmerLayerId to it.id }
        val warmPriorityBefore = bandEffects().single { it.programmerLayerId == warmLayer.layerId }.priority

        stack.move(warmLayer.layerId, 1)

        val after = bandEffects().associate { it.programmerLayerId to it.id }
        assertEquals(before, after, "the very same instances — no respawn, so no phase restart")
        val warmPriorityAfter = bandEffects().single { it.programmerLayerId == warmLayer.layerId }.priority
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
        assertEquals(2, bandEffects().size)

        stack.remove(warmLayer.layerId)

        val remaining = bandEffects()
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
            assertTrue(bandEffects().isEmpty())

            stack.patch(layer.layerId, enabled = true)
            assertEquals(1, bandEffects().size)
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
        val before = bandEffects().single().id

        stack.patch(layer.layerId, amount = 0.5)

        assertEquals(before, bandEffects().single().id)
    }

    @Test
    fun `an effect removed behind the stack's back is not resurrected`() = testApplication {
        // The FX sheet can remove a band effect directly, and `removeProgrammerBandEffects` sweeps
        // the whole band. Either way the instance is simply absent from the engine's band record
        // (`programmerLayerEffects`), so the next recook must respawn it rather than treat the
        // effect as still running.
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val look = client.createLook("Pulse", "200")
        val layer = add(look, "hex-1")
        engine.removeEffect(bandEffects().single().id)
        assertTrue(bandEffects().isEmpty())

        // Any recook: the layer is unchanged, so this is the "nothing to do" path.
        stack.patch(layer.layerId, amount = 0.9)

        assertEquals(1, bandEffects().size, "respawned exactly once, back in the band record")
        assertEquals(layer.layerId, bandEffects().single().programmerLayerId)
    }

    @Test
    fun `clear-all takes the stack and its effects together`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val look = client.createLook("Pulse", "200")
        add(look, "hex-1")

        uk.me.cormack.lighting7.routes.clearProgrammerCompletely(state, 0)

        assertTrue(bandEffects().isEmpty())
        assertTrue(state.show.programmerStore.layers.isEmpty())
        assertTrue(state.show.programmerStore.isEmpty)
    }

    @Test
    fun `a stomping programmer layer publishes suppression for the layers below it`() =
        testApplication {
            // The programmer's half of FU-LOOK-STOMP-WITHIN-CUE. Worth its own test because the
            // publish happens in `materialise`, not beside the instances in `syncEffects` — and
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
