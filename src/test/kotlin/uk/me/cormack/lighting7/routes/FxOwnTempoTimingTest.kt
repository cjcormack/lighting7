package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fx.CycleLength
import uk.me.cormack.lighting7.fx.FxDto
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxSpawner
import uk.me.cormack.lighting7.fx.FxTargetFactory
import uk.me.cormack.lighting7.models.SpeedMasterSource
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.fx.settingsSnapshot
import uk.me.cormack.lighting7.models.CueAdHocFxDto
import uk.me.cormack.lighting7.models.LookFxSpec
import uk.me.cormack.lighting7.models.withCycleOverride
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Fx-speed plan session 1: an effect's clock is a speed master or its *Own tempo* (D3), and a
 * wall-clock effect's a rate master or *Natural* (D4), carried through the add and update routes (the
 * `updateFx` frame shares the update's parse), the old `seconds` read by D5. A new effect is one bar
 * (venue-visit plan D16).
 */
class FxOwnTempoTimingTest : RouteIntegrationTest() {

    private val engine get() = state.show.fxEngine

    private fun masterUuid(index: Int) = engine.speedMasters.masterStates()[index].uuid!!.toString()

    private suspend fun io.ktor.client.HttpClient.add(request: AddFxRequest) =
        post("/api/rest/fx/add") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun io.ktor.client.HttpClient.update(id: Long, request: UpdateFxRequest) =
        put("/api/rest/fx/$id") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private fun effect(id: Long): FxInstance = assertNotNull(engine.getFx(id))

    @Test
    fun `a beat effect on Own tempo runs on the wall-clock loop with no master, its cycle in beats`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val id = client.add(
            AddFxRequest(fxType = "Wave", fixtureKey = "hex-1", attribute = "dimmer", cycle = 4.0, ownBpm = 96.0),
        ).body<AddFxResponse>().fxId

        val instance = effect(id)
        assertEquals(96.0, instance.ownBpm)
        assertTrue(instance.onOwnTempo)
        assertFalse(instance.onNatural)
        assertEquals(TimingSource.WALL_CLOCK, instance.timingSource, "Own tempo runs on the wall-clock loop")
        assertEquals(TimingSource.BEAT, instance.registrationTimingSource)
        assertNull(instance.speedMasterUuid)
        assertNull(instance.rateSpeedMasterUuid)
        assertEquals(4.0, instance.timing.cycle, "the cycle stays beats")
        assertEquals(96.0 / 60.0, instance.ownTempoScale)

