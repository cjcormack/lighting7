package uk.me.cormack.lighting7.telemetry

import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.hints.SubmissionResult
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import io.sentry.util.HintUtils
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.outputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What an offline run left in `<appDataDir>/sentry` is sent when Sentry next starts (Sentry plan D15). */
class DiskCacheResendTest {

    private class RecordingTransport : ITransport {
        val sent = CopyOnWriteArrayList<SentryEnvelope>()

        override fun send(envelope: SentryEnvelope, hint: Hint) {
            sent += envelope
            HintUtils.runIfHasType(hint, SubmissionResult::class.java) { it.setResult(true) }
        }

        override fun flush(timeoutMillis: Long) = Unit
        override fun getRateLimiter(): RateLimiter? = null
        override fun close(isRestarting: Boolean) = Unit
        override fun close() = Unit
    }

    @Test
    fun `a cached envelope is sent and deleted at start`() {
        val cacheDir = Files.createTempDirectory("sentry-cache")
        // The SDK keeps its cache in a subdirectory named for the DSN, so ask it where.
        val resolved = SentryOptions().apply {
            dsn = DSN
            cacheDirPath = cacheDir.toString()
        }
        val cached = Files.createDirectories(Path.of(checkNotNull(resolved.cacheDirPath))).resolve("left-offline.envelope")
        val event = SentryEvent().apply { setTag("left", "offline") }
        cached.outputStream().use { resolved.serializer.serialize(SentryEnvelope.from(resolved.serializer, event, null), it) }

        val transport = RecordingTransport()
        Sentry.init { options ->
            options.dsn = DSN
            options.cacheDirPath = cacheDir.toString()
            options.setTransportFactory { _, _ -> transport }
            options.isSendClientReports = false
            options.isEnableUncaughtExceptionHandler = false
            options.isEnableShutdownHook = false
            SentryRuntime.resendDiskCache(options)
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (cached.exists() && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse(cached.exists(), "the cached envelope was not resent")
            assertEquals(event.eventId, transport.sent.single().header.eventId)
        } finally {
            Sentry.close()
        }
    }

    @Test
    fun `turning telemetry off purges the disk cache`() {
        val cacheDir = Files.createTempDirectory("sentry-cache")
        val hashed = Files.createDirectories(cacheDir.resolve("abc123"))
        Files.writeString(hashed.resolve("queued.envelope"), "{}")
        Files.writeString(hashed.resolve("session.json"), "{}")
        SentryRuntime.purgeDiskCache(cacheDir)
        assertTrue(Files.exists(cacheDir))
        assertEquals(0, cacheDir.toFile().listFiles()!!.size)
    }

    private companion object {
        const val DSN = "https://key@sentry.invalid/1"
    }
}
