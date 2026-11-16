package uk.me.cormack.lighting7.backup

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a restore lets out of a backup's archive (cloud-p3 plan D9): only the paths a backup writes. */
class RestoreStagingTest {
    @Test
    fun `the paths a backup writes are restorable`() {
        for (path in listOf("lighting7.db", "extra/local.conf", "prompt-scripts/p-1/a.pdf", "scene-images/p/x.png", "sync/p/repo/.git/HEAD")) {
            assertTrue(RestoreStaging.isRestorablePath(path), path)
        }
    }

    @Test
    fun `a path that would leave the restore's tree, or name anything else, is not`() {
        for (path in listOf(
            "credentials.enc", "../lighting7.db", "/etc/passwd", "sync/../../x", "sync/./x", "sync//x", "sync",
            "prompt-scripts/..", "scene-images/a\\..\\b", "sync/C:/x", "extra/other.conf", "manifest.json",
        )) {
            assertFalse(RestoreStaging.isRestorablePath(path), path)
        }
    }
}
