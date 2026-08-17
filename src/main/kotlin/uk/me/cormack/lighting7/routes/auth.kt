package uk.me.cormack.lighting7.routes

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.request.userAgent
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.auth.DeviceLoginLookup
import uk.me.cormack.lighting7.auth.DeviceLoginRedemption
import uk.me.cormack.lighting7.auth.ResetRedemption
import uk.me.cormack.lighting7.auth.ResetTokenLookup
import uk.me.cormack.lighting7.auth.SESSION_COOKIE
import uk.me.cormack.lighting7.auth.authenticatedUser
import uk.me.cormack.lighting7.auth.authenticatedUserOrNull
import uk.me.cormack.lighting7.auth.buildDeviceLoginUrls
import uk.me.cormack.lighting7.auth.resolveSessionUser
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.state.State
import java.net.InetAddress

/**
 * Session lifecycle routes (multi-user-auth plan, session 1). `status`, `login` and
 * `setup` are auth-exempt (see `auth/AuthGate.kt`); the rest resolve the caller via
 * the gate's attribute. The session rides the [SESSION_COOKIE] httpOnly cookie —
 * same-origin, so REST and the WS upgrade share it with zero token plumbing.
 */
internal fun Route.routeApiRestAuth(state: State) {
    get<AuthStatusResource> {
        // Exempt path: the gate never resolves the cookie here, so do it ourselves —
        // a logged-in caller must see `authenticated: true`, not the login screen.
        val user = call.authenticatedUserOrNull ?: call.resolveSessionUser(state)
        call.respond(
            AuthStatusDto(
                setupRequired = !state.authService.hasAnyUser,
                authenticated = user != null,
                user = user?.toDto(),
            ),
        )
    }

    post<AuthSetupResource> {
        val request = call.receive<SetupRequest>()
        val created = state.authService.createFirstAdmin(request.username, request.displayName, request.password)
        if (created == null) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Setup has already been completed"))
            return@post
        }
        // Log the new admin straight in — mintSession, not login: re-verifying the
        // password we hashed one line ago would pay a second bcrypt for nothing.
        val (user, rawToken) = state.authService.mintSession(
            created,
            call.request.userAgent(),
            call.request.origin.remoteHost,
        )
        call.respondAuthenticated(user, rawToken)
    }

    post<AuthLoginResource> {
        val request = call.receive<LoginRequest>()
        val (user, rawToken) = state.authService.login(
            request.username,
            request.password,
            call.request.userAgent(),
            call.request.origin.remoteHost,
        )
        call.respondAuthenticated(user, rawToken)
    }

    post<AuthLogoutResource> {
        // In bootstrap-open mode there is no session to log out of: `authenticatedUser`
        // throws and the caller gets 401. Deliberate, not an oversight.
        call.authenticatedUser
        call.request.cookies[SESSION_COOKIE]?.let { state.authService.logout(it) }
        call.clearSessionCookie()
        call.respond(HttpStatusCode.NoContent)
    }

    put<AuthPasswordResource> {
        val user = call.authenticatedUser
        val request = call.receive<ChangePasswordRequest>()
        state.authService.changeOwnPassword(
            user.userId,
            request.currentPassword,
            request.newPassword,
            currentTokenHash = user.sessionTokenHash,
        )
        call.respond(HttpStatusCode.NoContent)
    }

    get<AuthSessionsResource> {
        val user = call.authenticatedUser
        call.respond(state.authService.sessionsFor(user.userId, user.sessionTokenHash))
    }

    delete<AuthSessionsResource> {
        val user = call.authenticatedUser
        state.authService.revokeAllSessionsFor(user.userId, exceptTokenHash = user.sessionTokenHash)
        call.respond(HttpStatusCode.NoContent)
    }

    // ─── QR password reset, redeemed on the locked-out user's phone ─────
    //
    // Auth-exempt by definition: whoever opens this has no session and cannot get one.
    // The admin-side minting endpoints under `/users/**` are *not* exempt.

    get<AuthResetResource> { resource ->
        when (val lookup = state.authService.lookupResetToken(resource.token)) {
            is ResetTokenLookup.Live -> call.respond(
                ResetTokenInfoDto(
                    username = lookup.username,
                    displayName = lookup.displayName,
                    expiresAtMs = lookup.expiresAtMs,
                ),
            )
            // 410 rather than 404 so the phone can say *why* — "already used" and
            // "expired" need different copy from "that link isn't a link".
            is ResetTokenLookup.Dead -> call.respond(
                HttpStatusCode.Gone,
                ErrorResponse("This reset link is no longer valid", lookup.status.name),
            )
            ResetTokenLookup.Unknown -> call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse("Unknown reset link"),
            )
        }
    }

    post<AuthResetResource> { resource ->
        // Rate limit on client IP, not username: this endpoint is open to the LAN and the
        // caller has no identity to throttle on yet (plan 3.4). Cheap insurance — the
        // token itself is 16 random bytes, so this is about noise, not about guessability.
        val throttleKey = "reset-ip:${call.request.origin.remoteHost}"
        state.authService.awaitThrottle(throttleKey)

        val request = call.receive<RedeemResetRequest>()
        when (val outcome = state.authService.redeemResetToken(resource.token, request.newPassword)) {
            is ResetRedemption.Applied -> {
                state.authService.clearThrottleFailures(throttleKey)
                call.respond(HttpStatusCode.NoContent)
            }
            is ResetRedemption.Dead -> {
                state.authService.recordThrottleFailure(throttleKey)
                call.respond(
                    HttpStatusCode.Gone,
                    ErrorResponse("This reset link is no longer valid", outcome.status.name),
                )
            }
            ResetRedemption.Unknown -> {
                state.authService.recordThrottleFailure(throttleKey)
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Unknown reset link"))
            }
        }
    }

    // ─── Device-login QR: sign yourself in on a phone or tablet ─────────
    //
    // Minting is authenticated but open to **any** role — this is "sign my own phone in", not
    // an administrative act, so it cannot live under the admin-only `/api/rest/users` subtree.
    // Redemption is auth-exempt for the same reason `/auth/login` is: the phone has no session
    // and the whole point is to give it one.

    /**
     * Mint a QR that signs the caller in on another device. Always the caller's own account:
     * there is no target parameter, deliberately, so this can never become a way to hand
     * somebody else a session.
     */
    post<AuthDeviceLoginsResource> {
        val user = call.authenticatedUser
        val (record, rawToken) = state.authService.createDeviceLogin(user.userId)
        val urls = call.buildDeviceLoginUrls(rawToken)
        call.respond(
            HttpStatusCode.Created,
            DeviceLoginResponse(
                id = record.id,
                url = urls.primary,
                alternateUrls = urls.alternates,
                expiresAtMs = record.expiresAtMs,
                displayName = user.displayName,
            ),
        )
    }

    /** The desk sheet's poll: has a phone taken this QR, and which phone was it? */
    get<AuthDeviceLoginResource> { resource ->
        val user = call.authenticatedUser
        val status = state.authService.deviceLoginStatus(user.userId, resource.id)
        if (status == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Unknown sign-in code"))
            return@get
        }
        call.respond(status)
    }

    /** Cancel a live QR — fired when the sheet closes. Idempotent. */
    delete<AuthDeviceLoginResource> { resource ->
        val user = call.authenticatedUser
        if (!state.authService.cancelDeviceLogin(user.userId, resource.id)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Unknown sign-in code"))
            return@delete
        }
        call.respond(HttpStatusCode.NoContent)
    }

    /**
     * Whose account this QR would sign you in as. Does **not** consume the token: the phone
     * shows the name and waits for a tap, so a scanner prefetch, a link preview or a
     * double-render can't burn a two-minute code before its owner has looked at the screen.
     */
    get<AuthDeviceResource> { resource ->
        call.requireLanPeer() ?: return@get
        val throttleKey = call.deviceLoginThrottleKey()
        state.authService.awaitThrottle(throttleKey)

        when (val lookup = state.authService.lookupDeviceLogin(resource.token)) {
            is DeviceLoginLookup.Live -> {
                state.authService.clearThrottleFailures(throttleKey)
                call.respond(
                    DeviceLoginInfoDto(
                        username = lookup.username,
                        displayName = lookup.displayName,
                        expiresAtMs = lookup.expiresAtMs,
                    ),
                )
            }
            is DeviceLoginLookup.Dead -> {
                state.authService.recordThrottleFailure(throttleKey)
                call.respond(
                    HttpStatusCode.Gone,
                    ErrorResponse("This sign-in code is no longer valid", lookup.status.name),
                )
            }
            DeviceLoginLookup.Unknown -> {
                state.authService.recordThrottleFailure(throttleKey)
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Unknown sign-in code"))
            }
        }
    }

    /**
     * Exchange the QR for a real session.
     *
     * Takes a JSON body it barely reads, and that is the point: `docs/desk-accounts.md` rests
     * the CSRF answer on "`SameSite=Lax` plus JSON-only endpoints", and a public POST with no
     * body would break the second half. A cross-origin auto-submitting form POST needs no
     * preflight, so without this any page could drive a victim's browser through this endpoint
     * and leave them signed in as an account the attacker controls. `call.receive` refusing a
     * form encoding is what keeps that documented invariant true.
     */
    post<AuthDeviceResource> { resource ->
        call.requireLanPeer() ?: return@post
        val throttleKey = call.deviceLoginThrottleKey()
        state.authService.awaitThrottle(throttleKey)
        call.receive<RedeemDeviceLoginRequest>()

        val userAgent = call.request.userAgent()
        val clientIp = call.request.origin.remoteHost
        when (val outcome = state.authService.redeemDeviceLogin(resource.token, userAgent, clientIp)) {
            is DeviceLoginRedemption.Applied -> {
                state.authService.clearThrottleFailures(throttleKey)
                call.respondAuthenticated(outcome.user, outcome.rawToken)
            }
            is DeviceLoginRedemption.Dead -> {
                state.authService.recordThrottleFailure(throttleKey)
                call.respond(
                    HttpStatusCode.Gone,
                    ErrorResponse("This sign-in code is no longer valid", outcome.status.name),
                )
            }
            DeviceLoginRedemption.Unknown -> {
                state.authService.recordThrottleFailure(throttleKey)
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Unknown sign-in code"))
            }
        }
    }
}

