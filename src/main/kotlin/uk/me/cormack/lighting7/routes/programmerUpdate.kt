package uk.me.cormack.lighting7.routes

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.ProgrammerLayer
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.state.State

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
    val viaEffectKeyCount: Int,
    val sample: List<ProgrammerChecklistKeyDto>,
)

/** One (fixture, property) the programmer is currently overriding. */
@Serializable
data class ProgrammerChecklistKeyDto(
    val targetKey: String,
    val propertyName: String,
    /** The programmer's winning value, serialised. */
    val currentValue: String,
    /** The value underneath, serialised — null when nothing but baseline is below. */
    val cueValue: String? = null,
    val viaEffect: Boolean = false,
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
 * operator adding a fixture to the cue.
 */
internal fun changedSinceInclude(
    state: State,
    mask: Set<PropertyMaskGroup>?,
): Pair<List<RecordEntry>, List<RecordSkip>> {
    val store = state.show.programmerStore
    val (entries, skips) = collectProgrammerEntries(state, RecordSource.TOUCHED, mask)
    val changed = entries.filter { entry ->
        val included = store.valueFor(ProgrammerOwner.INCLUDE, entry.fixtureKey, entry.propertyName)
            // New since Include — the operator adding a fixture to the cue.
            ?: return@filter true
        // A plain value comparison since the `ref:` grammar retired. It used to also compare
        // reference *identity*, because a ref resolves to exactly the literal it was included as, so
        // a value-only test would write an untouched ref back and harden it. Nothing can hold a
        // reference now, and the surviving half of the mechanism — an INCLUDE slot outliving later
        // writes, so an untouched positional `"P1"` row stays `"P1"` — is unaffected.
        included.resolved != entry.value
    }
    return changed to skips
}

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
    mask: Set<PropertyMaskGroup>?,
): ProgrammerUpdateChecklistDto {
    val engine = state.show.fxEngine
    val (entries, _) = collectProgrammerEntries(state, RecordSource.TOUCHED, mask)
    if (entries.isEmpty()) {
        return ProgrammerUpdateChecklistDto(emptyList(), emptyList(), 0)
    }

    val byKey = entries.associateBy { CueAssignmentResolver.Key.fixture(it.fixtureKey, it.propertyName) }
    val cueLayerState = engine.layerResolver.currentCueLayerState
    val sources = engine.underlyingSources(byKey.keys)

    val unattributed = ArrayList<ProgrammerChecklistKeyDto>()
    val byCue = LinkedHashMap<Int, MutableList<ProgrammerChecklistKeyDto>>()
    val stackOf = HashMap<Int, Int?>()

    for (source in sources) {
        val entry = byKey[source.key] ?: continue
        val dto = ProgrammerChecklistKeyDto(
            targetKey = entry.fixtureKey,
            propertyName = entry.propertyName,
            currentValue = entry.value.serialize(),
            cueValue = cueLayerState[source.key]?.serialize(),
            viaEffect = source.viaEffectId != null,
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

    // One read for every cue's name/number and, where the engine map didn't have it, its
    // stack. A cue whose assignments are empty but whose effects are running has no engine
    // stack entry, so the DB is the fallback rather than the primary.
    data class CueMeta(
        val name: String,
        val number: String?,
        val numberAuto: Boolean,
        val stackId: Int?,
        val stackName: String?,
    )
    val meta = transaction(state.database) {
        byCue.keys.associateWith { cueId ->
            DaoCue.findById(cueId)?.let { cue ->
                CueMeta(
                    cue.name, cue.cueNumber, cue.cueNumberAuto,
                    cue.cueStack.id.value, cue.cueStack.name,
                )
            }
        }
    }

    val cueDtos = byCue.map { (cueId, keys) ->
        val cueMeta = meta[cueId]
        val stackId = stackOf[cueId] ?: cueMeta?.stackId
        val sorted = keys.sortedWith(keyOrder)
        Triple(
            stackId,
            cueMeta?.stackName,
            ProgrammerChecklistCueDto(
                cueId = cueId,
                cueNumber = cueMeta?.number,
                cueNumberAuto = cueMeta?.numberAuto ?: false,
                cueName = cueMeta?.name ?: "Cue $cueId",
                isActive = stackId != null &&
                    state.show.cueStackManager.getActiveCueId(stackId) == cueId,
                keyCount = keys.size,
                viaEffectKeyCount = keys.count { it.viaEffect },
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
    ProgrammerChecklistKeyDto::propertyName,
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
    mask: Set<PropertyMaskGroup>?,
): List<RecordEntry> {
    val engine = state.show.fxEngine
    val (entries, _) = collectProgrammerEntries(state, RecordSource.TOUCHED, mask)
    if (entries.isEmpty()) return emptyList()
    val byKey = entries.associateBy { CueAssignmentResolver.Key.fixture(it.fixtureKey, it.propertyName) }
    return engine.underlyingSources(byKey.keys)
        .filter { it.cueId == cueId }
        .mapNotNull { byKey[it.key] }
}

/**
 * Package [entries] as a recording for the cue write path, with the band FX the operator has
 * running.
 *
 * Update applies MERGE semantics — it never deletes. Removing content from a cue is
 * `Record REMOVE`; conflating the two would make an Update after a Clear-and-rebuild silently
 * strip everything the operator didn't happen to re-set.
 */
internal fun recordingForUpdate(
    state: State,
    entries: List<RecordEntry>,
    includeFx: Boolean,
): ProgrammerRecording {
    val collapsed = collapseRecordingToAssignments(entries, state.show.fixtures)
    val bandEffects = if (includeFx) {
        state.show.fxEngine.getActiveEffects()
            .filter { FxEngine.isProgrammerFxPriority(it.priority) }
    } else emptyList()
    val adHoc = fxInstancesToCueChildren(bandEffects, mask = null, state.show.fixtures)
    return ProgrammerRecording(
        rows = collapsed.rows,
        // Update never writes the layer stack through this path: the *structural* half of Mode A is
        // a diff against the layers Include staged, applied directly to the cue's rows. Handing a
        // layer list to the MERGE writer as well would append the whole stack on every Update.
        layers = emptyList(),
        adHocEffects = adHoc,
        groupRowsEmitted = collapsed.groupRows,
        skipped = collapsed.skipped,
    )
}

/** What a layer-stack diff changed in the cue, for the response and the log line. */
internal data class LayerDiffOutcome(
    val added: Int,
    val removed: Int,
    val reordered: Int,
    val retuned: Int,
    val timedPreserved: Int,
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
    val live = current.filterNot { it.isPreview }
    val storedById = cue.layers.filterNot { it.isTimed }.associateBy { it.id.value }
    val timedPreserved = cue.layers.count { it.isTimed }

    var added = 0
    var removed = 0
    var reordered = 0
    var retuned = 0

    // Removed: in the baseline, gone from the stack. Read from the baseline rather than from the
    // cue's rows so a layer added to the cue by something else since the Include is left alone.
    val survivingSourceIds = live.mapNotNull { it.sourceCueLayerId }.toSet()
    for (gone in baseline.mapNotNull { it.sourceCueLayerId }.filterNot { it in survivingSourceIds }) {
        storedById[gone]?.let { it.delete(); removed++ }
    }

    for ((index, layer) in live.withIndex()) {
        val stored = layer.sourceCueLayerId?.let { storedById[it] }
        if (stored == null) {
            val resolved = resolveLayerSourceRecords(layer.source) ?: continue
            DaoCueLayer.new {
                this.cue = cue
                this.look = resolved.look
                this.template = resolved.template
                this.sortOrder = index
                this.enabled = layer.enabled
                this.targets = layer.targets
                this.propertyMask = layer.propertyMask
                this.blendMode = layer.blendMode
                this.amount = layer.amount
                this.stomp = layer.stomp
                this.speedMasterUuid = layer.speedMasterUuid
                this.rateSpeedMasterUuid = layer.rateSpeedMasterUuid
            }
            added++
            continue
        }

        if (stored.sortOrder != index) {
            stored.sortOrder = index
            reordered++
        }
        var moved = false
        if (stored.enabled != layer.enabled) { stored.enabled = layer.enabled; moved = true }
        if (stored.targets != layer.targets) { stored.targets = layer.targets; moved = true }
        if (stored.propertyMask != layer.propertyMask) { stored.propertyMask = layer.propertyMask; moved = true }
        if (stored.blendMode != layer.blendMode) { stored.blendMode = layer.blendMode; moved = true }
        if (stored.amount != layer.amount) { stored.amount = layer.amount; moved = true }
        if (stored.stomp != layer.stomp) { stored.stomp = layer.stomp; moved = true }
        if (stored.speedMasterUuid != layer.speedMasterUuid) {
            stored.speedMasterUuid = layer.speedMasterUuid; moved = true
        }
        if (stored.rateSpeedMasterUuid != layer.rateSpeedMasterUuid) {
            stored.rateSpeedMasterUuid = layer.rateSpeedMasterUuid; moved = true
        }
        if (moved) retuned++
    }

    return LayerDiffOutcome(added, removed, reordered, retuned, timedPreserved)
}
