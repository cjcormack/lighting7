package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplateRow
import uk.me.cormack.lighting7.models.LayerSourceKind
import uk.me.cormack.lighting7.models.LayerSourceShape
import uk.me.cormack.lighting7.models.layerSourceShape
import uk.me.cormack.lighting7.models.wellFormedOrWarn
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression tests for B7 (`docs/plans/completed/backend-post-refactor-sweep.md`): the
 * `DaoCueLayers.look`/`template` exactly-one invariant used to be enforced three ways with three
 * behaviours, and a *fourth* path wrote violations of it.
 *
 * Three guards, one per half of the fix:
 *
 * - the DB CHECK constraint refuses both malformed shapes, so no new path can quietly write one;
 * - [layerSourceShape] is the single verdict every caller shares;
 * - the cue-copy route preserves a template layer's referent. That loop set `look` alone, so
 *   copying a cue with a template layer produced a row naming neither record — and the resulting
 *   throw landed on the GO, not on the write that caused it.
 *
 * The CHECK guards pass here because the test DB is created fresh, which is exactly what they can
 * *not* prove about the operator's desk — see the constraint's own comment in `models/cues.kt`.
 */
class CueLayerSourceInvariantTest : RouteIntegrationTest() {

    /** A template with one deferred colour row, in the seeded project. */
    private fun seedTemplate(name: String): DaoTemplate = transaction(state.database) {
        val template = DaoTemplate.new {
            this.project = DaoProject.findById(projectId)!!
            this.name = name
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
        template
    }

    private fun seedCueInStack(name: String): DaoCue = transaction(state.database) {
        val project = DaoProject.findById(projectId)!!
        val stack = DaoCueStack.new {
            this.project = project
            this.name = "$name-stack"
            loop = false
            type = CueStackType.STACK.name
            sortOrder = 0
        }
        DaoCue.new {
            this.project = project
            this.name = name
            cueStack = stack
            sortOrder = 0
        }
    }

    /** Assert [block] fails the DB's CHECK constraint rather than being written. */
    private fun assertCheckConstraintViolation(what: String, block: () -> Unit) {
        try {
            block()
            fail("$what should violate the cue_layer_exactly_one_source CHECK constraint")
        } catch (e: Exception) {
            assertTrue(
                e.stackTraceToString().contains("CHECK constraint failed", ignoreCase = true),
                "expected a CHECK constraint violation, got: $e",
            )
        }
    }

    @Test
    fun `the CHECK constraint refuses a layer naming neither record`() {
        val cue = seedCueInStack("neither")
        assertCheckConstraintViolation("a cue layer naming neither a look nor a template") {
            // Exposed flushes on commit, so the violation surfaces from `transaction` rather than
            // from `new`.
            transaction(state.database) {
                DaoCueLayer.new {
                    this.cue = cue
                    targets = emptyList()
                    sortOrder = 0
                }
            }
        }
    }

    @Test
    fun `the CHECK constraint refuses a layer naming both records`() {
        val cue = seedCueInStack("both")
        val template = seedTemplate("both-template")
        val look = transaction(state.database) {
            DaoLook.new {
                this.project = DaoProject.findById(projectId)!!
                this.name = "both-look"
            }
        }
        assertCheckConstraintViolation("a cue layer naming both a look and a template") {
            transaction(state.database) {
                DaoCueLayer.new {
                    this.cue = cue
                    this.look = look
                    this.template = template
                    targets = emptyList()
                    sortOrder = 0
                }
            }
        }
    }

    /**
     * The verdict every path shares. Cheap to assert and worth asserting: the point of B7 is that
     * there is now *one* rule, reached by the read path, the REST write path and the importer
     * through these two functions.
     */
    @Test
    fun `layerSourceShape is the one verdict, and only the malformed shapes warn`() {
        assertEquals(LayerSourceShape.LOOK, layerSourceShape("a-look", null))
        assertEquals(LayerSourceShape.TEMPLATE, layerSourceShape(null, 7))
        assertEquals(LayerSourceShape.NEITHER, layerSourceShape(null, null))
        assertEquals(LayerSourceShape.BOTH, layerSourceShape("a-look", 7))

        assertNull(LayerSourceShape.LOOK.problem)
        assertNull(LayerSourceShape.TEMPLATE.problem)
        assertNotNull(LayerSourceShape.NEITHER.problem)
        assertNotNull(LayerSourceShape.BOTH.problem)

        assertTrue(LayerSourceShape.LOOK.wellFormedOrWarn { "unused" })
        assertFalse(LayerSourceShape.NEITHER.wellFormedOrWarn { "neither-case" })
        assertFalse(LayerSourceShape.BOTH.wellFormedOrWarn { "both-case" })
    }

    @Test
    fun `copying a cue preserves a template layer's referent`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val cue = seedCueInStack("copy-me")
        val template = seedTemplate("copy-template")
        val (sourceCueId, templateId) = transaction(state.database) {
            DaoCueLayer.new {
                this.cue = cue
                this.template = template
                targets = emptyList()
                sortOrder = 0
            }
            cue.id.value to template.id.value
        }

        // Into the same project: a cross-project copy would also carry the source project's
        // template reference across, which is a different problem and not this test's.
        val resp = client.post("/api/rest/projects/$projectId/cues/$sourceCueId/copy") {
            contentType(ContentType.Application.Json)
            setBody(CopyCueRequest(targetProjectId = projectId, newName = "copied"))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        val copiedCueId = resp.body<CopyCueResponse>().cueId

        transaction(state.database) {
            val layer = DaoCue.findById(copiedCueId)!!.layers.single()
            assertEquals(
                templateId, layer.template?.id?.value,
                "the copied layer should still name the template",
            )
            assertNull(layer.look, "and should not have grown a look")
        }

        // And the copy is readable: `toDto` resolves the layer's source rather than dropping it,
        // which is what a row naming neither record would now do.
        val details = client.get("/api/rest/projects/$projectId/cues/$copiedCueId")
        assertEquals(HttpStatusCode.OK, details.status, details.bodyAsText())
        val source = assertNotNull(details.body<CueDetails>().layers.single().source)
        assertEquals(LayerSourceKind.TEMPLATE.name, source.kind)
        assertEquals(templateId, source.id)
    }
}
