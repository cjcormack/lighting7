# Running the show

Cues, stacks and triggers, speed masters, a stack's FX amount, move in dark, the grand master, and Make rig safe and Quit desk. Moved out of `frontend/CLAUDE.md`, headings unchanged, so a citation of `CLAUDE.md §<heading>` lands here.

### Speed Masters

Named tempo buses. FX subscribe to one by uuid rather than owning a speed, so
retuning a master moves every look that follows it. **Master 1 is the global tempo**:
every legacy surface means it, every unassigned FX resolves to it, and it cannot
be deleted.

Two different BPMs live on a master and the UI must not conflate them. The **stored**
bpm (`useSpeedMasterListQuery`) is what it boots at; the **live** bpm
(`useSpeedMasterLiveQuery`, streamed over `speedMasters.*`) is what it is running at
now. The sheet's BPM column shows the live one and edits it with TAP / click-to-type; the stored
default is its **Start** column (labelled as the boot tempo) and the detail sheet's Starting BPM
(library-sheets plan D7). On the running project the desk couples the two — a live change is
written back to the row after 750 ms, and a stored tempo PUT retunes the running clock — so they are
two columns because they are two routes, not two independent numbers (§Library sheets).

`/projects/:id/speed-masters` manages the bank — one nav entry, one route, no sibling
switcher. `components/SpeedMasters.tsx` is the desk's performance surface, with **two hosts**
since `PD-SPEED-OVERLAY` — the ShowBar, and the Speed Masters *overview panel* that reaches the
bank from a view with no bar (see §Navigation Registry) — and it shows
**every** master, master 1 included. It used to render 2..N on the reasoning that the
ShowBar's own BPM tile *was* master 1; that tile is gone, and the split was the width bug —
two thresholds fired at 560px in opposite directions, so between 560 and 900px the tiles and
the transport left the live-state block nothing and its cue numbers spilled. Don't
reintroduce it: the component's docblock is the record of why. It picks one of three arms
from the bar's `@container` width **and** the master count (`ARMS.shared`, because a container
query cannot see how many masters there are): a named tile each, one railed tile with a pill
per master, or `SpeedMastersChip` at the bottom.

**A host states one thing — `room` — and it picks a width ladder, not an arm.** `ARMS.shared` is
the bar's and `ARMS.dedicated` the overview panel's. The bar's thresholds were never about whether
the tiles *fit*: a tile is ~150px and every one of them comes out of the live-state block, the only
`flex-1` item on that row, so 1600px for four masters is what they cost the cue numbers beside
them. A panel owns its row and is competing with nothing — measured, four masters are 464px of
tiles at a narrow container and 711px at a wide one — so it tiles from 620px and rails from 240px
instead of 440px. 620 rather than something rounder because 788px is a real panel: what a
landscape phone at 852 leaves after the rail. Each dedicated threshold is sized against the tile's width *at* that threshold,
since the tile grows with its room; a flat per-tile figure is wrong at both ends. **The 5+ ceiling
does not move**, because that one was never a width judgment. What a host must never gain is an
*arm* of its own — its own readout, tile or ladder shape — which is how the split brain comes back.
No dedicated threshold may equal its rail width: the chip's hide class and the rail's show class
are a min-width pair at one breakpoint on one element, at equal specificity, so Tailwind's utility
sort would decide it. Write no container-query class in prose, here or in a comment — Tailwind
scans comments, and a placeholder in that shape is emitted as a real rule that fails the build in
`lightningcss`.

**Which master the rail is showing is a `createSyncStore` singleton**, not `usePersistentState` —
the same move `useVisSource` made, for the same reason and now for a second pair of surfaces. Two
mounted hosts each read the key once in a `useState` initialiser with no storage listener, so they
drift the moment one writes, and here the drift is not cosmetic: the selected master *is* the tile,
so its TAP and click-to-edit BPM are the controls on screen, and a press in one host would retune a
master the operator is reading in the other. The key is unchanged (both paths decode with the same
`JSON.parse`), so desks keep the master they were on. `SpeedMasters.test.tsx` mounts both hosts and
pins that they move together; the store is exported so a suite can `reset()` it, because a
module-level cache outlives `localStorage.clear()`.

**The count of surfaces offering TAP and click-to-type stays at four.** The overview panel mounts
`SpeedMasters` whole — the same `MasterTile` and `MasterRow`, not a copy — so it is one of the
four relocated, never a fifth.

**A master can also declare a `family` and follow another master.** Both landed with the busking
view's speed-master work, and both are edited in `SpeedMasterDetailSheet` and in the Speed Masters
sheet's Family, Follows and Ratio columns (§Library sheets):

- **Family** (`intensity` / `colour` / `position` / `beam`) is the **apply-time routing default**. An FX
  created with no explicit master is *stamped* with the family-matching master's uuid at the moment
  it is created — `useSpeedMasterForFamily` in `store/speedMasters.ts`, the rule itself in
  `lib/speedMasterModel.ts`. Nothing resolves the family later, and `null` still means master 1
  everywhere; that invariant does not move. **Its caller is `PaletteEditor`**: an FX palette's
  master is stamped when the FX is chosen, so a Colour FX palette picks up the master whose
  family is `colour` without the operator being asked. The hook spent a while with no caller at all —
  the busk view's ad-hoc FX pads were the original one, and they went when that view was brought
  back onto its design — and was kept for exactly this. Every other half of the rule stands: a master
  declares a family, the detail sheet sets it, `BuskSpeedRail` badges it.
  A family is unique per project (the server 409s `SPEED_MASTER_FAMILY_TAKEN`), and `controls` is
  deliberately not routable — its FX land on master 1, which is what an unmatched
  family is defined to do. `speedMasterModel.test.ts` pins the vocabulary against
  `FX_FAMILY_INFO` the way `maskPicker.test.ts` pins the family lists.
- **Follow** (`followNum` / `followDen`, both null = manual, plus `followTargetUuid`) makes a
  master run at `leader.bpm × num/den`. The server owns that arithmetic *and the timing*: a
  follower's clock is **driven** by its leader's tick, so the two beat together rather than merely
  running at proportional speeds — say that, not "derives its tempo", when explaining the switch.
  Live values arrive as ordinary `speedMasters.changed` frames, so **this side never computes a
  follower's live tempo**, only previews and labels. Master 1 itself may never follow, so the
  sheet hides the control for it rather than disabling it.
  - **`followTargetUuid` is the leader; null means master 1** — the spelling every row written
    before targets existed carries, and the one the sheet sends when master 1 is picked, so
    "master 1" has two representations and comparisons must normalise (see `canonicalTarget` in
    the sheet). Chains are legal (M3 → M2 → M1) and loops are refused server-side
    (`SPEED_MASTER_FOLLOW_CYCLE`), which is why the picker filters through
    `eligibleFollowTargets` — self and descendants excluded — rather than letting the operator
    discover the rule by hitting a 400. Every read-only surface names the leader through
    `leaderLabelOf` / `leaderNameOf`: a bank prop is threaded to each of the four so a follower
    of M2 never reads "follows M1" — **including in its `aria-label`**, which is where three of
    the four kept saying "Master 1" after the visible text was fixed.
  - The REST row carries the **stored** target; the WS live frame carries the **resolved** one.
    They normally agree — a forced delete of a leader unlinks its followers server-side, so no
    route leaves a row naming a master that is gone. They can still diverge on a row no route
    wrote (an import, a hand-edited database): the bank degrades a dangling or looped link to
    manual while the row still advertises it, so the manage page would show a follow badge for a
    master the desk is running manually and a ratio-only PUT on it would 400 with
    `SPEED_MASTER_FOLLOW_TARGET_UNKNOWN`. Prefer the live frame wherever the question is "what
    is the desk doing".

