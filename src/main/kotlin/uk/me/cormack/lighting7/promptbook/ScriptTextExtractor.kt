package uk.me.cormack.lighting7.promptbook

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitRectangleDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.Writer
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.round

/**
 * Reads a script PDF into a [ScriptTextIndex] with PDFBox. Positions are PDFBox's direction-adjusted
 * ones (top-left origin, inside the crop box) divided by the page as it is displayed, so they land
 * where the viewer draws the book's rects. Lines come in PDFBox's position-sorted reading order.
 *
 * Runs only on [ScriptTextStore]'s own thread: a long script takes seconds.
 */
object ScriptTextExtractor {

    /** How far a line's box reaches below its baseline, as a share of its glyphs' height. */
    private const val DESCENT = 0.25

    private const val MAX_OUTLINE_DEPTH = 32

    fun extract(pdf: Path, scriptHash: String): ScriptTextIndex =
        Loader.loadPDF(pdf.toFile(), IOUtils.createTempFileOnlyStreamCache()).use { extract(it, scriptHash) }

    fun extract(doc: PDDocument, scriptHash: String): ScriptTextIndex {
        val sizes = doc.pages.map { displayedSize(it) }
        val lines = List(sizes.size) { mutableListOf<ScriptTextLine>() }
        LineStripper(sizes, lines).apply {
            sortByPosition = true
            writeText(doc, Writer.nullWriter())
        }
        val pages = lines.mapIndexed { index, pageLines ->
            ScriptTextPage(page = index, scanned = pageLines.size < ScriptTextPage.MIN_TEXT_LINES, lines = pageLines)
        }
        return ScriptTextIndex(
            version = ScriptTextIndex.VERSION,
            scriptHash = scriptHash,
            pageCount = sizes.size,
            pages = pages,
            outline = outlineOf(doc, sizes),
        )
    }

    private data class PageSize(val width: Float, val height: Float, val lowerY: Float, val rotated: Boolean)

    private fun displayedSize(page: PDPage): PageSize {
        val box = page.cropBox
        val rotated = page.rotation % 180 != 0
        return PageSize(
            width = if (rotated) box.height else box.width,
            height = if (rotated) box.width else box.height,
            lowerY = box.lowerLeftY,
            rotated = rotated,
        )
    }

    /** Collects PDFBox's words into lines, flushing at each line separator and at the page's end. */
    private class LineStripper(
        private val sizes: List<PageSize>,
        private val out: List<MutableList<ScriptTextLine>>,
    ) : PDFTextStripper() {
        private val text = StringBuilder()
        /** The left edge of each character of [text]; a word separator's is the previous word's right. */
        private val edges = mutableListOf<Float>()
        private var lastRight = 0f
        private var top = Float.MAX_VALUE
        private var bottom = -Float.MAX_VALUE

        override fun writeString(string: String, textPositions: List<TextPosition>) {
            if (string.isEmpty() || textPositions.isEmpty()) return
            val first = textPositions.first()
            val last = textPositions.last()
            val left = first.xDirAdj
            val right = last.xDirAdj + last.widthDirAdj
            if (string.length == textPositions.size) {
                textPositions.forEach { edges += it.xDirAdj }
            } else {
                // A ligature or a decomposed accent: the string no longer pairs with its glyphs.
                val step = (right - left) / string.length
                repeat(string.length) { edges += left + it * step }
            }
            text.append(string)
            lastRight = right
            for (p in textPositions) {
                top = minOf(top, p.yDirAdj - p.heightDir)
                bottom = maxOf(bottom, p.yDirAdj + p.heightDir * DESCENT.toFloat())
            }
        }

        override fun writeWordSeparator() {
            if (text.isEmpty()) return
            // The space spans the gap: from the last word's right edge to the next word's left.
            text.append(' ')
            edges += lastRight
        }

        override fun writeLineSeparator() = flush()

        override fun writeParagraphStart() {
            flush()
            super.writeParagraphStart()
        }

        override fun endPage(page: PDPage) = flush()

        private fun flush() {
            if (text.isEmpty()) return reset()
            val pageIndex = currentPageNo - 1
            val size = sizes.getOrNull(pageIndex) ?: return reset()
            val raw = text.toString()
            val cx = edges + lastRight
            val (start, end) = raw.indexOfFirst { !it.isWhitespace() } to raw.indexOfLast { !it.isWhitespace() }
            if (start < 0) return reset()
            val lineText = raw.substring(start, end + 1)
            val lineEdges = cx.subList(start, end + 2)
            val w = size.width
            val h = size.height
            val x = (lineEdges.first() / w).frac()
            val y = (top / h).frac()
            out[pageIndex] += ScriptTextLine(
                text = lineText,
                x = x,
                y = y,
                w = ((lineEdges.last() - lineEdges.first()) / w).frac().coerceAtMost(1.0 - x),
                h = ((bottom - top) / h).frac().coerceAtMost(1.0 - y),
                cx = lineEdges.map { (it / w).frac() },
            )
            reset()
        }

        private fun reset() {
            text.clear()
            edges.clear()
            top = Float.MAX_VALUE
            bottom = -Float.MAX_VALUE
        }
    }

    private fun outlineOf(doc: PDDocument, sizes: List<PageSize>): List<ScriptOutlineItem> {
        val root = doc.documentCatalog.documentOutline ?: return emptyList()
        val items = mutableListOf<ScriptOutlineItem>()
        val visited: MutableSet<COSDictionary> = Collections.newSetFromMap(IdentityHashMap())
        fun walk(node: PDOutlineNode, level: Int) {
            if (level > MAX_OUTLINE_DEPTH) return
            for (item in node.children()) {
                // An outline whose child or sibling points back at an item already read is a cycle.
                if (!visited.add(item.cosObject)) return
                val dest = runCatching {
                    val direct = item.destination ?: (item.action as? PDActionGoTo)?.destination
                    if (direct is PDNamedDestination) doc.documentCatalog.findNamedDestinationPage(direct) else direct
                }.getOrNull() as? PDPageDestination
                val page = dest?.let { d -> d.retrievePageNumber().takeIf { it >= 0 } ?: d.pageNumber.takeIf { it >= 0 } }
                val title = item.title?.trim().orEmpty()
                if (page != null && page < sizes.size && title.isNotEmpty()) {
                    val top = when (dest) {
                        is PDPageXYZDestination -> dest.top
                        is PDPageFitWidthDestination -> dest.top
                        is PDPageFitRectangleDestination -> dest.top
                        else -> -1
                    }
                    val size = sizes[page]
                    val y = if (top < 0 || size.rotated) null else (1.0 - (top - size.lowerY) / size.height).frac()
                    items += ScriptOutlineItem(title = title, level = level, page = page, y = y)
                }
                walk(item, level + 1)
            }
        }
        walk(root, 0)
        return items
    }

    /** A fraction of the page, clamped to it and rounded to four places, which is a tenth of a point. */
    private fun Float.frac(): Double = toDouble().frac()

    private fun Double.frac(): Double = round(coerceIn(0.0, 1.0) * 10_000) / 10_000
}
