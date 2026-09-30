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
annotation class FixtureType(val typeKey: String)
```

Marks a fixture class with a unique type identifier. Used for:
- REST API fixture type filtering
- Serialization/deserialization
- UI grouping

### @FixtureProperty

```kotlin
@Target(AnnotationTarget.PROPERTY)
annotation class FixtureProperty(val description: String = "")
```

Marks a property as controllable. The `fixtureProperties` list on `Fixture` collects these via reflection for:
- Channel description generation
- REST API property enumeration
- FX engine targeting

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
beamBar.head(0).colour.setting = SlenderBeamBarQuadFixture.Colour.RED

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
| `Source4RevolutionFixture.BaseFrame31Ch` | etc-source4-revolution-base-frame | 31 | Dimmer, Position (+ gel scroller, beam wheels, framing shutters) |
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
(type, beam angle, gel, kind override, hidden), which a paired lantern shares.

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
