package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

/**
 * One control-channel frame: `{v, type, id, replyTo?, body}`, `type` naming [body]'s message and
 * `body` its fields. A frame whose `type` this version does not know decodes to [Message.Unknown]
 * rather than failing, so a newer peer's messages reach an older one as something to ignore. So does
 * every frame of another protocol version, whose bodies this version's classes may not describe:
 * the reader judges [v].
 */
@Serializable(with = EnvelopeSerializer::class)
data class Envelope(
    /** Unique per message from one sender; what a reply's [replyTo] names. */
    val id: String,
    val body: Message,
    val replyTo: String? = null,
    val v: Int = PROTOCOL_VERSION,
) {
    val type: String get() = body.type

    companion object {
        fun decode(text: String, json: Json = CloudJson): Envelope = json.decodeFromString(EnvelopeSerializer, text)
    }

    fun encode(json: Json = CloudJson): String = json.encodeToString(EnvelopeSerializer, this)
}

/**
 * A control-channel message. Each one's `@SerialName` is its envelope `type`. Directions, and which
 * messages expect a reply, are in docs/cloud-protocol.md §"Messages" and §"Replies".
 */
@OptIn(ExperimentalSerializationApi::class)
sealed interface Message {
    /** Cloud → desk, first on every connection: the nonce the desk signs in [Auth]. */
    @Serializable
    @SerialName("challenge")
    data class Challenge(val nonce: String, val serverTime: String) : Message

    /** Desk → cloud, answering [Challenge]: [signature] is over [AuthPayload]. */
    @Serializable
    @SerialName("auth")
    data class Auth(val installId: String, val timestamp: String, val signature: String) : Message

    /**
     * Cloud → desk, once [Auth] is accepted. [heartbeatS] is how often the desk sends [Health].
     * [remoteOrigin] is the origin the cloud serves this install's remote sessions from, which the desk
     * allows in its Origin check; absent from a cloud that serves none.
     */
    @Serializable
    @SerialName("welcome")
    data class Welcome(val orgName: String, val heartbeatS: Int, val remoteOrigin: String? = null) : Message

