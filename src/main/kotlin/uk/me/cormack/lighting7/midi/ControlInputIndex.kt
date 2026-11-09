package uk.me.cormack.lighting7.midi

/**
 * One profile's inbound MIDI, indexed once: which control part each event addresses. The router
 * routes by it and Learn captures by it, so the two can never read one event two ways.
 *
 * A control change is a fader's or encoder's move, or a fader's touch; a note is a button's press or
 * release, an encoder's push (its [ControlPart.PUSH]), a fader's touch, or a layer button; a program
 * change is a layer button. Where two controls claim one address, the first declared wins, as the
 * profile's own order said before the index.
 */
class ControlInputIndex(controls: List<ControlDescriptor>) {
    /** What one event is. */
    sealed interface Input {
        /** A fader or encoder position. */
        data class Move(val control: ControlDescriptor, val value7Bit: UByte) : Input
        data class Press(val control: ControlDescriptor, val part: ControlPart) : Input
        data class Release(val control: ControlDescriptor, val part: ControlPart) : Input
        data class Touch(val control: FaderDescriptor, val down: Boolean) : Input

        /** A device-side layer button: never bindable, it switches the layer before any binding is resolved. */
        data class Layer(val bankId: String) : Input
    }

    private sealed interface CcEntry {
        data class Move(val control: ControlDescriptor) : CcEntry
        data class Touch(val control: FaderDescriptor) : CcEntry
    }

    private sealed interface NoteEntry {
        data class Button(val control: ControlDescriptor, val part: ControlPart) : NoteEntry
        data class Touch(val control: FaderDescriptor) : NoteEntry
        data class Layer(val bankId: String) : NoteEntry
    }

    private val cc = HashMap<Long, CcEntry>()
    private val notes = HashMap<Long, NoteEntry>()
    private val programs = HashMap<Long, String>()

    init {
        for (control in controls) {
            when (control) {
                is FaderDescriptor -> {
                    cc.putIfAbsent(key(control.channel, control.cc), CcEntry.Move(control))
                    control.touchCc?.let { cc.putIfAbsent(key(control.channel, it), CcEntry.Touch(control)) }
                    control.touchNote?.let { notes.putIfAbsent(key(control.channel, it), NoteEntry.Touch(control)) }
                }
                is EncoderDescriptor -> {
                    cc.putIfAbsent(key(control.channel, control.cc), CcEntry.Move(control))
                    control.pushNote?.let { notes.putIfAbsent(key(control.channel, it), NoteEntry.Button(control, ControlPart.PUSH)) }
                }
                is ButtonDescriptor -> notes.putIfAbsent(key(control.channel, control.note), NoteEntry.Button(control, ControlPart.MAIN))
                is BankButtonDescriptor -> {
                    control.note?.let { notes.putIfAbsent(key(control.channel, it), NoteEntry.Layer(control.bankId)) }
                    control.programChange?.let { programs.putIfAbsent(key(control.channel, it), control.bankId) }
                }
            }
        }
    }

    /** What [event] is on this profile, or null when it addresses no control. */
    fun match(event: MidiInputEvent): Input? = when (event) {
        is MidiInputEvent.ControlChange -> when (val entry = cc[key(event.channel, event.cc)]) {
            is CcEntry.Move -> Input.Move(entry.control, event.value)
            is CcEntry.Touch -> Input.Touch(entry.control, down = event.value.toInt() > 0)
            null -> null
        }
        // NoteOn(velocity=0) is the running-status-friendly form of NoteOff per MIDI spec.
        is MidiInputEvent.NoteOn -> note(event.channel, event.note, pressed = event.velocity.toInt() > 0)
        is MidiInputEvent.NoteOff -> note(event.channel, event.note, pressed = false)
        is MidiInputEvent.ProgramChange -> programs[key(event.channel, event.program)]?.let { Input.Layer(it) }
        is MidiInputEvent.PitchBend, is MidiInputEvent.SysEx -> null
    }

    private fun note(channel: Int, note: Int, pressed: Boolean): Input? = when (val entry = notes[key(channel, note)]) {
        is NoteEntry.Button -> if (pressed) Input.Press(entry.control, entry.part) else Input.Release(entry.control, entry.part)
        is NoteEntry.Touch -> Input.Touch(entry.control, pressed)
        // Layer buttons switch on press only; the release is swallowed.
        is NoteEntry.Layer -> if (pressed) Input.Layer(entry.bankId) else null
        null -> null
    }

    /**
     * The control part Learn captures from [event], or null when it captures nothing: a fader moved
     * off rest, any encoder turn, a press of a button or an encoder's push. Never a touch, a release
     * or a layer button.
     */
    fun capture(event: MidiInputEvent): Pair<String, ControlPart>? = when (val input = match(event)) {
        is Input.Move ->
            if (input.control is FaderDescriptor && input.value7Bit.toInt() == 0) null
            else input.control.controlId to ControlPart.MAIN
        is Input.Press -> input.control.controlId to input.part
        else -> null
    }

    private fun key(channel: Int, number: Int): Long = (channel.toLong() shl 32) or (number.toLong() and 0xffffffffL)
}
