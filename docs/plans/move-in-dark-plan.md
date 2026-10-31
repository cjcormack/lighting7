# Move in dark: dark heads moved ahead of the cue that lights them

> **Document status: READY — written 2026-10-31 from the page Chris and Claude agreed that day.**
> Five sessions, each one PR, each stacked on the one before while that one is unmerged, each run by
> `/next-session` (§5). The page and the record are in
> [`move-in-dark-design/INDEX.md`](move-in-dark-design/INDEX.md).
>
> This document is the engineering half. Where it and the page disagree, this plan wins on
> behaviour and the page on layout and copy.

## 1. Context

Chris asked, 2026-10-31, what support the desk has for moving heads in the dark in cue stacks, and
chose to build a real look-ahead: when a moving head goes dark, the desk finds the cue that lights it
next and moves its position, colour and beam there while nobody can see, so GO only brings it up.

What the code says, read at `ade1846`. `F/` is `frontend/src`, `K/` is
`src/main/kotlin/uk/me/cormack/lighting7`.

- **Today's flag** is per row: `cue_attribute_assignments.move_in_dark` (`K/models/cues.kt:393`,
  DTO `:136`, sync `CueAttributeAssignmentJson.moveInDark`). `CueAssignmentResolver.computeMoveInDarkArmed`
  (`K/fx/CueAssignmentResolver.kt:474`) arms a target when a `position` row carries it and a
  *different* cue's DIMMER row stores 0 on the same key; `composeLtp` (`:617`) then holds the incoming
  position for the whole crossfade. It acts at GO, reads only the cue tier, and nothing sets it: the
  Values grid has no control (`FU-CUE-ROW-TIMING`), Record's and Update's MERGE writes the recorded
  row's `false` over it (`K/routes/programmerRecord.kt:203`), and no MCP tool names it.
- **A GO** lands in `CueStackManager.activate` (`K/fx/CueStackManager.kt:198`): it acquires the new
  cue's hold at `cueDerivedPriority(stackId, pos) = stackId·1 000 000 + pos·1 000 + 1`
  (`K/fx/CueApply.kt:229`), releases the old, and starts `CueCrossfadeDriver` (16 ms frames,
  `updateFadeWeights`). Our cues do not track: a GO releases the stack's previous cue.
- **Next** is `CueRunStateTracker.effectiveNext` (`K/fx/CueRunStateTracker.kt:190`; `nextFrom` `:203`):
  the armed standby, else the positional next, with loop; markers skipped.
