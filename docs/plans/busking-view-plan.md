# The Busk view — a pad-first performance surface, and speed masters that route and follow

> **Document status: CODE COMPLETE, UNVERIFIED ON A DESK.** All four sessions have landed —
> session 1 as `6b1c391`, sessions 2 and 3 in [lighting-react](../../../lighting-react) as
> `ca4bc20` and `2ba1d2e`, session 4 as `462d0ff` here and `6d6113f` there. What is *not* done is
> §9: **no desk check has been run for any session**, so nothing below has been seen on a rig.
> The visual design is settled and checked
> in beside this plan at [`busking-view-design/`](busking-view-design/INDEX.md) — a clickable mock
> of the main view, the reworked speed-master sheet, and two low-fi layout alternates (A: left
> target rail, B: MA-style paged banks) that were considered and parked. The live, pannable canvas
> at <https://claude.ai/code/artifact/fdf63aa8-0145-4c8b-b98c-170a7489a4b3> is a convenience copy,
> private to Chris; the checked-in files are the authority. This document is the engineering half:
> the model changes, the decisions and their reasons, and the session split.

## 1. Context

Two asks, one surface.

**The busking view.** `/projects/:id/fx` (`routes/FxBusking.tsx` → `components/busking/BuskingView`
in lighting-react) already has the right bones: select targets, press pads. But it is off-nav and
reachable only by URL[^offnav], its target list is a sidebar list rather than a bank of toggle pads, its
pads cover effects, Looks-with-deferred-effects and templates but **not cues or cue stacks**, and
it says nothing about tempo — the thing a busking operator adjusts most. Other desks' busking
surfaces (the research behind `DEFAULT_SPEED_MASTER_COUNT`'s "visible bank of four") put
group/fixture select pads, palette pools, executor/cue pads and rate masters on one page. That is
the shape the design canvas draws.

[^offnav]: Wrong when written, and left standing as the record of what session 3 set out from: a
    `navItems` entry for `/fx` already existed, in the `live` group. What was true is the rest of
    the sentence — no ShowBar, no tempo, no show chrome — so D8's "enters `navItems`" was in
    practice a rename of the entry rather than a new one, which is why it kept `id: "fx"`.

**Speed masters.** Today a master is a name, an index and a tempo
(`models/speedMasters.kt`: `masterIndex`, `name`, `bpm`, `source`, `notes`, `uuid`;
`fx/SpeedMasterBank.kt`: slot 0 is master 1, every unassigned effect resolves there). Two things
it cannot yet say:

- **What it is *for*.** "Movement" is a name, not a fact — nothing routes a busked movement chase
  to the movement master. Every pad press that wants a non-global tempo needs a picker
  (`BuskingView`'s `defaultSpeedMasterUuid` dropdown), which is exactly the extra press a busking
  surface exists to remove.
- **A relationship to master 1.** A show where movement runs at half the colour tempo needs two
  taps kept in step by hand. A *time signature* — "M2 is ½ of M1" — makes one TAP on M1 retune the
  whole show.

Both changes exist to make the busk view's speed rail mean something: the rail shows M1 big with
TAP, followers with ratio chips instead of TAP, and a usage badge on each explaining where an
unassigned effect will land.

## 2. Decisions taken

- **D1 — usage routing is apply-time stamping, not engine resolution.** When the busk view (or any
  future caller) creates an effect with no explicit master, it looks up the project's master whose
  `usage` matches the effect's library `category` and stamps that master's uuid into the instance.
  A null `speedMasterUuid` keeps meaning **master 1**, everywhere, forever — the invariant
  `SpeedMasterBank` and the wire protocol are built on (`null → slot 0`) does not move. The
  routing is visible and editable afterwards in `EffectParameterForm`, and the engine is untouched.
- **D2 — a following master keeps a real `MasterClock`.** Follow is implemented in the bank as
  write-through: whenever M1's tempo changes (`setBpm`, `tap`, load), the bank recomputes every
  follower's bpm as `m1.bpm × ratio` and calls the follower's own `setBpm`. The engine, `slotFor`,
  the persister and the WS streams all see an ordinary master whose tempo happens to move; no new
  clock type, no engine changes. Beat-**phase** lock to M1 is explicitly out of scope (§7).
- **D3 — the ratio is stored as two ints** (`follow_num` / `follow_den`, both null = manual).
  Floats invite `1/3 ≠ 0.333…` comparison bugs on both sides of the wire; the pair also prints
  itself (`1/2` → ½). The UI vocabulary is the five chips **2× · 1× · ½ · ⅓ · ¼**; the backend
  accepts any positive pair so a custom ratio is a UI follow-up, not a schema change.
- **D4 — follow targets master 1 only.** `follow_num` non-null means "follows M1"; there is no
  follow-target column. No chains, therefore no cycles, no propagation ordering, and the sheet's
  copy ("Follow Master 1") is the whole truth. Master 1 itself is refused a ratio at the write
  boundary.
- **D5 — TAP and typed tempos are refused on a follower**, with a structured error, not silently
  ignored and not auto-unlink. Auto-unlink would mean a stray TAP mid-show silently severs the
  relationship the operator set up deliberately; the refusal names the fix ("unlink in the sheet").
  The UI removes the affordance anyway (ratio chips where TAP was), so the refusal is a backstop
  for stale clients and the script API.
- **D6 — one master per usage, enforced at the write boundary.** Two masters both claiming
  `position` would make routing ambiguous. Not a DB unique index: the column is nullable, partial
  indexes are awkward through Exposed, and the check needs a friendly 409 anyway. Pinned by test.
- **D7 — the usage vocabulary is the effect library's `category` strings** (`dimmer`, `colour`,
  `position` — `controls` deliberately excluded; a settings slider has no tempo). `FxRegistry.category`
  is what the routing has to match at apply time, so minting a parallel enum would just add a
  mapping to keep in step. The frontend already knows these strings (`EFFECT_CATEGORY_INFO`).
  *Caveat for the implementer:* `category` is a free string supplied by script definitions —
  session 1 must pin the canonical set with a test and decide what an unknown category routes to
  (answer: nowhere — null → M1).
- **D8 — the view is `/projects/:id/busk`, on-nav, the fourth ShowBar host.** "Busk" enters
  `navItems`; the page spreads `useShowBarProps` like the other three live views. `/fx` and
  `/projects/:id/fx` redirect from `legacyRedirects.tsx` (the path no longer names a view). The
  existing `components/busking/` machinery (`useBuskingState`, presence, the effect pads) is the
  foundation, not a rewrite.
- **D9 — busk pads move the playhead without an arming confirm.** This is a deliberate contrast
  with `/show`, whose confirm-gated arming exists because *browsing* must not fire cues. The busk
  view has no browsing: every control on it exists to be pressed, a pinned-cue pad names exactly
  what it fires, and the operator chose to stand at a performance surface. The show lock is not
  consulted either — busking is the live use, and the lock is a stray-click guard for editing
  surfaces, not a transport gate (same reasoning as `canOperate`).
- **D10 — pinned cues are a per-cue flag** (`pinned_to_busk` on cues), set from the cue's
  properties sheet / row menu in Show. A separate pin-list table would allow ordering and
  cross-project pins nobody has asked for; a flag survives export/import for free.
- **D11 — the busk speed rail edits the live tempo only.** Same rule as the ShowBar tiles and the
  Speed Masters page: the stored boot default is editable solely in the detail sheet, where it can
  be labelled as such.

## 3. The model

### 3.1 Schema

`speed_masters` gains three nullable columns, all additive:

| column | type | meaning |
| --- | --- | --- |
| `usage` | varchar(16), null | effect-library category this master is the default for; null = routes nothing |
| `follow_num` | int, null | time-signature numerator; null = manual tempo |
| `follow_den` | int, null | denominator; must be non-null exactly when `follow_num` is |

Write-boundary validation (one place, used by REST and any future script surface): master 1 may
not follow; a follower's pair must be positive; `usage` must be in the canonical set and unique
within the project (D6, D7).

### 3.2 The bank

`SpeedMasterSnapshot`, `MasterState` and the live-state wire shape each gain `usage` and the ratio
pair. On `load` and on every M1 `Change`, the bank sweeps followers and writes
`m1.bpm × num / den` through each follower's clock — synchronously, on the caller's thread, in the
same place the change listener already runs, so the persister and the WS `changes` flow see the
follower moves as ordinary `Change` events and every surface (ShowBar tiles, busk rail, manage
page) updates with no new wiring. `setBpm`/`tap` on a follower return the D5 refusal before
touching the clock.

Rounding: the derived bpm is stored and streamed as the exact double (`120 × 1/3 = 40.0`, but
`126 × 1/3 = 42.000…4` is fine); display formatting is the client's existing `formatBpm`.

### 3.3 Routing at apply time

The busk view's effect-apply path (today: `useBuskingFxActions` sending the picker's
`defaultSpeedMasterUuid`) instead resolves: explicit per-press choice from the configure sheet if
one was made → project master with `usage == effect.category` → null (M1). The picker row in the
pad header is removed; the configure sheet (long-press) keeps a master picker as the per-press
override. WALL_CLOCK effects are untouched: `rateSpeedMasterUuid` stays an explicit choice — a
rate scale is a deliberate binding, not a default worth guessing (§7).

