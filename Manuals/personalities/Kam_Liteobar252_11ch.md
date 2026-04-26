# Kam Liteobar 252 — 11ch

> **Source**: ChamSys MagicQ Head Editor, personality file
> `Kam_Liteobar252_11ch.hed`, captured 2026-04-26 during TCH 2026 patch
> analysis. The on-disk `.hed` and `heads.all` files are obfuscated; this
> transcription is taken from the MagicQ UI's `VIEW CHANS` and `VIEW RANGES`
> screens. To re-verify: open the patched Liteobar in MagicQ → `EDIT HEAD`.

ChamSys's marketing-name field for this personality is empty, so we use
ChamSys's spelling: `manufacturer = "Kam"`, `model = "Liteobar 252"`.
Personality key `Kam_Liteobar252_11ch`. The 11 channels split as:

- 1 channel of master macro (blackout / dimmer modes / built-in effects)
- 1 channel of strobe
- 3 × 3 channels of per-cell RGB (three independently addressable cells)

There is no continuous master dimmer — overall brightness comes from the
cell RGB values themselves. The macro channel must be in the **Dimmer 1**
or **Dimmer 2** range (041–120) for the cells to be visible; values
below 041 are blackout regardless of cell RGB levels.

## Channels (`VIEW CHANS`)

| Ch | Name   | Type | Attribute      | Encoder | Size  | Locate | Default | Highlight | Lowlight | Element |
|----|--------|------|----------------|---------|-------|--------|---------|-----------|----------|---------|
| 1  | Macro  | LTP  | White (19)     | C1D     | 8 bit | 041    | 000     | 041       | no level | Main    |
| 2  | Strobe | LTP  | Shutter (2)    | B1A     | 8 bit | 000    | 000     | 000       | no level | Main    |
| 3  | Red    | LTP  | Cyan (16)      | C1A     | 8 bit | 255    | 000     | 255       | 000      | 1       |
| 4  | Green  | LTP  | Magenta (17)   | C1B     | 8 bit | 255    | 000     | 255       | 000      | 1       |
| 5  | Blue   | LTP  | Yellow (18)    | C1C     | 8 bit | 255    | 000     | 255       | 255      | 1       |
| 6  | Red    | LTP  | Cyan (16)      | C1A     | 8 bit | 255    | 000     | 255       | 000      | Dup 2   |
| 7  | Green  | LTP  | Magenta (17)   | C1B     | 8 bit | 255    | 000     | 255       | 000      | Dup 2   |
| 8  | Blue   | LTP  | Yellow (18)    | C1C     | 8 bit | 255    | 000     | 255       | 255      | Dup 2   |
| 9  | Red    | LTP  | Cyan (16)      | C1A     | 8 bit | 255    | 000     | 255       | 000      | Dup 3   |
| 10 | Green  | LTP  | Magenta (17)   | C1B     | 8 bit | 255    | 000     | 255       | 000      | Dup 3   |
| 11 | Blue   | LTP  | Yellow (18)    | C1C     | 8 bit | 255    | 000     | 255       | 255      | Dup 3   |

**Notes:**
- All channels are LTP; there is no HTP master dimmer.
- ChamSys's `Element` column splits the three cells into Main / Element 1 +
  Dup 2 + Dup 3, matching the multi-element pattern used by
  `SlenderBeamBarQuadFixture` and `ShehdsLed19RgbwFixture`.
- Macro channel locate is 041 (start of `Dimmer 1`), so a desk `Locate`
  command produces a usable "cells working normally" state.

## Channel 1 — Macro (`VIEW RANGES`)

| Range   | Name       |
|---------|------------|
| 000–040 | Black Out  |
| 041–080 | Dimmer 1   |
| 081–120 | Dimmer 2   |
| 121–160 | Col Flash  |
| 161–199 | Col Change |
| 200–240 | Col Flow   |
| 241–255 | Dream Flow |

`Dimmer 1` and `Dimmer 2` both pass cell RGB straight through (the manual /
ChamSys does not document a difference between them); the four `Col …` and
`Dream Flow` bands are built-in colour effects whose speed is selected by
the level within the band.

## Channel 2 — Strobe (`VIEW RANGES`)

| Range   | Type       | Name       |
|---------|------------|------------|
| 000–000 | Open       | Open       |
| 001–255 | Strobe S>F | Strobe S>F |

Continuous slider, 0 = LED constant on, 1–255 = strobe slow → fast.

## Palettes (`VIEW PALETTES`)

For reference, ChamSys provides these auto-palettes that drive the cell
RGB channels (3/4/5) — the same triples are duplicated to the other two
cells via the auto-icons mechanism:

| Name    | Red | Green | Blue |
|---------|-----|-------|------|
| White   | 255 | 255   | 255  |
| Red     | 255 | 000   | 000  |
| Amber   | 255 | 127   | 000  |
| Yellow  | 255 | 255   | 000  |
| Green   | 000 | 255   | 000  |
| Cyan    | 000 | 255   | 255  |
| Blue    | 000 | 000   | 255  |
| Pink    | 255 | 105   | 180  |
| UV      | 075 | 000   | 130  |
| Magenta | 255 | 000   | 255  |

The fixture is RGB only (no white / amber / UV channels), so amber, pink
and UV are simulated by the RGB triples shown above.

## Mapping to fixture traits

When `KamLiteobar252Fixture` is implemented (Tier 8):

- No `WithDimmer` — the fixture has no continuous master dimmer. Brightness
  comes from the cell RGB values; the macro must be in `DIMMER_1` /
  `DIMMER_2` for cells to be visible.
- `WithStrobe` → ch 2; modelled like the Varytec Easymove (0 = constant on,
  1–255 = slow → fast).
- `MultiElementFixture<Cell>` with 3 cells, each cell `WithColour` (RGB).
- `DmxFixtureSetting<Macro>` → ch 1, with the seven band-start levels.
