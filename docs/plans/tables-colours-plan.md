# Programmer tables and fixture colours

> **Document status: IN PROGRESS — calls decided 2026-10-18; session 1 done (`4b61df1`).** The visual design is
> checked in beside this plan at [`tables-colours-design/`](tables-colours-design/INDEX.md), three
> hand-written artboards. The live copy at <https://claude.ai/artifact/GmP5uvxEaF2zBKCfYuHmPi> is a
> convenience, private to Chris; the checked-in files are the authority. Where this plan and the
> boards disagree, the plan wins on behaviour and the boards on layout and copy.

## 1. Context

The programmer shows what it runs in three places. The rail's Stack tab lists the scenery it holds,
Local and the layers, and the effects, and at its foot a *Per-fixture FX* disclosure (`FxSheet`)
redraws the grid's rows with effect chips in each cell, building a second full row model to do it.
Venue-visit S14 then added `/programmer/applied?table=fx|looks|palettes`, the same rows at full width
on their own route. Each copy has its own width, folds and verbs, and only the rail can reorder a
layer.

Chris's brief on 2026-10-18 had two parts. Stop the FX / Looks / Palettes tables being a separate
view, and fold the confusing sheets on the right into the other tables. Add ChamSys-style colours
for fixtures, which also reach the patch list and other views. Keep today's responsiveness.

What this plan is *not*: a change to the library sheets (Looks, Palettes, Effects under Library),
which list what a show owns rather than what is running, or to the composition model. The desk
gains one stored field on two tables and one show-owned table. Everything else is the client
rearranging tables it already has.

## 2. Decisions

- **D1 — The programmer is the table.** Row B starts with a tab segment, **Values · Stack · FX ·
  Scenery**, each with a live count. The tab is a per-window view in `?table=` on `/programmer`,
  remembered like the Applied tables' (`lib/appliedTables.ts`'s `applied.table`, renamed), and the
  Screens sheet's *Table* segment sets it. `/programmer/applied` and its `?table=` redirect.
- **D2 — Tabs, no split** (call 1). Side by side is answered by the FX lens (D3) and by a second
  screen showing the FX tab.
- **D3 — The FX lens.** A Values cell an effect drives carries a violet chip with the division,
  struck through when held back, the reason on its tooltip (`heldBackReason`). A click opens
  `FxEditorPopover`. With an FX row selected, its cells are ringed on the Values tab. `FxSheet` and
  the *Per-fixture FX* disclosure are deleted.
- **D4 — The right side is a dock** (call 2): Colour · Spread, collapsible, the same 260 floor and
  300 default. It lists nothing. Layer reorder moves to the Stack table.
- **D5 — One Stack table** (call 4). *Your values* is pinned on top, then every layer top-first. Kind
  chips (All · Looks · Palettes) filter, and a drag is refused while one filters. ⏎ or *Edit in grid*
  focuses the Values grid's scope on that layer and switches to Values. *Your values*' ⏎ focuses
  Local, and *Make layer* sits on its row.
- **D6 — Scenery is a tab** (call 3), its rows the band's (`SceneryElementRow`), with Set · Venue ·
  Moving only as chips and *All scenery…* as the chips' unfiltered state.
- **D7 — A fixture or group carries one colour** from a fixed set of ten named keys (call 5): `red`,
  `orange`, `yellow`, `lime`, `green`, `cyan`, `pink`, `brown`, `white`, `grey`. Blue, amber,
  violet, sky and teal are reserved. The stored value is the key, and each key has a light and a dark
  token in `index.css` (`--tag-<key>`). An unknown key reads as none.
- **D8 — Identity only.** The colour marks the name cell (a swatch and a wash, chosen with the calls), a busk
  tile's 4px top band, a stage label, a DMX footprint and a surface's target label. It never fills a
  value cell or draws a ring.
- **D9 — No inheritance** (call 6). A group's colour marks the group. *Colour from group* copies it
  onto selected members that have none, or onto all of them with ⌥.
- **D10 — On a venue, the venue's, recoloured by the show** (call 7). A venue patch's or group's
  colour is versioned with the venue and drawing-only for `VenueDiff`, which already classifies every
  unnamed field as drawing. A show recolours through `show_colours (project, record_uuid, colour)`,
  with no base and no drift, laid over the pinned version by `RigScope`.
