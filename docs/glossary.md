# Glossary

The desk's words, one row per idea: the word the screen, the wire, the code and the archive use for
it once its row is `done`, what it was called before, and MagicQ's word where the plan names one.
The words were agreed in [`plans/terminology-plan.md`](plans/terminology-plan.md); the D-numbers in
the Idea column are its decisions.

Write a `done` row's word everywhere. Write a `planned` row's word in docs and commit messages that
describe the plan; in code, and to an operator looking at the screen, its *Was* stands until the
session that renames it lands.

**Status.** `done`: the code already uses the word wherever it shows. `planned`: a rename still to
land, and *Was* is what the code says today. The session that lands a rename flips its rows to `done`
and gives them a key.

**In code.** The identifier each layer uses for the idea: the one §4.3 and §4.4 agree where a rename
moves it, and what the code already says where nothing does. Empty where the idea has no identifier of
its own.

**Key.** Where a `done` row's word lives, as one or more locators; a `planned` row has none.
`GlossaryVocabularyTest` (backend) and `glossary.test.ts` (frontend) read every `done` row and check
that each of its locators gives the row's word. Each locator's prefix says which test reads it, and
both tests refuse a prefix neither knows.

| Locator | Read by | Gives |
|---|---|---|
| `fx:<id>` | backend | the `name:` of the built-in FX `<id>` in `src/main/resources/fx/` |
| `category:<c>` | backend | `<c>`, a `category:` the built-in FX declare, compared without case |
| `usage:<u>` | backend | `<u>`, one of `SPEED_MASTER_USAGES`, compared without case |
| `ORDER_LABELS.<KEY>` | frontend | the label in `lib/orderLabels.ts` |
| `EFFECT_CATEGORY_INFO.<key>`, `BLEND_MODE_OPTIONS.<VALUE>`, `ELEMENT_MODE_OPTIONS.<VALUE>` | frontend | the entry's `label` in `components/fx/fxConstants.ts` |
| `src:<path>` | frontend | a string literal or JSX text in `frontend/src/<path>` that is the word, or the word with an *s* |

## FX

