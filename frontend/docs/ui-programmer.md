# The programmer

The fixture sheet, applying to a selection and its mask, the programmer's keyboard, grid, tables and undo, and fixture colours. Moved out of `frontend/CLAUDE.md`, headings unchanged, so a citation of `CLAUDE.md §<heading>` lands here.

### The fixture sheet

**One body for a fixture or a group in every host** (fixture-fx-sheets plan sessions 2 and 4, D1–D13;
`../docs/plans/completed/fixture-fx-sheets-plan.md`, the boards in `../docs/plans/completed/fixture-fx-sheets-design/`):
`components/fixtureSheet/FixtureSheet` takes a `fixture` **or** a `group` and a `host` — `popup`
(`FixtureDetailModal`, the 512px Radix sheet the list, the grid's Info, the overview panel and Channels
open; and `GroupDetailModal`, the **group sheet**), `stage` (`StageFixtureControlPanel`, 380px, with
the **Focus** tab and the *Aim…* popover), `card` (the cards page: no header, unscrolled, the page's
Attributes / Channels toggle kept; and `GroupCard`, under the card's own header) and `phone` (the
Stage view and the busk view's *Fixture sheet…* on a phone, in `PhoneSheet` — below). The context a
row reads (`sheetContext.ts`) carries the target and a `reachOf(head)` — `fxReaching`'s
arguments for one head — rather than a fixture.

**The phone host** (session 6, D14; Hosts board) is the sheet in `useEditorForm`'s two touch forms,
`components/fixtureSheet/PhoneSheet.tsx`: held upright a **bottom sheet with three heights** — *peek*
(the header, the scope line and the tray; the attributes fold away, `[data-sheet-body]` hidden), half
(where every open starts), full (88svh, `EditorSurface`'s cap) — with a **grabber**: a drag follows
the finger and settles on the nearest (`settleHeight`), a drag well below the peek closes it, a tap
or Enter steps it taller, round from full to the peek, the arrow keys step it either way. Held
landscape (short beats narrow) it is the **right-hand sheet**, the Stage panel's 380px plus the
safe-area inset, full height, no grabber. Both pad the safe-area inset **under the tray** (a class,
`SAFE_BOTTOM_CLASS`, dropped while the keyboard is up), so the tray — always the column's foot,
outside the scroller — sits above the home indicator, and both give back the keyboard's bite from
`visualViewport` as `EditorSurface` does (its `useKeyboardInset`, exported for this): the bottom sheet
rises, the side sheet shortens. On the phone host the rows take **finger sizes** (§4) through
`useFingerSized()` — the host, which is the form, never a `sm:` width: a 36px field at 16px text
(below 16px iOS zooms the page on a field's focus), a setting's 36px box, a 32px × and editor chevron,
and in the tray 32px chips and **+ FX** in a 48px row. The Stage's is **not modal** — the canvas
stays live, so another fixture's tap moves the sheet and empty stage closes it, as the docked panel
behaves — and keeps both Stage extras, Focus and *Aim…* (live project only); the busk view's is
modal, opened from a menu. `StageFixtureControls` picks the docked panel or the phone sheet.

**The cards page's corner** (session 6, Hosts board): each card's header actions end in
`OpenSheetButton` (⤢), which opens the fixture's 512px pop-up — the one `FixtureDetailModal` the page
holds — for what the card host leaves out (the views, Release, the scope line); the page's Attributes
/ Channels toggle stays. A **group card** has the same corner, opening the group sheet
(`GroupDetailModal`, now mounted by the Groups route): D1's one sheet for a group, and the only place a
group has Park, Release and the scope line.

**It is a programmer surface, and it says so.** Every value lands in Local; every FX it starts is
a programmer FX. `FixtureContent`'s properties view, `PropertyVisualizers`, `FxSection`,
`FixtureDetailView` and `EditModeContext` are deleted, and with them **every Edit / Done toggle on
the fixture sheet, the cards page and the Channels cards** (D2): a surface is live while the desk is
connected and read-only only offline, or per row on a parked attribute. Unpark keeps its confirm in
both places that offer it. Don't bring a mode back — the confirm is the guard.

The column (Main board): `SheetHeader` — name, the model line (heads, mode, footprint, address),
then a 40px row of **Values · Channels** (· **Focus**), Locate, Park and **Release n** — then
`ScopeLine` (*Writes to Local · fade 2.0 s*, the programmer fade read from `lib/programmerFade.ts` and
never set here; amber *BLIND* while blind; *Offline* when it is; the held count on the right), then
the attributes — **the only scroller** — then `FxTray`, **outside** the scroller at the foot, so the
FX never scroll away after the last attribute (issue 6). The root is a size container named `sheet`: the
width ladder is a container query on the sheet, never the viewport (§4) — below 400px of sheet
Release folds to its glyph and the scope line drops the fade.

- **Rows** (`sheetRows.ts`, `buildSheetRows`): grouped by family in D3's order — Intensity · Colour ·
  Position · Beam · **Controls** (macros, speeds, mode settings and timing channels, which
  `lib/attributeFamily.ts` files under Beam as its catch-all). Triggers, commands and fine halves are
  not rows. **Pan and tilt are one Position row** keyed `position` first (then the axis names), from
  the grid's own `resolveCell(…, 'position')`. A colour head with no dimmer gets an Intensity *Dimmer*
  row over its colour — no "Virtual" badge — but **a fixture's heads never do** (`headRowOptions`):
  a head has no dimmer channel, and the row only repeated what its colour row sets.
- **Controls are the editor kit's** (D7): a slider beside an `EditorField` in the row's unit — a
  **percent** for a level, as the grid reads; **centre-relative** for a Position on every head
  (`lib/positionScale.ts`, §The editor kit). A field **commits on Enter**, not per keystroke (`SheetField`): a sheet write
  takes the programmer fade, and `8` on the way to `80` would be a fade of its own; a drag writes as
  it moves, at no fade. A setting row mounts the grid's `SettingCell` — one type-ahead for a step on
  both surfaces. **Colour and Position open inline, one row at a time** (call 5): the colour row
  docks `ColourEditor` with its typed channels, the position row grows its pad and Pan / Tilt fields.
  The colour row's swatch is how the head **looks** — dimmed by its dimmer (a head's own, else the
  fixture's), with the UV dot — and the text beside it is the value.
- **Recent values** (programmer-fixes plan D20, §The editor kit's `RecentValues`): a level row
  records `level:<attribute>` (a drag on its commit, Enter at once) and draws its band only while
  it is the row last pressed or focused — the sheet's `recentRowId`, one band at a time, so a sheet
  of sliders is not a sheet of bands and an unengaged row subscribes to nothing. `LevelControl`
  keeps one element tree whether or not the band shows, since the band appears in the very press
  that engages the row. The Position row records the pair — a pad drag on its release, open or
  closed, and a typed axis on Enter — and draws its band in the open editor, under the pad; over a pick (`PickPositionControls` with `recents`; the busk Position tab leaves
  it off) nothing is recorded while the other axis differs across the heads. The open warmth row's
  `WarmthEditor` records `warmth`, the open colour row's `ColourEditor` `colour`. A dimmerless
  head's *Dimmer* scales a colour and is no rig level, so it records nothing.
- **Position writes `programmer.setPosition`, never raw channels** (D8; `sheetWrites.ts`): the commit
  goes through `clampCommitToResolution`'s centre rule, so `-20` lands where the programmer's position
  cell would land it, and a one-axis commit takes the other axis from the held `position` entry, else
  the wire. A raw pan landed in the sideband, held nothing back and read *Programmer* while an FX
  was on stage — the bug that started the plan.
- **Source marks** (D4): `useRowSource` subscribes per key (`subscribeToKey`) and joins `getKeyState`
  with the active-FX list through `rowSourceOf` (pure) — a 3px edge and a `SourceChip` in the
  grid's colours: *Programmer* (filled once touched), *Night · pressed* for a pressed layer, the FX
  as *Pulse · 1 beat · M2*, the cue as *Q12 · Night*, *Parked*, dashed *Base*. An **amber dot** while
  something under the winner is held back, by `lib/heldBack.ts` — the desk's
  `FxSuppression.heldBackByProgrammer` copied clause for clause (blind holds nothing back, a
  programmer-band FX is never held back, otherwise an entry on the FX's **own** key on that
  head). The programmer grid's FX lens strikes a chip through by the same function (§The programmer's
  tables). A dashed amber *Staged* sits beside the chip while blind and held. **Blind holds the programmer's own FX instead** (show-cue-editing
  plan D7): `isHeldByBlind` beside it (the desk's `fx/BlindHold.kt` — blind and in the band, which
  `programmerOwned` is), never true of an FX `isHeldBack` holds, read by the tray and the
  programmer's FX table for their *held · Blind* mark (`fx/HeldBlindMark.tsx`), and by the grid's
  lens.
- **The stack** (D5): the chip opens `LayerStack` through `EditorSurface` — `programmer.keyStack`
  asked on open and **again whenever the key's provenance moves** while open, every layer top first,
  the one on stage ticked, held-back layers struck through and said out loud — *why* from the desk's
  `heldBackReason`: your value, a stomp, Blind (*held · Blind*, show-cue-editing plan D7), or a
  replacing busk stack above it (*held · replaced*, lighting7 busk-stack plan D8: a weight, so it
  still paints at what that stack leaves, and resumes in phase as it fades); an older desk's
  unreasoned hold reads as your value. *Clear yours — let it run* is offered only over a
  hold your value makes (`heldByYourValue`). The client never works out what is underneath.
- **Clearing** (D6): a row's × is drawn only while the programmer holds the row by an owner
  `clearEntry` releases (a slot only a pressed layer holds is not the operator's to clear), and sends
  `programmer.clearEntry` per held key at the programmer fade. **Release n** (`useRelease`) is one
  `programmer.clearTarget` at the programmer fade, toasted in the desk's count, with the group FX
  it left running in `partialSweepMessage`'s vocabulary (`outside: 'this fixture'`); no confirm, like
  Clear (call 3). Its count (`heldOnFixture`) is the entries on the fixture and its heads whatever the
  owner but a layer's, the sideband inside its footprint, and its local FX.
- **An FX over a selection** (effects plan D3) reports `targetType: 'selection'` and its ordered
  `targets`, and its `targetKey` — the list's canonical JSON — names no fixture or group: every read
  of which heads an FX reaches goes through `lib/fxTargets.ts` (`fxNames`,
  `fxAimKeys`, `fxTargetLabel`, the stored-row `storedFxTargets`), never a comparison of
  that key. The tray, the badges, Release's count, the grid's ⌫ and its FX lens, the programmer's FX
  table and the busk FX tab all read it there; it reaches a fixture as *via <its items>*, and its stop asks first.
- **The tray** (`FxTray`, D9): four states (Fx board) — empty and **folded**, one 40px row (the
  count, a chip per FX dimmed while paused, *+n* past three, and **+ FX**);
  **open**, two-line rows with pause and stop, up to half the sheet, scrolling itself; and
  **adding**, `FxPicker` in the open tray. While the programmer is blind a programmer FX's chip
  takes Blind's amber dot (its hover adds *held · Blind*) and its row the `HeldBlindMark`: the desk
  is holding it, phase kept, until Blind goes off (show-cue-editing plan D7). A group's FX on the fixture reads *via <group>*, and
  any FX landing on elements its Over (*over fixtures*, `elementModeSummary`), and its stop **asks first** — the editor's × too — since it stops the FX on
  every member. A fixture's head row sees an FX on the fixture, which the desk paints onto the
  heads (`fxReaching`, the one filter the rows and the tray share).
- **The picker** (`components/fx/FxPicker.tsx`, D9, D10): a family segment (Intensity · Colour ·
  Position · Beam · Controls, a family the target cannot take disabled; Beam lists the beam
  built-ins, each only where a head has its one attribute, and Slot Steps on a setting; Palette Steps
  sits in every family with two generic value palettes or more naming an attribute the target has,
  effects plan §4.4) opening on the family of the row
  open on the sheet, and the FX rows. **The step FX start stepping** (session 7,
  `lib/stepFx.ts`, which never resolves — the desk does, per head): Palette Steps on that
  family's palettes, in name order, its master by the family; Slot Steps on the row's
  setting — from any other row the gobo wheel where the head has one, else its first setting — on
  its first two slots past its open one. Neither can be forked (the FX Library says why), and *Save
  as palette…* banks one by its palettes' family or as Beam on its setting (`stepFxPaletteShape`). The editor edits their lists with
  `FxStepListPickers.tsx` — `paletteList` (palettes by reference, one family once one is chosen)
  and `slotList` (the setting's options, a name the head lacks dashed; on an FX palette every
  patched head's options of the setting it names, `rigSettingUnion`, with typed names besides). **A tap is the start**: the FX runs at once with its
  defaults, on that row's attribute where the FX can take it, as a **programmer FX**
  (`programmerOwned`, so it plays over the programmer's values, Clear sweeps it and Record captures
  it), a movement FX starting *Around* (a Path on a shape with a centre; a Line has none and starts
  on Override), every FX starting on its type's own cycle and Direction
  — **one bar**, Forward, but half a bar and Bounce for Path's sweep presets and Wave's *Focus
  Pull* (`newFxDivision`, `newFxDirection`, `presetDefaults`) — on the master tagged for its family
  on that attribute (venue-visit plan D16, fx-settings plan D5; a wall-clock FX on four seconds, ×½).
  **Presets** (fx-settings plan D4) sit beside the types: Wave's *Strobe* and *Chase* under Intensity, and *Zoom
  Wave*, *Iris Pulse*, *Frost Pulse* and *Focus Pull* under Beam where the head has the attribute,
  each a Wave starting with its parameters, cycle and Direction; the bare Wave is listed under Beam
  too, on the head's beam attribute, starting at that attribute's `attributeDefaults`. Colour Cycle's
  *Colour Fade*, *Colour Wave* and *Split Chase* sit under Colour and Path's *Sweep*, *Pan Sweep* and *Tilt Sweep*
  under Position (fx-settings plan session 3). A **legacy** FX (the library's `legacy`: Colour Fade
  and Colour Wave themselves) is offered by no picker, the palette editor's FX list (but the one a
  palette already holds) or the FX Library page (`compatibleFxFor`), yet edits as any FX. **Another tap swaps it** through `updateFx` — the new
  type with its own defaults and starting blend, id and phase kept — so the operator auditions by
  tapping down the list; a tap that lands on another *attribute* cannot be a swap (an instance keeps
  its target), so it stops the auditioned FX and starts afresh. An FX offered through a
  sentinel (`setting`, `slider`) draws an *On* row where the target has several attributes it could
  land on. *Edit <name>* opens the editor.
- **The live editor** (`components/fx/FxEditor.tsx`, D11, D12, D12a, D15): the old
  `EffectParameterForm`'s inputs rebuilt on the editor kit (its fields are `FxFields`, which
  `PaletteEditor` mounts too, over a draft — §Looks, palettes and layers), **inline under its row** in the tray (Main board B). **No
  Apply**: every control writes the draft through `useLivePush` (50 ms floor, deduplicated, the
  release always lands) into `lightingApi.fx.updateFx` — parameters, cycle, blend and phase every
  time, an Order, element field or master only once the operator has set it, since the DTO
  leaves those out where they do not apply and the draft's default would be a guess; a typed field commits on Enter
  (`SheetField`, shared with the rows). **Revert** sends the snapshot taken at open in one write
  (a never-assigned master as master 1's uuid — the frame cannot say null; an FX that opened
  on its Own tempo gets `ownBpm` back, one on Natural `natural: true`, which is also how a wall-clock
  FX returns to unscaled, `FU-FX-RATE-MASTER-CLEAR`'s close); **Done** comes back
  to the list, landing any move the floor still held; **×** stops the FX, and pause / resume sit
  beside it. A write the closed socket dropped is forgotten as sent, so the release resends it. The draft is the editor's own while it is open, so a
  refetch never pulls a slider back under a finger; hosts key it on the FX's id *and* type, so
  the picker's swap opens a fresh one. **The fields ask four questions, then Advanced** (fx-settings
  plan D9), in this order in every host: *Speed*, the FX's own section, *Across the heads*, *Mix*.
  **Speed** (fx-speed plan D1–D4, D8; `SpeedSection`) is the tempo chip, Tap and the tempo, then the
  slider and its typed value, the readout, then Direction and Runs. The **tempo chip** (`TempoChip`,
  `M1 · Main ▾`) opens the masters, then *Own tempo* (*Natural* on a wall-clock FX, whose master is
  its rate master), then *Same speed as…* (`timingChoicePatch`, `cyclePatchFrom`). **Tap** and the
  typed tempo go where the host says (`FxTempoHost`): the live FX editor holds the master in the
  programmer (`programmer.tapTempo` / `setTempo`, the field amber while it is held), the cue FX tab
  sets that master's row of the cue's *Tempo on GO* and sends nothing live, the palette editor is a
  live tap; on Own tempo both set the FX's own, and the window counts the taps
  (`lib/tapTempo.ts`, the desk's `TapTempo` rules); Natural has no Tap. The **slider**
  (`SpeedSlider`) runs ×1/16 to ×16 on a log scale, labelled ×¼ ×½ ×1 ×2 ×4, sticks at every power of
  two unless ⌥ is held, moves a quarter of an octave an arrow and a whole one with Shift, writes a
  cycle rounded to whole ticks, and ends in a typed value taking `⅔`, `2/3` or a decimal; a Speed off
  the track draws its thumb at that end and writes nothing until it is dragged. The **readout** says
  the cycle in seconds at the running tempo, in bars or beats on a snap (*1 bar · 1.82 s a cycle at
  132 BPM*; `useFxTempo`). All of it is `lib/fxSpeed.ts`, which every chip reads too (`fxSpeedLabel`:
  `×1`, `×½ · Own 96`) and which the desk's `src/test/resources/fx/fxSpeed.fixture.json` pins. A
  stateful FX the library marks `ownPace` (Candle Flicker, Fluorescent Flicker) says *Runs at its own
  pace* in place of all but Direction and Runs. **Direction** is a segment of three glyphs, → ← ↔,
  the word on the hover; **Runs** (`RunsChip`, `∞ ▾`, `×2 ▾`) opens *Forever*, ×1 to ×4 and a typed
  count — after which each head hands back to what is underneath (effects plan D9; a null count is
  sent as `runForever: true`, since the frame's absent `runs` means keep). A chip opens a popover, and
  a bottom sheet with 44px rows on the phone host or below the FX editor's 760 arm (`fxChipSheet.ts`,
  which `FxEditorSelection` sets around its one-column inspector). **Every segment of presets ends in
  a typed value, lit while the value matches none** (`TypedCell`): the Speed's snaps, Runs' counts, and a
  parameter's common values (`fxParamPresets.ts`, a client table keyed by FX and parameter — Wave's
  Width *10 % · 50 % · One head · 35 %*, its `widthUnit` drawn inside the segment while Width shows;
  Colour Strobe's Width). Moving a beat FX from a master onto Own tempo starts the tempo at the
  master's live BPM and keeps the cycle, so its speed holds (fx-speed plan D3, `lib/fxTiming.ts`).
  The graph's Speed handle drags on the slider's scale, snapping to powers of two, its hover *×2 ·
  0.94 s*. **Mix is Amount beside the
  family's question** (effects plan D11; terminology plan D3): Amount a slider and a percent field — how
  much of the FX reaches the stage over what is underneath, which the desk weighs after the
  blend (a Replace at 50 % sits halfway over the level underneath) — and, for an FX that asks no
  question, a line saying what its blend does. **Across the heads** holds *Over*, then on a group or a fixture with
  heads the spread's questions (below; the Order, worded *Normal · Reverse · Centre out…* by `lib/orderLabels.ts` — the one
  vocabulary, which `fxConstants.ts`' `ORDER_OPTION_ENTRIES` / `getOrderLabel` (a
  Look's or cue's summary, a group's *Runs as*) and the Fan's `SPREAD_ORDERS` read too, keyed by the
  stored names `NORMAL`, `CENTRE_OUT`, `CENTRE_IN` and the rest). Amount and Direction are dynamics fields on the desk, so a write keeps
  the phase, and both ride the `updateFx` frame only once touched (`OPTIONAL_FIELDS`). The client
  computes none of it (P2). A movement
  FX asks *Centre: Around current position | Absolute* — Around is `ADDITIVE` with the centre
  pinned at 128 and **hidden**, Absolute `OVERRIDE` with it shown, and a stored `ADDITIVE` + 128
  reads back as Around; a level FX asks *Over the level underneath: Replace it | Within it*
  (`OVERRIDE` / `MULTIPLY`, default Replace), both in Mix. Levels are percents; a size (`…Radius`, `…Range`) is a
  distance in **DMX steps**, and a centre or a sweep's end a position on its axis, **centre-relative**
  (`-128..127`, venue-visit plan D3) on every head — the value is raw DMX, so no head is consulted. **Each parameter is drawn from the
  desk's metadata** (fx-settings plan D1; `paramView` in `fxEditorModel.ts`): its label, range, step and unit, a `choice` as a
  segment (a Wave's or a Path's `shape` with a small picture over each word, `fxShapeIcons.ts`, keyed by option), a `pair`
  as one two-thumb range with a field at each end, one whose `shownWhen` is false not at all, and `detail`
  ones folded under **More**, its summary line saying what is in it; `paramRole` and the board's label table guess these
  only for a parameter that declares none (a script FX's), whose curves, switches and ratios beside levels fold under
  *More* too. A **control** the desk declares over several parameters (venue-rig-fixes plan D20–D21,
  `controlViews` / `scaleControl`) is one slider before its first member — Path's *Size* over its two
  sizes on a Circle, Figure 8 or Random — reading the largest and scaling every member from their values
  at the gesture's start, the members folded under *More*. The parameters are **the FX's own section**, named for what it holds (`fxSectionName`: *Levels* for a Wave
  or a level FX, *Colours*, *Path*, *Steps*, *Flicker* for the Flicker family, *Parameters* for a script FX), its header's
  FX name the **type switcher** (`FxTypeSwap`): the FX this attribute takes, a pick swapping it as the picker does
  (`swappedDraft` — the new type's starting parameters and blend, a blend changed from the old type's start kept, the
  timing carried unless at the old type's defaults, a step spread kept at its offsets over the heads), id and phase kept
  in the live editor, which draws the new type at once and offers no swap on a pad's instance; one stored write, its
  family restamped, in the cue's FX tab. The palette editor's FX select above the fields is its switcher, so its header names the FX alone. **Advanced**
  holds the phase, *One cycle for: All heads | Each head* on a group or a fixture with heads (over `timing`, `CYCLE` |
  `HEAD`, the wire unchanged), the head filter and the raw blend (every FX, so nothing is out of reach), its summary
  saying each: *phase 0° · one cycle for all heads · override*. **Over** — *Fixtures ·
  Elements · Each fixture* (`WHOLE`, `FLAT`, `PER_FIXTURE`; FX editor follow-ups plan D7), what the spread counts where
  the FX lands on fixtures' elements — heads Across the heads wherever a target fixture has elements, and every
  door that starts or creates an FX writes `WHOLE` (D8: the picker, a cue's draft FX, a new FX palette), while a stored
  FX naming none is Each fixture (`NEW_FX_ELEMENT_MODE` / `STORED_ELEMENT_MODE`, `fxConstants.ts`; a preview's draft says
  the stored one, since the add route reads an absent one as Fixtures). The same doors start an FX whose
  library entry names `stepsFrom` (Colour Cycle, Palette Steps, Slot Steps) **one step behind** — `spreadUnit:
  'STEP'`, `spread: 1` (fx-settings plan D7, `newFxSpread`) — and a preview's draft says its stored unit, `CYCLE`
  where it names none, for the same reason. **The
  spread's shape is *Across the heads*'s** (fx-settings plan D10; `AcrossHeads` in `FxFields.tsx`) on a group or a
  fixture with heads, and always on an FX palette, in this order under *Over*: the **head ladder** (`HeadLadder.tsx`),
  each head numbered by when it starts — 1 first, heads starting together sharing a number and a colour — in rig
  order: the desk's preview in the FX editor's inspector (`previewLadderHeads`, its rows sorted back by `rigIndex`,
  a head hovering its graph row), elsewhere
  `spreadOffsets` on the FX's own heads (`FxScope.headCount`, `spreadHeadCount` — the desk's `buildExpansion`
  counted: one per fixture where the first fixture carries the FX's attribute itself (`carriesFxAttribute`: a slider
  or setting by name, a colour or position by type, the desk's traits), else its heads: one unit a fixture under
  `WHOLE`, a `FLAT` group's head filter across the whole strip on its global index, a `PER_FIXTURE` one's within each
  fixture), or eight notional heads on a palette or a group not yet loaded, *Drawn on eight heads* — sixteen, then
  *+n*; then the **patterns** (FX editor plan D11, below), each drawn on those heads, up to sixteen, and *Custom*,
  under which alone the Order select shows; then **Each head later by** — *Fit the cycle* (`CYCLE`, 1), *1 step*
  (`STEP`, 1, only where the library entry names `stepsFrom`) or *Free*, a slider in the unit the FX is on (steps
  0–16 under `STEP`, a percentage of the cycle 0–200 under `CYCLE`; it holds while its slider passes through 1, and
  a spread written from outside reads afresh) — with a readout of the gap between neighbouring starts as the ladder
  has them (`neighbourGap`) as a share of the cycle, in steps where the FX has them, in beats (seconds on
  the wall clock; a head's own cycle under *One cycle for: Each head*) and degrees, and under `STEP` on known heads
  the spread the desk runs: *⅓ cycle · 1 step · 1⅓ beats · 120° · spread 133 %* (`acrossHeads.ts`'
  `laterByReadout`, over `stepCountOf`; *on eight heads* where a `CYCLE` gap is the notional heads'); then **Segments · Parts · Wings**, each
  a segment `1 · 2 · 3 · 4` (*off* for Parts and Wings) ending in a typed count lit while it is none of them, with
  one line saying what they do (`groupingSentence`: *Segments 2: neighbours move in pairs · Parts off: one pattern
  across all four · Wings off: no mirror*). *Wings* is hidden under a mirrored Order — Centre out, Centre in, the line
  saying so — and set back to 1 in the same write when one is chosen (`orderPatch`, since the desk refuses wings
  there); under Unison only the ladder and the patterns show. Under Segments, Wings or Parts Position's continuous rank
  snaps to its nearest slot first, as the desk's `wholeRanks` does. The four and the unit ride `updateFx` once
  touched (`OPTIONAL_FIELDS`), `parts: 0` turning them off. *Same spread as* copies the four, Spread and its unit,
  and refuses a step spread onto an FX with none; the graph's Spread knob drags in steps under `STEP`
  (`spreadShareOf` over the editor's `steps`) and its hover says *Later by ⅓ cycle · 1 step* (`laterByPhrase`, over
  the preview's offsets). **The ladder, the drawings and the words are the one client copy of the desk's
  rule** (P2): `lib/spreadShape.ts` mirrors `fx/SpreadShape.kt` and the strategies' ranks — Random through
  `spreadPlans.ts`' `javaRandom` — and `spreadShape.test.ts` reads the desk's vector,
  `src/test/resources/fx/spreadShape.fixture.json`, so a head's number is the start the rig runs. The rules are
  pure, in `fxEditorModel.ts` and `acrossHeads.ts`.
  The Values grid's lens chips and a cell popup's FX band open the same editor in
  `EditorSurface` (`FxEditorPopover`), the FX editor draws it in its inspector, and the busk FX tab
  draws it inline under a row — `SplitFxEditor` (`components/fx/SplitFxEditor.tsx`), which the
  popover hosts, so the two cannot split two ways; the copies a split follows (`SplitSession`) are
  the host's to hold, since the tab's editor moves to the first copy's row. It **follows the
  selection** (apply-model plan D5, §4.5): with every head the FX paints selected, or none, a
  move edits it in place; with **some** of a Local FX's heads selected it says *Hex 1–2 of Hex
  1–4*, **holds** the edit (`FxEditor`'s `hold`), and *Apply to Hex 1–2* sends it once over REST —
  `PUT /fx/{id}` with those heads as `targets` (`useUpdateFxMutation`) — and the desk splits them off,
  each where it was, answering the copies (`FxSplitResponse`, put into the running list at once).
  The editor then follows the edited copies: the host reads the first by id (`onFollow`) and every
  later move goes to each (`alsoIds`), and pause and × act on each too. *Edit all 4* sends what is held
  to the whole FX in place. A held edit is never the live floor's: if the selection moves off the
  subset it is dropped and the editor redrawn from the FX, rather than landing on every head. A
  close ends the session, so a copy opened again later is edited alone; the editor stays where its row
  was though the split took that row away (a remembered anchor); and while the split is on its way the
  editor is inert and a close keeps it closed.
  The split goes over REST because the `updateFx` frame answers one only as `fxChanged` REMOVED and
  ADDED, which names no copy to follow. A palette layer's FX (plan P5), a cue's and a manual one
  never offer a split. Which heads the selection covers is the client's reading, for the words alone
  (`components/fx/fxSplit.ts`, over the cell batch, the busk pair or the window's pair); the
  split is the desk's, from the heads named. Row C's **FX**, ⌘K *Apply FX*, the FX table's `+ FX`
  and the busk FX tab's *+ FX…* open the one picker's
  **FX arm** (`ApplyPicker`, `library="fx"`): `FxPicker` on the **bare FX press**
  (its `press` prop — a tap is `POST /programmer/fx` with the door's pair, one Local FX over
  it, the desk first taking those heads off every other Local FX of its family, unless the
  programmer's doors' *Replace · Stack* says Stack — §"Applying to a selection"), above the
  FX palettes that fit, the editor opening in the panel and its Done coming back to the list —
  or, from row C's FX and ⌘K's programmer door, the editor taking the panel once a tap starts the
  FX, its Done closing it and *Open in FX editor* ⤢ beside it (the tray's ⤢, `useOpenInFxEditor`).
  The doors read one pair, `useWindowFxPair` (`components/fx/selectionFxTarget.ts`):
  the targets `useWindowSelectionTargets` gives — the programmer's rows in grid order, a group row
  kept a group so its FX is one group FX with its Order, the desk's while following,
  else the busk view's copy — and the mask a press from this window carries. **On the programmer
  ⌘K's *On the selection* opens the programmer's `+ FX`**, as its *Add FX on the selection…* does
  (`ProgrammerAddFxDoor`, one of the command doors — §"⌘K on the programmer"): the palette lives in
  `Layout`, outside the programmer's marquee store and scope, so a picker of its own there could read
  neither the marquee's mask nor a focused Look layer. Elsewhere it opens its own picker on the window's pair, and its per-fixture
  and per-group items press that one target with no mask. In a focused Look layer the bare FX starts
  on `/fx/add` alone and is absorbed into the Look once the panel's session ends — closed or
  unmounted — by the id the create answered, after the editor lands its held move: the press's
  replacement would take heads off other Local FX, which a press into a Look must not do. The
  popover is bounded by the room Radix measures beside its anchor and scrolls inside it. `ActiveEffectSheet`,
  `AddEditFxSheet` and (session 5) `EffectParameterForm` are deleted. A refused write comes back as
  `fxError`, toasted keyed per FX (`store/fixtureFx.ts`).
- **A pad's running FX** (§3.3, D20; session 5): an instance a **programmer** palette layer
  spawned (`isPaletteLayerInstance` — `paletteId` and `programmerLayerId`, no `cueId`) is edited as
  that running instance only. The editor finds its palette in the current project's list
  (`useSpawningPalette`), marks itself ***edited*** while the draft differs from the palette's
  FX (`differsFromPalette`: a null master is master 1, an omitted parameter the library's
  default, a number however spelled), and its footer is **Update palette** and **Reset to
  palette** in place of Revert. Update is the palette's `PUT {fx}` from the draft **then** W5
  on this instance — the PUT alone would respawn it at the stack's next recook, phase restarted —
  so it re-keys rather than restarts; Reset is W5 alone (`useResetFxToPaletteMutation`), dropping
  any move the floor still holds first. Every *other* running instance of that palette keeps the
  old settings and reads *edited* until reset by hand (`FU-TMPL-FX-EDIT-NO-RETIME`). The editor's ×
  is optional: the busk FX tab leaves it off a pad's FX, which its pad releases.
- **A cue's FX is read-only in the tray** (Fx board, *Open*; session 5, `isCueFx`): it names
  its cue (*on Q12*) and draws no editor, pause or stop — its home is the cue, whose next GO would
  put back anything changed here. Session 3 had left it editable. **Its cue opens it** (the board's
  *opens it*, built after the plan): the open row's *on Q12* is a button onto that cue's **cue
  editor** (`CueEditor` in a right-hand sheet), in place, through `stage/OwnerEditor` — the Stage
  view's *Moves with* path, not a second one — over whichever host the sheet is in, Escape or Close returning to the sheet. The
  project is the current one (a cue FX is always its), and the row is a button only once the cue
  is named from that project's stack list, so a press never lands before the project has. Its name
  is its visible text, never an `aria-label`. The editor is mounted by the tray on first use and
  kept, so a card page's trays mount nothing until pressed. A folded chip for one still
  only opens the tray, where the row names the cue before anything opens over the sheet.
- **The head strip** (`HeadStrip`, D13; HeadsGroups board): on a multi-head fixture, under the
  fixture's own rows and a *Heads · 4 of 12* line; on a group, fixed under the scope line, outside the
  scroller, the group's whole subject. **All**, then a pip per head or member in its **live colour**
  (`FixtureAppearanceSource` — one leaf for a fixture, whose heads are its `segments`; one per member,
  a member key naming one head of a bar — defensive, a patch-loaded group never holds one — coloured
  by that segment), a floor so a dark head is still a target. The gesture is the busk pip's, shared
  through `hooks/usePipRun.ts` with `RigTile`, read as a pick rather than a toggle: **a tap picks that
  head alone**, a mouse drag or a held finger **runs** — the first pip picked alone, every pip crossed
  added — and **⌘ or ⇧ toggles** one in or out; the release's click is swallowed. *All* lights every
  pip, since it holds every head, so a pick that covers every head (and folds to *All*) never reads as
  lost. Beside the pips, the busk band's vocabulary: an **Elements** menu — *All · Odd · Even · 1st half ·
  2nd half* over every head (exactly the element filters an FX starts with) and *Invert* of the
  pick — and *Prev* / *Next*, stepping the pick one head along and wrapping (from *All*, the first or
  last head). The menu's face names the filter last chosen, kept beside the pick, and any other
  gesture clears it. **The pick is the
  sheet's own** — never the desk selection (call 4) — remembered against the target it was made on, so
  it reads as *All* the moment the sheet shows another target (the Stage panel keeps one sheet mounted
  across fixtures). `sheetPick.ts` is the pure model: a `HeadPick` is `null` for *All* or a set that is
  never empty and never every head (`normalisePick`), so *All* is one state; a group's members are
  resolved by key among the fixtures and their elements, never parsed (`groupSheetMembers`). Locate
  acts on the pick (`LocateTargetsButton`); Park and Release stay the whole target's.
- **Rows over a pick** (`rowsOverHeads`, `PickAttributeRow`, `FamilyGroups`' `PickFamilyGroups`): a row
  is drawn where **every** picked head has it, the desk's own group-aggregation rule — a setting only
  where every head's options match, level for level, since one level is another option elsewhere. One picked head
  — or one member — draws that head's own `AttributeRow`; several draw a `PickAttributeRow`, which reads
  every head through one subscription (`useChannelValues`). **Mixed values** read as a range — the
  fill masked below the lowest head, the thumb at the highest, the field empty with `40–80` as its
  placeholder (`EditorField`'s `mixed`) — a strip of the heads' swatches for a colour, and a hollow dot
  per extra head on the position pad; whatever is set lands on every picked head. **Mixed sources**
  dash the edge (`MIXED_EDGE_CLASS`) and the chip counts the heads its source holds — *Programmer · 2
  of 4* (`mergeRowSources`: the strongest source, the grid's collapse) — and its stack
  (`LayerStack` over several heads) is one section per distinct stack with the heads that share it.
- **Writes over a pick** (`PickWrite`, `sheetWrites.ts`): a group's *All* is **one group entry**
  (`programmer.set('group', …)`, which the desk fans to the members with `sourceGroup` — Record turns
  it back into a group row); a subset of members is each member's own entry carrying the group as
  `sourceGroup`; a fixture's heads — *All* included, since the desk has no all-heads attribute to write
  — are each head's own entry. **Never a raw channel**: the group visualisers' sliders sent one per
  member, and `useGroupAttributeValues`' update hooks went with their raw-channel fallback. A position
  is resolved per head; a group's *All* stays one entry only where every member lands on the same
  bytes (one model), else each member's own. A colour fills an emitter it does not say from each
  head's own channel (the desk reads a missing one as 0), and is one group entry only over members
  with the same emitters. A dimmerless colour's *Dimmer* (a group member's, never a fixture's head)
  scales each head's colour, so it is never one group entry. The row's × over a group's *All* is one group `clearEntry`.
- **FX follow the pick** (`TrayPick`): the tray lists what reaches the picked heads or members
  (`fxReaching`, still the one filter), an FX on one head reading *on Head 3*. **+ FX**
  starts on the fixture or the group for *All* (a group's with its Order and Over in the
  editor), on that head or member alone for one (`headAsFixture` — the desk resolves a
  head's key as an FX target), on the fixture with the **element filter** that names a pick of
  exactly the odd, even or a half of the heads (`elementFilterFor`, the desk's `ElementFilter` clause
  for clause), and otherwise says why it cannot — one FX has no target for heads 1, 2 and 7. A
  pick that moves under an open picker re-keys it. The tray has **no Look picker** (absolute-looks
  plan P7): a Look lands whole, so none is put on one fixture — an FX palette does that job.
- **The group sheet**: *Values · Members* (`GroupMembersSection`, a member opening its own sheet, where
  a group's FX reads *via <group>*), Locate, **Park** over the members' channels (a member that is
  one head brings that head's channels, not the bar's — `ParkButton`, unpark confirmed) and **Release**
  (`clearTarget('group', …)`), the model line `Group · 6 × <model>`, the scope line *Writes the group
  to Local* or *Writes 2 members to Local*, and the group's surface-binding badges
  (`GroupBoundControlsRow`). `GroupPropertyVisualizers`, `HeadsSection`, the "Virtual" badges and the
  group visualisers' Edit toggle are deleted. A group's rows come from its members' own fixture
  attributes; a group of multi-head fixtures shows its members' fixture-level rows, and each member's
  heads are that member's own sheet.
- **A group's member order** (venue-visit plan S7; lighting7 `docs/groups-engineering.md` §"Member
  order"): the sheet's *Members* view lists the members **numbered in the group's order**, the order
  its FX and a fan over it run in. It is dragged to reorder, and each member's **step** under
  the Order *Runs as* picks is shown (the desk's `orderSteps`, never a client formula; the
  group's own Order first). A drop writes at once through `useReorderGroupMembersMutation`,
  sending only the members shown. The desk keeps every other member in its place, and so does the
  optimistic move (`lib/memberOrder.ts`'s `placeInOrder`), rolled back on a refusal.
  `useGroupMemberReorder` joins the runtime group to its patch group by name, for the id, the owner and
  the patch ids. On a venue's group in a show the drag is the show's order of it (group-order plan
  D14): a body naming only `memberOrder`, which the desk stores as an overlay, drawn *Your order* with
  *Take venue's* (`useTakeVenueGroupOrderMutation`, `memberOrder: null`) beside it while
  `orderOwner` is `SHOW`. Patches' *Edit Group* sheet drags it too, keeping its name, members and
  delete locked. A venue's rig for an operator is read-only with the reason, here and there. The
  Update review lists the venue's reorders of a group the show orders beside facet drift, kept unless
  answered *Take venue's* (`orders`, `order:<groupUuid>`); Attach's order-only pairs are a line of its
  *Drawing only* list, which asks nothing. The card keeps its members' cards. The Groups
  list draws member rows in the group's order (`GroupSummary.memberKeys`) with their place, and has
  no drag of its own, by Chris's call: its rows already carry the marquee and row selection. A
  Fan over exactly one group row whose members are all listed and none filtered out sends the
  **group** (`wholeGroupOf`, `SpreadColumn.group`), so the desk runs it in the order the numbers
  show; anything else is its heads, in rig order. The client's copies of the rig order
  (`lib/cellsSubSelection.ts`, `liveAppearance.ts`'s `rigHeadOrder`) walk a group by `memberKeys`
  as the desk does.

Session 6's list: `hosts.test.tsx` — the phone host opens on a fixture tap below `sm` and no docked
`stage` panel draws there, the desk still docks, the landscape side form (380px, no grabber), the
sheet not modal; `PhoneSheet`'s three heights, the grabber's tap, keys and drag (its click swallowed),
the dismiss below the peek, the safe-area class and the keyboard's bite; a card's corner opening the
pop-up and a group card's the group sheet — and `FixtureSheet.test.tsx`'s finger sizes and the tray
outside the scroller at the bottom sheet's foot.

`FixtureSheet.test.tsx` pins the plan's list — family order, each source kind's chip and edge, the
dot, × drawn only while held and its words, Release's frame and toast, Enter with the unit,
`setPosition` and never `channels.update`, the tray outside the scroller, no Edit toggle, read-only
offline and on a parked row, the picker and the inline editor in the tray — and `hosts.test.tsx`
the two hosts. Session 3's list is `FxPicker.test.tsx` (a tap starts a programmer-owned FX; a
second tap is one `updateFx` and no add), `FxEditor.test.tsx` (a drag at most one frame per 50 ms
with the release landing, Revert's snapshot, Around as `ADDITIVE` + 128 with the centre hidden and
read back, Within as `MULTIPLY`) and `store/fixtureFxErrors.test.ts` (`fxError` keyed per FX);
the layer absorb, once, is `ApplyPicker.test.tsx`'s FX arm.

Session 4's list is `HeadsGroups.test.tsx` — the pick resets when the target changes, a run selects,
*All* on a group sends one group write, a subset sends member writes carrying the group, no raw channel
write for a group — beside heads 1–4 of a 12-pixel bar written alone, the range and the swatch strip,
*n of m*, and the tray following the pick; `sheetPick.test.ts` (the model, the element filter, the
position write's group rule), `rowSource.test.ts`'s merge, `LayerStack.test.tsx`'s group sections and
`hosts.test.tsx`'s two group hosts.

Session 5's list (the Busk and Programmer views): a new movement palette saves `ADDITIVE` + 128
(`PaletteEditor.test.tsx`, and a stored Override reading as Absolute); an edited instance is marked
and Reset calls W5, and Update palette PUTs the instance's settings then re-keys
(`FxEditor.test.tsx`, `FxSheet.test.tsx`); the FX tab is in every place `LIVE_SHEET_TABS`
reaches (`SideSheet.test.tsx`, `buskWindow.test.ts`, `ScreensSheet.test.tsx`); Release on row C
sends one frame per selected row (`FixturesListContainer.test.tsx`), and on the rig row folds with
the verbs (`RigBand.test.tsx`); the grid dot appears for a held-back cue FX
(`FixturesTable.test.tsx`, `heldBack.test.ts`); the tile menu opens the sheet (`RigBand.test.tsx`).

### Applying to a selection

**One press, whatever it is pressed from** (`../docs/plans/completed/apply-model-plan.md` §4.1, session 3).
A press is *source × selection pair → scope*: a palette or a Look, the targets and attribute mask the
window is acting on, and where the programmer is pointed. **`hooks/usePress.ts`** is the one place
that turns that into a route, and every door calls it: the one picker
(`ApplyPicker`, from a band's *All …*, the Stack table's *+ Look or palette* and row A's *+ Look*), the popup's
Palettes bands (the programmer's colour, level and position editors, and the busk Colour, Level,
Position and Beam tabs), a busk
pad's *Apply as values*, the hand's place on the programmer's stack and a cue slot's Look. A busk
pad's own press stays `POST /busk/pads/{id}/press`,
which carries its bank's solo siblings. The **bare FX** is the one source `usePress` does not
take: row C's **FX**, ⌘K *Apply FX*, the FX table's *+ FX*, a band's *+ FX …*, the busk FX tab's
*+ FX…* and the picker's FX section
press it through `FxPicker`'s `press` arm, `POST /programmer/fx` with the door's pair (one Local
FX over the selection; the desk takes those heads off the family's other Local FX, unless the door
asked **Replace · Stack** and the operator chose Stack — below), or, in a focused Look, `/fx/add`
then `absorb-fx` (§3.2 rule 6) — see §FX in the fixture sheet's paragraphs.

- **The gestures**, the same on every chip and pad (D1, D8): **tap** is the press — for a value
  palette, **references** to it on the pressed heads (lighting7 palette-references plan D10–D12:
  `pal:{uuid}` as your values, which track the palette; no layer), for an FX palette or a Look a
  layer that *tracks* the record (`/palettes/{id}/toggle`, `/looks/{id}/toggle`) — or off the
  pressed heads when it already covers them all, the desk deciding which. The answer's `omitted`
  and `notCovered` (D14) say what a press left out, toasted by `reportLeftOut` (`usePress`, the busk
  pad's press) as one note: *DSR Focus: its spot-1, spot-4 were left out*; **⌥click** is *Apply as values*
  (`/palettes/{id}/apply`, `/looks/{id}/apply`): literals and Local FX with no layer, which
  retuning the record never moves; **hold or right-click** opens the menu — the tap's verb named
  (*Put on* / *Take off these heads*, from the chip's ring; *Put on or take off its own heads* with
  nothing selected, where the ring cannot say; a Look's *Put on* / *Take off*, since it lands whole),
  *Apply as values*, *Pick up*, *Edit palette…*
  (`/palettes?edit=<id>` opens that palette's editor, once the current project has loaded) — and
  **never presses**. Since a hold changes nothing on the rig it is armed for every pointer, a mouse's
  too, as a busk pad's always was; the click its release generates is swallowed. Only the primary
  button presses or holds (`useLongPress`): on Windows `contextmenu` follows the release, so a
  right-click pressed a busk pad before its menu opened. `components/press/PressGestures.tsx` holds the
  gesture hook, the shared title, `PalettePressButton`, the chip-with-menu the picker and the bands
  draw, and `LookPressButton`, the picker's Look pad (no *Edit* item: `/looks`
  opens no single Look yet). **The menu is a modal `Popover` anchored on the button, never a
  `ContextMenu`**: Radix's `ContextMenuTrigger` arms a ~700ms touch timer of its own that the
  button's hold would race; and modal, so the tap that dismisses it is not also a press on the chip
  underneath. A busk pad keeps its `ContextMenu`, reached through the synthetic `contextmenu` its hold
  dispatches, and gained *Put on* / *Take off these heads* and *Apply as values* (⌥click too) on a
  palette or Look pad; a cue pad's menu is unchanged, since a cue is playback.
- **The pair** is the door's: `usePress(projectId, pair)`. A door with none of its own — the
  programmer's hand place and the Stack table's *+ Look or palette* — takes the window's through
  `useWindowPress` (`useWindowPressPair`; that picker reads what those heads can take from the same
  snapshot through `useWindowPaletteFit`): on the programmer the list's own, the heads a press lands
  on (`paletteTargetsFor`) and the marquee's families published through the marquee store as
  `MarqueeSnapshot.press` (held by content, so a rows refetch republishes nothing), an unlinked
  window's own selection included; elsewhere `useSelectionPair`, the desk's while following and the
  tab's copy when not. Row A's *A new palette* (Record ▾) reads the same snapshot, since that row sits
  outside the grid. The split is a cost one: a door that brings its own pair — a cell batch, a fixture
  sheet's one target, a cue slot's none — then subscribes to no marquee frame. **A Look's press names neither half** (absolute-looks plan D1, D3): it lands whole, so
  `/looks/{id}/toggle`, `/looks/{id}/apply` and the cue's layer press go out with no targets and no
  mask, whatever the pair.
- **The scope** (D7): with a Look layer focused (`LookRowStore`), a palette press goes **into the
  Look** — a value palette's tap writes its references into the Look's rows, or takes them out, on
  the desk (`LookRowStore.pressPalette` → `POST /looks/{id}/press`, lighting7 palette-references plan
  D23; the draft lands first, no press if that save fails, and the answer's Look goes in the cache), and its *Apply as values* (⌥click)
  through `/palettes/{id}/apply` with `write: false`, each head's literal landing in the layer's
  draft through `setValue`; no `programmer.*` frame sent either way; an FX palette
  started on the selected heads and then absorbed (`absorb-fx`, *+ FX*'s path),
  and stopped again if the absorb is refused, since a press into a Look must leave the stage as it
  was; a Look refused, since Looks don't nest. `landValuesInLook` is the draft's one landing, shared
  with Fan's `write: false` arm. A door that writes Local whatever the grid is pointed at passes `scope: 'programmer'` (the
  fixture sheet, a cue slot, the busk view). In a focused Look the chips draw no ring and the menu
  offers *Put on or take off in <Look>* and a value palette's *Apply as values* (an FX palette's *Into
  <Look>* alone), as the cue editor's does. `write: false` stamps no recent. **In the cue
  editor a press writes the cue** (apply-model plan §3.6, session 8): `PressInto` gains `{kind:
  'cue', cueLabel}`, from `PressCue` (`components/press/pressCue.ts`), which `CueRowStoreProvider`
  provides and `usePress` reads before the programmer's scope — so every door under the editor (a
  cell's bands, the Looks tab's picker) is in the cue and none outside is, and a door passing
  `scope: 'programmer'` still writes Local. A palette or a Look is `POST /cues/{id}/layers/press`, the
  desk's rule over the stored cue — a value palette's reference rows in the cue's own rows, an FX
  palette's or a Look's layer; *Apply as values* lands a value palette's literals as the cue's
  own rows (a Look or an FX palette is refused, a tap being its layer); the FX arm is
  `FxPicker`'s draft arm. Nothing reaches the stage.
- **The desk owns every rule** (plan P2): coverage, family replacement, widening and the mask test
  are server-side. The hook pre-refuses one thing: a **palette with no targets that would land
  nowhere** — a generic one on either gesture, a per-fixture one applied as values — which the toggle
  and apply routes take without refusing (only the busk pad's route refuses it,
  `PALETTE_NEEDS_SELECTION`). A per-fixture palette's tap names its own heads, so it goes out (the
  menu says so, *Put on or take off its own heads*; a generic palette's reads *Put on — select
  fixtures first*), and nothing is refused from the mask. A palette's tap sends
  `attributeMask` (its family) as the belief it acts on, though the desk derives a palette layer's mask
  from its own rows; the response reports the mask applied, so a disagreement shows there rather
  than on the rig. A refusal is the
  desk's sentence, toasted by `errorToastMiddleware`. The hook reports the rest one way: *Apply as
  values*' per-head skips, *n FX started* for an FX palette (gated on the palette's
  **kind** — the desk answers a value apply with `fxIds: []` too, and reading that as "nothing
  started" toasted red on every value press), and in a focused Look the values that could not reach
  its draft. It answers what
  happened — `on`, `off`, `into`, or `null` for a refusal — which is how the hand's place says *taken
  off it* when the record was already on every selected head (`HandPlacement.describe`).
- **The Stack table** (§4.6): a layer's settings popover has **Detach** on a row of its own
  (`programmer.detachLayer`, the layer becoming Local with nothing on stage moving), and a row's second
  line names a replacement while it stands — *lost Hex 3–4 to Amber* — `lib/replacementNotes.ts`'s
  reading of the last two applied-state frames (heads by the desk's own resolved targets, never a
  client expansion; a bar lost whole named once, not with every element; names by `lib/headName.ts`,
  numbers kept as written), held until a frame that changes anything. It is never stored, and the first frame a window sees says
  nothing.

**On the grid a hold is the marquee, not a menu** — as a speed card's hold is its fader. On a touchscreen a finger pans and only a held
one marquees (`touch-action` cannot say that, so `useCellMarquee` arms by time for `touch`/`pen` and
refuses `touchmove` only while a marquee is live). A chip and a cell never share a point, so a hold
never means two things where a finger lands. The other phone rules on row C: below `@[600px]` it is
the heads chip counting cells · the mask's glyph · Set · Fan · FX · Release ▾ (`SelectionBar.tsx`, §The
programmer's scoped grid), Deselect in the chip's menu, and a tap on the grid's empty background
runs the same cells-then-rows ladder Escape does — only on the grid's own DOM, since React bubbles a
click inside a portalled cell editor up the same tree. On a touch screen row C also offers ***Add***,
touch's ⌘ (programmer-fixes plan D7, §Sheet kit) — a button, or on the phone arm the heads chip's
menu item: while it is on, a tap adds or takes out one cell or row and a hold's marquee accumulates,
so a selection with gaps can be built by finger.

`useTemplatePress` and `useTemplatePressHandlers` are gone; `usePress.test.tsx` pins the routes, the
pair and the scope.

**Palettes reach a selection through Set** (programmer-toolbar plan D1, D2). The palette strip that
rode row C is gone: Set's popup already draws the family's value palettes above the editor and its FX
palettes below (`PaletteBands`), with *All …* opening the one picker, and Set opens on whole rows too
by asking the family first (§The programmer's scoped grid). The busk view's pads are the one-tap door.

**Three doors, by what each needs** (programmer-toolbar plan D1). A **Look** needs no selection, so
**+ Look** is in row A on every tab, between Blind and Include (`programmer/AddLook.tsx`): the one
picker on its Looks with the pair `{targets: [], families: null}`, its ring, ⌥-click as values, open
while pressed. Below 600px of row A it and Include are one **+**, whose picker is led by *Include a cue
or Look…* (`ApplyPicker`'s `lead`), so the verbs keep their phone width. An **FX** starts on the
selection, so **FX** is on row C between Fan and Release (`programmer/ProgrammerAddFxButton.tsx`, over
`useProgrammerAddFx`'s offer: disabled with its reason in Output, absorbed in a focused Look): the
picker's FX arm under the button, on the mask's family when it names one. Palettes go through Set.

**Replace · Stack** (`components/fx/ReplaceStack.tsx`, plan D8) is the bare press's one question:
*Replace*, the default, is the press as it always was; *Stack* sends `stack: true`, which stops and
narrows nothing and starts the FX on its family's stacking blend (lighting7 `fx/FxStacking.kt`). It
is asked only where it changes something — a Local FX already reaching a selected head in the family
**the desk replaces in**, the family of the attribute the FX lands on (a Pulse on `uv` is Colour),
other than one the session started (`localHolderIn`, over `fxLanes`) — and only of the Local press:
a focused Look absorbs, a cue stores. Both doors that ask it — an FX editor lane's *+ FX* and the
picker's FX arm **on the programmer page** (where `useProgrammerScope` answers) — ask through one
hook, `useReplaceStack` (`components/fx/useReplaceStack.tsx`), over `useLocalFxHolder`, which reads the running list before any preview:
`FxPicker` reports the attributes a tap would **start afresh** on (`onLanding` — every one its listed
FX land on but the auditioned FX's, where a tap swaps it, and the *On* row's options in another family
than the auditioned FX's), the segment is drawn when one of them is
held by a Local FX other than the session's own (a fresh start removes that one first), and the press
takes the choice per landing attribute (`press.stackOn`). A **swap** is never asked, nor is the *On*
row's move of the auditioned FX to another setting of its family: each keeps how its FX started, which `FxPicker` records on the auditioned FX (`AuditionedFx.stacked`). A fresh start —
another attribute, a selection the session has started nothing over, or after the session's FX
stopped elsewhere — is asked of its own landing, and every start puts the segment back on *Replace*.
**The auditioned FX is the selection's**: both doors keep one per selection the session started over
(`useAuditionedFx`, keyed by `selectionKeyOf`, the FX's own target order), so a tap after the
selection moves starts afresh on the new heads and leaves the old FX running on the old ones, and back
on those heads a tap swaps that FX again; in a focused Look the absorb takes every one. So every FX door on the programmer page asks — row C's FX, ⌘K's *On the selection*, the FX
table's *+ FX*, a band's *+ FX …* and the palettes' *FX…* — and no door elsewhere does: the busk
view's family tabs, its FX tab and ⌘K's own picker off the programmer still press as they always did.

**Once started, row C's FX is the live editor** (D9): ⌘K's door too (`ProgrammerAddFxSheet`'s
`editor`, the arm's `editOnStart`). A tap starts the FX and the panel turns into `FxEditor` on it,
with *‹ Swap* back to the list — kept mounted under the editor, so it comes back on the family the FX
was started from and, on the selection it was started over, its taps swap it, phase kept — *Open in FX editor* ⤢ and the editor's Done,
which closes the picker; a selection that leaves under row C's open picker closes it. **Its closes
can be traced** (programmer-fixes plan D25, `lib/fxPickerCloseTrace.ts`): with
`localStorage['lighting7.debug.fxPickerClose'] = '1'` in a window, read at each close, every close of
`ProgrammerAddFxSheet` logs one `console.warn` naming its door — `ApplyPicker`'s Radix dismissal
(escape, outside press, focus outside, its sheet's ×) or Done, which it passes as `onOpenChange`'s
second argument, ⤢, the offer turning disabled (its reason, whether the window follows the desk and
the desk selection's `source`), the FX button pressed again and row C unmounting under it (the host's
selected and visible rows, *Lit*, the filter). Off, nothing is logged; it goes once the plan's §6 check
settles why the picker was seen closing. ⤢ is `useOpenInFxEditor` (`fxEditor/openInFxEditor.ts`, the
fixture sheet's tray's move lifted out of `FxTray`): the FX picked, the FX tab on *Selection*, the FX
table shown — `?table=fx`, replacing the address on the programmer. Nothing is reselected: the
selection is already the editor's subject. It is never drawn under a focused Look, whose FX goes into
the Look when the panel closes.

The offer is `paletteFits` (`lib/paletteOffer.ts`), which the picker and the popup bands both filter
by, so they offer one set: the family, the emitters (a palette naming `white`, `amber` or `uv` is
withheld unless some selected head has every emitter it names — the family cannot draw that line,
since the hex and all three emitters are COLOUR; see §Looks, palettes and layers) and a Beam
attribute. **The selection's mask filters it too** (apply-model plan §4.2): a palette outside the
families the press carries is not offered, since the desk would refuse it — an offer, never a refusal
of a press; the picker shows those under *Outside the mask*.

`paletteTargetsFor` in `rowModel.ts` is the target rule — since venue-visit plan S1 it *is*
`spreadTargetsFor`'s walk, and not `selectedRowTargets`: a group row lands on its *visible* members
(the filter rule every group-row action keeps), and an element row lands on its **element**, `{type: 'fixture', key: element.key}` — dropped under a selected parent, which
covers it. It folded an element row onto its fixture until venue-visit plan S1, on the belief that the
palette route would drop an element key; it never did, and the fold put a colour pressed on one zone
of a Shehds onto a parent with no colour. A whole fixture whose attribute lives only on its elements is
fanned to them **by the desk** (lighting7 `docs/lighting-composition-model.md` §"A press is per
target"), so the client never expands one; `paletteTargetsOf` in `rowModel.ts` is the same rule
over a batch's write targets. The families and emitters a press is offered by are read off the
**same** targets, each resolved to what it is — a fixture whole, an element as that element — so an
element selection is offered what the element can take (`FixturesListContainer`'s `writeTargetByKey`,
which holds every element), and row C counts them as *heads*. `NewPaletteFromSelectionSheet` takes the
same targets, so a press and *A new palette* cannot name different heads.

**Recency is a desk fact** rather than a tab's: it orders the picker's *Recent* section and puts the
pressed first in a popup band. lighting7 stamps `last_pressed_at_ms` on every press that *applies* a palette
— through four doors: the chip's ⌥click (*Apply as values*), its tap, a busk pad press, and a MIDI `pressPalette`
— so every client and the desk's own hardware agree about what was reached for; a toggle **off** is
not a press, and neither is an *Apply as values* that reached no head. It arrives as the keyed frame
`palettePressed { paletteId, lastPressedAt }`, which `startPalettesBridge` **patches** into every
cached `paletteList` entry (all `family` args — the filter is a query argument, so the programmer's
unfiltered list and `/palettes?family=` are two entries of one endpoint). It must not be folded
into `paletteListChanged`: that bridge invalidates `PaletteList`, `Cue` and `CueList`, and a press
happens at busking rate. `lib/paletteRecents.ts` is the one place that orders them, and it orders
by **parsing** the stamps: `Instant.toString()` omits the fraction on an exact second, so `…:34Z`
compares *after* `…:34.500Z` as text.

**`ApplyPicker`** (`components/press/ApplyPicker.tsx`, apply-model plan §4.2) is the one picker every
library door opens: on every family the selection fits from the Stack table's *Palette…*, on one family from a popup band's *All …*, on the Looks from its *Look…*. It is
the offerable library as a searchable pad grid, sections Recent · All A–Z · Per fixture · FX
(the FX palettes, and *FX…*, the way to the bare FX) · **Outside the mask** — the palettes the heads
could take but the selection's mask leaves out, dimmed with the reason (*Colour — the selection is
Position*) and **still pressable**, since the mask is tested on the on arm only and a lit one must
come off. Its Looks are **one list**, A–Z: a Look lands whole, so neither the selection nor its mask
says anything about one, and its ring is whether a layer of it is on (absolute-looks plan D1, P4).
Built from the busk pad's own face (`padFace.ts`'s `PAD_SHELL` / `paletteSwatch` / `padPresenceClass`,
and `paletteLayerPresence` / `lookPresence` against the desk's resolved applied state). It presses
the pair its door hands it through `usePress`, and **never asks for targets**: it replaced the
add-layer sheet's source and target steps — on the programmer in session 5, in the cue editor in
session 8 — whose `CueTargetPicker` step landed one
fixture or group whatever was selected. Four things about it: it takes **`useEditorForm`'s three forms** and neither of
its own media queries, so the picker and a cell editor never disagree about which shape a screen
gets, and a door with no anchor (the Stack table's create verb) takes the right-hand sheet on a
desk — `ApplyLayerPicker` is mounted only while open, since its pair is a marquee subscription; **a press does not close it**, because auditioning three colours in a row is the normal case;
it computes nothing while shut, since its host re-renders at marquee rate; and its popover width is
a **media** query rather than a container one — `PopoverContent` is portalled to `body`, so row C's
`@container` is not an ancestor and a container class there would match nothing, silently.
Its popover hangs on an anchor and has no Radix trigger, so it hands the focus back itself: Escape
returns it to whatever opened it (programmer-toolbar plan §4), a press elsewhere leaves it where that
press put it, and the FX arm, which has no search,
opens with its family segment focused.
**The FX arm** (`library="fx"`, apply-model plan §5, session 6) is what row C's FX, ⌘K *Apply FX*,
the FX table's *+ FX* and a band's *+ FX …* open, and the palettes' *FX…* switches to (with a way
back): `FxPicker` over the pair on the bare FX press, opening on the door's family and preferring
its attribute (`attribute`, a cell's), *Edit <name>* putting the live editor in the panel — or, with
`editOnStart`, the start doing so — *Replace · Stack* where the door asks, and below it the **FX
palettes** that fit, pressed through `usePress`. It retired `FxAddSheet`. Under a focused
Look the FX starts on `/fx/add` and the arm absorbs it when the panel closes or unmounts, once —
unless the editor's × stopped it. It sizes to its list (up to the same 540px), where the palettes and
Looks hold their height so a search never moves the panel under the pointer.
`ApplyPicker.test.tsx` pins the sections, the offer, the mask arm, the Looks' one list and ring, and
the FX arm's press, absorb, *Replace · Stack* and the editor on start; `ProgrammerAddFx.test.tsx` the
doors (row C's FX, ⌘K's, the FX table's, ⤢) and `AddLook.test.tsx` row A's + Look and its **+**.

**A new palette** — Record ▾'s, from the selection (programmer-toolbar plan D2) — is server-side
(`POST /palettes/from-programmer`), for the same reason apply
is: converting a recorded *literal* back into an **intent** is per-head arithmetic that has to agree
with the resolver. It also decides **generic vs per fixture from the data** — one row per attribute
where every selected head agrees, one row per head where they do not — rather than from a toggle,
because the operator already said which they meant by putting the heads where they are. The
colour inverse is a documented heuristic (fold the emitters back into RGB, policy `extract` when
either was driven); it lives in one place, `paletteRecord.kt`.

### The desk selection has a mask

**Every press sends the pair it is acting on — targets and `families` — and the desk decides what
lands** (multi-screen plan D4, D5; lighting7 af3575a). The four press routes take `families?`
beside `targets`: `POST /busk/pads/{id}/press`, `/palettes/{id}/apply`, `/palettes/{id}/toggle`
and `/looks/{id}/toggle` (`buskApi.ts`, `palettesApi.ts`, `looksApi.ts`). The pair is the desk's
while the tab follows the desk and the tab's own when unlinked — `usePressFamilies` in
`store/selection.ts` answers it for the programmer's doors and the picker (the pair they hand `usePress`),
`useBuskingSelection` for the pads. It is the *desk's* mask when following even in the
moment another window has moved it under this tab's marquee, because a following tab's press acts
on the desk's selection and must be masked as the desk is; Set's Palettes band and the picker offer
nothing outside it, and a press that reaches the desk outside it anyway is refused by name — and
**row C's mask reads that same pair** (`pressFamilies` in `programmer/SelectionBar.tsx`), drawn over
a row-only selection too, so the one state where the two differ (another window's mask landed here as
rows) is said on the bar rather than discovered from a 400. The plain lists keep the marquee's own
reading, never bridging.

**The family pill is the mask's picker** (apply-model plan D3, §4.4; `programmer/SelectionMaskPill.tsx`):
press it and S10's `MaskToggles` opens in a popover — the *All attributes* switch, and the four
families while it is off. The pill's face is always the stored mask (`All` while there is none), never
the picker's draft, and the toggles go through `useMaskDraft`, since the selection's `families` folds
all four ticked to none and has no word for none ticked: switching *All attributes* off ticks all four
and writes nothing, an untick writes the narrowing, all four ticked back writes "no mask" with the
switch left off, and unticking the last family writes nothing and says *Pick at least one attribute.
The selection stays …*. **Every write keeps the targets**: following, it is
`selection.setFamilies` (`setDeskSelectionFamilies` in `store/selection.ts`) — the mask alone, the
desk keeping its heads, so no heads heard of late are sent back to undo a toggle or a publish still
on its way. On the programmer it goes through the list's own bridge — `useDeskSelectionBridge`
answers `remask`, handed to the bar through `renderToolbar` — because its echo must be known as the
list's own: the FIFO gains the frame the desk will answer with (the newest unacknowledged publish's
heads, else the last frame's, under the new families), so the echo is acknowledged rather than read
back through `rowIdsForTargets`, whose lossiness would reshape the rows (a fixture under two expanded
groups, a parent and its element rows). A standing marquee whose columns no longer say the mask drops
to its rows, under apply's mute, so the drop publishes nothing over the mask just set. An **unlinked
programmer keeps a plain badge** (the bridge answers no write there): its own selection is the list's,
whose only mask is the marquee's columns, so there is no fact for a picker to write. The marquee still sets the mask and a
rows-only click still clears it; the picker's draft follows either as a write from elsewhere. **The
busk view carries the same picker** on every row that draws the pill — the rig row in Split and Rig,
the compact row, the rig strip and the pad row in Pads — as `busking/BuskFamilyPill.tsx`, writing
through `useBuskingSelection`'s `setFamilies` — `selection.setFamilies` while following, the tab's
own copy with its targets kept when not. Since the rig has no marquee, the busk picker is drawn **whenever
something is selected**, `All` while unmasked, and with a mask but nothing selected — no longer only
while a mask is set. The sheets that take a mask
open on this one (§Looks, palettes and layers, *Every mask a sheet takes*).

What the desk does under a mask, per kind: a **palette** is one family, so it lands whole or is
400 `PALETTE_OUTSIDE_MASK` on every door, apply and layer alike; a **Look** and a **cue** ignore it
— a Look lands whole, its layer's mask a setting of the layer, so a Look's press sends none.
The refusal carries a message naming both families and reaches the screen through
`errorToastMiddleware`, which toasts every rejected mutation — which is why `usePress` toasts no
failure of its own: it would say the same sentence twice.

**The mask is tested on the on arm only, so never pre-refuse a press from the mask this tab holds.**
A press that turns a lit record off answers `removed` under any mask (`routes/pressArm.kt` reads
the arm before the press), and only the desk knows which arm a press is on — a lit Intensity pad
must still release while a Colour marquee stands. Send it, and render the desk's answer.

**No press skips a family** (absolute-looks plan D3): the busk press, the Look routes and the cue's
layer press answer no `skippedFamilies` any more, since a Look's press ignores the selection's mask
and a palette's is one family. `skippedRowsMessage` (`lib/selectionMask.ts`) is Fan's alone now. A
Look layer's mask is set on the layer itself, and it filters the layer's rows, not its FX.

### The programmer's keyboard

**There is one editor per column, and the keyboard opens it.** Select cells, press Enter (or just
start typing), and the cell editor for the first selected cell opens with its first text field
focused and selected; what it commits lands on every selected cell through `commitToCells`. Enter
in any field applies and closes, comma steps to the next field, and the marquee stays so the next
Enter opens it again.

**It used to be two editors**, and that is the thing to understand before touching any of this. A
click opened the column's editor; Enter opened `CellEntryPopover`, a single line of text with a
grammar of its own (`parseCellEntry`: `127`, `50%`, `full`/`out`, `#ff8800`, `r,g,b`, `pan,tilt`).
Both are deleted. Two editors for one job drift, only one of them can be improved at a time, and
the text one could only ever set what a line of text can say. What went with the grammar, and where
it went instead:

- **Hex typed as text** — replaced in kind: the picker and the R/G/B boxes say the same thing.
- **`pan,tilt` and `r,g,b`** survive as a **gesture** rather than a grammar: comma steps to the
  next field. That is what `PositionCell`'s new pair of boxes is for — it had none, only a drag —
  and what makes the colour editor's R → G → B → emitters a typed sequence.
- **`50%` came back in kind, `full` and `out` did not.** The programmer's level editor's box is
  a **percent** since the editor kit (D13), so `50` typed there is what `50%` said; the DMX
  sheet's stays a byte. `full` and `out` are still gone and nothing replaced them: the box is
  `type="number"`, so those characters cannot be typed into it at all. Said plainly because it is
  the one part of the deletion that was a real loss rather than a relocation: it was put to the
  desk as a question and answered *leave it as built*, on the same reasoning as hex. Getting them
  back means a text grammar in the number field, which costs that field its arrow-key increment —
  so ask before reaching for it.

`components/editor/useEditorKeyboard.ts` is the one copy of the rule, shared by
every cell — the programmer's four and the kit's — **and by `SpreadPanel`**, which is the same kind
of panel and had the same gap. Two
things in it are not arbitrary. The focus is taken in **`onOpenAutoFocus`**, not in an effect:
Radix's own auto-focus is a *parent* effect and parent effects run after a child's, so a focus set
from inside the content is taken straight back off it. And **comma is left alone in a one-field
editor** — there is nowhere to step to, and a type-ahead may want the character.

**The panel behaves the same however it was opened** — the selection bar's Set, a keystroke, or a
double click on the cell. The first field is focused in all of them, and that is
not a nicety: *drag three dimmer cells, Enter, type `128`, Enter* is the ordinary desk gesture, and
it only works if the open leaves the field focused. A first cut focused the field only for a
keystroke, on the reasoning that a tap must not summon the on-screen keyboard, and quietly broke
exactly that. Refuse any change that makes focus a function of the gesture again.

**A released drag opens nothing, and neither does a single click.** The drag half went first
(`PD-POPUP-AFTER-DRAG`: a single-column marquee opened its first cell's editor behind the release)
when the selection bar gained a **Set** of its own; the click followed it, for the same reason. The
drag and the click say *what* to edit and Set — or Enter, its key, or a **double** click — says
*do it*; a popover springing open under a pointer that had just finished drawing a rectangle was
the second gesture being made for the operator, and it had no equivalent for a drag that spanned
two columns. `singleColumnAnchor` and
`onSingleColumnDrag` went with it; `autoOpenCell` in `FixturesTable` is fed by the container's
`keyboardOpen` alone now, whichever way the operator asked — the double click never reaches it,
being the surface's own.

**The one thing that does differ is the *form*, not the gesture.** Focus is taken in the popover
and in neither sheet (`useEditorForm`), because both sheets are reached by a finger and there
the keyboard rises over the grid for nothing. That is a question about the surface, so on any one
surface every way in still behaves identically. `SpreadPanel` opts out with `autoFocus: false`
where the marquee spans several spreadable plans or families: then its first question is *which*,
and jumping to the From box would skip the chooser that decides what From means. With one column
the selection has answered that, and From is focused like any editor's first field — in the
popover host alone; the docked busk tab is reached by a finger.

**`keyboardSeed` carries the character and nothing else.** A string (`''` for a bare Enter, null
for a click or a drag) threaded container → table → cell, latched by `useEditorOpen` into
`keyboardOpen` for as long as the editor is open, and read only to seed the first field.

**A character typed at the grid opens the editor and lands in its first field**, committing as
though it had been typed there — every field on this desk writes as it is typed. Digits, `.` and
**letters**: a letter is the first character of `SettingCell`'s type-ahead, and a numeric editor
simply ignores one (`numericSeed`). `#` and `,` went with the grammar — there is no hex to start,
and comma now means something inside an editor.

**`SettingCell` is a type-ahead** above three options (below that there is nothing to narrow): the
filter matches on display name, ↑/↓ move the highlight, and Enter takes the highlighted option —
the top match by default. An Enter that matches nothing is swallowed rather than closing, because
closing would throw away a search halfway through being fixed. It answers Enter and the arrows
itself and says so with `preventDefault()`, which is how the shared wrapper knows to stand aside.

**One column's editor for a selection that spans several is not a narrowing.** `commitToCells` fans
a commit across every selected column and drops it from the ones whose shape it does not fit — so a
Dimmer + Colour marquee opens the dimmer's slider, and moving it sets the dimmers and leaves the
colours alone, exactly as `127` did. `orderedSelectedCells` picks the order — topmost displayed
row, leftmost visible column — the display-order rule a released drag's auto-open once shared,
extended to the column axis — and the container takes the first cell that **has an editor**.
That second half is load-bearing: a marquee is geometric (`hitsFor` sweeps a rectangle
over rows and column bands), so it covers Colour cells on dimmer-only pars, and taking the
display-first cell flatly would leave Enter doing nothing on an ordinary mixed selection.

**`cellKeyboardPermission` in `cellEntry.ts` is the scope gate**, the fourth place "read-only" is
said (§The programmer's scoped grid): both keys in Local, neither in Output, entry only into a
focused Look layer (its draft has no removal), neither on a focused palette layer. The container
reports `cellEntryKey` / `cellClearKey` false where a key is refused, and the grid's hints read
those rather than restating the rule — so no hint can advertise a key that does nothing. The window
handler only names a cell, and **not from a focused control** (a button, a link, a menu or menu
item) on any arm: a cell trigger is tabbable and Tab-then-Enter opening its popover is a path the
grid already promises, and Backspace is the destructive arm. The first exemption is a cell trigger
the marquee itself covers (`marqueeOwnsKeyTarget`), or Enter there would fall through to that
button's own activation and open *that* cell's editor with nothing focused. The second is **a control
outside the rows that a click left focused** (`clickedForeignControl`, over `sheet/pointerFocus.ts`):
a toolbar button pressed with the mouse keeps the focus, and Radix hands it back to a menu trigger
when the menu closes, so without it Enter toggled that menu instead of opening the cell's editor. A
control reached by Tab keeps its own keys, and so does one inside a row — another row's pencil or
grip keeps its own Enter. The tracker is the focus-visible rule: a `pointerdown` arms it, any
`keydown` disarms it, each `focusin` records which; a press on the element already focused records
it, a `focusin` on the one already recorded keeps it (the window's refocus after ⌘Tab), and
`main.tsx` attaches it at boot so the click that navigated to a grid is seen. It never counts a
control a modal hides (Radix's `aria-hidden` around an open modal, where a sheet that keeps focus on
its door button must not let the grid behind take ⌫), nor one marked `data-own-keys` — the
`ShowBar`'s GO and BACK and the lock, whose Enter and `L` stay theirs after a click (Chris's call).
The kit's cell listener is capture-phase, so it takes only unshifted keys from a clicked control and
leaves a shifted one to it — it runs before the full-screen toggle's `document` capture listener,
and ⇧F must still reach that. The programmer's is bubble, so it takes unshifted keys from a clicked
control in a capture listener of its own, because a Radix menu trigger toggles on Enter from its own
keydown before a window bubble listener runs, and leaves shifted ones to the bubble listener, so ⇧F
reaches the full-screen toggle first and ⇧G still seeds the type-ahead. **The row keys follow the same rule** (`pointerLeftOnForeignControl`, heard in a
capture listener on both surfaces, since a menu or Select trigger opens on ↓ from its own keydown):
⌘A from any clicked control outside the rows, and an arrow from one that does not answer that arrow
itself. A slider or spin button answers all four; a tab list the arrows along its
`aria-orientation`, horizontal unless it says otherwise, so ↑ / ↓ after a click on the Values tab
step the grid; a radio group or toolbar (Radix's single and multiple `ToggleGroup`) the arrows along
its orientation, and all four when it names none, as Radix's roving focus does. The programmer's
→/← tree check, which also stands aside from a control inside a row, exempts a clicked one in the
anchor row (its name); another row's clicked chevron or Info button keeps its own keys.

**Backspace / Delete takes the selected cells out of Local** (`CellWriters.clearValue` →
`programmer.clearEntry`, by the programmer fade — the same store the action bar's Clear fades by)
— **and stops the local FX on them**. That second half is not a new gesture: the programmer's
whole-desk Clear has always swept values and programmer-band FX together
(`clearProgrammerCompletely` in lighting7), and three cells under an FX palette's press are
three instances that were otherwise removable only one at a time on the FX table.
`components/fixtures-list/cellFx.ts` owns the rule and `useClearCellFx.ts` the wiring;
two clauses keep the key inside the rectangle. **Local means unowned** — `programmerOwned` *and*
no `lookId` / `paletteId` / `programmerLayerId` / `cueId`, since an FX that came out of a
Look, a palette layer or a cue is that thing's, and stopping it here would be undone by the next
recook or would quietly edit a library record. And **every head, or none**: an FX drives
whatever its target names (for a group, every member) and there is no "stop it on these heads
only", so a partly-covered one is left running and toasts that it was, rather than half vanishing
from the rig. Matching is on `fixtureKey|attribute` (`cellFxKey`), the same pairing the
grid's FX lens places an FX on a cell by, and the pairs are **collected by the clear loop itself** rather than
re-derived, so the values half and the FX half cannot reach different heads.

The request itself is a **one-shot** on both halves: the container drops it on the commit after it
is set, and `FixturesTable` folds it into `autoOpenCell`, so there is one mechanism for "open that
editor with no click" — Enter and the bar's Set are the same request. A request left standing
re-opens the editor the next time the virtualiser renders the row it names.

### ⌘K on the programmer

**On the programmer route ⌘K lists two groups first, *Programmer* and *Selection*** (programmer-toolbar
plan D13): row A's verbs, the source box's Update and Revert, the fade, the tables and the scope; then
row C's — Set, *Apply a palette…*, *Add FX on the selection…*, Fan, both Releases, the subsets, the
mask, the rig verbs, the follow toggle and Deselect. Every item names what it would act on (*6 heads ·
Colour*, *over 1s*) and is **missing, not disabled,** when its control would be refused. *Add a Look…*
and *Apply a palette…* open sub-pages, ⏎ a press as a layer and ⌥⏎ or an ⌥-click *Apply as values*.

**The palette runs nothing itself.** It is mounted in `Layout`, outside the programmer's marquee,
scope and sheets, so each component that owns a command registers a **door** while it is mounted
(`programmer/commandDoors.ts`, `useCommandDoor`): its commands, each with its label, hint, whether it
can act and the handler its own control runs. ⌘K reads every door **once, as it opens**
(`readProgrammerCommands`), so `Layout` subscribes to nothing at selection rate, and a door's offer
reads its owner's latest render through a stable callback, never re-registering. The owners are row A
(`ProgrammerActionBar`, `AddLookAndInclude`), the source box, `ProgrammerTables`, the scope band, the
programmer's list (`selectionCommandDoors.ts`, from `FixturesListContainer`'s programmer arm) and three
renderless doors the tables mount: `ProgrammerAddFxDoor`, and `ProgrammerLookDoor` and
`ProgrammerPaletteDoor` (`ProgrammerPressDoors.tsx`), which press through `usePress` exactly as + Look
and the picker do — *Apply a palette…* offering the picker's set through the same `paletteFits` and
`splitByMask`. A new command goes on the door of whatever owns its control, in
`COMMAND_DOORS`' order; Set and Fan bring the Values table back first, since the grid is hidden under
the others, and Fan opens through an `{open: true}` seed (`SpreadSeed`) so its refusal is the trigger's.

### The programmer's undo

**Undo and Redo lead row A, ahead of Clear** (`components/programmer/ProgrammerUndoRedo.tsx`, undo
plan session 1, §4.3), programmer-only and at the programmer fade. They read `history.state`
(`api/historyApi.ts`, form 3 in `store/history.ts`) and write `POST projects/{id}/history/undo|redo`;
a step belongs to whoever's gesture made it but is anyone's to take, since there is one programmer.
Undo carries the step it takes in muted text where the row has words (`Undo · Clear`); a right-click
or a hold (`useLongPress`, so the release does not also undo) lists the last 12 steps, and picking one
goes back to before it. **Redo folds into that menu below `@[800px]`**, where every control on the
row is its icon, so the pair costs the iconic row one 32px button (40px with its gap); measured at
852×393, 945×457, 1024×457 and 612×457, rows A and B break where they did without it.

**Clear's toast carries Undo** naming the Clear's own step (`awaitHistoryStep`: the newest step after
the revision Clear was pressed at, labelled `Clear`), so it undoes the Clear and whatever came after
it, never whatever happens to be newest when it is clicked.

⌘Z and ⇧⌘Z are global now, and take the newer of the programmer's top step and your newest show
change (§Undo); the programmer view has no keys of its own.

### Undo

**One key, newest first, across the programmer and the show** (undo plan D1, D2, session 6;
`components/history/`). ⌘Z and ⇧⌘Z (Ctrl on Windows and Linux; `undoKeys.ts`) are on `window`,
registered once by `HistoryHost` in `Layout`, and stand aside in a text field, a contenteditable, an
open dialog or alert dialog — **but not a sheet** (`[data-slot="sheet-content"]`), so the History sheet
and an editor in a sheet take the key — and for a key another handler has `preventDefault()`ed. They
take what `history.state`'s `undo` / `redo` name: the newer of the live programmer's top step and this
window's source's newest show entry. The cue editor's own ⌘Z is gone.

**Every door goes through `useHistoryMove`** (`HistoryMove.tsx`): the header's joined pair after
`ProgrammerIndicator` (`HeaderUndoRedo`, its tooltip naming what it takes and its key, violet for a
programmer step; folded into the user menu below `sm`, which is the phone's Undo · Redo · History…),
⌘K's *History* group (*Undo · …*, *Redo · …*, *Show history*), the History sheet's head and rows, and
the toasts. One move is one `POST …/history/undo|redo` (`historyMove`, silent in `errorToastMiddleware`)
run through `runHistoryMove` (`undoFlow.ts`, pure): the **lock** first — a show entry on the live
project while the show runs and the show-editing lock is on asks *Unlock and undo* — then the desk's
409s, each asked once in `HistoryDialogs` (mounted once in `Layout`): `HISTORY_CONFLICT` lists the later
changes and resends with `backToHere` (*Undo n changes*; a redo has no back-to-here and is refused),
`HISTORY_CONFIRM` names what moves and resends with `confirm` (*Undo on stage*, amber). Anything else is
one error toast in the desk's words. A move that lands toasts what came back (*Undone · Delete cue
Q12*) with the way back (Redo, or Undo after a redo). A programmer step goes back through the deepest
of the moved steps on the other stack; entries one at a time.

**A toast's Undo names its change by request** (D11). The base query hands every answer's
`X-History-Entry` to the trackers listening for that endpoint (`trackHistoryWrites([endpoints])`,
`store/restApi.ts`) — named by endpoint, so a cue editor's flush or another sheet's write landing
meanwhile is not taken back with the gesture; a gesture starts one, stops it when its write lands, and `entryUndoAction(projectId, requestIds)` is the toast's
action — it reads the history page, finds the entries listing those request ids (`entriesForRequests`)
and undoes them newest first through the mounted host (`undoHistoryRequests` in `historyActions.ts`,
which needs no hooks, so a store module can call it), announced as one toast whose Redo brings them
all back. It so undoes the right change whatever happened since; an entry
another write folded into within 1.5 s (D8) comes back with it. The toasts carrying it: a library
batch delete (`useBatchDelete`'s `history`: Looks, palettes, scripts, speed masters — one toast once the
batch is done, both passes in it), the cue delete, Record, Record look and Update (`announceWrite`'s
action), and the hand's bank, cue-slot and cue-layer places (`HandPlacement.history`). Clear's stays the programmer step's. The cue
editor's *values saved* Undo and the prompt book's *Undo move* undo their own write's entry the same
way, keeping their words. A show entry's move refetches the record caches (`SHOW_RECORD_TAGS`, which
the cue editor's `Cue` reads need) and tells `onHistoryMoved` listeners: the cue editor's store drops
its saved draft rows and its Undo there, since the undo may have put the cue back from under them.

**The History sheet acts** (§4.2): its head carries Undo and Redo; a row's hover button is *Undo back
to here* on your own show row and a programmer step (programmer-only, through it), *Undo {name}'s
change* on anyone else's, *Redo* on an undone one (`rowActionLabel`). No door sends `backToHere` up
front: an entry that collides with nothing is undone alone (D3), and one that does asks first — on
your own entry *Undo back to here?*, saying it takes every later change of yours too, which is what
the desk's back-to-here does there; on anyone else's *Undo n changes?*. An operator's
show rows on a venue are read-only. An expanded record links to its page by the row id the desk now
sends (`historyRecordHref`; a removed one links nowhere).

`undoFlow.test.ts` holds each 409's flow and the lock, `undoKeys.test.ts` the key's skips,
`historyEntryHeader.test.ts` the toast's entry from the header, `HistorySheet.test.tsx` the row actions
and links, and `hand.test.ts` a place undone by entry.

### One selection, two shapes

**Rows and cells are one selection**, and either clears the other — on every sheet; `useSheet`
is the kit's copy of the rule for the three surfaces whose row selection is local (§Sheet kit). They were two independent states
— a row selection with checkboxes that Record scoped on, and a cell marquee drawn over it as a
transient edit scope — and an operator had to hold both in their head to know what the next gesture
reached. Now `FixturesListContainer` derives one `selectedRowIds` (the cells' rows under a marquee,
the row selection otherwise) and every consumer reads it: Record's published targets, Locate and
Highlight, the desk-selection bridge, row C's heads chip and the footer count. A marquee *is* the
fixture selection, narrowed to some of their attributes. Enforced at the doors, not by an effect:
the cell door (`selectCells`) clears the rows only when a hit arrives and there are rows to clear,
and every row door (`selectRow`, `selectAllRows`, `setRows`) clears the cells, whose `clear` bails
when there are none. `cellRowIds` is identity-stable while its *members* are unchanged, or a drag
would republish to the desk on every pointer move rather than at each row boundary.

**The desk selection is one fact with three parts — targets, `families`, `source` — and a marquee
publishes two of them** (multi-screen plan D2, D3, D7; backend af3575a). `selection.state` is
`{targets, families?, source?}`, both new fields *omitted* when absent, never null: absent
`families` is every attribute, absent `source` is nobody since the last clear. The rule that keeps
the mask part of the selection rather than a second fact: `set` replaces the whole fact (no
families = clear the mask), `toggle` keeps it, `clear` drops both. So `useDeskSelectionBridge`
publishes `cellFamilies(cells)` beside the rows — `FixturesListContainer` hands it the cells — and a
row selection with no cells publishes *no* mask, which is a `set` that clears the desk's. The
publish is keyed on the mask's **key** (`familiesKey`, `lib/selectionMask.ts`), not on `cells`,
because a drag mints a fresh `cells` array per frame; `normaliseFamilies` is the one spelling —
none and all four are both `null`, declaration order — so an echo compares equal to what was sent.
**The echo FIFO's key is targets *and* families**: a frame with the same heads and a different mask
is exactly what a second window changing the mask under a standing marquee produces, and a key of
the heads alone swallowed it as our own echo. It is applied as a **row** selection — this list
cannot draw a mask it did not make as a marquee, so the marquee is dropped to its rows. A frame the
FIFO *does* treat as an echo still lands its `source` in the cache, because the cache is
`store/selection.ts`'s and is written before the bridge's effect runs (`decodeSelectionState` keeps
the untouched parts' identities, so a source-only frame moves the chip and nothing else).
`source` is stamped by the desk from the window this socket **announced**, never sent: the
`windows.announce` every tab sends on connect and on every change (§Windows, full screen and the
hand) carries its name, and the desk stamps `source` with that name **and the socket-minted row
id** (lighting7 d774fd9). No selection write names this window any more — session 1's `sourceName`
on `selection.set` / `selection.toggle` is gone from this side, and the desk's fallback arm for it is
`FU-WINDOWS-RETIRE-SOURCENAME`, ready to delete.

**Nothing on this side draws `source` any more** (busk-chrome plan D18). The desk still stamps it
— the mover's *row* id, which a tab never sees except in its own registry row, and the name — and
`decodeSelectionState` still carries it into the cache; `deskReading`, which resolved *my* row as
the one whose `windowId` is this tab's (`thisWindowRow`, first match) and read *Desk · from <name>*
for another row's id, went with the following chip. `thisWindowRow` stays the Screens sheet's, for
its *this window* badge, and the caveat it carried stays with it: a duplicated tab copies its
`sessionStorage`, so two rows can share one `windowId` and cannot be told apart from this side.
Cosmetic, accepted by D9, and left; `FU-WINDOWS-OWN-ROW-ID` is the exact fix.

**Follow / local is a per-tab `sessionStorage` fact, default on** (`lib/deskFollow.ts`, D8), and it
gates **both** directions of the bridge, since one bridge is both. `localStorage` is one value per
origin per profile, and the two desk screens are two windows of one profile — `createSyncStore`
took a `storage` parameter for exactly this. Unlinking snapshots the desk's targets *and* families
into the tab's copy and leaves the desk's alone; re-linking adopts the desk's and publishes nothing
(the bridge treats re-enable as a fresh mount, for the mount rule's reason: a window joining must
not clear what another screen has selected). On the programmer the local selection *is* the list's
own row selection — an unlinked window records and presses what it shows; on the busk view it is
the copy in `deskFollow.ts`, read through `useSelectionPair`.

**A window's own selection is offered only where it can both select and act on it** (desk-follow
plan D1, D2): busk **Split** and the **Programmer**. Rig focus selects and has no pads — it exists
to be the selector for another screen's pads, even with the sheet's Colour or Fan tab open —
and Pads focus presses and has no tiles, so a local selection there would be a frozen snapshot
the operator could neither see nor change from that screen. So **Rig and Pads always follow**, and
the effective rule is `follows || followIsForced(view, focus)` on the busk view and the stored
flag alone on the Programmer. `followIsForced` in `lib/deskFollow.ts` is the one statement of it,
read by all four places that need it and restated by none: `BuskingView`'s relink, the
`windows.follow` refusal in `useWindowsBridge`, the Screens row's Selection segment and ⌘K. It
takes the window's **stored** busk focus, never the Split that edit mode or an empty project forces
for their duration — the focus is what the window returns to, and what other windows see
announced.

**Entering Rig or Pads while local drops the local selection and says so** (D3): `BuskingView`
relinks (`relinkToDesk` — adopts the desk's, publishes nothing) and toasts *<Window> follows the
desk selection again* over *<Focus> focus presses onto the desk's selection. Your own was
dropped.* The effect is keyed on the focus and the flag, **never on a mount gate**, because the
focus moves through five doors — the Focus control, the Screens row, ⌘K, a MIDI `BuskFocusSet`,
`?focus=` on arrival — three of them from elsewhere, and a window that *arrives* in Pads while local
(a reload over stale `sessionStorage`, an unlinked Programmer window navigated here) must relink the
same way; the flag in the key makes it a backstop against anything that ever unlinks a forced
window. Holding the selection dormant until Split was declined: it needs a chip, in a focus that
has none, to explain a selection nothing is using.

**The ways out of following are the badge, the Screens row and ⌘K** (D4, D11). The badge's own
press is the primary one (§The rig); the other two set a window from elsewhere. The Screens sheet draws a
*Selection · Desk | This window* segment on every Busk and Programmer row (`viewHasOwnSelection`,
the one list of those two views, which ⌘K's arm reads too), from `row.follows`, and writes the
fifth windows command, `windows.follow` (§Windows, full screen and the hand); on a busk
row in Rig or Pads it is **disabled with its reason** (*Pads focus follows*), never hidden. ⌘K
offers *<Window> · own selection* / *<Window> · follow the desk selection* for every other Busk or
Programmer window, flipping with that row's flag and withholding the unlink for a forced row, and
*Stop following the desk selection in this window* is withheld in Rig and Pads. There is no MIDI
target (`FU-SURFACE-SELECTION-FOLLOW-SET`). The page has the same two doors and its own flag
(§The busk layout): its badge's press, the row's *Page* segment and ⌘K's page pair.

**The desk chip always says which selection this window is on** (D8, revisiting busk-chrome D18):
following, it is **`LinkBadge`** (`components/desk/LinkBadge.tsx`) — a 20px link glyph, glyph only
at every width, accessible name *Following the desk selection*, whose press unlinks — a mark
instead, reason on the hover, in Rig and Pads, which always follow (D11) — `shrink-0`; unlinked,
the dashed `This window`, whose click follows the desk again. The
four readings the following chip once carried (`Desk`, `Desk · from <name>`, `Desk · from the
desk`) stay gone with `deskReading`: an operator at a two-screen desk knows which screen they are
selecting from. It sits on the busk rig row beside the family pill in Split and Rig, on the pad
row in Pads, on the compact rig strip and the short board's merged row, and nowhere else — the plain
lists never bridge (D1), so a mark there would name a link that does not exist. **On the
programmer's row C the link is inside the heads chip** (programmer-toolbar plan D5, revising D11 for
that row alone): the chip's glyph is the badge while following or a dashed mark while not, it is
not a press, and the toggle is the chip menu's *Follow the desk selection* item (§The programmer's
scoped grid). **The badge must never move a control**, so every row it sits on was re-measured for
it (§The rig): the rig and pad rows' rungs moved by its 29px. **On the busk band the chip takes
`showSubject` and reads `Targets: This window`**, because there it has a sibling — the page mark
(§The busk layout) — and two bare chips a row apart would be worse than either alone. The pill itself is `components/desk/FollowPill.tsx`, shared
by both chips so they cannot drift apart visually while their flags stay entirely separate; its
parts fold under the host's classes with the whole reading as `aria-label` (§The rig).

**The checkbox column is gone, and a drag from the name column selects rows** — the same
`useCellMarquee`, which decides at the press which side of the first value column it landed on and
never changes its mind mid-gesture (a rectangle dragged from the name column into the values is
still a row marquee, the way a spreadsheet's row-header drag is). A plain drag replaces; a ⌘-drag
unions onto the selection it *began* over, so shrinking the rectangle un-selects, which the cell
marquee's per-frame accumulation does not do. The list hands the whole id list to `onRowMarquee`
only when it changed, and the release's click-swallow is load-bearing twice over there: the name
cell's own `onClick` would otherwise select the one row under the release. Enabled on all three
lists, since the checkbox went from all three: without it the two plain routes would have had no
way to accumulate a selection by touch. The keyboard path is window-level and deliberately has no
per-row control: ⌘A, ↑/↓ with Shift extending, and **→/← open and close the anchor row** — over a
row selection; over a cell marquee every arrow moves the marquee (§Sheet kit) — a group
over its members, a multi-head fixture over its elements — with ← on a member or element climbing
to its parent first, the ARIA tree convention (`treeKeyAction` in `rowModel.ts` is the rule); ↑/↓
step by the kit's `arrowStepTarget`, which every sheet on the kit steps by too (§Sheet kit).

**What a list has open is per window, per list, per project** (programmer-fixes plan D9,
`lib/listExpansion.ts`): one `sessionStorage` entry per project and selection scope, group names and
fixture keys, so a row opened on the programmer is open on the way back to it, a show's same-named
group never opens another show's, and a name the loaded rig lacks is dropped on the next write.
A deep link adds to it. **A landing does not** (venue-rig-fixes plan D14, `fixtures-list/landing.ts`):
Include's and the heads chip's (`landTargets`) fold the keys first — a multi-head fixture all of whose
heads came in is selected as its own row, collapsed (`foldLandingKeys`) — and what a landing opens,
a fixture only some of whose heads came in and in the grouped view a fixture's group, is held by
`useLandingOpenings` over the stored set while the list stays mounted, each landing replacing the
last's. The chevrons, Expand all and Collapse all are the operator's: a row they close is closed
whichever opened it, a row they open is stored, and a landing's row they leave open stays the
landing's — but Expand all, a ⌥-click level expand and a deep link's group store what they mean to
open, a landing's rows among them. The Include landing selects on the commit after its openings, as
`landTargets` does, because the row door keeps only rows that are shown. **Expand all · Collapse all**
(`ExpandAllButton`, the toolbar's `expand` part) opens every group and multi-head fixture the filter
shows — a closed group's members included — and reads *Collapse all* once none is closed; a collapse
leaves open a fixture whose element rows hold a selected row or cell, and a group whose member rows
or their elements do, so it never empties the selection (`fixtures-list/expandAll.ts`). **⌥-click on
a chevron** does the same over that row's level alone. The cue editor's Values view has the button
and the ⌥-click over its own state, which is not stored. On the programmer's row B the button is its
glyph alone, from 640px of row and never on the folded short-height row: that row's widths are
measured without it.

**The grid's rows are `select-none`, and a fixture name is no longer selectable text.** Three
declarations ride together on the rows wrapper — `select-none`, `touch-manipulation` and
`[-webkit-touch-callout:none]` — each for a browser default a marquee was losing to
(`PD-MARQUEE-TOUCH`), and they are unconditional on all three lists. The two plain ones carried a
narrower arm while they had the row marquee alone: text selection was refused only *while* a row
drag was live, so a fixture or group name could be selected and copied straight out of the list,
which their comment named as deliberate. A cell marquee costs them that, and it is a real loss on
two everyday browsing views rather than a tidy-up — the name is still copyable from the detail
sheet, which is weaker. Recorded rather than argued: if it turns out to matter, the fix is to
exempt the name cell's own text, not to put the narrow arm back, because that arm leans on `arm()`
clearing whatever the browser began selecting in the five pixels before the threshold.

**The plain lists' cell verbs are Set · Clear · Fan** (`CellSelectionActions`), drawn before
Locate and Highlight when the selection is cells; the programmer's row C is Set · Fan · FX · Release ▾,
Release's cell arm doing Clear's job (§"The programmer's scoped grid"). Set and Clear are Enter and Backspace with a
button on them, and take the container's gate (`cellKeyboardPermission`) and words
(`cellActionCopy`) so a button cannot promise a gesture the keyboard refuses; Output and a focused
palette layer show them disabled with the reason, and Fan makes the same two refusals itself
(it is also drawn on the two plain routes, which have no scope). There is **one** Set —
its title names where the value lands (Local, or the focused Look's rows), and that is the scope's
answer rather than the press's. A "track it" arm the way a palette chip has (its tap) makes no
sense on a value: a typed number has no referent for a layer to follow, and *Make layer* on the
Stack table's *Your values* is how local literals become something trackable afterwards. Set and Clear keep their icons
at every width and only Fan folds on the phone arm, because on a phone Set is the only way into
a selection's editor now that a drag opens nothing.

**Fan reads the marquee, not the fixture selection**, and opens in `EditorSurface` like the four
cell editors. The column comes from the selection — `SpreadPopover` takes one `SpreadColumn` per
selected column, targets in visible row order, from the same `columnTargets` expansion
`commitToCells`, Backspace and the batch count use — and answers the panel's family from it; a
chooser is drawn only when the selection spans a desk-resolved plan and the raw Speed one. Focus
follows: with one column the first question is From and it is focused on open; with several it is
still *which*, so nothing is. Enter still *applies*, since Fan is the one panel here that does
not write as it is edited until Live is on. Every list *also* offers the whole-selection row Fan
(`spreadColumnsForTargets`), drawn in the toolbar's `actions` slot with nothing selected but rows
(on the programmer the one Fan button takes it), and the cell verbs replace it the moment a cell is. The colour editor's **Fan…** is wired here too
(editor-kit session 3): `ColourCell` hands its RGB to the container as a one-shot the shape
`keyboardOpen` uses (`SpreadSeed` — RGB only, no emitter travels, the busk hand-over's rule), the
container threads it to row C's `SpreadPopover` as `seed`, and the panel opens on Colour with
*From* seeded and asks for the seed to be dropped.

### The programmer's scoped grid

Session 2a gave the programmer's value grid a **scope**, and it is the mechanism most of the session
rests on: Output (the cook, read-only), Local (what *you* set, and nothing else), or one focused Look
layer. Same grid, same cell editors, same drag-select in all three — that sameness is the point,
because it is what makes editing a Look feel local rather than like a trip to the library.
`components/programmer/ProgrammerScope.tsx` owns it; the band above the grid is
`ProgrammerScopeBand`, on the Values table's row B after the table tabs, and a layer is focused by
⏎, a double click or *Edit in grid* on the Stack table, which switches to Values (§The programmer's
tables).

**The programmer's chrome is one spacing system**, and every number in it is stated once here so a
row cannot drift from its neighbours (design record: `../docs/plans/programmer-chrome-design/`,
whose `INDEX.md` has the reasoning). A 12px gutter on every row, the `ShowHeader` included — and
that header is shared, so Show, the Prompt Book and Busk take it too, and on those three it differs
from the `ShowBar`'s own `@[440px]:px-4` by 4px, deliberately. Every chrome row is 40px and holds
32px controls, so the inset is 4px everywhere: row A, row B (`h-10`, `min-h-10` when folded), row
C (`SelectionBar`), all level. Three control tiers by
nesting, and nothing else: 32 for a control on a row (`h-8` / `size="sm"`), 28 for a control inside
a control (`h-7`: Update and Revert in the source box, a palette chip, `New`), 24 for a toggle
item, 20 for a pill. The header is 40px (`CHROME_ROW_CLASS`, `h-10 px-3`) at every height, not only
under 500 — it was 48 (`py-2`) until the busk-chrome plan's session B made it the shell's chrome row
too (D12). 8px
between controls on a row (`gap-2`), 6px inside a control. Row B's filter is a field from 600px of
row B up (360 until the table tabs led the row — §The programmer's tables), says `Filter…` (`FIXTURE_FILTER_PLACEHOLDER_SHORT` — a placeholder cannot switch by
container query, and the two plain list routes keep the long form) with the whole hint on its
`title` and `aria-label`, and takes the row's slack before the spacer does (`flex-[999_1_0%]`
against the spacer's `flex-1`; the folded arm keeps plain `flex-1`, since there the spacer is
hidden). The key button is not drawn in layer scope — ownership tints are off there, so there is
nothing to explain — and `ScopedKeyPopover` holds the ownership key only. Two phone-arm folds
below `@[600px]`: the source box becomes `Q4 · Update · Revert` with the name and change count
hidden and the dirty state an amber dot on Update (count on its tooltip; a Look keeps its families
badge, being its only name there), and the fade trigger keeps its value and drops only its chevron
(86 → 48px); in layer scope the layer pill is capped at 120 and `Unsaved` / `Saving…` fold to a dot
on it with the word `sr-only`, while `Save failed` keeps its word at every width. The three
measured floors in `ProgrammerGrid` (`min-w-[min(410px,100%)]`, `@max-[739px]`, `@[840px]`) were
derived from a 285px iconic action bar and now carry ~38px of slack on the phone; move all three
together against 852×393 and 945×457, or none of them.

Things that will bite:

- **The grid must never remount on a scope change.** `useListSelection` clears its Redux scope on
  unmount, so a conditional mount or a `key` per scope silently discards the fixture selection
  Record scopes on. `ProgrammerPage.test.tsx` asserts `gridMounts` across a switch; that is the
  load-bearing test of the whole session.
- **A scope switch drops the marquee to its rows, it does not clear the selection.** The cells are
  scope-local and must go, but since the two selections became one (§One selection, two shapes) the
  rows had been cleared when the cells were selected — so clearing the cells outright emptied
  `selectedRowIds`, the desk bridge published `set([])`, and every other screen's target band and
  family pill went dark. `FixturesListContainer` converts instead, through the same row door every
  other row gesture uses, so the heads survive everywhere and only the mask is dropped: the reading
  the bridge already gives a mask another window made. **Never publish an empty selection the
  operator did not make.** Two things ride on that conversion. It **closes any open cell editor**,
  in a second effect and explicitly — the old clear did it by accident, by taking `selectionEmpty`
  across its false→true edge, and rows that survive keep that flag false; an open popover's fields
  are not disabled by a read-only scope (only the cell's trigger is) and `useCellWriters` has no
  Output arm, so a commit from a stale panel lands literals in Local. And the **conversion effect
  must stay declared above `useDeskSelectionBridge`**: effects run in hook order, and that is what
  makes a scope switch and a `selection.state` frame arriving together resolve with the desk
  winning.
- **`null` scope is not Output.** `/fixtures` and `/groups` mount the same table with no scope above
  them and must behave exactly as before — live values, editable cells, no em-dashes. Only an
  *explicit* Output scope is read-only. Pinned in `FixturesTable.test.tsx`.
- **`ChannelSource` is the wrong abstraction here** and was rejected: everything a derived source
  doesn't hold reads 0, and `holds()` is on `DerivedChannelSource` rather than `ChannelSource`, so it
  cannot express *unset* — which is the entire point of Local. A Look row can also name a group,
  which has no channel. `scopedCellValue.ts` instead feeds the *same* `aggregateCellValue` a lookup
  built from entries or rows, so the maths behind a cell is identical in every scope.
- **The Local predicate is `entry.owner !== 'layers'`**, not provenance: under blind, provenance
  reports what is *underneath* the programmer, and a parked attribute reports `PARKED` while still
  holding the operator's entry that Record would take.
- **An un-busked Local cell shows an em-dash but its editor opens at the live value** (`placeholder`
  on the four cells, `UnsetCellMark`). Local has to answer "what will Record take?" by itself, and a
  busk still has to start from where the rig is.
- **A layer-scope edit is a live write.** It goes through `PUT /looks/{id}`, which republishes every
  cue layering that Look — the point of composing in place. `LookRowDraft` coalesces at 400 ms with
  a 2 s ceiling, and **flush cadence is stage-update cadence**: a colour drag steps the rig rather
  than gliding, which the band says out loud. There is no smooth-preview escape hatch — backend
  sweep item D4 deleted the Look preview routes and `ProgrammerLayerStack.installPreview` with
  them, so no layer can carry an unsaved draft any more and `ProgrammerLayer` has no `isPreview`.
- **`RowCell.targetKeys` is index-parallel to `resolutions`** and `keys` is not: one resolution can
  contribute two keys (a position paired from pan/tilt sliders), so for a group row the two arrays
  share neither length nor indices. Ownership never noticed because it collapses to one verdict.
- **Widening a layer's targets is always explicit** — the `AddToTargetsButton` on the row, never a
  side effect of dragging a marquee across the grid.
- **A focused palette layer is a read, never an edit**, and the two kinds read differently. A
  **value** palette shows no rows at all: projecting its generic row onto every targeted row would
  silently convert it to a per-fixture one on the first edit, which is a change to what the palette
  *is* made by someone adjusting a value. An **FX** palette shows the **live** value on the
  cells it drives — ringed by `layerCellClass`, with the wave and the division in `FixturesTable`'s
  bottom-right corner slot (free there, because ownership is switched off in layer scope) — since an
  FX is one rule for every head and what is worth watching is what it is producing now. Per-element
  Look rows stay out too; all three cases are named in `LayerRowNotices` (`FU-LOOK-ELEMENT-ROWS` for
  the last).

  `LookRowStore` engages **only for a LOOK layer**, so a palette layer's answer comes from its
  sibling `FocusedPaletteLayer` — one context above the grid, read by `useScopedRowValues`,
  `LayerRowNotices`, `ProgrammerScopeBand`, `AddToTargetsButton` and `SpreadPopover`, because that
  hook runs per row and a query in it would be a subscription per visible row. Until that arm
  existed the layer scope fell through to *no* states, which the grid renders as live editable cells
  writing straight to Local while the band overhead says "One layer" — the notice had been claiming
  the opposite since it shipped.

  **"Read-only" has to be said in four places, not one.** `CellState.editable` reaches only the
  pointer (`pointer-events-none` on the wrapper); the cell trigger stays tabbable, so `AttributeCell`
  takes `disabled` from it too; `SpreadPopover` — which writes through the desk's fan route or
  `useCellWriters` from the toolbar, nowhere near a cell — gates on the focused palette as well; and the marquee's
  **keyboard** (the Enter/character arm of the grid's window handler, §The programmer's keyboard)
  gates on `cellKeyboardPermission`,
  because the marquee itself arms in every scope — its `pointerdown` sits on the rows wrapper and a
  read-only cell's `pointer-events-none` only retargets the press there. A commit through any hole
  is not dropped: `useCellWriters` has no arm for a palette layer, so it falls through to a **live**
  write and puts literals in Local. The Fan gate is on the *palette* case only, not on layer
  scope generally: a focused Look layer has a row draft and the fan lands in it through the
  route's `write: false` arm (§Sheet kit) — and the
  keyboard makes the same split, taking a typed value into a Look layer's draft and refusing it on
  a palette layer and in Output.

  An untargeted row in a palette layer is painted dashed like any other, so `AddToTargetsButton`
  reads whichever layer context is live — it is the only way a layer widens, and a tone with no way
  out of it is worse than no tone.

  `+ FX` is disabled on a focused palette — not D7 any more, but because a palette holds
  **one** thing, chosen when it was made: there is no second FX to add, and adding a first
  to a value palette would flip its identity.
- **In Output scope every tint is a destination**: clicking a cell jumps the scope to whatever won
  it. Three guards, and the middle one bites — `ProvenanceEntry.layerId` is present for a **cue's**
  layers too, so `focusLayer` checks membership in the programmer's own stack and reports failure.

**The grid says *held back*** (fixture-fx-sheets plan D19, session 5), and since tables-colours
session 2 it says it with the **FX lens** (§The programmer's tables): a cell an FX paints carries
a chip with the division, struck through with the reason where the FX is held back. The chip
takes the top-right corner's two older marks over — the **amber corner dot** (`lib/heldBack.ts`'s
rule, `heldBackReach` from the FX list through `useHeldBackReach`, `cellHoldsBack` per cell) and
the **FX badge** (provenance naming an FX) — which stay as the lens's fallback only where no
indexed FX paints the cell (an FX under a sibling key, which provenance can still name). Both
are absolutely placed inside the ownership ring, so they never move the value; the cell's hover adds
*holds an FX back*. **The cell editor's label line carries the source chip** (`CellSourceChip`,
the sheet's `SourceChip` over the cell's keys, `EditorLabelLine`'s `chip`): on the right, after the
column, in the popover form, and as a row of its own under the title in both sheet forms. It names
what drives the cell — *2 of 4* where the heads differ — carries the dot, and opens the attribute's
stack (`LayerStack`, which takes the cue names and the connection as props outside a sheet), whose
`programmer.keyStack` answer is the desk's own word on a stomp. It is mounted inside the editor, so
it subscribes only while one is open, and only where ownership is drawn — Local; Output's cells
take no edit (a click still jumps to the owner) and a focused Look layer's draft has no stack (§8's
follow-up).

**Row B's *Lit* shows only the fixtures emitting light** (`useLitFixtureKeys`, per browser as
`fixturesList.onlyLit`): a fixture is lit when its master dimmer, if it has one, and at least one head
dimmer, if it has any, are above 0, read from its **dimmer channels only**, so a dimmerless head is
never lit. It holds still while something is open over the grid (programmer-fixes plan D21,
`lib/litHold.ts`): a cell editor (`useEditorOpen`), row C's FX picker and a marquee drag each take a
hold, and while any is held the set is the one it had when the first was taken, catching up when the
last goes. So a dimmer FX or fade through 0 on a selected head does not take its row, row C and an
open FX picker away mid-gesture. Outside a hold a selected row that goes dark leaves the list, and a
typed filter still disarms row C whatever is held.

**Row C is three clusters** (programmer-toolbar plan D3, `programmer/SelectionBar.tsx`), left to
right, over the one selection:

- **Which heads** — the **heads chip** (`HeadsChip.tsx`, D4, D5) and the mask. The chip counts
  (*6 heads · 6 cells*, *4 heads*, *Nothing selected*), wears the desk link (the link badge while
  the window follows, a dashed mark while it keeps its own — not a press, unlike the busk band's
  `DeskChip`), and its **×** deselects. Its menu: the busk band's subsets under *In rig order*
  (`desk/SubsetControls.tsx`, which the band's Cells menu and steps are too), *Select by colour ▸*,
  *Follow the desk selection* ✓ (`unlinkFromDeskNow` / `relinkToDesk`) and Deselect. A subset and a
  colour pick land on whichever selection the window is on (`useListSelectionWrites`): one desk write
  while following; unlinked, the desk's rule (`lib/cellsSubSelection.ts`) over the **list's own
  rows**, read as targets by the bridge's `selectedRowTargets` and back by its `rowIdsForTargets` — so
  a target with no visible row has nowhere to land, as a desk frame would not. The mask is
  `SelectionMaskPill`'s `row` tone, the picker while following and a reading unlinked.
- **What to do to them** — **Set ⏎ · Fan · FX · Release ⌫ ▾**. **Set** (`SetButton.tsx`, D6) opens the
  cell popup on cells; on whole rows it first asks the family, selects those rows' cells in that
  family's shown columns (a marquee, so the mask becomes it) and opens the popup once they land. It is
  refused only by the scope, never by the selection's shape. **Release** is one split button
  (`ReleaseTargetsButton`'s cell arm, D7): the face releases the cells when there are any, else the
  whole heads; the menu has *These n cells* (⌫, their values out of Local, worded per scope by
  `cellActionCopy`) and *The whole n heads* (fixture-fx-sheets plan D18: one `programmer.clearTarget`
  per Locate target at the programmer fade, a group the group and an element that head; live in
  Local and Output, refused with its reason while a Look or palette layer is focused). There is no
  cell **Clear** on the programmer any more: *Clear* means row A's whole-programmer Clear. **FX**
  (`ProgrammerAddFxButton.tsx`, D8–D10) is `+ FX` on the selection — the picker under the button,
  *Replace · Stack*, the live editor once started — §"Applying to a selection".
- **Where they are on the rig** — **Locate ▾ · Highlight** (`fixtures-list/RigVerbs.tsx`, shared
  with the plain lists' `SelectionToolbar`), at the right edge.

**Its width ladder is the row's own** (D12, `rowCFolds.ts`, container queries on `ProgrammerGrid`'s
`@container`): from 1100 every word and ⏎ / ⌫ on Set and Release; 860–1099 Locate and Highlight
iconic, Set, Fan, FX and Release worded; 800–859 the verbs iconic too; 600–799 every control iconic and the mask abbreviated (`Col`), the chip counting a
marquee's cells alone under 660 (the arm measured 656 with the heads and FX, plan §9); below 600 the phone arm —
measured (`useContainerBand`) and handed down as `ToolbarPhoneContext`, since it changes components,
not classes: the heads chip counts cells and drops its ×, Locate, *Set locate from current* and
Highlight (latched, since a menu row cannot be held) move into its menu, and its menu, Set's family
question and Release ▾ are bottom sheets (`sheet/ToolbarMenu.tsx`, 44px rows), and the verbs close up
— no divider, a 4px gap, 8px padding — which is what fits Set · Fan · FX · Release ▾ on a 360px phone
(358 of it, measured). On a short viewport
the row stays out of the flow until something is selected, as before (`selectionBandState`).

`+ FX` follows the same rule as a value edit, from row C's FX, the FX table's library row or ⌘K's
*Apply FX → On the selection*: focused layer → into that Look (started on `/fx/add`, then
`POST /looks/{id}/absorb-fx`, which *moves* the running instance); Local → the bare FX
press (`POST /programmer/fx`), one Local FX over the selection under its mask, which Record
writes onto the cue; Output → disabled, naming the two places that can take one.
**Make layer** opens `RecordLookSheet` as *Make layer* (`makeLayer`: its own title, on the
selection's mask, no Mode and no option, and nothing to record until heads are selected): a new
Look over the selection, which is one `record-look` call with `asLayer` — the desk writes the Look,
puts it on top of the stack, and takes what it recorded out of Local, the rest staying yours, and
changes nothing when the write fails. *Record → A new Look* is the same sheet, opening on every
attribute, with *Use it here as a programmer layer* on and its own to untick.

**Clear counts held scenery** (`ProgrammerActionBar`): it is enabled for a programmer holding only
scenery, and its tooltip (`clearSentence`) says *Release … and 2 pieces of held scenery over 2s* —
the desk's `programmer.cleared` reply counts values and FX only, so the count is
`programmer.sceneryState`'s (`useHeldSceneryCount`, an RTK Query read of its own that no band or
table has to be mounted for).

### The programmer's tables

**The programmer is the table** (tables-colours plan D1, session 1; the design record is
`../docs/plans/completed/tables-colours-design/`, whose `Programmer.dc.html` strips A–D are this session's
layout). Row B starts with a tab segment, **Values · Stack · FX · Scenery**
(`programmer/ProgrammerTableTabs.tsx`), Stack, FX and Scenery each with its live count — layers,
running FX, held scenery. The tables were the applied tables (venue-visit plan S14) at
`/programmer/applied`, a route of their own beside the programmer; that route now redirects
(`LegacyAppliedRedirect`: `?table=fx` keeps its view, `looks` and `palettes` land on `stack`
filtered to that kind), and `/programmer/fx` lands on the FX tab. **They are the programmer's only
lists**, and nothing docks beside them: Colour and Fan are the grid's own editors — a Colour cell's
popover and row C's Fan — and `AppliedTables.test.tsx` pins what the tables carry alone.

- **The Values grid never unmounts.** `components/programmer/ProgrammerTables.tsx` is the frame: the
  grid, created above it by `ProgrammerBody`, stays mounted under the other three, `hidden` and
  `inert`, and Stack, FX and Scenery mount on demand. `useListSelection` clears the scoped selection
  on unmount, so a conditional grid would throw away what Record scopes on; `ProgrammerPage.test.tsx`
  asserts `gridMounts` across a tab switch. **`inert` is also what silences the hidden grid's
  keyboard**: `FixturesListContainer`'s window listener returns when its root is inside an `[inert]`
  ancestor, or ⌫ on the Stack tab would clear the hidden marquee's values. Hidden, its virtual rows
  are gone, so a programmer event under another table costs a fifth of one on Values.
- **Nothing on the page listens to the address but what must.** A tab, a filter keystroke and a
  sort all replace the URL, and every `useParams`, `useSearchParams` and `useNavigate` below the route
  re-renders on any location change (React Router's route context is a fresh object per location).
  So `FixturesListContainer` takes `projectId` as a prop and keeps the `?select=` deep link in a child
  (`DeepLinkSelect`) that only the two plain lists mount: measured on a profiling build (session 1), those subscriptions were 11 ms of every
  tab switch. `ProgrammerPage` is a
  route wrapper round a memoised `ProgrammerView`, the frame's `select` is a stable callback, and the
  tabs' counts are read once by the frame (`useProgrammerTableCounts`) and handed down in its
  context, so a table's own tab segment mounts subscribing to nothing. Keep it that way — a router
  hook in the grid is paid on every keystroke in a table's filter.
- **The table is a per-window fact; a table's view is the address.** `lib/programmerTables.ts`'s
  `programmer.table` (`sessionStorage`; it was `applied.table`, renamed, one store) is what the
  Screens sheet's *Table* segment sets (`windows.viewOptions {table}` under the Programmer view, now
  `[PROGRAMMER_TABLE_OPTION, IMMERSIVE_OPTION]`) and the announce carries; the frame mirrors it into
  `?table=` with `replace`, and a `?table=` arriving wins. A tab's press replaces the address with that
  table and the view the window left it on. Sort (`?sort=`, `?dir=`), filter (`?q=`) and the chip
  (`?kind=` on Stack, `?home=` on FX) ride the address with `replace` and are remembered per window and
  table; scroll beside them, out of the URL. **Values keeps no view there** — its filter and columns
  are the grid's — so a bare `/programmer` on Values stays bare. `RigGate` carries the search across a
  follow.
- **The scope picker is the Values tab's alone**: it is on the grid's row B, hidden with the grid.
  The other three draw their own row B — `AppliedTableShell`'s library row, led by the same tabs
  (`LibraryRow`'s `leading`) — over the selection bar, the sheet and the footer.
- **The segment folds by row B's width, words before counts, and is never a menu.** From 1040px of
  the row every tab has its word; below it a closed tab is its glyph and count; **below 600 the open
  tab is too**, and every count rides its glyph's top-right corner (`Count`'s `corner`), so a tab is
  22px whatever its count says and the segment 94 — the phone's `▦ ≡4 ~5 ⛰2`. Session 1 folded the
  four into a menu below 760; the corner count is what lets them stay a segment there (on the Values
  row Lit and Columns still go to their icons, `WORDLESS_BELOW_760`), and below 600 the Values row's
  icon buttons are the chrome rows' 32px squares, its gaps 6px and its spacer a margin
  (`PHONE_ROW_B`), which is what lets the segment, the scope picker and five buttons share a 393px
  phone's 369. Only the short-height row draws the menu button (`compact`,
  `data-programmer-tables-menu`), glyph-only, since that row carries row A as well. The Values row's
  filter is a field from **600** of row B. Measured with Playwright at 1440, 1180, 1024, 900, 768,
  700, 600, 393 and the folded 852×393, on all four tables, no row overflows and no two controls
  overlap. **Row A folds into row B only on Values**: under the other tables the
  grid's row B is hidden, so `ProgrammerBody` gives row A its own row there (`useValuesTableShown`,
  asked only while the viewport is short, so a tab switch at an ordinary height never re-renders the
  memo barrier).
- **Every cell is a read-out** but the Scenery table's state cells (below); a row acts through its own actions and the desk's mutations, the bar
  carrying the same verbs over a selection, a one-row verb disabled over several with the reason.
  **FX** is two views, *Selection* (the FX editor, below) and *All* (`applied/AppliedFxTable.tsx`,
  Applied FX moved, unchanged columns, bands and verbs): `fx/active`, one row per instance in the
  desk's order; timing `Own 96`, *Natural* or the instance's master off the live bank, size, direction, runs
  and *spent* the instance's fields, *held · Blind* `isHeldByBlind`; *Edit…*, *Pause* / *Resume*,
  *Save as palette…* (`applied/fxRows.ts`' `saveAsPaletteOffer`), *Stop*. *Edit…*, ⏎ and the
  name's double click are the row's press: it selects that FX's heads and opens it in *Selection*,
  picked (the host's `onOpenFx`). Its library
  row's create verb is **+ FX** (`useProgrammerAddFx`: the bare FX press over the
  selection in Local, into the Look a focused Look layer applies, refused with the reason in Output
  and over a palette; asking *Replace · Stack* as row C's **FX** does, which is the same gesture
  from the Values tab — §"Applying to a selection"), in its own component so only the button hears
  the selection; under the
  selection bar the rule row ***Values above beat FX below*** (`applied/FxBelowRule.tsx`).
  **Its selection is published** for the Values grid's rings (below).
- **Stack** (`applied/StackTable.tsx`, D5): *Your values* pinned on top — its value count, its Local
  FX, *Make layer* in its Note — then every layer top first (`stackLayers`), Looks and palettes
  in one table, numbered from the bottom (*layer 3* is the third from the weakest), with
  `useReplacementNotes`' *lost Hex 3–4 to Amber* as the Note and a palette whose FX is spent
  marked. Kind chips **All · Looks ·
  Palettes** filter, *Your values* staying pinned. **Reorder by `SheetTable`'s `rowDrag`**, positioned
  with `top`, calling `programmerMoveLayer` — the drop read back from top-first ids to the desk's
  bottom-first index by `layerMoveOf`; no client renumbering, since the desk renumbers the stack and
  re-ranks the running FX in place. *Your values* is pinned
  (`rowDrag.canDrag`: no grip and no drop onto it, so the top layer is as high as a drag goes).
  **The drag is refused while the rows are not the stack as it is** — a kind chip, a filter or a
  sort — and the footer says which. The shell's own *No layers match the filter* would never draw,
  *Your values* always being listed, so the table says it under the rule. **⏎, the name's double
  click and *Edit in grid*** (menu and bar) point the Values grid's scope at the layer, or at Local
  from *Your values*, and switch to Values. A layer row has its enable toggle and settings popover
  (`LayerSettingsPopover` — amount, blend, mask, stomp, *Detach*, remove), and its menu adds *Edit
  look…* (`LookDetailSheet`) / *Edit palette…*, *Pick up* and *Remove*. *+ Look or palette* is the two
  pickers behind one menu (`programmer/ApplyLayerPicker.tsx`); row A's *+ Look* is the Looks' half of
  it on every tab. The rule row is the FX table's, with
  the FX count and a press to the FX tab after it.
- **Scenery** (`applied/SceneryTable.tsx`, D6): every element, **Venue then Set** by name under
  divider rows, with the scenery filters as chips — *All* (their unfiltered state) · *Set* · *Venue*
  · *Moving only* · *Changed* (the Values grid's *Lit* for scenery: only what something on stage holds
  off its base, judged by each listed piece's state against its base — the frame also lists pieces a
  cue tracks back to their base), one per-window store (`useSceneryFilters`, `sessionStorage` under
  `programmer.scenery.filters`) — and a held piece listed whatever they say. **One scope reading**,
  `useSceneryScope` (`programmer/ProgrammerSceneryList.tsx`, shared with the Stage view's scenery
  popover): the state is three **cells**, one per kind of state — *Shown* (Shown · Hidden, every
  piece), *Open* (a percent, a drawn drape) and *Trim* (metres, a flown piece with an out to fly to,
  each piece stopping at its own in and out) — so a marquee down a column and Set write that state to
  every selected piece that has it, a piece without it skipped and named; there is no Clear (a piece
  is released whole) and no Fan. A Set over several pieces is **one** write (`SceneryScope.writeMany`),
  since a focused Look's scenery is written whole; Output and a palette layer refuse with the scope's
  reason (`AppliedTableShell`'s `cells`, which the other tables leave out). *Held by* and Kind are
  read-outs; a row's sliders (and ⏎) open its `SceneryElementRow` at the row through
  `EditorSurface`, and the × releases. *Release* on the bar releases the selection's held pieces —
  one at a time from a focused Look.
- **Folds are by the sheet's own width** (`useContainerBand`, `APPLIED_BAND_FLOORS` 1100 / 860 / 480,
  memo rows, overscan 8, all unchanged): band 0 draws every column; band 1 merges FX's Timing with
  Cycle and Amount · Dir · Runs into Dynamics, and a layer's Blend · Mask · Stomp into Settings; bands 2
  and 3 put the attribute, or the stack position, in the name column and let the rest scroll sideways
  under it. **The sheet is the programmer's width less the sidebar**, so a 1440 desk with the
  sidebar open gives it ~1200 — band 0 — and the 1180×820 iPad ~940, band 1. On a programmer table the partition chips carry
  `PROGRAMMER_CHIPS_CLASS` (`min-w-32`): with the tabs leading the row, the filter would otherwise
  take the slack and the chips' folded select would run under the create verb.

**The FX editor** (FX editor plan D1–D6, session 4) is the FX tab's *Selection* view
(`components/fxEditor/`): list · graph · inspector over the window's pair (`useWindowFxPair` — the
desk's selection while the window follows it, else the programmer list's rows). The graph (session 5)
draws the preview's answer; the patterns, the head ladder, Match, hover lights and the graph's handles
(session 6) tie the inspector to it; the FX palette editor, the cue editor's FX tab and the busk FX
tab draw its graph strip, and the fixture sheet's tray opens it (session 7). Things that will bite:

- **The view is a per-window fact** (`lib/fxEditorView.ts`, `sessionStorage`, P6): *Selection ·
  All* (`programmer.fx.view`, Selection the default), the picked FX, the one Solo marks, the
  folded lanes and the graph's toolbar (`programmer.fx.graph`). Solo is a mark on the card: the graph
  draws it (D14), the stage never changes.
  `ProgrammerFxTab` is the tab's host and draws *Selection · All* after the table tabs on row B.
  *All*'s row press and a running FX's card with nothing selected select through
  `fixtures-list/programmerSelectRequest.ts`: `setDeskSelection` while following, the desk's mask
  kept, else Include's request (`store/includeSelection.ts`), which opens the group or fixture a head
  sits in and clears the filter — a group sent as its row and its members, since a flat list has no
  group row, and an element as its row under its fixture's. The Values grid never unmounts, so the list
  is always there to hear it. **A request lands once** (programmer-fixes plan D10): the grid that lands
  it spends it a task later (an unmount cancels the spend, so StrictMode's remount lands it again),
  and a grid mounting after finds nothing to land; one published while no grid is mounted is landed by
  the next grid of the project the publishing window's address named, within five seconds, and never
  otherwise, so the way back to the programmer never re-selects, reopens or clears the filter and
  *Lit*. The picked FX is drawn only while it is on the selection: the id
  outlives a selection and a desk restart.
- **The list is the desk's apply order, read, never computed** (`fxEditorList.ts`' `fxLanes`):
  Intensity · Colour · Position · Beam, a lane for each family an FX is in or the selection can
  take, each FX reaching a selected head (`fxPaintedHeads`) a card. Top first: Local FX, then the
  programmer's layers' FX, then cue FX (read-only, *on Q12*, opening the cue editor through
  `OwnerEditor`), then *Underneath* (`useUnderneath`: `programmer.keyStack`'s top value — park, the
  programmer's slot, a stack's Move in dark hold, the cue's, the base, passing over the programmer's slot while it is blind, as
  `LayerResolver.fallbackFor` does — for the lane's first FX's attribute on the first selected head
  carrying it, re-asked on that key's provenance), then *+ FX*. `fx/active` carries no priority,
  so the bands are read from each FX's home and the order within a band from the preview's answer,
  whose `fx` is the apply order bottom first; one the answer has not reached goes on top of its band,
  where a new Local FX lands. A card's family is the answer's, else its attribute's family on a
  head that carries it. A card names its attribute in words where the lane's family has several
  (`paletteAttributeFor`'s label — *UV*, *Zoom*), and its Direction only where it is not the FX
  type's own (`newFxDirection`; Path's sweep presets bounce, by their own Direction). A layer's card has pause and no ×,
  as the busk FX tab's pad FX has none: stopping the instance alone, the layer respawns it on
  its next recook, so the layer is what lets it go.
- **Nothing selected** (follow-ups D1, `fxEditorHead.ts`' `runningCards`): one line, then every
  running FX not leaving as a card, by family in the lanes' order then by name — its family, or its
  attribute where that is not the family's own (*UV*, *Zoom*), its target (`fxTargetLabel`, a head
  by `headName`), how many fixtures that is, its cycle and master. A press is *All*'s row press:
  the FX's heads selected (`selectWindowHeads(fxTargetsOf(…))`) and the FX picked. With none
  running, *Select heads in Values* shows the Values table. The graph says why it is empty —
  *Select heads to draw their FX.* or *No FX reaches the selection.* (`FxGraph`'s `empty`).
- **The subject** (follow-ups D2, `subjectForm`): a group reads its name and how many fixtures, one
  fixture (or elements of one) its name and its elements, several fixtures *4 fixtures · 12
  elements ▾* — a `ToolbarMenu`, a dropdown on a desk and a bottom sheet of 44px rows below the 760
  arm, listing each fixture's colour (`useFixtureColourIndex`), name and selected elements, a press
  being *Only* (`selectWindowHeads` with that fixture's targets: the fixture whole, or the elements
  of it the selection named), the word shown on the highlighted row and on every row under a finger.
  *Only* takes the chip away, so focus goes to the mask pill. In a window with its own selection it
  goes through Include's request, so the marquee's mask is let go there. The count never truncates:
  the pill and badge wrap below it where the column is too narrow, and a named subject's name
  truncates beside them. Measured at
  248px with four Shehds, the count, the pill and the link badge take 226 of the row's 231. The
  family pill (`SelectionMaskPill`) is row C's: it writes through the programmer list's desk bridge,
  which the grid's toolbar publishes in `fixtures-list/remaskStore.ts` (`ProgrammerPage` provides
  it), so a marquee whose columns no longer say the mask drops to its rows as it does from row C; on
  a window with its own selection the pill is a read-out.
- **A drag is one `programmer.moveFx`** (`moveLocalFx`, `localMovesFor`): a Local card drops on
  another Local card of its lane and takes its place, the frame naming the card that ends directly
  above it; the grip moves it with ↑ / ↓. The desk can only put an FX *below* another, so the one
  drop that is two frames is to the top of a lane from further down than second — the dragged FX
  below the top one, then the top one below it. Layer and cue cards have no grip. Nothing is
  renumbered here: the desk's `fxChanged` refetches the list and re-asks the preview.
- **+ FX opens `FxPicker` in the inspector on the lane's family**, the press arm. Where a tap would
  start an FX on a family already holding a Local FX on a selected head — never the one this session
  started — it asks ***Replace · Stack*** (`fx/ReplaceStack.tsx`, asked through `fx/useReplaceStack.tsx`, the one hook the
  picker's FX arm asks through too), starting on *Replace*; *Stack* is the picker's `press.stackOn`,
  which sends `stack: true` and **no blend**, so the desk starts it on the family's stacking blend
  (`fx/FxStacking.kt`), and a swap keeps that blend. A focused Look layer
  takes `+ FX`'s own sheet instead, which absorbs into the Look; Output and a palette layer refuse
  with the offer's reason, as *+ FX* does everywhere (`useProgrammerAddFx`).
- **The inspector is the live editor** (`SplitFxEditor`, so the split, the 50 ms floor, Revert,
  *Update palette* and *Reset to palette* are unchanged) with `layout="inspector"`: `FxFields` in the
  four questions every host draws (fx-settings plan D9) — Speed, the FX's own section, Across
  the heads (Over, the head ladder from the preview, the patterns, Each head later by, Segments · Parts · Wings),
  Mix — and Advanced, at the inspector's wider spacing. Every other host draws the ladder from `lib/spreadShape.ts`. The Speed
  section's two rows (the tempo chip, Tap and the tempo; the slider) are what let the inspector fit the editor's three bands.
- **Widths are the editor's own** (`useContainerBand`, `FX_EDITOR_BAND_FLOORS`): from 1100 three
  columns, list 248 · graph · inspector 340; from 760 list · graph, the inspector over the graph's
  right half while something is picked or the picker is open; below 760 one column (§4.2's phone
  arm): the list folds to a row of lane chips (`LaneChips` — each family's word and FX count, lit
  while it holds the picked FX), one lane opened under them (`programmer.fx.phoneLane`, per window),
  then the graph, then the inspector.
- **The preview** (`useFxPreview`, `store/fxPreview.ts`, whose types mirror `routes/fxPreview.kt`)
  asks `POST /fx/preview` over the selection with the picked FX (else the list's top card), `include:
  STACK` — the desk folds every FX reaching the selection beside it, and naming more would cut those
  past the eighth out of the fold. It asks again, the latest ask winning and an older answer dropped,
  when the selection or the list's FX change (one joining included), on `fxChanged` for an FX in
  the list (how an `updateFx` this window sent is answered, and how a move lands; a `removed` one is
  left to the list), on a tempo move on a master in the answer's `clock`, and on an entry or
  provenance move on a row's key for an attribute it drew. Moves arriving together coalesce into one
  ask a microtask later, and **one ask is in flight at a time** with at most one waiting behind it,
  sent with whatever is asked by then — a fader dragged under a drawn row moves its key every frame,
  and the desk samples the whole stack each time. `previewFx` is a mutation, so it is in
  `NON_SAVE_ENDPOINTS` (it would flash *Saving…*) and `SILENT_ENDPOINTS` (a refused ask is followed
  by one that answers). It sends the graph's `window` and, while a card is solo'd, `solo` — an index
  into the ask, so a solo'd FX that is not the picked one is asked for beside it. The list reads only
  the apply order and the held-back reasons. The strip's hosts ask through the same hook with
  `drafts` (`{draft}` entries, `fxStripDraft.ts`' `previewDraftOf` — only the fields the FX says,
  no master beside an Own tempo), `include: ONLY`, `notional` (heads the rig lacks, no targets, no row
  keys to hear) or `ownHeads` (no targets: the FX's own heads); with none of those an ask with no
  targets is still no ask.
- **The graph draws the answer and computes nothing** (P2; `FxGraph.tsx`). `fxPreviewDecode.ts`
  decodes the base64 planes once per answer into `Uint8Array`s per row, attribute and FX (a Steps
  output's steps as an `Int32Array` beside its labels); `fxGraphModel.ts` lays a lane out —
  one section per attribute, its rows in the answer's order (the picked FX's Order) or by `rigIndex`
  under *Rig order*, a heat map at 6px a row from 25 rows — and reads the marks off the answer: the start ticks are `starts`, the hatching `holding`
  (inclusive), Segments and Parts brackets and the Wings' mirror the ladder's rungs, a row's phase on
  hover the distance between its starts (a cycle is 48 samples, the desk's rate, cut or not). The
  desk starts the window at whatever tick the master is on, so the beat grid and the hover's *Beat*
  are placed from the clock's `tickAtStart` (a beat's phase 0 is a multiple of 24 ticks), never
  counted from sample 0. `fxGraphDraw.ts` draws a lane onto a `GraphCtx` — a pick of
  `CanvasRenderingContext2D` — so tests draw onto `test/pixelCanvas.ts` and read pixels; `FxGraph`'s
  `contextOf` is that seam. One canvas a lane: Intensity and Beam an area per row over a dashed
  *Underneath*, the picked FX's own output a violet line under *Result*; Colour a strip (*As seen*:
  times the Intensity lane's `dimmer` result), and a colour wheel's row its level under a strip of
  the beam's colour and slot from the answer's `wheel`, under *Result* only and with no mixed row over
  a fixture's wheel heads; Position pan and tilt traces scaled to the widest
  swing, and a path panel beside the lanes; a Steps FX's blocks labelled in a band over its rows; a
  held-back FX hatched under *This FX*; *representative* and `truncated` in words. *As seen* takes an
  element's Intensity from its fixture's row where the element has none. The path panel sits beside the
  lanes while that leaves them a plot, else under them. Below the 760 arm it draws the picked FX's
  lane alone and says so: every row laid out, six of them shown and the rest a scroll away
  (`scrollRows`, the editor's; `narrow` without it cuts at six). The playhead sits inside the
  scroller (`RowScroll`), so it spans every row. The column's rows size to their content there
  (`auto-rows-max`), since the graph under the list is tall.
- **A fixture's own row** (follow-ups D5): every multi-element fixture gets a row over its elements —
  `layoutLane` groups a section's rows by fixture, in the order each first appears. Where the answer
  has a row for the fixture itself (an attribute it carries, a bar's dimmer) that row is it; else it
  is a **mixed** row (`LaidRow.mix`, `row` -1, hovered by `mixKeyOf`, which no answer row has),
  drawn as the mean of its elements' samples per plane, rounded — built once per answer as the
  attribute's `mixed` (`fxPreviewDecode.ts`' `mixedRows`, `mixRow`, `mixSeries`), drawing as *As
  seen* is, never output (P2). A Position or Steps section draws its name and no mix. A mixed row
  takes no Phase drag and no hover phase, reads *Bar · mix of 3* on hover through the one
  `HoverReadout` (the view honoured), and draws a start tick only where its elements share it
  (`rowStarts`). The brackets and the Wings' mirror are ranked over the answer's rows, a mixed row
  standing in for its elements (`rungRow`) only while they are folded. Its chevron folds the elements,
  per window (`programmer.fx.foldedFixtures`, beside the folded lanes); folded, it is drawn alone.
  The heat map counts the answer's rows, a folded fixture as one — a mixed row over drawn elements
  adds none — and since a heat section draws no names, the lane's header carries *Fold elements* /
  *Show elements* for every fixture in it (`setFixturesFolded`). The strip draws fixture rows too,
  with no chevron or fold.
- **Names** (D6) are DOM over the canvas (`GutterLabels`), never canvas text: a fixture's cut in the
  middle by measured width (`middleCut`, an `OffscreenCanvas` where there is one, else an estimate)
  with its whole name on hover; an element reads its own words after the fixture's name, else — or
  where they do not fit — its number (`elementLabel`). The name column is the window's
  (`programmer.fx.graph`'s `gutter`, 152 by default, 96–280), set by a grip between names and plot
  (`role="separator"`, the arrow keys stepping 8) and held at the default under a coarse pointer,
  where the grip is not drawn; wherever it would leave the plot under 160px it gives way, down to
  72 (`fitGutter`) — the side form's graph beside an open inspector is ~236px at the band's floor.
  The grip sits inside the names, at the width drawn, but reports and moves the window's width, so
  a key press while the names are narrowed never loses it.
  The strip's is a fixed 72, since its hosts are narrow.
- **The ruler and the toolbar** (D4): a 24px ruler canvas over the lanes numbers the picked FX's
  master's beats from the tick the window started on (`rulerTicks`: bar numbers on the bar, counted
  from the bar the window starts in, `bar.beat` between where there is room, bars alone over 16
  beats), or seconds on its own clock. The toolbar is one row of 28px segments, each labelled inside
  (*Window*, *Draw*), the window's length (*8 beats · 4 s*) beside Window, wrapping between segments;
  below 300px of toolbar (a container query on it) a segment's label folds and its buttons wrap
  inside it. The line under it is the hover's, or the row order and the hint. Widths measured at
  each band are the plan's §9.
- **The inspector and the graph meet through one context** (`components/fx/fxGraphLink.ts`'s
  `FxGraphLink`), which `FxEditorSelection` provides around its three columns only — never the sheets
  it opens (a cue's editor, *+ FX*'s sheet), whose editors have no graph — and only the live editor and
  `FxFields` read, so every other host draws none of what follows and draws its head ladder from `lib/spreadShape.ts`. The live editor
  **lends the graph its draft and its write** (`attach`; `detach` takes back only the editor it names,
  so one editor going never takes another's handles), its parameters filled from the library where
  the FX carries none, and tells it every move before the floor (`preview`): the editor holds it as
  the ask's `draft`, sent as `{fxId, draft}` (`useFxPreview`'s `draft`, on the first FX asked for), so
  the graph follows a finger, and drops it on a refetch of the running list once the gesture's
  release is sent — the desk has the move — never mid-gesture, when the move is newer than any push
  the desk answered. An answer to an earlier move of the same drag is still drawn (the stale check
  ignores the draft), the ask behind it bringing the latest. A held split edit is never previewed, and
  lends no handles. The trigger subscriptions key on whether there is an ask, not its content, so a
  drag does not resubscribe them each move. The graph's hovered row is a store
  (`HoveredRowStore`), read by the head ladder alone, so a pointer crossing the rows redraws only its heads.
- **Patterns** (D11, `fx/fxShapes.ts`; fx-settings plan D10) are chips under the head ladder, in every host: one
  tap writes Order, Wings, Segments and Parts in one `updateFx` and leaves Spread and its unit to *Each head later
  by*; a chip is lit only while all four match, else *Custom* is, which opens the Order select — and stays lit, once
  pressed, for the fields it was pressed on and those its Order select writes, so Revert lights the pattern again. A chip's 40 × 16
  drawing is `lib/spreadShape.ts` over the FX's own heads, up to sixteen, else eight notional — the one client rule,
  allowed where the desk has not answered (P2), since a pattern is drawn before it runs.
- **The head ladder** (fx-settings plan D10, `fx/HeadLadder.tsx`) heads *Across the heads*: in the inspector each row
  the FX paints, numbered by its start in the answer's `ladder.spread`, never computed — the five-row offset ladder it
  replaced drew each step of the spread, which the line under Segments · Parts · Wings now says in words. A split-off
  copy keeps its start. Hovering a head marks its graph row in the gutter (`linkedRow`); hovering a graph row rings
  its head (`onHoverRow`).
- **Match** (D13) is *Same as…* in the tempo chip's menu (*Same speed as…*) and beside the patterns, over the list's other FX: one
  write of the source's cycle and what it follows (master 1 named by its uuid, since an absent master keeps), or
  its four pattern fields with Spread and its unit. A source on a master of the other clock — read from its library entry,
  since a beat FX on Own tempo reports the wall clock — is offered disabled (one on Own tempo copies
  its tempo with the cycle), and so is a spread source on one head, which says no Order. Nothing links them
  afterwards (`FU-FX-LINKED-TIMING`).
- **Hover lights** (§4.5): `FxFields` wraps each control in `Lights`, which sets the link's `highlight`
  while the pointer rests there, and `drawLane` lights that mark (`LaneDrawOptions.highlight`,
  `palette.highlight` / `highlightArea`): Cycle shades the first cycle (over the rows, so a colour
  strip does not hide it), Each head later by, Order, the patterns and Phase the start ticks, Wings the mirror,
  Segments and Parts the brackets, Runs the hatching, Amount the gap between *This FX* and *Result*.
  A light whose control unmounts under the pointer puts itself out. A knob lights the same way, run the
  other way (`FxGraph`'s `onHighlight`): the Speed tab the first cycle, Spread's knob the start ticks,
  a max or min knob the level lines (`levels`).
- **The handles** (D12, `GraphHandles.tsx`, `fxGraphHandles.ts`; follow-ups D4) are knobs — a violet
  disc, named on hover (*Later by ¼ cycle*, *High 100%*) — and a mark is a tick or a thin line. Only while the inspector
  edits the picked FX, never under a coarse pointer (`hooks/useCoarsePointer.ts`) or read-only: the
  **Speed tab** on the ruler at the first cycle's end (always sample 48, the desk's rate), labelled
  with the Speed (*×1 ⇔*, its hover *Speed ×2 · 0.94 s*), snapped to a power of two by ratio on the
  slider's log scale (fx-speed plan §4), a wall-clock FX's too — the cycle the drag began on counting
  as a snap, so a triplet's ×⅔ is kept until the drag leaves it; over the picked FX's own section, **Spread's
  knob** at the end of the dashed line through each row's first start, on the lowest row Spread
  moves furthest (a row of share 0 never moves, so a knob there would not follow the finger); a
  row block dragged sideways (the canvas's own pointer-down) for Phase; an Intensity FX's `max` and
  `min` lines on every row, as marks, with one pair of knobs at the first row's right end, since
  they are the FX's (`levelScale`, the drawing's). A knob's light goes out as the pointer leaves
  it, so the next knob's holds. Each is measured
  from where the drag began, since the answer redraws under the finger, starts only once its own
  pointer has moved 3 px (`DRAG_SLOP_PX`, so a click edits nothing), writes `set(patch, false)` per
  move and lands `set(patch, true)` on release — nothing for a drag that changed nothing — through the
  live editor's `write`, so the floor, Revert, the split and the palette arm are the editor's. A
  member's own clock is the FX's less its offset plus its Phase, so dragging the chase line right
  widens Spread and dragging a row right lowers Phase: the starts follow the finger.
- **The playhead is the client's, the clock the desk's** (`GraphPlayhead`, `useGraphPlayhead`): it
  runs from sample 0 when an answer lands, at the wall clock's rate, wrapping at the window's last
  whole cycle of the picked FX (two under Bounce), since *2 bars* of a 3-beat FX is not whole; a
  `speedMasters.beat` on the picked FX's master resyncs it — beat *n* is the master's tick
  `24n + 1`, measured from the clock's `tickAtStart` at the answer's tempo — and a later answer on the same master carries
  the master's tick across, so a re-ask never sends it back to the start (a fresh answer also asks
  for a beat, since the desk throttles them). A `seconds` clock runs from sample 0 and nothing
  resyncs it. It is a positioned `div` moved by `requestAnimationFrame`, not a redraw of the lanes,
  and the path panel's dots are their own canvas for the same reason. Paused (the graph's, never the
  stage's) or under `prefers-reduced-motion` (`hooks/useReducedMotion.ts`) it holds still and asks
  for no frames; let go, it asks for a beat, since it runs on from where it was held. The hover
  holds a row by its key, so a re-ask that reorders the rows keeps naming the same head.
- **The graph strip** (D15, §4.6, `FxGraphStrip.tsx`) is the graph cut to the picked FX's lane for
  the hosts that edit one FX: `FxGraph`'s own lane drawing (`GraphLane`, `layoutLane`, the path panel
  for its lane only) and playhead, eight rows shown and the rest a scroll away, no toolbar, hover
  read-out, lights, ladder or handles — the link stays the FX editor's, and no host provides it. Each
  host owns its ask and says in a caption what it draws on. *This FX* (`draw="this"`) is
  `ownOutputOnly` over the answer: each row's own output as its result, nothing underneath, never
  parked or held back, since with `include: ONLY` the desk still folds over what is underneath now;
  where the FX holds (its Runs not begun or spent) the desk fills its own output with what is
  underneath, so those samples keep the FX's nearest own value under the hatching. A draft's moving
  fields are, like the inspector's `draft`, outside the hook's stale check — only what it is and where
  it runs must still match — so the strip redraws during a drag.
  - **The FX palette editor** (`PaletteRunsOn`, in place of its old stripe and beat ruler): the draft
    as a `{draft}`, `include: ONLY` and `programmerOwned` (the band a pressed palette runs in, so a
    programmer value does not hold it back), redrawn as it changes — *On the selection* (the
    window's, `useWindowSelectionTargets(false)`: the editor is the Palettes page's), drawn as *Result*
    over what the heads hold, or *On eight heads* (`notional: NOTIONAL_HEADS`, the desk's
    `NotionalRig`), drawn as *This FX*; with nothing selected only the second. A palette FX that names
    no attribute drives what `FxSpawner` resolves (`storedFxAttribute`): Palette Steps' from the
    palettes it steps (`paletteStepsAttribute`, the desk's `paletteStepsShape`), else the family's
    own, or a Beam FX's one declared attribute. Off the current project the desk refuses the ask and
    the strip says so; it draws no guess.
  - **The cue editor's FX tab**: the open row's FX, as edited, over its own targets, `include: ONLY`,
    drawn as *This FX*, the caption saying what is underneath at its GO is not drawn.
  - **The busk FX tab**: under the open row's editor, the running FX on its own heads (`ownHeads`,
    `include: STACK`), drawn as *Result*, every running FX `listed`, since any of them may fold in.
- **The fixture sheet's tray opens the FX editor** (⤢, `OpenInFxEditor`, mounted only inside a
  router): the sheet's fixture or group selected through `selectWindowHeads`, as *All*'s row press,
  the FX tab set to *Selection*, a modal host closed (`SheetCloseContext`, which `FixtureDetailModal`,
  `GroupDetailModal` and `BuskFixtureSheet` provide — a member's sheet closes its group's too — since
  one outside the routed page outlives the navigation), then `/projects/{id}/programmer?table=fx`.
  Off the programmer page the window's own selection goes as Include's request, which the
  programmer list takes up as it mounts. The list forgets the request it applied on unmount, as the
  unmount clears its selection, so `React.StrictMode`'s remount applies it again.

**The FX lens** (D3, session 2) answers *side by side* without a split: a Values cell an FX
drives carries a violet chip — the wave and the Speed (`lensDivision`: `×8`, `×1`, `×½`), `+n` where several paint it — in the cell's top-right corner,
**struck through when the FX is held back there**, the reason on its tooltip in the desk's
`heldBackReason` words: *your value holds it back* (`isHeldBack`), *held · Blind* (`isHeldByBlind`),
*a layer above stomps it*. A stomp the client cannot see directly, so it is read off provenance: the
desk names no FX on a key whose every painter it skipped, so a running, weighted FX under a
key provenance gives to a cue is stomped there — `lensHeldReason` checks the weight's other factors
first (paused, spent, leaving, amount 0, envelope 0, its stack at FX amount 0), so none of those is ever
called a stomp. A replacing busk stack fully in reads the same way there, so the lens words it as a
stomp: `LENS_REASON_WORDS` has `REPLACED`'s *a replacing stack holds it back*, but nothing on this side
can tell the two apart. A click opens `FxEditorPopover` on that FX, its split edit for the cell's batch;
with several, a menu names them first. Things that will bite:

- **One index, read per cell, never a subscription per row.** `fixtures-list/cellFx.ts`'s
  `buildCellFxIndex` keys every FX by the `(head, attribute)` keys it paints —
  `fxPaintedHeads`, the split editor's reading of the desk's expansion, head filter and element
  mode included — over the whole rig (`useFixtureLookup`, the rows' own fixtures until it loads), since
  a group's first member and a head filter's halves are read over members a filter may hide, in the
  desk's order. `CellFxLensHost`
  (`fixtures-list/CellFxLens.tsx`) holds the store and the one editor; `LensIndexer`, a *sibling*
  of the rows mounted only while the lens is on, subscribes to `fx/active` once and rebuilds the
  index per `fxChanged` into the store before paint. Each cell reads its own FX through
  `useCellFx`, a selector that keeps a cell's array while nothing the chip draws has changed
  (an instance's phase moves on every refetch, so it compares the fields the chip reads, and a
  reader matching an FX against the index goes by id), so an `fxChanged` re-renders the
  indexer and the cells whose chips moved.
- **The chip takes the corner, and the value never moves.** It is absolutely placed where the
  FX badge was, 12px tall at the cell's top edge — clear of a value drawn on the middle line —
  and opaque where it widens past the 18px gutter; the board drew it at the end of the cell's line,
  which in a 107px dimmer cell pushed the value. It takes the corner's badge and amber dot over
  (§The programmer's scoped grid), which stay as the fallback where no indexed FX paints the
  cell. It is `pointer-events-auto` and above Output's jump overlay, and stops the pointerdown, so a
  press on it is neither a marquee nor a cell selection. The grid's footer and the phone's key teach
  it as the board's footer does, whole beside *FX* and struck through beside *Held back*, from
  the cell's own classes (`lensChip.ts`).
- **Where it draws**: under `ProgrammerPage`'s FX selection store (the plain lists and the cue grid
  have none) and where ownership is drawn — Local and Output, not a focused layer, whose FX
  palette marks its own cells bottom-right. The host's tree is the same either way, so a scope
  switch re-renders the cells and never remounts a row.
- **Held-back state is read at render**, from `getKeyState` and `isBlind`: the row's ownership
  subscription already re-renders it on its keys' entries, provenance and Blind.

**FX selection rings.** The FX table publishes its selected rows' FX ids into
`fixtures-list/fxSelection.ts`, a store on the `marqueeContext.ts` pattern that `ProgrammerPage`
provides; on the Values tab the cells those FX paint are ringed violet (`data-fx-ring`). A cell
reads a boolean through `useFxRinged`, so a selection change re-renders only the cells whose ring
moves — and the hidden grid, whose rows are not drawn, none. The store outlives the FX table, which
mounts on demand: the rings show after a switch to Values, and the table takes its selection back
when it mounts again (`AppliedTableShell`'s `initialSelection`).

### Fixture colours

**A fixture or group carries one of ten named colours, as identity only** (tables-colours plan
D7–D11; the design record is `../docs/plans/completed/tables-colours-design/`, `Colours.dc.html` §B and §C;
the desk's contract is lighting7 `docs/fixtures-engineering.md` §"Fixture colours"). The keys,
labels, the reserved hues and the digit keymap are `lib/fixtureColours.ts`, pinned against the
desk's `models/fixtureColours.kt` by `fixtureColours.test.ts`, which reads the Kotlin source; each key
is `--tag-<key>` in `index.css`, light in `:root` and dark in `.dark`. A key this client does not
know reads as none (`readColour`). The colour marks a name, never a value cell or a ring: those say
who owns a value, and a red name must not look like a red value.

- **The name mark is a swatch and a wash** (D8), drawn by `FixturesTable` (the programmer, Fixtures
  › List, Groups › List and the cue editor's Values grid) and the patch sheet. The row takes
  `tagClass(colour)` — `fixture-tag fixture-tag-red`, whole strings — which sets `--tag`; the
  name's `TagMark` reads it, and `.fixture-tag > [data-name-cell]` washes the opaque sticky cell with
  `color-mix(in oklab, var(--tag) 16%, var(--background))`. **Oklab, not the plan's oklch**: the
  sheet ground is a near-grey at hue 286, and an oklch mix interpolates hue, so red's wash drew
  purple. The selection overlay draws over the wash, and the swatch keeps the colour readable there.
- **Resolved once, compared by value** (§3 of the plan). `hooks/useFixtureColourIndex` turns the
  cached patch list into a key → colour map whose identity changes only when a colour does (a
  selector over a per-list signature), so a patch refetch for an address re-renders nothing.
  `FixturesTable` resolves each row's colour into a primitive `tag` prop (`rowColour`: a group's own,
  a fixture's from the index, an element row its fixture's), so a recolour re-renders only the rows
  whose colour moved — `FixturesTable.test.tsx` counts them. Group colours ride `GroupSummary`. The
  rows read the index only while a `colour:` term filters (`hasColourTerm`).
- **Setting a patch's colour is the patch list's Colour column** — `FixtureColourCell` and its ten
  swatches in the cell editor's three forms. Over a selection of its cells, **1–9 and 0** pick the
  ten in order without opening it and ⌫ is Clear; inside the popover the same keys pick and close.
  Every write is **one** atomic request on the bulk route (`setPatchColours`), entries of
  `{patchId, colour}` alone, only where the colour moves; a refusal (`COLOUR_UNKNOWN`) is toasted by
  the sheet by code, so the endpoint is silent to the middleware.
- **No inheritance** (D9). A group's colour marks the group. *Colour from group* (row C, and the
  popover's *From group · name*) copies each head's coloured group's colour onto the selected heads
  that have none, onto all of them with ⌥ (`lib/colourFromGroup.ts`); a head whose coloured groups
  disagree is left and named in the toast. **A group's colour is set in Patches' *Edit Group* sheet**,
  written as it is picked and alone; the sheet's name and order drafts survive the refetch.
- **On a venue in a show** (D10) a pick on a venue head or group is the show's recolour, drawn
  *yours*; *Take venue's* sends `null`, which takes the venue's back — a show cannot recolour one to
  none. **The clear is named by what it does over the batch** (decided with Chris 2026-10-19):
  *None* where it only clears the show's own heads, *Take venue's* where it only restores recoloured
  venue heads, *Clear* where it does both (its title says which), and no clear where it would change
  nothing. A pick equal to the colour already drawn writes nothing — on a venue record it would pin
  the venue's colour as the show's.
- **`colour:red`** joins the fixture filter (`lib/fixtureSearch.ts`, the Fixtures cards and lists)
  and the patch list's (`lib/patchFilter.ts`); a term matches a key or label by prefix, several are
  any of them, and a bare `colour:` being typed filters nothing. On a grouped list a group of that
  colour stays too — its own colour, its members then filtered by the other terms (decided with
  Chris 2026-10-19) — beside the existing rule that a group stays while a member matches. ⌘K's
  *Select colour · Red* (`fixtureColours/SelectByColour.tsx`) writes the desk selection, and held ⌥
  turns it into *Add to selection*, keeping the desk's heads and mask.
- **Everywhere else a fixture is drawn** (session 5, `Colours.dc.html` §C), still identity only: a
  busk rig tile's **4px band** across its top (`RigTile`'s `TileBand`), the DMX sheet's **run
  edge** (`DmxSheet`), a 3px band over a surface control's **target label** (`SurfacePanel`'s
  `TargetLabel`), a Stage **label's swatch** (`stageLabels.ts`), a mark on the Positions panel's unit
  and group chips beside the live swatch, and a swatch before the fixture sheet's name. Each is
  handed its colour as a key and reads nothing per cell or per frame. The surfaces that name a
  *target* — a tile, a binding — read `hooks/useTargetColours`, the colour index widened to every
  head's element key (its fixture's colour) and every group's name (its own), identity stable until
  a colour moves; `lib/targetColours.ts`'s `targetColour` reads it. A group tile, a group's surface
  label and a group chip are the **group's** colour, never a member's (D9); a surface label's colour is
the desk's, named on its map. A real scribble strip is
  `FU-SURFACE-SCRIBBLE-COLOUR`.
- **The Stage view's housings by colour** (D12) are a view option, *Bodies · Plain | By colour*,
  below.