/**
 * Throttle key for the two public device-login endpoints: the client IP, because the caller
 * has no identity yet. Both verbs are keyed, not just the POST — guessing a 32-byte token is
 * irrelevant, but a LAN client looping the free GET against a single-connection pool during a
 * show is not.
 */
private fun ApplicationCall.deviceLoginThrottleKey() = "device-login-ip:${request.origin.remoteAddress}"

/**
 * Refuse a device-login exchange from off the LAN, answering 404 so a probe from outside
 * learns nothing about whether the path exists.
 *
 * "Same network" is the flow's whole trust boundary — there is no confirmation step — and
 * `auth/ResetUrls.kt` only picks a *reachable* address to advertise; it does not restrict who
 * may redeem. Without this, anything that can route to the port qualifies: a venue's flat
 * network, a port-forward, a mesh VPN.
 *
 * Reads `origin.remoteAddress`, **not** `remoteHost`: Ktor documents the latter as "client
 * address *or host name if it can be resolved*", so on a network with working reverse DNS it
 * hands back a name, and resolving a name means a forward DNS lookup — blocking network I/O on
 * a public endpoint, and a phone on this very Wi-Fi refused outright if that lookup fails or
 * answers with a public A record. `remoteAddress` is the numeric peer, which
 * [InetAddress.getByName] parses as a literal without touching a resolver.
 *
 * Either one is the real socket peer only because `plugins/HTTP.kt` installs neither
 * `ForwardedHeaders` nor CORS. **Installing `ForwardedHeaders` later would silently defeat both
 * this check and the IP throttle above**, since a client would then choose its own apparent
 * address.
 *
 * Link-local counts as on-LAN alongside loopback and site-local: a 169.254.x peer is a device
 * on this segment whose DHCP lease failed, which is a show-night reality, not an outsider.
 *
 * Returns null (and has already responded) when the peer is refused.
 */
