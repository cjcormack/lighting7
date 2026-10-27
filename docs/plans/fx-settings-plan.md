# FX settings: fewer FX, step-aligned chases, and an editor in four questions

> **Document status: READY — written 2026-10-26 from the page Chris and Claude agreed that day.**
> Six sessions, each one PR, each stacked on the one before while that one is unmerged, each run by
> `/next-session` (§5). The page and the record are in
> [`fx-settings-design/INDEX.md`](fx-settings-design/INDEX.md).
>
> This document is the engineering half. Where it and the page disagree, this plan wins on
> behaviour and the page on layout and copy.

## 1. Context

Chris's brief, 2026-10-26, after the FX editor follow-ups
([`fx-editor-followups-plan.md`](fx-editor-followups-plan.md)): Colour Cycle with three colours over
four fixtures at Spread 100 % staggers its colour edges, because four heads start a quarter of a
cycle apart and the colours change every third. 133 % aligns them, which nobody should have to work
out at a desk. And nobody knows what the Wings, Segments and Parts boxes do. He asked for a review of
every FX setting and type, against use-cases and other desks, simplifying without losing the engine.

What the code says, read at `e7fc7a8`. `F/` is `frontend/src`, `K/` is
`src/main/kotlin/uk/me/cormack/lighting7`, `R/` is `src/main/resources`.

- **The editor** is `FxFields` (`F/components/fx/FxFields.tsx`) in two layouts: `inspector` (the FX
  editor's right side: Cycle, Amount · Direction · Order · Over, Shape, Spread, Over what's
  underneath, Advanced) and `editor` (the tray, the popover, the palette editor and the cue FX tab).
  Cycle is an eight-button grid, a typed field, *Same cycle as…* and the timing chip. The Spread
  disclosure holds nine Shape chips drawn on eight notional heads (`fxShapes.ts`), Spread %, and
  Wings · Segments · Parts as three `CountRow`s under one grey line.
- **Parameters** are `ParameterInfo(name, type, defaultValue, description)` (`K/fx/FxRegistry.kt`),
  parsed from each `.fx.kts` frontmatter by `FxFileLoader`. No range, unit, options or importance
  travels; `paramRole` (`F/components/fx/fxEditorModel.ts`) guesses roles from names and types, and
  `PARAM_LABELS` renames six of them. `FU-FE-FX-PARAM-RANGE` tracks the gap.
- **The 29 built-ins** repeat each other: Pulse, Iris Pulse and Frost Pulse are one script; Sine Wave
  and Zoom Wave another; Ramp Up, Ramp Down, Ramp and Focus Pull are one formula; Square Wave and
  Dimmer Strobe compute the same square; Sweep is a superset of Pan Sweep and Tilt Sweep; Circle,
  Figure 8 and Random Position share four parameters under two names; Colour Fade and Colour Wave are
  two-colour Colour Cycles. One idea, a low and high level, has six spellings (`min/max`,
  `offValue/onValue`, `offLevel/onLevel`, `minBrightness/maxBrightness`, `start/end`, `baseLevel`).
- **Spread** is `SpreadShape.spread` (`K/fx/SpreadShape.kt`), a share of the cycle over every slot:
  `offset = spread × rank / slots`. It never knows the FX's step count. `FxContext.numDistinctSlots`
  already tells an FX how many slots it runs over.
- **Migrations:** `PRAGMA user_version` is at 6 (`AttributeNamesMigration`); `FxLibraryMigration`
  (step 4) is the precedent for rewriting stored FX through `sync/LegacyNames.kt`, which the importer
  shares. Sync is at `formatVersion` 40.

## 2. Decisions

