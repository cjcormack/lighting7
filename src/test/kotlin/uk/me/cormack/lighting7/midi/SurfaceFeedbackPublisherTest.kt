package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.ParkSource
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.FxSpeed
import uk.me.cormack.lighting7.fx.CueRunState
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.BlendMode
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.Fx
import uk.me.cormack.lighting7.fx.FxContext
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxOutput
import uk.me.cormack.lighting7.fx.FxOutputType
import uk.me.cormack.lighting7.fx.FxTargetRef
import uk.me.cormack.lighting7.fx.FxTiming
import uk.me.cormack.lighting7.fx.MasterClock
import uk.me.cormack.lighting7.fx.SliderTarget
import uk.me.cormack.lighting7.fx.LayerResolver
import uk.me.cormack.lighting7.fx.LookRegistry
import uk.me.cormack.lighting7.fx.LookRowEntry
import uk.me.cormack.lighting7.fx.LookSnapshot
import uk.me.cormack.lighting7.fx.ProgrammerLayerStack
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerStore
import uk.me.cormack.lighting7.fx.SpeedMasterBank
import uk.me.cormack.lighting7.fx.SpeedMasterSnapshot
import uk.me.cormack.lighting7.fx.PaletteRegistry
import uk.me.cormack.lighting7.fx.PaletteRowEntry
import uk.me.cormack.lighting7.fx.PaletteSnapshot
import uk.me.cormack.lighting7.history.HistorySource
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.DeskSelection
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SurfaceFeedbackPublisher]. The publisher composes several moving parts
 * (device matcher, binding cache, fixtures registry, controllers), so we build a minimal
 * real fixture graph and drive the matcher via [DeviceMatcher.handle]. A fixture control reads
 * back what Local holds over the cue layer (`LayerResolver.localOver`), so a test seeds the
 * programmer store or the cue layer and drives one poll with [SurfaceFeedbackPublisher.pollForTest];
 * the poll's own loop is parked an hour out, so nothing else feeds a control behind the test's back.
 */
class SurfaceFeedbackPublisherTest {

    private val projectId = 7
    private val stackUuid = UUID.fromString("00000000-0000-4000-8000-000000000007")
    private val deviceTypeKey = "x-touch-compact-standard"

    private val xTouchHandle = MidiDeviceHandle(
        displayKey = "x-touch-compact",
        displayName = "X-Touch Compact",
        inputPort = MidiDevicePort("in-1", "X-Touch Compact", "Behringer", PortDirection.INPUT),
        outputPort = MidiDevicePort("out-1", "X-Touch Compact", "Behringer", PortDirection.OUTPUT),
    )

    private val secondXTouch = MidiDeviceHandle(
        displayKey = "x-touch-compact-2",
        displayName = "X-Touch Compact 2",
        inputPort = MidiDevicePort("in-2", "X-Touch Compact 2", "Behringer", PortDirection.INPUT),
        outputPort = MidiDevicePort("out-2", "X-Touch Compact 2", "Behringer", PortDirection.OUTPUT),
    )

    private class RecordingController(override val handle: MidiDeviceHandle) : MidiController {
        val feedback = CopyOnWriteArrayList<MidiFeedbackMessage>()
        val invalidations = CopyOnWriteArrayList<MidiControlKey>()
        val allInvalidations = AtomicInteger()
        override val input = kotlinx.coroutines.flow.MutableSharedFlow<MidiInputEvent>()
        override val inboundCcRate = uk.me.cormack.lighting7.dmx.PacketRateCounter()
        override val outboundCcRate = uk.me.cormack.lighting7.dmx.PacketRateCounter()
        override fun sendFeedback(message: MidiFeedbackMessage) { feedback += message }
        override fun invalidateFeedbackCache(key: MidiControlKey) { invalidations += key }
        override fun invalidateAllFeedback() { allInvalidations.incrementAndGet() }
        override fun close() {}
    }

    /**
     * Wires up: fixtures registry with one HexFixture + MockDmxController, a DeviceMatcher,
     * binding service with seeded bindings, active-bank state, surface holds, global scaler,
     * and a recording MidiController. Returns everything the test needs to drive scenarios.
     */
    private inner class Harness(
        bindings: List<ControlSurfaceBindingService.ResolvedBinding>,
        // Wire the publisher to a real transport instead of the recording double, for the one
        // test whose subject is the composition of the two — delta suppression lives in
        // KtMidiController, so a fake that only records messages cannot show it being bypassed.
        midiControllerOverride: MidiController? = null,
        /** What each busk pad presses, for a pad LED. */
        buskRefs: BuskRefs = BuskRefs.EMPTY,
        /** Parked channels the DMX output carries; the readback never sees them. */
        parkSource: ParkSource? = null,
        /** The device profiles, for a test that needs a control the X-Touch lacks. */
        types: List<ControlSurfaceRegistry.DeviceTypeInfo> = ControlSurfaceRegistry.allTypes,
        pollIntervalMs: Long = 3_600_000L,
        /** The jobs the project's regions take. */
        jobs: List<ControlSurfaceBindingService.StoredJob> = emptyList(),
    ) {
        val fixtures = Fixtures()
        val controller = MockDmxController(Universe(0, 0), parkSource)
        /** What a live job reads; [jobLive] announces a change of it. */
        var heads: List<String> = emptyList()
        var pads: List<UUID> = emptyList()
        val jobLive = SurfaceJobLive(follows = { true }, heads = { heads }, pads = { pads })
        val bindingService = ControlSurfaceBindingService(FakeDatabase.instance, jobLive = { jobLive.state })
        val bankState = ActiveBankState()
        val encoderBankState = EncoderBankState()
        val holds = SurfaceHolds()
        val scaler: GlobalScalerState
        val speedMasters = SpeedMasterBank()
        val stackFxAmounts = uk.me.cormack.lighting7.fx.StackFxAmounts()
        val fan = FakeFanRing()
        val fxSpeed = FakeFxSpeedRing()
        val fxStates = kotlinx.coroutines.flow.MutableStateFlow(0)
        val selection = DeskSelection { fixtures }
        val matcher: DeviceMatcher
        val recordingController = RecordingController(
            MidiDeviceHandle(
                displayKey = "x-touch-compact",
                displayName = "X-Touch Compact",
                inputPort = MidiDevicePort("in-1", "X-Touch Compact", "Behringer", PortDirection.INPUT),
                outputPort = MidiDevicePort("out-1", "X-Touch Compact", "Behringer", PortDirection.OUTPUT),
            ),
        )
        /** A second X-Touch of the same type, for the per-device tests. */
        val secondController = RecordingController(secondXTouch)
        val publisher: SurfaceFeedbackPublisher
        val store = ProgrammerStore()
        val layerResolver = LayerResolver(CueAssignmentResolver(), store)
        val engine: FxEngine
        val looks = HashMap<UUID, LookSnapshot>()
        val palettes = HashMap<UUID, PaletteSnapshot>()
        val layers: ProgrammerLayerStack
        /** The stacks a stack pad's LED reads as running. */
        val runningStacks: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        /** The cues held loose, and each cue uuid's id. */
        val looseCues: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        val cueIds = HashMap<UUID, Int>()

        init {
            fixtures.register {
                addController(controller)
                val hex1 = addFixture(HexFixture(Universe(0, 0), "hex-1", "Hex 1", firstChannel = 1))
                val hex2 = addFixture(HexFixture(Universe(0, 0), "hex-2", "Hex 2", firstChannel = 13))
                createGroup<HexFixture>("front-wash") { addSpread(listOf(hex1, hex2)) }
            }
            scaler = GlobalScalerState(fixtures)
            scaler.attach()

            bindingService.seedCacheForTest(projectId, bindings, jobs)

            val registry = MidiDeviceRegistry(FakeMidiAccess(), pollIntervalMs = 60_000L, autoOpen = false)
            matcher = DeviceMatcher(registry)

            val lookRegistry = LookRegistry(fixtures = { fixtures }, loader = { looks[it] })
            val paletteRegistry = PaletteRegistry(loader = { palettes[it] })
            engine = FxEngine(
                fixtures = fixtures,
                speedMasters = speedMasters,
                programmerStore = store,
                layerResolver = layerResolver,
            )
            layers = ProgrammerLayerStack(
                fixtures = { fixtures },
                lookRegistry = { lookRegistry },
                paletteRegistry = { paletteRegistry },
                engine = { engine },
                store = store,
                state = { null },
            )

            publisher = SurfaceFeedbackPublisher(
                deviceMatcher = matcher,
                controllerLookup = { key ->
                    when (key) {
                        "x-touch-compact" -> midiControllerOverride ?: recordingController
                        secondXTouch.displayKey -> secondController
                        else -> null
                    }
                },
                bindingService = bindingService,
                bankState = bankState,
                encoderBankState = encoderBankState,
                holds = holds,
                projectIdProvider = { projectId },
                fixturesProvider = { fixtures },
                globalScalerStateProvider = { scaler },
                programmerStoreProvider = { store },
                layerResolverProvider = { layerResolver },
                speedMasterBankProvider = { speedMasters },
                deskSelection = selection,
                stackFxAmountsProvider = { stackFxAmounts },
                stackIdResolver = { uuid -> if (uuid == stackUuid.toString()) 7 else null },
                selectionFan = fan,
                fxSpeed = fxSpeed,
                fxStateProvider = { fxStates },
                programmerLayerStackProvider = { layers },
                buskRefsProvider = { buskRefs },
                stackRunningProvider = { it in runningStacks },
                cueLooseHeldProvider = { it in looseCues },
                cueIdResolver = { cueIds[it] },
                types = { types },
                pollIntervalMs = pollIntervalMs,
                jobLive = jobLive,
            )
        }

        /** Local holds [dmx] on [key]'s [attribute], as a surface or the web writes it. */
        fun hold(key: String, attribute: String, dmx: UByte, owner: ProgrammerOwner = ProgrammerOwner.WEB) =
            store.put(owner, key, attribute, CueAssignmentResolver.AttributeValue.Slider(dmx))

        /** Local holds an RGB colour on [key]. */
        fun paint(key: String, r: Int, g: Int, b: Int) =
            store.put(
                ProgrammerOwner.WEB, key, "rgbColour",
                CueAssignmentResolver.AttributeValue.Colour(ExtendedColour(java.awt.Color(r, g, b))),
            )

        /** The cue layer holds [dmx] on each of [levels]' keys' dimmers. */
        fun cue(vararg levels: Pair<String, UByte>) = layerResolver.applyAssignments(
            levels.map { (key, dmx) ->
                CueAssignmentResolver.Assignment(
                    cueId = 1, priority = 1, fadeWeight = 1.0, targetKey = key, targetIsGroup = false,
                    attribute = "dimmer", category = AttributeCategory.DIMMER,
                    value = CueAssignmentResolver.AttributeValue.Slider(dmx),
                )
            },
        )

        suspend fun attachSecondXTouch() {
            matcher.handle(MidiDeviceRegistry.DeviceEvent.Connected(secondXTouch))
        }

        /** Simulate an X-Touch attach by driving the matcher directly. */
        suspend fun attachXTouch() {
            matcher.handle(MidiDeviceRegistry.DeviceEvent.Connected(
                MidiDeviceHandle(
                    displayKey = "x-touch-compact",
                    displayName = "X-Touch Compact",
                    inputPort = MidiDevicePort("in-1", "X-Touch Compact", "Behringer", PortDirection.INPUT),
                    outputPort = MidiDevicePort("out-1", "X-Touch Compact", "Behringer", PortDirection.OUTPUT),
                ),
            ))
        }
    }

