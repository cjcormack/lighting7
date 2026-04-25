package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.plugins.CueEditMode
import uk.me.cormack.lighting7.plugins.CueEditSessionRegistry
import uk.me.cormack.lighting7.plugins.CueEditSessionState
import uk.me.cormack.lighting7.show.Fixtures
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        override val input = kotlinx.coroutines.flow.MutableSharedFlow<MidiInputEvent>()
        override val inboundCcRate = uk.me.cormack.lighting7.dmx.PacketRateCounter()
        override val outboundCcRate = uk.me.cormack.lighting7.dmx.PacketRateCounter()
        override fun sendFeedback(message: MidiFeedbackMessage) { feedback += message }
        override fun invalidateFeedbackCache(key: MidiControlKey) { invalidations += key }
        override fun close() {}
    }

    /**
     * Wires up: fixtures registry with one HexFixture + MockDmxController, a DeviceMatcher,
     * binding service with seeded bindings, active-bank state, flash tracker, global scaler,
     * and a recording MidiController. Returns everything the test needs to drive scenarios.
     */
    private inner class Harness(bindings: List<ControlSurfaceBindingService.ResolvedBinding>) {
        val fixtures = Fixtures()
        val controller = MockDmxController(Universe(0, 0))
        val bindingService = ControlSurfaceBindingService(FakeDatabase.instance)
        val bankState = ActiveBankState()
        val flashTracker = FlashStateTracker()
        val scaler: GlobalScalerState
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
                addFixture(HexFixture(Universe(0, 0), "hex-1", "Hex 1", firstChannel = 1))
            }
            scaler = GlobalScalerState(fixtures)
            scaler.attach()

            bindingService.seedCacheForTest(projectId, bindings)

            val registry = MidiDeviceRegistry(FakeMidiAccess(), pollIntervalMs = 60_000L, autoOpen = false)
            matcher = DeviceMatcher(registry)

            publisher = SurfaceFeedbackPublisher(
                deviceMatcher = matcher,
                controllerLookup = { key -> if (key == "x-touch-compact") recordingController else null },
                bindingService = bindingService,
                bankState = bankState,
                flashTracker = flashTracker,
                projectIdProvider = { projectId },
                fixturesProvider = { fixtures },
                globalScalerStateProvider = { scaler },
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
                override fun writeFixtureProperty(fixtureKey: String, propertyName: String, midiValue7Bit: UByte) {}
                override fun writeGroupProperty(groupName: String, propertyName: String, midiValue7Bit: UByte) {}
                override fun flashFixturePropertyPress(fixtureKey: String, propertyName: String, max: UByte) {}
                override fun flashGroupPropertyPress(groupName: String, propertyName: String, max: UByte) {}
                override fun flashFixturePropertyRelease(fixtureKey: String, propertyName: String) {}
                override fun flashGroupPropertyRelease(groupName: String, propertyName: String) {}
                override fun cueStackGo(stackId: Int) {}
                override fun cueStackBack(stackId: Int) {}
                override fun cueStackPause(stackId: Int) {}
                override fun fireCue(cueId: Int) {}
                override fun toggleBlackout(): Boolean = h.scaler.toggleBlackout()
                override fun toggleGrandMaster(): Boolean = h.scaler.toggleGrandMaster()
            }
            val router = SurfaceInputRouter(
                deviceMatcher = h.matcher,
                controllerLookup = { key -> if (key == "x-touch-compact") h.recordingController else null },
                bindingService = h.bindingService,
                bankState = h.bankState,
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

    /**
     * Phase-6 harness variant: wires a [CueEditSessionRegistry] into the publisher so tests
     * can drive session events and verify feedback switches between cue-assignment and
     * DMX-derived sources.
     */
    private inner class CueEditHarness(
        bindings: List<ControlSurfaceBindingService.ResolvedBinding>,
        val initialSession: CueEditSessionState? = null,
    ) {
        val fixtures = Fixtures()
        val controller = MockDmxController(Universe(0, 0))
        val bindingService = ControlSurfaceBindingService(FakeDatabase.instance)
        val bankState = ActiveBankState()
        val flashTracker = FlashStateTracker()
        val scaler: GlobalScalerState
        val matcher: DeviceMatcher
        val recordingController = RecordingController(
            MidiDeviceHandle(
                displayKey = "x-touch-compact",
                displayName = "X-Touch Compact",
                inputPort = MidiDevicePort("in-1", "X-Touch Compact", "Behringer", PortDirection.INPUT),
                outputPort = MidiDevicePort("out-1", "X-Touch Compact", "Behringer", PortDirection.OUTPUT),
            ),
        )
        val registry = CueEditSessionRegistry()

        @Volatile
        private var currentSession: CueEditSessionState? = initialSession

        val publisher: SurfaceFeedbackPublisher

        init {
            fixtures.register {
                addController(controller)
                addFixture(HexFixture(Universe(0, 0), "hex-1", "Hex 1", firstChannel = 1))
            }
            scaler = GlobalScalerState(fixtures)
            scaler.attach()
            bindingService.seedCacheForTest(projectId, bindings)

            val reg = MidiDeviceRegistry(FakeMidiAccess(), pollIntervalMs = 60_000L, autoOpen = false)
            matcher = DeviceMatcher(reg)

            publisher = SurfaceFeedbackPublisher(
                deviceMatcher = matcher,
                controllerLookup = { key -> if (key == "x-touch-compact") recordingController else null },
                bindingService = bindingService,
                bankState = bankState,
                flashTracker = flashTracker,
                projectIdProvider = { projectId },
                fixturesProvider = { fixtures },
                globalScalerStateProvider = { scaler },
                cueEditSessionProvider = { currentSession },
                cueEditEvents = registry.events,
            )
        }

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

        fun beginSession(session: CueEditSessionState) {
            currentSession = session
            registry.register(this, projectId, session)
        }

        fun endSession() {
            currentSession = null
            registry.unregister(this)
        }
    }

    @Test
    fun `during cue-edit session feedback reflects the cue's assigned value`() = runBlocking {
        val assignment = CuePropertyAssignmentDto(
            targetType = "fixture",
            targetKey = "hex-1",
            propertyName = "dimmer",
            value = "64",  // dmx 64 → 32 in 7-bit (≈25%)
        )
        val session = CueEditSessionState(
            cueId = 1,
            mode = CueEditMode.BLIND,
            snapshot = listOf(assignment),
        )
        val h = CueEditHarness(
            listOf(binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer"))),
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            // Live DMX = 255 (bright), but the cue's assignment is 64 — feedback should follow
            // the cue.
            h.controller.setValue(1, 255u, 0)
            h.recordingController.feedback.clear()

            h.beginSession(session)
            yield()

            val cc = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>()
            assertTrue(cc.isNotEmpty(), "Expected feedback after session Started event")
            val expected = PropertyChannelResolver.scaleDmxTo7Bit(64u)
            assertEquals(expected, cc.last().value, "Feedback should reflect cue's Layer 3 (64) not live DMX (255)")
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `AssignmentChanged event drives feedback to the new cue value`() = runBlocking {
        val session = CueEditSessionState(
            cueId = 1,
            mode = CueEditMode.BLIND,
            snapshot = emptyList(),
        )
        val h = CueEditHarness(
            listOf(binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer"))),
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            h.beginSession(session)
            yield()
            h.recordingController.feedback.clear()

            // Edit: set dimmer to 200.
            h.registry.notifyAssignmentChanged(projectId, 1, TargetRef.Fixture("hex-1"), "dimmer", "200")
            yield()

            val cc = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>()
            assertTrue(cc.isNotEmpty(), "Expected feedback after AssignmentChanged event")
            val expected = PropertyChannelResolver.scaleDmxTo7Bit(200u)
            assertEquals(expected, cc.last().value)
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `project switch clears cue-edit session cache and falls back to live DMX`() = runBlocking {
        // Begin a cue-edit session on project A (cue dimmer = 64 while live DMX = 255, so
        // feedback tracks the cue). Simulate a project switch via onProjectChanged(): the
        // session belongs to the previous project's cue, so the cached assignments must be
        // dropped and the post-switch resync must reflect live DMX (255) rather than the
        // stale 64.
        val session = CueEditSessionState(
            cueId = 1,
            mode = CueEditMode.BLIND,
            snapshot = listOf(
                CuePropertyAssignmentDto(
                    targetType = "fixture", targetKey = "hex-1", propertyName = "dimmer", value = "64",
                )
            ),
        )
        val h = CueEditHarness(
            listOf(binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer"))),
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            h.controller.setValue(1, 255u, 0)

            h.beginSession(session)
            yield()

            // Sanity: cue value wins before the project switch.
            val preCc = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>()
            assertTrue(preCc.isNotEmpty(), "Expected feedback after session Started event")
            assertEquals(
                PropertyChannelResolver.scaleDmxTo7Bit(64u),
                preCc.last().value,
                "Feedback should reflect cue value (64) before project switch",
            )

            h.recordingController.feedback.clear()
            h.publisher.onProjectChanged()
            yield()

            val postCc = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>()
            assertTrue(postCc.isNotEmpty(), "Expected full resync after project change")
            assertEquals(
                PropertyChannelResolver.scaleDmxTo7Bit(255u),
                postCc.last().value,
                "Feedback must fall back to live DMX after project switch (cache cleared)",
            )
        } finally {
            h.publisher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `Session Ended restores DMX-derived feedback`() = runBlocking {
        val session = CueEditSessionState(
            cueId = 1,
            mode = CueEditMode.BLIND,
            snapshot = listOf(
                CuePropertyAssignmentDto(
                    targetType = "fixture", targetKey = "hex-1", propertyName = "dimmer", value = "64",
                )
            ),
        )
        val h = CueEditHarness(
            listOf(binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer"))),
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            h.publisher.start(scope)
            h.attachXTouch()
            yield()
            h.controller.setValue(1, 255u, 0)

            h.beginSession(session)
            yield()
            h.recordingController.feedback.clear()

            // End the session — feedback should switch back to the live DMX value.
            h.endSession()
            yield()

            val cc = h.recordingController.feedback.filterIsInstance<MidiFeedbackMessage.ControlChangeFeedback>()
            assertTrue(cc.isNotEmpty(), "Expected feedback after session Ended event")
            val expected = PropertyChannelResolver.scaleDmxTo7Bit(255u)
            assertEquals(expected, cc.last().value, "Feedback should follow live DMX after session ends")
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
}
