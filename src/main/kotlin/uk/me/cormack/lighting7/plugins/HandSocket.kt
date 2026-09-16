package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.BuskPadKind
import uk.me.cormack.lighting7.routes.HandService
import uk.me.cormack.lighting7.state.HandState

private val logger = LoggerFactory.getLogger("HandSocket")

// ─── Inbound ────────────────────────────────────────────────────────────

/**
 * The `hand.*` family: the desk's one held record ([uk.me.cormack.lighting7.state.HandState]),
 * picked up on any window and placed on any other (multi-screen plan §3.5, D12).
 *
 * **There are three frames, and none of them places.** Every target a place can land on already has
 * a mutation with its own validation, so a `hand.place` would reimplement four of them behind one
 * name. A place is the placing window's own mutation followed by [HandDropInMessage] — the desk's
 * share of it is letting go.
 *
 * Reply convention 3, like `selection.*` and `windows.*`: nothing is answered directly. A pick-up
 * or a drop lands and the `hand.state` broadcast carries the new hand to every client including
 * this one.
 */
@Serializable
sealed class HandInMessage : InMessage()

/**
 * Take a record into the desk's hand.
 *
 * [kind] is `TEMPLATE`, `LOOK` or `CUE` and [id] is that record's id **in the current project** —
 * the handler resolves it there and nowhere else, so a stale id from another project picks nothing
 * up rather than putting another show's row in the hand. A record that does not resolve is dropped
 * with a log line and no reply: like every write in this family it has no error channel, and the
 * client sees the unchanged `hand.state` it already has.
 *
 * There is deliberately no `pickedUpOn` field. Who picked it up is the socket's own announced
 * window, stamped by the handler exactly as `selection.state`'s `source` is (D7) — a window that
 * could send it could claim to be another.
 */
@Serializable
@SerialName("hand.pickUp")
data class HandPickUpInMessage(val kind: String, val id: Int) : HandInMessage()

/**
 * Let go — the second half of every place, and also the chip's ×, Escape, and a client that has
 * changed its mind. Idempotent: dropping an empty hand is a no-op and sends no frame.
 *
 * [uuid] is the record this client believes it is letting go of. **Send it after a place**, where
 * it is the difference between letting go of your own item and clearing someone else's: a place is
 * two independent round-trips — the window's own mutation, then this — and another window may have
 * picked something up in the gap. With a [uuid] the desk drops only if that record is what it
 * holds; without one it drops whatever is there, which is what the chip's × and Escape mean, and
 * what a client that predates the field sends.
 */
@Serializable
@SerialName("hand.drop")
data class HandDropInMessage(val uuid: String? = null) : HandInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class HandOutMessage : OutMessage()

/**
 * What the desk is holding, or nothing: the connect snapshot and the broadcast on every change.
 * `StateFlow`-backed, so the subscription *is* the snapshot.
 *
 * [item] carries the record's **own summary DTO**, exactly as a busk pad does, so every window
 * draws the ghost from this frame alone with no second fetch.
 */
@Serializable
@SerialName("hand.state")
internal data class HandStateOutMessage(val item: HandState.Held? = null) : HandOutMessage()

// ─── Handler ────────────────────────────────────────────────────────────

suspend fun handleHand(scope: SocketScope, message: HandInMessage) {
    val hand = scope.state.handState
    when (message) {
        is HandPickUpInMessage -> {
            val kind = runCatching { BuskPadKind.valueOf(message.kind) }.getOrNull() ?: run {
                logger.warn("hand.pickUp dropped: '{}' is not a pad kind", message.kind)
                return
            }
            val projectId = runCatching { scope.state.projectManager.currentProject.id.value }.getOrNull() ?: run {
                logger.debug("hand.pickUp dropped: no current project")
                return
            }
            val record = HandService.resolve(
                scope.state, projectId, kind, message.id,
                pickedUpOn = scope.selectionSource(null),
            )
            if (record == null) {
                logger.warn("hand.pickUp dropped: no {} {} in project {}", kind, message.id, projectId)
                return
            }
            if (hand.pickUp(record) == null) {
                logger.debug("hand.pickUp dropped: the desk is shutting down")
            }
        }
        // A bare drop lets go of whatever is held; one naming a record lets go only of that record,
        // so a place cannot clear what another window picked up in the gap behind it.
        is HandDropInMessage -> message.uuid?.let { hand.dropIfRecord(it) } ?: hand.drop()
    }
}

// ─── Subscriptions ──────────────────────────────────────────────────────

/**
 * Registered in the **show band** of [configureSockets], beside `setupSelectionSubscriptions` and
 * unlike `setupWindowsSubscriptions`: the hand is project-scoped, holds one project's record ids,
 * and its pick-up reads the show's database.
 */
fun setupHandSubscriptions(scope: SocketScope) {
    scope.subscribe(scope.state.handState.held) { scope.send(HandStateOutMessage(it)) }
}
