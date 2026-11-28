package uk.me.cormack.lighting7.promptbook

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Over two PDFs under `src/test/resources/promptBook/`, written by hand so their text sits at known
 * places: `typed-script.pdf`, three US-letter pages (612 × 792 pt) of 12 pt Helvetica lines starting
 * at x = 72 pt, 20 pt apart, page 1's first baseline at 720 pt, page 2's at 500 pt, with an outline
 * (Act One → page 1 at 732 pt; Scene 2 → page 2 at 512 pt; Scene 3 → page 3 at 732 pt); and
 * `scanned-script.pdf`, two pages that are only an image.
 */
class ScriptTextExtractorTest {

    private fun resource(name: String): Path =
        Paths.get(checkNotNull(javaClass.getResource("/promptBook/$name")) { "$name is missing" }.toURI())

    private val typed by lazy { ScriptTextExtractor.extract(resource("typed-script.pdf"), "a".repeat(64)) }

    @Test
    fun `lines come out in reading order with the page's text`() {
        assertEquals(3, typed.pageCount)
        assertEquals(ScriptTextIndex.VERSION, typed.version)
        val first = typed.pages[0].lines.map { it.text }
        assertEquals(
            listOf(
                "ACT ONE", "Scene 1. A platform before the castle.", "BERNARDO: Who's there?",
                "FRANCISCO: Nay, answer me. Stand and unfold yourself.", "BERNARDO: Long live the king!",
                "FRANCISCO: You come most carefully upon your hour.",
            ),
            first,
        )
        assertEquals("question. Whether ’tis nobler in the mind", typed.pages[1].lines[2].text)
        assertEquals("to suffer the slings and arrows. The café is shut.", typed.pages[1].lines[3].text)
        assertFalse(typed.pages.any { it.scanned })
        assertTrue(typed.hasText)
    }

    @Test
    fun `each line's box is where the page draws it`() {
        val tolerance = 0.01
        typed.pages[1].lines.forEachIndexed { i, line ->
            val baselineFromTop = (792.0 - (500 - i * 20)) / 792
            assertEquals(72.0 / 612, line.x, 0.002, "x of line $i")
            assertTrue(line.y < baselineFromTop && line.y > baselineFromTop - 12.0 / 792 - tolerance, "line $i's top ${line.y} sits just above its baseline $baselineFromTop")
            assertTrue(line.y + line.h > baselineFromTop - tolerance, "line $i's box reaches its baseline")
            assertTrue(line.h < 20.0 / 792, "line $i's box is shorter than the line spacing")
            assertEquals(line.text.length + 1, line.cx.size, "one edge per character and the right edge of line $i")
            assertTrue(line.cx.zipWithNext().all { (a, b) -> b >= a }, "line $i's edges run left to right")
            assertEquals(line.x + line.w, line.cx.last(), 1e-3)
        }
    }

    @Test
    fun `a quote is boxed on the characters it covers`() {
        val hit = ScriptTextMatcher.search(typed, "slings and arrows").single()
        assertEquals(1, hit.page)
        val rect = ScriptTextMatcher.rects(typed, hit).single()
        val line = typed.pages[1].lines[3]
        assertTrue(rect.x > line.x + 0.05, "it starts mid-line, not at the line's left edge")
        assertTrue(rect.x + rect.w < line.x + line.w - 0.05, "and ends before the line does")
        assertEquals(line.y, rect.y)
    }

    @Test
    fun `the PDF's own outline is read with its levels and places`() {
        assertEquals(
            listOf("Act One" to 0, "Scene 2" to 1, "Scene 3" to 1),
            typed.outline.map { it.title to it.level },
        )
        assertEquals(listOf(0, 1, 2), typed.outline.map { it.page })
        assertEquals((792.0 - 512) / 792, typed.outline[1].y!!, 0.001)
    }

    @Test
    fun `an outline that loops back on itself is read once, and the text still comes out`() {
        Loader.loadPDF(resource("typed-script.pdf").toFile()).use { doc ->
            val actOne = doc.documentCatalog.documentOutline.firstChild
            val sceneTwo = actOne.firstChild
            sceneTwo.cosObject.setItem(COSName.FIRST, actOne.cosObject)
            sceneTwo.cosObject.setItem(COSName.LAST, actOne.cosObject)
            val index = ScriptTextExtractor.extract(doc, "c".repeat(64))
            assertEquals(listOf("Act One", "Scene 2", "Scene 3"), index.outline.map { it.title })
            assertEquals(typed.pages.map { p -> p.lines.map { it.text } }, index.pages.map { p -> p.lines.map { it.text } })
        }
    }

    @Test
    fun `a page that is only an image is a scan`() {
        val scanned = ScriptTextExtractor.extract(resource("scanned-script.pdf"), "b".repeat(64))
        assertEquals(2, scanned.pageCount)
        assertTrue(scanned.pages.all { it.scanned && it.lines.isEmpty() })
        assertEquals(listOf(0, 1), scanned.scannedPages)
        assertFalse(scanned.hasText)
        assertTrue(scanned.outline.isEmpty())
    }
}
