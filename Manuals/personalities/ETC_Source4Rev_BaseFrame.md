# ETC Source 4 Revolution — Base Frame (31ch)

> **Source**: ChamSys MagicQ Head Editor, personality file
> `ETC_Source4Rev_Base Frame.hed`, captured 2026-04-26 during TCH 2026 patch
> analysis. The on-disk `.hed` and `heads.all` files are obfuscated;
> this transcription is taken from the MagicQ UI's `VIEW CHANS` and
> `VIEW RANGES` screens. To re-verify: open the patched Source 4 Revolution
> in MagicQ → `EDIT HEAD`.

ChamSys library lists five Source 4 Revolution personalities; this is the
**Base Frame** one — chassis WITH the four-blade Framing Shutter module
installed. The 31 channels split as:

- 5 channels of base movement & dimmer (1, 2/3, 4/5)
- 7 channels of beam shaping (Media Frame, Focus, Zoom, three timing
  channels) and a Reset channel
- 1 channel of gel scrolling (14 frames) + 1 unused/reserved
- 1 channel of iris
- 8 channels for the two beam wheels (forward + rear; pos / func / rot 16-bit)
- 8 channels for the four framing shutters (4 × pos + rot)

Other personalities in the library, for reference: `Base` (14ch),
`Base Iris` (15ch), `15ch` (15ch), `Base Module` (23ch).

## Channels (`VIEW CHANS`)

| Ch | Name         | Type | Attribute        | Encoder | Size      | Locate | Default | Highlight |
|----|--------------|------|------------------|---------|-----------|--------|---------|-----------|
| 1  | Dimmer       | HTP  | Int (0)          | I1X     | 8 bit     | 255    | 000     | 255       |
| 2  | Pan          | LTP  | Pan (4)          | P1X     | 16 bit hi | 128    | 128     | no level  |
| 3  | Pan F        | LTP  | Pan (4)          | P1X     | 16 bit lo | 000    | 000     | no level  |
| 4  | Tilt         | LTP  | Tilt (5)         | P1Y     | 16 bit hi | 128    | 128     | no level  |
| 5  | Tilt F       | LTP  | Tilt (5)         | P1Y     | 16 bit lo | 000    | 000     | no level  |
| 6  | Media Frame  | LTP  | FX1 Prism (14)   | B2C     | 8 bit     | 000    | 000     | 000       |
| 7  | Focus        | LTP  | Focus (12)       | B1C     | 8 bit     | 000    | 000     | 000       |
| 8  | Zoom         | LTP  | Zoom (13)        | B1D     | 8 bit     | 000    | 000     | 000       |
| 9  | Focus Time   | LTP  | Pri Rot (31)     | B2E     | 8 bit     | 000    | 000     | 000       |
| 10 | Col Time     | LTP  | Col Speed (26)   | C1F     | 8 bit     | 000    | 000     | 000       |
| 11 | Beam Time    | LTP  | Cont4 (41)       | B3C     | 8 bit     | 000    | 000     | 000       |
| 12 | Reset        | LTP  | Frost2 (33)      | B2B     | 8 bit     | 000    | 000     | 000       |
| 13 | Gel Scroller | LTP  | Col1 (6)         | C1X     | 8 bit     | 000    | 000     | 000       |
| 14 | Reserved     | LTP  | Gobo4 (29)       | B2Y     | 8 bit     | 000    | 000     | 000       |
| 15 | Iris         | LTP  | Iris (3)         | B1B     | 8 bit     | 000    | 000     | 000       |
| 16 | FB Wheel Pos | LTP  | Macro1 (22)      | B3A     | 8 bit     | 000    | 000     | 000       |
| 17 | FB Wheel Func| LTP  | Macro2 (23)      | B3B     | 8 bit     | 000    | 000     | 000       |
| 18 | FB Wheel Rot | LTP  | FX4 (35)         | B3D     | 16 bit hi | 000    | 000     | 000       |
| 19 | FB Wheel Rot | LTP  | FX4 (35)         | B3D     | 16 bit lo | 000    | 000     | 000       |
| 20 | RB Wheel Pos | LTP  | FX8 (39)         | B3E     | 8 bit     | 000    | 000     | 000       |
| 21 | RB Wheel Func| LTP  | FX7 (38)         | B3F     | 8 bit     | 000    | 000     | 000       |
| 22 | RB Wheel Rot | LTP  | FX5 (36)         | B3X     | 16 bit hi | 000    | 000     | 000       |
| 23 | RB Wheel Rot | LTP  | FX5 (36)         | B3X     | 16 bit lo | 000    | 000     | 000       |
| 24 | Frame 1 Pos  | LTP  | Frame1A (52)     | B4A     | 8 bit     | 000    | 000     | 000       |
| 25 | Frame 1 Rot  | LTP  | Frame1B (53)     | B4B     | 8 bit     | 128    | 128     | 128       |
| 26 | Frame 2 Pos  | LTP  | Frame2A (54)     | B4C     | 8 bit     | 000    | 000     | 000       |
| 27 | Frame 2 Rot  | LTP  | Frame2B (55)     | B4D     | 8 bit     | 128    | 128     | 128       |
| 28 | Frame 3 Pos  | LTP  | Frame3A (56)     | B4E     | 8 bit     | 000    | 000     | 000       |
| 29 | Frame 3 Rot  | LTP  | Frame3B (57)     | B4F     | 8 bit     | 128    | 128     | 128       |
| 30 | Frame 4 Pos  | LTP  | Frame4A (58)     | B4Y     | 8 bit     | 000    | 000     | 000       |
| 31 | Frame 4 Rot  | LTP  | Frame4B (59)     | B4X     | 8 bit     | 128    | 128     | 128       |

