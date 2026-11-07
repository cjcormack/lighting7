# FX speed: a Speed slider, an own tempo, and tempo in the programmer

> **Document status: READY — written 2026-11-06 from the page Chris and Claude agreed that day.**
> Four sessions, each one PR, each stacked on the one before while that one is unmerged, each run by
> `/next-session` (§5). The page and the record are in
> [`fx-speed-design/INDEX.md`](fx-speed-design/INDEX.md).
>
> This document is the engineering half. Where it and the page disagree, this plan wins on
> behaviour and the page on layout and copy.

## 1. Context

Chris's brief, 2026-11-06: tying an FX's speed to a bar or a beat is clunky and hard to understand,
when what an operator wants is a speed slider with snaps. *Seconds* in place of a speed master is not
much use: you want to tap that value. And a cue's tempo is a useful idea but unnatural to programme:
you want to set it in the FX editor and have it land in the programmer, so Record stores it.

What the code says, read at `91c466a`. `F/` is `frontend/src`, `K/` is
`src/main/kotlin/uk/me/cormack/lighting7`.

- **An FX's timing** is `cycle` in beats (`CycleLength`, a new FX one bar, venue-visit plan D16), a
  master (`speedMasterUuid`, null master 1) and, on a wall-clock FX, a rate master
  (`rateSpeedMasterUuid`, scaling its seconds by `bpm / 120`). *Seconds* (venue-visit D7) is a flag,
  `seconds`, on every FX home (`FxInstance`, `palette_fx`, `look_fx`, `cue_ad_hoc_fx`; `LookFxSpec`,
  `StoredFx`, `LookFxSettings`): a beat FX on Seconds reads `cycle` as seconds and runs on the
  wall-clock loop with no master. `K/fx/FxTimingChoice.kt` holds the rules (`applyTimingChoice`,
  `secondsTimingConflict`, `familyMasterUuidFor`). A move between Seconds and a master is a swap and
  restarts the phase. Synced from `formatVersion` 29. `docs/fx-engineering.md` §"Seconds".
- **The editor** draws timing in `FxFields`' `TimingStrip` (`F/components/fx/FxFields.tsx`): a
  `CycleChip` opening `SPEED_STEPS`' eight labels, ⅛ beat to 4 bars (`F/components/fx/fxConstants.ts`),
  a typed field and *Same cycle as…*; a `TimingChip` of *Seconds* and the masters; Direction; Runs.
  `getCycleLabel` speaks a cycle, mostly through `fxSpeedLabel(cycle, timingSource, seconds)` and
  `secondsLabel` (same file), on every chip: `FxSummary`, `AppliedFxTable`, the Timeline
  (`F/lib/stackTimeline/fxRuns.ts`), the fixture sheet (`fxLabels.ts`, `LayerStack.tsx`), the fixtures
  table and its lens (`FixturesTable.tsx`, `CellFxLens.tsx`), busk FX pads (`padFace.ts`,
  `FxPadDetail.tsx`), the palette editor, `PaletteValue` and `PaletteRunsOn`, the cue FX tab and
  `NewPaletteFromFxSheet`. The graph's Cycle handle snaps to the eight steps
  (`F/components/fxEditor/fxGraphHandles.ts`, `GraphHandles.tsx`).
- **A beat FX's phase** is a pure function of its master's tick (`K/fx/FxPhase.kt`'s `beatPhaseAt`,
  anchored at tick 0 for a forever FX, at its start for one with runs), so editing `cycle` moves the
  phase at once: a live slider would jump the FX on every frame. A wall-clock FX's phase is its
  scaled accumulator modulo its cycle, so a rate change is continuous and a cycle change is not.
