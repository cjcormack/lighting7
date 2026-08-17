package uk.me.cormack.lighting7.launcher

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The generated command line as a pure string. None of this can be exercised on a Mac, and a
 * quoting bug would only ever surface as a lighting desk that failed to come back — so the shape
 * is pinned here rather than discovered on someone's Windows box.
 */
class WindowsUpdateApplyTest {
    private val msi = Path.of("""C:\Users\chris\AppData\Roaming\lighting7\updates\staged\lighting7-1.2.0.msi""")
    private val result = Path.of("""C:\Users\chris\AppData\Roaming\lighting7\updates\apply-result.properties""")
    private val exe = Path.of("""C:\Program Files\lighting7\lighting7.exe""")

    private fun script(
        msiPath: Path = msi,
        relaunchExe: Path? = exe,
        pid: Long = 4242L,
    ) = WindowsUpdateApply.buildScript(pid, msiPath, result, "1.2.0", relaunchExe)

    @Test
    fun `single quotes are doubled, not backslash-escaped`() {
        // PowerShell single-quoted literals escape a quote by doubling it. A backslash escape —
        // the instinct from every other shell — would terminate the string early.
        assertEquals("'plain'", WindowsUpdateApply.psQuote("plain"))
        assertEquals("'it''s'", WindowsUpdateApply.psQuote("it's"))
        assertEquals("""'C:\Program Files\x'""", WindowsUpdateApply.psQuote("""C:\Program Files\x"""))
    }

    @Test
    fun `a path containing an apostrophe cannot break out of its literal`() {
        val awkward = Path.of("""C:\Users\Chris O'Brien\AppData\lighting7\updates\staged\l7.msi""")
        val body = script(msiPath = awkward)

        assertTrue(body.contains("""'C:\Users\Chris O''Brien\AppData\lighting7\updates\staged\l7.msi'"""))
    }

    @Test
    fun `paths with spaces stay inside one quoted argument`() {
        assertTrue(script().contains("""'C:\Program Files\lighting7\lighting7.exe'"""))
    }

    /** Gates on the JVM actually exiting — a fixed sleep would be a race that passes locally. */
    @Test
    fun `waits on the launcher pid before touching the installer`() {
        val body = script(pid = 4242L)

        assertTrue(body.contains("Wait-Process -Id 4242"))
        assertTrue(
            body.indexOf("Wait-Process") < body.indexOf("msiexec.exe"),
            "the wait must come before msiexec",
        )
    }

    @Test
    fun `elevates msiexec but not the relaunch`() {
        val body = script()

        assertEquals(1, Regex("-Verb RunAs").findAll(body).count())
        val runAsAt = body.indexOf("-Verb RunAs")
        val relaunchAt = body.indexOf("Start-Process -FilePath 'C:\\Program Files")
        assertTrue(runAsAt < relaunchAt, "RunAs must be on msiexec, not on the relaunch")
    }

    @Test
    fun `uses a basic UI install that can never reboot the desk`() {
        val body = script()

        assertTrue(body.contains("'/qb'"))
        assertTrue(body.contains("'/norestart'"))
        // /qn fails 1925 unelevated and shows nothing during a multi-hundred-MB install;
        // /passive adds a cancel button that can leave the app half-installed.
        assertFalse(body.contains("'/qn'"))
        assertFalse(body.contains("/passive"))
    }

    @Test
    fun `records the exit code even when the UAC prompt is cancelled`() {
        val body = script()

        // A cancelled RunAs prompt throws rather than returning, so the default must already be
        // the cancel code (1602) before the try block runs.
        assertTrue(body.contains("\$code = 1602"))
        assertTrue(body.contains("catch { \$code = 1602 }"))
        assertTrue(body.contains("WriteAllText"))
        assertTrue(body.contains("exitCode="))
    }

    @Test
    fun `relaunches after the install and tolerates a missing exe`() {
        assertTrue(script().contains("Start-Process -FilePath 'C:\\Program Files\\lighting7\\lighting7.exe'"))
        // With no exe resolved the install still proceeds; the shortcuts remain the way back in.
        val noRelaunch = script(relaunchExe = null)
        assertFalse(noRelaunch.contains("lighting7.exe"))
        assertTrue(noRelaunch.contains("msiexec.exe"))
    }

    @Test
    fun `the argv disables the profile so a noisy user profile cannot hang the install`() {
        val command = WindowsUpdateApply.buildCommand(1L, msi, result, "1.2.0", exe)

        assertEquals("powershell.exe", command.first())
        assertTrue(command.contains("-NoProfile"))
        assertTrue(command.contains("-NonInteractive"))
        assertEquals("-Command", command[command.size - 2])
        // The script is one argument; splitting it would reinterpret every quote.
        assertEquals(script(pid = 1L), command.last())
    }
}
