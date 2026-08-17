package uk.me.cormack.lighting7.launcher

import java.nio.file.Path

/**
 * Builds the PowerShell command that installs a staged MSI after the launcher has exited.
 *
 * A separate file from [UpdateMarker] purely so the quoting is a pure function over strings and
 * can be tested without a Windows box. Quoting bugs here are invisible until they strand a
 * lighting desk on a machine nobody can attach a debugger to.
 *
 * The wrapper does four things in order, and each one is load-bearing:
 *
 *  1. **`Wait-Process` on the launcher's PID.** Not a sleep. `msiexec` has to replace files that
 *     the launcher JVM still holds open, and the only reliable signal that they are released is
 *     that process actually exiting. A fixed delay would be a race that passes on a fast machine.
 *  2. **`Start-Process msiexec -Verb RunAs -Wait`.** `RunAs` is on the *inner* call only, so
 *     exactly one UAC prompt appears and the relaunched app does **not** inherit administrator.
 *     A cancelled prompt throws, which is caught and recorded as 1602 (ERROR_INSTALL_USERCANCEL).
 *  3. **Write `apply-result.properties`.** The backend reads this on the next boot to decide
 *     whether to sweep the staged MSI or report a failure. Without it a failed upgrade is
 *     indistinguishable from one that never started.
 *  4. **Relaunch, unconditionally.** Success or failure, the desk must come back. A desk running
 *     the old version is a nuisance; a desk that vanishes mid-show is not.
 */
internal object WindowsUpdateApply {

    /**
     * `/qb` — a basic progress bar, no prompts, no cancel button.
     *
     * Not `/qn`: a silent per-machine install from a non-elevated context fails with 1925, and
     * even elevated it gives no feedback at all during a multi-hundred-megabyte install, so a
     * user watching a black screen has no way to tell it apart from a hang. Not `/passive`
     * either — its cancel button can leave a half-installed application.
     *
     * `/norestart` because this may be a lighting desk mid-show and the installer must never
     * reboot it. Exit code 3010 means "reboot needed", which the app surfaces as advice.
     */
    private val MSIEXEC_ARGS = listOf("/i", "/qb", "/norestart")

    /** Single-quoted PowerShell literal: the only escape inside one is a doubled quote. */
    internal fun psQuote(value: String): String = "'" + value.replace("'", "''") + "'"

    /**
     * The script body. Kept as one `-Command` string because the alternative — a temp `.ps1` —
     * would put an executable file in the same user-writable directory we are already treating
     * as untrusted input.
     *
     * Paths are quoted exactly as given, never re-resolved. Callers hand over paths that are
     * already absolute and normalised ([UpdateMarker] guarantees it for the MSI, and the install
     * root is derived from the launcher's own location). Calling `toAbsolutePath()` here would
     * resolve against *this* JVM's working directory, which is both redundant on Windows and
     * actively wrong anywhere else — a Windows path on a POSIX host is read as a relative
     * filename and silently prefixed with the cwd.
     */
    internal fun buildScript(
        launcherPid: Long,
        msiPath: Path,
        resultPath: Path,
        targetVersion: String,
        relaunchExe: Path?,
    ): String {
        val quotedMsi = psQuote(msiPath.toString())
        val quotedResult = psQuote(resultPath.toString())
        val quotedVersion = psQuote(targetVersion)
        val msiArgs = (MSIEXEC_ARGS.take(1) + listOf(quotedMsi) + MSIEXEC_ARGS.drop(1).map { psQuote(it) })
            .joinToString(",")

        val relaunch = relaunchExe
            ?.let { "try { Start-Process -FilePath ${psQuote(it.toString())} } catch { }" }
            ?: ""

        return buildString {
            // -ErrorAction SilentlyContinue: the launcher may already have exited by the time
            // this runs, and "the process I was waiting for is gone" is success, not an error.
            append("Wait-Process -Id $launcherPid -Timeout 60 -ErrorAction SilentlyContinue; ")
            // 1602 = ERROR_INSTALL_USERCANCEL. The default, so a cancelled UAC prompt (which
            // throws rather than returning) is recorded as the cancel it actually was.
            append("\$code = 1602; ")
            append("try { ")
            append("\$p = Start-Process -FilePath 'msiexec.exe' -ArgumentList $msiArgs -Verb RunAs -Wait -PassThru; ")
            append("\$code = \$p.ExitCode ")
            append("} catch { \$code = 1602 }; ")
            append("\$ms = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); ")
            append(
                "[System.IO.File]::WriteAllText($quotedResult, " +
                    "\"exitCode=\$code`ntargetVersion=\" + $quotedVersion + \"`nfinishedAtMs=\$ms`n\"); "
            )
            append(relaunch)
        }
    }

    /** The full argv. `-NoProfile` matters: a user profile that prints or prompts would hang this. */
    fun buildCommand(
        launcherPid: Long,
        msiPath: Path,
        resultPath: Path,
        targetVersion: String,
        relaunchExe: Path?,
    ): List<String> = listOf(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-WindowStyle", "Hidden",
        "-ExecutionPolicy", "Bypass",
        "-Command",
        buildScript(launcherPid, msiPath, resultPath, targetVersion, relaunchExe),
    )
}
