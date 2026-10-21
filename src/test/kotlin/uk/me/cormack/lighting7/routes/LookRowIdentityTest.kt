package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.LookEffectDto
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * A Look contents edit keeps the uuid of every row and effect it keeps, so a one-value edit reads in
 * the sync diff as one changed row rather than every row replaced.
 */
class LookRowIdentityTest : RouteIntegrationTest() {

    private fun base() = "/api/rest/projects/$projectId/looks"

    private val pulse = LookEffectDto(
        targetType = "fixture", targetKey = "hex-1",
        effectType = "Pulse", category = "dimmer", propertyName = "dimmer",
        beatDivision = 0.5, blendMode = "OVERRIDE", distribution = "LINEAR",
    )

    @Test
    fun `a PUT keeps the uuids of the rows and effects it keeps`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val lookId = client.createLook(
            listOf(
                LookRowDto("fixture", "hex-1", "dimmer", "100", sortOrder = 0),
                LookRowDto("fixture", "hex-1", "uv", "10", sortOrder = 1),
                LookRowDto("fixture", "hex-2", "dimmer", "50", sortOrder = 2),
            ),
            listOf(pulse),
        )
        val before = rowUuids(lookId)
        val effectBefore = effectUuids(lookId).single()

        val response = client.putContents(
            lookId,
            rows = listOf(
                LookRowDto("fixture", "hex-1", "dimmer", "100", sortOrder = 0),
                LookRowDto("fixture", "hex-1", "uv", "99", fadeDurationMs = 400L, sortOrder = 1),
                LookRowDto("fixture", "hex-2", "rgbColour", "#ff0000", sortOrder = 2),
            ),
            effects = listOf(pulse.copy(beatDivision = 2.0)),
        )
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

        val after = rowUuids(lookId)
        assertEquals(before.getValue(Row("hex-1", "dimmer")), after.getValue(Row("hex-1", "dimmer")), "untouched")
        assertEquals(before.getValue(Row("hex-1", "uv")), after.getValue(Row("hex-1", "uv")), "changed in place")
        assertFalse(Row("hex-2", "dimmer") in after, "dropped")
        assertFalse(after.getValue(Row("hex-2", "rgbColour")) in before.values, "added with a fresh uuid")
        assertEquals(effectBefore, effectUuids(lookId).single(), "the effect on the same property is edited in place")

        val details = response.body<LookDetails>()
        val uv = details.rows.single { it.propertyName == "uv" }
        assertEquals("99", uv.value)
        assertEquals(400L, uv.fadeDurationMs)
        assertEquals(2.0, details.effects.single().beatDivision)
    }

    @Test
    fun `a key held twice pairs in order, and a property's other spelling is the same key`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val lookId = client.createLook(
            listOf(
                LookRowDto("fixture", "hex-1", "dimmer", "1", sortOrder = 0),
                LookRowDto("fixture", "hex-1", "dimmer", "2", sortOrder = 1),
                LookRowDto("fixture", "hex-1", "rgbColour", "#00ff00", sortOrder = 2),
            ),
        )
        val (first, second) = orderedRowUuids(lookId).filter { it.first == "dimmer" }.map { it.second }
        val colour = orderedRowUuids(lookId).single { it.first == "rgbColour" }.second

        client.putContents(
            lookId,
            rows = listOf(
                LookRowDto("fixture", "hex-1", "dimmer", "3", sortOrder = 0),
                LookRowDto("fixture", "hex-1", "colour", "#0000ff", sortOrder = 1),
            ),
        )

        assertEquals(listOf("dimmer" to first, "colour" to colour), orderedRowUuids(lookId))
        assertNotEquals(first, second)
    }

    @Test
    fun `an effect swapped for another type on the same property is a new effect`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val lookId = client.createLook(emptyList(), listOf(pulse))
        val before = effectUuids(lookId).single()

        client.putContents(lookId, rows = emptyList(), effects = listOf(pulse.copy(effectType = "RampUp")))

        assertNotEquals(before, effectUuids(lookId).single())
    }

    @Test
    fun `a one-value edit changes only that value in the Look's sync file`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val rows = (1..5).map { LookRowDto("fixture", "hex-$it", "dimmer", "${it * 10}", sortOrder = it) }
        val lookId = client.createLook(rows)

        val before = exportedLook(lookId)
        client.putContents(lookId, rows = rows.map { if (it.targetKey == "hex-3") it.copy(value = "255") else it })
        val after = exportedLook(lookId)

        val expected = JsonObject(
            before + ("rows" to JsonArray(
                before.getValue("rows").jsonArray.map { row ->
                    val obj = row.jsonObject
                    if (obj["targetKey"] == JsonPrimitive("hex-3")) JsonObject(obj + ("value" to JsonPrimitive("255"))) else obj
                },
            )),
        )
        assertEquals(expected, after)
    }

    private data class Row(val targetKey: String, val propertyName: String)

    private fun rowUuids(lookId: Int): Map<Row, UUID> = transaction(state.database) {
        DaoLook[lookId].rows.associate { Row(it.targetKey, it.propertyName) to it.uuid }
    }

    private fun orderedRowUuids(lookId: Int): List<Pair<String, UUID>> = transaction(state.database) {
        DaoLook[lookId].rows.sortedBy { it.sortOrder }.map { it.propertyName to it.uuid }
    }

    private fun effectUuids(lookId: Int): List<UUID> = transaction(state.database) {
        DaoLook[lookId].effects.map { it.uuid }
    }

    private fun exportedLook(lookId: Int): JsonObject {
        val dir = Files.createTempDirectory("look-identity-")
        try {
            ProjectExporter(state).export(projectId, dir)
            val uuid = transaction(state.database) { DaoLook[lookId].uuid }
            return Json.parseToJsonElement(dir.resolve("looks/$uuid.json").readText()).jsonObject
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private suspend fun HttpClient.createLook(rows: List<LookRowDto>, effects: List<LookEffectDto> = emptyList()): Int =
        post(base()) {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = "Look", rows = rows, effects = effects))
        }.body<LookDetails>().id

    private suspend fun HttpClient.putContents(
        lookId: Int,
        rows: List<LookRowDto>,
        effects: List<LookEffectDto>? = null,
    ) = put("${base()}/$lookId") {
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
                put("rows", Json.encodeToJsonElement(rows))
                if (effects != null) put("effects", Json.encodeToJsonElement(effects))
            }
        )
    }
}