| # | Decision |
|---|---|
| D1 | **The desk declares each parameter's metadata** in frontmatter: `label`, `min`, `max`, `step`, `unit` (`%`, `dmx`, `position`, `ms`, `ratio`), `options` (a `choice` type's values and labels), `role` (`main` or `detail`), `shownWhen` (another parameter's values), and `pair` (a low and high drawn as one range). The library DTO carries it; the editor draws from it and stops guessing. A script FX that declares none is drawn as today. |
| D2 | **Consolidate on the desk.** **Wave** (`Wave`) replaces Sine Wave, Ramp Up, Ramp Down, Ramp, Square Wave, Pulse, Dimmer Strobe, Zoom Wave, Iris Pulse, Frost Pulse and Focus Pull: a `shape` of Sine · Ramp up · Ramp down · Triangle · Square · Pulse, `low`, `high`, `width`, `attack`, `hold`, `curve`, on intensity and beam attributes. **Colour Cycle** absorbs Colour Fade and Colour Wave: `colours`, `between` Cut · Fade · Smooth, `fade`. **Path** (`Path`) replaces Circle, Figure 8, Sweep, Pan Sweep, Tilt Sweep and Random Position: a `shape` of Circle · Figure 8 · Line · Random, a centre and size, and a Line's two ends. The Flicker family (Flicker, Candle Flicker, Fluorescent Flicker, Lightning Strike), Colour Strobe, Rainbow Cycle, Colour Flicker, Palette Steps and Slot Steps keep their own types, since their timing models or outputs differ; their parameters take D1's normalised names (`low`, `high`, `width`). 29 built-ins become 12. |
| D3 | **Rewrite once, read old names.** A one-shot data step rewrites every stored Look, cue and palette FX onto D2's types and parameters, and `LegacyNames` maps old ids and parameters wherever one can still arrive: an archive's import, MCP's tools, a script's `effect("SineWave", …)`. **A migration never changes what the rig does.** A stored FX with no exact equivalent stays on its old type, kept registered as **legacy**: hidden from the picker and the library listing, still run, still editable. Sessions 2 and 3 prove each mapping against the old script before it lands. |
| D4 | **Picker presets keep the familiar names.** An FX declares `presets` in frontmatter (a name, an attribute, parameters, a cycle and direction): Wave's include *Strobe*, *Zoom Wave*, *Iris Pulse*, *Frost Pulse* and *Focus Pull*; Colour Cycle's *Colour Fade*; Path's *Sweep*. The picker lists presets beside types, so a word an operator looks for is still there (Hog 4.0's removal of its stock colour effects drew complaints). MCP's library listing names them. |
| D5 | **A multi-family FX takes its family from the attribute it drives.** Wave on `zoom` is Beam for the speed-master routing (venue-visit D16), the picker's family segment, an FX palette's family and Record, through one function beside `attributeFamilyForFxFamily`. Frontmatter's `family` stays the FX's home family for the library's grouping. |
| D6 | **Each head later by.** Spread gains a unit, `spreadUnit`: `CYCLE` (today: `spread` is the share of the cycle over every slot) or `STEP` (`spread` is how many of the FX's steps each slot starts after the one before). The desk resolves `STEP` at expansion as `spread × slots / steps`, so a fixture joining the selection keeps the chase aligned. An FX that steps declares where its step count comes from (`stepsFrom: colours`): Colour Cycle, Palette Steps, Slot Steps. `STEP` on an FX that declares none is refused at the write boundary. It is stored on every FX home and synced. |
| D7 | **A new step FX starts one step behind.** Every door that starts or creates an FX writes `STEP` with `spread` 1 for an FX that declares `stepsFrom`, beside D8's `WHOLE` (the FX editor follow-ups plan): the picker, the bare press, `POST /fx/add`, the group FX route, MCP's tools, a cue's draft FX, a new FX palette. A stored FX naming no unit means `CYCLE` (`SpreadUnit.STORED_DEFAULT`), so no row or archive changes meaning. |
| D8 | **Width: One head.** Wave's `widthUnit` (`ratio` or `head`): `head` makes Square and Pulse on for `1 / numDistinctSlots` of the cycle, so exactly one head is on at a time whatever the selection (grandMA3's Adaptive Width, for any number of steps). It is a parameter, read against the context the FX already gets; no engine field. |
| D9 | **The inspector in four questions.** **Timing**, one strip of chips: Cycle (`1 bar ▾`, opening the eight steps, the typed field and *Same cycle as…*), the master or Seconds, Direction, Runs (`∞ ▾`, opening ×1 to ×4 and a count). **The FX's own section**, named for what it holds: *Levels* (a Wave), *Colours*, *Path*, *Steps*, *Flicker*, *Parameters* (a script FX). Never *Look*, the library's word for a recorded state. A `choice` is a segment, a Wave's or a Path's shape drawn as small pictures; a `pair` is one two-thumb range; `detail` parameters fold under *More*. **Across the heads** (D10). **Mix**: Amount beside the family's question (*Replace it · Within it*, *Around current position · Absolute*). **Advanced**: Phase, *One cycle for: All heads · Each head* (today's *Timing: Per cycle · Per head*), the head filter, the raw blend. Every segment of presets ends in a typed value. Both layouts follow; the `editor` layout keeps its narrower spacing. |
| D10 | **Across the heads.** Over first (where a fixture has elements). Then the **head ladder**: the selection's own heads, numbered by start (the preview's ladder in the inspector, `lib/spreadShape.ts` elsewhere). Then the **patterns**, today's nine Shape chips, each with its drawing on the selection's own heads (eight notional on a palette); the Order select shows only under *Custom*. Then **Each head later by**: *Fit the cycle* (`CYCLE`, 1) · *1 step* (`STEP`, 1, on an FX with steps) · *Free* (a slider in steps or in cycles), with a readout of the offset between neighbours as a share of the cycle, beats, degrees and steps, and the spread the desk runs. Then **Segments · Parts · Wings**, MagicQ's words kept, each a segment `1 · 2 · 3 · 4 · …` (`off` for Parts and Wings) with one line under them saying what the current values do (*Segments 2: neighbours move in pairs*). Wings stays hidden under a mirrored Order. |

