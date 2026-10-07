package uk.me.cormack.lighting7.fx

import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.plugins.UpdateChannelInMessage
import uk.me.cormack.lighting7.plugins.handleUpdateChannel
import uk.me.cormack.lighting7.testsupport.EffectTestSupport
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W4 (fixture-fx-sheets plan): provenance names the programmer as a key's winner only where the
 * engine holds the effect on that key back — both read [EffectSuppression] over one snapshot. A raw
 * pan dragged on the Channels tab lands in the sideband, which suppresses nothing, so under a running
 * Circle it is the Circle on stage and the key reads EFFECT; a `position` entry does hold the Circle
 * back, and reads PROGRAMMER as it always has.
 *
 * The spot is `fusion-100-spot-mkii-5ch` at channel 1: pan 1, tilt 2.
 */
class ProvenanceSidebandTest : RouteIntegrationTest() {

    private fun seedSpot() =
        LocateTestSupport.seedFixture(state, projectId, "fusion-100-spot-mkii-5ch", "spot-1", 1)

    private fun provenance(key: CueAssignmentResolver.Key) =
        state.show.fxEngine.provenance.compute()
            .single { it.targetKey == key.targetKey && it.propertyName == key.propertyName }

    private fun panKey() = assertNotNull(state.show.fxEngine.cascade.resolveChannelCoveringKey(0, 1))

    @Test
    fun `a Channels-tab pan under a priority-0 Circle reads EFFECT`() = testApplication {
        mountTestApp(state)
        seedSpot()
        // This head declares pan as a property, so the write is lifted to a `pan` entry — which
        // holds back an effect on `pan`, not the Circle on `position` painting the same channel.
        handleUpdateChannel(state, UpdateChannelInMessage(0, 1, 200u, fadeTime = 0))
        assertEquals(CueAssignmentResolver.Key.fixture("spot-1", "pan"), panKey())
        assertTrue(state.show.programmerStore.slotsFor("spot-1", "pan").isNotEmpty())
        val circle = EffectTestSupport.start(state, "Circle", "spot-1", "position")

        val entry = provenance(panKey())
        assertEquals(ProvenanceSource.EFFECT, entry.source)
        assertEquals(circle.id, entry.effectId)

        // And the stack read agrees: the Circle is on the `pan` row's stack, painting, not held back.
        val stack = state.show.fxEngine.provenance.keyStack("spot-1", "pan")!!
        val effect = stack.layers.single { it.kind == KeyStackLayer.Kind.EFFECT }
        assertEquals(circle.id, effect.effect?.id)
        assertEquals(false, effect.heldBack)
        assertTrue(effect.onStage)
    }

    @Test
    fun `a sideband pan under a priority-0 Circle reads EFFECT`() = testApplication {
        mountTestApp(state)
        seedSpot()
        // The other arm: a raw byte held in the sideband — a head with no pan property keeps a
        // Channels-tab write there, and an unpark hands one down on any head. Provenance files it
        // under the channel's covering key, and a sideband slot suppresses nothing.
        state.show.fxEngine.programmer.writeChannel(ProgrammerOwner.UNPARK, 0, 1, 200u, coveringKey = panKey())
        assertTrue(state.show.programmerStore.channelEntries().isNotEmpty())
        assertEquals(ProvenanceSource.PROGRAMMER, provenance(panKey()).source, "nothing running: the programmer's")

        val circle = EffectTestSupport.start(state, "Circle", "spot-1", "position")
        val entry = provenance(panKey())
        assertEquals(ProvenanceSource.EFFECT, entry.source)
        assertEquals(circle.id, entry.effectId)
    }

    @Test
    fun `an effect on one emitter leaves a colour entry the programmer's`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        ProgrammerHandler.setTyped(
            state, TargetRef.Fixture("hex-1"), "rgbColour",
            CueAssignmentResolver.PropertyValue.Colour(ExtendedColour(java.awt.Color(200, 40, 0))), fadeMs = 0,
        )
        EffectTestSupport.start(state, "SineWave", "hex-1", "uv")
        assertEquals(
            ProvenanceSource.PROGRAMMER,
            provenance(CueAssignmentResolver.Key.fixture("hex-1", "rgbColour")).source,
            "an effect painting some of a key's channels does not take the key",
        )
    }

    @Test
    fun `the same pan with nothing running is the programmer's`() = testApplication {
        mountTestApp(state)
        seedSpot()
        handleUpdateChannel(state, UpdateChannelInMessage(0, 1, 200u, fadeTime = 0))
        assertEquals(ProvenanceSource.PROGRAMMER, provenance(panKey()).source)
    }

    @Test
    fun `a position entry holds the Circle back, and reads PROGRAMMER`() = testApplication {
        mountTestApp(state)
        seedSpot()
        ProgrammerHandler.setTyped(
            state, TargetRef.Fixture("spot-1"), "position",
            CueAssignmentResolver.PropertyValue.Position(200u, 90u), fadeMs = 0,
        )
        EffectTestSupport.start(state, "Circle", "spot-1", "position")
        assertEquals(
            ProvenanceSource.PROGRAMMER,
            provenance(CueAssignmentResolver.Key.fixture("spot-1", "position")).source,
        )
    }

    @Test
    fun `a band Circle over a position entry reads EFFECT, as before`() = testApplication {
        mountTestApp(state)
        seedSpot()
        ProgrammerHandler.setTyped(
            state, TargetRef.Fixture("spot-1"), "position",
            CueAssignmentResolver.PropertyValue.Position(200u, 90u), fadeMs = 0,
        )
        val circle = EffectTestSupport.start(state, "Circle", "spot-1", "position", programmerBand = true)
        val entry = provenance(CueAssignmentResolver.Key.fixture("spot-1", "position"))
        assertEquals(ProvenanceSource.EFFECT, entry.source)
        assertEquals(circle.id, entry.effectId)
    }
}
