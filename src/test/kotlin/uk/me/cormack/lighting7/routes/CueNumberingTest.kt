package uk.me.cormack.lighting7.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the pure half of `cueNumbering.kt`. Supersedes the old `NaturalSortTest`, whose flat
 * comparator is gone — the ordering cases it pinned down ("1" < "1.5" < "2" < "14" < "14A" <
 * "14B" < "15" < "100", leading zeros, alpha-only strings) are all re-asserted here against the
 * group-aware model.
 */
class CueNumberingTest {

    private fun sortNumbers(numbers: List<String>): List<String> =
        groupAwareCueOrder(numbers) { it }

    // ─── Parsing ────────────────────────────────────────────────────────

    @Test
    fun `parse splits prefix, decimal run and suffix`() {
        assertEquals(ParsedCueNumber("S1-", listOf(3L, 1L), ""), parseCueNumber("S1-3.1"))
        assertEquals(ParsedCueNumber("S1-", listOf(4L), ""), parseCueNumber("S1-4"))
        assertEquals(ParsedCueNumber("T2-", listOf(1L), ""), parseCueNumber("T2-1"))
        assertEquals(ParsedCueNumber("Pre-show ", listOf(2L), ""), parseCueNumber("Pre-show 2"))
        assertEquals(ParsedCueNumber("", listOf(14L), "A"), parseCueNumber("14A"))
        assertEquals(ParsedCueNumber("", listOf(100L), ""), parseCueNumber("100"))
    }

    @Test
    fun `parse takes the last decimal run, not the first`() {
        // "S1-3" must group as ("S1-", 3), not ("S", 1) with a dangling "-3".
        assertEquals("S1-", parseCueNumber("S1-3")!!.prefix)
        assertEquals(listOf(3L), parseCueNumber("S1-3")!!.segments)
    }

    @Test
    fun `parse rejects numbers with nothing to order by`() {
        assertNull(parseCueNumber("A"))
        assertNull(parseCueNumber("intro"))
        assertNull(parseCueNumber(""))
        // Trailing non-letter junk leaves no anchorable run at the end.
        assertNull(parseCueNumber("S1-"))
    }

    // ─── Ordering ───────────────────────────────────────────────────────

    @Test
    fun `within a group, decimals nest and suffixes tie-break`() {
        assertEquals(
            listOf("1", "1.5", "2", "14", "14A", "14B", "15", "100"),
            sortNumbers(listOf("100", "14B", "1.5", "15", "2", "14A", "1", "14")),
        )
    }

    @Test
    fun `decimal segments compare numerically, not as text`() {
        // "1.10" is the tenth insert under 1, so it follows "1.5".
        assertEquals(listOf("1.5", "1.10"), sortNumbers(listOf("1.10", "1.5")))
    }

    @Test
    fun `leading zeros do not change ordering`() {
        val a = parseCueNumber("01")!!
        val b = parseCueNumber("1")!!
        assertEquals(0, compareWithinGroup(a, b))
    }

    @Test
    fun `groups are keyed on the prefix, case-insensitively`() {
        assertEquals("s1-", cueNumberGroupKey("S1-3"))
        assertEquals("s1-", cueNumberGroupKey("s1-9"))
        assertEquals("pre-show ", cueNumberGroupKey("Pre-show 2"))
        assertEquals("", cueNumberGroupKey("14A"))
        // Unparseable numbers get private keys so they can't be grouped with anything.
        assertTrue(cueNumberGroupKey("intro") != cueNumberGroupKey("verse"))
    }

    // ─── Out-of-order detection ─────────────────────────────────────────

    @Test
    fun `interleaved groups in ascending order are not out of order`() {
        assertFalse(
            detectCueNumbersOutOfOrder(
                listOf("Pre-show 1", "Pre-show 2", "T2-1", "S-1", "S-2"),
            ),
        )
    }

    @Test
    fun `a group descending against itself is out of order`() {
        assertTrue(
            detectCueNumbersOutOfOrder(
                listOf("Pre-show 1", "Pre-show 2", "T2-1", "S-2", "S-1"),
            ),
        )
    }

