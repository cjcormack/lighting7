package uk.me.cormack.lighting7.link

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The stdio protocol's lines as the Go sidecar's tests read them too (link/testdata/stdio.json). */
class LinkMessagesTest {
    private val fixture = Json.parseToJsonElement(File("link/testdata/stdio.json").readText()).jsonObject

    @Test
    fun `every line to the sidecar encodes as the fixture says`() {
        for (line in fixture.getValue("toSidecar").jsonArray) {
            val message = LinkJson.decodeFromJsonElement(LinkIn.serializer(), line)
            assertEquals(line, Json.parseToJsonElement(message.encode()), "$message")
        }
    }

    @Test
    fun `every line from the sidecar decodes, and re-encodes as itself`() {
        val types = mutableSetOf<String>()
        for (line in fixture.getValue("fromSidecar").jsonArray) {
            val message = assertNotNull(LinkOut.decodeOrNull(line.toString()), "$line")
            types += message::class.simpleName!!
            assertEquals(line, LinkJson.encodeToJsonElement(LinkOut.serializer(), message) as JsonObject, "$message")
        }
        assertEquals(setOf("Started", "Answer", "Ice", "State", "Closed"), types)
    }

    @Test
    fun `a line that is no message reads as none`() {
        assertNull(LinkOut.decodeOrNull("not json"))
        assertNull(LinkOut.decodeOrNull("""{"type":"from.the.future"}"""))
        assertNull(LinkOut.decodeOrNull("""{"type":"session.answer"}"""))
        assertNull(LinkOut.decodeOrNull("[]"))
    }

    @Test
    fun `a long line is skipped and the next one read`() {
        val lines = BoundedLines(ByteArrayInputStream(("x".repeat(100) + "\nshort\r\ntail").toByteArray()), 10)
        assertEquals(BoundedLines.Line.TooLong, lines.next())
        assertEquals(BoundedLines.Line.Text("short"), lines.next())
        assertEquals(BoundedLines.Line.Text("tail"), lines.next())
        assertNull(lines.next())
    }
}
