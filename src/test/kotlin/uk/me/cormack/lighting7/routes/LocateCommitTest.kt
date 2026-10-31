package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.CueAssignmentResolver.AttributeValue
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.PaletteRowDto
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.programmerChannel
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locate as a starting point (`show/LocateCommit.kt`): an edit to a located fixture makes its Locate
 * the operator's values and ends the Locate, with nothing on stage moving but what the edit set; an
 * untouched located fixture keeps its toggle.
 *
 * Hex at channel 1: dimmer 1, R/G/B 2/3/4, white 6, strobe 8. At 20: dimmer 20.
 */
class LocateCommitTest : RouteIntegrationTest() {

    @Test
    fun `an operator write to a located fixture keeps the locate as programmer values and ends it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-a", 1)
        assertTrue(toggle(client, "fixture", "hex-a").active)

        write(ProgrammerOwner.WEB, "hex-a", "dimmer", 100u)

        assertTrue(located(client).isEmpty(), "the edit ends the locate")
        assertEquals(100u.toUByte(), programmerChannel(state, 0, 1), "the edit lands")
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 2), "the locate's red stays")
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 6), "the locate's white stays")
        assertTrue(lockedOwners("hex-a").all { it == ProgrammerOwner.WEB }, "every value is the operator's now")

        // A Locate pressed now is a fresh one; letting it go reveals the operator's values.
        assertTrue(toggle(client, "fixture", "hex-a").active)
        assertFalse(toggle(client, "fixture", "hex-a").active)
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 2), "the committed red survives a later locate")
    }

    @Test
    fun `a surface fader commits, and a flash does not`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-a", 1)
        assertTrue(toggle(client, "fixture", "hex-a").active)

        write(ProgrammerOwner.FLASH, "hex-a", "dimmer", 50u)
        assertEquals(listOf(LocateTargetDto("fixture", "hex-a")), located(client), "a flash is momentary")
        state.show.fxEngine.programmer.clearAttribute(ProgrammerOwner.FLASH, fixture("hex-a"), "dimmer")

        write(ProgrammerOwner.SURFACE, "hex-a", "dimmer", 50u)
        assertTrue(located(client).isEmpty())
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 2))
    }

    @Test
    fun `an edit to one fixture leaves another located`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-a", 1)
        seedHex("hex-b", 20)
        assertTrue(toggle(client, "fixture", "hex-a").active)
        assertTrue(toggle(client, "fixture", "hex-b").active)

        write(ProgrammerOwner.WEB, "hex-a", "dimmer", 100u)

        assertEquals(listOf(LocateTargetDto("fixture", "hex-b")), located(client))
        assertFalse(toggle(client, "fixture", "hex-b").active)
        assertEquals(null, programmerChannel(state, 0, 20), "hex-b's locate still toggles off")
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 2), "hex-a's committed values stay")
    }

    @Test
    fun `an edit to one member commits the group's locate and any locate overlapping it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-a", 1)
        seedHex("hex-b", 20)
        seedHex("hex-c", 40)
        LocateTestSupport.seedGroup(state, projectId, "band", "hex-b", "hex-c")
        assertTrue(toggle(client, "fixture", "hex-b").active)
        assertTrue(toggle(client, "group", "band").active)
        assertTrue(toggle(client, "fixture", "hex-a").active)

        write(ProgrammerOwner.WEB, "hex-c", "dimmer", 10u)

