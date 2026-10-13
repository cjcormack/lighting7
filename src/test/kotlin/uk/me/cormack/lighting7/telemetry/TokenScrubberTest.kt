package uk.me.cormack.lighting7.telemetry

import org.junit.Test
import kotlin.test.assertEquals

class TokenScrubberTest {

    private fun assertScrubbed(input: String, expected: String) = assertEquals(expected, TokenScrubber.scrub(input), input)

    @Test
    fun `GitHub tokens are redacted whole`() {
        assertScrubbed("push failed with ghp_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8", "push failed with [redacted]")
        assertScrubbed("token=gho_16C7e42F292c6912E7710c838347Ae178B4a here", "token=[redacted] here")
        assertScrubbed("github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyz", "[redacted]")
        assertScrubbed("ghs_abcdefghijklmnopqrstuvwxyz0123", "[redacted]")
    }

    @Test
    fun `ngrok authtokens are redacted by shape and by key`() {
        assertScrubbed("using 2abcDEFghiJKLmnoPQRstuVWXyz1_7AbCdEfGhIjKlMnOpQrStU", "using [redacted]")
        assertScrubbed("authtoken: hunter2hunter2", "authtoken: [redacted]")
        assertScrubbed("NGROK_AUTHTOKEN=s3cret-value", "NGROK_AUTHTOKEN=[redacted]")
        assertScrubbed("ngrok start --authtoken s3cret-value --log stdout", "ngrok start --authtoken [redacted] --log stdout")
    }

    @Test
    fun `bearer credentials keep the scheme and lose the token`() {
        assertScrubbed("Authorization: Bearer eyJhbGciOi.JIUzI1NiJ9.abc-def_ghi", "Authorization: Bearer [redacted]")
        assertScrubbed("bearer abc123", "bearer [redacted]")
    }

    @Test
    fun `reset and device paths keep the route and lose the token`() {
        assertScrubbed("Bad request on /api/rest/auth/reset/AbC123xyz_-9: expired", "Bad request on /api/rest/auth/reset/[redacted]: expired")
        assertScrubbed("GET /api/rest/auth/device/Zz9-Yy8?x=1", "GET /api/rest/auth/device/[redacted]?x=1")
        // The authenticated mint route is not a token path.
        assertScrubbed("POST /api/rest/auth/device-logins", "POST /api/rest/auth/device-logins")
    }

    @Test
    fun `OAuth codes, tokens and the session cookie are redacted`() {
        assertScrubbed("/oauth/callback?code=abc123&state=xyz789", "/oauth/callback?code=[redacted]&state=[redacted]")
        assertScrubbed("{\"access_token\":\"abc\",\"refresh_token\":\"def\"}", "{\"access_token\":\"[redacted]\",\"refresh_token\":\"[redacted]\"}")
        assertScrubbed("Cookie: lighting7_session=AAAABBBBCCCC; other=1", "Cookie: lighting7_session=[redacted]; other=1")
    }

    @Test
    fun `ordinary log lines pass untouched`() {
        for (line in listOf(
            "Show startup complete",
            "FX engine paused SineWave on front-wash after 5 consecutive failing ticks",
            "Remote access authtoken is stored",
            "Loaded 24/24 built-in effects in 1.2s",
            "Unhandled exception on /api/rest/projects/4/cues/12",
        )) assertScrubbed(line, line)
    }
}