- **D11 — "Colour"** (call 8): the column, *Set colour*, `colour:` in filters. *Highlight* stays the
  momentary dimmer-to-full verb.
- **D12 — Stage view: *Bodies: Plain · By colour*** (call 9), a per-window view option, off by
  default. Labels always carry the swatch, and selection still turns a housing primary.

## 3. Responsiveness

There is no table performance test today, so every session records React Profiler commit times,
before and after, on the 1180×820 iPad frame and on the desk. It measures three cases: one
programmer event on a 120-fixture rig, a dimmer fader drag on the busk view, and a tab switch. A
session whose numbers regress does not merge. The numbers go in its commit message.

- **The Values grid never unmounts.** A conditional mount would clear the scoped selection
  (`useListSelection`), which is the "grid must never remount on a scope change" rule. Stack, FX
  and Scenery mount on demand, and the grid stays mounted but hidden. Assert `gridMounts` across a
  tab switch in `ProgrammerPage.test.tsx`.
- **The lens reads one index**, built per `fxChanged` from the rows the grid already has, keyed by
  head and property, and read per cell through a selector store, not a subscription per row.
- **Stack and FX reuse `AppliedTableShell` / `SheetTable` unchanged**: memo rows, overscan 8, the
  1100 / 860 / 480 bands.
- **A colour is resolved once**, into the row model from the cached patch and group lists, and drawn
  as `--tag` on the row. The opaque sticky name cell uses `color-mix(in oklch, var(--tag) 16%,
  var(--background))`. No new WebSocket frame.
- **3D colours are written on change only**, through `setColorAt`, plus one per-instance attribute
  on the billboard glyphs beside `aActive`.

## 4. Sessions

Sessions 1 and 2 are frontend only. Session 3 is backend only. Sessions 4 and 5 need session 3.
One PR per session.

### Session 1 — the tabs — done, `4b61df1`

- `routes/ProgrammerPage.tsx`, `programmer/ProgrammerWorkspace.tsx`: the tab segment on row B, and
  `?table=` read and written with `replace`. The grid stays mounted and the other three mount on
  demand.
- FX tab: `applied/AppliedFxTable.tsx` inside the programmer frame, *+ Effect* as the library row's
  create verb (the bare effect press), and the *Values above beat effects below* rule row under
  row B.
- Stack tab: a `StackTable` from `AppliedLayerTable.tsx`, with Looks and palettes merged in stack
  order (`layersOfKind` goes), the pinned Local row, kind chips, and `rowDrag` calling the reorder
  `ProgrammerLookStack` uses. Focus by ⏎ and *Edit in grid*.
- Scenery tab: `RailSceneryBand`'s rows and `ProgrammerSceneryList`'s filters on `SheetTable`, one
  scope reading (`useSceneryScope`).
- Navigation: `navigation.ts` drops the Applied child. `CommandPalette`'s `appliedTableNavItems`
  become *Programmer › FX / Stack / Scenery*. `legacyRedirects.tsx` redirects
  `/programmer/applied`. `lib/windowViews.ts` and the Screens sheet's *Table* segment gain Values and
  Scenery.
- The rail is unchanged in this session, so for one session there are two homes, and the next
  removes the old one.
- Tests: `AppliedTables.test.tsx` is retargeted at the tabs, and the rail-versus-tables comparison
  stays until session 2.

### Session 2 — the lens and the dock

- `fixtures-list/cellEffects.ts`: the effect index. `FixturesTable` draws the chip in the cell's
  marks gutter, and a click opens `FxEditorPopover`. The FX tab's selection is published as a store
  (the `marqueeContext.ts` pattern) for the Values tab's rings.
- `programmer/ProgrammerRail.tsx`: the Stack body, `ProgrammerLookStack`, `ProgrammerFxList`, the
  collapsed strip's counts, `RailBand` and the phone handle's doors all go. The rail becomes the dock
  (Colour · Spread). `FxSheet.tsx` is deleted.
- Phone: the bottom sheet hosts the dock, and the tab segment folds to glyph and count below 600.
- Clear's tooltip keeps counting held scenery (`useHeldSceneryCount`).
- `frontend/CLAUDE.md`: §The rail's tabs, §The applied tables and §The programmer's scoped grid are
  rewritten to match.

