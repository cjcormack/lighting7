package uk.me.cormack.lighting7.launcher

import java.awt.Desktop
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import kotlin.system.exitProcess

private const val BACKEND_PORT = 8413

// Override on slow hosts (e.g. x64 JRE under Windows-on-ARM emulation) via
// `-Dlighting7.readinessTimeoutMs=…` or the `LIGHTING7_READINESS_TIMEOUT_MS` env var.
private val READINESS_TIMEOUT_MS: Long = run {
    val raw = System.getProperty("lighting7.readinessTimeoutMs")
        ?: System.getenv("LIGHTING7_READINESS_TIMEOUT_MS")
    raw?.toLongOrNull()?.takeIf { it > 0 } ?: 600_000L
}

/** Marker used to resolve the launcher's own JAR / classpath via [Class.protectionDomain]. */
internal object LauncherMarker

fun main() {
    // Redirect launcher stdout/stderr to a log file before any other work — when
    // launched from Finder the .app's stdout/stderr go nowhere, so any pre-spawn
    // crash (e.g. a bad ProcessBuilder argument) is otherwise silent.
    val dataDir = appDataDir()
    val logsDir = dataDir.resolve("logs").also { Files.createDirectories(it) }
    redirectLauncherIo(logsDir.resolve("launcher.log"))

    // Refuse a second launch for the same data dir before spawning the backend, so a
    // double-launch is one clean log line rather than two processes fighting for port 8413.
    // The backend enforces its own lock too (covers direct `java -jar` / dev runs).
    LauncherLock.acquireOrExit(dataDir)

    // Anything left in updates/ belongs to a previous run: an apply that was interrupted by a
    // power cut, a tray Quit, or a crash. Clearing it here is what makes "quitting cancels the
    // update" true, and stops an update nobody is present for from firing days later.
    // Captured before the children start so a marker can only ever be acted on if it was written
    // by *this* run of the app.
    val launcherStartMs = System.currentTimeMillis()
    val launcherPid = ProcessHandle.current().pid()
    UpdateMarker.clearStale(dataDir)

    val backendJar = resolveJar("lighting7.jar")
    val javaBin = resolveJavaExecutable()

    ensureDefaultConfig(dataDir)

    // Where the app was installed from, resolved once and shared with the backend below. The
    // backend is a plain `java -jar` child either way and cannot work this out for itself.
    val baseDir = launcherBaseDir()
    val installKind = detectInstallKind(baseDir)
    val installRoot = installRoot(baseDir)

    println("lighting7 launcher")
    println("  version     = ${LauncherBuildInfo.version} (${if (LauncherBuildInfo.isRelease) "release" else "dev"})")
    println("  install     = $installKind${installRoot?.let { " at $it" } ?: ""}")
    println("  java        = $javaBin")
    println("  backend jar = $backendJar")
    println("  data dir    = $dataDir")
    println("  logs dir    = $logsDir")

    val backend = ChildProcess.spawn(
        name = "lighting7",
        java = javaBin,
        jar = backendJar,
        workingDir = dataDir,
        // Pin the backend to the launcher's resolved data dir. The child would inherit
        // LIGHTING7_DATA_DIR through the environment, but a `-Dlighting7.dataDir` override
        // on the launcher would not propagate — forwarding the resolved value covers both.
        //
        // The install kind and root ride along for the same reason: they are facts about how
        // *this process* was started, and the backend — a plain `java -jar` child — has no way
        // to observe them. The update path gates on both.
        env = buildMap {
            put("LIGHTING7_DATA_DIR", dataDir.toAbsolutePath().toString())
            put("LIGHTING7_INSTALL_KIND", installKind)
            installRoot?.let { put("LIGHTING7_INSTALL_ROOT", it.toAbsolutePath().toString()) }
        },
        logFile = logsDir.resolve("lighting7.log"),
    )

    // A list of one, kept as a list because everything below — shutdown hook, tray Quit,
    // readiness polling — was written to supervise a set, and the launcher's job is still
    // "own the child processes" even now that there is exactly one. The script editor used to
    // be the second: a bundled kotlin-compiler-server on 127.0.0.1:8321. It is served in-process
    // by the backend now.
    val children = listOf(backend)

    val onQuit = {
        println("Shutting down children…")
        children.forEach { runCatching { it.stop() } }
        exitProcess(0)
    }
    Runtime.getRuntime().addShutdownHook(Thread { children.forEach { runCatching { it.stop() } } })

    if (!awaitBackendReady(BACKEND_PORT, children)) {
        println("Backend did not become ready within ${READINESS_TIMEOUT_MS}ms — see $logsDir/lighting7.log")
        children.forEach { runCatching { it.stop() } }
        exitProcess(1)
    }

    val localUrl = "http://localhost:$BACKEND_PORT/"
    val lanUrl = "http://${deriveServiceName()}.local:$BACKEND_PORT/"

    runCatching { Desktop.getDesktop().browse(URI(localUrl)) }
        .onFailure { println("Could not open browser: ${it.message}") }

    installTray(localUrl = localUrl, lanUrl = lanUrl, logsDir = logsDir, onQuit = onQuit)

    // Block until a child dies. If the user picks Quit from the tray menu, onQuit calls
    // exitProcess(0) which preempts this loop.
    //
    // The same loop watches for a staged update: the backend cannot install one itself (it is a
    // child, and every file msiexec must replace is held open while it lives), so it drops a
    // marker and this is what acts on it. `Files.exists` first — the parse and the SHA-256 only
    // happen on the rare tick where a marker is actually present.
    while (children.all { it.isAlive }) {
        if (Files.exists(UpdateMarker.markerPath(dataDir))) {
            val request = UpdateMarker.readIfPresent(dataDir, launcherStartMs)
            // Consume before acting, and whether or not it validated. Before, so a crash between
            // here and msiexec cannot re-trigger the same apply on the next boot; regardless of
            // validity, so a marker we refused doesn't get re-read and re-hashed twice a second
            // for the rest of the show.
            UpdateMarker.consume(dataDir)
            if (request != null) {
                applyUpdateAndExit(
                    request = request,
                    dataDir = dataDir,
                    children = children,
                    launcherPid = launcherPid,
                    installRoot = installRoot,
                )
                // Only reached if the apply refused to start — carry on with the old version.
            }
        }
        Thread.sleep(500)
    }

    val dead = children.first { !it.isAlive }
    println("${dead.name} exited (code=${dead.exitValue}) — stopping the rest.")
    children.forEach { runCatching { it.stop() } }
    exitProcess(1)
}

