package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.packChannelKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ProgrammerStore] — the property-level owner-slot semantics carried over
 * from the old channel-level direct-write store (recency wins, per-owner release reveals
 * the slot below), plus the programmer-specific machinery: the touched flag, the channel
 * sideband and its absorption by property writes, and enumeration.
 */
class ProgrammerStoreTest {

    private val web = ProgrammerOwner.WEB
    private val locate = ProgrammerOwner.LOCATE
    // A third distinct owner. Was `ProgrammerOwner.preset(7)` until the Look-layer stack replaced
    // per-preset owners; INCLUDE serves the same purpose here — these tests are about the slot
    // stack's owner semantics, not about who the owners happen to be.
    private val include = ProgrammerOwner.INCLUDE

    private fun slider(v: Int) = CueAssignmentResolver.PropertyValue.Slider(v.toUByte())

    private fun ProgrammerStore.topSlider(fixtureKey: String, propertyName: String): UByte? =
        (get(fixtureKey, propertyName)?.value?.resolved as? CueAssignmentResolver.PropertyValue.Slider)?.value

    @Test
    fun `put then get returns the stored value`() {
        val store = ProgrammerStore()
        store.put(web, "hex-1", "dimmer", slider(180))
        assertEquals(180u.toUByte(), store.topSlider("hex-1", "dimmer"))
    }

    @Test
    fun `get returns null for absent entry`() {
        val store = ProgrammerStore()
        assertNull(store.get("hex-1", "dimmer"))
    }

    @Test
    fun `different fixtures do not collide`() {
        val store = ProgrammerStore()
        store.put(web, "hex-1", "dimmer", slider(100))
        store.put(web, "hex-2", "dimmer", slider(200))
        assertEquals(100u.toUByte(), store.topSlider("hex-1", "dimmer"))
        assertEquals(200u.toUByte(), store.topSlider("hex-2", "dimmer"))
    }

    @Test
    fun `overwriting put replaces previous value for the same owner`() {
        val store = ProgrammerStore()
        store.put(web, "hex-1", "dimmer", slider(10))
        store.put(web, "hex-1", "dimmer", slider(99))
        assertEquals(99u.toUByte(), store.topSlider("hex-1", "dimmer"))
        assertEquals(1, store.size)
    }

    @Test
    fun `clear removes the entry`() {
        val store = ProgrammerStore()
        store.put(web, "hex-1", "dimmer", slider(42))
        store.clear(web, "hex-1", "dimmer")
        assertNull(store.get("hex-1", "dimmer"))
    }

    @Test
    fun `clearAll removes everything including the sideband`() {
        val store = ProgrammerStore()
        store.put(web, "hex-1", "dimmer", slider(10))
        store.put(locate, "hex-2", "rgbColour", CueAssignmentResolver.PropertyValue.Colour(ExtendedColour.BLACK))
        store.putChannel(ProgrammerOwner.UNPARK, 0, 7, 55u, touched = false)
        store.clearAll()
        assertEquals(0, store.size)
        assertEquals(0, store.channelCount)
        assertTrue(store.isEmpty)
    }

    // --- Ownership stacking ---

    @Test
    fun `most recent owner wins on read`() {
        val store = ProgrammerStore()
        store.put(web, "hex-1", "dimmer", slider(102))
        store.put(locate, "hex-1", "dimmer", slider(255))
        assertEquals(255u.toUByte(), store.topSlider("hex-1", "dimmer"), "locate wrote last")

        store.put(web, "hex-1", "dimmer", slider(40))
        assertEquals(40u.toUByte(), store.topSlider("hex-1", "dimmer"), "a fresh web write moves web to the top")
    }

    @Test
    fun `clearing one owner falls back to the surviving owner's value`() {
        val store = ProgrammerStore()
        store.put(web, "hex-1", "dimmer", slider(102))
        store.put(locate, "hex-1", "dimmer", slider(255))

        store.clear(locate, "hex-1", "dimmer")
        assertEquals(102u.toUByte(), store.topSlider("hex-1", "dimmer"), "busked value survives locate release")
        assertEquals(1, store.size)

        store.clear(web, "hex-1", "dimmer")
        assertNull(store.get("hex-1", "dimmer"))
        assertEquals(0, store.size)
    }

