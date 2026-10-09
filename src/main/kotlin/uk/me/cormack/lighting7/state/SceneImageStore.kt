package uk.me.cormack.lighting7.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.SCENE_IMAGE_HASH
import uk.me.cormack.lighting7.sync.RecordHasher
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The two formats a scene image may be (scrim plan P3): the JVM's `ImageIO` reads both natively. */
enum class SceneImageFormat(val ext: String, val mediaType: String, internal val imageIoName: String) {
    PNG("png", "image/png", "png"),
    JPEG("jpg", "image/jpeg", "jpeg"),
    ;

    companion object {
        fun ofMediaType(mediaType: String?): SceneImageFormat? {
            val bare = mediaType?.substringBefore(';')?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.mediaType == bare }
        }

        /** The format [bytes] are, by their signature; null for anything else. */
        fun sniff(bytes: ByteArray): SceneImageFormat? = when {
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> PNG
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> JPEG
            else -> null
        }

        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}

/** What an image is, as the upload answers it and the list carries it. */
@Serializable
data class SceneImageInfo(
    val hash: String,
    val width: Int,
    val height: Int,
    /** True when at least one pixel is not fully opaque — a JPEG never is. */
    val hasAlpha: Boolean,
    val mediaType: String,
)

/** The derived copies a `GET` may ask for (scrim plan §3.3, D12). */
enum class SceneImageVariant(val wire: String, internal val suffix: String) {
    /** The 2048 px copy every cloth loads. */
    DISPLAY("display", "2048"),

    /** The 4096 px copy a cloth with *Full detail* loads. */
    DETAIL("detail", "4096"),

    /** The alpha at 256 px on the longest side, one byte a pixel; opaque white for a JPEG (D5). */
    MASK("mask", "mask"),
    ;

