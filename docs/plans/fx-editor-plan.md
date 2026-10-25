# FX editor: a list, a graph and an inspector for the FX on a selection

> **Document status: AGREED 2026-10-23; sessions 1a, 1b, 2, 3a, 3b, 4, 5 and 6 of 9 done.** The fifteen questions and their answers are
> in §11, every one answered as recommended. Each session has a **gate** on the terminology plan
> ([`terminology-plan.md`](completed/terminology-plan.md)) in §6; check it before starting. Every word here is
> that plan's (§4 there): *FX*, *Cycle*, *Amount*, *Order*, *Segments*, *Parts*, *element*, *attribute*, *family*,
> *Intensity*, *Runs*, *Timing: Per cycle | Per head*. The design record is
> [`fx-editor-design/`](fx-editor-design/INDEX.md); its one page,
> [`fx-editor-design/fx-editor.html`](fx-editor-design/fx-editor.html), draws the views and runs a
> working copy of the graph; it is live at <https://claude.ai/artifact/Dj9DW378cSMLMRbSUPp66r>,
> private to Chris and a convenience (the checked-in files are the authority). Where this plan and
> the page disagree, the plan wins on behaviour and the page on layout and copy.

## 1. Context

Chris's brief, 2026-10-23: a new FX editor for the programmer that helps build stacked FX and shows
how an FX's attributes interact, for example as a timeline of how colour and intensity change on each
head and element. He also asked what else would make FX programming simpler without taking power
away.

What the code says, read at `48f703b`. `K/` is `src/main/kotlin/uk/me/cormack/lighting7`, `F/` is
`frontend/src`. Paths and identifiers are the pre-terminology ones; sessions 4 to 6 of that plan
rename them.

- **The editor edits one FX at a time.** `FxEditor` (`F/components/fx/FxEditor.tsx`) mounts
  `FxEffectFields` (`F/components/fx/FxEffectFields.tsx`, 1222 lines) inline in the fixture sheet's
  tray, in a popover from the programmer's FX table, the Values grid's FX lens and a cell's FX
  band, and in the busk FX tab. Nothing shows the FX on a selection together, or what they add
  up to.
- **The programmer's FX table lists every running FX** (`F/components/applied/AppliedFxTable.tsx`):
  one row per instance, all homes, with Edit…, pause, Save as palette and Stop. It is the FX tab of
  *Values · Stack · FX · Scenery* (`F/components/programmer/ProgrammerTableTabs.tsx`).
- **There is no door for a second FX of a family on the same heads.** The bare FX press
  (`POST /programmer/fx`, `K/routes/programmerFx.kt`) first takes the selected heads
  off every other Local FX of its family. Two Intensity FX on one head are reachable only through a
  palette layer, a Look, a cue, or `/fx/add` from a script.
- **Order within the programmer band is creation order, and Local FX sit at its bottom.** Local FX get
  exactly `PROGRAMMER_FX_PRIORITY_BASE` (`K/routes/lightFx.kt:531`); a programmer layer's FX get the
  base plus the layer's index (`K/fx/ProgrammerLayerStack.kt:1149`, 0 the bottom layer). The engine
  sorts by `(priority, composeOrder, id)` (`K/fx/FxEngine.kt:95`). So a Local FX loses to every layer
  FX above the bottom layer on the same key, though Local *values* beat every layer, and the Stack
  table draws *Your values* and its Local FX on top. Two Local FX on one key apply in the order they
  were made, and nothing can change it.
- **Stored FX keep order in a cue, not in a Look.** `cue_ad_hoc_effects.sort_order` exists
  (`K/models/cues.kt:297`); so does `look_effects` (a column this finding missed, synced as
  `LookEffectJson.sortOrder`), but Record writes neither in apply order.
- **Beat and wall-clock FX on one key do not combine.** Each tick loop resets the keys its FX cover
  to the layer below and applies its own FX (`K/fx/FxEngine.kt:1598`, `:1740`), in its own
  transaction. The later commit wins, whatever the blend. `docs/fx-engineering.md` §"Beat ↔
  Wall-Clock interaction" says Add and Max "compose naturally"; they do not. A Sine Wave with a Candle
  Flicker *Within* it shows one or the other, alternating with the loops. `FU-PERF-FRAME-TXN-UNIFY`
  records the transaction half and is gated on visible flicker.
- **Nothing evaluates an FX off the stage.** `Effect.calculate` is called only from
  `FxEngine.calculateEffectOutput` (`K/fx/FxEngine.kt:1816`). Cue previews are Tier 4 only
  (`K/routes/cuePreview.kt:30-37`). `programmer.keyStack` lists what sits under an attribute and which is on
  stage, with no FX output values (`K/fx/ProvenanceService.kt:757-787`).
