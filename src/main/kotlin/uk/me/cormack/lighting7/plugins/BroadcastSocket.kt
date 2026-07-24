package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.dmx.Universe
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

@Serializable
@SerialName("showChanged")
data class ShowChangedOutMessage(
    val projectId: Int,
    val activeStackId: Int?,
    val activeStackName: String?,
) : BroadcastOutMessage()

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

        override fun showChanged(
            projectId: Int,
            activeStackId: Int?,
            activeStackName: String?,
        ) {
            fire(ShowChangedOutMessage(projectId, activeStackId, activeStackName))
        }

        override fun promptBookChanged() = fire(PromptBookChangedOutMessage)
    }

    var currentFixtures = state.show.fixtures
    currentFixtures.registerListener(listener)

    // Initial channel-mapping snapshot so a fresh connection doesn't have to ask.
    session.launch { scope.send(buildChannelMappingMessage(state)) }

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
