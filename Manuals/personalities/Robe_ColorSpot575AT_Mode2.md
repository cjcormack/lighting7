# Robe ColorSpot 575 AT — Mode 2 (19ch)

> **Sources**:
> - ChamSys MagicQ Head Editor, personality file `Robe_Spot575_Mode 2.hed`,
>   captured 2026-04-26 during TCH 2026 patch analysis.
> - Robe DMX charts:
>   [`Manuals/ColorSpot_575_AT_DMX_charts.pdf`](../ColorSpot_575_AT_DMX_charts.pdf)
>   ("DMX Protocol-version 1.0", page 14ff). The Mode 2 column was used.
> - Robe user manual:
>   [`Manuals/User_manual_ColorSpot_575_AT.pdf`](../User_manual_ColorSpot_575_AT.pdf).
>
> The MagicQ `VIEW RANGES` capture was spot-checked against the Robe DMX chart
> for ch 5–9 (Speed, Control, Col 1, Col 2, Static Gobo); every band aligned.
> Channels 10–19 were not screenshotted but the chart's Mode 2 column maps
> directly onto the same channel order.

This is the discharge moving-head spot personality patched on universe 1
(`01-202` → `01-221` × 4 fixtures). The Robe chart enumerates four
personalities (Modes 1/2/3/4) at 27/19/29/21 channels; only Mode 2 is patched.

## Channels (`VIEW CHANS`)

| Ch | Name        | Type | Attribute        | Encoder | Size      | Locate | Default | Highlight |
|----|-------------|------|------------------|---------|-----------|--------|---------|-----------|
| 1  | Pan         | LTP  | Pan (4)          | P1X     | 16 bit hi | 128    | 128     | no level  |
| 2  | Pan F       | LTP  | Pan (4)          | P1X     | 16 bit lo | 000    | 000     | no level  |
| 3  | Tilt        | LTP  | Tilt (5)         | P1Y     | 16 bit hi | 128    | 128     | no level  |
| 4  | Tilt F      | LTP  | Tilt (5)         | P1Y     | 16 bit lo | 000    | 000     | no level  |
| 5  | P/T Speed   | LTP  | Pos6-Speed (51)  | P1F     | 8 bit     | 000    | 000     | 000       |
| 6  | Control     | LTP  | Cont1 (20)       | B4A     | 8 bit     | 000    | 000     | 000       |
| 7  | Col 1       | LTP  | Col1 (6)         | C1X     | 8 bit     | 000    | 000     | 038       |
| 8  | Col 2       | LTP  | Col2 (7)         | C1Y     | 8 bit     | 000    | 000     | 000       |
| 9  | Static Gobo | LTP  | Gobo2 (9)        | B1Y     | 8 bit     | 000    | 000     | 000       |
| 10 | Rot Gobo    | LTP  | Gobo1 (8)        | B1X     | 8 bit     | 000    | 000     | 000       |
| 11 | Gobo Rot    | LTP  | Rotate1 (10)     | B1F     | 8 bit     | 000    | 000     | 000       |
| 12 | Prism       | LTP  | FX1 Prism (14)   | B2C     | 8 bit     | 000    | 000     | 000       |
| 13 | Prism Rot   | LTP  | Pri Rot (31)     | B2E     | 8 bit     | 000    | 000     | 000       |
| 14 | Frost       | LTP  | Frost1 (32)      | B2A     | 8 bit     | 000    | 000     | 000       |
| 15 | Iris        | LTP  | Iris (3)         | B1B     | 8 bit     | 000    | 000     | 000       |
| 16 | Zoom        | LTP  | Zoom (13)        | B1D     | 8 bit     | 000    | 000     | 000       |
| 17 | Focus       | LTP  | Focus (12)       | B1C     | 8 bit     | 070    | 070     | 070       |
| 18 | Shutter     | LTP  | Shutter (2)      | B1A     | 8 bit     | 035    | 035     | 035       |
| 19 | Dimmer      | HTP  | Int (0)          | I1X     | 8 bit     | 255    | 000     | 255       |

