package uk.me.cormack.lighting7.plugins

import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fx.BlendMode
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.EffectSuppression
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.testsupport.EffectTestSupport
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `programmer.keyStack` (fixture-fx-sheets plan W1): what sits under one property, top first, with
 * each layer's `onStage` and each effect's `heldBack` — the latter asked of the engine's own
 * suppression rule ([EffectSuppression]) over its own snapshot, never a second copy of it.
 *
 * The five cases the plan names: a held position under a band Circle, a cue Pulse held back by a
 * programmer dimmer, a parked key, a group answered per member, and Blind emptying the held-back set.
 */
class ProgrammerKeyStackTest : RouteIntegrationTest() {

    private fun ask(targetType: String, targetKey: String, propertyName: String) =
        ProgrammerHandler.keyStack(
            state, ProgrammerKeyStackInMessage(targetType, targetKey, propertyName, requestId = "r1"),
        )

    private fun kinds(stack: KeyStackDto) = stack.layers.map { it.kind }

    private fun setDimmer(key: String, value: Int) = ProgrammerHandler.setTyped(
        state, TargetRef.Fixture(key), "dimmer",
        CueAssignmentResolver.PropertyValue.Slider(value.toUByte()), fadeMs = 0,
    )

    @Test
    fun `a held position under a band Circle lists the Circle above the programmer, painting`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "fusion-100-spot-mkii-5ch", "spot-1", 1)
        ProgrammerHandler.setTyped(
            state, TargetRef.Fixture("spot-1"), "position",
            CueAssignmentResolver.PropertyValue.Position(40u, 200u), fadeMs = 0,
        )
        val circle = EffectTestSupport.start(
            state, "Circle", "spot-1", "position", programmerBand = true, blendMode = BlendMode.ADDITIVE,
        )

        val answer = ask("fixture", "spot-1", "position")
        assertNull(answer.error)
        assertEquals("r1", answer.requestId, "the request id is echoed so a promise can be matched")
        val stack = answer.stacks.single()
        assertEquals(listOf("PROGRAMMER_EFFECT", "PROGRAMMER", "BASE"), kinds(stack))

        val band = stack.layers[0]
        assertEquals(circle.id, band.effectId)
        assertEquals(false, band.heldBack, "a band effect modulates on top of the programmer")
        assertTrue(band.onStage)
        assertEquals("ADDITIVE", band.blendMode)

        val programmer = stack.layers[1]
        assertEquals("web", programmer.owner)
        assertEquals("40,200", programmer.value)
        assertTrue(programmer.onStage, "an Additive Circle orbits the held value, so the value is on stage under it")
        assertNotNull(programmer.ageMs)
        assertFalse(stack.layers[2].onStage, "the base is under the programmer")
    }

    @Test
    fun `an Override band effect covers the programmer value underneath`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "fusion-100-spot-mkii-5ch", "spot-1", 1)
        ProgrammerHandler.setTyped(
            state, TargetRef.Fixture("spot-1"), "position",
            CueAssignmentResolver.PropertyValue.Position(40u, 200u), fadeMs = 0,
        )
        EffectTestSupport.start(state, "Circle", "spot-1", "position", programmerBand = true)

        val layers = ask("fixture", "spot-1", "position").stacks.single().layers
        assertTrue(layers[0].onStage)
        assertFalse(layers.single { it.kind == "PROGRAMMER" }.onStage, "Override replaces what is underneath")
    }

    @Test
    fun `a cue Pulse under a programmer dimmer is listed below it and held back`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val pulse = EffectTestSupport.start(state, "Pulse", "hex-1", "dimmer", priority = 1_000_001, cueId = 42)
        setDimmer("hex-1", 200)

        val stack = ask("fixture", "hex-1", "dimmer").stacks.single()
        assertEquals(listOf("PROGRAMMER", "EFFECT", "BASE"), kinds(stack))
        val effect = stack.layers[1]
        assertEquals(pulse.id, effect.effectId)
        assertEquals(42, effect.cueId)
        assertEquals(true, effect.heldBack)
        assertFalse(effect.onStage)
        assertTrue(stack.layers[0].onStage)

        // One rule: the read's answer is the tick's, asked of the same snapshot.
        val engine = state.show.fxEngine
        assertTrue(
            EffectSuppression.isSuppressed(
                engine.programmerSuppression(), "hex-1", "dimmer", pulse, engine.cueLayer::isLayerStomped,
            ),
        )
    }

    @Test
    fun `blind empties the held-back set and takes the programmer off stage`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        EffectTestSupport.start(state, "Pulse", "hex-1", "dimmer", priority = 1_000_001, cueId = 42)
        setDimmer("hex-1", 200)
        state.show.fxEngine.programmer.setBlind(true)

        val answer = ask("fixture", "hex-1", "dimmer")
        assertTrue(answer.blind)
        val layers = answer.stacks.single().layers
        assertTrue(layers.none { it.heldBack == true }, "blind holds nothing back")
        assertFalse(layers.single { it.kind == "PROGRAMMER" }.onStage, "blind: listed, not on stage")
        assertTrue(layers.single { it.kind == "EFFECT" }.onStage, "the Pulse paints again")
    }

    @Test
    fun `a sideband byte newer than the entry on a one-channel property is what is on stage`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        setDimmer("hex-1", 200)
        // A raw byte held on the dimmer's channel after the entry — an unpark hand-down, which a
        // property write does not absorb. Recency arbitrates across granularities, so it wins.
        state.show.fxEngine.programmer.writeChannel(
            uk.me.cormack.lighting7.fx.ProgrammerOwner.UNPARK, 0, 1, 90u,
            coveringKey = CueAssignmentResolver.Key.fixture("hex-1", "dimmer"),
        )

        val programmer = ask("fixture", "hex-1", "dimmer").stacks.single().layers.filter { it.kind == "PROGRAMMER" }
        val entry = programmer.single { it.channel == null }
        val raw = programmer.single { it.channel != null }
        assertFalse(entry.onStage, "the older entry is not what the channel carries")
        assertTrue(raw.onStage)
        assertEquals("90", raw.value)
        assertEquals(1, programmer.count { it.onStage }, "one value on stage, never two")
    }

    @Test
    fun `a parked key lists park on top and nothing under it on stage`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        setDimmer("hex-1", 200)
        state.show.parkManager.park(0, 1, 77u)

        val layers = ask("fixture", "hex-1", "dimmer").stacks.single().layers
        assertEquals("PARK", layers.first().kind)
        assertTrue(layers.first().onStage)
        assertEquals(listOf(KeyStackParkedChannelDto(0, 1, 77)), layers.first().parkedChannels)
        assertTrue(layers.drop(1).none { it.onStage }, "a full park covers everything below it")
    }

    @Test
    fun `a cue's value names its cue, and the base is the fixture default`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        state.show.fxEngine.cueLayer.setAssignments(
            501,
            listOf(
                CueAssignmentResolver.Assignment(
                    cueId = 501, priority = 10, fadeWeight = 1.0,
                    targetKey = "hex-1", targetIsGroup = false, propertyName = "dimmer",
                    category = uk.me.cormack.lighting7.fixture.PropertyCategory.DIMMER,
                    value = CueAssignmentResolver.PropertyValue.Slider(120u),
                ),
            ),
        )

        val layers = ask("fixture", "hex-1", "dimmer").stacks.single().layers
        assertEquals(listOf("CUE", "BASE"), layers.map { it.kind })
        assertEquals(501, layers[0].cueId)
        assertEquals("120", layers[0].value)
        assertTrue(layers[0].onStage)
        assertEquals("0", layers[1].value)
        assertFalse(layers[1].onStage)
    }

    @Test
    fun `a group target answers one stack per member`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 20)
        LocateTestSupport.seedGroup(state, projectId, "front", "hex-1", "hex-2")
        setDimmer("hex-1", 200)

        val stacks = ask("group", "front", "dimmer").stacks
        assertEquals(listOf("hex-1", "hex-2"), stacks.map { it.targetKey })
        assertEquals(listOf("PROGRAMMER", "BASE"), kinds(stacks[0]))
        assertEquals(listOf("BASE"), kinds(stacks[1]))
    }

    @Test
    fun `an unknown target answers an error rather than nothing`() = testApplication {
        mountTestApp(state)
        val answer = ask("fixture", "nope", "dimmer")
        assertTrue(answer.stacks.isEmpty())
        assertNotNull(answer.error)
    }

    @Test
    fun `the answer goes to the asking socket, with its request id`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = createWsClient()
        client.webSocket("/api") {
            sendSerialized<InMessage>(ProgrammerKeyStackInMessage("fixture", "hex-1", "dimmer", requestId = "abc"))
            val reply = awaitOfType<ProgrammerKeyStackOutMessage>()
            assertEquals("abc", reply.requestId)
            assertEquals("hex-1", reply.stacks.single().targetKey)
        }
    }
}
