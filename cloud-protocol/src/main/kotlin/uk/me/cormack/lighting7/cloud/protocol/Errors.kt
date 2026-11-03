package uk.me.cormack.lighting7.cloud.protocol

/**
 * The cloud's WebSocket close codes. Only [INSTALL_REVOKED] changes the desk's state: after any
 * other close the desk reconnects. A close's reason text carries the detail each code names.
 */
enum class CloseCode(val code: Short) {
    /** A frame that is not a valid envelope, a known type with a malformed body, or one over [MAX_FRAME_BYTES]. */
    PROTOCOL_ERROR(4000),

    /** The desk's [PROTOCOL_VERSION] is no longer served. The reason is the lowest desk version that is. */
    PROTOCOL_UNSUPPORTED(4001),

    /** [Message.Auth] was refused. The reason is an [AuthFailure] name. */
    AUTH_FAILED(4003),

    /** The install was removed in the portal: the desk forgets its adoption. */
    INSTALL_REVOKED(4004),

    /** A newer connection from the same install took this one's place. */
    REPLACED(4009),
    ;

    companion object {
        fun of(code: Int): CloseCode? = entries.firstOrNull { it.code.toInt() == code }
    }
}

/**
 * Why a signed thing was refused: the reason of an [CloseCode.AUTH_FAILED] close, and the
 * [ApiError.code] of a refused signed request.
 */
enum class AuthFailure {
    /** The signature does not verify against the install's key. */
    BAD_SIGNATURE,

    /** The signed timestamp is more than [CLOCK_SKEW_TOLERANCE] from the cloud's clock. */
    CLOCK_SKEW,

    /** A field is missing or not of its form, or the install id is not the one being authenticated. */
    MALFORMED,

    /** No [Message.Auth] came in time after the [Message.Challenge]. */
    TIMEOUT,
    ;

    companion object {
        /** The failure [reason] names, or null for a reason this version does not know. */
        fun parse(reason: String): AuthFailure? = entries.firstOrNull { it.name == reason }
    }
}

/**
 * The codes [Message.Error] and [ApiError] carry. Strings rather than an enum so that a code a
 * newer peer adds reads as an unknown code, not a decoding failure.
 */
object ErrorCode {
    const val PROTOCOL_ERROR: String = "PROTOCOL_ERROR"
    const val BAD_SIGNATURE: String = "BAD_SIGNATURE"
    const val CLOCK_SKEW: String = "CLOCK_SKEW"
    const val MALFORMED: String = "MALFORMED"

    /** The adoption or install a request names is not one the cloud has. */
    const val NOT_FOUND: String = "NOT_FOUND"
}
