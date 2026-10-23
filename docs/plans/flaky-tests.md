# Flaky tests

Tests that pass and fail on the same commit. Each row is one test, so a session that meets a red
CI run can check here before root-causing it, and a session with time can pick one up and fix it.
Engineering follow-ups live in [`followups.md`](followups.md); the suite's own rules (forks, the
pinned data dir, the tests that assert real elapsed time) are in
[`../testing-engineering.md`](../testing-engineering.md).

**A test goes here only once it has failed on a commit it also passes on.** A failure that
reproduces every time is a bug, not a flake. Record:

- where it was seen failing, as a CI run (or "local") and its commit;
- how often it fails, if you measured it;
- the suspected cause, saying whether you traced it or only suspect it.

A flaky test is never skipped, disabled or quarantined to get CI green. Fix it, or leave it here
and re-run. When one is fixed, move its row to [Fixed](#fixed) with the commit that fixed it, and
put anything durable in `testing-engineering.md`.

## Open

| Test | Seen failing | Suspected cause |
|---|---|---|
| `StageRenderJob.test.tsx` (frontend), an unhandled `ReferenceError: cancelAnimationFrame is not defined` after its tests pass | local `npm run check:quick` on `claude/fixture-colours` before its first commit (2026-10-19), 1 of 2 runs; passes alone | Suspected, not traced: an RTK Query auto-batch timer (`redux-toolkit.modern.mjs`'s rAF fallback) firing after the file's jsdom environment is torn down. The tests pass; Vitest fails the run on the unhandled error |
| `RigSafetyRoutesTest` "make rig safe that cannot turn every lamp off blacks out but is not ready", a bare `AssertionError` | #253's CI run 39954841494 (ab01733, 2026-10-23), 1 of 3796 tests; 6 of 6 local runs of the class pass on that commit | Partly traced: the test holds mac2 busy with a 2 s reset (`holdMsFor = { 2_000 }`) and expects *Make rig safe* to find it busy. Lamp-off starts every unit at once (`BulkCommandRun.together()`), so mac2 is refused only if the rig-safe POST reaches it inside those 2 s; on a loaded runner it can arrive later, mac2 turns off, `notOff` is empty and the run reads ready, failing `assertFalse(after.ready)`. Not confirmed which assertion failed: the log names only `Assert.java:89` |
| `PatchSheet.test.tsx` (frontend) "opens the ten swatches on Set and picks one; the same colour again sends nothing", `setPatchColours` never called after the Cyan click | local `vitest run` of the file on 500dc85 (2026-10-23, macOS), 4 of 4 runs; passes alone, and in that commit's CI run | Partly traced: it fails only after the Gel column's "takes the first match on Enter, and Open white clears a gel", which seems to leave a popover open, so the second Set opens no fresh swatches. Why CI's runner does not hit it is not known |
| `errorToastMiddleware.test.ts` (frontend) "only names endpoints that actually exist", a 5005 ms timeout | local full `npm run check` on `claude/colour-everywhere-else` before its first commit (2026-10-19), 1 of 2 full runs, at a load average of ~8; 10 of 10 alone | Suspected, not traced: the test dynamically imports about 35 store modules, and their cold transform under a loaded full run takes longer than the 5 s default |

## Fixed

| Test | Fixed in | What it was |
|---|---|---|
| `CueSpeedMastersTest` "GO, GO TO, GO BACK and auto-advance each set the cue's masters, and a tap wins until the next cue" | a3949db | Traced: `activateEntry` sets the cue's tempo before the stack's active cue moves, so waiting on the BPM could end before the GO landed; the test now waits on the active cue |
| `ProgrammerDetachTest` "detach keeps the layer's effects running as Local effects" | 7a9c7cd | A running Pulse moved the dimmer between two channel reads; the comparison now leaves that channel out |

### `ProgrammerDetachTest` "detach keeps the layer's effects running as Local effects"

Seen failing on `main`'s CI (run 401, 1933e64), on both attempts of #183's run 402, and 2 of 10
local runs; traced. The test presses a Look carrying a half-beat Pulse on hex-1's dimmer, reads
hex-1's twelve channels, detaches the layer and compares them again. The Pulse runs on master 1's
live clock throughout, so an FX engine tick between the reads moved channel 1 (0 → 255) whatever
Detach did.

The fix compares every channel except the one the Pulse drives, found from hex-1's dimmer property
(`DmxSlider.channelNo`) rather than by index. The effect is asserted on its own, as before: the
same instance runs on as a Local effect. 20 of 20 runs passed after it.
