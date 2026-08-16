package uk.me.cormack.lighting7.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import uk.me.cormack.lighting7.models.DaoUser
import uk.me.cormack.lighting7.models.DaoUserSession
import uk.me.cormack.lighting7.models.DaoUserSessions
import uk.me.cormack.lighting7.models.DaoUsers
import uk.me.cormack.lighting7.models.UserRole
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the auth gate stashes on a call: everything a handler needs to know about the caller. */
data class AuthenticatedUser(
    val userId: Int,
    val uuid: UUID,
    val username: String,
    val displayName: String,
    val role: UserRole,
    /** SHA-256 hex of the caller's session token — lets handlers except "this session" from revocations. */
    val sessionTokenHash: String,
)

/** A user row as cached in memory. [passwordHash] never leaves this class's callers' process. */
data class UserRecord(
    val userId: Int,
    val uuid: UUID,
    val username: String,
    val displayName: String,
    val role: UserRole,
    val disabled: Boolean,
    val passwordHash: String,
    val createdAtMs: Long,
    val passwordChangedAtMs: Long,
    val lastLoginAtMs: Long?,
)

/** One live session as reported by `GET /auth/sessions`. */
@Serializable
data class SessionInfo(
    val id: Int,
    val createdAtMs: Long,
    val lastSeenAtMs: Long,
    val userAgent: String?,
    val current: Boolean,
)

/**
 * The only place that touches [DaoUsers] / [DaoUserSessions].
 *
 * **One instance per [Database]** — the in-memory caches are authoritative for the
 * process and the tables are the durable copy (multi-user-auth plan, Decision 5), so a
 * second instance over the same database would silently diverge. Both caches load in
 * `init` and are written through on every mutation; the per-request session check is a
 * `ConcurrentHashMap` lookup, never a query against the single-connection pool. Cache
 * mutations go through `computeIfPresent` so a slow bcrypt path can never write back a
 * stale snapshot over a concurrent change (e.g. a disable racing a password change).
 *
 * Session expiry is sliding (Decision 4): each lookup bumps the in-memory record
 * immediately, but persists `last_seen_at_ms` / `expires_at_ms` at most once per
 * [refreshIntervalMs] per session — with `maximumPoolSize=1`, a DB write per request
 * is not acceptable.
 *
 * Revocation takes effect on the next REST request (the gate re-resolves per call).
 * An already-open WebSocket keeps its upgrade-time identity until it closes — the live
 * revocation stream that closes such sockets ships in Session 3 (Decision 14).
 *
 * [clock] is injectable so expiry, refresh-throttle and login-throttle tests are
 * deterministic instead of timing-flaky.
 */
