# Effects — what a theatre and busking desk needs from them

> **Document status: AGREED, 2026-10-15.** This is design session R4 of
> [`venue-visit-plan.md`](venue-visit-plan.md): a survey of what ChamSys MagicQ, grandMA3, ETC Eos and
> Hog 4 offer an operator in an effect, set against our 24 built-ins, and a plan for the gaps that
> matter here. The questions asked and their answers are in §11. The survey, with every manual page
> cited, is the design record's [`effects-design/survey.md`](effects-design/survey.md), beside
> [`effects-design/INDEX.md`](effects-design/INDEX.md). A one-page copy for human readers is
> [`effects-design/effects.html`](effects-design/effects.html), with a live copy at
> <https://claude.ai/artifact/LttskDxQVM8upsDYoBj9P5> (the checked-in file is the authority).
>
> "Palette" is venue-visit D2's word for what the code still calls a template; this plan uses it for
> everything a person reads. Where this plan and R3 ([`apply-model-plan.md`](apply-model-plan.md))
> meet — an effect pressed on a selection — this plan decides what the effect *is* and R3 how the
> press reaches it (D12).

## 1. Context

On the first evening in the hall every effect started at one beat a cycle (S15 and D16 of the
venue-visit plan fix that), there was no way to breathe a zoom, and Colour Cycle could only run one
way round. R4 asked what other desks offer, and which of it a desk like this one should have.

**S15 has landed** as this plan assumed: an effect's timing is a speed master or *Seconds* (D7 of the
venue-visit plan), and a new effect is one bar on its family's `usage` master (D16). What the sessions
below build on: the flag is `seconds` on the same four effect homes they extend (`look_effects`,
`cue_ad_hoc_effects`, a palette's effect, the programmer's instances) and on `LookEffectSpec`,
`EffectEntry` and `LookEffectSettings`; a beat effect on Seconds runs on the **wall-clock loop**
(`fx/EffectTiming.kt`), so a session touching the per-member phase (§3.3) touches both loops'
`PhaseSource` arms, which share one path already; the default master is **stamped at creation** —
by `FxPicker`, the palette editor and the MCP tools — so session 6's "a Beam master takes a new beam
effect by S15's default" needs only `usage` to gain `beam`. Nothing here changes timing. Sessions 1–3
each bump `formatVersion`, each taking its number by venue-visit P5: S11 took 28, S15 29, session 1 30,
S8 31, R1.3 32 and session 2 33.

### 1.1 The survey

Summarised from [`effects-design/survey.md`](effects-design/survey.md), where each claim cites its page.

