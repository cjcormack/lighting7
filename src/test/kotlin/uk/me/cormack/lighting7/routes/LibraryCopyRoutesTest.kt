package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import uk.me.cormack.lighting7.models.Timing
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
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPaletteFxEntity
import uk.me.cormack.lighting7.models.DaoPaletteFx
import uk.me.cormack.lighting7.models.DaoPaletteRow
import uk.me.cormack.lighting7.models.DaoPaletteRows
import uk.me.cormack.lighting7.models.PaletteFxDto
import uk.me.cormack.lighting7.models.PaletteRowDto
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

    private fun templates(project: Int = projectId) = "/api/rest/projects/$project/palettes"

    private fun otherProject(name: String = "other"): Int = transaction(state.database) {
        DaoProject.new { this.name = name; isCurrent = false }.id.value
    }

    private suspend fun HttpClient.createPalette(input: PaletteInput): PaletteDto {
        val resp = post(templates()) {
            contentType(ContentType.Application.Json)
            setBody(input)
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.copyPalette(
        paletteId: Int,
        targetProjectId: Int,
        newName: String? = null,
        fromProject: Int = projectId,
    ): HttpResponse = post("${templates(fromProject)}/$paletteId/copy") {
        contentType(ContentType.Application.Json)
        setBody(CopyPaletteRequest(targetProjectId = targetProjectId, newName = newName))
    }

    private fun perFixturePalette(name: String) = PaletteInput(
        name = name,
        notes = "aim at the lectern",
        fadeDurationMs = 2_500L,
        rows = listOf(
            PaletteRowDto("fixture", "mover-1", "position", "deg:12,-8"),
            PaletteRowDto("fixture", "mover-2", "position", "deg:-14,-8"),
        ),
    )

    @Test
    fun `a same-project copy under a new name is a new template with the same contents`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val source = client.createPalette(perFixturePalette("lectern"))
        // A press history belongs to the template that was pressed.
        transaction(state.database) { DaoPalette[source.id].lastPressedAt = nowUtc() }

        val resp = client.copyPalette(source.id, projectId, newName = "lectern (Copy)")
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val answer = resp.body<CopyPaletteResponse>()
        assertEquals("lectern (Copy)", answer.paletteName)
        assertEquals(projectId, answer.targetProjectId)
        assertNotEquals(source.id, answer.paletteId)

        val copy = client.get("${templates()}/${answer.paletteId}").body<PaletteDto>()
        assertNotEquals(source.uuid, copy.uuid, "a copy is a new entity — sync must not see one record")
        assertEquals("aim at the lectern", copy.notes)
        assertEquals(2_500L, copy.fadeDurationMs)
        assertEquals("POSITION", copy.family)
        assertEquals(
            source.rows.map { listOf(it.targetType, it.targetKey, it.attribute, it.value, it.sortOrder) },
            copy.rows.map { listOf(it.targetType, it.targetKey, it.attribute, it.value, it.sortOrder) },
            "per-fixture keys travel as they are",
        )
        assertNull(copy.lastPressedAt, "the copy has never been pressed")
        assertNotNull(
            client.get("${templates()}/${source.id}").body<PaletteDto>().lastPressedAt,
            "the source keeps its own stamp",
        )

        // Fresh child uuids, and the source's rows untouched.
        transaction(state.database) {
            val sourceRowUuids = DaoPaletteRow.find { DaoPaletteRows.palette eq source.id }.map { it.uuid }.toSet()
            val copyRowUuids = DaoPaletteRow.find { DaoPaletteRows.palette eq answer.paletteId }.map { it.uuid }.toSet()
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
        val effect = PaletteFxDto(
            fxType = "ColourWave",
            family = "colour",
            attribute = "rgbColour",
            cycle = 0.5,
            blendMode = "ADDITIVE",
            order = "NORMAL",
            phaseOffset = 0.25,
            elementMode = "PER_FIXTURE",
            timing = Timing.HEAD,
            parameters = mapOf("colour" to "#FF0000"),
            speedMasterUuid = master,
            rateSpeedMasterUuid = rateMaster,
        )
        val source = client.createPalette(PaletteInput(name = "red-pulse", fx = effect))
        val sourceFx = assertNotNull(source.fx)

        val other = otherProject()
        val resp = client.copyPalette(source.id, other)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val answer = resp.body<CopyPaletteResponse>()
        assertEquals("red-pulse", answer.paletteName, "an absent newName keeps the source's name")

        val copy = client.get("${templates(other)}/${answer.paletteId}").body<PaletteDto>()
        assertTrue(copy.rows.isEmpty())
        // The whole DTO, `timingSource` included, since both are read through the same registry.
        assertEquals(sourceFx, copy.fx, "the effect arrives with the same contents")
        assertEquals(master, copy.fx?.speedMasterUuid, "a master that is not in the target is kept")
        assertEquals(rateMaster, copy.fx?.rateSpeedMasterUuid)

        transaction(state.database) {
            val sourceUuid = DaoPaletteFxEntity.find { DaoPaletteFx.palette eq source.id }.single().uuid
            val copyUuid = DaoPaletteFxEntity.find { DaoPaletteFx.palette eq answer.paletteId }.single().uuid
            assertNotEquals(sourceUuid, copyUuid)
        }
    }

    @Test
    fun `a copy into another project lands there and says where`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val source = client.createPalette(perFixturePalette("lectern"))
        val other = otherProject("touring")

        val answer = client.copyPalette(source.id, other).body<CopyPaletteResponse>()
        assertEquals(other, answer.targetProjectId)
        assertEquals("touring", answer.targetProjectName)
        assertEquals(listOf("lectern"), client.get(templates(other)).body<List<PaletteDto>>().map { it.name })
        assertEquals(listOf("lectern"), client.get(templates()).body<List<PaletteDto>>().map { it.name })
        transaction(state.database) {
            assertEquals(other, DaoPalette[answer.paletteId].project.id.value)
        }
    }

    @Test
    fun `copying out of a project that is not the live show works`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val other = otherProject()
        val sourceId = transaction(state.database) {
            val template = DaoPalette.new {
                project = DaoProject[other]
                name = "far-amber"
            }
            DaoPaletteRow.new {
                this.palette = template
                targetType = DEFERRED_TARGET_TYPE
                targetKey = ""
                attribute = "rgbColour"
                value = "#FF9D4A;policy=extract"
            }
            template.id.value
        }

        val resp = client.copyPalette(sourceId, projectId, fromProject = other)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        assertEquals(listOf("far-amber"), client.get(templates()).body<List<PaletteDto>>().map { it.name })
    }

    @Test
    fun `a name already taken in the target is a conflict that names it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val source = client.createPalette(perFixturePalette("lectern"))

        val sameName = client.copyPalette(source.id, projectId)
        assertEquals(HttpStatusCode.Conflict, sameName.status)
        assertTrue("'lectern'" in sameName.bodyAsText(), sameName.bodyAsText())

        client.createPalette(perFixturePalette("lectern 2"))
        val taken = client.copyPalette(source.id, projectId, newName = "  lectern 2  ")
        assertEquals(HttpStatusCode.Conflict, taken.status)
        assertTrue("'lectern 2'" in taken.bodyAsText(), "the name is trimmed before it is judged")

        assertEquals(2, client.get(templates()).body<List<PaletteDto>>().size, "nothing was written")
    }

    @Test
    fun `a template of another project, a missing one and a missing target are not found`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val source = client.createPalette(perFixturePalette("lectern"))
        val other = otherProject()

        // Addressed through a project it does not belong to.
        assertEquals(HttpStatusCode.NotFound, client.copyPalette(source.id, other, fromProject = other).status)
        assertEquals(HttpStatusCode.NotFound, client.copyPalette(source.id + 1_000, projectId).status)
        val noTarget = client.copyPalette(source.id, 9_999)
        assertEquals(HttpStatusCode.NotFound, noTarget.status)
        assertTrue("Target project" in noTarget.bodyAsText(), noTarget.bodyAsText())
        assertTrue(client.get(templates(other)).body<List<PaletteDto>>().isEmpty())
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
