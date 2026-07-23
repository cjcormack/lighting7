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
private const val COMPILER_PORT = 8321

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

    // Refuse a second launch for the same data dir before spawning any children, so a
    // double-launch is one clean log line rather than a second compiler-server fighting for
    // port 8321. The backend enforces its own lock too (covers direct `java -jar` / dev runs).
    LauncherLock.acquireOrExit(dataDir)

    val backendJar = resolveJar("lighting7.jar")
    val compilerJar = resolveJar("kotlin-compiler-server.jar")
    val javaBin = resolveJavaExecutable()

    ensureDefaultConfig(dataDir)

    println("lighting7 launcher")
    println("  java        = $javaBin")
    println("  backend jar = $backendJar")
    println("  compiler jar= $compilerJar")
    println("  data dir    = $dataDir")
    println("  logs dir    = $logsDir")

    // The compiler server's bundled logback config writes `./logs/spring-boot-logger.log`
    // relative to its CWD, so the CWD has to be writable. Use a per-user dir under
    // appDataDir; pass absolute paths to the kotlin library directories so they resolve
    // regardless of CWD (the lib dirs themselves stay read-only inside the install bundle).
    val compilerWorkDir = dataDir.resolve("compiler-server").also { Files.createDirectories(it) }
    val compilerLibsDir = compilerJar.parent
    val compiler = ChildProcess.spawn(
        name = "kotlin-compiler-server",
        java = javaBin,
        jar = compilerJar,
        args = listOf(
            "--server.port=$COMPILER_PORT",
            "--server.address=127.0.0.1",
            "--libraries.folder.jvm=${compilerLibsDir.resolve("2.1.21")}",
            "--libraries.folder.js=${compilerLibsDir.resolve("2.1.21-js")}",
            "--libraries.folder.wasm=${compilerLibsDir.resolve("2.1.21-wasm")}",
            "--libraries.folder.compose-wasm=${compilerLibsDir.resolve("2.1.21-compose-wasm")}",
            "--libraries.folder.compose-wasm-compiler-plugins=${compilerLibsDir.resolve("2.1.21-compose-wasm-compiler-plugins")}",
            "--libraries.folder.compiler-plugins=${compilerLibsDir.resolve("2.1.21-compiler-plugins")}",
        ),
        workingDir = compilerWorkDir,
        logFile = logsDir.resolve("compiler-server.log"),
    )

    val backend = ChildProcess.spawn(
        name = "lighting7",
        java = javaBin,
        jar = backendJar,
        workingDir = dataDir,
        // Pin the backend to the launcher's resolved data dir. The child would inherit
        // LIGHTING7_DATA_DIR through the environment, but a `-Dlighting7.dataDir` override
        // on the launcher would not propagate — forwarding the resolved value covers both.
        env = mapOf("LIGHTING7_DATA_DIR" to dataDir.toAbsolutePath().toString()),
        logFile = logsDir.resolve("lighting7.log"),
    )

    val children = listOf(compiler, backend)

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
    while (children.all { it.isAlive }) {
        Thread.sleep(500)
    }

    val dead = children.first { !it.isAlive }
    println("${dead.name} exited (code=${dead.exitValue}) — stopping the rest.")
    children.forEach { runCatching { it.stop() } }
    exitProcess(1)
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
 * Resolve a JAR by filename. Prefers `-D<jarName>=...` (set by `:launcher:run`), falls
 * back to a sibling of the launcher's own JAR / classes dir (the jpackage `app/` layout).
 */
private fun resolveJar(jarName: String): Path {
    System.getProperty(jarName)?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }

    val launcherLocation = runCatching {
        Path.of(LauncherMarker::class.java.protectionDomain.codeSource.location.toURI())
    }.getOrNull() ?: error("Cannot resolve launcher location to find $jarName — pass -D$jarName=...")

    val baseDir = if (Files.isDirectory(launcherLocation)) launcherLocation else launcherLocation.parent
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
