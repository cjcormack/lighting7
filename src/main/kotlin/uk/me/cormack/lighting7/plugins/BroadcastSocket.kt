package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.CueRunState
import uk.me.cormack.lighting7.show.FixturesChangeListener

// ─── Outbound (listener-driven; no inbound) ─────────────────────────────

@Serializable
sealed class BroadcastOutMessage : OutMessage()

@Serializable
@SerialName("presetListChanged")
data object PresetListChangedOutMessage : BroadcastOutMessage()

@Serializable
@SerialName("cueListChanged")
data object CueListChangedOutMessage : BroadcastOutMessage()

@Serializable
@SerialName("cueStackListChanged")
data object CueStackListChangedOutMessage : BroadcastOutMessage()

@Serializable
@SerialName("cueSlotListChanged")
data object CueSlotListChangedOutMessage : BroadcastOutMessage()

@Serializable
@SerialName("patchListChanged")
data object PatchListChangedOutMessage : BroadcastOutMessage()

@Serializable
@SerialName("riggingListChanged")
data object RiggingListChangedOutMessage : BroadcastOutMessage()

@Serializable
@SerialName("stageRegionListChanged")
data object StageRegionListChangedOutMessage : BroadcastOutMessage()

/**
 * Named-palette CRUD only — created, renamed, deleted. Deliberately *not* fired when a
 * palette's contents change: the client treats this as a cache-invalidation signal, so
 * emitting it per resolution would be an invalidation storm. A contents change publishes
 * `provenanceState` instead (see `routes/paletteRepublish.kt`), which is what drives the
 * programmer sheet to re-read resolved ref values.
 */
@Serializable
@SerialName("paletteListChanged")
data object PaletteListChangedOutMessage : BroadcastOutMessage()

/**
 * Speed-master CRUD only — created, renamed, deleted. Live BPM changes stream over the
 * `speedMasters.*` family instead, for the same storm rationale as [PaletteListChangedOutMessage]:
 * this message is a cache-invalidation signal, and a tapped tempo would fire it twice a second.
 */
@Serializable
@SerialName("speedMasterListChanged")
data object SpeedMasterListChangedOutMessage : BroadcastOutMessage()

@Serializable
@SerialName("showChanged")
data class ShowChangedOutMessage(
    val projectId: Int,
    val activeStackId: Int?,
    val activeStackName: String?,
) : BroadcastOutMessage()

/**
 * A cue stack's run state — live cue, armed next, fade progress. One frame per transition, not a
 * per-tick stream: the client animates the fade locally from [fadeElapsedMs] and
 * [fadeDurationMs], the same way the session that pressed GO always has.
 *
 * Mirrors [uk.me.cormack.lighting7.fx.CueRunState]; see its KDoc for the field semantics, in
 * particular why the fade is described as an elapsed duration rather than a start timestamp.
 */
@Serializable
@SerialName("cueRunStateChanged")
data class CueRunStateChangedOutMessage(
    val projectId: Int,
    val stackId: Int,
    val activeCueId: Int?,
    val nextCueId: Int?,
    val nextIsArmed: Boolean,
    val transition: Boolean,
    val fadeDurationMs: Long?,
    val fadeElapsedMs: Long?,
    val autoAdvance: Boolean,
    val autoAdvanceDelayMs: Long?,
) : BroadcastOutMessage() {
    companion object {
        fun of(runState: CueRunState) = CueRunStateChangedOutMessage(
            projectId = runState.projectId,
            stackId = runState.stackId,
            activeCueId = runState.activeCueId,
            nextCueId = runState.nextCueId,
            nextIsArmed = runState.nextIsArmed,
            transition = runState.transition,
            fadeDurationMs = runState.fadeDurationMs,
            fadeElapsedMs = runState.fadeElapsedMs,
            autoAdvance = runState.autoAdvance,
            autoAdvanceDelayMs = runState.autoAdvanceDelayMs,
        )
    }
}

@Serializable
@SerialName("fixturesChanged")
data object FixturesChangedOutMessage : BroadcastOutMessage()

@Serializable
@SerialName("promptBookChanged")
data object PromptBookChangedOutMessage : BroadcastOutMessage()