- **The client never computes an FX's output.** The one client copy of a desk rule is the spread's
  offsets for the phase dials (`F/lib/spreadShape.ts`), pinned to `K/fx/SpreadShape.kt` by
  `src/test/resources/fx/spreadShape.fixture.json`. FX are Kotlin scripts, user FX included, so a
  graph must come from the desk.
- **Sampling needs the engine's maths without its state.** The phase functions mutate the instance
  (`nShotStartTick`, `nShotPending`, `lastPhase`; `K/fx/FxInstance.kt:1053-1137`); a wall-clock phase
  reads the instance's accumulator; a stateful FX's `state` map is shared by every member and split
  copy (`K/fx/ScriptEffectAdapter.kt:73-99`); the envelope reads the wall clock. Blend and weight are
  pure but live inside `FxTarget.applyValueToFixture` (`K/fx/FxTarget.kt:383-391, 644-668, 794-802,
  915-930`). Expansion and distribution plans are already cached, pure functions of the rig
  (`FxEngine.buildExpansion`, `DistributionPlan.of`).
- **Two built-ins are stateful** (Candle Flicker, Fluorescent Flicker); Flicker, Colour Flicker and
  Random Position are pseudo-random but pure in phase.

## 2. Decisions

Agreed with Chris on 2026-10-23 (§11):

| # | Decision |
|---|---|
| D1 | **The programmer's FX tab becomes the FX editor**, with two views: *Selection*, the new editor, and *All*, today's table. A row in *All* selects its FX's heads and opens it in *Selection*. |
| D2 | **Selection is three columns: list · graph · inspector.** The list holds every FX reaching the selected heads, by family; the graph draws each element's output over time; the inspector is today's live editor for the FX picked in the list. |
| D3 | **The list is in family lanes, top wins.** Intensity, Colour, Position, Beam, each with its FX in apply order, top first: Local FX, then programmer layers' FX, then cue FX (read-only, naming the cue), then an *Underneath* line naming what the lane sits on. A lane appears when an FX is in it or the selection can take the family. |
| D4 | **Apply order can be changed, and it is kept.** A Local FX is dragged within its lane. The desk ranks Local FX in the programmer band; Record keeps the order into a cue's FX and a Look's (`sort_order`, which both already had and sync carries, so no `formatVersion`). |
| D5 | **Local FX sit above programmer layers' FX**, as Local values sit above layers. |
| D6 | **+ FX on a lane that already holds a Local FX asks *Replace · Stack*.** *Replace* is today's press. *Stack* adds the FX on top and replaces nothing, starting on the family's stacking blend: Intensity *Within*, Position *Around*, Colour and Beam *Replace* at 50 % Amount. |
| D7 | **The graph is sampled by the desk.** `POST /projects/{id}/fx/preview` runs the engine's own phase, spread, waveform, blend and weight functions over a stated window, without touching a running instance or the stage, for running FX and for drafts. A parity test holds it to the tick. |
| D8 | **The graph draws what the stage will get.** *Result* is the lane's output after every FX in it, over what is underneath now; *This FX* is the picked FX's own output before Amount; *Underneath* is drawn as a ghost behind both. Rows are elements, grouped by fixture, in the picked FX's Order. |
| D9 | **The graph explains the spread.** Each row marks where its cycle starts; Segments and Parts bracket the rows; Wings mark the mirror; Runs hatch where an element holds; an *offset ladder* in the inspector draws each element after Order, Segments, Wings, Parts and Spread. Hovering a control lights what it changes. |
| D10 | **Beat and wall-clock FX on one key combine in apply order.** One compose pass per frame, woken by either clock, replaces the two loops' separate apply passes. |
| D11 | **Shapes.** One tap sets Order, Spread, Wings, Segments and Parts together: *Unison, Chase, Back, Centre out, Centre in, Pairs, Odd/even, Mirror, Random*. The fields stay, and a shape is lit only while every field matches it. Nothing new is stored. |
| D12 | **Handles on the graph.** Drag a cycle's edge for Cycle (snapping to the cycle steps), the chase line for Spread, a row block for Phase, a level line for an Intensity FX's min and max. Each writes the field the inspector writes. |
| D13 | **Match**: *Same cycle as* and *Same spread as* another FX in the list copy those fields once. Nothing links them afterwards. |
| D14 | **Solo is the graph's.** Solo draws the lane as though the picked FX were alone over what is underneath. Nothing on stage changes. |
| D15 | **The FX palette editor and the cue editor's FX tab get the graph** over their drafts, on the selection or eight notional heads. A Look's FX are still edited through Include. |

The plan adds its own:

- **P1. One PR per session**, started only once its §6 gate and needs are met, each crossing the wire with its client (CLAUDE.md
  §"Git workflow"). Each session runs the packages it touches; sessions 2 and 3b run the full suites,
  for the engine change and the `formatVersion` bump. Each writes its engineering-doc paragraphs and
  puts its change commit's SHA in its heading when it lands.
