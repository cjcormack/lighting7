# Fixture System Engineering Documentation

This document describes the fixture abstraction layer that maps physical lighting devices to controllable properties.

## Overview

The fixture system provides a type-safe, trait-based abstraction over raw DMX channels. Instead of manipulating channel numbers directly, scripts and UI work with named fixtures and semantic properties like `dimmer`, `rgbColour`, and `strobe`.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Scripts / UI / API                              │
│                                                                         │
│   fixture.dimmer.value = 255u                                           │
│   fixture.rgbColour.fadeToColour(Color.RED, 1000)                       │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                            Fixtures                                     │
│                     (Registry & Transaction)                            │
│                                                                         │
│   ┌──────────────────────────────────────────────────────────────────┐  │
│   │                  FixturesWithTransaction                         │  │
│   │         Provides fixtures bound to a transaction                 │  │
│   └──────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          Fixture Classes                                │
│                                                                         │
│   ┌───────────────────────┐                                             │
│   │   Fixture (sealed)    │ ◄── Base class                              │
│   └───────────┬───────────┘                                             │
│               │                                                         │
│               ├─────────────────────┐                                   │
│               ▼                     ▼                                   │
│   ┌───────────────────┐   ┌───────────────────┐                         │
│   │    DmxFixture     │   │    HueFixture     │                         │
│   └─────────┬─────────┘   └───────────────────┘                         │
│             │                                                           │
│             ▼                                                           │
│   ┌────────────────────────────────────────────────────────────────┐    │
│   │  Concrete Fixtures (HexFixture, QuadBarFixture, etc.)          │    │
│   │                                                                │    │
│   │  Implements traits: WithDimmer, WithColour, WithPosition, etc. │    │
│   └────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         Property Types                                  │
│                                                                         │
│   ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────┐     │
│   │    DmxSlider    │  │    DmxColour    │  │ DmxFixtureSetting   │     │
│   │  (single value) │  │   (RGB group)   │  │  (enum mapping)     │     │
│   └────────┬────────┘  └────────┬────────┘  └──────────┬──────────┘     │
│            │                    │                      │                │
│            └────────────────────┼──────────────────────┘                │
│                                 ▼                                       │
│                    ┌────────────────────────┐                           │
│                    │  ControllerTransaction │                           │
│                    │   (batched updates)    │                           │
│                    └────────────────────────┘                           │
└─────────────────────────────────────────────────────────────────────────┘
```

## Core Types

### Fixture (Base Class)

```kotlin
sealed class Fixture(
    val key: String,           // Unique identifier (e.g., "front-wash-1")
    val fixtureName: String    // Display name (e.g., "Front Wash Left")
)
```

**Key members:**
- `typeKey`: Extracted from `@FixtureType` annotation
- `fixtureProperties`: List of properties marked with `@FixtureProperty`
- `withTransaction()`: Creates a copy bound to a transaction
- `blackout()`: Sets dimmer to 0 and colour to black

### DmxFixture

```kotlin
abstract class DmxFixture(
    val universe: Universe,    // ArtNet subnet + universe
    val firstChannel: Int,     // Starting DMX channel (1-512)
    val channelCount: Int,     // Number of channels used
    key: String,
    fixtureName: String
)
```

Adds DMX-specific addressing. The `channelDescriptions()` method returns a map of channel numbers to human-readable names for debugging and UI.

## Trait Interfaces

Fixtures compose capabilities through trait interfaces (in `fixture/trait/`):

| Trait | Property | Purpose |
|-------|----------|---------|
| `WithDimmer` | `dimmer: Slider` | Master brightness control |
| `WithColour` | `rgbColour: Colour` | RGB color mixing |
| `WithStrobe` | `strobe: Strobe` | Strobe effect control |
| `WithUv` | `uv: Slider` | UV channel control |
| `WithPosition` | `pan: Slider, tilt: Slider` | Pan/tilt control |

## Property Interfaces

Properties (in `fixture/property/`) provide a unified interface for fixture and group control:

### Slider

```kotlin
interface Slider {
    var value: UByte?                          // Nullable for groups (null if non-uniform)
    fun fadeToValue(value: UByte, fadeMs: Long)
}

interface AggregateSlider : Slider {
    val memberValues: List<UByte?>   // Values from all members
    val isUniform: Boolean           // True if all same
    val minValue: UByte?             // Min across members
    val maxValue: UByte?             // Max across members
}
```

### Colour

```kotlin
interface Colour {
    val redSlider: Slider
    val greenSlider: Slider
    val blueSlider: Slider
    var value: Color?                          // Nullable for groups
    fun fadeToColour(colour: Color, fadeMs: Long)
}

interface AggregateColour : Colour {
    override val redSlider: AggregateSlider
    val memberValues: List<Color?>
    val isUniform: Boolean
}
```

### Strobe

```kotlin
interface Strobe {
    fun fullOn()                    // Disable strobe, full output
    fun strobe(intensity: UByte)    // Enable strobe at speed
}
```

## DMX Property Implementations

### DmxSlider

Maps a single DMX channel to a slider (in `fixture/dmx/`):

```kotlin
class DmxSlider(
    val transaction: ControllerTransaction?,
    val universe: Universe,
    val channelNo: Int,
    val min: UByte = 0u,      // Clamp minimum
    val max: UByte = 255u     // Clamp maximum
) : Slider
```

- Reads/writes through the transaction (not direct to controller)
- Automatically clamps values to min/max range
- `value` always returns non-null for single fixtures
- Throws if used without a transaction

### DmxColour

Groups three channels as RGB:

```kotlin
class DmxColour(
    transaction: ControllerTransaction?,
    universe: Universe,
    redChannelNo: Int,
    greenChannelNo: Int,
    blueChannelNo: Int
) : Colour
```

Creates three `DmxSlider` instances internally.

### DmxFixtureSetting

Maps an enum to DMX levels for mode/program selection:

```kotlin
class DmxFixtureSetting<T : DmxFixtureSettingValue>(
    transaction: ControllerTransaction?,
    universe: Universe,
    channelNo: Int,
    settingValues: Array<T>
)
```

**Usage pattern:**
```kotlin
enum class ProgramMode(override val level: UByte) : DmxFixtureSettingValue {
    NONE(0u),
    SOUND_ACTIVE(201u),
}

@FixtureProperty
val mode = DmxFixtureSetting(transaction, universe, channelNo, ProgramMode.entries.toTypedArray())

// In script:
fixture.mode.setting = ProgramMode.SOUND_ACTIVE
```

The `valueForLevel()` method finds the appropriate enum value for a raw DMX level (useful when reading back state).

## Annotations

### @FixtureType

```kotlin
@Target(AnnotationTarget.CLASS)
annotation class FixtureType(
    val typeKey: String,
    // … kind, lengthM, acceptsLength …
    val body: FixtureBody = FixtureBody(),   // the 3D body the Stage view draws
    val acceptsLantern: Boolean = false,     // hung with a lantern from the library
    val depthOfField: Double = -1.0,         // how fast focus goes soft; -1.0 = the family's
)
```

Marks a fixture class with a unique type identifier. Used for:
- REST API fixture type filtering
- Serialization/deserialization
- UI grouping

`body` (`fixture/FixtureBody.kt`) says what the type *looks like*: an `archetype` (profile, box
profile, fresnel, PAR, flood, downlight, mover, batten, blinder, effect, cannon, tape), a mover's
`head` (spot, wash, profile, bar) and a `lensDiameterM`. Every field defaults to *inherit*, and a
type that declares none answers `body: null` on `GET /fixture-types` — the Stage view then chooses
from the kind, the type's words and its tilt axis, as it did before a type could say. It is
presentational only, like every stage field. `acceptsLantern` is the other half: a type whose body
is **the lantern the operator hangs it with** (§"Lanterns and focus"), which today is the generic
dimmer alone. A type declares one or the other; `LanternLibraryTest` pins that every declared
mover body agrees with the desk's own mover test (`RigBriefing.isMovingHead`).

`depthOfField` (fixture-optics plan D9) says how fast the type's focus goes soft off its focal
plane: the Stage view's blur, in field radii, per unit of **relative** focus error `|f − d| / f` —
larger is softer. `-1.0` (the default, like the dimensions') leaves it to the body's family
(`DEPTH_OF_FIELD` in the frontend's `stage3d/bodies/archetype.ts`, 3 for a profile, tuned by eye
against a Source Four Revolution throwing 24 m — `frontend/docs/stage-vis-engineering.md`
§"Focus"), and reflects as `depthOfField: null` on `GET /fixture-types`. Only a type with a FOCUS
channel is drawn by it, and no type in the library declares one today: the family's value serves
the Revolution. Like `body`, it is presentational; nothing on the desk reads it.

### @FixtureProperty

```kotlin
@Target(AnnotationTarget.PROPERTY)
annotation class FixtureProperty(
    val description: String = "",
    val category: PropertyCategory = PropertyCategory.OTHER,
    // … composition, bundleWithColour, compactDisplay, axis …
    val degMin: Double = Double.NaN,
    val degMax: Double = Double.NaN,
    val inverted: Boolean = false,
    val focusNearM: Double = Double.NaN,
    val focusFarM: Double = Double.NaN,
    val fineOf: String = "",
    val rpmMax: Double = Double.NaN,
    val indexDegMax: Double = Double.NaN,
    val blade: Blade = Blade.NONE,
    val depthMax: Double = Double.NaN,
    val media: MediaSlot = MediaSlot.NONE,
)
```

Marks a property as controllable. The `fixtureProperties` list on `Fixture` collects these via reflection for:
- Channel description generation
- REST API property enumeration
- FX engine targeting

**A property may not be named an alias.** Stored rows and FX targets name a property by its Kotlin
name, and `canonicalPropertyName` reads `colour` / `color` / `rgbcolour` (any case) as "this head's
RGB colour", rewriting it to `rgbColour` at every lookup. A property *named* one of those would never
be reached: its rows would be rewritten to a name the head does not have and silently dropped. So a
colour wheel is `colourWheel`, and `FixturePropertyNameAliasTest` walks every fixture and element
class and fails on any name the alias would rewrite (`rgbColour` itself is its own canonical form).

**A slider's optics.** The Stage view reads optional fields off the property descriptor
(`SliderPropertyDescriptor`). `NaN` means unset, because an annotation cannot default a `Double` to
null, and reflects as null:

- `degMin` / `degMax`: a PAN or TILT slider's travel in degrees (with `axis`), or a ZOOM slider's
  full beam angle, at DMX min / max.
- `focusNearM` / `focusFarM`: a FOCUS slider's nearest and farthest focal distance, in metres from
  the aperture, at DMX min / max. The Stage view focuses at that fixed distance wherever the head
  points; a slider that declares neither racks over the beam's own throw instead
  (`frontend/docs/stage-vis-engineering.md` §"Light lands through one surface shader"). Declare both
  or neither: `FocusRangeTest` holds every FOCUS slider in the library to a usable range. The
  desk solves the same mapping backwards — *Focus here* (§"Focusing a head on a point") and Locate,
  which parks a declared range at its middle **distance** (21 m on 2–40 m) rather than mid-DMX
  (3.8 m), since DMX is linear in 1 / distance.
- `inverted`: reverses the mapping. On a FOCUS slider DMX min is far focus, as on the MAC 250, whose
  chart runs "Infinity → 2 meters".
- A ZOOM slider's `degMin` / `degMax` may run either way: the Source Four Revolution's DMX 0 is its
  widest, so it declares `degMin = 35.0, degMax = 15.0`. `ZoomAnglesTest` holds every ZOOM slider
  in the library to declaring both; a zoom that declares neither is silently inert, the view keeping
  the family's fixed angle. The Robe ColorSpot 575's three-step zoom is the one exemption, by name,
  until the fixture optics plan's session 5 makes it a setting.
- `fineOf`: names the coarse property this one is the low byte of, so the pair decodes as one
  value — the 16-bit `coarse × 256 + fine`, rescaled to the coarse range (`combineFinePair`). Empty,
  the default, on every other property, and reflected as null.
  It generalises the PAN_FINE / TILT_FINE pairing to any 16-bit pair — pan and tilt keep theirs. A
  fine property carries its coarse one's category, and every client finder that picks *the*
  property of a category skips it (`findFineProperty` in `frontend/src/store/fixtures.ts` finds it
  by name).
- `rpmMax` / `indexDegMax`: on a GOBO_ROTATION slider whose wheel has a **GOBO_ROTATION_MODE**
  channel — a setting whose bands are named `INDEX`, `ROTATE_FWD` and `ROTATE_REV` — the speed in
  RPM at DMX max in a rotate band, and the angle in degrees at DMX max in the index band (DMX min is
  0°). The Stage view decodes the rotation through the mode (`resolveGoboRotation` in
  `frontend/src/components/stage3d/beamOptics.ts`): an angle while indexing, a speed while rotating,
  turning the way the band names, and still in any other band. A wheel with no mode channel spins as
  its rotation channel's bands always said. The Source Four Revolution's front wheel is the one
  today.
- `blade` / `depthMax`: on a framing shutter's SHUTTER or SHUTTER_ROTATION slider, which blade it
  drives (`Blade.NONE`, the default, reflects as null) and, on the insertion, its depth at DMX max.
  See §"Framing shutters".

**A setting's media.** `media` (`MediaSlot.NONE`, the default, reflects as null) marks a setting
whose options can be **loaded** — `GEL`, `GOBO` or `GOBO_OR_GEL` — and rides the setting's
descriptor (`SettingPropertyDescriptor.media`, each option's `loadable`). What is loaded is each
unit's, not the type's: see §"Fitted media".

Every value a fixture declares that its manufacturer does not state carries an `// Estimate:`
comment at its source saying what it rests on (fixture optics plan D15); the rig checks that settle
them are listed in `docs/plans/manual-validation.md`.

