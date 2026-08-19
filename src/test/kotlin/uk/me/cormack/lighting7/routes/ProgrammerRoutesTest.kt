package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.models.FxPresetPropertyAssignmentDto
import uk.me.cormack.lighting7.plugins.UpdateChannelInMessage
import uk.me.cormack.lighting7.plugins.handleUpdateChannel
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.programmerValue
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `updateChannel` → programmer compatibility shim, and the clear-all escape hatch
 * (`POST /api/rest/programmer/clear-all`) including the toggle-bookkeeping reset that
 * keeps locate and preset toggles consistent with the swept store.
 */
class ProgrammerRoutesTest : RouteIntegrationTest() {

    // Hex at channel 1: dimmer=1, R/G/B=2/3/4, amber=5, white=6, uv=7, strobe=8.

    private fun busk(universe: Int, channel: Int, level: UByte) =
        handleUpdateChannel(state, UpdateChannelInMessage(universe, channel, level, fadeTime = 0))

    @Test
    fun `shim lifts a slider channel to a property entry`() = testApplication {
        mountTestApp(state)
        seedHex("hex-shim", startChannel = 1)

        busk(0, 1, 200u)

        val slot = state.show.programmerStore.get("hex-shim", "dimmer")!!
        assertEquals(ProgrammerOwner.WEB, slot.owner)
        assertTrue(slot.touched)
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(200u), slot.value.resolved)
    }

    @Test
    fun `shim lifts a colour sub-channel to the whole colour property`() = testApplication {
        mountTestApp(state)
        seedHex("hex-col", startChannel = 1)

        // Red to 200: siblings (green/blue, currently 0) freeze into the entry.
        busk(0, 2, 200u)
        val first = programmerValue(state, "hex-col", "rgbColour") as CueAssignmentResolver.PropertyValue.Colour
        assertEquals(200, first.value.color.red)
        assertEquals(0, first.value.color.green)
        assertEquals(0, first.value.color.blue)

        // Green to 100: composes with the existing entry rather than resetting red.
        busk(0, 3, 100u)
        val second = programmerValue(state, "hex-col", "rgbColour") as CueAssignmentResolver.PropertyValue.Colour
        assertEquals(200, second.value.color.red, "earlier red drag survives")
        assertEquals(100, second.value.color.green)
    }

    @Test
    fun `shim routes an unbacked channel to the sideband and the wire`() = testApplication {
        mountTestApp(state)
        seedHex("hex-side", startChannel = 1)

        busk(0, 100, 90u)

        assertEquals(90u.toUByte(), state.show.programmerStore.getChannel(0, 100))
        val controller = state.show.fixtures.controllerOrNull(uk.me.cormack.lighting7.dmx.Universe(0, 0))!!
        assertEquals(90u.toUByte(), controller.currentValues[100])
    }

    @Test
    fun `clear-all sweeps the store and resets locate and preset bookkeeping`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-clr", startChannel = 1)

        // One of everything: a busked value, an active locate, an applied preset.
        busk(0, 1, 120u)
        assertTrue(
            LocateTestSupport.toggleLocate(client, "fixture", "hex-clr")
                .body<ToggleLocateResponse>().active,
        )
        val presetId = 7301
        val assignments = listOf(FxPresetPropertyAssignmentDto(propertyName = "strobe", value = "0"))
        val targets = listOf(TogglePresetTarget(type = "fixture", key = "hex-clr"))
        togglePresetOnTargets(state, presetId, emptyList(), assignments, targets, null)

        assertTrue(state.show.programmerStore.size > 0)

        val response: ProgrammerClearAllResponse =
            client.post("/api/rest/programmer/clear-all").body()
        assertTrue(response.cleared > 0)

        assertEquals(0, state.show.programmerStore.size, "every property entry swept")
        assertEquals(0, state.show.programmerStore.channelCount, "sideband swept")
        assertTrue(
            state.show.locateManager.activeTargets.value.isEmpty(),
            "locate bookkeeping reset — no phantom located targets",
        )
        assertNull(programmerValue(state, "hex-clr", "dimmer"))

        // The preset's bookkeeping was reset too: the next toggle applies cleanly rather
        // than believing it is still active.
        val reapplied = togglePresetOnTargets(state, presetId, emptyList(), assignments, targets, null)
        assertEquals("applied", reapplied.action)
    }

    @Test
    fun `programmerOwned stamps the priority band and clear-all sweeps it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-band", startChannel = 1)

        val banded: AddEffectResponse = client.post("/api/rest/fx/add") {
            contentType(ContentType.Application.Json)
            setBody(
                AddEffectRequest(
                    effectType = "SineWave",
                    fixtureKey = "hex-band",
                    propertyName = "dimmer",
                    programmerOwned = true,
                )
            )
        }.body()

        val plain: AddEffectResponse = client.post("/api/rest/fx/add") {
            contentType(ContentType.Application.Json)
            setBody(
                AddEffectRequest(
                    effectType = "SineWave",
                    fixtureKey = "hex-band",
                    propertyName = "strobe",
                )
            )
        }.body()

        val bandInstance = state.show.fxEngine.getEffect(banded.effectId)!!
        assertTrue(
            FxEngine.isProgrammerFxPriority(bandInstance.priority),
            "programmerOwned lands in the reserved band, above every cue-derived priority",
        )
        assertEquals(0, state.show.fxEngine.getEffect(plain.effectId)!!.priority)

        val response: ProgrammerClearAllResponse =
            client.post("/api/rest/programmer/clear-all").body()
        assertEquals(1, response.effectsCleared, "only the band effect is swept")

        val surviving = state.show.fxEngine.getActiveEffects().map { it.id }.toSet()
        assertTrue(banded.effectId !in surviving, "programmer FX go with the programmer values")
        assertTrue(plain.effectId in surviving, "plain manual effects are not the programmer's")
    }

    @Test
    fun `clearEntry prunes locate bookkeeping for released LOCATE slots`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-prune", startChannel = 1)

        assertTrue(
            LocateTestSupport.toggleLocate(client, "fixture", "hex-prune")
                .body<ToggleLocateResponse>().active,
        )
        val locateWrites = state.show.programmerStore.entries()
            .filter { it.fixtureKey == "hex-prune" }
            .map { it.propertyName }
        assertTrue(locateWrites.size > 1, "locate asserts several properties")

        // Clearing one property keeps the fixture located — other writes remain.
        uk.me.cormack.lighting7.plugins.ProgrammerHandler.clearEntry(
            state, uk.me.cormack.lighting7.models.TargetRef.Fixture("hex-prune"),
            locateWrites.first(), fadeMs = 0,
        )
        assertTrue(
            state.show.locateManager.activeTargets.value.isNotEmpty(),
            "a partial clear must not un-locate the fixture",
        )

        // Clearing the rest releases the last LOCATE write and drops the locate state.
        for (property in locateWrites.drop(1)) {
            uk.me.cormack.lighting7.plugins.ProgrammerHandler.clearEntry(
                state, uk.me.cormack.lighting7.models.TargetRef.Fixture("hex-prune"),
                property, fadeMs = 0,
            )
        }
        assertTrue(
            state.show.locateManager.activeTargets.value.isEmpty(),
            "clearing every LOCATE write must clear the locate bookkeeping too",
        )
        assertEquals(0, state.show.programmerStore.size)
    }

    private fun seedHex(key: String, startChannel: Int) =
        LocateTestSupport.seedHex(state, projectId, key, startChannel)
}
