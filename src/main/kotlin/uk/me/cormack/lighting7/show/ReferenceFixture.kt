package uk.me.cormack.lighting7.show

import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.models.EffectTargetRef
import uk.me.cormack.lighting7.models.TargetRef

/**
 * The fixture a target is read through — for a property's attribute family, or a factory's
 * reflective lookup: a fixture or cell itself, a group's first member. Null when it does not resolve.
 */
fun Fixtures.referenceFixtureFor(target: TargetRef): GroupableFixture? = try {
    when (target) {
        is TargetRef.Group -> untypedGroup(target.key).fixtures.firstOrNull()
        is TargetRef.Fixture -> untypedGroupableFixture(target.key)
    }
} catch (_: Exception) {
    null
}

/** A selection's reference fixture is its first item's that resolves. */
fun Fixtures.referenceFixtureFor(target: EffectTargetRef): GroupableFixture? =
    target.items.firstNotNullOfOrNull { referenceFixtureFor(it) }