A focus range carries a comment naming its source: the manufacturer's document, or what an estimate
rests on where the manufacturer publishes nothing. "Infinity" is written as 40 m, the Stage view's
longest throw. A conventional's focus is not here: it is per placement, on the patch (§"Lanterns and
focus").

### Framing shutters

A profile's framing shutters are four blades in its gate, each pushed in and turned. A DMX head that
drives them from its channels (the Source Four Revolution's shutter module, ch 24–31) declares each
blade as two sliders in two categories (fixture optics plan D4):

- **SHUTTER** — the blade's insertion. It names its `blade` and declares `depthMax`, its depth at DMX
  max, in the lantern focus's unit: a **fraction of the field's diameter**, so 0.5 reaches the
  centre and 1 closes the field. DMX min is out, and depth rises linearly to `depthMax`; `inverted`
  puts the blade fully in at DMX min instead.
- **SHUTTER_ROTATION** — the blade's angle. It names its `blade` and declares `degMin` / `degMax`,
  the angle at DMX min and max (the zoom's and pan's fields, honouring `inverted` the same way). The
  blade turns about the middle of its own edge, positive clockwise as seen from behind the head.

`Blade` is `TOP · BOTTOM · LEFT · RIGHT` — the lantern focus's wire order — named for the edge of the
light it cuts, seen from behind the head down its beam. The blades are fixed in the **head**, so they
turn with its pan and tilt, as the metal does. A moving head's are named as a **hung** head tilted out
(positive tilt, which is how `POST /programmer/aim` reaches the stage from a balcony at pan centre)
shows them: there the top blade cuts the top of the pool. The same head swung over the top to reach
the point the other way cuts it at the bottom, and so does a standing head tilted out positive — a
standing head is a hung one turned over. Which side of the real head each frame sits on is an
estimate (`FU-MANUAL-S4REV-OPTICS` step 4).

Both categories are LTP and in the BEAM mask group, and stored and recorded like any other slider;
neither is in the template grammar or Spread's vocabulary, so neither can be spread. **Locate** takes every blade out
(the DMX for depth 0: DMX min, or max where inverted) and squares every rotation (the DMX for 0°,
solved through its degree range — 128 on the Revolution's ±45°), as ChamSys locates them.

The Stage view draws a DMX head's blades through the same shader path as a lantern's
(`frontend/docs/stage-vis-engineering.md` §"The lantern's focus: the cut, the gate and the oval"):
the director reads the eight channels every frame and packs them where a lantern's focus would be,
and a fixture with DMX blades never draws a lantern's beside them.

`ShutterBladesTest` holds the library to it: every SHUTTER names a blade and declares `depthMax`,
every SHUTTER_ROTATION names a blade and declares a degree range, a fixture (or cell) declares at
most one of each per blade, nothing else declares `blade` or `depthMax`, and no type with framing
shutters is `acceptsLantern` — so a unit's blades have one source. The Revolution's blade sides,
depth and rotation sign are estimates (`// Estimate:` at the source; `FU-MANUAL-S4REV-OPTICS`).

### @FixtureTrigger — one-shot triggers

```kotlin
@FixtureTrigger("Tube A", label = "A", armName = "master", armDescription = "Master enable")
val output1: DmxTrigger = DmxTrigger(universe, firstChannel, armChannelNo = firstChannel + 2)
```

A **one-shot trigger** (stage-view plan session 9, D15) is an output that spends something physical
when it fires — a confetti tube — so it is **its own property kind, not a `@FixtureProperty`**. A
`DmxTrigger` is a channel and its **arm** channel, nothing more: it is not a `Slider` and carries no
transaction, so nothing that resolves a property by name — the programmer, a cue row, a Look row, a
template, an effect target, Record, a surface fader — finds one, and nothing composes or crossfades
it. The Equinox Twin Shot MKII is the one fixture with triggers: `output1` (A) and `output2` (B),
armed by its shared `master`, which is no property either.

- **The desk owns the channels.** `state/TriggerOutput.kt` (one per show, rebuilt on every register
  change) holds every trigger channel at idle and every arm channel at the desk's arm, **above
  composition and under park**, through the controllers' park source (`dmx/LayeredParkSource.kt`:
  park on top, the trigger output under it, every `DbFixtureLoader.loadFixtures` handed
  `Show.outputSource`). A raw `updateChannel` on one of them is dropped (logged) rather than parked
  in the programmer's sideband; a **park at or above the fire threshold (51) is refused** on them —
  a park beats the trigger output, so it would be a held fire — while a park below it is a lock-out
  and allowed. A reload seeds them idle (`DbFixtureLoader` zeroes their buffer after the register
  rebuild), so no value carried across a repatch can raise one.
- **A fire is an event**, `state/EffectsService.kt`: a backend-timed pulse to `FIRE_LEVEL` for
  `FixtureTriggers.PULSE` (300 ms) and back to idle. Three doors — a cue's **events** (§"Cue events"
  in `cues-engineering.md`), the cannon's hold-to-fire panel (`POST …/patches/{id}/fire`) and a MIDI
  `FireTrigger` — and **every one needs the desk's arm** (`POST …/effects/arm {on, seconds?}`):
  desk-wide, 60 s by default, dropped on a disarm, a lapse, a project switch or any stack stop, and
  runtime only. While armed and not blind every cannon's arm channel is high. **Blind rehearses**:
  the fire is announced and drawn, nothing is sent, no arm is needed, no tube is spent — as is a fire
  a window asks to rehearse (`rehearse: true`, a window whose vis source is the programmer).
- **Loaded and spent** are the physical tubes, machine-local (`effect_tube_state`: patch uuid,
  trigger, `spent_at`): a fire marks its tube spent, `POST …/patches/{id}/reload` loads it again, and
  firing a spent tube warns (409 `TRIGGER_SPENT`) and sends nothing.
