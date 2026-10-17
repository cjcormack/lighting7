# Applying to a selection — one press, whatever you press it from

> **Document status: AGREED, 2026-10-15.** This is design session R3 of
> [`venue-visit-plan.md`](venue-visit-plan.md). It designs one model for putting a palette, a Look or
> an effect onto a selection of fixtures and attributes, replacing the seven mechanisms the
> venue-visit plan's §1 lists. The questions asked and their answers are in §11 and in the design
> record, [`apply-model-design/INDEX.md`](apply-model-design/INDEX.md), beside the boards. A
> one-page copy for human readers is
> [`apply-model-design/apply-model.html`](apply-model-design/apply-model.html), with a live copy at
> <https://claude.ai/artifact/9cDRZDeBKFiN5un88v7cNJ> (the checked-in file is the authority).
>
> "Palette" is venue-visit D2's word for what the code, the tables and the sync archive still call a
> template. This plan uses it for everything a person reads and keeps the code's names for code.
> Where this plan and the venue-visit plan or R2 ([`show-cue-editing-plan.md`](show-cue-editing-plan.md))
> disagree, this plan wins inside its scope: how a press reaches a selection. R2 §8 left "pressing a
> palette onto cue cells" to this plan; §3.6 answers it.

## 1. Context

On the first evening in the hall, putting a palette, a Look or an effect onto some fixtures felt like a
different mechanism on every surface, and it was confusing. A survey of the frontend and the press
routes on `main` at `2bf9aba` (after venue-visit S1, `4a1a8fb`) found that the surfaces disagree on
all three things a press needs: what it puts on, which fixtures, and which attributes. They also
disagree on where it lands.

- **What a press puts on differs by surface.**
  - The palette strip and the picker: a click writes literals into Local
    (`POST /palettes/{id}/apply`), and ⌥click or a 500 ms hold adds a tracking layer
    (`POST /palettes/{id}/toggle`) (`components/programmer/useTemplatePress.ts:32-142`).
  - A busk pad always toggles a tracking layer; a hold opens a menu (`BuskPad.tsx:166-237`,
    `routes/BuskPressService.kt`).
  - The colour cell's *Recent* chips apply literals only, and offer only palettes pressed before
    (`editor/ColourEditor.tsx:887-922`).
  - The add-layer sheet always adds a new layer and never toggles (`ProgrammerAddLayerSheet.tsx:30-53`
    → `programmer.addLayer`).
- **Fixtures come from a different place on almost every surface.**
  - The strip, the picker and the Recent chips: the grid's marquee (`templateTargetsFor`).
  - Busk: the window's selection pair (`useSelectionPair`, the desk's while following).
  - The add-layer sheet: one fixture or group from its own `CueTargetPicker` (`LayerPicker.tsx:171`),
    ignoring the selection.
  - *+ Effect*: the first group, else the first fixture, of the programmer scope
    (`ProgrammerAddEffect.tsx:66-75`).
  - The hand's *Place* on the layer strip: always the desk selection, even when the window is unlinked
    (`hand/HandLayerTargets.tsx:68-91`).
- **The attribute mask is honoured by five doors and ignored by seven.** The strip, the picker, the
  Recent chips (hard-coded `['COLOUR']` when unlinked), the busk pad and the Spread sheet send
  `families`. The add-layer sheet, the hand's place, Make layer, `LookTogglePicker`, the cue-slot
  `toggleLook`, `FxAddSheet` and Include do not. **There is no picker for the desk mask**: only the
  grid's cell marquee sets it (`useDeskSelectionBridge.ts:193`). Record, Record Look, Include, Make
  layer and the layer popover use a separate `MaskPicker` that never touches it
  (`programmer/maskPicker.tsx`).
- **A focused Look layer is honoured by some doors.** Cell edits, Spread (`write: false`) and
  *+ Effect* (`absorb-effects`) write into the focused Look. The strip, the picker and the Recent
  chips ignore it, so in a focused-Look colour editor the picker writes the draft while a Recent chip
  writes Local.
- **Effects arrive in four forms**: a detached `programmerOwned` instance from *+ Effect*
  (`beatDivision: 1`, `FxPicker.tsx:191-205`), a detached copy per target from a palette click, a
  layer's tracked instance from ⌥click or a pad, and an instance absorbed into a Look.
- **Stacking hides what is lit.** Outside a solo bank, a second palette of the same family on the same
  heads goes on top as a second layer. The first stays lit underneath though it shows nothing, and
  pressing the first again turns it *off*. The composition doc names this case (Amber under Blue),
  and only a solo bank fixes it.
- **Per-property palettes exist only for colour**, as the Recent chips. Slider, position and setting
  cells offer nothing (`fixtures-list/cells/`). The busk side sheets are Speed, Colour, Spread, Effects
  and Show (`lib/buskWindow.ts:60-75`); Effects has no add.
- **What the desk already has.**
  - The press is per target (`ProgrammerLayerStack.toggle`, `fx/ProgrammerLayerStack.kt:373`).
  - `releaseSiblings` narrows other records' layers on the pressed heads, the solo bank's mechanism.
  - `pressWouldRelease` tests the mask on the on arm only (`routes/pressArm.kt`).
  - `TemplateFanOut` lands a palette on a fixture's cells (S1).
  - `appliedState` answers rings.
  - Spread already has a `write: false` arm.

  The model needs few new mechanisms. Most of the work is routing every door through these.
- **An effect instance targets one fixture or one group** (`fx/FxTarget.kt:28`). A selection of loose
  fixtures runs one instance per fixture, in unison; distribution needs a group. This is as true of a
  layer's effects today as of *+ Effect*.
- **S14 (the full-width applied tables) has not landed.** The rail's layer and FX rows are where an
  applied row's actions live today (`ProgrammerLookStack.tsx`, `ProgrammerFxList.tsx`). This plan adds
  *Detach* there, and S14's tables take it when they land.

## 2. Decisions

Agreed with Chris on 2026-10-15 (§11):