## 4. UX

[`busking-view-design/`](busking-view-design/INDEX.md) is the source of truth for layout and copy
(`Main.dc.html` for the view, `SpeedMasterSheet.dc.html` for the sheet); the summary, for
grep-ability:

- **Target band** (top): groups then fixtures as toggle pads, two rows, horizontally scrolling,
  member-count badge on groups, selection summary + Clear. Replaces the desktop sidebar list;
  the mobile bottom-sheet picker stays.
- **Pools**: templates in four family columns (colour pads carry swatches), Looks with deferred
  effects, and ~~the effect pads that exist today~~ **nothing else** — see the reversal note below.
  Pool dimming + hint banner when nothing is selected; presence rings unchanged (the none/some/all
  ladder).
- **Cue stacks**: one card per stack — name, live pip, current → next cue, Release, GO. GO on an
  inactive stack activates at its first cue (the existing go-to semantics); on the active stack it
  advances. Below, **pinned cues** as pads (number, name, owning stack), lit green when live.
- **Speed rail** (right): M1 big with TAP; followers show derived bpm, a link glyph
  ("follows M1 · ½×") and the five ratio chips in place of TAP; manual masters keep TAP. A usage
  badge per master; one caption explains routing. "Manage speed masters" footer link.
- **The sheet** (`/speed-masters` detail): Name · Default usage (select, with the one-per-usage
  rule in the helper text) · Tempo (Manual | Follow Master 1 segmented; ratio chips + a
  120 → ½× → 60 preview when following; Default BPM only when manual).