**Notes:**
- Dimmer is the only HTP channel.
- Pan/Tilt are 16-bit (paired hi/lo channels).
- Shutter locate 035 puts the fixture in the 032–063 "Open" band; dimmer
  locate 255 brings the fixture to full when the desk's `LOCATE` button is hit.
- Discharge lamp + reset bands live on **channel 6 (Control)**, not the
  shutter channel as on the MAC 250. The fixture class must keep ch 6
  un-FX-targetable.

## Channel 5 — P/T Speed (`VIEW RANGES`)

| Range   | Type | Name           |
|---------|------|----------------|
| 000–000 | None | Max Spd Track  |
| 001–249 | None | Var Spd Vector |
| 250–252 | None | Max Spd Track  |
| 253–255 | None | Max Spd Vector |

The chart documents this channel's value depending on the fixture-menu
`P.t.Mo.` setting: `SP.Mo` (speed mode) interprets 1–255 as max→min speed,
`ti.Mo` (time mode) as 0.1s→25.5s.

## Channel 6 — Control (`VIEW RANGES`)

Reset and lamp control bands. **Each band must be held for ≥3s** for the
fixture to act on it; the strobe (ch 18) must additionally be closed (0–31)
for the lower bands. The class's lamp/reset methods are responsible for
holding the value long enough — call them as discrete write-and-wait
operations, not from FX loops.

| Range   | Type    | Name             |
|---------|---------|------------------|
| 000–049 | None    | Reserved         |
| 050–059 | None    | P/T Sp Mode      |
| 060–069 | None    | P/T Tim Mode     |
| 070–079 | None    | Blackout Move    |
| 080–089 | None    | Dis Black Move   |
| 090–099 | None    | Blackout Col     |
| 100–109 | None    | Dis Black Col    |
| 110–119 | None    | Black Gobo       |
| 120–129 | None    | Dis Black Gobo   |
| 130–139 | Lamp On | Lamp On          |
| 140–149 | None    | P/T Reset        |
| 150–159 | None    | Col Reset        |
| 160–169 | None    | Gobo Reset       |
| 170–179 | None    | Dim Reset        |
| 180–189 | None    | Focus Reset      |
| 190–199 | None    | Iris Reset       |
| 200–209 | None    | Total Reset      |
| 210–229 | None    | Reserved         |
| 230–239 | None    | Lamp Off         |
| 240–255 | None    | No Function      |

## Channel 7 — Colour wheel 1 (`VIEW RANGES`)

