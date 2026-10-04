package uk.me.cormack.lighting7.fixture.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Test
import uk.me.cormack.lighting7.fixture.dmx.Source4RevolutionFixture.GelFrame
import uk.me.cormack.lighting7.fixture.dmx.Source4RevolutionFixture.MediaFrame
import uk.me.cormack.lighting7.fixture.dmx.Source4RevolutionFixture.WheelPosition
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fitted media's own rules (fixture optics plan session 3): the shape a write may send, what a type
 * refuses — every problem at once — and how a unit's slot resolves (placement, else patch, else
 * stock).
 */
class FittedMediaTest {

    private val rev = "etc-source4-revolution-base-frame"

    @Test
    fun `parse reads absent and null as nothing fitted, and keeps an empty slot`() {
        assertNull(FittedMedia.parse(null).getOrThrow())
        assertNull(FittedMedia.parse(JsonNull).getOrThrow())
        assertNull(FittedMedia.parse(json("""{"slots": {}}""")).getOrThrow(), "an empty map is nothing fitted")
        val media = FittedMedia.parse(
            json("""{"slots": {"gelScroller": {"L201_FULL_CT_BLUE": {"gel": " R26 "}, "OPEN_LEADER": {}}, "fbWheelPos": {"SLOT_1": {"gobo": "BreakUp"}}}}"""),
        ).getOrThrow()!!
        assertEquals(FittedSlot(gel = "R26"), media.slot("gelScroller", "L201_FULL_CT_BLUE"))
        assertEquals(FittedSlot(), media.slot("gelScroller", "OPEN_LEADER"), "an empty slot is fitted, with nothing")
        assertEquals(FittedSlot(gobo = "breakup"), media.slot("fbWheelPos", "SLOT_1"), "a gobo name is kept lowercase")
    }

    @Test
    fun `parse names every shape problem at once`() {
        val e = FittedMedia.parse(
            json("""{"slots": {"gelScroller": {"A": "R26", "B": {"gel": 26, "colour": "red"}}, "fbWheelPos": []}, "extra": 1}"""),
        ).exceptionOrNull()
        val message = e?.message.orEmpty()
        for (expected in listOf(
            "media has unknown field(s) extra",
            "media.slots.gelScroller.A must be an object",
            "media.slots.gelScroller.B.gel must be a string",
            "media.slots.gelScroller.B has unknown field(s) colour",
            "media.slots.fbWheelPos must be an object keyed by option name",
        )) {
            assertTrue(message.contains(expected), "expected '$expected' in: $message")
        }
        assertTrue(FittedMedia.parse(json("[]")).isFailure, "not an object")
    }

    @Test
    fun `a type refuses every problem together, each by its path`() {
        val media = FittedMedia(
            mapOf(
                "gelScroller" to mapOf(
                    "L201_FULL_CT_BLUE" to FittedSlot(gel = "R999"),
                    "R02_BASTARD_AMBER" to FittedSlot(gobo = "breakup"),
                    "FRAME_99" to FittedSlot(gel = "R26"),
                ),
                "fbWheelPos" to mapOf(
                    "OPEN" to FittedSlot(gel = "R26"),
                    "SLOT_1" to FittedSlot(gobo = "lasers"),
                    "SLOT_2" to FittedSlot(gel = "R26", gobo = "breakup"),
                ),
                "fbWheelFunc" to mapOf("INDEX" to FittedSlot(gel = "R26")),
                "noSuchProperty" to mapOf("X" to FittedSlot()),
            ),
        )
        val problems = media.typeProblems(rev).joinToString("\n")
        for (expected in listOf(
            "media.slots.gelScroller.L201_FULL_CT_BLUE: unknown gel 'R999'",
            "media.slots.gelScroller.R02_BASTARD_AMBER: gelScroller's slots take gels, not gobos",
            "media.slots.gelScroller.FRAME_99: gelScroller has no option FRAME_99",
            "media.slots.fbWheelPos.OPEN: OPEN is not a slot anything can be loaded into",
            "media.slots.fbWheelPos.SLOT_1: unknown gobo 'lasers'",
            "media.slots.fbWheelPos.SLOT_2: a slot holds a gel or a gobo, not both",
            "media.slots.fbWheelFunc: fbWheelFunc is not loadable",
            "media.slots.noSuchProperty: '$rev' has no property noSuchProperty",
        )) {
            assertTrue(problems.contains(expected), "expected '$expected' in:\n$problems")
        }

        val fine = FittedMedia(mapOf("fbWheelPos" to mapOf("SLOT_1" to FittedSlot(gel = "R26"), "SLOT_2" to FittedSlot(gobo = "breakup"))))
        assertEquals(emptyList(), fine.typeProblems(rev), "a module wheel slot takes a gel or a gobo")
    }