    @Test
    fun `prefixed numbers participate — the old digit-first rule missed these`() {
        assertTrue(detectCueNumbersOutOfOrder(listOf("S1-4", "S1-3.1")))
        assertFalse(detectCueNumbersOutOfOrder(listOf("S1-3.1", "S1-4")))
    }

    @Test
    fun `blank and unparseable numbers are ignored by detection`() {
        assertFalse(detectCueNumbersOutOfOrder(listOf("S1-1", null, "A", "", "S1-2")))
    }

    // ─── Group-aware sort ───────────────────────────────────────────────

    @Test
    fun `sort fixes a group without disturbing the group interleave`() {
        assertEquals(
            listOf("Pre-show 1", "Pre-show 2", "T2-1", "S-1", "S-2"),
            sortNumbers(listOf("Pre-show 1", "Pre-show 2", "T2-1", "S-2", "S-1")),
        )
    }

    @Test
    fun `sort leaves unparseable entries in their slots`() {
        // "A" holds index 1 throughout; only S1-2 / S1-1 swap around it.
        assertEquals(
            listOf("S1-1", "A", "S1-2"),
            sortNumbers(listOf("S1-2", "A", "S1-1")),
        )
    }

    @Test
    fun `sort only ever permutes within a group`() {
        // Group "s1-" occupies indices 0 and 2, group "t-" indices 1 and 3. Each sorts into its
        // own slots, so the two stay interleaved.
        assertEquals(
            listOf("S1-1", "T-1", "S1-2", "T-2"),
            sortNumbers(listOf("S1-2", "T-2", "S1-1", "T-1")),
        )
    }

    // ─── Auto numbering ─────────────────────────────────────────────────

    private fun auto(vararg cues: Pair<String?, Boolean>): Map<Int, String> =
        computeAutoCueNumbers(cues.map { AutoNumberInput(it.first, it.second) })

    private fun explicit(number: String) = number as String? to false
    private fun blank() = null as String? to false

    @Test
    fun `a fresh stack counts from one`() {
        assertEquals(mapOf(0 to "1", 1 to "2", 2 to "3"), auto(blank(), blank(), blank()))
    }

    @Test
    fun `a run after an explicit number increments its trailing segment`() {
        assertEquals(
            mapOf(1 to "S1-4", 2 to "S1-5"),
            auto(explicit("S1-3"), blank(), blank()),
        )
    }

    @Test
    fun `incrementing falls back to decimal insertion when the next number is taken`() {
        // "S1-4" exists further down, so the run can't increment into it.
        assertEquals(
            mapOf(1 to "S1-3.1", 2 to "S1-3.2"),
            auto(explicit("S1-3"), blank(), blank(), explicit("S1-4")),
        )
    }

    @Test
    fun `incrementing falls back when it would run past the next explicit cue`() {
        // Room for one increment before "S1-6", but not three.
        assertEquals(
            mapOf(1 to "S1-3.1", 2 to "S1-3.2", 3 to "S1-3.3"),
            auto(explicit("S1-3"), blank(), blank(), blank(), explicit("S1-6")),
        )
    }

    @Test
    fun `a clash with an explicit number anywhere in the stack forces decimals`() {
        // "5" sits *after* the run, but the unique index is stack-wide, so 4,5 is unusable.
        assertEquals(
            mapOf(1 to "3.1", 2 to "3.2"),
            auto(explicit("3"), blank(), blank(), explicit("9"), explicit("5")),
        )
    }

    @Test
    fun `a next number in a different group does not constrain the run`() {
        assertEquals(
            mapOf(1 to "S1-4", 2 to "S1-5"),
            auto(explicit("S1-3"), blank(), blank(), explicit("T-1")),
        )
    }

    @Test
    fun `a leading run borrows the following prefix and counts up to it`() {
        assertEquals(
            mapOf(0 to "S1-1", 1 to "S1-2"),
            auto(blank(), blank(), explicit("S1-3")),
        )
    }

    @Test
    fun `a lone leading cue takes the integer below the next one`() {
        // Not "S1-0.1" — zero itself is free, so the obvious label is available.
        assertEquals(mapOf(0 to "S1-0"), auto(blank(), explicit("S1-1")))
        assertEquals(mapOf(0 to "S1-2"), auto(blank(), explicit("S1-3")))
        assertEquals(mapOf(0 to "0"), auto(blank(), explicit("1")))
    }

