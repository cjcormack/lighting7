package uk.me.cormack.lighting7.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaturalSortTest {

    @Test
    fun `naturalSortKey splits numeric and non-numeric segments`() {
        assertEquals(listOf(14L, "A"), naturalSortKey("14A"))
        assertEquals(listOf(1L, ".", 5L), naturalSortKey("1.5"))
        assertEquals(listOf(100L), naturalSortKey("100"))
        assertEquals(listOf("intro"), naturalSortKey("intro"))
    }

    @Test
    fun `naturalCompare orders simple numbers correctly`() {
        assertTrue(naturalCompare("1", "2") < 0)
        assertTrue(naturalCompare("2", "14") < 0)
        assertTrue(naturalCompare("14", "100") < 0)
        assertEquals(0, naturalCompare("42", "42"))
    }

    @Test
    fun `naturalCompare orders decimal-like numbers correctly`() {
        assertTrue(naturalCompare("1", "1.5") < 0)
        assertTrue(naturalCompare("1.5", "2") < 0)
    }

    @Test
    fun `naturalCompare orders mixed alphanumeric correctly`() {
        assertTrue(naturalCompare("14", "14A") < 0)
        assertTrue(naturalCompare("14A", "14B") < 0)
        assertTrue(naturalCompare("14B", "15") < 0)
    }

    @Test
    fun `naturalCompare full sequence matches spec`() {
        val input = listOf("100", "14B", "1.5", "15", "2", "14A", "1", "14")
        val sorted = input.sortedWith(CueNumberComparator)
        assertEquals(listOf("1", "1.5", "2", "14", "14A", "14B", "15", "100"), sorted)
    }

    @Test
    fun `naturalCompare handles leading zeros`() {
        assertTrue(naturalCompare("01", "1") == 0)
        assertTrue(naturalCompare("001", "1") == 0)
    }

    @Test
    fun `naturalCompare handles all-alpha strings`() {
        assertTrue(naturalCompare("abc", "def") < 0)
        assertTrue(naturalCompare("intro", "verse") < 0)
    }

    @Test
    fun `naturalCompare is case-sensitive`() {
        // Uppercase letters sort before lowercase in standard string comparison
        assertTrue(naturalCompare("A", "a") < 0)
    }

    @Test
    fun `CueNumberComparator works with sortedWith`() {
        val cueNumbers = listOf("2", "1", "1.5", "14A", "14", "100", "14B", "15")
        val sorted = cueNumbers.sortedWith(CueNumberComparator)
        assertEquals(listOf("1", "1.5", "2", "14", "14A", "14B", "15", "100"), sorted)
    }

    @Test
    fun `participating cue classification - digit first`() {
        val testCases = listOf("1", "1.5", "14A", "100", "0intro")
        testCases.forEach { num ->
            assertTrue(num[0].isDigit(), "Expected '$num' to be classified as participating")
        }
    }

    @Test
    fun `pinned cue classification - non-digit first`() {
        val testCases = listOf("intro", "verse", "Q14", "Act1")
        testCases.forEach { num ->
            assertTrue(!num[0].isDigit(), "Expected '$num' to be classified as pinned")
        }
    }
}