**Notes:**
- Dimmer is the only HTP channel (mechanical douser).
- Pan/Tilt are 16-bit (paired hi/lo channels).
- Frame Pos channels locate at 000 (blade out), Frame Rot channels locate at
  128 (blade square to the lens).
- Reset (ch 12) — VIEW RANGES not captured. Per the TCH 2026 plan this
  channel should not be FX-targetable.
- Reserved (ch 14) — no documented purpose.
- ChamSys assigns the beam wheels to Macro/FX attribute slots (`Macro1/2`,
  `FX4`, `FX8/7/5`); these are arbitrary mapping choices for the desk's
  encoder layout, not authoritative information about what each channel
  controls. The names "FB Wheel Pos / Func / Rot" and "RB Wheel Pos / Func /
  Rot" come from MagicQ's per-channel name field.

## Channel 8 — Zoom (`VIEW RANGES`)

| Range   | Type            | Name          |
|---------|-----------------|---------------|
| 000–000 | Wide            | Wide          |
| 001–254 | Wide to Narrow  | Wide > Narrow |
| 255–255 | Narrow          | Narrow        |

Continuous slider, named endpoints only.

## Channel 13 — Gel Scroller (`VIEW RANGES`)

14 evenly-spaced bands. The personality uses generic frame names; map to
physical gel colours from the venue's gel string.

| Range   | Name     |
|---------|----------|
| 000–017 | Frame 0  |
| 018–036 | Frame 1  |
| 037–054 | Frame 2  |
| 055–072 | Frame 3  |
| 073–090 | Frame 4  |
| 091–109 | Frame 5  |
| 110–127 | Frame 6  |
| 128–145 | Frame 7  |
| 146–164 | Frame 8  |
| 165–182 | Frame 9  |
| 183–200 | Frame 10 |
| 201–218 | Frame 11 |
| 219–237 | Frame 12 |
| 238–255 | Frame 13 |

## Channel 15 — Iris (`VIEW RANGES`)

| Range   | Type          | Name          |
|---------|---------------|---------------|
| 000–000 | Open          | Wide          |
| 001–254 | Open to Closed| Wide > Narrow |
| 255–255 | Closed        | Narrow        |

Continuous slider, named endpoints only. (MagicQ keeps the Wide/Narrow names
from the Zoom-style template.)

## Channels with no discrete ranges captured

These are continuous controls; no `VIEW RANGES` entries beyond the implicit
0–255. They are modelled as plain sliders in the fixture class:

- **Ch 1 Dimmer** — HTP, 0–255 linear (mechanical douser).
- **Ch 2/3 Pan**, **Ch 4/5 Tilt** — 16-bit position.
- **Ch 6 Media Frame** — beam shaping.
- **Ch 7 Focus** — 0–255 linear.
- **Ch 9 Focus Time, Ch 10 Col Time, Ch 11 Beam Time** — fade-time channels.
- **Ch 12 Reset** — see safety note above.
- **Ch 14 Reserved** — unused.
- **Ch 16/17 FB Wheel Pos/Func, Ch 18/19 FB Wheel Rot (16-bit)** — forward
  beam wheel.
- **Ch 20/21 RB Wheel Pos/Func, Ch 22/23 RB Wheel Rot (16-bit)** — rear beam
  wheel.
- **Ch 24–31 Frame 1–4 Pos/Rot** — four framing shutter blades.

## Mapping to fixture traits

When `Source4RevolutionFixture` is implemented (Tier 7):

- `WithDimmer` → ch 1.
- `WithPosition` → 16-bit pan/tilt (channels 2/3, 4/5).
- No `WithStrobe` — there is no shutter/strobe channel in this personality.
- No `WithColour` — colour comes from the discrete gel scroller.
- `DmxFixtureSetting` enum → ch 13 (Gel Scroller).
- Plain `Slider` → all other beam-shaping, wheel, and frame channels.
- Ch 12 (Reset) and ch 14 (Reserved) are not exposed as `@FixtureProperty` —
  Reset for safety (no captured value bands, dangerous if hit by FX), Reserved
  because it has no documented purpose.
