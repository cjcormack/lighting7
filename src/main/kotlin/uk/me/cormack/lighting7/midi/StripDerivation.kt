package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef

/** The property a strip fader and its flash button always drive. */
const val STRIP_FADER_PROPERTY: String = "dimmer"

/**
 * What a control on a [StripDescriptor] behaves as: one group or fixture on a strip becomes a
 * dimmer fader, a select button, an encoder on whichever attribute the device's encoder bank
 * names, and a flash button.
 *
 * A pure function, deliberately: [ControlSurfaceBindingService.resolve] derives on the input and
 * feedback paths, and the frontend inspector explains a strip binding by asking the same question,
 * so there is exactly one answer to keep in step.
 *
 * Returns null only for a role the strip does not declare — a master strip has no encoder.
 */
fun deriveStripTarget(
    role: StripRole,
    target: CueTargetDto,
    encoderBankProperty: String,
): BindingTarget = when (role) {
    StripRole.FADER -> propertyTarget(target, STRIP_FADER_PROPERTY)
    StripRole.SELECT -> BindingTarget.SelectTarget(target, BindingTarget.SelectMode.TOGGLE)
    StripRole.ENCODER -> propertyTarget(target, encoderBankProperty)
    StripRole.FLASH -> BindingTarget.Flash(propertyTarget(target, STRIP_FADER_PROPERTY))
}

/** A control's place on a strip: which strip claims it, and as what. */
data class StripControl(val strip: StripDescriptor, val role: StripRole)

/**
 * Every control the [strips] claim, keyed by control id — the "which strip owns this control"
 * question, answered once. Built at profile-load time (the registry) or per device type and
 * cached (the binding service): a linear scan over strips × roles on every MIDI event would be
 * a scan per tick of a held fader, one line below an O(1) map lookup.
 *
 * The registry refuses a control claimed by two strips, so the last-writer-wins here never fires
 * on a validated profile.
 */
fun stripControlsByControlId(strips: List<StripDescriptor>): Map<String, StripControl> = buildMap {
    for (strip in strips) {
        for (role in StripRole.entries) {
            val controlId = strip.controlFor(role) ?: continue
            put(controlId, StripControl(strip, role))
        }
    }
}

/**
 * Derive every role the strip declares. Used by the *Fader only…* expansion, which turns a strip
 * row into the single rows it was deriving.
 */
fun deriveStripTargets(
    strip: StripDescriptor,
    target: CueTargetDto,
    encoderBankProperty: String,
): Map<String, BindingTarget> = buildMap {
    for (role in StripRole.entries) {
        val controlId = strip.controlFor(role) ?: continue
        put(controlId, deriveStripTarget(role, target, encoderBankProperty))
    }
}

/**
 * A continuous target on a group or a fixture. The same shape a hand-made binding has, so
 * everything downstream — the write path, the feedback index, health — treats a derived binding
 * and a fixed one identically.
 */
private fun propertyTarget(target: CueTargetDto, propertyName: String): BindingTarget =
    when (target.target) {
        is TargetRef.Group -> BindingTarget.GroupProperty(target.key, propertyName)
        is TargetRef.Fixture -> BindingTarget.FixtureProperty(target.key, propertyName)
    }
