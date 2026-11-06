package uk.me.cormack.lighting7.routes

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the pure half of `cueNumbering.kt`: the numeric parse, the ordering, and auto numbering. */
class CueNumberingTest {

    // ─── Parse ──────────────────────────────────────────────────────────

    @Test
    fun `a cue number is a number with at most four decimal places`() {
        assertEquals("1", canonicalCueNumber("1"))
        assertEquals("1.5", canonicalCueNumber("1.5"))
        assertEquals("9999.9999", canonicalCueNumber("9999.9999"))
        assertEquals("0", canonicalCueNumber("0"))
        assertEquals("0.5", canonicalCueNumber(".5"))
        assertEquals("3", canonicalCueNumber("3."))
        assertEquals("12", canonicalCueNumber(" 12 "))
        assertEquals("12", canonicalCueNumber("Q12"))
    }

    @Test
    fun `one number has one spelling`() {
        assertEquals("2.1", canonicalCueNumber("2.10"))
        assertEquals("2.1", canonicalCueNumber("2.10000"))
        assertEquals("7", canonicalCueNumber("007"))
        assertEquals("10", canonicalCueNumber("10.0"))
        assertEquals("100", canonicalCueNumber("100"))
    }

    @Test
    fun `anything else is not a cue number`() {
        for (raw in listOf("", ".", "A", "intro", "14A", "S1-3", "1.2.3", "-1", "1e3", "10000", "1.23456", "Q")) {
            assertNull(canonicalCueNumber(raw), raw)
        }
    }

    @Test
    fun `a typed number is blank, canonical or refused with the rule`() {
        assertNull(parseTypedCueNumber("  ").getOrThrow())
        assertNull(parseTypedCueNumber(null).getOrThrow())
        assertEquals("4.5", parseTypedCueNumber("4.50").getOrThrow())
        val refused = parseTypedCueNumber("14A").exceptionOrNull()
        assertTrue(refused?.message.orEmpty().startsWith(CUE_NUMBER_RULE))
    }

    // ─── Ordering ───────────────────────────────────────────────────────

    @Test
    fun `numbers sort by value`() {
        assertEquals(
            listOf("1", "1.5", "2", "2.05", "2.1", "2.15", "2.9", "9", "10", "14", "100"),
            numericCueOrder(listOf("100", "2.9", "14", "1.5", "2.15", "10", "2.1", "9", "2", "2.05", "1")) { it },
        )
    }

    @Test
    fun `blanks keep their places in a sort`() {
        assertEquals(listOf("1", null, "2", null, "3"), numericCueOrder(listOf("3", null, "1", null, "2")) { it })
    }

    @Test
    fun `out of order is any number lower than one before it`() {
        assertFalse(detectCueNumbersOutOfOrder(listOf("1", "2", "2.5", "10")))
        assertTrue(detectCueNumbersOutOfOrder(listOf("1", "10", "9")))
        assertTrue(detectCueNumbersOutOfOrder(listOf("2.2", "2.15")))
        assertFalse(detectCueNumbersOutOfOrder(listOf("1", null, "", "2")))
    }

    @Test
    fun `a number reads out of order against the nearest numbered cue either side`() {
        val numbers = listOf("1", null, "3", "4", "5")
        assertNull(outOfOrderAt(numbers, 1, BigDecimal("2")))
        assertEquals(OutOfOrderNeighbours(previous = null, next = "3"), outOfOrderAt(numbers, 1, BigDecimal("4.5")))
        assertEquals(OutOfOrderNeighbours(previous = "1", next = null), outOfOrderAt(numbers, 1, BigDecimal("0.5")))
        assertEquals(OutOfOrderNeighbours(previous = "5", next = null), outOfOrderAt(numbers, 5, BigDecimal("2")))
        assertNull(outOfOrderAt(numbers, 5, BigDecimal("6")))
        assertNull(outOfOrderAt(emptyList(), 0, BigDecimal("6")))
    }

