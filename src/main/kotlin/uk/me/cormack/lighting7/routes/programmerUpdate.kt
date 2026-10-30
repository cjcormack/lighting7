package uk.me.cormack.lighting7.routes

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureAttributeCatalogue
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.IncludedTarget
import uk.me.cormack.lighting7.fx.CueLayerFields
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerStore
import uk.me.cormack.lighting7.fx.canonicalAttributeName
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.familyForAttribute
import uk.me.cormack.lighting7.fx.AttributeFamily
import uk.me.cormack.lighting7.fx.ProgrammerLayer
import uk.me.cormack.lighting7.fx.buildCueApplyData
import uk.me.cormack.lighting7.fx.buildCueAssignmentsForCue
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignment
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.FxTargetsRef
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.layerTargets
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.models.entryInStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry

/** One cue Update would overwrite, with a sample of the properties driving that. */
@Serializable
data class ProgrammerChecklistCueDto(
    val cueId: Int,
    val cueNumber: String? = null,
    /** True when [cueNumber] was derived from position rather than typed — rendered dimmed. */
    val cueNumberAuto: Boolean = false,
    val cueName: String,
    val isActive: Boolean,
    val keyCount: Int,
    /** Keys the cue drives through an effect rather than a property assignment. */
    val viaFxKeyCount: Int,
    val sample: List<ProgrammerChecklistKeyDto>,
)

/** One (fixture, property) the programmer is currently overriding. */
@Serializable
data class ProgrammerChecklistKeyDto(
    val targetKey: String,
    val attribute: String,
    /** The programmer's winning value, serialised. */
    val currentValue: String,
    /** The value underneath, serialised — null when nothing but baseline is below. */
    val cueValue: String? = null,
    val viaFx: Boolean = false,
)

/** Overridden cues, grouped by the stack they belong to. */
@Serializable
data class ProgrammerChecklistStackDto(
    /** Null bucket: the cue's stack couldn't be determined. */
    val cueStackId: Int? = null,
    val cueStackName: String? = null,
    val isActive: Boolean = false,
    val cues: List<ProgrammerChecklistCueDto>,
)

/**
 * The Mode B answer: everything the programmer is currently sitting on top of, so the client
 * can ask which of it to write.
 */
@Serializable
data class ProgrammerUpdateChecklistDto(
    val stacks: List<ProgrammerChecklistStackDto>,
    /** Touched keys with no cue underneath — programmer over baseline. */
    val unattributed: List<ProgrammerChecklistKeyDto>,
    val totalKeys: Int,
)

/** How many rows in the sample list per cue. Enough to recognise the cue, short enough to read. */
private const val CHECKLIST_SAMPLE_SIZE = 8

/**
 * The programmer entries Update should write back to the cue that was Included.
 *
 * The rule — write back only what changed since Include — is what makes Update
 * reference-preserving before palette refs exist at all.
 *
 * Include parses a cue row into a concrete value and stores it in an `INCLUDE` slot. Because
 * the store keeps a per-owner slot stack, that slot survives underneath any later `WEB` write,
 * so comparing the winning value against it answers "did the operator change this?" exactly.
 * Rows the operator never touched are not rewritten — which means a cue row stored as the
 * positional palette ref `"P1"` is still `"P1"` afterwards. Write everything back instead and
 * the first Update after any Include would silently harden every ref in the cue into a literal.
 *
 * Values that are new since the Include (no INCLUDE slot) are always written: that is the
 * operator adding a fixture to the cue. So is any value the source does not store
 * ([ProgrammerStore.sourceHolds], read afresh here), changed or not — what a masked or scoped
 * Record left out.
 */
