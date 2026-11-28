package uk.me.cormack.lighting7.promptbook

import org.junit.After
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.HeldExecutor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The index cache beside the script store: its states, its rebuilds and its prune (prompt-book navigation plan §3). */
class ScriptTextStoreTest {

    private val root: Path = Files.createTempDirectory("script-text-")
    private val project = "11111111-1111-1111-1111-111111111111"
    private val executor = HeldExecutor()
    private val store = ScriptTextStore({ uuid, hash -> root.resolve(uuid).resolve("$hash.pdf") }, executor)
    private val hash = "c".repeat(64)

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun placeScript(name: String = "typed-script.pdf", as_: String = hash): Path {
        val src = Paths.get(checkNotNull(javaClass.getResource("/promptBook/$name")).toURI())
        val dst = root.resolve(project).resolve("$as_.pdf")
        Files.createDirectories(dst.parent)
        Files.copy(src, dst)
        return dst
    }

    @Test
    fun `no PDF is script missing, never an empty index`() {
        assertEquals(ScriptTextState.ScriptMissing, store.state(project, hash))
        assertTrue(executor.queued.isEmpty(), "nothing to build")
    }

    @Test
    fun `an absent index is building until the background build lands, then ready`() {
        placeScript()
        assertEquals(ScriptTextState.Building, store.state(project, hash))
        assertEquals(ScriptTextState.Building, store.state(project, hash))
        assertEquals(1, executor.queued.size, "asking twice queues one build")
        executor.runAll()
        val ready = assertIs<ScriptTextState.Ready>(store.state(project, hash))
        assertEquals(3, ready.index.pageCount)
        assertTrue(store.indexPath(project, hash).exists())
    }

    @Test
    fun `a deleted index is rebuilt`() {
        placeScript()
        store.request(project, hash)
        executor.runAll()
        Files.delete(store.indexPath(project, hash))
        assertEquals(ScriptTextState.Building, store.state(project, hash))
        executor.runAll()
        assertIs<ScriptTextState.Ready>(store.state(project, hash))
    }

    @Test
    fun `an older extractor's index is rebuilt`() {
        placeScript()
        Files.writeString(
            store.indexPath(project, hash),
            """{"version":0,"scriptHash":"$hash","pageCount":3,"pages":[]}""",
        )
        assertEquals(ScriptTextState.Building, store.state(project, hash))
        executor.runAll()
        assertEquals(ScriptTextIndex.VERSION, assertIs<ScriptTextState.Ready>(store.state(project, hash)).index.version)
    }

    @Test
    fun `a PDF PDFBox cannot read is unreadable, and is not read again`() {
        val pdf = root.resolve(project).resolve("$hash.pdf")
        Files.createDirectories(pdf.parent)
        Files.writeString(pdf, "%PDF-1.4 this is not a PDF")
        store.request(project, hash)
        executor.runAll()
        assertIs<ScriptTextState.Unreadable>(store.state(project, hash))
        assertTrue(executor.queued.isEmpty())
    }

    @Test
    fun `an index whose PDF has left the store is pruned`() {
        val other = "d".repeat(64)
        placeScript()
        placeScript(as_ = other)
        store.request(project, hash)
        store.request(project, other)
        executor.runAll()
        Files.delete(root.resolve(project).resolve("$other.pdf"))
        val halfWritten = Files.writeString(root.resolve(project).resolve(".text-123.tmp"), "{")
        assertEquals(2, store.pruneOrphans(project))
        assertTrue(store.indexPath(project, hash).exists())
        assertFalse(store.indexPath(project, other).exists())
        assertFalse(halfWritten.exists(), "a write a crash left half done")
        assertEquals(0, store.pruneOrphans("22222222-2222-2222-2222-222222222222"), "a project with no store")
    }

    @Test
    fun `only the store's own files are an index`() {
        assertTrue(ScriptTextStore.isIndexFile("$hash.text.json"))
        assertTrue(ScriptTextStore.isIndexFile(".text-123.tmp"))
        assertFalse(ScriptTextStore.isIndexFile("$hash.pdf"))
    }
}