**A follower's tempo cannot be typed or tapped, and exactly four surfaces offer those:**
`MasterTile` and `MasterRow` in `components/SpeedMasters.tsx`, the Speed Masters sheet's BPM cell
and TAP read-out in `components/speedMasters/SpeedMasterSheet.tsx`, and `MasterCard` in
`components/busking/BuskSpeedRail.tsx`. The sheet's pair replaced `SpeedMasterRow` in that count
(library-sheets session 1) and keeps its refusals: a follower's BPM is a read-out (`value:
undefined`, so a marquee over it skips it) and its TAP is disabled with the reason. It adds one
refusal of its own — **off the current project both are read-only** — because they write the running
show's clocks. The other three still swap TAP for the ratio and stop opening the draft. Those four
are all of them: the fifth, an unarmed master-1-only TAP in `EffectsOverviewPanel`, went when that
panel did.

**The busk rail is the second surface that can *write* a follow ratio**, after
`SpeedMasterDetailSheet`; its five chips retune a link that already exists. Both write it the same
way and must keep to both of the sheet's rules: **both halves of the pair or neither** (a half-patch
is a 400), and **never `bpm` beside them** — the server refuses that combination on a follower,
whose tempo comes from its leader rather than from a stored default. The chips deliberately send
**no `followTargetUuid`**: they retune an existing link, and a ratio-only patch carries the stored
leader forward server-side, so sending one would let a chip press re-point the link. Linking and
*unlinking* are the detail sheet's **and the Speed Masters sheet's Follows column's** (Manual, or
Clear, unlinks; a leader links or re-points), under the same rules — both halves or neither, never
`bpm` beside them — and its Ratio column retunes as the chips do; the busk rail still only retunes,
since retuning a link mid-show is the half that belongs on a performance surface.
`speedMasters.error` is the backstop for writers with no affordance to remove (a MIDI surface, a
script, a stale tab); `store/speedMasters.ts` toasts it, keyed per master so a burst of hardware
taps replaces rather than stacks.

Two traps in that area:

- **`speedMasterLive`'s field-wise merge must copy every new field.** It writes named fields into
  the Immer draft rather than replacing the array (so a bpm push doesn't churn tile identity), so
  a field it forgets is written once from the first frame and never again — a family retagged in
  another tab would leave this one routing to the old master for the life of the page.
- **Family and follow ride `speedMasters.state`, not `.changed`.** The change frame is the tempo
  push and says nothing about routing.
- **Never render a link PUT's response `bpm`** — it is the pre-link stored value beside the ratio
  it just accepted (`FU-SPEED-LINK-PUT-STALE-BPM` in lighting7). The state frame corrects it.

Two independent per-FX references, both uuid-addressed:

- `speedMasterUuid` — which tempo an FX's beats come from. BEAT FX only.
- `rateSpeedMasterUuid` — scales a **WALL_CLOCK** FX's cycle (`bpm / 120`). Beat
  FX never read it.

The FX editor gates on the library entry's `timingSource` (as `EffectParameterForm`, retired in
fixture-fx-sheets session 5, did): a wall-clock FX gets its cycle in seconds and the rate
master, a beat FX beats a cycle and the speed master. Showing both to both was the pre-existing
bug — a wall-clock FX's "Speed Master" did nothing at all. **The live FX editor keeps that
gate and draws the timing as a chip** (`FxFields`' `TempoChip`, fixture-fx-sheets D11 and
fx-speed plan D3, D4): every master — on a beat FX the speed master (`M2 · Chase`, master 1 when none
is stored), on a wall-clock one the **rate** master (*Rate M2 · Chase*) — then *Own tempo* (*Natural*
on a wall-clock FX), at the head of the Speed section rather than as a select under it. *Own tempo* is a
beat FX's own tempo with no master (`ownBpm` on the wire, the section's tempo field and Tap); *Natural* is a wall-clock FX with no rate
master (`natural: true`). It lists the live
bank and always sends a concrete uuid — choosing M1 sends master 1's uuid, `SpeedMasterSelect`'s rule,
because `updateFx`'s absent field means "keep" — and it reads the live BPM, never the stored one.
**A new FX is its type's own cycle (one bar but where the library says otherwise) on the master
tagged for its family, else master 1** (venue-visit plan D16): `FxPicker` and `PaletteEditor` stamp it through `useSpeedMasterForFamily` at creation,
so `null` still means master 1 everywhere; a wall-clock FX starts on Natural (4 s).

`BeatIndicator` pulses from the keyed `speedMasters.beat` stream, always — the unkeyed
legacy `beatSync` it used to fall back to is gone from both sides (backend D2). Omitting the
master, or passing master 1 with a null uuid, resolves to master 1's **real row uuid** through
`useMaster1Uuid` in `store/speedMasters.ts`: null means master 1 only on the tempo *write*
messages, and an `''`-keyed subscriber matches no frame at all. Server frames are throttled
(one per 16 beats), so the component free-runs a local timer in between — that interpolation
is load-bearing, not decoration.

### The grand master

**A fifth overview panel, summoned from the header, never resident on it** (venue-visit plan S5,
D5, D12; lighting7 `midi/GlobalScalerState.kt` and `plugins/GrandMasterSocket.kt` are the contract).
`components/GrandMasterOverviewPanel.tsx` is a `CollapsiblePanel` row — the fader, the level, *Out*
and *Full* — registered in `overviewPanels.tsx` between Speed Masters and Cue Slots, so the header
toggle, the ⌘K row (`SunDim`) and the panel come from the one array like the other four. **The
toggle is the level chip**, `GrandMasterChip` (the descriptor's `toggleChip`, which `OverviewToggle`
draws in place of an icon button, and which `Layout` places with the chips — after the programmer
chip, before the icon toggles): `GM 100%`, the percentage alone below 620px of header, amber below
full and red at 0, `aria-pressed` while the panel is open. A press opens or closes the panel and never
moves the level. It was a chip with its own popover fader and an inline fader on the header first,
and Chris moved the fader behind the toggle: a fader always under the pointer is too easy to move by
accident. It is the live project's level, 0–100 %, which
the desk applies at transmit: every dimmer and UV channel, and the colour mix of a head with no
dimmer; a strobe or an LED ring is cut only at 0. So the Stage view and the channel views draw the rig as the grand
master leaves it.

- **Every chip on the app header is 28px** (`h-7`, the ARMED chip's): ARMED, Connected (both its
  forms), the programmer chip (`className="h-7"` in `Layout` alone) and this one; the icon toggles
  and the avatar are 36.
- **While immersive** — panels and toggles withheld — the `ShowHeader` draws
  `GrandMasterImmersiveChip`, a readout and never a control, and only below full.
- **The wire is `grandMaster.state` / `grandMaster.set`** (`api/grandMasterApi.ts`), and the store
  is form 3 (`store/grandMaster.ts`'s `grandMaster` entry, seeded from the last frame) — the chip
  is on the earliest render path. The fader writes over WS through `useLivePush` (50 ms floor, the
  release always lands, `sendGesture` toasting a dead socket); REST's
  `GET/PUT projects/{id}/grand-master` is for tools, and nothing here calls it.
- **A drag shows its own value until the release**, then the desk's frame is the truth, since a
  MIDI `grandMasterFader` binding or another window may move it too.
- **Below 620px of header `ConnectionStatus` draws nothing while Connected** (anything else still
  draws): the chip took the room a 375px phone needs for the programmer chip beside the avatar,
  measured in the app.
- **It is per project and machine-local**: the desk keeps each project's level across a switch and
  a restart, and never syncs it. The MIDI `grandMasterToggle` takes it to 0 and back; the
  `blackout` target is unchanged.
- **A surface binds it** as the catalogue's *Grand master* (a fader, or a button that takes it to 0
  and back) or *Grand master to a level*.

### A stack's FX amount

**Each cue stack has a runtime FX amount, 0–100 %, and the desk owns it** (lighting7 effects plan
D10; `docs/fx-engineering.md` §"Amount, direction and runs" there): a weight on the FX that
stack's cues run, applied after the blend as an FX's own amount is. `api/stackFxAmountApi.ts`
reads `stackFxAmount.state` (every stack below full; absent is 100 %) and sends
`stackFxAmount.set`; `store/stackFxAmount.ts` is form 3, `useStackFxAmount(stackId)`
narrowed to one stack. `components/runner/StackFxAmountControl.tsx` is the *FX amount* slider, 0–1
on the wire and a percent on screen, amber below full: on the Show view's stack row (from
`@[480px]` of the overview) and, as `MobileStackFxAmount`, a row under the phone runner's top
strip — drawn below full, and at full only while an FX runs whose cue this stack weighs
(`cueHolds.state`'s `stackId`, the cue's highest hold's stack, read only at full). A runtime gesture, so it works while the show is
locked, as GO does; it greys only offline, and a press inside it never drills the row. It writes
through `useLiveFader` (`components/editor/`), the grand master fader's drag-and-settle lifted out
so the two cannot differ. A project switch starts every stack at full and a reload keeps it. A surface
binds it as the catalogue's *FX amount*, on a stack.

### Move in dark

**What a stack moves in the dark is the desk's** (lighting7 move-in-dark plan D11; its
`docs/cue-stacks-engineering.md` §"Move in dark"). `api/moveInDarkApi.ts` reads `moveInDark.state`
— every head a live stack is moving or waiting to move, with its state (`WAITING · MOVING · SET ·
LIT_EARLY`), the entry and cue it is for and their label, its families and the target's pan and
tilt — and anchors the delay or move time left to this browser's clock on receipt (`endsAtMs`);
`store/moveInDark.ts` is form 3, `useMoveInDarkHeads(stackId)` narrowed to one stack. The key stack's
`MOVE_IN_DARK` row is drawn by `LayerStack` as *Move in dark · for Q12*, above the cue it beats, and a
fixture sheet row the move holds reads *Move in dark · Q12* (`rowSource.ts`, off `provenanceState`'s
`moveInDark`, which `provenanceSignature` carries so a key moving from the live cue to a move wakes).

The stored half is the desk's static plan, `GET cue-stacks/{id}/move-in-dark` (`StackMoveInDarkPlan`,
`useCueStackMoveInDarkQuery` in `store/cueStacks.ts`): per cue entry its cue's own mode, the heads moved
in the dark for it and the entry each move happens in, and the heads that move lit and why (`LIT_BEFORE · TOO_QUICK ·
KEPT_LIVE · CUE_OFF`). No frame of its own: it rides the stack list, the cue list, the cue
(`cuesRecomposed`), the Looks, palettes and the rig. Off the current project it answers `rigLoaded:
false` and every surface says the count waits for it. `lib/moveInDark.ts` is the pure read-out —
the words, a cue's cell (`darkCellOf`: set, lit and its reasons; *Off* off the entry's own `mode`, else
a `CUE_OFF` reason from an older desk), what a stack moves now (`movingNow`) and the stack
block's draft — and `cues/moveInDarkMarks.tsx` its one look: indigo, a dashed edge and a moon for
*moved in dark*, amber ▲ for *moves while lit*. Six screens read them:

- **The stack sheet** (`CueStackForm`'s `MoveInDarkStackBlock`, under Loop): the switch, *When*,
  Delay and Move time in seconds, *Moves* as three chips, saved with the name and Loop. A new stack
  opens on at the defaults, one from a desk that sends no setting reads off, and a Save that leaves
  the block — or one of its times — alone sends it as stored, since the fields show tenths. Its
  read-out (`☾ 38 heads in 14 cues · ▲ 3 move lit · 2 cues off`) is the stored list's; pressed, the
  sheet closes and the host opens the stack's cue table on those cues (`onShowDarkCues`: the Show
  page's `/show/stacks/{id}/table?dark=1`, threaded through `ShowOverview` and `OwnerEditor`). The
  fields start afresh on open or another stack, never on a re-read of the same one (a host passing
  the live list hands a new copy on every GO); switched off, the hidden times neither hold Save back
  nor are written. The sheet has no locked arm: every door to it is an unlocked gesture.
- **The Cue tab** (`CueMoveInDarkSection`, between Notes and Speed masters): the segment, its first
  option the stack's setting (*Stack · Early*), Delay and Move time showing the stack's until typed
  over (blank gives it back), and the heads this cue lights in the stack it was opened from (else its
  first place) — *From*, *Moves*, *Now* from `moveInDark.state` (*Set*, a bar while moving, *Waiting*,
  *Lit early*) and *In dark · Live*, which writes `moveInDark.live` (a head live with its whole
  fixture goes dark alone, the fixture's other heads written live), built on the list as last written
  until the cue reads back, so two quick toggles both land. The list sits under *Heads this cue
  lights* and over the page's legend line; the heads that move lit follow it, each with its families
  and why (*Lit in Q11*, the entry played before, for a head lit there; the reason's sentence
  otherwise). Each gesture is one cue `PATCH` of the one
  field (`CuePatchInput.moveInDark`, partial); locked, the controls go and the list stays.
- **The cue table's *Dark* column** (`CueSheet`, after Follow): `☾ 6 ▲ 1 lit before`, `Off · 4
  live`, and on the live row what is moving now (`4 moving → Q12`); the words fold to the glyphs
  below the sheet's `@container/cuesheet` 900px. A press selects the row and opens the pane on the
  Cue tab at that section (`CueEditorTabRequest.section`). `darkOnly` (`?dark=1`) shows only the
  cues the column says something about, with *Show all*; it turns row drag off (a reorder naming only
  the shown rows would put the rest after them) and filters nothing until the plan has loaded on the
  current project, saying why. A cue card carries the folded cell
  (`CueDarkGlyph`, which selects its entry, never a fresh cell, so a card re-renders only when it moves).
- **The stack row, the stack's header and the phone runner** (`StackMovingChip`): `☾ 4 moving` (and an
  amber *lit early*) on the Show view's row while the stack moves anything, nothing otherwise; in the
  drilled stack's header (`StackDetail`, locked or not) and on the phone's *Up next* card, for the
  playhead's stack, `☾ 4 moving for Q12 · 1.4s`, the cue named being the one the heads
  move for, which under Early may be past the next.
- **The Stage view** (`stage3d/MoveInDarkMarker.tsx` over the pure `moveInDarkOverlay.ts`): for each
  head `moveInDark.state` moves with a position, a dashed aim line along where the head points now, a
  ring where the move lands — the beam's own landing, cast at the target's pan and tilt — and a badge,
  `☾ Q12 · 1.4s`. Behind the View menu's per-window *Moves in dark* (`scene/movesInDark.ts`, on by
  default, announced as `movesInDark`), in the two Output sources only, never in a `render_view`
  capture. See `docs/stage-vis-engineering.md` §"Moves in dark".
- **The Timeline's *Dark* column** (`StackTimeline`, after the kinds, drawn while the plan has
  anything to show): one hatched span per move from the entry it happens in to the entry it is for,
  across markers and round the end of a loop, side by side in lanes where spans share a row
  (`lib/stackTimeline/darkSpans.ts`); `☾ 4 → Q12` where a move starts and the cue's own cell where
  moves land. Read-only: a tap selects the cue the move is for and opens the pane on its Cue tab at
  Move in dark.

### Make rig safe and Quit desk

**The end of the night** (venue-visit plan S6, D9, D13, D14, D18, D19; lighting7
`docs/fixtures-engineering.md` §"Bulk commands and Make rig safe" is the contract). Four surfaces, one
frame:

- **`rigSafety.state`** (`api/rigSafetyApi.ts`, form 3 in `store/rigSafety.ts` — the banner is on the
  earliest render path) carries the run, *Ready to switch off*, *Make rig safe*'s blackout, what *Undo*
  would strike, the restrike ledger and this socket's `quitAllowed`. Every operation is REST, and the
  frame is how every window follows it. Times left are anchored to the frame's arrival
  (`receivedAt`) and counted down by `lib/rigSafety.ts`'s `remainingAt` — the pure model the banner,
  the chip and their tests share (`rigBanner`, `runProgress`, `unitLine`, `blackoutReason`).
- **The Grand Master panel's Rig row** (`components/rigSafety/RigSafetyControls.tsx`, under the fader):
  *Make rig safe…* (every lamp or the selection's), a *Lamps* menu of bulk Lamp on / Lamp off / Reset
  over the selection (`useSelectionPair`, this window's pair) or the whole rig, and *Undo rig safe*
  while there is something to undo. Each asks first; all are inert while a run goes or offline.
  Refusals toast through `errorToastMiddleware`.
- **`RigSafetyBanner`**, in `Layout`'s band slot after the venue banners, never on the header (its
  375px budget is spent): a run's progress, the next lamp waiting on its restrike counted down,
  *Details* and *Cancel*; neutral *Lamps off, cooling — safe to power down in 4:12* while the frame's
  `coolingRemainingMs` holds (`coolingLeft`), with *Undo* and *Quit desk…*; green *Ready to switch
  off* with *Undo* and, where `quitAllowed`, *Quit desk…*; amber *Blacked out by Make rig safe, not ready* with why, *Undo* and *Release blackout*; a
  run that ended with something not done until this window dismisses it. The amber banner also says
  *Lamps still cooling: mains safe in m:ss* from the lamps' own `coolRemainingMs` (`lampsCoolingLeft`)
  when output moved during the cool-down. Immersive, the `ShowHeader`
  draws `RigSafetyImmersiveChip` instead, a readout. *Undo* asks first everywhere
  (`UndoRigSafeDialog`): a stray press would strike still-cooling lamps minutes later.
- **Quit desk** — the user menu's last item and the banner's, both only where the frame's
  `quitAllowed` holds (an admin on the desk's own listener). `QuitDeskDialog` warns when the rig is
  not ready and sends `force` only then (*Quit anyway*); a cooling rig quits plainly, the dialog saying
  how long to leave the rig's power on, and `markDeskQuit(coolsAt)` hands that time to `DeskQuitScreen`,
  which counts it out once no window hears the desk; it shows refusals inline (`quitDesk` is in
  `SILENT_ENDPOINTS`). On success `markDeskQuit()` raises `DeskQuitScreen`, mounted in `App` beside
  the gates so the reconnect screens they draw once the socket closes never cover it. A desk that
  backs up on quit (the Cloud tab's *When the desk quits*) answers `backingUp` with the backups'
  status, which `markDeskQuit(coolsAt, backup)` hands on: the screen reads `GET
  /install/cloud/backups` every second (a read from before the quit set aside, by its
  `fulfilledTimeStamp`), says what the backup is doing, that the desk waits 15 minutes at most, and
  offers *Quit without waiting* (`skipBackup`), and once the desk stops answering — the only sure sign
  it has gone — says whether it backed up. `RunningLine` is `cloud/BackupRunLine.tsx`, kept apart from
  the Cloud tab so the always-mounted screen does not pull it in.

The fixture panel's *Commands* menu reads the restrike ledger too: a *Lamp on* (`role: 'LAMP_ON'` on
the descriptor) inside its restrike time says *Still cooling — restrike in 5:54* in the menu and the
confirm, and is still offered.

### Cues, Stacks & Triggers
What a stack moves in the dark — the stack sheet's block, the Cue tab's section, the cue table's
and the Timeline's *Dark* columns, the chips and the Stage view's marks — is §"Move in dark" above.

Cues bundle an ordered stack of **Look layers** (see §Looks and layers), their own attribute assignments, ad-hoc FX, and **script hooks** into named snapshots. A cue is a project record, and a stack's list is **entries** naming cues (lighting7 cue model plan): a cue may sit in several stacks, twice in one, or in none (*loose*). A project owns an *ordered* list of stacks (the "show"); a stack owns an ordered list of entries. **A place's number and follow are the entry's** — written through `patchCueStackEntry`, never the cue's PATCH, which ignores them — and so is a prompt-book anchor (`/prompt-book/anchors/{entryId}`; the book keys everything on entry, and a shared cue is a card and an anchor per entry). *Remove from stack* deletes the entry, never the cue, and asks nothing; *Add to stack…* shares the cue, and *Move to stack…* moves the entry whole — number, follow and anchor with it — through its own route (§"The cue action set" below); a cue in two or more places wears the `in N stacks` chip (`SharedCueChip`, from `lib/cuePlaces.ts` over the stack list). Deleting a stack keeps its cues, its confirm (`DeleteStackDialog`) offering, unticked, to delete the ones no other stack uses after listing what names them from a dry run. A drawn row is an entry, so the Show view's cards, the cue sheet and the phone list are lit from the transport's entry cursors (`serverActiveEntryId`, `standbyEntryId`), a card opens per entry (`?entry=` beside `?cue=` where the cue sits twice in the stack), and arming names the entry. A stack row can also be a **SEPARATOR** (a label-only divider between stacks). A cue can also carry **scenery** — scene elements it moves on GO, each on its own clock — shown on its card (with what it tracks, hatched) and edited in the cue editor's Scenery tab, in the cue table's Scenery column and from the Prompt Book's rail card (§"Scenery in the cue table and the Prompt Book" below); a stack has a *set* and a Look *scenery while live* (stage-view plan session 8, §Stage views). Cues and stacks are authored **and run** entirely in the **Show** view (`/projects/:projectId/show`, drilling into a stack at `/show/stacks/:stackId?cue=:cueId`) — the old separate "FX Cues" view has been removed, Show was itself called Program until the programmer moved out of it into `/programmer`, and the separate **Run** view folded into it in session 2b (see §Navigation Registry and §The show-editing lock).

#### The cue editor

**The Values tab edits the cue's own rows in the programmer's table, and sends nothing to the stage**
(show-cue-editing plan session 5: D2, D10, D14, P2, §3.3, §4.1, §4.5; the editor's header and tabs
are §"Cues, Stacks & Triggers" above). `components/cues/editor/CueValuesGrid.tsx` mounts
`FixturesTable` — the four cells, the marquee, the double click, Set · Clear · Fan — under an
`EditorContext` of `{kind: 'cue', cueId}` and over the editor's `CueRowStore`.

- **`CueRowStore`** (`CueRowStore.tsx`, `cueRowDraft.ts`; `LookRowStore`'s shape over one cue): the
  server state is the cue's details and its `GET /cues/{id}/cooked`; the **draft** holds own values
  set and own rows removed, keyed by target type, key and canonical attribute (`colour` and
  `rgbColour` are one row), notifying per key. A write schedules a flush — 400 ms quiet, 2 s ceiling
  — that rebuilds the **whole** `attributeAssignments` list from the cue as it stands then
  (`applyCueRowEntries`: every kept row's `fadeDurationMs` and `sortOrder` kept, a new
  row appended after the last order, read-only fields such as `health` dropped) and PATCHes it. An
  entry retires only once the cue agrees **and** a cook read after its save has landed (`markSaved`
  / `reconcile`), since the two queries refetch apart and retiring on the details alone flashes the
  old composed value. The flush is read through a ref by its timers and the unmount teardown, so a
  new `flush` identity is never mistaken for an unmount: a card collapsing on a GO, a pane folding
  or a page left still lands a pending edit. Every editor mounts its own store, so two editors of one
  cue keep their own drafts, selections and Undo.
- **A save builds on the cue as the desk holds it**, read past the cache at every flush
  (`fetchLatestCue` in `store/cues.ts`): another client's cue PATCH fires `cueListChanged`, which
  invalidates only `CueList`, so a cached cue can hold a list someone has since rewritten. A flush
  sends only the entries no save has carried yet, and saves and Undos run one after another.
- **Undo is one step**: the last save that landed, taken back from the history by the save's own
  entry (§Undo) — from the *Q1 · values saved* toast; ⌘Z is the global key, which takes your newest
  show change, normally that save. Pending edits to the save's rows are dropped with it; a refused save
  leaves the previous Undo standing. **One flush is one PATCH** for
  everything pending — the rows, and the Looks and FX tabs' lists — so one Undo puts back each list
  that save wrote (the toast names them: *Q1 · values and looks saved*); a list gesture still
  pending lands after the Undo, on what it put back. The header's `CueValuesSaveState`, beside
  `SaveStatusIndicator` (which already says *Saving… · Saved* for the PATCH), says *Unsaved* while
  a flush waits and *Save failed* when the desk refused one, for all three tabs.
- **The writers** (`useCellWriters`' cue branch, `cueCellWriters`): set, colour, position and clear
  land in the store and **nowhere else — no live fallback**, so a cue context with no store drops the
  write. A colour stores the components the write named (no wire sampling); a single-axis nudge on an
  atomic `position` takes the other axis from the draft, else the **composed** cell (`composedValue`),
  never the wire; clear removes the own row. A **group row's** commit is one group own row — the value
  clamped against the first member that takes the column (`writeGroupRow`), written by
  `cueCellWriters(store, 'group')` — and every other row writes its heads.
- **Fan** posts `programmer/fan` with `write: false` (`useMarqueeSpreadPlans`' cue arm) and
  lands each `written[].value` as an own row on its head, as a focused Look layer's land in its
  draft; an older desk's intent answer is refused by shape. Read-only, it says why.
- **The reads** (`useScopedRowValues`' fourth arm, `useCueRowValues`): the draft over the cook, each
  cell's `CellState.source` from the cooked row's `layerId` / `layerSource` and its
  `sourceTargetType` / `sourceTargetKey` — the row's own target before group expansion, which the
  desk answers since this session. **An own cell has a blue edge** with a faint wash, **a Look-won
  cell a violet one** (teal is a palette reference's, palette-references plan D19), a row whose heads
  disagree a muted one (`cueSourceCellClass`); the hover
  names it (`cueSourceTitle`: *Own*, *Own · overrides Scrim · opaque*, *Own · Front's row*, *From
  Scrim · opaque*). *Overrides* is what this editor saw win the cell before the own value was typed
  — the cook keeps only the winner, so after the save nothing else says it, and an editor opened
  afresh reads plain *Own* (Chris's call: session-only, no backend field). ⌫ removes the own row and
  the cell shows the layer again at once where the cook already names one; over a cell the cue holds
  no own row on it writes nothing. **A group's own row is
  drawn on the group's row** and its members read it through the group unless a fixture row of their
  own beats it; cells a row cannot take are blank, as on the programmer.
- **The rows** (D14): every head an own row or a layer reaches, in rig order (the raw fixture list's
  order, through `useFixtureLookup`), a multi-head fixture opened onto its elements where the cue holds
  them (the operator's toggle flips it), and above them a group row per group the cue holds an own
  row on. The selection bar is the kit's `SelectionBar` with **Own only** (only the rows the cue holds
  itself), **+ Fixtures…** (`CueTargetPicker` in a popover — a fixture or a group, so a group own
  row can be started on the group's row) and **Whole rig** (every visible fixture) — in its chip slot
  with nothing selected, after Set · Clear · Fan otherwise.
- **P2 — what it does not mount**: no desk-selection bridge, no Release, Locate or Highlight (the row
  hover's Info, Locate and Add-to-targets are withheld in the cue context), no
  `useClearCellFx`, no live writer, no `projectId` on the table (so no appearance leaves and no
  *Save as palette…*); the source chip is never drawn (its stack is a `programmer.keyStack` request).
  A cell's popup **does** draw the Palettes and FX bands (apply-model plan session 8): they press
  into the cue through `PressCue`, never the programmer — a palette or a Look goes on as a cue layer
  through `POST /cues/{id}/layers/press`, the desk's press rule over the stored layers (Blue replaces
  Red on the heads, a second press takes it off), sent in the store's save chain as one Undo with its
  answer's layers landed in the cache; ⌥click (*Apply as values*) lands a value palette's literals as
  own rows; a bare FX is stored in draft, a second pick on the same selection swapping the one it
  stored. A read-only cue's cells draw neither band (`useCueBandProject`), and a press that reaches
  it anyway is refused with a toast.
  Its selection is `useLocalListSelection` and `useCellSelection`, held by the editor (below), never
  the desk's.
  `CueValuesGrid.test.tsx` runs a full pass — set, colour, position, Fan, ⌫, Undo — over the real
  `lightingApi` on a fake socket and asserts no `programmer.*`, `channels.*` or `fx.*` frame left it.
- **Read-only** — locked, or another project's cue: the cells are inert, Set · Clear · Fan are
  disabled with the reason, and the marquee still selects (§4.6, the cue sheet's rule). **A refused
  ⌫ or Enter over the grid's selection is the editor's, never the transport's**: locked, the show
  binds ⌫ to BACK, so the grid claims both and opens the unlock question (`CueEditor`'s
  `onRequestUnlock`, which the card and the cue sheet's pane pass), or swallows them where the lock
  is not the operator's. A typed `l` still toggles the lock. Escape that clears the selection is
  claimed too, so the right-hand sheet the editor opens in stays open.
- **The programmer's contexts stop at the grid**: it can open over the programmer (the FX table's
  *on Q12*), and context flows through the sheet's portal, so it clears the programmer scope, the
  focused Look and palette layers (`NoProgrammerScope`, `NoLookRowStore`,
  `NoFocusedPaletteLayer`). A group row draws only the columns its own row can take
  (`buildCueRowCells`), a colour with white folds into RGB on a head without a white channel, and a
  drag's commits are throttled at 33 ms as the programmer's are.
- **Key regions** (§4.5, `components/sheet/keyRegion.ts`): a `[data-key-region]` — the editor's root,
  the pane's wrapper — is **active** while it holds focus or took the last pointer press; a press or
  focus inside a portalled overlay (a cell editor, a Select, a menu, a toast) leaves it where it was,
  so a slider in the grid's own editor does not hand the keys back. `useSheetKeyboard` gained the
  test: given a `region`, it hears a key only while that region is active, its guards less the
  region's own ancestry (the right-hand sheet the editor opens in from outside the Show view is a
  `role="dialog"`); without one it yields while any region is. So two cards, or the cue sheet and
  its pane, each take keys only in their own region, and a grid that is not the region draws its
  selection dimmed (`FixturesTable`'s `selectionDimmed`, `cellSelectionClass`'s third argument). The
  programmer's page mounts no region and is unchanged.
- **The Values selection is the editor's** (`CueValuesView.tsx`, mounted under `CueRowStoreProvider`):
  the grid's rows, *Own only* / *Whole rig* / *+ Fixtures…* and its row and cell selection live
  there, so a tab switch keeps them and the Looks and FX tabs act on `selectionTargets` — the rows a
  marquee covers, else the selected rows, through `selectedRowTargets` (a group row as its group, an
  element by its element key). Local state, never the desk's selection (P2).
- **The Looks tab** (`CueLooksTab.tsx`, session 6, §4.1, D3): `LookStack` at its wide density over
  the cue's layers, a layer edited as a layer and never as a Look. Each gesture — reorder, remove,
  enable, amount, blend, mask, stomp, targets — is one `CueRowStore.editLayers` write: the edit
  applied to the cue's `layers` as the desk holds it (`fetchLatestCue`), its layer found there by the
  drawn layer's read-only `id` (`cueListEdits.ts`'s `indexOfDrawn`: another client may have moved the
  list, and a layer the desk no longer holds is left alone), every `sortOrder` restated from its
  place, the whole list written (never `attributeAssignments`), so the desk pairs each layer with its
  stored one and keeps its uuid (§3.4). A disabled layer is stored. The tab draws each gesture at
  once and keeps drawing it while any list write waits (`CueRowStore.listsBusy`), until the cue has
  been read again — so a refetch between two writes never draws a removed layer back. *+ Look* and
  *+ Palette* open the one picker (`ApplyPicker`, apply-model plan session 8) on the Values selection,
  no mask, offering what its heads can take (`cueWriteTargets`, their `targetFamilies` and
  `targetEmitters`), and its presses write the cue through the desk's press route (`PressCue`) — a
  Look's is a layer naming no targets, since it lands whole; `AddLayerSheet` and `LayerPicker` are deleted
  (`addLayerSheetRetired.test.ts`). **A layer's timing and masters are its settings popover's**
  (§11, 12): the wide row's popover draws `LayerTimingFields` — delay, interval, random window, and
  the opt-in speed and rate master overrides that were the add sheet's last step — when the host passes
  `LayerHandlers.onSetTiming`, which only this tab does, for a layer the picker just put on and an
  existing one alike; typed timing coalesces on the store's flush, a master pick saves at once. A
  palette layer's **Targets** is
  `LayerHandlers.onSetTargets`, a new optional member only this host passes, refused with the reason
  while nothing is selected (`targetsRefusal`) — a Look's layer has none, and no target chips; **Open** (`onOpenSource`) leaves for
  `/looks?edit={id}` — the Looks route opens that Look's sheet, the palettes route's rule — or
  `/palettes?edit=` for a palette layer (`hooks/useEditParam.ts`, both routes'). The hand's *Place
  here* strip stays under it, unlocked, and its place is a press on the Values selection
  (`HandCueLayerStrip`'s `host`, through `usePress`'s cue arm), its Undo the store's save toast's.
- **The FX tab** (`CueFxTab.tsx`, session 6, §4.1, D17): one row per stored FX — target
  (`fxTargetLabel`, a selection by its items), FX, attribute, Speed (`×1 · Own 96` on its Own tempo, `×½ · Natural`,
  or the Speed on its master), amount, direction and runs (`fxDynamicsParts`, `runsWord`) and
  its fire timing — reorderable by its grip. A row opens `FxFields` over the stored FX
  through `cueFxDraftOf` / `withCueFxDraft` (`fxEditorModel.ts`: `paletteDraftOf`'s reading,
  so Own tempo, amount, direction, runs and the spread's shape carry, plus timing, element mode and
  the head filter), its `FxScope` from the FX's target through `useFxScope` — `FxEditor`'s
  derivation, lifted to be shared — with the cue's *Delay · Interval · Random* (`TimingFields`)
  under it. The editor opens once the FX library has (whether an FX is wall-clock, so on Natural, is read from
  it), and its draft is its own, seeded on open and again after an Undo (`CueRowStore.undoSeq`). A
  drag's moves coalesce on the store's flush (400 ms, 2 s), a release, reorder or add saves at once,
  and every gesture finds its FX in the desk's list by `uuid` (`indexOfDrawn`), which the store
  strips before the PATCH. The sortable's attributes sit on the grip, never the row, so a click in
  a locked row leaves the key guards (below) to answer ⌫. **+ FX**
  opens `FxPicker` **in draft** (`draft={{current, onPick}}`) over `fxTargetOf(selection)` — one
  row as itself, several as one `selection` FX keyed by the list's canonical JSON
  (`selectionKeyOf`): a tap appends the FX with the picker's starting spec and D16's master and
  runs nothing (no `fx.add`, no active-FX read); a second tap swaps it in place. A Look layer's FX
  are not listed: they name their own targets and are the Look's, edited in its library.
- **Locked, both tabs read** (§4.6): `LookStack`'s `readOnly` draws the rows' badges with no drag,
  the FX rows have no grip, × or editor writes, and the add buttons are disabled with the reason.
  `useRefusedEditKeys` (`editorIsolation.tsx`) claims a ⌫ or Enter pressed while the editor holds
  the keys — not on a focused control or field — and asks to unlock, as the Values grid's refused
  keys do. `CueEditorIsolation` clears the programmer's scope and focused layers
  above both tabs, for the grid's reason.
- **Edited since GO and Update stage** (session 7, D4, D11, §3.5): these tabs' writes send nothing
  to the stage, so the desk marks a held cue they changed in what Update stage sends — a row's
  value, an untimed layer or FX, not a row's own fade or a timed one — and a live cue whose Look
  or palette had its FX edited; a Record leaves the mark. It is `cueHolds.state`'s `edited`, read by
  `useCueEdited` (`store/cueHolds.ts`, narrowed to the one answer). `EditedSinceGoChip`
  (`components/cues/EditedSinceGo.tsx`) draws *Edited since GO* on the editor's header, the cue
  sheet's Name cell and the phone card under its identity (`CueCardBody`), and nothing otherwise;
  `UpdateStageButton` sits beside it in the header and `CueActions`' ⋯ menu has *Update stage…*
  under *Record…*. Both open **one confirm per press**, `UpdateStageDialog`, held in
  `CueActionDialogsProvider` beside Record and Delete since a GO can collapse the card that opened
  it, and mounted only while open: it reads `POST /cues/{id}/update-stage {dryRun: true}` for the
  counts and the cue's fade (`lib/updateStage.ts`'s `updateStageLines`: *3 values change, over its
  2.0s fade · 1 FX edited in place · 1 stopped*), names the cue and what is not re-fired, and its *Update stage* sends
  the one real request. Locked, both ask to unlock (§4.6), or are disabled with the reason where the
  lock is not the operator's. It is REST, so the P2 no-frame tests are unchanged. The header reaches
  the provider through `runner/cueActionDialogsContext.ts`, the openers alone, so the editor does not
  import the dialogs; under no provider (a test) the button is not drawn.
- **Show on stage** (session 8, D13, §3.7; `ShowOnStageButton.tsx`) sits after *Update stage…* in
  the header, so the card, the pane and `CueEditorSheet` all draw it. It points **this window's**
  Stage view and Positions plan at the cue through the `edit` vis source — the entry it was opened
  from, else the cue itself (`EditTarget`, `useVisSource.ts`), announced inside `viewOptions` as
  `editEntry` / `editCue` — and draws the stored cue through the desk's preview, never output: an
  entry's in its stack's context, a cue with no entry on `GET cues/{id}/preview` as a loose press,
  refetched 300 ms after each `cueListChanged` and each `cuesRecomposed` naming the cue
  (`hooks/useEditCuePreview.ts`). It is lit while the window shows that cue. Another editor's press
  moves it, and a press while lit goes back to Output. It works locked, and is withheld with the
  reason for another project's cue. The Screens row and the View menu offer *Cue in edit* only while
  the window shows one. See `docs/stage-vis-engineering.md` §"The fifth source: Cue in edit".
- **Not yet**: a row's own fade is kept on every write but not edited here
  (`FU-CUE-ROW-TIMING`).

#### The cue action set

**One component, five hosts** (show-cue-editing plan §5, D6, D8, D9, D12, D16;
`components/runner/CueActions.tsx`): the card's strip directly under its collapsed row, the cue
sheet's selection bar (after Set · Clear · Fan, before Deselect), the phone card's ⋯ menu in
`CueCardBody`'s header (`actions`; `RunMobile` on the phone Show and the busk Show tab, and the
Prompt Book's rail card), the Cues table's selection bar (`host="table"`, §"The Cues table"), and
the Timeline's bar (`host="timeline"`, §"The Timeline"). It takes the rows (a cue and the entry it was opened from, several from
the sheet), the lock and `onRequestUnlock`, and reads the run state and the stacks itself, so a host
hands in nothing else. **Its Record sheet, delete confirm, Update stage confirm
(`UpdateStageDialog`) and unlock question live above every host** — `CueActionDialogsProvider` (`runner/cueActionDialogs.tsx`), mounted once in `Layout` around
the routed page and **above its path-keyed error boundary** — because a host can go mid-gesture: a
card open only because it is live collapses on a GO from MIDI or another window, and a GO into the
next stack moves the Show view to that stack's path, which remounts everything under the boundary. Without a
provider (a test drawing the set alone) the set draws them itself.

- **The split button follows the show** (D6). Stopped, its face is *Edit in programmer* — Include,
  then the programmer — with *Blind edit in programmer* in its menu; running, the two swap, and the
  face is amber while it is Blind edit. **Blind edit waits for the desk**: `programmerBlindOn`
  (`store/programmer.ts`) sends `programmer.setBlind` at the programmer fade and resolves when the
  desk reports Blind on, and only then is the cue Included — Blind is a WS gesture and Include a
  REST call, which the desk does not order, so sending both at once could put the cue on stage. No
  confirmation within 3 s loads nothing and says so. Blind holds the Include's FX too (D7).
- **Load into programmer** Includes and stays; its title says live or blind. **Record…** opens the
  Record sheet on the cue. **Duplicate** is the desk's route, each row in row order, naming the
  row's entry so the copy keeps its follow, the one copy opened by its entry. **Remove from stack** deletes the entry. **Delete…** is `CueDeleteDialog`
  (`components/cues/`): one confirm for every row, each cue's entries, pads, slots, anchors and
  bindings from `cueDeletePreview`'s dry run, then `POST /cues/delete` with `force`; it says a
  surface binding stays and reports its cue missing (`FU-CUE-BINDING-SWEEP`). The Cues table's
  multi-select *Delete…* is the same confirm. **⋯** is *Add to stack…*, *Move to stack…* —
  `useMoveCueStackEntryMutation`, the entry moved whole by the desk (Chris's call, 2026-10-16, over
  R1.4's add then remove, which dropped the number, follow and anchor) — and *Copy link*, the
  card's address with `?entry=`. On the phone's ⋯ menu *Update stage…* follows *Record…* while the
  one cue is edited since GO (§The cue editor).
- **Locked, only Blind edit works** (D8, §4.6): every other action — *Copy link* included, the
  plan's rule — opens `UnlockShowDialog` (`components/runner/`, lifted from the cue sheet, whose
  refused cell verbs open it too), or is disabled with the reason where the lock is not the
  operator's (`onRequestUnlock` absent; the busk Show tab, which has no lock control, names the
  Show view). **Over several rows** the one-cue actions — the edits, Load, Record, Copy link — are
  disabled with *One cue at a time*.
- **The phone's menu follows the lock itself**, not the phone runner's always-locked layout: the
  menu is the editing chrome a phone has room for, and a locked action asks to unlock.
- **Words fold to glyphs** on container rungs measured in the app (`FOLDS` in `CueActions.tsx`):
  the stack group first, then Load and Record, the split button last — the card's own `@container`
  for the strip, the sheet bar's for the bar, the Cues table bar's for the table. The menu never folds.
- ***← Show*** (D16) is in the programmer's source box while the include target is the one this
  window made from the Show view: `lib/showInclude.ts` records `{projectId, cueId, entryId}` per tab
  on every Show-view Include, and the box draws the arrow only while `ProgrammerSummary.lastIncluded`
  names that cue and entry, linking to `/show/stacks/:id?cue=…&entry=…` — so a cue twice in one
  stack opens the right card. Its word shows from `@[800px]` of row A, the arrow alone down to
  `@[600px]`, and below that it is not drawn: the phone's arm is `Q4 · Update · Revert` to the pixel
  and the arrow pushed Revert out of the box at 393px, so the switcher's Show pill is the way back
  there.

#### The show-editing lock

**`/show` is the runner and the editor, separated by a lock rather than a route.** `useEditLock`
derives it, through `editLockRule` (show-view-fixes plan D18):

```
guarded = canEdit && isShowActive && inProduction
locked  = !canEdit || (guarded && lockRequested)
lockRelevant = guarded; unlockedWarning = guarded && !locked
```

`inProduction` is a show setting (`ProjectDetail.inProduction`, Settings › General's *In production*
switch; synced with the show, every show from before it on, a new show off), read from the current
project by `useInProduction`, unknown reading as on. A show being built or teched turns it off and stays
editable while a stack runs: no lock, no amber, and in the lock control's place a quiet *Not in
production* link to Settings (`NotInProductionLabel`, from the hook's `notInProduction`). The hosts —
Show, the Prompt Book, the Cues table, the busk Show tab — take `locked`, `lockRelevant` and
`unlockedWarning` from the hook and compute none of them; `useHistoryMove` asks `editLockRule` too.

Six things about it are load-bearing:

- **It is a stray-click guard, not access control.** The backend has no notion of it and no route
  refuses a write on its account, so a second client can edit a "locked" show. Dressing it as
  permission would be worse than not having it. `canEdit` is not a role either — the backend
  computes it as "is this the current project".
- **It is not the transport gate.** GO must work while locked; locked *is* the normal running state.
  `canOperate` on `useShowTransport` is a different question and must never be handed `locked`.
- **A stopped show is simply editable**, with no lock chrome at all — there is nothing to protect,
  so there is nothing to warn about. `lockRelevant` gates the chrome.
- **`lockRequested` lives in a Redux slice** (`store/editLockSlice.ts`), shared with the Prompt Book,
  because "I am in a fix-it session" is one fact about the operator and one GO should end it
  everywhere. It is **never persisted**: a running show always opens locked. The re-arm effect
  therefore fires on the *transition* into `guarded` — a show starting, or a running show put into
  production (D19) — and not on mount, or navigating between the two surfaces would re-lock and the
  sharing would be pointless — `useEditLock.test.tsx` pins both.
- **Dragging is disabled through dnd-kit's own `disabled`**, per sortable, never by unmounting the
  `DndContext` — `useSortable` needs that ancestor, so removing it breaks every row. The drag and
  inline-edit affordances are **hidden rather than greyed out**: a row of disabled destructive
  buttons reads as breakage. The cue action set is the exception, drawn whole and asking to unlock
  (below).
- **The cue action set is drawn whole while locked** (show-cue-editing plan D8): *Blind edit in
  programmer* works — it sends nothing to the stage — and every other action asks to unlock, or is
  disabled with the reason where the lock is not the operator's (§"The cue action set"). The card
  draws it whether or not the show is locked, so a locked card is no longer a pure read surface.
- **The lock is the cue sheet's read-only scope** (§Sheet kit): locked, the sheet's value cells
  are inert and a click on the row head arms the cue as next, exactly as a card's body click
  does; `useTransportKeys` keeps its `enabled: locked` and now stands aside from a key another
  handler has already claimed, which is how a name typed into a cue cell beginning with `l` does
  not toggle the lock.
- **Arming is not only a locked gesture: the pip arms** (show-view-fixes plan D13, D14).
  `CueStatePip` (`components/cues/CueRowParts.tsx`) takes `onArm`; given, a pip that is neither live
  nor next is a focusable button, *Arm Q13 as the next GO*, whose press stops at it, so it never
  selects a row, starts a marquee or expands a card. Cards, the Table and the Timeline give it
  wherever they have `onSetStandby`, locked or not; the live and next pips stay read-outs, *On stage*
  and *Next GO*. The bar's *Arm as next* is drawn unlocked too. The locked body click and row-head
  click still arm; unlocked they expand or select, so a click meant to look at a cue never changes
  what GO fires. On a card the grip has its own 14px slot before the pip, drawn only unlocked, so the
  pip is never hidden under it.
- **Transport shortcuts act only while locked**, via `useTransportKeys`, on **both** lock surfaces;
  `L` stays bound in both states so there is always a keyboard way back to a safe desk. Unlocked, the
  row's cue number, name and fade are live text fields, and in an editing surface Space is a space.
  That handler took the *union* of the two it replaced — a focused button must not fire GO as well as
  activating itself, which is a double advance the old Run handler allowed.
- **Unlocked-while-guarded washes the header amber** — `ShowHeader`'s `unlockedWarning`, on both lock
  surfaces, from the hook. The signal is for the *unlocked* state, not the locked one: locked is the quiet default,
  and believing you are locked when you are not is how a show gets edited by accident. A stopped show
  is simply editable, so there is nothing to warn about and no wash. The border is always present and
  transparent, so colouring it cannot shift the layout as the lock flips. The Prompt Book's toolbar
  drew this itself until 2b; two adjacent amber bars said it twice.
- **The lock control is `ShowLockControl` in `ShowHeader`'s `actions` slot**, on Show *and* the Prompt
  Book. The Prompt Book used to draw its own in its toolbar, so one control sat in two places
  depending on the view. It carries the Prompt Book's extra case: where the backend will not accept
  edits, the control is shown but **inert**, because it is the only thing saying why.

**One `ShowBar`, identical on the two live views that have one — Show and the Prompt Book.** Every
host spreads `showBarProps` from `useShowBarProps` and overrides exactly one prop —
`showShortcuts`, which advertises keys and so can only be answered by the host that binds them.
Everything else comes from the hook, which is what stops the bar drifting into near-copies: it
previously had no Blind on the Prompt Book, a different stack-name rule on Show, and a hand-wired
transport on the Prompt Book that gave that page two transport instances. **The busk view was the
third host until the busk-chrome plan's session A** and draws no bar on any board: it still calls
the hook — for the transport — and hands the result to its side sheet's Show
tab, which mounts the phone runner in the bar's place (§Focus and the side sheet). It is the
second view without a bar and for the opposite reason to the programmer's: not too much chrome for
the job, but the wrong shape of it.

**The programmer is the exception, and draws no bar at all.** It keeps `ShowHeader` — the breadcrumb,
the save pill, the view switcher, Start/Stop and the live dot — and nothing below it until row A.
That is the space plan's session 5, and it is *not* what D9 proposed: D9 was to fold `ShowHeader`
into `ShowBar` on all four views, which was built and then rejected at the desk in favour of this.
The reasoning is D1 applied to a band rather than to a row — everything above the grid earns its
place by the line, and ~60px of tempo, cue numbers and transport is the largest thing on
that page that is not about editing values. Three consequences, each of which reads as a bug if you
do not know it is a decision:

- **Blind is toggled on the programmer, and only there.** Blind is a
  *programmer* fact, not show chrome — `ProgrammerSummary.blind`, written by `programmerSetBlind`,
  faded by the programmer's own fade — and session 5 leaving the programmer with no press was the
  one failed check of the desk pass (`PD-BLIND-ON-PROGRAMMER`). The control is the action bar's, in
  row A's Stage zone beside Clear and the fade picker; `useShowBarProps` supplies no `onBlind` **for
  any host**, so no bar draws a tile; Show and the Prompt Book *report* it through the
  `ProgrammerIndicator` their bar mounts, and Busk — which has no bar — through the band's `BLIND`
  pill, the Show glyph's dot and the Show tab's own `ProgrammerIndicator` (§The rig, §Focus and the
  side sheet). All three say *values and FX held*: Blind holds the programmer's FX as it
  holds its values (show-cue-editing plan D7, the desk's `fx/BlindHold.kt`), so a blind busk is not
  a working one either. That is session 2b's arrangement exactly inverted,
  and the rule it was written for still holds: one control, one place. The drift to refuse now is
  the reverse one — a Blind tile back in the bar for one host. Do **not** make `ProgrammerIndicator`
  the toggle either: it is also the link to the programmer, and one control cannot be both without
  one of the two jobs becoming a surprise. It has no `blindShownSeparately` any more — the ShowBar
  tile was its only true caller, and a badge that can be told to stay quiet is one a host can silence
  with nothing else saying it. On the programmer the header's badge and the action bar's button are
  both amber when blind: the reporter and the control, one row apart.
- **GO and BACK are not on the programmer**, which binds no transport keys either
  (`useTransportKeys` is Show's and the Prompt Book's). The switcher in the header is one pill from
  three views that do have a transport.
- **The speed masters are not *resident* on the programmer**, and `PD-SPEED-OVERLAY` did not put
  them there: the bank is summoned from the app header's **Speed Masters overview panel**, which
  hangs over every route and is nobody's view chrome — the programmer gained nothing of its own.
  The FX table's Timing column still names each FX's master, and `/speed-masters` still manages
  the bank.

`ProgrammerPage.test.tsx` pins the absence; `ProgrammerPage.tsx`'s note beside the header is the
long form of all three.

- **The bar is not gated on the show running.** It carries the speed masters and the programmer
  chip, both of which mean something with the show down, and `goDisabled` already mutes
  BACK/GO. Gating it was what once made **Blind's location depend on the show's state**.
- **Blind is not in the bar.** From session 2b to `PD-BLIND-ON-PROGRAMMER` it was, beside a blackout tile,
  on the reasoning that the two are the same class of thing (a gate on what reaches the rig) — and
  that put the press on the three views whose programmer is usually empty and off the one whose
  whole subject it gates. See the programmer bullet above for where it is now. The fade survived
  both moves and must survive any next one: `lib/programmerFade.ts` is a module-level store, not a
  `usePersistentState` per reader, because as two instances of one key it was two mount-time
  snapshots, and Blind snapped for the rest of the visit. The action bar's Blind and Clear read one
  subscribed value from it; the marquee's Backspace reads it at press time.
- **There is no blackout tile in the bar.** The inert DBO tile went with venue-visit plan S5, as did
  the phone runner's and the Prompt Book drawer's copies; the grand master is the app header's panel
  (§The grand master). The MIDI `blackout` target is the desk's one blackout.

**Browsing a stack never moves the playhead.** A tab click used to run
`deactivate(old) → goToStack → deactivate(target)`, so one unconfirmed press took the live cue off
stage and repositioned every other client. `StackTabStrip` now takes `selectedStackId` (the underline)
and `liveStackId` (the green pip) as separate props, and arming is an explicit, confirm-gated control
in `OffPlayheadBanner`. The confirmation is not ceremony: `POST /show/go-to` deactivates the stack
being left and then calls `activateAtFirstCue` on the target, so the target's first cue genuinely
fires and the desk darkens it again — a visible blip on top of losing the current cue.

**Busk stacks come after the show's own** (lighting7 busk-stack plan D1–D4). A stack with
`inRunningOrder` false is a busk stack, run from its own GO or a pad, never the playhead's. The tab
strip and the stack list (`ShowOverview`) draw the running order's stacks and separators, then a
*Busk* divider and the busk stacks, each running one with a live dot and its live entry's number,
and the list numbers only the running order's rows, a busk stack being no step of the show
(`lib/runningOrder.ts`'s `splitBuskStacks`); a drag in the list reorders within its side of the
divider (`reorderWithinGroup`). The off-playhead banner offers no *Make this stack live* for one. The
transport's boundary GO, the next-stack label and `canStart` read the **running order**
(`runningOrder`, `nextInRunningOrder`); the counts that decide whether the strip and the phone's
picker draw keep every stack, busk stacks included. Two predicates answer the GO: **GO releases**
(`isGoReleases`: the playhead stack is set to Release, live, nothing armed) sends GO to the stack's own
advance, which releases it, and **nothing on deck** (`isNothingOnDeck`: no next cue, or the run
state's `releasedAtEnd`) makes it the boundary GO; `useShowTransport` exposes both, and the phone's
GO/END reads them through `RunnerDisplayState`. *Make this stack live* deactivates with `snap: true`,
since a stop otherwise fades over the stack's release time. `CueStackForm` carries *In the running
order*, *After the last cue* (Stay · Loop · Release, one choice) and *Release time*; turning the
switch off sets Release unless the stack loops.

**And the Stacks button has to be able to leave one.** `/show`, `/show/stacks/:id`,
`/show/stacks/:id/table` and `/show/stacks/:id/timeline` are four sibling routes with an `element` each, so going back from a stack
**remounts** `ShowPage` — which reset the `initialDrillDoneRef` that makes the "drill into the live
stack on arrival" auto-navigate fire once, and the auto-drill put the operator straight back where
they had just left. A ref cannot say "they asked for the list" across a remount, so the signal rides
the *location* instead: `STACK_LIST_STATE` on the Stacks button's and the breadcrumb's navigate, read
by that effect. Do not replace it with a ref or a module-level flag — the first cannot survive the
remount and the second would suppress the auto-drill for the rest of the tab's life.

**Two cursors reach a cue row, and neither is a mode.** `serverActiveCueId` places the stable
"on stage" marker; `activeCueId` (the optimistic runner cursor) says which row owns the fade chrome.
During a crossfade those are different rows, so one value cannot serve both. The fade *value* is
never a prop — each row reads its own through `useCueFade`, because `ShowView` is memoized
specifically to stop several hundred rows reconciling at frame rate, and passing `fadeProgress` down
would defeat that with the memo still in place, looking effective.

**Each server run fact has one owner.** The RTK cache owns what the server says
(`stack.activeCueId`, `stack.nextCueId`); the runner slice owns what is local — the animating
cursor, the optimistic next (`standbyCueId`, each with its entry: **the optimistic GO and BACK walk
by entry**, show-cue-editing plan session 4, so in a stack holding one cue twice the optimistic next
lands on the right place before the desk's `cueRunStateChanged` answers), done ticks, fade/auto descriptors — plus a private
memory of the last frame it adopted (`serverActiveCueId`), which exists because a reducer cannot
read the cache. That slice field is **not** a substitute for `useShowTransport`'s own
change-tracking ref: the optimistic mutation patches move the cache with no frame, and a snapshot
frame moves both stores at once, so "do the stores disagree" and "did the cache move" are
different questions (the reconcile effect's docblock spells this out). Surfaces read cursors
through `useShowTransport`, whose docblock maps who reads which and why; don't hand-compute
`activeStack?.activeCueId` in a view, and don't add a second cache copy of a run cursor (the
armed-only `CueStack.standbyCueId` was exactly that — written twice, read never — and was
removed).

**Expansion is the operator's cards plus the playhead's, derived.** `useCueExpansion` owns one
rule — there are two reasons a card can be open, and closing must silence both — and leaves the
operator's slot to the caller, because its storage and multiplicity genuinely differ: Show keeps one
cue in `?cue=` (an external contract; the Prompt Book mints those links), while the Prompt Book rail
keeps a set in local state so two cues can be compared against the page they anchor to. The rail
also auto-opens the cue on deck (`nextCueId`), which Show does not. Either way a GO opens the new
playhead cards and cannot take away one being read, because nothing in the hook writes the
operator's slot. Run kept a `Set` and never removed from it (five GOs, five open cards); Show kept a
bare scalar a GO would overwrite. Dismissed playhead cards self-clear as their ids stop matching;
`resetKey` exists for the one id that survives a transition — a dismissed *next* card the GO makes
live.

**Cue numbers** are numbers — 0 to 9999.9999, at most four decimal places, sorted by value (`2.1` is `2.10`, `10` follows `9`) — and `sortOrder` is the authoritative playback order. `src/lib/cueNumber.ts` holds the model (`canonicalCueNumber`, `cueNumberProblem` for a field's `validate`) and drives the "Fix Order" banner; it mirrors `routes/cueNumbering.kt` in lighting7, which refuses anything else and performs the fix — **keep the two in step**. Every field that edits a cue's number — the cue sheet's Cue # column, the cue editor's header, the card, the Prompt Book's rail and card, Record's *Cue #* — sends `placement: 'ASK'` through `useCueNumberPlacement` (`components/cues/CueNumberPlacement.tsx`), which asks *Move to its place* or *Keep it here* when the desk answers `CUE_NUMBER_OUT_OF_ORDER` (the Prompt Book's create-a-cue sheet places a new cue by page order, so it only checks the number), and the cue sheet's *Renumber…* (`runner/RenumberCuesButton.tsx`), or several of its Cue # cells set at once, numbers the selected cues from a whole number by a whole step through one `RenumberForm`.

A cue without an explicit number gets one derived from its position (`cueNumberAuto: true`), kept by the backend while the cue stays where it is and derived again when a change to the list moves it. Auto numbers render dimmed via `AUTO_CUE_NUMBER_CLASS`; clearing the `Cue #` field returns a cue to auto. **An explicit renumber never relabels its auto neighbours** (venue-visit plan D11): in five auto cues, cue 2 set to `4.5` reads 1, 4.5, 3, 4, 5, `detectOutOfOrder` sees the descent and draws the banner, and *Fix Order* (`sort-by-cue-number`) moves the cue to where its number says rather than renumbering anything — the rule is lighting7's, `docs/cue-stacks-engineering.md` §"Auto Numbering".

