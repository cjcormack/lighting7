package uk.me.cormack.lighting7.fixture.locate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fx.LocateValueResolver
import uk.me.cormack.lighting7.models.DaoFixturePatch
import java.util.concurrent.ConcurrentHashMap

/**
 * One unit's **stored locate** (venue-visit plan D6, D17; `docs/fixtures-engineering.md` §"Stored
 * locate"): the pan and tilt Locate points it at instead of the centre of travel, as the DMX bytes
 * Locate writes — coarse, and fine where the unit has a fine channel. Pan and tilt only: every other
 * property keeps its category default.
 *
 * A unit is the fixture itself ([head] null) or one of its heads ([head] its `elementIndex`), so a bar
 * whose heads each pan and tilt stores one per head. Shown and typed on the centre-relative scale
 * (D3, the frontend's `positionScale.ts`); stored as bytes, like every position literal.
 */
@Serializable
data class UnitLocate(
    /** Null for the fixture's own pan and tilt; a head's zero-based `elementIndex` otherwise. */
    val head: Int? = null,
    val pan: Int,
    val tilt: Int,
    /** The fine bytes, where the unit has fine channels; null writes 0 there, as the default locate does. */
    val panFine: Int? = null,
    val tiltFine: Int? = null,
)

/**
 * A patch's stored locate: one [UnitLocate] per unit that has one, in canonical order (the fixture's
 * own first, then heads by index), one per unit. Empty is none — every unit locates to its default (`LocateValueResolver`).
 */
