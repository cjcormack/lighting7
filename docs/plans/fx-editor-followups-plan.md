# FX editor follow-ups: the list's head, the graph's rows and controls, aligned steps, and Fixtures

> **Document status: READY — written 2026-10-26 from the page Chris and Claude agreed that day.**
> Four sessions, each one PR, each stacked on the one before while that one is unmerged, each run by
> `/next-session` (§5). The page and the record are in
> [`fx-editor-followups-design/INDEX.md`](fx-editor-followups-design/INDEX.md).
>
> This document is the engineering half. Where it and the page disagree, this plan wins on
> behaviour and the page on layout and copy.

## 1. Context

Chris's brief, 2026-10-26, after using the FX editor ([`completed/fx-editor-plan.md`](completed/fx-editor-plan.md)) on the rig:

1. With nothing selected the list is verbose, its button runs out of the 248px column, and it does
   not help the operator get anywhere.
2. With several fixtures selected the subject is their names joined until they truncate.
3. Colour cells are sometimes misaligned between rows.
4. The graph's controls (the window, the Cycle and Spread handles) are hard to make out.
5. A multi-element fixture's first element is drawn as if it were the fixture, and the names have
   no room.
6. There is no way to run a colour FX with every element of a fixture alike and the fixtures
   different, chasing fixture to fixture with or without a fade.

What the code says, read at `e408e0f9`. `F/` is `frontend/src`, `K/` is
`src/main/kotlin/uk/me/cormack/lighting7`.

- **The empty state** is `NothingSelected` in `F/components/fxEditor/FxEditorSelection.tsx`: an
  `EditorReadout` paragraph and a dropdown button whose label is wider than the list column.
  `FxGraph` says *No FX reaches the selection.* for both "nothing selected" and "nothing reaches".
- **The subject** is `Subject` in the same file: `fxTargetOf`'s label (for a selection, the items'
  names joined), then the element count, the mask pill and `DeskChip`, wrapping onto a second line.
- **The step boundary**: Colour Cycle picks its colour with `(phase / segmentSize).toInt()`
  (`src/main/resources/fx/colour/ColourCycle.fx.kts`), and `stepsAt` (`K/fx/StepList.kt`) with
  `(p * n).toInt()`. At a phase that is exactly *k/n* the product lands either side of *k* by float
  rounding, depending on the row's offset. In Chris's screenshot one row steps at 17 · 15 · 16
  samples where its neighbours step at 16 · 16 · 16 (`FxSampler.SAMPLES_PER_CYCLE` is 48).
- **The graph's gutter** is `GUTTER_PX = 72` (`F/components/fxEditor/fxGraphModel.ts`). `layoutLane`
  puts a fixture's name on its first element's row (`fixtureLabel`), so a fixture with no attribute of
  its own has no row: its first element reads as the fixture. `elementLabel` falls back to the
  element's whole label, which the gutter cuts to *· Element*.
- **The toolbar** (`GraphToolbar` in `F/components/fxEditor/FxGraph.tsx`) is 24px segments in 11px
  muted text; *Window 8 beats · 4 s* is a read-out line below it, which reads as a control. The
  handles (`GraphHandles.tsx`) are 1px violet lines with a small grip, beside 1px violet start marks.
