package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.fx.PersistedFixtureReferenceValidator
import uk.me.cormack.lighting7.fx.speedMasterUuidOrNull
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID

/**
 * Pure evaluator that maps a [BindingTarget] onto an [AssignmentHealth] given a snapshot
 * of the current project state. Stateless — callers assemble a [Context] per evaluation
 * batch and throw it away. Fixture / group reference validation delegates to
 * [PersistedFixtureReferenceValidator] so cue-authoring's rules (property
 * canonicalisation, `"position"` compound, group-member probing) stay the one source of
 * truth.
 *
 * Stack / cue / bank variants live here because they're surface-specific — cue-authoring
 * never references a cue stack or a device bank by ID.
 *
 * [BindingTarget.Flash] recurses on its inner target so a flash on a now-deleted fixture
 * surfaces the same `MissingFixture` a continuous binding would.
 */
object BindingHealthEvaluator {

    /**
     * Bundle of snapshots needed to evaluate every target variant. Assembled once by
     * [ControlSurfaceBindingService] per rebuild / query so we don't re-read the DB or
     * re-scan fixtures per binding.
     *
     * @param fixtures current patch; delegated to `PersistedFixtureReferenceValidator`
     * @param validStackIds IDs of cue stacks that currently exist in the project
     * @param validCueIds IDs of cues that currently exist in the project
     * @param deviceTypes device profiles — used for [BindingTarget.SetBank] bank validation
     * @param validSpeedMasterUuids uuids currently in the bank; master 1 is addressed by a
     *   null uuid and so never needs to appear here
     * @param validStackUuids uuids of the same stacks as [validStackIds] — a stack variant with
     *   a uuid is judged by this set and its int is ignored
     * @param validCueUuids likewise for cues
     * @param selectionProperties every property name some patched fixture declares that a
     *   continuous control can write (sliders and colour) — the vocabulary of
     *   [BindingTarget.SelectionProperty]; see [selectionPropertiesOf]
     * @param colourProperties the subset of [selectionProperties] some patched fixture declares as
     *   a **colour** — what a [ColourAxis] on a selection or encoder-bank target is judged against
     *   at the write boundary (`refuseAxisOnNonColour`); see [colourPropertiesOf]. Health itself
     *   does not read it: an axis is refused when it is bound, never reported afterwards, and a
     *   head that stops being a colour under a re-patch simply drops the move.
     * @param validLookUuids Looks that exist in the project
     * @param looksNeedingSelection the subset of [validLookUuids] carrying a **deferred effect**, so
     *   an [BindingTarget.ApplyLook] on one has no own targets to press onto. A separate set rather
     *   than a filter on the first, because "does it exist" and "can a button press it" are
     *   different questions with different answers and different fixes — and the second cannot be
     *   derived from a set of uuids at all.
     * @param validTemplateUuids templates that exist in the project
     * @param validPadUuids busk pads that exist — a pad dragged off a page, or swept by its
     *   record's delete, is gone
     * @param validPageUuids busk pages that exist
     */
    data class Context(
        val fixtures: Fixtures,
        val validStackIds: Set<Int>,
        val validCueIds: Set<Int>,
        val deviceTypes: List<ControlSurfaceRegistry.DeviceTypeInfo>,
        val validSpeedMasterUuids: Set<UUID> = emptySet(),
        val validStackUuids: Set<UUID> = emptySet(),
        val validCueUuids: Set<UUID> = emptySet(),
        val selectionProperties: Set<String> = emptySet(),
        val colourProperties: Set<String> = emptySet(),
        val validLookUuids: Set<UUID> = emptySet(),
        val looksNeedingSelection: Set<UUID> = emptySet(),
        val validTemplateUuids: Set<UUID> = emptySet(),
        val validPadUuids: Set<UUID> = emptySet(),
        val validPageUuids: Set<UUID> = emptySet(),
    )

    /**
     * The property names a [BindingTarget.SelectionProperty] may carry: every name that at least
     * one patched fixture declares and that [PropertyChannelResolver] would accept on a fader —
     * sliders and colour, never a setting. Computed once per context, not per binding.
     */
    fun selectionPropertiesOf(fixtures: Fixtures): Set<String> =
        fixtures.fixtures.flatMapTo(HashSet()) { fixture ->
            fixture.fixtureProperties.map { it.name }
                .filter { PropertyChannelResolver.describeFixtureProperty(fixture, it).isNotEmpty() }
        }