The plan adds three of its own:

- **P1 — Six sessions, six stacked PRs**, in §5's order, each run by `/next-session`
  (`.claude/skills/next-session/SKILL.md`) through `/verified-ship`. A session stacks on the previous
  session's branch while that PR is open, and on `main` once it has merged.
- **P2 — The desk owns the rule.** The client draws D6's offsets, D10's ladder and examples from the
  desk's preview or from `lib/spreadShape.ts`, which a shared vector pins to `SpreadShape.kt`; it never
  works out what an FX outputs. Sessions that change the rule extend the vector on both sides.
- **P3 — Equivalence before migration.** No mapping in D3 lands without a test that runs the old
  script (kept as a test resource) and the new FX over a grid of phases, parameter values,
  directions, spreads and element modes and finds the same output to the DMX step. A mapping that
  fails is not forced: the stored FX stays legacy.

## 3. The model

### The mappings (D2, D3)

| Old id | New | Parameters | Dynamics |
|---|---|---|---|
| `SineWave` | `Wave` · Sine | `min`→`low`, `max`→`high` | |
| `RampUp` | `Wave` · Ramp up | + `curve` | |
| `RampDown` | `Wave` · Ramp down | + `curve` | |
| `Ramp` | `Wave` · Triangle | + `curve` | |
| `SquareWave` | `Wave` · Square | `dutyCycle`→`width` | |
| `Pulse` | `Wave` · Pulse | `attackRatio`→`attack`, `holdRatio`→`hold`, `curve` | |
| `DimmerStrobe` | `Wave` · Square | `offValue`→`low`, `onValue`→`high`, `onRatio`→`width` | |
| `ZoomWave` | `Wave` · Sine | `min`/`max` → `low`/`high` | |
| `IrisPulse`, `FrostPulse` | `Wave` · Pulse | as Pulse | |
| `FocusPull` | `Wave` · Ramp up | `start`→`low`, `end`→`high`, `curve` | |
| `ColourCycle` | `ColourCycle` | `fadeRatio` 0 → `between` Cut; else Fade with `fade` = `fadeRatio` | |
| `ColourWave` | `ColourCycle` · Smooth | `colourA`, `colourB` → `colours` | a phase shift if the test finds one |
| `ColourFade` (Bounce) | `ColourCycle` · Fade 100 % | `fromColour`, `toColour` → `colours` | cycle × 2, Forward; spread and phase as the test finds. Forward or Backward stays legacy |
| `Circle` | `Path` · Circle | `panRadius`/`tiltRadius` → `panSize`/`tiltSize` | |
| `Figure8` | `Path` · Figure 8 | as Circle | |
| `RandomPosition` | `Path` · Random | `panRange`/`tiltRange` → `panSize`/`tiltSize` | |
| `Sweep` | `Path` · Line | `startPan`, `endPan`, `startTilt`, `endTilt`, `curve` kept | |
| `PanSweep` | `Path` · Line | `tilt` → `startTilt` = `endTilt` | |
| `TiltSweep` | `Path` · Line | `pan` → `startPan` = `endPan` | |
| `Flicker` | `Flicker` | `min`/`max` → `low`/`high` | |
| `CandleFlicker` | `CandleFlicker` | `baseLevel`→`base`, `min`→`low`, `max`→`high`, `smoothing`→`settle` | |
| `FluorescentFlicker` | `FluorescentFlicker` | `offLevel`→`low`, `onLevel`→`high`, `flickerDurationMs`→`burstMs`, `flickerSpeed`→`speed` | |
| `LightningStrike` | `LightningStrike` | `minBrightness`→`low`, `maxBrightness`→`high` | |
| `ColourStrobe` | `ColourStrobe` | `onRatio`→`width` | |

A Path's Line draws its ends (`shownWhen: shape = LINE`) and its centre and size are hidden; the
other shapes the reverse. The Centre question follows the centre parameters' visibility. The rows
read with `ColourWave` and `ColourFade` are where P3 is expected to find a shift; the session records
what it found.

### Each head later by (D6)

| Unit | `spread` means | The desk runs | Example: 4 heads, 3 colours |
|---|---|---|---|
| `CYCLE` | share of the cycle over every slot (0–2) | `spread × rank / slots` | 1 → each ¼ cycle later |
| `STEP` | steps each slot starts after the one before (0–16) | `spread × rank / steps` | 1 → each ⅓ cycle later |

`STEP` applies after Segments, Wings and Parts, on the slots they leave; its resolved spread may
exceed `MAX_SPREAD` (eight heads, three colours: 8/3), which caps only the stored `CYCLE` value. A
type swap or a colour list edit keeps `STEP` where the new FX steps and converts to `CYCLE` at the
running offsets where it does not.

