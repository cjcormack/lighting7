package uk.me.cormack.lighting7.plugins

import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.testsupport.EffectTestSupport
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.routes.CreateLookRequest
import uk.me.cormack.lighting7.routes.LookDetails
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.awt.Color
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `programmer.clearTarget` (fixture-fx-sheets plan W2): a fixture's or a group's *Release* — every
 * owner's slot but a layer's, on every property and every head, plus the local effects on it, in one
 * pass at the programmer fade. A group effect that also drives heads outside the target is left
 * running and named, the rule the grid's ⌫ applies.
 */
class ProgrammerClearTargetTest : RouteIntegrationTest() {

    private fun release(targetType: String, targetKey: String, fadeMs: Long = 0) =
        ProgrammerHandler.clearTarget(
            state, ProgrammerClearTargetInMessage(targetType, targetKey, fadeMs, requestId = "r1"),
        )

    private fun set(key: String, property: String, value: CueAssignmentResolver.PropertyValue) =
        ProgrammerHandler.setTyped(state, TargetRef.Fixture(key), property, value, fadeMs = 0)

    private val store get() = state.show.programmerStore
    private val engine get() = state.show.fxEngine

    private fun controller() = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController

    @Test
    fun `releases every value on the fixture and on its heads, every owner`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "led-lightbar-12-pixel-48ch", "bar", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 60)
        set("bar.pixel-2", "rgbColour", CueAssignmentResolver.PropertyValue.Colour(ExtendedColour(Color(200, 0, 0))))
        set("bar.pixel-3", "white", CueAssignmentResolver.PropertyValue.Slider(90u))
        engine.programmer.writeProperty(
            ProgrammerOwner.SURFACE, state.show.fixtures.untypedGroupableFixture("bar.pixel-3"), "white",
            CueAssignmentResolver.PropertyValue.Slider(120u),
        )
        set("hex-1", "dimmer", CueAssignmentResolver.PropertyValue.Slider(200u))

        val answer = release("fixture", "bar")
        assertNull(answer.error)
        assertEquals("r1", answer.requestId)
        assertEquals(2, answer.values, "one per key released, however many owners held it")
        assertTrue(store.entries().none { it.fixtureKey.startsWith("bar") }, "the heads went with the bar")
        assertEquals(listOf("hex-1"), store.entries().map { it.fixtureKey }, "and nothing else did")
    }

    @Test
    fun `a raw channel on the fixture goes too`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        handleUpdateChannel(state, UpdateChannelInMessage(0, 2, 180u, fadeTime = 0))
        assertTrue(store.channelEntries().isNotEmpty() || store.entries().isNotEmpty())

        release("fixture", "hex-1")
        assertTrue(store.channelEntries().isEmpty())
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `a layer's slots are left alone`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val look: LookDetails = jsonClient().post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = "Warm", rows = listOf(LookRowDto("fixture", "hex-1", "dimmer", "150"))))
        }.body()
        state.show.programmerLayerStack.add(
            source = LayerSource.look(look.id, UUID.fromString(look.uuid), look.name),
            targets = listOf(CueTargetDto("fixture", "hex-1")),
        )
        set("hex-1", "dimmer", CueAssignmentResolver.PropertyValue.Slider(50u))
        assertEquals(
            listOf("web", "layers"),
            store.slotsFor("hex-1", "dimmer").map { it.owner.id },
        )

        release("fixture", "hex-1")
        assertEquals(
            listOf("layers"), store.slotsFor("hex-1", "dimmer").map { it.owner.id },
            "a layer leaves by being removed, not by clearing what it cooked",
        )
    }

    @Test
    fun `local effects on the fixture stop, and an effect that belongs to something stays`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val local = EffectTestSupport.start(state, "Pulse", "hex-1", "dimmer", programmerBand = true)
        val cue = EffectTestSupport.start(state, "SineWave", "hex-1", "uv", priority = 1_000_001, cueId = 9)
        val manual = EffectTestSupport.start(state, "SineWave", "hex-1", "strobe")

        val answer = release("fixture", "hex-1")
        assertEquals(1, answer.effects)
        val live = engine.getActiveEffects().map { it.id }.toSet()
        assertTrue(local.id !in live)
        assertTrue(cue.id in live, "a cue's effect is the cue's")
        assertTrue(manual.id in live, "a priority-0 manual effect is not the programmer's")
    }

    @Test
    fun `a group effect on a member is left running and named, and goes with its group`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 20)
        LocateTestSupport.seedGroup(state, projectId, "front", "hex-1", "hex-2")
        val groupEffect = EffectTestSupport.start(state, "Pulse", "front", "dimmer", group = true, programmerBand = true)

        val member = release("fixture", "hex-1")
        assertEquals(0, member.effects)
        val partial = member.partial.single()
        assertEquals(groupEffect.id, partial.effectId)
        assertEquals("front", partial.targetKey)
        assertTrue(partial.isGroupTarget)
        assertNotNull(engine.getEffect(groupEffect.id), "left running: there is no stopping it on one head")

        val group = release("group", "front")
        assertEquals(1, group.effects)
        assertTrue(group.partial.isEmpty())
        assertNull(engine.getEffect(groupEffect.id))
    }

    @Test
    fun `the release fades at the requested time, in one publish`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        set("hex-1", "dimmer", CueAssignmentResolver.PropertyValue.Slider(200u))
        set("hex-1", "uv", CueAssignmentResolver.PropertyValue.Slider(90u))
        val before = controller().changesTo(1).size

        release("fixture", "hex-1", fadeMs = 1_500)
        val dimmer = controller().changesTo(1).drop(before)
        assertEquals(1, dimmer.size, "one ramp per channel, not one per owner")
        assertEquals(0u.toUByte(), dimmer.single().newValue)
        assertEquals(1_500L, dimmer.single().fadeMs)
        assertEquals(1_500L, controller().changesTo(7).last().fadeMs)
    }

    @Test
    fun `a key a stopped local effect was painting fades with the rest`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        set("hex-1", "dimmer", CueAssignmentResolver.PropertyValue.Slider(200u))
        EffectTestSupport.start(state, "Pulse", "hex-1", "dimmer", programmerBand = true)
        val before = controller().changesTo(1).size

        release("fixture", "hex-1", fadeMs = 1_500)
        // Stopped first, the effect no longer covers the dimmer when the values publish, so the
        // release reaches it as a ramp. Released first, the publish skipped the covered key and the
        // effect's removal then reset it with no fade.
        val changes = controller().changesTo(1).drop(before)
        assertTrue(
            changes.any { it.newValue == 0u.toUByte() && it.fadeMs == 1_500L },
            "the dimmer under the stopped effect fades at the release's time, got $changes",
        )
    }

    @Test
    fun `locate's bookkeeping is pruned`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.toggleLocate(jsonClient(), "fixture", "hex-1")
        assertTrue(state.show.locateManager.activeTargets.value.isNotEmpty())

        release("fixture", "hex-1")
        assertTrue(state.show.locateManager.activeTargets.value.isEmpty())
    }

    @Test
    fun `the answer goes to the asking socket`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        set("hex-1", "dimmer", CueAssignmentResolver.PropertyValue.Slider(200u))
        val client = createWsClient()
        client.webSocket("/api") {
            sendSerialized<InMessage>(ProgrammerClearTargetInMessage("fixture", "hex-1", requestId = "x"))
            val reply = awaitOfType<ProgrammerTargetClearedOutMessage>()
            assertEquals("x", reply.requestId)
            assertEquals(1, reply.values)
        }
    }
}
