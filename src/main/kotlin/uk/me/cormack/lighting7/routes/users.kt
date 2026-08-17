package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.auth.ResetTokenStatus
import uk.me.cormack.lighting7.auth.UserMutation
import uk.me.cormack.lighting7.auth.UserRecord
import uk.me.cormack.lighting7.auth.authenticatedUserOrNull
import uk.me.cormack.lighting7.auth.buildResetUrls
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.state.State

/** The caller tried to remove the desk's only usable administrator. */
internal const val CODE_LAST_ADMIN = "LAST_ADMIN"

/** The caller tried to disable or delete their own account. */
internal const val CODE_SELF_TARGET = "SELF_TARGET"

private const val USER_NOT_FOUND = "User not found"

/**
 * Desk account administration (multi-user-auth plan, session 3). Every route here is
 * admin-only, enforced centrally: `/api/rest/users` is in `ADMIN_ONLY_PREFIXES` in
 * `auth/AuthGate.kt`, so an operator never reaches a handler. Password hashes never
 * appear in a DTO.
 *
 * Two guards shape the mutating routes, and both exist to keep the desk recoverable
 * without the break-glass file drop:
 *
 * - **Last admin.** Demoting, disabling or deleting the last enabled ADMIN is refused
 *   (409 + [CODE_LAST_ADMIN]). A desk with no usable admin can only be repaired by
 *   dropping `RESET-ADMIN` in the data dir and restarting — which means interrupting a
 *   show.
 * - **Self.** You cannot disable or delete the account you are signed in as (409 +
 *   [CODE_SELF_TARGET]). Changing your own display name or password is fine; those are
 *   what the user menu is for.
 */