    @Test
    fun `a leading run with no room below the next one inserts beneath zero`() {
        // Two cues need two integers below "S1-1", and only zero is available.
        assertEquals(
            mapOf(0 to "S1-0.1", 1 to "S1-0.2"),
            auto(blank(), blank(), explicit("S1-1")),
        )
    }

    @Test
    fun `a leading cue before a decimal insert takes the whole number above it`() {
        // "S1-3" orders before "S1-3.2", so it is still usable as a label.
        assertEquals(mapOf(0 to "S1-3"), auto(blank(), explicit("S1-3.2")))
    }

    @Test
    fun `a leading run counts up to the next one without colliding`() {
        // 1, 2, 3 are free below "S1-4"; the explicit "S1-2" case is covered by the clash test.
        assertEquals(
            mapOf(0 to "S1-1", 1 to "S1-2", 2 to "S1-3"),
            auto(blank(), blank(), blank(), explicit("S1-4")),
        )
    }

    @Test
    fun `a decimal anchor nests one level deeper only when it must`() {
        assertEquals(mapOf(1 to "S1-3.2"), auto(explicit("S1-3.1"), blank()))
        assertEquals(
            mapOf(1 to "S1-3.1.1"),
            auto(explicit("S1-3.1"), blank(), explicit("S1-3.2")),
        )
    }

    @Test
    fun `existing auto numbers are recomputed, explicit ones are left alone`() {
        // The auto cue holds a stale "S1-9" from a previous position; it gets relabelled, while
        // the explicit "S1-3" is untouched (absent from the result map).
        assertEquals(
            mapOf(1 to "S1-4"),
            auto(explicit("S1-3"), "S1-9" as String? to true),
        )
    }

    @Test
    fun `an auto number never blocks another auto number`() {
        // The two autos currently hold "2" and "1"; they swap. Only *explicit* numbers are
        // reserved, so nothing blocks the reassignment. This is the case that would trip
        // uq_cue_number_per_stack without the two-pass write in renumberAutoCues.
        assertEquals(
            mapOf(0 to "1", 1 to "2", 2 to "3"),
            auto(blank(), "2" as String? to true, "1" as String? to true),
        )
    }

    @Test
    fun `a cue with no expressible slot stays blank rather than sorting wrong`() {
        // Between "S1-3" and "S1-3.1" there is nothing this scheme can express: incrementing
        // overshoots, "S1-3.1" is taken, and "S1-3.2" would sort *after* the follower. A number
        // there would make the stack report itself out of order the moment it was written.
        val labels = auto(explicit("S1-3"), blank(), explicit("S1-3.1"))
        assertNull(labels[1])
        assertFalse(detectCueNumbersOutOfOrder(listOf("S1-3", labels[1], "S1-3.1")))
    }

    @Test
    fun `a leading cue with nothing below the boundary stays blank`() {
        // Nothing sorts below an explicit "S1-0", and "S1-0.1" would sort after it.
        val labels = auto(blank(), explicit("S1-0"))
        assertNull(labels[0])
        assertFalse(detectCueNumbersOutOfOrder(listOf(labels[0], "S1-0")))
    }

    @Test
    fun `a label too long for the column is skipped rather than truncated`() {
        // 20 chars of prefix leaves no room for ".1", so the cue stays blank.
        val prefix = "ABCDEFGHIJKLMNOPQR-1" // exactly MAX_CUE_NUMBER_LENGTH
        assertEquals(MAX_CUE_NUMBER_LENGTH, prefix.length)
        val labels = computeAutoCueNumbers(
            listOf(
                AutoNumberInput(prefix, false),
                AutoNumberInput(null, false),
                AutoNumberInput("ABCDEFGHIJKLMNOPQR-2", false),
            ),
        )
        assertNull(labels[1])
    }

