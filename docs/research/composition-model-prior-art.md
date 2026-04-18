# Prior-Art Survey — Channel Composition Model

**Purpose**: grounding research behind [lighting-composition-model.md](../lighting-composition-model.md). Surveys how established lighting consoles compose channel output, so lighting7's Layer 1–5 model is based on proven patterns rather than invented from scratch.

Four behaviour areas in scope:

1. Layer / priority stack (HTP vs LTP vs blend-mode)
2. Multi-effect / multi-cue composition on the same property
3. Direct-write ("programmer" / "editor") vs playback interaction
4. Blind vs Live edit semantics and crossfade of static (absolute) values

Systems surveyed: ETC EOS family, MA Lighting grandMA2 / grandMA3, High End Hog 4, Chamsys MagicQ, Avolites Titan.

---

## Current lighting7 pipeline (baseline at time of survey)

Confirmed via [FxEngine.kt](../../src/main/kotlin/uk/me/cormack/lighting7/fx/FxEngine.kt), [FxTarget.kt](../../src/main/kotlin/uk/me/cormack/lighting7/fx/FxTarget.kt), [ArtNetController.kt](../../src/main/kotlin/uk/me/cormack/lighting7/dmx/ArtNetController.kt), [projectCues.kt](../../src/main/kotlin/uk/me/cormack/lighting7/routes/projectCues.kt):

- **Per-tick reset to neutral**: at the top of `processBeatTick()`, all active-effect properties are reset to a neutral baseline before each effect applies its output. This is load-bearing: `ADDITIVE` / `MAX` only work because the baseline is cleared each tick.
- **Effects iterated from a `ConcurrentHashMap`** — iteration order is unspecified. When two effects target the same property the winner is undefined.
- **Blend modes** (`OVERRIDE`, `ADDITIVE`, `MULTIPLY`, `MAX`, `MIN`) are applied within Layer 2 against transaction-local state, but since there's no ordering they don't compose deterministically.
- **Parking** is a transmit-time override in `ArtNetController` — after FX composition, before ArtNet emit.
- **Direct channel writes** (`updateChannel`) bypass FX entirely, writing straight to `ArtNetController.setValue()`. On the next beat tick, FX reset-to-neutral will clobber them if any active effect covers that property.
- **Cues are tags on `FxInstance`s** — `applyCue()` adds a batch of effects with the same `cueId`. Static values use the `StaticValue` / `StaticSetting` effect types, which is the wart this refactor removes.
- **Timing decoupling**: FX ticks run at BPM-synced 24 ticks/beat; DMX transmission runs at ~50Hz wall-clock; max ~20 ms latency between them.

Gaps the refactor must close: undefined multi-effect ordering; direct writes being clobbered by effect reset; no principled separation between "state a cue asserts" and "modulation a cue applies"; parking is implicit rather than documented as a layer.

---

## Area 1 — Layer / priority stack

### Industry consensus

All surveyed consoles share the same broad hierarchy, though they name it differently:

```
(top, highest priority)
    Park / Blackout / Grandmaster
    Manual / Programmer / Editor / Captured
    Playbacks (cue stacks, submasters) — ordered by priority + HTP/LTP
    Default / home values
(bottom)
```

The universal rule: **manual/programmer wins over playbacks**, and **park wins over everything** (often with explicit blackout and grandmaster also sitting above playbacks). The interesting variation is in how the "playback layer" is resolved.

### HTP vs LTP split

- **ETC EOS**: cue lists can be HTP or LTP **for intensity only**; all non-intensity parameters (colour, position, beam) are always LTP. Manual values always win over playback unless a submaster is marked "shielded".
- **grandMA2**: sequences default to LTP. HTP is explicitly an intensity-only option. Programmer priority is its own fixed level; `Freeze` promotes the programmer above playbacks. A playback priority `Super` exists for "beats everything including programmer".
- **grandMA3**: phasers compose with priority: absolute values > relative values; HTP sequences > LTP sequences. "Stomp" deletes phaser steps back to the absolute step-1 value. `Off` / `Release` walks back through the priority stack.
- **Hog 4**: intensity defaults LTP, configurable to HTP per cuelist. All other parameters are LTP full stop. Cuelist priority set in increments of 10; equal priority falls back to LTP by activation order.
- **MagicQ**: three playback priority levels — `NORMAL`, `HIGH`, `ABOVE PROGRAMMER`. Programmer beats `NORMAL` and `HIGH`. HTP and LTP coexist per-channel, with a playback option "All Chans Controlled LTP" that forces HTP channels to behave as LTP when that playback is on top.
- **Avolites Titan**: HTP for intensity, LTP for everything else. Playback priority `Low / Normal / High / Programmer / Very High`; Programmer = same level as manual programmer values.