class AuthService(
    private val database: Database,
    bcryptCost: Int = DEFAULT_BCRYPT_COST,
    private val sessionTtlMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val refreshIntervalMs: Long = 60L * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val passwords = Passwords(bcryptCost)

    private class SessionRecord(
        val id: Int,
        val tokenHash: String,
        val userId: Int,
        val createdAtMs: Long,
        val userAgent: String?,
        @Volatile var lastSeenAtMs: Long,
        @Volatile var expiresAtMs: Long,
        /** When the sliding refresh last reached the DB — the write throttle's CAS anchor. */
        val lastPersistedMs: AtomicLong,
    )

    private val users = ConcurrentHashMap<Int, UserRecord>()
    private val sessions = ConcurrentHashMap<String, SessionRecord>()

    /** Cheap read for the bootstrap-open gate: false only while the desk has zero accounts. */
    @Volatile
    var hasAnyUser: Boolean = false
        private set

    init {
        // One transaction for prune + both cache loads: with maximumPoolSize=1, each
        // transaction is a full connection acquire + BEGIN/COMMIT on the startup path.
        transaction(database) {
            pruneExpiredSessionRows()
            DaoUser.all().forEach { users[it.id.value] = it.toRecord() }
            DaoUserSession.all().forEach { sessions[it.tokenHash] = it.toRecord() }
        }
        hasAnyUser = users.isNotEmpty()
    }

    // ─── Users ─────────────────────────────────────────────────────────

    /**
     * Create a user. Username is stored lowercase; the unique index answers duplicate
     * usernames with an `ExposedSQLException` that `ErrorHandling` maps to 409.
     */
    suspend fun createUser(username: String, displayName: String, role: UserRole, password: String): UserRecord =
        insertUser(username, displayName, role, password, onlyIfFirst = false)!!

    /**
     * The setup path: creates the first ADMIN, or returns null when any user already
     * exists (the route answers 409). The zero-user check and the insert share one
     * transaction; the unique index on `username` is the ultimate race guard if two
     * setups sprint for different usernames simultaneously — one of them still wins
     * the count check because SQLite serialises the writes.
     */
    suspend fun createFirstAdmin(username: String, displayName: String, password: String): UserRecord? {
        // Fast fail before paying a bcrypt hash for a setup that will 409 anyway;
        // the in-transaction check below remains the authoritative race guard.
        if (hasAnyUser) return null
        return insertUser(username, displayName, UserRole.ADMIN, password, onlyIfFirst = true)
    }

    private suspend fun insertUser(
        username: String,
        displayName: String,
        role: UserRole,
        password: String,
        onlyIfFirst: Boolean,
    ): UserRecord? {
        val uname = normaliseUsername(username)
        require(uname.isNotEmpty()) { "username must not be blank" }
        passwords.validatePolicy(password)
        val hash = withContext(Dispatchers.IO) { passwords.hash(password) }
        val now = clock()
        val record = transaction(database) {
            if (onlyIfFirst && !DaoUser.all().empty()) return@transaction null
            DaoUser.new {
                this.username = uname
                this.displayName = displayName
                this.role = role
                this.passwordHash = hash
                this.createdAtMs = now
                this.passwordChangedAtMs = now
            }.toRecord()
        } ?: return null
        users[record.userId] = record
        hasAnyUser = true
        return record
    }

    fun findUserByUsername(username: String): UserRecord? =
        users.values.firstOrNull { it.username == normaliseUsername(username) }

    /**
     * Enable or disable an account. Disabling revokes every session, so the next REST
     * request 401s; an already-open WebSocket persists until Session 3's live
     * revocation stream (Decision 14).
     */
    fun setUserDisabled(userId: Int, disabled: Boolean) {
        transaction(database) {
            DaoUser.findById(userId)?.disabled = disabled
        }
        users.computeIfPresent(userId) { _, u -> u.copy(disabled = disabled) }
        if (disabled) revokeAllSessionsFor(userId)
    }

    /**
     * Break-glass reset: new password, forced ADMIN role, re-enabled, all sessions
     * revoked. Skips current-password verification by design — this is the recovery
     * path for an account nobody can log into.
     */
    suspend fun resetAndEnable(userId: Int, newPassword: String) {
        rotatePassword(userId, newPassword, exceptTokenHash = null, enableAsAdmin = true)
    }

    // ─── Login / logout ────────────────────────────────────────────────

    /**
     * Verify credentials and mint a session. Wrong username and wrong password throw
     * the same [AuthenticationException] after the same bcrypt work (see
     * [Passwords.dummyVerify]) so neither the message nor the response time says which
     * half was wrong. A disabled account authenticates first, then 403s — same
     * anti-enumeration reasoning.
     */
    suspend fun login(username: String, password: String, userAgent: String?, clientIp: String?): Pair<AuthenticatedUser, String> {
        val uname = normaliseUsername(username)
        val penalty = penaltyDelayMs(uname, clock())
        if (penalty > 0) delay(penalty)

        val user = findUserByUsername(uname)
        val verified = withContext(Dispatchers.IO) {
            if (user == null) {
                passwords.dummyVerify()
                false
            } else {
                passwords.verify(password, user.passwordHash)
            }
        }
        if (user == null || !verified) {
            recordLoginFailure(uname, clock())
            throw AuthenticationException("Incorrect username or password")
        }
        if (user.disabled) {
            throw AuthorizationException("This account is disabled")
        }
        clearLoginFailures(uname)
        return mintSession(user, userAgent, clientIp)
    }

    /**
     * Mint a session for an already-authenticated user — the tail of [login], also
     * used by the setup route so it doesn't re-verify the password it just hashed.
     */
    fun mintSession(user: UserRecord, userAgent: String?, clientIp: String?): Pair<AuthenticatedUser, String> {
        val rawToken = SessionTokens.newToken()
        val tokenHash = SessionTokens.sha256Hex(rawToken)
        val now = clock()
        val record = transaction(database) {
            val daoUser = DaoUser.findById(user.userId) ?: throw AuthenticationException("Account no longer exists")
            daoUser.lastLoginAtMs = now
            DaoUserSession.new {
                this.tokenHash = tokenHash
                this.user = daoUser
                this.createdAtMs = now
                this.lastSeenAtMs = now
                this.expiresAtMs = now + sessionTtlMs
                this.userAgent = userAgent?.take(200)
                this.clientIp = clientIp?.take(45)
            }.toRecord()
        }
        sessions[tokenHash] = record
        val current = users.computeIfPresent(user.userId) { _, u -> u.copy(lastLoginAtMs = now) } ?: user
        return current.toAuthenticated(tokenHash) to rawToken
    }

    /**
     * Resolve a raw cookie token to its user, or null for unknown / expired / revoked
     * sessions and disabled users. Pure map lookups on the happy path; the sliding
     * refresh persists at most once per [refreshIntervalMs] per session.
     */
    fun lookupSession(rawToken: String): AuthenticatedUser? {
        val tokenHash = SessionTokens.sha256Hex(rawToken)
        val session = sessions[tokenHash] ?: return null
        val now = clock()
        if (session.expiresAtMs <= now) {
            sessions.remove(tokenHash)
            return null
        }
        val user = users[session.userId] ?: return null
        if (user.disabled) return null

        session.lastSeenAtMs = now
        session.expiresAtMs = now + sessionTtlMs
        val lastPersisted = session.lastPersistedMs.get()
        if (now - lastPersisted >= refreshIntervalMs && session.lastPersistedMs.compareAndSet(lastPersisted, now)) {
            transaction(database) {
                DaoUserSession.findById(session.id)?.apply {
                    lastSeenAtMs = now
                    expiresAtMs = now + sessionTtlMs
                }
            }
        }
        return user.toAuthenticated(tokenHash)
    }

    /** Revoke the session behind [rawToken]; a no-op for tokens we don't know. */
    fun logout(rawToken: String) {
        val tokenHash = SessionTokens.sha256Hex(rawToken)
        val session = sessions.remove(tokenHash) ?: return
        val now = clock()
        transaction(database) {
            DaoUserSession.findById(session.id)?.revokedAtMs = now
        }
    }

    /** Revoke every session for [userId], optionally sparing [exceptTokenHash] (the caller's own). */
    fun revokeAllSessionsFor(userId: Int, exceptTokenHash: String? = null) {
        val doomed = sessions.values.filter { it.userId == userId && it.tokenHash != exceptTokenHash }
        if (doomed.isEmpty()) return
        val now = clock()
        transaction(database) {
            DaoUserSessions.update({
                if (exceptTokenHash == null) {
                    DaoUserSessions.user eq userId
                } else {
                    (DaoUserSessions.user eq userId) and (DaoUserSessions.tokenHash neq exceptTokenHash)
                }
            }) { it[revokedAtMs] = now }
        }
        doomed.forEach { sessions.remove(it.tokenHash) }
    }

    /**
     * Verify [currentPassword], store a new hash, and revoke every *other* session —
     * the changing browser keeps its login; other REST sessions (a stolen cookie
     * included) die on their next request. Live WS revocation is Session 3.
     */
    suspend fun changeOwnPassword(userId: Int, currentPassword: String, newPassword: String, currentTokenHash: String) {
        val user = users[userId] ?: throw AuthenticationException("Account no longer exists")
        val verified = withContext(Dispatchers.IO) { passwords.verify(currentPassword, user.passwordHash) }
        if (!verified) throw AuthenticationException("Current password is incorrect")
        rotatePassword(userId, newPassword, exceptTokenHash = currentTokenHash)
    }

    /**
     * Shared tail of every password rotation: policy check, rehash, persist, cache
     * write-through, revoke sessions. Cache updates go through `computeIfPresent`, so
     * a concurrent change to other fields (a racing disable) is never clobbered by
     * this method's slow bcrypt path.
     */
    private suspend fun rotatePassword(
        userId: Int,
        newPassword: String,
        exceptTokenHash: String?,
        enableAsAdmin: Boolean = false,
    ) {
        passwords.validatePolicy(newPassword)
        val hash = withContext(Dispatchers.IO) { passwords.hash(newPassword) }
        val now = clock()
        transaction(database) {
            DaoUser.findById(userId)?.apply {
                passwordHash = hash
                passwordChangedAtMs = now
                if (enableAsAdmin) {
                    disabled = false
                    role = UserRole.ADMIN
                }
            }
        }
        users.computeIfPresent(userId) { _, u ->
            val rotated = u.copy(passwordHash = hash, passwordChangedAtMs = now)
            if (enableAsAdmin) rotated.copy(disabled = false, role = UserRole.ADMIN) else rotated
        }
        revokeAllSessionsFor(userId, exceptTokenHash)
    }

    /** Live (unexpired) sessions for [userId], newest first, flagging the caller's own. */
    fun sessionsFor(userId: Int, currentTokenHash: String): List<SessionInfo> {
        val now = clock()
        return sessions.values
            .filter { it.userId == userId && it.expiresAtMs > now }
            .sortedByDescending { it.createdAtMs }
            .map {
                SessionInfo(
                    id = it.id,
                    createdAtMs = it.createdAtMs,
                    lastSeenAtMs = it.lastSeenAtMs,
                    userAgent = it.userAgent,
                    current = it.tokenHash == currentTokenHash,
                )
            }
    }

    /**
     * Delete expired and revoked rows in one statement. Runs inside the `init`
     * transaction — dead sessions otherwise accumulate forever on a desk that never
     * restarts its browser.
     */
    private fun pruneExpiredSessionRows() {
        val now = clock()
        DaoUserSessions.deleteWhere {
            (DaoUserSessions.expiresAtMs lessEq now) or DaoUserSessions.revokedAtMs.isNotNull()
        }
    }

    // ─── Login throttle ────────────────────────────────────────────────

    private val loginFailures = ConcurrentHashMap<String, ArrayDeque<Long>>()

    private val throttleWindowMs = 5L * 60 * 1000
    private val throttleThreshold = 5
    private val throttlePenaltyMs = 1_000L

    /** Caps [loginFailures]; login is unauthenticated, so the map is attacker-growable. */
    private val throttleMapLimit = 1_000

    /**
     * The throttle policy, kept pure (no sleeping) so it can be unit-tested with a fake
     * clock: 1 s once a username has 5+ failures inside the last 5 minutes. In-memory
     * only — a restart forgives, which is fine for a physically-present crew.
     */
    internal fun penaltyDelayMs(username: String, nowMs: Long): Long {
        val window = loginFailures[throttleKey(username)] ?: return 0
        synchronized(window) {
            while (window.isNotEmpty() && nowMs - window.first() > throttleWindowMs) {
                window.removeFirst()
            }
            return if (window.size >= throttleThreshold) throttlePenaltyMs else 0
        }
    }

    internal fun recordLoginFailure(username: String, nowMs: Long) {
        // The endpoint is unauthenticated, so bound the map: drop windows whose newest
        // failure has aged out, and if a flood keeps it over the cap anyway, reset —
        // the throttle is best-effort insurance, not the desk's memory ceiling.
        if (loginFailures.size >= throttleMapLimit) {
            loginFailures.entries.removeIf { (_, w) -> synchronized(w) { w.isEmpty() || nowMs - w.last() > throttleWindowMs } }
            if (loginFailures.size >= throttleMapLimit) loginFailures.clear()
        }
        val window = loginFailures.computeIfAbsent(throttleKey(username)) { ArrayDeque() }
        synchronized(window) { window.addLast(nowMs) }
    }

    internal fun clearLoginFailures(username: String) {
        loginFailures.remove(throttleKey(username))
    }

    /** Attacker-supplied usernames can be arbitrarily long; the throttle key is capped. */
    private fun throttleKey(username: String) = normaliseUsername(username).take(64)

    // ─── Internals ─────────────────────────────────────────────────────

    private fun normaliseUsername(username: String) = username.trim().lowercase()

    private fun UserRecord.toAuthenticated(tokenHash: String) = AuthenticatedUser(
        userId = userId,
        uuid = uuid,
        username = username,
        displayName = displayName,
        role = role,
        sessionTokenHash = tokenHash,
    )

    private fun DaoUser.toRecord() = UserRecord(
        userId = id.value,
        uuid = uuid,
        username = username,
        displayName = displayName,
        role = role,
        disabled = disabled,
        passwordHash = passwordHash,
        createdAtMs = createdAtMs,
        passwordChangedAtMs = passwordChangedAtMs,
        lastLoginAtMs = lastLoginAtMs,
    )

    private fun DaoUserSession.toRecord() = SessionRecord(
        id = id.value,
        tokenHash = tokenHash,
        userId = user.id.value,
        createdAtMs = createdAtMs,
        userAgent = userAgent,
        lastSeenAtMs = lastSeenAtMs,
        expiresAtMs = expiresAtMs,
        lastPersistedMs = AtomicLong(lastSeenAtMs),
    )
}
