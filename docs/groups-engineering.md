# Fixture Groups Engineering Documentation

This document describes the type-safe fixture group system for treating multiple fixtures as a single unit.

## Overview

The fixture group system provides:
- **Type-safe groups**: Compile-time enforcement of fixture capabilities
- **FxTargetable interface**: Groups implement the same targeting interface as fixtures
- **Group-level FX targeting**: A single FxInstance targets the entire group; engine expands at processing time
- **Position-aware members**: Each fixture has a position within the group for FX distribution
- **Orders**: Various patterns for distributing FX phases across group members
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
├── SpreadOrder.kt  # Spread Orders
└── GroupFxExtensions.kt     # Extension functions for group FX
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
- Querying which FX are active on a group

```kotlin
// Type-safe: only HexFixture methods available
val group: FixtureGroup<HexFixture> = ...
group.fixtures.forEach { it.dimmer.value = 255u }

// Direct group attribute access (aggregated)
group.dimmer.value = 255u          // Sets all fixtures
group.rgbColour.value = Color.RED  // Sets all fixtures
val level: UByte? = group.dimmer.value  // null if non-uniform
val levels = group.dimmer.memberValues  // [255, 255, ...]

// Type narrowing for capability checks
val dimmerGroup: FixtureGroup<WithDimmer>? = group.asCapable()

// FxTargetable allows querying FX
val effects = fxEngine.getFxForGroup(group.name)
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
    val symmetricInvert: Boolean = false, // Invert for mirror FX
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

## Orders

Orders determine how FX phases are distributed across group members.
The distribution offset is **subtracted** from the clock phase, so higher-offset members are
behind in the cycle and the visual sweep flows in the natural direction.

| Order | Description | Distinct Slots | Offset Formula |
|----------|-------------|---------------|----------------|
| `NORMAL` | Evenly spaced, element 0 → N | N | `index / N` |
| `UNIFIED` | All same phase (synchronized) | 1 | `0` |
| `CENTRE_OUT` | Rank-based: center → edges | ⌈N/2⌉ | `rank / ⌈N/2⌉` (centre=0) |
| `CENTRE_IN` | Rank-based: edges → center | ⌈N/2⌉ | `(maxRank - rank) / ⌈N/2⌉` |
| `REVERSE` | Evenly spaced, element N → 0 | N | `(N-1-index) / N` |
| `PING_PONG` | NORMAL + triangle phase bounce | N | `index / N` (triangle remap at phase level) |
| `RANDOM(seed)` | Fisher-Yates shuffle of even offsets | N | Deterministic permutation of `i/N` |
| `POSITIONAL` | Based on normalized position | N | `member.normalizedPosition` |
| `CUSTOM(fn)` | Lambda-based calculation | N | User-defined |

**Symmetric Orders** (CENTRE_OUT, CENTRE_IN) have fewer distinct offset slots
than group members because symmetric pairs share the same offset. This is exposed via
`distinctSlots(groupSize)` and used by a windowed step chase for gap-free window sizing.

**Order interface:**

```kotlin
sealed interface SpreadOrder {
    val hasSpread: Boolean              // false only for UNIFIED
    val usesTrianglePhase: Boolean      // true only for PING_PONG
    fun distinctSlots(groupSize: Int): Int
    fun calculateOffset(member: DistributionMemberInfo, groupSize: Int): Double
}
```

### Example Usage

```kotlin
val group = fixtures.group<HexFixture>("front-wash")

// Chase FX - each fixture offset
group.applyDimmerFx(
    fxEngine,
    Pulse(),
    order = SpreadOrder.NORMAL
)

// Synchronized colour - all same
group.applyColourFx(
    fxEngine,
    RainbowCycle(),
    order = SpreadOrder.UNIFIED
)

