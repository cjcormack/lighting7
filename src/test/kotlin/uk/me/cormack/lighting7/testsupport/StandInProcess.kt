package uk.me.cormack.lighting7.testsupport

import java.nio.file.Files
import java.nio.file.Path

/**
 * A long-lived process whose command is a path of the test's choosing, standing in for a binary the desk
 * supervises (lighting7-link, an ngrok agent). The stand-in is a copy of bash waiting up to [SECONDS] on
 * its stdin, a pipe the JVM holds open, so it needs no child and stops when killed.
 *
 * Not a copy of `sleep`: Rust coreutils (Ubuntu 26.04) is one binary that picks its command from the name
 * it is run as, so a copy under another name exits at once.
 */
object StandInProcess {
    const val SECONDS = 60

    /** Copies bash to [path]; null where there is no bash to copy. */
    fun install(path: Path): Path? {
        val bash = listOf("/bin/bash", "/usr/bin/bash").map(Path::of).firstOrNull { Files.isExecutable(it) } ?: return null
        Files.createDirectories(path.parent)
        Files.copy(bash, path)
        path.toFile().setExecutable(true)
        return path
    }

    /** The command that runs the stand-in installed at [path]. */
    fun command(path: Path): List<String> = listOf(path.toString(), "-c", "read -t $SECONDS")
}
