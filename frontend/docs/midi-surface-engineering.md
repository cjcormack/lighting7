# The Surfaces view — engineering notes

`/projects/:id/settings/surfaces` (`routes/Surfaces.tsx`). A picture of the attached MIDI desk, an
inspector for whichever control you click, and — under *Edit bindings* — a library you drag onto it.

The plan and the decisions are `lighting7/docs/plans/completed/midi-surface-plan.md`; the layout authority is
`lighting7/docs/plans/completed/midi-surface-design/` (`Main.dc.html` run mode, `Edit.dc.html` edit mode,
`Legend.dc.html` the control-state vocabulary). The backend contract is
`lighting7/docs/midi-control-surface-engineering.md`. This file is the client half: the things that
will bite.

## The picture is data, and the state is what the hardware was told

`SurfacePanel` draws `ControlSurfaceType.layout` — named regions and a grid cell per control — so a
second device is one `.kt` file in lighting7 and no component here. Every state it shows comes from
the `surfaceControls` stream, which is what `SurfaceFeedbackPublisher` *sent*, never a
recomputation from DMX. **If the picture and the desk disagree, the publisher is wrong**, and that
is the bug worth finding rather than papering over here.

`strips` and `layout` are **optional client-side**: a desk on a pre-strip build serves neither, and
this client talks to whatever desk it is pointed at. Without a layout the page falls back to
`BindingMatrix`, which is also the rendering below `md`.

The one place the panel reads DMX is the inspector's **stage value** line, and deliberately: the
other three live lines answer "what is the desk doing with this control" and that one answers "and
what came of it". A fader at 66% over a dimmer reading 0 is a bound control writing into a park.

## `lib/surfaceResolve.ts` is a mirror, and its failure is silent

The wire carries raw binding rows. `ControlSurfaceBindingService.resolve` and `deriveStripTarget`
are what turn those into "what is this control actually doing", and neither is exposed — so the
panel, which labels every control, re-derives both. A precedence read the wrong way round paints a
plausible label for a control the desk drives differently, and no rig check reaches the browser
copy. `surfaceResolve.test.ts` replays the Kotlin cases; keep it in step with
`midi/ControlSurfaceBindingService.kt` and `midi/StripDerivation.kt`.

The order is **the control's own row across both bank levels, then its strip's** — direct before
strip at *both* levels, which an "obvious" bank-first reading gets backwards.

The encoder bank it derives against is an `EncoderBankSelection` — a property and, for a colour, a
`colourAxis` — and the axis goes on the strip's **encoder** and nowhere else. `propertyTarget`
leaves the field *absent* for hue (`withAxis`): that is what every pre-axis row has and what the
server writes, and `toEqual` in the tests tells absent from `null`.

## Editing: one context, two zones, one write

Edit mode is **local state in `SurfacesContent`**, not a Redux slice. The busk view needs a slice
because the cue-slot overlay is a *sibling* of the routed page; here the library and the picture are
both inside this route.

Dnd is the app's single `DndContext` (`components/dnd/DeskDndProvider.tsx`), joined with
`useDndMonitor` and **never nested** — the busk page's rule, and the reason `SurfacesContent` must
be rendered inside that provider (`Layout.tsx` mounts it; the tests mount their own). Ghosts go
through `dnd/dragOverlayRegistry.ts`, registered at module scope.

Four things are load-bearing:

- **Droppables sit on the grid-cell wrapper, never inside `ControlCell`** — that cell is memoized so
  a 20 Hz `surfaceControls` delta re-renders only the controls that moved, and `useDroppable`
  re-renders its host whenever `isOver` flips. **And the wrapper is two components, not one with a
  `disabled` flag**: `PanelRegion` reads `controls[…]`, so it re-renders on every frame, and a
  single wrapper would re-run `useDroppable` for every control on the device at stream rate while
  nobody is dragging. Run mode — the state the panel spends its life in, and which has no drop
  target at all — renders no hook.
- **Legal targets follow the source, told twice.** A **row** lands on a strip and nothing else; a
  **chip** lands on one kind-matching control and nothing else. Enforced by dnd-kit's own
  `disabled` — which keeps `over`, and so the highlight, off a place the drop would refuse — *and*
  by the pure `canLand` in `lib/surfaceDrop.ts`, which is the half a test can reach.
