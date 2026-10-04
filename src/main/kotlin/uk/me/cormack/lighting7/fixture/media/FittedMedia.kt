package uk.me.cormack.lighting7.fixture.media

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.fixture.MediaSlot
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureColourSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureGoboSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSettingValue
import uk.me.cormack.lighting7.fixture.dmx.GoboPattern
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatchPlacement
import uk.me.cormack.lighting7.routes.SettingPropertyDescriptor

/**
 * What is loaded into one slot of a unit: a gel by its library code, or a gobo by its pattern
 * name (lowercase, as `SettingOption.gobo` carries it). **Neither is an empty slot** — fitted, with
 * nothing in it, so the frame or slot is open — which is not the same as no entry, which leaves the
 * type's stock content.
 */
@Serializable
data class FittedSlot(
    val gel: String? = null,
    val gobo: String? = null,
) {
    val isEmpty: Boolean get() = gel == null && gobo == null
}

/**
 * A unit's **fitted media** (fixture optics plan D1, D6; `docs/fixtures-engineering.md` §"Fitted
 * media"): what is loaded in its loadable settings, keyed by the setting's property name and then by
 * the option's name — `{slots: {gelScroller: {L201_FULL_CT_BLUE: {gel: "R26"}}}}`. Only the options
 * the venue changed are named; every other option keeps its stock content (`colourPreview` / `gobo`).
 *
 * It sits on a patch (`fixture_patches.media`) and on each extra placement
 * (`fixture_patch_placements.media`), so two units of one type carry different strings. Physical,
 * portable, and never in a Look: a Look selects a frame, and which gel that frame holds is the unit's.
 */
@Serializable
data class FittedMedia(
    val slots: Map<String, Map<String, FittedSlot>> = emptyMap(),
) {
    val isEmpty: Boolean get() = slots.values.all { it.isEmpty() }

    /** The slot this unit has fitted for [option] of [propertyName], or null for the stock. */
    fun slot(propertyName: String, option: String): FittedSlot? = slots[propertyName]?.get(option)

    /**
     * This unit's media layered over [base]'s, option by option — a placement's over its patch's:
     * an option the placement names is the placement's, every other the patch's.
     */
    fun over(base: FittedMedia?): FittedMedia {
        if (base == null || base.isEmpty) return this
        if (isEmpty) return base
        val names = base.slots.keys + slots.keys
        return FittedMedia(
            names.associateWith { name -> base.slots[name].orEmpty() + slots[name].orEmpty() }.toSortedMap(),
        )
    }

    /**
     * Problems with this media against [typeKey]'s loadable settings — every one at once, so a write
     * is refused for all of them together. Unknown property, a property that is not loadable, an
     * unknown option, an option that is no slot (an open hole), an unknown gel or gobo, a gel where
     * the slot takes none, a gobo where it takes none, both at once. Each is prefixed by its path.
     */
    fun typeProblems(typeKey: String, where: String = "media"): List<String> {
        if (isEmpty) return emptyList()
        val loadable = loadableSettings(typeKey)
        if (loadable.isEmpty()) {
            return listOf("$where: '$typeKey' has no loadable settings, so nothing can be fitted to it")
        }
        val properties = FixtureTypeRegistry.typeInfoForKey(typeKey)?.properties.orEmpty().map { it.name }.toSet()
        return buildList {
            for ((propertyName, options) in slots.toSortedMap()) {
                val at = "$where.slots.$propertyName"
                val setting = loadable[propertyName]
                if (setting == null) {
                    add(
                        if (propertyName in properties) {
                            "$at: $propertyName is not loadable (the type's loadable settings are ${loadable.keys.sorted().joinToString()})"
                        } else {
                            "$at: '$typeKey' has no property $propertyName"
                        },
                    )
                    continue
                }
                val slot = MediaSlot.valueOf(checkNotNull(setting.media))
                val byName = setting.options.associateBy { it.name }
                for ((optionName, content) in options.toSortedMap()) {
                    val atOption = "$at.$optionName"
                    val option = byName[optionName]
                    if (option == null) {
                        add("$atOption: $propertyName has no option $optionName (its options are ${setting.options.joinToString { it.name }})")
                        continue
                    }
                    if (option.loadable != true) {
                        add("$atOption: $optionName is not a slot anything can be loaded into")
                        continue
                    }
                    if (content.gel != null && content.gobo != null) add("$atOption: a slot holds a gel or a gobo, not both")
                    content.gel?.let { code ->
                        if (!slot.takesGel) add("$atOption: $propertyName's slots take gobos, not gels")
                        else if (GelLibrary.byCode(code) == null) add("$atOption: unknown gel '$code' (see GET /gels)")
                    }
                    content.gobo?.let { pattern ->
                        if (!slot.takesGobo) add("$atOption: $propertyName's slots take gels, not gobos")
                        else if (pattern !in GOBO_NAMES) add("$atOption: unknown gobo '$pattern' (the library's are ${GOBO_NAMES.joinToString()})")
                    }
                }
            }
        }
    }

    companion object {
        /** Every gobo pattern a slot may be fitted with, as the wire spells them. */
        val GOBO_NAMES: List<String> = GoboPattern.entries.map { it.serialized() }

        /**
         * [typeKey]'s loadable settings, by property name: its fixture-level setting descriptors
         * that declare `media`. Empty for an unknown type and for every type but a loadable one.
         */
        fun loadableSettings(typeKey: String): Map<String, SettingPropertyDescriptor> =
            FixtureTypeRegistry.typeInfoForKey(typeKey)?.properties.orEmpty()
                .filterIsInstance<SettingPropertyDescriptor>()
                .filter { it.media != null }
                .associateBy { it.name }

        /**
         * The `media` value of a JSON body — absent and null both null (nothing fitted) — with its
         * shape checked, every problem at once. The type's rules ([typeProblems]) need the patch and
         * run where it is known. A wrong JSON type is a problem named, never a 500. A gobo name is
         * read case-insensitively and kept lowercase; an empty `slots`, or one naming only empty
         * maps, is nothing fitted (null).
         */
        fun parse(element: JsonElement?, where: String = "media"): Result<FittedMedia?> {
            if (element == null || element is JsonNull) return Result.success(null)
            val problems = mutableListOf<String>()
            val obj = element as? JsonObject
                ?: return failure(listOf("$where must be an object {slots: {<property>: {<option>: {gel?, gobo?}}}} or null"))
            (obj.keys - "slots").takeIf { it.isNotEmpty() }?.let { problems += "$where has unknown field(s) ${it.sorted().joinToString()}" }
            val slotsElement = obj["slots"]
            val slots = sortedMapOf<String, Map<String, FittedSlot>>()
            if (slotsElement != null && slotsElement !is JsonNull) {
                val byProperty = slotsElement as? JsonObject
                if (byProperty == null) {
                    problems += "$where.slots must be an object keyed by property name"
                } else {
                    for ((propertyName, optionsElement) in byProperty) {
                        val at = "$where.slots.$propertyName"
                        val byOption = optionsElement as? JsonObject
                        if (byOption == null) {
                            problems += "$at must be an object keyed by option name"
                            continue
                        }
                        val options = sortedMapOf<String, FittedSlot>()
                        for ((optionName, contentElement) in byOption) {
                            val atOption = "$at.$optionName"
                            val content = contentElement as? JsonObject
                            if (content == null) {
                                problems += "$atOption must be an object {gel?, gobo?}"
                                continue
                            }
                            (content.keys - setOf("gel", "gobo")).takeIf { it.isNotEmpty() }
                                ?.let { problems += "$atOption has unknown field(s) ${it.sorted().joinToString()}" }
                            fun text(name: String): String? {
                                val e = content[name] ?: return null
                                if (e is JsonNull) return null
                                val p = e as? JsonPrimitive
                                if (p == null || !p.isString) {
                                    problems += "$atOption.$name must be a string"
                                    return null
                                }
                                return p.content.trim().takeIf { it.isNotEmpty() }
                            }
                            options[optionName] = FittedSlot(gel = text("gel"), gobo = text("gobo")?.lowercase())
                        }
                        if (options.isNotEmpty()) slots[propertyName] = options
                    }
                }
            }
            if (problems.isNotEmpty()) return failure(problems)
            return Result.success(FittedMedia(slots).takeUnless { it.isEmpty })
        }

        private fun failure(problems: List<String>): Result<FittedMedia?> =
            Result.failure(IllegalArgumentException(problems.joinToString("; ")))

        private val JSON = Json { encodeDefaults = false }

        /** The column's text for [media]: compact JSON, keys sorted. Null for nothing fitted. */
        fun toText(media: FittedMedia?): String? {
            if (media == null || media.isEmpty) return null
            val sorted = FittedMedia(media.slots.filterValues { it.isNotEmpty() }.mapValues { it.value.toSortedMap() }.toSortedMap())
            return JSON.encodeToString(serializer(), sorted)
        }

        /**
         * The media a column holds. Tolerant, as every reader of stored JSON here is: text that will
         * not parse reads as nothing fitted rather than failing the patch.
         */
        fun fromText(text: String?): FittedMedia? {
            if (text.isNullOrBlank()) return null
            return runCatching { Json.decodeFromString(serializer(), text) }.getOrNull()?.takeUnless { it.isEmpty }
        }
    }
}