## 4. UX

The page is the authority on layout and copy: the case, the inspector board and its notes, the live
model of *Across the heads*, the look cards (now D2's FX and D4's picker entries) and the recipes.
Three things it does not show:

- **Touch**: the Timing strip's chips open popovers on a desk and bottom sheets below the editor's
  760 arm, with 44px rows. Segments keep 32px cells on the phone host (`useFingerSized`).
- **Focus**: a chip's popover takes focus and Escape returns it to the chip; a segment's typed cell
  commits on Enter, as `SheetField` does.
- **The graph**: its Spread knob's hover reads D10's words (*Later by ⅓ cycle · 1 step*), and a
  drag of it under `STEP` moves `spread` in steps.

## 5. Implementation — six sessions

Each session is one PR. Each is run by `/next-session`, which cuts the branch, stacks it on the
previous session's branch while that PR is open (on `main` once merged), records the previous
session's PR and head SHA in the table below, and hands the session to `/verified-ship`. A session
ends with its checks green (§"Pre-commit checks" in the root `CLAUDE.md`; sessions 2, 3 and 4 also
run the full `./gradlew test` and `npm run check`, as a migration and a sync `formatVersion` bump
require), the docs it names written, and its row marked *in review* with its branch. A session's PR
is titled *FX settings: session N — title*. Never merge a later session's PR before an earlier one.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | Parameter metadata: the desk declares, the editor reads | merged | `claude/fx-settings-s1` | [#299](https://github.com/cjcormack/lighting7/pull/299) | `dc33373d` |
| 2 | Wave: one FX for the level waves | merged | `claude/fx-settings-s2` | [#302](https://github.com/cjcormack/lighting7/pull/302) | `5ad80802` |
| 3 | Colour Cycle and Path absorb their siblings | in review | `claude/fx-settings-s3` | | |
| 4 | Each head later by: spread in steps, and Width one head | planned | | | |
| 5 | The inspector in four questions | planned | | | |
| 6 | Across the heads | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — Parameter metadata: the desk declares, the editor reads

- **The desk (D1):** `ParameterInfo` gains `label`, `min`, `max`, `step`, `unit`, `options`, `role`,
  `shownWhen` and `pair`, all optional; `FxFileLoader.parseSimpleYaml` reads them (its continuation
  keys list grows), and the `choice` parameter type joins `TypedParams` (`params.choice("shape")`,
  refusing a value outside `options`). The library DTO and `GET /fx/library` carry them; MCP's library
  listing includes them. Every built-in declares its metadata, labelled in the page's words (*Low*,
  *High*, *Width*, *Attack*), with today's script clamps as the ranges.
- **The editor:** `ParamControl` draws from the metadata where it is present: a `choice` as a
  segment, a `pair` as one two-thumb range, a `unit` in its field, `detail` behind a *More* fold, a
  parameter whose `shownWhen` is false not at all. `paramRole` and `PARAM_LABELS` remain only for a
  script FX that declares none. Nothing else in the editor moves.
- **Tests:** `FxFileLoaderTest` (each key parsed; a `choice` refusing an unknown value), a test that
  every built-in parameter declares a label and, where numeric, a range; `FxFields.test.tsx` (a
  segment for a choice, a range for a pair, `shownWhen` hiding, the fallback for a bare script FX).
- **Docs:** `docs/fx-engineering.md` §"Parameters"; `docs/show-scripts-engineering.md` on what a
  script FX may declare. Close `FU-FE-FX-PARAM-RANGE` in `docs/plans/followups.md`.
- **As built:** `%` is a byte level read as a percent and `ratio` a 0–1 share read as one; ranges
  are in the stored value's terms. A `pair` sits on the low and names its high. A `choice` matches
  its options ignoring case; the write boundary refuses another (`fxWriteProblem`), and
  `params.choice` reads one an older row holds as its default. An `easingCurve`'s options are the
  desk's `EasingCurve`s, filled in by `GET /fx/library`, because the client's own list named curves
  the desk lacks (`NORMAL`, `EXPO_*`, which ran as Linear) and missed four it has; a fork stores no
  curve options, so it follows the desk's. A unit names the types it reads (`%` and `position` a
  byte's, `ratio` a fraction's). The editor reads what a parameter declares and guesses each field it
  leaves out, as before. A user FX definition's and a script's `registerFx` metadata are checked as a
  built-in's is (400 on `/fx/definitions`). No `formatVersion` change: `docs/sync-engineering.md`
  §"Format versioning" records why.

### Session 2 — Wave: one FX for the level waves

