package uk.me.cormack.lighting7.fx

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Test
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.ParkManager
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.ImgStageLineWash42LedFixture
import uk.me.cormack.lighting7.fixture.dmx.MartinMac250Fixture
import uk.me.cormack.lighting7.fixture.dmx.ShowSoundLedBeamRingFixture
import uk.me.cormack.lighting7.fixture.media.FittedMedia
import uk.me.cormack.lighting7.fixture.media.FittedSlot
import uk.me.cormack.lighting7.fx.group.SpreadOrder
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.routes.CueAdHocFxSpec
import uk.me.cormack.lighting7.routes.ProgrammerFxRequest
import uk.me.cormack.lighting7.routes.ProgrammerIncludeRequest
import uk.me.cormack.lighting7.routes.ProgrammerIncludeResponse
import uk.me.cormack.lighting7.routes.ProgrammerRecordRequest
import uk.me.cormack.lighting7.routes.ProgrammerRecordResponse
import uk.me.cormack.lighting7.routes.ProgrammerRouteTestSupport
import uk.me.cormack.lighting7.routes.ProgrammerFxResponse
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A colour chase reaches colour-wheel heads (wheel-colour-chase plan D1, D3): Colour Cycle and
 * Palette Steps land each step on a wheel head's nearest slot, on the beat the mixed heads change,
 * and every reader of what the chase paints on that head reads the wheel.
 *
 * The rig: a hex at 1 (RGB 2–4), a LEDBEAMRING at 101 (colour wheel 108), a wash at 151 with RGB
 * (157–159) and a colour macro (161), and a MAC 250 at 201 (colour wheel 203).
 */
class WheelColourChaseTest : RouteIntegrationTest() {

    private val universe = Universe(0, 0)

    private class Rig(val fixtures: Fixtures, val controller: MockDmxController, val engine: FxEngine, val programmer: ProgrammerStore) {
        fun at(channel: Int): Int = controller.currentValues[channel]?.toInt() ?: 0
        fun rgb(first: Int) = Triple(at(first), at(first + 1), at(first + 2))
    }

