package uk.me.cormack.lighting7.launcher

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around a child JVM the launcher spawns. Streams stdout+stderr to a
 * single per-child log file so the user can `View Logs` from the tray menu and
 * still see startup banners after the fact.
 */
class ChildProcess(val name: String, val process: Process) {
    fun stop(timeoutMs: Long = 5_000) {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
        }
    }

    val isAlive: Boolean get() = process.isAlive
    val exitValue: Int? get() = if (process.isAlive) null else process.exitValue()

    companion object {
        fun spawn(
            name: String,
            java: Path,
            jar: Path,
            args: List<String> = emptyList(),
            workingDir: Path? = null,
            env: Map<String, String> = emptyMap(),
            logFile: Path,
        ): ChildProcess {
            Files.createDirectories(logFile.parent)
            val cmd = mutableListOf(java.toAbsolutePath().toString(), "-jar", jar.toAbsolutePath().toString())
            cmd += args
            val pb = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                // Inherit the parent's (likely null) stdin. `Redirect.DISCARD` is output-only
                // and throws `IllegalArgumentException: Redirect invalid for reading: WRITE`
                // when passed to `redirectInput`. INHERIT is fine here: when the launcher
                // is launched from Finder, stdin is already null; when launched from a
                // terminal during dev, our Java children don't read stdin anyway.
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
            if (workingDir != null) pb.directory(workingDir.toFile())
            if (env.isNotEmpty()) pb.environment().putAll(env)
            return ChildProcess(name, pb.start())
        }
    }
}
