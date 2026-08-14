package uk.me.cormack.lighting7.midi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.fx.MasterClock

/**
 * What a bound [ControlDescriptor] drives when the user moves / presses it.
 *
 * Persisted as a discriminated JSON union in `DaoControlSurfaceBindings.targetPayload`
 * so the full sealed hierarchy can be migrated without schema changes. The discriminator
 * lives in a `type` field set by [SerialName] on each subtype.
 *
 * Targets fall into three rough families:
 *   - **Continuous** ([FixtureProperty], [GroupProperty], [SpeedMasterBpm]) — fader /
 *     encoder movements map to a Layer 4 property write (Phase 3) or a tempo write.
 *   - **Discrete** ([CueStackGo], [CueStackBack], [CueStackPause], [FireCue],
 *     [SpeedMasterTap]) — button press invokes a service call.
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

    /**
     * Drive a speed master's tempo from a fader / encoder. [masterUuid] null means master 1,
     * matching the `speedMasters.*` WS family — and a **uuid** rather than an int id, so the
     * binding survives the clone and cross-install import that the int-id cue/stack variants
     * above do not (`FU-SYNC-BINDING-PAYLOAD-UUIDS`).
     *
     * The control's 0..127 maps onto [minBpm]..[maxBpm] rather than the clock's full
     * 20..300: absolute encoders here have 128 steps, and spreading those over the whole
     * range gives ~2.2 BPM a step, too coarse to trim a tempo with. The default window is
     * the musically useful middle; widen it per binding when you need to.
     */
    @Serializable
    @SerialName("speedMasterBpm")
    data class SpeedMasterBpm(
        val masterUuid: String? = null,
        val minBpm: Double = DEFAULT_MIN_BPM,
        val maxBpm: Double = DEFAULT_MAX_BPM,
    ) : BindingTarget() {
        init {
            require(minBpm < maxBpm) { "SpeedMasterBpm minBpm must be below maxBpm" }
            require(minBpm >= MasterClock.MIN_BPM && maxBpm <= MasterClock.MAX_BPM) {
                "SpeedMasterBpm range must sit within ${MasterClock.MIN_BPM}..${MasterClock.MAX_BPM}"
            }
        }

        companion object {
            const val DEFAULT_MIN_BPM = 60.0
            const val DEFAULT_MAX_BPM = 180.0
        }
    }

    /** Tap a speed master's tempo on button press ([masterUuid] null → master 1). */
    @Serializable
    @SerialName("speedMasterTap")
    data class SpeedMasterTap(val masterUuid: String? = null) : BindingTarget()
}

/** JSON codec for [BindingTarget] payloads. Stable discriminator = `type`. */
val BindingTargetJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = false
}
