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
import io.ktor.client.HttpClient
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CODE_SPEED_MASTER_CANNOT_FOLLOW
import uk.me.cormack.lighting7.models.CODE_SPEED_MASTER_FOLLOWER
import uk.me.cormack.lighting7.models.CODE_SPEED_MASTER_FOLLOW_CYCLE
import uk.me.cormack.lighting7.models.CODE_SPEED_MASTER_FOLLOW_TARGET_UNKNOWN
import uk.me.cormack.lighting7.models.CODE_SPEED_MASTER_INVALID
import uk.me.cormack.lighting7.models.CODE_SPEED_MASTER_USAGE_TAKEN
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoSpeedMaster
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DEFAULT_SPEED_MASTER_COUNT
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    // ─── Usage and follow (busking-view plan, session 1) ─────────────────

    private suspend fun HttpClient.masterAt(index: Int): SpeedMasterDto =
        get("/api/rest/projects/$projectId/speed-masters")
            .body<List<SpeedMasterDto>>()
            .single { it.masterIndex == index }

    @Test
    fun `usage and ratio round-trip through POST PUT GET`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val created = client.post("/api/rest/projects/$projectId/speed-masters") {
            contentType(ContentType.Application.Json)
            setBody(CreateSpeedMasterRequest(name = "Movement", usage = "position"))
        }.body<SpeedMasterDto>()
        assertEquals("position", created.usage)

        val putResp = client.put("/api/rest/projects/$projectId/speed-masters/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("followNum", JsonPrimitive(1))
                    put("followDen", JsonPrimitive(2))
                }
            )
        }
        assertEquals(HttpStatusCode.OK, putResp.status, putResp.bodyAsText())
        val updated = putResp.body<SpeedMasterDto>()
        assertEquals(1, updated.followNum)
        assertEquals(2, updated.followDen)
        assertEquals("position", updated.usage, "an untouched usage survives a ratio PUT")

        val fetched = client.get("/api/rest/projects/$projectId/speed-masters/${created.id}")
            .body<SpeedMasterDto>()
        assertEquals(1, fetched.followNum)
        assertEquals(2, fetched.followDen)
    }

    @Test
    fun `a second master claiming the same usage is refused on create and update`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        client.post("/api/rest/projects/$projectId/speed-masters") {
            contentType(ContentType.Application.Json)
            setBody(CreateSpeedMasterRequest(name = "Movement", usage = "position"))
        }

        val dupCreate = client.post("/api/rest/projects/$projectId/speed-masters") {
            contentType(ContentType.Application.Json)
            setBody(CreateSpeedMasterRequest(name = "Movement 2", usage = "position"))
        }
        assertEquals(HttpStatusCode.Conflict, dupCreate.status)
        assertEquals(CODE_SPEED_MASTER_USAGE_TAKEN, dupCreate.body<ErrorResponse>().code)

        val master2 = client.masterAt(2)
        val dupPut = client.put("/api/rest/projects/$projectId/speed-masters/${master2.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("usage", JsonPrimitive("position")) })
        }
        assertEquals(HttpStatusCode.Conflict, dupPut.status)
        assertEquals(CODE_SPEED_MASTER_USAGE_TAKEN, dupPut.body<ErrorResponse>().code)
    }

    @Test
    fun `re-saving a master with its own usage is allowed`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val created = client.post("/api/rest/projects/$projectId/speed-masters") {
            contentType(ContentType.Application.Json)
            setBody(CreateSpeedMasterRequest(name = "Movement", usage = "position"))
        }.body<SpeedMasterDto>()

        // The uniqueness check must exclude the row being edited — a rename that carries the
        // existing usage back is the commonest PUT there is.
        val resave = client.put("/api/rest/projects/$projectId/speed-masters/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("name", JsonPrimitive("Movement Renamed"))
                    put("usage", JsonPrimitive("position"))
                }
            )
        }
        assertEquals(HttpStatusCode.OK, resave.status, resave.bodyAsText())
    }

    @Test
    fun `master 1 may not follow`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val master1 = client.masterAt(1)

        val resp = client.put("/api/rest/projects/$projectId/speed-masters/${master1.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("followNum", JsonPrimitive(1))
                    put("followDen", JsonPrimitive(2))
                }
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(CODE_SPEED_MASTER_CANNOT_FOLLOW, resp.body<ErrorResponse>().code)
    }

    @Test
    fun `a half-set or non-positive ratio is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val master2 = client.masterAt(2)

        val half = client.put("/api/rest/projects/$projectId/speed-masters/${master2.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("followNum", JsonPrimitive(2)) })
        }
        assertEquals(HttpStatusCode.BadRequest, half.status)
        assertEquals(CODE_SPEED_MASTER_INVALID, half.body<ErrorResponse>().code)

        val zero = client.put("/api/rest/projects/$projectId/speed-masters/${master2.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("followNum", JsonPrimitive(0))
                    put("followDen", JsonPrimitive(1))
                }
            )
        }
        assertEquals(HttpStatusCode.BadRequest, zero.status)
        assertEquals(CODE_SPEED_MASTER_INVALID, zero.body<ErrorResponse>().code)
    }

    @Test
    fun `an unknown usage is refused at the boundary`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // `beam` and `controls` are real strings elsewhere in the codebase but deliberately
        // NOT routable usages (D7) — pinning the exclusion here, not just in the constant.
        for (bad in listOf("beam", "controls", "smoke")) {
            val resp = client.post("/api/rest/projects/$projectId/speed-masters") {
                contentType(ContentType.Application.Json)
                setBody(CreateSpeedMasterRequest(name = "Bad $bad", usage = bad))
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status, "usage '$bad' must be refused")
            assertEquals(CODE_SPEED_MASTER_INVALID, resp.body<ErrorResponse>().code)
        }
    }

    @Test
    fun `a typed bpm on a follower is refused and the stored bpm is unchanged`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val master2 = client.masterAt(2)

        client.put("/api/rest/projects/$projectId/speed-masters/${master2.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("followNum", JsonPrimitive(1))
                    put("followDen", JsonPrimitive(2))
                }
            )
        }

        val resp = client.put("/api/rest/projects/$projectId/speed-masters/${master2.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("bpm", JsonPrimitive(90.0)) })
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(CODE_SPEED_MASTER_FOLLOWER, resp.body<ErrorResponse>().code)

        val fetched = client.masterAt(2)
        // The live bank has written the derived tempo (60) through to the row; what matters is
        // the refused 90 never landed anywhere.
        assertTrue(fetched.bpm != 90.0, "the refused bpm must not land in the row")
    }

    @Test
    fun `unlinking clears both columns and re-enables bpm writes`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val master2 = client.masterAt(2)

        client.put("/api/rest/projects/$projectId/speed-masters/${master2.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("followNum", JsonPrimitive(1))
                    put("followDen", JsonPrimitive(2))
                }
            )
        }

        val unlink = client.put("/api/rest/projects/$projectId/speed-masters/${master2.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("followNum", JsonNull)
                    put("followDen", JsonNull)
                }
            )
        }
        assertEquals(HttpStatusCode.OK, unlink.status, unlink.bodyAsText())
        val unlinked = unlink.body<SpeedMasterDto>()
        assertNull(unlinked.followNum)
        assertNull(unlinked.followDen)

        val bpmPut = client.put("/api/rest/projects/$projectId/speed-masters/${master2.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("bpm", JsonPrimitive(90.0)) })
        }
        assertEquals(HttpStatusCode.OK, bpmPut.status, "an unlinked master takes bpm writes again")
    }

    @Test
    fun `creating a follower with a bpm is refused, matching the PUT`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // The same field combination the PUT 400s must not 201 on POST with a bpm the load
        // sweep would immediately overwrite.
        val resp = client.post("/api/rest/projects/$projectId/speed-masters") {
            contentType(ContentType.Application.Json)
            setBody(CreateSpeedMasterRequest(name = "Movement", bpm = 90.0, followNum = 1, followDen = 2))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(CODE_SPEED_MASTER_FOLLOWER, resp.body<ErrorResponse>().code)
    }

    @Test
    fun `a rename succeeds on a row whose imported columns never saw the write boundary`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val master2 = client.masterAt(2)

        // Simulate an import: ProjectImporter writes usage/follow verbatim, so a row can hold
        // values the write boundary would refuse. A PUT that touches neither must not trip
        // over them — carried-forward values go through the sanitisers, not re-validation.
        transaction(state.database) {
            val dao = DaoSpeedMaster.findById(master2.id)!!
            dao.usageCategory = "color" // non-canonical spelling
            dao.followNum = 1           // half-written pair
            dao.followDen = null
        }

        val resp = client.put("/api/rest/projects/$projectId/speed-masters/${master2.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", JsonPrimitive("Renamed Anyway")) })
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        // The write-back heals the junk pair (sanitised carry-forward), and GET agrees.
        val fetched = client.masterAt(2)
        assertNull(fetched.followNum)
        assertNull(fetched.followDen)

        // The same rule for a *whole* link naming a master this project doesn't have — an
        // import that carried a follower but not its leader. The forced delete cleans its own
        // followers up, so an import is the only way this row shape still arrives, and a PUT
        // that touches neither half of the link must still not be judged on it.
        val master3 = client.masterAt(3)
        transaction(state.database) {
            val dao = DaoSpeedMaster.findById(master3.id)!!
            dao.followNum = 1
            dao.followDen = 2
            dao.followTargetUuid = UUID.randomUUID()
        }
        val notesEdit = client.put("/api/rest/projects/$projectId/speed-masters/${master3.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("notes", JsonPrimitive("still editable")) })
        }
        assertEquals(HttpStatusCode.OK, notesEdit.status, notesEdit.bodyAsText())
    }

    @Test
    fun `a master may follow a master other than master 1`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val master2 = client.masterAt(2)
        val master3 = client.masterAt(3)

        client.put("/api/rest/projects/$projectId/speed-masters/${master2.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("bpm", JsonPrimitive(90.0)) })
        }
        val link = client.put("/api/rest/projects/$projectId/speed-masters/${master3.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("followNum", JsonPrimitive(1))
                    put("followDen", JsonPrimitive(2))
                    put("followTargetUuid", JsonPrimitive(master2.uuid))
                }
            )
        }
        assertEquals(HttpStatusCode.OK, link.status, link.bodyAsText())
        assertEquals(master2.uuid, link.body<SpeedMasterDto>().followTargetUuid)
        assertEquals(master2.uuid, client.masterAt(3).followTargetUuid, "and it survives a re-read")

        // The live bank derives from *that* master, not from master 1.
        val live = state.show.speedMasterBank.masterStates().single { it.index == 3 }
        assertEquals(45.0, live.bpm, "½ of master 2's 90, not of master 1's 120")

        // A ratio-only patch keeps the stored leader — the target rides with the pair, but an
        // edit that doesn't send it must not silently re-point the link at master 1.
        val reratio = client.put("/api/rest/projects/$projectId/speed-masters/${master3.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("followNum", JsonPrimitive(1))
                    put("followDen", JsonPrimitive(4))
                }
            )
        }
        assertEquals(master2.uuid, reratio.body<SpeedMasterDto>().followTargetUuid)

        // …and an unlink clears it, so a later re-link starts from master 1 rather than from a
        // leader the operator can no longer see.
        val unlink = client.put("/api/rest/projects/$projectId/speed-masters/${master3.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("followNum", JsonNull)
                    put("followDen", JsonNull)
                }
            )
        }
        assertNull(unlink.body<SpeedMasterDto>().followTargetUuid)
    }

    @Test
    fun `a follow chain is allowed but a cycle is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val master2 = client.masterAt(2)
        val master3 = client.masterAt(3)

        // M2 → M1, then M3 → M2: chains are legal (the answer to "can a follower be followed").
        client.put("/api/rest/projects/$projectId/speed-masters/${master2.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("followNum", JsonPrimitive(1))
                    put("followDen", JsonPrimitive(2))
                }
            )
        }
        val chain = client.put("/api/rest/projects/$projectId/speed-masters/${master3.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("followNum", JsonPrimitive(1))
                    put("followDen", JsonPrimitive(2))
                    put("followTargetUuid", JsonPrimitive(master2.uuid))
                }
            )
        }
        assertEquals(HttpStatusCode.OK, chain.status, chain.bodyAsText())
        assertEquals(30.0, state.show.speedMasterBank.masterStates().single { it.index == 3 }.bpm)

        // Closing the loop is the one shape with no tempo: M2 would derive from M3, which
        // derives from M2, and nothing upstream has a timer.
        val cycle = client.put("/api/rest/projects/$projectId/speed-masters/${master2.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("followNum", JsonPrimitive(1))
                    put("followDen", JsonPrimitive(2))
                    put("followTargetUuid", JsonPrimitive(master3.uuid))
                }
            )
        }
        assertEquals(HttpStatusCode.BadRequest, cycle.status)
        assertEquals(CODE_SPEED_MASTER_FOLLOW_CYCLE, cycle.body<ErrorResponse>().code)

        val self = client.put("/api/rest/projects/$projectId/speed-masters/${master2.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("followNum", JsonPrimitive(1))
                    put("followDen", JsonPrimitive(1))
                    put("followTargetUuid", JsonPrimitive(master2.uuid))
                }
            )
        }
        assertEquals(HttpStatusCode.BadRequest, self.status)
        assertEquals(CODE_SPEED_MASTER_FOLLOW_CYCLE, self.body<ErrorResponse>().code)
    }

    @Test
    fun `a follow target that names no master is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val master2 = client.masterAt(2)

        val resp = client.put("/api/rest/projects/$projectId/speed-masters/${master2.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("followNum", JsonPrimitive(1))
                    put("followDen", JsonPrimitive(2))
                    put("followTargetUuid", JsonPrimitive(UUID.randomUUID().toString()))
                }
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(CODE_SPEED_MASTER_FOLLOW_TARGET_UNKNOWN, resp.body<ErrorResponse>().code)
    }

    @Test
    fun `deleting a master another one follows is refused, force overrides`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val master2 = client.masterAt(2)
        val master3 = client.masterAt(3)

        client.put("/api/rest/projects/$projectId/speed-masters/${master3.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("name", JsonPrimitive("Movement"))
                    put("followNum", JsonPrimitive(1))
                    put("followDen", JsonPrimitive(2))
                    put("followTargetUuid", JsonPrimitive(master2.uuid))
                }
            )
        }

        // A follow link is a reference like a stored effect's: deleting the leader would
        // silently re-time the follower, so it gates the delete the same way.
        assertEquals(1, client.masterAt(2).referenceCount)
        val del = client.delete("/api/rest/projects/$projectId/speed-masters/${master2.id}")
        assertEquals(HttpStatusCode.Conflict, del.status)
        val inUse = del.body<SpeedMasterInUseResponse>()
        assertEquals(CODE_SPEED_MASTER_IN_USE, inUse.code)
        assertEquals(listOf("Movement"), inUse.followerNames)

        val forced = client.delete("/api/rest/projects/$projectId/speed-masters/${master2.id}?force=true")
        assertEquals(HttpStatusCode.NoContent, forced.status)

        // The force unlinks the followers rather than leaving them naming a master that is
        // gone. Both halves of the ratio and the target go, so REST and the live bank tell the
        // same story: without this the desk runs the master manually (the bank degrades a
        // dangling leader) while the row still advertises a ratio, which hides TAP on the
        // manage page and 400s a ratio-chip press.
        val orphanRow = client.masterAt(3)
        assertNull(orphanRow.followNum, "a follower whose leader was force-deleted goes manual")
        assertNull(orphanRow.followDen)
        assertNull(orphanRow.followTargetUuid, "and stores no target naming the deleted master")
        val orphanLive = state.show.speedMasterBank.masterStates().single { it.name == "Movement" }
        assertNull(orphanLive.followNum, "the live bank must agree with the row")

        // Manual on the write path too, not merely in the reporting.
        val retune = client.put("/api/rest/projects/$projectId/speed-masters/${master3.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("bpm", JsonPrimitive(96.0)) })
        }
        assertEquals(HttpStatusCode.OK, retune.status, retune.bodyAsText())
        assertEquals(96.0, retune.body<SpeedMasterDto>().bpm)
    }

    @Test
    fun `a follower's live tempo tracks master 1 after a REST link`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val master2 = client.masterAt(2)

        val link = client.put("/api/rest/projects/$projectId/speed-masters/${master2.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("followNum", JsonPrimitive(1))
                    put("followDen", JsonPrimitive(2))
                }
            )
        }
        assertEquals(HttpStatusCode.OK, link.status, link.bodyAsText())

        // The PUT reloads the live bank (this project IS the live one), and load's sweep
        // derives the follower — proving the whole REST → reload → derive chain is wired.
        val live = state.show.speedMasterBank.masterStates().single { it.index == 2 }
        assertEquals(1, live.followNum)
        assertEquals(2, live.followDen)
        assertEquals(state.show.speedMasterBank.master1().bpm.value / 2, live.bpm)
    }
}
