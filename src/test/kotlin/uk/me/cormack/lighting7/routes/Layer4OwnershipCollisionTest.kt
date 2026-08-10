package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.DirectWriteOwner
import uk.me.cormack.lighting7.fx.Layer3Resolver
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.FxPresetPropertyAssignmentDto
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Layer-4 ownership collisions: releasing one subsystem's direct writes must not destroy
 * another subsystem's writes on the same channels. These are the concrete failures the
 * owner-tagged [uk.me.cormack.lighting7.fx.DirectWriteStore] exists to prevent — before it,
 * the store was a flat per-channel map and a Locate release wiped whatever busking or a
 * preset toggle had asserted underneath.
 *
 * Locate-vs-locate overlap is deliberately *not* re-pinned here — every locate shares one
 * owner and `LocateManager`'s re-assert loop arbitrates; `LocateRoutesTest` covers it.
 */
class Layer4OwnershipCollisionTest : RouteIntegrationTest() {

    // Hex at channel 1: dimmer=1, R/G/B=2/3/4, amber=5, white=6, uv=7, strobe=8.

    @Test
    fun `releasing a locate restores the busked level instead of wiping it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-busk", startChannel = 1)
        val controller = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController
        val store = state.show.directWriteStore

        // Busk the dimmer to 102 — controller write + BUSKING store put, as the
        // `updateChannel` WebSocket handler does.
        controller.setValue(1, 102u, 0)
        store.put(DirectWriteOwner.BUSKING, 0, 1, 102u)

        assertTrue(toggle(client, "fixture", "hex-busk").body<ToggleLocateResponse>().active)
        assertEquals(255u.toUByte(), store.get(0, 1), "locate on top of the busked value")
        assertEquals(255u.toUByte(), controller.getEffectiveValue(1))

        assertFalse(toggle(client, "fixture", "hex-busk").body<ToggleLocateResponse>().active)
        assertEquals(102u.toUByte(), store.get(0, 1), "busked value survives the locate release")
        assertEquals(102u.toUByte(), controller.getEffectiveValue(1), "and is republished to the wire")
        assertNull(store.get(0, 2), "channels only locate touched are fully released")
    }

    @Test
    fun `busking while located survives the locate release`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-live", startChannel = 1)
        val controller = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController
        val store = state.show.directWriteStore

        assertTrue(toggle(client, "fixture", "hex-live").body<ToggleLocateResponse>().active)

        // Operator busks the dimmer down while the fixture is located: most recent write wins.
        controller.setValue(1, 60u, 0)
        store.put(DirectWriteOwner.BUSKING, 0, 1, 60u)
        assertEquals(60u.toUByte(), store.get(0, 1))

        assertFalse(toggle(client, "fixture", "hex-live").body<ToggleLocateResponse>().active)
        assertEquals(60u.toUByte(), store.get(0, 1), "busk-during-locate survives the release")
    }

    @Test
    fun `releasing a locate keeps a preset's write and its toggle-off still works`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-preset", startChannel = 1)
        val controller = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController
        val store = state.show.directWriteStore

        val presetId = 7101
        val assignments = listOf(FxPresetPropertyAssignmentDto(propertyName = "dimmer", value = "120"))
        val targets = listOf(TogglePresetTarget(type = "fixture", key = "hex-preset"))

        val applied = togglePresetOnTargets(
            state, presetId,
            presetEffects = emptyList(),
            presetPropertyAssignments = assignments,
            targets = targets,
            beatDivisionOverride = null,
        )
        assertEquals("applied", applied.action)
        assertEquals(120u.toUByte(), store.get(0, 1), "preset asserted the dimmer")

        assertTrue(toggle(client, "fixture", "hex-preset").body<ToggleLocateResponse>().active)
        assertEquals(255u.toUByte(), store.get(0, 1), "locate on top of the preset write")

        assertFalse(toggle(client, "fixture", "hex-preset").body<ToggleLocateResponse>().active)
        assertEquals(120u.toUByte(), store.get(0, 1), "preset write survives the locate release")
        assertEquals(120u.toUByte(), controller.getEffectiveValue(1))