| # | Decision |
|---|---|
| D1 | **A palette press puts on a layer that tracks the palette, on every surface**: strip, popup, picker, pad and MIDI. Values come only from *Apply as values* or a layer's *Detach*. |
| D2 | **One palette per head per family.** A palette pressed on heads that hold another palette of its family takes them off it. Pressing a palette on heads it already covers takes it off them, as a pad does. |
| D3 | **One mask: the selection's.** The selection bar's family pill is its picker (the marquee still sets it). Record, Record Look, Include, Make layer and Update open on it, and stay editable in their sheet. |
| D4 | **The per-property popup puts bands round the editor**: the family's palettes that fit the cell's heads above it, effects below it, and *All …* for the family's full picker. |
| D5 | **A bare library effect is a Local effect on the whole selection, and edits follow the selection** (MagicQ's rule). An edit with all of an effect's heads selected changes it in place, keeping its phase. With a subset selected, those heads split off at the phase they had. Pressing another effect of the family on some of its heads takes them off it. |
| D6 | **The family replacement keeps value and effect apart, and Looks always stack.** A value palette replaces value palettes of its family; an effect palette replaces effect palettes of its family. So Amber Breathe can run over Red. |
| D7 | **With a Look layer focused, a press goes into the Look.** A palette's values are resolved per head and written into its rows; an effect palette's effect joins its effects. Looks do not nest. In R2's cue editor a palette goes on as a layer of the cue. |
| D8 | **⌥click is *Apply as values*, and a hold opens the menu, on every surface**, as a busk pad's hold does. |
| D9 | **The MCP tools follow the UI.** `apply_palette` (S4's name for `apply_template`) puts a tracking layer on by default, with `asValues: true` for literals, under the same selection, mask and replacement rules. |
| D10 | **The busk side sheet gets a tab per family**: Level, Colour, Position and Beam, each the popup's body over the busk selection. |

The plan adds its own:

- **P1 — One PR per session**, in §8's order, per CLAUDE.md §"Git workflow". One exception (agreed at
  venue-visit course check K1, 2026-10-16): while session 2 waits on the effects plan's session 3,
  sessions 3–5 may run ahead of it. They leave the effect door — ⌘K *Apply FX* and the picker's Effects
  section, which press through session 2's `POST /programmer/effects` — to session 6, which owns it and
  still waits for session 2. Each session ends with
  `./gradlew test` and `npm run check` green, its engineering-doc paragraphs written, and a
  done-marker in its heading citing its change commit(s), never the merge commit.
  The venue-visit plan's P1 checklist ([`venue-visit-plan.md`](venue-visit-plan.md)) applies
  before each PR.
- **P2 — The desk owns every rule.** Coverage, replacement, widening, the mask test and the split
  are server-side, in one place each. A door sends what was pressed, the selection pair and its
  scope. The client keeps no copy of a rule (the composition doc's §"Applied state is resolved by the
  desk" argument, unchanged).
- **P3 — A press widens a record's layer only when it is the top of the stack.** Pressing Red on Hex 1
  and then on Hex 2 makes one Red layer on both when nothing went on above it in between. Otherwise it
  adds a new layer on top, so a widening never changes what an earlier, higher layer shows.
- **P4 — Solo banks keep their meaning.** A solo bank still releases its siblings of any kind (D6 of
  the busk-layout plan). D2's replacement runs beside it, never instead.
- **P5 — A palette layer's effect is edited as it is today.** The instance's own edit, *Reset to
  palette* and *Update palette* (fixture-fx-sheets plan D20). Only Local effects split (D5), since
  splitting a layer's instance would need splitting its layer.
- **P6 — Include stays its own verb.** It opens a record to edit (`INCLUDE` slots, the include target)
  rather than using one. It starts from the selection's mask (D3) and is otherwise unchanged.
- **P7 — Nothing is stored differently.** No table, no column and no `formatVersion` change. A layer
  records into a cue exactly as a ⌥click layer does today. A Local effect records as an ad-hoc cue
  effect.

## 3. The model

### 3.1 Vocabulary

- **Source**: what was pressed. A *palette* (a value palette or an effect palette) or a *Look* is a
  named record. A *bare effect* is an effect type from the library with parameters and no record. A
  cue pad is playback and outside the model.
- **Selection pair**: the targets (fixtures, cells, groups) and the mask (Intensity, Position, Colour,
  Beam, or every attribute). It is the desk selection while the window follows it, else the window's
  own (`usePressFamilies`, `useSelectionPair`).
- **Scope**: where the programmer is pointed, per window. *Output* and *Local* land on the programmer.
  A *focused Look layer* (`ProgrammerScope.layer`) lands in that Look. R2's *cue editor* lands in that
  cue.
- **Press**: source × selection pair → scope. **Local**: the programmer's own values and its local
  effects (`FxInstance.isLocalEffect`), above its layers.

### 3.2 The rules

1. **Named things track; direct things are Local** (D1, D5).
   - A palette or a Look goes on as a layer whose targets are the selection and whose mask is
     `selection mask ∩ the source's families`.
   - Values set in a cell, a spread, an aim and a bare effect go into Local.
   - *Detach* turns a layer into Local. *Make layer* and *Save as palette* go the other way.
2. **A press is per target**, as today: "already on" is the record covering every selected head, and
   an off press takes the selected heads off every layer of the record.
3. **Replacement** (D2, D6). An *on* press of a palette passes, as siblings to release, every other
   palette on the programmer stack of the **same family and kind** (value or effect). A Look's press
   passes none. A solo bank adds its own siblings (P4).
4. **Widening** (P3). An *on* press whose record's newest layer is the top of the stack, with the
   same mask, blend and amount, widens that layer's targets instead of adding one.
5. **The mask cuts; the layer keeps the cut**, as today: a palette outside the mask is refused
   (`PALETTE_OUTSIDE_MASK`); a Look lands masked and names the skipped families; an off press comes
   off under any mask. A cell's popup is masked to its own family.
6. **Scope redirects** (D7). With a Look focused, a press resolves without writing the programmer and
   writes the Look. In the cue editor it writes the cue. Nothing reaches the stage in either.

### 3.3 Selection, mask and layer

```
                 mask (columns)                         Programmer · top wins
             Int  Pos  Col  Beam                        ┌──────────────────────────────┐
  Hex 1  ┐   ·    ·   [■]   ·        press Amber ────►  │ Local   values · bare effects│
  Hex 2  │   ·    ·   [■]   ·        (palette, Colour)  │ Amber   Hex 1–4 · Colour     │ ← replaced Red
  Hex 3  ├── targets  [■]   ·                           │ Red     Hex 5   · Colour     │   on Hex 1–4
  Hex 4  ┘   ·    ·   [■]   ·        press Warm wash ─► │ Warm    Hex 1–4 · Colour     │ ← Int, Pos skipped
  Hex 5      ·    ·    ·    ·        (Look, I·C·P)      └──────────────────────────────┘
```

The selection's targets are rows and its mask is columns. A press can touch only where they meet,
and a layer records both as its `targets` and `propertyMask`.

### 3.4 Effects (D5)

- **A bare effect press** is `POST /programmer/effects` (§3.7): one Local instance per selected
  target, as a layer spawns them (a group stays one group effect, its distribution intact).
  > *Note (effects plan session 3):* a bare effect press is one instance over the selection,
  > `effects-plan.md` D3 — `POST /fx/add` takes the ordered `targets`, and the programmer's
  > `+ Effect` and ⌘K *Apply FX* start one over the selection's rows. Session 2 builds the press and
  > the split on that instance rather than on one per target. The mask
  must allow the effect's family. With no timing it takes S15's default (venue-visit D16): one bar on
  the master tagged for the family's `usage`, else master 1. The desk stamps it, as MCP's
  `parseEffectTiming` does; today only the client and MCP stamp a master, and `POST /fx/add` defaults
  the division alone.
- **Replacement for Local effects.** The press first takes the selected heads off every other Local
  effect of the same family. A fixture instance wholly inside the selection stops. A group instance
  partly inside is narrowed: it is rewritten as one instance per uncovered member (the split below,
  with no edit), at the phase and count each member had.
- **The split.** `PUT /fx/{id}` gains an optional `targets`.
  - Naming every head the instance covers, or no `targets` at all, is today's in-place edit. The id is
    kept, and the phase too, except on a move to or from *Seconds*, which restarts it (S15).
  - Naming a strict subset narrows the instance to the rest and spawns the edited copy on the subset.
  - Each split-off head starts where it was: a group member's distribution offset becomes its own
    instance's `phaseOffset`, so nothing jumps. A wall-clock or *Seconds* copy also takes the instance's
    clock (`accumulatedScaledMs`), and an N-shot copy its count (`nShotStartTick`,
    `nShotStartScaledMs`, moved on by the member's offset), as `FxEngine.updateEffect`'s swap carries
    them (S15, effects plan session 1).
  - Refused for a layer's instance (P5) with `FX_SPLIT_LAYER_EFFECT`; a cue's instance stays read-only.
  > *Note (session 2, as built):* the split rewrites the effect as one instance per head it paints and
  > the named heads' copies take the edit, so the rest are narrowed the same way the replacement
  > narrows. The member's offset is carried **beside** each copy's `phaseOffset` (`SplitMember`, with
  > the member's group size and context), not in it: every copy keeps one phase offset, so an edit
  > following the selection sends one value to each, and Record folds the offset into the stored phase
  > offset. Folding it in and also moving the N-shot anchor by it, as above, would count it twice. A
  > cue's or a manual effect's split is 409 `FX_SPLIT_NOT_LOCAL`. The family the press tests against
  > the mask and replaces by is the driven property's group, not the library category (decided with
  > Chris during review). See `fx-engineering.md` §"Local effects follow the selection".
- **The Effects band and the busk Effects tab** list the effects running on the selected heads:
  Local ones editable by these rules, a palette layer's by P5, a cue's read-only.
- **Distribution over a hand selection** is R4's, and landed with the effects plan's session 3: one
  instance spread over the selection in its order (`effects-plan.md` D3, §3.4). A layer's deferred
  effects still run one instance per target.

### 3.5 Detach and Apply as values (D1, D8)

- **Detach** (`programmer.detachLayer {layerId}`, WS like the other layer ops):
  - The layer's cooked values on its keys are written as `WEB` Local values, `touched`, in one batch.
  - Its spawned effects lose their layer key and `LayerSource` and keep running, phase kept, as Local
    effects. A spent N-shot (effects plan session 1) is removed instead: only a layer keeps one
    (`FxEngine.finishNShots`).
  - The layer is then removed with no republish of its keys, so the stage does not move.
  - A Look layer's scenery passes into the programmer's own scenery, so it does not move either
    (decided with Chris during session 1; §9's "scenery" is about addressing it through the
    selection, which this does not do).
  - The rail's layer menu and S14's tables carry it.
- **Apply as values** is ⌥click and the hold menu's second item:
  - for a palette, today's `POST /palettes/{id}/apply` (literals, a detached effect copy);
  - for a Look, a new `POST /looks/{id}/apply`, which is the Look's rows over the selection (or its own
    fixtures) as `WEB` literals and its effects as Local, the same work as a press followed by Detach,
    in one recook.

### 3.6 Into a record (D7)

- **A focused Look layer.**
  - `POST /palettes/{id}/apply` gains `write: false`, as Spread has: the same resolve and fan-out
    (`TemplateFanOut`), answering `values[].value` as each head's literal in the Look row grammar
    (`written` stays the count, since the click already answers it as a number), and
    writing nothing.
  - The client lands those values in `LookRowStore`'s draft, which saves through the ordinary Look PUT.
  - An effect palette's effect, and a bare effect, become the Look's effects through `absorb-effects`,
    as *+ Effect* does today.
  - A Look pressed into a Look is refused by the client (*Looks don't nest*).
- **R2's cue editor.** `POST /cues/{id}/layers/press {templateId | lookId, targets, families}` applies
  §3.2's rules 2–5 to the **stored** cue's layer list and writes it, sending nothing:
  - A palette goes on as a cue layer, replacing the family's other palette layers on those heads.
  - A Look goes on as a layer.
  - A second press takes it off.
  - A bare effect is a cue effect, added in draft as R2 D17 adds one.
- **One rule, two stacks.** `fx/LayerPress.kt` is the press as a pure function over a layer list:
  `(layers, source, targets, mask, siblings) → layers + outcome`. `ProgrammerLayerStack.toggle`
  becomes its programmer caller and the cue route its stored caller, so the two cannot drift.
  > *Note (session 1, as built):* `LayerPress.press(layers, arriving, ops, coverage, siblings,
  > replaces, replacesOn)`, generic over the layer type through `LayerPress.Ops`. It takes the
  > replacement set rather than finding it, and the mask is the arriving layer's. What finds the
  > set — `familyReplacements`, `paletteHeads`, `pressTargets` — is private to
  > `ProgrammerLayerStack`, and the arm a door asks before its mask test (`pressWouldRelease`,
  > `routes/pressArm.kt`) reads the programmer's store. Session 8 needs both over a cue's layers.

### 3.7 The wire

| Route or frame | Session | Notes |
|---|---|---|
| `POST /palettes/{id}/toggle`, `/looks/{id}/toggle`, `/busk/pads/{id}/press` | 1 | Replacement siblings (§3.2 rule 3) and widening (rule 4). No request change. |
| `programmer.detachLayer {layerId}` | 1 | Inbound WS, an operator gesture; a `TraceSampling.WS_MESSAGES` row. Answers `programmer.layerState`. |
| `POST /looks/{id}/apply` | 1 | `{targets, families?, fadeMs?}`; Apply as values for a Look. |
| `POST /palettes/{id}/apply` `write` | 1 | `false` answers `values[].value` without writing (D7); `written` is the count. |
| MCP `apply_palette` | 1 | `LAYER` by default, `asValues: true` for literals (D9); `apply_look` gains `asValues`. |
| `POST /programmer/effects` | 2 | `{effectType, propertyName, targets, families?, parameters?}` plus the timing and dynamics fields `POST /fx/add` takes (`beatDivision`, `speedMasterUuid`, `rateSpeedMasterUuid`, `seconds`, `size`, `direction`, `nShot`, `spread`, `wings`, `blocks`, `groups`; S15, effects plan sessions 1–2); a bare effect over the selection, Local. 400 `EFFECT_OUTSIDE_MASK`, `EFFECT_NEEDS_SELECTION`. |
| `PUT /fx/{id}` `targets` | 2 | The split (§3.4). The `updateFx` frame takes the same field. Answers `EffectSplitResponse {splitEffectId, edited, rest}` on a split. 409 `FX_SPLIT_LAYER_EFFECT` (a layer's), `FX_SPLIT_NOT_LOCAL` (a cue's or a manual one); 400 `FX_UPDATE_REFUSED` for a shape change with a strict subset, or targets naming none of its heads. |
| `POST /cues/{id}/layers/press` | 8 | §3.6, the stored cue's layers. |

No table, no column, no `formatVersion` change (P7).

## 4. UX

### 4.1 One press in the client

`hooks/usePress.ts` replaces `useTemplatePress` and the scattered `toggleLook` / `programmerAddLayer`
calls:

- It reads the window's selection pair and scope, and takes `source` and `gesture`
  (`press | asValues | off`).
- It calls one route per kind and scope (§3.7) and reports one way:
  - a skip toast per head or family (`skippedRowsMessage`);
  - "*n* effects started";
  - the server's refusal text, with no client pre-refusal.
- The doors that call it:
  - the strip and the picker;
  - every popup band;
  - the busk pads (their press already is one) and the busk side sheets' chips;
  - the hand's *Place* on the layer strip, now honouring an unlinked window;
  - `LookTogglePicker` and the cue-slot Look press;
  - ⌘K's *Apply FX*.
- Gestures on every chip and pad:
  - **tap**: the press;
  - **⌥click**: *Apply as values*;
  - **hold or right-click**: the menu. Its first item names the tap's verb for this selection
    (*Put on* / *Take off these heads*), then *Apply as values*, *Pick up* and *Edit palette…*.

### 4.2 The palette strip and the picker

- **The strip** (row C) stays a shortcut:
  - It shows the recently pressed palettes that fit the selection, by name when there is no history,
    up to eight.
  - It is filtered by the **selection's mask** as well as the heads' capabilities: a palette outside
    the mask is not offered.
  - Its chips gain the pad's ring (`templateLayerPresence`).
  - *All · n* opens the picker.
- **The picker** (`ApplyPicker`) is one component for every library door:
  - It is opened on one family by a popup's *All …*, and on every family by *All · n*, the rail's
    *+ Look* and *+ Palette*, and the busk sheets.
  - Sections: Recent, A–Z, Per fixture, Effects, and *Outside the mask*, dimmed with the reason.
  - A press doesn't close it.
  - It replaces `TemplatePicker` and the add-layer sheet's source step.

### 4.3 The per-property popup (D4)

`components/editor/PaletteBands.tsx` wraps every value cell's editor (`ColourEditor`, `SliderCell`'s,
`PositionCell`'s) in every form it already has (popover, bottom sheet, side):

- **Header**: the property, the heads the press lands on (the cell batch, cells by their element key),
  and the mask, which is the cell's own family.
- **Palettes band**: the family's palettes that fit the heads, filtered as the strip filters, recent
  first, then by name, up to two rows, with *All …*.
  - A Beam cell offers the Beam palettes that set its property (a zoom cell, those with a zoom row).
  - A setting cell (gobo, prism) has no Palettes band: a value palette can't carry a slotted role.
    R4.7's Slot Steps is an effect on a setting, and a palette of it an effect palette, so a setting
    cell has the popup with its Effects band only (session 6, §11, 11).
- **The editor**, unchanged. A drag or a typed value is a Local value.
- **Effects band**:
  - the effects running on these heads, lit;
  - the family's effect palettes;
  - *+ Effect …*, which opens `FxPicker` on this property for a bare effect (§3.4).
- **Footer**: *Save as palette…* for every family, from the cell's values
  (`NewTemplateFromSelectionSheet` with the family), and the family's own tools (*Spread…*, *Pick*,
  *Aim…*). As session 5 built it (`293db483`), `PaletteFooter`'s `tools` slot is drawn empty and the
  colour editor keeps its own *Pick* and *Spread…*; the position cell's *Spread…* and *Aim…* are
  venue-visit S12's and S13's.
- **Under a focused Look** a teal band says *Into Warm wash*. The rings go, since a Look holds values.
  The presses follow §3.6.

The colour cell's Recent chips become this band. The desk and iPad forms, which hid them because row C
showed the strip, now show the band too, since it is the family's offer and the strip is recents.

### 4.4 The mask (D3)

- **The selection bar's family pill becomes a picker**:
  - Intensity, Position, Colour and Beam as toggles, and S10's *All attributes*, which clears the mask.
    S10 built these as `MaskToggles` (`programmer/maskPicker.tsx`): *All attributes* is a switch,
    and the four family checkboxes show only while it is off, all four ticked when it is first
    switched off. It knows nothing of the selection, so the pill mounts it as it is, through
    S10's `useMaskDraft` — the selection's `families` folds all four to none and cannot hold
    "none ticked", so the draft holds the switch's state and writes only a real change.
  - It writes `selection.set` with the current targets and the new families. *(As built:
    `selection.setFamilies`, the mask alone with the heads kept — see Session 4.)*
  - The marquee still sets it; a rows-only click still clears it.
- Record, Record Look, Include, Make layer and Update open their `MaskPicker` on the selection's mask.
  A change there is the sheet's own and does not move the selection.
- The busk selection summary carries the same pill.

### 4.5 The busk side sheets (D10)

- **The tabs**: Level · Colour · Position · Beam · Spread · Effects · Speed · Show.
- The four family tabs are the popup's body over the busk selection pair. The Colour tab keeps today's
  live picker as its editor, with the band replacing its Recent chips.
- **Effects** lists what runs on the selection (§3.4), gains *+ Effect …*, and edits by selection: with
  part of an effect selected its editor says *Hex 1–2 of 1–4* and offers *Apply to Hex 1–2* and
  *Edit all 4* (session 6's decisions say why it is not *Select all 4*).

### 4.6 The rail

- **A layer's menu** gains *Detach*.
- A layer row's subtitle names a replacement when it happens (*lost Hex 3–4 to Amber*) until the next
  change. It is the client's reading of two `layerState` frames, never a stored fact.
- *+ Look* and *+ Palette* open the picker on the selection (§4.2). The add-layer sheet's
  `CueTargetPicker` step goes; *the Look's own fixtures* stays as the picker's option for a Look with
  no deferred effect.
- *+ Effect* opens `FxPicker` over the whole selection under its mask, and lands Local (§3.4).

## 5. What folds in, and what stays a shortcut

| Surface | Becomes |
|---|---|
| Palette strip | A shortcut: recents, the press, the ring (§4.2). |
| Palette picker | The one picker (§4.2). |
| Busk pads | A shortcut, unchanged; solo banks stay theirs (P4). |
| Add-layer sheet | Folds into the picker on the selection (§4.6): the programmer's in session 5, the cue editor's in session 8. |
| FX sheet, ⌘K *Apply FX* | Fold into the Effects band and the picker's Effects section, over the whole selection (§3.4). |
| Busk Colour and Spread sheets | Colour becomes a family tab (§4.5); Spread stays a direct-value tool. |
| Busk Effects sheet | Gains *+ Effect* and the split edit. |
| Include | Stays its own verb (P6); opens on the selection's mask. |
| Colour Recent chips | Become the popup's palette band. |
| The hand's *Place* | Calls the press with the window's selection pair. |

## 6. Migration

None (P7). A programmer layer stack in memory when the desk updates is unaffected: replacement acts
only on the next press.

## 7. Dependencies

- **S4 (palettes)** before session 1, so new copy, routes and the MCP tool are named once
  (venue-visit P2).
- **S10 (*All attributes*)** before session 4.
- **S15 (effect timing)** before session 2, so a bare effect starts on S15's default.
- **S14 (applied tables)**: independent. Whichever lands second carries *Detach* onto the other's rows.
  S14 landed second and carries it, in the layer tables' settings popover (`dae7dd5d`).
- **R2 sessions 5–6** before session 8. Both have merged (`ec093275`, `639fe8ca`).

## 8. Implementation — eight sessions

Each session is one PR (P1).

### Session 1 — The press on the desk (backend) — done, `5613a361`

- `fx/LayerPress.kt` factored out of `ProgrammerLayerStack.toggle`, pure over a layer list.
  `toggle` becomes its caller, with no behaviour change, pinned by the existing toggle tests.
- Replacement siblings (§3.2 rule 3) in every palette door: the toggle route, `BuskPressService`,
  `SurfaceActions.pressTemplate` and the MCP tool.
- Widening (rule 4).
- `programmer.detachLayer`, `POST /looks/{id}/apply`, `write: false` on palette apply.
- MCP `apply_palette` defaulting to a layer (D9), `apply_look`'s `asValues`.
- **Tests:**
  - Blue on Red's heads narrows Red, and Red pressed back narrows Blue.
  - An effect palette over a value palette of its family leaves it (D6); a Look over a palette leaves
    it.
  - A solo bank still releases siblings of other kinds.
  - Red on Hex 1 then Hex 2 is one layer, and with Blue pressed between them it is two.
  - Detach leaves every channel where it was and the effects at their phase.
  - `write: false` writes nothing and answers literals, a Shehds' cells included.
  - The mask tests stay green.
- **Docs:** `lighting-composition-model.md` §"A press is per target" (replacement and widening),
  §"Looks, palettes and layers" (Detach, Apply as values); `mcp-engineering.md`; CLAUDE.md's
  §"Palette Endpoints".

### Session 2 — Local effects follow the selection (backend) — done, `4212865a`

- §3.4: `POST /programmer/effects`, the Local replacement, the `targets` split on `PUT /fx/{id}` and
  `updateFx`, the per-member phase carry, `FX_SPLIT_LAYER_EFFECT`.
- **Ask Chris before building: the split of a step-timed or ping-pong group effect.** A step-timed
  member's cycle is scaled by `distinctSlots` (`FxInstance.memberCycleDivision`) and a ping-pong's
  sweep by the slot count. Both predate R4.2, which changed only how `distinctSlots` is counted (after
  Blocks and Groups). A one-head copy has neither, so carrying the offset as `phaseOffset` (§3.4)
  keeps the phase but not the cycle or the sweep. Either the split carries the parent's cycle and
  sweep, or it refuses those effects. A shape edit named with a strict subset of `targets` does
  nothing on one-head copies: refuse it or ignore it.
  - *Answered by Chris, 2026-10-16:* the split **carries** the parent's cycle and sweep — each copy
    remembers the size of the set it came from — and a shape edit with a strict subset is
    **refused** (400 `FX_UPDATE_REFUSED`, only for a field that would change).
- **Tests:**
  - A bare effect on four fixtures is one Local instance over them (effects plan D3), and on a
    group one.
  - A second colour effect on two of four takes those two off the first.
  - A subset edit of a group effect leaves the rest running and starts the subset at each member's
    old phase.
  - A whole-set edit keeps the id.
  - A layer's instance refuses a split.
  - Clear and Release take the split instances.
- **Docs:** `fx-engineering.md` (Local effects and the split), `websocket-engineering.md` (`updateFx`).

### Session 3 — One press in the client (frontend) — done, `a14d8220`

- §4.1's `usePress` and every door in it but the effect door (⌘K *Apply FX*), which is session 6's.
- The strip's tap, ⌥click and hold, with rings and the mask filter (§4.2).
- The hold menu on chips.
- Scope routing for a focused Look (§3.6), via `write: false` and `absorb-effects`.
- *Detach* in the rail; the replacement subtitle (§4.6).
- **Tests:**
  - A strip tap toggles a layer and ⌥click applies values.
  - A hold opens the menu, never a layer.
  - Every door sends the window's pair, and an unlinked window's own.
  - With a Look focused, a strip tap writes the Look's draft and sends no programmer frame.
  - The hand's place honours an unlinked window.
- **Docs:** the frontend CLAUDE.md (a section, "Applying to a selection"), retiring
  `useTemplatePress`'s paragraph.
- The client still refuses one press before sending (decided during session 3): a palette with
  no targets that would land nowhere — a generic one on either gesture, a per-fixture one applied
  as values — since the toggle and apply routes take it without refusing (§4.1's "no client
  pre-refusal" assumed they refuse).

### Session 4 — One mask (backend + frontend) — done, `0172c210`

- §4.4: the pill's picker, the busk summary's pill, and the five sheets opening on the selection's mask.
- **Tests:**
  - The pill writes `selection.setFamilies`, the targets kept.
  - *All attributes* clears it.
  - Record opens on the selection's mask, and a change in the sheet leaves the selection alone.
- **Docs:** the frontend CLAUDE.md's selection paragraphs; `lighting-composition-model.md` §"A press
  carries the selection's attribute mask".
- The pill writes a mask-only desk frame (decided during session 4): `selection.setFamilies`
  (`DeskSelection.setFamilies`, the mask replaced and the heads kept) rather than §4.4's
  `selection.set` with the current targets, which would send back heads the window may have heard
  of late and undo a toggle still on its way. The programmer's goes through its list bridge's
  `remask`, so the echo is known as its own. Every sheet opens on the selection's mask wherever it
  is opened from, Show's and the Prompt Book's Record and Update included — D3 as written.

### Session 5 — The palette popup and the one picker (frontend) — done, `293db483`

- §4.3's `PaletteBands` on the colour, slider and position editors; *Save as palette* for every family.
- §4.2's `ApplyPicker`, replacing `TemplatePicker` and the add-layer sheet's source and target steps
  (§4.6). Its Effects section is session 6's.
- **The cue editor keeps its own.** `AddLayerSheet` (`LayerPicker`, its `CueTargetPicker` step) has
  one host when this session began, the rail's `ProgrammerAddLayerSheet`, which this session removes;
  R2.6 gives it the cue editor's *+ Look* and *+ Palette* (show-cue-editing §4.1). This session moves only the programmer's door.
  The cue's moves to the picker in session 8.
- **No band in the cue grid.** R2.5's Values grid mounts the same cells with no press door (R2 P2:
  no palette strip, no Recent, which has pressed through `usePress` since session 3). A band press
  there would reach the programmer, so the bands are not drawn in it until session 8 routes them to
  the cue.
- **Tests:**
  - A dimmer cell offers Intensity palettes and a zoom cell the Beam palettes with a zoom row.
  - A gobo cell has no Palettes band (session 6 gives it its Effects band).
  - A band press lands on the cell batch under the cell's family.
  - A Shehds cell is offered the colour palettes its zone takes (S1).
  - *+ Palette* presses onto the selection.
- **Docs:** the frontend CLAUDE.md's editor-kit section.
- The Palettes band holds **value** palettes, per-fixture ones among them as the strip offers them;
  effect palettes are the Effects band's (decided during session 5). The picker keeps
  `TemplatePicker`'s Effects section of effect palettes, so they stay reachable from every library
  door until session 6 adds bare effects to it.
- One offer filter, `paletteFits` (`lib/paletteOffer.ts`), for the strip, the bands and the picker
  (decided during session 5). Only a Beam cell is narrowed by its property; a colour cell's
  `rgbColour` says nothing about which colour palettes fit. A cell whose property no value palette
  can carry (outside `TEMPLATE_PROPERTIES`) has no band and no *Save as palette…*. A setting cell
  (gobo, prism) is bare because `SettingCell` is not wrapped, prism being in that vocabulary.
- *Outside the mask* stays pressable (decided during session 5): the desk tests the mask on the on arm
  only, so a lit palette outside it must still come off, and an on press is refused there in the
  desk's words.
- The busk Colour tab's Recent became the band too (decided during session 5): Recent was
  `ColourEditor`'s one slot for both hosts. It sits where Recent sat, at the foot of the tab's
  scroller, until session 7 rebuilds the tab. The rail's Colour tab draws no band, since row C's strip
  and the cell's popup are both on screen.
- A Look in the picker can land on its own fixtures (decided during session 5): *On its own
  fixtures* beside *On n selected*, chosen by default with nothing selected — the add-layer sheet's
  *Use the look's own fixtures*, kept. A Look whose effects take the layer's targets is shown outside
  with that reason there.
- The programmer's add-layer sheet went whole, its speed and rate master overrides with it (decided
  during session 5): a press carries neither, and the toggle routes take neither. Recorded as
  `FU-PRESS-LAYER-MASTER`; a cue layer keeps them in the cue's sheet.

### Session 6 — Effects in the popup (frontend) — done, `e8217314`

- The Effects band (§4.3); *+ Effect*, ⌘K *Apply FX* and `ApplyPicker`'s Effects section on
  `POST /programmer/effects`.
- The split editor (§4.5's *Apply to Hex 1–2 · Edit all 4*) in the FX editor popover, wherever it
  opens.
- `FxAddSheet` retired into the band and the picker.
- **What R4.3 left.** *+ Effect* and ⌘K *Apply FX* already start one effect over the selection,
  through `FxAddSheet` on `POST /fx/add` with `targets` (`effectTargetOf`,
  `components/fx/selectionEffectTarget.ts`), programmer-owned and so Local. They send no mask and
  replace nothing, and they read two selections: *+ Effect* the programmer list's rows, ⌘K
  `useWindowSelectionTargets`. This session sends both through `POST /programmer/effects` with
  the window's pair.
- **A focused Look stays on `/fx/add`.** `POST /programmer/effects` takes the heads off other Local
  effects before it starts, which a press into a Look must not do (§3.2 rule 6). There the band and
  *+ Effect* keep `/fx/add` then `absorb-effects`, as session 3's effect-palette press does.
- **A split answers new ids.** `PUT /fx/{id}` answers `EffectSplitResponse {splitEffectId, edited,
  rest}`; the `updateFx` frame answers only `fxChanged` REMOVED + ADDED. An open editor follows the
  edited copy.
- **What the band offers.** Its family is the cell property's group, as the press reads it (session
  2: a Pulse on uv is Colour). A zoom, iris, frost or focus cell offers R4.6's beam effect that
  drives it (Zoom Breathe, Iris Pulse, Frost Pulse, Focus Pull) and its Beam effect palettes. R4.7's
  Palette Steps is offered where `FxPicker` offers it (two generic value palettes or more naming
  the property).
- **A setting cell gains the popup with its Effects band only** (§11, 11). §4.3 gives a gobo or
  prism cell no Palettes band and session 5 wraps only colour, slider and position editors; R4.7
  added Slot Steps on a setting and its effect palettes, which this band offers there.
- **Tests:**
  - *+ Effect* over a four-head selection starts one effect over them (effects plan D3), and takes
    those heads off another Local effect of its family.
  - A zoom cell's band offers Zoom Breathe.
  - A subset edit sends `targets`.
  - A palette layer's effect offers no split.
  - In a focused Look, an effect is absorbed.
- **Docs:** the frontend CLAUDE.md's FX paragraphs.
- `FxAddSheet` went into the one picker as its **effects arm** (decided during session 6):
  `ApplyPicker` with `library="effects"` is `FxPicker` on the bare effect press (a tap starts, a second
  swaps, phase kept, *Edit <name>* opens the editor in the panel) above the effect palettes that fit.
  ⌘K *Apply FX*, the rail's *+ Effect* and a band's *+ Effect …* open it, and the palettes' Effects
  section reaches it through an *Effect…* pad. The bare effects are `FxPicker` there rather than pads,
  so auditioning by tapping keeps its swap and its editor.
- *+ Effect* and ⌘K read one pair, `useWindowEffectPair` (decided during session 6): the targets
  `useWindowSelectionTargets` gives, so a group row stays one group effect with its distribution
  (§3.4), and the mask a press from the window carries. The marquee's heads (`useWindowPressPair`)
  would have expanded a group row to its members.
- **A subset edit is held, then sent once** (decided during session 6): with part of a Local effect
  selected the editor sends nothing as it moves, and *Apply to Hex 1–2* sends the edit as `PUT /fx/{id}`
  with those heads as `targets`. A split answers one copy per head, so the editor follows the edited
  copies, sending every later move to each. Splitting on every move of a drag would mint a set of
  copies per frame. Which heads are covered is the client's reading, for the words alone
  (`components/fx/effectSplit.ts`); the split stays the desk's.
- *Select all 4* became ***Edit all 4*** (decided during session 6): it sends what is held to the whole
  effect in place and carries on live, without moving the selection. Inside a cell's popup the batch is
  the selection and cannot be moved from within it, and moving the desk's from an editor would retarget
  every other door. §4.5 and the session's scope above use the new words.
- The Effects band lists the effects **on its property** that paint one of the heads (decided during
  session 6); a cue's is drawn but not opened, its home being the cue. `paletteFits` narrows a Beam
  cell's effect palettes by the property the effect drives, as it narrows value palettes by their rows.
  The colour editor draws the band only in the programmer's cell: the busk Colour tab's effects are its
  Effects tab's (session 7).
- **⌘K on the programmer opens the rail's *+ Effect*** (decided during session 6): the palette is
  mounted outside the programmer's marquee store and scope, so a picker of its own there could read
  neither the window's mask nor a focused Look layer. Elsewhere ⌘K opens its own picker on the window's
  pair.
- The effects view of the picker sizes to its list, up to the same 540px, where the palettes and Looks
  hold their height; and it opens over the cell's popup, its own lit row the feedback while it is open
  (Chris's calls during session 6's review).

### Session 7 — The busk side sheets (frontend)

- §4.5's tabs and the Effects tab's add and split.
- **The tab list.** `LIVE_SHEET_TABS` and the `BuskSheet` vocabulary in `lib/buskWindow.ts` (today
  `speed · colour · spread · effects · show`) are the one list, with `SIDE_SHEET_TABS` in
  `SideSheet.tsx`. A tab added there reaches the strip, the fold, the Screens sheet's Sheet segment
  and the MIDI toggle's memory; `SideSheetOverlay` (no Speed, opening on `OVERLAY_SHEET_TAB`,
  Colour) mounts each tab by name.
- **Eight tabs do not fit the floor as measured.** `SHEET_MIN_WIDTH` (320) holds five only because
  `tabWordClass` folds every tab but the open one to a 30px glyph below `BUSK_TAB_WORDS_FROM` (460):
  243px of strip at the floor, where the row has 296. Three more glyphs are about 90px. The overlay's
  `w-80` strip has less room still. This session re-measures the floor, the rung and the overlay.
- **What a family tab can dock.** `ColourEditor` is the one editor body with a docked host
  (`ColourSheet`, which plans the busk writes through `planColourWrites` / `writeTargetsOf`). The
  level and position editors live inside `SliderCell` and `PositionCell`, and the XY pad is written
  twice, both private: `PositionCell`'s `XyPad` and the fixture sheet's `PositionPad`
  (`fixtureSheet/PropertyRow.tsx`, writing through `sheetWrites.ts`). The Level, Position and Beam
  tabs lift one of each rather than add a third.
- **The bands.** `PaletteBand` and `EffectsBand` (`components/editor/PaletteBands.tsx`) are exported
  and take their targets and mask. Session 5 put the Palettes band in the Colour tab where Recent
  sat, at the foot of its scroller, pressed under the busk pair's mask. Session 6 left that tab
  without an Effects band, its effects being the Effects tab's; whether the Level, Position and Beam
  tabs draw one is this session's call.
- **The Effects tab today.** `EffectsSheet` lists the programmer-owned effects on the selection
  (`effectsOnSelection`) and opens `FxEditor` inline, with no split. Session 6's split editor is
  `SplitEditor`, private to `FxEditorPopover.tsx`, which reads the window's pair
  (`useWindowPressPair`) when given no `selection`. *+ Effect …* is `ApplyPicker`'s effects arm
  (`library="effects"`) on `useWindowEffectPair(false)`: the desk's pair while following, the busk
  view's copy when unlinked.
- **Tests:**
  - Each family tab presses over the busk pair.
  - The Position tab's XY writes Local.
  - The Effects tab's add uses the busk selection.
- **Docs:** the frontend CLAUDE.md's busk section, whose §"Focus and the side sheet" lists
  `busk.sheet`'s values and the overlay's tabs (`docs/desk-screens.md` names none).

### Session 8 — Into the cue editor (backend + frontend)

- §3.6's `POST /cues/{id}/layers/press` on `LayerPress`. The replacement set, a per-fixture
  palette's targets and the press's arm are the programmer's today (§3.6's note); lift them beside
  `LayerPress` so the cue route and `ProgrammerLayerStack.toggle` find them one way.
