# Busking

Looks, palettes and layers, the busk layout, focus and the side sheet, the rig and the hand. Moved out of `frontend/CLAUDE.md`, headings unchanged, so a citation of `CLAUDE.md §<heading>` lands here.

### Looks, palettes and layers

**Two library entities, and a Layer applies either.** A **Look** composes cues: any families, its
own fixtures, its own FX, added to a cue's stack at a declared position. A **Palette** is
**one named thing** of exactly one attribute family, with no targets of its own, applied to a
selection — either a value, *or* one FX, never both (`fx-templates-plan.md` D1). Which of the
two it holds is `kind`, and it is fixed at creation the way the family is; three rules keep the
FX half narrow, and each removes a way for it to complicate the cook: **a value or an FX**,
**one FX** (several together is what a Look is for), and **always
generic** (an FX fans over whatever the layer names, so `isGeneric` is true for every one).
Every family may hold an FX — a **Beam** one a Wave naming the beam attribute it drives (zoom, iris,
frost or focus: the palette editor's *Attribute* select; fx-settings plan D5) or Slot Steps naming its setting, and any family Palette Steps
over that family's palettes (session 7, `lib/stepFx.ts`'s `stepFxPaletteShape`) — and the
backend refuses `controls` **by name**, since it names no family. Backend contract in `../docs/lighting-composition-model.md` §"Looks and layers"
and `models/palettes.kt`; the completed records are
`../docs/plans/completed/looks-and-layers-plan.md` and
`desk-simplification-plan.md` §Session 3.

They were one entity until session 3, split on the row's targeting mode: a **bound** Look behaved
like an old named palette bank, a fully-**deferred** one like a preset. That is now two tables, and the reason it
had to be is the design's own best example — a *per-fixture* palette (eight heads aimed at one
spot hold eight different pan/tilts) has only bound rows, so `hasDeferredRows` could never have
told the two apart. What the split deleted: `editorFixtureType`, `LookEditor`'s synthetic-fixture
value grid, `LookDraftContext`, `LookLivePreview`, `syntheticFixture.ts`, `EditorContextValue`'s
`look` arm, and the type gate in `compatibleIdsFor`.

**A Look row is always bound** — `validateLookRows` refuses `deferred` — and a Look is always
*recorded*, from the programmer or by promoting a selection, which is why `/looks` has no New
button and one editor (`LookDetailSheet`, read-only about values on purpose; read its doc comment
before adding a value grid; it lists the Look's FX read-only too, and Include runs them in the
programmer, each on its own target, for Update to write an edit back onto). A Look **FX**
names its own target too (absolute-looks plan D2), so a Look needs no selection anywhere it is
pressed — a busk pad, a cue slot, a surface button, the hand — and `LookSummary` carries no
`hasDeferredFx`.

**A Look is an absolute state** (absolute-looks plan D1): pressed, it lands its own rows, FX and
scenery, never narrower and never wider, and its layer names no targets. To apply something to the
selection, use a palette. So no sender puts a Look's press on the selection — `usePress`,
`ApplyPicker`, `CueRowStore.pressLayer`, `record-look`'s `asLayer` and the hand send no targets and no mask
for one — and the desk refuses a stored write that would give a Look's layer targets by name,
`LOOK_LAYER_TARGETED` (P2; a cue's layers, `programmer.addLayer` / `patchLayer`, the Timeline's ops,
MCP's cue layers). A Look layer draws no target chips (`LookStack`, the Stack table's Targets
column) and has no *Targets* button, and a focused Look layer has **no write guard**: every write in
the grid lands in the Look (`LookRowStore`). Its mask is still a layer setting (D3) — the layer
popover, the cue editor's Looks tab and the Timeline's *Settings…*.

**A palette stores an intent, not a literal**, resolved per head at cook: a colour plus a
white/amber policy, a level or beam role as a percentage of each head's own range, a position
**centre-relative** (`pos:pan,tilt`, `-128..127` about the centre of travel, the same fraction of
travel on every head — venue-visit plan D3). A position stored before D3 is `deg:` travel degrees:
the desk keeps resolving it as angles, an editor shows it on the centre scale (a generic one through
the old editor's 540° / 270°, a per-fixture row through its own head — `positionOnScale`) and saves
it as `pos:` only once it is moved (P3). `fx/PaletteIntent.kt` owns the grammar and `lib/paletteIntent.ts` mirrors it — the
client half serialises and parses only, and **never resolves**, because
`fx/PaletteResolver.kt` must be the single answer to what the rig will do (§6 of the plan). All
three consumers go through it: cook, `POST /palettes/{id}/apply`, and the editor's resolves-to
panel via `POST /palettes/resolve`. Two deliberate degradations in that grammar are documented in
`PaletteIntent.kt`; do not "fix" either by teaching the literal parsers about intents.

**The one literal is a bundled emitter — `dmx:180`.** White, amber and UV are rows of the closed
vocabulary in their own right, family COLOUR, and the departure from "an intent, not a literal" is
deliberate: an emitter has no range to be a proportion of and no room to be a position in, so there
is nothing to re-derive per head. Keep the `dmx:` prefix — a bare `180` is what the *literal* parser
reads, so the two grammars would agree on some rows and silently disagree on others. Rows rather
than fields on the colour intent, because each then writes only its own channel: that is what lets a
UV-only palette sit **over** an amber wash instead of replacing it, and it is the only way to say
"UV at 200" at all, since no `WhitePolicy` has ever driven UV. An explicit `white` or `amber` row
**forces the colour row to `rgbonly`** — the policy drives the same byte — which the one rows
builder enforces by rewriting the saved colour row's policy (not just the button's variant: showing
RGB only while the value still said `extract` produced an editor displaying a palette it could not
save) and the write boundary refuses by name. UV is exempt. The builder is `paletteRowsFromValues`
in `components/templates/familyControls/paletteRows.ts`, through `effectiveColourPolicy`;
`ColourControl` reads the same derivation **for display only**, and both hosts of the family
controls — `PaletteEditor` and the palette sheet's Value cell — save through the builder, so
neither can send the refused pair (§Library sheets).

**A colour palette refuses as a whole**, and this is the one place compatibility is finer than D6.
A head missing any emitter the palette names takes *none* of its colour rows —
`PaletteResolver.unmetColourRequirement`, folded in by cook, apply and the resolves-to panel alike —
because the rows are facets of one output and half a colour is a wrong answer, not a partial one.
Scoped to COLOUR: zoom and frost are independent roles and keep their per-row skip.
`PaletteSummary.requiredEmitters` is the derived form the client filters on, paired with
`targetEmitters` in `fixtures-list/rowModel.ts` — a **union** over the selection, like
`targetFamilies` beside it, so a mixed selection still offers the palette and the head that cannot
take it reports a skip. `targetEmitters` must read the **colour descriptor's** `whiteChannel` /
`amberChannel` / `uvChannel`: bundled emitters are omitted from the flat descriptor list, so
scanning categories the way `targetFamilies` does finds no emitter on any head.

**A palette's attribute vocabulary is closed** (`PaletteAttribute`), and that is where "a palette
cannot carry a gobo" actually lives: gobo, colour-wheel and macro slots are per-model, so they are
refused by name at the write boundary and shown *disabled with the reason* in the beam editor
rather than omitted. The three emitters are *in* the vocabulary — not slotted, and the only
per-model question about them is presence. Compatibility is otherwise **capability-only** (D6):
"does this head have colour at all", never "was this authored against that model".

There is **no stored attribute type on either**. `LookSummary.families` is derived server-side from
the rows, so a Look spanning colour and position reports both. A palette's single family is derived
the same way and validated to be exactly one at the write boundary. That is why the **family filter
lives on `/palettes`, not `/looks`**: a palette is in exactly one family, so a family is an exact
partition; a Look spans families by nature, so filtering by one would hide most of the library from
most filters.

`src/lib/attributeFamily.ts` owns the family vocabulary and mirrors the backend's
`AttributeFamily`, one name on both sides; `maskPicker.test.ts` pins the two lists against each other, and
`paletteIntent.test.ts` pins the palette vocabulary the same way. It caught a real divergence
already: `Number('')` is 0 where Kotlin's `toDoubleOrNull()` is null, so the client read `pct:` as
0% while the server rejected the row.

**Within a cue, later layers win — for every attribute, intensity included**, and the cue's
own `attributeAssignments` are the last layer and beat all of them. Across cues, HTP still
governs intensity. That flip is the change an operator coming from presets is most likely to
be surprised by, so it is said out loud rather than left implied: the programmer's Stack table
lists **top wins** — *Your values* first, then the layers strongest to weakest, its footer saying
*· top wins* — and `LookStack` keeps the sentence as its `precedenceNote` paragraph.
A layer's `sortOrder` is authoritative, not its array position: two layers sharing one leaves the
tie to insertion order in the cook step. The programmer stack asks the server to move a layer and
takes the order back; the cue editor's Looks tab reorders client-side and so restates **every**
`sortOrder`, not just the two it moved (`components/cues/editor/cueListEdits.ts`'s `restated`, the
rule the deleted `reorderCueLayers` / `densifyCueLayerOrder` stated).

**Layer order does not govern the value/FX boundary**, and per-layer `stomp` is the escape
hatch. FX are Tier 3 and values Tier 4, so a lower layer's colour *FX* beats a higher
layer's static colour whatever the order says; `stomp` on the higher layer switches off the FX
of every layer below it, on every attribute it asserts. It is **suppression, not removal** — the
instance keeps running, so clearing the stomp brings it back mid-phase — and it applies to the
programmer stack as well as to a cue. `LookStack`'s `onSetStomp` is the toggle, and a read-only row
draws a badge instead; the Stack table's Settings column and its settings popover carry it for the
programmer. Backend contract in `../docs/lighting-composition-model.md`
§Stomp, which is also where the *other* stomp lives — the cue-level, cross-cue, removing one. Don't
conflate them.

`buildCueInput` rebuilds `layers` and `triggers` **field by field**, and its comment says why. A
field missing from that rebuild is dropped on every inline cue edit; `cueUtils.test.ts` pins every
field of both individually rather than by deep-equal — a deep-equal against a fixture built in the
test file would pass just as happily with the same field missing from both sides. `adHocFx`
is spread rather than rebuilt, so a read-only field on it has to be stripped by name: the FX's
`uuid`, which the desk answers so a client can name one FX across writes and ignores on write,
is, and `cueUtils.test.ts` pins that too.

**The `ref:{uuid}` value grammar is gone** — retired in session 4, on both sides at once,
because a client cannot render rows a server still produces. A cue or the programmer depends on
a Look through a **layer**, which names it by FK, and a layer's `attributeMask` is what expresses
"this attribute comes from that Look". What went with it: `parsePaletteRefUuid` /
`isPaletteRefValue` / `serializePaletteRef`, `ProgrammerEntry`'s five `palette*` fields,
`CellPaletteRef` and `describePaletteRef`, `PaletteRefNotice` and the four cell editors' notices,
the `FixturesTable` reference rail and its `Link2` corner glyph (the `Layers` glyph beside it
stays), the two `missingPalette*` health arms on both `AssignmentHealth` and `BindingHealth`,
`refRowCount`, and the programmer-wide Make Hard with its dialog.

Two things survived it on purpose. **`validateLookRows` still rejects a `ref:`-shaped value** at
the Look write boundary, as an inlined shape check with its own local constant — that rejection
*is* `FU-LOOK-NESTED`'s non-recursion guarantee, and it must not be deleted along with the last
reader of the grammar. And **`StateMigrations`' `removePrefix("ref:")` is the upgrade path**, not
dead code: it folds a v4 database's ref rows into layers. `LooksMigrationTest` spells the old
form out locally for the same reason.

`LookRefBadge` became **`LookNameBadge`** and changed more than its name: chain iconography and
"References …" titles both misdescribe a layer, so it draws `Layers` and names the Look plainly. It
takes an `isPalette` flag since session 3 and swaps the glyph for `Palette` — same size, same
shape, because the two sit in the same list at the same rank and a louder chip would make one look
more important. **Never mint a `P<n>` short code for either**; display the name.

**"Palette" is what this codebase calls the record `models/palettes.kt` stores** — a named value or
one named FX, one attribute family, applied to a selection (venue-visit plan D2). Everything a
person or Claude reads says palette: the copy, the `/palettes` route (`/templates` redirects, search
kept), the REST paths, the `paletteListChanged` / `palettePressed` frames, the `PALETTE_*` error
codes and the `create_palette` / `apply_palette` MCP tools. So does storage since terminology plan
session 4: the `palettes` tables, the sync archive's `palettes/` folder, `pal:` references and the
`pressPalette` binding. **So does the code since session 5:** JSON field names (`paletteId`),
`missingPalette` health, a layer's `PALETTE` kind and the TS and Kotlin identifiers — `PaletteSheet`
draws the palette library. The `components/templates/` folder keeps its old name.

The word had two earlier senses, both gone. One was the positional ordered colour list FX parameters
indexed as `P1`/`P2`/`P*`, scoped `look > cue > global`, which went with `PalettePanel`,
`CuePaletteEditor`, `ActiveStackPalettes`, `CuePaletteBar`, the `palette` column on cues / stacks /
Looks, `Cue.updateGlobalPalette`, `FxState.palette` / `stackPalettes`, the whole `PaletteSocket`, the
old `set_palette` AI tool and `isPaletteRef` / `parsePaletteIndex` / `resolveColourWithPalette`. The
other was the *named* palette entity that became a Look in session 4, with its retired `ref:` grammar
(above). Don't reintroduce either. The busk view's edit-mode library keeps the word in its
identifiers (`LibraryPalette.tsx`, `RigPalette.tsx`, the `busk-palette` and `palette:` drag ids), but
its copy says Library and Rig and this document calls it the library. A lucide `Palette` icon, a
colour picker's swatches and a 3D material list are unrelated.

**An FX colour parameter names a palette instead** — `pal:{uuid}`, whose helpers are
`isPaletteRef` / `parsePaletteRefUuid` / `serializePaletteRef` in
`components/fx/colourUtils.ts`, mirroring `fx/PaletteColourSource.kt`. Five things about it:

- **This half serialises and parses only, and never resolves.** Same rule as `paletteIntent.ts`,
  for the same reason: `PaletteResolver` must be the single answer to what the rig will do. The
  backend's `resolveColourGeneric` resolves a colour intent *without a head*, because an FX's
  output is one colour applied to every head it targets — so it resolves as though the head were
  RGBW, which makes an FX-referenced palette identical to the same palette applied as a layer on
  any RGBW/RGBWA head. **A head with no white emitter pays for that**, and by more than a stop:
  the neutral is already out of RGB and its white byte is dropped, so `#FF9D4A` arrives as
  `#B55300` — dimmer *and* more saturated, which is worse than the RGB-only reading rather than
  equal to it. Accepted trade, documented at `resolveColourGeneric`, one line to invert.
- **A value may hold one too** (lighting7 palette-references plan D1): a cue row, a Look row and a
  programmer entry may be `pal:{uuid}`, which the desk resolves **per head** rather than to one
  colour, answering the resolved value beside the reference (`reference`, `resolvedByHead` on a
  row; `reference` on a programmer entry, typed as `api/paletteReference.ts`). This side never
  resolves one. `validateLookRows` still refuses `ref:`. **A reference is drawn as one** (that plan's
  D18–D21): `CellOwnership.reference` (`useRowOwnership`, counted by head) and a focused Look's or a
  cue's own rows give the Values grids a 3px teal edge over the owner's ring and a pill naming the
  palette (`fixtures-list/CellMarkPill.tsx`) — *DSR Focus · 2 of 4* and a dashed edge over part of a
  row, a dashed pill while unresolved — and a Look layer's value its own violet edge and `Layers`
  pill; the fixture sheet's `SourceChip` names the palette in teal. The Look sheet draws a reference
  row as `LookReferenceChip` (`looks/lookValueChips.tsx`: teal, the name and what it resolves to,
  dashed while unresolved), and it and the cue editor's Values tab say *2 values follow DSR Focus*
  over **Copy the values…** (`looks/CopyTheValues.tsx`: the picked chips, or the rows the Values
  selection reaches — `cueReferencesIn` — else every reference, one question, then `POST
  /looks/{id}/harden` or the cue store's `hardenRows` in its save chain). `LookSummary.referenceCount`
  is what the Looks sheet's Contents and a busk pad's face count; `preview` holds literals only. A cue's reference arrives as a
  uuid alone (its provenance), named from the library (`usePaletteNameByUuid`). A per-fixture
  palette's chip counts its reach over the press (`lib/paletteOffer.ts`'s `paletteReach`, the desk's
  `paletteHeads` / `omittedHeads`) and its hold menu names the heads it leaves out.
- **Only generic colour *value* palettes are offerable.** `family === 'COLOUR' && isGeneric`
  holds on both sides — a per-fixture palette holds no single colour, so there is nothing for a
  fixture-agnostic output to take. The third clause was `rows.length === 1` and is now
  `kind === 'value'`, which is exactly the swap that clause's own docblock said to make if it were
  ever relaxed: it excluded an FX palette only by the accident of holding no rows. The count
  went because a colour palette may hold a hex *and* explicit emitters, and both sides now fold the
  whole colour-family row set into one colour — `paletteRowsSwatch` here, `resolveColourGeneric`
  there. **Never read `rows[0]` for a palette's swatch**: row order is authoring order, so a
  palette whose `uv` row sorted first was drawn purple under an amber name. All three exclusions
  are pinned in `FxColourPalettes.test.tsx`.
- **There is no successor to `P*`.** A palette holds one colour, so there is no set to expand; a
  colour list is an explicit ordered mix of literals and references. `FxColourListPicker`'s
  "Use entire palette" checkbox (the positional sense) and its `savedValue` machinery went with it.
- **`pal:` rather than `ref:`** because `ref:{uuid}` is retired *and* still actively rejected at
  the Look write boundary — reusing it would collide with a live check.

