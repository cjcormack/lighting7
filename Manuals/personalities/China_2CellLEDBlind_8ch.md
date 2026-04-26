# China 2-Cell LED Blinder — 8ch

> **Source**: ChamSys MagicQ Head Editor, personality file
> `China_2CellLEDBlind_8ch.hed`, captured 2026-04-26 during TCH 2026 patch
> analysis. The on-disk `.hed` and `heads.all` files are obfuscated; this
> transcription is taken from the MagicQ UI's `VIEW CHANS` and `VIEW RANGES`
> screens. To re-verify: open the patched blinder in MagicQ → `EDIT HEAD`.

ChamSys's manufacturer field is the literal string `China` and the model is
`2CellLEDBlind`, with no marketing-name field. The fixture is a 2-cell
audience blinder — each cell has independent warm-white + cold-white
channels (variable colour temperature, no RGB). The 8 channels split as:

- 1 channel of master dimmer (HTP)
- 1 channel of strobe
- 1 channel of built-in programs + 1 channel of program speed
- 2 × 2 channels of per-cell WW/CW

## Channels (`VIEW CHANS`)

| Ch | Name        | Type | Attribute      | Encoder | Size  | Locate | Default | Highlight | Element | Indep |
|----|-------------|------|----------------|---------|-------|--------|---------|-----------|---------|-------|
| 1  | Dimmer      | HTP  | Int (0)        | I1X     | 8 bit | 255    | 000     | 255       | Main    | no    |
| 2  | Strobe      | LTP  | Shutter (2)    | B1A     | 8 bit | 000    | 000     | 000       | Main    | no    |
| 3  | Prog        | LTP  | FX1 Prism (14) | B2C     | 8 bit | 000    | 000     | 000       | Main    | no    |
| 4  | Prog Speed  | LTP  | FX2 (15)       | B2D     | 8 bit | 000    | 000     | 000       | Main    | no    |
| 5  | Warm White  | LTP  | Magenta (17)   | C1B     | 8 bit | 255    | 255     | 255       | 1       | no    |
| 6  | Cold White  | LTP  | Cyan (16)      | C1A     | 8 bit | 255    | 255     | 255       | 1       | no    |
| 7  | Warm White  | LTP  | Magenta (17)   | C1B     | 8 bit | 255    | 255     | 255       | Ind 2   | yes   |
| 8  | Cold White  | LTP  | Cyan (16)      | C1A     | 8 bit | 255    | 255     | 255       | Ind 2   | yes   |

**Notes:**
- Dimmer (ch 1) is HTP and applies globally to both cells.
- Strobe is global, not per-cell.
- ChamSys's `Indep yes` flag on cell 2 (ch 7/8) marks it as an independent
  element — desk operations on cell 1's WW/CW won't accidentally fan-spread
  to cell 2.
- Attribute slots (`FX1 Prism`, `Magenta`, `Cyan`) are arbitrary mapping
  choices for the desk's encoder layout, not authoritative info about what
  each channel does. The blinder has no prism, no magenta, no cyan — they
  are warm/cold whites that ChamSys pinned to those encoder slots so the
  cells line up vertically with RGB-fixture encoders.

## Channel 2 — Strobe (`VIEW RANGES`)

| Range   | Type       | Name       |
|---------|------------|------------|
| 000–010 | Open       | Open       |
| 011–255 | Strobe S>F | Strobe S>F |

The "Open" band here is wider than the typical 0–0 — `0–10` all read as
`Open`. The strobe band starts at 011.

## Channel 3 — Prog (`VIEW RANGES`)

| Range   | Name         |
|---------|--------------|
| 000–039 | No Function  |
| 040–079 | Col Jump     |
| 080–119 | Col Gradient |
| 120–159 | Col Pulse    |
| 160–199 | Col Mutation |
| 200–239 | Auto Mode    |
| 240–255 | Sound Active |

The seven bands are evenly-spaced (~40 values each except `Sound Active`
at 16). Within each band the level controls the speed/intensity of that
program.

## Channels with no discrete ranges

- **Ch 1 Dimmer** — HTP, 0–255 linear.
- **Ch 4 Prog Speed** — speed of the built-in program selected on ch 3.
- **Ch 5/6, Ch 7/8** — variable-white per cell (Warm White + Cold White).
  Continuous sliders; no `VIEW RANGES` entries beyond the implicit 0–255.

## Mapping to fixture traits

When `China2CellLedBlinderFixture` is implemented (Tier 8):

- `WithDimmer` → ch 1 (HTP, applies globally).
- `WithStrobe` → ch 2 (`fullOn` → 0, strobe band → 011–255).
- `MultiElementFixture<Cell>` with 2 cells. Each cell exposes warm-white
  and cold-white as plain `WithWhite`-category sliders (no trait — there
  is no `WithWarmWhite` trait, and using `WithWhite` for one of them
  would imply asymmetry that isn't there).
- `DmxFixtureSetting<Program>` → ch 3 with the seven band-start levels.
- Plain `Slider` → ch 4 (Prog Speed).
