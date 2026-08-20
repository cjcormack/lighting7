package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fx.paletteRefValue
import uk.me.cormack.lighting7.models.FxPresetPropertyAssignmentDto
import uk.me.cormack.lighting7.models.PaletteEntryDto
import uk.me.cormack.lighting7.models.PaletteType
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Preset-level Make Hard.
 *
 * The whole point of these cases is the target-less rule: a preset row hardens only when the
 * palette gives every candidate fixture the *same* literal, and reports the disagreement
 * otherwise. Candidate = a patched fixture whose type key matches the preset's declared
 * `fixtureType`, which is why several tests differ only in which fixtures are patched.
 */
class FxPresetMakeHardTest : RouteIntegrationTest() {

    private fun colourEntry(targetKey: String, value: String, sortOrder: Int = 0) = PaletteEntryDto(
        targetType = "fixture",
        targetKey = targetKey,
        propertyName = "colour",
        value = value,
        sortOrder = sortOrder,
    )

    private suspend fun io.ktor.client.HttpClient.createPalette(
        name: String,
        vararg entries: PaletteEntryDto,
    ): PaletteDetails {
        val resp = post("/api/rest/project/$projectId/palettes") {
            contentType(ContentType.Application.Json)
            setBody(
                CreatePaletteRequest(
                    name = name,
                    type = PaletteType.COLOUR.name,
                    entries = entries.toList(),
                )
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun io.ktor.client.HttpClient.createPreset(
        fixtureType: String,
        vararg assignments: FxPresetPropertyAssignmentDto,
    ): FxPresetDetails {
        val resp = post("/api/rest/project/$projectId/fx-presets") {
            contentType(ContentType.Application.Json)
            setBody(
                NewFxPreset(
                    name = "look-${System.nanoTime()}",
                    description = null,
                    fixtureType = fixtureType,
                    effects = emptyList(),
                    propertyAssignments = assignments.toList(),
                )
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun io.ktor.client.HttpClient.makeHard(
        presetId: Int,
        request: PresetMakeHardRequest = PresetMakeHardRequest(),
    ): PresetMakeHardResponse {
        val resp = post("/api/rest/project/$projectId/fx-presets/$presetId/make-hard") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private fun colourRef(paletteUuid: String, elementKey: String? = null) =
        FxPresetPropertyAssignmentDto(
            propertyName = "colour",
            value = paletteRefValue(UUID.fromString(paletteUuid)),
            elementKey = elementKey,
        )

    @Test
    fun `a palette that agrees across the preset's fixture type hardens the row`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)

        val palette = client.createPalette(
            "Warm",
            colourEntry("hex-1", "#ff8800", 0),
            colourEntry("hex-2", "#ff8800", 1),
        )
        val preset = client.createPreset("hex", colourRef(palette.uuid))

        val body = client.makeHard(preset.id)
        assertEquals(1, body.converted)
        assertTrue(body.ambiguous.isEmpty(), "expected no ambiguity, got ${body.ambiguous}")
        assertEquals(0, body.unresolved)
        assertEquals("#ff8800", body.preset.propertyAssignments.single().value)

        // And it has stopped tracking: the palette is now deletable.
        assertEquals(
            0,
            client.get("/api/rest/project/$projectId/palettes/${palette.id}")
                .body<PaletteDetails>().referenceCount,
        )
    }

    @Test
    fun `a palette whose fixtures disagree leaves the row a reference and reports both`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)

        val palette = client.createPalette(
            "Specials",
            colourEntry("hex-1", "#ff8800", 0),
            colourEntry("hex-2", "#00ff00", 1),
        )
        val preset = client.createPreset("hex", colourRef(palette.uuid))

        val body = client.makeHard(preset.id)
        assertEquals(0, body.converted)
        assertEquals(0, body.unresolved)
        val ambiguity = body.ambiguous.single()
        // The row's stored name, so it matches what the preset editor lists.
        assertEquals("colour", ambiguity.propertyName)
        assertEquals(palette.uuid, ambiguity.paletteUuid)
        assertEquals("Specials", ambiguity.paletteName)
        assertEquals(
            setOf("#ff8800" to listOf("hex-1"), "#00ff00" to listOf("hex-2")),
            ambiguity.variants.map { it.literal to it.fixtureKeys }.toSet(),
        )
        // The row is untouched, so the reference still counts against the palette's delete guard.
        assertTrue(
            body.preset.propertyAssignments.single().value.startsWith("ref:"),
            "row should still be a reference",
        )
    }

    @Test
    fun `differently-cased literals are one answer, because the comparison is on the parsed value`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
            LocateTestSupport.seedHex(state, projectId, "hex-2", 13)

            val palette = client.createPalette(
                "Warm",
                colourEntry("hex-1", "#ff8800", 0),
                colourEntry("hex-2", "#FF8800", 1),
            )
            val preset = client.createPreset("hex", colourRef(palette.uuid))

            val body = client.makeHard(preset.id)
            assertEquals(1, body.converted, "raw strings differ but the colours don't")
            assertTrue(body.ambiguous.isEmpty(), "expected no ambiguity, got ${body.ambiguous}")
        }

    @Test
    fun `a palette covering no fixture of the preset's declared type is unresolved`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)

        val palette = client.createPalette("Warm", colourEntry("hex-1", "#ff8800"))
        // The palette covers a hex, but this preset can only ever be applied to moving heads —
        // and none are patched, so there is no candidate to read a literal from.
        val preset = client.createPreset("martin-mac-250-mode-4", colourRef(palette.uuid))

        val body = client.makeHard(preset.id)
        assertEquals(0, body.converted)
        assertEquals(1, body.unresolved)
        assertTrue(body.ambiguous.isEmpty())
    }

