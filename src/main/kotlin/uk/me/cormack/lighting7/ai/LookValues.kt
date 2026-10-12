package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fx.CueAssignmentResolver.PropertyValue
import uk.me.cormack.lighting7.fx.PropertyChannelWriter
import uk.me.cormack.lighting7.fx.TemplateIntent
import uk.me.cormack.lighting7.fx.TemplateProperty
import uk.me.cormack.lighting7.fx.TemplateResolver
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.fx.parseTemplateIntent
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures

/**
 * `create_look`'s values: intents resolved per head through [TemplateResolver] into the literal
 * rows a Look stores. A group keeps one row while its members agree, else one row per head.
 */
internal class ResolvedLookValues(
    val rows: List<LookRowDto>,
    /** Heads a value could not land on, while some head of its target did. */
    val skipped: List<LookValueSkip>,
    /** Refusals; any one writes nothing. */
    val problems: List<String>,
)

/**
 * The same rows as a cue's own values (`create_cue` / `build_cue_stack`), which address a cell by
 * its own key, as Record writes one.
 */
internal fun ResolvedLookValues.asCueAssignments(): List<CuePropertyAssignmentDto> =
    rows.mapIndexed { index, row ->
        CuePropertyAssignmentDto(row.targetType, row.elementKey ?: row.targetKey, row.propertyName, row.value, sortOrder = index)
    }

internal data class LookValueSkip(val target: String, val propertyName: String, val reason: String)

/** What `values[].value` may say, for the schema and the refusals. */
internal const val LOOK_VALUE_GRAMMAR =
    "'pct:0-100' for a level (dimmer, strobe, zoom, focus, iris, frost, or any other single channel — a fan, a pump), " +
        "'#rrggbb' for rgbColour (a wheel head lands on its nearest slot), 'deg:<pan>,<tilt>' for position, " +
        "'dmx:0-255' for a raw channel level (white, amber and uv, or any single channel), 'on' / 'off' for prism, " +
        "or a setting's option name (a gobo, a colour-wheel slot, a macro) as list_fixture_types' properties name it"

private class Entry(val at: String, val ref: TargetRef, val propertyName: String, val raw: String, val value: LookValue)

internal fun resolveLookValues(fixtures: Fixtures, items: JsonArray): ResolvedLookValues {
    val problems = ArrayList<String>()
    val entries = items.mapIndexedNotNull { index, element -> parseEntry("values[$index]", element, problems) }

    val seen = HashMap<Pair<TargetRef, String>, String>()
    for (entry in entries) {
        seen.putIfAbsent(entry.ref to entry.propertyName, entry.at)?.let { first ->
            problems += "${entry.at}: ${entry.ref.key} ${entry.propertyName} is already set by $first"
        }
    }
    if (problems.isNotEmpty()) return ResolvedLookValues(emptyList(), emptyList(), problems)

    // A fixture's own value beats its group's in the cook; a group split per head must not
    // shadow it with a fixture row of its own.
    val namedOnFixture = entries.filter { it.ref is TargetRef.Fixture }.mapTo(HashSet()) { it.ref.key to it.propertyName }

    val rows = ArrayList<LookRowDto>()
    val skipped = ArrayList<LookValueSkip>()
    for (entry in entries) {
        val heads: List<GroupableFixture> = when (val ref = entry.ref) {
            is TargetRef.Fixture -> runCatching { fixtures.untypedGroupableFixture(ref.key) }.getOrNull()
                ?.let { listOf(it) }
                ?: run { problems += "${entry.at}: no fixture '${ref.key}' is patched"; continue }
            is TargetRef.Group -> runCatching { fixtures.untypedGroup(ref.key).fixtures }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: run { problems += "${entry.at}: no group '${ref.key}' with members"; continue }
        }

        val landed = LinkedHashMap<GroupableFixture, Pair<String, String>>()
        val missed = ArrayList<LookValueSkip>()
        for (head in heads) {
            when (val result = resolveOnHead(fixtures, head, entry)) {
                is HeadValue.Landed -> landed[head] = result.propertyName to result.literal
                is HeadValue.Missed -> missed += LookValueSkip(head.targetKey, entry.propertyName, result.reason)
            }
        }
        if (landed.isEmpty()) {
            problems += "${entry.at}: no head of ${entry.ref.key} can take ${entry.propertyName} '${entry.raw}' — " +
                missed.distinctBy { it.reason }.joinToString("; ") { "${it.target}: ${it.reason}" }
            continue
        }
        skipped += missed

        val distinct = landed.values.toSet()
        if (distinct.size == 1) {
            val (resolvedName, literal) = distinct.single()
            val head = if (entry.ref is TargetRef.Fixture) landed.keys.single() else null
            rows += row(entry.ref, head, resolvedName, literal, rows.size)
        } else {
            for ((head, resolved) in landed) {
                if ((head.targetKey to entry.propertyName) in namedOnFixture) continue
                rows += row(TargetRef.Fixture(head.targetKey), head, resolved.first, resolved.second, rows.size)
            }
        }
    }
    return ResolvedLookValues(rows, skipped, problems)
}

