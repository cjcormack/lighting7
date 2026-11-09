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

    private fun auto(vararg cues: String?): Map<Int, String> = computeAutoCueNumbers(cues.toList())

    private fun explicit(number: String): String? = number
    private fun blank(): String? = null

    /** The stack as it reads: each cue's typed number, else the label it was given. */
    private fun reads(vararg cues: String?): List<String?> {
        val labels = auto(*cues)
        return cues.mapIndexed { i, n -> n ?: labels[i] }
    }

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
        // Nor between a typed number and the highest there is, nor between two decimals four places apart.
        assertEquals(emptyMap(), auto(explicit("9999.9999"), blank(), blank()))
        assertEquals(emptyMap(), auto(explicit("2.5"), blank(), explicit("2.5001")))
        // A blank cue is still a step: the cue after it counts on from the typed number.
        assertEquals(listOf("3", null, "3.0001", "4"), reads(explicit("3"), blank(), explicit("3.0001"), blank()))
    }

    @Test
    fun `an auto number is never a decimal when a whole number fits`() {
        val labels = auto(explicit("1"), blank(), blank(), blank(), explicit("20"), blank())
        assertEquals(mapOf(1 to "2", 2 to "3", 3 to "4", 5 to "21"), labels)
    }

    // ─── From position (venue-rig-fixes plan D6–D7) ─────────────────────

    @Test
    fun `an auto cue after a typed number follows it`() {
        // The stack that started it: an auto cue a typed Q30 left behind at Q40.
        assertEquals(listOf("30", "31"), reads(explicit("30"), blank()))
    }

    @Test
    fun `a typed number between autos moves the autos after it`() {
        // Five auto cues, the second typed 4.5.
        val read = reads(blank(), explicit("4.5"), blank(), blank(), blank())
        assertEquals(listOf("1", "4.5", "5", "6", "7"), read)
        assertFalse(detectCueNumbersOutOfOrder(read))
    }

    @Test
    fun `a leading run counts up from one where it fits`() {
        assertEquals(listOf("1", "2", "30"), reads(blank(), blank(), explicit("30")))
        assertEquals(listOf("1", "2", "3"), reads(blank(), blank(), explicit("3")))
        // 29 cues fit the whole numbers from 1 below 30.
        val twentyNine = reads(*arrayOfNulls<String>(29), explicit("30"))
        assertEquals((1..29).map(Int::toString) + "30", twentyNine)
    }

    @Test
    fun `a leading run that fits only from zero takes the highest whole numbers below`() {
        assertEquals(listOf("0", "1"), reads(blank(), explicit("1")))
        assertEquals(listOf("0", "0.5"), reads(blank(), explicit("0.5")))
        // 30 cues before a typed 30 are 0–29.
        val thirty = reads(*arrayOfNulls<String>(30), explicit("30"))
        assertEquals((0..29).map(Int::toString) + "30", thirty)
    }

    @Test
    fun `a typed number too low for the cues before it reads out of place and the run counts through it`() {
        // 38 auto cues before a typed 30, then one more: 1–29 and 31–39, the 30 out of place, and 41.
        val read = reads(*arrayOfNulls<String>(38), explicit("30"), blank())
        assertEquals((1..29).map(Int::toString) + (31..39).map(Int::toString) + "30" + "41", read)
        assertTrue(detectCueNumbersOutOfOrder(read), "the stack offers Fix Order")
        // Two auto cues before a typed 1: neither 1 nor 0 below it holds two.
        assertEquals(listOf("2", "3", "1"), reads(blank(), blank(), explicit("1")))
    }

    @Test
    fun `a typed number not above the one before it is out of place`() {
        // The 3 neither anchors the cue after it nor bounds the run: the run counts on from 5 through it.
        val read = reads(explicit("5"), explicit("3"), blank())
        assertEquals(listOf("5", "3", "7"), read)
        assertTrue(detectCueNumbersOutOfOrder(read))
        // Five auto cues, the third typed 1: out of place after two cues it cannot hold.
        val third = reads(blank(), blank(), explicit("1"), blank(), blank())
        assertEquals(listOf("2", "3", "1", "5", "6"), third)
        assertTrue(detectCueNumbersOutOfOrder(third))
    }

    @Test
    fun `a later typed number in place bounds the run through an earlier one out of place`() {
        // The 30 out of place, the 50 in place: the run before 50 counts from 1 and stops below it.
        val read = reads(*arrayOfNulls<String>(38), explicit("30"), blank(), explicit("50"), blank())
        assertEquals((1..29).map(Int::toString) + (31..39).map(Int::toString) + listOf("30", "41", "50", "51"), read)
    }

    @Test
    fun `typed numbers in order anchor and bound the runs between them`() {
        assertEquals(listOf("10", "11", "12", "20", "21"), reads(explicit("10"), blank(), blank(), explicit("20"), blank()))
        assertEquals(listOf("3", "3.5", "4"), reads(explicit("3"), blank(), explicit("4")))
    }
}
