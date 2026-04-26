# TCH 2026 Fixture Patch — Plan & Handover

> **Document status: PLANNING (2026-04-26).** Analysis complete; no code written yet.
> Source patch list: [Manuals/TCH_2026_.pdf](../../../Manuals/TCH_2026_.pdf) (exported from ChamSys MagicVis 2026-04-19).

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
- [docs/fixtures-engineering.md](../../fixtures-engineering.md) — fixture/trait architecture and
  the "Adding a New DMX Fixture" recipe. **Every fixture class follows this recipe; the tiers
  below only call out fixture-specific deviations.**
- [docs/patch-system-engineering.md](../../patch-system-engineering.md) — DB tables and how
  `FixtureTypeRegistry` keys feed the patch UI.
- Reference for multi-mode pattern:
  [src/main/kotlin/uk/me/cormack/lighting7/fixture/dmx/Fusion100SpotMkIIFixture.kt](../../../src/main/kotlin/uk/me/cormack/lighting7/fixture/dmx/Fusion100SpotMkIIFixture.kt).

---

## Status

**All tiers done (2026-04-26).** Fixture classes (Tiers 0–8), shared
`BandedStrobeChannel` refactor (Tier 10), and the DB patch rows
(Tier 9) are all complete. Project 9 ("The Commemoration Hall") has
50 patches across 4 universes seeded by
[`scripts/tch-2026-patch.sh`](../../../scripts/tch-2026-patch.sh).

**Universes in use**: 1, 2, 4, 5 (universe 3 unused). Universe configs were
auto-created by the patch endpoint with `controllerType=ARTNET` and
`address=null` (broadcast); set per-universe addresses in the UI if a
specific ArtNet node IP is needed.

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
- **The 2-ch [HazerFixture.kt](../../../src/main/kotlin/uk/me/cormack/lighting7/fixture/dmx/HazerFixture.kt)
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
   (`01-173` → `01-186`). Manual: [Manuals/UM_MAC250_EN_D.PDF](../../../Manuals/UM_MAC250_EN_D.PDF)
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
     [Manuals/personalities/Martin_Mac250_Mode4.md](../../../Manuals/personalities/Martin_Mac250_Mode4.md).

2. **Robe ColorSpot 575 AT "Mode 2" — channel count?** Patched at 19-channel intervals
   (`01-202` → `01-221`). Manuals:
   [User_manual_ColorSpot_575_AT.pdf](../../../Manuals/User_manual_ColorSpot_575_AT.pdf),
   [ColorSpot_575_AT_DMX_charts.pdf](../../../Manuals/ColorSpot_575_AT_DMX_charts.pdf).
   - Confirm Mode 2 = 19ch (vs Mode 1 standard / Mode 3 reduced).
   - **Answer (2026-04-26):** Confirmed. ChamSys personality `Robe,Spot575,Mode 2`,
     19 channels. The DMX chart's Mode 2 column matches the MagicQ `EDIT HEAD`
     capture exactly for ch 5–9 (the screenshotted ranges). Channel layout:
     16-bit pan/tilt + P/T speed + Control + Col 1 + Col 2 + static gobo +
     rotating gobo + gobo rot + prism + prism rot + frost + iris + zoom + focus
     + shutter + dimmer. Full layout in
     [Manuals/personalities/Robe_ColorSpot575AT_Mode2.md](../../../Manuals/personalities/Robe_ColorSpot575AT_Mode2.md).

