# Programmer Redesign — Proposal

**Status**: In progress — **Session 1 (backend programmer core) landed 2026-08-11**:
`ProgrammerStore` + owner slots + touched flag + channel sideband, layer reorder with
all-FX suppression (per the confirmed widening of §3.1's cue-FX rule to manual FX too, so
"locate wins" survives non-destructively), programmer-FX priority band, blind gate, fades,
provenance + `provenanceState` broadcast, all six writers migrated, `ChannelSocket` bypass
deleted, `programmer.*` WS + REST clear-all, docs renumbered. Sessions 2–5 not yet started.
**Scope**: lighting7 (backend) + lighting-react (frontend).
**Supersedes-in-part**: the "no programmer buffer" decision in
[composition-model-prior-art.md](../research/composition-model-prior-art.md)
§"Should we adopt a programmer-style buffer?", and — in
[lighting-composition-model.md](../lighting-composition-model.md) — the Layer 4
placement, the "Divergences" claims that depend on it ("no programmer layer
above playbacks", "no Update command"), the worked examples built on
direct-writes-below-cues ordering, and the layer *numbering* itself: §3.1
renumbers Effects (2→3) and Property Assignments (3→4), so every "Layer N"
reference in that doc — and the hard-coded layer numbers in
`docs/fx-engineering.md` and `docs/cues-engineering.md` — goes stale and is
swept as part of Session 1. The composition *rules* (HTP/LTP categories,
specificity, crossfade, park) stand.

---

## 1. Why revisit this

The composition model (Layers 1–5) is solid and well-tested. What sits *on top
of it* — the way an operator manually sets values and turns them into cues —
was deliberately built without a console-style programmer, and the survey that
drove this proposal found that the programmer's job has ended up split across
**three incompatible mechanisms**:

| Surface | Mechanism | Granularity | Layer | Persistence |
|---|---|---|---|---|
| Fixture pages / channels / stage 3D | `updateChannel` → `DirectWriteStore` | channel | 4 | in-memory, sticky |
| Cue card editor | `cueEdit.setProperty/setChannel` | property | 3 | **DB, immediately** |
| Busking view | `StaticValue`/`StaticSetting` FX instances | property | 2 | in-memory FX |

(Layer numbers in this table are the *current* model's; §3.1 renumbers.)

Concrete problems this causes today:

1. **Record is lossy.** `snapshot-from-live` reads composed Layer 3 + active FX
   but cannot see Layer 4 (`captureLayer3Assignments` reads
   `layerResolver.currentLayer3State` only). Anything busked with a fixture
   slider, MIDI fader, or Locate silently vanishes from the recorded cue.
2. **Priority is inverted vs. every console, and inconsistently enforced.**
   Direct writes sit *below* cues — the prior-art survey itself noted
   "programmer above playback is universal" — and `ChannelSocket.updateChannel`
   (`plugins/ChannelSocket.kt:99`) bypasses the cascade entirely, writing to
   the wire unconditionally. Observed behaviour is "manual write wins until
   the next Layer 3 republish", i.e. it depends on republish timing.
3. **No try-then-discard for live work.** Cue edits auto-persist (discard is
   the only escape); live writes have no capture/commit path at all; busking
   static values are FX with different clear semantics than everything else.
4. **No blind for live work.** Blind exists only inside cue-edit sessions.
   There is no "build a look/FX invisibly, then land it" gesture — which is
   the busking move the whole ChamSys workflow is built around.
5. **No fade on release.** Every clear/release path snaps. A per-channel fade
   primitive exists — `DmxController.setValue(channel, value, fadeMs)` ramps
   via `TickerState`, and `updateChannel` even carries a `fadeTime` field —
   but the frontend hard-codes `fadeTime: 0` and no release path uses it. (The
   Layer 3 cue crossfade is a separate weight-envelope mechanism in
   `CueStackManager`.) Fade-on-clear in §3.1 should drive the existing ramp,
   not add a third interpolator.
6. **One tempo.** A single `MasterClock` BPM scales all BEAT effects;
   WALL_CLOCK effects are outside tempo entirely. No way to run the pan wave
   at half the strobe rate without baking speeds into definitions.
7. **Layer 4 is channel-level while everything else is property-level**,
   forcing `inferTargetForProperty` reverse-engineering and channel→property
   mapping warts in `cueEdit.setChannel`: colour sub-channels get a guided
   rejection ("use setProperty with propertyName='rgbColour'",
   `CueEditSession.kt:523`), while pan/tilt channels fall through
   `resolveChannelToProperty` to the *generic* "no fixture/property backs"
   error (`CueEditSession.kt:511`) with no guidance at all.

None of these are bugs in the composition core. They are the consequence of
not having a single, first-class answer to "where do manual values live".

## 2. What we take from the console model

From the ChamSys/MA/Eos research (primary sources: the MagicQ manual at
`secure.chamsys.co.uk/docs/magicq/manual/` — esp. `programmer`,
`editing_cues`, `palletes`, `cue_stacks`, `busking`, `blind_visualisation` —
plus grandMA3 help at `help.malighting.com` on the programmer/recipes/phasers
and the ETC Eos online help on Update/Blind/Make Absolute), the essential
loop is:

- A **programmer**: a sparse per-(fixture, property) overlay with a **sticky
  touched flag**, holding values (with **palette references preserved**) and
  FX. It is simultaneously the live override for busking and the staging
  buffer that Record serialises. It sits **above playbacks**.
- **Clear** — timed (fade-out), deterministic (stage reverts to exactly what
  playbacks dictate), with an always-visible "programmer holds data"
  indicator.
- **Record** — programmer → cue, with attribute-type **masks** (I/P/C/B) and
  **merge/remove** modes, not just overwrite.
- **Include → edit → Update** — load a cue back into the programmer, tweak,
  write back. Update writes palette *references* when the value came from a
  palette. (MagicQ's default — and Eos's factory default too; Eos's
  `Make Absolute` is the opt-in hardening modifier, not the default. See §5.)
- **Update-without-Include** — press Update with overrides in the programmer
  and get a checklist of every cue/palette you're currently sitting on top
  of. Requires **per-property output provenance** (which layer/cue/palette
  produced the current value) — also the best debugging view in the system.
- **Blind** — a boolean gate on the programmer's output contribution (with a
  fade time), while the programmer keeps working normally. Paired with a
  visualiser source selector (we have Stage3D — a big advantage).
- **Speed masters** — named tempo buses that effects *subscribe to* rather
  than owning speeds. MagicQ has 100; MA2 has 15; MA3 assigns them
  per-attribute inside a phaser.

Explicitly **not** adopted (they don't fit this system or the solo-operator
audience): tracking mode and its apparatus (block/unblock/trace/cue-only —
non-tracking is a legitimate complete product and MagicQ's own recommended
default), the keypad command language, playback pages/holdover, priority
bands beyond programmer-vs-playback, linked palettes (phase-later at most).

## 3. Proposed architecture

### 3.1 New layer stack

```
Layer 1  Parking                   (unchanged — absolute per-channel, transmit-time)
Layer 2  PROGRAMMER                (NEW — values + programmer-owned FX; blind gate;
                                    absorbs today's Layer 4 and busking statics)
Layer 3  Effects                   (cue-owned + manual FX — unchanged engine)
Layer 4  Cue property assignments  (unchanged Layer3Resolver semantics)
Layer 5  Baseline / defaults       (unchanged)
```

Rules:

- An **active programmer value** on a property wins over cue values *and
  suppresses cue-owned FX on that property* (the effect reset/apply pass
  skips cue effects whose target property has an active programmer entry).
  This is the MagicQ LTP rule; for HTP categories we also let the programmer
  win (MagicQ's `Programmer overrides HTP chans` setting, on by default here —
  busking-first system, predictability beats max-merge).
- **Programmer-owned FX** run in the existing `FxEngine` pass with priority
  above all cue-derived priorities (cue priority is `stackId*1M + sort*1K + 1`;
  programmer FX get a reserved band above that). Busking's
  `StaticValue`/`StaticSetting` hack is retired for the plain case: static
  busking values become programmer *values*; blend-mode/distributed statics
  stay FX (see §5.7); busking dynamic FX become programmer *FX*.
- **Blind** = the programmer's contribution is excluded from the merge (gate +
  fade coefficient on enter/exit). The programmer state itself is unaffected.
