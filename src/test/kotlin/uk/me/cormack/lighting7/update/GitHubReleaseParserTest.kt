package uk.me.cormack.lighting7.update

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubReleaseParserTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/update/$name")) { "missing fixture $name" }
            .bufferedReader().readText()

    @Test
    fun `parses a captured releases-latest payload`() {
        val release = assertNotNull(parseRelease(fixture("releases-latest.json")))

        assertEquals("v1.2.0", release.tag)
        assertEquals("v1.2.0", release.name)
        assertEquals("https://github.com/cjcormack/lighting7/releases/tag/v1.2.0", release.htmlUrl)
        assertEquals(java.time.Instant.parse("2026-08-10T18:41:07Z").toEpochMilli(), release.publishedAtMs)
        assertTrue(release.notes!!.contains("not code-signed"))
    }

    @Test
    fun `selects the msi and pairs its checksum`() {
        val release = assertNotNull(parseRelease(fixture("releases-latest.json")))

        val installer = assertNotNull(release.installer)
        assertEquals("lighting7-1.2.0-windows-x64.msi", installer.name)
        assertEquals(351234560L, installer.sizeBytes)
        assertTrue(installer.downloadUrl.startsWith("https://github.com/"))

        val checksum = assertNotNull(release.checksum)
        assertEquals("lighting7-1.2.0-windows-x64.msi.sha256", checksum.name)
    }

    /**
     * GitHub adds response fields routinely. If an unknown key threw, a field added upstream
     * would break "check for updates" on every already-installed desk at once.
     */
    @Test
    fun `tolerates unknown fields`() {
        val json = """
            {
              "tag_name": "v9.9.9",
              "html_url": "https://example.invalid/r",
              "some_brand_new_field": {"nested": [1, 2, 3]},
              "assets": [
                {"name": "lighting7-9.9.9-windows-x64.msi", "size": 10,
                 "browser_download_url": "https://example.invalid/a.msi",
                 "another_new_field": true}
              ]
            }
        """.trimIndent()

        val release = assertNotNull(parseRelease(json))
        assertEquals("v9.9.9", release.tag)
        assertEquals("lighting7-9.9.9-windows-x64.msi", release.installer?.name)
    }

    @Test
    fun `a release with no msi yields a null installer rather than failing`() {
        val json = """
            {"tag_name": "v1.0.1", "html_url": "https://example.invalid/r",
             "assets": [{"name": "notes.txt", "size": 3, "browser_download_url": "https://example.invalid/n"}]}
        """.trimIndent()

        val release = assertNotNull(parseRelease(json))
        assertNull(release.installer)
        assertNull(release.checksum)
    }

    @Test
    fun `a checksum that does not name the installer is not paired to it`() {
        val json = """
            {"tag_name": "v1.0.1", "html_url": "https://example.invalid/r", "assets": [
              {"name": "lighting7-1.0.1-windows-x64.msi", "size": 10, "browser_download_url": "https://example.invalid/a.msi"},
              {"name": "some-other-artifact.zip.sha256", "size": 9, "browser_download_url": "https://example.invalid/o"}
            ]}
        """.trimIndent()

        val release = assertNotNull(parseRelease(json))
        assertNotNull(release.installer)
        assertNull(release.checksum)
    }

    @Test
    fun `drafts and prereleases are rejected`() {
        val draft = """{"tag_name": "v2.0.0", "draft": true, "html_url": "https://example.invalid/r"}"""
        val pre = """{"tag_name": "v2.0.0", "prerelease": true, "html_url": "https://example.invalid/r"}"""
        assertNull(parseRelease(draft))
        assertNull(parseRelease(pre))
    }

    @Test
    fun `garbage and a missing tag yield null`() {
        assertNull(parseRelease("not json at all"))
        assertNull(parseRelease("""{"html_url": "https://example.invalid/r"}"""))
        assertNull(parseRelease("""{"tag_name": "  ", "html_url": "https://example.invalid/r"}"""))
    }

    @Test
    fun `release notes are capped`() {
        val huge = "x".repeat(MAX_RELEASE_NOTES_CHARS * 2)
        val json = """{"tag_name": "v1.0.1", "html_url": "https://example.invalid/r", "body": "$huge"}"""
        val release = assertNotNull(parseRelease(json))
        assertEquals(MAX_RELEASE_NOTES_CHARS, release.notes?.length)
    }

    @Test
    fun `sha256 asset parsing accepts every shape the workflow or a human might produce`() {
        val digest = "a".repeat(64)
        listOf(
            "$digest  lighting7-1.2.0-windows-x64.msi\n",
            "$digest  lighting7-1.2.0-windows-x64.msi\r\n",
            digest,
            "$digest\n",
            digest.uppercase(),
            "SHA256: $digest",
        ).forEach { raw ->
            assertEquals(digest, parseSha256Asset(raw), "failed on: ${raw.take(20)}…")
        }
    }

    @Test
    fun `sha256 asset parsing rejects anything that is not a digest`() {
        listOf("", "not a hash", "a".repeat(63), "z".repeat(64), "<html>404</html>").forEach { raw ->
            assertNull(parseSha256Asset(raw), "should not have parsed: $raw")
        }
    }
}
