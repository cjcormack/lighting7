# The Surfaces view — engineering notes

`/projects/:id/settings/surfaces` (`routes/Surfaces.tsx`). The attached MIDI desk as a picture, with
an inspector for whichever control you click or touch on the desk (**editor A**), or the same bindings
as a table (**editor C**), the only view below tablet width.

The plan is `docs/plans/surface-setup-plan.md` (sessions 1–3 here; jobs, new functions and *Assign to
surface…* come later) and its record `docs/plans/surface-setup-design/`; the earlier picture's layout
authority is `docs/plans/completed/midi-surface-design/`. The backend contract is
`docs/midi-control-surface-engineering.md`. This file is the client half: the things that will bite.

## The desk is the only resolver (P5)

A binding is `{does, on, how}` at a slot `(device type, control, part, layer)` — `SurfaceBinding` in
`api/surfacesApi.ts`, mirroring `midi/Binding.kt`. **Nothing on this side resolves one.** Three things
the desk serves replace what the client used to compute:

- **`surfaceMap.state`** (`store/surfaces.ts`' `surfaceMaps` / `useSurfaceMap`): per device type, per
  layer, per control part (`"btn-36:MAIN"`), what the part resolves to — `from` (`DIRECT`, `STRIP`,
  `NONE`), the row's `bindingId` and `bank`, `does` / `on` / `how`, the catalogue `entry`, the
  `label` (records by name: *GO · Act 1*), `subjectLabel`, `colour`, `writesTo`, the `shows` sentence,
  `health` and `healthText`. Only the current project's maps are sent; the Surfaces tab is on the rig
  only.
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

`src/test/resources/surfaces/catalogue.fixture.json` and `map.fixture.json` are the desk's answers,
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
layer* chip carries the device's dot, and a line says the desk's own layer key switches it. The
`?binding=` link minted by the fixture and group sheets' badges views its binding's layer too, and moves
nothing on the hardware.

## Editor A: the inspector

`SurfaceInspector` is the per-part editor: one block per part (*Move*, *Turn* and *Push*, *Press*),
each a `BindingForm` over the part's slot, its *Writes to* word, its layer chips (*Both layers*, *A
only*, *B only*) and the map's `shows` line; a dead part says why. A control a strip derives says
*From strip 3* and offers **Make this control its own**, which writes the strip's derived binding at the
control's own slot on the viewed layer; the strip's subject is a *Strip 3 follows* select. Moving a
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

## Editor C: the mapping sheet

`SurfaceSheet` is one row per control part on the sheet kit, grouped by the picture's regions with the
strips first: Control · Layer · Does · On · How · Writes to · Shows · Health. *Does* is the compound
cell, editing through the same `BindingForm`. *On* takes a marquee: a subject set over many rows, each
row taking it only where its function takes that kind of subject — the kit's `SheetColumn.accepts`,
which drops the refused rows before `write` and names them through `refused`. **Fill in order** is the
bar's *Fill*, a `records` plan on `SpreadPanel` beside the address plan: groups, fixtures, cue stacks or
speed masters laid down one to a row in their order, or the selection, a row that cannot take one
skipped and named (`planFill`). A control its strip derives has no *On* of its own: the cell reads out
the strip's subject, and a marquee or a fill skips it. *Dead first* brings the dead rows to the top. Live values are never in
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
