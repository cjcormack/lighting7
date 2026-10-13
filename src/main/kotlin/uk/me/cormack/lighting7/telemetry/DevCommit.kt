package uk.me.cormack.lighting7.telemetry

import java.util.concurrent.TimeUnit

/**
 * The checkout's commit, for a dev run's release name. Read at runtime rather than baked by Gradle:
 * `build.gradle.kts` keeps the sha out of the build's inputs on purpose (see `buildCommitSha`).
 */
object DevCommit {
    private val SHORT_SHA = Regex("[0-9a-f]{7,40}")

    /** `git rev-parse --short HEAD` in the working directory, or null if that is not a checkout. */
    fun resolve(): String? = runCatching {
        val process = ProcessBuilder("git", "rev-parse", "--short=7", "HEAD").redirectErrorStream(true).start()
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        val out = process.inputStream.bufferedReader().readText().trim()
        out.takeIf { process.exitValue() == 0 && SHORT_SHA.matches(it) }
    }.getOrNull()
}