        val dto = engine.fxDto(instance)
        assertEquals(96.0, dto.ownBpm)
        assertFalse(dto.natural)
        assertEquals("WALL_CLOCK", dto.timingSource)
    }

    @Test
    fun `the old seconds with a cycle is Own tempo 120 at twice it, and without one the master's tempo`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val withCycle = effect(
            client.add(AddFxRequest(fxType = "Wave", fixtureKey = "hex-1", attribute = "dimmer", cycle = 5.0, seconds = true))
                .body<AddFxResponse>().fxId,
        )
        assertEquals(120.0, withCycle.ownBpm)
        assertEquals(10.0, withCycle.timing.cycle, "five seconds is ten beats at 120")

        engine.speedMasters.setBpm(null, 90.0, SpeedMasterSource.MANUAL)
        val noCycle = effect(
            client.add(AddFxRequest(fxType = "Wave", fixtureKey = "hex-1", attribute = "dimmer", seconds = true))
                .body<AddFxResponse>().fxId,
        )
        assertEquals(90.0, noCycle.ownBpm, "onto Own tempo at the tempo of the master it would have followed")
        assertEquals(CycleLength.ONE_BAR, noCycle.timing.cycle)
    }

    @Test
    fun `Own tempo beside a master, on a wall-clock effect or out of range is refused`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val master = masterUuid(0)

        for (request in listOf(
            AddFxRequest(fxType = "Wave", fixtureKey = "hex-1", attribute = "dimmer", ownBpm = 96.0, speedMasterUuid = master),
            AddFxRequest(fxType = "Wave", fixtureKey = "hex-1", attribute = "dimmer", seconds = true, speedMasterUuid = master),
            AddFxRequest(fxType = "Wave", fixtureKey = "hex-1", attribute = "dimmer", ownBpm = 10.0),
            AddFxRequest(fxType = "FluorescentFlicker", fixtureKey = "hex-1", attribute = "dimmer", ownBpm = 96.0),
            AddFxRequest(fxType = "Wave", fixtureKey = "hex-1", attribute = "dimmer", natural = true),
        )) {
            assertEquals(HttpStatusCode.BadRequest, client.add(request).status, request.toString())
        }
        assertTrue(engine.getActiveFx().isEmpty())

        val id = client.add(AddFxRequest(fxType = "Wave", fixtureKey = "hex-1", attribute = "dimmer"))
            .body<AddFxResponse>().fxId
        val before = effect(id)
        val update = client.update(id, UpdateFxRequest(ownBpm = 96.0, speedMasterUuid = master))
        assertEquals(HttpStatusCode.BadRequest, update.status)
        assertEquals(CODE_FX_UPDATE_REFUSED, update.body<ErrorResponse>().code)
        assertEquals(HttpStatusCode.BadRequest, client.update(id, UpdateFxRequest(ownBpm = 400.0)).status)
        assertSame(before, effect(id), "a refused update moves nothing")
    }

    @Test
    fun `an omitted division is one bar`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val id = jsonClient().add(AddFxRequest(fxType = "Wave", fixtureKey = "hex-1", attribute = "dimmer"))
            .body<AddFxResponse>().fxId
        assertEquals(CycleLength.ONE_BAR, effect(id).timing.cycle)
    }

    @Test
    fun `an update moves a beat effect onto Own tempo and back, keeping its id`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val second = masterUuid(1).takeIf { engine.speedMasters.masterStates().size > 1 }
        val id = client.add(AddFxRequest(fxType = "Wave", fixtureKey = "hex-1", attribute = "dimmer"))
            .body<AddFxResponse>().fxId

        val onOwn = client.update(id, UpdateFxRequest(ownBpm = 100.0)).body<FxDto>()
        assertEquals(id, onOwn.id)
        assertEquals(100.0, onOwn.ownBpm)
        assertEquals(TimingSource.WALL_CLOCK, effect(id).timingSource)
        assertEquals(CycleLength.ONE_BAR, effect(id).timing.cycle, "the cycle is kept")

        // Leaving Own tempo onto a master with none named is master 1 — null, as everywhere.
        val back = client.update(id, UpdateFxRequest(seconds = false)).body<FxDto>()
        assertNull(back.ownBpm)
        assertEquals(TimingSource.BEAT, effect(id).timingSource)
        assertNull(effect(id).speedMasterUuid)

        // Naming a master alone takes an effect off Own tempo too.
        client.update(id, UpdateFxRequest(ownBpm = 100.0))
        val master = second ?: masterUuid(0)
        client.update(id, UpdateFxRequest(speedMasterUuid = master))
        assertFalse(effect(id).onOwnTempo)
        assertEquals(master, effect(id).speedMasterUuid.toString())
        assertEquals(TimingSource.BEAT, effect(id).timingSource)
    }

    @Test
    fun `a new Own tempo moves only the rate, so the phase is kept`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val id = client.add(AddFxRequest(fxType = "Wave", fixtureKey = "hex-1", attribute = "dimmer", ownBpm = 100.0))
            .body<AddFxResponse>().fxId
        val instance = effect(id)
        instance.accumulatedScaledMs = 1234.5

        val moved = client.update(id, UpdateFxRequest(ownBpm = 132.0)).body<FxDto>()
        assertEquals(132.0, moved.ownBpm)
        assertSame(instance, effect(id), "a new tempo is written in place, not a swap")
        assertEquals(1234.5, effect(id).accumulatedScaledMs, "the accumulator, and so the phase, is kept")
        assertEquals(132.0 / 60.0, effect(id).ownTempoScale)
    }

    @Test
    fun `a wall-clock effect's Natural is its unscaled rate, and leaving it names a master`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val master = masterUuid(0)
        assertEquals(TimingSource.WALL_CLOCK, state.show.fxRegistry.getRegistration("FluorescentFlicker")!!.timingSource)

        val id = client.add(
            AddFxRequest(fxType = "FluorescentFlicker", fixtureKey = "hex-1", attribute = "dimmer", rateSpeedMasterUuid = master),
        ).body<AddFxResponse>().fxId
        assertEquals(TimingSource.WALL_CLOCK, effect(id).timingSource)
        assertFalse(effect(id).onNatural)

        val unscaled = client.update(id, UpdateFxRequest(natural = true)).body<FxDto>()
        assertTrue(unscaled.natural)
        assertNull(unscaled.ownBpm)
        assertNull(effect(id).rateSpeedMasterUuid)
        assertEquals(FxInstance.NO_RATE_MASTER, effect(id).rateMasterSlot)

        // The old seconds on a wall-clock effect is Natural too.
        client.update(id, UpdateFxRequest(rateSpeedMasterUuid = master))
        assertFalse(effect(id).onNatural)
        assertTrue(client.update(id, UpdateFxRequest(seconds = true)).body<FxDto>().natural)

        // Leaving Natural has to name the master, since a null rate master *is* Natural.
        assertEquals(HttpStatusCode.BadRequest, client.update(id, UpdateFxRequest(natural = false)).status)
        assertEquals(HttpStatusCode.BadRequest, client.update(id, UpdateFxRequest(ownBpm = 96.0)).status)
        val scaled = client.update(id, UpdateFxRequest(natural = false, rateSpeedMasterUuid = master)).body<FxDto>()
        assertFalse(scaled.natural)
        assertEquals(master, effect(id).rateSpeedMasterUuid.toString())
    }

    @Test
    fun `a wall-clock effect added to a group runs on the wall-clock loop`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        LocateTestSupport.seedGroup(state, projectId, "front", "hex-1", "hex-2")
        val response = jsonClient().post("/api/rest/groups/front/fx") {
            contentType(ContentType.Application.Json)
            setBody(AddGroupFxRequest(fxType = "FluorescentFlicker", attribute = "dimmer"))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.toString())
        val id = response.body<AddGroupFxResponse>().fxId
        assertEquals(TimingSource.WALL_CLOCK, effect(id).timingSource, "the group route takes the type's loop since S15")
        assertEquals(CycleLength.ONE_BAR, effect(id).timing.cycle)
    }

    @Test
    fun `a group effect takes an Own tempo`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        LocateTestSupport.seedGroup(state, projectId, "front", "hex-1", "hex-2")
        val response = jsonClient().post("/api/rest/groups/front/fx") {
            contentType(ContentType.Application.Json)
            setBody(AddGroupFxRequest(fxType = "Wave", attribute = "dimmer", ownBpm = 72.0))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.toString())
        val instance = effect(response.body<AddGroupFxResponse>().fxId)
        assertEquals(72.0, instance.ownBpm)
        assertEquals(TimingSource.WALL_CLOCK, instance.timingSource)
    }

    @Test
    fun `a stored effect on Own tempo spawns with no master, whatever its layer overrides`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val master = java.util.UUID.fromString(masterUuid(0))
        val spec = LookFxSpec(
            fxType = "Wave", family = "intensity", attribute = "dimmer", cycle = 5.0,
            blendMode = "OVERRIDE", order = "NORMAL", ownBpm = 84.0,
        )
        val target = FxTargetFactory.forFixture("hex-1", "dimmer", null, state.show.fixtures.untypedGroupableFixture("hex-1"))
        val instance = FxSpawner.createFxInstance(
            spec, target, state, overrideSpeedMasterUuid = master, overrideRateSpeedMasterUuid = master,
        )
        assertEquals(84.0, instance.ownBpm)
        assertEquals(TimingSource.WALL_CLOCK, instance.timingSource)
        assertNull(instance.speedMasterUuid, "a layer's master override does not reach an effect on Own tempo")
        assertNull(instance.rateSpeedMasterUuid)

        val onMaster = FxSpawner.createFxInstance(spec.copy(ownBpm = null), target, state, overrideSpeedMasterUuid = master)
        assertNull(onMaster.ownBpm)
        assertEquals(TimingSource.BEAT, onMaster.timingSource)
        assertEquals(master, onMaster.speedMasterUuid)
    }

    @Test
    fun `a stored wall-clock effect naming an Own tempo runs on Natural at the seconds it meant`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val spec = LookFxSpec(
            fxType = "FluorescentFlicker", family = "intensity", attribute = "dimmer", cycle = 8.0,
            blendMode = "OVERRIDE", order = "NORMAL", ownBpm = 120.0,
        )
        val target = FxTargetFactory.forFixture("hex-1", "dimmer", null, state.show.fixtures.untypedGroupableFixture("hex-1"))
        val instance = FxSpawner.createFxInstance(spec, target, state)
        assertNull(instance.ownBpm)
        assertTrue(instance.onNatural)
        assertEquals(4.0, instance.timing.cycle, "eight beats at 120 is four seconds")
    }

    @Test
    fun `a stored write refuses an Own tempo on a wall-clock effect, and keeps one on a beat effect`() {
        fun look(fxType: String) = uk.me.cormack.lighting7.models.LookFxDto(
            targetType = "fixture", targetKey = "hex-1", fxType = fxType, family = "intensity", attribute = "dimmer",
            cycle = 4.0, blendMode = "OVERRIDE", order = "NORMAL", ownBpm = 96.0,
        )
        assertNotNull(validateLookFx(listOf(look("CandleFlicker")), state.show.fxRegistry))
        assertNull(validateLookFx(listOf(look("Wave")), state.show.fxRegistry))
    }

    @Test
    fun `only a wall-clock effect the database step can name is refused an Own tempo on a stored write`() {
        val candle = state.show.fxRegistry.getRegistration("CandleFlicker")!!
        assertTrue(namedByStep(candle), "a built-in")
        val definition = candle.copy(id = "Smoke", source = uk.me.cormack.lighting7.fx.FxSource.USER, script = "// smoke")
        assertTrue(namedByStep(definition), "a project's FX definition carries its script")
        val scripted = candle.copy(id = "Haze", source = uk.me.cormack.lighting7.fx.FxSource.USER, script = null)
        assertFalse(namedByStep(scripted), "one an FX_DEFINITION script registers is the step's blind spot")
    }

    @Test
    fun `Update stage sends the cycle with a move between Natural and Own tempo, so the swap keeps it`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val master1 = engine.speedMasters.masterStates().first().uuid
        val natural = buildFxInstance(state, AddFxRequest(fxType = "CandleFlicker", fixtureKey = "hex-1", attribute = "dimmer", cycle = 4.0))
        val own = buildFxInstance(state, AddFxRequest(fxType = "Wave", fixtureKey = "hex-1", attribute = "dimmer", cycle = 4.0, ownBpm = 120.0))
        val request = alignmentRequest(natural.settingsSnapshot(master1), own.settingsSnapshot(master1))
        assertEquals(4.0, request.cycle, "the same number is sent, as the cycle is read on a new clock")
        assertEquals(120.0, request.ownBpm)

        val faster = buildFxInstance(state, AddFxRequest(fxType = "Wave", fixtureKey = "hex-1", attribute = "dimmer", cycle = 4.0, ownBpm = 132.0))
        assertNull(alignmentRequest(own.settingsSnapshot(master1), faster.settingsSnapshot(master1)).cycle, "a new tempo alone keeps the cycle unsent")
    }

    @Test
    fun `a layer's division override leaves an effect on Own tempo alone`() {
        val spec = LookFxSpec(
            fxType = "Wave", family = "intensity", cycle = 12.0, blendMode = "OVERRIDE", order = "NORMAL",
        )
        assertEquals(2.0, spec.withCycleOverride(2.0).cycle)
        assertEquals(12.0, spec.copy(ownBpm = 90.0).withCycleOverride(2.0).cycle)
        assertSame(spec, spec.withCycleOverride(null))
    }

    @Test
    fun `Include carries a cue effect's timing`() {
        val master = "8b0d5f7e-2c1a-4f53-9d2e-6a1b3c4d5e6f"
        val child = CueAdHocFxDto(
            targetType = "fixture", targetKey = "hex-1", fxType = "Wave", family = "intensity",
            cycle = 2.0, blendMode = "OVERRIDE", order = "NORMAL",
            speedMasterUuid = master,
        )
        assertEquals(master, child.toFxSpec().speedMasterUuid, "the master was dropped on Include before S15")
        assertEquals(90.0, child.copy(speedMasterUuid = null, ownBpm = 90.0).toFxSpec().ownBpm)
    }
}
