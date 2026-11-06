package uk.me.cormack.lighting7.mcp.tunnel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.process.PidFile
import uk.me.cormack.lighting7.process.WindowsJob
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** What the tunnel is doing, as the Remote access tab shows it. */
sealed interface TunnelState {
    /** Remote access is turned off (or not set up). */
    data object Off : TunnelState

    /** Downloading the pinned ngrok agent, the first time remote access is turned on. */
    data class Installing(val downloadedBytes: Long, val totalBytes: Long?) : TunnelState

    data object Starting : TunnelState

    data class Online(val url: String) : TunnelState

    /**
     * [retrying] means the desk will try again by itself (the agent crashed, or lost its
     * connection); otherwise only the operator can fix it — a rejected authtoken, a domain this
     * account doesn't own.
     */
    data class Error(val code: String?, val message: String, val retrying: Boolean) : TunnelState

    /** There is no agent to run: none is pinned for this platform and none is configured. */
    data class NoBinary(val message: String) : TunnelState
}

/**
 * Runs and supervises the ngrok agent as a child process.
 *
 * One run writes a config into [workDir] (the authtoken, one endpoint `https://<domain>` →
 * `http://127.0.0.1:<port>`, JSON logs on stdout, no local inspection API) and starts
 * `ngrok start --all`. Its log lines drive [onState]. A crash restarts with backoff (1 s doubling
 * to 60 s, reset after a minute online); an `ERR_NGROK_…` error before the endpoint came up —
 * a bad authtoken, a domain the account doesn't own — does **not**, because retrying cannot fix
 * it and hammering ngrok with a bad token is how an account gets rate-limited.
 *
 * The agent must not outlive the desk: an orphan keeps holding the domain, and the next desk's
 * agent then fails to start its endpoint. Three layers: [stop] from the application's shutdown,
 * a JVM shutdown hook for the paths that skip it, and on Windows a Job Object with
 * KILL_ON_JOB_CLOSE so even a killed JVM takes the agent with it ([WindowsJob]). On macOS and Linux
 * a [PidFile] lets the next start kill an orphan — only if that PID is still running *our* agent
 * binary, never anything else (see CLAUDE.md on killing processes the desk didn't start).
 */