### Implications for us

- **We don't need HTP/LTP at all for non-intensity.** Every console agrees: position, colour, beam are always LTP. For our Layer 3 property assignments, LTP (= last-applied-wins, cue stack order then creation order) is the right and only answer.
- **Intensity HTP is a useful opt-in** for theatrical mixes (combining multiple stacks where the brighter win). But it's an intensity-specific toggle, not the default. We can treat it as a **blend-mode on the intensity property** (`MAX` vs `OVERRIDE`) rather than a separate "HTP/LTP layer flag" — our blend-mode machinery already covers this.
- **Programmer above playback is universal**; direct writes with `kind: 'live'` (no cue-edit session) should therefore sit above Layer 3 — which matches the plan's current Layer 4 placement if we add one refinement: Layer 4 should be **sticky until explicitly released**, not "overwritten by the next effect tick". Today we lose the direct write on the next tick because reset-to-neutral runs across every active-effect property. Fix: **only reset properties that currently have a layer-2 OR layer-3 contribution**, leaving untouched-by-higher-layers direct writes alone.
- **Park / blackout / grandmaster** as an unconditional top layer is universal. Our Layer 1 is fine.

### Priority within Layer 2 (effects)

All consoles order effect composition deterministically. grandMA3 explicitly ranks by priority and walks back through the stack on release. Our current `ConcurrentHashMap` iteration is non-deterministic and should become a sorted pass. **Recommended ordering**: (1) effect `priority` field (explicit) → (2) cue-stack position for cue-owned effects → (3) creation timestamp for manual/ad-hoc effects. Equal priorities → creation order.

---

## Area 2 — Multi-effect / multi-cue on the same property

### Industry patterns

- **ETC EOS**: effects don't stack on the same attribute of the same channel — you can't run two colour effects on the same fixture's colour. The workaround is one effect per attribute (colour effect + position effect simultaneously is fine). In pixel-map effect layers specifically, EOS outputs effect layers HTP so overlapping colour effects combine brightly.
- **grandMA3**: phasers have explicit priority; two phasers targeting the same attribute resolve by priority + absolute-beats-relative. "Stomp" behaviour lets a new cue explicitly override an in-flight phaser back to a static value.
- **Hog 4**: multiple effects on the same parameter resolve by cuelist priority + LTP. If two same-priority cuelists run effects on the same attribute, latest wins.
- **MagicQ**: playbacks with effects follow the same priority ladder as static values. Programmer values active on a channel suppress effects from playbacks.
- **Avolites**: same — playback priority + LTP, programmer suppresses.

### Implications for us

Two valid models, with different tradeoffs:

| Model | Description | Pro | Con |
|---|---|---|---|
| **A. Strict LTP (EOS-style)** | Only one effect per property per frame wins; others are suppressed. | Simple, predictable, matches what most theatre programmers expect. | Loses expressive power of stacking (e.g. slow pulse + fast strobe on dimmer). |
| **B. Priority-ordered blend stack (grandMA3 / our current intent)** | All active effects on a property compose in priority order via their `blendMode`. | Expressive — an `ADDITIVE` ripple over an `OVERRIDE` base works as one would expect. | Rules for "what does ADDITIVE mean when two effects on the same property both want to add" need to be crystal clear in docs. |

The plan commits to model B. The survey supports this choice — no major console suppresses same-property effect stacking entirely, and grandMA3's phaser priority model is explicitly similar. Recommendation: **keep model B, but document the invariants explicitly**:

