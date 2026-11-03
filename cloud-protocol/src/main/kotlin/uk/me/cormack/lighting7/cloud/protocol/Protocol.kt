package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The protocol version both sides speak. Within a version every change is additive: a new message
 * type, a new optional field, a new capability in [Message.Hello]. Anything else is a new version
 * (docs/cloud-protocol.md §"Versioning").
 */
const val PROTOCOL_VERSION: Int = 1

/** The largest frame either side sends or accepts on the control channel, 8 MiB. */
const val MAX_FRAME_BYTES: Int = 8 * 1024 * 1024

/** How far a signed timestamp may be from the receiver's clock before it is refused as [AuthFailure.CLOCK_SKEW]. */
val CLOCK_SKEW_TOLERANCE: Duration = Duration.ofMinutes(5)

/** The cloud's paths, relative to its base URL. */
object CloudPaths {
    /** The control channel, a WebSocket (`wss`). */
    const val DESK_CHANNEL: String = "/v1/desk"

    /** `POST` an [AdoptionRequest]; answered [AdoptionStarted]. */
    const val ADOPTIONS: String = "/v1/adoptions"

    /** `POST`, signed ([AdoptionPollPayload]); answered [AdoptionPollResult]. */
    fun adoptionPoll(adoptionId: String): String = "/v1/adoptions/$adoptionId/poll"

    /** `DELETE`, signed ([UnadoptPayload]): unadopt from the desk. */
    fun install(installId: String): String = "/v1/installs/$installId"
}

/** The headers a signed HTTP request carries: the timestamp it signed and the signature. */
object SignatureHeaders {
    const val TIMESTAMP: String = "Lighting7-Timestamp"
    const val SIGNATURE: String = "Lighting7-Signature"
}

/**
 * The one JSON configuration both sides use. Unknown keys are ignored, so a field a later desk or
 * cloud adds within [PROTOCOL_VERSION] reaches an older reader harmlessly; absent optional fields
 * are omitted rather than written as `null`.
 */
val CloudJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * A point in time on the wire, the form every signed timestamp takes: ISO-8601 in UTC with exactly
 * three fraction digits, `2026-11-02T22:18:03.000Z`. A signature covers the string, so both sides
 * sign and verify what was sent, never a re-formatting of it.
 */
object Timestamps {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun format(instant: Instant): String = FORMAT.format(instant.truncatedTo(ChronoUnit.MILLIS))

    /** The instant [text] names, or null when it is not a timestamp in exactly the form [format] writes. */
    fun parse(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()?.takeIf { format(it) == text }

    /** Whether [text] is a timestamp within [CLOCK_SKEW_TOLERANCE] of [now]. */
    fun withinSkew(text: String, now: Instant): Boolean {
        val at = parse(text) ?: return false
        return Duration.between(at, now).abs() <= CLOCK_SKEW_TOLERANCE
    }
}

/**
 * The user code an adoption shows (RFC 8628 §6.1): eight letters from twenty consonants with no
 * vowels, so no word can be spelled, written `XXXX-XXXX`.
 */
object UserCode {
    const val ALPHABET: String = "BCDFGHJKLMNPQRSTVWXZ"

    private val SHAPE = Regex("[$ALPHABET]{4}-[$ALPHABET]{4}")

    fun isWellFormed(code: String): Boolean = SHAPE.matches(code)
}
