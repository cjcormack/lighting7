package uk.me.cormack.lighting7.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import uk.me.cormack.lighting7.models.DaoPasswordResetToken
import uk.me.cormack.lighting7.models.DaoPasswordResetTokens
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

/** Where a password reset token is in its short life. Derived from the row's timestamps, never stored. */
@Serializable
enum class ResetTokenStatus {
    /** Live and redeemable. */
    PENDING,

    /** The user set a new password with it — the admin's QR sheet's success state. */
    USED,
    EXPIRED,

    /** Superseded by a newer token for the same user, or the admin closed the sheet. */
    CANCELLED,
}

/**
 * Outcome of an admin mutation that the last-admin guard can refuse.
 *
 * The guard's answer has to come from inside the mutating transaction rather than from a
 * pre-check in the route — see [AuthService.updateUser] — so the routes learn about it
 * from the return value instead of asking first.
 */
sealed interface UserMutation<out T> {
    data class Done<T>(val value: T) : UserMutation<T>

    data object NotFound : UserMutation<Nothing>

    /** Refused: this would have left the desk with no enabled ADMIN. */
    data object LastAdmin : UserMutation<Nothing>
}

/** A freshly minted reset token, minus its raw value (which the caller receives separately). */
data class ResetTokenRecord(val id: Int, val expiresAtMs: Long)

/** What the public `GET /auth/reset/{token}` page learns about a token. */
sealed interface ResetTokenLookup {
    data class Live(
        val tokenId: Int,
        val userId: Int,
        val username: String,
        val displayName: String,
        val expiresAtMs: Long,
    ) : ResetTokenLookup

    /** Known token, no longer redeemable — the phone gets 410 plus status-specific copy. */
    data class Dead(val status: ResetTokenStatus) : ResetTokenLookup

    /** No such token: a typo, or one already pruned. 404. */
    data object Unknown : ResetTokenLookup
}

/** Outcome of `POST /auth/reset/{token}` — the same three shapes as [ResetTokenLookup]. */
sealed interface ResetRedemption {
    data class Applied(val user: UserRecord) : ResetRedemption
    data class Dead(val status: ResetTokenStatus) : ResetRedemption
    data object Unknown : ResetRedemption
}

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
 * The only place that touches [DaoUsers] / [DaoUserSessions] / [DaoPasswordResetTokens].
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
 * Revocation takes effect on the next REST request (the gate re-resolves per call) and,
 * for an already-open WebSocket, immediately: every revoked token hash is emitted on
 * [revocations], which `plugins/Sockets.kt` collects per connection and answers by
 * closing 4401. Reset tokens are the one thing here with **no** in-memory cache — they
 * are looked up by their unique hash index on a public, rate-limited endpoint, so a
 * cache would buy nothing and add a second thing to keep coherent.
 *
 * [clock] is injectable so expiry, refresh-throttle and login-throttle tests are
 * deterministic instead of timing-flaky.
 */
