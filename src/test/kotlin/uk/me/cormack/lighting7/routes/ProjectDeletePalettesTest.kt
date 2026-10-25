package uk.me.cormack.lighting7.routes

import io.ktor.client.request.delete
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPaletteRow
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression test for A1 (`docs/plans/completed/backend-post-refactor-sweep.md`): `DELETE /project/{id}`
 * had no teardown loop for `templates`, so a project holding one could never be deleted —
 * `templates.project_id` has no `ON DELETE` cascade and `project.delete()` would fail.
 */
class ProjectDeletePalettesTest : RouteIntegrationTest() {

    @Test
    fun `deleting a project with templates succeeds and drops the template rows`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // A second, non-current project — the current one can't be deleted at all, and
        // that's not what this test is about.
        val (doomedProjectId, paletteId) = transaction(state.database) {
            val project = DaoProject.new {
                name = "doomed-with-template"
                description = "holds a template"
                isCurrent = false
            }
            val template = DaoPalette.new {
                this.project = project
                name = "amber-key"
            }
            DaoPaletteRow.new {
                this.palette = template
                targetType = DEFERRED_TARGET_TYPE
                targetKey = ""
                attribute = "rgbColour"
                value = "#ff9d4a"
                sortOrder = 0
            }
            project.id.value to template.id.value
        }

        val del = client.delete("/api/rest/projects/$doomedProjectId")
        assertEquals(HttpStatusCode.NoContent, del.status, del.bodyAsText())

        transaction(state.database) {
            assertNull(DaoProject.findById(doomedProjectId), "project should be gone")
            assertNull(DaoPalette.findById(paletteId), "template should be gone with it")
        }
    }
}