- **Deterministic release**: clearing a programmer entry re-resolves the
  cascade below it (cues → baseline) — this is `publishLayer4ForKeys` today,
  generalised, plus an optional **fade time on clear**.

### 3.2 Programmer store (backend)

Property-level, replacing `DirectWriteStore`'s channel-level model. Reuses the
existing typed value model from `Layer3Resolver` (`PropertyValue` sealed class,
`serialize()`/`parseAssignmentValue()` round-trip):

```kotlin
class ProgrammerStore {
    // Per (target, propertyName): a recency-ordered stack of OWNER SLOTS —
    // DirectWriteStore's multi-owner model lifted to property level, kept
    // deliberately. Releasing an owner reveals the slot below: flash over a
    // surface fader restores the fader value; locate release restores the
    // busked level; two presets sharing a property release independently.
    // (The behaviours pinned by Layer4OwnershipCollisionTest carry over as
    // programmer tests.)
    data class Slot(
        val owner: Owner,                   // WEB | SURFACE | FLASH | LOCATE | PRESET_PREVIEW | INCLUDE | UNPARK
        val value: ProgrammerValue,         // Hard(PropertyValue) | Ref(paletteId, resolved)
        val touched: Boolean,               // sticky — never value-diffed; drives Record/Update
    )
    data class Entry(
        val target: TargetRef,              // fixture | group (group kept for record shape)
        val propertyName: String,
        val slots: List<Slot>,              // top = winning contribution
    )
    // plus: programmer FX instance ids, blind flag + fade,
    // lastIncludedTarget: CueId | PaletteId | null
}
```

