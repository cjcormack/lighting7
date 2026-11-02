package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/*
 * Adoption (RFC 8628's shape, run by the desk's backend): the desk sends an [AdoptionRequest] to
 * [CloudPaths.ADOPTIONS], shows the [AdoptionStarted] user code to an admin, and polls
 * [CloudPaths.adoptionPoll] every [AdoptionStarted.intervalS] seconds, each poll signed over an
 * [AdoptionPollPayload], until an [AdoptionPollResult] other than [AdoptionPollResult.Pending].
 */

/** Desk → cloud: start adopting this install. */
@Serializable
data class AdoptionRequest(
    /** The install key's public half, [InstallKeys.encodePublicKey]. */
    val publicKey: String,
    /** The desk's own `installs.uuid`. */
    val deskInstallUuid: String,
    val friendlyName: String,
    val platform: String,
    val deskVersion: String,
)

/** Cloud → desk: the adoption is waiting for someone to approve it in the portal. */
@Serializable
data class AdoptionStarted(
    /** What the desk polls; useless without the install key's signature. */
    val adoptionId: String,
    /** `XXXX-XXXX`, from [UserCode.ALPHABET]. */
    val userCode: String,
    val verificationUri: String,
    /** [verificationUri] with the user code filled in, for a QR code. */
    val verificationUriComplete: String,
    /** The least time between polls. */
    val intervalS: Int,
    /** How long the adoption waits for approval, from when this answer was sent. */
    val expiresInS: Int,
)

/**
 * Cloud → desk, answering a poll. `status` names which, and is written only when the value is encoded
 * as an [AdoptionPollResult]: encoded as one of its subclasses (Ktor's `call.respond(Pending)` takes
 * the static type) it has no `status` and the desk cannot read it. Respond with
 * `call.respond<AdoptionPollResult>(…)`, or encode with `AdoptionPollResult.serializer()`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("status")
sealed interface AdoptionPollResult {
    /** Not approved yet: poll again after the interval. */
    @Serializable
    @SerialName("pending")
    data object Pending : AdoptionPollResult

    /** Polling too fast: wait [intervalS] between polls from now on (RFC 8628 §3.5). */
    @Serializable
    @SerialName("slow_down")
    data class SlowDown(val intervalS: Int) : AdoptionPollResult

    /** Approved: the install is now [installId] in [org], approved by [account]. */
    @Serializable
    @SerialName("approved")
    data class Approved(val installId: String, val org: CloudOrg, val account: CloudAccount) : AdoptionPollResult

    /** Refused in the portal. */
    @Serializable
    @SerialName("denied")
    data object Denied : AdoptionPollResult

    /** Nobody approved it in time. */
    @Serializable
    @SerialName("expired")
    data object Expired : AdoptionPollResult
}

@Serializable
data class CloudOrg(val id: String, val name: String)

@Serializable
data class CloudAccount(val id: String, val email: String, val displayName: String)

/** The body of an HTTP refusal from the cloud's API. [code] is one of [ErrorCode]'s, or a newer one. */
@Serializable
data class ApiError(val code: String, val message: String)
