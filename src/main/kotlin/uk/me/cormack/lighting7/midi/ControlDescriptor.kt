package uk.me.cormack.lighting7.midi

/**
 * Feedback capability of an LED on a control.
 *
 *  - [NONE]       — no LED feedback available.
 *  - [ON_OFF]     — single binary LED; feedback is a note velocity 0 or 127.
 *  - [BRIGHTNESS] — variable brightness via velocity / CC 0..127.
 *  - [COLOUR]     — RGB LED; encoding is device-specific.
 */
enum class LedFeedback { NONE, ON_OFF, BRIGHTNESS, COLOUR }

/** Absolute-value fader encoding. 14-bit uses MSB+LSB CC pairs. */
enum class FaderResolution { SEVEN_BIT, FOURTEEN_BIT }

/**
 * How an encoder's surrounding LED ring visualises its value.
 *
 *  - [NONE]       — no ring LEDs.
 *  - [SINGLE_DOT] — one LED at a time indicates position.
 *  - [FAN]        — LEDs fill from the start to the current position.
 *  - [PAN]        — LEDs fan symmetrically from centre (negative/positive span).
 */
enum class EncoderRingStyle { NONE, SINGLE_DOT, FAN, PAN }

/**
 * Static description of a single control on a [ControlSurfaceDevice]. Purely metadata —
 * owns no state, no bindings, no runtime behaviour. Consumers key bindings by
 * `(deviceTypeKey, controlId)` and use the MIDI addressing fields (cc, note, channel,
 * touchNote, motorCc, ringCc, pushNote) to route input and drive feedback.
 */
sealed class ControlDescriptor {
    abstract val controlId: String
    abstract val label: String
}

/**
 * A linear fader. When [hasMotor] is true, outbound feedback drives the motor via
 * [motorCc]; if the control is touch-sensitive, [touchNote] fires on touch-on / touch-off
 * so feedback can suspend while the user holds the fader.
 */
data class FaderDescriptor(
    override val controlId: String,
    override val label: String,
    val cc: Int,
    val channel: Int = 0,
    val hasMotor: Boolean = false,
    val motorCc: Int? = null,
    val touchNote: Int? = null,
    val resolution: FaderResolution = FaderResolution.SEVEN_BIT,
) : ControlDescriptor()

/**
 * A rotary encoder. May have a surrounding LED ring ([ringCc] + [ringStyle]) and / or a
 * push switch ([pushNote]). Absolute-value encoders only; relative encodings (Mackie,
 * two's complement, etc.) are not supported.
 */
data class EncoderDescriptor(
    override val controlId: String,
    override val label: String,
    val cc: Int,
    val channel: Int = 0,
    val ringCc: Int? = null,
    val ringStyle: EncoderRingStyle = EncoderRingStyle.SINGLE_DOT,
    val pushNote: Int? = null,
    val pushLed: LedFeedback = LedFeedback.NONE,
) : ControlDescriptor()

/**
 * A momentary / toggle button. Press / release arrive as NoteOn / NoteOff on [note].
 * LED feedback (where available) is driven back out on the same note.
 */
data class ButtonDescriptor(
    override val controlId: String,
    override val label: String,
    val note: Int,
    val channel: Int = 0,
    val ledFeedback: LedFeedback = LedFeedback.ON_OFF,
) : ControlDescriptor()

/**
 * A device-side bank-switch button. Press emits a synthetic bank-change targeting
 * [bankId]. Kept distinct from [ButtonDescriptor] so the registry and UI can treat
 * bank buttons specially — they're not bindable to arbitrary targets.
 */
data class BankButtonDescriptor(
    override val controlId: String,
    override val label: String,
    val note: Int,
    val channel: Int = 0,
    val bankId: String,
) : ControlDescriptor()

/**
 * Declares an app-side bank. The matching physical bank button (if any) is declared
 * separately as a [BankButtonDescriptor] with the same [id].
 */
data class BankDefinition(val id: String, val name: String)
