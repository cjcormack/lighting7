package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Verifies the SQLite-safe collapse migration: a legacy `show_entries` layer is folded into the
 * ordered cue-stacks collection (STACK order applied, MARKER → SEPARATOR stack) and the playhead
 * migrates `active_entry_id` → `active_stack_id`.
 */
class CollapseShowMigrationTest : RouteIntegrationTest() {

    @Test
    fun `legacy show_entries collapse into ordered stacks`() {
        // Seed two runnable stacks and a legacy show_entries table: stackA, MARKER, stackB.
        val (aId, bId) = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val a = DaoCueStack.new {
                this.project = project; name = "Act 1"; palette = emptyList(); loop = false
                type = CueStackType.STACK.name; sortOrder = 0
            }
            val b = DaoCueStack.new {
                this.project = project; name = "Act 2"; palette = emptyList(); loop = false
                type = CueStackType.STACK.name; sortOrder = 0
            }

            exec(
                """CREATE TABLE IF NOT EXISTS show_entries (
                       id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INT, cue_stack_id INT,
                       entry_type VARCHAR(20), sort_order INT, label VARCHAR(255), uuid VARCHAR(36))"""
            )
            runCatching { exec("ALTER TABLE projects ADD COLUMN active_entry_id INTEGER") }

            exec("INSERT INTO show_entries (project_id, cue_stack_id, entry_type, sort_order, label, uuid) " +
                "VALUES ($projectId, ${a.id.value}, 'STACK', 0, NULL, 'uuid-a')")
            exec("INSERT INTO show_entries (project_id, cue_stack_id, entry_type, sort_order, label, uuid) " +
                "VALUES ($projectId, NULL, 'MARKER', 1, 'Interval', 'uuid-m')")
            exec("INSERT INTO show_entries (project_id, cue_stack_id, entry_type, sort_order, label, uuid) " +
                "VALUES ($projectId, ${b.id.value}, 'STACK', 2, NULL, 'uuid-b')")

            var entryId = 0
            exec("SELECT id FROM show_entries WHERE cue_stack_id = ${a.id.value}") { rs ->
                if (rs.next()) entryId = rs.getInt(1)
            }
            exec("UPDATE projects SET active_entry_id = $entryId WHERE id = $projectId")

            a.id.value to b.id.value
        }

        transaction(state.database) { migrateCollapseShowIntoStacks(state.database) }

        transaction(state.database) {
            val a = DaoCueStack.findById(aId)!!
            val b = DaoCueStack.findById(bId)!!
            assertEquals(0, a.sortOrder, "first stack keeps position 0")
            assertEquals(2, b.sortOrder, "second stack sits after the separator")

            val separators = DaoCueStack.find {
                (DaoCueStacks.project eq projectId) and (DaoCueStacks.type eq CueStackType.SEPARATOR.name)
            }.toList()
            assertEquals(1, separators.size, "MARKER entry becomes exactly one SEPARATOR stack")
            assertEquals("Interval", separators.first().label)
            assertEquals(1, separators.first().sortOrder, "separator sits between the two stacks")

            var activeStackId = -1
            exec("SELECT active_stack_id FROM projects WHERE id = $projectId") { rs ->
                if (rs.next()) activeStackId = rs.getInt(1)
            }
            assertEquals(aId, activeStackId, "active_entry_id resolves to its stack")

            var showEntriesExists = false
            exec("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'show_entries'") { rs ->
                showEntriesExists = rs.next()
            }
            assertFalse(showEntriesExists, "show_entries table is dropped")
        }
    }
}