- **Wave (D2):** `R/fx/dimmer/Wave.fx.kts` (listed in `R/fx/index.txt`, as session 3's Path is), family `intensity`, compatible
  `[dimmer, uv, zoom, iris, frost, focus]`, its parameters per §3 with D1's metadata (`width` shown for
  Square, `attack` and `hold` for Pulse, `curve` for the ramps, the triangle and Pulse). Per-attribute
  defaults (zoom 64–192, iris and frost 0–160) are frontmatter `attributeDefaults`, which `FxSpawner`
  and the picker read for a new FX.
- **Family by attribute (D5):** one desk function names an FX's family from the attribute it drives,
  used by `familyMasterUuidFor` (`K/fx/FxTimingChoice.kt`), `AttributeMask`, the palette family
  checks (`K/fx/PaletteRegistry.kt`, `routes/projectPalettes.kt`), Record and the Timeline; and its
  client twin in `F/lib/attributeFamily.ts` for the picker, `PaletteEditor` and `fxRows.ts`. Read
  `grep -rln attributeFamilyForFxFamily` for the full list before starting.
- **Presets (D4):** frontmatter `presets`, carried by the library DTO; the picker lists Wave's
  (*Strobe*, *Zoom Wave*, *Iris Pulse*, *Frost Pulse*, *Focus Pull*) under the family the attribute
  gives, each starting Wave with its parameters, cycle and direction. `newFxDivision` /
  `newFxDirection` (`F/lib/fxTiming.ts`) read a preset's in place of the old ids.
- **Migration (D3):** `LegacyNames` gains the Wave rows of §3 (id and parameters, by
  `formatVersion`); `state/FxConsolidationMigration.kt`, `user_version` 7, rewrites stored Look, cue
  ad-hoc and palette FX, backing the database up first when it changes anything
  (`<db>.pre-fx-consolidation-<utc>.db`) and logging each FX by its owner's name. The registry
  resolves an old id through `LegacyNames` for `effect()` and MCP. The old scripts move to
  `src/test/resources/fx/legacy/` and leave the library; any FX P3 refuses to map stays registered as
  legacy (`legacy: true` in frontmatter, left out of the listing and the picker).
- **Equivalence (P3):** `LegacyEquivalenceTest` compiles each old script and Wave and compares
  outputs over phases 0–1 in 1/96 steps, a parameter grid, the three directions and a spread of 4
  heads; each row of §3's Wave block is a case.
- **Sync:** `formatVersion` and `minReader` 41, since a 40 reader cannot run a Wave. The importer maps
  an older archive's ids and parameters through `LegacyNames`. `RichProjectFixture` stores a Wave.
- **Tests:** the migration over a database holding every old id (frozen as
  `src/test/resources/db/pre-fx-consolidation.sql`, never regenerated), the round trip, a script's
  `effect("SineWave", "min" to "40")` running a Wave, the picker listing the presets,
  `GlossaryVocabularyTest` with the new rows.
- **Docs:** root `CLAUDE.md` (the built-in list and its count, the migrations section),
  `docs/fx-engineering.md`, `docs/sync-engineering.md` (41), `docs/glossary.md` (Wave and its shapes
  `done`; the old names as *Was*).