        assertEquals(listOf(LocateTargetDto("fixture", "hex-a")), located(client), "band and hex-b go together")
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 20), "hex-b's locate values kept")
        assertTrue(lockedOwners("hex-b").all { it == ProgrammerOwner.WEB })
    }

    @Test
    fun `a palette pressed onto a located fixture shows, and the rest of the locate is kept`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-a", 1)
        state.show.fixtures.patchListChanged()
        val blue = client.createPalette("Blue", "#0000ff")
        assertTrue(toggle(client, "fixture", "hex-a").active)

        client.press(blue, "hex-a")

        assertTrue(located(client).isEmpty())
        assertEquals(0u.toUByte(), programmerChannel(state, 0, 2), "red off — the palette shows")
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 4), "blue on")
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 1), "the locate's dimmer kept")
        assertEquals(ProgrammerOwner.WEB, state.show.programmerStore.get("hex-a", "dimmer")?.owner)
    }

    @Test
    fun `a press over a value the locate was hiding shows the press`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-a", 1)
        state.show.fixtures.patchListChanged()
        val red = client.createPalette("Red", "#ff0000")
        val blue = client.createPalette("Blue", "#0000ff")
        val apply = client.post("/api/rest/projects/$projectId/palettes/$red/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyPaletteRequest(targets = listOf(PaletteTargetDto("fixture", "hex-a"))))
        }
        assertEquals(HttpStatusCode.OK, apply.status, apply.bodyAsText())
        assertTrue(toggle(client, "fixture", "hex-a").active)

        client.press(blue, "hex-a")

        assertTrue(located(client).isEmpty())
        assertEquals(0u.toUByte(), programmerChannel(state, 0, 2), "the hidden red does not resurface")
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 4), "blue shows")
    }

    @Test
    fun `a layer that changes under a press made elsewhere leaves a locate alone`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-a", 1)
        seedHex("hex-b", 20)
        state.show.fixtures.patchListChanged()
        val amber = client.createLook("Amber A", LookRowDto("fixture", "hex-a", "rgbColour", "#ffbf00"))
        val other = client.createLook("Dim B", LookRowDto("fixture", "hex-b", "dimmer", "200"))
        val red = client.createPalette("Red", "#ff0000")
        val stack = state.show.programmerLayerStack
        stack.toggle(amber, emptyList())
        client.press(red, "hex-a")
        assertTrue(toggle(client, "fixture", "hex-a").active)

        // A solo bank's Look press takes Red off whole, so hex-a's layer value moves to amber, but
        // nothing was pressed onto hex-a.
        stack.toggle(other, emptyList(), releaseSiblings = setOf(paletteUuid(client, red)))

        assertEquals(listOf(LocateTargetDto("fixture", "hex-a")), located(client))
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 6), "still Locate's white")
    }

    @Test
    fun `widening a layer onto a located fixture ends its locate and shows the layer`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-a", 1)
        seedHex("hex-b", 20)
        state.show.fixtures.patchListChanged()
        val blue = client.createPalette("Blue", "#0000ff")
        client.press(blue, "hex-b")
        assertTrue(toggle(client, "fixture", "hex-a").active)

        val layer = state.show.programmerStore.layers.single()
        state.show.programmerLayerStack.patch(
            layer.layerId,
            targets = listOf(CueTargetDto("fixture", "hex-a"), CueTargetDto("fixture", "hex-b")),
        )

        assertTrue(located(client).isEmpty())
        assertEquals(0u.toUByte(), programmerChannel(state, 0, 2))
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 4), "the widened palette shows on hex-a")
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 1), "the locate's dimmer kept")
    }

    @Test
    fun `widening a pressed palette onto another fixture leaves a located one it already held alone`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-a", 1)
        seedHex("hex-b", 20)
        state.show.fixtures.patchListChanged()
        val red = client.createPalette("Red", "#ff0000")
        client.press(red, "hex-a")
        assertTrue(toggle(client, "fixture", "hex-a").active)

        client.press(red, "hex-b")

        assertEquals(1, state.show.programmerStore.layers.size, "the press widened the one layer")
        assertEquals(listOf(LocateTargetDto("fixture", "hex-a")), located(client))
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 6), "hex-a keeps Locate's white")
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 21), "hex-b is red")
    }

    @Test
    fun `a Look landing only on an attribute the locate never wrote still ends it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-a", 1)
        state.show.fixtures.patchListChanged()
        val speed = client.createLook("Speed", LookRowDto("fixture", "hex-a", "programSpeed", "90"))
        assertTrue(toggle(client, "fixture", "hex-a").active)
        assertTrue(state.show.programmerStore.slotsFor("hex-a", "programSpeed").isEmpty(), "Locate leaves the program speed alone")

        state.show.programmerLayerStack.toggle(speed, emptyList())

        assertTrue(located(client).isEmpty())
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 2), "the locate's red kept")
        assertTrue(lockedOwners("hex-a").all { it == ProgrammerOwner.WEB })
    }

    @Test
    fun `a raw channel write a fixture attribute covers ends the locate`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-a", 1)
        assertTrue(toggle(client, "fixture", "hex-a").active)

        state.show.fxEngine.programmer.writeChannel(
            ProgrammerOwner.WEB, 0, 1, 90u, coveringKey = CueAssignmentResolver.Key.fixture("hex-a", "dimmer"),
        )

        assertTrue(located(client).isEmpty())
        assertEquals(255u.toUByte(), programmerChannel(state, 0, 2), "the locate's red kept")
        assertTrue(lockedOwners("hex-a").all { it == ProgrammerOwner.WEB })
    }

    private fun write(owner: ProgrammerOwner, key: String, attribute: String, value: UByte) {
        state.show.fxEngine.programmer.writeAttribute(owner, fixture(key), attribute, AttributeValue.Slider(value))
    }

    private fun fixture(key: String) = state.show.fixtures.untypedGroupableFixture(key)

    /** The owner on top of every attribute the programmer holds on [key], the layer stack's excepted. */
    private fun lockedOwners(key: String) = state.show.programmerStore.entries()
        .filter { it.fixtureKey == key }
        .mapNotNull { state.show.programmerStore.get(it.fixtureKey, it.attribute)?.owner }
        .filter { it != ProgrammerOwner.LAYERS }
        .also { assertTrue(it.isNotEmpty(), "the programmer holds $key") }

    private suspend fun located(client: HttpClient): List<LocateTargetDto> =
        client.get("/api/rest/locate").body<LocateStateResponse>().targets

    private suspend fun toggle(client: HttpClient, type: String, key: String): ToggleLocateResponse =
        LocateTestSupport.toggleLocate(client, type, key).body()

    private fun seedHex(key: String, startChannel: Int) =
        LocateTestSupport.seedHex(state, projectId, key, startChannel)

    private suspend fun HttpClient.createPalette(name: String, value: String): Int {
        val resp = post("/api/rest/projects/$projectId/palettes") {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = name, rows = listOf(PaletteRowDto(DEFERRED_TARGET_TYPE, "", "rgbColour", value))))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<PaletteDto>().id
    }

    private suspend fun HttpClient.press(palette: Int, vararg fixtureKeys: String) {
        val resp = post("/api/rest/projects/$projectId/palettes/$palette/toggle") {
            contentType(ContentType.Application.Json)
            setBody(TogglePaletteRequest(targets = fixtureKeys.map { PaletteTargetDto("fixture", it) }))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
    }

    private suspend fun HttpClient.createLook(name: String, vararg rows: LookRowDto): LayerSource {
        val resp = post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = name, rows = rows.toList()))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        val look = resp.body<LookDetails>()
        return LayerSource.look(look.id, UUID.fromString(look.uuid), look.name)
    }

    private suspend fun paletteUuid(client: HttpClient, id: Int): UUID =
        UUID.fromString(client.get("/api/rest/projects/$projectId/palettes/$id").body<PaletteDto>().uuid)
}