- **Element modes**: `ElementMode` in `K/fx/FxInstance.kt` has `PER_FIXTURE` (the default: the FX runs
  inside each fixture, every fixture alike) and `FLAT` (every element one strip). MagicQ asks the same
  question with three answers: *Dup Elements* (a head's elements act as one, its default), *Include
  Elements* (our Flat) and *Use Elements* (our Per Fixture). Item 6 is Dup Elements, which the desk
  lacks. Today it can be faked with Flat and Segments set to the element count, which breaks under any
  Order but Normal and on mixed element counts.

## 2. Decisions

| # | Decision |
|---|---|
| D1 | **Nothing selected**: one line (*No heads selected.* and *Pick a running FX to edit it on its heads.*), then **the running FX as cards** — name, family, its target and how many fixtures, its cycle and master. A press selects that FX's heads and picks it (today's menu item's two calls). With nothing running: *Select heads in Values*, which shows the Values table. The graph says *Select heads to draw their FX.* when nothing is selected and keeps *No FX reaches the selection.* when something is. |
| D2 | **The subject**: one fixture reads its name, a group its name (*Front wash · 8 fixtures*). Several fixtures read **4 fixtures · 12 elements ▾**, which opens a list: each fixture's colour, name and element count, and an *Only* that narrows the selection to it (through `selectWindowHeads`). The count chip, mask pill and link badge share one row and wrap only below the chip's own width. |
| D3 | **Steps land on their sample.** One desk step function, `stepIndex(phase, n)`, adds a tolerance before it floors; `stepsAt` and Colour Cycle use it, and so does any other built-in that indexes by `phase * n`. A sampler test pins equal step widths. The stage gets the same fix. |
| D4 | **The graph's controls.** A **beat ruler** over the lanes (bars and beats from the clock, as the hover's *Beat*) carries the **Cycle tab** at the first cycle's end. **Spread ends in a knob.** A handle is a knob; a mark is a tick or a thin line; hovering a knob names it (*Spread 100%*) and lights what it changes. The toolbar is one row of **28px labelled segments**, and the window's length (*8 beats · 4 s*) sits beside Window. The line under the toolbar is the hover's alone. |
| D5 | **A fixture's own row.** Every multi-element fixture gets a row over its elements. Where the answer has a row for the fixture itself (an attribute it carries), that row is it, as now. Where it does not, the row draws **the mix of its elements**: the mean level, the mean RGB. Position and Steps sections draw the name and no mix. A fixture's elements **fold** under a chevron on its row; folded, the mixed row is drawn alone. |
| D6 | **Room for names.** The gutter is **152px** by default and drags between **96 and 280**, kept per window. A fixture's name is cut **in the middle** (*LX2 Shehds…15 SR*) with the whole name on hover; an element row reads its own words after the fixture's name, else its number. |
| D7 | **Fixtures, the third element mode.** `ElementMode.WHOLE`: each fixture is one unit and every element it paints takes that unit's output; Order, Spread, Wings, Segments and Parts count fixtures. On screen, **Over: Fixtures · Elements · Each fixture** (`WHOLE`, `FLAT`, `PER_FIXTURE`) sits beside Order in the inspector, drawn where a target fixture has elements, and leaves Advanced. The element filter keeps its meaning under Fixtures: it picks which elements of each fixture are painted. |
| D8 | **New FX start on Fixtures.** Every door that **starts or creates** an FX writes `WHOLE`: the bare press, `POST /fx/add`, the group FX route, MCP's tools, the picker's draft arm (cue FX), and a new FX palette. A **stored** FX with no element mode still means `PER_FIXTURE`, so no archive or row changes meaning. Fixtures differs from the other two only where the FX lands on elements, so "every new FX" is the page's "an attribute a fixture carries only on its elements" said once. The script API's defaults do not change. |

The plan adds three of its own:

- **P1 — Four sessions, four stacked PRs**, in §5's order, each run by `/next-session`
  (`.claude/skills/next-session/SKILL.md`) through `/verified-ship`. A session stacks on the previous
  session's branch while that PR is open, and on `main` once it has merged. Sessions 3 and 4 do not
  need 1 and 2's code, but sessions run in order.
- **P2 — The graph still computes no FX output.** D5's mix averages the desk's samples for a row the
  desk does not send; it is drawing, as *As seen* multiplies two lanes. Nothing else on the client
  works out what an FX does.
- **P3 — One PR per change across the wire.** Session 2 adds a wire value, so the desk and every
  client reader of element modes land together.

## 3. The model

**Over** decides what the spread counts and what shares an output:

| Over | Units the spread counts | An element's output | MagicQ |
|---|---|---|---|
| Fixtures (`WHOLE`) | fixtures | its fixture's | Dup Elements |
| Elements (`FLAT`) | every element of every fixture | its own | Include Elements |
| Each fixture (`PER_FIXTURE`) | the elements inside one fixture, every fixture alike | its own | Use Elements |

