# Wheel colour chases: colour FX on colour-wheel heads, and split colours

> **Document status: PLANNED, 2026-11-23.** Written from the design page Chris and Claude went
> through that day, with his answers to its questions.
> Three sessions, each one PR, each stacked on the one before while that one is unmerged, each run by
> `/next-session` (§5). The page and the record are in
> [`wheel-colour-chase-design/INDEX.md`](wheel-colour-chase-design/INDEX.md).
>
> This document is the engineering half. Where it and the page disagree, this plan wins on
> behaviour and the page on layout and copy.

## 1. Context

Chris's brief, 2026-11-23: a colour chase on the fixtures with gobos (the Show-Sound LEDBEAMRING
was his example), taking into account split colours on fixtures that support them. Those spots and
beams all make their colour with a **colour wheel**. A **split** is a wheel parked half-way between
two adjacent slots, so the beam is half one colour and half the other.

What the code says, read at `8e197fe`. `F/` is `frontend/src`, `K/` is
`src/main/kotlin/uk/me/cormack/lighting7`, `R/` is `src/main/resources`.

- **Colour FX skip wheel heads.** `ColourTarget` (`K/fx/FxTarget.kt`) takes a head only if it is
  `WithColour` (`fixtureHasAttribute`), so a wheel head in the selection is silently left out.
  `FU-FX-STEPS-COLOUR-WHEEL` records this for Palette Steps; this plan closes it.
- **Slot Steps** (`SlotStepList`, `K/fx/StepList.kt`) is the only wheel chase. It goes by slot name
  and is filed under Beam. Names differ between models, so one list cannot chase a mixed rig, and
  it cannot include an RGB head.
- **The snap exists.** `PaletteResolver.resolveColour` tries RGB, then a white mix, then a wheel,
  where `nearestColourSlot` picks the slot nearest by ΔE76 against each slot's `colourPreview` or the
  unit's fitted media (`Fixtures.fittedMediaFor`). A colour palette pressed on a wheel head, a fan and
  *Apply as values* all land through it.
