# Gear4Music SOL Party 12B — 8ch

> **Source**: ChamSys MagicQ Head Editor, personality file
> `Gear4Music_SOLParty12B_8ch.hed`, captured 2026-04-26 during TCH 2026
> patch analysis. The on-disk `.hed` and `heads.all` files are obfuscated;
> this transcription is taken from the MagicQ UI's `VIEW CHANS`,
> `VIEW RANGES`, and `VIEW PALETTES` screens. To re-verify: open the
> patched SOL Party in MagicQ → `EDIT HEAD`.

ChamSys personality key `Gear4Music,SOLParty12B,8ch`. Marketing-name field
`SOL Party 12B`. The 8 channels split as:

- 1 channel of internal mode select
- 1 channel of built-in colour wheel + 1 channel of colour-wheel speed
- 1 channel of HTP master dimmer
- 3 channels of single global RGB
- 1 channel of FX / prism

Single global RGB only — there are no per-cell channels. The colour wheel
on ch 2 is a self-contained band-based effect; manual RGB on ch 5/6/7 is
intended for use when the colour wheel is at 000 (`All Col` / off-band).

## Channels (`VIEW CHANS`)

| Ch | Name      | Type | Attribute        | Encoder | Size  | Locate | Default | Highlight | Lowlight |
|----|-----------|------|------------------|---------|-------|--------|---------|-----------|----------|
| 1  | Int Mode  | LTP  | Int mode (1)     | I1Y     | 8 bit | 000    | 000     | 000       | no level |
| 2  | Col Wheel | LTP  | Col1 (6)         | C1X     | 8 bit | 000    | 000     | 000       | no level |
| 3  | Col Speed | LTP  | Col Speed (26)   | C2F     | 8 bit | 000    | 000     | 000       | no level |
| 4  | Dimmer    | HTP  | Int (0)          | I1X     | 8 bit | 255    | 000     | 255       | 128      |
| 5  | Red       | LTP  | Cyan (16)        | C1A     | 8 bit | 255    | 255     | 255       | 000      |
| 6  | Green     | LTP  | Magenta (17)     | C1B     | 8 bit | 255    | 255     | 255       | 000      |
| 7  | Blue      | LTP  | Yellow (18)      | C1C     | 8 bit | 255    | 255     | 255       | 255      |
| 8  | FX        | LTP  | FX1 Prism (14)   | B2C     | 8 bit | 000    | 000     | 000       | 000      |

**Notes:**
- Dimmer (ch 4) is the only HTP channel.
- ChamSys's `FX1 Prism` attribute slot for ch 8 is an arbitrary mapping
  choice for the desk's encoder layout, not authoritative info that this
  is a literal prism. The product is a LED party bar with no moving
  optics; the channel is best treated as a generic FX/macro slider.
- No strobe channel. No pan/tilt.

## Channel 2 — Col Wheel (`VIEW RANGES`)

19 bands. The first eight are `Fixed` (single colour); the rest are
`Split` (two-colour or rainbow blends).

| Range   | Type  | Name              | Preview |
|---------|-------|-------------------|---------|
| 000–039 | Fixed | All Col           | (none — manual RGB band) |
| 040–049 | Fixed | Red               | red     |
| 050–059 | Fixed | Green             | green   |
| 060–069 | Fixed | Blue              | blue    |
| 070–079 | Fixed | Yellow            | yellow  |
| 080–089 | Fixed | Cyan              | cyan    |
| 090–099 | Fixed | Purple            | purple  |
| 100–109 | Fixed | Rainbow Ball      | rainbow |
| 110–119 | Split | Red and Green     | green   |
| 120–129 | Split | Red and Blue      | blue    |
| 130–139 | Split | Red and Rainbow   | rainbow |
| 140–149 | Split | Green and Blue    | blue    |
| 150–159 | Split | Green and Rainbow | green   |
| 160–169 | Split | Blue and Rainbow  | blue    |
| 170–179 | Split | R, G, Rainbow     | rainbow |
| 180–189 | Split | R, B, Rainbow     | rainbow |
| 190–199 | Split | Green, Blue       | blue    |
| 200–200 | Split | R, G, B           | (none)  |
| 201–255 | Split | R, G, B, Rainbow  | rainbow |

The `All Col` band (000–039) is the "off / manual control" band — when the
colour wheel is in this range, ch 5/6/7 manual RGB is what's visible.

## Palettes (`VIEW PALETTES`)

ChamSys auto-palettes drive the manual RGB channels (5/6/7):

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

RGB-only fixture (no white / amber / UV channels), so amber, pink and UV
are simulated by RGB triples.

## Channels with no discrete ranges

- **Ch 1 Int Mode** — internal-program mode select; no `VIEW RANGES`
  entries beyond the implicit 0–255. Plain slider in the fixture class.
- **Ch 3 Col Speed** — speed of the colour-wheel transitions when ch 2
  is in a `Split` band; plain slider.
- **Ch 4 Dimmer** — HTP, 0–255 linear.
- **Ch 5/6/7 Red/Green/Blue** — manual RGB.
- **Ch 8 FX** — generic prism/FX macro; plain slider.

## Mapping to fixture traits

When `Gear4MusicSolParty12BFixture` is implemented (Tier 8):

- `WithDimmer` → ch 4 (HTP).
- `WithColour` → ch 5/6/7 (single global RGB; no per-cell channels).
- No `WithStrobe`, no `WithPosition`.
- Plain `Slider` → ch 1 (Int Mode), ch 3 (Col Speed), ch 8 (FX).
- `DmxFixtureSetting<ColourWheel>` → ch 2 with the 19 band-start levels.