    @Test
    fun `a real stack shape - one cue before S1-1 and a tail after S1-4`() {
        // Shape taken from a live "Act 1": an unnumbered pre-show cue, the numbered body of the
        // act, then a tail of unnumbered cues still being written.
        assertEquals(
            mapOf(
                0 to "S1-0",
                6 to "S1-5", 7 to "S1-6", 8 to "S1-7", 9 to "S1-8",
                10 to "S1-9", 11 to "S1-10", 12 to "S1-11", 13 to "S1-12",
            ),
            auto(
                blank(),
                explicit("S1-1"), explicit("S1-2"), explicit("S1-3.1"),
                explicit("S1-3.2"), explicit("S1-4"),
                blank(), blank(), blank(), blank(), blank(), blank(), blank(), blank(),
            ),
        )
    }

    @Test
    fun `an unparseable explicit anchor falls back to the following prefix`() {
        assertEquals(
            mapOf(1 to "S1-1", 2 to "S1-2"),
            auto(explicit("intro"), blank(), blank(), explicit("S1-3")),
        )
    }

    // ─── D11: an auto number stays put while its cue does ───────────────
    //
    // venue-visit plan D11, carried onto stack entries unchanged by cue-model plan D14 (§3.3). The
    // three number-edit cases are the plan's: a number edit moves nothing, so the handler passes
    // the order as it stands and every other auto cue is `kept`.

    private fun autoOf(vararg cues: AutoNumberInput): Map<Int, String> = computeAutoCueNumbers(cues.toList())

    /** An auto cue at the same place as before the change, holding [number]. */
    private fun kept(number: String?) = AutoNumberInput(number, auto = true, keep = true)

    /** An auto cue the change moved (or shifted), still holding the number it had. */
    private fun moved(number: String?) = AutoNumberInput(number, auto = true, keep = false)

    private fun explicitCue(number: String) = AutoNumberInput(number, auto = false)

    /** The stack as it reads: each cue's explicit number, else the label it was given. */
    private fun reads(cues: List<AutoNumberInput>, labels: Map<Int, String>): List<String?> =
        cues.mapIndexed { i, c -> if (!c.auto && !c.number.isNullOrEmpty()) c.number else labels[i] }

    @Test
    fun `D11 — five auto cues, cue 2 set to 4·5, reads 1, 4·5, 3, 4, 5 and out of order`() {
        val cues = listOf(kept("1"), explicitCue("4.5"), kept("3"), kept("4"), kept("5"))
        val read = reads(cues, autoOf(*cues.toTypedArray()))
        assertEquals(listOf("1", "4.5", "3", "4", "5"), read)
        assertTrue(detectCueNumbersOutOfOrder(read), "the banner shows")
    }

    @Test
    fun `D11 — five auto cues, cue 3 set to 10, reads 1, 2, 10, 4, 5 and out of order`() {
        val cues = listOf(kept("1"), kept("2"), explicitCue("10"), kept("4"), kept("5"))
        val read = reads(cues, autoOf(*cues.toTypedArray()))
        assertEquals(listOf("1", "2", "10", "4", "5"), read)
        assertTrue(detectCueNumbersOutOfOrder(read), "the banner shows")
    }

    @Test
    fun `D11 — five auto cues, cue 3 set to 1, takes cue 1's number and leaves the rest`() {
        // `releaseAutoNumber` freed "1" from cue 1, which is the one cue that has to move: the
        // highest free integer below its neighbour is 0. Cues 2, 4 and 5 keep theirs.
        val cues = listOf(kept(null), kept("2"), explicitCue("1"), kept("4"), kept("5"))
        val read = reads(cues, autoOf(*cues.toTypedArray()))
        assertEquals(listOf("0", "2", "1", "4", "5"), read)
        assertTrue(detectCueNumbersOutOfOrder(read), "the banner shows")
    }