    @Test
    fun `a number's place is after the last cue below it`() {
        val numbers = listOf("1", null, "3", "4", "5")
        assertEquals(4, positionForNumber(numbers, BigDecimal("4.5")) { it })
        assertEquals(0, positionForNumber(numbers, BigDecimal("0.5")) { it })
        assertEquals(5, positionForNumber(numbers, BigDecimal("9")) { it })
        assertEquals(1, positionForNumber(numbers, BigDecimal("2")) { it })
        assertNull(positionForNumber(listOf<String?>(null, null), BigDecimal("2")) { it })
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
    fun `a run after a number takes the whole numbers after it`() {
        assertEquals(mapOf(1 to "4", 2 to "5"), auto(explicit("3"), blank(), blank()))
        assertEquals(mapOf(1 to "4", 2 to "5"), auto(explicit("3.5"), blank(), blank()))
    }

    @Test
    fun `a run skips a whole number used elsewhere in the stack`() {
        // The 5 further down is taken, so the run steps over it.
        assertEquals(mapOf(1 to "4", 2 to "6"), auto(explicit("3"), blank(), blank(), explicit("10"), explicit("5")).filterKeys { it < 3 })
    }

    @Test
    fun `a run fits between two numbers with whole numbers when it can`() {
        assertEquals(mapOf(1 to "4", 2 to "5"), auto(explicit("3"), blank(), blank(), explicit("10")))
    }

    @Test
    fun `with no whole number free, a cue goes at the midpoint`() {
        assertEquals(mapOf(1 to "3.5"), auto(explicit("3"), blank(), explicit("4")))
        assertEquals(mapOf(1 to "3.25"), auto(explicit("3"), blank(), explicit("3.5")))
        assertEquals(mapOf(1 to "3.05"), auto(explicit("3"), blank(), explicit("3.1")))
    }

    @Test
    fun `several cues with no whole number free are spaced evenly in the fewest places`() {
        assertEquals(mapOf(1 to "3.3", 2 to "3.7"), auto(explicit("3"), blank(), blank(), explicit("4")))
        assertEquals(mapOf(1 to "3.25", 2 to "3.5", 3 to "3.75"), auto(explicit("3"), blank(), blank(), blank(), explicit("4")))
        // One whole number free below 5 is not enough for two: the 4, then the midpoint after it.
        assertEquals(mapOf(1 to "4", 2 to "4.5"), auto(explicit("3"), blank(), blank(), explicit("5")))
        assertEquals(mapOf(1 to "2", 2 to "3", 3 to "3.5"), auto(explicit("1"), blank(), blank(), blank(), explicit("4")))
    }

    @Test
    fun `an auto number never passes the highest cue number`() {
        assertEquals(mapOf(1 to "9999.5"), auto(explicit("9999"), blank()))
        assertEquals(mapOf(1 to "9999.3", 2 to "9999.7"), auto(explicit("9999"), blank(), blank()))
        assertEquals(mapOf(1 to "9999.9999"), auto(explicit("9999.9998"), blank()))
        assertEquals(emptyMap(), auto(explicit("9999.9999"), blank()))
        for (label in auto(explicit("9990"), blank(), blank(), blank(), blank(), blank(), blank(), blank(), blank(), blank(), blank(), blank()).values) {
            assertTrue(canonicalCueNumber(label) == label, label)
        }
    }

    @Test
    fun `a cue with no room even in four places stays blank`() {
        assertEquals(emptyMap(), auto(explicit("3"), blank(), explicit("3.0001")))
    }

    @Test
    fun `a run with nothing before it counts up to the cue that follows`() {
        assertEquals(mapOf(0 to "1", 1 to "2"), auto(blank(), blank(), explicit("3")))
        assertEquals(mapOf(0 to "0"), auto(blank(), explicit("1")))
    }

    @Test
    fun `two cues before 1 are 0 and the midpoint after it`() {
        assertEquals(mapOf(0 to "0", 1 to "0.5"), auto(blank(), blank(), explicit("1")))
    }

    @Test
    fun `an auto number is never a decimal when a whole number fits`() {
        val labels = auto(explicit("1"), blank(), blank(), blank(), explicit("20"), blank())
        assertEquals(mapOf(1 to "2", 2 to "3", 3 to "4", 5 to "21"), labels)
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
        // A stack's numbers do not change unless its list does. Every shape below is what the
        // derive-from-scratch rule writes; kept, each is left exactly as it is.
        val act1 = listOf(
            kept("0"),
            explicitCue("1"), explicitCue("2"), explicitCue("3.1"),
            explicitCue("3.2"), explicitCue("4"),
            kept("5"), kept("6"), kept("7"),
        )
        assertEquals(
            listOf("0", "1", "2", "3.1", "3.2", "4", "5", "6", "7"),
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
