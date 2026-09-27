package uk.me.cormack.lighting7.launcher

import java.io.DataInputStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The command line the launcher spawns the backend with — the packaged app's only way to pass JVM
 * options, since `./gradlew run`'s `applicationDefaultJvmArgs` never reach an installed desk.
 */
class BackendCommandLineTest {

    private val java = Path.of("runtime", "bin", "java").toAbsolutePath()
    private val jar = Path.of("app", "lighting7.jar").toAbsolutePath()

    @Test
    fun `the backend is spawned with the Unsafe opt-in before -jar`() {
        assertEquals(
            listOf(java.toString(), "--sun-misc-unsafe-memory-access=allow", "-jar", jar.toString()),
            ChildProcess.commandLine(java, jar, BACKEND_JVM_ARGS),
            "a JVM option after the jar path would be handed to main() as an argument instead",
        )
    }

    @Test
    fun `application arguments still follow the jar`() {
        assertEquals(
            listOf(java.toString(), "-Xmx1g", "-jar", jar.toString(), "--flag"),
            ChildProcess.commandLine(java, jar, jvmArgs = listOf("-Xmx1g"), args = listOf("--flag")),
        )
    }

    /**
     * `--sun-misc-unsafe-memory-access` is an unrecognised option before JDK 23 and aborts JVM
     * start, so [BACKEND_JVM_ARGS] is ungated only because the child's `java` is the launcher's own
     * runtime — and that runtime can only be 23+ while launcher.jar's class files demand it. If the
     * launcher's toolchain is ever lowered, this fails and the flag needs a version gate.
     */
    @Test
    fun `the launcher cannot run on a JVM that would reject the flag`() {
        val bytes = LauncherMarker::class.java.getResourceAsStream("LauncherMarker.class")
            ?: error("LauncherMarker.class not found on the test classpath")
        val major = DataInputStream(bytes).use { input ->
            check(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
            input.readUnsignedShort() // minor
            input.readUnsignedShort()
        }
        // Class-file major 67 is Java 23, the release that introduced the flag.
        assertTrue(major >= 67, "launcher classes target class-file major $major (< 67, Java 23)")
    }
}
