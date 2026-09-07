package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.perf.MidiLatencyStage
import uk.me.cormack.lighting7.perf.MidiLatencyTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [SurfaceInputRouter]. Uses [SurfaceInputRouter.offerInputForTest] to drive
 * the routing pipeline synchronously without needing a real MIDI transport. The matcher /
 * device attach paths are covered by higher-level tests (DeviceMatcherTest); here we focus
 * on event-to-descriptor matching, binding resolution, and dispatch semantics.
 */
class SurfaceInputRouterTest {

    private val projectId = 42
    private val deviceTypeKey = "x-touch-compact-standard"

    private fun buildRouter(
        actions: RecordingActions,
        bindings: List<ControlSurfaceBindingService.ResolvedBinding>,
        bankState: ActiveBankState = ActiveBankState(),
        encoderBankState: EncoderBankState = EncoderBankState(),
        flashTracker: FlashStateTracker = FlashStateTracker(),
        latencyTracker: MidiLatencyTracker = MidiLatencyTracker(),
        scope: CoroutineScope? = null,
        selectHoldMs: Long = SurfaceInputRouter.SELECT_HOLD_MS,
    ): SurfaceInputRouter {
        val bindingService = ControlSurfaceBindingService(FakeDatabase.instance)
        bindingService.seedCacheForTest(projectId, bindings)
        // DeviceMatcher is not exercised via offerInputForTest; the router type still needs one.
        val matcher = DeviceMatcher(
            MidiDeviceRegistry(FakeMidiAccess(), pollIntervalMs = 60_000L, autoOpen = false),
        )
        return SurfaceInputRouter(
            deviceMatcher = matcher,
            controllerLookup = { null },
            bindingService = bindingService,
            bankState = bankState,
            encoderBankState = encoderBankState,
            flashTracker = flashTracker,
            projectIdProvider = { projectId },
            actions = actions,
            latencyTracker = latencyTracker,
            selectHoldMs = selectHoldMs,
        ).also { router -> scope?.let { router.start(it) } }
    }

    private fun binding(
        id: Int,
        controlId: String,
        target: BindingTarget,
        bank: String? = null,
        health: AssignmentHealth = AssignmentHealth.Ok,
    ) = ControlSurfaceBindingService.ResolvedBinding(
        id = id,
        projectId = projectId,
        deviceTypeKey = deviceTypeKey,
        controlId = controlId,
        bank = bank,
        target = target,
        takeoverPolicy = BindingTakeoverPolicy.IMMEDIATE,
        sortOrder = 0,
        health = health,
    )

    @Test
    fun `fader CC dispatches to writeFixtureProperty with 7-bit value`() {
        val actions = RecordingActions()
        val router = buildRouter(
            actions,
            listOf(binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer"))),
        )
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(channel = 0, cc = 1, value = 100u))
        assertEquals(listOf<RecordedCall>(RecordedCall.WriteFixture("hex-1", "dimmer", 100u)), actions.calls.toList())
    }