| Idea | Word | In code | Was | MagicQ | Shows | Status | Key |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A lighting7 FX, anywhere (D19) | FX | the `Fx` prefix: `FxRegistration`, `FxSpawner`, `FxEnvelope`, `FxSpecCoercion`, `FxDto`, `fxType`, `fxMode`; columns `fx_type`, `fx_id`, `allow_fx`; tables `look_fx`, `cue_ad_hoc_fx`; MCP `clear_fx`, `active_fx`; REST `/fx/*` already | effect; `EffectRegistration`, `EffectSpawner`, `EffectEnvelope`, `EffectSpecCoercion`, `EffectDto`, `effectType`, `effectMode`, `effect_type`, `effect_id`, `allow_effects`, tables `look_effects`, `cue_ad_hoc_effects`, `template_effects`, MCP `clear_effects`, `active_effects` | | screen, wire, code, storage, MCP | planned | |
| A palette that holds one FX (D19) | FX palette | | Effect palette | FX palette | screen | planned | |
| Length of one cycle (D1) | Cycle | `cycle`, `CycleLength` (constants in beats and bars; `WHOLE` dropped as a duplicate of `ONE_BAR`); column `cycle` | Speed; `beatDivision`, `BeatDivision`, `beat_division` | Speed is a rate | screen, wire, code, storage, archive | planned | |
| A speed master's rate (D1) | Speed | `bpm` | | | screen, wire | done | `src:components/busking/SideSheet.tsx` |
| Mix of an FX over what is underneath (D2) | Amount | `amount`; column `amount` | Size; `size` | not Size | screen, wire, code, storage, archive | planned | |
| A cue stack's weight on its FX (D2) | FX amount | `/fx-amount`, `stackFxAmount.*`, MIDI `stackFxAmount` | FX size; `/cue-stacks/{id}/effect-size`, `stackEffectSize.*`, MIDI `stackEffectSize` | Fader Controls FX Size | screen, REST, WebSocket, MIDI | planned | |
| How each head runs its cycle (D3) | Direction | `direction` | | | screen, wire | done | `src:components/fx/FxEffectFields.tsx` |
| Direction: forwards | Forward | `FORWARD` | | | screen, wire | done | `src:components/fx/FxEffectFields.tsx` |
| Direction: backwards | Backward | `BACKWARD` | | | screen, wire | done | `src:components/fx/FxEffectFields.tsx` |
| Direction: there and back | Bounce | `BOUNCE` | | | screen, wire | done | `src:components/fx/FxEffectFields.tsx` |
| The bounce of Colour Fade, Focus Pull and the sweeps, by Direction rather than a parameter (D24) | Bounce | Direction `BOUNCE`; frontmatter `defaultDirection` | `pingPong` | | FX parameters, FX frontmatter | planned | |
| Cycles to run before handing back (D28) | Runs | `runs`, `runsStartTick`…; column `runs` | `nShot`, `nShotStartTick`, `n_shot` | One Shot | screen, wire, code, storage | planned | |
| Runs with no count | Forever | `runForever` | | | screen | done | `src:components/fx/FxEffectFields.tsx` |
| Cycle per head or for the whole FX (D22) | Timing: Per cycle \| Per head | `timing` `CYCLE` / `HEAD`, `defaultTiming`; columns `timing`, `default_timing` | Step timing; `stepTiming`, `defaultStepTiming`, `step_timing`, `default_step_timing` | Per Step \| Per Head | screen, wire, code, storage, FX frontmatter | planned | |
| Blend: take the FX's value | Override | `OVERRIDE` | | | screen, wire | done | `BLEND_MODE_OPTIONS.OVERRIDE` |
| Blend: add to what is underneath | Add | `ADDITIVE` | | | screen | done | `BLEND_MODE_OPTIONS.ADDITIVE` |
| Blend: scale what is underneath | Multiply | `MULTIPLY` | | | screen, wire | done | `BLEND_MODE_OPTIONS.MULTIPLY` |
| Blend: the higher of the two | Max | `MAX` | | | screen, wire | done | `BLEND_MODE_OPTIONS.MAX` |
| Blend: the lower of the two | Min | `MIN` | | | screen, wire | done | `BLEND_MODE_OPTIONS.MIN` |
| A movement about the position underneath | Around current position | `ADDITIVE`, centre at 128 | | | screen | done | `src:components/fx/FxEffectFields.tsx` |
| A movement at its own pan and tilt | Absolute | `OVERRIDE` | | | screen | done | `src:components/fx/FxEffectFields.tsx` |
| A level FX that ignores the level underneath | Replace it | `OVERRIDE` | | | screen | done | `src:components/fx/FxEffectFields.tsx` |
| A level FX scaled by the level underneath | Within it | `MULTIPLY` | | | screen | done | `src:components/fx/FxEffectFields.tsx` |
| Each fixture runs the FX for itself | Per Fixture | `PER_FIXTURE` | | | screen, wire | done | `ELEMENT_MODE_OPTIONS.PER_FIXTURE` |
| Every element in one strip | Flat | `FLAT` | | | screen, wire | done | `ELEMENT_MODE_OPTIONS.FLAT` |

## Spread

| Idea | Word | In code | Was | MagicQ | Shows | Status | Key |
| --- | --- | --- | --- | --- | --- | --- | --- |
| How the heads' offsets are ordered (D3) | Order | `order`, `SpreadOrder`; column `spread_order` (`ORDER` is an SQL keyword) | Distribution, distribution strategy; `distributionStrategy`, `DistributionStrategy`, `distribution` | | screen, wire, code, storage, archive | planned | |
| Order: member order (D8) | Normal | `NORMAL` | Rig; `LINEAR` | Normal | screen, wire, code | planned | |
| Order: member order backwards | Reverse | `REVERSE` | | | screen, wire | done | `ORDER_LABELS.REVERSE` |
| Order: from the centre outwards (D20) | Centre out | `CENTRE_OUT` | `CENTER_OUT` | | screen, wire, code | planned | |
| Order: from the edges inwards (D18) | Centre in | `CENTRE_IN` | Edges in, Split; `EDGES_IN`, `SPLIT` | Centre In | screen, wire, code | planned | |
| Order: a random offset per head | Random | `RANDOM` | | | screen, wire | done | `ORDER_LABELS.RANDOM` |
| Order: there and back across the heads | Ping-pong | `PING_PONG` | | | screen, wire | done | `ORDER_LABELS.PING_PONG` |
| Order: by where each fixture hangs | Position | `POSITIONAL` | | | screen | done | `ORDER_LABELS.POSITIONAL` |
| Order: every head in step | Unified | `UNIFIED` | | | screen, wire | done | `ORDER_LABELS.UNIFIED` |
| How much of the cycle the heads cover, as a percentage | Spread | `spread` | | | screen, wire | done | `src:components/fx/FxEffectFields.tsx` |
| Mirrored repeats of the spread (D5) | Wings | `wings` | | | screen, wire | done | `src:components/fx/FxEffectFields.tsx` |
| The spread repeats every N heads (D4) | Parts | `parts`; column `spread_parts` | Groups; `groups`, `spread_groups` | Parts | screen, wire, code, storage, archive | planned | |
| N neighbours share one offset (D4) | Segments | `segments`; column `spread_segments` | Blocks; `blocks`, `spread_blocks` | Segments | screen, wire, code, storage, archive | planned | |

