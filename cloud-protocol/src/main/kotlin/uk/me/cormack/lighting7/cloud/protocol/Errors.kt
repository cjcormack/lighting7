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
 * The codes the protocol carries: an [Message.Error] or [ApiError]'s `code`, an [Message.RtcClose]'s
 * `reason`, a [Message.LinkAck]'s `reason`, a [CtlMessage.Refused]'s `code`, and a [TokenRefusal]'s.
 * Strings rather than an enum so that a code a newer peer adds reads as an unknown code, not a
 * decoding failure (docs/cloud-protocol.md §"Codes").
 */
object ErrorCode {
    const val PROTOCOL_ERROR: String = "PROTOCOL_ERROR"
    const val BAD_SIGNATURE: String = "BAD_SIGNATURE"
    const val CLOCK_SKEW: String = "CLOCK_SKEW"
    const val MALFORMED: String = "MALFORMED"

    /** The adoption or install a request names is not one the cloud has. */
    const val NOT_FOUND: String = "NOT_FOUND"

    /** The cloud account is not linked to a local user on this desk. */
    const val CLOUD_ACCOUNT_NOT_LINKED: String = "CLOUD_ACCOUNT_NOT_LINKED"

    /** The desk's *Remote sessions* switch is off. */
    const val REMOTE_ACCESS_OFF: String = "REMOTE_ACCESS_OFF"

    /** An admin at the desk denied the session, or nobody allowed it in time. */
    const val REMOTE_SESSION_DENIED: String = "REMOTE_SESSION_DENIED"

    /** The desk has as many remote sessions open, or has had as many offers this minute, as it takes. */
    const val REMOTE_SESSION_LIMIT: String = "REMOTE_SESSION_LIMIT"

    /** A remote session ended for none of the other reasons: ended in the portal, or by the desk. */
    const val SESSION_ENDED: String = "SESSION_ENDED"

    /** The desk's *Claude (MCP)* switch is off. */
    const val MCP_ACCESS_OFF: String = "MCP_ACCESS_OFF"

    /** The desk's sidecar is not running, so it cannot take a remote session. */
    const val SIDECAR_UNAVAILABLE: String = "SIDECAR_UNAVAILABLE"

    /** The cloud has no control channel open to the desk. */
    const val DESK_OFFLINE: String = "DESK_OFFLINE"

    /** The desk's control channel closed while a request to it was outstanding. */
    const val DESK_DISCONNECTED: String = "DESK_DISCONNECTED"

    /** The desk did not answer a request in time. */
    const val DESK_TIMEOUT: String = "DESK_TIMEOUT"

    /** The desk is running as many relayed calls as it takes. */
    const val DESK_BUSY: String = "DESK_BUSY"

    /** The desk does not announce [Capability.REMOTE_V1]. */
    const val DESK_REMOTE_UNSUPPORTED: String = "DESK_REMOTE_UNSUPPORTED"

    /** The desk does not announce [Capability.MCP_V1]. */
    const val DESK_MCP_UNSUPPORTED: String = "DESK_MCP_UNSUPPORTED"

    /** A request body over what one frame can relay, [MAX_RELAYED_REQUEST_BYTES]. */
    const val REQUEST_TOO_LARGE: String = "REQUEST_TOO_LARGE"

    /** A link acceptance whose email is not the invite's. */
    const val LINK_EMAIL_MISMATCH: String = "LINK_EMAIL_MISMATCH"

    /** A link acceptance for an invite that expired, was cancelled or was never made. */
    const val LINK_INVITE_NOT_LIVE: String = "LINK_INVITE_NOT_LIVE"

    /** The cloud account is already linked to another local user on this desk, or the user to another account. */
    const val LINK_TAKEN: String = "LINK_TAKEN"

    /** A token that verifies but whose session, account or install a `revoke` has revoked. */
    const val TOKEN_REVOKED: String = "TOKEN_REVOKED"

    /** A remote token for a `sid` the desk never opened, or has ended. */
    const val REMOTE_SESSION_UNKNOWN: String = "REMOTE_SESSION_UNKNOWN"

    /** A remote token whose `sub` or `org` is not the account and org its session was opened for. The desk then ends that session. */
    const val REMOTE_SESSION_MISMATCH: String = "REMOTE_SESSION_MISMATCH"

    /** An `mcp.call` whose `grant.id` is not its token's `gid`. */
    const val MCP_GRANT_MISMATCH: String = "MCP_GRANT_MISMATCH"

    const val TOKEN_MALFORMED: String = "TOKEN_MALFORMED"
    const val TOKEN_UNKNOWN_KEY: String = "TOKEN_UNKNOWN_KEY"
    const val TOKEN_BAD_SIGNATURE: String = "TOKEN_BAD_SIGNATURE"
    const val TOKEN_WRONG_AUDIENCE: String = "TOKEN_WRONG_AUDIENCE"
    const val TOKEN_WRONG_SCOPE: String = "TOKEN_WRONG_SCOPE"
    const val TOKEN_TOO_LONG_LIVED: String = "TOKEN_TOO_LONG_LIVED"
    const val TOKEN_NOT_YET_VALID: String = "TOKEN_NOT_YET_VALID"
    const val TOKEN_EXPIRED: String = "TOKEN_EXPIRED"
}

/**
 * What a desk announces in [Message.Hello] and [Message.Capabilities]: what it speaks, and the state
 * that stops it serving what it speaks. Strings, so a newer one reads as unknown.
 */
object Capability {
    /** The desk takes remote sessions (`rtc.*`), whatever its switch says. */
    const val REMOTE_V1: String = "remote.v1"

    /** The desk relays MCP calls (`mcp.*`), whatever its switch says. */
    const val MCP_V1: String = "mcp.v1"

    /** The *Remote sessions* switch is off: an offer is refused with [ErrorCode.REMOTE_ACCESS_OFF]. */
    const val REMOTE_OFF: String = "remote.off"

    /** The *Claude (MCP)* switch is off: a call is refused with [ErrorCode.MCP_ACCESS_OFF]. */
    const val MCP_OFF: String = "mcp.off"

    /** No sidecar is running: an offer is refused with [ErrorCode.SIDECAR_UNAVAILABLE]. */
    const val REMOTE_NO_SIDECAR: String = "remote.no-sidecar"

    val ALL: List<String> = listOf(REMOTE_V1, MCP_V1, REMOTE_OFF, MCP_OFF, REMOTE_NO_SIDECAR)
}
