package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.models.LayerSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.HttpResponse
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.plugins.UpdateChannelInMessage
import uk.me.cormack.lighting7.plugins.handleUpdateChannel
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.programmerChannel
import uk.me.cormack.lighting7.testsupport.programmerValue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Programmer ownership collisions: releasing one subsystem's entries must not destroy
 * another subsystem's entries on the same property. These are the concrete failures the
 * owner-slot [uk.me.cormack.lighting7.fx.ProgrammerStore] exists to prevent — before it,
 * the store was a flat per-channel map and a Locate release wiped whatever busking or a
 * preset toggle had asserted underneath.
 *
 * Locate-vs-locate overlap is deliberately *not* re-pinned here — every locate shares one
 * owner and `LocateManager`'s re-assert loop arbitrates; `LocateRoutesTest` covers it.
 */
class ProgrammerOwnershipCollisionTest : RouteIntegrationTest() {

    // Hex at channel 1: dimmer=1, R/G/B=2/3/4, amber=5, white=6, uv=7, strobe=8.

    /** Busk a raw channel the way the web UI does — through the `updateChannel` shim. */
    private fun busk(universe: Int, channel: Int, level: UByte) =
        handleUpdateChannel(state, UpdateChannelInMessage(universe, channel, level, fadeTime = 0))

