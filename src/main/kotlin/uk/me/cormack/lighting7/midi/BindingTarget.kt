package uk.me.cormack.lighting7.midi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a bound [ControlDescriptor] drives when the user moves / presses it.
 *
 * Persisted as a discriminated JSON union in `DaoControlSurfaceBindings.targetPayload`
 * so the full sealed hierarchy can be migrated without schema changes. The discriminator
 * lives in a `type` field set by [SerialName] on each subtype.
 *
 * Targets fall into three rough families:
 *   - **Continuous** ([FixtureProperty], [GroupProperty]) — fader / encoder movements map
 *     to a Layer 4 property write (Phase 3).
 *   - **Discrete** ([CueStackGo], [CueStackBack], [CueStackPause], [FireCue]) — button press
 *     invokes a cue-stack / cue-apply service call.
 *   - **Momentary / global / meta** ([Flash], [Blackout], [GrandMasterToggle], [SetBank]) —
 *     press / release change transport-level state.
 */
@Serializable
sealed class BindingTarget {
    /**
     * Write a continuous property (e.g. dimmer, UV, rgbColour) on a single fixture.
     * The value coming off the fader / encoder is scaled to the property's native range.
     */
    @Serializable
    @SerialName("fixtureProperty")
    data class FixtureProperty(
        val fixtureKey: String,
        val propertyName: String,
    ) : BindingTarget()

    /**
     * Write a continuous property on a fixture group. Writes fan out to members via the
     * group's property-aggregator semantics.
     */
    @Serializable
    @SerialName("groupProperty")
    data class GroupProperty(
        val groupName: String,
        val propertyName: String,
    ) : BindingTarget()

    /** Advance the named cue stack on button press. */
    @Serializable
    @SerialName("cueStackGo")
    data class CueStackGo(val stackId: Int) : BindingTarget()

    /** Step back in the named cue stack on button press. */
    @Serializable
    @SerialName("cueStackBack")
    data class CueStackBack(val stackId: Int) : BindingTarget()

    /** Pause / resume the named cue stack on button press. */
    @Serializable
    @SerialName("cueStackPause")
    data class CueStackPause(val stackId: Int) : BindingTarget()

    /** Fire a specific cue (by primary key) on button press. */
    @Serializable
    @SerialName("fireCue")
    data class FireCue(val cueId: Int) : BindingTarget()

    /**
     * Momentary "flash" write: on press, write [max] to the nested property via Layer 4;
     * on release, restore whatever was underneath. The nested target must be a
     * [FixtureProperty] or [GroupProperty] (constrained at bind time).
     */
    @Serializable
    @SerialName("flash")
    data class Flash(
        val target: BindingTarget,
        val max: Int = 255,
    ) : BindingTarget() {
        init {
            require(target is FixtureProperty || target is GroupProperty) {
                "Flash target must be FixtureProperty or GroupProperty"
            }
            require(max in 0..255) { "Flash max must be in 0..255" }
        }
    }

    /** Toggle global blackout (output scaler) on press. */
    @Serializable
    @SerialName("blackout")
    data object Blackout : BindingTarget()

    /** Toggle Grand Master (global intensity scaler) on press. */
    @Serializable
    @SerialName("grandMasterToggle")
    data object GrandMasterToggle : BindingTarget()

    /**
     * Switch the active bank for the given device on press. Device-side bank buttons
     * synthesise this target inside Phase 3 routing; users can also bind this to arbitrary
     * buttons to drive banks from anywhere.
     */
    @Serializable
    @SerialName("setBank")
    data class SetBank(
        val deviceTypeKey: String,
        val bank: String,
    ) : BindingTarget()
}

/** JSON codec for [BindingTarget] payloads. Stable discriminator = `type`. */
val BindingTargetJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = false
}
