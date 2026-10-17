package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.LookEffectDto
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.TemplateRowDto
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `POST /cues/{id}/layers/press` (apply-model plan §3.6): the programmer's press over a **stored**
 * cue's layers — per target, the family's other palettes replaced, the top layer widened, a second
 * press taking it off, the mask tested on the on arm only — written to the cue and sent nowhere.
 */
class CueLayerPressRouteTest : RouteIntegrationTest() {

    private val base get() = "/api/rest/projects/$projectId"

    private fun seedRig() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        state.show.fixtures.patchListChanged()
    }

    private suspend fun HttpClient.palette(name: String, value: String = "#ff0000", propertyName: String = "rgbColour"): Int {
        val resp = post("$base/palettes") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = name, rows = listOf(TemplateRowDto(DEFERRED_TARGET_TYPE, "", propertyName, value))))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<TemplateDto>().id
    }

    private suspend fun HttpClient.look(name: String, vararg keys: String, deferredEffect: Boolean = false): Int {
        val resp = post("$base/looks") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = name,
                    rows = keys.flatMap { listOf(LookRowDto("fixture", it, "dimmer", "200"), LookRowDto("fixture", it, "rgbColour", "#00ff00")) },
                    effects = if (!deferredEffect) emptyList() else listOf(
                        LookEffectDto(
                            targetType = DEFERRED_TARGET_TYPE, targetKey = "",
                            effectType = "Pulse", category = "dimmer", propertyName = "dimmer",
                            beatDivision = 0.5, blendMode = "OVERRIDE", distribution = "LINEAR",
                        ),
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<LookDetails>().id
    }

    private suspend fun HttpClient.cue(name: String, stackId: Int? = null): Int {
        val resp = post("$base/cues") {
            contentType(ContentType.Application.Json)
            setBody(
                NewCue(
                    name = name,
                    cueStackId = stackId,
                    propertyAssignments = listOf(CuePropertyAssignmentDto(TargetRef.Fixture.TYPE, "hex-1", "dimmer", "128")),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<CueDetails>().id
    }

    private suspend fun HttpClient.pressRaw(cueId: Int, body: CueLayerPressRequest): HttpResponse =
        post("$base/cues/$cueId/layers/press") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.press(
        cueId: Int,
        templateId: Int? = null,
        lookId: Int? = null,
        vararg keys: String,
        families: List<String>? = null,
    ): CueLayerPressResponse {
        val resp = pressRaw(cueId, CueLayerPressRequest(templateId, lookId, keys.map { CueTargetDto("fixture", it) }, families))
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    /** The cue's stored layers as `name: heads`, bottom first. */
    private suspend fun HttpClient.layers(cueId: Int): List<String> =
        get("$base/cues/$cueId").body<CueDetails>().layers.map { layer ->
            "${layer.source?.name}: ${layer.targets.joinToString(",") { it.key }}"
        }

    @Test
    fun `a palette pressed into a cue adds a layer, and Blue replaces Red on the pressed heads`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val red = client.palette("Red")
        val blue = client.palette("Blue", "#0000ff")
        val q = client.cue("Q")

        val on = client.press(q, templateId = red, keys = arrayOf("hex-1", "hex-2"))
        assertEquals("applied", on.action)
        assertEquals("COLOUR", on.propertyMask)
        assertEquals(listOf("Red: hex-1,hex-2"), on.cue.layers.map { "${it.source?.name}: ${it.targets.joinToString(",") { t -> t.key }}" })
        val redId = on.cue.layers.single().id

        val replaced = client.press(q, templateId = blue, keys = arrayOf("hex-1"))
        assertEquals(1, replaced.released)
        assertEquals(listOf("Red: hex-2", "Blue: hex-1"), client.layers(q))
        assertEquals(redId, replaced.cue.layers.first().id, "a narrowed layer keeps its row")

        // Red pressed back on hex-1 takes Blue's only head, which leaves Red's layer the top: it widens.
        client.press(q, templateId = red, keys = arrayOf("hex-1"))
        assertEquals(listOf("Red: hex-2,hex-1"), client.layers(q))
    }

    @Test
    fun `a second press takes it off, and a press on the top layer widens it`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val red = client.palette("Red")
        val q = client.cue("Q")

        client.press(q, templateId = red, keys = arrayOf("hex-1"))
        client.press(q, templateId = red, keys = arrayOf("hex-2"))
        assertEquals(listOf("Red: hex-1,hex-2"), client.layers(q), "the top layer widens")

        val off = client.press(q, templateId = red, keys = arrayOf("hex-1"))
        assertEquals("removed", off.action)
        assertEquals(listOf("Red: hex-2"), client.layers(q))
        assertEquals("removed", client.press(q, templateId = red, keys = arrayOf("hex-2")).action)
        assertTrue(client.layers(q).isEmpty())
    }

    @Test
    fun `the mask refuses a palette on the on arm only`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val red = client.palette("Red")
        val q = client.cue("Q")

        val refused = client.pressRaw(q, CueLayerPressRequest(templateId = red, targets = listOf(CueTargetDto("fixture", "hex-1")), families = listOf("INTENSITY")))
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue(refused.bodyAsText().contains(CODE_TEMPLATE_OUTSIDE_MASK))

        client.press(q, templateId = red, keys = arrayOf("hex-1"))
        val off = client.press(q, templateId = red, keys = arrayOf("hex-1"), families = listOf("INTENSITY"))
        assertEquals("removed", off.action, "an off press comes off under any mask")

        val none = client.pressRaw(q, CueLayerPressRequest(templateId = red))
        assertEquals(HttpStatusCode.BadRequest, none.status)
        assertTrue(none.bodyAsText().contains(CODE_TEMPLATE_NEEDS_SELECTION))
    }

    @Test
    fun `a Look goes on masked, and on its own fixtures as a layer naming none`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.look("Warm", "hex-1", "hex-2")
        val chase = client.look("Chase", "hex-1", deferredEffect = true)
        val q = client.cue("Q")

        val masked = client.press(q, lookId = warm, keys = arrayOf("hex-1"), families = listOf("COLOUR"))
        assertEquals("COLOUR", masked.propertyMask)
        assertEquals(listOf("INTENSITY"), masked.skippedFamilies)

        val own = client.press(q, lookId = warm)
        assertEquals("applied", own.action)
        assertEquals(listOf("Warm: hex-1", "Warm: "), client.layers(q))
        assertEquals("removed", client.press(q, lookId = warm).action, "the same gesture again takes it off")
        assertEquals(listOf("Warm: hex-1"), client.layers(q))

        val needs = client.pressRaw(q, CueLayerPressRequest(lookId = chase))
        assertEquals(HttpStatusCode.BadRequest, needs.status)
        assertTrue(needs.bodyAsText().contains(CODE_LOOK_NEEDS_SELECTION))
    }

    @Test
    fun `a Look on its own fixtures reads as covering them, so a press on one of them narrows it`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.look("Warm", "hex-1", "hex-2")
        val q = client.cue("Q")

        client.press(q, lookId = warm)
        val off = client.press(q, lookId = warm, keys = arrayOf("hex-1"))
        assertEquals("removed", off.action)
        assertEquals(listOf("Warm: hex-2"), client.layers(q))
    }

    @Test
    fun `a Look pressed with no targets comes off layers already on all its own fixtures`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.look("Warm", "hex-1", "hex-2")
        val q = client.cue("Q")
        client.press(q, lookId = warm, keys = arrayOf("hex-1", "hex-2"))

        assertEquals("removed", client.press(q, lookId = warm).action)
        assertTrue(client.layers(q).isEmpty())
    }

    @Test
    fun `a Look that can no longer be pressed with no targets still comes off that way`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.look("Warm", "hex-1")
        val q = client.cue("Q")
        client.press(q, lookId = warm)

        val edit = client.put("$base/looks/$warm") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "effects" to listOf(
                        LookEffectDto(
                            targetType = DEFERRED_TARGET_TYPE, targetKey = "",
                            effectType = "Pulse", category = "dimmer", propertyName = "dimmer",
                            beatDivision = 0.5, blendMode = "OVERRIDE", distribution = "LINEAR",
                        ),
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, edit.status, edit.bodyAsText())

        assertEquals("removed", client.press(q, lookId = warm).action)
        assertTrue(client.layers(q).isEmpty())
        val again = client.pressRaw(q, CueLayerPressRequest(lookId = warm))
        assertEquals(HttpStatusCode.BadRequest, again.status)
        assertTrue(again.bodyAsText().contains(CODE_LOOK_NEEDS_SELECTION))
    }

    @Test
    fun `a target that is neither a fixture nor a group is refused`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val red = client.palette("Red")
        val q = client.cue("Q")

        val refused = client.pressRaw(q, CueLayerPressRequest(templateId = red, targets = listOf(CueTargetDto("selection", "[]"))))
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue(client.layers(q).isEmpty())
    }

    @Test
    fun `a press into a live cue sends nothing and marks it edited since GO`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val half = client.palette("Half", "pct:50", propertyName = "dimmer")
        val stackId = client.post("$base/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "Main"))
        }.body<CueStackDetails>().id
        val q = client.cue("Q", stackId)
        val entry = client.get("$base/cues/$q").body<CueDetails>().entries.single().entryId
        state.show.cueStackManager.activateEntry(state, stackId, entry)
        val controller = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController
        val before = controller.currentValues.toMap()

        client.press(q, templateId = half, keys = arrayOf("hex-1", "hex-2"))

        assertEquals(before, controller.currentValues.toMap(), "the stage did not move")
        assertTrue(state.show.programmerStore.layers.isEmpty(), "the programmer was not touched")
        assertTrue(state.show.cueStackManager.holds.isEdited(q))
    }
}