- Within Layer 2, effects are sorted by priority (ascending), then iterated.
- Starting state for each channel = Layer-3 contribution (or default if none).
- For each effect in order, `accumulator = blend(accumulator, effect.output, effect.blendMode)`.
- Final Layer-2 output = accumulator.
- This composition is deterministic given the sorted iteration.

### Special case: "Stomp"

grandMA3's `Stomp` command is genuinely useful — it tells a new cue "cancel all in-flight phasers on properties I cover, snap to my absolute values". We should consider a **"stomp on apply"** flag on cues (per-cue, default off): when the cue applies, it removes other cues' ad-hoc effects that target the same properties, rather than stacking them.

---

## Area 3 — Direct-write vs playback interaction

This is the area where consoles differ most, and where the `cueEdit.*` design needs to land precisely.

### How each console models "programmer"

- **ETC EOS — Manual / Captured**: any attribute touched from a keypad or encoder is "manual" (displayed red/magenta). Manual values **override all playback** until released (`Release` or reaching blackout). `Update` pushes manual values into the nearest active cue. `Record` stores them as a new cue. `Capture` pins selected channels so they stay manual even after deselection.
- **grandMA2/3 — Programmer**: every value you touch enters the programmer (yellow/white). The programmer sits **above all LTP playbacks** (below HTP intensities unless `Freeze` is on, which flips that). `Clear` empties the programmer. `Store` writes programmer contents into a new cue; `Store /merge` merges into an existing cue. `Update` is the "grab changes back" flow.
- **Hog 4 — Editor**: same model, different name. Editor values sit on top until cleared or merged via `Update` / `Record`.
- **MagicQ — Programmer**: identical shape. Programmer beats all playbacks except those explicitly set `ABOVE PROGRAMMER`. `Update` merges programmer into a cue; `Record` stores new.
- **Avolites Titan — Programmer**: identical. Programmer priority is a playback level (`Programmer` / `Very High` both available), allowing sub-patterns like "this playback can beat the programmer".

### Universal pattern

Every console has:

1. A **persistent edit buffer** (programmer / editor) that overrides playbacks.
2. An **explicit commit step** (`Update` / `Record` / `Store` / `Store /merge`) that moves buffer contents into a cue.
3. A **clear / release** step that discards buffer without committing.

Our Phase 0 design diverges: we auto-persist direct writes into the cue being edited (no buffer, no explicit commit). **This is unusual.** The survey asks us to decide deliberately whether we want the buffer pattern.

### Should we adopt a programmer-style buffer?

**Arguments for auto-persist (chosen design)**:
- Matches our audience (solo operator, rehearsal-first workflow; not multi-person touring show).
- Simpler mental model for a small-scale system.
- "Live mode = WYSIWYG editor" is coherent — what you see is what you saved.

**Arguments for a programmer/editor buffer**:
- Matches every professional console → operators coming from those tools expect it.
- Enables "try it live, discard if you don't like it" without touching the stored cue.
- Separates two distinct operations — "fiddle with the stage" vs "modify the cue" — that are conflated in auto-persist.
- Makes multi-cue editing (snapshot across cues) trivial.

**Hybrid middle ground**: keep auto-persist as the default, but add a **"discard pending changes" action** to the editor that rolls Layer 3 back to the last-saved state of the cue. This gets the safety valve without an explicit commit step. Snapshot the cue's property assignments on `beginEdit` to roll back to — cheap, session-scoped.

### Implications for Layer 4

Regardless of the buffer question, Layer 4 ("direct live writes when no cue is open for edit") needs one clarification the survey makes clear:

**Layer 4 direct writes must be sticky — they should NOT be clobbered by effect reset-to-neutral on the next tick.** Our current `FxEngine` resets any property targeted by an active effect to neutral each tick, which is why manual channel tweaks disappear under running effects. This is broken vs every professional console. The fix is to **treat Layer 4 as the new neutral for properties not covered by Layer 3**: effects reset to Layer 4 (or Layer 5 baseline if Layer 4 is empty), not to hardcoded zero.

---

