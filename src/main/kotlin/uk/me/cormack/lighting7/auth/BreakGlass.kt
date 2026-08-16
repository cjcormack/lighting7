package uk.me.cormack.lighting7.auth

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.UserRole
import java.nio.file.Files
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("BreakGlass")

private const val TRIGGER_FILE = "RESET-ADMIN"
private const val PASSWORD_FILE = "RESET-ADMIN-PASSWORD.txt"

/**
 * Last-resort recovery for a desk whose admins are all locked out (multi-user-auth
 * plan, Decision 15): drop a file named `RESET-ADMIN` in the app data dir (or start
 * with `-Dlighting7.resetAdmin=true`) and restart. Creates — or re-enables and
 * re-passwords — the user `admin` with a fresh random password, prints it at WARN,
 * writes it to `RESET-ADMIN-PASSWORD.txt` next to the trigger, and deletes the
 * trigger so the file-drop reset is one-shot. (The JVM flag inherently re-runs on
 * every boot it is present for — a WARN says so; remove the flag after recovering.)
 *
 * Ordering matters: the password file is written **before** the account is touched.
 * On an installed desk launched by double-click there is no console, so that file is
 * the only durable copy of the password — resetting first and failing the write would
 * lock the desk out harder than before the recovery ran. Failures are contained: a
 * failed reset logs at ERROR and lets the server boot (leaving the trigger in place
 * for a retry) rather than boot-looping the recovery mechanism itself.
 *
 * Called from `Application.module()` (the production entry point), never from `State`:
 * tests construct `State` dozens of times and must not scan the real data directory.
 * A file drop rather than a flag alone because installed desks launch by double-click;
 * physical access to the data dir already means access to the SQLite file, so this
 * adds no exposure.
 */
fun runBreakGlassIfRequested(authService: AuthService, dataDir: Path) {
    val trigger = dataDir.resolve(TRIGGER_FILE)
    val viaProperty = System.getProperty("lighting7.resetAdmin") == "true"
    if (!Files.exists(trigger) && !viaProperty) return

    try {
        val password = SessionTokens.randomPassword()

        // Durable record first — see the KDoc. If this write fails, the account is
        // untouched and the trigger stays armed for a retry after fixing the disk.
        val passwordFile = dataDir.resolve(PASSWORD_FILE)
        Files.writeString(passwordFile, "admin\n$password\n")

        val existing = authService.findUserByUsername("admin")
        if (existing != null) {
            // Startup, single-threaded: blocking on the bcrypt hash here is fine.
            runBlocking { authService.resetAndEnable(existing.userId, password) }
            logger.warn("BREAK-GLASS: existing user 'admin' re-enabled with a new password: {}", password)
        } else {
            runBlocking { authService.createUser("admin", "Recovery Admin", UserRole.ADMIN, password) }
            logger.warn("BREAK-GLASS: user 'admin' created with password: {}", password)
        }
        logger.warn("BREAK-GLASS: password also written to {}", passwordFile)

        Files.deleteIfExists(trigger)
        if (viaProperty) {
            logger.warn("BREAK-GLASS: triggered by -Dlighting7.resetAdmin=true — this repeats on EVERY boot until the flag is removed from the launch command")
        }
    } catch (e: Exception) {
        logger.error("BREAK-GLASS failed — admin account left as it was; fix the cause and restart (trigger file, if any, is still in place)", e)
    }
}
