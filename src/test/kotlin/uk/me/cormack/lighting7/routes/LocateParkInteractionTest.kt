package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Test
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.BeatDivision
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxTargetRef
import uk.me.cormack.lighting7.fx.FxTiming
import uk.me.cormack.lighting7.fx.SliderTarget
import uk.me.cormack.lighting7.fx.effects.SineWave
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.programmerChannel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locate versus park: park is the top output layer, so a Locate toggle must not move a parked
 * channel on the wire — neither when locate is asserted nor when it is released — while the
 * fixture's unparked channels still take their locate values.
 *
 * Two consequences pinned here beyond the output itself. A fixture whose every locatable
 * channel is parked is a no-op locate: inactive, `parkMasked` so the UI can say why, and no
 * programmer entries land. And since the programmer redesign, "locate wins" is
 * non-destructive: effects covering located properties are *suppressed* while the locate
 * holds and resume when it releases — nothing is removed.
 */
class LocateParkInteractionTest : RouteIntegrationTest() {

    @Test
    fun `locate leaves parked channels at their parked value`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-park", startChannel = 1)

        // Hex at channel 1: dimmer=1, R/G/B=2/3/4, amber=5, white=6, uv=7, strobe=8.
        // Park the dimmer (fully parks the `dimmer` property) and red (partial park of `colour`).
        runBlocking {
            state.show.parkManager.park(0, 1, 40u)
            state.show.parkManager.park(0, 2, 11u)
        }

        val controller = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController

        val on: ToggleLocateResponse = toggle(client, "fixture", "hex-park").body()
        assertTrue(on.active)

        assertEquals(40u.toUByte(), controller.getEffectiveValue(1), "parked dimmer holds its parked value")
        assertEquals(11u.toUByte(), controller.getEffectiveValue(2), "parked red holds its parked value")
        assertEquals(255u.toUByte(), controller.getEffectiveValue(3), "unparked green takes the locate value")
        // A store entry is the proof the shutter was written: BandedStrobeChannel's open level
        // is 0u, which an untouched channel also reads as, so the channel value alone says nothing.
        assertEquals(0u.toUByte(), programmerChannel(state, 0, 8), "shutter opened for locate")
        assertEquals(0u.toUByte(), controller.getEffectiveValue(8))

        val off: ToggleLocateResponse = toggle(client, "fixture", "hex-park").body()
        assertFalse(off.active)

