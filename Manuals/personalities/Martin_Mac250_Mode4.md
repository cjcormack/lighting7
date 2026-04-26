# Martin MAC 250 — Mode 4 (13ch)

> **Source**: ChamSys MagicQ Head Editor, personality file `Martin_Mac250_Mode 4.hed`,
> captured 2026-04-26 during TCH 2026 patch analysis.
> The on-disk `.hed` and `heads.all` files are obfuscated; this transcription is taken
> from the MagicQ UI's `VIEW CHANS` and `VIEW RANGES` screens. To re-verify: open the
> patched MAC 250 in MagicQ → `EDIT HEAD`.

This is the **original** MAC 250 (not Krypton, Entour, Wash, Beam, or 250+).
Identifiable in MagicQ by the personality key `Martin,Mac250,Mode 4` and by the channel
layout — single colour wheel + single gobo wheel (no CMY mixing).

The MAC 250+ has an identical Mode 4 channel layout (only Mode 3 diverged in the refresh),
so a MAC 250+ user manual can serve as a human-readable reference if one is needed.

## Channels (`VIEW CHANS`)

| Ch | Name      | Type | Attribute       | Encoder | Size      | Locate | Default | Highlight |
|----|-----------|------|-----------------|---------|-----------|--------|---------|-----------|
| 1  | Shutter   | LTP  | Shutter (2)     | B1A     | 8 bit     | 035    | 035     | 035       |
| 2  | Dimmer    | HTP  | Int (0)         | I1X     | 8 bit     | 255    | 000     | 255       |
| 3  | Col       | LTP  | Col1 (6)        | C1X     | 8 bit     | 000    | 000     | 000       |
| 4  | Gobo      | LTP  | Gobo1 (8)       | B1X     | 8 bit     | 000    | 000     | 000       |
| 5  | Rotate    | LTP  | Rotate1 (10)    | B1F     | 8 bit     | 000    | 000     | 000       |
| 6  | Focus     | LTP  | Focus (12)      | B1C     | 8 bit     | 070    | 070     | 070       |
| 7  | Prism     | LTP  | FX1 Prism (14)  | B1D     | 8 bit     | 000    | 000     | 000       |
| 8  | Pan       | LTP  | Pan (4)         | P1X     | 16 bit hi | 128    | 128     | no level  |
| 9  | Pan lo    | LTP  | Pan (4)         | P1X     | 16 bit lo | 128    | 128     | no level  |
| 10 | Tilt      | LTP  | Tilt (5)        | P1Y     | 16 bit hi | 128    | 128     | no level  |
| 11 | Tilt lo   | LTP  | Tilt (5)        | P1Y     | 16 bit lo | 128    | 128     | no level  |
| 12 | P/T Speed | LTP  | Pos6-Speed (51) | P1F     | 8 bit     | 000    | 000     | 000       |
| 13 | Speed FX  | LTP  | FX4 (35)        | B3D     | 8 bit     | 000    | 000     | 000       |

**Notes:**
- Dimmer is the only HTP channel.
- Pan/Tilt are 16-bit (paired hi/lo channels).
- "Snap" (instant on/off) was set on Pan and Tilt MSB in the patched instance.
- Lamp control lives on the Shutter channel (228–237 Lamp On, 248–255 Lamp Off) — the
  fixture class needs to be careful that random FX targeting `Strobe` cannot wander into
  those bands.

## Channel 1 — Shutter (`VIEW RANGES`)

| Range   | Type           | Name        |
|---------|----------------|-------------|
| 000–019 | Closed         | Closed      |
| 020–049 | Open           | Open        |
| 050–072 | Strobe F>S     | Strobe      |
| 080–099 | Pulse S>F      | Pulse Open  |
| 100–119 | Pulse Close    | Pulse Close |
| 128–147 | None           | Rnd F       |
| 148–167 | Rnd Strobe S>F | Rnd         |
| 168–187 | None           | Rnd S       |
| 191–193 | None           | Rnd Open F  |
| 194–196 | None           | Rnd Open S  |
| 197–199 | None           | Rnd Close F |
| 200–202 | None           | Rnd Close S |
| 208–217 | None           | Reset Fix   |
| 228–237 | None           | Lamp On     |
| 248–255 | None           | Lamp Off    |

## Channel 3 — Col (colour wheel) (`VIEW RANGES`)

Split positions (smooth half-and-half between adjacent colours):

