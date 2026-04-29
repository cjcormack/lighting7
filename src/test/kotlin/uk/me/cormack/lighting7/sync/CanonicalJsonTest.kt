package uk.me.cormack.lighting7.sync

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanonicalJsonTest {

    @Serializable
    private data class Sample(
        val zeta: String,
        val alpha: Int,
        val mid: List<String> = emptyList(),
        val nullable: String? = null,
        val nested: Nested? = null,
    )

    @Serializable
    private data class Nested(val z: Int, val a: Int)

    @Test
    fun `keys are sorted alphabetically including nested objects`() {
        val sample = Sample(
            zeta = "z",
            alpha = 1,
            mid = listOf("x", "y"),
            nested = Nested(z = 9, a = 1),
        )
        val json = canonicalEncode(Sample.serializer(), sample)
        // Top-level keys present and ordered.
        val alphaIdx = json.indexOf("\"alpha\"")
        val midIdx = json.indexOf("\"mid\"")
        val nestedIdx = json.indexOf("\"nested\"")
        val zetaIdx = json.indexOf("\"zeta\"")
        assertTrue(alphaIdx in 0 until midIdx)
        assertTrue(midIdx in 0 until nestedIdx)
        assertTrue(nestedIdx in 0 until zetaIdx)
        // Nested object keys sorted too.
        val nestedAIdx = json.indexOf("\"a\"", nestedIdx)
        val nestedZIdx = json.indexOf("\"z\"", nestedIdx)
        assertTrue(nestedAIdx in 0 until nestedZIdx)
    }

    @Test
    fun `nulls and defaults are omitted`() {
        val sample = Sample(zeta = "z", alpha = 1)  // mid defaults to emptyList(), nullable=null, nested=null
        val json = canonicalEncode(Sample.serializer(), sample)
        assertFalse(json.contains("\"nullable\""), "null fields should be omitted: $json")
        assertFalse(json.contains("\"mid\""), "default fields should be omitted: $json")
        assertFalse(json.contains("\"nested\""), "null nested objects should be omitted: $json")
    }

    @Test
    fun `output ends with a single trailing newline`() {
        val json = canonicalEncode(Sample.serializer(), Sample(zeta = "z", alpha = 1))
        assertTrue(json.endsWith("\n"), "must end with newline")
        assertFalse(json.endsWith("\n\n"), "must not have double newline")
    }

    @Test
    fun `same input encodes to identical bytes across runs`() {
        val sample = Sample(zeta = "z", alpha = 1, mid = listOf("a", "b", "c"))
        val a = canonicalEncode(Sample.serializer(), sample)
        val b = canonicalEncode(Sample.serializer(), sample)
        assertEquals(a, b)
    }

    @Test
    fun `decode returns the original value`() {
        val sample = Sample(zeta = "value", alpha = 42, mid = listOf("a"))
        val text = canonicalEncode(Sample.serializer(), sample)
        val decoded = canonicalDecode(Sample.serializer(), text)
        assertEquals(sample, decoded)
    }

    @Test
    fun `2-space indent is used`() {
        val sample = Sample(zeta = "z", alpha = 1)
        val json = canonicalEncode(Sample.serializer(), sample)
        // First indented line should start with exactly two spaces.
        val firstNewline = json.indexOf('\n')
        assertTrue(firstNewline >= 0)
        val secondLine = json.substring(firstNewline + 1)
        assertTrue(secondLine.startsWith("  "), "expected 2-space indent, got: ${secondLine.take(10)}")
        assertFalse(secondLine.startsWith("   "), "should not be 3+ spaces")
    }
}
