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
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.TemplateEffectDto
import uk.me.cormack.lighting7.models.TemplateRowDto
import uk.me.cormack.lighting7.fx.FxEngine
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

    private fun base() = "/api/rest/projects/$projectId/templates"

    private fun colourRow(value: String = "#FF9D4A;policy=extract") = TemplateRowDto(
        targetType = DEFERRED_TARGET_TYPE, targetKey = "",
        propertyName = "rgbColour", value = value,
    )

    private fun colourEffect(
        effectType: String = "ColourPulse",
        category: String = "colour",
        parameters: Map<String, String> = emptyMap(),
    ) = TemplateEffectDto(
        effectType = effectType,
        category = category,
        propertyName = "rgbColour",
        beatDivision = 0.5,
        blendMode = "OVERRIDE",
        distribution = "UNIFIED",
        parameters = parameters,
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

    // ─── Apply — the effect arm ─────────────────────────────────────────

    /**
     * The click gesture on an effect template, and the decision that shapes it: the copy is
     * **detached**.
     *
     * It carries no [uk.me.cormack.lighting7.models.LayerSource], which is why `templateId` is null
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
            setBody(TemplateInput(name = "amber-breathe", effect = colourEffect()))
        }.body<TemplateDto>()

        val applied = client.post("${base()}/${breathe.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(
                ApplyTemplateRequest(
                    targets = listOf(
                        TemplateTargetDto("fixture", "hex-1"),
                        TemplateTargetDto("fixture", "hex-2"),
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        val body = applied.body<ApplyTemplateResponse>()
        assertEquals(2, body.effectIds.size, "one copy per target")
        assertTrue(body.skipped.isEmpty(), "both hexes take colour")
        // An effect writes no programmer values, so the value arm's counter stays at zero rather
        // than being repurposed into an effect count.
        assertEquals(0, body.written)

        val running = state.show.fxEngine.getActiveEffects()
        assertEquals(2, running.size)
        assertEquals(body.effectIds.sorted(), running.map { it.id }.sorted())
        assertTrue(
            running.all { FxEngine.isProgrammerFxPriority(it.priority) },
            "a clicked copy sits in the programmer's band, as `+ Effect` does",
        )
        assertTrue(running.all { it.source == null }, "the copy names no template")
        assertTrue(running.all { it.templateId == null }, "so `FX running` shows it as band-owned")
        // Not a layer, which is the other half of "detached": nothing tracks the template, so
        // nothing recooks or retracts the copy.
        assertTrue(state.show.programmerStore.layers.isEmpty(), "click adds no layer")
        assertTrue(running.all { it.programmerLayerId == null })
    }

    /**
     * Fan-out follows `CueComposer.effectsForLayer`, which spawns over a layer's targets **as
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
            setBody(TemplateInput(name = "amber-breathe", effect = colourEffect()))
        }.body<TemplateDto>()

        val body = client.post("${base()}/${breathe.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyTemplateRequest(targets = listOf(TemplateTargetDto("group", "front-wash"))))
        }.body<ApplyTemplateResponse>()

        assertEquals(1, body.effectIds.size, "a group is one effect, not one per member")
        val running = state.show.fxEngine.getActiveEffects().single()
        assertTrue(running.isGroupEffect)
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
            setBody(TemplateInput(name = "amber-breathe", effect = colourEffect()))
        }.body<TemplateDto>()
        client.post("${base()}/${breathe.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyTemplateRequest(targets = listOf(TemplateTargetDto("fixture", "hex-1"))))
        }

        val captured = client
            .get("/api/rest/projects/$projectId/cues/current-state")
            .body<CueCurrentStateResponse>()
        assertTrue(captured.layers.isEmpty(), "a detached copy is nobody's layer")
        val snapshotChild = captured.adHocEffects.single()
        assertEquals("hex-1", snapshotChild.targetKey)
        assertEquals("ColourPulse", snapshotChild.effectType)

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
        assertEquals("ColourPulse", recorded.cue.adHocEffects.single().effectType)
    }

    /**
     * The copy carries no `programmerLayerEffectKey`, so `ProgrammerLayerStack.syncEffects` — which
     * classifies only the instances in `FxEngine.programmerLayerEffects()` — cannot see it. Nothing
     * a later edit does reaches it.
     *
     * A *layer* gets the same answer for a different reason (`recookIfReferences` cooks
     * `withEffects = false`; see `FU-TMPL-FX-EDIT-NO-RETIME`), and there it is a limitation. Here it
     * is the contract: a clicked copy is a literal, and retuning the template must not move it.
     */
    @Test
    fun `a template edit does not move a clicked copy`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val breathe = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "amber-breathe", effect = colourEffect()))
        }.body<TemplateDto>()
        client.post("${base()}/${breathe.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyTemplateRequest(targets = listOf(TemplateTargetDto("fixture", "hex-1"))))
        }
        assertEquals(0.5, state.show.fxEngine.getActiveEffects().single().timing.beatDivision)

        val edited = client.put("${base()}/${breathe.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                TemplateInput(
                    effect = colourEffect().copy(beatDivision = 2.0, effectType = "RainbowCycle"),
                )
            )
        }
        assertEquals(HttpStatusCode.OK, edited.status, edited.bodyAsText())

        val still = state.show.fxEngine.getActiveEffects().single()
        assertEquals(0.5, still.timing.beatDivision, "the copy kept its own timing")
        assertEquals("ColourPulse", still.registrationId, "and its own effect")
    }

    /**
     * `FxTargetFactory` never fails by design — `"rgbColour"` resolves to a `ColourTarget` whether
     * or not the head has colour — so without an explicit capability check the effect would run
     * into nothing and the click would report success. The value arm gets the same honesty from
     * [uk.me.cormack.lighting7.fx.TemplateResolver]; this is its effect twin, and the counterpart
     * to `applying to a head the template cannot reach` above.
     */
    @Test
    fun `click-apply reports a head that cannot take the effect`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "hazer", "haze-1", 1)
        val client = jsonClient()

        val breathe = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "amber-breathe", effect = colourEffect()))
        }.body<TemplateDto>()

        val body = client.post("${base()}/${breathe.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyTemplateRequest(targets = listOf(TemplateTargetDto("fixture", "haze-1"))))
        }.body<ApplyTemplateResponse>()

        assertTrue(body.effectIds.isEmpty(), "nothing was spawned")
        assertEquals("haze-1", body.skipped.single().fixtureKey)
        assertTrue(state.show.fxEngine.getActiveEffects().isEmpty())
    }

    /** Clear sweeps the band, and a clicked copy is in it — the operator's way to take one off. */
    @Test
    fun `Clear releases a clicked copy`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val breathe = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "amber-breathe", effect = colourEffect()))
        }.body<TemplateDto>()
        client.post("${base()}/${breathe.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyTemplateRequest(targets = listOf(TemplateTargetDto("fixture", "hex-1"))))
        }
        assertEquals(1, state.show.fxEngine.getActiveEffects().size)

        assertEquals(1, clearProgrammerCompletely(state).effectsCleared)
        assertTrue(state.show.fxEngine.getActiveEffects().isEmpty())
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
        // A *value* template holds no effect, so its count is zero. Still not what drives the pads'
        // active ring, and that is the point of asserting it: an effect template reports non-zero
        // (see below), so a ring driven off this number would light for one kind of template and not
        // the other. `lookLayerPresence` reads the layer stack for exactly that reason.
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
            setBody(TemplateInput(name = "amber-key", rows = listOf(colourRow())))
        }.body<TemplateDto>()
        assertEquals("COLOUR", template.family)
        val targets = listOf(TemplateTargetDto("fixture", "hex-1"))

        // Says nothing: the family is still applied.
        val silent = client.post("${base()}/${template.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleTemplateRequest(targets = targets))
        }.body<ToggleTemplateResponse>()
        assertEquals("COLOUR", silent.propertyMask)
        assertEquals("COLOUR", state.show.programmerStore.layers.single().propertyMask)

        client.post("${base()}/${template.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleTemplateRequest(targets = targets))
        }

        // Says the wrong thing: the template wins, and the response says so.
        val wrong = client.post("${base()}/${template.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleTemplateRequest(targets = targets, propertyMask = "POSITION"))
        }.body<ToggleTemplateResponse>()
        assertEquals("applied", wrong.action)
        assertEquals("COLOUR", wrong.propertyMask)
        assertEquals("COLOUR", state.show.programmerStore.layers.single().propertyMask)
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
        val resp = client.post("/api/rest/projects/$projectId/looks") {
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
            state.show.fxEngine.programmer.writeProperties(
                uk.me.cormack.lighting7.fx.ProgrammerOwner.WEB,
                listOf(
                    uk.me.cormack.lighting7.fx.ProgrammerWriter.PropertyWrite(
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
            state.show.fxEngine.programmer.writeProperties(
                uk.me.cormack.lighting7.fx.ProgrammerOwner.WEB,
                listOf(
                    uk.me.cormack.lighting7.fx.ProgrammerWriter.PropertyWrite(
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

    // ─── Effect templates ───────────────────────────────────────────────

    @Test
    fun `an effect template round-trips and is banked by its effect's category`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val created = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "amber-breathe", effect = colourEffect()))
        }.body<TemplateDto>()

        assertEquals("effect", created.kind)
        // Derived from the effect's `category`, not from rows it does not have (D4).
        assertEquals("COLOUR", created.family)
        assertTrue(created.rows.isEmpty())
        assertEquals("ColourPulse", created.effect?.effectType)
        // An effect fans over whatever the layer names, so there is no per-fixture case for it to
        // be — true rather than the false an "all rows are deferred" test would give (D3).
        assertTrue(created.isGeneric, "an effect template is always generic")

        // The list route's family filter reads the same derivation.
        val colourTab = client.get("${base()}?family=COLOUR").body<List<TemplateDto>>()
        assertTrue(colourTab.any { it.id == created.id }, "effect template missing from its family tab")
    }

    @Test
    fun `the DTO reports the effect's timing source, so beatDivision has a unit`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // Without this field `beatDivision` is unreadable: it is beats for a BEAT effect and
        // *seconds* for a WALL_CLOCK one, and nothing else on the DTO says which. A client would
        // otherwise have to fetch the whole FX library to render a template's speed.
        val beat = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "amber-breathe", effect = colourEffect()))
        }.body<TemplateDto>()
        assertEquals("BEAT", beat.effect?.timingSource)

        val wallClock = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                TemplateInput(
                    name = "candle",
                    effect = colourEffect(effectType = "Candle Flicker", category = "dimmer"),
                )
            )
        }.body<TemplateDto>()
        assertEquals("WALL_CLOCK", wallClock.effect?.timingSource)
    }

    @Test
    fun `timingSource is null when the effect type does not resolve here`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val breathe = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "amber-breathe", effect = colourEffect()))
        }.body<TemplateDto>()

        // Stand in for the two ways this is reachable, neither of them a corrupt state: an import
        // from a desk carrying script-registered effects this one lacks, and — because the registry
        // is the *current show's* — reading another project's library, where an effect only that
        // project's scripts register is unknown here. `validateTemplateContents` refuses authoring
        // such a template, so the DAO is the only way to stand one up.
        transaction(state.database) {
            DaoTemplate.findById(breathe.id)!!.effect!!.effectType = "NotInThisRegistry"
        }

        // Null rather than a defaulted "BEAT": the two readings of `beatDivision` are a tempo apart,
        // so a client is meant to drop the speed clause entirely rather than state a wrong unit.
        // Defaulting here would put the guess back on the server where no reader can see it.
        val reRead = client.get("${base()}/${breathe.id}").body<TemplateDto>()
        assertEquals("NotInThisRegistry", reRead.effect?.effectType)
        assertNull(reRead.effect?.timingSource)
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
                TemplateInput(
                    name = "liar",
                    effect = colourEffect().copy(timingSource = "WALL_CLOCK"),
                )
            )
        }.body<TemplateDto>()
        assertEquals("BEAT", created.effect?.timingSource)

        val reRead = client.get("${base()}/${created.id}").body<TemplateDto>()
        assertEquals("BEAT", reRead.effect?.timingSource)
    }

    @Test
    fun `a template holding both a value and an effect is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val resp = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "both", rows = listOf(colourRow()), effect = colourEffect()))
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

        for ((effectType, category) in listOf("LightningStrike" to "composite", "Whatever" to "controls")) {
            val resp = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(
                    TemplateInput(
                        name = "no-family-$category",
                        effect = colourEffect(effectType = effectType, category = category),
                    )
                )
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status, "category '$category' should be refused")
            assertTrue(resp.bodyAsText().contains("has none"), resp.bodyAsText())
        }
    }

    /**
     * Beam is refused by *name* rather than by the library happening to ship no beam effect — so a
     * script-registered beam effect cannot mint a Beam effect template behind the rule.
     */
    @Test
    fun `a beam-category effect is refused with the reason`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val resp = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                TemplateInput(
                    name = "beamy",
                    effect = colourEffect(effectType = "Zoomy", category = "beam"),
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("BEAM template cannot hold an effect"), resp.bodyAsText())
    }

    @Test
    fun `an unknown effect type is refused, and so is a category the library disagrees with`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()

            val unknown = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(
                    TemplateInput(name = "nope", effect = colourEffect(effectType = "NotAnEffect"))
                )
            }
            assertEquals(HttpStatusCode.BadRequest, unknown.status)
            assertTrue(unknown.bodyAsText().contains("not an effect this desk knows"), unknown.bodyAsText())

            // A real dimmer effect declared as colour: the template would bank under Colour and
            // run as Intensity, so the library is the authority and the two must agree.
            val mismatched = client.post(base()) {
                contentType(ContentType.Application.Json)
                setBody(
                    TemplateInput(
                        name = "mislabelled",
                        effect = colourEffect(effectType = "Pulse", category = "colour"),
                    )
                )
            }
            assertEquals(HttpStatusCode.BadRequest, mismatched.status)
            assertTrue(mismatched.bodyAsText().contains("is a dimmer effect"), mismatched.bodyAsText())
        }

    /**
     * D12 both ways: an effect template's colour parameter may name a *value* colour template, but
     * not itself — the latter would recurse in `createEffectWithTemplates`.
     */
    @Test
    fun `an effect parameter may name another template but not this one`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val amber = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "amber-key", rows = listOf(colourRow())))
        }.body<TemplateDto>()

        val breathe = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(
                TemplateInput(
                    name = "amber-breathe",
                    effect = colourEffect(parameters = mapOf("colours" to "tmpl:${amber.uuid}")),
                )
            )
        }.body<TemplateDto>()
        assertEquals("tmpl:${amber.uuid}", breathe.effect?.parameters?.get("colours"))

        val selfRef = client.put("${base()}/${breathe.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                TemplateInput(
                    effect = colourEffect(parameters = mapOf("colours" to "tmpl:${breathe.uuid}")),
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, selfRef.status)
        assertTrue(selfRef.bodyAsText().contains("reference the template itself"), selfRef.bodyAsText())
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
            setBody(TemplateInput(name = "amber-breathe", effect = colourEffect()))
        }.body<TemplateDto>()

        val rowsOnEffect = client.put("${base()}/${breathe.id}") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "renamed", rows = listOf(colourRow())))
        }
        assertEquals(HttpStatusCode.BadRequest, rowsOnEffect.status)
        assertTrue(rowsOnEffect.bodyAsText().contains("holds an effect"), rowsOnEffect.bodyAsText())

        // Exposed commits a transaction that returns normally, so a rename applied before the
        // rejection would have survived it. The validation runs first for exactly this reason.
        val after = client.get("${base()}/${breathe.id}").body<TemplateDto>()
        assertEquals("amber-breathe", after.name, "a refused PUT must not have renamed anything")

        val amber = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "amber-key", rows = listOf(colourRow())))
        }.body<TemplateDto>()
        val effectOnValue = client.put("${base()}/${amber.id}") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(effect = colourEffect()))
        }
        assertEquals(HttpStatusCode.BadRequest, effectOnValue.status)
        assertTrue(effectOnValue.bodyAsText().contains("holds values"), effectOnValue.bodyAsText())
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
                setBody(TemplateInput(name = "amber-breathe", effect = colourEffect()))
            }.body<TemplateDto>()
            val targets = listOf(
                TemplateTargetDto("fixture", "hex-1"),
                TemplateTargetDto("fixture", "hex-2"),
            )

            val on = client.post("${base()}/${breathe.id}/toggle") {
                contentType(ContentType.Application.Json)
                setBody(ToggleTemplateRequest(targets = targets))
            }.body<ToggleTemplateResponse>()
            assertEquals("applied", on.action)
            // One per head — the layer names the targets, the template names the effect. This is
            // the number that was structurally always zero before the fx-templates plan.
            assertEquals(2, on.effectCount)
            // Derived server-side from the effect's category, not echoed from the request: an
            // unmasked layer would assert across every family instead of the effect's own.
            assertEquals("COLOUR", on.propertyMask)

            val running = state.show.fxEngine.getActiveEffects()
            assertEquals(2, running.size)
            // Provenance is the template, so `FX running` can say *in Amber Breathe* — and Record
            // can put it back as a template layer rather than a loose ad-hoc child.
            assertTrue(running.all { it.templateId == breathe.id }, "effects should name the template")
            assertTrue(running.all { it.lookId == null }, "a template is not a Look")

            val off = client.post("${base()}/${breathe.id}/toggle") {
                contentType(ContentType.Application.Json)
                setBody(ToggleTemplateRequest(targets = targets))
            }.body<ToggleTemplateResponse>()
            assertEquals("removed", off.action)
            assertEquals(2, off.effectCount)
            assertTrue(state.show.fxEngine.getActiveEffects().isEmpty())
        }

    @Test
    fun `the delete guard counts a template running on the programmer`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val breathe = client.post(base()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "amber-breathe", effect = colourEffect()))
        }.body<TemplateDto>()
        client.post("${base()}/${breathe.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleTemplateRequest(targets = listOf(TemplateTargetDto("fixture", "hex-1"))))
        }

        val blocked = client.delete("${base()}/${breathe.id}")
        assertEquals(HttpStatusCode.Conflict, blocked.status)
        val guard = blocked.body<TemplateInUseResponse>()
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
     * `ProgrammerLayerStack.recookIfReferences` cooks `withEffects = false` on purpose: an edit
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
                setBody(TemplateInput(name = "amber-breathe", effect = colourEffect()))
            }.body<TemplateDto>()
            val targets = listOf(TemplateTargetDto("fixture", "hex-1"))
            client.post("${base()}/${breathe.id}/toggle") {
                contentType(ContentType.Application.Json)
                setBody(ToggleTemplateRequest(targets = targets))
            }
            assertEquals(0.5, state.show.fxEngine.getActiveEffects().single().timing.beatDivision)

            val edited = client.put("${base()}/${breathe.id}") {
                contentType(ContentType.Application.Json)
                setBody(
                    TemplateInput(
                        effect = colourEffect().copy(beatDivision = 2.0, effectType = "RainbowCycle"),
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, edited.status)
            assertEquals("RainbowCycle", edited.body<TemplateDto>().effect?.effectType)

            // Still the old instance, untouched — see the KDoc.
            val duringEdit = state.show.fxEngine.getActiveEffects().single()
            assertEquals(0.5, duringEdit.timing.beatDivision)
            assertEquals(breathe.id, duringEdit.templateId, "provenance must survive the republish")

            // Re-applying reads the refreshed snapshot, which is what proves the effect-only PUT
            // took the `contentsChanged` branch: the `templateListChanged` branch would have left
            // the registry serving its pre-edit snapshot.
            for (i in 0..1) {
                client.post("${base()}/${breathe.id}/toggle") {
                    contentType(ContentType.Application.Json)
                    setBody(ToggleTemplateRequest(targets = targets))
                }
            }
            val respawned = state.show.fxEngine.getActiveEffects().single()
            assertEquals(2.0, respawned.timing.beatDivision, "re-applying did not pick up the edit")
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
                setBody(TemplateInput(name = "amber-breathe", effect = colourEffect()))
            }.body<TemplateDto>()
            client.post("${base()}/${breathe.id}/toggle") {
                contentType(ContentType.Application.Json)
                setBody(ToggleTemplateRequest(targets = listOf(TemplateTargetDto("fixture", "hex-1"))))
            }

            val captured = client
                .get("/api/rest/projects/$projectId/cues/current-state")
                .body<CueCurrentStateResponse>()

            assertTrue(captured.adHocEffects.isEmpty(), "a tracked effect is not an ad-hoc child")
            val layer = captured.layers.single()
            assertEquals(breathe.id, layer.templateId)
            assertNull(layer.lookId, "exactly one of lookId / templateId is set")
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
            setBody(TemplateInput(name = "amber-breathe", effect = colourEffect()))
        }.body<TemplateDto>()

        // Stand in for what an import from a newer build leaves behind: a category this build maps
        // to no family, which `validateTemplateContents` refuses outright.
        transaction(state.database) {
            DaoTemplate.findById(breathe.id)!!.effect!!.category = "hologram"
        }

        val renamed = client.put("${base()}/${breathe.id}") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "amber-breathe-2", notes = "renamed", notesPresent = true))
        }
        assertEquals(HttpStatusCode.OK, renamed.status, renamed.bodyAsText())
        assertEquals("amber-breathe-2", renamed.body<TemplateDto>().name)

        // But a write that does touch the effect still goes through the rules.
        val edited = client.put("${base()}/${breathe.id}") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(effect = colourEffect(category = "hologram")))
        }
        assertEquals(HttpStatusCode.BadRequest, edited.status)
    }
}