internal fun changedSinceInclude(
    state: State,
    mask: Set<AttributeFamily>?,
): Pair<List<RecordEntry>, List<RecordSkip>> {
    val store = state.show.programmerStore
    // `allowElements`: both destinations can hold a cell — a cue as a cell-target row, a Look as
    // an element row — so a nudged head comes back through Update as the row it was included as.
    val (entries, skips) = collectProgrammerEntries(state, RecordSource.TOUCHED, mask, allowElements = true)
    val recorded = store.recordedValues
    store.lastIncludedTarget?.let { refreshSourceKeys(state, it) }
    val changed = entries.filterTo(LinkedHashSet()) { entry ->
        if (!store.sourceHolds(entry.key())) return@filterTo true
        // Since a Record, what was recorded is the baseline; a key it did not see is new.
        if (recorded != null) {
            return@filterTo recorded[ProgrammerStore.IncludedKey(entry.fixtureKey, entry.attribute)] !=
                (entry.reconciledFrom ?: entry.value)
        }
        val included = store.valueFor(ProgrammerOwner.INCLUDE, entry.fixtureKey, entry.attribute)
            // New since Include — the operator adding a fixture to the cue.
            ?: return@filterTo true
        // A plain value comparison since the `ref:` grammar retired. It used to also compare
        // reference *identity*, because a ref resolves to exactly the literal it was included as, so
        // a value-only test would write an untouched ref back and harden it. Nothing can hold a
        // reference now, and the surviving half of the mechanism — an INCLUDE slot outliving later
        // writes, so an untouched positional `"P1"` row stays `"P1"` — is unaffected.
        //
        // Against the slot's own value, not the bundle-reconciled one: a cue whose colour row and
        // emitter row disagree would otherwise read as changed the moment it was included, and
        // Update would rewrite a row the operator never touched.
        included.resolved != (entry.reconciledFrom ?: entry.value)
    }
    // An emitter an operator's newer colour overrode must go back too, carrying the colour's
    // component — playback lets the emitter's own row win, so leaving it would play back the old W.
    // "Its colour" is the bundle's own ([FixtureAttributeCatalogue.Entry.colour]), not a colour macro.
    val fixtures = state.show.fixtures
    val colourChanged = changed.mapNotNullTo(HashSet()) { entry ->
        val fixture = runCatching { fixtures.untypedGroupableFixture(entry.fixtureKey) }.getOrNull()
            ?: return@mapNotNullTo null
        entry.fixtureKey.takeIf { entry.attribute == FixtureAttributeCatalogue.of(fixture::class).colour?.name }
    }
    val overridden = entries.filter { entry ->
        entry !in changed &&
            entry.reconciledFrom is CueAssignmentResolver.AttributeValue.Slider &&
            entry.fixtureKey in colourChanged
    }
    return (changed + overridden).let { set -> entries.filter { it in set } } to skips
}

/**
 * After an Update, what it wrote is the baseline for the next — but only while a Record's baseline is
 * in force, and only what was written: a masked Update leaves the rest as the Record saw it.
 */
internal fun foldUpdateIntoRecorded(
    state: State,
    written: Collection<RecordEntry>,
    cleared: Collection<ProgrammerStore.IncludedKey>,
) {
    val store = state.show.programmerStore
    val recorded = store.recordedValues ?: return
    store.recordedValues = recorded - cleared.toSet() +
        written.associate { ProgrammerStore.IncludedKey(it.fixtureKey, it.attribute) to (it.reconciledFrom ?: it.value) }
}

/** The programmer key [this] entry was collected from. */
internal fun RecordEntry.key() = ProgrammerStore.IncludedKey(fixtureKey, attribute)

/**
 * The keys [target]'s source stores, unmasked and through Include's own expansion — a cue's rows
 * fanned out to members and cells, a Look's through the [uk.me.cormack.lighting7.fx.LookRegistry].
 */
internal fun sourceRowKeys(state: State, target: IncludedTarget): Set<ProgrammerStore.IncludedKey> {
    val fixtures = state.show.fixtures
    return when (target.kind) {
        IncludedTarget.Kind.CUE -> {
            val data = transaction(state.database) {
                DaoCue.findById(target.targetId)?.let { buildCueApplyData(it, null) }
            } ?: return emptySet()
            buildCueAssignmentsForCue(fixtures, data)
                .mapTo(HashSet()) { ProgrammerStore.IncludedKey(it.targetKey, canonicalAttributeName(it.attribute)) }
        }
        IncludedTarget.Kind.LOOK -> {
            val uuid = target.sourceUuid
                ?: transaction(state.database) { DaoLook.findById(target.targetId)?.uuid }
                ?: return emptySet()
            val expanded = state.show.lookRegistry.expanded(uuid) ?: return emptySet()
            expanded.byFixture.flatMapTo(HashSet()) { (fixtureKey, byAttribute) ->
                byAttribute.keys.map { ProgrammerStore.IncludedKey(fixtureKey, canonicalAttributeName(it)) }
            }
        }
    }
}

