# Fixture Groups Engineering Documentation

This document describes the type-safe fixture group system for treating multiple fixtures as a single unit.

## Overview

The fixture group system provides:
- **Type-safe groups**: Compile-time enforcement of fixture capabilities
- **FxTargetable interface**: Groups implement the same targeting interface as fixtures
- **Group-level FX targeting**: A single FxInstance targets the entire group; engine expands at processing time
- **Position-aware members**: Each fixture has a position within the group for effect distribution
- **Distribution strategies**: Various patterns for distributing effect phases across group members
- **Multi-element support**: Fixtures with multiple controllable elements (e.g., quad moving head bars)

## Architecture

### Core Components

```
fixture/group/
├── GroupMember.kt           # Member wrapper with metadata
├── FixtureGroup.kt          # Type-safe group class
├── GroupBuilder.kt          # DSL for group construction
└── MultiElementFixture.kt   # Multi-element fixture support

fx/group/
├── DistributionStrategy.kt  # Phase distribution strategies
└── GroupFxExtensions.kt     # Extension functions for group effects
```

### Type System

Groups are parameterized by fixture type and implement `FixtureTarget`:

```kotlin
// GroupableFixture is implemented by Fixture and FixtureElement
// FixtureGroup does NOT implement GroupableFixture (prevents recursive types)
class FixtureGroup<T : GroupableFixture> : FixtureTarget, FxTargetable {
    override val targetKey: String get() = name
    override val isGroup: Boolean get() = true
    override val memberCount: Int get() = size
}
```

The `GroupableFixture` interface prevents recursive group types (`FixtureGroup<FixtureGroup<T>>`).
Use `subGroups` for hierarchical organization instead.

This enables:
- Compile-time type checking for fixture operations
- Unified FX targeting (same interface as individual fixtures)
- Querying which effects are active on a group

```kotlin
// Type-safe: only HexFixture methods available
val group: FixtureGroup<HexFixture> = ...
group.fixtures.forEach { it.dimmer.value = 255u }

// Direct group property access (aggregated)
group.dimmer.value = 255u          // Sets all fixtures
group.rgbColour.value = Color.RED  // Sets all fixtures
val level: UByte? = group.dimmer.value  // null if non-uniform
val levels = group.dimmer.memberValues  // [255, 255, ...]

// Type narrowing for capability checks
val dimmerGroup: FixtureGroup<WithDimmer>? = group.asCapable()

// FxTargetable allows querying effects
val effects = fxEngine.getEffectsForGroup(group.name)
```

## Group Members

Each fixture in a group is wrapped in `GroupMember`:

```kotlin
data class GroupMember<T : FixtureTarget>(
    val fixture: T,
    val index: Int,               // 0-based position in group
    val normalizedPosition: Double, // 0.0 to 1.0
    val metadata: MemberMetadata
)

data class MemberMetadata(
    val panOffset: Double = 0.0,       // Pan center offset (degrees)
    val tiltOffset: Double = 0.0,      // Tilt center offset (degrees)
    val symmetricInvert: Boolean = false, // Invert for mirror effects
    val tags: Set<String> = emptySet()   // Filtering tags
)
```

## Creating Groups

### Using the DSL Builder

```kotlin
fixtures.register {
    val hex1 = addFixture(HexFixture(universe, "hex-1", "Hex 1", 1))
    val hex2 = addFixture(HexFixture(universe, "hex-2", "Hex 2", 13))
    val hex3 = addFixture(HexFixture(universe, "hex-3", "Hex 3", 25))
    val hex4 = addFixture(HexFixture(universe, "hex-4", "Hex 4", 37))

    // Create group with spread pan offsets
    createGroup<HexFixture>("front-wash") {
        addSpread(listOf(hex1, hex2, hex3, hex4), panSpread = 120.0)
        configure(symmetricMode = SymmetricMode.MIRROR)
    }

    // Or add individually with custom metadata
    createGroup<HexFixture>("stage-left") {
        add(hex1, panOffset = -60.0)
        add(hex2, panOffset = -30.0, tags = setOf("inner"))
    }
}
```

### Standalone Groups

