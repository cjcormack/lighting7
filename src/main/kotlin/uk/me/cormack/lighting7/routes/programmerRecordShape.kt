package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.fx.paletteRefValue

/** The result of collapsing a recording into cue assignment rows. */
data class CollapsedAssignments(
    val rows: List<CuePropertyAssignmentDto>,
    /** How many of [rows] came out group-shaped — reported so the operator can see it happened. */
    val groupRows: Int,
    val skipped: List<RecordSkip>,
)

/**
 * Turn fixture-level programmer entries into cue property assignments, re-emitting a
 * group-shaped row wherever that is *provably* equivalent to the per-member rows.
 *
 * ## Why the group shape is earned rather than asserted
 *
 * [RecordEntry.sourceGroup] is a hint, not a fact. It reflects the winning slot only, and
 * several writers can't supply it at all. If Record trusted it, a stale hint would write a
 * group row asserting a value for members the operator never set — silently changing fixtures
 * that weren't part of the look.
 *
 * So a hint only nominates a candidate. The row is emitted group-shaped iff **every** member
 * of the named group holds an entry for that property **and** they all carry the same value.
 * A missing or stale hint therefore degrades to per-fixture rows: more verbose, never wrong.
 *
 * This is the same uniformity test [captureLayer3AssignmentsFromSnapshot] applies to the stage
 * snapshot, so both record paths preserve operator-authored group shape identically.
 *
 * Pure: no DB, no engine, no [uk.me.cormack.lighting7.state.State] — the fixture patch is the
 * only input beyond the entries, which is what makes the rules above directly testable.
 */
internal fun collapseRecordingToAssignments(
    entries: List<RecordEntry>,
    fixtures: Fixtures,
    /**
     * Whether a recorded row may store `ref:{uuid}` when the programmer slot was a reference.
     *
     * True for cues — that is what keeps "busk a palette, Record a cue" attached to the palette.
     * **False for palettes**, which hold literals only: an entry holding a reference would make
     * resolution recursive, and it is rejected at the entry write boundary anyway. Recording a
     * palette from a selection that is itself referencing one therefore captures what is on stage
     * now, which is the sensible reading of the gesture; the route reports how many refs that
     * flattened.
     */
    preserveRefs: Boolean = true,
): CollapsedAssignments {
    if (entries.isEmpty()) return CollapsedAssignments(emptyList(), 0, emptyList())

    val byKey = entries.associateBy { it.fixtureKey to it.propertyName }
    val emitted = ArrayList<CuePropertyAssignmentDto>(entries.size)
    val covered = HashSet<Pair<String, String>>()
    var groupRows = 0

    // Sorted so the output is deterministic. The snapshot path inherits an order from the DB
    // rows it reads; a store walk has none, and a recording whose row order changed between
    // two identical Records would make cue diffs unreadable.
    val hints = entries
        .mapNotNull { entry -> entry.sourceGroup?.let { it to entry.propertyName } }
        .distinct()
        .sortedWith(compareBy({ it.first }, { it.second }))

    for ((groupKey, propertyName) in hints) {
        val members = try {
            fixtures.untypedGroup(groupKey).fixtures.filterIsInstance<Fixture>()
        } catch (_: IllegalStateException) {
            continue
        }
        if (members.isEmpty()) continue

        // Overlapping groups: if an earlier hint already spoke for these members, a second
        // group row would assert the same values twice. Harmless at compose time (the
        // specificity rule sorts it out) but it makes the cue card read as if two different
        // group decisions were made.
        if (members.all { (it.key to propertyName) in covered }) continue

        val first = byKey[members.first().key to propertyName] ?: continue
        // Compare palette identity as well as value. Two members resolving to the same colour
        // from *different* palettes are not uniform: collapsing them would emit one group row
        // referencing one of the two palettes, silently rebinding half the members.
        val uniform = members.all { member ->
            val entry = byKey[member.key to propertyName]
            entry?.value == first.value && entry?.paletteUuid == first.paletteUuid
        }
        if (!uniform) continue

        emitted.add(
            CuePropertyAssignmentDto(
                targetType = TargetRef.Group.TYPE,
                targetKey = groupKey,
                propertyName = propertyName,
                value = first.recordedValue(preserveRefs),
                sortOrder = emitted.size,
            )
        )
        groupRows++
        for (member in members) covered.add(member.key to propertyName)
    }

    for (entry in entries.sortedWith(compareBy({ it.fixtureKey }, { it.propertyName }))) {
        if ((entry.fixtureKey to entry.propertyName) in covered) continue
        emitted.add(
            CuePropertyAssignmentDto(
                targetType = TargetRef.Fixture.TYPE,
                targetKey = entry.fixtureKey,
                propertyName = entry.propertyName,
                value = entry.recordedValue(preserveRefs),
                sortOrder = emitted.size,
            )
        )
    }

    return CollapsedAssignments(emitted, groupRows, emptyList())
}

/**
 * The string a recorded row stores: the palette reference when the entry came from one, else the
 * literal.
 *
 * This is what keeps "busk a palette, Record a cue" attached to the palette. Serialising the
 * resolved literal instead would store today's value and leave the cue permanently detached — every
 * later palette edit would skip it, because `activeCuesReferencingPalette` matches on the ref
 * string.
 */
private fun RecordEntry.recordedValue(preserveRefs: Boolean): String =
    paletteUuid?.takeIf { preserveRefs }?.let { paletteRefValue(it) } ?: value.serialize()
