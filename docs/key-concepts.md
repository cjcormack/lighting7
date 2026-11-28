# Key concepts

The desk's main ideas in one place — fixtures, venues, attributes, scripts, DMX, FX and groups — with the common development tasks and the external integrations. Each subsystem's engineering doc goes deeper.

### Fixtures
Fixtures represent physical lighting devices. They use trait-based composition:
- `WithDimmer` - brightness control via `dimmer: Slider`
- `WithColour` - RGB color control via `rgbColour: Colour`
- `WithStrobe` - strobe effects via `strobe: Strobe`
- `WithUv` - UV lighting via `uv: Slider`
- `WithPosition` - pan/tilt control via `pan: Slider`, `tilt: Slider`

Add new fixtures in `fixture/dmx/` by extending the appropriate base classes and traits.

**One-shot triggers** (stage-view plan session 9) are their own attribute kind, not a
`@FixtureAttribute`: a `DmxTrigger` declared with `@FixtureTrigger(description, label, armName,
armDescription)` — the Twin Shot's `output1` / `output2`, armed by its `master`. Nothing that resolves
an attribute by name sees one, so no Look, palette, cue row, FX, programmer value, Record or busk
pad can hold it, and every write boundary refuses one **by name** (`TRIGGER_NOT_STORABLE`,
`fixture/TriggerGuard.kt`'s `TriggerIndex`), MCP tools and surface bindings included. The desk owns the
channels (`state/TriggerOutput.kt`, above composition and under park through `Show.outputSource`): a
trigger sits idle except for one ~300 ms pulse per fire, its arm follows the desk's arm, a raw channel
write on either is dropped and a park at a firing level refused. A fire is an event
(`state/EffectsService.kt`) — a cue's **events** on GO, the cannon's hold-to-fire panel, a surface's
`fire` — and needs the desk's **arm** (desk-wide, 60 s, dropped on a project switch or any stack
stop, runtime only); in Blind it is rehearsed (drawn, never sent). Loaded/spent tubes are machine-local
(`effect_tube_state`). See `docs/fixtures-engineering.md` §"@FixtureTrigger" and
`docs/cues-engineering.md` §"Cue events".

**Fixture commands** (fixture optics plan session 7) follow the trigger's shape: resets and lamp
control are a `DmxCommand` declared with `@FixtureCommand(label, description, holdMs, confirm)` — the
Revolution's five resets, the Robe's lamp on, lamp off and seven resets, the MAC 250's reset and lamp, and the reset
the Varytec, Shehds, Fusion, Orbit and Slender carried as a setting option until then. No Look,
palette, cue row, FX, programmer value or binding can hold one: every write boundary refuses one
**by name** (`COMMAND_NOT_STORABLE`, `fixture/CommandGuard.kt`'s `CommandIndex`, beside each
`TriggerIndex` call), and on a channel a command **shares** with an attribute (the MAC 250's shutter) the
output's band guard sends a value in a command's band as idle, whatever wrote it. `state/CommandOutput.kt`
holds a command's level — and the preconditions it declares (`alongside`: the MAC 250's CTC, prism and
open gobo, the Robe's closed shutter) — for `holdMs`, above composition and under park through
`Show.outputSource`, then gives the channels back; a **dedicated** command channel (no attribute covers
it) is held idle between commands and a raw write on it dropped. One command per unit (`COMMAND_BUSY`),
refused in Blind (`COMMAND_BLIND`) or on a parked channel (`COMMAND_PARKED`); a blackout does not cut a
hold short, a project switch does. Stored rows holding one were stripped once at startup
and are on every sync import. See
`docs/fixtures-engineering.md` §"@FixtureCommand".

**The end of the night** (venue-visit plan S6): a command declares a `role` (`LAMP_ON`, `LAMP_OFF`,
`RESET`, else `OTHER`) and a strike its `restrikeMs` and `strikeSpacingMs`, so `state/RigSafetyService.kt`
can run one role over the selection or the whole rig — a strike staggered and restrike-aware — and
*Make rig safe*: lamps off, the holds waited out, a **runtime-only** blackout
(`GlobalScalerStateHolder.safeBlackout`), then — once each lamp-off's declared `coolDownMs` has passed,
counted down meanwhile — *Ready to switch off* on every window until anything sends output again (D14). *Undo* strikes those lamps again and releases the blackout, remembered per
project while the desk runs. *Quit desk* is separate, admin-only and never remote. See
`docs/fixtures-engineering.md` §"Bulk commands and Make rig safe".

**Travel time** (fixture optics plan session 8, D14) is drawn, never output: `@FixtureType(travel =
Travel(panDegPerS, tiltDegPerS, beamMs, colourMs))` is how fast a type's mechanics move, and a SPEED
slider declared `@FixtureAttribute(timing = TimingRole.POSITION | BEAM | COLOUR | ALL,
timingSecondsPerStep, timingFastFrom)` is one of the fixture's own timing channels — the Revolution's
Focus (pan/tilt), Colour and Beam Timing, a move's **duration** at 1 s a step, 0 its own speed. Only the
Stage view reads them (`frontend/src/lib/travel.ts`); the desk sends every value as composed. Most
speeds are estimates (`FU-MANUAL-S8-TRAVEL`); movers' vector speed channels are deliberately not
modelled. See `docs/fixtures-engineering.md` §"Travel and timing channels".

### Venues
A **venue** is a project of kind `VENUE` (`projects.kind`: `SHOW | VENUE | VENUE_VERSION`, default
`SHOW`) holding a hall's rig — its patch, universes, riggings, regions, scene and viewpoints (venue
plan, `docs/plans/completed/venue-plan.md`). Its own rows are its **draft**; publishing freezes the draft as
the next **version**, a project of kind `VENUE_VERSION` made by `sync/KeptUuidCopy.kt` (export → a
fresh project uuid → import, every record uuid kept) and indexed by the machine-local
`venue_versions` (`venue/VenueVersions.kt`). A version is never listed (REST, MCP's `list_projects`,
the switcher), never current, and refuses every write: `routes/projectWriteGuard.kt` sits at the head
of the whole `/projects/{id}/…` subtree, so every route under it — `withProject`'s and the rest —
answers a snapshot's write 409 `VENUE_VERSION_READ_ONLY`, and an operator's stored write to a venue
403 `VENUE_ADMIN_ONLY` (opening one and the runtime gestures pass). Record is off in a venue
(`VENUE_RECORD_OFF`, REST and MCP). A venue **syncs as its own repo of published versions**
(session 4, `sync/VenueRepoSync.kt`): attached through the same Sync tab as a show's, the branch the
latest version plus `venueVersion.json`, each version a commit `vN: note` tagged `vN`; *Sync now*
materialises every tag the desk lacks as a snapshot (rebuilding `venue_versions`, which only ever
gains rows) and pushes every version the remote lacks, and a clean draft follows the newest in. The
draft never syncs (`VENUE_DRAFT_NOT_SYNCED`); a version's sync subtree is `VENUE_VERSION_READ_ONLY`.
`docs/sync-engineering.md` §"Venue repos".
What separates a version from the draft is `venue/VenueDiff.kt`, classified **changes output** or
**drawing only** — `docs/fixtures-engineering.md` §"Venue versions".

