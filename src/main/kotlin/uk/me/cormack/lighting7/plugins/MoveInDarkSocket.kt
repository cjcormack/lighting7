package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.MoveInDarkService

/**
 * The `moveInDark.*` family (move-in-dark plan D11, session 2): one outbound frame and **no
 * inbound**. What a stack moves in the dark is the desk's (P2); the settings are stored writes over
 * REST, so the socket gains no operation and `FU-AUTH-WS-PER-MESSAGE` stays unfired.
 */
@Serializable
sealed class MoveInDarkOutMessage : OutMessage()

/** A target's pan and tilt bytes, where the move carries Position. */
@Serializable
data class MoveInDarkPositionDto(val pan: Int, val tilt: Int)

/**
 * One head a stack is moving, or waiting to: [target] the head's key, [entryId] and [cueId] what it
 * is moved for, [label] that cue as a GO names it. [state] is `WAITING` (dark, counting the delay),
 * `MOVING`, `SET` or `LIT_EARLY`; [remainingMs] the delay or move time left at send, for a client to
 * count down. [families] the families the move carries.
 */
@Serializable
data class MoveInDarkHeadDto(
    val target: String,
    val entryId: Int,
    val cueId: Int,
    val label: String,
    val state: String,
    val remainingMs: Long? = null,
    val families: List<String>,
    val position: MoveInDarkPositionDto? = null,
)

@Serializable
data class MoveInDarkStackDto(val stackId: Int, val heads: List<MoveInDarkHeadDto>)

/**
 * Every head the live show's stacks are moving in the dark, or waiting to; a stack absent moves
 * none. `StateFlow`-backed, so the subscription is the connect snapshot; republished when a head's
 * state, target or move changes, at most every 100 ms (`MoveInDarkService.FRAME_MS`), never for the
 * time left alone.
 */
@Serializable
@SerialName("moveInDark.state")
data class MoveInDarkStateOutMessage(val projectId: Int, val stacks: List<MoveInDarkStackDto>) : MoveInDarkOutMessage()

internal fun MoveInDarkService.Snapshot.toMessage(projectId: Int) = MoveInDarkStateOutMessage(
    projectId = projectId,
    stacks = stacks.map { (stackId, heads) ->
        MoveInDarkStackDto(
            stackId,
            heads.map { head ->
                MoveInDarkHeadDto(
                    target = head.head,
                    entryId = head.targetEntryId,
                    cueId = head.targetCueId,
                    label = head.label,
                    state = head.state.name,
                    remainingMs = head.remainingMs,
                    families = head.families.sortedBy { it.ordinal }.map { it.name },
                    position = head.position?.let { MoveInDarkPositionDto(it.pan.toInt(), it.tilt.toInt()) },
                )
            },
        )
    },
)

/** Follows the live show: the service is the show's, which a project switch or a reload replaces. */
fun setupMoveInDarkSubscriptions(scope: SocketScope) {
    scope.subscribeShow({ show ->
        val projectId = show.project.id.value
        show.moveInDark.state.map { it.toMessage(projectId) }.distinctUntilChanged()
    }) { scope.send(it) }
}
