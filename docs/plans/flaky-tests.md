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

| Test | Seen failing | Rate | Suspected cause |
|---|---|---|---|
| `CueSpeedMastersTest` "GO, GO TO, GO BACK and auto-advance each set the cue's masters, and a tap wins until the next cue" | local, the routes package run on R1.5's merge of `main` (S16 at 8236eb9): `the follow fired Q2 expected:<2> but was:<1>` | 1 of 4 runs (3 passes running the class alone) | Suspected, not traced: the test waits for master 2 to read 140 BPM, then asserts the active cue. If the auto-advance's GO sets Q2's masters before the stack's active cue moves to Q2, the wait ends early. Waiting on the active cue as well would close it |

## Fixed

| Test | Fixed in | What it was |
|---|---|---|
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
