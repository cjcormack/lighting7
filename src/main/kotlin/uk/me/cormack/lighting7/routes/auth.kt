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
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.auth.ResetRedemption
import uk.me.cormack.lighting7.auth.ResetTokenLookup
import uk.me.cormack.lighting7.auth.SESSION_COOKIE
import uk.me.cormack.lighting7.auth.authenticatedUser
import uk.me.cormack.lighting7.auth.authenticatedUserOrNull
import uk.me.cormack.lighting7.auth.resolveSessionUser
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.state.State

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
}

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