- `CueEditor`'s (`components/cues/editor/`, R2.4) Values grid and Looks tab gain the bands and the
  picker over its own selection, writing the cue (R2's P2: nothing sent). The Looks tab's *+ Look*
  and *+ Palette* leave R2.6's `AddLayerSheet` for the picker, which retires it.
- **What R2.6 left.** The editor's selection is `CueValuesView` (`components/cues/editor/`), its
  `selectionTargets` what *+ Look*, *+ Palette*, *Targets* and *+ Effect* act on; `AddLayerSheet`
  takes it as `defaultTargets`. The tabs write through `CueRowStore.editLayers` / `editEffects`, a
  whole list built on `fetchLatestCue` — what a press route answering the cue would replace.
- **What sessions 5 and 6 left.** The bands and the picker press the programmer. `usePress` and
  `ApplyPicker` take a scope of `'window' | 'programmer'` and land in Local or a focused Look
  (`PressInto`); neither has a cue arm. `PaletteBands` draws nothing without `projectId`, which
  R2.5's grid withholds, so its cells are bare today. `EffectsBand` lists the running effects
  (`fx/active`), and its *+ Effect …* opens the picker's effects arm on the bare press. In the cue a
  bare effect is R2.6's `FxPicker` draft arm (`draft={{current, onPick}}`), which stores the effect
  and runs nothing (§3.6).
- **A cue layer's timing and masters move to its settings popover** (§11, 12). `AddLayerSheet`'s
  last step (`LayerPicker`'s timing step) is the only place a cue layer's delay, interval, random
  window and speed or rate master override are set; `LookStack` shows them as a `TimingBadge` and
  edits none. The cue Looks tab's layer settings popover gains those fields, so a layer the picker
  adds takes them there, and so does an existing one. `FU-PRESS-LAYER-MASTER` stays the
  programmer's.
- **Tests:**
  - A palette pressed on cue cells adds a cue layer, and Blue replaces Red in the stored cue.
  - A second press takes it off.
  - No `programmer.*`, `channels.*` or `fx.*` frame is sent.
- **Docs:** `cues-engineering.md` §"REST API", the frontend CLAUDE.md's "The cue editor".

## 9. Explicitly out of scope

- ~~**Distribution over a hand selection**~~ — R4's, landed with the effects plan's session 3
  (`3cefa3ed`): one effect over the selection in its order (§3.4).
- **Effect parameters and families** beyond what exists: R4's.
- **Per-head overrides inside one effect instance** (Eos's Effect Channels). The split gives each
  subset its own instance instead.
- **Splitting a palette layer's effect** (P5).
- **Palettes for slotted roles** (gobo, prism wheel slots). A Look holds them; the closed vocabulary
  stands. R4.7's Slot Steps palette is an effect palette, not one of these.
- **Scenery.** It is addressed by element, never through the selection, and nothing here changes that.

## 10. Follow-ups to record

In [`followups.md`](followups.md), when the session that creates them lands:

- ~~`FU-FX-SELECTION-DISTRIBUTION`~~ — not recorded: the effects plan's session 3 closed it, one
  effect over the selection (`effects-plan.md` D3).
- `FU-PRESS-REPLACE-LOOKS` (session 1): a Look pressed over a Look on the same heads stacks (D6).
  Trigger: an operator expects the second to take the first off outside a solo bank.
- `FU-PRESS-LAYER-MASTER` (session 5, recorded then): a programmer layer has no speed or rate master
  override since the add-layer sheet left the programmer. Session 8 meets the same question for a
  cue layer.

## 11. Questions

Answered by Chris, 2026-10-15, in three rounds:

1. **What does a palette press put on?** A tracking layer, everywhere (D1). Values everywhere with a
   layer on ⌥, and today's split, were declined.
2. **A palette on heads holding another of its family?** It replaces it there (D2). Stacking, and
   replacing only in solo banks, were declined.
3. **How many masks?** One, the selection's (D3). Two independent masks, and the marquee alone, were
   declined.
4. **The popup's layout?** Bands round the editor (D4). Tabs, and a separate palette button, were
   declined.
5. **A bare library effect, and how it is edited?** Local on the whole selection, edits following the
   selection (D5). Asked after a look at how MagicQ, grandMA3 and Eos edit an effect on several
   heads. "An edit changes all heads" and "always through a palette" were declined.
6. **What does the family replacement cover?** Value and effect palettes apart, Looks stacking (D6).
   "Any palette of the family" and "Looks too" were declined.
7. **A palette pressed with a Look layer focused?** Its values are written into the Look (D7).
   Refusing it, and going to the programmer, were declined.
8. **⌥click and hold, now a click puts a layer on?** ⌥ for values, hold for the menu (D8). No
   modifier, and ⌥ to stack, were declined.
9. **Should the MCP tools follow the UI?** Yes (D9). Keeping values as the default was declined.
10. **The busk view's popup?** A side-sheet tab per family (D10). Colour only, and pads only, were
    declined.

Answered by Chris, 2026-10-17, at venue-visit course check K3:

11. **A gobo or prism cell's popup, now Slot Steps exists?** It gains the popup with only its Effects
    band, offering Slot Steps and its effect palettes. Leaving it to *+ Effect* in the rail was
    declined.

Answered by Chris, 2026-10-17, at venue-visit course check K4:

12. **A cue layer's timing and masters, once the add-layer sheet goes?** The cue Looks tab's layer
    settings popover gains them, for new and existing layers alike. A timing step after the
    picker's press, and dropping them under `FU-PRESS-LAYER-MASTER`, were declined.
