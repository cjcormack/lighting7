package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.fx.PersistedFixtureReferenceValidator
import uk.me.cormack.lighting7.fx.speedMasterUuidOrNull
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
     */
    data class Context(
        val fixtures: Fixtures,
        val validStackIds: Set<Int>,
        val validCueIds: Set<Int>,
        val deviceTypes: List<ControlSurfaceRegistry.DeviceTypeInfo>,
        val validSpeedMasterUuids: Set<UUID> = emptySet(),
    )

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
        is BindingTarget.CueStackGo -> checkStack(target.stackId, context)
        is BindingTarget.CueStackBack -> checkStack(target.stackId, context)
        is BindingTarget.CueStackPause -> checkStack(target.stackId, context)
        is BindingTarget.FireCue ->
            if (target.cueId in context.validCueIds) AssignmentHealth.Ok
            else AssignmentHealth.MissingCue(target.cueId)
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

    private fun checkStack(stackId: Int, context: Context): AssignmentHealth =
        if (stackId in context.validStackIds) AssignmentHealth.Ok
        else AssignmentHealth.MissingStack(stackId)

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
