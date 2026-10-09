package uk.me.cormack.lighting7.sync

import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.sceneJpeg
import uk.me.cormack.lighting7.testsupport.scenePng
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `SceneImageRepoSync` (scrim plan P2): the tree reconciled to exactly the referenced images, never
 * losing a referenced one this install lacks, and the store hydrated from a tree.
 */
class SceneImageRepoSyncTest {

    private lateinit var state: State
    private lateinit var storeRoot: Path
    private lateinit var tree: Path
    private val project = UUID.randomUUID()

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        storeRoot = Files.createTempDirectory("scene-store-")
        tree = Files.createTempDirectory("scene-tree-")
        state = State(testAppConfig("stage.sceneImageStoreRoot" to storeRoot.toString()))
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        storeRoot.toFile().deleteRecursively()
        tree.toFile().deleteRecursively()
    }

    @Test
    fun `reconcile copies what is referenced, drops what is not, and keeps a referenced image it cannot copy`() {
        val held = state.sceneImages.store(project.toString(), scenePng(16, 8, alpha = true), "image/png")
        val peers = scenePng(16, 8, seed = 4)
        val peersHash = RecordHasher.sha256Hex(peers)
        val dir = tree.resolve(RecordHasher.SCENE_IMAGES_DIR)
        Files.createDirectories(dir)
        // A peer's image this install never held, an image nothing names any more, and junk.
        Files.write(dir.resolve("$peersHash.png"), peers)
        val orphan = dir.resolve("${"0".repeat(64)}.jpg").also { Files.write(it, sceneJpeg(8, 8)) }
        val junk = dir.resolve("notes.txt").also { Files.writeString(it, "hi") }

        SceneImageRepoSync.reconcileTree(state, project, setOf(held.hash, peersHash), tree)

        assertContentEquals(
            Files.readAllBytes(state.sceneImages.original(project.toString(), held.hash)!!.first),
            Files.readAllBytes(dir.resolve("${held.hash}.png")),
        )
        assertTrue(Files.exists(dir.resolve("$peersHash.png")), "a referenced image the store lacks stays in the repo")
        assertFalse(Files.exists(orphan))
        assertFalse(Files.exists(junk))
        assertEquals(2, Files.list(dir).use { it.count() })
    }

    @Test
    fun `hydrate fills the store from a tree, checking each image is what its name says`() {
        val good = sceneJpeg(12, 6)
        val goodHash = RecordHasher.sha256Hex(good)
        val dir = tree.resolve(RecordHasher.SCENE_IMAGES_DIR)
        Files.createDirectories(dir)
        Files.write(dir.resolve("$goodHash.jpg"), good)
        Files.write(dir.resolve("${"1".repeat(64)}.png"), good)
        Files.writeString(dir.resolve("README"), "not an image")

        SceneImageRepoSync.hydrateStore(state, project, tree)

        assertEquals(setOf(goodHash), state.sceneImages.storedHashes(project.toString()))
        assertContentEquals(good, Files.readAllBytes(state.sceneImages.original(project.toString(), goodHash)!!.first))
    }
}