// ─── Listener wiring ────────────────────────────────────────────────────

/**
 * Registers the [FixturesChangeListener] and the project-change re-registration handler.
 * Returns an `unregister` function the WS teardown should call to detach the listener
 * from whatever [Fixtures] instance is current at disconnect — the project may have
 * switched mid-connection.
 */
fun setupBroadcastSubscriptions(scope: SocketScope): () -> Unit {
    val state = scope.state
    val session = scope.session

    val listener = object : FixturesChangeListener {
        // Bridges the non-suspending callback into the suspending [scope.send]; safe to call
        // from any thread because [DefaultWebSocketServerSession] is its own CoroutineScope.
        private fun fire(message: OutMessage) {
            session.launch { scope.send(message) }
        }

        override fun channelsChanged(universe: Universe, changes: Map<Int, UByte>) {
            if (universe.subnet != 0) return
            fire(ChannelStateOutMessage(changes.map { ChannelState(universe.universe, it.key, it.value) }))
        }

        override fun controllersChanged() {
            fire(UniversesStateOutMessage(buildUniverseList(state)))
        }

        override fun fixturesChanged() {
            fire(FixturesChangedOutMessage)
            fire(buildChannelMappingMessage(state))
        }

        override fun presetListChanged() = fire(PresetListChangedOutMessage)
        override fun cueListChanged() = fire(CueListChangedOutMessage)
        override fun cueStackListChanged() = fire(CueStackListChangedOutMessage)
        override fun cueSlotListChanged() = fire(CueSlotListChangedOutMessage)
        override fun patchListChanged() = fire(PatchListChangedOutMessage)
        override fun riggingListChanged() = fire(RiggingListChangedOutMessage)
        override fun stageRegionListChanged() = fire(StageRegionListChangedOutMessage)
        override fun paletteListChanged() = fire(PaletteListChangedOutMessage)
        override fun speedMasterListChanged() = fire(SpeedMasterListChangedOutMessage)

        override fun showChanged(
            projectId: Int,
            activeStackId: Int?,
            activeStackName: String?,
        ) {
            fire(ShowChangedOutMessage(projectId, activeStackId, activeStackName))
        }

        override fun cueRunStateChanged(runState: CueRunState) {
            fire(CueRunStateChangedOutMessage.of(runState))
        }

        override fun promptBookChanged() = fire(PromptBookChangedOutMessage)
    }

    var currentFixtures = state.show.fixtures
    currentFixtures.registerListener(listener)

    // Initial channel-mapping snapshot so a fresh connection doesn't have to ask.
    session.launch { scope.send(buildChannelMappingMessage(state)) }

    // Run-state snapshot, for the same reason plus one more: a session that opens mid-fade
    // gets a non-null `fadeElapsedMs` and animates the remainder instead of nothing.
    //
    // Captured here, synchronously, and only sent from the launch: a snapshot *read* inside the
    // coroutine would describe whenever the coroutine happened to be scheduled, which can be
    // long after the connect and after a GO the listener has already queued a `transition = true`
    // frame for — a stale-looking `transition = false` frame carrying the newer cue. One
    // transaction for the whole walk, since each `runStateFor` would otherwise open its own.
    val runStateSnapshot = transaction(state.database) {
        val manager = state.show.cueStackManager
        manager.stacksWithRunState().map { manager.runStateFor(state, it) }
    }
    if (runStateSnapshot.isNotEmpty()) {
        session.launch {
            for (runState in runStateSnapshot) scope.send(CueRunStateChangedOutMessage.of(runState))
        }
    }

    // Re-register on project switch — the previous project's [Fixtures] instance is replaced
    // wholesale, so a stale registration would silently stop firing. `.drop(1)` skips the
    // SharedFlow's replay-1 cached event, which would otherwise unregister/re-register the
    // freshly-installed listener at every connect for no reason.
    scope.subscribe(state.projectManager.projectChangedFlow.drop(1)) {
        currentFixtures.unregisterListener(listener)
        currentFixtures = state.show.fixtures
        currentFixtures.registerListener(listener)
    }

    return { currentFixtures.unregisterListener(listener) }
}
