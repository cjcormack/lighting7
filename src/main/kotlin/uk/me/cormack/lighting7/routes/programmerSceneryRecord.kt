package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueScenery
import uk.me.cormack.lighting7.models.DaoCueSceneryRow
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookScenery
import uk.me.cormack.lighting7.models.DaoLookSceneryRow
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoStageElement
import uk.me.cormack.lighting7.models.DaoStageElements
import uk.me.cormack.lighting7.models.ElementStates
import uk.me.cormack.lighting7.models.decodeSceneryState
import uk.me.cormack.lighting7.models.encodeSceneryState
import uk.me.cormack.lighting7.models.stackSceneryOf
import uk.me.cormack.lighting7.show.SceneryResolver
import uk.me.cormack.lighting7.state.ProgrammerScenery
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.state.toSceneryElement
import java.time.Duration
import java.util.UUID
import kotlin.math.abs

/*
 * **Record, Include and Update for the programmer's scenery** (scenery-programmer plan session 3,
 * D7 and D8) — the overlay of `state/ProgrammerScenery.kt` written into a cue's or a Look's scenery
 * rows, and read back out of them.
 *
 * Scenery is addressed by element, never through the desk selection (D3), so none of a Record's
 * mask, source or fixture scope governs it: what is held is what is recorded, whole.
 *
 * - **Into a cue** a held state is written only where it says something the cue would not show
 *   anyway (D7): an element the cue has no row for is written when its held state differs from what
 *   the cue tracks — the element's base, the stack's set and every earlier cue of the stack
 *   ([sceneryBeneathCue], the cue card's own "tracked") — and skipped when it equals it. A row the cue
 *   **already has** is replaced, whatever it says: it is the cue's own assertion, and a Record over it
 *   means "this is what the cue moves the piece to now". A held cue row's clock comes back with it
 *   (Include keeps it on the overlay entry); a piece held from the band keeps the row's own clock.
 * - **Into a Look** every held state is written, because a Look asserts rather than tracks.
 * - REMOVE deletes the owner's rows for the held elements; UPDATE_EXISTING is MERGE (the attribute
 *   mask that narrows a lighting Replace has nothing to say about scenery).
 */

/** The programmer's held scenery, by element, when it belongs to [projectId]; empty otherwise. */
internal fun heldSceneryOf(state: State, projectId: Int): Map<UUID, ProgrammerScenery.Held> {
    val snapshot = state.programmerScenery.flow.value
    return if (snapshot.projectId == projectId) snapshot.elements else emptyMap()
}

/**
 * What a scenery write did to one owner: rows written (a row already saying exactly the held state
 * is left alone and not counted), rows removed, and held states the cue already tracked.
 */
data class SceneryRecordOutcome(
    val written: Int = 0,
    val removed: Int = 0,
    val alreadyTracked: Int = 0,
    val warnings: List<String> = emptyList(),
)

/**
 * Write [held] into [cue]'s scenery under [mode] — see the file's notes for the rules. A MARKER is
 * never live, so it records none and says so. Must run inside a transaction.
 */
internal fun writeHeldSceneryIntoCue(
    cue: DaoCue,
    held: Map<UUID, ProgrammerScenery.Held>,
    mode: RecordMode,
): SceneryRecordOutcome {
    if (held.isEmpty()) return SceneryRecordOutcome()
    if (cue.cueType == CueType.MARKER.name) {
        return SceneryRecordOutcome(warnings = listOf("A marker is never live, so it records no scenery — ${held.size} held piece(s) left out"))
    }
    val elements = elementsOf(cue.project)
    val own = DaoCueSceneryRow.find { DaoCueScenery.cue eq cue.id }.associateBy { it.element.id.value }
    val beneath by lazy { sceneryBeneathCue(cue, elements.values.map { it.second }) }
    var nextSort = (own.values.maxOfOrNull { it.sortOrder } ?: -1) + 1
    var written = 0
    var removed = 0
    var tracked = 0

    for ((uuid, h) in held) {
        val (dao, element) = elements[uuid] ?: continue
        val existing = own[element.id]
        if (mode == RecordMode.REMOVE) {
            if (existing != null) {
                existing.delete()
                removed++
            }
            continue
        }
        val states = SceneryResolver.only(h.state, element.keys)
        if (SceneryResolver.keysOf(states).isEmpty()) continue
        if (existing == null && sameAs(states, beneath[element.id])) {
            tracked++
            continue
        }
        val text = encodeSceneryState(states)
        val transition = h.transitionMs?.let(Duration::ofMillis) ?: existing?.transition
        // A row that already says exactly this — Include's own, written back — is left untouched.
        if (existing != null && existing.stateJson == text && existing.transition == transition) continue
        val row = existing ?: DaoCueSceneryRow.new {
            this.cue = cue
            this.element = dao
            this.stateJson = ""
            this.sortOrder = nextSort++
        }
        row.stateJson = text
        row.transition = transition
        written++
    }
    return SceneryRecordOutcome(written, removed, tracked)
}

/**
 * Write [held] into [look]'s scenery under [mode]: every held state, since a Look asserts rather
 * than tracks; REMOVE deletes the Look's rows for the held elements. Must run inside a transaction.
 */
