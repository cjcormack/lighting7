package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Unit tests for the cue-edit socket message wire format + [CueEditMode] parsing.
 *
 * The handler's DB-touching paths (`beginEdit` / `endEdit` / `setProperty` / `setChannel` /
 * `discardChanges`) are covered by manual smoke-check against the running backend — same
 * approach the routes tests use.
 */
class CueEditSessionTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── Mode parsing ────────────────────────────────────────────────────────

    @Test
    fun `CueEditMode parseOrNull accepts both cases`() {
        assertEquals(CueEditMode.LIVE, CueEditMode.parseOrNull("LIVE"))
        assertEquals(CueEditMode.LIVE, CueEditMode.parseOrNull("live"))
        assertEquals(CueEditMode.BLIND, CueEditMode.parseOrNull("Blind"))
    }

    @Test
    fun `CueEditMode parseOrNull returns null on garbage`() {
        assertNull(CueEditMode.parseOrNull("banana"))
        assertNull(CueEditMode.parseOrNull(""))
    }

    // ── Inbound message round-trips ────────────────────────────────────────

    @Test
    fun `beginEdit deserialises with cueId and mode`() {
        val raw = """{"type":"cueEdit.beginEdit","cueId":7,"mode":"live"}"""
        val decoded = assertIs<CueEditBeginEditInMessage>(json.decodeFromString<InMessage>(raw))
        assertEquals(7, decoded.cueId)
        assertEquals("live", decoded.mode)
    }

    @Test
    fun `endEdit deserialises with cueId`() {
        val raw = """{"type":"cueEdit.endEdit","cueId":3}"""
        val decoded = assertIs<CueEditEndEditInMessage>(json.decodeFromString<InMessage>(raw))
        assertEquals(3, decoded.cueId)
    }

    @Test
    fun `setChannel deserialises with universe channel level`() {
        val raw = """{"type":"cueEdit.setChannel","cueId":1,"universe":0,"channel":42,"level":200}"""
        val decoded = assertIs<CueEditSetChannelInMessage>(json.decodeFromString<InMessage>(raw))
        assertEquals(1, decoded.cueId)
        assertEquals(0, decoded.universe)
        assertEquals(42, decoded.channel)
        assertEquals(200u.toUByte(), decoded.level)
    }

    @Test
    fun `setProperty deserialises a fixture colour write`() {
        val raw = """{
            "type":"cueEdit.setProperty",
            "cueId":12,
            "targetType":"fixture",
            "targetKey":"hex-1",
            "propertyName":"rgbColour",
            "value":"#ff00aa"
        }"""
        val decoded = assertIs<CueEditSetPropertyInMessage>(json.decodeFromString<InMessage>(raw))
        assertEquals(12, decoded.cueId)
        assertEquals("fixture", decoded.targetType)
        assertEquals("hex-1", decoded.targetKey)
        assertEquals("rgbColour", decoded.propertyName)
        assertEquals("#ff00aa", decoded.value)
    }

    @Test
    fun `setProperty deserialises a group slider write`() {
        val raw = """{
            "type":"cueEdit.setProperty",
            "cueId":9,
            "targetType":"group",
            "targetKey":"front-wash",
            "propertyName":"dimmer",
            "value":"180"
        }"""
        val decoded = assertIs<CueEditSetPropertyInMessage>(json.decodeFromString<InMessage>(raw))
        assertEquals("group", decoded.targetType)
        assertEquals("180", decoded.value)
    }

    @Test
    fun `discardChanges deserialises with cueId`() {
        val raw = """{"type":"cueEdit.discardChanges","cueId":5}"""
        val decoded = assertIs<CueEditDiscardChangesInMessage>(json.decodeFromString<InMessage>(raw))
        assertEquals(5, decoded.cueId)
    }

    @Test
    fun `setMode deserialises with cueId and mode`() {
        val raw = """{"type":"cueEdit.setMode","cueId":4,"mode":"LIVE"}"""
        val decoded = assertIs<CueEditSetModeInMessage>(json.decodeFromString<InMessage>(raw))
        assertEquals(4, decoded.cueId)
        assertEquals("LIVE", decoded.mode)
    }

    @Test
    fun `clearAssignment deserialises with target + propertyName`() {
        val raw = """{
            "type":"cueEdit.clearAssignment",
            "cueId":11,
            "targetType":"fixture",
            "targetKey":"hex-1",
            "propertyName":"dimmer"
        }"""
        val decoded = assertIs<CueEditClearAssignmentInMessage>(json.decodeFromString<InMessage>(raw))
        assertEquals(11, decoded.cueId)
        assertEquals("fixture", decoded.targetType)
        assertEquals("hex-1", decoded.targetKey)
        assertEquals("dimmer", decoded.propertyName)
    }

    @Test
    fun `remaining stub messages still deserialise without error`() {
        val setPalette = json.decodeFromString<InMessage>(
            """{"type":"cueEdit.setPalette","cueId":1,"palette":["#ff0000","#00ff00"]}"""
        )
        assertIs<CueEditSetPaletteInMessage>(setPalette)

        val addPreset = json.decodeFromString<InMessage>(
            """{"type":"cueEdit.addPresetApplication","cueId":1}"""
        )
        assertIs<CueEditAddPresetApplicationInMessage>(addPreset)
    }

    @Test
    fun `assignmentCleared serialises with discriminator`() {
        val out = CueEditAssignmentClearedOutMessage(
            cueId = 7,
            targetType = "fixture",
            targetKey = "hex-1",
            propertyName = "dimmer",
        )
        val encoded = json.encodeToString<OutMessage>(out)
        val decoded = assertIs<CueEditAssignmentClearedOutMessage>(json.decodeFromString<OutMessage>(encoded))
        assertEquals(out, decoded)
    }

    // ── Outbound messages serialise to expected shape ──────────────────────

    @Test
    fun `sessionStarted serialises with discriminator`() {
        val out = CueEditSessionStartedOutMessage(cueId = 7, mode = "LIVE")
        val encoded = json.encodeToString<OutMessage>(out)
        assertEquals(
            """{"type":"cueEdit.sessionStarted","cueId":7,"mode":"LIVE"}""",
            encoded,
        )
    }

    @Test
    fun `assignmentChanged serialises with all fields`() {
        val out = CueEditAssignmentChangedOutMessage(
            cueId = 3,
            targetType = "fixture",
            targetKey = "hex-1",
            propertyName = "dimmer",
            value = "180",
        )
        val encoded = json.encodeToString<OutMessage>(out)
        val decoded = assertIs<CueEditAssignmentChangedOutMessage>(json.decodeFromString<OutMessage>(encoded))
        assertEquals(out, decoded)
    }

    @Test
    fun `error serialises with nullable cueId`() {
        val out = CueEditErrorOutMessage(cueId = null, message = "boom")
        val encoded = json.encodeToString<OutMessage>(out)
        val decoded = assertIs<CueEditErrorOutMessage>(json.decodeFromString<OutMessage>(encoded))
        assertNull(decoded.cueId)
        assertEquals("boom", decoded.message)
    }
}