- **A drop patches the control's own row at the exact bank, or creates.** `exactBindingAt`, never
  `resolveControl` or its sibling `activeBindingAt`: those answer a strip's row for a strip's
  control and a global row when no exact-bank one exists, and patching either moves a binding the
  operator was not pointing at. A chip on a strip's fader must *create* a direct row — the only way
  "direct beats strip" is reachable from the UI. A created row takes the **active bank**, because
  that is the bank the panel is drawing.

  The two lookups differ by one fallback and the split is the point: **`exactBindingAt` is the
  write question, `activeBindingAt` the read one.** Anything saying what a slot is *doing* — a
  label, a remove cross, the library's placement badge — must see the bank-agnostic row, which
  really is in force on every bank.
- **One remove cross per row, not per control.** A control's own row is crossed off on the control;
  a strip's is crossed off on the strip's column backdrop, where one cross means one row rather than
  four controls.

### What the router will and will not dispatch

`controlKinds` in `lib/surfaceDrop.ts` is read off `SurfaceInputRouter.matchEvent`, and two arms are
surprises:

- An **encoder with a push note reaches both halves** — its CC is a `Continuous` and its note a
  `ButtonPress`, on one control id. The X-Touch declares a push on all sixteen, so an encoder
  legitimately takes a cue chip as well as a property one.
- A **bank button takes nothing at all**: `route` answers `ResolvedInput.BankButton` and switches
  the bank *before* resolving a binding. A row there can never fire, so the library will not offer
  one and `BankButtonCell` draws its own label whatever row sits on its id — but it draws itself
  **dead** when one does, because the picture's promise is that it mirrors the desk and an orphaned
  row only the inspector mentions is one nobody scanning the surface would find.

A colour axis does not change which half a target reaches — `targetControlKind` switches on the kind
alone — but it is refused on a property that is not a colour, by name, at the same door
(`BINDING_AXIS_NEEDS_COLOUR`, `refuseAxisOnNonColour`). The picker shows its *Colour axis* field only
where the named property is a colour, and **drops the axis whenever the property changes**, so a
colour → dimmer edit cannot save a 400.

`controlKinds` and `targetControlKind` are now the **mirror** of `midi/BindingControlKind.kt`
rather than the only copy: session 4 closed `FU-MIDI-BIND-CONTROL-KIND`, so
`ControlSurfaceBindingService.refuseWrongKind` refuses a mismatch at the write boundary whichever
door it comes through — MIDI Learn, a script, a hand-rolled call. The dim is still worth having: it
keeps the operator from dropping onto a control that would answer a 400, which is a worse way to
learn the rule. Keep the two tables in step; a divergence here only makes the palette offer or
withhold a chip the server would judge differently.

## The desk selection

One desk, one selection, server-owned — the composition model's argument for one programmer,
verbatim. `store/selection.ts` is the cache (no REST behind it; `selection.state` is both the
snapshot and the broadcast), and three surfaces move it: the busk view's target band
(`useBuskingSelection`), the programmer's fixture list (`useDeskSelectionBridge`), and a
`selectTarget` button on the surface itself.

Read `components/fixtures-list/useDeskSelectionBridge.ts` before touching the list half. Its
rules each fail silently, and the first is the one the plan names: **rows are published through
`rowLocateTarget`** (via `selectedRowTargets`), never through the `programmer` scope's `targetKeys`,
which `expandSelectionToTargets` has already flattened to member keys. Publish those and a marquee
over *Front wash* arrives at the desk as eight loose fixtures, with the strip's group select LED
dark.