```kotlin
val myGroup = fixtureGroup<HexFixture>("my-group") {
    addAll(hex1, hex2, hex3)
}
```

## Distribution Strategies

Distribution strategies determine how effect phases are distributed across group members:

| Strategy | Description |
|----------|-------------|
| `LINEAR` | Evenly spaced phases (chase effect) |
| `UNIFIED` | All fixtures same phase (synchronized) |
| `CENTER_OUT` | Effects radiate from center |
| `EDGES_IN` | Effects converge to center |
| `REVERSE` | Reverse linear order |
| `SPLIT` | Left/right halves mirror each other |
| `PING_PONG` | Back-and-forth sweep |
| `RANDOM(seed)` | Deterministic random offsets |
| `POSITIONAL` | Based on normalized position |
| `CUSTOM(fn)` | Lambda-based calculation |

### Example Usage

```kotlin
val group = fixtures.group<HexFixture>("front-wash")

// Chase effect - each fixture offset
group.applyDimmerFx(
    fxEngine,
    Pulse(),
    distribution = DistributionStrategy.LINEAR
)

// Synchronized colour - all same
group.applyColourFx(
    fxEngine,
    RainbowCycle(),
    distribution = DistributionStrategy.UNIFIED
)

// Center-out dimmer effect
group.applyDimmerFx(
    fxEngine,
    SineWave(),
    distribution = DistributionStrategy.CENTER_OUT
)
```

## Group FX Extensions

Extension functions provide type-safe effect application. Each function creates a **single group-level FxInstance** that the engine expands to group members at processing time:

```kotlin
// Each returns a single effect ID (not a list)
fun <T> FixtureGroup<T>.applyDimmerFx(...): Long
    where T : FixtureTarget, T : WithDimmer

fun <T> FixtureGroup<T>.applyColourFx(...): Long
    where T : FixtureTarget, T : WithColour

fun <T> FixtureGroup<T>.applyPositionFx(...): Long
    where T : FixtureTarget, T : WithPosition

fun <T> FixtureGroup<T>.applyUvFx(...): Long
    where T : FixtureTarget, T : WithUv

// Clear all effects for group (both group-level and per-fixture)
fun FixtureGroup<*>.clearFx(engine: FxEngine): Int
```

### Group-Level Targeting

The key architectural decision is that group effects create a single `FxInstance` with a group target:

```kotlin
// This creates ONE effect that targets the group
val effectId = group.applyDimmerFx(fxEngine, Pulse(),
    distribution = DistributionStrategy.LINEAR)

// The FxInstance stores:
// - target: FxTargetRef.GroupRef("front-wash")
// - distributionStrategy: LINEAR

// At processing time, FxEngine:
// 1. Looks up the group by name
// 2. Iterates members with distribution offsets
// 3. Applies effect to each fixture
```

This enables querying effects by group:

```kotlin
val activeEffects = fxEngine.getEffectsForGroup("front-wash")
fxEngine.removeEffectsForGroup("front-wash")
```

### DSL Builder for Multiple Effects

```kotlin
group.fx(fxEngine) {
    dimmer<HexFixture>(
        Pulse(),
        beatDivision = BeatDivision.QUARTER,
        distribution = DistributionStrategy.LINEAR
    )
    colour<HexFixture>(
        RainbowCycle(),
        beatDivision = BeatDivision.ONE_BAR,
        distribution = DistributionStrategy.UNIFIED
    )
}
```

## Group Manipulation

Groups support various filtering and transformation operations:

```kotlin
val group = fixtures.group<HexFixture>("front-wash")

// Get subsets
val left = group.leftHalf()
val right = group.rightHalf()
val center = group.center(margin = 0.25)
val edges = group.edges(margin = 0.25)

// Filter by predicate
val evens = group.everyNth(2, offset = 0)
val tagged = group.withTags("inner", "front")

// Transform
val reversed = group.reversed()
val (leftSplit, rightSplit) = group.splitAt(0.5)

// Type narrowing
val positionGroup: FixtureGroup<WithPosition>? = group.asCapable()
```

## Multi-Element Fixtures

Fixtures with multiple controllable elements implement `MultiElementFixture`:

