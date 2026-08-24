package uk.me.cormack.lighting7.routes

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerLayer
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.maskGroupForProperty
import uk.me.cormack.lighting7.models.CueAdHocEffectDto
import uk.me.cormack.lighting7.models.CueLayerDto
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State
import java.util.UUID
import java.awt.Color

private val logger = LoggerFactory.getLogger("uk.me.cormack.lighting7.routes.programmerCapture")

/**
 * Where a Record reads its content from.
 *
 * The default, [TOUCHED], is the point of the whole redesign: what records is what the
 * operator busked, read straight out of the programmer rather than reverse-engineered from
 * composed stage state.
 */
enum class RecordSource {
    /**
     * Programmer entries whose winning slot is an operator edit, plus touched sideband slots.
     * Excludes unpark hand-downs.
     */
    TOUCHED,

    /**
     * Everything [TOUCHED] takes plus untouched slots — today that means unpark hand-downs,
     * which are channel-shaped by construction and so only ever appear in the sideband. "Record
     * the rig exactly as the programmer is holding it", for when a park/unpark cycle is part of
     * the look you want.
     */
    ALL,

    /**
     * The old `snapshot-from-live` behaviour: composed stage state (cue layer overlaid with the
     * programmer) plus every running effect. Captures everything on stage, including values no one
     * in this session touched.
     */
    STAGE_SNAPSHOT,
}

/** Why a programmer entry didn't make it into the recording. Surfaced to the operator. */
enum class RecordSkipReason {
    /**
     * The entry is keyed by an element (`bar-1.head-0`). Cue assignments resolve fixture keys
     * through `Fixtures.untypedFixture`, which doesn't see elements, so the row would be
     * permanently dead. Lifting this needs an `element_key` column on cue assignments.
     */
    ELEMENT_TARGET,

    /** The fixture the entry names no longer exists in the patch. */
    MISSING_FIXTURE,

    /** The property no longer resolves on that fixture (renamed or removed). */
    MISSING_PROPERTY,

    /** A sideband channel with no backing property — cue assignments have no channel form. */
    NO_BACKING_PROPERTY,

    /** Filtered out by the request's attribute mask. */
    MASKED_OUT,

    /**
     * Outside the request's fixture scope. Recording a **Look** from the *whole* programmer is
     * almost always wrong — "Warm Amber" would capture every head the programmer happens to hold —
     * so the Look routes pass the operator's selection and everything else reports this.
     */
    OUT_OF_SCOPE,
}

/** One entry that couldn't be recorded, named however it can be. */
data class RecordSkip(
    val targetKey: String? = null,
    val propertyName: String? = null,
    val universe: Int? = null,
    val channel: Int? = null,
    val reason: RecordSkipReason,
)

/**
 * One fixture-level value pulled out of the programmer, before group collapse.
 *
 * [sourceGroup] is the hint that lets [collapseRecordingToAssignments] consider emitting a
 * group row; it is only a candidate, never authoritative — see that function.
 */
data class RecordEntry(
    val fixtureKey: String,
    val propertyName: String,
    val value: CueAssignmentResolver.PropertyValue,
    val sourceGroup: String?,
    val maskGroup: PropertyMaskGroup?,
)

/** Everything one Record source produced, ready to be written into a cue. */
data class ProgrammerRecording(
    val rows: List<CuePropertyAssignmentDto>,
    /**
     * The programmer's Look-layer stack, in order — the *structure* Record saves.
     *
     * Taken from [uk.me.cormack.lighting7.fx.ProgrammerStore.layers] rather than reconstructed from
     * the running effects, because the stack is the thing the operator built: it carries the blend
     * mode, amount, mask and target set, none of which survive in an `FxInstance`.
     *
     * **Empty for [RecordSource.ALL], and that is the whole meaning of that source.** See
     * `collectProgrammerRecording`.
     */
    val layers: List<CueLayerDto>,
    val adHocEffects: List<CueAdHocEffectDto>,
    val groupRowsEmitted: Int,
    val skipped: List<RecordSkip>,
)

