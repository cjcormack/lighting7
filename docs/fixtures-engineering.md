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
│   │  Implements traits: FixtureWithDimmer, FixtureWithColour, etc. │    │
│   └────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         Property Types                                  │
│                                                                         │
│   ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────┐     │
│   │ DmxFixtureSlider│  │ DmxFixtureColour│  │ DmxFixtureSetting   │     │
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
    val fixtureName: String,   // Display name (e.g., "Front Wash Left")
    val position: Int          // Ordering for UI display
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
    fixtureName: String,
    position: Int
)
```

Adds DMX-specific addressing. The `channelDescriptions()` method returns a map of channel numbers to human-readable names for debugging and UI.

## Trait Interfaces

Fixtures compose capabilities through trait interfaces:

| Trait | Property | Purpose |
|-------|----------|---------|
| `FixtureWithDimmer` | `dimmer: FixtureSlider` | Master brightness control |
| `FixtureWithColour<T>` | `rgbColour: T` | RGB color mixing |
| `FixtureWithStrobe` | `strobe: FixtureStrobe` | Strobe effect control |
| `FixtureWithUv` | `uvColour: FixtureSlider` | UV channel control |

### FixtureSlider

```kotlin
interface FixtureSlider {
    var value: UByte                           // Immediate set (0-255)
    fun fadeToValue(value: UByte, fadeMs: Long) // Timed fade
}
```

### FixtureColour

```kotlin
abstract class FixtureColour<T: FixtureSlider>(
    val redSlider: T,
    val greenSlider: T,
    val blueSlider: T
) {
    var value: Color                              // Get/set as java.awt.Color
    fun fadeToColour(rgbColor: Color, fadeMs: Long)
}
```

### FixtureStrobe

```kotlin
interface FixtureStrobe {
    fun fullOn()                    // Disable strobe, full output
    fun strobe(intensity: UByte)    // Enable strobe at speed
}
```

## DMX Property Implementations

### DmxFixtureSlider

Maps a single DMX channel to a slider:

```kotlin
class DmxFixtureSlider(
    val transaction: ControllerTransaction?,
    val universe: Universe,
    val channelNo: Int,
    val min: UByte = 0u,      // Clamp minimum
    val max: UByte = 255u     // Clamp maximum
)
```

- Reads/writes through the transaction (not direct to controller)
- Automatically clamps values to min/max range
- Throws if used without a transaction

### DmxFixtureColour

Groups three channels as RGB:

```kotlin
class DmxFixtureColour(
    transaction: ControllerTransaction?,
    universe: Universe,
    redChannelNo: Int,
    greenChannelNo: Int,
    blueChannelNo: Int
)
```

Creates three `DmxFixtureSlider` instances internally.

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
- Scene recording

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
- Active scene tracking
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

### Scene Tracking

The registry tracks which scenes are currently "active" (their channel values match the current output). When channel values change, scenes are automatically marked inactive.

## Adding a New DMX Fixture

### Step 1: Create the Class

```kotlin
@FixtureType("my-fixture")
class MyFixture(
    universe: Universe,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, CHANNEL_COUNT, key, fixtureName, position),
    FixtureWithDimmer,           // If it has a dimmer
    DmxFixtureWithColour         // If it has RGB
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
        fixture.position,
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
    override val dimmer = DmxFixtureSlider(transaction, universe, firstChannel)

    // Channels 2-4: RGB
    @FixtureProperty
    override val rgbColour = DmxFixtureColour(
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

    addFixture(MyFixture(Universe(0, 0), "my-1", "My Fixture 1", 1, 1))
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
    position: Int,
    protected val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, channelCount, key, fixtureName, position),
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
    class Mode6Ch(...) : MyBarFixture(..., 6, ...), FixtureWithDimmer {
        override val mode = Mode.MODE_6CH
        // 6-channel properties...
    }

    @FixtureType("my-bar-14ch")
    class Mode14Ch(...) : MyBarFixture(..., 14, ...),
        FixtureWithDimmer, MultiElementFixture<Head>
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
) : Head(elementIndex, headTransaction), FixtureWithPosition {
    override val pan = DmxFixtureSlider(headTransaction, universe, headFirstChannel)
    override val tilt = DmxFixtureSlider(headTransaction, universe, headFirstChannel + 1)
}

// Full head for advanced modes
inner class FullHead(...) : Head(...), FixtureWithPosition {
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
| `FixtureSlider.kt` | Single-value interface |
| `FixtureColour.kt` | RGB grouping base class |
| `FixtureMultiSlider.kt` | Named slider collection interface |
| `FixtureWithDimmer.kt` | Dimmer trait |
| `FixtureWithColour.kt` | Colour trait |
| `FixtureWithStrobe.kt` | Strobe trait |
| `FixtureWithUv.kt` | UV trait |
| `dmx/DmxFixtureSlider.kt` | DMX slider implementation |
| `dmx/DmxFixtureColour.kt` | DMX RGB implementation |
| `dmx/DmxFixtureSetting.kt` | DMX enum mapping |
| `dmx/DmxFixtureMultiSlider.kt` | DMX multi-slider interface |
| `DmxChannelMode.kt` | Multi-mode channel configuration interface |
| `MultiModeFixtureFamily.kt` | Multi-mode fixture marker interface |
| `show/Fixtures.kt` | Registry and transaction wrapper |

## Existing Fixture Implementations

| Class | Type Key | Channels | Traits |
|-------|----------|----------|--------|
| `HexFixture` | hex | 12 | Dimmer, Colour, UV, Strobe |
| `WhexFixture` | whex | 12 | Dimmer, Colour (RGBW variant) |
| `QuadBarFixture` | quadbar | 1 | Settings only (show modes) |
| `LightstripFixture` | lightstrip | 3 | Colour |
| `StarClusterFixture` | starcluster | 2 | Dimmer, Settings |
| `ScantasticFixture` | scantastic | 17 | Settings (scanner effects) |
| `UVFixture` | uv | 2 | Dimmer, Settings |
| `HazerFixture` | hazer | 2 | Sliders (haze, fan) |
| `FusionSpotFixture` | fusionspot | 14 | Dimmer, Colour, pan/tilt |
| `LaserworldCS100Fixture` | laserworld-cs-100 | 7 | Settings, pattern control |
| `SlenderBeamBarQuadFixture.Mode1Ch` | slender-beam-bar-quad-1ch | 1 | Settings (show presets) |
| `SlenderBeamBarQuadFixture.Mode6Ch` | slender-beam-bar-quad-6ch | 6 | Dimmer, Strobe |
| `SlenderBeamBarQuadFixture.Mode12Ch` | slender-beam-bar-quad-12ch | 12 | MultiElementFixture (4 heads) |
| `SlenderBeamBarQuadFixture.Mode14Ch` | slender-beam-bar-quad-14ch | 14 | Dimmer, Strobe, MultiElementFixture |
| `SlenderBeamBarQuadFixture.Mode27Ch` | slender-beam-bar-quad-27ch | 27 | Dimmer, Strobe, MultiElementFixture (full) |