private fun parseEntry(at: String, element: Any, problems: MutableList<String>): Entry? {
    val obj = element as? JsonObject ?: run { problems += "$at must be an object"; return null }
    val targetObj = obj["target"] as? JsonObject
    val type = targetObj?.get("type")?.jsonPrimitive?.contentOrNull
    val key = targetObj?.get("key")?.jsonPrimitive?.contentOrNull
    val ref = if (type != null && key != null) TargetRef.ofOrNull(type, key) else null
    if (ref == null) {
        problems += "$at: target must be {type: 'fixture' | 'group', key}"
        return null
    }
    val propertyName = obj["propertyName"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?.let(::canonicalPropertyName)
        ?: run { problems += "$at: propertyName is required"; return null }
    val raw = obj["value"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: run { problems += "$at: value is required"; return null }
    val value = parseLookValue(propertyName, raw)
        ?: run { problems += "$at: '$raw' is not a value $propertyName takes — use $LOOK_VALUE_GRAMMAR"; return null }
    return Entry(at, ref, propertyName, raw, value)
}

/** A cell is stored as Record stores one: the parent as the target, the cell as `elementKey`. */
private fun row(ref: TargetRef, head: GroupableFixture?, propertyName: String, literal: String, sortOrder: Int): LookRowDto =
    if (head is FixtureElement<*>) {
        LookRowDto(TargetRef.Fixture.TYPE, head.parentFixture.key, propertyName, literal, elementKey = head.elementKey, sortOrder = sortOrder)
    } else {
        LookRowDto(ref.discriminator, ref.key, propertyName, literal, sortOrder = sortOrder)
    }

private sealed interface LookValue {
    data class Intent(val intent: TemplateIntent) : LookValue
    data class Option(val name: String) : LookValue
}

/**
 * A hex only for a colour and `deg:` only for `position`, so [TemplateResolver]'s colour fallback
 * cannot land a hex on some other property. Anything that is not an intent is an option name.
 */
private fun parseLookValue(propertyName: String, raw: String): LookValue? {
    val vocabulary = TemplateProperty.ofOrNull(propertyName)
    val intent = parseTemplateIntent(raw)
    return when {
        vocabulary == TemplateProperty.COLOUR -> (intent as? TemplateIntent.Colour)?.let { LookValue.Intent(it) }
        vocabulary == TemplateProperty.POSITION -> (intent as? TemplateIntent.Position)?.let { LookValue.Intent(it) }
        intent is TemplateIntent.Colour || intent is TemplateIntent.Position -> null
        intent != null -> LookValue.Intent(intent)
        else -> LookValue.Option(raw)
    }
}

private sealed interface HeadValue {
    data class Landed(val propertyName: String, val literal: String) : HeadValue
    data class Missed(val reason: String) : HeadValue
}

private fun resolveOnHead(fixtures: Fixtures, head: GroupableFixture, entry: Entry): HeadValue {
    val propertyName = entry.propertyName
    val backing = PropertyChannelWriter.resolveProperty(head, propertyName)?.value
    val setting = backing as? DmxFixtureSetting<*>
    // An option literally named On or Off is that option, not the first or last slot.
    val named = setting?.sortedValues?.firstOrNull { it.name.equals(entry.raw, ignoreCase = true) }
    if (named != null) return HeadValue.Landed(propertyName, PropertyValue.Setting(named.level).serialize())

    return when (val value = entry.value) {
        is LookValue.Option -> HeadValue.Missed(
            when {
                backing == null -> "no $propertyName"
                setting == null -> "$propertyName has no named options"
                else -> "$propertyName has no option '${value.name}' (it has ${setting.sortedValues.joinToString { it.name }})"
            },
        )
        is LookValue.Intent -> {
            val intent = value.intent
            if (intent is TemplateIntent.Level && setting != null) {
                return HeadValue.Landed(propertyName, PropertyValue.Setting(intent.value.toUByte()).serialize())
            }
            val owner = (head as? FixtureElement<*>)?.parentFixture ?: head as? Fixture
            val resolution = TemplateResolver.resolve(head, propertyName, intent, owner?.let { fixtures.fittedMediaFor(it.key) })
            val resolved = resolution.value
                ?: return HeadValue.Missed((resolution.note as? TemplateResolver.Note.Unsupported)?.reason ?: "unsupported")
            HeadValue.Landed(resolution.propertyName, resolved.serialize())
        }
    }
}