- **The cue tier** is `CueAssignmentLayer`, keyed by cue id (`K/fx/CueAssignmentLayer.kt:31`;
  `setAssignments` `:77` replaces a cue's contribution). Rows reach DMX through
  `LayerResolver` → `CascadePublisher.publishCueLayerToControllers`, under the programmer, park and FX.
- **A cue's own rows** are built by `buildCombinedCueLayerRows` (`K/fx/CueApply.kt:523`), which the
  Next GO preview reuses (`K/routes/cuePreview.kt:83`): Looks, palettes and own rows, groups expanded.
- **Intensity** per head is already classified for the grand master: dimmer, UV, and colour mix on a
  head with no dimmer of its own or on its parent (`K/midi/GlobalScalerState.kt:118`
  `refreshClassification`). The grand master and *Make rig safe*'s blackout apply at transmit
  (`:185`), after `ArtNetController`'s composed values (`K/dmx/ArtNetController.kt:414`), which
  already include park.
- **Families**: `AttributeCategory.family()` (`K/fx/AttributeMask.kt:32`): Intensity = DIMMER, STROBE;
  Position = PAN, TILT and fines (cue rows store the synthetic `position`); Colour = COLOUR, AMBER,
  WHITE, UV; Beam = the rest.
- **Storage:** sync is at `formatVersion` 44 (`SUPPORTED_FORMAT_VERSION`, `K/sync/ProjectImporter.kt:318`);
  `PRAGMA user_version` is at 8 (`FxSiblingsMigration`).

## 2. Decisions

The page's D1–D12, as agreed, with Q1–Q8's answers folded in.

| # | Decision |
|---|---|
| D1 | **Live, never stored.** A move in dark is computed from the stack's list, its run state and the output. No cue is written and nothing is recorded; turning it off undoes it at once. |
| D2 | **A stack setting**: on or off, *When* Early or Late (Early by default), a delay (1 s), a move time (2 s), and the families that move (Position, Colour, Beam; all three by default). **New stacks are on, existing stacks off** (Q2): the column defaults off, and every door that creates a stack writes on unless told otherwise. A change applies at once, without releasing the stack. |
| D3 | **A cue setting, on the cue that lights the head**: *Stack* (default), *Early*, *Late* or *Off*, with its own delay and move time when set, and a list of heads kept **live** (a fly-in, Q7). A cue's *Early* or *Late* applies even on a stack that is off; *Off* and a live head move in the GO's fade, lit. |
| D4 | **Dark means dark on the output.** Every intensity channel of the head (its dimmer, its parent's master dimmer, UV, a dimmerless head's colour mix) is 0 in the composed output, after cues, FX, the programmer and park, before the grand master, *Make rig safe*'s blackout and the venue hold. A head with an Intensity FX running on it is lit, whatever the FX outputs this frame; so is a head whose strobe channel is open on a lit dimmer. It must stay dark for the whole delay. |
| D5 | **The target.** *Early*: the first entry ahead in play order (the effective next, then positional, with loop, at most one pass of the list) whose cue's own rows light the head (an intensity row above 0, or an Intensity FX on it), through any cues that keep it dark. *Late*: the effective next only. Only attributes that cue's rows set, in the moving families, move. Intensity and strobe never move, and colour stays put on a head whose colour is its intensity. |
| D6 | **Its own contributor in the cue tier**, one per stack, in an id space no cue can take, at the stack's live cue priority + 500. The programmer, a higher stack, park and FX all still win over it. A head's move fades in over its move time from the moment its delay is up. |
| D7 | **At GO it hands over without a step.** When the GO's entry is a head's target, its rows equal the target cue's, so the contributor holds them until that GO's crossfade ends, then lets go. A head that is dark at the start of a GO but not in place (the GO came too soon, a GO TO, a standby armed late) takes its moving families from the incoming cue at once rather than over the fade. This generalises and replaces today's per-row arming. |
| D8 | **A head that lights early is let go** (Q5). If anything lights a held head before its target's GO, its rows release over the move time, so it returns to what the live cues say, and it reads *lit early*. Dark again, it waits the delay and moves again. |
| D9 | **Anything that changes "next" re-plans**: a standby armed or cleared, GO, GO TO, GO BACK, an entry added, moved or removed, a cue, Look or palette edit, the stack's settings or loop. A head heading for a target that is no longer ahead moves to the new one, or back, in the dark. Stopping the stack, switching project or a reload lets every move go; a reload re-plans from the carried state. |
| D10 | **FX wait for their GO.** A cue's FX start at GO as now. A move in dark sets the values under an incoming FX; it never starts one. |
| D11 | **Shown wherever a head's value is explained**: the cue table's *Dark* column, the Cue tab's section, the stack row and the phone runner, the Stage view, the Timeline, and the attribute's key stack as its own row, *Move in dark · for Q12*. |
| D12 | **Two stacks, one head**: the higher contributor wins, as LTP already decides. |
| D13 | **The per-row flag is retired** (Q6). A one-shot data step gives every cue with a flagged row the cue setting *Early*, then drops the column; the importer does the same for an older archive. D7 covers what the flag did. |
| D14 | **The word is *Move in dark*** (Q1), a `done` glossary row once the first screen says it. |

The plan adds its own:

- **P1 — Five sessions, five stacked PRs**, in §5's order, each run by `/next-session`
  (`.claude/skills/next-session/SKILL.md`) through `/verified-ship`. A session stacks on the previous
  session's branch while that PR is open, and on `main` once it has merged.
- **P2 — The desk owns the plan.** Which heads move, where to, when and why not are answered by the
  desk (a pure planner, a read route and a frame). The client draws them and never works out a move
  itself.
- **P3 — One reading of dark.** `HeadIntensity` names a head's intensity channels from the same
  classification the grand master uses, so the two can never disagree about which channels light a
  head. A change to one is a change to both.
- **P4 — Off is today.** A stack with Move in dark off, holding cues that set no cue mode, sends
  exactly what it sends today, frame for frame. A test pins it.
- **P5 — The crossfade stays fast.** Sessions 1 and 2 touch the cue tier's compose path, so each runs
  `FxEngineBenchmark`'s `[crossfade]` scenario against the baselines in `docs/testing-engineering.md`
  and records the result in its section (`FU-TEST-FX-BENCH-CI-GATE`).

## 3. The model

**Per stack**, while it is live and Move in dark applies to at least one upcoming cue:

1. **Plan** (pure, `MoveInDarkPlanner`): from the live entry, walk the play order (D5) and, per head
   any upcoming cue lights, answer its target entry, the attribute values to move (in the stack's or
   the target cue's families, less the head's colour where colour is its intensity), the delay and
   move time that apply (the target cue's, else the stack's), and why a head will not move (the
   target cue *Off*, the head kept live, lit in every cue before it). Re-run on D9's triggers, never
   per frame.
2. **Watch** (`MoveInDarkService`, a ~50 ms tick over planned heads only): read each head's intensity
   channels (D4, P3). A head dark since time *t* becomes *waiting*; at *t* + delay it becomes
   *moving* and its rows join the stack's contributor, fading in over the move time; after the move
   time it is *set*. A head that lights while waiting goes back to idle; while moving or set it goes
   *lit early* (D8) and its rows fade out over the move time.
3. **Hand over** (D7): on a GO whose entry is a head's target, the contributor keeps that head's rows
   until the GO's crossfade ends; on any GO, heads dark at its start and not set take the incoming
   cue's moving families at once (the resolver's armed set, now supplied by the service rather than
   computed from row flags). Then re-plan from the new entry.

