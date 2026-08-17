package uk.me.cormack.lighting7.update

import org.junit.Test
import java.time.Instant
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BuildInfoTest {
    private fun props(vararg pairs: Pair<String, String>) = Properties().apply {
        pairs.forEach { (k, v) -> setProperty(k, v) }
    }

    @Test
    fun `parses a release build`() {
        val info = BuildInfo.parse(
            props(
                "version" to "1.4.2",
                "channel" to "release",
                "commitSha" to "0123456789abcdef",
                "commitTimestamp" to "2026-08-17T09:00:00Z",
            )
        )

        assertEquals("1.4.2", info.version)
        assertEquals(BuildInfo.Channel.RELEASE, info.channel)
        assertEquals("0123456", info.shortSha)
        assertEquals(Instant.parse("2026-08-17T09:00:00Z"), info.commitTimestamp)
    }

    /**
     * The channel gate is what stops a hand-built `./gradlew packageWindows` MSI from offering
     * to msiexec over itself, so anything that isn't exactly "release" has to land on DEV. A
     * typo in the generated resource must not enable self-update.
     */
    @Test
    fun `channel fails closed to DEV for anything but the exact literal`() {
        listOf("dev", "RELEASE", "Release", "release ", "nightly", "", "true").forEach { raw ->
            assertEquals(
                BuildInfo.Channel.DEV,
                BuildInfo.parse(props("version" to "1.4.2", "channel" to raw)).channel,
                "channel='$raw' should not be treated as a release build",
            )
        }
    }

    @Test
    fun `an absent resource degrades to an unknown dev build rather than throwing`() {
        val info = BuildInfo.parse(Properties())

        assertEquals(BuildInfo.UNKNOWN_VERSION, info.version)
        assertEquals(BuildInfo.Channel.DEV, info.channel)
        assertNull(info.commitSha)
        assertNull(info.commitTimestamp)
    }

    @Test
    fun `blank commit fields read as absent rather than as empty strings`() {
        val info = BuildInfo.parse(
            props("version" to "1.4.2", "channel" to "release", "commitSha" to "", "commitTimestamp" to "")
        )

        assertNull(info.commitSha)
        assertNull(info.shortSha)
        assertNull(info.commitTimestamp)
    }

    /** Display metadata only — a bad value must not take out the whole build identity. */
    @Test
    fun `an unparseable commit timestamp degrades to null and keeps the rest`() {
        val info = BuildInfo.parse(
            props("version" to "1.4.2", "channel" to "release", "commitTimestamp" to "last Tuesday")
        )

        assertNull(info.commitTimestamp)
        assertEquals("1.4.2", info.version)
        assertEquals(BuildInfo.Channel.RELEASE, info.channel)
    }

    @Test
    fun `install kind reads the launcher's environment and fails closed to DEV`() {
        assertEquals(InstallKind.PACKAGED, InstallKind.fromEnv { "packaged" })
        assertEquals(InstallKind.DEV, InstallKind.fromEnv { "dev" })
        assertEquals(InstallKind.DEV, InstallKind.fromEnv { null })
        assertEquals(InstallKind.DEV, InstallKind.fromEnv { "PACKAGED" })
    }
}
