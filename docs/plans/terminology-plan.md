# Terminology: one vocabulary across the desk, the code and the archive, in MagicQ's words where they fit

> **Document status: AGREED 2026-10-23, not started.** The questions asked and their answers are in
> §11. The survey behind them is a one-page artifact for human readers,
> <https://claude.ai/artifact/8Nu94UPNfSvrBmyQcdpd9V>, private to Chris and a convenience; this plan
> is the authority.

## 1. Context

Engineers who know ChamSys MagicQ cannot talk FX with this desk without a translator. The survey of
`main` at `8f3ecbb` (2026-10-21) against the MagicQ 1.9.9.x manual found eight words that exist on
both desks with different meanings, about forty ideas filed under different names, and twelve places
where lighting7 uses two words for one thing.

The findings that shape the plan:

- **Speed runs backwards.** The editor's *Speed* is `beatDivision`, the length of one cycle in beats
  (`frontend/src/components/fx/fxConstants.ts`, `SPEED_STEPS`). MagicQ's Speed is a rate. The same instruction,
  "halve the speed", does opposite things on the two desks. Speed masters, also called Speed, are a
  rate here too.
- **Size is a mix, not an amplitude.** `FxInstance.weight` applies `size × envelope × stackSize` after
  the blend (`docs/fx-engineering.md` §"Size, direction and N-shot"). MagicQ's Size is amplitude, to
  800 %. A cue layer's `amount` (`cue_layers.amount`) already means a mix.
- **The spread vocabulary is grandMA3's.** Spread %, Wings, Blocks and Groups were taken from MAtricks
  in the effects design (`completed/effects-design/INDEX.md`). MagicQ's *Parts* and *Segments* give
  the same offsets as Groups and Blocks on every row of the manual's 12-head tables, checked against
  `DistributionPlan.of`. *Groups* collides with fixture groups (`/groups/{name}/fx`).
- **Spread means two things.** The FX offset and the programmer gesture `POST /programmer/spread`,
  which MagicQ calls *Fan*. The gesture also has a *Wings* curve and a `parts` option that counts
  contiguous fans.
- **Step timing is MagicQ's Per Head.** `stepTiming = true` multiplies the cycle by
  `distinctSlots` (`docs/fx-engineering.md` §"Step Timing"). MagicQ's Timing has *Per Head* (the
  same) and *Per Step* (each step of the waveform), defaulting to Per Step.
- **Effect names point at different shapes.** MagicQ's *ramp* is a triangle, its *pulse* is an
  on/off, *Colpulse* pulses the base colour. lighting7's *Strobe* drives the dimmer, never the strobe
  attribute. *Breathe* is *Sine Wave* a quarter-cycle on (`sin²(πφ) = (1 − cos 2πφ)/2`).
- **Two colour effects cycle palettes differently.** *Colour Cycle* folds a `tmpl:` reference to one
  generic colour when the effect starts (`fx/TemplateColourSource.kt`); *Palette Steps* resolves each
  palette per head, as MagicQ's 2col/3col/4col do.
- **Composite applies one output.** `LightningStrike` computes dimmer and colour and applies the dimmer
  (`CompositeEffect`). A MagicQ waveform FX drives every attribute it holds.
- **Internal drift multiplies all of it.** Earlier renames were UI-only, so storage still says
  `templates`, `tmpl:`, `pressTemplate`, `distribution`; the I/P/C/B split has five names (`category`,
  `usage`, `PropertyCategory`, family, mask group); *head*, *cell* and *element* name one thing;
  `CENTER_OUT` and `panCenter` sit beside `onColour`; `fx-engineering.md` still lists `onColor` and
  leaves Fluorescent Flicker out of its tables.
- **There is no production data.** Chris confirmed on 2026-10-23 that renaming ids and columns is
  safe. The archive still needs the old names read, because show and venue repos already exist in git
  at `formatVersion` 37 (`sync/ProjectImporter.kt:307`).

## 2. Decisions

Agreed with Chris on 2026-10-23 (§11):