## Area 4 — Blind vs Live edit, and attribute crossfade

### Blind / Preview across consoles

- **ETC EOS — Blind**: edits to cue/preset/palette data in Blind don't touch the stage, **but** if the edited target is currently active (submaster, effect, sub nested in the live cue), changes apply immediately. Blind auto-saves (no Update command works in Blind). Classic workflow: advance through cue list in Blind to prep changes without interrupting the run.
- **grandMA2/3 — Blind + Preview**: Blind hides the programmer from output; Preview spins up a second programmer for rehearsal-style edits that don't touch stage. These are distinct: Blind is "don't show my current edits on stage", Preview is "spin up a parallel workspace for edits".
- **Hog 4 — Blind**: similar to EOS Blind — cue/palette edits don't show on stage unless the cue being edited is currently running.
- **MagicQ — Blind**: programmer can be in Blind (not output) or active. Cues can be edited via Update in Blind.
- **Avolites Titan — Blind**: per-playback blind toggle. Edits to a playback in Blind don't output.

### Universal "gotcha" — editing a live cue in Blind

Every console has a subtle rule: **if the cue you're editing in Blind is currently live on stage, some changes still affect the stage because they're being sourced from the cue's live output**. EOS explicitly calls this out (active subs / effects update immediately). grandMA3 has this same behaviour. Our plan's Blind semantic is stricter — "Blind mode persists without stage-side write" — which is cleaner but operators with console muscle memory will be briefly confused. Worth a docs note, not a behaviour change.

### Attribute crossfade

Universal rules across consoles:

- **Intensity** crossfades linearly over the cue's fade time by default. Up-fade time and down-fade time can differ (EOS split crossfader is the archetype).
- **Non-intensity LTP attributes** (colour, position, beam) **also crossfade** over the cue's fade time. They don't snap. Colour fade is a linear RGB-space blend unless a "Gel Fade" mode is set, in which case it simulates filter changeover.
- **"Move in dark" / "Move before bright"**: when a fixture is at zero intensity at the end of one cue, position/colour changes for the next cue can be pre-applied before the next up-fade starts. This is a per-cue opt-in.
- **Per-attribute fade time**: most consoles let you override the global cue fade time per attribute (e.g. dimmer fades 3s, colour fades 1s, position snaps).
- **Snap-only attributes**: on/off settings (gobo rotation direction, strobe on/off) snap rather than fade; the console tracks these as "discrete" parameters.

### Implications for Phase 0

Property assignments (Layer 3) need a **fade envelope**, mirroring effects' `intensityMultiplier`. Survey says:

- **Default**: weighted blend between outgoing cue's Layer-3 contribution and incoming cue's Layer-3 contribution, over the cue fade time. Weight = cue's fade progress [0..1].
- **Per-property type blending**:
  - Sliders (dimmer, UV, pan, tilt): linear interpolation between values.
  - Colour (RGB): linear RGB-space interpolation by default. Flag for HSV / LAB modes as future stretch.
  - Settings (enum): snap at 50% fade progress (matches EOS behaviour for discrete parameters).
  - Position (pan/tilt) with a "move in dark" flag: when outgoing cue has intensity 0 at its end, pre-apply the new position during the outgoing cue's fade-out rather than waiting.
- **Per-attribute fade override**: defer to post-Phase-0. The layer model supports it (each assignment can carry its own fade time), but we don't need the authoring UI for it yet.

---

## Per-console summary table

| System | Intensity rule | Non-intensity rule | Programmer/editor buffer? | Blind mode? |
|---|---|---|---|---|
| ETC EOS | HTP or LTP (per-cuelist, intensity only) | Always LTP | Yes (Manual/Captured); Update/Record commits | Yes (auto-saves; active targets still update) |
| grandMA2 | LTP default, HTP option (intensity only) | Always LTP | Yes (Programmer); Store/Merge/Update | Yes (hides programmer output) |
| grandMA3 | HTP sequence > LTP sequence | Priority + absolute > relative | Yes (Programmer + separate Preview) | Yes, plus Preview parallel programmer |
| Hog 4 | LTP default, HTP per-cuelist | Always LTP | Yes (Editor) | Yes |
| MagicQ | HTP or LTP per channel + playback priority | LTP | Yes (Programmer); 3 priority tiers including ABOVE PROGRAMMER | Yes (per programmer / per playback) |
| Avolites Titan | HTP | LTP | Yes (Programmer); 5 priority tiers | Yes (per playback) |
| **lighting7 (proposed)** | Blend-mode per effect; Layer 3 = HTP for intensity-like categories, else LTP | LTP (always) | No buffer; auto-persist into cue being edited with snapshot-based discard | Yes (Live / Blind toggle per edit session) |

