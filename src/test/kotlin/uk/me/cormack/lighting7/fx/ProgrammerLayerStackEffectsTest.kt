package uk.me.cormack.lighting7.fx

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
 * `resolveTargetForCue` and `createInstanceFromPreset` take one, so unlike the value rules in
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

    private suspend fun io.ktor.client.HttpClient.createLook(name: String, dimmer: String): LookDetails =
        post("/api/rest/project/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = name,
                    editorFixtureType = "hex",
                    rows = listOf(
                        LookRowDto(DEFERRED_TARGET_TYPE, "", "dimmer", dimmer),
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
        lookId = look.id,
        lookUuid = UUID.fromString(look.uuid),
        lookName = look.name,
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
            // `presetId` stays null: passing the *Look* id there is what made captureCurrentState
            // reconstruct a preset application naming whatever DaoFxPreset shared the number.
            assertEquals(null, effects.single().presetId)
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
        // the whole band. Either leaves the stack's instance map holding a dead id; the next recook
        // must drop it rather than treat the effect as still running.
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val look = client.createLook("Pulse", "200")
        val layer = add(look, "hex-1")
        engine.removeEffect(bandEffects().single().id)
        assertTrue(bandEffects().isEmpty())

        // Any recook: the layer is unchanged, so this is the "nothing to do" path.
        stack.patch(layer.layerId, amount = 0.9)

        assertEquals(1, bandEffects().size, "respawned exactly once, and tracked again")
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
}
