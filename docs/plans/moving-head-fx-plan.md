# Moving-head FX: the MCP FX fixes, fine position, aiming from an FX, and where an FX starts

> **Document status: PLANNED, 2026-11-28.** Written from Claude's report after building an XMAS
> letters effect for the Sleeping Beauty show (four Show-Sound LEDBEAMRINGs on LX3, colour-wheel
> heads, an 8-bit Path), which Chris asked to be planned. There was no design page; the brief and the
> choices this plan made without asking him are in
> [`moving-head-fx-design/INDEX.md`](moving-head-fx-design/INDEX.md).
> Eight sessions, each one PR, each stacked on the one before while that one is unmerged, each run by
> `/next-session` (§5).

## 1. Context

The brief: fix two MCP bugs (a colour FX stored where no head runs it; an FX no target can take
saved silently), then make smooth, consistent beam movement possible: fine pan/tilt end to end, a
choice of aim solution, aiming from inside an FX, a head's identity in an FX, where an FX starts on
a press, per-fixture palettes in Palette Steps, a way to read a cue over MCP, and four smaller items.

What the code says, read at `57f25f0`. `F/` is `frontend/src`, `K/` is
`src/main/kotlin/uk/me/cormack/lighting7`, `R/` is `src/main/resources`.

- **The colour family infers `colour`, gated on `WithColour`.** An MCP FX with no `attribute` is
  stored with none (`AiTools.parseLookFx`, `parseAdHocFxFromJson`). At spawn
  `FxSpawner.resolveFxAttributeForCue` answers `"colour"` for a group only where
  `"colour" in group.detectCapabilities()`, which lists a head only if it is `WithColour`
  (`FixtureTypeRegistry.detectCapabilities`). A colour-wheel head is not, so the spawn returns
  null and the FX never runs. The fixture form (`resolveFxAttributeForFixtureInCue`) answers
  `"colour"` unconditionally, which `FxTargetFactory.resolve` reads as a `ColourTarget`. The schema
  text says "colour→colour" (`AiToolSchemas.kt:216`). The wheel-colour-chase plan made a stepping
  colour FX on `rgbColour` land on a wheel (`ColourTarget.wheels`, set by `FxInstance` from the
  FX's step count), so `rgbColour` is the attribute that works.
- **Values are checked against their targets, FX are not.** `LookValues.kt:120` and `:433` refuse
  "no head of X can take …". No tool checks an FX's attribute. The only write-time FX checks are
  `FxSpecCoercion.Strict` (enum values) and `fxWriteProblem` (parameters). Palette Steps is checked
  only inside a palette (`validatePaletteSteps`, `routes/projectPalettes.kt:1357`). Elsewhere a bad
  one drops at spawn.
- **`record_cue` counts layers as FX.** `appendFxChildren` (`routes/programmerRecord.kt:338`)
  upserts the recording's layers, then its ad-hoc FX, into one `count`, reported as `fxWritten`.
  `includeFx` filters ad-hoc FX only (`collectProgrammerRecording`, `routes/programmerCapture.kt:465`);
  layers are always recorded. Its description says "Record the running FX as well as the static
  values".
- **FX position is 8-bit.** `FxOutput.Position(pan: UByte, tilt: UByte)` (`fx/Fx.kt:309`).
  `PositionTarget` writes `pan` and `tilt` only (`FxTarget.kt:764`), so a fine byte left by an aim
  stays under a moving FX. Path (`R/fx/position/Path.fx.kts`) is the one built-in, its parameters all
  `ubyte`. Ten types carry `PAN_FINE`/`TILT_FINE` sliders, found by category
  (`programmerAim.kt:532`, `LocateValueResolver.axesOf`); the Beam Ring's are ch 2 and 4. The 16-bit
  split is `travelToDmx` / `dmxToTravel` (`show/AxisTravel.kt`). The Stage view reads the fine
  channel (`F/components/stage3d/FixtureModel.tsx:810`).
- **Stored positions.** A Look's or cue's `position` row is `"pan,tilt"` in coarse DMX, parsed by
  `toUByteParam` (`toIntOrNull`), so a decimal fails to parse. Aim already records fine as separate
  `panFine`/`tiltFine` rows (`programmerAim.kt:213`, `:277`), and a crossfade lerps them as two
  unrelated sliders (`CueAssignmentResolver.interpolate`). A palette's `pos:` is centre-relative coarse
  DMX: `parseAxes` reads doubles and `centreStep` rounds them (`fx/PaletteIntent.kt:175`, `:243`), so
  a decimal already parses and rounds. `PaletteResolver` writes coarse only (`:468`).
- **The solver already chooses.** `aimAt` (`show/FixtureAim.kt:74`) tries both families (tilt ±t,
  pan p / p±180°) at pan ±2 turns, keeps those inside travel and takes the one nearest `near`
  (|Δpan|+|Δtilt|), else nearest each axis's zero. Jog and the fan pass `near`; `aim_fixtures`
  (`AiTools.kt:1065`, through `aimIntoProgrammer`) does not, so neighbouring heads each take the one
  nearest zero. Its dryRun answers degrees to 0.1 and the coarse `position`. `saveAsPalette` writes a
  coarse `pos:` per head.
- **An FX cannot tell heads apart.** `FxContext` (`fx/Fx.kt:25`) has `groupSize`, `memberIndex`,
  `distributionOffset`, `hasDistributionSpread`, `numDistinctSlots`, `trianglePhase`. Contexts are
  built per member in `DistributionPlan.of`; every element of one fixture shares one under
  `processWholeUnits`; under PER_FIXTURE `memberIndex` restarts in each fixture. `calculateFxOutput`
  has the element key and does not pass it on. No test pins the context's fields.
- **A beat FX runs on its master's grid.** `FxPhase.elapsed()` counts a forever FX from the master's
  tick 0 and an FX with runs from its anchor (`runsStartTick`, set on its first pass). So a Look
  pressed mid-bar starts mid-cycle; a `runs: 1` move starts at phase 0 but its members' spread offsets
  are counted from the same anchor. A wall-clock or Own-tempo FX starts at 0 on spawn. A bar is
  `CycleLength.ONE_BAR`, four beats; a master has no time signature. `startedAtBeat`
  (`FxInstance.kt:943`) is declared and never set. A press spawns only new layers' FX
  (`ProgrammerLayerStack.syncFx`); a running one keeps its phase.
- **Palette Steps takes generic palettes only.** `PaletteSource.paletteStep`
  (`fx/PaletteColourSource.kt:149`) drops a palette with any non-deferred row, logged "not a generic
  value palette"; `validatePaletteSteps` refuses it in a palette FX. `PaletteStep` holds
  `(attribute, intent)` with no targets (`StepList.kt:228`). `PaletteReferences.resolveOn` already
  answers "this head's bound row, else the deferred one" for a press.
- **No tool reads a cue.** The tools are create, apply, stop, record, update-from-programmer, copy,
  delete, move and `set_cue_events`. REST has `GET …/cues/{cueId}` (`routes/projectCues.kt:163`) and
  `…/cooked` (`cueCooked.kt`).
- **No tool reads or sets a busk pad.** Pads are `busk_pads` under pages, columns and banks
  (`models/buskLayout.kt:160`), each naming exactly one palette, Look, cue or stack.
  `appendBuskPad` (`routes/projectBusk.kt:479`) has two callers, the route and the hand;
  `FU-HAND-PLACE-SHAPE`'s trigger is a third.
- **Speed channels carry no direction.** Pan/tilt speed sliders are plain SPEED sliders; direction is
  label text where it is anything ("0 fastest → 255 slowest" on the Varytec; the Beam Ring's says
  "Pan/tilt speed"). `docs/fixtures-engineering.md` §"Travel and timing channels" says why none is
  modelled; `FU-STAGE-VECTOR-SPEED` holds the curve.
- **The preview knows no pan/tilt travel.** `Travel` declares `panDegPerS`/`tiltDegPerS`, which only
  the Stage view reads. `WheelNotes` raises `TOO_FAST` for a colour wheel from `colourMs`
  (`fx/WheelNotes.kt`), on `POST /fx/preview` only: MCP `preview_fx` (`ai/FxScriptTools.kt:500`)
  passes no notes and prints position samples as coarse `"pan,tilt"` (`FxSampler.kt:745`).
- **`render_view` from the programmer.** Lens and beam read one channel source
  (`FixtureModel.tsx:2395`, `:1722`), and under `programmer` a channel the programmer does not hold
  reads 0 (`F/api/channelSource.ts:174`), pan and tilt included. Read, not reproduced: the likeliest
  cause is a programmer holding intensity and colour but no position for the Beam Rings, drawn at
  DMX 0 into a collider or out of frame; next, heads downstage of the proscenium whose haze the
  fixed *Haze: Stage* clips (`F/scene/stageSurfaces.ts:135`); next, Blind's staged scenery flying the
  cloth in (`F/lib/scenery.ts:487`).

## 2. Decisions

| # | Decision |
|---|---|
| D1 | **The colour family means `rgbColour`.** Both inferences in `FxSpawner` answer `rgbColour` for `colour`, and the group form admits a group where its colour target lands on a member (D2's rule), so a stepping colour FX reaches wheel heads as it does when `rgbColour` is named. A stored `colour` keeps resolving to a `ColourTarget`. The schema text says colour→rgbColour. |
| D2 | **An FX is checked against its targets by the engine's own membership.** One function, `fxLanding(state, targets, spec)`, builds the target the spawn would (`FxSpawner.resolveTargetForCue`, with `wheels` as `FxInstance` sets it) and asks `fixtureHasAttribute` of every head the expansion reaches. It answers the attribute that will run, the heads it lands on and each head skipped with why. No head: the write is refused naming the FX, its attribute and its targets. Some: the write goes ahead and the answer lists the skipped heads, as values do. |
| D3 | **Every MCP tool that stores or runs an FX calls D2:** `create_look`, `update_look`, `create_cue` (ad-hoc FX, and a layer whose palette holds an FX, against the layer's targets), `apply_fx`, and `create_palette` with `fx`, which has no targets and is checked against the patch (refused only when no fixture in the rig can take it). The MCP tools store the attribute D2 resolved, so what is stored is what was checked. REST is unchanged: the picker offers only FX a head can take. |
| D4 | **Layers are counted apart from FX.** `record_cue`, `update_from_programmer` and `record_look` answer `layersWritten` beside `fxWritten`, and `fxWritten` counts ad-hoc FX only. `includeFx: false` leaves the cue's Look and palette layers in, since a layer is a reference and holds the Look's values as well as its FX; the descriptions say so. |
| D5 | **An FX's position is exact.** `FxOutput.Position` holds pan and tilt as doubles in coarse-DMX units (0.0 to 255.996, 256 steps of 1/256 per coarse step, `dmxToTravel`'s scale). `Position(pan: UByte, tilt: UByte)` stays as a constructor and `pan`/`tilt` stay as the coarse bytes, so every script and reader written for 8-bit compiles and runs unchanged. `FxOutput.Position.exact(pan, tilt)` is the script's door. |
| D6 | **A position FX owns its head's fine channels.** `PositionTarget` writes `panFine`/`tiltFine` where the head has them, through `travelToDmx`; an 8-bit output writes fine 0. The blend runs on exact values, so ADDITIVE about 128 keeps the base's fine. Every reader keyed by `(head, attribute)` (suppression, Blind, park, the key stack, provenance, fallback) treats the fine sliders as part of `position`. Every built-in's coarse output is unchanged, proved before it lands (P3). |
| D7 | **Path moves in exact steps.** Its parameters stay `ubyte` (stored shows unchanged) and its maths runs in doubles to `Position.exact`, so a slow Path no longer steps. |
| D8 | **Stored positions carry fine their existing ways.** A Look's and a cue's fine is the `panFine`/`tiltFine` rows aim already records; a crossfade lerps a head's `position` and its fine rows as one 16-bit value per axis. A palette's `pos:` takes a fraction of a step to three decimal places (`pos:12.25,-3.5`; 0.001 is finer than a fine step's 1/256), rounded to the nearest fine step on resolve; `PaletteResolver` writes the fraction to fine where the head has it. An older desk parses a decimal `pos:` and rounds it (P1). The client's palette editor keeps the fraction it did not change. |
| D9 | **`aim_fixtures` chooses a solution on request.** It takes `near`: `"zero"` (today's, the default), `"current"` (the head's pan/tilt now), or `{panDeg, tiltDeg}`; and `tiltSide`: `positive`, `negative` or `either` (default), filtering before `near`. dryRun lists every reachable solution per head, `{panDeg, tiltDeg, position, panFine, tiltFine, chosen}`, degrees to 0.01. An aim writes exact fine, and `saveAsPalette` saves decimal `pos:`. `POST /programmer/aim` takes the same two fields; the client sends neither. |
| D10 | **An FX knows its head.** `FxContext` gains `head: FxHead?`: `fixtureKey`, `elementKey` (null for a whole unit), and `aimAt`. Null only in the compile-time dummy. Under `processWholeUnits` it is the unit's. The guide says to key per-head tables by `fixtureKey`, not `memberIndex`. |
| D11 | **An FX can aim.** `context.head?.aimAt(x, y, z, near = null)` answers `FxAim(pan, tilt)` in D5's exact units, or null where the point is out of reach, from the head's rigging, mounting and travel through `aimAt`. With no `near` it takes the solution nearest this head's previous answer in this FX run, else the head's pan/tilt now, so a path never flips mid-way. The head's placement is captured when the target expands, in memory, and refreshed when the rig changes; the tick reads no database. |
| D12 | **A built-in Stage Path.** `R/fx/position/StagePath.fx.kts`, family position: `points` (stage metres, `"x y z; x y z; …"`), `shape` `LINE` or `SPLINE` (Catmull-Rom through the points), `closed`, and Path's `curve` as the ease along the arc length. Each head aims at the same point through D11. A Stage Path whose points no head reaches paints nothing on that head and the preview says so. Its points are typed; drawing them on the stage is a follow-up (§8). |
| D13 | **An FX has a start.** A stored FX gains `start`: `FREE` (today's, the default), `RESTART` (phase 0 at the press), `NEXT_BEAT` or `NEXT_BAR` (four beats). `RESTART` anchors the FX's clock at its spawn tick; the next-beat forms anchor at that master's next boundary and hold phase 0 until it. Runs count from the anchor. A wall-clock FX, which has no beat, runs `NEXT_BEAT` and `NEXT_BAR` as `RESTART`. Only a spawn anchors: an FX carried across a GO, re-prioritised on a press or swapped for a speed change keeps its place. `RESTART` does not glide to the grid. |
| D14 | **The start is per FX, not per Look or pad.** A Look whose FX should start together sets each. A pad's own override is a follow-up (§8). |
| D15 | **Palette Steps takes per-fixture palettes.** A step's palette may hold fixture- and group-bound rows: each head takes its own row, else the palette's deferred row, else holds what is underneath for that step (it stays a member for Order and Spread). An FX palette in the list is still refused, and so is a palette with no rows. |
| D16 | **`get_cue(cueId)` answers a cue in `create_cue`'s shapes:** its layers (with targets, amount, blend, mask), values, ad-hoc FX, scenery, events, speed masters, move in dark, and its timing, plus the stacks and numbers it sits at. Feeding the answer to `create_cue` makes the same cue. |
| D17 | **Two busk-pad tools.** `get_busk_pads` lists pages, columns, banks and pads with what each presses; `set_busk_pads` adds a pad to a bank, replaces what a pad presses, or removes one, in one batch. The third caller of `appendBuskPad` takes `FU-HAND-PLACE-SHAPE`'s fix. |
| D18 | **A speed channel says which end is fast.** `@FixtureAttribute(speedFastest = …, speedSlowest = …)`: the DMX ends of the channel's speed band, so direction is implied and the levels outside it (tracking, blackout-while-moving) are not the band. Declared from each manual's channel table; a type whose manual says no direction declares none. `list_fixture_types` reports it. Nothing is drawn from it (`FU-STAGE-VECTOR-SPEED` stays). |
| D19 | **The preview flags a head that cannot keep up.** A position FX's preview reads each head's pan and tilt between samples in degrees (its axes' range) against `panDegPerS`/`tiltDegPerS`, and notes `POSITION_TOO_FAST {axis, needMs, stepMs}` for the head's worst shortfall, once. It reads the type's top speed, never a speed channel, so a flagged head is late at best. MCP `preview_fx` passes every note (the wheel's too) and its position samples carry fine. |
| D20 | **`render_view` says when it draws a head the source does not aim.** Reproduce first. If the cause is §1's first, the answer lists heads lit in the source with no pan/tilt held there ("drawn at DMX 0; `outputProgrammer` keeps the rig's aim") and the description says which source to use; the `programmer` source keeps reading 0. If it is the haze clip, `render_view` takes `haze`. If neither, record what was found and file it. |

The plan adds three of its own:

- **P1 — Only D13 changes what is stored.** D13's `start` is a column on the three FX tables, a
  field on `StoredFxFields`, and a writer and `minReader` bump to the next format version (57 at
  the time of writing): an older reader would run a `RESTART` FX as `FREE` and push it back. D8's
  decimal `pos:` is a value string an older desk already parses and rounds; D15 relaxes a refusal and
  an older desk drops a bound palette from the steps, as today. Session 3 and session 6 each prove
  their half with today's parser.
- **P2 — The desk owns the rule.** Membership, the aim solve, the start anchor and the travel check
  are Kotlin. The client draws what the wire carries; it gains no solver.
- **P3 — Equivalence before the switch.** Sessions 2 and 3 move what every position FX and every
  stored position writes. Each runs today's code (kept as a test resource or a frozen copy of the
  function) and the new code over a grid (every built-in's position output over phases, blends and
  spreads; every stored position form) and finds the same coarse bytes. Only the fine bytes may move.

## 3. The model

### Exact position (D5, D6)

```kotlin
// fx/Fx.kt
data class Position(val panExact: Double, val tiltExact: Double) : FxOutput {
    constructor(pan: UByte, tilt: UByte) : this(pan.toDouble(), tilt.toDouble())
    val pan: UByte get() = …   // the coarse byte travelToDmx writes
    val tilt: UByte get() = …
    companion object { fun exact(pan: Double, tilt: Double): Position }
}
```

`PositionTarget.applyValueToFixture` splits each axis through `travelToDmx` and writes the fine
slider found by category; `FxBlend.positionAxis` takes doubles. The sampler's position series carries
fine planes beside the coarse, and `POST /fx/preview` sends them; the FX editor's graph reads them
where present.

### The head in a context (D10, D11)

```kotlin
// fx/Fx.kt
class FxHead(val fixtureKey: String, val elementKey: String?, private val placement: AimPlacement?) {
    fun aimAt(x: Double, y: Double, z: Double, near: FxAim? = null): FxAim?
}
data class FxAim(val pan: Double, val tilt: Double)   // D5's units
```

`DistributionPlan.of` builds a context per member, so the head rides there; the plans are rebuilt when
the expansion changes, which a re-hang must also trigger. The previous answer D11 reads is kept per
(FX instance, head) by the engine, not in the shared context. The sampler and the MCP trial run build
heads from the rig too, so a preview of a Stage Path aims as the stage does; the trial run's hardcoded
`FxContext(4, …)` takes the selection's heads where it has one.

### The start (D13)

`FxStart` (`FREE | RESTART | NEXT_BEAT | NEXT_BAR`), column `start_mode` (default `FREE`) on
`look_fx`, `cue_ad_hoc_fx` and `palette_fx`, carried by `LookFxSpec` and set in
`FxSpawner.createFxInstance` beside the dynamics; `spreadUnit` (sync v43) is the template for every
file it passes through. `FxPhase.elapsed()` counts from the start anchor where one is set, as it counts
an FX with runs from `runsStartTick`: one anchor, and runs read it.

## 4. UX

- The FX editor's timing section gains *Starts*: *In step* (FREE) · *From the top* (RESTART) ·
  *Next beat* · *Next bar*, with the help line *Where the FX is in its cycle when it is pressed.* The
  glossary takes the row (session 5). *Start* is already an old name for *Low*, hence *Starts*.
- The Palette Steps list offers fixture-bound palettes, marked *Per head* (session 6).
- The FX editor's graph draws fine position where the preview sends it (session 2). Nothing else on
  screen moves; D9's options are MCP and REST only.

## 5. Implementation — eight sessions

Each session is one PR. Each is run by `/next-session`, which cuts the branch, stacks it on the
previous session's branch while that PR is open (on `main` once merged), records the previous
session's PR and head SHA in the table below, and hands the session to `/verified-ship`. A session
ends with its checks green (§"Pre-commit checks" in the root `CLAUDE.md`), the MCP descriptions and
guides it changes rewritten, the docs it names written, and its row marked *in review* with its
branch. A session's PR is titled *Moving-head FX: session N — title*. Never merge a later session's
PR before an earlier one.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | The MCP FX fixes | planned | | | |
| 2 | Fine position in the FX engine | planned | | | |
| 3 | Fine stored positions and the aim solution | planned | | | |
| 4 | The head in an FX, aiming, and Stage Path | planned | | | |
| 5 | Where an FX starts | planned | | | |
| 6 | Per-fixture palettes in Palette Steps | planned | | | |
| 7 | Reading a cue, and busk pads over MCP | planned | | | |
| 8 | Speed channels, travel in the preview, and render_view | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — The MCP FX fixes

- **The colour family (D1):** both inferences in `FxSpawner`, the group gate by D2's membership, the
  `attribute` text at `AiToolSchemas.kt:216` and on every other FX shape that describes the inference,
  and the rig briefing where it names it.
- **The landing check (D2, D3):** `fxLanding` beside `FxSpawner`, called from every tool D3 names,
  with the refusal and the skipped list in each answer. Palette Steps outside a palette is checked
  here too, through its palettes' attribute (`drivenAttributeOf`).
- **The record counts (D4):** `layersWritten` from `appendFxChildren`, `replaceImmediateFxChildren`
  and `createCueFromRecording` (which counts `recording.adHocFx` alone today), through
  `CueWriteOutcome`, the record and update answers and REST's DTOs; the three `includeFx`
  descriptions.
- **Tests:** in `mcp.*`: a ColourCycle Look with no attribute on a Beam Ring group spawning and in
  `get_current_state.activeFx`; the same on a fixture target and a selection; a stored `colour` row
  still running; an FX on `zoom` over pars refused naming the attribute and targets; over a mixed
  group the skipped heads listed; each D3 tool refusing once; a MERGE with `includeFx: false` over a
  Look layer answering `layersWritten: 1, fxWritten: 0`.
- **Docs:** `docs/mcp-engineering.md` §"Tools" (the check and the counts); `docs/fx-engineering.md`
  §"Colour FX" (the inference).
- **Checks:** `./gradlew compileTestKotlin`, then `./gradlew test --tests` for `mcp.*`, `ai.*`,
  `fx.*` and `routes.*`; the full suite is CI's. No frontend change beyond regenerated types if the
  record DTO's TypeScript mirror exists; if it does, `npm run check:quick`.

### Session 2 — Fine position in the FX engine

- **The output (D5):** `FxOutput.Position` exact, its constructors and coarse accessors; every
  producer and reader §1 names (`LayerResolver`, `ProvenanceService`, `FxSampler`, `StepList`'s palette
  steps) reading exact where it composes.
- **The target (D6):** `PositionTarget`'s fine writes, `FxBlend.positionAxis` on doubles, and every
  `(head, attribute)` reader taking the fine sliders as `position`. Read `followups.md`'s index for
  gates on FX tick loops and ArtNet output first (CLAUDE.md); `FU-PROG-SHARED-CHANNEL-RELEASE` is
  near.
- **Path (D7)** in doubles.
- **Equivalence (P3):** `PositionFineEquivalenceTest` runs today's `PositionTarget` and Path (kept
  under `src/test/resources/fx/legacy/`) and the new ones over P3's grid; coarse bytes equal, fine 0
  under OVERRIDE for an 8-bit output, the base's fine kept under ADDITIVE.
- **The preview:** fine planes in `FxSampleSeries.Position` and on `POST /fx/preview`; the FX
  editor's graph (`F/components/fx/`) reads them.
- **The cue crossfade (D8, first half):** `CueAssignmentResolver.interpolate` lerps a head's
  `position` and its fine rows as one 16-bit value per axis.
- **Tests:** a slow Path on a Beam Ring writing a smooth 16-bit ramp; a crossfade between two aimed
  positions with no backward fine step; suppression of a Path by a programmer `panFine`.
- **Docs:** `docs/fx-engineering.md` §"Position FX" and §"FxContext"'s neighbours where the output is
  described; the script guide (`ai/FxScriptGuide.kt`) on `Position.exact`.
- **Checks:** the full `./gradlew test` locally, since every position FX's output and the FX readers
  across `fx`, `state` and `plugins` move; `npm run check:quick` in `frontend/`.

### Session 3 — Fine stored positions and the aim solution

- **Palettes (D8):** decimal `pos:` in `PaletteIntent` (`trimNumber`, today `%.1f`, to three places with
  trailing zeros dropped), `PaletteResolver` writing fine, `centreIntentOf` keeping the fraction;
  the client's `F/lib/paletteIntent.ts` and `F/lib/positionScale.ts` keeping a fraction they did not
  edit. `pos:` and the `"pan,tilt"` row through the importer verbatim (P1), and a test reading a
  decimal `pos:` through today's parse to the rounded step.
- **The aim (D9):** `near` and `tiltSide` on `aimIntoProgrammer`, `POST /programmer/aim` and
  `aim_fixtures`; `aimAt` answering every reachable candidate; dryRun's `solutions`; exact fine
  written; `saveAsPalette` decimal. The frontend request types (`F/store/programmerOps.ts`) gain the
  optional fields, unsent.
- **Tests:** `aim_fixtures` with `near: {panDeg, tiltDeg}` choosing the nearer of two solutions and
  dryRun listing both; `tiltSide: negative` over four heads choosing one family; `near: "current"`; a
  saved palette pressed writing the same 16-bit value the aim did; Vitest on a decimal `pos:`.
- **Docs:** `docs/mcp-engineering.md` §"Show-setup tools" on `aim_fixtures`; the palette intent
  section of `docs/lighting-composition-model.md`; `docs/sync-engineering.md` on a decimal `pos:`.
- **Checks:** `./gradlew compileTestKotlin`, then `./gradlew test --tests` for `fx.*`, `routes.*`,
  `show.*`, `mcp.*`, `sync.*`; `npm run check:quick`.

### Session 4 — The head in an FX, aiming, and Stage Path

- **The head (D10):** `FxHead` on `FxContext`, from `DistributionPlan.of` and the whole-unit path;
  the sampler and the MCP trial run building heads; a test pinning the context's fields
  (`FxContextSurfaceTest`), so the next change to it is deliberate.
- **Aiming (D11):** `FxHead.aimAt` over `show/FixtureAim.kt`, the placement captured at expansion and
  refreshed on a rig change, the per-head previous answer on the instance. Measure a tick with four
  heads aiming against one without, and record it here.
- **Stage Path (D12):** the script, its frontmatter and presets, the preview's note for a head that
  cannot reach a point.
- **The guide:** `ai/FxScriptGuide.kt` on `head`, `aimAt` and keying by `fixtureKey`, with a stateful
  example that aims; the rig briefing names Stage Path.
- **Tests:** an FX's per-head table surviving a group reorder; `aimAt` matching `aim_fixtures` to the
  fine step; a 2 m S on a cloth 3 m away traced with no pan flip and no step over one fine unit per
  frame; a head re-hung mid-run aiming from its new place; Stage Path's preview.
- **Docs:** `docs/fx-engineering.md` §"FxContext" and §"Position FX".
- **Checks:** `./gradlew compileTestKotlin`, then `./gradlew test --tests` for `fx.*`, `show.*`,
  `mcp.*`, `ai.*`; the full suite if the context change reaches `state` or `plugins`. No frontend
  change.

### Session 5 — Where an FX starts

- **Storage (D13, P1):** `start_mode` on the three tables, `LookFxSpec`, the DTOs, `FxSpecCoercion`,
  and every file `spreadUnit` passes through; `StoredFxFields.start`; the format version bump and its
  `docs/sync-engineering.md` section; `RichProjectFixture` with a non-default start;
  `ProjectRoundTripTest`.
- **The anchor:** `FxInstance` and `FxPhase` counting from it; runs reading it; `keepCycles` and the
  glide (none under RESTART); `carryRunState` keeping it; the sampler drawing it.
- **The MCP schema** on every FX shape, and the rig briefing.
- **The editor (§4):** *Starts* in the FX editor's timing section, every host that edits a stored FX;
  the glossary row; `glossary.test.ts` and `GlossaryVocabularyTest` where they read it.
- **Tests:** a sequence Look on a pad with RESTART starting on its first step at any beat; NEXT_BAR
  holding step one to the bar; `runs: 1` with NEXT_BEAT finishing one cycle after the beat; a speed
  change keeping a RESTART FX's place; a carried FX across a GO not restarting.
- **Docs:** `docs/fx-engineering.md` §"Phase Calculation" and §"Amount, direction and runs".
- **Checks:** the full `./gradlew test` and `npm run check` locally: a `formatVersion` bump.

### Session 6 — Per-fixture palettes in Palette Steps

- **The steps (D15):** `PaletteStep` carrying its rows' targets; `PaletteStepList.valueFor`,
  `colourFor` and `resolveWheelSlot` through `PaletteReferences.resolveOn`; `PaletteSource.paletteStep`
  and `validatePaletteSteps` refusing only an FX palette or an empty one.
- **The client:** the Palette Steps list offering bound palettes, marked *Per head*.
- **Tests:** four heads stepping through three per-head position palettes, each on its own row; a
  palette with bound rows and a deferred row, a head outside the bound rows taking the deferred; a head
  with neither holding underneath; today's parse dropping a bound palette (P1).
- **Docs:** `docs/fx-engineering.md` §"Step FX".
- **Checks:** `./gradlew compileTestKotlin`, then `./gradlew test --tests` for `fx.*`, `routes.*`,
  `mcp.*`; `npm run check:quick`.

### Session 7 — Reading a cue, and busk pads over MCP

- **`get_cue` (D16)** over the GET route's reading, translated to `create_cue`'s shapes.
- **Busk pads (D17):** `get_busk_pads`, `set_busk_pads`; `appendBuskPad(state, project, bank, kind,
  recordId)` per `FU-HAND-PLACE-SHAPE`, the route calling `buskPadKind` after `bankIn`; the
  `buskLayoutChanged` broadcast; the History journal (`docs/undo-engineering.md` on busk layout).
  Close `FU-HAND-PLACE-SHAPE`.
- **Tests:** `create_cue` → `get_cue` → `create_cue` making a cue that exports the same; `get_cue` on
  a cue with every child kind; adding, replacing and removing pads; a pad naming a missing record
  refused before any row is written.
- **Docs:** `docs/mcp-engineering.md` §"Tools".
- **Checks:** `./gradlew compileTestKotlin`, then `./gradlew test --tests` for `mcp.*`, `routes.*`,
  `fx.*`; no frontend change.

### Session 8 — Speed channels, travel in the preview, and render_view

- **Speed (D18):** the two annotation fields, each type's speed channel declared from its manual
  where the manual says, `TravelVocabularyTest` holding the band inside the channel's range,
  `list_fixture_types`. The Beam Ring's manual is not in the repo: leave it undeclared and add a
  `docs/plans/manual-validation.md` row to read it off the unit.
- **Travel (D19):** the position note beside `WheelNotes`, on REST and MCP; MCP `preview_fx`
  carrying every note and fine samples. Correct `Travel`'s KDoc, which says the backend never reads it.
- **`render_view` (D20):** reproduce over a test scene (a programmer holding Beam Ring intensity and
  colour without position; heads downstage of the proscenium; Blind flying a cloth), then the fix for
  the cause found.
- **Tests:** a Path too fast for a Beam Ring noted; a slow one not; `list_fixture_types` naming a
  speed band; `McpRenderViewTest` for D20's answer.
- **Docs:** `docs/fixtures-engineering.md` §"Travel and timing channels"; `docs/fx-engineering.md`
  §"Previewing an FX"; `docs/mcp-engineering.md` §"`render_view`: Claude seeing the model".
- **Checks:** `./gradlew compileTestKotlin`, then `./gradlew test --tests` for `fixture.*`, `fx.*`,
  `mcp.*`, `ai.*`; `npm run check:quick` if D20 touches the client.

## 6. Migration

Session 5 only: `start_mode` is an additive column with a default (`createMissingTablesAndColumns`),
and the format version bump in P1. Nothing else is stored differently.

## 7. Explicitly out of scope

- A solver in the client, or D9's options in the aim UI.
- A start mode per Look or per pad (D14).
- Drawing a Stage Path's points on the stage.
- Drawing a speed channel's effect in the Stage view (`FU-STAGE-VECTOR-SPEED`).
- An FX's travel check against a fixture's speed channel; D19 reads the type's top speed.
- `get_look` or `get_palette`; the brief asked for a cue.
- The `programmer` vis source reading the rig for channels it does not hold.

## 8. Follow-ups

Session 2 changes what the FX tick writes and session 5 how its phase counts, so each reads
`followups.md`'s index for a gate on FX tick loops and ArtNet output before starting, per CLAUDE.md.
None adds a WS command. Session 7 closes `FU-HAND-PLACE-SHAPE`. Sessions add, as they reach them:
`FU-FX-START-PER-PAD` (D14), `FU-FX-STAGE-PATH-EDITOR` (D12's points drawn on the stage), and in
session 8 a manual-validation row for the Beam Ring's speed channel.

## 9. Verification

- **Session 1:** a ColourCycle Look created over MCP without an attribute runs on the Beam Rings;
  one with an attribute no head has is refused with a message naming it.
- **Session 2:** a slow Path on the Beam Rings moves with no visible stepping on the virtual stage,
  and every existing show's position FX looks the same.
- **Session 3:** `aim_fixtures` with `near` returns the solution closest to the given pan/tilt, and
  dryRun lists both.
- **Session 4:** a path FX using `context.head.aimAt` (or Stage Path) traces a 2 m S on a cloth 3 m
  away with no visible stepping in the virtual stage, and no head flips pan mid-path.
- **Session 5:** a sequence Look pressed on a pad with *From the top* always starts on its first step.
- **Session 6:** the XMAS letters' per-head aims step through Palette Steps with no linked cues.
- **Session 7:** Claude reads Act 2 Q4 with `get_cue` before adding the Beam Ring positions.
- **Session 8:** a fast Path's preview flags the Beam Rings, and `render_view` either draws their
  beams or says why not.