- **Refused by name at every write boundary** (`fixture/TriggerGuard.kt`'s `TriggerIndex`): a Look
  row or effect, a template row or effect, a cue row or ad-hoc effect, a programmer value, a live
  effect (`FxTargetFactory`, every door an effect is spawned through) and a surface binding naming a
  trigger or its arm — on a fixture, on a group with a cannon in it, or, for a row with no target
  of its own, by any trigger's name — answer 400 `TRIGGER_NOT_STORABLE` naming every refused row.
  Record and Update capture from the programmer, which can hold none, and a busk pad presses a record
  that can hold none. The sync importer strips such a row from an older archive, and a one-off pass at
  startup stripped the rows stored before this (`state/TriggerRowStrip.kt`, to be deleted once run).

On the wire a trigger is a `TriggerPropertyDescriptor` (`type: "trigger"`, its channel, its arm
channel and its label) at the end of a fixture's `properties` — so the cannon's panel can name its
tubes, and a client drawing controls from the list skips it. The DMX sheet names the channels
`Tube A (trigger)` and `Master enable (arm)`.

## Transaction Pattern

Fixtures require a `ControllerTransaction` to read/write values. This ensures:

1. **Batched updates**: Multiple channel changes apply atomically
2. **Read-after-write consistency**: Reading a value you just set returns the new value
3. **Cross-universe atomicity**: Changes to multiple universes apply together

### Usage

```kotlin
// Create transaction from controllers
val transaction = ControllerTransaction(controllers)

// Get fixtures bound to transaction
val fixturesWithTx = fixtures.withTransaction(transaction)
val hex = fixturesWithTx.fixture<HexFixture>("front-wash")

// Make changes (queued, not sent yet)
hex.dimmer.value = 255u
hex.rgbColour.value = Color.BLUE

// Apply all changes
transaction.apply()
```

### withTransaction() Method

Each fixture class implements:

```kotlin
override fun withTransaction(transaction: ControllerTransaction): HexFixture
```

This creates a new fixture instance with the same configuration but bound to the given transaction. The original fixture remains unchanged.

## Fixtures Registry

The `Fixtures` class manages:
- Controller registration
- Fixture registration with grouping
- Change notification

### Registration

```kotlin
fixtures.register {
    val controller = addController(ArtNetController(Universe(0, 0)))

    addFixture(
        HexFixture(Universe(0, 0), "front-1", "Front Wash 1", 1, 1),
        "front", "wash"  // Group names
    )
    addFixture(
        HexFixture(Universe(0, 0), "front-2", "Front Wash 2", 13, 2),
        "front", "wash"
    )
}
```

### Fixture Groups

Fixtures can belong to multiple groups for batch operations:

```kotlin
val frontFixtures = fixtures.fixtureGroup("front")
frontFixtures.forEach { it.blackout() }
```

## Adding a New DMX Fixture

### Step 1: Create the Class

```kotlin
@FixtureType("my-fixture")
class MyFixture(
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, CHANNEL_COUNT, key, fixtureName),
    WithDimmer,           // If it has a dimmer
    WithColour            // If it has RGB
{
    companion object {
        const val CHANNEL_COUNT = 8  // Total DMX channels
    }

    // Copy constructor for withTransaction()
    private constructor(
        fixture: MyFixture,
        transaction: ControllerTransaction,
    ) : this(
        fixture.universe,
        fixture.key,
        fixture.fixtureName,
        fixture.firstChannel,
        transaction,
    )

    override fun withTransaction(transaction: ControllerTransaction) =
        MyFixture(this, transaction)
```

### Step 2: Define Properties

Map each DMX channel to a property:

```kotlin
    // Channel 1: Dimmer
    @FixtureProperty
    override val dimmer = DmxSlider(transaction, universe, firstChannel)

    // Channels 2-4: RGB
    @FixtureProperty
    override val rgbColour = DmxColour(
        transaction, universe,
        firstChannel + 1,  // Red
        firstChannel + 2,  // Green
        firstChannel + 3   // Blue
    )

    // Channel 5: Mode selector
    enum class Mode(override val level: UByte) : DmxFixtureSettingValue {
        MANUAL(0u),
        AUTO(128u),
    }

    @FixtureProperty
    val mode = DmxFixtureSetting(transaction, universe, firstChannel + 4, Mode.entries.toTypedArray())
}
```

### Step 3: Register in Script

```kotlin
fixtures.register {
    addController(ArtNetController(Universe(0, 0)))

    addFixture(MyFixture(Universe(0, 0), "my-1", "My Fixture 1", 1))
}
```

## DMX Channel Layout Reference

When implementing fixtures, refer to the manufacturer's DMX chart. Common patterns:

| Pattern | Channels | Example |
|---------|----------|---------|
| Dimmer only | 1 | Simple dimmer pack |
| RGB | 3 | LED wash |
| RGBW | 4 | LED wash with white |
| RGBAW+UV | 6 | HexFixture style |
| Full feature | 8-16 | Moving heads, complex LEDs |

## Multi-Mode Fixtures

Many professional fixtures support multiple DMX channel modes (personalities), selectable via DIP switches. For example, a fixture might offer:
- **6-channel mode**: Basic control (dimmer, strobe, color presets)
- **14-channel mode**: Per-head control with global dimmer
- **27-channel mode**: Full control with fine positioning

### Infrastructure

Two interfaces support multi-mode fixtures:

#### DmxChannelMode

```kotlin
interface DmxChannelMode {
    val channelCount: Int   // Number of DMX channels
    val modeName: String    // Human-readable name
}
```

#### MultiModeFixtureFamily

```kotlin
interface MultiModeFixtureFamily<M : DmxChannelMode> {
    val mode: M
    val familyName: String  // Auto-derived from class name
}
```

### Implementation Pattern

Use a **sealed class hierarchy** where:
- The sealed base class contains shared enums and common functionality
- Each mode is a distinct subclass with its own `@FixtureType` annotation
- Each subclass implements only the traits available in that mode

```kotlin
sealed class MyBarFixture(
    universe: Universe,
    firstChannel: Int,
    channelCount: Int,  // Passed from subclass
    key: String,
    fixtureName: String,
    protected val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, channelCount, key, fixtureName),
    MultiModeFixtureFamily<MyBarFixture.Mode>
{
    // Mode enum
    enum class Mode(
        override val channelCount: Int,
        override val modeName: String
    ) : DmxChannelMode {
        MODE_6CH(6, "6-Channel"),
        MODE_14CH(14, "14-Channel")
    }

    // Shared enums for all modes
    enum class Colour(override val level: UByte) : DmxFixtureSettingValue {
        RED(10u), GREEN(20u), BLUE(30u)
    }

    // Mode-specific subclasses
    @FixtureType("my-bar-6ch")
    class Mode6Ch(...) : MyBarFixture(..., 6, ...), WithDimmer {
        override val mode = Mode.MODE_6CH
        // 6-channel properties...
    }

    @FixtureType("my-bar-14ch")
    class Mode14Ch(...) : MyBarFixture(..., 14, ...),
        WithDimmer, MultiElementFixture<Head>
    {
        override val mode = Mode.MODE_14CH
        // 14-channel properties + per-head control...
    }
}
```

### Per-Head Control with MultiElementFixture

For fixtures with multiple independently controllable elements (e.g., a bar with 4 heads), combine multi-mode with `MultiElementFixture`:

```kotlin
// Define head classes inside the sealed base
abstract inner class Head(
    override val elementIndex: Int,
    protected val headTransaction: ControllerTransaction?
) : FixtureElement<MyBarFixture> {
    override val parentFixture get() = this@MyBarFixture
    override val elementKey get() = "${key}.head-$elementIndex"
}

// Basic head for simpler modes
inner class BasicHead(
    elementIndex: Int,
    headTransaction: ControllerTransaction?,
    private val headFirstChannel: Int
) : Head(elementIndex, headTransaction), WithPosition {
    override val pan = DmxSlider(headTransaction, universe, headFirstChannel)
    override val tilt = DmxSlider(headTransaction, universe, headFirstChannel + 1)
}

// Full head for advanced modes
inner class FullHead(...) : Head(...), WithPosition {
    // Additional properties like fine control, speed, etc.
}
```

### Example: SlenderBeamBarQuadFixture

The Equinox Slender Beam Bar Quad demonstrates all these patterns:

| Mode | Channels | Traits | Description |
|------|----------|--------|-------------|
| `Mode1Ch` | 1 | - | Show presets only |
| `Mode6Ch` | 6 | Dimmer, Strobe | Basic global control |
| `Mode12Ch` | 12 | MultiElementFixture<BasicHead> | Per-head control, no global dimmer |
| `Mode14Ch` | 14 | Dimmer, Strobe, MultiElementFixture<BasicHead> | Global + per-head |
| `Mode27Ch` | 27 | Dimmer, Strobe, MultiElementFixture<FullHead> | Full control with fine channels |

**Usage:**
```kotlin
// Register a 14-channel mode fixture
val beamBar = SlenderBeamBarQuadFixture.Mode14Ch(
    universe, "beam-bar-1", "Beam Bar 1", 1, 1
)

// Global control
beamBar.dimmer.value = 255u
beamBar.strobe.fullOn()

// Per-head control
beamBar.head(0).pan.value = 128u
beamBar.head(0).colourWheel.setting = SlenderBeamBarQuadFixture.Colour.RED

// All heads same color
beamBar.setAllHeadsColour(SlenderBeamBarQuadFixture.Colour.BLUE)
```

## File Reference

| File | Purpose |
|------|---------|
| `Fixture.kt` | Sealed base class |
| `DmxFixture.kt` | DMX-specific base with addressing |
| `FixtureType.kt` | Class annotation |
| `FixtureProperty.kt` | Property annotation |
| `property/Slider.kt` | Single-value interface, AggregateSlider |
| `property/Colour.kt` | RGB colour interface, AggregateColour |
| `property/Position.kt` | Position interface |
| `property/Strobe.kt` | Strobe interface |
| `trait/WithDimmer.kt` | Dimmer trait |
| `trait/WithColour.kt` | Colour trait |
| `trait/WithStrobe.kt` | Strobe trait |
| `trait/WithUv.kt` | UV trait |
| `trait/WithPosition.kt` | Position trait |
| `dmx/DmxSlider.kt` | DMX slider implementation |
| `dmx/DmxColour.kt` | DMX RGB implementation |
| `dmx/DmxFixtureSetting.kt` | DMX enum mapping |
| `group/property/GroupSlider.kt` | AggregateSlider for groups |
| `group/property/GroupColour.kt` | AggregateColour for groups |
| `group/GroupExtensions.kt` | Extension properties for groups |
| `DmxChannelMode.kt` | Multi-mode channel configuration interface |
| `MultiModeFixtureFamily.kt` | Multi-mode fixture marker interface |
| `show/Fixtures.kt` | Registry and transaction wrapper |

## Existing Fixture Implementations

| Class | Type Key | Channels | Traits |
|-------|----------|----------|--------|
| `AdjFogFuryJettFixture.Mode7Ch` | adj-fog-fury-jett-7ch | 7 | Dimmer, Colour, Amber, Strobe (+ fog trigger slider) |
| `China2CellLedBlinderFixture` | china-2-cell-led-blinder-8ch | 8 | Dimmer, Strobe, MultiElementFixture (2 WW/CW cells + programs) |
| `EquinoxTwinShotMkIIFixture` | equinox-twin-shot-mkii | 3 | None — pyro-adjacent, plain trigger sliders only |
| `Gear4MusicOrbit70Fixture.Mode13Ch` | gear4music-orbit-70-13ch | 13 | Dimmer, Colour, White, Strobe, Position |
| `Gear4MusicSolParty12BFixture` | gear4music-sol-party-12b-8ch | 8 | Dimmer, Colour (+ colour-wheel macros, FX slider) |
| `GenericDimmerFixture` | generic-dimmer | 1 | Dimmer |
| `HexFixture` | hex | 12 | Dimmer, Colour, UV, Strobe |
| `ImgStageLineWash42LedFixture.Mode13Ch` | imgstageline-wash-42led-13ch | 13 | Dimmer, Colour, White, Strobe, Position |
| `KamLiteobar252Fixture` | kam-liteobar-252-11ch | 11 | Strobe, MultiElementFixture (3 RGB cells + macro modes) |
| `MartinMac250Fixture.Mode4Ch` | martin-mac-250-mode-4 | 13 | Dimmer, Position, Strobe (+ colour/gobo/prism wheels, lamp/reset methods) |
| `RobeColorSpot575Fixture.Mode2Ch` | robe-color-spot-575-mode-2 | 19 | Dimmer, Position, Strobe (+ dual colour wheels, static/rotating gobos, prism, frost, iris, zoom, focus, lamp/reset methods) |
| `WhexFixture` | whex | 12 | Dimmer, Colour (RGBW variant) |
| `QuadBarFixture` | quadbar | 1 | Settings only (show modes) |
| `LightstripFixture` | lightstrip | 5 | Colour, White (variable length) |
| `LightstripRgbFixture` | lightstrip-rgb | 3 | Colour (variable length) |
| `StarClusterFixture` | starcluster | 2 | Dimmer, Settings |
| `ScantasticFixture` | scantastic | 17 | Settings (scanner effects) |
| `ShehdsLed19RgbwFixture.Mode16Ch` | shehds-led19-rgbw-16ch | 16 | Dimmer, Colour, White, Strobe, Position |
| `ShehdsLed19RgbwFixture.Mode24Ch` | shehds-led19-rgbw-24ch | 24 | Dimmer, Strobe, Position, MultiElementFixture (3 RGBW zones) |
| `Source4RevolutionFixture.BaseFrame31Ch` | etc-source4-revolution-base-frame | 31 | Dimmer, Position, Zoom, Focus, Iris (+ gel scroller, front wheel, media frame, fan speed, framing shutters) |
| `UVFixture` | uv | 2 | Dimmer, Settings |
| `HazerFixture` | hazer | 2 | Sliders (haze, fan) |
| `FusionSpotFixture` | fusionspot | 14 | Dimmer, Colour, pan/tilt |
| `LaserworldCS100Fixture` | laserworld-cs-100 | 7 | Settings, pattern control |
| `SlenderBeamBarQuadFixture.Mode1Ch` | slender-beam-bar-quad-1ch | 1 | Settings (show presets) |
| `SlenderBeamBarQuadFixture.Mode6Ch` | slender-beam-bar-quad-6ch | 6 | Dimmer, Strobe |
| `SlenderBeamBarQuadFixture.Mode12Ch` | slender-beam-bar-quad-12ch | 12 | MultiElementFixture (4 heads) |
| `SlenderBeamBarQuadFixture.Mode14Ch` | slender-beam-bar-quad-14ch | 14 | Dimmer, Strobe, MultiElementFixture |
| `SlenderBeamBarQuadFixture.Mode27Ch` | slender-beam-bar-quad-27ch | 27 | Dimmer, Strobe, MultiElementFixture (full) |
| `VarytecEasymoveXl60SpotFixture.Mode11Ch` | varytec-easymove-xl-60-spot-11ch | 11 | Dimmer, Position, Strobe (+ colour/gobo wheels) |

## Infrastructure fixtures

Some patches are real DMX but not lighting: a dimmer channel switching a hazer's hard power, a
relay, a fan. `DaoFixturePatches.infrastructure` (boolean, default false) marks one. An
infrastructure fixture is **hidden from every operator surface except the Patches view** (where the
flag is set, as a checkbox in the edit form and the patch sheet's *Role* column) **and the Channels
view** (where its raw channels are driven). It is never offered as a target.

It is presentational, like `stageHidden`, and deliberately so:

- It still patches, outputs and takes part in anything that already names it — a group, a cue, a
  Look row, an effect, a surface binding. Hiding is about what the operator is *offered*, not what
  the desk *does*. Nothing sweeps references when the flag is set.
- It implies `stageHidden` on the Stage without writing it: the Stage never shows infrastructure
  under any flag, not even as the selected patch the picker keeps drawn.

Where the rule lives:

| Layer | What |
|---|---|
| `FixturePatchDto` / `CreatePatchRequest` / patch `PUT` | The field. The PUT applies it **without a rebuild** (`PUT_KEYS_WITHOUT_REBUILD`) — the loader builds nothing from it — and, when it flips, calls `Fixtures.announceFixturesChanged()` so every window refetches `GET /fixtures` and drops (or regains) the fixture, without tearing down every controller of a live rig for a view flag. It is **not** in `METADATA_ONLY_PUT_KEYS`, because that set is also the bulk placement route's allowlist, and that route announces nothing: the bulk route refuses it. |
| `Fixtures.FixturePatchMetadata.infrastructure` / `isInfrastructure` / `operatorFixtures` / `infrastructureKeys()` | The running show's copy, set by `DbFixtureLoader`. Every `setPatchMetadata` caller passes the flag through, or a metadata-only edit (a gel) would reset it. `operatorFixtures` is the register minus infrastructure under one read lock — what a server-side "every fixture" that *offers* reads. |
| `DmxFixtureDetails.infrastructure` (`GET /fixtures`) | What the frontend filters on. The list still **carries** the fixture, so a key a cue or group holds still resolves. |
| `BuskRigOrder` / `DeskSelection` | The empty rig's *steps* leave it out (`operatorFixtures`), mirrored by the client's `effectiveRig`, so *All* / *Next* / *Invert* never reach a power dimmer. A group that contains one still steps as the group; a tile placed before the flag was set stays. `positions()` still ranks it: ordering a head a caller already named (a spread) is not offering one. |
| `POST /templates/resolve` | Empty `targets` ("the whole patch", the template editor's *Resolves to* panel) is `operatorFixtures`; a target that names one still resolves it. |
| `RigBriefing` (`describe_rig`, the AI prompt), `get_current_state` | The briefing lists it under its own *Infrastructure* heading (and sets its keys apart in the script API); `get_current_state`'s fixture list marks it `infrastructure: true`. |
| MCP `patch_fixtures` / `get_patch` | Take and report `infrastructure`; a row that omits it leaves an existing fixture's flag alone. |
| Sync | `FixturePatchJson.infrastructure`, an optional field with a false default — no `formatVersion` bump. |

In lighting-react, views that list or offer fixtures read `useVisibleFixtureListQuery` /
`useVisiblePatchListQuery` (or apply `lib/infrastructure.ts`'s `withoutInfrastructure`); views that
only resolve a key they already hold read the raw lists.

## Head numbers

`DaoFixturePatches.headNumber` is the operator's number for a head — what ChamSys MagicQ calls a
head number, other desks a fixture or channel number. It exists because a show migrated from another
console is keyed on those numbers (the patch export lists them, the operator calls fixtures by them),
and a key like `lx1-spot-3` is not what anyone says on headset. It is optional and presentational: the
loader never reads it, so a write never rebuilds the rig.

- **Storage**: `head_number`, nullable integer, `MIN_HEAD_NUMBER` 1 to `MAX_HEAD_NUMBER` 99999
  (`models/fixturePatches.kt`). Null is unnumbered.
- **Unique within a project, at the write boundary — not by an index.** `POST /patches` and
  `PUT /patches/{id}` answer 409 naming the head that holds the number (`headNumberHolder` /
  `headNumberTaken` in `routes/projectPatches.kt`); a bad value (out of range, a fraction, a string)
  is a 400 from `parseHeadNumber`, never the 500 `nullableInt()` would throw. No unique index, for
  two reasons: a sync import of two peers' merged numbers must import rather than fail, and a
  renumber that swaps two heads has to pass through a state where both hold one number.
- **The bulk route renumbers atomically.** `headNumber` is in `METADATA_ONLY_PUT_KEYS`, so
  `PUT /patches/placements` accepts it, and `headNumberClashes` judges uniqueness against the rig **as
  it will stand** once the batch lands — every stored number, overridden by each entry not refused —
  iterated to a fixpoint, because refusing one entry leaves its head on its old number, which a
  sibling may have been moving onto. Every batch entry sharing a number is refused; a head outside
  the batch keeps its own. It is the one key on that route with a uniqueness rule, and it is why the
  patch list's *Set* over several heads is one request rather than the ordered PUT loop the Key
  column needs.
- **Sync**: `FixturePatchJson.headNumber` (formatVersion 16 — `docs/sync-engineering.md`
  §"Version 16 — head numbers"). Imported as stored; the patch list rings a shared number rather than
  anything refusing it.
- **MCP**: `patch_fixtures` takes `headNumber` per row (absent leaves it, `null` clears) and checks
  uniqueness against the patch as it will stand, as it does overlaps; `get_patch` reports it, and
  `describe_rig` lists it beside each key (`head 12, key=…`) — read from the patch, since the live
  registry carries none — so "head 12 at full" is something a model can act on.
- **Not on `GET /fixtures`**, deliberately, yet: nothing outside the patch views reads it. Carrying it
  there means adding it to `Fixtures.FixturePatchMetadata` (every `setPatchMetadata` caller passes it)
  and announcing `fixturesChanged` when it moves, as `infrastructure` does.
- **Frontend**: the patch list's *Head* column (Set over N numbers them consecutively from the typed
  one, in visible-row order, through the bulk route; Clear unnumbers), and a *Head number* field in
  the add and edit forms (`frontend/src/lib/headNumber.ts`).

## Stage geometry & coordinate system

Fixture patches carry physical-world geometry on `DaoFixturePatches` so the
frontend can render an FOH (Front-of-House) 3D view. Per-project stage
dimensions live on `DaoProjects` and bound the rendered scene. Both are
synced — a project travels with the rig layout it was designed for.

### Coordinate system (FOH-relative)

Right-handed, **Z-up**, units = **metres**. Origin = stage centre at deck
level. Z-up matches the convention used by most theatrical lighting CAD tools
(Capture, Wysiwyg) and by stage-design intuition ("the stage is the floor;
height goes up from it"):

| Axis | + direction                  | Notes                                       |
|------|------------------------------|---------------------------------------------|
| X    | audience-right               | "stage left" in actor terms                 |
| Y    | upstage                      | away from the audience (0 = downstage edge) |
| Z    | up                           | height above the deck (0 = deck level)      |

Negative values are valid: `stageX < 0` is audience-left, `stageY < 0` is
in front of the downstage edge (thrust, runway), `stageZ < 0` is below deck
(orchestra pit, traps).

Patch validation accepts any finite value in ±500 m on each axis — generous
enough for any real venue, tight enough to catch unit mistakes (mm, pixels).

`baseYawDeg` rotates about Z (up). `basePitchDeg` rotates about X. Numerically
unchanged from v2 — only the axis labels swapped. `baseRollDeg` (sync v17) is the third turn,
applied first: the body's `YXZ` Euler is `(pitch, yaw, roll)` in three.js space.

### Per-patch fields

Defined on `DaoFixturePatches` and surfaced through `FixturePatchDto`. Every
field is nullable; an unplaced fixture has `null` on all six.

| Column          | Meaning                                                         |
|-----------------|-----------------------------------------------------------------|
| `stage_x`       | X position in metres (FOH-relative — see table above).          |
| `stage_y`       | Y position in metres (depth into the stage).                    |
| `stage_z`       | Z position in metres (height above deck).                       |
| `base_yaw_deg`  | Body rotation around Z. 0° = pointing toward the audience (along −Y); +yaw rotates toward audience-right. Stored ±360°; renderers should reduce mod 360. |
| `base_pitch_deg`| Body rotation around X. 0° = horizontal; +pitch aims the fixture down. |
| `base_roll_deg` | Body rotation around its local Z (three.js), applied before pitch and yaw — it tips the body sideways in its own X–Y plane. For a moving head that lays it on its side; it is not a spin about the beam. ±180°; null is 0. |

Roll was left out until a fixture needed it, and a lightstrip did: its length runs along the body's
own X, which pitch turns *about* and yaw swings *round*, so with yaw and pitch alone a strip is
always level — a ring's upright sides were drawn lying flat at the right height. Roll 90 stands one
on end. It is stored on the patch and on each extra placement (`fixture_patch_placements`), so one
side of a run can stand while another lies along the deck; the placement mirror (`mirroredPlacement`)
negates it with the yaw, as a reflection across x = 0 does. The Stage view's rotate gizmo edits yaw
and pitch only and leaves a stored roll alone; the patch form's *Base orientation* takes all three.
The aim solve composes it too (`aimAt` / `beamDirection` below), so a head hung on its side aims
true.

#### Rigging-relative offsets

When `rigging_id` is set, `stage_x` / `stage_y` / `stage_z` are interpreted as
**offsets in the rigging's local frame** rather than absolute world
coordinates. World position is computed by composing the rigging's pose
(position + yaw/pitch/roll) with the patch offset:

```
world = rigging.position + R(yaw, pitch, roll) · (stage_x, stage_y, stage_z)
```

The REST DTO carries only the raw offsets — `stageX/Y/Z`, rigging-frame or
world depending on `riggingUuid` — and every reader composes the world position
itself, by one rule: `worldPosition` in `show/StageCoords.kt` on the desk (the
aim solve, `describe_rig`), and its mirror `worldPositionLighting` in the
frontend's `lib/stageCoords.ts` (the Stage view, Positions, the cue cards).

`base_yaw_deg` / `base_pitch_deg` / `base_roll_deg` are the fixture body's
orientation **in the world**, not in the rigging's frame: a rigging's pose
**places** a fixture but does not turn it. A fixture clamped square on a truss
yawed 20° is drawn square to the stage until its own `baseYawDeg` is set to 20
too — the rule §"Aiming a head at a point" states and `FixtureAim` solves by.

### Extra placements (paired dimmers)

A paired dimmer drives two lanterns from one DMX address — an SL and an SR unit on one bar, say.
That is **one fixture** to control (one address, one level, one row in every cue, group and FX
target) and **two objects** on the stage. So the patch is made once, keeps its own placement in the
columns above, and lists its other lanterns in `fixture_patch_placements`
(`models/fixturePatchPlacements.kt`): per entry a `rigging_id`, `stage_x/y/z`, `base_yaw_deg`,
`base_pitch_deg`, `base_roll_deg`, a short `label` (e.g. "SR") and a `sort_order`. The geometry follows the patch's
rules exactly, rigging-relative offsets included. A placement carries none of the fixture's own facts
(type, beam angle, gel, kind override, hidden), which a paired lantern shares — except its
**lantern and focus** (§"Lanterns and focus"): a pair can be two different lanterns, and each is
focused on its own.

Why placements and not two patches at one address: two patches would be two fixtures the desk can
set to different values while only one channel exists, the overlap check would have to be relaxed,
and every group, cue and the DMX sheet would count the pair twice. A placement cannot be driven
apart from its fixture, because it is not a fixture.

- **Presentational only**, like every stage field: `DbFixtureLoader` never reads the table, so
  `extraPlacements` is in `METADATA_ONLY_PUT_KEYS` and a write never rebuilds the rig.
- **REST**: `FixturePatchDto.extraPlacements` (always present, usually empty). `PUT
  /patches/{id}` and the bulk `PUT /patches/placements` take `extraPlacements` as the **whole
  list**: an entry whose `uuid` names one of this patch's placements edits it, any other entry is
  created with a fresh uuid, and a stored placement the list omits is deleted. Absent leaves the
  list alone; `null` or `[]` clears it. Every entry is validated (ranges as for the patch, label ≤40
  characters, at most 16 entries, no uuid twice) and its rigging resolved **before** anything is
  written, so a refusal writes nothing. The bulk route adds its past-the-end-of-the-truss warning
  for a placement as for the patch.
- **No cascade**: `PRAGMA foreign_keys` is off, so every patch delete calls `deletePlacementsOf`
  (the patch route, the universe delete, the project delete and the importer's replace path) and
  every rigging delete calls `detachPlacementsFromRigging` (the rigging route and `set_stage`'s
  `removeRiggings`), which leaves the offsets as they were — the patch's own treatment.
- **Sync**: embedded in the patch's document as `extraPlacements` (formatVersion 14 —
  `docs/sync-engineering.md` §"Version 14 — paired placements"); a placement's `baseRollDeg` is
  formatVersion 17 (§"Version 17 — fixture roll").
- **MCP**: `place_fixtures` and `patch_fixtures` take `alsoAt` (a whole list, matched to the
  stored placements by position), and `get_patch` reports it.
- **Frontend**: every stage surface draws each lantern lit from the fixture's channels, and
  clicking one selects the fixture; they are edited in the patch form's *Also hung at* section, not
  dragged on the plot (`frontend/docs/stage-vis-engineering.md`).

### Variable-length fixtures

Most types have a length that is a fact of the model: `@FixtureType(lengthM = …)`, or the
`FixtureKind` default, and a pixel bar is always the bar it is. A **lightstrip** is not — it is cut
to the run it is laid along, so its length is a fact of the install. Such a type sets
`@FixtureType(acceptsLength = true)` (`LightstripFixture` and `LightstripRgbFixture` today), which surfaces as
`FixtureTypeInfo.acceptsLength` / `FixtureTypeDetails.acceptsLength` on `GET /fixture-types`, and
its declared `lengthM` becomes only the **default** drawn until a patch sets its own.

- **Storage**: `fixture_patches.length_m` (metres along the body's long axis, its local +X) and
  `fixture_patch_placements.length_m` — nullable, `MIN_FIXTURE_LENGTH_M` 0.01 to
  `MAX_FIXTURE_LENGTH_M` 100 (`models/fixturePatches.kt`). Null on the patch is the type default;
  null on a placement is the patch's own length.
- **Runs in segments**: a run laid round several sides — the motivating case is a ring round the
  stage edge on one 5-channel controller — is **one patch**, because it is one colour on one
  address. Its own placement is one side; each other side is an **extra placement** carrying its
  own position, yaw and `lengthM`. That is the one fixture fact a placement may override (see the
  KDoc on `DaoFixturePatchPlacements`); everything else about a side — type, colour, groups — is
  the fixture's.
- **Refused for every other type**: `fixedLengthRefusal(typeKey)` in `routes/projectPatches.kt` is
  the one rule, called wherever a **non-null** length would be written — `POST /patches`, `PUT
  /patches/{id}` (its own `lengthM` or any `extraPlacements[].lengthM`; a 400, checked before the
  first write), the bulk `PUT /patches/placements`, and the MCP `patch_fixtures` /
  `place_fixtures`. Clearing one (`null`) is always allowed. The range check is
  `validateStageMetadata`'s `lengthM` argument.
- **Presentational only**: `DbFixtureLoader` never reads it, so `lengthM` is in
  `METADATA_ONLY_PUT_KEYS` and a write never rebuilds the rig.
- **Sync**: `FixturePatchJson.lengthM` and `PatchPlacementJson.lengthM` (formatVersion 15 —
  `docs/sync-engineering.md` §"Version 15 — variable-length fixtures"). The importer takes them as
  stored, with no type check; the stage views ignore a length on a fixed-length type.
- **MCP**: `list_fixture_types` marks such a type `acceptsLength` with its `defaultLengthM`;
  `patch_fixtures` / `place_fixtures` take `lengthM` on the row and on each `alsoAt` entry, and
  `get_patch` reports both.
- **Frontend**: the patch form offers *Length* only for such a type, and per side under *Other
  sides of this run*; the 3D body is drawn at the length, and editing on a Plan, Front or Side
  section takes a press anywhere between its two projected ends (`frontend/src/lib/fixtureLength.ts`,
  which mirrors `FixtureModel`: the long axis swings with `baseYawDeg` and tips up with
  `baseRollDeg`; pitch turns the body about it).

### Static fixtures vs. moving heads

For a static fixture (PAR, wash bar, fresnel), `baseYawDeg` + `basePitchDeg`
(+ `baseRollDeg`, where set) **is** the aim direction — the beam exits along the
body's local −Y axis after applying roll, pitch and yaw.

For a moving head, the base orientation describes only where the **yoke is
bolted**. The live aim is the base orientation composed with runtime
`pan` / `tilt` (already in fixture state and broadcast over the WebSocket
`channelState` updates). The frontend animates moving-head beams by reading
both: base from the patch, current pan/tilt from the live channel feed.

### Lanterns and focus

A conventional's body is not a fact of its type: a generic dimmer is whatever lantern is plugged
into it. So a type that says so (`@FixtureType(acceptsLantern = true)`) is hung with a lantern from
the desk's **lantern library**, and each unit carries how it was focused (stage-view plan session 7,
D9 and D14). A DMX fixture never takes either — its body is its type's `body`, and its zoom, focus,
iris and framing shutters are its channels, which its looks drive.

**The library** is a desk resource, `src/main/resources/lanterns/library.json`, shipped the way the
`.fx.kts` effects are and read once by `fixture/lantern/LanternLibrary.kt`; `GET /lanterns` answers
it whole. About 25 entries — Source Fours at each fixed angle and the two zooms, Silhouette,
Patt 23, Cantata, Prelude, SL, Acclaim, Cantata F, Rama, Patt 743, Quartet, the PCs, the four Par 64
lamps and a Par 16, a Coda flood, an Iris cyc and a house downlight. Each carries an id, a name and
maker, a **family** (`PROFILE · FRESNEL · PC · PAR · FLOOD · CYC · DOWNLIGHT`, whose kind the patch
takes — a PC is a `FRESNEL`, a flood and a cyc a `WASH`, a downlight `GENERIC`), the static
**archetype** it is drawn as, beam and field angles, a zoom range, a PAR lamp's **oval** (wide ×
narrow, the wide being the field), the lens diameter, the frame size, its dimensions, its
accessories (shutters *or* barn doors, an iris, a colour frame) and the kinds it is the **default
for**. The seed is from datasheets as remembered, with each entry's `source` naming the sheet; the
hall's own list and a verification pass are `FU-LANTERN-LIBRARY-HALL` in the follow-ups.

`parse` refuses a library that would draw wrong — an id not `[a-z0-9-]`, a field outside 1–180°, a
beam wider than its field, a zoom range not containing the field, an oval that is not a PAR's or
whose wide angle is not the field or that zooms, a mover archetype, dimensions outside 0–3 m,
shutters *and* barn doors, two defaults for one kind, a repeated id — so a bad edit fails the first
test run, not a render. **The defaults** answer a dimmer that names no lantern: `PROFILE` → Source
Four 19°, `FRESNEL` → Cantata F, `PAR` → Par 64 CP62, `WASH` → Coda 500, `GENERIC` → the downlight
(`LanternLibrary.effective(lanternType, kind)`; an id the library does not hold falls back the same
way, so an archive from a newer desk still draws).

**The focus** is seven nullable fields, on `fixture_patches` and on each `fixture_patch_placements`
row alike (`fixture/lantern/LanternFocus.kt`):

| Field               | Meaning                                                                    |
|---------------------|----------------------------------------------------------------------------|
| `lantern_type`      | A library id; null is the kind's default (the patch's lantern, on a placement). |
| `zoom_deg`          | The field angle a zoom lantern is set to, within its range; null is its own field. |
| `lamp_rotation_deg` | A PAR lamp's turn about the beam, ±180 — which way the oval lies.         |
| `shutters`          | Four blades, top · bottom · left · right, each `{depth, angleDeg}`: depth 0 out to 1 closed as a fraction of the field's diameter (0.5 reaches the centre), angle ±30° turning the blade about the middle of its edge. JSON text in the column. |
| `gate_rotation_deg` | The barrel's turn, ±180: the four blades turn together about the beam.   |
| `iris`              | 0 closed to 1 open; null is open.                                         |
| `focus_softness`    | The focus knob, 0 sharp to 1 soft; null is the family's own edge.         |

The blades are named for the edge of the light each cuts, seen from behind the lantern down the
beam: *top* cuts the top of the pool, *left* its left. They sit in the **head's frame**, so they turn
with the lantern's own roll and with its gate — a level lantern's top blade is up whichever way it is
yawed. `kind_override` is **derived from the lantern** at the write boundary: a patch naming a
lantern takes its family's kind, so the mover test (`effectiveKind`, which `RigBriefing` and the
Stage view both read, lantern first) and the Stage view's archetype agree about what the unit is,
and an explicit, different kind beside a lantern is refused (*kindOverride is derived from the
lantern*). `beam_angle_deg` stays what it was — an override that beats the lantern's field.

The write boundary is `routes/patchFocus.kt`'s `resolvePatchFocus`, which `POST` and `PUT /patches`,
the bulk `PUT /patches/placements` and the MCP tools all call: it overlays the keys a request sends
on what is stored (a key absent is kept, `null` clears), refuses a range problem, a type that takes
no lantern, an unknown id, or a zoom outside the lantern's range, and **drops a stored zoom when the
lantern changes and the request sent none** — a zoom that fitted the old lantern need not fit the new.
A placement is checked against its own lantern, else the patch's (`placementFocusRefusal`). All seven
are in `METADATA_ONLY_PUT_KEYS`: `DbFixtureLoader` never reads them, so a focus write never rebuilds
the rig. They round-trip through sync on `formatVersion` 19 (`docs/sync-engineering.md`
§"Version 19 — lanterns and focus"). The frontend reads the library through `useLanternIndex` and
draws the lantern, the cut and the oval — `frontend/docs/stage-vis-engineering.md` §"Fixture bodies".

### Fitted media

What is loaded into a unit — the gel string in its scroller, the gobos or dichroics in its module
wheel's slots, the gel in its media frame — is that **unit's**, not its type's (fixture optics plan
D1, D6): the manual settles the bands, the venue settles what is in the slots, and two units in one
rig can carry different strings. The type declares which settings are loadable and their stock
contents; each unit overrides them slot by slot.

