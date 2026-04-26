# TCH 2026 Fixture Patch — Plan & Handover

> **Document status: PLANNING (2026-04-26).** Analysis complete; no code written yet.
> Source patch list: [Manuals/TCH_2026_.pdf](../../Manuals/TCH_2026_.pdf) (exported from ChamSys MagicVis 2026-04-19).

## How to use this document

This plan is split across multiple follow-up sessions. Pick one tier, do the work, update the
checkboxes and the **Status** line, then stop. Don't combine tiers in a single session — the
fixture classes are independent and small, and keeping the blast radius small makes review and
ChamSys cross-checks tractable.

- **Locked decisions**: see [Decisions](#decisions). Don't re-open without asking.
- **Open questions**: see [Open Questions](#open-questions). Most are gated on the user
  validating settings in ChamSys; do not guess channel layouts past what the manual states.
- **When picking up cold**: read [Status](#status), then the tier section you're tackling for
  entry criteria, manual references, and verification steps.

Related docs:
- [docs/fixtures-engineering.md](../fixtures-engineering.md) — fixture/trait architecture and
  the "Adding a New DMX Fixture" recipe. **Every fixture class follows this recipe; the tiers
  below only call out fixture-specific deviations.**
- [docs/patch-system-engineering.md](../patch-system-engineering.md) — DB tables and how
  `FixtureTypeRegistry` keys feed the patch UI.
- Reference for multi-mode pattern:
  [src/main/kotlin/uk/me/cormack/lighting7/fixture/dmx/Fusion100SpotMkIIFixture.kt](../../src/main/kotlin/uk/me/cormack/lighting7/fixture/dmx/Fusion100SpotMkIIFixture.kt).

---

## Status

**Next action**: Tiers 0, 1, 2, 3, 4 and 5 are done (2026-04-26). Most [Open Questions](#open-questions)
are answered (2026-04-26 ChamSys session — Q1, Q3, Q4, Q6, Q7). Q2 (Robe ColorSpot 575
mode) and the China 2-Cell Blinder personality are still pending a second ChamSys pass;
Q5 (personality export method) is resolved — we transcribe `EDIT HEAD` screenshots into
Markdown under `Manuals/personalities/` because the on-disk `.hed` and `heads.all` are
obfuscated. Next session: pick [Tier 6](#tier-6--robe-colorspot-575-at) (Robe 575 — needs
OQ2 answered first) or [Tier 7](#tier-7--source-4-revolution) (S4 Revolution Base Frame
— still needs the personality capture before implementation).

**Universes in use**: 1, 2, 4, 5 (universe 3 unused). Universe configs and individual
`fixture_patches` rows still need creating once the classes exist — tracked separately in
[Tier 9](#tier-9--db-patch-rows).

---

## Decisions

- **One tier per follow-up session.** Each tier is bounded enough to fit comfortably in one
  session including tests and a status update.
- **Manual file is the source of truth.** Where a manual exists, channel maps, mode names, and
  enum values come from it. Don't paraphrase the ChamSys personality unless the manual is
  missing.
- **Every multi-mode fixture uses `sealed class` + `MultiModeFixtureFamily<Mode>`** like
  `Fusion100SpotMkIIFixture`, even when we only patch one mode. Implement just the patched mode
  initially; leave mode enum entries for the others as `// TODO`.
- **Conventional non-DMX fixtures share one class.** A single `GenericDimmerFixture` (1ch,
  `WithDimmer`) covers all faces, fresnels, profiles, PARs, RING R/G/B, house lights, non-dim
  circuits, and the 1-ch hazer. Identity is carried by the patch row's `key`/`displayName`,
  not the class.
- **The 2-ch [HazerFixture.kt](../../src/main/kotlin/uk/me/cormack/lighting7/fixture/dmx/HazerFixture.kt)
  is unrelated** to the single-channel `HAZER` at `01-512` in this patch. Don't try to merge
  them — the existing class stays as-is for fixtures that have separate haze and fan channels.
- **Enum order matches DMX levels.** Comment any non-obvious split points (e.g. "indexed vs
  rotating" gobos). Use `DmxFixtureColourSettingValue` (with `colourPreview` hex) for
  colour-wheel enums so the UI can show swatches.

---

## Open Questions

> **All gated on the user verifying in ChamSys before the relevant tier starts.**
> Update this section in-place with the answers; don't open follow-ups for these.

1. **Martin "Mode 4" — which MAC 250 variant?** The patched span is 13 channels
   (`01-173` → `01-186`). Manual: [Manuals/UM_MAC250_EN_D.PDF](../../Manuals/UM_MAC250_EN_D.PDF)
   (MAC 250 Krypton). Confirm:
   - Is this MAC 250 Krypton, Entour, Wash, or Entour-Krypton?
   - Does ChamSys' "Mode 4" map to the manual's 13-channel "Mode 4 (extended)" personality, or
     something else?
   - **Answer (2026-04-26):** This is the **original MAC 250** (not Krypton/Entour/Wash/Beam/+).
     ChamSys personality key `Martin,Mac250,Mode 4`, 13 channels. Identifiable by the channel
     layout: single colour wheel + single gobo wheel, no CMY mixing. The Krypton manual we have
     is for the wrong variant. The MAC 250+ has a byte-identical Mode 4 layout (only the +'s
     Mode 3 diverged in the lamp-ballast refresh), so a MAC 250+ user manual is a valid
     human-readable reference. Full channel map and value ranges are captured in
     [Manuals/personalities/Martin_Mac250_Mode4.md](../../Manuals/personalities/Martin_Mac250_Mode4.md).

2. **Robe ColorSpot 575 AT "Mode 2" — channel count?** Patched at 19-channel intervals
   (`01-202` → `01-221`). Manuals:
   [User_manual_ColorSpot_575_AT.pdf](../../Manuals/User_manual_ColorSpot_575_AT.pdf),
   [ColorSpot_575_AT_DMX_charts.pdf](../../Manuals/ColorSpot_575_AT_DMX_charts.pdf).
   - Confirm Mode 2 = 19ch (vs Mode 1 standard / Mode 3 reduced).
   - **Answer:** _Pending._ 2026-04-26 grep of `headindex.csv` for `^robe,(colou?r.?spot|cspot)`
     filtered to `575` returned no rows — Robe is filed under a different name in this
     ChamSys library. Next pass: `grep -iE '^robe,' headindex.csv` (full Robe list) or open
     the patched fixture in `EDIT HEAD` and capture the personality directly.

3. **ETC Source 4 Revolution "Base Frame" — exact mode/channel count?** Patched at
   `02-001` and `02-101` (100ch spacing — almost certainly just clean addressing, not actual
   channel count). Manual:
   [S4_Revolution_User_Manual_RevE.pdf](../../Manuals/S4_Revolution_User_Manual_RevE.pdf).
   "Base Frame" likely means the chassis without the optional Framing Shutter or Iris module.
   - Which personality is this fixture set to? (e.g. "Standard 14ch", "Standard + Framing
     26ch", etc.)
   - Are the framing shutters physically installed at TCH or not?
   - **Answer (2026-04-26):** ChamSys personality `ETC,Source4Rev,Base Frame` is **31 channels**,
     not the 14ch/26ch range originally guessed. The plan's interpretation was inverted —
     "Base Frame" in ChamSys means **chassis WITH Framing Shutter module installed**, a
     17-channel jump over the 14ch `Base` personality (4 shutters × insert+rotate + extras).
     So framing shutters ARE addressed in the patch, and the fixture class needs to model
     them. Other ETC Source4Rev personalities in the library: `Base` (14ch), `Base Iris` (15ch),
     `15ch` (15ch), `Base Module` (23ch), `Base Frame` (31ch — the patched mode).

4. **Shehds LED19x15W RGBW — 24ch personality.** Patched at 24ch intervals. Manual
   [19颗调焦4合1染色灯(16-24CH)最新.pdf](../../Manuals/19颗调焦4合1染色灯(16-24CH)最新.pdf)
   covers both 16ch and 24ch modes.
   - Confirm 24ch = the per-pixel mode, and 16ch = global RGBW + macros.
   - Does ChamSys split this into 19 individual RGBW pixels in 24ch mode, or treat it as one
     fixture? (Affects whether we need `MultiElementFixture` or just a flat property list.)
   - **Answer (2026-04-26):** Confirmed not per-pixel for 19 LEDs (would need 76+ channels).
     The original answer also presumed "global RGBW + macros" — **incorrect**. Reading the
     bundled manual's 24ch chart shows `Red1/Green1/Blue1/White1` (CH9–12), `Red2…` (CH13–16),
     `Red3…` (CH17–20): **three RGBW zones**, not one global RGBW. ChamSys treats it as one
     personality entry, but the underlying fixture has three independently-addressable RGBW
     zones. Modelled as `MultiElementFixture<Zone>` with 3 elements (matching
     `SlenderBeamBarQuadFixture`). 16ch mode collapses to a single global RGBW.

5. **ChamSys personality export.** For the three unmanualed fixtures (Kam Liteobar 252,
   Gear4Music SOL Party 12B, China 2-Cell LED Blinder), we need DMX charts. Easiest options
   in order:
   - Export `.fxt` personality files from MagicQ/MagicVis (`Setup → Patch → Export` or via
     the personality editor). Drop them into [Manuals/](../../Manuals/) (or a new
     `Manuals/personalities/` subfolder).
   - Open the personality in MagicQ's "Edit Head" view and screenshot each channel's settings.
   - Last resort: paste the channel map as text in the relevant Tier section below.
   - **Method chosen:** Markdown transcription of MagicQ's `EDIT HEAD` → `VIEW CHANS` and
     `VIEW RANGES` screens, dropped into `Manuals/personalities/`. Tried the file-export
     path first: MagicQ's per-head `.hed` files and the consolidated `heads.all` are both
     obfuscated (high-bit-set bytes, no readable strings), so direct file capture is a dead
     end. The UI is the only readable source.
   - **Files added:**
     - [Manuals/personalities/Martin_Mac250_Mode4.md](../../Manuals/personalities/Martin_Mac250_Mode4.md)
     - _More to follow as additional fixtures are captured._

6. **Kam "Liteobar 252" — model spelling.** ChamSys lists `Liteobar252`. The actual product is
   probably the **Kam LightBar 252** or **LiteBar 252**. Confirm exact product name + DMX mode
   (the channel count is 11ch).
   - **Answer (2026-04-26):** ChamSys's marketing-name field for this entry is empty, so we
     have no clean retail-product name to confirm. Use what ChamSys gives us:
     `manufacturer = "Kam"`, `model = "Liteobar 252"`, mode `11ch`. Personality key
     `Kam_Liteobar252_11ch`. Not worth chasing the marketing name further — the
     `@FixtureType` annotation will use ChamSys's spelling for consistency with the patch.

7. **Gear4Music "SOLParty12B" — confirm model.** Likely the Gear4Music SOL Party Bar 12 B (an
   8-channel LED party bar). Confirm exact product name.
   - **Answer (2026-04-26):** Confirmed. ChamSys personality `Gear4Music,SOLParty12B,8ch`,
     marketing-name field gives long form `SOL Party 12B`. 8 channels.

8. **China 2-Cell LED Blinder — personality details.** Patched at 8ch intervals.
   - **Answer:** _Pending._ 2026-04-26 grep of `headindex.csv` for blinders matching the 8ch
     count returned no obvious match (the only blinder hit was an Elation 2-channel fixture).
     Next pass: open the patched fixture in `EDIT HEAD` and capture the personality.

---

## Patch summary

### DMX-controlled fixtures

| # | Manufacturer / Model | Mode | Qty | Manual | Tier |
|---|----------------------|------|-----|--------|------|
| 1 | ADJ Fog Fury Jett | 7ch | 1 | [download_215302.pdf](../../Manuals/download_215302.pdf) | [Tier 1](#tier-1--simple-manual-backed-batch) |
| 2 | Equinox Twin Shot MKII | 3ch | 2 | [EQLED406_Manual.pdf](../../Manuals/EQLED406_Manual.pdf) | [Tier 1](#tier-1--simple-manual-backed-batch) |
| 3 | ImgStageLine Wash-42LED | 13ch | 2 | [WASH-42LED@BDA.pdf](../../Manuals/WASH-42LED@BDA.pdf) | [Tier 1](#tier-1--simple-manual-backed-batch) |
| 4 | Gear4Music Orbit-70 | 13ch | 6 | [download_399717.pdf](../../Manuals/download_399717.pdf) | [Tier 2](#tier-2--orbit-70-moving-head) |
| 5 | Varytec Easymove XL 60 Spot | 11ch | 2 | [246811_manual.pdf](../../Manuals/246811_manual.pdf) | [Tier 3](#tier-3--easymove-xl-60-moving-head) |
| 6 | Shehds LED19x15W RGBW | 24ch | 4 | [19颗调焦4合1染色灯(16-24CH)最新.pdf](../../Manuals/19颗调焦4合1染色灯(16-24CH)最新.pdf) | [Tier 4](#tier-4--shehds-led19x15w-rgbw) |
| 7 | Martin MAC 250 | "Mode 4" (13ch) | 2 | [UM_MAC250_EN_D.PDF](../../Manuals/UM_MAC250_EN_D.PDF) | [Tier 5](#tier-5--martin-mac-250) |
| 8 | Robe ColorSpot 575 AT | Mode 2 (19ch) | 4 | [User_manual_ColorSpot_575_AT.pdf](../../Manuals/User_manual_ColorSpot_575_AT.pdf) + [ColorSpot_575_AT_DMX_charts.pdf](../../Manuals/ColorSpot_575_AT_DMX_charts.pdf) | [Tier 6](#tier-6--robe-colorspot-575-at) |
| 9 | ETC Source 4 Revolution | Base Frame | 2 | [S4_Revolution_User_Manual_RevE.pdf](../../Manuals/S4_Revolution_User_Manual_RevE.pdf) | [Tier 7](#tier-7--source-4-revolution) |
| 10 | Kam Liteobar 252 | 11ch | 2 | none | [Tier 8](#tier-8--reverse-engineered-fixtures) |
| 11 | Gear4Music SOL Party 12B | 8ch | 2 | none | [Tier 8](#tier-8--reverse-engineered-fixtures) |
| 12 | China 2-Cell LED Blinder | 8ch | 2 | none | [Tier 8](#tier-8--reverse-engineered-fixtures) |

### Conventional / dimmer-only patches (Tier 0)

19 patches in the lower table of TCH_2026, all single-channel:

| Patch DMX | Name |
|-----------|------|
| 01-005 | SR FACE 19° |
| 01-006 | C FACE 19° |
| 01-007 | SL FACE 19° |
| 01-008 | Dimmer |
| 01-009 | LX1 FRES |
| 01-010 | LX2 FRES |
| 01-012 | ADV CANS |
| 01-019 | FOH L PROF 19° |
| 01-020 | LX1 C FRES |
| 01-021 | LX2 C FRES |
| 01-022 | NON DIM (adv r) |
| 01-023 | NON DIM (LX3) |
| 01-024 | NON DIM |
| 01-199 | RING RED |
| 01-200 | RING GREEN |
| 01-201 | RING BLUE |
| 01-512 | HAZER |
| 04-001 | house 1 |
| 04-002 | house 2 |
| 04-003 | house 3 |

---

## Tier 0 — Generic 1-channel dimmer fixture

**Goal**: One fixture class covering all 19 conventional patches.

**Status: DONE (2026-04-26).** Class added, registered, tested, docs updated, full test
suite green.

- [x] Create `src/main/kotlin/uk/me/cormack/lighting7/fixture/dmx/GenericDimmerFixture.kt`
  - 1 channel, `WithDimmer` only.
  - `@FixtureType("generic-dimmer", manufacturer = "Generic", model = "Single-channel dimmer")`.
- [x] Verify `FixtureTypeRegistry` picks it up. **Correction to plan**: the registry is
  *not* reflection-driven; `fixtureClasses` in `FixtureTypeRegistry.kt` is an explicit list,
  so `GenericDimmerFixture::class` had to be added to it. Confirmed via the existing
  `instantiateByTypeKey works for all registered types` test.
- [x] Add a unit test (channel write through transaction round-trips):
  `src/test/kotlin/.../fixture/dmx/GenericDimmerFixtureTest.kt`.
- [x] Update `docs/fixtures-engineering.md` — added to the "Existing Fixture
  Implementations" table at the bottom.
- [x] Run `./gradlew test` — `BUILD SUCCESSFUL`.

---

## Tier 1 — Simple manual-backed batch

**Goal**: Three small manual-backed fixtures in one session.

**Status: DONE (2026-04-26).** All three classes added, registered, tested, docs updated,
fixture-targeted test suite green. (One pre-existing flake in `PacketRateCounterTest`
re-runs green in isolation; unrelated to this tier.)

**Entry criteria**: Tier 0 done. No open questions block this tier.

- [x] **ADJ Fog Fury Jett** (7ch) — [manual](../../Manuals/download_215302.pdf)
  - Implemented as `sealed class AdjFogFuryJettFixture` with `Mode7Ch` only; the four
    other personalities (1/2/3/5) are left as `// TODO` enum entries per the locked
    decision. Manual reread changed the trait shape: ch1 is a fog trigger (not a dimmer),
    ch2–5 are RGBA, ch6 is strobe, ch7 is the master dimmer. Final traits:
    `WithDimmer`, `WithColour`, `WithAmber`, `WithStrobe` + a plain `fog: Slider`
    (`PropertyCategory.OTHER`). Strobe band is 32–95 only — pulse (96–159) and
    random-strobe (160–255) are reachable as raw channel writes but not exposed via
    `Strobe`.
- [x] **Equinox Twin Shot MKII** (3ch confetti launcher, EQLED406) —
  [manual](../../Manuals/EQLED406_Manual.pdf)
  - Implemented as a single-mode `EquinoxTwinShotMkIIFixture`. Three plain `Slider`
    properties (`output1`, `output2`, `master`) all categorised `OTHER`. **No traits**
    — by design, so FX engine cannot accidentally fire confetti pods. Doc comment
    captures the safety reasoning.
- [x] **ImgStageLine Wash-42LED** (13ch) — [manual](../../Manuals/WASH-42LED@BDA.pdf)
  - Implemented as `sealed class ImgStageLineWash42LedFixture` with `Mode13Ch` only;
    `MODE_8CH` is left as a `// TODO`. Manual reread upgraded the plan's guess: this
    is actually a 7×10W RGBW **moving head** wash (540°/180°), not just RGBW + strobe.
    Final traits: `WithDimmer`, `WithColour`, `WithWhite`, `WithStrobe`, `WithPosition`
    (with separate fine-pan/fine-tilt sliders). Ch6 is shared dimmer+strobe — the
    `dimmer` clamps to 0–134 (the dim band), `strobe.fullOn()` writes 240 (the
    "max-brightness" band that overrides the dimmer), and `strobe.strobe(intensity)`
    maps into 135–239.
- [x] Tests for each (one or two `assertEquals` per fixture).
- [x] Update `docs/fixtures-engineering.md` table.
- [x] `./gradlew test`.

---

## Tier 2 — Orbit-70 moving head

**Goal**: The Gear4Music Orbit-70 (13ch). Highest-quantity DMX fixture (6 patched), so worth
its own session.

**Status: DONE (2026-04-26).** Class added, registered, tested, docs updated, full test
suite green.

**Entry criteria**: Tier 0 done.

- [x] Read the manual end-to-end: [download_399717.pdf](../../Manuals/download_399717.pdf).
  Two personalities (9CH / 13CH); 13CH is the patched mode. 7×10W RGBW LEDs.
- [x] Implemented as `sealed class Gear4MusicOrbit70Fixture` with `Mode13Ch` only;
  `MODE_9CH` is left as a `// TODO` per the locked decision.
- [x] Final traits: `WithDimmer`, `WithColour`, `WithWhite`, `WithStrobe`, `WithPosition`
  (with separate fine-pan/fine-tilt sliders). Ch7 is the strobe/shutter channel —
  `strobe.fullOn()` writes 248 (LED open switch) and `strobe.strobe(intensity)` maps
  into 016–131 (linear strobe band). Pulse, quick-start and random-flash bands are
  reachable as raw channel writes but not exposed via `Strobe`.
- [x] `Program` enum on ch13 covers the documented bands (4 program variants + reset).
  Sound-active mode (240–255 in the 9CH personality) is omitted — not documented for 13CH.
- [x] Ch12 (static colour select) is a plain `Slider` since the manual gives no value bands.
- [x] Tests for the channel layout and the strobe band semantics.
- [x] `./gradlew test` — `BUILD SUCCESSFUL`.

**Note**: ChamSys lists the model as `Orbit70WLEDHead`. Don't take that name literally —
follow the manual's naming.

---

## Tier 3 — Easymove XL 60 moving head

**Goal**: Varytec Easymove XL 60 Spot (11ch).

**Status: DONE (2026-04-26).** Class added, registered, tested, docs updated, full test
suite green.

**Entry criteria**: Tier 0 done.

- [x] Read [246811_manual.pdf](../../Manuals/246811_manual.pdf) — section 5.1 has the DMX
  channel list. Confirmed single-mode 11ch fixture.
- [x] Implemented as `sealed class VarytecEasymoveXl60SpotFixture` with only `Mode11Ch`,
  matching the rest of the codebase even though the manual lists no other personalities.
- [x] Final traits: `WithDimmer`, `WithPosition` (with separate fine-pan/fine-tilt sliders),
  `WithStrobe`. Confirmed white-LED engine — no `WithColour` (colour wheel only). The
  colour wheel is exposed as a `DmxFixtureSetting<Colour>` with seven indexed positions
  + open + forward/reverse rainbow rotation. The manual gives no specific colour for each
  index, so `colourPreview` is left null on every entry.
- [x] Channel 5 (gobo spin) is a plain `Slider` since the manual gives only continuous
  forward/reverse bands. Channel 11 (reset) is modelled as a two-state
  `DmxFixtureSetting<Reset>` (NO_FUNCTION/RESET) so an FX can't accidentally trigger a
  head reset by writing a random value to the channel.
- [x] Strobe channel: 0 = no strobe (LED constant on), 1–255 = slow → fast. `fullOn()`
  writes 0; `strobe(intensity)` linearly maps 0–255 onto the 1–255 band.
- [x] Tests for the channel layout and strobe band semantics.
- [x] `./gradlew test` — `BUILD SUCCESSFUL`.

---

## Tier 4 — Shehds LED19x15W RGBW

**Status: DONE (2026-04-26).** Class added (both 16ch and 24ch modes), registered, tested,
docs updated, full test suite green.

**Entry criteria**: [OQ4](#open-questions) answered (2026-04-26).

**Architecture confirmed**: 24ch is **three RGBW zones**, not global RGBW (correcting the
original OQ4 answer). Modelled as `MultiElementFixture<Zone>` with 3 elements.

- [x] Manual: [19颗调焦4合1染色灯(16-24CH)最新.pdf](../../Manuals/19颗调焦4合1染色灯(16-24CH)最新.pdf).
  Channel charts on pages 13–14 are bilingual enough that no translation was needed —
  channel names like "Red1 Dimmer" / "White3 Dimmer" map straight across.
- [x] `sealed class ShehdsLed19RgbwFixture` with `Mode.MODE_16CH` and `Mode.MODE_24CH`.
- [x] Mode 24Ch (the patched mode): `WithDimmer`, `WithPosition`, `WithStrobe`, plus
  `MultiElementFixture<Zone>` with 3 zones. Each `Zone` is a `FixtureElement` with
  `WithColour` + `WithWhite`. Master pan/tilt + speed + zoom + dimmer + strobe live on
  the parent (CH1–8); zones drive CH9–20; programs/program speed/control mode/reset
  live on CH21–24.
- [x] Mode 16Ch: collapses to a single global RGBW. `WithDimmer`, `WithColour`, `WithWhite`,
  `WithStrobe`, `WithPosition`. Notable wrinkle: 16ch puts zoom **after** the RGBW block
  (CH12) whereas 24ch puts zoom **before** the dimmer (CH6) — the layouts are not just
  truncations of each other.
- [x] Strobe channel: manual lists only "0–255 Strobe" with no value bands. Modelled
  Varytec-style: 0 = no strobe (LED constant on), 1–255 = slow → fast.
- [x] Reset channel: modelled as a discrete two-state `DmxFixtureSetting<Reset>`
  (NO_FUNCTION / RESET) so accidental FX writes can't trigger a head reset.
- [x] Programs: enum with the seven documented bands (Preset/Jump/Gradient/Pulse/Auto1/2/3).
  Same DMX values across both modes despite slightly different band labels (16ch calls
  the last three "Effect modes", 24ch calls them "Auto modes").
- [x] Tests for both modes (channel layout, multi-element zone keys, strobe band semantics).
- [x] `./gradlew test` — `BUILD SUCCESSFUL`.

---

## Tier 5 — Martin MAC 250

**Status: DONE (2026-04-26).** Class added, registered, tested, docs updated, full test
suite green.

**Entry criteria**: [OQ1](#open-questions) answered (2026-04-26).

**Variant confirmed**: original MAC 250, not Krypton/Entour/Wash/Beam/+. Single colour
wheel + single gobo wheel, no CMY mixing. Personality key `Martin,Mac250,Mode 4`.

**Authoritative reference**:
[Manuals/personalities/Martin_Mac250_Mode4.md](../../Manuals/personalities/Martin_Mac250_Mode4.md)
— full channel layout, defaults, and DMX value ranges captured from MagicQ's `EDIT HEAD`.
The bundled [UM_MAC250_EN_D.PDF](../../Manuals/UM_MAC250_EN_D.PDF) is the **wrong manual**
(it's for the Krypton variant); a MAC 250+ user manual would be a valid human-readable
reference if needed (Mode 4 layout is shared with the +).

- [x] `sealed class MartinMac250Fixture` with `MODE_4` (13ch) implemented; Mode 1, 2, 3
  left as `// TODO` enum entries per the locked decision.
- [x] Traits: `WithDimmer` (ch 2), `WithStrobe` (ch 1 — `StrobeChannel` clamps the slider
  to `STROBE_MAX = 72`, so neither `Strobe` API nor raw `value` writes can wander into
  the Reset/Lamp bands), `WithPosition` (16-bit pan/tilt at ch 8/9 + 10/11).
- [x] `DmxFixtureSetting<DmxFixtureColourSettingValue>` for ch 3 with hex preview swatches
  on the indexed colours.
- [x] `DmxFixtureSetting` enums for ch 4 (`Gobo` with shake variants + scroll) and ch 7
  (`Prism` with macros).
  - **Plan deviation**: ch 12 (P/T Speed) and ch 13 (Effect speed) modelled as plain
    `Slider`s, not enums — the personality capture explicitly notes "no `VIEW RANGES`
    entries beyond the implicit 0–255" for both. The earlier checklist's expectation of
    enums was a guess.
- [x] Plain `Slider` for ch 5 (Gobo rotation) and ch 6 (Focus).
- [x] Explicit `lampOn()`, `lampOff()`, `reset()` on `Mode4Ch` that bypass the strobe
  slider's clamp — they write directly via the transaction. Not FX-targetable.
- [x] Tests for the channel layout, the strobe-band semantics + clamp, and the lamp/reset
  methods.
- [x] `./gradlew test` — `BUILD SUCCESSFUL`.

---

## Tier 6 — Robe ColorSpot 575 AT

**Entry criteria**: [Open question 2](#open-questions) answered.

- [ ] Manuals:
  [User_manual_ColorSpot_575_AT.pdf](../../Manuals/User_manual_ColorSpot_575_AT.pdf) +
  [ColorSpot_575_AT_DMX_charts.pdf](../../Manuals/ColorSpot_575_AT_DMX_charts.pdf).
- [ ] `sealed class RobeColorSpot575Fixture` with both Mode 1 and Mode 2; implement Mode 2.
- [ ] Discharge lamp, so include lamp on/off / shutter / dimmer logic carefully — note the
  difference between mechanical shutter and electronic dimmer in the manual.
- [ ] Traits: `WithDimmer`, `WithPosition`, `WithStrobe`. CMY mixing +
  colour wheel; gobo wheel(s); prism; focus; iris; frost.
- [ ] Tests.
- [ ] `./gradlew test`.

---

## Tier 7 — Source 4 Revolution

**Entry criteria**: [OQ3](#open-questions) answered (2026-04-26).

**Personality confirmed**: ChamSys `ETC,Source4Rev,Base Frame` = **31 channels**, i.e.
chassis WITH the Framing Shutter module installed. The plan's earlier guess that "Base
Frame" meant chassis-without-modules was inverted. Other library personalities for cross-
reference: `Base` (14ch), `Base Iris` (15ch), `15ch` (15ch), `Base Module` (23ch).

- [ ] Manual: [S4_Revolution_User_Manual_RevE.pdf](../../Manuals/S4_Revolution_User_Manual_RevE.pdf).
- [ ] Capture the personality from MagicQ's `EDIT HEAD` (`VIEW CHANS` + `VIEW RANGES`) into
  `Manuals/personalities/ETC_Source4Rev_BaseFrame.md` before implementing — same approach as
  the Mac 250 capture.
- [ ] `sealed class Source4RevolutionFixture` with the personalities ChamSys lists (Base,
  Base Iris, 15ch, Base Module, Base Frame). Implement Base Frame; leave others as
  `// TODO` enum entries.
- [ ] This is a discharge fixture (HMI 700) — same lamp/shutter/dimmer caution as Robe and
  the MAC 250: lamp on/off and reset bands must not be FX-targetable.
- [ ] Tests.
- [ ] `./gradlew test`.

---

## Tier 8 — Reverse-engineered fixtures

**Entry criteria**: [Open question 5](#open-questions) answered (i.e., DMX charts captured
from ChamSys).

Three small fixtures, batched into one session because they're all similar shape:

- [ ] **Kam Liteobar 252** (11ch) — see OQ6 for product name confirmation.
- [ ] **Gear4Music SOL Party 12B** (8ch) — see OQ7.
- [ ] **China 2-Cell LED Blinder** (8ch) — likely 2-cell architecture: per-cell dimmer + RGB
  or warm-white. Use `MultiElementFixture<Cell>` if confirmed.
- [ ] Tests for each.
- [ ] `./gradlew test`.

---

## Tier 9 — DB patch rows

**Goal**: Translate the TCH_2026 patch list into actual `fixture_patches` and
`universe_configs` rows so the show can load.

**Entry criteria**: All preceding tiers done. All `FixtureTypeRegistry` keys exist.

Two ways to do this:
1. **Via REST API**: script the inserts using `POST /api/rest/project/{projectId}/patches`
   with the full list. Reproducible, version-controllable.
2. **Via the React patch UI**: hand-entered. Slower but works for one-offs.

- [ ] Confirm with the user which approach (recommend option 1 for ~70 fixtures).
- [ ] If option 1: write a one-shot Kotlin or shell script under `scripts/` that POSTs the
  patches. Keep it idempotent (skip if `key` already exists).
- [ ] Verify in the UI that all fixtures load and respond to channel writes.
- [ ] Smoke-test a sample from each fixture type at the venue when possible.