class NgrokTunnel(
    private val workDir: Path,
    private val scope: CoroutineScope,
    private val onState: (TunnelState) -> Unit,
    private val launcher: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
    private val backoffMs: (attempt: Int) -> Long = { attempt -> minOf(60_000L, 1_000L shl minOf(attempt, 6)) },
    private val stableAfterMs: Long = 60_000L,
) {
    data class Spec(val binary: Path, val authtoken: String, val domain: String, val upstreamPort: Int)

    private val lock = Any()
    private var job: Job? = null
    /** Bumped by every start and stop; a report from an older run is dropped. */
    @Volatile private var generation = 0
    @Volatile private var process: Process? = null
    @Volatile private var stopping = false

    private val shutdownHook = Thread({ destroyCurrent(waitMs = 2_000) }, "ngrok-shutdown")

    init {
        runCatching { Runtime.getRuntime().addShutdownHook(shutdownHook) }
    }

    /** Stop whatever is running and start [spec]. */
    fun start(spec: Spec) {
        synchronized(lock) {
            stopLocked()
            stopping = false
            val gen = ++generation
            job = scope.launch { supervise(spec, gen) }
        }
    }

    /** Stop the agent and wait for it to exit. Reports nothing: the caller says what state follows. */
    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    /** [stop], and drop the shutdown hook — for when the owner itself is being closed. */
    fun close() {
        stop()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }

    private fun stopLocked() {
        stopping = true
        generation++
        job?.cancel()
        job = null
        destroyCurrent(waitMs = 3_000)
    }

    private fun destroyCurrent(waitMs: Long) {
        val p = process ?: return
        runCatching {
            p.destroy()
            if (!p.waitFor(waitMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                p.waitFor(waitMs, TimeUnit.MILLISECONDS)
            }
        }
        if (!p.isAlive) {
            process = null
            pidFile.delete()
        }
    }

    /**
     * Report [state] unless this run has been superseded. A stopped agent's reader thread can
     * still be draining its last lines after [stop] returned and the owner reported what comes
     * next; without this, its "stopped unexpectedly" would overwrite that.
     */
    private fun report(gen: Int, state: TunnelState) {
        synchronized(lock) {
            if (gen != generation || stopping) return
            onState(state)
        }
    }

    private suspend fun supervise(spec: Spec, gen: Int) {
        sweepOrphan(spec.binary)
        var attempt = 0
        while (scope.isActive && !stopping) {
            report(gen, TunnelState.Starting)
            val configFile = writeConfig(spec)
            val p = try {
                launcher(listOf(spec.binary.toString(), "start", "--all", "--config", configFile.toString()))
            } catch (e: Exception) {
                report(gen, TunnelState.Error(null, "Couldn't start ngrok: ${e.message ?: e.javaClass.simpleName}", retrying = false))
                return
            }
            synchronized(lock) {
                if (stopping) {
                    p.destroyForcibly()
                    return
                }
                process = p
            }
            pidFile.write(p, spec.binary)
            WindowsJob.assign(p, "ngrok")

            val run = RunWatcher(spec.domain, gen)
            try {
                runInterruptible { run.watch(p) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Reading ngrok's output failed: {}", e.message)
            }
            runCatching { p.waitFor(5, TimeUnit.SECONDS) }
            if (!p.isAlive) {
                synchronized(lock) { if (process === p) process = null }
                pidFile.delete()
            }
            if (stopping) return

            run.fatal?.let {
                report(gen, it)
                return
            }
            if (run.onlineForMs() >= stableAfterMs) attempt = 0
            val wait = backoffMs(attempt++)
            val exit = runCatching { p.exitValue() }.getOrNull()
            report(gen, 
                TunnelState.Error(
                    run.lastCode,
                    run.lastError ?: "ngrok stopped unexpectedly${exit?.let { " (exit $it)" } ?: ""}. Restarting…",
                    retrying = true,
                ),
            )
            delay(wait)
        }
    }

    /** One agent run: reads its log lines until the stream ends. */
    private inner class RunWatcher(private val domain: String, private val gen: Int) {
        var fatal: TunnelState.Error? = null
        var lastCode: String? = null
        var lastError: String? = null
        private var onlineSince: Long? = null
        private var everOnline = false

        fun onlineForMs(): Long = onlineSince?.let { System.currentTimeMillis() - it } ?: 0

        fun watch(p: Process) {
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) handle(p, line)
            }
        }

        private fun handle(p: Process, line: String) {
            val event = parseLogLine(line) ?: return
            if (event.online) {
                everOnline = true
                if (onlineSince == null) onlineSince = System.currentTimeMillis()
                report(gen, TunnelState.Online(event.url?.takeIf { it.startsWith("https://") } ?: "https://$domain"))
                return
            }
            if (event.reconnected && everOnline) {
                if (onlineSince == null) onlineSince = System.currentTimeMillis()
                report(gen, TunnelState.Online("https://$domain"))
                return
            }
            if (!event.isError) return
            lastCode = event.code ?: lastCode
            lastError = friendlyMessage(event.code, event.message)
            if (!everOnline && event.code != null) {
                // Before the endpoint ever came up, a coded error is the operator's to fix. Stop
                // the agent rather than leave it retrying a token or domain that won't work.
                fatal = TunnelState.Error(event.code, lastError!!, retrying = false)
                report(gen, fatal!!)
                runCatching { p.destroy() }
            } else if (everOnline) {
                // Lost the session after being up: the agent reconnects by itself.
                onlineSince = null
                report(gen, TunnelState.Error(event.code, lastError!!, retrying = true))
            }
        }
    }

    private fun writeConfig(spec: Spec): Path {
        Files.createDirectories(workDir)
        val file = workDir.resolve("ngrok.yml")
        // YAML accepts JSON strings, so every value goes through the JSON encoder: a token or
        // domain can't break out of its scalar whatever it contains.
        fun q(s: String) = JsonPrimitive(s).toString()
        val yaml = buildString {
            appendLine("# Written by lighting7 on every start. Edits are overwritten.")
            appendLine("version: 3")
            appendLine("agent:")
            appendLine("  authtoken: ${q(spec.authtoken)}")
            appendLine("  web_addr: false")
            appendLine("  update_check: false")
            appendLine("  log: stdout")
            appendLine("  log_format: json")
            appendLine("  log_level: info")
            appendLine("endpoints:")
            appendLine("  - name: lighting7")
            appendLine("    url: ${q("https://${spec.domain}")}")
            appendLine("    upstream:")
            appendLine("      url: ${q("http://127.0.0.1:${spec.upstreamPort}")}")
        }
        // Owner-only: the file holds the authtoken.
        Files.deleteIfExists(file)
        runCatching {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }.onFailure { Files.createFile(file) }
        Files.writeString(file, yaml, StandardOpenOption.TRUNCATE_EXISTING)
        return file
    }

    private val pidFile = PidFile(workDir.resolve("ngrok.pid"), "an ngrok agent")

    /**
     * Kill an agent a previous run of this desk left behind — only when the PID file's process is
     * alive *and* is running [binary]. A PID reused by anything else is left alone.
     */
    internal fun sweepOrphan(binary: Path) = pidFile.sweepOrphan(binary)

    companion object {
        private val log = LoggerFactory.getLogger(NgrokTunnel::class.java)
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val codePattern = Regex("ERR_NGROK_\\d+")

        internal data class LogEvent(
            val online: Boolean,
            val reconnected: Boolean,
            val isError: Boolean,
            val code: String?,
            val message: String,
            val url: String?,
        )

        /**
         * One line of ngrok's output. JSON lines are the agent's log (`lvl`, `msg`, `err`, `url`);
         * anything else is plain text it printed before its logger was up (a config error), which
         * counts as an error only if it names an `ERR_NGROK_…` code or starts with `ERROR`.
         */
        internal fun parseLogLine(line: String): LogEvent? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            val obj: JsonObject? = if (trimmed.startsWith("{")) {
                runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            } else null
            if (obj == null) {
                val code = codePattern.find(trimmed)?.value
                val isError = code != null || trimmed.startsWith("ERROR", ignoreCase = true)
                return LogEvent(false, false, isError, code, trimmed.removePrefix("ERROR:").trim(), null)
            }
            fun field(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val lvl = field("lvl").orEmpty().lowercase()
            val msg = field("msg").orEmpty()
            val err = field("err")?.takeIf { it.isNotBlank() && it != "<nil>" }
            val url = field("url")
            val code = codePattern.find(err.orEmpty())?.value ?: codePattern.find(msg)?.value
            val online = msg == "started tunnel" || msg == "started endpoint" ||
                (msg.startsWith("started") && url?.startsWith("https://") == true)
            val reconnected = msg == "client session established"
            val isError = lvl == "eror" || lvl == "error" || lvl == "crit" || code != null
            return LogEvent(online, reconnected, isError, code, (err ?: msg).trim(), url)
        }

        /** ngrok's own text, with a plainer lead for the errors an operator meets first. */
        internal fun friendlyMessage(code: String?, raw: String): String {
            val detail = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").take(400)
            val lead = when (code) {
                "ERR_NGROK_105", "ERR_NGROK_106", "ERR_NGROK_107", "ERR_NGROK_4018" ->
                    "ngrok didn't accept the authtoken. Paste a fresh one from the ngrok dashboard."
                "ERR_NGROK_108" -> "This ngrok account is already running its maximum number of agents."
                "ERR_NGROK_320", "ERR_NGROK_319", "ERR_NGROK_15002" ->
                    "ngrok won't serve this domain for this account. Check the domain in the ngrok dashboard."
                else -> null
            }
            return if (lead == null) detail else "$lead ($detail)"
        }
    }
}
