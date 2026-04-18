package uk.me.cormack.lighting7.midi.devices

import uk.me.cormack.lighting7.midi.ControlSurfaceDevice
import uk.me.cormack.lighting7.midi.ControlSurfaceType
import uk.me.cormack.lighting7.midi.LedFeedback

/**
 * Behringer X-Touch Compact profile in **Standard** operating mode (not MC / Mackie mode).
 *
 * Standard-mode MIDI map (all on MIDI channel 1, 0-indexed = channel `0`):
 *   - **Faders** — 8 channel faders + 1 master fader. Values arrive on CC 1..9, touch
 *     notes on 101..109. All 9 are motorised.
 *   - **Encoders** — 16 rotary encoders in two rows of 8. Value CC 16..31. Each has an
 *     LED ring driven by CC 48..63, and a push-switch on notes 32..47 with a push LED.
 *   - **Buttons** — 39 illuminated buttons (16 upper-row above encoders, 16 lower-row
 *     under encoders, plus function / transport buttons on the right). Notes 8..46.
 *   - **Layer A / B** — device-side bank-switch buttons on notes 84 and 85. Emitted
 *     as synthetic `SetBank` events in Phase 3.
 *
 * Source: X-Touch Compact MIDI Implementation chart, Behringer, rev 1.
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
                touchNote = 101 + i,
                label = if (i == 8) "Master" else "Fader ${i + 1}",
            )
        }
        repeat(16) { i ->
            encoder(
                id = "enc-${i + 1}",
                cc = 16 + i,
                ringCc = 48 + i,
                pushNote = 32 + i,
                pushLed = LedFeedback.ON_OFF,
                label = "Encoder ${i + 1}",
            )
        }
        repeat(39) { i ->
            button(
                id = "btn-${i + 1}",
                note = 8 + i,
                ledFeedback = LedFeedback.ON_OFF,
                label = "Button ${i + 1}",
            )
        }
        bank(id = "layer-a", inputNote = 84, name = "A")
        bank(id = "layer-b", inputNote = 85, name = "B")
    }
}