- **P2. The desk owns every rule.** The client draws samples and never computes an FX's output. The
  offset ladder's steps come from the desk too, so `spreadShape.ts` stays the only client copy, used
  where the desk has not answered yet (the dials).
- **P3. One sampler, the engine's own functions.** The preview calls the functions the tick calls
  (§3.2). No second waveform, blend or spread lives anywhere, and the parity test fails if the two
  drift.
- **P4. Previewing changes nothing.** A preview never touches an instance's clocks, Runs anchors,
  state map, envelope, `lastPhase` or the transaction, and never sends DMX.
- **P5. Every write is an existing write.** The inspector, the handles, Shapes and Match send
  `updateFx` as the live editor does; the list's drag sends the new move frame (§3.5); *Stack* is the
  bare press with one new field. Nothing else stores anything.
- **P6. Nothing new is held only by the client.** Solo, the picked FX, the window and the lanes'
  fold are per-window view state (`sessionStorage`), as the busk view's focus is.

## 3. The model

### 3.1 Vocabulary

- **FX editor**: the programmer FX tab's *Selection* view (D1).
- **List**: the left column; one **lane** per family.
- **Graph**: the middle column. Not *timeline*, which is the stack view's mode (stack-timeline plan
  C1).
- **Inspector**: the right column; the live editor's fields for the **picked** FX.
- **Apply order**: the order the engine folds FX on a key, bottom first. The list draws it top first.
- **Underneath**: what an attribute holds before the lane's FX, `LayerResolver.fallbackFor`: the
  programmer's value, else the cue's, else the base.
- **Window**: the span the graph draws, in the picked FX's cycles or bars.
- **Offset ladder**: each element's rank after each step of the spread (§3.3 of the effects plan).

### 3.2 The sampler (D7, P3, P4)

`fx/FxSampler.kt`, pure, built from functions the engine already has, split out where they are
fused with state today:

1. **Phase.** `FxInstance.beatPhase` and `wallClockPhase` become a pure `phaseAt(clock, dynamics,
   anchors)` returning the member phase or NaN, where `clock` is a tick count or scaled milliseconds
   and `anchors` the Runs start. The instance methods call it and keep their writes
   (`runsStartTick`, `lastPhase`) outside it. `shapeMemberPhase` is already pure in its arguments.
2. **Members.** `buildExpansion` / `buildSelectionExpansion` and `DistributionPlan.of` as they are,
   read-only, for the instance's target or a draft's.
3. **Output.** STANDARD FX through the registration's `Fx.calculate(phase, context)` on a fresh
   FX object; Steps through `StepList.valueFor` per head. STATEFUL FX on a fresh object per
   member, `initialize()`d, driven tick by tick from the window's start with `deltaMs` at the sample
   rate and a fixed seed; the answer marks them `representative`. (The terminology plan's D27 leaves
   no COMPOSITE FX.)
4. **Blend and weight.** `FxTarget`'s per-kind blend and `weigh` / `weighDiscrete` move to
   `fx/FxBlend.kt` as pure functions of (before, output, blend, weight). `applyValueToFixture` calls
   them; so does the sampler.
5. **Underneath.** `LayerResolver.fallbackFor` once per (head, attribute) at the request, held
   constant across the window. A fade underneath is not drawn.
6. **The fold.** For each (head, attribute), the stack's FX in apply order (`sortedFxComparator`,
   with D5's band), each blended over the running result and weighed by its Amount × the stack's FX
   amount (cue FX) × 1 for the envelope; one suppressed for this key (`FxSuppression`, Blind
   included) is sampled but left out of *Result* and reported `heldBack` with its reason.

Sample rate: 48 samples a cycle of the picked FX, at most 1024 a row; at most 96 rows (elements)
and 8 FX a request; the answer says when it truncated. Slider and colour samples go as base64 bytes
per row (one byte a sample; colour as three planes plus W, A, UV where present), position as two
planes, Steps as step indices beside the step labels.

### 3.3 The preview route (D7, D8, D9)

`POST /projects/{id}/fx/preview`, current project only (it needs the rig and the programmer), both
roles, no write:

```
{ targets: [TargetRef…],            // the selection, in its order
  fx: [ {fxId} | {draft: AddFxRequest fields, targets?} | {fxId, draft} ],
  include: "STACK" | "ONLY",       // STACK: every FX reaching the targets, plus `fx`; ONLY: `fx` alone
  window: { cycles?: n, beats?: n, seconds?: n },  // of the first `fx` entry
  solo?: fxId }
```

`{fxId, draft}` previews a running FX with the editor's unsent move, so the graph follows a
finger before the 50 ms floor has sent it. The answer:

```
{ window: {beats?, seconds?, samples, sampleMs},
  clock:  [{speedMasterUuid | "seconds", tickAtStart | scaledMsAtStart, msPerBeat}],  // the playhead
  rows:   [{key, fixtureKey, label, element?}],                                    // graph order
  lanes:  [{family, attributes: [{attribute, kind,
             underneath, result, fx: [{fxId|draftIndex, output, heldBack?, heldBackReason?}]}]}],
  fx:     [{fxId|draftIndex, family, attribute, offsets: [..], starts: [..], holding: [[from,to]..],
            slots, ladder: {order: [..], segments: [..], wings: [..], parts: [..], spread: [..]},
            representative?}],
  truncated? }
```

400 `FX_PREVIEW_INVALID` names every problem at once (an unknown FX, a draft the write boundary
would refuse, a window over the cap); 409 off the current project.

As shipped (session 1b), the route speaks the wire's final names and differs from this sketch in a
few places; `fx-engineering.md` §"Previewing an FX" is the shape the client is built on. An FX is
`fxId`; `solo` is an index into `fx` (a draft has no id); an answer's per-row FX output names its FX
by index into the answer's `fx`, whose `entry` is its place in the request; `heldBackReason` alone says
an FX is held back; rows carry `rigIndex`, grouped by fixture; the window's `beats` are always
answered; and a `seconds` clock carries no start time, its playhead running from sample 0.

The client asks again, latest request wins, when the selection changes, on `fxChanged` for an FX in
the list, after an `updateFx` it sent is answered, on `speedMasters.changed` for a master in `clock`,
and on `provenanceState` for a row's key (the underneath moved). The playhead advances from `clock`
on the client, as a cue's fade does, and resyncs on `speedMasters.beat`.

### 3.4 One compose pass (D10)

The beat loop's wake (any master's tick) and the 20 ms wall-clock ticker run **one** pass: reset
every covered key once, then fold every running FX, beat and wall-clock, in apply order, in one
transaction. A beat FX reads its master's current tick, which is a pure function of the clock, so a
pass woken by the ticker paints it where the last tick left it; a wall-clock FX advances its
accumulator by the real time since its last pass, as it does now. Passes are coalesced to at most
one per 5 ms. This closes `FU-PERF-FRAME-TXN-UNIFY` and corrects `docs/fx-engineering.md` §"Beat ↔
Wall-Clock interaction". The tick-failure counter (`MAX_CONSECUTIVE_TICK_FAILURES`) and the Runs
bookkeeping keep their per-loop meaning by recording which clock woke the pass.

### 3.5 Apply order (D4, D5)

- **The band.** Programmer layers' FX stay at `BASE + layer index`. Local FX move to
  `BASE + LOCAL_OFFSET + localRank`, `LOCAL_OFFSET = 200_000`, above the rank clamp, so every Local FX
  sits above every layer's FX. `isProgrammerFxPriority` is unchanged. This is a behaviour change: a
  pressed FX palette that used to win over a Local FX on the same key now sits under it.
  `fx-engineering.md` says so where it describes the band.
- **The rank.** A new Local FX takes the top of its key's lane (`localRank` one above the highest
  Local FX sharing an attribute with it). `programmer.moveFx {fxId, above: fxId | null}`
  re-ranks it below `above`'s FX (null: the bottom), answered by `fxChanged(UPDATED)` for each FX whose
  priority moved; an FX not Local is refused (`fxError FX_MOVE_NOT_LOCAL`). An operator gesture, so
  `FU-AUTH-WS-PER-MESSAGE` does not fire; a row in `TraceSampling.WS_MESSAGES`.
- **Stored.** Record writes a cue's FX `sort_order` and a Look's `look_fx.sort_order` in
  apply order; the spawners add a home's FX in that order, so `composeOrder` matches. `sort_order` on
  Look FX was already portable, so no `formatVersion` moves; `RichProjectFixture` sets a non-default
  order, the round-trip test reads it back. An archive without it reads FX in file order, as now.
- **Undo.** Moves are programmer history steps (undo plan §3.6) once that plan lands; `updateFx`
  joins its one-second continuous-gesture merge.

### 3.6 Stacking a press (D6)

The bare FX press gains `stack: true`: the FX is added over the selection, no other Local FX is
stopped or narrowed, and an absent blend or Amount takes the family's stacking start
(`fx/FxStacking.kt`, one table: Intensity `MULTIPLY` at 100 %, Position `ADDITIVE` with both centres
at 128 at 100 %, Colour and Beam `OVERRIDE` at 50 %). The answer is `{fxId, stopped: 0,
narrowed: 0}`. No MCP tool presses a bare FX (`apply_palette`, `clear_fx` and the stored FX on
`create_look` and `create_cue` are the FX tools), so none takes `stack`; agreed with Chris during
session 3a.

## 4. UX

### 4.1 Where it lives (D1)

The FX tab's row B gains *Selection · All* before its chips; *Selection* is the default and is kept
per window. *All* is today's table unchanged, except that a row's press selects that FX's heads (the
window's selection, or the desk's while it follows) and switches to *Selection* with the FX picked.

