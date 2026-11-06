package uk.me.cormack.lighting7.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.cloud.OnceLog
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One launch at a time of the sidecar, lighting7-link, and its three pipes (cloud-p1 plan D7, D11):
 * stdout and stderr read on threads of their own, stdout's lines handed to [scope] (the cloud's) as
 * bounded lines through a bounded channel, stderr drained into the log at a bounded rate, and stdin
 * written by a thread of its own from a bounded queue that never drops a line. A queue that fills
 * means the sidecar has stopped reading: it is taken as hung and restarted, which ends its sessions.
 * So a hung or dead sidecar never blocks the JVM, nor holds the cloud's threads for longer than a line.
 *
 * A stop sends the sidecar SIGTERM (on Windows, a kill), on which it ends its sessions and exits.
 *
 * Development only (session 3): [command] names a binary built by hand. It is not restarted when it
 * exits; session 5 bundles it and supervises it with backoff.
 */
class LinkSidecar(
    private val command: List<String>,
    private val scope: CoroutineScope,
    /** A new secret for each launch: its first line on stdin. */
    private val newSecret: () -> String,
    /** Each line the sidecar writes that is a [LinkOut], on [scope]. */
    private val onMessage: (LinkOut) -> Unit,
    /** The sidecar serves ([LinkOut.Started] arrived), or has stopped serving; called once each way per launch. */
    private val onRunning: (Boolean) -> Unit,
    private val stdinCapacity: Int = STDIN_LINES,
) {
    private val logger = LoggerFactory.getLogger(LinkSidecar::class.java)

    /** Guards [launch], [stopped] and each launch's [Launch.ended] and [Launch.serving]. */
    private val lock = Any()
    private var launch: Launch? = null
    private var stopped = false
    private val generation = AtomicInteger()
    private val unknown = OnceLog()

    /** How many times a sidecar has been launched; tests read it. */
    val launches = AtomicInteger()

    /** Whether the current launch has said it serves. */
    val running: Boolean get() = synchronized(lock) { launch?.serving == true }

    private inner class Launch(val id: Int, val process: Process) {
        val stdin = ArrayBlockingQueue<String>(stdinCapacity)
        val lines = Channel<String>(STDOUT_LINES)
        var ended = false
        var serving = false
    }

    /** Launch the sidecar, unless one runs or [stop] has been called. */
    fun start() {
        val l = synchronized(lock) {
            if (stopped || launch != null) return
            val process = try {
                ProcessBuilder(command).start()
            } catch (e: Exception) {
                logger.warn("Couldn't start the sidecar {}: {}", command.firstOrNull(), e.message)
                return
            }
            Launch(generation.incrementAndGet(), process).also {
                launch = it
                launches.incrementAndGet()
            }
        }
        logger.info("Started the sidecar (pid {})", l.process.pid())
        val secret = newSecret()
        thread("link-stdin-${l.id}") { writeStdin(l, secret) }
        thread("link-stdout-${l.id}") { readStdout(l) }
        thread("link-stderr-${l.id}") { drainStderr(l) }
        scope.launch { consume(l) }
    }

    /** Stop for good: the sidecar is asked to end its sessions and exit, and killed if it has not in 5 s. Never blocks. */
    fun stop() {
        val l = synchronized(lock) {
            stopped = true
            launch
        } ?: return
        finish(l, kill = false)
    }

    /**
     * Queue [message] for the sidecar's stdin. False when no sidecar serves, or when the queue is full,
     * after which the sidecar is restarted as hung.
     */
    fun send(message: LinkIn): Boolean {
        val l = synchronized(lock) { launch?.takeIf { it.serving && !it.ended } } ?: return false
        if (l.stdin.offer(message.encode())) return true
        logger.warn("The sidecar stopped reading its stdin; restarting it")
        if (finish(l, kill = true)) start()
        return false
    }

    /**
     * End [l], once: its queues closed, [onRunning] told if it served, and the process stopped on a
     * thread of its own, since a write blocked on its full stdin holds the pipe's lock. True for the
     * call that ended it.
     */
    private fun finish(l: Launch, kill: Boolean): Boolean {
        val wasServing = synchronized(lock) {
            if (l.ended) return false
            l.ended = true
            if (launch === l) launch = null
            l.serving.also { l.serving = false }
        }
        l.stdin.clear()
        l.lines.close()
        if (wasServing) onRunning(false)
        thread("link-stop-${l.id}") {
            if (kill) {
                l.process.destroyForcibly()
            } else {
                l.process.destroy()
                if (!l.process.waitFor(STOP_GRACE_S, TimeUnit.SECONDS)) l.process.destroyForcibly()
            }
        }
        return true
    }

    private fun writeStdin(l: Launch, secret: String) {
        val out: OutputStream = l.process.outputStream
        try {
            out.write((secret + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            while (synchronized(lock) { !l.ended }) {
                val line = l.stdin.poll(1, TimeUnit.SECONDS) ?: continue
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (_: Exception) {
            // The sidecar went: its stdout's end says so.
        } finally {
            runCatching { out.close() }
        }
    }

    private fun readStdout(l: Launch) {
        val lines = BoundedLines(l.process.inputStream, MAX_LINE_BYTES)
        try {
            while (true) {
                val line = when (val next = lines.next() ?: break) {
                    is BoundedLines.Line.Text -> next.text
                    BoundedLines.Line.TooLong -> {
                        logger.warn("Skipping a line from the sidecar over {} bytes", MAX_LINE_BYTES)
                        continue
                    }
                }
                // Blocks this thread, and through the pipe the sidecar, while the cloud's threads catch up.
                if (l.lines.trySendBlocking(line).isClosed) break
            }
        } catch (_: Exception) {
        } finally {
            val code = runCatching { l.process.waitFor() }.getOrNull()
            if (finish(l, kill = false)) logger.warn("The sidecar exited ({})", code)
        }
    }

    private fun drainStderr(l: Launch) {
        val lines = BoundedLines(l.process.errorStream, MAX_LINE_BYTES)
        var windowStart = System.nanoTime()
        var logged = 0
        var dropped = 0
        try {
            while (true) {
                val line = lines.next() ?: break
                val now = System.nanoTime()
                if (now - windowStart > STDERR_WINDOW_NS) {
                    if (dropped > 0) logger.warn("lighting7-link: {} lines not logged", dropped)
                    windowStart = now
                    logged = 0
                    dropped = 0
                }
                if (logged++ < STDERR_LINES_PER_WINDOW) {
                    val text = (line as? BoundedLines.Line.Text)?.text ?: "(a line over $MAX_LINE_BYTES bytes)"
                    logger.info("lighting7-link: {}", text.take(MAX_LOGGED_CHARS))
                } else {
                    dropped++
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun consume(l: Launch) {
        var handled = 0
        for (line in l.lines) {
            when (val message = LinkOut.decodeOrNull(line)) {
                null -> unknown.once("line") { logger.info("Ignoring a line from the sidecar that is no message") }
                is LinkOut.Started -> {
                    val now = synchronized(lock) {
                        (launch === l && !l.ended && !l.serving).also { if (it) l.serving = true }
                    }
                    if (now) onRunning(true)
                }
                else -> if (synchronized(lock) { !l.ended }) {
                    runCatching { onMessage(message) }.onFailure { logger.warn("Handling the sidecar's {} failed", message::class.simpleName, it) }
                }
            }
            // A flood of lines shares the cloud's threads rather than holding one.
            if (++handled % 64 == 0) yield()
        }
    }

    private fun thread(name: String, body: () -> Unit) {
        Thread(body, name).apply { isDaemon = true }.start()
    }

    companion object {
        /** Lines queued for the sidecar's stdin before it is taken as hung. */
        const val STDIN_LINES = 256

        /** Lines read from its stdout before its reader waits for the cloud's threads. */
        const val STDOUT_LINES = 32

        /** The longest line read from the sidecar; an answer's SDP is a few KiB. */
        const val MAX_LINE_BYTES = 256 * 1024

        private const val STOP_GRACE_S = 5L
        private const val STDERR_LINES_PER_WINDOW = 50
        private const val STDERR_WINDOW_NS = 10_000_000_000L
        private const val MAX_LOGGED_CHARS = 500
    }
}

/** Lines of at most [max] bytes from [input]; a longer one is skipped and answered as [Line.TooLong]. */
internal class BoundedLines(input: InputStream, private val max: Int) {
    sealed interface Line {
        data class Text(val text: String) : Line

        data object TooLong : Line
    }

    private val stream = input.buffered(64 * 1024)
    private val buf = java.io.ByteArrayOutputStream()

    /** The next line, or null at the end of the stream. */
    fun next(): Line? {
        buf.reset()
        var tooLong = false
        while (true) {
            val b = stream.read()
            if (b < 0) {
                return when {
                    tooLong -> Line.TooLong
                    buf.size() > 0 -> Line.Text(buf.toString(Charsets.UTF_8))
                    else -> null
                }
            }
            if (b == '\n'.code) return if (tooLong) Line.TooLong else Line.Text(buf.toString(Charsets.UTF_8).removeSuffix("\r"))
            if (tooLong) continue
            if (buf.size() >= max) {
                tooLong = true
                buf.reset()
            } else {
                buf.write(b)
            }
        }
    }
}