Since the multi-screen plan's session 1 the selection is one fact with three parts —
`{targets, families, source}` — and the bridge carries two of them. It takes the marquee's `cells`
as well as the row ids and publishes `cellFamilies(cells)` beside the rows (a rows-only selection
publishes *no* mask, which clears the desk's); its echo FIFO is keyed on targets **and** families,
so a frame with the same heads and a different mask is applied (as a row selection) rather than
swallowed as an echo; and its `enabled` is gated on the tab's follow/local flag
(`lib/deskFollow.ts`) in both directions, re-enabling as a fresh mount. `source` is stamped by the
desk from the window this socket announced (`api/windowsApi.ts` — the name and the row id, so the
chip matches by id); a MIDI write stamps
`{kind: "surface"}`, which no chip draws any more — the desk chip is drawn only while its window is unlinked (busk-chrome plan D18) and names no mover. The long form is
CLAUDE.md §"One selection, two shapes" and §"The desk selection has a mask".

## Records on buttons

Session 4 added six targets: `applyLook`, `pressTemplate`, `pressPad`, and the three busk-page ones.
All are buttons. Five things about them on this side:

- **The library rows are not `TargetRowItem`s.** That component exists to mount
  `useTargetProperties` per group or fixture and is memoized on primitive target props for exactly
  that reason; a template, Look or busk page has no per-target property lookup, so its row is built
  in `SurfaceLibrary`'s `rows` memo from `actionChip` beside Selection and Desk.
- **The kind row stays at the artboard's six.** *All · Groups · Fixtures · Looks · Cues · Desk* — a
  template files under **Looks** (the row of named recallable records) and a busk page under
  **Desk** (which already holds the encoder bank), rather than widening a 360px segmented control to
  seven. *Next page* / *Prev page* sit on the Desk row **once**: they are page-agnostic, and a chip
  repeated per page reads as page-specific.
- **A Look with a deferred effect is offered with no chip at all**, and the picker disables it for
  the same reason. `applyLook` presses onto the Look's *own* fixtures, and one with a deferred
  effect has none — the write boundary refuses it by name (`BINDING_LOOK_NEEDS_SELECTION`). The
  row's detail line says *needs a selection*, which is a better answer than a chip that 400s.
- **A template's *Press* chip carries its family; a Look's *Apply* does not.** A template is in
  exactly one family, so the palette's family filter can hide it honestly; a Look spans families by
  nature, so its chip is built by `actionChip` with `family: null` and survives every filter.
- **`describeTarget` will not resolve a uuid, and `recordOptions.ts` is where the name comes from.**
  Same split as `speedMasterBpm` and `useSpeedMasterDisplay`: `describeTarget` is pure and has no
  library to ask. `useRecordBindingOptions` is the one owner of the three lists, shared by the
  picker (which offers them) and the inspector (which resolves the choice) — without it the
  resolution would exist twice and the two would name one uuid differently. The picker starts a
  record field **unset**, not on the first row: an empty uuid is refused by name, where a silent
  first-row default would save and bind the button to something nobody picked.

## Windows and the sub-selection

Session 7 of the busk-further plan mirrored the five BUTTON targets its session 2 added to
`BindingTarget` (D14): `buskFocusSet {windowName, focus}`, `buskSheetToggle {windowName}`,
`selectionNext`, `selectionPrev`, `selectionCells {mode}`. `lib/surfaceDrop.ts` answers `button` for
all five, `describeTarget` names them (*Busk focus · Pads · Screen 2*, *Cells · Odd*),
`BindingHealth` gains `missingWindow` and `describeHealth` its line, and the picker offers them. Four
things about them on this side:

- **A window is addressed by its registry name, never a row id.** `windows.state`'s `id` is
  socket-minted and changes on every reconnect; a binding has to survive one, so it carries the
  `name` the window announced. The desk sends the command to **every** connected row of that name
  (a duplicated name is D9's accepted case), carrying the view each row announced, so a window off
  the busk view ignores it. The picker's *Window* field lists the names signed in now, plus the
  binding's own name when no such window is connected, so a screen that is off tonight still reads
  as what it is rather than as a blank.
- **`missingWindow` is transient**, unlike every other health arm: it clears the moment a window of
  that name announces, and the desk re-evaluates on every registry change rather than only on the
  fixture hooks. Nothing here polls for it.
- **The library splits them as it split the page targets.** *Focus · Split / Pads / Rig* and
  *Sheet* are a row **per window** under *Desk* (`window:<name>`, from `useDeskWindows`, one per
  name), because a chip per window reads as window-specific, which it is; *Next · Prev · Odd · Even ·
  Masters* sit on the Desk row **once**, because they rewrite the one desk selection. The kind row
  stays at six.
- **`selectionCells` has no LED** (`FU-SURFACE-SUBSELECT-LED`): a sub-selection is not a state the
  desk keeps. The mode's label is `SUBSELECT_MODE_LABELS` in `lib/cellsSubSelection.ts`, shared with
  the busk band's Cells chip, so a button and the chip name one rule the same way.

## The showing busk page

`busk.pageState` / `busk.setPage` (`api/buskPageApi.ts`, `store/busk.ts`'s `buskShowingPage` entry)
is the desk's own answer to "which busk page is showing", so a hardware *next page* button and a tab
click are one gesture. `BuskingView` resolves in this order:

```
this tab's offline override  >  the desk's showing page  >  ?page=  >  the first page
```

and a tab click writes the **desk**, with the URL mirroring what comes back. Writing the URL
directly would leave this tab on a page the desk and every other client disagreed about, which is
what the shared state exists to prevent. `null` from the desk is not "the first page" — it means
nothing has moved it, and each client falls back on its own.

The **offline override** is the one thing that beats the desk on purpose. `setShowingBuskPage` goes
through `sendGesture`, which drops the frame and toasts when the socket is down — so without a local
escape hatch a tab click while offline would do nothing at all and the desk's last-known value,
however stale, would keep winning. `onPageSelect` sets it *only* on that failure, and any change to
`deskPageId` — a reconnect delivering the real answer, or this tab's own next successful click —
clears it again.

It is **unrelated to `busk.layoutChanged`'s echo suppression**, despite the namespace: that frame
names pages whose *document* changed and is keyed on a page being saved. A page-state frame carries
no layout.

## `hooks/useTargetProperties.ts`

Closes `FU-FE-USE-TARGET-PROPERTIES`, and lands as **two** exports rather than one hook, because the
consumers want different halves: `categoriseProperties` (pure, generic, narrowing) for the surfaces
that *render* properties and need the descriptors, and `useTargetProperties` / `useRigProperties`
(flat) for the surfaces that *bind* them and need names plus "can a fader drive this".

`continuous` mirrors `PropertyChannelResolver`: sliders and colours only. A position pair or a
setting on a fader would be a control that does nothing, so the library does not offer one.

**A colour descriptor expands to the colour plus its bundled emitters.** The fixture descriptor
list omits a `bundleWithColour` slider — `white`, `amber`, `uv` — and folds its channel into the
colour's `whiteChannel` / `amberChannel` / `uvChannel`; the desk drives each as a slider by that
name, so the hook mints one `AvailableProperty` per present emitter (a group has one if any member
does). The name is the category, which lighting7's `BundledEmitterNamesTest` pins across every
fixture type. `SurfaceInspector`'s stage line mints the same descriptor off the colour's channel ref
for those three names.

## Colour axes

A colour on a continuous control is one of four HSV axes — hue, a fine hue trim, saturation,
brightness — and `lib/colourAxis.ts` is this side's whole vocabulary: the four wire strings
(mirroring `midi/ColourAxis.kt`, pinned by `colourAxis.test.ts`), the chip labels and swatches,
and the one rule everything compares through: **null is hue, and so is `'hue'`**. A binding never
carries the field for hue; `withAxis` is how the picker, the library and the resolver keep it
absent. This module labels and draws and never resolves — what a trim or a saturation position does
to a head is `PropertyChannelResolver`'s in both directions, and the *Position* line on the
inspector's live card is already that reading.

The library offers a colour as **four chips**, one per axis with its own swatch, labelled by the
axis alone when the target has one colour property; the Selection and Encoder bank rows get the
same four from the rig vocabulary. `describeTarget` appends ` · sat` and the like for every axis but
hue, so a hue binding reads exactly as it always did.

## The library's sections and family groups

The list is one sticky `EditorLabel` per kind that has rows, in kind-row order — **Desk · Groups ·
Fixtures · Looks · Cues**, Desk moved to the front of both because the Selection row is what a
selection-driven desk reaches for first and it used to sit under every fixture. Under a kind filter
the one section shows without its heading. Inside a row the chips are grouped by family with a
hairline between groups (`groupChipsByFamily`, `TemplateStrip`'s split sideways) and the actions
last, which is what keeps a fixture row legible with four colour chips and the emitters beside them.
The search matches chip labels as well as names; a target row's chips are known only inside
`TargetRowItem`, which finishes the search itself, and a section whose rows all answered null hides
with its heading (`:has`).
