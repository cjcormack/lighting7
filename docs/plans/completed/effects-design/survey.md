# The effects survey — four desks from their own manuals

Read on 2026-10-15 for design session R4 of [`../venue-visit-plan.md`](../venue-visit-plan.md). Each
desk was read from its manufacturer's current online manual and nothing else; search engines were used
only to find the pages. Where a manual is silent or contradicts itself it says so. The plan's §1 is the
summary; this file is the evidence.

## ChamSys MagicQ (manual v1.9.9.x)

Pages, under `https://docs.chamsys.co.uk/magicq/1.9.9.x/manual/`:
[ENG] `FX_engine.html`, [EDT] `FX_editor.html`, [BUSK] `busking.html`,
[CSS] `cue_stack_settings.html`, [PB] `playback.html`, [ACS] `advanced_cue_stacks.html`.

**Families and attributes.** The ADD FX picker has Intensity, Position, Colour, Beam, Any Attrib, Pix
Map, User and Old [ENG#adding-in-fx]. *Any Attrib* holds generic waveforms (Ramp, Sine, Cosine) and asks
which attribute to drive; *Pix Map* FX "can be applied to any attribute or to Palettes". So zoom, iris,
focus, gobo and prism are all reachable through a generic waveform; dedicated built-ins are named only
for iris (iris chase, 2iris) and focus (2focus). Colour FX are flagged *Auto Invert RGB* so one FX runs
on CMY and RGB heads. Several FX on one head must drive different attributes [ENG#applying-the-fx].

**Parameters** [ENG unless marked]:

| MagicQ | What it does |
|---|---|
| Speed | the rate; its button stops/starts the FX. Units not stated (masters are BPM) |
| Size | 0–100 %, SHIFT oversizes to 800 %; its button toggles size ↔ zero |
| Crossfade | 0 % snaps between steps, 100 % fades (the editor calls it *contrast*) |
| Attack / Decay | skews the crossfade: fade in & snap out, or the reverse |
| Width | duty cycle for 1–2 step waveforms |
| Spread | maximum offset between heads: 100 % even round the cycle, 0 % in sync, over 100 % allowed |
| Parts | repeat the cycle every N heads |
| Segments | N adjacent heads share one offset |
| Chunks | split the selection into N blocks (pairs with Centre In/Out) |
| Direction | Forward, Backward, Centre In, Centre Out, Rnd, Odd/Even, Even/Odd, Left/Right, Right/Left, Segments; each with an *Invert* (flips polarity); Bounce variants since 1.9.8 |
| Add mode | Normal (± about the base), Plus (never below), Minus (never above), Abs (ignores the base) |
| Speed type | Run, Stop, Double, Div 2–8, applied after everything else |
| One Shot | one (to four) cycles then removed |
| Timing | per head or per step |
| FX fade / delay | FX fade in/out times, and delay start or delay size per head |

**Timing** [BUSK#speed-masters, PB#speed-masters]. 100 speed masters, each a BPM and a divisor
(/32…×32), each optionally limited to Intensity, Position, Colour or Beam. A stack defaults to master 1;
an FX may name its own. Tapping phase-syncs every FX on the master. Programmer FX ignore masters by
default.

**Many fixtures.** Selection order matters, and REORDER FX offers Normal, Reverse, Into Centre, Centre
Out, Random [ENG#fx-reordering]. Group FX reference the group, so adding a head updates every cue
[ENG#group-based-fx]. There is no *wings* parameter: mirroring is Direction's (Odd/Even, Left/Right,
Centre In/Out and their Inverts).

**Live editing.** Select heads, ADD FX, adjust with encoders; the FX window's speed, size, crossfade and
offset encoders act on every FX on the selected heads [ENG#modifying-fx-for-selected-heads]. CHANGE FX
swaps the waveform keeping speed, size and offsets. FX palettes (since 1.9.5) push their overall
parameters into linked cues [ENG#fx-palettes]. A playback fader can be set to *Controls FX Size* or
*Controls FX Speed* [CSS].

## MA Lighting grandMA3 (manual 2.4 / 2.5)

Pages, under `https://help.malighting.com/grandMA3/`: [PH] `2.4/HTML/phaser.html`,
[PE] `2.4/HTML/phaser_editor.html`, [F24] `2.4/HTML/rn_features-2-4.html`, [SH] `2.5/HTML/shapes.html`,
[QS] `2.5/HTML/qsg_phasers.html`, [SM] `2.5/HTML/masters_speed.html`, [EX] `2.5/HTML/executor_assign.html`,
[MX] `2.4/HTML/matricks.html`, [MW] `2.4/HTML/matricks_wings.html`, [MG] `2.4/HTML/matricks_group.html`,
[MB] `2.4/HTML/matricks_block.html`, [CP] `2.5/HTML/phaser_create_circle_preset.html`.

**Model** [PH][QS]. A *phaser* is a value with two or more steps played in a loop; there are no waveform
objects — a sine dimmer is two steps with smooth transitions. Steps hold absolute values, relative values
or both, so a relative circle preset rides on any absolute position [CP]. Per-step layers: **Width**
(percent of a beat to the next step), **Transition** (share of the width spent moving), **Accel / Decel**.
Per-attribute layers: **Speed** (BPM, Hz or seconds), **SpeedMaster**, **Phase** (degrees), **Measure**
(beats per loop). 2.4 added **NShot**, **Direction** Forward / Backward / Alternate, a **Curve**, and
grid-adaptive Measure and Width [F24]; a Shapes pool of 22 generates phasers [SH]. The manual gives no
restricted attribute list; its examples are dimmer, colour and position. Whether zoom or a wheel slot
behaves differently is not stated.

**Many fixtures.** Phase takes a range, "0 thru 360", spread in selection order [QS]. **MAtricks**
reshapes the selection first [MX]: **Groups** deal fixtures round-robin [MG], **Blocks** make neighbours
one unit [MB], **Wings** split the selection and pick from opposite ends of each, so a step runs from the
ends to the centre [MW], plus Width, Shuffle (a seeded random order) and Shift.

**Timing** [SM][EX]. 16 speed masters (0–225 BPM); master 16 follows audio; a Speed Scale multiplies or
divides; tap is *Learn*. An executor's Speed fader scales the phasers in its cue; Rate with *Speed from
Rate* scales them too. The manual describes no fader for phaser size.

**Live** [PE]. The Phaser Editor edits the running programmer phaser: 2D and 1D views, Change Size,
Rotation, Phase, Width, Select Form (Rectangle, Sawtooth, Sine, Circle), Mirror X/Y and **Mirror Time**
(reverses it). Encoder bars carry Speed, Phase, Transition, Width and Measure. Phasers store into presets
and cues like any value; *recipes* cook a shape and MAtricks into a preset or cue part.

## ETC Eos (manual v3.3.10)

Pages, under `https://www.etcconnect.com/WebDocs/Controls/EosFamilyOnlineHelp/en/Content/14_Effects/`:
[A] `About_Effects.htm`, [ED] `01_Effects_Editor/About_the_Effects_Editor.htm`, [ST] `Step_Effects.htm`,
[AB] `Absolute_Effects.htm`, [REL] `Relative_Effects.htm`, [ESD] `Effect_Status_[Tab_6].htm`,
[LIVE] `Editing_Effects_Live.htm`, [BPM] `Beats_Per_Minute.htm`, [RP] `Recording_Effect_Preset.htm`,
[RSUB] `02_Effects_on_Faders/Running_Effect_From_Sub.htm`, [GFX] `02_Effects_on_Faders/Global_Effects_Fader.htm`.

**Types** [A]. *Step* effects are chases of on/off states with step, in, dwell and decay times [ST].
*Absolute* effects are sequences of actions, each a level or a palette [AB]. *Relative* effects are a
continuous offset from the current value: *Focus* (pan/tilt), *Color* (hue/saturation offsets) and
*Linear*, which "can be applied to any parameter" [REL]. Shapes are drawn, not chosen from a named
waveform list. 917/918 are the rainbows.

**Parameters** [ED]. Scale (relative size as a % of the programmed value), Cycle Time, Duration or Num
Cycles; Forward / Reverse, Bounce, **Bounce Channels**; Random Group and Random Rate; Positive/Negative
(inverts a step effect's states); **Entry modes** Cascade, Immediate, Fade by Size, Fade by Rate, Fade by
Size and Rate, and **Exit modes** Fade by Size, Fade by Rate, both, Stop and Fade, Stop and Hold, each
with an **Entry/Exit time** defaulting to the cue's; **Grouping** (every Nth light moves together) and
**Trail** (Even, 0–200 % of cycle time, or Solo). No *wings*, *offset* or *centre out*: distribution is
selection order, Grouping, Trail and Bounce Channels.

**Live** [ESD][LIVE]. Effect Status edits Rate and Size (0–2000 %), Shape and Axis on the running
instance as a **cue-level override**, colour-coded; `{Edit}` changes the effect everywhere. Effect
Channels holds per-channel overrides. Tempo is per effect: `{BPM}`, tap with `[Learn][Time]` averaging
three taps [BPM]. No shared tempo bus. A preset records an effect *copied*, not referenced [RP]. A sub's
fader runs size or rate by its Entry mode [RSUB]; a Global Effects fader scales rate or size by filter,
and several multiply [GFX].

## High End Hog 4 (manual v3.15)

Pages, under `https://www.etcconnect.com/webdocs/Controls/HOG/HTML/en/`: [TE] `sect-table_effects.htm`,
[EP] `sect-effect_palettes.htm`, [FAN] `sect-fanning.htm`, [SO] `sect-selection_order.htm`,
[AP] `sect-advanced_playback.htm`, [PM] `sect-physical_masters.htm`.

**Model** [TE]. Table effects — "wave tables that apply mathematical functions … to values against
time" — on any fixture function: Sine, Tangent, Step, Sawtooth, Ramp, Inverse Ramp, Mark On, Mark Off,
Spiral, Random 1–3. Effects are attributes on the same grid as values, per head and per function. The
manual shows position, slotted colour (in slots), strobe (in Hz), CMY and iris; zoom, gobo and prism are
not named but Beam is an engine row group.

**Parameters** [TE]: **Size** in the parameter's own units (degrees, slots, Hz); **Rate** in cycles a
minute, and **Beats** against a master's tapped tempo; **Offset** in degrees; **Length** (the active
share of the period); **N Shot**; **Direction** forward/reverse and **Bounce**; Sync; and **effect fade,
delay and path**, separate from the value's own, so "the effect movement changes during a cue".

**Many fixtures.** Offsets are fanned across the selection in selection order [TE][SO]: Fan Normal,
From Start, From End, To Centre, multi-part fans, Segments and Buddying [FAN]. An **Effect Spread** wheel
spreads offsets 0–360° at 100 %, negative to reverse; it is a front-end tool and not stored [TE].

**Timing and masters** [AP][PM]. Each master has a base effect rate and size; a fader or button set to
*Scale Effect Rate* or *Scale Effect Size* scales them. Effect tap tempo (v3.15) taps a master's base
tempo. No grand rate master.

**Palettes** [EP]. `1 Thru 5 Effect 1 Enter`; Record Effect stores the effect attributes, and a mask
stores the base too.
