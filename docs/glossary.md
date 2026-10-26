# Glossary

The desk's words, one row per idea: the word the screen, the wire, the code and the archive use for
it once its row is `done`, what it was called before, and MagicQ's word where the plan names one.
The words were agreed in [`plans/completed/terminology-plan.md`](plans/completed/terminology-plan.md); the D-numbers in
the Idea column are its decisions.

Write a `done` row's word everywhere. Write a `screen` row's word on screen, in docs and in commit
messages; in code its *Was* stands until the session that renames it there. Write a `planned` row's
word in docs and commit messages that describe the plan; in code, and to an operator looking at the
screen, its *Was* stands until the session that renames it lands.

**Status.** `done`: the code already uses the word wherever it shows. `screen`: the screen says the
word, and *In code* and the other layers still say *Was*; a later session moves those and flips the
row to `done`. `planned`: a rename still to land, and *Was* is what the code says today. The session
that lands a rename flips its rows to `screen` or `done` and gives them a key.

**In code.** The identifier each layer uses for the idea: the one §4.3 and §4.4 agree where a rename
moves it, and what the code already says where nothing does. Empty where the idea has no identifier of
its own.

**Key.** Where a `done` or `screen` row's word lives, as one or more locators; a `planned` row has
none. `GlossaryVocabularyTest` (backend) and `glossary.test.ts` (frontend) read every `done` and
`screen` row and check that each of its locators gives the row's word. Each locator's prefix says
which test reads it, and both tests refuse a prefix neither knows. An FX renamed before its id is
keyed by its old id, `fx:<old id>`, whose `name:` is the new word.

| Locator | Read by | Gives |
|---|---|---|
| `fx:<id>` | backend | the `name:` of the built-in FX `<id>` in `src/main/resources/fx/` |
| `family:<f>` | backend | `<f>`, a `family:` the built-in FX declare, compared without case |
| `masterFamily:<f>` | backend | `<f>`, one of `SPEED_MASTER_FAMILIES`, compared without case |
| `ORDER_LABELS.<KEY>` | frontend | the label in `lib/orderLabels.ts` |
| `FX_FAMILY_INFO.<key>`, `BLEND_MODE_OPTIONS.<VALUE>`, `ELEMENT_MODE_OPTIONS.<VALUE>` | frontend | the entry's `label` in `components/fx/fxConstants.ts` |
| `doc:<path>` | backend | `<path>`, a file from the repository root, says the word, ignoring case |
| `src:<path>` | frontend | a string literal or JSX text in `frontend/src/<path>` that is the word, or the word with an *s*; a segment's word, `Label: A \| B`, is checked as each of its parts |

## FX

