package uk.me.cormack.lighting7.routes

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State

private val logger = LoggerFactory.getLogger("programmerLookInclude")

/*
 * **Include a Look into the programmer**, plus the target-expansion helper the Record paths share.
 *
 * This was `programmerPaletteRecord.kt`, whose own doc comment said the filename was "wider than the
 * contents, deliberately for one session" — waiting for the deletion it belonged to rather than
 * being renamed twice. Session 4 is that deletion. What went with the palette tables:
 *
 * - `writeRecordingIntoPalette` and `PaletteWriteOutcome` — recording the programmer into a palette.
 *   `routes/lookRecord.kt` replaces it, and the destination table no longer exists.
 * - `updateIncludedPalette` — the palette arm of Mode A Update, reachable only by a caller naming
 *   `paletteId` explicitly. The desk never did, and the Looks migration left no palette rows behind,
 *   so it was already dead in practice.
 * - `includePaletteIntoProgrammer` and `handleIncludePalette` — the palette *wrappers* around the
 *   shared include body. The body survives as [includeExpandedIntoProgrammer]; only the wrappers
 *   were palette-shaped, which is why Include needed no rewrite to lose them.
 */

/**
 * Expand a selection of fixture and group targets into the fixture keys Record filters on.
 * Unknown targets contribute nothing — the caller reports an empty result as a 400.
 */
internal fun expandTargetsToFixtureKeys(state: State, targets: List<CueTargetDto>): Set<String> {
    val out = LinkedHashSet<String>()
    for (target in targets) {
        when (val ref = TargetRef.ofOrNull(target.type, target.key)) {
            is TargetRef.Fixture -> out += ref.key
            is TargetRef.Group -> {
                val members = runCatching {
                    state.show.fixtures.untypedGroup(ref.key).fixtures
                        .filterIsInstance<uk.me.cormack.lighting7.fixture.Fixture>()
                }.getOrNull() ?: continue
                members.forEach { out += it.key }
            }
            null -> continue
        }
    }
    return out
}


/**
 * Load a Look's rows into the programmer as the edit buffer.
 *
 * **One-way for now.** Update-back is not wired to Looks — `updateIncludedPalette` writes into
 * `DaoPalette`, which no consumer reads any more — so the client disables Update for a `LOOK`
 * include target rather than being allowed to write rows nothing sees. The record rewrite is what
 * closes that.
 *
 * Every row arrives with a target of its own: a Look row is always bound (sweep item B6), and a
 * row an older database left deferred never survives `loadLookSnapshot`.
 */
internal fun includeLookIntoProgrammer(
    state: State,
    lookId: Int,
    lookUuid: java.util.UUID,
    mask: Set<PropertyMaskGroup>?,
    fadeMs: Long,
): LookIncludeOutcome = includeExpandedIntoProgrammer(
    state, lookUuid, mask, fadeMs,
    uk.me.cormack.lighting7.fx.IncludedTarget.look(lookId, lookUuid),
)

/**
 * The shared body: expand [sourceUuid] through [uk.me.cormack.lighting7.fx.LookRegistry], write the
 * literals into the programmer, and record [includedTarget] so Update knows what it is looking at.
 *
 * Writes **[ProgrammerValue.Hard]** slots, not refs: you are editing the source's own contents, and
 * a slot referencing the thing it is about to rewrite is meaningless. Contrast Include-a-cue,
 * which does write refs — there the reference is part of what the cue means.
 *
 * Group rows expand to members, and a member the source covers directly wins over the group row,
 * matching how it resolves at compose time.
 */
