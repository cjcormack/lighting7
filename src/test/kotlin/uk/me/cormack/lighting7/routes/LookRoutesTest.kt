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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Test
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.LookFxDto
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
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

    private fun base() = "/api/rest/projects/$projectId/looks"

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
                    rows = listOf(
                        LookRowDto(
                            targetType = "fixture", targetKey = "hex-1",
                            attribute = "dimmer", value = "128", fadeDurationMs = 750L,
                        ),
                    ),
                    fx = listOf(
                        LookFxDto(
                            targetType = "fixture", targetKey = "hex-1",
                            fxType = "Pulse", family = "intensity", attribute = "dimmer",
                            cycle = 0.5, blendMode = "OVERRIDE", order = "NORMAL",
                        ),
                    ),
                )
            )
        }
        assertEquals(HttpStatusCode.Created, createResp.status, createResp.bodyAsText())
        val created = createResp.body<LookDetails>()
        assertEquals("dim-50", created.name)
        assertEquals("128", created.rows.single().value)
        assertEquals(750L, created.rows.single().fadeDurationMs)
        assertEquals("Pulse", created.fx.single().fxType)
        val lookId = created.id

        val list = client.get(base()).body<List<LookDto>>()
        val summary = list.single { it.id == lookId }
        assertEquals(1, summary.rowCount)
        assertEquals(1, summary.fxCount)
        assertFalse(summary.hasDeferredFx, "a Look effect names its own target")

        val fetched = client.get("${base()}/$lookId").body<LookDetails>()
        assertEquals("dimmer", fetched.rows.single().attribute)

        // Rows and effects are replaced wholesale when present.
        val putResp = client.put("${base()}/$lookId") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("name", "dim-75")
                    put("rows", buildJsonArray {
                        add(buildJsonObject {
                            put("targetType", "fixture"); put("targetKey", "hex-1")
                            put("attribute", "dimmer"); put("value", "192")
                        })
                        add(buildJsonObject {
                            put("targetType", "fixture"); put("targetKey", "hex-1")
                            put("attribute", "uv"); put("value", "64")
                        })
                    })
                }
            )
        }
        assertEquals(HttpStatusCode.OK, putResp.status, putResp.bodyAsText())
        val updated = putResp.body<LookDetails>()
        assertEquals("dim-75", updated.name)
        assertEquals(2, updated.rows.size)
        val byProp = updated.rows.associateBy { it.attribute }
        assertEquals("192", byProp.getValue("dimmer").value)
        assertEquals("64", byProp.getValue("uv").value)
        assertEquals(
            1, updated.fx.size,
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
                        LookRowDto(targetType = "fixture", targetKey = "hex-1", attribute = "colour", value = "#ff8800"),
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
                        LookRowDto(targetType = "fixture", targetKey = "hex-1", attribute = "colour", value = "#ff8800"),
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
                            attribute = "colour",
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
    fun `a look row naming a deferred target is rejected, because a look row is always bound`() =
        testApplication {
            // The guard the rest of B6 rests on: `LookRowEntry.target` is non-null and
            // `loadLookSnapshot` drops a row whose discriminator names no arm, so this rejection is
            // the only thing keeping a deferred row from reaching the database and then vanishing
            // silently at cue time. `validateLookFx` refuses the same discriminator for effects.
            mountTestApp(state)
            val client = jsonClient()
            val resp = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(
                    CreateLookRequest(
                        name = "Generic",
                        rows = listOf(
                            LookRowDto(
                                targetType = DEFERRED_TARGET_TYPE, targetKey = "",
                                attribute = "dimmer", value = "180",
                            ),
                        ),
                    )
                )
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertTrue(
                resp.bodyAsText().contains("must name its own fixture or group"),
                resp.bodyAsText(),
            )
        }

    @Test
    fun `a look effect naming no target of its own is rejected, every problem at once`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            fun effect(targetType: String, targetKey: String, blendMode: String = "OVERRIDE") = LookFxDto(
                targetType = targetType, targetKey = targetKey,
                fxType = "Pulse", family = "intensity", attribute = "dimmer",
                cycle = 0.5, blendMode = blendMode, order = "NORMAL",
            )
            val resp = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(
                    CreateLookRequest(
                        name = "Deferred",
                        fx = listOf(
                            effect("fixture", "hex-1"),
                            effect(DEFERRED_TARGET_TYPE, ""),
                            effect("fixture", "hex-2", blendMode = "ADD"),
                        ),
                    )
                )
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = resp.bodyAsText()
            assertTrue("fx[1]: A Look FX must name its own fixture, group or selection" in body, body)
            assertTrue("fx[2]: Unknown blendMode 'ADD'" in body, body)
            assertFalse("fx[0]" in body, body)
            assertTrue(client.get(base()).body<List<LookDto>>().none { it.name == "Deferred" }, "nothing is written")

            val lookId = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(CreateLookRequest(name = "Bound", fx = listOf(effect("group", "front-wash"))))
            }.body<LookDetails>().id
            val put = client.put("${base()}/$lookId") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("fx", TestJson.encodeToJsonElement(listOf(effect(DEFERRED_TARGET_TYPE, ""))))
                })
            }
            assertEquals(HttpStatusCode.BadRequest, put.status)
            assertEquals("group", client.get("${base()}/$lookId").body<LookDetails>().fx.single().targetType, "the PUT wrote nothing")
        }

    @Test
    fun `a put naming an old field is refused rather than dropped`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            val pulse = LookFxDto(
                targetType = "group", targetKey = "front-wash", fxType = "Pulse", family = "intensity",
                attribute = "dimmer", cycle = 0.5, blendMode = "OVERRIDE", order = "NORMAL", amount = 0.4,
            )
            val lookId = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(CreateLookRequest(name = "Bound", fx = listOf(pulse)))
            }.body<LookDetails>().id

            val oldList = client.put("${base()}/$lookId") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("effects", buildJsonArray {}) })
            }
            assertEquals(HttpStatusCode.BadRequest, oldList.status, oldList.bodyAsText())
            assertTrue("effects" in oldList.bodyAsText(), oldList.bodyAsText())

            val oldField = client.put("${base()}/$lookId") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("fx", buildJsonArray {
                        add(buildJsonObject {
                            TestJson.encodeToJsonElement(pulse).let { it as kotlinx.serialization.json.JsonObject }
                                .forEach { (k, v) -> if (k != "amount") put(k, v) }
                            put("size", 0.9)
                        })
                    })
                })
            }
            assertEquals(HttpStatusCode.BadRequest, oldField.status, oldField.bodyAsText())
            assertTrue("size" in oldField.bodyAsText(), oldField.bodyAsText())
            assertEquals(0.4, client.get("${base()}/$lookId").body<LookDetails>().fx.single().amount, "the PUT wrote nothing")

            val oldRow = client.put("${base()}/$lookId") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("rows", kotlinx.serialization.json.Json.parseToJsonElement(
                        """[{"targetType":"fixture","targetKey":"hex-1","propertyName":"dimmer","value":"200"}]""",
                    ))
                })
            }
            assertEquals(HttpStatusCode.BadRequest, oldRow.status, oldRow.bodyAsText())
            assertTrue("propertyName" in oldRow.bodyAsText(), oldRow.bodyAsText())
        }

    @Test
    fun `an unrecognised effect enum is rejected at the write, not survived at spawn`() =
        testApplication {
            // The failure this closes: `blendMode: "ADD"` used to reach `varchar(50)` intact and
            // read back as itself, so the UI rendered "ADD" as the layer's blend while every spawn
            // warned and played OVERRIDE. `FxSpecCoercion.Lenient` exists to *survive* a row an
            // older build wrote, not to paper over one this build accepted.
            mountTestApp(state)
            val client = jsonClient()
            val resp = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(
                    CreateLookRequest(
                        name = "Bad Blend",
                        fx = listOf(
                            LookFxDto(
                                targetType = "fixture", targetKey = "hex-1",
                                fxType = "Pulse", family = "intensity", attribute = "dimmer",
                                cycle = 0.5, blendMode = "ADD", order = "NORMAL",
                            ),
                        ),
                    )
                )
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertTrue(resp.bodyAsText().contains("Unknown blendMode 'ADD'"), resp.bodyAsText())

            // The other three fields go through the same check, so one of them stands for the set.
            val filter = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(
                    CreateLookRequest(
                        name = "Bad Filter",
                        fx = listOf(
                            LookFxDto(
                                targetType = "fixture", targetKey = "hex-1",
                                fxType = "Pulse", family = "intensity", attribute = "dimmer",
                                cycle = 0.5, blendMode = "OVERRIDE", order = "NORMAL",
                                elementFilter = "THIRDS",
                            ),
                        ),
                    )
                )
            }
            assertEquals(HttpStatusCode.BadRequest, filter.status)
            assertTrue(filter.bodyAsText().contains("elementFilter"), filter.bodyAsText())
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
                        LookRowDto(targetType = "fixture", targetKey = "hex-1", attribute = "colour", value = "#ff8800"),
                        LookRowDto(targetType = "fixture", targetKey = "hex-1", attribute = "dimmer", value = "200"),
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

        val stackId = client.post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", "show-1") })
        }.body<CueStackDetails>().id

        client.post("/api/rest/projects/$projectId/cues") {
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

        val lookId = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "Pulse",
                    rows = listOf(
                        LookRowDto(
                            targetType = "fixture", targetKey = "hex-1",
                            attribute = "dimmer", value = "200",
                        ),
                    ),
                    fx = listOf(
                        LookFxDto(
                            targetType = "fixture", targetKey = "hex-1",
                            fxType = "Pulse", family = "intensity", attribute = "dimmer",
                            cycle = 0.5, blendMode = "OVERRIDE", order = "NORMAL",
                        ),
                    ),
                )
            )
        }.body<LookDetails>().id

        val request = ToggleLookRequest(targets = listOf(CueTargetDto("fixture", "hex-1")))

        val applied = client.post("${base()}/$lookId/toggle") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        assertEquals("applied", applied.body<ToggleLookResponse>().action)
        assertTrue(state.show.programmerStore.layers.single().targets.isEmpty(), "a Look layer names no targets, whatever the press named")

        val removed = client.post("${base()}/$lookId/toggle") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertEquals("removed", removed.body<ToggleLookResponse>().action)
    }

    @Test
    fun `toggling an empty target set presses an effects-only Look onto its effect's fixture, and an unknown look is 404`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        state.show.fixtures.patchListChanged()
        val client = jsonClient()

        val lookId = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "Pulse",
                    fx = listOf(
                        LookFxDto(
                            targetType = "fixture", targetKey = "hex-1",
                            fxType = "Pulse", family = "intensity", attribute = "dimmer",
                            cycle = 0.5, blendMode = "OVERRIDE", order = "NORMAL",
                        ),
                    ),
                ),
            )
        }.body<LookDetails>().id

        // The effect names its own fixture, so the Look needs no selection: an empty press lands on it.
        val empty = client.post("${base()}/$lookId/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleLookRequest(targets = emptyList()))
        }
        assertEquals(HttpStatusCode.OK, empty.status, empty.bodyAsText())
        val pressed = empty.body<ToggleLookResponse>()
        assertEquals("applied", pressed.action)
        assertEquals(1, pressed.fxCount)
        assertTrue(state.show.programmerStore.layers.single().targets.isEmpty())
        assertEquals(listOf("hex-1"), state.show.fxEngine.programmerLayerFx().values.map { it.target.targetKey })

        val missing = client.post("${base()}/999999/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleLookRequest(targets = listOf(CueTargetDto("fixture", "hex-1"))))
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun `toggling ignores the selection's mask, on and off`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        // Dimmer rows and a colour effect: the Look spans INTENSITY + COLOUR.
        val lookId = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "Warm pulse",
                    rows = listOf(LookRowDto("fixture", "hex-1", "dimmer", "200")),
                    fx = listOf(
                        LookFxDto(
                            targetType = "fixture", targetKey = "hex-1",
                            fxType = "ColourWave", family = "colour", attribute = "rgbColour",
                            cycle = 0.5, blendMode = "OVERRIDE", order = "NORMAL",
                        ),
                    ),
                ),
            )
        }.body<LookDetails>().id
        val targets = listOf(CueTargetDto("fixture", "hex-1"))

        val on = client.post("${base()}/$lookId/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleLookRequest(targets = targets, families = listOf("POSITION")))
        }
        assertEquals(HttpStatusCode.OK, on.status, on.bodyAsText())
        val applied = on.body<ToggleLookResponse>()
        assertEquals("applied", applied.action, "nothing of it is Position, and it goes on all the same")
        assertTrue(applied.skippedFamilies.isEmpty())
        assertNull(state.show.programmerStore.layers.single().attributeMask, "the press gives the layer no mask (D3)")
        assertEquals(1, applied.fxCount)

        // A mask name outside the vocabulary is ignored with the rest of the mask.
        val off = client.post("${base()}/$lookId/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleLookRequest(targets = targets, families = listOf("GOBO")))
        }
        assertEquals(HttpStatusCode.OK, off.status, off.bodyAsText())
        assertEquals("removed", off.body<ToggleLookResponse>().action)
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    private suspend fun io.ktor.client.HttpClient.createBoundLook(name: String, vararg fixtureKeys: String): Int =
        post(base()) {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = name, rows = fixtureKeys.map { LookRowDto("fixture", it, "dimmer", "200") }))
        }.body<LookDetails>().id

    private suspend fun io.ktor.client.HttpClient.toggleBare(lookId: Int) =
        post("${base()}/$lookId/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleLookRequest())
        }

    @Test
    fun `toggling lands a bound Look whole, on its own fixtures, naming no targets`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        state.show.fixtures.patchListChanged()
        val client = jsonClient()
        val warm = client.createBoundLook("warm", "hex-1", "hex-2")

        val on = client.toggleBare(warm)
        assertEquals(HttpStatusCode.OK, on.status, on.bodyAsText())
        assertEquals("applied", on.body<ToggleLookResponse>().action)
        assertTrue(state.show.programmerStore.layers.single().targets.isEmpty())
        assertEquals(setOf("hex-1", "hex-2"), dimmedByLayers("hex-1", "hex-2"))
        assertEquals("removed", client.toggleBare(warm).body<ToggleLookResponse>().action)
    }

    @Test
    fun `toggling lands the rows still patched and skips the rest`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        state.show.fixtures.patchListChanged()
        val client = jsonClient()
        val warm = client.createBoundLook("warm", "hex-9", "hex-1")

        val on = client.toggleBare(warm)
        assertEquals(HttpStatusCode.OK, on.status, on.bodyAsText())
        assertEquals(setOf("hex-1"), dimmedByLayers("hex-1", "hex-9"))
    }

    @Test
    fun `a Look none of whose fixtures are patched goes on and lands nothing, as a cue does`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val gone = client.createBoundLook("gone", "hex-9")

        val resp = client.toggleBare(gone)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        assertEquals("applied", resp.body<ToggleLookResponse>().action)
        assertEquals(1, state.show.programmerStore.layers.size, "its ring lights: a layer of it is on")
        assertEquals("removed", client.toggleBare(gone).body<ToggleLookResponse>().action)
    }

    /** Which of [keys] the programmer's layers hold a dimmer on. */
    private fun dimmedByLayers(vararg keys: String): Set<String> = keys.filter { key ->
        state.show.programmerStore.get(key, "dimmer")?.owner == uk.me.cormack.lighting7.fx.ProgrammerOwner.LAYERS
    }.toSet()

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
                            attribute = "dimmer", value = "180",
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
    fun `including a look whose rows name nothing in the patch stages nothing and says so`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // `hex-1` is never seeded, so the row is bound to a fixture the patch does not have and
        // nothing stages. Silence would read as a broken button — and the message has to send the
        // operator to the *patch*, not to the Look editor where the row is plainly there and bound.
        //
        // This test named a *deferred* row until sweep item B6; a Look row cannot be deferred and
        // has not been able to be since session 3, so it had been testing this case under that name
        // for a while.
        val lookId = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "Template",
                    rows = listOf(
                        LookRowDto(
                            targetType = "fixture", targetKey = "hex-1",
                            attribute = "dimmer", value = "180",
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
        assertTrue(
            body.warnings.any { it.contains("could be staged") },
            body.warnings.toString(),
        )
    }

    @Test
    fun `including an effects-only look runs its effect on its own fixture, with nothing selected`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        state.show.fixtures.patchListChanged()
        val client = jsonClient()

        val lookId = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "Pulse Only",
                    fx = listOf(
                        LookFxDto(
                            targetType = "fixture", targetKey = "hex-1",
                            fxType = "Pulse", family = "intensity", attribute = "dimmer",
                            cycle = 0.5, blendMode = "OVERRIDE", order = "NORMAL",
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
        assertEquals(1, body.fxSpawned)
        assertEquals(listOf("hex-1"), body.fixtureKeys)
        assertTrue(body.warnings.isEmpty(), body.warnings.toString())
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
}