    /**
     * The property names some patched fixture declares as a **colour** — the subset of
     * [selectionPropertiesOf] a [ColourAxis] may be bound against on a target that names no head.
     */
    fun colourPropertiesOf(fixtures: Fixtures): Set<String> =
        fixtures.fixtures.flatMapTo(HashSet()) { fixture ->
            fixture.fixtureProperties.map { it.name }.filter {
                PropertyChannelResolver.describePropertyRead(fixture, it) is PropertyChannelResolver.PropertyRead.Colour
            }
        }

    fun evaluate(target: BindingTarget, context: Context): AssignmentHealth = when (target) {
        is BindingTarget.FixtureProperty -> PersistedFixtureReferenceValidator.validateTargetedReference(
            fixtures = context.fixtures,
            target = TargetRef.Fixture(target.fixtureKey),
            propertyName = target.propertyName,
        )
        is BindingTarget.GroupProperty -> PersistedFixtureReferenceValidator.validateTargetedReference(
            fixtures = context.fixtures,
            target = TargetRef.Group(target.groupName),
            propertyName = target.propertyName,
        )
        is BindingTarget.CueStackGo -> checkStack(target.stackId, target.stackUuid, context)
        is BindingTarget.CueStackBack -> checkStack(target.stackId, target.stackUuid, context)
        is BindingTarget.CueStackPause -> checkStack(target.stackId, target.stackUuid, context)
        is BindingTarget.FireCue -> checkCue(target.cueId, target.cueUuid, context)
        is BindingTarget.SelectionProperty ->
            if (target.propertyName in context.selectionProperties) AssignmentHealth.Ok
            else AssignmentHealth.UnknownProperty(target.propertyName)
        is BindingTarget.SelectTarget -> checkTarget(target.target, context)
        // A strip is judged by its group or fixture alone. The properties its controls derive
        // are the encoder bank's business, and an attribute no selected head declares makes the
        // encoder read unbound — not the strip dead.
        is BindingTarget.Strip -> checkTarget(target.target, context)
        is BindingTarget.EncoderBankSet ->
            if (target.propertyName in context.selectionProperties) AssignmentHealth.Ok
            else AssignmentHealth.UnknownProperty(target.propertyName)
        BindingTarget.ClearSelection -> AssignmentHealth.Ok
        BindingTarget.LocateSelection -> AssignmentHealth.Ok
        // Two failures, not one, because the fixes differ: a Look that is gone has to be rebound,
        // one that has gained a deferred effect only has to be given targets. Both drop the press.
        is BindingTarget.ApplyLook -> when (val uuid = uuidOrNull(target.lookUuid)) {
            null -> AssignmentHealth.MissingLook(target.lookUuid)
            !in context.validLookUuids -> AssignmentHealth.MissingLook(target.lookUuid)
            in context.looksNeedingSelection -> AssignmentHealth.LookNeedsSelection(target.lookUuid)
            else -> AssignmentHealth.Ok
        }
        // A template's *emptiness* is not a health question: a generic template pressed with
        // nothing selected is a dropped press, which is a fact about the selection at that moment
        // and not about the binding — the same line `SelectionProperty` draws.
        is BindingTarget.PressTemplate ->
            uuidHealth(target.templateUuid, context.validTemplateUuids) { AssignmentHealth.MissingTemplate(it) }
        // The pad, not its record: a pad whose record was deleted is itself swept in the same
        // transaction, so "the pad exists" already answers "its record does".
        is BindingTarget.PressPad ->
            uuidHealth(target.padUuid, context.validPadUuids) { AssignmentHealth.MissingPad(it) }
        is BindingTarget.BuskPageSet ->
            uuidHealth(target.pageUuid, context.validPageUuids) { AssignmentHealth.MissingPage(it) }
        // Page-agnostic: they move along whatever pages there are, and a project with none simply
        // has nowhere to move to — not a dead binding.
        BindingTarget.BuskPageNext -> AssignmentHealth.Ok
        BindingTarget.BuskPagePrev -> AssignmentHealth.Ok
        is BindingTarget.Unknown -> AssignmentHealth.UnknownTarget(target.targetType)
        is BindingTarget.SetBank -> {
            val profile = context.deviceTypes.firstOrNull { it.typeKey == target.deviceTypeKey }
            if (profile == null || profile.banks.none { it.id == target.bank }) {
                AssignmentHealth.UnknownBank(target.deviceTypeKey, target.bank)
            } else {
                AssignmentHealth.Ok
            }
        }
        is BindingTarget.Flash -> evaluate(target.target, context)
        is BindingTarget.SpeedMasterBpm -> checkSpeedMaster(target.masterUuid, context)
        is BindingTarget.SpeedMasterTap -> checkSpeedMaster(target.masterUuid, context)
        BindingTarget.Blackout -> AssignmentHealth.Ok
        BindingTarget.GrandMasterToggle -> AssignmentHealth.Ok
    }