| Idea | Word | In code | Was | MagicQ | Shows | Status | Key |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A lighting7 FX, anywhere (D19) | FX | the `Fx` prefix: `Fx`, `FxRegistration`, `FxSpawner`, `FxEnvelope`, `FxSpecCoercion`, `FxDto`, `fxType`, `fxMode`, `fxId`, a list's `fx`; columns `fx_type`, `fx_id`, `fx_mode`; tables `look_fx`, `cue_ad_hoc_fx`, `palette_fx`; archive `fx`, `fxType`, `cueAdHocFx/`; MCP `clear_fx`, `active_fx`; REST `/fx/*` already. A field or local holding one FX may keep the plain word (`FxInstance.effect`), as a `template` local holding a palette may. Plain English keeps *effect* ("has no effect", "a side effect"), and special-effects hardware keeps it (Rules) | effect; `Effect`, `EffectRegistration`, `EffectSpawner`, `EffectEnvelope`, `EffectSpecCoercion`, `EffectDto`, `effectType`, `effectMode`, `effect_type`, `effect_id`, `effect_mode`, tables `look_effects`, `cue_ad_hoc_effects`, `template_effects`, archive `effects`, `effectType`, `cueAdHocEffects/`, MCP `clear_effects`, `active_effects` |  | screen, wire, code, storage, MCP | done | `src:components/applied/AppliedFxTable.tsx` |
| A palette that holds one FX (D19) | FX palette | a palette's `kind` `fx` (beside `value`) | Effect palette | FX palette | screen, wire | done | `src:components/looks/LookNameBadge.tsx` |
| Length of one cycle (D1) | Cycle | `cycle`, `CycleLength` (constants in beats and bars; `WHOLE` dropped as a duplicate of `ONE_BAR`); column `cycle`; frontmatter and library `defaultCycle`. `SPEED_STEPS`, `fxSpeedLabel` and the FX table's `speed` column keep the old word | Speed; `beatDivision`, `BeatDivision`, `beat_division`, `defaultBeatDivision` | Speed is a rate | screen, wire, code, storage, archive | done | `src:components/fx/FxFields.tsx` |
| A speed master's rate (D1) | Speed | `bpm` | | | screen, wire | done | `src:components/busking/SideSheet.tsx` |
| Mix of an FX over what is underneath (D2) | Amount | `amount`; column `amount` | Size; `size` | not Size | screen, wire, code, storage, archive | done | `src:components/fx/FxFields.tsx` |
| A cue stack's weight on its FX (D2) | FX amount | `/fx-amount`, `stackFxAmount.*`, MIDI `stackFxAmount` | FX size; `/cue-stacks/{id}/effect-size`, `stackEffectSize.*`, MIDI `stackEffectSize` | Fader Controls FX Size | screen, REST, WebSocket, MIDI | done | `src:components/runner/StackFxAmountControl.tsx` `src:components/surfaces/SurfaceLibrary.tsx` |
| How each head runs its cycle (D3) | Direction | `direction` | | | screen, wire | done | `src:components/fx/FxFields.tsx` |
| Direction: forwards | Forward | `FORWARD` | | | screen, wire | done | `src:components/fx/FxFields.tsx` |
| Direction: backwards | Backward | `BACKWARD` | | | screen, wire | done | `src:components/fx/FxFields.tsx` |
| Direction: there and back | Bounce | `BOUNCE` | | | screen, wire | done | `src:components/fx/FxFields.tsx` |
| The bounce of Colour Fade, Wave's *Focus Pull* and the sweeps, by Direction rather than a parameter (D24) | Bounce | Direction `BOUNCE`; frontmatter and library `defaultDirection` | `pingPong` | | FX parameters, FX frontmatter | done | `src:components/fx/FxFields.tsx` |
| Cycles to run before handing back (D28) | Runs | `runs`, `runsStartTick`…; column `runs` | `nShot`, `nShotStartTick`, `n_shot` | One Shot | screen, wire, code, storage | done | `src:components/fx/FxFields.tsx` |
| Runs with no count | Forever | `runForever` | | | screen | done | `src:components/fx/FxFields.tsx` |
| Cycle per head or for the whole FX (D22) | Timing: Per cycle \| Per head | `timing` `CYCLE` / `HEAD`, `defaultTiming`; columns `timing`, `default_timing` | Step timing; `stepTiming`, `defaultStepTiming`, `step_timing`, `default_step_timing` | Per Step \| Per Head | screen, wire, code, storage, FX frontmatter | done | `src:components/fx/FxFields.tsx` |
| Blend: take the FX's value | Override | `OVERRIDE` | | | screen, wire | done | `BLEND_MODE_OPTIONS.OVERRIDE` |
| Blend: add to what is underneath | Add | `ADDITIVE` | | | screen | done | `BLEND_MODE_OPTIONS.ADDITIVE` |
| Blend: scale what is underneath | Multiply | `MULTIPLY` | | | screen, wire | done | `BLEND_MODE_OPTIONS.MULTIPLY` |
| Blend: the higher of the two | Max | `MAX` | | | screen, wire | done | `BLEND_MODE_OPTIONS.MAX` |
| Blend: the lower of the two | Min | `MIN` | | | screen, wire | done | `BLEND_MODE_OPTIONS.MIN` |
| A movement about the position underneath | Around current position | `ADDITIVE`, centre at 128 | | | screen | done | `src:components/fx/FxFields.tsx` |
| A movement at its own pan and tilt | Absolute | `OVERRIDE` | | | screen | done | `src:components/fx/FxFields.tsx` |
| A level FX that ignores the level underneath | Replace it | `OVERRIDE` | | | screen | done | `src:components/fx/FxFields.tsx` |
| A level FX scaled by the level underneath | Within it | `MULTIPLY` | | | screen | done | `src:components/fx/FxFields.tsx` |
| What an FX's spread counts where it lands on fixtures' elements | Over | `elementMode`; column `element_mode` | Element mode | Dup · Include · Use Elements | screen | done | `src:components/fx/FxFields.tsx` |
| Each fixture is one unit, its elements alike; a new FX's default | Fixtures | `WHOLE` | | Dup Elements | screen, wire, storage, archive | done | `ELEMENT_MODE_OPTIONS.WHOLE` |
| Every element in one strip | Elements | `FLAT` | Flat | Include Elements | screen, wire | done | `ELEMENT_MODE_OPTIONS.FLAT` |
| Each fixture runs the FX for itself; a stored FX's default | Each fixture | `PER_FIXTURE` | Per Fixture | Use Elements | screen, wire | done | `ELEMENT_MODE_OPTIONS.PER_FIXTURE` |