/** Read what [target]'s source now stores into [ProgrammerStore.sourceKeys]. */
internal fun refreshSourceKeys(state: State, target: IncludedTarget) {
    state.show.programmerStore.sourceKeys = ProgrammerStore.IncludedKeys(target, sourceRowKeys(state, target))
}

/**
 * How many of the programmer's touched values the include target's source does not store — what
 * Update writes though nothing has changed since. 0 with nothing included.
 */
internal fun heldOutsideSource(state: State): Int {
    val store = state.show.programmerStore
    if (store.lastIncludedTarget == null) return 0
    val (entries, _) = collectProgrammerEntries(state, RecordSource.TOUCHED, mask = null, allowElements = true)
    return entries.count { !store.sourceHolds(it.key()) }
}

/** A Record made the include target agree with the programmer: its touched values become Update's baseline. */
internal fun stampRecordedValues(state: State) {
    val (entries, _) = collectProgrammerEntries(state, RecordSource.TOUCHED, mask = null, allowElements = true)
    state.show.programmerStore.recordedValues = entries.associate {
        ProgrammerStore.IncludedKey(it.fixtureKey, it.attribute) to (it.reconciledFrom ?: it.value)
    }
}

/**
 * The keys Include staged from [target] that the operator has since cleared, under [mask] — the
 * values whose rows Mode A deletes.
 *
 * A key counts as cleared when nothing but a layer holds it any more: ⌫, a Release or a
 * `clear_programmer` scoped to it takes every owner's slot, the `INCLUDE` one with it.
 */
internal fun clearedSinceInclude(
    state: State,
    target: IncludedTarget,
    mask: Set<AttributeFamily>?,
): List<ProgrammerStore.IncludedKey> {
    val store = state.show.programmerStore
    return store.includedKeysFor(target).filter { included ->
        if (store.slotsFor(included.fixtureKey, included.attribute).any { it.owner != ProgrammerOwner.LAYERS }) {
            return@filter false
        }
        val fixture = runCatching { state.show.fixtures.untypedGroupableFixture(included.fixtureKey) }.getOrNull()
            ?: return@filter false
        maskAllows(mask, familyForAttribute(fixture, included.attribute))
    }
}

/** A stored value row as [planClearedRemoval] reads it: a cue's assignment or a Look's row. */
internal class StoredValueRow<R>(
    val row: R,
    val targetType: String,
    /** The fixture, cell or group the row names. */
    val targetKey: String,
    val attribute: String,
)

/** What deleting cleared keys does to one cue's or Look's rows. */
internal class ClearedRemoval<R>(
    /** Each cleared key's own row, and every group row covering a cleared member. */
    val delete: List<R>,
    /**
     * The member rows a deleted group row leaves behind — the group row to copy and the member it now
     * names — for every member still held that has no row of its own.
     */
    val split: List<Pair<R, String>>,
    /** Cleared keys no row here names, alone or through a group. */
    val unmatched: List<ProgrammerStore.IncludedKey>,
)

/**
 * Plan the deletion of [cleared] from [rows].
 *
 * A key's own row goes. A group row covering a cleared member goes too, and is **split**: each member
 * not cleared and without a row of its own gets the group row's value, so the members the operator
 * kept play on and the cleared one plays nothing — even where a direct row overrode it, which would
 * otherwise leave the group row driving it once the direct row went.
 */
internal fun <R> planClearedRemoval(
    rows: List<StoredValueRow<R>>,
    cleared: Collection<ProgrammerStore.IncludedKey>,
    membersOf: (groupKey: String) -> List<String>?,
): ClearedRemoval<R> {
    val clearedKeys = cleared.mapTo(HashSet()) { it.fixtureKey to canonicalAttributeName(it.attribute) }
    val direct = rows
        .filter { it.targetType == TargetRef.Fixture.TYPE }
        .mapTo(HashSet()) { it.targetKey to canonicalAttributeName(it.attribute) }
    val delete = ArrayList<R>()
    val split = ArrayList<Pair<R, String>>()
    val handled = HashSet<Pair<String, String>>()
    val splitKeys = HashSet<Pair<String, String>>()

    for (stored in rows) {
        val attribute = canonicalAttributeName(stored.attribute)
        when (stored.targetType) {
            TargetRef.Fixture.TYPE -> if ((stored.targetKey to attribute) in clearedKeys) {
                delete += stored.row
                handled += stored.targetKey to attribute
            }
            TargetRef.Group.TYPE -> {
                val members = membersOf(stored.targetKey) ?: continue
                val hit = members.filter { (it to attribute) in clearedKeys }
                if (hit.isEmpty()) continue
                delete += stored.row
                hit.forEach { handled += it to attribute }
                for (member in members) {
                    val key = member to attribute
                    if (key in clearedKeys || key in direct || !splitKeys.add(key)) continue
                    split += stored.row to member
                }
            }
        }
    }
    val unmatched = cleared.filter { (it.fixtureKey to canonicalAttributeName(it.attribute)) !in handled }
    return ClearedRemoval(delete, split, unmatched)
}