    companion object {
        fun ofWire(value: String?): SceneImageVariant? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/** A refused image: [code] is the wire's, [tooLarge] answers 413 rather than 400. */
class SceneImageException(val code: String, message: String, val tooLarge: Boolean = false) : Exception(message)

/**
 * The content-addressed store of the images painted on scene cloths (scrim plan §3.3):
 * `{root}/{projectUuid}/{sha256}.{png|jpg}`, the originals as uploaded and synced, and under
 * `{projectUuid}/derived/` the per-machine copies made on first request — `{sha}-2048.png`,
 * `{sha}-4096.png`, `{sha}-mask.png` — plus `{sha}-info.json`, what the image is, so a list never
 * decodes an 8192 px original twice.
 *
 * Every write is crash-atomic (a temp file in the destination's directory, then an atomic move), as
 * the prompt-book PDFs' are, so a partial write can never answer to a hash its bytes do not have.
 *
 * Images are decoded through `ImageIO` and resampled in plain Kotlin on the pixel arrays: nothing
 * here touches `Graphics2D`, which on macOS starts the AWT toolkit and puts a Java icon in the Dock.
 * A full decode of an 8192 px image is a quarter of a gigabyte, so decodes are serialised.
 */
class SceneImageStore(val root: Path) {

    fun projectDir(projectUuid: String): Path = root.resolve(projectUuid)

    private fun derivedDir(projectUuid: String): Path = projectDir(projectUuid).resolve(DERIVED_DIR)

    /** Where [hash] in [format] lives, whether or not it is there. */
    fun path(projectUuid: String, hash: String, format: SceneImageFormat): Path =
        projectDir(projectUuid).resolve("$hash.${format.ext}")

    /** The stored original of [hash] and its format, or null when this machine does not hold it. */
    fun original(projectUuid: String, hash: String): Pair<Path, SceneImageFormat>? {
        if (!SCENE_IMAGE_HASH.matches(hash)) return null
        return SceneImageFormat.entries.firstNotNullOfOrNull { f ->
            path(projectUuid, hash, f).takeIf { Files.isRegularFile(it) }?.let { it to f }
        }
    }

    fun exists(projectUuid: String, hash: String): Boolean = original(projectUuid, hash) != null

    /**
     * Check [bytes] and store them, answering what they are. Idempotent by hash: bytes already held
     * are not rewritten (their mtime is touched, as a reference would). [declared] is the request's
     * media type, which must be one of the two and agree with the bytes.
     */
    fun store(projectUuid: String, bytes: ByteArray, declared: String?): SceneImageInfo {
        val declaredFormat = SceneImageFormat.ofMediaType(declared)
            ?: throw invalid("A scene image is a PNG or a JPEG: send Content-Type image/png or image/jpeg")
        if (bytes.size > MAX_UPLOAD_BYTES) {
            throw SceneImageException(CODE_INVALID, "A scene image is at most ${MAX_UPLOAD_BYTES / (1024 * 1024)} MB", tooLarge = true)
        }
        val format = SceneImageFormat.sniff(bytes) ?: throw invalid("The body is neither a PNG nor a JPEG")
        if (format != declaredFormat) {
            throw invalid("The body is a ${format.name}, not the ${declaredFormat.mediaType} its Content-Type says")
        }
        val hash = RecordHasher.sha256Hex(bytes)
        original(projectUuid, hash)?.let { (path, _) ->
            touch(path)
            info(projectUuid, hash)?.let { return it }
        }
        val info = inspect(hash, bytes, format)
        val dst = path(projectUuid, hash, format)
        writeAtomic(dst, bytes)
        writeInfo(projectUuid, info)
        return info
    }

    /** What [hash] is, from its sidecar — read off the original and written when missing. */
    fun info(projectUuid: String, hash: String): SceneImageInfo? {
        val (path, format) = original(projectUuid, hash) ?: return null
        val sidecar = derivedDir(projectUuid).resolve("$hash-info.json")
        runCatching { infoJson.decodeFromString(SceneImageInfo.serializer(), Files.readString(sidecar)) }
            .getOrNull()?.takeIf { it.hash == hash }?.let { return it }
        val info = runCatching { inspect(hash, Files.readAllBytes(path), format) }.getOrElse {
            logger.warn("Scene image {} in {} does not read: {}", hash, projectUuid, it.message)
            return null
        }
        writeInfo(projectUuid, info)
        return info
    }

    /** Every image this machine holds for [projectUuid], ordered by hash. */
    fun list(projectUuid: String): List<SceneImageInfo> = storedHashes(projectUuid).sorted().mapNotNull { info(projectUuid, it) }

    /** Every original's hash in [projectUuid]'s store. */
    fun storedHashes(projectUuid: String): Set<String> {
        val dir = projectDir(projectUuid)
        if (!Files.isDirectory(dir)) return emptySet()
        return Files.list(dir).use { stream ->
            stream.toList().mapNotNull { originalHash(it) }.toSet()
        }
    }

    /**
     * The derived copy of [hash] as [variant], made on first request and kept. Null when this
     * machine holds no such image.
     */
    fun variant(projectUuid: String, hash: String, variant: SceneImageVariant): Path? {
        val (path, format) = original(projectUuid, hash) ?: return null
        val dst = derivedDir(projectUuid).resolve("$hash-${variant.suffix}.png")
        if (Files.isRegularFile(dst)) return dst
        decodeLock.withLock {
            if (Files.isRegularFile(dst)) return dst
            val image = decode(Files.readAllBytes(path), format)
            val out = when (variant) {
                SceneImageVariant.DISPLAY -> displayCopy(image, DISPLAY_PX)
                SceneImageVariant.DETAIL -> displayCopy(image, DETAIL_PX)
                SceneImageVariant.MASK -> maskCopy(image, format)
            }
            Files.createDirectories(dst.parent)
            val tmp = Files.createTempFile(dst.parent, ".derived-", ".tmp")
            try {
                if (!ImageIO.write(out, "png", tmp.toFile())) error("no PNG writer")
                moveAtomic(tmp, dst)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
        return dst
    }

    /** Mark [hashes] as referenced now, which is what holds them back from [prune]. */
    fun touch(projectUuid: String, hashes: Collection<String>) {
        for (hash in hashes) original(projectUuid, hash)?.let { touch(it.first) }
    }

    /**
     * At project load (scrim plan §3.3): an original [referenced] names is touched; one no element
     * has referenced for [PRUNE_AFTER] — its mtime, which every reference and every element write
     * that names or drops it moves, is older than that — is deleted with its derived copies. So an
     * upload whose element was never saved does not pile up, and an image taken off a cloth a
     * moment ago survives a week for an undo. Derived files whose original is gone go too. Answers
     * how many originals went.
     */
    fun prune(projectUuid: String, referenced: Set<String>, now: Instant = Instant.now()): Int {
        val dir = projectDir(projectUuid)
        if (!Files.isDirectory(dir)) return 0
        val cutoff = now.minus(PRUNE_AFTER)
        var pruned = 0
        val files = Files.list(dir).use { it.toList() }
        for (file in files) {
            val hash = originalHash(file) ?: continue
            if (hash in referenced) {
                runCatching { Files.setLastModifiedTime(file, FileTime.from(now)) }
                continue
            }
            val modified = runCatching { Files.getLastModifiedTime(file).toInstant() }.getOrNull() ?: continue
            if (modified.isBefore(cutoff)) {
                Files.deleteIfExists(file)
                pruned++
            }
        }
        val kept = storedHashes(projectUuid)
        val derived = derivedDir(projectUuid)
        if (Files.isDirectory(derived)) {
            Files.list(derived).use { it.toList() }.forEach { f ->
                val name = f.fileName.toString()
                val hash = name.substringBefore('-')
                if (SCENE_IMAGE_HASH.matches(hash) && hash !in kept) Files.deleteIfExists(f)
            }
        }
        return pruned
    }

    /** Remove every image a deleted project held. */
    fun deleteProject(projectUuid: String) {
        projectDir(projectUuid).toFile().deleteRecursively()
    }

    /**
     * Copy [src] — an original from a repo or an export folder — into the store as [hash] when it
     * is not already there, after checking the bytes are what the name says (a PNG or a JPEG whose
     * SHA-256 is [hash]) and an image the store would take from an upload (it decodes, at most
     * [MAX_SIDE_PX] a side). Its info sidecar is written with it, so the list never decodes a
     * pulled image on a request thread. Answers whether the store holds it afterwards.
     */
    fun hydrate(projectUuid: String, hash: String, src: Path): Boolean {
        if (exists(projectUuid, hash)) return true
        val size = runCatching { Files.size(src) }.getOrNull() ?: return false
        if (size > MAX_UPLOAD_BYTES) {
            logger.warn("Skipping scene image {} for {}: {} bytes is over the upload cap", hash, projectUuid, size)
            return false
        }
        val bytes = Files.readAllBytes(src)
        val format = SceneImageFormat.sniff(bytes)
        if (format == null || src.fileName.toString() != "$hash.${format.ext}" || RecordHasher.sha256Hex(bytes) != hash) {
            logger.warn("Skipping scene image {} for {}: its bytes are not the image its name says", src.fileName, projectUuid)
            return false
        }
        val info = try {
            inspect(hash, bytes, format)
        } catch (e: SceneImageException) {
            logger.warn("Skipping scene image {} for {}: {}", src.fileName, projectUuid, e.message)
            return false
        }
        writeAtomic(path(projectUuid, hash, format), bytes)
        writeInfo(projectUuid, info)
        return true
    }

    // ─── Inspection ─────────────────────────────────────────────────────────

    /**
     * [bytes] checked as an image of [format]: the size is read from the header **before** anything
     * is decoded, so an image claiming 100 000 px a side is refused without the gigabytes a decode
     * would take; then it must decode, and its alpha is read.
     */
    private fun inspect(hash: String, bytes: ByteArray, format: SceneImageFormat): SceneImageInfo {
        val reader = readerFor(format)
        try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                reader.setInput(input, true, true)
                val (w, h) = try {
                    reader.getWidth(0) to reader.getHeight(0)
                } catch (e: IOException) {
                    throw invalid("The ${format.name} does not decode: ${e.message ?: "unreadable header"}")
                } catch (e: RuntimeException) {
                    throw invalid("The ${format.name} does not decode: ${e.message ?: "unreadable header"}")
                }
                checkSize(w, h)
                return decodeLock.withLock {
                    val image = try {
                        reader.read(0)
                    } catch (e: IOException) {
                        throw invalid("The ${format.name} does not decode: ${e.message ?: "unreadable"}")
                    } catch (e: RuntimeException) {
                        throw invalid("The ${format.name} does not decode: ${e.message ?: "unreadable"}")
                    } ?: throw invalid("The ${format.name} does not decode")
                    SceneImageInfo(hash, image.width, image.height, hasTransparentPixel(image), format.mediaType)
                }
            }
        } finally {
            reader.dispose()
        }
    }

    private fun decode(bytes: ByteArray, format: SceneImageFormat): BufferedImage {
        val reader = readerFor(format)
        try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                reader.setInput(input, true, true)
                checkSize(reader.getWidth(0), reader.getHeight(0))
                return reader.read(0)
            }
        } finally {
            reader.dispose()
        }
    }

