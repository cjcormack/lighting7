package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The flatten-layer route — `POST /project/{projectId}/cues/{cueId}/flatten`.
 *
 * It replaces `projectCuesMakeHard.kt` and `projectFxPresetsMakeHard.kt`, **neither of which had a
 * single test anywhere**, so everything here is new coverage of re-implemented behaviour rather than
 * a port. Three things are pinned deliberately because they were the untested parts of the routes
 * being replaced: that a group-targeted layer comes out as per-fixture rows, that `moveInDark` on
 * the cue's own rows survives, and that the URL's `projectId` is actually honoured — the old route
 * passed a hardcoded `"current"`.
 */
class CueFlattenRouteTest : RouteIntegrationTest() {

    private fun flattenUrl(cueId: Int, project: Any = projectId) =
        "/api/rest/project/$project/cues/$cueId/flatten"

    // ─── Whole-stack flatten ────────────────────────────────────────────────

    @Test
    fun `flattening the stack writes the cooked values as local rows and drops the layers`() =
        testApplication {
            mountTestApp(state)
            seedHex("hex-1", startChannel = 1)
            val look = seedLook("Warm", mapOf("hex-1" to "#ff8800"))
            val cueId = seedCueWithLayer(look)

            val resp = jsonClient().post(flattenUrl(cueId)) {
                contentType(ContentType.Application.Json)
                setBody(CueFlattenRequest())
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body: CueFlattenResponse = resp.body()

            assertEquals(1, body.rowsWritten)
            assertEquals(1, body.layersRemoved)
            assertEquals(0, body.maskedOut)

            val rows = transaction(state.database) {
                DaoCue.findById(cueId)!!.propertyAssignments.map {
                    Triple(it.targetType, it.targetKey, it.value)
                }
            }
            assertEquals(
                listOf(Triple(TargetRef.Fixture.TYPE, "hex-1", "#ff8800")), rows,
                "the layer's cooked contribution is now a local row",
            )
            assertTrue(
                transaction(state.database) { DaoCue.findById(cueId)!!.layers.empty() },
                "the layer is gone — the cue no longer depends on the library",
            )
        }

    @Test
    fun `a later look edit no longer moves a flattened cue`() = testApplication {
        // The point of flattening, stated as a behaviour rather than a row count.
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)
        val look = seedLook("Warm", mapOf("hex-1" to "#ff8800"))
        val cueId = seedCueWithLayer(look)

        jsonClient().post(flattenUrl(cueId)) {
            contentType(ContentType.Application.Json)
            setBody(CueFlattenRequest())
        }
        transaction(state.database) {
            DaoLook.findById(look)!!.rows.forEach { it.value = "#0000ff" }
        }

        val rows = transaction(state.database) {
            DaoCue.findById(cueId)!!.propertyAssignments.map { it.value }
        }
        assertEquals(listOf("#ff8800"), rows, "the flattened literal is detached from the Look")
    }

    @Test
    fun `a group-targeted layer flattens to one row per member, not a group row`() = testApplication {
        // The behaviour that *changed* versus the route this replaces, so it is pinned rather than
        // left to be discovered: the old cue route could keep a group row when every member resolved
        // alike, because it rewrote a value in place. Cook's output is per fixture by construction
        // and carries no group name, so flatten always emits members.
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)
        seedHex("hex-2", startChannel = 20)
        LocateTestSupport.seedGroup(state, projectId, "front-wash", "hex-1", "hex-2")

        val look = seedLook("Warm", mapOf("hex-1" to "#ff8800", "hex-2" to "#ff8800"))
        val cueId = seedCueWithLayer(look)

        val body: CueFlattenResponse = jsonClient().post(flattenUrl(cueId)) {
            contentType(ContentType.Application.Json)
            setBody(CueFlattenRequest())
        }.body()