    @Test
    fun `a type with no loadable settings refuses any media, and nothing fitted is never refused`() {
        val media = FittedMedia(mapOf("gelScroller" to mapOf("L201_FULL_CT_BLUE" to FittedSlot(gel = "R26"))))
        assertEquals(listOf("media: 'hex' has no loadable settings, so nothing can be fitted to it"), media.typeProblems("hex"))
        assertEquals(emptyList(), FittedMedia().typeProblems("hex"))
    }

    @Test
    fun `a slot resolves to the placement's, else the patch's, else the stock`() {
        val patch = FittedMedia(
            mapOf("gelScroller" to mapOf("L201_FULL_CT_BLUE" to FittedSlot(gel = "R26"), "R25_ORANGE_RED" to FittedSlot(gel = "R80"))),
        )
        val placement = FittedMedia(mapOf("gelScroller" to mapOf("L201_FULL_CT_BLUE" to FittedSlot(gel = "L106"))))
        val unit = placement.over(patch)

        assertEquals(GelLibrary.byCode("L106")!!.color, unit.colourOf("gelScroller", GelFrame.L201_FULL_CT_BLUE), "the placement's own")
        assertEquals(GelLibrary.byCode("R80")!!.color, unit.colourOf("gelScroller", GelFrame.R25_ORANGE_RED), "else the patch's")
        assertEquals(GelFrame.R68_SKY_BLUE.colourPreview, unit.colourOf("gelScroller", GelFrame.R68_SKY_BLUE), "else the stock")
        assertEquals(GelLibrary.byCode("R26")!!.color, patch.colourOf("gelScroller", GelFrame.L201_FULL_CT_BLUE))
        assertEquals(GelFrame.L201_FULL_CT_BLUE.colourPreview, (null as FittedMedia?).colourOf("gelScroller", GelFrame.L201_FULL_CT_BLUE))
        assertEquals(patch, FittedMedia().over(patch), "a placement with nothing of its own is its patch's")
    }

    @Test
    fun `an empty slot is open, a gobo slot carries no colour, and an unknown gel keeps the stock`() {
        val media = FittedMedia(
            mapOf(
                "gelScroller" to mapOf("L201_FULL_CT_BLUE" to FittedSlot(), "R68_SKY_BLUE" to FittedSlot(gel = "R-NEWER")),
                "fbWheelPos" to mapOf("SLOT_1" to FittedSlot(gobo = "breakup"), "SLOT_2" to FittedSlot(gel = "R26")),
                "mediaFrame" to mapOf("IN" to FittedSlot(gel = "L202")),
            ),
        )
        assertEquals(OPEN_WHITE, media.colourOf("gelScroller", GelFrame.L201_FULL_CT_BLUE))
        assertEquals(GelFrame.R68_SKY_BLUE.colourPreview, media.colourOf("gelScroller", GelFrame.R68_SKY_BLUE))
        assertNull(media.colourOf("fbWheelPos", WheelPosition.SLOT_1))
        assertEquals("breakup", media.goboOf("fbWheelPos", WheelPosition.SLOT_1))
        assertEquals("#ee5b5e", media.colourOf("fbWheelPos", WheelPosition.SLOT_2))
        assertNull(media.goboOf("fbWheelPos", WheelPosition.SLOT_2), "a dichroic in the slot is no pattern")
        assertNull(media.colourOf("fbWheelPos", WheelPosition.SLOT_3), "an unfitted wheel slot is the stock: empty")
        assertEquals(GelLibrary.byCode("L202")!!.color, media.colourOf("mediaFrame", MediaFrame.IN))
        assertNull(media.colourOf("mediaFrame", MediaFrame.OUT))
    }

    @Test
    fun `the column round-trips, sorted, and a bad one reads as nothing fitted`() {
        val media = FittedMedia(
            mapOf(
                "gelScroller" to mapOf("R25_ORANGE_RED" to FittedSlot(gel = "R80"), "L201_FULL_CT_BLUE" to FittedSlot()),
                "fbWheelPos" to mapOf("SLOT_1" to FittedSlot(gobo = "breakup")),
            ),
        )
        val text = FittedMedia.toText(media)!!
        assertEquals(
            """{"slots":{"fbWheelPos":{"SLOT_1":{"gobo":"breakup"}},"gelScroller":{"L201_FULL_CT_BLUE":{},"R25_ORANGE_RED":{"gel":"R80"}}}}""",
            text,
        )
        assertEquals(media, FittedMedia.fromText(text))
        assertNull(FittedMedia.toText(FittedMedia()))
        assertNull(FittedMedia.fromText("not json"))
    }

    private fun json(text: String) = Json.parseToJsonElement(text)
}