**The vocabulary.** `@FixtureProperty(media = …)` on a setting: `GEL` (a slot takes a gel — a
scroller's frame, a media frame's wing), `GOBO` (a pattern) or `GOBO_OR_GEL` (either — a module
wheel holds a gobo or a dichroic). Each option's **stock** content is what it already declares: its
`colourPreview` (`DmxFixtureColourSettingValue`) or its `gobo` (`DmxFixtureGoboSettingValue`). An
option whose `loadable` is false — an open hole, an out position — takes nothing
(`DmxFixtureSettingValue.loadable`, true by default). The Source Four Revolution is the one loadable
type: its gel scroller is `GEL` (every frame, the open leader and trailer included — they are frames
of the string), its front wheel position `GOBO_OR_GEL` (OPEN takes nothing; the three slots ship
empty) and its media frame `GEL` (IN takes the wing's gel, OUT nothing; the wings ship empty).

**The rules**, which `MediaSlotsTest` holds every library type to:

1. `media` only on a **setting-backed** property, and only on the fixture itself — never a slider,
   never a cell, since a cell is no unit.
2. `GEL` and `GOBO_OR_GEL` only where the options can carry a colour (the option type implements
   `DmxFixtureColourSettingValue`); `GOBO` and `GOBO_OR_GEL` only where they can carry a pattern
   (`DmxFixtureGoboSettingValue`). A stock with nowhere to live has nothing to be replaced.
3. At least one option is a slot.
4. **Nothing else declares it**: the test lists the library's loadable settings by name, so a new
   one is a recorded decision rather than an annotation nobody noticed.

**The JSON shape** — one nullable `media` text column on `fixture_patches` **and** on
`fixture_patch_placements`, canonical JSON with keys sorted (`fixture/media/FittedMedia.kt`):

```json
{"slots": {"gelScroller": {"L201_FULL_CT_BLUE": {"gel": "R26"}, "OPEN_LEADER": {}},
           "fbWheelPos": {"SLOT_1": {"gobo": "breakup"}}}}
```

keyed by the setting's property name, then the option's name, naming only the options that differ
from the stock. A slot holds `{gel: code}` (a code from the desk's gel library, `GET /gels`),
`{gobo: pattern}` (one of the 16 `GoboPattern` names, lowercase) or `{}` — **an empty slot**, fitted
with nothing, so the frame is open; that is not the same as no entry, which leaves the stock. Null,
or nothing fitted, is the stock everywhere. Custom gobo artwork is `FU-GOBO-CUSTOM-IMAGES`.

**The write boundary** is `routes/patchMedia.kt`'s `patchMediaRefusal`, which `POST` and
`PUT /patches`, the bulk `PUT /patches/placements` and the MCP `patch_fixtures` / `place_fixtures`
all call, following `resolvePatchFocus`: on a patch a `media` key absent is **kept** and `null`
**clears**; within an `extraPlacements` entry it is plain, as every placement field is (the entry is
a whole placement), so a client that re-sends the list carries each placement's media. It refuses,
**every problem at once** and each by its path: a type with no loadable settings, an unknown
property, one that is not loadable, an unknown option, an option that is no slot, an unknown gel
code or gobo pattern, a gel where the slot takes none, a gobo where it takes none, and a slot holding
both. `FittedMedia.parse` refuses the shape first (a wrong JSON type is a problem named, never a 500).
An MCP re-patch to another type drops stored media the new type cannot hold. Import is **not**
checked (`docs/sync-engineering.md` §"Version 22 — fitted media"), so a gel a newer desk's library
holds survives a round trip; such a slot draws as its stock.

`media` is in **`METADATA_ONLY_PUT_KEYS`**: `DbFixtureLoader` never builds a fixture from it, so a
media write never rebuilds the rig — but the runtime patch metadata (`Fixtures.FixturePatchMetadata`)
carries the patch's media for the template resolver, and the metadata-only path refreshes it there
as it refreshes `gelCode`. That is also what lets the Stage view's Focus tab and the bulk route
write it. **It recomposes nothing**: a live cue, or a programmer layer, whose colour template snapped
a scroller or wheel frame keeps that frame until it is next composed (its next GO, or a recook), as
with every metadata edit — a loaded-media change is a between-shows job, and a scroller moving under
a live cue unasked is a visible jump. Re-snapping live consumers on a media write is
`FU-MEDIA-LIVE-RESNAP`.

**The resolution order** is *the placement's slot, else the patch's, else the type's stock*,
option by option (`FittedMedia.over`). A fitted gel resolves to its library colour, a fitted gobo to
its pattern and no colour, an empty slot to open white and no pattern
(`FittedMedia.colourOf` / `goboOf`). Where it happens:

- **The client overlays it, per unit**, on the type's descriptor (`frontend/src/lib/fittedMedia.ts`'s
  `fittedProperties`). A descriptor is per fixture, and a fixture with extra placements is several
  units — two scrollers on one address, each its own string — so only the surface drawing a
  placement knows which unit it is drawing; a backend-resolved descriptor could answer for the
  patch alone. The Stage view, the 2D appearance and the patch sheet all overlay the same way
  (`frontend/docs/stage-vis-engineering.md` §"Fitted media").
- **The desk resolves it for what it answers alone**: `TemplateResolver.nearestColourSlot` snaps a
  colour against the unit's fitted colours (the patch's own: a placement's DMX is the patch's, so a
  snap can answer for one unit, and the note names the fitted gel, *L201_FULL_CT_BLUE (R26)*);
  Locate's white frame is the one open on this unit; `describe_rig` lists each unit's fitted media.
  The resolver takes the media as a **required** argument (`Fixtures.fittedMediaFor`), so no caller
  can snap against the stock string by forgetting it.