A **show on a venue** (session 3) pins `projects.venue_uuid` + `.venue_version` — both null for a
standalone show, which behaves exactly as before — and its rig is its own rows plus the pinned
version's, less its **hides** (`show_hides`, six record kinds) and less the version's Preset seating:
`RigScope` (`models/RigScope.kt`, `VenueLayer`) is the one place that says so, `RigScope.own` is
still the show's own. Keys and names are one namespace (`KEY_TAKEN_BY_VENUE`; unhiding over a show's
reuse is `KEY_TAKEN_BY_SHOW`); a write through a show to a venue's row is 409 `VENUE_OWNED`, never a
404 (`venue/VenueRigGuard.kt`) — a show makes four changes to one: it hides it, it recolours a venue
patch or group (`show_colours`, tables-colours plan D10: no base and no drift, laid over the version by
`RigScope.colourOf`, dormant under a hide), it chases a venue group in its own order (`show_group_orders`,
group-order plan: merged over the version's order by `RigScope.members`, which every reader of a group's
order goes through, and the group PUT's `memberOrder` on a venue group; sync `formatVersion` 44,
`docs/groups-engineering.md` §"Member order"), and it makes a venue patch's **facets** its own (session 5, `show_overrides`): Address, Position, Focus, Media and
Name (venue-rig-fixes plan D24, the show's name for it, sync `formatVersion` 56), each whole, identity (key, type,
head) still the venue's, laid over the version's patch by
`RigScope.effective` — so the loader, the Patch list, the Stage view and `describe_rig` all read the
overridden rig. Each override records the venue's value as its `base`; the pinned version's value
differing from it is **drift**, answered by *Keep mine* (base := venue's) or *Take venue's* (a revert),
and `ShowOverrides.drift` is the read session 6's Update uses (`docs/fixtures-engineering.md` §"Facet
overrides"). A hidden patch's overrides stay dormant; only patches take overrides
(`FU-VENUE-ELEMENT-OVERRIDES`); a show group may hold venue patches, a venue group never a show's (D12); a machine
override of a venue record resolves against the **venue's** id (P4), so a universe's address is the
same everywhere and is still set through a show. **Extract** moves a show's rig into a new venue,
publishes v1 and pins the show, in one transaction, its dry run that run rolled back
(`venue/VenueExtract.kt`). A show's archive (`formatVersion` 24, session 4) is its own rows, its
hides (`showHides/`), its recolours (`showColours/`, `formatVersion` 36) and its pin in `project.json` (`venueUuid`, `venueVersion`, a `venueRepo` hint);
the importer seeds its uuid maps from the pinned snapshot, and a show whose pinned version the desk
lacks is 409 **`VENUE_MISSING`** — on import, add-remote-project, clone and pull — naming the venue and
its repo, so a fresh desk imports the venue's repo first. A clone keeps the pin; a show with a
missing version cannot be made current and, current at startup, loads no rig
(`venue/VenuePins.kt`). **Detach** (session 7, `venue/VenueDetach.kt`, D9) flattens a show into a
standalone one: the effective rig at its **pin** — overrides applied, hides and Preset seating left
out — written into its own rows with every uuid kept, every `VenueLinks` link re-pointed onto them, the
venue's machine overrides of them **copied** to the show's id (P4), its hides, overrides and pin gone, its recolours written into the copies;
the venue is untouched, and a detached show syncs as a standalone show with no `formatVersion` change.
The pin becomes the show's **origin** (`projects.detached_from_venue_uuid` + `.detached_from_version`,
venue-attach plan D1; `project.json`'s `detachedFromVenueUuid` / `detachedFromVersion`, also no
`formatVersion` change): a clone keeps it, Extract clears it, and so does **Attach** (venue-attach plan
session 2, `venue/VenueAttach.kt`), Detach in reverse: a standalone show put onto a venue version, its
records paired with the version's (`venue/VenueAttachPairing.kt`, a pure function answering an
`AttachPlan`, three ways against the origin when the desk holds it) — a paired row goes and its links
are re-pointed onto the venue's by row id, what the show changed becomes overrides, hides and releases,
what it keeps while a venue record matches it takes a fresh uuid and hides that record (P5), *Take
venue's* renames its references through `RigKeyReferences.rewrite` — then Update's guards
(`venue/VenueRigChecks.kt`: clashes block, an introduced address overlap is P8's decision), in one
transaction whose review is the same run rolled back. A live show reloads, and one attached behind on
output runs against its pin. Also no `formatVersion` change. `docs/fixtures-engineering.md` §"Attach".
A venue's **Preset** seating (D6) is copied, never referenced: *Create* takes one
(`seatingPresetUuid`), *Replace from preset* swaps the show's for a fresh copy — the show's own row, no
`block`, the preset's uuid kept (`venue/SeatingPresets.kt`). Both are either role's: each changes only
the show. `docs/fixtures-engineering.md` §"Detach" and §"Seating presets". A show whose pin is older than the installed version is **behind** (session 6,
`venue/ShowBehind.kt`, `ProjectDto.venue.behind`): **`OUTPUT`** when an output change reaches it — one
to a patch it hides, or a re-address of one whose Address it overrides, does not — else **`DRAWING`**,
a banner. Behind on output it **opens held**: the loader judges it first
(`state/VenueHoldService.kt`) and every controller sends zero on every channel **but a parked one**
(a park gets through the hold), while the show still
composes and draws (`dmx/OutputHold.kt`); arm, fire and fixture command are 409 `VENUE_HOLD`. *Run
against* lifts the hold for that pin pair on this desk run, keeping the installed version's trigger
and command channels idle — and a publish or venue pull that puts a *running* show behind does that
for it rather than blacking it out mid-run; **Update** (`venue/VenueUpdate.kt`) moves the pin and re-points every
`VenueLinks` link in one transaction, dropping links to removed records, after a review that is the
same run rolled back. `docs/fixtures-engineering.md` §"Behind, the hold and Update". Its overrides travel as `showOverrides/{uuid}.json` at the same v24 (session 4
reserved the folder, so a session-4 desk refuses a show that carries one). A venue a show pins refuses
delete (`VENUE_IN_USE`) until its shows are detached or deleted. **Venue parks** (`formatVersion` 27): a venue's parked channels are
its rig, versioned with it, and a show on a venue runs its pinned version's parks overlaid by its own
re-parks and less its **releases** (`show_park_releases`, keyed by universe and channel, never a park's
uuid) — `models/VenueParks.kt` is the one resolution, read by the loader, a switch's hand-over
(`ParkManager.storedParks`), the reload carry, the hold and the project list's `parks` count. Unparking a venue park in a show releases
it (the version is never written), unparking a show's re-park brings the venue's back, a venue park
change is output for the hold unless the show parks or releases that channel, and a park in a venue is
an admin's (`parkError VENUE_ADMIN_ONLY`). `docs/fixtures-engineering.md` §"Venue parks". The Projects page (session 7) draws a
Venues row (each venue's card, an admin's *Extract a venue…*) over the Shows, each show card with its
venue badge, and a show's menu carries *Update venue* and *Detach*, a standalone show's *Attach to venue…*. `docs/fixtures-engineering.md`
§"Shows on a venue".

### Attribute System
Attributes provide a unified interface for fixture and group control:

**Attribute Interfaces** (`fixture/attribute/`):
- `Slider` - Single value control (dimmer, UV, pan, tilt)
- `Colour` - RGB colour with `redSlider`, `greenSlider`, `blueSlider`
- `Position` - Pan/tilt via `panSlider`, `tiltSlider`
- `Strobe` - Strobe control with `fullOn()`, `strobe(intensity)`

**Aggregate Interfaces** (for groups):
- `AggregateSlider` extends `Slider` - adds `memberValues`, `isUniform`, `minValue`, `maxValue`
- `AggregateColour` extends `Colour` - adds `memberValues`, `isUniform`

**Value Semantics**:
- Single fixtures: `value` always returns the actual value (non-null)
- Groups: `value` returns null if members have different values
```kotlin
group.dimmer.value = 200u        // Sets all members
val level = group.dimmer.value   // null if non-uniform
val uniform = group.dimmer.isUniform
val all = group.dimmer.memberValues  // [200, 200, 200]
```

### Scripts
Lighting scripts use embedded Kotlin via `LightingScript` base class:
- Access fixtures through the `fixtures` property
- Use coroutines for timing and animation
- Scripts are cached by SHA-256 hash

### DMX Control
- `DmxController` interface abstracts DMX output
- `ArtNetController` implements ArtNet protocol
- `Universe` represents subnet + universe addressing
- Use `ControllerTransaction` to batch channel updates with fades
- `EasingCurve` enum provides curve types for smooth fades (sine, quad, cubic, step)
- **Handing the rig between projects never dips a kept channel to 0**: a project switch freezes the
  outgoing controllers on their last frame (`DmxController.freeze` — a blackout bar the channels the
  incoming project parks) before tearing anything down, and `Fixtures.register` waits for each old
  controller's final frame before building its successor. A **reload** — `switchProject(id, reload =
  true)`, passed only by a sync pull, a venue Update, Detach, Extract, Attach and a venue's Discard — carries
  the running show across (`show/ShowCarry.kt`, `Show.resume`: the frame, live cues and standbys, the
  programmer and Locate, hand-run FX) and crossfades what changed over `RESUME_FADE_MS`; every
  other same-id switch is a clean restart. Parks are per project and channel-level. See
  `docs/dmx-engineering.md` §"Handing the rig from one show to the next"

### FX System
Tempo-synchronized FX for continuous animations without complex scripts:
- **Own tempo** - A beat FX follows a speed master or its *Own tempo*, `ownBpm` (20–300): its `cycle` still beats, at that tempo, following no master (fx-speed plan D3; `fx/FxTimingChoice.kt`). It runs on the wall-clock loop at `ownBpm / 60`, so a new tempo moves only the rate and keeps the phase. A wall-clock FX keeps its `cycle` in seconds on a rate master or *Natural* (no rate master, D4), and has no Own tempo. Stored as `own_bpm` on every FX home (sync `formatVersion` 49, `minReader` 49), carried by Record, Include, the FX routes and the MCP tools. It replaced *Seconds* (venue-visit plan D7), which every request still reads by D5 — a beat FX onto Own tempo 120 at twice its cycle, the same output. A new FX is one bar on the master tagged for its `family`, else master 1 (venue-visit plan D16) — see `docs/fx-engineering.md` §"Own tempo"
- **Amount, Direction, Runs** - Every running FX has an `amount` (0–1: a weight applied *after* the blend, `out = before + (blended − before) × amount × envelope × stackAmount`, so a Replace at 50 % sits halfway over what is underneath and an Around movement's radius halves; `stackAmount` is its cue stack's runtime *FX amount*, D10), a `direction` (Forward, Backward, Bounce, applied to each member's phase) and an optional `runs` (run N cycles, each member handing back after its own; the instance ends N cycles past the largest offset, a programmer layer's kept *spent*). `intensityMultiplier` is the envelope a fade moves, never a scale on the raw output: an immutable ramp on the clock (`fx/FxEnvelope.kt`), so an FX **fades by amount with its cue** (a GO's outgoing FX shrink to nothing and are removed, its incoming grow, one both cues run continues untouched, a cue brought back mid-fade keeps its instances — effects plan session 4) and with the programmer's press, release, Clear and leaving Blind at the programmer fade (D13). An instance let go on a fade is `leaving` — nobody's, removed at 0 by the wall-clock pass. Stored on every FX home, synced from `formatVersion` 30 — see `docs/fx-engineering.md` §"Amount, direction and runs"
- **The spread's shape** - An FX's ordering is its **Order**, and four more fields shape it (effects plan D4, §3.3): `spread` (0–2, how much of the cycle the members cover; 1 is the Order's own offsets), `wings` (mirrored wings, only under an asymmetric Order — `CENTRE_OUT` and `CENTRE_IN` are two wings already and refuse it), `segments` (neighbours sharing one phase) and `parts` (the spread repeating every N members; 0 is off), applied in `DistributionPlan.of` after the Order; `distinctSlots` is the rank count after Parts, so a step chase stays gap-free, and N runs end N cycles past the largest *shaped* offset. `FxDynamics` fields, edited in place; stored on every FX home, synced from `formatVersion` 33; the editor's head ladder, patterns and words are `frontend/src/lib/spreadShape.ts`, pinned to the desk by `src/test/resources/fx/spreadShape.fixture.json` — see `docs/fx-engineering.md` §"The spread's shape". **Each head later by** (fx-settings plan D6): `spreadUnit` `STEP` makes `spread` the FX's own steps each head starts after the one before (0–16), resolved at expansion from the list its frontmatter's `stepsFrom` names (Colour Cycle, Palette Steps, Slot Steps), so a chase stays on its steps whatever the head count; refused on an FX with none, absent `CYCLE`, and a new step FX starts one step behind at every door (D7). Column `spread_unit`, synced from `formatVersion` 43
- **One FX over a selection** - An FX may target an ordered list of fixtures and groups (effects plan D3): `FxTargetsRef.Selection` beside `TargetRef` (`models/TargetRef.kt`), FX-only, so a value row, a layer's targets and a binding cannot hold one and their write boundaries refuse it; stored as `targetType: "selection"` with the list's canonical JSON as `targetKey` on a cue's ad-hoc FX and a Look's FX, synced from `formatVersion` 34. `FxEngine.buildSelectionExpansion` flattens it in order — a group as its members, a head named twice once — into one instance's members, distributed and shaped as a group's. `POST /fx/add` takes `targets`, Record and Include keep it one FX, MCP's ad-hoc FX take `targets`; the programmer's `+ FX` and ⌘K *Apply FX* start one over the selection's rows — see `docs/fx-engineering.md` §"Over a selection"
- **Over** - What an FX's spread counts where it lands on fixtures' elements (FX editor follow-ups plan D7): *Fixtures* (`ElementMode.WHOLE` — each fixture one unit, its elements alike, MagicQ's Dup Elements), *Elements* (`FLAT`) or *Each fixture* (`PER_FIXTURE`). Two defaults named apart (D8): a door that starts an FX with none — `POST /fx/add`, `/programmer/fx`, `/groups/{name}/fx`, MCP's FX tools — gets `NEW_FX_DEFAULT`, `WHOLE`; a stored FX naming none is `STORED_DEFAULT`, `PER_FIXTURE`. Synced from `formatVersion` 40 — see `docs/fx-engineering.md` §"Element Mode: what *Over* counts"
- **SpeedMasterBank** - Per-project bank of named tempo buses (persisted, portable in sync); slot 0 = master 1, the global tempo the script API (`setBpm`/`tapTempo`) and a bare AI `set_bpm` mean (that tool takes an optional `speedMasterUuid` to retune any other master, and `create_speed_master` adds one). FX subscribe via `speedMasterUuid` (null → master 1), and wall-clock FX scale their cycle via `rateSpeedMasterUuid` (null → unscaled); both are settable on every authoring surface. A master may also carry a `family` (intensity/colour/position/beam — the apply-time routing default, one per project) and a follow link (`follow_num`/`follow_den`/`follow_target_uuid`: the leader *drives the follower's clock*, so its beats land on the leader's rather than free-running; chains allowed, cycles refused; tempo writes on a follower are refused with `SPEED_MASTER_FOLLOWER`) — see `docs/fx-engineering.md` §"Family routing and follow". One engine pass per (conflated) tick wake-up, however many masters tick. `SpeedMasterBank.beats` fans every master's beat boundaries into one keyed stream (`speedMasters.beat`) — master 1 included, under its real uuid once the bank has loaded. Hardware drives masters through the `tempo` / `tap` / `tempoScale` surface functions (×½ and ×2, also `speedMasters.scale` on the rail and the master sheet, refused `SPEED_MASTER_RANGE` outside 20–300, fx-speed plan D12), and the selection's Local FX through `fxSpeed` (`midi/SelectionFxSpeed.kt`, D13: an encoder a quarter of an octave a detent, a fader ×¼–×4 picking up at the lead FX's Speed). A **cue** can set masters too (venue-visit plan S16, D20): its `speedMasters` list, master and BPM pairs, is written to the bank on every GO into one of its entries — GO TO, GO BACK and a cue another stack already holds included — and every loose press, never on a reload's re-GO (`setTempo = false` in `Show.resume`, beside `fireEvents`); a follower or a missing master is skipped and logged, never failing the GO (`fx/CueTempo.kt`). A tap wins until the next cue. Each master keeps a **live** tempo and may run the programmer's **hold** over it (fx-speed plan D9, `state/ProgrammerTempo.kt`): a GO moves only the live tempo, a tap or typed tempo on a held master moves the hold, Release lands the live tempo, the write-through persists the live tempo, and Record writes the holds into a cue's *Tempo on GO* (`routes/programmerTempoRecord.kt`) — `docs/fx-engineering.md` §"Live and held tempo". Synced from `formatVersion` 35 — see `docs/cues-engineering.md` §"Speed masters on a cue"
- **MasterClock** - One master's tempo clock (20-300 BPM), emits 24 ticks/beat; phase is a pure function of the tick counter (`MasterClock.phaseForDivision`). A *follower's* clock is driven by its leader instead of by a timer (`adoptDriven`/`driveTo`), and its derived BPM is reported unclamped — the 20–300 range guards a timer it doesn't have
- **FxEngine** - Processes active FX, applies to fixtures via transactions
- **FxRegistry** - Unified registry for all FX types (built-in and script-defined)
- **FxTargetable** - Common interface for Fixture and FixtureGroup (enables unified FX targeting)
- **FxTargetRef** - Reference type distinguishing fixture vs group targets
- **CycleLength** - Timing constants (ONE_BEAT, TWO_BEATS, ONE_BAR, TWO_BARS, etc.)
- **BlendMode** - How FX combine: OVERRIDE, ADDITIVE, MULTIPLY, MAX, MIN

FX interfaces:
- **Fx** - Pure phase-based: `(phase, context) → FxOutput`
- **StatefulFx** - Tick-based with internal state: `(tick, deltaMs, context) → FxOutput` (e.g., CandleFlicker)

Built-in FX types — **all 14 live as `.fx.kts` resources** under
`src/main/resources/fx/`, compiled into the `FxRegistry` at startup by `FxFileLoader`. There are
no compiled-in FX classes: the parallel `fx/effects/*.kt` set was deleted (sweep item D7)
once it had drifted a file behind the resources and nothing but tests constructed it.
- **Intensity**: Wave (fx-settings plan D2: one `shape` — Sine, Ramp up, Ramp down, Triangle, Square,
  Pulse — on the dimmer, UV and the beam attributes, its family Beam on a beam attribute, D5; a
  Square's or Pulse's `widthUnit` *One head* on for one slot's share, D8, its preset *Chase*),
  Flicker, LightningStrike, CandleFlicker, FluorescentFlicker (their levels `low` / `high`)
- **Colour**: ColourCycle (literal colours only; `between` Cut, Fade, Smooth or Split — its presets *Colour
  Fade*, *Colour Wave* and *Split Chase*; a colour chase that steps, it or Palette Steps, reaches colour-wheel heads,
  each step on the nearest slot, and on Split holds a wheel's split where its slots declare one, `fixture/dmx/ColourSplits.kt`), RainbowCycle, ColourStrobe, ColourFlicker; and ColourWave and ColourFade,
  **legacy** (`legacy: true`): they run and edit what is stored, but no picker or listing offers them,
  since Colour Cycle draws each a DMX step apart (fx-settings plan D3)
- **Position**: Path (D2: one `shape` — Circle, Figure 8, Random about a centre, or a Line between
  two ends; its presets *Sweep*, *Pan Sweep*, *Tilt Sweep*)
- **Beam**: Wave on `zoom`, `iris`, `frost` or `focus`, which the picker offers as its presets *Zoom
  Wave*, *Iris Pulse*, *Frost Pulse* and *Focus Pull* (D4), only on a head that has the attribute
- **Steps**: PaletteSteps (through generic value palettes of one family, filed under that family)
  and SlotSteps (a setting's option names, `beam`; `between` Cut or Split, a colour wheel holding the split of two neighbouring slots), one family in the library (`family: steps`) — `FxOutput.Steps`, which the **target**
  resolves per head, so each head lands on its own value for each step (effects plan D8;
  `docs/fx-engineering.md` §"Step FX")

Colour Fade and Path's sweep presets bounce by Direction at half a bar by default (frontmatter
`defaultDirection`, `defaultCycle`, a preset's `cycle` and `direction`). The old ids, Breathe, `pingPong`, `SPLIT` and the
composite mode are read only by `sync/LegacyNames.kt` and refused at every write. The eleven level
waves Wave replaced (Sine Wave, the ramps, Square Wave, Pulse, Dimmer Strobe and the four beam waves)
are refused at every stored write too, but a script's `effect("SineWave", …)` and MCP's FX tools still
run one, as Wave with its parameters (`LegacyNames.runnableFx`); so do the six movements Path replaced,
as Path, and the renamed parameters of the Flicker family, Lightning Strike, Colour Strobe and Colour
Cycle's `fadeRatio`. `LegacyEquivalenceTest` holds each to its old script, kept under
`src/test/resources/fx/legacy/` — see `docs/fx-engineering.md` §"Wave" and §"Path, Colour Cycle and the
renamed parameters".

There is no static FX. A fixed level, colour, position or setting is a **value** on the
fixture's own attribute — in the programmer, a Look or a cue — never an FX: StaticValue,
StaticSetting, StaticColour and StaticPosition were removed for that reason. A stored FX naming
one is skipped with a warn where a cue or Include spawns it (`FxSpawner.createFxInstanceOrNull`).

A **palette** may hold one FX instead of values — an *FX palette*, the busking pad for a
named FX. It is always generic (it names no target; the layer's targets are the fan-out), always
exactly one FX, and never both an FX and rows: which one a palette holds is its identity,
refused at the write boundary if a write would flip it. Its family is its FX's library `family` on
the attribute it names, via `fxAttributeFamily` (Beam on a beam attribute, else
`attributeFamilyForFxFamily`), so `controls` is refused by name;
a Beam FX palette holds a Wave naming its beam attribute or Slot Steps (naming the setting it steps), and a
Palette Steps palette is its palettes' family, not its library family `steps`. A **Look's** FX,
unlike a palette's, always name their own target — a fixture, a group or a selection (absolute-looks
plan D2): `validateLookFx` refuses `deferred`, the importer drops one, and
`state/DeferredLookEffectsMigration.kt` deleted this database's once. `CueComposer.fxForLayer`
runs a Look's `FxEntry`s on their own targets and a palette's one `StoredFx` on each of the
layer's. See
`docs/lighting-composition-model.md` §"A palette holds a value *or* an FX". **A palette reference is
a value** (palette-references plan D1–D9): a cue's own row, a Look row and a programmer entry may hold
`pal:{uuid}` naming a value palette of the same project (else 400 `PALETTE_REFERENCE_INVALID`),
resolved per head as a press resolves (`fx/PaletteReferences.kt`) — a cue's rows in
`buildCueAssignmentsForCue`, a Look's in `LookRegistry.expand`, the programmer's as a
`ProgrammerValue.Reference` — keeping its last value, marked, where it no longer resolves. The
indexed `palette_uuid` on both row tables, derived from the value, says what references a palette; a
palette edit moves every reference (`republishForPaletteEdit`), a referenced palette refuses delete
and `?force=true` hardens each reference to its literal first, and *Copy the values* is `POST
/looks/{id}/harden` and `/cues/{id}/harden` and MCP's `harden_values`. Sync `formatVersion` 47,
`minReader` 47. A **value** palette's press writes references, never a layer (session 2,
`fx/PaletteReferencePress.kt`; in a stored cue `routes/cueReferencePress.kt`): every door through
`ProgrammerLayerStack.toggle`, the rings read from the references, `omitted` / `notCovered` in the
answer, and Record, Include and Update carry a reference as one; an FX palette is still a layer. See
`docs/lighting-composition-model.md` §"A palette reference is a value". **A Look is pressed
whole** (absolute-looks plan D1, P1): on puts one layer of it on top, naming no targets and no mask,
off takes every layer of it off, whatever is selected; a Look layer's mask is a layer setting (D3). A
palette press is per
target on **both** arms and is the exact inverse of the pad's lit ring: "already on" is the record
covering every pressed target, and an off press clears them from every layer of that record. An
on press *releases* two things, both per target, not per target set, so a layer that only overlaps
the press is narrowed rather than left lit underneath: through a solo bank on a busk page, its
siblings of any kind (`ProgrammerLayerStack.toggle`'s `releaseSiblings`, resolved by the press
route, never read by the cook) — a Look sibling, which cannot be narrowed, off whole, and every
sibling off whole under a Look press (P3); and through every palette door, the other palettes of the
pressed palette's family and kind (apply-model plan D2, D6; `fx/LayerPress.kt`). A Look replaces
nothing. The library itself is a flat list ordered by name. See §"The busk layout" in the same doc.

Scripts name an FX rather than constructing one — `effect(id, params)` resolves it through the
registry, so a script reaches the same vocabulary as the UI and cues, user FX included:
```kotlin
fixture.applyDimmerFx(fxEngine, effect("Wave", "low" to "40"), FxTiming(CycleLength.TWO_BEATS))
fixture.applyColourFx(fxEngine, effect("RainbowCycle"), FxTiming(CycleLength.ONE_BAR))

// Every apply site takes a speed master; null means master 1 (unscaled for the rate master)
fixture.applyDimmerFx(fxEngine, effect("Wave", "shape" to "PULSE"), speedMasterUuid = speedMasterUuidAt(2))
```

Scripts can also register custom FX that appear in the library API:
```kotlin
registerFx(FxRegistration(
    id = "my-effect", name = "My Effect",
    family = "intensity", outputType = FxOutputType.SLIDER,
    compatibleAttributes = listOf("dimmer"),
    factory = { params, _, _ -> MyCustomEffect(params) },
))
```

### Fixture Groups
Type-safe fixture groups for treating multiple fixtures as a single unit:
- **FixtureGroup<T>** - Generic group with compile-time type safety, implements `FixtureTarget`
- **GroupMember** - Fixture wrapper with position and metadata (pan/tilt offsets, tags)
- **SpreadOrder** - Phase distribution patterns (NORMAL, UNIFIED, CENTRE_OUT, etc.)
- **MultiElementFixture** - Support for fixtures with multiple controllable elements

**Group Attribute Access**: Groups expose trait attributes through extension properties:
```kotlin
val group = fixtures.group<HexFixture>("front-wash")

// Direct property access (returns AggregateSlider/AggregateColour)
group.dimmer.value = 255u                    // Set all dimmers
group.rgbColour.value = Color.RED            // Set all colours
group.uv.value = 128u                        // Set all UV

// Uniformity detection
if (group.dimmer.isUniform) {
    println("All at ${group.dimmer.value}")
} else {
    println("Mixed: ${group.dimmer.memberValues}")
}

// Access individual channels
group.rgbColour.redSlider.value = 200u       // Set all reds
```

**Hierarchical Groups (SubGroups)**: Groups can contain other groups of the same type:
```kotlin
val frontHexes = createGroup<HexFixture>("front-hexes") {
    addSpread(listOf(hex1, hex2))
}
val atmosphericHexes = createGroup<HexFixture>("atmospheric-hexes") {
    addSpread(listOf(hex3, hex4))
}
val allHexes = createGroup<HexFixture>("all-hexes") {
    addGroup(frontHexes)
    addGroup(atmosphericHexes)
    // Or: addGroups(listOf(frontHexes, atmosphericHexes))
}
// allHexes.fixtures returns all 4 HexFixtures
// allHexes.subGroups returns [frontHexes, atmosphericHexes]
allHexes.dimmer.value = 255u  // Sets all 4 fixtures
```

**Flatten Method**: Use `flatten()` to get all fixtures including from sub-groups:
```kotlin
val allHexes: FixtureGroup<HexFixture> = ...
val allFixtures = allHexes.flatten()          // List<FixtureTarget>
val hexOnly = allHexes.flattenAs<HexFixture>() // List<HexFixture>
```

**Group FX Targeting**: A single `FxInstance` targets the entire group. The `FxEngine` expands
the FX to group members at processing time, applying distribution offsets.

Groups are created via `DbFixtureLoader` from DB patch records. Internally, the loader calls `Fixtures.register {}`:
```kotlin
// Internal to DbFixtureLoader — not available in user scripts
fixtures.register {
    val hex1 = addFixture(HexFixture(universe, "hex-1", "Hex 1", 1))
    val hex2 = addFixture(HexFixture(universe, "hex-2", "Hex 2", 13))

    createGroup<HexFixture>("front-wash") {
        addSpread(listOf(hex1, hex2), panSpread = 60.0)
        configure(symmetricMode = SymmetricMode.MIRROR)
    }
}
```

Applying FX to groups:
```kotlin
val group = fixtures.group<HexFixture>("front-wash")

// A pulse in Normal order
val fxId = group.applyDimmerFx(fxEngine, effect("Wave", "shape" to "PULSE"), order = SpreadOrder.NORMAL)

// Unified colour across all fixtures
group.applyColourFx(fxEngine, effect("RainbowCycle"), order = SpreadOrder.UNIFIED)
```

## Common Development Tasks

### Adding a New Fixture Type
1. Create class in `fixture/dmx/` extending appropriate base
2. Add `@FixtureType` annotation with name
3. Implement required traits (Dimmer, Colour, etc.)
4. Use `@FixtureAttribute` to annotate controllable attributes
5. Declare its 3D `body` on `@FixtureType` (archetype, a mover's head, lens) where the kind and
   the words would guess wrong; a type whose body is the lantern it is hung with sets
   `acceptsLantern` instead (the generic dimmer — the lantern library and each unit's focus are
   `docs/fixtures-engineering.md` §"Lanterns and focus")

### Multi-Mode Fixtures
Some fixtures support multiple DMX channel modes (set via DIP switches). Use the sealed class pattern:
- `DmxChannelMode` - Interface for mode definitions with `channelCount` and `modeName`
- `MultiModeFixtureFamily<M>` - Marker interface associating fixture with its mode

**Pattern:**
```kotlin
sealed class MyFixture(...) : DmxFixture(...), MultiModeFixtureFamily<MyFixture.Mode> {
    enum class Mode(override val channelCount: Int, override val modeName: String) : DmxChannelMode {
        MODE_6CH(6, "6-Channel"),
        MODE_12CH(12, "12-Channel")
    }

    @FixtureType("my-fixture-6ch")
    class Mode6Ch(...) : MyFixture(...), FixtureWithDimmer { ... }

    @FixtureType("my-fixture-12ch")
    class Mode12Ch(...) : MyFixture(...), FixtureWithDimmer, MultiElementFixture<Head> { ... }
}
```

**Example:** `SlenderBeamBarQuadFixture` - 4-head LED bar with 5 modes (1/6/12/14/27 channel)

### Writing a Lighting Script

Three script types with focused API surfaces (controlled by `ScriptType` enum, stored per-script in DB):

**`FX_APPLICATION`** — apply FX to fixtures (most common). `effect(id, params)` resolves a
name through the `FxRegistry`; `speedMasterUuidAt(n)` names a speed master by its 1-based index:
```kotlin
val wash = fixture<HexFixture>("front-wash-1")
wash.fx {
    dimmer(effect("Wave", "low" to "40"), CycleLength.TWO_BEATS)
    colour(effect("ColourCycle"), CycleLength.ONE_BAR, speedMasterUuid = speedMasterUuidAt(2))
}
setBpm(128.0)
```

**`FX_DEFINITION`** — define custom FX types. The factory returns an `Fx` the script
implements itself; to build on a built-in, look its registration up via `fxRegistry` and call its
own factory rather than reimplementing the maths:
```kotlin
registerFx(FxRegistration(
    id = "my-effect", name = "My Effect",
    family = "intensity", outputType = FxOutputType.SLIDER,
    compatibleAttributes = listOf("dimmer"),
    factory = { params, _, _ ->
        object : Fx {
            override val name = "My Effect"
            override val outputType = FxOutputType.SLIDER
            override fun calculate(phase: Double, context: FxContext) =
                FxOutput.Slider(if (phase < 0.5) 255u else 0u)
        }
    },
))
```

**`GENERAL`** (`LightingScript`) — full-power scripts with DMX, fixtures, FX, coroutines. Can read fixture state but cannot register fixtures (registration is handled by DbFixtureLoader from DB patches).

### Modifying REST API
Add routes in `routes/` package using Ktor Resources for type-safe routing.

## External Integrations

- **DMX Hardware**: ArtNet protocol over network
- **Philips Hue**: HTTP API via Ktor client
- **MCP (Claude) and remote access**, both **through Lighting7 Cloud** (the cloud-p1 plan): Claude calls
  the MCP URL the cloud serves and authorises for the install, and the cloud relays each JSON-RPC call
  over the control channel to `McpProtocol` (`cloud/McpRelay.kt`), run as the desk user the cloud
  account is linked to. The desk runs no OAuth server and no `/mcp` of its own; the ngrok tunnel is gone
  too. The tools are the show-running tools (`ai/AiTools.kt` — no script tool),
  `describe_rig` and the show-setup tools (`ai/SetupTools.kt`: projects, venues — list, extract,
  publish, a show on one, attach — and copying between projects (`ai/VenueSetupTools.kt`), the stored-data
  ones taking a `projectId` so a show is built without switching to it; patch, stage and
  rigging, the scene — its cloths' fabric and paint, and `upload_scene_image`, the one tool that
  takes file bytes, a PNG or JPEG of at most 16 MB, about 5 MB through the cloud, with no remote-access gate — cue stacks and
  prompt-book markup, built from documents the model reads; Claude's own history, `list_history`
  and `undo_changes` (`ai/HistoryTools.kt`), which take back the grant's own show entries only — and
  `render_view`, a PNG of a stage viewpoint drawn offscreen by a signed-in desk window on the desk's
  own listener, never a remote one, so Claude can check the model it built). A **remote session** — the
  desk's own SPA opened from the portal — reaches a second, **public listener** (`mcp.port`, default
  `127.0.0.1:8414`) serving the whole desk (UI, REST, WS) through the bundled sidecar
  `lighting7-link`. **The port split is the security boundary**: everything on that listener is
  remote, decided by port and never by a forwarded header, its one caller a remote session's bearer
  token (no session cookie, no password sign-in, `REMOTE_PASSWORD_SIGN_IN`), and `installRemoteHardening`
  applies there — no remote bootstrap, a lockout on prove-once, an Origin check, no QR flows,
  scripts refused unless an admin allows them, and arming, firing and fixture commands likewise
  (`requireEffectsAccess`, `requireCommandsAccess` — MCP's `run_fixture_command` included), the three
  switches `cloud/RemotePolicy.kt`'s. Anything mounted on it is reachable from the
  internet, and a script-capable route must call `requireScriptAccess`. The browser's Sentry
  tunnel, `/api/sentry`, is mounted there too, signed out included: its DSN check, 1 MB cap and
  telemetry gate are all that keep it from being an open relay. See
  [docs/mcp-engineering.md](mcp-engineering.md)
- **OSC in** (`osc/`, the OSC plan): QLab and other show-control senders drive the desk over UDP
  or SLIP-framed TCP (default 8415, both) with a fixed address set under `/lighting7/` — `/stack/act-1/cue/5/go`,
  `/look/haze-fill/on`, `/busk/storm/1/2/press`, `/grandmaster 0.5`, `/tempo/2 128` — names as slugs,
  an ambiguous one refused, values taken whole. Every
  address resolves to a `Binding` and runs through `midi/SurfaceDispatch.kt`, the dispatch MIDI
  uses (D2), so OSC adds functions rather than show logic; the explicit forms (GO TO, `on`, `off`) are
  no-ops when already so (D4). Off by default and allowlisted by sender address or range, LAN only (D5). The
  receive path is its own `osc-1` thread (and one `osc-tcp-N` reader per TCP connection) that never touches the
  database or the show, handing off through a `DROP_OLDEST` channel to one guarded collector (D8);
  `OscFloodOutputTimingTest` holds a fade's timing under a flood. A sender with *Replies* on gets a
  `/lighting7/reply` per message, through a queue the collector only offers to (D14). Each message is an `osc.message` Sentry transaction named by its
  pattern, at most five a second. No arming, triggers, commands, Record, undo or project switching over
  OSC (D6). See [docs/osc-engineering.md](osc-engineering.md)
- **Lighting7 Cloud** (`cloud/`, the cloud-p0 plan): a hosted service the desk connects *out* to. An
  admin on the desk's own listener **adopts** the desk into a cloud organisation (RFC 8628's flow,
  polled by the backend, `cloud { baseUrl, enabled }` in `local.conf`); while adopted, `CloudChannel`
  keeps one WebSocket open to `/v1/desk`, authenticated by the install key (Ed25519, in
  `CredentialStore`), with a `health` heartbeat and a reconnect capped at 30 s. The wire contract is
  `cloud-protocol/`. **The cloud is never on the show's path**: the connector's own work runs on two
  `cloud-N` threads (Ktor's non-blocking socket I/O on its engine's dispatcher), sends through two bounded queues that never block (heartbeats drop the oldest; everything else is
  refused when full, never displaced), writes the database only on a transition (adopt, unadopt,
  revoke, a switch, a remote session's open and end, a link or invite's change) and takes no lock the show takes. Every change here answers one
  question in review: *does the show run exactly as before with the cloud down, hung or hostile?*
  `CloudOutageOutputTimingTest` holds a fade's output to that. See
  [docs/cloud-engineering.md](cloud-engineering.md) §"Show safety"