    @Test
    fun `clearing an owner that holds no entry is a no-op`() {
        val store = ProgrammerStore()
        store.put(web, "hex-1", "dimmer", slider(102))
        store.clear(locate, "hex-1", "dimmer")
        assertEquals(102u.toUByte(), store.topSlider("hex-1", "dimmer"))
        store.clear(locate, "hex-1", "shutter")
        assertEquals(1, store.size)
    }

    @Test
    fun `three owners release in any order and fall back by recency`() {
        val store = ProgrammerStore()
        store.put(web, "hex-1", "dimmer", slider(10))
        store.put(include, "hex-1", "dimmer", slider(20))
        store.put(locate, "hex-1", "dimmer", slider(30))
        assertEquals(30u.toUByte(), store.topSlider("hex-1", "dimmer"))

        // Release the middle owner first: top is untouched.
        store.clear(include, "hex-1", "dimmer")
        assertEquals(30u.toUByte(), store.topSlider("hex-1", "dimmer"))

        store.clear(locate, "hex-1", "dimmer")
        assertEquals(10u.toUByte(), store.topSlider("hex-1", "dimmer"), "falls past the released preset to web")
    }

    @Test
    fun `refreshing a buried owner's value does not lose the others`() {
        val store = ProgrammerStore()
        store.put(web, "hex-1", "dimmer", slider(10))
        store.put(locate, "hex-1", "dimmer", slider(255))
        store.put(web, "hex-1", "dimmer", slider(60))   // operator busks while located
        store.clear(web, "hex-1", "dimmer")
        assertEquals(255u.toUByte(), store.topSlider("hex-1", "dimmer"), "locate entry still present underneath")
    }

    @Test
    fun `flash release restores the fader value underneath`() {
        val store = ProgrammerStore()
        store.put(ProgrammerOwner.SURFACE, "hex-1", "dimmer", slider(128))
        store.put(ProgrammerOwner.FLASH, "hex-1", "dimmer", slider(255))
        assertEquals(255u.toUByte(), store.topSlider("hex-1", "dimmer"), "flash on top")
        store.clear(ProgrammerOwner.FLASH, "hex-1", "dimmer")
        assertEquals(128u.toUByte(), store.topSlider("hex-1", "dimmer"), "fader level survives flash release")
    }

    @Test
    fun `valueFor reads a buried owner's entry`() {
        val store = ProgrammerStore()
        store.put(web, "hex-1", "dimmer", slider(102))
        store.put(locate, "hex-1", "dimmer", slider(255))
        assertEquals(
            slider(102),
            (store.valueFor(web, "hex-1", "dimmer") as ProgrammerValue.Hard).resolved,
        )
        assertEquals(
            slider(255),
            (store.valueFor(locate, "hex-1", "dimmer") as ProgrammerValue.Hard).resolved,
        )
        assertNull(store.valueFor(ProgrammerOwner.FLASH, "hex-1", "dimmer"))
    }

    @Test
    fun `clearOwner sweeps only that owner's entries across properties and sideband`() {
        val store = ProgrammerStore()
        store.put(include, "hex-1", "dimmer", slider(100))
        store.put(include, "hex-2", "dimmer", slider(110))
        store.put(web, "hex-1", "dimmer", slider(40))   // stacked above the include slot
        store.put(web, "hex-3", "dimmer", slider(50))
        store.putChannel(include, 0, 9, 70u)

        val swept = store.clearOwner(include)
        assertEquals(3, swept)
        assertEquals(40u.toUByte(), store.topSlider("hex-1", "dimmer"), "web survives the sweep")
        assertNull(store.get("hex-2", "dimmer"), "include-only entry fully released")
        assertEquals(50u.toUByte(), store.topSlider("hex-3", "dimmer"))
        assertNull(store.getChannel(0, 9))

        // No stranded entry can resurface once web clears.
        store.clear(web, "hex-1", "dimmer")
        assertNull(store.get("hex-1", "dimmer"))
        assertEquals(0, store.clearOwner(include), "second sweep finds nothing")
    }