`FxColourPalettes.tsx` owns both halves of the UI: `useColourPalettes()` (the offerable list plus
the three lookups a picker needs to draw a reference) and `FxColourPaletteRow` (the chips, plus
**Save `<hex>` as palette…**, which fills the library the way Record ▾'s *A new palette* does);
`FxColourPicker` hands the row to the colour editor (`ColourEditorCore`) as its `references` band
(venue-rig-fixes plan D18), and Colour Cycle's list draws none. Both pickers read `projectId` from the **route**, not a
prop — the FX library page has no project, and there the row simply offers nothing and still edits
literals.

**The programmer is a layer stack too**, and `LookStack` (`components/looks/LookStack.tsx`) is
the one component that draws both — that sharing is the point rather than a saving, because a
cue *is* a saved programmer stack. Its seam is `LayerHandlers`, which is **index-based on
purpose**: the rows render a list and the operator acts on a position in it, so translating
index → whatever addresses a layer in that world is the host's job. The cue's host — the cue
editor's Looks tab — PATCHes the whole `layers` array, built from the cue as the desk holds it
(§The cue editor); the programmer's Stack table (§The programmer's tables) addresses a layer by
`layerId` itself and sends `programmer.removeLayer` / `moveLayer` / `patchLayer` / `detachLayer`.
Adding there is *+ Look or palette*, which presses onto the selection through `ApplyPicker`
(§"Applying to a selection"). It must not renumber
`sortOrder` client-side the way the cue path does — the server renumbers the stack and re-ranks
the running FX **in place**, so a drag doesn't restart any FX's phase.

`programmer.layerState` is the **third broadcast** frame (after `provenanceState` and
`programmer.includeTarget`), because the programmer is shared and a second tab's reorder must
not leave this one showing a stale order. It arrives two ways, and both are needed: as a
**unicast reply** to whichever socket sent the layer op (the acting tab's fast path), and as a
**broadcast** to every socket from `ProgrammerStore.layersFlow`. The broadcast was missing until
session 4, on the reasoning that "every layer mutation also emits `provenanceState`, which
already schedules the value re-read" — which is false for a mutation that moves no value. A
layer that asserts nothing (a palette layer on heads its rows never reach, a Look of only
scenery) emitted no provenance when added, reordered or disabled, and left every other tab on a
stale layer list. Found on
a desk, not by a test. The flow is emitted from `mutateLayers` rather than from `recook`,
because `reset()` bypasses the recook path and a full programmer clear must reach other tabs
too. Its handler still calls `notifyState()` only and deliberately *not*
`scheduleStateRefetch()` — the frame carries the whole stack, so there is nothing to re-read.
Layers ride a **separate** cache entry
(`useProgrammerLayersQuery`) rather than joining `ProgrammerSummary`, which the always-visible
`ProgrammerIndicator` reads.

Creating a **bound** Look and **update-back after Include** both work now —
`RecordLookSheet` (`POST /programmer/record-look`) and `updateIncludedLook`. `includedTargetIsReadOnly`
and the `INCLUDE_TARGET_READ_ONLY` conflict arm are gone with them.

**Record, Include and Update carry the programmer's scenery** (scenery-programmer plan session 3,
D7, D8; the rules are lighting7 `docs/lighting-composition-model.md` §"Record / Include / Update").
Both Record sheets draw a ***Record scenery too*** row — `RecordSceneryRow`, beside *Record FX
too* on `RecordSheet` and after the FX on `RecordLookSheet` — **absent while the programmer holds
no scenery and ticked on every open while it holds some**, its hint naming the held pieces in the
order first held (`useHeldSceneryNames`, *a piece* for one the element list has not loaded) and saying
what the destination does with them: a cue gets a row for each piece it would not already show, a
Look keeps every held piece. It sends `scenery` on the request; neither the mask nor *Selected
fixtures only* narrows it (D3), so the hint does not mention them. **Record, Record look, Include and
Update close once the write lands** and say what it wrote as a toast (`programmer/writeToasts.ts`,
amber where the desk skipped or warned; a refusal keeps the sheet open with its error inline), and
the Record toasts add `describeSceneryWrite`'s notes (*1 scenery change*, *2 held pieces already shown there*). The action
bar's **Record is enabled for a programmer holding only scenery** (`hasContent` counts it) — a Look of
only the moon is the busk pad that flies it. Include needs nothing on this side: the desk loads the
source's rows into the overlay and the band shows them. **Update's dirty count adds the desk's**
`changedSinceInclude` from `programmer.sceneryState` (`withSceneryChanges` in `lib/includeBaseline.ts`,
read by `useIncludeBaseline`), because a client snapshot of the overlay at the include transition
could land on either side of the overlay's Include — the two frames are unordered — and without it a
moved tab after Include left Update disabled as *in sync*. `null` stays `null`: a tab that did not
see the Include still claims nothing. **The layers are counted the same way**: the desk's
`layersChangedSinceInclude` (`withLayerChanges`), the layers Update would write into an included
cue, since a Look of scenery alone pressed over a loaded cue adds a layer and no value — and a value
a layer holds is not counted as a value (`snapshotEntries` leaves `layers`-owned entries out), so a
Look pressed over a cue is one change, not one per row. **Record look retargets the include target**
to the Look it wrote, as Record into a cue does, so the source strip names it and Update writes back
there — except for a new Look used here as a programmer layer (`asLayer`, on by default for *A new
Look* and always from *Make layer*), which leaves the target where it was, since the Look it makes
is a layer of whatever is loaded and Update still writes that layer into the loaded cue. The Include sheet's Looks tab lists a **Scenery** group after the families, so a Look of
scenery alone (no family) can be included. `UpdateDialog`'s toast adds *, 1 scenery change* where one
was written, so a scenery-only Update does not read as *0 values written* and nothing else; the
Include sheet's toast counts `sceneryIncluded` the same way, Record's menu heading names held
scenery (`recordMenuLabel`), and a busk pad's face counts a Look's scenery (`padFace.ts`'s
`describeLookContents`, *1 scenery*), so a scenery Look is never an *empty* pad. A Record or Update
that wrote scenery invalidates every `Cue` entry, as `setCueScenery` does, since a row moves what
every later cue tracks.

**Update also writes the values the source does not hold** — what a masked or scoped Record left
out, which *in sync* cannot show: it means nothing moved since the Include or Record, and the desk
takes a Record's every touched value as that baseline, mask or not. The desk counts them,
`heldOutsideSource` on `programmer.state` (`ProgrammerSummary.heldOutsideSource`): the programmer's
touched values against the keys the source stores, as the desk last read its rows
(`ProgrammerStore.sourceKeys`, after each Include, Record and Update), and Mode A writes them, changed or not, under the dialog's
mask. So the source strip keeps the *in sync* chip and leaves **Update enabled while that count is
above 0** (never in a venue), its hover and ⌘K's hint saying how many; it is the desk's, so it
survives a reload where *in sync* cannot. FX and scenery are not counted. Record ▾'s first item (and
⌘K's *Record into …*) opens Record on Merge into the included cue or Look
(`useProgrammerSheets().openRecordInto`).

**All three Make Hard routes are gone, and nothing replaced them.** They existed to swap
value-level `ref:` references (the old named-palette sense) for the literals they resolved to, and the `ref:` grammar retired,
so there is nothing left to harden. A successor gesture — "promote a layer's *cooked* values into
local rows and delete the layers" — shipped briefly as `POST /{projectId}/cues/{cueId}/flatten`
and was deleted again in the backend sweep, uncalled. **Do not cite flatten as live**, and do not
add a button for it.

The two constraints that made it hard are worth keeping, because any reimplementation meets them
again. Rows come out **fixture-targeted, never group-targeted** — cook's output is per fixture by
construction and carries no group name, so the old route's "keep a group row when every member
agrees" cannot be reproduced without guessing which of several overlapping groups to name. And a
**single `layerId` can only be the last enabled layer**, because local rows beat every layer:
promoting a middle layer's values would make them win over the layers above and change the cue's
output, which is the opposite of what flattening promises.

Two things about the record sheet that are not arbitrary. The **mask is prominent** rather than
incidental — an old palette bank implied its attribute, a Look has no type, so an unmasked record of
a busked state quietly captures position and beam alongside the colour that was meant; the
per-family counts (`familyForCategory`, at last with a caller) exist to make that visible
*before* it happens. And the **selection defaults on**, the opposite of `RecordSheet`: a cue
usually does want everything you busked, a Look named "Warm Amber" almost never does.