/** A group's member keys, for [planClearedRemoval]; null for a group the patch no longer has. */
internal fun groupMembersIn(fixtures: Fixtures): (String) -> List<String>? = { groupKey ->
    runCatching { fixtures.untypedGroup(groupKey).fixtures.filterIsInstance<Fixture>().map { it.key } }.getOrNull()
}

/** The warning for a cleared key Update could not delete: something other than a row of its own sets it. */
internal fun unmatchedClearWarning(key: ProgrammerStore.IncludedKey): String =
    "'${key.fixtureKey}'.${key.attribute} was cleared, but no row of its own sets it — use Record → Remove on what does"

/**
 * Delete [cleared] from [cue]'s own rows, splitting a group row as [planClearedRemoval] says.
 * Must run inside a transaction.
 */
internal fun removeClearedFromCue(
    cue: DaoCue,
    cleared: Collection<ProgrammerStore.IncludedKey>,
    fixtures: Fixtures,
): ClearedOutcome {
    if (cleared.isEmpty()) return ClearedOutcome()
    val assignments = cue.attributeAssignmentsInOrder()
    val plan = planClearedRemoval(
        assignments.map { StoredValueRow(it, it.targetType, it.targetKey, it.attribute) },
        cleared,
        groupMembersIn(fixtures),
    )
    var nextSort = (assignments.maxOfOrNull { it.sortOrder } ?: -1) + 1
    for ((group, member) in plan.split) {
        DaoCueAttributeAssignment.new {
            this.cue = cue
            targetType = TargetRef.Fixture.TYPE
            targetKey = member
            attribute = group.attribute
            value = group.value
            fadeDuration = group.fadeDuration
            sortOrder = nextSort++
            moveInDark = group.moveInDark
        }
    }
    plan.delete.forEach { it.delete() }
    return ClearedOutcome(plan.delete.size, plan.split.size, plan.unmatched.map(::unmatchedClearWarning))
}

/** What [removeClearedFromCue] or [removeClearedFromLook] changed. */
internal data class ClearedOutcome(
    val removed: Int = 0,
    /** Member rows written in place of a split group row. */
    val written: Int = 0,
    val warnings: List<String> = emptyList(),
)

/**
 * Build the Mode B checklist: which cues (grouped by stack) the programmer's touched entries
 * are currently overriding.
 *
 * Uses [FxEngine.underlyingSources] rather than provenance: provenance correctly reports the
 * programmer as the winner, which is precisely the answer this can't use. The Layer 4 winner
 * map is computed at publish time and knows nothing about the programmer, so it already is
 * "the cue underneath".
 *
 * **Cue-only, deliberately, and there is no palette equivalent to add.** This checklist's premise
 * is "which cue am I sitting on top of", answered from the output cascade — and a palette is not in
 * that cascade at all. It contributes by being *resolved into* cue and programmer values, so
 * nothing here can attribute a key to one. Writing back to a palette therefore only happens via
 * Mode A, where Include named the palette explicitly.
 */
