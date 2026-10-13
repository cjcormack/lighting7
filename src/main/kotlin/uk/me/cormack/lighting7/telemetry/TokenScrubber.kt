package uk.me.cormack.lighting7.telemetry

/**
 * Redacts the token shapes the desk handles before any text leaves for Sentry: GitHub tokens,
 * ngrok authtokens, bearer credentials, the session cookie, OAuth codes and tokens, and the
 * one-time tokens in `/auth/reset/{token}` and `/auth/device/{token}` paths.
 */
object TokenScrubber {
    const val REDACTED = "[redacted]"

    // Each pattern's group 1, when present, is kept; the rest of the match is redacted.
    private val patterns = listOf(
        Regex("""\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"""),
        Regex("""(?i)(\bbearer\s+)[A-Za-z0-9._~+/=-]+"""),
        // An ngrok authtoken: `<27 alphanumerics>_<21+ alphanumerics>`, starting with a digit.
        Regex("""\b[0-9][A-Za-z0-9]{20,}_[A-Za-z0-9]{15,}\b"""),
        Regex("""(?i)(\b(?:ngrok_)?authtoken["']?\s*[:=]\s*["']?|--authtoken[=\s]+)[^\s"',;&]+"""),
        Regex("""(?i)(\b(?:access|refresh|id)_token["']?\s*[:=]\s*["']?)[^\s"',;&]+"""),
        Regex("""(/auth/(?:reset|device)/)[A-Za-z0-9_~.%-]+"""),
        Regex("""([?&](?:code|state|token)=)[^&\s#"']+"""),
        Regex("""(lighting7_session=)[^;\s"']+"""),
    )

    fun scrub(text: String): String = patterns.fold(text) { acc, pattern ->
        pattern.replace(acc) { m -> m.groupValues.getOrElse(1) { "" } + REDACTED }
    }

    fun scrubOrNull(text: String?): String? = text?.let(::scrub)
}
