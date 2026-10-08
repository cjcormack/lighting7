# Fixture and FX sheets: one sheet that says who drives each value, clears it, takes typed values and edits effects live

> **Document status: COMPLETE, 2026-10-08 — all six sessions shipped (`1d4a7660`, `50691dd`, `eff39ab`, `7ee8347`, `2664955`, `887edc2`).** Chris approved the design on 2026-10-07 and called its six open calls the same day
> (§10). Session 1 (the desk's half), session 2 (the sheet), session 3 (the tray and the live
> editor), session 4 (heads and groups), session 5 (the Busk and Programmer views) and session 6
> (the remaining hosts: the phone, the cards' corner) are done. The plan and its design record
> moved to `completed/` with session 6; §8's follow-ups are in `../followups.md` and §9's rig checks
> in `../manual-validation.md` (`FU-MANUAL-FIXTURE-FX-SHEETS`).
>
> - Design record: [`fixture-fx-sheets-design/INDEX.md`](fixture-fx-sheets-design/INDEX.md).
> - Boards: <https://claude.ai/artifact/AnEyZHYphuSdmWcSArXigv>. Private to Chris and a
>   convenience; the checked-in `.dc.html` files are the authority.
>
> This document is the engineering half. Where it and the boards disagree, this plan wins on
> behaviour and the boards win on layout and copy.

## 1. Context

Chris's brief (2026-10-07): survey the fixture and FX sheets everywhere they appear and propose
better designs. He named six problems:

1. You can't see what the layers are doing. A position effect added after a manual position seems
   to do nothing.
2. There is no way to clear a value.
3. An FX change has to be committed before you can see it.
4. You can't type a value for a slider.
5. The sheets should borrow from the programmer and busk views.
6. In the Stage view especially, the FX section is lost at the end of the properties.

He later added the Busk and Programmer views wherever the same answers apply.

The findings that shape the plan, read from `main` at `b42aff4`:

- **One body under two headers, in five hosts.**
  - The body is `FixtureContent` and the header is `FixtureDetailView`.
  - It appears in the 512px pop-up (`FixtureDetailModal`), the Stage view's 380px docked panel
    (`StageFixtureControlPanel`, shown only at ≥ 640px), and one card per fixture on the cards
    page.
  - Groups get a separate visualiser set. `GroupDetailModal` has no FX and no park, and writes one
    entry per member, sending sliders as raw channels. Only `GroupCard` writes group entries.
- **The sheet is already a programmer surface and doesn't say so.**
  - Every visualiser writes the programmer (owner `web`).
  - The sheet reads only the output (`useChannelSource`). Park's lock is the only source it shows.
  - There is no clear and no typed field. Only the Channels view takes a number, as a raw channel.
- **Pan and tilt write raw channels.** The sheet's position sliders go through `updateChannel`'s
  shim into the programmer's raw-channel side track.
  - That track holds no effect back.
  - `ProvenanceService.compute` nonetheless counts it as a programmer key, so a pan from the
    Channels tab reads *Programmer* while an effect is what is on stage.
  - The grid writes a `position` entry, which does hold effects back.
- **Why the Circle did nothing (issue 1).**
  - `FxSection` opens `AddEditFxSheet` without `programmerOwned`, so the effect is created at
    priority 0.
  - `FxEngine.isSuppressed` skips every non-programmer-band effect on a (fixture, property) the
    programmer holds. Nothing on screen says so.
  - Even unsuppressed, Circle's default Override replaces pan/tilt around its own centre
    (128/128). Only Additive (`base + effect − 128`) orbits what is underneath.
- **The same bug is in the busk view.** An effect template stores `blendMode: 'OVERRIDE'`
  (`TemplateEditor.tsx:233`), so a Circle pad pressed after a position pad circles 128/128.
- **Every FX form is a draft.**
  - `AddEditFxSheet` and `ActiveEffectSheet` hold local state until Apply or Update.
  - `PUT /fx/{id}` → `FxEngine.updateEffect` already swaps parameters while keeping the id and
    phase.
  - The live pattern on the desk is `useLivePush`.
- **The winner per property is already on the wire; what sits under it is not.**
  - `provenanceState` plus `getKeyState` name the winner, and the grid paints from them.
  - A held-back effect never appears in provenance.
  - The value underneath is resolved only for Update (`ProvenanceService.underlyingSources`).
- **A clear exists: `programmer.clearEntry`.** It releases every owner's slot on one property
  except layer slots. The grid's ⌫ uses it; nothing on the sheet does.
- **A pad's running effect survives a re-cook.** `ProgrammerLayerStack.syncEffects` keys a layer's
  effect by (layer, effect, target) and leaves an existing instance alone, so an instance edited in
  place keeps its edit until the layer goes.
  - An edit to the template's effect does not reach a layer already applied: `recookIfReferences`
    cooks `withEffects = false`. That is `FU-TMPL-FX-EDIT-NO-RETIME`.
- **The phone has no fixture panel in the Stage view.**

## 2. Decisions

The D-numbers match the boards' Model board.

| # | Decision |
|---|---|
| D1 | **One sheet body for a fixture and a group, in every host:** the pop-up, the Stage panel, a phone's bottom sheet, the cards page and the group sheet. |
| D2 | **No Edit / Done.** The sheet is live while the desk is connected. It is read-only only offline, and per row on a parked property. `EditModeContext` goes. |
| D3 | **Rows are grouped by family:** Intensity · Colour · Position · Beam · Controls (`lib/attributeFamily.ts`'s vocabulary plus Controls). |
| D4 | **A source mark on every row.** A 3px edge and a chip in the programmer grid's colours (`ownership.ts`), from `provenanceState` + `getKeyState`. The chip names the cue, the Look layer, or the effect with its division and master. An amber dot marks a row where something underneath is held back. |
| D5 | **The chip opens the property's stack** through `EditorSurface`: top wins, the winner ticked, held-back layers named. Fed by W1. |
| D6 | **× on a row clears one value** (`programmer.clearEntry` at the programmer fade). It is drawn only while the programmer holds that property. **Release n** in the header clears every value and local effect on the fixture (W2). |
| D7 | **Editor-kit controls.** Each row has an `EditorField` with its unit (% for a level, degrees for an annotated pan/tilt, the step for a setting). `ColourEditor` is docked in the colour row, the position pad is in degrees, and settings use the type-ahead. A row's full editor opens **inline**, one at a time (call 5). |
| D8 | **Pan and tilt write `programmer.setPosition`**, resolved per head from degrees as the position cell does. Never raw channels. |
| D9 | **The FX tray is pinned to the sheet's foot.** It has four states: empty, folded (chips), open (the rail's two-line rows), and adding (the picker). |
| D10 | **A sheet's effect is a programmer effect** (`programmerOwned: true`), and so is ⌘K's *Apply FX*. |
| D11 | **Effects are edited live.** There is no Apply. Changes go through `useLivePush` into W3's `updateFx`, keeping the phase. **Revert** restores the settings from when the editor opened; **Done** returns to the list; × stops the effect. |
| D12 | **Movement effects ask *Centre: Around current position \| Absolute*.** Around is Additive with the centre pinned at 128 and hidden; Absolute is Override with the centre shown. Movement starts Around. |
| D12a | **Level effects ask *Over the level underneath: Replace it \| Within it*, default Replace** (call 2). Replace is Override; Within is Multiply. |
| D13 | **A strip under the header picks heads or members.** *All* writes the fixture's all-heads properties, or one group entry. A subset writes each head or member (members carry the group as `sourceGroup`). The pick belongs to the sheet and resets when the sheet opens on another fixture (call 4). |
| D14 | **The Stage view on a phone gets the sheet** as a bottom sheet. *Aim at point* moves to an *Aim…* popover on the Position row. Focus stays a Stage-only tab. |
| D15 | **One effect editor** (`FxEditor`) serves the tray, the busk Effects tab, the rail's *Edit…* and `FxSheet`'s chips. `ActiveEffectSheet` and `AddEditFxSheet` retire. |
| D16 | **Effect templates ask D12's Centre question** in `TemplateEditor` and the template sheet's Value cell. New movement templates start Around; a pad face says *around*. Stored templates are not migrated (call 6). |
| D17 | **The programmer rail edits effects live:** *Edit…* and `FxSheet`'s chips open `FxEditor` beside the rail. |
| D18 | **Release over a selection** on the programmer's row C (a row selection) and the busk rig row: W2 once per target. |
| D19 | **The grid says *held back*:** an amber corner dot on the cell. The cell editor's label line carries the source chip, which opens the stack (D5). |
| D20 | **A busk Effects tab**, the side sheet's fifth. It lists what runs on the selection and edits it live. A pad's effect is edited **as the running instance only**, is marked *edited* while it differs from its template, and offers **Update template** and **Reset to template** (W5). |
| D21 | **A rig tile's menu has *Fixture sheet…***, which opens D1's sheet (or the group sheet) over the busk view. |

The plan's own decisions:

- **P1 — Six sessions, one PR each**, in §5's order (CLAUDE.md §"Git workflow").
  - Session 1 (the desk) comes first.
  - Session 2 needs 1.
  - Session 3 needs 1 and 2.
  - Session 4 needs 2.
  - Session 5 needs 1 and 3.
  - Session 6 needs 2.
- **P2 — No schema change, no `formatVersion` bump.**
  - Every new piece is a runtime frame or a client concern.
  - *Around* and *Within* are spellings of existing fields: `blendMode` (`ADDITIVE`, `MULTIPLY`)
    plus the stored centre parameters at 128. An older desk reads them unchanged.
- **P3 — Additive on the wire.**
  - The new frames (`programmer.keyStack`, `programmer.clearTarget`, `updateFx`, `fxError`) and the
    new route (`POST /fx/{id}/reset`) are new names.
  - Nothing existing changes shape, so session 1 leaves today's client drawing exactly as before.
- **P4 — `updateFx` is the FX family's spelling** of the boards' `fx.update`, beside `pauseFx`
  and `removeFx` (`plugins/FxSocket.kt`). Its refusal is `fxError`.

## 3. The model

### 3.1 Portable

Nothing new. A template's centre and blend are its existing effect fields (P2).

### 3.2 Runtime and the desk

- **`programmer.keyStack {targetType, targetKey, propertyName}` (W1).** It is answered to the
  asking socket only, never broadcast. The reply lists the layers under one property, top first:
  - **Park**: present with its value, or absent.
  - **Programmer-band effects** on the key: name, division, master, `layerId` / `templateId`.
  - **Programmer slots**: each owner with its value and age. A layer slot names its layer's source.
  - **Other effects** on the key, each with **`heldBack`**. This is `FxEngine.isSuppressed`'s
    answer, so both read one suppression snapshot: suppression is factored into a pure function the
    tick and this read share, never recomputed by a second rule.
  - **The cue contributor**: cue, stack, Look layer and value, from
    `ProvenanceService.underlyingSources` against one Layer-4 snapshot.
  - **Base**: `LayerResolver.fallbackFor`'s baseline.

  Each entry carries `onStage`. A group target answers per member: one stack per member, grouped
  by source on the client.
- **`programmer.clearTarget {targetType, targetKey, fadeMs}` (W2).** One pass, one republish.
  - It clears every entry on the fixture (its elements included) or the group: every owner's slot
    but layer slots, as `clearEntry` does.
  - It removes the local effects on it: `programmerOwned` with no Look, template, layer or cue —
    `cellEffects.isLocalEffect`'s rule, moved to the desk.
  - A group-targeted local effect is removed from a group target only. On a member it is left
    running and named (`partial`), as `useClearCellEffects` does today.
  - It replies `programmer.targetCleared {values, effects, partial[]}`.
  - Locate's bookkeeping is pruned as `clearEntry` prunes it.
- **`updateFx {effectId, …UpdateEffectRequest}` (W3)**, on `FxSocket`.
  - It calls the same `FxEngine.updateEffect` as `PUT /fx/{id}`; the request parsing is factored so
    the two cannot drift, including the strict `EffectSpecCoercion`.
  - It answers `fxChanged(UPDATED)` as pause does, or `fxError {effectId, code, message}` for an
    unknown effect or a refused field.
  - It is gated exactly as `pauseFx` and `removeFx` are. It is an operator gesture on both roles,
    so `FU-AUTH-WS-PER-MESSAGE`'s trigger (an *admin-only* operation gaining a WS command) does not
    fire.
- **`POST /fx/{id}/reset` (W5).** For an instance a template layer spawned, it re-applies the
  template's current effect to the instance through `updateEffect`, keeping the id and phase. It is
  refused for an instance no template spawned (`FX_NOT_FROM_TEMPLATE`).
- **The raw-channel side track and provenance (W4).**
  - `ProvenanceService.compute` stops counting a raw-channel slot as a programmer key wherever the
    engine would not hold an effect back for it.
  - The two read one predicate, `ProgrammerStore.activePropertiesByFixture`, extended to answer
    "holds" and "suppresses" from the same entries.

### 3.3 The client

- **`components/fixtureSheet/`** (new):
  - `FixtureSheet` is the body. It takes a target (fixture or group) and a `host` (`popup`,
    `stage`, `phone`, `card`).
  - The parts: `SheetHeader`, `ScopeLine`, `PropertyRow`, `SourceChip` + `useRowSource`,
    `LayerStack`, `HeadStrip`, `FxTray`, `FxEditor`, `FxPicker`, `useRelease`.
- **`useRowSource`** joins `getKeyState` with the active-effect list. It answers the winner, its
  label and the held-back dot.
  - The dot's rule: an effect on a held (fixture, property) that is not programmer-band is held
    back. That is `FxSheet.isSuppressed`'s rule, moved to `lib/heldBack.ts` and shared with D19's
    grid dot.
- **`FxEditor`** is `EffectParameterForm`'s parameter inputs rebuilt on the editor kit.
  - Speed is a segment, with the master as a chip.
  - D12 / D12a are segments.
  - Shape parameters sit behind a disclosure.
  - Sizes are in degrees where both axes carry a travel range; elsewhere bytes.
  - It writes through `useLivePush` (50 ms floor, the release always lands) into `updateFx`.
  - Revert sends the snapshot taken when the editor opened, in one write.
  - For a template-spawned instance, *edited* compares the instance with the template's effect (the
    template list's summary DTO). The footer is then **Update template** (`PUT` the template's
    effect from the instance) and **Reset to template** (W5) in place of Revert.