### Session 3 — colour on the desk

- `models/fixturePatches.kt`, `models/fixtureGroups.kt`: a nullable `colour varchar(16)`, additive.
  `models/fixtureColours.kt` holds the ten keys and their validation (400 `COLOUR_UNKNOWN`).
- `models/showColours.kt` (`show_colours`), portable: `showColours/{uuid}.json`. `RigScope` lays it
  over a venue's patches and groups, and `RigScope.own` is untouched. A colour on a venue's row
  written through a show is a `show_colours` row, never 409 `VENUE_OWNED`. Hiding a record leaves
  its row dormant.
- REST: `colour` on `FixturePatchDto` (with `colourOwner: SHOW | VENUE` on a venue patch),
  `PatchGroupDto` and the live `GroupSummary`, written through the patch PUT and its bulk path and
  the group PUT.
- Sync: `colour` on `FixturePatchJson` and `FixtureGroupJson`, `ShowColourJson`, **formatVersion
  36**. An older reader would drop the field and write the record back, which is the case the bump
  rule names. `RichProjectFixture` sets a non-default colour on a patch, a group and a show
  recolour. `SyncCoverageTest` gets the new table's disposition. `ProjectRoundTripTest` and
  Extract, Detach and Attach carry it: Detach writes the effective colour into the show's own rows,
  and Attach turns a differing colour into a `show_colours` row.
- MCP: `patch_fixtures`, `place_fixtures`, `override_venue_fixtures` and `edit_groups` take
  `colour`, and `describe_rig` lists it.
- Docs: `docs/sync-engineering.md` (v36), `docs/fixtures-engineering.md` (§"Fixture colours"), the
  root `CLAUDE.md`.

### Session 4 — colour in the tables

- `index.css`: `--tag-<key>` in `:root` and `.dark`. `lib/fixtureColours.ts` holds keys, labels and
  the 1–9 / 0 keymap.
- Patch list: a `ColourCell` on the sheet kit with the swatch popover, keys 1–9 and 0, ⌫ clearing,
  and the marquee writing in one bulk request. *Colour from group* is on row C. Venue rows show
  *yours* and *Take venue's*.
- The name mark in `FixturesTable` (programmer, Fixtures › List, Groups › List, cue editor) and the
  patch sheet, through `SheetTable`'s `rowClass` and a `--tag` style.
- `lib/fixtureSearch.ts`: a `colour:<key>` term. ⌘K gets *Select colour · Red* (writing
  `selection.set`) and *Add to selection* with ⌥.

### Session 5 — colour everywhere else

- Busk: `RigTile.tsx`'s 4px top band.
- Channels: `DmxSheet.tsx`'s footprint run takes the colour.
- Surfaces: `SurfacePanel.tsx`'s `TargetLabel` band.
- Stage: `stageLabels.ts`'s swatch, and *Bodies: Plain · By colour* in `StageViewMenu.tsx` as a
  `windows.viewOptions` key. `bodies/StageBodies.tsx` writes per-instance colour on change, with a
  billboard attribute beside `aActive`.
- Positions panel chips and the fixture sheet's header take the swatch.

## 5. Risks

- **The hidden grid.** It keeps re-rendering on programmer events while another tab shows. That is
  no worse than today, when the grid is always visible, but measure it, and if it costs, gate its
  store subscriptions on visibility rather than unmounting it.
- **Two homes for one session.** Session 1 ships the tabs before session 2 removes the rail's lists.
  Merging 1 without 2 is allowed but not a resting state.
- **Cyan.** It is the nearest key to sky and teal. If it reads as a cue on the rig, swap it for
  magenta, a key rename before any desk stores it.
- **Scribble strips.** Driving a real surface's backlight from the colour is a follow-up
  (`FU-SURFACE-SCRIBBLE-COLOUR`): the backend has no scribble colour today.

## 6. Operator checks

One row in `manual-validation.md` per merged session: the tabs on the iPad frame and a second
screen; the lens against a held-back effect; a colour set on a venue patch from a show and taken
back; a colour on the busk rig and the Stage view in a blacked-out room.
