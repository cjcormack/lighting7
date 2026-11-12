package uk.me.cormack.lighting7.cloud

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudRepoUrlsTest {
    private val base = "https://lighting7.net"

    @Test
    fun `a repo of the cloud's origin, by ids, is the cloud's`() {
        assertEquals(CloudRepoUrls.CloudRepo("01ORG", "01REPO"), CloudRepoUrls.parse("https://lighting7.net/git/01ORG/01REPO.git", base))
        assertTrue(CloudRepoUrls.isCloudRepo("HTTPS://Lighting7.NET:443/git/o/r.git", base))
        assertTrue(CloudRepoUrls.isCloudRepo("http://localhost:5173/git/o/r.git", "http://localhost:5173"))
    }

    @Test
    fun `any other URL is not`() {
        for (url in listOf(
            "https://github.com/owner/repo.git",
            "https://lighting7.net:8443/git/o/r.git",
            "http://lighting7.net/git/o/r.git",
            "https://cloud.lighting7.net/git/o/r.git",
            "https://lighting7.net/git/o/r",
            "https://lighting7.net/git/o/r/x.git",
            "https://lighting7.net/git/o/../r.git",
            "https://x-install-token:secret@lighting7.net/git/o/r.git",
            "https://lighting7.net/git/o/r.git?x=1",
            "file:///tmp/r.git",
            "not a url",
        )) {
            assertFalse(CloudRepoUrls.isCloudRepo(url, base), url)
        }
        assertNull(CloudRepoUrls.parse("https://lighting7.net/git/o/r.git", null), "no cloud configured")
    }
}