/**
 * Read the programmer's property entries (and the sideband slots that lift to properties) as
 * fixture-level [RecordEntry] rows.
 *
 * Shared by Record and by Update, which needs the same key set before comparing each entry
 * against what Include put there.
 *
 * **Precedence across granularities is by recency**, using [ProgrammerStore.Slot.seq] — the
 * same rule the render path applies (`FxTarget.composeProgrammerOver` compares a property
 * entry's seq against a covering sideband slot's and takes the newer). Record has to agree
 * with it, or a raw pan drag over an older Locate would show the new angle on stage and
 * record the old one — a fresh instance of exactly the lossiness this redesign removes.
 */
internal fun collectProgrammerEntries(
    state: State,
    source: RecordSource,
    mask: Set<PropertyMaskGroup>?,
    /** Restrict to these fixture keys (groups already expanded). Null or empty = no restriction. */
    targets: Set<String>? = null,
): Pair<List<RecordEntry>, List<RecordSkip>> {
    val store = state.show.programmerStore
    val fixtures = state.show.fixtures
    val entries = LinkedHashMap<Pair<String, String>, RecordEntry>()
    val seqs = HashMap<Pair<String, String>, Long>()
    val skips = ArrayList<RecordSkip>()

    val scope = targets?.takeIf { it.isNotEmpty() }

    fun accept(
        fixtureKey: String,
        rawPropertyName: String,
        value: CueAssignmentResolver.PropertyValue,
        sourceGroup: String?,
        seq: Long,
    ) {
        val propertyName = canonicalPropertyName(rawPropertyName)
        val mapKey = fixtureKey to propertyName
        // Older than what already claimed this property — the newer write is what's on stage.
        if (seqs[mapKey]?.let { it >= seq } == true) return
        if (scope != null && fixtureKey !in scope) {
            skips += RecordSkip(fixtureKey, propertyName, reason = RecordSkipReason.OUT_OF_SCOPE)
            return
        }
        val fixture = try {
            fixtures.untypedGroupableFixture(fixtureKey)
        } catch (_: Exception) {
            skips += RecordSkip(fixtureKey, propertyName, reason = RecordSkipReason.MISSING_FIXTURE)
            return
        }
        if (fixture !is Fixture) {
            skips += RecordSkip(fixtureKey, propertyName, reason = RecordSkipReason.ELEMENT_TARGET)
            return
        }
        val maskGroup = maskGroupForProperty(fixture, propertyName)
        if (maskGroup == null) {
            skips += RecordSkip(fixtureKey, propertyName, reason = RecordSkipReason.MISSING_PROPERTY)
            return
        }
        if (!maskAllows(mask, maskGroup)) {
            skips += RecordSkip(fixtureKey, propertyName, reason = RecordSkipReason.MASKED_OUT)
            return
        }
        entries[mapKey] = RecordEntry(fixtureKey, propertyName, value, sourceGroup, maskGroup)
        seqs[mapKey] = seq
    }

    for (entry in store.entries()) {
        val top = entry.slots.firstOrNull() ?: continue
        if (source == RecordSource.TOUCHED && !top.touched) continue
        accept(entry.fixtureKey, entry.propertyName, top.value.resolved, top.sourceGroup, top.seq)
    }

    // Sideband slots that a property covers can still be recorded — a raw pan/tilt drag from
    // the Channels view becomes a `position` row rather than vanishing, which is a capability
    // today's snapshot doesn't have. Slots with no covering property genuinely cannot: there
    // is no (target, property) to name them by, and inventing a channel-shaped cue child would
    // resurrect the channel/property split this redesign removed.
    for (channelEntry in store.channelEntries()) {
        val top = channelEntry.slots.firstOrNull() ?: continue
        if (source == RecordSource.TOUCHED && !top.touched) continue
        val key = state.show.fxEngine.resolveChannelCoveringKey(channelEntry.universe, channelEntry.channel)
        if (key == null) {
            skips += RecordSkip(
                universe = channelEntry.universe,
                channel = channelEntry.channel,
                reason = RecordSkipReason.NO_BACKING_PROPERTY,
            )
            continue
        }
        val canonical = canonicalPropertyName(key.propertyName)
        // Cheap pre-check before the fixture/output reads below, which aren't free.
        if (seqs[key.targetKey to canonical]?.let { it >= top.seq } == true) continue
        val fixture = try {
            fixtures.untypedGroupableFixture(key.targetKey)
        } catch (_: Exception) {
            skips += RecordSkip(key.targetKey, canonical, reason = RecordSkipReason.MISSING_FIXTURE)
            continue
        }
        // The sideband holds one channel; the property it backs may span several (a colour
        // bundle, both position axes), so the value is read from the output rather than from
        // the slot — the other channels' current values have to come from somewhere.
        val value = readOutputPropertyValue(state, fixture, canonical, channelEntry.universe)
        if (value == null) {
            skips += RecordSkip(key.targetKey, canonical, reason = RecordSkipReason.MISSING_PROPERTY)
            continue
        }
        accept(key.targetKey, canonical, value, sourceGroup = null, seq = top.seq)
    }

    return entries.values.toList() to skips
}

