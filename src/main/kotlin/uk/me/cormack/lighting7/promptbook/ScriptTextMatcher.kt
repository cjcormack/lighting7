package uk.me.cormack.lighting7.promptbook

import uk.me.cormack.lighting7.models.PromptBookRectDto
import java.text.Normalizer
import java.util.Locale

/**
 * The one match rule (prompt-book navigation plan P1), used by `search_prompt_book` and a quoted `at`,
 * and from session 2 mirrored in TypeScript by the Prompt Book's find: case, accents, quote style and
 * runs of whitespace are ignored, and a hit may run from the end of one line into the next. Held to
 * `src/test/resources/promptBook/textSearch.fixture.json`, which the mirror's tests will read too.
 */
object ScriptTextMatcher {

    /** Where a character of a page's folded text came from: its line, and its index in that line. */
    private class Folded(val text: String, val line: IntArray, val char: IntArray, val charEnd: IntArray)

    /** One place a query was found: its page and each line it touches, as `[startChar, endChar)`. */
    data class Hit(val page: Int, val spans: List<Span>)

    data class Span(val line: Int, val start: Int, val end: Int)

    /** [text] folded as the rule folds it, for a query or a comparison. */
    fun fold(text: String): String = foldLines(listOf(text)).text.trim()

    /**
     * Every non-overlapping hit of [query] in [pages] (each page its lines), in page and reading order.
     * A query that folds to nothing finds nothing.
     */
    fun search(pages: List<List<String>>, query: String, onlyPage: Int? = null): List<Hit> {
        val needle = fold(query)
        if (needle.isEmpty()) return emptyList()
        val hits = mutableListOf<Hit>()
        pages.forEachIndexed { page, lines ->
            if (onlyPage != null && page != onlyPage) return@forEachIndexed
            val folded = foldLines(lines)
            var from = 0
            while (true) {
                val at = folded.text.indexOf(needle, from)
                if (at < 0) break
                hits += Hit(page, spansOf(folded, at, at + needle.length))
                from = at + needle.length
            }
        }
        return hits
    }

    /** [search] over an index's pages. */
    fun search(index: ScriptTextIndex, query: String, onlyPage: Int? = null): List<Hit> =
        search(index.pages.map { p -> p.lines.map { it.text } }, query, onlyPage)

    /** A hit's boxes in page coordinates, one per line it touches, from the index's character edges. */
    fun rects(index: ScriptTextIndex, hit: Hit): List<PromptBookRectDto> {
        val page = index.pages[hit.page]
        return hit.spans.map { span ->
            val line = page.lines[span.line]
            val left = line.cx.getOrElse(span.start) { line.x }
            val right = line.cx.getOrElse(span.end) { line.x + line.w }
            PromptBookRectDto(page = hit.page, x = left, y = line.y, w = (right - left).coerceAtLeast(0.0), h = line.h)
        }
    }

    private fun spansOf(folded: Folded, start: Int, end: Int): List<Span> =
        (start until end)
            // A space standing for a line break belongs to neither line.
            .filter { folded.char[it] >= 0 }
            .groupBy { folded.line[it] }
            .map { (line, at) -> Span(line, folded.char[at.first()], folded.charEnd[at.last()]) }

    /**
     * The page's lines folded into one string, a single space at each line break and for each run of
     * whitespace, with each character traced back to its line and the source characters it came from
     * ([Folded.char] −1 for a break).
     */
    private fun foldLines(lines: List<String>): Folded {
        val out = StringBuilder()
        val lineOf = ArrayList<Int>()
        val charOf = ArrayList<Int>()
        val endOf = ArrayList<Int>()
        fun space(line: Int, char: Int, end: Int) {
            if (out.isEmpty() || out.last() == ' ') return
            out.append(' ')
            lineOf += line
            charOf += char
            endOf += end
        }
        lines.forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) space(lineIndex, -1, -1)
            var i = 0
            while (i < line.length) {
                val cp = line.codePointAt(i)
                val width = Character.charCount(cp)
                val folded = foldCodePoint(cp)
                if (folded == " ") {
                    space(lineIndex, i, i + width)
                } else {
                    for (c in folded) {
                        out.append(c)
                        lineOf += lineIndex
                        charOf += i
                        endOf += i + width
                    }
                }
                i += width
            }
        }
        // A trailing space is never matched by a trimmed needle, so it may stay.
        return Folded(out.toString(), lineOf.toIntArray(), charOf.toIntArray(), endOf.toIntArray())
    }

    /** One code point as the rule reads it: `" "` for whitespace, `""` for a mark it drops. */
    private fun foldCodePoint(cp: Int): String {
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) return " "
        QUOTES[cp]?.let { return it }
        val decomposed = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKD)
        val sb = StringBuilder()
        for (ch in decomposed) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            QUOTES[ch.code]?.let { sb.append(it); continue }
            if (Character.isWhitespace(ch)) { sb.append(' '); continue }
            sb.append(ch.toString().lowercase(Locale.ROOT))
        }
        return sb.toString()
    }

    private val QUOTES: Map<Int, String> = buildMap {
        "‘’‚‛′`´".forEach { put(it.code, "'") }
        "“”„‟″«»".forEach { put(it.code, "\"") }
    }
}
