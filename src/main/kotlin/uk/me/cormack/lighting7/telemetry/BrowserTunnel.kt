package uk.me.cormack.lighting7.telemetry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val logger = LoggerFactory.getLogger(BrowserTunnel::class.java)

/** A Sentry DSN, `https://<key>@<host>[:port]/<projectId>`, by its parts. */
data class SentryDsn(val scheme: String, val publicKey: String, val host: String, val port: Int, val projectId: String) {
    /** The project's envelope endpoint, authenticated by its public key. */
    val envelopeUrl: URI get() = URI(scheme, null, host, port, "/api/$projectId/envelope/", "sentry_key=$publicKey&sentry_version=7", null)

    companion object {
        fun parse(dsn: String): SentryDsn? = runCatching {
            val uri = URI(dsn.trim())
            val scheme = uri.scheme?.lowercase()?.takeIf { it == "https" || it == "http" } ?: return null
            val key = uri.rawUserInfo?.substringBefore(':')?.takeIf { it.isNotEmpty() } ?: return null
            val host = uri.host?.lowercase() ?: return null
            val project = uri.path?.trim('/')?.takeIf { it.isNotEmpty() && '/' !in it } ?: return null
            SentryDsn(scheme, key, host, uri.port, project)
        }.getOrNull()
    }
}

/** Why the tunnel refused an envelope, as the status and code the route answers. */
enum class TunnelRefusal(val status: Int, val code: String, val message: String) {
    OFF(403, "TELEMETRY_OFF", "Telemetry is off on this desk"),
    TOO_LARGE(413, "ENVELOPE_TOO_LARGE", "An envelope is at most 1 MB"),
    INVALID(400, "ENVELOPE_INVALID", "Not a Sentry envelope naming a DSN"),
    FOREIGN_DSN(403, "ENVELOPE_DSN_REFUSED", "This desk forwards only to its own project"),
    NOT_SPOOLED(503, "ENVELOPE_NOT_SPOOLED", "The desk couldn't store the envelope"),
}

/** What became of one forwarding attempt. */
sealed interface SendResult {
    /** Taken, or refused for good (a 4xx other than 429): either way the envelope is done with. */
    data object Done : SendResult

    /** The project is rate-limited: keep the envelope and wait this long. */
    data class Wait(val millis: Long) : SendResult

    /** No route out, or Sentry failing: keep the envelope and back off. */
    data object Failed : SendResult
}

fun interface EnvelopeSender {
    fun send(envelope: ByteArray): SendResult
}

/**
 * The browser's envelope tunnel (Sentry plan D9): `POST /api/sentry` hands each envelope here, which
 * spools it to disk only while telemetry is on, only if it is at most 1 MB, and only if its header
 * names this desk's browser DSN — on the public listener that check is what stops it being an open
 * relay. A worker forwards the spool byte for byte, oldest first, to that project, and keeps what it
 * could not send, so a venue with no route out keeps its browsers' reports until it has one — unless
 * telemetry is turned off, which deletes them.
 *
 * The spool lives in `State`, so the route is testable; only `Application.module()` starts the
 * worker, so the suite never sends.
 */