internal fun buildUpdateChecklist(
    state: State,
    mask: Set<AttributeFamily>?,
): ProgrammerUpdateChecklistDto {
    val engine = state.show.fxEngine
    val (entries, _) = collectProgrammerEntries(state, RecordSource.TOUCHED, mask, allowElements = true)
    if (entries.isEmpty()) {
        return ProgrammerUpdateChecklistDto(emptyList(), emptyList(), 0)
    }

    val byKey = entries.associateBy { CueAssignmentResolver.Key.fixture(it.fixtureKey, it.attribute) }
    // One snapshot for both the cueValue reads and the attribution, so a cue apply landing
    // mid-request cannot pair one cue's value with another cue's name in the checklist.
    val cueLayer = engine.layerResolver.current
    val cueLayerState = cueLayer.state
    val sources = engine.provenance.underlyingSources(byKey.keys, cueLayer)

    val unattributed = ArrayList<ProgrammerChecklistKeyDto>()
    val byCue = LinkedHashMap<Int, MutableList<ProgrammerChecklistKeyDto>>()
    val stackOf = HashMap<Int, Int?>()

    for (source in sources) {
        val entry = byKey[source.key] ?: continue
        val dto = ProgrammerChecklistKeyDto(
            targetKey = entry.fixtureKey,
            attribute = entry.attribute,
            currentValue = entry.value.serialize(),
            cueValue = cueLayerState[source.key]?.serialize(),
            viaFx = source.viaFxId != null,
        )
        val cueId = source.cueId
        if (cueId == null) {
            unattributed.add(dto)
        } else {
            byCue.getOrPut(cueId) { mutableListOf() }.add(dto)
            stackOf.putIfAbsent(cueId, source.cueStackId)
        }
    }

    if (byCue.isEmpty()) {
        return ProgrammerUpdateChecklistDto(
            emptyList(), unattributed.sortedWith(keyOrder), entries.size,
        )
    }

    // One read for every cue's name, and its number in the stack the engine says holds it. A cue
    // held only loose has no stack, and nothing here picks one of a shared cue's (cue model plan P7).
    data class CueMeta(
        val name: String,
        val number: String?,
        val numberAuto: Boolean,
        val stackName: String?,
    )
    val meta = transaction(state.database) {
        byCue.keys.associateWith { cueId ->
            DaoCue.findById(cueId)?.let { cue ->
                // The stack's live entry when it is this cue's (a cue twice in one stack, D7), else its first there.
                val entry = stackOf[cueId]?.let { stackId ->
                    state.show.cueStackManager.getActiveEntryId(stackId)
                        ?.let { DaoCueStackEntry.findById(it) }?.takeIf { it.cueId == cueId }
                        ?: entryInStack(stackId, cueId)
                }
                CueMeta(cue.name, entry?.cueNumber, entry?.cueNumberAuto ?: false, entry?.stack?.name)
            }
        }
    }

    val cueDtos = byCue.map { (cueId, keys) ->
        val cueMeta = meta[cueId]
        val stackId = stackOf[cueId]
        val sorted = keys.sortedWith(keyOrder)
        Triple(
            stackId,
            cueMeta?.stackName,
            ProgrammerChecklistCueDto(
                cueId = cueId,
                cueNumber = cueMeta?.number,
                cueNumberAuto = cueMeta?.numberAuto ?: false,
                cueName = cueMeta?.name ?: "Cue $cueId",
                // Live is held — by a stack or a loose press (cue model plan §3.4).
                isActive = state.show.cueStackManager.holds.isHeld(cueId),
                keyCount = keys.size,
                viaFxKeyCount = keys.count { it.viaFx },
                sample = sorted.take(CHECKLIST_SAMPLE_SIZE),
            ),
        )
    }

    val stacks = cueDtos
        .groupBy { it.first }
        .map { (stackId, group) ->
            ProgrammerChecklistStackDto(
                cueStackId = stackId,
                cueStackName = group.firstNotNullOfOrNull { it.second },
                isActive = stackId != null && state.show.cueStackManager.getActiveCueId(stackId) != null,
                cues = group.map { it.third }.sortedWith(
                    compareBy({ it.cueNumber ?: "" }, { it.cueId }),
                ),
            )
        }
        .sortedWith(compareBy(nullsLast()) { it.cueStackId })

    return ProgrammerUpdateChecklistDto(stacks, unattributed.sortedWith(keyOrder), entries.size)
}

private val keyOrder = compareBy(
    ProgrammerChecklistKeyDto::targetKey,
    ProgrammerChecklistKeyDto::attribute,
)

/**
 * The touched entries whose underlying source is [cueId] — the rows a Mode B commit writes to
 * that cue.
 *
 * Scoping matters: a checklist commit naming two cues must not smear one cue's overrides onto
 * the other. Each cue gets only the keys it was actually underneath.
 */