Continual positioning (0–129) scrolls smoothly between adjacent colours;
positioning (130–189) jumps to indexed colours. The chart's single-value
indexed positions for the continual band fall inside MagicQ's "Scrl" ranges
(e.g. chart's 13 "Light blue" → MagicQ 007–019 "Lt Blue Scrl"); the `level`
on each enum entry is taken from the chart's centre value so a UI swatch
shows the named colour.

| Range   | Type   | Name           | Chart "Continual" centre |
|---------|--------|----------------|--------------------------|
| 000–006 | Fixed  | White          | 0                        |
| 007–019 | Fixed  | Lt Blue Scrl   | 13                       |
| 020–034 | Fixed  | Red Scrl       | 26                       |
| 035–047 | Fixed  | Blue Scrl      | 39                       |
| 048–059 | Fixed  | Lt Green Scrl  | 52                       |
| 060–072 | Fixed  | Yellow Scrl    | 65                       |
| 073–086 | Fixed  | Magenta Scrl   | 78                       |
| 087–097 | Fixed  | Cyan Scrl      | 91                       |
| 098–110 | Fixed  | Green Scrl     | 104                      |
| 115–127 | Fixed  | Orange Scrl    | 117                      |
| 128–129 | Fixed  | White          |                          |
| 130–136 | Fixed  | Lt Blue        |                          |
| 137–143 | Fixed  | Red            |                          |
| 144–149 | Fixed  | Blue           |                          |
| 157–163 | Fixed  | Yellow         |                          |
| 164–169 | Fixed  | Magenta        |                          |
| 170–176 | Fixed  | Cyan           |                          |
| 177–183 | Fixed  | Green          |                          |
| 184–189 | Fixed  | Orange         |                          |
| 190–215 | CW F>S | Col Scroll CW  |                          |
| 216–217 | None   | No Rot Col     |                          |
| 218–243 | None   | Rev Rainbow    |                          |
| 244–249 | None   | Rnd Col        |                          |
| 250–255 | None   | Auto Rnd       |                          |

(MagicQ's capture omits a Light-green positioning band at 150–156 — the
chart has it. Treat it as present at 150 if needed.)

## Channel 8 — Colour wheel 2 (`VIEW RANGES`)

| Range   | Type   | Name             | Chart "Continual" centre |
|---------|--------|------------------|--------------------------|
| 000–011 | Fixed  | White            | 0                        |
| 012–023 | Fixed  | Deep Red Scrl    | 14                       |
| 024–035 | Fixed  | Deep Blue Scrl   | 28                       |
| 036–049 | Fixed  | Pink Scrl        | 42                       |
| 050–062 | Fixed  | Cyan Scrl        | 56                       |
| 063–075 | Fixed  | Magenta Scrl     | 70                       |
| 076–090 | Fixed  | Yellow           | 84                       |
| 091–105 | Fixed  | 3200 K Scrl      | 98                       |
| 106–127 | Fixed  | UV Filter Scrl   | 112                      |
| 128–129 | Fixed  | White            |                          |
| 130–136 | Fixed  | Deep Red         |                          |
| 137–144 | Fixed  | Deep Blue        |                          |
| 145–151 | Fixed  | Pink             |                          |
| 152–159 | Fixed  | Cyan             |                          |
| 160–166 | Fixed  | Magenta          |                          |
| 167–174 | Fixed  | Yellow           |                          |
| 175–181 | Fixed  | 3200 K           |                          |
| 182–189 | Fixed  | UV Filter        |                          |
| 190–215 | CW F>S | Col Scroll CW    |                          |
| 216–217 | None   | No Rot Col       |                          |
| 218–243 | None   | Rev Rainbow      |                          |
| 244–249 | None   | Rnd Col          |                          |
| 250–255 | None   | Auto Rnd         |                          |

## Channel 9 — Static gobo wheel (`VIEW RANGES` partial; remainder from chart)

Continual positioning (0–64) and step positioning (65–109), then 9 shake
bands (110–199), open at 200–201, scroll 202–243, random 244–255.

| Range   | Type  | Name          |
|---------|-------|---------------|
| 000–005 | Fixed | Open          |
| 006–010 | Fixed | Gobo 1 Scrl   |
| 011–016 | Fixed | Gobo 2 Scrl   |
| 017–022 | Fixed | Gobo 3 Scrl   |
| 023–029 | Fixed | Gobo 4 Scrl   |
| 030–036 | Fixed | Gobo 5 Scrl   |
| 037–043 | Fixed | Gobo 6 Scrl   |
| 044–047 | Fixed | Gobo 7 Scrl   |
| 048–054 | Fixed | Gobo 8 Scrl   |
| 055–062 | Fixed | Gobo 9 Scrl   |
| 063–064 | Fixed | Open Gobo     |
| 065–069 | Fixed | Gobo 1        |
| 070–074 | Fixed | Gobo 2        |
| 075–079 | Fixed | Gobo 3        |
| 080–084 | Fixed | Gobo 4        |
| 085–089 | Fixed | Gobo 5        |
| 090–094 | Fixed | Gobo 6        |
| 095–099 | Fixed | Gobo 7        |
| 100–104 | Fixed | Gobo 8        |
| 105–109 | Fixed | Gobo 9        |
| 110–119 | Shake | Gobo 1 Shake  |
| 120–129 | Shake | Gobo 2 Shake  |
| 130–139 | Shake | Gobo 3 Shake  |
| 140–149 | Shake | Gobo 4 Shake  |
| 150–159 | Shake | Gobo 5 Shake  |
| 160–169 | Shake | Gobo 6 Shake  |
| 170–179 | Shake | Gobo 7 Shake  |
| 180–189 | Shake | Gobo 8 Shake  |
| 190–199 | Shake | Gobo 9 Shake  |
| 200–201 | None  | Open Gobo     |
| 202–221 | CW    | Gobo Scroll   |
| 222–223 | None  | No Rot        |
| 224–243 | CCW   | Gobo Rev Scrl |
| 244–249 | None  | Rnd Gobo      |
| 250–255 | None  | Auto Rnd Gobo |

## Channel 10 — Rotating gobo wheel (chart only)

7 indexable gobos with index/rotation modes plus shake variants. The chart's
ranges:

| Range   | Mode      | Name        |
|---------|-----------|-------------|
| 000–003 | Index     | Open        |
| 004–007 | Index     | Gobo 1      |
| 008–011 | Index     | Gobo 2      |
| 012–015 | Index     | Gobo 3      |
| 016–019 | Index     | Gobo 4      |
| 020–023 | Index     | Gobo 5      |
| 024–027 | Index     | Gobo 6      |
| 028–031 | Index     | Gobo 7      |
| 032–035 | Rotate    | Gobo 1      |
| 036–039 | Rotate    | Gobo 2      |
| 040–043 | Rotate    | Gobo 3      |
| 044–047 | Rotate    | Gobo 4      |
| 048–051 | Rotate    | Gobo 5      |
| 052–055 | Rotate    | Gobo 6      |
| 056–059 | Rotate    | Gobo 7      |
| 060–069 | Shake/Idx | Gobo 1      |
| 070–079 | Shake/Idx | Gobo 2      |
| 080–089 | Shake/Idx | Gobo 3      |
| 090–099 | Shake/Idx | Gobo 4      |
| 100–109 | Shake/Idx | Gobo 5      |
| 110–119 | Shake/Idx | Gobo 6      |
| 120–129 | Shake/Idx | Gobo 7      |
| 130–139 | Shake/Rot | Gobo 1      |
| 140–149 | Shake/Rot | Gobo 2      |
| 150–159 | Shake/Rot | Gobo 3      |
| 160–169 | Shake/Rot | Gobo 4      |
| 170–179 | Shake/Rot | Gobo 5      |
| 180–189 | Shake/Rot | Gobo 6      |
| 190–199 | Shake/Rot | Gobo 7      |
| 200–201 | —         | Open        |
| 202–221 | CW F>S    | Wheel Scrl  |
| 222–223 | —         | No Rot      |
| 224–243 | CCW S>F   | Wheel Rev   |
| 244–249 | —         | Rnd Gobo    |
| 250–255 | —         | Auto Rnd    |

## Channel 11 — Gobo indexing/rotation (chart only)

Meaning depends on ch 10's mode (index vs rotation). In rotation mode:
0 = no rotation, 1–127 = forwards fast→slow, 128–129 = no rotation,
130–255 = backwards slow→fast. In index mode, the value is a 0–255
proportional gobo position. Modelled as a plain slider — scripts decide
which interpretation applies.

## Channel 12 — Prism (chart only)

| Range   | Name       |
|---------|------------|
| 000–019 | Prism Off  |
| 020–127 | 3-Facet    |
| 128–135 | Macro 1    |
| 136–143 | Macro 2    |
| 144–151 | Macro 3    |
| 152–159 | Macro 4    |
| 160–167 | Macro 5    |
| 168–175 | Macro 6    |
| 176–183 | Macro 7    |
| 184–191 | Macro 8    |
| 192–199 | Macro 9    |
| 200–207 | Macro 10   |
| 208–215 | Macro 11   |
| 216–223 | Macro 12   |
| 224–231 | Macro 13   |
| 232–239 | Macro 14   |
| 240–247 | Macro 15   |
| 248–255 | Macro 16   |

## Channel 13 — Prism rotation (chart only)

| Range   | Name           |
|---------|----------------|
| 000–000 | No Rotation    |
| 001–127 | CW (fast→slow) |
| 128–129 | No Rotation    |
| 130–255 | CCW (slow→fast)|

Modelled as a plain slider; band semantics documented inline.

## Channel 14 — Frost (chart only)

| Range   | Name                |
|---------|---------------------|
| 000–000 | Open                |
| 001–179 | Frost 0%→100%       |
| 180–189 | 100% Frost          |
| 190–211 | Pulse Close S→F     |
| 212–233 | Pulse Open F→S      |
| 234–255 | Ramping F→S         |

Plain slider; effect bands written raw if needed.

## Channel 15 — Iris (chart only)

| Range   | Name                       |
|---------|----------------------------|
| 000–000 | Open                       |
| 001–179 | Max → Min diameter         |
| 180–191 | Closed                     |
| 192–219 | Pulse Open S→F             |
| 220–247 | Pulse Close F→S            |
| 248–249 | Random Pulse Open (fast)   |
| 250–251 | Random Pulse Open (slow)   |
| 252–253 | Random Pulse Close (fast)  |
| 254–255 | Random Pulse Close (slow)  |

Plain slider.

## Channel 16 — Zoom (chart only)

Three zoom presets, with and without focus correction:

| Range   | Name (no focus corr.) |
|---------|------------------------|
| 000–039 | Zoom 15°              |
| 040–079 | Zoom 18°              |
| 080–127 | Zoom 22°              |
| 128–169 | Zoom 15° (focus corr.) |
| 170–219 | Zoom 18° (focus corr.) |
| 220–255 | Zoom 22° (focus corr.) |

Plain slider; zoom positions accessible by raw value. (Could be modelled as
an enum, but the value is essentially three discrete positions × two modes,
so a slider is enough.)

## Channel 17 — Focus (chart only)

Continuous coarse focus 0–255. Locate 70.

## Channel 18 — Shutter / Strobe (chart only)

| Range   | Name                          |
|---------|-------------------------------|
| 000–031 | Shutter Closed                |
| 032–063 | No Function (shutter open)    |
| 064–095 | Strobe S→F                    |
| 096–127 | No Function (shutter open)    |
| 128–143 | Opening pulse in seq S→F      |
| 144–159 | Closing pulse in seq F→S      |
| 160–191 | No Function (shutter open)    |
| 192–223 | Random strobe S→F             |
| 224–255 | No Function (shutter open)    |

The fixture class clamps the strobe slider to 0–95: `Strobe.fullOn()` writes
35 (open) and `Strobe.strobe(intensity)` linearly maps 0–255 → 64–95. Pulse
bands and random-strobe bands are reachable via raw transaction writes only.

## Channel 19 — Dimmer (chart only)

Continuous 0–255 (HTP). Locate 255.

## Mapping to fixture traits

When `RobeColorSpot575Fixture` is implemented (Tier 6):

- `WithDimmer` → ch 19 (HTP).
- `WithStrobe` → ch 18; expose only the safe band (closed/open/strobe at
  0–95).
- `WithPosition` → 16-bit pan/tilt (ch 1/2, 3/4).
- `DmxFixtureSetting<DmxFixtureColourSettingValue>` → ch 7 (Col 1) and ch 8
  (Col 2), with hex preview swatches per the indexed colour table.
- `DmxFixtureSetting` enums → ch 9 (Static Gobo), ch 10 (Rot Gobo), ch 12
  (Prism).
- Plain `Slider` → ch 5 (P/T Speed), ch 11 (Gobo rotation), ch 13 (Prism
  rotation), ch 14 (Frost), ch 15 (Iris), ch 16 (Zoom), ch 17 (Focus).
- **Channel 6 (Control) is NOT exposed as `@FixtureProperty`** — lamp/reset
  bands live there. Surface them as explicit methods (`lampOn()`,
  `lampOff()`, `reset()` family) that bypass the property system, mirroring
  the MAC 250's strobe-channel approach. Configuration bands (P/T speed
  mode, blackout-while-moving) are also written via raw transaction calls
  since they're set-once-at-startup, not runtime FX.