The subject is the window's pair (`useWindowFxPair`), as `+ FX` reads it today. With nothing
selected the list says so and offers *Select the heads of a running FX…*, a menu of the running FX
by name.

### 4.2 Widths

- **From 1100 px of table**: list 248 px · graph · inspector 340 px.
- **760-1099 px**: list · graph; picking an FX opens the inspector over the graph's right half as a
  side `EditorSurface`, the graph narrowing to stay visible beside it.
- **Below 760 px** (phone, a narrow window): one column. The list folds to a row of lane chips; the
  graph is a strip of the picked FX's lane at 6 rows (scrolls for more); the inspector follows below.
  Handles (D12) are not offered under a coarse pointer.

### 4.3 The list (D3, D4, D6)

- **Header**: the subject (*Hex 1–8 · 8 heads*, *Slender 1–2 · 8 elements*), the family pill, the
  desk chip, as the Values grid's row C has them.
- **A lane**: the family's word and glyph, its FX as cards, then *Underneath · Q12 Warm 60 %* (or
  *Local 80 %*, *Base*), then *+ FX*. A lane folds to its header.
- **A card**: drag grip (Local only), name, attribute where the family has several (*UV*, *Zoom*),
  *1 bar · M2*, the blend in the family's words (*Replace*, *Within*, *Around*, *Add*, *Max*, *Min*),
  an Amount bar, Direction glyph and Runs where not default, a coverage mark where it reaches only
  some of the selection (*5 of 8*), and the held-back and *held · Blind* marks the tray uses. Its
  verbs: Solo, pause, ×. A layer's card says *Night · pressed* and has no grip; a cue's card says
  *on Q12* and opens the cue editor, as the tray's row does.
- **+ FX** opens the picker (`FxPicker`, the press arm) in the inspector column, on that lane's
  family. Where the lane already holds a Local FX on any selected head, a segment above the list
  asks ***Replace* · *Stack***, starting on *Replace*; the picker's tap-to-audition is unchanged under
  either.
- **Footer**: *Record as Look…* (`RecordLookSheet`, mask the lanes' families, FX ticked), and the
  count of FX in the list.

### 4.4 The graph (D8, D9, D12, D14)

- **Toolbar**: the window (*1 cycle · 2 cycles · 2 bars · 8 bars*), *Result · This FX*, *Colour as
  seen* (the colour lane multiplied by the Intensity result), the master's BPM with a tap button
  (`speedMasters.tap`), pause of the playhead (the graph's, not the stage's).
- **Rows**: one per element in the picked FX's Order (*Normal* rows are rig order); a fixture with
  elements draws its name once and its elements beneath it; *Rig order* in the toolbar holds the rows
  still while the Order changes. Over 24 rows a lane draws as a heat map, 6 px a row.
- **Lanes**, each with its family's word:
  - **Intensity**: an area per row, 0–100 %, *Underneath* as a dashed line.
  - **Colour**: a strip per row, as the colour, or as seen.
  - **Position**: pan and tilt as two thin traces per row, centre-relative; beside the lanes a
    **path** panel draws each element's pan/tilt path with a dot at the playhead.
  - **Beam**: an area per attribute per row.
  - **Steps**: blocks per row, each labelled with its palette or slot.
- **Marks**: a tick where each row's cycle starts; brackets at the left for Segments and Parts; a
  mirror mark between Wings; hatching where an element holds (Runs, before its offset comes round);
  a held-back FX's own lane drawn hatched under *This FX*; *representative* on a stateful FX.
- **Hover** shows each row's value at that moment, and its phase.
- **Handles** (pointer only): the first cycle's right edge (Cycle), the line joining the rows' start
  ticks (Spread), a row block dragged sideways (Phase), the max and min lines of an Intensity FX
  that has them. Each writes through the inspector's draft and `useLivePush`, so the floor and
  Revert are the live editor's.
- **Solo** draws the lane as the picked FX alone over *Underneath*; a solo'd card is marked, and the
  toolbar says *Solo: Dimmer Strobe*.

### 4.5 The inspector (D9, D11, D13)

`FxEditor` over `FxFields` as the terminology plan's §6 leaves them: *Cycle · Amount ·
Direction · Order* on top, then **Shape** (the FX's parameters), **Spread** (Shapes, Spread %,
Wings, Segments, Parts, the offset ladder), **Over what's underneath** (the family's question; the
raw blend for any FX), **Advanced** (Phase, Timing, Runs, element mode, element filter). The phase
dials leave the Spread section: the graph and the ladder draw the same offsets with time in them.

- **Shapes** are chips over the Spread fields, each with a 40 × 16 drawing of its offsets; the lit
  one names the fields' current values, *Custom* when none matches.
- **The ladder**: five dot rows, one per spread step, an element a dot at its offset; hover links a
  dot to its graph row.