## Spread

| Idea | Word | In code | Was | MagicQ | Shows | Status | Key |
| --- | --- | --- | --- | --- | --- | --- | --- |
| How the heads' offsets are ordered (D3) | Order | `order`, `SpreadOrder`; column `spread_order` (`ORDER` is an SQL keyword). The offsets an Order gives keep `distribution` (`DistributionPlan`, `FxContext.distributionOffset`) | Distribution, distribution strategy; `distributionStrategy`, `DistributionStrategy`, `distribution` |  | screen, wire, code, storage, archive | done | `src:components/fx/FxFields.tsx` |
| Order: member order (D8) | Normal | `NORMAL` | Rig; `LINEAR` | Normal | screen, wire, code | done | `ORDER_LABELS.NORMAL` |
| Order: member order backwards | Reverse | `REVERSE` | | | screen, wire | done | `ORDER_LABELS.REVERSE` |
| Order: from the centre outwards (D20) | Centre out | `CENTRE_OUT` | `CENTER_OUT` | | screen, wire, code | done | `ORDER_LABELS.CENTRE_OUT` |
| Order: from the edges inwards (D18) | Centre in | `CENTRE_IN` | Edges in, Split; `EDGES_IN`, `SPLIT` | Centre In | screen, wire, code | done | `ORDER_LABELS.CENTRE_IN` |
| Order: a random offset per head | Random | `RANDOM` | | | screen, wire | done | `ORDER_LABELS.RANDOM` |
| Order: there and back across the heads | Ping-pong | `PING_PONG` | | | screen, wire | done | `ORDER_LABELS.PING_PONG` |
| Order: by where each fixture hangs | Position | `POSITIONAL` | | | screen | done | `ORDER_LABELS.POSITIONAL` |
| Order: every head in step | Unified | `UNIFIED` | | | screen, wire | done | `ORDER_LABELS.UNIFIED` |
| How much of the cycle the heads cover, as a percentage | Spread | `spread` | | | screen, wire | done | `src:components/fx/FxFields.tsx` |
| Mirrored repeats of the spread (D5) | Wings | `wings` | | | screen, wire | done | `src:components/fx/FxFields.tsx` |
| The spread repeats every N heads (D4) | Parts | `parts`; column `spread_parts` | Groups; `groups`, `spread_groups` | Parts | screen, wire, code, storage, archive | done | `src:components/fx/FxFields.tsx` |
| N neighbours share one offset (D4) | Segments | `segments`; column `spread_segments` | Blocks; `blocks`, `spread_blocks` | Segments | screen, wire, code, storage, archive | done | `src:components/fx/FxFields.tsx` |

