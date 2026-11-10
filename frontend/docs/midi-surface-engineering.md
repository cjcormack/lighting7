# The Surfaces view — engineering notes

`/projects/:id/settings/surfaces` (`routes/Surfaces.tsx`). The attached MIDI desk as a picture, with
an inspector for whichever control you click or touch on the desk (**editor A**), or the same bindings
as a table (**editor C**), the only view below tablet width.

The plan is `docs/plans/completed/surface-setup-plan.md` and its record
`docs/plans/completed/surface-setup-design/`; the earlier picture's layout
authority is `docs/plans/completed/midi-surface-design/`. The backend contract is
`docs/midi-control-surface-engineering.md`. This file is the client half: the things that will bite.

## The desk is the only resolver (P5)

A binding is `{does, on, how}` at a slot `(device type, control, part, layer)` — `SurfaceBinding` in
`api/surfacesApi.ts`, mirroring `midi/Binding.kt`. **Nothing on this side resolves one.** Three things
the desk serves replace what the client used to compute:

- **`surfaceMap.state`** (`store/surfaces.ts`' `surfaceMaps` / `useSurfaceMap`): per device type, per
  layer, per control part (`"btn-36:MAIN"`), what the part resolves to — `from` (`DIRECT`, `JOB`,
  `NONE`), the row's `bindingId` and `bank`; on a `JOB` part its `jobId`, `jobRegion`, `item` (the
  slot, 0-based) and `jobLabel` (*Groups as channel strips · item 3*), and on a `DIRECT` part `beats`,
  what the job it beats would derive there; `does` / `on` / `how`, the catalogue `entry`, the `label`
  (records by name: *GO · Act 1*), `subjectLabel`, `colour`, `writesTo`, the `shows` sentence, `health`
  and `healthText`. The frame's `jobs` is every job stored for the type, its items named. Only the
  current project's maps are sent; the Surfaces tab is on the rig only.
- **`GET /surface-functions`** (`useSurfaceFunctionsQuery`): the function catalogue, read from the
  desk's specs — each entry's id, label, group, the inputs and subjects it takes, its Hows, its
  parameters with their options, where it writes and a line of help. The form draws any function from
  it, so a new function needs no screen work. An entry is what the operator picks; two functions are two
  entries each (a grand master with a level, a tempo with a BPM), and the map's `entry` says which a
  stored binding is, so the client never matches a binding to an entry itself.
- **The slot write** (`useWriteSurfaceSlotMutation`, `useClearSurfaceSlotMutation`): `PUT` / `DELETE
  …/surface-bindings/slot` with the slot in the body. Whatever the slot holds becomes the binding, and
  the desk refuses what the function's spec does not admit. Nothing patches a cache: the map comes back
  on the socket.
