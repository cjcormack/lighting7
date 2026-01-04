package uk.me.cormack.lighting7.fixture.group

import uk.me.cormack.lighting7.fixture.FixtureTarget
import uk.me.cormack.lighting7.fixture.group.property.GroupColour
import uk.me.cormack.lighting7.fixture.group.property.GroupPosition
import uk.me.cormack.lighting7.fixture.group.property.GroupSlider
import uk.me.cormack.lighting7.fixture.group.property.GroupStrobe
import uk.me.cormack.lighting7.fixture.property.AggregateColour
import uk.me.cormack.lighting7.fixture.property.AggregatePosition
import uk.me.cormack.lighting7.fixture.property.AggregateSlider
import uk.me.cormack.lighting7.fixture.property.AggregateStrobe
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithStrobe
import uk.me.cormack.lighting7.fixture.trait.WithUv

/**
 * Extension properties for accessing group-level properties.
 *
 * These extensions enable ergonomic property access on fixture groups:
 * ```kotlin
 * val group = fixtures.group<HexFixture>("front-wash")
 * group.dimmer.value = 200u       // Set all members
 * group.rgbColour.value = Color.RED
 * ```
 *
 * The extensions are type-bounded, so they only appear when the group's
 * member type implements the corresponding trait.
 */

// ============================================
// Dimmer Extensions
// ============================================

/**
 * Get the aggregated dimmer for groups where all members have dimmer capability.
 *
 * Works for any group depth - for `FixtureGroup<FixtureGroup<T>>`, the dimmer
 * delegates through to nested groups' dimmers.
 *
 * @return AggregateSlider providing unified access to all member dimmers
 */
val <T> FixtureGroup<T>.dimmer: AggregateSlider
    where T : FixtureTarget, T : WithDimmer
    get() = GroupSlider(this) { it.dimmer }

// ============================================
// Colour Extensions
// ============================================

/**
 * Get the aggregated RGB colour for groups where all members have colour capability.
 *
 * Works for any group depth - for `FixtureGroup<FixtureGroup<T>>`, the colour
 * delegates through to nested groups' colours.
 *
 * @return AggregateColour providing unified access to all member colours
 */
val <T> FixtureGroup<T>.rgbColour: AggregateColour
    where T : FixtureTarget, T : WithColour
    get() = GroupColour(this) { it.rgbColour }

// ============================================
// Position Extensions
// ============================================

/**
 * Get the aggregated position for groups where all members have position capability.
 *
 * @return AggregatePosition providing unified access to all member positions
 */
val <T> FixtureGroup<T>.position: AggregatePosition
    where T : FixtureTarget, T : WithPosition
    get() = GroupPosition(this, { it.pan }, { it.tilt })

/**
 * Get the aggregated pan slider for groups where all members have position capability.
 *
 * @return AggregateSlider providing unified access to all member pan values
 */
val <T> FixtureGroup<T>.pan: AggregateSlider
    where T : FixtureTarget, T : WithPosition
    get() = GroupSlider(this) { it.pan }

/**
 * Get the aggregated tilt slider for groups where all members have position capability.
 *
 * @return AggregateSlider providing unified access to all member tilt values
 */
val <T> FixtureGroup<T>.tilt: AggregateSlider
    where T : FixtureTarget, T : WithPosition
    get() = GroupSlider(this) { it.tilt }

// ============================================
// UV Extensions
// ============================================

/**
 * Get the aggregated UV slider for groups where all members have UV capability.
 *
 * @return AggregateSlider providing unified access to all member UV values
 */
val <T> FixtureGroup<T>.uv: AggregateSlider
    where T : FixtureTarget, T : WithUv
    get() = GroupSlider(this) { it.uv }

// ============================================
// Strobe Extensions
// ============================================

/**
 * Get the aggregated strobe for groups where all members have strobe capability.
 *
 * @return AggregateStrobe providing unified access to all member strobes
 */
val <T> FixtureGroup<T>.strobe: AggregateStrobe
    where T : FixtureTarget, T : WithStrobe
    get() = GroupStrobe(this) { it.strobe }