- **Colour Cycle blends inside its script** (`R/fx/colour/ColourCycle.fx.kts`) and returns one
  `FxOutput.Colour`. Its Fade rule is `stepsAt`'s, which returns `FxOutput.Steps(from, to, blend,
  steps)`. `ColourTarget` already applies `Steps` per head for Palette Steps, through
  `StepList.colourFor`. `SettingTarget` snaps a `Steps` to the nearer step.
- **Splits are not modelled.** The MAC 250's colour wheel documents split positions at 1–144 and does
  not list them. The Robe ColorSpot 575's wheels scroll between adjacent colours over 0–127. The
  Stage view (`F/lib/colourBands.ts`) reads a level as the option whose band holds it, so it draws the
  MAC 250 at 70 as open.
- **Travel** is declared per type (`Travel.colourMs`, the time to cross the colour wheel's range:
  MAC 250 700, Robe 800, Beam Ring 600) and read only by the Stage view (`F/lib/travel.ts`).
- **Storage:** an FX's parameters are a string map, and Colour Cycle's `between` is a `choice`
  parameter. Sync is unaffected by anything below (P1).

## 2. Decisions

| # | Decision |
|---|---|
| D1 | **A colour chase reaches wheel heads.** A colour FX that declares `stepsFrom` (Colour Cycle, Palette Steps) counts a head whose colour is a colour wheel as a member of its colour target, and lands each step on that head's nearest slot through `nearestColourSlot` and the unit's fitted media. A head with a mixed colour stays on its mix, as `PaletteResolver` orders it. Every member counts toward Order, Spread and *Each head later by*. A colour FX that does not step (Rainbow Cycle, Colour Flicker, Colour Strobe, a script FX) leaves a wheel head alone, as today. |
| D2 | **Colour Cycle returns steps.** It returns `FxOutput.Steps` over a `ColourStepList` of its literal colours. Cut, Fade and Smooth are `stepsAt`'s crossing, Smooth with its cosine ease. RGB output is unchanged to the DMX step, proved against today's script before it lands. |
| D3 | **A wheel changes at the middle of the crossing.** Under Cut a wheel head changes at the step's edge; under Fade and Smooth, when the crossing is half done, so the rig changes on one beat. A wheel never rolls through the slots between two colours. Under Amount a wheel holds below half weight, as every setting does (`weighDiscrete`). |
| D4 | **`between` gains Split.** Each colour gives way to the next through their split, held for `fade` of the step (default 0.5: colour, split, colour, split). A wheel that can split (D5) shows the split of its two slots where they are neighbours; any other wheel, or two slots that are not neighbours, changes at the middle of the split. An **RGB head holds the half-way mix** for the split, so every head changes at the same moments. |
| D5 | **A wheel declares where it splits.** `DmxFixtureColourSettingValue` gains `continuousLevel: UByte?`, the level in the wheel's continuous band at which that slot is full in the beam. Two slots split only where both declare one and they are next to each other in that order; their split is the midpoint of the two levels. Nothing is guessed from names. **The MAC 250 and the Robe ColorSpot 575 declare them. The LEDBEAMRING, the Fusion 100 Spot and the Varytec XL60 do not split**, and declare none; nor does the Revolution's scroller. |
| D6 | **Slot Steps on a colour wheel takes Split too:** `between` (`CUT \| SPLIT`) and `fade`, shown only on a colour wheel that declares splits. |
| D7 | **The picker starts a wheel chase from the wheel.** On a selection whose colour heads are wheels of one model, Colour Cycle starts on that wheel's colours in wheel order (each slot's preview, fitted media read, open and no-colour bands left out), on Split where the wheel splits and Cut where it does not. A preset, *Split Chase*: Split at fade 0.5. |
| D8 | **The preview says what each head will do.** `POST /fx/preview` answers a wheel head's colour lane as its slot, or slot and split, per sample, and per head the notes: two colours on one slot, a split that cannot happen, and a step shorter than the wheel's travel for that move. The editor draws them. |
| D9 | **The Stage view and the tables draw a split.** The setting option DTO carries `continuousLevel`. The 2D leaf draws a split as a half-and-half disc; 3D draws the beam as the two slots' mix by share and its pool in two halves. The fixtures table reads a split level as *Red / Yellow*. |
| D10 | **Rainbow Cycle on a wheel is a follow-up**, `FU-FX-RAINBOW-WHEEL-SCROLL`: a non-stepping colour FX running a wheel's own scroll band. Not in this plan. |
| D11 | **The fork sheet says a forked Colour Cycle needs this desk or later.** A fork of the new script calls `ColourStepList` and `stepsAt`'s new arguments, which an older desk's compiler lacks. Forking is otherwise unchanged. |

The plan adds three of its own:

- **P1 — No stored change.** `SPLIT` is a new value of an existing `choice`. An older desk reads a
  stored `SPLIT` through `params.choice`, which answers an unknown value as the default (Fade, fx-settings
  plan session 1's rule), so it runs as Fade. Membership and `continuousLevel` are runtime and
  fixture code. No table, migration, `formatVersion` or sync DTO moves; session 1 confirms the
  importer passes an unknown choice through.
- **P2 — The desk owns the rule.** Snap, split level, adjacency and the travel check live in Kotlin.
  The client draws what the preview and the option DTO say. A shared vector,
  `src/test/resources/fixture/colourSplit.fixture.json`, pins a level's reading as `(slotA, slotB,
  share)` on both sides.
- **P3 — Equivalence before the switch.** Colour Cycle moves onto steps only once a test runs
  today's script (kept as a test resource) and the new one over a grid of phases, `between`, `fade`,
  list lengths 0 to 5, directions and spreads, and finds the same output to the DMX step.

## 3. The model

### What each head writes (D3, D4)

| `between` | RGB head | Wheel that splits, neighbours | Any other wheel |
|---|---|---|---|
| Cut | A, then B at the edge | A, then B at the edge | same |
| Fade · Smooth | A, crossing to B | A, then B at the crossing's middle | same |
| Split | A, the half-way mix held, B | A, the A/B split held, B | A, then B at the split's middle |

### Steps carry a split

`FxOutput.Steps` gains `split: Double?`: null for an ordinary crossing, else how far through the held
split the phase is, 0 to 1. During a split `blend` is 0.5, so `ColourTarget`'s existing blend holds
the half-way mix on an RGB head with no new code. `snapped` reads `split` where it is set (`split <
0.5` is `from`), so a wheel that cannot split changes at the split's middle through the snap the
setting target already does.

```kotlin
// StepList.kt
fun stepsAt(phase: Double, steps: StepList, fadeRatio: Double = 0.0,
            ease: StepEase = StepEase.LINEAR, split: Boolean = false): FxOutput.Steps
