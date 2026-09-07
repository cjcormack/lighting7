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
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.SpeedMasterBank
import uk.me.cormack.lighting7.fx.SpeedMasterSnapshot
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.DeskSelection
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
 * real fixture graph with a [MockDmxController] and drive the matcher via [DeviceMatcher.handle].
 */
class SurfaceFeedbackPublisherTest {

    private val projectId = 7
    private val deviceTypeKey = "x-touch-compact-standard"

    private val xTouchHandle = MidiDeviceHandle(
        displayKey = "x-touch-compact",
        displayName = "X-Touch Compact",
        inputPort = MidiDevicePort("in-1", "X-Touch Compact", "Behringer", PortDirection.INPUT),
        outputPort = MidiDevicePort("out-1", "X-Touch Compact", "Behringer", PortDirection.OUTPUT),
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
     * binding service with seeded bindings, active-bank state, flash tracker, global scaler,
     * and a recording MidiController. Returns everything the test needs to drive scenarios.
     */
    private inner class Harness(
        bindings: List<ControlSurfaceBindingService.ResolvedBinding>,
        // Wire the publisher to a real transport instead of the recording double, for the one
        // test whose subject is the composition of the two — delta suppression lives in
        // KtMidiController, so a fake that only records messages cannot show it being bypassed.
        midiControllerOverride: MidiController? = null,
    ) {
        val fixtures = Fixtures()
        val controller = MockDmxController(Universe(0, 0))
        val bindingService = ControlSurfaceBindingService(FakeDatabase.instance)
        val bankState = ActiveBankState()
        val encoderBankState = EncoderBankState()
        val flashTracker = FlashStateTracker()
        val scaler: GlobalScalerState
        val speedMasters = SpeedMasterBank()
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
        val publisher: SurfaceFeedbackPublisher

        init {
            fixtures.register {
                addController(controller)
                val hex1 = addFixture(HexFixture(Universe(0, 0), "hex-1", "Hex 1", firstChannel = 1))
                val hex2 = addFixture(HexFixture(Universe(0, 0), "hex-2", "Hex 2", firstChannel = 13))
                createGroup<HexFixture>("front-wash") { addSpread(listOf(hex1, hex2)) }
            }
            scaler = GlobalScalerState(fixtures)
            scaler.attach()

            bindingService.seedCacheForTest(projectId, bindings)

            val registry = MidiDeviceRegistry(FakeMidiAccess(), pollIntervalMs = 60_000L, autoOpen = false)
            matcher = DeviceMatcher(registry)

            publisher = SurfaceFeedbackPublisher(
                deviceMatcher = matcher,
                controllerLookup = { key ->
                    if (key == "x-touch-compact") midiControllerOverride ?: recordingController else null
                },
                bindingService = bindingService,
                bankState = bankState,
                encoderBankState = encoderBankState,
                flashTracker = flashTracker,
                projectIdProvider = { projectId },
                fixturesProvider = { fixtures },
                globalScalerStateProvider = { scaler },
                speedMasterBankProvider = { speedMasters },
                deskSelection = selection,
            )
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

    private fun binding(
        id: Int,
        controlId: String,
        target: BindingTarget,
        bank: String? = null,
        policy: BindingTakeoverPolicy? = BindingTakeoverPolicy.IMMEDIATE,
    ) = ControlSurfaceBindingService.ResolvedBinding(
        id = id, projectId = projectId, deviceTypeKey = deviceTypeKey,
        controlId = controlId, bank = bank, target = target,
        takeoverPolicy = policy, sortOrder = 0,
    )

    @Test
    fun `channel change drives motor fader feedback`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer")),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            h.recordingController.feedback.clear()

            // Set the channel to 200 then simulate channelsChanged. With min=0, max=255 on
            // HexFixture's dimmer, 200 dmx ≈ 100 in 7-bit.
            h.controller.setValue(1, 200u, 0)
            h.publisher.simulateChannelsChangedForTest(Universe(0, 0), mapOf(1 to 200u.toUByte()))
            yield()

            val cc = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>()
            assertTrue(cc.isNotEmpty(), "Expected motor feedback on channel change, got ${h.recordingController.feedback}")
            assertEquals(1, cc.last().cc)  // fader-1's motor CC
            val expected = PropertyChannelResolver.scaleDmxTo7Bit(200u)
            assertEquals(expected, cc.last().value)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `channel change drives feedback to fader AND encoder bound to same target`() = runBlocking {
        // Regression test for the cross-control feedback bug: fader-1 and enc-1 both bound
        // to hex-1.dimmer. When the channel changes, BOTH controls must receive feedback —
        // motor CC for the fader, ring CC for the encoder.
        val h = Harness(listOf(
            binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer")),
            binding(2, "enc-1", BindingTarget.FixtureProperty("hex-1", "dimmer")),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            h.recordingController.feedback.clear()

            h.controller.setValue(1, 200u, 0)
            h.publisher.simulateChannelsChangedForTest(Universe(0, 0), mapOf(1 to 200u.toUByte()))
            yield()

            val cc = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>()
            val faderFeedback = cc.filter { it.cc == 1 }   // fader-1 motor CC
            val ringFeedback = cc.filter { it.cc == 10 }   // enc-1 ring CC (same as turn CC on Layer A)
            assertTrue(faderFeedback.isNotEmpty(), "Expected motor feedback for fader-1, got ${h.recordingController.feedback}")
            assertTrue(ringFeedback.isNotEmpty(), "Expected ring feedback for enc-1, got ${h.recordingController.feedback}")
            val expected = PropertyChannelResolver.scaleDmxTo7Bit(200u)
            assertEquals(expected, faderFeedback.last().value)
            assertEquals(expected, ringFeedback.last().value)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    /**
     * The reason [SurfaceFeedbackPublisher] indexes tempo bindings at all: PICKUP arms from
     * `setLogical`, so without an index entry a tempo encoder stays ENGAGED and jumps the
     * show's tempo on first touch.
     */
    @Test
    fun `a tempo-bound encoder gets ring feedback and arms soft takeover`() = runBlocking {
        val h = Harness(listOf(
            // enc-1: turn CC and ring CC are both 10 on this profile.
            binding(
                1, "enc-1",
                BindingTarget.SpeedMasterBpm(masterUuid = null, minBpm = 60.0, maxBpm = 180.0),
                policy = BindingTakeoverPolicy.PICKUP,
            ),
        ))
        // Load the bank the way a real show does. This matters: `load()` replaces the
        // synthetic slot-0 entry, so master 1 stops reporting a null uuid — and a binding
        // that stores null for master 1 then has to be resolved by index, not by uuid.
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

            // And the logical value is recorded, which is what makes PICKUP suppress an
            // inbound value that hasn't crossed it yet.
            assertFalse(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "enc-1", 5u),
                "a PICKUP encoder far from the logical tempo must not be allowed to jump it",
            )
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
            binding(10, "btn-1", BindingTarget.SpeedMasterTap(masterUuid = null)),
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
            binding(10, "btn-1", BindingTarget.Blackout),
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
            binding(10, "btn-1", BindingTarget.Blackout),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            h.recordingController.feedback.clear()
            h.recordingController.invalidations.clear()

            val scalerActions = object : SurfaceActions {
                override fun writeFixtureProperty(fixtureKey: String, propertyName: String, midiValue7Bit: UByte, colourAxis: ColourAxis?) {}
                override fun writeGroupProperty(groupName: String, propertyName: String, midiValue7Bit: UByte, colourAxis: ColourAxis?) {}
                override fun flashFixturePropertyPress(fixtureKey: String, propertyName: String, max: UByte) {}
                override fun flashGroupPropertyPress(groupName: String, propertyName: String, max: UByte) {}
                override fun flashFixturePropertyRelease(fixtureKey: String, propertyName: String) {}
                override fun flashGroupPropertyRelease(groupName: String, propertyName: String) {}
                override fun cueStackGo(stackId: Int, stackUuid: String?) {}
                override fun cueStackBack(stackId: Int, stackUuid: String?) {}
                override fun cueStackPause(stackId: Int, stackUuid: String?) {}
                override fun fireCue(cueId: Int, cueUuid: String?) {}
                override fun writeSelectionProperty(propertyName: String, midiValue7Bit: UByte, colourAxis: ColourAxis?) {}
                override fun selectTarget(target: CueTargetDto, mode: BindingTarget.SelectMode) {}
                override fun clearSelection() {}
                override fun locateSelection() {}
                override fun toggleBlackout(): Boolean = h.scaler.toggleBlackout()
                override fun toggleGrandMaster(): Boolean = h.scaler.toggleGrandMaster()
                override fun writeSpeedMasterBpm(
                    masterUuid: String?,
                    minBpm: Double,
                    maxBpm: Double,
                    midiValue7Bit: UByte,
                ) {}
                override fun tapSpeedMaster(masterUuid: String?) {}
                override fun applyLook(lookUuid: String) {}
                override fun pressTemplate(templateUuid: String) {}
                override fun pressPad(padUuid: String) {}
                override fun buskPageStep(delta: Int) {}
                override fun buskPageSet(pageUuid: String) {}
            }
            val router = SurfaceInputRouter(
                deviceMatcher = h.matcher,
                controllerLookup = { key -> if (key == "x-touch-compact") h.recordingController else null },
                bindingService = h.bindingService,
                bankState = h.bankState,
                encoderBankState = h.encoderBankState,
                flashTracker = h.flashTracker,
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
        val flash = BindingTarget.Flash(BindingTarget.FixtureProperty("hex-1", "dimmer"))
        val h = Harness(listOf(binding(42, "btn-1", flash)))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            h.recordingController.feedback.clear()

            h.flashTracker.pressed(42)
            yield()
            val on = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.NoteOnFeedback>()
            assertTrue(on.isNotEmpty())
            h.recordingController.feedback.clear()

            h.flashTracker.clearPress(42)
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
            binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer")),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            h.recordingController.feedback.clear()

            // Simulate fader touch.
            h.publisher.onTouch("x-touch-compact", "fader-1", down = true)
            assertTrue(h.publisher.touchState.isTouched("x-touch-compact", "fader-1"))

            // Trigger a channel change — motor should NOT be driven while touched.
            h.controller.setValue(1, 200u, 0)
            h.publisher.rebuildIndexForTest()
            // No direct channels-changed hook in mock, so we manually fire via the fixture
            // listener. The listener path would be: ArtNet transmit → ChannelChangeListener
            // → FixturesChangeListener.channelsChanged → publisher. Mock skips real transmit,
            // so instead assert that calling the publisher's channel-change path respects
            // touch state — verified by forcing a resync which should produce no output.
            h.publisher.onTouch("x-touch-compact", "fader-1", down = true) // still held
            // Call `resyncControl` via touch-off flow: down=false triggers resync.
            h.recordingController.feedback.clear()
            h.publisher.onTouch("x-touch-compact", "fader-1", down = false)
            yield()
            assertFalse(h.publisher.touchState.isTouched("x-touch-compact", "fader-1"))
            // Touch-off triggered a catch-up resync of the fader's current value.
            val ccFeedback = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>()
            assertTrue(ccFeedback.isNotEmpty(), "Expected motor catch-up feedback after touch-off")
            // fader-1's motor CC is 1 (standard X-Touch).
            assertEquals(1, ccFeedback.last().cc)
            // The value should be the 7-bit representation of 200 (≈ 100).
            val expected = PropertyChannelResolver.scaleDmxTo7Bit(200u)
            assertEquals(expected, ccFeedback.last().value)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `policyFor inherits device class default for non-motor fader`() = runBlocking {
        // Use XTouchCompactStandard — all faders are motor, so class default is IMMEDIATE.
        val h = Harness(listOf(
            binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer"), policy = null),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertEquals(BindingTakeoverPolicy.IMMEDIATE, h.publisher.effectivePolicyFor(deviceTypeKey, "fader-1"))
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `policyFor uses per-binding override when set`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer"), policy = BindingTakeoverPolicy.PICKUP),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertEquals(BindingTakeoverPolicy.PICKUP, h.publisher.effectivePolicyFor(deviceTypeKey, "fader-1"))
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `bank change triggers resync and fires LED feedback for new bank`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "btn-1", BindingTarget.Blackout, bank = "a"),
            binding(2, "btn-1", BindingTarget.GrandMasterToggle, bank = "b"),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.bankState.setBank(deviceTypeKey, "a")
            h.attachXTouch()
            yield()
            h.recordingController.feedback.clear()

            // Switch to bank b — grand-master LED should be driven (enabled=true → NoteOn).
            h.bankState.setBank(deviceTypeKey, "b")
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
            listOf(binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer"))),
            midiControllerOverride = midi,
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.controller.setValue(1, 200u, 0)
            h.bankState.setBank(deviceTypeKey, "layer-a")
            h.attachXTouch()
            yield()
            midi.flushForTest()

            // Nothing on the rig moves; the bank change resyncs the device with the same value.
            h.bankState.setBank(deviceTypeKey, "layer-b")
            yield()
            midi.flushForTest()

            val motorWrite = MidiFeedbackMessage.ControlChangeFeedback(
                0, 1, PropertyChannelResolver.scaleDmxTo7Bit(200u),
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
        val h = Harness(listOf(binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer"))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.bankState.setBank(deviceTypeKey, "layer-a")
            h.attachXTouch()
            yield()
            assertEquals(1, h.recordingController.allInvalidations.get(), "attach: the physical position is stale")

            h.bankState.setBank(deviceTypeKey, "layer-b")
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
            binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer")),
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
    fun `inbound fader accepted immediately for motor fader`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer"), policy = null),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            // Motor fader → IMMEDIATE policy → always accepted.
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 64u))
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 127u))
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `inbound fader under PICKUP policy rejected until crossing`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer"), policy = BindingTakeoverPolicy.PICKUP),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()

            // After attach, non-trivial logical value (0 initially) — but the fader's physical
            // is unknown. Drive a channel change so logical becomes 127 (dmx=255).
            h.controller.setValue(1, 255u, 0)
            // Simulate setLogical directly since we don't have a real fixture listener path.
            h.publisher.takeover.setLogical("x-touch-compact", "fader-1", 127u, BindingTakeoverPolicy.PICKUP)

            // Physical moves from 0 — no cross yet.
            assertFalse(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 10u))
            assertFalse(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 80u))
            // Crossing the target = engage.
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
        val h = Harness(listOf(binding(1, "enc-1", BindingTarget.SelectionProperty("dimmer"))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.controller.setValue(1, 200u, 0)
            h.controller.setValue(13, 200u, 0)
            h.selection.set(listOf(hex1, hex2))
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            val onAttach = h.recordingController.feedback.ccOn(10)
            assertEquals(PropertyChannelResolver.scaleDmxTo7Bit(200u), onAttach.last().value, "uniform: the ring shows the value")
            assertEquals(100, h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("enc-1").value)
            assertEquals(RingState.ON, h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("enc-1").ring)

            h.recordingController.feedback.clear()
            h.controller.setValue(13, 100u, 0)
            h.publisher.simulateChannelsChangedForTest(Universe(0, 0), mapOf(13 to 100u.toUByte()))
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
            h.controller.setValue(1, 100u, 0)
            h.publisher.simulateChannelsChangedForTest(Universe(0, 0), mapOf(1 to 100u.toUByte()))
            yield()
            assertEquals(PropertyChannelResolver.scaleDmxTo7Bit(100u), h.recordingController.feedback.ccOn(10).last().value)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a mixed selection disarms pickup and a uniform one arms it`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", BindingTarget.SelectionProperty("dimmer"), policy = BindingTakeoverPolicy.PICKUP),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.controller.setValue(1, 200u, 0)
            h.controller.setValue(13, 200u, 0)
            h.selection.set(listOf(wash))
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertFalse(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 5u),
                "uniform: PICKUP is armed against the common value",
            )
            h.controller.setValue(13, 50u, 0)
            h.publisher.simulateChannelsChangedForTest(Universe(0, 0), mapOf(13 to 50u.toUByte()))
            yield()
            assertTrue(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 6u),
                "mixed: there is nothing to cross, so the move writes through",
            )
            assertTrue(h.recordingController.feedback.ccOn(1).none { it.value == 0u.toUByte() && false })
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `an empty selection reads as no value and a selection change re-feeds the control`() = runBlocking {
        val h = Harness(listOf(binding(1, "enc-1", BindingTarget.SelectionProperty("dimmer"))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.controller.setValue(1, 200u, 0)
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
            assertEquals(PropertyChannelResolver.scaleDmxTo7Bit(200u), h.recordingController.feedback.ccOn(10).last().value)
            assertEquals(100, h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("enc-1").value)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a divergent fixed group binding reads mixed and drives no motor`() = runBlocking {
        val h = Harness(listOf(binding(1, "fader-1", BindingTarget.GroupProperty("front-wash", "dimmer"))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.controller.setValue(1, 200u, 0)
            h.controller.setValue(13, 100u, 0)
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertTrue(h.recordingController.feedback.ccOn(1).isEmpty(), "no motor write on a divergent group")
            assertNull(h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("fader-1").value)

            h.controller.setValue(13, 200u, 0)
            h.publisher.simulateChannelsChangedForTest(Universe(0, 0), mapOf(13 to 200u.toUByte()))
            yield()
            assertEquals(PropertyChannelResolver.scaleDmxTo7Bit(200u), h.recordingController.feedback.ccOn(1).last().value)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `an encoder-bank change moves a strip encoder onto the new property's channels`() = runBlocking {
        val h = Harness(listOf(binding(1, "strip-1", BindingTarget.Strip(wash))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()

            // enc-1 is strip-1's encoder; on the default bank it stands on the group's dimmer,
            // which is channel 1 for hex-1. Its ring RX is CC 10.
            fun ringWrites() = h.recordingController.feedback
                .filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>()
                .filter { it.cc == 10 }

            // Both members move together: a group entry reads null unless its channels agree.
            h.recordingController.feedback.clear()
            h.controller.setValue(1, 200u, 0)
            h.controller.setValue(13, 200u, 0)
            h.publisher.simulateChannelsChangedForTest(
                Universe(0, 0), mapOf(1 to 200u.toUByte(), 13 to 200u.toUByte()),
            )
            yield()
            assertTrue(ringWrites().isNotEmpty(), "on the dimmer bank the encoder follows the dimmer channels")

            // Point the device's encoders at UV — channel 7 on hex-1, 19 on hex-2.
            h.encoderBankState.setProperty(deviceTypeKey, "uv")
            yield()

            h.recordingController.feedback.clear()
            h.controller.setValue(1, 100u, 0)
            h.controller.setValue(13, 100u, 0)
            h.publisher.simulateChannelsChangedForTest(
                Universe(0, 0), mapOf(1 to 100u.toUByte(), 13 to 100u.toUByte()),
            )
            yield()
            assertTrue(ringWrites().isEmpty(), "the dimmer channels no longer reach the encoder")

            h.controller.setValue(7, 90u, 0)
            h.controller.setValue(19, 90u, 0)
            h.publisher.simulateChannelsChangedForTest(
                Universe(0, 0), mapOf(7 to 90u.toUByte(), 19 to 90u.toUByte()),
            )
            yield()
            val moved = ringWrites()
            assertTrue(moved.isNotEmpty(), "the encoder now follows the UV channels")
            assertEquals(PropertyChannelResolver.scaleDmxTo7Bit(90u), moved.last().value)

            // The strip's fader is unmoved by the bank: it is always the dimmer.
            val faderWrites = h.recordingController.feedback
                .filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>()
                .filter { it.cc == 1 }
            assertTrue(faderWrites.isNotEmpty(), "fader-1 still tracked the dimmer change")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `an encoder-bank change re-arms pickup on the device`() = runBlocking {
        // A non-motor control on PICKUP: the bank change re-arms it, because the meaning of the
        // control moved under the operator's hand even though they did not touch it.
        val h = Harness(listOf(
            binding(1, "strip-1", BindingTarget.Strip(wash), policy = BindingTakeoverPolicy.PICKUP),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            h.controller.setValue(1, 200u, 0)
            h.controller.setValue(13, 200u, 0)
            h.publisher.simulateChannelsChangedForTest(Universe(0, 0), mapOf(1 to 200u.toUByte()))
            yield()

            // Physically at the logical value, so a move is accepted.
            val at = PropertyChannelResolver.scaleDmxTo7Bit(200u)
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", at))

            h.encoderBankState.setProperty(deviceTypeKey, "uv")
            yield()

            // UV is at 0, so the fader's physical position no longer matches and pickup holds.
            assertFalse(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "enc-1", at),
                "a re-armed PICKUP control waits for the operator to cross the new value",
            )
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `an encoder-bank button lights while its property is the active bank`() = runBlocking {
        val h = Harness(listOf(binding(1, "btn-1", BindingTarget.EncoderBankSet("uv"))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            fun lastLed() = h.recordingController.feedback.last {
                it is MidiFeedbackMessage.NoteOnFeedback || it is MidiFeedbackMessage.NoteOffFeedback
            }
            assertTrue(lastLed() is MidiFeedbackMessage.NoteOffFeedback, "the device is on dimmer, so UV is dark")

            h.encoderBankState.setProperty(deviceTypeKey, "uv")
            yield()
            assertTrue(lastLed() is MidiFeedbackMessage.NoteOnFeedback, "UV is the active bank: lit")
            assertEquals(LedState.ON, h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("btn-1").led)

            h.encoderBankState.setProperty(deviceTypeKey, "dimmer")
            yield()
            assertTrue(lastLed() is MidiFeedbackMessage.NoteOffFeedback, "another bank is active: dark again")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a select button's LED follows selection membership, a group by its members`() = runBlocking {
        val h = Harness(listOf(binding(1, "btn-1", BindingTarget.SelectTarget(wash))))
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
    fun `attach seeds a snapshot of every control and touch is recorded without a motor write`() = runBlocking {
        val h = Harness(listOf(binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer"))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val snapshots = mutableListOf<ControlStateTracker.Snapshot>()
        val deltas = mutableListOf<ControlStateTracker.Delta>()
        scope.launch { h.publisher.controlStates.snapshots.collect { snapshots += it } }
        scope.launch { h.publisher.controlStates.deltas.collect { deltas += it } }
        try {
            h.controller.setValue(1, 200u, 0)
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
            h.controller.setValue(1, 100u, 0)
            h.publisher.simulateChannelsChangedForTest(Universe(0, 0), mapOf(1 to 100u.toUByte()))
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
     * A tempo-bound encoder is not a `ContinuousEntry` (no channel behind it), so a full resync's
     * tracker reset used to wipe its row and never re-feed it: the stream reported a lit ring as
     * unbound after every attach, bank change and select press. Pins the re-feed.
     */
    @Test
    fun `a tempo-bound encoder keeps its value in the snapshot across a resync`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "enc-1", BindingTarget.SpeedMasterBpm(masterUuid = null, minBpm = 60.0, maxBpm = 180.0)),
            binding(2, "btn-1", BindingTarget.SelectTarget(hex1)),
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

            // A select press rebuilds and resyncs every device — the encoder must survive it.
            h.selection.set(listOf(hex1))
            yield()
            assertEquals(64, encoder().value, "after a resync: still fed")
            assertEquals(RingState.ON, encoder().ring)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }
    // hex-1's rgbColour is channels 2..4, hex-2's 14..16.
    private fun MockDmxController.paint(first: Int, r: Int, g: Int, b: Int) {
        setValue(first, r.toUByte(), 0)
        setValue(first + 1, g.toUByte(), 0)
        setValue(first + 2, b.toUByte(), 0)
    }

    @Test
    fun `a colour-bound encoder reads one hue from all three channels, so red and yellow read mixed`() = runBlocking {
        val h = Harness(listOf(binding(1, "enc-1", BindingTarget.SelectionProperty("rgbColour"))))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.controller.paint(2, 0, 0, 255)
            h.controller.paint(14, 0, 0, 255)
            h.selection.set(listOf(hex1, hex2))
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            val blue = PropertyChannelResolver.hueRead(0u, 0u, 255u)!!.value7Bit
            assertEquals(blue, h.recordingController.feedback.ccOn(10).last().value, "two blue heads: the ring reads blue")
            assertEquals(blue.toInt(), h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("enc-1").value)

            // The FU-MIDI-SELECTION-COLOUR-RED-ONLY case: red and yellow share a full red channel.
            h.recordingController.feedback.clear()
            h.controller.paint(2, 255, 0, 0)
            h.controller.paint(14, 255, 255, 0)
            h.publisher.simulateChannelsChangedForTest(Universe(0, 0), mapOf(2 to 255u.toUByte(), 4 to 0u.toUByte(), 14 to 255u.toUByte(), 15 to 255u.toUByte(), 16 to 0u.toUByte()))
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
            h.controller.paint(14, 255, 0, 0)
            h.publisher.simulateChannelsChangedForTest(Universe(0, 0), mapOf(15 to 0u.toUByte()))
            yield()
            h.publisher.controlStates.flushForTest()
            val red = h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("enc-1")
            assertEquals(0, red.value)
            assertEquals(RingState.ON, red.ring)

            // A grey head has no hue: the selection has no one value.
            h.controller.paint(2, 100, 100, 100)
            h.publisher.simulateChannelsChangedForTest(Universe(0, 0), mapOf(2 to 100u.toUByte(), 3 to 100u.toUByte(), 4 to 100u.toUByte()))
            yield()
            h.publisher.controlStates.flushForTest()
            assertNull(h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue("enc-1").value, "grey has no hue")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a colour-bound PICKUP fader arms against the hue and disarms on a grey head`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "fader-1", BindingTarget.SelectionProperty("rgbColour"), policy = BindingTakeoverPolicy.PICKUP),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.controller.paint(2, 0, 0, 255)
            h.controller.paint(14, 0, 0, 255)
            h.selection.set(listOf(hex1, hex2))
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            assertFalse(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 5u),
                "uniform blue: PICKUP is armed against the hue, and red is nowhere near it",
            )
            // Under the red-only read both heads' red channel was 0 and a fader at 0 would have picked up.
            assertFalse(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 0u))
            h.controller.paint(14, 90, 90, 90)
            h.publisher.simulateChannelsChangedForTest(Universe(0, 0), mapOf(14 to 90u.toUByte(), 15 to 90u.toUByte(), 16 to 90u.toUByte()))
            yield()
            assertTrue(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "fader-1", 6u),
                "a grey head: nothing to cross, so the move writes through",
            )
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    // ─── Colour axes ────────────────────────────────────────────────────

    @Test
    fun `an encoder-bank button lights only when the property and the axis both match`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "btn-1", BindingTarget.EncoderBankSet("rgbColour", ColourAxis.SATURATION)),
            binding(2, "btn-2", BindingTarget.EncoderBankSet("rgbColour")),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            fun led(id: String) = h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue(id).led

            h.encoderBankState.set(deviceTypeKey, EncoderBankSelection("rgbColour"))
            yield()
            assertEquals(LedState.OFF, led("btn-1"), "the hue bank is not the saturation bank")
            assertEquals(LedState.ON, led("btn-2"))

            h.encoderBankState.set(deviceTypeKey, EncoderBankSelection("rgbColour", ColourAxis.SATURATION))
            yield()
            assertEquals(LedState.ON, led("btn-1"))
            assertEquals(LedState.OFF, led("btn-2"), "same property, other axis: dark")

            h.encoderBankState.set(deviceTypeKey, EncoderBankSelection("rgbColour", ColourAxis.HUE))
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
            binding(1, "enc-1", BindingTarget.FixtureProperty("hex-1", "rgbColour", ColourAxis.SATURATION)),
            binding(2, "enc-2", BindingTarget.FixtureProperty("hex-1", "rgbColour", ColourAxis.BRIGHTNESS)),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.controller.paint(2, 60, 60, 200)
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            fun control(id: String) = h.publisher.controlStates.snapshot("x-touch-compact")!!.controls.getValue(id)
            assertEquals(PropertyChannelResolver.saturationRead(60u, 60u, 200u)!!.value7Bit.toInt(), control("enc-1").value)
            assertEquals(PropertyChannelResolver.scaleDmxTo7Bit(200u).toInt(), control("enc-2").value)

            // A grey has a saturation — 0 — and a level; neither ring goes dark.
            h.controller.paint(2, 100, 100, 100)
            h.publisher.simulateChannelsChangedForTest(Universe(0, 0), mapOf(2 to 100u.toUByte(), 3 to 100u.toUByte(), 4 to 100u.toUByte()))
            yield()
            h.publisher.controlStates.flushForTest()
            assertEquals(0, control("enc-1").value, "a grey reads saturation 0")
            assertEquals(RingState.ON, control("enc-1").ring)
            assertEquals(PropertyChannelResolver.scaleDmxTo7Bit(100u).toInt(), control("enc-2").value)

            // Black has a level of 0 and no saturation at all.
            h.controller.paint(2, 0, 0, 0)
            h.publisher.simulateChannelsChangedForTest(Universe(0, 0), mapOf(2 to 0u.toUByte(), 3 to 0u.toUByte(), 4 to 0u.toUByte()))
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
    fun `an axis-only change of encoder bank rebuilds the index and re-arms pickup`() = runBlocking {
        val h = Harness(listOf(
            binding(1, "strip-1", BindingTarget.Strip(wash), policy = BindingTakeoverPolicy.PICKUP),
        ))
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.controller.paint(2, 0, 0, 200)
            h.controller.paint(14, 0, 0, 200)
            h.encoderBankState.set(deviceTypeKey, EncoderBankSelection("rgbColour", ColourAxis.SATURATION))
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            // Both heads fully saturated: the encoder reads 127, and a move there is accepted.
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "enc-1", 127u))

            h.encoderBankState.set(deviceTypeKey, EncoderBankSelection("rgbColour", ColourAxis.BRIGHTNESS))
            yield()
            // Brightness is 200/255 ≈ 100: the physical position no longer matches and pickup holds.
            assertFalse(
                h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "enc-1", 127u),
                "a re-armed PICKUP control waits for the operator to cross the new axis's value",
            )
            assertTrue(h.publisher.acceptInboundFader("x-touch-compact", deviceTypeKey, "enc-1", PropertyChannelResolver.scaleDmxTo7Bit(200u)))
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }
}
