package uk.me.cormack.lighting7.update

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemVerTest {
    private fun v(raw: String): SemVer = assertNotNull(SemVer.parse(raw), "expected '$raw' to parse")

    @Test
    fun `parses a plain version`() {
        val parsed = v("1.4.2")
        assertEquals(1, parsed.major)
        assertEquals(4, parsed.minor)
        assertEquals(2, parsed.patch)
        assertTrue(parsed.prerelease.isEmpty())
        assertNull(parsed.build)
    }

    @Test
    fun `tolerates the v prefix release tags carry`() {
        assertEquals(v("1.4.2"), v("v1.4.2"))
        assertEquals(v("1.4.2"), v("V1.4.2"))
        assertEquals(v("1.4.2"), v("  v1.4.2  "))
    }

    @Test
    fun `zero-fills a missing minor or patch`() {
        assertEquals(v("1.2.0"), v("v1.2"))
        assertEquals(v("1.0.0"), v("v1"))
    }

    @Test
    fun `parses prerelease and build metadata`() {
        val parsed = v("1.0.0-rc.1+build.5")
        assertEquals(listOf("rc", "1"), parsed.prerelease)
        assertEquals("build.5", parsed.build)
        assertTrue(parsed.isPrerelease)
    }

    /**
     * The precedence chain from the SemVer 2.0.0 spec (§11), verbatim. Every adjacent pair must
     * compare strictly less-than, and the whole list must already be in sorted order.
     */
    @Test
    fun `implements the spec precedence chain`() {
        val ascending = listOf(
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
        ).map { v(it) }

        ascending.zipWithNext().forEach { (lower, higher) ->
            assertTrue(lower < higher, "expected $lower < $higher")
            assertTrue(higher > lower, "expected $higher > $lower")
        }
        assertEquals(ascending, ascending.shuffled().sorted())
    }

    @Test
    fun `numeric prerelease identifiers compare numerically not lexically`() {
        // The classic trap: "11" sorts before "2" as a string.
        assertTrue(v("1.0.0-beta.2") < v("1.0.0-beta.11"))
    }

    @Test
    fun `numeric prerelease identifiers rank below alphanumeric ones`() {
        assertTrue(v("1.0.0-1") < v("1.0.0-alpha"))
    }

    @Test
    fun `a prerelease ranks below its own release`() {
        assertTrue(v("1.0.0-rc.1") < v("1.0.0"))
        assertTrue(v("2.0.0-rc.1") > v("1.9.9"))
    }

    @Test
    fun `build metadata is ignored for precedence`() {
        assertEquals(0, v("1.0.0+aaa").compareTo(v("1.0.0+zzz")))
        assertEquals(0, v("1.0.0").compareTo(v("1.0.0+build.9")))
    }

    @Test
    fun `core fields dominate in order`() {
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertTrue(v("1.3.0") > v("1.2.99"))
        assertTrue(v("1.2.4") > v("1.2.3"))
    }

    @Test
    fun `returns null rather than guessing on garbage`() {
        listOf(
            null, "", "   ", "v", "banana", "1.2.3.4", "1.-2.3", "-1.2.3",
            "1.2.3-", "1.2.3-rc..1", "1.2.3 rc1", "1.2.3-hotfix wednesday", "x1.2.3",
        ).forEach { raw ->
            assertNull(SemVer.parse(raw), "expected '$raw' to fail to parse")
        }
    }

    @Test
    fun `round-trips through toString`() {
        listOf("1.4.2", "1.0.0-rc.1", "1.0.0-rc.1+build.5", "2.0.0+sha.abc").forEach { raw ->
            assertEquals(raw, v(raw).toString())
        }
    }
}
