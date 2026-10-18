package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.state.RigSafetyService
import java.time.Instant

/**
 * The `rigSafety.*` family (venue-visit plan S6): one outbound frame and **no inbound**. Every
 * operation — a bulk command, *Make rig safe*, *Undo*, *Release*, *Quit desk* — is REST
 * (`routes/projectRigSafety.kt`, `routes/installQuit.kt`), so the socket gains no operation and
 * `FU-AUTH-WS-PER-MESSAGE` stays unfired. The frame is how every window learns *Ready to switch off*
 * and follows a staggered strike that can wait minutes on a restrike.
 */
@Serializable
sealed class RigSafetyOutMessage : OutMessage()

/** One unit of a run. [remainingMs] is the time left **when this frame was sent**: a wait's, or a hold's. */
@Serializable
data class CommandRunUnitDto(
    val fixture: String,
    val fixtureName: String,
    val command: String,
    /** `QUEUED`, `WAITING` (a restrike), `RUNNING`, `DONE`, `CUT_SHORT`, `SKIPPED` or `CANCELLED`. */
    val status: String,
    val remainingMs: Long? = null,
    val code: String? = null,
    val message: String? = null,
)

/** One bulk run: a role over a set of units. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CommandRunDto(
    val id: String,
    /** `LAMP_ON`, `LAMP_OFF` or `RESET`. */
    val role: String,
    /** `BULK`, `MAKE_SAFE` or `UNDO`. */
    val purpose: String,
    val startedAt: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val units: List<CommandRunUnitDto> = emptyList(),
    /** How many units the selection named have no command of [role]. */
    val withoutCommand: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val finished: Boolean = false,
    val cancelled: Boolean = false,
)

/**
 * A unit whose lamp went off on this desk; [restrikeRemainingMs] is 0 once it can be struck, and
 * [coolRemainingMs] 0 once its mains can be cut.
 */
@Serializable
data class LampOffDto(val fixture: String, val offAt: String, val restrikeRemainingMs: Long, val coolRemainingMs: Long)

/** A unit *Undo* would strike. */
@Serializable
data class RigUnitDto(val fixture: String, val fixtureName: String)

/**
 * The end of the night on the live project. `StateFlow`-backed, so the subscription is the connect
 * snapshot. Times left are measured when the frame is sent, so a client counts down from its own
 * receive time, as `effects.armed` does.
 *
 * [quitAllowed] is the receiving socket's own: an admin on the desk's listener. *Quit desk* is never
 * offered over remote access.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("rigSafety.state")
data class RigSafetyStateOutMessage(
    val projectId: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val ready: Boolean = false,
    val readySince: String? = null,
    /** Made safe, but a lamp is still cooling: how long until the last has and the rig is [ready]. */
    val coolingRemainingMs: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val blackout: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val undo: List<RigUnitDto> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val notOff: List<String> = emptyList(),
    val run: CommandRunDto? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val lampsOff: List<LampOffDto> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val quitAllowed: Boolean = false,
) : RigSafetyOutMessage()

internal fun RigSafetyService.RunSnapshot.toDto(nowMs: Long = System.currentTimeMillis()) = CommandRunDto(
    id = id,
    role = role.name,
    purpose = purpose.name,
    startedAt = Instant.ofEpochMilli(startedAtMs).toIsoUtc(),
    units = units.map { u ->
        CommandRunUnitDto(
            fixture = u.unit.fixtureKey,
            fixtureName = u.unit.fixtureName,
            command = u.unit.command,
            status = u.status.name,
            remainingMs = u.untilMs?.let { (it - nowMs).coerceAtLeast(0) },
            code = u.code,
            message = u.message,
        )
    },
    withoutCommand = withoutCommand,
    finished = finished,
    cancelled = cancelled,
)

internal fun RigSafetyService.Snapshot.toMessage(quitAllowed: Boolean, nowMs: Long = System.currentTimeMillis()) = RigSafetyStateOutMessage(
    projectId = projectId,
    ready = ready,
    readySince = readySinceMs?.let { Instant.ofEpochMilli(it).toIsoUtc() },
    coolingRemainingMs = coolingUntilMs?.let { (it - nowMs).coerceAtLeast(0) },
    blackout = blackout,
    undo = undo.map { (key, name) -> RigUnitDto(key, name) },
    notOff = notOff,
    run = run?.toDto(nowMs),
    lampsOff = lampsOff.map {
        LampOffDto(
            it.fixtureKey,
            Instant.ofEpochMilli(it.offAtMs).toIsoUtc(),
            (it.restrikeAtMs - nowMs).coerceAtLeast(0),
            (it.coolAtMs - nowMs).coerceAtLeast(0),
        )
    },
    quitAllowed = quitAllowed,
)

/**
 * Whether this socket may quit the desk: on the desk's own listener, as an admin (or on a desk with no
 * accounts). The role is the one resolved at upgrade, as `tunnel.state`'s is; the route decides.
 */
internal fun SocketScope.mayQuit(): Boolean = !remote && (user == null || user.role == UserRole.ADMIN)

/**
 * Registered in the **show band** of [configureSockets]: the run and the ready state are the live
 * show's. A `StateFlow`, so it is its own snapshot.
 */
fun setupRigSafetySubscriptions(scope: SocketScope) {
    val quitAllowed = scope.mayQuit()
    scope.subscribe(scope.state.rigSafety.snapshot) { scope.send(it.toMessage(quitAllowed)) }
}