```kotlin
interface FixtureElement<P : Fixture> {
    val parentFixture: P
    val elementIndex: Int
    val elementKey: String
}

interface MultiElementFixture<E : FixtureElement<*>> {
    val elements: List<E>
    val elementCount: Int
}
```

### Example: QuadMoverBarFixture

```kotlin
@FixtureType("quad-mover-bar")
class QuadMoverBarFixture(...) : DmxFixture(...),
    WithDimmer,
    MultiElementFixture<QuadMoverBarFixture.Head>
{
    inner class Head(override val elementIndex: Int) :
        Fixture(...),
        FixtureElement<QuadMoverBarFixture>,
        WithDimmer,
        WithColour,
        WithPosition
    {
        override val parentFixture get() = this@QuadMoverBarFixture
        // ... implement traits
    }

    override val elements = (0 until 4).map { Head(it) }
    override val dimmer = DmxSlider(...)  // Master dimmer
}
```

### Automatic FX Expansion to Elements

When a fixture effect targets a property that the parent fixture doesn't have but its elements do, the FX engine automatically expands the effect to all elements with distribution strategy support. This means you don't need to create a group just to apply effects to elements.

For example, `QuadMoverBarFixture` has `WithDimmer` (master dimmer) but not `WithColour`. Its `Head` elements have `WithColour`. Applying a colour FX to the parent automatically distributes it across all heads:

```kotlin
// This automatically expands to all 4 heads with distribution
fxEngine.addEffect(FxInstance(
    effect = RainbowCycle(),
    target = ColourTarget("quad-mover-1"),
    timing = FxTiming(BeatDivision.ONE_BAR),
).apply {
    distributionStrategy = DistributionStrategy.LINEAR
})
```

Via the REST API:
```json
POST /api/rest/fx/add
{
  "effectType": "RainbowCycle",
  "fixtureKey": "quad-mover-1",
  "propertyName": "rgbColour",
  "distributionStrategy": "LINEAR"
}
```