    /** Desk → cloud, after [Welcome]: who the desk is, and its [Capability] list. */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocol: Int,
        val deskVersion: String,
        val platform: String,
        val deskInstallUuid: String,
        val friendlyName: String,
        val capabilities: List<String> = emptyList(),
    ) : Message

    /** Desk → cloud, every [Welcome.heartbeatS] seconds. */
    @Serializable
    @SerialName("health")
    data class Health(val uptimeS: Long, val deskVersion: String) : Message

    /** Cloud → desk: the install was removed in the portal. The desk forgets its adoption. */
    @Serializable
    @SerialName("unadopted")
    data object Unadopted : Message

    /** Either way: a request could not be met. [code] is one of [ErrorCode]'s, or a newer one. */
    @Serializable
    @SerialName("error")
    data class Error(val code: String, val message: String) : Message

    /** Desk → cloud: the desk's [Capability] list has changed. [list] replaces the whole of [Hello.capabilities]. */
    @Serializable
    @SerialName("capabilities")
    data class Capabilities(val list: List<String>) : Message

    /** Cloud → desk, after [Welcome] and whenever the keys change: the keys desk access tokens are signed with. */
    @Serializable
    @SerialName("jwks")
    data class Jwks(val keys: List<Jwk>) : Message

    /**
     * Cloud → desk: end and refuse what [sid], [accountId] and [all] name (normally one of them), every
     * token for it issued at or before [at], the cloud's own time of the revocation ([covers]).
     */
    @Serializable
    @SerialName("revoke")
    data class Revoke(
        val at: String,
        val sid: String? = null,
        val accountId: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val all: Boolean = false,
    ) : Message {
        /** Everything this revokes. A revoke naming more than one target revokes each; one naming none revokes nothing. */
        val targets: Set<RevokeTarget>
            get() = buildSet {
                sid?.let { add(RevokeTarget.Session(it)) }
                accountId?.let { add(RevokeTarget.Account(it)) }
                if (all) add(RevokeTarget.All)
            }

        /**
         * Whether a token for a target of this revoke, issued at [iat] (whole seconds, floored), is
         * revoked: it is when [iat] is at or before [at]'s whole second, so a token minted in the second
         * of the revocation is refused too. An [at] that is not a [Timestamps] string covers every token.
         */
        fun covers(iat: Long): Boolean {
            val revokedAt = Timestamps.parse(at) ?: return true
            return iat <= revokedAt.epochSecond
        }
    }

    /** Cloud → desk: a cloud account asks for a remote session. Answered by [RtcAnswer] or, refused, [RtcClose]. */
    @Serializable
    @SerialName("rtc.offer")
    data class RtcOffer(
        val sid: String,
        val accountId: String,
        val email: String,
        val displayName: String,
        val sdp: String,
        val iceServers: List<IceServer>,
    ) : Message

    /** Desk → cloud, replying to [RtcOffer]: the sidecar's answer. */
    @Serializable
    @SerialName("rtc.answer")
    data class RtcAnswer(val sid: String, val sdp: String) : Message

    /** Either way: one ICE candidate for [sid], or none to say there are no more. */
    @Serializable
    @SerialName("rtc.ice")
    data class RtcIce(val sid: String, val candidate: IceCandidate? = null) : Message

    /** Either way: [sid] is over, or, as the reply to an [RtcOffer], refused. [reason] is an [ErrorCode]. */
    @Serializable
    @SerialName("rtc.close")
    data class RtcClose(val sid: String, val reason: String) : Message

    /** Desk → cloud: an admin invites [email] to link to the local user [userName]. [expiresAt] is a [Timestamps] string. */
    @Serializable
    @SerialName("link.invite")
    data class LinkInvite(
        val inviteId: String,
        val email: String,
        val userName: String,
        val invitedByName: String,
        val expiresAt: String,
    ) : Message

    /** Desk → cloud: the admin cancelled [inviteId]. */
    @Serializable
    @SerialName("link.cancelled")
    data class LinkCancelled(val inviteId: String) : Message

    /** Cloud → desk: an owner or admin of the org declined [inviteId] in the portal. */
    @Serializable
    @SerialName("link.refused")
    data class LinkRefused(val inviteId: String) : Message

    /** Cloud → desk: [accountId], signed in as [email], accepted [inviteId]. Answered by [LinkAck]. */
    @Serializable
    @SerialName("link.accepted")
    data class LinkAccepted(val inviteId: String, val accountId: String, val email: String) : Message

    /** Desk → cloud, replying to [LinkAccepted]. [reason] is an [ErrorCode] when [outcome] is [LinkOutcome.REFUSED]. */
    @Serializable
    @SerialName("link.ack")
    data class LinkAck(val inviteId: String, val outcome: LinkOutcome, val reason: String? = null) : Message

    /** Desk → cloud: [accountId] proved it is the local user [userName] and is now linked to it. */
    @Serializable
    @SerialName("link.created")
    data class LinkCreated(val accountId: String, val userName: String) : Message

    /** Desk → cloud: [accountId]'s link to this desk is gone. */
    @Serializable
    @SerialName("link.removed")
    data class LinkRemoved(val accountId: String) : Message

    /**
     * Cloud → desk: run one MCP JSON-RPC message, [request], as the account [token] names, under
     * [grant]. Answered by [McpResult], or by [Error] when the desk refuses the call.
     */
    @Serializable
    @SerialName("mcp.call")
    data class McpCall(val token: String, val grant: McpGrant, val request: JsonObject) : Message

    /** Desk → cloud, replying to [McpCall]: the JSON-RPC response, absent for a notification. */
    @Serializable
    @SerialName("mcp.result")
    data class McpResult(val response: JsonObject? = null) : Message

    /** A message of a type this version does not know, or of another protocol version, kept whole so it re-encodes as it came. */
    data class Unknown(override val type: String, val body: JsonObject) : Message

    /** This message's envelope `type`. */
    val type: String get() = typed().type
}

/** What a [Message.Revoke] names. */
sealed interface RevokeTarget {
    data class Session(val sid: String) : RevokeTarget

    data class Account(val accountId: String) : RevokeTarget

    data object All : RevokeTarget
}

/** Every message type this version knows, by envelope `type`. */
val KNOWN_MESSAGE_TYPES: Set<String> get() = decoders.keys

private val decoders: Map<String, DeserializationStrategy<Message>> = listOf<DeserializationStrategy<Message>>(
    Message.Challenge.serializer(),
    Message.Auth.serializer(),
    Message.Welcome.serializer(),
    Message.Hello.serializer(),
    Message.Health.serializer(),
    Message.Unadopted.serializer(),
    Message.Error.serializer(),
    Message.Capabilities.serializer(),
    Message.Jwks.serializer(),
    Message.Revoke.serializer(),
    Message.RtcOffer.serializer(),
    Message.RtcAnswer.serializer(),
    Message.RtcIce.serializer(),
    Message.RtcClose.serializer(),
    Message.LinkInvite.serializer(),
    Message.LinkCancelled.serializer(),
    Message.LinkRefused.serializer(),
    Message.LinkAccepted.serializer(),
    Message.LinkAck.serializer(),
    Message.LinkCreated.serializer(),
    Message.LinkRemoved.serializer(),
    Message.McpCall.serializer(),
    Message.McpResult.serializer(),
).associateBy { it.descriptor.serialName }