        assertEquals(40u.toUByte(), controller.getEffectiveValue(1), "release does not disturb park either")
        assertEquals(11u.toUByte(), controller.getEffectiveValue(2))
    }

    @Test
    fun `unparking hands off the parked value over a locate value in the programmer`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-unpark", startChannel = 1)

        // Red only: `colour` stays publishable (green/blue unparked), so locate *does* write
        // 255 into the programmer for the parked red channel — the case the hand-off has to
        // overwrite.
        runBlocking { state.show.parkManager.park(0, 2, 11u) }
        val controller = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController

        assertTrue(toggle(client, "fixture", "hex-unpark").body<ToggleLocateResponse>().active)
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 2), "locate wrote under the park")
        assertEquals(11u.toUByte(), controller.getEffectiveValue(2), "park still wins on the wire")

        runBlocking { state.show.parkManager.unpark(0, 2) }

        assertEquals(
            11u.toUByte(),
            controller.getEffectiveValue(2),
            "unpark hands off the parked value — red must not snap to the locate's 255",
        )
        assertEquals(
            11u.toUByte(),
            programmerChannel(state, 0, 2),
            "the hand-off sits above the locate value in the programmer, so nothing republishes 255",
        )
    }

    @Test
    fun `a wholly parked fixture is a no-op locate that keeps its effects`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-masked", startChannel = 1)

        // Every channel locate would assert on a hex: dimmer, R/G/B, amber, white, uv, strobe.
        runBlocking { (1..8).forEach { state.show.parkManager.park(0, it, 40u) } }

        val effectId = state.show.fxEngine.addEffect(dimmerEffect("hex-masked"))

        val on: ToggleLocateResponse = toggle(client, "fixture", "hex-masked").body()
        assertFalse(on.active, "nothing locate writes could be seen under park")
        assertEquals(0, on.writeCount)
        assertEquals(0, on.effectsRemoved, "locate never removes effects")
        assertTrue(on.parkMasked, "the response says park is why, not 'nothing to write'")
        assertTrue(
            state.show.fxEngine.getActiveEffects().any { it.id == effectId },
            "the operator's effect survives a park-masked locate",
        )
        assertNull(programmerChannel(state, 0, 1), "no programmer assertion landed")
        assertTrue(
            client.get("/api/rest/locate").body<LocateStateResponse>().targets.isEmpty(),
            "a no-op locate is not registered as active",
        )
    }

    @Test
    fun `a real locate suppresses covering effects instead of removing them`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-partial", startChannel = 1)

        runBlocking { state.show.parkManager.park(0, 1, 40u) }  // dimmer only

        val maskedEffect = state.show.fxEngine.addEffect(dimmerEffect("hex-partial"))
        val liveEffect = state.show.fxEngine.addEffect(sliderEffect("hex-partial", "strobe"))

        val on: ToggleLocateResponse = toggle(client, "fixture", "hex-partial").body()
        assertTrue(on.active, "colour and strobe are unparked, so the locate is real")
        assertFalse(on.parkMasked, "partially parked is not park-masked")
        assertNull(programmerChannel(state, 0, 1), "the parked dimmer property is skipped")
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 3), "green still asserted")

        // "Locate wins" is non-destructive now: both effects survive; the strobe effect is
        // suppressed by the programmer entry for as long as the locate holds.
        val remaining = state.show.fxEngine.getActiveEffects().map { it.id }.toSet()
        assertTrue(maskedEffect in remaining, "park-masked effect untouched")
        assertTrue(liveEffect in remaining, "covering effect suppressed, not removed")
        assertEquals(0, on.effectsRemoved, "nothing is ever removed by locate")

        // Releasing the locate lets the strobe effect resume painting on the next tick.
        assertFalse(toggle(client, "fixture", "hex-partial").body<ToggleLocateResponse>().active)
        assertTrue(liveEffect in state.show.fxEngine.getActiveEffects().map { it.id }.toSet())
    }

    @Test
    fun `parking a located member does not un-locate it when an overlapping group locate is released`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-a", startChannel = 1)
        seedHex("hex-b", startChannel = 20)
        LocateTestSupport.seedGroup(state, projectId, "locate-band", "hex-a", "hex-b")

        assertTrue(toggle(client, "fixture", "hex-a").body<ToggleLocateResponse>().active)
        assertTrue(toggle(client, "group", "locate-band").body<ToggleLocateResponse>().active)

        // Park hex-a *after* it was located, so its re-assert now resolves to zero writes.
        runBlocking { (1..8).forEach { state.show.parkManager.park(0, it, 40u) } }

        assertFalse(toggle(client, "group", "locate-band").body<ToggleLocateResponse>().active)

        assertEquals(
            listOf(LocateTargetDto("fixture", "hex-a")),
            client.get("/api/rest/locate").body<LocateStateResponse>().targets,
            "a write-less re-assert is not staleness — hex-a stays located",
        )
    }

    private fun dimmerEffect(fixtureKey: String): FxInstance = sliderEffect(fixtureKey, "dimmer")

    private fun sliderEffect(fixtureKey: String, propertyName: String): FxInstance = FxInstance(
        effect = SineWave(),
        target = SliderTarget(FxTargetRef.fixture(fixtureKey), propertyName),
        timing = FxTiming(beatDivision = BeatDivision.QUARTER),
    )

    private suspend fun toggle(client: HttpClient, type: String, key: String): HttpResponse =
        LocateTestSupport.toggleLocate(client, type, key)

    private fun seedHex(key: String, startChannel: Int) =
        LocateTestSupport.seedHex(state, projectId, key, startChannel)
}