| # | Decision |
|---|---|
| D1 | **An FX's cycle length is *Cycle*.** Values unchanged (*½ beat*, *1 bar*, *4 s*). *Speed* means a rate and only speed masters have one. |
| D2 | **Size becomes *Amount*; the stack's *FX size* slider becomes *FX amount*.** Same rule, same 0–1 range. A MagicQ-style amplitude is a follow-up (§9). |
| D3 | **Order moves up beside Direction.** Both keep their values. No hint text. |
| D4 | **Groups becomes *Parts*; Blocks becomes *Segments*.** MagicQ's words, exact equivalents. *Groups* now means fixture groups only. |
| D5 | **Wings is unchanged.** |
| D6 | **The value Spread gesture becomes *Fan*,** with modes *Between two values* and *About each position*. Its *Wings* curve becomes *Mirror out*; its `parts` becomes *Runs*. FX *Spread* keeps its name. |
| D7 | **The parts of a multi-head fixture are *elements* on screen.** *Head* means a fixture and its head number. |
| D8 | **The Order *Rig* becomes *Normal*.** |
| D9 | **The output stack's Layers 1–5 become *tiers*.** *Layer* means a cue layer or a programmer layer, always qualified. |
| D10 | **Triangle becomes *Ramp*.** Ramp Up and Ramp Down keep their names, which already match MagicQ. Ids rename too. |
| D11 | **Colour Pulse becomes *Colour Wave*.** Pulse, Iris Pulse and Frost Pulse keep theirs. |
| D12 | **Strobe becomes *Dimmer Strobe*.** |
| D13 | **Breathe is removed.** Stored Breathes become Sine Wave a quarter-cycle on. Zoom Breathe becomes *Zoom Wave*. |
| D14 | **Colour Cycle takes literal colours only.** Palettes go to Palette Steps. |
| D15 | **Renames reach everything**: screen, REST, WebSocket, MCP, MIDI binding names, Kotlin and TypeScript identifiers, database tables and columns, the sync archive. The importer reads every old name. |
| D16 | **No hint text.** No control gains a hint for this plan, and none names another desk. |
| D17 | **A glossary and a vocabulary test.** `docs/glossary.md`, linked from `CLAUDE.md`, and a test pinning the label tables to it. |
| D18 | **Split and Edges in become one Order, *Centre in*.** Their offsets are identical (`FU-FX-SPLIT-DUPLICATE`). |
| D19 | **FX everywhere, never *effect*.** *Effect palette* becomes *FX palette*. Code uses the `Fx` prefix. |
| D20 | **UK spelling in lighting7's own identifiers.** `CENTRE_OUT`, `panCentre`. Third-party types keep theirs. |
| D21 | **Project keeps its name.** Docs that say *show* for a project are fixed. |
| D22 | **Step timing becomes *Timing: Per cycle \| Per head*,** stored as `timing` `CYCLE` / `HEAD`. |
| D23 | **Palette Steps and Slot Steps keep their names and form one *Steps* family.** |
| D24 | **The `pingPong` parameter goes.** Colour Fade, Focus Pull and the sweeps bounce through Direction: Bounce at half the cycle. The Order *Ping-pong* stays. |
| D25 | **One word, *family*, for the I/P/C/B split, everywhere;** the first family is *Intensity*. |
| D26 | **Property becomes *Attribute*, everywhere.** |
| D27 | **The composite mode and category go.** Lightning Strike becomes a plain Intensity FX. |
| D28 | **The run count is *Runs*, everywhere.** |
| D29 | **Element keys stay as they are.** `.head-N`, `.pixel-N`, `.cell-N` and the rest name the physical part; D7 is what the screen calls them. |
| D30 | **The other colour FX keep palette references.** Colour Fade, Colour Wave, Colour Strobe and Colour Flicker take `pal:`, one colour for every head, and the glossary says so. |

## 3. Principles

- **P1. One word per idea, at every layer.** The glossary names it; the screen, the wire, the code and
  the archive all use it.
