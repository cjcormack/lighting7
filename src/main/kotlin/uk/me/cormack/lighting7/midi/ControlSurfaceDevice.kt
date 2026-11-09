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
 * Instances are immutable after construction: [controls], [banks] and [jobRegions] return
 * read-only views of the lists populated during `init`, and [layout] the panel picture.
 */
abstract class ControlSurfaceDevice {
    private val _controls = mutableListOf<ControlDescriptor>()
    private val _banks = mutableListOf<BankDefinition>()
    private val _jobRegions = mutableListOf<JobRegion>()
    private var _layout: SurfaceLayout? = null

    val controls: List<ControlDescriptor> get() = _controls
    val banks: List<BankDefinition> get() = _banks
    val jobRegions: List<JobRegion> get() = _jobRegions
    val layout: SurfaceLayout? get() = _layout

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
     * Declare a region a job fills (surface-setup plan D11): its [id], stored in a job row, its
     * [name], and its slots in order. [ControlSurfaceRegistry] refuses a region naming a control the
     * profile does not declare, a role naming a control of the wrong kind, a control in two regions
     * and an empty region.
     */
    protected fun jobRegion(id: String, name: String, build: JobRegionBuilder.() -> Unit) {
        _jobRegions += JobRegion(id, name, JobRegionBuilder().apply(build).slots)
    }

    /** Receiver for [jobRegion]. */
    protected class JobRegionBuilder {
        internal val slots = mutableListOf<JobSlot>()

        /** One slot: the control playing each role it has; [push] names the encoder whose push it is. */
        fun slot(
            fader: String? = null,
            select: String? = null,
            encoder: String? = null,
            flash: String? = null,
            button: String? = null,
            push: String? = null,
        ) {
            val roles = listOf(
                SlotRole.FADER to fader, SlotRole.SELECT to select, SlotRole.ENCODER to encoder,
                SlotRole.FLASH to flash, SlotRole.BUTTON to button, SlotRole.PUSH to push,
            )
            slots += JobSlot(buildMap { for ((role, id) in roles) if (id != null) put(role, id) })
        }
    }

    /**
     * Declare where the controls sit when the surface is drawn as a picture. A profile either
     * declares no layout — the frontend falls back to a grouped table — or a complete one: the
     * registry requires exactly one cell per declared control.
     */
    protected fun layout(build: LayoutBuilder.() -> Unit) {
        _layout = SurfaceLayout(LayoutBuilder().apply(build).regions)
    }

    /** Receiver for [layout]. */
    protected class LayoutBuilder {
        internal val regions = mutableListOf<LayoutRegion>()

        /** A named block of the panel. [columns] is the block's width in cells. */
        fun region(name: String, columns: Int, build: RegionBuilder.() -> Unit) {
            regions += LayoutRegion(name, columns, RegionBuilder().apply(build).cells)
        }
    }

    /** Receiver for [LayoutBuilder.region]. */
    protected class RegionBuilder {
        internal val cells = mutableListOf<LayoutCell>()

        /**
         * Place the controls of one column top to bottom; rows are numbered from declaration
         * order, so a strip column reads as the physical panel does.
         */
        fun column(col: Int, build: ColumnBuilder.() -> Unit) {
            val column = ColumnBuilder().apply(build)
            column.controlIds.forEachIndexed { row, controlId -> cells += LayoutCell(controlId, col, row) }
        }

        /** Place one control explicitly. */
        fun cell(controlId: String, col: Int, row: Int) {
            cells += LayoutCell(controlId, col, row)
        }
    }

    /** Receiver for [RegionBuilder.column]. */
    protected class ColumnBuilder {
        internal val controlIds = mutableListOf<String>()

        /** Append a control to this column, one row below the previous one. */
        fun cell(controlId: String) {
            controlIds += controlId
        }
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
