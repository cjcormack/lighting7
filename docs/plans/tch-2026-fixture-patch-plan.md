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

**Next action**: User to answer [Open Questions](#open-questions) in ChamSys, then start with
[Tier 0](#tier-0--generic-1-channel-dimmer-fixture) (smallest, unblocks all the conventional
patches in one go).

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
   - **Answer:**

2. **Robe ColorSpot 575 AT "Mode 2" — channel count?** Patched at 19-channel intervals
   (`01-202` → `01-221`). Manuals:
   [User_manual_ColorSpot_575_AT.pdf](../../Manuals/User_manual_ColorSpot_575_AT.pdf),
   [ColorSpot_575_AT_DMX_charts.pdf](../../Manuals/ColorSpot_575_AT_DMX_charts.pdf).
   - Confirm Mode 2 = 19ch (vs Mode 1 standard / Mode 3 reduced).
   - **Answer:**

3. **ETC Source 4 Revolution "Base Frame" — exact mode/channel count?** Patched at
   `02-001` and `02-101` (100ch spacing — almost certainly just clean addressing, not actual
   channel count). Manual:
   [S4_Revolution_User_Manual_RevE.pdf](../../Manuals/S4_Revolution_User_Manual_RevE.pdf).
   "Base Frame" likely means the chassis without the optional Framing Shutter or Iris module.
   - Which personality is this fixture set to? (e.g. "Standard 14ch", "Standard + Framing
     26ch", etc.)
   - Are the framing shutters physically installed at TCH or not?
   - **Answer:**

4. **Shehds LED19x15W RGBW — 24ch personality.** Patched at 24ch intervals. Manual
   [19颗调焦4合1染色灯(16-24CH)最新.pdf](../../Manuals/19颗调焦4合1染色灯(16-24CH)最新.pdf)
   covers both 16ch and 24ch modes.
   - Confirm 24ch = the per-pixel mode, and 16ch = global RGBW + macros.
   - Does ChamSys split this into 19 individual RGBW pixels in 24ch mode, or treat it as one
     fixture? (Affects whether we need `MultiElementFixture` or just a flat property list.)
   - **Answer:**

5. **ChamSys personality export.** For the three unmanualed fixtures (Kam Liteobar 252,
   Gear4Music SOL Party 12B, China 2-Cell LED Blinder), we need DMX charts. Easiest options
   in order:
   - Export `.fxt` personality files from MagicQ/MagicVis (`Setup → Patch → Export` or via
     the personality editor). Drop them into [Manuals/](../../Manuals/) (or a new
     `Manuals/personalities/` subfolder).
   - Open the personality in MagicQ's "Edit Head" view and screenshot each channel's settings.
   - Last resort: paste the channel map as text in the relevant Tier section below.
   - **Method chosen:**
   - **Files added:**

6. **Kam "Liteobar 252" — model spelling.** ChamSys lists `Liteobar252`. The actual product is
   probably the **Kam LightBar 252** or **LiteBar 252**. Confirm exact product name + DMX mode
   (the channel count is 11ch).
   - **Answer:**

7. **Gear4Music "SOLParty12B" — confirm model.** Likely the Gear4Music SOL Party Bar 12 B (an
   8-channel LED party bar). Confirm exact product name.
   - **Answer:**

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

- [ ] Create `src/main/kotlin/uk/me/cormack/lighting7/fixture/dmx/GenericDimmerFixture.kt`
  - 1 channel, `WithDimmer` only.
  - `@FixtureType("generic-dimmer", manufacturer = "Generic", model = "Single-channel dimmer")`.
- [ ] Verify `FixtureTypeRegistry` picks it up automatically (registry is reflection-driven —
  no manual registration needed if the class lives under `fixture/dmx/`).
- [ ] Add a unit test mirroring an existing simple-fixture test (channel write through
  transaction round-trips).
- [ ] Update `docs/fixtures-engineering.md` — add the new fixture to the "Existing Fixture
  Implementations" table at the bottom.
- [ ] Run `./gradlew test`.

---

## Tier 1 — Simple manual-backed batch

**Goal**: Three small manual-backed fixtures in one session.

**Entry criteria**: Tier 0 done. No open questions block this tier.

- [ ] **ADJ Fog Fury Jett** (7ch) — [manual](../../Manuals/download_215302.pdf)
  - Likely channels: dimmer/output, optional DMX-mode fan/timer settings. Treat as
    `WithDimmer` plus a `DmxFixtureSetting` for any sound/auto modes.
- [ ] **Equinox Twin Shot MKII** (3ch confetti launcher, EQLED406) —
  [manual](../../Manuals/EQLED406_Manual.pdf)
  - 3 channels = master + fire-left + fire-right (verify against manual). No `WithDimmer` if
    it's purely a fire trigger; expose `Slider` per channel and let scripts treat it as a
    momentary trigger.
  - **Safety note**: this is a pyrotechnic-adjacent device. Document in the class doccomment
    that random FX should not target the trigger channels.
- [ ] **ImgStageLine Wash-42LED** (13ch) — [manual](../../Manuals/WASH-42LED@BDA.pdf)
  - 13ch is most likely RGBW + strobe + macros. Use `WithDimmer`, `WithColour`, `WithStrobe`.
- [ ] Tests for each (one per fixture is fine).
- [ ] Update `docs/fixtures-engineering.md` table.
- [ ] `./gradlew test`.

---

## Tier 2 — Orbit-70 moving head

**Goal**: The Gear4Music Orbit-70 (13ch). Highest-quantity DMX fixture (6 patched), so worth
its own session.

**Entry criteria**: Tier 0 done.

- [ ] Read the manual end-to-end: [download_399717.pdf](../../Manuals/download_399717.pdf).
  Pay attention to the channel chart and any pan/tilt range specs.
- [ ] Implement as a single-mode fixture (no `MultiModeFixtureFamily` needed unless the
  manual exposes more modes — verify first).
- [ ] Traits: `WithDimmer`, `WithColour`, `WithPosition`, `WithStrobe` if present.
- [ ] Likely needs `DmxFixtureSetting` enums for built-in macros / colour macros / sound mode.
- [ ] Test.
- [ ] `./gradlew test`.

**Note**: ChamSys lists the model as `Orbit70WLEDHead`. Don't take that name literally —
follow the manual's naming.

---

## Tier 3 — Easymove XL 60 moving head

**Goal**: Varytec Easymove XL 60 Spot (11ch).

**Entry criteria**: Tier 0 done.

- [ ] Read [246811_manual.pdf](../../Manuals/246811_manual.pdf) — section 5.1 has the DMX
  channel list.
- [ ] Single-mode fixture (verify in manual).
- [ ] Likely traits: `WithDimmer`, `WithPosition`, `WithStrobe`. May have a colour wheel
  rather than RGB mixing — use `DmxFixtureSetting<Colour>` not `WithColour` if so.
- [ ] Test.
- [ ] `./gradlew test`.

---

## Tier 4 — Shehds LED19x15W RGBW

**Entry criteria**: [Open question 4](#open-questions) answered.

- [ ] Manual: [19颗调焦4合1染色灯(16-24CH)最新.pdf](../../Manuals/19颗调焦4合1染色灯(16-24CH)最新.pdf).
  Translate the channel chart from Chinese (model the structure on `SlenderBeamBarQuadFixture`).
- [ ] `sealed class ShehdsLed19RgbwFixture` with `Mode.MODE_16CH` and `Mode.MODE_24CH`.
- [ ] Implement Mode 24Ch first (the patched mode). Per-pixel control depends on the answer
  to OQ4 — if 24ch is per-pixel for 19 LEDs, this needs `MultiElementFixture<Pixel>`.
- [ ] Implement Mode 16Ch as well if straightforward; otherwise leave a TODO.
- [ ] Tests.
- [ ] `./gradlew test`.

---

## Tier 5 — Martin MAC 250

**Entry criteria**: [Open question 1](#open-questions) answered.

- [ ] Manual: [UM_MAC250_EN_D.PDF](../../Manuals/UM_MAC250_EN_D.PDF). Pick the variant the user
  confirms.
- [ ] `sealed class MartinMac250Fixture` with the four DMX modes from the manual; implement
  the patched mode (likely `MODE_4`).
- [ ] Traits: `WithDimmer`, `WithPosition`, `WithStrobe`. Colour wheel +
  CMY-or-colour-macro `DmxFixtureSetting`s; gobo wheel; effect/prism wheel.
- [ ] Tests.
- [ ] `./gradlew test`.

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

**Entry criteria**: [Open question 3](#open-questions) answered.

- [ ] Manual: [S4_Revolution_User_Manual_RevE.pdf](../../Manuals/S4_Revolution_User_Manual_RevE.pdf).
- [ ] `sealed class Source4RevolutionFixture` with the personalities the manual lists. The
  fixture supports interchangeable modules (Iris, Framing Shutter), each adding channels.
- [ ] Implement the "Base Frame" personality the user confirmed in OQ3. Leave other modes as
  `// TODO` enum entries with a doc comment pointing at this plan.
- [ ] This is a discharge fixture (HMI 700) — same lamp/shutter/dimmer caution as Robe.
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