private suspend fun ApplicationCall.requireLanPeer(): Unit? {
    val remote = request.origin.remoteAddress
    val address = runCatching { InetAddress.getByName(remote) }.getOrNull()
    val onLan = address != null &&
        (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress)
    if (onLan) return Unit
    logger.warn("refusing device-login exchange from off-LAN peer {}", remote)
    respond(HttpStatusCode.NotFound, ErrorResponse("Unknown sign-in code"))
    return null
}

private val logger = LoggerFactory.getLogger("auth.routes")

@Resource("/auth/status")
data object AuthStatusResource

@Resource("/auth/setup")
data object AuthSetupResource

@Resource("/auth/login")
data object AuthLoginResource

@Resource("/auth/logout")
data object AuthLogoutResource

@Resource("/auth/password")
data object AuthPasswordResource

@Resource("/auth/sessions")
data object AuthSessionsResource

@Resource("/auth/reset/{token}")
data class AuthResetResource(val token: String)

/**
 * Device-login resources, declared **flat** rather than as a parent chain — the chain form is
 * what produces the `resource.parent.parent.userId` spelling over in `routes/users.kt`.
 *
 * Note the two prefixes are deliberately distinct words, not one nested under the other:
 * `/auth/device-logins` is authenticated, `/auth/device/` is public, and the auth gate tells
 * them apart by prefix. Nesting the public one under the private one would put a single
 * trailing slash between "mint your own QR" and "anyone on the network can mint one".
 */
