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
 * [motorCc]; if the control is touch-sensitive, either [touchNote] (NoteOn/Off) or
 * [touchCc] (ControlChange — value > 0 means down) fires on touch-on / touch-off so
 * feedback can suspend while the user holds the fader. Devices use one or the other,
 * not both (Mackie Control uses notes; Behringer X-Touch Compact Standard uses CC).
 * Leave both null for faders without touch sensing.
 */
data class FaderDescriptor(
    override val controlId: String,
    override val label: String,
    val cc: Int,
    val channel: Int = 0,
    val hasMotor: Boolean = false,
    val motorCc: Int? = null,
    val touchNote: Int? = null,
    val touchCc: Int? = null,
    val resolution: FaderResolution = FaderResolution.SEVEN_BIT,
) : ControlDescriptor() {
    init {
        require(touchNote == null || touchCc == null) {
            "FaderDescriptor '$controlId' sets both touchNote and touchCc; pick one"
        }
    }
}

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
 *
 * The device may signal the switch via either a NoteOn on [note] or a Program Change
 * matching [programChange] (X-Touch Compact uses the latter for its A/B Layer button).
 * Exactly one of the two must be set.
 */
data class BankButtonDescriptor(
    override val controlId: String,
    override val label: String,
    val note: Int? = null,
    val programChange: Int? = null,
    val channel: Int = 0,
    val bankId: String,
) : ControlDescriptor() {
    init {
        require((note == null) != (programChange == null)) {
            "BankButtonDescriptor '$controlId' must set exactly one of note / programChange"
        }
    }
}

/**
 * Declares an app-side bank. The matching physical bank button (if any) is declared
 * separately as a [BankButtonDescriptor] with the same [id].
 */
data class BankDefinition(val id: String, val name: String)

/**
 * The role a control plays in a [JobSlot]: a strip's fader, select button, encoder and flash button,
 * a row's button, or an encoder's push. A job kind derives one binding per role it fills.
 */
enum class SlotRole {
    FADER, SELECT, ENCODER, FLASH, BUTTON, PUSH;

    /** The control part the role is: an encoder's push, else the control's main part. */
    val part: ControlPart get() = if (this == PUSH) ControlPart.PUSH else ControlPart.MAIN
}

/** One slot of a [JobRegion]: the control playing each of its roles. */
data class JobSlot(val controls: Map<SlotRole, String>) {
    fun controlFor(role: SlotRole): String? = controls[role]

    /** Whether the slot has a control in [role]. */
    fun has(role: SlotRole): Boolean = role in controls
}

/**
 * A region of the panel a **job** fills (surface-setup plan D11): ordered slots, each with its
 * roles — the eight channel strips, a row of buttons, the right-hand encoders. Kept apart from the
 * picture's [LayoutRegion]s: the picture places every control, a job region names slots. The
 * registry refuses a control in two regions, a role naming a control the profile does not declare
 * or of the wrong kind, and a region with no slots.
 */
data class JobRegion(val id: String, val name: String, val slots: List<JobSlot>) {
    /** Every control part the region claims, with its slot and role. */
    val places: Map<Pair<String, ControlPart>, JobPlace> by lazy {
        buildMap {
            slots.forEachIndexed { index, slot ->
                for ((role, controlId) in slot.controls) put(controlId to role.part, JobPlace(id, index, role))
            }
        }
    }

    /** Every control the region claims, in slot order. */
    val controlIds: Set<String> get() = slots.flatMapTo(LinkedHashSet()) { it.controls.values }
}

/** Where one control part sits in a job region. */
data class JobPlace(val region: String, val slot: Int, val role: SlotRole)

/**
 * Where a control sits when the surface is drawn as a picture rather than a table. Purely
 * presentational data owned by the profile (rather than a React component per device), so
 * a second device is still one `.kt` file.
 */
data class LayoutCell(val controlId: String, val col: Int, val row: Int)

/** A named block of the panel — `strips`, `right`, `master` on the X-Touch Compact. */
data class LayoutRegion(val name: String, val columns: Int, val cells: List<LayoutCell>)

/**
 * The whole panel. When a profile declares one the registry requires it to be complete:
 * every declared control gets exactly one cell, so a control can never go missing from
 * the picture unnoticed.
 */
data class SurfaceLayout(val regions: List<LayoutRegion>)
