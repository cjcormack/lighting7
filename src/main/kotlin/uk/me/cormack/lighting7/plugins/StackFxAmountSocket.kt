package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.routes.stackInProject

private val logger = LoggerFactory.getLogger("StackFxAmountSocket")

/**
 * The `stackFxAmount.*` family (effects plan D10, §11 question 20): each cue stack's effect
 * size, a runtime weight 0–1 on that stack's effects. One inbound frame, for the *FX amount* slider,
 * which moves at frame rate; one outbound, every stack below full, streamed. Its own frame rather
 * than a field of `cueRunStateChanged`, which a fader drag would flood. REST
 * (`routes/projectStackFxAmount.kt`) reads and sets the same value, and a MIDI `stackFxAmount`
 * binding sets it too.
 */

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class StackFxAmountInMessage : InMessage()

/** Set one of the live project's stacks' FX amount, clamped into 0–1. */
@Serializable
@SerialName("stackFxAmount.set")
data class StackFxAmountSetInMessage(val stackId: Int, val amount: Double) : StackFxAmountInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class StackFxAmountOutMessage : OutMessage()

/** One stack's FX amount. */
@Serializable
data class StackFxAmountEntry(val stackId: Int, val amount: Double)

/**
 * Every stack of the live project whose FX amount is below full, by stack id; a stack absent is
 * at 100 %. The subscription is the connect snapshot.
 */
@Serializable
@SerialName("stackFxAmount.state")
data class StackFxAmountStateOutMessage(val projectId: Int, val stacks: List<StackFxAmountEntry>) : StackFxAmountOutMessage()

// ─── Handlers ───────────────────────────────────────────────────────────

internal fun handleStackFxAmount(scope: SocketScope, message: StackFxAmountInMessage) {
    when (message) {
        is StackFxAmountSetInMessage -> {
            val manager = scope.state.show.cueStackManager
            // A stack already moved is known to be this project's; only a first move reads the row,
            // so a fader dragged at frame rate costs no database read after its first frame.
            val known = message.stackId in manager.fxAmounts.snapshot()
            if (!known && !stackInProject(scope.state, scope.state.projectManager.currentProject, message.stackId)) {
                logger.debug("stackFxAmount.set dropped: no stack {} in the live project", message.stackId)
                return
            }
            manager.setFxAmount(message.stackId, message.amount)
        }
    }
}

/**
 * Follows the live show, as `grandMaster.state` does: the sizes live on the show's
 * `CueStackManager`, which a project switch replaces, so they are re-subscribed off
 * `projectChangedFlow`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun setupStackFxAmountSubscriptions(scope: SocketScope) {
    val state = scope.state
    scope.subscribe(
        state.projectManager.projectChangedFlow
            .map { Unit }
            .onStart { emit(Unit) }
            .flatMapLatest {
                val show = state.show
                val projectId = show.project.id.value
                show.cueStackManager.fxAmounts.state.map { sizes ->
                    StackFxAmountStateOutMessage(
                        projectId,
                        sizes.entries.sortedBy { it.key }.map { StackFxAmountEntry(it.key, it.value) },
                    )
                }
            }
            .distinctUntilChanged(),
    ) { scope.send(it) }
}
