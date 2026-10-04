package uk.me.cormack.lighting7.fixture.media

import org.junit.Test
import uk.me.cormack.lighting7.fixture.dmx.Source4RevolutionFixture
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The gel library as the desk ships it (fixture optics plan D7): it loads, it holds every gel of the
 * Revolution's stock string, and the loader refuses a malformed entry naming every problem.
 */
class GelLibraryTest {

    @Test
    fun `the shipped library loads, by brand then code`() {
        val all = GelLibrary.all
        assertEquals(40, all.size)
        assertEquals(all.size, all.map { it.code }.toSet().size)
        assertEquals(listOf("Lee", "Rosco"), all.map { it.brand }.distinct())
        assertEquals("#9bbede", GelLibrary.byCode("L201")?.color)
    }

    @Test
    fun `every gel of the Revolution's stock string is in the library, with the string's swatch`() {
        val codes = mapOf(
            Source4RevolutionFixture.GelFrame.R02_BASTARD_AMBER to "R02",
            Source4RevolutionFixture.GelFrame.R05_ROSE_TINT to "R05",
            Source4RevolutionFixture.GelFrame.R09_PALE_AMBER_GOLD to "R09",
            Source4RevolutionFixture.GelFrame.R54_SPECIAL_LAVENDER to "R54",
            Source4RevolutionFixture.GelFrame.R357_ROYAL_LAVENDER to "R357",
            Source4RevolutionFixture.GelFrame.R36_MEDIUM_PINK to "R36",
            Source4RevolutionFixture.GelFrame.R25_ORANGE_RED to "R25",
            Source4RevolutionFixture.GelFrame.L203_QUARTER_CT_BLUE to "L203",
            Source4RevolutionFixture.GelFrame.L201_FULL_CT_BLUE to "L201",
            Source4RevolutionFixture.GelFrame.R68_SKY_BLUE to "R68",
            Source4RevolutionFixture.GelFrame.R88_LIGHT_GREEN to "R88",
            Source4RevolutionFixture.GelFrame.L_HT115_PEACOCK_BLUE to "L-HT115",
        )
        for ((frame, code) in codes) {
            val gel = assertNotNull(GelLibrary.byCode(code), "$code is in the library")
            assertEquals(frame.colourPreview.lowercase(), gel.color.lowercase(), "$code's swatch is the string's")
        }
        val added = listOf("R05", "R09", "R54", "R357", "R36", "R25", "R88", "L-HT115")
        assertTrue(added.all { GelLibrary.byCode(it)!!.estimate }, "the eight added swatches are marked as estimates")
        assertTrue(GelLibrary.all.filter { it.code !in added }.none { it.estimate })
    }

    @Test
    fun `the loader refuses a malformed library, naming every problem`() {
        val bad = """
            [
              {"code": "201", "name": "", "color": "blue", "brand": "Lee"},
              {"code": "L201", "name": "Full CT Blue", "color": "#9bbede", "brand": "GAM"},
              {"code": "R26", "name": "Light Red", "color": "#ee5b5e", "brand": "Lee"},
              {"code": "R26", "name": "Light Red", "color": "#ee5b5e", "brand": "Rosco"}
            ]
        """.trimIndent()
        val e = assertFailsWith<IllegalArgumentException> { GelLibrary.parse(bad) }
        val message = e.message.orEmpty()
        for (expected in listOf(
            "gel '201': code must be", "name is blank", "color 'blue' is not #rrggbb",
            "unknown brand 'GAM'", "a Lee code starts with L", "code 'R26' appears twice",
        )) {
            assertTrue(message.contains(expected), "expected '$expected' in: $message")
        }
    }
}