class ColourStepList(val colours: List<ExtendedColour>) : StepList
```

A Colour Cycle with no colours still writes black, through a one-step list of black: an empty
`StepList` would hold what is underneath, which is not today's output.

### One target, a landing per head (D1)

```kotlin
// FxTarget.kt
sealed interface ColourLanding {
    data class Mix(val colour: Colour) : ColourLanding
    data class Wheel(val setting: DmxFixtureSetting<*>, val attribute: String) : ColourLanding
}
fun ColourTarget.landingOn(fixture: GroupableFixture): ColourLanding?
```

`ColourTarget` gains `wheels: Boolean`, set by `FxTargetFactory` from the registration's `stepsFrom`
(`FxSpawner` already reads it). With it on, `fixtureHasAttribute` is `landingOn(...) != null`. A wheel
head's chase writes **`colourWheel`**, not `rgbColour`, so every reader of "does this FX paint
`(head, attribute)`" asks `landingOn` for the attribute it lands on: `FxSuppression` (a programmer
value on the wheel holds the chase back on that head), `BlindHold`, the key stack
(`programmer.keyStack` lists it under `colourWheel`), provenance, `composeProgrammerOver`,
`isAttributeFullyParked`, `currentValue`, `resetToFallback` and Locate's re-assert. On a wheel landing
each of these does what `SettingTarget` does.

### A step on a wheel (D1, D5)

`StepList.wheelSlotFor(index, fixture, setting, media): WheelSlot?` answers the step's slot on that
head. `ColourStepList` snaps each colour through `nearestColourSlot`; `PaletteStepList` reads the
palette's resolution, already a `Setting` on a wheel head. Both cache per `(model class,
fitted-media version)`, as `SlotStepList` caches per option set. Fitted media reaches the list
through `FxContext`, from `Fixtures.fittedMediaFor` at expansion, so the tick reads no database.

The split level of slots A and B is `(A.continuousLevel + B.continuousLevel) / 2`, rounded down, where
both declare one and no declared slot lies between them. `ColourSplitTest` (the `ColourPreviewTest`
pattern) walks every COLOUR setting: declared levels rise in the options' declared order and lie in a
band no indexed option starts in.

## 4. UX

On the page (`wheel-colour-chase-design/wheel-colour-chase.html`, §"In the editor"):

- The Colours section of Colour Cycle: the swatches, and on a one-model wheel selection *Start from
  the {model}'s wheel*, which replaces the list (D7).
- *Between*: Cut · Fade · Smooth · Split. Split's slider is labelled *Split for*, Fade's *Fade*, the
  same `fade` parameter. The help line: *Each colour gives way to the next through their split, on
  wheels that have one.*
- *Lands on*, under the section: each model in the selection, its slots and splits in step order,
  and the preview's notes in amber (D8). A selection of RGB heads only shows nothing new.
- Slot Steps on a colour wheel that splits shows *Between* as Cut · Split (D6).

## 5. Implementation — three sessions

Each session is one PR. Each is run by `/next-session`, which cuts the branch, stacks it on the
previous session's branch while that PR is open (on `main` once merged), records the previous
session's PR and head SHA in the table below, and hands the session to `/verified-ship`. A session
ends with its checks green (§"Pre-commit checks" in the root `CLAUDE.md`), the docs it names written,
and its row marked *in review* with its branch. A session's PR is titled *Wheel colour chases:
session N — title*. Never merge a later session's PR before an earlier one.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | Wheel heads in colour chases | in review | `claude/fervent-cannon-jfon7w` | [#453](https://github.com/cjcormack/lighting7/pull/453) | 6139efe |
| 2 | Split colours | in review | `claude/friendly-ramanujan-04vy7d` | | |
| 3 | The editor and the Stage view | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — Wheel heads in colour chases

- **Colour Cycle on steps (D2, P3):** `ColourStepList`, `stepsAt`'s `ease`, Colour Cycle's script on
  them, `split` on `FxOutput.Steps` (unread until session 2). Today's script kept under
  `src/test/resources/fx/legacy/`, and `ColourCycleStepsEquivalenceTest` over P3's grid. The preview
  and `FxSampler` read Colour Cycle's lanes as they read Palette Steps'.
- **Wheel heads as members (D1, D3):** `ColourTarget.wheels`, `landingOn`, `wheelSlotFor` with
  fitted media through `FxContext`, and every reader in §3 asking `landingOn`. Palette Steps on colour
  reaches wheel heads.
- **Split as a choice:** `between` gains `SPLIT` in the frontmatter, run as session 2 will run it on
  RGB heads (the held mix) and on every wheel as the middle change, so a stored Split is honest from
  the start.
- **Fork (D11):** a note in `EditFxDefinitionSheet` (`F/components/fxLibrary/`) when `forkedFrom` is
  Colour Cycle, beside the step-timing note it already shows on a fork.
- **Tests:** a chase over a par and a Beam Ring (the Beam Ring snapping, both on one beat); a
  programmer value on the Beam Ring's `colourWheel` holding the chase back on it alone, in the key
  stack under `colourWheel`; Blind; a parked wheel channel; Palette Steps on a MAC 250 with fitted
  media; a head with RGB and a colour preset staying on RGB; Rainbow Cycle leaving a wheel alone; an
  unknown `between` on import (P1).
- **Docs:** `docs/fx-engineering.md` §"Colour FX" and §"Step FX"; close `FU-FX-STEPS-COLOUR-WHEEL` in
  `docs/plans/followups.md`; add `FU-FX-RAINBOW-WHEEL-SCROLL` (D10).
- **Checks:** the full `./gradlew test` locally, since every Colour Cycle's output and the readers of
  FX suppression across `fx`, `state` and `plugins` move; `npm run check:quick` in `frontend/` for the
  fork note.
- **Where the code differed from this plan** (corrected in session 1):
  - `ColourTarget.wheels` is set by `FxInstance`, from the FX's `stepCount`, not by `FxTargetFactory`:
    every spawn path meets in `FxInstance`, and several build a `ColourTarget` without the factory.
  - Fitted media reaches the step list from the engine at apply
    (`FixturesWithTransaction.fittedMediaFor`, an in-memory map), not through `FxContext`: the
    context is per distribution slot and shared across heads, and the Fx calculation never sees the
    slot a step lands on — the target does.
  - A wheel landing is the wheel's `SettingTarget` with `colourWheel` set (`ColourTarget.on`), so the
    readers that already handle a setting (the fallback, park, compose, the key stack) take it as one.
    The readers keyed by `(head, attribute)` ask `FxEngine.attributeAt`.
  - Locate's re-assert needed no change: it writes programmer values, which suppression reads by the
    landed attribute.
  - A stored `fade` above 1 is storable (no range check), and today's script ran it starting each
    crossing part-way in. `stepsAt` still clamps the ratio to 1 by default; Colour Cycle's Fade passes
    `clampFade = false` to keep that.
  - A fork of Colour Cycle does not reach wheel heads: a project FX definition registers with no
    `stepsFrom` (`routes/fxDefinitions.kt`), so its `stepCount` is null and `wheels` stays off. The
    fork note says only that it needs this desk or later.

### Session 2 — Split colours

- **Split levels (D5):** `continuousLevel` on `DmxFixtureColourSettingValue` and `ColourSplitTest`.
  The MAC 250's colour wheel and the Robe's wheel 1 and wheel 2 declare levels, each from its manual's
  table where it is to hand, else spread evenly over the documented band in wheel order, marked as an
  estimate and checked by `FU-MANUAL-WHEEL-SPLITS`. The Beam Ring's, Fusion's and Varytec's KDoc says
  their wheels do not split.
- **The split write (D4, D6):** `ColourTarget`'s wheel landing writes the split level where both
  slots split and are neighbours; `SettingTarget` the same for Slot Steps on a colour wheel, which
  gains `between` and `fade` with `shownWhen`.
- **The preview (D8):** a wheel head's colour lane `{slot, splitWith?, preview}` per sample, and per
  head `notes`: `SAME_SLOT {colours, slot}`, `NO_SPLIT {from, to, reason: WHEEL | NOT_NEIGHBOURS}`,
  `TOO_FAST {needMs, stepMs}`, the need read from `colourMs` by the share of the wheel's range the move
  crosses, as `F/lib/travel.ts` reads it.
- **The DTO (D9):** the setting option DTO (`routes/lightFixtures.kt`, `DmxFixture.kt`) carries
  `continuousLevel`; MCP's `list_fixture_types` names a wheel that splits.
- **The vector (P2):** `colourSplit.fixture.json`, generated from the MAC 250 and the Robe, read by a
  Kotlin test now and by Vitest in session 3.
- **Tests:** a MAC 250 Split Chase in wheel order writing every split; two slots apart changing at the
  middle; a Beam Ring under Split changing at the middle; the RGB mix held; Slot Steps' Split; each
  note.
- **Docs:** `docs/fixtures-engineering.md` §"Beam vocabulary" (`continuousLevel`, and how to declare
  it on a new fixture); `docs/fx-engineering.md` §"Step FX"; the `FU-MANUAL-WHEEL-SPLITS` row in
  `docs/plans/manual-validation.md`.
- **Checks:** `./gradlew compileTestKotlin`, then `./gradlew test --tests` for `fx.*`, `fixture.*`,
  `routes.*` and `ai.*`; the full suite is CI's. No frontend change.
- **Where the code differed from this plan** (corrected in session 2):
  - The levels: the MAC 250's fourteen slots (open to white) at 1, 12, … 144 and the Robe's ten and
    nine at 0 to 127, each spread evenly in wheel order: neither manual tables the band. The Robe's
    open slot is at 0 on both scales, so `ColourSplitTest`'s band rule is that no indexed option
    *other than the one whose band holds the first declared level* starts inside the declared range.
  - The wheel lane is on the preview's attribute row as `wheel: {states, samples}`: each distinct
    `{slot, splitWith?, preview}` once, and per sample an index into them, as a step FX's labels are
    carried, rather than an object per sample. The notes are on each FX's output on that row
    (`FxPreviewOutput.notes`), since a row can hold several FX.
  - `TOO_FAST`'s share of the wheel is of its continuous band for a move made through a split (the
    chase on Split, the two slots splitting), since that move is written on the band; any other move,
    Cut and Fade's included, is the indexed levels' distance of 255, as `F/lib/travel.ts` reads it. It
    names the chase's longest move on that head, once.
  - The notes are read from the FX, not the window's samples: its step list and whether it splits from
    evaluating it at the start and at the end of its first step, so a split shorter than a sample's gap
    and a paused FX still get them.
  - Outside the continuous band the wheel lane reads a level's slot by `DmxFixtureSetting.valueForLevel`,
    the desk's reading, not as band starts.
  - Slot Steps' `between` and `fade` are offered on every setting: no frontmatter can say "a colour wheel
    that splits", so showing *Between* only there is the editor's, in session 3. On a setting that does
    not split, Split changes at the split's middle.
  - MCP's `list_fixture_types` names a type's `splitWheels` on every type, not only with `attributes`.
  - The MCP tests are in `mcp.*`, not `ai.*`; both were run.
  - The vector carries each wheel's options, its splits and the reading of every level 0–255, made by
    an independent port of the rule rather than by the Kotlin model.

### Session 3 — The editor and the Stage view

- **The editor (D7, §4):** Split in *Between* with its label and help, *Start from the wheel*, the
  *Split Chase* preset, *Lands on* from the preview's lanes and notes, Slot Steps' *Between* on a
  colour wheel that splits.
- **The Stage view (D9):** `colourBands.ts` reads a level in a continuous band as two slots and a
  share, through the shared vector; the 2D leaf and 3D `ColourSync` draw it. `SettingCell` and the
  fixture sheet read a split level as *A / B*.
- **Tests:** Vitest over the vector; `FxFields.test.tsx` (Split, its label, *Start from the wheel* on
  a one-model selection and not on two models); the Stage view's split in 2D and 3D.
- **Docs:** `frontend/docs/stage-vis-engineering.md` on drawing a split; the FX editor's section of
  `docs/fx-engineering.md` §"Previewing an FX".
- **Checks:** `npm run check:quick` in `frontend/`; the full `npm run check` is CI's. No backend
  change.

## 6. Migration

None. P1: no stored shape changes, and a stored `SPLIT` reads as Fade on an older desk.

## 7. Explicitly out of scope

- A wheel rolling through the slots between two colours (Q2 on the page: declined).
- Rainbow Cycle or any non-stepping colour FX on a wheel (D10).
- The Robe's second wheel as part of a chase; it stays a filter under the chase
  (`FU-TMPL-SECOND-COLOUR-WHEEL`).
- A Look, palette or programmer value holding a split; only an FX writes one.
- Splits on a gel scroller.

## 8. Follow-ups

Session 1 changes what the FX tick writes, so it reads `followups.md`'s index for a gate on FX tick
loops before it starts, per CLAUDE.md. None adds a WS command. Session 1 closes
`FU-FX-STEPS-COLOUR-WHEEL` and adds `FU-FX-RAINBOW-WHEEL-SCROLL`; session 2 adds
`FU-MANUAL-WHEEL-SPLITS`.

## 9. Verification

- **Session 1:** every show's Colour Cycle FX looks the same on the RGB heads after the upgrade. A
  Colour Cycle over the pars and the Beam Rings changes them on one beat, each Beam Ring on its
  nearest slot.
- **Session 2:** a MAC 250 (or the Robe) on a Split Chase in wheel order shows each half-and-half
  between its slots, and the estimated levels land on the wheel's real splits
  (`FU-MANUAL-WHEEL-SPLITS`).
- **Session 3:** Chris builds a split chase on the MAC 250s from the picker without typing a colour,
  and the Stage view's split matches the beam.