/**
 * Stop everything and hand the staged MSI to `msiexec` through a detached PowerShell wrapper.
 *
 * Returns only if the apply could not be started, in which case the caller carries on running
 * the version already installed — the desk staying up on an old build always beats it going
 * dark over a failed upgrade.
 */
private fun applyUpdateAndExit(
    request: UpdateMarker.Request,
    dataDir: Path,
    children: List<ChildProcess>,
    launcherPid: Long,
    installRoot: Path?,
) {
    println("Applying update ${request.targetVersion} from ${request.msiPath}")

    val relaunchExe = if (request.relaunch) installRoot?.resolve("lighting7.exe") else null
    if (request.relaunch && relaunchExe == null) {
        // Not fatal: the Start-menu and desktop shortcuts jpackage installs still work, and
        // refusing the update would leave the user with no way to apply it at all.
        println("WARNING: could not resolve lighting7.exe to relaunch; the update will install but not restart the app.")
    }

    val command = WindowsUpdateApply.buildCommand(
        launcherPid = launcherPid,
        msiPath = request.msiPath,
        resultPath = dataDir.resolve("updates").resolve(UpdateMarker.RESULT_FILENAME),
        targetVersion = request.targetVersion,
        relaunchExe = relaunchExe,
    )

    // Logged in full before anything is spawned. On a real Windows box, with no console and no
    // debugger, this line is the single most useful artifact when an upgrade misbehaves.
    println("Update command: ${command.joinToString(" ")}")

    // Spawn the wrapper FIRST, while the desk is still fully alive.
    //
    // Ordering here is the whole safety property. Spawning is the only step that can fail, and
    // stopping the children is irreversible — this launcher has no way to bring them back. Doing
    // the irreversible thing first meant a failed spawn left the desk with no backend *and* no
    // update: `applyUpdateAndExit` would return, the caller's `children.all { it.isAlive }` loop
    // condition would already be false, and the app would die via `exitProcess(1)` reporting a
    // misleading "child exited". A desk going dark mid-show is the exact outcome this feature is
    // supposed to avoid.
    //
    // Spawning first is safe because the wrapper's first act is `Wait-Process` on *our* PID: it
    // is armed but blocked, and cannot touch a file until this JVM exits.
    val started = runCatching {
        ProcessBuilder(command)
            .directory(dataDir.toFile())
            // Detached from this JVM's streams: the wrapper outlives us by design, and an
            // inherited pipe with no reader would eventually block it.
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }.onFailure {
        println("ERROR: could not start the update wrapper (${it.message}). Staying on ${LauncherBuildInfo.version}.")
    }.getOrNull()

    // Nothing has been stopped yet, so this really is "carry on with the old version".
    if (started == null) return

    // From here on we are committed: the wrapper is armed and will proceed once we exit (or after
    // its Wait-Process timeout). Returning now would let msiexec run against a live app, so every
    // path below ends in exitProcess.
    println("Update wrapper started (pid=${started.pid()}). Stopping children…")
    children.forEach { runCatching { it.stop() } }

    val stillAlive = children.filter { it.isAlive }
    if (stillAlive.isNotEmpty()) {
        // `stop` escalates to destroyForcibly and waits, so surviving it is close to impossible.
        // Log it anyway: if it ever happens, msiexec will report files in use, and this line is
        // the only thing that will explain why.
        println("WARNING: ${stillAlive.joinToString { it.name }} did not exit; msiexec may find files in use.")
    }

    println("Exiting so msiexec can replace our files.")
    exitProcess(0)
}