## Fan

| Idea | Word | In code | Was | MagicQ | Shows | Status | Key |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Laying values across the selection (D6) | Fan | `POST /programmer/fan`; the client's `Spread*` names are `FU-FE-FAN-NAMES` | Spread; `POST /programmer/spread` | Fan | screen, REST, MCP | done | `src:components/editor/SpreadPanel.tsx` |
| Fan from two values, or about each head's own position (D6) | Between two values \| About each position | `mode: BETWEEN` / `ABOUT` | Spread / Fan; `mode: FAN` | | screen, REST | done | `src:components/editor/SpreadPanel.tsx` |
| Fan curve: first to last | Line | `LINE` | | | screen, REST | done | `src:lib/spreadIntent.ts` |
| Fan curve: centre to both ends | Mirror | `MIRROR` | | | screen, REST | done | `src:lib/spreadIntent.ts` |
| Fan curve: both ends to the centre | Arrow | `ARROW` | | | screen, REST | done | `src:lib/spreadIntent.ts` |
| Fan curve: mirrored outwards (D6) | Mirror out | `MIRROR_OUT` | Wings; `WINGS` |  | screen, REST | done | `src:lib/spreadIntent.ts` |
| Contiguous fans across the selection (D6) | Runs | `runs` | Parts; `parts` |  | screen, REST | done | `src:components/editor/SpreadPanel.tsx` |

## Families and attributes

| Idea | Word | In code | Was | MagicQ | Shows | Status | Key |
| --- | --- | --- | --- | --- | --- | --- | --- |
| The Intensity / Position / Colour / Beam split (D25) | Family | `family`, `families`, `AttributeFamily` (Kotlin and TypeScript), `familyForAttribute`, `attributeFamilyForFxFamily`, `PALETTE_FX_FAMILIES`, `masterFamilyLabel`; column `family`. `AttributeCategory` and its `category` name an attribute's role (Dimmer, Pan, Gobo), finer than its family, and keep the word. *usage* meaning use (memory usage) is a plain word | category, usage, mask group; `category`, `usage`, `PropertyMaskGroup`, `maskGroup`, `maskGroupForProperty`, `maskGroupForFxFamily`, `MASK_GROUPS`, `TEMPLATE_EFFECT_FAMILIES`, `usageLabel`, `usageOptionLabel`, `USAGE_NONE` | | screen, wire, code, storage | done | `src:components/speedMasters/SpeedMasterSheet.tsx` |
| Family: intensity (D25) | Intensity | `intensity` | Dimmer; `dimmer` | Intensity | screen, wire, storage, archive | done | `family:intensity` `masterFamily:intensity` `FX_FAMILY_INFO.intensity` |
| Family: colour | Colour | `colour` | | Colour | screen, wire, storage | done | `family:colour` `masterFamily:colour` `FX_FAMILY_INFO.colour` |
| Family: position | Position | `position` | | Position | screen, wire, storage | done | `family:position` `masterFamily:position` `FX_FAMILY_INFO.position` |
| Family: beam | Beam | `beam` | | Beam | screen, wire, storage | done | `family:beam` `masterFamily:beam` `FX_FAMILY_INFO.beam` |
| Family: FX that step through a list (D23) | Steps | `steps` | Palette steps; `palette`, and Slot Steps under `beam` | | screen, wire, storage | done | `family:steps` `FX_FAMILY_INFO.steps` |
| Family: FX on settings and sliders | Controls | `controls` | | | screen | done | `FX_FAMILY_INFO.controls` |
| Lightning Strike's family, the composite mode removed (D27) | Intensity | Lightning Strike `STANDARD`, family `intensity` | Composite; `composite`, `effectMode: COMPOSITE`, `CompositeEffect`, `FX_CALC_COMPOSITE` | | FX library | done | `FX_FAMILY_INFO.intensity` |
| A controllable part of a fixture (D26) | Attribute | `@FixtureAttribute`, `attribute`, `attributes`, `attributeMask`, `attributeAssignments`, `fixture/attribute/`, `AttributeValue`, `AttributeCategory`, `*AttributeDescriptor`; MIDI `fixtureAttribute`, `groupAttribute`, `selectionAttribute`; REST `/groups/{name}/attributes`; columns `attribute`, `attribute_mask`; table `cue_attribute_assignments`. Every name for one says attribute, locals and parameters included. `Slider`, `Colour`, `Position`, `Strobe`, `WhiteMix` and `Aggregate*` are kinds of control and keep their names; Kotlin reflection (`KProperty1`, `classProperty`), JSON Schema's `properties`, CSS properties, JVM system properties and other libraries' words keep theirs | Property; `@FixtureProperty`, `propertyName`, `properties`, `propertyMask`, `propertyAssignments`, `fixture/property/`, `PropertyValue`, `PropertyCategory`, `*PropertyDescriptor`, MIDI `fixtureProperty`, `groupProperty`, `selectionProperty`, `/groups/{name}/properties`, `property_name`, `property_mask`, `cue_property_assignments` | Attribute | screen, wire, code, storage, archive, MCP, MIDI | done | `src:components/applied/AppliedFxTable.tsx` |

