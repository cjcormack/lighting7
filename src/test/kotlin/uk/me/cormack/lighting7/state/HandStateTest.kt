package uk.me.cormack.lighting7.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import uk.me.cormack.lighting7.models.BuskPadKind
import uk.me.cormack.lighting7.routes.BuskCueDto
import uk.me.cormack.lighting7.routes.LookDto
import uk.me.cormack.lighting7.routes.TemplateDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [HandState]'s four rules, each one the multi-screen plan names (§3.5, D12):
 *
 *  - a **second pick-up replaces** and is not an error — the operator changed their mind, and
 *    there is no other gesture that means it;
 *  - the two time stamps are **[HandState]'s to write**, not the caller's;
 *  - the **timeout** drops what nobody placed, and does so without emptying a hand that has since
 *    been refilled — the one race in this class;
 *  - **[HandState.reconcile]** drops a record that has gone, and keeps one that has merely changed.
 *
 * The fifth rule, *a project switch clears it*, is a fact about what `State`'s project collector
 * **does**, so its guard is over a real `State` in `plugins/HandSocketTest` — the shape
 * `WindowRegistryTest` uses for the same split.
 *
 * Virtual time throughout: `runTest`'s scheduler is what makes a five-minute timeout a test that
 * runs in milliseconds, and the expiry job is launched into [kotlinx.coroutines.test.TestScope]
 * precisely so it can be advanced rather than waited for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandStateTest {

    // Real summary DTOs, because `Held` asserts that exactly one is set and matches the kind — the
    // rule `DaoBuskPads` has a database CHECK constraint for. Their contents don't matter here;
    // their presence does.
    private val templateDto = TemplateDto(
        id = 7, uuid = "tmpl-uuid", name = "Warm wash",
        isGeneric = true, kind = "value", layerCount = 0, buskPageCount = 0,
    )
    private val lookDto = LookDto(
        id = 9, uuid = "look-uuid", name = "Act 1 opener",
        families = emptyList(), rowCount = 0, effectCount = 0, targetCount = 0,
        hasDeferredEffects = false, preview = emptyList(), layerCount = 0, buskPageCount = 0,
    )
    private val cueDto = BuskCueDto(
        id = 11, uuid = "cue-uuid", name = "Cue 3", cueStackId = 1, cueStackName = "Main",
    )

    private val template = HandState.Held(BuskPadKind.TEMPLATE, id = 7, uuid = "tmpl-uuid", template = templateDto)
    private val look = HandState.Held(BuskPadKind.LOOK, id = 9, uuid = "look-uuid", look = lookDto)
    private val cue = HandState.Held(BuskPadKind.CUE, id = 11, uuid = "cue-uuid", cue = cueDto)

    /** Everything resolves unless a test says otherwise. */
    private val everything: (BuskPadKind, Int) -> Boolean = { _, _ -> true }

    @Test
    fun `a pick-up stamps the times the caller did not, and a second one replaces the first`() = runTest {
        var clock = 1_000L
        val hand = HandState(backgroundScope, everything, timeout = 5.minutes, nowMs = { clock })

        assertNull(hand.held.value, "a fresh desk holds nothing")

        val first = assertNotNull(hand.pickUp(template))
        assertEquals(1_000L, first.pickedUpAtMs)
        assertEquals(1_000L + 5.minutes.inWholeMilliseconds, first.expiresAtMs)
        assertEquals(first, hand.held.value)

        // A caller that tried to write its own stamps is overwritten: all three are the desk's.
        clock = 2_000L
        val second = assertNotNull(
            hand.pickUp(look.copy(holdId = 999, pickedUpAtMs = 1, expiresAtMs = Long.MAX_VALUE)),
        )
        assertEquals(2_000L, second.pickedUpAtMs)
        assertEquals(2_000L + 5.minutes.inWholeMilliseconds, second.expiresAtMs)
        assertNotEquals(999L, second.holdId, "a caller cannot choose the hold a guarded drop matches")
        assertEquals(BuskPadKind.LOOK, hand.held.value?.kind, "the second pick-up replaced the first")
        assertEquals(9, hand.held.value?.id)
    }

    @Test
    fun `a drop empties the hand and dropping again is a no-op`() = runTest {
        val hand = HandState(backgroundScope, everything)
        hand.pickUp(cue)
        assertNotNull(hand.held.value)

        hand.drop()
        assertNull(hand.held.value)
        hand.drop()
        assertNull(hand.held.value, "an empty hand is the state a drop wants — dropping again is idempotent")
    }

    @Test
    fun `the timeout drops what nobody placed`() = runTest {
        val hand = HandState(backgroundScope, everything, timeout = 5.minutes)
        hand.pickUp(template)

        advanceTimeBy(4.minutes + 59.seconds)
        yield()
        assertNotNull(hand.held.value, "still held with a second to go")

        advanceTimeBy(2.seconds)
        yield()
        assertNull(hand.held.value, "five minutes later the desk has let go by itself")
    }

    @Test
    fun `the first item's timeout does not empty a hand a second pick-up has refilled`() = runTest {
        // The one race in this class. Five minutes after the first pick-up, its job wakes; by then
        // the operator has picked up something else, and an unguarded drop would take that instead.
        val hand = HandState(backgroundScope, everything, timeout = 5.minutes)
        hand.pickUp(template)

        advanceTimeBy(4.minutes)
        yield()
        val second = hand.pickUp(look)

        advanceTimeBy(2.minutes)  // past the *first* item's expiry, well short of the second's
        yield()
        assertEquals(second, hand.held.value, "the replaced item's timer must not empty the new hold")

        advanceTimeBy(4.minutes)  // now past the second's
        yield()
        assertNull(hand.held.value, "and the second item has its own five minutes, not the first's")
    }

    @Test
    fun `a drop cancels the timer, so a later pick-up keeps its own full five minutes`() = runTest {
        val hand = HandState(backgroundScope, everything, timeout = 5.minutes)
        hand.pickUp(template)
        advanceTimeBy(1.minutes)
        yield()
        hand.drop()

        val second = hand.pickUp(look)
        advanceTimeBy(4.minutes + 30.seconds)
        yield()
        assertEquals(second, hand.held.value, "the dropped item's cancelled timer cannot fire on this one")
    }

    @Test
    fun `reconcile drops a record that has gone, on each of the three kinds`() = runTest {
        for (record in listOf(template, look, cue)) {
            var gone = false
            val hand = HandState(backgroundScope, resolves = { _, _ -> !gone })
            hand.pickUp(record)

            hand.reconcile(record.kind)
            assertNotNull(hand.held.value, "${record.kind} still exists, so the hand keeps it")

            gone = true
            hand.reconcile(record.kind)
            assertNull(hand.held.value, "a deleted ${record.kind} takes the hand with it")
        }
    }

    @Test
    fun `reconcile asks about the record actually held, and leaves an empty hand alone`() = runTest {
        val asked = mutableListOf<Pair<BuskPadKind, Int>>()
        val hand = HandState(backgroundScope, resolves = { kind, id -> asked += kind to id; true })

        hand.reconcile(BuskPadKind.LOOK)
        assertTrue(asked.isEmpty(), "nothing held, nothing to ask about")

        hand.pickUp(look)
        hand.reconcile(BuskPadKind.TEMPLATE)
        assertTrue(asked.isEmpty(), "a template list moving cannot affect a held Look — no read at all")

        hand.reconcile(BuskPadKind.LOOK)
        assertEquals(listOf(BuskPadKind.LOOK to 9), asked, "the kind and id it holds, not some other pair")
    }

    @Test
    fun `an edit is not a delete — a record whose contents moved stays in the hand`() = runTest {
        // The hand's face is frozen, exactly as a busk pad's is between reads: `reconcile` asks
        // whether the record *exists*, never whether it still looks the way it did.
        val hand = HandState(backgroundScope, everything)
        val held = hand.pickUp(template)
        hand.reconcile(BuskPadKind.TEMPLATE)
        assertSame(held, hand.held.value)
    }

    // ─── The conflation trap, and the guards built for it ──────────────

    /**
     * The bug a reference-identity guard could not survive, and the reason [HandState.Held] carries
     * a `holdId` at all.
     *
     * `MutableStateFlow` conflates by `equals`: assigning a value equal to the current one keeps the
     * **old instance** and emits nothing. Two pick-ups of the *same* record inside one `nowMs()`
     * tick produce `equals` `Held`s — and `System.currentTimeMillis()` is ~15.6 ms granular on
     * Windows, the packaged target, so "inside one tick" is a double-click. With a reference check
     * the second assignment was a no-op, the second expiry job closed over an instance the flow had
     * never stored, and five minutes later it declined to fire: the hand sat held with **no armed
     * timer**.
     *
     * Note the clock is pinned, which is what makes this deterministic rather than a race.
     */
    @Test
    fun `two identical pick-ups in one clock tick still expire`() = runTest {
        val hand = HandState(backgroundScope, everything, timeout = 5.minutes, nowMs = { 1_000L })

        val first = hand.pickUp(template)
        val second = hand.pickUp(template)

        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(
            first.holdId, second.holdId,
            "two holds must not be equal, or the flow conflates the second away",
        )
        assertEquals(second, hand.held.value, "the second pick-up is what the flow holds")

        advanceTimeBy(6.minutes)
        yield()
        assertNull(hand.held.value, "the timeout fired — with a reference guard this stayed held forever")
    }

    @Test
    fun `dropIfHolding lets go of that hold and nothing else`() = runTest {
        val hand = HandState(backgroundScope, everything)
        val first = assertNotNull(hand.pickUp(template))

        assertTrue(hand.dropIfHolding(first.holdId))
        assertNull(hand.held.value)
        assertTrue(!hand.dropIfHolding(first.holdId), "an empty hand holds nobody's hold")

        // The case the surface's place needs: the hand moved on under the press.
        val second = assertNotNull(hand.pickUp(look))
        assertTrue(!hand.dropIfHolding(first.holdId), "a stale hold id must not clear the new item")
        assertEquals(second, hand.held.value)
    }

    @Test
    fun `dropIfRecord lets go only of the record it names`() = runTest {
        val hand = HandState(backgroundScope, everything)
        hand.pickUp(template)

        assertTrue(!hand.dropIfRecord(look.uuid), "another window's record must not clear this one")
        assertNotNull(hand.held.value)

        assertTrue(hand.dropIfRecord(template.uuid))
        assertNull(hand.held.value)
    }

    @Test
    fun `close lets go and refuses every later pick-up`() = runTest {
        // Without this a frame still in flight through teardown would arm a fresh five-minute job
        // capturing the whole State graph — the retention FU-TEST-COREMIDI-INIT-DEADLOCK names.
        val hand = HandState(backgroundScope, everything)
        hand.pickUp(template)

        hand.close()
        assertNull(hand.held.value)
        assertNull(hand.pickUp(look), "a pick-up after close is refused rather than arming a timer")
        assertNull(hand.held.value)
    }

    @Test
    fun `a Held must carry exactly one summary, matching its kind`() {
        // The rule `DaoBuskPads` has a database CHECK constraint for, asserted here through the same
        // `buskPadKind` that decides it there — a Held naming two records would serialize happily
        // into a ghost every window draws blank.
        assertFailsWith<IllegalArgumentException> {
            HandState.Held(BuskPadKind.TEMPLATE, 1, "u", template = templateDto, look = lookDto)
        }
        assertFailsWith<IllegalArgumentException> {
            HandState.Held(BuskPadKind.TEMPLATE, 1, "u", look = lookDto)
        }
        assertFailsWith<IllegalArgumentException> {
            HandState.Held(BuskPadKind.TEMPLATE, 1, "u")
        }
    }
}
