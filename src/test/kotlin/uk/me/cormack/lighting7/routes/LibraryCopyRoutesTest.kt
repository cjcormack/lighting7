package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplateEffect
import uk.me.cormack.lighting7.models.DaoTemplateEffects
import uk.me.cormack.lighting7.models.DaoTemplateRow
import uk.me.cormack.lighting7.models.DaoTemplateRows
import uk.me.cormack.lighting7.models.TemplateEffectDto
import uk.me.cormack.lighting7.models.TemplateRowDto
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.scripts.ScriptType
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The library copy routes: `POST /templates/{id}/copy` (library-sheets plan D10, `copyLook`'s shape)
 * and `POST /scripts/{id}/copy`, which used to drop the script's type.
 *
 * What is worth pinning about a template copy is that it is a **new entity with the same
 * contents**: every uuid fresh (or sync would take the two for one record), every field that makes
 * the template what it is carried over, and the press history left behind.
 */
class LibraryCopyRoutesTest : RouteIntegrationTest() {

    private fun templates(project: Int = projectId) = "/api/rest/projects/$project/templates"

    private fun otherProject(name: String = "other"): Int = transaction(state.database) {
        DaoProject.new { this.name = name; isCurrent = false }.id.value
    }

    private suspend fun HttpClient.createTemplate(input: TemplateInput): TemplateDto {
        val resp = post(templates()) {
            contentType(ContentType.Application.Json)
            setBody(input)
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.copyTemplate(
        templateId: Int,
        targetProjectId: Int,
        newName: String? = null,
        fromProject: Int = projectId,
    ): HttpResponse = post("${templates(fromProject)}/$templateId/copy") {
        contentType(ContentType.Application.Json)
        setBody(CopyTemplateRequest(targetProjectId = targetProjectId, newName = newName))
    }

    private fun perFixtureTemplate(name: String) = TemplateInput(
        name = name,
        notes = "aim at the lectern",
        fadeDurationMs = 2_500L,
        rows = listOf(
            TemplateRowDto("fixture", "mover-1", "position", "deg:12,-8"),
            TemplateRowDto("fixture", "mover-2", "position", "deg:-14,-8"),
        ),
    )

    @Test
    fun `a same-project copy under a new name is a new template with the same contents`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val source = client.createTemplate(perFixtureTemplate("lectern"))
        // A press history belongs to the template that was pressed.
        transaction(state.database) { DaoTemplate[source.id].lastPressedAt = nowUtc() }

        val resp = client.copyTemplate(source.id, projectId, newName = "lectern (Copy)")
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val answer = resp.body<CopyTemplateResponse>()
        assertEquals("lectern (Copy)", answer.templateName)
        assertEquals(projectId, answer.targetProjectId)
        assertNotEquals(source.id, answer.templateId)

        val copy = client.get("${templates()}/${answer.templateId}").body<TemplateDto>()
        assertNotEquals(source.uuid, copy.uuid, "a copy is a new entity — sync must not see one record")
        assertEquals("aim at the lectern", copy.notes)
        assertEquals(2_500L, copy.fadeDurationMs)
        assertEquals("POSITION", copy.family)
        assertEquals(
            source.rows.map { listOf(it.targetType, it.targetKey, it.propertyName, it.value, it.sortOrder) },
            copy.rows.map { listOf(it.targetType, it.targetKey, it.propertyName, it.value, it.sortOrder) },
            "per-fixture keys travel as they are",
        )
        assertNull(copy.lastPressedAt, "the copy has never been pressed")
        assertNotNull(
            client.get("${templates()}/${source.id}").body<TemplateDto>().lastPressedAt,
            "the source keeps its own stamp",
        )

        // Fresh child uuids, and the source's rows untouched.
        transaction(state.database) {
            val sourceRowUuids = DaoTemplateRow.find { DaoTemplateRows.template eq source.id }.map { it.uuid }.toSet()
            val copyRowUuids = DaoTemplateRow.find { DaoTemplateRows.template eq answer.templateId }.map { it.uuid }.toSet()
            assertEquals(2, sourceRowUuids.size)
            assertEquals(2, copyRowUuids.size)
            assertTrue(sourceRowUuids.intersect(copyRowUuids).isEmpty(), "every row uuid is fresh")
        }
    }

    @Test
    fun `an effect template copies its one effect field by field, with a fresh uuid`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val master = UUID.randomUUID().toString()
        val rateMaster = UUID.randomUUID().toString()
        val effect = TemplateEffectDto(
            effectType = "ColourPulse",
            category = "colour",
            propertyName = "rgbColour",
            beatDivision = 0.5,
            blendMode = "ADDITIVE",
            distribution = "LINEAR",
            phaseOffset = 0.25,
            elementMode = "PER_FIXTURE",
            stepTiming = true,
            parameters = mapOf("colour" to "#FF0000"),
            speedMasterUuid = master,
            rateSpeedMasterUuid = rateMaster,
        )
        val source = client.createTemplate(TemplateInput(name = "red-pulse", effect = effect))
        val sourceEffect = assertNotNull(source.effect)

        val other = otherProject()
        val resp = client.copyTemplate(source.id, other)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val answer = resp.body<CopyTemplateResponse>()
        assertEquals("red-pulse", answer.templateName, "an absent newName keeps the source's name")

        val copy = client.get("${templates(other)}/${answer.templateId}").body<TemplateDto>()
        assertTrue(copy.rows.isEmpty())
        // The whole DTO, `timingSource` included, since both are read through the same registry.
        assertEquals(sourceEffect, copy.effect, "the effect arrives with the same contents")
        assertEquals(master, copy.effect?.speedMasterUuid, "a master that is not in the target is kept")
        assertEquals(rateMaster, copy.effect?.rateSpeedMasterUuid)

        transaction(state.database) {
            val sourceUuid = DaoTemplateEffect.find { DaoTemplateEffects.template eq source.id }.single().uuid
            val copyUuid = DaoTemplateEffect.find { DaoTemplateEffects.template eq answer.templateId }.single().uuid
            assertNotEquals(sourceUuid, copyUuid)
        }
    }

    @Test
    fun `a copy into another project lands there and says where`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val source = client.createTemplate(perFixtureTemplate("lectern"))
        val other = otherProject("touring")

        val answer = client.copyTemplate(source.id, other).body<CopyTemplateResponse>()
        assertEquals(other, answer.targetProjectId)
        assertEquals("touring", answer.targetProjectName)
        assertEquals(listOf("lectern"), client.get(templates(other)).body<List<TemplateDto>>().map { it.name })
        assertEquals(listOf("lectern"), client.get(templates()).body<List<TemplateDto>>().map { it.name })
        transaction(state.database) {
            assertEquals(other, DaoTemplate[answer.templateId].project.id.value)
        }
    }

    @Test
    fun `copying out of a project that is not the live show works`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val other = otherProject()
        val sourceId = transaction(state.database) {
            val template = DaoTemplate.new {
                project = DaoProject[other]
                name = "far-amber"
            }
            DaoTemplateRow.new {
                this.template = template
                targetType = DEFERRED_TARGET_TYPE
                targetKey = ""
                propertyName = "rgbColour"
                value = "#FF9D4A;policy=extract"
            }
            template.id.value
        }

        val resp = client.copyTemplate(sourceId, projectId, fromProject = other)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        assertEquals(listOf("far-amber"), client.get(templates()).body<List<TemplateDto>>().map { it.name })
    }

    @Test
    fun `a name already taken in the target is a conflict that names it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val source = client.createTemplate(perFixtureTemplate("lectern"))

        val sameName = client.copyTemplate(source.id, projectId)
        assertEquals(HttpStatusCode.Conflict, sameName.status)
        assertTrue("'lectern'" in sameName.bodyAsText(), sameName.bodyAsText())

        client.createTemplate(perFixtureTemplate("lectern 2"))
        val taken = client.copyTemplate(source.id, projectId, newName = "  lectern 2  ")
        assertEquals(HttpStatusCode.Conflict, taken.status)
        assertTrue("'lectern 2'" in taken.bodyAsText(), "the name is trimmed before it is judged")

        assertEquals(2, client.get(templates()).body<List<TemplateDto>>().size, "nothing was written")
    }

    @Test
    fun `a template of another project, a missing one and a missing target are not found`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val source = client.createTemplate(perFixtureTemplate("lectern"))
        val other = otherProject()

        // Addressed through a project it does not belong to.
        assertEquals(HttpStatusCode.NotFound, client.copyTemplate(source.id, other, fromProject = other).status)
        assertEquals(HttpStatusCode.NotFound, client.copyTemplate(source.id + 1_000, projectId).status)
        val noTarget = client.copyTemplate(source.id, 9_999)
        assertEquals(HttpStatusCode.NotFound, noTarget.status)
        assertTrue("Target project" in noTarget.bodyAsText(), noTarget.bodyAsText())
        assertTrue(client.get(templates(other)).body<List<TemplateDto>>().isEmpty())
    }

    // ─── Scripts ────────────────────────────────────────────────────────

    @Test
    fun `a copied script keeps its type`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val other = otherProject()
        val sourceId = transaction(state.database) {
            DaoScript.new {
                project = DaoProject[projectId]
                name = "chase-front"
                script = "// applies a chase"
                scriptType = ScriptType.FX_APPLICATION
            }.id.value
        }

        val resp = client.post("/api/rest/projects/$projectId/scripts/$sourceId/copy") {
            contentType(ContentType.Application.Json)
            setBody(CopyScriptRequest(targetProjectId = other))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        val copyId = resp.body<CopyScriptResponse>().scriptId
        transaction(state.database) {
            val copy = DaoScript[copyId]
            assertEquals(ScriptType.FX_APPLICATION, copy.scriptType, "not the column's GENERAL default")
            assertEquals(other, copy.project.id.value)
        }
    }
}
