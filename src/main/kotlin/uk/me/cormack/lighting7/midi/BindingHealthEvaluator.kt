package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.fx.AssignmentHealth
import uk.me.cormack.lighting7.fx.PersistedFixtureReferenceValidator
import uk.me.cormack.lighting7.show.Fixtures

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
     */
    data class Context(
        val fixtures: Fixtures,
        val validStackIds: Set<Int>,
        val validCueIds: Set<Int>,
        val deviceTypes: List<ControlSurfaceRegistry.DeviceTypeInfo>,
    )

    fun evaluate(target: BindingTarget, context: Context): AssignmentHealth = when (target) {
        is BindingTarget.FixtureProperty -> PersistedFixtureReferenceValidator.validateTargetedReference(
            fixtures = context.fixtures,
            targetType = "fixture",
            targetKey = target.fixtureKey,
            propertyName = target.propertyName,
        )
        is BindingTarget.GroupProperty -> PersistedFixtureReferenceValidator.validateTargetedReference(
            fixtures = context.fixtures,
            targetType = "group",
            targetKey = target.groupName,
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
        BindingTarget.Blackout -> AssignmentHealth.Ok
        BindingTarget.GrandMasterToggle -> AssignmentHealth.Ok
    }

    private fun checkStack(stackId: Int, context: Context): AssignmentHealth =
        if (stackId in context.validStackIds) AssignmentHealth.Ok
        else AssignmentHealth.MissingStack(stackId)
}