**The gel library** is `src/main/resources/gels.json`, read once by `fixture/media/GelLibrary.kt`
and served whole at `GET /gels` (fixture optics plan D7); it moved from the frontend so the desk can
turn a fitted gel into a colour. `parse` refuses a bad code, a bad hex, a duplicate code or an unknown
brand, every problem at once. Eight gels of the Revolution's stock string were added with the
string's swatches and are marked `estimate` (D15). A patch's `gelCode` is not checked against it: a
gel the plot names and the library lacks is still the plot's.

### Per-project fields (stage dimensions)

`DaoProjects` carries the stage bounding box so the renderer has scene
extents to draw the deck and walls. All three are nullable metres; if absent,
the renderer should fall back to a default stage size.

| Column           | Meaning                                                       |
|------------------|---------------------------------------------------------------|
| `stage_width_m`  | Total width along X (audience-left to audience-right).        |
| `stage_depth_m`  | Total depth along Y (downstage to upstage).                   |
| `stage_height_m` | Trim height along Z (typical fixture hang above the deck).    |

These are venue-shaped, not fixture-shaped: don't try to derive them from
patch positions, and don't gate rendering on them being set.

The bounding box is a **coarse** description of the venue. For irregular
floor plans (thrusts, raised platforms, multi-level stages), see the
`stage_regions` table below.

