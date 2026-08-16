package uk.me.cormack.lighting7.auth

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.testAppConfig
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AuthService] against a real (fresh, file-backed) SQLite database.
 * The service under test is **test-owned** — never `state.authService` — because two
 * instances over one database have independent caches, and this suite wants full
 * control of the clock. bcrypt cost 4 keeps hashing at ~2 ms.
 */
class AuthServiceTest {

    private lateinit var state: State
    private var now: Long = 1_000_000_000_000L
    private lateinit var auth: AuthService

    private val hourMs = 60L * 60 * 1000
    private val ttlMs = 30L * 24 * hourMs

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        auth = AuthService(state.database, bcryptCost = 4, clock = { now })
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
    }

    private fun seed(username: String = "alice", password: String = "hunter2hunter2") =
        runBlocking { auth.createUser(username, "Alice", UserRole.ADMIN, password) }

    // ─── Passwords ─────────────────────────────────────────────────────

    @Test
    fun `hash and verify round-trip`() {
        val passwords = Passwords(cost = 4)
        val hash = passwords.hash("a fine password")
        assertTrue(passwords.verify("a fine password", hash))
        assertTrue(!passwords.verify("a wrong password", hash))
        assertEquals(60, hash.length)
    }

    @Test
    fun `policy rejects short and over-72-byte passwords`() {
        val passwords = Passwords(cost = 4)
        assertFailsWith<PasswordPolicyException> { passwords.validatePolicy("seven77") }
        // 73 ASCII bytes.
        assertFailsWith<PasswordPolicyException> { passwords.validatePolicy("a".repeat(73)) }
        // 25 chars but 75 UTF-8 bytes — the byte limit is what matters.
        assertFailsWith<PasswordPolicyException> { passwords.validatePolicy("€".repeat(25)) }
        // 72 bytes exactly is fine.
        passwords.validatePolicy("a".repeat(72))
    }

    // ─── Sessions ──────────────────────────────────────────────────────

    @Test
    fun `login mints a session that lookupSession resolves`() {
        val record = seed()
        val (user, rawToken) = runBlocking { auth.login("alice", "hunter2hunter2", "agent", "127.0.0.1") }
        assertEquals(record.userId, user.userId)

        val resolved = auth.lookupSession(rawToken)
        assertNotNull(resolved)
        assertEquals("alice", resolved.username)
        assertEquals(SessionTokens.sha256Hex(rawToken), resolved.sessionTokenHash)
    }

    @Test
    fun `username case and padding are normalised at login`() {
        seed()
        val (user, _) = runBlocking { auth.login("  ALICE ", "hunter2hunter2", null, null) }
        assertEquals("alice", user.username)
    }

    @Test
    fun `sessions expire, sliding on activity`() {
        seed()
        val (_, rawToken) = runBlocking { auth.login("alice", "hunter2hunter2", null, null) }

        // 29 days later the session is alive, and the lookup slides expiry forward.
        now += ttlMs - 24 * hourMs
        assertNotNull(auth.lookupSession(rawToken))

        // Another 29 days of silence — still alive only because of the slide.
        now += ttlMs - 24 * hourMs
        assertNotNull(auth.lookupSession(rawToken))

        // 31 days of silence exceeds the TTL.
        now += ttlMs + hourMs
        assertNull(auth.lookupSession(rawToken))
    }

    @Test
    fun `sessionsFor omits expired sessions even before they are lazily evicted`() {
        val record = seed()
        val (_, rawToken) = runBlocking { auth.login("alice", "hunter2hunter2", null, null) }
        val tokenHash = SessionTokens.sha256Hex(rawToken)
        assertEquals(1, auth.sessionsFor(record.userId, tokenHash).size)

        // Past the TTL with no lookup in between: the cache entry still exists, but the
        // listing must not report a dead credential as live.
        now += ttlMs + hourMs
        assertEquals(0, auth.sessionsFor(record.userId, tokenHash).size)
    }

    @Test
    fun `logout revokes the session`() {
        seed()
        val (_, rawToken) = runBlocking { auth.login("alice", "hunter2hunter2", null, null) }
        auth.logout(rawToken)
        assertNull(auth.lookupSession(rawToken))
    }

    @Test
    fun `disabling a user kills lookups and revokes sessions`() {
        val record = seed()
        val (_, rawToken) = runBlocking { auth.login("alice", "hunter2hunter2", null, null) }
        auth.setUserDisabled(record.userId, true)
        assertNull(auth.lookupSession(rawToken))
        assertFailsWith<AuthorizationException> {
            runBlocking { auth.login("alice", "hunter2hunter2", null, null) }
        }
    }

    @Test
    fun `sessions survive a service restart via the durable table`() {
        seed()
        val (_, rawToken) = runBlocking { auth.login("alice", "hunter2hunter2", null, null) }

        val rebooted = AuthService(state.database, bcryptCost = 4, clock = { now })
        val resolved = rebooted.lookupSession(rawToken)
        assertNotNull(resolved)
        assertEquals("alice", resolved.username)
    }

    // ─── changeOwnPassword ─────────────────────────────────────────────

    @Test
    fun `changeOwnPassword revokes sibling sessions but keeps the caller's`() {
        val record = seed()
        val (_, keepToken) = runBlocking { auth.login("alice", "hunter2hunter2", "browser-a", null) }
        val (_, doomedToken) = runBlocking { auth.login("alice", "hunter2hunter2", "browser-b", null) }

        runBlocking {
            auth.changeOwnPassword(
                record.userId,
                currentPassword = "hunter2hunter2",
                newPassword = "a-new-password",
                currentTokenHash = SessionTokens.sha256Hex(keepToken),
            )
        }

        assertNotNull(auth.lookupSession(keepToken))
        assertNull(auth.lookupSession(doomedToken))

        // Old password dead, new password live.
        assertFailsWith<AuthenticationException> {
            runBlocking { auth.login("alice", "hunter2hunter2", null, null) }
        }
        runBlocking { auth.login("alice", "a-new-password", null, null) }
    }

    @Test
    fun `changeOwnPassword rejects a wrong current password`() {
        val record = seed()
        val (_, rawToken) = runBlocking { auth.login("alice", "hunter2hunter2", null, null) }
        assertFailsWith<AuthenticationException> {
            runBlocking {
                auth.changeOwnPassword(record.userId, "not-the-password", "a-new-password", SessionTokens.sha256Hex(rawToken))
            }
        }
        // Nothing was revoked.
        assertNotNull(auth.lookupSession(rawToken))
    }

    // ─── Login failures & throttle ─────────────────────────────────────

    @Test
    fun `unknown user and wrong password throw the same exception message`() {
        seed()
        val wrongPassword = assertFailsWith<AuthenticationException> {
            runBlocking { auth.login("alice", "not-the-password", null, null) }
        }
        val unknownUser = assertFailsWith<AuthenticationException> {
            runBlocking { auth.login("nobody", "not-the-password", null, null) }
        }
        assertEquals(wrongPassword.message, unknownUser.message)
    }

    @Test
    fun `throttle kicks in after five failures in the window and clears on success`() {
        assertEquals(0, auth.penaltyDelayMs("alice", now))

        repeat(4) { auth.recordLoginFailure("alice", now) }
        assertEquals(0, auth.penaltyDelayMs("alice", now))

        auth.recordLoginFailure("alice", now)
        assertTrue(auth.penaltyDelayMs("alice", now) > 0)

        // Failures age out of the 5-minute window.
        assertEquals(0, auth.penaltyDelayMs("alice", now + 6 * 60 * 1000))

        // A success clears the counter outright.
        repeat(5) { auth.recordLoginFailure("alice", now) }
        auth.clearLoginFailures("alice")
        assertEquals(0, auth.penaltyDelayMs("alice", now))
    }

    // ─── Setup path ────────────────────────────────────────────────────

    @Test
    fun `createFirstAdmin succeeds once and returns null after`() {
        assertTrue(!auth.hasAnyUser)
        val first = runBlocking { auth.createFirstAdmin("boss", "The Boss", "a-password") }
        assertNotNull(first)
        assertEquals(UserRole.ADMIN, first.role)
        assertTrue(auth.hasAnyUser)

        assertNull(runBlocking { auth.createFirstAdmin("boss2", "Another", "a-password") })
    }
}