`FLASH` slots are momentary by construction — cleared on release, revealing
the slot below. `UNPARK` slots are created with `touched = false` and are
excluded from Record and the Update checklist: an unpark hand-down is not an
operator edit, and letting it into Record would be a new leak (today's Layer 4
is invisible to Record entirely).

Migration of today's Layer 4 writers (all become programmer writers):

| Today | Becomes |
|---|---|
| `updateChannel` (web) → controller + store | `programmer.set` (property-level); channel-level kept only for the raw Channels debug view |
| MIDI surface faders (`SurfaceActions`) | programmer slots, `owner = SURFACE` — **keeping the existing branch** that routes writes into an open cue-edit session (`SurfaceActions.kt:77-112`) rather than the value layer |
| Flash press/release | momentary `FLASH` slots; release pops the slot, revealing the owner below |
| Locate (`lightLocate.kt`) | programmer entries, `source = LOCATE` — and locate stops destroying effects (`removeEffectsCoveringFixtures` is replaced by programmer suppression, so releasing locate restores them) |
| Preset toggle/preview (`preset:{id}` owner) | programmer entries, `source = PRESET_PREVIEW` |
| Unpark hand-down (`Show.kt:65`) | `UNPARK` slot, `touched = false` — releasable like a manual write but excluded from Record/Update |

`ChannelSocket.updateChannel`'s cascade bypass is deleted as part of this —
every write goes through the engine's publish path.

### 3.3 Provenance

The engine maintains, per `(targetKey, propertyName)`, the identity of the
winning contributor: `{ layer, cueId?, cueStackId?, effectId?, paletteId? }`.
Broadcast as a diffed WS message alongside channel state (throttled; it only
changes on layer events, not per-frame). This powers:

- ownership colouring in the sheets (programmer / cue / FX / parked),
- **Update-without-Include** (the checklist is "distinct cues/palettes under
  my touched entries"),
- a "who owns this value" inspector — the single best debugging view we can
  add, per every console surveyed.

### 3.4 Record / Include / Update

New WS ops (naming aligned with the existing `cueEdit.*` style):

```
programmer.set / setColour / setPosition / clearEntry / clearAll { fadeMs? }
programmer.setBlind { blind, fadeMs? }
programmer.record  { stackId, cueId?, mode: CREATE|MERGE|REMOVE|UPDATE_EXISTING,
                     mask?: PropertyCategory[], source: TOUCHED|ALL|STAGE_SNAPSHOT }
programmer.include { cueId | paletteId, mask?, alsoSelect: true }
programmer.update  { targets?: [...] }   // omitted targets ⇒ Mode B checklist round-trip
```

- **Record from the programmer, not from composed stage state.** This fixes
  the lossy snapshot: what you busked is what records. `STAGE_SNAPSHOT` keeps
  the old capture-everything behaviour (existing `captureCurrentState` logic)
  as an explicit record source, MagicQ-style.
- **Include** pulls a cue's assignments + FX into the programmer with palette
  references preserved, sets `lastIncludedTarget`, and auto-selects the
  fixtures in the UI (MagicQ's `Select Heads on Include` — small detail,
  large workflow impact).
- **Update** writes touched entries back to `lastIncludedTarget` (Mode A). If
  nothing was included, the server answers with the provenance-derived
  checklist of overridden cues/palettes and the client confirms which to
  write (Mode B). Group-shaped entries write back as group assignments,
  preserving the group-hint behaviour record has today.

**Fate of `cueEdit` sessions**: kept. They are exactly Eos-style direct blind
editing (bound form over the cue document, instant persist) and the cue card
editor is good at structured content — triggers, timing, presets, palette
lists. The programmer loop is the *stage-driven* path. The two meet at one
guard: opening a cue-edit session on a cue that is currently Included (or
vice versa) warns. Longer-term the cue card's value cells can delegate to
Include/Update, but that is not required for any phase below.

Naming note: the programmer's **Blind** gate and the cue-edit session's
Live/Blind *mode* are different mechanisms that will now coexist. UI labels
must distinguish them — proposal: "Blind" stays with the programmer gate
(console muscle memory), and the cue-edit mode toggle is relabelled
"Preview edit" wherever both are visible.

### 3.5 Palettes as first-class references

Today "palette" is an ordered colour list in three cascading scopes, resolved
positionally (`P1`, `P2`) at cue-apply time, colour-only. Proposal:

- New entity: `Palette { id, projectId, type: COLOUR|POSITION|BEAM|INTENSITY,
  name, entries: (fixtureKey → PropertyValue) }` with CRUD + "record from
  programmer" (masked by type, selected fixtures merge — MagicQ re-record
  semantics).
- `ProgrammerValue.Ref(paletteId)` and a `PropertyValue`-level ref in stored
  cue assignments: `value` column grows a `ref:{paletteId}` form beside the
  canonical literal. Refs resolve per-fixture at compose time; **editing a
  palette republishes every active cue that references it** — the touring
  feature, and the highest-leverage single item in the console research.
- UI: reference badges on cells (ref vs hard at a glance), **Make Hard** on
  selection, palette pages per type.
- Migration: existing global/stack/cue colour lists keep working as today
  (they are consumed by FX and by `P1`/`P2` literals). New-style refs are
  additive; migrating the positional refs to named palettes can be a follow-up
  inside the palette session, not a blocker.

### 3.6 Speed masters

- `SpeedMaster { id, projectId, name, bpm, source: MANUAL|TAP }`, N per
  project (start with 4 visible, no hard cap). `MasterClock` generalises to a
  bank — one tick flow per master, master 1 = today's global BPM (existing
  `setFxBpm`/`tapTempo` map to it, so nothing breaks).
- `FxInstance.speedMasterId` (null → master 1). Because an FX instance targets
  specific properties, **per-instance master assignment gives "different
  speeds for different FX properties" for everything except composites** — a
  position wave on master 2 and a dimmer chase on master 1 on the same
  fixtures is just two instances. Composite effects are the stated exception:
  a composite computes all of its outputs from a single phase
  (`Effect.calculateComposite`, e.g. `LightningStrike`), so its constituents
  share one master by construction — that coupling is the point of a
  composite. Splitting it (MA3-style per-attribute assignment *inside* one
  instance) is explicitly out of scope.
- WALL_CLOCK effects gain an optional `rateMasterId`: effective interval
  scaled by `master.bpm / 120`. Unassigned wall-clock effects stay fixed —
  current behaviour preserved.
- Cue/preset authoring picks the master per effect application; the busking
  effect pad exposes it; ShowBar/Run get a masters strip with per-master tap
  and BPM entry. MIDI binding to masters is a natural follow-up in the
  control-surface work, not part of this proposal.

### 3.7 UI surfaces

**Programmer sheet** — the centrepiece, and a direct reuse of the
fixtures-list machinery (`src/components/fixtures-list/`): `columns.ts`,
`rowModel.ts`, selection, fan, virtualised `FixturesTable`, and the cell
editors are target-shape-agnostic and already `EditorContext`-aware. What's
new:

- a value source keyed `(targetKey, propertyName)` fed by programmer state +
  provenance + live channel values (today's `useRowValues` reads only live
  DMX),