**Every mask a sheet takes is one picker with an *All attributes* switch** (venue-visit plan S10):
`MaskPicker` in `programmer/maskPicker.tsx` — a label, `MaskToggles`, and a sentence under them — on
Record, Record Look (the busk Fan sheet's *Save as Look…* included), Include, Make layer, Update and
a layer's popover in `LookStack`. **Record, Record Look and Update open on every attribute** — a
write that quietly left a family out is only found when the cue is fired — Record Look on the opener's
mask where it names one (the FX editor's lanes). **Include and Make layer open on the selection's mask**
(apply-model plan D3), read once on open by `useWindowMaskReader` — the desk's while the window follows it; unlinked, the
programmer's marquee (the marquee store is provided above `ProgrammerSheetsProvider` for this) or the
busk tab's own copy — and **a change in a sheet is that sheet's own**: it never writes the selection,
and a selection moved while the sheet is open never moves it. Read, not subscribed, because the
sheets stay mounted while closed and a subscription would re-render them per marquee frame. Make
layer's unsaved-changes guard compares against the mask it opened on. **The switch is on for every attribute and hides the four family
checkboxes**; switching it off shows them **all ticked**, which is not yet a narrowing — the operator
unticks what the write should leave alone. The switch moves only when pressed: unticking the last
family leaves it off with nothing ticked, and ticking all four back leaves it off too, so nothing
vanishes under the pointer. Switched on, the families' per-family counts (Record Look, Make layer) are
hidden, so the sentence names what the programmer holds instead. **The value is a `MaskValue`, not
the wire's mask**: `null` while switched on, the ticked families while off. Off-with-all-four is the
same mask as `null` but a different control state, and off-with-none is a state the wire has no word
for — so a host sends `maskToWire(value)` (`undefined` for every attribute: the desk folds all four to
none anyway, and the presses' `families` is the same vocabulary under its own name) and **refuses to
act while `maskIsEmpty(value)`**, its button disabled and the sentence reading *Pick at least one
attribute.* A host that writes on every change cannot hold that state in what it stores —
`serializeAttributeMask` folds all four to null and has no "none" — so it goes through
**`useMaskDraft(stored, write)`**: the picker's own draft, writing only a change to the folded mask and
never an empty one, and following the stored mask only when it changes to something it did not send
itself (it keeps the keys it has sent and not yet seen back, so its own echoes — even one landing after
a newer gesture, Colour unticked and ticked straight back — leave the draft alone). `LookStack`'s
`LayerMaskPicker` is one; the selection's family pill is the other (§The desk selection has a mask).
**`MaskToggles` is the mountable half on purpose** — no label, no sentence, nothing about the
selection — which is how `SelectionMaskPill` mounts it in a popover, through `useMaskDraft`, writing
the selection's mask with its targets kept. `UpdateDialog` gained the picker with
S10: Mode A sends the mask on the write; in Mode B the mask also decides which cues the programmer is
over, so a change re-fetches the checklist under it (keyed on `maskValueKey`, a stale answer dropped by
a request counter, the operator's unticks kept across it, nothing asked while nothing is ticked). Held
scenery goes back whole, which the mask never narrows. The dialog explains none of this on screen: an
operator is expected to know what Update does.

**Record writes into any cue, or into a stack it makes, or into none** (S10; cue model plan §10,
19). After the stacks and a separator the stack select offers ***New stack…***: a required name
field appears under it, and the stack is created (`loop: false`, appended to the show order) only
when Record is pressed, so a cancelled sheet leaves nothing behind; once made it is the selection, so
a failed Record is retried into it. A show with no stack opens on it. Its last item is ***No stack***,
which sends no `cueStackId`: the new cue is loose, and the sheet says it is found in the Cues table
(§"The Cues table"). Merge, Replace and Remove are offered from every Record, not only from a cue row,
and each draws `RecordCuePicker`: one filterable radio list of every cue **in show order** under a
divider per stack (venue-rig-fixes plan D15; `cuePickerSections` over `lib/runningOrder.ts`'s
`cuesInShowOrder`, markers left out): the running order's stacks, then the busk stacks (*· busk*), each
cue once at its first place in that order, then the loose cues by name. The programmer Record menu's
*An existing cue…* opens on Merge with it (`RecordPreset.existingCue`); until S10 it opened the sheet
with only *New cue* to offer. **A row names every place its cue sits**, in the same order, from the
list's `entries` — `Q7 · Act 1, Q14 · Act 2`, or *Loose* — the list the Cues table reads, so a loose
cue is still a cue, labelled by its name, and a shared cue is one row because it is one cue. The
filter matches the name or any place's number or stack, and keeps a divider only over a match. On
Merge with no cue named, the sheet **preselects** the included cue when Include last loaded one, else
the live cue of the stack the show's playhead is on (D16, `preselectedRecordCue`), filling it in when
either arrives after the open; the preselection is held apart from the cue picked, so a cue nobody
picked is never what a Replace or a Remove writes into, and the picker brings a picked row into view
once. Record ▾'s ***A new cue after Q13*** (D17) opens on New cue with `RecordPreset.afterEntryId`, the
entry the include named, its subtitle *Act 1 · directly after 13* (*· appended* when the include named
none or the stack list does not hold it — never a row guessed by cue); the sheet
says *After Q13 in Act 1* under the stack select while that stack is the choice, with a *Put it at the
end* checkbox, and sends `afterEntryId`, which the desk resolves when it records.

**Provenance names the winning layer.** `ProvenanceEntry` gained `layerId` and `layerSource` —
the resolved referent, `kind` + `id` + `name` — which `useRowOwnership` aggregates into
`CellOwnership.layer`, so "why is this fixture this colour?" answers *Warm Wash* rather than *a
cue*. Those fields **must stay in `provenanceSignature`**: a key can move from the cue to one of
the cue's layers with `source` unchanged, and a cell that didn't wake would keep naming the old
answer. The signature reads the whole `layerSource` object rather than its id alone, because a
Look layer and a palette layer can share an int PK — that is the reason the entry carries a
source object at all, and matching on `layerId` would let a swap between the two look like no
change.

Both branches fill them in, and the `PROGRAMMER` one only since session 4: session 3a wired the
`CUE` branch from `cueLayerLayerWinners` and left the programmer branch building a bare entry, so
a cell lit by a busking pad answered *the programmer* and the layer-aware hover never appeared
there. The programmer branch has no resolver to ask, so it recovers the winning layer's **rank**
from the reserved `seq` band that `putLayerSlots` stamps
(`ProgrammerStore.layerWinnerRankByKey`), and reports only keys whose *winning* slot is the
layer's — a local busk sits above the layer slot, and naming the Look there would credit a Look
for the operator's own hand.

**The pads still go through `POST /looks/{id}/toggle`**, which is `programmerLayerStack.toggle`
server-side — it adds or removes a layer, matching a Look on its source alone, since it lands
whole, and a palette per head by coverage — the source by uuid and kind, never an id alone, which
would let a Look and a palette that share an int PK cancel each other.
Keeping one owner for that match rule is why they weren't moved to the explicit ops. The rings read
the desk's **resolved applied state** (`programmer.applied`, from `useProgrammerAppliedQuery`),
never the FX list: `ProgrammerLayerStack.appliedState` sends one entry per record on the stack. **A
Look's ring is on or off** (absolute-looks plan P4): `lookPresence` / `lookIsApplied`
(`busking/lookPresence.ts`) light it while a layer of it is on, whatever is selected, and its entry
names no targets. A palette's names every target it covers, each group marked `all` or `some`, and
`paletteLayerPresence` folds the selection into one ring — two copies of a coverage rule drift, and
the copy in the browser is the one no test against the rig can reach. It is the *only* way a palette
pad can light: an FX-palette pad's ring would look matchable against the running instance, and
matching there would light for an FX palette while leaving every value palette's pad dark.

The **busk view**'s pad grid takes both, and takes **cues** besides — but it no longer decides where
any of them go. A pad sits where the operator put it, in a bank on a page they built (see §The busk
layout); the library's family says nothing about the arrangement. What survives from
the old automatic layout is the pad *face*: an FX pad is a pad like any other — same component,
same presence ladder, same long-press — and only its wave glyph says what it holds. The paragraphs
below describe the pad face only.

**`/palettes` is a list ordered by name, grouped by family under *All*, and there is nothing to
drag.** Since the library sheets' session 2 it is a sheet (§Library sheets): under *All* the rows sit
under family dividers — Intensity · Colour · Position · Beam, `PALETTE_FAMILY_ORDER` — and within
each, as in a filtered view, they are the server's name order. The dividers are a grouping of that
order, not an order of their own. A palette carried an
operator-set position and could sit in a *group* whose pads released each other; both went with the
automatic layout, because both were things the busk page does better — order is a pad's place in a
bank, exclusivity is a solo bank's. `TemplateLayoutList`, `TemplateGroupRow` and
`lib/templateLayout.ts` are deleted, `/templates/reorder` and the `/template-groups` CRUD with them,
and `PaletteSummary` carries no `sortOrder` and no `groupId`. **Do not reintroduce a stored library
order**: the only orders a palette has are this page's name order and the recency the picker and the
popup bands read (§"Applying to a selection"), and neither is a field.

The **family filter** stays, and is the page's only partition: a palette is in exactly one family,
so `?family=colour` is a view of a small library rather than a division of it, and it deep-links
from Cmd+K. It is the library row's `PartitionChips` now, with counts (`LookFamilyFilterBar` is
gone), and `ApplyPicker` mounts the same chips with state of its own. The footer says how many of
the whole library the sheet is showing.

A colour pad carries a **swatch**, but only when the
palette is generic and single-row — `paletteSwatch` in `components/busking/padFace.ts` makes
the same two exclusions `isOfferable` makes in `FxColourPalettes.tsx`, and for the same reason:
`rows[0]` under a name covering several rows states one of them as the whole thing. An FX
palette has no rows, so it draws the wave in the swatch's place and an `FxPadDetail` line
(`Colour Wave · ×8 · M2`). That detail is a **component** and not a string built by the caller:
the master's label is a live value, hooks cannot be conditional, and a hook in the loop would make
every value pad in the library subscribe to the master bank. (The palette sheet's Master column
answers the same question the other way — it names masters from the project's *stored* bank,
fetched once by the route, so no row subscribes to anything.)

**A movement FX palette asks *Centre: Around current position | Absolute*** (fixture-fx-sheets
plan D16, session 5) — D12's question, through the live editor's own rules (`fx/centreMode.ts`,
re-exported by `fxEditorModel.ts`) rather than a second copy. `PaletteEditor`'s FX branch
mounts **`FxFields`** — the live editor's fields over the palette's draft (`paletteDraftOf`
/ `withPaletteDraft`), with no target: the Order and *Over* in *Across the heads* (a palette's own, read and
saved by both and compared by `differsFromPalette`; a new one starts on `WHOLE`, a type swap keeps
it, *New palette from FX* takes the running FX's), no *One cycle for* or head filter, and the same timing chip — *Own tempo* (*Natural*) or a master — as the live editor.
**`EffectParameterForm` is
retired**, not rebuilt: it had been that branch's form and nothing else's since session 3. Choosing
an FX starts it on its **starting spec** (`startingSpec`, the picker's rule): a movement FX
starts **Around** — `ADDITIVE` with both centres pinned at 128 and the centre hidden — and anything
else on Override; the hard-coded `blendMode: 'OVERRIDE'` that made every Circle pad circle 128/128 is
gone. **Stored palettes are not migrated** (call 6): one still on Override reads as **Absolute**,
centre shown, until the operator presses Around. The palette sheet's **Value** cell is an
`OptionCell` over the same two answers on a movement FX palette (`centreValue`; `''` presses
neither for a blend that is neither), landing `withCentreMode` on every movement palette in the
batch, a value palette in it named and skipped; any other FX palette still reads out. The
FX library comes from the route, as the masters do. A pad's face says ***around*** for a
palette spelled that way (`isAroundSpelling`, in `FxPadDetail` and `padFace`'s static line). It adds ← or ↔ for a
backward or bouncing palette and the amount where it is under 100 % — *Colour Cycle · ×1 · M2 · ← ·
60 %* (effects plan §4.1, `lib/fxDynamics.ts`'s `fxDynamicsParts`, both lines).

### The busk layout

**The busk page is a thing the operator builds, not a view the library lays out.** A page is rows; a
row is columns, each with a **width share in twelfths**; a column stacks banks top to bottom; a bank
has a name, a `solo` flag and a `flow` (`WRAP` | `COLUMN` | `SCROLL` — the last one line of fixed-width
pads scrolling sideways, the rig row's own flow, offered to banks since 2026-09-21) and holds ordered **pads**, each a
reference to exactly one palette, Look, cue or busk stack. One record may sit on several pads, on several
pages. Backend contract in `lighting7/models/buskLayout.kt` and
`../docs/lighting-composition-model.md` §"The busk layout"; the plan is
`../docs/plans/completed/busk-layout-plan.md` and the design authority is `look-groups-design/`.

It replaced an automatic layout — four family columns of palettes, a Looks pool, a cue column of
stack cards and pinned-cue pads. The reason is the one a palette group could not meet: a group
holds **one family**, and the operator's actual ask (a position palette and a movement pattern that
must not run together) needs a cluster holding both, plus cues. So order and exclusivity moved to
the page, and the library went back to being a flat list.

**A press goes through the pad, and the bank decides the siblings.**
`POST /busk/pads/{padId}/press` is the *only* press on this surface — the three kind-specific
mutations (`togglePalette`, `toggleLook`, `goToStack`) are the programmer's doors' (through
`usePress`) and the AI's now. A pad's ⌥click and its menu's *Apply as values* are not a press: they
go through `usePress` to the apply routes over the busk pair, and touch no bank. The pad press has a
second door since the MIDI surface's session 4: a `PressPad` binding runs the
same `BuskPressService` with the desk selection as the targets, which is why that service exists at
all — the solo rules and the refusals are the pad's behaviour, not the endpoint's. The server reads the pad, its record and, when the bank is **solo**, the records on its
sibling pads in one transaction. Solo has one meaning for every kind: pressing one *on* turns its
siblings off — a palette sibling narrowed on the pressed heads, a Look sibling off whole, a live cue
sibling stopped, and a cue or a Look press taking its layer siblings off whole, because neither has
targets to narrow by. An **off** press releases nothing, and a stacking bank has no siblings at all. The request carries the
selection's **`families`** beside its targets — the desk's pair while this tab follows the desk,
the tab's own when unlinked — which a palette pad's press reads and a Look's or a cue's ignores; see
§The desk selection has a mask for what lands under it and why a press is never pre-refused here. The band's one row
carries the family pill and the desk chip for the same pair.

**`src/lib/buskLayout.ts` is the document model**: no React, no store, every gesture a plain call a
unit test can make. Three rules run through it:

- **Addresses, not ids.** A pad, bank or column created by the *previous* gesture has no id yet —
  the layout PUT mints it — so drag ids and every position are index tuples (`bpad:{r}.{c}.{b}.{p}`,
  `bbody:`, `bbank:`, `bunder:`, `bgut:`, `bnewrow`, `palette:`). Ids are read in **one** place,
  `toLayoutRequest`, at commit. React keys are `uuid ?? localKey` for the same reason, and a **row's
  key is its first column's uuid** — a row has no identity on either side of the wire, and an index
  key over a list that gets rows spliced out of the middle is the classic remount bug.
- **Normalise inside every mutator.** The server refuses an empty row or column
  (`BUSK_LAYOUT_INVALID`), so `normalisePage` is not cosmetic — forget it and the gesture 400s. It
  drops an empty column and the row left empty with it, and **keeps an empty bank**. That asymmetry
  is deliberate: pruning a bank would delete the one the operator just made, the instant they
  crossed the last pad off it.
- **Null means nothing would change**, which is what lets a repeat hover write no state.

`applyDrop` runs **once, on drop**, in the order **lift → insert → prune**; object identity carries
the destination across the lift, so a bank leaving the column it is being dropped beside cannot
renumber its own target.

**A pad target is an insertion point, not a destination index** — it names the gap the dashed slot is
drawn in, counted over the document as it stands with the source still in it and ghosted. Both halves
must agree on that, or a pad lands somewhere other than where the operator was shown it would:
`resolveDropTarget` produces the gap, `BuskBank` draws the slot at it, and `applyDrop` has to put the
pad in *that* gap — which means subtracting one when the lifted pad sat before it, since removing it
shifts every later gap down. Reading the index as an `arrayMove` destination instead made every
**downward** drag within a bank overshoot by exactly one place, invisibly, because each half was
self-consistent and only the composition was wrong. `buskDnd.test.ts` composes the two and asserts
the slot and the landing place agree; testing either half alone passes with the bug in.

**There is one *app-wide* `DndContext`**, in `components/dnd/DeskDndProvider.tsx` (it was
`CueSlotDndProvider`; it is not about cue slots). `Layout.tsx` mounts it around **both** the FX
cue-slot overlay and the routed page, and the busk page joins it with `useDndMonitor` rather than
nesting one.

Other surfaces nest one of their own, and the line between them and the busk page is whether the
drag has to *cross* into the shell's droppables: the show's cue cards (`StackDetail`), the stack
list (`ShowOverview`), the cue sheet's row drag (`SheetTable`'s `rowDrag`, mounted only for a
sheet that asked for it), a group's member list (the group sheet's *Members* view and Patches'
*Edit Group* sheet), `LookStack`'s layers and `FxColourListPicker`'s colours each reorder a list
**within themselves**, and none of them has a gesture
that ends on a cue slot. A nested context wins for its subtree, which is exactly why the busk page
must not have one: it would hide that page from the overlay's droppables, which is exactly the drop
the plan's session 3 needs. The overlay and the busk page coexist by
**mutual ignorance of ids and of foreign *targets***: `parseBuskDragId` answers null for a `slot-…`
id, and the slot handler returns for any `over` that is not a slot. It is **not** ignorance of
*sources* — a drop onto a slot is resolved in the provider whatever lifted it, today a sibling slot
or a `busk-palette` row from the busk view's library, because the provider owns the slot droppables
(mounted on every route the app is drawn on — an immersive window takes the cue-slot overlay with
the other three panels, §Windows, full screen and the hand, so there a library row has no slot to land on), `projectId` and
both slot mutations, while the panel body unmounts with the overlay. The source→assignment mapping is pure in `components/dnd/slotDrop.ts`, imported
type-only, so the shell still reaches no busk runtime code. Three things about that provider are
load-bearing:

- **Collision detection is `pointerWithin` falling back to `closestCenter`.** dnd-kit's default
  `rectIntersection` compares the *dragged* rect by area, so a 20px column gutter could never beat
  the 300px column beside it, and it answers nothing at all in the gaps between banks.
- **`MeasuringStrategy.Always`**, because the page changes its own geometry mid-drag.
- **One `DragOverlay`, content dispatched** through `dnd/dragOverlayRegistry.ts`. A second overlay is
  not an option — it registers itself on the context and two would fight over one ref. A surface
  registers a renderer at **module scope**, so the app shell draws a busk pad without its import
  graph ever reaching one. The ghost face is a **frozen snapshot**: an FX palette's detail line
  reads a live speed-master label through a hook, and the overlay must be hookless.

**The FX cue-slot overlay is filled from the busk view's library, and edited only while the busk
view is.** A slot holds a cue or a **Look** — `itemType` is `'cue' | 'look'`, and the
cue-*stack* arm is gone. It has no selection, so it can hold only what needs none: a Look tile
presses `POST /looks/{id}/toggle` with an **empty target list**, and the desk lands the Look whole. A **cue** tile is a busk cue pad's twin (§"The cue
column is gone"): a tap takes a loose hold (`/cues/{id}/apply`) or, while a press holds it, lets go
(`/cues/{id}/stop`), and it draws the pad's two looks — the overlay's fill for a loose hold, an
outline (`slotLitClass`'s `outlined`) for a cue live only in a stack. Three rules follow, and each
has a reason that is easy to undo by accident:

- **A Look tile lights from the programmer's applied feed, never from cue liveness.**
  `lookIsApplied` (`busking/lookPresence.ts`) is a selection-*independent* helper for exactly this:
  the target-scoped folds beside it answer `'none'` for an empty target list by design, so reading a
  slot through one would leave every Look tile dark. It matches on the source being a **LOOK**,
  because a palette layer can carry the same int PK.
- **Crosses, drags and drop targets follow `useBuskEditMode()`**, the `buskEdit` Redux slice — the
  overlay is a *sibling* of the routed page in `Layout.tsx`, so a context inside the busk view could
  never reach it. The panel's own long-press wiggle mode and its inline assign panel are deleted.
  Outside edit mode the overlay is press-only, with its *View* / *Clear slot* context menu intact.
- **`slotEligible` is decided at the library and re-checked in `slotDrop.ts`.** An ineligible row
  dims and says *needs a selection* while a slot is the target (`useCueSlotHover`, a `useDndMonitor`
  rather than provider state, so only the library re-renders at hover rate), and the drop is a no-op
  if it lands anyway.

Known gap: the slot droppables live inside `CollapsiblePanel`, so with the overlay shut a library
row dropped at the header lands on nothing, silently — `FU-SLOT-DROP-OVERLAY-HIDDEN` in lighting7's
follow-ups.

**The preview is a ghosted source plus one dashed slot, and there is no `SortableContext` anywhere
on this page.** The primary gesture is a *library* drop, and a library row is not a member of any
sortable list, so the dashed placeholder has to be hand-drawn regardless; the three bank zones are
not list indices; and the committed artefact is the whole document anyway. Two rules stop the
classic placeholder oscillation: the dashed slot is **`pointer-events: none` and is not a
droppable**, so it can never be the thing you are over, and the reducer answers null for a repeat
hover. The cost is honest — pads snap around the slot rather than sliding. If that ever reads badly,
`view-transition-name` is the additive fix; do not reach back for `SortableContext`.

**Legal targets follow the source, and the hover is fed from `onDragMove`.** Six things went wrong
at once the first time a bank was dragged on a real desk, and each is now stated in code rather than
assumed:

- **A bank lands on the three bank zones and nothing else; a pad or library row lands on a pad or a
  bank body and nothing else.** `canLand` in `buskDnd.ts` filters the collisions *before* the
  deepest-wins pick, and the pad and body droppables are `disabled` while a bank is lifted (the
  strips, gutters and new-row zone were already disabled otherwise). Both halves are needed: the
  filter is the half a test can reach, the `disabled` flag is what keeps dnd-kit's `over` — and so
  `isOver` — off a place the drop would refuse. Before this, a bank over a pad resolved to a pad
  target, opened a dashed slot inside the bank, and dropped nowhere: `dropBank` refuses a pad target,
  so the monitor returned before `commit` and no request was sent.
- **The `closestCenter` fallback returns one collision.** `closestCenter` answers with every
  droppable nearest-first; a deepest-wins reader then took a pad three banks away while `over` lit the
  nearest strip. `DeskDndProvider` slices it to one, so the highlight and the slot always name the
  same place.
- **`onDragOver` fires only when the over-id changes**, so the leading/trailing half-of-a-pad decision
  was made on entry and never again. `BuskEditProvider` feeds `resolveDropTarget` from `onDragMove`
  as well; `sameTarget` keeps the state write to the moves that change the answer.
- **`MeasuringStrategy.Always` is not a timer.** dnd-kit re-measures when the set of droppables
  changes or a droppable's own ResizeObserver fires — not when the slot opens and shifts every later
  pad without resizing it. The provider calls `useDndContext().measureDroppableContainers()` in an
  effect keyed on the target, after the commit that moved the slot.
- **With fresh rects the open slot needs to be sticky.** Opening it shifts the hovered pad along and
  leaves the pointer on the slot — which is no droppable — inside the bank body; collapsing that to
  the body's append would throw the slot to the end of the bank the instant it opened.
  `resolveDropTarget` takes `current` and keeps it while the pointer is in the body of the bank the
  slot is open in. Entering another bank's body still appends, which is the only way into an empty one.
- **The drop reads a ref.** `targetRef` is written in the same handler as the state, so a drop that
  lands before the re-render following the last hover cannot read the target before that one.

**A seventh, found on the desk later and from the other direction: the operator's pointer moves the
slot, and the slot does not move the slot.** Opening a slot physically displaces the pads after it,
so with a *stationary* pointer the pad underneath it changes — which re-answers "what are you over",
which moves the slot, which displaces the pads back. Dragging a pad near the boundary between two
banks in one column, the slot flipped between them every two or three frames while the pointer moved
**one pixel**; and because the re-measure below runs on every target change, React counted the
nested updates and threw *Maximum update depth exceeded* rather than merely flickering.

`TARGET_HYSTERESIS_PX` (6px, against the pointer sensor's own 8px activation) is the fix:
`resolveDropTarget` returns `current` unchanged until the pointer has travelled that far from
wherever it was when the slot was placed. It is checked **before** the `overId == null` arm, because
the displacement can just as easily carry the pointer off every droppable and close the slot — the
same loop with an extra frame in it. The distance comes from dnd-kit's own `delta`, never
reconstructed from `activatorEvent` plus coordinates, which is the reconstruction `edgeDrag.ts`
refuses for going wrong under browser zoom.

**The `sameBank` stickiness could not catch it, and widening it would be the wrong fix.** That rule
holds `current` only while the pointer is in the body of the *same* bank, and this oscillation is
precisely *between* banks — which is the case it is written to allow ("entering another bank's body
still appends"). Bank scope is the wrong axis; pointer movement is the right one, because it is the
one input the slot cannot change. `BuskEditProvider`'s `measureDroppableContainers([])` now also
runs on an **animation frame** rather than inline, so anything that ever oscillates again is a
flicker and not an error boundary — one measure per frame is all a 60Hz drag can use.

**`newBank` requires a name.** The server refuses a blank one (`BUSK_LAYOUT_INVALID`), and a
default of `''` was how `+ Bank` and `+ Row` shipped never having succeeded once — the optimistic
patch drew the bank, the PUT 400'd, the queue rolled back and toasted "A bank at row 1, column 5 has
a blank name". `nextBankName` mints `Bank N` for the smallest free N, the vocabulary
`lib/buskAdd.ts` already used to *label* a nameless bank; `BankNameField` reverts a blank rename
rather than sending it. `buskLayout.test.ts` "mints documents the server would accept" now asserts
the name, which is the assertion that was missing.

**Every gesture saves the whole page, and the queue holds operations rather than documents.**
`useBuskLayoutCommit` in `store/busk.ts` enqueues `(page) => page` so each gesture can be *replayed*
against the freshest confirmed document at send time. A queue of pre-computed documents could not:
gesture 2's document was built before gesture 1's response existed, so it would name none of the ids
that response minted and the server would recreate every pad it touched. The response is written
into the cache **only when the queue drains** — an intermediate one describes the world a gesture
ago, and writing it is exactly the snap-back the queue exists to avoid. On failure the queue is
dropped, the last confirmed page restored, **and the page re-read**: the write may have been refused
because a record behind one of its pads was deleted, and that is the one frame the echo suppression
below can swallow.

Patching from the **response** rather than optimistically-then-invalidating is a deliberate
departure from `reorderPalettes`: that route answers `void`, so a refetch is its only way to learn
what happened; this one answers the page as written, ids included.

**`busk.layoutChanged {pageIds}` is keyed, and the bridge suppresses our own echo.** The frame fires
for page CRUD, reorder, every layout write **including this client's**, a record delete that took
pads off a page, and a stack-list write that moved the places or numbers of the cues a page's pads name; a page delete carries the deleted id *plus every survivor*, since their `sortOrder`
moved. Invalidation is per page (`{type:'BuskPage', id: 'page-N'}` — prefixed, because the project
id shares that tag type), so **a page id from another project matches nothing and is a silent
no-op**: the frame needs no project lookup. A single-page frame is dropped while that page is saving
or within a short grace after its response, because our own write would otherwise refetch once per
gesture and could land between an optimistic patch and its own response. One page is the tell — a
layout write announces exactly one, page CRUD and reorder announce several.

**The desk holds a showing page, and a window chooses whether to be on it.** `busk.pageState` /
`busk.setPage` (`api/buskPageApi.ts`, `store/busk.ts`'s `buskShowingPage` entry) stays server-owned,
for the reason it always was: a hardware *next page* button and a tab click are two ways of making
one gesture, so the surface needs one thing to move — `BuskPageNext` / `BuskPagePrev` /
`BuskPageSet` write `BuskPageState` and every *following* window moves with them. What that argument
never established is that **every** window must be pinned to it, and on two screens it is wrong: the
flow this exists for is a colour page on one screen and a position page on the other, pressed onto
one selection. Reported on the desk 2026-09-16.

**The desk's page is a paging group, and a window is either in it or on its own page** (desk-follow
plan D5–D7, D9). Nothing but following windows and the `BuskPageSet` LEDs reads `BuskPageState` — no
binding presses a pad by page position (`PressPad` names a uuid) — so following it means *paging
with* the MIDI Next · Prev · Set and every other window in the group, which is what every surveyed
console does (Eos paging groups, Titan's *Follow World Page Change*, Onyx Wing IDs, MagicQ bank ties).
The words are the job, not the mechanism: ***Paged with the desk*** · ***Own page*** on the Screens
row (*With desk* · *Own* on a narrow one), ***Page: Own*** on the chip (*Own* on its short rung). A
per-window page everywhere was drafted and dropped (D5): the desk page, `busk.pageState` /
`busk.setPage` and the three page targets are unchanged.

So the page gets a **follow/local split of its own**, `lib/buskPageFollow.ts`, on `lib/deskFollow.ts`'s
model — per-tab `sessionStorage` (two desk screens are two windows of one profile), default follow,
unlinking snapshots what the window is showing and leaves the desk's alone, re-linking adopts the
desk's and publishes nothing. The order is **this window's own page (unlinked) or the desk's
(following) > `?page=` > the first page**, all resolved against the fetched list so a stale bookmark
lands somewhere real; a tab click writes the **desk** while following and this window's copy once
unlinked, and the URL mirrors whichever won with `replace` — flipping pages is not a history entry.
A `null` from the desk is *not* "the first page": it means nothing has moved it, and each client
falls back on its own.

**Two flags, two marks, and neither may drive the other.** A single flag cannot express the flow:
following would pin both screens to one page, and unlinking to get two pages would take the shared
selection with it, so the operator would select twice. `DeskChip` governs the selection and
`BuskPageChip` the page, and **each is always drawn, one way or the other** (desk-follow plan D7,
D8, revisiting busk-chrome D18): while linked, the **link badge** (`components/desk/LinkBadge.tsx`,
one component for both so they cannot drift); while not, the dashed `FollowPill`. **The mark is
the toggle, both ways** (desk-follow D11, 2026-09-23, revising D7 and D8's "a mark, never a
control" after Chris tried it: the chip on the row is the first thing an operator reaches for, and
a Screens sheet was the wrong primary door): the badge's press unlinks — the selection's takes the
desk's selection as this window's own (`unlinkFromDeskNow`, ⌘K's gesture), the page's keeps the
page on show as its own (`unlinkBuskPage(activePageId)`, handed down by the strip) — and the dashed
pill's press relinks. The badge is `aria-pressed` like the pill (pressed = linked) and the same
20px box as the mark, so no ladder moved. **Where the window cannot leave it stays a mark**: the
selection in Rig and Pads focus always follows (D2), so there `DeskChip` takes `forcedBy` and draws
`role="img"` with the reason on its hover, rather than a press the D3 relink would undo at once. The
Screens row and ⌘K stay, for setting *another* window. The two badges differ in one thing: the page's
**names the other open busk windows paged with this one** (*⛓ Screen 2*, `+N` for several, every
one on the hover, which also says a tab click here pages them too) — `useCoPagedWindowNames` in
`store/windows.ts`, from `windows.state`: rows on `busk` whose `viewOptions.pageFollows` is not
`'false'`, other than this window's own row (`thisWindowRow`); a row that has not announced the key
counts as paged with, and a row in Rig focus is included, since it still pages with the group. The
names fold to the glyph on each row's ladder and the glyph never does; the name is capped at 96px so
the ladders have a ceiling to be measured against. The page mark sits **beside the tabs** — on the
pad row in Split and Pads, on the short board's merged row, and on Rig focus's folded page strip,
since Rig still pages with the group (§The rig has the pad row's numbers). The ways onto a page of
its own are the Screens row, ⌘K's page pair, a `?page=` arrival and a tab click that never reached
the desk; the ways back are the chip, the Screens row and ⌘K. On this view both chips name their
subject (*Targets:* / *Page:*) so neither reads as governing the whole view. On the programmer's
row C the selection chip is alone and stays bare; there is **no page mark there**. A window that has
unlinked its page has not unlinked its selection and still presses onto the desk's targets and mask.

**Another window can set the page both ways** (D6): the Screens row's Page segment writes
`windows.viewOptions {pageFollows}`, which `applyBuskViewOptions` applies — `'true'` is
`relinkBuskPage`, the chip's own press; `'false'` keeps the page the window is *showing* as its own.
That is the one fact the handler (in `Layout`) cannot know, so `BuskingView` reports its active page
through `reportShowingBuskPage` and the handler reads `showingBuskPage()` — `unlinkBuskPage(null)`
would drop a window on the desk's page to its `?page=` or the first page at the moment it unlinked.
A `{page}` still unlinks onto that page, as a `?page=` arrival does. **A frame carrying both keys
resolves on `pageFollows`**: `'false'` with a page is the unlink onto that page, once; `'true'` with a
page relinks and ignores the page, because a paged-with window's page is the desk's and a frame aimed
at one window must not move it.

**The flag is tri-state, and `?page=` is what the third state is for.** Arriving with a `?page=`
**that resolves against the fetched list** is an explicit statement about *this* window — it composes
with the Screens sheet's launcher, so `?window=Screen%202&page=3` starts a second screen where you
want it — so it **unlinks** the window onto that page. An arrival naming no page, or one that is
gone, records that the window *follows* instead; both arms write the flag, which is what makes a
reload never an arrival. It has to be once per tab: the view mirrors the showing page back into
`?page=` on every change, so a following window reloading would otherwise read its own mirror as a
deliberate statement and unlink on every refresh. `null` means *this tab has not decided yet*, and
it is the only thing that tells a fresh window from a reloaded one — so there is deliberately no
second, in-memory "already ran" flag beside it. The parameter is latched at mount as the **raw
string**, null when absent, because `Number(null)` is 0 and a page whose id were 0 would make every
plain `/busk` load read as an arrival.

**Two consequences of that, both surprising enough to be worth saying out loud.** The busk view's own
address always carries `?page=`, so **a copied URL opened in a fresh window arrives local, not
following** — one click on the chip joins it to the desk, but nothing does it for you. And **there is
no separate offline override any more**: it was a second thing meaning "this tab's page", so a click
that never left the browser (`setShowingBuskPage` returns `sendGesture`'s `false` when the socket is
down) now unlinks the window onto the page clicked. That loses the old override's self-healing — it
was cleared by any change to the desk's value, so a Wi-Fi blip mended itself — and the trade is
deliberate: one mechanism rather than two, and a state that *says* what it is on the chip instead of
a silent override. A window stuck local after a blip is one click from following again.

**The mirror waits for the arrival decision to be rendered.** Both effects run in one commit, in that
order, and `unlinkBuskPage` only *schedules* the re-render that moves the active page — so an
ungated mirror writes the page the window is unlinking *from* into the URL and corrects it a tick
later. `useBuskPageDecided` is the **rendered** tri-state, and lagging by one render is exactly what
makes it the right gate: a live `isBuskPageDecided()` has already been flipped by the arrival effect
beside it, and `useBuskPageFollow()` collapses `null` and `true` to one `true` and so never changes
in the keep-following arm.

It shares only a namespace with `busk.layoutChanged`, which names pages whose *document* changed and
is what the echo suppression is written against; a local page still needs that invalidation like any
other. Edit mode
lives in `store/buskEditSlice.ts` rather than a React context, because the cue-slot overlay is a
*sibling* of the routed page in `Layout.tsx` and could never read a context provided inside the busk
view. It is never persisted, and `BuskingView` **must** exit it on unmount, or that overlay keeps
drawing crosses on whatever page the operator went to next.

### Focus and the side sheet

**Each window gives the busk view its own shape, and the shape is three per-tab facts**
(busk-further plan D5–D7, `lib/buskWindow.ts`): `busk.focus` (`split` · `pads` · `rig`),
`busk.rigHeight` (how tall the split draws the rig region, in px — `null` until the window chooses,
drawn then as the surface's default whole lines) and `busk.sheet` (`none` · `level` · `colour` ·
`position` · `beam` · `spread` · `fx` · `speed` · `show`, where `none` is the fold). They sit on `buskPageFollow.ts`'s model and **beside** it —
per-tab `sessionStorage` through `createSyncStore`, never `localStorage`, never the desk's — and
nothing here reads the selection's follow flag or the page's. The reason is the reason focus
exists: two screens at one desk showing two shapes of one view, pressed onto one selection.

**Split** is the band drawing the rig as a scroller at `busk.rigHeight` over the page with the handle between; **Pads**
folds the rig to `RigStrip` (the summary, the family pill, the desk chip or its link badge — what a
press needs to be honest about — labelled *Pads*, since in Pads it is the body's top row, and with
no chevron: the Focus control beside it is the way back) **off the desk board**, and on it draws **no rig
row at all** (busk-chrome plan D17, session A.5): the **pad row** is the body's top row and the page
fills the rest; **Rig** fills the
body with every row and folds the page to `BuskPageStrip`'s **40px folded arm** at the bottom —
the page's name and its bank count, **not the tab strip drawn folded**
(`Phones.dc.html` note 8, busk-further plan §11): Rig focus exists to give the band the height,
the full tab strip wraps on a phone and takes it back, and a page is chosen in Split. **The Elements
menu is not on the folded rig strip** — the compact boards' fold — by decision (session 8): `Focus`
and `Phones` draw the strip without it and `Cells` draws it with, and the strip carries what a press
must be *honest* about, where the menu is a *write*. On the desk board there is no fold: Pads draws
the pad row instead (§The rig), which carries the three selection verbs and not the Elements menu, for
the same reason. **Below `md` Rig focus stacks every row two tiles across, scrolling
vertically with the band** (`Phones` note 6, `RigBand`'s `stackRows`, which `BuskingView` sets
for the **narrow board only** — the short board is compact too but wider than `md`, and keeps its
sideways rows): it is D15's replacement for the narrow-width target sheet, and a sideways scroll
per row on a phone defeats the point of a list; Split there keeps the one sideways row with the
row chip. **The band's chrome is one row, the same row in Split and Rig, and the Focus control and
*Edit layout* / *Done* end whichever row is the body's top row** (busk-chrome plan D13, D17,
2026-09-21): on the desk board the band draws the `RIG` label, the Elements menu and its steps, the
verbs, then the family pill, the **`BLIND` pill** and the desk chip **left-anchored after the
verbs**, the gap, and the host's `controls` — `BuskFocusControl` and `EditLayoutToggle`, filled by
`BuskingView` — right-anchored, in Split and Rig. The blind pill (`busking/BlindMarks.tsx`) is a
**reporter, never a toggle** — Blind is switched on the programmer's action bar and nowhere else —
drawn in the indicator's amber only while `ProgrammerSummary.blind` holds, on the terms the family
pill had until it became the mask's picker (absent means nothing to say), its hover saying *values and FX held* — Blind holds the
programmer's FX as well as its values (show-cue-editing plan D7), read through `hooks/useProgrammerBlind.ts`'s narrowed
subscription so the band does not re-render on the programmer's entry churn; in Pads the pad row
carries it in the same place, and off the desk board `RigStripContent` — the compact strip and the
short board's merged row — does, since Pads is the resting focus there and the sheet is an overlay
with no fold. The pill is the eye-off glyph plus the word, and **the word folds on a rung of the
host's** — the rig row's own `BLIND_WORD_CLASS` (1020, above every other rung, since that row's
ladder has no slack for it beside a mask pill), the pad row's and the compact rows' Focus-words
rung (their summaries are `min-w-0 flex-1 truncate` and absorb it) — with `min-w-0 shrink` as the
last resort, because the ladders were measured without it and the rig row is `flex-nowrap` above
its floor; `RigBand.tsx`'s ladder note names the four narrow bands where, blind *and* masked, the
pill squashes to a sliver rather than moving a control. It exists because D10 put the programmer report on the Show tab's
strip, which is invisible with the sheet folded or another tab open, and an immersive window has no
app header to carry `ProgrammerIndicator` — while on a busk page every press is a programmer write. **In Pads the band is not drawn**: its controls act on tiles —
the Elements menu, the steps and Clear narrow, move or release a selection made on them — and there
are none on screen, so the **pad row** (`BuskPageStrip` with `pads`, §The rig) is the top row and
takes the host's controls instead; the band's `focus="pads"` arm and its chevron pill lasted one
day. The **selection summary is drawn only in Pads**, on the pad row; in Split and Rig the lit
tiles say it. Under the band the pad row is tabs and the page mark and nothing else.
The row was two — a label row over a controls row — from the morning of 2026-09-21 to the evening,
and the merge cost 32px in every shape; before that the Focus control was on the page strip and
moved with the fold, and then briefly on the band's one label row, which a desk width with the
sidebar open filled to wrapping. **The handle is `RigHandle`,
a grip under the rows in Split, and it sets one thing: how tall the rig region is** (D6, rebuilt
2026-09-22). The region is a scroller with every line mounted, so the bar can rest between two
lines and the rows scroll under it; past the last line the region simply has room, which is how the
page is made smaller. The drag (a focusable `role="separator"`) is **live and magnetic**: within
`RIG_SNAP_PX` (10) of a line's bottom edge the height jumps to it and elsewhere it follows the
pointer, the last line's edge a snap point like any other; the arrow keys step between the same
edges, a double press restores the default (read off the two presses, not `dblclick`, since the press cancels `pointerdown` and Safari is the desk browser), and the fact is written on release only if the drag
moved. `snapRigHeight` / `stepRigHeight` are the pure rules, pinned by table in `RigBand.test.tsx`.
It is clamped **on read, never on write**: the band measures a ceiling off the column
(`data-busk-column`'s height less the fixed chrome in it — the band's row above the grid, the
handle and padding below it, the page strip — less the page's `PAGE_MIN_HEIGHT_PX` of 120) and a
floor of `RIG_MIN_HEIGHT_PX` (56), so a stored height taller than a window that has since shrunk
is drawn at the ceiling and comes back when the window does. **Never infer that ceiling from the
page body's remainder**: on the first frame the grid is at its natural height, so a rig taller than
the column has already collapsed the body to nothing and "grid + body − minimum" answers several
times the truth — a stored height past it was accepted and the page drawn off the bottom of the
screen. The chrome boxes do not move when the band overflows, and the column's box is the
viewport's. The handle is **mounted before the measurement** for the same reason: its 16px box is
part of that chrome, and a ceiling measured without it left the page 104 at the bottom of a drag. **The handle never changes focus.** The one it replaced counted whole lines
(`busk.rigRows`) and snapped past its ends into Rig and Pads, and the desk's report was four
inconsistencies from one design: the ends snapped as the pointer crossed them while the lines
snapped on release, the count could shrink only the rig and never the page, the bar could not rest
between lines, and only Rig had a way back drawn under the rows. Focus is the segmented control's
alone now. **The rig strip is the band's compact row without the rows under it** (`RigStrip`): the
same `pt-2.5 pb-2` box and `min-h-7` row, so the row and the Focus control at its end sit where the
band's do whichever is drawn — it was a 36px chrome row of its own, 4px off the band's row centre,
carrying a chevron before the Focus control that pushed the control along on that strip only. The
chevron is gone (the Focus control is the one way between the shapes on every board, D17's desk-board
rule made general) and the label reads **Pads**, since in Pads that row is the body's top row as the
desk board's pad row is. Only Rig's folded page strip keeps a chevron back to Split (`BuskPageStrip`,
40px at the bottom of the body, nothing beside it to line up against); the chevron pill under the
rows went with the ends. The band writes the resolved height
onto the grid from a **layout effect keyed on the value, never a `style` prop**: the handle writes
the same property per move, a prop would undo that on any re-render mid-drag, and a handle unmounted
mid-drag (a `focus: 'rig'` from another window) must leave no fixed height on a grid that has to
flex — its teardown touches the DOM not at all, because a passive cleanup runs after the commit
that cleared it. The grip takes pointer capture so a drag that runs off the bottom of the window
still delivers its release, `pointercancel` ends it as a release does, and the ceiling is read
through a ref so a resize mid-drag does not cancel the drag. The default is **measured, not
stored**: with the fact `null` the band measures the first `defaultBuskRigRows` lines' edges in a
layout effect (so the unmeasured first frame never paints) and again when the rows change or the
column resizes; every other reader of that default is gone. It is drawn for a **one-line rig too**
and **hidden off the desk board** — below `md` and on the short board, where the band is one row
with a row chip and the segmented control is the route. **Edit mode forces Split** for its duration, because a library drag
needs both regions, and restores the window's focus on Done by never having written it; the
control is disabled while editing rather than hidden, still lighting the stored focus, which is
what Done returns to. A project with **no pages** forces Split too: its first-open screen lives in
the page column, and a folded page strip has nothing to create a page from.

**A default is only a default.** Each store rests at `null` — *this window has not chosen* — and
the reader resolves it against the surface through the module's own copies of the short (500) and
cramped (750) height queries (`shortViewport.test.ts` pins both spellings) plus Tailwind's `lg`
for width: focus defaults to `pads` on a short viewport; the sheet to `speed` where docking the
288px rail leaves the page its 600px (`lg` and up), `none` where it would stack the page (iPad
portrait) or the viewport is short; the rig's default lines to three on a desk screen and two where cramped or
below `lg` — so a 1024×768 iPad in landscape gets two, and an **820-tall** one (1180×820) gets
**three**, since 820 clears the 750 fold; the `Tablets` board's "2 rows" never anticipated the
taller iPad, and `buskWindow.test.ts` pins both cases. `lg` rather than a measured body because the default has to be answerable off the busk
view: the announce carries these values from `Layout`, where there is no body to measure. Once the
window has chosen, the tab fact wins and survives a reload.

**`?focus=` and `?sheet=` are this window's on arrival, once, like `?page=`.** `BuskingView`
latches both raw strings at mount, `applyBuskArrival` applies the valid ones and marks the tab
**decided** whatever they held (both arms write it — a window arriving with neither has decided
too), and the view mirrors the two facts back into the URL with `replace`, gated on the
**rendered** tri-state `useBuskWindowDecided` for `useBuskPageDecided`'s reason. So the view's
address always carries both, a copied link reproduces the shape, and a reload finds the mirror's
own writes and is never an arrival.

**The sheet is one fact, and the fold is `none`.** There is no `sheetOpen` beside it, whatever
`Focus.dc.html`'s older sketch lists — `SideSheet` (`components/busking/SideSheet.tsx`) draws the
tab strip and the tab when the fact names a live tab and `SideSheetFold` (the shared 40px strip:
the beat, master 1's tempo, one glyph per live tab, the selection's colour off the stage's colour
dispatch, the head count) otherwise. What *is* kept beside it is the last tab that was open — a memory, not a flag —
so a MIDI `{sheet: 'toggle'}` **and the fold's chevron** unfold onto the tab the operator had —
both through `toggleBuskSheet`, the one reader of that memory (a glyph on the fold names its own tab). **Speed is `BuskSpeedRail`**
inside the sheet — filling the width the sheet is dragged to since 2026-09-21, where it was a fixed
288px column, and drawing no heading or caption (the family badge's sentence is on the badge's own
title) — **Colour is `ColourSheet`**, **Fan is `SpreadSheet`** and **Level, Position and Beam are
`FamilySheets.tsx`'s** (all below). **None of them draws a heading**: the tab strip names the tab, the rig band's row carries the mask (the family
pill and the desk chip), and what is selected is the lit tiles — or, in Pads, the summary in the
row's gap. **Colour, Fan and the family tabs keep their verbs in a static footer** under the
tab's one scroller, the save first on each (*Save as palette…* / *Save as Look…*), so the tabs
put their save in one place; the Colour footer is its own `@container` and *Pick* and *Fan…* fold
to icons below `FOOTER_WORDS` (340px — `ColourEditor`'s, applied on the docked host alone), since
at the sheet's 320px default the worded three wrapped. The Fan footer also carries **Live** beside **Apply** — with its word at every width,
since *Save as Look… · Live · Apply* fits the floor — and while Live is on Apply reads *Send again*
and stays pressable, the un-deduped resend. Their pickers are fluid (`ColourEditor`), taking the sheet's
width, and their picker rows are padded to the knob's half-width — a tab body scrolls, a scroller
clips at its edge, and a knob at 0% hung 14px past the square and was cut off at the 12px gutter. All eight have landed, and `LIVE_SHEET_TABS` stays the one list rather than
collapsing into the sheet vocabulary: Show landed exactly the way Colour and Fan did, FX the
way Show did, Level, Position and Beam the way FX did, and a ninth tab would too — **hidden**
there until its session — adding a tab to that
list lights it in the strip, the fold's glyph row, the Screens sheet's Sheet segment and the
toggle's memory at once — because a tab with an empty state is a promise the desk cannot keep, and
a fact naming a hidden tab draws the fold.

**Level, Position and Beam are the family tabs beside Colour** (apply-model plan §4.5, D10, session
7; `components/busking/FamilySheets.tsx`): each is the per-attribute popup's body over the busk
selection pair — the family's Palettes band, the editor, the FX band, and *Save as palette…* in
the static footer — the same `PaletteBand`, `FxBand` and `PaletteFooter` a cell's popup draws
(§The editor kit). **A tab is not one cell, so each names its attribute**: Level is the dimmer;
Position is pan and tilt as one; Beam is one Beam level — zoom, focus, iris or frost — picked from
those the selection carries (a *Beam attribute* row, drawn when there are two or more, zoom first),
and its band, its level and its FX band all mean that attribute, as a Beam cell's popup does.
**The editors are the fixture sheet's pick controls** (`LevelControl` from
`fixtureSheet/AttributeRow.tsx`; `PickPositionControls`, the pick row's pad, hollow dots and axes,
from `PickAttributeRow.tsx`; and `writePickPosition`), not a third copy of the level slider or the XY
pad, and they **write Local and only that**: a drag at once, a typed value at the programmer
fade. `familyHeads.ts` plans the writes — a group as one group entry where every member carries the
attribute itself, else each member's own entry (or its elements', for a bar whose dimmer lives on its
elements) carrying the group; a fixture its own head, else its elements; an element itself; a head reached twice
written once, a group entry falling back to member entries over a member already reached — and a
head without the attribute is left out, the tab saying how many (`headsWithout`). **A band chip is a press** through `usePress` with the busk pair under its mask and
`scope: 'programmer'`, so a programmer pointed at a focused Look layer elsewhere does not redirect it;
*+ FX …* is the picker's FX arm on the tab's attribute over the same pair.
`FamilySheets.test.tsx` pins the press per tab, the XY pad's `setPosition` and the Beam attribute row.

**FX is between Fan and Speed** (fixture-fx-sheets plan D20, session 5; apply-model plan
§4.5, session 7; `components/busking/FxSheet.tsx`, `BuskProgrammer.dc.html`). It lists what runs
on the busk selection — a pad's FX and the operator's own, and a cue's drawn **read-only** (no
editor, pause or stop; its home is the cue), never a script's (`fxOnSelection`: on a selected
fixture or its heads, an element or its fixture, a group or its members, and any group those fixtures are
in) — under *Running on 4 heads · 2 FX*, in the tray's two-line rows, and a row's name opens the
live editor under it: **`SplitFxEditor`, the FX popover's own**, over the busk pair's targets, so with
part of a Local FX selected it says *Spot 1 of Spot 1–4*, holds the edit and offers *Apply to
Spot 1* (`PUT /fx/{id}` with those heads as `targets`, the row following the edited copy) and *Edit
all 4* (§The fixture sheet's FX paragraph). **+ FX…** in the header opens the one picker's FX
arm (`ApplyPicker`, `library="fx"`, `scope: 'programmer'`) on the busk pair — a group kept one
group FX, its distribution intact — so a bare library FX lands Local, taking those heads off
the FX of its family they run; there is no second FX picker. **A pad's FX is edited as
the running instance only**: the row and the editor say ***edited*** while the instance differs from
the palette list's summary DTO, read once for every row, and the editor's footer is **Update palette**
and **Reset to palette** (§The fixture sheet's *A pad's running FX*); it offers no split
(apply-model plan P5). A pad's row has pause and no stop — the pad releases it; stopping the instance
alone would leave the layer to respawn it — and the operator's own keeps both. It is in the docked
sheet and the overlay form, its glyph the FX wave. `FxSheet.test.tsx` pins the reach, the
mark, the read-only cue row, *+ FX…*'s pair and a subset edit sending `targets`;
`SideSheet.test.tsx` and `buskWindow.test.ts` that each of the four tabs is in every place
`LIVE_SHEET_TABS` reaches — the docked strip, the overlay strip, the fold, the Screens sheet's Sheet
segment (`ScreensSheet.test.tsx`) and the MIDI `{sheet: 'toggle'}` memory.

**Show is the last tab, and it is why the busk view has no `ShowBar`** (busk-chrome plan D1–D6,
`ShowTab.dc.html`; `components/busking/ShowTab.tsx`). It mounts **`RunMobile`** — the phone runner
Show swaps to below 600px, one component and not a copy — over the transport `routes/Busk.tsx`
already holds through `useShowBarProps`, whose whole result the route threads down as `show`
(through `BuskingView` to both sheet forms) so the tab, a pad press and the fold act on one
transport. Its display state is `hooks/useRunnerDisplay.ts`, lifted out of `ShowPage` so the tab
and the phone cannot disagree about which cue is next; *Make live* is `useMakeStackLive`, lifted
beside it for the same reason, because the tab's picker **browses** as the phone's does and
`OffPlayheadBanner` says so, with requeue inert there. `RunMobile` gained a `strip` slot for it,
where the tab puts `ProgrammerIndicator` (the blind report and value count the bar carried). **The
cards open collapsed here** (D2), because the rig band is the stage on this view; it took a
`defaultExpansion` prop while the phone opened Current in Stage, and since venue-visit plan S3 took
the cards' stage map away both hosts open collapsed and the prop is gone. BACK · GO is the tab's static footer, the way
Colour and Fan keep their verbs; the grand master is the app header's panel (§The grand master). **No transport
keys on the busk view** (D5): Space on a focused pad presses the pad, and a key that also fired GO
would be two effects from one press on a live rig — GO is the footer, a MIDI `go` binding, or the
Show view one pill away; `Busk.test.tsx` and `ShowTab.test.tsx` both pin that `useTransportKeys`
is never called. **The tab strip is glyphs, and only the open tab gains its word, from 380px of
sheet** (apply-model plan session 7; `buskTabWordClass` in
`components/sheet/sidePanel.ts`). Eight worded tabs
want ~610px of strip, past the panel's 480 ceiling, so the five-tab rule — every word from 460, the
open one's always — went with the eighth tab: a closed tab is its glyph at every width, named by its
`aria-label` and its `title`, and below the rung so is the open one, marked by its fill. The docked
glyphs are **26px** (`px-1.5`): at the 320 floor the eight take 222px of the 244 the mode toggle and
the chevron leave the group, and at 380 the widest word, *Position*, makes them 275 of 304 —
measured in headless Chromium against the built stylesheet. The strip's **unpadded wrapper** is the
`@container` — `SheetPage.Header`'s convention, since a size query measures the content box and the
row's own `px-3` would fire the rung 24px early. The overlay strip takes the same rule and the same
wrapper with 32px glyphs (`px-2`): its seven are 236px, inside the right-hand form's 260 clear of the
sheet primitive's close cross, and an upright phone's bottom sheet is wide enough for the open word
(297 of 320 at 380). **That form is 320**, the sheet's own floor: the Show tab's strip — name, list, programmer chip, tempo chip —
fits at 320 with the stack name shrunk to its minimum (`RunMobile` makes the name the one
item that gives). **The fold shows the live cue number under the Show glyph** (D4) — green, an
em-dash with nothing on stage — from the **server** cursor (`transport.serverActiveCueId`, which
holds on the outgoing cue mid-fade), derived in `SideSheet` and handed in as `liveCue`; the strip
subscribes to nothing for it. **The Show glyph also carries an amber dot while the programmer is
blind** (`BlindDot` in `busking/BlindMarks.tsx`), in the tab strip — docked and overlay — and on
the fold: the sheet's one programmer report is the Show tab's strip, so the glyph says there is
something to open it for. A mark only, `aria-hidden`, never a control — the word rides the tab's
and the fold button's accessible names (*Show — programmer blind, values and FX held*,
`BLIND_NAME_SUFFIX`), because an `sr-only` span inside
those buttons was silent on the fold, whose `aria-label` wins the name, and *became* the name in the
strip wherever the tab's word is `display: none`; the pill on the band
(§The rig) is the louder half of the same answer.
Off the desk board the sheet is `SideSheetOverlay` — a bottom sheet on an upright phone, a
right-hand sheet where the viewport is short, through `useEditorForm`'s forms — opened from
the page strip's *Sheet* button onto Colour, carrying **every tab but Speed** (D6): Speed was withheld there because the ShowBar had the tempo chip, and the Show
tab's strip carries that chip now, so the reason is met by the tab that replaced the bar. The
library still replaces the whole region while editing.

**Its chrome lives in `components/sheet/sidePanel.ts`** — a column against the right edge of a live
view, folded to a strip of glyphs, a 40px header over a scroller — stated once
(`SIDE_PANEL_BODY_CLASS`, `_HEADER_BUTTON_CLASS`, `_STRIP_CLASS`, `_STRIP_CELL_CLASS`,
`_ENTER_CLASS`), `sheetFrame.ts`'s model: a surface that wants to differ says so at its own import.
The header is a tab strip and the fold is the module's too (`buskTabWordClass`). **There is no
`SIDE_PANEL_HEADER_CLASS`**: a panel's header is a 40px chrome row like any other, so it takes
`CHROME_ROW_CLASS` from `sheetFrame.ts`, and a panel-specific name for it would have been one
measurement stated twice. The overlay form's tab row takes it too, at `h-11`, so its inset is 12:
a tab row is a chrome row off the desk board as much as on it. The **fill is opaque** (`color-mix`,
not `bg-card/40`), because the panel has an overlay arm over live content. The **chevrons** are
`ChevronLeft` opens, `ChevronRight` folds, and the fold's chevron is the strip's own 40px cell. The
fold is **40px, not 44**: 44 is a touch handle's, and this strip is drawn only on a board wide
enough to dock. `Sheets.dc.html` §Folded, still cited above as the layout authority, draws that
strip at 44 — **the commit wins**, as elsewhere in this doc.

**The fold is a fact, not chrome**: `busk.sheet === 'none'`, a per-tab fact the announce, ⌘K, the
Screens sheet and MIDI all write. A *docked* panel takes no Escape; the overlay arm takes it through
Radix.

**The panel is dragged to width** by `useSidePanelResize` and `SidePanelResizeHandle`: a ceiling of
`SIDE_PANEL_MAX_WIDTH` 480 and a shared floor (`SIDE_PANEL_MIN_WIDTH` 260) that a panel may raise
for itself, with its own key and default in **`localStorage`** — `busk.sheet.width` at 320.

**A panel's floor is set by its header, and the sheet's is 320.** Three labelled tabs, the mode
toggle and the fold chevron inside the chrome row's 12px gutters measured 304px when the floor was
set, so at the shared 260 that row overflowed by 38 and put *both buttons outside the panel* —
reported from an iPad. The strip holds eight tabs now, and 320 still holds only because each is a
26px glyph with no word below 380px of sheet (§Focus and the side sheet). 320 and not 304 because a
minimum sitting on the exact fit clips again the moment anything joins the row, which is how this
broke: the row fitted until the mode toggle was added to it. A width stored below a panel's floor
is lifted on read. And the row **degrades instead of clipping** now — the tab strip is a `min-w-0
flex-1 overflow-hidden` group and the two buttons are `shrink-0`, so a row with no space loses the
end of the last tab rather than pushing a control off the edge.
`localStorage` and not the per-tab `sessionStorage` the other `busk.*` keys use, because a width is
a fact about this desk's screen rather than about which of two windows you are looking at. The
handle is drawn wherever the width on screen *is* the stored one.

**The frame that holds the width takes the panel's contents as `children`, and that is
load-bearing.** The width changes at pointer rate, so whatever holds it re-renders at pointer
rate; `DockedSideSheet` takes `children`, so the element references are unchanged between its
renders and React skips those subtrees — otherwise a drag would rebuild a colour picker or a fan
panel mid-drag sixty times a second.

**Never give the panel a bare `duration-*` or `ease-*` class.** `tailwindcss-animate`
re-declares `duration-*` as `animation-duration` while Tailwind's own still sets
`transition-duration`, and CSS's initial `transition-property` is `all` — so a panel carrying one
transitions **every** property, for as long as `usePanelEnter` latches the class, which is the
whole time it is open. What it broke was the resize: every width the drag set was *transitioned*
to, so the panel eased toward the pointer instead of following it. Reported from the desk as the
drag feeling like an animation. `SIDE_PANEL_ENTER_CLASS` says `[animation-duration:300ms]` and
`[animation-timing-function:…]` instead, which cannot leak.

**Beside the content, or over it** — `lib/sidePanelMode.ts`, one `push` | `overlay` fact, per tab
in `sessionStorage` like every other fact about how *this window* is arranged, toggled from
`SidePanelModeToggle` in the header. It rests at `push`, and it can only ever make the panel
**float where it would have docked** — never dock where there is no room, because the sheet keeps
its own floor (off the desk board it never docks).

**The panel never draws its strip and its body at once.** The fold *is* the closed state of the
panel — one control in two shapes — so the overlay is flush `right-0` rather than inset past a strip
and the strip's chevron is open-only. **But a floating panel keeps the strip's 40px in the row.** The
overlay is absolute and takes no room, so a strip that left the flow as it opened handed its width
to the view behind, which reflowed on every open and close — the thing floating is meant to avoid.
The busk sheet draws an empty `w-10` spacer before its floating panel, which the overlay covers.
**And the panel stands beside the view's menus, under nothing but the `ShowHeader`**, as the rig
band does.

**The panel animates its opening, and not its closing** (`SIDE_PANEL_ENTER_CLASS`,
`usePanelEnter`). The slide is the panel's **whole width**, bare `slide-in-from-right` as the
`Sheet` primitive uses. It shipped as `slide-in-from-right-4` — 16px — and read as no animation at
all, correctly: the content reflows by the panel's full width in one frame, so the eye takes the
jump and never registers a slide a fifth of its size. **The reflow itself stays instant in push
mode**, deliberately: animating it means animating `width`, which relayouts the page every frame,
needs `overflow: hidden` (which clips the resize handle, drawn 3px outside its own left edge) and
needs an inner fixed-width wrapper or the header's tabs reflow the whole way in. The flag is
**latched** while the panel is open — `animate-in` is a class, so a re-render that dropped it
mid-flight would cut the animation off part-way, and the panel re-renders freely while open — and
it is **false on the first render** whatever the state says, or a panel whose stored tab is open
would slide in on every arrival at the route and every reload. It must be computed in a component
that *outlives* the panel: `SideSheet` renders the strip or the body and stays mounted across the
swap, so a hook inside either subtree would see every appearance as a first render. There is no
exit animation because an exiting panel has to stay mounted for its duration, which here means
holding a colour picker mid-drag, a fan's plans and their subscriptions alive after the operator
asked for them to go.

**The Colour tab is the docked host of `ColourEditor`, and it writes literals to Local, and only
that** (D8, `components/busking/ColourSheet.tsx`; editor-kit plan D10). What is the host's: the
selection → write-targets planning (`planColourWrites` — a group as a group write, an element by its
element key, a whole fixture as one write or one per element for a pixel bar whose colour lives on its
elements; it is about the busk selection, not the editor), the rig order Pick reads in, the Fan
hand-over, the live push and its release, and the save sheet. Everything else — the picker, the
typed R/G/B, the emitter rows, the read-out, Pick and its hidden leaves, the Palettes and FX
bands (at the foot of the scroller, under the picker, where Recent sat), the footer — is
`components/editor/ColourEditor.tsx`'s, docked (§The editor kit). Every drag is
`programmer.setColour` per selected target, which is what a palette's *Apply as values* does and what the
programmer's colour cell does. There is **no layer arm and no ⌥ arm**: a picked colour has no
library referent for a layer to follow, and *Save as palette…* (`NewPaletteFromSelectionSheet`
over the sheet's targets, family Colour) is the route to something trackable. **The family mask is
not consulted**: the desk's mask gates presses, not value writes, so the sheet does not refuse under
a Position marquee — the rig band's family pill, one row up, is where the mask is read (the tab's
own header said *Colour of 14 heads · writes to Local* and repeated the band until 2026-09-21). A
band chip is a press and goes through `usePress` under the busk pair's mask like any other, with
`scope: 'programmer'` — Local, whatever the grid is pointed at — as the family tabs' do. The
writes go through `components/editor/useLivePush.ts`, the tempo fader's discipline lifted out of
`BuskSpeedRail` and made generic over the value: dedupe on an equality, a 50 ms floor, a deferred
value sent when the floor lifts, the release bypassing both. **A group is one group write only
where its members agree on emitters**; otherwise it fans to one write per member carrying
`sourceGroup`, because the desk writes a group colour verbatim per member and adds white only on a
head that has one — it can fold white for none of them, so pure white as a group write over a mixed
group would black out its RGB heads. **The buffer is seeded from the rig** — the editor's Pick on
mount and on every change of heads, retried until they have reported (`pickOnTargets`) — so a
single-channel edit leaves the other RGB bytes where the rig has them, as it does in the cell; the
emitters start at 0, since the appearance store exposes one folded colour and nothing per emitter.
The host hands the editor its write targets in **rig order** (`rigHeadOrder` over `effectiveRig`,
the desk's own order for an empty rig, as the editor's `headOrder`), which is what makes *the first
head in rig order wins* true there and *the first row of the marquee* true on the programmer. And
**the release is read from the window**, as the tempo fader's is, since the picker binds its own
release to the document; the host keeps its own copy of the channels for that flush and for the
Fan hand-over, told through the editor's `onPick` when Pick moves them. **The knob's seed is a
constant** (`NEUTRAL_CSS`): the editor's knob follows `combinedCss` while open, and here it is
always open, so routing every write back into that prop is the ping-pong the editor's own docblock
describes — the knob moves only when the editor means it to, on Pick. The
*Fan…* button — a plain button with a verb, since it opens a tab and holds no state a
`role="switch"` could ever read as checked — hands the current channels to `onSpread`, which both
sheet hosts wire (`SideSheet.tsx`'s `useSpreadSeed`): it opens the Fan tab with *From* set to
their **RGB** — a colour intent has no emitter component, so a white or amber the tab was driving
does not travel — through a seed the host holds and the Fan tab drops once it has read it, so a
later visit by any other door is not re-seeded with a stale colour. A host with no Fan tab to
open leaves `onSpread` out and the editor draws the button inert.

**The Fan tab is the docked host of `SpreadPanel`, and the client never lerps** (D9;
`components/busking/SpreadSheet.tsx`, `lib/spreadIntent.ts`; editor-kit plan D2, session 3).
What is the host's: the busk selection → the request's targets (`lookLayerTarget` — a group as a
group, an element by its element key) and the families the heads can take (`targetFamilies` over the
selection's write targets), the selection's mask, the mutation with the programmer fade read at
send time (`useSpreadMutation` in `store/programmerOps.ts` — REST for that file's own reason, the
structured reply), the colour palettes an endpoint may name (`isSpreadColourPalette` in
`fx/FxColourPalettes.tsx`), the *Over: Elements* count (`selectedCells` in `lib/cellsSubSelection.ts`,
the Elements chip's own expansion, so the chip and the tab cannot count elements two ways), the Colour
tab's seed hand-over, and *Save as Look…* in the footer's `save` slot. Everything else — the form,
the endpoint editors, Curve · Order · Runs · Over, Live and Apply / Send again, the keyboard, the
answer read for `skippedFamilies` (and in *About each position* for `base` and `clamped` too) — is the panel's, shared with the programmer's row C (§Sheet
kit), and nothing changed to the eye when it moved. The tab sends two intents of one attribute's
shape (in *About each position*, an amount per axis instead), a curve, an order, runs and an over-switch to `POST /programmer/fan`; the desk
interpolates in the intent's own space, resolves one literal per head through the same
`PaletteResolver` a palette's *Apply as values* uses, writes each into Local as an ordinary entry (so it rides
`programmer.entryChanged`, Record captures it, Blind previews it, Clear releases it), and answers
what it wrote. Only the desk knows a group's member order, each head's range, which elements a fixture
has and what a colour means on a head with amber — the same rule that keeps `paletteIntent.ts` a
serialiser, and `spreadIntent.ts` keeps it: it serialises `from` / `to` per family over
`paletteIntent.ts`'s own serialisers (a colour + policy, or a `pal:{uuid}` reference through
`colourUtils`; `pct:` for a level or a beam role; centre-relative `pos:` for a position; `dmx:` for an emitter) and
`spreadIntent.test.ts` asserts its import list reaches no resolver — and the panel's and the
popover's reach no lerp but `rawValues`, from the raw arm alone. **There is no preview strip**
(2026-09-21): there was one, drawn from `written[]` and `skipped[]` — one bar per head in the desk's
order — and it went because it cost the tab its height and the rig itself is the preview. The rule
it embodied stands and is why nothing replaced it client-side: the tab never shows what it thinks
the desk *would* do. Only the **latest** request's answer is read: Live keeps several in flight and
their answers can land out of order, so an attribute or selection change disowns whatever is in
flight (`requestSeq`). The family segment is **Intensity · Colour · Position · Beam** with a
**Attribute** row beneath it where a family holds more than one (zoom and frost under Beam): the
board drew *Zoom* as a fourth segment, conflating the family with the attribute. Curves are Titan's
four (`LINE` · `MIRROR` · `ARROW` · `MIRROR_OUT`, each drawn as a picture — *Mirror out* as **two strokes
meeting at a marked centre**, since its eight fractions are Mirror's and one polyline through them
*was* Mirror's V); order is a `SpreadOrder` name, worded by `lib/orderLabels.ts` — Normal ·
Reverse · Centre out · Random are
`NORMAL` · `REVERSE` · `CENTRE_OUT` · `RANDOM`, and pressing Random again bumps the request's `seed`
for a fresh shuffle. The design's *Stage L→R* is a **footnote under the row with the reason**, not
an option: the desk has no stage order today (`FanPlan` feeds `POSITIONAL` a head's index, which
is Normal again), offering it as something it is not would be worse than withholding it, and a
disabled item in the row wrapped at 288px and read as a control that was merely off. A position
spread's defaults are **±16 about the centre of travel**, centre-relative (`pos:`, venue-visit plan
D3), so they never sit against a hard stop. *Over: Elements* carries the element count where a selected fixture has elements, and stays pressable
where none does — the choice survives a change of selection, an empty one included (it is the
window's, `lib/spreadOver.ts`), and over heads with no elements the request says Heads
(`effectiveSpreadOver` — the desk would make the same substitution, each fixture without elements
being its own unit).
**Live** sends every adjustment through `useLivePush` with an equality over the whole request, the
release read from the window as the Colour tab's is; off, only *Apply* writes. An explicit Apply
always sends — and stays pressable while Live is on, reading *Send again*, because it is the one
un-deduped resend after a write the desk refused; turning Live on sends nothing by itself. The
window release flushes only while Live is on, since the switch can be toggled from the keyboard
with no `pointerup` to clear the gesture; the typed fields keep a draft and commit only a number,
so clearing one writes nothing and a leading minus can be typed; and the `skippedFamilies` toast is
keyed like the endpoint's error toast, since a Live drag under a mask answers it on every write.
Under an empty selection nothing is sent and the panel's own sentence is toasted — the desk would
answer `FAN_NEEDS_SELECTION` otherwise, and `errorToastMiddleware` renders every 400 (the
mutation is **not** in `SILENT_ENDPOINTS`, keyed so a failing Live burst replaces one toast), so the
tab must not say it twice. **The mask is honoured by the desk, not pre-refused here**: an attribute
outside the selection's families writes nothing and answers `skippedFamilies` — a 200, never a
refusal — toasted in `skippedRowsMessage`'s vocabulary; the tab opens on the first family the
mask names that the selection can take. The colour endpoints share one `ColourEditor` (compact,
footer and read-out off — the busk tab's shipped measurement, which every host of the panel now
takes) for whichever end is being edited, and its `combinedCss` is a **seed** that moves only when
the panel means the knob to move (switching ends, Swap, the Colour tab's hand-over) — never the
picker's own writes, for the ping-pong reason documented on that prop.

**A fan is a result, not a palette** (D10). *Save as Look…* opens `RecordLookSheet` over the
selection — `record-look`, the same gesture every busked state is kept by; the sheet gained an
optional `targets` prop for an opener whose selection is the desk's rather than the programmer
list's — and nothing on the tab mints a palette. A "fan palette" would need a second grammar
(two intents plus a curve) and a resolver that knows the selection's order at cook time, which no
palette does. The band's *Fan…* verb and its compact menu item open the tab, writing
`busk.sheet` and nothing else.

**Short beats narrow.** `BuskingView` draws one of three boards: `md` says desk or narrow, and its
own copy of the 500px height query (`shortViewport.test.ts` pins the spelling) says whether a
window wide enough for the desk board has the height for it. A landscape phone is wider than `md`
with ~350px under the ShowHeader, so on the **short board** the rig strip and the page strip merge into
**one 32px row** — `RigStripContent`'s pieces in `BuskPageStrip`'s `leading` slot while the rig is
folded, the same pieces and not a third strip — Split shows one row of 48px tiles with the row chip
(`RigBand compact`), the side sheet **overlays** rather than docks (neither the fold nor the docked
rail is drawn; `SideSheetOverlay`'s right-hand form, Colour in its compact layout, from the merged
row's *Sheet* button), and *Edit layout* is withheld as it is below `md` (`editable` on the strip),
since a library drag needs both regions on screen. The defaults — Pads, the sheet folded — were
already the ladder's; only which board is drawn changed.

**The facts ride the announce and the Screens sheet sets them** — see §Windows, full screen and
the hand for `viewOptions`, and `lib/windowViews.ts` for the descriptor the sheet renders from.
⌘K offers *Split · Focus pads · Focus rig* for this window only while it is on the busk view, and
*Show Busk on <window> · Focus …* as a two-frame arm on the show (`buildWindowCommands`).

**The columns of a row stack below 600px of the page body's width, and editing is desktop-only.**
`PageRow` hands its twelfths → `fr` tracks to the grid through a CSS variable so a container query
(`@max-[600px]:grid-cols-1`, on `BuskPageBody`'s `@container`) can override them — an inline
`grid-template-columns` could be overridden by nothing. It is the body's width and not the
viewport's because the rail or the library has already taken its share: at `md` the body is ~480px
beside the rail and ~408px beside the library, and four quarter-columns need ~600px before each can
hold one 110px pad. Edit mode stacks too, with a gutter drawn as a strip between two stacked columns
(still "the new column before column N"). Below `md` the library is not shown and *Edit layout* is
withheld with it — and on the short board too, through the strip's `editable` — by decision:
narrow widths get play mode only, and an edit mode with nothing to drag from is a trap. `Done` stays at every width so a window narrowed mid-edit can leave.

### The rig

**The target band is a document the operator builds, and an empty one is the band as it was.** The
busk view's top region draws the **rig** (`lighting7/models/buskRig.kt`, busk-further plan D1–D3):
rows of tiles, each tile a group, a fixture, or one **element** of a multi-head fixture; one rig per
project, `GET` / `PUT /busk/rig` whole-document with the page write's three refusals
(`BUSK_RIG_INVALID` / `_IDENTITY` / `_REF`), tiles renumbered dense and answered with the ids the
write minted. `api/buskRigApi.ts` is the wire, `lib/buskRig.ts` the document model (addresses
`rrow:{r}` / `rbody:{r}` / `rgap:{r}` / `rtile:{r}.{t}` / `rpal:{kind}:{key}` / `rnewrow`,
`applyDrop`, `normaliseRig`, `toRigRequest`, `nextRowName`), `components/busking/RigBand.tsx` the
band, `RigTile.tsx` the tile, `RigPalette.tsx` the library's **Rig** tab, `RigEditProvider.tsx` the
band's half of the one `DndContext`, and `useBuskRigCommit` in `store/busk.ts` the operation queue —
`useBuskLayoutCommit`'s model over one document, response written when the queue drains, a refusal
toasted **by code** (`rigWriteFailureMessage`; `saveBuskRig` is in `SILENT_ENDPOINTS` so the
middleware does not say it a second time, generically). `TargetBand`, `TargetList`,
`TargetListItem` and the narrow-width *Pick targets…* sheet are deleted (D15).

**The show-all fallback is the client's, and its order is the desk's.** The server stores what the
operator built and answers an empty rig as empty (`{}` on the wire — lighting7 omits a defaulted
empty list, so `rows`, `tiles` and a patch's `elements` are all optional on this side, the
`PaletteSummary.rows` rule); `effectiveRig(rig, groups, fixtures)` then draws a `Groups` row and a
`Fixtures` row from the two lists the view already holds, **in the order `GET /groups` and
`GET /fixtures` answer them**. That order is not a choice made here: `state/BuskRigOrder.kt` walks
the rig for `selection.subselect`'s *Next* / *Prev* and answers every group then every fixture for
an empty one, and the two must agree. `buskRig.test.ts` pins `effectiveRig` and `rigSteps` against
the server's own fixture — the test imports `../src/test/resources/busk/rigOrder.fixture.json`,
the file `BuskRigOrderTest` reads, straight from the backend's tree (it was a hand-refreshed copy
while the two were separate repos). The fallback has **one render path** with the
built rig: its rows are rows like any other, only their tiles carry no address, take no drop and
are drawn dimmed behind the new-row zone while editing an empty rig.

**A multi-head tile decides how it shows its elements** (D3), on the tile rather than in a mode:
`cellMode` is `PIPS` (the whole fixture, elements as read-only pips — the default), `WHOLE`, `PER_CELL`
(one tile per element) or `HALVES` with `cellSplit` (2 up to the element count, contiguous runs cut the
desk's way — twelve elements in five are 3 · 3 · 2 · 2 · 2, `runsOf`); a tile dragged in as one element
carries its `elementKey` and is `WHOLE`. `expandTile` is the one place a stored tile becomes what is
drawn and pressed, and **an element is `{type: 'fixture', key: element.key}`**, the shape
`rowLocateTarget` publishes — element keys come from `patch.elements[].key` and are never parsed.
`useBuskingSelection` rehydrates an element target by looking the key up in its parent's own
`elements`, so a fixture tile reads `some` with a `1 of 4` count when an element is selected anywhere,
and `all` when every element is — with the badge still `4 of 4`, because an elements-all tile and a
parent-selected tile are two different selections (the desk's `TargetCoverage` covers an element by its
parent and never a parent by its elements) and the press differs: **from `all` a tile goes dark** — the
parent toggled off, or every element toggled off when only the elements were selected — and from anything
else it is the whole fixture, the run tile's rule. A run tile toggles each of its elements, and
*Fan…* opens the side sheet's Fan tab (§Focus and the side sheet).

**A pip is a press of its own, and an element is not a new target kind** (busk-further plan D11,
session 7). A `PIPS` tile's elements are checkbox-role buttons in a row **beside** the tile's button —
a button cannot hold buttons — and a tap toggles `{type: 'fixture', key: element.key}` through the
one `toggleTarget`, the shape `rowLocateTarget` publishes; a drag across them is a **run**, each pip
crossed toggled once, with the marquee's arm rule: a mouse runs from `pointerdown` under pointer
capture, a `touch` or `pen` runs only after a 500ms hold (a finger pans; a held one runs, and a
non-passive `touchmove` guard stops the pan for as long as the run lasts), a pip rests at the
board's 8px and the one under a held finger grows to 44px, and the click a run's release generates
is swallowed so it is not a second toggle. **A pip reads checked while its parent is selected**, and the band folds the parent's elements
into `selectedCells` for exactly that: the desk narrows the parent on such a press, and a dark pip
whose press deselects is the reading `presenceOf`'s comment already refuses for an element tile.
**The desk owns the parent↔element coverage rule** (`fx/TargetCoverage.kt`): a layer on the
whole bar covers a press on its elements, a layer on four elements does not cover the bar, and
`lookPresence.ts` keeps reading the desk's resolved `applied` extents with no browser copy of that
rule — `lookPresence.test.ts`'s cells block pins that an element selection lights from what `applied`
names and from nothing here.

**The Elements menu is one desk op, and an unlinked window mirrors it** (D12, revised 2026-09-21). The
seven **filters** — All · Odd · Even · 1st half · 2nd half · Invert · Masters only — in one menu whose
label names the filter last pressed, and *Prev* / *Next* as two **step buttons** beside it
(`SUBSELECT_FILTER_MODES` / `SUBSELECT_STEP_MODES`), because a step moves the selection along the rig
where a filter narrows it, and the two read as one control only while they shared a chip's face —
each a press of `selection.subselect {mode}` (`api/selectionApi.ts`'s `subselect`,
`store/selection.ts`'s `subselectDeskSelection`; `SubselectMode` is the backend's nine names, pinned
against the server's fixture) while the window follows the desk, which rewrites the selection's
**targets** over rig order, keeps the mask, and answers with the ordinary `selection.state` frame.
The desk keeps **no** sub-selection state, so nothing on the menu's face is derived from the
selection: *Elements: Odd* is only the filter last pressed here, and a step is never remembered as it. Unlinked, `useBuskingSelection`'s
`subselect` runs `lib/cellsSubSelection.ts` over the tab's copy — and **only once the rig has
answered** (`RigBand`'s own `rigLoaded` guard), because `effectiveRig(undefined, …)` is the show-all
fallback and a press before the query lands would walk the wrong order with no frame to correct it — the nine modes over
`effectiveRig`'s rows, the fixtures' `elements` and the groups' members derived from each fixture's
`groups`, put in the group's own order by `GroupSummary.memberKeys` (venue-visit plan S7; fixture-list
order where a desk sends none — a session 7 amendment to D12's "member lists it already fetches") —
pure, no React, and **pinned against the server's own fixture**: `cellsSubSelection.test.ts` imports the backend's
`../src/test/resources/busk/subselect.fixture.json`, as `buskRig.test.ts` does the rig order's, and
runs every case. The Fan tab's *Over: Elements* counts through the same
module (`selectedCells`), so the chip and the tab cannot count elements two ways. Below `md` the nine
modes sit under an *Elements* heading in the band's verbs menu.

**The live bar reads the stage's colour dispatch.** `RigTile` mounts one `FixtureAppearanceSource`
leaf per fixture tile — one of **four** mounting readers of `components/fixtures/fixtureAppearance.tsx`,
beside the Positions panel's chips, the side sheet's fold (one leaf for the selection's colour dot)
and the colour editor's **hidden leaf per parent fixture of the heads it is editing**
(`components/editor/ColourEditor.tsx`, the busk Colour tab's and the programmer's colour cell's
alike since the editor kit's session 2), which reports into `lib/liveAppearance.ts` for *Pick*
(§Focus and the side sheet, §The editor kit) — and an element or run tile draws its own elements' colours off
the parent's per-element `segments` by the element's **position in the patch's element list**, an
index into a list the desk ordered. A group tile has no channels of its own and draws no bar.
**A tile's colour is a 4px band inside its top border** (§Fixture colours): a group tile's is the
group's own, every other tile's its fixture's, resolved by the band (`tileColour`) and handed down as
a key, so the border and fill stay the selection's.

**Two wire facts the client has to live with, both recorded as session 3 amendments.** The rig GET
embeds `GroupSummaryDto`, which carries **no id**, while the PUT names every group tile by `groupId`
— on a kept tile as much as a new one — so `toRigRequest` resolves a group through the **patch
list** (`FixturePatchDto.groups[].id`, `rigIdsFromPatches`), the one place the desk publishes one,
and a group with no patched member is refused **here, by name**, before a PUT the server would 400
(`RigRequestError`). And **the hand cannot hold a group or fixture**: `hand.pickUp {kind, id}`
takes a `BuskPadKind`, so the `rig-row` target in `lib/handTargets.ts` answers false for every
kind, the row's `HandPlaceStrip` is mounted and wired (the rig PUT through the queue, then
`hand.drop`, Undo the rig as it stood) and never lights, and `RigBand`'s `rigRecordOf` is the one
function to teach the day the desk can hold one. Neither is worked around client-side beyond that.

**The band joins the one `DndContext` with `r…` ids and `rig-` data**, and the two documents are
mutually foreign: `parseBuskDragId` answers null for every rig id and `parseRigDragId` for every
page and slot id, `canLand` in `buskDnd.ts` refuses a rig source everywhere but the rig and a page
source everywhere on it, and each provider carries a **`foreign`** flag — set when something not its
own is lifted — that its droppables disable on, so a rig tile crossing a bank lights no ring
`canLand` would refuse. `resolveRigDropTarget` is `resolveDropTarget` over the rig with the same
four rules (self-hover ahead of the hysteresis, the hysteresis ahead of the null-over arm, a sticky
slot within one row's body, the half-of-the-tile test only on the tile dnd-kit measured), and
`buskDnd.test.ts` composes the slot and the landing place for a downward drag within a row. **A row
is created by the first thing dropped into it** (`rnewrow`): the server refuses an empty row, so
`+ Row` is the new-row zone rather than a button that mints a placeholder, and `normaliseRig` drops
an empty row while keeping an empty rig. Rows reorder by their grip onto the gaps drawn while a row
is lifted; `NameField` (extracted from `BuskBank`'s private `BankNameField`) is the row's name as it
is the bank's, and **a tile's too**: every in-document tile carries a menu while editing (the cell
modes on a multi-head fixture, *Rename tile…* on every kind, *Remove from rig* last), and *Rename
tile…* swaps the tile's face for that field, seeded with the name shown, writing the tile's
**`label`** — the column the rig has carried since session 3 and the UI §11 found missing. Saving
the record's own name clears the label rather than storing a copy of it (`relabelTile`, which
takes either kind; `setTile` is the cell-mode mutator and leaves a group alone). The seed is
`tileOwnName`, which follows how `expandTile` applies a label: the name shown where the label
*replaces* it (a group, a whole fixture, a single-element tile — `Bar L · Cell 3`), the fixture's own
name where the element names *compose on* it (per element, halves), and every drawn sibling of one stored
tile takes the one label. `tileInput` sets the label **before** the GROUP arm's early return — a
label set only on the patch path silently reverted every group rename on the drained response. **A row is laid
out the way a bank is** (2026-09-21): it carries a `width` share in twelfths and a `flow` — the
bank's two facts, plus `SCROLL`, the sideways-scrolling line every row was and still the default
(`rowFlow` / `rowWidth` read an absent field as the default, for a desk that predates the two
columns) — set from the row's `…` menu in edit mode (`setRowLayout`). The rows fill a twelve-track
grid in order (`rigLines`), so two half-width rows share a line, and the band's unit is the **line**:
the handle snaps to a line's edge and the default height counts lines. `toRigRequest` sends the two only where
they differ from the defaults, since the desk's Json refuses a key it does not know and a desk
mid-upgrade must keep accepting a rig nobody has re-laid-out. The drag handle (D6, §Focus and the
side sheet) reads and writes the window's `busk.rigHeight`, never the focus, is drawn for a
one-line rig and hidden off the desk board (below `md` and on the short board alike, `!compact`);
every line is mounted in Split and in edit mode alike — the one scrolls, the other cannot hide a
row that has to take a drop. **The one row folds in a fixed
order, and its floor is two rows by design** (busk-chrome plan D15, `Band.dc.html`; the thresholds
are re-measured in the app and are `RigBand.tsx`'s to move): the band is its own `@container` and
carries `data-focus`, and the row gives up its words in this order — the **verbs' words first**
(`VERB_WORD_CLASS`: every verb is the desk's outline button with its icon; *Edit layout*'s word
goes with them, `EDIT_WORD_CLASS` — one ladder for Split and Rig, the two shapes the row is drawn
in since session A.5); then the ***Elements:* prefix and the Focus words** at one width in
both (`CELLS_PREFIX_CLASS`, `FOCUS_WORD_CLASS`) — **the Elements control keeps its mode word**
in its short form, All · Odd · Even · 1st · 2nd · Invert · Masters (`CELLS_SHORT_LABELS`), and is
never a bare glyph, since *All* is the state an operator most needs to be sure of; then the `RIG`
**label folds to nothing** (D20, `RIG_LABEL_CLASS`; `data-rig-row` is the handle a test reaches the
row by, since the label no longer is); and **below the floor
the row wraps at exactly one place** — the verbs group on the first row, the state group (pill,
chip, Focus, Edit layout) taking the second whole — through `TWO_ROWS_CLASS` /
`SECOND_ROW_CLASS`, **not `flex-wrap`'s own choice of break**: above the floor the row is
`flex-nowrap`, so a control never lands mid-row; only under it may the verbs group wrap within its
own line (`FIRST_ROW_CLASS`), the last resort for a band narrower than the ~330px the iconic verbs
need, which the desk board reaches with the sidebar open and the sheet at its 480 ceiling, and
which a size-contained `@container` would otherwise paint over the sheet. The desk chip is given
`min-w-0 shrink` on this row — both words, because `FollowPill`'s base is `shrink-0` — so its value
truncates before any control moves; the link badge it draws while following is `shrink-0` and
never gives. The rungs are **1100 / 790 / 660 / 615** (780 until terminology plan session 2's longer *Elements:* prefix) since fixture-fx-sheets session 5 put
***Release n*** after the verbs (D18: the selection's values and local FX out of the
programmer, one `programmer.clearTarget` per target — an element by its own head key — at the programmer
fade, pad layers left lit; it folds with the verbs at `VERB_WORD_CLASS` and sits in the compact
verbs menu above Clear; `ReleaseTargetsButton`, `useReleaseTargets`), re-measured in the app with a
60px mask-pill stand-in (`RigBand.tsx`'s docblock has the measurements). They were **990 / 730 /
610 / 570** since desk-follow session 2 put the badge on the resting row — 21px plus a gap —
960 / 700 / 580 / 540 with nothing there, 1100 / 820 / 700 with a chip,
860 / 680 while the summary had a row of its own, and 1150 / 1000 before that, when the verbs
shared the label row with the summary and the chips. The compact boards keep their one `flex-wrap` row — the row chip, the
summary, the verbs menu — and their Focus words fold at that row's own measure
(`COMPACT_FOCUS_WORD_CLASS`, 680), not the desk row's. **A
tile's cross and menu sit inside its top-right corner**, and **a tile's menu has *Fixture
sheet…*** (fixture-fx-sheets plan D21, session 5) — *Group sheet…* on a group tile — which opens
D1's sheet over the busk view (`BuskFixtureSheet`: on a phone the sheet's `phone` host in
`PhoneSheet` by `useEditorForm`, modal, the same three-height bottom sheet the Stage view opens —
session 6 — and on a desk the pop-up's own 512px right-hand sheet, `host="popup"`; a group's member
opens in place). In play mode it is the tile's right-click or long-press menu (`useLongPress`
dispatching the context menu as a pad's hold does, the click that ends a hold swallowed); in edit
mode it joins the tile menu's items. The band holds which sheet is open and hands tiles the door
through `BuskFixtureSheetContext`. The cross and the menu sit there because, hanging 7px off the corners they were
clipped by a `SCROLL` row's body and overlapped the next tile's at the 8px gap. **A selected pip is
the accent, solid** (`Cells.dc.html`'s `pip.on`), and **a dark head draws no live bar** — the bar
sits inside the tile's border and is transparent at zero intensity, where a 15% floor painted a
faint line over a dark spot's bottom edge. Below `md` the band is one row with a row chip and the verbs in a menu,
and there is no editing there; in Rig focus below `md` every row is stacked two tiles across
(§Focus and the side sheet).

**In Pads the pad row is the body's top row, and the rig row is not drawn** (busk-chrome plan D17,
session A.5; `BuskPageStrip` with `pads`, `data-pad-row="pads"`). It carries, in order: the `PADS`
label, the page tabs **at the rig row's control size** (an `h-7 p-0.5` group of `h-6 px-2 text-xs`
items — the Focus control's segmented look; they were larger and rounder than everything beside
them) with the **page mark** beside them (§The busk layout: the link badge naming the windows paged
with this one, or *Page: Own*), **Fan · Locate · Highlight** as icon verbs, the selection summary in the gap (`min-w-0`,
truncating, its whole text on the title — nothing else says it in Pads), the family pill — the
mask's picker (§The desk selection has a mask) — the selection's **link badge** (Pads always follows the desk selection — desk-follow
D2 — so here the desk chip is only ever the badge; D17 put the chip on this row since there is no
rig row to carry it), then the host's Focus control and
*Edit layout* / *Done*. **No Elements menu, no steps, no Clear**: those act on a selection made on the tiles, and the
use this view is built for is a two-screen desk with the rig on one screen, so a rig operation is
reached there; the three verbs act on the rig from either screen. In Split and Rig the same row
(`data-pad-row="split"`) is the tabs and the page mark only, and it wraps as it always did for edit
mode's name field. **The three verbs are one hook instance**, `useSelectionVerbs` in
`components/busking/selectionVerbs.tsx`, called once by `BuskingView` and handed to whichever row is
drawn — `RigBand`'s `verbs` prop in Split and Rig, the pad row's in Pads — so a window has one
Highlight capture and one locate fold and the two rows cannot answer a press two ways;
`SelectionVerbButtons` draws the three with the band's `aria-label`s, and the band adds Clear after
them. **A held Highlight releases when those buttons unmount**: `useHighlight` releases on its own
host's unmount, and its host is the view now, which outlives a focus change — so a MIDI
`buskFocusSet` mid-hold would otherwise take the band and the held button away with the dimmers
pinned at full. `RigStrip` and the short board's merged row carry the same pieces without a chevron
since 2026-09-22, and `RigHandle` has one arm, Split's. **The pad row's `@container` is a wrapper and the row is its child**, as the band's is:
a query container is the nearest *ancestor* and never the element itself, so a floor class on the
container element has nothing to match — the first cut did that, and below 680 the first group
took `w-full` in a row that never wrapped and pushed the Focus control under the docked sheet.
jsdom evaluates no container query, so `BuskPageStrip.test.tsx` pins the *structure* — and that
the 12px gutter is the wrapper's, so the container's content box is the row's and the rungs are
numbers on that box, as the band's are.

**A chip is drawn while its window is unlinked, and a linked window says so with a badge**
(busk-chrome D18, revisited by desk-follow D7 and D8). Unlinked, `DeskChip` draws the dashed
*This window* and `BuskPageChip` the dashed *Own*, each pressing back. Following, `DeskChip` draws **`LinkBadge`**
(`components/desk/LinkBadge.tsx`) — a 20px glyph, glyph only at every width, accessible name
*Following the desk selection*, whose press unlinks (D11; a mark instead in Rig and Pads), `shrink-0` — where D18 drew
nothing: a pill saying *Desk* all night was noise, but a window saying nothing about which selection
it is on left the operator to remember (Chris, 2026-09-23: "always show when we're linked, even if
it is just a small badge"). `BuskPageChip` draws the same badge while paged with the desk, naming
the other windows paged with it (§The busk layout), its names on each host's fold class. The *· from <window>* readout went with D18 and stays gone: an operator at a
two-screen desk knows which screen they are selecting from, and `deskReading` is deleted. `FollowPill` keeps the solid form and a `from` part all the
same, each part under a class of its own (D19: the host's rung for the suffix and for the subject),
with the **whole reading as `aria-label`** — `Targets: This window`, `Page: Own` — so the
name a test or a screen reader gets never changes with the width. The desk chip is on the rig
row in Split and Rig (`CHIP_SUBJECT_CLASS`, the Focus words' rung) and on the pad row's state line
in Pads (`PAD_CHIP_SUBJECT_CLASS`, both); the page mark is beside the tabs. The way *out* of
following is the badge's own press (D11), with ⌘K's *Stop following the desk selection in this
window* and the Selection segment on the window's row of the Screens sheet, from any window
(`windows.follow`, desk-follow D4), beside it — the Screens row being the door for a touch-only
screen set up from another; there is still no MIDI target (`FU-SURFACE-SELECTION-FOLLOW-SET`).
**Rig and Pads always follow** (§One selection, two shapes), so on those shapes only the badge is
ever drawn, and drawn as a mark.

**The words come back under the floor, as closed ranges** (D19). Below the floor each row has its
whole line, so what the ladder took returns while the line holds it and folds again at a second
measured rung — every re-expansion a **stacked `@min-[…]:@max-[floor]:` variant**, which overlaps
no rung above the floor, so no rung depends on the order Tailwind emits the rules in. On the rig
row the verbs' words no longer return at all since *Release n* joined them — worded with the prefix
folded the line is 624, wider than the 615 floor — and the prefix's range runs from 420 up to the
floor (before Release: the words from 520, the prefix from 350 to 520); on the state line *Edit layout*'s word returns from 400 and the Focus words and the chip's
subject from 340 (the badge's 29 on that line, and the toggle measured at 36 rather than the 32 the
first note assumed). **The pad row has a ladder of the same shape and its own numbers**
(`BuskPageStrip.tsx`'s docblock: 1200 / 1120 / 900 / 810 / 770, then 610 · 560 · 530 on the first
line and 560 · 490 on the state line under the floor, with both badges) — measured on the dev rig's
two page names, since the tabs have no ceiling and a longer name moves every rung by its width. The
page mark is budgeted at its **wider resting form**, *Own* (57) rather than the glyph (21), since an
own page is an ordinary state, and its names and *Page:* go first, at 1200 — the badge's name is
capped at 96px so that rung has a ceiling. The merged row (names 820, *Page:* 760), the folded strip
(440, 400) and Split (410, 360) carry the mark on rungs of their own, in the same docblock. **The
labels fold last before each floor** (D20): `RIG` at 660 (610 before *Release n*) and `PADS` at 810, to nothing, and neither comes back under it. Both ladders
are pinned as orderings, not numbers, by `RigBand.test.tsx` and `BuskPageStrip.test.tsx`.

### The hand

**The desk holds one record between two screens.** A palette, a Look or a cue is *picked up* on
any window and *placed* on any other — the cross-window move, deliberately instead of a pointer
drag, because the window that saw the press keeps the pointer for the whole gesture and the
neighbour never receives a pointer event of its own. Backend contract in `lighting7`'s
`state/HandState.kt` and `plugins/HandSocket.kt` (17140b3), the wire in
`docs/websocket-engineering.md` §Hand; the plan is
`../docs/plans/completed/multi-screen-plan.md` §3.5 and D12, and where
that plan's sketch and the shipped commit differ, **the commit wins**.

**Three frames, and none of them places.** `hand.state {item?}` is the connect snapshot and the
broadcast; `hand.pickUp {kind, id}` resolves the record **in the current project and nowhere else**;
`hand.drop {uuid?}` lets go. There is no `hand.place` and **you must not add one** — every target
already has a mutation with its own validation, so one frame that placed would reimplement four of
them behind a single name. **A place is the placing window's own mutation followed by `hand.drop`**,
and its Undo, offered for ten seconds (`HAND_UNDO_MS`), takes that mutation's own history entry back
(§Undo) — the rig row's, whose write the rig's commit queue makes later, is still its inverse.

**A drop after a place names what it is dropping; the chip's × and Escape do not.** A place is two
independent round-trips — the window's mutation, then the drop — and another window may have picked
something up in the gap, so a bare `hand.drop` would clear an item this window never touched, on
exactly the two-screen case the hand exists for. `useHandPlace` in `store/hand.ts` is the one owner
of that sequence and sends `hand.drop {uuid}`; `handDrop()` beside it is the bare form and is what
the × and Escape call, because "let go of whatever is there" is precisely what those two mean.
Getting it backwards is **invisible in one window**, which is why `hand.test.ts` asserts the uuid
rather than the call — a test that accepted a bare drop would pass with the bug in. A failed
mutation drops nothing and toasts nothing: the operator still has the item, and
`errorToastMiddleware` has already reported the refusal.

**Every hold carries a server-stamped `holdId`**, monotonic, which is the identity of *this* hold
rather than of the record. It exists because `MutableStateFlow` conflates by `equals` and two
pick-ups in one clock tick would otherwise be indistinguishable. On this side it is the chip's React
`key` and the `data-hold-id` it draws: two hands of one record differ in nothing else, so without it
neither a test nor a later session can tell "still holding" from "picked the same thing up again".

**`store/hand.ts` is form 3** (`onCacheEntryAdded`), not the form 1 the plan's bullet names, and
`store/windows.ts` is the precedent that settles it in two steps. `HandChip` mounts in `Layout.tsx`,
so the module is on the **earliest render path** — the case §"Where a WS bridge subscribes" reserves
for form 2, where a module-scope `lightingApi.hand.subscribe(…)` can throw a TDZ `ReferenceError`
that `tsc`, `vite build` and the tests all miss. And `hand.state` is a **stream** carrying the whole
item with nothing to refetch, so there is no invalidation for a bridge to dispatch; a `queryFn` that
closes over `lightingApi` touches it only when the first reader mounts and needs no
`startHandBridge()`. There is also **no `open` branch**, and that is `selectionApi`'s rule rather
than `windowsApi`'s: the desk pushes the snapshot on every connect, and a write on connect would be
this window silently changing the desk's hand.

**The ghost is frozen and hookless.** The frame carries the record's **own summary DTOs**, exactly
as `BuskPadDto` does, so `HandChip` builds its face through `padFaceOf` and subscribes to *nothing
about the held item* — `dragOverlayRegistry`'s rule, for its reason: an FX palette's detail line
reads a live speed-master label through a hook, and a chip that can sit on screen for five minutes
across every route must not mount one per hold. The cost is `FU-DTO-RECORD-SUMMARY` in lighting7:
the embedded `usage` and `buskPageCount` were computed at pick-up, so a long hold can read "on 3
pages" after a fourth was added. Intended — a pad's face is frozen between reads too — but **do not
build anything that presents those two fields as live**.

**Drawing the held record from its own DTO makes `HandChip` the widest blast radius on the desk, and
an empty list is what found it.** lighting7's **WebSocket and sync converters** set
`encodeDefaults = false` (the REST `json()` is Ktor's `DefaultJson` and *does* encode defaults — a
fact the rig row's `flow` / `width` test pins, and which four docblocks had backwards until
2026-09-21), so on a socket frame a defaulted **empty** collection is not serialised as `[]` — it
vanishes, and a client that declares the field required reads `undefined`. An *FX* palette holds no rows, so
`PaletteDto.rows` did exactly that, and `paletteRowsSwatch` threw on `rows.find`. Because this
chip is mounted in `Layout.tsx` and draws through `padFaceOf`, **every route** sat behind the error
boundary for as long as the desk held one.

The fix is in three layers, and the middle one is what stops it recurring. `@EncodeDefault(ALWAYS)`
on the backend field — `rows` and `requiredEmitters` both carry it now, as
`ProgrammerLayerStateOutMessage.applied` already did — is the durable half. But **`PaletteSummary.rows`
is declared `rows?:` on this side**, and that is not belt-and-braces about the annotation: a desk
mid-upgrade serves handler bodies without new response fields, which `lastPressedAt`'s own comment
already records, so the client must not crash on a server that predates the fix. Declaring it
required was what hid the other unguarded reads from `strict` — the retired add-layer picker's `.length`,
`seedValues`' `for…of` (then `PaletteEditor`'s, now `familyControls/paletteRows.ts`; its `isGeneric` guard lets an FX palette through,
since every FX palette is generic), and four more. Making the type honest is what found them;
each now reads `?? []`, and `paletteRowsSwatch` takes an absent list as an empty one.

The rule to carry forward: **a defaulted list or zero on a DTO needs `@EncodeDefault(ALWAYS)` on the
server *and* an optional type on the client**, because those answer different questions — what this
desk sends, and what any desk might. A lone `?? []` at one call site (which the retired palette
strip had grown for `requiredEmitters`) is the same bug caught one layer later, at one of the places it
reaches.

**Escape is the last rung, and the question is asked in the capture phase.** `HandChip` drops only
when nothing else claims the key: a cell editor open anywhere (`editorIsOpen()`), any
`[role="dialog"]` or Radix popper on the page, or an event already `defaultPrevented`. That is
§"The cell editor's three forms"' snapshot rule — *is an editor open*, not *where was the key
pressed*, which `isEditableTarget` and `closest('[role="dialog"]')` answer and answer wrongly the
moment focus leaves the panel. Radix listens on the **document** and closes first, so a bubble-phase
read always says "nothing open"; a **window capture** listener is the first thing any keydown
reaches, and the bubble handler reads what it recorded for that same press. Get it wrong and Escape
in a cue-name field silently drops the operator's held item.

**The timing is `useEscapeEditorSnapshot`'s, shared — only the predicate differs.** That hook takes
an optional `extra` and the chip passes `anyOverlayOpen`; the hand's ladder is longer than the
grid's, but the capture/bubble trick is delicate enough that a second copy of it is how one of the
two silently stops working. The overlay half is a **document-wide** query, never an ancestor walk,
for the same reason the rule is stated the way it is.

**`lib/handTargets.ts` is the one eligibility table.** A bank takes all three kinds the hand holds (a pad
*is* a reference to one of them), and nothing takes a stack, which the hand never carries; a slot takes a cue or a Look, which lands whole (it has no selection,
D7; absolute-looks plan D1); both layer stacks take a Look or a palette. So a cue lands only on a bank or a
slot, which falls out of the table rather than being stated a fifth time. It is eligibility and
never permission: every place still runs its own mutation.

**`canHandLand(…, 'slot')` is also what `LibraryPalette` sets each row's `slotEligible` from**, and
that sharing is the fix for a real hole rather than tidiness: the rule used to be written out four
times, and a test that built the flag from its own copy of the formula compared two hardcoded copies
of one rule. With one statement of it that drift is gone by construction, so `handTargets.test.ts`
pins what is *still* independent code — `slotAssignmentFor`'s kind re-check, fed a deliberately
wrong `slotEligible`, catching a palette.

**The rings are buttons, not `useDroppable` sites**, which is a deliberate departure from the plan's
sketch. A hand place is a **tap** — the whole point of the hand is that no drag is in flight — so a
droppable could never be dropped on in this session, while being registered on the app's *one*
`DndContext` for every busk-page and surface drag that is; `DeskDndProvider`'s `closestCenter`
fallback returns one collision in the gaps between banks, and a foreign candidate there is exactly
the "highlight in one place, slot in another" failure §"The busk layout" is written about. Session
4's `elementsFromPoint` hit-test would not have been helped by one either — dnd-kit gives no way
back from a DOM element to a droppable — so **`data-hand-target` is the registration** both sessions
read. The affordance is explicit (`HandPlaceStrip`: a *Place “X” here* band, rendering null otherwise)
rather than a temporary second meaning for a surface's existing press, because a mode is state an
operator forgets.

**It is offered in *Edit layout* too, and draws solid there** (`amongDropTargets`). Both busk
targets withheld it while editing at first, and that was wrong in a way worth recording because the
reasoning sounded right: dashed means "a drag lands here" on that page, and this band is a button no
drag can land on — which is an argument about how it is *drawn*. Turning it into a refusal removed
the gesture in exactly the case the hand exists for, a record picked up on **another** window, which
this window's library cannot stand in for. The empty cue slot made it plainer still: an empty tile is
not draggable and its click does nothing at all while editing, so the gate prevented no collision and
cost the place. Reported as confusing by Chris on the desk, 2026-09-16.

**`useHandOffer` narrows through `selectFromResult`, and the two layer strips are each split in
two.** This hook runs once per bank and *unconditionally* in every `CueSlotCell` — hooks cannot be
conditional, so the filled-slot path pays for it too — and a plain subscription woke all of them on
every `hand.state` frame desk-wide, including the usual case where their own answer was null before
and after. `useIsDeskConnected` is the same move for the same reason. The strips are split because
the window's selection pair (`useWindowPressPair`: the marquee store and the selection) is a second
standing subscription: the outer component asks the narrowed offer
and renders nothing when the hand holds nothing it can take, so the selection is subscribed to only
while a placeable record is held. Flat, the programmer's strip re-rendered on every marquee that
crossed a row boundary — it sits under the programmer's tables on every visit to `/programmer` — and the cue
strip multiplied that by however many cue cards are expanded.

**A place the desk refused is not a place.** The programmer strip's `run` is the press, which
answers `null` when refused — already toasted by `errorToastMiddleware` — so `useHandPlace` keeps
the record held and stays quiet. A press can also take the record **off** (it was already on every
selected head) or write it **into** a focused Look, and the place's toast says which
(`HandPlacement.describe`). Answering `true` regardless dropped the hand and toasted success
**beside** the error, on the one place with no Undo to recover through.

**The four places, and the three Undos.** A **bank** is `useAddBuskPadMutation` — it answers the
whole page, so the busk view's commit queue needs nothing — and its Undo is the append's own history entry (`HandPlacement.history`, §Undo). A **cue slot** is `useAssignCueSlotMutation`, on **empty tiles only** — a filled tile's press is
live, and a hand held over the panel must not quietly become a second meaning for it; replacing an
occupied slot is *Clear slot* and then this. A **cue's stack** is `patchProjectCue`
through `buildCueInput`, appended at the top (later wins within a cue), its Undo the patch's entry as well. The rig row's
place keeps its own inverse, since its write goes through the rig's commit queue after the place returns. The **programmer's layer stack** is the press
(`usePress`, apply-model plan §5) — it toggles, replaces the palette's family and lands in a focused
Look as a palette chip's tap does — and has **no Undo**, which is a decision: a press returns no layer id, the only way
to find the new layer would be to diff a stack that is *shared*, and a wrong inverse removing another
window's layer on a live rig is worse than none — the row it just added is one click from its own
remove. Both layer places send a palette the **window's selection** as `targets`
(`useWindowPressPair`: an unlinked window's own), which *supplies* them, and a Look none — it lands
whole; the family mask is the server's.

**Five doors pick something up.** A busk pad's hold opens a **menu** — on a palette or Look pad the
tap's verb and *Apply as values* first (§"Applying to a selection"), then *Pick up* and *View* —
where it used to navigate straight to the library, because the hand needed a door there and
a hold cannot mean two things (`CueSlotCell` does the same synthetic-`contextmenu` trick, which
leaves right-click working on a mouse for free). On `/looks` and `/palettes` it is a **bar verb over
one row** (library-sheets plan D11): the row menus that carried it are gone with the rows, *Pick up*
sits on the selection bar beside Duplicate and Copy to…, disabled over several rows with the reason,
and it is drawn only for the project the desk is on — the hand is project-scoped server-side, so a
pick-up from another project's library resolves nothing.
The Stack table's *Pick up* picks up a layer's **referent** and leaves the layer alone. And the
programmer's palette chip
offers it in its hold menu — a hold or a right-click, which never presses (§"Applying to a
selection").

**That chip is a `Popover` anchored on the button, and must not become a `ContextMenu`.**
Radix's `ContextMenuTrigger` arms a long-press timer of its **own** (~700ms) for `touch`/`pen`,
cleared only by its own pointer handlers or by a `contextmenu` reaching the trigger. The pad and the
cue-slot tile are safe because their holds *dispatch* a synthetic `contextmenu`
(`dispatchSyntheticContextMenu`, shared by both — clearing that timer is what it is for, not just
reaching the menu on touch). The chip's hold opens its `Popover` directly and dispatches nothing,
so under a `ContextMenuTrigger` one finger would arm two hold detectors — which, while the chip's
hold still added a layer, put the layer on at 500ms and popped the menu at 700ms. `PopoverAnchor`
registers no handlers at all.

**MIDI has three doors of its own** — the surface functions *Pick up* (a pad), *Place* (a busk bank)
and *Let go*, all buttons, offered from the catalogue. There is no place into a cue slot and no
layer-stack place: neither has a uuid a binding could carry. *Place* brought a health variant,
**`missingBank`** — a *busk* bank, never the device profile's `unknownBank`, which shares only the
word.

**A record can also be *dragged* between two screens of one browser, and that gesture ends in the
hand rather than going around it.** Drag a library row off the right (or left) edge of one
window and it arrives on the window beyond that edge, under the pointer. The difficulty is the one
the hand exists for: the OS delivers a held button's moves to the window that saw the press, so the
neighbour never receives a pointer event of its own. So the drag is cut in two at the boundary —
`DeskDndProvider` hands the record into the hand (`hand.pickUp`), cancels its own drag, and posts
only the **release point** on a `BroadcastChannel('desk-drag')`; whichever window is under that
point claims it by hit-testing itself. `components/dnd/edgeDrag.ts` is the rule (bounds test,
screen→client, `data-hand-target` hit test, the message) and `useEdgeDrag.ts` the wiring. Seven
things about it:

- **A release no window claims is not an error.** The record is simply still in the hand, every lit
  band is still lit, and nothing is toasted. That is the whole reason the hand-off is a pick-up and
  not a transfer — and it is why the receiving half may never drop the hand on a miss.
- **It is gated on `BroadcastChannel` and on nothing else.** The plan asked for
  `'getScreenDetails' in window` plus a granted `window-management` permission; that is stricter
  than the APIs the gesture uses, so the gate is not built. The bounds test reads
  `screenX / screenY / outerWidth / outerHeight` and the pointer's own `screenX / screenY`, all six
  of which have always reported virtual-desktop coordinates and none of which is permissioned;
  `getScreenDetails` only *enumerates* the other screens, which nothing here needs because the
  channel is a broadcast and the neighbour claims by hit-testing rather than by being addressed.
  Where `BroadcastChannel` is missing the gesture is quiet (D13) and the hand is the route — as it
  is to the iPad, which this channel deliberately never reaches.
- **A presence handshake arms it, and without that it is a regression rather than a feature.** The
  bounds test alone cannot tell *the pointer crossed onto the next screen* from *the pointer
  overshot the edge of a windowed browser with nothing beside it* — and the horizontal chrome offset
  is 0 on every current desktop browser, so a window's outer edge **is** its visible content edge and
  an ordinary drag toward a cue slot overshoots it easily. So windows say `hello` / `here` over the
  same channel and the hand-off arms **only while another window is actually listening**. The probe
  is made **at the boundary, not at drag start**, so a drag that never leaves this window puts
  nothing on the channel at all and is exactly what it was before this session; a first crossing
  that finds presence stale probes and waits one frame for the answer. The same gate is what
  makes the channel's real reach safe: it is one browser instance and profile, so a Chrome window
  beside a Safari one, two profiles, or `localhost` beside the LAN name never hear each other — and
  each of those now gives an ordinary in-window drag rather than one that silently vanishes.
- **One `BroadcastChannel` object per page**, which is why both halves hang off `DeskDndProvider`.
  A channel delivers to every other object of its name *including ones in the same page*, and never
  to the object that posted — so a second object here would make the sending window hit-test its own
  release, place the record on itself, and the gesture would never leave the screen.
- **A posted release becomes a place by synthesising a click** on the hit element.
  `HandPlaceStrip` is already a `<button>` whose `onClick` runs the right mutation with the right
  *where* string and the right Undo, and `useHandOffer` has already refused anything ineligible; the
  empty cue-slot tile is the same shape. A click reuses all of it and states no rule twice, where an
  element→callback registry would be a second copy of what the button already is. Two properties
  come free: a `disabled` tile dispatches no click at all, and a target scrolled out of view is at
  no point. `dispatchSyntheticContextMenu` is the codebase's precedent for the move.
- **The cancel is a synthetic `pointercancel`, never a synthetic Escape.** dnd-kit has no
  programmatic cancel, but its `PointerSensor` binds `pointercancel` on the owner document to the
  very same `handleCancel` as its Escape handler — so this cancels as surely and leaves the keyboard
  alone. An Escape would sit in front of `HandChip`'s Escape ladder and every other document-level
  Escape listener on the page, and keeping it harmless rested on marking it `defaultPrevented`
  before anything else read it: true of the window *node*, but not guaranteed of listener *order* at
  that node. Saying "the pointer was cancelled" is also simply true, where "the operator pressed
  Escape" was a pretence. It runs through the provider's existing `onDragCancel` — the one that
  clears `isDragging` and so keeps the cue-slot panel body mounted — and `handleDragEnd` returns
  early for a handed-off gesture besides, so the cancel is not load-bearing: a dnd-kit that ever
  declined it could not also resolve the drop.
- **The pointer's screen position is read off a window `pointermove`, never reconstructed** from
  dnd-kit's `delta` and activator event, which goes wrong under browser zoom and a non-1
  `devicePixelRatio` — the desk's likely setup. The listeners are attached **imperatively at arm
  time** rather than by an effect, because an effect attaches a commit later and a fast flick can
  cross the boundary inside it; and they **outlive the drag**, because the hand-off *is* a cancel and
  the release they exist for has not happened yet, so the teardown is the pointer's. They refuse
  three things, each a real way to get this wrong: an event from a **different `pointerId`** (a
  second finger must not move or end the first one's drag), **our own cancel** (a flag held across
  the synchronous dispatch, since it would otherwise read as the release and post from the boundary),
  and a **second pick-up after a refused one** (a pick-up that never left the browser leaves the drag
  alone, and without this every later move would try again at up to 120Hz — one attempt per
  crossing, come back inside to retry).
- **A release names its record, and the receiver waits for the hand to hold it.** The channel is
  local and instant while the hand arrives over the WebSocket, so a release can outrun its own
  `hand.state` frame — the receiver would find no band rendered yet, or, if something else was
  already in the hand, find *that* record's band and place the wrong record. `whenHandHolds` is the
  wait — **and the question is asked once more in the instant before the click**, because the hand is
  shared and can move again while this window polls and settles, and the band is the same DOM node
  across renders, so React swaps its `onClick` closure in place: clicking the element found a moment
  ago would place whatever is held *now*, under a band that still looks eligible because it is. A
  release places the record it names or nothing at all. A target that never appears is a release
  nobody claims.
- **There is no arbitration between two claimants, and overlapping windows place twice.** A claim
  message with a lowest-id tie-break was built for that and removed again, which is worth recording
  because the reasoning is the general one. It **cannot happen on this desk**: the release is one
  point in virtual-desktop space, so both windows would have to contain it, and two windows tiled on
  two monitors never overlap — it needs windows stacked on one screen. It was a **mitigation, not a
  guarantee**: a claimant can only wait so long before clicking, so two windows whose target
  discovery differed by more than that settle window both placed anyway — exactly the timing skew
  (differing render latency, differing `hand.state` arrival) the case is about. And it was the
  source of a real double-place bug of its own. Both places are ordinary mutations the operator sees
  toasts for, and the bank place carries Undo. If it ever matters, the fix is arbitration that
  **waits for an acknowledgement** rather than for a timeout — not the tie-break that was here.
- **A library row, and only a library row.** A `busk-pad` drag carries a `PadFace` and a position
  and a `slot-item` drag a slot address; neither names a record id, so neither can be handed off
  without widening the busk page's own drag contract. `screenToClient` is an honest heuristic
  (`outerHeight - innerHeight` of chrome, all at the top; half of any side border), exact on
  Chrome and Edge on Windows and affordable against 28px bands, and **unmeasured on Safari**, where
  it fails safe — a point a few pixels out misses the band and the record stays in the hand. If it
  ever reads wrong the fix is to cache `event.screenX - event.clientX` from a real pointer event,
  not to reach for `getScreenDetails`. `handTargetAt` takes the **topmost** hit and walks up from
  it, never down the stack: a band behind an open dialog is still mounted, and burrowing past the
  overlay would place a record on a target the operator cannot see.

There is **no ghost following the posted point**, by decision: the receiving window's `HandChip` and
every eligible band already draw the moment the hand fills, so a following ghost is new UI over an
affordance that is already there. If it is wanted, it is a `move` message plus a **frozen, hookless**
snapshot, for `dragOverlayRegistry`'s stated reason.