- **P2. MagicQ's word where it means the same thing.** Where MagicQ's word would mislead, lighting7
  keeps or picks its own: *Cycle*, *Amount*, *Order*, *Wings*.
- **P3. An old name never returns with a new meaning in the same field.** So the importer can map old
  names by field without looking at the version. The rename table in §4 is checked against this.
- **P4. A migrated show looks the same.** Every rewrite of stored FX produces the same output on every
  channel, except D14's palette move, which is the point of D14, and a `pingPong` FX whose spread is
  above 1 (§4.2), which is logged.
- **P5. The importer is the only place an old name lives.** No alias in the registry, the REST layer or
  the client.

## 4. The vocabulary

The full list goes into `docs/glossary.md` in session 1, one row per idea with its old names and
MagicQ's word. These are the changes, grouped by where they show.

### 4.1 On screen

| Idea | Was | Now | MagicQ |
|---|---|---|---|
| Cycle length (D1) | Speed | Cycle | Speed is a rate |
| Mix over what is underneath (D2) | Size | Amount | not Size |
| Stack weight on its FX (D2) | FX size | FX amount | Fader Controls FX Size |
| Repeat every N heads (D4) | Groups | Parts | Parts |
| N neighbours share an offset (D4) | Blocks | Segments | Segments |
| Order: member order (D8) | Rig | Normal | Normal |
| Order: edges first (D18) | Edges in, Split | Centre in | Centre In |
| Value gesture (D6) | Spread | Fan | Fan |
| Fan modes (D6) | Spread / Fan | Between two values / About each position | |
| Fan curve (D6) | Wings | Mirror out | |
| Fan runs (D6) | Parts | Runs | |
| Part of a multi-head fixture (D7) | Cell, head, element | Element | Element |
| Timing (D22) | Step timing ☐ | Timing: Per cycle \| Per head | Per Step \| Per Head |
| FX library families (D23, D25, D27) | Dimmer, Colour, Position, Beam, Palette steps, Controls | Intensity, Colour, Position, Beam, Steps, Controls | Intensity, Colour, Position, Beam |
| FX palette (D19) | Effect palette | FX palette | FX palette |
| Attribute (D26) | Property | Attribute | Attribute |
| Run count (D28) | Runs | Runs | One Shot |

### 4.2 The FX library

| Was (id, name) | Now (id, name) | Stored FX become |
|---|---|---|
| `Triangle`, Triangle | `Ramp`, Ramp | the same FX under the new id |
| `ColourPulse`, Colour Pulse | `ColourWave`, Colour Wave | the same, params `colourA` / `colourB` unchanged |
| `Strobe`, Strobe | `DimmerStrobe`, Dimmer Strobe | the same |
| `ZoomBreathe`, Zoom Breathe | `ZoomWave`, Zoom Wave | the same |
| `Breathe`, Breathe | removed | `SineWave`, same `min`/`max`, phase offset + 0.75, Direction Forward: Breathe is symmetric, so its Direction never showed, and Sine Wave's would |
| `ColourCycle` with `tmpl:` entries | `ColourCycle`, literal only | all palettes: `PaletteSteps` with the same palettes and `fadeRatio`; mixed: each `tmpl:` replaced by the colour it resolves to now |
| `ColourFade`, `FocusPull`, `Sweep`, `PanSweep`, `TiltSweep` with `pingPong` | same ids, no `pingPong` | `pingPong: true` → Direction Bounce, cycle halved, phase offset, spread and runs doubled, since all three count in cycles; a spread above 1 stops at 2 and is logged; `false` → parameter dropped |
| `LightningStrike`, `effectMode: COMPOSITE` | `STANDARD`, family `intensity` | unchanged output: the colour it computed was never applied |
| `PaletteSteps` family `palette`, `SlotSteps` family `beam` | both family `steps` | unchanged |

Built-in defaults follow D24: Colour Fade, Focus Pull and the sweeps declare a default Direction of
Bounce (a frontmatter `defaultDirection`, like `defaultStepTiming` today) and halve their default cycle,
so a new one looks as it does now.

### 4.3 Wire and code