    private fun checkSize(w: Int, h: Int) {
        if (w < 1 || h < 1) throw invalid("The image has no pixels")
        if (w > MAX_SIDE_PX || h > MAX_SIDE_PX) {
            throw invalid("The image is $w × $h px; a scene image is at most $MAX_SIDE_PX px on a side")
        }
    }

    private fun readerFor(format: SceneImageFormat): ImageReader =
        ImageIO.getImageReadersByFormatName(format.imageIoName).asSequence().firstOrNull()
            ?: throw invalid("This desk cannot read ${format.name}")

    private fun hasTransparentPixel(image: BufferedImage): Boolean {
        if (!image.colorModel.hasAlpha()) return false
        val row = IntArray(image.width)
        for (y in 0 until image.height) {
            image.getRGB(0, y, image.width, 1, row, 0, image.width)
            if (row.any { (it ushr 24) != 0xFF }) return true
        }
        return false
    }

    // ─── Derived copies ─────────────────────────────────────────────────────

    /**
     * [image] with its longest side at most [target]: halved while that keeps it at least [target]
     * (bicubic each time), then one bicubic step to [target] exactly. A smaller image is copied
     * as it is. Alpha is kept, resampled premultiplied so a hole's edge does not darken.
     */
    private fun displayCopy(image: BufferedImage, target: Int): BufferedImage {
        val alpha = image.colorModel.hasAlpha()
        val pixels = shrinkTo(image, target)
        val out = BufferedImage(pixels.w, pixels.h, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        out.setRGB(0, 0, pixels.w, pixels.h, unpremultiply(pixels.px), 0, pixels.w)
        return out
    }

    /**
     * The alpha channel at exactly [MASK_PX] on the longest side, one byte a pixel — up or down —
     * or opaque white for an image with no alpha (a JPEG).
     */
    private fun maskCopy(image: BufferedImage, format: SceneImageFormat): BufferedImage {
        val (w, h) = fit(image.width, image.height, MASK_PX)
        val out = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val samples = if (format == SceneImageFormat.JPEG || !image.colorModel.hasAlpha()) {
            IntArray(w * h) { 255 }
        } else {
            val shrunk = shrinkTo(image, MASK_PX)
            val sized = if (shrunk.w == w && shrunk.h == h) shrunk else resample(shrunk, w, h)
            IntArray(w * h) { sized.px[it] ushr 24 }
        }
        out.raster.setSamples(0, 0, w, h, 0, samples)
        return out
    }

    /** Premultiplied ARGB pixels, packed one int each. */
    private class Pixels(val w: Int, val h: Int, val px: IntArray)

    private fun shrinkTo(image: BufferedImage, target: Int): Pixels {
        val (tw, th) = fit(image.width, image.height, target)
        if (max(image.width, image.height) <= target) {
            return Pixels(image.width, image.height, readPremultiplied(image))
        }
        // The first halving reads the image a row at a time, so the full-size frame is never held
        // twice (the decoded raster and an int copy of it).
        var current = if (max(image.width, image.height) / 2 >= target) {
            halveStreaming(image)
        } else {
            return resampleStreaming(image, tw, th)
        }
        while (max(current.w, current.h) / 2 >= target) {
            current = resample(current, half(current.w), half(current.h))
        }
        return if (current.w == tw && current.h == th) current else resample(current, tw, th)
    }

    private fun half(n: Int) = max(1, (n + 1) / 2)

    private fun halveStreaming(image: BufferedImage): Pixels = resampleStreaming(image, half(image.width), half(image.height))

    private fun resampleStreaming(image: BufferedImage, dw: Int, dh: Int): Pixels {
        val sw = image.width
        val sh = image.height
        val xw = Taps(sw, dw)
        val mid = IntArray(dw * sh)
        val row = IntArray(sw)
        for (y in 0 until sh) {
            image.getRGB(0, y, sw, 1, row, 0, sw)
            for (x in 0 until sw) row[x] = premultiply(row[x])
            horizontal(row, 0, xw, mid, y * dw)
        }
        return vertical(Pixels(dw, sh, mid), dh)
    }

    private fun resample(src: Pixels, dw: Int, dh: Int): Pixels {
        val xw = Taps(src.w, dw)
        val mid = IntArray(dw * src.h)
        for (y in 0 until src.h) horizontal(src.px, y * src.w, xw, mid, y * dw)
        return vertical(Pixels(dw, src.h, mid), dh)
    }

    private fun horizontal(src: IntArray, srcOffset: Int, taps: Taps, dst: IntArray, dstOffset: Int) {
        for (x in 0 until taps.dst) {
            var a = 0f
            var r = 0f
            var g = 0f
            var b = 0f
            val start = taps.start[x]
            val weights = taps.weights[x]
            for (k in weights.indices) {
                val p = src[srcOffset + taps.clamp(start + k)]
                val w = weights[k]
                a += (p ushr 24) * w
                r += ((p ushr 16) and 0xFF) * w
                g += ((p ushr 8) and 0xFF) * w
                b += (p and 0xFF) * w
            }
            dst[dstOffset + x] = pack(a, r, g, b)
        }
    }

    private fun vertical(src: Pixels, dh: Int): Pixels {
        val taps = Taps(src.h, dh)
        val out = IntArray(src.w * dh)
        for (y in 0 until dh) {
            val start = taps.start[y]
            val weights = taps.weights[y]
            for (x in 0 until src.w) {
                var a = 0f
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in weights.indices) {
                    val p = src.px[taps.clamp(start + k) * src.w + x]
                    val w = weights[k]
                    a += (p ushr 24) * w
                    r += ((p ushr 16) and 0xFF) * w
                    g += ((p ushr 8) and 0xFF) * w
                    b += (p and 0xFF) * w
                }
                out[y * src.w + x] = pack(a, r, g, b)
            }
        }
        return Pixels(src.w, dh, out)
    }

