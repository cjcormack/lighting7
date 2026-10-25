package uk.me.cormack.lighting7.models

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * A fixture's or group's **colour** (tables-colours plan D7): one of ten named keys that mark which
 * fixture a row, tile or label is — identity, never state. The stored value is the key; each key's
 * light and dark values are the frontend's (`--tag-<key>`). Five hues are reserved because the desk
 * already means something by them, and are never a colour.
 *
 * A write naming anything else is refused (400 `COLOUR_UNKNOWN`). A **stored** key this desk does not
 * know — an older or newer desk's — reads as none ([read]) rather than failing, and sync carries it as
 * stored, as an unknown gel code is. See `docs/fixtures-engineering.md` §"Fixture colours".
 */
object FixtureColours {
    const val CODE_UNKNOWN = "COLOUR_UNKNOWN"

    /** The field a write names a colour by, on every route and tool. */
    const val FIELD = "colour"

    /** Column width of a stored colour; a longer one is never written. */
    const val MAX_LENGTH = 16

    val KEYS: List<String> = listOf("red", "orange", "yellow", "lime", "green", "cyan", "pink", "brown", "white", "grey")

    /** What each reserved hue already means on the desk. */
    val RESERVED: Map<String, String> = linkedMapOf(
        "blue" to "your values",
        "amber" to "a park",
        "violet" to "an FX",
        "sky" to "a cue",
        "teal" to "a Look and the venue",
    )

    private val keySet = KEYS.toSet()

    /**
     * A colour an import stores as it is, known or not, so another desk's key survives the round trip;
     * null for a blank one and for one too long for the column.
     */
    fun storable(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }

    /** [stored] as a key: null for none and for a key this desk does not know. */
    fun read(stored: String?): String? = stored?.takeIf { it in keySet }

    /**
     * A written colour: null clears it, a key (any case, trimmed) sets it, and a blank string is a
     * clear. Fails with a message naming the keys for anything else.
     */
    fun normalise(raw: String?): Result<String?> {
        val key = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return Result.success(null)
        if (key in keySet) return Result.success(key)
        val why = RESERVED[key]?.let { "'$key' is reserved (the desk draws $it in it)" } ?: "'${raw.trim()}' is not a colour"
        return Result.failure(IllegalArgumentException("$why: use one of ${KEYS.joinToString()}"))
    }

    /** A JSON `colour` field as [normalise] reads it; a non-string is refused. */
    fun parse(element: JsonElement?): Result<String?> = when {
        element == null || element is JsonNull -> Result.success(null)
        element is JsonPrimitive && element.isString -> normalise(element.content)
        else -> Result.failure(IllegalArgumentException("colour must be a string: one of ${KEYS.joinToString()}, or null"))
    }

    /**
     * A tool row's `colour` at [where], as [parse] reads it, or null with the refusal added to [problems]
     * in the tools' `CODE: where: why` form.
     */
    fun parseSent(element: JsonElement?, where: String, problems: MutableList<String>): SentColour? =
        parse(element).fold({ SentColour(it) }, { problems += "$CODE_UNKNOWN: $where: ${it.message}"; null })
}

/** A colour a write sent: a key, or null to clear it (on a venue record in a show, to take the venue's back). */
class SentColour(val value: String?)
