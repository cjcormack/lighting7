package uk.me.cormack.lighting7.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.auth.AuthenticationException
import uk.me.cormack.lighting7.auth.AuthorizationException
import uk.me.cormack.lighting7.auth.PasswordPolicyException
import uk.me.cormack.lighting7.routes.ErrorResponse

private val logger = LoggerFactory.getLogger("error-handling")

/**
 * Last-resort exception handler.
 *
 * Without it, anything thrown out of a route handler reaches Netty's default, which answers 500
 * with an *empty body* — the client gets a status code and nothing else, so the UI can't say what
 * went wrong. Every response from here carries the same [ErrorResponse] shape that hand-written
 * error paths already use, so callers only ever have to understand one wire format.
 *
 * This is a safety net, not a substitute for explicit checks: handlers that can anticipate a
 * failure should still pre-check and return their own, more specific message.
 */
fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<ExposedSQLException> { call, cause ->
            val uniqueViolation = cause.isUniqueConstraintViolation()
            if (uniqueViolation) {
                logger.warn("Unique constraint violation on {}", call.request.local.uri, cause)
                call.respond(HttpStatusCode.Conflict, ErrorResponse(cause.friendlyConstraintMessage()))
            } else {
                logger.error("Database error on {}", call.request.local.uri, cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("Database error: ${cause.cause?.message ?: cause.message ?: "unknown"}"),
                )
            }
        }

        // Malformed or unparseable request bodies. Ktor throws BadRequestException from
        // `call.receive`; kotlinx wraps schema mismatches in SerializationException.
        exception<BadRequestException> { call, cause ->
            logger.warn("Bad request on {}: {}", call.request.local.uri, cause.message)
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.cause?.message ?: cause.message ?: "Malformed request"),
            )
        }
        exception<SerializationException> { call, cause ->
            logger.warn("Unparseable request body on {}: {}", call.request.local.uri, cause.message)
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.message ?: "Malformed request body"),
            )
        }
        // A body whose *content type* no converter handles — a form encoding posted at a
        // JSON-only endpoint, most often. ContentNegotiation throws this, which descends from
        // `ContentTransformationException` (an IOException) rather than from BadRequestException,
        // so without this clause it reached the catch-all and answered 500: a server fault for
        // what is squarely a caller mistake. It matters on the two public endpoints
        // (`/auth/reset/{token}` and `/auth/device/{token}`) in particular, where refusing a
        // cross-origin form post is the desk's whole CSRF answer — those must read as "no", not
        // as "we fell over".
        //
        // Deliberately the leaf type, not `ContentTransformationException`: its other subtypes
        // are `UnsupportedMediaTypeException` and `PayloadTooLargeException`, which Ktor answers
        // 415 and 413. StatusPages resolves to the nearest registered ancestor, so catching the
        // parent would quietly fold both of those into a generic 400 the day a body-size limit
        // is installed.
        exception<CannotTransformContentToTypeException> { call, cause ->
            logger.warn("Unsupported request body on {}: {}", call.request.local.uri, cause.message)
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.message ?: "Unsupported request body"),
            )
        }

        // Auth failures thrown by AuthService / the auth gate helpers. Expected outcomes,
        // not faults — logged at WARN so a wrong password doesn't page anyone.
        exception<AuthenticationException> { call, cause ->
            logger.warn("Unauthenticated request on {}: {}", call.request.local.uri, cause.message)
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(cause.message ?: "Not signed in", "unauthenticated"))
        }
        exception<AuthorizationException> { call, cause ->
            logger.warn("Forbidden request on {}: {}", call.request.local.uri, cause.message)
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(cause.message ?: "Not permitted", "forbidden"))
        }
        exception<PasswordPolicyException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Password rejected"))
        }

        exception<Throwable> { call, cause ->
            // A cancelled call is not a failure — the client hung up, or a parent scope was
            // cancelled. StatusPages has no special case for it (`findHandlerByValue` matches
            // this catch-all), so without the rethrow every aborted request would log an
            // error-level stack trace and then try to respond on a dead call. Rethrowing also
            // keeps structured concurrency intact.
            if (cause is CancellationException) throw cause

            logger.error("Unhandled exception on {}", call.request.local.uri, cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: cause::class.simpleName ?: "Internal server error"),
            )
        }
    }
}

/**
 * SQLite reports uniqueness failures as `SQLITE_CONSTRAINT_UNIQUE` / "UNIQUE constraint failed:".
 * Matched on the message text rather than the SQLSTATE because the driver reports its extended
 * result code in the message and leaves `sqlState` unhelpfully generic.
 */
private fun ExposedSQLException.isUniqueConstraintViolation(): Boolean {
    val text = generateSequence(this as Throwable) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" ")
    return text.contains("UNIQUE constraint failed", ignoreCase = true) ||
        text.contains("SQLITE_CONSTRAINT_UNIQUE", ignoreCase = true)
}

/**
 * Turns a raw driver message — `UNIQUE constraint failed: cues.cue_stack_id, cues.cue_number` —
 * into something an operator can act on. Additive by design: unmatched constraints fall through
 * to a generic message, which alongside a correct 409 is already far better than a bodyless 500.
 */
private fun ExposedSQLException.friendlyConstraintMessage(): String {
    val text = generateSequence(this as Throwable) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" ")

    return when {
        text.contains("cues.cue_stack_id") && text.contains("cues.cue_number") ->
            "That cue number is already used in this stack."
        text.contains("uq_cue_number_per_stack") ->
            "That cue number is already used in this stack."
        text.contains("uq_cue_stack_name_per_project") || text.contains("cue_stacks.name") ->
            "A cue stack with that name already exists in this project."
        text.contains("fx_definitions") && text.contains("effect_id") ->
            "An FX definition with that effect ID already exists in this project."
        else -> "That value is already in use."
    }
}