/**
 * A programmer layer as the cue row that would recreate it.
 *
 * `sourceCueLayerId` is deliberately not carried across: it names the row this layer was *included
 * from*, which is Update's diff key, and has no meaning in a freshly written cue.
 */
internal fun ProgrammerLayer.toCueLayerDto() = CueLayerDto(
    // Exactly one, from the source's own kind — the DTO's write contract.
    lookId = source.id.takeUnless { source.isTemplate },
    templateId = source.id.takeIf { source.isTemplate },
    sortOrder = sortOrder,
    enabled = enabled,
    targets = targets,
    propertyMask = propertyMask,
    blendMode = blendMode,
    amount = amount,
    stomp = stomp,
    speedMasterUuid = speedMasterUuid?.toString(),
    rateSpeedMasterUuid = rateSpeedMasterUuid?.toString(),
)

/**
 * Is [target] wholly inside [scope]? A null scope means "no restriction" and accepts everything.
 *
 * **Wholly** is the load-bearing word, and it is what makes one rule serve both directions. A
 * group target writes to every member, so one whose membership only partly overlaps the
 * selection would put values on heads the operator didn't select. Recording therefore drops
 * such a target, and the destructive `UPDATE_EXISTING` / `REMOVE` passes preserve it — the same
 * predicate, read once as "may I capture this" and once as "may I overwrite this".
 *
 * The TOUCHED/ALL path gets this for free rather than through this function:
 * [collapseRecordingToAssignments] only emits a group row when every member has an entry, and a
 * partly-scoped group has already lost some.
 */
internal fun targetInScope(fixtures: Fixtures, target: TargetRef, scope: Set<String>?): Boolean {
    if (scope == null) return true
    return when (target) {
        is TargetRef.Fixture -> target.key in scope
        is TargetRef.Group -> {
            val members = try {
                fixtures.untypedGroup(target.key).fixtures.filterIsInstance<Fixture>()
            } catch (_: Exception) {
                return false
            }
            members.isNotEmpty() && members.all { it.key in scope }
        }
    }
}

/**
 * Collect a full recording for [source], with the group-shape collapse already applied.
 *
 * [includeFx] controls whether programmer-band effects become cue FX children — an operator
 * recording "just the look" over a busked chase wants the values without the chase.
 *
 * [targets] restricts the capture to a set of fixture keys (groups already expanded by
 * [expandTargetsToFixtureKeys]); null means the whole programmer, which is the historical
 * behaviour and still the default.
 */
