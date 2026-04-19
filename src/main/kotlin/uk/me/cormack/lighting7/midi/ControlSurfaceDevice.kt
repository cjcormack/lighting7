package uk.me.cormack.lighting7.midi

/**
 * Base class for a concrete control-surface profile. Subclasses populate themselves via
 * the DSL methods below from inside an `init { }` block:
 *
 * ```
 * @ControlSurfaceType("my-device", vendor = "Acme", product = "Widget")
 * class MyDevice : ControlSurfaceDevice() {
 *     init {
 *         repeat(4) { i -> fader(id = "fader-${i+1}", cc = 20 + i) }
 *         button(id = "play", note = 64)
 *     }
 * }
 * ```
 *
 * The base class's field initialisers run before any subclass `init` block, so the
 * mutable backing lists are ready to receive descriptors by the time the DSL calls
 * fire. Each subclass must have a no-argument primary constructor — [ControlSurfaceRegistry]
 * instantiates it reflectively once at registry load for introspection.
 *
 * Instances are immutable after construction: [controls] and [banks] return read-only
 * views of the lists populated during `init`.
 */
abstract class ControlSurfaceDevice {
    private val _controls = mutableListOf<ControlDescriptor>()
    private val _banks = mutableListOf<BankDefinition>()

    val controls: List<ControlDescriptor> get() = _controls
    val banks: List<BankDefinition> get() = _banks

    /**
     * Declare a motorised fader. [motorCc] defaults to [cc] since most motor faders echo
     * their own CC. Touch sensing is optional; pass either [touchNote] (Mackie-style NoteOn)
     * or [touchCc] (X-Touch Compact style CC) — not both.
     */
    protected fun motorFader(
        id: String,
        cc: Int,
        touchNote: Int? = null,
        touchCc: Int? = null,
        motorCc: Int? = cc,
        channel: Int = 0,
        label: String = id,
        resolution: FaderResolution = FaderResolution.SEVEN_BIT,
    ) {
        _controls += FaderDescriptor(
            controlId = id,
            label = label,
            cc = cc,
            channel = channel,
            hasMotor = true,
            motorCc = motorCc,
            touchNote = touchNote,
            touchCc = touchCc,
            resolution = resolution,
        )
    }

    /** Declare a non-motorised fader. Consumers should apply soft-takeover for these. */
    protected fun fader(
        id: String,
        cc: Int,
        channel: Int = 0,
        label: String = id,
        resolution: FaderResolution = FaderResolution.SEVEN_BIT,
    ) {
        _controls += FaderDescriptor(
            controlId = id,
            label = label,
            cc = cc,
            channel = channel,
            hasMotor = false,
            motorCc = null,
            touchNote = null,
            resolution = resolution,
        )
    }

    /** Declare a rotary encoder. Absolute encoding only. */
    protected fun encoder(
        id: String,
        cc: Int,
        ringCc: Int? = null,
        pushNote: Int? = null,
        pushLed: LedFeedback = if (pushNote != null) LedFeedback.ON_OFF else LedFeedback.NONE,
        channel: Int = 0,
        label: String = id,
        ringStyle: EncoderRingStyle = EncoderRingStyle.SINGLE_DOT,
    ) {
        _controls += EncoderDescriptor(
            controlId = id,
            label = label,
            cc = cc,
            channel = channel,
            ringCc = ringCc,
            ringStyle = ringStyle,
            pushNote = pushNote,
            pushLed = pushLed,
        )
    }

    /** Declare a button. */
    protected fun button(
        id: String,
        note: Int,
        ledFeedback: LedFeedback = LedFeedback.ON_OFF,
        channel: Int = 0,
        label: String = id,
    ) {
        _controls += ButtonDescriptor(
            controlId = id,
            label = label,
            note = note,
            channel = channel,
            ledFeedback = ledFeedback,
        )
    }

    /**
     * Declare an app-side bank together with the device-side button that switches to it.
     * The device may signal the switch via either a NoteOn on [inputNote] or a Program
     * Change matching [inputProgramChange] — pass exactly one. Emits both a
     * [BankDefinition] and a [BankButtonDescriptor] with controlId = `"bank-$id"`.
     */
    protected fun bank(
        id: String,
        name: String,
        inputNote: Int? = null,
        inputProgramChange: Int? = null,
        channel: Int = 0,
    ) {
        _banks += BankDefinition(id = id, name = name)
        _controls += BankButtonDescriptor(
            controlId = "bank-$id",
            label = name,
            note = inputNote,
            programChange = inputProgramChange,
            channel = channel,
            bankId = id,
        )
    }
}