    @Test
    fun `fader CC with no binding is a no-op`() {
        val actions = RecordingActions()
        val router = buildRouter(actions, emptyList())
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 50u))
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `group property binding dispatches to writeGroupProperty`() {
        val actions = RecordingActions()
        val router = buildRouter(
            actions,
            listOf(binding(1, "fader-2", BindingTarget.GroupProperty("front-wash", "dimmer"))),
        )
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 2, value = 64u))
        assertEquals(listOf<RecordedCall>(RecordedCall.WriteGroup("front-wash", "dimmer", 64u)), actions.calls.toList())
    }

    @Test
    fun `button press dispatches cueStackGo`() {
        val actions = RecordingActions()
        // btn-1 = note 16 on X-Touch Compact Standard Layer A.
        val router = buildRouter(actions, listOf(binding(1, "btn-1", BindingTarget.CueStackGo(stackId = 7))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        assertEquals(listOf<RecordedCall>(RecordedCall.CueStackGo(7)), actions.calls.toList())
    }

    @Test
    fun `button release on non-Flash is a no-op`() {
        val actions = RecordingActions()
        val router = buildRouter(actions, listOf(binding(1, "btn-1", BindingTarget.CueStackGo(7))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 16, velocity = 0u))
        // Only the press recorded an action.
        assertEquals(1, actions.calls.size)
    }

    @Test
    fun `flash press and release call flash actions and track state`() {
        val actions = RecordingActions()
        val flashTracker = FlashStateTracker()
        val flash = BindingTarget.Flash(
            target = BindingTarget.FixtureProperty("hex-1", "dimmer"),
            max = 255,
        )
        val router = buildRouter(actions, listOf(binding(1, "btn-2", flash)), flashTracker = flashTracker)

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        assertTrue(flashTracker.isActive(1))
        assertEquals(
            listOf<RecordedCall>(RecordedCall.FlashFixturePress("hex-1", "dimmer", 255u)),
            actions.calls.toList(),
        )

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 17, velocity = 0u))
        assertEquals(
            listOf<RecordedCall>(
                RecordedCall.FlashFixturePress("hex-1", "dimmer", 255u),
                RecordedCall.FlashFixtureRelease("hex-1", "dimmer"),
            ),
            actions.calls.toList(),
        )
        assertFalse(flashTracker.isActive(1))
    }

    @Test
    fun `repeated flash press does not fire action twice`() {
        val actions = RecordingActions()
        val flash = BindingTarget.Flash(BindingTarget.FixtureProperty("hex-1", "dimmer"), max = 200)
        val router = buildRouter(actions, listOf(binding(1, "btn-2", flash)))

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        assertEquals(1, actions.calls.size)
    }

    @Test
    fun `bank button press updates ActiveBankState without binding resolution`() {
        val bankState = ActiveBankState()
        val actions = RecordingActions()
        val router = buildRouter(actions, emptyList(), bankState)

        // X-Touch layer-a button = Program Change 0.
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ProgramChange(channel = 0, program = 0))
        assertEquals("layer-a", bankState.bankFor(deviceTypeKey))
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `bank-scoped binding resolves only under the matching bank`() {
        val actions = RecordingActions()
        val bankState = ActiveBankState()
        val router = buildRouter(
            actions,
            listOf(
                binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer"), bank = "layer-b"),
            ),
            bankState,
        )

        // No bank active → no binding → no action.
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        assertTrue(actions.calls.isEmpty())

        // Activate bank-b → binding now resolves.
        bankState.setBank(deviceTypeKey, "layer-b")
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        assertEquals(listOf<RecordedCall>(RecordedCall.WriteFixture("hex-1", "dimmer", 100u)), actions.calls.toList())
    }

    @Test
    fun `Blackout target toggles global scaler via actions`() {
        val actions = RecordingActions()
        val router = buildRouter(actions, listOf(binding(1, "btn-3", BindingTarget.Blackout)))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 18, velocity = 127u))
        assertEquals(listOf<RecordedCall>(RecordedCall.ToggleBlackout), actions.calls.toList())
    }

    @Test
    fun `SetBank target updates ActiveBankState`() {
        val bankState = ActiveBankState()
        val actions = RecordingActions()
        val router = buildRouter(
            actions,
            listOf(binding(1, "btn-4", BindingTarget.SetBank(deviceTypeKey, "layer-a"))),
            bankState,
        )
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 19, velocity = 127u))
        assertEquals("layer-a", bankState.bankFor(deviceTypeKey))
    }

    @Test
    fun `encoder CC dispatches same as fader for FixtureProperty`() {
        val actions = RecordingActions()
        // enc-1 = CC 10 on X-Touch Compact Layer A.
        val router = buildRouter(
            actions,
            listOf(binding(1, "enc-1", BindingTarget.FixtureProperty("hex-1", "dimmer"))),
        )
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 80u))
        assertEquals(listOf<RecordedCall>(RecordedCall.WriteFixture("hex-1", "dimmer", 80u)), actions.calls.toList())
    }

    @Test
    fun `SpeedMasterTap target taps the named master on press only`() {
        val actions = RecordingActions()
        val master = "7d444840-9dc0-11d1-b245-5ffdce74fad2"
        val router = buildRouter(actions, listOf(binding(1, "btn-3", BindingTarget.SpeedMasterTap(master))))

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 18, velocity = 127u))
        // Release must be a no-op — a tap marks one beat, it does not bracket a duration.
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 18, velocity = 0u))

        assertEquals(listOf<RecordedCall>(RecordedCall.TapSpeedMaster(master)), actions.calls.toList())
    }

    @Test
    fun `SpeedMasterBpm target passes the configured window through on encoder moves`() {
        val actions = RecordingActions()
        // enc-1 = CC 10 on X-Touch Compact Layer A.
        val router = buildRouter(
            actions,
            listOf(binding(1, "enc-1", BindingTarget.SpeedMasterBpm(masterUuid = null, minBpm = 90.0, maxBpm = 150.0))),
        )

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 0u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 127u))

        // The router hands the raw 7-bit value and the window to the action layer; the
        // scaling itself lives in DefaultSurfaceActions, which owns the clock's clamp.
        assertEquals(
            listOf<RecordedCall>(
                RecordedCall.WriteSpeedMasterBpm(null, 90.0, 150.0, 0u),
                RecordedCall.WriteSpeedMasterBpm(null, 90.0, 150.0, 127u),
            ),
            actions.calls.toList(),
        )
    }

    @Test
    fun `a button press on a BPM target is ignored rather than jumping the tempo`() {
        val actions = RecordingActions()
        val router = buildRouter(actions, listOf(binding(1, "btn-3", BindingTarget.SpeedMasterBpm())))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 18, velocity = 127u))
        assertTrue(actions.calls.isEmpty(), "a press carries no position, so there is no tempo to set")
    }

    @Test
    fun `touch event is forwarded to feedback hooks`() {
        val actions = RecordingActions()
        val feedback = RecordingFeedbackHooks()
        val router = SurfaceInputRouter(
            deviceMatcher = DeviceMatcher(MidiDeviceRegistry(FakeMidiAccess(), pollIntervalMs = 60_000L, autoOpen = false)),
            controllerLookup = { null },
            bindingService = ControlSurfaceBindingService(FakeDatabase.instance).also {
                it.seedCacheForTest(projectId, emptyList())
            },
            bankState = ActiveBankState(),
            encoderBankState = EncoderBankState(),
            flashTracker = FlashStateTracker(),
            projectIdProvider = { projectId },
            actions = actions,
            feedbackHooks = feedback,
        )
        // fader-1 touch is CC 101 on X-Touch Compact Standard (Layer A).
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 101, value = 127u), displayKey = "dev-a")
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 101, value = 0u), displayKey = "dev-a")
        assertEquals(
            listOf<RecordingFeedbackHooks.Call>(
                RecordingFeedbackHooks.Call.Touch("dev-a", "fader-1", true),
                RecordingFeedbackHooks.Call.Touch("dev-a", "fader-1", false),
            ),
            feedback.calls.toList(),
        )
        // Touch doesn't reach actions.
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `feedback hooks rejection suppresses continuous dispatch`() {
        val actions = RecordingActions()
        val feedback = RecordingFeedbackHooks()
        feedback.acceptReturn = false  // always reject pickup
        val router = SurfaceInputRouter(
            deviceMatcher = DeviceMatcher(MidiDeviceRegistry(FakeMidiAccess(), pollIntervalMs = 60_000L, autoOpen = false)),
            controllerLookup = { null },
            bindingService = ControlSurfaceBindingService(FakeDatabase.instance).also {
                it.seedCacheForTest(projectId, listOf(binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer"))))
            },
            bankState = ActiveBankState(),
            encoderBankState = EncoderBankState(),
            flashTracker = FlashStateTracker(),
            projectIdProvider = { projectId },
            actions = actions,
            feedbackHooks = feedback,
        )
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        // Hook saw the attempt but action was suppressed.
        assertEquals(1, feedback.calls.size)
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `dead fader binding is dropped without invoking actions`() {
        val actions = RecordingActions()
        val router = buildRouter(
            actions,
            listOf(
                binding(
                    1, "fader-1", BindingTarget.FixtureProperty("hex-gone", "dimmer"),
                    health = AssignmentHealth.MissingFixture("hex-gone"),
                ),
            ),
        )
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `dead button binding is dropped without invoking actions`() {
        val actions = RecordingActions()
        val router = buildRouter(
            actions,
            listOf(
                binding(
                    1, "btn-1", BindingTarget.CueStackGo(7),
                    health = AssignmentHealth.MissingStack(7),
                ),
            ),
        )
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `NoteOn with velocity 0 is treated as release`() {
        val actions = RecordingActions()
        val flashTracker = FlashStateTracker()
        val flash = BindingTarget.Flash(BindingTarget.FixtureProperty("hex-1", "dimmer"), max = 255)
        val router = buildRouter(actions, listOf(binding(1, "btn-2", flash)), flashTracker = flashTracker)

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 0u))
        assertEquals(2, actions.calls.size)
        assertEquals(RecordedCall.FlashFixtureRelease("hex-1", "dimmer"), actions.calls.last())
        assertFalse(flashTracker.isActive(1))
    }

    @Test
    fun `latency tracker records a sample on continuous dispatch`() {
        val actions = RecordingActions()
        val tracker = MidiLatencyTracker()
        val router = buildRouter(
            actions,
            listOf(binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "dimmer"))),
            latencyTracker = tracker,
        )
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        assertEquals(1, tracker.bucket(MidiLatencyStage.INGRESS_CONTINUOUS).count)
        assertEquals(0, tracker.bucket(MidiLatencyStage.INGRESS_BUTTON).count)
    }

    @Test
    fun `latency tracker records button press and release into INGRESS_BUTTON`() {
        val actions = RecordingActions()
        val tracker = MidiLatencyTracker()
        val flash = BindingTarget.Flash(BindingTarget.FixtureProperty("hex-1", "dimmer"), max = 255)
        val router = buildRouter(actions, listOf(binding(1, "btn-2", flash)), latencyTracker = tracker)

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 17, velocity = 0u))
        assertEquals(2, tracker.bucket(MidiLatencyStage.INGRESS_BUTTON).count)
    }

    @Test
    fun `latency tracker is not invoked when no binding resolves`() {
        val actions = RecordingActions()
        val tracker = MidiLatencyTracker()
        val router = buildRouter(actions, emptyList(), latencyTracker = tracker)
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        assertEquals(0, tracker.bucket(MidiLatencyStage.INGRESS_CONTINUOUS).count)
        assertEquals(0, tracker.bucket(MidiLatencyStage.INGRESS_BUTTON).count)
    }

    // ─── Selection-relative arms (midi-surface plan, session 1) ────────────

    @Test
    fun `a SelectionProperty fader dispatches the property and the value`() {
        val actions = RecordingActions()
        val router = buildRouter(actions, listOf(binding(1, "fader-1", BindingTarget.SelectionProperty("dimmer"))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        assertEquals(listOf<RecordedCall>(RecordedCall.WriteSelection("dimmer", 100u)), actions.calls.toList())
    }

    @Test
    fun `a SelectionProperty on a button writes full and a release does nothing`() {
        val actions = RecordingActions()
        val router = buildRouter(actions, listOf(binding(1, "btn-1", BindingTarget.SelectionProperty("dimmer"))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 16, velocity = 0u))
        assertEquals(listOf<RecordedCall>(RecordedCall.WriteSelection("dimmer", 127u)), actions.calls.toList())
    }

    @Test
    fun `select clear and locate buttons dispatch on press only`() {
        val actions = RecordingActions()
        val wash = CueTargetDto("group", "front-wash")
        val router = buildRouter(actions, listOf(
            binding(1, "btn-1", BindingTarget.SelectTarget(wash, BindingTarget.SelectMode.REPLACE)),
            binding(2, "btn-2", BindingTarget.ClearSelection),
            binding(3, "btn-3", BindingTarget.LocateSelection),
        ))
        for (note in 16..18) {
            router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = note, velocity = 127u))
            router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = note, velocity = 0u))
        }
        assertEquals(
            listOf(
                RecordedCall.SelectTarget(wash, BindingTarget.SelectMode.REPLACE),
                RecordedCall.ClearSelection,
                RecordedCall.LocateSelection,
            ),
            actions.calls.toList(),
        )
    }

    // ─── Strips, the encoder bank, and the select long press ───────────────

    @Test
    fun `a strip binding drives the fader, select and flash of its own controls`() {
        val actions = RecordingActions()
        val wash = CueTargetDto("group", "front-wash")
        val router = buildRouter(actions, listOf(binding(1, "strip-1", BindingTarget.Strip(wash))))

        // fader-1 is CC 1; btn-25 (select) is note 40; btn-1 (flash) is note 16.
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 40, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))

        assertEquals(
            listOf(
                RecordedCall.WriteGroup("front-wash", "dimmer", 100u),
                RecordedCall.SelectTarget(wash, BindingTarget.SelectMode.TOGGLE),
                RecordedCall.FlashGroupPress("front-wash", "dimmer", 255u),
            ),
            actions.calls.toList(),
        )
    }

    @Test
    fun `a strip encoder writes whatever the device's encoder bank names`() {
        val actions = RecordingActions()
        val encoderBank = EncoderBankState()
        val wash = CueTargetDto("group", "front-wash")
        val router = buildRouter(
            actions,
            listOf(binding(1, "strip-1", BindingTarget.Strip(wash))),
            encoderBankState = encoderBank,
        )

        // enc-1 is CC 10.
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 64u))
        encoderBank.setProperty(deviceTypeKey, "colour")
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 64u))

        assertEquals(
            listOf(
                RecordedCall.WriteGroup("front-wash", "dimmer", 64u),
                RecordedCall.WriteGroup("front-wash", "colour", 64u),
            ),
            actions.calls.toList(),
        )
    }

    @Test
    fun `an EncoderBankSet press moves the bank of the device the button is on`() {
        val actions = RecordingActions()
        val encoderBank = EncoderBankState()
        val router = buildRouter(
            actions,
            listOf(binding(1, "btn-1", BindingTarget.EncoderBankSet("colour"))),
            encoderBankState = encoderBank,
        )

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))

        assertEquals(EncoderBankSelection("colour"), encoderBank.selectionFor(deviceTypeKey))
        // It is desk state, not a show action — nothing reaches SurfaceActions.
        assertTrue(actions.calls.isEmpty())
    }

    // ─── Colour axes ────────────────────────────────────────────────────

    @Test
    fun `a continuous move carries the binding's colour axis to every property write`() {
        val actions = RecordingActions()
        val router = buildRouter(
            actions,
            listOf(
                binding(1, "fader-1", BindingTarget.FixtureProperty("hex-1", "rgbColour", ColourAxis.BRIGHTNESS)),
                binding(2, "fader-2", BindingTarget.GroupProperty("front-wash", "rgbColour", ColourAxis.SATURATION)),
                binding(3, "fader-3", BindingTarget.SelectionProperty("rgbColour", ColourAxis.HUE_FINE)),
            ),
        )
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 2, value = 64u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 3, value = 32u))
        assertEquals(
            listOf<RecordedCall>(
                RecordedCall.WriteFixture("hex-1", "rgbColour", 100u, ColourAxis.BRIGHTNESS),
                RecordedCall.WriteGroup("front-wash", "rgbColour", 64u, ColourAxis.SATURATION),
                RecordedCall.WriteSelection("rgbColour", 32u, ColourAxis.HUE_FINE),
            ),
            actions.calls.toList(),
        )
    }

    @Test
    fun `a button press on a continuous target carries the axis with its full value`() {
        val actions = RecordingActions()
        val router = buildRouter(
            actions,
            listOf(binding(1, "btn-1", BindingTarget.SelectionProperty("rgbColour", ColourAxis.SATURATION))),
        )
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        assertEquals(
            listOf<RecordedCall>(RecordedCall.WriteSelection("rgbColour", 127u, ColourAxis.SATURATION)),
            actions.calls.toList(),
        )
    }

    @Test
    fun `an EncoderBankSet press sets the property and the axis, and a strip encoder follows both`() {
        val actions = RecordingActions()
        val encoderBank = EncoderBankState()
        val wash = CueTargetDto("group", "front-wash")
        val router = buildRouter(
            actions,
            listOf(
                binding(1, "strip-1", BindingTarget.Strip(wash)),
                binding(2, "btn-2", BindingTarget.EncoderBankSet("rgbColour", ColourAxis.SATURATION)),
            ),
            encoderBankState = encoderBank,
        )

        // btn-2 is note 17; enc-1 is CC 10.
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        assertEquals(EncoderBankSelection("rgbColour", ColourAxis.SATURATION), encoderBank.selectionFor(deviceTypeKey))

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 40u))
        assertEquals(
            listOf<RecordedCall>(RecordedCall.WriteGroup("front-wash", "rgbColour", 40u, ColourAxis.SATURATION)),
            actions.calls.toList(),
            "the strip encoder writes the bank's axis",
        )

        // An axis-only change of bank is a change the encoder follows too.
        encoderBank.set(deviceTypeKey, EncoderBankSelection("rgbColour", ColourAxis.HUE_FINE))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 64u))
        assertEquals(RecordedCall.WriteGroup("front-wash", "rgbColour", 64u, ColourAxis.HUE_FINE), actions.calls.last())
    }

    @Test
    fun `a raw strip target never reaches dispatch`() {
        val actions = RecordingActions()
        val wash = CueTargetDto("group", "front-wash")
        // btn-2 belongs to no strip, so the Strip target sits on it undertived.
        val router = buildRouter(actions, listOf(binding(1, "btn-2", BindingTarget.Strip(wash))))

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 2, value = 50u))

        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `a short select press toggles and never replaces`() = runTest {
        val actions = RecordingActions()
        val wash = CueTargetDto("group", "front-wash")
        val router = buildRouter(
            actions,
            listOf(binding(1, "btn-1", BindingTarget.SelectTarget(wash, BindingTarget.SelectMode.TOGGLE))),
            scope = this,
            selectHoldMs = 500L,
        )

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        advanceTimeBy(100L)
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 16, velocity = 0u))
        advanceTimeBy(5_000L)

        assertEquals(
            listOf<RecordedCall>(RecordedCall.SelectTarget(wash, BindingTarget.SelectMode.TOGGLE)),
            actions.calls.toList(),
        )
        router.stop()
    }

    @Test
    fun `holding a select button past the threshold also replaces the selection`() = runTest {
        val actions = RecordingActions()
        val wash = CueTargetDto("group", "front-wash")
        val router = buildRouter(
            actions,
            listOf(binding(1, "btn-1", BindingTarget.SelectTarget(wash, BindingTarget.SelectMode.TOGGLE))),
            scope = this,
            selectHoldMs = 500L,
        )

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        advanceTimeBy(600L)
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 16, velocity = 0u))
        advanceTimeBy(5_000L)

        assertEquals(
            listOf(
                RecordedCall.SelectTarget(wash, BindingTarget.SelectMode.TOGGLE),
                RecordedCall.SelectTarget(wash, BindingTarget.SelectMode.REPLACE),
            ),
            actions.calls.toList(),
        )
        router.stop()
    }

    @Test
    fun `a REPLACE select binding acts on press and arms no hold`() = runTest {
        val actions = RecordingActions()
        val wash = CueTargetDto("group", "front-wash")
        val router = buildRouter(
            actions,
            listOf(binding(1, "btn-1", BindingTarget.SelectTarget(wash, BindingTarget.SelectMode.REPLACE))),
            scope = this,
            selectHoldMs = 500L,
        )

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        advanceTimeBy(5_000L)

        assertEquals(
            listOf<RecordedCall>(RecordedCall.SelectTarget(wash, BindingTarget.SelectMode.REPLACE)),
            actions.calls.toList(),
        )
        router.stop()
    }

    @Test
    fun `a release cancels the pending replace even when the binding went dead meanwhile`() = runTest {
        val actions = RecordingActions()
        val wash = CueTargetDto("group", "front-wash")
        val bindingService = ControlSurfaceBindingService(FakeDatabase.instance)
        bindingService.seedCacheForTest(
            projectId,
            listOf(binding(1, "btn-1", BindingTarget.SelectTarget(wash, BindingTarget.SelectMode.TOGGLE))),
        )
        val router = SurfaceInputRouter(
            deviceMatcher = DeviceMatcher(MidiDeviceRegistry(FakeMidiAccess(), pollIntervalMs = 60_000L, autoOpen = false)),
            controllerLookup = { null },
            bindingService = bindingService,
            bankState = ActiveBankState(),
            encoderBankState = EncoderBankState(),
            flashTracker = FlashStateTracker(),
            projectIdProvider = { projectId },
            actions = actions,
            selectHoldMs = 500L,
        )
        router.start(this)

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        // The binding goes dead under the operator's finger.
        bindingService.seedCacheForTest(
            projectId,
            listOf(
                binding(
                    1, "btn-1", BindingTarget.SelectTarget(wash, BindingTarget.SelectMode.TOGGLE),
                    health = AssignmentHealth.MissingGroup("front-wash"),
                ),
            ),
        )
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 16, velocity = 0u))
        advanceTimeBy(5_000L)

        assertEquals(
            listOf<RecordedCall>(RecordedCall.SelectTarget(wash, BindingTarget.SelectMode.TOGGLE)),
            actions.calls.toList(),
        )
        router.stop()
    }

    @Test
    fun `cue and stack presses carry the binding's uuid through to the actions`() {
        val actions = RecordingActions()
        val router = buildRouter(actions, listOf(
            binding(1, "btn-1", BindingTarget.FireCue(5, cueUuid = "7d444840-9dc0-11d1-b245-5ffdce74fad2")),
            binding(2, "btn-2", BindingTarget.CueStackGo(7, stackUuid = "11111111-2222-3333-4444-555555555555")),
        ))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        assertEquals(
            listOf(
                RecordedCall.FireCue(5, "7d444840-9dc0-11d1-b245-5ffdce74fad2"),
                RecordedCall.CueStackGo(7, "11111111-2222-3333-4444-555555555555"),
            ),
            actions.calls.toList(),
        )
    }

    // ─── Records on buttons (midi-surface plan D6) ─────────────────────
    //
    // The router's job for these six is a pure hand-off — every rule about *what* a press does
    // lives in `DefaultSurfaceActions` and `BuskPressService`, which is the point: a hardware press
    // and a screen press of one pad go through one implementation. What is worth pinning here is
    // that each variant reaches its own action with its own uuid, since the six are structurally
    // identical and a copy-paste slip between them would be invisible.

    @Test
    fun `a record button dispatches to its own action with its own uuid`() {
        val lookUuid = "11111111-1111-4111-8111-111111111111"
        val templateUuid = "22222222-2222-4222-8222-222222222222"
        val padUuid = "33333333-3333-4333-8333-333333333333"
        val actions = RecordingActions()
        val router = buildRouter(actions, listOf(
            binding(1, "btn-1", BindingTarget.ApplyLook(lookUuid)),
            binding(2, "btn-2", BindingTarget.PressTemplate(templateUuid)),
            binding(3, "btn-3", BindingTarget.PressPad(padUuid)),
        ))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 18, velocity = 127u))
        assertEquals(
            listOf(
                RecordedCall.ApplyLook(lookUuid),
                RecordedCall.PressTemplate(templateUuid),
                RecordedCall.PressPad(padUuid),
            ),
            actions.calls,
        )
    }

    @Test
    fun `busk page next and prev step in opposite directions`() {
        val actions = RecordingActions()
        val router = buildRouter(actions, listOf(
            binding(1, "btn-1", BindingTarget.BuskPageNext),
            binding(2, "btn-2", BindingTarget.BuskPagePrev),
        ))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        assertEquals(listOf<RecordedCall>(RecordedCall.BuskPageStep(1), RecordedCall.BuskPageStep(-1)), actions.calls)
    }

    @Test
    fun `a dead record binding never reaches the actions`() {
        val lookUuid = "11111111-1111-4111-8111-111111111111"
        val actions = RecordingActions()
        val router = buildRouter(actions, listOf(
            // The state a Look that gained a deferred effect after it was bound falls into: it is
            // still there, but a button has no selection to give it, so the press must be dropped
            // rather than reaching a `toggle` that would refuse it one layer down.
            binding(
                1, "btn-1", BindingTarget.ApplyLook(lookUuid),
                health = AssignmentHealth.LookNeedsSelection(lookUuid),
            ),
        ))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `a record target on a fader is ignored rather than dispatched`() {
        // The router half of `FU-MIDI-BIND-CONTROL-KIND`: `refuseWrongKind` stops such a row being
        // written, but an older row or an imported archive can still hold one, and continuous
        // dispatch must drop it rather than find some arm for it.
        val actions = RecordingActions()
        val router = buildRouter(actions, listOf(
            binding(1, "fader-1", BindingTarget.PressTemplate("22222222-2222-4222-8222-222222222222")),
        ))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `an Unknown target is dead and never reaches the actions`() {
        val actions = RecordingActions()
        val router = buildRouter(actions, listOf(
            binding(1, "btn-1", BindingTarget.Unknown("fromTheFuture", "{}"), health = AssignmentHealth.UnknownTarget("fromTheFuture")),
        ))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        assertTrue(actions.calls.isEmpty())
    }
}

/** Recording fake of [SurfaceActions] for tests. Every call appends to [calls]. */
private class RecordingActions : SurfaceActions {
    val calls = mutableListOf<RecordedCall>()
    override fun writeFixtureProperty(fixtureKey: String, propertyName: String, midiValue7Bit: UByte, colourAxis: ColourAxis?) {
        calls += RecordedCall.WriteFixture(fixtureKey, propertyName, midiValue7Bit, colourAxis)
    }
    override fun writeGroupProperty(groupName: String, propertyName: String, midiValue7Bit: UByte, colourAxis: ColourAxis?) {
        calls += RecordedCall.WriteGroup(groupName, propertyName, midiValue7Bit, colourAxis)
    }
    override fun flashFixturePropertyPress(fixtureKey: String, propertyName: String, max: UByte) {
        calls += RecordedCall.FlashFixturePress(fixtureKey, propertyName, max)
    }
    override fun flashGroupPropertyPress(groupName: String, propertyName: String, max: UByte) {
        calls += RecordedCall.FlashGroupPress(groupName, propertyName, max)
    }
    override fun flashFixturePropertyRelease(fixtureKey: String, propertyName: String) {
        calls += RecordedCall.FlashFixtureRelease(fixtureKey, propertyName)
    }
    override fun flashGroupPropertyRelease(groupName: String, propertyName: String) {
        calls += RecordedCall.FlashGroupRelease(groupName, propertyName)
    }
    override fun cueStackGo(stackId: Int, stackUuid: String?) { calls += RecordedCall.CueStackGo(stackId, stackUuid) }
    override fun cueStackBack(stackId: Int, stackUuid: String?) { calls += RecordedCall.CueStackBack(stackId, stackUuid) }
    override fun cueStackPause(stackId: Int, stackUuid: String?) { calls += RecordedCall.CueStackPause(stackId, stackUuid) }
    override fun fireCue(cueId: Int, cueUuid: String?) { calls += RecordedCall.FireCue(cueId, cueUuid) }
    override fun writeSelectionProperty(propertyName: String, midiValue7Bit: UByte, colourAxis: ColourAxis?) {
        calls += RecordedCall.WriteSelection(propertyName, midiValue7Bit, colourAxis)
    }
    override fun selectTarget(target: CueTargetDto, mode: BindingTarget.SelectMode) {
        calls += RecordedCall.SelectTarget(target, mode)
    }
    override fun clearSelection() { calls += RecordedCall.ClearSelection }
    override fun locateSelection() { calls += RecordedCall.LocateSelection }
    override fun toggleBlackout(): Boolean { calls += RecordedCall.ToggleBlackout; return true }
    override fun toggleGrandMaster(): Boolean { calls += RecordedCall.ToggleGrandMaster; return true }
    override fun writeSpeedMasterBpm(masterUuid: String?, minBpm: Double, maxBpm: Double, midiValue7Bit: UByte) {
        calls += RecordedCall.WriteSpeedMasterBpm(masterUuid, minBpm, maxBpm, midiValue7Bit)
    }
    override fun tapSpeedMaster(masterUuid: String?) {
        calls += RecordedCall.TapSpeedMaster(masterUuid)
    }
    override fun applyLook(lookUuid: String) { calls += RecordedCall.ApplyLook(lookUuid) }
    override fun pressTemplate(templateUuid: String) { calls += RecordedCall.PressTemplate(templateUuid) }
    override fun pressPad(padUuid: String) { calls += RecordedCall.PressPad(padUuid) }
    override fun buskPageStep(delta: Int) { calls += RecordedCall.BuskPageStep(delta) }
    override fun buskPageSet(pageUuid: String) { calls += RecordedCall.BuskPageSet(pageUuid) }
}

/** Recording fake of [SurfaceFeedbackHooks] for tests. */
private class RecordingFeedbackHooks : SurfaceFeedbackHooks {
    sealed class Call {
        data class Touch(val displayKey: String, val controlId: String, val down: Boolean) : Call()
        data class InboundFader(val displayKey: String, val deviceTypeKey: String, val controlId: String, val value: UByte) : Call()
    }
    val calls = mutableListOf<Call>()
    var acceptReturn: Boolean = true

    override fun onTouch(displayKey: String, controlId: String, down: Boolean) {
        calls += Call.Touch(displayKey, controlId, down)
    }

    override fun acceptInboundFader(
        displayKey: String,
        deviceTypeKey: String,
        controlId: String,
        value7Bit: UByte,
    ): Boolean {
        calls += Call.InboundFader(displayKey, deviceTypeKey, controlId, value7Bit)
        return acceptReturn
    }
}

private sealed class RecordedCall {
    data class WriteFixture(val fixtureKey: String, val prop: String, val value: UByte, val axis: ColourAxis? = null) : RecordedCall()
    data class WriteGroup(val groupName: String, val prop: String, val value: UByte, val axis: ColourAxis? = null) : RecordedCall()
    data class FlashFixturePress(val fixtureKey: String, val prop: String, val max: UByte) : RecordedCall()
    data class FlashGroupPress(val groupName: String, val prop: String, val max: UByte) : RecordedCall()
    data class FlashFixtureRelease(val fixtureKey: String, val prop: String) : RecordedCall()
    data class FlashGroupRelease(val groupName: String, val prop: String) : RecordedCall()
    data class CueStackGo(val stackId: Int, val stackUuid: String? = null) : RecordedCall()
    data class CueStackBack(val stackId: Int, val stackUuid: String? = null) : RecordedCall()
    data class CueStackPause(val stackId: Int, val stackUuid: String? = null) : RecordedCall()
    data class FireCue(val cueId: Int, val cueUuid: String? = null) : RecordedCall()
    data class WriteSelection(val prop: String, val value: UByte, val axis: ColourAxis? = null) : RecordedCall()
    data class SelectTarget(val target: CueTargetDto, val mode: BindingTarget.SelectMode) : RecordedCall()
    data object ClearSelection : RecordedCall()
    data object LocateSelection : RecordedCall()
    data object ToggleBlackout : RecordedCall()
    data object ToggleGrandMaster : RecordedCall()
    data class ApplyLook(val lookUuid: String) : RecordedCall()
    data class PressTemplate(val templateUuid: String) : RecordedCall()
    data class PressPad(val padUuid: String) : RecordedCall()
    data class BuskPageStep(val delta: Int) : RecordedCall()
    data class BuskPageSet(val pageUuid: String) : RecordedCall()
    data class WriteSpeedMasterBpm(
        val masterUuid: String?,
        val minBpm: Double,
        val maxBpm: Double,
        val value: UByte,
    ) : RecordedCall()
    data class TapSpeedMaster(val masterUuid: String?) : RecordedCall()
}