    @Test
    fun `touched is sticky per slot and unpark slots are untouched`() {
        val store = ProgrammerStore()
        store.put(web, "hex-1", "dimmer", slider(80))
        store.putChannel(ProgrammerOwner.UNPARK, 0, 1, 55u, touched = false)

        assertTrue(store.get("hex-1", "dimmer")!!.touched)
        val channelSlot = store.channelEntries().single().slots.single()
        assertFalse(channelSlot.touched, "an unpark hand-down is not an operator edit")
    }

    // --- Channel sideband ---

    @Test
    fun `sideband stores and releases per owner like property entries`() {
        val store = ProgrammerStore()
        store.putChannel(ProgrammerOwner.UNPARK, 0, 5, 90u, touched = false)
        store.putChannel(web, 0, 5, 200u)
        assertEquals(200u.toUByte(), store.getChannel(0, 5))
        store.clearChannel(web, 0, 5)
        assertEquals(90u.toUByte(), store.getChannel(0, 5), "unpark value revealed under the web write")
        assertEquals(90u.toUByte(), store.channelValueFor(ProgrammerOwner.UNPARK, 0, 5))
    }

    @Test
    fun `high universe numbers and channel 512 pack correctly in the sideband`() {
        val store = ProgrammerStore()
        store.putChannel(web, 32767, 512, 255u)
        assertEquals(255u.toUByte(), store.getChannel(32767, 512))
        val entry = store.channelEntries().single()
        assertEquals(32767, entry.universe)
        assertEquals(512, entry.channel)
    }

    @Test
    fun `a property write absorbs sideband slots under its channels`() {
        val store = ProgrammerStore()
        store.putChannel(ProgrammerOwner.UNPARK, 0, 1, 90u, touched = false)
        store.putChannel(ProgrammerOwner.UNPARK, 0, 2, 91u, touched = false)
        store.put(web, "hex-1", "dimmer", slider(200))
        store.clearChannelsAbsorbedBy(listOf(packChannelKey(0, 1)))

        assertNull(store.getChannel(0, 1), "absorbed by the property write")
        assertEquals(91u.toUByte(), store.getChannel(0, 2), "unrelated channel untouched")
    }

    // --- Enumeration ---

    @Test
    fun `entries and activeKeys enumerate the store`() {
        val store = ProgrammerStore()
        store.put(web, "hex-1", "dimmer", slider(10))
        store.put(locate, "hex-1", "dimmer", slider(20))
        store.put(web, "hex-2", "rgbColour", CueAssignmentResolver.PropertyValue.Colour(ExtendedColour.BLACK), sourceGroup = "all-hex")

        val entries = store.entries().sortedBy { it.fixtureKey }
        assertEquals(2, entries.size)
        assertEquals(listOf(locate, web), entries[0].slots.map { it.owner }, "most recent first")
        assertEquals("all-hex", entries[1].slots.single().sourceGroup)

        assertEquals(
            setOf(
                CueAssignmentResolver.Key.fixture("hex-1", "dimmer"),
                CueAssignmentResolver.Key.fixture("hex-2", "rgbColour"),
            ),
            store.activeKeys(),
        )
        assertEquals(mapOf("hex-1" to setOf("dimmer"), "hex-2" to setOf("rgbColour")), store.activePropertiesByFixture())
    }

