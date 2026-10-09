package uk.me.cormack.lighting7.state

import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.sync.RecordHasher
import uk.me.cormack.lighting7.testsupport.pngHeaderOnly
import uk.me.cormack.lighting7.testsupport.sceneJpeg
import uk.me.cormack.lighting7.testsupport.scenePng
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scene-image store (scrim plan §3.3): what it takes and refuses, the copies it derives, and
 * what its prune keeps. The routes over it are `SceneImageRoutesTest`'s.
 */
class SceneImageStoreTest {

    private lateinit var root: Path
    private lateinit var store: SceneImageStore
    private val project = "4b6e1f1c-58ab-4e5e-9a54-0e2b5d0c9f11"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("scene-images-")
        store = SceneImageStore(root)
    }

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `stores an image under its hash, once, and answers what it is`() {
        val bytes = scenePng(300, 150, alpha = true)
        val info = store.store(project, bytes, "image/png")
        assertEquals(RecordHasher.sha256Hex(bytes), info.hash)
        assertEquals(SceneImageInfo(info.hash, 300, 150, hasAlpha = true, mediaType = "image/png"), info)
        val path = store.path(project, info.hash, SceneImageFormat.PNG)
        assertContentEquals(bytes, Files.readAllBytes(path))

        // Idempotent: the same bytes answer the same image and are not written again.
        Files.setLastModifiedTime(path, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")))
        assertEquals(info, store.store(project, bytes, "image/png; charset=binary"))
        assertTrue(Files.getLastModifiedTime(path).toInstant().isAfter(Instant.parse("2025-01-01T00:00:00Z")), "a re-upload touches it")
        assertEquals(listOf(info), store.list(project))

        val jpeg = store.store(project, sceneJpeg(64, 48), "image/jpeg")
        assertEquals(SceneImageFormat.JPEG.mediaType, jpeg.mediaType)
        assertFalse(jpeg.hasAlpha, "a JPEG has no alpha")
        assertTrue(Files.isRegularFile(store.path(project, jpeg.hash, SceneImageFormat.JPEG)))
        assertEquals(setOf(info.hash, jpeg.hash), store.storedHashes(project))
    }

    @Test
    fun `an alpha channel with no transparent pixel is not a hole`() {
        assertFalse(store.store(project, scenePng(40, 20, alpha = true, holes = false), "image/png").hasAlpha)
    }

    @Test
    fun `refuses what is not a PNG or a JPEG, and a body that is not what its type says`() {
        val png = scenePng(10, 10)
        assertRefused("PNG or a JPEG") { store.store(project, png, "image/webp") }
        assertRefused("PNG or a JPEG") { store.store(project, png, null) }
        assertRefused("neither a PNG nor a JPEG") { store.store(project, "GIF89a.....".toByteArray(), "image/png") }
        assertRefused("not the image/jpeg") { store.store(project, png, "image/jpeg") }
        // A PNG signature over garbage does not decode.
        assertRefused("does not decode") {
            store.store(project, png.copyOfRange(0, 8) + ByteArray(200) { 7 }, "image/png")
        }
        assertTrue(store.storedHashes(project).isEmpty(), "nothing refused is stored")
    }

    @Test
    fun `refuses an image over the size cap as too large`() {
        val e = assertFailsWith<SceneImageException> {
            store.store(project, scenePng(4, 4) + ByteArray(SceneImageStore.MAX_UPLOAD_BYTES), "image/png")
        }
        assertTrue(e.tooLarge)
        assertEquals(SceneImageStore.CODE_INVALID, e.code)
    }

    @Test
    fun `reads the size from the header before decoding, so a huge claim is refused cheaply`() {
        // A header and no pixels: decoding it would fail, so the size answer proves the header came first.
        assertRefused("is 9000 × 10 px") { store.store(project, pngHeaderOnly(9000, 10), "image/png") }
        assertRefused("is 100000 × 100000 px") { store.store(project, pngHeaderOnly(100_000, 100_000), "image/png") }
        // A header within the cap goes on to the decode — and this one has nothing to decode.
        assertRefused("does not decode") { store.store(project, pngHeaderOnly(8192, 10), "image/png") }
        // 8192 a side is allowed.
        assertEquals(8192, store.store(project, scenePng(8192, 2), "image/png").width)
    }

    @Test
    fun `derives the display and detail copies by halving, keeping alpha`() {
        val small = store.store(project, scenePng(1000, 500, alpha = true), "image/png")
        val display = readPng(store.variant(project, small.hash, SceneImageVariant.DISPLAY)!!)
        assertEquals(1000 to 500, display.width to display.height, "an image under 2048 keeps its size")
        assertTrue(display.colorModel.hasAlpha())
        assertEquals(0, display.getRGB(10, 10) ushr 24, "the hole stays a hole")
        assertEquals(255, display.getRGB(900, 400) ushr 24)

        val big = store.store(project, scenePng(5000, 2500, alpha = true), "image/png")
        val d = readPng(store.variant(project, big.hash, SceneImageVariant.DISPLAY)!!)
        assertEquals(2048 to 1024, d.width to d.height)
        assertTrue(d.colorModel.hasAlpha())
        assertEquals(0, d.getRGB(5, 5) ushr 24)
        assertEquals(255, d.getRGB(2000, 1000) ushr 24)
        // The gradient survives: red rises across, green down.
        assertTrue(((d.getRGB(2000, 900) shr 16) and 0xFF) > ((d.getRGB(600, 900) shr 16) and 0xFF))
        assertTrue(((d.getRGB(1500, 1000) shr 8) and 0xFF) > ((d.getRGB(1500, 300) shr 8) and 0xFF))
        val detail = readPng(store.variant(project, big.hash, SceneImageVariant.DETAIL)!!)
        assertEquals(4096 to 2048, detail.width to detail.height)

        val jpeg = store.store(project, sceneJpeg(3000, 2000), "image/jpeg")
        val j = readPng(store.variant(project, jpeg.hash, SceneImageVariant.DISPLAY)!!)
        assertEquals(2048 to 1365, j.width to j.height)
        assertFalse(j.colorModel.hasAlpha(), "a JPEG's copy is opaque")

        // Made once, then served from the cache.
        val path = store.variant(project, big.hash, SceneImageVariant.DISPLAY)!!
        assertTrue(path.toString().contains("derived"))
        assertEquals(path, store.variant(project, big.hash, SceneImageVariant.DISPLAY))
    }

    @Test
    fun `the mask is the alpha at 256 px on the longest side, one byte a pixel`() {
        val painted = store.store(project, scenePng(1024, 512, alpha = true), "image/png")
        val mask = readPng(store.variant(project, painted.hash, SceneImageVariant.MASK)!!)
        assertEquals(256 to 128, mask.width to mask.height)
        assertEquals(BufferedImage.TYPE_BYTE_GRAY, mask.type)
        assertEquals(0, mask.raster.getSample(10, 10, 0), "the hole reads 0")
        assertEquals(255, mask.raster.getSample(200, 100, 0), "the cloth reads 255")

        // A small image is scaled up to 256.
        val tiny = store.store(project, scenePng(64, 32, alpha = true), "image/png")
        val tm = readPng(store.variant(project, tiny.hash, SceneImageVariant.MASK)!!)
        assertEquals(256 to 128, tm.width to tm.height)

        val jpeg = store.store(project, sceneJpeg(300, 600), "image/jpeg")
        val jm = readPng(store.variant(project, jpeg.hash, SceneImageVariant.MASK)!!)
        assertEquals(128 to 256, jm.width to jm.height)
        for (y in 0 until jm.height step 17) for (x in 0 until jm.width step 13) {
            assertEquals(255, jm.raster.getSample(x, y, 0), "a JPEG's mask is opaque white")
        }
    }

    @Test
    fun `an unknown hash has no original and no copies`() {
        val unknown = "c".repeat(64)
        assertNull(store.original(project, unknown))
        assertNull(store.variant(project, unknown, SceneImageVariant.DISPLAY))
        assertNull(store.original(project, "../../etc/passwd"))
    }

    @Test
    fun `the prune keeps what is referenced and what is young, and takes the rest with its copies`() {
        val referenced = store.store(project, scenePng(20, 10, seed = 1), "image/png")
        val young = store.store(project, scenePng(20, 10, seed = 2), "image/png")
        val stale = store.store(project, sceneJpeg(20, 10, seed = 3), "image/jpeg")
        store.variant(project, stale.hash, SceneImageVariant.DISPLAY)
        store.variant(project, referenced.hash, SceneImageVariant.MASK)

        val now = Instant.parse("2026-10-09T12:00:00Z")
        fun age(hash: String, format: SceneImageFormat, by: Duration) =
            Files.setLastModifiedTime(store.path(project, hash, format), FileTime.from(now.minus(by)))
        age(referenced.hash, SceneImageFormat.PNG, Duration.ofDays(40))
        age(young.hash, SceneImageFormat.PNG, Duration.ofDays(6))
        age(stale.hash, SceneImageFormat.JPEG, Duration.ofDays(8))

        assertEquals(1, store.prune(project, setOf(referenced.hash), now))
        assertEquals(setOf(referenced.hash, young.hash), store.storedHashes(project))
        assertNull(store.original(project, stale.hash))
        val derived = root.resolve(project).resolve("derived")
        assertTrue(Files.list(derived).use { s -> s.toList() }.none { it.fileName.toString().startsWith(stale.hash) }, "its copies went with it")
        assertNotNull(store.variant(project, referenced.hash, SceneImageVariant.MASK))
        // The referenced image was touched, so its week starts again now: six days on, the young
        // one (twelve days unreferenced by then) goes and it stays; a week later it goes too.
        assertEquals(now, Files.getLastModifiedTime(store.path(project, referenced.hash, SceneImageFormat.PNG)).toInstant())
        assertEquals(1, store.prune(project, emptySet(), now.plus(Duration.ofDays(6))))
        assertEquals(setOf(referenced.hash), store.storedHashes(project))
        assertEquals(1, store.prune(project, emptySet(), now.plus(Duration.ofDays(8))))
        assertTrue(store.storedHashes(project).isEmpty())
    }

    @Test
    fun `hydrate takes an image only when its bytes are what its name says`() {
        val dir = Files.createTempDirectory("tree-")
        try {
            val bytes = scenePng(8, 8)
            val hash = RecordHasher.sha256Hex(bytes)
            val good = dir.resolve("$hash.png").also { Files.write(it, bytes) }
            val liar = dir.resolve("${"d".repeat(64)}.png").also { Files.write(it, bytes) }
            assertTrue(store.hydrate(project, hash, good))
            assertFalse(store.hydrate(project, "d".repeat(64), liar))
            assertEquals(setOf(hash), store.storedHashes(project))
            // Its info is written with it, so the list never decodes a pulled image on a request.
            assertTrue(Files.isRegularFile(root.resolve(project).resolve("derived").resolve("$hash-info.json")))

            // A file that is what its name says but not an image the store would take — oversize by
            // its header, or one that does not decode — is refused, not stored and served as a 500.
            val huge = pngHeaderOnly(9000, 9000)
            val hugeHash = RecordHasher.sha256Hex(huge)
            assertFalse(store.hydrate(project, hugeHash, dir.resolve("$hugeHash.png").also { Files.write(it, huge) }))
            val broken = pngHeaderOnly(16, 16)
            val brokenHash = RecordHasher.sha256Hex(broken)
            assertFalse(store.hydrate(project, brokenHash, dir.resolve("$brokenHash.png").also { Files.write(it, broken) }))
            assertEquals(setOf(hash), store.storedHashes(project))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun readPng(path: Path): BufferedImage = ImageIO.read(path.toFile())

    private fun assertRefused(fragment: String, block: () -> Unit) {
        val e = assertFailsWith<SceneImageException>(block = block)
        assertEquals(SceneImageStore.CODE_INVALID, e.code)
        assertFalse(e.tooLarge)
        assertTrue(fragment in (e.message ?: ""), "expected '$fragment' in '${e.message}'")
    }
}