| Idea | Was | Now |
|---|---|---|
| Cycle | `beatDivision`, `BeatDivision` (`QUARTER`, `WHOLE`, `ONE_BAR`…) | `cycle`, `CycleLength` (constants in beats and bars; `WHOLE` dropped as a duplicate of `ONE_BAR`) |
| Amount | `size`, `/cue-stacks/{id}/effect-size`, `stackEffectSize.*`, MIDI `stackEffectSize` | `amount`, `/fx-amount`, `stackFxAmount.*`, MIDI `stackFxAmount` |
| Order | `distributionStrategy`, `DistributionStrategy`, `LINEAR`, `CENTER_OUT`, `EDGES_IN`, `SPLIT` | `order`, `SpreadOrder`, `NORMAL`, `CENTRE_OUT`, `CENTRE_IN` |
| Spread shape | `groups`, `blocks` | `parts`, `segments` (`wings`, `spread` unchanged) |
| Timing | `stepTiming`, `defaultStepTiming` | `timing` `CYCLE`/`HEAD`, `defaultTiming` |
| Runs | `nShot`, `nShotStartTick`… | `runs`, `runsStartTick`… (`runForever`, `spent` unchanged) |
| FX | `EffectRegistration`, `EffectSpawner`, `EffectEnvelope`, `EffectSpecCoercion`, `EffectDto`, `effectType`, `effectMode`, MCP `clear_effects`, `active_effects` | `FxRegistration`, `FxSpawner`, `FxEnvelope`, `FxSpecCoercion`, `FxDto`, `fxType`, `fxMode`, `clear_fx`, `active_fx` |
| Palette | `Template*` classes, `tmpl:{uuid}`, MIDI `pressTemplate` | `Palette*`, `pal:{uuid}`, `pressPalette` |
| Family | `category`, `usage`, `PropertyCategory`, `TEMPLATE_EFFECT_FAMILIES`, value `dimmer` | `family`, `AttributeFamily`, `PALETTE_FX_FAMILIES`, value `intensity` |
| Attribute | `@FixtureProperty`, `propertyName`, `properties`, the `property/` interfaces | `@FixtureAttribute`, `attribute`, `attributes`, `attribute/` |
| Fan | `POST /programmer/spread`, curve `WINGS`, `parts`, `mode: FAN` | `POST /programmer/fan`, `MIRROR_OUT`, `runs`, `mode: BETWEEN` / `ABOUT` |
| Spelling | `panCenter`, `tiltCenter`, `CENTER_*` | `panCentre`, `tiltCentre`, `CENTRE_*` |

*Effect tubes* (`effect_tube_state`, the confetti cannon) are special effects hardware, not FX, and
keep their name.

### 4.4 Storage and archive

| Was | Now |
|---|---|
| tables `templates`, `template_rows`, `template_effects` | `palettes`, `palette_rows`, `palette_fx` |
| tables `look_effects`, `cue_ad_hoc_effects`, `cue_property_assignments` | `look_fx`, `cue_ad_hoc_fx`, `cue_attribute_assignments` |
| columns `beat_division`, `size`, `n_shot`, `distribution` | `cycle`, `amount`, `runs`, `spread_order` (`ORDER` is an SQL keyword, as `GROUPS` was) |
| columns `spread_groups`, `spread_blocks` | `spread_parts`, `spread_segments` |
| columns `step_timing` (bool), `default_step_timing` | `timing` (`CYCLE`/`HEAD`), `default_timing` |
| columns `template_id`, `property_name`, `property_mask`, `usage`, `category`, `effect_type`, `effect_id`, `allow_effects` | `palette_id`, `attribute`, `attribute_mask`, `family`, `family`, `fx_type`, `fx_id`, `allow_fx` |
| archive folder `templates/`, DTO fields as above | `palettes/`, the same renames |

## 5. Storage, migration and sync

**The database moves in two one-shot steps** in `initDatabase`, recorded in `PRAGMA user_version`
as `CLAUDE.md` §Database prescribes:

- **4: FX rewrites (session 3).** §4.2's table, in place, logging each FX it rewrites by its Look, cue
  or palette name.
- **5: the rename (session 4).** Back the database up first (`VACUUM INTO
  '<db>.pre-terminology-<utc>.db'`, as `CueModelMigration` does), then `ALTER TABLE … RENAME` and
  `RENAME COLUMN` in one transaction. `step_timing` changes type, so its tables are rebuilt the way
  `CueModelMigration` rebuilds `cues`. It runs before `createMissingTablesAndColumns`, which would
  otherwise create the new columns empty beside the old.

**The archive bumps once, to `formatVersion` 38, in session 4.** The importer gains one file,
`sync/LegacyNames.kt`: old folder, field, enum and FX-id names mapped by field to new ones, and §4.2's
rewrites as functions. By P3 it applies to any archive, whatever its version, so the FX rewrites
landing in session 3 need no bump of their own. `MIN_SUPPORTED_FORMAT_VERSION` stays 5. Session 5's
wire renames touch no archive.

**Surface bindings** stored as `pressTemplate` or `stackEffectSize` are rewritten in step 5 and read
through `LegacyNames` on import.

## 6. UX

- **The FX editor's top row** reads *Cycle · Amount · Direction · Order*. Order leaves the Spread
  section, which keeps *Spread %, Wings, Segments, Parts* and the phase dials.
- **Timing** is a two-way segment, *Per cycle | Per head*, under Advanced where Step timing was. Its
  sublabel goes with the checkbox.
- **Runs** is unchanged: *Forever | Count*.
- **The library** groups FX as Intensity, Colour, Position, Beam, Steps, Controls. The composite group
  disappears.
- **The stack row and phone runner** say *FX amount*.
- **The programmer's Fan** offers *Between two values* and *About each position*, curves *Line,
  Mirror, Arrow, Mirror out*, and *Runs*.
- **Selection chips** say *Elements* where they said *Cells*.
- **Order labels**: Normal, Reverse, Centre out, Centre in, Random, Ping-pong, Position, Unified.

No control gains hint text (D16).

## 7. What is not changing

Wings (D5); the Order *Ping-pong*; *Project* (D21); *Speed* on speed masters; *Spread %*; Ramp Up,
Ramp Down, Pulse, Iris Pulse, Frost Pulse; *Palette Steps* and *Slot Steps* as names; *Look*, *Cue*,
*Cue stack*, *Programmer*, *Park*, *Locate*, *Blind*, *Record*, *Include*, *Update*, which already
match MagicQ; blend-mode names and the *Around / Absolute / Replace it / Within it* questions;
`/fx/*` REST paths.

## 8. Implementation: seven sessions

Each session is one PR. Sessions run in order; session 2 can land at any point after session 1.

### Session 1: the glossary and its test (docs + backend + frontend tests)

- `docs/glossary.md`: one row per idea with the agreed word, its old names, MagicQ's word, where it
  shows, and a status column (`planned` / `done`). Linked from `CLAUDE.md` beside the prose rules, with
  one line telling agent sessions to use its words.
- `GlossaryVocabularyTest` (backend) and `glossary.test.ts` (frontend) read the glossary and check
  every `done` row against the label tables: `fxConstants.ts`, `orderLabels.ts`,
  `ELEMENT_MODE_OPTIONS`, the FX registry's names and families, and `SpeedMasterUsageVocabularyTest`'s
  vocabulary, which it absorbs. Each later session flips its rows to `done`.

### Session 2: on-screen words (frontend + FX frontmatter names)

- D1, D2, D3, D6, D7, D8, D9's screen words, D18's label, D19's screen words, D22's segment over
  the existing `stepTiming`, D25's *Intensity*, D26's *Attribute*, §6's layout.
- FX display names for D10–D12 and Zoom Wave (frontmatter `name:` only; ids move in session 3).
- No wire or storage change.

### Session 3: the FX library (backend + frontend)