    /**
     * Uuid first: a row that carries one is judged by it alone, so a clone whose ints point at
     * the source project's rows still reads healthy when its remapped uuid resolves — and a row
     * whose uuid names a deleted stack is dead even if the stale int happens to collide with a
     * live one. Only a pre-v11 row with no uuid falls back to the int.
     */
    private fun checkStack(stackId: Int, stackUuid: String?, context: Context): AssignmentHealth {
        val uuid = stackUuid?.let(::uuidOrNull)
        val ok = if (stackUuid != null) uuid != null && uuid in context.validStackUuids
        else stackId in context.validStackIds
        return if (ok) AssignmentHealth.Ok else AssignmentHealth.MissingStack(stackId)
    }

    private fun checkCue(cueId: Int, cueUuid: String?, context: Context): AssignmentHealth {
        val uuid = cueUuid?.let(::uuidOrNull)
        val ok = if (cueUuid != null) uuid != null && uuid in context.validCueUuids
        else cueId in context.validCueIds
        return if (ok) AssignmentHealth.Ok else AssignmentHealth.MissingCue(cueId)
    }

    /** A select button needs its group or fixture to exist; no property is involved. */
    private fun checkTarget(target: CueTargetDto, context: Context): AssignmentHealth =
        when (val ref = TargetRef.ofOrNull(target.type, target.key)) {
            is TargetRef.Fixture ->
                if (runCatching { context.fixtures.untypedFixture(ref.key) }.isSuccess) AssignmentHealth.Ok
                else AssignmentHealth.MissingFixture(ref.key)
            is TargetRef.Group ->
                if (runCatching { context.fixtures.untypedGroup(ref.key) }.isSuccess) AssignmentHealth.Ok
                else AssignmentHealth.MissingGroup(ref.key)
            null -> AssignmentHealth.MissingFixture(target.key)
        }

    private fun uuidOrNull(raw: String): UUID? = try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Shared shape for "does this raw uuid resolve into [valid]" — [PressTemplate]/[PressPad]/
     * [BuskPageSet] all reduce to this, differing only in which set and which [AssignmentHealth]
     * arm names the miss. One helper rather than three copies, so a future arm reusing the wrong
     * `valid*Uuids` set at the call site is a value passed at the call, not a re-typed condition
     * that would compile silently wrong.
     */
    private fun uuidHealth(raw: String, valid: Set<UUID>, missing: (String) -> AssignmentHealth): AssignmentHealth =
        if (uuidOrNull(raw)?.let { it in valid } == true) AssignmentHealth.Ok else missing(raw)

    /**
     * Null means master 1, which always exists — so an unkeyed binding is always healthy.
     * A malformed uuid is reported dead rather than parsed leniently: it can never resolve
     * to anything, and saying so is more useful than a binding that quietly does nothing.
     */
    private fun checkSpeedMaster(masterUuid: String?, context: Context): AssignmentHealth {
        if (masterUuid == null) return AssignmentHealth.Ok
        val parsed = speedMasterUuidOrNull(masterUuid)
        return if (parsed != null && parsed in context.validSpeedMasterUuids) AssignmentHealth.Ok
        else AssignmentHealth.MissingSpeedMaster(masterUuid)
    }
}
