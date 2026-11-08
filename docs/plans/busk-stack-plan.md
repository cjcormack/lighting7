# Stack pads: a busk stack run from a pad, over the show or in place of it

> **Document status: READY — written 2026-11-07 from the page Chris and Claude agreed that day.**
> Four sessions, each one PR, each stacked on the one before while that one is unmerged, each run
> by `/next-session` (§5). Sessions 3 and 4 need the OSC plan's session 1 merged first, and in their base. The page
> and the record are in [`busk-stack-design/INDEX.md`](busk-stack-design/INDEX.md).
>
> This document is the engineering half. Where it and the page disagree, this plan wins on
> behaviour and the page on layout and copy.

## 1. Context

Chris's brief, 2026-11-07: run a pantomime from one cue stack, busk the musical numbers (whose
backing tracks never vary) without complicating that stack, and trigger a separate stack from a busk
button, stepping through its cues. A second use followed: a short stack that runs itself when
Carabossy enters, replacing the state the main stack's cue is giving, and hands the stage back when
it ends.

What the code says, read at `6a5ffcf` (the OSC plan at `9bc4001`). `F/` is `frontend/src`, `K/` is
`src/main/kotlin/uk/me/cormack/lighting7`.

- **Pads.** `busk_pads` (`K/models/buskLayout.kt:160`) has nullable `palette_id`, `look_id` and
  `cue_id` and a CHECK that exactly one is set; `buskPadKind` decides the same in code and every
  reader drops a malformed pad. `BuskPadKind` is `PALETTE | LOOK | CUE`, matched exhaustively in
  `K/routes/BuskPressService.kt`, `K/routes/buskLayout.kt` (`buskPageCountsFor` `:109-116` among
  them), `K/routes/projectBusk.kt`, `K/routes/HandService.kt` and `K/state/State.kt`
  (`handRecordResolves` `:802`, `buildBuskRefs` `:1028`); `K/state/HandState.kt`,
  `K/routes/projectPalettes.kt` (`:1554`) and `K/routes/projectLooks.kt` (`:822`) only name the type
  or pass a constant, so they need no new arm;
  `State.kt:1111` reconciles the hand over `BuskPadKind.entries`, and `K/plugins/HandSocket.kt:84`
  reads a pick-up's kind with `BuskPadKind.valueOf`. A cue pad toggles a loose hold
  (`BuskPressService.kt:209`); a solo bank's cue press takes layer siblings off whole
  (`ProgrammerLayerStack.release`) and lets cue siblings' loose holds go. A record's delete sweeps
  its pads (`deleteBuskPadsReferencing`, `K/routes/buskLayout.kt:73`).
- **The CHECK is on the operator's database.** The terminology step rebuilt `busk_pads` from the
  model (`K/state/TerminologyMigration.kt:146`). Exposed emits a CHECK only in `CREATE TABLE` and
  SQLite cannot alter one, so a row naming only a new column fails the old CHECK at insert.
  `rebuildFromModel` (`K/state/MigrationSupport.kt:50`) is the rebuild helper; step 12
  (`OwnTempoMigration`) is the latest one-shot step and runs after the reconciliation
  (`K/state/State.kt:1482`).