**A cue is edited in one editor, two hosts** (show-cue-editing plan D1, §4.1–4.3, session 4;
`components/cues/editor/CueEditor.tsx`). The open card draws its collapsed row, the cue action set's
strip, then `CueEditor`; the cue sheet's bottom pane draws the same `CueEditor` for its one selected
cue row while the window's *Editor* toggle is on (§Sheet kit); and `stage/OwnerEditor` mounts it in a right-hand sheet for a cue opened from
outside the Show view — the fixture sheet FX tray's *on Q12* and the Stage view's *Moves with*.
**Cue properties is gone** (D15): `CuePropertiesSheet` and `CuePropsPane` are deleted, and the card
has no *Cue properties…*. Two cards can be open, each with its own editor.

- **The header is one 40px chrome row** and draws the entry apart from the cue (P5): the stack, this
  place's number and follow — written to the entry by its id, `patchCueStackEntry` — then a rule,
  the cue's name, fade and curve (`patchProjectCue`), the shared chip from `Cue.entries`, and the
  per-cue `SaveStatusIndicator`. A cue read with no context entry has no number or follow to write:
  a loose one says *In no stack · no number or follow* in their place, and one in several places
  opened from none (as the sheet host can be) keeps the fields, disabled, saying why. A
  header field half-typed when its host goes — a GO collapsing a live card — commits
  (`InlineEditField`'s `commitOnUnmount`), as the old properties pane's autosave did. While the
  desk marks the cue edited since GO it draws the *Edited since GO* chip and *Update stage…* (§The
  cue editor); *Show on stage* points this window's Stage view at the cue (§The cue editor).
- **The tabs are *Values n · Looks n · FX n · Scenery n · Events n · Cue*.** Values counts the
  values a GO lands (`/cooked`'s rows). The tab is the window's, `sessionStorage cueEditor.tab`
  (`cueEditorTab.ts`): an open editor keeps its own, a new one opens on the window's last choice, and
  a host may ask for a tab (`tabRequest`, the sheet's Layers and FX read-outs).
- **Values is the cue's own rows, Looks its layers and FX its own FX, each edited in place**
  (§The cue editor). The hand's *Place here* strip (`HandCueLayerStrip`) is in the Looks tab,
  unlocked. **Scenery** is
  `CueSceneryEditor`'s body (`CueSceneryEditorBody`), **Events** `CueEventsEditor`, and **Cue** the
  notes, the speed masters, script hooks (`CueTriggerEditor`) and the busk-page append
  (`editor/CueTab.tsx`).
- **Speed masters** (`editor/CueSpeedMasters.tsx`, venue-visit plan S16, D20) are the masters the cue
  sets on every GO into it and every press of it — never a reload — each a row of master · BPM · ×
  under *+ Master*, which adds the lowest master the cue does not set at its stored tempo, a follower
  only when no other is free. Every gesture writes the **whole list** through the cue's own PATCH
  (`speedMasters`), the rows a draft held over a refetch, settled on the list the last save answered
  and put back on a refusal (which the middleware toasts) — the Events editor's shape. A BPM commits
  on Enter, blur or a pause in typing, never as typed, and one outside 20–300 is not sent. The master list is the project's stored bank (`useSpeedMasterListQuery`), so it reads
  on another project's cue too. A follower's row says *Follows M2 — skipped on GO* and a deleted
  master's *The master was deleted — skipped on GO* (the desk skips both); locked, the section is a
  list of *M2 · Chase · 128 bpm*. `buildCueInput` hands the list back, since a PUT clears it.
- **Locked, it reads** (§4.6): the header's fields are plain text, the curve inert, the Values,
  Looks and FX tables inert, and Scenery, Events and Cue draw their read-outs. A cue the desk will not edit (`canEdit` false) reads the same,
  saying it is another project's. Its field ids are its own (`useId`), so two editors of one cue
  (a cue twice in a stack, a card under the fixture sheet's) do not share a field.
- **It is a key region** (`data-key-region`, §4.5): the cue sheet's keyboard stands aside while it
  has the keys, and its Values grid hears them only then (§The cue editor).
- **`CueDetailContent` stays the read-out** the phone card and the Prompt Book draw (`CueCardBody`),
  its values through `CueValueGrid`, which reads a group-derived cooked row onto the member it names,
  its layers and FX as rows read-only (D9).

*Edit in programmer*, which Includes the cue, is the cue action set's (§"The cue action set"), on
the strip under the collapsed row. The card draws **no stage map**: the 2D
`MiniStage` went from the cards on desktop and phone in venue-visit plan S3, with `StageBackdrop`,
its last user, so the phone card's toggle is *Details* alone and the phone runner, the busk Show
tab and the Prompt Book rail all open their cards collapsed. Consequences worth knowing:

- **Edit in programmer opens the programmer** once the Include lands (venue-visit plan S3); a
  refused Include leaves the operator on the cue, with `useInclude`'s toast. The view switcher's
  Show pill brings them back to the stack and the card (§"The Show position"), and so does *← Show*
  in the programmer's source box (§"The cue action set").
- **Duplicate appends the copy to the end of the stack** and opens it by its entry (venue-visit plan
  S3; show-cue-editing plan D12), on the desk's `POST /cues/{id}/duplicate {stackId, entryId}` since that
  plan's session 3: a full copy — rows, layers, FX, hooks, notes, scenery and events — unnumbered,
  so it takes the next auto number with nothing else in the stack moving, and with the follow of
  the entry it was duplicated from (show-view-fixes plan D1). The client-side
  `buildCueInput` copy it replaced carried neither scenery nor events.

- **`CueValueGrid` reads `GET /{projectId}/cues/{cueId}/cooked`**, which wraps the same
  `buildCombinedCueLayerRows` the GO path runs. Do **not** compose a cue's values client-side —
  layer order, masks, per-layer amount and blend, group expansion and specificity would all have to
  be reimplemented, and each is a place for the desk and the display to disagree. It borrows the
  four cell components rather than mounting `FixturesListContainer`: that container owns a filter,
  a row marquee and a cell marquee, and its selection is Redux-scoped to one of three scopes.
- **"Add Cue" is gone.** A cue is a captured state, so recording is the only way one is made;
  `StackDetail` offers *Record into `<stack>`*. Separators and stacks keep their create buttons —
  neither is a captured state, and that is the line rather than "no new buttons". The Prompt Book's
  `CueAnchorPickerSheet` also still creates a cue at an anchor, deliberately.
- **`EditorContextValue`'s `cue` arm writes the stored cue, never the rig** (§The cue editor). The
  arm 2b removed was a different thing — a *live* `cueEdit.*` session that made a cue and the
  programmer two places to set a value on stage — and that protocol is gone on both sides: the
  `409 CUE_EDIT_SESSION_OPEN` handling, the `force` field Record and Update sent, the two "do it
  anyway" buttons and the Diagnostics `cueEdit` histogram went with it. Don't reintroduce any of
  that; `EditorContext.tsx`'s doc comment is the record of both. `INCLUDE_TARGET_GONE` is Update's
  own 409 and is unrelated — that one is live.

#### The Cues table

**Every cue once, as a library** (lighting7 cue model plan §3.7, D13, session 5): `/projects/:id/cues`
(`routes/Cues.tsx`, `components/cues/CueLibrarySheet.tsx`, the model in `lib/cuesTable.ts`), the
Library row after Looks. A cue is a project record a stack only names, so this is where a loose one
is found — Record's *No stack* makes one.

- **The rows are `GET /cues`**, which carries each cue's `entries` and `usedBy` (`{pads, slots,
  bindings}`). *In* is a chip per entry, `Act 2 · Q14`, or *Loose*; *Used by* counts what else names
  the cue, each named on the hover, and stays current because a pad, slot or binding write — and
  `busk.layoutChanged`, the slot and binding frames — invalidates `CueList` too; Fade, Layers, FX, Scenery and Events are read-outs; the dot by the
  name is D11's two looks from `cueHolds.state`. **Name and notes are the cells** (library-sheets
  D5), `PATCH`ed on the cue.
- **The view is the address**: `?q=` (name, notes, a place's stack or number), `?stack=`, `?show=`
  for the *Loose · Shared · On a pad* chips (counted over the other filters), `?cue=` for one cue,
  `?sort=` / `?dir=` — each written with `replace`; a `?cue=` naming a cue that has gone keeps its
  chip, so the filter can be let go. Nothing is remembered beyond the address.
- **The bar is the cue action set** (`host="table"`, its own `FOLDS` rungs) after the kit's Set ·
  Clear over the name and notes cells, never verbs of the table's own. A row stands for the cue, so
  it hands the set its entry only when it has exactly one (`actionRowOf`): Duplicate appends a copy
  to that stack with that entry's follow or makes a loose one, *Remove from stack* and *Move to stack…* are off whenever a
  selected cue sits in several places (it is changed in its stack), and the table stays put after Duplicate rather than opening the
  copy. *Delete…* is `CueDeleteDialog`.
- **A row opens the cue at its first entry** on the Show view; a loose cue opens `CueEditor` in a
  right-hand sheet (`stage/OwnerEditor`'s `CueEditorSheet`, which takes the lock from this host).
  A loose cue's busk pad "open cue" lands here filtered to it (`cueOpenPath`), drawing *Only Storm ×*.
- **It honours the show-editing lock while the show runs** (cue model plan §10, 20): `useEditLock`
  with `ShowLockControl` in the header, the cells inert and every action but *Blind edit* asking to
  unlock, the loose cue's editor read-only. Off the running project it reads only and draws no
  action set — every one would act on the running show — and no row opens.
- ***Copy from another show…* is the library row's verb** (cue model plan §3.8, §10, 22), in
  `LibraryRow`'s create slot, drawn on the current project only: `CopyFromShowSheet`
  (`components/cues/`), mounted while open, picks the source project (any other, shows before
  venues), its cues (a multi-select over its `GET /cues`, filtered by name or place), then where they
  go — *No stack* or a stack, at its end — and runs `POST /cues/copy-from` as a **dry run** for its
  review: the cues, the Looks and palettes copied or linked by name, and what will not resolve, each
  with the records that hold it. *Copy n cues* sends the same request without `dryRun`. The mapping
  is the desk's alone. Locked it asks to unlock first (the action set's `UnlockShowDialog`, through
  `useCueActionDialogs`), and a GO that re-locks closes it. A copied cue whose fixture the rig lacks
  reads as missing in its editor (`AssignmentHealth`).

#### The Timeline

**A stack's third view** (lighting7 `docs/plans/completed/stack-timeline-plan.md`, D1–D11, C1–C7):
`/projects/:id/show/stacks/:stackId/timeline`, `ShowPage stackView="timeline"`, the third segment
of `ShowStackViewSwitcher` — *Cards · Table · Timeline*. `CardsListView` gained `'timeline'`; it is
stored under `show.view` alone (`getStoredCardsListView` reads it for no other key), and the cards
route's sticky hand-off asks `stickyStackSheetView`, which names the Table or the Timeline.
**From 1100 px of content width** (C6, `TIMELINE_MIN_WIDTH`): `StackDetail` measures itself
(`useContainerBand`), and below it the switcher draws two segments and a linked or remembered
Timeline draws as the Table (`effectiveStackView`), the stored choice kept for when there is room.

- **The read is `GET …/cue-stacks/{sid}/timeline`** (`useCueStackTimelineQuery`, tag
  `CueStackTimeline`), which rides the stack list's, the cue list's, each cue's, the Looks', the
  palettes' and the scene's tags rather than a frame of its own. **The grid is derived on the client**
  (P6) by `lib/stackTimeline/`, pure and tested on its own: `columns` (a column per Look, sub *Look*,
  since a Look lands whole; per palette and target — none is its own heads — per FX by type,
  attribute and target, per
  scenery element; grouped Looks · Palettes · FX · Scenery, each in first use down the stack),
  `cells` (rank, glyphs), `runs` (start, end, ↺ where two run keys meet, ◆ where a run's settings
  change, the scenery tracked line, and what passes a marker) and `filter`. Layer cells join
  whenever both are filled; FX cells only on one shared `runUuid`, and never where either has a
  Runs count, which a GO fires afresh; scenery never.
- **`StackTimeline`** (`components/runner/`) is the grid on `useSheet` / `SheetTable`, with the
  Table's rows, row drag, lock, arming click and `CueEditorPane`: the sticky cue column (pip, number,
  name, shared and *Edited* chips, the live row's fade bar), Fade and Follow, then 84 px columns (92
  for FX and scenery) under the kit's header bands; a palette's cells and header carry its
  colour where it has one (`paletteRowsSwatch` over a generic palette's rows, the busk pad's rule). Markers are divider rows whose cells a run
  and a tracked line pass through. A cell selects through its `onBeginEdit`; editing is the next
  bullet. With the pane on, one selected cell shows its cue at the column's tab (Looks for Look and
  palette columns, FX, Scenery); a range over several cues shows the blank state (C2). A layer cell's rank is the desk's
  `rank`, which counts a layer whose source is gone, so the ranks above it do not shift.
- **Row B** is the filters, per window (`sessionStorage timeline.filters`): the kind chips with
  their counts under the other filters (`kindCounts`), the family segment (scenery always
  kept), the Scenery chip's ▾ (`TimelineSceneryPick`), which ticks each element the stack moves in
  or out — stored as the hidden uuids (`hiddenScenery`), so an element the stack starts to move
  shows, and *Show all* here keeps another stack's — the chip then counting *2/3*, and *Selection only*, the columns whose `heads` meet the desk selection expanded through the
  cached group list and met by the desk's parent↔element rule both ways, a selected fixture reaching its
  elements and a selected element a layer on its parent (scenery kept too). **Column order** is per window and never stored on the desk
  (C7): `sessionStorage timeline.columns.<stackId>`, moved within its kind by the header's ‹ ›,
  which trades places with the neighbour on screen, so a column the filters hide stays put.
  A Timeline is drawn only once the stack is measured, so a narrow desk never mounts it.
- **The kit's three changes**, generic: `SheetColumn.dividerCell` draws a divider as a row of cells
  where any column gives one (other sheets keep the full-width rule); a selected cell whose
  neighbour directly above or below in its column is selected, and draws a cell there, drops that
  edge (`cellSelectionClass`'s `CellSelectionJoin`, on every `SheetTable`), so a range is one ring and
  a blank cell the marquee swept up still closes it; `SheetColumn.group` draws a band over
  consecutive columns, its rule down the first. The programmer's `FixturesTable` keeps whole rings:
  whether its neighbour is blank depends on the neighbour's live values.
  `SheetColumn.header` replaces the uppercase label where a column needs two lines.
- **Editing** (§4.4, sessions 4 and 5; `TimelineEditing.tsx`, the pure op builders in
  `lib/stackTimeline/edits.ts` and `fxRuns.ts`). **Every gesture is one request** to `POST
  …/timeline/edit` (`useEditCueStackTimelineMutation`), sent through `useTimelineEdits`: nothing
  reaches the stage, and the answer's `before` is pushed as one Undo step — per stack, kept while the
  window lives (module-level, so a view switch keeps it), last first — which ⌘Z and the toast's
  *Undo* send back as `restore` ops (P5) — an Undo the desk refuses as invalid is dropped rather than
  left on top, and `StackDetail` keys the Timeline by stack, so one stack's toast cannot undo another's. Every gesture toasts what it did, in one id per stack, the
  press's knock-on named from the answer's `released` (*Amber on LX1 Orbits in Q5. Lavender released
  there.*). The gestures:
  - **One cell.** A click selects; ⌘, ⇧ and ⌘⇧ (or a tap under *Add*) select as on every sheet
    (§Sheet kit), through `useSheet`'s `clickCell` and never the toggle below, so a ⌘-click on the one
    selected cell deselects it and writes nothing; a plain second click on the one selected cell
    toggles it — a Look or
    palette cell on is `layerOn` with `settingsFrom` the nearest filled cell above in its column, else
    below (D6, `nearestFilled`), off is `layerOff` — and a scenery cell's second click opens
    `TimelineSceneryPopover` instead, through `EditorSurface`: `SceneryControl` (`commit="release"`, so one write per gesture),
    the transition, and *Clear change*. ⏎ toggles the selection (all filled → off, else the empty ones
    on) and ⌫ turns it off, through the kit's new `useSheet` options `onActivate` and `onClearCells`
    (a surface-owned ⏎ and ⌫ — one request over every column rather than each column's `clear`; a
    typed character is then left unclaimed, so the transport's `L` still works). ⌘D and ⌘Z are
    `useTimelineCommandKeys`, standing aside for a key region (the pane), a guarded target or an open
    editor.
  - **An FX cell** (session 5, D7, D8). On is `fxOn`, a copy of the nearest filled cell's row (above,
    else below) that takes its run key, so a cell filled from a neighbour — a second click, *On*,
    *Fill ↓*, a drag of the run's end — **joins its run**; off is `fxOff`, naming the row by uuid
    (`offOp` takes the cell for it). A filled cell's second click opens **`TimelineFxRunPopover`**
    instead of taking it off (⌫ does that): the run cue by cue (`fxRunAt`, through the grid's own
    run marks, so across markers), each cue's settings or *same* in the pad face's words
    (`fxCycleWords`, `fxDynamicsParts`), ◆ with what changed, then *Break run at Qn* (`breakRunVerb`,
    offered on a cell joined above), *Join run above* (`joinRunVerb`, offered on a cell not joined
    above whose cue entry directly above fills the column, neither with a Runs count, which a GO fires
    afresh) — each disabled with its reason otherwise, both sending the cell's `entryId` — and *Edit
    Qn's FX…*, which turns the pane to its FX tab. The popover closes when its cell empties or its
    column is filtered away. The ↺ and ◆ are `runs()`'s, so they move with the refetch an edit brings.
  - **A run's end** carries a handle (on hover, never while locked). Dragging it down fills the cells
    it crosses with the dragged cell's settings, up empties them, the one under the pointer kept
    (`dragPlan`); ghost cells and a scope chip by the pointer show the result before the release,
    which is one request. The pointer's row is read with `elementFromPoint`, markers skipped.
  - **The bar's verbs** (`TimelineVerbs`): *On* (empty cells filled, each from its nearest filled
    cell), *Off*, *Fill ↓* (each column's top row copied down, ⌘D), *Replace with…* and *Settings…*,
    then *Update stage…* when the live row is selected and edited since GO, then the **cue action
    set** over the selected cues (§"The cue action set": *Edit in programmer*, *Load into
    programmer*, *Record…*, *Duplicate*, … — a copy reopens on the Timeline), then *Deselect*. Its
    rungs are its own (`FOLDS.timeline`), and below the last the verbs' words fold with it
    (`TIMELINE_LAST_FOLD_CLASS`, `timelineFolds.ts`), since the Timeline draws from 1100 px. A
    filter that hides a column lets go of its selected cells, which would read as empty. **A verb
    that would change nothing is disabled** — its op list is empty. *Replace with…*
    (`TimelineReplacePopover`) takes a selection in one Look or palette column: the replacement
    record and its target (the desk selection's first by default), *Keep each layer's rank and
    settings*, and a readout of which cues swap and which already hold it (`replacePlan`). *Settings…*
    (`TimelineSettingsPopover`) is the Looks tab's fields over every selected layer, each change one
    request, a field the layers disagree on reading *mixed* — the mask through the shared
    `MaskToggles` and `useMaskDraft`, and over differing masks the first mask chosen written to every
    layer; a number commits on Enter or blur, never
    per keystroke; it names a layer covering several targets and offers *Open Look* / *Open palette*.
  - **+ Column** (`TimelineAddColumn`) searches the Looks and palettes; a palette takes the desk
    selection's first target unless another is picked, and a Look none (`edits.ts`' `cellTargetOf`
    sends none for a Look's cell whatever a stored column names) — a generic palette is
    offered no *own heads*, here or in *Replace with…*, since the desk refuses it on none. The column
    lives in this window (`sessionStorage timeline.extra.<stackId>`, `withExtraColumns`, which gives it
    its target's heads and the window's column order) until a cell is filled, when the derived one
    replaces it and the window lets go.
  - **Locked**, every write asks to unlock (`UnlockShowDialog`, the cue sheet's `refuseVerb` /
    `onRefused` pattern, ⏎ alone claimed from the keyboard; ⌘Z and the toast's *Undo* too) or is disabled with the reason where the
    lock is not the operator's; the selection and filters still work and no handle is drawn.
  `StackTimeline.editing.test.tsx` asserts one request per gesture and the locked refusals,
  `edits.test.ts` the op lists, `useSheet.test.ts` the two kit options.

#### The Show position

**The way back to Show returns to the stack and the card this window last had open there**
(venue-visit plan S3, `lib/showPosition.ts`). The position is `ShowPage`'s to write — the drilled
stack and the `?cue=` it holds, per project, whenever they settle; on the stack list the project's
entry is dropped — and it is **per window**, in `sessionStorage` under `show.position`, like every
other fact about how this window is being worked (§"Windows, full screen and the hand"). Only what
exists is written: a stack the list does not hold is about to be redirected away from, and a
`?cue=` that names no cue in its stack opens no card, so neither is handed back. A cue the list has
not caught up with yet — a duplicate's, just created — is written once the refetch brings it.

**Three doors read it, and they are the ones that mean "back to Show"**: the view switcher's Show
pill (`ViewSwitcher`, through `useShowViewPath`, so a link drawn before the position moved still
follows it), and the sidebar's Show entry with its ⌘K twin (`navigation.ts`'s `path`, which both
call at the press, through `showViewPath`). **The rest deliberately do not**, because each names a
place of its own: the Prompt Book's *Edit cue*, the cue table's read-outs and a busk cue pad's
*View* (its first entry's card by `?cue=&entry=`, or the Cues table filtered to a loose cue), ⌘K's *New Cue Stack* (the stack list), and the doors that land on
bare `/show` and so on the live stack while one runs — a cue slot's *View*, and `windows.show`,
another screen putting this one on the show — and the legacy `/program*` and `/run*` redirects, whose targets are frozen
(§"What may live in `routes/`") and still carry their search string. Each of them writes the
position when it lands, so the next return goes where that door left the window.

**What a remembered position meets on arrival is the Show view's ordinary handling**: a stack that
has gone redirects to `/show` (the stale-stack effect) and is then forgotten; a cue that has gone
opens no card and is forgotten; another project's entry is never read, being keyed by project, and
a window on a project that is not current draws `NotOnRig` (§Navigation Registry) and writes nothing.
`ShowPage.cueActions.test.tsx` drives the round trip through the real switcher, and the stale
cases with the stored position seeded.

#### Scenery in the cue table and the Prompt Book

Scenery-programmer plan session 5 (D13, D14); the mechanism is `docs/show-mode-engineering.md`
§"Scenery in the cue table and the Prompt Book".

- **The stack list carries every cue's own changes** (`CueStackCueEntry.scenery`, optional on the
  client), so the table and the book read every cue at once; what a cue only *tracks* stays a
  per-cue read (`CueDetails.trackedScenery`). Every cue scenery write announces the stack list too,
  as does an element delete that swept scenery, and the client's scenery-writing mutations invalidate
  `CueStackList`. `cueSceneryLines` /
  `cueChangeLine` (`lib/scenery.ts`) are the one reader of a cue's lines — `House tabs → closed · 4 s`,
  names from the scene, an element the scene has lost left out — so the column, the glyph's hover and
  the rail cards cannot word a change three ways.
- **One editor in place: `CueSceneryEditor`** (`components/scenery/`), the cue editor's Scenery
  tab body — `SceneryEditor` with times — inside `EditorSurface` at the button that opened it (wide in the side
  sheet), with the cue's tracked state as its header. The cue table's Scenery cell and an open rail
  card's *Scenery…* both open it; every host opens it unlocked only and closes it when a GO re-locks.
- **The Show stack detail's header carries *Stack settings…*** unlocked, the stack form (and its set)
  mounted in place through `OwnerEditor`, as the overview row's menu offers it.
- **Prompt Book: a `Blinds` glyph** beside the margin marker of a cue that moves something — its
  hover lists the changes, its tap opens the cue's card in the rail (the drawer slid in on narrow) —
  the cue's lines on its rail card, both faces; **the *On GO* line** at the top of the rail
  (`useOnGoScenery`: the Next GO preview's scenery against live, piece by piece, *appears* / *hides*
  for a visibility flip, nothing drawn when nothing moves); and below 1040px a `Blinds` dot on the
  toolbar's *Cues* toggle while the line is non-empty. The hook keeps the shared preview query fresh
  while the book is mounted — the Stage view's Next GO source alone does not (stage-vis doc).
  `FU-SCENERY-FLY-DEPT` is a fly department of its own (FQ markers, its own lane), not built.

#### The Prompt Book's follow scroll

The book scrolls to the live cue's anchor on every new live **sequence** — a follower firing has no
line, so it moves nothing — and once when its sheets are laid out, to the live cue (or a `?cue=`
link, which the follow then leaves alone). `scrollToEntry` keeps one identity for the session, so a
book refetch never re-runs it. **It waits while the operator works in the script** (venue-rig-fixes
plan D29): `ScriptViewer` reports `onHoldChange(true)` while a pointer is down in it, a text
selection is live in it or the selection's toolbar is open, unlocked, and `useHeldFollow`
(`lib/promptBook/`) holds the scroll until the hold ends, then scrolls once to wherever the show then
is; a hold with no GO behind it scrolls nothing, and nor does one the viewer drops by unmounting
(`onHoldChange(false, true)`). A scroll under a selection carried it out of view, past the render
window (where its page unmounted and the DOM selection went with it), or under a drag, running the
selection on to whatever came under the pointer. The first and last pages of a selection also stay
mounted whatever the render window says — the pages between it may unmount, since only a boundary's
text layer holds the selection. Locked, nothing holds, and the book follows as before. The rail's
clicks, a desync warning's, *Jump to live* and a `?cue=` link are the operator's own: they never wait,
and each cancels a follow still waiting, so the book stays where the operator took it.

**Timed FX**: Layers and ad-hoc FX can have optional timing (delayMs, intervalMs, randomWindowMs) to fire after a delay or on a recurring interval. Immediate (no timing) is the default. A timed layer re-cooks the whole cue when it fires rather than appending its rows, so an in-flight crossfade weight survives.

**Script hooks** (triggers) automate FX_APPLICATION script execution on cue lifecycle events:
- **ACTIVATION** / **DEACTIVATION** — fire when the cue starts/stops
- **DELAYED** — fire after a configurable delay
- **RECURRING** — fire at an interval with optional randomisation for organic timing

FX definitions have a `timingSource` field (`BEAT` or `WALL_CLOCK`) controlling whether FX sync to BPM or run on a fixed 50Hz wall-clock timer.