internal fun writeHeldSceneryIntoLook(
    look: DaoLook,
    held: Map<UUID, ProgrammerScenery.Held>,
    mode: RecordMode,
): SceneryRecordOutcome {
    if (held.isEmpty()) return SceneryRecordOutcome()
    val elements = elementsOf(look.project)
    val own = DaoLookSceneryRow.find { DaoLookScenery.look eq look.id }.associateBy { it.element.id.value }
    var nextSort = (own.values.maxOfOrNull { it.sortOrder } ?: -1) + 1
    var written = 0
    var removed = 0

    for ((uuid, h) in held) {
        val (dao, element) = elements[uuid] ?: continue
        val existing = own[element.id]
        if (mode == RecordMode.REMOVE) {
            if (existing != null) {
                existing.delete()
                removed++
            }
            continue
        }
        val states = SceneryResolver.only(h.state, element.keys)
        if (SceneryResolver.keysOf(states).isEmpty()) continue
        val text = encodeSceneryState(states)
        if (existing != null && existing.stateJson == text) continue
        val row = existing ?: DaoLookSceneryRow.new {
            this.look = look
            this.element = dao
            this.stateJson = ""
            this.sortOrder = nextSort++
        }
        row.stateJson = text
        written++
    }
    return SceneryRecordOutcome(written, removed)
}

/**
 * A cue's or a Look's own scenery rows as Include loads them into the overlay (D8): the states each
 * row sets, narrowed to what its element still takes, and a cue row's clock. Never the tracked state.
 * Must run inside a transaction.
 */
internal fun cueSceneryForInclude(cue: DaoCue): List<ProgrammerScenery.Included> =
    DaoCueSceneryRow.find { DaoCueScenery.cue eq cue.id }
        .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
        .mapNotNull { row ->
            included(row.element, decodeSceneryState(row.stateJson), row.transition?.toMillis())
        }

/** See [cueSceneryForInclude]. Must run inside a transaction. */
internal fun lookSceneryForInclude(look: DaoLook): List<ProgrammerScenery.Included> =
    DaoLookSceneryRow.find { DaoLookScenery.look eq look.id }
        .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
        .mapNotNull { row -> included(row.element, decodeSceneryState(row.stateJson), null) }

private fun included(element: DaoStageElement, state: ElementStates, transitionMs: Long?): ProgrammerScenery.Included? {
    val states = SceneryResolver.only(state, element.toSceneryElement().keys)
    if (SceneryResolver.keysOf(states).isEmpty()) return null
    return ProgrammerScenery.Included(element.uuid, states, transitionMs)
}

/**
 * What [cue] shows of each element were its own scenery rows gone — the element's base, then the
 * stack's set, then every earlier STANDARD cue of its stack, the last one winning: the state a held
 * piece is compared against before Record writes a row for it (D7). Every element of [elements] has
 * an entry. Must run inside a transaction.
 */
internal fun sceneryBeneathCue(cue: DaoCue, elements: Collection<SceneryResolver.Element>): Map<Int, ElementStates> {
    val stack = cue.cueStack
    val set = stackSceneryOf(stack.id).map { SceneryResolver.Change(it.element.id.value, decodeSceneryState(it.stateJson)) }
    val list = DaoCue.find { DaoCues.cueStack eq stack.id }.sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
    val at = list.indexOfFirst { it.id == cue.id }
    val earlier = (if (at < 0) list else list.take(at)).filter { it.cueType == CueType.STANDARD.name }
    val rows = if (earlier.isEmpty()) emptyMap() else {
        DaoCueSceneryRow.find { DaoCueScenery.cue inList earlier.map { it.id } }
            .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
            .groupBy { it.cue.id.value }
    }
    val cues = earlier.map { c ->
        SceneryResolver.Cue(
            c.id.value,
            c.cueNumber?.takeIf { it.isNotBlank() } ?: c.name,
            rows[c.id.value].orEmpty().map { SceneryResolver.CueChange(it.element.id.value, decodeSceneryState(it.stateJson), 0) },
        )
    }
    return SceneryResolver.resolve(
        elements,
        listOf(SceneryResolver.LiveStack(stackId = stack.id.value, goSeq = 0, set = set, cues = cues, layeredLookIds = emptyList())),
        emptyMap(),
        emptyList(),
    ).mapValues { it.value.state }
}

/** Whether every state [held] sets is what [shown] already has — the "differs from tracked" test. */
private fun sameAs(held: ElementStates, shown: ElementStates?): Boolean {
    if (shown == null) return false
    fun close(a: Double?, b: Double?) = a == null || (b != null && abs(a - b) < 1e-6)
    return (held.visible == null || held.visible == shown.visible) && close(held.open, shown.open) && close(held.trimM, shown.trimM)
}

/** [project]'s scene elements by uuid, as a scenery write needs them: the row, and the resolver's view of it. */
private fun elementsOf(project: DaoProject): Map<UUID, Pair<DaoStageElement, SceneryResolver.Element>> =
    DaoStageElement.find { DaoStageElements.project eq project.id }
        .associate { it.uuid to (it to it.toSceneryElement()) }
