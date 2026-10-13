package uk.me.cormack.lighting7.telemetry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cases are `src/test/resources/telemetry/scrubCases.fixture.json`, which the browser's
 * `lib/sentryScrub.test.ts` runs too: the two scrubbers share their patterns, and this pins that
 * they apply them alike.
 */
class TokenScrubberTest {

    @Serializable
    private data class Case(val input: String, val expected: String? = null)

    @Serializable
    private data class Cases(val cases: List<Case>)

    private val json = Json { ignoreUnknownKeys = true }

    private val cases = json.decodeFromString<Cases>(
        checkNotNull(javaClass.getResource("/telemetry/scrubCases.fixture.json")) { "the shared cases are missing" }.readText(),
    ).cases

    @Test
    fun `every shared case scrubs as the fixture says`() {
        assertTrue(cases.size > 20)
        for (case in cases) assertEquals(case.expected ?: case.input, TokenScrubber.scrub(case.input), case.input)
    }

    @Serializable
    private data class Pattern(val about: String, val pattern: String, val ignoreCase: Boolean = false)

    @Serializable
    private data class Patterns(val patterns: List<Pattern>)

    @Test
    fun `every pattern has a case that it redacts`() {
        val patterns = json.decodeFromString<Patterns>(
            checkNotNull(javaClass.getResource("/telemetry/secret-patterns.json")).readText(),
        ).patterns
        for (p in patterns) {
            val regex = if (p.ignoreCase) Regex(p.pattern, RegexOption.IGNORE_CASE) else Regex(p.pattern)
            assertTrue(cases.any { it.expected != null && regex.containsMatchIn(it.input) }, "no case for: ${p.about}")
        }
    }
}