        // The bookkeeping still marks the preset active, and — the second half of the old
        // bug — its toggle-off must actually release the write rather than no-op.
        val removed = togglePresetOnTargets(
            state, presetId,
            presetEffects = emptyList(),
            presetPropertyAssignments = assignments,
            targets = targets,
            beatDivisionOverride = null,
        )
        assertEquals("removed", removed.action)
        assertNull(store.get(0, 1), "preset toggle-off releases its write")
        assertEquals(0u.toUByte(), controller.getEffectiveValue(1), "channel cascades to baseline")
    }

    @Test
    fun `two presets sharing a channel release independently`() = testApplication {
        mountTestApp(state)
        seedHex("hex-two", startChannel = 1)
        val store = state.show.directWriteStore

        val targets = listOf(TogglePresetTarget(type = "fixture", key = "hex-two"))
        val dimA = listOf(FxPresetPropertyAssignmentDto(propertyName = "dimmer", value = "100"))
        val dimB = listOf(FxPresetPropertyAssignmentDto(propertyName = "dimmer", value = "200"))

        togglePresetOnTargets(state, 8001, emptyList(), dimA, targets, null)
        togglePresetOnTargets(state, 8002, emptyList(), dimB, targets, null)
        assertEquals(200u.toUByte(), store.get(0, 1), "most recent preset wins")

        val removedB = togglePresetOnTargets(state, 8002, emptyList(), dimB, targets, null)
        assertEquals("removed", removedB.action)
        assertEquals(100u.toUByte(), store.get(0, 1), "first preset's write survives the second's release")

        togglePresetOnTargets(state, 8001, emptyList(), dimA, targets, null)
        assertNull(store.get(0, 1))
    }

    @Test
    fun `group Layer-4 write and clear pair on one owner and respect survivors`() = testApplication {
        mountTestApp(state)
        seedHex("hex-g1", startChannel = 1)
        seedHex("hex-g2", startChannel = 20)
        LocateTestSupport.seedGroup(state, projectId, "layer4-band", "hex-g1", "hex-g2")
        val store = state.show.directWriteStore
        val engine = state.show.fxEngine
        val group = state.show.fixtures.untypedGroup("layer4-band")
        val owner = DirectWriteOwner.preset(9001)

        store.put(DirectWriteOwner.BUSKING, 0, 1, 40u)
        engine.writeLayer4GroupProperty(owner, group, "dimmer", Layer3Resolver.PropertyValue.Slider(90u))
        assertEquals(90u.toUByte(), store.get(0, 1), "group write covers member 1")
        assertEquals(90u.toUByte(), store.get(0, 20), "group write covers member 2")

        engine.clearLayer4GroupProperty(owner, group, "dimmer")
        assertEquals(40u.toUByte(), store.get(0, 1), "busked value survives the group clear")
        assertNull(store.get(0, 20), "member without other owners fully released")
    }

    @Test
    fun `stale preset release sweeps its stranded entries instead of ghosting`() = testApplication {
        mountTestApp(state)
        seedHex("hex-ghost", startChannel = 1)
        val store = state.show.directWriteStore

        val presetId = 7102
        val assignments = listOf(FxPresetPropertyAssignmentDto(propertyName = "dimmer", value = "120"))
        val targets = listOf(TogglePresetTarget(type = "fixture", key = "hex-ghost"))
        togglePresetOnTargets(state, presetId, emptyList(), assignments, targets, null)
        assertEquals(120u.toUByte(), store.get(0, 1))

        // Rekey the fixture out from under the recorded write, as the patch editor would.
        renamePatch("hex-ghost", "hex-ghost-renamed")

        // Toggle-off can no longer resolve the recorded key; the owner sweep must release
        // the stranded entry rather than leaving a ghost that resurfaces under later owners.
        val removed = togglePresetOnTargets(state, presetId, emptyList(), assignments, targets, null)
        assertEquals("removed", removed.action)
        assertNull(store.get(0, 1), "stranded preset entry swept")

        store.put(DirectWriteOwner.BUSKING, 0, 1, 60u)
        store.clear(DirectWriteOwner.BUSKING, 0, 1)
        assertNull(store.get(0, 1), "no ghost value resurfaces once busking clears")
    }

    @Test
    fun `stale locate records are swept once nothing is located`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-l1", startChannel = 1)
        seedHex("hex-l2", startChannel = 20)
        LocateTestSupport.seedGroup(state, projectId, "sweep-band", "hex-l1", "hex-l2")
        val store = state.show.directWriteStore

        // Fixture locate under a group locate, then rekey the member out from under both.
        assertTrue(toggle(client, "fixture", "hex-l1").body<ToggleLocateResponse>().active)
        assertTrue(toggle(client, "group", "sweep-band").body<ToggleLocateResponse>().active)
        renamePatch("hex-l1", "hex-l1-renamed")

        // Releasing the group skips the stale member's per-channel clears and drops its
        // stale fixture-locate entry on the re-assert pass — leaving nothing located, so
        // the sweep must release the member's stranded LOCATE entries.
        assertFalse(toggle(client, "group", "sweep-band").body<ToggleLocateResponse>().active)
        assertTrue(state.show.locateManager.activeTargets.value.isEmpty(), "stale fixture locate dropped")
        assertNull(store.get(0, 1), "stranded LOCATE entry swept")
        assertNull(store.get(0, 20), "released group member cleared normally")
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