- **Writes** use the existing hooks:
  - a fixture or a head: `programmer.set` / `setColour` / `setPosition`;
  - a group's *All*: `useGroupPropertyValues` with `groupName`;
  - a subset of members: per member with `sourceGroup`.
  - The group sheet's raw-channel slider path goes.

## 4. UX

- **Width is the only ladder.** It is a container query on the sheet, never the viewport, so a
  pop-up and a panel of one width draw the same.
  - At 512px every control has its word.
  - At ≤ 400px Release folds to its glyph (count on its title) and the scope line drops *· fade*.
  - On a phone (the `useEditorForm` bottom-sheet form) rows take finger sizes: a 36px field, a 32px
    ×, 32px tray chips.
- **The tray:**
  - It is `flex: 0 0 auto` at the foot of a column whose body scrolls.
  - Open, it takes up to half the sheet's height and scrolls itself.
  - The folded chips overflow to *+n*.
- **The phone (D14)** is a bottom sheet with three heights: header and tray, half, full. It has a
  grabber, keeps the tray above the home indicator, and gives back the keyboard's bite as
  `EditorSurface` does. A landscape phone gets the right-hand sheet (`useEditorForm`'s side form).
- **Hosts:**
  - The pop-up keeps its 512px Radix sheet. The overview panel and Channels open it as today.
  - The Stage panel keeps 380px. Its foot is the tray's alone, and *Aim…* opens a popover from the
    Position row with the same `StageAimControls` body.
  - On the cards page each card's foot is the tray. The page keeps its Properties / Channels
    toggle; the per-card pencil goes and a corner button opens the pop-up.
  - The group sheet replaces `GroupDetailModal`'s body. `GroupCard` mounts the card host.
- **Busk (D20, D21):**
  - The Effects tab joins `LIVE_SHEET_TABS` between Spread and Show. Below 400px of sheet it folds
    to a glyph by `tabWordClass`'s rule.
  - It appears in the docked sheet and in the overlay form, where it sits beside Colour · Spread ·
    Show.
  - *Fixture sheet…* opens through `EditorSurface`'s side or bottom form over the busk view.
  - **Release** on the rig row sits after the verbs and folds with them (`VERB_WORD_CLASS`). The
    band's ladder rungs are re-measured in session 5.
- **Programmer (D18, D19):**
  - **Release n** joins row C's verbs over a row selection, after Spread.
  - The dot sits in the cell's top-right corner, inside the ownership ring, and never moves the
    cell's text.
  - The source chip sits on `EditorLabelLine`'s right side in the popover form, and as a row under
    the title in both sheet forms.
  - In Output scope a click still jumps to the owner (unchanged).

## 5. Implementation — six sessions

Each session is one PR. Every session ends with:

- `./gradlew test` green where anything outside `frontend/` changed;
- `npm run check` green where anything inside it changed;
- the engineering-doc paragraphs written;
- its done-marker here: a one-line row with the session's commit SHA, added on the branch before
  its PR merges.

### ~~Session 1 — the desk's half (backend + client API)~~ — done, `1d4a7660`

- **W1 `programmer.keyStack`.** The suppression rule is factored out of `FxEngine.isSuppressed` into
  one function that the tick and the read share.
- **W2 `programmer.clearTarget`** and its reply. `isLocalEffect`'s rule moves to the desk.
- **W3 `updateFx` / `fxError`** on `FxSocket`, sharing `PUT /fx/{id}`'s request parsing.
- **W4.** Provenance and suppression read one predicate.
- **W5 `POST /fx/{id}/reset`.**
- **Checks to confirm, and pin with tests, before session 5 relies on them:**
  - an instance edited through `updateFx` survives a re-cook of its layer (any `patch`, `move` or
    unrelated `add`);
  - what a template effect `PUT` does to an instance already applied (`FU-TMPL-FX-EDIT-NO-RETIME`
    says nothing until re-press).

  If a re-cook respawns an edited instance, make `syncEffects` match on the instance's spawn key
  rather than its current parameters, and record the amendment here.
- **Client:**
  - `api/programmerWsApi.ts` gains `keyStack` (request/reply, promise-shaped) and `clearTarget`.
  - `api/fxApi.ts` gains `updateFx` and the `fxError` stream.
  - `store/fixtureFx.ts` gains the reset mutation.
  - No UI.
- **Tests:**
  - `keyStack`:
    - a held position under a band Circle;
    - a cue Pulse held back by a programmer dimmer;
    - a parked key;
    - a group target per member;
    - Blind empties the held-back set.
  - `clearTarget`:
    - values;
    - local effects;
    - a group effect left running on a member, named;
    - layer slots untouched;
    - the fade.
  - `updateFx`:
    - phase kept;
    - an unknown id → `fxError`;
    - a refused blend string.
  - W4: a Channels-tab pan under a priority-0 Circle reads EFFECT.
  - W5: phase kept; refused for a non-template instance.
- **Docs:**
  - `docs/websocket-engineering.md` (the four frames);
  - `docs/fx-engineering.md` §"Programmer suppression and the priority band" (one predicate,
    `updateFx`, reset);
  - `docs/lighting-composition-model.md` (the stack read);
  - root `CLAUDE.md`'s WebSocket list and FX endpoints.

#### Session 1 amendment

What session 1 found, and where it departed from the bullets above:

- **Check 1 holds; `syncEffects` is unchanged.** It already matches a live instance by the
  `EffectEntry` it was spawned from (`ProgrammerLayerEffectKey`), and `updateEffect` carries that key
  across its swap, so an instance edited through `updateFx` survives a patch, a move and an
  unrelated add (`FxLiveEditRoutesTest`).
- **Check 2: a template-effect `PUT` reaches an applied instance at the stack's next recook, not
  never.** The `PUT` recooks values only and leaves the instance alone, but the next recook *for
  any reason* reads the edited template, whose entry no longer matches the instance's key, and
  respawns it — phase restarted, any instance edit gone. `FU-TMPL-FX-EDIT-NO-RETIME` said "until the
  layer is re-applied" and is corrected. Session 5's *edited* mark should expect it.
- **W5 re-keys.** Reset moves the instance's spawn key to the template's current entry (under the
  stack's effects lock), so a recook after a template edit keeps a reset instance rather than
  respawning it. It covers **programmer** template layers — the pads; a cue's template-layer
  instance is refused with `FX_NOT_FROM_TEMPLATE`, since the next GO respawns it. `FX_TEMPLATE_GONE`
  (409) is a second refusal, for a template that no longer holds an effect.
- **W4 is wider than the sideband.** On a head that declares pan as a property (the Fusion spot),
  `updateChannel` lifts a Channels-tab pan to a `pan` **entry**, not the sideband; it still holds
  nothing back, because a Circle is keyed `position`. So provenance credits an effect that paints
  **every** channel of a programmer-held key under a sibling key, as well as one on a sideband key —
  asking `EffectSuppression` of the key the effect paints. Partial overlap (a UV wave beside an RGBW
  colour entry) keeps the programmer's answer. `keyStack` lists such an effect on the key's stack.
- **`updateFx` and `PUT /fx/{id}` carry the timing source on a type swap** (`newTimingSource`), as
  the add path does; before, a beat effect swapped for a wall-clock one ran on the beat loop. Session
  3's picker swap relies on it.
- **Both request/reply frames carry a client `requestId`**, echoed, and answer an unresolvable
  target with an `error` field rather than `programmer.error`, so a promise is answered rather than
  timed out.
- **Slot age** is a runtime `writtenAtMs` on `ProgrammerStore.Slot` — no schema (P2). A layer slot
  is re-installed by every recook, so its age is since the stack last moved it.

### ~~Session 2 — the sheet (frontend)~~ — done, `50691dd`

- **The body (D1–D3, D7, D8).**
  - `FixtureSheet` with `SheetHeader` (name, model, Values · Channels · Focus, Locate, Park,
    Release) and `ScopeLine` (Local, the programmer fade from `lib/programmerFade.ts`, Blind's amber
    arm, the held count).
  - The family groups, and `PropertyRow` per descriptor kind on the editor kit.
  - Colour and Position open inline; one row is open at a time.
  - Position writes `setPosition` through `clampCommitToResolution`'s degree rule.
- **Marks and the stack (D4, D5).** `useRowSource`, `SourceChip`, `lib/heldBack.ts`, and
  `LayerStack` over `keyStack` in `EditorSurface`, re-asked on `provenanceState` while open.
- **Clearing (D6).** Each row's × → `clearEntry`; Release → `clearTarget`, toasting the reply.
- **The tray shell (D9's first half, issue 6).**
  - `FxTray` pinned at the foot, folded chips and the open list.
  - Its rows edit through today's `ActiveEffectSheet` until session 3.
  - `+ Effect` opens today's `AddEditFxSheet`, now with `programmerOwned: true` (D10 lands here).
- **Hosts:**
  - `FixtureDetailModal` and `StageFixtureControlPanel` mount the sheet.
  - *Aim…* popover on the Position row (D14's half).
  - `FixtureContent`'s properties view, `PropertyVisualizers`, `FxSection`, `EditModeContext` and
    the Edit toggles go. The Channels view moves into the sheet unchanged.
- **Tests:**
  - row order by family;
  - each source kind's chip and edge;
  - the held-back dot;
  - × drawn only while held, and its message;
  - Release's frame;
  - typed values commit on Enter with the unit;
  - Position writes `setPosition`, never `channels.update`;
  - the tray stays mounted outside the scroller;
  - no Edit toggle in any host;
  - read-only offline and on a parked row.
- **Docs:** `frontend/CLAUDE.md` gains §"The fixture sheet"; `docs/stage-vis-engineering.md` gets
  Aim's move.

#### Session 2 amendment

Where session 2 departed from the bullets above, and why:

- **The cards page and `GroupCard` moved a session early, as far as the deletions forced.** Deleting
  `FixtureContent`'s properties view, `FxSection` and `EditModeContext` left the cards page and the
  group card nothing to draw with. So the cards page mounts the sheet's `card` host now (rows and
  tray, the page's Properties / Channels toggle kept, the per-card pencil gone), and `GroupCard`'s
  effects are the sheet's `FxTray` at its foot — so a group card's **+ Effect** is a programmer effect
  too (D10), where `FxSection` added one the programmer's values held back. Session 6 keeps the card's corner pop-up button;
  session 4 keeps the group sheet — `GroupCard` and `GroupDetailModal` still draw the group
  visualisers and their own Edit toggle until then.
- **The Channels cards lost their Edit toggle too** (`EditModeContext` was theirs as well). They are
  live while the desk is connected, as the DMX sheet already was, and a single channel's unpark now
  asks first, as the fixture's Park and Unpark All do.
- **A typed field commits on Enter, a drag as it moves.** The kit's `EditorField` writes per
  keystroke; the sheet wraps it (`SheetField`) so a typed value is one write at the programmer fade.
  A drag writes at no fade — it follows the hand. Setting picks and × take the fade.
- **The heads are an interim section.** Without the head strip (session 4), a multi-head fixture
  shows its all-heads controls through the group visualisers and one disclosure per head of the
  sheet's own rows; `HeadsSection` is what session 4 replaces.
- **The tray keeps the Look picker** `FxSection` carried, beside **+ Effect**, rather than dropping a
  way to press a Look onto one fixture.
- **A group's effect in a fixture's tray keeps pause and stop, and stop asks first** (Chris, in
  session 2's review): it stops the effect on every member. `FxSection` showed these read-only.
- **The colour row's swatch is dimmed by the dimmer, with the UV dot** (Chris, the same review) — the
  old visualiser's reading; the value is the text beside it.

### ~~Session 3 — the FX tray and the live editor (frontend)~~ — done, `eff39ab`

- **`FxPicker` (D9).** Family segment, effect rows. A tap starts the effect with defaults on the
  property the sheet came from; another tap swaps the type through `updateFx` (phase kept).
- **`FxEditor` (D11, D12, D12a, D15).**
  - `useLivePush` → `updateFx`, with Revert, Done and ×.
  - Around / Absolute with the hidden centre; Replace / Within.
  - Degrees for sizes.
  - Distribution and Element mode under Advanced on a group or a fixture with heads.
- **Rewiring (D17):**
  - The rail's *Edit…* and `FxSheet`'s chips open `FxEditor` in `EditorSurface`.
  - ⌘K *Apply FX* opens `FxPicker` + `FxEditor` in a sheet.
  - `ActiveEffectSheet` and `AddEditFxSheet` are deleted.
- **Tests:**
  - a tap starts a programmer-owned effect;
  - a second tap swaps (one `updateFx`, no add);
  - a drag sends at most one frame per 50 ms and the release lands;
  - Revert sends the snapshot;
  - Around sends `ADDITIVE` with centre 128 and hides the centre;
  - a stored `ADDITIVE` + 128 reads back as Around;
  - Within sends `MULTIPLY`;
  - `fxError` is toasted keyed per effect.
- **Docs:**
  - `frontend/CLAUDE.md` §"The fixture sheet" (the tray) and §"Speed Masters" (the editor's
    master chip);
  - `docs/fx-engineering.md` §"Blend Modes" (Around and Within as the UI's names).

#### Session 3 amendment

Where session 3 departed from the bullets above, and why:

- **The tray edits inline; the other hosts use `EditorSurface` or a sheet.** The boards draw the
  tray's editor under its row (Main B, Fx) and the picker in the open tray, so that is where they
  are; the rail's *Edit…* and `FxSheet`'s chips open `FxEditorPopover` (the editor in
  `EditorSurface`), and ⌘K *Apply FX* and the rail's `+ Effect` open `FxAddSheet` — the picker,
  then the editor, whose **Done closes the sheet** with the effect running (a sheet has no list to
  go back to but the picker, which *Effects* returns to).
- **A swap carries the new type's defaults and starting blend.** `updateFx` without parameters
  would hand a Figure 8 the Circle's map; the picker sends the new type's defaults, so a movement
  swap stays *Around*; a swap across timing sources (a beat effect for a wall-clock flicker) lands
  on one cycle, since the number would otherwise change units. A tap that resolves to a **different property** (another family, another
  setting) cannot be a swap — an instance keeps its target — so it stops the auditioned effect and
  starts afresh, and a session still holds one effect. The old sheet's *Target setting* / *Target
  property* pickers survive as the picker's *On* row.
- **The raw blend stays under Advanced for every effect.** The two questions are views of it; a
  stored blend neither answers (`MAX`, an `ADDITIVE` with a centre of its own) presses neither and
  is still reachable there, so nothing the old form reached is out of reach.
- **Shape is a rule** (`paramRole`): curves and switches always, and a ratio when the effect also
  has levels — a Pulse's attack and hold are its shape, a Colour Cycle's fade is the effect.
- **Speed is beats a cycle, ⅛ to 16** (the board's segment), where the old form spoke note values;
  any other division (a triplet's ⅓) is typed in the *Beats a cycle* field beside the read-out. The tray's chips keep the note-value
  label (`effectDetail`) — two vocabularies for one number, left for a later pass.
- **Revert puts a never-assigned master back as master 1's uuid** — `updateFx` has no spelling for
  null. A **rate** master set in the editor cannot be put back to *unscaled* the same way (unscaled
  is not a master); that needs a clear on the wire and is recorded rather than built, and until
  then Revert leaves the chip naming the master the desk still runs, never *Unscaled*.
- **Pause and resume are in the editor's header** beside ×: the rail and `FxSheet` reached them only
  through `ActiveEffectSheet`, and their rows have no other door.
- **Absolute's centre is two slider rows** (Pan, Tilt, in degrees where the head annotates), not
  the board's small pad with the circle drawn on it.
- **The picker always starts on the beat**, the old sheet's default; its *Start on beat* checkbox
  had no live meaning once an effect starts on the tap.
- **A cue's or a Look's effect stays editable in the tray**, as `ActiveEffectSheet` let it be; the
  board's read-only row for one is left to session 5, which owns instance edits (D20).
- **In a focused Look layer, `+ Effect` absorbs when the session ends**, not on the first tap, so the
  Look takes the effect the operator kept at the settings they left it at — once, however the sheet
  goes (closed, or unmounted when the selection it was opened for goes), by the id the create
  answered, after the editor lands any move its floor still held.
- **Deleted with the two sheets:** `EffectCategoryPicker`, `EffectTypePicker` (the wizard's steps)
  and `toEffectContext` / `ActiveEffectContext`. `EffectParameterForm` stays as `TemplateEditor`'s
  draft form until session 5.
- **Found, not fixed:** the sheet's Position row (session 2) takes `resolveCell`'s resolution, which
  for a mover with a real `position` descriptor carries no `panProperty`, so a Robe ColorSpot whose
  pan and tilt annotate travel types **bytes** there while the editor speaks degrees for its sizes
  (D14's rule, read from the axis sliders). The programmer grid's position cell shares the rule.

### ~~Session 4 — heads and groups (frontend)~~ — done, `7ee8347`

- **`HeadStrip` (D13).**
  - *All*, plus pips in live colours from `FixtureAppearanceSource`.
  - The busk pip's run rules: a tap toggles; a mouse drag runs; a held finger runs.
- **Rows over a pick.** Ranges and swatch strips for mixed values, a dashed edge where sources
  differ, and *n of m* on the chip.
- **Writes.** A group's *All* → group entries; a subset → members with `sourceGroup`. Effects
  follow the pick.
- **The group sheet** replaces `GroupDetailModal`'s body (Values · Members). `GroupCard` mounts the
  card host. `ElementsView`, `GroupPropertyVisualizers` and the "Virtual" badges go.
- **Tests:**
  - the pick resets when the target changes;
  - a run selects;
  - *All* on a group sends one group write;
  - a subset sends member writes carrying the group;
  - no raw channel write for a group.
- **Docs:** `frontend/CLAUDE.md`; `docs/groups-engineering.md`'s UI paragraph.

#### Session 4 amendment

Where session 4 departed from the bullets above, and why:

- **The writes do not go through `useGroupPropertyValues`.** `sheetWrites.ts` sends a group's *All*
  as `programmer.set('group', …)` / `setColour` / `setPosition` itself — the frame those hooks sent
  with a `groupName` — because the rows read the members' own descriptors, not the group's. The
  hooks' update half went with the group visualisers, **raw-channel fallback included** (the path
  §3.3 says goes), and so did the position and setting value hooks only the visualisers read,
  `useUpdateFixtureColour` (its raw-channel branch had no caller left) and `categoriseProperties`
  (its last consumer was `GroupCard`). `ElementsView` was already gone before this session.
- **A fixture's *All* writes each head's key.** D13's "the fixture's all-heads properties" has no
  property on the desk to write — `elementGroupProperties` is a client-side grouping, which the
  interim section wrote as one raw channel per head for a slider. Its controls are the heads' rows
  over *All*.
- **A group's rows come from its members' own fixture properties**, intersected by name and kind
  (the desk's aggregation rule). The group descriptor's element-group half — *all heads* of a group
  of multi-head fixtures — is not offered: a group op resolves each member's fixture-level property
  and, on a member whose colour lives on its heads, resolves no channels, so the visualiser offering
  it was already a write that failed. A member's heads are that member's own sheet. A member key
  that names a single head (`GroupMember.tags` allows it; a group loaded from the patch never holds
  one today) is a pip too, resolved by key among the fixtures' elements — defensive, since the desk's
  group op would skip it.
- **A position over a group's *All* is one group entry only when every member lands on the same
  bytes** — a group of one model. Otherwise each member's own entry carries the group, since a degree
  or a pad point lands on different bytes per model and one group entry would hand every member the
  first's. A dimmerless colour's *Dimmer* is always per member (each colour scales differently).
- **A row over a pick is drawn only where every head can take one write.** A setting needs the same
  options, level for level, on every head (the desk's own group-setting rule), and a colour fills an
  emitter it does not say from each head's channel, since the desk reads a missing W / A / UV as 0;
  a group's *All* colour is one group entry only over members with the same emitters.
- **Effects follow the pick as far as one effect can.** *All* starts on the fixture or the group;
  one head or member starts on it alone (the desk takes a head's key as an effect target); a pick
  that is exactly the odd, even or a half of a fixture's heads starts on the fixture with that
  **element filter**; any other subset says why + Effect cannot start — one instance per head would
  make the live editor edit one of them and the tray list twelve. The board's *Rainbow · heads* chip is
  the row's *on Head 3* / *first half heads*. On a group the tray lists what reaches the members (the
  group's effects, the members' own, another group's *via*), not only the group's own, so *All* reads
  like a fixture's tray. The tray's **Look picker**, which neither the plan nor the board placed,
  follows the same rule (Chris's call in session 4's review): the whole target on *All*, the one head
  or member when one is picked, absent for any other pick.
- **The group sheet's header has Park and Release**, which `GroupDetailModal` never had: Park over the
  members' channels (a member that is one head of a bar brings that head's channels), Release as
  `clearTarget('group', …)`. Locate acts on the pick (board note 1); Park and Release stay the whole
  target's. `GroupDetailModal`'s capability badges went with its body; `GroupCard` keeps its header
  and badges, and the group's surface-binding badges are one row above the rows
  (`GroupBoundControlsRow`, a strip on the group counted under its dimmer as the old badge did).
- **The kit's `EditorField` gained `mixed`** — an empty box with the range as its placeholder — for
  the rows' `40–80`; typing still commits one value to every picked head.
- **The pip gesture is one hook**, `hooks/usePipRun.ts`, factored out of `RigTile` unchanged, so the
  strip and the rig tile cannot drift.
- **Session 3's Position-bytes issue was fixed beside this session, not in it** (#87, `17a9e7d`:
  `resolveCell`'s descriptor branch attaches the axis sliders, `property` still choosing one
  `setPosition`). Rows over a pick read the same resolution, so a pick of such movers types degrees
  too, each head's degree resolved to its own byte.

### ~~Session 5 — the Busk and Programmer views (frontend, W5's client)~~ — done, `2664955`

- **D16.**
  - The Centre segment in `TemplateEditor` and the template sheet's Value cell; new movement
    templates start Around.
  - `EffectPadDetail` adds *around*.
  - `TemplateEditor.tsx:233`'s default follows the effect's category.
- **D17's leftovers**, if any remain after session 3.
- **D18.** *Release n* on row C (`CellSelectionActions`' surface verbs) and on the rig row, both
  `clearTarget` per target. The rig row's ladder is re-measured.
- **D19.** The grid's corner dot through `lib/heldBack.ts`; the source chip in `EditorLabelLine`
  opening `LayerStack`.
- **D20.**
  - `components/busking/EffectsSheet.tsx`: the list over the busk selection, `FxEditor`, *edited*,
    Update template and Reset to template.
  - `LIVE_SHEET_TABS`, the fold's glyph, the Screens sheet's Sheet segment, and the MIDI
    `{sheet}` memory all gain it at once (§Focus and the side sheet's rule).
- **D21.** *Fixture sheet…* in the rig tile's menu.
- **Tests:**
  - a new movement template saves `ADDITIVE` + 128;
  - an edited instance is marked and Reset calls W5;
  - Update template PUTs the instance's settings;
  - the Effects tab is in every place `LIVE_SHEET_TABS` reaches;
  - Release on row C sends one frame per selected row;
  - the grid dot appears for a held-back cue effect;
  - the tile menu opens the sheet.
- **Docs:**
  - `frontend/CLAUDE.md` §"Focus and the side sheet" (the fifth tab), §"Looks, templates and layers"
    (Around in templates), §"The programmer's scoped grid" (the dot);
  - `docs/lighting-composition-model.md` (instance edits on a template layer).

#### Session 5 amendment

Where session 5 departed from the bullets above, and why:

- **`EffectParameterForm` is retired, not rebuilt.** It was `TemplateEditor`'s draft form and nothing
  else's, so the live editor's body was split out as `FxEffectFields` (`fx/FxEffectFields.tsx`) and
  mounted over the template's draft (`templateDraftOf` / `withTemplateDraft`); D12's rules moved to a
  store-free `fx/centreMode.ts`, re-exported by `fxEditorModel.ts`, so the pad face can say *around*
  without a store. A template's sizes are **bytes** — there is no head to read degrees from — and its
  rate master can be set back to *Unscaled*, which a draft can hold and a live frame cannot.
- **The template sheet's Value cell asks Centre as an `OptionCell`** on a movement effect template
  (any other effect template still reads out): one PUT of the effect with `withCentreMode` per
  movement template in the batch, a value template in it skipped by name. The route fetches the
  effect library, as it does the masters.
- **Update template is the PUT then W5 on the edited instance.** The PUT alone would leave the
  instance on a stale key and respawn it (phase restarted) at the stack's next recook — session 1's
  amendment — so the reset re-keys it first. Every other running instance of the template reads
  *edited* afterwards (`FU-TMPL-FX-EDIT-NO-RETIME`, amended). *Edited* compares the distribution only
  where the instance says one (a single fixture's DTO leaves it out, which the draft reads `LINEAR`),
  and Update keeps the template's own there rather than writing the guess over it.
- **D17's leftover was the template arm, and the tray's read-only cue row.** The rail's *Edit…* and
  `FxSheet`'s chips already opened `FxEditor` (session 3); they gain *edited*, Update template and
  Reset to template with the editor. The tray now shows a **cue's** effect read-only (*on Q12*, no
  editor, pause or stop), as the Fx board drew; the board's *opens it* is not built.
- **The Effects tab lists programmer effects only** — a pad's and the operator's own, the board's
  "pad effects and your own" — never a cue's, which the tray shows read-only. A pad's row has pause
  and no stop, and its editor no ×: the pad releases it, and stopping the instance alone would leave
  the layer to respawn it. The editor's footer wraps its two template verbs under the note, and each
  onto a line of its own, at the sheet's 320px floor.
- **The busk strip's words fold below 460px of sheet, not 400.** Five worded tabs measure 378px —
  455 of sheet with the mode toggle, the chevron and the gutters — so at 400 the last tab was
  clipped (`BUSK_TAB_WORDS_FROM`; the rail's three tabs keep 400).
- **The rig row's rungs moved to 1100 / 780 / 660 / 615** (from 990 / 730 / 610 / 570) for
  *Release n* (100px worded, 36 iconic, plus a gap each), re-measured in the app with a 60px
  mask-pill stand-in at each rung and one below; and **the verbs' words no longer return under the
  floor** — worded with the prefix folded the verbs line is 618, wider than the floor — so the Cells
  prefix's range runs to the floor. Release over a cell sends `clearTarget` with the head's own key,
  which the desk resolves as an element.
- **Release on row C counts Locate's targets** — a group row is the group, an element row that
  head by its own key — over a row selection or a marquee's rows, on the programmer only, folded on
  the phone arm with Spread. It is live in Local and Output and refused, with the reason, while a
  Look or template layer is focused (Chris's call in session 5's review): there the band says writes
  go to the layer, and a Local wipe would surprise.
- **The Effects tab's stop asks first for a group's effect reached through a member** — the tray's
  rule (session 2) — and its row reads *via <group>*. The Screens sheet's enum segments wrap inside
  their pill where the row is narrower than their values: the Sheet segment holds six, and at a
  375px phone *Show* was clipped.
- **The grid's source chip is drawn where ownership is** — Local; Output's cells take no edit and a
  focused Look layer's draft has no stack (§8). `LayerStack` and `SourceChip` take the cue names and
  the connection as props outside a fixture sheet.
- ***Fixture sheet…* opens through `useEditorForm`'s forms, not `EditorSurface`**: a bottom sheet on
  an upright phone, and on a desk the pop-up's own 512px right-hand sheet — the sheet is the pop-up's
  body and draws its own header, which `EditorSurface`'s side form (352px, its own title row) would
  have doubled. A group tile's item reads *Group sheet…*. In play mode it is the tile's right-click
  or long-press menu.
- **The pad face's *around* comes last** (*Circle · ½ · M1 · around*, the board's order), so a
  narrow pad's line truncates it first; the template sheet's Value column says it in full.

### ~~Session 6 — the remaining hosts (frontend)~~ — done, `887edc2`

- **The phone (D14).** The Stage route's phone arm mounts `FixtureSheet` in the bottom-sheet form
  with three heights. A landscape phone gets the side form.
- **The cards page.** The card host, the tray at the card's foot, the corner pop-up button.
- **Docs and the record:**
  - `docs/stage-vis-engineering.md` (the phone panel);
  - the follow-ups (§8);
  - `manual-validation.md` rows (§9);
  - move this plan and its record to `completed/`.

#### Session 6 amendment

Where session 6 departed from the bullets above, and why:

- **The host is picked by the form, not by `sm`.** `StageFixtureControls` mounts the docked 380px
  panel where `useEditorForm` answers *popover*, and the phone host everywhere else — a bottom sheet
  held upright, the right-hand sheet on a short viewport. So a **landscape phone** (844×390, wider than
  `sm`) now gets the side sheet where it used to get the docked panel beside a 464px canvas, and so
  does a desk window under 500px tall — the cell editor's own rule ("short beats narrow"), and the one
  answer to "is a finger on this" the frontend has.
- **One phone sheet, `PhoneSheet`, for both phone hosts.** The busk view's *Fixture sheet…*
  (session 5's `BuskFixtureSheet`: a fixed 88svh bottom sheet with `host="popup"`, the 512px side
  sheet held landscape) moved onto it: on a phone it is the `phone` host with the three heights,
  finger sizes and the 380px side form, **modal** there because it opens from a menu. The desk's
  512px pop-up form is unchanged.
- **The Stage's phone sheet is not modal.** The canvas behind stays live: a tap on another fixture
  moves the sheet onto it and a tap on empty stage closes it, as the docked panel behaves; an outside
  press is the stage's, not a dismiss, and opening it takes no focus. The peek is what makes that
  usable — the properties fold away (`[data-sheet-body]` hidden) and the stage is in view.
- **The grabber's gestures**, which the board does not spell: a drag follows the finger and settles on
  the nearest height; a drag well below the peek (64px) closes the sheet; a tap or Enter steps it
  taller, round from full to the peek; the arrow keys step it either way. Every open starts at half.
- **The phone drops nothing the Stage panel has.** The Hosts board draws Focus on the phone, and the
  Focus tab lands its beam on the canvas the sheet sits over; *Aim…* opens its popover over the
  sheet, on the live project only, as on the panel. The multi-selection's docked aim panel stays at
  tablet width and up (D14 is one fixture's sheet).
- **Finger sizes go a little past §4's three.** The 36px field types at **16px** — below that iOS
  zooms the page on a field's focus and the sheet slides half off the screen; a setting's box is 36px
  too, the row's editor chevron 32px beside the ×, and the tray's **+ Effect** 32px in a 48px row (the
  board's). `useFingerSized()` reads the host, so `FxEditor`'s fields in the phone's tray take them
  and its popover elsewhere does not.
- **A group card has the corner too**, opening the group sheet (`GroupDetailModal`, which the Groups
  route now mounts): D1's one sheet for a group, and the one place a group has Park, Release and the
  scope line. The corner is `Maximize2`, the board's glyph, last in each card's header actions.
- **The safe-area inset was not seen on glass.** Chromium's iPhone emulation reports
  `env(safe-area-inset-bottom)` as 0, so the review run proved the tray is the sheet's foot, the inset
  is its bottom padding, and the keyboard's bite against an emulated `visualViewport`; the home
  indicator itself is §9 item 5's (`FU-MANUAL-FIXTURE-FX-SHEETS`).

## 6. Migration

None. Stored templates keep Override and read as Absolute (call 6). No table or column changes.

## 7. Explicitly out of scope

- Save as template / Record from the sheet (not chosen at the brief).
- Flipping existing movement templates to Around (call 6).
- A per-parameter range on the wire (`FU-FE-FX-PARAM-RANGE`): the editor keeps today's guessed
  ranges.
- Touring a template's effect edit to every applied layer automatically. Reset is the per-instance
  answer; see `FU-TMPL-FX-EDIT-NO-RETIME`.

## 8. Follow-ups to record

- **`FU-TMPL-FX-EDIT-NO-RETIME` is touched.** Once session 5 lands, *Update template* makes other
  running instances of the same template read *edited* (they hold the old settings), and Reset
  tours each one by hand. Amend its body to say so. It stays a Trigger: the automatic tour is still
  not built.
- **A held-back *count* in the rail's FX strip.** It is not drawn; record it if the dot proves too
  quiet.
- **The stack for a Look layer's own rows in layer scope.** The stack reads the rig; a focused
  layer's draft has no stack. Record if asked.

## 9. Verification

On the rig, after each session that changes what an operator sees:

1. **Session 2.**
   - Set Spot 3's position on the sheet: the row reads *Programmer*.
   - Add a cue with a Pulse on its dimmer and set the dimmer by hand: the row carries the amber
     dot, the stack names the Pulse as held back, and × lets it run in phase.
2. **Session 3.** Set a position, then add a Circle from the tray: it starts on the tap and orbits
   the set position. Drag its pan size: the rig follows with no restart. Revert snaps back.
3. **Session 4.** Pick heads 1–4 of the 12-pixel bar and set a colour: only those four change. On
   *Front wash*'s *All*, Record writes a group row.
4. **Session 5.**
   - Press a Circle pad after a position pad: it orbits.
   - In the busk Effects tab, widen it: *edited*.
   - Press the pad off and on: the template's size returns.
   - Widen again and choose Update template: the next press keeps it.
5. **Session 6.** On an iPhone, tap a fixture in the Stage view: the bottom sheet opens with the
   tray above the home indicator.

## 10. Questions answered

Chris, 2026-10-07. Before drawing:

- Sheet effects are programmer effects.
- Movement orbits the set position.
- FX settings are always live.
- The Edit toggle goes.
- A source mark plus a tap for the stack.
- One sheet for groups.
- A phone bottom sheet.
- Release, the editor-kit controls and a head strip are in.

After the first boards, he carried over to the Busk and Programmer views:

- Around in templates;
- the live editor in the programmer;
- Release over a selection;
- held-back marks and the stack in the grid;
- a busk Effects tab, editing a pad's effect as the running instance with Update template and
  Reset to template;
- *Fixture sheet…* on a rig tile.

The open calls:

| # | Call | Answer |
|---|---|---|
| 1 | How live FX edits reach the desk | A new socket frame, `updateFx`, gated like `pauseFx` / `removeFx`, with an `fxError` refusal. |
| 2 | Replace / Within for a level effect | Offered, defaulting to Replace. |
| 3 | Whether Release confirms | No confirm, like Clear. |
| 4 | Whose pick the strip is | The sheet's own, never the desk selection. |
| 5 | Where a row's colour or position editor opens | Inline, one row at a time. |
| 6 | Existing movement templates | Stay Override, read as Absolute; only new ones start Around. |
