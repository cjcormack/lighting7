package uk.me.cormack.lighting7.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import uk.me.cormack.lighting7.cloud.protocol.IceCandidate
import uk.me.cormack.lighting7.cloud.protocol.IceServer

/*
 * The stdio protocol between the desk and its sidecar, lighting7-link (cloud-p1 plan D7;
 * docs/cloud-engineering.md §"The sidecar"): the launch's link secret as the first line on its stdin,
 * then one JSON object per line each way, `type` naming each. Both ends are in this repo, so it is not
 * part of cloud-protocol; `link/testdata/stdio.json` holds one of each, read by both ends' tests.
 */

internal val LinkJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    classDiscriminator = "type"
}

/** Desk → sidecar. */
@Serializable
sealed interface LinkIn {
    val sid: String

    /** Answer the browser's [offer] for a new session, gathering through [iceServers]. */
    @Serializable
    @SerialName("session.open")
    data class Open(override val sid: String, val offer: String, val iceServers: List<IceServer>) : LinkIn

    /** One of the browser's candidates, or none for the end of them. */
    @Serializable
    @SerialName("session.ice")
    data class Ice(override val sid: String, val candidate: IceCandidate? = null) : LinkIn

    /** End the session, for [reason] (an `ErrorCode`). */
    @Serializable
    @SerialName("session.close")
    data class Close(override val sid: String, val reason: String) : LinkIn

    fun encode(): String = LinkJson.encodeToString(LinkIn.serializer(), this)
}

/** Sidecar → desk. */
@Serializable
sealed interface LinkOut {
    /** The sidecar has read its secret and serves; [build] echoes the desk's. The first line it writes. */
    @Serializable
    @SerialName("started")
    data class Started(val build: String? = null) : LinkOut

    /** The sidecar's answer to an [LinkIn.Open]. */
    @Serializable
    @SerialName("session.answer")
    data class Answer(val sid: String, val sdp: String) : LinkOut

    /** One of the sidecar's candidates, or none for the end of them. */
    @Serializable
    @SerialName("session.ice")
    data class Ice(val sid: String, val candidate: IceCandidate? = null) : LinkOut

    /** The session's selected candidate pair is [path]: `DIRECT`, or `RELAY` through TURN. */
    @Serializable
    @SerialName("session.state")
    data class State(val sid: String, val path: String) : LinkOut

    /** The session is over, for [reason] (an `ErrorCode`): the browser went, the desk refused its token, a limit. */
    @Serializable
    @SerialName("session.closed")
    data class Closed(val sid: String, val reason: String) : LinkOut

    companion object {
        private val TYPES = setOf("started", "session.answer", "session.ice", "session.state", "session.closed")

        /** The message [line] holds, or null when it is not one this desk knows. */
        fun decodeOrNull(line: String): LinkOut? = runCatching {
            val element = LinkJson.parseToJsonElement(line) as? JsonObject ?: return null
            val type = (element["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (type !in TYPES) return null
            LinkJson.decodeFromJsonElement(LinkOut.serializer(), element)
        }.getOrNull()
    }
}

/** The paths a [LinkOut.State] names. */
object LinkPath {
    const val DIRECT: String = "DIRECT"
    const val RELAY: String = "RELAY"

    val ALL: Set<String> = setOf(DIRECT, RELAY)
}
