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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.PaletteFxDto
import uk.me.cormack.lighting7.models.PaletteRowDto
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The template library at route level: CRUD, the four write-boundary rules, and the two apply
 * gestures.
 *
 * The write-boundary tests are the ones worth having. "Exactly one family" is what makes a template
 * a template, and "no slotted properties" is where the promise that a template does not lie about a
 * gobo actually lives — both are enforced nowhere else.
 */
class PaletteRoutesTest : RouteIntegrationTest() {

    private fun base() = "/api/rest/projects/$projectId/palettes"

    private fun colourRow(value: String = "#FF9D4A;policy=extract") = PaletteRowDto(
        targetType = DEFERRED_TARGET_TYPE, targetKey = "",
        attribute = "rgbColour", value = value,
    )

    private fun colourEffect(
        fxType: String = "ColourWave",
        category: String = "colour",
        parameters: Map<String, String> = emptyMap(),
    ) = PaletteFxDto(
        fxType = fxType,
        family = category,
        attribute = "rgbColour",
        cycle = 0.5,
        blendMode = "OVERRIDE",
        order = "UNIFIED",
        parameters = parameters,
    )

    @Test
    fun `create, list, GET, PUT and DELETE round-trip a generic template`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val createResp = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-key", notes = "warm key", fadeDurationMs = 1_500L, rows = listOf(colourRow())))
        }
        assertEquals(HttpStatusCode.Created, createResp.status, createResp.bodyAsText())
        val created = createResp.body<PaletteDto>()
        assertEquals("amber-key", created.name)
        assertEquals(1_500L, created.fadeDurationMs)
        assertEquals("COLOUR", created.family, "the family is derived from the row, never declared")
        assertTrue(created.isGeneric, "one deferred row is the Generic case")
        assertEquals("#FF9D4A;policy=extract", created.rows.single().value)
        assertEquals(0, created.layerCount)

        val listed = client.get(base()).body<List<PaletteDto>>()
        assertEquals(listOf("amber-key"), listed.map { it.name })

        val fetched = client.get("${base()}/${created.id}").body<PaletteDto>()
        assertEquals(created.uuid, fetched.uuid)

        val updated = client.put("${base()}/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-key", rows = listOf(colourRow("#FFB070;policy=additive"))))
        }.body<PaletteDto>()
        assertEquals("#FFB070;policy=additive", updated.rows.single().value)

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("${base()}/${created.id}").status,
        )
        assertTrue(client.get(base()).body<List<PaletteDto>>().isEmpty())
    }

    @Test
    fun `the library is served under palettes, and the template paths are gone`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        assertEquals(HttpStatusCode.OK, client.get(base()).status)
        // The SPA fallback answers any GET, so "gone" is: no JSON library there, and nothing to POST to.
        val old = client.get("/api/rest/projects/$projectId/templates")
        assertTrue(old.contentType()?.match(ContentType.Application.Json) != true, old.bodyAsText().take(80))
        val create = client.post("/api/rest/projects/$projectId/templates") {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "x", rows = listOf(colourRow())))
        }
        assertTrue(create.status != HttpStatusCode.Created, create.status.toString())
        assertEquals(
            listOf("PALETTE_IN_USE", "PALETTE_OUTSIDE_MASK", "PALETTE_NEEDS_SELECTION"),
            listOf(CODE_PALETTE_IN_USE, CODE_PALETTE_OUTSIDE_MASK, CODE_PALETTE_NEEDS_SELECTION),
            "codes reach Claude through MCP results, so they say palette",
        )
    }

    @Test
    fun `a per-fixture template keeps its own targets and is not generic`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "shehds-led19-rgbw-16ch", "mover-1", 1)
        LocateTestSupport.seedFixture(state, projectId, "shehds-led19-rgbw-16ch", "mover-2", 20)
        val client = jsonClient()

        val created = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteInput(
                    name = "downstage-centre",
                    rows = listOf(
                        PaletteRowDto("fixture", "mover-1", "position", "deg:12,-8"),
                        PaletteRowDto("fixture", "mover-2", "position", "deg:-14,-8"),
                    ),
                )
            )
        }.body<PaletteDto>()

        assertEquals("POSITION", created.family)
        assertTrue(!created.isGeneric, "rows naming their own heads are the Per fixture case")
        assertEquals(2, created.rows.size)
    }

    // ─── The write boundary ─────────────────────────────────────────────

    @Test
    fun `a template spanning two families is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val resp = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteInput(
                    name = "mixed",
                    rows = listOf(
                        colourRow(),
                        PaletteRowDto(DEFERRED_TARGET_TYPE, "", "dimmer", "pct:75"),
                    ),
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("exactly one attribute family"), resp.bodyAsText())
    }

    @Test
    fun `a slotted attribute is refused, and the message says where it lives instead`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        for (attribute in listOf("gobo", "goboRotation", "ledMacro")) {
            val resp = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(
                    PaletteInput(
                        name = "gobo-$attribute",
                        rows = listOf(PaletteRowDto(DEFERRED_TARGET_TYPE, "", attribute, "pct:50")),
                    )
                )
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status, attribute)
            // The message has a job beyond refusing: an operator looking for gobo needs to learn
            // *where* it lives, not conclude the desk cannot do it.
            assertTrue(resp.bodyAsText().contains("recorded look"), resp.bodyAsText())
        }
    }

    private fun emitterRow(attribute: String, value: Int) = PaletteRowDto(
        targetType = DEFERRED_TARGET_TYPE, targetKey = "",
        attribute = attribute, value = "dmx:$value",
    )

    @Test
    fun `a colour naming an emitter outright reports it as required`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val created = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteInput(
                    name = "uv-wash",
                    rows = listOf(
                        colourRow("#FF9D4A;policy=rgbonly"),
                        emitterRow("uv", 255),
                        emitterRow("white", 180),
                    ),
                )
            )
        }.body<PaletteDto>()

        // Still one family — every emitter is COLOUR, which is what lets a hex and an amber be one
        // named thing. The emitters are what the client filters on beyond that.
        assertEquals("COLOUR", created.family)
        // In vocabulary order, not row order, so the wire form has one spelling.
        assertEquals(listOf("white", "uv"), created.requiredEmitters)
    }

    @Test
    fun `a plain colour template requires no emitter, so it still travels`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val created = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "warm-key", rows = listOf(colourRow())))
        }.body<PaletteDto>()
        assertTrue(created.requiredEmitters.isEmpty(), created.requiredEmitters.toString())
    }

    @Test
    fun `an explicit white beside a deriving policy is refused by name`() = testApplication {
        // Extract and Additive drive the same emitter byte the explicit row names, so a template
        // holding both asks two things of one channel. Refused rather than letting one win, because
        // the operator who set a policy and then dialled a white should be told it does nothing.
        mountTestApp(state)
        val client = jsonClient()
        for (policy in listOf("extract", "additive")) {
            val resp = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(
                    PaletteInput(
                        name = "clash-$policy",
                        rows = listOf(colourRow("#FF9D4A;policy=$policy"), emitterRow("white", 180)),
                    )
                )
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status, policy)
            assertTrue(resp.bodyAsText().contains("rgbonly"), resp.bodyAsText())
        }
    }

    @Test
    fun `a deferred deriving colour clashes with a per-fixture white on the same rig`() =
        testApplication {
            // The rule is **co-applicability**, not a shared target key. A deferred colour row
            // applies to every head the template lands on, so it meets a per-fixture white row there
            // — and grouping by target key put the two in separate buckets and waved this through,
            // leaving that head's white channel driven from two unarbitrated sources at cook time.
            // `POST /templates/from-programmer` can genuinely record this shape, because the
            // generic/per-fixture collapse is decided per property.
            mountTestApp(state)
            LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
            val client = jsonClient()
            val resp = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(
                    PaletteInput(
                        name = "split-clash",
                        rows = listOf(
                            colourRow("#FF9D4A;policy=extract"),
                            PaletteRowDto("fixture", "hex-1", "white", "dmx:180"),
                        ),
                    )
                )
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status, resp.bodyAsText())
            assertTrue(resp.bodyAsText().contains("rgbonly"), resp.bodyAsText())
        }

    @Test
    fun `two heads that never meet keep their own policies`() = testApplication {
        // The other side of co-applicability: per-fixture rows on *different* heads do not conflict,
        // so a template may derive white on one head and set it outright on another.
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        val client = jsonClient()
        val resp = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteInput(
                    name = "per-head",
                    rows = listOf(
                        PaletteRowDto("fixture", "hex-1", "rgbColour", "#FF9D4A;policy=extract"),
                        PaletteRowDto("fixture", "hex-2", "white", "dmx:180"),
                    ),
                )
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
    }

    @Test
    fun `a UV-only template omits every head with no colour, rather than listing the rig`() =
        testApplication {
            // The resolves-to panel's omit rule used to be a string match against `"no colour"`. A
            // UV-only template answers `"no uv"` for every head in the rig, so a rig-wide resolve
            // listed every dimmer and hazer as a failure instead of omitting them — the opposite of
            // what the panel documents. A head that *has* colour but no UV is still reported: it
            // could have taken this and cannot, which is the question the panel exists to answer.
            mountTestApp(state)
            LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
            LocateTestSupport.seedFixture(state, projectId, "shehds-led19-rgbw-16ch", "mover-1", 40)
            LocateTestSupport.seedFixture(state, projectId, "hazer", "haze-1", 90)
            val client = jsonClient()

            val body = client.post("${base()}/resolve") {
                contentType(ContentType.Application.Json)
                setBody(PaletteResolveRequest(rows = listOf(emitterRow("uv", 255))))
            }.body<PaletteResolveResponse>()

            val keys = body.entries.map { it.fixtureKey }.toSet()
            assertTrue("haze-1" !in keys, "a head with no colour is not a candidate: $keys")
            assertTrue("hex-1" in keys, keys.toString())
            assertTrue("mover-1" in keys, "RGBW head has colour but no UV — reported: $keys")
            assertEquals("no uv", body.entries.single { it.fixtureKey == "mover-1" }.detail)
        }

    @Test
    fun `a rig-wide resolve leaves infrastructure out, and a named target still resolves`() = testApplication {
        // Empty targets is "the whole patch" the editor previews against — the patch an operator is
        // offered, so never an infrastructure fixture. Naming one explicitly still resolves it.
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 20)
        state.show.fixtures.setPatchMetadata("hex-2", Fixtures.FixturePatchMetadata(gelCode = null, infrastructure = true))
        val client = jsonClient()

        val rigWide = client.post("${base()}/resolve") {
            contentType(ContentType.Application.Json)
            setBody(PaletteResolveRequest(rows = listOf(colourRow())))
        }.body<PaletteResolveResponse>()
        assertEquals(listOf("hex-1"), rigWide.entries.map { it.fixtureKey }.distinct())

        val named = client.post("${base()}/resolve") {
            contentType(ContentType.Application.Json)
            setBody(PaletteResolveRequest(rows = listOf(colourRow()), targets = listOf(PaletteTargetDto("fixture", "hex-2"))))
        }.body<PaletteResolveResponse>()
        assertEquals(listOf("hex-2"), named.entries.map { it.fixtureKey }.distinct())
    }

    @Test
    fun `the refusal does not depend on which row was authored first`() = testApplication {
        // `unmetColourRequirement` short-circuited on the first Unsupported row it met, so the same
        // hazer was omitted or listed depending on whether the emitter row happened to sort before
        // the hex. The colour row is now consulted first whatever the stored order.
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "hazer", "haze-1", 90)
        val client = jsonClient()
        for (rows in listOf(
            listOf(colourRow("#FF9D4A;policy=rgbonly"), emitterRow("uv", 255)),
            listOf(emitterRow("uv", 255), colourRow("#FF9D4A;policy=rgbonly")),
        )) {
            val body = client.post("${base()}/resolve") {
                contentType(ContentType.Application.Json)
                setBody(PaletteResolveRequest(rows = rows))
            }.body<PaletteResolveResponse>()
            assertTrue(
                body.entries.none { it.fixtureKey == "haze-1" },
                "row order must not decide whether a colourless head is listed: ${body.entries}",
            )
        }
    }

    @Test
    fun `one row per attribute — a second colour row is refused rather than silently losing`() =
        testApplication {
            // Every reader downstream takes the first colour row it finds, and nothing says which
            // that is. Reachable before emitters existed but hidden, because `isOfferable`'s old
            // "exactly one row" clause kept such a template off the one surface that folds rows.
            mountTestApp(state)
            val client = jsonClient()
            val resp = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(
                    PaletteInput(
                        name = "two-colours",
                        rows = listOf(colourRow("#FF9D4A;policy=rgbonly"), colourRow("#4A9DFF;policy=rgbonly")),
                    )
                )
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status, resp.bodyAsText())
            assertTrue(resp.bodyAsText().contains("twice"), resp.bodyAsText())
        }

    @Test
    fun `the same attribute on two different heads is not a duplicate`() = testApplication {
        // Per property *per target* — a focus position is one pan/tilt row per head by construction.
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        val client = jsonClient()
        val resp = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteInput(
                    name = "per-head-colour",
                    rows = listOf(
                        PaletteRowDto("fixture", "hex-1", "rgbColour", "#FF9D4A;policy=rgbonly"),
                        PaletteRowDto("fixture", "hex-2", "rgbColour", "#4A9DFF;policy=rgbonly"),
                    ),
                )
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
    }

    @Test
    fun `UV sits beside any policy, because no policy has ever driven it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val resp = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteInput(
                    name = "warm-plus-uv",
                    rows = listOf(colourRow("#FF9D4A;policy=extract"), emitterRow("uv", 255)),
                )
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
    }

    @Test
    fun `a head missing a named emitter takes none of the colour, and says so once`() =
        testApplication {
            // The whole-template rule. The Shehds mover is RGBW: it has the white this template
            // names but not the amber, so it must take neither *nor* the hex — half a colour under
            // this template's name would be a wrong answer delivered confidently.
            mountTestApp(state)
            LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
            LocateTestSupport.seedFixture(state, projectId, "shehds-led19-rgbw-16ch", "mover-1", 40)
            val client = jsonClient()

            val template = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(
                    PaletteInput(
                        name = "amber-key",
                        rows = listOf(colourRow("#FF9D4A;policy=rgbonly"), emitterRow("amber", 200)),
                    )
                )
            }.body<PaletteDto>()

            val body = client.post("${base()}/${template.id}/apply") {
                contentType(ContentType.Application.Json)
                setBody(
                    ApplyPaletteRequest(
                        targets = listOf(
                            PaletteTargetDto("fixture", "hex-1"),
                            PaletteTargetDto("fixture", "mover-1"),
                        ),
                    )
                )
            }.body<ApplyPaletteResponse>()

            // The Hex has amber, so both of its rows land.
            assertEquals(2, body.written)
            // One refusal is one skip, not one per colour row.
            assertEquals(1, body.skipped.size, body.skipped.toString())
            assertEquals("mover-1", body.skipped.single().fixtureKey)
            assertEquals("no amber", body.skipped.single().reason)
        }

    @Test
    fun `resolve shows the refusal per head, before the template is saved`() = testApplication {
        // The panel is where an operator is meant to learn this — the whole reason `/resolve` takes
        // a draft. Each colour row reports the *template's* reason rather than its own, so the hex
        // does not read as landing fine beside an unsupported amber.
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "shehds-led19-rgbw-16ch", "mover-1", 40)
        val client = jsonClient()

        val body = client.post("${base()}/resolve") {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteResolveRequest(
                    rows = listOf(colourRow("#FF9D4A;policy=rgbonly"), emitterRow("amber", 200)),
                )
            )
        }.body<PaletteResolveResponse>()

        val forMover = body.entries.filter { it.fixtureKey == "mover-1" }
        assertEquals(2, forMover.size, forMover.toString())
        assertTrue(forMover.all { it.outcome == "UNSUPPORTED" }, forMover.toString())
        assertTrue(forMover.all { it.detail == "no amber" }, forMover.toString())
        // Null even for the hex row, which resolves on its own: the head receives nothing, and
        // printing the value it *would* have taken would say the opposite.
        assertTrue(forMover.all { it.value == null }, forMover.toString())
    }

    @Test
    fun `an emitter level lands on that head's own slider`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val body = client.post("${base()}/resolve") {
            contentType(ContentType.Application.Json)
            setBody(PaletteResolveRequest(rows = listOf(emitterRow("uv", 200))))
        }.body<PaletteResolveResponse>()

        val entry = body.entries.single { it.fixtureKey == "hex-1" }
        assertEquals("EXACT", entry.outcome)
        assertEquals("uv", entry.resolvedAttributeName)
        assertEquals("200", entry.value)
    }

    @Test
    fun `an intent of the wrong shape for its attribute is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val resp = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteInput(
                    name = "wrong-shape",
                    // Degrees on a dimmer: a client bug, and storing it would make a row that
                    // resolves to nothing on every head in the rig.
                    rows = listOf(PaletteRowDto(DEFERRED_TARGET_TYPE, "", "dimmer", "deg:45,12")),
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("wrong kind of value"), resp.bodyAsText())
    }

    @Test
    fun `a group row is refused, because a template names no targets of its own`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val resp = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteInput(
                    name = "grouped",
                    rows = listOf(PaletteRowDto("group", "front-wash", "rgbColour", "#ff0000;policy=extract")),
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("targets a fixture or nothing"), resp.bodyAsText())
    }

    @Test
    fun `a template with no rows is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val resp = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "empty", rows = emptyList()))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `a duplicate name is a conflict`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val body = PaletteInput(name = "amber-key", rows = listOf(colourRow()))
        client.post(base()) { contentType(ContentType.Application.Json); setBody(body) }
        val second = client.post(base()) { contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Conflict, second.status)
    }

    // ─── Resolve ────────────────────────────────────────────────────────

    @Test
    fun `resolve answers per head, against a draft that has never been saved`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedFixture(state, projectId, "martin-mac-250-mode-4", "mac-1", 40)
        LocateTestSupport.seedFixture(state, projectId, "hazer", "haze-1", 60)
        val client = jsonClient()

        val resp = client.post("${base()}/resolve") {
            contentType(ContentType.Application.Json)
            setBody(PaletteResolveRequest(rows = listOf(colourRow())))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val entries = resp.body<PaletteResolveResponse>().entries

        val onHex = entries.single { it.fixtureKey == "hex-1" }
        assertEquals("EXACT", onHex.outcome)
        assertNotNull(onHex.value)

        // The wheel is the interesting one: it reports the slot it landed on and how far off it was,
        // which is what the editor's panel shows before a save.
        val onMac = entries.single { it.fixtureKey == "mac-1" }
        assertEquals("SNAPPED", onMac.outcome)
        assertNotNull(onMac.detail)
        assertNotNull(onMac.deltaE)
        assertEquals("colourWheel", onMac.resolvedAttributeName, "the wheel, not `rgbColour`")

        // A head with nothing in this family was never a candidate, so it is absent rather than
        // listed as a failure.
        assertTrue(entries.none { it.fixtureKey == "haze-1" }, "a hazer has no colour to report on")
    }

    @Test
    fun `resolve can be narrowed to a selection`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        val client = jsonClient()

        val entries = client.post("${base()}/resolve") {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteResolveRequest(
                    rows = listOf(colourRow()),
                    targets = listOf(PaletteTargetDto("fixture", "hex-1")),
                )
            )
        }.body<PaletteResolveResponse>().entries

        assertEquals(listOf("hex-1"), entries.map { it.fixtureKey })
    }

    // ─── Apply ──────────────────────────────────────────────────────────

    @Test
    fun `click-apply writes literals into the programmer`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val template = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-key", rows = listOf(colourRow())))
        }.body<PaletteDto>()

        val applied = client.post("${base()}/${template.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyPaletteRequest(targets = listOf(PaletteTargetDto("fixture", "hex-1"))))
        }
        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        assertEquals(1, applied.body<ApplyPaletteResponse>().written)

        // Literals, not a dependency: the programmer holds a value, and the *stack* holds nothing —
        // which is the whole difference between clicking a chip and ⌥clicking it.
        assertTrue(state.show.programmerStore.size > 0, "the value reached the programmer")
        assertTrue(state.show.programmerStore.layers.isEmpty(), "click adds no layer")
    }

    @Test
    fun `click-apply and toggle refuse a template outside the selection's mask by name`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val template = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-key", rows = listOf(colourRow())))
        }.body<PaletteDto>()
        val targets = listOf(PaletteTargetDto("fixture", "hex-1"))

        // The click: refused before either arm, so nothing reaches the programmer.
        val click = client.post("${base()}/${template.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyPaletteRequest(targets = targets, families = listOf("INTENSITY", "POSITION")))
        }
        assertEquals(HttpStatusCode.BadRequest, click.status, click.bodyAsText())
        assertEquals(CODE_PALETTE_OUTSIDE_MASK, click.body<ErrorResponse>().code)
        assertEquals(0, state.show.programmerStore.size, "no literal landed")

        // The ⌥click: the same refusal, the same code.
        val hold = client.post("${base()}/${template.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(TogglePaletteRequest(targets = targets, families = listOf("INTENSITY")))
        }
        assertEquals(HttpStatusCode.BadRequest, hold.status, hold.bodyAsText())
        assertEquals(CODE_PALETTE_OUTSIDE_MASK, hold.body<ErrorResponse>().code)
        assertTrue(state.show.programmerStore.layers.isEmpty(), "no layer either")

        // Inside the mask, both doors are today's press.
        val applied = client.post("${base()}/${template.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyPaletteRequest(targets = targets, families = listOf("COLOUR")))
        }
        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        assertEquals(1, applied.body<ApplyPaletteResponse>().written)
        val on = client.post("${base()}/${template.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(TogglePaletteRequest(targets = targets, families = listOf("COLOUR")))
        }.body<TogglePaletteResponse>()
        assertEquals("applied", on.action)
        assertEquals("COLOUR", on.attributeMask, "the layer's mask is still the template's own family")

        // The layer is on; a mask that excludes its family no longer stops the off press — the mask
        // is about what a press puts on, and toggle's "already on" reading ignores it.
        val off = client.post("${base()}/${template.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(TogglePaletteRequest(targets = targets, families = listOf("INTENSITY")))
        }
        assertEquals(HttpStatusCode.OK, off.status, off.bodyAsText())
        assertEquals("removed", off.body<TogglePaletteResponse>().action)
        assertTrue(state.show.programmerStore.layers.isEmpty())

        // A name outside the vocabulary is 400 on both doors, as it is on Record.
        val bad = client.post("${base()}/${template.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyPaletteRequest(targets = targets, families = listOf("GOBO")))
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }

    @Test
    fun `applying to a head the template cannot reach reports it rather than failing`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "hazer", "haze-1", 1)
        val client = jsonClient()

        val template = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-key", rows = listOf(colourRow())))
        }.body<PaletteDto>()

        val body = client.post("${base()}/${template.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyPaletteRequest(targets = listOf(PaletteTargetDto("fixture", "haze-1"))))
        }.body<ApplyPaletteResponse>()

        assertEquals(0, body.written)
        assertEquals(1, body.skipped.size)
        assertEquals("haze-1", body.skipped.single().fixtureKey)
    }

    // ─── Apply — the effect arm ─────────────────────────────────────────

    /**
     * The click gesture on an effect template, and the decision that shapes it: the copy is
     * **detached**.
     *
     * It carries no [uk.me.cormack.lighting7.models.LayerSource], which is why `paletteId` is null
     * here and `breathe.id` in the toggle test above. That asymmetry is the feature — click is the
     * effect twin of the value arm's literals, and the value arm leaves no template attribution in
     * the programmer either. `Record files a clicked copy as an ad-hoc effect` is the half of it
     * that would actually bite an operator.
     */
    @Test
    fun `click-apply on an effect template mints detached band copies`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        val client = jsonClient()

        val breathe = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-breathe", fx = colourEffect()))
        }.body<PaletteDto>()

        val applied = client.post("${base()}/${breathe.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(
                ApplyPaletteRequest(
                    targets = listOf(
                        PaletteTargetDto("fixture", "hex-1"),
                        PaletteTargetDto("fixture", "hex-2"),
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        val body = applied.body<ApplyPaletteResponse>()
        assertEquals(2, body.fxIds.size, "one copy per target")
        assertTrue(body.skipped.isEmpty(), "both hexes take colour")
        // An effect writes no programmer values, so the value arm's counter stays at zero rather
        // than being repurposed into an effect count.
        assertEquals(0, body.written)

        val running = state.show.fxEngine.getActiveFx()
        assertEquals(2, running.size)
        assertEquals(body.fxIds.sorted(), running.map { it.id }.sorted())
        assertTrue(
            running.all { FxEngine.isProgrammerFxPriority(it.priority) },
            "a clicked copy sits in the programmer's band, as `+ Effect` does",
        )
        assertTrue(running.all { it.source == null }, "the copy names no template")
        assertTrue(running.all { it.paletteId == null }, "so `FX running` shows it as band-owned")
        // Not a layer, which is the other half of "detached": nothing tracks the template, so
        // nothing recooks or retracts the copy.
        assertTrue(state.show.programmerStore.layers.isEmpty(), "click adds no layer")
        assertTrue(running.all { it.programmerLayerId == null })
    }

    /**
     * Fan-out follows `CueComposer.fxForLayer`, which spawns over a layer's targets **as
     * authored** — so a group stays one group-targeted effect with its distribution intact rather
     * than becoming one instance per member. Clicking and ⌥clicking the same selection then put the
     * same thing on stage, which is the whole promise of the two gestures being a pair.
     */
    @Test
    fun `click-apply keeps a group target's shape`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        LocateTestSupport.seedGroup(state, projectId, "front-wash", "hex-1", "hex-2")
        val client = jsonClient()

        val breathe = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-breathe", fx = colourEffect()))
        }.body<PaletteDto>()

        val body = client.post("${base()}/${breathe.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyPaletteRequest(targets = listOf(PaletteTargetDto("group", "front-wash"))))
        }.body<ApplyPaletteResponse>()

        assertEquals(1, body.fxIds.size, "a group is one effect, not one per member")
        val running = state.show.fxEngine.getActiveFx().single()
        assertTrue(running.isGroupFx)
        assertEquals("front-wash", running.target.targetKey)
    }

    /**
     * The reason the copy carries no source, pinned on both Record forks — they key on different
     * fields and would otherwise disagree about the same instance.
     *
     * `fxInstancesToCueChildren` (programmer Record) skips an effect with a `programmerLayerId`;
     * `captureCurrentState` (the stage snapshot behind `/cues/current-state`) forks on `source`.
     * Stamp the template on a clicked copy and the second one rebuilds it as a *tracking* template
     * layer — the exact opposite of "yours until recorded". The tracked case is the twin test
     * `capture files an effect template's effect as a template layer`.
     */
    @Test
    fun `Record files a clicked copy as an ad-hoc effect, not a template layer`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val breathe = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-breathe", fx = colourEffect()))
        }.body<PaletteDto>()
        client.post("${base()}/${breathe.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyPaletteRequest(targets = listOf(PaletteTargetDto("fixture", "hex-1"))))
        }

        val captured = client
            .get("/api/rest/projects/$projectId/cues/current-state")
            .body<CueCurrentStateResponse>()
        assertTrue(captured.layers.isEmpty(), "a detached copy is nobody's layer")
        val snapshotChild = captured.adHocFx.single()
        assertEquals("hex-1", snapshotChild.targetKey)
        assertEquals("ColourWave", snapshotChild.fxType)

        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-a")
        val recorded: ProgrammerRecordResponse = client.post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(
                ProgrammerRecordRequest(
                    projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
                )
            )
        }.body()
        assertTrue(recorded.cue.layers.isEmpty(), "and nobody's layer on the programmer path either")
        assertEquals("ColourWave", recorded.cue.adHocFx.single().fxType)
    }

    /**
     * The copy carries no `programmerLayerFxKey`, so `ProgrammerLayerStack.syncFx` — which
     * classifies only the instances in `FxEngine.programmerLayerFx()` — cannot see it. Nothing
     * a later edit does reaches it.
     *
     * A *layer* gets the same answer for a different reason (`recookIfReferences` cooks
     * `withFx = false`; see `FU-TMPL-FX-EDIT-NO-RETIME`), and there it is a limitation. Here it
     * is the contract: a clicked copy is a literal, and retuning the template must not move it.
     */
    @Test
    fun `a template edit does not move a clicked copy`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val breathe = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-breathe", fx = colourEffect()))
        }.body<PaletteDto>()
        client.post("${base()}/${breathe.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyPaletteRequest(targets = listOf(PaletteTargetDto("fixture", "hex-1"))))
        }
        assertEquals(0.5, state.show.fxEngine.getActiveFx().single().timing.cycle)

        val edited = client.put("${base()}/${breathe.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteInput(
                    fx = colourEffect().copy(cycle = 2.0, fxType = "RainbowCycle"),
                )
            )
        }
        assertEquals(HttpStatusCode.OK, edited.status, edited.bodyAsText())

        val still = state.show.fxEngine.getActiveFx().single()
        assertEquals(0.5, still.timing.cycle, "the copy kept its own timing")
        assertEquals("ColourWave", still.registrationId, "and its own effect")
    }

    /**
     * `FxTargetFactory` never fails by design — `"rgbColour"` resolves to a `ColourTarget` whether
     * or not the head has colour — so without an explicit capability check the effect would run
     * into nothing and the click would report success. The value arm gets the same honesty from
     * [uk.me.cormack.lighting7.fx.PaletteResolver]; this is its effect twin, and the counterpart
     * to `applying to a head the template cannot reach` above.
     */
    @Test
    fun `click-apply reports a head that cannot take the effect`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "hazer", "haze-1", 1)
        val client = jsonClient()

        val breathe = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-breathe", fx = colourEffect()))
        }.body<PaletteDto>()

        val body = client.post("${base()}/${breathe.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyPaletteRequest(targets = listOf(PaletteTargetDto("fixture", "haze-1"))))
        }.body<ApplyPaletteResponse>()

        assertTrue(body.fxIds.isEmpty(), "nothing was spawned")
        assertEquals("haze-1", body.skipped.single().fixtureKey)
        assertTrue(state.show.fxEngine.getActiveFx().isEmpty())
    }

    /** Clear sweeps the band, and a clicked copy is in it — the operator's way to take one off. */
    @Test
    fun `Clear releases a clicked copy`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val breathe = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-breathe", fx = colourEffect()))
        }.body<PaletteDto>()
        client.post("${base()}/${breathe.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyPaletteRequest(targets = listOf(PaletteTargetDto("fixture", "hex-1"))))
        }
        assertEquals(1, state.show.fxEngine.getActiveFx().size)

        assertEquals(1, clearProgrammerCompletely(state).fxCleared)
        assertTrue(state.show.fxEngine.getActiveFx().isEmpty())
    }

    // ─── Toggle ─────────────────────────────────────────────────────────

    @Test
    fun `toggle adds a tracking layer and takes it off again`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val template = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-key", rows = listOf(colourRow())))
        }.body<PaletteDto>()
        val targets = listOf(PaletteTargetDto("fixture", "hex-1"))

        val on = client.post("${base()}/${template.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(TogglePaletteRequest(targets = targets, attributeMask = "COLOUR"))
        }.body<TogglePaletteResponse>()
        assertEquals("applied", on.action)
        // A *value* template holds no effect, so its count is zero. Still not what drives the pads'
        // active ring, and that is the point of asserting it: an effect template reports non-zero
        // (see below), so a ring driven off this number would light for one kind of template and not
        // the other. `lookLayerPresence` reads the layer stack for exactly that reason.
        assertEquals(0, on.fxCount)
        assertEquals(1, state.show.programmerStore.layers.size)
        assertTrue(state.show.programmerStore.layers.single().source.isPalette)

        val off = client.post("${base()}/${template.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(TogglePaletteRequest(targets = targets))
        }.body<TogglePaletteResponse>()
        assertEquals("removed", off.action)
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    /**
     * Rescued from `PaletteGroupRoutesTest` when template groups went. It never had a group in it:
     * it is the plain pad's sequence — on one head, widen the selection, press, press again — and
     * the second press must clear the template from *both* heads. It used to remove only the layer
     * whose target set matched exactly, leaving the pad reading partial.
     */
    @Test
    fun `widening the selection and pressing twice leaves the pad off`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        val client = jsonClient()

        val red = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "red", rows = listOf(colourRow("#FF0000"))))
        }.body<PaletteDto>()

        suspend fun press(vararg keys: String): TogglePaletteResponse =
            client.post("${base()}/${red.id}/toggle") {
                contentType(ContentType.Application.Json)
                setBody(TogglePaletteRequest(targets = keys.map { PaletteTargetDto("fixture", it) }))
            }.body()

        press("hex-1")
        val wider = press("hex-1", "hex-2")
        assertEquals("applied", wider.action)
        assertEquals(1, state.show.programmerStore.layers.size, "extended, not stacked")
        assertEquals(
            listOf("hex-1", "hex-2"),
            state.show.programmerStore.layers.single().targets.map { it.key }.sorted(),
        )

        val off = press("hex-1", "hex-2")
        assertEquals("removed", off.action)
        assertTrue(state.show.programmerStore.layers.isEmpty(), "off means off, on every pressed head")
    }

    /**
     * The toggle route has no solo siblings — those are a busk bank's — but a palette still takes its
     * heads off the other palettes of its family and kind, as it does through every door.
     */
    @Test
    fun `the toggle route replaces a palette of the same family`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        suspend fun create(name: String, colour: String) = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = name, rows = listOf(colourRow(colour))))
        }.body<PaletteDto>()

        val amber = create("amber", "#FF9D4A")
        val blue = create("blue", "#0000FF")

        suspend fun press(id: Int): TogglePaletteResponse =
            client.post("${base()}/$id/toggle") {
                contentType(ContentType.Application.Json)
                setBody(TogglePaletteRequest(targets = listOf(PaletteTargetDto("fixture", "hex-1"))))
            }.body()

        press(amber.id)
        val blueOn = press(blue.id)
        assertEquals(1, blueOn.released, "a palette replaces its family's other palettes on its heads (apply-model D2)")
        assertEquals(listOf("blue"), state.show.programmerStore.layers.map { it.source.name })
    }

    /**
     * The mask is the server's answer, not the caller's. Pinned because the layer this asserts on is
     * exactly what `programmer.layerState` emits — an unmasked template layer reads as "this could
     * touch anything", and until the mask was derived here every ⌥click and pad press produced one.
     *
     * Both halves matter: a client that says nothing still gets the family, and a client that says
     * the wrong thing does not get to impose it. The response reports what was applied, which is
     * what makes a disagreement visible rather than silent.
     */
    @Test
    fun `toggle masks the layer to the template's own family, whatever the caller claims`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val template = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-key", rows = listOf(colourRow())))
        }.body<PaletteDto>()
        assertEquals("COLOUR", template.family)
        val targets = listOf(PaletteTargetDto("fixture", "hex-1"))

        // Says nothing: the family is still applied.
        val silent = client.post("${base()}/${template.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(TogglePaletteRequest(targets = targets))
        }.body<TogglePaletteResponse>()
        assertEquals("COLOUR", silent.attributeMask)
        assertEquals("COLOUR", state.show.programmerStore.layers.single().attributeMask)

        client.post("${base()}/${template.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(TogglePaletteRequest(targets = targets))
        }

        // Says the wrong thing: the template wins, and the response says so.
        val wrong = client.post("${base()}/${template.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(TogglePaletteRequest(targets = targets, attributeMask = "POSITION"))
        }.body<TogglePaletteResponse>()
        assertEquals("applied", wrong.action)
        assertEquals("COLOUR", wrong.attributeMask)
        assertEquals("COLOUR", state.show.programmerStore.layers.single().attributeMask)
    }

    @Test
    fun `the family filter is an exact partition`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-key", rows = listOf(colourRow())))
        }
        client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteInput(
                    name = "half-up",
                    rows = listOf(PaletteRowDto(DEFERRED_TARGET_TYPE, "", "dimmer", "pct:50")),
                )
            )
        }

        assertEquals(
            listOf("amber-key"),
            client.get("${base()}?family=COLOUR").body<List<PaletteDto>>().map { it.name },
        )
        assertEquals(
            listOf("half-up"),
            client.get("${base()}?family=INTENSITY").body<List<PaletteDto>>().map { it.name },
        )
        assertEquals(2, client.get(base()).body<List<PaletteDto>>().size)
        assertEquals(HttpStatusCode.BadRequest, client.get("${base()}?family=SIDEWAYS").status)
    }

    @Test
    fun `a look row may no longer be deferred, because that is a template now`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val resp = client.post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "should-not-exist",
                    rows = listOf(
                        uk.me.cormack.lighting7.models.LookRowDto(
                            targetType = DEFERRED_TARGET_TYPE, targetKey = "",
                            attribute = "dimmer", value = "128",
                        ),
                    ),
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status, resp.bodyAsText())
        assertTrue(resp.bodyAsText().contains("is a palette"), resp.bodyAsText())
        assertNull(client.get("/api/rest/projects/$projectId/looks").body<List<LookDto>>()
            .firstOrNull { it.name == "should-not-exist" })
    }
    // ─── New from selection ─────────────────────────────────────────────

    @Test
    fun `new from selection records agreeing heads as one generic value`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        val client = jsonClient()

        // Both heads set to the same colour, so the recording should collapse to one row.
        for (key in listOf("hex-1", "hex-2")) {
            state.show.fxEngine.programmer.writeAttributes(
                uk.me.cormack.lighting7.fx.ProgrammerOwner.WEB,
                listOf(
                    uk.me.cormack.lighting7.fx.ProgrammerWriter.AttributeWrite(
                        state.show.fixtures.untypedGroupableFixture(key),
                        "rgbColour",
                        uk.me.cormack.lighting7.fx.CueAssignmentResolver.AttributeValue.Colour(
                            uk.me.cormack.lighting7.fx.ExtendedColour(java.awt.Color(255, 157, 74)),
                        ),
                    ),
                ),
            )
        }

        val resp = client.post("${base()}/from-programmer") {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteFromProgrammerRequest(
                    name = "amber-key",
                    mask = listOf("COLOUR"),
                    targets = listOf(
                        uk.me.cormack.lighting7.models.CueTargetDto("fixture", "hex-1"),
                        uk.me.cormack.lighting7.models.CueTargetDto("fixture", "hex-2"),
                    ),
                )
            )
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val body = resp.body<PaletteFromProgrammerResponse>()

        assertTrue(body.isGeneric, "both heads agreed, so one value serves any head")
        val row = body.palette.rows.single()
        assertEquals(DEFERRED_TARGET_TYPE, row.targetType)
        assertEquals("rgbColour", row.attribute)
        // Read back as an intent, not as the channel bytes it was recorded from.
        assertTrue(row.value.startsWith("#"), row.value)
        assertTrue(row.value.contains("policy="), row.value)
        assertEquals("COLOUR", body.palette.family)
    }

    @Test
    fun `new from selection records a position centre-relative, one row for heads whose degrees differ`() = testApplication {
        mountTestApp(state)
        // A Shehds tilts 0–270°, a MAC 250 0–257°: the same bytes are different degrees, which
        // made the old degree record two per-fixture rows. Centre-relative (venue-visit plan P3),
        // the same bytes are the same fraction of travel, so the heads agree and one row serves.
        LocateTestSupport.seedFixture(state, projectId, "shehds-led19-rgbw-16ch", "mover-1", 1)
        LocateTestSupport.seedFixture(state, projectId, "martin-mac-250-mode-4", "mac-1", 40)
        val client = jsonClient()
        for (key in listOf("mover-1", "mac-1")) {
            state.show.fxEngine.programmer.writeAttributes(
                uk.me.cormack.lighting7.fx.ProgrammerOwner.WEB,
                listOf(
                    uk.me.cormack.lighting7.fx.ProgrammerWriter.AttributeWrite(
                        state.show.fixtures.untypedGroupableFixture(key),
                        "position",
                        uk.me.cormack.lighting7.fx.CueAssignmentResolver.AttributeValue.Position(100u, 200u),
                    ),
                ),
            )
        }

        val body = client.post("${base()}/from-programmer") {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteFromProgrammerRequest(
                    name = "stage left high",
                    mask = listOf("POSITION"),
                    targets = listOf(
                        uk.me.cormack.lighting7.models.CueTargetDto("fixture", "mover-1"),
                        uk.me.cormack.lighting7.models.CueTargetDto("fixture", "mac-1"),
                    ),
                )
            )
        }.body<PaletteFromProgrammerResponse>()

        assertTrue(body.isGeneric, "the same bytes on both heads, so one centre-relative value serves")
        val row = body.palette.rows.single()
        assertEquals(DEFERRED_TARGET_TYPE, row.targetType)
        assertEquals("pos:-28,72", row.value, "100 - 128, 200 - 128")
    }

    @Test
    fun `new from selection keeps per-fixture rows where the heads disagree`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        val client = jsonClient()

        // Different levels per head — the focus-position shape, in the cheapest family to set up.
        for ((key, level) in listOf("hex-1" to 255, "hex-2" to 128)) {
            state.show.fxEngine.programmer.writeAttributes(
                uk.me.cormack.lighting7.fx.ProgrammerOwner.WEB,
                listOf(
                    uk.me.cormack.lighting7.fx.ProgrammerWriter.AttributeWrite(
                        state.show.fixtures.untypedGroupableFixture(key),
                        "dimmer",
                        uk.me.cormack.lighting7.fx.CueAssignmentResolver.AttributeValue.Slider(level.toUByte()),
                    ),
                ),
            )
        }

        val body = client.post("${base()}/from-programmer") {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteFromProgrammerRequest(
                    name = "uneven",
                    mask = listOf("INTENSITY"),
                    targets = listOf(
                        uk.me.cormack.lighting7.models.CueTargetDto("fixture", "hex-1"),
                        uk.me.cormack.lighting7.models.CueTargetDto("fixture", "hex-2"),
                    ),
                )
            )
        }.body<PaletteFromProgrammerResponse>()

        assertTrue(!body.isGeneric, "the heads disagreed, so the values are kept per head")
        assertEquals(2, body.palette.rows.size)
        assertEquals(setOf("hex-1", "hex-2"), body.palette.rows.map { it.targetKey }.toSet())
        // A percentage of each head's own range, which is what makes it re-appliable elsewhere.
        //
        // `pct:50.2`, not `pct:50`: 128 is not half of 255, and the one decimal is what makes the
        // conversion round-trip — `round(0.502 * 255)` is 128 again, where 50% would come back 127.
        // Recording a value and re-applying it must not walk.
        assertEquals(setOf("pct:100", "pct:50.2"), body.palette.rows.map { it.value }.toSet())
    }

    @Test
    fun `new from selection needs exactly one family, and a selection`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val targets = listOf(uk.me.cormack.lighting7.models.CueTargetDto("fixture", "hex-1"))

        val twoFamilies = client.post("${base()}/from-programmer") {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteFromProgrammerRequest(
                    name = "mixed", mask = listOf("COLOUR", "INTENSITY"), targets = targets,
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, twoFamilies.status)
        assertTrue(twoFamilies.bodyAsText().contains("exactly one"), twoFamilies.bodyAsText())

        val noTargets = client.post("${base()}/from-programmer") {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteFromProgrammerRequest(name = "empty", mask = listOf("COLOUR"), targets = emptyList())
            )
        }
        assertEquals(HttpStatusCode.BadRequest, noTargets.status)

        // Nothing busked on that head, so there is nothing to keep.
        val nothingSet = client.post("${base()}/from-programmer") {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteFromProgrammerRequest(name = "nothing", mask = listOf("COLOUR"), targets = targets)
            )
        }
        assertEquals(HttpStatusCode.BadRequest, nothingSet.status)
        assertTrue(nothingSet.bodyAsText().contains("Nothing to record"), nothingSet.bodyAsText())
    }

    // ─── Effect templates ───────────────────────────────────────────────

    @Test
    fun `an effect template round-trips and is banked by its effect's category`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val created = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-breathe", fx = colourEffect()))
        }.body<PaletteDto>()

        assertEquals("fx", created.kind)
        // Derived from the effect's `category`, not from rows it does not have (D4).
        assertEquals("COLOUR", created.family)
        assertTrue(created.rows.isEmpty())
        assertEquals("ColourWave", created.fx?.fxType)
        // An effect fans over whatever the layer names, so there is no per-fixture case for it to
        // be — true rather than the false an "all rows are deferred" test would give (D3).
        assertTrue(created.isGeneric, "an effect template is always generic")

        // The list route's family filter reads the same derivation.
        val colourTab = client.get("${base()}?family=COLOUR").body<List<PaletteDto>>()
        assertTrue(colourTab.any { it.id == created.id }, "effect template missing from its family tab")
    }

    @Test
    fun `the DTO reports the effect's timing source, so cycle has a unit`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // Without this field `cycle` is unreadable: it is beats for a BEAT effect and
        // *seconds* for a WALL_CLOCK one, and nothing else on the DTO says which. A client would
        // otherwise have to fetch the whole FX library to render a template's speed.
        val beat = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-breathe", fx = colourEffect()))
        }.body<PaletteDto>()
        assertEquals("BEAT", beat.fx?.timingSource)

        val wallClock = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteInput(
                    name = "candle",
                    fx = colourEffect(fxType = "Candle Flicker", category = "intensity"),
                )
            )
        }.body<PaletteDto>()
        assertEquals("WALL_CLOCK", wallClock.fx?.timingSource)
    }

    @Test
    fun `timingSource is null when the effect type does not resolve here`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val breathe = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-breathe", fx = colourEffect()))
        }.body<PaletteDto>()

        // Stand in for the two ways this is reachable, neither of them a corrupt state: an import
        // from a desk carrying script-registered effects this one lacks, and — because the registry
        // is the *current show's* — reading another project's library, where an effect only that
        // project's scripts register is unknown here. `validatePaletteContents` refuses authoring
        // such a template, so the DAO is the only way to stand one up.
        transaction(state.database) {
            DaoPalette.findById(breathe.id)!!.effect!!.fxType = "NotInThisRegistry"
        }

        // Null rather than a defaulted "BEAT": the two readings of `cycle` are a tempo apart,
        // so a client is meant to drop the speed clause entirely rather than state a wrong unit.
        // Defaulting here would put the guess back on the server where no reader can see it.
        val reRead = client.get("${base()}/${breathe.id}").body<PaletteDto>()
        assertEquals("NotInThisRegistry", reRead.fx?.fxType)
        assertNull(reRead.fx?.timingSource)
    }

    @Test
    fun `timingSource is resolved on read and ignored on write`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // The registry is the authority — the same stance the write boundary takes on `category`,
        // which it checks against the registration rather than trusting. A client that asserts a
        // timing source its effect does not have is answered with the real one, not obeyed: the
        // field is not stored, so there is nowhere for the lie to live.
        val created = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteInput(
                    name = "liar",
                    fx = colourEffect().copy(timingSource = "WALL_CLOCK"),
                )
            )
        }.body<PaletteDto>()
        assertEquals("BEAT", created.fx?.timingSource)

        val reRead = client.get("${base()}/${created.id}").body<PaletteDto>()
        assertEquals("BEAT", reRead.fx?.timingSource)
    }

    @Test
    fun `a template holding both a value and an effect is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val resp = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "both", rows = listOf(colourRow()), fx = colourEffect()))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("never both"), resp.bodyAsText())
    }

    /**
     * The two categories that belong to no attribute family, and the reason the family map is
     * consulted rather than assumed: there is no column for a template with no family to sit in.
     */
    @Test
    fun `an effect whose category has no family is refused by name`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        for ((fxType, category) in listOf("Whatever" to "controls", "PaletteSteps" to "palette")) {
            val resp = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(
                    PaletteInput(
                        name = "no-family-$category",
                        fx = colourEffect(fxType = fxType, category = category),
                    )
                )
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status, "category '$category' should be refused")
            assertTrue(resp.bodyAsText().contains("has none"), resp.bodyAsText())
        }
    }

    /**
     * A beam effect is a Beam palette (effects plan D6). Its stored effect names no property, so a
     * press lands on the one property the beam effect declares, and a head without it is skipped.
     */
    @Test
    fun `a beam effect palette saves and presses onto the heads with its attribute`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "etc-source4-revolution-base-frame", "rev-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 100)
        val client = jsonClient()

        val created = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteInput(
                    name = "zoom-breathe",
                    fx = colourEffect(fxType = "ZoomWave", category = "beam").copy(attribute = null),
                )
            )
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val palette = created.body<PaletteDto>()
        assertEquals("BEAM", palette.family)

        val body = client.post("${base()}/${palette.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(
                ApplyPaletteRequest(
                    targets = listOf(PaletteTargetDto("fixture", "rev-1"), PaletteTargetDto("fixture", "hex-1")),
                )
            )
        }.body<ApplyPaletteResponse>()

        assertEquals(1, body.fxIds.size, body.toString())
        assertEquals(listOf("hex-1"), body.skipped.map { it.fixtureKey })
        val running = state.show.fxEngine.getActiveFx().single()
        assertEquals("rev-1", running.target.targetKey)
        assertEquals("zoom", running.target.attribute)
    }

    /**
     * A beam effect's palette names no property, so the press needs the one its registration
     * declares. A script-registered beam effect declaring two, or a sentinel, is refused.
     */
    @Test
    fun `a beam effect palette needs an effect that drives one named attribute`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val registry = state.show.fxRegistry
        val sine = registry.getRegistration("SineWave")!!
        for ((id, attributes) in listOf("TwoBeams" to listOf("zoom", "iris"), "AnyBeam" to listOf("slider"))) {
            registry.register(sine.copy(id = id, aliases = emptySet(), name = id, family = "beam", compatibleAttributes = attributes))
        }
        try {
            for (id in listOf("TwoBeams", "AnyBeam")) {
                val resp = client.post(base()) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        PaletteInput(name = "p-$id", fx = colourEffect(fxType = id, category = "beam").copy(attribute = null))
                    )
                }
                assertEquals(HttpStatusCode.BadRequest, resp.status, "$id should be refused")
                assertTrue(resp.bodyAsText().contains("one named property"), resp.bodyAsText())
            }
        } finally {
            registry.unregister("TwoBeams")
            registry.unregister("AnyBeam")
        }
    }

    private suspend fun io.ktor.client.HttpClient.positionPalette(name: String, value: String): PaletteDto =
        post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteInput(
                    name = name,
                    rows = listOf(PaletteRowDto(targetType = DEFERRED_TARGET_TYPE, targetKey = "", attribute = "position", value = value)),
                )
            )
        }.body<PaletteDto>()

    /**
     * Palette Steps sits in the family of the palettes it is given (effects plan §3.6, §4.4): a
     * palette holding it is filed under that family, refused under any other, and refused with
     * fewer than two palettes. Pressed, it lands on the property its palettes name.
     */
    @Test
    fun `a Palette Steps palette is filed under its palettes' family and presses onto their attribute`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "fusion-100-spot-mkii-8ch", "spot-1", 1)
        LocateTestSupport.seedFixture(state, projectId, "varytec-easymove-xl-60-spot-11ch", "spot-2", 21)
        val client = jsonClient()
        val down = client.positionPalette("Downstage", "pos:-64,32")
        val centre = client.positionPalette("Centre", "deg:270,105")
        val refs = listOf(down, centre).joinToString(",") { "pal:${it.uuid}" }
        fun steps(category: String, palettes: String = refs) =
            colourEffect(fxType = "PaletteSteps", category = category, parameters = mapOf("palettes" to palettes))
                .copy(attribute = null)

        for ((category, problem) in listOf(
            "colour" to "is in the position family",
            "steps" to "has none",
        )) {
            val resp = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(PaletteInput(name = "steps-$category", fx = steps(category)))
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status, "category '$category' should be refused")
            assertTrue(resp.bodyAsText().contains(problem), resp.bodyAsText())
        }
        val one = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "steps-one", fx = steps("position", "pal:${down.uuid}")))
        }
        assertEquals(HttpStatusCode.BadRequest, one.status)
        assertTrue(one.bodyAsText().contains("two palettes or more"), one.bodyAsText())

        val created = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "positions", fx = steps("position")))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val palette = created.body<PaletteDto>()
        assertEquals("POSITION", palette.family)

        val body = client.post("${base()}/${palette.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(
                ApplyPaletteRequest(
                    targets = listOf(PaletteTargetDto("fixture", "spot-1"), PaletteTargetDto("fixture", "spot-2")),
                )
            )
        }.body<ApplyPaletteResponse>()
        assertEquals(2, body.fxIds.size, body.toString())
        assertEquals(
            setOf("position"),
            state.show.fxEngine.getActiveFx().map { it.target.attribute }.toSet(),
            "the steps drive the property the palettes name",
        )
    }

    /** Slot Steps names a setting's options, so a palette holding it names the setting it steps. */
    @Test
    fun `a Slot Steps palette names the setting it steps`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        fun slots(attribute: String?, slots: String) =
            colourEffect(fxType = "SlotSteps", category = "beam", parameters = mapOf("slots" to slots))
                .copy(attribute = attribute)

        for ((name, effect, problem) in listOf(
            Triple("no-setting", slots(null, "Gobo 3,Gobo 5"), "must name the setting"),
            Triple("one-slot", slots("gobo", "Gobo 3"), "two slots or more"),
        )) {
            val resp = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(PaletteInput(name = name, fx = effect))
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status, "$name should be refused")
            assertTrue(resp.bodyAsText().contains(problem), resp.bodyAsText())
        }

        val created = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "gobos", fx = slots("gobo", "Gobo 3,Gobo 5")))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        assertEquals("BEAM", created.body<PaletteDto>().family)
    }

    @Test
    fun `an unknown effect type is refused, and so is a category the library disagrees with`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()

            val unknown = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(
                    PaletteInput(name = "nope", fx = colourEffect(fxType = "NotAnFx"))
                )
            }
            assertEquals(HttpStatusCode.BadRequest, unknown.status)
            assertTrue(unknown.bodyAsText().contains("not an effect this desk knows"), unknown.bodyAsText())

            // A real dimmer effect declared as colour: the template would bank under Colour and
            // run as Intensity, so the library is the authority and the two must agree.
            val mismatched = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(
                    PaletteInput(
                        name = "mislabelled",
                        fx = colourEffect(fxType = "Pulse", category = "colour"),
                    )
                )
            }
            assertEquals(HttpStatusCode.BadRequest, mismatched.status)
            assertTrue(mismatched.bodyAsText().contains("is in the intensity family"), mismatched.bodyAsText())
        }

    /**
     * D12 both ways: an effect template's colour parameter may name a *value* colour template, but
     * not itself — the latter would recurse in `createFxWithPalettes`.
     */
    @Test
    fun `an effect parameter may name another template but not this one`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val amber = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-key", rows = listOf(colourRow())))
        }.body<PaletteDto>()

        val breathe = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteInput(
                    name = "amber-breathe",
                    fx = colourEffect(parameters = mapOf("colours" to "pal:${amber.uuid}")),
                )
            )
        }.body<PaletteDto>()
        assertEquals("pal:${amber.uuid}", breathe.fx?.parameters?.get("colours"))

        val selfRef = client.put("${base()}/${breathe.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteInput(
                    fx = colourEffect(parameters = mapOf("colours" to "pal:${breathe.uuid}")),
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, selfRef.status)
        assertTrue(selfRef.bodyAsText().contains("reference the palette itself"), selfRef.bodyAsText())
    }

    /**
     * Holds is identity, like the family: the sheet locks the segment, and the server says so too
     * because the AI surface and a hand-rolled PUT reach the same route.
     */
    @Test
    fun `a PUT cannot flip what a template holds, and refuses before it renames`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val breathe = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-breathe", fx = colourEffect()))
        }.body<PaletteDto>()

        val rowsOnFx = client.put("${base()}/${breathe.id}") {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "renamed", rows = listOf(colourRow())))
        }
        assertEquals(HttpStatusCode.BadRequest, rowsOnFx.status)
        assertTrue(rowsOnFx.bodyAsText().contains("holds an effect"), rowsOnFx.bodyAsText())

        // Exposed commits a transaction that returns normally, so a rename applied before the
        // rejection would have survived it. The validation runs first for exactly this reason.
        val after = client.get("${base()}/${breathe.id}").body<PaletteDto>()
        assertEquals("amber-breathe", after.name, "a refused PUT must not have renamed anything")

        val amber = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-key", rows = listOf(colourRow())))
        }.body<PaletteDto>()
        val fxOnValue = client.put("${base()}/${amber.id}") {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(fx = colourEffect()))
        }
        assertEquals(HttpStatusCode.BadRequest, fxOnValue.status)
        assertTrue(fxOnValue.bodyAsText().contains("holds values"), fxOnValue.bodyAsText())
    }

    @Test
    fun `toggling an effect template spawns its effect on every head the layer names`() =
        testApplication {
            mountTestApp(state)
            LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
            LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
            val client = jsonClient()

            val breathe = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(PaletteInput(name = "amber-breathe", fx = colourEffect()))
            }.body<PaletteDto>()
            val targets = listOf(
                PaletteTargetDto("fixture", "hex-1"),
                PaletteTargetDto("fixture", "hex-2"),
            )

            val on = client.post("${base()}/${breathe.id}/toggle") {
                contentType(ContentType.Application.Json)
                setBody(TogglePaletteRequest(targets = targets))
            }.body<TogglePaletteResponse>()
            assertEquals("applied", on.action)
            // One per head — the layer names the targets, the template names the effect. This is
            // the number that was structurally always zero before the fx-templates plan.
            assertEquals(2, on.fxCount)
            // Derived server-side from the effect's category, not echoed from the request: an
            // unmasked layer would assert across every family instead of the effect's own.
            assertEquals("COLOUR", on.attributeMask)

            val running = state.show.fxEngine.getActiveFx()
            assertEquals(2, running.size)
            // Provenance is the template, so `FX running` can say *in Amber Breathe* — and Record
            // can put it back as a template layer rather than a loose ad-hoc child.
            assertTrue(running.all { it.paletteId == breathe.id }, "effects should name the template")
            assertTrue(running.all { it.lookId == null }, "a template is not a Look")

            val off = client.post("${base()}/${breathe.id}/toggle") {
                contentType(ContentType.Application.Json)
                setBody(TogglePaletteRequest(targets = targets))
            }.body<TogglePaletteResponse>()
            assertEquals("removed", off.action)
            assertEquals(2, off.fxCount)
            assertTrue(state.show.fxEngine.getActiveFx().isEmpty())
        }

    @Test
    fun `the delete guard counts a template running on the programmer`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val breathe = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-breathe", fx = colourEffect()))
        }.body<PaletteDto>()
        client.post("${base()}/${breathe.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(TogglePaletteRequest(targets = listOf(PaletteTargetDto("fixture", "hex-1"))))
        }

        val blocked = client.delete("${base()}/${breathe.id}")
        assertEquals(HttpStatusCode.Conflict, blocked.status)
        val guard = blocked.body<PaletteInUseResponse>()
        // No cue layers — this is live programmer state with no row of its own, which is why it is
        // counted separately from `layerCount`.
        assertEquals(0, guard.layerCount)
        assertEquals(1, guard.runningCount)
        assertTrue(guard.error.contains("the programmer now"), guard.error)

        assertEquals(HttpStatusCode.NoContent, client.delete("${base()}/${breathe.id}?force=true").status)
    }

    /**
     * An effect-only PUT is a **contents** change, so it goes down the republish branch and
     * refreshes the cached snapshot — the next application of the template runs the new effect.
     *
     * What it deliberately does *not* do is retime an instance already on stage.
     * `ProgrammerLayerStack.recookIfReferences` cooks `withFx = false` on purpose: an edit
     * touring to an already-applied layer "is not the layer arriving", and re-spawning would make
     * every nudge of a parameter restart the effect mid-show. A template inherits that rule from a
     * Look unchanged, and this test pins both halves so the asymmetry is deliberate rather than
     * discovered.
     */
    @Test
    fun `an effect-only edit refreshes the snapshot without restarting what is on stage`() =
        testApplication {
            mountTestApp(state)
            LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
            val client = jsonClient()

            val breathe = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(PaletteInput(name = "amber-breathe", fx = colourEffect()))
            }.body<PaletteDto>()
            val targets = listOf(PaletteTargetDto("fixture", "hex-1"))
            client.post("${base()}/${breathe.id}/toggle") {
                contentType(ContentType.Application.Json)
                setBody(TogglePaletteRequest(targets = targets))
            }
            assertEquals(0.5, state.show.fxEngine.getActiveFx().single().timing.cycle)

            val edited = client.put("${base()}/${breathe.id}") {
                contentType(ContentType.Application.Json)
                setBody(
                    PaletteInput(
                        fx = colourEffect().copy(cycle = 2.0, fxType = "RainbowCycle"),
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, edited.status)
            assertEquals("RainbowCycle", edited.body<PaletteDto>().fx?.fxType)

            // Still the old instance, untouched — see the KDoc.
            val duringEdit = state.show.fxEngine.getActiveFx().single()
            assertEquals(0.5, duringEdit.timing.cycle)
            assertEquals(breathe.id, duringEdit.paletteId, "provenance must survive the republish")

            // Re-applying reads the refreshed snapshot, which is what proves the effect-only PUT
            // took the `contentsChanged` branch: the `paletteListChanged` branch would have left
            // the registry serving its pre-edit snapshot.
            for (i in 0..1) {
                client.post("${base()}/${breathe.id}/toggle") {
                    contentType(ContentType.Application.Json)
                    setBody(TogglePaletteRequest(targets = targets))
                }
            }
            val respawned = state.show.fxEngine.getActiveFx().single()
            assertEquals(2.0, respawned.timing.cycle, "re-applying did not pick up the edit")
            assertEquals("RainbowCycle", respawned.registrationId)
        }

    /**
     * Record's stage capture, through the route that exposes it.
     *
     * The regression this guards is specific and silent: `captureCurrentState` used to fork on
     * `effect.lookId != null`, so a template layer's effect — which has no Look id — was filed as a
     * loose **ad-hoc** cue effect. Record would have written it onto the cue as a child, severing
     * the tracking that is the entire point of layering a template, with nothing to show it had
     * happened.
     */
    @Test
    fun `capture files an effect template's effect as a template layer, not an ad-hoc effect`() =
        testApplication {
            mountTestApp(state)
            LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
            val client = jsonClient()

            val breathe = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(PaletteInput(name = "amber-breathe", fx = colourEffect()))
            }.body<PaletteDto>()
            client.post("${base()}/${breathe.id}/toggle") {
                contentType(ContentType.Application.Json)
                setBody(TogglePaletteRequest(targets = listOf(PaletteTargetDto("fixture", "hex-1"))))
            }

            val captured = client
                .get("/api/rest/projects/$projectId/cues/current-state")
                .body<CueCurrentStateResponse>()

            assertTrue(captured.adHocFx.isEmpty(), "a tracked effect is not an ad-hoc child")
            val layer = captured.layers.single()
            assertEquals(breathe.id, layer.paletteId)
            assertNull(layer.lookId, "exactly one of lookId / paletteId is set")
            assertEquals(listOf("hex-1"), layer.targets.map { it.key })
        }

    /**
     * The write boundary checks only what a request actually *sends* — the carve-out
     * `validateSpeedMasterSettings` documents for its follow target.
     *
     * Contents already stored are upstream of this write: the importer writes an effect verbatim
     * on purpose, so a category from a newer build can land in the table. Re-validating on every
     * PUT would then 400 a rename, which is a write that has nothing to do with the contents.
     */
    @Test
    fun `a rename survives stored contents this build would refuse`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val breathe = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-breathe", fx = colourEffect()))
        }.body<PaletteDto>()

        // Stand in for what an import from a newer build leaves behind: a category this build maps
        // to no family, which `validatePaletteContents` refuses outright.
        transaction(state.database) {
            DaoPalette.findById(breathe.id)!!.effect!!.family = "hologram"
        }

        val renamed = client.put("${base()}/${breathe.id}") {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-breathe-2", notes = "renamed", notesPresent = true))
        }
        assertEquals(HttpStatusCode.OK, renamed.status, renamed.bodyAsText())
        assertEquals("amber-breathe-2", renamed.body<PaletteDto>().name)

        // But a write that does touch the effect still goes through the rules.
        val edited = client.put("${base()}/${breathe.id}") {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(fx = colourEffect(category = "hologram")))
        }
        assertEquals(HttpStatusCode.BadRequest, edited.status)
    }

    // ─── Cells (busk-further plan, session 1) ───────────────────────────

    /**
     * The pin for the click door: `paletteApply` already resolved an element key through
     * `Fixtures.untypedGroupableFixture`, and this keeps it that way.
     */
    @Test
    fun `a colour template clicked on two cells changes only those cells`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "led-lightbar-12-pixel-48ch", "bar-1", 1)
        val client = jsonClient()
        val template = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "amber-key", rows = listOf(colourRow())))
        }.body<PaletteDto>()

        val body = client.post("${base()}/${template.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(
                ApplyPaletteRequest(
                    targets = listOf(
                        PaletteTargetDto("fixture", "bar-1.pixel-2"),
                        PaletteTargetDto("fixture", "bar-1.pixel-5"),
                    ),
                ),
            )
        }.body<ApplyPaletteResponse>()

        assertEquals(2, body.written, body.skipped.toString())
        assertTrue(body.skipped.isEmpty(), body.skipped.toString())
        val store = state.show.programmerStore
        val onCell2 = store.get("bar-1.pixel-2", "rgbColour")?.value?.resolved
        assertIs<uk.me.cormack.lighting7.fx.CueAssignmentResolver.AttributeValue.Colour>(onCell2)
        assertEquals(74u.toUByte(), onCell2.value.white, "extracted to the cell's own white emitter")
        assertNotNull(store.get("bar-1.pixel-5", "rgbColour"))
        assertNull(store.get("bar-1.pixel-3", "rgbColour"), "an unnamed cell is untouched")
        assertNull(store.get("bar-1", "rgbColour"), "the parent is untouched")
    }

    // ─── A fixture whose colour lives on its cells (venue-visit plan S1) ─

    /**
     * Two Shehds in 24-channel mode, grouped, and a hex beside them. The Shehds' pan, tilt, dimmer,
     * strobe and zoom are the parent's; its colour is RGBW on three `Zone` elements and the parent
     * has none — which is why a colour template pressed on the parent used to write nothing.
     */
    private fun seedShehds() {
        LocateTestSupport.seedFixture(state, projectId, "shehds-led19-rgbw-24ch", "shehds-1", 1)
        LocateTestSupport.seedFixture(state, projectId, "shehds-led19-rgbw-24ch", "shehds-2", 25)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 49)
        LocateTestSupport.seedGroup(state, projectId, "washes", "shehds-1", "shehds-2")
        LocateTestSupport.seedGroup(state, projectId, "mixed", "shehds-1", "hex-1")
    }

    private fun zones(key: String) = (1..3).map { "$key.zone-$it" }

    private suspend fun io.ktor.client.HttpClient.amberPalette(): PaletteDto = post(base()) {
        contentType(ContentType.Application.Json)
        setBody(PaletteInput(name = "amber-key", rows = listOf(colourRow())))
    }.body()

    private suspend fun io.ktor.client.HttpClient.click(id: Int, vararg targets: PaletteTargetDto): ApplyPaletteResponse {
        val resp = post("${base()}/$id/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyPaletteRequest(targets = targets.toList()))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun io.ktor.client.HttpClient.toggle(id: Int, vararg targets: PaletteTargetDto): TogglePaletteResponse {
        val resp = post("${base()}/$id/toggle") {
            contentType(ContentType.Application.Json)
            setBody(TogglePaletteRequest(targets = targets.toList()))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    /** The colour the programmer holds on [key], and by which owner. */
    private fun heldColour(key: String): Pair<uk.me.cormack.lighting7.fx.ProgrammerOwner, java.awt.Color>? {
        val slot = state.show.programmerStore.get(key, "rgbColour") ?: return null
        val colour = slot.value.resolved as uk.me.cormack.lighting7.fx.CueAssignmentResolver.AttributeValue.Colour
        return slot.owner to colour.value.color
    }

    private fun assertHeldBy(owner: uk.me.cormack.lighting7.fx.ProgrammerOwner, keys: List<String>) {
        for (key in keys) {
            val held = assertNotNull(heldColour(key), "$key holds the colour")
            assertEquals(owner, held.first, "$key is held by $owner")
        }
    }

    @Test
    fun `a colour template clicked on a Shehds lands on its three zones`() = testApplication {
        mountTestApp(state)
        seedShehds()
        val client = jsonClient()
        val template = client.amberPalette()

        val body = client.click(template.id, PaletteTargetDto("fixture", "shehds-1"))

        assertEquals(3, body.written, body.skipped.toString())
        assertTrue(body.skipped.isEmpty(), body.skipped.toString())
        assertHeldBy(uk.me.cormack.lighting7.fx.ProgrammerOwner.WEB, zones("shehds-1"))
        val zone = state.show.programmerStore.get("shehds-1.zone-1", "rgbColour")?.value?.resolved
        assertIs<uk.me.cormack.lighting7.fx.CueAssignmentResolver.AttributeValue.Colour>(zone)
        assertEquals(74u.toUByte(), zone.value.white, "extracted to the zone's own white emitter")
        assertNull(state.show.programmerStore.get("shehds-1", "rgbColour"), "the parent has no colour to hold")
        assertNull(heldColour("shehds-2.zone-1"), "the unselected Shehds is untouched")
    }

    @Test
    fun `apply with write false answers each zone's literal and writes nothing`() = testApplication {
        mountTestApp(state)
        seedShehds()
        val client = jsonClient()
        val template = client.amberPalette()

        val resp = client.post("${base()}/${template.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyPaletteRequest(targets = listOf(PaletteTargetDto("fixture", "shehds-1")), write = false))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val body = resp.body<ApplyPaletteResponse>()

        assertEquals(zones("shehds-1").toSet(), body.values.map { it.target.key }.toSet())
        assertTrue(body.values.all { it.attribute == "rgbColour" && it.value.startsWith("#") }, body.values.toString())
        assertEquals(3, body.written, "what it would have written")
        for (zone in zones("shehds-1")) assertNull(heldColour(zone), "$zone holds nothing")
        val after = client.get("${base()}/${template.id}").body<PaletteDto>()
        assertNull(after.lastPressedAt, "a resolve is not a press")
    }

    @Test
    fun `apply with write false starts no effect`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val template = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "cycle", fx = colourEffect()))
        }.body<PaletteDto>()
        val before = state.show.fxEngine.getActiveFx().size

        val resp = client.post("${base()}/${template.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyPaletteRequest(targets = listOf(PaletteTargetDto("fixture", "hex-1")), write = false))
        }

        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        assertTrue(resp.body<ApplyPaletteResponse>().fxIds.isEmpty())
        assertEquals(before, state.show.fxEngine.getActiveFx().size)
    }

    @Test
    fun `a colour template pressed on a blinder lands on both cells as their nearest white mix`() = testApplication {
        mountTestApp(state)
        // Ch 60 the master dimmer, ch 64–65 and 66–67 the cells' warm and cold whites (D8).
        LocateTestSupport.seedFixture(state, projectId, "china-2-cell-led-blinder-8ch", "blinder-1", 60)
        val client = jsonClient()
        val template = client.amberPalette()
        val mix = uk.me.cormack.lighting7.fixture.dmx.China2CellLedBlinderFixture.WHITE_MIX
        val levels = mix.levelsFor(java.awt.Color(0xFF, 0x9D, 0x4A))

        val body = client.click(template.id, PaletteTargetDto("fixture", "blinder-1"))

        assertEquals(2, body.written, body.skipped.toString())
        val cells = listOf("blinder-1.cell-1", "blinder-1.cell-2")
        assertHeldBy(uk.me.cormack.lighting7.fx.ProgrammerOwner.WEB, cells)
        // Each cell holds the colour its mix makes, which is what it reads back as.
        val held = mix.colourOf(levels.warm, levels.cold)
        for (cell in cells) assertEquals(held, heldColour(cell)?.second, cell)
        assertNull(state.show.programmerStore.get("blinder-1", "rgbColour"), "the parent has no colour to hold")
        val out = mix.levelsFor(held)
        val controller = state.show.fixtures.controllerOrNull(uk.me.cormack.lighting7.dmx.Universe(0, 0)) as uk.me.cormack.lighting7.dmx.MockDmxController
        for (warm in listOf(64, 66)) assertEquals(out.warm, controller.getEffectiveValue(warm), "ch $warm")
        for (cold in listOf(65, 67)) assertEquals(out.cold, controller.getEffectiveValue(cold), "ch $cold")
    }

    @Test
    fun `a colour template toggled on a blinder cooks onto both cells`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "china-2-cell-led-blinder-8ch", "blinder-1", 60)
        val client = jsonClient()
        val template = client.amberPalette()

        assertEquals("applied", client.toggle(template.id, PaletteTargetDto("fixture", "blinder-1")).action)

        assertHeldBy(uk.me.cormack.lighting7.fx.ProgrammerOwner.LAYERS, listOf("blinder-1.cell-1", "blinder-1.cell-2"))
        assertNull(state.show.programmerStore.get("blinder-1", "rgbColour"))
    }

    @Test
    fun `a colour template clicked on one Shehds zone changes only that zone`() = testApplication {
        mountTestApp(state)
        seedShehds()
        val client = jsonClient()
        val template = client.amberPalette()

        val body = client.click(template.id, PaletteTargetDto("fixture", "shehds-1.zone-2"))

        assertEquals(1, body.written, body.skipped.toString())
        assertHeldBy(uk.me.cormack.lighting7.fx.ProgrammerOwner.WEB, listOf("shehds-1.zone-2"))
        assertNull(heldColour("shehds-1.zone-1"))
        assertNull(heldColour("shehds-1.zone-3"))
    }

    @Test
    fun `a colour template clicked through a group of Shehds lands on every zone, carrying the group`() =
        testApplication {
            mountTestApp(state)
            seedShehds()
            val client = jsonClient()
            val template = client.amberPalette()

            val body = client.click(template.id, PaletteTargetDto("group", "washes"))

            assertEquals(6, body.written, body.skipped.toString())
            assertTrue(body.skipped.isEmpty(), body.skipped.toString())
            assertHeldBy(uk.me.cormack.lighting7.fx.ProgrammerOwner.WEB, zones("shehds-1") + zones("shehds-2"))
            assertEquals(
                "washes",
                state.show.programmerStore.get("shehds-2.zone-3", "rgbColour")?.sourceGroup,
                "a zone carries the group its parent arrived through, as a spread's cell does",
            )
        }

    @Test
    fun `a click naming a Shehds and one of its zones writes that zone once`() = testApplication {
        mountTestApp(state)
        seedShehds()
        val client = jsonClient()
        val template = client.amberPalette()

        val body = client.click(
            template.id,
            PaletteTargetDto("fixture", "shehds-1"),
            PaletteTargetDto("fixture", "shehds-1.zone-1"),
        )

        assertEquals(3, body.written, body.skipped.toString())
    }

    @Test
    fun `a template row the Shehds carries itself stays on the parent`() = testApplication {
        mountTestApp(state)
        seedShehds()
        val client = jsonClient()
        val half = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteInput(
                    name = "half",
                    rows = listOf(PaletteRowDto(DEFERRED_TARGET_TYPE, "", "dimmer", "pct:50")),
                ),
            )
        }.body<PaletteDto>()

        val body = client.click(half.id, PaletteTargetDto("fixture", "shehds-1"))

        assertEquals(1, body.written, body.skipped.toString())
        assertNotNull(state.show.programmerStore.get("shehds-1", "dimmer"), "the master dimmer is the parent's")
        assertNull(state.show.programmerStore.get("shehds-1.zone-1", "dimmer"))
    }

    @Test
    fun `a colour template toggled on a Shehds cooks onto its zones`() = testApplication {
        mountTestApp(state)
        seedShehds()
        val client = jsonClient()
        val template = client.amberPalette()

        assertEquals("applied", client.toggle(template.id, PaletteTargetDto("fixture", "shehds-1")).action)

        assertHeldBy(uk.me.cormack.lighting7.fx.ProgrammerOwner.LAYERS, zones("shehds-1"))
        assertNull(state.show.programmerStore.get("shehds-1", "rgbColour"))
        assertEquals("removed", client.toggle(template.id, PaletteTargetDto("fixture", "shehds-1")).action)
        assertNull(heldColour("shehds-1.zone-1"), "the layer took its zones with it")
    }

    @Test
    fun `a colour template toggled on one Shehds zone cooks onto that zone alone`() = testApplication {
        mountTestApp(state)
        seedShehds()
        val client = jsonClient()
        val template = client.amberPalette()

        client.toggle(template.id, PaletteTargetDto("fixture", "shehds-1.zone-3"))

        assertHeldBy(uk.me.cormack.lighting7.fx.ProgrammerOwner.LAYERS, listOf("shehds-1.zone-3"))
        assertNull(heldColour("shehds-1.zone-1"))
    }

    @Test
    fun `a colour template toggled on a group of Shehds and a hex cooks onto every head that has colour`() =
        testApplication {
            mountTestApp(state)
            seedShehds()
            val client = jsonClient()
            val template = client.amberPalette()

            client.toggle(template.id, PaletteTargetDto("group", "mixed"))

            assertHeldBy(uk.me.cormack.lighting7.fx.ProgrammerOwner.LAYERS, zones("shehds-1") + "hex-1")
            assertNull(state.show.programmerStore.get("shehds-1", "rgbColour"))
        }

    @Test
    fun `resolve lists a Shehds by its zones, not as a head with no colour`() = testApplication {
        mountTestApp(state)
        seedShehds()
        val client = jsonClient()

        val entries = client.post("${base()}/resolve") {
            contentType(ContentType.Application.Json)
            setBody(
                PaletteResolveRequest(
                    rows = listOf(colourRow()),
                    targets = listOf(PaletteTargetDto("fixture", "shehds-1")),
                ),
            )
        }.body<PaletteResolveResponse>().entries

        assertEquals(zones("shehds-1"), entries.map { it.fixtureKey })
        assertEquals("shehds-1 · Cell 2", entries[1].fixtureName)
        assertEquals("shehds-led19-rgbw-24ch", entries[1].typeKey)
        assertTrue(entries.all { it.value != null }, entries.toString())
    }
}