### Riggings (`DaoRiggings`)

A first-class entity for trusses, bars, booms, pipes, and floor stands. Each
rigging carries a 3D pose and groups the fixtures hung off it. Fixture
patches reference a rigging via the optional `rigging_id` FK; when set, the
patch's `stage_x` / `stage_y` / `stage_z` are interpreted in the rigging's
local frame.

| Column        | Meaning                                                                     |
|---------------|-----------------------------------------------------------------------------|
| `name`        | Operator-facing label, unique per project (e.g. `"FOH"`, `"LX1"`, `"Boom-SL"`). |
| `kind`        | Optional advisory label (`TRUSS`, `BAR`, `BOOM`, `PIPE`, `FLOOR_STAND`, `LEDGE`, `OTHER`). Renderers may use this to pick a default mesh; not enforced on REST, checked by `set_stage`. `FLOOR_STAND` and `LEDGE` are **stood on** rather than hung from — a ledge is a balcony front or a shelf the units sit on (stage-view plan session 2). Since session 6 the Stage view draws a body on one base down with no hanger, and a hung one with a hanger up to its bar; the list is `STANDING_RIGGING_KINDS` (`ai/SetupToolSchemas.kt`) — see §"Aiming a head at a point" for why it never turns an aim. |
| `position_x/y/z` | Origin in world coordinates (metres). Nullable; null = treat as 0.       |
| `yaw_deg`     | Rotation about Z (up). Stored ±360°.                                        |
| `pitch_deg`   | Rotation about X (audience-right). Stored ±180°.                            |
| `roll_deg`    | Rotation about Y (upstage). Stored ±180°.                                   |
| `sort_order`  | Display order in operator UI.                                               |