internal fun collectProgrammerRecording(
    state: State,
    source: RecordSource,
    mask: Set<PropertyMaskGroup>?,
    includeFx: Boolean,
    targets: Set<String>? = null,
): ProgrammerRecording {
    val fixtures = state.show.fixtures
    val scope = targets?.takeIf { it.isNotEmpty() }

    if (source == RecordSource.STAGE_SNAPSHOT) {
        // This branch never reaches `collectProgrammerEntries`, so it does not inherit that
        // function's scope filter and has to apply its own — without this, a scoped Record from
        // "Whole stage" would silently capture the entire rig.
        val captured = captureCurrentState(state)
        val rows = ArrayList<CuePropertyAssignmentDto>(captured.propertyAssignments.size)
        val skipped = ArrayList<RecordSkip>()
        for (row in captured.propertyAssignments) {
            // Scope before mask: an operator who narrowed by fixture should see the reason they
            // chose, not an attribute reason that happens to also apply.
            if (!targetInScope(fixtures, row.target, scope)) {
                skipped += RecordSkip(
                    row.targetKey, row.propertyName, reason = RecordSkipReason.OUT_OF_SCOPE,
                )
                continue
            }
            if (!maskAllows(mask, maskGroupForRow(fixtures, row))) {
                skipped += RecordSkip(
                    row.targetKey, row.propertyName, reason = RecordSkipReason.MASKED_OUT,
                )
                continue
            }
            rows += row
        }
        return ProgrammerRecording(
            rows = renumber(rows),
            adHocEffects = if (includeFx) {
                captured.adHocEffects.filter {
                    maskAllows(mask, maskGroupForAdHoc(fixtures, it)) &&
                        targetInScope(fixtures, it.target, scope)
                }
            } else emptyList(),
            groupRowsEmitted = rows.count { it.targetType == TargetRef.Group.TYPE },
            skipped = skipped,
            // A stage snapshot describes what is *on stage*, which is not the same as what the
            // programmer's stack holds — the output may be coming from a live cue the operator never
            // touched. `captureCurrentState` reconstructs layers from the running effects instead.
            layers = captured.layers,
        )
    }

    val (entries, entrySkips) = collectProgrammerEntries(state, source, mask, scope)
    val collapsed = collapseRecordingToAssignments(entries, fixtures)

    val bandEffects = if (includeFx) {
        state.show.fxEngine.getActiveEffects()
            .filter { FxEngine.isProgrammerFxPriority(it.priority) }
    } else emptyList()
    val adHoc = fxInstancesToCueChildren(bandEffects, mask, fixtures, scope)

    // **`ALL` means flatten; `TOUCHED` means save the structure.** The distinction is not a
    // convenience — emitting layers *and* the rows they cook to would put both representations of
    // the same keys into one cue. The composed output would be identical (cook overlays local rows
    // last), but the cue would be permanently detached from the Look: a later Look edit would move
    // the layer and be immediately overridden by the frozen local row. Silently losing the touring
    // behaviour is the worst available outcome, so the two sources are kept explicitly apart.
    //
    // The mechanism underneath is `touched`: layer-materialised slots carry `touched = false`, so
    // `collectProgrammerEntries(TOUCHED)` skips a key nothing local covers, while `ALL` picks it up.
    // So the rows are already right for each source; only `layers` has to differ.
    val layers: List<CueLayerDto> = if (source == RecordSource.ALL) {
        emptyList()
    } else {
        state.show.programmerStore.layers
            .filterNot { it.isPreview }
            .map { it.toCueLayerDto() }
    }

    return ProgrammerRecording(
        rows = collapsed.rows,
        layers = layers,
        adHocEffects = adHoc,
        groupRowsEmitted = collapsed.groupRows,
        skipped = entrySkips + collapsed.skipped,
    )
}

private fun renumber(rows: List<CuePropertyAssignmentDto>): List<CuePropertyAssignmentDto> =
    rows.mapIndexed { index, row -> if (row.sortOrder == index) row else row.copy(sortOrder = index) }

/**
 * The mask group of a stored cue assignment row, resolved against its reference fixture (the
 * first member for group rows). Null when nothing resolves — the caller decides what that
 * means, and for the destructive `UPDATE_EXISTING` pass it deliberately means "leave alone".
 */