    /**
     * Catmull-Rom weights for resampling [src] samples to [dst], the kernel widened by the scale
     * when shrinking so every source sample is heard. Each row is normalised to 1.
     */
    private class Taps(val src: Int, val dst: Int) {
        val start = IntArray(dst)
        val weights: Array<FloatArray>

        init {
            val scale = src.toDouble() / dst
            val stretch = max(1.0, scale)
            val support = 2.0 * stretch
            weights = Array(dst) { i ->
                val centre = (i + 0.5) * scale - 0.5
                val first = floor(centre - support).toInt() + 1
                val last = floor(centre + support).toInt()
                val w = FloatArray(last - first + 1) { k -> cubic((first + k - centre) / stretch).toFloat() }
                val sum = w.sum()
                if (sum != 0f) for (k in w.indices) w[k] /= sum
                start[i] = first
                w
            }
        }

        fun clamp(i: Int) = min(src - 1, max(0, i))
    }

    // ─── Files ──────────────────────────────────────────────────────────────

    private fun writeInfo(projectUuid: String, info: SceneImageInfo) {
        runCatching {
            writeAtomic(derivedDir(projectUuid).resolve("${info.hash}-info.json"), infoJson.encodeToString(SceneImageInfo.serializer(), info).toByteArray())
        }.onFailure { logger.warn("Could not record scene image {}'s info: {}", info.hash, it.message) }
    }