    /** A job on the X-Touch's strips: each of [items] on its strip. */
    private fun strips(kind: JobKind = JobKind.GROUPS, items: List<JobItem?> = listOf(JobItem.Group("front-wash")), bank: String? = null) =
        ControlSurfaceBindingService.StoredJob(
            id = 50, uuid = UUID.randomUUID(), projectId = projectId, deviceTypeKey = deviceTypeKey, region = "strips", bank = bank,
            kindId = kind.id, kind = kind, rawItems = encodeJobItems(items), items = items,
        )

    private fun binding(
        id: Int,
        controlId: String,
        binding: Binding,
        bank: String? = null,
        policy: BindingTakeoverPolicy? = BindingTakeoverPolicy.IMMEDIATE,
        part: ControlPart = ControlPart.MAIN,
    ) = ControlSurfaceBindingService.ResolvedBinding(
        id = id, projectId = projectId, deviceTypeKey = deviceTypeKey,
        controlId = controlId, bank = bank, binding = binding,
        takeoverPolicy = policy, sortOrder = 0, part = part,
    )

    @Test
    fun `a Local level drives motor fader feedback on the poll`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1"))),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            h.recordingController.feedback.clear()

            // Local holds 200 on hex-1's dimmer. With min=0, max=255 on HexFixture's dimmer, 200 dmx ≈ 100 in 7-bit.
            h.hold("hex-1", "dimmer", 200u)
            h.publisher.pollForTest()

            val cc = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>()
            assertTrue(cc.isNotEmpty(), "Expected motor feedback on a programmer change, got ${h.recordingController.feedback}")
            assertEquals(1, cc.last().cc)  // fader-1's motor CC
            val expected = AttributeChannelResolver.scaleDmxTo7Bit(200u)
            assertEquals(expected, cc.last().value)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a programmer change drives feedback to fader AND encoder bound to same target`() = runBlocking {
        // Regression test for the cross-control feedback bug: fader-1 and enc-1 both bound
        // to hex-1.dimmer. When the value changes, BOTH controls must receive feedback —
        // motor CC for the fader, ring CC for the encoder.
        val h = Harness(listOf(
            binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1"))),
            binding(2, "enc-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1"))),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            h.recordingController.feedback.clear()

            h.hold("hex-1", "dimmer", 200u)
            h.publisher.pollForTest()

            val cc = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>()
            val faderFeedback = cc.filter { it.cc == 1 }   // fader-1 motor CC
            val ringFeedback = cc.filter { it.cc == 10 }   // enc-1 ring CC (same as turn CC on Layer A)
            assertTrue(faderFeedback.isNotEmpty(), "Expected motor feedback for fader-1, got ${h.recordingController.feedback}")
            assertTrue(ringFeedback.isNotEmpty(), "Expected ring feedback for enc-1, got ${h.recordingController.feedback}")
            val expected = AttributeChannelResolver.scaleDmxTo7Bit(200u)
            assertEquals(expected, faderFeedback.last().value)
            assertEquals(expected, ringFeedback.last().value)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    /**
     * The reason [SurfaceFeedbackPublisher] reads tempo bindings at all: the jump rule judges a move
     * against what the control shows, so without a readout a tempo encoder shows nothing and jumps
     * the show's tempo on first touch.
     */
    @Test
    fun `a tempo-bound encoder gets ring feedback and is held to the jump rule`() = runBlocking {
        val h = Harness(listOf(
            // enc-1: turn CC and ring CC are both 10 on this profile.
            binding(
                1, "enc-1",
                Binding(Does.Tempo(minBpm = 60.0, maxBpm = 180.0)),
                policy = BindingTakeoverPolicy.PICKUP,
            ),
        ))
        // Load the bank the way a real show does. This matters: `load()` replaces the
        // synthetic slot-0 entry, so master 1 stops reporting a null uuid — and a binding
        // that names no master (master 1) then has to be resolved by index, not by uuid.
        // Without this line the test passes even when that resolution is broken.
        h.speedMasters.load(
            listOf(
                SpeedMasterSnapshot(
                    java.util.UUID.randomUUID(), 1, "Master 1", 120.0,
                    uk.me.cormack.lighting7.models.SpeedMasterSource.MANUAL,
                ),
            )
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            h.recordingController.feedback.clear()

            // Master 1 moves to 150 BPM — three-quarters of the way through the 60..180
            // window, so the ring should sit at ~95 of 127.
            h.speedMasters.setBpm(null, 150.0, uk.me.cormack.lighting7.models.SpeedMasterSource.MANUAL)
            yield()

            val cc = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>()
            assertTrue(cc.isNotEmpty(), "expected ring feedback on tempo change, got ${h.recordingController.feedback}")
            assertEquals(10, cc.last().cc)
            assertEquals(95u.toUByte(), cc.last().value)

            // And the shown value is recorded: a ring encoder is driven, so a move far from what it
            // shows is a stale position and is dropped, and the ring is shown its value again.
            h.recordingController.feedback.clear()
            h.recordingController.invalidations.clear()
            assertFalse(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "enc-1", 5u),
                "a ring encoder far from the tempo must not be allowed to jump it",
            )
            assertTrue(MidiControlKey(0, MidiControlKey.Type.CC, 10) in h.recordingController.invalidations, "reshown past the cache")
            assertEquals(95u.toUByte(), h.recordingController.feedback.ccOn(10).single().value, "the ring is shown the tempo again")
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "enc-1", 97u), "a turn near it is taken")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    /**
     * A grand-master fader follows the level wherever it is set from — a browser, REST, another
     * surface — and arms soft takeover from it, as a tempo encoder does; a touched motor is spared
     * and catches up on release.
     */
    @Test
    fun `a grand-master fader follows the level, spares a touched motor and judges moves against it`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", Binding(Does.GrandMaster())),
            binding(2, "enc-1", Binding(Does.GrandMaster()), policy = BindingTakeoverPolicy.PICKUP),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            fun motor() = h.recordingController.feedback
                .filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>().filter { it.cc == 1 }
            assertEquals(127u.toUByte(), motor().last().value, "attach: the fader sits at full")
            h.recordingController.feedback.clear()

            h.scaler.setGrandMasterLevel(50.0)
            yield()
            assertEquals(64u.toUByte(), motor().last().value)
            val ring = h.recordingController.feedback
                .filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>().filter { it.cc == 10 }
            assertEquals(64u.toUByte(), ring.last().value)
            assertFalse(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "enc-1", 5u),
                "a ring encoder far from the level must not jump it",
            )

            h.publisher.onTouch("x-touch-compact", "fader-1", down = true)
            h.recordingController.feedback.clear()
            h.scaler.setGrandMasterLevel(25.0)
            yield()
            assertTrue(motor().isEmpty(), "a touched motor is not driven: ${h.recordingController.feedback}")
            h.publisher.onTouch("x-touch-compact", "fader-1", down = false)
            assertEquals(32u.toUByte(), motor().last().value, "released: the motor catches up")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    /**
     * A stack effect-size fader follows its stack's size and arms takeover from it, as the grand
     * master's does; its stack is the one its uuid names, and another stack's move leaves it alone.
     */
    @Test
    fun `a stack effect-size fader follows its stack's size and judges moves against it`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", Binding(Does.FxAmount, Subject.Record(RecordKind.STACK, stackUuid))),
            binding(2, "enc-1", Binding(Does.FxAmount, Subject.Record(RecordKind.STACK, stackUuid)), policy = BindingTakeoverPolicy.PICKUP),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            fun motor() = h.recordingController.feedback
                .filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>().filter { it.cc == 1 }
            assertEquals(127u.toUByte(), motor().last().value, "attach: a stack's size starts at full")