    @Test
    fun `D11 — Fix Order moves the renumbered cue and the cues it passed follow their places`() {
        // After `sort-by-cue-number`: the cues whose index the sort changed are not kept.
        val afterHalf = listOf(kept("1"), moved("3"), moved("4"), explicitCue("4.5"), kept("5"))
        assertEquals(listOf("1", "2", "3", "4.5", "5"), reads(afterHalf, autoOf(*afterHalf.toTypedArray())))

        val afterTen = listOf(kept("1"), kept("2"), moved("4"), moved("5"), explicitCue("10"))
        assertEquals(listOf("1", "2", "3", "4", "10"), reads(afterTen, autoOf(*afterTen.toTypedArray())))

        val afterOne = listOf(kept("0"), explicitCue("1"), moved("2"), kept("4"), kept("5"))
        val read = reads(afterOne, autoOf(*afterOne.toTypedArray()))
        assertEquals(listOf("0", "1", "2", "4", "5"), read)
        assertFalse(detectCueNumbersOutOfOrder(read))
    }

    @Test
    fun `D11 — an out-of-order renumber neither anchors nor bounds a later relabel`() {
        // A cue inserted above the renumbered one shifts everything after it, and the shifted cues
        // count through the 10 rather than continuing from it — anchoring on it would relabel them
        // 11, 12 and quietly put the stack back in order around a cue nobody moved.
        val cues = listOf(kept("1"), moved(null), moved("2"), explicitCue("10"), moved("4"), moved("5"))
        assertEquals(
            listOf("1", "2", "3", "10", "5", "6"),
            reads(cues, autoOf(*cues.toTypedArray())),
        )
        // And a cue appended to the renumbered stack takes the next number, nothing else moving.
        val appended = listOf(kept("1"), explicitCue("4.5"), kept("3"), kept("4"), kept("5"), moved(null))
        assertEquals(
            listOf("1", "4.5", "3", "4", "5", "6"),
            reads(appended, autoOf(*appended.toTypedArray())),
        )
    }

    @Test
    fun `D11 — an insert, a delete or a move still relabels the cues it shifts`() {
        val inserted = listOf(kept("1"), moved(null), moved("2"), moved("3"))
        assertEquals(listOf("1", "2", "3", "4"), reads(inserted, autoOf(*inserted.toTypedArray())))

        val deleted = listOf(kept("1"), kept("2"), moved("4"), moved("5"))
        assertEquals(listOf("1", "2", "3", "4"), reads(deleted, autoOf(*deleted.toTypedArray())))

        val swapped = listOf(kept("1"), moved("3"), moved("2"), kept("4"), kept("5"))
        assertEquals(listOf("1", "2", "3", "4", "5"), reads(swapped, autoOf(*swapped.toTypedArray())))
    }

    @Test
    fun `D11 — a stack the old rule numbered reads the same under the new one`() {
        // An existing show's numbers must not change on upgrade. Every shape below is what the
        // derive-from-scratch rule wrote; kept, each is left exactly as it is.
        val act1 = listOf(
            kept("S1-0"),
            explicitCue("S1-1"), explicitCue("S1-2"), explicitCue("S1-3.1"),
            explicitCue("S1-3.2"), explicitCue("S1-4"),
            kept("S1-5"), kept("S1-6"), kept("S1-7"),
        )
        assertEquals(
            listOf("S1-0", "S1-1", "S1-2", "S1-3.1", "S1-3.2", "S1-4", "S1-5", "S1-6", "S1-7"),
            reads(act1, autoOf(*act1.toTypedArray())),
        )
        // The old rule's answer to "cue 3 set to 10": a relabelled stack, now in order, stays so.
        val oldTen = listOf(kept("8"), kept("9"), explicitCue("10"), kept("11"), kept("12"), moved(null))
        assertEquals(
            listOf("8", "9", "10", "11", "12", "13"),
            reads(oldTen, autoOf(*oldTen.toTypedArray())),
        )
        // Derived from scratch, an in-order explicit number still anchors the run after it.
        val scratch = listOf(explicitCue("14"), moved(null), moved(null))
        assertEquals(listOf("14", "15", "16"), reads(scratch, autoOf(*scratch.toTypedArray())))
    }

    @Test
    fun `D11 — a kept number an explicit one has since claimed is relabelled`() {
        // `releaseAutoNumber` normally clears it first; this is the backstop if it did not.
        val cues = listOf(kept("1"), kept("2"), explicitCue("2"))
        val labels = autoOf(*cues.toTypedArray())
        assertEquals("1", labels[0])
        assertNotEquals("2", labels[1])
    }
}
