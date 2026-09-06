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
        BindingTarget.ClearSelection -> AssignmentHealth.Ok
        BindingTarget.LocateSelection -> AssignmentHealth.Ok
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