    @Test
    fun `clearing a fixture's last entry keeps the store usable and accurately empty`() {
        val store = ProgrammerStore()
        store.put(web, "hex-1", "dimmer", slider(10))
        assertTrue(store.coversFixture("hex-1"))

        store.clear(web, "hex-1", "dimmer")
        // The emptied per-fixture map is deliberately left in place (removing it would
        // race a concurrent put on another property of the same fixture) — but every
        // observable accessor must still read as empty.
        assertFalse(store.coversFixture("hex-1"))
        assertTrue(store.isEmpty)
        assertEquals(0, store.size)
        assertTrue(store.activeKeys().isEmpty())
        assertTrue(store.activePropertiesByFixture().isEmpty())

        // And a fresh put on the same fixture lands normally.
        store.put(web, "hex-1", "rgbColour", CueAssignmentResolver.PropertyValue.Colour(ExtendedColour.BLACK))
        assertTrue(store.coversFixture("hex-1"))
        assertEquals(1, store.size)
    }

    @Test
    fun `no-op clears do not bump the epoch`() {
        val store = ProgrammerStore()
        store.put(web, "hex-1", "dimmer", slider(10))
        val e = store.epoch
        store.clear(locate, "hex-1", "dimmer")   // owner holds nothing here
        store.clear(web, "hex-2", "dimmer")      // fixture holds nothing
        store.clearChannel(web, 0, 9)            // sideband holds nothing
        assertEquals(e, store.epoch, "no-op clears must not invalidate epoch-keyed caches")
    }

    @Test
    fun `epoch increments on every mutation`() {
        val store = ProgrammerStore()
        val e0 = store.epoch
        store.put(web, "hex-1", "dimmer", slider(10))
        val e1 = store.epoch
        assertTrue(e1 > e0)
        store.clear(web, "hex-1", "dimmer")
        assertTrue(store.epoch > e1)
    }

    @Test
    fun `concurrent writes survive`() {
        val store = ProgrammerStore()
        // Smoke check for the copy-on-write slot machinery under rapid-fire writes.
        repeat(1000) { i ->
            store.put(web, "fixture-${i % 8}", "prop-${i % 5}", slider(i % 256))
        }
        // lcm(8, 5) = 40 distinct (fixture, property) pairs.
        assertEquals(40, store.size)
    }

    // ─── Palette references ────────────────────────────────────────────────

    private val paletteA: java.util.UUID =
        java.util.UUID.fromString("2f1c9a54-8d3b-4f7e-9a11-6c0de5b47a02")
    private val paletteB: java.util.UUID =
        java.util.UUID.fromString("9b7e2c10-4a5d-4c88-b0f3-1de4a7c93b55")

    @Test
    fun `a Ref reads through resolved exactly like a Hard`() {
        // The property every hot read path depends on: nothing on the tick loop branches on which
        // variant it has.
        val store = ProgrammerStore()
        store.putValue(web, "hex-1", "dimmer", ProgrammerValue.Ref(paletteA, slider(180)))
        assertEquals(180u.toUByte(), store.topSlider("hex-1", "dimmer"))
        assertEquals(paletteA, store.get("hex-1", "dimmer")?.value?.paletteUuidOrNull)
    }

    @Test
    fun `a Hard value reports no palette`() {
        val store = ProgrammerStore()
        store.put(web, "hex-1", "dimmer", slider(180))
        assertNull(store.get("hex-1", "dimmer")?.value?.paletteUuidOrNull)
    }

    @Test
    fun `programmerValueOf wraps by whether a palette was involved`() {
        assertEquals(ProgrammerValue.Hard(slider(10)), programmerValueOf(slider(10), null))
        assertEquals(ProgrammerValue.Ref(paletteA, slider(10)), programmerValueOf(slider(10), paletteA))
    }

    @Test
    fun `rewriteSlotValues re-resolves refs and reports only changed keys`() {
        val store = ProgrammerStore()
        store.putValue(web, "hex-1", "dimmer", ProgrammerValue.Ref(paletteA, slider(100)))
        store.putValue(web, "hex-2", "dimmer", ProgrammerValue.Ref(paletteB, slider(100)))
        store.put(web, "hex-3", "dimmer", slider(100))

        val changed = store.rewriteSlotValues { _, _, slot ->
            val ref = slot.value as? ProgrammerValue.Ref ?: return@rewriteSlotValues null
            if (ref.paletteUuid != paletteA) null else ProgrammerValue.Ref(paletteA, slider(200))
        }

        assertEquals(setOf(CueAssignmentResolver.Key.fixture("hex-1", "dimmer")), changed)
        assertEquals(200u.toUByte(), store.topSlider("hex-1", "dimmer"))
        assertEquals(100u.toUByte(), store.topSlider("hex-2", "dimmer"), "another palette is untouched")
        assertEquals(100u.toUByte(), store.topSlider("hex-3", "dimmer"), "a literal is untouched")
    }