- **What landed, and where it differs from the above:**
  - **P3:** `LegacyEquivalenceTest` found every one of the eleven old FX equal to its Wave, to the
    byte, over 97 phases plus the edges, a spread over four heads at five spreads, each Direction on
    an even and an odd cycle, three contexts and each row's parameter grid (blank, out-of-range and
    unparsable values included). None stays legacy, so the `legacy: true` frontmatter key was not
    built: session 3 adds it if P3 refuses a colour or position mapping.
  - **Old ids map by name, not by `formatVersion`**, as every `LegacyNames` map does: no old id
    returns with a new meaning. `LegacyNames.consolidateFx` runs after `rewriteFx` on import and is
    database step 7 alone, so step 4 never half-converts a row.
  - **The registry does not resolve an old id.** A script's `effect()` and MCP's FX tools map one
    (`LegacyNames.runnableFx`, `asWave`) before they reach it; every stored write still refuses one
    (`writeProblem`), naming its shape. An old Focus Pull from MCP or a script starts at its half-bar
    Bounce (`waveTiming`).
  - **`attributeDefaults` also covers `focus`** (64–192, Focus Pull's), and is laid under a spawn's
    parameters on every spawn that names its attribute (`withAttributeDefaults`: `FxSpawner`, the FX
    routes, the group route, the preview), not a script's `effect()`, which builds the FX before it
    knows the attribute.
  - **D5's rule:** Beam on a beam attribute (zoom, iris, frost, focus, prism — the palette
    vocabulary's), else the library family. A Wave on `uv` stays Intensity, as Sine Wave on UV always
    routed, rather than taking UV's Colour. A stored FX keeps `family: intensity` — Record files a
    Wave so too — and the function is read over it, which is what keeps a palette's Rule 3 (its family
    agrees with the registration) true. The picker lists the bare Wave under Beam as well as its
    presets, on the head's beam attribute.
  - **Old ids the registry does not resolve.** A script or FX definition that looks one up
    (`getRegistration("Pulse")`) finds nothing; step 7 names each such stored script in a warning, as
    step 6 names an old centre, and rewrites none. A project FX definition may no longer take the id
    `Wave`, and step 7 names one that already does.
  - **A Wave in a Beam palette names its attribute** (the palette editor's *Attribute* select), since
    six compatible attributes name no single one; step 7 writes a beam palette FX's attribute.
  - Wave's `curve` defaults to `LINEAR`; a stored Pulse is written with its `QUAD_OUT`, and the
    presets name theirs.
  - **One recorded exception to D3**, which Chris accepted on 2026-10-26: a beam wave stored with no
    attribute — every Beam FX palette the UI made, and any beam Look or cue FX stored without one — ran
    on a group only where every head had its attribute, and on none of that group otherwise. Migrated,
    it names the attribute, so on a mixed group it now runs on the heads that have it, as a Wave
    authored on that attribute does. Exact behaviour would have needed hidden legacy types for most
    Beam palettes (D3's route) or a stored marker. Fixture and selection targets, groups whose heads
    all have the attribute, and *Apply as values* are unchanged. Step 7 and the importer name every
    such row in the log (`output moves: …`), and `FxSpawnerBeamTest` pins both sides.

### Session 3 — Colour Cycle and Path absorb their siblings

- **Colour Cycle and Path (D2):** Colour Cycle gains `between` (Cut · Fade · Smooth) and `fade`;
  Smooth eases between neighbouring colours on a cosine. `R/fx/position/Path.fx.kts` with §3's
  parameters and `shownWhen`. Their presets: *Colour Fade*, *Colour Wave*, *Sweep* (Line, Bounce,
  half a bar), *Pan Sweep*, *Tilt Sweep*.
- **Renames:** the Flicker family's and Colour Strobe's parameters per §3, in their scripts and
  through `LegacyNames`.
- **Migration (D3):** the §3 rows for these, as a second step, `user_version` 8, on session 2's
  pattern, and `LegacyEquivalenceTest` cases for each. `ColourWave` and `ColourFade` are where a shift
  is expected; record what the test found in this plan, and leave as legacy any that P3 refuses.
- **The Centre question:** `hasCentre` and the Around pinning (`LegacyNames.CENTRED_FX`,
  `centreModeOf`) read Path's centre parameters through their `shownWhen`, so a Line asks no Centre
  question.
- **Sync:** `formatVersion` and `minReader` 42.
- **Tests:** as session 2's, for these FX; a Line Path round-trips its ends; Around on a Circle Path
  pins both centres; the picker's *Sweep* starts a Line on Bounce.
- **Docs:** as session 2's.

- **What landed, and where it differs from the above:**
  - **P3 refused Colour Wave and Colour Fade**, each by one DMX step, so both stay registered as
    **legacy** (`legacy: true` in frontmatter, built here as session 2 foresaw): a stored one runs and
    edits as before under its own id, and the picker, the palette editor's FX list (but for the one a
    palette already holds), the FX Library page and `describe_rig` leave it out. The library DTO still
    carries it, flagged, so the editor can draw a stored one. Colour Wave against Smooth a quarter-cycle
    on: equal on black–white and red–blue, but on a mixed pair (`#102030` to `#f0e0d0`) 4 of 97 phases
    differ by a step, where the cosine and Colour Wave's sine floor either side of a byte; Backward and
    Bounce would need another offset besides. Colour Fade (Bounce) against Fade 100 % at twice the cycle,
    Forward: a step apart at every turn (5 of 579 samples on black–white, 247 on the mixed pair), because
    the bounce's reversed phase reads 2ε short of the end and floors below Colour Cycle's own fall; no
    spread or phase shift helps. `LegacyEquivalenceTest` pins both refusals. Their presets on Colour
    Cycle start new ones: *Colour Fade* (two colours, Fade 100 %, a four-beat cycle Forward) and
    *Colour Wave* (black to white, Smooth).
  - **Every other mapping is exact**: the six movements as Path, the renamed parameters (the stateful
    Candle and Fluorescent Flicker compared tick for tick over 400 ticks, a low above a high throwing
    alike), and Colour Cycle's `fadeRatio` as `between` and `fade`. A `fadeRatio` of 0 is Cut; any
    other, a blank or unparsable one included, is Fade at the value the old script read, ratios outside
    0–1 and `NaN` running as they ran. Colour Cycle's Fade is the old crossfade's formula unchanged.
  - **Path's defaults are Circle's and Sweep's**; Figure 8's 32 tilt size, the sweeps' and a Pan or
    Tilt Sweep's fixed axis (both ends) are written out by `consolidatedParameters`, as session 2's
    defaults were. The three sweep presets bounce at half a bar.
  - **Step 8 is `LegacyNames.absorbFx` alone** (`state/FxSiblingsMigration.kt`, `user_version` 8); the
    importer runs `rewriteFx`, `consolidateFx` and `absorbFx` in that order. Renames are by key only and
    touch only a row that holds an old key; an old key beside its new one wins, being what the FX read.
    `waveParameters` and `waveTiming` became `consolidatedParameters` and `consolidatedTiming`, which
    cover Wave's rows and Path's; MCP's `asWave` became `asToday`.
  - **The Centre question** reads the centres through their `shownWhen` on both sides: the client's
    `hasCentre(entry, parameters)` (with `isParamShown` moved into `centreMode.ts`), and the desk's
    stacking start through `shownParameters` (`fx/ParameterMetadata.kt`). A shape change across a Line
    moves the blend with it (`centreFlip`): into a centred shape *Around*, out of an *Around* one
    Override, any other blend kept. `LegacyNames.CENTRED_FX` gains Path, so a write naming `panCenter`
    on one is refused by name.
  - Frontmatter's inline preset maps take a quoted value holding commas (`colours: "#ff0000,#0000ff"`).
  - **A key under a new name the old FX never read goes** from a row written before the renames (step
    8; the importer below `formatVersion` 42), or the new script would start reading it: a Fluorescent
    Flicker's stray `high` beside `offLevel` ran at 255 and would have run at its value. A tool's call
    today keeps one. A movement's `panCenter` from a tool is its centre.
  - **No FX definition may take Path's id**, as none may take Wave's (`consolidatedTypeNamed`), and the
    FX Library page reserves a legacy FX's id though it lists none (`offeredFx` is the one filter).
    Step 8 also names a stored script quoting a renamed parameter's distinctive old name. Steps 7 and 8
    are one configured `state/FxRewriteStep.kt`.
  - **`centreFlip`'s rule**, decided here: out of *Around* into a Line, Override; into a centred shape,
    *Around* only while the centres are the pinned 128, so an *Absolute* circle taken through a Line
    comes back Absolute where it was.

### Session 4 — Each head later by: spread in steps, and Width one head

- **The desk (D6):** `SpreadUnit { CYCLE, STEP }` beside `SpreadShape` (`STORED_DEFAULT = CYCLE`);
  `FxDistributionPlan` resolves `STEP` from the FX's step count (frontmatter `stepsFrom`, read from
  the instance's parameters at expansion) on the slots left after Segments, Wings and Parts.
  `FxSampler` and the preview's ladder follow. A type swap or a parameter edit that changes the step
  count re-expands. The write boundary (`FxSpecCoercion`, `applyFxUpdate`) refuses `STEP` on an FX
  without `stepsFrom`, and `spread` above 16 steps.
- **Every home:** `spreadUnit` on `FxDynamics`, the FX DTOs, `POST /fx/add`, `PUT /fx/{id}`, the
  `updateFx` frame (`OPTIONAL_FIELDS`), `POST /programmer/fx`, the group FX route, the stored homes
  (`look_fx`, `cue_ad_hoc_fx`, `palette_fx`: a nullable column, absent = `CYCLE`), Record, Include,
  the Timeline's `comparableFxSettings`, `FxSpawner`, the sync DTOs and MCP's schemas
  (`ai/AiToolSchemas.kt`). Read `grep -rln "spreadSegments\|\.parts\b" src/main/kotlin` for the homes
  `parts` already reaches; `spreadUnit` goes everywhere it does.
- **New FX (D7):** every door session 2 of the follow-ups plan made write `WHOLE` writes `STEP` and 1
  for an FX with `stepsFrom`; the client's doors (the picker, a cue's draft FX, a new FX palette) too.
