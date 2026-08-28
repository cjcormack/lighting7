package uk.me.cormack.lighting7.routes

import kotlinx.serialization.Serializable

/**
 * Wire shape for every error response across the REST surface — hand-written route checks and
 * the [uk.me.cormack.lighting7.plugins.configureErrorHandling] safety net alike, so callers only
 * ever have to understand one error format.
 */
@Serializable
data class ErrorResponse(
    val error: String,
    /** Machine-readable error code for responses a client branches on (e.g. delete guards). */
    val code: String? = null,
)