object StoredLocate {
    private val json = Json {
        explicitNulls = false
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    /** [entries] in canonical order: the fixture's own first, then heads by index; one per unit, the last kept. */
    fun canonical(entries: List<UnitLocate>): List<UnitLocate> =
        entries.associateBy { it.head }.values.sortedWith(compareBy(nullsFirst()) { it.head })

    /** The column's text for [entries], or null for none. */
    fun toText(entries: List<UnitLocate>): String? =
        canonical(entries).takeIf { it.isNotEmpty() }?.let { json.encodeToString(LIST, it) }

    /**
     * The entries a column holds. Tolerant, as every reader of stored JSON here is: text that will not
     * parse reads as no stored locate rather than failing the patch.
     */
    fun fromText(text: String?): List<UnitLocate> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(LIST, text) }.getOrNull()?.let(::canonical).orEmpty()
    }

    /**
     * [element] read as a stored locate — an array of `{head?, pan, tilt, panFine?, tiltFine?}`, null
     * for none — with every problem named at once; [typeKey]'s units checked where it is given: a head
     * index the type has, a unit that pans and tilts, a fine byte only where it has a fine channel.
     */
    fun parse(element: JsonElement?, where: String = "locate", typeKey: String? = null): Result<List<UnitLocate>> {
        if (element == null || element is JsonNull) return Result.success(emptyList())
        val array = element as? JsonArray
            ?: return Result.failure(IllegalArgumentException("$where must be an array of {head?, pan, tilt, panFine?, tiltFine?} or null"))
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<Int?>()
        val out = array.mapIndexedNotNull { i, item ->
            val at = "$where[$i]"
            val obj = item as? JsonObject ?: run { problems += "$at must be an object"; return@mapIndexedNotNull null }
            (obj.keys - FIELDS).takeIf { it.isNotEmpty() }?.let { problems += "$at has unknown field(s) ${it.sorted().joinToString()}" }
            fun byte(name: String, required: Boolean): Int? {
                val e = obj[name]
                if (e == null || e is JsonNull) {
                    if (required) problems += "$at.$name is required"
                    return null
                }
                val v = (e as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                if (v == null || v !in 0..255) {
                    problems += "$at.$name must be a DMX value, 0 to 255"
                    return null
                }
                return v
            }
            val headElement = obj["head"]
            val head = if (headElement == null || headElement is JsonNull) null
            else (headElement as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                ?: run { problems += "$at.head must be a head's index, or null for the fixture's own"; return@mapIndexedNotNull null }
            val pan = byte("pan", required = true)
            val tilt = byte("tilt", required = true)
            val panFine = byte("panFine", required = false)
            val tiltFine = byte("tiltFine", required = false)
            if (!seen.add(head)) problems += "$at names ${unitName(head)} twice"
            if (pan == null || tilt == null) null else UnitLocate(head, pan, tilt, panFine, tiltFine)
        }
        if (problems.isEmpty() && typeKey != null) problems += typeProblems(typeKey, out, where)
        if (problems.isNotEmpty()) return Result.failure(IllegalArgumentException(problems.joinToString("; ")))
        return Result.success(canonical(out))
    }

    /**
     * What [typeKey] cannot hold of [entries], each named by its unit: a unit that does not pan and
     * tilt on the type, a fine byte where the unit has no fine channel. Empty when it holds them all.
     */
    fun typeProblems(typeKey: String, entries: List<UnitLocate>, where: String = "locate"): List<String> {
        val units = unitsOf(typeKey)
        return entries.mapNotNull { entry ->
            val name = unitName(entry.head)
            val unit = units[entry.head]
            when {
                unit == null -> "$where: $name does not pan and tilt on this type"
                entry.panFine != null && !unit.panFine -> "$where: $name has no fine pan channel"
                entry.tiltFine != null && !unit.tiltFine -> "$where: $name has no fine tilt channel"
                else -> null
            }
        }
    }

    /**
     * [entries] as [typeKey] can hold them — a retype, which keeps the patch: each unit the type pans
     * and tilts keeps its entry, less a fine byte it has no channel for, and every other is dropped.
     */
    fun fitTo(typeKey: String, entries: List<UnitLocate>): List<UnitLocate> {
        if (entries.isEmpty()) return entries
        val units = unitsOf(typeKey)
        return entries.mapNotNull { entry ->
            val unit = units[entry.head] ?: return@mapNotNull null
            entry.copy(
                panFine = entry.panFine.takeIf { unit.panFine },
                tiltFine = entry.tiltFine.takeIf { unit.tiltFine },
            )
        }
    }

    /** What a locate unit of a type can hold: whether it has fine pan and tilt channels. */
    data class LocateUnit(val panFine: Boolean, val tiltFine: Boolean)

    /**
     * The units of [typeKey] that can hold a stored locate — the fixture's own (key null) where it
     * pans and tilts, and each head that does — read from the type's property descriptors. Empty for
     * a type that does not move.
     */
    fun unitsOf(typeKey: String): Map<Int?, LocateUnit> = unitsByType.getOrPut(typeKey) {
        val sample = runCatching {
            FixtureTypeRegistry.instantiateByTypeKey(typeKey, Universe(0, 0), "locate-introspection", "Locate introspection", 1)
        }.getOrNull() ?: return@getOrPut emptyMap()
        unitsOf(sample)
    }

    /** [unitsOf] for a built fixture: its own axes and each head's, by [LocateValueResolver.axesOf]. */
    fun unitsOf(fixture: Fixture): Map<Int?, LocateUnit> = buildMap {
        LocateValueResolver.axesOf(fixture)?.let { put(null, LocateUnit(it.panFine != null, it.tiltFine != null)) }
        if (fixture is MultiElementFixture<*>) {
            for (element in fixture.elements) {
                LocateValueResolver.axesOf(element)?.let { put(element.elementIndex, LocateUnit(it.panFine != null, it.tiltFine != null)) }
            }
        }
    }

    private val unitsByType = ConcurrentHashMap<String, Map<Int?, LocateUnit>>()

    private fun unitName(head: Int?) = if (head == null) "the fixture" else "head ${head + 1}"

    private val FIELDS = setOf("head", "pan", "tilt", "panFine", "tiltFine")
    private val LIST = kotlinx.serialization.builtins.ListSerializer(UnitLocate.serializer())
}

/** The patch's stored locate, read through [StoredLocate]'s tolerant decode. */
var DaoFixturePatch.storedLocate: List<UnitLocate>
    get() = StoredLocate.fromText(locate)
    set(entries) {
        locate = StoredLocate.toText(entries)
    }