internal fun Route.routeApiRestUsers(state: State) {
    get<UsersResource> {
        call.respond(state.authService.listUsers().map { it.toDto() })
    }

    post<UsersResource> {
        val request = call.receive<NewUserRequest>()
        val username = request.username.trim()
        validateUsername(username)?.let { error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
            return@post
        }
        val displayName = request.displayName.trim()
        validateDisplayName(displayName)?.let { error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
            return@post
        }
        // A duplicate username surfaces as the unique-index violation that
        // `plugins/ErrorHandling.kt` maps to 409 — the same race guard setup relies on.
        val created = state.authService.createUser(username, displayName, request.role, request.password)
        call.respond(HttpStatusCode.Created, created.toDto())
    }

    get<UserResource> { resource ->
        val user = state.authService.findUser(resource.userId)
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(USER_NOT_FOUND))
            return@get
        }
        call.respond(user.toDto())
    }

    put<UserResource> { resource ->
        val request = call.receive<UpdateUserRequest>()
        val displayName = request.displayName?.trim()
        if (displayName != null) {
            validateDisplayName(displayName)?.let { error ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
                return@put
            }
        }
        if (request.disabled == true && call.isSelf(resource.userId)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("You cannot disable your own account", CODE_SELF_TARGET),
            )
            return@put
        }

        // The last-admin guard is the service's, not this handler's: it has to be atomic
        // with the write (see AuthService.updateUser), so asking first would be a race.
        when (val outcome = state.authService.updateUser(resource.userId, displayName, request.role, request.disabled)) {
            is UserMutation.Done -> call.respond(outcome.value.toDto())
            UserMutation.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse(USER_NOT_FOUND))
            UserMutation.LastAdmin ->
                call.respond(HttpStatusCode.Conflict, ErrorResponse(LAST_ADMIN_MESSAGE, CODE_LAST_ADMIN))
        }
    }

    delete<UserResource> { resource ->
        if (call.isSelf(resource.userId)) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("You cannot delete your own account", CODE_SELF_TARGET),
            )
            return@delete
        }
        when (state.authService.deleteUser(resource.userId)) {
            is UserMutation.Done -> call.respond(HttpStatusCode.NoContent)
            UserMutation.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse(USER_NOT_FOUND))
            UserMutation.LastAdmin ->
                call.respond(HttpStatusCode.Conflict, ErrorResponse(LAST_ADMIN_MESSAGE, CODE_LAST_ADMIN))
        }
    }

    /** Direct password set, for when the user is standing next to you. All their sessions die. */
    put<UserPasswordResource> { resource ->
        val userId = resource.parent.userId
        if (state.authService.findUser(userId) == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(USER_NOT_FOUND))
            return@put
        }
        val request = call.receive<SetUserPasswordRequest>()
        state.authService.setPasswordAsAdmin(userId, request.newPassword)
        call.respond(HttpStatusCode.NoContent)
    }

    /**
     * Mint a QR reset token. The response carries the URL to encode plus every alternate
     * address the page answers on — see `auth/ResetUrls.kt` for why the request's own
     * `Host` header is the first choice and loopback is the one case that overrides it.
     */
    post<UserResetTokensResource> { resource ->
        val userId = resource.parent.userId
        val user = state.authService.findUser(userId)
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(USER_NOT_FOUND))
            return@post
        }
        val minted = state.authService.createResetToken(userId, call.authenticatedUserOrNull?.userId)
        if (minted == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(USER_NOT_FOUND))
            return@post
        }
        val (record, rawToken) = minted
        val urls = call.buildResetUrls(rawToken)
        call.respond(
            HttpStatusCode.Created,
            ResetTokenResponse(
                id = record.id,
                url = urls.primary,
                alternateUrls = urls.alternates,
                expiresAtMs = record.expiresAtMs,
                username = user.username,
                displayName = user.displayName,
            ),
        )
    }

    /** The admin sheet's poll: has the phone at the other end of the QR used it yet? */
    get<UserResetTokenResource> { resource ->
        val status = state.authService.resetTokenStatus(resource.parent.parent.userId, resource.tokenId)
        if (status == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Reset token not found"))
            return@get
        }
        val (value, expiresAtMs) = status
        call.respond(ResetTokenStatusDto(status = value, expiresAtMs = expiresAtMs))
    }

    /** Cancel a live token — fired when the admin closes the QR sheet. Idempotent. */
    delete<UserResetTokenResource> { resource ->
        val cancelled = state.authService.cancelResetToken(resource.parent.parent.userId, resource.tokenId)
        if (!cancelled) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Reset token not found"))
            return@delete
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private const val LAST_ADMIN_MESSAGE =
    "This is the only enabled administrator — promote another account first"

/**
 * Column widths from `models/users.kt`, enforced here because SQLite doesn't: it stores an
 * over-long varchar happily, and the same row would then be rejected outright by a
 * length-enforcing database. Same reasoning — and the same shape — as the `friendlyName`
 * checks in `routes/install.kt`.
 */
private const val MAX_USERNAME_LENGTH = 64
private const val MAX_DISPLAY_NAME_LENGTH = 100

private fun validateUsername(username: String): String? = when {
    username.isEmpty() -> "Username must not be blank"
    username.length > MAX_USERNAME_LENGTH -> "Username must be $MAX_USERNAME_LENGTH characters or fewer"
    else -> null
}

private fun validateDisplayName(displayName: String): String? = when {
    displayName.isEmpty() -> "Display name must not be blank"
    displayName.length > MAX_DISPLAY_NAME_LENGTH ->
        "Display name must be $MAX_DISPLAY_NAME_LENGTH characters or fewer"
    else -> null
}

/**
 * Whether [userId] is the caller's own account. In bootstrap-open mode there is no caller,
 * so nothing is "self" and the self-guards can't fire — correct, since with zero users
 * there is no account to lock yourself out of.
 */
private fun ApplicationCall.isSelf(userId: Int): Boolean =
    authenticatedUserOrNull?.userId == userId

@Resource("/users")
data object UsersResource

@Resource("/users/{userId}")
data class UserResource(val userId: Int)

@Resource("/password")
data class UserPasswordResource(val parent: UserResource)

@Resource("/reset-tokens")
data class UserResetTokensResource(val parent: UserResource)

@Resource("/{tokenId}")
data class UserResetTokenResource(val parent: UserResetTokensResource, val tokenId: Int)

@Serializable
data class UserDto(
    val id: Int,
    val uuid: String,
    val username: String,
    val displayName: String,
    val role: UserRole,
    val disabled: Boolean,
    val createdAtMs: Long,
    val lastLoginAtMs: Long? = null,
)

@Serializable
data class NewUserRequest(
    val username: String,
    val displayName: String,
    val role: UserRole,
    val password: String,
)

/** Null means "leave this field alone", so a form can send only what the admin changed. */
@Serializable
data class UpdateUserRequest(
    val displayName: String? = null,
    val role: UserRole? = null,
    val disabled: Boolean? = null,
)

@Serializable
data class SetUserPasswordRequest(
    val newPassword: String,
)

@Serializable
data class ResetTokenResponse(
    val id: Int,
    /** The URL to render as a QR code. */
    val url: String,
    /** Other addresses the same page answers on, for a phone that can't reach [url]. */
    val alternateUrls: List<String>,
    val expiresAtMs: Long,
    val username: String,
    val displayName: String,
)

@Serializable
data class ResetTokenStatusDto(
    val status: ResetTokenStatus,
    val expiresAtMs: Long,
)

private fun UserRecord.toDto() = UserDto(
    id = userId,
    uuid = uuid.toString(),
    username = username,
    displayName = displayName,
    role = role,
    disabled = disabled,
    createdAtMs = createdAtMs,
    lastLoginAtMs = lastLoginAtMs,
)