        assertEquals(2, body.rowsWritten)
        val rows = transaction(state.database) {
            DaoCue.findById(cueId)!!.propertyAssignments
                .map { it.targetType to it.targetKey }
                .sortedBy { it.second }
        }
        assertEquals(
            listOf(TargetRef.Fixture.TYPE to "hex-1", TargetRef.Fixture.TYPE to "hex-2"), rows,
            "identical literals still come out per fixture — no group row is invented",
        )
    }

    @Test
    fun `a local row's moveInDark survives the flatten untouched`() = testApplication {
        // The old route captured sortOrder / fadeDurationMs / moveInDark before deleting a row it
        // was expanding. Flatten never rewrites a local row, so the property holds for a different
        // reason — and that is worth a test, because it is the reason that could regress.
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)
        seedHex("hex-2", startChannel = 20)
        val look = seedLook("Warm", mapOf("hex-1" to "#ff8800"))
        val cueId = seedCueWithLayer(look)
        transaction(state.database) {
            DaoCuePropertyAssignment.new {
                cue = DaoCue.findById(cueId)!!
                targetType = TargetRef.Fixture.TYPE; targetKey = "hex-2"
                propertyName = "dimmer"; value = "200"; sortOrder = 0
                moveInDark = true
            }
        }

        jsonClient().post(flattenUrl(cueId)) {
            contentType(ContentType.Application.Json)
            setBody(CueFlattenRequest())
        }

        val preserved = transaction(state.database) {
            DaoCue.findById(cueId)!!.propertyAssignments.single { it.targetKey == "hex-2" }
        }
        assertEquals(true, transaction(state.database) { preserved.moveInDark })
    }

    // ─── Single-layer flatten, and the precedence guard ─────────────────────

    @Test
    fun `flattening the last layer takes only that layer`() = testApplication {
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)
        seedHex("hex-2", startChannel = 20)
        val lower = seedLook("Lower", mapOf("hex-1" to "#ff0000"))
        val upper = seedLook("Upper", mapOf("hex-2" to "#00ff00"))
        val cueId = seedCueWithLayer(lower)
        val upperLayerId = addLayer(cueId, upper, sortOrder = 1)

        val body: CueFlattenResponse = jsonClient().post(flattenUrl(cueId)) {
            contentType(ContentType.Application.Json)
            setBody(CueFlattenRequest(layerId = upperLayerId))
        }.body()

        assertEquals(1, body.rowsWritten)
        assertEquals(1, body.layersRemoved)
        val remaining = transaction(state.database) {
            DaoCue.findById(cueId)!!.layers.map { it.look.name }
        }
        assertEquals(listOf("Lower"), remaining, "only the named layer went")
    }

    @Test
    fun `flattening a middle layer is refused, because local rows would outrank the layers above`() =
        testApplication {
            // The correctness guard, and the one thing about this route that is not a port: local
            // rows beat every layer, so promoting a middle layer's values would change the cue's
            // output — the opposite of what flattening promises.
            mountTestApp(state)
            seedHex("hex-1", startChannel = 1)
            val lower = seedLook("Lower", mapOf("hex-1" to "#ff0000"))
            val upper = seedLook("Upper", mapOf("hex-1" to "#00ff00"))
            val cueId = seedCueWithLayer(lower)
            val lowerLayerId = transaction(state.database) {
                DaoCue.findById(cueId)!!.layers.single().id.value
            }
            addLayer(cueId, upper, sortOrder = 1)

            val resp = jsonClient().post(flattenUrl(cueId)) {
                contentType(ContentType.Application.Json)
                setBody(CueFlattenRequest(layerId = lowerLayerId))
            }
            assertEquals(HttpStatusCode.Conflict, resp.status)
            assertEquals(
                2, transaction(state.database) { DaoCue.findById(cueId)!!.layers.count() },
                "nothing was flattened",
            )
        }

    @Test
    fun `a disabled layer above does not block flattening, because it cannot win a key`() =
        testApplication {
            mountTestApp(state)
            seedHex("hex-1", startChannel = 1)
            val lower = seedLook("Lower", mapOf("hex-1" to "#ff0000"))
            val upper = seedLook("Upper", mapOf("hex-1" to "#00ff00"))
            val cueId = seedCueWithLayer(lower)
            val lowerLayerId = transaction(state.database) {
                DaoCue.findById(cueId)!!.layers.single().id.value
            }
            addLayer(cueId, upper, sortOrder = 1, enabled = false)

            val resp = jsonClient().post(flattenUrl(cueId)) {
                contentType(ContentType.Application.Json)
                setBody(CueFlattenRequest(layerId = lowerLayerId))
            }
            assertEquals(HttpStatusCode.OK, resp.status)
        }

    @Test
    fun `the cue read exposes each layer's id, so a client can address one`() = testApplication {
        // Without this the route's single-layer mode is **unreachable**: `lookId` is not unique (a
        // cue may layer the same Look twice) and array position is not identity when `sortOrder` is
        // authoritative. Found by driving the route against a desk — every other test in this file
        // reads the id straight from the database and so never noticed it wasn't on the wire.
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)
        val cueId = seedCueWithLayer(seedLook("Warm", mapOf("hex-1" to "#ff8800")))

        val details: CueDetails =
            jsonClient().get("/api/rest/project/$projectId/cues/$cueId").body()

        val layerId = details.layers.single().id
        assertNotNull(layerId, "a layer read back without an id cannot be flattened on its own")
        assertEquals(
            HttpStatusCode.OK,
            jsonClient().post(flattenUrl(cueId)) {
                contentType(ContentType.Application.Json)
                setBody(CueFlattenRequest(layerId = layerId))
            }.status,
            "the id the read handed out must be the one the route accepts",
        )
    }

    @Test
    fun `an unknown layer id is a 404`() = testApplication {
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)
        val look = seedLook("Warm", mapOf("hex-1" to "#ff8800"))
        val cueId = seedCueWithLayer(look)

        val resp = jsonClient().post(flattenUrl(cueId)) {
            contentType(ContentType.Application.Json)
            setBody(CueFlattenRequest(layerId = 99_999))
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    // ─── Mask ───────────────────────────────────────────────────────────────

    @Test
    fun `a mask copies only the named families down and leaves the layer in place`() = testApplication {
        // A masked flatten cannot delete the layer: it still carries the properties the mask
        // excluded, and dropping it would lose them silently.
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)
        val look = seedLook("Warm", mapOf("hex-1" to "#ff8800"))
        transaction(state.database) {
            DaoLookRow.new {
                this.look = DaoLook.findById(look)!!
                targetType = TargetRef.Fixture.TYPE; targetKey = "hex-1"
                propertyName = "dimmer"; value = "200"; sortOrder = 1
                uuid = UUID.randomUUID()
            }
        }
        val cueId = seedCueWithLayer(look)

        val body: CueFlattenResponse = jsonClient().post(flattenUrl(cueId)) {
            contentType(ContentType.Application.Json)
            setBody(CueFlattenRequest(mask = listOf("COLOUR")))
        }.body()

        assertEquals(1, body.rowsWritten, "only the colour came down")
        assertTrue(body.maskedOut > 0, "the intensity row was excluded")
        assertEquals(0, body.layersRemoved, "the layer survives because it still owns the intensity")
    }

    @Test
    fun `a bad mask is a 400`() = testApplication {
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)
        val cueId = seedCueWithLayer(seedLook("Warm", mapOf("hex-1" to "#ff8800")))

        val resp = jsonClient().post(flattenUrl(cueId)) {
            contentType(ContentType.Application.Json)
            setBody(CueFlattenRequest(mask = listOf("NOT_A_FAMILY")))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // ─── Project addressing — the bug the old route had ─────────────────────

    @Test
    fun `a cue id that is not in the addressed project is a 404, not a silent edit`() = testApplication {
        // `handleMakeCueHard` passed a hardcoded `"current"` and never read the URL's projectId, so
        // a request naming another project resolved the current one instead — answering 404 where
        // 409 was intended, and, when the cue *was* in the current project, editing a cue the caller
        // had not addressed. This pins that the URL is honoured.
        mountTestApp(state)
        seedHex("hex-1", startChannel = 1)
        val cueId = seedCueWithLayer(seedLook("Warm", mapOf("hex-1" to "#ff8800")))

        val resp = jsonClient().post(flattenUrl(cueId, project = 999_999)) {
            contentType(ContentType.Application.Json)
            setBody(CueFlattenRequest())
        }
        assertTrue(
            resp.status == HttpStatusCode.NotFound || resp.status == HttpStatusCode.Conflict,
            "expected the request to be refused, got ${resp.status}",
        )
        assertEquals(
            1, transaction(state.database) { DaoCue.findById(cueId)!!.layers.count() },
            "the cue in the current project must not have been touched",
        )
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private fun seedHex(key: String, startChannel: Int) =
        LocateTestSupport.seedHex(state, projectId, key, startChannel)

    /** A bound Look with one colour row per entry of [rows]. Returns its id. */
    private fun seedLook(name: String, rows: Map<String, String>): Int =
        transaction(state.database) {
            val look = DaoLook.new {
                project = DaoProject.findById(projectId)!!
                this.name = name
                sortOrder = 0
            }
            rows.entries.forEachIndexed { index, (fixtureKey, hex) ->
                DaoLookRow.new {
                    this.look = look
                    targetType = TargetRef.Fixture.TYPE; targetKey = fixtureKey
                    propertyName = "colour"; value = hex; sortOrder = index
                    uuid = UUID.randomUUID()
                }
            }
            look.id.value
        }

    /** A cue in its own stack, carrying one layer over [lookId]. Returns the cue id. */
    private fun seedCueWithLayer(lookId: Int): Int = transaction(state.database) {
        val project = DaoProject.findById(projectId)!!
        val stack = DaoCueStack.new {
            this.project = project
            name = "stack-${System.nanoTime()}"; palette = emptyList(); loop = false
            type = CueStackType.STACK.name; sortOrder = 0
        }
        val cue = DaoCue.new {
            this.project = project
            name = "cue"; cueStack = stack; sortOrder = 0
            palette = emptyList(); cueType = CueType.STANDARD.name
        }
        DaoCueLayer.new {
            this.cue = cue
            look = DaoLook.findById(lookId)!!
            sortOrder = 0
            targets = emptyList()
        }
        cue.id.value
    }

    private fun addLayer(cueId: Int, lookId: Int, sortOrder: Int, enabled: Boolean = true): Int =
        transaction(state.database) {
            DaoCueLayer.new {
                cue = DaoCue.findById(cueId)!!
                look = DaoLook.findById(lookId)!!
                this.sortOrder = sortOrder
                this.enabled = enabled
                targets = emptyList()
            }.id.value
        }
}