// Centre-out dimmer FX
group.applyDimmerFx(
    fxEngine,
    SineWave(),
    order = SpreadOrder.CENTRE_OUT
)
```

## Member order

A patched group's order is `fixture_group_members.sort_order` (venue-visit plan S7). The loader
builds each group's members in that order, less any venue patch the show hides, and numbers them
`index` 0…n−1 with `normalizedPosition = i / (n − 1)`. Everything above reads only those two, so the
stored order is the order a group's FX run in under every Order. `POSITIONAL` included: it is
the list position, not the stage. A **spread** whose one target is a group runs in the group's order
too (each fixture's elements in order) — a busk group tile, and a list selection that is exactly one group row
with every member listed and none filtered out, which the client sends as the group
(`wholeGroupOf`). Any other selection spreads in rig order (`state/BuskRigOrder.kt`),
which places a fixture at its first appearance on the rig, so an earlier group or tile holding it can
put it anywhere (`routes/programmerFan.kt`).

**Writing it.** The patch-group PUT's `memberOrder` (patch ids) and the `edit_groups` tool's
`members` both go through `reorderGroupMembers` (`models/fixtureGroups.kt`). The members named fill,
in the order given, the places the members named held. A member not named **keeps its place**, and
every member is renumbered `0…n−1`. That is what lets a view send only what it shows: no view of a
show lists a venue patch the show hides, and the Groups views leave out an infrastructure head. Ties
in an old stored order break by row id, the order members were added in. A venue's group seen from a
show is the venue's to order (409 `VENUE_OWNED`, like every write to it).

**Reading it.** `GET /groups` answers each group's `memberKeys` in order, and the Groups list draws
member rows in that order with their place. `GET /groups/{name}` adds `orderSteps`, each
Order's step per member by `index` (`SpreadOrder.stepsBySize`; `steps` is a member's place
in the sequence the Order brings members round in, 1 first, shared offsets sharing a step). It is a
function of the group's size alone, so the group sheet shows it without mirroring a formula, and
moves with an optimistic reorder. `RANDOM` is seed 0, the seed a group FX gets by name. The
group sheet's *Members* view is where a group is reordered from the Groups views. It is a numbered
list in that order, dragged, with each member's step under the Order *Runs as* picks (the group's
own Order first). A venue's group in a show, and a venue's rig for an operator, are read-only
and say why. Patches' *Edit Group* sheet does the same (lighting7 `frontend/CLAUDE.md` §"The fixture
sheet").

## Group FX Extensions

Extension functions provide type-safe FX application. Each function creates a **single group-level FxInstance** that the engine expands to group members at processing time:

```kotlin
// Each returns a single FX ID (not a list)
fun <T> FixtureGroup<T>.applyDimmerFx(...): Long
    where T : FixtureTarget, T : WithDimmer

fun <T> FixtureGroup<T>.applyColourFx(...): Long
    where T : FixtureTarget, T : WithColour

fun <T> FixtureGroup<T>.applyPositionFx(...): Long
    where T : FixtureTarget, T : WithPosition

fun <T> FixtureGroup<T>.applyUvFx(...): Long
    where T : FixtureTarget, T : WithUv

// Clear all FX for group (both group-level and per-fixture)
fun FixtureGroup<*>.clearFx(engine: FxEngine): Int
```

### Group-Level Targeting

The key architectural decision is that group FX create a single `FxInstance` with a group target:

```kotlin
// This creates ONE FX that targets the group
val fxId = group.applyDimmerFx(fxEngine, Pulse(),
    order = SpreadOrder.NORMAL)

// The FxInstance stores:
// - target: FxTargetRef.GroupRef("front-wash")
// - order: NORMAL