- **Match**: a menu on Cycle and on Spread, *Same as…* listing the other FX in the list.
- Hovering a control lights what it changes on the graph: Cycle the first cycle, Spread and Order
  the start ticks, Wings the mirror, Segments and Parts the brackets, Runs the hatching, Amount the
  gap between *This FX* and *Result*.

Revert, Done, *Update palette* and *Reset to palette*, the split banner (*Apply to Hex 1–2 · Edit
all 4*), pause and × are the live editor's, unchanged.

### 4.6 Other hosts (D15)

- **The fixture sheet's tray** gains *Open in FX editor* (⤢) on its header: the programmer opens on
  that fixture or group with its FX tab on *Selection*.
- **The FX palette editor** draws the graph strip under its fields, over eight notional heads, or the
  current selection when there is one (*On the selection · On eight heads*). It replaces
  `PaletteRunsOn`'s stripe.
- **The cue editor's FX tab** draws the graph strip for its open FX row over that FX's own targets,
  `include: "ONLY"`; a cue FX's graph cannot say what is underneath at its GO, so it draws over
  nothing and says so.
- **The busk FX tab** keeps its rows and gains the strip under the open one.

## 5. What is not changing

The live editor's rules: no Apply, the 50 ms floor, Revert, the split (apply-model plan D5), *Update
palette* / *Reset to palette*. The bare press's family replacement under *Replace*. A palette holds
one FX. Cue FX are read-only outside the cue. No per-element overrides inside an FX, no waveform
drawing, no Amount over 100 % (`FU-FX-AMPLITUDE`), no live links between FX. The Stage view draws
only what the desk sends.

## 6. Implementation: nine sessions, gated on the terminology plan

The work runs beside the terminology plan rather than after it. Each session names a **gate**: the
terminology sessions that must be on `main` before it starts. A gate is met when that plan's session
heading reads *done* with its commit SHA, and that commit is an ancestor of `origin/main`; a session
in review or in progress does not count. Check the gate before cutting the branch. If it is not met,
the session waits; nothing here is started early and rebased later.

The gates follow one rule: **a session that adds nothing the terminology plan renames can start now;
anything else waits until the names it would use are final.** Engine-internal work renames nothing.
A wire field, a stored column or a client identifier written before its rename is something the
terminology plan then has to rename again, or a conflict with the session that does.

| Session | Gate: terminology sessions on `main` | Also needs | Best landed before |
|---|---|---|---|
| 1a: the sampler | 3 | — | terminology 5 |
| 1b: the preview route | 5 | 1a | — |
| 2: one compose pass | none | — | terminology 5 |
| 3a: the band and Stack | none | — | terminology 5 |
| 3b: apply order kept | 4 | 3a | — |
| 4: the editor's shell | 5, and 6 done or not started | 1b, 3a | — |
| 5: the graph | as 4 | 4 | — |
| 6: Shapes, ladder, handles, Match | as 4 | 5 | — |
| 7: the other hosts | as 4 | 5 | — |

Why each gate is where it is:

- **None (2, 3a).** Both are engine-internal or add a name that is already final
  (`programmer.moveFx`, the `stack` field). Landing them **before terminology session 5** matters:
  that session and session 6 rename identifiers across the whole engine, and a rework of the tick
  loops merges badly with a rename. Their tests use FX that terminology session 3 does not touch
  (Sine Wave, Candle Flicker, Pulse, Circle), never Triangle, Strobe, Breathe, Colour Pulse or Zoom
  Breathe.
- **Terminology 3 (1a).** The sampler runs the FX library as that session leaves it: no composite
  mode, no `pingPong`, the new default Direction, one Centre in.
- **Terminology 4 (3b).** It renames `look_effects` and takes `formatVersion` 38; Look FX order is
  a column on the renamed table.
- **Terminology 5 (1b, and the frontend).** The route takes the FX request's fields and the client
  speaks the wire; before that session they are `beatDivision`, `size`, `nShot`, `EffectDto`.
- **Terminology 6 done or not started (4–7).** Session 6 renames every client identifier that says
  property. The frontend sessions may run before it or after it, never while it is in review: two
  large diffs over the same TypeScript at once is the conflict the gates exist to avoid. After it is
  preferred, since it is mechanical and quick.

### Session 1a: the sampler (backend) — done, `21aca965`

**Gate: terminology session 3.**

- §3.2: `phaseAt`, `FxBlend` split out of `FxTarget`, `FxSampler`. No route, no DTO.
- **Tests**: `FxSamplerParityTest` drives the engine through `FxEngineTickShims` and asserts the
  sampler's *Result* equals the committed DMX at every sampled tick, for: Sine Wave on a group under
  every Order with Segments, Wings, Parts and Spread set; Runs 2 with Direction Bounce; Timing Per head;
  two Intensity FX (Replace under Within); a Circle *Around* a programmer position; Colour Cycle under
  Amount 50 %; Palette Steps over two palettes; a Seconds FX; a multi-element fixture under each element
  mode and filter; a held-back FX. Sampling leaves every instance's fields and the transaction as they
  were (P4).