internal fun entriesUnderlyingCue(
    state: State,
    cueId: Int,
    mask: Set<AttributeFamily>?,
): List<RecordEntry> {
    val engine = state.show.fxEngine
    val (entries, _) = collectProgrammerEntries(state, RecordSource.TOUCHED, mask, allowElements = true)
    if (entries.isEmpty()) return emptyList()
    val byKey = entries.associateBy { CueAssignmentResolver.Key.fixture(it.fixtureKey, it.attribute) }
    return engine.provenance.underlyingSources(byKey.keys)
        .filter { it.cueId == cueId }
        .mapNotNull { byKey[it.key] }
}

/**
 * Package [entries] as a recording for the cue write path, with the band FX the operator has
 * running.
 *
 * Update applies MERGE semantics. The only rows it deletes are the ones [clearedSinceInclude]
 * names, never the rows the programmer merely doesn't hold: a Clear drops the Include baseline with
 * the target, so an Update after a Clear-and-rebuild cannot strip what the operator didn't re-set.
 */
internal fun recordingForUpdate(
    state: State,
    entries: List<RecordEntry>,
    includeFx: Boolean,
): ProgrammerRecording {
    val collapsed = collapseRecordingToAssignments(entries, state.show.fixtures)
    val bandFx = if (includeFx) {
        state.show.fxEngine.getActiveFx()
            .filter { FxEngine.isProgrammerFxPriority(it.priority) && !it.leaving }
    } else emptyList()
    val captured = fxInstancesToCueChildren(state.show.fxEngine.inStoredApplyOrder(bandFx), mask = null, state.show.fixtures)
    return ProgrammerRecording(
        rows = collapsed.rows,
        // Update never writes the layer stack through this path: the *structural* half of Mode A is
        // a diff against the layers Include staged, applied directly to the cue's rows. Handing a
        // layer list to the MERGE writer as well would append the whole stack on every Update.
        layers = emptyList(),
        adHocFx = captured.map { it.second },
        groupRowsEmitted = collapsed.groupRows,
        skipped = collapsed.skipped,
        adHocSourceIds = captured.map { it.first.id },
    )
}

/**
 * The uuids of [cueId]'s ad-hoc effects whose Included instance now runs only as split
 * copies (apply-model plan §3.4). Update writes the copies as children of their own, one per head,
 * so the child they stand for is retired, or the cue would play the effect twice. A child a copy
 * was already written as is on that copy's own head, and is kept.
 *
 * So is one whose instance a programmer layer took over (`asLayer` on Record → A new Look): Update
 * writes that layer into the cue, and the layer plays the effect.
 */
internal fun splitIncludedChildren(state: State, cueId: Int): Set<String> {
    val byChild = state.show.fxEngine.getActiveFx()
        .filter { FxEngine.isProgrammerFxPriority(it.priority) && it.programmerOrigin?.cueId == cueId && !it.leaving }
        .groupBy { it.programmerOrigin!!.childUuid }
        .filterKeys { it != null }
    val layered = byChild.filterValues { instances -> instances.all { it.programmerLayerId != null } }
        .keys.filterNotNullTo(HashSet())
    val copies = byChild.filterValues { instances -> instances.all { it.splitMember != null } }
    if (copies.isEmpty()) return layered
    val targets = transaction(state.database) {
        DaoCue.findById(cueId)?.adHocFx?.associate {
            it.uuid.toString() to FxTargetsRef.canonical(it.targetType, it.targetKey)
        }.orEmpty()
    }
    return copies.filter { (uuid, instances) ->
        instances.none { FxTargetsRef.canonical(it.target.targetRef.discriminator, it.target.targetKey) == targets[uuid] }
    }.keys.filterNotNullTo(layered)
}

private fun DaoCueLayer.cueFields() =
    CueLayerFields(enabled, targets, attributeMask, blendMode, amount, stomp, speedMasterUuid, rateSpeedMasterUuid)

/** What a layer-stack diff changed in the cue, for the response and the log line. */
internal data class LayerDiffOutcome(
    val added: Int,
    val removed: Int,
    val reordered: Int,
    val retuned: Int,
    val timedPreserved: Int,
    /** The cue row each added layer was written as, by [ProgrammerLayer.layerId]. */
    val addedRows: Map<Int, Int> = emptyMap(),
)

