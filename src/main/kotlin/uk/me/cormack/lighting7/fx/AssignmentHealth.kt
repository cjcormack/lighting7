package uk.me.cormack.lighting7.fx

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Health status for a persisted fixture-reference row — cue property assignment, preset
 * property assignment, or (future) MIDI binding target. Surfaced in REST responses so the
 * UI can mark dead rows instead of silently dropping them at apply time.
 *
 * See `docs/cue-authoring-unification-plan.md` §"Phase 6" for the motivating workflow and
 * `docs/control-surface-plan.md` §"Phase 7" for the sibling binding-health pattern this
 * intentionally mirrors — both subsystems consume the same ADT via
 * [PersistedFixtureReferenceValidator].
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
}