- **ShowBar tiles** (`SpeedMasters.tsx` `MasterTile`): a follower's TAP cell becomes its ratio
  label; click-to-type is disabled with the sheet named in the tooltip. Everything else unchanged.

### 4a. The effect pads went after all (reversed, 2026-09-01)

§4 above said the effect pads that existed before this plan would stay alongside the new library
pads. They did not: the design canvas draws six regions — targets, templates, Looks, cue stacks,
pinned cues, speed masters — and drawing those *plus* three pools of ad-hoc effect pads, a Controls
pool of hold-to-slide property pads and a beat-division toggle to parameterise whatever those minted
put two instruments on one page. A busk pad presses a **named thing from the library** onto the
selection; a second grid minting anonymous FX instances with their own timing model is a different
gesture wearing the same clothes.

Removed on the frontend: `EffectPadButton`, `PropertyPadButton`, `ConfigureEffectSheet`,
`useBuskingFxActions`, `useBuskingPresence`, and the eight-slot per-target effect fan-out in
`BuskingView` (which is how `FU-BUSK-TARGET-CAP` retired). `EffectPad` became `BuskPools`.
`ActiveEffectSheet` survives — the Programmer's `FxSheet` mounts it too. No backend change.

Three consequences worth carrying forward:

- **An ad-hoc effect now reaches the stage through a Look with deferred effects, a cue, or the
  Programmer's `+ Effect`**, and a raw level through an intensity template or the Programmer.
  Nothing on the busk view mints an FX instance any more.