    private fun rig(parkManager: ParkManager? = null): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            val hex = addFixture(HexFixture(universe, "hex", "Hex", 1))
            val ring = addFixture(ShowSoundLedBeamRingFixture.Mode14Ch(universe, "ring", "Ring", 101))
            addFixture(ImgStageLineWash42LedFixture.Mode13Ch(universe, "wash", "Wash", 151))
            addFixture(MartinMac250Fixture.Mode4Ch(universe, "mac", "MAC", 201))
            createGroup<Fixture>("rig") { addSpread(listOf(hex, ring)) }
        }
        val programmer = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = programmer,
            layerResolver = LayerResolver(CueAssignmentResolver(), programmer),
            parkManager = parkManager,
        )
        return Rig(fixtures, controller, engine, programmer)
    }

    private fun tick(n: Long): MasterClock.ClockTick {
        val ticksPerBeat = MasterClock.TICKS_PER_BEAT.toLong()
        val tickInBeat = (n % ticksPerBeat).toInt()
        return MasterClock.ClockTick(
            tickNumber = n,
            beatNumber = n / ticksPerBeat,
            tickInBeat = tickInBeat,
            phase = tickInBeat.toDouble() / MasterClock.TICKS_PER_BEAT,
            timestampMs = 1_000_000L + n * 20L,
        )
    }

    private val registry get() = FxSamplerParityTest.registry

    /** Red then blue, a beat each, cut, the members in one phase. */
    private fun chase(target: FxTarget, priority: Int = 0, parameters: Map<String, String> = mapOf("colours" to "#ff0000,#0000ff", "between" to "CUT")) =
        FxInstance(registry.createFx("ColourCycle", parameters), target, FxTiming(cycle = 2.0)).also {
            it.order = SpreadOrder.UNIFIED
            it.priority = priority
        }

    private val ringRed = ShowSoundLedBeamRingFixture.Colour.RED.level.toInt()
    private val ringBlue = ShowSoundLedBeamRingFixture.Colour.BLUE.level.toInt()

    @Test
    fun `a colour chase lands each step on the Beam Ring's nearest slot, on the beat the par changes`() {
        val rig = rig()
        rig.engine.addFx(chase(ColourTarget.forGroup("rig")))

        rig.engine.processBeatTick(tick(12))
        assertEquals(Triple(255, 0, 0), rig.rgb(2), "the par mixes red")
        assertEquals(ringRed, rig.at(108), "the Beam Ring snaps to its red slot")

        rig.engine.processBeatTick(tick(23))
        assertEquals(ringRed, rig.at(108), "still red at the end of the first beat")
        rig.engine.processBeatTick(tick(24))
        assertEquals(Triple(0, 0, 255), rig.rgb(2), "the par changes on the beat")
        assertEquals(ringBlue, rig.at(108), "and the Beam Ring with it")
    }

    @Test
    fun `under Fade the wheel changes at the middle of the crossing`() {
        val rig = rig()
        rig.engine.addFx(chase(ColourTarget.forGroup("rig"), parameters = mapOf("colours" to "#ff0000,#0000ff", "between" to "FADE", "fade" to "0.5")))

        // The crossing is the second half of each beat: ticks 12–24 from red to blue.
        rig.engine.processBeatTick(tick(17))
        assertEquals(ringRed, rig.at(108), "short of the crossing's middle the wheel holds red")
        rig.engine.processBeatTick(tick(19))
        assertEquals(ringBlue, rig.at(108), "past it, blue, never a slot between")
    }

    @Test
    fun `Split holds the half-way mix on the par and changes the wheel at the split's middle`() {
        val rig = rig()
        rig.engine.addFx(chase(ColourTarget.forGroup("rig"), parameters = mapOf("colours" to "#ff0000,#0000ff", "between" to "SPLIT", "fade" to "0.5")))

        rig.engine.processBeatTick(tick(14))
        val early = rig.rgb(2)
        rig.engine.processBeatTick(tick(22))
        assertEquals(early, rig.rgb(2), "the par holds one mix through the split")
        assertTrue(early.first in 100..160 && early.third in 100..160 && early.second == 0, "the half-way mix of red and blue: $early")

        rig.engine.processBeatTick(tick(17))
        assertEquals(ringRed, rig.at(108), "a wheel that cannot split holds red to the split's middle")
        rig.engine.processBeatTick(tick(19))
        assertEquals(ringBlue, rig.at(108), "then blue")
    }

    @Test
    fun `a programmer value on the Beam Ring's wheel holds the chase back on it alone, under colourWheel in the key stack`() {
        val rig = rig()
        val effect = chase(ColourTarget.forGroup("rig"))
        rig.engine.addFx(effect)
        rig.programmer.put(ProgrammerOwner.WEB, "ring", "colourWheel", CueAssignmentResolver.AttributeValue.Setting(80u))

        rig.engine.processBeatTick(tick(12))
        assertEquals(80, rig.at(108), "the programmer's slot stands on the Beam Ring")
        assertEquals(Triple(255, 0, 0), rig.rgb(2), "the par still chases")

        val stack = assertNotNull(rig.engine.provenance.keyStack("ring", "colourWheel"))
        val fx = stack.layers.single { it.kind == KeyStackLayer.Kind.FX }
        assertSame(effect, fx.effect)
        assertEquals(KeyStackLayer.HeldBackReason.PROGRAMMER, fx.heldBackReason)
        assertTrue(Pair("ring", "colourWheel") in rig.engine.runningFxCoverage(), "the chase covers the wheel")
        assertFalse(Pair("ring", "rgbColour") in rig.engine.runningFxCoverage(), "and nothing the Beam Ring lacks")
    }

    @Test
    fun `Blind holds a programmer chase on the wheel head`() {
        val rig = rig()
        rig.engine.addFx(chase(ColourTarget.forGroup("rig"), priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE))
        rig.engine.processBeatTick(tick(12))
        assertEquals(ringRed, rig.at(108))

        rig.engine.programmer.setBlind(true)
        rig.engine.processBeatTick(tick(36))
        assertEquals(0, rig.at(108), "held: the wheel's base shows")
        assertEquals(Triple(0, 0, 0), rig.rgb(2))
    }

    @Test
    fun `a parked wheel channel is the row's park, and a chase leaves it be`() {
        val parkManager = ParkManager(state.database, projectId)
        runBlocking { parkManager.park(universe = 0, channel = 108, value = 200u) }
        val rig = rig(parkManager)
        val effect = chase(ColourTarget.forGroup("rig"))
        rig.engine.addFx(effect)

        assertTrue(effect.target.isAttributeFullyParked(rig.fixtures.untypedGroupableFixture("ring"), parkManager))
        val samples = rig.engine.sampler { registry.createFx(it.fxTypeId, it.effect.parameters) }.sample(FxSampleRequest(listOf(effect)))
        assertTrue(assertNotNull(samples.attribute("colourWheel")?.row("ring")).parked, "the Beam Ring's row is its wheel's, parked")
        assertFalse(assertNotNull(samples.attribute("rgbColour")?.row("hex")).parked)
    }

    @Test
    fun `Palette Steps on a MAC 250 snaps each palette to the slot its fitted media holds nearest`() {
        val rig = rig()
        val red = PaletteStep(UUID.randomUUID(), "Red", listOf("rgbColour" to parsePaletteIntent("#FF0000")!!))
        val blue = PaletteStep(UUID.randomUUID(), "Blue", listOf("rgbColour" to parsePaletteIntent("#0000FF")!!))
        val snapshots = listOf(red, blue).associate { s ->
            s.paletteUuid to PaletteSnapshot(
                paletteId = 1, paletteUuid = s.paletteUuid, name = s.name, fadeDurationMs = null,
                rows = s.rows.map { (p, i) -> PaletteRowEntry(null, p, i.serialize()) },
            )
        }
        val palettes = PaletteRegistry { snapshots[it] }
        val refs = snapshots.keys.joinToString(",") { serializePaletteColourRef(it) }
        val steps = registry.createFxWithPalettes(palettes, PALETTE_STEPS_ID, mapOf("palettes" to refs, "fadeRatio" to "0"))

        // The red frame holds a blue gel, so red lands on another slot on this unit.
        val media = FittedMedia(mapOf("colourWheel" to mapOf("RED" to FittedSlot(gel = "R80"))))
        rig.fixtures.setPatchMetadata("mac", Fixtures.FixturePatchMetadata(gelCode = null, media = media))
        rig.engine.addFx(FxInstance(steps, ColourTarget(FxTargetRef.fixture("mac")), FxTiming(cycle = 2.0)))

        val mac = rig.fixtures.untypedGroupableFixture("mac")
        val pressed = PaletteResolver.resolve(mac, "colourWheel", parsePaletteIntent("#FF0000")!!, media).value
        rig.engine.processBeatTick(tick(12))
        assertEquals((pressed as CueAssignmentResolver.AttributeValue.Setting).channelValue.toInt(), rig.at(203), "where a press of the palette lands")
        assertNotEquals(MartinMac250Fixture.Colour.RED.level.toInt(), rig.at(203), "not the stock red frame")
    }

    @Test
    fun `a head with RGB and a colour preset stays on its RGB`() {
        val rig = rig()
        val target = ColourTarget(FxTargetRef.fixture("wash"))
        val effect = chase(target)
        assertSame(effect.target, effect.target.on(rig.fixtures.untypedGroupableFixture("wash")))
        assertIs<ColourLanding.Mix>((effect.target as ColourTarget).landingOn(rig.fixtures.untypedGroupableFixture("wash")))
        rig.engine.addFx(effect)

        rig.engine.processBeatTick(tick(12))
        assertEquals(Triple(255, 0, 0), rig.rgb(157))
        assertEquals(0, rig.at(161), "the colour macro is left alone")
    }

    @Test
    fun `Rainbow Cycle leaves a wheel alone`() {
        val rig = rig()
        val rainbow = FxInstance(registry.createFx("RainbowCycle"), ColourTarget.forGroup("rig"), FxTiming(cycle = 2.0))
        assertFalse((rainbow.target as ColourTarget).wheels, "an FX that does not step reaches no wheel")
        assertFalse(rainbow.target.fixtureHasAttribute(rig.fixtures.untypedGroupableFixture("ring")))
        rig.engine.addFx(rainbow)

        rig.engine.processBeatTick(tick(12))
        assertEquals(0, rig.at(108))
        assertNotEquals(Triple(0, 0, 0), rig.rgb(2), "the par still runs it")
    }

    @Test
    fun `Colour Cycle on a MAC 250 lands on the slots its fitted media holds nearest, in the tick and the preview`() {
        val rig = rig()
        val media = FittedMedia(mapOf("colourWheel" to mapOf("RED" to FittedSlot(gel = "R80"))))
        rig.fixtures.setPatchMetadata("mac", Fixtures.FixturePatchMetadata(gelCode = null, media = media))
        val effect = chase(ColourTarget(FxTargetRef.fixture("mac")))
        rig.engine.addFx(effect)

        val setting = assertIs<ColourLanding.Wheel>((effect.target as ColourTarget).landingOn(rig.fixtures.untypedGroupableFixture("mac"))).setting
        fun slot(hex: String, fitted: FittedMedia?) =
            assertNotNull(PaletteResolver.nearestWheelOption(setting, "colourWheel", fitted, parseExtendedColour(hex).color)).first.level.toInt()
        val fitted = setOf(slot("#ff0000", media), slot("#0000ff", media))
        assertNotEquals(setOf(slot("#ff0000", null), slot("#0000ff", null)), fitted, "the fitting moves a colour's slot")

        rig.engine.processBeatTick(tick(12))
        assertEquals(slot("#ff0000", media), rig.at(203))

        val samples = rig.engine.sampler { registry.createFx(it.fxTypeId, it.effect.parameters) }.sample(FxSampleRequest(listOf(effect)))
        val result = assertIs<FxSampleSeries.Bytes>(assertNotNull(samples.attribute("colourWheel")?.row("mac")).result)
        assertEquals(fitted, result.values.mapTo(HashSet()) { it.toInt() and 0xFF }, "the preview snaps as the tick does")
    }

    @Test
    fun `a chase keeps one wheel's slots per wheel model and fitting`() {
        val rig = rig()
        val effect = chase(ColourTarget(FxTargetRef.fixture("mac")))
        rig.engine.addFx(effect)
        val steps = assertIs<ColourStepList>(assertIs<FxOutput.Steps>(effect.effect.calculate(0.0)).steps)

        for (n in 0L..48L) rig.engine.processBeatTick(tick(n))
        assertEquals(1, steps.cachedWheels, "every tick's fresh head is the same wheel")

        val media = FittedMedia(mapOf("colourWheel" to mapOf("RED" to FittedSlot(gel = "R80"))))
        rig.fixtures.setPatchMetadata("mac", Fixtures.FixturePatchMetadata(gelCode = null, media = media))
        for (n in 49L..60L) rig.engine.processBeatTick(tick(n))
        assertEquals(2, steps.cachedWheels, "a new fitting is another")
    }

    @Test
    fun `a stomping cue that asserts the Beam Ring's wheel stomps a chase on it, and a group's only where it asserts the group`() {
        val rig = rig()
        val onRing = chase(ColourTarget(FxTargetRef.fixture("ring"))).also { it.cueId = 1 }
        val onRig = chase(ColourTarget.forGroup("rig")).also { it.cueId = 1 }
        rig.engine.addFx(onRing)
        rig.engine.addFx(onRig)

        assertEquals(1, rig.engine.stompForCue(2, setOf(FxEngine.TargetAttribute("ring", "colourWheel"))))
        assertEquals(null, rig.engine.getFx(onRing.id))
        assertNotNull(rig.engine.getFx(onRig.id), "one member asserted leaves the group running")
    }

    @Test
    fun `swapping a chase onto Rainbow Cycle hands the wheel heads back`() {
        val rig = rig()
        val effect = chase(ColourTarget.forGroup("rig"))
        rig.engine.addFx(effect)
        rig.engine.processBeatTick(tick(12))
        assertEquals(ringRed, rig.at(108))

        assertNotNull(rig.engine.updateFx(effect.id, FxEngine.FxEdit(newFx = registry.createFx("RainbowCycle"))))
        assertEquals(0, rig.at(108), "the wheel the new FX does not cover is reset")
        rig.engine.processBeatTick(tick(13))
        assertEquals(0, rig.at(108))
        assertNotEquals(Triple(0, 0, 0), rig.rgb(2), "the par runs the new FX")
    }

    @Test
    fun `a colour chase pressed on a Beam Ring is Colour, so a Colour mask takes it and a second replaces it`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "show-sound-ledbeamring-14ch", "beam", 1)
        val client = jsonClient()
        val request = ProgrammerFxRequest(
            fxType = "ColourCycle", attribute = "rgbColour",
            targets = listOf(CueTargetDto(TargetRef.Fixture.TYPE, "beam")), families = listOf("COLOUR"),
        )
        suspend fun press(): ProgrammerFxResponse {
            val response = client.post("/api/rest/projects/$projectId/programmer/fx") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return response.body()
        }
        val first = press().fxId
        assertEquals(listOf(first), press().stopped, "the second colour FX takes the head off the first")
    }

    @Test
    fun `a Colour-masked Include keeps a cue's colour chase on a Beam Ring`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "show-sound-ledbeamring-14ch", "beam", 1)
        val client = jsonClient()
        val cueId = ProgrammerRouteTestSupport.createCue(
            client, projectId, "chase",
            adHoc = listOf(CueAdHocFxSpec("fixture", "beam", "ColourCycle", "rgbColour", category = "colour")),
        )
        val response = client.post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerIncludeRequest(projectId = projectId.toString(), cueId = cueId, mask = listOf("COLOUR")))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(1, response.body<ProgrammerIncludeResponse>().fxSpawned)
    }

    @Test
    fun `a Colour-masked re-record of a Beam Ring's colour chase replaces the cue's copy`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "show-sound-ledbeamring-14ch", "beam", 1)
        val client = jsonClient()
        val pressed = client.post("/api/rest/projects/$projectId/programmer/fx") {
            contentType(ContentType.Application.Json)
            setBody(
                ProgrammerFxRequest(
                    fxType = "ColourCycle", attribute = "rgbColour",
                    targets = listOf(CueTargetDto(TargetRef.Fixture.TYPE, "beam")),
                )
            )
        }
        assertEquals(HttpStatusCode.OK, pressed.status, pressed.bodyAsText())
        suspend fun record(request: ProgrammerRecordRequest): ProgrammerRecordResponse {
            val response = client.post("/api/rest/programmer/record") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return response.body()
        }
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "stack")
        val cueId = record(ProgrammerRecordRequest(projectId = projectId.toString(), cueStackId = stackId)).cue.id
        val updated = record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "UPDATE_EXISTING", cueId = cueId, mask = listOf("COLOUR"),
            )
        )
        assertEquals(1, updated.cue.adHocFx.size, "${updated.cue.adHocFx}")
    }
}