private fun redirectLauncherIo(logFile: Path) {
    val stream = PrintStream(
        Files.newOutputStream(logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
        true,
    )
    System.setOut(stream)
    System.setErr(stream)
}

/**
 * Bootstrap a writable `local.conf` next to the SQLite DB on first launch. The backend's
 * `EngineMain -config=local.conf` is resolved against its working directory, which we
 * set to [appDataDir] for the backend child — so `dataDir/local.conf` is what loads at
 * runtime. After a clean install the file is missing; copy the bundled default once.
 */
private fun ensureDefaultConfig(dataDir: Path) {
    val target = dataDir.resolve("local.conf")
    if (Files.exists(target)) return
    val resource = LauncherMarker::class.java.getResourceAsStream("/default-local.conf")
        ?: error("Missing /default-local.conf resource in launcher classpath")
    resource.use { Files.copy(it, target) }
    println("Wrote default config to $target")
}

/**
 * The directory holding the launcher's own JAR (or its classes dir in a dev run). In the
 * jpackage layout this is `<install root>/app/`, which is what makes it the anchor for both
 * sibling-jar resolution and install-kind detection.
 *
 * Null when the location can't be resolved at all — callers decide whether that is fatal.
 */
internal fun launcherBaseDir(): Path? {
    val location = runCatching {
        Path.of(LauncherMarker::class.java.protectionDomain.codeSource.location.toURI())
    }.getOrNull() ?: return null
    return if (Files.isDirectory(location)) location else location.parent
}

/**
 * Resolve a JAR by filename. Prefers `-D<jarName>=...` (set by `:launcher:run`), falls
 * back to a sibling of the launcher's own JAR / classes dir (the jpackage `app/` layout).
 */
private fun resolveJar(jarName: String): Path {
    System.getProperty(jarName)?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }

    val baseDir = launcherBaseDir()
        ?: error("Cannot resolve launcher location to find $jarName — pass -D$jarName=...")

    val candidate = baseDir.resolve(jarName)
    require(Files.exists(candidate)) {
        "Cannot find $jarName next to launcher (looked in $baseDir). Pass -D$jarName=... or stage it next to launcher.jar."
    }
    return candidate
}

private fun resolveJavaExecutable(): Path {
    val javaHome = System.getProperty("java.home") ?: error("java.home not set")
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val exe = if (isWindows) "java.exe" else "java"
    return Path.of(javaHome, "bin", exe).also {
        require(Files.isExecutable(it)) { "Java executable not found at $it" }
    }
}

private fun awaitBackendReady(port: Int, children: List<ChildProcess>): Boolean {
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    val request = HttpRequest.newBuilder(URI("http://localhost:$port/")).timeout(Duration.ofSeconds(2)).build()
    val deadline = System.currentTimeMillis() + READINESS_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
        if (children.any { !it.isAlive }) return false
        val ok = runCatching { client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() }
            .getOrNull()
        if (ok != null && ok < 500) return true
        Thread.sleep(500)
    }
    return false
}

/**
 * Mirrors [uk.me.cormack.lighting7.state.MdnsService.deriveServiceName]. Duplicated so
 * the launcher stays a pure-JDK module.
 */
private fun deriveServiceName(): String {
    val raw = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
    val short = raw?.lowercase()?.substringBefore('.')?.takeIf { it.isNotBlank() } ?: "host"
    val sanitized = short.replace(Regex("[^a-z0-9-]"), "-").trim('-').ifEmpty { "host" }
    return "lighting7-$sanitized"
}