- **Every desk can drive any attribute.** MagicQ's *Any Attrib* waveforms, grandMA3's per-attribute
  phaser layers, Eos's *Linear* relative effects and Hog's table effects all reach zoom, iris, focus
  and the rest. Wheels are stepped by absolute values or palettes (MagicQ Palette FX, Eos absolute
  effects, Hog's size in slots).
- **Every desk has one Size** (MagicQ 0–800 %, Eos 0–2000 % per instance, Hog in the parameter's own
  units), and every desk puts size or rate on a fader (MagicQ *Fader controls FX size*, Hog *Scale
  Effect Size*, Eos sub entry modes and the Global Effects fader, grandMA3 speed only).
- **Every desk spreads an effect over the selection in selection order**, and lets the operator shape
  the spread: how far (MagicQ Spread, Hog Effect Spread, grandMA3 phase range, Eos Trail), neighbours
  as one (MagicQ Segments, MA Blocks, Hog Buddying), repeats (MagicQ Parts, MA Groups, Eos Grouping)
  and mirrors (MA Wings; MagicQ's Centre and Left/Right directions).
- **Every desk runs the cycle in a direction**: Forward, Backward and Bounce (Hog, Eos, MagicQ since
  1.9.8), Forward, Backward and Alternate (grandMA3 2.4).
- **Three of four fade an effect with the cue**: Eos's Entry and Exit modes (Fade by Size, Fade by
  Rate, both, Stop and Fade…), Hog's effect fade, delay and path, MagicQ's FX fade and delay.
  grandMA3's manual does not say.
- **Every desk can run an effect a set number of times** (MagicQ One Shot, MA NShot, Eos Num Cycles,
  Hog N Shot).
- **The big desks also have** user waveform editors (MagicQ's, the Phaser Editor, Eos's drawn shapes),
  pixel and grid effects (MagicQ Pix Map, MAtricks grids), per-head overrides inside one effect (Eos
  Effect Channels, MagicQ's Advanced view) and effect variables (Eos).

### 1.2 Ours

Read from `main` at `8182be4`, 2026-10-15. Since then session 1 has given every effect a size and made
`intensityMultiplier` the envelope (§3.5), session 2 has shaped the spread, and R1.2 has put a GO on the
hold registry, `fx/CueHolds.kt`; the three bullets that name the old code say so.

- **24 built-ins**, all `.fx.kts` (`src/main/resources/fx/`): eleven level effects, six colour, six
  position, one composite. Each takes literal parameters — `min`/`max`, a radius, a range, a colour
  list — and none declares a range or options (`FxFileLoader`'s `PARAM_KEYS` reads four keys).
- **The engine can already drive zoom.** `FxTargetFactory.resolve` turns any slider name into a
  `SliderTarget` and `requireOutputTypeMatch` checks only the output type, so Sine Wave on `zoom`
  works through REST, a cue or the AI. The picker never offers it: `compatibleEffectsFor`
  (`frontend/src/lib/fxTargetProperties.ts`) matches `compatibleProperties`, every level built-in
  declares `[dimmer, uv]`, and the New definition form writes `[dimmer]`.
- **A setting takes raw bytes.** `SettingTarget` writes the byte, always overriding; there is no
  slot-aware output (`FxOutput` is Slider, Colour or Position).
- **No size.** `FxInstance.intensityMultiplier` exists and nothing sets it; the engine scales the raw
  output by it (`FxEngine.calculateEffectOutput`), which is why it once dropped an `OVERRIDE` effect
  to 0. (Session 1 replaced this with `FxInstance.weight`.)
- **The spread is always the whole cycle.** `LINEAR` is `index / size`. The shaped orders are fixed:
  `CENTER_OUT`, `EDGES_IN` and `SPLIT` (the last two give identical offsets). There are no blocks,
  groups or general wings. (Session 2 added Spread, Wings, Blocks and Groups after the Order,
  `fx/SpreadShape.kt`.)
- **Direction is an order, not a run.** `REVERSE` and `PING_PONG` reorder heads; a waveform runs
  backwards only where its script wrote it (Ramp Down, Colour Fade's and the sweeps' `pingPong`).
  Colour Cycle has none.
- **Effects snap at GO.** `CueStackManager.activateCueInStack` removes the outgoing cue's effects and
  starts the incoming at full (since R1.2, `activateEntry` through `CueHolds.bringOn` and `takeOff`); `CueCrossfadeDriver`'s doc says this "matches Eos / grandMA / Hog 4",
  which the manuals contradict.
- **An effect targets one fixture or one group.** A Local effect over loose fixtures runs them in
  unison; R3 left that to this plan (`FU-FX-SELECTION-DISTRIBUTION`).
- **Effect rows store their fields as columns** — `look_effects`, `cue_ad_hoc_effects`, a palette's
  effect, the programmer's — and `LookEffectSpec` carries them, so a new field on an instance is a
  `formatVersion` bump. A new parameter of one effect is a key in `parameters` and is not.
- **Speed masters are ahead of the field**: a bank with per-family `usage` routing and phase-locked
  follow. The usage vocabulary is pinned to the effect categories (`SpeedMasterUsageVocabularyTest`).
- **One editor carries every effect**: `FxEffectFields` is the live editor's (`FxEditor`), the effect
  palette's (`TemplateEditor`) and so the busk Effects tab's. A parameter added there reaches all
  three.

### 1.3 The gaps, and which matter

| Gap | Verdict |
|---|---|
| Fade with the cue | **Matters most.** A theatre cue that brings in a ripple should grow it. |
| One effect over a selection | **Matters.** It is how every desk spreads an effect. |
| Size, and Size on a fader | **Matters.** What a fade and a fader move. |
| Spread, wings, blocks, groups | **Matters.** Busking bars and pixel fixtures. |
| Direction | **Matters.** Colour Cycle backwards, a ripple the other way. |
| Beam effects | **Matters.** Zoom and iris breathing. |
| Wheel and palette steps | **Matters.** Two gobos or two positions on the beat. |
| N-shot | **Matters.** Lightning, a flash on cue. |
| One Width and Softness name | Partly there; left as a follow-up (§10). |
| Rate scale on the instance | Speed masters cover it; left out. |
| Waveform editor, pixel/grid FX, per-head overrides, effect variables, global FX faders | **Big-desk features; left out** (§9). Scripts are our waveform editor. |

## 2. Decisions

Agreed with Chris on 2026-10-15 (§11):

| # | Decision |
|---|---|
| D1 | **An effect fades by size with the cue.** Over a cue's fade, an effect leaving shrinks to nothing and one arriving grows from nothing. An effect in both cues runs on untouched. |
| D2 | **An effect has a Size, 0–100 %: how much of it reaches the stage over what is underneath.** Stored on effect, Look, cue and palette rows. The cue fade and a fader move it; an Around movement effect's radius scales with it. |
| D3 | **One effect over the selection.** An effect may target an ordered list of fixtures and groups, spread in selection order as a group is: a marquee in grid order, a busk selection in the order pressed. |
| D4 | **The spread is shaped by Spread %, Wings, Blocks and Groups.** |
| D5 | **Direction — Forward, Backward, Bounce — is a field of every running effect**, applied to its phase. |
| D6 | **Beam gets built-ins of its own**: Zoom Breathe, Iris Pulse, Frost Pulse and Focus Pull, each on its own property. Offering the level effects on beam sliders was declined. |
| D7 | **Beam joins the speed masters' `usage`** beside Dimmer, Colour and Position. |
| D8 | **Steps between named things**: *Palette Steps* through a list of value palettes (positions, beam roles, colours), and *Slot Steps* through a setting's option names (gobo and colour wheels), each resolved per head. |
| D9 | **N-shot**: an effect can run N cycles and stop. |
| D10 | **A cue stack has an effect Size, at runtime**: 100 % at load, moved by a MIDI fader, a slider on the stack in the Show view, REST and WS, like the grand master. Nothing stored. |
| D11 | **The editor puts Size and Direction beside Speed**, adds a **Spread** section (order, Spread %, Wings, Blocks, Groups, with a row of phase dials previewing them), and keeps N-shot and phase under Advanced. |
| D12 | **Session 3 (one effect over a selection) lands before R3's session 2**, which then builds its press and split on it. |
| D13 | **The programmer fades effects too**: a pad or palette press, a release and Clear grow or shrink effects over the programmer fade, as they fade values. |

The plan adds its own:

- **P1 — One PR per session**, in §8's order, per CLAUDE.md §"Git workflow". Each session ends with
  `./gradlew test` and `npm run check` green, its engineering-doc paragraphs written, and a done-marker
  in its heading citing its change commit(s), never the merge commit.
  The venue-visit plan's P1 checklist ([`venue-visit-plan.md`](venue-visit-plan.md)) applies
  before each PR.
- **P2 — The desk owns every rule.** Size weighting, direction, the spread's shape, the per-head
  resolution of a step and the fade envelope are server-side, once each. The editor's dial preview
  is the one client copy, of the spread's shape only, and a shared vector pins it to the desk's
  (`src/test/resources/fx/spreadShape.fixture.json`, read by both suites — the
  `focusInverse.fixture.json` precedent).
- **P3 — Size is a weight on the effect's contribution, applied after the blend**:
  `out = before + (blended − before) × w`, per channel, where `w = size × envelope × stackSize`. It
  replaces `intensityMultiplier`'s scaling of the raw output, which is what dropped an `OVERRIDE`
  effect to 0. For an Around effect (`ADDITIVE` about 128) the weight scales the offset, so the radius
  shrinks about the position underneath.
- **P4 — A continuing effect is matched by its Look effect uuid and target.** Since `652128a` a Look's
  effect uuids survive a contents edit, so an incoming cue whose layer runs the same Look effect on the
  same target keeps the running instance, phase intact. Anything else is a leave and an arrival.
  Under holds (R1.2) a match can only run between a cue coming off (its last release) and one coming
  on (its first hold) in one GO. The incoming cue is acquired before the outgoing is released, and
  effects are tagged and removed by cue (`CueHolds.takeOff`), so session 4 must hand the acquire the
  outgoing cue to adopt from. Neither `EffectEntry` nor `FxInstance` carries the Look effect's uuid
  yet.
- **P5 — A stored field is a `formatVersion` bump in the session that adds it** (sessions 1, 2 and 3).
  A parameter of one effect (the step lists) rides `parameters` and is not.
- **P6 — The built-ins keep their shapes.** Ramp Down stays though Ramp Up backward equals it, and the
  `pingPong` parameters stay; no stored effect changes meaning.

## 3. The model

### 3.1 Vocabulary

- **Size** — the effect's weight over what is underneath, 0–100 % (D2).
- **Direction** — Forward, Backward or Bounce: how each head runs its own cycle (D5).
- **Order** — the distribution strategy, renamed in the UI only: which head comes first (Rig, Reverse,
  Centre out, Edges in, Split, Random, Ping-pong, Position, Unified — one vocabulary on every surface
  that names one, `frontend/src/lib/orderLabels.ts`).
- **Spread** — how much of the cycle the heads cover, 0–200 % (D4). 100 % is today's.
- **Blocks** — N heads neighbouring in the Order share one phase: on the rig under Rig, Reverse and
  Position; beside each other in the shuffle under Random, so a Random in blocks is random pairs.
- **Groups** — the spread repeats every N heads.
- **Wings** — the heads split into N wings, alternate wings mirrored.
- **Envelope** — the runtime part of the weight a fade moves, 0–1 (D1, D13).
- **Stack size** — a cue stack's runtime weight on its effects (D10).
- **N-shot** — the number of cycles an effect runs, null for forever (D9).

### 3.2 Stored

| Where | What |
|---|---|
| `look_effects`, `cue_ad_hoc_effects`, a palette's effect, `LookEffectSpec`, the programmer's effects | `size` (double, default 1.0), `direction` (`FORWARD \| BACKWARD \| BOUNCE`, default `FORWARD`), `n_shot` (int, nullable) — session 1 |
| the same | `spread` (double, default 1.0), `wings`, `blocks` (int, default 1), `groups` (int, default 0 — off) — session 2 (columns `spread`, `spread_wings`, `spread_blocks`, `spread_groups`: `GROUPS` is an SQLite keyword) |
| `TargetRef` | a third kind, `selection`, for effects only: its key is the canonical JSON of the ordered `[{type, key}]` list — session 3 |
| `speed_masters.usage` | gains `beam` — session 6 |

Canonical JSON omits defaults, so a show that uses none of this exports byte for byte as before; the
bump is for the readers, which refuse a value they cannot parse.

### 3.3 The phase of one head

For each member `i` of an effect's expansion (`FxDistributionPlan`):

1. **Order** gives its rank `r` (today's strategies).
2. **Blocks** `B`: `r ← floor(r / B)`, over `ceil(n / B)` ranks (`ceil(R / B)` for an Order with `R`
   ranks — the same under an asymmetric one).
3. **Wings** `W` (only under an asymmetric order — Centre out, Edges in and Split are two wings
   already): the ranks split into `W` runs of `m = ceil(R / W)`; in odd runs `r ← m − 1 − r`; over `m`
   ranks. Session 2 mirrors a short last run within its own length (`len − 1 − r`), so seven heads in
   two wings are 0 1 2 3 2 1 0 and the outer pair shares a rank.
4. **Groups** `G`: `r ← r mod G`, over `G` ranks — when `G` is below the rank count (Groups off is 0).
5. **Spread** `s`: `offset = s × r / ranks`.

Blocks, Wings and Groups count whole ranks, so Position's continuous rank (its position × `n`) is
snapped to the nearest of its `n` slots, `round(position × (n − 1))`, whenever one of them acts; under
Spread alone it stays continuous. Ping-pong's sweep is the Order's own `(n − 1) / n`: the shape sets the
offsets, never the sweep.

`distinctSlots` is the number of ranks left after step 4, so a step chase's window and step timing
stay gap-free. Then, on the member's own phase `p = clock + phaseOffset − offset`:

6. **Direction**: Forward `p`; Backward `1 − p`; Bounce `p` on even cycles and `1 − p` on odd ones,
   counted on the member's own clock.

The effect is calculated at that phase, blended, and weighted by `w` (P3). Order, blocks, wings, groups
and spread are `FxDynamics` fields, edited in place as the distribution is today.

### 3.4 Over a selection (D3)

- `FxTargetRef.Selection(items)`: the expansion flattens the list in order. A fixture with the
  property is one member; a group contributes its members in its own sort order; a multi-head fixture
  whose heads hold the property contributes them by the instance's element mode. A head named twice
  is counted once, at its first place.
- It is refused anywhere but an effect: a Look or cue *value* row, a layer's targets and a binding keep
  fixture or group.
- Record writes a Local effect over a selection as one ad-hoc cue effect with a `selection` target.
  Include brings it back as one.
- R3's split (`PUT /fx/{id}` with `targets`) narrows the list and starts the edited copy over the
  subset, each head at the phase it had — the same rule as for a group (D12).

### 3.5 Fading (D1, D13)

- **On GO** (and GO TO, GO BACK), an outgoing effect's envelope runs 1 → 0 over the cue's fade on the
  cue's curve, and the instance is removed at 0; an incoming one runs 0 → 1. A continuing effect
  (P4) is untouched. A 0 s fade is today's snap.
- **Under shared cues** (the cue model plan's holds), the envelope follows the cue's holds: it fades
  in on the first hold and out on the last release. A GO between two entries of a cue that stays
  held moves no envelope, as it moves no value. A loose hold (a pad, a slot, `apply_cue`) has no
  fade: its rows snap on and off (`CueHolds.takeLoose`, `dropLoose`), and so do its effects.
- **A stack stop** fades its effects over the time it fades its values.
- **The programmer** (D13): a layer added or removed by a press, a release (`clearTarget`,
  `clearEntry` on an effect) and Clear move the envelope over the programmer fade.
- **Blind** holds programmer effects (R2.1, `fx/BlindHold.kt`): the envelope stays 1 while held, and
  leaving Blind fades them in over the Blind fade, like a press. This replaces R2.1's "the Blind fade
  does not fade an effect" in `fx-engineering.md`, `lighting-composition-model.md` §Blind and
  Example 6, and `show-cue-editing-plan.md` §3.6.
- The envelope is runtime, never stored and never recorded: Record takes the row's own `size`.
- Where it lives (session 1): the envelope is `FxInstance.intensityMultiplier`, and `FxInstance.weight`
  is the one product the targets apply after the blend (`size × envelope`, clamped to 0–1), so the
  envelope and session 5's stack size multiply in there. A programmer layer's N-shot that has run its
  count is kept in the engine painting nothing (`FxInstance.spent`) until its layer goes; a release
  that fades it fades nothing visible and removes it at 0 like any other.

### 3.6 Steps (D8)

Both step effects emit a new output, `FxOutput.Steps(from, to, blend)` — two step indices and how far
between them — and the **target** resolves it per head, which is what no output does today.

- **Palette Steps** (`category` by the palettes' family). Parameters: `palettes`, a list of value
  palette references (`tmpl:{uuid}`, generic, one family), and `fadeRatio` as Colour Cycle's. The
  target resolves each step's palette for its head through `TemplateResolver` — a position, whether
  centre-relative (`pos:`, venue-visit plan D3) or stored in degrees (`deg:`, P3), to that head's own
  pan and tilt, a beam role to its range — and blends between the two by the property's own rule. A
  colour target resolves per head too, so a wheel-less RGBW head and an RGB one each get their own.
- **Slot Steps** (`category: beam`, a setting). Parameters: `slots`, a list of option names, and no
  blend — a slot snaps. The target looks each name up in the head's own setting options; a head without
  that option holds what is underneath for that step.
- Size, direction and the spread apply as to any effect (Bounce runs the steps back).

### 3.7 The wire

| Route or frame | Change |
|---|---|
| `POST /fx/add`, `PUT /fx/{id}`, `updateFx`, `POST /groups/{name}/fx` | gain `size`, `direction`, `nShot` (s1); `spread`, `wings`, `blocks`, `groups` (s2); a `targets` list (s3) |
| Look, cue and palette effect DTOs, `ActiveEffect` | the same fields |
| `PUT /projects/{id}/cue-stacks/{sid}/effect-size` `{size}` | new (s5); `cueRunStateChanged` carries `effectSize` |
| MIDI `BindingTarget.StackEffectSize {stackUuid}` | new fader target (s5) |
| MCP effect schemas (`AiToolSchemas`) | gain each field as its session lands |

## 4. UX

### 4.1 The effect editor (D11)

`FxEffectFields` changes once and every host follows: the FX tray's inline editor, the rail's and
`FxSheet`'s popover, the add sheet, the effect-palette editor and the busk Effects tab.

- **Beside Speed**: *Size* — a slider and an `EditorField` in percent — and *Direction*, a segment of
  three glyphs (→ ← ↔) with its word on the hover.
- **Spread** (a disclosure, on a group, a selection or a fixture with heads; and always on an effect
  palette, which has no target): *Order* (today's Distribution select), *Spread* %, *Wings*, *Blocks*,
  *Groups* (Spread a slider beside a percent field, as Size is; the three counts small number fields),
  and a row of eight phase dials previewing them — the effect palette's over eight notional heads, a
  live effect's over its own as the desk expands it (one per fixture where the fixture carries the
  property itself, else its heads; up to sixteen, then *+n*). The summary line says the non-defaults: *Rig · 50 % · 2 wings*.
- **Advanced** keeps phase, blend, step timing, element mode and the head filter, and gains *Runs*:
  *Forever* or a count.
- A busk pad's face (`EffectPadDetail`) adds a ← or ↔ for a non-forward palette and the size where it
  is under 100 %: *Colour Cycle · 1 bar · M2 · ← · 60 %*.

### 4.2 The busk view

Nothing new is drawn on the busk view beyond the pad face (§4.1): its Effects tab is the editor. The
size fader is per cue stack (D10), so it lives with the stack, in the Show view and on the busk Show
tab's runner (`RunMobile`), not on a busk strip.

### 4.3 The stack's size (D10)

A slider on the stack's row in the Show view and in `RunMobile`, labelled *FX size*, from 0 to 100 %,
reading the desk's value; hidden while 100 % on the phone runner unless the stack has effects. A MIDI
fader binds to it as `stackEffectSize`.

### 4.4 The picker

`FxPicker`'s family segment becomes Intensity · Colour · Position · Beam · Controls; Beam lists the
four beam built-ins and Slot Steps, each offered only on heads with its property. Palette Steps sits
in the family of the palettes it is given, and is offered in every family that has two or more
generic value palettes.

## 5. Migration

None. Every new column defaults to today's behaviour, `selection` is a new value, and no stored effect
changes meaning (P6). Older desks refuse a newer archive by `formatVersion`, as they already do.

## 6. Dependencies

- **S15** must land first (timing, the one-bar default). Session 6's beam effects take its default.
- **R3 session 2** follows session 3 (D12). R3's §3.4 and §10 get a note in session 3's PR: *a bare
  effect press is one instance over the selection, `effects-plan.md` D3*, and
  `FU-FX-SELECTION-DISTRIBUTION` is closed by session 3 rather than recorded.
- **The grand master (venue-visit S5)** is the shape session 5 copies — REST, WS and MIDI over a
  runtime value; either may land first. S5 landed first: `routes/projectGrandMaster.kt` (REST, current
  project only), `plugins/GrandMasterSocket.kt` (one `StateFlow`-backed state frame following the live
  show, one `CONTINUOUS` set frame), `BindingTarget.GrandMasterFader` with its health arm and a
  `GrandMasterEntry` in `SurfaceFeedbackPublisher` (a value with no channel behind it, fed back and
  armed for PICKUP as `SpeedMasterEntry` is), and `GrandMasterOverviewPanel.tsx` over `useLivePush`. The
  grand master persists per project; a stack's size is dropped on a switch, so it needs no table.
- **The cue model plan's session 2 (holds)** has landed (`595aced3`): session 4 builds the envelope
  on `fx/CueHolds.kt` (§3.5), not on a stack's live cue.

## 7. What the busk and editor surfaces need

Asked by the venue-visit plan's R4 entry. **The editor changes, once** (§4.1), and that is all the
busk view's Effects tab and the effect-palette editor need, because both mount `FxEffectFields`. **The
busk view's own chrome does not change**: no new strip, no new tab, only the pad face's detail line.
**The Show view gains one control** (§4.3). The picker gains a family (§4.4).

## 8. Implementation — seven sessions

### Session 1 — Size, direction and N-shot (backend + editor) — done, `02b1c4a4`

- `FxDynamics` gains `size`, `direction`, `nShot`; P3's weight replaces the raw-output scaling, with
  `intensityMultiplier` becoming the envelope (§3.5 uses it in session 4).
- Direction applied to each member's phase (§3.3 step 6); N-shot ends the instance when its clock has
  run N cycles past the largest member offset, each member holding what is underneath after its own N.
- Stored on the four effect homes, the DTOs, sync (`formatVersion` bump, `RichProjectFixture` with
  non-default values), Record and Include, MCP schemas.
- Editor: Size and Direction beside Speed, *Runs* under Advanced (§4.1); the pad face.
- **Tests:** a Replace dimmer at 50 % sits halfway between the level underneath and the effect, never
  at 0; an Around circle at 50 % has half the radius about the position underneath; Colour Cycle
  backward runs blue → green → red; Bounce returns on odd cycles; a 2-shot ends after two cycles and
  each member hands back to the level underneath; a round trip keeps every field.
- **Docs:** `fx-engineering.md` (Size, Direction, N-shot; the end of `intensityMultiplier`'s raw
  scaling), `sync-engineering.md`.

### Session 2 — Shaping the spread (backend + editor) — done, `6df3b8ff`, `835d5684`, `de9986c3`, `f8bf4454`

- §3.3 steps 2–5 in `DistributionPlan.of`, with `distinctSlots` after step 4; `FxDynamics` gains the
  four fields; stored as session 1's are (`formatVersion` bump).
- The shared vector (P2) and the client's copy (`lib/spreadShape.ts`), both reading it.
- Editor: the Spread section and its dial row (§4.1).
- **Tests:** the vector's cases on both sides; blocks of 3 over 12 heads give 4 slots; wings 2 under
  Centre out is refused by the write boundary and hidden by the editor; a step chase with groups 2 has
  no gap.
- **Docs:** `fx-engineering.md` §"Distribution Strategies" (and that `SPLIT` equals `EDGES_IN`).

### Session 3 — One effect over a selection (backend + programmer) — done, `3cefa3ed`

- `TargetRef.Selection` and `FxTargetRef.Selection` (§3.4), the expansion, the effect-only refusal,
  Record and Include, sync (`formatVersion` bump), MCP (`targets` on the effect tools).
- The programmer's *+ Effect* and ⌘K *Apply FX* start one effect over the marquee's or selection's
  targets in order, where today they take the first group or fixture.
- R3's plan note and `FU-FX-SELECTION-DISTRIBUTION` (§6).
- The expansion's members go through `DistributionPlan.of` like a group's, so session 2's spread shape
  (Spread, Wings, Blocks, Groups) applies to a selection unchanged — rank in selection order, then
  shaped.
- **Tests:** eight loose pars and a group of four make one instance of twelve members in order; a
  head in both counts once; a value row naming `selection` is 400; a recorded cue brings the effect
  back as one.
- **Docs:** `fx-engineering.md` §"FX Targets", `lighting-composition-model.md`.

### Session 4 — Fading by size (backend)

- §3.5 on the hold registry, as R1.2 shipped it. Today `CueHolds.bringOn` spawns a cue's effects at
  full whatever its `Arrival` (which governs rows only), `takeOff` removes them whatever its
  `Departure`, and the manager starts `CueCrossfadeDriver` only for a side with Layer 4 rows
  (`Acquired.hasRows`, `Released.fadingRows`), so an effect-only cue gets no fade at all. The driver is
  per stack, one cue a side, and moves `cueLayer` weights alone; `cancel` and `forgetOutgoing` know only
  rows.
  - On `FADE_IN`, `bringOn` spawns the effects at envelope 0; on `FADE_OUT`, `takeOff` leaves them for
    the driver, which ticks each side's envelope beside its weight and removes the outgoing effects at
    0. The manager's gate widens to rows or effects.
  - `cancel` and `forgetOutgoing` treat effects as they treat rows. **Ask Chris first** what a cue
    brought back on mid-fade does: keep its fading instances, envelope back to 1, or remove and
    respawn them (`afterRegister` calls `forgetOutgoing`, then `bringOn`).
  - A stack stop releases with `Departure.SNAP`, so its effects snap as its values do.
  - P4's continuing match; the programmer's press, release and Clear at the programmer fade; Blind.
- Replace the "effects always snap" text: `CueStackManager`'s KDoc and its `useCrossfade` comment,
  `CueCrossfadeDriver`'s KDoc with its claim about the other desks, `cue-stacks-engineering.md`
  §"Activate Flow" and §"Crossfade", `lighting-composition-model.md` §"Fade envelopes",
  `fx-engineering.md`'s envelope bullet and its "FxInstance.cueId" paragraph.
- **Tests:** a GO over 3 s takes the outgoing ripple's weight 1 → 0 and the incoming 0 → 1, both
  running throughout; a continuing Look effect keeps its id and phase; a 0 s cue snaps; a pad press
  at a 2 s programmer fade grows the effect; GO BACK fades as GO does; an effect-only cue fades; a GO
  onto a cue another stack holds moves no envelope; a stack leaving a cue a press holds leaves it at 1;
  a loose press snaps.
- **Docs:** `fx-engineering.md` §"Cue Integration" and §"Size, direction and N-shot",
  `cue-stacks-engineering.md` §"Holds", `lighting-composition-model.md`, and R2.1's Blind text (§3.5).

### Session 5 — A stack's effect size (backend + frontend)

- §4.3 and the wire (§3.7): a runtime per-stack value in `CueStackManager` weighting that stack's
  effects (P3's `stackSize`), REST, `cueRunStateChanged`, the MIDI target with its health and
  feedback; dropped on a project switch.
- **Ask Chris first** which stack's size weighs a cue two stacks hold, or a stack and a press. Since
  R1.2 such a cue is one set of instances, tagged with its first hold's stack (`FxInstance.cueStackId`,
  never retagged). Two readings: the highest hold's stack, 100 % under a loose hold; or the product
  over its stack holds.
- The slider on the Show view's stack row and `RunMobile`; `lib/surfaceDrop.ts` and the binding
  picker.
- **Tests:** 50 % halves each of the stack's effects' weight and no other stack's; a fader moves it;
  a reload keeps it, a project switch drops it.
- **Docs:** `fx-engineering.md`, `midi-control-surface-engineering.md`,
  `websocket-engineering.md`.

### Session 6 — Beam effects (backend + frontend)

- `category: beam`: Zoom Breathe (`zoom`, a slow sine between two levels), Iris Pulse (`iris`, Pulse's
  shape), Frost Pulse (`frost`), Focus Pull (`focus`, a sweep between two levels) — `.fx.kts`, each
  declaring its one property; `FxRegistrationTargetCompatibilityTest` covers them.
- `usage` gains `beam` (D7); the usage vocabulary test, `speedMasterModel.ts`, the detail sheet and the
  busk rail's badge follow. Beam effect palettes: the family already maps (`familyForEffectCategory`);
  lift any refusal of a beam effect palette.
- The picker's Beam family (§4.4).
- **Tests:** each beam effect is offered only on heads with its property; a beam effect palette saves
  and presses; a Beam master takes a new beam effect by S15's default.
- **Docs:** `fx-engineering.md` §"Effect Types", `lighting-composition-model.md`.

### Session 7 — Palette Steps and Slot Steps (backend + editor)

- `FxOutput.Steps` and per-head resolution in the slider, colour, position and setting targets (§3.6);
  the two built-ins.
- Editor parameter types: a palette list (the colour list's picker, any family, generic value palettes
  only) and a slot list (the target's setting options; on an effect palette, typed names).
- **Tests:** two position palettes land on each head's own pan and tilt — one centre-relative
  (`pos:`) and one stored in degrees (`deg:`), since `TemplateResolver` resolves both; Slot Steps of
  *Gobo 3, Gobo 5* lands each head's own levels; a head without *Gobo 5* holds underneath on that step;
  Bounce runs the steps back.
- **Docs:** `fx-engineering.md`, `fixtures-engineering.md` (settings as effect targets).

Order: 1, 2, 3, 4, 5, 6, 7. Session 3 is ahead of R3's session 2 (D12); 4 needs 1's weight; 5 needs
4's envelope composition; 6 and 7 are independent of the rest after 1.

## 9. Explicitly out of scope

- **User waveform editors** (MagicQ, grandMA3's Phaser Editor, Eos's drawn shapes). Scripts are ours.
- **Pixel-map and grid effects** (MagicQ Pix Map, MAtricks grids and adaptive measure).
- **Per-head overrides inside one effect** (Eos Effect Channels, MagicQ Advanced). R3's split instead.
- **Effect variables** (Eos) and **global effects faders** (Eos).
- **A rate scale on the instance** (Eos Rate %): speed masters, their ratios and *Seconds* cover it.
- **Size above 100 %** (MagicQ's oversize, Eos's 2000 %).
- **Entry and exit by rate** (Eos *Fade by Rate*); D1 fades by size only.
- **Level effects on any beam slider**: declined for named beam built-ins (D6).

## 10. Follow-ups to record

In [`followups.md`](followups.md), when the session that creates them lands:

- `FU-FX-WIDTH-SOFTNESS` (session 1): duty, on ratio, fade ratio and attack are each an effect's own
  parameter under its own name. Trigger: an operator looks for one Width or Softness across effects.
- `FU-FX-SIZE-OVER-100` (session 1): Size stops at 100 %. Trigger: an operator wants an effect bigger
  than it was written.
- `FU-FX-BEAM-ANY-SLIDER` (session 6): a level effect is not offered on a beam slider without a beam
  built-in of its own. Trigger: an operator wants to breathe prism or a fixture's own speed channel.
- `FU-FX-SPLIT-DUPLICATE` (session 2): `SPLIT` and `EDGES_IN` give the same offsets. Ready.

## 11. Questions

Answered by Chris, 2026-10-15, in five rounds:

1. **What should a cue's fade do to effects?** Fade by size (D1). Per-effect entry and exit modes, and
   keeping the snap, were declined.
2. **Should an effect carry a Size?** Yes, 0–100 % over what is underneath (D2). An amplitude of 0–200 %
   about the effect's centre, and a runtime-only size, were declined.
3. **An effect on loose fixtures?** One effect over the selection, in selection order (D3). Rig order
   and keeping unison were declined.
4. **Which shaping parameters?** Spread %, Wings, Blocks and Groups, all four (D4).
5. **Where does direction live?** On every running effect (D5). A parameter on the effects that need
   one was declined.
6. **How do effects reach beam attributes?** Beam built-ins of their own (D6). Offering the level
   effects on beam sliders, and the picker only, were declined.
7. **Wheels and slots?** A steps effect (D8). Slot-aware level effects and leaving it out were declined.
8. **Which smaller features?** Size on a fader and N-shot (D9, D10). One Width and Softness and a
   rate scale on the instance were not taken (§9, §10).
9. **The first beam set?** Zoom Breathe, Iris Pulse, Frost Pulse and Focus Pull (D6).
10. **Should a speed master be taggable Beam?** Yes (D7).
11. **Where do the new parameters go in the editor?** Size and Direction up front, a Spread section
    (D11). Everything under Advanced was declined.
12. **What does a Size fader scale?** One cue stack's effects (D10). Every effect of one family, and a
    speed master's effects, were declined.
13. **This plan and R3's session 2?** This plan's selection session first (D12).
14. **The stack's size, stored or runtime?** Runtime, like the grand master (D10).
15. **Do the programmer's gestures fade effects?** Yes, at the programmer fade (D13).
16. **Palettes cannot hold wheel slots; how do steps reach wheels?** By slot name (D8). Palette steps
    without wheels, and opening the palette vocabulary to slots, were declined.