/**
 * The colour [option] of [propertyName] draws on a unit fitted with this media — the backend's half
 * of the per-unit resolution (the Stage view's is `lib/fittedMedia.ts`):
 *
 * - a fitted gel is its library colour (a code the library does not hold — an archive from a desk
 *   whose library was newer — keeps the stock preview);
 * - a slot fitted with a gobo carries no colour;
 * - an empty fitted slot is open, `#FFFFFF`;
 * - an option the unit has not fitted keeps its stock `colourPreview`.
 *
 * Null media is a unit with nothing fitted: every option its stock.
 */
fun FittedMedia?.colourOf(propertyName: String, option: DmxFixtureSettingValue): String? {
    val stock = (option as? DmxFixtureColourSettingValue)?.colourPreview
    val fitted = this?.slot(propertyName, option.name) ?: return stock
    fitted.gel?.let { return GelLibrary.byCode(it)?.color ?: stock }
    if (fitted.gobo != null) return null
    return OPEN_WHITE
}

/** The gobo pattern [option] of [propertyName] carries on a unit fitted with this media — the
 *  stock `gobo` where it has fitted nothing, else the fitted pattern (null for a gel or an empty
 *  slot). Lowercase, as the wire carries it. */
fun FittedMedia?.goboOf(propertyName: String, option: DmxFixtureSettingValue): String? {
    val stock = (option as? DmxFixtureGoboSettingValue)?.gobo?.serialized()
    val fitted = this?.slot(propertyName, option.name) ?: return stock
    return fitted.gobo
}

/** An open frame's colour, as the stock string's open frames and Locate's white slot spell it. */
const val OPEN_WHITE = "#FFFFFF"

var DaoFixturePatch.fittedMedia: FittedMedia?
    get() = FittedMedia.fromText(media)
    set(m) {
        media = FittedMedia.toText(m)
    }

var DaoFixturePatchPlacement.fittedMedia: FittedMedia?
    get() = FittedMedia.fromText(media)
    set(m) {
        media = FittedMedia.toText(m)
    }
