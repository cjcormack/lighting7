package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
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
import uk.me.cormack.lighting7.fx.BeatDivision
import uk.me.cormack.lighting7.fx.EffectDto
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxTargetRef
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.TemplateEffectDto
import uk.me.cormack.lighting7.plugins.FxChangeType
import uk.me.cormack.lighting7.plugins.FxChangedOutMessage
import uk.me.cormack.lighting7.plugins.FxErrorOutMessage
import uk.me.cormack.lighting7.plugins.InMessage
import uk.me.cormack.lighting7.plugins.ProgrammerClearTargetInMessage
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.plugins.UpdateFxInMessage
import uk.me.cormack.lighting7.testsupport.EffectTestSupport
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Apply-model plan session 2 on the wire (§3.4, D5): the bare effect press
 * (`POST /programmer/effects`), the Local replacement it makes, and the split — `PUT /fx/{id}` and
 * `updateFx` with `targets` naming some of an effect's heads. That the copies paint exactly what the
 * effect painted is `EffectSplitTest`'s.
 */
class LocalEffectRoutesTest : RouteIntegrationTest() {

    private val engine get() = state.show.fxEngine

    private val hexes = (0 until 4).map { "hex-$it" }

    private fun seedRig() {
        for ((i, key) in hexes.withIndex()) LocateTestSupport.seedHex(state, projectId, key, 1 + 12 * i)
        LocateTestSupport.seedGroup(state, projectId, "quad", *hexes.toTypedArray())
    }

    private fun fixtures(vararg keys: String) = keys.map { CueTargetDto(TargetRef.Fixture.TYPE, it) }

    private val quad = listOf(CueTargetDto(TargetRef.Group.TYPE, "quad"))

    private fun pulse(targets: List<CueTargetDto>, families: List<String>? = null) =
        ProgrammerEffectRequest(effectType = "Pulse", propertyName = "dimmer", targets = targets, families = families)

    private fun colourCycle(targets: List<CueTargetDto>) =
        ProgrammerEffectRequest(effectType = "ColourCycle", propertyName = "rgbColour", targets = targets)

    private suspend fun HttpClient.press(request: ProgrammerEffectRequest): HttpResponse =
        post("/api/rest/projects/$projectId/programmer/effects") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.pressed(request: ProgrammerEffectRequest): ProgrammerEffectResponse {
        val response = press(request)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun HttpClient.edit(id: Long, request: UpdateEffectRequest): HttpResponse =
        put("/api/rest/fx/$id") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private fun live(id: Long): FxInstance = assertNotNull(engine.getEffect(id), "effect $id is running")

    // ── The bare effect press ──────────────────────────────────────────────────

    @Test
    fun `a bare effect on four fixtures is one Local instance over them`() = testApplication {
        mountTestApp(state)
        seedRig()
        val pressed = jsonClient().pressed(pulse(fixtures(*hexes.toTypedArray())))

        val instance = live(pressed.effectId)
        assertEquals(1, engine.getActiveEffects().size, "one instance, not one per fixture")
        assertIs<FxTargetRef.SelectionRef>(instance.target.targetRef)
        assertEquals(hexes, engine.fixtureKeysCoveredBy(instance))
        assertTrue(instance.isLocalEffect, "the programmer's own")
        assertEquals(FxEngine.PROGRAMMER_FX_PRIORITY_BASE, instance.priority)
        assertEquals(BeatDivision.ONE_BAR, instance.timing.beatDivision, "one bar when no division is sent (D16)")
    }

    @Test
    fun `on a group it is one group effect`() = testApplication {
        mountTestApp(state)
        seedRig()
        val instance = live(jsonClient().pressed(pulse(quad)).effectId)
        assertEquals(FxTargetRef.GroupRef("quad"), instance.target.targetRef)
    }

    @Test
    fun `no selection and a family outside the mask are refused, and nothing starts`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()

        val none = client.press(pulse(emptyList()))
        assertEquals(HttpStatusCode.BadRequest, none.status)
        assertEquals(CODE_EFFECT_NEEDS_SELECTION, none.body<ErrorResponse>().code)

        val outside = client.press(pulse(quad, families = listOf("COLOUR")))
        assertEquals(HttpStatusCode.BadRequest, outside.status)
        assertEquals(CODE_EFFECT_OUTSIDE_MASK, outside.body<ErrorResponse>().code)

        assertEquals(HttpStatusCode.OK, client.press(pulse(quad, families = listOf("INTENSITY", "COLOUR"))).status)
        assertEquals(1, engine.getActiveEffects().size)
    }

