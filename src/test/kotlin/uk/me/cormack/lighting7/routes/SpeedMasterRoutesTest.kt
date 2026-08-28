package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DEFAULT_SPEED_MASTER_COUNT
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedMasterRoutesTest : RouteIntegrationTest() {

    @Test
    fun `list lazily seeds the default bank and orders by index`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val list = client.get("/api/rest/projects/$projectId/speed-masters").body<List<SpeedMasterDto>>()
        assertEquals(DEFAULT_SPEED_MASTER_COUNT, list.size, "first read seeds the default bank")
        assertEquals((1..DEFAULT_SPEED_MASTER_COUNT).toList(), list.map { it.masterIndex })
        assertEquals("Master 1", list.first().name)
        assertEquals(120.0, list.first().bpm)
        assertTrue(list.all { it.referenceCount == 0 })

        // Seeding is idempotent — a second read must not mint another bank.
        val second = client.get("/api/rest/projects/$projectId/speed-masters").body<List<SpeedMasterDto>>()
        assertEquals(list.map { it.uuid }, second.map { it.uuid })
    }

    @Test
    fun `masters round-trip through POST GET PUT DELETE`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val createResp = client.post("/api/rest/projects/$projectId/speed-masters") {
            contentType(ContentType.Application.Json)
            setBody(CreateSpeedMasterRequest(name = "Strobe Bus", bpm = 240.0, notes = "chorus only"))
        }
        assertEquals(HttpStatusCode.Created, createResp.status, createResp.bodyAsText())
        val created = createResp.body<SpeedMasterDto>()
        assertEquals(DEFAULT_SPEED_MASTER_COUNT + 1, created.masterIndex, "create takes the next free index")
        assertEquals(240.0, created.bpm)

        val fetched = client.get("/api/rest/projects/$projectId/speed-masters/${created.id}")
            .body<SpeedMasterDto>()
        assertEquals(created.uuid, fetched.uuid)

        val putResp = client.put("/api/rest/projects/$projectId/speed-masters/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("name", JsonPrimitive("Strobe Bus 2"))
                    put("bpm", JsonPrimitive(180.0))
                }
            )
        }
        assertEquals(HttpStatusCode.OK, putResp.status, putResp.bodyAsText())
        val updated = putResp.body<SpeedMasterDto>()
        assertEquals("Strobe Bus 2", updated.name)
        assertEquals(180.0, updated.bpm)
        assertEquals("MANUAL", updated.source, "a typed bpm records MANUAL provenance")
        assertEquals("chorus only", updated.notes, "untouched notes survive a rename")

        val del = client.delete("/api/rest/projects/$projectId/speed-masters/${created.id}")
        assertEquals(HttpStatusCode.NoContent, del.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/rest/projects/$projectId/speed-masters/${created.id}").status,
        )
    }

    @Test
    fun `master 1 is protected from deletion`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val master1 = client.get("/api/rest/projects/$projectId/speed-masters")
            .body<List<SpeedMasterDto>>()
            .single { it.masterIndex == 1 }

        val del = client.delete("/api/rest/projects/$projectId/speed-masters/${master1.id}")
        assertEquals(HttpStatusCode.Conflict, del.status)
        val error = del.body<ErrorResponse>()
        assertEquals(CODE_SPEED_MASTER_PROTECTED, error.code)

        // Force does not bypass protection — master 1 is what null references resolve to.
        val forced = client.delete("/api/rest/projects/$projectId/speed-masters/${master1.id}?force=true")
        assertEquals(HttpStatusCode.Conflict, forced.status)
    }

    @Test
    fun `a referenced master refuses deletion with usage, force overrides`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val master2 = client.get("/api/rest/projects/$projectId/speed-masters")
            .body<List<SpeedMasterDto>>()
            .single { it.masterIndex == 2 }

        // Reference it from a cue ad-hoc effect — the column-backed usage source.
        val cueId = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val stack = DaoCueStack.new {
                this.project = project; name = "s"; loop = false
                type = CueStackType.STACK.name; sortOrder = 0
            }
            val cue = DaoCue.new {
                this.project = project; name = "c"; cueStack = stack; sortOrder = 0
            }
            DaoCueAdHocEffect.new {
                this.cue = cue; targetType = "fixture"; targetKey = "hex-1"
                effectType = "Pulse"; category = "dimmer"; beatDivision = 1.0
                blendMode = "OVERRIDE"; distribution = "LINEAR"
                parameters = emptyMap()
                speedMasterUuid = UUID.fromString(master2.uuid)
            }
            cue.id.value
        }

        val listed = client.get("/api/rest/projects/$projectId/speed-masters")
            .body<List<SpeedMasterDto>>()
            .single { it.masterIndex == 2 }
        assertEquals(1, listed.referenceCount, "the list surfaces persisted references")

        val del = client.delete("/api/rest/projects/$projectId/speed-masters/${master2.id}")
        assertEquals(HttpStatusCode.Conflict, del.status)
        val inUse = del.body<SpeedMasterInUseResponse>()
        assertEquals(CODE_SPEED_MASTER_IN_USE, inUse.code)
        assertEquals(1, inUse.cueAdHocEffectCount)
        assertEquals(listOf(cueId), inUse.cueIds)

        val forced = client.delete("/api/rest/projects/$projectId/speed-masters/${master2.id}?force=true")
        assertEquals(HttpStatusCode.NoContent, forced.status, "force leaves the reference dangling on purpose")
    }

    /**
     * A master used *only* as a wall-clock rate master is still in use. Before the rate
     * field was counted, the usage scan looked at `speedMasterUuid` alone and this delete
     * went through silently, leaving the look it scaled running unscaled.
     */
    @Test
    fun `a master referenced only as a rate master still refuses deletion`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val master2 = client.get("/api/rest/projects/$projectId/speed-masters")
            .body<List<SpeedMasterDto>>()
            .single { it.masterIndex == 2 }

        val cueId = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val stack = DaoCueStack.new {
                this.project = project; name = "s"; loop = false
                type = CueStackType.STACK.name; sortOrder = 0
            }
            val cue = DaoCue.new {
                this.project = project; name = "c"; cueStack = stack; sortOrder = 0
            }
            DaoCueAdHocEffect.new {
                this.cue = cue; targetType = "fixture"; targetKey = "hex-1"
                effectType = "CandleFlicker"; category = "dimmer"; beatDivision = 4.0
                blendMode = "OVERRIDE"; distribution = "LINEAR"
                parameters = emptyMap()
                // Deliberately no speedMasterUuid — the rate role alone must count.
                rateSpeedMasterUuid = UUID.fromString(master2.uuid)
            }
            cue.id.value
        }

        val listed = client.get("/api/rest/projects/$projectId/speed-masters")
            .body<List<SpeedMasterDto>>()
            .single { it.masterIndex == 2 }
        assertEquals(1, listed.referenceCount)

        val del = client.delete("/api/rest/projects/$projectId/speed-masters/${master2.id}")
        assertEquals(HttpStatusCode.Conflict, del.status)
        val inUse = del.body<SpeedMasterInUseResponse>()
        assertEquals(CODE_SPEED_MASTER_IN_USE, inUse.code)
        assertEquals(listOf(cueId), inUse.cueIds)
    }

    /** One row naming the same master in both roles is one place to go and fix, not two. */
    @Test
    fun `a row referencing a master in both roles counts once`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val master2 = client.get("/api/rest/projects/$projectId/speed-masters")
            .body<List<SpeedMasterDto>>()
            .single { it.masterIndex == 2 }

        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val stack = DaoCueStack.new {
                this.project = project; name = "s"; loop = false
                type = CueStackType.STACK.name; sortOrder = 0
            }
            val cue = DaoCue.new {
                this.project = project; name = "c"; cueStack = stack; sortOrder = 0
            }
            DaoCueAdHocEffect.new {
                this.cue = cue; targetType = "fixture"; targetKey = "hex-1"
                effectType = "Pulse"; category = "dimmer"; beatDivision = 1.0
                blendMode = "OVERRIDE"; distribution = "LINEAR"
                parameters = emptyMap()
                speedMasterUuid = UUID.fromString(master2.uuid)
                rateSpeedMasterUuid = UUID.fromString(master2.uuid)
            }
        }

        val listed = client.get("/api/rest/projects/$projectId/speed-masters")
            .body<List<SpeedMasterDto>>()
            .single { it.masterIndex == 2 }
        assertEquals(1, listed.referenceCount, "one row, one reference — not one per role")
    }

    @Test
    fun `bpm outside the clock's range is rejected`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val master1 = client.get("/api/rest/projects/$projectId/speed-masters")
            .body<List<SpeedMasterDto>>()
            .first()

        val tooFast = client.put("/api/rest/projects/$projectId/speed-masters/${master1.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("bpm", JsonPrimitive(999.0)) })
        }
        assertEquals(HttpStatusCode.BadRequest, tooFast.status)
    }

    @Test
    fun `duplicate names are refused per project`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // Seed the bank, then collide with a seeded name.
        client.get("/api/rest/projects/$projectId/speed-masters")
        val dup = client.post("/api/rest/projects/$projectId/speed-masters") {
            contentType(ContentType.Application.Json)
            setBody(CreateSpeedMasterRequest(name = "Master 1"))
        }
        assertEquals(HttpStatusCode.Conflict, dup.status)
    }
}
