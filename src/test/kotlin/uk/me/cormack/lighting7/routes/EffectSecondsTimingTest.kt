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
import uk.me.cormack.lighting7.fx.BeatDivision
import uk.me.cormack.lighting7.fx.EffectDto
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.models.CueAdHocEffectDto
import uk.me.cormack.lighting7.models.LookEffectSpec
import uk.me.cormack.lighting7.models.withBeatDivisionOverride
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
 * Venue-visit plan S15: an effect's timing is a speed master or *Seconds* (D7), carried through the
 * add and update routes (the `updateFx` frame shares the update's parse), and a new effect is one bar
 * (D16). The wall-clock half is `FU-FX-RATE-MASTER-CLEAR`'s close: `seconds: true` is the explicit
 * *unscaled* a null rate master could never say.
 */
class EffectSecondsTimingTest : RouteIntegrationTest() {

    private val engine get() = state.show.fxEngine

    private fun masterUuid(index: Int) = engine.speedMasters.masterStates()[index].uuid!!.toString()

    private suspend fun io.ktor.client.HttpClient.add(request: AddEffectRequest) =
        post("/api/rest/fx/add") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun io.ktor.client.HttpClient.update(id: Long, request: UpdateEffectRequest) =
        put("/api/rest/fx/$id") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private fun effect(id: Long): FxInstance = assertNotNull(engine.getEffect(id))

    @Test
    fun `a beat effect on Seconds runs on the wall-clock loop with no master`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val id = client.add(
            AddEffectRequest(effectType = "Pulse", fixtureKey = "hex-1", propertyName = "dimmer", beatDivision = 10.0, seconds = true),
        ).body<AddEffectResponse>().effectId

        val instance = effect(id)
        assertTrue(instance.seconds)
        assertTrue(instance.onSeconds)
        assertEquals(TimingSource.WALL_CLOCK, instance.timingSource, "the wall-clock loop reads the division as seconds")
        assertEquals(TimingSource.BEAT, instance.registrationTimingSource)
        assertNull(instance.speedMasterUuid)
        assertNull(instance.rateSpeedMasterUuid)
        assertEquals(FxInstance.NO_RATE_MASTER, instance.rateMasterSlot, "Seconds is unscaled — no master's tempo reaches it")
        assertEquals(10.0, instance.timing.beatDivision)

        val dto = engine.effectDto(instance)
        assertTrue(dto.seconds)
        assertEquals("WALL_CLOCK", dto.timingSource)
    }

    @Test
    fun `Seconds beside a master is refused on add and on update`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val master = masterUuid(0)

        val refused = client.add(
            AddEffectRequest(effectType = "Pulse", fixtureKey = "hex-1", propertyName = "dimmer", seconds = true, speedMasterUuid = master),
        )
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue(engine.getActiveEffects().isEmpty())

        val id = client.add(AddEffectRequest(effectType = "Pulse", fixtureKey = "hex-1", propertyName = "dimmer"))
            .body<AddEffectResponse>().effectId
        val before = effect(id)
        val update = client.update(id, UpdateEffectRequest(seconds = true, speedMasterUuid = master))
        assertEquals(HttpStatusCode.BadRequest, update.status)
        assertEquals(CODE_FX_UPDATE_REFUSED, update.body<ErrorResponse>().code)
        assertSame(before, effect(id), "a refused update moves nothing")
    }

    @Test
    fun `an omitted division is one bar`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val id = jsonClient().add(AddEffectRequest(effectType = "Pulse", fixtureKey = "hex-1", propertyName = "dimmer"))
            .body<AddEffectResponse>().effectId
        assertEquals(BeatDivision.ONE_BAR, effect(id).timing.beatDivision)
    }

    @Test
    fun `an update moves a beat effect onto Seconds and back, keeping its id`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val second = masterUuid(1).takeIf { engine.speedMasters.masterStates().size > 1 }
        val id = client.add(AddEffectRequest(effectType = "Pulse", fixtureKey = "hex-1", propertyName = "dimmer"))
            .body<AddEffectResponse>().effectId

        val onSeconds = client.update(id, UpdateEffectRequest(seconds = true, beatDivision = 6.0)).body<EffectDto>()
        assertEquals(id, onSeconds.id)
        assertTrue(onSeconds.seconds)
        assertTrue(effect(id).seconds)
        assertEquals(TimingSource.WALL_CLOCK, effect(id).timingSource)

        // Leaving Seconds onto a master with none named is master 1 — null, as everywhere.
        val back = client.update(id, UpdateEffectRequest(seconds = false, beatDivision = 4.0)).body<EffectDto>()
        assertFalse(back.seconds)
        assertEquals(TimingSource.BEAT, effect(id).timingSource)
        assertFalse(effect(id).seconds)
        assertNull(effect(id).speedMasterUuid)

        // Naming a master alone takes an effect off Seconds too.
        client.update(id, UpdateEffectRequest(seconds = true))
        val master = second ?: masterUuid(0)
        client.update(id, UpdateEffectRequest(speedMasterUuid = master))
        assertFalse(effect(id).onSeconds)
        assertEquals(master, effect(id).speedMasterUuid.toString())
        assertEquals(TimingSource.BEAT, effect(id).timingSource)
    }

    @Test
    fun `a wall-clock effect's Seconds is its unscaled rate, and the wire can now say so`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val master = masterUuid(0)
        assertEquals(TimingSource.WALL_CLOCK, state.show.fxRegistry.getRegistration("FluorescentFlicker")!!.timingSource)

        val id = client.add(
            AddEffectRequest(effectType = "FluorescentFlicker", fixtureKey = "hex-1", propertyName = "dimmer", rateSpeedMasterUuid = master),
        ).body<AddEffectResponse>().effectId
        assertEquals(TimingSource.WALL_CLOCK, effect(id).timingSource)
        assertFalse(effect(id).onSeconds)

        // FU-FX-RATE-MASTER-CLEAR: an explicit Seconds clears the rate master, which a null never could.
        val unscaled = client.update(id, UpdateEffectRequest(seconds = true)).body<EffectDto>()
        assertTrue(unscaled.seconds)
        assertNull(effect(id).rateSpeedMasterUuid)
        assertEquals(FxInstance.NO_RATE_MASTER, effect(id).rateMasterSlot)
        assertFalse(effect(id).seconds, "the flag is a beat effect's; a wall-clock effect's Seconds is no rate master")

        // Leaving Seconds has to name the master, since a null rate master *is* Seconds.
        val refused = client.update(id, UpdateEffectRequest(seconds = false))
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        val scaled = client.update(id, UpdateEffectRequest(seconds = false, rateSpeedMasterUuid = master)).body<EffectDto>()
        assertFalse(scaled.seconds)
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
            setBody(AddGroupFxRequest(effectType = "FluorescentFlicker", propertyName = "dimmer"))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.toString())
        val id = response.body<AddGroupFxResponse>().effectId
        assertEquals(TimingSource.WALL_CLOCK, effect(id).timingSource, "the group route takes the type's loop since S15")
        assertEquals(BeatDivision.ONE_BAR, effect(id).timing.beatDivision)
    }

    @Test
    fun `a stored effect on Seconds spawns with no master, whatever its layer overrides`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val master = java.util.UUID.fromString(masterUuid(0))
        val spec = LookEffectSpec(
            effectType = "Pulse", category = "dimmer", propertyName = "dimmer", beatDivision = 5.0,
            blendMode = "OVERRIDE", distribution = "LINEAR", seconds = true,
        )
        val target = uk.me.cormack.lighting7.fx.FxTargetFactory.forFixture(
            "hex-1", "dimmer", null, state.show.fixtures.untypedGroupableFixture("hex-1"),
        )
        val instance = uk.me.cormack.lighting7.fx.EffectSpawner.createEffectInstance(
            spec, target, state, overrideSpeedMasterUuid = master, overrideRateSpeedMasterUuid = master,
        )
        assertTrue(instance.seconds)
        assertEquals(TimingSource.WALL_CLOCK, instance.timingSource)
        assertNull(instance.speedMasterUuid, "a layer's master override does not reach an effect on Seconds")
        assertNull(instance.rateSpeedMasterUuid)

        val onMaster = uk.me.cormack.lighting7.fx.EffectSpawner.createEffectInstance(
            spec.copy(seconds = false), target, state, overrideSpeedMasterUuid = master,
        )
        assertFalse(onMaster.seconds)
        assertEquals(TimingSource.BEAT, onMaster.timingSource)
        assertEquals(master, onMaster.speedMasterUuid)
    }

    @Test
    fun `a layer's division override leaves an effect on Seconds alone`() {
        val spec = LookEffectSpec(
            effectType = "Pulse", category = "dimmer", beatDivision = 12.0, blendMode = "OVERRIDE", distribution = "LINEAR",
        )
        assertEquals(2.0, spec.withBeatDivisionOverride(2.0).beatDivision)
        assertEquals(12.0, spec.copy(seconds = true).withBeatDivisionOverride(2.0).beatDivision)
        assertSame(spec, spec.withBeatDivisionOverride(null))
    }

    @Test
    fun `Include carries a cue effect's timing`() {
        val master = "8b0d5f7e-2c1a-4f53-9d2e-6a1b3c4d5e6f"
        val child = CueAdHocEffectDto(
            targetType = "fixture", targetKey = "hex-1", effectType = "Pulse", category = "dimmer",
            beatDivision = 2.0, blendMode = "OVERRIDE", distribution = "LINEAR",
            speedMasterUuid = master,
        )
        assertEquals(master, child.toEffectSpec().speedMasterUuid, "the master was dropped on Include before S15")
        val onSeconds = child.copy(speedMasterUuid = null, seconds = true).toEffectSpec()
        assertTrue(onSeconds.seconds)
    }
}
