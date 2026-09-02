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
        followTarget: UUID? = null,
    ) = SpeedMasterSnapshot(
        uuid, index, name, bpm, source, usage, follow?.first, follow?.second, followTarget,
    )

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

        val expected = SpeedMasterBank.TempoWriteOutcome.RefusedFollower(u2, 2, "Movement", 1, 2, "Master 1")
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
    fun `a derived bpm outside the clock range is reported exactly, not clamped`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 120.0), snapshot(u2, 2, follow = 2 to 1)))

        bank.setBpm(u1, 200.0, SpeedMasterSource.MANUAL)

        // MIN/MAX_BPM guard the tick *timer*, and a follower has no timer — its clock is
        // driven by its leader's, so it genuinely runs at 400. Clamping to 300 (which is what
        // this did while follow was tempo-only) would make the strip's readout, the persisted
        // row and `rateScales` all disagree with the master's real rate.
        assertEquals(400.0, bank.clockFor(bank.slotFor(u2)).bpm.value, "2× of 200 is 400, timer range or not")
        assertEquals(400.0, bank.masterStates()[1].bpm, "every surface reports the same derived truth")

        // Still derived from the leader's live bpm rather than the follower's previous value,
        // so nothing ratchets.
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
        assertEquals(
            u1, states[1].followTargetUuid,
            "a row that names no target follows master 1, and reports it *resolved* — a client " +
                "should not have to know that null once meant master 1",
        )
    }

    // ─── Follow: phase lock, targets and chains ──────────────────────────

    /**
     * Run [bank] for [forMs] and stop it, so both clocks are static when the assertions read
     * them. A follower is driven from inside its leader's tick callback, so sampling a running
     * pair can catch the leader one tick ahead of the follower — hence the stop, and hence the
     * one-tick tolerance in the relations below.
     */
    private fun runBank(bank: SpeedMasterBank, forMs: Long = 600) = runBlocking {
        bank.start(this)
        delay(forMs)
        bank.stop()
    }

    private fun tickOf(bank: SpeedMasterBank, uuid: UUID): Long =
        bank.clockFor(bank.slotFor(uuid)).currentTick.tickNumber

    @Test
    fun `a follower's tick counter is a pure function of its leader's`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val half = UUID.randomUUID()
        val double = UUID.randomUUID()
        bank.load(
            listOf(
                snapshot(u1, 1, bpm = 300.0),
                snapshot(half, 2, follow = 1 to 2),
                snapshot(double, 3, follow = 2 to 1),
            )
        )

        runBank(bank)

        // This is the fix for FU-SPEED-PHASE-LOCK: the follower's counter is derived from the
        // leader's, anchored at tick 0, rather than free-running at whatever offset its own
        // timer happened to start on.
        val leaderTick = tickOf(bank, u1)
        assertTrue(leaderTick > 24, "the leader must have run, got $leaderTick")
        assertTrue(
            (1 + (leaderTick - 1) / 2) - tickOf(bank, half) in 0..1,
            "½ must tick at half the leader's count",
        )
        assertTrue(
            (1 + (leaderTick - 1) * 2) - tickOf(bank, double) in 0..2,
            "2× must tick at twice the leader's count",
        )
    }

    /**
     * Re-pointing a *live* follower at a smaller ratio must snap its counter, not freeze it.
     *
     * A follower driven at 2× carries twice its leader's tick count; ½ maps it to a quarter of
     * where it stands, and [MasterClock.driveTo] is monotonic — so without the re-anchor the
     * clock refuses every drive until the leader's counter quadruples, which on a desk that has
     * been up an hour is another hour. Nothing about that failure is loud: the tempo readout
     * still reads the derived bpm, so the rail looks right while the beat lamp never lights and
     * every effect on the master stands still. Ratios *above* the old one hide it — they map
     * ahead of the counter and drive on the first tick — which is why 2× looked fine while ½,
     * ⅓ and ¼ did not.
     */
    @Test
    fun `lowering a live follower's ratio snaps it instead of freezing it`() = runBlocking {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 300.0), snapshot(u2, 2, follow = 2 to 1)))
        bank.start(this)
        delay(400)

        // What the busk rail's ratio chips do: a PUT, then a reload of the same uuid.
        bank.load(listOf(snapshot(u1, 1, bpm = 300.0), snapshot(u2, 2, follow = 1 to 2)))
        delay(50)
        val first = tickOf(bank, u2)
        delay(300)
        val second = tickOf(bank, u2)
        bank.stop()

        assertTrue(second > first, "the follower must keep ticking, got $first then $second")
    }

    /**
     * The other half of the snap rule: a reload that does not touch the mapping must not
     * re-anchor. Renames, usage retags and stored-tempo edits all arrive as a reload of the
     * same uuid, and snapping on those is not the no-op it looks like — the counter lands back
     * on the same mapped value (it is a pure function of the leader's tick), but the zeroed
     * counter makes [MasterClock.driveTo] read "no previous beat" and fire [onBeat] again for
     * the beat already in progress. The visible cost is a beat lamp that double-flashes every
     * time anything about the master is edited, so the assertion is on the beat *sequence*
     * rather than on the counter, which cannot see this at all.
     */
    @Test
    fun `a reload that leaves the ratio alone does not re-fire the follower's beat`() = runBlocking {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 300.0), snapshot(u2, 2, follow = 1 to 2)))

        val beats = ConcurrentLinkedQueue<SpeedMasterBank.Beat>()
        val collector = launch(Dispatchers.Default) { bank.beats.collect { beats.add(it) } }
        delay(50)
        bank.start(this)
        // 300 BPM halves to a 400 ms follower beat, so 500 ms lands the reload squarely
        // *inside* beat 1 — a reload that coincided with a boundary would re-fire the beat it
        // was already emitting and hide the difference.
        delay(500)

        // A rename: same uuid, same ratio, same leader.
        bank.load(
            listOf(
                snapshot(u1, 1, bpm = 300.0),
                snapshot(u2, 2, name = "Renamed", follow = 1 to 2),
            )
        )
        delay(500)
        bank.stop()
        collector.cancel()

        val numbers = beats.filter { it.uuid == u2 }.map { it.beatNumber }
        assertTrue(numbers.size > 1, "the follower must have beaten either side of the reload, got ${'$'}numbers")
        assertEquals(
            numbers.distinct(), numbers,
            "a rename must not re-fire a beat the follower has already emitted",
        )
    }

    @Test
    fun `a follower's beats land on its leader's, not between them`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 300.0), snapshot(u2, 2, follow = 1 to 2)))

        val beats = collectBeats(bank, forMs = 1_200)

        val leaderBeats = beats.filter { it.uuid == u1 }
        val followerBeats = beats.filter { it.uuid == u2 }
        assertTrue(followerBeats.isNotEmpty(), "the follower must beat at all")
        // Identical timestamps, not merely close ones: a driven beat is emitted from inside the
        // leader tick that produced it, carrying that tick's timestamp. This is the assertion
        // an operator would make by eye, and the one the old free-running follower failed.
        val leaderInstants = leaderBeats.map { it.timestampMs }.toSet()
        assertTrue(
            followerBeats.all { it.timestampMs in leaderInstants },
            "every ½ follower beat must coincide with a leader beat, got ${followerBeats.map { it.timestampMs }} " +
                "against $leaderInstants",
        )
        assertTrue(
            followerBeats.all { follower -> leaderBeats.any { it.timestampMs == follower.timestampMs && it.beatNumber == follower.beatNumber * 2 } },
            "a ½ follower's beat n must land on the leader's beat 2n",
        )
    }

    @Test
    fun `a follower runs driven, with no timer of its own`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 300.0), snapshot(u2, 2, follow = 1 to 2)))

        runBlocking {
            bank.start(this)
            delay(100)
            val follower = bank.clockFor(bank.slotFor(u2))
            assertTrue(follower.driven, "a follower's ticks come from its leader")
            assertTrue(follower.isRunning.value, "…and it still reports as running, because it is")
            assertTrue(!bank.master1().driven, "a manual master keeps its own timer")
            bank.stop()
        }
    }

    @Test
    fun `a master can follow a master other than master 1`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        val u3 = UUID.randomUUID()
        bank.load(
            listOf(
                snapshot(u1, 1, bpm = 120.0),
                snapshot(u2, 2, bpm = 90.0),
                snapshot(u3, 3, follow = 1 to 2, followTarget = u2),
            )
        )

        assertEquals(45.0, bank.clockFor(bank.slotFor(u3)).bpm.value, "½ of master 2, not of master 1")
        assertEquals(u2, bank.masterStates()[2].followTargetUuid)

        // Retuning master 1 must not touch it; retuning its actual leader must.
        bank.setBpm(u1, 200.0, SpeedMasterSource.MANUAL)
        assertEquals(45.0, bank.clockFor(bank.slotFor(u3)).bpm.value)
        bank.setBpm(u2, 100.0, SpeedMasterSource.MANUAL)
        assertEquals(50.0, bank.clockFor(bank.slotFor(u3)).bpm.value)

        val refusal = bank.setBpm(u3, 70.0, SpeedMasterSource.MANUAL)
        assertEquals(
            SpeedMasterBank.TempoWriteOutcome.RefusedFollower(u3, 3, "Master 3", 1, 2, "Master 2"),
            refusal,
            "the refusal must name the leader to retune instead, which is no longer always master 1",
        )
    }

    @Test
    fun `a chain derives and ticks through its leader`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        val u3 = UUID.randomUUID()
        bank.load(
            listOf(
                snapshot(u1, 1, bpm = 120.0),
                snapshot(u2, 2, follow = 1 to 2),
                snapshot(u3, 3, follow = 1 to 2, followTarget = u2),
            )
        )

        // One sweep pass, leaders first: the grandchild sees its parent's already-derived value.
        assertEquals(60.0, bank.clockFor(bank.slotFor(u2)).bpm.value)
        assertEquals(30.0, bank.clockFor(bank.slotFor(u3)).bpm.value)

        bank.setBpm(u1, 240.0, SpeedMasterSource.MANUAL)
        assertEquals(120.0, bank.clockFor(bank.slotFor(u2)).bpm.value)
        assertEquals(60.0, bank.clockFor(bank.slotFor(u3)).bpm.value, "a chain propagates the whole way down")

        runBank(bank)
        // Nested floors compose — floor(floor(t/2)/2) == floor(t/4) — so the grandchild is
        // aligned to the root, not just to its parent.
        val leaderTick = tickOf(bank, u1)
        assertTrue(leaderTick > 24, "the root must have run, got $leaderTick")
        assertTrue(
            (1 + (leaderTick - 1) / 4) - tickOf(bank, u3) in 0..1,
            "the grandchild must tick at a quarter of the root",
        )
    }

    @Test
    fun `a follow cycle degrades every member to manual`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        val u3 = UUID.randomUUID()

        // The write boundary refuses this; a hand-edited row or a half-imported project can
        // still produce it, and nothing upstream of the pair has a timer to drive either one.
        bank.load(
            listOf(
                snapshot(u1, 1, bpm = 120.0),
                snapshot(u2, 2, bpm = 90.0, follow = 1 to 2, followTarget = u3),
                snapshot(u3, 3, bpm = 80.0, follow = 1 to 2, followTarget = u2),
            )
        )

        val states = bank.masterStates()
        assertNull(states[1].followNum, "a cycle member must not read as following")
        assertNull(states[2].followNum, "…and neither must the other one")
        assertEquals(SpeedMasterBank.TempoWriteOutcome.Applied, bank.setBpm(u2, 95.0, SpeedMasterSource.MANUAL))
        assertEquals(SpeedMasterBank.TempoWriteOutcome.Applied, bank.setBpm(u3, 85.0, SpeedMasterSource.MANUAL))
    }

    @Test
    fun `a dangling follow target degrades to manual`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()

        // What a forced delete of the leader leaves behind. Same principle as an effect whose
        // speed master vanished: fall back to something that runs.
        bank.load(
            listOf(
                snapshot(u1, 1, bpm = 120.0),
                snapshot(u2, 2, bpm = 90.0, follow = 1 to 2, followTarget = UUID.randomUUID()),
            )
        )

        assertEquals(90.0, bank.clockFor(bank.slotFor(u2)).bpm.value, "it keeps its own stored tempo")
        assertNull(bank.masterStates()[1].followNum)
        assertEquals(SpeedMasterBank.TempoWriteOutcome.Applied, bank.setBpm(u2, 95.0, SpeedMasterSource.MANUAL))
    }

    @Test
    fun `unlinking hands the timer back without resetting the tick counter`() = runBlocking {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 300.0), snapshot(u2, 2, follow = 1 to 1)))

        bank.start(this)
        delay(400)
        val whileLinked = tickOf(bank, u2)

        // Linking snaps — that is what phase alignment costs — but *unlinking* must not: the
        // operator stopped following, they didn't ask every effect on this master to jump.
        bank.load(listOf(snapshot(u1, 1, bpm = 300.0), snapshot(u2, 2, bpm = 300.0)))
        delay(300)
        val afterUnlink = tickOf(bank, u2)
        val clock = bank.clockFor(bank.slotFor(u2))
        bank.stop()

        assertTrue(whileLinked > 24, "the follower must have been driven, got $whileLinked")
        assertTrue(afterUnlink > whileLinked, "its own timer must take over, got $afterUnlink after $whileLinked")
        assertTrue(!clock.driven, "and it is no longer driven")
    }

    @Test
    fun `linking a master that has been running snaps its counter instead of freezing it`() = runBlocking {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 300.0), snapshot(u2, 2, bpm = 300.0)))

        bank.start(this)
        delay(800)
        val freeRunning = tickOf(bank, u2)

        // The link is made *after* both have been free-running, which is the only way an
        // operator ever makes one. The tick M1 maps to at ½ is about half M2's own count, and
        // `driveTo` is monotonic — so a follower that kept its counter on adoption would refuse
        // every drive until its leader overtook it, i.e. sit frozen for as long as the desk had
        // been up. Adoption zeroes the counter so the next leader tick assigns the mapped one.
        bank.load(listOf(snapshot(u1, 1, bpm = 300.0), snapshot(u2, 2, follow = 1 to 2)))
        delay(300)
        val afterLink = tickOf(bank, u2)
        val leader = tickOf(bank, u1)
        bank.stop()

        assertTrue(freeRunning > 24, "the follower must have free-run first, got $freeRunning")
        assertTrue(
            afterLink < freeRunning,
            "linking must snap the counter down onto its leader's, got $afterLink after $freeRunning",
        )
        assertTrue(
            (1 + (leader - 1) / 2) - afterLink in 0..1,
            "…and it must then be driven from there, got $afterLink against a leader at $leader",
        )
    }

    @Test
    fun `unlinking pulls an out-of-range derived tempo back into the timer's range`() {
        val bank = SpeedMasterBank()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        bank.load(listOf(snapshot(u1, 1, bpm = 200.0), snapshot(u2, 2, follow = 2 to 1)))
        assertEquals(
            400.0, bank.clockFor(bank.slotFor(u2)).bpm.value,
            "a derived tempo is deliberately unclamped — a driven clock has no timer to protect",
        )

        // …but the moment it is handed its own timer back it does, and 400 BPM is a rate the
        // write boundary would refuse and the row must not go on storing.
        bank.load(listOf(snapshot(u1, 1, bpm = 200.0), snapshot(u2, 2, bpm = 400.0)))
        assertEquals(MasterClock.MAX_BPM, bank.clockFor(bank.slotFor(u2)).bpm.value)
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