- **Stacks.** `cue_stacks` (`K/models/cueStacks.kt:21`): `name`, `loop`, `sort_order`, `type`
  (`STACK | SEPARATOR`), Move in dark. `NewCueStack` serves both the stack `POST` and `PUT`; its
  `loop` is non-null, so a `PUT` without it resets it, while `moveInDark` is nullable and kept
  (`K/routes/projectCueStacks.kt:720-734`). `CueStackManager.go` (`K/fx/CueStackManager.kt:432`)
  starts a stopped stack on its armed or first entry, else advances; at the end of a non-looping
  stack `advanceStack` stays on the live entry. `advanceStack` returns null for a stack with no cue
  entries, which its callers report as such, and `go` on a stopped stack with none throws from
  `activateAtFirstCue` (`:428-432`, `:448-453`). A start from stopped always snaps its cue on: the GO
  fades only from an existing state (`useCrossfade`, `:252`). A follow runs inside the stack's `autoAdvanceJob`
  (`:560-569`). `deactivateStack` (`:465`) cancels that job, cancels the stack's crossfade
  (removing an outgoing cue's rows no hold keeps), lets the hold go with `CueHolds.Departure.SNAP`
  (`:472`), and drops the stack's FX, Move in dark, standby, scenery and the desk's effects arm. Its
  callers: the REST deactivate (`projectCueStacks.kt:477`), a stack delete (`deleteCueStackRows`,
  `:1152`), `/show/deactivate`, `/show/advance` and `/show/go-to` (`K/routes/projectShow.kt:98`,
  `:153`, `:195`), MCP's `deactivate_cue_stack` (`K/ai/AiTools.kt:1748`) and the undo restorer
  (`K/history/RecordRestorer.kt:278`). A project switch tears the show down without it.
- **Fades.** `CueHolds.release(…, Departure.FADE_OUT)` shrinks the cue's FX but leaves its rows at
  full weight for the caller's crossfade (`K/fx/CueHolds.kt:352-357`); only
  `CueCrossfadeDriver.start` ramps row weights (`K/fx/CueCrossfadeDriver.kt:69-93`), one fade per
  stack, cancelled by the stack's next activation (`CueStackManager.kt:237`). `start` only installs
  the new fade, overwriting the stack's slot without cancelling the old job (`:63-68`, `:91`), and a
  finished fade removes its outgoing cue's rows (`:168`). The GO path fades the outgoing cue only
  when its release was the last hold and left fading rows or FX (`CueStackManager.kt:293`). A fade
  holds one outgoing cue ramped 1→0 and one incoming 0→1 (`CueCrossfadeDriver.kt:50-59`, `:152-160`),
  and `CueHolds.takeOff` pins a fading departure's weight to 1 so it "fades out from full"
  (`CueHolds.kt:846-848`).
- **The running order.** The show is the project's ordered list of `STACK` rows
  (`orderedStacks`, `projectShow.kt:285`); its playhead is `projects.active_stack_id`.
  `/show/advance` (`:113`) releases the stack it leaves and starts the next with
  `activateAtFirstCue`, in either direction; `/show/go-to` likewise (`:152-154`, `:194-196`). The Show
  view's GO with nothing on deck (`runner.standbyCueId == null`) calls `/show/advance` FORWARD to the
  next runnable stack (`F/hooks/useShowTransport.ts:306-318`); on a non-looping stack's last cue the
  server names no next (`K/fx/CueRunStateTracker.kt:206-217`), so that is the branch GO takes there,
  and a stopped stack's next is its first entry. Its BACK is the playhead stack's own BACK. The connect
  snapshot of run state walks `stacksWithRunState()`, the live stacks and armed standbys
  (`CueRunStateTracker.kt:322`, read at `K/plugins/BroadcastSocket.kt:381-384`). The undo restorer
  clears `activeStackId` only for a stack it deletes (`K/history/RecordRestorer.kt:273-282`); import
  and pull null it (`K/sync/ProjectImporter.kt:558`, `:641`). The same
  `type === 'STACK'` filter appears in `F/hooks/useShowBarProps.ts:85-94` (the next-stack label
  beside GO, and the count behind `canStart`), `PromptBookPage.tsx:431` (its `canStart`),
  `F/components/runner/ShowOverview.tsx` (the Show view's stack list: every stack and separator drawn
  in `sortOrder` at `:473`, a drag sending the whole order at `:429-430`, and a distinct-cue count at
  `:346`), `ShowPage.tsx:154` and `ShowTab.tsx:75` (counts that decide whether the tab strip and the
  phone's stack picker are drawn), `RunMobile.tsx:206` (the FX-amount gate), `F/lib/cuePlaces.ts`,
  `F/lib/promptBook/useCueIndex.ts`, `F/lib/promptBook/geometry.ts`,
  `F/components/runner/CueActions.tsx:181`, `F/components/cues/CopyFromShowSheet.tsx:75`,
  `F/components/promptbook/CueAnchorPickerSheet.tsx:100` and `F/routes/Cues.tsx:82`;
  `StackPickerSheet.tsx:43` skips separators (`type === 'SEPARATOR'`).
  The only client caller of the REST deactivate is `F/hooks/useMakeStackLive.ts:37-41`: *Make this
  stack live* calls `/show/go-to`, which snaps the target's first cue on, then deactivates the target
  so it arrives armed. The off-playhead banner offers *Make
  this stack live* (`F/components/runner/OffPlayheadBanner.tsx`). A stack's own GO
  (`CueStackManager.go`, the surface's `CueStackGo`, MCP's `go_cue_stack`) never moves the playhead.
- **Priority.** A stack hold's priority is `stackId × STACK_PRIORITY_BAND + position × 1 000 + 1`
  (`cueDerivedPriority`, `K/fx/CueApply.kt:233`, band 1 000 000), so the stack *created* later wins
  LTP. Its producers: `cueDerivedPriority`, `stackEntryPriorities` (`CueApply.kt:248`) and its
  callers (the stack routes, `K/routes/projectCueStacks.kt:210`, `:246`, `:315`, `:383`, `:423`,
  `:636`, `:1053`; `POST /cues`, duplicate and copy-from, `K/routes/projectCues.kt:139`, `:446`,
  `:485`; `K/routes/cueDelete.kt:193`; `K/ai/AiTools.kt:1333`, `:1900`; `K/ai/VenueSetupTools.kt:382`;
  `programmerSurface.kt:153`; `history/KindEffects.kt:99`), `activateEntry`'s
  `goPriority` (`CueStackManager.kt:267`) and the Next-GO preview (`K/routes/cuePreview.kt:98`,
  `:141`). `stackOfDerivedPriority` decodes the stack back out of a priority in three places: the
  moved-entry guard in `CueHolds.restampEntries` (`CueHolds.kt:489`), `weighingStackOf`
  (`K/fx/StackFxAmounts.kt:77-81`, which reads the priority on purpose, so a leaving FX keeps the
  stack that let it go; `FxInstance.cueStackId` is documented as not that stack) and
  `MoveInDarkService.onEntriesRestamped` (`:294`). A reload (`Show.resume`, `K/show/Show.kt:486-489`)
  re-GOs every live stack through `activateEntry` in `ShowCarry`'s order, the key order of a
  `ConcurrentHashMap` (`K/show/ShowCarry.kt:60`), which is undefined. A loose hold is `LOOSE_BAND + seq`
  (`CueHolds.kt:933`, 2 000 000 000), newest on top. `restampEntries` (`:479`) restamps *live*
  holds only and calls `FxEngine.repriorityCues`; a cue whose last hold went keeps its published
  priority on its fading rows, and its leaving FX keep theirs.
- **Composition.** Intensity categories are HTP across every live cue, the rest LTP by priority
  (`K/fx/CueAssignmentResolver.kt:367`, `composeHtp` `:501`, `composeLtp` `:569`, which blends the
  winner with the highest-priority other contributor still weighted). FX are Tier 3, above every
  cue's values; `FxSuppression.isSuppressed` (`K/fx/FxSuppression.kt:33`) holds an FX back, on or
  off per tick, for a layer stomp or a programmer value, and `KeyStackLayer.HeldBackReason` is
  `STOMP | PROGRAMMER | BLIND`; the engine skips an FX's apply when it is held back
  (`FxEngine.kt:2669`, `:2735`, `:2769`, `:2819`), and an FX's weight after the blend is
  `amount × envelope × stackAmount` (`FxEngine.weightOf`). Manual and script FX default to priority 0
  (`FxInstance.kt:763`), below every cue, and the programmer's sit at `PROGRAMMER_FX_PRIORITY_BASE`
  (`FxEngine.kt:397`). The client types the reason in `F/api/programmerWsApi.ts:321`,
  `F/components/fxEditor/fxPreviewDecode.ts:26`, `F/store/fxPreview.ts:89` and
  `F/components/fixtures-list/CellFxLens.tsx:53`, and words it in
  `F/components/fixtureSheet/LayerStack.tsx:30-40`. Cue stomp removes other cues' ad-hoc FX for good
  (`FxEngine.stompForCue`, `K/fx/FxEngine.kt:2011`, called at `CueHolds.kt:666`).
- **Surfaces.** `PressPad(padUuid)` (`K/midi/BindingTarget.kt:354`) calls `BuskPressService`; its
  LED is `SurfaceFeedbackPublisher.padLed` (`:888`), fed by `State.buildBuskRefs`. Stacks have
  `CueStackGo`, `CueStackBack` and `CueStackPause`.
- **The OSC plan** (`docs/plans/completed/osc-plan.md`, DONE, all three sessions merged): lifts
  the router's dispatch into `K/midi/SurfaceDispatch.kt` with an `ActionOutcome`, makes `pressPad`
  return `BuskPressService`'s outcome, adds `CueStackStop(stackUuid)` → `deactivateStack`, and addresses
  stacks by slug (`/lighting7/stack/{stack}/go`, `/cue/{n}/go`, `/stop`) and pads by position
  (`/busk/{page}/{bank}/{pad}/press`). No `formatVersion` change and no migration.
- **Copying.** Clone and sync go through export and import. `copy_to_project` builds a copied stack
  field by field (`K/ai/VenueSetupTools.kt:591`).
- **Client.** The stack form is `F/components/cues/CueStackForm.tsx`; the Show view's tabs
  `F/components/runner/StackTabStrip.tsx`; a pad `F/components/busking/BuskPad.tsx` and
  `padFace.ts`; the library palette `F/components/busking/LibraryPalette.tsx` (filters
  `all | palette | look | cue`); placement from elsewhere `F/components/busking/AddToBuskPageMenu.tsx`;
  a stack's run state `selectStackRunner` (`F/store/runnerSlice.ts:423`), fed by
  `cueRunStateChanged` (`CueRunStateEvent` in `F/api/cueStacksApi.ts`, bridged in
  `F/api/cueStacksWsApi.ts`, landed by `applyServerRunState`, `runnerSlice.ts:339-350`); the
  deactivate mutation is `F/store/cueStacks.ts:446-460`. The client's `BuskPadKind`
  (`F/api/buskApi.ts:46`) is read by `F/lib/buskLayout.ts` (the library drag id `:143`, `padRecordId`
  `:204-207`, `newPad` `:263`, `padInput` `:317-324`, each falling through to `cueId`),
  `F/lib/buskAdd.ts` (`buskAddBody` `:163-171`), `F/components/dnd/edgeDrag.ts:150`,
  `F/lib/handTargets.ts`, `F/store/hand.ts`, `F/api/handApi.ts` and `BuskingView.tsx`'s per-kind
  branches (`:380-424`). `get_current_state`'s stack report gives `loop` and `moveInDark`
  (`K/ai/AiTools.kt:755-779`); MCP's `advance_cue_stack` also calls `advanceStack` (`:1770`).
- **Versions.** Sync is at `formatVersion` 49 (`SUPPORTED_FORMAT_VERSION`,
  `K/sync/ProjectImporter.kt:397`); `CueStackJson` and `BuskPadJson` are in
  `K/sync/dto/SyncDtos.kt` (`:997`, `:1331`). `PRAGMA user_version` is 12.

## 2. Decisions

From the page's Decisions, Q1–Q12, all answered with the recommendation.

| # | Decision |
|---|---|
| D1 | **Busk stack.** A stack with **In the running order** off is a *busk stack*; its pad is a *stack pad* (Q1). Every stack that exists, and every new one, is in the running order. |
| D2 | **The running order is the stacks in it.** The playhead's activate, `/show/advance` in either direction and `/show/go-to`, and the client's boundary GO and next-stack label, see only stacks in the running order; `/show/go-to` on a busk stack is 409 `CUE_STACK_NOT_IN_SHOW`. A busk stack still runs from its own GO, a pad, a binding, MCP and OSC. |
| D3 | **After the last cue: Stay, Loop or Release** (Q3). Release is a GO past the last cue entry, or a follow off it, stopping the stack (D6). Loop and Release together are refused. Making a stack a busk stack (a `POST` or a `PUT`) sets Release unless Loop is on. On the playhead stack, the Show view's GO on its last cue releases it, and the next GO goes on to the next stack in the running order. |
| D4 | **Busk stacks are listed after the show's own** on the Show view's tabs, past a *Busk* divider, with the live cue; readable and editable, never the playhead (Q7). The off-playhead banner offers *Jump to live* and never *Make this stack live* for one, and turning the playhead stack out of the running order is 409 `CUE_STACK_IS_PLAYHEAD`: stop the show or move the playhead first. An undo that does it clears the playhead, as an undo of a stack's delete does. |
| D5 | **Latest GO wins, in two bands** (Q9). An operator's cue change on a stack (a start, GO, GO TO, BACK or pad press) makes it the newest of its band; a follow keeps the stack where it is, so a looping chase never climbs over the operator's latest GO. The show's stacks are one band, busk stacks a band above, loose presses and the programmer above both. Entry position still orders the two cues of one stack's crossfade. |
| D6 | **A deliberate stop fades** over the stack's **Release time** (default 2 s, 0 snaps), on every stack (Q11): Release at the end, the REST deactivate, `/show/deactivate`, MCP's `deactivate_cue_stack`, the pad's *Release*, a solo bank's release of a sibling stack, `CueStackStop` and OSC's `/stop`. Each cue the stack had on stage fades from where it stood, or stays at full where another hold keeps it. A stack delete, the undo restorer and the show's advance and go-to from an active stack let go at once, as now; a release already running is left to finish. The stack reads stopped at once; only its rows and FX fade. Any stop drops the effects arm, as now (Q8). |
| D7 | **Over the show: Adds or Replaces**, busk stacks only (Q10, Q11), Adds the default. *Adds* is today's composition in the busk band. *Replaces* makes a busk stack's cues the floor of every key they set, HTP categories included: what is below them on that key is blended out by the stack's combined fade weight from the value it composes to, and anything above them composes over the floor by the key's own rule; replacing stacks fold in priority order. A stack's start snaps on, as every start does; its GOs crossfade within it, and its release hands back with no jump. |
| D8 | **Replaces holds FX back, never removes them.** On a key a replacing stack sets, every FX of lower priority but the programmer's (cue FX, and manual and script FX at priority 0) is held back with a new reason, `REPLACED`, by the stack's combined fade weight: fully at weight 1, handing back as it fades out, phase kept throughout. The busk stack's own FX and the programmer's run. |
| D9 | **A stack pad** is a fourth pad kind, naming a **busk stack only** (Q2). Tap is GO. Its menu: GO, Back, Release (each only where it does something) and View; no *Pick up*. |
| D10 | **Solo** (Q4): a stack pad's press that *starts* its stack takes siblings off as a cue pad's on press does, and also stops every running sibling stack (D6); one that advances takes nothing off. A palette, Look or cue pad's on press also stops running sibling stacks. |
| D11 | **Back into the running order with pads** is 409 `CUE_STACK_ON_PADS` naming the pages; `?force=true` takes the pads off (Q5). |
| D12 | **Beside the OSC plan** (Q12): stopping a stack from a button is its `CueStackStop`; this plan adds no binding of its own. Sessions 3 and 4 wait for OSC session 1 to merge, since they change the press path and the dispatch it introduces. |

The plan adds three of its own:

- **P1 — Four sessions, four stacked PRs**, in §5's order, each run by `/next-session`
  (`.claude/skills/next-session/SKILL.md`) through `/verified-ship`. A session stacks on the previous
  session's branch while that PR is open, and on `main` once it has merged. A session whose
  `**Needs:**` names another plan's session also checks its base after `/next-session` §2 picks it,
  since §1.5 checks the need on `main` while §2 may stack on an unmerged branch (see session 3).
- **P2 — A format bump per session that adds a portable field**, each raising `minReader` with it:
  an older desk would otherwise read a busk stack as one in the running order, a replacing stack as
  an adding one, or a stack pad as naming nothing, and push that back.
- **P3 — Priorities are ranks in fixed bands, not counters.** D5's order is each live stack's dense
  rank in its band, renumbered on every operator GO, because priorities are `Int`s below
  `LOOSE_BAND` and a GO counter times the band width would reach it after about two thousand GOs.
  The bands' bases are fixed so creating or deleting a stack moves nothing.

## 3. The model

### Stack settings

| Column | Type | Default | Wire | Sync |
|---|---|---|---|---|
| `cue_stacks.in_running_order` | boolean | `true` | `inRunningOrder` | v50 |
| `cue_stacks.release_at_end` | boolean | `false` | `releaseAtEnd` | v50 |
| `cue_stacks.release_time` | `duration()` | 2 s | `releaseTimeMs` | v50 (`releaseTimeMs: Long = 2000`) |
| `cue_stacks.over_show` | `ADDS \| REPLACES` | `ADDS` | `overShow` | v51 |

All added by the reconciliation. On `NewCueStack` the four are nullable, as `moveInDark` is: absent
on a `POST` takes the column default, absent on a `PUT` keeps what is stored, except as below. `CueStackDetails` and
the stack list carry them. A request is 400 `CUE_STACK_INVALID` naming every problem at once, judged
on the merged result (the stored stack with the request laid over it): `loop` with `releaseAtEnd`, a
release time outside 0–60 s, `overShow: REPLACES` on a stack in the running order. A `POST` with
`inRunningOrder` false, or a `PUT` that turns it off, and that names no `releaseAtEnd` sets it unless
the merged `loop` is on (D3), MCP's `create_cue_stack` and `build_cue_stack` included; a `PUT` that
turns it on and names no `overShow` sets `ADDS` (session 2), so a Replaces busk stack can rejoin the
running order without tripping the rule above. Turning it off on the playhead stack is 409
`CUE_STACK_IS_PLAYHEAD` (D4), and the undo restorer clears `activeStackId` for a stack its targets
leave out of the running order, as it does for one it deletes; import and pull already clear it.
Turning it on with pads naming the stack is 409 `CUE_STACK_ON_PADS` (D11, session 3).

### Release (D3, D6)

`CueStackManager.stopStack(state, stackId)` is the deliberate stop. Its contract:

1. The stack reads stopped at once: it leaves `activeStacks`, its follow is cancelled, and the run
   state, the REST answer and every reader of the playhead see it stopped while its light fades.
2. Every cue the stack had on stage when the stop came (its live cue and, mid-crossfade, the cue
   that fade was taking out) ends in one of two states, never at part weight: a cue no hold keeps
   fades from the weight it stood at to 0 over the release time; a cue another hold keeps (a loose
   press, another stack) is pinned at full.
3. A stop on a stack that is already stopped and still releasing changes nothing: the release runs
   on.
4. The rest of `deactivateStack`'s teardown runs as now: the stack's own cue-less FX
   (`removeFxForCueStack`, `CueStackManager.kt:474`), Move in dark, standby, scenery and the effects
   arm. That tail (`:474-483`) becomes one private function both call.
5. A release time of 0 is today's snap.

What the code must change for it: the crossfade driver gains a release fade holding every cue of
(2), each with the weight it started from, ramped as w₀ × (1 − eased), its `cancel`,
`forgetOutgoing` and end-of-fade removal treating each as outgoing; the stop skips `takeOff`'s pin to
full (`CueHolds.kt:846-848`), which the GO path keeps; and `stopStack` does not run
`deactivateStack`'s `crossfades.cancel` (`:467-469`).

- **Starts.** A GO or restart of the stack mid-release snaps its new cue on, as every start from
  stopped does (`CueStackManager.kt:252`), and cancels the release, the releasing rows snapping off
  unless another hold keeps them. Fading a stack's first cue in is out of scope (§7).
- **Other stops.** A stack delete, a project switch or the undo restorer mid-release removes the rows
  at once. `/show/advance` and `/show/go-to` must call `deactivateStack` on the stack they leave
  unless it is stopped and still releasing. Today they call it whenever there is a previous stack
  (`projectShow.kt:152-154`, `:194-196`); with the change, a GO into Act 2 during Act 1's release
  leaves the release running. (Corrected in session 1: "only while it is active" would also skip a
  stopped stack's teardown, leaving a standby armed on it to fire when the show comes back.)
- **Release at the end** is `advanceStack` FORWARD from the last cue entry with `releaseAtEnd` set.
  It answers an explicit result rather than null (an `ActivateResult` with `released = true`, or a
  sealed result), and each caller words it: the REST advance (`projectCueStacks.kt:495`) the
  stack's stopped state, MCP's `go_cue_stack` (`AiTools.kt:1860`) and `advance_cue_stack`
  (`AiTools.kt:1770`) "released stack N", the surface (`SurfaceActions.kt:344`) nothing, the follow
  (`CueStackManager.kt:564`) nothing. When a follow releases it, the stop runs after the follow's job
  has done its last suspending call, so cancelling that job is harmless; the plan's test pins it.
  `go` on a stopped stack with no cue entries still throws, and the pad maps that to `empty`.
- **Who stops which way.** The REST deactivate, `/show/deactivate`, MCP's `deactivate_cue_stack`,
  the pad's *Release* and a solo release call `stopStack`; OSC's `CueStackStop` moves onto it in
  session 4. A stack delete, `/show/advance`, `/show/go-to` and `RecordRestorer` keep
  `deactivateStack`. *Make this stack live* keeps today's snap: the REST deactivate takes
  `snap: true`, which calls `deactivateStack`, and `useMakeStackLive` sends it.
- **Nothing on deck.** The run state carries `releasedAtEnd` for a stack stopped this way until its
  next start, and `stacksWithRunState` includes such a stack, so a window that connects later learns
  it. The server still names the first cue as next, which the stack pad's *GO starts Q1* reads. One
  client predicate, *nothing on deck* (`standbyCueId == null`, or the playhead stack has
  `releasedAtEnd`), is read by `useShowTransport`'s GO (`:309-318`), `useShowBarProps`'s next-stack
  label (`:85-89`) and RunMobile's GO/END label and `goDisabled` (`RunMobile.tsx:148-149`).
- **GO releases.** On the playhead stack with `releaseAtEnd` and its live entry its last cue entry,
  a second predicate, *GO releases*, sends the Show view's and RunMobile's GO to the stack's own
  advance, which releases it, rather than to `/show/advance`; the next GO is then the boundary GO, and
  on the last stack in the running order the label reads END.

### Priority (D5, P3)

One function gives a stack hold's priority: `band base + rank × STACK_PRIORITY_BAND + position ×
1 000 + 1`. The show band's base is 0 and the busk band's is `1 000 × STACK_PRIORITY_BAND`
(1 000 000 000); ranks run 1–999 in each, so both stay below `LOOSE_BAND`. A 1 000th live stack in
one band is refused at its start, `CUE_STACK_LIMIT`. `rank` is the stack's place among its band's
live stacks by latest operator GO (1 the oldest), renumbered densely when a stack starts, takes an
operator GO or changes band, and when a stopped stack's release fade ends; a follow does not renumber
(D5). A stopping stack keeps its rank, counting in its band's order and in `CUE_STACK_LIMIT`, until
its release fade ends, so its fading rows never share a rank with a live stack. A stopped stack's GO
takes the top rank of its band.

- **Producers.** `cueDerivedPriority(stackId, sortOrder)` gives way to that function, and
  `stackEntryPriorities` and every caller, `activateEntry`'s `goPriority` and every build no hold
  has stamped go through it: the fallback in `cueDerivedPriority(cueData)` (`CueApply.kt:226-227`)
  for a null `livePriority`, reached by the Next-GO preview (`K/routes/cuePreview.kt:98`, `:141`),
  Include (`K/routes/programmerInclude.kt:95`), Update's `sourceRowKeys`
  (`K/routes/programmerUpdate.kt:173`) and the cook of a cue with no hold (`CueApply.kt:581-586`).
  An unstamped build composes at the rank its GO would take: the band from the stack's
  `inRunningOrder`, the stack's current rank if it is live, else the top of its band. A cue with no
  stack keeps today's `position × 1 000 + 1`, below every stack. `cuePreview.kt:98`'s `maxOf` still
  holds, since a GO's rank is never below the held one.
- **Restamping.** `stackEntryPriorities` returns its stack with the map, and `restampEntries` and
  `MoveInDarkService.onEntriesRestamped` take priorities per (stack, entry), so the moved-entry guard
  compares stack ids instead of decoding a priority. A rank change restamps every live hold of the
  stacks it moved, **and** the rows and leaving FX of a crossfade or release still in flight on those
  stacks, which the crossfade driver knows per stack, so a fading cue can never land in a rank another
  stack now holds. Move in dark's +500 offset, and its settle's `maxOf` over the previous entry's priority, stay
  inside the stack's band.
- **Weighing.** `weighingStackOf` resolves a priority to its stack through a lookup `CueHolds` keeps
  for live and releasing ranks, unambiguous because a releasing stack keeps its rank, so a leaving FX
  keeps the stack that let it go. `FxInstance.cueStackId`
  is not used for it.
- **Reload.** A reload keeps the ranks: `ShowCarry` carries each live stack's band and rank, and
  `Show.resume`'s re-GOs restore them rather than counting as operator GOs.
- `stackOfDerivedPriority` goes once those three readers have moved.

### Replaces (D7, D8)

A cue replaces while any of its holds is a stack on Replaces, whatever its top hold, so a loose press
on Carabossy's cue keeps it replacing. An `overShow` change on a live stack applies at its next GO.
For each key the resolver folds upward in priority order, one replacing stack at a time:

1. The floor starts as *V*₀: the non-replacing contributors below the lowest replacing stack,
   composed by the key's own rule.
2. For each replacing stack *s* on the key, in priority order: its live and fading rows (a
   crossfade or release has two) compose among themselves by the key's rule into *R*ₛ, with weight
   *W*ₛ = min(1, Σ*w*); the floor becomes the floor blended toward *R*ₛ by *W*ₛ; then the floor,
   carrying weight 1, composes by the key's rule with the non-replacing contributors above *s* and
   below the next replacing stack.
3. The last step's floor composes with everything above the highest replacing stack (a loose press,
   a busk stack GO'd later) the same way.

With one replacing stack this is: *V* below, blended toward *R* by *W*, with anything above composed
over it. The floor always carries weight 1 into the next composition, since it already holds the fade
(`composeHtp` sums value × weight, `CueAssignmentResolver.kt:501-507`, so the fade must not be
applied twice). A non-replacing stack between two replacing ones composes over the lower one's floor
and is blended out by the higher one's *W*.

A replacing stack's set keys and *W*ₛ are published beside its rows, as `CookResult.assertedKeys` is.
On a replaced key every FX but the programmer band's (cue FX, and manual and script FX at priority 0)
is weighed by ∏(1 − *W*ₛ) over the replacing stacks above it. The weight is a factor after the blend
beside `amount × envelope × stackAmount` (`FxEngine.weightOf`), read at the four apply sites, by
`FxSampler` and by provenance; `heldBackReason` reads `REPLACED` while that product is below 1.
`FxSuppression.isSuppressed` stays a boolean for stomp, the programmer and Blind. A busk stack's own
FX are not held back by itself.

### Stack pads (D9, D10, D11)

`busk_pads.cue_stack_id`, a nullable reference to `cue_stacks`, the CHECK widened to four arms, and
`BuskPadKind.CUE_STACK`. One-shot **step 13**, `BuskPadStackMigration`, after step 12: while
`sqlite_master`'s DDL for `busk_pads` has a CHECK that does not name `cue_stack_id` (the column
itself appears in that DDL once the reconciliation has added it, so the CHECK expression is what is
read), the table is rebuilt from the model through `rebuildFromModel` after a `VACUUM INTO
'<db>.pre-busk-stack-<utc>.db'` backup, rows copied by id; a database without that CHECK is only
marked 13. The layout `PUT` and `POST …/banks/{bid}/pads` take `cueStackId` as the fourth exactly-one
id, 400 `BUSK_LAYOUT_REF` for a stack in the running order (D9); `BuskPadDto` embeds
`{id, uuid, name}`. A stack delete sweeps its pads in `deleteCueStackRows` and announces their pages;
a rename announces them too. The other exhaustive readers of `BuskPadKind` take the new arm:
`State.buildBuskRefs` maps a stack pad to no ref (unlit until session 4),
`State.handRecordResolves` answers false, the hand's reconcile skips it, and a `hand.pickUp` of a
stack pad is refused at `HandSocket` and `HandService`. The press answers `kind: "CUE_STACK"` with
`action` `started | advanced | released | stayed | empty`. Sync v52: `BuskPadJson.cueStackUuid`,
read with the importer's `cueStackMap`.

### Wire, history, MCP

- No new WebSocket frame: the pad reads `cueRunStateChanged` through the runner slice and the cue
  names from the stack list; the run state gains `releasedAtEnd`. `programmer.keyStack` gains
  `heldBackReason: REPLACED`.
- Settings, pads and deleted stacks with their pads restore through the journal's existing folders
  (`cueStacks/`, `buskPages/`); no new kind.
- `create_cue_stack` (`K/ai/AiTools.kt`, `K/ai/AiToolSchemas.kt`) and `build_cue_stack`
  (`K/ai/SetupTools.kt`, `K/ai/SetupToolSchemas.kt`) take the four settings, `copy_to_project`'s stack copier carries them, `go_cue_stack` and
  `advance_cue_stack` report a release, and `get_current_state` reports each stack's
  `inRunningOrder`, `releaseAtEnd`, `releaseTimeMs` and `releasedAtEnd` (session 1) and `overShow`
  and, per replaced key, the replacing stack and the FX it holds back (session 2).

## 4. UX

- **Stack settings** (sessions 1–3): *In the running order*; *After the last cue* (Stay, Loop,
  Release); *Release time*; for a busk stack *Over the show* (Adds, Replaces) (session 2) and *Put on
  a busk page…* through `AddToBuskPageMenu` (session 3).
- **Show view** (session 1): busk stacks after a *Busk* divider on the tab strip, a live dot and the
  live cue's number on each running one; the boundary GO and the next-stack label skip them, as
  `/show/advance` does in either direction; the banner's *Make this stack live* is not offered for
  one.
- **Stack pad** (session 3): taller than a cue pad, three lines (the stack's name, the live cue's
  number and name, the next cue) and a fade bar; solid green while running; *GO starts Q1 …* while
  stopped; *no cues* when empty. The library's filter gains *Stacks*, listing busk stacks.
- **Surfaces** (session 4): a `PressPad` LED lit while the pad's stack runs.

## 5. Implementation — four sessions

Each session is one PR. Each is run by `/next-session`, which cuts the branch, stacks it on the
previous session's branch while that PR is open (on `main` once merged), records the previous
session's PR and head SHA in the table below, and hands the session to `/verified-ship`. A session
ends with its checks green (§"Pre-commit checks" in the root `CLAUDE.md`), the docs it names
written, and its row marked *in review* with its branch. A session's PR is titled
*Stack pads: session N — title*. Never merge a later session's PR before an earlier one.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | Busk stacks and the running order | merged | `claude/blissful-pasteur-r0ys12` | [#392](https://github.com/cjcormack/lighting7/pull/392) | `d1a99f6` |
| 2 | Over the show | in review | `claude/busk-stack-s8cddn` | | |
| 3 | Stack pads | planned | | | |
| 4 | Surfaces | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — Busk stacks and the running order

- **Storage and wire:** `in_running_order`, `release_at_end` and `release_time` on `DaoCueStacks`;
  the stack routes, nullable on `NewCueStack`, `CueStackDetails` and the list (§3 "Stack
  settings"), Release set on a `POST` or `PUT` that makes a busk stack; `CUE_STACK_INVALID` on the
  merged result; `CUE_STACK_IS_PLAYHEAD`, and the undo restorer clearing the playhead
  (`RecordRestorer.kt:273-282`).
- **Sync (P2):** `CueStackJson` gains the three fields; `SUPPORTED_FORMAT_VERSION` and the writer to
  50, `minReader` 50; `RichProjectFixture` gets a busk stack with Release and a 3 s release time.
- **The running order (D2, D3, D4):** `orderedStacks` filters on the setting; `/show/go-to` refuses a
  busk stack; `/show/advance` and `/show/go-to` leave a stack that is stopped and releasing alone.
- **Release (D3, D6):** `CueStackManager.stopStack` and its contract (§3 "Release"): the driver's
  release fade, the stop skipping `takeOff`'s pin, the shared teardown tail; Release at the end in
  `advanceStack` with its explicit result and every caller's wording; `releasedAtEnd` in the run
  state and in `stacksWithRunState`; the REST deactivate, `/show/deactivate` and MCP's
  `deactivate_cue_stack` onto `stopStack`, and the REST deactivate's `snap: true` onto
  `deactivateStack`.
- **MCP:** `create_cue_stack` and `build_cue_stack` take the three settings; `copy_to_project`'s
  stack copier carries them; `go_cue_stack` and `advance_cue_stack` word a release;
  `get_current_state` reports the three settings and `releasedAtEnd`.
- **Client, the run state:** `CueRunStateEvent.releasedAtEnd` (`F/api/cueStacksApi.ts`, bridged in
  `F/api/cueStacksWsApi.ts`), landed by `runnerSlice.applyServerRunState`; the deactivate mutation's
  `snap` argument (`F/store/cueStacks.ts:446-460`), which `useMakeStackLive` sends; the two client
  predicates, *nothing on deck* and *GO releases* (§3 "Release"), read by `useShowTransport`'s GO,
  `useShowBarProps`'s next-stack label and RunMobile's GO/END label and `goDisabled`.
- **Client, the running order:** one running-order selector (`type === 'STACK'` and
  `inRunningOrder`). Onto it: `useShowTransport`'s boundary GO (`:311`), `useShowBarProps`'s
  next-stack label (`:87`) and its `runnableStackCount` (`:94`), which only feeds `canStart`
  (`:114`), and `PromptBookPage`'s `runnableStackCount` (`:431`), which only feeds `canStart`
  (`:432`): these answer whether and where the show starts, which `/show/activate` and
  `/show/advance` answer from the running order. Keep the plain `STACK` filter, and say why at each
  site: `ShowPage`'s `runnableStackCount` (`:154`) and `ShowTab`'s (`:75`), which decide whether
  `StackTabStrip` (`:84`) and `RunMobile`'s stack picker (`multiStack`, `ShowPage.tsx:521`,
  `RunMobile.tsx:159`, `:269`) are drawn, and D4 puts busk stacks on them, so one show stack and a
  busk stack still draw the strip with its *Busk* divider; `RunMobile.tsx:206`, the FX-amount gate,
  which a busk stack keeps; and `StackPickerSheet` (its separator filter), `cuePlaces`, `useCueIndex`,
  `geometry`, `CueActions`, `CopyFromShowSheet`, `CueAnchorPickerSheet` and `routes/Cues.tsx`, which
  pick or list stacks for editing.
- **Client, the screens:** `ShowOverview`, the Show view's stack list, draws the running order's
  stacks and separators in `sortOrder`, then a *Busk* divider and the busk stacks in `sortOrder`, as
  the tab strip does (D4); a drag reorders within its group and still sends the whole order
  (`:429-430`), a drop across the divider changing no stack's band, which is the switch's; its
  distinct-cue count (`:346`) keeps every stack. `CueStackForm` (the switch, *After the last cue*,
  *Release time* on every stack, the switch setting Release); `StackTabStrip`'s divider and live cue
  (D4); `OffPlayheadBanner` without *Make this stack live* for a busk stack.
- **Tests:** show route tests advancing in both directions past a busk stack, go-to refused, the
  playhead guard, an undo that leaves the playhead on a busk stack clearing it, and a GO into the
  next stack during a release leaving the release running; a new
  `src/test/kotlin/uk/me/cormack/lighting7/fx/CueStackManagerTest.kt` for each clause of the stop's
  contract (a stop at 40 % into a fade-in falling from 0.4, never 1; a stop while a press holds the
  cue leaving it at full; a stop mid-crossfade where the press holds the incoming cue, the outgoing
  one still fading to 0; a stop on a releasing stack changing nothing; a stack-tagged cue-less FX
  removed), Release at the end by GO and by follow and its result, a restart mid-release snapping
  its cue on, 0 s and `snap: true` snapping, a delete still snapping, and an empty stopped stack's
  `go` still throwing; a socket test that a window connecting after a release at the end receives
  `releasedAtEnd`; the existing tests that stop a stack through a path moving to `stopStack` and
  read it as stopped straight after stay green on D6's "stopped at once" basis:
  `CueStandbyRouteTest` (`:220`) and `SceneryRoutesTest` (`:241`) through the REST deactivate, and
  `McpLookCueEditingTest` (`:512`) through `deactivate_cue_stack`; the tests that call
  `deactivateStack` directly (`CueHoldsTest`, `MoveInDarkServiceTest`, `CueUpdateStageRouteTest`,
  `FxFadeTest`, `EffectsRoutesTest`, `CueApplyDataBuilderTest`) keep its snap and do not change; the
  stack route's refusals, keep-on-`PUT` and the busk-stack Release rule on `POST` and `PUT`;
  `ProjectRoundTripTest`; `useShowTransport` (GO on a Release stack's last cue calls its advance; the
  next GO advances the show), `CueStackForm`, `StackTabStrip` (one show stack and one busk stack draw
  the strip and its divider), `ShowView.test.tsx` (the overview lists busk stacks after the divider,
  and a drag within a group sends the whole order), `F/routes/ShowPage.test.tsx`'s *Make this stack live* banner test (`:641`), extended to assert the
  deactivate sends `snap: true`, and RunMobile's GO/END label
  tests, and a new `F/hooks/useShowBarProps.test.ts` for the next-stack label after a release at the
  end and `canStart` false with only busk stacks.
- **Docs:** `docs/cue-stacks-engineering.md` (the running order, Release, the stop's fade);
  `docs/sync-engineering.md` (v50); the root `CLAUDE.md` (stack endpoints, the show's running order);
  frontend `CLAUDE.md` where it describes the Show view's tabs; `docs/glossary.md` rows for *busk
  stack*, *running order*, a stack's *Release* (after the last cue or a stop, not the programmer's
  *Release*) and *Release time*; `FU-MANUAL-BUSK-STACK-S1` in `docs/plans/manual-validation.md` (§6).
- **Local:** the full `./gradlew test` (a `formatVersion` bump) and `npm run check:quick`.

### Session 2 — Over the show

- **Priority (D5, P3):** the one priority function with fixed bands and ranks (§3 "Priority"); every
  producer through it (`stackEntryPriorities` and each caller listed in §1, `goPriority`, the
  Next-GO preview); a releasing stack keeping its rank until its fade ends; `ShowCarry` carrying the
  ranks; `restampEntries` and `onEntriesRestamped` per (stack, entry); a rank change restamping live
  holds and in-flight fades; follows keeping their rank; `weighingStackOf` through the
  priority-to-stack lookup; `stackOfDerivedPriority` retired; `CUE_STACK_LIMIT`.
- **Replaces (D7, D8):** `over_show`, wire and `CUE_STACK_INVALID`'s new problem, and a `PUT`
  turning `inRunningOrder` on with no `overShow` setting `ADDS` (§3 "Stack settings"); sync v51,
  `minReader` 51, the fixture's busk stack on Replaces; the resolver's floor (§3 "Replaces"), which
  hold makes a cue replace, and an `overShow` change landing at the next GO; the replaced weight as a
  factor beside `FxEngine.weightOf`'s at the four apply sites, in `FxSampler` and provenance, and
  `REPLACED` in `KeyStackLayer.HeldBackReason` and the key stack; MCP's two stack tools and
  `copy_to_project` carry `overShow`, and `get_current_state` reports it and what each replacing
  stack holds and holds back.
- **Client:** *Over the show* in `CueStackForm` for a busk stack; `REPLACED` in each type that names
  the reason (`programmerWsApi.ts:321`, `fxPreviewDecode.ts:26`, `fxPreview.ts:89`,
  `CellFxLens.tsx:53`) and its own wording in `LayerStack.heldBackDetail` ("a replacing stack holds
  it; it resumes in phase as that stack fades"), `heldByYourValue` staying false for it.
- **Tests:** `CueHoldsTest` for ranks (a later operator GO on an older stack overtakes, a follow does
  not, a busk stack above a later show GO, ranks dense once a release ends, a stopping stack under a
  live one keeping its rank through its release and `weighingStackOf` naming it for its leaving FX, a
  GO on another stack mid-crossfade keeping the fading cue below the incoming one, a loose press
  still above); the resolver over each row of the page's §3 table under Adds and Replaces, a follow
  between two replacing cues that both set 0 staying at 0, a release of a mid-crossfade replacing
  stack going from 0 to the show's value with no jump, a loose press on a replacing cue keeping the
  wash at 0, a loose press above a replacing cue mid-fade on an HTP key, an Adds busk stack GO'd
  after a replacing one, a non-replacing busk stack between two replacing ones (C fading out with A
  at full shows B over A, and B's FX held back by C alone), and two show stacks under one replaced dimmer (the fade starting from their
  HTP value and ending without a jump); an FX held back by fade weight through a release, phase kept,
  a manual FX held back too and a programmer FX not; a reload with two live stacks keeping their
  order; `StackFxAmountTest` with a leaving FX after a renumber; `MoveInDarkServiceTest` with two live
  stacks; `get_current_state`'s report; a Replaces busk stack put back into the running order with no
  `overShow` landing on Adds, and one naming `REPLACES` refused; `ProjectRoundTripTest`;
  `LayerStack.test.tsx` and the `fxPreviewDecode` tests for `REPLACED`.
- **Docs:** `docs/lighting-composition-model.md` (the priority table under §"Holds and the loose
  band", a new §"Replacing stacks", the worked examples); `docs/fx-engineering.md` on suppression;
  `docs/cue-stacks-engineering.md` §"Holds"; `docs/sync-engineering.md` (v51); the root
  `CLAUDE.md`; `docs/glossary.md` rows for *Over the show*, *Adds* and *Replaces*, noting it is not
  the FX blend's *Replace it* (`OVERRIDE`); `FU-MANUAL-BUSK-STACK-S2`.
- **Local:** the full `./gradlew test` (composition across `fx`, `routes` and `sync`) and
  `npm run check:quick`.
- **As built** (where the code led away from the text above):
  - `stackEntryPriorities` became `stackEntryPositions`, `stackId → entryId → position`: a priority
    needs the stack's rank, which a transaction cannot know, so `CueHolds.restampEntries` turns the
    positions into priorities at each stack's place and hands Move in dark `stackId → entryId →
    priority`. The ranks are `fx/StackRanks.kt`, kept by `CueHolds.ranks`; `stackAt` is the
    priority-to-stack lookup `weighingStackOf` reads.
  - A cue's replace group is its top hold's stack when that hold replaces, else the cue's own
    (`ownReplaceGroup`, negative): a loose press on Carabossy's cue keeps it replacing at the press's
    priority, without folding it in with the cue her stack GOs to next. A group's place on a key is
    the lowest priority its rows hold there; an effect is held back by a group below which it sits,
    unless it is one of the group's cues' or in its stack's rank (a leaving cue's).
  - The resolver's specificity rule (a fixture row beating a group row on one head) has always reached
    across cues; it now runs within each replace group and among the rows that compose by the key's
    rule apart, so a show cue's fixture row cannot cancel a replacing stack's group row. The Next GO
    preview stamps a replacing stack's incoming rows with its group, as its GO's hold would.
  - An effects-only cue is held in a stop's release too, with no rows to ramp, so its stack keeps its
    rank until its effects have shrunk. Rank changes are serialised in `CueStackManager` and restamp
    from the places as they stand, and a release's end gives up no rank a GO is taking. An undo that
    moves a running stack into or out of the running order moves its band as the stack `PUT` does.
  - `REPLACED` is a weight rather than a switch: the key stack and the sampler paint a replaced
    effect at what is left, and provenance skips one weighed to 0.
  - A live stack moved between the running order and the busk band takes the top of its new band
    (`CueStackManager.onBandChanged`).
  - An unstamped build reads its stack's band from the entry's placement
    (`CueApplyData.stackInRunningOrder`).
  - The Values grid's FX lens has `REPLACED` in its vocabulary but no way to answer it: it infers a
    stomp from provenance naming a cue on the key (`CellFxLens.lensHeldReason`), which is also what a
    replacing stack fully in looks like there, so it words that hold as a stomp. The fixture sheet's
    stack and the FX editor's preview, which read the desk's reason, say it right.

### Session 3 — Stack pads

**Needs:** cjcormack/lighting7 docs/plans/completed/osc-plan.md session 1 merged

`/next-session` §1.5 checks this on `main`, but §2 may stack on the previous session's branch. So
after §2 the session also checks its base: `git merge-base --is-ancestor <OSC session 1's head>
origin/<base>`. OSC session 1's head is its *Head SHA* in osc-plan.md's Sessions table when recorded;
that column is filled only when OSC session 2 starts, so until then it is the head (or merge commit)
of OSC session 1's merged PR, found as `/next-session` §1.3 finds one: its *Branch* from that table,
then `list_pull_requests` with `state: all` and `head cjcormack:<branch>`, the PR whose `merged_at` is
set. If the base lacks it, the run stops before
cutting its branch and ships nothing, as §2 stops on a broken base: it commits only on its own branch,
never on the previous session's. Run it again once the previous session has merged (the base is then
`main`, which the need has shown holds OSC session 1), or once the operator has merged `main` into
that branch with a merge commit, never a rebase (root `CLAUDE.md` §"Git workflow").

- **Storage:** `busk_pads.cue_stack_id`, the four-arm CHECK, `BuskPadKind.CUE_STACK`, `buskPadKind`;
  step 13, `BuskPadStackMigration` (§3 "Stack pads"), its frozen
  `src/test/resources/db/pre-busk-stack.sql` written from a step-12 database and never regenerated.
- **Routes:** the layout `PUT`, the append and `BuskPadDto`; D9's and D11's guards; the delete sweep
  and the rename announce; the new arm in `State.buildBuskRefs`, `handRecordResolves` and the hand's
  reconcile; `HandSocket` and `HandService` refusing a stack pad.
- **Sync (P2):** `BuskPadJson.cueStackUuid`, the importer and exporter, 52 and `minReader` 52; the
  fixture's stack pad.
- **The press (D9, D10):** `BuskPressService`'s `CUE_STACK` arm (start, advance, Release at the end,
  empty) and solo, a sibling stack stopped through `stopStack`, reached by the busk route and,
  through OSC session 1's `SurfaceDispatch`, `PressPad`.
- **Client:** every client reader of `BuskPadKind` gets a `CUE_STACK` arm (grep the type): the
  library drag id, `padRecordId`, `newPad` and `padInput` in `F/lib/buskLayout.ts` send `cueStackId`,
  and so does `buskAddBody` in `F/lib/buskAdd.ts`; the hand surfaces (`F/lib/handTargets.ts`,
  `F/store/hand.ts`, `F/api/handApi.ts`, `F/components/dnd/edgeDrag.ts`) exclude it, matching the
  desk's refusal; `BuskingView.tsx`'s per-kind press, menu and navigate branches take it. `BuskPad`'s
  stack face, menu and ghost, `padFace`, the library's *Stacks* filter, `AddToBuskPageMenu` in
  `CueStackForm` for a busk stack, `F/api/buskApi.ts` and `F/store/busk.ts`.
- **Tests:** `BuskPadStackMigrationTest`; press tests for each action and each solo row of the
  page's §5 table; the guard and layout route tests; the delete sweep; `HandSocketTest`'s malformed
  kind changed to a string no enum holds, and a stack pad's `hand.pickUp` refused;
  `ProjectRoundTripTest`; `BuskPad.test.tsx`, `LibraryPalette`'s tests, `buskLayout.test.ts` (a stack
  pad round-trips through `toLayoutRequest`) and `handTargets.test.ts`'s kind list.
- **Docs:** `docs/lighting-composition-model.md` §"The busk layout"; `docs/sync-engineering.md`
  (v52); the root `CLAUDE.md` (step 13 in §Database, the busk endpoints); `docs/osc-engineering.md`
  (a stack pad among the predictable pads); `docs/glossary.md`'s row for *stack pad*;
  `FU-MANUAL-BUSK-STACK-S3`.
- **Local:** the full `./gradlew test` (a migration and a bump) and `npm run check:quick`.

### Session 4 — Surfaces

**Needs:** cjcormack/lighting7 docs/plans/completed/osc-plan.md session 1 merged

`/next-session` §1.5 checks this on `main`, but §2 may stack on the previous session's branch. So
after §2 the session also checks its base: `git merge-base --is-ancestor <OSC session 1's head>
origin/<base>`. OSC session 1's head is its *Head SHA* in osc-plan.md's Sessions table when recorded;
that column is filled only when OSC session 2 starts, so until then it is the head (or merge commit)
of OSC session 1's merged PR, found as `/next-session` §1.3 finds one: its *Branch* from that table,
then `list_pull_requests` with `state: all` and `head cjcormack:<branch>`, the PR whose `merged_at` is
set. If the base lacks it, the run stops before
cutting its branch and ships nothing, as §2 stops on a broken base: it commits only on its own branch,
never on the previous session's. Run it again once the previous session has merged (the base is then
`main`, which the need has shown holds OSC session 1), or once the operator has merged `main` into
that branch with a merge commit, never a rebase (root `CLAUDE.md` §"Git workflow").

- **LED:** `State.buildBuskRefs` gives a stack pad its ref, and `SurfaceFeedbackPublisher`'s `padLed`
  lights it while its stack runs, resynced on the stack's run-state changes.
- **Stop:** OSC's `CueStackStop` onto `stopStack`, so a button and `/stop` fade over the release
  time (D6, D12).
- **Tests:** the feedback test for a stack pad's LED through start, GO and release; a router test
  that `CueStackStop` fades.
- **Docs:** `docs/midi-control-surface-engineering.md`; `docs/osc-engineering.md`;
  `FU-MANUAL-BUSK-STACK-S4`.
- **Local:** `./gradlew compileTestKotlin`, then `./gradlew test` for the `midi`, `osc` and `fx`
  packages.

## 6. Operator checks

One row in `manual-validation.md` per merged session, on the rig:

- `FU-MANUAL-BUSK-STACK-S1`: a song stack out of the running order; Act 1's last GO goes to Act 2,
  not the song; the song's own GO and a follow off its last cue release it over its release time;
  the Show view's stop fades.
- `FU-MANUAL-BUSK-STACK-S2`: Carabossy on Replaces takes the front wash from 80 % to 0, holds the
  movers' chase back, and fades the kitchen back on release with the chase growing back in phase; a
  song on Adds lifts heads over the base and never darkens them; a GO on Act 1 during Carabossy
  lands under her.
- `FU-MANUAL-BUSK-STACK-S3`: a stack pad steps a song by taps, solo releases a sibling song, and
  moving the stack back into the running order asks first.
- `FU-MANUAL-BUSK-STACK-S4`: a bound `PressPad` lights with its stack; `CueStackStop` fades.

## 7. Explicitly out of scope

- Clear on GO, and any change to how the programmer sits over the stacks.
- Fading a stack's first cue in: every start from stopped snaps, busk stacks included.
- An on-screen GO or stop for busk stacks outside the busk view. A busk stack started with no pad
  (from OSC, MCP or a `CueStackGo` binding) stops by its Release at the end, `CueStackStop` or MCP.
- The hand carrying a stack pad.
- An MCP tool for busk pages.
- Replaces on stacks in the running order (D7).
- A priority number per stack.
