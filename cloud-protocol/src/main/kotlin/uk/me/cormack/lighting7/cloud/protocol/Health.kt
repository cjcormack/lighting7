package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.Serializable

/*
 * The parts of a full heartbeat ([Message.Health], docs/cloud-protocol.md §"Health"): what the cloud
 * judges its fleet alerts on. Every time is a [Timestamps] string and every code an [ErrorCode] or a
 * desk's own code, read as text.
 */

/** The project a desk runs: its uuid and the name its operator gave it. */
@Serializable
data class HealthProject(val uuid: String, val name: String)

/** One universe of the live project. */
@Serializable
data class HealthUniverse(
    /** The Art-Net port address, `subnet * 16 + universe`. */
    val universe: Int,
    /** The controller's address as the project names it; absent for a universe with none. */
    val address: String? = null,
    /** Art-Net frames sent per second, over the last 30 s; absent when unsaid. */
    val framesPerS: Double? = null,
    /** Whether the controller answers ArtPoll; absent when unknown, which the cloud never alerts on. */
    val reachable: Boolean? = null,
    /** Seconds since the controller last answered; absent when it never has. */
    val lastReplyS: Long? = null,
)

/** Cloud sync across every project on the desk. */
@Serializable
data class HealthSync(
    val lastRunAt: String? = null,
    val lastOkAt: String? = null,
    /** When the oldest project still failing first failed; absent while none fails. */
    val failingSince: String? = null,
    /** The code of a run failing now. */
    val code: String? = null,
)

/** The update client's state, its phase as the desk names it (`IDLE`, `DOWNLOADING`, …). */
@Serializable
data class HealthUpdate(
    val phase: String,
    val lastOutcome: String? = null,
    val stagedVersion: String? = null,
)

/** The desk's off-site backups. */
@Serializable
data class HealthBackup(
    /** The desk's *Off-site backups* switch. */
    val enabled: Boolean,
    val lastOkAt: String? = null,
    val lastFailedAt: String? = null,
    /** The last failure's code. */
    val code: String? = null,
)