@Resource("/auth/device-logins")
data object AuthDeviceLoginsResource

@Resource("/auth/device-logins/{id}")
data class AuthDeviceLoginResource(val id: String)

@Resource("/auth/device/{token}")
data class AuthDeviceResource(val token: String)

@Serializable
data class AuthUserDto(
    val uuid: String,
    val username: String,
    val displayName: String,
    val role: UserRole,
)

@Serializable
data class AuthStatusDto(
    val setupRequired: Boolean,
    val authenticated: Boolean,
    val user: AuthUserDto? = null,
)

@Serializable
data class SetupRequest(
    val username: String,
    val displayName: String,
    val password: String,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

/** What the phone shows before asking for a new password: whose account this link is for. */
@Serializable
data class ResetTokenInfoDto(
    val username: String,
    val displayName: String,
    val expiresAtMs: Long,
)

@Serializable
data class RedeemResetRequest(
    val newPassword: String,
)

/** A freshly minted device-login QR, as the desk's sheet needs it. */
@Serializable
data class DeviceLoginResponse(
    /** Opaque uuid for the poll and cancel URLs. */
    val id: String,
    /** The URL to render as a QR code. */
    val url: String,
    /** Other addresses the same page answers on, for a phone that can't reach [url]. */
    val alternateUrls: List<String>,
    val expiresAtMs: Long,
    /** Who the phone will be signed in as — always the caller. */
    val displayName: String,
)

/** What the phone shows before anyone taps: whose account this QR would sign you in as. */
@Serializable
data class DeviceLoginInfoDto(
    val username: String,
    val displayName: String,
    val expiresAtMs: Long,
)

/**
 * Empty on purpose. The exchange needs no input — the token is in the path — but it must be a
 * JSON *body* so `call.receive` refuses a cross-origin form POST; see the route's KDoc.
 */
@Serializable
class RedeemDeviceLoginRequest

private const val SESSION_COOKIE_MAX_AGE_SECONDS = 30 * 24 * 60 * 60

/** Shared tail of setup and login: set the cookie, answer the same status shape. */
private suspend fun ApplicationCall.respondAuthenticated(user: AuthenticatedUser, rawToken: String) {
    setSessionCookie(rawToken)
    respond(AuthStatusDto(setupRequired = false, authenticated = true, user = user.toDto()))
}

/** Cookie shape per the oauth.kt precedent; `secure = false` because desks run plain LAN HTTP. */
private fun ApplicationCall.setSessionCookie(rawToken: String) {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE,
            value = rawToken,
            maxAge = SESSION_COOKIE_MAX_AGE_SECONDS,
            path = "/",
            httpOnly = true,
            secure = false,
            extensions = mapOf("SameSite" to "Lax"),
            encoding = CookieEncoding.URI_ENCODING,
        ),
    )
}

private fun ApplicationCall.clearSessionCookie() {
    response.cookies.append(Cookie(name = SESSION_COOKIE, value = "", maxAge = 0, path = "/"))
}

private fun AuthenticatedUser.toDto() = AuthUserDto(
    uuid = uuid.toString(),
    username = username,
    displayName = displayName,
    role = role,
)
