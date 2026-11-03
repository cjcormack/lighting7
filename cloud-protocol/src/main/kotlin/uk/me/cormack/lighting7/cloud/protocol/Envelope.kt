package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.DeserializationStrategy
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
 * A control-channel message. Each one's `@SerialName` is its envelope `type`. Directions are as P0
 * uses them (docs/cloud-protocol.md §"Messages").
 */
sealed interface Message {
    /** Cloud → desk, first on every connection: the nonce the desk signs in [Auth]. */
    @Serializable
    @SerialName("challenge")
    data class Challenge(val nonce: String, val serverTime: String) : Message

    /** Desk → cloud, answering [Challenge]: [signature] is over [AuthPayload]. */
    @Serializable
    @SerialName("auth")
    data class Auth(val installId: String, val timestamp: String, val signature: String) : Message

    /** Cloud → desk, once [Auth] is accepted. [heartbeatS] is how often the desk sends [Health]. */
    @Serializable
    @SerialName("welcome")
    data class Welcome(val orgName: String, val heartbeatS: Int) : Message

    /** Desk → cloud, after [Welcome]: who the desk is and what it can do beyond P0. */
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

    /** A message of a type this version does not know, or of another protocol version, kept whole so it re-encodes as it came. */
    data class Unknown(override val type: String, val body: JsonObject) : Message

    /** This message's envelope `type`. */
    val type: String get() = typed().type
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