    @Test
    fun `releasing a locate restores the busked level instead of wiping it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-busk", startChannel = 1)
        val controller = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController

        // Busk the dimmer to 102 — the shim lifts channel 1 to a WEB entry on `dimmer`
        // and publishes it to the wire.
        busk(0, 1, 102u)
        assertEquals(102u.toUByte(), controller.getEffectiveValue(1))

        assertTrue(toggle(client, "fixture", "hex-busk").body<ToggleLocateResponse>().active)
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 1), "locate on top of the busked value")
        assertEquals(255u.toUByte(), controller.getEffectiveValue(1))

        assertFalse(toggle(client, "fixture", "hex-busk").body<ToggleLocateResponse>().active)
        assertEquals(102u.toUByte(), programmerChannel(state, 0, 1), "busked value survives the locate release")
        assertEquals(102u.toUByte(), controller.getEffectiveValue(1), "and is republished to the wire")
        assertNull(programmerChannel(state, 0, 2), "properties only locate touched are fully released")
    }

    @Test
    fun `busking while located survives the locate release`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-live", startChannel = 1)

        assertTrue(toggle(client, "fixture", "hex-live").body<ToggleLocateResponse>().active)

        // Operator busks the dimmer down while the fixture is located: most recent write wins.
        busk(0, 1, 60u)
        assertEquals(60u.toUByte(), programmerChannel(state, 0, 1))

        assertFalse(toggle(client, "fixture", "hex-live").body<ToggleLocateResponse>().active)
        assertEquals(60u.toUByte(), programmerChannel(state, 0, 1), "busk-during-locate survives the release")
    }

    @Test
    fun `releasing a locate reveals a programmer layer instead of wiping it`() = testApplication {
        // The layer-stack heir to three tests that used to drive `togglePresetOnTargets`. Two of
        // them are gone rather than ported, because their subject was the per-preset owner: a
        // layer stack cooks every layer into **one** slot per key, so "two presets sharing a
        // property release independently" has no equivalent, and neither does a stale per-preset
        // release stranding entries — there is no per-layer bookkeeping left to go stale.
        //
        // What does survive, and matters more, is this: LOCATE writes above LAYERS, and releasing
        // it must fall back to the layer rather than to baseline. That is the same bug the preset
        // version guarded, one owner along.
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-layer", startChannel = 1)
        val controller = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController

        val look = client.post("/api/rest/project/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "Warm",
                    rows = listOf(
                        uk.me.cormack.lighting7.models.LookRowDto(
                            "fixture", "hex-layer", "dimmer", "120",
                        ),
                    ),
                )
            )
        }.body<LookDetails>()

        val (layer, _) = state.show.programmerLayerStack.add(
            source = LayerSource.look(look.id, java.util.UUID.fromString(look.uuid), look.name),
            targets = listOf(uk.me.cormack.lighting7.models.CueTargetDto("fixture", "hex-layer")),
        )
        assertEquals(120u.toUByte(), programmerChannel(state, 0, 1), "the layer asserted the dimmer")

        assertTrue(toggle(client, "fixture", "hex-layer").body<ToggleLocateResponse>().active)
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 1), "locate on top of the layer")

        assertFalse(toggle(client, "fixture", "hex-layer").body<ToggleLocateResponse>().active)
        assertEquals(
            120u.toUByte(), programmerChannel(state, 0, 1),
            "the layer's contribution survives the locate release",
        )
        assertEquals(120u.toUByte(), controller.getEffectiveValue(1))

        // And removing the layer really releases it, rather than leaving it stranded.
        state.show.programmerLayerStack.remove(layer.layerId)
        assertNull(programmerChannel(state, 0, 1))
        assertEquals(0u.toUByte(), controller.getEffectiveValue(1), "channel cascades to baseline")
    }

    @Test
    fun `group programmer write and clear pair on one owner and respect survivors`() = testApplication {
        mountTestApp(state)
        seedHex("hex-g1", startChannel = 1)
        seedHex("hex-g2", startChannel = 20)
        LocateTestSupport.seedGroup(state, projectId, "programmer-band", "hex-g1", "hex-g2")
        val engine = state.show.fxEngine
        val group = state.show.fixtures.untypedGroup("programmer-band")
        // Any owner that is not WEB; this test is about the group write/clear pairing, not the
        // owner. Was a per-preset owner before the Look-layer stack retired those.
        val owner = ProgrammerOwner.INCLUDE

        busk(0, 1, 40u)
        engine.writeProgrammerGroupProperty(owner, group, "dimmer", CueAssignmentResolver.PropertyValue.Slider(90u))
        assertEquals(90u.toUByte(), programmerChannel(state, 0, 1), "group write covers member 1")
        assertEquals(90u.toUByte(), programmerChannel(state, 0, 20), "group write covers member 2")
        assertEquals(
            "programmer-band",
            state.show.programmerStore.get("hex-g2", "dimmer")?.sourceGroup,
            "group-shaped writes are tagged with their source group",
        )

        engine.clearProgrammerGroupProperty(owner, group, "dimmer")
        assertEquals(40u.toUByte(), programmerChannel(state, 0, 1), "busked value survives the group clear")
        assertNull(programmerChannel(state, 0, 20), "member without other owners fully released")
    }

    // `stale preset release sweeps its stranded entries instead of ghosting` lived here. Its
    // subject was the per-preset owner's recorded-write bookkeeping, which could strand an entry
    // when a fixture was rekeyed under it. A layer keeps no such record: every recook rebuilds the
    // whole LAYERS contribution from the stack and `putLayerSlots` releases any key the new set
    // does not name, so a rekey cannot leave a ghost behind. The locate equivalent below still
    // matters, because LocateManager *does* keep per-target records.

    @Test
    fun `stale locate records are swept once nothing is located`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-l1", startChannel = 1)
        seedHex("hex-l2", startChannel = 20)
        LocateTestSupport.seedGroup(state, projectId, "sweep-band", "hex-l1", "hex-l2")
        val store = state.show.programmerStore

        // Fixture locate under a group locate, then rekey the member out from under both.
        assertTrue(toggle(client, "fixture", "hex-l1").body<ToggleLocateResponse>().active)
        assertTrue(toggle(client, "group", "sweep-band").body<ToggleLocateResponse>().active)
        renamePatch("hex-l1", "hex-l1-renamed")

        // Releasing the group skips the stale member's per-property clears and drops its
        // stale fixture-locate entry on the re-assert pass — leaving nothing located, so
        // the sweep must release the member's stranded LOCATE entries.
        assertFalse(toggle(client, "group", "sweep-band").body<ToggleLocateResponse>().active)
        assertTrue(state.show.locateManager.activeTargets.value.isEmpty(), "stale fixture locate dropped")
        assertNull(programmerValue(state, "hex-l1", "dimmer"), "stranded LOCATE entry swept")
        assertEquals(0, store.size, "nothing located leaves no LOCATE entries anywhere")
        assertNull(programmerChannel(state, 0, 20), "released group member cleared normally")
    }

    private fun renamePatch(fromKey: String, toKey: String) {
        transaction(state.database) {
            DaoFixturePatch.find { DaoFixturePatches.key eq fromKey }.first().key = toKey
        }
        LocateTestSupport.reloadFixtures(state, projectId)
    }

    private suspend fun toggle(client: HttpClient, type: String, key: String): HttpResponse =
        LocateTestSupport.toggleLocate(client, type, key)

    private fun seedHex(key: String, startChannel: Int) =
        LocateTestSupport.seedHex(state, projectId, key, startChannel)
}