internal fun maskGroupForRow(fixtures: Fixtures, row: CuePropertyAssignmentDto): PropertyMaskGroup? {
    val fixture = referenceFixtureFor(fixtures, row.target) ?: return null
    return maskGroupForProperty(fixture, row.propertyName)
}

private fun maskGroupForAdHoc(fixtures: Fixtures, effect: CueAdHocEffectDto): PropertyMaskGroup? {
    val propertyName = effect.propertyName ?: return null
    val fixture = referenceFixtureFor(fixtures, effect.target) ?: return null
    return maskGroupForProperty(fixture, propertyName)
}

private fun referenceFixtureFor(fixtures: Fixtures, target: TargetRef): GroupableFixture? = try {
    when (target) {
        is TargetRef.Group -> fixtures.untypedGroup(target.key).fixtures.firstOrNull()
        is TargetRef.Fixture -> fixtures.untypedGroupableFixture(target.key)
    }
} catch (_: Exception) {
    null
}

/**
 * Turn running FX instances into cue FX children, reusing the shape `captureCurrentState`
 * emits so a cue recorded from the programmer and one snapshotted from the stage describe
 * their effects identically.
 *
 * **Effects belonging to a programmer layer are skipped**, not collapsed into anything. The layer
 * itself is recorded (see [ProgrammerRecording.layers]) and re-spawns its own effects when the cue
 * fires, so emitting them here as well would put two contributors on one key — and the ad-hoc copy
 * would be detached from the Look, so a later Look edit would no longer move it. That is the same
 * double-representation trap [RecordSource.ALL] avoids on the value side.
 *
 * Everything else — a manually applied effect, a busked one — becomes an ad-hoc child.
 */
internal fun fxInstancesToCueChildren(
    instances: List<FxInstance>,
    mask: Set<PropertyMaskGroup>?,
    fixtures: Fixtures,
    /**
     * Fixture scope, as [targetInScope] reads it. An effect is captured only when every head it
     * drives is in the selection: "record just these two heads" that quietly wrote a whole-rig
     * chase into the cue would be a worse surprise than dropping the chase and saying so.
     */
    scope: Set<String>? = null,
): List<CueAdHocEffectDto> {
    if (instances.isEmpty()) return emptyList()
    val adHocEffects = ArrayList<CueAdHocEffectDto>()

    for (effect in instances) {
        // Owned by a layer: recorded as part of that layer, not as a loose child.
        if (effect.programmerLayerId != null) continue

        val targetType = if (effect.isGroupEffect) TargetRef.Group.TYPE else TargetRef.Fixture.TYPE
        val targetKey = effect.target.targetKey
        val target = TargetRef.of(targetType, targetKey)

        if (!targetInScope(fixtures, target, scope)) continue

        // An FX is masked by the property it drives, so "record only the colours" doesn't drag
        // a position wave along with them.
        if (mask != null) {
            val fixture = referenceFixtureFor(fixtures, target)
            val group = fixture?.let { maskGroupForProperty(it, effect.target.propertyName) }
            if (!maskAllows(mask, group)) continue
        }

        adHocEffects.add(
                CueAdHocEffectDto(
                    targetType = targetType,
                    targetKey = targetKey,
                    effectType = effect.effectTypeId,
                    category = categoryFromPropertyName(effect.target.propertyName),
                    propertyName = effect.target.propertyName,
                    beatDivision = effect.timing.beatDivision,
                    blendMode = effect.blendMode.name,
                    distribution = effect.distributionStrategy.javaClass.simpleName,
                    phaseOffset = effect.phaseOffset,
                    elementMode = if (effect.isGroupEffect) effect.elementMode.name else null,
                    elementFilter = if (effect.elementFilter != uk.me.cormack.lighting7.fx.ElementFilter.ALL) {
                        effect.elementFilter.name
                    } else null,
                    stepTiming = if (effect.stepTiming != effect.effect.defaultStepTiming) {
                        effect.stepTiming
                    } else null,
                    parameters = effect.effect.parameters,
                    sortOrder = adHocEffects.size,
                )
        )
    }

    return adHocEffects
}