3. **ETC Source 4 Revolution "Base Frame" — exact mode/channel count?** Patched at
   `02-001` and `02-101` (100ch spacing — almost certainly just clean addressing, not actual
   channel count). Manual:
   [S4_Revolution_User_Manual_RevE.pdf](../../../Manuals/S4_Revolution_User_Manual_RevE.pdf).
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
   [19颗调焦4合1染色灯(16-24CH)最新.pdf](../../../Manuals/19颗调焦4合1染色灯(16-24CH)最新.pdf)
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
     the personality editor). Drop them into [Manuals/](../../../Manuals/) (or a new
     `Manuals/personalities/` subfolder).
   - Open the personality in MagicQ's "Edit Head" view and screenshot each channel's settings.
   - Last resort: paste the channel map as text in the relevant Tier section below.
   - **Method chosen:** Markdown transcription of MagicQ's `EDIT HEAD` → `VIEW CHANS` and
     `VIEW RANGES` screens, dropped into `Manuals/personalities/`. Tried the file-export
     path first: MagicQ's per-head `.hed` files and the consolidated `heads.all` are both
     obfuscated (high-bit-set bytes, no readable strings), so direct file capture is a dead
     end. The UI is the only readable source.
   - **Files added:**
     - [Manuals/personalities/Martin_Mac250_Mode4.md](../../../Manuals/personalities/Martin_Mac250_Mode4.md)
     - [Manuals/personalities/ETC_Source4Rev_BaseFrame.md](../../../Manuals/personalities/ETC_Source4Rev_BaseFrame.md)
     - [Manuals/personalities/Robe_ColorSpot575AT_Mode2.md](../../../Manuals/personalities/Robe_ColorSpot575AT_Mode2.md)
     - [Manuals/personalities/Kam_Liteobar252_11ch.md](../../../Manuals/personalities/Kam_Liteobar252_11ch.md)
     - [Manuals/personalities/Gear4Music_SOLParty12B_8ch.md](../../../Manuals/personalities/Gear4Music_SOLParty12B_8ch.md)
     - [Manuals/personalities/China_2CellLEDBlind_8ch.md](../../../Manuals/personalities/China_2CellLEDBlind_8ch.md)

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
   - **Answer (2026-04-26):** Captured from MagicQ `EDIT HEAD`. ChamSys personality key
     `China,2CellLEDBlind,8ch` (manufacturer field is the literal string `China`). Variable-
     white blinder, not RGB: each cell has independent warm-white + cold-white channels.
     8 channels split: master Dimmer (HTP) + Strobe + Prog + Prog Speed + 2 cells × (WW, CW).
     Cell 2's WW/CW are flagged `Indep yes` in ChamSys so the desk's fan-spread doesn't
     accidentally affect it. Full layout in
     [Manuals/personalities/China_2CellLEDBlind_8ch.md](../../../Manuals/personalities/China_2CellLEDBlind_8ch.md).

---

## Patch summary

### DMX-controlled fixtures

