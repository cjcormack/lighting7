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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.LookEffectDto
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Route-level Look CRUD: children round-tripping, the derived family banking that replaced the
 * per-type palette banks, the no-nesting write boundary, and the in-use delete guard.
 */
class LookRoutesTest : RouteIntegrationTest() {

    private fun base() = "/api/rest/project/$projectId/looks"

    @Test
    fun `create, list, GET, PUT, DELETE round-trip rows and effects`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val createResp = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "dim-50",
                    notes = "dimmer at 50%",
                    editorFixtureType = "hex-fixture",
                    palette = listOf("#ff8800"),
                    rows = listOf(
                        LookRowDto(
                            targetType = DEFERRED_TARGET_TYPE, targetKey = "",
                            propertyName = "dimmer", value = "128", fadeDurationMs = 750L,
                        ),
                    ),
                    effects = listOf(
                        LookEffectDto(
                            targetType = DEFERRED_TARGET_TYPE, targetKey = "",
                            effectType = "Pulse", category = "dimmer", propertyName = "dimmer",
                            beatDivision = 0.5, blendMode = "OVERRIDE", distribution = "LINEAR",
                        ),
                    ),
                )
            )
        }
        assertEquals(HttpStatusCode.Created, createResp.status, createResp.bodyAsText())
        val created = createResp.body<LookDetails>()
        assertEquals("dim-50", created.name)
        assertEquals("hex-fixture", created.editorFixtureType)
        assertEquals(listOf("#ff8800"), created.palette)
        assertEquals("128", created.rows.single().value)
        assertEquals(750L, created.rows.single().fadeDurationMs)
        assertEquals("Pulse", created.effects.single().effectType)
        val lookId = created.id

        val list = client.get(base()).body<List<LookDto>>()
        val summary = list.single { it.id == lookId }
        assertEquals(1, summary.rowCount)
        assertEquals(1, summary.effectCount)
        assertTrue(summary.hasDeferredRows, "every row was authored deferred")

        val fetched = client.get("${base()}/$lookId").body<LookDetails>()
        assertEquals("dimmer", fetched.rows.single().propertyName)

        // Rows and effects are replaced wholesale when present.
        val putResp = client.put("${base()}/$lookId") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("name", "dim-75")
                    put("rows", buildJsonArray {
                        add(buildJsonObject {
                            put("targetType", DEFERRED_TARGET_TYPE); put("targetKey", "")
                            put("propertyName", "dimmer"); put("value", "192")
                        })
                        add(buildJsonObject {
                            put("targetType", DEFERRED_TARGET_TYPE); put("targetKey", "")
                            put("propertyName", "uv"); put("value", "64")
                        })
                    })
                }
            )
        }
        assertEquals(HttpStatusCode.OK, putResp.status, putResp.bodyAsText())
        val updated = putResp.body<LookDetails>()
        assertEquals("dim-75", updated.name)
        assertEquals(2, updated.rows.size)
        val byProp = updated.rows.associateBy { it.propertyName }
        assertEquals("192", byProp.getValue("dimmer").value)
        assertEquals("64", byProp.getValue("uv").value)
        assertEquals(
            1, updated.effects.size,
            "omitting `effects` leaves them alone — absent is not the same as empty",
        )

        assertEquals(HttpStatusCode.NoContent, client.delete("${base()}/$lookId").status)
        assertEquals(HttpStatusCode.NotFound, client.get("${base()}/$lookId").status)
    }

    @Test
    fun `a metadata-only PUT leaves rows untouched`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val lookId = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "Warm",
                    rows = listOf(
                        LookRowDto(targetType = "fixture", targetKey = "hex-1", propertyName = "colour", value = "#ff8800"),
                    ),
                )
            )
        }.body<LookDetails>().id

        val resp = client.put("${base()}/$lookId") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("notes", "act one") })
        }
        val updated = resp.body<LookDetails>()
        assertEquals("act one", updated.notes)
        assertEquals("#ff8800", updated.rows.single().value)
    }

    @Test
    fun `an explicitly empty rows array clears them, unlike an absent one`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val lookId = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "Warm",
                    rows = listOf(
                        LookRowDto(targetType = "fixture", targetKey = "hex-1", propertyName = "colour", value = "#ff8800"),
                    ),
                )
            )
        }.body<LookDetails>().id

        val updated = client.put("${base()}/$lookId") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("rows", buildJsonArray { }) })
        }.body<LookDetails>()
        assertTrue(updated.rows.isEmpty())
    }

    @Test
    fun `a look row holding a reference is rejected, because looks do not nest`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val resp = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "Nested",
                    rows = listOf(
                        LookRowDto(
                            targetType = "fixture", targetKey = "hex-1",
                            propertyName = "colour",
                            value = "ref:2f1c9a54-8d3b-4f7e-9a11-6c0de5b47a02",
                        ),
                    ),
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("do not nest"), resp.bodyAsText())
    }

    @Test
    fun `a duplicate name in one project is rejected`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val body = CreateLookRequest(name = "Warm")
        assertEquals(
            HttpStatusCode.Created,
            client.post(base()) { contentType(ContentType.Application.Json); setBody(body) }.status,
        )
        assertEquals(
            HttpStatusCode.Conflict,
            client.post(base()) { contentType(ContentType.Application.Json); setBody(body) }.status,
            "a look is identified by (project, name) alone — no type or fixtureType to disambiguate",
        )
    }

    @Test
    fun `families are derived from the rows, not stored`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        state.show.fixtures.patchListChanged()
        val client = jsonClient()

        // One look spanning two families — impossible under the old per-type palette banks, which
        // is the point of deriving rather than storing.
        val lookId = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "Wash",
                    rows = listOf(
                        LookRowDto(targetType = "fixture", targetKey = "hex-1", propertyName = "colour", value = "#ff8800"),
                        LookRowDto(targetType = "fixture", targetKey = "hex-1", propertyName = "dimmer", value = "200"),
                    ),
                )
            )
        }.body<LookDetails>().id

        val details = client.get("${base()}/$lookId").body<LookDetails>()
        assertEquals(setOf("INTENSITY", "COLOUR"), details.families.toSet())

        // …and the library banks on it.
        assertTrue(client.get("${base()}?family=COLOUR").body<List<LookDto>>().any { it.id == lookId })
        assertTrue(client.get("${base()}?family=INTENSITY").body<List<LookDto>>().any { it.id == lookId })
        assertFalse(client.get("${base()}?family=POSITION").body<List<LookDto>>().any { it.id == lookId })
        assertEquals(HttpStatusCode.BadRequest, client.get("${base()}?family=NONSENSE").status)
    }

    @Test
    fun `deleting a look a cue layers is refused until forced`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val lookId = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = "Warm"))
        }.body<LookDetails>().id

        val stackId = client.post("/api/rest/project/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", "show-1") })
        }.body<CueStackDetails>().id

        client.post("/api/rest/project/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("name", "cue-1")
                    put("cueStackId", stackId)
                    put("layers", buildJsonArray {
                        add(buildJsonObject { put("lookId", lookId) })
                    })
                }
            )
        }.let { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }

        val details = client.get("${base()}/$lookId").body<LookDetails>()
        assertEquals(1, details.layerCount, "the guard counts layers through a real FK")

        val refused = client.delete("${base()}/$lookId")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(CODE_LOOK_IN_USE, refused.body<LookInUseResponse>().code)

        assertEquals(HttpStatusCode.NoContent, client.delete("${base()}/$lookId?force=true").status)
        assertNull(client.get(base()).body<List<LookDto>>().firstOrNull { it.id == lookId })
    }

    // ── Toggle ──────────────────────────────────────────────────────────────

    @Test
    fun `toggling a look applies it and toggling again removes it`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        state.show.fixtures.patchListChanged()
        val client = jsonClient()

        // Deferred rows and effects only: the pad supplies the targets, so a bound row would land
        // on fixtures the operator never selected. `loadLookToggleData` filters them out, which is
        // why this look is authored deferred.
        val lookId = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "Pulse",
                    editorFixtureType = "hex-fixture",
                    rows = listOf(
                        LookRowDto(
                            targetType = DEFERRED_TARGET_TYPE, targetKey = "",
                            propertyName = "dimmer", value = "200",
                        ),
                    ),
                    effects = listOf(
                        LookEffectDto(
                            targetType = DEFERRED_TARGET_TYPE, targetKey = "",
                            effectType = "Pulse", category = "dimmer", propertyName = "dimmer",
                            beatDivision = 0.5, blendMode = "OVERRIDE", distribution = "LINEAR",
                        ),
                    ),
                )
            )
        }.body<LookDetails>().id

        val request = TogglePresetRequest(targets = listOf(TogglePresetTarget("fixture", "hex-1")))

        val applied = client.post("${base()}/$lookId/toggle") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        assertEquals("applied", applied.body<TogglePresetResponse>().action)

        val removed = client.post("${base()}/$lookId/toggle") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertEquals("removed", removed.body<TogglePresetResponse>().action)
    }

    @Test
    fun `toggling refuses an empty target set and an unknown look`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val lookId = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = "Pulse"))
        }.body<LookDetails>().id

        // No targets is a 400 rather than a silent no-op: a toggle that applied nothing and
        // reported success would read as a dead pad.
        val empty = client.post("${base()}/$lookId/toggle") {
            contentType(ContentType.Application.Json)
            setBody(TogglePresetRequest(targets = emptyList()))
        }
        assertEquals(HttpStatusCode.BadRequest, empty.status)

        val missing = client.post("${base()}/999999/toggle") {
            contentType(ContentType.Application.Json)
            setBody(TogglePresetRequest(targets = listOf(TogglePresetTarget("fixture", "hex-1"))))
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    // ── Preview ─────────────────────────────────────────────────────────────

    @Test
    fun `preview writes the draft to the slot and clearing releases it`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        state.show.fixtures.patchListChanged()
        val client = jsonClient()

        // Whole-desired-state, and no stored look involved at all — which is exactly why this
        // route could reuse the preset editor's preview slot rather than growing new logic.
        val pushed = client.post("${base()}/preview") {
            contentType(ContentType.Application.Json)
            setBody(
                PresetPreviewRequest(
                    propertyAssignments = listOf(
                        uk.me.cormack.lighting7.models.FxPresetPropertyAssignmentDto(
                            propertyName = "dimmer", value = "128",
                        ),
                    ),
                    targets = listOf(TogglePresetTarget("fixture", "hex-1")),
                )
            )
        }
        assertEquals(HttpStatusCode.OK, pushed.status, pushed.bodyAsText())
        assertTrue(pushed.body<PresetPreviewResponse>().writeCount > 0)

        assertEquals(HttpStatusCode.OK, client.delete("${base()}/preview").status)
    }

    @Test
    fun `preview does not collide with the look-id routes`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // `/preview` and `/{lookId}` sit at the same depth. Nothing serves `POST /{lookId}` and
        // `lookId` is an Int that "preview" cannot parse as, so the two are unambiguous — but a
        // future `POST /{lookId}` would shadow this route silently, so pin it.
        val resp = client.post("${base()}/preview") {
            contentType(ContentType.Application.Json)
            setBody(PresetPreviewRequest())
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
    }

    // ── Include ─────────────────────────────────────────────────────────────

    @Test
    fun `including a look stages its bound rows in the programmer`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        state.show.fixtures.patchListChanged()
        val client = jsonClient()

        val lookId = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "Warm",
                    rows = listOf(
                        LookRowDto(
                            targetType = "fixture", targetKey = "hex-1",
                            propertyName = "dimmer", value = "180",
                        ),
                    ),
                )
            )
        }.body<LookDetails>().id

        val resp = client.post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("projectId", "current"); put("lookId", lookId) })
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val body = resp.body<ProgrammerIncludeResponse>()
        assertEquals("LOOK", body.kind, "the client keys 'Update is unavailable' off this arm")
        assertEquals(lookId, body.lookId)
        assertEquals("Warm", body.name)
        assertEquals(1, body.entriesWritten)
        assertEquals(listOf("hex-1"), body.fixtureKeys)
    }

    @Test
    fun `including a fully-deferred look stages nothing and says so`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // A deferred row names no fixture and the programmer has no layer to take one from, so
        // there is nothing to stage. Silence would read as a broken button.
        val lookId = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "Template",
                    editorFixtureType = "hex-fixture",
                    rows = listOf(
                        LookRowDto(
                            targetType = DEFERRED_TARGET_TYPE, targetKey = "",
                            propertyName = "dimmer", value = "180",
                        ),
                    ),
                )
            )
        }.body<LookDetails>().id

        val body = client.post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("projectId", "current"); put("lookId", lookId) })
        }.body<ProgrammerIncludeResponse>()
        assertEquals(0, body.entriesWritten)
        assertTrue(body.warnings.any { it.contains("no rows naming a fixture") }, body.warnings.toString())
    }

    @Test
    fun `include refuses more than one of cueId, paletteId and lookId`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val resp = client.post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("projectId", "current")
                    put("cueId", 1)
                    put("lookId", 1)
                }
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `update refuses to write back into an included look`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        state.show.fixtures.patchListChanged()
        val client = jsonClient()

        val lookId = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "Warm",
                    rows = listOf(
                        LookRowDto(
                            targetType = "fixture", targetKey = "hex-1",
                            propertyName = "dimmer", value = "180",
                        ),
                    ),
                )
            )
        }.body<LookDetails>().id

        client.post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("projectId", "current"); put("lookId", lookId) })
        }.let { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }

        // A Look include is one-way until the record rewrite lands. Refused *explicitly*: Mode A
        // otherwise falls through to `includeTarget.cueId!!`, which is null for a Look, so the
        // alternative to this guard is a 500 rather than a 400. And the code is its own rather than
        // INCLUDE_TARGET_GONE — nothing is missing, so "gone" would send the operator looking for a
        // problem that isn't there.
        val refused = client.post("/api/rest/programmer/update") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("projectId", "current") })
        }
        assertEquals(HttpStatusCode.Conflict, refused.status, refused.bodyAsText())
        assertEquals(CODE_INCLUDE_TARGET_READ_ONLY, refused.body<ProgrammerConflictResponse>().code)
    }
}
