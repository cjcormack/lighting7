package uk.me.cormack.lighting7.models

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
 * [uk.me.cormack.lighting7.fx.PersistedFixtureReferenceValidator] and [uk.me.cormack.lighting7.midi.BindingHealthEvaluator].
 * Cue consumers see [Ok] / [MissingFixture] / [MissingGroup] / [MissingProperty]; the
 * binding-specific variants are produced only by the surface evaluator.
 *
 * Two reference variants, `MissingPalette` and `MissingPaletteEntry`, retired with the `ref:` value
 * grammar in session 4 of the looks-and-layers plan, along with the `PaletteTypeMismatch` arm that
 * had already gone in session 2. A row can no longer *hold* a reference, so a reference that
 * resolves to nothing is not a state this ADT has to describe: a layer naming a deleted Look simply
 * contributes nothing, and the Look-delete guard is an indexed FK query rather than a health
 * diagnosis.
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

    /**
     * A `speedMasterBpm` / `speedMasterTap` binding names a master that no longer exists in
     * the bank. Control-surface-only: an *effect* whose master vanished degrades to master 1
     * and keeps running, but a binding that silently retuned the global tempo instead of the
     * master the operator chose would be worse than one that reports itself dead.
     */
    @Serializable
    @SerialName("missingSpeedMaster")
    data class MissingSpeedMaster(val masterUuid: String) : AssignmentHealth()

    /**
     * A `selectionProperty` binding names a property no fixture in the patch declares as a
     * continuous (slider / colour) property, so no selection could ever give it something to
     * write. Control-surface-only. A property *some* fixtures lack is not this: the write
     * simply skips those heads.
     */
    @Serializable
    @SerialName("unknownProperty")
    data class UnknownProperty(val propertyName: String) : AssignmentHealth()

    /**
     * A `applyLook` binding names a Look that no longer exists in the project. Control-surface-only,
     * and keyed by uuid because that is what the binding carries — an int id would not survive the
     * clone the uuid exists for.
     */
    @Serializable
    @SerialName("missingLook")
    data class MissingLook(val lookUuid: String) : AssignmentHealth()

    /** A `pressTemplate` binding names a template that no longer exists. Control-surface-only. */
    @Serializable
    @SerialName("missingTemplate")
    data class MissingTemplate(val templateUuid: String) : AssignmentHealth()

    /**
     * A `pressPad` binding names a busk pad that no longer exists — the page was deleted, the pad
     * was dragged off it, or the record behind it was deleted and swept its pads with it.
     * Control-surface-only.
     */
    @Serializable
    @SerialName("missingPad")
    data class MissingPad(val padUuid: String) : AssignmentHealth()

    /** A `buskPageSet` binding names a busk page that no longer exists. Control-surface-only. */
    @Serializable
    @SerialName("missingPage")
    data class MissingPage(val pageUuid: String) : AssignmentHealth()

    /**
     * An `applyLook` binding names a Look that exists but has gained a **deferred effect**, so it
     * has no own targets to press onto and a button has no selection to supply. Refused at bind
     * time; this is the state a Look edited afterwards falls into. Control-surface-only.
     *
     * Distinct from [MissingLook] because the fix is different: the Look is still there, and either
     * the effect is bound to targets or the button is rebound.
     */
    @Serializable
    @SerialName("lookNeedsSelection")
    data class LookNeedsSelection(val lookUuid: String) : AssignmentHealth()

    /**
     * The binding's persisted payload carries a `type` discriminator this build does not know
     * — an archive written by a newer desk. The row is kept, dead and rebindable, rather than
     * refusing the project. Control-surface-only.
     */
    @Serializable
    @SerialName("unknownTarget")
    data class UnknownTarget(val targetType: String) : AssignmentHealth()
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
    is AssignmentHealth.MissingSpeedMaster -> "missing speed master ${health.masterUuid}"
    is AssignmentHealth.UnknownProperty -> "no patched fixture has property '${health.propertyName}'"
    is AssignmentHealth.UnknownTarget -> "unknown binding target type '${health.targetType}'"
    is AssignmentHealth.MissingLook -> "missing Look ${health.lookUuid}"
    is AssignmentHealth.MissingTemplate -> "missing template ${health.templateUuid}"
    is AssignmentHealth.MissingPad -> "missing busk pad ${health.padUuid}"
    is AssignmentHealth.MissingPage -> "missing busk page ${health.pageUuid}"
    is AssignmentHealth.LookNeedsSelection -> "Look ${health.lookUuid} has a deferred effect and needs a selection"
}
