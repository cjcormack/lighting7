package uk.me.cormack.lighting7.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.PaletteAttribute
import uk.me.cormack.lighting7.fx.PaletteIntent
import java.math.BigDecimal

/**
 * The value pop-ups' **recent values** (programmer-fixes plan D14–D16): per project, a list of the
 * last values each editor committed, newest first, at most [CAP], no repeats. Runtime only — no
 * table, no sync — kept for every project while the desk runs, so a show switched away from and back
 * keeps its lists, and cleared by a restart. Shared by every window and device on the desk.
 *
 * The desk resolves nothing: a value is stored as the editor spelled it, once [RecentValues.normalise]
 * has checked its key and value against the vocabulary and put it in canonical form. Anything else is
 * dropped and logged, so a client cannot grow the map.
 */
class RecentValuesService {
    private val lists = MutableStateFlow<Map<Int, Map<String, List<String>>>>(emptyMap())

    /** Every project's lists. */
    val state: StateFlow<Map<Int, Map<String, List<String>>>> = lists.asStateFlow()

    /** One project's lists, by key. */
    fun listsFor(projectId: Int): Map<String, List<String>> = lists.value[projectId].orEmpty()

    /**
     * Put [value] at the front of [key]'s list in [projectId], moving it there if it is already
     * listed. [isRigLevel] answers whether a `level:` key's attribute is a level some patched
     * fixture has. Answers false, recording nothing, for a key or value outside the vocabulary.
     */
    fun record(projectId: Int, key: String, value: String, isRigLevel: (String) -> Boolean): Boolean {
        val canonical = RecentValues.normalise(key, value, isRigLevel)
        if (canonical == null) {
            logger.debug("recentValues.record dropped: {} = {}", key.take(MAX_LOGGED), value.take(MAX_LOGGED))
            return false
        }
        lists.update { all ->
            val project = all[projectId].orEmpty()
            val list = project[key].orEmpty()
            if (list.firstOrNull() == canonical) return@update all
            val next = (listOf(canonical) + list.filter { it != canonical }).take(CAP)
            all + (projectId to (project + (key to next)))
        }
        return true
    }

    companion object {
        /** The longest a list grows. */
        const val CAP = 12
        private const val MAX_LOGGED = 80
        private val logger = LoggerFactory.getLogger(RecentValuesService::class.java)
    }
}

/**
 * The vocabulary of recent-value keys and each key's value spelling (D15):
 *
 * - `colour`: `#rrggbb` then a `;w` / `;a` / `;uv` tag per emitter the editor wrote, zero included.
 * - `level:<attribute>`: `pct:<0–100>`, a share of 255; the attribute a level some patched fixture has.
 * - `palette-level:<attribute>`: `pct:<0–100>`, a share of each head's range; a palette attribute
 *   whose intent is a percent.
 * - `position`: `pos:<pan>,<tilt>`, each centre-relative, −128..127.
 * - `warmth`: `<kelvin>K;<0–100>`.
 */
object RecentValues {
    const val COLOUR = "colour"
    const val POSITION = "position"
    const val WARMTH = "warmth"
    const val LEVEL_PREFIX = "level:"
    const val PALETTE_LEVEL_PREFIX = "palette-level:"

    private const val MAX_LENGTH = 64
    private val HEX = Regex("^#[0-9a-f]{6}$")
    private val EMITTER_TAG = Regex("^(w|a|uv)(\\d{1,3})$")
    private val PERCENT = Regex("^pct:(\\d{1,3}(?:\\.\\d{1,2})?)$")
    private val POSITION_VALUE = Regex("^pos:(-?\\d{1,3}),(-?\\d{1,3})$")
    private val WARMTH_VALUE = Regex("^(\\d{3,5})K;(\\d{1,3}(?:\\.\\d{1,2})?)$")
    private val EMITTER_ORDER = listOf("w", "a", "uv")
    private val PALETTE_LEVELS = PaletteAttribute.entries
        .filter { it.accepts(PaletteIntent.Percent(0.0)) }
        .map { it.attribute }
        .toSet()

    /** [value] in canonical form, or null when the key or the value is outside the vocabulary. */
    fun normalise(key: String, value: String, isRigLevel: (String) -> Boolean): String? {
        if (key.length > MAX_LENGTH || value.length > MAX_LENGTH) return null
        return when {
            key == COLOUR -> colour(value)
            key == POSITION -> position(value)
            key == WARMTH -> warmth(value)
            key.startsWith(LEVEL_PREFIX) -> key.removePrefix(LEVEL_PREFIX)
                .takeIf { it.isNotEmpty() && isRigLevel(it) }
                ?.let { percent(value) }
            key.startsWith(PALETTE_LEVEL_PREFIX) -> key.removePrefix(PALETTE_LEVEL_PREFIX)
                .takeIf { it in PALETTE_LEVELS }
                ?.let { percent(value) }
            else -> null
        }
    }

    private fun colour(value: String): String? {
        val parts = value.lowercase().split(';')
        if (!HEX.matches(parts.first())) return null
        val emitters = mutableMapOf<String, Int>()
        for (part in parts.drop(1)) {
            val match = EMITTER_TAG.matchEntire(part) ?: return null
            val (name, level) = match.destructured
            val byte = level.toInt()
            if (byte > 255 || emitters.put(name, byte) != null) return null
        }
        return (listOf(parts.first()) + EMITTER_ORDER.mapNotNull { name -> emitters[name]?.let { "$name$it" } }).joinToString(";")
    }

    private fun percent(value: String): String? =
        PERCENT.matchEntire(value)?.groupValues?.get(1)?.let(::share)?.let { "pct:$it" }

    private fun position(value: String): String? {
        val (pan, tilt) = POSITION_VALUE.matchEntire(value)?.destructured ?: return null
        val axes = listOf(pan.toInt(), tilt.toInt())
        if (axes.any { it !in -128..127 }) return null
        return "pos:${axes[0]},${axes[1]}"
    }

    private fun warmth(value: String): String? {
        val (kelvin, level) = WARMTH_VALUE.matchEntire(value)?.destructured ?: return null
        val k = kelvin.toInt()
        if (k !in 1000..20000) return null
        return share(level)?.let { "${k}K;$it" }
    }

    /** A 0–100 share in its shortest spelling (`50.0` is `50`), or null outside the range. */
    private fun share(text: String): String? {
        val number = text.toBigDecimal()
        if (number > BigDecimal(100)) return null
        return number.stripTrailingZeros().toPlainString()
    }
}
