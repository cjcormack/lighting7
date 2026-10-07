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
    /**
     * One element the programmer holds: the states it sets, the fade it was moved on, and — when
     * Include loaded it from a cue — that cue row's own [transitionMs], kept so Update writes the
     * row back on the clock it had (scenery-programmer plan D8). A later move keeps it.
     */
    data class Held(val state: ElementStates, val fadeMs: Long?, val transitionMs: Long? = null)

    /**
     * The whole overlay: the project it belongs to, and each held element in the order first held.
     * [baseline] is what the last Include (or a Record or Update that wrote the overlay back) left
     * the source holding, by element — null until one has — so a client can tell "the tabs moved
     * since Include" from "the moon is what Include loaded" ([changedSinceInclude]).
     */
    data class Snapshot(
        val projectId: Int?,
        val elements: Map<UUID, Held>,
        val baseline: Map<UUID, ElementStates>? = null,
    ) {
        /**
         * How many held elements Update would write that the source does not already say: each held
         * state that differs from [baseline]'s, or that [baseline] does not name. Null with no
         * baseline. A released piece is not counted — Update writes what is held, never a release.
         */
        val changedSinceInclude: Int?
            get() = baseline?.let { base -> elements.count { (uuid, held) -> base[uuid] != held.state } }
    }

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
            val transitionMs = current.elements[elementUuid]?.transitionMs
            _flow.value = current.copy(elements = LinkedHashMap(current.elements).apply { put(elementUuid, Held(merged, fadeMs, transitionMs)) })
        }
        return emptyList()
    }

    /** One element as Include loads it: the source's own states for it, and a cue row's clock. */
    data class Included(val elementUuid: UUID, val state: ElementStates, val transitionMs: Long?)

    /**
     * Include (scenery-programmer plan D8): hold the source's own scenery rows — each replacing what
     * the programmer held on that element, never merged into it — moved on [fadeMs]. Elements the
     * source does not name keep what they hold. [projectId] guards an Include that read the rows of a
     * project this overlay has left. Answers how many elements were loaded.
     *
     * The [Snapshot.baseline] is not touched here: it belongs to the include target, which an Include
     * that stages nothing does not move — the caller takes it with [includedBaseline] once it knows.
     */
    fun include(projectId: Int, rows: List<Included>, fadeMs: Long?): Int = synchronized(lock) {
        val current = _flow.value
        if (current.projectId != projectId) return 0
        val fade = fadeMs?.takeIf { it > 0 }?.coerceAtMost(MAX_SCENERY_TRANSITION.toMillis())
        val elements = LinkedHashMap(current.elements)
        for (row in rows) {
            releases.remove(row.elementUuid)
            elements[row.elementUuid] = Held(row.state, fade, row.transitionMs)
        }
        _flow.value = current.copy(elements = elements)
        rows.size
    }

    /**
     * The include target has moved to the source whose own [rows] Include just loaded: they are what
     * Update compares the held pieces against from now on.
     */
    fun includedBaseline(projectId: Int, rows: List<Included>): Unit = synchronized(lock) {
        val current = _flow.value
        if (current.projectId != projectId) return
        _flow.value = current.copy(baseline = rows.associate { it.elementUuid to it.state })
    }

    /**
     * The source now says what the programmer holds — a Record or an Update wrote the overlay into
     * it — so nothing held is "changed since Include". [written] false (a Record told to leave the
     * scenery out) leaves the source saying none of it.
     */
    fun rebaseline(projectId: Int, written: Boolean): Unit = synchronized(lock) {
        val current = _flow.value
        if (current.projectId != projectId) return
        _flow.value = current.copy(
            baseline = if (written) current.elements.mapValues { it.value.state } else emptyMap(),
        )
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