/**
 * Write the programmer's layer stack back onto [cue] — the **structural** half of Mode A Update.
 *
 * The value half (`changedSinceInclude`) answers "which local rows did the operator edit?" by
 * comparing against the surviving `INCLUDE` slot. Neither question implies the other: reordering a
 * layer changes no slot's value, and editing a row changes no layer. So Update needs both, and this
 * is the second.
 *
 * The diff keys on [ProgrammerLayer.sourceCueLayerId] — the `DaoCueLayer` row Include minted the
 * layer from. That is the only stable identity available: `layerId` is in-memory and re-minted on
 * every Include, `lookId` cannot distinguish one Look layered twice, and array position is exactly
 * what the operator may have just changed.
 *
 * Five classifications, and one deliberate non-case:
 *
 * - **added** — no `sourceCueLayerId`, so it was created in the programmer after the Include.
 * - **removed** — in the baseline but not in the stack now.
 * - **reordered** — present, but at a different index. Every surviving row is renumbered densely
 *   from the current list, so a single move cannot leave a gap or a tie.
 * - **retuned** — field-by-field inequality. **Not** data-class equality: `layerId` and `sortOrder`
 *   differ by construction, so `!=` on the whole object would report every layer as changed. This is
 *   the same trap `buildCueInput` documents on the frontend.
 * - **timed** — a layer Include never held, because the programmer has no trigger manager. Left
 *   exactly as it is and counted, which is what makes "Include, edit, Update" safe on a cue with a
 *   delayed layer in it.
 *
 * There is no "the layer's Look changed" case, because there is no gesture that changes it: the
 * desk removes the layer and adds another. Modelling it would be inventing a case to handle.
 *
 * Must be called inside a transaction.
 */
internal fun writeLayerStackIntoCue(
    cue: DaoCue,
    current: List<ProgrammerLayer>,
    baseline: List<ProgrammerLayer>,
): LayerDiffOutcome {
    val storedById = cue.layers.filterNot { it.isTimed }.associateBy { it.id.value }
    val timedPreserved = cue.layers.count { it.isTimed }

    val addedRows = HashMap<Int, Int>()
    var removed = 0
    var reordered = 0
    var retuned = 0

    // Removed: in the baseline, gone from the stack. Read from the baseline rather than from the
    // cue's rows so a layer added to the cue by something else since the Include is left alone.
    val survivingSourceIds = current.mapNotNull { it.sourceCueLayerId }.toSet()
    for (gone in baseline.mapNotNull { it.sourceCueLayerId }.filterNot { it in survivingSourceIds }) {
        storedById[gone]?.let { it.delete(); removed++ }
    }

    val left = baseline.filter { it.sourceCueLayerId == null }.mapTo(HashSet()) { it.layerId }
    for ((index, layer) in current.withIndex()) {
        val stored = layer.sourceCueLayerId?.let { storedById[it] }
        // A layer the last Record flattened into rows rather than writing as a layer.
        if (stored == null && layer.sourceCueLayerId == null && layer.layerId in left) continue
        if (stored == null) {
            val resolved = resolveLayerSourceRecords(layer.source) ?: continue
            addedRows[layer.layerId] = DaoCueLayer.new {
                this.cue = cue
                this.look = resolved.look
                this.palette = resolved.palette
                this.sortOrder = index
                this.enabled = layer.enabled
                this.targets = layerTargets(layer.source, layer.targets)
                this.attributeMask = layer.attributeMask
                this.blendMode = layer.blendMode
                this.amount = layer.amount
                this.stomp = layer.stomp
                this.speedMasterUuid = layer.speedMasterUuid
                this.rateSpeedMasterUuid = layer.rateSpeedMasterUuid
            }.id.value
            continue
        }

        if (stored.sortOrder != index) {
            stored.sortOrder = index
            reordered++
        }
        if (stored.cueFields() != layer.cueFields()) {
            stored.enabled = layer.enabled
            stored.targets = layerTargets(layer.source, layer.targets)
            stored.attributeMask = layer.attributeMask
            stored.blendMode = layer.blendMode
            stored.amount = layer.amount
            stored.stomp = layer.stomp
            stored.speedMasterUuid = layer.speedMasterUuid
            stored.rateSpeedMasterUuid = layer.rateSpeedMasterUuid
            retuned++
        }
    }

    return LayerDiffOutcome(addedRows.size, removed, reordered, retuned, timedPreserved, addedRows)
}