Composition order is yaw → pitch → roll (intrinsic Tait-Bryan). For a typical
flown truss with only `yawDeg` set, this collapses to a single 2D rotation
in the X/Y plane. CRUD: `GET`/`POST`/`PUT`/`DELETE` under
`/api/rest/projects/{projectId}/riggings`. Deleting a rigging detaches any
patches that reference it (`rigging_id` is set to NULL; the fixtures are not
deleted).

### Stage regions (`DaoStageRegions`)

Rectangular platforms describing the actual playable surface. Multiple
regions handle thrusts, raised platforms, pits, and multi-level stages.

| Column        | Meaning                                                                     |
|---------------|-----------------------------------------------------------------------------|
| `name`        | Operator-facing label, unique per project.                                  |
| `center_x/y`  | Centre of the rectangle in the deck plane (metres).                         |
| `center_z`    | Z (height) of the platform's top surface. 0 = deck level; >0 raised.        |
| `width_m`     | Extent along X (after rotation).                                            |
| `depth_m`     | Extent along Y (after rotation).                                            |
| `height_m`    | Vertical extent — total platform thickness, including the bit below `center_z`. |
| `yaw_deg`     | Rotation about Z relative to the rectangle's centre.                        |
| `sort_order`  | Display / draw order.                                                       |

Regions are additive — the union of all regions is the playable surface. A
plain rectangular stage is a single region whose `width_m`/`depth_m` match
the project's bounding box. CRUD endpoints mirror riggings under
`/api/rest/projects/{projectId}/stage-regions`.

### The scene document (`DaoStageElements`, `DaoStageViewpoints`)

The venue and the set, as named elements the Stage view draws (stage-view plan session 2, D2): the
room, the proscenium, masking, platforms, seating, furniture. Nothing composes them and nothing
here reaches DMX — they are what the light lands on. Regions stay separate (D5): a region is the
playing surface, an aim target and a beam receiver, and a platform may **link** to one rather than
duplicate it. The design record is `docs/plans/stage-view-design/INDEX.md` §"2. A scene document".

| Column | Meaning |
|---|---|
| `name` | Unique per project: `Hall`, `Proscenium`, `Stalls`, `SR wall`. |
| `kind` | `ROOM`, `PROSCENIUM`, `FLAT`, `DRAPE`, `PLATFORM`, `SEATING`, `OBJECT` — the RP-2 minimum. |
| `layer` | `VENUE` outlives a production; `SET` is this show's. |
| `position_x/y/z` | The origin (metres, the frame above): the centre of the footprint; for `SEATING`, the centre of the first row. **Z is the base**, except a **`PLATFORM`'s, which is its top surface** with the deck hanging below — as a region's `center_z` is. A hall floor under a raised stage is negative. |
| `yaw_deg` | Rotation about Z at the origin, anticlockwise from above — the desk's yaw everywhere. |
| `width_m` / `depth_m` / `height_m` | Size along the element's own axes; all three > 0 for every kind but `SEATING`, whose size comes from its rows and seats (all three 0). |
| `finish_colour` / `finish_pattern` / `emissive` | `#rrggbb`, `PLAIN · PANELS · TILES · BOARDS`, and whether it glows (an exit sign). |
| `params` | What only one kind means — a sealed `ElementParams` per kind, canonical JSON. |
| `hidden`, `sort_order` | Stored but not drawn; display order. |

**`params` is validated per kind at the write boundary** (`models/stageScene.kt`'s
`parseElementParams`, used by REST and `set_scene` alike through `validateStageElement`): every
problem at once, unknown keys refused, enumerations read case-insensitively and stored upper-case.

| Kind | Params |
|---|---|
| `ROOM` | `omit` (sides not drawn: `DOWNSTAGE · UPSTAGE · STAGE_LEFT · STAGE_RIGHT · FLOOR · CEILING`), `floor` and `ceiling` finishes. |
| `PROSCENIUM` | `openingWidthM`, `openingHeightM` (required), `openingSillM`, `surroundM` — the opening must fit the wall. |
| `FLAT` | `openings[]` of `{kind: DOOR · WINDOW · FRENCH_WINDOW · ARCH, fromM, widthM, heightM, sillM}`, `fromM` from the stage-right end — each must fit the flat. |
| `DRAPE` | `role` (`LEG · BORDER · TABS · CYC · BACKCLOTH`, required), `operation` (`DEAD · DRAW · FLY`). |
| `PLATFORM` | `railHeightM` and `railEdge` together, `regionUuid` (a region of this project). |
| `SEATING` | `rows` (1–26), `seatsPerRow`, `rowPitchM`, `seatPitchM`, `firstRow` (a letter), `rakeM` (rise per row). |
| `OBJECT` | `shape` (`BOX · CYLINDER · SHADE · DISC`), `flies`. |

Every kind may carry `states`, the base values session 8's scenery tracks from: `visible` on any,
`open` (0–1) on a `DRAW` drape, `trimM` (replacing Z while set) on a flown piece.

**Seats** are derived, never stored: row `firstRow` is nearest the stage at the origin, each later row
a `rowPitchM` further from the stage (local −Y) and `rakeM` higher, seat 1 at the stage-right end,
the block turned by the element's yaw. A seated eye is 1.15 m above the seat, 5 cm towards its back.
`SeatingParams.seat` in Kotlin and `lib/stageSeats.ts` in the frontend are the same maths, and
`StageSceneTest` and `stageSeats.test.ts` pin the same seats.