// At processing time, FxEngine:
// 1. Looks up the group by name
// 2. Iterates members with distribution offsets
// 3. Applies the FX to each fixture
```

This enables querying FX by group:

```kotlin
val activeFx = fxEngine.getFxForGroup("front-wash")
fxEngine.removeFxForGroup("front-wash")
```

### DSL Builder for Multiple FX

```kotlin
group.fx(fxEngine) {
    dimmer<HexFixture>(
        Pulse(),
        cycle = CycleLength.ONE_BEAT,
        order = SpreadOrder.NORMAL
    )
    colour<HexFixture>(
        RainbowCycle(),
        cycle = CycleLength.ONE_BAR,
        order = SpreadOrder.UNIFIED
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
val reversed = group.reverseOrder()
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

When a fixture FX targets an attribute that the parent fixture doesn't have but its elements do, the FX engine automatically expands the FX to all elements with Order support. This means you don't need to create a group just to apply FX to elements.

For example, `QuadMoverBarFixture` has `WithDimmer` (master dimmer) but not `WithColour`. Its `Head` elements have `WithColour`. Applying a colour FX to the parent automatically distributes it across all heads:

```kotlin
// This automatically expands to all 4 heads with distribution
fxEngine.addFx(FxInstance(
    effect = RainbowCycle(),
    target = ColourTarget("quad-mover-1"),
    timing = FxTiming(CycleLength.ONE_BAR),
).apply {
    order = SpreadOrder.NORMAL
})
```

Via the REST API:
```json
POST /api/rest/fx/add
{
  "fxType": "RainbowCycle",
  "fixtureKey": "quad-mover-1",
  "attribute": "rgbColour",
  "order": "NORMAL"
}
```

See the [FX System docs](fx-engineering.md#multi-element-fixture-expansion) for full details.

### Group FX Element Mode

When a group contains multi-element fixtures and the FX targets an element-level attribute, the `elementMode` field — *Over* on screen — controls how distribution is applied:

| Mode | Over | Behaviour | Example (2 quad movers, 4 heads each) |
|------|------|-----------|---------------------------------------|
| `WHOLE` | Fixtures | Each fixture is one unit and its elements take its output. Distribution runs across the fixtures. | 2 units: each mover's 4 heads one colour, the two movers apart. |
| `FLAT` | Elements | All elements across all fixtures form one flat list. Distribution runs across the entire set. | 8 elements total (0-7). A NORMAL chase sweeps across all 8 heads sequentially. |
| `PER_FIXTURE` | Each fixture | Each fixture gets the FX independently across its own elements. All fixtures look the same. | Head #0 on both fixtures = same colour. Distribution across 4 heads per fixture. |

An FX started without one — `POST /groups/{name}/fx` included — runs on `WHOLE`; a stored FX naming none means `PER_FIXTURE` (`ElementMode.NEW_FX_DEFAULT` / `STORED_DEFAULT`). It is only relevant when group members are multi-element fixtures whose elements have the target attribute. When members directly have the attribute, `elementMode` is ignored.

**Script example:**
```kotlin
val group = fixtures.group<QuadMoverBarFixture>("all-movers")

// PER_FIXTURE: each fixture's heads run the same rainbow
fxEngine.addFx(FxInstance(
    effect = RainbowCycle(),
    target = ColourTarget.forGroup("all-movers"),
    timing = FxTiming(CycleLength.ONE_BAR),
).apply {
    order = SpreadOrder.NORMAL
    elementMode = ElementMode.PER_FIXTURE
})

// FLAT: rainbow sweeps across all 8 heads sequentially
fxEngine.addFx(FxInstance(
    effect = RainbowCycle(),
    target = ColourTarget.forGroup("all-movers"),
    timing = FxTiming(CycleLength.ONE_BAR),
).apply {
    order = SpreadOrder.NORMAL
    elementMode = ElementMode.FLAT
})
```

**REST API:**
```json
POST /api/rest/groups/all-movers/fx
{
  "fxType": "RainbowCycle",
  "attribute": "colour",
  "order": "NORMAL",
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
    order = SpreadOrder.NORMAL
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
val reversed = quadBar.elementsGroup.reverseOrder()

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
| GET | `/api/rest/groups` | List all groups, each with its `memberKeys` in order |
| GET | `/api/rest/groups/{name}` | Get group details, with each Order's `orderSteps` |
| GET | `/api/rest/groups/{name}/attributes` | Get aggregated attribute descriptors |
| GET | `/api/rest/groups/{name}/fx` | Get active FX for group |
| POST | `/api/rest/groups/{name}/fx` | Apply FX to group |
| DELETE | `/api/rest/groups/{name}/fx` | Clear group FX |
| GET | `/api/rest/spread-orders` | List the spread Orders |

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
    "defaultOrder": "NORMAL"
  }
]
```

**Get Group FX:**
```json
GET /api/rest/groups/front-wash/fx

[
  {
    "id": 1001,
    "fxType": "Pulse",
    "attribute": "dimmer",
    "cycle": 1.0,
    "blendMode": "OVERRIDE",
    "order": "NORMAL",
    "elementMode": null,
    "isRunning": true
  }
]
```

The `elementMode` field is `null` when the FX targets attributes that members have directly. It shows `"WHOLE"`, `"FLAT"` or `"PER_FIXTURE"` when the FX expands to multi-element fixture elements.

**Add Group FX:**
```json
POST /api/rest/groups/front-wash/fx
{
  "fxType": "pulse",
  "attribute": "dimmer",
  "cycle": 1.0,
  "blendMode": "OVERRIDE",
  "order": "NORMAL",
  "elementMode": "WHOLE",
  "parameters": {
    "min": "0",
    "max": "255"
  }
}

Response:
{
  "fxId": 1001
}
```

An absent `elementMode` starts the FX on `"WHOLE"`; it controls how FX distribute across multi-element fixture elements. See [Group FX Element Mode](#group-fx-element-mode).

Note: The response returns a single `fxId` because one `FxInstance` is created that targets the entire group. The engine expands this to group members at processing time.

## Group Attributes

Groups support direct attribute access via extension properties that aggregate across all members.

### Direct Attribute Access

Extension properties provide type-safe access to group attributes:

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

### REST API Attribute Aggregation

Groups also expose aggregated attribute descriptors that include channel references for all group members.
This enables the frontend to:
- View attribute values with range/summary display for mixed values (e.g., "50-100%")
- Set uniform values across all group members simultaneously

### Attribute Aggregation

The `generateGroupAttributeDescriptors()` extension function collects attribute descriptors from all
group members and combines them:

```kotlin
// Extension function in fixture/group/GroupAttributeAggregation.kt
fun FixtureGroup<*>.generateGroupAttributeDescriptors(): List<GroupAttributeDescriptor>

// Returns descriptors with memberChannels list for each attribute
// Only attributes common to ALL members are included
```

### Setting Attributes in Scripts

To set attributes on all group fixtures programmatically:

```kotlin
val group = fixtures.group<HexFixture>("front-wash")

// Direct group attribute access (preferred)
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

A group is edited on the **group sheet** — the fixture sheet's body on a group (fixture-fx-sheets
plan D1, D13; lighting7 `frontend/CLAUDE.md` §"The fixture sheet"), in `GroupDetailModal` and on
each `GroupCard`, whose corner opens the former. A strip of member pips picks what the rows edit, and every write is a programmer
write, never a raw channel:

- **All** sends one group-targeted programmer op (`programmer.set` / `setColour` / `setPosition`
  with `targetType: "group"`). `ProgrammerWriter.writeGroupAttribute` fans it to the members and
  tags each slot with the group as `sourceGroup`, which is what lets Record write the value back as
  one group row.
- **A subset of members** sends one fixture-targeted op per member, each carrying the group as
  `sourceGroup` (`validateSourceGroup` drops it unless the group really contains the fixture), so
  Record can still tell the values came through the group.
- A position is resolved per member from the operator's centre-relative value (venue-visit plan D3:
  the same fraction of each member's own travel), and stays one group op only where every member
  lands on the same bytes; a colour head's level with no dimmer scales each member's
  own colour, so it is always per member.

The rows a group offers are the attributes every picked member has, matched by name and type — the
rule `generateGroupAttributeDescriptors` applies — read from the members' own fixture descriptors. A
group attribute descriptor's element-group half (a group of multi-head fixtures' *all heads*
attributes) is not offered there: a group op resolves each member's fixture-level attribute, so on a
member whose colour lives on its heads it resolves no channels. Each member's heads are edited on
that member's own sheet.

## WebSocket Messages

### Inbound (Client → Server)

| Message | Description |
|---------|-------------|
| `groupsState` | Request current groups state |
| `clearGroupFx` | Clear all FX for a group |

### Outbound (Server → Client)

| Message | Description |
|---------|-------------|
| `groupsState` | Groups list with capabilities |
| `groupFxCleared` | Confirmation of FX removal |

## Symmetric Modes

Groups can be configured with symmetric FX behavior:

| Mode | Description |
|------|-------------|
| `NONE` | No symmetry |
| `MIRROR` | Left/right halves mirror each other |
| `CENTRE_OUT` | FX radiate from center |
| `CENTRE_IN` | FX converge to center |

These modes work with the `symmetricInvert` member metadata to automatically invert FX directions for symmetric positioning.

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

// Or use group attributes directly
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
3. **Configure pan/tilt offsets**: For position FX, set member offsets based on physical fixture positions
4. **Use Orders**: Choose appropriate Orders for the desired visual effect
5. **Clear FX before new ones**: Call `clearFx()` before applying new FX to avoid accumulation