## The FX library

| Idea | Word | In code | Was | MagicQ | Shows | Status | Key |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Intensity and beam, the level waves as one FX (fx-settings plan D2) | Wave | `Wave`, family `intensity`, on `dimmer`, `uv`, `zoom`, `iris`, `frost`, `focus` | Sine Wave, Ramp Up, Ramp Down, Ramp, Square Wave, Pulse, Dimmer Strobe, Zoom Wave, Iris Pulse, Frost Pulse, Focus Pull, Zoom Breathe; `SineWave`, `RampUp`, `RampDown`, `Ramp`, `SquareWave`, `Pulse`, `DimmerStrobe`, `ZoomWave`, `IrisPulse`, `FrostPulse`, `FocusPull`, `ZoomBreathe` | | FX library | done | `fx:Wave` |
| Wave's shape | Sine | `shape` `SINE` | Sine Wave, Zoom Wave, Zoom Breathe; `SineWave`, `ZoomWave`, `ZoomBreathe` | | FX editor | done | `doc:src/main/resources/fx/dimmer/Wave.fx.kts` |
| Wave's shape | Ramp up | `RAMP_UP` | Ramp Up, Focus Pull; `RampUp`, `FocusPull` | Ramp Up | FX editor | done | `doc:src/main/resources/fx/dimmer/Wave.fx.kts` |
| Wave's shape | Ramp down | `RAMP_DOWN` | Ramp Down; `RampDown` | Ramp Down | FX editor | done | `doc:src/main/resources/fx/dimmer/Wave.fx.kts` |
| Wave's shape, up and down (D10) | Triangle | `TRIANGLE` | Ramp, Triangle; `Ramp`, `Triangle` | Ramp | FX editor | done | `doc:src/main/resources/fx/dimmer/Wave.fx.kts` |
| Wave's shape | Square | `SQUARE` | Square Wave, Dimmer Strobe, Strobe; `SquareWave`, `DimmerStrobe`, `Strobe` | | FX editor | done | `doc:src/main/resources/fx/dimmer/Wave.fx.kts` |
| Wave's shape | Pulse | `PULSE` | Pulse, Iris Pulse, Frost Pulse; `Pulse`, `IrisPulse`, `FrostPulse` | | FX editor | done | `doc:src/main/resources/fx/dimmer/Wave.fx.kts` |
| The bottom of a level FX (fx-settings plan D2) | Low | `low` | Min, Off value, Start; `min`, `offValue`, `start` | | FX editor, FX parameters | done | `doc:src/main/resources/fx/dimmer/Wave.fx.kts` |
| The top of a level FX | High | `high` | Max, On value, End; `max`, `onValue`, `end` | | FX editor, FX parameters | done | `doc:src/main/resources/fx/dimmer/Wave.fx.kts` |
| How much of the cycle a Square is high | Width | `width` | Duty cycle, On ratio; `dutyCycle`, `onRatio` | | FX editor, FX parameters | done | `doc:src/main/resources/fx/dimmer/Wave.fx.kts` |
| A Pulse's rise | Attack | `attack` | `attackRatio` | | FX editor, FX parameters | done | `doc:src/main/resources/fx/dimmer/Wave.fx.kts` |
| A Pulse's hold at the top | Hold | `hold` | `holdRatio` | | FX editor, FX parameters | done | `doc:src/main/resources/fx/dimmer/Wave.fx.kts` |
| A familiar name the picker lists for an FX on one attribute (fx-settings plan D4): Wave's *Strobe*, *Zoom Wave*, *Iris Pulse*, *Frost Pulse*, *Focus Pull* | Preset | frontmatter `presets`, `FxPreset`, the library's `presets` | | | FX picker | done | `doc:docs/fx-engineering.md` |
| Intensity | Flicker | `Flicker` | | | FX library | done | `fx:Flicker` |
| Intensity, what Breathe was (D13): a Wave on Sine a quarter-cycle on | Wave | `Wave` `SINE`, phase offset + 0.75 | Breathe; `Breathe` | | FX library | done | `fx:Wave` |
| Intensity | Candle Flicker | `CandleFlicker` | | | FX library | done | `fx:CandleFlicker` |
| Intensity | Fluorescent Flicker | `FluorescentFlicker` | | | FX library | done | `fx:FluorescentFlicker` |
| Intensity (D27) | Lightning Strike | `LightningStrike` | | | FX library | done | `fx:LightningStrike` |
| Colour | Colour Cycle | `ColourCycle` | | | FX library | done | `fx:ColourCycle` |
| Colour | Rainbow Cycle | `RainbowCycle` | | | FX library | done | `fx:RainbowCycle` |
| Colour | Colour Strobe | `ColourStrobe` | | | FX library | done | `fx:ColourStrobe` |
| Colour (D11) | Colour Wave | `ColourWave` | Colour Pulse; `ColourPulse` | | FX library | done | `fx:ColourWave` |
| Colour | Colour Fade | `ColourFade` | | | FX library | done | `fx:ColourFade` |
| Colour | Colour Flicker | `ColourFlicker` | | | FX library | done | `fx:ColourFlicker` |
| Position | Circle | `Circle` | | | FX library | done | `fx:Circle` |
| Position | Figure 8 | `Figure8` | | | FX library | done | `fx:Figure8` |
| Position | Sweep | `Sweep` | | | FX library | done | `fx:Sweep` |
| Position | Pan Sweep | `PanSweep` | | | FX library | done | `fx:PanSweep` |
| Position | Tilt Sweep | `TiltSweep` | | | FX library | done | `fx:TiltSweep` |
| Position | Random Position | `RandomPosition` | | | FX library | done | `fx:RandomPosition` |
| Steps, each palette on each head for itself (D23) | Palette Steps | `PaletteSteps` | | 2col, 3col, 4col | FX library | done | `fx:PaletteSteps` |
| Steps, a setting's named slots (D23) | Slot Steps | `SlotSteps` | | | FX library | done | `fx:SlotSteps` |