class AuthService(
    private val database: Database,
    bcryptCost: Int = DEFAULT_BCRYPT_COST,
    private val sessionTtlMs: Long = 30L * 24 * 60 * 60 * 1000,
    private val refreshIntervalMs: Long = 60L * 60 * 1000,
    /** QR reset tokens are handed over in person and redeemed immediately; 15 minutes is plenty. */
    private val resetTokenTtlMs: Long = 15L * 60 * 1000,
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

    private val _revocations = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)

    /**
     * Session-token hashes that have just been revoked (logout, disable, delete, password
     * change, reset redemption). `plugins/Sockets.kt` collects this per connection and closes
     * 4401 on a match — without it, "disable this user" would leave their already-open socket
     * streaming until they happened to make a REST call (plan 3.5, Decision 14).
     *
     * Emitted with `tryEmit` from non-suspending call sites: a dropped frame under a
     * pathological burst costs at most a socket that closes on its next REST round-trip
     * instead of instantly, which is strictly better than blocking a revocation on a slow
     * collector.
     */
    val revocations: SharedFlow<String> = _revocations.asSharedFlow()

    /** Cheap read for the bootstrap-open gate: false only while the desk has zero accounts. */
    @Volatile
    var hasAnyUser: Boolean = false
        private set

    init {
        // One transaction for prune + both cache loads: with maximumPoolSize=1, each
        // transaction is a full connection acquire + BEGIN/COMMIT on the startup path.
        transaction(database) {
            pruneExpiredSessionRows()
            pruneDeadResetTokenRows()
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
            if (disabled) cancelOutstandingResetTokens(userId, clock())
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

    /** Every account, username order — what `GET /users` lists. Cache read; no query. */
    fun listUsers(): List<UserRecord> = users.values.sortedBy { it.username }

    fun findUser(userId: Int): UserRecord? = users[userId]

    /**
     * Enabled ADMINs, from the in-memory cache. A display/reporting read — the
     * last-admin *guard* deliberately does not use this (see [countEnabledAdmins]).
     */
    fun enabledAdminCount(): Int = users.values.count { it.role == UserRole.ADMIN && !it.disabled }

    /**
     * Apply an admin's edits to display name / role / disabled. Null means "leave alone",
     * so a UI that only sends the changed fields does the right thing.
     *
     * The last-admin guard lives **inside the transaction**, not in the route. A desk with
     * zero usable admins can only be repaired by the break-glass file drop plus a restart,
     * so this must not be a check-then-act: two admins demoting each other from two tabs
     * would each read a count of 2, each pass a route-level check, and both writes would
     * land. With `maximumPoolSize=1` every transaction is serialised on the single
     * connection, so a count taken here sees the other request's commit.
     *
     * Disabling revokes every session — which, via [revocations], also closes any socket
     * that user has open — and cancels any live reset link, so a QR already on their phone
     * can't hand the account back after an admin has taken it away.
     */
    fun updateUser(
        userId: Int,
        displayName: String?,
        role: UserRole?,
        disabled: Boolean?,
    ): UserMutation<UserRecord> {
        val now = clock()
        val outcome = transaction(database) {
            val dao = DaoUser.findById(userId) ?: return@transaction UserMutation.NotFound
            // Demotion and disabling are the same question — "does an enabled admin
            // survive this?" — so they share one check.
            val losesAdmin = dao.role == UserRole.ADMIN && !dao.disabled &&
                (role == UserRole.OPERATOR || disabled == true)
            if (losesAdmin && countEnabledAdmins() <= 1) return@transaction UserMutation.LastAdmin
            displayName?.let { dao.displayName = it }
            role?.let { dao.role = it }
            disabled?.let { dao.disabled = it }
            if (disabled == true) cancelOutstandingResetTokens(userId, now)
            UserMutation.Done(dao.toRecord())
        }
        if (outcome !is UserMutation.Done) return outcome
        users.computeIfPresent(userId) { _, u ->
            u.copy(
                displayName = displayName ?: u.displayName,
                role = role ?: u.role,
                disabled = disabled ?: u.disabled,
            )
        }
        if (disabled == true) revokeAllSessionsFor(userId)
        return outcome
    }

    /**
     * Admin sets someone else's password directly — the "they're standing next to you"
     * path, no current-password check. Every session of theirs dies, so a shared browser
     * left logged in elsewhere doesn't survive the reset.
     */
    suspend fun setPasswordAsAdmin(userId: Int, newPassword: String) {
        rotatePassword(userId, newPassword, exceptTokenHash = null)
    }

    /**
     * Delete an account. Sessions and reset tokens are removed explicitly rather than
     * leaning on the declared CASCADE — SQLite runs with `PRAGMA foreign_keys` OFF here
     * (see `models/userSessions.kt`), so the FK would silently leave orphans that the
     * in-memory caches would happily keep honouring.
     */
    fun deleteUser(userId: Int): UserMutation<Unit> {
        val outcome = transaction(database) {
            val dao = DaoUser.findById(userId) ?: return@transaction UserMutation.NotFound
            // Same in-transaction guard as updateUser, for the same reason: two admins
            // deleting each other from two tabs must not both succeed.
            if (dao.role == UserRole.ADMIN && !dao.disabled && countEnabledAdmins() <= 1) {
                return@transaction UserMutation.LastAdmin
            }
            DaoUserSessions.deleteWhere { DaoUserSessions.user eq userId }
            DaoPasswordResetTokens.deleteWhere { DaoPasswordResetTokens.user eq userId }
            // Tokens this admin minted for *other* people outlive them: SET_NULL is
            // declared but unenforced, so clear the reference by hand.
            DaoPasswordResetTokens.update({ DaoPasswordResetTokens.createdByUser eq userId }) {
                it[createdByUser] = null
            }
            dao.delete()
            UserMutation.Done(Unit)
        }
        if (outcome !is UserMutation.Done) return outcome
        users.remove(userId)
        sessions.values.filter { it.userId == userId }.forEach {
            sessions.remove(it.tokenHash)
            _revocations.tryEmit(it.tokenHash)
        }
        hasAnyUser = users.isNotEmpty()
        return outcome
    }

    /**
     * Enabled ADMINs as the database sees them right now. Must be called inside a
     * transaction — that placement is the whole point, since it is what makes the
     * last-admin guard atomic with the write it guards.
     */
    private fun countEnabledAdmins(): Long =
        DaoUser.count((DaoUsers.role eq UserRole.ADMIN) and (DaoUsers.disabled eq false))

    /**
     * Cancel every live reset link for [userId]. Must be called inside a transaction.
     *
     * The rule is that the newest admin action wins: minting a link retires the previous
     * one, and disabling an account — or setting its password by any other route — retires
     * whatever link is outstanding. Without this, an operator holding a QR from five
     * minutes ago could redeem it *after* being disabled and, because redemption
     * re-enables the account, walk straight back in with a password of their choosing.
     */
    private fun cancelOutstandingResetTokens(userId: Int, nowMs: Long) {
        DaoPasswordResetTokens.update({
            (DaoPasswordResetTokens.user eq userId) and
                DaoPasswordResetTokens.usedAtMs.isNull() and
                DaoPasswordResetTokens.cancelledAtMs.isNull()
        }) { it[cancelledAtMs] = nowMs }
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
        _revocations.tryEmit(tokenHash)
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
        doomed.forEach {
            sessions.remove(it.tokenHash)
            _revocations.tryEmit(it.tokenHash)
        }
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
            // A password that has just been set makes any outstanding reset link stale —
            // whoever holds it would otherwise be able to overwrite the new password.
            cancelOutstandingResetTokens(userId, now)
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

    // ─── Password reset tokens ─────────────────────────────────────────

    /**
     * Mint a single-use reset token for [userId], returning the row and the **raw** token
     * (the only time it exists outside the QR URL). Returns null if the account is gone.
     *
     * Minting cancels that user's outstanding tokens: one live token per account means a
     * QR someone photographed five minutes ago stops working the moment the admin makes a
     * new one, which is what an operator expects "generate a new link" to mean.
     */
    fun createResetToken(userId: Int, createdByUserId: Int?): Pair<ResetTokenRecord, String>? {
        val rawToken = SessionTokens.newResetToken()
        val tokenHash = SessionTokens.sha256Hex(rawToken)
        val now = clock()
        val record = transaction(database) {
            val dao = DaoUser.findById(userId) ?: return@transaction null
            cancelOutstandingResetTokens(userId, now)
            val row = DaoPasswordResetToken.new {
                this.tokenHash = tokenHash
                this.user = dao
                this.createdByUser = createdByUserId?.let { DaoUser.findById(it) }
                this.createdAtMs = now
                this.expiresAtMs = now + resetTokenTtlMs
            }
            ResetTokenRecord(row.id.value, row.expiresAtMs)
        } ?: return null
        return record to rawToken
    }

    /** Resolve a raw token from the QR URL. An index probe on `token_hash`, not a scan. */
    fun lookupResetToken(rawToken: String): ResetTokenLookup {
        val tokenHash = SessionTokens.sha256Hex(rawToken)
        return transaction(database) {
            val row = DaoPasswordResetToken
                .find { DaoPasswordResetTokens.tokenHash eq tokenHash }
                .firstOrNull() ?: return@transaction ResetTokenLookup.Unknown
            val status = row.statusAt(clock())
            if (status != ResetTokenStatus.PENDING) return@transaction ResetTokenLookup.Dead(status)
            ResetTokenLookup.Live(
                tokenId = row.id.value,
                userId = row.user.id.value,
                username = row.user.username,
                displayName = row.user.displayName,
                expiresAtMs = row.expiresAtMs,
            )
        }
    }

    /**
     * Status of one token by id, for the admin sheet's poll. Scoped to [userId] so a
     * token id from another user's sheet can't be read through the wrong URL. Null when
     * the token doesn't exist (or isn't theirs).
     */
    fun resetTokenStatus(userId: Int, tokenId: Int): Pair<ResetTokenStatus, Long>? = transaction(database) {
        val row = DaoPasswordResetToken.findById(tokenId) ?: return@transaction null
        if (row.user.id.value != userId) return@transaction null
        row.statusAt(clock()) to row.expiresAtMs
    }

    /**
     * Cancel a live token — fired when the admin closes the QR sheet, so a link that was
     * on screen for ten seconds doesn't stay redeemable for fifteen minutes. Idempotent:
     * an already-used or already-cancelled token is left exactly as it is.
     */
    fun cancelResetToken(userId: Int, tokenId: Int): Boolean = transaction(database) {
        val row = DaoPasswordResetToken.findById(tokenId) ?: return@transaction false
        if (row.user.id.value != userId) return@transaction false
        if (row.usedAtMs == null && row.cancelledAtMs == null) {
            row.cancelledAtMs = clock()
        }
        true
    }

    /**
     * Redeem a token: set the user's password, mark the token used, kill every session
     * they had. The pre-check exists so an unknown or dead token never costs a bcrypt hash
     * — this endpoint is public, and hashing on demand would be a free CPU sink.
     *
     * The authoritative check is the one inside the transaction. With `maximumPoolSize=1`
     * every transaction is serialised on the single connection, so two phones racing the
     * same QR cannot both see `used_at_ms == null`: the loser gets [ResetRedemption.Dead].
     */
    suspend fun redeemResetToken(rawToken: String, newPassword: String): ResetRedemption {
        when (val preCheck = lookupResetToken(rawToken)) {
            is ResetTokenLookup.Unknown -> return ResetRedemption.Unknown
            is ResetTokenLookup.Dead -> return ResetRedemption.Dead(preCheck.status)
            is ResetTokenLookup.Live -> Unit
        }
        passwords.validatePolicy(newPassword)
        val hash = withContext(Dispatchers.IO) { passwords.hash(newPassword) }
        val now = clock()
        val tokenHash = SessionTokens.sha256Hex(rawToken)
        val outcome = transaction(database) {
            val row = DaoPasswordResetToken
                .find { DaoPasswordResetTokens.tokenHash eq tokenHash }
                .firstOrNull() ?: return@transaction ResetRedemption.Unknown
            val status = row.statusAt(now)
            if (status != ResetTokenStatus.PENDING) return@transaction ResetRedemption.Dead(status)
            row.usedAtMs = now
            val dao = row.user
            dao.passwordHash = hash
            dao.passwordChangedAtMs = now
            // A reset also re-enables: an admin resetting a disabled account's password
            // means to hand it back, and leaving it disabled would answer the new
            // password with 403 (the confusing failure mode this whole flow exists to fix).
            dao.disabled = false
            ResetRedemption.Applied(dao.toRecord())
        }
        if (outcome is ResetRedemption.Applied) {
            val userId = outcome.user.userId
            users.computeIfPresent(userId) { _, u ->
                u.copy(passwordHash = hash, passwordChangedAtMs = now, disabled = false)
            }
            revokeAllSessionsFor(userId)
        }
        return outcome
    }

    /**
     * Dead reset tokens are pruned at startup alongside dead sessions. Live ones are kept:
     * a 15-minute token minted seconds before a restart should survive it, since the
     * person holding the QR has no way to know the desk bounced.
     */
    private fun pruneDeadResetTokenRows() {
        val now = clock()
        DaoPasswordResetTokens.deleteWhere {
            (DaoPasswordResetTokens.expiresAtMs lessEq now) or
                DaoPasswordResetTokens.usedAtMs.isNotNull() or
                DaoPasswordResetTokens.cancelledAtMs.isNotNull()
        }
    }

    /** Must be called inside a transaction. Status is derived from timestamps, never stored. */
    private fun DaoPasswordResetToken.statusAt(now: Long): ResetTokenStatus = when {
        usedAtMs != null -> ResetTokenStatus.USED
        cancelledAtMs != null -> ResetTokenStatus.CANCELLED
        expiresAtMs <= now -> ResetTokenStatus.EXPIRED
        else -> ResetTokenStatus.PENDING
    }

    // ─── Login throttle ────────────────────────────────────────────────

    private val loginFailures = ConcurrentHashMap<String, ArrayDeque<Long>>()

    private val throttleWindowMs = 5L * 60 * 1000
    private val throttleThreshold = 5
    private val throttlePenaltyMs = 1_000L

    /** Caps [loginFailures]; login is unauthenticated, so the map is attacker-growable. */
    private val throttleMapLimit = 1_000

    /**
     * Delay the caller if [key] has recently failed too often, then let them try. The
     * public `POST /auth/reset/{token}` endpoint keys this on **client IP** rather than a
     * username, because a stranger hammering reset URLs has no username to be throttled
     * on (plan 3.4). Login uses the username-keyed path in [login] instead.
     */
    suspend fun awaitThrottle(key: String) {
        val penalty = penaltyDelayMs(key, clock())
        if (penalty > 0) delay(penalty)
    }

    /** Record a failure against an arbitrary throttle key — see [awaitThrottle]. */
    fun recordThrottleFailure(key: String) = recordLoginFailure(key, clock())

    /** Forget an arbitrary throttle key's failures — see [awaitThrottle]. */
    fun clearThrottleFailures(key: String) = clearLoginFailures(key)

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