| # | Manufacturer / Model | Mode | Qty | Manual | Tier |
|---|----------------------|------|-----|--------|------|
| 1 | ADJ Fog Fury Jett | 7ch | 1 | [download_215302.pdf](../../../Manuals/download_215302.pdf) | [Tier 1](#tier-1--simple-manual-backed-batch) |
| 2 | Equinox Twin Shot MKII | 3ch | 2 | [EQLED406_Manual.pdf](../../../Manuals/EQLED406_Manual.pdf) | [Tier 1](#tier-1--simple-manual-backed-batch) |
| 3 | ImgStageLine Wash-42LED | 13ch | 2 | [WASH-42LED@BDA.pdf](../../../Manuals/WASH-42LED@BDA.pdf) | [Tier 1](#tier-1--simple-manual-backed-batch) |
| 4 | Gear4Music Orbit-70 | 13ch | 6 | [download_399717.pdf](../../../Manuals/download_399717.pdf) | [Tier 2](#tier-2--orbit-70-moving-head) |
| 5 | Varytec Easymove XL 60 Spot | 11ch | 2 | [246811_manual.pdf](../../../Manuals/246811_manual.pdf) | [Tier 3](#tier-3--easymove-xl-60-moving-head) |
| 6 | Shehds LED19x15W RGBW | 24ch | 4 | [19颗调焦4合1染色灯(16-24CH)最新.pdf](../../../Manuals/19颗调焦4合1染色灯(16-24CH)最新.pdf) | [Tier 4](#tier-4--shehds-led19x15w-rgbw) |
| 7 | Martin MAC 250 | "Mode 4" (13ch) | 2 | [UM_MAC250_EN_D.PDF](../../../Manuals/UM_MAC250_EN_D.PDF) | [Tier 5](#tier-5--martin-mac-250) |
| 8 | Robe ColorSpot 575 AT | Mode 2 (19ch) | 4 | [User_manual_ColorSpot_575_AT.pdf](../../../Manuals/User_manual_ColorSpot_575_AT.pdf) + [ColorSpot_575_AT_DMX_charts.pdf](../../../Manuals/ColorSpot_575_AT_DMX_charts.pdf) | [Tier 6](#tier-6--robe-colorspot-575-at) |
| 9 | ETC Source 4 Revolution | Base Frame | 2 | [S4_Revolution_User_Manual_RevE.pdf](../../../Manuals/S4_Revolution_User_Manual_RevE.pdf) | [Tier 7](#tier-7--source-4-revolution) |
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

- [x] **ADJ Fog Fury Jett** (7ch) — [manual](../../../Manuals/download_215302.pdf)
  - Implemented as `sealed class AdjFogFuryJettFixture` with `Mode7Ch` only; the four
    other personalities (1/2/3/5) are left as `// TODO` enum entries per the locked
    decision. Manual reread changed the trait shape: ch1 is a fog trigger (not a dimmer),
    ch2–5 are RGBA, ch6 is strobe, ch7 is the master dimmer. Final traits:
    `WithDimmer`, `WithColour`, `WithAmber`, `WithStrobe` + a plain `fog: Slider`
    (`PropertyCategory.OTHER`). Strobe band is 32–95 only — pulse (96–159) and
    random-strobe (160–255) are reachable as raw channel writes but not exposed via
    `Strobe`.
- [x] **Equinox Twin Shot MKII** (3ch confetti launcher, EQLED406) —
  [manual](../../../Manuals/EQLED406_Manual.pdf)
  - Implemented as a single-mode `EquinoxTwinShotMkIIFixture`. Three plain `Slider`
    properties (`output1`, `output2`, `master`) all categorised `OTHER`. **No traits**
    — by design, so FX engine cannot accidentally fire confetti pods. Doc comment
    captures the safety reasoning.
- [x] **ImgStageLine Wash-42LED** (13ch) — [manual](../../../Manuals/WASH-42LED@BDA.pdf)
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

- [x] Read the manual end-to-end: [download_399717.pdf](../../../Manuals/download_399717.pdf).
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

- [x] Read [246811_manual.pdf](../../../Manuals/246811_manual.pdf) — section 5.1 has the DMX
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

- [x] Manual: [19颗调焦4合1染色灯(16-24CH)最新.pdf](../../../Manuals/19颗调焦4合1染色灯(16-24CH)最新.pdf).
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
[Manuals/personalities/Martin_Mac250_Mode4.md](../../../Manuals/personalities/Martin_Mac250_Mode4.md)
— full channel layout, defaults, and DMX value ranges captured from MagicQ's `EDIT HEAD`.
The bundled [UM_MAC250_EN_D.PDF](../../../Manuals/UM_MAC250_EN_D.PDF) is the **wrong manual**
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

**Status: DONE (2026-04-26).** Class added (Mode 2 19ch only), registered, tested,
docs updated, full test suite green.

**Entry criteria**: [OQ2](#open-questions) answered (2026-04-26).

**Personality confirmed**: ChamSys `Robe,Spot575,Mode 2` = 19 channels. The
fixture has dual colour wheels (Col 1 + Col 2) with deep / corrective filters
on Col 2 — there is **no CMY mixing** on this fixture, despite the plan's
earlier guess.

**Authoritative reference**:
[Manuals/personalities/Robe_ColorSpot575AT_Mode2.md](../../../Manuals/personalities/Robe_ColorSpot575AT_Mode2.md)
— full 19-channel layout. The MagicQ `VIEW RANGES` capture (ch 5–9
screenshotted) was spot-checked against the bundled Robe DMX chart Mode 2
column and they agree exactly; channels 10–19 use the chart directly.

- [x] Manuals:
  [User_manual_ColorSpot_575_AT.pdf](../../../Manuals/User_manual_ColorSpot_575_AT.pdf) +
  [ColorSpot_575_AT_DMX_charts.pdf](../../../Manuals/ColorSpot_575_AT_DMX_charts.pdf).
- [x] `sealed class RobeColorSpot575Fixture` with `MODE_2` (19ch) implemented;
  Modes 1 / 3 / 4 left as `// TODO` enum entries per the locked decision.
- [x] Traits: `WithDimmer` (ch 19, HTP), `WithPosition` (16-bit pan/tilt at
  ch 1/2 + 3/4), `WithStrobe` (ch 18 — `StrobeChannel` clamps the slider to
  `STROBE_BAND_MAX = 95u`, so neither `Strobe` API nor raw `value` writes can
  wander into the pulse-open/pulse-close/random-strobe bands above 95).
- [x] Discharge-lamp safety: lamp on/off + the seven reset bands all live on
  **channel 6 (Control)**, not the shutter channel as on the MAC 250. Channel 6
  is intentionally NOT exposed as `@FixtureProperty`. Explicit methods
  (`lampOn()`, `lampOff()`, `reset()`, `resetPanTilt()`, `resetColour()`,
  `resetGobo()`, `resetDimmer()`, `resetFocusZoomFrost()`, `resetIrisPrism()`)
  bypass the property system and write directly via the transaction.
- [x] `DmxFixtureSetting<DmxFixtureColourSettingValue>` for ch 7 (Col 1) and
  ch 8 (Col 2) with hex preview swatches.
- [x] `DmxFixtureSetting` enums for ch 9 (`StaticGobo` with shake variants +
  scroll), ch 10 (`RotGobo` with index/rotate/shake variants + scroll), and
  ch 12 (`Prism` with 16 macros).
- [x] Plain `Slider` for ch 5 (P/T Speed), ch 11 (Gobo rotation — semantics
  depend on ch 10 mode), ch 13 (Prism rotation), ch 14 (Frost), ch 15 (Iris),
  ch 16 (Zoom), ch 17 (Focus).
- [x] Tests for the channel layout, the strobe-band semantics + clamp, and
  the lamp/reset family methods.
- [x] `./gradlew test` — `BUILD SUCCESSFUL`.

---

## Tier 7 — Source 4 Revolution

**Status: DONE (2026-04-26).** Class added (Base Frame 31ch only), registered, tested,
docs updated.

**Entry criteria**: [OQ3](#open-questions) answered (2026-04-26).

**Personality confirmed**: ChamSys `ETC,Source4Rev,Base Frame` = **31 channels**, i.e.
chassis WITH the Framing Shutter module installed. The plan's earlier guess that "Base
Frame" meant chassis-without-modules was inverted. Other library personalities for cross-
reference: `Base` (14ch), `Base Iris` (15ch), `15ch` (15ch), `Base Module` (23ch).

**Authoritative reference**:
[Manuals/personalities/ETC_Source4Rev_BaseFrame.md](../../../Manuals/personalities/ETC_Source4Rev_BaseFrame.md)
— full 31-channel layout captured from MagicQ's `EDIT HEAD`. Only ch 8 (Zoom),
ch 13 (Gel Scroller) and ch 15 (Iris) had `VIEW RANGES` detail; all other channels
are continuous controls without documented value bands.

- [x] Manual: [S4_Revolution_User_Manual_RevE.pdf](../../../Manuals/S4_Revolution_User_Manual_RevE.pdf).
- [x] Captured the personality into `Manuals/personalities/ETC_Source4Rev_BaseFrame.md`
  (`VIEW CHANS` for all 31 channels; `VIEW RANGES` for ch 8 Zoom, ch 13 Gel Scroller,
  ch 15 Iris). The other channels had no `VIEW RANGES` detail and are modelled as
  plain sliders.
- [x] `sealed class Source4RevolutionFixture` with `BASE_FRAME` (31ch) implemented;
  Base / Base Iris / 15ch / Base Module left as `// TODO` enum entries per the locked
  decision.
- [x] Traits: `WithDimmer` (ch 1, mechanical douser), `WithPosition` (16-bit pan ch 2/3
  + tilt ch 4/5). No `WithStrobe` (no shutter channel in this personality). No
  `WithColour` (colour comes from the discrete gel scroller).
- [x] `DmxFixtureSetting<GelFrame>` for ch 13 with the 14-frame band layout. Generic
  `FRAME_0..FRAME_13` names since the personality capture has no actual gel colour
  labels — venue gel string maps frame index → colour.
- [x] Plain `Slider` for ch 6 (Media Frame), ch 7 (Focus), ch 8 (Zoom), ch 9–11
  (Focus / Col / Beam fade times), ch 15 (Iris), ch 16/17/18/19 (FB beam wheel
  pos/func/rot 16-bit), ch 20/21/22/23 (RB beam wheel pos/func/rot 16-bit), ch
  24–31 (4 framing shutters × pos+rot).
- [x] **Plan deviation**: Reset (ch 12) and Reserved (ch 14) are NOT exposed as
  `@FixtureProperty`. The earlier plan called for "lamp on/off and reset bands must
  not be FX-targetable" with the assumption this was a discharge fixture; the
  captured personality has no lamp-control bands at all (the Source 4 Revolution
  uses an HPL tungsten lamp, not HMI), and Reset's band layout was not captured.
  Both channels default to 0 and stay there unless a script writes raw values via
  the controller transaction.
- [x] Tests for the channel layout and the gel scroller band levels.
- [x] `./gradlew test` — `BUILD SUCCESSFUL`.

---

## Tier 8 — Reverse-engineered fixtures

**Status: DONE (2026-04-26).** All three personalities captured, three classes
added, registered, tested, docs updated, full test suite green.

**Entry criteria**: [Open questions 5, 6, 7, 8](#open-questions) all answered
(2026-04-26).

Three small fixtures, batched into one session — all similar shape (small,
no manuals, all driven from a transcribed ChamSys personality):

- [x] **Kam Liteobar 252** (11ch). Three RGB cells + master macro + strobe.
  No manual; personality
  [Kam_Liteobar252_11ch.md](../../../Manuals/personalities/Kam_Liteobar252_11ch.md).
  Modelled as `MultiElementFixture<Cell>` with 3 RGB cells. No `WithDimmer` —
  the fixture has no continuous master dimmer; brightness comes from the cell
  RGB values themselves and the macro channel must be in `DIMMER_1` /
  `DIMMER_2` (041–120) for cells to be visible. `WithStrobe` on the parent.
  Macro modelled as `DmxFixtureSetting<Macro>` with the seven band-start
  levels (BLACK_OUT/DIMMER_1/DIMMER_2/COL_FLASH/COL_CHANGE/COL_FLOW/DREAM_FLOW).
- [x] **Gear4Music SOL Party 12B** (8ch). Single global RGB party bar.
  No manual; personality
  [Gear4Music_SOLParty12B_8ch.md](../../../Manuals/personalities/Gear4Music_SOLParty12B_8ch.md).
  Single-mode `Gear4MusicSolParty12BFixture` with `WithDimmer` + `WithColour`.
  No strobe channel, no pan/tilt. Built-in colour wheel (ch 2) modelled as a
  19-entry `DmxFixtureColourSettingValue` enum with hex previews; `ALL_COL`
  (000–039) is the "off" band where manual RGB on ch 5/6/7 takes over.
  Plain sliders for Int Mode, Col Speed, FX.
- [x] **China 2-Cell LED Blinder** (8ch). Variable-white blinder (warm + cold,
  no RGB). No manual; personality
  [China_2CellLEDBlind_8ch.md](../../../Manuals/personalities/China_2CellLEDBlind_8ch.md).
  Single-mode `China2CellLedBlinderFixture` with `WithDimmer` (ch 1, HTP) +
  `WithStrobe` (ch 2) + `MultiElementFixture<Cell>` with 2 cells. Each cell
  has a Warm White and a Cold White slider — both `PropertyCategory.WHITE`,
  no `WithWhite` trait (using it on one would imply asymmetry). Strobe band
  is 011–255 (the `Open` band is 0–10). Programs as a 7-entry enum.
- [x] Tests for each.
- [x] `./gradlew test` — `BUILD SUCCESSFUL`.

---

## Tier 9 — DB patch rows

**Status: DONE (2026-04-26).** 50 patches seeded into project 9
("The Commemoration Hall") via REST. All 50 instantiate correctly at
runtime (`/api/rest/fixture/list` returns 50 with no errors); 6 groups
auto-created (ADV1 / ADV2 / LX1 / LX2 / LX3 / Pipe 1FOH).

**Approach taken**: Option 1 (REST API). The seeding script is
[`scripts/tch-2026-patch.sh`](../../../scripts/tch-2026-patch.sh) — a bash
wrapper around `curl` that POSTs to
`/api/rest/project/{PROJECT_ID}/patches`. Idempotent: re-running treats
"channel overlap with own key" / "duplicate key" as skips, so the script
is safe to re-run after the show is partially patched.

**What got patched** (from
[Manuals/TCH_2026_.pdf](../../../Manuals/TCH_2026_.pdf)):

| Universe | Type | Count |
|----------|------|-------|
| 1 | DMX-controlled fixtures + 17 single-channel dimmers | 44 |
| 2 | ETC Source 4 Revolution (Base Frame, 31ch) | 2 |
| 4 | House lights (1ch) | 3 |
| 5 | Equinox Twin Shot MKII (3ch) | 1 |

Position column from the MagicVis export was used as the group name where
present (ADV1 / ADV2 / LX1 / LX2 / LX3 / Pipe 1FOH); fixtures with no
listed Position were left ungrouped. Universe configs were auto-created
with `controllerType=ARTNET` and `address=null` (broadcast).

**Plan deviation**: head 57 (ADJ Fog Fury Jett 7ch) is listed in the
MagicVis export but with **no DMX address** — it appears unpatched in the
visualizer. The fixture class exists (Tier 1), so it can be patched via the
UI later. The script intentionally skips it; an inline comment in the
script explains why.

- [x] User chose Option 1 (REST API script) — see chat history 2026-04-26.
- [x] Script written: [`scripts/tch-2026-patch.sh`](../../../scripts/tch-2026-patch.sh).
      Idempotent: treats overlap-with-own-key as a skip. Run with
      `PROJECT_ID=9 ./scripts/tch-2026-patch.sh`.
- [x] Run against project 9 — 50 patches created, 4 universe configs
      auto-created, 6 groups auto-created. Re-run reports `created=0
      skipped=50 failed=0`.
- [x] Verified at runtime: `/api/rest/fixture/list` returns 50 fixtures
      with no errors; capability mix matches expectations
      (dimmer=46 / position=22 / strobe=24 / colour=16 / multi-element=8).
- [ ] Smoke-test a sample from each fixture type at the venue (deferred to
      first venue setup; not blocking).

---

## Tier 10 — Shared `BandedStrobeChannel` refactor

**Status: DONE (2026-04-26).** New shared class
[`BandedStrobeChannel.kt`](../../../src/main/kotlin/uk/me/cormack/lighting7/fixture/dmx/BandedStrobeChannel.kt)
landed and 14 fixtures collapsed onto it. Three fixtures
(`Fusion100SpotMkII`, `LedLightbar12Pixel`, `SlenderBeamBarQuad`) keep a
thin subclass that overrides `strobe(0)` to short-circuit to `fullOn()`,
preserving the original quirk where intensity 0 means "no strobe." Two
fixtures (`MartinMac250`, `RobeColorSpot575`) pass `max = strobeMax` to
keep their slider-clamp safety; their `lampOn()` / `lampOff()` / `reset()`
methods plus the band/level constants moved to the Mode-class companion
since the standalone `StrobeChannel` was deleted. Per-fixture tests were
updated to point at the new constant locations. `WhexFixture.DmxStrobe`
was intentionally left as-is — the formula `(255F / 245F * intensity)`
appears inverted (mirror image of [HexFixture](../../../src/main/kotlin/uk/me/cormack/lighting7/fixture/dmx/HexFixture.kt)
which uses `(245F / 255F * intensity)`); preserving "no behaviour change"
trumped the visible bug, so a separate fix is wanted before refactoring.

**Goal**: Collapse the ~12 near-identical `StrobeChannel` inner classes scattered
across the fixture codebase into a single shared abstraction.

**Entry criteria**: None — independent of the patching tiers. Can run any time
after Tier 8. Tier 9 does not depend on this and vice versa.

**Background** — surfaced by the simplify pass during Tier 8 (2026-04-26): every
fixture that exposes `WithStrobe` defines its own `class StrobeChannel(...) :
DmxSlider(...), Strobe` whose `strobe(intensity)` body is the byte-identical
expression
`((span / 255F * intensity.toFloat()).roundToInt() + STROBE_MIN.toInt()).toUByte()`,
differing only in the `STROBE_MIN` / `STROBE_MAX` band constants and the
`fullOn()` value. Known instances at time of writing:

- `KamLiteobar252Fixture.StrobeChannel` (1u..255u, fullOn = 0u)
- `China2CellLedBlinderFixture.StrobeChannel` (11u..255u, fullOn = 0u)
- `Gear4MusicOrbit70Fixture.StrobeChannel` (16u..131u, fullOn = 248u)
- `ShehdsLed19RgbwFixture.StrobeChannel` (1u..255u, fullOn = 0u)
- `VarytecEasymoveXl60SpotFixture.StrobeChannel` (1u..255u, fullOn = 0u)
- `ImgStageLineWash42LedFixture.StrobeChannel` (135u..239u, fullOn = 240u)
- `HexFixture.DmxStrobe` (10u..255u, fullOn = 0u)
- `SlenderBeamBarQuadFixture.StrobeChannel` (8u..255u, fullOn = 0u)
- `Fusion100SpotMkIIFixture.StrobeChannel`
- `Scantastic4Fixture.StrobeChannel`
- `MartinMac250Fixture.StrobeChannel` (50u..72u, fullOn = 35u — note slider is
  also `max`-clamped to 72u for safety; the new abstraction must support that)
- `RobeColorSpot575Fixture.StrobeChannel` (64u..95u, fullOn = 35u — slider also
  `max`-clamped to 95u so neither raw `value` writes nor `Strobe` calls can
  wander into the pulse / random-strobe bands)
- `LedLightbar12PixelFixture.StrobeChannel`
- `AdjFogFuryJettFixture.StrobeChannel` (32u..95u, fullOn = 0u)

**Proposed API** (open class so MartinMac250's safety clamp can extend it):
```kotlin
open class BandedStrobeChannel(
    transaction: ControllerTransaction?,
    universe: Universe,
    channelNo: Int,
    private val strobeMin: UByte,
    private val strobeMax: UByte,
    private val fullOnValue: UByte = 0u,
    max: UByte = 255u,
) : DmxSlider(transaction, universe, channelNo, max = max), Strobe {
    override fun fullOn() { value = fullOnValue }
    override fun strobe(intensity: UByte) {
        val span = (strobeMax - strobeMin).toFloat()
        value = ((span / 255F * intensity.toFloat()).roundToInt() + strobeMin.toInt()).toUByte()
    }
}
```

The MartinMac250 case (slider `max`-clamped for safety so neither raw `value`
writes nor `Strobe` calls can wander into Reset/Lamp bands) is preserved by
passing `max = STROBE_BAND_MAX` to the base constructor and keeping
`MartinMac250Fixture.StrobeChannel` as a thin subclass that exposes the
explicit `lampOn()` / `lampOff()` / `reset()` methods.

**Approach**:

- [x] Add `BandedStrobeChannel` next to `DmxSlider.kt` in `fixture/dmx/`.
- [x] Replace each fixture's `StrobeChannel` inner class with either a direct
      `BandedStrobeChannel(...)` call site (most fixtures) or a thin subclass
      (MartinMac250 keeps its lamp/reset methods on the Mode4Ch companion;
      Fusion100Spot / LedLightbar12Pixel / SlenderBeamBarQuad subclass
      `BandedStrobeChannel` to preserve their `strobe(0) → fullOn()` quirk).
- [x] Each fixture's existing `STROBE_MIN` / `STROBE_MAX` companion constants
      moved out of the deleted `StrobeChannel` class onto the parent fixture
      class (or its Mode-class companion); names unchanged. Tests updated to
      point at the new locations.
- [x] Update each fixture's per-fixture test to construct the new strobe class
      with the band constants and confirm the same channel writes.
- [x] `./gradlew test` — `BUILD SUCCESSFUL`.

**Out of scope**:

- Renaming / re-organising existing `STROBE_MIN` / `STROBE_MAX` companion
  objects — leave them on the fixture class for documentation purposes.
- Touching strobe band semantics (which DMX values are reachable). This is
  purely a code-organisation refactor, not a behaviour change.
