package uk.me.cormack.lighting7.routes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.PropertyChannelWriter
import uk.me.cormack.lighting7.fx.CueApplyData
import uk.me.cormack.lighting7.fx.buildCueApplyData
import uk.me.cormack.lighting7.fx.buildCombinedCueLayerRows
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.sceneryStateObject
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.fx.cueDerivedPriority
import uk.me.cormack.lighting7.models.DaoCueStackEntry

/**
 * "What would this cue look like?" — composes a cue's Layer 4 contribution against what is
 * already live and returns the resulting DMX channel values, without publishing anything.
 *
 * Exists for the Next GO stage view (lighting-react `src/hooks/useNextGoPreview.ts`): previewing
 * a cue in the browser would otherwise mean reimplementing specificity, HTP/LTP, template
 * resolution and move-in-dark arming client-side. Every step here is the same code an actual
 * apply runs — [buildCombinedCueLayerRows] for the rows, a [CueAssignmentResolver] for the merge,
 * [PropertyChannelWriter] for the patch — so a preview and the GO that follows it cannot
 * disagree.
 *
 * Three deliberate limits, all worth knowing before building a UI on this:
 *
 * 1. **Layer 4 only.** Effects in the cue band have no static value to report, and timed preset
 *    applications don't contribute (matching [buildCombinedCueLayerRows] and a GO's first publish). A cue
 *    whose look is carried by an effect previews as whatever its assignments say, which may be
 *    nothing.
 * 2. **Assertions only.** Channels no cue asserts are absent from the response rather than
 *    reported as 0 — the caller falls back to the live output, the way the "Output + Programmer"
 *    vis source already overlays. Reporting 0 would black out every unaddressed fixture.
 * 3. **No programmer, no park.** This is a preview of playback, not of the stage.
 */
/** One channel of a previewed look. Mirrors the `channelState` WS shape the client already merges. */
@Serializable
data class PreviewChannel(
    val universe: Int,
    val channel: Int,
    val value: UByte,
)

@Serializable
data class PreviewCueResponse(
    val cueId: Int,
    val channels: List<PreviewChannel>,
    /**
     * `fixtureKey.property` for each composed value that couldn't be resolved to channels — a
     * missing fixture, or a property the patched fixture doesn't have. Advisory: the same
     * forgiving contract [buildCueAssignmentsForCue] uses, surfaced so a caller can see that a
     * preview is thinner than the cue.
     */
    val skipped: List<String>,
    /**
     * The scenery the GO would land (stage-view plan session 8): every element a scenery change
     * names, as it would resolve with this cue live — its stack's set and its cues tracked down to
     * it, beside every other live stack and Look — and the move from where it is now, timed as the
     * GO would time it. The Next GO vis source animates it on arrival. Unlike [channels] this is
     * whole, not assertions only: an element absent here shows its base.
     */
    val scenery: List<PreviewSceneryDto> = emptyList(),
)

@Serializable
data class PreviewSceneryDto(
    val elementUuid: String,
    val state: JsonObject,
    val from: JsonObject,
    val durationMs: Long,
)

/**
 * Compose entry [requestedEntryId] of [stackId] (or the stack's effective next) as if it were live.
 *
 * Returns null when there is nothing on deck — end of a non-looping stack with no entry named.
 * Throws [IllegalArgumentException] when a named entry isn't in the stack, or is a marker.
 */
internal fun previewCueLook(state: State, stackId: Int, requestedEntryId: Int?): PreviewCueResponse? {
    val engine = state.show.fxEngine
    val holds = state.show.cueStackManager.holds
    val entryId = requestedEntryId
        ?: state.show.cueStackManager.runState.effectiveNext(state, stackId)?.entryId
        ?: return null

    val applyData = transaction(state.database) {
        // The caller names the stack and the entry (cue model plan P2, P7).
        val entry = DaoCueStackEntry.findById(entryId)?.takeIf { it.stackId == stackId }
            ?: throw IllegalArgumentException("Entry $entryId is not in stack $stackId")
        val cue = entry.cue
            ?: throw IllegalArgumentException("Entry $entryId is a marker, which cannot be previewed")
        // At the priority the GO would give it: this entry's hold beside any the cue already has.
        val placed = buildCueApplyData(cue, entry)
        holds.priorityOf(cue.id.value)?.let { placed.copy(livePriority = maxOf(it, cueDerivedPriority(placed))) } ?: placed
    }
    val cueId = applyData.cueId

    // What the GO leaves alone: every published cue but the ones only this stack holds — a cue
    // another stack or a loose press keeps live stays on stage through the GO (cue model plan D3) —
    // and the previewed cue's own published rows, which its incoming ones stand in for.
    val retained = engine.cueLayer.assignmentsExcludingCues(holds.cuesHeldOnlyBy(stackId) + cueId)

    val incoming = buildCombinedCueLayerRows(state, cueId, applyData, priority = cueDerivedPriority(applyData)).rows

    // A fresh resolver, not `engine.layerResolver`: [CueAssignmentResolver.resolve] is a pure
    // function of its rows, so composing here cannot disturb what is on stage.
    val composed = CueAssignmentResolver().resolve(retained + incoming)

    val fixtures = state.show.fixtures
    // Keyed so two properties backing the same channel resolve the way a transaction would
    // apply them — last write wins — rather than emitting the channel twice.
    val channels = LinkedHashMap<Pair<Int, Int>, PreviewChannel>()
    val skipped = ArrayList<String>()

    for ((key, value) in composed) {
        val fixture = try {
            // Element-aware, like the engine's own cascade publish: a cue that applies an FX
            // preset with element-scoped assignments has rows keyed "parent.element-N", which
            // the plain fixture register does not hold.
            fixtures.untypedGroupableFixture(key.targetKey)
        } catch (_: IllegalStateException) {
            skipped.add("${key.targetKey}.${key.propertyName}")
            continue
        }
        val writes = PropertyChannelWriter.resolve(fixture, key.propertyName, value)
        if (writes.isEmpty()) {
            skipped.add("${key.targetKey}.${key.propertyName}")
            continue
        }
        for (write in writes) {
            // Subnet 0 only, matching the channel stream the client merges this against.
            if (write.universe.subnet != 0) continue
            val id = write.universe.universe to write.channel
            channels[id] = PreviewChannel(write.universe.universe, write.channel, write.value)
        }
    }

    return PreviewCueResponse(
        cueId = cueId,
        channels = channels.values.sortedWith(compareBy({ it.universe }, { it.channel })),
        skipped = skipped.sorted(),
        scenery = state.sceneryService.preview(stackId, entryId, cueId).map {
            PreviewSceneryDto(it.elementUuid.toString(), sceneryStateObject(it.state), sceneryStateObject(it.from), it.durationMs)
        },
    )
}
