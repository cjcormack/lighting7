package uk.me.cormack.lighting7.midi.devices

import uk.me.cormack.lighting7.midi.ControlSurfaceDevice
import uk.me.cormack.lighting7.midi.ControlSurfaceType
import uk.me.cormack.lighting7.midi.LedFeedback

/**
 * Behringer X-Touch Compact profile in **Standard** operating mode (not MC / Mackie mode),
 * Preset **Layer A** (factory "Mixer Control" defaults). All traffic is on MIDI channel 1
 * (0-indexed `0`).
 *
 * Layer A MIDI map (source: Behringer X-Touch Compact Quick Start Guide, pages 19–21):
 *   - **Faders** — 9 motorised 100 mm faders (8 channel + master). Move values on CC 1..9,
 *     touch on CC 101..109 (velocity >0 = touch, 0 = release). Motor feedback echoes CC 1..9.
 *   - **Encoders (top row, 1..8)** — Turn value on CC 10..17, push switch on notes 0..7,
 *     LED ring remote-value CC 26..33.
 *   - **Encoders (right-side block, 9..16)** — Turn value on CC 18..25, push switch on notes
 *     8..15, LED ring remote-value CC 34..41.
 *   - **Buttons** — 39 illuminated buttons laid out as upper/mid/lower rows above the faders,
 *     select row beneath the faders, and a right-side transport/function area. Layer A TX
 *     notes are 16..54 (8 + 8 + 8 + 9 + 6). Note: the firmware's LED-remote-control notes on
 *     page 22 (RX notes 0..38) differ from the Layer A TX notes — LED feedback parity is
 *     tracked separately; this profile's [note] values drive input routing correctly for
 *     Layer A button presses.
 *   - **Layer A / B** — the device-side layer switch emits **Program Change** (value 0 = A,
 *     1 = B) — *not* a NoteOn.
 */
@ControlSurfaceType(
    typeKey = "x-touch-compact-standard",
    vendor = "Behringer",
    product = "X-Touch Compact",
    portPattern = "(?i)x[ _-]?touch[ _-]?compact",
)
class XTouchCompactStandard : ControlSurfaceDevice() {
    init {
        repeat(9) { i ->
            motorFader(
                id = "fader-${i + 1}",
                cc = 1 + i,
                touchCc = 101 + i,
                label = if (i == 8) "Master" else "Fader ${i + 1}",
            )
        }
        // Encoders 1–8: top horizontal row above the button block.
        repeat(8) { i ->
            encoder(
                id = "enc-${i + 1}",
                cc = 10 + i,
                ringCc = 26 + i,
                pushNote = 0 + i,
                pushLed = LedFeedback.ON_OFF,
                label = "Encoder ${i + 1}",
            )
        }
        // Encoders 9–16: right-side 2×4 block above the master fader.
        repeat(8) { i ->
            encoder(
                id = "enc-${i + 9}",
                cc = 18 + i,
                ringCc = 34 + i,
                pushNote = 8 + i,
                pushLed = LedFeedback.ON_OFF,
                label = "Encoder ${i + 9}",
            )
        }
        // 39 buttons: upper top (16–23), upper mid (24–31), upper bottom (32–39),
        // select/MC row (40–48), right-area transport/function (49–54).
        repeat(39) { i ->
            button(
                id = "btn-${i + 1}",
                note = 16 + i,
                ledFeedback = LedFeedback.ON_OFF,
                label = "Button ${i + 1}",
            )
        }
        bank(id = "layer-a", name = "A", inputProgramChange = 0)
        bank(id = "layer-b", name = "B", inputProgramChange = 1)
    }
}
