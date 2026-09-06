package uk.me.cormack.lighting7.midi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.fx.MasterClock
import uk.me.cormack.lighting7.models.CueTargetDto

/**
 * What a bound [ControlDescriptor] drives when the user moves / presses it.
 *
 * Persisted as a discriminated JSON union in `DaoControlSurfaceBindings.targetPayload`
 * so the full sealed hierarchy can be migrated without schema changes. The discriminator
 * lives in a `type` field set by [SerialName] on each subtype.
 *
 * Targets fall into three rough families:
 *   - **Continuous** ([FixtureProperty], [GroupProperty], [SpeedMasterBpm]) — fader /
 *     encoder movements map to a Layer 2 property write (Phase 3) or a tempo write.
 *   - **Discrete** ([CueStackGo], [CueStackBack], [CueStackPause], [FireCue],
 *     [SpeedMasterTap]) — button press invokes a service call.
 *   - **Momentary / global / meta** ([Flash], [Blackout], [GrandMasterToggle], [SetBank]) —
 *     press / release change transport-level state.
 *   - **Selection-relative** ([SelectionProperty], [SelectTarget], [ClearSelection],
 *     [LocateSelection]) — the desk's selection (`state.DeskSelection`) is the target set, so
 *     one fader or button reaches whatever the operator has selected rather than one fixed thing.
 *     See `docs/plans/midi-surface-plan.md` §D3.
 *
 * Cue and stack variants carry a **uuid beside the int id** ([FireCue.cueUuid],
 * [CueStackGo.stackUuid] …). The int is what the REST client sends and what `CueStackManager`
 * consumes; the uuid is what survives a clone or a cross-install import, because the export
 * remapper rewrites every uuid-shaped string in the payload and nothing can translate an int.
 * Dispatch and health resolve by uuid first and fall back to the int only for a pre-v11 row
 * that has none (`FU-SYNC-BINDING-PAYLOAD-UUIDS`, first half).
 *
 * [Unknown] is the one variant never written by a client: the tolerant per-row decode in
 * `ControlSurfaceBindingService` produces it for a payload whose `type` this build does not
 * know, so an archive from a newer desk loads with that row dead and rebindable instead of
 * refusing the whole project.
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

    /** Advance the named cue stack on button press. [stackUuid] wins over [stackId] when set. */
    @Serializable
    @SerialName("cueStackGo")
    data class CueStackGo(val stackId: Int, val stackUuid: String? = null) : BindingTarget()

    /** Step back in the named cue stack on button press. [stackUuid] wins over [stackId] when set. */
    @Serializable
    @SerialName("cueStackBack")
    data class CueStackBack(val stackId: Int, val stackUuid: String? = null) : BindingTarget()

    /** Pause / resume the named cue stack on button press. [stackUuid] wins over [stackId] when set. */
    @Serializable
    @SerialName("cueStackPause")
    data class CueStackPause(val stackId: Int, val stackUuid: String? = null) : BindingTarget()

    /** Fire a specific cue on button press. [cueUuid] wins over [cueId] when set. */
    @Serializable
    @SerialName("fireCue")
    data class FireCue(val cueId: Int, val cueUuid: String? = null) : BindingTarget()

    /**
     * Momentary "flash" write: on press, write [max] to the nested property via Layer 2;
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

    /**
     * Write a continuous property on **every selected target** — a fixture in the selection
     * directly, a group fanned to its members exactly as [GroupProperty] fans. An empty selection
     * drops the write with a debug log (the legend's "no selection" state); it is never widened
     * to "everything".
     */
    @Serializable
    @SerialName("selectionProperty")
    data class SelectionProperty(val propertyName: String) : BindingTarget()

    /** How a [SelectTarget] press changes the desk selection. */
    @Serializable
    enum class SelectMode {
        /** Add the target if its heads are not all selected, else take them off. */
        @SerialName("toggle") TOGGLE,
        /** Replace the whole selection with this one target. */
        @SerialName("replace") REPLACE,
    }

    /**
     * Put one group or fixture into (or out of) the desk selection on button press. The LED is
     * lit while the target's heads are all selected — a group by its members counts, so a select
     * button and a busk pad agree on what "selected" means.
     */
    @Serializable
    @SerialName("selectTarget")
    data class SelectTarget(
        val target: CueTargetDto,
        val mode: SelectMode = SelectMode.TOGGLE,
    ) : BindingTarget()

    /** Empty the desk selection on press. */
    @Serializable
    @SerialName("clearSelection")
    data object ClearSelection : BindingTarget()

    /**
     * Locate every selected target on press (through `LocateManager.toggle`, per target); a
     * second press releases them. The LED is lit while every selected target is located.
     */
    @Serializable
    @SerialName("locateSelection")
    data object LocateSelection : BindingTarget()

    /**
     * One group or fixture on a whole **channel strip**. The row is addressed by the strip id
     * rather than a control id, and never dispatched: [ControlSurfaceBindingService.resolve]
     * derives it to the target each of the strip's controls actually behaves as — see
     * [deriveStripTarget]. A strip binding is refused on a control id at bind time, and a
     * control binding on a strip id.
     */
    @Serializable
    @SerialName("strip")
    data class Strip(val target: CueTargetDto) : BindingTarget()

    /**
     * Point the device's strip encoders at [propertyName] on press — the attribute-select
     * buttons every console has. Applies to the device the button is on, so the payload names
     * no device; the LED is lit while this is the device's current encoder bank.
     */
    @Serializable
    @SerialName("encoderBankSet")
    data class EncoderBankSet(val propertyName: String) : BindingTarget()

    /**
     * A persisted payload whose `type` this build does not know. Never constructed by a client
     * (the binding routes refuse it); produced only by the tolerant row decode, and re-encoded
     * as [rawPayload] verbatim so a round trip through an older desk loses nothing. Field names
     * deliberately avoid `type`, which is the discriminator.
     */
    @Serializable
    @SerialName("unknown")
    data class Unknown(val targetType: String, val rawPayload: String) : BindingTarget()
}

/** JSON codec for [BindingTarget] payloads. Stable discriminator = `type`. */
val BindingTargetJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = false
}