/** A known message beside its own serializer, so it encodes without an unchecked cast. */
private class Typed<T : Message>(private val serializer: KSerializer<T>, private val message: T) {
    val type: String get() = serializer.descriptor.serialName

    fun encode(json: Json): JsonElement = json.encodeToJsonElement(serializer, message)
}

private fun Message.typed(): Typed<*> = when (this) {
    is Message.Challenge -> Typed(Message.Challenge.serializer(), this)
    is Message.Auth -> Typed(Message.Auth.serializer(), this)
    is Message.Welcome -> Typed(Message.Welcome.serializer(), this)
    is Message.Hello -> Typed(Message.Hello.serializer(), this)
    is Message.Health -> Typed(Message.Health.serializer(), this)
    is Message.Unadopted -> Typed(Message.Unadopted.serializer(), this)
    is Message.Error -> Typed(Message.Error.serializer(), this)
    is Message.Capabilities -> Typed(Message.Capabilities.serializer(), this)
    is Message.Jwks -> Typed(Message.Jwks.serializer(), this)
    is Message.Revoke -> Typed(Message.Revoke.serializer(), this)
    is Message.RtcOffer -> Typed(Message.RtcOffer.serializer(), this)
    is Message.RtcAnswer -> Typed(Message.RtcAnswer.serializer(), this)
    is Message.RtcIce -> Typed(Message.RtcIce.serializer(), this)
    is Message.RtcClose -> Typed(Message.RtcClose.serializer(), this)
    is Message.LinkInvite -> Typed(Message.LinkInvite.serializer(), this)
    is Message.LinkCancelled -> Typed(Message.LinkCancelled.serializer(), this)
    is Message.LinkRefused -> Typed(Message.LinkRefused.serializer(), this)
    is Message.LinkAccepted -> Typed(Message.LinkAccepted.serializer(), this)
    is Message.LinkAck -> Typed(Message.LinkAck.serializer(), this)
    is Message.LinkCreated -> Typed(Message.LinkCreated.serializer(), this)
    is Message.LinkRemoved -> Typed(Message.LinkRemoved.serializer(), this)
    is Message.McpCall -> Typed(Message.McpCall.serializer(), this)
    is Message.McpResult -> Typed(Message.McpResult.serializer(), this)
    is Message.Unknown -> error("An unknown message has no serializer")
}

/** Writes and reads an [Envelope] as one JSON object; JSON only, as every frame is. */
object EnvelopeSerializer : KSerializer<Envelope> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("uk.me.cormack.lighting7.cloud.protocol.Envelope") {
        element("v", JsonPrimitive.serializer().descriptor)
        element("type", JsonPrimitive.serializer().descriptor)
        element("id", JsonPrimitive.serializer().descriptor)
        element("replyTo", JsonPrimitive.serializer().descriptor, isOptional = true)
        element("body", JsonObject.serializer().descriptor)
    }

    override fun serialize(encoder: Encoder, value: Envelope) {
        val out = encoder as? JsonEncoder ?: throw SerializationException("An envelope is JSON only")
        val message = value.body
        val type = message.type
        val body = if (message is Message.Unknown) message.body else message.typed().encode(out.json)
        out.encodeJsonElement(
            buildJsonObject {
                put("v", JsonPrimitive(value.v))
                put("type", JsonPrimitive(type))
                put("id", JsonPrimitive(value.id))
                value.replyTo?.let { put("replyTo", JsonPrimitive(it)) }
                put("body", body)
            },
        )
    }

    override fun deserialize(decoder: Decoder): Envelope {
        val input = decoder as? JsonDecoder ?: throw SerializationException("An envelope is JSON only")
        val frame = input.decodeJsonElement() as? JsonObject ?: throw SerializationException("An envelope is a JSON object")
        fun string(key: String): String? = (frame[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val v = (frame["v"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: throw SerializationException("An envelope needs an integer v")
        val type = string("type") ?: throw SerializationException("An envelope needs a string type")
        val id = string("id") ?: throw SerializationException("An envelope needs a string id")
        val replyTo = when (val r = frame["replyTo"]) {
            null, JsonNull -> null
            else -> (r as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw SerializationException("An envelope's replyTo is a string")
        }
        val body = frame["body"] as? JsonObject ?: throw SerializationException("An envelope needs an object body")
        val decoder = decoders[type]?.takeIf { v == PROTOCOL_VERSION }
        val message = decoder?.let { input.json.decodeFromJsonElement(it, body) } ?: Message.Unknown(type, body)
        return Envelope(id = id, body = message, replyTo = replyTo, v = v)
    }
}
