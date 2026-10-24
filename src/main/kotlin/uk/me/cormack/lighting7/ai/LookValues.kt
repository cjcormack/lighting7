package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureAttributeCatalogue
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fx.CueAssignmentResolver.AttributeValue
import uk.me.cormack.lighting7.fx.AttributeChannelWriter
import uk.me.cormack.lighting7.fx.PaletteIntent
import uk.me.cormack.lighting7.fx.PaletteAttribute
import uk.me.cormack.lighting7.fx.PaletteResolver
import uk.me.cormack.lighting7.fx.WhitePolicy
import uk.me.cormack.lighting7.fx.canonicalAttributeName
import uk.me.cormack.lighting7.fx.parsePaletteIntent
import uk.me.cormack.lighting7.models.CueAttributeAssignmentDto
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures

/**
 * `create_look`'s values: intents resolved per head through [PaletteResolver] into the literal
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
internal fun ResolvedLookValues.asCueAssignments(): List<CueAttributeAssignmentDto> =
    rows.mapIndexed { index, row ->
        CueAttributeAssignmentDto(row.targetType, row.elementKey ?: row.targetKey, row.attribute, row.value, sortOrder = index)
    }

internal data class LookValueSkip(val target: String, val attribute: String, val reason: String)

/** What `values[].value` may say, for the schema and the refusals. */
internal const val LOOK_VALUE_GRAMMAR =
    "'pct:0-100' for a level (dimmer, strobe, zoom, focus, iris, frost, or any other single channel — a fan, a pump), " +
        "'#rrggbb' for rgbColour (a wheel head lands on its nearest slot), optionally followed by ';w0-255', ';a0-255' " +
        "and ';uv0-255' for the head's own white, amber and UV emitters (an emitter the head lacks is left out), " +
        "or by ';policy=extract' (the hex's white moved into the white or amber emitter) or ';policy=additive' (driven " +
        "alongside RGB) — without one, RGB alone; a named emitter level wins over the policy's, " +
        "'pos:<pan>,<tilt>' for position (centre-relative DMX, -128 to 127 each, 0 the centre of travel; 'deg:' travel " +
        "degrees is still read), or pan and tilt each on its own with 'pct:' or 'dmx:', " +
        "'dmx:0-255' for a raw channel level (white, amber and uv, or any single channel), 'on' / 'off' for prism, " +
        "or a setting's option name (a gobo, a colour-wheel slot, a macro) as list_fixture_types' attributes name it"

private class Entry(val at: String, val ref: TargetRef, val attribute: String, val raw: String, val value: LookValue)

private class ParsedValue(val attribute: String, val raw: String, val value: LookValue)

internal fun resolveLookValues(fixtures: Fixtures, items: JsonArray): ResolvedLookValues {
    val problems = ArrayList<String>()
    val entries = items.mapIndexedNotNull { index, element -> parseEntry("values[$index]", element, problems) }

    val seen = HashMap<Pair<TargetRef, String>, String>()
    for (entry in entries) {
        seen.putIfAbsent(entry.ref to entry.attribute, entry.at)?.let { first ->
            problems += "${entry.at}: ${entry.ref.key} ${entry.attribute} is already set by $first"
        }
    }
    if (problems.isNotEmpty()) return ResolvedLookValues(emptyList(), emptyList(), problems)

    // A fixture's own value beats its group's in the cook; a group split per head must not
    // shadow it with a fixture row of its own.
    val namedOnFixture = entries.filter { it.ref is TargetRef.Fixture }.mapTo(HashSet()) { it.ref.key to it.attribute }

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
            for ((cell, result) in resolveOnHeadOrCells(fixtures, head, entry.attribute, entry.raw, entry.value)) {
                when (result) {
                    is HeadValue.Landed -> landed[cell] = result.attribute to result.value.serialize()
                    is HeadValue.Missed -> missed += LookValueSkip(cell.targetKey, entry.attribute, result.reason)
                }
            }
        }
        if (landed.isEmpty()) {
            problems += "${entry.at}: no head of ${entry.ref.key} can take ${entry.attribute} '${entry.raw}' — " +
                missed.distinctBy { it.reason }.joinToString("; ") { "${it.target}: ${it.reason}" }
            continue
        }
        skipped += missed

        val distinct = landed.values.toSet()
        // A value that landed on a fixture's cells rather than on the fixture is one row per cell:
        // the cook never fans a fixture or group row out to cells, so a single row would reach none.
        val onCells = landed.keys.any { it !in heads }
        if (distinct.size == 1 && !onCells) {
            val (resolvedName, literal) = distinct.single()
            val head = if (entry.ref is TargetRef.Fixture) landed.keys.single() else null
            rows += row(entry.ref, head, resolvedName, literal, rows.size)
        } else {
            for ((head, resolved) in landed) {
                if (shadowedByOwnValue(entry, head, namedOnFixture)) continue
                rows += row(TargetRef.Fixture(head.targetKey), head, resolved.first, resolved.second, rows.size)
            }
        }
    }
    return ResolvedLookValues(rows, skipped, problems)
}