class BrowserTunnel(
    private val settings: TelemetrySettings,
    private val spoolDir: Path,
    private val maxEnvelopes: Int = MAX_SPOOLED_ENVELOPES,
    private val maxBytes: Long = MAX_SPOOLED_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val dsn: SentryDsn? = settings.config.browserDsn?.let { raw ->
        SentryDsn.parse(raw) ?: null.also { logger.warn("sentry.browserDsn is not a DSN; the browser won't report") }
    }

    private val sequence = AtomicLong()
    private val signal = LinkedBlockingQueue<Unit>(1)
    private var backoffMs = INITIAL_BACKOFF_MS

    private class Spooled(val path: Path, val bytes: Long)

    private val lock = Any()

    // The spool oldest first, read from disk on first use, so neither a POST nor a forward lists it.
    private var index: ArrayDeque<Spooled>? = null
    private var indexedBytes = 0L

    @Volatile private var worker: Thread? = null

    /** Spools [envelope] for forwarding, or says why not. */
    fun accept(envelope: ByteArray): TunnelRefusal? {
        val ours = dsn
        if (ours == null || !settings.enabled) return TunnelRefusal.OFF
        if (envelope.size > MAX_ENVELOPE_BYTES) return TunnelRefusal.TOO_LARGE
        val named = dsnNamedBy(envelope) ?: return TunnelRefusal.INVALID
        if (named != ours) return TunnelRefusal.FOREIGN_DSN
        try {
            spool(envelope)
        } catch (e: IOException) {
            logger.warn("Couldn't spool a browser envelope: {}", e.toString())
            return TunnelRefusal.NOT_SPOOLED
        }
        wake()
        return null
    }

    /** Starts forwarding, once; a desk with no browser DSN has nothing to forward to. */
    fun startForwarding(sender: EnvelopeSender? = null) {
        val ours = dsn ?: return
        if (worker != null) return
        val forward = sender ?: HttpEnvelopeSender(ours)
        worker = Thread({ forwardLoop(forward) }, "sentry-browser-tunnel").apply {
            isDaemon = true
            start()
        }
    }

    /** The switch moved. Off withdraws consent for what is already spooled too, so it is deleted. */
    fun onTelemetryChanged(enabled: Boolean) {
        if (!enabled) purge()
        wake()
    }

    /** A new envelope, or telemetry switched: look at the spool again. */
    fun wake() {
        signal.offer(Unit)
    }

    fun close() {
        val running = worker ?: return
        worker = null
        running.interrupt()
        running.join(2_000)
    }

    /** The spooled envelopes, oldest first. */
    internal fun pending(): List<Path> = synchronized(lock) { loadedIndex().map { it.path } }

    internal fun purge() {
        synchronized(lock) {
            val spooled = loadedIndex()
            for (entry in spooled) deleteQuietly(entry.path)
            spooled.clear()
            indexedBytes = 0
        }
    }

    /**
     * Forwards the spool until it is empty, telemetry is off or a send fails; answers how long to wait
     * before trying again, or null to wait for [wake].
     */
    internal fun drain(sender: EnvelopeSender): Long? {
        while (true) {
            if (!settings.enabled) return null
            val next = synchronized(lock) { loadedIndex().firstOrNull() } ?: return null
            val bytes = try {
                Files.readAllBytes(next.path)
            } catch (_: NoSuchFileException) {
                forget(next)
                continue
            } catch (e: IOException) {
                logger.warn("Couldn't read a spooled browser envelope: {}", e.toString())
                return backOff()
            }
            val result = try {
                sender.send(bytes)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Forwarding a browser envelope failed: {}", e.toString())
                SendResult.Failed
            }
            when (result) {
                SendResult.Done -> {
                    forget(next)
                    deleteQuietly(next.path)
                    backoffMs = INITIAL_BACKOFF_MS
                }
                is SendResult.Wait -> return result.millis.coerceIn(INITIAL_BACKOFF_MS, MAX_BACKOFF_MS)
                SendResult.Failed -> return backOff()
            }
        }
    }

    private fun backOff(): Long {
        val wait = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        return wait
    }

    private fun forwardLoop(sender: EnvelopeSender) {
        var retryAt = 0L
        try {
            while (!Thread.currentThread().isInterrupted) {
                val now = clock()
                if (now < retryAt) {
                    signal.poll(retryAt - now, TimeUnit.MILLISECONDS)
                    continue
                }
                val wait = try {
                    drain(sender)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("The browser tunnel failed; retrying: {}", e.toString())
                    backOff()
                }
                if (wait == null) signal.take() else retryAt = clock() + wait
            }
        } catch (_: InterruptedException) {
            // Closed.
        }
    }

    private fun spool(envelope: ByteArray) {
        synchronized(lock) { loadedIndex() }
        Files.createDirectories(spoolDir)
        val name = "%013d-%06d".format(clock(), sequence.incrementAndGet() % 1_000_000)
        val partial = spoolDir.resolve("$name$PARTIAL")
        val path = spoolDir.resolve("$name$SUFFIX")
        Files.write(partial, envelope)
        Files.move(partial, path, StandardCopyOption.ATOMIC_MOVE)
        synchronized(lock) {
            val spooled = loadedIndex()
            spooled.addLast(Spooled(path, envelope.size.toLong()))
            indexedBytes += envelope.size
            while (spooled.size > maxEnvelopes || indexedBytes > maxBytes) {
                val oldest = spooled.removeFirst()
                indexedBytes -= oldest.bytes
                deleteQuietly(oldest.path)
            }
        }
    }

    private fun forget(entry: Spooled) {
        synchronized(lock) {
            if (index?.remove(entry) == true) indexedBytes -= entry.bytes
        }
    }

    /** Under [lock]. Sweeps a spool a crash left half-written. */
    private fun loadedIndex(): ArrayDeque<Spooled> {
        index?.let { return it }
        val files = try {
            Files.list(spoolDir).use { it.toList() }.sorted()
        } catch (_: NoSuchFileException) {
            emptyList()
        } catch (e: IOException) {
            logger.warn("Couldn't read the browser spool: {}", e.toString())
            emptyList()
        }
        val loaded = ArrayDeque<Spooled>()
        for (file in files) {
            val name = file.fileName.toString()
            if (name.endsWith(PARTIAL)) deleteQuietly(file)
            if (!name.endsWith(SUFFIX)) continue
            val size = runCatching { Files.size(file) }.getOrNull() ?: continue
            loaded.addLast(Spooled(file, size))
        }
        indexedBytes = loaded.sumOf { it.bytes }
        index = loaded
        return loaded
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            logger.warn("Couldn't delete {}: {}", path.fileName, e.toString())
        }
    }

    companion object {
        const val MAX_ENVELOPE_BYTES = 1024 * 1024
        const val MAX_SPOOLED_ENVELOPES = 300
        const val MAX_SPOOLED_BYTES = 64L * 1024 * 1024
        internal const val INITIAL_BACKOFF_MS = 5_000L
        internal const val MAX_BACKOFF_MS = 5 * 60_000L
        private const val SUFFIX = ".envelope"
        private const val PARTIAL = ".part"

        /** The DSN an envelope's header line names, or null if it has none. */
        internal fun dsnNamedBy(envelope: ByteArray): SentryDsn? {
            val end = envelope.indexOf('\n'.code.toByte()).let { if (it < 0) envelope.size else it }
            val header = runCatching { Json.parseToJsonElement(String(envelope, 0, end, Charsets.UTF_8)) }.getOrNull() as? JsonObject
            val dsn = (header?.get("dsn") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return SentryDsn.parse(dsn)
        }
    }
}

