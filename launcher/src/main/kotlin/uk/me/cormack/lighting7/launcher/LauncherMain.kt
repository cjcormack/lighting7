package uk.me.cormack.lighting7.launcher

import java.awt.Desktop
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.system.exitProcess

private const val BACKEND_PORT = 8413
private const val COMPILER_PORT = 8321
private const val READINESS_TIMEOUT_MS = 30_000L

/** Marker used to resolve the launcher's own JAR / classpath via [Class.protectionDomain]. */
internal object LauncherMarker

fun main() {
    val backendJar = resolveJar("lighting7.jar")
    val compilerJar = resolveJar("kotlin-compiler-server.jar")
    val javaBin = resolveJavaExecutable()

    val dataDir = appDataDir()
    val logsDir = dataDir.resolve("logs").also { Files.createDirectories(it) }

    println("lighting7 launcher")
    println("  java        = $javaBin")
    println("  backend jar = $backendJar")
    println("  compiler jar= $compilerJar")
    println("  data dir    = $dataDir")
    println("  logs dir    = $logsDir")

    val compiler = ChildProcess.spawn(
        name = "kotlin-compiler-server",
        java = javaBin,
        jar = compilerJar,
        args = listOf(
            "--server.port=$COMPILER_PORT",
            "--server.address=127.0.0.1",
        ),
        logFile = logsDir.resolve("compiler-server.log"),
    )

    val backend = ChildProcess.spawn(
        name = "lighting7",
        java = javaBin,
        jar = backendJar,
        workingDir = dataDir,
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
