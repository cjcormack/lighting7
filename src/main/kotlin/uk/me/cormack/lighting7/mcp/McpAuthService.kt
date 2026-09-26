package uk.me.cormack.lighting7.mcp

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import uk.me.cormack.lighting7.auth.AuthService
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.auth.SessionTokens
import uk.me.cormack.lighting7.models.DaoMcpOAuthClient
import uk.me.cormack.lighting7.models.DaoMcpOAuthClients
import uk.me.cormack.lighting7.models.DaoMcpOAuthGrant
import uk.me.cormack.lighting7.models.DaoMcpOAuthGrants
import uk.me.cormack.lighting7.models.DaoUser
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.models.toIsoUtc
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A client as registered: every one is public, so it has no secret. */
data class McpOAuthClient(val clientId: String, val clientName: String, val redirectUris: List<String>)

/**
 * An authorization request that has been validated and is waiting on the sign-in form. The form
 * carries only [id]; everything the redirect depends on stays server-side, so the POST cannot
 * be edited into a different client, redirect URI or PKCE challenge.
 */
data class McpPendingAuthorization(
    val id: String,
    val client: McpOAuthClient,
    val redirectUri: String,
    val state: String?,
    val codeChallenge: String,
    val expiresAt: Instant,
)

/** One row of the Connected apps list (Profile → Devices). */
@Serializable
data class ConnectedAppDto(
    val id: Int,
    val clientName: String,
    val createdAt: String,
    val lastUsedAt: String,
)

/** RFC 6749 §5.1 success body. */
@Serializable
data class McpTokenResponse(
    @kotlinx.serialization.SerialName("access_token") val accessToken: String,
    @kotlinx.serialization.SerialName("token_type") val tokenType: String = "Bearer",
    @kotlinx.serialization.SerialName("expires_in") val expiresIn: Long,
    @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String,
    val scope: String = MCP_SCOPE,
)

/** An RFC 6749 §5.2 error: the endpoint answers 400 with `{error, error_description}`. */
class McpOAuthError(val error: String, val description: String) : Exception(description)

/** The one scope there is: control of the lights, at whatever the account's role allows. */
const val MCP_SCOPE = "lighting"

/**
 * The desk as its own OAuth 2.1 authorization server, for the MCP endpoint only.
 *
 * The identity is the desk account: `/oauth/authorize` asks for its username and password (through
 * [AuthService.verifyCredentials], so the login throttle covers it) and the grant resolves to the
 * same [AuthenticatedUser] a cookie would. Tokens are opaque, stored as SHA-256 hashes, and
 * accepted **only** by the MCP endpoint — the REST gate reads cookies and nothing else — which is
 * what makes them audience-bound without a claim to check.
 *
 * - **Access tokens** live an hour ([accessTtl]). Resolved from an in-memory cache, like sessions,
 *   because every MCP call resolves one and the pool has a single connection.
 * - **Refresh tokens** rotate on every use and slide [refreshTtl]. Presenting the one just retired
 *   revokes the grant, since only a replayed stolen token would ever do that.
 * - **Codes** are in memory, single use, a minute long, bound to client, redirect URI and a PKCE
 *   S256 challenge. A restart forgets them, which costs a sign-in that was mid-flight.
 *
 * Grants die on every event that ends sessions (via
 * [AuthService.addCredentialRevocationListener]), on the Revoke button, and on `/oauth/revoke`.
 * A disabled or deleted account's token also fails at resolution, without waiting for any of that.
 */
