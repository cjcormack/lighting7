# Manual hardware validation

Operational checks pending an operator session on the rig (most on the X-Touch Compact). No
engineering scope — each is 10–20 minutes end-to-end. Engineering follow-ups live in
[`followups.md`](followups.md); if a check here fails, promote the finding to a `FU-` item there
rather than fixing inline. Checks that have passed move to [Validated](#validated) at the bottom
as a one-line row.

## Outstanding

| Item | What it proves | Origin |
|---|---|---|
| [`FU-MANUAL-DESK-S1`](#fu-manual-desk-s1) | the Programmer view, the show-bar ladder and drag-select survive a real desk | Desk simplification S1, 2026-08-23 |
| [`FU-MANUAL-FX-TEMPLATE-COLOUR`](#fu-manual-fx-template-colour) | a running effect follows the template its parameter names | Palette removal, 2026-08-24 |
| [`FU-MANUAL-EDITOR-INPROCESS`](#fu-manual-editor-inprocess) | in-process editor compiles don't stutter live output | KCS retire, 2026-08-18 |
| [`FU-MANUAL-SPEED-MASTERS-RIG`](#fu-manual-speed-masters-rig) | two masters drive one show — **restart required first** | Programmer S5, 2026-08-14 |
| [`FU-MANUAL-UPDATE-APPLY`](#fu-manual-update-apply) | the in-app update upgrades in place — now unblocked | Windows updates, 2026-08-17 |
| [`FU-MANUAL-SCALER-PROJECT-SWITCH`](#fu-manual-scaler-project-switch) | scaler state survives a project switch | Control-surface P9 |
| [`FU-MANUAL-SUSPEND-PATH`](#fu-manual-suspend-path) | the suspend path doesn't stutter under load | Control-surface P8 |
| [`FU-MANUAL-SURFACES-FLOW`](#fu-manual-surfaces-flow) | the `/surfaces` MIDI-learn flow works | Control-surface P5 |
| [`FU-MANUAL-DEAD-ASSIGNMENTS`](#fu-manual-dead-assignments) | dead markers render after a fixture rename | Cue-authoring P6 |
| [`FU-MANUAL-RUN-STATE-TWO-SESSIONS`](#fu-manual-run-state-two-sessions) | desk, tablet and MIDI surface agree on what GO fires | Server-owned Next, 2026-08-20 |
| [`FU-MANUAL-CROSSFADE-C3`](#fu-manual-crossfade-c3) | crossfades stay smooth and provenance names the right cue after the C3 republish rework | Sweep C3, 2026-08-26 |

---

## `FU-MANUAL-DESK-S1`

**The Programmer as a place, at real widths** · from desk simplification Session 1, 2026-08-23

Session 1 is almost entirely in the category automated tests cannot judge. jsdom applies **no CSS**,
so every container query is invisible to it — and a *missing* `@container` ancestor is equally
invisible, which means the six switcher hosts converted here have no safety net at all.
`getBoundingClientRect` returns zeros, so the marquee's geometry is only covered where it was kept
pure. And one browser cannot show a stale second tab. Everything below is chosen for that gap.

**Widths.** Drag the window through 1240 → 1000 → 880 → 700 → 640 → 440 → 380, with the sidebar
expanded, collapsed, and with a side panel open. Nothing may be *deleted* — only re-arranged — and
GO must get **wider** as the bar narrows. Then the two ends of the master ladder: a one-master show
should be byte-for-byte the width it is today, and a four-master show must not crush the live-state
block (this one already failed once by eye, which is why the tile collapses on count as well as
width). Below 440 the meters chip's popover must reach every master with its own TAP.

**The programmer.** Busk, read the source strip, drag-select a block of colour cells, set them in
one gesture, Record — without meeting a tab or a tooltip. Confirm the floating scope chip **tracks
the cursor** (it is portalled out of the workspace precisely because a container makes itself the
containing block for `fixed`, so a regression here puts it ~150px adrift). Drag past one viewport of
rows and confirm the list auto-scrolls and keeps extending while the pointer is held still. Confirm
the popover's "Applying to N" agrees with what the rig does, and that a marquee crossing Colour and
Position writes **only** the colour. Finally, give the layer stack enough layers to overflow and
confirm the rail scrolls rather than running off the page.

**The source strip's honesty.** Include a cue, edit, then **reload mid-edit**. The strip must show
the cue's identity with **no change badge** and Update still enabled — never "in sync". That claim
is the one thing in this band that costs a cue if it is wrong.

**Master 1.** It is click-to-edit for the first time. Type, Escape, type, Enter — then open
`/speed-masters` and confirm the **stored** default did not move. Only the live tempo may change.

**Run, which must not regress.** Hard-reload Run on a wide window and watch for a flash of the
mobile layout. With eight stacks, scroll the tab strip, then change stack from a second tab and
confirm the strip scrolls to it. On a `QS1-3.2.10` stack, check a collapsed cue's note lines up
under the name at all three header arms. And confirm the card's new `contain: layout` has not
trapped a floating layer — every tooltip and popover inside a cue card should still escape it.

**If anything here fails**, promote it to a `FU-` item in [`followups.md`](followups.md) rather than
fixing inline — Session 2 is already the largest of the three.

## `FU-MANUAL-RUN-STATE-TWO-SESSIONS`

**Desk, tablet and MIDI surface agree on the show** · from server-owned Next, 2026-08-20

Standby and the cue-fade animation moved out of the browser and into `CueStackManager`, broadcast
as `cueRunStateChanged`. Integration tests cover the frame reaching a second socket and the
runner slice adopting it, but three things only a real session can show:

* **Timing.** The client animates from `fadeElapsedMs`, so a following session's fade should look
  like the desk's, not lag or jump. A phone on wifi is the interesting case.
* **The MIDI surface.** GO on the X-Touch goes through `advanceStack`, which now fires the armed
  standby. Arm cue 7 in the prompt book, press GO on the surface, and cue 7 should fire — the
  surface previously had no idea a cue was armed.
* **Auto-advance stepping once.** The client stopped calling the server when its countdown
  finishes (the backend's timer already advances and broadcasts). With three sessions open on an
  auto-advancing stack the show must step one cue per cue, not three. Pausing (open a cue-edit
  Live session, or the surface's Pause binding) should stop the countdown bar everywhere, not
  leave it completing into nothing.

**Test**: desk browser + phone + X-Touch on one show. Arm from each surface in turn and check the
NEXT pill on the others; GO from each and watch the fade animate everywhere; open a fourth session
mid-fade and confirm it joins part-way rather than replaying; then run an auto-advancing stack for
several cues with all sessions watching.

**If a session lags or double-steps**, the levers are in `useShowTransport` /
`applyServerRunState` — not in the backend, which sends one frame per transition by design.

## `FU-MANUAL-EDITOR-INPROCESS`

**Script editor compiling while the rig is live** · from `FU-DIST-KCS-RETIRE`, 2026-08-18

The one property the retired compiler server actually provided beyond `/highlight` was **process
isolation**. Editor highlighting (every pause in typing) and completion (most keystrokes) used to
run in a separate JVM on port 8321; they now run in the JVM that drives DMX.

`ScriptEditorService` bounds this — one below-normal-priority daemon thread, superseded requests
dropped rather than queued, a 10 s response cap — but that caps *concurrency*, not cost. A compile
is 0.2–0.6 s of real work now competing for heap and CPU with the output loop, and no unit test can
speak to that.

**Test**: with a cue stack running and effects live on real fixtures, open the script editor and
type continuously for a minute in a GENERAL script — enough for many highlight and completion
round-trips. Watch for output stutter, dropped frames, or audible hesitation in moving-head motion.
Repeat with an FX_CALC script, whose template differs.

**If it stutters**, levers in order of bluntness: raise the client-side debounce, drop autocomplete
on lower-powered desks, or gate the editor routes on the rig being idle. A second process is the
last resort — it's what this change removed.

## `FU-MANUAL-FX-TEMPLATE-COLOUR`

**A template retune moves a running effect** · from the positional-palette removal, 2026-08-24

The positional colour list is gone and an effect's colour parameter names a **colour template**
instead (`tmpl:{uuid}`). `TemplateColourSourceTest` covers the grammar, the fixture-free resolution
and the cache invalidation, but the payoff is a rig behaviour and the path is long: registry
invalidation → `TemplateRegistry.versionFor(refs)` → `TypedParams`'s colour caches → the next
tick's `calculate`. A stale cache looks exactly like "nothing happened".

**The check.** Create a generic colour template on `/templates`. Add a `ColourCycle` ad-hoc effect
whose `colours` mixes it with a literal, and confirm the chase runs those colours. Then **retune the
template** and watch the running chase follow within a tick without re-firing anything.

Three things worth checking in the same sitting, each of which a single-effect retune cannot show:

1. **A programmer layer's effect follows too.** That path deliberately froze its colour source at
   include time (version pinned to `0L`) when the source was a palette; it is live now, and that is
   a behaviour change rather than a port.
2. **A mixed group degrades as documented, and this is the one to look hardest at.**
   `resolveColourGeneric` resolves as though the head were RGBW, so an RGBW/RGBWA head should match
   the same template applied as a **layer**. An RGB-only head does *not* get the plain hex: the
   neutral has already been taken out of RGB and its white byte is dropped, so `#FF9D4A` arrives as
   `#B55300` — dimmer and more saturated. Confirm that is tolerable on the real rig. If it is not,
   the fix is one line (resolve `RGB_ONLY` in `resolveColourGeneric`) and it inverts which class of
   head is exact — worth deciding with lamps in front of you rather than from the arithmetic.
3. **The delete guard sees the reference.** Deleting a template an effect parameter names should 409
   with an `fxReferenceCount`, not succeed and leave the chase running white. This is the one arm
   with no unit coverage of its JSON scan.
4. **A template *list* change still reaches the chase** (sweep item C4, 2026-08-26). The colour
   version is now per template uuid, so editing an *unrelated* template must leave the chase
   running (that is the point), while creating, renaming or deleting one — which drops the whole
   cache — must still move it. The sharpest case: point a `colours` entry at a uuid that has no
   template, confirm it runs white, then create a template *with that uuid* (import or clone) and
   confirm the chase picks it up. Watch for a stutter in the same sitting: `invalidateAll` now
   re-reads every requested template on the route thread *before* publishing the bump, so a list
   change on a template-heavy show costs N transactions at once against a size-1 pool
   (`FU-TMPL-REWARM-BOUND` holds the bound if it bites).

**If it fails at step 1**, suspect the pre-warm: `prewarmTemplateColours` runs on the request thread
at every spawn site, and a missed one means the first resolve happens on the 50 Hz loop.

## `FU-MANUAL-PALETTE-TOURING`

> **Core case CLOSED on the rig, 2026-08-22** (looks-and-layers session 4). Against the live test
> desk on 8413: a bound Look on `freedom-par-hex`, a cue layering it, GO, then a `PUT` changing the
> Look's colour — the fixture's DMX output went `255,0,0` → `0,0,255` with no re-fire. The feature's
> whole payoff, seen for the first time outside a unit test. **Three extensions below are still
> unseen** and are what keeps this item open; if none of them matters to you, close it.

**A Look edit moves a live cue** · Programmer redesign Session 4, 2026-08-14 · re-scoped for Looks
2026-08-21

Session 4 was verified on a live rig for record, include, apply, cue-side badges and health, and
Make Hard at both levels. The behaviour that was **not** verified on stage was the point of the
feature: edit a Look while a cue depending on it is live and watch the output move without
re-firing. That is now done for a colour Look on a single head.

Republish-on-Look-edit is unit-covered (`LookRepublishTest`) but the path is long — `LookRegistry` invalidation →
version-counter re-check → `replaceCueAssignments` (which preserves `cueFadeWeights`, unlike
`setCueAssignments`) → the controller write. A stale cache or dropped republish looks exactly like
"nothing happened", indistinguishable from operator error unless someone is watching lamps.

**What remains untested**, all three of which a single-head colour edit cannot show:

1. **Two heads, so per-fixture resolution matters** — one Look resolving to a different value per
   head. Trivial for colour, load-bearing for position.
2. **A position Look on a moving head**, where the per-head difference is the whole point.
3. **Mid-crossfade**: edit a Look layered by the *incoming* cue while the fade runs and confirm it
   continues rather than snapping. That is the `cueFadeWeights` preservation, and the reason the
   republish goes through `replaceCueAssignments` rather than `setCueAssignments`. This is the one
   worth actually doing — it is the longest path and the least likely to be right by accident.

The "both dependency paths" note is void: the `ref:` value grammar retired in session 4, so the
indexed FK query is the only path a cue can depend on a Look through.

## `FU-MANUAL-LAYER-PRECEDENCE`

> **Core case CLOSED on the rig, 2026-08-22** (looks-and-layers session 4). One cue, two layers on
> `freedom-par-hex` — Bright (dimmer 255) then Dim (dimmer 60): the fixture sat at **60**, not 255,
> so within-cue composition really is ordered override for intensity and not HTP max. Flipping the
> two layers' `sortOrder` and re-applying flipped the winner to 255. **The cross-cue, amount and
> blend-mode extensions below are still unseen.**

**Layered intensity is later-wins, not HTP** · Looks-and-layers Session 1, 2026-08-21

The named behaviour change of the cook step, and the one an operator could reasonably be surprised
by: **within one cue**, stacking a dim layer over a bright one really does dim, because within-cue
composition is strict ordered override for every attribute. Across cues, HTP still applies. Unit
tests pin both, but "does this feel right at the desk?" is not something a test answers.

**Done**: one cue, two layers on the same fixture — layer 1 dimmer 255, layer 2 dimmer 60. GO: the
fixture sat at 60, and reordering flipped it to 255.

**Still to do**, and the cross-cue half is the one that matters, because it is the *pairing* an
operator gets surprised by rather than either rule alone: a second cue also asserting dimmer 255 on
that fixture — with both cues live the fixture must go to 255, because cross-cue is still HTP. Then
set layer 2's amount to 0.5 and confirm 120 (mixing halfway from layer 1's 255... 127), and its
blend mode to MAX and confirm 255.

Also worth confirming on the rig: a layer whose Look runs a colour *effect* beats a later layer
setting colour statically, because effects sit above static values regardless of layer order. That
is the one place layer order does not govern, and the only cure is the layer's `stomp`.

**And now the cure itself**, since `FU-LOOK-STOMP-WITHIN-CUE` landed: set `stomp` on the later
(static) layer and confirm the effect below goes quiet while the static colour holds. Then clear it
and confirm the effect comes back **without restarting its phase** — that is the whole reason it
suppresses rather than removes, and a respawn would be visible as a hitch on a slow chase. Worth
doing on the rig rather than trusting `FxEnginePipelineTest`: the test asserts the instance survives
and paints again, not that the eye sees no step. Then repeat it on the **programmer** stack, where
the effect being suppressed sits in the priority band that is otherwise exempt from suppression.

## `FU-MANUAL-SPEED-MASTERS-RIG`

**Two masters driving one show** · **backend restart required first (new classes)** · Programmer
redesign Session 5, 2026-08-14

Session 5 shipped with a restart outstanding, so **no part of the speed-master bank has run against
the rig**. `SpeedMasterBankTest` pins the tick-interval arithmetic (the old `toLong()` truncation
ran 120 BPM at ~125, which with two masters is *relative* drift at 120:60 ⇒ 2.05:1) and
`SocketMessageWireFormatTest` pins the `speedMasters.*` wire format, but the single-engine-pass
composition — one `ControllerTransaction` per frame however many masters ticked — has only ever
been exercised by tests.

**Coordinate the restart with the user first; it may be driving a live rig.**

**Test**: restart → confirm the default bank seeds and the ShowBar masters strip reads and taps
master 1. Then put a position wave on master 2 at half master 1's BPM and a dimmer chase on
master 1, both on the same fixtures → confirm a visibly 2:1 ratio held over several minutes
(drift is what the deadline timer fixes and only shows up over time). Tap master 2 → only its
effect changes rate. Check the surviving master-1 entry points still land on master 1: script
`setBpm`/`tapTempo`, the AI `set_bpm` tool, and `PUT /project/{id}/speed-masters/{mid}` with a
`bpm` (which must retune the *live* clock, not just the stored default).

While there, check the per-master beat dots — every `BeatIndicator` pulses from the keyed
`speedMasters.beat` stream now, so the dot beside a master-2 effect should track master 2. Watch
the **master 1** dots in particular (the strip's M1 tile *and* the FX panel's): sweep item D2
moved them off `beatSync` onto the keyed stream, which needed master 1's real uuid resolved
client-side — a dot that stays an empty ring forever is that resolution failing. The local
interpolation is also load-bearing there for the first time: `beatSync` used to (accidentally)
arrive every beat, and the keyed stream genuinely throttles to every 16.

## `FU-MANUAL-UPDATE-APPLY`

**In-app update, end to end on Windows** · Windows in-app updates, 2026-08-17 ·
**Unblocked** — `FU-MANUAL-DIST-INSTALL` passed 2026-08-19, so there is now a confirmed-installing
build to upgrade from. This is the next one to run.

Everything from `POST /update/apply` onward is unautomatable: the marker protocol is round-tripped
in one JVM by `UpdateMarkerRoundTripTest` and the PowerShell command line is pinned by
`WindowsUpdateApplyTest`, but no test can observe a real launcher exiting, a UAC prompt, or
`msiexec` replacing files in `C:\Program Files`.

**Upgrade mechanics** — this is what proves the UpgradeCode fix worked:

1. Install `v1.1.0` on a clean VM. Note the install directory and **whether UAC prompted** — that
   answers per-machine vs per-user, a one-way door that must be settled before the first release.
2. Add/Remove Programs shows **one** `lighting7`.
3. Install `v1.1.1` by double-click → still **one** entry, version bumped, one Start-menu shortcut,
   `%APPDATA%\lighting7\` untouched (DB, `local.conf`, logs survive).
4. Repeat with `msiexec /i <msi> /qb /norestart /l*v %TEMP%\l7.log` — the exact command the apply
   flow issues. Confirm the behaviour **while the app is running**, and grep the log for
   `RemoveExistingProducts`.
5. Install `v1.1.0` over `v1.1.1` → expect a downgrade refusal, not a silent side-by-side.
6. Confirm a user-chosen install directory (`--win-dir-chooser`) is honoured across the upgrade.

**The in-app path**, in order: dev-build state → check with no release published (expect the
404/"nothing yet" state, not an error) → check with a release → download and verify →
**deliberately corrupt the staged MSI and confirm the launcher refuses it and keeps running** →
real apply → confirm the relaunch and the new reported version.

**Failure paths**: cancel the UAC prompt (expect exit 1602 recorded, desk back on the old version);
kill the wrapper mid-install; unplug the network mid-download (expect the `.part` gone, nothing
staged); fill the disk.

See [`docs/windows-updates.md`](../windows-updates.md).

## `FU-MANUAL-SCALER-PROJECT-SWITCH`

**Scaler state across project switches** · Control-surface Phase 9

Connect device → toggle **Blackout** on project A (confirm LED + stage) → switch to project B via
`/projects` → Blackout off on B (fresh holder) → switch back to A → Blackout still on, stage still
dark. Same for **Grand Master**. Verify a WS client open across the switch sees the correct
`surfaceScaler.state` payload at each switch and after toggling within the new project. A backend
restart no longer resets them — `FU-BE-SCALER-PERSISTENCE` landed in `7bcd109`, so confirm they
survive one.

## `FU-MANUAL-SUSPEND-PATH`

**Suspend-path sanity check** · Control-surface Phase 8

Run a script adding and removing 100 effects/sec while a MIDI fader runs at full 60 Hz on the same
property. Confirm no stage stutter, no WebSocket `channelState` lag, no coroutine leak on a thread
dump. No functional change expected — the suspend path delivers the same per-channel acks as the old
blocking path. Regression sanity check, not new validation.

## `FU-MANUAL-SURFACES-FLOW`

**End-to-end `/surfaces` flow** · Control-surface Phase 5

Connect the device → `/surfaces` shows it attached. Click **+** on a fader row, open MIDI Learn,
wiggle the physical fader → the binding appears. Switch banks via `BankSwitcher` → matrix rows
update. Validates the Phase 5 UI and Phase 3/4 wiring against real hardware edges (debounce,
device-side bank events, motor drive under load).

## `FU-MANUAL-DEAD-ASSIGNMENTS`

**Dead-assignment banner on a live rig** · Cue-authoring Phase 6, 2026-04-22

Backend logic for `DeadAssignmentsBanner` / `DeadPresetAssignmentsBanner` is stateless and
unit-covered; WS fan-out plus React rendering of dead markers after a fixture rename was never
validated end-to-end.

**Test**: rename a fixture in a patch, reload the cue editor, confirm dead markers appear on the
affected rows and Remove clears them. 10 minutes.

## `FU-MANUAL-CROSSFADE-C3`

**Crossfade smoothness and provenance after the republish rework** · Sweep item C3, 2026-08-26

C3 rebuilt what a crossfade frame recomputes: weight ticks now reuse the previous winner maps and
a cached effect-coverage set, and the composed state is published index-first. Unit tests cover
the composition values and the cache-invalidation edges; what they can't judge is the *feel* of a
long fade on real fixtures, or attribution in the live UI.

**Test**: run a 10 s+ crossfade between two full cues (dimmer + colour rows on every fixture) with
a handful of effects running. Confirm the fade reads as smooth (no stepping, no flicker on
effect-covered keys), pause/resume an effect mid-fade and confirm its keys hand over cleanly, and
at fade end check the provenance/Update views name the incoming cue — not the outgoing one — as
the owner of shared keys. 10 minutes.

---

## Validated

Passed on the rig, or retired unrun because the feature went away; the procedures are in this
file's git history if one is ever needed again.

| Item | Passed | Result |
|---|---|---|
| `FU-MANUAL-DIST-INSTALL` | 2026-08-19 | clean install on Mac + Windows; all four native payloads and editor completion good |
| `FU-MANUAL-AUTH-QR-SCAN` | 2026-08-19 | both QR flows resolved and completed from a real phone |
| `FU-MANUAL-CUEEDIT-HARDWARE` | — | retired unrun 2026-08-24: sweep item D1 removed `cueEdit.*`, so there is no cue to open for edit from a fader |