    @Test
    fun `an element-scoped ref is unresolved, because palettes are fixture-shaped`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)

        val palette = client.createPalette("Warm", colourEntry("hex-1", "#ff8800"))
        val preset = client.createPreset("hex", colourRef(palette.uuid, elementKey = "head-0"))

        val body = client.makeHard(preset.id)
        assertEquals(0, body.converted)
        assertEquals(1, body.unresolved)
        assertTrue(
            body.preset.propertyAssignments.single().value.startsWith("ref:"),
            "there is nothing to harden it to, so it must be left alone",
        )
    }

    @Test
    fun `a ref to a deleted palette is unresolved`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)

        val palette = client.createPalette("Warm", colourEntry("hex-1", "#ff8800"))
        val preset = client.createPreset("hex", colourRef(palette.uuid))
        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/rest/project/$projectId/palettes/${palette.id}?force=true").status,
        )

        val body = client.makeHard(preset.id)
        assertEquals(0, body.converted)
        assertEquals(1, body.unresolved)
    }

    @Test
    fun `the paletteUuids filter leaves other references alone`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)

        val warm = client.createPalette("Warm", colourEntry("hex-1", "#ff8800"))
        val cool = client.createPalette(
            "Cool",
            PaletteEntryDto("fixture", "hex-1", "uv", "200", 0),
        )
        val preset = client.createPreset(
            "hex",
            colourRef(warm.uuid),
            FxPresetPropertyAssignmentDto(
                propertyName = "uv",
                value = paletteRefValue(UUID.fromString(cool.uuid)),
                sortOrder = 1,
            ),
        )

        val body = client.makeHard(preset.id, PresetMakeHardRequest(paletteUuids = listOf(warm.uuid)))
        assertEquals(1, body.converted)
        val byProp = body.preset.propertyAssignments.associateBy { it.propertyName }
        assertEquals("#ff8800", byProp.getValue("colour").value)
        assertTrue(byProp.getValue("uv").value.startsWith("ref:"), "the unnamed palette's row stands")
    }

    @Test
    fun `a mask skips rows outside the requested attribute groups`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)

        val palette = client.createPalette("Warm", colourEntry("hex-1", "#ff8800"))
        val preset = client.createPreset("hex", colourRef(palette.uuid))

        // A masked-out row is neither converted nor reported: it was never in scope.
        val body = client.makeHard(preset.id, PresetMakeHardRequest(mask = listOf("INTENSITY")))
        assertEquals(0, body.converted)
        assertEquals(0, body.unresolved)
        assertTrue(body.ambiguous.isEmpty())
        assertTrue(body.preset.propertyAssignments.single().value.startsWith("ref:"))
    }

    @Test
    fun `literal rows are left byte-identical`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)

        val palette = client.createPalette("Warm", colourEntry("hex-1", "#ff8800"))
        val preset = client.createPreset(
            "hex",
            FxPresetPropertyAssignmentDto(propertyName = "dimmer", value = "200", sortOrder = 0),
            colourRef(palette.uuid).copy(sortOrder = 1),
        )

        val body = client.makeHard(preset.id)
        assertEquals(1, body.converted)
        val byProp = body.preset.propertyAssignments.associateBy { it.propertyName }
        assertEquals("200", byProp.getValue("dimmer").value)
    }

    @Test
    fun `an unknown mask token is a 400`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val preset = client.createPreset("hex")

        val resp = client.post("/api/rest/project/$projectId/fx-presets/${preset.id}/make-hard") {
            contentType(ContentType.Application.Json)
            setBody(PresetMakeHardRequest(mask = listOf("SPARKLE")))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status, resp.bodyAsText())
    }

    @Test
    fun `a missing preset is a 404`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val resp = client.post("/api/rest/project/$projectId/fx-presets/999999/make-hard") {
            contentType(ContentType.Application.Json)
            setBody(PresetMakeHardRequest())
        }
        assertEquals(HttpStatusCode.NotFound, resp.status, resp.bodyAsText())
    }
}