**Viewpoints** (D6) are saved places to look from, portable because "Row F" is the venue's:

| Kind | Carries |
|---|---|
| `ORBIT` | `eye_*` and `target_*`: the orbit camera placed at the eye, circling the target. No lens — the orbit camera's is fixed. |
| `EYE` | `eye_*`, `target_*` and `fov_deg` (15–90, default 50): a person standing there. |
| `SEAT` | `seat_element_uuid` and `seat_id` (`F6`), optionally `target_*` and `fov_deg` (default 52): sitting in a seat. No eye — it is the seat's, so a moved seating moves the view. With no target it looks at the stage's centre line, 2.4 m upstage and 0.9 m up. |

Plan, Front and Side are built in and never rows. The seat reference is a uuid, not an FK: deleting
or reshaping a seating that a seat view still sits in is **409 `STAGE_ELEMENT_IN_USE`**, naming the
views, and `?force=true` goes ahead and leaves them dangling (`docs/api-conventions.md`
§"Guard overrides"); a reader treats a dangling one as no seat. A `PUT` on such a view — or a
`set_scene` row naming it — still succeeds while it leaves the seat as it was — a rename, a new
target or lens — because the merged row's seat is the stored one, unchanged
(`validateStageViewpoint`'s `storedSeat`, as `validateStageElement` tolerates a platform's unchanged
region); naming a different seat, or the same seating with another seat id, is checked as a new
one. `set_scene` passes the stored seat only when it already dangled before the call, so a call
that removes or reshapes the seating cannot keep a view in it by restating the view.

CRUD: `GET`/`POST` `/api/rest/projects/{projectId}/stage-elements` and `/stage-viewpoints`, and
`GET`/`PUT`/`DELETE` on `…/{id}`. Stored data only, so ungated by the current project, like regions.
A `PUT` is partial and the merged element is checked whole. Each write fires
`stageElementListChanged` or `stageViewpointListChanged`. The MCP surface is `set_scene` and
`get_scene` (`docs/mcp-engineering.md` §"Show-setup tools"); sync is v18
(`docs/sync-engineering.md`).

On the desk (stage-view plan session 5) the Stage view's Edit mode places an element with
`+ Scenery` and edits it in its element form, both over these routes: a 400 lists every problem
`validateStageElement` found, joined with `"; "`, each leading with the field it is about as the
REST body spells it (`widthM`, `params.openings[1]`, `params.states.open`), and the form draws each
beside that field. Keep that spelling if a message is reworded — it is how a refusal finds its
field (`frontend/src/components/stage/elementProblems.ts`).

### Aiming a head at a point

`POST /api/rest/projects/{projectId}/programmer/aim` `{targets, x, y, z, fadeMs?}` points moving
heads at a stage coordinate (metres, the frame above), and the `aim_fixtures` tool (chat and MCP,
with a `dryRun`) is the same call. `show/FixtureAim.kt`'s `aimAt` solves each head's pan and tilt;
`routes/programmerAim.kt` reads the placements, converts to DMX and writes the programmer as owner
`WEB`, like a spread — so Record captures an aim, Blind previews it and Clear releases it. The
Stage view's docked fixture panel (and its multi-select aim panel, in view mode) is the UI.

The solve is the **inverse of the Stage view's drawing**, not of a model of real yokes, so a head
the desk aims is drawn with its beam through the point. The view places the body at the patch's
world position (composed through its rigging by `worldPosition`) and rotates it by `baseYawDeg` /
`basePitchDeg` / `baseRollDeg` only — **a rigging's pose moves a fixture but does not turn it** in the view, so the
solve does not turn it either. At DMX mid-travel a head's beam runs up the body's own axis, so a
hung mover is `basePitchDeg = 180` and a floor-standing one 0; pan turns about that axis and tilt
leans away from it. `beamDirection` is the forward half, pinned against the view's `panTiltToDir`
vectors in `FixtureAimTest`.

**Standing mounts** (stage-view plan session 6). A rigging of kind `LEDGE` or `FLOOR_STAND` is stood
on — `STANDING_RIGGING_KINDS` — and the Stage view draws its units base down with no hanger. That is
how a body is *carried*, and nothing more: a moving head's mount is its own `basePitchDeg`, 0
standing and 180 hung, which is what the view draws and what this solve reads, so the rigging's kind
never turns an aim. A moving head stood on a ledge at 180 is therefore drawn and aimed upside down
under it — the Commemoration Hall's balcony Revolutions until the plan's P5 sets them to 0 — and
`describe_rig` names every head whose `basePitchDeg` disagrees with its rigging's kind, one way
(180 on a ledge: hung under it) or the other (0 on a bar: standing on top of it), on a *Mounts:*
line in its stage summary. Which way a body faces is read as the view reads it — its own axis
through yaw, pitch and roll (`beamDirection` at pan and tilt 0), up or down past 60° — so a head
rolled over hangs, and a head on its side is neither and is not flagged. A static lantern's
`basePitchDeg` is its focus, not its mount, so it is never flagged.

- **Travel degrees come from the annotation.** A head aims only where both its pan and tilt carry
  `@FixtureProperty(degMin =, degMax =)`; the mechanical centre is the middle of the range, as the
  view's `axisCentreDeg` reads it. Pan repeats every 360°, and tilting either way off the axis
  reaches the same direction with pan half a turn apart, so every candidate inside the travel is
  tried and the one nearest the centre of both wins — never the long way round, and the same answer
  for the same rig each time. Pan turns about the body axis, so it never changes how far off that
  axis a direction is: a 0–270° tilt misses only the 45° cone behind the base, a 0–180° one the whole
  hemisphere behind it.
- **Fine channels are written.** A head with `PAN_FINE` / `TILT_FINE` sliders gets 16 bits
  (`coarse + fine / 256`, as the view combines them); one step of an 8-bit 540° pan is about 2°,
  some 37 cm at a 10 m throw. The degree→DMX mapping is `TemplateResolver`'s (range, inversion,
  the coarse slider's own `min..max`).
- **Everything else is skipped by name**, never guessed at: a fixed head, an axis with no degree
  range, an unplaced fixture, a cell (it has no placement of its own), a point at the fixture or
  outside its travel (the reason says how far out).
- **It aims from the placement point.** The drawn head pivots a few centimetres along the body axis
  from it, so the drawn beam passes that close to the point; a real head is as close as its
  placement and mount were measured.
- **An aim can be kept as a position template.** The programmer is scratch — Clear releases an aim
  like any other entry — so `aim_fixtures` takes `saveAsTemplate`: a new template with one
  fixture-specific `position` row per aimed head, in travel degrees (`deg:pan,tilt`, the template
  grammar), which `TemplateResolver` turns back into each head's aim to within that tenth of a degree
  (the grammar's precision; the programmer write is exact, fine channels included). It is a focus
  palette cues, Looks and busk pads can reference, and it outlives the programmer. The template is
  created before the programmer is written, so a taken name refuses the call with no head moved;
  `dryRun` creates nothing.

### Focusing a head on a point

`POST /api/rest/projects/{projectId}/programmer/focus` `{targets, point: {x, y, z}, fadeMs?, write?}`
is *Focus here* (fixture-optics plan D11): it focuses each head's FOCUS channel on a stage coordinate
and writes the level into the programmer as owner `WEB`, as an aim does. It answers
`{written: [{target, value, distanceM}], skipped: [{target, reason}]}` — `value` the focus slider's
DMX literal, `distanceM` the distance it was solved for, to the centimetre. `write: false` (default
true) answers without writing, as Spread's does. The Stage view's Focus tab sends the point its beam
lands on (`frontend/docs/stage-vis-engineering.md` §"Focus"), and `aim_fixtures`' `focus: true`
sends its aim point through the same `focusIntoProgrammer`, for the heads aim aimed only — a head
aim skipped is not pointing there, and is listed as not focused.

- **The solve is the view's mapping backwards.** `show/FixtureFocus.kt`'s `FocusRange` is the
  declared range over the slider's own `min..max`: `distanceAt` is the Stage view's
  `resolveDeclaredFocusDistance` (linear in 1 / distance, `inverted` putting the far end at DMX min)
  and `levelFor` its inverse, rounded to the slider's step by `dmxFor`. One test vector,
  `src/test/resources/stage/focusInverse.fixture.json`, pins both sides — `FixtureFocusTest` here and
  `beamOptics.test.ts` in the view — so a head the desk focuses at a distance is the head the view
  draws sharp at it. Change the rule, the vector and both pins in one commit.
- **The distance is from the lens, as the view draws it.** The head's placement composed through its
  rigging (`worldPosition`, `routes/programmerAim.kt`'s `aimPlacements`, aim's own maths), then up the
  body's axis to the head's pivot and along the beam to its lens: `MoverLens` and `lensDistance` in
  `show/FixtureFocus.kt`, the Stage view's mover proportions (the pivot 0.6 of the unit's height up
  the body, the lens half the head's length beyond it — 0.51 m and 0.22 m on a Revolution). The
  point is on the beam's axis — *Focus here* sends where the axis lands, and an aimed head points
  at its aim — so the distance is the pivot's less the lens's offset. That 0.22 m is under 1 % of a
  24 m throw but 7 % of a 3 m one, about a DMX step's softness, which is why it is measured. The
  vector's `heads` pins the proportions on both sides (`FixtureFocusTest`, `archetype.test.ts`), and
  `FixtureFocusTest` holds every focus type to a declared mover body and head, so the desk never
  has to guess the view's words; a type without one would be measured from its placement point.
- **Everything else is skipped by name**: a head with no focus channel, a focus that declares no
  range, an unplaced fixture, a cell (it has no placement of its own), a point outside the head's
  range (the reason gives the distance and the range). An empty selection is 400
  `FOCUS_NEEDS_SELECTION`; a missing point or one off any stage (`checkStageCoord`'s ±500 m) is 400
  `FOCUS_INVALID` — aim's two refusals, the selection checked first.
- **A focus is not saved as a template by aim.** The template grammar holds focus (`pct:`), but a
  template is one family, and focus is beam where an aim is position, so `aim_fixtures`'
  `saveAsTemplate` stays position-only; `record_cue` keeps a focus.

