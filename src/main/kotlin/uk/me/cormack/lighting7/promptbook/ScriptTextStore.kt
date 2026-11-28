package uk.me.cormack.lighting7.promptbook

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * What the desk can say about a script's text (prompt-book navigation plan §3): never an empty
 * index for one it has not read.
 */
sealed interface ScriptTextState {
    val wire: String

    data class Ready(val index: ScriptTextIndex, val file: Path) : ScriptTextState {
        override val wire get() = "READY"
    }

    /** Being read in the background; ask again shortly. */
    data object Building : ScriptTextState {
        override val wire get() = "BUILDING"
    }

    /** The PDF is not in this desk's store (a pulled show whose PDF has not arrived). */
    data object ScriptMissing : ScriptTextState {
        override val wire get() = "SCRIPT_MISSING"
    }

    /** PDFBox could not read the PDF. Kept until the desk restarts, so it is not read again and again. */
    data class Unreadable(val reason: String) : ScriptTextState {
        override val wire get() = "UNREADABLE"
    }
}

/**
 * The text indexes beside the script store's PDFs, `{projectUuid}/{hash}.text.json` (plan D1, P2): a
 * cache, never a record — no table, not synced, not backed up, deletable at any time. [state] never
 * waits: an absent or older index is built on the store's own thread, never a show thread or a
 * request's, and answered [ScriptTextState.Building] meanwhile.
 */
class ScriptTextStore(
    private val pdfPath: (projectUuid: String, hash: String) -> Path,
    /** For tests: where builds run. The store's own single thread when absent. */
    executor: ExecutorService? = null,
) {
    private val logger = LoggerFactory.getLogger(ScriptTextStore::class.java)

    private val ownsExecutor = executor == null
    private val executorLazy: Lazy<ExecutorService> = lazy {
        executor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "script-text-${THREADS.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }

    private val building: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    private val failed = ConcurrentHashMap<Path, String>()
    private val mismatchLogged: MutableSet<Path> = ConcurrentHashMap.newKeySet()

    /** The last index read, by file and its modification time: a search per keystroke reads it once. */
    @Volatile private var cached: Triple<Path, Long, ScriptTextIndex>? = null

    fun indexPath(projectUuid: String, hash: String): Path =
        pdfPath(projectUuid, hash).resolveSibling("$hash$SUFFIX")

    /**
     * The index for [hash], or why there is none. Starts a build for an absent or older one.
     * [expectedPageCount] is the book's: a PDF the desk counts differently is logged, never rewritten.
     */
    fun state(projectUuid: String, hash: String, expectedPageCount: Int? = null): ScriptTextState {
        val pdf = pdfPath(projectUuid, hash)
        val file = indexPath(projectUuid, hash)
        read(file, hash)?.let { index ->
            if (expectedPageCount != null && index.pageCount != expectedPageCount && mismatchLogged.add(file)) {
                logger.warn(
                    "Script {} of project {} has {} pages, but its prompt book says {}",
                    hash, projectUuid, index.pageCount, expectedPageCount,
                )
            }
            return ScriptTextState.Ready(index, file)
        }
        if (!pdf.exists()) return ScriptTextState.ScriptMissing
        failed[file]?.let { return ScriptTextState.Unreadable(it) }
        request(projectUuid, hash)
        return ScriptTextState.Building
    }

    /** Build [hash]'s index in the background if it has none of this version: an import or a sync's arrival. */
    fun request(projectUuid: String, hash: String) {
        val pdf = pdfPath(projectUuid, hash)
        val file = indexPath(projectUuid, hash)
        if (!pdf.exists() || failed.containsKey(file) || read(file, hash) != null) return
        if (!building.add(file)) return
        try {
            executorLazy.value.execute { build(pdf, file, hash) }
        } catch (e: Exception) {
            building.remove(file)
            logger.warn("Could not queue the text index of script {}: {}", hash, e.message)
        }
    }

    /**
     * Remove [projectUuid]'s indexes whose PDF has left the store, and any half-written index a crash
     * left, at project load. Best-effort.
     */
    fun pruneOrphans(projectUuid: String): Int {
        val dir = pdfPath(projectUuid, ZERO_HASH).parent
        if (!dir.isDirectory()) return 0
        var removed = 0
        Files.newDirectoryStream(dir, "*$SUFFIX").use { stream ->
            for (index in stream) {
                val hash = index.name.removeSuffix(SUFFIX)
                if (!pdfPath(projectUuid, hash).exists() && Files.deleteIfExists(index)) removed++
            }
        }
        Files.newDirectoryStream(dir, "$TMP_PREFIX*$TMP_SUFFIX").use { stream ->
            for (tmp in stream) if (runCatching { Files.deleteIfExists(tmp) }.getOrDefault(false)) removed++
        }
        return removed
    }

    fun close() {
        if (ownsExecutor && executorLazy.isInitialized()) executorLazy.value.shutdownNow()
    }

    private fun build(pdf: Path, file: Path, hash: String) {
        try {
            val started = System.nanoTime()
            val index = try {
                ScriptTextExtractor.extract(pdf, hash)
            } catch (e: Throwable) {
                if (isTransient(e)) {
                    logger.warn("Could not read the text of script {} this time: {}", hash, e.toString())
                    return
                }
                failed[file] = e.message ?: e.javaClass.simpleName
                logger.warn("Could not read the text of script {}: {}", hash, e.toString())
                if (e is VirtualMachineError && e !is StackOverflowError) throw e
                return
            }
            try {
                write(file, Json.encodeToString(ScriptTextIndex.serializer(), index))
            } catch (e: Exception) {
                logger.warn("Could not store the text index of script {}: {}", hash, e.toString())
                return
            }
            logger.info(
                "Read the text of script {}: {} pages, {} scanned, in {} ms",
                hash, index.pageCount, index.scannedPages.size, (System.nanoTime() - started) / 1_000_000,
            )
        } finally {
            building.remove(file)
        }
    }

    /** A failure that says nothing about the PDF itself, so a later ask reads it again. */
    private fun isTransient(e: Throwable): Boolean =
        e is InterruptedException || e is InterruptedIOException || e is ClosedByInterruptException ||
            e is NoSuchFileException || Thread.currentThread().isInterrupted

    private fun read(file: Path, hash: String): ScriptTextIndex? {
        val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: return null
        cached?.let { (path, time, index) -> if (path == file && time == modified) return index }
        val index = runCatching { JSON.decodeFromString(ScriptTextIndex.serializer(), Files.readString(file)) }
            .getOrNull()
            ?.takeIf { it.version == ScriptTextIndex.VERSION && it.scriptHash == hash }
            ?: return null
        cached = Triple(file, modified, index)
        return index
    }

    private fun write(file: Path, json: String) {
        Files.createDirectories(file.parent)
        val tmp = Files.createTempFile(file.parent, TMP_PREFIX, TMP_SUFFIX)
        try {
            Files.writeString(tmp, json)
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    companion object {
        const val SUFFIX = ".text.json"
        private const val TMP_PREFIX = ".text-"
        private const val TMP_SUFFIX = ".tmp"
        private val ZERO_HASH = "0".repeat(64)
        private val THREADS = AtomicInteger()
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Whether [name] is one of the store's own files rather than a script: left out of backups. */
        fun isIndexFile(name: String): Boolean = name.endsWith(SUFFIX) || (name.startsWith(TMP_PREFIX) && name.endsWith(TMP_SUFFIX))
    }
}