- a **per-cell ownership state** beyond `isUniform`: *untouched* (dimmed stage
  value, provenance-coloured), *programmer/touched* (highlighted), *palette
  ref* (badge), *parked* (loud, per Eos),
- a toolbar: Clear (timed) · Blind · Record · Include · Update · Locate ·
  Highlight, plus the always-visible programmer indicator (entry count +
  blind state + current edit target) — which also lives in `ShowBar` so it is
  visible from every view.

Reachable as a route and as an embeddable pane in the Program view (see a cue,
Include it, edit in the sheet, Update — the ChamSys flow, blind or live).

**FX sheet** — a sibling view (same rows/columns/grouping, including the
group-rollup mode from `GroupsList`) whose cells show the FX instances
covering that fixture×category as chips: effect name, speed master, intensity;
click → parameter popover; per-chip stop. Sources: active `FxInstance` state +
provenance. This is the "see applied FX by group and fixture" view.

**Blind visualisation** — Stage3D gains a **vis source** selector: `Output`
(today) / `Output + Programmer` (see blind edits over the live show) /
`Programmer only` / `Next GO` (preview the next cue in the active stack).
Implementation: a second composed frame for the selected source, streamed on a
separate WS channel at reduced rate, never touching ArtNet. `Next GO` is
cheap and disproportionately useful for a web controller.

## 4. What stays untouched

`Layer3Resolver` (HTP/LTP categories, specificity, move-in-dark, crossfade
weights), `CueStackManager` (activation, crossfade, effects-snap rationale),
cue/stack data model and Program view structure, park, cue numbering, triggers,
scripts, stomp. The proposal adds a layer and an authoring loop; it does not
re-litigate the composition model.

## 5. Decisions taken (flag if you disagree)

1. **Programmer above playbacks, for HTP categories too.** (§3.1 — MagicQ
   default + its override setting collapsed to one rule.)
2. **Single shared programmer.** Solo-operator system; per-user programmers
   (MA3) are noted as future work — the store is keyed so adding an owner
   dimension later is mechanical, but no session work is spent on it.
