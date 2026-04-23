package uk.me.cormack.lighting7.fx

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Health status for a persisted fixture-reference row — cue property assignment, preset
 * property assignment, or MIDI binding target. Surfaced in REST responses so the UI can
 * mark dead rows instead of silently dropping them at apply time.
 *
 * See `docs/cue-authoring-unification-plan.md` §"Phase 6" for the motivating workflow and
 * `docs/control-surface-plan.md` §"Phase 7" for the sibling binding-health pattern this
 * intentionally mirrors — both subsystems consume the same ADT via
 * [PersistedFixtureReferenceValidator] and [uk.me.cormack.lighting7.midi.BindingHealthEvaluator].
 * Cue / preset consumers only ever see [Ok] / [MissingFixture] / [MissingGroup] /
 * [MissingProperty]; the binding-specific variants are produced only by the surface evaluator.
 */
@Serializable
sealed class AssignmentHealth {
    @Serializable
    @SerialName("ok")
    data object Ok : AssignmentHealth()

    /** The target fixture key is no longer registered in the current patch. */
    @Serializable
    @SerialName("missingFixture")
    data class MissingFixture(val fixtureKey: String) : AssignmentHealth()

    /** The target group name is no longer registered, or has no fixture members. */
    @Serializable
    @SerialName("missingGroup")
    data class MissingGroup(val groupName: String) : AssignmentHealth()

    /**
     * The target exists, but [propertyName] is not a known annotated property on it.
     * [targetKey] echoes the original fixture / group key for UI context.
     */
    @Serializable
    @SerialName("missingProperty")
    data class MissingProperty(val targetKey: String, val propertyName: String) : AssignmentHealth()

    /**
     * A cue stack referenced by a `cueStackGo` / `cueStackBack` / `cueStackPause` binding
     * no longer exists in the project. Control-surface-only.
     */
    @Serializable
    @SerialName("missingStack")
    data class MissingStack(val stackId: Int) : AssignmentHealth()

    /** A cue referenced by a `fireCue` binding no longer exists. Control-surface-only. */
    @Serializable
    @SerialName("missingCue")
    data class MissingCue(val cueId: Int) : AssignmentHealth()

    /**
     * A `setBank` binding references an unknown device type key or an unknown bank id
     * within that type (e.g. the profile was renamed or the bank was removed).
     * Control-surface-only.
     */
    @Serializable
    @SerialName("unknownBank")
    data class UnknownBank(val deviceTypeKey: String, val bankId: String) : AssignmentHealth()
}

/**
 * Operator-facing one-line description of a non-Ok [AssignmentHealth], used by log lines
 * and diagnostics. Returns `"ok"` for the happy path — callers filter ahead of the call
 * when they want to log only failures.
 */
fun describeAssignmentHealth(health: AssignmentHealth): String = when (health) {
    is AssignmentHealth.Ok -> "ok"
    is AssignmentHealth.MissingFixture -> "missing fixture '${health.fixtureKey}'"
    is AssignmentHealth.MissingGroup -> "missing group '${health.groupName}'"
    is AssignmentHealth.MissingProperty ->
        "missing property '${health.propertyName}' on '${health.targetKey}'"
    is AssignmentHealth.MissingStack -> "missing cue stack id=${health.stackId}"
    is AssignmentHealth.MissingCue -> "missing cue id=${health.cueId}"
    is AssignmentHealth.UnknownBank ->
        "unknown bank '${health.bankId}' for device '${health.deviceTypeKey}'"
}
