package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip the five "Patch with Stage" fields (`stageX`, `stageY`,
 * `riggingPosition`, `beamAngleDeg`, `gelCode`) through
 *   POST /patches  →  GET /patches/{id}  →  PUT /patches/{id} (set)
 *   →  GET /patches/{id}  →  PUT /patches/{id} (clear via null)
 *   →  GET /patches/{id}.
 *
 * Also exercises the validation paths (out-of-range stageX, beamAngleDeg) and the
 * normalisation of `riggingPosition` (uppercase) and `gelCode` (trim).
 */
class PatchStageMetadataRoundTripTest : RouteIntegrationTest() {

    @Test
    fun `stage metadata round-trips through POST, GET, PUT (set then clear)`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val createResp = client.post("/api/rest/project/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(
                CreatePatchRequest(
                    universe = 0,
                    fixtureTypeKey = "generic-dimmer",
                    key = "dim-1",
                    name = "Dimmer 1",
                    startChannel = 1,
                    stageX = 25.0,
                    stageY = 75.5,
                    riggingPosition = "lx1",
                    beamAngleDeg = 36,
                    gelCode = "  L201  ",
                )
            )
        }
        assertEquals(HttpStatusCode.Created, createResp.status, createResp.bodyAsText())
        val created = createResp.body<FixturePatchDto>()
        assertEquals(25.0, created.stageX)
        assertEquals(75.5, created.stageY)
        assertEquals("LX1", created.riggingPosition, "riggingPosition normalises to uppercase + trim")
        assertEquals(36, created.beamAngleDeg)
        assertEquals("L201", created.gelCode, "gelCode trims surrounding whitespace")

        val patchId = created.id

        val getResp = client.get("/api/rest/project/$projectId/patches/$patchId")
        assertEquals(HttpStatusCode.OK, getResp.status)
        val fetched = getResp.body<FixturePatchDto>()
        assertEquals(25.0, fetched.stageX)
        assertEquals(75.5, fetched.stageY)
        assertEquals("LX1", fetched.riggingPosition)
        assertEquals(36, fetched.beamAngleDeg)
        assertEquals("L201", fetched.gelCode)

        val listed = client.get("/api/rest/project/$projectId/patches")
            .body<List<FixturePatchDto>>()
        val listedDim = listed.firstOrNull { it.id == patchId }
        assertNotNull(listedDim, "list should include the created patch")
        assertEquals("LX1", listedDim.riggingPosition)
        assertEquals("L201", listedDim.gelCode)

        // PUT replaces stage metadata. Keys not present must be left unchanged —
        // we change only stageX, riggingPosition, gelCode and verify stageY +
        // beamAngleDeg survive untouched.
        val putSet = client.put("/api/rest/project/$projectId/patches/$patchId") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("stageX", JsonPrimitive(10.0))
                put("riggingPosition", JsonPrimitive("foh"))
                put("gelCode", JsonPrimitive("R26"))
            })
        }
        assertEquals(HttpStatusCode.OK, putSet.status, putSet.bodyAsText())
        val updated = putSet.body<FixturePatchDto>()
        assertEquals(10.0, updated.stageX)
        assertEquals(75.5, updated.stageY, "stageY must survive an unrelated PUT")
        assertEquals("FOH", updated.riggingPosition)
        assertEquals(36, updated.beamAngleDeg, "beamAngleDeg must survive an unrelated PUT")
        assertEquals("R26", updated.gelCode)

        // PUT with explicit JSON null clears the fields.
        val putClear = client.put("/api/rest/project/$projectId/patches/$patchId") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("stageX", JsonNull)
                put("stageY", JsonNull)
                put("riggingPosition", JsonNull)
                put("beamAngleDeg", JsonNull)
                put("gelCode", JsonNull)
            })
        }
        assertEquals(HttpStatusCode.OK, putClear.status, putClear.bodyAsText())
        val cleared = putClear.body<FixturePatchDto>()
        assertNull(cleared.stageX)
        assertNull(cleared.stageY)
        assertNull(cleared.riggingPosition)
        assertNull(cleared.beamAngleDeg)
        assertNull(cleared.gelCode)

        // Final GET confirms the cleared state was persisted.
        val finalGet = client.get("/api/rest/project/$projectId/patches/$patchId")
            .body<FixturePatchDto>()
        assertNull(finalGet.stageX)
        assertNull(finalGet.stageY)
        assertNull(finalGet.riggingPosition)
        assertNull(finalGet.beamAngleDeg)
        assertNull(finalGet.gelCode)
    }

    @Test
    fun `stage metadata validation rejects out-of-range values`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val createOk = client.post("/api/rest/project/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(
                CreatePatchRequest(
                    universe = 0,
                    fixtureTypeKey = "generic-dimmer",
                    key = "dim-validate",
                    name = "Dimmer Validate",
                    startChannel = 5,
                )
            )
        }
        assertEquals(HttpStatusCode.Created, createOk.status, createOk.bodyAsText())
        val patchId = createOk.body<FixturePatchDto>().id

        // stageX out of range on PUT
        val badStageX = client.put("/api/rest/project/$projectId/patches/$patchId") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("stageX", JsonPrimitive(150.0)) })
        }
        assertEquals(HttpStatusCode.BadRequest, badStageX.status)
        assertTrue(badStageX.bodyAsText().contains("stageX"), "error should mention stageX")

        // beamAngleDeg below the floor on PUT
        val badBeam = client.put("/api/rest/project/$projectId/patches/$patchId") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("beamAngleDeg", JsonPrimitive(1)) })
        }
        assertEquals(HttpStatusCode.BadRequest, badBeam.status)
        assertTrue(badBeam.bodyAsText().contains("beamAngleDeg"), "error should mention beamAngleDeg")

        // beamAngleDeg above the ceiling on POST
        val badPost = client.post("/api/rest/project/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(
                CreatePatchRequest(
                    universe = 0,
                    fixtureTypeKey = "generic-dimmer",
                    key = "dim-bad",
                    name = "Dimmer Bad",
                    startChannel = 6,
                    beamAngleDeg = 200,
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, badPost.status)
        assertTrue(badPost.bodyAsText().contains("beamAngleDeg"))

        // The original patch must still be intact and field-free.
        val final = client.get("/api/rest/project/$projectId/patches/$patchId")
            .body<FixturePatchDto>()
        assertNull(final.stageX)
        assertNull(final.beamAngleDeg)
    }

    @Test
    fun `fixture types endpoint exposes acceptsBeamAngle and acceptsGel`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val types = client.get("/api/rest/fixture/types").body<List<FixtureTypeDetails>>()
        val genericDimmer = types.firstOrNull { it.typeKey == "generic-dimmer" }
        assertNotNull(genericDimmer, "Generic Dimmer fixture type must be registered")
        assertTrue(genericDimmer.acceptsBeamAngle, "generic-dimmer should accept a beam angle")
        assertTrue(genericDimmer.acceptsGel, "generic-dimmer should accept a gel")

        // A non-conventional fixture (e.g. the LED Hex) must default to false on both flags.
        val hex = types.firstOrNull { it.typeKey == "hex" }
        assertNotNull(hex, "hex fixture type expected in registry")
        assertEquals(false, hex.acceptsBeamAngle)
        assertEquals(false, hex.acceptsGel)
    }
}
