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

private val logger = LoggerFactory.getLogger("StackEffectSizeSocket")

/**
 * The `stackEffectSize.*` family (effects plan D10, §11 question 20): each cue stack's effect
 * size, a runtime weight 0–1 on that stack's effects. One inbound frame, for the *FX size* slider,
 * which moves at frame rate; one outbound, every stack below full, streamed. Its own frame rather
 * than a field of `cueRunStateChanged`, which a fader drag would flood. REST
 * (`routes/projectStackEffectSize.kt`) reads and sets the same value, and a MIDI `stackFxAmount`
 * binding sets it too.
 */

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class StackEffectSizeInMessage : InMessage()

/** Set one of the live project's stacks' effect size, clamped into 0–1. */
@Serializable
@SerialName("stackEffectSize.set")
data class StackEffectSizeSetInMessage(val stackId: Int, val size: Double) : StackEffectSizeInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class StackEffectSizeOutMessage : OutMessage()

/** One stack's effect size. */
@Serializable
data class StackEffectSizeEntry(val stackId: Int, val size: Double)

/**
 * Every stack of the live project whose effect size is below full, by stack id; a stack absent is
 * at 100 %. The subscription is the connect snapshot.
 */
@Serializable
@SerialName("stackEffectSize.state")
data class StackEffectSizeStateOutMessage(val projectId: Int, val stacks: List<StackEffectSizeEntry>) : StackEffectSizeOutMessage()

// ─── Handlers ───────────────────────────────────────────────────────────

internal fun handleStackEffectSize(scope: SocketScope, message: StackEffectSizeInMessage) {
    when (message) {
        is StackEffectSizeSetInMessage -> {
            val manager = scope.state.show.cueStackManager
            // A stack already moved is known to be this project's; only a first move reads the row,
            // so a fader dragged at frame rate costs no database read after its first frame.
            val known = message.stackId in manager.effectSizes.snapshot()
            if (!known && !stackInProject(scope.state, scope.state.projectManager.currentProject, message.stackId)) {
                logger.debug("stackEffectSize.set dropped: no stack {} in the live project", message.stackId)
                return
            }
            manager.setEffectSize(message.stackId, message.size)
        }
    }
}

/**
 * Follows the live show, as `grandMaster.state` does: the sizes live on the show's
 * `CueStackManager`, which a project switch replaces, so they are re-subscribed off
 * `projectChangedFlow`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun setupStackEffectSizeSubscriptions(scope: SocketScope) {
    val state = scope.state
    scope.subscribe(
        state.projectManager.projectChangedFlow
            .map { Unit }
            .onStart { emit(Unit) }
            .flatMapLatest {
                val show = state.show
                val projectId = show.project.id.value
                show.cueStackManager.effectSizes.state.map { sizes ->
                    StackEffectSizeStateOutMessage(
                        projectId,
                        sizes.entries.sortedBy { it.key }.map { StackEffectSizeEntry(it.key, it.value) },
                    )
                }
            }
            .distinctUntilChanged(),
    ) { scope.send(it) }
}