3. **Reference-preserving Update**, with **Make Hard** as the explicit
   hardening escape hatch — the same shape MagicQ and Eos both default to
   (Eos's `Make Absolute` is an opt-in Update modifier, not its default). A
   hardening default would silently pin palettes across a whole show.
4. **`cueEdit` sessions survive** as the direct-edit path (§3.4).
5. **Non-tracking stays.** No tracking apparatus.
6. **Simple Clear** (single action + timed variant + clear-selected +
   clear-FX-only). MA's staged three-press clear is nice but is muscle-memory
   UX for hardware; revisit if the simple version bites.
7. **Plain busking statics stop being FX.** Today's `StaticValue`/
   `StaticSetting` instances are not always flat values: with a non-OVERRIDE
   blend mode they compose against the underlying output, and on groups
   `StaticValue` runs step-timed with `distribution: LINEAR` — a windowed
   chase across members (`DimmerEffects.kt:276-298`), not a scalar. So the
   busking UI writes programmer *values* only for the default case (OVERRIDE,
   no spread) and keeps creating FX instances — now programmer-owned — when a
   blend mode or distribution is in play. The effect types survive for
   scripts.
8. **The programmer sheet ships as both a route and a Program-view pane**
   (§3.7); the pane is what makes include→update a one-screen flow.
9. **Layer numbering is rewritten, not aliased.**
   `lighting-composition-model.md` is the source of truth and takes the
   renumber (with `fx-engineering.md` and `cues-engineering.md` swept in the
   same pass); an out-of-band "Layer P" name would leave two numbering
   schemes alive indefinitely.

## 6. Implementation plan — five sessions

Sized for one comfortable 1M-token Claude Code session each; each lands
independently shippable. Order matters for 1→2→3. Session 5 depends only on
1; Session 4 is best scheduled after 3 — palette record-from-programmer
reuses the mask/merge record machinery Session 3 builds.

### Session 1 — Backend programmer core (lighting7)

`ProgrammerStore` (property-level, owner slots, momentary, touched); layer
reordering (programmer above Layer 3 in `LayerResolver.fallbackFor` and the
effect reset/apply pass, cue-FX suppression on programmer-active properties);
programmer-FX priority band; blind gate + fades (clear/blind enter/exit,
driving the existing `DmxController` ramp); absorb all six Layer 4 writers
(§3.2) and delete the `ChannelSocket` cascade bypass; provenance tracking +
WS broadcast; `programmer.*` WS protocol (set/clear/blind —
record/include/update are Session 3).

The `channels.update` compatibility shim routes property-backed channels
through the programmer (colour sub-channels map to `rgbColour` the way
`cueEdit.setChannel` guides today); channels with **no backing property**
get a channel-level sideband within the programmer layer — still above cues,
still cleared by Clear — so the raw Channels debug view keeps working
without resurrecting the cascade bypass.

Sequence the work as two independently revertible halves: **(a)** writer
migration onto the new store with today's layer ordering preserved, then
**(b)** the reorder + cue-FX suppression + provenance — so the delicate
`FxEngine` surgery reviews and reverts separately from the mechanical
migration. Tests at the level of the existing `FxEnginePipelineTest` /
`Layer4OwnershipCollisionTest` suites, which this largely replaces — the
ownership-collision behaviours (flash-over-fader restore, locate release
revealing busked levels, independent preset release) carry over as
programmer tests. Because Session 2 ships the UI, Session 1 also ships
`programmer.clearAll` (WS + REST) as the operator escape hatch for stuck
values in the interim — or land Sessions 1+2 together when deploying to a
live rig.
*Restart required (new classes) — coordinate, may drive a live rig.*

### Session 2 — Programmer UI (lighting-react)

Programmer sheet + FX sheet (fixtures-list reuse per §3.7: new value source,
ownership cell states, FX chips); ShowBar programmer indicator; Clear/Blind
controls; rewire fixture pages, stage views, and busking statics to
`programmer.set` (blend-mode/distributed statics keep creating FX per §5.7);
busking FX pad gains "add to programmer" semantics (its FX become
programmer-owned). The §3.7 toolbar ships Clear/Blind live; Record, Include,
and Update render as disabled placeholders until Session 3. No new backend
work beyond what Session 1 shipped.

### Session 3 — Record / Include / Update (both repos)

Backend ops per §3.4 incl. Mode B checklist; frontend flows in the Program
view and programmer sheet toolbar (record dialog with mask/mode, include from
cue row, update with target confirmation); `snapshot-from-live` reimplemented
as `record { source: STAGE_SNAPSHOT }`; included-vs-cueEdit guard; Stage3D
vis-source selector with `Output + Programmer` and `Next GO` (the second
composed frame lands here since blind editing only becomes truly useful with
it). The vis-source work is severable: if the Stage3D second-frame plumbing
fights back, ship record/include/update alone and carry vis-source into a
follow-up session rather than letting it drag the editing loop.

### Session 4 — Palettes as references (both repos)

`Palette` entity + CRUD + record-from-programmer; `Ref` value form through
programmer, cue assignments, and resolver; republish-on-palette-edit; palette
pages, cell ref badges, Make Hard; migration/coexistence with positional
`P1`/`P2` refs per §3.5.
*New table + migration — restart required. Remember the migration gate: most
migrations are Postgres-gated and silently no-op on the SQLite dev DB, so
verify against Postgres.*

### Session 5 — Speed masters (both repos)

`MasterClock` → bank; `SpeedMaster` entity + persistence; per-instance
`speedMasterId` through FX definitions/presets/cues/busking; wall-clock
`rateMasterId`; masters strip UI with tap/BPM; compatibility mapping of the
existing global BPM to master 1.
*New table + migration — same restart and Postgres-gate caveats as
Session 4.*

**Deliberately deferred** (revisit after the five land): per-user programmers,
MA staged clear, highlight/lowlight personality values, linked palettes,
MIDI binding for speed masters, per-attribute masters inside one FX instance,
rate (percentage) masters as distinct from speed masters. As each session
lands, promote any still-wanted deferred item into
[followups.md](followups.md) with a trigger condition — items parked only in
this proposal fall off the backlog, since nothing re-reads a shipped proposal.

## 7. Open questions

1. **Group entries in the programmer** — store group-shaped entries (better
   record shape, matches cue assignments) vs always fan to fixtures with
   group hints at record time (matches how group controls write today).
   Recommendation: store group-shaped when the write came from a group
   control, fixture-shaped otherwise; the specificity rule already handles
   overlap.