- **Docs**: `fx-engineering.md` §"Previewing an FX", the sampler half.

### Session 1b: the preview route (backend) — done, `11651d1b`

**Gate: terminology session 5.** Needs 1a.

- §3.3: `POST /fx/preview`, its DTOs in the wire's final names, Swagger.
- **Tests**: a running FX, a draft and a running FX with a draft move each answer the sampler's
  samples; a draft the write boundary refuses is 400 with every problem; off the current project 409;
  the caps truncate and say so.
- **Docs**: `fx-engineering.md` §"Previewing an FX", the route.

### Session 2: one compose pass (backend) — done, `fb8c3421`

**Gate: none.** Best landed before terminology session 5.

- §3.4. Full suites.
- **Tests**: a beat Sine Wave with a wall-clock Candle Flicker *Within* it on one dimmer composes on
  every pass; a wall-clock FX's cycle length is unchanged by the extra wakes; tick-failure counting and
  runs' ends behave per clock as before; once 1a has landed, the parity test gains a mixed pair.
- **Docs**: the corrected §"Beat ↔ Wall-Clock interaction"; `FU-PERF-FRAME-TXN-UNIFY` to Completed.

### Session 3a: the band and Stack (backend) — done, `4e01c253`

**Gate: none.** Best landed before terminology session 5.

- §3.5's band, `localRank` and `programmer.moveFx`; §3.6's `stack: true` and `fx/FxStacking.kt`.
  Nothing stored. The client's types and the store's mutation for the frame and the field; no UI.
- **Tests**: a Local FX sits above a layer FX on the same key; a move re-ranks and a second move
  undoes it; a non-Local FX's move is `FX_MOVE_NOT_LOCAL`; `stack: true` stops nothing and starts on
  the family table. No MCP schema: no tool presses a bare FX (§3.6).
- **Docs**: `fx-engineering.md` §"Programmer suppression and the priority band", with the behaviour
  change, and §"FX iteration order" (which still says `id` breaks ties; it is `composeOrder`).

### Session 3b: apply order kept (backend) — done, `402cac92`

**Gate: terminology session 4.** Needs 3a.

- §3.5's stored half: Record's order into both homes and the spawners' order. The Look FX table
  already had `sort_order` and sync already carried it, so no column and no `formatVersion`. Full
  suites.
- **Tests**: Record writes both homes' order and a Look spawns in it; the v38 archive imports in file
  order; `RichProjectFixture` carries a non-default order through the round trip and the clone.
- **Docs**: `sync-engineering.md`, the paragraph on an order that needed no version, before
  §"Version 38"; `fx-engineering.md` §"Programmer suppression and the priority band".

### Session 4: the editor's shell (frontend) — done, `6331f660`

**Gate: terminology session 5, and session 6 done or not started.** Needs 1b and 3a.

