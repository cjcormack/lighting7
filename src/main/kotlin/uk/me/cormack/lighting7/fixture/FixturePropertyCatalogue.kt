package uk.me.cormack.lighting7.fixture

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

/**
 * Per-class catalogue of `@FixtureProperty` members, built once per class for the life of
 * the process.
 *
 * The `memberProperties` scan this replaces used to run **per instance**, in [Fixture]'s
 * constructor. That reads as harmless — a fixture is patched once — but the FX tick allocates a
 * fresh `ControllerTransaction` every 20 ms and
 * [Fixtures.FixturesWithTransaction][uk.me.cormack.lighting7.show.Fixtures.FixturesWithTransaction]
 * binds a fixture to it by *constructing a new instance*. Its `wrappedFixtureCache` dedupes
 * within one tick but cannot dedupe across ticks, so every fixture touched by a running effect
 * re-ran a full member scan, an annotation filter per member, and a fresh [Fixture.Property]
 * list, fifty times a second. Keying on the class instead is exact: nothing in a catalogue
 * depends on the instance (see below), so the only thing the per-instance rebuild bought was
 * the cost.
 *
 * **The catalogue is a pure function of the runtime class, but the *values* are not.**
 * [Fixture.Property.classProperty] is an unbound `KProperty1`; the receiver is supplied at call
 * time, and a property may legitimately resolve to null on some instances of the class and not
 * others (`Scantastic4Fixture.ScannerHead.colourPattern` is `if (hasColour) DmxSlider(…) else
 * null`). So: cache descriptors here, never resolved values.
 *
 * Elements are catalogued the same way even though
 * [FixtureElement][uk.me.cormack.lighting7.fixture.group.FixtureElement] does not extend
 * [Fixture]. [Fixture.Property.classProperty]'s `out Fixture` receiver bound is already treated
 * as advisory across the codebase — every read site supplies an explicit receiver and casts as
 * needed — so one entry type serves both rather than a second parallel implementation. That
 * duplication is exactly what this object was created to remove: the same scan was written out
 * six times, of which only one (`PropertyChannelWriter`'s element catalogue, whose rationale
 * this KDoc inherits) was cached.
 *
 * Thread-safe by [ConcurrentHashMap] plus `computeIfAbsent`, so a class is scanned at most once
 * even under concurrent first construction. [Fixture] is a sealed hierarchy and elements are
 * declared alongside their parents, so every key is an application-classloader class and the
 * map is bounded by the number of fixture types.
 */
object FixturePropertyCatalogue {

    /**
     * One class's `@FixtureProperty` members, pre-indexed for the lookups the tick path makes.
     *
     * [all] preserves `memberProperties` order. The indexes resolve ties first-wins, matching
     * the `find {}` / `firstOrNull {}` calls they replaced.
     */
    class Entry(
        /** Every annotated property on the class, in declaration-scan order. */
        val all: List<Fixture.Property>,
        /** [all] indexed by property name — replaces a linear scan per name lookup. */
        val byName: Map<String, Fixture.Property>,
        /** The `bundleWithColour` sliders indexed by category (WHITE / AMBER / UV). */
        val bundledByCategory: Map<PropertyCategory, Fixture.Property>,
        /** The [PropertyCategory.COLOUR] property, if the class declares one. */
        val colour: Fixture.Property?,
        /** The class's `@FixtureType`. Null for element classes, which carry none. */
        val fixtureType: FixtureType?,
    )

    private val catalogues = ConcurrentHashMap<KClass<*>, Entry>()

    /**
     * The catalogue for [klass], scanning it on first use.
     *
     * The `get` before the `computeIfAbsent` is not redundant: `computeIfAbsent` only returns
     * lock-free when the key happens to be the *first* node in its bin, and otherwise
     * `synchronized`s on the bin head even for a key that is already present. This is called
     * once per [Fixture] construction — i.e. per touched fixture per tick, 50×/s — so the
     * steady state must not be able to take a lock. `computeIfAbsent` is still the only writer,
     * so a cold class is scanned at most once however many threads race it.
     */
    fun of(klass: KClass<*>): Entry =
        catalogues[klass] ?: catalogues.computeIfAbsent(klass) { build(it) }

    private fun build(klass: KClass<*>): Entry {
        val all = klass.memberProperties.flatMap { classProperty ->
            @Suppress("UNCHECKED_CAST")
            val asFixtureProperty = classProperty as KProperty1<out Fixture, *>
            classProperty.annotations.filterIsInstance<FixtureProperty>().map { annotation ->
                Fixture.Property.fromAnnotation(asFixtureProperty, annotation)
            }
        }
        return Entry(
            all = all,
            byName = buildMap { for (property in all) putIfAbsent(property.name, property) },
            bundledByCategory = buildMap {
                for (property in all) if (property.bundleWithColour) putIfAbsent(property.category, property)
            },
            colour = all.firstOrNull { it.category == PropertyCategory.COLOUR },
            fixtureType = klass.annotations.filterIsInstance<FixtureType>().firstOrNull(),
        )
    }
}
