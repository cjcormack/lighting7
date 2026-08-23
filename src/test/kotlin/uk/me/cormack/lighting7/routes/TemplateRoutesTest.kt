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
import org.junit.Test
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.TemplateRowDto
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
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
class TemplateRoutesTest : RouteIntegrationTest() {

    private fun base() = "/api/rest/project/$projectId/templates"

    private fun colourRow(value: String = "#FF9D4A;policy=extract") = TemplateRowDto(
        targetType = DEFERRED_TARGET_TYPE, targetKey = "",
        propertyName = "rgbColour", value = value,
    )

    @Test
    fun `create, list, GET, PUT and DELETE round-trip a generic template`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val createResp = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "amber-key", notes = "warm key", fadeDurationMs = 1_500L, rows = listOf(colourRow())))
        }
        assertEquals(HttpStatusCode.Created, createResp.status, createResp.bodyAsText())
        val created = createResp.body<TemplateDto>()
        assertEquals("amber-key", created.name)
        assertEquals(1_500L, created.fadeDurationMs)
        assertEquals("COLOUR", created.family, "the family is derived from the row, never declared")
        assertTrue(created.isGeneric, "one deferred row is the Generic case")
        assertEquals("#FF9D4A;policy=extract", created.rows.single().value)
        assertEquals(0, created.layerCount)

        val listed = client.get(base()).body<List<TemplateDto>>()
        assertEquals(listOf("amber-key"), listed.map { it.name })

        val fetched = client.get("${base()}/${created.id}").body<TemplateDto>()
        assertEquals(created.uuid, fetched.uuid)

        val updated = client.put("${base()}/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "amber-key", rows = listOf(colourRow("#FFB070;policy=additive"))))
        }.body<TemplateDto>()
        assertEquals("#FFB070;policy=additive", updated.rows.single().value)

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("${base()}/${created.id}").status,
        )
        assertTrue(client.get(base()).body<List<TemplateDto>>().isEmpty())
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
                TemplateInput(
                    name = "downstage-centre",
                    rows = listOf(
                        TemplateRowDto("fixture", "mover-1", "position", "deg:12,-8"),
                        TemplateRowDto("fixture", "mover-2", "position", "deg:-14,-8"),
                    ),
                )
            )
        }.body<TemplateDto>()

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
                TemplateInput(
                    name = "mixed",
                    rows = listOf(
                        colourRow(),
                        TemplateRowDto(DEFERRED_TARGET_TYPE, "", "dimmer", "pct:75"),
                    ),
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("exactly one attribute family"), resp.bodyAsText())
    }

    @Test
    fun `a slotted property is refused, and the message says where it lives instead`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        for (property in listOf("gobo", "goboRotation", "ledMacro")) {
            val resp = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(
                    TemplateInput(
                        name = "gobo-$property",
                        rows = listOf(TemplateRowDto(DEFERRED_TARGET_TYPE, "", property, "pct:50")),
                    )
                )
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status, property)
            // The message has a job beyond refusing: an operator looking for gobo needs to learn
            // *where* it lives, not conclude the desk cannot do it.
            assertTrue(resp.bodyAsText().contains("recorded look"), resp.bodyAsText())
        }
    }

    @Test
    fun `an intent of the wrong shape for its property is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val resp = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                TemplateInput(
                    name = "wrong-shape",
                    // Degrees on a dimmer: a client bug, and storing it would make a row that
                    // resolves to nothing on every head in the rig.
                    rows = listOf(TemplateRowDto(DEFERRED_TARGET_TYPE, "", "dimmer", "deg:45,12")),
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
                TemplateInput(
                    name = "grouped",
                    rows = listOf(TemplateRowDto("group", "front-wash", "rgbColour", "#ff0000;policy=extract")),
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
            setBody(TemplateInput(name = "empty", rows = emptyList()))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `a duplicate name is a conflict`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val body = TemplateInput(name = "amber-key", rows = listOf(colourRow()))
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
            setBody(TemplateResolveRequest(rows = listOf(colourRow())))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val entries = resp.body<TemplateResolveResponse>().entries

        val onHex = entries.single { it.fixtureKey == "hex-1" }
        assertEquals("EXACT", onHex.outcome)
        assertNotNull(onHex.value)

        // The wheel is the interesting one: it reports the slot it landed on and how far off it was,
        // which is what the editor's panel shows before a save.
        val onMac = entries.single { it.fixtureKey == "mac-1" }
        assertEquals("SNAPPED", onMac.outcome)
        assertNotNull(onMac.detail)
        assertNotNull(onMac.deltaE)
        assertEquals("colour", onMac.resolvedPropertyName, "the wheel, not `rgbColour`")

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
                TemplateResolveRequest(
                    rows = listOf(colourRow()),
                    targets = listOf(TemplateTargetDto("fixture", "hex-1")),
                )
            )
        }.body<TemplateResolveResponse>().entries

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
            setBody(TemplateInput(name = "amber-key", rows = listOf(colourRow())))
        }.body<TemplateDto>()

        val applied = client.post("${base()}/${template.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyTemplateRequest(targets = listOf(TemplateTargetDto("fixture", "hex-1"))))
        }
        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        assertEquals(1, applied.body<ApplyTemplateResponse>().written)

        // Literals, not a dependency: the programmer holds a value, and the *stack* holds nothing —
        // which is the whole difference between clicking a chip and ⌥clicking it.
        assertTrue(state.show.programmerStore.size > 0, "the value reached the programmer")
        assertTrue(state.show.programmerStore.layers.isEmpty(), "click adds no layer")
    }

    @Test
    fun `applying to a head the template cannot reach reports it rather than failing`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "hazer", "haze-1", 1)
        val client = jsonClient()

        val template = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "amber-key", rows = listOf(colourRow())))
        }.body<TemplateDto>()

        val body = client.post("${base()}/${template.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyTemplateRequest(targets = listOf(TemplateTargetDto("fixture", "haze-1"))))
        }.body<ApplyTemplateResponse>()

        assertEquals(0, body.written)
        assertEquals(1, body.skipped.size)
        assertEquals("haze-1", body.skipped.single().fixtureKey)
    }

    // ─── Toggle ─────────────────────────────────────────────────────────

    @Test
    fun `toggle adds a tracking layer and takes it off again`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val template = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "amber-key", rows = listOf(colourRow())))
        }.body<TemplateDto>()
        val targets = listOf(TemplateTargetDto("fixture", "hex-1"))

        val on = client.post("${base()}/${template.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleTemplateRequest(targets = targets, propertyMask = "COLOUR"))
        }.body<ToggleTemplateResponse>()
        assertEquals("applied", on.action)
        // A template holds no effects at all, so the count is always zero — the pads' ring cannot be
        // driven from the effect list, which is why `lookLayerPresence` reads the layer stack.
        assertEquals(0, on.effectCount)
        assertEquals(1, state.show.programmerStore.layers.size)
        assertTrue(state.show.programmerStore.layers.single().source.isTemplate)

        val off = client.post("${base()}/${template.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleTemplateRequest(targets = targets))
        }.body<ToggleTemplateResponse>()
        assertEquals("removed", off.action)
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    @Test
    fun `the family filter is an exact partition`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "amber-key", rows = listOf(colourRow())))
        }
        client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                TemplateInput(
                    name = "half-up",
                    rows = listOf(TemplateRowDto(DEFERRED_TARGET_TYPE, "", "dimmer", "pct:50")),
                )
            )
        }

        assertEquals(
            listOf("amber-key"),
            client.get("${base()}?family=COLOUR").body<List<TemplateDto>>().map { it.name },
        )
        assertEquals(
            listOf("half-up"),
            client.get("${base()}?family=INTENSITY").body<List<TemplateDto>>().map { it.name },
        )
        assertEquals(2, client.get(base()).body<List<TemplateDto>>().size)
        assertEquals(HttpStatusCode.BadRequest, client.get("${base()}?family=SIDEWAYS").status)
    }

    @Test
    fun `a look row may no longer be deferred, because that is a template now`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val resp = client.post("/api/rest/project/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = "should-not-exist",
                    rows = listOf(
                        uk.me.cormack.lighting7.models.LookRowDto(
                            targetType = DEFERRED_TARGET_TYPE, targetKey = "",
                            propertyName = "dimmer", value = "128",
                        ),
                    ),
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status, resp.bodyAsText())
        assertTrue(resp.bodyAsText().contains("is a template"), resp.bodyAsText())
        assertNull(client.get("/api/rest/project/$projectId/looks").body<List<LookDto>>()
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
            state.show.fxEngine.writeProgrammerProperties(
                uk.me.cormack.lighting7.fx.ProgrammerOwner.WEB,
                listOf(
                    uk.me.cormack.lighting7.fx.FxEngine.ProgrammerPropertyWrite(
                        state.show.fixtures.untypedGroupableFixture(key),
                        "rgbColour",
                        uk.me.cormack.lighting7.fx.CueAssignmentResolver.PropertyValue.Colour(
                            uk.me.cormack.lighting7.fx.ExtendedColour(java.awt.Color(255, 157, 74)),
                        ),
                    ),
                ),
            )
        }

        val resp = client.post("${base()}/from-programmer") {
            contentType(ContentType.Application.Json)
            setBody(
                TemplateFromProgrammerRequest(
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
        val body = resp.body<TemplateFromProgrammerResponse>()

        assertTrue(body.isGeneric, "both heads agreed, so one value serves any head")
        val row = body.template.rows.single()
        assertEquals(DEFERRED_TARGET_TYPE, row.targetType)
        assertEquals("rgbColour", row.propertyName)
        // Read back as an intent, not as the channel bytes it was recorded from.
        assertTrue(row.value.startsWith("#"), row.value)
        assertTrue(row.value.contains("policy="), row.value)
        assertEquals("COLOUR", body.template.family)
    }

    @Test
    fun `new from selection keeps per-fixture rows where the heads disagree`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        val client = jsonClient()

        // Different levels per head — the focus-position shape, in the cheapest family to set up.
        for ((key, level) in listOf("hex-1" to 255, "hex-2" to 128)) {
            state.show.fxEngine.writeProgrammerProperties(
                uk.me.cormack.lighting7.fx.ProgrammerOwner.WEB,
                listOf(
                    uk.me.cormack.lighting7.fx.FxEngine.ProgrammerPropertyWrite(
                        state.show.fixtures.untypedGroupableFixture(key),
                        "dimmer",
                        uk.me.cormack.lighting7.fx.CueAssignmentResolver.PropertyValue.Slider(level.toUByte()),
                    ),
                ),
            )
        }

        val body = client.post("${base()}/from-programmer") {
            contentType(ContentType.Application.Json)
            setBody(
                TemplateFromProgrammerRequest(
                    name = "uneven",
                    mask = listOf("INTENSITY"),
                    targets = listOf(
                        uk.me.cormack.lighting7.models.CueTargetDto("fixture", "hex-1"),
                        uk.me.cormack.lighting7.models.CueTargetDto("fixture", "hex-2"),
                    ),
                )
            )
        }.body<TemplateFromProgrammerResponse>()

        assertTrue(!body.isGeneric, "the heads disagreed, so the values are kept per head")
        assertEquals(2, body.template.rows.size)
        assertEquals(setOf("hex-1", "hex-2"), body.template.rows.map { it.targetKey }.toSet())
        // A percentage of each head's own range, which is what makes it re-appliable elsewhere.
        //
        // `pct:50.2`, not `pct:50`: 128 is not half of 255, and the one decimal is what makes the
        // conversion round-trip — `round(0.502 * 255)` is 128 again, where 50% would come back 127.
        // Recording a value and re-applying it must not walk.
        assertEquals(setOf("pct:100", "pct:50.2"), body.template.rows.map { it.value }.toSet())
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
                TemplateFromProgrammerRequest(
                    name = "mixed", mask = listOf("COLOUR", "INTENSITY"), targets = targets,
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, twoFamilies.status)
        assertTrue(twoFamilies.bodyAsText().contains("exactly one"), twoFamilies.bodyAsText())

        val noTargets = client.post("${base()}/from-programmer") {
            contentType(ContentType.Application.Json)
            setBody(
                TemplateFromProgrammerRequest(name = "empty", mask = listOf("COLOUR"), targets = emptyList())
            )
        }
        assertEquals(HttpStatusCode.BadRequest, noTargets.status)

        // Nothing busked on that head, so there is nothing to keep.
        val nothingSet = client.post("${base()}/from-programmer") {
            contentType(ContentType.Application.Json)
            setBody(
                TemplateFromProgrammerRequest(name = "nothing", mask = listOf("COLOUR"), targets = targets)
            )
        }
        assertEquals(HttpStatusCode.BadRequest, nothingSet.status)
        assertTrue(nothingSet.bodyAsText().contains("Nothing to record"), nothingSet.bodyAsText())
    }
}
