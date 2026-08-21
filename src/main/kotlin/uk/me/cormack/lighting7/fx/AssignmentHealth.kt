package uk.me.cormack.lighting7.fx

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Health status for a persisted fixture-reference row — cue property assignment, preset
 * property assignment, or MIDI binding target. Surfaced in REST responses so the UI can
 * mark dead rows instead of silently dropping them at apply time.
 *
 * See `docs/plans/completed/cue-authoring-unification-plan.md` §"Phase 6" for the motivating workflow and
 * `docs/plans/completed/control-surface-plan.md` §"Phase 7" for the sibling binding-health pattern this
 * intentionally mirrors — both subsystems consume the same ADT via
 * [PersistedFixtureReferenceValidator] and [uk.me.cormack.lighting7.midi.BindingHealthEvaluator].
 * Cue / preset consumers see [Ok] / [MissingFixture] / [MissingGroup] / [MissingProperty], plus
 * the reference variants [MissingPalette] / [MissingPaletteEntry] on rows whose value is a `ref:`
 * (see [parsePaletteRef]); the binding-specific variants are produced only by the surface
 * evaluator.
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
     * The row's value is `ref:{paletteUuid}` but no palette with that uuid exists — it was
     * deleted with `?force=true`, or the row arrived from an import whose palette folder was
     * incomplete. The row is skipped at apply rather than guessed at.
     */
    @Serializable
    @SerialName("missingPalette")
    data class MissingPalette(val paletteUuid: String) : AssignmentHealth()

    /**
     * The referenced Look exists but holds no row covering this target and property, so there is
     * nothing to resolve to.
     *
     * This is now the *only* diagnosis for a reference that finds nothing. There used to be a
     * `PaletteTypeMismatch` arm naming a wrong-type reference as the cause, and it has no producer
     * left: a Look declares no attribute type — its families are derived from its rows, and one
     * spanning colour and position is entirely legitimate — so "wrong type" is no longer a
     * coherent complaint. The symptom is reported instead of a cause that cannot exist.
     */
    @Serializable
    @SerialName("missingPaletteEntry")
    data class MissingPaletteEntry(
        val paletteUuid: String,
        val targetKey: String,
        val propertyName: String,
    ) : AssignmentHealth()

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

    /**
     * A `speedMasterBpm` / `speedMasterTap` binding names a master that no longer exists in
     * the bank. Control-surface-only: an *effect* whose master vanished degrades to master 1
     * and keeps running, but a binding that silently retuned the global tempo instead of the
     * master the operator chose would be worse than one that reports itself dead.
     */
    @Serializable
    @SerialName("missingSpeedMaster")
    data class MissingSpeedMaster(val masterUuid: String) : AssignmentHealth()
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
    is AssignmentHealth.MissingPalette -> "missing palette ${health.paletteUuid}"
    is AssignmentHealth.MissingPaletteEntry ->
        "palette ${health.paletteUuid} has no entry for '${health.propertyName}' on '${health.targetKey}'"
    is AssignmentHealth.MissingStack -> "missing cue stack id=${health.stackId}"
    is AssignmentHealth.MissingCue -> "missing cue id=${health.cueId}"
    is AssignmentHealth.UnknownBank ->
        "unknown bank '${health.bankId}' for device '${health.deviceTypeKey}'"
    is AssignmentHealth.MissingSpeedMaster -> "missing speed master ${health.masterUuid}"
}
