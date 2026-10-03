# ETC Source 4 Revolution — Base Frame (31ch)

> **Sources**: ChamSys MagicQ Head Editor, personality file
> `ETC_Source4Rev_Base Frame.hed`, captured 2026-04-26 during TCH 2026 patch
> analysis (the on-disk `.hed` and `heads.all` files are obfuscated; this
> transcription is taken from the MagicQ UI's `VIEW CHANS` and `VIEW RANGES`
> screens — to re-verify, open the patched Source 4 Revolution in MagicQ →
> `EDIT HEAD`), **corrected 2026-10-02 against ETC's *Source Four Revolution
> User Manual*, 7160M1200 Rev E** (`Manuals/S4_Revolution_User_Manual_RevE.pdf`).
> Where the two disagree the manual wins; the corrections are marked
> **(manual)** below. Page numbers are the PDF's, with the printed page in
> brackets. The manual covers units built before May 2007; later units have
> manual 7160M1210.

ChamSys lists five Source 4 Revolution personalities; this is **Base Frame** —
the base unit with the four-blade Shutter Module in the rear bay. The manual's
own name for it is "Base + Framing" (p14 [10]). The 31 channels split as:

- 5 channels of intensity and movement (1, 2/3, 4/5)
- the internal media frame, focus, zoom, three timing channels and reset (6–12)
- the gel scroller and fan speed (13, 14)
- the iris (15)
- 4 channels for the front bay wheel (16, 17, 18/19 16-bit)
- 4 reserved channels (20–23) — **(manual)** the shutter module takes the rear
  bay, so the rear wheel these address in "Base + Modules" cannot be fitted
- 8 channels for the four framing shutters (4 × in + rotate)

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

The table above is ChamSys's as captured. The manual's channel table
(p14 [10], "Base + Framing") names three of these differently:

- **Ch 14** is **Fan Speed Control**, not reserved **(manual)**.
- **Ch 16–19** are the **Front Bay Wheel** position, function and 16-bit
  index/rotation.
- **Ch 20–23** are **Reserved** **(manual)**. ChamSys's "RB Wheel" names are
  the "Base + Modules" personality's rear wheel, which cannot be fitted beside
  the shutter module.

**Notes:**
- Intensity is the only HTP channel. **(manual)** It is an integral pulse-width
  modulated 0–77 VAC electronic dimmer (p4 [ii]), not a mechanical douser.
- Pan/Tilt are 16-bit (paired hi/lo channels).
- Frame Pos channels locate at 000 (blade out), Frame Rot channels locate at
  128 (blade square to the lens). The shutter module's blades each rotate
  ±45° (p17 [13]); ETC gives no channel bands for them.
- ChamSys assigns the wheel channels to Macro/FX attribute slots (`Macro1/2`,
  `FX4`, `FX8/7/5`); these are arbitrary mapping choices for the desk's
  encoder layout, not information about what each channel controls.

## Channel 8 — Zoom (`VIEW RANGES`)

| Range   | Type            | Name          |
|---------|-----------------|---------------|
| 000–000 | Wide            | Wide          |
| 001–254 | Wide to Narrow  | Wide > Narrow |
| 255–255 | Narrow          | Narrow        |

Continuous slider, named endpoints only. **(manual)** The zoom runs 15°–35°
(p4 [ii]; field angles 15.3° / 25.3° / 34.3° at the narrow, median and wide
spreads, p47 [43]). The manual does not say which DMX end is wide; DMX 0 wide
is ChamSys's.

## Channel 12 — Reset (manual, p15 [11])

Hold the channel at one of these levels for three seconds, then snap it to 0
with no timing or fade. The fixture recalibrates the named functions and
returns to the incoming DMX.

| Function                        | DMX %  | DMX     |
|---------------------------------|--------|---------|
| Reset entire fixture            | 75     | 185–190 |
| Reset scroller and lenses only  | 60     | 147–152 |
| Reset pan and tilt only         | 50     | 126–129 |
| Reset front module only         | 40     | 97–102  |
| Reset rear module only          | 30     | 72–77   |

ChamSys captured no ranges for this channel.

## Channel 13 — Gel Scroller (`VIEW RANGES`)

ChamSys's 14 bands are named `Frame 0` … `Frame 13`. **(manual)** They match
ETC's standard 12-colour string exactly (p15 [11]); the scroller holds up to
20 frames and ships with this string (p29 [25]):

