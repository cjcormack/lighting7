package uk.me.cormack.lighting7.auth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.routes.ErrorResponse
import uk.me.cormack.lighting7.state.State

/** The httpOnly session cookie. Owned by the auth package: `routes/auth.kt` writes it, the gate and the WS upgrade read it. */
const val SESSION_COOKIE = "lighting7_session"

val AuthenticatedUserKey = AttributeKey<AuthenticatedUser>("authUser")

/** The caller resolved by the auth gate, or null in bootstrap-open mode / on exempt paths. */
val ApplicationCall.authenticatedUserOrNull: AuthenticatedUser?
    get() = attributes.getOrNull(AuthenticatedUserKey)

/** The caller, for handlers that cannot mean anything without one (logout, sessions, password). */
val ApplicationCall.authenticatedUser: AuthenticatedUser
    get() = authenticatedUserOrNull ?: throw AuthenticationException("Not signed in")

/**
 * Resolve the session cookie to its user, or null. The single definition of "who is
 * calling" — used by the gate, the WS upgrade in `plugins/Sockets.kt`, and the exempt
 * `auth/status` route (which the gate deliberately skips).
 */
fun ApplicationCall.resolveSessionUser(state: State): AuthenticatedUser? =
    request.cookies[SESSION_COOKIE]?.let { state.authService.lookupSession(it) }

/**
 * 403 unless the caller is an ADMIN. A **missing** user attribute passes: the gate
 * only omits it when it already decided the call needs no credentials (bootstrap-open
 * mode, or an exempt path), so absence means "allowed", not "anonymous".
 */
fun ApplicationCall.requireAdmin() {
    val user = authenticatedUserOrNull ?: return
    if (user.role != UserRole.ADMIN) throw AuthorizationException("Administrator access required")
}

/**
 * The authentication gate (multi-user-auth plan, session 1). Installed inside
 * `route("/api/rest")` immediately after the warm-up intercept, and on the
 * `/script-editor` subtree — never on the root routing node, because the
 * static SPA (login page included) must stay reachable without credentials.
 *
 * While the desk has zero users the gate passes everything (bootstrap-open,
 * Decision 7): a fresh install behaves exactly as before this feature existed, and
 * the existing route tests run unchanged.
 */
// Route.intercept is deprecated in favour of route-scoped plugins, but respond+finish()
// remains the cleanest short-circuit — same trade-off as the warm-up gate in router.kt.
@Suppress("DEPRECATION")
fun Route.installAuthGate(state: State) {
    intercept(ApplicationCallPipeline.Plugins) {
        val auth = state.authService
        if (!auth.hasAnyUser) return@intercept
        val path = routingNormalisedPath(call.request.path())
        if (isAuthExempt(path)) return@intercept

        val user = call.resolveSessionUser(state)
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Not signed in", "unauthenticated"))
            finish()
            return@intercept
        }
        call.attributes.put(AuthenticatedUserKey, user)

        if (user.role != UserRole.ADMIN && isAdminOnly(path)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("Administrator access required", "forbidden"))
            finish()
        }
    }
}

/**
 * Normalise a raw request path the way Ktor's routing resolver does before matching:
 * split into segments, drop empty ones (`//`), percent-decode each. The exempt and
 * admin checks below must see the same path the router dispatches on, or an encoded
 * or double-slashed spelling of an admin route slips past the prefix check while
 * still reaching the handler.
 */
internal fun routingNormalisedPath(rawPath: String): String =
    rawPath.split('/')
        .filter { it.isNotEmpty() }
        .joinToString(separator = "/", prefix = "/") { segment ->
            // A segment routing can't decode can't match a route either; keep it raw.
            try {
                segment.decodeURLPart()
            } catch (_: Exception) {
                segment
            }
        }

/**
 * Paths that must answer without a cookie even once users exist: the boot-status poll, the
 * status probe the frontend gate runs on, and the ways to acquire a session.
 *
 * Two of those are QR flows, exempt for different reasons. **Reset** redemption
 * (`/auth/reset/`) is exempt because whoever opens that link is by definition locked out, so
 * requiring a session would make the recovery path impossible to walk. **Device login**
 * (`/auth/device/`) is exempt because acquiring a session is the entire point — the phone has
 * none yet, exactly as it has none when it POSTs to `/auth/login`.
 *
 * In both cases only the *redemption* half is exempt. Minting and polling stay behind the
 * gate: reset under the admin-only `/api/rest/users` subtree, device login under
 * `/api/rest/auth/device-logins`, which is authenticated but open to any role.
 *
 * **The trailing slash on `/auth/device/` is load-bearing.** Without it the prefix also
 * matches `/api/rest/auth/device-logins`, which would hand the mint endpoint to anyone on the
 * network. `AuthGateTest` pins this.
 */
private fun isAuthExempt(path: String): Boolean =
    path == "/api/rest/status" ||
        path == "/api/rest/auth/status" ||
        path == "/api/rest/auth/login" ||
        path == "/api/rest/auth/setup" ||
        path.startsWith("/api/rest/auth/reset/") ||
        path.startsWith("/api/rest/auth/device/")

/**
 * Admin territory: user management (Session 3), the install-level cloud-sync batch
 * endpoints, the GitHub OAuth flows, and every per-project cloud-sync route (config,
 * credentials, run, conflicts — they carry the desk's git identity and remotes).
 * Routes where only some methods are admin-only (`PUT /install`) call [requireAdmin]
 * per-handler instead.
 */
private fun isAdminOnly(path: String): Boolean =
    ADMIN_ONLY_PREFIXES.any { path.startsWith(it) } || PROJECT_SYNC_PATH.matches(path)

private val ADMIN_ONLY_PREFIXES = listOf(
    "/api/rest/users",
    "/api/rest/cloud-sync/",
    "/api/rest/oauth/",
)

/** The per-project cloud-sync subtree (`routes/cloudSync.kt`, mounted under `/project`). */
private val PROJECT_SYNC_PATH = Regex("^/api/rest/project/[^/]+/sync(/.*)?$")
