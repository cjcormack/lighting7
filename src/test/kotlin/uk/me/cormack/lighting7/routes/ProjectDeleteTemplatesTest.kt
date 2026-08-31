package uk.me.cormack.lighting7.routes

import io.ktor.client.request.delete
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplateRow
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
class ProjectDeleteTemplatesTest : RouteIntegrationTest() {

    @Test
    fun `deleting a project with templates succeeds and drops the template rows`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // A second, non-current project — the current one can't be deleted at all, and
        // that's not what this test is about.
        val (doomedProjectId, templateId) = transaction(state.database) {
            val project = DaoProject.new {
                name = "doomed-with-template"
                description = "holds a template"
                isCurrent = false
            }
            val template = DaoTemplate.new {
                this.project = project
                name = "amber-key"
                sortOrder = 0
            }
            DaoTemplateRow.new {
                this.template = template
                targetType = DEFERRED_TARGET_TYPE
                targetKey = ""
                propertyName = "rgbColour"
                value = "#ff9d4a"
                sortOrder = 0
            }
            project.id.value to template.id.value
        }

        val del = client.delete("/api/rest/projects/$doomedProjectId")
        assertEquals(HttpStatusCode.NoContent, del.status, del.bodyAsText())

        transaction(state.database) {
            assertNull(DaoProject.findById(doomedProjectId), "project should be gone")
            assertNull(DaoTemplate.findById(templateId), "template should be gone with it")
        }
    }
}