    @Test
    fun `it takes the master tagged for its family, unless it names one or runs on Seconds`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        assertNull(live(client.pressed(pulse(quad)).effectId).speedMasterUuid, "no master tagged: master 1")

        val tagged = client.post("/api/rest/projects/$projectId/speed-masters") {
            contentType(ContentType.Application.Json)
            setBody(CreateSpeedMasterRequest(name = "Chase", usage = "dimmer"))
        }.body<SpeedMasterDto>()
        assertEquals(tagged.uuid, live(client.pressed(pulse(quad)).effectId).speedMasterUuid.toString())

        val onSeconds = live(client.pressed(pulse(quad).copy(seconds = true)).effectId)
        assertTrue(onSeconds.onSeconds)
        assertNull(onSeconds.speedMasterUuid)

        val master1 = state.show.fxEngine.speedMasters.masterStates().first().uuid.toString()
        assertEquals(master1, live(client.pressed(pulse(quad).copy(speedMasterUuid = master1)).effectId).speedMasterUuid.toString())
    }

    // ── Local replacement ─────────────────────────────────────────────────────

    @Test
    fun `a second colour effect on two of four takes those two off the first`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val first = client.pressed(colourCycle(fixtures(*hexes.toTypedArray()))).effectId
        val dimmer = client.pressed(pulse(quad)).effectId

        val second = client.pressed(colourCycle(fixtures("hex-0", "hex-1")))

        assertNull(engine.getEffect(first), "the first is rewritten, one instance per head it keeps")
        val narrowed = second.narrowed.single()
        assertEquals(first, narrowed.effectId)
        assertEquals(listOf("hex-2", "hex-3"), narrowed.into.map { live(it).target.targetKey })
        assertTrue(narrowed.into.all { live(it).isLocalEffect })
        assertEquals(listOf("hex-0", "hex-1"), engine.fixtureKeysCoveredBy(live(second.effectId)))
        assertEquals(dimmer, live(dimmer).id, "another family is left alone")
        assertTrue(second.stopped.isEmpty())
    }

    @Test
    fun `an effect whose every head is pressed stops`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val first = client.pressed(colourCycle(fixtures("hex-0", "hex-1"))).effectId

        val second = client.pressed(colourCycle(quad))
        assertEquals(listOf(first), second.stopped)
        assertNull(engine.getEffect(first))
        assertEquals(1, engine.getActiveEffects().size)
    }

    @Test
    fun `a palette layer's effect is not Local and the press leaves it`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val layered = pressPaletteLayer(client, quad)

        client.pressed(pulse(quad))
        assertEquals(layered.id, live(layered.id).id)
    }

    // ── The split ─────────────────────────────────────────────────────────────

    @Test
    fun `a subset edit of a group effect leaves the rest running and starts the subset where it was`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val parent = client.pressed(pulse(quad)).effectId
        val offsets = engine.paintedHeads(live(parent)).associate { it.key to it.offset }

        val response = client.edit(parent, UpdateEffectRequest(beatDivision = 2.0, targets = fixtures("hex-0", "hex-1")))
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val split = response.body<EffectSplitResponse>()

        assertEquals(parent, split.splitEffectId)
        assertNull(engine.getEffect(parent))
        assertEquals(listOf("hex-0", "hex-1"), split.edited.map { it.targetKey })
        assertEquals(listOf("hex-2", "hex-3"), split.rest.map { it.targetKey })
        assertTrue(split.edited.all { it.beatDivision == 2.0 })
        assertTrue(split.rest.all { it.beatDivision == BeatDivision.ONE_BAR }, "the rest run on as they were")
        for (dto in split.edited + split.rest) {
            val copy = live(dto.id)
            assertEquals(offsets.getValue(dto.targetKey), assertNotNull(copy.splitMember).offset, "${dto.targetKey} starts where it was")
            assertEquals(4, copy.splitMember!!.groupSize)
            assertTrue(copy.isLocalEffect)
        }
    }

    @Test
    fun `a whole-set edit keeps the id, however the set is named`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val id = client.pressed(pulse(quad)).effectId

        for (targets in listOf(quad, fixtures(*hexes.toTypedArray()), fixtures(*hexes.reversed().toTypedArray()))) {
            val response = client.edit(id, UpdateEffectRequest(beatDivision = 2.0, targets = targets))
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals(id, response.body<EffectDto>().id)
        }
        assertEquals(1, engine.getActiveEffects().size)
    }

    @Test
    fun `a layer's instance refuses a split, and a cue's or a manual one is not Local`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val layered = pressPaletteLayer(client, quad)
        val toLayer = client.edit(layered.id, UpdateEffectRequest(beatDivision = 2.0, targets = fixtures("hex-0")))
        assertEquals(HttpStatusCode.Conflict, toLayer.status)
        assertEquals(CODE_FX_SPLIT_LAYER_EFFECT, toLayer.body<ErrorResponse>().code)
        assertEquals(0.5, live(layered.id).timing.beatDivision, "refused whole")

        val manual = EffectTestSupport.start(state, "Pulse", "quad", "dimmer", group = true)
        val toManual = client.edit(manual.id, UpdateEffectRequest(beatDivision = 2.0, targets = fixtures("hex-0")))
        assertEquals(HttpStatusCode.Conflict, toManual.status)
        assertEquals(CODE_FX_SPLIT_NOT_LOCAL, toManual.body<ErrorResponse>().code)

        val whole = client.edit(layered.id, UpdateEffectRequest(beatDivision = 2.0, targets = quad))
        assertEquals(HttpStatusCode.OK, whole.status, "a whole-set edit of a layer's effect is the in-place edit")
    }

    @Test
    fun `a shape edit on some heads is refused, and the shape it has is not`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val id = client.pressed(pulse(quad)).effectId
        val order = live(id).distributionStrategy.javaClass.simpleName

        val moved = client.edit(id, UpdateEffectRequest(distributionStrategy = "CENTER_OUT", blocks = 2, targets = fixtures("hex-0")))
        assertEquals(HttpStatusCode.BadRequest, moved.status)
        val message = moved.body<ErrorResponse>().error
        assertContains(message, "distributionStrategy")
        assertContains(message, "blocks")
        assertEquals(id, live(id).id, "nothing moved")

        val same = client.edit(id, UpdateEffectRequest(distributionStrategy = order, blocks = 1, beatDivision = 2.0, targets = fixtures("hex-0")))
        assertEquals(HttpStatusCode.OK, same.status, same.bodyAsText())
        assertEquals(listOf("hex-0"), same.body<EffectSplitResponse>().edited.map { it.targetKey })
    }

    @Test
    fun `targets naming none of its heads are refused`() = testApplication {
        mountTestApp(state)
        seedRig()
        LocateTestSupport.seedHex(state, projectId, "hex-9", 100)
        val client = jsonClient()
        val id = client.pressed(pulse(quad)).effectId

        val response = client.edit(id, UpdateEffectRequest(beatDivision = 2.0, targets = fixtures("hex-9")))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(CODE_FX_UPDATE_REFUSED, response.body<ErrorResponse>().code)
        assertEquals(id, live(id).id)
    }

    @Test
    fun `updateFx with targets splits, answering the effect removed and each copy added`() = testApplication {
        mountTestApp(state)
        seedRig()
        val id = jsonClient().pressed(pulse(quad)).effectId
        val layered = pressPaletteLayer(jsonClient(), quad)

        createWsClient().webSocket("/api") {
            sendSerialized<InMessage>(UpdateFxInMessage(id, beatDivision = 2.0, targets = fixtures("hex-1")))
            val removed = awaitOfType<FxChangedOutMessage>()
            assertEquals(FxChangeType.REMOVED, removed.changeType)
            assertEquals(id, removed.effectId)
            val added = List(4) { awaitOfType<FxChangedOutMessage>() }
            assertTrue(added.all { it.changeType == FxChangeType.ADDED })
            assertEquals("hex-1", live(added.first().effectId!!).target.targetKey, "the edited copy first")
            assertEquals(2.0, live(added.first().effectId!!).timing.beatDivision)

            sendSerialized<InMessage>(UpdateFxInMessage(layered.id, beatDivision = 2.0, targets = fixtures("hex-0")))
            val error = awaitOfType<FxErrorOutMessage>()
            assertEquals(layered.id, error.effectId)
            assertEquals(CODE_FX_SPLIT_LAYER_EFFECT, error.code)
        }
    }

    @Test
    fun `Release and Clear take the split instances`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val id = client.pressed(pulse(quad)).effectId
        val split = client.edit(id, UpdateEffectRequest(beatDivision = 2.0, targets = fixtures("hex-0"))).body<EffectSplitResponse>()

        val released = ProgrammerHandler.clearTarget(state, ProgrammerClearTargetInMessage("fixture", "hex-2", 0, requestId = "r"))
        assertEquals(1, released.effects)
        assertNull(engine.getEffect(split.rest.single { it.targetKey == "hex-2" }.id))

        clearProgrammerCompletely(state)
        assertTrue(engine.getActiveEffects().isEmpty(), "every copy is in the programmer's band")
    }

    @Test
    fun `Record writes a split copy at the phase it ran`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val id = client.pressed(pulse(quad)).effectId
        val split = client.edit(id, UpdateEffectRequest(phaseOffset = 0.5, targets = fixtures("hex-2"))).body<EffectSplitResponse>()
        val edited = live(split.edited.single().id)
        assertEquals(0.5, edited.phaseOffset, "the edit's phase offset, as on the whole effect")

        val recorded = captureCurrentState(state).adHocEffects.single { it.targetKey == "hex-2" }
        assertEquals(0.5 - edited.splitMember!!.offset, recorded.phaseOffset, 1e-12)
    }

    @Test
    fun `the family is the property's group, so a Pulse on UV is Colour`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val onUv = pulse(quad).copy(propertyName = "uv")

        val underIntensity = client.press(onUv.copy(families = listOf("INTENSITY")))
        assertEquals(HttpStatusCode.BadRequest, underIntensity.status)
        assertEquals(CODE_EFFECT_OUTSIDE_MASK, underIntensity.body<ErrorResponse>().code)
        val uv = client.pressed(onUv.copy(families = listOf("COLOUR"))).effectId

        val dimmer = client.pressed(pulse(quad))
        assertTrue(dimmer.stopped.isEmpty() && dimmer.narrowed.isEmpty(), "a dimmer press leaves a UV pulse alone")
        assertEquals(uv, live(uv).id)
        assertEquals(listOf(uv), client.pressed(colourCycle(quad)).stopped, "a colour press takes it off")
    }

    @Test
    fun `Update after a split writes the copies and retires the child they stand for`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val cueId = ProgrammerRouteTestSupport.createCue(
            client, projectId, "chase",
            adHoc = listOf(CueAdHocEffectSpec(targetType = "group", targetKey = "quad", effectType = "Pulse", propertyName = "dimmer")),
        )
        val include = client.post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerIncludeRequest(projectId = projectId.toString(), cueId = cueId))
        }
        assertEquals(HttpStatusCode.OK, include.status, include.bodyAsText())
        val included = engine.getActiveEffects().single()
        val split = client.edit(included.id, UpdateEffectRequest(beatDivision = 2.0, targets = fixtures("hex-0")))
        assertEquals(HttpStatusCode.OK, split.status, split.bodyAsText())

        suspend fun update() {
            val response = client.post("/api/rest/programmer/update") {
                contentType(ContentType.Application.Json)
                setBody(ProgrammerUpdateRequest(projectId = projectId.toString()))
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        }
        suspend fun children() = client.get("/api/rest/projects/$projectId/cues/$cueId").body<CueDetails>().adHocEffects

        update()
        val written = children()
        assertEquals(hexes, written.map { it.targetKey }.sorted(), "one child per head, the group child gone")
        assertEquals(2.0, written.single { it.targetKey == "hex-0" }.beatDivision)

        update()
        assertEquals(hexes, children().map { it.targetKey }.sorted(), "a second Update retires nothing more")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** An effect palette pressed as a layer onto [targets]; answers the instance it spawned. */
    private suspend fun pressPaletteLayer(client: HttpClient, targets: List<CueTargetDto>): FxInstance {
        val palette = client.post("/api/rest/projects/$projectId/palettes") {
            contentType(ContentType.Application.Json)
            setBody(
                TemplateInput(
                    name = "Chase ${engine.getActiveEffects().size}",
                    effect = TemplateEffectDto(
                        effectType = "Pulse", category = "dimmer", propertyName = "dimmer",
                        beatDivision = 0.5, blendMode = "OVERRIDE", distribution = "LINEAR",
                    ),
                ),
            )
        }.body<TemplateDto>()
        val response = client.post("/api/rest/projects/$projectId/palettes/${palette.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleTemplateRequest(targets = targets.map { TemplateTargetDto(it.type, it.key) }))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return engine.getActiveEffects().single { it.programmerLayerEffectKey != null && it.templateId == palette.id }
    }
}
