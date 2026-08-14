package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import uk.me.cormack.lighting7.models.SpeedMasterSource
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeedMasterBankTest {

    private fun snapshot(
        uuid: UUID,
        index: Int,
        name: String = "Master $index",
        bpm: Double = 120.0,
        source: SpeedMasterSource = SpeedMasterSource.MANUAL,
    ) = SpeedMasterSnapshot(uuid, index, name, bpm, source)

    // ─── Slot binding ────────────────────────────────────────────────────

    @Test
    fun `null and unknown uuids resolve to slot 0`() {
        val bank = SpeedMasterBank()
        assertEquals(0, bank.slotFor(null))
        assertEquals(0, bank.slotFor(UUID.randomUUID()), "an unknown uuid degrades to master 1")
    }

    @Test
    fun `load binds uuids to slots in index order, master 1 first`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        val u3 = UUID.randomUUID()
        // Deliberately unordered input — slot order must come from the index.
        bank.load(listOf(snapshot(u3, 3), snapshot(u1, 1), snapshot(u2, 2, bpm = 60.0)))

        assertEquals(0, bank.slotFor(u1))
        assertEquals(1, bank.slotFor(u2))
        assertEquals(2, bank.slotFor(u3))
        assertEquals(60.0, bank.clockFor(1).bpm.value, "a new clock starts at its stored bpm")
        assertEquals(listOf(1, 2, 3), bank.masterStates().map { it.index })
    }

    @Test
    fun `first load adopts the stored bpm onto the synthetic master 1`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 97.0, source = SpeedMasterSource.TAP)))

        assertEquals(97.0, bank.master1().bpm.value)
        assertEquals(SpeedMasterSource.TAP, bank.masterStates().first().source)
    }

    @Test
    fun `reload keeps a surviving clock's live tempo`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1), snapshot(u2, 2, bpm = 60.0)))

        // Operator taps master 2 up to 90 live; the row still says 60 (debounce in flight).
        bank.setBpm(u2, 90.0, SpeedMasterSource.TAP)

        // A rename triggers a reload with the stale stored bpm — it must not clobber the tap.
        bank.load(listOf(snapshot(u1, 1), snapshot(u2, 2, name = "Renamed", bpm = 60.0)))

        assertEquals(90.0, bank.clockFor(bank.slotFor(u2)).bpm.value, "reload must keep the live tempo")
        assertEquals("Renamed", bank.masterStates()[1].name)
    }

    @Test
    fun `master 1 keeps its clock instance across reloads`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1)))
        val clockAfterFirstLoad = bank.master1()

        bank.load(listOf(snapshot(u1, 1, name = "House")))
        assertTrue(
            clockAfterFirstLoad === bank.master1(),
            "beatSync/fxState subscriptions hold master 1's StateFlows — the instance must survive",
        )
    }

    @Test
    fun `a deleted master's uuid resolves back to slot 0 and version moves`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1), snapshot(u2, 2)))
        val versionBefore = bank.version
        assertEquals(1, bank.slotFor(u2))

        bank.load(listOf(snapshot(u1, 1)))

        assertEquals(0, bank.slotFor(u2), "a dangling reference degrades to master 1, never crashes")
        assertNotEquals(versionBefore, bank.version, "membership changes must move the version so the engine re-binds")
    }

    @Test
    fun `a write to an unknown uuid is dropped, never redirected to master 1`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 120.0)))

        // A stale UI tile can tap a master deleted moments earlier: that write must not
        // fall through to the global master and retune the whole show.
        bank.setBpm(UUID.randomUUID(), 90.0, SpeedMasterSource.MANUAL)
        bank.tap(UUID.randomUUID())

        assertEquals(120.0, bank.master1().bpm.value, "master 1 must be untouched by a dangling write")
        assertEquals(SpeedMasterSource.MANUAL, bank.masterStates().single().source)
    }

    // ─── Wake conflation ─────────────────────────────────────────────────

    @Test
    fun `wake channel conflates a burst of ticks into one pass`() {
        val bank = SpeedMasterBank()
        repeat(5) { bank.wake.trySend(Unit) }

        assertTrue(bank.wake.tryReceive().isSuccess, "one wake must be pending")
        assertTrue(bank.wake.tryReceive().isFailure, "the other four collapsed into it")
    }

    // ─── Tempo writes ────────────────────────────────────────────────────

    @Test
    fun `setBpm and tap record their source and emit a change`() = runBlocking {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1)))

        val changes = ConcurrentLinkedQueue<SpeedMasterBank.Change>()
        val collector = launch(Dispatchers.Default) { bank.changes.collect { changes.add(it) } }
        // Give the collector a beat to subscribe before emitting.
        delay(50)

        bank.setBpm(null, 128.0, SpeedMasterSource.MANUAL)
        delay(100)
        collector.cancel()

        assertEquals(128.0, bank.master1().bpm.value)
        val change = changes.single()
        assertEquals(u1, change.uuid)
        assertEquals(128.0, change.bpm)
        assertEquals(SpeedMasterSource.MANUAL, change.source)
    }

    // ─── Beat fan-out ────────────────────────────────────────────────────

    /**
     * Collect from [SpeedMasterBank.beats] while the bank runs for [forMs], then stop.
     * Clocks run at 300 BPM in these tests (5 beats/s) so a short window still sees several.
     */
    private fun collectBeats(
        bank: SpeedMasterBank,
        forMs: Long = 700,
    ): List<SpeedMasterBank.Beat> = runBlocking {
        val beats = ConcurrentLinkedQueue<SpeedMasterBank.Beat>()
        val collector = launch(Dispatchers.Default) { bank.beats.collect { beats.add(it) } }
        delay(50)
        bank.start(this)
        delay(forMs)
        bank.stop()
        collector.cancel()
        beats.toList()
    }

    @Test
    fun `every master's beats are tagged with its own identity`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 300.0), snapshot(u2, 2, name = "Chorus", bpm = 300.0)))

        val beats = collectBeats(bank)

        // The whole point of the keyed stream: master 2's beats are distinguishable from
        // master 1's, which `beatSync` can never be — it is wired to one clock object.
        val fromM1 = beats.filter { it.uuid == u1 }
        val fromM2 = beats.filter { it.uuid == u2 }
        assertTrue(fromM1.isNotEmpty(), "master 1 must appear on the keyed stream too")
        assertTrue(fromM2.isNotEmpty(), "master 2 must emit its own beats")
        assertTrue(fromM1.all { it.index == 1 })
        assertTrue(fromM2.all { it.index == 2 })
        assertTrue(fromM2.all { it.bpm == 300.0 }, "each beat carries its own master's tempo")
    }

    @Test
    fun `a master added by a reload starts emitting without re-wiring`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 300.0)))
        // The reload mints a brand-new clock for master 2. Nothing re-subscribes — the hook
        // is wired at clock creation and the tagging happens at emit time.
        bank.load(listOf(snapshot(u1, 1, bpm = 300.0), snapshot(u2, 2, bpm = 300.0)))

        val beats = collectBeats(bank)

        assertTrue(beats.any { it.uuid == u2 }, "a master added after the first load must still emit")
    }

    @Test
    fun `a deleted master stops appearing on the stream`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 300.0), snapshot(u2, 2, bpm = 300.0)))
        bank.load(listOf(snapshot(u1, 1, bpm = 300.0)))

        val beats = collectBeats(bank)

        assertTrue(beats.any { it.uuid == u1 }, "the surviving master keeps beating")
        assertTrue(beats.none { it.uuid == u2 }, "a dropped master's clock is stopped, not left emitting")
    }

    // ─── Timer accuracy ──────────────────────────────────────────────────

    /**
     * The regression guard for the tick-interval truncation fix: the old
     * `(60_000 / (bpm * 24)).toLong()` delay made a 120 BPM clock run at 50 ticks/s
     * (~125 BPM) and a 60 BPM clock at ~24.4 ticks/s — a 2.05 ratio between masters that
     * should hold exactly 2.0. Rates are measured from the clocks' own tick timestamps,
     * so scheduler jitter inside the window doesn't skew the measurement; the deadline
     * timer self-corrects per-delay overshoot, which is precisely what's being asserted.
     */
    @Test
    fun `deadline timer holds long-run rates exact — 120 vs 60 BPM is 2 to 1`() = runBlocking {
        val fast = MasterClock().apply { setBpm(120.0) }
        val slow = MasterClock().apply { setBpm(60.0) }
        val fastTicks = ConcurrentLinkedQueue<MasterClock.ClockTick>()
        val slowTicks = ConcurrentLinkedQueue<MasterClock.ClockTick>()
        val j1 = launch(Dispatchers.Default) { fast.tickFlow.collect { fastTicks.add(it) } }
        val j2 = launch(Dispatchers.Default) { slow.tickFlow.collect { slowTicks.add(it) } }
        delay(50)

        fast.start(this)
        slow.start(this)
        delay(2_500)
        fast.stop()
        slow.stop()
        j1.cancel()
        j2.cancel()

        fun ratePerSecond(ticks: List<MasterClock.ClockTick>): Double {
            assertTrue(ticks.size > 20, "expected a healthy tick count, got ${ticks.size}")
            val spanMs = ticks.last().timestampMs - ticks.first().timestampMs
            return (ticks.size - 1) * 1000.0 / spanMs
        }

        val fastRate = ratePerSecond(fastTicks.toList())
        val slowRate = ratePerSecond(slowTicks.toList())

        // 120 BPM = 48 ticks/s, 60 BPM = 24 ticks/s. The truncation bug measured ~50 and
        // ~24.4 — well outside these bounds.
        assertEquals(48.0, fastRate, 0.7, "120 BPM must tick at 48/s, not ~50/s")
        assertEquals(24.0, slowRate, 0.35, "60 BPM must tick at 24/s")
        assertEquals(2.0, fastRate / slowRate, 0.04, "inter-master ratio must hold exactly")
    }

    @Test
    fun `bpm change preserves the tick counter so phase stays continuous`() = runBlocking {
        val clock = MasterClock().apply { setBpm(300.0) }
        val ticks = ConcurrentLinkedQueue<MasterClock.ClockTick>()
        val j = launch(Dispatchers.Default) { clock.tickFlow.collect { ticks.add(it) } }
        delay(50)

        clock.start(this)
        delay(300)
        val beforeChange = ticks.toList().lastOrNull()?.tickNumber ?: 0
        clock.setBpm(150.0)
        delay(300)
        clock.stop()
        j.cancel()

        assertTrue(beforeChange > 0, "clock must have ticked before the change")
        val after = ticks.toList().map { it.tickNumber }
        assertEquals(after.sorted(), after, "tick numbers stay monotonic across a tempo change")
        assertNull(
            after.zipWithNext().firstOrNull { (a, b) -> b != a + 1 },
            "no tick number is skipped or repeated across a tempo change",
        )
    }
}
