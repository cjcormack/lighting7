package uk.me.cormack.lighting7.telemetry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Redacts the token shapes the desk handles before any text leaves for Sentry: GitHub tokens,
 * ngrok authtokens, bearer credentials, the session cookie, OAuth codes and tokens, and the
 * one-time tokens in reset and device-login links. The patterns are
 * `resources/telemetry/secret-patterns.json`, which the browser's scrubber compiles too.
 */
object TokenScrubber {
    @Serializable
    private data class SecretPatterns(val replacement: String, val patterns: List<SecretPattern>)

    @Serializable
    private data class SecretPattern(val pattern: String, val ignoreCase: Boolean = false)

    private val json = Json { ignoreUnknownKeys = true }

    private val secrets: SecretPatterns = json.decodeFromString(
        checkNotNull(TokenScrubber::class.java.getResource("/telemetry/secret-patterns.json")) { "telemetry/secret-patterns.json is missing" }.readText(),
    )

    val REDACTED: String = secrets.replacement

    // Each pattern's group 1, when present, is kept; the rest of the match is redacted.
    private val patterns = secrets.patterns.map { p ->
        if (p.ignoreCase) Regex(p.pattern, RegexOption.IGNORE_CASE) else Regex(p.pattern)
    }

    fun scrub(text: String): String = patterns.fold(text) { acc, pattern ->
        pattern.replace(acc) { m -> m.groupValues.getOrElse(1) { "" } + REDACTED }
    }

    fun scrubOrNull(text: String?): String? = text?.let(::scrub)
}
