package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import uk.me.cormack.lighting7.models.PromptBookRectDto
import uk.me.cormack.lighting7.models.checkPromptBookRegion
import uk.me.cormack.lighting7.promptbook.ScriptTextIndex
import uk.me.cormack.lighting7.promptbook.ScriptTextMatcher
import uk.me.cormack.lighting7.promptbook.ScriptTextState
import kotlin.math.round

/**
 * A tool's `at` read into the book's rects (prompt-book navigation plan D6, P4): either a quote
 * (`text`, with an optional `pdfPage` and `occurrence`) the desk finds in the script's text, or
 * `pdfPage` and `y` meaning what they always have, a width or height running off the page clamped to
 * it. [text] is asked only for a quote, at most once.
 */
internal class ScriptPlaces(private val pageCount: Int, text: () -> ScriptTextState) {

    private val textState by lazy(text)

    /** [element] as the rects it marks, or null after adding its problems under [where]. */
    fun parse(element: JsonElement?, where: String, problems: MutableList<String>): List<PromptBookRectDto>? {
        val at = element as? JsonObject ?: run { problems += "$where: 'at' must be an object"; return null }
        val quote = at["text"]?.takeUnless { it is JsonNull }
        return if (quote != null) quoted(at, quote, where, problems) else placed(at, where, problems)?.let(::listOf)
    }

    private fun placed(at: JsonObject, where: String, problems: MutableList<String>): PromptBookRectDto? {
        val page = at.int("pdfPage")
        val y = at.double("y")
        if (page == null || page !in 1..pageCount) {
            problems += "$where: pdfPage must be 1–$pageCount (the PDF's own page order)"
            return null
        }
        if (y == null || !y.isFinite() || y < 0.0 || y >= 1.0) {
            problems += "$where: give text quoted from the script, or y at least 0 and below 1"
            return null
        }
        val x = at.double("x") ?: DEFAULT_X
        if (!x.isFinite() || x < 0.0 || x >= 1.0) {
            problems += "$where: x must be at least 0 and below 1"
            return null
        }
        val w = (at.double("width") ?: DEFAULT_WIDTH).coerceAtMost(1.0 - x)
        val h = (at.double("height") ?: DEFAULT_HEIGHT).coerceAtMost(1.0 - y)
        val rect = PromptBookRectDto(page = page - 1, x = x, y = y, w = w, h = h)
        checkPromptBookRegion(listOf(rect), pageCount)?.let { problems += "$where: $it"; return null }
        return rect
    }

    private fun quoted(at: JsonObject, quote: JsonElement, where: String, problems: MutableList<String>): List<PromptBookRectDto>? {
        val before = problems.size
        val text = (quote as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (text == null || ScriptTextMatcher.fold(text).isEmpty()) problems += "$where: text must be words quoted from the script"
        val stray = PLACE_FIELDS.filter { at[it] != null && at[it] !is JsonNull }
        if (stray.isNotEmpty()) problems += "$where: give text, or pdfPage and y — not both (drop ${stray.joinToString()})"
        val page = at["pdfPage"]?.takeUnless { it is JsonNull }?.let {
            at.int("pdfPage")?.takeIf { p -> p in 1..pageCount } ?: run {
                problems += "$where: pdfPage must be 1–$pageCount (the PDF's own page order)"
                null
            }
        }
        val occurrence = at["occurrence"]?.takeUnless { it is JsonNull }?.let {
            at.int("occurrence")?.takeIf { n -> n >= 1 } ?: run {
                problems += "$where: occurrence must be 1 or more"
                null
            }
        }
        if (problems.size > before || text == null) return null

        val index = when (val state = textState) {
            is ScriptTextState.Ready -> state.index
            ScriptTextState.Building -> {
                problems += "$where: the desk is still reading the script's text, so '${short(text)}' cannot be found yet — " +
                    "nothing was written; try again shortly"
                return null
            }
            ScriptTextState.ScriptMissing -> {
                problems += "$where: the script PDF is not on this desk, so its text cannot be searched — give pdfPage and y instead"
                return null
            }
            is ScriptTextState.Unreadable -> {
                problems += "$where: the desk could not read the script's text (${state.reason}) — give pdfPage and y instead"
                return null
            }
        }
        val hits = ScriptTextMatcher.search(index, text, page?.minus(1)).filter { it.page < pageCount }
        val onPage = page?.let { " on pdfPage $it" }.orEmpty()
        if (hits.isEmpty()) {
            val scanned = index.scannedPages.filter { it < pageCount && (page == null || it == page - 1) }
            problems += "$where: '${short(text)}' is not in the script's text$onPage" +
                if (scanned.isEmpty()) "" else
                    " (${scanned.size} page${if (scanned.size == 1) " has" else "s have"} little or no text — likely scans — so the quote may be there unread: pdfPage ${pageList(scanned)})"
            return null
        }
        val hit = when {
            occurrence != null && occurrence > hits.size -> {
                problems += "$where: '${short(text)}' is found ${times(hits.size)}$onPage, so there is no occurrence $occurrence"
                return null
            }
            occurrence != null -> hits[occurrence - 1]
            hits.size > 1 -> {
                problems += "$where: '${short(text)}' is found ${times(hits.size)}$onPage — " +
                    hits.take(MAX_CANDIDATES).joinToString("; ") { candidate(index, it) } +
                    (if (hits.size > MAX_CANDIDATES) "; …" else "") +
                    " — give ${if (page == null) "pdfPage or " else ""}occurrence, or quote more of the line"
                return null
            }
            else -> hits.single()
        }
        val rects = ScriptTextMatcher.rects(index, hit).map { r ->
            val w = r.w.coerceAtLeast(MIN_SIZE).coerceAtMost(1.0 - r.x)
            val h = r.h.coerceAtLeast(MIN_SIZE).coerceAtMost(1.0 - r.y)
            r.copy(w = w, h = h)
        }
        checkPromptBookRegion(rects, pageCount)?.let { problems += "$where: $it"; return null }
        return rects
    }

    private fun candidate(index: ScriptTextIndex, hit: ScriptTextMatcher.Hit): String {
        val line = index.pages[hit.page].lines[hit.spans.first().line]
        return "pdfPage ${hit.page + 1} y ${round(line.y * 1000) / 1000} \"${short(line.text)}\""
    }

    companion object {
        const val DEFAULT_X = 0.06
        const val DEFAULT_WIDTH = 0.88
        const val DEFAULT_HEIGHT = 0.03
        private const val MAX_CANDIDATES = 8
        private const val MIN_SIZE = 0.001
        private val PLACE_FIELDS = listOf("y", "x", "width", "height")

        /** Whether any row of [rows] places itself by a quote, under [key] (`at`). */
        fun anyQuoted(rows: JsonElement?, key: String = "at"): Boolean =
            (rows as? JsonArray).orEmpty().any { ((it as? JsonObject)?.get(key) as? JsonObject)?.get("text").let { t -> t != null && t !is JsonNull } }

        private fun short(text: String): String = text.trim().replace(Regex("\\s+"), " ").let { if (it.length > 60) it.take(57) + "…" else it }

        private fun times(n: Int) = if (n == 1) "once" else "$n times"

        private fun pageList(pages: List<Int>): String =
            pages.take(12).joinToString { (it + 1).toString() } + if (pages.size > 12) ", …" else ""
    }
}
