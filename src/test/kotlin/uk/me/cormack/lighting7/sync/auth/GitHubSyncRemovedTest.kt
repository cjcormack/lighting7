package uk.me.cormack.lighting7.sync.auth

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.assertTrue

/**
 * GitHub sync is gone (cloud-p2 plan D8): no source names its OAuth routes or the frontend's calls to
 * them. The built bundle under `src/main/resources/static` is the build's, rebuilt from `frontend/src`.
 */
class GitHubSyncRemovedTest {

    @Test
    fun `nothing under src or frontend's src names oauth-github`() {
        val needle = "oauth" + "/github"
        val roots = listOf(Path.of("src"), Path.of("frontend", "src"))
        roots.forEach { assertTrue(Files.isDirectory(it), "$it is not where the test runs from") }
        val naming = roots.flatMap { root ->
            Files.walk(root).use { paths ->
                paths.filter { it.isRegularFile() }
                    .filter { !it.invariantSeparatorsPathString.startsWith("src/main/resources/static/") }
                    .filter { runCatching { needle in it.readText() }.getOrDefault(false) }
                    .map { it.invariantSeparatorsPathString }
                    .toList()
            }
        }
        assertTrue(naming.isEmpty(), "These still name $needle: $naming")
    }
}
