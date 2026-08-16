package uk.me.cormack.lighting7.auth

/**
 * The three auth failure modes, each mapped to a status by `plugins/ErrorHandling.kt`:
 * [AuthenticationException] → 401, [AuthorizationException] → 403,
 * [PasswordPolicyException] → 400. Route handlers and `AuthService` throw these rather
 * than hand-rolling responses, so every auth failure carries the shared `ErrorResponse`
 * wire shape.
 */
class AuthenticationException(message: String) : Exception(message)

class AuthorizationException(message: String) : Exception(message)

class PasswordPolicyException(message: String) : Exception(message)
