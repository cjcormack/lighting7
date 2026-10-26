# Flaky tests

> **Document status: STANDING.** This is both the register of flaky tests and a plan that
> `/next-session` runs. Unlike a feature plan it never moves to `completed/`: it stays here, and is
> idle whenever every session row is `merged`. To queue a fix, add a session (§6).

## 1. Context

The register lists tests that pass and fail on the same commit, one test per row. A session that
meets a red CI run checks here before root-causing it (root `CLAUDE.md` §"Follow-ups"). A run of
`/next-session` takes the next session (§5) and fixes the rows that session names.

Engineering follow-ups live in [`followups.md`](followups.md). The suite's own rules (the forks, the
pinned data dir, the tests that assert real elapsed time, the order-dependence script) are in
[`../testing-engineering.md`](../testing-engineering.md).

**A test goes in the register only once it has failed on a commit it also passes on.** A failure
that reproduces every time is a bug, not a flake. Record:

- where it was seen failing, as a CI run (or "local") and its commit;
- how often it fails, if you measured it;
- the suspected cause, and whether you traced it or only suspect it.

## 2. Decisions

| # | Decision |
|---|---|
| D1 | **Never skip, disable, quarantine or retry a flaky test** to get CI green: no `@Ignore`, no `it.skip`, no Vitest `retry`, no rerun plugin. Fix it, or leave it in the register and re-run. |
| D2 | **Reproduce before fixing.** A session first makes the failure happen on demand: a test that forces the interleaving (as `FxRemovalRaceTest` does), a delay injected while diagnosing, or a measured recipe (*fails 6 of 20 runs beside `./gradlew test`*). The fix is judged against that. A session that cannot reproduce a row records what it tried in that row's *Suspected cause*, leaves the row open, and says so in its PR. |
| D3 | **Fix the code or the test, and say which.** When the flake is a real race that a client or the rig could meet (a snapshot published before it is complete, an order two writers can take either way), fix the code under test. When only the test is wrong about timing, fix the test. A wall-clock wait is never the fix for a race. |
| D4 | **A timeout is raised only for work that is large and fixed in size**, such as a cold import of many modules. The raise goes at the site, with the measurement in a comment. Anything else that times out under load has its work cut down. |
| D5 | **Done means**: the recipe from D2 passes 20 of 20 runs under the load that failed it, the row moves to [§4 Fixed](#4-fixed), and anything durable goes in `testing-engineering.md`. |

The plan's own rules:

- **P1: sessions are independent but run in order.** `/next-session` stacks a session on the
  previous one's branch while that PR is open. Nothing here needs another session's code, so
  merging each PR before running the next is simplest.
- **P2: the register belongs to every session.** Any session, from any plan, adds an Open row
  when it sees a flake. Only a session of this plan moves a row to Fixed, unless another change
  fixes a flake in passing, in which case that change moves the row and cites its own commit.

## 3. Open

*Session* names the session (§5) that takes the row. It is blank for a row no session has taken yet.

| Test | Seen failing | Suspected cause | Session |
|---|---|---|---|
| `RigSafetyRoutesTest` "output during the cool-down drops it, and making safe again keeps each lamp's cool time", "each lamp still says when its mains can be cut" | local `./gradlew :test --tests 'uk.me.cormack.lighting7.routes.*'` (with six other packages) on `claude/fx-settings-s2` before its first commit (2026-10-26, Linux cloud container), 1 of 1 package runs; passes alone, 14 of 14 | Not traced. Seen on a commit without session 2's fix (9dc4e15), which stopped a run being published finished before its end was acted on; that race may be the cause, but these cases were not run against the fix | |
| `ProjectActionPlaces.test.tsx` (frontend), *for a show behind its venue*: `Test timed out in 5000ms` | a full local `npm run check` on `claude/fx-editor-followups-s2` before its first commit (2026-10-26, Linux cloud container, a backend suite and two review agents running beside it), 1 of 1; passes alone | Suspected, not traced: load. The container was also running the full backend suite, and the test renders three menus in one case | 4 |
| `errorToastMiddleware.test.ts` (frontend) "only names endpoints that actually exist", a 5005 ms timeout | local full `npm run check` on `claude/colour-everywhere-else` before its first commit (2026-10-19), 1 of 2 full runs, at a load average of ~8; 10 of 10 alone | Suspected, not traced: the test dynamically imports about 35 store modules, and their cold transform under a loaded full run takes longer than the 5 s default | 4 |

## 4. Fixed

*Fixed in* is the commit, or the session whose *Head SHA* (§5) carries the fix.

| Test | Fixed in | What it was |
|---|---|---|
| `PatchSheet.test.tsx` (frontend) "opens the ten swatches on Set and picks one; the same colour again sends nothing" and the Gel column's "takes the first match on Enter, and Open white clears a gel", and `AppliedTables.test.tsx` (frontend) "the Scenery table > draws Open only on a drawn drape…": the second write not yet sent when asserted | session 3 | Seen locally: PatchSheet's swatch case 4 of 4 runs of the file on 500dc85 (2026-10-23, macOS), AppliedTables every run of the file on b6964df and `claude/terminology-s3` (2026-10-23); PatchSheet passed in that commit's CI run, AppliedTables alone and in a full `npm run check`. The Gel case was never seen failing; it makes the same two commits, and the forcing below found it with the other two. Traced: no popover was left open, and nothing a case leaves behind reaches the next. `useSheet` sends every commit from a cell's editor through `useLivePush` with a 33 ms floor, so a second commit within 33 ms of the first is held on a timer and lands when the floor lifts. Each case makes two commits and asserted the second one synchronously, which holds only on a machine slow enough to take 33 ms between them. That the Mac running PatchSheet's file was fast enough to fail, and CI and a full run were not, is inferred rather than measured, and so is why the swatch case passed alone there (a cold first case is slower). The writes were never lost, so the tests were wrong (D3): each asserts its second write through `waitFor`. Freezing `Date.now` for `useLivePush` alone forces it: the three cases failed every run on the base, and nothing else in the frontend suite failed |
| `RigSafetyRoutesTest` "make rig safe that cannot turn every lamp off blacks out but is not ready", a bare `AssertionError` | session 2 | Seen on #253's CI run 39954841494 (ab01733, 2026-10-23), 1 of 3796 tests. Two causes, both traced, either enough to fail it. The test held mac2 busy with a 2 s reset and needed the rig-safe POST to reach mac2 inside those 2 s; it now holds mac2 until teardown. And the snapshot it read could be torn, as in the row below: one with the run finished but the blackout not yet set fails `assertTrue(after.blackout)` |
| `RigSafetyRoutesTest` "undo strikes the lamps it turned off once their restrike time is up, then releases the blackout", `only the selection's lamp expected:<[mac1]> but was:<[]>` | session 2 | Seen locally in a full `./gradlew test` on `claude/terminology-s4` (2026-10-23). Traced, a code race (D3): `BulkCommandRun.execute()` published the run finished before `RigSafetyService` had acted on its end (the blackout, *Undo*'s lamps, `notOff`), so every window could see a finished *Make rig safe* with nothing to undo. A run now reads finished only once its end has been acted on. "a run is not finished until its end has been acted on" forces the window: it failed 5 of 5 runs on main |
| `StageRenderJob.test.tsx`, `BuskingView.test.tsx` and `store/cues.test.tsx` (frontend), an unhandled `ReferenceError: cancelAnimationFrame is not defined` after a file's tests pass, or `Hook timed out in 10000ms` on `test/setup.ts`'s closing frame wait | session 1 | Seen locally, each passing alone: `StageRenderJob` 1 of 2 runs of `npm run check:quick` on `claude/fixture-colours` (2026-10-19) and its hook timeout in 1 full `npm run check` on `claude/fx-editor-s5` (2026-10-25); `BuskingView` 1 of 1 `check:quick` on `claude/fx-editor-s4` (2026-10-24); `store/cues` 1 full `npm run check` on `claude/fx-editor-followups-s2` (2026-10-26). Traced: RTK Query's subscription middleware dispatches an auto-batched `subscriptionsUpdated` from a 500 ms timer after a subscription changes, and `resetApiState` does not cancel it, so a file's last render can notify after setup's one-frame wait. Batched on a frame, RTK's 100 ms fallback timer then outlived jsdom and called the deleted `cancelAnimationFrame`. The hook timeout was not load: a batched dispatch under fake timers (`StageRenderJob`'s) started jsdom's frame interval on the fake clock, so after `useRealTimers` no frame came for the rest of the file. Every store a test builds now batches to the end of the tick (`setup.ts` wraps `configureStore`) and the frame wait is gone; `storeBatching.test.tsx` forces both, failing 4 of 4 cases on main |
| `FxApplyOrderKeptTest` and `SameTypeFxWriteBackTest`, any case that awaits the dimmer after its FX come off (`clearStage`, a Look pressed off) | 48eb0ea | Traced: a removal reset what it uncovered without `passLock`, so a compose pass that had read the old FX list committed the removed FX's frame over the reset, and with nothing left running no later pass painted it out. After its commit a pass now resets what the FX in its list that are no longer in the engine leave uncovered; `FxRemovalRaceTest` forces the interleavings. Seen on `claude/eager-archimedes-ka6utl` and main's 5ee8331 (`FxApplyOrderKeptTest` alone, 2 of 10 runs); 10 of 10 runs of both passed after the fix |
| `CueSpeedMastersTest` "GO, GO TO, GO BACK and auto-advance each set the cue's masters, and a tap wins until the next cue" | a3949db | Traced: `activateEntry` sets the cue's tempo before the stack's active cue moves, so waiting on the BPM could end before the GO landed; the test now waits on the active cue |
| `ProgrammerDetachTest` "detach keeps the layer's effects running as Local effects" | 7a9c7cd | Traced: a running Pulse moved hex-1's dimmer channel between the test's two reads of hex-1's channels, whatever Detach did. The comparison now leaves out the channel the Pulse drives, found from the dimmer attribute (`DmxSlider.channelNo`), and the FX is asserted on its own. Seen on `main`'s CI (run 401, 1933e64), both attempts of #183's run 402 and 2 of 10 local runs; 20 of 20 passed after the fix |
| `StackTimeline.editing.test.tsx` (frontend) "one cell > a put-back toast carries no Undo", and the two ⌘Z tests beside it | d6d620f | Traced: ⌘Z undoes only while `canUndo`, which the press sets when its answer comes back, and the tests pressed ⌘Z as soon as the edit was sent. Seen locally on `claude/terminology-s4` and on #261's CI (frontend shard 1); with answers delayed 50 ms all three failed every time. They now wait for the press's toast, called once the Undo step is recorded |

## 5. Implementation: sessions

Each session is one PR, run by `/next-session`, which cuts the branch, records the previous
session's PR and head SHA in the table below, and hands the session to `/verified-ship`. A session
ends with:

- its checks green (root `CLAUDE.md` §"Pre-commit checks");
- D5's 20 runs recorded under its section;
- its rows moved to §4 with *Fixed in* `session N`, or left open with what it learnt (D2);
- its row in the table below marked *in review*.

A session's PR is titled *Flaky tests: session N — title*.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | The jsdom teardown frame | merged | `claude/flaky-tests-s1` | [#298](https://github.com/cjcormack/lighting7/pull/298) | c29dd54 |
| 2 | Rig safety's timing | merged | `claude/flaky-tests-s2` | [#300](https://github.com/cjcormack/lighting7/pull/300) | 9dc4e15 |
| 3 | The sheet's commit floor | in review | `claude/flaky-tests-s3` | | |
| 4 | Timeouts under load | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next. Whoever merges the last session fills in that session's own.

### Session 1 — The jsdom teardown frame

Rows: `StageRenderJob.test.tsx`, `BuskingView.test.tsx`, `store/cues.test.tsx`. All three have one
signature: `ReferenceError: cancelAnimationFrame is not defined` after a file's tests pass, or the
closing frame wait timing out.

- **What is there.** `frontend/src/test/setup.ts`'s `afterAll` waits one animation frame before a
  jsdom file's environment goes, so that RTK's auto-batch (a `requestAnimationFrame` raced against a
  100 ms timer) settles first. Its module comment gives the reasoning. That wait is not enough:
  either a dispatch lands after it, or the frame never comes under load and the hook times out
  (10 s).
- **Reproduce (D2).** Find the dispatch that queues the late notification, by logging the store's
  last dispatch per file or by delaying jsdom's frame in a copy of the setup. A test that forces it
  should fail on `main`.
- **Fix in one place, not per file.** Candidates to weigh, not a prescription: giving every test
  store an auto-batch that needs no frame (RTK's `autoBatchEnhancer({ type: 'tick' })` or
  `'timer'`); keeping a reference to `cancelAnimationFrame` past teardown; or waiting in `afterAll`
  for the store to be idle rather than for one frame. Whichever you pick must not change the app's
  production store. If it touches how the store batches, check that no test relies on frame timing.
- **Recipe for D5.** `npm run check` while `./gradlew test` runs beside it, 20 times, with none of
  the three files failing. If that is too slow, run the three files in a loop under the same load.
- **Docs.** Update `setup.ts`'s module comment to describe what the fix does now.
- **Result (2026-10-26, Linux cloud container, 4 cores).** The late dispatch is RTK Query's
  `subscriptionsUpdated`, from a 500 ms timer `resetApiState` does not cancel, seen queued after
  setup's frame wait by logging RTK's 100 ms timers. The hook timeout is not load: under fake
  timers the auto-batch's frame starts jsdom's frame interval on the fake clock, and after
  `useRealTimers` no frame comes. The plan's first candidate is the fix: `setup.ts` wraps
  `configureStore` so every store a test builds batches with `{ type: 'tick' }`, and the frame
  wait is gone. `storeBatching.test.tsx` forces both signatures on the app store and on a store a
  test builds: 4 of 4 cases fail on main, the last with `Hook timed out in 10000ms`, and with
  batching off the end-of-tick case fails. The full frontend suite passed with the fix (522 files,
  6281 tests), so no test relied on frame timing.
- **D5.** The three files in a loop under an 8-process CPU soak (load 10 to 21): on main 1 of 20
  runs failed (`StageRenderJob`'s hook timeout); with the fix, plus `storeBatching.test.tsx`, 0 of
  20 failed (load 6 to 17). `./gradlew test` was not run beside it; the soak stood in for it.

### Session 2 — Rig safety's timing

Rows: both `RigSafetyRoutesTest` rows.

- **"cannot turn every lamp off".** The test makes mac2 busy with a 2 s hold and relies on wall time
  for the rig-safe POST to land inside it. Hold mac2 deterministically instead: a hold that ends
  only when the test lets it go, through a seam on `CommandOutput` if none exists. Then mac2 is busy
  whatever the runner's speed. Check the class's other `holdMsFor = { 2_000 }` cases (the bulk run
  refused per unit, undo refused in flight) for the same pattern, and fix them the same way.
- **"undo strikes the lamps".** Trace `state/RigSafetyService.kt`: can a `rigSafety.state` with
  `run.finished` be published before `undo` is filled in? If so, this is D3's code case. A client
  would see the same torn snapshot, so the service publishes both in one update. If not, find what
  empties `undo` and record it.
- **Recipe for D5.** The class 20 times under a full `./gradlew test` or a CPU soak, plus whatever
  forcing test the trace yields.
- **Docs.** `docs/fixtures-engineering.md` §"Bulk commands and Make rig safe", if the service's
  publish order changes.
- **Result (2026-10-26, Linux cloud container, 4 cores).** Both rows are fixed, and the second is
  D3's code case. `BulkCommandRun.execute()`'s `finally` publishes the run with `finished` set, and
  only then does `RigSafetyService.begin` run its end: the blackout, *Undo*'s lamps, `notOff`. So a
  `rigSafety.state` with the run finished and those empty reached every window, and the frontend
  re-enabled *Undo* and *Release* while the server still refused them `COMMAND_RUN_BUSY`.
  `RigSafetyService.publish` now reports a run finished only once `active` no longer names it, which
  `begin` clears after the end has been acted on. The torn snapshot fails the first row as well: its
  first assertion is the blackout. Its 2 s hold was the plan's suspicion too. The class's three
  2 s holds (that row's, the per-unit refusal's, and undo refused in flight) are now held until
  teardown, which ends them. `holdMsFor` was seam enough, so `CommandOutput` gained none. The new
  "a run is not finished until its end has been acted on" reads the published snapshot from inside the
  window, through an Unconfined collector on `safeBlackout`. On main it failed 5 of 5 runs
  (`finished=true`, `undo=[]`, `blackout=false`). *Cancel* now follows the same rule: a run the frame
  still shows in flight answers 204 rather than 409 `COMMAND_NO_RUN`, and stops nothing once its
  last hold has ended.
- **D5.** The class, including the new test, 20 times under an 8-process CPU soak (load 2 to 11):
  20 of 20 passed. A full `./gradlew test` was not run beside it; the soak stood in for it.

### Session 3 — The sheet's commit floor

Rows: `PatchSheet.test.tsx` and `AppliedTables.test.tsx`. Both pass alone and fail after a sibling
case.

- **Reproduce.** Run each failing case alone, then after each earlier case in its `describe` (`-t`
  with a pattern matching both), to name the case that breaks it. Then find what that case leaves
  behind: an open Radix popover portal, a stub's call history, a module-level store, focus. Setup's
  `afterEach(cleanup)` unmounts renders, and `resetPointerFocus` resets one module's record. Check
  what neither reaches.
- **Fix the leak where it lives.** If it is a component holding state past unmount, that is D3's
  code case. If the tests share a stub or a store between cases, reset it per case. If the leak can
  bite any jsdom file, fix it in `setup.ts`.
- **Why CI passes PatchSheet** is worth one look (Linux and macOS jsdom timing, or the shard's
  ordering), but not a blocker.
- **Recipe for D5.** Each file 20 times on macOS and on Linux, or in whichever environments the
  session has.
- **Result (2026-10-26, Linux cloud container, 4 cores).** The plan's reading was wrong: nothing a
  case leaves behind reaches the next. Neither row reproduced here as found, on Node 22, 24 or 26,
  on this branch, on 500dc85 or on b6964df. A Vitest setup file that froze `Date.now` made both fail
  with their recorded assertions, AppliedTables' case run alone as well as in its file, and the Gel
  case's second half with them, though it was never seen failing. Why the swatch case passed alone
  on the Mac and failed after the Gel case is not shown; a cold first case taking longer than 33 ms
  between its commits would explain it. Logging that clock's callers led to `useSheet`'s commit
  path: every commit from a cell's editor (not a key typed over the marquee) goes through
  `useLivePush` at `COMMIT_INTERVAL_MS` (33 ms), and a commit inside the floor of the last send, for
  the same cell or another, waits on a timer and lands when it lifts, as `useSheet.test.ts` pins.
  Each case made two commits and checked the second at once, so it passed only where 33 ms went by
  between them. On the rig the write lands 33 ms late at most, so D3 puts the fault in the tests,
  and each now asserts its second write through `waitFor`. The forcing recipe is a setup file that
  freezes `Date.now` only when `useLivePush` is on the stack: on the base each file failed 5 of 5
  runs, and the whole frontend suite under it (522 files, 6281 tests) failed only these three cases.
  Frontend `CLAUDE.md`'s note on the sheet's throttle says to assert a second commit through
  `waitFor`. Why CI passed is inferred, not measured: its shared runner, like this container, takes
  longer than 33 ms between the two commits.
- **D5.** With `useLivePush`'s clock frozen, each file passed 20 of 20 runs; without it, each passed
  20 of 20.

### Session 4 — Timeouts under load

Rows: `ProjectActionPlaces.test.tsx` and `errorToastMiddleware.test.ts`.

- **Measure first.** Time each test alone and beside `./gradlew test` to find where the time goes:
  transform and import, render, or waiting.
- **`errorToastMiddleware`** imports about 35 store modules cold. That is D4's fixed, large work, so
  a raised timeout at the site, with its measurement, is acceptable. Cutting the imports (only the
  endpoints the test names, or the endpoint list read without loading each slice) is better if it
  keeps what the test proves.
- **`ProjectActionPlaces`** renders three menus in one case. Split it, or find the render that is
  slow under load. A raised timeout is not the fix here (D4).
- **Recipe for D5.** `npm run check` beside `./gradlew test`, 20 times, or the two files in a loop
  under the same load.

## 6. Adding a session

To queue a fix for open rows that no session has taken:

1. Add a row to the Sessions table, numbered after the last, with *Status* `planned`.
2. Add its `### Session N — title` section: the rows it takes, what is known, how to reproduce, and
   the recipe for D5.
3. Put *N* in each taken row's *Session* column.

Group rows that probably share a cause into one session. A row whose cause is unknown can be a
session of its own. Do this on a branch and merge it like any other change, so that `/next-session`
finds it on `main`.

## 7. Explicitly out of scope

- Speeding up the suite. `testing-engineering.md`'s rules apply, and a session that finds a slow
  test that is not flaky leaves it alone.
- Tests that fail every time. Those are bugs, fixed where they are found.
- CI configuration (runners, shards, retries; D1).