/**
 * The value a property is currently putting on the wire, read from the controller buffer with
 * the programmer's channel sideband laid over it.
 *
 * Used to lift a sideband channel slot into a whole-property value: the slot names one channel,
 * but a colour bundle or a position pair needs its siblings' current values too.
 *
 * Deliberately does *not* consult programmer property entries — the caller has already
 * preferred those, and re-reading them here would make the precedence rule live in two places.
 */
internal fun readOutputPropertyValue(
    state: State,
    fixture: GroupableFixture,
    propertyName: String,
    universe: Int,
): CueAssignmentResolver.PropertyValue? {
    val canonical = canonicalPropertyName(propertyName)
    if (canonical.equals("position", ignoreCase = true)) {
        val positioned = fixture as? WithPosition ?: return null
        val pan = positioned.pan as? DmxSlider
        val tilt = positioned.tilt as? DmxSlider
        if (pan == null || tilt == null) return null
        return CueAssignmentResolver.PropertyValue.Position(
            channelOutput(state, universe, pan.channelNo),
            channelOutput(state, universe, tilt.channelNo),
        )
    }
    return when (
        val raw = uk.me.cormack.lighting7.fx.PropertyChannelWriter
            .resolveProperty(fixture, canonical)?.value
    ) {
        is DmxColour -> CueAssignmentResolver.PropertyValue.Colour(
            readOutputColour(state, fixture as? Fixture, raw, universe),
        )
        is DmxFixtureSetting<*> ->
            CueAssignmentResolver.PropertyValue.Setting(channelOutput(state, universe, raw.channelNo))
        is DmxSlider ->
            CueAssignmentResolver.PropertyValue.Slider(channelOutput(state, universe, raw.channelNo))
        else -> null
    }
}

/**
 * The fixture's current output colour (RGB plus bundled W/A/UV), read from the controller
 * buffer with the programmer sideband on top.
 *
 * Shared with `ChannelSocket`'s `updateChannel` shim, which layers its own
 * "prefer an existing programmer colour entry" rule over this so successive component drags
 * compose. Keeping the wire read in one place stops the two from drifting on which channels
 * count as part of the colour.
 */
internal fun readOutputColour(
    state: State,
    fixture: Fixture?,
    dmxColour: DmxColour,
    universe: Int,
): ExtendedColour {
    fun bundled(category: PropertyCategory): UByte {
        val prop = fixture?.fixtureProperties?.find { it.bundleWithColour && it.category == category }
            ?: return 0u
        val dmx = try {
            prop.classProperty.call(fixture) as? DmxSlider
        } catch (_: Exception) {
            null
        } ?: return 0u
        return channelOutput(state, universe, dmx.channelNo)
    }
    return ExtendedColour(
        Color(
            channelOutput(state, universe, dmxColour.redSlider.channelNo).toInt(),
            channelOutput(state, universe, dmxColour.greenSlider.channelNo).toInt(),
            channelOutput(state, universe, dmxColour.blueSlider.channelNo).toInt(),
        ),
        bundled(PropertyCategory.WHITE),
        bundled(PropertyCategory.AMBER),
        bundled(PropertyCategory.UV),
    )
}

/**
 * One channel's current output: the programmer's sideband slot if it holds one, else the
 * controller's buffer. Park is deliberately not consulted — a parked channel's *underlying*
 * value is what a cue should record, matching how the Layer 4 snapshot reads.
 */
private fun channelOutput(state: State, universe: Int, channelNo: Int): UByte {
    state.show.programmerStore.getChannel(universe, channelNo)?.let { return it }
    val controller = try {
        state.show.fixtures.controllerOrNull(Universe(0, universe))
    } catch (_: Exception) {
        logger.debug("no controller for universe {} while reading channel {}", universe, channelNo)
        null
    }
    return controller?.currentValues?.get(channelNo) ?: 0u
}