On a fixture whose attribute is its own (a bar's dimmer), all three expand to one member per
fixture, as today.

What item 6 asked for, with the Shehds: Over Fixtures, Colour Cycle with one colour per fixture, a
cycle as many beats as fixtures, Spread 100%. Each beat the colours move one fixture; a fade ratio
above 0 crossfades them.

## 4. UX

The page is the authority on layout and copy: the two list boards for D1 and D2, the graph board for
D4–D7 (its drawing is a working copy of the model), and the step strips for D3. Two things it does
not show:

- **Touch**: below the editor's 760 arm the subject's list opens as a bottom sheet with 44px rows;
  the gutter's grip is not drawn under a coarse pointer, where the width is the default.
- **Focus**: the subject's list takes focus on open and Escape returns it to the chip; a fixture
  row's chevron is a button, Enter and Space fold it.

## 5. Implementation — four sessions

Each session is one PR. Each is run by `/next-session`, which cuts the branch, stacks it on the
previous session's branch while that PR is open (on `main` once merged), records the previous
session's PR and head SHA in the table below, and hands the session to `/verified-ship`. A session
ends with its checks green (§"Pre-commit checks" in the root `CLAUDE.md`; session 2 also runs the
full `./gradlew test` and `npm run check`, as a sync `formatVersion` bump requires), the docs it
names written, and its row marked *in review* with its branch. A session's PR is titled *FX editor
follow-ups: session N — title*. Never merge a later session's PR before an earlier one.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | Steps land on their sample | merged | claude/fx-editor-followups-s1 | [#292](https://github.com/cjcormack/lighting7/pull/292) | 1da63241 |
| 2 | Fixtures: the third element mode, and Over | merged | claude/fx-editor-followups-s2 | [#293](https://github.com/cjcormack/lighting7/pull/293) | c3b12b23 |
| 3 | The graph: ruler, knobs, fixture rows and room for names | merged | claude/fx-editor-followups-s3 | [#294](https://github.com/cjcormack/lighting7/pull/294) | 729f62b1 |
| 4 | The list's head: nothing selected and the subject | merged | claude/fx-editor-followups-s4 | [#295](https://github.com/cjcormack/lighting7/pull/295) | dec701db |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — Steps land on their sample

- **Prove the diagnosis first (D3).** Write the sampler test before the fix: Colour Cycle, fade
  ratio 0, three colours, over one fixture's three elements under Each fixture, two cycles. Every
  step must be 16 samples wide. It should fail on `main`. If it does not, the cause is elsewhere
  (the member offsets in `FxDistributionPlan`, the sampler's tick arithmetic): find it, record it
  below and fix that instead.
- **The step function:** `stepIndex(phase: Double, n: Int): Int` beside `stepsAt` in
  `K/fx/StepList.kt`, flooring `phase * n + ε` (pick ε against the sample rate; one tick of a 1-beat
  cycle at 24 ticks a beat is the largest error it must absorb, and it must never move a step that is
  not at a boundary). `stepsAt` uses it. Colour Cycle uses it in place of its own division.
- **Audit** every `.fx.kts` under `src/main/resources/fx/` that turns a phase into an index or tests
  `phase < k/n` (the strobes, Square Wave, Pulse, Rainbow Cycle): a comparison against an exact
  fraction takes the same tolerance, through the one function where it fits.
- **The compiled-script cache** recompiles the built-ins when a source changes, so nothing is
  migrated.
- **Tests:** the sampler test above; `stepIndex` at *k/n* for n in 2..8 with the offsets a spread of
  n members produces; the built-ins' existing tests unchanged.
- **Docs:** `docs/fx-engineering.md` §"Step FX", a sentence on `stepIndex` and why.
- **Recorded (session 1):** the sampler test failed on `main` as diagnosed: zone 1, offset 0, changed
  colour at samples 17 · 33 · 48 · 65 · 81 over a one-bar cycle. The error is `FxPhase.shape`'s wrap,
  `((x % 1) + 1) % 1`, which turns tick 32 of 96 into 0.33333333333333326, a rounding error short of a third.
  The wrap is left as it is, since every FX's phase would move with it; the tolerance absorbs it.
  ε is `PHASE_EPSILON` = 1e-9 (of a step in `stepIndex`, of a cycle elsewhere), not a tick: the error
  to absorb is float rounding (~1e-15), and a tick-sized ε (a 24th of a cycle) would move steps that
  are not at a boundary. FxSampler's cycle starts share it. Colour Cycle also needs how far through
  its step a phase is, so `stepWithin` sits beside `stepIndex`; an edge that is no whole step (an on
  ratio, a duty cycle) takes `phaseBefore`, used by the two strobes, Square Wave, Pulse, Frost Pulse,
  Iris Pulse and Lightning Strike. Rainbow Cycle and Ramp's `phase < 0.5` are continuous across their
  edge, so they are unchanged. The plan missed direction: a reversed phase meets each boundary from
  above, so an upward ε alone put Backward a sample late and flashed a step at each Bounce.
  `FxDirection.reverse` now reads `1 − p` two ε short, which also keeps phase 0 at the cycle's end.

### Session 2 — Fixtures: the third element mode, and Over

- **The desk (D7):** `ElementMode.WHOLE` in `K/fx/FxInstance.kt`. `FxTargetExpansion` and
  `FxDistributionPlan` build one member per fixture whose paint is its elements (filtered by the
  element filter); `FxEngine` writes the member's output to each. Every other reader of
  `ElementMode` follows (the list in `grep -rln ElementMode src/main/kotlin`): `FxSampler` (each
  element a row, offsets from its fixture, `starts` and `holding` per row), the preview's offset
  ladder, `FxSpawner`, the registries, the routes that parse it, `FxSpecCoercion`, and MCP's schemas
  (`ai/AiToolSchemas.kt`, the enum gains the value).
- **Defaults (D8):** the routes that start an FX — `POST /fx/add`, `POST /programmer/fx`,
  `POST /groups/{name}/fx` — and MCP's tools read an absent element mode as `WHOLE`. Every stored
  read (a cue's, a Look's or a palette's FX, an import) still reads absent as `PER_FIXTURE`. Name the
  two defaults apart in code so neither is mistaken for the other.
- **Sync:** the FX DTOs carry `elementMode` as a nullable string (`sync/dto/SyncDtos.kt`), so a 39
  desk would meet `WHOLE` it cannot parse: `formatVersion` and `minReader` both move to 40
  (`SUPPORTED_FORMAT_VERSION` in `ProjectImporter.kt`, the rules in `docs/sync-engineering.md`
  §"formatVersion"). Extend `testsupport/RichProjectFixture.kt` with a `WHOLE` FX (non-default, so the
  round trip sees it) and the round-trip and clone tests; an archive without the field imports as
  `PER_FIXTURE`.
- **The client (P3):** the `ElementMode` type, `ELEMENT_MODE_OPTIONS` (*Fixtures · Elements · Each
  fixture*, in that order), and every reader in `F/` (`fxConstants.ts`, `fxEditorModel.ts`'s
  `spreadHeadCount`, `cellFx.ts` and `fxPaintedHeads`, `FxSummary.tsx`, `lib/spreadShape.ts` and its
  vector `src/test/resources/fx/spreadShape.fixture.json`, which gains `WHOLE` cases so the dials
  and the desk agree).
- **Over (D7):** `FxFields` draws *Over* beside Order where the target has a fixture with elements,
  and drops Element mode from Advanced. `orderPatch`'s rules are unchanged; Wings under a mirrored
  Order are refused as today, counted over fixtures under Fixtures.
- **Creation doors (D8):** `FxPicker`'s starting spec, the draft arm (cue FX), `PaletteEditor`'s new
  FX palette and `startingSpec` write `WHOLE`. An FX opened for editing keeps the mode it has.
- **Words:** `docs/glossary.md` — the Per Fixture and Flat rows take *Each fixture* and *Elements*,
  a *Fixtures* row is added (MagicQ column *Dup Elements*), and *Over* is the control's word; the
  vocabulary tests (`GlossaryVocabularyTest`, `glossary.test.ts`) follow.
- **Tests:** expansion and sampler over four three-element fixtures under each mode (Fixtures: four
  offsets, each fixture's elements equal at every sample); the start routes default to `WHOLE` and a
  stored FX without the field reads `PER_FIXTURE`; MCP's schema accepts it; `spreadShape.test.ts`
  over the new vector; `FxFields` draws Over where it should and not in Advanced; each creation door
  writes `WHOLE`.
- **Docs:** `docs/fx-engineering.md` (element modes, the two defaults), `docs/sync-engineering.md`
  (version 40), root and `frontend/CLAUDE.md` where they describe Element mode.
- **Recorded (session 2):** the plan treated element mode as a group's question, and the code did
  too: Record, the cue capture, a Look's settings and the Timeline dropped the mode off anything but
  a group or a selection, and the FX DTO reported it only over members. Under Fixtures a lone
  multi-element fixture differs (its elements alike, where Elements and Each fixture chase them), so
  a stored row now keeps the mode over members as before and, on a lone fixture, `WHOLE` alone
  (`ElementMode.stored`, Record's canonical form beside it), old rows comparing unchanged; the DTO
  reports it wherever the FX lands on elements, and Over is drawn for a lone fixture with heads too.
  The preview's draft (`previewDraftOf`) sent no mode for a stored FX naming none, which `/fx/add` now
  reads as Fixtures, so it says `PER_FIXTURE`. MCP's tools write `WHOLE` into the row rather than
  reading an absent one as it later. The shared vector's element-mode cases carry `over` (a mode,
  fixtures and elements each): the desk checks every element takes its unit's offset, the client that
  `spreadHeadCount` counts the same units. Over is drawn as a segment on its own row under Direction
  and Order, as the page's board does; three labels do not fit beside Order in the inspector.
  Chris's calls during the session's review: a palette **owns** its Over (drawn in the palette
  editor, compared for a pad's *edited*, saved by *Update palette*; a type swap keeps it); *New
  palette from FX* keeps the running FX's Over, Fixtures where it reports none; summary lines say
  *over fixtures* · *over elements* · *each fixture*, and the tray shows a lone fixture's. Copies split
  from one fixture of a stateful FX under Fixtures share one step a tick for each effect object they
  run (`SplitMember.step`), so a narrow leaves the fixture's elements alike and an edit naming some of
  them lands on those alone. `startingSpec` names no mode: the doors it serves write `WHOLE` beside
  it (the picker's start, the draft arm, a new FX palette), since a type swap must keep the mode.

### Session 3 — The graph: ruler, knobs, fixture rows and room for names

- **Fixture rows (D5):** `layoutLane` inserts a fixture row before each run of a fixture's elements;
  it is the answer's own row for the fixture where there is one, else a mixed row (`kind: 'mix'`, its
  members the element rows). `fxPreviewDecode` gains the mix for slider and colour sections: the
  mean of the members' planes per sample, rounded. Position and Steps sections draw the name line
  with no strip. A mixed row takes no Phase drag and no hover phase; its hover reads *LX2 Shehds 19x15
  SR · mix of 3*. Its start tick is drawn only where its elements share one.
- **Folding (D5):** a fixture row's chevron folds its elements, kept per window beside the folded
  lanes (`lib/fxEditorView.ts`). Folded rows count towards the heat map's 25-row threshold as one.
- **Gutter (D6):** `GUTTER_PX` becomes the window's gutter width (default 152, 96–280,
  `programmer.fx.graph`), dragged by a grip between gutter and plot, arrow keys stepping 8. The graph
  strip (`FxGraphStrip`) keeps a fixed width. Names are cut in the middle by measured width, the whole
  name on hover; `elementLabel` returns the element's own words or its number, never its whole label.
- **The ruler and the knobs (D4):** a ruler canvas over the lanes, numbered from `barBeatAt`'s
  clock (bar numbers on the bar, `bar.beat` between; seconds on a wall-clock FX). The Cycle handle
  moves to a tab on the ruler at the first cycle's end, labelled with the cycle (*1 bar ⇔*). The Spread
  handle ends in a knob on the last row's start; the min and max lines of an Intensity FX get knobs
  at their right end. A knob's hover names it and sets the graph's `highlight` as the inspector's
  hover does. The playhead stays sky; marks stay thin.
- **The toolbar (D4):** `Segment` and `Toggle` at 28px with their label inside the segment
  (*Window*, *Draw*) and the foreground's colour; the window's length next to Window;
  `WindowReadout` keeps only the row order and the hover hint.
- **Measure:** in Chromium at the editor's three bands (1100+, 760–1099, under 760) on a desk with
  the four Shehds and a bar with fixture-level dimmer: the plot keeps at least `MIN_PLOT_PX` beside
  the default gutter, the toolbar fits its row or wraps cleanly, and no label overruns. Record the
  widths in §9 and fix the default where it fails.
- **Tests:** `fxGraphModel.test.ts` (fixture rows, mixed members, folding, the heat threshold, the
  middle cut), `fxGraphDraw.test.ts` (a mixed row's pixels are the mean of its elements'),
  `FxGraph.test.tsx` (the toolbar's segments and length, the ruler's numbers, the Cycle tab and
  Spread knob writing through the editor as the old handles did), `fxGraphHandles.test.ts` updated.
- **Docs:** `frontend/CLAUDE.md` §"The programmer's tables", the FX editor's graph bullets.
- **Recorded (session 3):** the strip keeps the old 72px name column rather than the graph's
  default: its hosts (the busk FX tab's 320px sheet, the palette editor, the cue FX tab) are narrow,
  and 152 would leave them no plot. Names moved off the canvas into DOM over it, since a fixture row
  needs a chevron that is a button. A mixed row has no answer row (`row` -1), so it is hovered by a
  key of its own and reads its ladder rungs off its first element. A max or min knob lights a new
  mark, `levels`, the level lines; the inspector has no control that lights it. The ruler numbers
  bars from the bar the window starts in, not from the master's tick 0, which would run into the
  thousands and move with every re-ask. Three calls the session's review made: the heat map counts
  the answer's rows, a folded fixture as one — a mixed row over drawn elements adds none, so the strip,
  which cannot fold, turns to a heat map exactly where it did before — and the lane's header carries
  *Fold elements*, since a heat map draws no chevrons; High and Low are one pair of knobs on the first
  row (they are the FX's, and a pair per row overlapped at 28px), the lines still drawn on every row;
  Spread's knob rides the lowest row Spread moves furthest, the last row when it moves at all. The
  mixed rows are built once per answer (`DecodedAttribute.mixed`); `LaidRow` keeps `row` -1 for them.

### Session 4 — The list's head: nothing selected and the subject

- **Nothing selected (D1):** `NothingSelected` becomes the running FX as cards (not leaving), by
  family then name: name, family, `fxTargetLabel` and fixture count, cycle and master. A press calls
  `selectWindowHeads(fxTargetsOf(effect), fixtures)` and `setPickedFx`. With none running, a button
  that shows the Values table. `FxGraph` takes why it is empty (`nothing selected` or `nothing
  reaches`) and says which.
- **The subject (D2):** `Subject` reads one fixture's or a group's name as today; a selection of
  several fixtures, or of elements across several, reads *n fixtures · m elements* as a chip that
  opens the list (a popover, a bottom sheet below the 760 arm): each fixture's colour (the fixture
  colour index), name and selected element count, with *Only*. The chip, mask pill and link badge
  sit on one row.
- **Tests:** `FxEditorSelection.test.tsx`: the cards list the running FX and a press selects and
  picks; the empty-rig button; the graph's two empty sentences; the subject's three forms; the list
  and *Only*.
- **Docs:** `frontend/CLAUDE.md`, the FX editor's list bullets.
- **Recorded (session 4):** the words are a pure module, `fxEditorHead.ts` (`subjectForm`,
  `runningCards`), beside `fxEditorList.ts`. The count chip keeps its width and the row wraps as D2
  says: with a mask set, the pill outgrows the room the 248 column leaves beside the count, so the
  pill and badge drop to a line below. A named subject stays on one row, the name truncating and the
  pill too where a long mask would overflow the column. Elements of one fixture read as that fixture
  with its elements counted, and a fixture with none as *1 head*, as today. The list is the heads chip's
  `ToolbarMenu`, so the desk's arm is a dropdown whose rows are the *Only* press, the word shown on
  the highlighted row; the phone's sheet shows it on every row, since a finger has no hover.
  `FxGraph`'s `empty` is a reason, `nothing-selected` or `nothing-reaches`, rather than a flag.
  Chris's calls during the session's review: a card names its attribute where that is not its
  family's own (*UV*, *Zoom*), as a lane card does, where the board drew the family alone, since two
  FX of one type on one target were otherwise the same card; *Only* takes away the chip its list
  closes onto, so focus goes to the mask pill on the row; and in a window with its own selection
  *Only* goes through Include's request as the plan says, so the marquee's mask and the grid's
  filter are let go there, where following the desk keeps the mask. *Only* is also shown on every
  row under a coarse pointer, whatever the editor's width.

## 6. Migration

None for stored data: an FX without an element mode still means Each fixture. Session 2 bumps the
sync `formatVersion` to 40 for the new value.

## 7. Explicitly out of scope

- Changing the script API's element-mode default.
- A mixed row for Position or Steps.
- Over on the busk FX tab's rows beyond what `FxFields` draws there.
- Renaming the wire values `PER_FIXTURE` and `FLAT`.

## 8. Follow-ups

None fired. Session 2 adds no WS command (`FU-AUTH-WS-PER-MESSAGE`) and changes how FX expand, so it
reads `followups.md`'s index for a gate on FX tick loops before it starts, per CLAUDE.md.

## 9. Verification

- **Session 1:** Colour Cycle at a hard cut over a Shehds' three elements steps evenly on the graph,
  and on the rig the three rings step a third of a cycle apart.
- **Session 2:** four Shehds, Colour Cycle with four colours over four beats, Over Fixtures: every
  ring of a fixture is one colour, and each beat the colours move one fixture, with and without a
  fade; Elements and Each fixture draw as before; a show exported before the change imports and runs
  unchanged.
- **Session 3:** the graph's fixture rows read as the rig looks from the desk; the Cycle tab and the
  Spread knob are found without being told; the names read at the three bands. Widths recorded here.
  - **Measured (session 3)**, in Chromium on an isolated desk with four Shehds (three zones each)
    and a 10-channel pixel bar with its own dimmer, Colour Cycle over all five on Elements:

    | Window | Editor (band) | Graph | Names | Plot | Toolbar |
    |---|---|---|---|---|---|
    | 1600, sidebar open | 1360 (columns) | 772 | 152 | 592 | 2 rows, clean |
    | 1180, sidebar open | 940 (side, inspector open) | 346 | 152 | 166 | 3 rows, clean |
    | 1024, sidebar folded | 960 (side) | 356 | 152 | 176 | 3 rows, clean |
    | 1024, sidebar open | 784 (side) | 236 | 72 | 136 | segments wrap inside, clean |
    | 700 (touch) | 700 (stacked) | 700 | 152, no grip | 520 | 2 rows, clean |

    No label overran the name column at any width. 152 failed once: in the side form the inspector
    sits *beside* the graph (half its width, at least 300px), so near the band's floor the graph is
    ~236px and 152px of names left 56px of plot. The default stands; the names now give way, down
    to 72px, wherever they would leave the plot under 160 (`fitGutter`), and an element whose words
    do not fit reads its number. Below about 236px of graph no name width keeps 160px of plot; that
    is the side form's layout, not the graph's. The Window segment (295px) overflowed a 212px
    toolbar, so below 300px of toolbar a segment's label folds and its buttons wrap inside it.
- **Session 4:** from nothing selected, one press on a card puts its FX in the inspector with its
  heads selected; the subject reads at 248px with four fixtures.
  - **Measured (session 4)**, in Chromium on an isolated desk with four Shehds (three zones each)
    and a par, at 1440: a press on Colour Cycle's card selected the Shehds group and put the FX in
    the inspector. Four Shehds selected read *4 fixtures · 12 elements*: the count 166px, the pill
    27, the link badge 21, 226 of the row's 231 with the gaps; with *Intensity · Colour* masked
    the count kept its 166 and the pill (98) and badge dropped to a second line, nothing past the
    column. *Only* narrowed to one, which read
    *LX2 Shehds 19x15 SR · 3 elements*. At 375 the list opened as a bottom sheet of 44px rows.