**States** a head reports: `WAITING` (dark, counting the delay), `MOVING` (with time left), `SET`,
`LIT_EARLY`. A head that is lit, or that no upcoming cue lights, is not reported.

**The static plan** (session 3's read route) runs the same planner over the stored list with no other
stacks, from every entry rather than the live one, and adds `TOO_QUICK`: the entry before the target
is reached by a follow whose delay, less that cue's fade where the cue takes the head out, is shorter
than the delay plus the move time. Manual GOs are never too quick.

## 4. UX

The page is the authority on layout and copy: §5.1 to §5.7. Three things it does not show:

- **Locked**: the stack sheet's block and the Cue tab's segment and per-head toggles are stored
  writes, hidden while locked; every read-out and chip stays. Nothing about Move in dark is a runtime
  gesture.
- **Narrow widths**: the Dark column folds to its glyphs (`☾ 6`, `▲ 1`) under the table's `@container`
  breakpoint, as the Scenery column does; the Cue tab's head list drops its *Moves* column on the
  phone host.
- **Refetch**: the plan route is re-read on `cueStackListChanged`, `cueListChanged` and
  `cuesRecomposed` naming the stack's cues; `moveInDark.state` drives the live chips and the *Now*
  column without a refetch.

## 5. Implementation — five sessions

Each session is one PR. Each is run by `/next-session`, which cuts the branch, stacks it on the
previous session's branch while that PR is open (on `main` once merged), records the previous
session's PR and head SHA in the table below, and hands the session to `/verified-ship`. A session
ends with its checks green (§"Pre-commit checks" in the root `CLAUDE.md`; sessions 1 and 3 also run
the full `./gradlew test` and `npm run check`, as a sync `formatVersion` bump and a migration
require), the docs it names written, and its row marked *in review* with its branch. A session's PR
is titled *Move in dark: session N — title*. Never merge a later session's PR before an earlier one.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | Stack settings and the look-ahead engine | planned | | | |
| 2 | Every edge: re-planning, catch-up, lit early, the key stack and the frame | planned | | | |
| 3 | The cue's setting, the static plan, MCP, and the row flag retired | planned | | | |
| 4 | Screens: stack settings, the Cue tab and the cue list | planned | | | |
| 5 | Screens: the Stage view, the Timeline, and the docs closed | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — Stack settings and the look-ahead engine

- **Storage (D2):** `cue_stacks` gains `move_in_dark` (bool, default false), `move_in_dark_when`
  (`EARLY | LATE`, default `EARLY`), `move_in_dark_delay` and `move_in_dark_time` (`duration()`
  columns, nanoseconds, defaults 1 s and 2 s; never an `_ms` name) and `move_in_dark_families`
  (`POSITION`, `COLOUR`, `BEAM`, stored as a sorted comma list, default all three). Additive, so
  `createMissingTablesAndColumns` adds them and every existing stack reads off.
- **The wire:** `NewCueStack`, the stack `PUT`/`PATCH` and `CueStackDetails` carry
  `moveInDark {on, when, delayMs, timeMs, families}`. `POST /cue-stacks` with no `moveInDark` creates
  the stack **on**, at the defaults; so do `create_cue_stack` and `build_cue_stack` (their schemas
  gain the object, optional). 400 `MOVE_IN_DARK_INVALID` names every problem at once: delay or time
  outside 0–30 s, an empty or unknown family. A clone and an import keep what is stored.
- **Sync:** `CueStackJson.moveInDark…` fields, defaulted, at `formatVersion` 45 under the house rule
  for a new defaulted field (writer and `SUPPORTED_FORMAT_VERSION` 45, `MIN_SUPPORTED_FORMAT_VERSION`
  unchanged; the comment block above it and `docs/sync-engineering.md` §"Version 45" say why). An
  archive below 45 reads every stack off. `RichProjectFixture` stores a stack with non-default values
  (on, `LATE`, 2.5 s, 0.5 s, Position only); `ProjectRoundTripTest` covers it.
- **`HeadIntensity` (D4, P3):** `K/fx/HeadIntensity.kt` names a fixture's or element's intensity
  channels (its own and its parent's DIMMER, UV; the colour mix when neither has a dimmer, which
  then also excludes Colour from its moves) and reads them from the controllers' composed values
  (park included, transmit modifiers not). It shares `refreshClassification`'s rules by extracting
  them, not by copying. *Is lit* also counts an Intensity-family FX instance targeting the head
  (`FxEngine`'s active list) and an open strobe on a lit dimmer.
- **`MoveInDarkPlanner` (D5):** pure; takes the ordered entries from the live one, the effective next,
  loop, the stack's settings and a provider of each cue's own rows (`buildCombinedCueLayerRows`) and
  Intensity FX targets, and answers §3's plan per head, Early and Late both. Cached per stack;
  session 1 re-plans on GO and on a standby armed or cleared, session 2 on everything else.
- **`MoveInDarkService` (D6, §3 steps 2–3):** one per `Show`, started and stopped with it. The
  contributor lives in `CueAssignmentLayer` under an id space no cue can take (negative ids are the
  obvious choice; check nothing reads cue ids as positive), at live priority + 500. Heads start at
  different times, so read how row fades behave under `setAssignments(honourRowFades = true)` and
  either give each head's rows its own fade or a contributor id per (stack, head); record which and
  why in this section. The normal hand-over at a target's GO lands here (§3 step 3, first half).
  `CueStackManager.activate`, `deactivateStack` and the project switch call it; nothing in the GO
  path waits on it.
- **Tests:** `MoveInDarkPlannerTest`: Early through two dark cues, Late, loop wrap, an armed earlier
  standby, markers skipped, only set attributes moved, families filtered, colour kept on a dimmerless
  head, an Intensity FX counting as lit. `HeadIntensityTest`: an element under a master dimmer, a
  dimmerless head, UV, a parked dimmer above 0 counting as lit, the grand master at 0 and a safe
  blackout not counting as dark. `MoveInDarkServiceTest` on a test controller: Q10 lit, Q11 fades the
  head out, after delay + time the head holds Q12's position while its dimmer reads 0, and Q12's GO
  moves no pan or tilt byte across its fade (sampled per frame); a stack off sends what it sends
  today (P4).
- **P5:** run the `[crossfade]` benchmark and record it here.
- **Docs:** `docs/cue-stacks-engineering.md` §"Move in dark" (new: the model, the planner, the
  service, the contributor's id space and priority); `docs/lighting-composition-model.md`
  §"Crossfade behaviour" (the look-ahead beside the row flag, which session 3 removes);
  `docs/sync-engineering.md` §"Version 45"; root `CLAUDE.md` §"Cue Stack Run Endpoints" (one line
  on the stack's `moveInDark`).

### Session 2 — Every edge: re-planning, catch-up, lit early, the key stack and the frame

- **Re-planning (D9):** the service re-plans a stack on `GO TO`, `GO BACK`, an entry placed, moved
  or removed (`announceEntriesPlaced`, `announceEntryMoved`, `afterEntriesRemoved`), a stack `PUT`
  (loop, settings), a cue `PUT`/`PATCH`, Record or Update into one of its cues, and a Look or palette
  republish naming its cues (`K/routes/lookRepublish.kt`). Find every write that moves a stack's list
  or a cue's rows (`grep -rn cueStackListChanged\|cueListChanged\|cuesRecomposed`) and hook each
  once, at the announcement rather than the route. A head heading for a target no longer ahead moves
  to the new one, or back to the live values, over the move time, while dark.
- **Catch-up at GO (D7):** `CueAssignmentResolver`'s armed set is supplied by the service (heads dark
  at the GO's start, not set, in the incoming cue's moving families), and `composeLtp` snaps every
  armed key in those families, not only `position`. The row flag's own arming stays beside it until
  session 3.
- **Lit early (D8):** §3 step 2's transitions, with the rows fading out over the move time.
- **Lifecycle:** a stack stop, a project switch and Blind-independent behaviour (Blind gates the
  programmer, not this); a reload (`K/show/ShowCarry.kt`) carries nothing of the service and
  re-plans, keeping a head that is still dark and already in place where it is, so nothing moves on
  a reload. Two stacks on one head (D12).
- **The key stack (D11):** `ProvenanceService.keyStack` (`K/fx/ProvenanceService.kt:647`) gains a
  `MOVE_IN_DARK` row `{stackId, targetEntryId, targetCueId, label}` at its place in the order, with
  `onStage`; `programmer.keyStack` carries it, and the client's key-stack panel draws it (*Move in
  dark · for Q12*) in this session, since the wire and its reader land together.
- **`moveInDark.state`:** outbound, `StateFlow`-backed, so the subscription is the snapshot:
  `{projectId, stacks: [{stackId, heads: [{target, entryId, cueId, state, remainingMs?,
  families, position?: {pan, tilt}}]}]}`, `position` the target pan and tilt when Position moves
  (the Stage view's ring, session 5). Conflated to at most every 100 ms. The client gains its slice
  and types (`F/store/`), subscribed and tested, with no screen yet.
- **Tests:** each trigger re-plans (one test per announcement); a GO too soon lands the rest at once
  and moves no byte while the dimmer is above 0 afterwards; GO TO into a cue lands its dark heads at
  once; a busk press lighting a set head releases it, and it moves again after the delay once dark;
  a reload moves nothing; two stacks; the key stack row; the frame's snapshot and deltas.
- **P5:** re-run the `[crossfade]` benchmark and record it here.
- **Docs:** `docs/cue-stacks-engineering.md` §"Move in dark" (re-planning, catch-up, lit early,
  reload); `docs/websocket-engineering.md` §"Move in dark"; `docs/lighting-composition-model.md`
  §"Reading one attribute's stack"; root `CLAUDE.md` §"WebSocket Messages" (one bullet).

### Session 3 — The cue's setting, the static plan, MCP, and the row flag retired

- **Storage (D3):** `cues` gains `move_in_dark_mode` (`STACK | EARLY | LATE | OFF`, default
  `STACK`), `move_in_dark_delay` and `move_in_dark_time` (nullable `duration()`: null is the
  stack's), and `move_in_dark_live` (a JSON list of target keys, fixtures or elements, default empty).
  The planner reads them on the target cue (D3, D5). The cue `POST`/`PUT`/`PATCH` and `CueDetails`
  carry `moveInDark {mode, delayMs?, timeMs?, live}`, checked as the stack's is; a live key the rig
  lacks is kept and reported, as a cue row's target is. *Duplicate*, `POST /cues/copy-from` and MCP's
  `copy_to_project` carry it (`K/routes/cueCopier.kt`).
- **The static plan (§3):** `GET /cue-stacks/{id}/move-in-dark` answers per entry
  `{entryId, cueId, heads: [{target, fromEntryId, families}], live: [{target, reason}]}`, `reason`
  `LIT_BEFORE | TOO_QUICK | KEPT_LIVE | CUE_OFF`, and a stack summary `{heads, cues, live, off}` (the
  stack sheet's read-out). Both roles; stored data, so not gated on the current project.
- **The row flag retired (D13):** `state/MoveInDarkRowFlagMigration.kt`, `user_version` 9: every cue
  with a flagged row takes mode `EARLY`, then `ALTER TABLE cue_attribute_assignments DROP COLUMN
  move_in_dark`; a database holding no flagged row is only marked, one that holds some is backed up
  first (`<db>.pre-move-in-dark-<utc>.db`), each cue logged by name. The column leaves the model, the
  DTO, Record, Update, `programmerUpdate`'s split, the copier and the resolver's row arming.
  `MoveInDarkRowFlagMigrationTest` runs over `src/test/resources/db/pre-move-in-dark.sql`, a database
  at 8 with flagged rows, frozen and never regenerated.
- **Sync:** `CueJson.moveInDark…` fields, and `CueAttributeAssignmentJson.moveInDark` no longer
  written, at `formatVersion` 46 (writer and `SUPPORTED_FORMAT_VERSION`); the importer gives a cue
  whose older archive flags a row mode `EARLY`. `RichProjectFixture` stores a cue with mode `LATE`, a
  delay and a live head.
- **MCP:** `create_cue` and `build_cue_stack`'s cues take `moveInDark`; a new `set_move_in_dark`
  tool writes a stack's or a cue's settings (`{stackId | cueId, …}`, a stored write, `projectId`
  like the other setup tools); `cue_stacks` reports each stack's settings and summary, `cue_run`
  the live heads. No remote-access gate: it moves only dark heads, as scenery is drawn.
- **Follow-ups:** narrow `FU-CUE-ROW-TIMING` to the row fade; restate `FU-FE-HEALTH-BADGE`, whose
  trigger names the row-list editor that will now never exist.
- **Tests:** the planner with each cue mode, live heads and cue timing; the route's four reasons,
  `TOO_QUICK` at the boundary; the migration; the round trip and an older archive's flag lifted; the
  MCP tools; `CueModelMigrationTest` and `TerminologyMigrationTest` still green over their frozen
  files.
- **Docs:** `docs/cue-stacks-engineering.md` §"Move in dark" (the cue setting, the static plan);
  `docs/lighting-composition-model.md` §"Crossfade behaviour" (the row flag gone);
  `docs/sync-engineering.md` §"Version 46"; root `CLAUDE.md` (§"Database" step 9, §"Cue Stack Run
  Endpoints"); `docs/mcp-engineering.md` (the tool).

### Session 4 — Screens: stack settings, the Cue tab and the cue list

- **Stack settings (page §5.1):** the *Move in dark* block under Loop in `F/components/cues/CueStackForm.tsx`:
  the switch, *When* `Early · Late`, Delay and Move time (`SheetField`-style, seconds), *Moves* as
  three toggle chips, and the summary read-out from the plan route, opening the cue table filtered to
  the cues it counts.
- **The Cue tab (page §5.3):** a *Move in dark* section in `F/components/cues/editor/CueTab.tsx`:
  the segment (its first option reads the stack's setting, *Stack · Early*), delay and time showing
  the stack's until typed over, and the head list from the plan route merged with
  `moveInDark.state` (*From*, *Moves*, *Now*, and the *In dark · Live* toggle writing
  `moveInDark.live` through the cue `PATCH`). Locked, the controls hide and the list stays (§4).
- **The cue list (page §5.2, §5.4):** the *Dark* column in `F/components/runner/CueSheet.tsx`
  (`☾ n`, `▲ n` with its reason, *Off · n live*, and on the live row what is moving now), its glyph on
  the card view (`CueCardEditor.tsx`), the stack-row chip (`ShowOverview.tsx`) and the phone runner's
  *Up next* chip (`runner/mobile/`). A tap on a Dark cell opens the Cue tab at the section.
- **Glossary (D14):** a *Move in dark* row in `docs/glossary.md`, `done`, MagicQ's *Move when dark*,
  keyed `src:components/cues/CueStackForm.tsx`; `glossary.test.ts` and `GlossaryVocabularyTest`
  green.
- **Tests:** Vitest for the form block (writes, validation, locked), the Cue tab section (segment,
  overrides, toggles, locked), the Dark column's cells for each reason and live state, the chips.
- **Docs:** `frontend/CLAUDE.md` §Cues, Stacks & Triggers (the block, the section, the column and
  where each reads from).

### Session 5 — Screens: the Stage view, the Timeline, and the docs closed

- **The Stage view (page §5.5):** for each head `moveInDark.state` reports with a `position`, a
  dashed aim line from the head along its current drawn pan and tilt, a ring where the target lands
  (the same beam-to-surface maths the view draws a lit beam's pool with), and a badge with the cue
  and time left. A per-window *Moves in dark* switch in the View menu (`windows.viewOptions` key
  `movesInDark`, `on | off`, default on). Drawn only in the Output sources; never in a `render_view`
  capture. The head's own travel is already drawn (`F/lib/travel.ts`).
- **The Timeline (page §5.6):** a *Dark* column after the kinds in `F/components/runner/StackTimeline.tsx`,
  one hatched span per planned move from the plan route, from the entry it happens in to the entry it
  is for, in the run-bar idiom; read-only, a tap opening the target's Cue tab.
- **Tests:** the overlay's presence by source and switch, its absence in a capture; the column's
  spans for Early, Late and a move across a marker.
- **Docs:** `frontend/docs/stage-vis-engineering.md` §"Moves in dark"; `frontend/CLAUDE.md` (the
  Timeline column, the overlay); `docs/plans/manual-validation.md` (§9's rig checks); move this plan
  to `docs/plans/completed/` with its design folder once every row is merged.

## 6. Migration

Session 1 adds stack columns read as off, and sync 45. Session 3 adds cue columns read as *Stack*,
runs data step 9 (backing up first when it changes anything) to lift and drop the row flag, and moves
sync to 46, the importer lifting an older archive's flag the same way. No step changes what a stack
that is off sends (P4).

## 7. Explicitly out of scope

- A per-patch *never moves in dark* (declined for now, Q7); MA3's *UponGo* and a named target cue
  (declined, Q3).
- Starting an incoming cue's FX early, or moving the centre of a position FX the target cue starts.
- Reading a shutter modelled as a setting as dark (`FU-STAGE-SETTING-SHUTTER`).
- Philips Hue fixtures, which have no position.
- A dimmer-phaser exception: a head with any Intensity FX running is lit (D4), even one whose steps
  are all closed.

## 8. Follow-ups

Sessions 1 and 2 touch the cue tier's compose path: each reads `followups.md`'s index for a gate on
FX tick loops and ArtNet output before it starts, per CLAUDE.md, and runs P5. No session adds an
inbound WS frame. Session 3 narrows `FU-CUE-ROW-TIMING` and restates `FU-FE-HEALTH-BADGE`.

## 9. Verification

- **Session 1:** on the rig, a stack of three cues — a mover lit, then faded out, then lit elsewhere:
  the head moves a second after it goes dark and is in place before the GO; turning the stack off
  stops it with nothing moving in view.
- **Session 2:** GO quickly after the fade: the head finishes moving while its dimmer is still low.
  Press a busk pad lighting a moved head: it returns to the live position, then moves again once
  released. GO TO a later cue: dark heads land at once.
- **Session 3:** a cue set *Off* fades its heads up while they move; one head kept live flies in while
  the others are pre-set. A show exported before session 3 imports with its flagged cues on *Early*.
- **Session 4:** the stack sheet, the Cue tab and the Dark column agree with what the rig does,
  including a *too quick* follow.
- **Session 5:** the Stage view's rings land where the heads come up; the Timeline's spans match.
