# Palette references: a palette is a value

> **Document status: IN PROGRESS — session 1 in review.** Calls decided 2026-11-04 (§3). Design
> record: [`palette-references-design/`](palette-references-design/INDEX.md).

## 1. Context

A value palette is pressed as a **layer**. In the programmer, a tap puts a palette layer on the
selection (`ProgrammerLayerStack.toggle`), which sits on the Stack beside Look layers, under every
value you set. A cue holds one as a `cue_layers` row (`palette_id`). Chris, testing the programmer on
2026-11-04: *a palette being a layer doesn't feel natural — it should act the same as a value, a
reference instead of a literal.*

The layer model also loses the palette in two places:

- **A Look cannot follow a palette.** `validateLookRows` refuses a `pal:` value, a Look cannot hold a
  layer, and `record-look` either skips a palette layer (`TOUCHED`: its slots are untouched) or
  flattens it to literals (`ALL`). Retune "DSR Focus" and no Look built from it moves.
- **Nothing on screen says a value came from a palette.** A palette layer's cell and a Look layer's
  both get the faint `ring-primary/40` and a grey `Layers` glyph, with the name only in the hover.
  Local shows an em-dash, and the legend says "came from a Look layer" for both.

This plan makes a value palette a **value**: pressing it writes a **palette reference**
(`pal:{uuid}`, the glossary's word for the FX parameter of the same form) into each pressed head's
value for each attribute the palette names. A reference is held wherever a literal can be, in the
programmer, a cue's rows and a Look's rows, and the desk resolves it per head through
`PaletteResolver`. The three flows from the design session follow from that:

1. Four heads' positions, all different, become a per-fixture palette (`POST
   /palettes/from-programmer` already decides that from the data), and the four values become
   references to it. Nothing moves on stage.
2. A press of that palette with two of its heads selected writes references on those two only. Its
   rows for the other two are ignored. This is the desk's rule today (`LayerPressRules.pressTargets`,
   `paletteHeads`), and the press now says so.
3. A reference is drawn as one: a teal edge and the palette's name in the cell.

**This reverses part of a past decision, on purpose.** The looks-and-layers plan's D1 retired
value-level references (`ref:{uuid}`, to the named palettes that became Looks) for "layers only",
because a Look is a bundle over named fixtures and a layer is the right shape for a bundle. A palette
is one family with no targets, which is the shape of a value. Looks stay layers, and Looks still do
not nest (`ref:` stays refused). The two problems the old grammar had are answered here: what
references a palette is found by an indexed column, not a text scan (D4), and a deleted palette
leaves literals behind, never a dangling reference (D8).

What this plan is *not*: an FX palette as a value, or a Look as a value (§7).

## 2. Decisions

### A reference is a value

- **D1 — `pal:{uuid}` is a value.** A programmer entry, a cue's own row and a Look row may hold
  `pal:{uuid}` where they hold a literal today. It names a **value** palette of the same project and
  one attribute: the row's. `parseAssignmentValue` and `validateLookRows` accept it, and
  `parseAssignmentValue` answers a `Reference`, never white. A palette row may not hold one, a `ref:`
  is still refused, and an FX palette is refused by name (`PALETTE_REFERENCE_INVALID`).
- **D2 — Resolved per head, as a press resolves.** A reference on one head and attribute resolves to
  the palette's row for that head (a per-fixture palette), else its generic row for the attribute,
  through `PaletteResolver.resolve` with the head's fitted media. A colour reference resolves the
  palette's colour family on that head whole, and `unmetColourRequirement` refuses it whole, as a
  press does. A group row referencing a palette resolves per member.
- **D3 — A reference that no longer resolves keeps its last value, marked.** A palette edit that
  removes a head's row or an attribute leaves the head on the literal it last resolved to, and the
  cell and the row DTO say *DSR Focus has no row for Spot 3*. This was the `ref:` era's rule
  ("a disappearing programmer entry mid-show is worse than a stale one"), and it is right here too.
  A reference is per attribute: a palette that later gains an attribute reaches only heads pressed
  after the edit.
- **D4 — An indexed column says what references a palette.** `cue_attribute_assignments` and
  `look_rows` gain a nullable `palette_id`, set by every write and by the importer from the value,
  never synced (the value carries the uuid). `ProgrammerStore` keeps the same index in memory. Every
  "what uses this palette" question reads these, never the value text.
- **D5 — A palette edit moves every reference.** `republishForPaletteEdit` re-resolves the
  programmer's references (republishing those keys, as a Look edit does), the live cues whose rows
  reference the palette, and the Looks whose rows do (`LookRegistry.invalidate`, then the cues
  layering them), in one pass, firing `cuesRecomposed` with every cue it moved. A held cue moved this
  way is not marked edited since GO, as a Look edit's republish is not.
- **D6 — The desk answers the resolved value beside the reference.** No client resolves (the
  `paletteIntent.ts` rule). A programmer entry's frame carries `reference {paletteId, paletteUuid,
  name, unresolved?}` beside its resolved `value`. `CueAttributeAssignmentDto` and the Look row DTO
  carry `reference` and `resolvedByHead` (head key → literal), since a group row's heads resolve
  apart. `ProvenanceEntry` gains `reference`, kept in `provenanceSignature` because a palette edit can
  change a key's value without changing its source.
- **D7 — Sync `formatVersion` 47, `minReader` 47.** No new folder: the reference is the row's value.
  A v46 reader would refuse a Look row holding `pal:`, so the archive needs both numbers moved. The
  importer remaps the uuid on clone and refuses a reference to a palette the archive lacks
  (`PALETTE_REFERENCE_INVALID`, naming the row).
- **D8 — Delete, copy and history.** A referenced palette refuses delete. `PaletteUsage` counts
  reference rows by kind (cue, Look, programmer). `?force=true` **hardens** every reference to the
  literal it last resolved to, per head (a group row split into member rows where its heads differ),
  then deletes. Copying a cue or a Look across projects maps each reference as `cueCopier` maps an FX
  parameter's `pal:` (the palette by name, else a copy). History needs nothing new: a reference is a
  row's value.
- **D9 — Copy the values** hardens references on demand: `POST /looks/{id}/harden` and `POST
  /cues/{id}/harden` `{rowUuids?}` (absent is every reference), the same per-head rule as D8, one
  history entry. MCP's `harden_values` is the same call.

### A press writes references

- **D10 — A value palette's tap writes and releases references.** It no longer adds a layer. An
  **on** press writes `pal:{uuid}` as a touched `WEB` entry on each pressed head the palette has a
  row for (D2's rule; `pressTargets`, `paletteHeads`), for each attribute its rows name within the
  press's mask, at the press's fade. It also releases the pressed heads' references to **other**
  palettes of the same family on attributes this one does not name (apply-model plan D2's family
  replacement, kept). An **off** press, when every pressed head already references this palette on
  every attribute it names there, releases those entries. A literal you set is overwritten by an on
  press on the attributes it names, as a later value overwrites any value.
- **D11 — One rule for every door.** `ProgrammerLayerStack.toggle` hands a value palette to
  `ProgrammerStore`'s reference press, so every door that presses a palette writes references: the
  programmer's chips, a busk pad and its solo bank (a palette sibling's references on the pressed
  heads released), MIDI `PressPalette`, the hand, MCP's `apply_palette` and the cue slot. An **FX**
  palette stays a layer (call A).
- **D12 — The rings read references.** `ProgrammerLayerStack.appliedState`'s palette entries are
  built from the store's reference index: a palette covers a target when every attribute it names
  there references it. The frame's shape is unchanged, so `paletteLayerPresence` and the busk pads
  need no change.
- **D13 — In a cue, the same press writes rows.** `POST /cues/{id}/layers/press` with a value palette
  writes or removes reference rows in the cue's own rows (D10's rule over the stored rows), and the
  Timeline's `layerOn` / `layerOff` / `replace` for a value palette do the same. The Timeline's read
  shows a cue's references as one row per palette, from `palette_id` (D4), so its grid keeps a
  palette's row and its runs.
- **D14 — The press says what it left out.** `TogglePaletteResponse` and the busk pad press's answer
  gain `omitted` (heads a per-fixture palette holds that the press did not name) and `notCovered`
  (pressed heads it holds no row for).
- **D15 — *Use it on these heads*.** `POST /palettes/from-programmer` takes `asReference` (the sheet
  sends `true`). Once the palette is written, the recorded entries are rewritten in place as
  references to it: same owner, same keys, the same resolved bytes, so nothing moves.
- **D16 — Record, Include and Update copy values as they are.** A reference in the programmer is
  recorded as a reference into a cue or a Look; Include loads one back as one; Update writes it back.
  Nothing in `programmerCapture.kt` needs a palette arm. A Record into a palette refuses a reference
  (a palette holds intents) and records the resolved value.
- **D17 — Stored palette layers become rows.** A one-shot data step (`PRAGMA user_version` 10,
  `state/PaletteLayerMigration.kt`) turns each **plain** cue palette layer into reference rows on the
  cue: enabled, amount 1, `OVERRIDE`, no stomp, not timed, a mask that is none or the palette's
  family, and no Look layer above it that asserts on the heads it reaches. Its rows land on the heads
  and attributes the layer reached, except where the cue has an own row, which already won. Every
  other palette layer stays a layer and keeps working, logged by cue and palette; no door makes a new
  one (call B). Backed up first to `<db>.pre-palette-references-<utc>.db`. The importer runs the same
  conversion on a v46 archive (`sync/LegacyNames.kt`'s pattern). Programmer layers are runtime and
  need nothing.

### The marks

- **D18 — Teal edge and pill: a reference, and to which palette.** A cell whose value is a reference
  keeps its owner's mark (yours, blue; a cue's; a Look's in a focused Look) and adds a 3px teal left
  edge, a faint teal wash and a 20px pill with the `Palette` glyph and the palette's name, never a
  short code. Its hover says whose value it is and which row: *Yours: DSR Focus, Spot 3's row*. An
  unresolved reference (D3) draws its pill dashed. In the Values grid, the cue editor's Values grid, a
  focused Look and the fixture sheet's `SourceChip`.
- **D19 — A Look layer keeps its own mark.** A value a Look layer won in Output keeps a Look's mark
  with the `Layers` glyph and the Look's name, so it is never confused with a reference.
  `OwnershipLegend` says *A palette reference, named in the cell* and *From a Look layer*.
- **D20 — A group row that is partly references** gets a dashed teal edge and a pill counting the
  heads: *DSR Focus · 2 of 4*.
- **D21 — Set's chip counts a per-fixture palette's reach.** In `PaletteBands` and `ApplyPicker` a
  per-fixture palette's chip shows *2 of 4*: the selected heads it has rows for, out of all the heads
  it has rows for. The hold menu's first line says *Put on Spot 2 and Spot 3 — its Spot 1 and Spot 4
  are left out*. It is counted from the palette's rows on `PaletteDto` and the press's targets.

### The editors

- **D22 — The Look sheet and the cue editor show references.** `lookValueChips` draws a reference
  as a teal chip with the palette's name and its resolved value (*DSR Focus -14,+22*). Both sheets
  get *Copy the values…* (D9) over the selected rows, else all, and say *2 values follow DSR Focus*.
  The Looks sheet's Contents column counts references.
- **D23 — A palette tapped into a focused Look writes references.** With a Look layer focused
  (`LookRowStore`), a value palette's tap writes reference rows into the Look's draft by D10's rule;
  ⌥click keeps today's literals through `write: false`. In the cue editor the same tap writes
  references through D13.

## 3. Calls

Decided 2026-11-04.

- **A — An FX palette stays a layer** (D11). An FX palette has no per-head value to reference. A
  press that starts a Local FX following the palette is a follow-up (§7).
- **B — Plain palette layers migrate; the rest stay as layers** (D17). A layer using amount, blend,
  stomp or timing cannot be said as rows without changing what it does. Migrating those would lose
  their settings, and leaving every layer would keep two models of a palette in every stored show.
- **C — *Use it on these heads* defaults on** (D15).
- **D — A value over a reference replaces it.** A reference is a value, so a later value overwrites
  it, Release takes it away and Undo brings it back. (An earlier draft of this plan had a value cover
  a palette layer and Release bring the palette back. That draft's layer model is gone.)
- **E — Local shows references.** They are your values. A Look layer's values stay out of Local.

## 4. Risks

- **A press door that still adds a value palette layer** would leave two models running side by side
  and nothing would fail. D11 lists the doors; the `toggle` callers today are `ai/ProgrammerTools.kt`,
  `ai/AiTools.kt`, `midi/SurfaceActions.kt`, `plugins/SelectionSocket.kt`,
  `routes/projectPalettes.kt`, `routes/projectLooks.kt`, `routes/BuskPressService.kt` and
  `routes/lightLocate.kt`. Session 2 adds a test that presses through each and finds no palette
  layer on the stack.
- **Family replacement changes shape** (D10). A layer took its heads off every other palette of its
  family, all attributes at once; references are per attribute. The press must also release the
  family's other references on attributes it does not name, or a colour palette without white
  pressed over one with white leaves the old white behind. `LayerPressTest`'s family cases are the
  list to port.
- **The migration's Look-layer condition** (D17). A palette layer under a Look layer that asserts on
  the same heads cannot become rows, because rows beat every layer. Read the condition per head and
  attribute, and keep the layer if any one fails.
- **Resolution cost.** A reference resolves per head at publish, not per tick, and the resolved value
  is cached on the entry and the row build, re-resolved only on a palette refresh or a patch change.
  A palette edit that moves many references is one republish pass (D5).
- **The colour family.** A colour palette's rows are several attributes (`colour`, and `white`,
  `amber`, `uv` as rows of their own), and each pressed head gets a reference on each. Resolution
  must keep the bundled-emitter rule (§"A bundled emitter's own row beats the colour's copy") and
  D2's whole refusal. Test a palette with a hex and a white row on an RGBW head and an RGB one.
- **The Timeline** (D13) reads palette layers today. Its rows for a palette must come from
  references after session 2, or a migrated show's Timeline loses every palette row.

## 5. Implementation — four sessions

Each session is one PR, run by `/next-session`, titled *Palette references: session N — title*.
Session 1 is the desk alone: references can be written (by MCP, or a row PUT) and resolve, but no
press writes one yet. Sessions 2–4 each land a route change and its client together. Sessions 1 and
2 run the full `./gradlew test` and `npm run check` locally (a `formatVersion` bump; a schema
migration); sessions 3 and 4 run the checks for what they touch (root `CLAUDE.md` §"Pre-commit
checks"). Never merge a session's PR before the one before it.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | References as values on the desk | in review | `claude/wizardly-ride-d2z3ns` | | |
| 2 | Presses write references | planned | | | |
| 3 | The marks and the new palette sheet | planned | | | |
| 4 | Looks and cues: the sheets and Copy the values | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — References as values on the desk

- `fx/CueAssignmentResolver.kt`: `AttributeValue.Reference` from `parseAssignmentValue` (D1).
  `fx/PaletteReferences.kt`: the per-head resolution (D2, D3) and its cache, over `PaletteResolver`.
- `ProgrammerStore`: an entry may hold a reference, resolved at publish, with the in-memory index
  (D4). `programmer.set` and MCP's `set_programmer` accept `pal:` values.
- Cue rows: `buildCueAssignmentsForCue` resolves references. Look rows: `validateLookRows` accepts
  them, and `LookRegistry.expand` resolves them, so the cook and `/looks/{id}/apply` read literals.
- The `palette_id` columns (D4), written by every row write and the importer.
- `routes/lookRepublish.kt`: D5. `routes/projectPalettes.kt`: the delete guard, `PaletteUsage` and
  `force`'s hardening (D8). The two harden routes and `harden_values` (D9).
- DTOs and provenance (D6). Copy across projects (D8).
- Sync v47 with `minReader` 47 (D7): exporter and importer, the clone's remap, `RichProjectFixture`
  (a cue row and a Look row holding references, one on a group), `ProjectRoundTripTest`.
- Tests: resolution (generic and per-fixture, a group row, an element, colour with emitters on RGBW
  and RGB heads, an unmet colour, an unresolved row keeping its value); a palette edit moving a
  programmer entry, a live cue's row and a Look layered in a live cue in one pass; the delete guard
  and hardening; harden routes; copy; sync round trip and clone; the importer's refusal.
- Docs: `docs/lighting-composition-model.md` §"What replaced the positional palette" (the rule
  "a reference is legal only in an FX parameter" goes) and a new §"A palette reference is a value";
  `docs/sync-engineering.md` §"Version 47"; the glossary's *Palette references* rule widened from
  colour FX to values; the root `CLAUDE.md`'s palette paragraphs.

**Where session 1 followed the code** (recorded with it):

- **No `Reference` arm on `AttributeValue`.** Composition only ever sees literals, so every reader
  resolves a reference on its head before parsing (`fx/PaletteReferences.kt`), and
  `parseAssignmentValue` still answers null for one — which is D1's "never white". The programmer's
  arm is `ProgrammerValue.Reference`, the sealed interface's second arm, carrying its resolved value.
- **The column is `palette_uuid`, not `palette_id`.** It is set from the value alone by the row's
  `value` setter, so no write — the importer's included — needs the palette to exist first, and it is
  joined on the row's project, because a venue version keeps every record's uuid.
- **No resolved-value cache** (§4's risk). A reference resolves at each build, the same per-head
  arithmetic a palette layer's cook already runs; the desk remembers only what each (palette, head,
  attribute) last resolved to, for D3, for the run. A desk started on a palette that already lacks a
  head's row puts nothing there.
- **A reference is keyed by the property it lands on**, so a colour reference on a MAC 250 resolves
  to its `colourWheel`; a palette row is matched to the attribute by where it resolves on the head,
  else by its own name.
- **D16 moves to session 2 whole.** Record still writes what the programmer resolves to, and Include
  of a Look loads literals; a reference recorded as a reference, and Include loading one back, land
  with the presses that make them.
- **`provenanceSignature` is the client's** (`frontend/src/api/programmerWsApi.ts`); this session adds
  `reference` to it and to the programmer entry's signature. `ProvenanceEntry.reference` names a
  programmer entry's or a cue's own row's palette; a Look layer's value keeps the Look's attribution,
  as D19 draws it.
- **The harden routes name rows by uuid**, which the row DTOs now carry (`uuid`) beside `reference`
  and `resolvedByHead`; the delete answer's counts are `cueReferenceCount`, `lookReferenceCount` and
  `programmerReferenceCount`.
- **MCP's value grammar takes `pal:`** (`ai/LookValues.kt`), so `set_programmer`, `create_look`,
  `update_look` and a cue's values write references — how S1's operator check writes its Look row. A
  colour reference is laid on each colour-family attribute the palette names, as D10's press will.
- **Hardening keeps what it cannot resolve.** *Copy the values* leaves a row whose head has no value
  to keep — not patched now, or never resolved since the desk started — as the reference and counts
  it as `kept`; only a forced palette delete drops it, since nothing could resolve it again.
- **A Look's group reference stays a group row**, carrying each member's resolution
  (`LookRowEntry.byHead`), so it keeps its place among the Look's group rows and its group origin.
- **Update's cleared-key matching against a reference row** (a row stored on `rgbColour` that lands
  on a `colourWheel`) is D16's, so session 2's; the stomp overlap and a row's health already read
  where a reference lands.

### Session 2 — Presses write references

- `ProgrammerStore`'s reference press (D10) and `ProgrammerLayerStack.toggle`'s hand-off for a value
  palette (D11); `appliedState` from the index (D12); solo siblings; the hand.
- `fx/LayerPress.kt` over stored rows for the cue editor's press, and the Timeline's ops and read
  (D13).
- `omitted` and `notCovered` (D14). `asReference` on `/palettes/from-programmer` (D15). D16 whole:
  Record, Include and Update carrying a reference as one, and Record into a palette resolving it.
- `state/PaletteLayerMigration.kt` (D17), step 10, its frozen fixture
  `src/test/resources/db/pre-palette-references.sql` (a database at 9, never regenerated), and the
  importer's conversion of a v46 archive.
- Frontend: the Stack table and `LookStack` list FX palette layers only; `usePress` toasts `omitted`
  and `notCovered`; the programmer's palette-layer code paths that only a value palette reached
  (`FocusedPaletteLayer`'s value arm, *Detach* on a value palette) go.
- Tests: §4's door test; the family cases; an off press; a press over a literal; the rings; a solo
  bank; the cue editor press; the Timeline; the migration's every condition, each kept layer logged;
  the importer conversion.
- Docs: `docs/lighting-composition-model.md` §"A press is per target", the apply-model rules it
  restates, and `frontend/CLAUDE.md` §"Applying to a selection".

### Session 3 — The marks and the new palette sheet

- `fixtures-list/ownership.ts`: the reference arm (D18), the dashed unresolved pill, the group-row
  count (D20), the Look-layer mark (D19); `FixturesTable`'s pill in the cell; `OwnershipLegend`.
  `scopedCellValue.ts` needs nothing: a reference is a touched entry.
- The cue editor's Values grid (`cueSourceCellClass`) and a focused Look's cells draw the same edge
  and pill.
- `SourceChip` / `CellSourceChip`: *DSR Focus* with the palette glyph and the teal edge.
- `PaletteBands` / `ApplyPicker`: the *2 of 4* chip and the hold menu's line (D21).
- `NewPaletteFromSelectionSheet.tsx`: *Use it on these heads*, on (D15, board A2). `RecordSheet` and
  `RecordLookSheet` say how many of the values are references and that they stay references (C1).
- Tests: `ownership.test.ts` for each mark; `FixturesTable.test.tsx` for the pill and its hover;
  `paletteOffer.test.ts` for the count against `LayerPressRulesTest`'s cases; the sheet sending
  `asReference`.

### Session 4 — Looks and cues: the sheets and Copy the values

- `lookValueChips.tsx` and `LookDetailSheet.tsx`: reference chips, the sentence, *Copy the values…*
  (D22). The cue editor's rows the same. `LookSheet`'s Contents count.
- `LookRowStore.tsx` and `usePress`'s focused-Look arm: a value palette's tap writes reference rows
  into the draft, ⌥click literals (D23).
- Busk pad faces: `describeLookContents` counts references.
- Tests: the chips; *Copy the values* over a selection and over all; a tap in a focused Look writing
  references and ⌥click writing literals; a second tap taking them off.

## 6. Operator checks

One row in `manual-validation.md` per merged session:

- **S1:** a Look row written as a reference through MCP, layered in a live cue, follows a palette
  retune on the rig without a GO.
- **S2:** a palette pressed on two of its four heads, and an existing show's plain palette layers
  migrated with nothing on stage moving.
- **S3:** four heads made into a per-fixture palette with nothing moving, and every mark on the rig's
  own fixtures.
- **S4:** a Look recorded from them, still following the palette, and *Copy the values*.

## 7. Explicitly out of scope

- **An FX palette as a value** (call A). Its press would start a Local FX that follows the palette's
  edits. It goes in `followups.md` as Trigger-gated until a show needs it.
- **A Look as a value.** A Look stays a layer, and Looks do not nest: `ref:` stays refused.
- **Retiring the palette layers that stay** (D17). They keep working with no door that makes one; a
  follow-up retires them once no stored show has one.
- **A reference in a palette's own rows.** A palette holds intents.