- **Masters** are `SpeedMasterBank` (`K/fx/SpeedMasterBank.kt`): `setBpm(uuid, bpm, source)` and
  `tap` (`TapTempo`, the last four taps, two seconds' timeout) answer a `TempoWriteOutcome`, a
  follower refusing. The stored `bpm` is the starting tempo, written through after live changes on a
  750 ms debounce. The busk rail is `F/components/busking/BuskSpeedRail.tsx`, the manage page
  `F/components/SpeedMasters.tsx` and `F/components/speedMasters/SpeedMasterSheet.tsx`, the detail
  sheet `SpeedMasterDetailSheet.tsx`. Surfaces drive masters through `BindingTarget.SpeedMasterBpm`
  and `SpeedMasterTap` (`K/midi/BindingTarget.kt`).
- **A cue's tempos** are `cues.speed_masters` (`models/cueSpeedMasters.kt`), written to the bank on
  every GO into the cue and every loose press, never on a reload (`K/fx/CueTempo.kt`); edited by hand
  in the Cue tab (`F/components/cues/editor/CueSpeedMasters.tsx`); synced from `formatVersion` 35.
  Record, Include and Update never touch them.
- **The programmer's scenery** (`K/state/ProgrammerScenery.kt`, scenery-programmer plan) is the
  precedent for runtime state the programmer holds and Record captures: its frames, Blind staging,
  Clear, `changedSinceInclude`, `routes/programmerSceneryRecord.kt`, and in the client
  `ProgrammerSceneryList.tsx`, `RecordSceneryRow.tsx` and `useIncludeBaseline.ts`. Programmer undo is
  `K/history/ProgrammerHistory.kt`.
- **Migrations:** `PRAGMA user_version` is at 11 (`CueNumberMigration`); sync is at `formatVersion`
  48 (`SUPPORTED_FORMAT_VERSION`, `K/sync/ProjectImporter.kt`).

## 2. Decisions

| # | Decision |
|---|---|
| D1 | **Speed is how fast an FX runs against its tempo.** ×1 is one cycle a bar; each snap doubles or halves it. It is a view of `cycle`, `speed = 4 ÷ cycle` on a beat FX, so no stored beat FX changes. The slider's track runs ×1/16 to ×16 on a log scale, labelled at ×¼ ×½ ×1 ×2 ×4 with ticks at ×1/16 ×⅛ ×8 ×16, sticks at every power of two (⌥ slides past), and ends in a typed value taking a fraction (`⅔`, `2/3`) or a decimal, from ×1/512 to ×32 (a cycle of 2048 beats to today's ⅛ beat, and a 600 s Seconds FX after D5). A beat FX runs its cycle as whole ticks, 24 a beat (`FxPhase.beatCycleTicks` truncates the rest), so the editor writes a cycle rounded to whole ticks and the readout and chips say the speed that runs (×5 is 19 ticks, ×5.05). An FX whose Speed is off the track shows its thumb at that end beside its value, and nothing is written until the thumb is dragged. |
| D2 | **The readout says the cycle in seconds** at the tempo the FX runs at, live, and in bars or beats where the Speed is a power of two: *1 bar · 1.82 s a cycle at 132 BPM*. Chips read `×1 · M1`, `×½ · Own 96`; a chip's hover gives the seconds. |
| D3 | **Own tempo replaces Seconds.** An FX runs on a speed master or on its own tempo, `ownBpm` (20–300), stored on every FX home in place of `seconds`. The same Speed applies to it. On Own, the editor's Tap and BPM field set the FX's own tempo and no other FX moves. A new Own tempo starts at the tempo the FX was running at, so moving onto it keeps the speed. |
| D4 | **A wall-clock FX** (`timingSource: WALL_CLOCK`) keeps its `cycle` in seconds. The two that ship, Candle Flicker and Fluorescent Flicker, read neither their cycle nor a rate master (`FU-SPEED-RATEMASTER-STATEFUL`), so they declare it in frontmatter (`ownPace: true`) and their Speed section shows no tempo chip, Tap or slider, only *Runs at its own pace*. The gate is that flag, not `fxMode`: a user's stateful FX that reads its phase does scale, which is why the FU kept the control, and it keeps it. A rate master already stored on either is kept, still inert, and not shown. Any other wall-clock FX reads Speed against its rate master or **Natural**, a fixed 120 BPM with no Tap, as `speed = 2 ÷ cycle seconds`: ×1 is then one bar at the running tempo on either, as on a beat FX. Today's default for a new one, one bar read as four seconds, reads ×½. `ownBpm` on a wall-clock FX is refused. |
| D5 | **Seconds rewritten once, exactly.** A beat FX on Seconds of *s* seconds becomes Own tempo 120 at `cycle` *2s* beats: the same output, by the rule venue-visit D7 runs today (the wall-clock loop at a rate of `ownBpm / 120`, a cycle of `cycle ÷ 2` seconds). A wall-clock FX with the flag loses it, which is Natural. A one-shot database step, the importer for an older archive, and the FX routes and MCP tools, which still read `seconds` and convert: with a `cycle`, as the rewrite does; without one, onto Own tempo at the master's running tempo with the cycle kept, by D3. |
| D6 | **A speed change never jumps.** Changing a running FX's Speed keeps its **elapsed cycles**, whole and fraction, so its phase, its run count and Bounce's parity are all unchanged. A forever beat FX that lands on a power of two then glides back onto its master's grid within one cycle (a Bounce FX onto a two-cycle grid, so its forward cycles start where an unmoved one's would), so at a snap each cycle starts on a bar or a beat again. An FX with runs, and an Own-tempo FX, which has no master grid, keep their cycles and do not glide. A tempo change on a master or an Own tempo is continuous already. A move between a master and Own tempo still restarts the phase, as it changes the loop the FX runs on. |
| D7 | **Tempo is the word for a master's rate**, on screen; Speed is the FX's. The rail, the master sheets, the binding picker and the Cue tab say Tempo. `bpm` stays the wire's and the code's. |
| D8 | **The programmer holds tempos.** A held master runs at its held tempo at once. A tap or a typed tempo in the FX editor holds that master. Where the Speed section edits a stored record rather than the programmer, Tap goes elsewhere: in the cue FX tab it sets that master's row of the cue's *Tempo on GO* (adding one) and sends nothing live; in the palette editor, which has nowhere to keep a tempo, it is a live tap. *Capture* on a rail card holds a master at the tempo it is running at. (The rail's verb is *Capture*, since the glossary's *Hold* is a Pulse's hold at the top.) Each hold is released by ✕ on its programmer row, *Release* on the rail, or Clear. |
| D9 | **Live and held.** The bank keeps each master's **live** tempo, the last a GO, a press, a tap, a typed tempo or ×½/×2 gave it, and runs a hold over it. A GO that sets a held master moves its live tempo and does not run it. **A tap, typed tempo, ×½ or ×2 on a held master, from the rail, a surface or MCP, moves the hold**, so a tap always changes what is on screen. The master sheet's tempo (`PUT /speed-masters/{mid}` with `bpm`) is the starting tempo: it writes the stored row and the live tempo, as today, and on a held master moves the hold too. Release lands the live tempo. The debounced starting-tempo write-through follows the live tempo, never a hold. A follower refuses a hold as it refuses every tempo write, and a held master linked as a follower loses its hold. |
| D10 | **Record, Include and Update carry tempo.** Record writes the held tempos into the cue's *Tempo on GO*: a new cue takes the held list, a merge replaces the rows of the held masters and keeps the rest, a Remove deletes the rows of the held masters, and a Replace acts as a merge, as the scenery Record does (`routes/programmerSceneryRecord.kt`); the attribute mask and *Selected fixtures only* do not narrow tempo, which names no fixture; Record's sheet has a Tempo row, on by default, to leave them out, as the Scenery row does. Include holds the cue's tempos and sets the baseline; Update writes the holds back, and `changedSinceInclude` counts a held tempo the cue does not say. Record into a Look or a palette records no tempo. |
| D11 | **Blind stages holds**: the running tempo stays, the rail shows the staged one beside it, and leaving Blind lands them. A rail or surface tap in Blind on a master whose hold is only staged moves its live tempo, which is what is on stage, and leaves the staged hold to land. A hold is a programmer step for undo. A project switch drops the holds; a carried reload keeps them, less any whose master the reload removed. |
| D12 | **×½ and ×2 on every master**: on each rail card, in the detail sheet, and as a surface button target, `speedMasterScale {masterUuid?, factor: 0.5 \| 2}`. Each is a tempo write by D9, disabled where it would leave 20–300 and on a follower. A tap afterwards sets the beat it hears (grandMA3's Learn default) and keeps no factor. |
| D13 | **FX speed on a surface**: a binding target, `fxSpeed`, moving the Speed of every Local FX that paints a head of the desk selection, each whole: `applyFxUpdate` with no `targets`, so nothing splits and a Local FX partly inside the selection changes on every head it paints. A programmer layer's FX is not a Local FX and is left alone. An encoder moves each a quarter of an octave a detent, each on its own, rounded to whole ticks (D1); each sticks at its own powers of two, so FX not a power of two apart keep their ratio only between snaps. A fader sets every one from ×¼ to ×4 with soft takeover against the **lead FX**'s Speed, read as the nearer end when it lies outside that range: the lead is the Local FX painting the first selected head in rig order, the lowest Local rank where two do. The ring shows the lead's Speed. Phase kept by D6. |

The plan adds three of its own:

- **P1 — Four sessions, four stacked PRs**, in §5's order, each run by `/next-session`
  (`.claude/skills/next-session/SKILL.md`) through `/verified-ship`. A session stacks on the previous
  session's branch while that PR is open, and on `main` once it has merged.
- **P2 — The desk owns the rules.** Speed's arithmetic (D1, D4), the readout's seconds and the snap set
  live in one client module, `F/lib/fxSpeed.ts`, pinned to the desk's by a shared vector
  (`src/test/resources/fx/fxSpeed.fixture.json`) where the desk computes the same thing (D6's snap,
  D13's encoder). The window counts an Own tempo's taps (`F/lib/tapTempo.ts`) by `TapTempo`'s rules,
  pinned by the same vector, and so does the cue FX tab's Tap on a master, which sets a stored *Tempo on
  GO* row and reaches no desk clock (D8). Every other tap on a master stays the desk's.
- **P3 — No output moves on migration.** D5's rewrite is held to the old output by a test that runs a
  Seconds FX before and after over a grid of cycles, phases, directions and runs, to the DMX step,
  as `LegacyEquivalenceTest` held the FX library consolidation.

## 3. The model

### Speed (D1, D4)

| FX | Tempo | `speed` | Cycle in seconds |
|---|---|---|---|
| Beat FX on a master | the master's `bpm` | `4 ÷ cycle` (beats) | `cycle × 60 ÷ bpm` |
| Beat FX on Own tempo | `ownBpm` | `4 ÷ cycle` (beats) | `cycle × 60 ÷ ownBpm` |
| Wall-clock FX on Natural | 120, fixed | `2 ÷ cycle` (seconds) | `cycle` |
| Wall-clock FX on a master | the master's `bpm`, as its rate | `2 ÷ cycle` (seconds) | `cycle × 120 ÷ bpm` |
| Stateful wall-clock FX (both shipped) | none | none shown | its own |

An Own-tempo FX runs as a Seconds FX runs today: the wall-clock loop, `rateScale = ownBpm / 120`,
cycle `cycle ÷ 2` seconds. A tap moves only the rate, so it never jumps the phase.

### Live and held tempo (D8, D9)

| Write | Master not held | Master held |
|---|---|---|
| FX editor Tap or BPM, `programmer.setTempo` / `.tapTempo`, MCP `set_bpm` with `hold` | holds it | moves the hold |
| `programmer.holdTempo` (*Capture*) | holds it at its live tempo | nothing |
| A rail or surface tap, typed tempo, ×½ / ×2, MCP `set_bpm` | moves live, runs it | moves the hold |
| The master sheet's `PUT` with `bpm` | writes the stored row and live, runs it | writes the stored row and live, moves the hold |
| A cue's GO or press | moves live, runs it | moves live; the hold keeps running |
| ✕, *Release*, Clear | | the live tempo runs |
| Linked as a follower | | the hold is dropped |

### Wire

- **FX homes:** `ownBpm: Double?` on `FxDto` and the group and indirect FX DTOs, `AddFxRequest`,
  `AddGroupFxRequest`, `UpdateFxRequest` (null in an update means no change; naming a master leaves
  Own), `LookFxSpec`, `StoredFx`, `LookFxSettings`, `PaletteFxDto`, `CueAdHocFxDto`, and the sync
  JSON (`LookFxJson`, `PaletteFxJson`, `CueAdHocFxJson`); column `own_bpm` on `palette_fx`, `look_fx`,
  `cue_ad_hoc_fx`; `seconds` dropped from the columns and DTOs, still read on a request and converted
  (D5). `ownBpm` beside a master is refused as `secondsTimingConflict` refuses today, and on a
  wall-clock FX (D4). A wall-clock FX goes back to Natural with `natural: true` (a null rate master
  means no change), which `FxDto` and its kin report beside `ownBpm`; `seconds: true` on a wall-clock
  FX is read as it.
- **MCP** speaks a cycle as today: its FX tools take `cycle` and `ownBpm`, not a Speed, and say what
  ×1 means in their descriptions.
- **Frames:** `programmer.setTempo {speedMasterUuid, bpm}`, `programmer.tapTempo {speedMasterUuid}`,
  `programmer.holdTempo {speedMasterUuid}`, `programmer.clearTempo {speedMasterUuid?}`, answered and
  broadcast as `programmer.tempoState {projectId, masters: [{speedMasterUuid, bpm, staged?, released?}],
  changedSinceInclude?}` (StateFlow-backed, refusals as `programmer.error`); `speedMasters.scale
  {speedMasterUuid, factor}`. Each inbound frame has its `TraceSampling.WS_MESSAGES` row.
- **Bindings:** `BindingTarget.SpeedMasterScale(masterUuid?, factor)` and `BindingTarget.FxSpeed`.

## 4. UX

The page is the authority on layout and copy: the Speed section and its notes, the tempo chip's menu,
the programmer's Tempo rows, Record's Tempo row, the rail card with *Held*, *Capture*, *Release*, ×½
and ×2, and the labels table. Five things it does not show or the plan changes:

- **The rail's "set by cue 11"** line on the mock is out: a master's source does not carry the cue.
- **Touch**: the tempo chip opens a bottom sheet below the editor's 760 arm, with 44px rows; Tap is at
  least 44px on the phone host (`useFingerSized`). The slider's snaps stick under a finger as under
  ⌥-less pointer drags; the typed value is how a touch user goes between them.
- **Focus**: the slider moves a quarter of an octave per arrow key and a whole one with Shift; Enter
  in the typed value commits, as `SheetField` does. Tap answers Space and Enter like a click.
- **The graph**: its Cycle handle becomes a Speed handle, dragged on the same log scale and snapping to
  powers of two; its hover reads *×2 · 0.94 s*.
- **No ×½ or ×2 in the FX editor.** The page's live model puts them beside Tap to show them working;
  D12 puts them on the rail, the master sheet and surfaces only. The editor has Tap and the tempo
  field.

## 5. Implementation — four sessions

Each session is one PR. Each is run by `/next-session`, which cuts the branch, stacks it on the
previous session's branch while that PR is open (on `main` once merged), records the previous
session's PR and head SHA in the table below, and hands the session to `/verified-ship`. A session
ends with its checks green (§"Pre-commit checks" in the root `CLAUDE.md`; session 1 also runs the
full `./gradlew test` and `npm run check`, as a migration and a `formatVersion` bump require), the
docs it names written, and its row marked *in review* with its branch. A session's PR is titled
*FX speed: session N — title*. Never merge a later session's PR before an earlier one.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | Own tempo on the desk | merged | `claude/quirky-faraday-k08oa7` | [#374](https://github.com/cjcormack/lighting7/pull/374) | `0082602` |
| 2 | Tempo in the programmer | merged | `claude/fx-speed-0zrqvu` | [#376](https://github.com/cjcormack/lighting7/pull/376) | `b3a9373` |
| 3 | The Speed slider | in review | `claude/jolly-keller-rfs1c3` | [#379](https://github.com/cjcormack/lighting7/pull/379) | |
| 4 | Half, double and a speed fader | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — Own tempo on the desk

- **The desk (D3, D4):** `ownBpm` on `FxInstance` and every FX home (§3 "Wire"), `seconds` gone from
  each. `FxTimingChoice.kt` speaks Own tempo: `applyTimingChoice` runs an Own FX on the wall-clock loop
  at `rateScale = ownBpm / 120`, cycle `cycle ÷ 2` seconds (or the equivalent `ownBpm / 60` on the
  stored beats; the session picks one and keeps every wall-clock reader of `timing.cycle` on it);
  `FxInstance.onSeconds` splits into `onOwnTempo` (a beat FX with `ownBpm`) and `onNatural` (a
  wall-clock FX with no rate master), and every reader of `seconds` or `onSeconds` takes the one it
  means (the compiler finds them: `routes/cueUpdateStage.kt`, `ProgrammerLayerStack`,
  `routes/cueStackTimeline.kt`, `ai/RigBriefing.kt` among them). Three readers the compiler will
  not find, as they read a rate slot rather than the flag, must give an Own FX its own rate: the
  engine's wall-clock pass (`FxEngine`'s compose pass, which scales only by `rateMasterSlot` and
  samples `rateScales()` only when one is assigned), so the stage runs it; `FxSampler` (its phase
  sampling, `cycleMs`, `windowSlot`); and `routes/fxPreview.kt`'s clock for a master-less FX. Missed,
  any of them runs an Own FX at 120. `FxEngine.updateFx`
  changes an Own FX's tempo through its rate, phase kept, and swaps on a move between Own and a master
  as it swaps for Seconds today. Layer and press overrides leave an Own FX alone, as they leave Seconds
  (`withCycleOverride`). Record, Include, Duplicate, `copy-from` and the Timeline carry it.
- **The rewrite (D5, P3):** `state/OwnTempoMigration.kt`, the next free `user_version` (12 at writing),
  rewrites every Look, cue and palette FX with `seconds` set (a beat FX to `own_bpm` 120 and `cycle`
  × 2, a wall-clock FX to its flag dropped), then drops the column, backing the database up first when
  it changes a row (`<db>.pre-own-tempo-<utc>.db`) and logging each FX by its owner's name. Frozen
  `src/test/resources/db/pre-own-tempo.sql`, a database at the previous step holding Seconds FX of
  every home, never regenerated. `OwnTempoEquivalenceTest` holds P3, and a second test runs an Own
  FX at a tempo other than 120 through the engine's pass, the sampler and the preview.
- **Sync:** the next `formatVersion` and `minReader` (49 at writing), since an older reader would run
  an Own FX on master 1. The importer converts an older archive's `seconds` by D5's rule.
  `RichProjectFixture` stores an Own FX at a non-default tempo.
- **Wire and MCP:** the FX routes, `updateFx` and the group route take `ownBpm`; they still take
  `seconds` (a `true` converting at the cycle sent as seconds, by D5, so a client sending it keeps
  working) and refuse both beside a master. The MCP FX tools take `ownBpm` and still read `seconds`;
  `describe_rig` and the tool descriptions say Own tempo.
- **The client, as little as the wire needs:** `TimingChip`'s *Seconds* becomes *Own tempo* with a
  BPM field (`timingChoicePatch` converts, keeping the speed by D3), `CycleChip` reads beats on Own,
  `fxSpeedLabel` and `secondsLabel` take `ownBpm` in place of `seconds`, labels say `Own 96` where
  they said `4 s`, and a wall-clock FX's *Seconds* sends `natural: true`. The slider is session 3's.
- **Tests:** the migration over the frozen database, the round trip, an older archive's Seconds FX
  imported as Own tempo, `updateFx` moving an Own tempo with the phase kept, a request with `seconds`
  converted, MCP's tools, `FxFields.test.tsx` for the chip.
- **Docs:** `docs/fx-engineering.md` §"Seconds" rewritten as §"Own tempo", the root `CLAUDE.md`'s FX
  bullet and migrations section, `docs/sync-engineering.md` (the new version), `docs/glossary.md` (an
  *Own tempo* row, *Was* Seconds, `done`).
- **Local:** full `./gradlew test` and `npm run check`.
- **As built.** The rate is `ownBpm / 60` on the stored beats, so `timing.cycle` stays the stored
  cycle on every reader. The rewrite drops a Seconds FX's masters as well as its flag, a wall-clock
  one's rate master included: Seconds spawned it with none, so keeping the master would have moved its
  output. With no registry at database init, a wall-clock FX is told by name, normalised as the
  registry resolves one: a built-in (`BUILT_IN_WALL_CLOCK_FX`, held to the library by a test) or one of
  its project's FX definitions on the wall clock. An FX a script registers, which neither can see, is
  read as a beat one; it runs on Natural at the seconds it meant (`storedClock`), and a stored write of
  it is kept that way rather than refused (every other wall-clock FX is refused an Own tempo, D4). The
  importer converts an older archive's `seconds` the same way, dropping that row's masters as the step
  does. One exception to P3: a wall-clock FX on Seconds ignored a layer's cycle and rate-master
  overrides, and on Natural it takes them; only a user FX that reads its phase can show it.

### Session 2 — Tempo in the programmer

- **The desk (D8, D9, D11):** `state/ProgrammerTempo.kt`, runtime-only, on the pattern of
  `ProgrammerScenery.kt`: the holds, their frames (§3 "Wire") and `programmer.tempoState`.
  `SpeedMasterBank` keeps each master's live tempo beside an optional hold and runs the hold;
  `setBpm`, `tap` and `setCueTempos` follow §3's table (a GO takes its own live-only path, and
  `CueTempo.kt`'s skip compares the live tempo, not the running one); the master sheet's `PUT`
  (`routes/projectSpeedMasters.kt`) writes the stored row and live and moves a hold, and its follow
  fields drop the hold of a master they link; the debounced starting-tempo write-through (`Show.kt`'s
  `onChangeSync`) persists the live tempo only; `SpeedMasterBank.load` keeps each surviving master's
  live tempo and hold across a master's create, rename or delete. Blind stages, Clear releases, a
  project switch drops, `ShowCarry` keeps; `ProgrammerHistory` snapshots the holds so a hold is a
  step. MCP's `set_bpm` takes `hold`, and `get_current_state` lists held tempos.
- **Record, Include, Update (D10):** `routes/programmerTempoRecord.kt` beside
  `programmerSceneryRecord.kt`: Record's create, merge, Remove and Replace (D10), the request's
  `tempo: false` to leave them out, Include's holds and baseline, Update's write,
  `changedSinceInclude`.
- **The client:** `ProgrammerTempoList.tsx` (rows with an editable tempo and ✕) beside the scenery
  list, `RecordTempoRow.tsx` beside `RecordSceneryRow.tsx`, `useIncludeBaseline` counting tempo. The
  rail card shows *Held* in amber, the staged tempo in Blind, and *Capture* or *Release*. The words of
  D7 on the rail, the master sheets, the binding picker's tempo targets and the Cue tab, whose section
  becomes *Tempo on GO*.
- **Tests:** §3's table row by row in `SpeedMasterBankTest` (a GO under a hold, a rail tap moving the
  hold, release landing live, the sheet's `PUT` on a held master, a held master linked as a follower,
  a follower refused, the write-through persisting live under a hold); Blind staging and landing, and
  a rail tap in Blind on a staged hold moving live; Record create, merge, Remove, Replace and
  leave-out; Include and Update; undo of a hold; a carried reload; the client's list, Record row and
  rail card.
- **Docs:** `docs/fx-engineering.md` §"Speed Masters" (live and held), `docs/cues-engineering.md`
  §"Speed masters on a cue" (Record is the way in), `docs/lighting-composition-model.md` where the
  programmer's scenery is described, `docs/websocket-engineering.md` (the frames),
  `docs/mcp-engineering.md` (`set_bpm`'s `hold`, `get_current_state`'s held tempos),
  `docs/glossary.md` (row *A speed master's rate*: Tempo, *Was* Speed; *Tempo on GO*; *Held tempo*;
  *Capture*).
- **Local:** `./gradlew compileTestKotlin`, then the `fx`, `state`, `routes`, `plugins`, `ai`,
  `history`, `show`, `midi` and `telemetry` packages; `npm run check:quick`.
- **As built, where the code moved the plan:** the holds live on `State`, as the programmer's scenery
  does, not on the show, so a carried reload keeps them through `ProgrammerTempo.attach(reload)` and
  `ShowCarry` carries nothing new; the bank takes only the holds that changed
  (`SpeedMasterBank.changeHolds`), so a hold it has just moved is never put back, and reports one it
  moved or dropped itself through `onHoldChanged`, which the programmer reads back from the bank. D11
  does not say what a rail tap in Blind does to a hold released in Blind that still runs: it moves the
  hold, as on any held master, and the live tempo too, so leaving Blind lands the tapped tempo rather
  than losing it. `programmer.tempoState`'s entries carry a
  `released` flag beside `staged`, for a hold let go in Blind that still runs. The Record request takes
  `tempo` and answers `tempoWritten` and `tempoRemoved`; Include answers `tempoIncluded`, Update's
  results `tempoWritten`. A Look as the include target clears the tempo baseline, since its Update
  writes no tempo.

### Session 3 — The Speed slider

- **The desk (D6):** a speed change keeps the FX's elapsed cycles: a beat FX carries a runtime tick
  shift that `updateFx` sets so `elapsed ÷ ticksPerCycle` (whole and fraction) is the same either side
  of a cycle change, which keeps its phase, its run count and Bounce's parity. A forever beat FX whose
  new cycle is a power of two of a beat then glides its shift to the nearest grid alignment (a whole
  cycle, two under Bounce) over one cycle, never running backwards. A wall-clock or Own-tempo FX
  rescales its accumulator to keep its elapsed cycles and does not glide; nor does an FX with runs.
  The shift is never stored: a re-GO, and a carried reload (whose hand-run FX already restart their
  phase, `ShowCarry`), start on the grid. The vector (P2) pins the snap set and the glide's length.
- **The client (D1, D2, D4):** `F/lib/fxSpeed.ts` (speed from a cycle and back, the snaps, the
  readout, the chip label) and `F/lib/tapTempo.ts`. `FxFields`' Timing strip becomes the page's Speed
  section in both layouts: the tempo chip (masters, Own tempo, *Same speed as…*), Tap (by D8: a hold
  on a master in the FX editor, the cue's *Tempo on GO* in the cue FX tab, a live tap in the palette
  editor, the FX's own tempo on Own), the tempo field, the slider and its typed value, the readout,
  then Direction and Runs; a stateful wall-clock FX's *Runs at its own pace* (D4), from the library's
  `ownPace`, which the desk reads from frontmatter (`FxFileLoader`) and the two built-ins declare. The
  slider writes cycles rounded to whole ticks (D1). `SPEED_STEPS`, `getCycleLabel`, `fxSpeedLabel`,
  `secondsLabel` and `CycleChip` go, with their cases in `fxConstants.test.ts`; every caller §1 lists
  speaks `fxSpeed.ts`. The graph's Speed handle (§4). A live drag pushes through `useLivePush` as
  other fields do.
- **Tests:** `fxSpeed.test.ts` and the desk's against the vector; the phase kept across a cycle change
  and the glide onto the grid, a Bounce FX's parity and an FX with runs ending on its count (a new
  `FxSpeedPhaseTest`; `WallClockTimingTest` for the wall clock); `FxFields.test.tsx` for the section,
  the snaps, the typed fraction and each Tap's target; the labels on a chip, a Timeline cell and a pad.
- **Docs:** `docs/fx-engineering.md` §"Cycle Lengths" (Speed and its readout) and §"Phase
  Calculation" (the shift), `frontend/CLAUDE.md` where the FX editor's sections are described,
  `docs/glossary.md` (a *Speed* row for the FX, `done`; the *Cycle* row's note that Speed is a rate
  replaced). The word is freed first: Fluorescent Flicker's `speed` parameter is labelled *Burst rate*
  in `FluorescentFlicker.fx.kts`, its glossary row's word with it (*Was* Speed), the parameter's name
  unchanged. The glossary's *Cycle* row keeps a key that still finds its word once `CycleChip` goes
  (the readout's *a cycle*), moved if needed, as `glossary.test.ts` reads it.
- **Local:** `./gradlew compileTestKotlin`, then the `fx` package; `npm run check:quick`.
- **As built.** The shift is in ticks and its glide carries its own length and grid in ticks
  (`CycleShift`, `FxPhase.kt`), set by `FxEngine.keepCycles` on the swap a new cycle takes, from the
  master's current tick; an FX never sped up keeps the integer arithmetic. The grid is the whole
  cycle's ticks, so under *One cycle for: Each head* a glide aligns each step rather than the whole
  sequence. The vector also pins `TapTempo` and the whole ticks a typed Speed writes (×5 is 19 ticks,
  `×5.05`). The wall-clock FX's Speed, ×1 at two seconds, makes a power of two of its cycle a snap
  too, so the graph's Speed handle snaps a wall-clock cycle to a power of two of its Speed rather than
  to 0.05 s. A chip says the Speed and its tempo; the seconds are its hover where the plan's labels table
  asks — the fixture sheet's tray chips, the Applied FX table's Speed column and the Timeline's run
  list (`useCycleSeconds`) — beside the editor's readout and the graph tab's hover. `beatCycleTicks`
  now lands a whole tick that `n/24 × steps` misses in binary, so a step-timed FX runs its Speed. Only the live FX editor's Speed section reads the
  programmer's holds, so a host that holds none (the palette editor, the cue FX tab) needs no store
  for it. The Applied FX table's *Cycle* column is *Speed*; the glossary's *Cycle* row is keyed to
  `docs/fx-engineering.md` now that no control says the word. Every instance standing in for another
  — a swap, a split's head copy, a programmer undo — takes the shift with the run's place
  (`carryRunState`), and a change of master or loop drops it, as its ticks are the old master's. A
  follower master offers no Tap or tempo field in the Speed section, as the rail offers none.

### Session 4 — Half, double and a speed fader

- **Half and double (D12):** `speedMasters.scale` and `BindingTarget.SpeedMasterScale`, through D9's
  rule; ×½ and ×2 on each rail card and in `SpeedMasterDetailSheet`, disabled at the range's ends and on
  a follower; the binding picker's entry.
- **FX speed (D13):** `BindingTarget.FxSpeed`, dispatched by `SurfaceInputRouter` on the desk
  selection's Local FX through `applyFxUpdate`; its feedback in `SurfaceFeedbackPublisher` (soft
  takeover arms from it, as for the tempo bindings); `AssignmentHealth` when the selection paints no
  Local FX; the binding picker's entry and the Surfaces view's ring.
- **Tests:** a scale on a held and a live master, at a range's end, on a follower; the lead FX
  chosen by rig order and Local rank; the encoder's
  detents through a snap and the ratios kept, extending the vector (P2) with them; a Local FX partly
  inside the selection changing whole; the fader's soft takeover, from an FX outside ×¼–×4 too; the
  feedback value; the picker entries.
- **Docs:** `docs/fx-engineering.md` §"Control-surface bindings",
  `docs/midi-control-surface-engineering.md`.
- **Local:** `./gradlew compileTestKotlin`, then the `midi`, `fx`, `plugins` and `telemetry` packages;
  `npm run check:quick`.

## 6. Operator checks

One row in `manual-validation.md` per merged session, at the desk:

- Session 1: a Look that held a Seconds breathe before the upgrade runs at the same rate after it, and
  reads *Own tempo*.
- Session 2: tap M2 on the rail and *Capture* it, GO a cue that sets M2: the chase keeps the tapped
  tempo; Record into a new cue, Clear, GO it: M2 lands on the tapped tempo.
- Session 3: drag Speed on a running chase: no jump; let go on ×2 and watch it settle on the beat. Tap
  M2 in the FX editor: the rail shows it held.
- Session 4: ×½ on a busked master mid-song, a fader on the selection's FX speed.

## 7. Explicitly out of scope

- Speed counted in steps, so a longer colour list runs slower (`FU-FX-PER-STEP-TIMING` stays dormant;
  one cycle a bar was chosen over a step a beat).
- MIDI clock, Ableton Link and audio beat detection.
- One FX at two speeds on two attributes (`FU-SPEED-PER-ATTRIBUTE`).
- A surface's LED confirming a tap (`FU-SPEED-SURFACE-TAP-LED`).
- Scripts: `FxTiming` and `CycleLength` keep their beats.
- A master's source naming the cue that set it (the page's "set by cue 11").