- §4.2: id renames, Breathe's removal, `pingPong`'s removal with `defaultDirection`, Colour Cycle
  literal-only (a `tmpl:` entry refused at the write boundary), the composite mode and category removed,
  the Steps family, `SPLIT` and `EDGES_IN` folded.
- Database step 4 and the first `LegacyNames` entries.
- `spreadShape.fixture.json` regenerated without `SPLIT`; `FxRegistrationTargetCompatibilityTest`
  updated for the new ids and families.
- Closes `FU-FX-SPLIT-DUPLICATE`.

### Session 4: storage and archive (backend + frontend)

- §4.4 and the storage half of §4.3.
- Every renamed stored value that is also a wire value, with the client that sends it: the Order
  constants (`CENTRE_OUT`, `NORMAL`), `pal:`, the family values (`intensity`, `steps`) and the MIDI
  binding names (`pressPalette`, `stackFxAmount`).
- Database step 5 with its backup; `formatVersion` 38; `LegacyNames` complete; a frozen v37 archive
  under `src/test/resources/sync/legacy-v37/` that must import.

### Session 5: the wire (backend + frontend + MCP)

- REST fields and paths, WebSocket frames, MCP tool names and schemas (`AiToolSchemas`,
  `describe_rig`, `get_current_state`), and the TypeScript client, in one PR as the git workflow
  requires. Values moved in session 4.
- `Effect*` → `Fx*` and `Template*` → `Palette*` across Kotlin and TypeScript.

### Session 6: Attribute in the code (backend + frontend)

- `@FixtureProperty` → `@FixtureAttribute`, `fixture/property/` → `fixture/attribute/`,
  `PropertyCategory` → `AttributeFamily`, and every identifier that says property for a fixture
  attribute. Storage and wire already moved in sessions 4 and 5.
- The largest diff in the plan and the least risky: no behaviour, no stored name.

### Session 7: the docs sweep (docs)

- Every engineering doc on the glossary's words: tiers in `lighting-composition-model.md`; *project*
  where docs say show; `fx-engineering.md`'s FX tables regenerated from the frontmatter, which adds
  Fluorescent Flicker and fixes the colour parameter names.
- Every glossary row `done`.

## 9. Follow-ups to record

In [`followups.md`](followups.md), when the session that creates them lands:

- **`FU-FX-AMPLITUDE`** (session 2): a MagicQ-style Size that scales an FX's swing about its centre,
  beside Amount. Absorbs `FU-FX-SIZE-OVER-100`. Trigger: an operator wants an FX bigger than it was
  written.
- **`FU-SPEED-PER-ATTRIBUTE`** (exists; session 3): now also the home of multi-attribute FX, since D27
  removes the composite mode. Its trigger becomes: an operator wants one FX to drive two attributes.
- **`FU-FX-STEP-WIDTH`** (session 3): a Width for Palette Steps and Colour Cycle, so one step can hold
  longer, as MagicQ's Width does on 2col. Trigger: an operator asks for an uneven step.
- **`FU-FX-PER-STEP-TIMING`** (session 2): MagicQ's *Per Step*, where adding a step lengthens the
  cycle. Trigger: an operator expects a longer list to run slower.
- **`FU-FX-LIBRARY-GAPS`** (session 3): MagicQ's square, diamond and TiltDim have no lighting7 FX.
  Trigger: an operator reaches for one.
- **`FU-FX-FLICKER-REPEATS`** (session 3): Flicker, Colour Flicker and Random Position repeat every
  cycle; say so in their descriptions, or make them stateful. Trigger: someone sees the repeat on a
  long cycle.

## 10. Verification

Beyond the suites, at the desk on Sleeping Beauty:

- **Session 1.** Change one `done` row's word in the glossary; `GlossaryVocabularyTest` and
  `glossary.test.ts` both fail, naming the row.
- **Session 2.** The FX editor's top row reads *Cycle · Amount · Direction · Order*; the stack row
  says *FX amount*; the programmer's Fan offers *Between two values* and *About each position*.