/**
 * Whether a per-head row [entry] would write for [head] is one another value names more closely —
 * a fixture's own value over its group's, a cell's own over its fixture's (or group's) — and so
 * must not be written beside it.
 */
private fun shadowedByOwnValue(entry: Entry, head: GroupableFixture, namedOnFixture: Set<Pair<String, String>>): Boolean {
    val attribute = entry.attribute
    if (head.targetKey != entry.ref.key && (head.targetKey to attribute) in namedOnFixture) return true
    val parent = (head as? FixtureElement<*>)?.parentFixture?.key
    return entry.ref is TargetRef.Group && parent != null && (parent to attribute) in namedOnFixture
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
    val parsed = parseValue(at, obj, problems) ?: return null
    return Entry(at, ref, parsed.attribute, parsed.raw, parsed.value)
}

/** An entry's `attribute` and `value`, checked against the grammar. */
private fun parseValue(at: String, obj: JsonObject, problems: MutableList<String>): ParsedValue? {
    val attribute = obj["attribute"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?.let(::canonicalAttributeName)
        ?: run { problems += "$at: attribute is required"; return null }
    val raw = obj["value"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: run { problems += "$at: value is required"; return null }
    val value = parseLookValue(attribute, raw)
        ?: run { problems += "$at: '$raw' is not a value $attribute takes — use $LOOK_VALUE_GRAMMAR"; return null }
    return ParsedValue(attribute, raw, value)
}

/** A cell is stored as Record stores one: the parent as the target, the cell as `elementKey`. */
private fun row(ref: TargetRef, head: GroupableFixture?, attribute: String, literal: String, sortOrder: Int): LookRowDto =
    if (head is FixtureElement<*>) {
        LookRowDto(TargetRef.Fixture.TYPE, head.parentFixture.key, attribute, literal, elementKey = head.elementKey, sortOrder = sortOrder)
    } else {
        LookRowDto(ref.discriminator, ref.key, attribute, literal, sortOrder = sortOrder)
    }

private sealed interface LookValue {
    /** [emitters] is a colour's own white, amber and UV, laid over the mix where the head has them. */
    data class Intent(val intent: PaletteIntent, val emitters: Emitters? = null) : LookValue
    data class Option(val name: String) : LookValue
}

/** The `;w` / `;a` / `;uv` levels a colour value named; null for one it did not. */
private data class Emitters(val white: UByte?, val amber: UByte?, val uv: UByte?)

/**
 * A hex only for a colour and `pos:` / `deg:` only for `position`, so [PaletteResolver]'s colour fallback
 * cannot land a hex on some other property. Anything that is not an intent is an option name.
 */
private fun parseLookValue(attribute: String, raw: String): LookValue? {
    val vocabulary = PaletteAttribute.ofOrNull(attribute)
    if (vocabulary == PaletteAttribute.COLOUR) return parseColourValue(raw)
    val intent = parsePaletteIntent(raw)
    return when {
        vocabulary == PaletteAttribute.POSITION -> (intent as? PaletteIntent.Position)?.let { LookValue.Intent(it) }
        intent is PaletteIntent.Colour || intent is PaletteIntent.Position -> null
        intent != null -> LookValue.Intent(intent)
        else -> LookValue.Option(raw)
    }
}

/**
 * `#rrggbb`, then any of `;policy=…`, `;w0-255`, `;a0-255`, `;uv0-255` — the template grammar's
 * colour plus the emitter levels a colour literal carries, as the desk's own colour picker writes
 * them. Each token at most once; anything else refuses the value rather than being dropped.
 */
private fun parseColourValue(raw: String): LookValue? {
    val parts = raw.split(";").map { it.trim() }
    var policy: String? = null
    var white: UByte? = null
    var amber: UByte? = null
    var uv: UByte? = null
    for (token in parts.drop(1)) {
        val lower = token.lowercase()
        fun level(prefix: String): UByte? = lower.removePrefix(prefix).toIntOrNull()?.takeIf { it in 0..255 }?.toUByte()
        when {
            lower.startsWith("policy=") -> {
                if (policy != null) return null
                policy = WhitePolicy.ofToken(lower.removePrefix("policy="))?.token ?: return null
            }
            lower.startsWith("uv") -> { if (uv != null) return null; uv = level("uv") ?: return null }
            lower.startsWith("w") -> { if (white != null) return null; white = level("w") ?: return null }
            lower.startsWith("a") -> { if (amber != null) return null; amber = level("a") ?: return null }
            else -> return null
        }
    }
    val hex = parts.first() + (policy?.let { ";policy=$it" } ?: "")
    val intent = parsePaletteIntent(hex) as? PaletteIntent.Colour ?: return null
    val emitters = if (white == null && amber == null && uv == null) null else Emitters(white, amber, uv)
    return LookValue.Intent(intent, emitters)
}

/**
 * [value] with [emitters] laid over it, each only where [head] bundles that emitter with its colour
 * (`bundleWithColour`, the class catalogue's answer, as the colour write itself asks) — elsewhere a
 * colour's W/A/UV reaches no channel, so it is left out rather than stored.
 */
private fun withEmitters(head: GroupableFixture, value: AttributeValue, emitters: Emitters?): AttributeValue {
    if (emitters == null || value !is AttributeValue.Colour) return value
    val bundled = FixtureAttributeCatalogue.of(head::class).bundledByCategory
    val mix = value.value
    return AttributeValue.Colour(
        mix.copy(
            white = emitters.white?.takeIf { AttributeCategory.WHITE in bundled } ?: mix.white,
            amber = emitters.amber?.takeIf { AttributeCategory.AMBER in bundled } ?: mix.amber,
            uv = emitters.uv?.takeIf { AttributeCategory.UV in bundled } ?: mix.uv,
        ),
    )
}

/**
 * [head]'s value — or, where [head] is a multi-head fixture that cannot take it itself, each of its
 * cells'. The 24-channel Shehds has no colour of its own (its three zones each have one), so
 * `rgbColour` on the fixture lands on every zone, as a selection of the whole fixture does in the
 * programmer. A parent that takes the value keeps it; a fixture none of whose cells can take it
 * reports its own miss.
 */
private fun resolveOnHeadOrCells(
    fixtures: Fixtures,
    head: GroupableFixture,
    attribute: String,
    raw: String,
    value: LookValue,
): List<Pair<GroupableFixture, HeadValue>> {
    val own = resolveOnHead(fixtures, head, attribute, raw, value)
    if (own is HeadValue.Landed || head !is MultiElementFixture<*> || head.elements.isEmpty()) return listOf(head to own)
    val cells = head.elements.map { cell -> cell to resolveOnHead(fixtures, cell, attribute, raw, value) }
    return if (cells.any { it.second is HeadValue.Landed }) cells else listOf(head to own)
}

private sealed interface HeadValue {
    data class Landed(val attribute: String, val value: AttributeValue) : HeadValue
    data class Missed(val reason: String) : HeadValue
}

private fun resolveOnHead(fixtures: Fixtures, head: GroupableFixture, attribute: String, raw: String, value: LookValue): HeadValue {
    val backing = AttributeChannelWriter.resolveAttribute(head, attribute)?.value
    val setting = backing as? DmxFixtureSetting<*>
    // An option literally named On or Off is that option, not the first or last slot.
    val named = setting?.sortedValues?.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    if (named != null) return HeadValue.Landed(attribute, AttributeValue.Setting(named.level))

    return when (value) {
        is LookValue.Option -> HeadValue.Missed(
            when {
                backing == null -> "no $attribute"
                setting == null -> "$attribute has no named options"
                else -> "$attribute has no option '${value.name}' (it has ${setting.sortedValues.joinToString { it.name }})"
            },
        )
        is LookValue.Intent -> {
            val intent = value.intent
            if (intent is PaletteIntent.Level && setting != null) {
                return HeadValue.Landed(attribute, AttributeValue.Setting(intent.value.toUByte()))
            }
            val owner = (head as? FixtureElement<*>)?.parentFixture ?: head as? Fixture
            val resolution = PaletteResolver.resolve(head, attribute, intent, owner?.let { fixtures.fittedMediaFor(it.key) })
            val resolved = resolution.value
                ?: return HeadValue.Missed((resolution.note as? PaletteResolver.Note.Unsupported)?.reason ?: "unsupported")
            HeadValue.Landed(resolution.attribute, withEmitters(head, resolved, value.emitters))
        }
    }
}

/**
 * `set_programmer`'s values: the same grammar and per-head resolution as [resolveLookValues], but
 * every value goes to every head of every target and comes back as the head's own value rather
 * than as Look rows, for the programmer to hold.
 */
internal class ResolvedProgrammerValues(
    val writes: List<ProgrammerValueWrite>,
    /** Heads a value could not land on, while some head of the selection did. */
    val skipped: List<LookValueSkip>,
    /** Refusals; any one writes nothing. */
    val problems: List<String>,
)

/** One head's value; [requestedName] is the name the caller used, [attribute] the one it landed on. */
internal class ProgrammerValueWrite(
    val head: GroupableFixture,
    val requestedName: String,
    val attribute: String,
    val value: AttributeValue,
)

internal fun resolveProgrammerValues(fixtures: Fixtures, targets: List<TargetRef>, items: JsonArray): ResolvedProgrammerValues {
    val problems = ArrayList<String>()
    if (targets.isEmpty()) problems += "targets must name at least one fixture or group"
    if (items.isEmpty()) problems += "values must hold at least one {attribute, value}"

    val heads = LinkedHashMap<String, GroupableFixture>()
    for (ref in targets) {
        val found: List<GroupableFixture>? = when (ref) {
            is TargetRef.Fixture -> runCatching { listOf(fixtures.untypedGroupableFixture(ref.key)) }.getOrNull()
            is TargetRef.Group -> runCatching { fixtures.untypedGroup(ref.key).fixtures }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
        if (found == null) {
            problems += if (ref is TargetRef.Fixture) "no fixture '${ref.key}' is patched" else "no group '${ref.key}' with members"
            continue
        }
        for (head in found) heads.putIfAbsent(head.targetKey, head)
    }

    val entries = items.mapIndexedNotNull { index, element ->
        val at = "values[$index]"
        val obj = element as? JsonObject ?: run { problems += "$at must be an object"; return@mapIndexedNotNull null }
        parseValue(at, obj, problems)?.let { at to it }
    }
    val seen = HashMap<String, String>()
    for ((at, parsed) in entries) {
        seen.putIfAbsent(parsed.attribute, at)?.let { first ->
            problems += "$at: ${parsed.attribute} is already set by $first"
        }
    }
    if (problems.isNotEmpty()) return ResolvedProgrammerValues(emptyList(), emptyList(), problems)

    val writes = ArrayList<ProgrammerValueWrite>()
    val skipped = ArrayList<LookValueSkip>()
    for ((at, entry) in entries) {
        val missed = ArrayList<LookValueSkip>()
        var landed = 0
        for (head in heads.values) {
            for ((cell, result) in resolveOnHeadOrCells(fixtures, head, entry.attribute, entry.raw, entry.value)) {
                when (result) {
                    is HeadValue.Landed -> {
                        writes += ProgrammerValueWrite(cell, entry.attribute, result.attribute, result.value)
                        landed++
                    }
                    is HeadValue.Missed -> missed += LookValueSkip(cell.targetKey, entry.attribute, result.reason)
                }
            }
        }
        if (landed == 0) {
            problems += "$at: no selected head can take ${entry.attribute} '${entry.raw}' — " +
                missed.distinctBy { it.reason }.joinToString("; ") { "${it.target}: ${it.reason}" }
        }
        skipped += missed
    }
    if (problems.isNotEmpty()) return ResolvedProgrammerValues(emptyList(), emptyList(), problems)
    return ResolvedProgrammerValues(writes, skipped, problems)
}