## The show

| Idea | Word | In code | Was | MagicQ | Shows | Status | Key |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A show or a venue, as the desk stores it (D21) | Project | `ProjectKind` `SHOW` / `VENUE` / `VENUE_VERSION`. *Show* stays for a project of kind `SHOW` ("a show on a venue") and for the running show | | | screen, wire, code, docs | done | `src:navigation.ts` |
| A stored state of the rig | Look | | | Look | screen, wire | done | `src:components/looks/LookSheet.tsx` |
| One step of a show | Cue | | | Cue | screen, wire | done | `src:components/runner/CueSheet.tsx` |
| An ordered list of cues run with GO | Cue stack | | | Cue stack | screen, wire | done | `src:components/promptbook/CueStackPanel.tsx` |
| Values being set by hand, above every cue | Programmer | | | Programmer | screen, wire | done | `src:lib/windowViews.ts` |
| A channel held at a level whatever else sends | Park | | | Park | screen, wire | done | `src:components/channels/DmxSheet.tsx` |
| Heads to their known point | Locate | | | Locate | screen, wire | done | `src:components/patches/LocateBox.tsx` |
| Programming without sending | Blind | | | Blind | screen, wire | done | `src:components/programmer/ProgrammerActionBar.tsx` |
| The programmer into a cue or a Look | Record | | | Record | screen, wire | done | `src:components/programmer/ProgrammerActionBar.tsx` |
| A cue or a Look into the programmer | Include | | | Include | screen, wire | done | `src:components/looks/LookSheet.tsx` |
| The programmer back into what it included | Update | | | Update | screen, wire | done | `src:components/programmer/ProgrammerSourceStrip.tsx` |
| A tempo bus that FX follow | Speed master | | | | screen, wire | done | `src:components/SpeedMasters.tsx` |
| A stored palette of values or one FX | Palette | `Palette*`, `pal:{uuid}`; tables `palettes`, `palette_rows`, `palette_fx`; column `palette_id`; archive `palettes/`, `paletteUuid`; MIDI `pressPalette`. A local, parameter or test helper holding one may keep `template`. *Template* stays for a script's compile template, `set_scene`'s scene `template` and the `components/templates/` folder | template; `Template*`, `tmpl:{uuid}`, tables `templates`, `template_rows`, `template_effects`, `template_id`, archive `templates/`, `templateUuid`, MIDI `pressTemplate` |  | screen, wire, code, storage, archive, MIDI | done | `src:navigation.ts` `src:lib/windowViews.ts` |
| One of the five places the output is composed from (D9) | Tier | | Layer 1–5 | | docs | done | `doc:docs/lighting-composition-model.md` |
| A part of a multi-head fixture; *head* means a fixture and its head number (D7) | Element | element keys `.head-N`, `.pixel-N`, `.cell-N` unchanged (D29); the stored busk rig's `cellMode` and `cellSplit`, the Fan's `over: CELLS`, the MIDI `SelectionCells` binding, `list_fixture_types`' `cellAttributes` and `subselect`'s cell counting keep *cell*, since renaming them would move stored or wire names. A table's or the programmer grid's cell is a plain word | Cell, head; the *Cells* chip | Element | screen, docs | done | `src:components/busking/RigBand.tsx` |
| lighting7's own identifiers in UK spelling (D20) | Centre | `CENTRE_*`, `panCentre`, `tiltCentre` | `CENTER_*`, `panCenter`, `tiltCenter` | | wire, code, storage, archive | done | `src:components/fx/FxFields.tsx` |

## Rules

- **Palette references in colour FX (D30).** A colour parameter may name a generic colour palette,
  `pal:{uuid}` (`tmpl:{uuid}` before the rename). It resolves to one colour when the FX starts, the same
  for every head. Colour Fade, Colour Wave, Colour Strobe and Colour Flicker keep these references.
  Colour Cycle takes literal colours only (D14), and every write boundary refuses a palette in its
  list; Palette Steps is the FX that lands each palette on each head for itself.
- **Layer is always qualified (D9):** a cue layer or a programmer layer, never one of the five tiers.
- **Element keys stay (D29).** `.head-N`, `.pixel-N`, `.cell-N` and the rest name the physical part;
  *Element* is what the screen calls it.
- **Effect tubes** (`effect_tube_state`, the confetti cannon) are special-effects hardware, not FX,
  and keep their name, as do the names that arm and fire them: `remote_access_settings.allow_effects`
  and its `allowEffects`, `/projects/{id}/effects/*` and the `effects.armed`, `effects.fired` and
  `effects.skipped` frames. So does the fixture kind `EFFECT`, a hazer's or a fogger's.
- **`/fx/*` REST paths** already say FX and stay.
- **No hint text (D16).** No control gains a hint for this plan, and none names another desk.
