package uk.me.cormack.lighting7.plugins

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

/**
 * Look CRUD only — created, renamed, deleted. Deliberately *not* fired when a Look's contents
 * change: the client treats this as a cache-invalidation signal, so emitting it per resolution
 * would be an invalidation storm. A contents change publishes `provenanceState` instead (see
 * `routes/lookRepublish.kt`), which is what drives the programmer sheet to re-read resolved values.
 *
 * Replaces the two messages this collapses — `presetListChanged` and `paletteListChanged` — now
 * that FX presets and the old named palette banks are one entity, the Look.
 */
@Serializable
@SerialName("lookListChanged")
data object LookListChangedOutMessage : BroadcastOutMessage()

/**
 * A template was created, renamed or deleted.
 *
 * Its own message rather than a reuse of [LookListChangedOutMessage], for the same reason the
 * backend signals are separate: the two invalidate different client caches, and one message would
 * make every Look edit re-read the template library and vice versa. Contents changes ride the
 * provenance / layer-state frames the edit itself produces.
 */
@Serializable
@SerialName("templateListChanged")
data object TemplateListChangedOutMessage : BroadcastOutMessage()

/**
 * A Look or template contents edit changed what [cueIds] compose to.
 *
 * The contents counterpart to the two list signals above, which is why it carries ids where they
 * carry nothing: those refuse to fire on a contents edit because they invalidate *every* cached
 * expansion, and a retune only moves a handful of cues. Naming them lets a client re-read exactly
 * those, so the frame is affordable at save cadence.
 *
 * [cueIds] is every cue layering the edited record, not only the live ones whose Layer 4 rows were
 * replaced: `GET /cues/{id}/cooked` composes on read, so a dark cue reads stale from the same edit.
 */
@Serializable
@SerialName("cuesRecomposed")
data class CuesRecomposedOutMessage(val cueIds: List<Int>) : BroadcastOutMessage()

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
 * Speed-master CRUD only — created, renamed, deleted. Live BPM changes stream over the
 * `speedMasters.*` family instead, for the same storm rationale as [LookListChangedOutMessage]:
 * this message is a cache-invalidation signal, and a tapped tempo would fire it twice a second.
 *
 * Named into the `speedMasters.*` wire namespace even though it is fired from this file's
 * [FixturesChangeListener] like its `*ListChanged` neighbours: the namespace describes the client
 * cache the frame invalidates, not which server component emits it.
 */
@Serializable
@SerialName("speedMasters.listChanged")
data object SpeedMasterListChangedOutMessage : BroadcastOutMessage()

/** A script was created, renamed, edited or deleted. */
@Serializable
@SerialName("scriptListChanged")
data object ScriptListChangedOutMessage : BroadcastOutMessage()

/** An FX definition (user-created effect) was created, edited or deleted. */
@Serializable
@SerialName("fxDefinitionListChanged")
data object FxDefinitionListChangedOutMessage : BroadcastOutMessage()

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

        override fun lookListChanged() = fire(LookListChangedOutMessage)
        override fun templateListChanged() = fire(TemplateListChangedOutMessage)
        override fun cuesRecomposed(cueIds: List<Int>) = fire(CuesRecomposedOutMessage(cueIds))
        override fun cueListChanged() = fire(CueListChangedOutMessage)
        override fun cueStackListChanged() = fire(CueStackListChangedOutMessage)
        override fun cueSlotListChanged() = fire(CueSlotListChangedOutMessage)
        override fun patchListChanged() = fire(PatchListChangedOutMessage)
        override fun riggingListChanged() = fire(RiggingListChangedOutMessage)
        override fun stageRegionListChanged() = fire(StageRegionListChangedOutMessage)
        override fun speedMasterListChanged() = fire(SpeedMasterListChangedOutMessage)
        override fun scriptListChanged() = fire(ScriptListChangedOutMessage)
        override fun fxDefinitionListChanged() = fire(FxDefinitionListChangedOutMessage)

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

    // Connect snapshots for the three channel-family states this file broadcasts changes for
    // (see docs/websocket-engineering.md §"Snapshot rule"). One snapshot job for all three
    // rather than three: `scope.send` only throws on a serialization bug, which fails the whole
    // session scope anyway, so splitting them buys no isolation. Clients must not read anything
    // into the order — the families are set up in separate coroutines and the burst as a whole
    // has none. The matching request messages stay as explicit resync.
    scope.sendSnapshot {
        send(buildChannelStateMessage(state))
        send(UniversesStateOutMessage(buildUniverseList(state)))
        send(buildChannelMappingMessage(state))
    }

    // Run-state snapshot, for the same reason plus one more: a session that opens mid-fade
    // gets a non-null `fadeElapsedMs` and animates the remainder instead of nothing.
    //
    // Captured here, synchronously, and only sent from the launch: a snapshot *read* inside the
    // coroutine would describe whenever the coroutine happened to be scheduled, which can be
    // long after the connect and after a GO the listener has already queued a `transition = true`
    // frame for — a stale-looking `transition = false` frame carrying the newer cue. One
    // transaction for the whole walk, since each `runStateFor` would otherwise open its own.
    val runStateSnapshot = transaction(state.database) {
        val runState = state.show.cueStackManager.runState
        runState.stacksWithRunState().map { runState.runStateFor(state, it) }
    }
    if (runStateSnapshot.isNotEmpty()) {
        scope.sendSnapshot {
            for (runState in runStateSnapshot) send(CueRunStateChangedOutMessage.of(runState))
        }
    }

    // Re-register on project switch — the previous project's [Fixtures] instance is replaced
    // wholesale, so a stale registration would silently stop firing. No `drop(1)` here: it used
    // to skip `projectChangedFlow`'s replay-1 cached event, but that flow is replay-0 now (the
    // connect snapshot is `projectState`, not a replayed change event), and a `drop(1)` against
    // an empty replay cache swallows the *first real* switch instead — leaving the connection
    // listening to the outgoing project's [Fixtures] for the rest of its life.
    scope.subscribe(state.projectManager.projectChangedFlow) {
        currentFixtures.unregisterListener(listener)
        currentFixtures = state.show.fixtures
        currentFixtures.registerListener(listener)
    }

    return { currentFixtures.unregisterListener(listener) }
}
