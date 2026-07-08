package uk.me.cormack.lighting7.routes

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.models.PromptBookRectDto
import uk.me.cormack.lighting7.models.checkPromptBookRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PromptBookRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val rect = PromptBookRectDto(page = 0, x = 0.06, y = 0.25, w = 0.88, h = 0.04)

    // ─── DTO round-trips ─────────────────────────────────────────────────

    @Test
    fun `PromptBookRectDto round-trips`() {
        val serialized = json.encodeToString(rect)
        assertEquals(rect, json.decodeFromString<PromptBookRectDto>(serialized))
    }

    @Test
    fun `PromptBookDetails round-trips with anchors and annotations`() {
        val details = PromptBookDetails(
            scriptHash = "ab".repeat(32),
            scriptFileName = "act-one.pdf",
            pageCount = 42,
            coverPages = 2,
            anchors = listOf(
                PromptBookAnchorDto(cueId = 3, region = listOf(rect), label = "LX 12"),
                PromptBookAnchorDto(cueId = 4, region = listOf(rect, rect.copy(page = 1, y = 0.0))),
            ),
            annotations = listOf(
                PromptBookAnnotationDto(id = 1, kind = "STRIKETHROUGH", region = listOf(rect)),
                PromptBookAnnotationDto(id = 2, kind = "NOTE", region = listOf(rect), text = "slow build", color = "#ffb000"),
            ),
            canEdit = true,
        )
        val deserialized = json.decodeFromString<PromptBookDetails>(json.encodeToString(details))
        assertEquals(details, deserialized)
        assertEquals(2, deserialized.anchors.size)
        assertEquals(2, deserialized.coverPages, "coverPages must round-trip")
        assertNull(deserialized.anchors[1].label, "label omitted must stay null")
    }

    @Test
    fun `UpsertAnchorRequest defaults label to null`() {
        val deserialized = json.decodeFromString<UpsertAnchorRequest>(
            """{"region":[{"page":0,"x":0.1,"y":0.2,"w":0.5,"h":0.05}]}"""
        )
        assertNull(deserialized.label)
        assertEquals(1, deserialized.region.size)
    }

    @Test
    fun `AnnotationRequest strikethrough needs no text`() {
        val deserialized = json.decodeFromString<AnnotationRequest>(
            """{"kind":"STRIKETHROUGH","region":[{"page":2,"x":0.1,"y":0.4,"w":0.8,"h":0.1}]}"""
        )
        assertEquals("STRIKETHROUGH", deserialized.kind)
        assertNull(deserialized.text)
    }

    // ─── Region validation ───────────────────────────────────────────────

    private val pageCount = 10

    @Test
    fun `checkRegion rejects pages beyond the script`() {
        assertNotNull(checkPromptBookRegion(listOf(rect.copy(page = pageCount)), pageCount))
        assertNull(checkPromptBookRegion(listOf(rect.copy(page = pageCount - 1)), pageCount))
    }

    @Test
    fun `checkRegion accepts a valid multi-rect region`() {
        assertNull(checkPromptBookRegion(listOf(rect, rect.copy(page = 1, y = 0.0, h = 0.5)), pageCount))
    }

    @Test
    fun `checkRegion rejects empty region`() {
        assertNotNull(checkPromptBookRegion(emptyList(), pageCount))
    }

    @Test
    fun `checkRegion rejects negative page`() {
        assertNotNull(checkPromptBookRegion(listOf(rect.copy(page = -1)), pageCount))
    }

    @Test
    fun `checkRegion rejects non-finite coordinates`() {
        assertNotNull(checkPromptBookRegion(listOf(rect.copy(x = Double.NaN)), pageCount))
        assertNotNull(checkPromptBookRegion(listOf(rect.copy(h = Double.POSITIVE_INFINITY)), pageCount))
    }

    @Test
    fun `checkRegion rejects out-of-range origin`() {
        assertNotNull(checkPromptBookRegion(listOf(rect.copy(x = -0.1)), pageCount))
        assertNotNull(checkPromptBookRegion(listOf(rect.copy(y = 1.2)), pageCount))
    }

    @Test
    fun `checkRegion rejects zero or negative extents`() {
        assertNotNull(checkPromptBookRegion(listOf(rect.copy(w = 0.0)), pageCount))
        assertNotNull(checkPromptBookRegion(listOf(rect.copy(h = -0.1)), pageCount))
    }

    @Test
    fun `checkRegion rejects rects extending past the page`() {
        assertNotNull(checkPromptBookRegion(listOf(rect.copy(x = 0.8, w = 0.3)), pageCount))
        assertNotNull(checkPromptBookRegion(listOf(rect.copy(y = 0.99, h = 0.05)), pageCount))
    }

    @Test
    fun `checkRegion tolerates float noise at the page edge`() {
        assertNull(checkPromptBookRegion(listOf(rect.copy(x = 0.2, w = 0.80005)), pageCount))
    }
}