- §4.1–4.3 and §4.5 without the graph: *Selection · All*, the list with lanes, cards, drag, *Underneath*
  (from `programmer.keyStack`'s top value per lane), *+ FX* with *Replace · Stack*, Solo as a mark, the
  inspector, the widths.
- **Tests**: the list's order is the desk's apply order; a drag sends one `programmer.moveFx`; a cue FX
  card opens its cue; *Stack* sends `stack: true` and *Replace* does not; *All*'s row press selects and
  switches; the 760 and 1100 px arms.

### Session 5: the graph (frontend) — done, `0de1adcc`

**Gate: as session 4.** Needs 4.

- §4.4: one canvas per lane, drawn from the answer; rows, marks, hover, *Result · This FX*, *Colour as
  seen*, the window, the playhead and its resync, the path panel, heat-map rows, refetching per §3.3.
- **Tests**: a fixture answer draws the expected pixels at three sample points per lane; a request is
  sent on each trigger in §3.3 and a stale answer is dropped; the playhead's position from `clock`;
  reduced motion holds it still.

### Session 6: Shapes, the ladder, handles and Match (frontend) — done, `166c5629`

**Gate: as session 4.** Needs 5.

- §4.5's Shapes, ladder, Match and hover lights; §4.4's handles.
- **Tests**: each Shape writes its fields in one `updateFx` and lights from them; a handle's drag
  writes through the floor and its release lands; Match copies once.

### Session 7: the other hosts (frontend)

**Gate: as session 4.** Needs 5.

- §4.6, and the phone arm of §4.2.
- **Tests**: the palette editor's strip asks with a draft and redraws on a field change; the tray's ⤢
  lands on the programmer with the target selected.

## 7. Migration

None for the database: `look_fx.sort_order` defaults to 0 and existing Looks keep their id order.
An archive whose Look FX carry no order reads them in file order.

## 8. Explicitly out of scope

- Drawing FX on the Stage view from the graph, or scrubbing the stage to the playhead
  (`FU-FX-STAGE-SCRUB`).
- Fades in the graph: a cue's crossfade, a press's grow, Blind's release.
- Per-element overrides inside one FX (declined in the effects and apply-model plans).
- A live link between two FX's cycles or spreads (`FU-FX-LINKED-TIMING`).
- A Look's FX edited in place outside Include.

## 9. Follow-ups to record

- **`FU-FX-STAGE-SCRUB`** (session 5): the Stage view draws the graph's moment. Trigger: an operator
  scrubs the graph and looks at the stage.
- **`FU-FX-LINKED-TIMING`** (session 6): Match as a stored link. Trigger: an operator matches the same
  pair again after changing one.
- **`FU-FX-PREVIEW-FADES`** (session 5): draw a cue's fade under the graph. Trigger: an operator reads
  the graph across a GO.

## 10. Verification

At the desk on Sleeping Beauty:

- **Session 1a.** Nothing at the desk changes; `FxSamplerParityTest` is the check.
- **Session 1b.** In Swagger, preview a Sine Wave over a group with Spread 50 % and Segments 2; the
  answer's start offsets pair neighbours; the stage does not move.
- **Session 2.** Sine Wave and Candle Flicker *Within* it on one head: the head flickers inside the
  wave, steadily, never one or the other.
- **Session 3a.** Press a palette with an FX, then a Local Pulse on the same heads: the Pulse wins.
- **Session 3b.** Stack two Local FX, reorder them, Record a Look; press it; the order holds.
- **Session 4.** Select Hex 1–8 with a Sine Wave and a Dimmer Strobe stacked *Within*; the list shows both,
  top first; drag the Sine Wave above; the stage changes.
- **Session 5.** The graph's playhead crosses Hex 1's peak as Hex 1 peaks on the rig.
- **Session 6.** *Pairs* then *Mirror*; the graph and the rig agree.
- **Session 7.** A Circle palette's strip shows eight circles before it is pressed.

## 11. Questions

Answered by Chris, 2026-10-23, one round of fifteen, each as recommended:

1. **Where does the editor live?** The FX tab's *Selection* view, beside *All* (D1). A fourth rail
   tab (too narrow for a graph) and a route of its own (losing row C, the selection and Record) were
   declined.
2. **Beat and wall-clock FX on one key?** Fix the engine with one compose pass (D10, session 2),
   accepting a session on the tick path and wall-clock FX evaluated on beat wakes, coalesced. Warning
   only, and refusing mixed stacks, were declined.
3. **A second FX of a family on the same heads?** Ask *Replace · Stack*, starting on *Replace* (D6).
   Always stacking (which reverses the apply-model plan's D5 rule and piles up auditioned FX) and a
   modifier key (invisible, and awkward on touch) were declined.
4. **The blend a stacked FX starts on?** The family table in §3.6. The FX's own starting blend
   (which makes *Stack* look like *Replace*) and remembering the last choice per window were
   declined.
5. **Apply order editable and kept?** Yes (D4): `programmer.moveFx`, and Record keeps the order, Look
   FX gaining `sort_order` at `formatVersion` 39. Editable but not recorded, and creation order drawn
   but fixed, were declined.
6. **Local FX above layers' FX?** Yes (D5), accepting the behaviour change in §3.5. Keeping today's
   ranks, and interleaving Local and layer FX by press time, were declined.
7. **Where does the graph's data come from?** The desk's sampler (D7). A client copy of the
   waveforms (no user FX, no per-head Steps, every rule twice) and a recording of the live output
   (no drafts, no preview before a change) were declined.
8. **What is underneath in the graph?** What the attribute holds now (§3.2 step 5). A flat zero
   (which misdraws *Within* and *Around*) and a level the operator picks were declined.
9. **Rows in which order?** The picked FX's Order, with *Rig order* to hold them (§4.4). Always rig
   order, and rig order by default with an Order toggle, were declined.
10. **Handles on the graph in the first version?** Yes, all four in §4.4: Cycle, Spread, Phase, and
    min and max. The cycle handle alone, and none, were declined.
11. **Shapes?** The fixed nine (D11). A user-saved list (a new portable table for five numbers) and
    no Shapes were declined.
12. **Match?** Copy once (D13); the stored link is `FU-FX-LINKED-TIMING`. A stored link now, and no
    Match, were declined.
13. **Solo?** The graph's only (D14). A stage mute (new runtime state, a frame and a held-back
    reason) and no Solo were declined.
14. **The editor's other hosts?** The palette editor, the cue FX tab and the busk FX tab get the
    strip, and the tray links to the editor (D15). The programmer only, and the programmer plus the
    palette editor, were declined.
15. **Names?** *FX editor*; *list*, *graph*, *inspector*; *Shapes*; *offset ladder*; *Match*, all
    entering `docs/glossary.md`. *Scope* (taken by the programmer's scope) and *Phaser* (grandMA3's
    word) were declined; *Timeline* is the stack view's.
