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
| [`FU-MANUAL-DESK-S2A`](#fu-manual-desk-s2a) | the programmer stack composes on a rig — live retune, a layer dragged under a running effect, second-tab agreement | Desk simplification S2a, 2026-08-23 |
| [`FU-MANUAL-DESK-S2B`](#fu-manual-desk-s2b) | the Run/Show merge's edit lock protects a running show, and off-playhead browsing is safe | Desk simplification S2b, 2026-08-23 |
| [`FU-MANUAL-DESK-S3`](#fu-manual-desk-s3) | one template resolves per head across three colour types, two tilt ranges and a white/amber policy | Desk simplification S3, 2026-08-23 |
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
| [`FU-MANUAL-WALLCLOCK-RATE`](#fu-manual-wallclock-rate) | an unassigned wall-clock effect no longer follows master 1's tempo | Sweep C6, 2026-08-26 |
| [`FU-MANUAL-RECONNECT-RESYNC`](#fu-manual-reconnect-resync) | a widened reconnect resync heals a slept tab without a refetch storm | Frontend sweep, 2026-08-29 |
| [`FU-MANUAL-WS-SINGLE-PARSE`](#fu-manual-ws-single-parse) | the single-parse WS seam loses no frame on any bridge | Frontend sweep, 2026-08-29 |
| [`FU-MANUAL-SAVELOOK-INVALIDATION`](#fu-manual-savelook-invalidation) | a layer-scope drag stays smooth, and Look compatibility still refreshes | Frontend sweep, 2026-08-30 |
| [`FU-MANUAL-FADE-DISPATCH`](#fu-manual-fade-dispatch) | fades still draw, join mid-fade, and complete once with the 60 Hz dispatch gone | Frontend sweep, 2026-08-30 |
| [`FU-MANUAL-FADE-SHOWBAR`](#fu-manual-fade-showbar) | the ShowBar's FADING countdown reads right at 10 Hz and the chrome sits out fades | Frontend sweep, 2026-08-30 |
| [`FU-MANUAL-PROGRAMMER-MEMO`](#fu-manual-programmer-memo) | non-fade `/programmer` traffic no longer re-renders the whole grid/rail subtree | Frontend sweep, 2026-08-30 |
| [`FU-MANUAL-PROVENANCE-REFETCH`](#fu-manual-provenance-refetch) | a crossfade no longer drives a refetch storm, and a MIDI write mid-fade still lands in the grid | Frontend sweep, 2026-08-30 |
| [`FU-MANUAL-MOBILE-SHEET-FADE`](#fu-manual-mobile-sheet-fade) | the phone cue-list sheet and the desktop cue-stack view stay smooth on a big stack | Frontend sweep, 2026-08-30 |
| [`FU-MANUAL-CURSOR-OWNERSHIP`](#fu-manual-cursor-ownership) | GO/BACK/standby and the fade survive the transport's single reconcile effect | Frontend sweep, 2026-08-30 |
| [`FU-MANUAL-CODE-SPLITTING`](#fu-manual-code-splitting) | the four lazy chunks arrive on a real desk, including one with no internet | Frontend sweep, 2026-08-30 |
| [`FU-MANUAL-COLLAPSED-PANELS`](#fu-manual-collapsed-panels) | collapsed overview panels stop working, and reopening one is instant rather than empty | Frontend sweep, 2026-08-30 |
| [`FU-MANUAL-CUE-REPUBLISH-FRAME`](#fu-manual-cue-republish-frame) | a cue expanded on one client refreshes when another retunes a Look it layers | Frontend sweep, 2026-08-30 |
| [`FU-MANUAL-WS-SEND-DROPPED`](#fu-manual-ws-send-dropped) | a WebSocket blip announces itself instead of eating every gesture silently | Frontend sweep, 2026-08-30 |
| [`FU-MANUAL-CHANNEL-FANOUT`](#fu-manual-channel-fanout) | coalesced channel wake-ups still track the rig, on the sheet, the cards and the stage | Frontend sweep, 2026-08-30 |
| [`FU-MANUAL-MARQUEE-COUNT`](#fu-manual-marquee-count) | a large marquee drag on `/fixtures` stays smooth and still reports the right batch count | Frontend sweep, 2026-08-30 |
| [`FU-MANUAL-CHANNEL-THROTTLE`](#fu-manual-channel-throttle) | the channel stream still settles on its final value once the idle interval stops ticking | Frontend sweep, 2026-08-31 |
| [`FU-MANUAL-PALETTE-COLD-OPEN`](#fu-manual-palette-cold-open) | the first ⌘K of a session opens fully populated, not empty | Frontend sweep, 2026-08-31 |
| [`FU-MANUAL-RENDER-IDENTITY`](#fu-manual-render-identity) | the newly-stabilised render inputs still track the desk — GO, a mid-fade reorder, the phone runner, the pads' layer ring | Frontend sweep, 2026-08-31 |
| [`FU-MANUAL-FX-BADGE-COUNTS`](#fu-manual-fx-badge-counts) | the "N FX" badges still count what the per-target endpoints counted, indirect group effects included | Frontend sweep, 2026-08-31 |
| [`FU-MANUAL-STAGE-BUFFER-UPLOADS`](#fu-manual-stage-buffer-uploads) | the 3D stage still draws every beam, pool and pixel wash now that only written buffers upload | Frontend sweep, 2026-08-31 |
| [`FU-MANUAL-SIGNATURE-CACHE`](#fu-manual-signature-cache) | programmer cells still repaint when a value moves, now that the diff trusts a cached signature | Frontend sweep, 2026-08-31 |
| [`FU-MANUAL-CHANNEL-VALUE-HOOK`](#fu-manual-channel-value-hook) | raw channel sliders still track the rig and still write, with the per-channel RTK cache gone | Frontend sweep, 2026-08-31 |
| [`FU-MANUAL-BEAT-PRUNE`](#fu-manual-beat-prune) | beat indicators and the stage's derived sources survive the shared keyed-subscription pool | Frontend sweep, 2026-08-31 |
| [`FU-MANUAL-PROMPTBOOK-RELOCK`](#fu-manual-promptbook-relock) | the prompt book's idle re-lock still catches every edit, and the rail still opens the right cards | Frontend sweep, 2026-08-31 |
| [`FU-MANUAL-BUSK-VIEW`](#fu-manual-busk-view) | the Busk view is a place an operator can run a show from, and a follower tracks its leader | Busking view, 2026-09-01 |
| [`FU-MANUAL-FX-TEMPLATE-PADS`](#fu-manual-fx-template-pads) | a template that holds an effect is authored, busked and tracked exactly as a value template is | FX templates, 2026-09-02 |

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

## `FU-MANUAL-DESK-S2A`

**The programmer stack, composing on a rig** · from desk simplification Session 2a, 2026-08-23

Session 2a shipped on browser verification alone, and had two defects that a green suite called
working. The looks-and-layers arc before it found two more on a desk that no test had — a
provenance branch that never named the winning layer, and a layer frame that reached only the
acting tab. Both are in exactly this area, so assume the pattern holds.

**The composing pass.** Build three Looks and an effect inside one session. Retune a Look mid-scene
and watch the stage. Drag a layer while its effect is running and confirm the phase survives the
reorder. Record values *and* effects into one Look, then confirm a second tab sees the stack change
— and that the scope falls back when the focused layer goes away.

**The two only a rig can answer.**

1. **Is the layer-scope save cadence tolerable?** It is roughly two writes a second, so the rig
   **steps** rather than glides under a drag. If it is not tolerable, the answer is a bound-row
   preview channel, not a faster `PUT` — `LookPreviewRequest` is deferred-only, so it cannot carry
   this.
2. **Does landing in Local rather than on the cook read right at the desk?** Only an operator at
   the desk can judge where a busked value should appear.

Anything that fails becomes a `FU-` item in [`followups.md`](followups.md) rather than an inline
fix.

## `FU-MANUAL-DESK-S2B`

**The Run/Show merge and its edit lock** · from desk simplification Session 2b, 2026-08-23

2b is the half that can hurt a running show: it folded Run into Show behind an edit lock, which
moved show-critical playback into an authoring change. What made that acceptable is that the lock
is a pre-existing, proven mechanism rather than something invented here — this check is where that
claim gets tested on a rig.

**With the show running.** Try to drag a cue and fail. Press `L`, edit, press GO, and confirm it
re-locked itself. Leave it unlocked and idle, and watch the countdown re-lock it. Confirm Blind
appears beside DBO **only** while unlocked, and that it actually gates the rig.

**With the show stopped.** Confirm it is simply editable, with no lock chrome anywhere.

**On a phone.** Confirm it is locked and cannot be unlocked.

**The two-cursor rule.** Switch stacks from the tab strip while a cue fades. The fade must animate
*and* the marker must not jitter. This is the one that will be got wrong.

**The browse/arm split.** Browse to a sibling stack and confirm **GO still fires the live one** —
that guarantee is what makes off-playhead browsing safe at all. Then press *Make this stack live*
with a cue on stage: it must ask first, and the blip it warns about (`go-to` fires the target's
first cue before the desk darkens it) is worth watching for, since only a rig can show it.

The `cueEdit` check this list originally carried is retired: there is no client-side session to
open. That is a structural guarantee now rather than a behavioural one — the arm and its API module
are deleted.

## `FU-MANUAL-DESK-S3`

**Templates resolving per head** · from desk simplification Session 3, 2026-08-23

Session 3 removed the fixture type from a template and made resolution per head. That is the
conceptually interesting part of the desk-simplification arc and also where its cost is: the
resolution has to be right in the engine *and* visible in the editor, or it is worse than the
constraint it replaced. Almost all of that is only answerable on a rig, which is why this session
has more checks than the others.

1. **One colour template across three colour types at once** — an RGBWA hex, a white-only head and
   the MAC 250's colour *wheel* — and each should read as the same colour. The wheel is the one to
   judge by eye: the editor's ΔE says how close the desk believes it got, and only the rig says
   whether that belief is right. A bad annotation lands as
   [`FU-TMPL-WHEEL-PREVIEWS`](followups.md#fu-tmpl-wheel-previews).
2. **Retune it and watch every tracking layer move**, in a cue and in the programmer's own stack.
   That is `republishForTemplateEdit`, and it is the touring feature.
3. **The two gestures are visibly different.** Click, then retune — the busked values must *not*
   move. ⌥click, then retune — they must.
4. **A position template in degrees across two ranges.** The Shehds tilts 0–270°, the MAC 250
   0–257°: the same degrees must land on different DMX, and a clamp must be reported in the panel
   *and* true on stage.
5. **The white/amber policy on a head that has both.** Extract should look brighter and cleaner
   than RGB-only at the same hue, which is the whole claim.
6. **A beam template says out loud that it cannot carry a gobo** — the disabled rows with their
   reasons, rather than the property simply being absent.
7. **New from selection**, first on heads that agree, then on heads that differ: the first must come
   out *Generic*, the second *Per fixture*, and the toast says which.

New routes, classes or fields need a **restart** — the backend hot-swaps changed handler bodies but
not new surface area, and the desk may be driving a live rig. Ask the operator first.

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

Also watch the **client** side now, which `FS-EDITOR-DEBOUNCE-DIRTY` changed (lighting-react
`a402981`): the editor reports every keystroke to React instead of one value per 500 ms of idle,
so the surrounding sheet re-renders per character where it used to coalesce. The compile and
completion traffic this check was written for is untouched — that still runs through the widget's
own debounce — so what to look for is local typing latency in the editor, not new server load.

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

## `FU-MANUAL-WALLCLOCK-RATE`

**Wall-clock effects with no rate master run unscaled** · Sweep item C6, 2026-08-26

`slotFor(null)` is slot 0 — master 1 — so a wall-clock effect with no rate master was being
scaled by master 1's `bpm / 120`. C6 gave `rateMasterSlot` a `NO_RATE_MASTER` sentinel, so it is
now genuinely unscaled. Both shipped wall-clock effects (`CandleFlicker`, `FluorescentFlicker`)
are STATEFUL and never read a phase, so nothing in-tree exercised the old behaviour — which is
also why this can only be judged on the rig, with an effect written for it.

**Test**: author a STANDARD + WALL_CLOCK `.fx.kts` definition (a slow colour fade over, say, 8 s)
and run it with no rate master assigned. Move master 1 from 120 to 200 BPM and back. The effect's
speed must not change. Then assign master 1 as its *rate* master and repeat: now it must speed up
and slow down with the fader. 10 minutes.

## `FU-MANUAL-RECONNECT-RESYNC`

**The reconnect resync heals a slept tab, and costs nothing visible** · frontend sweep
`FS-BUG-RECONNECT-RESYNC`, 2026-08-29

The client used to invalidate a hand-written 15-tag list on CLOSED→OPEN, under a comment claiming
"all REST caches"; twenty tags had no reconnect path at all. It now invalidates every tag but
`Auth`, derived from `REST_TAG_TYPES`, spread over waves of eight after a 250 ms debounce so the
burst doesn't land in one tick on a single-connection SQLite pool that is still warming up. The
timing is unit-tested against fake timers, but the numbers themselves are guesses: nothing in-tree
can say whether 250 ms + 150 ms per wave is imperceptible on a real reconnect, or whether the
serialisation it exists to avoid was ever going to bite.

**Test**: with a show running and the Programmer open on a busy rig, start the show from a
*second* browser, then sleep the laptop (or stop and restart lighting7) for a minute and wake it.
On reconnect the transport must agree with the other tab — GO live, no phantom grey-out — and the
fixtures table, all three stage views, the MIDI bindings page and the Updates tab must show current
data without a manual reload. Watch the network panel across the reconnect: requests should arrive
in small groups roughly 150 ms apart rather than all at once, the whole resync should be done
inside about a second, and there should be no visible stall in DMX output. Then pull the network
cable for two seconds and plug it back in — the flap must produce exactly one resync, not one per
transition. Repeat once signed in as an **operator** rather than an admin, which is the case where the
role-gated families must produce no requests at all. 15 minutes.

## `FU-MANUAL-WS-SINGLE-PARSE`

**No bridge lost a frame when the WebSocket parse moved into the connection** · frontend sweep
`FS-PERF-WS-SINGLE-PARSE` + `FS-WS-ERROR-ISOLATION`, 2026-08-29

Every inbound frame used to be `JSON.parse`d once per bridge — 24 of the ~27 parsed it
unconditionally and threw it away, against a `channelState` firehose running at up to ~40 frames/s
per universe on the thread that also paints the grid. The connection now parses each frame once and
hands every bridge the same object, and `notifyEvent` wraps each subscriber in its own try/catch so
one that throws can no longer starve the ones registered after it. Two of the bridges
(programmer, speed masters) lost hand-rolled substring pre-filters in the process, and the six
`handleOnMessage` bridges now take a parsed body rather than the raw event. This is a **code-read**
change: the parse count is arithmetic, not a measurement, and nothing in-tree exercises all
twenty-eight bridges against a real server.

**Test**: with the desk running and DMX moving (an effect on a group is enough), open the client and
work each WS-fed surface in turn, confirming each still updates live from a *second* browser rather
than only from its own actions — the fixtures grid and channel values, the universes list, the
Programmer (set a value, go Blind, watch provenance), the cue list and the show bar's live/next
cursor, prompt books, the patch and rigging pages, the stage regions and park views, the MIDI
`/surfaces` page (bank change and a learn), Cloud Sync (run a sync and watch the log stream), the
Updates tab, and the speed-master BPM readout including its beat indicator. Then restart lighting7
under the client and confirm every one of those recovers on the reconnect. Finally, watch the
browser console throughout: a `WebSocket: a message subscriber threw` line is the new isolation
firing and means a real bug to file, and no frame should ever be reported as unparseable. 20 minutes.

## `FU-MANUAL-SAVELOOK-INVALIDATION`

**A layer-scope drag no longer refetches the fixture list, and compatibility still refreshes when
it must** · frontend sweep `FS-PERF-SAVELOOK-INVALIDATION`, 2026-08-30

`saveLook` used to invalidate `Fixture` and `GroupList` on every PUT. `LookRowStore` writes a
rows-only body every 400 ms for the length of a layer-scope drag, so each tick refetched the
fixture list — 48 consumers client-side, `loadLookCompatibilityInfos` + `detectCapabilities` per
fixture server-side — and handed every consumer a new array identity mid-drag. Those two tags are
now sent only when the body writes `effects`, which is the only thing `compatibleIdsFor` reads.
This is a **code-read** change: the saving is arithmetic over a request that is no longer sent, not
a measurement, and nothing in-tree drags a real grid against a real server.

**Test**: on a rig with enough patched fixtures to make the list expensive, focus a LOOK layer in
the Programmer and drag a value across several targeted heads for a few seconds. The grid must stay
responsive and the rows must not visibly rebuild under the drag; in the network panel the 400 ms
saves should appear with **no** accompanying `GET /fixtures` or `GET /groups`. Then confirm the
other half still works: with the Look library open beside a second browser, add the first effect of
a new family to a Look (`+ Effect` with a layer focused) and check it becomes offerable — it must
appear in `LookTogglePicker` and stop disabling heads in `LayerPicker` without a manual reload.
Rename a Look and delete one too; both must still refresh the library. 10 minutes.

---

## `FU-MANUAL-FADE-DISPATCH`

**Fades animate identically now that progress never passes through Redux** · frontend sweep
`FS-PERF-FADE-DISPATCH`, 2026-08-30

The fade/auto-advance animation used to dispatch `setFadeProgress`/`setAutoProgress` into the
runner slice once per rAF, re-rendering every `selectStackRunner` subscriber ~60×/s and putting
four deep-scanned slices in front of the dev invariant middleware per frame. The slice now stores
a write-once `(startMs, durationMs, cueId)` descriptor per animation and the drawing components
compute progress locally (`useAnimatedProgress`). This is a **code-read** change: jsdom's rAF is a
16 ms timer and no automated test watches a real fade draw, so smoothness and the timing seams are
asserted, not measured.

**Test**: run a stack with a long fade (5 s+) on `/show`. GO — the row's fade bar and the FADING
countdown must animate smoothly to completion, the done tick must appear exactly once, and an
auto-advance cue must count down and roll on as before. While the fade runs, open the same show on
a second browser/tablet: it must join mid-fade at the right point, not restart from 0. Press BACK
mid-fade (the bar must stop and the cursor return) and re-fire the live cue from another surface
(the fade must restart). Then the point of the change: with the Programmer grid on screen during a
long fade, the grid must feel no busier than the idle state — and in a dev build, a Performance
profile during a fade should show no `runner/setFadeProgress` dispatch storm. 10 minutes.

---

## `FU-MANUAL-FADE-SHOWBAR`

**The show-bar chrome sits out a fade; the FADING countdown still reads right** · frontend sweep
`FS-PERF-FADE-IN-SHOWBAR`, 2026-08-30

The bar used to take the remaining fade time as a per-frame prop, so every tile in it — the speed
masters included — reconciled ~60×/s for the whole of every fade, on every view that mounts the
bar. It now takes the fade's write-once descriptor, is memoized, and runs its own countdown off a
100 ms interval. This is a **code-read** change: no automated test watches a real countdown tick or
can see a reconcile storm, so the 10 Hz readout and the memo actually holding are asserted, not
measured.

**Test**: run a long fade (5 s+) on each of `/show`, `/programmer` and the Prompt Book. The amber
FADING badge must appear at once, count down smoothly in 0.1 s steps to the real fade length, and
vanish when the fade completes — no badge stuck on screen, none flickering. While it counts, the
speed-master tiles must stay live (beat indicator still pulsing, TAP still responsive) and a dev
Performance profile during the fade should show the bar's subtree rendering ~10×/s, not 60. On
`/programmer`, drag a slider mid-fade — the grid must feel no busier than idle. 10 minutes.

---

## `FU-MANUAL-PROGRAMMER-MEMO`

**Non-fade `/programmer` traffic no longer re-renders the whole grid/rail subtree** · frontend sweep
`FS-PERF-PROGRAMMER-MEMO-BARRIER`, 2026-08-30

`ProgrammerBody` (the grid, rail, action bar and scope band below the page's chrome) had no memo
barrier, so any re-render of `ProgrammerPage` — a `useProgrammerSummaryQuery` refresh, a project
refetch, anything upstream of the bar — reconciled the entire subtree even outside a fade. It is now
wrapped in `React.memo` on its one primitive prop (`projectId`), and (found in the
`FS-PERF-PROVENANCE-REFETCH` review) no longer holds a summary subscription of its own — the Revert
handler reads the include target at click time, because a subscription held by the barrier component
is a wake `memo` cannot block. This is a **code-read** change: no automated test watches a reconcile
storm, so the barrier holding is asserted (the grid-never-remounts test still passes), not measured.

**Test**: on `/programmer`, drive summary traffic that doesn't touch the grid — Include/Update/Record
from another surface — while a dev Performance profile is running. `ProgrammerBody` itself and the
grid/rail/scope-band subtree should not re-render on that traffic; the toolbar leaves that draw
summary state (`ProgrammerSourceStrip`, `ProgrammerActionBar`, the sheets host) legitimately do,
since they subscribe for what they render. After a Revert, the previously included cue must still
re-Include (the handler now reads the target at click time). Toggle Groups and switch the
Output/Local scope segment as before; the grid must still re-render (never remount) and behave
identically to before this change. 10 minutes.

---

## `FU-MANUAL-PROVENANCE-REFETCH`

**`programmerRevision` ends the crossfade refetch storm without stranding off-connection writes**
· frontend sweep `FS-PERF-PROVENANCE-REFETCH`, 2026-08-30

A crossfade's weight ticks republish `provenanceState` at ~20 Hz, and every frame made every tab
re-request `programmer.state` (~10 requests/s per tab for the whole fade). Each frame now carries
a monotonic `programmerRevision` that weight ticks don't bump, and the client refetches only when
it moved. This is a **code-read** change; the stranding risk (a skipped refetch that should have
fired) is exactly what only a rig can prove.

**Test**: run a long crossfade (≥10 s) with the browser's network/WS inspector open on the
programmer socket — during the fade there should be at most a couple of `programmer.state`
requests (fade start/end), not a steady ~10/s stream. Then, mid-fade, write a programmer value
from an off-connection source (a MIDI CC twist, or a second tab): the first tab's grid must show
the new value within ~a quarter second, both mid-fade and after the fade completes. Locate and a
template apply mid-fade should behave the same. 10 minutes.

---

## `FU-MANUAL-MOBILE-SHEET-FADE`

**The phone cue-list sheet and the desktop cue-stack view stay smooth on a big stack**
· frontend sweep `FS-PERF-MOBILE-SHEET-FADE`, 2026-08-30

The phone runner's cue-list sheet used to prop-drill `fadeProgress`/`autoProgress` from `ShowPage`
into every rendered `MobileCueRow`, and each row recomputed `completedCueIds.includes(cue.id)` —
O(n) per row, so O(n²) over a 200-cue stack, on every fade frame, on a phone. Rows now read their
own fade/auto-advance via `useCueFade`/`useCueAutoProgress` (gated by `fadeStackId`, only truthy
for the active row), `MobileCueRow` is memoized, and the done-tick is a hoisted `Set`. The review
found the desktop cue-stack view (`StackDetail.tsx`) had the identical `completedCueIds.includes()`
cost, so that got the same `Set` hoist in this landing. This is a **code-read** change: no
automated test watches a reconcile storm or profiles a real device, so the mechanism is asserted,
not measured.

**Test**: on a project with a 100+ cue stack, open the phone runner (`/show` on a phone or a
narrow/touch-emulated viewport) and open the cue-list sheet mid-show with a dev Performance profile
running. Fire a GO with a multi-second fade: only the active row's fade/auto-advance chrome should
animate and re-render per frame, other rows must not flash or reconcile, and the done tick must
appear exactly once per completed cue. Tap a row to select a cue and confirm `onSelectCue` still
fires for the tapped row specifically (not the previously-active one). Then repeat the same big-stack
fade on desktop `/programmer`'s cue-stack panel and confirm the same smoothness. 10 minutes.

---

## `FU-MANUAL-PROMPTBOOK-FADE`

**A live cue's fade in Prompt Book animates without re-rendering every other cue in the show**
· frontend sweep `FS-PERF-PROMPTBOOK-FADE-DRILL`, 2026-08-30

`fadeProgress`/`fadeRemainMs` used to be prop-drilled from the page into every rendered
`PromptBookCueCard` in the whole show, ~60×/s during a fade. The live card now reads its own fade
via `useCueFade`, and the card is memoized against the page's own stable per-row callbacks, so a
fade frame re-renders only the one card that is actually fading. This is a **code-read** change:
no automated test watches a reconcile storm.

**Test**: open a multi-stack Prompt Book with several dozen cues expanded, open a dev Performance
profile, and fire a GO with a multi-second fade. Only the live (green) card's amber fade bar/badge
should animate and re-render per frame; other cards must not flash or reconcile. The fade bar must
still reach 100% and clear at the same moment the card drops out of "fading". Toggle a card's
expand/collapse and edit a cue name/number mid-fade to confirm the memoized card still responds to
its own prop changes. 10 minutes.

---

## `FU-MANUAL-CURSOR-OWNERSHIP`

**The transport's two reconcile effects became one, and the dead cache standby copy is gone**
· frontend sweep `FS-ARCH-CURSOR-OWNERSHIP`, 2026-08-30

`useShowTransport`'s two runner↔server reconcile effects merged into a single effect (same
change-detection semantics, one ref more), ShowPage now reads the server cursor through the
transport rather than hand-computing it, and the never-read `CueStack.standbyCueId` cache copy was
deleted. The pinned behaviours (deferred reorder mid-fade, GO/BACK not restarting a fade, the
two-cursor marker) are unit-tested, but the transport is the most live-desk-critical path in the
tree and the merge is exactly the kind of refactor whose subtle diffs only two real sessions show.

**Test**: two sessions on one show. Session A runs GOs with multi-second fades; session B follows.
Confirm B's marker, NEXT pill and done ticks track A through GO, BACK, re-arm (standby), a cue
reorder mid-fade, and "make live" onto another stack. Then background B's tab for a minute while A
advances several cues, foreground it, and confirm B converges on the right live/next cursors and
done ticks without a stuck fade or a spurious fade replay. On A, confirm its own GO never restarts
or stutters the fade it is drawing, and that a standby armed mid-fade survives the fade ending.
10 minutes.

---

## `FU-MANUAL-CODE-SPLITTING`

**Four route/component chunks now arrive after boot rather than in it**
· frontend sweep `FS-PERF-CODE-SPLITTING`, 2026-08-30

The app was one 4.0 MB entry chunk; it is now a 2.0 MB boot payload plus four lazily-fetched
chunks — Stage (`@react-three/*`), Prompt Book (`react-pdf`/pdfjs), the Kotlin script editor
(`kotlin-playground`), and the Lux chat panel (`react-markdown`). The split is measured from the
build output, not profiled, and the checks that matter are the ones a build can't make: that the
chunks actually load from the packaged installer's static resources, and that the fallbacks read as
"loading" rather than "broken" at desk latency.

**Test**: from a packaged install (not the Vite dev server), hard-reload the desk and confirm the
login screen paints noticeably sooner than before, then visit `/stage`, `/prompt-book`, a script in
`/scripts`, an effect editor in `/fx-library`, a cue's trigger editor inside Show, and Lux. Each
should show a brief spinner and then the real surface — never a blank pane, a console 404 on a
`/assets/*.js` chunk, or a stuck spinner. Repeat the Stage and Prompt Book visits with the desk's
network cable pulled, since these are the first assets the desk fetches *after* boot and a desk
runs offline: they are served by lighting7 itself from `src/main/resources/static/`, so they must
still arrive. Finally, open Lux, send a message, close the sheet, reopen it, and confirm the
conversation is still there — the panel is now mounted on first open and deliberately never
unmounted, and losing the thread on close would be the regression. 10 minutes.

---

## `FU-MANUAL-COLLAPSED-PANELS`

**A collapsed overview panel is gone, not merely flat** · from the frontend sweep's Layout cluster,
2026-08-30 (`FS-PERF-COLLAPSED-PANELS`, lighting-react `52660a6`)

Layout rendered all four overview panels on every route, with visibility switching only the CSS
grid rows — so on a live rig the mini-stage kept re-rendering its markers, the effects panel kept a
beat interval running and the cue-slot panel kept its queries and listeners alive behind a
zero-height container while the operator was on some unrelated page. Each panel is now a wrapper
plus a body, and the body unmounts once the collapse animation finishes. This is a code-read win,
not a profiled one, and the three things a build cannot check are the animation, the reopen, and
the drag.

**Test**: with a patched rig outputting and the programmer holding values, open all four overview
panels, then close each one and watch it *animate* shut rather than snap — the body is held for
200 ms precisely so it can. Reopen each immediately and confirm it comes back populated with no
spinner and no "No fixtures placed yet": the mini-stage should show live marker colours at once,
the effects panel a BPM and a beating dot rather than a dash. Then leave a panel closed for two
minutes and reopen it — past RTK Query's cache retention this one *may* show a brief spinner, which
is correct; what must not happen is an empty state where data exists. Navigate to `/users` with all
four closed and confirm the desk is quiet. Finally, with the cue-slot panel open and in edit mode,
start dragging a cue toward a slot and — without releasing — have a second person click the cue-slot
toggle in the toolbar; the drop must still land, not vanish (lighting-react `6365894`). That last
step is the one genuinely open question in the cluster: the body is held mounted through the drag,
but the wrapper still collapses to zero height, and if dnd-kit re-measures droppable rects rather
than using the ones cached at drag start, the drop resolves to nothing anyway. If it does vanish,
the fix is to hold the wrapper open too, not just the body. 10 minutes.

---

## `FU-MANUAL-CUE-REPUBLISH-FRAME`

**A cue expanded on one client refreshes when another client retunes a Look it layers** · frontend
sweep `FS-BUG-CUE-TAG-STALE`, 2026-08-30

`GET /cues/{id}/cooked` is tagged `Cue`, and until now nothing invalidated `Cue` from any socket
frame — the two library CRUD signals deliberately don't fire for a contents edit, so a retune moved
the rig while a second tab's read-only grid held pre-edit values indefinitely on a healthy socket.
`republishForSourceEdit` now broadcasts `cuesRecomposed` naming every cue that layers the edited
record, and `store/cues.ts` invalidates exactly those ids. The backend half has an integration test
(`LookRepublishTest`); the client bridge and the two-tab behaviour do not, which is what this check
is for.

**Test**: two browsers on `/show`, same project. In A, expand a cue that layers a Look and note a
composed value. In B, retune that Look (change a colour and save). A's grid must show the new value
within a beat, with no reload — and the network panel should show one `GET /cues/{id}/cooked`, not
a refetch of the whole cue list. Repeat with the cue **dark** (never fired), which is the case the
frame is deliberately wider than the REST field of the same name to cover. Then repeat both with a
template instead of a Look. Finally check the storm guard: with a Look library open in A, do a
handful of rapid saves in B and confirm A issues one cooked read per save, not a cue-cache-wide
refetch. 15 minutes.

---

## `FU-MANUAL-WS-SEND-DROPPED`

**What it proves**: every WebSocket write used to be `if (readyState === OPEN) ws.send(...)` with no
return value, so a programmer set, a Blind press, a blackout, a park, a TAP — all of them — were
discarded in silence for however long the reconnect backoff was sleeping (up to 30 s). The write
path now reports the drop as a toast, and the controls that promise an immediate rig change go
disabled while the socket is down. There is deliberately **no replay queue**: flushing a
minute-old blackout on reconnect would move the rig behind the operator's back.

**Test**: with the desk running and a browser on `/programmer`, pull the backend's network (or stop
the app) and watch the connection pill go red. Then, without reconnecting:

1. Drag a dimmer cell in the grid — the cell must not open at all (mouse *and* Tab-then-Enter), and
   the hover text should say the desk is unreachable. Confirm a cell that Output scope has already
   made read-only still says *its* reason, not the connection's.
2. Press Blind in the show bar, and blackout / GM on `/settings/surfaces` — all three disabled.
3. On `/channels`, in Edit mode: the level sliders, the park/unpark affordances, Unpark All, and
   both "…at Value" buttons are refused, and the park tooltip and its context-menu item say the
   same thing.
4. TAP and the BPM readout in the show bar are disabled; a fixture-detail sheet's property sliders
   are read-only; a busking property pad takes no press.
5. Start MIDI Learn while offline — the dialog must show a reason rather than spinning forever.

Exactly one toast should have appeared across all of that (they share a sonner id). Then restore
the network: the pill goes green, every control comes back live, and **nothing queued fires** — the
rig must not suddenly blackout or move. Finally check the race the disable can't cover: begin a
drag with the desk up, pull the network mid-drag, and confirm the one toast and no stuck state.
15 minutes.

---

## `FU-MANUAL-CHANNEL-FANOUT`

**What it proves**: a multi-channel value subscription used to be woken once per changed channel per
33 ms batch, so a collapsed multi-head bar or a large group row reran its whole snapshot dozens of
times a frame to produce one render. Those wake-ups are now coalesced onto a microtask, one per
batch — which moves every multi-channel reader (the fixtures sheet's rows, the group cards, the
virtual dimmer, the stage views) from a synchronous notification to a deferred one. Single-channel
readers are unchanged and still synchronous. This is a code-read change with **no measurement**: the
risk is not the saving but a wake-up that stops arriving, and a value that silently stops tracking
the rig looks exactly like a rig that isn't moving.

The 3D stage is the case to watch hardest. `FixtureModel`'s `useLiveColour` writes **straight to the
scene**, deliberately outside React, so it has no reconciler to cover a missed wake-up; it now
coalesces too, which is where most of the saving is (a seven-channel colour beam was reapplying its
whole colour seven times a batch, per fixture). A microtask drains before paint, so a beam should
still change on the same frame it always did.

**Test**: with the desk running and DMX flowing, on `/fixtures`:

1. Run an FX on a group and watch a *collapsed* multi-head bar row and a group row. Both must track
   continuously — colour swatch, dimmer bar, the non-uniform indicator — with no visible stutter,
   staleness or lag against a single-fixture row beside them.
2. Expand the same bar and confirm the element rows and the parent agree.
3. Drag a group colour on a group card and confirm the swatch follows the drag at input rate, then
   settles on the value the sheet shows.
4. On the 3D stage, run a colour effect on an RGBW/A/UV fixture with a separate dimmer and watch
   the beam: hue and brightness must follow beat for beat, with no visible step or hold. Then pull
   the dimmer down and confirm the beam darkens without the hue shifting.
5. Open a stage view in Blind, change a colour in the programmer, and confirm the stage repaints —
   this is the derived `createProgrammerChannelSource` fan-out, a different notifier from the wire.
6. Leave a stage view and the sheet open side by side through a crossfade; they must not diverge,
   and neither may end the fade holding a value the rig has left.

Then the teardown case the coalescing has to get right: filter the list hard enough to unmount most
rows mid-FX, clear the filter, and confirm every returning row is live rather than frozen at its
last pre-unmount value. 15 minutes.

---

## `FU-MANUAL-MARQUEE-COUNT`

**What it proves**: `batchCountFor` used to recompute the marquee's "Applying to N" count from
scratch for every rendered cell on every pointer-move frame — an O(rows × columns) reduce over the
whole selection, repeated per visible cell. That count is now hoisted into one `useMemo` keyed on
the cell selection and the row list, so it's computed once per selection change rather than once
per cell per frame. This is a code-read change with **no measurement**: the risk is not the saving
but a count that goes stale or wrong, since the memo's correctness depends on `cellSelection` and
`rows` identity actually changing when the underlying selection does.

**Test**: with the desk running, on `/fixtures` with a large list (enough rows/columns to make the
old per-cell recompute visible as lag):

1. Drag a marquee across many rows and columns and confirm the drag itself stays smooth — no visible
   stutter or dropped frames while the selection grows.
2. Hover a cell inside the marquee and confirm the "Applying to N" popover shows the right count —
   the upper bound across every selected column, matching what a commit would actually write (a
   collapsed multi-head bar's cell counts its heads, not 1).
3. Extend or shrink the marquee (drag further, or click to clear and reselect) and confirm the count
   updates to match the new selection — it must not stick at a stale number from the previous drag.
4. Commit a write over the marquee (e.g. a colour drag) and confirm what actually lands matches what
   the popover promised.

10 minutes.

## `FU-MANUAL-CHANNEL-THROTTLE`

**The channel stream still settles on its final value with no idle timer running** · frontend sweep
`FS-WS-DEBOUNCE-TICK` + `FS-PERF-LITKEYS-ALLOC`, 2026-08-31

`debounceMapUpdates` was a `setInterval` that kept ticking until a tick found an empty batch; it is
now a throttle that remembers the last emit time and arms a `setTimeout` only while something is
pending, so no timer is alive when the rig is still. The risk is entirely at the *end* of a burst:
the last batch of a fade must still be delivered, not left in the pending map. `useLitFixtureKeys`
changed on the same 33 ms path — it now counts matches against its cached set and allocates a new
one only on divergence, so a fade that moves levels without lighting or darkening anything must
still return the identical set. Both are **code-read** changes; nothing here was measured.

**Test**: with the desk running and DMX moving:

1. Run a slow fade to a settled level (a cue with a long fade, or a fader move) and confirm the
   channel readouts on `/channels` and the fixtures grid land on the *exact* final value — not one
   batch short of it. Repeat a few times: an off-by-one-batch bug shows as a value that sticks a
   step below where the desk actually is, and only sometimes.
2. Blackout and confirm everything reads 0 rather than freezing at the last mid-fade value.
3. On `/fixtures`, switch the list to the "only lit" filter, then fade a group up from zero and back
   down. Rows must appear as fixtures cross above zero and disappear as they reach it, and the list
   must not flicker or reorder during the middle of the fade, when membership is unchanged.
4. With the filter still on, park a channel and unpark it, and confirm the row list follows.

10 minutes.

## `FU-MANUAL-PALETTE-COLD-OPEN`

**The first ⌘K of a session opens fully populated** · frontend sweep `FS-PERF-PALETTE-QUERIES`,
2026-08-31

The command palette is mounted on every route and used to hold six list subscriptions open while
closed — refetching and re-rendering on every fixture, group, park and channel-mapping
invalidation, on pages that show none of them. Those queries are now skipped until the palette is
first opened, latched so reopens stay instant. The flag is set in the same state batch as `open`,
so a warm cache should render immediately; what an operator session proves is the **cold** case,
where the palette is the first thing touched after a page load.

**Test**: with the desk running, hard-reload the client and — without visiting the fixtures, groups
or channels pages first — press ⌘K:

1. Confirm the Navigation, Actions and Projects groups are there, and that Fixtures & Groups
   populates (immediately, or within a blink of the fetch landing) rather than staying empty.
2. Type a fixture name and confirm it matches; select it and confirm it lands on the list view with
   the row selected.
3. Close and reopen the palette and confirm it is instant and complete this time.
4. Park a channel from `/channels`, then open the palette and confirm "View Parked Channels" shows
   the right count and "Go to Parked Channel..." lists it with its fixture name.

10 minutes.

## `FU-MANUAL-RENDER-IDENTITY`

**The newly-stabilised render inputs still track the desk** · frontend sweep
`FS-PERF-TRANSPORT-ALLOC` + `FS-PERF-LAYER-SIGNATURE`, 2026-08-31

Three per-render allocations became stable identities: `useShowTransport`'s cue signature and
`animCue` are memoized on the active stack, `ShowPage`'s `runnerDisplay` is memoized on its six
fields, and the programmer layer cache now short-circuits on array identity before stringifying.
Each is invisible when right and looks like a stuck display when wrong, and the transport pair sits
on the reset gate that owns a mid-fade cue reorder — the one place this hook has had a real defect.

**Test**: with the desk running and a stack of a dozen-plus cues:

1. GO and BACK several times and confirm the live/next cursors, the done ticks and the fade chrome
   all follow, including across a stack boundary.
2. Mid-fade, reorder cues (the out-of-order banner's "Fix Order" is the one-press way) and confirm
   the fade the operator is watching runs to completion rather than stopping dead, and that the
   cursors are correct once it lands.
3. Add and delete a cue while the stack is live, and confirm the runner re-cursors.
4. On a phone (or a narrow window), confirm the mobile runner's active/standby cue cards, the done
   ticks and the next-stack label update on GO.
5. On the busking pads, add and remove a Look layer and confirm the active ring follows, then edit a
   value inside a layered Look and confirm the ring does *not* churn on the provenance traffic.

15 minutes.

## `FU-MANUAL-FX-BADGE-COUNTS`

**The "N FX" badges count what the per-target endpoints counted** · frontend sweep
`FS-PERF-BPM-INVALIDATION`, 2026-08-31

`FxBadge` used to hold a `fx/fixture/{key}` or `groups/{name}/fx/active` subscription per card, so
one `fxChanged` broadcast fanned out to one GET per badge on screen. It now derives its count from
the rig-wide `fx/active` list every badge already shares. The count has to come out identical: for
a group, the effects targeting it; for a fixture, its own effects **plus** the group effects that
reach it through membership — the endpoint's `indirect` half, which the client now reconstructs
from the fixture DTO's `groups`. A hierarchical group is the interesting case, since a fixture can
be reached through a parent group it was never listed in directly. Freshness also moves: the badge
now refreshes on the `fxChanged` broadcast rather than on the acting client's own tag
invalidation. **Code-read** change; nothing here was measured.

**Test**: with the desk running, on `/fixtures` and the groups view:

1. Apply an effect directly to a fixture and confirm its card's badge appears and reads `1 FX`,
   promptly, on the tab that applied it *and* on a second tab.
2. Apply an effect to a group containing that fixture and confirm the fixture's badge goes to
   `2 FX` and the group card's badge reads `1 FX`.
3. If the rig has a group-of-groups, apply an effect to the *parent* group and confirm a member
   fixture of a child group counts it.
4. Pause the effect and confirm the badge drops to the muted variant without changing its count;
   resume and confirm it lights again.
5. Remove the effects and confirm both badges disappear rather than sticking at a stale count.

10 minutes.

---

## `FU-MANUAL-STAGE-BUFFER-UPLOADS`

**Only the emitter buffers a frame wrote get uploaded** · from frontend sweep
`FS-PERF-STAGE-BUFFER-UPLOADS`, 2026-08-31

`StageEmitters` used to flag all ~55 instanced attribute and matrix buffers dirty every frame; it
now flags only the groups its writers touched. **Code-read** change — nothing was measured on a
desk, and no frame time is claimed. `StageEmitters.test.ts` pins the mapping by diffing each
writer's actual byte changes against what the flush flagged, so a *dropped* bit fails the suite.
What the suite cannot see is a group whose writer is only reached by a rig shape no test builds,
which is what this check is for: the failure mode is not a slow frame, it is a fixture frozen on
last frame's geometry or colour.

**Test**: with the desk running, on the 3D stage view:

1. Fade a conventional up and down and confirm the beam cone, the floor pool and the lens all
   track continuously, with no step or stick partway.
2. Pan and tilt a mover through its full range, then zoom and focus it if it has those channels,
   and confirm the cone, the floor pool and any wall pool follow the head without lag or freeze.
3. Swing a gobo in and out (the beam should switch between the shell and the volumetric render),
   then a prism in and out, and confirm the extra lobes appear, spin, and *disappear* — a parked
   lobe left drawing is the signature of a missed matrix flag.
4. Run a fixture over a stage region and confirm the region-top cookies light and extinguish as the
   beam crosses them.
5. On a rig with a pixel bar: run a per-pixel chase and confirm every pixel's floor wash updates,
   then black the bar out and confirm the whole wash block clears rather than freezing lit.
6. On a rig with **no** pixel bar — the case this change exists for — confirm the stage renders
   normally and nothing stray appears on the floor.
7. Black out the whole rig and confirm every beam and pool goes, with no ghost left on screen.

15 minutes.

---

## `FU-MANUAL-SIGNATURE-CACHE`

**The programmer's per-key diff compares against cached signatures** · from frontend sweep
`FS-PERF-SIGNATURE-CACHE`, 2026-08-31

`programmerWsApi`'s `changedKeys` used to re-`JSON.stringify` *both* sides of every entry and
provenance diff, per key, per frame — at 10–20 Hz under load. `entries` and `provenance` are now
`signedMap`s, which carry each value's signature beside it, so a frame stringifies only what
arrived. **Code-read** change — nothing was measured on a desk, and no frame time is claimed.

The saving is not the risk; the cache going stale is. A signature left behind for a key whose value
has moved makes the diff call that key unchanged, and the cell keeps painting the old value until
something unrelated wakes it — a stale cell that looks perfectly fine. `signedMap` moves both maps in
every mutator so a caller cannot desync them, and four unit tests pin the three paths that write an
entry outside a state snapshot (the local echo, the single clear, clear-all) — each fails if its
maintenance is removed. What they cannot see is a real desk's interleaving of an operator's own
writes with off-connection ones.

**Test**: with the desk running and a second browser tab open on `/programmer`:

1. Drag a dimmer and a colour on the sheet and confirm both cells track the drag at input rate, then
   settle on the server's value ~100 ms later rather than sticking at the drag's last frame.
2. Drive the *same* property from the second tab (or a MIDI surface) and confirm the first tab's cell
   follows within a beat — this is the echo-then-disagreeing-snapshot case.
3. Clear one cell, then re-record it from the other tab, and confirm the cleared tab shows the value
   again rather than staying blank.
4. Press Clear All, then set the same property again, and confirm the cell repopulates.
5. Run a crossfade with the programmer active and confirm cells do **not** flicker or repaint during
   the fade — the revision gate and the content diff both still have to hold.
6. Change a cue's layer so a key's provenance moves between a Look layer and a template layer, and
   confirm the cell's provenance badge renames rather than keeping the old layer.

10 minutes.

---

## `FU-MANUAL-CHANNEL-VALUE-HOOK`

**Raw channel values read their WS subscription instead of an RTK Query entry** · from frontend
sweep `FS-PERF-CHANNEL-CACHE-DISPATCH`, 2026-08-31

Every mounted raw-channel slider used to hold a `{universe, channelNo}`-keyed RTK Query cache entry
whose `onCacheEntryAdded` called `updateCachedData` on each `channelState` frame — a Redux dispatch,
reducer pass and subscriber scan per changed channel per frame, at up to 30 Hz, for a value nothing
outside the slider consumes. They now read `lightingApi.channels.subscribeToChannel` through
`useChannelValue`, a `useSyncExternalStore` hook beside the fixture-property readers. The per-channel
split, `channelsApi`'s 33 ms batching and the write path are all unchanged. **Code-read** change —
nothing was measured on a desk, and no frame time is claimed.

The risk is the usual one for this shape: not the saving but a value that quietly stops tracking.
Three surfaces read it, and one of them (the dialog) used to pass `skip` for an unparsed input,
which is now a `null` channel reading 0.

**Test**: with the desk running and DMX flowing:

1. On `/channels` for a live universe, scroll the grid and confirm every visible row's level tracks
   a running effect continuously, with no row frozen at an old value. Scroll away and back — the
   returning rows must be live, not stale (this is the virtualizer remounting subscriptions).
2. Drag a channel slider there and confirm the rig follows at input rate and the row settles on the
   value the desk reports, not on the drag's last frame.
3. Park a channel, confirm the row shows the parked value and stops following the show, then unpark
   in Edit mode and confirm it rejoins.
4. Open a fixture's detail sheet and confirm its per-channel sliders track and write the same way.
5. Open the channel dialog, type a partial channel number (so the input is not yet a valid channel)
   and confirm it reads 0 rather than showing a stale value from the previously-typed channel, then
   complete the number and confirm Current jumps to the live level.
6. Pull the network for a few seconds and reconnect; every row above must resume tracking without a
   refresh.

10 minutes.

---

## `FU-MANUAL-BEAT-PRUNE`

**Per-master beat subscribables are dropped when their last subscriber leaves** · from frontend
sweep `FS-CHROME-BEAT-MAP-PRUNE`, 2026-08-31

`speedMastersWsApi` pools one subscribable per master uuid for `speedMasters.beat`, and used to keep
every entry it ever created. On reconnect it re-requests a beat for each key in that map — which
meant asking the desk for beats for every master ever displayed in the tab, including indicators
unmounted long ago. Empty entries are now dropped, identity-checked so a repeated unsubscribe cannot
delete a key that has since been re-pooled for a live subscriber. Master 1's `''` pre-load key and
the reconnect re-request itself are unchanged — the re-request is load-bearing for phase recovery,
and only its membership narrows.

The pooling, the pruning and the identity check now live once in `createKeyedWsSubscribable`, and
`channelSource`'s `createFanOut` — the per-channel notifier behind every derived stage source — was
moved onto it in the same change. Its behaviour is meant to be identical (it had the same logic,
hand-rolled), but that puts the stage views in scope for this check as well as the beat indicators.

**Test**: with the desk running, a bank of at least two speed masters, and an effect on each:

1. Open a surface showing beat indicators for two masters (the speed-master panel, or an FX list
   with effects on different masters) and confirm both pulse at their own tempo.
2. Navigate away so one master's indicator unmounts, then back, and confirm it re-locks phase
   promptly rather than free-running — that is the re-pooled subscription re-requesting a beat.
3. Pull the network for a few seconds and reconnect. Every *mounted* indicator must re-lock within a
   beat or two; none may drift at the pre-drop tempo.
4. Retune a master with a tap while indicators for both are mounted and confirm only that master's
   indicator changes rate.
5. Then the shared fan-out: open a stage view in Blind, change a colour in the programmer, and
   confirm the stage repaints; navigate away from the stage and back and confirm it is live rather
   than frozen. Same for a Next GO preview. A missed wake-up here looks exactly like a rig that
   isn't moving.

10 minutes.

---

## `FU-MANUAL-PROMPTBOOK-RELOCK`

**One capture boundary replaces fifteen `noteEdit()` calls, and the rail's expansion moved onto the
shared hook** · from frontend sweep `FS-RES-PROMPTBOOK-GODPAGE`, 2026-08-31

The auto-relock idle timer used to be fed by fifteen hand-placed calls spread through the page and a
four-deep `onEditInteraction` prop chain. It is now fed once, by `onPointerDownCapture` /
`onKeyDownCapture` / `onInputCapture` on the page root — the same shape `ShowPage` uses at its body
boundary. The claim that needs a human is that **nothing that edits the book sits outside that
boundary**: the annotation sheet, the cut confirmation and the anchor picker are Radix portals, and
the reasoning that they are still covered rests on React propagating through the React tree rather
than the DOM tree. That is correct, and the tests pass, but a 2-minute idle timer tearing a half-typed
note off the screen mid-show is not a failure anyone wants to discover live.

In the same change the rail's card expansion moved onto the shared `useCueExpansion`, which brings
one deliberate behaviour change: a live card the operator collapsed used to spring back open when a
*different standby* was armed, and now stays shut until the playhead actually leaves that cue.

**Test**: with the desk running a show that has a prompt book and at least four cues:

1. Unlock the book mid-show. Confirm the amber ring and the countdown chrome appear.
2. Start a note on an annotation, and a cue rename in the rail, and in each case leave it sitting
   for over two minutes while typing occasionally. Neither may be torn down — the countdown must
   reset on every keystroke.
3. Repeat with the anchor picker open and with the cut-confirmation dialog open: interact, wait,
   confirm the countdown resets rather than running down behind the dialog.
4. If dictation is available on the tablet, dictate into a cue note for over two minutes without
   touching the keyboard. This is the `onInputCapture` arm and the only one no keystroke covers.
5. Then leave the book genuinely untouched for two minutes and confirm it *does* re-lock, with the
   10-second countdown visible first. A boundary that never stops firing is the opposite failure.
6. Expansion: collapse the live card, then arm a different standby. The live card must stay
   collapsed. Then GO, and confirm the new live and next cards open.
7. Open two non-live cards by hand, confirm both stay open together, then GO and confirm they close
   and only the new live/next pair is open.

15 minutes.

---

## `FU-MANUAL-BUSK-VIEW`

**The Busk view on a rig, and speed masters that route and follow** · from the busking view plan,
2026-09-01

All four sessions of the busking-view plan landed on automated evidence alone — `./gradlew
cleanTest test` and `npm run check` — and **not one of its desk checks was ever run**. The plan was
retired to `completed/` with that gap intact, and this is where it went. Two of the six checks need
two browsers, because the thing being proved is that two clients agree about a cursor.

The riskiest claims are the ones no test can reach: that a follower's clock, now *driven* by its
leader rather than free-running, retimes a live chase without restarting its phase; and that GO
from a busk stack card and GO from `/show` move one cursor rather than two.

**Test**: with the desk running, at least two speed masters, and a cue stack with several cues:

1. Set M2 to follow M1 at ½ with a movement chase running on M2. TAP a new tempo on M1: the chase
   rate must halve in step, with no visible phase restart. Both tabs' speed rails and ShowBar tiles
   must show the follower move (two browsers).
2. Busk a movement effect onto a selection with no explicit master. It must land on the
   `position`-usage master; the configure sheet must show that master selected; retagging the
   effect's master afterwards must work.
3. TAP on a follower from a stale client → a structured refusal (`SPEED_MASTER_FOLLOWER`), tempo
   unmoved.
4. Interleave GO from a busk stack card and GO from `/show` → one cursor, both surfaces agreeing
   after every press (two browsers).
5. Pin a cue in Show → its pad appears on Busk. Press it → the playhead jumps and the pad ring goes
   live; the Show view's cursor and its `OffPlayheadBanner` state must agree.
6. Export → import a project holding a follower and a usage: the ratio, the usage and the M1
   relationship must all survive the uuid remap.

20 minutes, 30 with the two-browser items.

---

## `FU-MANUAL-FX-TEMPLATE-PADS`

**A template that holds an effect, end to end** · from the FX templates plan, 2026-09-02

All four sessions landed green, but an effect template touches the busk pads, the programmer strip,
the cue editor, the delete guard and the sync round-trip, and the two apply gestures differ in a way
only a rig shows: a **click** mints a detached programmer-band copy that never follows the template
again, while **⌥click** adds a template layer that does. Nothing automated distinguishes "it ran"
from "it ran and stayed attached to the right thing".

**Test**: with the desk running and a Front Wash group of four heads:

1. Create *Amber Breathe* (Colour, Effect, master by usage). It must appear in the Colour column of
   the Busk view under the hairline, and in the Colour tab of Templates with the wave tile.
2. Select Front Wash on Busk and press the pad → the effect runs on the four heads on M2's tempo.
   Press again → it stops. The presence dot must follow the layer stack, not the effect list.
3. Retune M2 → the running effect retimes. Then edit the template's beat division: the *live*
   instance keeps its old division until the pad is re-pressed (`FU-TMPL-FX-EDIT-NO-RETIME`,
   inherited from deferred Look effects), and a programmer-band copy minted by a strip click never
   follows the template at all.
4. In the programmer, ⌥click the chip with two heads selected, then Record → the cue holds a
   template *layer*; GO on that cue runs the effect; the cue editor's layer panel shows tempo and
   amount overrides that work.
5. Click the chip → *FX running* shows a `programmer band` row; *Save as template…* on it creates a
   second template with the same settings; Clear releases the copy.
6. Delete a template that is both tracked by a cue and running on the programmer → the guard must
   name both, and *Delete anyway* must stop it everywhere.
7. Export → import a project holding an effect template with a `tmpl:` colour parameter and a
   stamped master → the effect, the reference and the master survive the uuid remap. Clone the
   project → the same.
8. Under Beam in the New template sheet, the Effect choice must be disabled with its reason visible.

20 minutes.

---

## Validated

Passed on the rig, or retired unrun because the feature went away; the procedures are in this
file's git history if one is ever needed again.

| Item | Passed | Result |
|---|---|---|
| `FU-MANUAL-DIST-INSTALL` | 2026-08-19 | clean install on Mac + Windows; all four native payloads and editor completion good |
| `FU-MANUAL-AUTH-QR-SCAN` | 2026-08-19 | both QR flows resolved and completed from a real phone |
| `FU-MANUAL-CUEEDIT-HARDWARE` | — | retired unrun 2026-08-24: sweep item D1 removed `cueEdit.*`, so there is no cue to open for edit from a fader |
