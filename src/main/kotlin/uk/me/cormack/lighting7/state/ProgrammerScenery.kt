package uk.me.cormack.lighting7.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import uk.me.cormack.lighting7.models.ElementStates
import uk.me.cormack.lighting7.models.MAX_SCENERY_TRANSITION
import uk.me.cormack.lighting7.models.SceneryElementInfo
import uk.me.cormack.lighting7.models.parseSceneryState
import java.util.UUID

/**
 * The programmer's own scenery (scenery-programmer plan D1): a sparse overlay of element states
 * the operator's hands hold — the moon flown in *now*, the tabs half drawn — one per desk like the
 * programmer, and runtime only. [SceneryService] resolves it as the top tier, above the
 * programmer's Looks (D2); while the programmer is blind it is staged rather than drawn (D12).
 *
 * **Project-scoped like [SceneryService]**: [attach]ed to the current project at start and again by
 * State's project collector, which drops everything held — an element uuid belongs to one project's
 * scene. Clear (`clearProgrammerCompletely`) releases it all on the Clear fade.
 *
 * **A write is checked against its element's kind** with [parseSceneryState] — `open` only on a
 * drawn drape, `trimM` only on a flown piece — and merges into what the element already holds, so
 * a slider riding `open` keeps an earlier `visible`. The fade travels with the latest write.
 *
 * Written by `programmer.setScenery` / `programmer.clearScenery` and the AI's `move_scenery`;
 * streamed as `programmer.sceneryState`, backed by [flow] so the subscription is the snapshot.
 * Nothing here is stored, synced or output: scenery is drawn, never a channel.
 */
class ProgrammerScenery(
    /** The project's scene elements by uuid, as a scenery write checks them. Opens its own transaction. */
    private val elementsOf: (projectId: Int) -> Map<UUID, SceneryElementInfo>,
) {
    /** One element the programmer holds: the states it sets, and the fade it was moved on. */
    data class Held(val state: ElementStates, val fadeMs: Long?)

    /** The whole overlay: the project it belongs to, and each held element in the order first held. */
    data class Snapshot(val projectId: Int?, val elements: Map<UUID, Held>)

    private val lock = Any()
    private val _flow = MutableStateFlow(Snapshot(null, emptyMap()))
    val flow: StateFlow<Snapshot> = _flow.asStateFlow()

    /**
     * Elements released on a fade, by uuid, that no recompute has read yet — the clock a piece
     * flies back on when the programmer lets it go ([SceneryService.durationFor]). Taken by
     * [readForResolve].
     */
    private val releases = HashMap<UUID, Long>()

    /** The current project changed (or the desk started): everything held belonged to the old one. */
    fun attach(projectId: Int?) {
        synchronized(lock) {
            releases.clear()
            _flow.value = Snapshot(projectId, emptyMap())
        }
    }

    /**
     * Hold [state] on [elementUuid], merged over what it already holds, moved on [fadeMs]. Answers
     * the problems that refused it, every one at once; an empty list means it was written.
     */
    fun set(elementUuid: UUID, state: JsonObject, fadeMs: Long?): List<String> {
        val projectId = _flow.value.projectId ?: return listOf("No project is loaded")
        val problems = mutableListOf<String>()
        if (fadeMs != null && (fadeMs < 0 || fadeMs > MAX_SCENERY_TRANSITION.toMillis())) {
            problems += "fadeMs must be between 0 and ${MAX_SCENERY_TRANSITION.toMillis()}"
        }
        val element = elementsOf(projectId)[elementUuid]
        if (element == null) problems += "elementUuid names no stage element in this project"
        val label = element?.let { "state ('${it.name}')" } ?: "state"
        val parsed = parseSceneryState(state, element, label, problems)
        if (problems.isNotEmpty() || parsed == null) return problems
        synchronized(lock) {
            val current = _flow.value
            // A switch landed between the read above and here: the element is the old project's.
            if (current.projectId != projectId) return listOf("The project changed")
            val was = current.elements[elementUuid]?.state
            val merged = ElementStates(
                visible = parsed.visible ?: was?.visible,
                open = parsed.open ?: was?.open,
                trimM = parsed.trimM ?: was?.trimM,
            )
            releases.remove(elementUuid)
            _flow.value = current.copy(elements = LinkedHashMap(current.elements).apply { put(elementUuid, Held(merged, fadeMs)) })
        }
        return emptyList()
    }

    /**
     * Let go of [elementUuid], or of everything when it is null, so the piece returns to what the
     * show below holds — on [fadeMs] when above 0, else on its own travel. Answers how many
     * elements were released.
     */
    fun release(elementUuid: UUID?, fadeMs: Long? = null): Int = synchronized(lock) {
        val current = _flow.value
        val gone = if (elementUuid == null) current.elements.keys.toList() else listOfNotNull(elementUuid.takeIf { it in current.elements })
        if (gone.isEmpty()) return 0
        val fade = fadeMs?.takeIf { it > 0 }?.coerceAtMost(MAX_SCENERY_TRANSITION.toMillis())
        for (uuid in gone) if (fade != null) releases[uuid] = fade else releases.remove(uuid)
        _flow.value = current.copy(elements = current.elements.filterKeys { it !in gone })
        gone.size
    }

    /** Clear: everything held let go on the Clear's own fade (D1). */
    fun clear(fadeMs: Long = 0): Int = release(null, fadeMs)

    /**
     * Elements the scene no longer has — deleted, or the project's elements reloaded — dropped
     * without a clock, since there is nothing left to draw. [projectId] guards a recompute that
     * read the scene of a project this overlay has already left.
     */
    fun forget(projectId: Int, uuids: Collection<UUID>) {
        if (uuids.isEmpty()) return
        synchronized(lock) {
            val current = _flow.value
            if (current.projectId != projectId || uuids.none { it in current.elements }) return
            uuids.forEach { releases.remove(it) }
            _flow.value = current.copy(elements = current.elements.filterKeys { it !in uuids })
        }
    }

    /**
     * The overlay as a resolve reads it, and — when [takeReleases] — the release fades no resolve
     * has read yet, taken in the same step so a release and the move it starts cannot part. A
     * preview reads without taking: it moves nothing.
     */
    fun readForResolve(takeReleases: Boolean): Pair<Snapshot, Map<UUID, Long>> = synchronized(lock) {
        val out = if (releases.isEmpty()) emptyMap() else HashMap(releases)
        if (takeReleases) releases.clear()
        _flow.value to out
    }
}