    @Test
    fun `rewriteSlotValues preserves owner, touched, sourceGroup and seq`() {
        // seq preservation is load-bearing: it arbitrates a property entry against a sideband slot
        // covering the same channel, so bumping it here would let a palette edit outrank a *newer*
        // raw channel write and silently change what Record captures. touched and sourceGroup
        // matter because neither a re-resolve nor a harden is an operator edit.
        val store = ProgrammerStore()
        store.putValue(
            locate, "hex-1", "dimmer", ProgrammerValue.Ref(paletteA, slider(100)),
            touched = false, sourceGroup = "front-wash",
        )
        val before = store.get("hex-1", "dimmer")!!

        store.rewriteSlotValues { _, _, _ -> ProgrammerValue.Ref(paletteA, slider(200)) }

        val after = store.get("hex-1", "dimmer")!!
        assertEquals(before.owner, after.owner)
        assertEquals(before.touched, after.touched)
        assertEquals(before.sourceGroup, after.sourceGroup)
        assertEquals(before.seq, after.seq, "seq must survive a re-resolve")
        assertEquals(200u.toUByte(), store.topSlider("hex-1", "dimmer"))
    }

    @Test
    fun `hardening a ref bumps the epoch even though no value moved`() {
        // Make Hard changes slot identity without changing the resolved value, so `changed` is
        // empty — but epoch-cached consumers still have to re-read.
        val store = ProgrammerStore()
        store.putValue(web, "hex-1", "dimmer", ProgrammerValue.Ref(paletteA, slider(100)))
        val epochBefore = store.epoch

        val changed = store.rewriteSlotValues { _, _, slot ->
            (slot.value as? ProgrammerValue.Ref)?.let { ProgrammerValue.Hard(it.resolved) }
        }

        assertTrue(changed.isEmpty(), "the resolved value did not move, so nothing needs republishing")
        assertTrue(store.epoch > epochBefore, "but the store did mutate")
        assertNull(store.get("hex-1", "dimmer")?.value?.paletteUuidOrNull, "the ref is gone")
        assertEquals(100u.toUByte(), store.topSlider("hex-1", "dimmer"), "the value stayed put")
    }

    @Test
    fun `rewriteSlotValues rewrites slots below the winner too`() {
        // A ref under a later literal write must still re-resolve: releasing the literal reveals
        // it, and it would otherwise reveal a stale value.
        val store = ProgrammerStore()
        store.putValue(locate, "hex-1", "dimmer", ProgrammerValue.Ref(paletteA, slider(100)))
        store.put(web, "hex-1", "dimmer", slider(50))

        store.rewriteSlotValues { _, _, slot ->
            (slot.value as? ProgrammerValue.Ref)?.let { ProgrammerValue.Ref(paletteA, slider(200)) }
        }

        assertEquals(50u.toUByte(), store.topSlider("hex-1", "dimmer"), "the winner is unaffected")
        assertEquals(
            slider(200),
            (store.valueFor(locate, "hex-1", "dimmer") as ProgrammerValue.Ref).resolved,
            "the buried ref re-resolved",
        )
    }

    @Test
    fun `rewriteSlotValues leaves everything alone when the transform declines`() {
        val store = ProgrammerStore()
        store.put(web, "hex-1", "dimmer", slider(100))
        val epochBefore = store.epoch
        assertTrue(store.rewriteSlotValues { _, _, _ -> null }.isEmpty())
        assertEquals(epochBefore, store.epoch, "a no-op sweep must not bump the epoch")
    }
}