class McpAuthService(
    private val database: Database,
    private val authService: AuthService,
    private val config: McpConfig,
    private val accessTtl: Duration = Duration.ofHours(1),
    private val refreshTtl: Duration = Duration.ofDays(30),
    private val codeTtl: Duration = Duration.ofMinutes(1),
    private val pendingTtl: Duration = Duration.ofMinutes(10),
    private val clock: () -> Instant = ::nowUtc,
) {
    private class GrantRecord(
        val id: Int,
        val userId: Int,
        val accessHash: String,
        val accessExpiresAt: Instant,
        val lastPersistedMs: AtomicLong,
    )

    private data class IssuedCode(
        val userId: Int,
        val client: McpOAuthClient,
        val redirectUri: String,
        val codeChallenge: String,
        val expiresAt: Instant,
    )

    /** Live access tokens by hash. The DB row is the truth; this is the read path. */
    private val grants = ConcurrentHashMap<String, GrantRecord>()
    private val pending = ConcurrentHashMap<String, McpPendingAuthorization>()
    private val codes = ConcurrentHashMap<String, IssuedCode>()

    init {
        val now = clock()
        transaction(database) {
            DaoMcpOAuthGrants.deleteWhere {
                DaoMcpOAuthGrants.revokedAt.isNotNull() or (DaoMcpOAuthGrants.refreshExpiresAt lessEq now)
            }
            DaoMcpOAuthGrant.all().forEach { grants[it.accessTokenHash] = it.toRecord() }
        }
        authService.addCredentialRevocationListener { revokeAllFor(it) }
    }

    // ─── Clients (RFC 7591) ────────────────────────────────────────────

    /**
     * Register a public client. Unauthenticated by the spec's design, which is why the redirect
     * URIs are allowlisted: a registration can only ever send codes to claude.ai's callback, a
     * loopback address, or a URI the operator added in `mcp.extraRedirectUris`.
     */
    fun registerClient(redirectUris: List<String>, clientName: String?): McpOAuthClient {
        if (redirectUris.isEmpty()) throw McpOAuthError("invalid_redirect_uri", "At least one redirect_uri is required")
        redirectUris.firstOrNull { !isAllowedRedirectUri(it) }?.let {
            throw McpOAuthError("invalid_redirect_uri", "Redirect URI not allowed by this desk: $it")
        }
        val name = clientName?.trim()?.take(200)?.takeIf { it.isNotEmpty() } ?: "MCP client"
        val clientId = UUID.randomUUID().toString()
        val now = clock()
        transaction(database) {
            pruneUnusedClients(now)
            if (DaoMcpOAuthClient.count() >= MAX_CLIENTS) {
                throw McpOAuthError("invalid_client_metadata", "Too many registered clients on this desk")
            }
            DaoMcpOAuthClient.new {
                this.clientId = clientId
                this.clientName = name
                this.redirectUris = redirectUris.joinToString("\n")
                this.createdAt = now
            }
        }
        return McpOAuthClient(clientId, name, redirectUris)
    }

    fun findClient(clientId: String): McpOAuthClient? = transaction(database) {
        DaoMcpOAuthClient.find { DaoMcpOAuthClients.clientId eq clientId }.singleOrNull()?.let {
            McpOAuthClient(it.clientId, it.clientName, it.redirectUris.split('\n').filter(String::isNotEmpty))
        }
    }

    internal fun isAllowedRedirectUri(uri: String): Boolean {
        if (uri in BUILT_IN_REDIRECT_URIS || uri in config.extraRedirectUris) return true
        // RFC 8252 §7.3 loopback redirects, for Claude Code and mcp-remote on the desk's LAN
        // laptops: plain http to a loopback literal, any port and path, no fragment.
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return false
        return parsed.scheme == "http" && parsed.host in LOOPBACK_HOSTS && parsed.rawFragment == null &&
            parsed.rawUserInfo == null
    }

    /** Registration is open to the internet, so clients that never got a grant don't live forever. */
    private fun pruneUnusedClients(now: Instant) {
        val cutoff = now - Duration.ofDays(1)
        val used = DaoMcpOAuthGrant.all().map { it.clientId }.toSet()
        DaoMcpOAuthClient.find { DaoMcpOAuthClients.createdAt lessEq cutoff }
            .filter { it.clientId !in used }
            .forEach { it.delete() }
    }

    // ─── Authorization ─────────────────────────────────────────────────

    /**
     * Validate an authorization request that is safe to redirect errors for — the caller has
     * already matched [client] and [redirectUri], which must happen before anything is sent back
     * to a redirect URI (RFC 6749 §4.1.2.1).
     */
    fun beginAuthorization(
        client: McpOAuthClient,
        redirectUri: String,
        state: String?,
        responseType: String?,
        codeChallenge: String?,
        codeChallengeMethod: String?,
        resource: String?,
    ): McpPendingAuthorization {
        if (responseType != "code") throw McpOAuthError("unsupported_response_type", "Only response_type=code is supported")
        if (codeChallenge.isNullOrBlank()) throw McpOAuthError("invalid_request", "PKCE code_challenge is required")
        if (codeChallengeMethod != "S256") throw McpOAuthError("invalid_request", "code_challenge_method must be S256")
        checkResource(resource)
        val now = clock()
        pending.values.removeIf { it.expiresAt <= now }
        if (pending.size >= MAX_PENDING) throw McpOAuthError("temporarily_unavailable", "Too many sign-ins in progress")
        val request = McpPendingAuthorization(
            id = SessionTokens.newResetToken(),
            client = client,
            redirectUri = redirectUri,
            state = state,
            codeChallenge = codeChallenge,
            expiresAt = now + pendingTtl,
        )
        pending[request.id] = request
        return request
    }

    fun pendingAuthorization(id: String): McpPendingAuthorization? =
        pending[id]?.takeIf { it.expiresAt > clock() }

    /** The request is finished either way — denied, or a code issued. */
    fun completeAuthorization(id: String) {
        pending.remove(id)
    }

    /** Mint a one-minute code for [userId]. Returns the raw code for the redirect. */
    fun issueCode(request: McpPendingAuthorization, userId: Int): String {
        val now = clock()
        codes.values.removeIf { it.expiresAt <= now }
        val raw = SessionTokens.newToken()
        codes[SessionTokens.sha256Hex(raw)] = IssuedCode(
            userId, request.client, request.redirectUri, request.codeChallenge, now + codeTtl,
        )
        completeAuthorization(request.id)
        return raw
    }

    // ─── Sign-in lockout ───────────────────────────────────────────────

    private val signInFailures = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    /**
     * Whether the MCP sign-in page should refuse [username] outright. Stricter than the desk's
     * own login throttle (which only slows a guesser to one try a second) because this page is
     * the one reachable from the internet: [SIGN_IN_LOCKOUT_FAILURES] failures inside
     * [SIGN_IN_LOCKOUT_WINDOW] lock that username out *of this page* for the rest of the window.
     * The LAN login form is untouched, so a stranger cannot lock the crew out of the desk.
     */
    fun signInLockedOut(username: String): Boolean {
        val window = signInFailures[lockoutKey(username)] ?: return false
        val cutoff = clock() - SIGN_IN_LOCKOUT_WINDOW
        synchronized(window) {
            while (window.isNotEmpty() && window.first() <= cutoff) window.removeFirst()
            return window.size >= SIGN_IN_LOCKOUT_FAILURES
        }
    }

    fun recordSignInFailure(username: String) {
        if (signInFailures.size >= 1_000) signInFailures.clear()
        val window = signInFailures.computeIfAbsent(lockoutKey(username)) { ArrayDeque() }
        synchronized(window) { window.addLast(clock()) }
    }

    fun clearSignInFailures(username: String) {
        signInFailures.remove(lockoutKey(username))
    }

    private fun lockoutKey(username: String) = username.trim().lowercase().take(64)

    // ─── Token endpoint ────────────────────────────────────────────────

    fun exchangeCode(
        code: String?,
        clientId: String?,
        redirectUri: String?,
        codeVerifier: String?,
        resource: String?,
    ): McpTokenResponse {
        if (code.isNullOrBlank()) throw McpOAuthError("invalid_request", "code is required")
        // Removed on first sight, valid or not: a code is single use, and a failed exchange must
        // not leave it redeemable by whoever tries next with the right verifier.
        val issued = codes.remove(SessionTokens.sha256Hex(code))
            ?: throw McpOAuthError("invalid_grant", "Unknown or already-used authorization code")
        if (issued.expiresAt <= clock()) throw McpOAuthError("invalid_grant", "Authorization code expired")
        if (clientId != issued.client.clientId) throw McpOAuthError("invalid_grant", "Code was issued to another client")
        if (redirectUri != null && redirectUri != issued.redirectUri) {
            throw McpOAuthError("invalid_grant", "redirect_uri does not match the authorization request")
        }
        if (codeVerifier.isNullOrBlank() || s256(codeVerifier) != issued.codeChallenge) {
            throw McpOAuthError("invalid_grant", "PKCE verification failed")
        }
        checkResource(resource)
        val user = authService.findUser(issued.userId)
        if (user == null || user.disabled) throw McpOAuthError("invalid_grant", "The account is no longer active")
        return createGrant(issued.userId, issued.client)
    }

    fun refresh(refreshToken: String?, clientId: String?, resource: String?): McpTokenResponse {
        if (refreshToken.isNullOrBlank()) throw McpOAuthError("invalid_request", "refresh_token is required")
        checkResource(resource)
        val hash = SessionTokens.sha256Hex(refreshToken)
        val now = clock()
        val access = SessionTokens.newToken()
        val refresh = SessionTokens.newToken()
        val outcome = transaction(database) {
            val grant = DaoMcpOAuthGrant.find { DaoMcpOAuthGrants.refreshTokenHash eq hash }.singleOrNull()
            if (grant == null) {
                // Reuse of a retired refresh token: someone holds a copy. Kill the grant.
                DaoMcpOAuthGrant.find { DaoMcpOAuthGrants.previousRefreshHash eq hash }.singleOrNull()?.let {
                    if (it.revokedAt == null) it.revokedAt = now
                    return@transaction RefreshOutcome.Reused(it.accessTokenHash)
                }
                return@transaction RefreshOutcome.Invalid("Unknown refresh token")
            }
            if (grant.revokedAt != null) return@transaction RefreshOutcome.Invalid("This connection was revoked")
            if (grant.refreshExpiresAt <= now) return@transaction RefreshOutcome.Invalid("Refresh token expired")
            if (grant.clientId != clientId) return@transaction RefreshOutcome.Invalid("Token was issued to another client")
            val user = authService.findUser(grant.user.id.value)
            if (user == null || user.disabled) return@transaction RefreshOutcome.Invalid("The account is no longer active")
            val oldAccess = grant.accessTokenHash
            grant.previousRefreshHash = hash
            grant.refreshTokenHash = SessionTokens.sha256Hex(refresh)
            grant.refreshExpiresAt = now + refreshTtl
            grant.accessTokenHash = SessionTokens.sha256Hex(access)
            grant.accessExpiresAt = now + accessTtl
            grant.lastUsedAt = now
            RefreshOutcome.Rotated(oldAccess, grant.toRecord())
        }
        when (outcome) {
            is RefreshOutcome.Invalid -> throw McpOAuthError("invalid_grant", outcome.reason)
            is RefreshOutcome.Reused -> {
                grants.remove(outcome.accessHash)
                throw McpOAuthError("invalid_grant", "Refresh token reuse detected; the connection was revoked")
            }
            is RefreshOutcome.Rotated -> {
                grants.remove(outcome.oldAccessHash)
                grants[outcome.record.accessHash] = outcome.record
            }
        }
        return McpTokenResponse(accessToken = access, expiresIn = accessTtl.seconds, refreshToken = refresh)
    }

    private sealed interface RefreshOutcome {
        data class Invalid(val reason: String) : RefreshOutcome
        data class Reused(val accessHash: String) : RefreshOutcome
        data class Rotated(val oldAccessHash: String, val record: GrantRecord) : RefreshOutcome
    }

    private fun createGrant(userId: Int, client: McpOAuthClient): McpTokenResponse {
        val access = SessionTokens.newToken()
        val refresh = SessionTokens.newToken()
        val now = clock()
        val record = transaction(database) {
            DaoMcpOAuthGrant.new {
                this.user = DaoUser[userId]
                this.clientId = client.clientId
                this.clientName = client.clientName
                this.accessTokenHash = SessionTokens.sha256Hex(access)
                this.accessExpiresAt = now + accessTtl
                this.refreshTokenHash = SessionTokens.sha256Hex(refresh)
                this.refreshExpiresAt = now + refreshTtl
                this.createdAt = now
                this.lastUsedAt = now
            }.toRecord()
        }
        grants[record.accessHash] = record
        return McpTokenResponse(accessToken = access, expiresIn = accessTtl.seconds, refreshToken = refresh)
    }

    /**
     * RFC 8707: a client may name the resource it wants a token for. There is one — this desk's MCP
     * endpoint — and some clients name the server's base instead, so both are accepted.
     */
    private fun checkResource(resource: String?) {
        if (resource == null) return
        val r = resource.trimEnd('/')
        if (r != config.resourceUrl && r != config.publicUrl) {
            throw McpOAuthError("invalid_target", "This desk only issues tokens for ${config.resourceUrl}")
        }
    }

    // ─── Resolution, listing and revocation ────────────────────────────

    /**
     * The caller behind a bearer token, or null. Refused on a desk with no accounts even though
     * no grant could exist there: bootstrap-open means nobody consented, and the MCP surface is
     * the one reachable from the internet.
     */
    fun resolveAccessToken(raw: String): AuthenticatedUser? {
        if (!authService.hasAnyUser) return null
        val grant = grants[SessionTokens.sha256Hex(raw)] ?: return null
        val now = clock()
        if (grant.accessExpiresAt <= now) return null
        val user = authService.findUser(grant.userId) ?: return null
        if (user.disabled) return null

        val nowMs = now.toEpochMilli()
        val last = grant.lastPersistedMs.get()
        if (nowMs - last >= LAST_USED_WRITE_INTERVAL_MS && grant.lastPersistedMs.compareAndSet(last, nowMs)) {
            transaction(database) { DaoMcpOAuthGrant.findById(grant.id)?.lastUsedAt = now }
        }
        return AuthenticatedUser(
            userId = user.userId,
            uuid = user.uuid,
            username = user.username,
            displayName = user.displayName,
            role = user.role,
            sessionTokenHash = "mcp-grant:${grant.id}",
        )
    }

    fun grantsFor(userId: Int): List<ConnectedAppDto> = transaction(database) {
        val now = clock()
        DaoMcpOAuthGrant.find {
            (DaoMcpOAuthGrants.user eq userId) and DaoMcpOAuthGrants.revokedAt.isNull()
        }.filter { it.refreshExpiresAt > now }
            .sortedByDescending { it.lastUsedAt }
            .map { ConnectedAppDto(it.id.value, it.clientName, it.createdAt.toIsoUtc(), it.lastUsedAt.toIsoUtc()) }
    }

    /** Revoke one of [userId]'s own grants. False when it is not theirs or not live. */
    fun revokeGrant(userId: Int, grantId: Int): Boolean {
        val accessHash = transaction(database) {
            val grant = DaoMcpOAuthGrant.findById(grantId) ?: return@transaction null
            if (grant.user.id.value != userId || grant.revokedAt != null) return@transaction null
            grant.revokedAt = clock()
            grant.accessTokenHash
        } ?: return false
        grants.remove(accessHash)
        return true
    }

    /** RFC 7009: revoke by either token. Unknown tokens are not an error. */
    fun revokeToken(raw: String) {
        val hash = SessionTokens.sha256Hex(raw)
        val accessHash = transaction(database) {
            val grant = DaoMcpOAuthGrant.find {
                (DaoMcpOAuthGrants.accessTokenHash eq hash) or (DaoMcpOAuthGrants.refreshTokenHash eq hash)
            }.singleOrNull() ?: return@transaction null
            if (grant.revokedAt == null) grant.revokedAt = clock()
            grant.accessTokenHash
        } ?: return
        grants.remove(accessHash)
    }

    fun revokeAllFor(userId: Int) {
        val now = clock()
        transaction(database) {
            DaoMcpOAuthGrants.update({
                (DaoMcpOAuthGrants.user eq userId) and DaoMcpOAuthGrants.revokedAt.isNull()
            }) { it[revokedAt] = now }
        }
        grants.values.removeIf { it.userId == userId }
        codes.values.removeIf { it.userId == userId }
    }

    private fun DaoMcpOAuthGrant.toRecord() = GrantRecord(
        id = id.value,
        userId = user.id.value,
        accessHash = accessTokenHash,
        accessExpiresAt = accessExpiresAt,
        lastPersistedMs = AtomicLong(lastUsedAt.toEpochMilli()),
    )

    companion object {
        /** Where claude.ai (and so the Claude iOS, Android and desktop apps) sends the code back. */
        val BUILT_IN_REDIRECT_URIS = setOf(
            "https://claude.ai/api/mcp/auth_callback",
            "https://claude.com/api/mcp/auth_callback",
        )
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
        private const val MAX_CLIENTS = 200L
        private const val MAX_PENDING = 200
        const val SIGN_IN_LOCKOUT_FAILURES = 10
        val SIGN_IN_LOCKOUT_WINDOW: Duration = Duration.ofMinutes(15)
        private const val LAST_USED_WRITE_INTERVAL_MS = 10L * 60 * 1000

        /** PKCE S256: base64url(SHA-256(verifier)), unpadded. */
        fun s256(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}