## Fan

| Idea | Word | In code | Was | MagicQ | Shows | Status | Key |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Laying values across the selection (D6) | Fan | `POST /programmer/fan` | Spread; `POST /programmer/spread` | Fan | screen, REST, MCP | planned | |
| Fan from two values, or about each head's own position (D6) | Between two values \| About each position | `mode: BETWEEN` / `ABOUT` | Spread / Fan; `mode: FAN` | | screen, REST | planned | |
| Fan curve: first to last | Line | `LINE` | | | screen, REST | done | `src:lib/spreadIntent.ts` |
| Fan curve: centre to both ends | Mirror | `MIRROR` | | | screen, REST | done | `src:lib/spreadIntent.ts` |
| Fan curve: both ends to the centre | Arrow | `ARROW` | | | screen, REST | done | `src:lib/spreadIntent.ts` |
| Fan curve: mirrored outwards (D6) | Mirror out | `MIRROR_OUT` | Wings; `WINGS` | | screen, REST | planned | |
| Contiguous fans across the selection (D6) | Runs | `runs` | Parts; `parts` | | screen, REST | planned | |

## Families and attributes

| Idea | Word | In code | Was | MagicQ | Shows | Status | Key |
| --- | --- | --- | --- | --- | --- | --- | --- |
| The Intensity / Position / Colour / Beam split (D25) | Family | `family`, `AttributeFamily`, `PALETTE_FX_FAMILIES`; column `family` | category, usage, mask group; `category`, `usage`, `PropertyCategory`, `TEMPLATE_EFFECT_FAMILIES` | | screen, wire, code, storage | planned | |
| Family: intensity (D25) | Intensity | `intensity` | Dimmer; `dimmer` | Intensity | screen, wire, storage, archive | planned | |
| Family: colour | Colour | `colour` | | Colour | screen, wire, storage | done | `category:colour` `usage:colour` `EFFECT_CATEGORY_INFO.colour` |
| Family: position | Position | `position` | | Position | screen, wire, storage | done | `category:position` `usage:position` `EFFECT_CATEGORY_INFO.position` |
| Family: beam | Beam | `beam` | | Beam | screen, wire, storage | done | `category:beam` `usage:beam` `EFFECT_CATEGORY_INFO.beam` |
| Family: FX that step through a list (D23) | Steps | `steps` | Palette steps; `palette`, and Slot Steps under `beam` | | screen, wire | planned | |
| Family: FX on settings and sliders | Controls | `controls` | | | screen | done | `EFFECT_CATEGORY_INFO.controls` |
| Composite FX, removed (D27) | none: Lightning Strike is an Intensity FX | Lightning Strike `STANDARD`, family `intensity` | Composite; `composite`, `effectMode: COMPOSITE` | | FX library | planned | |
| A controllable part of a fixture (D26) | Attribute | `@FixtureAttribute`, `attribute`, `attributes`, `fixture/attribute/`; columns `attribute`, `attribute_mask`; table `cue_attribute_assignments` | Property; `@FixtureProperty`, `propertyName`, `properties`, `fixture/property/`, `property_name`, `property_mask`, `cue_property_assignments` | Attribute | screen, wire, code, storage | planned | |

## The FX library