internal fun includeExpandedIntoProgrammer(
    state: State,
    sourceUuid: java.util.UUID,
    mask: Set<PropertyMaskGroup>?,
    fadeMs: Long,
    includedTarget: uk.me.cormack.lighting7.fx.IncludedTarget,
): LookIncludeOutcome {
    val expanded = state.show.lookRegistry.expanded(sourceUuid)
        ?: return LookIncludeOutcome(0, emptyList(), emptyList())

    val writes = ArrayList<uk.me.cormack.lighting7.fx.ProgrammerWriter.PropertyWrite>()
    val skips = ArrayList<RecordSkip>()
    val fixtureKeys = LinkedHashSet<String>()

    // Which group each member was covered by, so the slot keeps the operator's group shape and a
    // later Record can collapse back to a group row.
    val groupHints = HashMap<Pair<String, String>, String>()
    for (entry in expanded.snapshot.rows) {
        val group = entry.target as? TargetRef.Group ?: continue
        val members = runCatching {
            state.show.fixtures.untypedGroup(group.key).fixtures
                .filterIsInstance<uk.me.cormack.lighting7.fixture.Fixture>()
        }.getOrNull() ?: continue
        for (member in members) {
            groupHints[member.key to canonicalPropertyName(entry.propertyName)] = group.key
        }
    }

    for ((fixtureKey, byProperty) in expanded.byFixture) {
        val fixture = runCatching { state.show.fixtures.untypedGroupableFixture(fixtureKey) }.getOrNull()
        if (fixture == null) {
            skips += RecordSkip(fixtureKey, reason = RecordSkipReason.MISSING_FIXTURE)
            continue
        }
        for ((propertyName, literal) in byProperty) {
            val group = uk.me.cormack.lighting7.fx.maskGroupForProperty(fixture, propertyName)
            if (group == null) {
                skips += RecordSkip(fixtureKey, propertyName, reason = RecordSkipReason.MISSING_PROPERTY)
                continue
            }
            if (!uk.me.cormack.lighting7.fx.maskAllows(mask, group)) {
                skips += RecordSkip(fixtureKey, propertyName, reason = RecordSkipReason.MASKED_OUT)
                continue
            }
            val category = uk.me.cormack.lighting7.fx.PropertyChannelWriter
                .resolveProperty(fixture, propertyName)?.category
                ?: uk.me.cormack.lighting7.fixture.PropertyCategory.OTHER
            val value = uk.me.cormack.lighting7.fx.CueAssignmentResolver
                .parseAssignmentValue(category, propertyName, literal)
            if (value == null) {
                skips += RecordSkip(fixtureKey, propertyName, reason = RecordSkipReason.MISSING_PROPERTY)
                continue
            }
            writes += uk.me.cormack.lighting7.fx.ProgrammerWriter.PropertyWrite(
                fixture, propertyName, value,
                sourceGroup = groupHints[fixtureKey to propertyName],
            )
            fixtureKeys += fixtureKey
        }
    }

    if (writes.isNotEmpty()) {
        // One batched write, as Include-a-cue does: a large palette is hundreds of properties and
        // per-property publishing would visibly stutter the rig.
        state.show.fxEngine.programmer.writeProperties(
            uk.me.cormack.lighting7.fx.ProgrammerOwner.INCLUDE, writes, fadeMs = fadeMs,
        )
    }
    state.show.programmerStore.lastIncludedTarget = includedTarget

    return LookIncludeOutcome(writes.size, fixtureKeys.toList(), skips)
}

internal data class LookIncludeOutcome(
    val entriesWritten: Int,
    val fixtureKeys: List<String>,
    val skipped: List<RecordSkip>,
)

/**
 * Why a zero-write Look include always says something: silently writing nothing reads as a broken
 * button. The two reasons point at different places — the Look editor, or the patch — so they are
 * two messages rather than one hedged one.
 */
internal fun lookIncludeWarnings(lookName: String, outcome: LookIncludeOutcome): List<String> = when {
    outcome.entriesWritten > 0 -> emptyList()
    outcome.skipped.isNotEmpty() -> listOf(
        "None of '$lookName''s rows could be staged: the fixtures or properties they " +
            "name are not in the current patch, or the mask excluded them.",
    )
    else -> listOf("'$lookName' holds no rows to stage — only effects, or nothing at all.")
}