See the [FX System docs](fx-engineering.md#multi-element-fixture-expansion) for full details.

### Group FX Element Mode

When a group contains multi-element fixtures and the effect targets an element-level property, the `elementMode` field controls how distribution is applied:

| Mode | Behaviour | Example (2 quad movers, 4 heads each) |
|------|-----------|---------------------------------------|
| `PER_FIXTURE` | Each fixture gets the effect independently across its own elements. All fixtures look the same. | Head #0 on both fixtures = same colour. Distribution across 4 heads per fixture. |
| `FLAT` | All elements across all fixtures form one flat list. Distribution runs across the entire set. | 8 elements total (0-7). A LINEAR chase sweeps across all 8 heads sequentially. |

`elementMode` defaults to `PER_FIXTURE` and is only relevant when group members are multi-element fixtures whose elements have the target property. When members directly have the property, `elementMode` is ignored.

**Script example:**
```kotlin
val group = fixtures.group<QuadMoverBarFixture>("all-movers")

// PER_FIXTURE: each fixture's heads run the same rainbow
fxEngine.addEffect(FxInstance(
    effect = RainbowCycle(),
    target = ColourTarget.forGroup("all-movers"),
    timing = FxTiming(BeatDivision.ONE_BAR),
).apply {
    distributionStrategy = DistributionStrategy.LINEAR
    elementMode = ElementMode.PER_FIXTURE
})

// FLAT: rainbow sweeps across all 8 heads sequentially
fxEngine.addEffect(FxInstance(
    effect = RainbowCycle(),
    target = ColourTarget.forGroup("all-movers"),
    timing = FxTiming(BeatDivision.ONE_BAR),
).apply {
    distributionStrategy = DistributionStrategy.LINEAR
    elementMode = ElementMode.FLAT
})
```

**REST API:**
```json
POST /api/rest/groups/all-movers/fx
{
  "effectType": "RainbowCycle",
  "propertyName": "colour",
  "distribution": "LINEAR",
  "elementMode": "FLAT"
}
```

### Adding Elements to Groups

```kotlin
val quadBar = addFixture(QuadMoverBarFixture(...))

// Add all heads as separate group members
createGroup<WithPosition>("all-heads") {
    addElements(quadBar, panSpread = 60.0)
}

// Apply chase across individual heads
fixtures.group<WithPosition>("all-heads").applyPositionFx(
    fxEngine,
    Circle(),
    distribution = DistributionStrategy.LINEAR
)
```

### Direct Element Access via elementsGroup

Multi-element fixtures provide a convenient `elementsGroup` extension property that returns all elements as a `FixtureGroup`:

```kotlin
val quadBar: SlenderBeamBarQuadFixture.Mode14Ch = ...

// Get elements as a group
val headGroup = quadBar.elementsGroup  // FixtureGroup<BasicHead>

// The group name is "{fixture-key}-elements"
assertEquals("quad-bar-elements", headGroup.name)

// Set all heads to same position
headGroup.fixtures.forEach { head ->
    head.pan.value = 128u
    head.tilt.value = 64u
}

// Use group filtering operations
val leftHeads = quadBar.elementsGroup.leftHalf()
val everyOther = quadBar.elementsGroup.everyNth(2)
val reversed = quadBar.elementsGroup.reversed()

// Filter by element tags (automatically set as "element" and "element-N")
val head1Only = quadBar.elementsGroup.withTags("element-1")
```

Elements are automatically indexed with:
- Sequential indices (0 to elementCount-1)
- Normalized positions (0.0 to 1.0)
- Tags: `"element"` and `"element-N"` for each element

This is simpler than creating a named group via `addElements()` when you just need to operate on a single fixture's elements.

## REST API

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/rest/groups` | List all groups |
| GET | `/api/rest/groups/{name}` | Get group details |
| GET | `/api/rest/groups/{name}/properties` | Get aggregated property descriptors |
| GET | `/api/rest/groups/{name}/fx` | Get active effects for group |
| POST | `/api/rest/groups/{name}/fx` | Apply effect to group |
| DELETE | `/api/rest/groups/{name}/fx` | Clear group effects |
| GET | `/api/rest/groups/distribution-strategies` | List strategies |

### Request/Response Examples

**List Groups:**
```json
GET /api/rest/groups

[
  {
    "name": "front-wash",
    "memberCount": 4,
    "capabilities": ["dimmer", "colour", "uv"],
    "symmetricMode": "MIRROR",
    "defaultDistribution": "LINEAR"
  }
]
```

**Get Group Effects:**
```json
GET /api/rest/groups/front-wash/fx

[
  {
    "id": 1001,
    "effectType": "Pulse",
    "propertyName": "dimmer",
    "beatDivision": 1.0,
    "blendMode": "OVERRIDE",
    "distribution": "LINEAR",
    "elementMode": null,
    "isRunning": true
  }
]
```

The `elementMode` field is `null` when the effect targets properties that members have directly. It shows `"PER_FIXTURE"` or `"FLAT"` when the effect expands to multi-element fixture elements.

**Add Group Effect:**
```json
POST /api/rest/groups/front-wash/fx
{
  "effectType": "pulse",
  "propertyName": "dimmer",
  "beatDivision": 1.0,
  "blendMode": "OVERRIDE",
  "distribution": "LINEAR",
  "elementMode": "PER_FIXTURE",
  "parameters": {
    "min": "0",
    "max": "255"
  }
}

Response:
{
  "effectId": 1001
}
```

The `elementMode` field defaults to `"PER_FIXTURE"` and controls how effects distribute across multi-element fixture elements. See [Group FX Element Mode](#group-fx-element-mode).

Note: The response returns a single `effectId` because one `FxInstance` is created that targets the entire group. The engine expands this to group members at processing time.

## Group Properties

Groups support direct property access via extension properties that aggregate across all members.

### Direct Property Access

Extension properties provide type-safe access to group properties:

```kotlin
// Extension properties require bounded types
val <T> FixtureGroup<T>.dimmer: AggregateSlider
    where T : FixtureTarget, T : WithDimmer

val <T> FixtureGroup<T>.rgbColour: AggregateColour
    where T : FixtureTarget, T : WithColour

val <T> FixtureGroup<T>.uv: AggregateSlider
    where T : FixtureTarget, T : WithUv
```

### Value Semantics

- **Setting**: `group.dimmer.value = 200u` sets ALL member fixtures
- **Getting uniform**: Returns value if all members match, null otherwise
- **Getting non-uniform**: `group.dimmer.memberValues` returns list of individual values
- **Uniformity check**: `group.dimmer.isUniform` checks if all values match
- **Range access**: `group.dimmer.minValue` / `maxValue` for bounds

```kotlin
val group = fixtures.group<HexFixture>("front-wash")

group.dimmer.value = 200u                    // Set all to 200
assertTrue(group.dimmer.isUniform)           // All same
assertEquals(200u, group.dimmer.value)       // Returns uniform value

fixtures[0].dimmer.value = 100u              // Change one
assertFalse(group.dimmer.isUniform)          // Now non-uniform
assertNull(group.dimmer.value)               // Null because mixed
assertEquals(100u, group.dimmer.minValue)
assertEquals(200u, group.dimmer.maxValue)
```

### REST API Property Aggregation

Groups also expose aggregated property descriptors that include channel references for all group members.
This enables the frontend to:
- View property values with range/summary display for mixed values (e.g., "50-100%")
- Set uniform values across all group members simultaneously

### Property Aggregation

The `generateGroupPropertyDescriptors()` extension function collects property descriptors from all
group members and combines them:

```kotlin
// Extension function in fixture/group/GroupPropertyAggregation.kt
fun FixtureGroup<*>.generateGroupPropertyDescriptors(): List<GroupPropertyDescriptor>

// Returns descriptors with memberChannels list for each property
// Only properties common to ALL members are included
```

### Setting Properties in Scripts

To set properties on all group fixtures programmatically:

```kotlin
val group = fixtures.group<HexFixture>("front-wash")

// Direct group property access (preferred)
group.dimmer.value = 200u
group.rgbColour.value = Color(255, 128, 0)  // Orange

// Or iterate through fixtures individually
group.fixtures.forEach { it.dimmer.value = 200u }

// With fade transition
val transaction = controller.startTransaction(fadeMs = 1000)
val groupWithTx = fixtures.withTransaction(transaction).group<HexFixture>("front-wash")
groupWithTx.dimmer.value = 255u  // Fade all to full
transaction.commit()
```

### Frontend Integration

The frontend Properties dialog in "By Group" view uses WebSocket `updateChannel` messages
to set values. When editing a group property, it sends individual channel updates for each
member fixture, which the backend processes and broadcasts as `channelState` updates.

## WebSocket Messages

### Inbound (Client → Server)

| Message | Description |
|---------|-------------|
| `groupsState` | Request current groups state |
| `clearGroupFx` | Clear all effects for a group |

### Outbound (Server → Client)

| Message | Description |
|---------|-------------|
| `groupsState` | Groups list with capabilities |
| `groupFxCleared` | Confirmation of effect removal |

## Symmetric Modes

Groups can be configured with symmetric effect behavior:

| Mode | Description |
|------|-------------|
| `NONE` | No symmetry |
| `MIRROR` | Left/right halves mirror each other |
| `CENTER_OUT` | Effects radiate from center |
| `EDGES_IN` | Effects converge to center |

These modes work with the `symmetricInvert` member metadata to automatically invert effect directions for symmetric positioning.

## Hierarchical Groups (SubGroups)

Groups support hierarchical composition through the `subGroups` property. This allows you to create groups that contain other groups of the same fixture type, with automatic flattening when accessing fixtures.

### Adding SubGroups

Use `addGroup()` or `addGroups()` in the builder to add child groups:

```kotlin
// Create sub-groups
val frontHexes = createGroup<HexFixture>("front-hexes") {
    addSpread(listOf(hex1, hex2))
}
val atmosphericHexes = createGroup<HexFixture>("atmospheric-hexes") {
    addSpread(listOf(hex3, hex4))
}

// Create parent group containing sub-groups
val allHexes = createGroup<HexFixture>("all-hexes") {
    addGroup(frontHexes)
    addGroup(atmosphericHexes)
    // Or: addGroups(listOf(frontHexes, atmosphericHexes))
    // Or: addGroups(frontHexes, atmosphericHexes)
}

// Access flattened fixtures - all 4 HexFixtures
allHexes.fixtures.forEach { it.dimmer.value = 255u }

// Or use group properties directly
allHexes.dimmer.value = 255u  // Sets all 4 fixtures
```

### Key Properties

- `subGroups`: List of child `FixtureGroup<T>` instances
- `members`: Direct fixture members only (excludes subgroup fixtures)
- `allMembers`: Combined list of direct members + subgroup members, reindexed
- `fixtures`: Shorthand for `allMembers.map { it.fixture }`

### Member Ordering

When a group has both direct members and subgroups, `allMembers` orders them as:
1. Direct members (in order added)
2. Subgroup members (flattened, in order subgroups were added)

```kotlin
val subGroup = fixtureGroup<UVFixture>("sub") {
    add(fixture0)
    add(fixture1)
}

val parentGroup = fixtureGroup<UVFixture>("parent") {
    add(fixture10)      // Direct member
    addGroup(subGroup)  // Subgroup with 2 fixtures
    add(fixture20)      // Direct member
}

// Result order: fixture10, fixture20, fixture0, fixture1
assertEquals("fixture-10", parentGroup.fixtures[0].key)
assertEquals("fixture-20", parentGroup.fixtures[1].key)
assertEquals("fixture-0", parentGroup.fixtures[2].key)
assertEquals("fixture-1", parentGroup.fixtures[3].key)
```

### Nested SubGroups

SubGroups can themselves contain subgroups, and flattening is recursive:

```kotlin
val innerGroup = fixtureGroup<HexFixture>("inner") {
    add(hex1)
}

val middleGroup = fixtureGroup<HexFixture>("middle") {
    addGroup(innerGroup)
    add(hex2)
}

val outerGroup = fixtureGroup<HexFixture>("outer") {
    addGroup(middleGroup)
    add(hex3)
}

// Flattens to: hex3, hex2, hex1 (direct members first at each level)
assertEquals(3, outerGroup.fixtures.size)
```

### Operations with SubGroups

All group operations work with the flattened `allMembers`:

```kotlin
val group = fixtureGroup<HexFixture>("parent") {
    addGroup(subGroup1)  // 2 fixtures
    addGroup(subGroup2)  // 2 fixtures
}

// Filter operates on all 4 fixtures
val leftHalf = group.leftHalf()

// memberCount includes all fixtures
assertEquals(4, group.memberCount)

// Transaction propagates to subgroups
val boundGroup = group.withTransaction(transaction)
```

## Flatten Method

The `flatten()` method returns all fixtures from the group and sub-groups:

```kotlin
// Recursively extract all fixtures
fun FixtureGroup<*>.flatten(): List<FixtureTarget>

// Type-filtered flattening
inline fun <reified R : FixtureTarget> FixtureGroup<*>.flattenAs(): List<R>
```

### Example

```kotlin
val innerGroup = fixtureGroup<HexFixture>("inner") {
    add(hex1)
    add(hex2)
}

val outerGroup = fixtureGroup<HexFixture>("outer") {
    add(hex3)
    addGroup(innerGroup)
}

// Flatten returns all 3 HexFixtures: hex3, hex1, hex2
val allFixtures = outerGroup.flatten()
assertEquals(3, allFixtures.size)

// Type-safe flattening
val hexFixtures = outerGroup.flattenAs<HexFixture>()
```

## Transaction Support

Groups integrate with the transaction system:

```kotlin
val transaction = controller.startTransaction(fadeMs = 1000)

val group = fixtures.withTransaction(transaction).group<HexFixture>("front-wash")
group.fixtures.forEach { it.dimmer.value = 255u }

transaction.commit()
```

## Best Practices

1. **Use specific types**: Create groups with the most specific type possible for better type safety
2. **Name groups descriptively**: Use names that indicate location/purpose (e.g., "front-wash", "back-movers")
3. **Configure pan/tilt offsets**: For position effects, set member offsets based on physical fixture positions
4. **Use distribution strategies**: Choose appropriate strategies for the desired visual effect
5. **Clear effects before new ones**: Call `clearFx()` before applying new effects to avoid accumulation