- **Width one head (D8):** Wave's `widthUnit` (`ratio` · `head`), shown for Square and Pulse; `head`
  reads `context.numDistinctSlots`. Its preset *Chase* (Square, `head`) joins Wave's.
- **The client's copy (P2):** `lib/spreadShape.ts` takes the unit and a step count, and the shared
  vector `src/test/resources/fx/spreadShape.fixture.json` gains `STEP` cases (after Segments, Wings
  and Parts, and above `MAX_SPREAD`) that both sides read.
- **Sync:** `formatVersion` and `minReader` 43; `RichProjectFixture` stores a `STEP` FX.
- **Tests:** `SpreadShapeTest` and `spreadShape.test.ts` on the vector; `FxSamplerTest`'s four heads
  and three colours at `STEP` 1 changing colour together on each boundary (the case in §1), and at 5
  heads after a fixture joins; the doors writing `STEP`; refusal on a Wave; one head on at a time at
  `head` over 3, 4 and 8 heads.
- **Docs:** `docs/fx-engineering.md` §"The spread's shape", `docs/sync-engineering.md` (43),
  `docs/glossary.md` (a row for the unit).

### Session 5 — The inspector in four questions

- **Timing (D9):** one strip of chips at the top of both layouts: `CycleChip` (the eight steps, the
  typed field and *Same cycle as…* in its popover), `TimingChip`, Direction's segment, `RunsChip`
  (×1 to ×4 and a count; `runForever` as today). The graph's Cycle tab is unchanged.