    private fun touch(path: Path) {
        runCatching { Files.setLastModifiedTime(path, FileTime.from(Instant.now())) }
    }

    private fun originalHash(file: Path): String? {
        if (!Files.isRegularFile(file)) return null
        val name = file.fileName.toString()
        val hash = name.substringBefore('.')
        val ext = name.substringAfter('.', "")
        return hash.takeIf { SCENE_IMAGE_HASH.matches(it) && SceneImageFormat.entries.any { f -> f.ext == ext } }
    }

    companion object {
        /** `ErrorResponse.code` for an image the store will not take (400, or 413 over the size cap). */
        const val CODE_INVALID = "SCENE_IMAGE_INVALID"

        /** `ErrorResponse.code` for a hash this machine holds no image for (404). */
        const val CODE_UNKNOWN = "SCENE_IMAGE_UNKNOWN"

        /** The upload cap — a painted cloth's PNG with room to spare, small enough that a mistake cannot OOM the desk. */
        const val MAX_UPLOAD_BYTES = 25 * 1024 * 1024

        /** The largest side a stored image may have. */
        const val MAX_SIDE_PX = 8192

        const val DISPLAY_PX = 2048
        const val DETAIL_PX = 4096
        const val MASK_PX = 256

        /** How long an image no element references is kept before [prune] takes it. */
        val PRUNE_AFTER: Duration = Duration.ofDays(7)

        private const val DERIVED_DIR = "derived"

        private val logger = LoggerFactory.getLogger(SceneImageStore::class.java)
        private val infoJson = Json { ignoreUnknownKeys = true }

        /** One full decode at a time across every store: an 8192 px frame is 256 MB of pixels. */
        private val decodeLock = ReentrantLock()

        private fun invalid(message: String) = SceneImageException(CODE_INVALID, message)

        /** [w] × [h] scaled so its longest side is exactly [target]. */
        internal fun fit(w: Int, h: Int, target: Int): Pair<Int, Int> {
            val scale = target.toDouble() / max(w, h)
            return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
        }

        private fun cubic(x: Double): Double {
            val t = abs(x)
            return when {
                t < 1 -> 1.5 * t * t * t - 2.5 * t * t + 1
                t < 2 -> -0.5 * t * t * t + 2.5 * t * t - 4 * t + 2
                else -> 0.0
            }
        }

        private fun premultiply(argb: Int): Int {
            val a = argb ushr 24
            if (a == 255) return argb
            if (a == 0) return 0
            val r = ((argb ushr 16) and 0xFF) * a / 255
            val g = ((argb ushr 8) and 0xFF) * a / 255
            val b = (argb and 0xFF) * a / 255
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        private fun readPremultiplied(image: BufferedImage): IntArray {
            val out = IntArray(image.width * image.height)
            image.getRGB(0, 0, image.width, image.height, out, 0, image.width)
            for (i in out.indices) out[i] = premultiply(out[i])
            return out
        }

        private fun unpremultiply(px: IntArray): IntArray = IntArray(px.size) { i ->
            val p = px[i]
            val a = p ushr 24
            when (a) {
                255 -> p
                0 -> 0
                else -> {
                    val r = min(255, ((p ushr 16) and 0xFF) * 255 / a)
                    val g = min(255, ((p ushr 8) and 0xFF) * 255 / a)
                    val b = min(255, (p and 0xFF) * 255 / a)
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        /** Clamped into bytes, colour kept within alpha so the pixel stays premultiplied. */
        private fun pack(a: Float, r: Float, g: Float, b: Float): Int {
            val ai = a.roundToInt().coerceIn(0, 255)
            val ri = r.roundToInt().coerceIn(0, ai)
            val gi = g.roundToInt().coerceIn(0, ai)
            val bi = b.roundToInt().coerceIn(0, ai)
            return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
        }

        private fun writeAtomic(dst: Path, bytes: ByteArray) {
            Files.createDirectories(dst.parent)
            val tmp = Files.createTempFile(dst.parent, ".upload-", ".tmp")
            try {
                Files.write(tmp, bytes)
                moveAtomic(tmp, dst)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }

        private fun moveAtomic(tmp: Path, dst: Path) {
            try {
                Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