---

## Implications for Phase 0 design

### Locked decisions (confirmed during research)

1. **Five-layer stack** (Parking → Effects → Property Assignments → Direct Live Writes → Baseline) stands. Every surveyed system uses an equivalent stack.
2. **Deterministic Layer 2 ordering**: explicit `priority` field + stable fallback (cue-stack position for cued effects, creation time for manual). Replace `ConcurrentHashMap` iteration with a sorted pass.
3. **Park semantics reframed as Layer 1** in docs; implementation stays at transmit-time override (it's an optimisation, not a different model).
4. **Reset-to-neutral fix**: effects reset targets to the layer below (Layer 3 if present, else Layer 4 direct-write, else Layer 5 baseline), not to hardcoded zero. This unblocks Layer 4 stickiness and fixes the current "direct writes clobbered under running effects" bug.
5. **Per-property-type crossfade defaults** for Layer 3: sliders linear, colour RGB-linear, settings snap at 50% fade progress, position respects a `moveInDark` flag (pre-apply new position during outgoing cue's fade-out when outgoing intensity is 0 at end).
6. **Layer 4 direct-write stickiness**: direct writes persist until explicitly released — new cue triggered, `clearAssignment` called, or fresh `updateChannel` with different value. Matches the programmer pattern across all consoles and falls out naturally from the reset fix.

### Refined design: composition rules driven by `PropertyCategory`

Fixture library files in ETC EOS / grandMA / Hog all tag each parameter with a category (Intensity / Color / Position / Beam / Shutter), and HTP/LTP falls out of the category, not the cue. Fixture authors can override per-parameter when the default doesn't fit.

Our existing enum [PropertyCategory](../../src/main/kotlin/uk/me/cormack/lighting7/fixture/FixtureProperty.kt) already has the right granularity: `DIMMER`, `COLOUR`, `PAN`, `TILT`, `PAN_FINE`, `TILT_FINE`, `UV`, `STROBE`, `AMBER`, `WHITE`, `SPEED`, `SETTING`, `OTHER`.

**Proposed model**: each `PropertyCategory` carries a default composition rule. When multiple cues contribute Layer-3 assignments to the same property, the resolver uses the category's rule:

| Category | Default rule | Reasoning |
|---|---|---|
| `DIMMER`, `UV` | `HTP` (max) | Intensity-like — taking the brighter is what operators expect when stacking looks. |
| `COLOUR`, `AMBER`, `WHITE` | `LTP` | Blending colours from stacked cues produces muddy results; last-applied-wins matches every pro console for non-intensity. |
| `PAN`, `TILT`, `PAN_FINE`, `TILT_FINE` | `LTP` | You can't meaningfully combine two position intents; LTP. |
| `STROBE` | `HTP` | Intensity-like. Two cues both wanting strobe should yield the brighter / faster. |
| `SPEED` | `LTP` | Non-additive control parameter. |
| `SETTING`, `OTHER` | `LTP` | Discrete / enum values; last wins. |

**Fixture-type overrides**: the `@FixtureProperty` annotation can accept a `composition: CompositionRule` parameter that overrides the category default for that specific property. Rarely needed, but e.g. a fixture whose "dimmer" channel is really a shutter setting might want `LTP`. Designed in, not used in Phase 0.

**No per-assignment blend modes.** Cues don't pick their blend mode — the property/category determines it. Simpler than a per-assignment blend-mode model; matches the user's intuition. Cue authoring stays simple (just "set this property to this value"); the composition rule is an intrinsic of the property type.

**HTP inside Layer 3** means Layer 3 composition isn't pure LTP — it's a per-category fold:
- `LTP` categories: last cue wins, by cue-stack position + standalone cue activation order.
- `HTP` categories: take the `max` across all contributing cues; fade each contribution by its cue's current fade weight so crossfades still look right.

### Programmer rollback — what other consoles actually do

Every pro console uses a **buffer + explicit commit** pattern. The buffer is the programmer / editor. Edits accumulate there; three actions leave the buffer:

- **`Update`** (EOS / MagicQ / Titan) / **`Store /merge`** (grandMA) — commits buffer contents into an existing cue. This is how cue-editing actually happens: you open the cue, its values come into the programmer (marked), you tweak, you Update. The cue only changes when you Update.
- **`Record`** / **`Store`** — commits buffer as a new cue. Not relevant here.
- **`Clear`** (grandMA / Hog / Titan / MagicQ) / **`Release`** (EOS) — discards buffer without committing. The stage returns to whatever the playbacks were saying. This is the universal "undo" for a programming session.

Key observation: **you can always tell, at any moment, what's "in the buffer" vs "what's in the cue"** because the console shows buffer-owned values in a distinct colour (red on EOS, yellow on grandMA). The separation between "I'm trying this out" and "this is saved in the cue" is the core safety mechanism.

Our "auto-persist into the cue while editing" approach collapses this separation. That's fine for a solo-operator workflow — we never have to reconcile two programmer sessions — but it loses the "try then discard" affordance.

**Two ways to adapt this to our no-buffer model**:

- **A. Snapshot-on-beginEdit + `discardChanges`**: on `cueEdit.beginEdit`, server snapshots the cue's Layer 3 state. Maintain the snapshot for the session. Expose `cueEdit.discardChanges` which restores the snapshot. Practically identical to `Release` — discards everything since edit started. No in-between states, no multi-level undo. Cheap (one object per open session). Matches operator muscle memory.
- **B. Transactional edit mode**: on `beginEdit`, the cue's assignments are copied into a "draft" / "shadow" version. Edits write to the draft. `endEdit { commit: true }` promotes the draft over the live cue; `endEdit { commit: false }` discards it. Stronger guarantee (no partial states visible between clients) but heavier to build.

A is the right choice — equivalent safety with much less machinery. B is genuinely transactional but is overkill for a single-operator system.

### Locked decisions (round 2)

- **Composition rules by category** confirmed as the table above: `DIMMER`, `UV`, `STROBE` default to **HTP**; everything else (`COLOUR`, `AMBER`, `WHITE`, `PAN`, `TILT`, `PAN_FINE`, `TILT_FINE`, `SPEED`, `SETTING`, `OTHER`) defaults to **LTP**. Per-property fixture override via `@FixtureProperty(composition = ...)` is designed in but unused in Phase 0.
- **Rollback model = Snapshot-on-beginEdit + `discardChanges` (A)**. Server snapshots the cue's Layer 3 state on `cueEdit.beginEdit`; `cueEdit.discardChanges` restores the snapshot. Edits still auto-persist (no explicit commit step); the snapshot is just a session-lifetime undo to the pre-edit baseline.
- **Stomp flag designed in now**: cue model carries a `stomp: Boolean` (default false). Resolver logic: when a cue with `stomp=true` applies, the FX engine removes ad-hoc effects tagged with other cue IDs that target properties covered by this cue's Layer 3 assignments. No authoring UI in Phase 0/1/2 — implement when authoring surface lands.

### Divergence from industry worth flagging

- **Blind is stricter than EOS**: our Blind says "no stage writes ever". EOS's Blind says "no stage writes *from the edit surface*, but if the edited cue is live its active effects still update". We're stricter; keep our rule — simpler to explain. Worth a docs note.
- **No "Update" command**: because we auto-persist (or snapshot+discard), there's no explicit commit step. Operators coming from pro consoles will look for it briefly. Worth a docs note.

---

## Sources

- ETC EOS — HTP/LTP and priority: [community thread](https://community.etcconnect.com/control_consoles/eos-family-consoles/f/eos-family/22783/prioity-htp-ltp), [v2.6.0 Operations Supplement](https://enlx.co.uk/wptemp/wp-content/uploads/2024/05/EosFamily_v2.6.0_OperationsManualSupplement_RevD.pdf), [v2.0 manual p308](https://www.manualsdir.com/manuals/559089/etc-eos-titanium-eos-and-gio-v200.html?page=308)
- ETC EOS — Effect stacking: [multi-effect thread](https://community.etcconnect.com/control_consoles/eos-family-consoles/f/eos-family/12349/multiple-effects-on-one-channel-or-channel-group), [Effects Intensive workbook](https://www.etcconnect.com/uploadedFiles/Main_Site/Documents/Public/Video_Tutorial/EosFamily_ET_Effects_INT_Wrkbk.pdf)
- ETC EOS — Blind / Update: [Update vs Record Only](https://community.etcconnect.com/control_consoles/eos-family-consoles/f/eos-family/115/update-vs-record-only), [Editing in Blind](https://community.etcconnect.com/control_consoles/eos-family-consoles/f/eos-family/13450/editing-in-blind), [manual p199 Blind recording](https://www.manualslib.com/manual/1292976/Etc-Eos.html?page=199)
- grandMA2 — Programmer / Freeze / priority: [Freeze tutorial](https://consoletrainer.com/freeze/), [Playback overview](https://academy.actentertainment.com/grandma2-playback), [LTP forum](https://forum.malighting.com/forum/thread/43742-ltp-lowesttakes-precedence/)
- grandMA3 — Phasers / priority / Stomp: [Phasers help](https://help.malighting.com/grandMA3/2.0/HTML/phaser.html), [Play Back Cues](https://help.malighting.com/grandMA3/2.3/HTML/cue_playback.html), [Stomp thread](https://forum.malighting.com/forum/thread/4969-stomp-phaser-with-different-executor/)
- grandMA3 — Programmer / Blind / Preview / Edit: [Programmer help](https://help.malighting.com/grandMA3/2.3/HTML/operate_programmer.html), [Update Cues](https://help.malighting.com/grandMA3/2.0/HTML/cue_update.html), [store merge vs edit](https://forum.malighting.com/forum/thread/4186-store-merge-vs-edit-cue-ma3-v1-1/)
- Hog 4 — HTP/LTP / priority: [sect-ltp](https://www.etcconnect.com/webdocs/Controls/HOG/HTML/en/sect-ltp.htm), [sect-htp_ltp](https://www.etcconnect.com/webdocs/Controls/HOG/HTML/en/sect-htp_ltp.htm), [User Manual v3.17](https://www.rentex.com/wp-content/uploads/2022/01/HedgeHog4X-Manual.pdf)
- Chamsys MagicQ — Playback priority / Programmer: [Programmer docs](https://secure.chamsys.co.uk/docs/magicq/manual/programmer.html), [Advanced cue stack options](https://secure.chamsys.co.uk/docs/magicq/manual/advanced_cue_stacks.html), [Concepts](https://secure.chamsys.co.uk/help/documentation/magicq/concepts.html)
- Avolites Titan — Priority / HTP/LTP / Release: [Playback Options](https://manual.avolites.com/docs/cues/playback-options/), [Cue Playback](https://manual.avolites.com/docs/cues/cue-playback/), [Pearl Expert Titan manual p120](https://www.manualslib.com/manual/763134/Avolites-Pearl-Expert-Titan.html?page=120)
- Crossfade / move-in-dark / per-attribute timing: [Avolites Cue Timing](https://manual.avolites.com/docs/cues/cue-timing/), [On Stage Lighting cue timing](https://www.onstagelighting.co.uk/console-programming/lighting-cue-timing/), [ETC Control Philosophy white paper](https://www.etcconnect.com/uploadedFiles/Main_Site/Documents/Public/White_Papers/White_Paper_Control_Philosophy_revA.pdf), [ETC Out of Control blog — LED transitions](https://blog.etcconnect.com/2017/06/out-of-control-cue-transitions-leds)
