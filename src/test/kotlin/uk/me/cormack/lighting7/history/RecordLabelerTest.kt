package uk.me.cormack.lighting7.history

import org.junit.Test
import uk.me.cormack.lighting7.sync.SyncDispositions
import kotlin.test.assertEquals

class RecordLabelerTest {
    @Test
    fun `every record folder has a name in the History`() {
        assertEquals(emptySet(), SyncDispositions.folders - RecordLabeler.namedFolders)
    }
}

class HistorySourceNameTest {
    @Test
    fun `a Claude grant is named by its client unless the client is Claude or unnamed`() {
        assertEquals("Claude · Cursor", HistorySource.mcp("cloud-grant:1", "Cursor").name)
        assertEquals("Claude", HistorySource.mcp("cloud-grant:1", "Claude").name)
        assertEquals("Claude", HistorySource.mcp("cloud-grant:1", "MCP client").name)
        assertEquals("Claude", HistorySource.mcp("cloud-grant:1").name)
        assertEquals("mcp:cloud:1", HistorySource.mcp("cloud-grant:1", "Cursor").key)
    }
}
