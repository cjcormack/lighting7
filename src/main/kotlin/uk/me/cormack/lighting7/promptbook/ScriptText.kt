package uk.me.cormack.lighting7.promptbook

import kotlinx.serialization.Serializable

/**
 * The text of a prompt book's script PDF, read by the desk (prompt-book navigation plan D1): every
 * line with its box in the 0–1 page coordinates the book's rects use, whether each page is a scan,
 * and the PDF's own outline. A cache derived from the PDF's bytes, never a record: see
 * `docs/prompt-book-engineering.md` §"The text index".
 */
@Serializable
data class ScriptTextIndex(
    val version: Int,
    val scriptHash: String,
    val pageCount: Int,
    val pages: List<ScriptTextPage>,
    val outline: List<ScriptOutlineItem> = emptyList(),
) {
    val scannedPages: List<Int> get() = pages.filter { it.scanned }.map { it.page }

    /** True when any page carries text; a script that is all scans has none to search. */
    val hasText: Boolean get() = pages.any { !it.scanned }

    companion object {
        /** Moved whenever the extractor's output changes, so an older index is rebuilt. */
        const val VERSION = 1
    }
}

/** One page, counted from 0 as the book's rects count it. */
@Serializable
data class ScriptTextPage(
    val page: Int,
    /** Too little text to be a typed page: fewer than [MIN_TEXT_LINES] lines. */
    val scanned: Boolean,
    val lines: List<ScriptTextLine>,
) {
    companion object {
        /** The viewer's own floor for "this page has real text" (`ScriptViewer`'s `MIN_TEXT_ITEMS`). */
        const val MIN_TEXT_LINES = 5
    }
}

/**
 * One line in reading order: its text and its box as fractions of the page. [cx] holds the left edge
 * of each character of [text] and then the right edge of the last, so `cx.size == text.length + 1` and
 * a hit that starts mid-line can be boxed.
 */
@Serializable
data class ScriptTextLine(
    val text: String,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val cx: List<Double>,
)

/** An entry of the PDF's own outline: its title, depth (0 at the top) and where it points. */
@Serializable
data class ScriptOutlineItem(
    val title: String,
    val level: Int,
    val page: Int,
    val y: Double? = null,
)