/** Posts an envelope to its project's ingest endpoint as it came, never through the SDK. */
internal class HttpEnvelopeSender(private val dsn: SentryDsn) : EnvelopeSender {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun send(envelope: ByteArray): SendResult {
        val request = HttpRequest.newBuilder(dsn.envelopeUrl)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-sentry-envelope")
            .POST(HttpRequest.BodyPublishers.ofByteArray(envelope))
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.discarding())
        } catch (_: IOException) {
            return SendResult.Failed
        }
        val status = response.statusCode()
        return when {
            status in 200..299 -> SendResult.Done
            status == 429 -> SendResult.Wait(retryAfterMillis(response.headers()))
            status in 400..499 -> {
                logger.warn("Sentry refused a browser envelope: HTTP {}", status)
                SendResult.Done
            }
            else -> SendResult.Failed
        }
    }

    companion object {
        /** `Retry-After`, else the first limit in `X-Sentry-Rate-Limits`, else a minute. */
        internal fun retryAfterMillis(headers: HttpHeaders): Long {
            val seconds = headers.firstValue("Retry-After").orElse(null)?.trim()?.toLongOrNull()
                ?: headers.firstValue("X-Sentry-Rate-Limits").orElse(null)?.substringBefore(':')?.trim()?.toLongOrNull()
                ?: 60L
            return seconds * 1_000
        }
    }
}
