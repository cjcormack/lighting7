package uk.me.cormack.lighting7.midi

/** The property a strip fader and its flash button always drive. */
const val STRIP_FADER_ATTRIBUTE: String = "dimmer"

/**
 * What a control on a [StripDescriptor] behaves as: one group or fixture on a strip becomes a
 * dimmer fader, a select button, an encoder on whichever attribute — and, for a colour, whichever
 * axis — the device's encoder page names, and a flash button. Only a control's main part: an
 * encoder's push on a strip is its own binding, and does nothing until bound (D4).
 *
 * A pure function, deliberately: [ControlSurfaceBindingService.resolve] derives on the input and
 * feedback paths, and the *Fader only…* expansion asks the same question, so there is exactly one
 * answer to keep in step. Jobs replace strips in session 5.
 */
fun deriveStripBinding(
    role: StripRole,
    on: Subject,
    encoderBank: EncoderBankSelection,
): Binding = when (role) {
    StripRole.FADER -> Binding(Does.Level(STRIP_FADER_ATTRIBUTE), on)
    StripRole.SELECT -> Binding(Does.Select, on)
    // The page's axis rides along: it is the encoder, and only the encoder, that a colour axis
    // reaches on a strip — the fader and flash are the dimmer whatever the page says.
    StripRole.ENCODER -> Binding(Does.Level(encoderBank.attribute, encoderBank.colourAxis), on)
    StripRole.FLASH -> Binding(Does.Flash(STRIP_FADER_ATTRIBUTE), on)
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
fun deriveStripBindings(
    strip: StripDescriptor,
    on: Subject,
    encoderBank: EncoderBankSelection,
): Map<String, Binding> = buildMap {
    for (role in StripRole.entries) {
        val controlId = strip.controlFor(role) ?: continue
        put(controlId, deriveStripBinding(role, on, encoderBank))
    }
}