- **`surfaceUses.state`** (`store/surfaces.ts`' `useSurfaceUses`): the map's reverse index, every
  control part acting on each group (`group:<key>`), fixture (`fixture:<key>`) and record
  (`record:<KIND>:<uuid>`, `surfaceUseKey`), a record a function names in a parameter (a palette's
  press) included, each with its place in words (*Button 36 · A*, *Encoder 9 push*), what it does there
  and whether a job derives it. It is read **straight off the socket** with `useSyncExternalStore`, not
  through RTK Query, because the doors that draw it — sheets, menus, a stack card — are mounted in tests
  and hosts with no store; the hooks call the API optionally for the same reason.

`src/test/resources/surfaces/catalogue.fixture.json`, `map.fixture.json` and `uses.fixture.json` are the desk's answers,
pinned by lighting7's `SurfaceCatalogueTest` and read here (`components/surfaces/surfaceFixtures.ts`)
by every surface test, so a change to the wire fails on both sides.

Gone with that: the drag library (`SurfaceLibrary`), the 48-kind form (`BindingTargetPicker`), the
phone table (`BindingMatrix`), the Learn overlay, `lib/surfaceResolve.ts` and `lib/surfaceDrop.ts`
(the resolve order and the kind tables), `lib/healthDescriptor.ts`, `lib/colourAxis.ts`, the old
union's types, the label and colour helpers that read it, and edit mode with its drag-and-drop.

## The picture is data, and the state is what the hardware was told

`SurfacePanel` draws `ControlSurfaceType.layout` — named regions and a grid cell per control — so a
second device is one `.kt` file in lighting7 and no component here. What a control *does* is the map's,
for the layer being viewed: its main part's label, else *Push · …* for an encoder bound only on its
push. Every live state comes from the `surfaceControls` stream, which is what `SurfaceFeedbackPublisher`
*sent*, never a recomputation from DMX. **If the picture and the desk disagree, the publisher is
wrong.**

`ControlCell` is memoized: a `surfaceControls.changed` delta names only the controls that moved and the
WS layer merges rather than rebuilds, so an untouched control keeps its identity at 20 Hz. A map frame
arrives at operator rate.

## Layer A / B, viewed (D13, D17)

The header's chips (`BankSwitcher`) say *Layer A* and *Layer B* — always with the letter — and **view**
a layer in both editors. They send nothing: the desk changes layer on its own key, or on a *Switch
layer* binding. While the viewed layer is not the one the device is on, the picture's live state is
drawn faint (`data-live-dimmed`) and a line says so. A device starts on **no layer** after a desk
restart, where only the bindings on both layers fire: the tab then views the map's `""` entry, a *No
layer* chip carries the device's dot, and a line says the desk's own layer key switches it. A *Bound
on* chip's link (`?type=&control=&layer=`) picks a device of the type and the control and views the
layer too, and moves nothing on the hardware.

## Editor A: the inspector

`SurfaceInspector` is the per-part editor: one block per part (*Move*, *Turn* and *Push*, *Press*),
each a `BindingForm` over the part's slot, its *Writes to* word, its layer chips (*Both layers*, *A
only*, *B only*) and the map's `shows` line; a dead part says why. A control a job derives says
*From Groups as channel strips · item 3* (the map's `jobLabel`) and offers **Make this control its
own**, which writes the job's derived binding at the control's own slot on the viewed layer; a control
of its own over a job says *Bound by hand over* the job (`beats`). The header names the region and slot
the control sits in (`jobPlaceOf`) and *Its job…* opens Editor B on that region. Moving a
binding between layers writes the new slot first and clears the old one only once that landed.
**Remove** names what it takes off — *Remove from both layers*, *Remove from Layer B only* — from the
banks of the rows it clears, and a clear the desk refuses is toasted, in both editors.

`BindingForm` asks **Does · On · How** in that order: Does is the catalogue, grouped and searchable,
filtered to the part's input; then its parameters; On from the visible groups and fixtures, the
selection, and the records of each kind (`recordOptions.ts`, `useBindingChoices`); How from the entry's
own Hows, or *Pick up · Jump* on a continuous part (a motor fader's is a sentence). A function picked
without a subject it can fill is held as a draft — *Choose what it acts on to bind it.* — and written
only once whole. Every binding names where it writes in one word, *Writes to Local* and its four
siblings (D8).

**Identify mode** (D14) is *Touch a control on the desk*: `useIdentify` sends `surfaceIdentify.begin`,
draws the banner while the desk waits, and selects the control the `surfaceIdentify.captured` frame
names. The desk captures that touch **without dispatching it** — a bound GO is not fired — and sends it
to this window only; 30 s without a touch ends the wait.

## Editor B: jobs

The view's *Jobs* tab (surface-setup plan D11, D12) draws the picture with `SurfaceJobsPanel` beside
it: a card per **job region** the profile declares (`ControlSurfaceType.jobRegions`), the focused one
tinted on the picture (`SurfacePanel`'s `highlighted`) and a click on the picture focusing the region of
the control under it. Layer chips — *Both layers*, *Layer A only* — choose which layer's jobs the cards
edit. Each card's select is the kinds that fit the region, from `GET /surface-job-kinds` and the region's
own `kinds`, after *Nothing: bind each control yourself*; under it the kind's help line, then its items
in slot order, each moved with up and down and taken off with ×, and an *Add* select drawn from what the
kind takes — groups, fixtures, records of one kind (`itemOptions`, read back by `itemOf`), or for a
kind of functions a `BindingForm` limited to the kind's catalogue entries, held until its *Add* button so
the subject and parameters are chosen first (the form reports a whole binding as soon as it is one). ×
leaves a gap: the item becomes null in the list sent, drawn as *Empty* in its place, so every later item
keeps its slot; *Add* fills the first gap, else appends, and the desk trims trailing gaps. A region with no
gap and every slot filled says *Every slot has an item.* Every change is one `PUT …/surface-jobs/{type}/{region}` of the whole job; *Nothing* is its
`DELETE`. A new kind keeps the items only where it holds the same sort. The cards read the job and its
items' names and health from the map frame's `jobs`, never from a copy of their own (P5); a job this
desk cannot read says it is kept and does nothing.

## Editor C: the mapping sheet

`SurfaceSheet` is one row per control part on the sheet kit, grouped by the picture's regions: Control · Layer · Does · On · How · Writes to · Shows · Health. *Does* is the compound
cell, editing through the same `BindingForm`. *On* takes a marquee: a subject set over many rows, each
row taking it only where its function takes that kind of subject — the kit's `SheetColumn.accepts`,
which drops the refused rows before `write` and names them through `refused`. **Fill in order** is the
bar's *Fill*, a `records` plan on `SpreadPanel` beside the address plan: groups, fixtures, cue stacks or
speed masters laid down one to a row in their order, or the selection, a row that cannot take one
skipped and named (`planFill`). A control its job derives has no *On* of its own: the cell reads out
the job's item, its layer reads *Job · Both*, and a marquee or a fill skips it (*follows its job*). *Dead first* brings the dead rows to the top. Live values are never in
the rows, so a fader moving never re-renders the sheet.

## The desk selection

One desk, one selection, server-owned — the composition model's argument for one programmer,
verbatim. `store/selection.ts` is the cache (no REST behind it; `selection.state` is both the
snapshot and the broadcast), and three surfaces move it: the busk view's target band
(`useBuskingSelection`), the programmer's fixture list (`useDeskSelectionBridge`), and a
`select` binding on the surface itself.

Read `components/fixtures-list/useDeskSelectionBridge.ts` before touching the list half. Its
rules each fail silently, and the first is the one the plan names: **rows are published through
`rowLocateTarget`** (via `selectedRowTargets`), never through the `programmer` scope's `targetKeys`,
which `expandSelectionToTargets` has already flattened to member keys. Publish those and a marquee
over *Front wash* arrives at the desk as eight loose fixtures, with the group's select LED
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

## Editor D: *Assign to surface…*

Each record's own door onto a control (surface-setup plan session 6): stacks (the stack row's menu),
cues (`CueActions`, its place then the cue — GO TO and Standby, then a loose hold), busk pads and pages,
Looks, palettes, speed masters, and fixtures and groups (the fixture and group sheets, a rig tile's
menu). A menu door is `AssignToSurfaceMenuItem`, drawn in a dropdown or a context menu through
`components/menus/menuAdapter.ts` (the adapter *Copy OSC address* uses), with *Bound on …* under its
words; a sheet's is `AssignToSurfaceButton` beside a `BoundOnChip`. Every door opens one sheet,
`AssignToSurfaceHost` in `Layout`, through a module store (`openAssignToSurface`), so a menu that closes
on select does not take the sheet with it.

The sheet waits for a touch on the desk at once (`useIdentify`, D14: captured, never fired; *Cancel*,
and *Nothing was touched.* after 30 s) — except in remote mode, where a click on the picture is the only
way and no wait starts. A touch or a click picks the control and the first part anything fits; an
encoder's *Turn* and *Push* are chips. What it offers is `assignOffer.ts`'s `fitEntries`: the catalogue
entries the part's input reaches whose subjects include the record's kind — narrowed to that kind, so
*On* offers the record alone — or whose parameter names a record of the kind (a palette's press, on the
selection, the palette filled in), a programmer verb on a record (Record into a stack) after what plays
it. The rest is `BindingForm` over that narrowed catalogue, held in the sheet until *Assign*, with the
sheet's own Layer chips (*Both*, *A only*, *B only*). The slot's heading and *Replaces …* are the desk's
answer, `GET …/surface-bindings/slot/preview` (`useSurfaceSlotPreviewQuery`, asked again whenever the
type's map frame moves): the binding the write replaces, one layer's binding that keeps beating a
both-layers assign, a both-layers binding a one-layer assign beats there, a job it takes the control from.
A slot on a type no longer offered is dropped, a layer the shown profile lacks reads as *Both*, and each
open is a fresh sheet; a wait begun on open and abandoned before the desk answered is cancelled when the
answer arrives. A record of a show other than the current
one assigns nothing: surfaces are bound in the current show.

**Bound on** (`BoundOnChip`) replaced `BoundControlBadge` and `FixtureBoundControlsRow`, whose fixture
and group rows read binding rows and so never saw a job. It reads `surfaceUses`, so a strip a job derives
counts, and a fixture's chip counts its heads' bindings too (`SurfaceAssignRow`'s `also`).
