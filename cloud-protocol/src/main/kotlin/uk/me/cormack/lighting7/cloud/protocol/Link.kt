package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The contracts between a browser and the desk's sidecar, `lighting7-link`, over a remote session's
 * WebRTC data channels (docs/cloud-protocol.md §"The link framing" and §"ctl"). The desk's JVM speaks
 * neither; they are here, with fixtures under `protocol/link/`, so the sidecar (Go) and the cloud's
 * shell (TypeScript) read one specification.
 */

/** The framing of every data channel but `ctl`: each message binary, its first byte its kind. */
object LinkFraming {
    /** The channel a session's control messages ride, [CtlMessage]s as JSON text. Opened first. */
    const val LABEL_CTL: String = "ctl"

    /** One channel per HTTP request. */
    const val LABEL_HTTP: String = "http"

    /** One channel per WebSocket. */
    const val LABEL_WS: String = "ws"

    /** A request's or response's head, UTF-8 JSON. */
    const val HEAD: Int = 0x01

    /** Body bytes, 1 to [MAX_CHUNK_BYTES] of them. */
    const val DATA: Int = 0x02

    /** The body is complete. No payload. */
    const val END: Int = 0x03

    /** The exchange is abandoned, UTF-8 JSON `{reason}`. Either side; the channel then closes. */
    const val RESET: Int = 0x04

    /** A WebSocket text message's fragment, more to come: 1 to [MAX_CHUNK_BYTES] bytes. */
    const val TEXT_PART: Int = 0x10

    /** A WebSocket text message's last (or only) fragment: 0 to [MAX_CHUNK_BYTES] bytes, so an empty message is one empty frame. */
    const val TEXT_END: Int = 0x11

    /** A WebSocket binary message's fragment, more to come: 1 to [MAX_CHUNK_BYTES] bytes. */
    const val BINARY_PART: Int = 0x12

    /** A WebSocket binary message's last (or only) fragment: 0 to [MAX_CHUNK_BYTES] bytes. */
    const val BINARY_END: Int = 0x13

    /** The WebSocket closed, UTF-8 JSON `{code, reason}`. */
    const val CLOSE: Int = 0x20

    /** The most bytes a [DATA] frame or a message fragment carries after its kind byte, 16 KiB. */
    const val MAX_CHUNK_BYTES: Int = 16 * 1024

    /**
     * The most bytes a [HEAD], [RESET] or [CLOSE] frame's JSON may take: with its kind byte, 64 KiB, the
     * largest message SCTP carries when a peer's SDP names no `max-message-size` (RFC 8841).
     */
    const val MAX_HEAD_BYTES: Int = 64 * 1024 - 1

    /** A sender stops sending on a channel once its `bufferedAmount` passes this. */
    const val BUFFERED_HIGH_BYTES: Int = 1024 * 1024

    /** ... and resumes when it falls to this, the channel's `bufferedAmountLowThreshold`. */
    const val BUFFERED_LOW_THRESHOLD_BYTES: Int = 256 * 1024

    /**
     * The headers the sidecar removes from a head's `headers` before forwarding it, compared without
     * case: the ones that authorise or identify a request, which only the sidecar sets (the session's
     * token, its secret, `Origin` from the head's `origin`), and the ones its own connection to the desk
     * owns.
     */
    val STRIPPED_HEADERS: List<String> = listOf(
        "authorization",
        "cookie",
        "host",
        "lighting7-link",
        "origin",
        "connection",
        "keep-alive",
        "proxy-authorization",
        "proxy-connection",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
        "sec-websocket-key",
        "sec-websocket-version",
        "sec-websocket-extensions",
    )

    /** Every kind, by name. */
    val KINDS: Map<String, Int> = mapOf(
        "HEAD" to HEAD,
        "DATA" to DATA,
        "END" to END,
        "RESET" to RESET,
        "TEXT_PART" to TEXT_PART,
        "TEXT_END" to TEXT_END,
        "BINARY_PART" to BINARY_PART,
        "BINARY_END" to BINARY_END,
        "CLOSE" to CLOSE,
    )
}

/**
 * A message on a session's `ctl` channel, JSON text with `type` naming which. A reader ignores a
 * `type` it does not know, as [decodeOrNull] does; the serializer itself refuses one.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface CtlMessage {
    companion object {
        private val TYPES = setOf("hello", "ready", "refused", "token")

        /**
         * The message [text] holds, or null when its `type` is one this version does not know. Throws
         * `SerializationException` for text that is not a JSON object with a string `type`, or a known
         * type with a malformed body.
         */
        fun decodeOrNull(text: String, json: Json = CloudJson): CtlMessage? {
            val element = json.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("A ctl message is a JSON object")
            val type = (element["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw SerializationException("A ctl message needs a string type")
            if (type !in TYPES) return null
            return json.decodeFromJsonElement(serializer(), element)
        }
    }

    /** Browser → sidecar, first: the session's desk access token. */
    @Serializable
    @SerialName("hello")
    data class Hello(val token: String) : CtlMessage

    /**
     * Sidecar → browser, answering [Hello]: the desk took the token. A linked session names its
     * [user] and [role]; an unlinked one says [unlinked]. [build] is the desk's build.
     */
    @Serializable
    @SerialName("ready")
    data class Ready(
        val user: String? = null,
        val role: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val unlinked: Boolean = false,
        val build: String,
    ) : CtlMessage

    /** Sidecar → browser: the desk refused the session's token, [code] an [ErrorCode]. The sidecar then ends the session. */
    @Serializable
    @SerialName("refused")
    data class Refused(val code: String) : CtlMessage

    /** Browser → sidecar: a refreshed token for the same session, used from the next request on. */
    @Serializable
    @SerialName("token")
    data class Token(val token: String) : CtlMessage
}

/**
 * The remote-frame contract: the `postMessage` messages between the cloud's shell, the desk's SPA
 * framed in it, and the shell's service worker (docs/cloud-protocol.md §"The remote frame"). Each is
 * a JSON object whose `type` is one of [MESSAGE_TYPES].
 */
object RemoteFrame {
    /** The version an SPA's `hello` names. */
    const val PROTOCOL: Int = 1

    val MESSAGE_TYPES: Set<String> = setOf(
        "hello",
        "port",
        "open",
        "send",
        "message",
        "close",
        "session.end",
        "auth.unlinked",
        "port.request",
    )
}