| Range   | Type  | Name              |
|---------|-------|-------------------|
| 001–012 | Split | White > CTC       |
| 013–024 | Split | CTC > Yellow      |
| 025–036 | Split | Yellow > Blue     |
| 037–048 | Split | Blue > Pink       |
| 049–064 | Split | Pink > Green49    |
| 065–072 | Split | Green > Blue108   |
| 073–084 | Split | Blue108 > Red     |
| 085–096 | Split | Red > Magenta     |
| 097–108 | Split | Magenta > Blue1   |
| 109–120 | Split | Blue101 > Orang   |
| 121–132 | Split | Orange > Green2   |
| 133–144 | Split | Green > Purple    |

Indexed colour positions:

| Range   | Name     |
|---------|----------|
| 156–159 | Purple   |
| 160–163 | Green202 |
| 164–167 | Orange   |
| 168–171 | Blue101  |
| 172–175 | Magenta  |
| 176–179 | Red      |
| 180–183 | Blue108  |
| 184–187 | Green    |
| 188–191 | Pink     |
| 192–195 | Blue     |
| 196–199 | Yellow   |
| 200–203 | CTC      |
| 204–207 | White    |

Effects:

| Range   | Type    | Name          |
|---------|---------|---------------|
| 208–226 | CW F>S  | Col Scroll CW |
| 227–245 | CCW S>F | Col Scroll CCW|
| 246–248 | None    | Rnd Col F     |
| 249–251 | None    | Rnd Col M     |
| 252–255 | None    | Rnd Col S     |

## Channel 4 — Gobo (`VIEW RANGES`)

| Range   | Type  | Name             |
|---------|-------|------------------|
| 000–009 | None  | Open             |
| 010–019 | None  | Cone             |
| 020–029 | None  | Bar              |
| 030–039 | None  | Fan Hat          |
| 040–049 | None  | Triple           |
| 050–059 | None  | Dec Beam         |
| 060–069 | None  | Fibroid          |
| 070–079 | None  | Rnd Holes Blue   |
| 080–089 | None  | Pys Cir Mag      |
| 090–104 | Shake | Pys Cir Shake    |
| 105–119 | Shake | Rnd Holes Shake  |
| 120–134 | Shake | Fibroid Shake    |
| 135–149 | Shake | Dec Beam Shake   |
| 150–164 | Shake | Triple Shake     |
| 165–179 | Shake | Fan Hat Shake    |
| 180–194 | Shake | Bar Shake        |
| 195–209 | Shake | Cone Shake       |
| 210–232 | None  | Gobo Scrl CW     |
| 233–255 | None  | Gobo Scrl CCW    |

## Channel 7 — Prism (`VIEW RANGES`)

| Range   | Name           |
|---------|----------------|
| 000–019 | Prism Off      |
| 020–079 | Rot Ccw Prism  |
| 080–089 | No Rot Prism   |
| 090–149 | Rot Cw Prism   |
| 150–215 | Prism Off      |
| 216–220 | Macro 1        |
| 221–225 | Macro 2        |
| 226–230 | Macro 3        |
| 231–235 | Macro 4        |
| 236–240 | Macro 5        |
| 241–245 | Macro 6        |
| 246–250 | Macro 7        |
| 251–255 | Macro 8        |

## Channels with no discrete ranges

These are continuous controls; no `VIEW RANGES` entries beyond the implicit 0–255:

- **Ch 2 Dimmer** — 0–255 linear, HTP.
- **Ch 5 Rotate** — gobo rotation (CCW/stop/CW typical band layout, exact split not captured).
- **Ch 6 Focus** — 0–255 linear.
- **Ch 8/9 Pan, 10/11 Tilt** — 16-bit position.
- **Ch 12 P/T Speed** — pan/tilt speed control.
- **Ch 13 Speed FX** — effect/macro speed.

## Mapping to fixture traits

When `MartinMac250Fixture` is implemented (Tier 5):

- `WithDimmer` → ch 2.
- `WithStrobe` → ch 1; expose only the safe bands (Closed/Open/Strobe). Lamp on/off and Reset
  must not be addressable from FX or random output — surface them as explicit methods
  (`lampOn()`, `lampOff()`, `reset()`) or guard them in `Strobe.strobe(intensity)`.
- `WithPosition` → 16-bit pan/tilt (channels 8/9, 10/11).
- `DmxFixtureSetting<DmxFixtureColourSettingValue>` → ch 3, with hex preview swatches per
  the indexed colour table.
- `DmxFixtureSetting` enums → ch 4 (Gobo), ch 7 (Prism), ch 12 (P/T Speed band), ch 13 (Speed FX).
- Plain `Slider` → ch 5 (Rotate), ch 6 (Focus).