- **D1's apply-time usage routing has no caller.** The stamping is client-side, and the effect pads
  were the only surface doing it — the backend stores and serves `usage` but does not resolve it.
  Everything else about the rule stands (a master still declares a usage, the sheet still sets it,
  `EffectParameterForm` still shows and edits an effect's master, null still means master 1), so
  `useSpeedMasterForCategory` is kept and documented as uncalled rather than deleted. The rail's
  caption was reworded off the promise it could no longer keep: it now says what a usage badge
  *means* rather than what a press there does.
- **A speed-master card gained a fader.** Hold it and drag: the busk view's own hold-to-slide
  gesture, inherited from the property pads that were deleted in the same pass, and the third way to
  set a tempo beside typing it and tapping it. It trims a tempo that is nearly right, which TAP and
  a typed number both do badly. The travel is 60..180 — the same window as `BindingTarget.
  SpeedMasterBpm` and for that comment's reason — and it **applies as it goes**: the tempo is judged
  by ear against a running show, so the rig moves under the drag rather than on the release. Writes
  are deduplicated on the whole BPM and floored at 50 ms, with the release bypassing both. A
  follower refuses the drag exactly as it refuses TAP and typing.
- **The rail is also the way into `SpeedMasterDetailSheet`**, through the sliders glyph in a card's
  title row. A performance surface that shows a master's usage badge should be able to change it,
  and `/speed-masters` was the only route to that sheet.

## 5. Implementation — four sessions

### Session 1 — speed masters: usage and time signature (backend) — done, `6b1c391`

Columns + validation (§3.1), bank write-through + refusal (§3.2), wire fields on the list REST
route and the `speedMasters.*` socket frames, `ensureDefaultSpeedMasters` unchanged (new masters
are manual, usage-less). Tests: follower tracks M1 through setBpm *and* tap; refusal on follower
writes; one-per-usage 409; master-1-may-not-follow; ratio arithmetic including ⅓; export/import
carries the three columns (uuid references already survive via `ExportUuidRemapper`).
Deliberately invisible to the desk: every new wire field is additive with a null default, so an
old client renders today's bank untouched.

### Session 2 — speed masters: frontend — done, `ca4bc20` (lighting-react)

`store/speedMasters.ts` types; the detail sheet's two new sections; the manage page's list
columns; the ShowBar follower arm; the busk-routing swap in `useBuskingFxActions` + removal of the
`defaultSpeedMasterUuid` header picker (configure-sheet override stays). Pin the
category-set mirror with a test the way `maskPicker.test.ts` pins family lists.

Landed as planned, with four things worth carrying forward:

- **`lib/speedMasterModel.ts`** is the mirror module — vocabulary, ratio chips as int pairs,
  `resolveSpeedMasterForCategory`. Its test pins the usage set against `EFFECT_CATEGORY_INFO`,
  the twin of `SpeedMasterUsageVocabularyTest` on this side.
- **Three tap surfaces, not one.** §4 named the ShowBar tiles; the phone popover row and the
  manage-page row offer the same tap + click-to-type pair and all three needed the follower arm.
  `EffectsOverviewPanel`'s fourth TAP did not — it taps master 1, which can never follow.
- **The configure sheet pre-selects the routed master** rather than showing "Default" and routing
  on send, so the routing is visible at the moment of the press. That also made
  `defaultSpeedMasterUuid` a *derived* value, which had to come out of the reset effect's
  dependencies: a usage retagged in another tab would otherwise wipe an open sheet mid-edit.
- **The live-bank merge compares before writing** for these three fields, unlike the four beside
  them. They are optional on the wire and the server encodes no defaults, so an unrouted master
  arrives with the keys absent and an unconditional `undefined` write is a mutation to Immer —
  every master gets a fresh identity on every tempo push, which is the exact churn that
  field-wise merge exists to prevent. Caught in review, not by a test; there is no guard for it
  (importing that slice under test needs a `speedMasters` namespace in `backendMock.ts`), so the
  invariant lives in a comment.

The `speedMasters.error` frame gained its first consumer here, which session 1 left unconsumed:
a per-master toast, the backstop for writers with no affordance to remove.

### Session 3 — the Busk view becomes a place — done, `2ba1d2e` (lighting-react)

Route rename + `navItems` entry + legacy redirects; ShowBar host wiring; the target band replacing
the desktop sidebar; template pads grouped into family columns; the speed rail component (reads
the live query for tempo, the list query for usage/ratio — the two-BPM rule from
`SpeedMasters.tsx` applies). No new backend.

Landed as planned. Five things worth carrying into session 4:

- **Busk took the full live-view chrome, not just the bar.** §4 drew only a ShowBar, but a live
  view reachable solely from the sidebar would be the one destination the Programmer / Show /
  Prompt Book switcher could not get to. `ShowView` is four now, and `LABEL_AT_760` went back to
  `@[820px]` — the number it had when Run was a view — because that threshold tracks the pill
  count, and is asserted so the fifth pill cannot be added for free.
- **The rail reads the live query for everything drawn.** §5's "list query for usage/ratio" is out
  of date: session 2's field-wise merge copies `usage` and the follow pair into the live state, so
  the REST list is needed only for the numeric `id` a ratio-chip PUT addresses. Both queries stay,
  for that narrower reason.
- **The rail's ratio chips are the second surface that can write a follow ratio.** They keep the
  detail sheet's two rules — both halves or neither, and never `bpm` alongside — and *linking*
  and *unlinking* deliberately stay in the sheet: retuning a link mid-show belongs on a
  performance surface, deciding whether to have one does not.
- **Two things were removed rather than repointed.** `SelectedTargetSummary` went with the
  sidebar it headed (and took `Breadcrumbs`' `extra` prop with it, its last consumer), and the
  Effects Overview panel's route lock went entirely — a panel forced open and made unclosable by
  one route was buying nothing once that route had a speed rail and pad presence rings. Its
  `isLocked`-gated Kill All became unconditional rather than disappearing.
- **The empty-selection state is now dim-and-inert, not a placeholder page.** Seeing the library
  before picking a target is most of what makes a pad grid learnable, and nothing can be pressed
  by mistake: presence is `none` for every pad while the selection is empty. The inertness has to
  go on the **buttons**, not on the pool's own scroll container: `pointer-events-none` there takes
  it out of hit-testing, so the wheel and a touch drag find no scrollable ancestor and the library
  the operator was just invited to read is stuck at its first screenful. Caught in review, not by
  a test that could have known — the pads render either way.

Two things left open, both recorded rather than resolved:

- **Kill All is now unconditional in `EffectsOverviewPanel`**, where it used to appear only while
  the busk view held the panel locked open. The panel's visibility is persisted and it renders on
  every route, so an unconfirmed, undoable "remove every running effect" press is reachable
  anywhere the panel is open, for operators as well as admins. Route-gating it was the wrong shape
  — one control in two places depending on where you came from — but the blast radius is a
  separate question, and a confirm gated on a non-empty stage (the asymmetry `ApplyUpdateDialog`
  already uses) would cost nothing. Revisit if it bites.
- **No desk check has been run.** Everything below in §9 that needs a browser or two is still
  outstanding for the session-3 items; the automated gate (build, 1542 tests, lint at
  `--max-warnings 0`) is all that has passed.

Session 4 inherits a left column whose pools stack full-width. §4 draws Looks at `2fr` beside a
`3fr` cue-stack column, so that split is the session's first move rather than a new region.

### Session 4 — cues and stacks on pads — done, `462d0ff` (lighting7) + `6d6113f` (lighting-react)

`pinned_to_busk` flag + pin affordance in Show's cue properties; stack cards wired to the existing
transport (go-to for activation, GO for advance — session verifies whether Release needs a new
deactivate route or an existing one serves); pinned-cue pads via the go-to-cue path; live/next
state from the same cache `useShowTransport` owns — **no second cache copy of a run cursor**, per
the standing rule in lighting-react's CLAUDE.md.

Both §10 guesses resolved in favour of what already existed, and one thing needed adding that the
plan had assumed away:

- **Release needs no new route.** `POST /cue-stacks/{stackId}/deactivate` serves it, already
  wired as `useDeactivateCueStackMutation`. It is disabled rather than hidden on a stack that is
  not running, so the two buttons keep their positions as stacks go live.
- **`/show/go-to` gained an optional `cueId`.** This is the one addition beyond the flag, and the
  reason is D9 rather than convenience: a pinned pad names a cue in a stack that may not be live,
  so it has to move the playhead *and* fire that cue. Go-to-then-activate fires the target stack's
  **first** cue on the way past — a visible blip on a live rig, in the single gesture the plan
  calls out as naming exactly what it fires. The cue is validated inside the same transaction that
  moves the playhead, before the assignment, because it is the one error here reachable from
  client-supplied data (a pad naming a cue since deleted or moved).
- **The flag rides `CueStackCueEntry`, not just `CueDetails`.** A pad and the stack card above it
  then read their live/next state out of *one* cache — the stack list the transport already owns.
  Sourcing pads from `/cues` would have been two caches and two answers to "is this cue on stage",
  which disagree mid-fade.
- **A MARKER is refused a pin**, at both ends: the toggle is hidden on one (a lone greyed-out
  switch reads as breakage rather than as "not for this kind of cue"), and the pad list filters
  them out anyway, because an imported project can carry the flag and `goToCue` refuses a marker
  server-side.
- **The empty-selection dim moved off the scroller onto each `CategorySection`.** Session 3 put
  `[&_button]:pointer-events-none` on the pool container; the cue column now shares that scroller
  and answers to the playhead rather than to the selection, so a subtree-wide rule would have made
  GO inert for want of a selected fixture. Pinned by `BuskPools.test.tsx`.
- **The transport is passed into `BuskingView`, not mounted there.** A second `useShowTransport`
  on the page would run a second rAF loop and a second reconcile effect writing one runner slice —
  the defect adopting `useShowBarProps` removed from the Prompt Book. The *stack list*, by
  contrast, is read by `BuskCueStacks` directly: that is the same RTK Query cache entry, keyed and
  deduplicated, not a second copy.

Two things the review found afterwards are worth keeping, because both were defects the code's own
comments claimed to have prevented:

- **The `cueId` pre-check had no MARKER arm.** It exists precisely so a bad cue cannot fail *after*
  the playhead moves, and then deferred the marker case to `goToCue` — which rejects one only once
  the transaction has committed `project.activeStackId` and `deactivateStack(previous)` has run.
  A marker `cueId` would therefore have answered 400 with the previous stack torn down and the show
  parked on a stack holding nothing: a dark rig from a rejected press. Reachable from an import
  carrying the flag on a marker, or from a MIDI surface, a script or a stale tab.
- **A released live stack still drew the live pip.** `deactivateStack` stops a stack without
  clearing `project.activeStackId`, so "holds the playhead" and "is running" come apart — and only
  ever because of a press on the new card. Keying the card off the playhead alone showed the green
  pip beside "Inactive — GO fires …", and sent GO to the transport, which would have crossed into
  the next stack rather than firing the cue the card had just named.

Still outstanding: **the desk checks**. §9 items 4 and 5 (interleaved GO from a busk card and
`/show`; pin → pad → press → cursors agree) need two browsers and have not been run, and neither
have session 3's. The automated gate is all that has passed — `./gradlew cleanTest test` (1826
tests, 0 failures) and `npm run check` (build + 1555 tests + lint at `--max-warnings 0`).

One process note, because it nearly shipped a false record: the first claim that this gate passed
was written off a `timeout 900 ./gradlew test` that never ran — macOS has no `timeout`, so the
command died before Gradle and still exited 0. A failing test read as green for the whole build
phase. Read the result, not the exit code.

## 6. Migration

Nothing to migrate — all three columns are additive and nullable, created by the startup schema
pass. Existing masters come up manual and usage-less, which is exactly today's behaviour. A desk
restart is required for the schema change (SQLite dev DBs take no Postgres-gated migrations; the
startup pass is what adds columns). No rollback shims: a rolled-back binary ignores the columns.

## 7. Explicitly out of scope

- **Beat-phase locking a follower to M1.** Write-through keeps *tempo* in step; the follower's
  tick counter still free-runs, so its beat boundary can sit anywhere inside M1's beat. Fixing
  that means a follower samples M1's tick instead of owning a timer — engine plumbing
  (`Frame`/`slotFor` changes), not a bank tweak. Recorded as a follow-up; the audible effect at
  musical ratios is small because both clocks tick at exact multiples.
- **Usage routing for `rateSpeedMasterUuid`.** A wall-clock rate binding is a deliberate scaling
  choice; guessing it from category would surprise.
- **Custom ratios in the UI.** The backend stores any pair (D3); the chips stay five.
- **Momentary pads** (flash/solo — value while held). Real busking desks have them; our pads are
  toggles today and stay toggles this round.
- **Layout alternates A and B** from the canvas — parked, revisit only if the main arrangement
  fails at a real desk.
- **MIDI surface bindings** for ratio chips and busk pads (`SurfaceActions` has TAP already;
  extending the binding vocabulary is its own piece of work).

## 8. Follow-ups to record

On landing, add to `followups.md`:

- `FU-SPEED-PHASE-LOCK` — follower beat boundaries free-run within M1's beat (§7, first bullet).
- `FU-BUSK-MOMENTARY` — pads are toggles; no flash/solo gesture.
- `FU-SPEED-CUSTOM-RATIO` — backend accepts any positive pair; UI offers five.

## 9. Verification

**None of the desk checks below has been run.** Every session's automated gate passes, and that is
the whole of the evidence: the six items here need a rig, and two of them need two browsers. They
are the outstanding work on this plan.

Unit: the session 1 list above. Desk checks (two browsers where marked):

1. Set M2 to follow M1 at ½ with a movement chase running on it: TAP a new tempo on M1 and the
   chase rate halves in step, with no phase restart (setBpm preserves the tick counter). Both
   tabs' rails and ShowBar tiles show the follower move (two browsers).
2. Busk a movement effect with no explicit master onto a selection → it lands on the
   `position`-usage master; the configure sheet shows that master selected; retagging the
   effect's master afterwards works.
3. TAP on a follower from a stale client → structured refusal, tempo unmoved.
4. GO from a busk stack card and from `/show` interleaved → one cursor, both surfaces agree
   (two browsers).
5. Pin a cue in Show → pad appears on Busk; press it → playhead jumps, pad ring goes live;
   the Show view's cursor and OffPlayheadBanner state agree.
6. Export → import a project with a follower and a usage: ratio, usage and the M1 relationship
   survive the uuid remap.

## 10. Scope honesty

Guessed, to be verified in-session: ~~whether stack Release maps to an existing deactivate route or
needs one (session 4)~~ — it does, `/deactivate`; the exact set of `FxRegistry.category` values in the shipped effect library
(D7's caveat — the canonical-set test settles it); whether the target band's two-row grid earns
its keep below tablet width or the mobile arm keeps the sheet picker (session 3, on the mock it
does). The speed-master half is deliberately conservative — write-through and apply-time stamping
were chosen over engine changes precisely so sessions 1–2 cannot destabilise effect processing;
if phase lock later becomes a real complaint, that is the moment the engine gets touched.