- **Session 3.** Before the step, add to a test show a Breathe, a Triangle, a `pingPong` Sweep over a
  group with a spread and two runs, and a Colour Cycle over two palettes. After it, the log names each
  rewrite; the first three look as they did; the Colour Cycle is a Palette Steps landing each palette
  per head.
- **Session 4.** The database's `pre-terminology` backup exists. The frozen v37 archive and Sleeping
  Beauty's repo import; a pull and a push round-trip; a surface pad bound to a palette still presses
  it.
- **Session 5.** Swagger shows `/programmer/fan` and `/fx-amount`; Claude clears FX with `clear_fx`.
- **Session 6.** Export Sleeping Beauty before and after; `diff -r` is empty.
- **Session 7.** A search of `docs/` for each old word finds only the glossary.

## 11. Questions

Answered by Chris, 2026-10-23, in ten rounds:

1. **What is the cycle length called?** *Cycle* (D1). Beats a cycle, keeping Speed with a hint, and a
   MagicQ-style rate were declined.
2. **What does Size become?** *Amount*, with the stack's *FX amount* (D2). A hint, a true amplitude
   Size beside Amount, and *Mix* were declined.
3. **Direction and Order?** Order beside Direction (D3). The hints offered with it were withdrawn by
   question 16. Merging into one MagicQ-style list was declined.
4. **Groups and Blocks?** *Parts* and *Segments* (D4). Plain English and the grandMA3 words were
   declined.
5. **Wings?** Unchanged, no hint (D5).
6. **The value gesture?** *Fan*, with its curve and parts renamed (D6).
7. **Parts of a fixture on screen?** *Element* (D7). Cell was declined.
8. **The Order *Rig*?** *Normal* (D8). *In order* was declined.
9. **Layer?** The output stack becomes tiers (D9).
10. **Ramp Up, Ramp Down, Triangle?** Triangle becomes *Ramp*, ids included (D10). Keeping the names
    with a note, and *Saw Up / Saw Down*, were declined.
11. **Pulse and Colour Pulse?** *Colour Wave* (D11).
12. **Strobe?** *Dimmer Strobe* (D12). *Flash* was declined.
13. **Breathe?** Removed; Zoom Breathe becomes Zoom Wave (D13).
14. **Colour Cycle and Palette Steps?** Colour Cycle literal only (D14). Merging was declined.
15. **How far do renames reach?** Everywhere, the importer reading old names (D15). A clean break and
    UI-only renames were declined.
16. **Other desks' words in hints?** None, and no hints at all: the desk is for skilled operators
    (D16). This reverses the hints in question 3.
17. **A glossary?** With a vocabulary test (D17).
18. **Split and Edges in?** One Order, *Centre in* (D18).
19. **Effect or FX?** *FX* everywhere (D19).
20. **Spelling in identifiers?** UK, for lighting7's own (D20).
21. **Project and Show?** Keep Project, fix the docs (D21).
22. **Step timing?** *Timing: Per cycle | Per head* (D22).
23. **Palette Steps and Slot Steps?** Names kept, one Steps family (D23).
24. **Three kinds of bounce?** The `pingPong` parameter goes; the Ping-pong Order stays (D24).
25. **The I/P/C/B split?** *Family* everywhere, *Intensity* first (D25).
26. **Property or Attribute?** *Attribute* everywhere (D26).
27. **Composite FX?** Dropped; Lightning Strike becomes plain Intensity (D27).
28. **Run count?** *Runs* everywhere (D28).
29. **Where does the plan live?** Here, in `docs/plans/`.
30. **How is the work split?** The glossary first, then by area (§8).

Answered while reviewing the plan, 2026-10-23, each as recommended:

31. **Element keys?** Unchanged (D29). Renaming `.head-N` to `.element-N` was declined.
32. **Palette references in the other colour FX?** Kept, one colour for every head (D30).
33. **`pingPong`'s rewrite?** Phase offset, spread and runs scale with the halved cycle (§4.2).
    Halving the cycle alone, and keeping the parameter, were declined.
34. **Stored values that are also wire values?** In session 4, client included (§8). A second database
    step and `formatVersion` 39 in session 5 were declined.