            h.stackFxAmounts.set(7, 0.5)
            yield()
            assertEquals(64u.toUByte(), motor().last().value)
            assertFalse(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "enc-1", 5u),
                "a ring encoder far from the size must not jump it",
            )

            h.recordingController.feedback.clear()
            h.stackFxAmounts.set(8, 0.1)
            yield()
            assertTrue(motor().all { it.value == 64u.toUByte() }, "another stack's size is not this fader's: ${motor()}")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    /** A fan the test sets by hand: [SelectionFan]'s ring half without a desk behind it. */
    private class FakeFanRing : FanRing {
        var pan = 0
        private val stamps = kotlinx.coroutines.flow.MutableStateFlow(SelectionFan.Shown(0))
        override val shown: kotlinx.coroutines.flow.StateFlow<SelectionFan.Shown> = stamps
        override fun amountFor(axis: FanAxis, on: Subject) = if (axis == FanAxis.PAN) pan else 0

        fun set(amount: Int) {
            pan = amount
            stamps.value = SelectionFan.Shown(stamps.value.stamp + 1)
        }
    }

    /** An FX speed's lead the test sets by hand: [SelectionFxSpeed]'s ring half without a desk behind it. */
    private class FakeFxSpeedRing : FxSpeedRing {
        var lead: Double? = null
        override val shown = kotlinx.coroutines.flow.MutableStateFlow(0L)
        override fun leadSpeed(on: Subject) = lead
    }

    /**
     * An FX speed control shows the lead FX's Speed — ×¼–×4 on a fader, the slider's track on an
     * encoder's ring, the nearer end past either — follows it as the running FX change, arms takeover
     * from it, and reads nothing while no Local FX paints the selection (fx-speed plan D13).
     */
    @Test
    fun `an FX speed control shows the lead FX's Speed and judges moves against it`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", Binding(Does.FxSpeed, Subject.Selection)),
            binding(2, "enc-1", Binding(Does.FxSpeed, Subject.Selection), policy = BindingTakeoverPolicy.PICKUP),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            fun control(id: String) = h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue(id)
            fun motor() = h.recordingController.feedback
                .filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>().filter { it.cc == 1 }
            assertNull(control("fader-1").value, "no Local FX on the selection: the control reads nothing")
            assertEquals(RingState.OFF, control("enc-1").ring)

            h.fxSpeed.lead = 2.0
            h.fxStates.value = 1
            yield()
            assertEquals(95u.toUByte(), motor().last().value, "×2 on a fader's ×¼–×4")
            assertEquals(68, control("enc-1").value, "×2 on the ring, a step a quarter of an octave")
            assertFalse(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "enc-1", 10u),
                "a ring encoder far from the lead's Speed must not jump every FX",
            )

            h.fxSpeed.lead = 8.0
            h.fxStates.value = 2
            yield()
            assertEquals(127u.toUByte(), motor().last().value, "a lead past ×4 shows at the fader's top")
            assertEquals(76, control("enc-1").value)

            // A turn the desk read as stale re-shows the ring past the transport's last-sent cache.
            h.recordingController.invalidations.clear()
            h.fxSpeed.shown.value = 1
            yield()
            assertTrue(
                MidiControlKey(0, MidiControlKey.Type.CC, 10) in h.recordingController.invalidations,
                "enc-1's ring is re-sent: ${h.recordingController.invalidations}",
            )

            h.fxSpeed.lead = null
            h.fxStates.value = 3
            yield()
            assertNull(control("fader-1").value)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    /**
     * A motor fader's own write echoes back as the lead's Speed, snapped to a power of two or rounded
     * to whole ticks, a few steps from where the fader stands: the fader keeps showing where it was
     * moved, so its motor never chases its own write. A Speed moved from elsewhere is shown, and a move
     * from a position far from it is taken as stale.
     */
    @Test
    fun `an FX speed fader's own echo keeps it where it was moved`() = runBlocking {
        val h = Harness(listOf(binding(1, "fader-1", Binding(Does.FxSpeed, Subject.Selection), policy = BindingTakeoverPolicy.PICKUP)))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            fun motor() = h.recordingController.feedback.ccOn(1)
            assertEquals(JumpPolicy.DRIVEN, h.publisher.jumpPolicyFor("x-touch-compact", "fader-1"))
            h.fxSpeed.lead = 1.0
            h.fxStates.value = 1
            yield()
            assertEquals(64u.toUByte(), motor().last().value, "×1 sits in the middle")
            assertFalse(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 20u), "far from ×1: stale")
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 67u), "near ×1: taken")

            // 67 sets ×1, which shows at 64: the echo must not drive the motor back to 64.
            h.recordingController.feedback.clear()
            h.fxSpeed.lead = FxSpeed.faderSpeed(67)
            h.fxStates.value = 2
            yield()
            assertEquals(listOf(67u.toUByte()), motor().map { it.value }.distinct(), "the motor stays where the fader was moved")
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 68u))

            h.fxSpeed.lead = 4.0
            h.fxStates.value = 3
            yield()
            assertEquals(127u.toUByte(), motor().last().value, "a Speed moved from elsewhere is shown")
            assertFalse(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 70u),
                "and a move from the old position is stale",
            )

            // A lead outside ×¼–×4 shows at the nearer end.
            h.fxSpeed.lead = 8.0
            h.fxStates.value = 4
            yield()
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 127u), "×8 shows at the top")
            h.fxSpeed.lead = 0.125
            h.fxStates.value = 5
            yield()
            assertFalse(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 60u))
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 0u), "×⅛ shows at the bottom")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    /** A fan control shows the fan's amount on its axis, 64 at no fan, re-fed on every move (venue-visit plan S12). */
    @Test
    fun `a fan control shows the amount on its axis, centred at no fan`() = runBlocking {
        val h = Harness(listOf(binding(1, "fader-1", Binding(Does.Fan(FanAxis.PAN), Subject.Selection))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            fun motor() = h.recordingController.feedback
                .filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>().filter { it.cc == 1 }
            assertEquals(64u.toUByte(), motor().last().value, "attach: no fan sits at the centre")

            h.fan.set(40)
            yield()
            assertEquals(84u.toUByte(), motor().last().value)

            h.recordingController.feedback.clear()
            h.fan.set(40)
            yield()
            assertEquals(84u.toUByte(), motor().last().value, "an equal amount written again is still re-fed")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    /**
     * Pins the deliberate v1 cut: every other LED entry reflects a steady boolean the
     * publisher can read back, and a tap has no "on" state to hold. If tap LEDs are added
     * later this test is the thing that should be updated, not silently deleted.
     */
    @Test
    fun `a tap binding never drives LED feedback`() = runBlocking {
        val h = Harness(listOf(
            binding(10, "btn-1", Binding(Does.Tap)),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()

            // A Blackout binding on the same button would have emitted an LED state here.
            assertTrue(
                h.recordingController.feedback.none {
                    it is MidiFeedbackMessage.NoteOnFeedback || it is MidiFeedbackMessage.NoteOffFeedback
                },
                "tap bindings must stay out of the LED index, got ${h.recordingController.feedback}",
            )
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `LED feedback fires on blackout toggle`() = runBlocking {
        val h = Harness(listOf(
            binding(10, "btn-1", Binding(Does.Blackout)),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            // Initial attach resync sends LED off (blackout disabled).
            val initialNotes = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.NoteOffFeedback>()
            assertTrue(initialNotes.isNotEmpty())
            h.recordingController.feedback.clear()

            h.scaler.setBlackout(true)
            yield()
            val on = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.NoteOnFeedback>()
            assertTrue(on.isNotEmpty(), "Expected NoteOn feedback on blackout enable, got ${h.recordingController.feedback}")
            assertEquals(16, on.first().note)  // btn-1 = note 16

            h.recordingController.feedback.clear()
            h.scaler.setBlackout(false)
            yield()
            val off = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.NoteOffFeedback>()
            assertTrue(off.isNotEmpty())
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `physical press and release on Blackout binding reasserts LED after release`() = runBlocking {
        val h = Harness(listOf(
            binding(10, "btn-1", Binding(Does.Blackout)),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            h.recordingController.feedback.clear()
            h.recordingController.invalidations.clear()

            val scalerActions = object : uk.me.cormack.lighting7.testsupport.RecordingSurfaceActions() {
                override fun toggleBlackout(): Boolean = h.scaler.toggleBlackout()
                override fun toggleGrandMaster(): Boolean = h.scaler.toggleGrandMaster()
            }
            val router = SurfaceInputRouter(
                deviceMatcher = h.matcher,
                controllerLookup = { key -> if (key == "x-touch-compact") h.recordingController else null },
                bindingService = h.bindingService,
                bankState = h.bankState,
                encoderBankState = h.encoderBankState,
                holds = h.holds,
                projectIdProvider = { projectId },
                actions = scalerActions,
                feedbackHooks = h.publisher,
            )

            // Press toggles blackout on; the combine-flow path sends NoteOn via sendLed.
            router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u), displayKey = "x-touch-compact")
            yield()
            assertTrue(h.scaler.blackoutEnabled.value, "Blackout should toggle to true after press")
            assertTrue(h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.NoteOnFeedback>().isNotEmpty(),
                "Expected NoteOn LED feedback after press, got ${h.recordingController.feedback}")

            // Release: the Momentary-mode workaround must invalidate delta cache and re-queue
            // NoteOn so the state lands again after the device clobbered its own LED.
            h.recordingController.feedback.clear()
            router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 16, velocity = 0u), displayKey = "x-touch-compact")
            yield()
            assertTrue(h.scaler.blackoutEnabled.value, "Blackout should remain true across release")
            assertTrue(h.recordingController.feedback.any {
                it is MidiFeedbackMessage.NoteOnFeedback && it.note == 16 && it.velocity == 127u.toUByte()
            }, "Expected re-asserted NoteOn LED feedback after release, got ${h.recordingController.feedback}")
            assertTrue(h.recordingController.invalidations.any {
                it == MidiControlKey(0, MidiControlKey.Type.NOTE, 16)
            }, "Expected delta cache invalidation for btn-1 note on release, got ${h.recordingController.invalidations}")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `flash press drives LED on, release drives LED off`() = runBlocking {
        val flash = Binding(Does.Flash("dimmer"), Subject.Fixture("hex-1"))
        val h = Harness(listOf(binding(42, "btn-1", flash)))
        val dispatch = SurfaceDispatch(uk.me.cormack.lighting7.testsupport.RecordingSurfaceActions(), h.bankState, h.encoderBankState, h.holds)
        val origin = SurfaceDispatch.Origin(
            deviceTypeKey, HistorySource.surface("x-touch-compact"),
            hold = SurfaceDispatch.HoldKey("x-touch-compact", "btn-1", ControlPart.MAIN),
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            h.recordingController.feedback.clear()

            dispatch.dispatch(flash, SurfaceDispatch.Input.Press, origin)
            yield()
            val on = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.NoteOnFeedback>()
            assertTrue(on.isNotEmpty())
            h.recordingController.feedback.clear()

            dispatch.dispatch(flash, SurfaceDispatch.Input.Release, origin)
            yield()
            val off = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.NoteOffFeedback>()
            assertTrue(off.isNotEmpty())
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `touch suppression skips motor writes while fader is held`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1"))),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            h.recordingController.feedback.clear()

            h.publisher.onTouch("x-touch-compact", "fader-1", down = true)
            assertTrue(h.publisher.touchState.isTouched("x-touch-compact", "fader-1"))

            // A value moves while the fader is held: the motor is not driven against the finger.
            h.hold("hex-1", "dimmer", 200u)
            h.publisher.pollForTest()
            assertTrue(h.recordingController.feedback.ccOn(1).isEmpty(), "touched: no motor write")

            h.publisher.onTouch("x-touch-compact", "fader-1", down = false)
            assertFalse(h.publisher.touchState.isTouched("x-touch-compact", "fader-1"))
            // Touch-off catches the motor up with what the fader reads back as now.
            val ccFeedback = h.recordingController.feedback.ccOn(1)
            assertTrue(ccFeedback.isNotEmpty(), "Expected motor catch-up feedback after touch-off")
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(200u), ccFeedback.last().value)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a motor fader on the default policy is driven, and a button has no jump policy`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")), policy = null),
            binding(2, "btn-1", Binding(Does.Blackout), policy = null),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertEquals(JumpPolicy.DRIVEN, h.publisher.jumpPolicyFor("x-touch-compact", "fader-1"))
            assertNull(h.publisher.jumpPolicyFor("x-touch-compact", "btn-1"))
            assertNull(h.publisher.jumpPolicyFor("x-touch-compact", "fader-2"), "nothing bound")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `the jump policy follows the binding's takeover policy`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")), policy = BindingTakeoverPolicy.PICKUP),
            binding(2, "fader-2", Binding(Does.Level("dimmer"), Subject.Fixture("hex-2")), policy = BindingTakeoverPolicy.IMMEDIATE),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertEquals(JumpPolicy.DRIVEN, h.publisher.jumpPolicyFor("x-touch-compact", "fader-1"))
            assertEquals(JumpPolicy.JUMP, h.publisher.jumpPolicyFor("x-touch-compact", "fader-2"))
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `bank change triggers resync and fires LED feedback for new bank`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "btn-1", Binding(Does.Blackout), bank = "a"),
            binding(2, "btn-1", Binding(Does.GrandMaster()), bank = "b"),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.bankState.setBank("x-touch-compact", "a")
            h.attachXTouch()
            yield()
            h.recordingController.feedback.clear()

            // Switch to bank b — grand-master LED should be driven (enabled=true → NoteOn).
            h.bankState.setBank("x-touch-compact", "b")
            yield()
            val on = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.NoteOnFeedback>()
            assertNotNull(on.firstOrNull())
            assertEquals(16, on.first().note)  // btn-1 = note 16
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    /**
     * `FU-MIDI-RESYNC-DELTA-SUPPRESSED`. The transport suppresses a send whose bytes match the
     * last ones it sent for that control — a cache of what *we sent*, read as though it were
     * what *the hardware holds*. A re-arming resync exists for exactly the case where those
     * two have diverged (a power cycle, a device reset, a replug the registry missed), so it
     * is the one write that must not be deduplicated: on the rig, one resync drove the faders
     * whose values had moved and left a fader at 74% sitting at the bottom.
     *
     * Composed against a real [KtMidiController] on purpose. The suppression lives in the
     * transport, so the recording double cannot show it being bypassed — which is why this
     * went unnoticed.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `a re-arming resync re-sends a control whose value has not changed`() = runBlocking {
        val target = RecordingSendTarget()
        val midi = KtMidiController(
            handle = xTouchHandle,
            sendTarget = target,
            inputSource = null,
            // Far past the test's lifetime; drains are driven explicitly by flushForTest().
            transmitIntervalMs = 3_600_000L,
            parentScope = GlobalScope,
        )
        val h = Harness(
            listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")))),
            midiControllerOverride = midi,
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.hold("hex-1", "dimmer", 200u)
            h.bankState.setBank("x-touch-compact", "layer-a")
            h.attachXTouch()
            yield()
            midi.flushForTest()

            // Nothing on the rig moves; the bank change resyncs the device with the same value.
            h.bankState.setBank("x-touch-compact", "layer-b")
            yield()
            midi.flushForTest()

            val motorWrite = MidiFeedbackMessage.ControlChangeFeedback(
                0, 1, AttributeChannelResolver.scaleDmxTo7Bit(200u),
            ).encode()
            assertEquals(
                2, target.sent.count { it.contentEquals(motorWrite) },
                "each re-arming resync must drive fader-1, unchanged value or not",
            )
        } finally {
            h.publisher.stop()
            scope.cancel()
            midi.close()
        }
    }

    /**
     * The other half of the rule: a resync that does *not* re-arm pickup leaves the cache
     * alone. Delta suppression is load-bearing on the DMX-driven path, where a bound channel
     * can move at frame rate, and there the hardware really does still hold what we sent it.
     */
    @Test
    fun `only a re-arming resync drops the whole delta cache`() = runBlocking {
        val h = Harness(listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.bankState.setBank("x-touch-compact", "layer-a")
            h.attachXTouch()
            yield()
            assertEquals(1, h.recordingController.allInvalidations.get(), "attach: the physical position is stale")

            h.bankState.setBank("x-touch-compact", "layer-b")
            yield()
            assertEquals(2, h.recordingController.allInvalidations.get(), "bank change: the control's meaning moved")

            h.selection.set(listOf(hex1))
            yield()
            assertEquals(
                2, h.recordingController.allInvalidations.get(),
                "a selection change re-feeds without re-arming, so the delta cache still holds",
            )
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `detach clears touch and takeover state for that device`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1"))),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            h.publisher.onTouch("x-touch-compact", "fader-1", down = true)
            assertTrue(h.publisher.touchState.isTouched("x-touch-compact", "fader-1"))

            h.matcher.handle(MidiDeviceRegistry.DeviceEvent.Disconnected(xTouchHandle))
            yield()
            assertFalse(h.publisher.touchState.isTouched("x-touch-compact", "fader-1"))
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a motor fader takes a move near what it shows and reshows a jump`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")), policy = null),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.hold("hex-1", "dimmer", 128u)
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            val shown = AttributeChannelResolver.scaleDmxTo7Bit(128u)
            h.recordingController.feedback.clear()
            h.recordingController.invalidations.clear()
            assertFalse(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 127u),
                "a fader the desk did not put there is stale",
            )
            assertEquals(shown, h.recordingController.feedback.ccOn(1).single().value, "and is driven back to its value")
            assertTrue(MidiControlKey(0, MidiControlKey.Type.CC, 1) in h.recordingController.invalidations, "past the transport's cache")
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", (shown.toInt() + 3).toUByte()))
            h.publisher.onTouch("x-touch-compact", "fader-1", down = true)
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 127u), "touched: the finger is the truth")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `an IMMEDIATE binding takes every move`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")), policy = BindingTakeoverPolicy.IMMEDIATE),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.hold("hex-1", "dimmer", 128u)
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 0u))
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 127u))
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    /** The X-Touch with its first fader stripped of its motor: the one undriven fader the tests need. */
    private val xTouchWithoutMotor: List<ControlSurfaceRegistry.DeviceTypeInfo> = ControlSurfaceRegistry.allTypes.map { info ->
        if (info.typeKey != deviceTypeKey) info
        else info.copy(controls = info.controls.map { c ->
            if (c is FaderDescriptor && c.controlId == "fader-1") c.copy(hasMotor = false, motorCc = null) else c
        })
    }

    @Test
    fun `an undriven fader under PICKUP is ignored until it crosses its value`() = runBlocking {
        val h = Harness(
            listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")), policy = BindingTakeoverPolicy.PICKUP)),
            types = xTouchWithoutMotor,
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertEquals(JumpPolicy.UNDRIVEN, h.publisher.jumpPolicyFor("x-touch-compact", "fader-1"))

            h.hold("hex-1", "dimmer", 255u)
            h.publisher.pollForTest()
            h.recordingController.feedback.clear()

            // The physical fader is unknown, then moves up from 10 without reaching 127.
            assertFalse(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 10u))
            assertFalse(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 80u))
            assertTrue(h.recordingController.feedback.isEmpty(), "an undriven fader is never sent a position")
            assertEquals(
                SoftTakeoverStateMachine.State.AWAITING_PICKUP,
                h.publisher.takeover.stateFor("x-touch-compact", "fader-1").state,
            )
            // Crossing the value picks it up.
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 127u))
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    // ─── Selection entries, the mixed arm and the control-state stream ─────────

    private val hex1 = CueTargetDto("fixture", "hex-1")
    private val hex2 = CueTargetDto("fixture", "hex-2")
    private val wash = CueTargetDto("group", "front-wash")

    private fun List<MidiFeedbackMessage>.ccOn(cc: Int) =
        filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>().filter { it.cc == cc }

    @Test
    fun `a uniform selection feeds the common value and a mixed one darkens the ring`() = runBlocking {
        val h = Harness(listOf(binding(1, "enc-1", Binding(Does.Level("dimmer"), Subject.Selection))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.hold("hex-1", "dimmer", 200u)
            h.hold("hex-2", "dimmer", 200u)
            h.selection.set(listOf(hex1, hex2))
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            val onAttach = h.recordingController.feedback.ccOn(10)
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(200u), onAttach.last().value, "uniform: the ring shows the value")
            assertEquals(100, h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("enc-1").value)
            assertEquals(RingState.ON, h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("enc-1").ring)

            h.recordingController.feedback.clear()
            h.hold("hex-2", "dimmer", 100u)
            h.publisher.pollForTest()
            yield()
            val mixed = h.recordingController.feedback.ccOn(10)
            assertEquals(1, mixed.size, "mixed: exactly one ring write, the off byte")
            assertEquals(0u.toUByte(), mixed.single().value)
            h.publisher.controlStates.flushForTest()
            val state = h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("enc-1")
            assertNull(state.value)
            assertEquals(RingState.OFF, state.ring)

            // A turn writes every head (the actions' job); once they agree the next tick is uniform.
            h.recordingController.feedback.clear()
            h.hold("hex-1", "dimmer", 100u)
            h.publisher.pollForTest()
            yield()
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(100u), h.recordingController.feedback.ccOn(10).last().value)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a mixed selection takes any move and a uniform one is judged against its value`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Selection), policy = BindingTakeoverPolicy.PICKUP),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.hold("hex-1", "dimmer", 200u)
            h.hold("hex-2", "dimmer", 200u)
            h.selection.set(listOf(wash))
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertFalse(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 5u),
                "uniform: a move far from the common value is stale",
            )
            h.recordingController.feedback.clear()
            h.hold("hex-2", "dimmer", 50u)
            h.publisher.pollForTest()
            assertTrue(h.recordingController.feedback.ccOn(1).isEmpty(), "mixed: the motor is driven nowhere")
            assertTrue(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 6u),
                "mixed: there is no one value to judge by, so the move writes through",
            )
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `an empty selection reads as no value and a selection change re-feeds the control`() = runBlocking {
        val h = Harness(listOf(binding(1, "enc-1", Binding(Does.Level("dimmer"), Subject.Selection))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.hold("hex-1", "dimmer", 200u)
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            val controls = h.publisher.controlStates.snapshot("x-touch-compact")!!.controls
            assertNull(controls.getValue("enc-1").value)
            assertEquals(RingState.OFF, controls.getValue("enc-1").ring)
            assertEquals(0u.toUByte(), h.recordingController.feedback.ccOn(10).last().value, "ring off on attach")

            h.recordingController.feedback.clear()
            h.selection.set(listOf(hex1))
            yield()
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(200u), h.recordingController.feedback.ccOn(10).last().value)
            assertEquals(100, h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("enc-1").value)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a divergent fixed group binding reads mixed and drives no motor`() = runBlocking {
        val h = Harness(listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Group("front-wash")))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.hold("hex-1", "dimmer", 200u)
            h.hold("hex-2", "dimmer", 100u)
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertTrue(h.recordingController.feedback.ccOn(1).isEmpty(), "no motor write on a divergent group")
            assertNull(h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("fader-1").value)

            h.hold("hex-2", "dimmer", 200u)
            h.publisher.pollForTest()
            yield()
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(200u), h.recordingController.feedback.ccOn(1).last().value)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a heads job follows the selection, re-feeding its strips without re-arming pickup`() = runBlocking {
        val h = Harness(emptyList(), jobs = listOf(strips(JobKind.HEADS, emptyList())))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.hold("hex-1", "dimmer", 200u)
            h.hold("hex-2", "dimmer", 50u)
            h.heads = listOf("hex-1", "hex-2")
            h.jobLive.refresh()
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(200u), h.recordingController.feedback.ccOn(1).last().value, "strip 1 is hex-1")
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(50u), h.recordingController.feedback.ccOn(2).last().value, "strip 2 is hex-2")
            val rearms = h.recordingController.allInvalidations.get()
            fun control(id: String) = h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue(id)

            // The selection narrows to hex-2: strip 1 is hex-2 now, strip 2 nothing.
            h.recordingController.feedback.clear()
            h.heads = listOf("hex-2")
            h.jobLive.refresh()
            yield()
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(50u), h.recordingController.feedback.ccOn(1).last().value, "strip 1 follows the selection")
            assertNull(control("fader-2").value, "strip 2 has no head, so shows nothing")
            assertEquals(rearms, h.recordingController.allInvalidations.get(), "a live job's change re-arms nothing")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a busk-page job follows the showing page, re-feeding its LEDs without re-arming pickup`() = runBlocking {
        val lookUuid = UUID.nameUUIDFromBytes("look:Warm".toByteArray())
        val lookPad = UUID.nameUUIDFromBytes("pad:Warm".toByteArray())
        val otherPad = UUID.nameUUIDFromBytes("pad:Cold".toByteArray())
        val job = ControlSurfaceBindingService.StoredJob(
            id = 51, uuid = UUID.randomUUID(), projectId = projectId, deviceTypeKey = deviceTypeKey, region = "row-2", bank = null,
            kindId = JobKind.BUSK_PAGE.id, kind = JobKind.BUSK_PAGE, rawItems = "[]", items = emptyList(),
        )
        val h = Harness(
            emptyList(),
            buskRefs = BuskRefs(
                pads = mapOf(lookPad to PadRef.Layer(lookUuid, isLook = true), otherPad to PadRef.Layer(UUID.randomUUID(), isLook = true)),
                pageIds = emptyMap(),
            ),
            jobs = listOf(job),
        )
        h.looks[lookUuid] = LookSnapshot(lookId = 1, lookUuid = lookUuid, name = "Warm", rows = emptyList(), effects = emptyList())
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.pads = listOf(lookPad)
            h.jobLive.refresh()
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            fun led(control: String) = h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue(control).led
            h.layers.add(source = LayerSource.look(1, lookUuid, "Warm"), targets = emptyList())
            yield()
            assertEquals(LedState.ON, led("btn-9"), "the page's first pad is the Look's, which is on")
            val rearms = h.recordingController.allInvalidations.get()

            // Another page shows: the Look's pad is second now.
            h.pads = listOf(otherPad, lookPad)
            h.jobLive.refresh()
            yield()
            assertEquals(LedState.OFF, led("btn-9"), "button 9 is the other page's first pad")
            assertEquals(LedState.ON, led("btn-10"), "button 10 is the Look's pad")
            assertEquals(rearms, h.recordingController.allInvalidations.get(), "a page change re-arms nothing")

            // A page with no pads: the row goes dark.
            h.pads = emptyList()
            h.jobLive.refresh()
            yield()
            assertEquals(LedState.OFF, led("btn-10"), "a slot with no pad turns off")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `an encoder-bank change moves a strip encoder onto the new attribute`() = runBlocking {
        val h = Harness(emptyList(), jobs = listOf(strips()))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()

            // enc-1 is strip 1's encoder; on the default bank it stands on the group's dimmer. Its ring RX is CC 10.
            fun ringWrites() = h.recordingController.feedback
                .filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>()
                .filter { it.cc == 10 }

            // Both members move together: a group entry reads null unless its members agree.
            h.recordingController.feedback.clear()
            h.hold("hex-1", "dimmer", 200u)
            h.hold("hex-2", "dimmer", 200u)
            h.publisher.pollForTest()
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(200u), ringWrites().last().value, "on the dimmer bank the encoder follows the dimmers")

            // Point the device's encoders at UV.
            h.encoderBankState.setAttribute("x-touch-compact", "uv")
            yield()

            h.recordingController.feedback.clear()
            h.hold("hex-1", "dimmer", 100u)
            h.hold("hex-2", "dimmer", 100u)
            h.publisher.pollForTest()
            assertTrue(ringWrites().all { it.value == 0u.toUByte() }, "the encoder shows UV, at nothing, not the dimmers: ${ringWrites()}")

            h.hold("hex-1", "uv", 90u)
            h.hold("hex-2", "uv", 90u)
            h.publisher.pollForTest()
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(90u), ringWrites().last().value, "the encoder now follows UV")

            // The strip's fader is unmoved by the bank: it is always the dimmer.
            val faderWrites = h.recordingController.feedback.ccOn(1)
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(100u), faderWrites.last().value, "fader-1 still tracked the dimmer change")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `an encoder-bank change re-shows the device, and a ring encoder judges moves against the new attribute`() = runBlocking {
        // The bank change re-arms the device, because the meaning of the control moved under the
        // operator's hand even though they did not touch it.
        val h = Harness(emptyList(), jobs = listOf(strips()))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            h.hold("hex-1", "dimmer", 200u)
            h.hold("hex-2", "dimmer", 200u)
            h.publisher.pollForTest()
            yield()

            // At the shown value, so a move is accepted.
            val at = AttributeChannelResolver.scaleDmxTo7Bit(200u)
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", at))
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "enc-1", at))

            h.recordingController.allInvalidations.set(0)
            h.encoderBankState.setAttribute("x-touch-compact", "uv")
            yield()
            assertEquals(1, h.recordingController.allInvalidations.get(), "the device is re-shown past the cache")

            // UV is at 0, so the ring's old position is stale.
            assertFalse(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "enc-1", at),
                "a ring encoder now standing for UV must not jump it from the dimmer's position",
            )
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `an encoder-bank button lights while its attribute is the active bank`() = runBlocking {
        val h = Harness(listOf(binding(1, "btn-1", Binding(Does.EncoderPage("uv")))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            fun lastLed() = h.recordingController.feedback.last {
                it is MidiFeedbackMessage.NoteOnFeedback || it is MidiFeedbackMessage.NoteOffFeedback
            }
            assertTrue(lastLed() is MidiFeedbackMessage.NoteOffFeedback, "the device is on dimmer, so UV is dark")

            h.encoderBankState.setAttribute("x-touch-compact", "uv")
            yield()
            assertTrue(lastLed() is MidiFeedbackMessage.NoteOnFeedback, "UV is the active bank: lit")
            assertEquals(LedState.ON, h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("btn-1").led)

            h.encoderBankState.setAttribute("x-touch-compact", "dimmer")
            yield()
            assertTrue(lastLed() is MidiFeedbackMessage.NoteOffFeedback, "another bank is active: dark again")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a select button's LED follows selection membership, a group by its members`() = runBlocking {
        val h = Harness(listOf(binding(1, "btn-1", Binding(Does.Select, Subject.Group("front-wash")))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            fun lastLed() = h.recordingController.feedback.last { it is MidiFeedbackMessage.NoteOnFeedback || it is MidiFeedbackMessage.NoteOffFeedback }
            assertTrue(lastLed() is MidiFeedbackMessage.NoteOffFeedback, "nothing selected: off")
            assertEquals(LedState.OFF, h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("btn-1").led)

            h.selection.set(listOf(hex1, hex2))
            yield()
            assertTrue(lastLed() is MidiFeedbackMessage.NoteOnFeedback, "both members selected: the group's LED lights")
            assertEquals(LedState.ON, h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("btn-1").led)

            h.selection.set(listOf(hex1))
            yield()
            assertTrue(lastLed() is MidiFeedbackMessage.NoteOffFeedback, "one member is not the group")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a Look's LED is lit while a layer of it is on, whatever is selected, and a palette's follows the selection`() = runBlocking {
        val lookUuid = UUID.nameUUIDFromBytes("look:Warm".toByteArray())
        val paletteUuid = UUID.nameUUIDFromBytes("palette:Red".toByteArray())
        val lookPadUuid = UUID.nameUUIDFromBytes("pad:Warm".toByteArray())
        val h = Harness(
            listOf(
                binding(1, "btn-1", Binding(Does.Look, Subject.Record(RecordKind.LOOK, lookUuid))),
                binding(2, "btn-2", Binding(Does.Pad, Subject.Record(RecordKind.PAD, lookPadUuid))),
                binding(3, "btn-3", Binding(Does.Palette(paletteUuid), Subject.Selection)),
            ),
            buskRefs = BuskRefs(pads = mapOf(lookPadUuid to PadRef.Layer(lookUuid, isLook = true)), pageIds = emptyMap()),
        )
        // The Look's rows reach hex-1 only: its LED still reads the whole rig.
        h.looks[lookUuid] = LookSnapshot(
            lookId = 1, lookUuid = lookUuid, name = "Warm",
            rows = listOf(LookRowEntry(TargetRef.Fixture("hex-1"), "dimmer", "200")),
            effects = emptyList(),
        )
        h.palettes[paletteUuid] = PaletteSnapshot(
            paletteId = 2, paletteUuid = paletteUuid, name = "Red", fadeDurationMs = null,
            rows = listOf(PaletteRowEntry(target = null, attribute = "uv", value = "dmx:200")),
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            fun led(control: String) = h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue(control).led

            assertEquals(listOf(LedState.OFF, LedState.OFF, LedState.OFF), listOf(led("btn-1"), led("btn-2"), led("btn-3")))

            h.layers.add(source = LayerSource.look(1, lookUuid, "Warm"), targets = emptyList())
            h.layers.add(source = LayerSource.palette(2, paletteUuid, "Red"), targets = listOf(hex1))
            yield()
            assertEquals(LedState.ON, led("btn-1"), "a Look's button: lit with nothing selected")
            assertEquals(LedState.ON, led("btn-2"), "and a Look pad's")
            assertEquals(LedState.OFF, led("btn-3"), "a palette's is dark with nothing selected")

            h.selection.set(listOf(hex2))
            yield()
            assertEquals(LedState.ON, led("btn-1"), "lit on a head the Look's rows never reach")
            assertEquals(LedState.ON, led("btn-2"))
            assertEquals(LedState.OFF, led("btn-3"), "the palette does not cover hex-2")

            h.selection.set(listOf(hex1))
            yield()
            assertEquals(LedState.ON, led("btn-3"), "and covers hex-1")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a stack pad's LED is lit while its stack runs, through start, GO and release`() = runBlocking {
        val songStack = 12
        val padUuid = UUID.nameUUIDFromBytes("pad:Song".toByteArray())
        val h = Harness(
            listOf(binding(1, "btn-1", Binding(Does.Pad, Subject.Record(RecordKind.PAD, padUuid)))),
            buskRefs = BuskRefs(pads = mapOf(padUuid to PadRef.Stack(songStack)), pageIds = emptyMap()),
        )
        fun runState(activeCueId: Int?, releasedAtEnd: Boolean = false) = CueRunState(
            projectId = projectId, stackId = songStack, activeCueId = activeCueId, nextCueId = null,
            activeEntryId = activeCueId, nextEntryId = null, standbyEntryId = null, nextIsArmed = false,
            transition = true, fadeDurationMs = null, fadeElapsedMs = null, autoAdvance = false,
            autoAdvanceDelayMs = null, seq = 0, releasedAtEnd = releasedAtEnd,
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            fun led() = h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("btn-1").led

            assertEquals(LedState.OFF, led(), "dark while the stack is stopped")

            h.runningStacks += songStack
            h.fixtures.cueRunStateChanged(runState(activeCueId = 101))
            yield()
            assertEquals(LedState.ON, led(), "lit once it starts")

            h.fixtures.cueRunStateChanged(runState(activeCueId = 102))
            yield()
            assertEquals(LedState.ON, led(), "and still lit after a GO")

            h.runningStacks -= songStack
            h.fixtures.cueRunStateChanged(runState(activeCueId = null, releasedAtEnd = true))
            yield()
            assertEquals(LedState.OFF, led(), "dark again once it is released")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `attach seeds a snapshot of every control and touch is recorded without a motor write`() = runBlocking {
        val h = Harness(listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val snapshots = mutableListOf<ControlStateTracker.Snapshot>()
        val deltas = mutableListOf<ControlStateTracker.Delta>()
        scope.launch { h.publisher.controlStates.snapshots.collect { snapshots += it } }
        scope.launch { h.publisher.controlStates.deltas.collect { deltas += it } }
        try {
            h.hold("hex-1", "dimmer", 200u)
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            val attach = snapshots.single()
            assertEquals("x-touch-compact", attach.displayKey)
            assertTrue(attach.controls.size >= 64, "every profile control is seeded, got ${attach.controls.size}")
            assertEquals(100, attach.controls.getValue("fader-1").value)
            assertEquals(ControlState.UNBOUND, attach.controls.getValue("fader-9"))

            h.recordingController.feedback.clear()
            h.publisher.onTouch("x-touch-compact", "fader-1", down = true)
            h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 90u)
            h.hold("hex-1", "dimmer", 100u)
            h.publisher.pollForTest()
            h.publisher.controlStates.flushForTest()
            yield()
            assertTrue(h.recordingController.feedback.ccOn(1).isEmpty(), "touched: the motor is spared")
            val row = deltas.single().controls.getValue("fader-1")
            assertTrue(row.touched)
            assertEquals(90, row.physical)
            assertEquals(50, row.value, "the value the motor would have been told is still recorded")

            h.matcher.handle(MidiDeviceRegistry.DeviceEvent.Disconnected(xTouchHandle))
            yield()
            assertTrue(snapshots.last().controls.isEmpty(), "detach: an empty snapshot")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    /**
     * A tempo-bound encoder has no fixture behind it, so a full resync's tracker reset once wiped its
     * row and never re-fed it: the stream reported a lit ring as unbound after every attach, bank
     * change and select press. Pins the re-feed, and that a selection change no longer resyncs.
     */
    @Test
    fun `a tempo-bound encoder keeps its value in the snapshot across a resync`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "enc-1", Binding(Does.Tempo(minBpm = 60.0, maxBpm = 180.0))),
            binding(2, "btn-1", Binding(Does.Select, Subject.Fixture("hex-1"))),
        ))
        h.speedMasters.load(
            listOf(
                SpeedMasterSnapshot(
                    java.util.UUID.randomUUID(), 1, "Master 1", 120.0,
                    uk.me.cormack.lighting7.models.SpeedMasterSource.MANUAL,
                ),
            )
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            // 120 BPM is half-way through 60..180: 64 of 127.
            fun encoder() = h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("enc-1")
            assertEquals(64, encoder().value, "attach: the tempo encoder is in the snapshot")
            assertEquals(RingState.ON, encoder().ring)

            // A select press re-reads the selection's controls and leaves the encoder alone.
            h.selection.set(listOf(hex1))
            yield()
            assertEquals(64, encoder().value, "after a selection change: still fed")
            assertEquals(RingState.ON, encoder().ring)

            // A layer change is a full resync of the device, which re-feeds it.
            h.bankState.setBank("x-touch-compact", "layer-b")
            yield()
            assertEquals(64, encoder().value, "after a resync: still fed")
            assertEquals(RingState.ON, encoder().ring)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a colour-bound encoder reads one hue from all three channels, so red and yellow read mixed`() = runBlocking {
        val h = Harness(listOf(binding(1, "enc-1", Binding(Does.Level("rgbColour"), Subject.Selection))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.paint("hex-1", 0, 0, 255)
            h.paint("hex-2", 0, 0, 255)
            h.selection.set(listOf(hex1, hex2))
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            val blue = AttributeChannelResolver.hueRead(0u, 0u, 255u)!!.value7Bit
            assertEquals(blue, h.recordingController.feedback.ccOn(10).last().value, "two blue heads: the ring reads blue")
            assertEquals(blue.toInt(), h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("enc-1").value)

            // The FU-MIDI-SELECTION-COLOUR-RED-ONLY case: red and yellow share a full red channel.
            h.recordingController.feedback.clear()
            h.paint("hex-1", 255, 0, 0)
            h.paint("hex-2", 255, 255, 0)
            h.publisher.pollForTest()
            yield()
            val mixed = h.recordingController.feedback.ccOn(10)
            assertEquals(1, mixed.size, "mixed: exactly one ring write, the off byte")
            assertEquals(0u.toUByte(), mixed.single().value)
            h.publisher.controlStates.flushForTest()
            val state = h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("enc-1")
            assertNull(state.value, "red and yellow are not one colour")
            assertEquals(RingState.OFF, state.ring)

            // Both red: uniform at hue 0 — a lit ring at position 0, not the off state.
            h.recordingController.feedback.clear()
            h.paint("hex-2", 255, 0, 0)
            h.publisher.pollForTest()
            yield()
            h.publisher.controlStates.flushForTest()
            val red = h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("enc-1")
            assertEquals(0, red.value)
            assertEquals(RingState.ON, red.ring)

            // A grey head has no hue: the selection has no one value.
            h.paint("hex-1", 100, 100, 100)
            h.publisher.pollForTest()
            yield()
            h.publisher.controlStates.flushForTest()
            assertNull(h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("enc-1").value, "grey has no hue")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a colour-bound motor fader is judged against the hue and takes any move on a grey head`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", Binding(Does.Level("rgbColour"), Subject.Selection), policy = BindingTakeoverPolicy.PICKUP),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.paint("hex-1", 0, 0, 255)
            h.paint("hex-2", 0, 0, 255)
            h.selection.set(listOf(hex1, hex2))
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertFalse(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 5u),
                "uniform blue: a move is judged against the hue, and red is nowhere near it",
            )
            // Under the red-only read both heads' red channel was 0 and a fader at 0 would have been taken.
            assertFalse(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 0u))
            h.paint("hex-2", 90, 90, 90)
            h.publisher.pollForTest()
            yield()
            assertTrue(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 6u),
                "a grey head: no one hue, so the move writes through",
            )
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    // ─── Colour axes ────────────────────────────────────────────────────

    @Test
    fun `an encoder-bank button lights only when the attribute and the axis both match`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "btn-1", Binding(Does.EncoderPage("rgbColour", ColourAxis.SATURATION))),
            binding(2, "btn-2", Binding(Does.EncoderPage("rgbColour"))),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            fun led(id: String) = h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue(id).led

            h.encoderBankState.set("x-touch-compact", EncoderBankSelection("rgbColour"))
            yield()
            assertEquals(LedState.OFF, led("btn-1"), "the hue bank is not the saturation bank")
            assertEquals(LedState.ON, led("btn-2"))

            h.encoderBankState.set("x-touch-compact", EncoderBankSelection("rgbColour", ColourAxis.SATURATION))
            yield()
            assertEquals(LedState.ON, led("btn-1"))
            assertEquals(LedState.OFF, led("btn-2"), "same property, other axis: dark")

            h.encoderBankState.set("x-touch-compact", EncoderBankSelection("rgbColour", ColourAxis.HUE))
            yield()
            assertEquals(LedState.ON, led("btn-2"), "an explicit hue is the bank the axis-less button names")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a saturation-bound encoder reads chroma and a brightness-bound one reads the level`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "enc-1", Binding(Does.Level("rgbColour", ColourAxis.SATURATION), Subject.Fixture("hex-1"))),
            binding(2, "enc-2", Binding(Does.Level("rgbColour", ColourAxis.BRIGHTNESS), Subject.Fixture("hex-1"))),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.paint("hex-1", 60, 60, 200)
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            fun control(id: String) = h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue(id)
            assertEquals(AttributeChannelResolver.saturationRead(60u, 60u, 200u)!!.value7Bit.toInt(), control("enc-1").value)
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(200u).toInt(), control("enc-2").value)

            // A grey has a saturation — 0 — and a level; neither ring goes dark.
            h.paint("hex-1", 100, 100, 100)
            h.publisher.pollForTest()
            yield()
            h.publisher.controlStates.flushForTest()
            assertEquals(0, control("enc-1").value, "a grey reads saturation 0")
            assertEquals(RingState.ON, control("enc-1").ring)
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(100u).toInt(), control("enc-2").value)

            // Black has a level of 0 and no saturation at all.
            h.paint("hex-1", 0, 0, 0)
            h.publisher.pollForTest()
            yield()
            h.publisher.controlStates.flushForTest()
            assertNull(control("enc-1").value, "black has no saturation")
            assertEquals(RingState.OFF, control("enc-1").ring)
            assertEquals(0, control("enc-2").value, "black is brightness 0, a value the ring shows")
            assertEquals(RingState.ON, control("enc-2").ring)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `an axis-only change of encoder bank rebuilds the index and judges the ring against the new axis`() = runBlocking {
        val h = Harness(emptyList(), jobs = listOf(strips()))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.paint("hex-1", 0, 0, 200)
            h.paint("hex-2", 0, 0, 200)
            h.encoderBankState.set("x-touch-compact", EncoderBankSelection("rgbColour", ColourAxis.SATURATION))
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            // Both heads fully saturated: the encoder reads 127, and a move there is accepted.
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "enc-1", 127u))

            h.encoderBankState.set("x-touch-compact", EncoderBankSelection("rgbColour", ColourAxis.BRIGHTNESS))
            yield()
            // Brightness is 200/255 ≈ 100: the ring's old position is stale.
            assertFalse(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "enc-1", 127u),
                "a ring encoder on the new axis must not jump it from the old axis's position",
            )
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "enc-1", AttributeChannelResolver.scaleDmxTo7Bit(200u)))
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    // ─── The readback: Local over the cue layer, off the transmit path (D6) ─────────

    private fun Harness.motor() = recordingController.feedback.ccOn(1)

    private fun Harness.value(controlId: String, displayKey: String = "x-touch-compact"): Int? {
        publisher.controlStates.flushForTest()
        return publisher.controlStates.snapshot(displayKey)!!.controls.getValue(controlId).value
    }

    private fun Harness.led(controlId: String, displayKey: String = "x-touch-compact"): LedState {
        publisher.controlStates.flushForTest()
        return publisher.controlStates.snapshot(displayKey)!!.controls.getValue(controlId).led
    }

    private val hex1Dimmer = SliderTarget(FxTargetRef.fixture("hex-1"), "dimmer")

    @Test
    fun `in Blind a fader shows the staged value, not the stage`() = runBlocking {
        val h = Harness(listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.cue("hex-1" to 60u)
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(60u), h.motor().last().value, "the cue under an empty programmer")

            h.store.blind = true
            h.hold("hex-1", "dimmer", 200u)
            h.publisher.pollForTest()
            val hex = h.fixtures.untypedFixture("hex-1")
            assertEquals(FxOutput.Slider(60u), h.layerResolver.fallbackFor(hex1Dimmer, hex, "hex-1"), "the stage still shows the cue")
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(200u), h.motor().last().value, "the fader shows what Blind stages")

            h.store.blind = false
            h.recordingController.feedback.clear()
            h.publisher.pollForTest()
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(200u), h.motor().last().value, "leaving Blind re-reads, and the value stands")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a programmer or cue-layer change re-feeds on the poll, and a poll with nothing moved sends nothing`() = runBlocking {
        val h = Harness(listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            h.publisher.pollForTest()
            h.recordingController.feedback.clear()

            h.publisher.pollForTest()
            assertTrue(h.recordingController.feedback.isEmpty(), "nothing moved: nothing sent")

            h.hold("hex-1", "dimmer", 200u)
            assertTrue(h.recordingController.feedback.isEmpty(), "a store write alone sends nothing: the poll reads it")
            h.publisher.pollForTest()
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(200u), h.motor().single().value)

            // The cue layer moves under Local: re-read, and Local still wins.
            h.recordingController.feedback.clear()
            h.cue("hex-1" to 40u)
            h.publisher.pollForTest()
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(200u), h.motor().single().value)

            // Local lets go: the cue shows through.
            h.recordingController.feedback.clear()
            h.store.clear(ProgrammerOwner.WEB, "hex-1", "dimmer")
            h.publisher.pollForTest()
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(40u), h.motor().single().value)

            // Blind alone moves the poll.
            h.recordingController.feedback.clear()
            h.store.blind = true
            h.publisher.pollForTest()
            assertEquals(1, h.motor().size)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `the poll's own loop picks up a programmer change`() = runBlocking {
        val h = Harness(
            listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")))),
            pollIntervalMs = 5,
        )
        // Unconfined, so the publisher subscribes to the matcher before the attach; the loop's delay
        // resumes it on the timer thread, as in production it runs off the transmit thread.
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            h.hold("hex-1", "dimmer", 200u)
            val want = AttributeChannelResolver.scaleDmxTo7Bit(200u)
            withTimeout(5_000) { while (h.motor().none { it.value == want }) delay(5) }
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `touch-off in Blind keeps the motor where the fader was moved`() = runBlocking {
        val h = Harness(listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")), policy = null)))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.cue("hex-1" to 50u)
            h.store.blind = true
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            val onStage = AttributeChannelResolver.scaleDmxTo7Bit(50u)
            assertEquals(onStage, h.motor().last().value)

            h.publisher.onTouch("x-touch-compact", "fader-1", down = true)
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 100u), "touched: taken")
            // What the surface's level write stages for that move.
            h.hold("hex-1", "dimmer", AttributeChannelResolver.scaleWithinRange(100u, 0u, 255u), owner = ProgrammerOwner.SURFACE)
            h.publisher.pollForTest()
            h.recordingController.feedback.clear()

            h.publisher.onTouch("x-touch-compact", "fader-1", down = false)
            assertEquals(listOf(100u.toUByte()), h.motor().map { it.value }, "the motor stays where it was let go, not back on the stage's $onStage")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    /** Paints its phase on a slider. */
    private class Ramp : Fx {
        override val name = "Ramp"
        override val outputType = FxOutputType.SLIDER
        override fun calculate(phase: Double, context: FxContext): FxOutput = FxOutput.Slider((10 + (phase * 200).toInt()).toUByte())
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

    @Test
    fun `a Local FX moves the output and leaves the motor where Local stands`() = runBlocking {
        val h = Harness(listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.hold("hex-1", "dimmer", 200u)
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            val local = AttributeChannelResolver.scaleDmxTo7Bit(200u)
            assertEquals(local, h.motor().last().value)

            val fx = FxInstance(
                effect = Ramp(),
                target = SliderTarget(FxTargetRef.fixture("hex-1"), "dimmer"),
                timing = FxTiming(cycle = 1.0),
                blendMode = BlendMode.OVERRIDE,
            ).apply { priority = FxEngine.localFxPriority(0) }
            h.engine.addFx(fx)
            assertTrue(fx.isLocalFx)
            val painted = mutableSetOf<UByte>()
            for (n in listOf(0L, 6L, 12L, 18L)) {
                h.engine.processBeatTickSuspend(tick(n))
                painted += h.controller.getValue(1)
                h.publisher.pollForTest()
            }
            assertTrue(painted.size > 1 && 200u.toUByte() !in painted, "the FX moves the output: $painted")
            assertTrue(h.motor().all { it.value == local }, "the motor never follows the FX: ${h.motor()}")
            assertEquals(local.toInt(), h.value("fader-1"))
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a held flash does not move its key's faders`() = runBlocking {
        val h = Harness(listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.hold("hex-1", "dimmer", 100u)
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            val local = AttributeChannelResolver.scaleDmxTo7Bit(100u)

            h.hold("hex-1", "dimmer", 255u, owner = ProgrammerOwner.FLASH)
            h.publisher.pollForTest()
            val hex = h.fixtures.untypedFixture("hex-1")
            assertEquals(FxOutput.Slider(255u), h.layerResolver.fallbackFor(hex1Dimmer, hex, "hex-1"), "the flash is on stage")
            assertTrue(h.motor().all { it.value == local }, "the fader reads beneath the flash: ${h.motor()}")

            h.store.clear(ProgrammerOwner.FLASH, "hex-1", "dimmer")
            h.publisher.pollForTest()
            assertEquals(local, h.motor().last().value)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a parked dimmer's fader shows Local, or the cue, never the park`() = runBlocking {
        val park = object : ParkSource {
            override fun getParkedValue(universe: Int, channel: Int): UByte? = if (universe == 0 && channel == 1) 255u else null
            override fun isParked(universe: Int, channel: Int): Boolean = getParkedValue(universe, channel) != null
        }
        val h = Harness(
            listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")))),
            parkSource = park,
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.cue("hex-1" to 60u)
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertEquals(255u.toUByte(), h.controller.getValue(1), "the channel is parked at full")
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(60u), h.motor().last().value, "the cue")

            h.hold("hex-1", "dimmer", 100u)
            h.publisher.pollForTest()
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(100u), h.motor().last().value, "Local")

            h.store.clear(ProgrammerOwner.WEB, "hex-1", "dimmer")
            h.publisher.pollForTest()
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(60u), h.motor().last().value, "the cue again")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a touched tempo fader catches up with the tempo on touch-off`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", Binding(Does.Tempo(minBpm = 60.0, maxBpm = 180.0)), policy = BindingTakeoverPolicy.PICKUP),
        ))
        h.speedMasters.load(
            listOf(
                SpeedMasterSnapshot(
                    UUID.randomUUID(), 1, "Master 1", 120.0,
                    uk.me.cormack.lighting7.models.SpeedMasterSource.MANUAL,
                ),
            ),
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertEquals(64u.toUByte(), h.motor().last().value)

            h.publisher.onTouch("x-touch-compact", "fader-1", down = true)
            h.recordingController.feedback.clear()
            h.speedMasters.setBpm(null, 150.0, uk.me.cormack.lighting7.models.SpeedMasterSource.MANUAL)
            yield()
            assertTrue(h.motor().isEmpty(), "touched: the motor is spared")
            assertEquals(95, h.value("fader-1"), "but the value is recorded")

            h.publisher.onTouch("x-touch-compact", "fader-1", down = false)
            assertEquals(95u.toUByte(), h.motor().last().value, "released: the motor catches up with the tempo")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    // ─── Live state per device (D16) ─────────

    @Test
    fun `two devices of one type each keep their own layer and encoder page, and each lights its own flash`() = runBlocking {
        val flash = Binding(Does.Flash("dimmer"), Subject.Fixture("hex-1"))
        val h = Harness(listOf(
            binding(1, "btn-10", Binding(Does.Blackout)),
            binding(2, "btn-10", Binding(Does.GrandMaster()), bank = "layer-b"),
            binding(4, "btn-11", flash),
        ), jobs = listOf(strips()))
        val dispatch = SurfaceDispatch(uk.me.cormack.lighting7.testsupport.RecordingSurfaceActions(), h.bankState, h.encoderBankState, h.holds)
        fun origin(displayKey: String) = SurfaceDispatch.Origin(
            deviceTypeKey, HistorySource.surface(displayKey), hold = SurfaceDispatch.HoldKey(displayKey, "btn-11", ControlPart.MAIN),
        )
        val left = "x-touch-compact"
        val right = secondXTouch.displayKey
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.hold("hex-1", "dimmer", 200u)
            h.hold("hex-2", "dimmer", 200u)
            h.hold("hex-1", "uv", 90u)
            h.hold("hex-2", "uv", 90u)
            h.publisher.start(scope)
            h.attachXTouch()
            h.attachSecondXTouch()
            yield()
            assertEquals(LedState.OFF, h.led("btn-10", left), "both on the default layer: blackout, off")
            assertEquals(LedState.OFF, h.led("btn-10", right))

            h.bankState.setBank(left, "layer-b")
            yield()
            assertEquals(LedState.ON, h.led("btn-10", left), "the left device is on layer B: the grand master, engaged")
            assertEquals(LedState.OFF, h.led("btn-10", right), "the right device stays on its own layer")

            h.encoderBankState.setAttribute(right, "uv")
            yield()
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(200u).toInt(), h.value("enc-1", left), "the left encoders still drive the dimmer")
            assertEquals(AttributeChannelResolver.scaleDmxTo7Bit(90u).toInt(), h.value("enc-1", right), "the right ones UV")
            assertEquals(
                AttributeChannelResolver.scaleDmxTo7Bit(90u),
                h.secondController.feedback.ccOn(10).last().value,
                "and the right device's own ring is sent it",
            )

            dispatch.dispatch(flash, SurfaceDispatch.Input.Press, origin(left))
            yield()
            assertEquals(LedState.ON, h.led("btn-11", left))
            assertEquals(LedState.OFF, h.led("btn-11", right), "the other device's flash is not held")

            dispatch.dispatch(flash, SurfaceDispatch.Input.Press, origin(right))
            yield()
            assertEquals(LedState.ON, h.led("btn-11", left))
            assertEquals(LedState.ON, h.led("btn-11", right), "both held: both lit")
            assertTrue(
                h.secondController.feedback.any { it is MidiFeedbackMessage.NoteOnFeedback && it.note == 26 },
                "the right device's own LED is sent: ${h.secondController.feedback}",
            )

            dispatch.dispatch(flash, SurfaceDispatch.Input.Release, origin(left))
            yield()
            assertEquals(LedState.OFF, h.led("btn-11", left))
            assertEquals(LedState.ON, h.led("btn-11", right), "the right flash is still down")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    // ─── LEDs ─────────

    @Test
    fun `a GO button is lit while its stack runs`() = runBlocking {
        val h = Harness(listOf(binding(1, "btn-1", Binding(Does.Go, Subject.Record(RecordKind.STACK, stackUuid)))))
        fun runState(activeCueId: Int?) = CueRunState(
            projectId = projectId, stackId = 7, activeCueId = activeCueId, nextCueId = null,
            activeEntryId = activeCueId, nextEntryId = null, standbyEntryId = null, nextIsArmed = false,
            transition = true, fadeDurationMs = null, fadeElapsedMs = null, autoAdvance = false,
            autoAdvanceDelayMs = null, seq = 0,
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertEquals(LedState.OFF, h.led("btn-1"), "stopped: dark")

            h.runningStacks += 7
            h.fixtures.cueRunStateChanged(runState(activeCueId = 101))
            yield()
            assertEquals(LedState.ON, h.led("btn-1"), "running: lit")

            h.runningStacks -= 7
            h.fixtures.cueRunStateChanged(runState(activeCueId = null))
            yield()
            assertEquals(LedState.OFF, h.led("btn-1"), "stopped again: dark")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a cue button is lit while the cue is held loose`() = runBlocking {
        val cue = UUID.fromString("00000000-0000-4000-8000-000000000042")
        val h = Harness(listOf(
            binding(1, "btn-1", Binding(Does.Cue, Subject.Record(RecordKind.CUE, cue))),
            binding(2, "btn-2", Binding(Does.Cue, Subject.Record(RecordKind.CUE, UUID.randomUUID()))),
        ))
        h.cueIds[cue] = 42
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertEquals(LedState.OFF, h.led("btn-1"))

            h.looseCues += 42
            h.fixtures.cueHoldsChanged()
            yield()
            assertEquals(LedState.ON, h.led("btn-1"), "held loose: lit")
            assertEquals(LedState.OFF, h.led("btn-2"), "a cue the project lacks stays dark")

            h.looseCues -= 42
            h.fixtures.cueHoldsChanged()
            yield()
            assertEquals(LedState.OFF, h.led("btn-1"), "let go: dark")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `an encoder push's LED is lit by its own binding and re-asserted on release`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "enc-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1"))),
            binding(2, "enc-1", Binding(Does.Blackout), part = ControlPart.PUSH),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            val pushLed = MidiControlKey(0, MidiControlKey.Type.NOTE, 0)
            assertTrue(
                h.recordingController.feedback.any { it is MidiFeedbackMessage.NoteOffFeedback && it.note == 0 },
                "attach: enc-1's push LED (note 0) is sent dark",
            )

            val scalerActions = object : uk.me.cormack.lighting7.testsupport.RecordingSurfaceActions() {
                override fun toggleBlackout(): Boolean = h.scaler.toggleBlackout()
            }
            val router = SurfaceInputRouter(
                deviceMatcher = h.matcher,
                controllerLookup = { key -> if (key == "x-touch-compact") h.recordingController else null },
                bindingService = h.bindingService,
                bankState = h.bankState,
                encoderBankState = h.encoderBankState,
                holds = h.holds,
                projectIdProvider = { projectId },
                actions = scalerActions,
                feedbackHooks = h.publisher,
            )
            router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 0, velocity = 127u), displayKey = "x-touch-compact")
            yield()
            assertTrue(h.scaler.blackoutEnabled.value)
            assertEquals(LedState.ON, h.led("enc-1"))

            h.recordingController.feedback.clear()
            h.recordingController.invalidations.clear()
            router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 0, velocity = 0u), displayKey = "x-touch-compact")
            yield()
            assertTrue(pushLed in h.recordingController.invalidations, "the push LED is sent past the cache: ${h.recordingController.invalidations}")
            assertTrue(
                h.recordingController.feedback.any { it is MidiFeedbackMessage.NoteOnFeedback && it.note == 0 && it.velocity == 127u.toUByte() },
                "and re-asserted lit: ${h.recordingController.feedback}",
            )
            assertTrue(h.recordingController.feedback.ccOn(10).isEmpty(), "the turn's ring is not touched by the push")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }
}