- **The FX's section (D9):** named by the FX's family (*Levels*, *Colours*, *Path*, *Steps*,
  *Flicker*), *Parameters* for a script FX; its header's FX name is the type switcher (the picker's
  swap, id and phase kept). Shape `choice`s draw small pictures from a client table keyed by option
  (`fxShapeIcons.ts`). The old *Shape* disclosure goes; `detail` parameters fold under *More*.
- **Mix and Advanced (D9):** Amount moves beside the family's question; the raw blend joins
  Advanced in both layouts; *Timing: Per cycle · Per head* reads *One cycle for: All heads · Each
  head* (glossary row D22 updated, the wire values unchanged).
- **Presets in typed cells:** every segment of presets ends in a typed value (Width's percentage,
  Strobe's *On for*), lit when the value matches no preset.
- **Tests:** `FxFields.test.tsx` for the order of sections in both layouts, the chips' popovers and
  their writes, the section names, the swap from the header; `FxEditor.test.tsx`'s live writes
  unchanged; `PaletteEditor.test.tsx` and the cue FX tab still saving every field.
- **Docs:** `frontend/CLAUDE.md` §"The fixture sheet" (the live editor bullet), the FX editor's
  inspector bullets; `docs/glossary.md`.

### Session 6 — Across the heads

- **The section (D10):** Over, the head ladder (the preview's `ladder` in the inspector, `spreadOffsets`
  ranks elsewhere, numbered by start), the patterns (`FX_SHAPES`, drawn on `scope.headCount` heads up
  to 16, else eight; the Order select under *Custom*), *Each head later by* with its readout, and
  Segments · Parts · Wings as segments with typed counts. The Spread disclosure, the phase dials and
  the separate offset ladder go.
- **The words (P2):** a pure `groupingSentence(order, shape, headCount)` and `laterByReadout(…)`
  (`F/components/fx/acrossHeads.ts`) build the example line and the readout from `lib/spreadShape.ts`.
- **The graph:** the Spread knob's hover and drag read the unit (§4).
- **Tests:** `acrossHeads.test.ts` (the sentence for each count, the readout in each unit, the
  case in §1 reading *⅓ cycle · 1 step*), `FxFields.test.tsx` (the three modes writing `CYCLE` 1,
  `STEP` 1 and a free value; *1 step* absent on a Wave; Wings hidden under Centre out),
  `FxGraph.test.tsx` (the knob's words under `STEP`).
- **Docs:** `frontend/CLAUDE.md`, the Spread section's bullet rewritten; `docs/glossary.md` (*Each
  head later by* on screen for the Spread row).

## 6. Migration

Sessions 2 and 3 rewrite stored FX through two one-shot data steps (`user_version` 7 and 8), each
backing the database up first, and map older archives on import; session 4 adds a nullable column
read as `CYCLE`. Sync moves 40 → 41 → 42 → 43, each with `minReader`. A stored FX no mapping
reproduces exactly stays legacy (D3).

## 7. Explicitly out of scope

- Merging the Flicker family, Colour Strobe, or Palette Steps and Slot Steps into fewer types.
- Changing `MAX_SPREAD`, the Orders, or what Segments, Wings and Parts compute.
- New waveforms or path shapes beyond those the old built-ins drew.
- Renaming the stored `segments`, `parts` and `wings` fields, or the wire's `timing` values.
- A per-step width or transition (grandMA3's phaser steps).

## 8. Follow-ups

Sessions 2 and 4 change how FX expand and run, so each reads `followups.md`'s index for a gate on FX
tick loops before it starts, per CLAUDE.md. None adds a WS command. Session 1 closes
`FU-FE-FX-PARAM-RANGE`.

## 9. Verification

- **Session 1:** the editor's fields read the same as before on every built-in, with ranges and units
  that match the rig's clamps.
- **Session 2:** a show with every old intensity and beam FX in cues, Looks and palettes runs
  unchanged on the rig after the upgrade; a show exported before it imports and runs unchanged; the
  picker's *Zoom Wave* lands on the Beam master.
- **Session 3:** the same for the colour and position FX; a Pan Sweep and a Figure 8 move the movers
  as before.
- **Session 4:** four Shehds, Colour Cycle with three colours from the picker: each fixture one
  colour behind; add a fifth fixture to the selection and the chase stays aligned. Width one head on
  eight dimmers lights one at a time.
- **Session 5:** the inspector fits the FX editor's three bands without the Cycle grid's two rows;
  every setting is still reachable.
- **Session 6:** Chris sets the §1 chase and a mirrored pairs chase without being told the numbers.