| Idea | Word | In code | Was | MagicQ | Shows | Status | Key |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Intensity | Sine Wave | `SineWave` | | | FX library | done | `fx:SineWave` |
| Intensity | Ramp Up | `RampUp` | | Ramp Up | FX library | done | `fx:RampUp` |
| Intensity | Ramp Down | `RampDown` | | Ramp Down | FX library | done | `fx:RampDown` |
| Intensity, up and down (D10) | Ramp | `Ramp` | Triangle; `Triangle` | Ramp | FX library | planned | |
| Intensity | Pulse | `Pulse` | | | FX library | done | `fx:Pulse` |
| Intensity | Square Wave | `SquareWave` | | | FX library | done | `fx:SquareWave` |
| Intensity, on the dimmer (D12) | Dimmer Strobe | `DimmerStrobe` | Strobe; `Strobe` | | FX library | planned | |
| Intensity | Flicker | `Flicker` | | | FX library | done | `fx:Flicker` |
| Intensity, removed (D13) | none: Sine Wave a quarter-cycle on | `SineWave`, phase offset + 0.75 | Breathe; `Breathe` | | FX library | planned | |
| Intensity | Candle Flicker | `CandleFlicker` | | | FX library | done | `fx:CandleFlicker` |
| Intensity | Fluorescent Flicker | `FluorescentFlicker` | | | FX library | done | `fx:FluorescentFlicker` |
| Composite, Intensity once D27 lands | Lightning Strike | `LightningStrike` | | | FX library | done | `fx:LightningStrike` |
| Colour | Colour Cycle | `ColourCycle` | | | FX library | done | `fx:ColourCycle` |
| Colour | Rainbow Cycle | `RainbowCycle` | | | FX library | done | `fx:RainbowCycle` |
| Colour | Colour Strobe | `ColourStrobe` | | | FX library | done | `fx:ColourStrobe` |
| Colour (D11) | Colour Wave | `ColourWave` | Colour Pulse; `ColourPulse` | | FX library | planned | |
| Colour | Colour Fade | `ColourFade` | | | FX library | done | `fx:ColourFade` |
| Colour | Colour Flicker | `ColourFlicker` | | | FX library | done | `fx:ColourFlicker` |
| Position | Circle | `Circle` | | | FX library | done | `fx:Circle` |
| Position | Figure 8 | `Figure8` | | | FX library | done | `fx:Figure8` |
| Position | Sweep | `Sweep` | | | FX library | done | `fx:Sweep` |
| Position | Pan Sweep | `PanSweep` | | | FX library | done | `fx:PanSweep` |
| Position | Tilt Sweep | `TiltSweep` | | | FX library | done | `fx:TiltSweep` |
| Position | Random Position | `RandomPosition` | | | FX library | done | `fx:RandomPosition` |
| Beam (D13) | Zoom Wave | `ZoomWave` | Zoom Breathe; `ZoomBreathe` | | FX library | planned | |
| Beam | Iris Pulse | `IrisPulse` | | | FX library | done | `fx:IrisPulse` |
| Beam | Frost Pulse | `FrostPulse` | | | FX library | done | `fx:FrostPulse` |
| Beam | Focus Pull | `FocusPull` | | | FX library | done | `fx:FocusPull` |
| Steps, each palette on each head for itself (D23) | Palette Steps | `PaletteSteps` | | 2col, 3col, 4col | FX library | done | `fx:PaletteSteps` |
| Steps, a setting's named slots (D23) | Slot Steps | `SlotSteps` | | | FX library | done | `fx:SlotSteps` |

## The show

| Idea | Word | In code | Was | MagicQ | Shows | Status | Key |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A show or a venue, as the desk stores it (D21) | Project | | | | screen, wire, code | done | `src:navigation.ts` |
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
| A stored palette of values or one FX | Palette | `Palette*`, `pal:{uuid}`; tables `palettes`, `palette_rows`, `palette_fx`; column `palette_id`; archive `palettes/`; MIDI `pressPalette` | template; `Template*`, `tmpl:{uuid}`, tables `templates`, `template_rows`, `template_id`, archive `templates/`, MIDI `pressTemplate` | | screen, wire, code, storage, archive, MIDI | planned | |
| One of the five places the output is composed from (D9) | Tier | | Layer 1–5 | | docs | planned | |
| A part of a multi-head fixture; *head* means a fixture and its head number (D7) | Element | element keys `.head-N`, `.pixel-N`, `.cell-N` unchanged (D29) | Cell, head; the *Cells* chip | Element | screen | planned | |
| lighting7's own identifiers in UK spelling (D20) | Centre | `CENTRE_*`, `panCentre`, `tiltCentre` | `CENTER_*`, `panCenter`, `tiltCenter` | | wire, code | planned | |

## Rules

- **Palette references in colour FX (D30).** A colour parameter may name a generic colour palette,
  `tmpl:{uuid}` (`pal:{uuid}` once renamed). It resolves to one colour when the FX starts, the same
  for every head. Colour Fade, Colour Wave, Colour Strobe and Colour Flicker keep these references;
  Colour Cycle takes literal colours only once D14 lands; Palette Steps is the FX that lands each
  palette on each head for itself.
- **Layer is always qualified (D9):** a cue layer or a programmer layer, never one of the five tiers.
- **Element keys stay (D29).** `.head-N`, `.pixel-N`, `.cell-N` and the rest name the physical part;
  *Element* is what the screen calls it.
- **Effect tubes** (`effect_tube_state`, the confetti cannon) are special-effects hardware, not FX,
  and keep their name.
- **`/fx/*` REST paths** already say FX and stay.
- **No hint text (D16).** No control gains a hint for this plan, and none names another desk.
