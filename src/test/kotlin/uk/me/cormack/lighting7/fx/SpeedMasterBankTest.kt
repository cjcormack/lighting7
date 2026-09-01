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
        usage: String? = null,
        follow: Pair<Int, Int>? = null,
    ) = SpeedMasterSnapshot(uuid, index, name, bpm, source, usage, follow?.first, follow?.second)

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
            "master 1's onTick/onBeat are wired once in init and never re-wired by load — " +
                "replacing the instance would orphan the engine's wake nudge and the beat fan-out",
        )
    }

    @Test
    fun `master1Uuid reports the loaded uuid, not null`() {
        val bank = SpeedMasterBank()
        assertEquals(null, bank.master1Uuid(), "the synthetic pre-load master has no row to name it")

        val u1 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1)))

        // What `speedMasters.requestBeat` resolves an omitted uuid to. Beats are tagged from
        // the bank entry, so a request parked as `null` could never match a loaded master 1.
        assertEquals(u1, bank.master1Uuid())
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

    // ─── Follow (time signature) ─────────────────────────────────────────

    /** Capture emissions synchronously — the same hook the persister uses. */
    private fun captureChanges(bank: SpeedMasterBank): MutableList<SpeedMasterBank.Change> {
        val changes = mutableListOf<SpeedMasterBank.Change>()
        bank.onChangeSync = { changes.add(it) }
        return changes
    }

    @Test
    fun `a follower tracks master 1 through setBpm, follower change after master 1's`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 120.0), snapshot(u2, 2, follow = 1 to 2)))
        val changes = captureChanges(bank)

        val outcome = bank.setBpm(u1, 100.0, SpeedMasterSource.MANUAL)

        assertEquals(SpeedMasterBank.TempoWriteOutcome.Applied, outcome)
        assertEquals(50.0, bank.clockFor(bank.slotFor(u2)).bpm.value, "follower must derive m1 × ½")
        assertEquals(
            listOf(u1 to 100.0, u2 to 50.0),
            changes.map { it.uuid to it.bpm },
            "master 1's change must be emitted before its follower's — the causal order clients want",
        )
    }

    @Test
    fun `a follower tracks master 1 through tap`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1), snapshot(u2, 2, follow = 1 to 2)))

        // Two real taps; whatever tempo they land on, the follower must sit at half of it.
        bank.tap(u1)
        Thread.sleep(400)
        bank.tap(u1)

        val m1Bpm = bank.master1().bpm.value
        assertNotEquals(120.0, m1Bpm, "the second tap must have moved master 1")
        assertEquals(
            m1Bpm / 2, bank.clockFor(bank.slotFor(u2)).bpm.value,
            "the tap path must sweep followers exactly like setBpm",
        )
    }

    @Test
    fun `one third ratio is exact — multiply before dividing`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1), snapshot(u2, 2, follow = 1 to 3)))

        bank.setBpm(u1, 126.0, SpeedMasterSource.MANUAL)

        // `126.0 * 1 / 3` is exactly 42.0 in IEEE 754; `126.0 * (1.0 / 3.0)` is not. Pins the
        // operation order in the sweep.
        assertEquals(42.0, bank.clockFor(bank.slotFor(u2)).bpm.value)
    }

    @Test
    fun `load derives a follower's bpm, ignoring its stored bpm`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()

        // The row claims 200; a follower's stored bpm is meaningless while linked.
        bank.load(listOf(snapshot(u1, 1, bpm = 120.0), snapshot(u2, 2, bpm = 200.0, follow = 1 to 2)))

        assertEquals(60.0, bank.clockFor(bank.slotFor(u2)).bpm.value, "boot/import must come up in step")
    }

    @Test
    fun `a reload with unchanged tempo emits no follower change`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 120.0), snapshot(u2, 2, follow = 1 to 2)))
        val changes = captureChanges(bank)

        // The persister has flushed the derived 60 to the row; the CRUD reload re-derives the
        // same value. Emitting here would re-arm the persister's debounce forever.
        bank.load(listOf(snapshot(u1, 1, bpm = 120.0), snapshot(u2, 2, bpm = 60.0, follow = 1 to 2)))

        assertTrue(changes.isEmpty(), "an idempotent reload must not emit follower changes, got $changes")
    }

    @Test
    fun `setBpm and tap on a follower are refused and leave the clock alone`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 120.0), snapshot(u2, 2, name = "Movement", follow = 1 to 2)))
        val changes = captureChanges(bank)

        val fromSet = bank.setBpm(u2, 90.0, SpeedMasterSource.MANUAL)
        val fromTap = bank.tap(u2)

        val expected = SpeedMasterBank.TempoWriteOutcome.RefusedFollower(u2, 2, "Movement", 1, 2)
        assertEquals(expected, fromSet)
        assertEquals(expected, fromTap)
        assertEquals(60.0, bank.clockFor(bank.slotFor(u2)).bpm.value, "the refusal must precede any clock write")
        assertTrue(changes.isEmpty(), "a refused write must not emit")
    }

    @Test
    fun `a ratio edit takes effect on reload of the same uuid`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 120.0), snapshot(u2, 2, bpm = 90.0)))
        assertEquals(90.0, bank.clockFor(bank.slotFor(u2)).bpm.value)

        // The reuse branch of load must refresh the follow settings, not just the name — a
        // ratio edit arrives as exactly this reload, and a stale entry would silently drop it.
        bank.load(listOf(snapshot(u1, 1, bpm = 120.0), snapshot(u2, 2, follow = 1 to 2)))
        assertEquals(60.0, bank.clockFor(bank.slotFor(u2)).bpm.value, "the link must take effect")

        // And the unlink direction: back to manual, keeping the live (derived) tempo.
        bank.load(listOf(snapshot(u1, 1, bpm = 120.0), snapshot(u2, 2, bpm = 60.0)))
        val outcome = bank.setBpm(u2, 95.0, SpeedMasterSource.MANUAL)
        assertEquals(SpeedMasterBank.TempoWriteOutcome.Applied, outcome, "an unlinked master takes writes again")
    }

    @Test
    fun `a derived bpm outside the clock range clamps, and un-clamps by re-derivation`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 120.0), snapshot(u2, 2, follow = 2 to 1)))

        bank.setBpm(u1, 200.0, SpeedMasterSource.MANUAL)
        assertEquals(300.0, bank.clockFor(bank.slotFor(u2)).bpm.value, "2× of 200 clamps at MAX_BPM")
        assertEquals(300.0, bank.masterStates()[1].bpm, "every surface reports the clamped truth")

        // The sweep derives from M1's live bpm, never the follower's previous (clamped) value —
        // the naive implementation would ratchet and stick at 300.
        bank.setBpm(u1, 100.0, SpeedMasterSource.MANUAL)
        assertEquals(200.0, bank.clockFor(bank.slotFor(u2)).bpm.value)
    }

    @Test
    fun `master 1 is never a follower, even if a row says so`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()

        // A hand-edited or imported row claiming master 1 follows itself must degrade to
        // manual — validation refuses this at the write boundary, and the bank must not trust
        // rows it didn't validate.
        bank.load(listOf(snapshot(u1, 1, bpm = 120.0, follow = 1 to 2)))

        assertEquals(
            SpeedMasterBank.TempoWriteOutcome.Applied,
            bank.setBpm(u1, 100.0, SpeedMasterSource.MANUAL),
            "master 1 must keep taking tempo writes",
        )
        assertEquals(100.0, bank.master1().bpm.value)
        assertNull(bank.masterStates().single().followNum, "the claimed ratio must be discarded")
    }

    @Test
    fun `a half-written or non-positive ratio degrades to manual`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        val u3 = UUID.randomUUID()
        bank.load(
            listOf(
                snapshot(u1, 1, bpm = 120.0),
                SpeedMasterSnapshot(u2, 2, "Half", 90.0, SpeedMasterSource.MANUAL, null, 1, null),
                snapshot(u3, 3, bpm = 80.0, follow = 0 to 2),
            )
        )

        assertEquals(90.0, bank.clockFor(bank.slotFor(u2)).bpm.value, "a half-written pair must not divide by zero")
        assertEquals(80.0, bank.clockFor(bank.slotFor(u3)).bpm.value, "a non-positive pair is manual")
        assertEquals(SpeedMasterBank.TempoWriteOutcome.Applied, bank.setBpm(u2, 95.0, SpeedMasterSource.MANUAL))
    }

    @Test
    fun `a linked follower reads MANUAL even when the derived tempo equals its stored one`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()

        // Tapped to 60 while manual, then linked at ½ of 120: the sweep derives the same 60,
        // but the TAP badge must not survive on a master that refuses taps.
        val changes = captureChanges(bank)
        bank.load(
            listOf(
                snapshot(u1, 1, bpm = 120.0),
                snapshot(u2, 2, bpm = 60.0, source = SpeedMasterSource.TAP, follow = 1 to 2),
            )
        )

        assertEquals(SpeedMasterSource.MANUAL, bank.masterStates()[1].source)
        // ...and the correction has to be *emitted*, or the row keeps its TAP forever: no bpm
        // moved, so the emission can only come from the source half of the sweep's check.
        assertEquals(
            listOf(u2 to SpeedMasterSource.MANUAL),
            changes.map { it.uuid to it.source },
            "the source correction must reach the persister, got $changes",
        )

        // ...and only once: the reload after the flush sees MANUAL and stays quiet, so the
        // persister's debounce is not re-armed forever.
        changes.clear()
        bank.load(
            listOf(
                snapshot(u1, 1, bpm = 120.0),
                snapshot(u2, 2, bpm = 60.0, follow = 1 to 2),
            )
        )
        assertTrue(changes.isEmpty(), "the healed reload must be idempotent, got $changes")
    }

    @Test
    fun `writes to unknown uuids report UnknownMaster`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 120.0)))

        assertEquals(
            SpeedMasterBank.TempoWriteOutcome.UnknownMaster,
            bank.setBpm(UUID.randomUUID(), 90.0, SpeedMasterSource.MANUAL),
        )
        assertEquals(SpeedMasterBank.TempoWriteOutcome.UnknownMaster, bank.tap(UUID.randomUUID()))
        assertEquals(120.0, bank.master1().bpm.value)
    }

    @Test
    fun `masterStates reports usage and ratio`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(
            listOf(
                snapshot(u1, 1, usage = "dimmer"),
                snapshot(u2, 2, usage = "position", follow = 1 to 2),
            )
        )

        val states = bank.masterStates()
        assertEquals("dimmer", states[0].usage)
        assertNull(states[0].followNum)
        assertEquals("position", states[1].usage)
        assertEquals(1, states[1].followNum)
        assertEquals(2, states[1].followDen)
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
        // master 1's, so an indicator can pulse against the master its effect runs on.
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
     * should hold exactly 2.0. Rates are measured entirely from the clocks' own bookkeeping —
     * tick *numbers* over tick *timestamps* — so neither scheduler jitter nor a dropped
     * delivery skews the measurement; the deadline timer self-corrects per-delay overshoot,
     * which is precisely what's being asserted.
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

        // Span the *numbered* ticks, not the delivered ones. `tryEmit` on a 1-slot buffer drops
        // a tick rather than stall the clock, so `ticks.size` under-counts under load and the
        // measured rate reads low — which is a property of this test's collector, not of the
        // timer being asserted. Tick numbers are assigned by the clock as it emits, so numerator
        // and denominator both come from the clock itself.
        fun ratePerSecond(ticks: List<MasterClock.ClockTick>): Double {
            assertTrue(ticks.size > 20, "expected a healthy tick count, got ${ticks.size}")
            val spanMs = ticks.last().timestampMs - ticks.first().timestampMs
            val spanTicks = ticks.last().tickNumber - ticks.first().tickNumber
            return spanTicks * 1000.0 / spanMs
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

        // Strictly increasing, *not* gap-free. `tickFlow` is a MutableSharedFlow with
        // `extraBufferCapacity = 1` published via `tryEmit`, which deliberately drops a tick
        // rather than let a slow collector stall the clock — so a delivered sequence is allowed
        // to skip. Asserting `b == a + 1` asserted a delivery guarantee the clock does not make,
        // and duly failed under full-suite load (observed `(20, 22)`: tick 21 dropped in
        // delivery, never mis-numbered).
        //
        // Strictly increasing is what this test is actually for: a counter *reset* by `setBpm` —
        // the phase discontinuity being pinned — shows up as a decrease, and a double-count shows
        // up as a repeat. Both are still caught.
        assertNull(
            after.zipWithNext().firstOrNull { (a, b) -> b <= a },
            "tick numbers must never repeat or go backwards across a tempo change",
        )
    }
}