| Frame | DMX     | Gel     | Name                |
|-------|---------|---------|---------------------|
| 0     | 000–017 | —       | Open white (leader) |
| 1     | 018–036 | R02     | Bastard Amber       |
| 2     | 037–054 | R05     | Rose Tint           |
| 3     | 055–072 | R09     | Pale Amber Gold     |
| 4     | 073–090 | R54     | Special Lavender    |
| 5     | 091–109 | R357    | Royal Lavender      |
| 6     | 110–127 | R36     | Medium Pink         |
| 7     | 128–145 | R25     | Orange Red          |
| 8     | 146–164 | L203    | ¼ C.T. Blue         |
| 9     | 165–182 | L201    | Full C.T. Blue      |
| 10    | 183–200 | R68     | Sky Blue            |
| 11    | 201–218 | R88     | Light Green         |
| 12    | 219–237 | L-HT115 | Peacock Blue        |
| 13    | 238–255 | —       | Open white (trailer)|

R = Roscolux / Rosco Supergel, L = Lee. A venue that has re-strung its
scroller has a different string.

## Channel 14 — Fan Speed (manual, p16 [12])

0 runs every fan at full; higher values lower the speed proportionally
(25% = 75% fan speed), and 255 turns the fans off. The fixture's temperature
sensors override the channel whenever cooling is needed. Keep it low for gel
life.

## Channel 15 — Iris (`VIEW RANGES`)

| Range   | Type          | Name          |
|---------|---------------|---------------|
| 000–000 | Open          | Wide          |
| 001–254 | Open to Closed| Wide > Narrow |
| 255–255 | Closed        | Narrow        |

Continuous slider, named endpoints only. (MagicQ keeps the Wide/Narrow names
from the Zoom-style template.) The iris module (18 leaves) is one of the front
bay's options; the manual gives no bands.

## Channels 16–19 — Front Bay Wheel (manual, p22–24 [18–20])

The front bay takes one of a blank, the iris, a static wheel or a rotating
wheel (p17 [13]). The wheels hold the user's M-size gobos or dichroic filters;
none ship loaded.

**Ch 16, position:**

| Function             | DMX %   | DMX     |
|----------------------|---------|---------|
| Open                 | 0–5     | 0–13    |
| Position 1           | 6–10    | 14–26   |
| Position 2           | 11–15   | 27–39   |
| Position 3           | 16–20   | 40–50   |
| Reserved (position 3)| 21–100  | 51–255  |

**Ch 17, function (rotating wheel):**

| Function  | DMX %  | DMX    |
|-----------|--------|--------|
| Index     | 0–5    | 0–13   |
| Rotate >> | 6–10   | 14–26  |
| Rotate << | 11–15  | 27–39  |
| Reserved  | 16–100 | 40–255 |

**Ch 18/19, index / rotation (16-bit):** in index, the angle the image is
aligned to; in a rotate band, the speed, 0–30 RPM. The manual gives no index
range or rotation direction.

## Channels 20–23 — Reserved

**(manual)** Reserved in "Base + Framing" (p14 [10]): the shutter module must
go in the rear bay (p17 [13]), so there is no rear wheel to address. ChamSys
names them `RB Wheel Pos / Func / Rot`.

## Channel 6 — Internal Media Frame

**(manual)** Two semicircular gel frames ("wings") moved in and out of the
beam by the front lens, for diffusion or colour correction (p31–33 [27–29]).
In or out only; neither ChamSys nor the manual gives bands.

## Channels with no discrete ranges

- **Ch 1 Intensity** — HTP, 0–255.
- **Ch 2/3 Pan**, **Ch 4/5 Tilt** — 16-bit position.
- **Ch 7 Focus** — 0–255. The manual gives no range or direction.
- **Ch 9 Focus Time, Ch 10 Colour Time, Ch 11 Beam Time** — **(manual)** one
  second per DMX step, to 4 min 15 s; record the parameter and its timing
  channel in a zero-fade cue. Ch 9 at 100% is a "console response" mode for
  manual control (p16 [12]).
- **Ch 24–31 Shutters 1–4, in / rotate** — four blades, each rotating ±45°.

## Mapping to the fixture class

`Source4RevolutionFixture.BaseFrame31Ch`:

- `WithDimmer` → ch 1; `WithPosition` → 16-bit pan/tilt (2/3, 4/5).
- No `WithStrobe` and no `WithColour`: colour comes from the gel scroller, a
  `COLOUR` setting whose frames carry the stock string's previews.
- Ch 6 → a two-band `SETTING` (out / in).
- Ch 7 focus, ch 8 zoom (35° → 15°), ch 15 iris → `FOCUS` / `ZOOM` / `IRIS`
  sliders.
- Ch 14 → the fan speed slider.
- Ch 16 → a `GOBO` setting (open, slots 1–3); ch 17 → a `GOBO_ROTATION_MODE`
  setting (`INDEX`, `ROTATE_FWD`, `ROTATE_REV`, `RESERVED`); ch 18/19 → a
  `GOBO_ROTATION` slider and its `fineOf` low byte.
- Ch 24–31 → plain sliders (the fixture optics plan's session 2 models them).
- Ch 12 (Reset) is not exposed: it is a command, not a value, and a reset held
  in a Look would fire on playback. Ch 20–23 are not exposed: reserved.
