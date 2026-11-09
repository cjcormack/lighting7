package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.perf.MidiLatencyStage
import uk.me.cormack.lighting7.perf.MidiLatencyTracker
import uk.me.cormack.lighting7.testsupport.RecordedCall
import uk.me.cormack.lighting7.testsupport.RecordingSurfaceActions
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SurfaceInputRouter]. Uses [SurfaceInputRouter.offerInputForTest] to drive
 * the routing pipeline synchronously without needing a real MIDI transport. The matcher /
 * device attach paths are covered by higher-level tests (DeviceMatcherTest); here we focus
 * on event-to-control-part matching, binding resolution, and dispatch semantics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SurfaceInputRouterTest {

    private val projectId = 42
    private val deviceTypeKey = "x-touch-compact-standard"

    private val hex1 = Subject.Fixture("hex-1")
    private val wash = Subject.Group("front-wash")
    private val washTarget = CueTargetDto("group", "front-wash")
    private val stack = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val master = UUID.fromString("7d444840-9dc0-11d1-b245-5ffdce74fad2")
    private val padUuid = UUID.fromString("44444444-4444-4444-8444-444444444444")
    private val bankUuid = UUID.fromString("55555555-5555-4555-8555-555555555555")
    private val fader = ControlSurfaceRegistry.typeFor(deviceTypeKey)!!.controls.filterIsInstance<FaderDescriptor>().first()

    private fun buildRouter(
        actions: RecordingSurfaceActions,
        bindings: List<ControlSurfaceBindingService.ResolvedBinding>,
        bankState: ActiveBankState = ActiveBankState(),
        encoderBankState: EncoderBankState = EncoderBankState(),
        holds: SurfaceHolds = SurfaceHolds(),
        latencyTracker: MidiLatencyTracker = MidiLatencyTracker(),
        scope: CoroutineScope? = null,
        selectHoldMs: Long = SurfaceDispatch.SELECT_HOLD_MS,
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
            holds = holds,
            projectIdProvider = { projectId },
            actions = actions,
            latencyTracker = latencyTracker,
            selectHoldMs = selectHoldMs,
        ).also { router -> scope?.let { router.start(it) } }
    }

    private fun binding(
        id: Int,
        controlId: String,
        bound: Binding,
        bank: String? = null,
        health: AssignmentHealth = AssignmentHealth.Ok,
        part: ControlPart = ControlPart.MAIN,
    ) = ControlSurfaceBindingService.ResolvedBinding(
        id = id,
        projectId = projectId,
        deviceTypeKey = deviceTypeKey,
        controlId = controlId,
        bank = bank,
        binding = bound,
        takeoverPolicy = BindingTakeoverPolicy.IMMEDIATE,
        sortOrder = 0,
        health = health,
        part = part,
    )

    @Test
    fun `a fader's move writes its level with the 7-bit value`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), hex1))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(channel = 0, cc = 1, value = 100u))
        assertEquals(listOf<RecordedCall>(RecordedCall.WriteLevel(hex1, "dimmer", 100u)), actions.calls.toList())
    }

    @Test
    fun `each input runs under the surface's own origin, a fader's moves keyed to fold into one step`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), hex1))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(channel = 0, cc = 1, value = 100u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(channel = 0, cc = 1, value = 90u))
        val (first, second) = actions.origins.map { assertNotNull(it) }
        assertEquals(uk.me.cormack.lighting7.history.HistorySource.surface(deviceTypeKey), first.source)
        assertTrue(first.requestId != second.requestId, "each input is its own request")
        assertEquals(first.coalesceKey, second.coalesceKey, "but one gesture")
        assertNull(uk.me.cormack.lighting7.history.WriteOrigin.current(), "and the origin ends with the input")
    }

    @Test
    fun `fader CC with no binding is a no-op`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, emptyList())
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 50u))
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `a group level binding writes to the group`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(binding(1, "fader-2", Binding(Does.Level("dimmer"), wash))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 2, value = 64u))
        assertEquals(listOf<RecordedCall>(RecordedCall.WriteLevel(wash, "dimmer", 64u)), actions.calls.toList())
    }

    @Test
    fun `a button press GOes its stack`() {
        val actions = RecordingSurfaceActions()
        // btn-1 = note 16 on X-Touch Compact Standard Layer A.
        val router = buildRouter(actions, listOf(binding(1, "btn-1", Binding(Does.Go, Subject.Record(RecordKind.STACK, stack)))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        assertEquals(listOf<RecordedCall>(RecordedCall.CueStackGo(stack)), actions.calls.toList())
    }

    /** `FU-MIDI-SHOW-GUARD`: an action that throws — `state.show` mid-switch — must not end the device's collector. */
    @Test
    fun `an input whose action throws is caught, and the next input still dispatches`() {
        val actions = RecordingSurfaceActions()
        actions.outcome = { throw IllegalStateException("show not ready") }
        val router = buildRouter(actions, listOf(binding(1, "btn-1", Binding(Does.Go, Subject.Record(RecordKind.STACK, stack)))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        actions.outcome = { ActionOutcome.DONE }
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        assertEquals(listOf<RecordedCall>(RecordedCall.CueStackGo(stack), RecordedCall.CueStackGo(stack)), actions.calls.toList())
    }

    @Test
    fun `a button release on anything but a While held binding does nothing`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(binding(1, "btn-1", Binding(Does.Go, Subject.Record(RecordKind.STACK, stack)))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 16, velocity = 0u))
        // Only the press recorded an action.
        assertEquals(1, actions.calls.size)
    }

    // ─── Flash, held per control part ──────────────────────────────────

    @Test
    fun `a flash press writes and its release clears, held on the control part`() {
        val actions = RecordingSurfaceActions()
        val holds = SurfaceHolds()
        val router = buildRouter(actions, listOf(binding(1, "btn-2", Binding(Does.Flash("dimmer"), hex1))), holds = holds)
        val key = SurfaceDispatch.HoldKey(deviceTypeKey, "btn-2", ControlPart.MAIN)

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        assertTrue(holds.isHeld(key), "the control part's LED reads it")
        assertEquals(listOf<RecordedCall>(RecordedCall.FlashPress(hex1, "dimmer", 100)), actions.calls.toList())

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 17, velocity = 0u))
        assertEquals(
            listOf(RecordedCall.FlashPress(hex1, "dimmer", 100), RecordedCall.FlashRelease(hex1, "dimmer")),
            actions.calls.toList(),
        )
        assertFalse(holds.isHeld(key))
    }

    @Test
    fun `repeated flash press does not fire action twice`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(binding(1, "btn-2", Binding(Does.Flash("dimmer", 78), hex1))))

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        assertEquals(listOf<RecordedCall>(RecordedCall.FlashPress(hex1, "dimmer", 78)), actions.calls.toList())
    }

    @Test
    fun `two devices of one type each hold their own flash, and each release lets go of its own`() {
        val actions = RecordingSurfaceActions()
        val holds = SurfaceHolds()
        // One binding on the device type, so both devices' btn-2 resolve to it.
        val router = buildRouter(actions, listOf(binding(1, "btn-2", Binding(Does.Flash("dimmer"), hex1))), holds = holds)
        val left = SurfaceDispatch.HoldKey("x-touch-left", "btn-2", ControlPart.MAIN)
        val right = SurfaceDispatch.HoldKey("x-touch-right", "btn-2", ControlPart.MAIN)
        val press = RecordedCall.FlashPress(hex1, "dimmer", 100)
        val release = RecordedCall.FlashRelease(hex1, "dimmer")

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u), displayKey = "x-touch-left")
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u), displayKey = "x-touch-right")
        assertEquals(listOf(press, press), actions.calls.toList(), "the second device's press is its own, not a retrigger")

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 17, velocity = 0u), displayKey = "x-touch-left")
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 17, velocity = 0u), displayKey = "x-touch-left")
        assertEquals(listOf(press, press, release), actions.calls.toList(), "the left release, once")
        assertFalse(holds.isHeld(left))
        assertTrue(holds.isHeld(right), "the right-hand flash is still down, and its LED still lit")

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 17, velocity = 0u), displayKey = "x-touch-right")
        assertEquals(listOf(press, press, release, release), actions.calls.toList())
        assertFalse(holds.isHeld(right))
    }

    @Test
    fun `bank button press updates ActiveBankState without binding resolution`() {
        val bankState = ActiveBankState()
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, emptyList(), bankState)

        // X-Touch layer-a button = Program Change 0.
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ProgramChange(channel = 0, program = 0))
        assertEquals("layer-a", bankState.bankFor(deviceTypeKey))
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `bank-scoped binding resolves only under the matching bank`() {
        val actions = RecordingSurfaceActions()
        val bankState = ActiveBankState()
        val router = buildRouter(
            actions,
            listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), hex1), bank = "layer-b")),
            bankState,
        )

        // No bank active → no binding → no action.
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        assertTrue(actions.calls.isEmpty())

        // Activate bank-b → binding now resolves.
        bankState.setBank(deviceTypeKey, "layer-b")
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        assertEquals(listOf<RecordedCall>(RecordedCall.WriteLevel(hex1, "dimmer", 100u)), actions.calls.toList())
    }

    @Test
    fun `a Blackout binding toggles the global scaler via actions`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(binding(1, "btn-3", Binding(Does.Blackout))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 18, velocity = 127u))
        assertEquals(listOf<RecordedCall>(RecordedCall.ToggleBlackout), actions.calls.toList())
    }

    @Test
    fun `a Layer press switches the layer of the device the button is on`() {
        val bankState = ActiveBankState()
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(binding(1, "btn-4", Binding(Does.Layer("layer-a")))), bankState)
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 19, velocity = 127u))
        assertEquals("layer-a", bankState.bankFor(deviceTypeKey))
    }

    @Test
    fun `an encoder's turn writes its level the same as a fader's move`() {
        val actions = RecordingSurfaceActions()
        // enc-1 = CC 10 on X-Touch Compact Layer A.
        val router = buildRouter(actions, listOf(binding(1, "enc-1", Binding(Does.Level("dimmer"), hex1))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 80u))
        assertEquals(listOf<RecordedCall>(RecordedCall.WriteLevel(hex1, "dimmer", 80u)), actions.calls.toList())
    }

    // ─── An encoder's push is its own part (surface-setup plan D4) ─────

    @Test
    fun `an encoder's push with nothing bound on it writes nothing, whatever its turn drives`() {
        val actions = RecordingSurfaceActions()
        // enc-1 turns on CC 10 and pushes on note 0.
        val router = buildRouter(actions, listOf(binding(1, "enc-1", Binding(Does.Level("dimmer"), hex1))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 0, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 0, velocity = 0u))
        assertTrue(actions.calls.isEmpty(), "a push does not write the turn's attribute full")
    }

    @Test
    fun `an encoder's push dispatches the binding on its push part, held there, and its turn the main part's`() {
        val actions = RecordingSurfaceActions()
        val holds = SurfaceHolds()
        val router = buildRouter(
            actions,
            listOf(
                binding(1, "enc-1", Binding(Does.Level("dimmer"), hex1)),
                binding(2, "enc-1", Binding(Does.Flash("dimmer"), wash), part = ControlPart.PUSH),
            ),
            holds = holds,
        )

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 0, velocity = 127u))
        assertTrue(holds.isHeld(SurfaceDispatch.HoldKey(deviceTypeKey, "enc-1", ControlPart.PUSH)))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 40u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 0, velocity = 0u))

        assertEquals(
            listOf(
                RecordedCall.FlashPress(wash, "dimmer", 100),
                RecordedCall.WriteLevel(hex1, "dimmer", 40u),
                RecordedCall.FlashRelease(wash, "dimmer"),
            ),
            actions.calls.toList(),
        )
        assertFalse(holds.isHeld(SurfaceDispatch.HoldKey(deviceTypeKey, "enc-1", ControlPart.PUSH)))
    }

    @Test
    fun `a strip derives main parts only, so its encoder's push resolves nothing`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(binding(1, "strip-1", Binding(Does.Strip, wash))))

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 0, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 0, velocity = 0u))
        assertTrue(actions.calls.isEmpty())

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 40u))
        assertEquals(listOf<RecordedCall>(RecordedCall.WriteLevel(wash, "dimmer", 40u)), actions.calls.toList(), "the turn still derives")
    }

    // ─── Tempo ─────────────────────────────────────────────────────────

    @Test
    fun `a Tap binding taps the named master on press only`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(binding(1, "btn-3", Binding(Does.Tap, Subject.Record(RecordKind.MASTER, master)))))

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 18, velocity = 127u))
        // Release must be a no-op — a tap marks one beat, it does not bracket a duration.
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 18, velocity = 0u))

        assertEquals(listOf<RecordedCall>(RecordedCall.TapSpeedMaster(master)), actions.calls.toList())
    }

    @Test
    fun `a TempoScale binding halves or doubles its master on press only, master 1 with no subject`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(
            binding(1, "btn-3", Binding(Does.TempoScale(0.5), Subject.Record(RecordKind.MASTER, master))),
            binding(2, "btn-4", Binding(Does.TempoScale(2.0))),
        ))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 18, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 18, velocity = 0u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 19, velocity = 127u))
        assertEquals(
            listOf<RecordedCall>(RecordedCall.ScaleSpeedMaster(master, 0.5), RecordedCall.ScaleSpeedMaster(null, 2.0)),
            actions.calls.toList(),
        )
    }

    @Test
    fun `FxSpeed tells the action an encoder's turn from a fader's move`() {
        val actions = RecordingSurfaceActions()
        val fxSpeed = Binding(Does.FxSpeed, Subject.Selection)
        // fader-1 = CC 1, enc-1 = CC 10 on X-Touch Compact Layer A.
        val router = buildRouter(actions, listOf(binding(1, "fader-1", fxSpeed), binding(2, "enc-1", fxSpeed)))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 96u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 65u))
        assertEquals(
            listOf<RecordedCall>(RecordedCall.WriteFxSpeed(96u, encoder = false), RecordedCall.WriteFxSpeed(65u, encoder = true)),
            actions.calls.toList(),
        )
    }

    @Test
    fun `a Tempo binding passes its window through on encoder moves`() {
        val actions = RecordingSurfaceActions()
        // enc-1 = CC 10 on X-Touch Compact Layer A.
        val router = buildRouter(actions, listOf(binding(1, "enc-1", Binding(Does.Tempo(minBpm = 90.0, maxBpm = 150.0)))))

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
    fun `a grand master binding writes an encoder's level and toggles on a button`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(
            actions,
            listOf(
                binding(1, "enc-1", Binding(Does.GrandMaster())),
                binding(2, "btn-3", Binding(Does.GrandMaster())),
            ),
        )

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 64u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 18, velocity = 127u))

        assertEquals(
            listOf<RecordedCall>(RecordedCall.WriteGrandMaster(64u), RecordedCall.ToggleGrandMaster),
            actions.calls.toList(),
        )
    }

    @Test
    fun `a Fan binding passes the encoder's value and its axis to the fan, and ignores a press`() {
        val actions = RecordingSurfaceActions()
        val fan = Binding(Does.Fan(FanAxis.TILT), Subject.Selection)
        val router = buildRouter(actions, listOf(binding(1, "enc-1", fan), binding(2, "btn-3", fan)))

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 70u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 18, velocity = 127u))

        assertEquals(listOf<RecordedCall>(RecordedCall.WriteFan(FanAxis.TILT, 70u)), actions.calls.toList())
    }

    @Test
    fun `an FxAmount binding passes the fader's value and its stack to the stack's amount, and ignores a press`() {
        val actions = RecordingSurfaceActions()
        val stack4 = UUID.fromString("00000000-0000-0000-0000-000000000004")
        val amount = Binding(Does.FxAmount, Subject.Record(RecordKind.STACK, stack4))
        val router = buildRouter(actions, listOf(binding(1, "enc-1", amount), binding(2, "btn-3", amount)))

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 64u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 18, velocity = 127u))

        assertEquals(listOf<RecordedCall>(RecordedCall.WriteStackFxAmount(stack4, 64u)), actions.calls.toList())
    }

    @Test
    fun `a button press on a tempo window is ignored rather than jumping the tempo`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(binding(1, "btn-3", Binding(Does.Tempo()))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 18, velocity = 127u))
        assertTrue(actions.calls.isEmpty(), "a press carries no position, so there is no tempo to set")
    }

    @Test
    fun `touch event is forwarded to feedback hooks`() {
        val actions = RecordingSurfaceActions()
        val feedback = RecordingFeedbackHooks()
        val router = SurfaceInputRouter(
            deviceMatcher = DeviceMatcher(MidiDeviceRegistry(FakeMidiAccess(), pollIntervalMs = 60_000L, autoOpen = false)),
            controllerLookup = { null },
            bindingService = ControlSurfaceBindingService(FakeDatabase.instance).also {
                it.seedCacheForTest(projectId, emptyList())
            },
            bankState = ActiveBankState(),
            encoderBankState = EncoderBankState(),
            holds = SurfaceHolds(),
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
        val actions = RecordingSurfaceActions()
        val feedback = RecordingFeedbackHooks()
        feedback.acceptReturn = false  // always reject pickup
        val router = SurfaceInputRouter(
            deviceMatcher = DeviceMatcher(MidiDeviceRegistry(FakeMidiAccess(), pollIntervalMs = 60_000L, autoOpen = false)),
            controllerLookup = { null },
            bindingService = ControlSurfaceBindingService(FakeDatabase.instance).also {
                it.seedCacheForTest(projectId, listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), hex1))))
            },
            bankState = ActiveBankState(),
            encoderBankState = EncoderBankState(),
            holds = SurfaceHolds(),
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
        val actions = RecordingSurfaceActions()
        val router = buildRouter(
            actions,
            listOf(
                binding(
                    1, "fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-gone")),
                    health = AssignmentHealth.MissingFixture("hex-gone"),
                ),
            ),
        )
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `dead button binding is dropped without invoking actions`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(
            actions,
            listOf(
                binding(
                    1, "btn-1", Binding(Does.Go, Subject.Record(RecordKind.STACK, stack)),
                    health = AssignmentHealth.MissingStack(stackUuid = stack.toString()),
                ),
            ),
        )
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `NoteOn with velocity 0 is treated as release`() {
        val actions = RecordingSurfaceActions()
        val holds = SurfaceHolds()
        val router = buildRouter(actions, listOf(binding(1, "btn-2", Binding(Does.Flash("dimmer"), hex1))), holds = holds)

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 0u))
        assertEquals(2, actions.calls.size)
        assertEquals(RecordedCall.FlashRelease(hex1, "dimmer"), actions.calls.last())
        assertFalse(holds.isHeld(SurfaceDispatch.HoldKey(deviceTypeKey, "btn-2", ControlPart.MAIN)))
    }

    @Test
    fun `latency tracker records a sample on continuous dispatch`() {
        val actions = RecordingSurfaceActions()
        val tracker = MidiLatencyTracker()
        val router = buildRouter(
            actions,
            listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), hex1))),
            latencyTracker = tracker,
        )
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        assertEquals(1, tracker.bucket(MidiLatencyStage.INGRESS_CONTINUOUS).count)
        assertEquals(0, tracker.bucket(MidiLatencyStage.INGRESS_BUTTON).count)
    }

    @Test
    fun `latency tracker records button press and release into INGRESS_BUTTON`() {
        val actions = RecordingSurfaceActions()
        val tracker = MidiLatencyTracker()
        val router = buildRouter(actions, listOf(binding(1, "btn-2", Binding(Does.Flash("dimmer"), hex1))), latencyTracker = tracker)

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 17, velocity = 0u))
        assertEquals(2, tracker.bucket(MidiLatencyStage.INGRESS_BUTTON).count)
    }

    @Test
    fun `latency tracker is not invoked when no binding resolves`() {
        val actions = RecordingSurfaceActions()
        val tracker = MidiLatencyTracker()
        val router = buildRouter(actions, emptyList(), latencyTracker = tracker)
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        assertEquals(0, tracker.bucket(MidiLatencyStage.INGRESS_CONTINUOUS).count)
        assertEquals(0, tracker.bucket(MidiLatencyStage.INGRESS_BUTTON).count)
    }

    // ─── Selection-relative arms (midi-surface plan, session 1) ────────────

    @Test
    fun `a selection level fader writes the attribute and the value`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(binding(1, "fader-1", Binding(Does.Level("dimmer"), Subject.Selection))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        assertEquals(listOf<RecordedCall>(RecordedCall.WriteLevel(Subject.Selection, "dimmer", 100u)), actions.calls.toList())
    }

    @Test
    fun `a level on a button writes nothing, since a press carries no position`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(
            binding(1, "btn-1", Binding(Does.Level("dimmer"), Subject.Selection)),
            binding(2, "btn-2", Binding(Does.Level("rgbColour", ColourAxis.SATURATION), Subject.Selection)),
        ))
        for (note in 16..17) {
            router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = note, velocity = 127u))
            router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = note, velocity = 0u))
        }
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `select clear and locate buttons dispatch on press only`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(
            binding(1, "btn-1", Binding(Does.Select, wash, How.REPLACE)),
            binding(2, "btn-2", Binding(Does.SelectionStep(SelectionMove.CLEAR))),
            binding(3, "btn-3", Binding(Does.Locate, Subject.Selection)),
        ))
        for (note in 16..18) {
            router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = note, velocity = 127u))
            router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = note, velocity = 0u))
        }
        assertEquals(
            listOf(
                RecordedCall.SelectTarget(washTarget, replace = true),
                RecordedCall.ClearSelection,
                RecordedCall.Locate(),
            ),
            actions.calls.toList(),
        )
    }

    // ─── Strips, the encoder bank, and the select long press ───────────────

    @Test
    fun `a strip binding drives the fader, select and flash of its own controls`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(binding(1, "strip-1", Binding(Does.Strip, wash))))

        // fader-1 is CC 1; btn-25 (select) is note 40; btn-1 (flash) is note 16.
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 40, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))

        assertEquals(
            listOf(
                RecordedCall.WriteLevel(wash, "dimmer", 100u),
                RecordedCall.SelectTarget(washTarget, replace = false),
                RecordedCall.FlashPress(wash, "dimmer", 100),
            ),
            actions.calls.toList(),
        )
    }

    @Test
    fun `a strip encoder writes whatever the device's encoder bank names`() {
        val actions = RecordingSurfaceActions()
        val encoderBank = EncoderBankState()
        val router = buildRouter(
            actions,
            listOf(binding(1, "strip-1", Binding(Does.Strip, wash))),
            encoderBankState = encoderBank,
        )

        // enc-1 is CC 10.
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 64u))
        encoderBank.setAttribute(deviceTypeKey, "colour")
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 64u))

        assertEquals(
            listOf(
                RecordedCall.WriteLevel(wash, "dimmer", 64u),
                RecordedCall.WriteLevel(wash, "colour", 64u),
            ),
            actions.calls.toList(),
        )
    }

    @Test
    fun `an EncoderPage press moves the page of the device the button is on`() {
        val actions = RecordingSurfaceActions()
        val encoderBank = EncoderBankState()
        val router = buildRouter(
            actions,
            listOf(binding(1, "btn-1", Binding(Does.EncoderPage("colour")))),
            encoderBankState = encoderBank,
        )

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))

        assertEquals(EncoderBankSelection("colour"), encoderBank.selectionFor(deviceTypeKey))
        // It is desk state, not a show action — nothing reaches SurfaceActions.
        assertTrue(actions.calls.isEmpty())
    }

    // ─── Colour axes ────────────────────────────────────────────────────

    @Test
    fun `a continuous move carries the binding's colour axis to every attribute write`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(
            actions,
            listOf(
                binding(1, "fader-1", Binding(Does.Level("rgbColour", ColourAxis.BRIGHTNESS), hex1)),
                binding(2, "fader-2", Binding(Does.Level("rgbColour", ColourAxis.SATURATION), wash)),
                binding(3, "fader-3", Binding(Does.Level("rgbColour", ColourAxis.HUE_FINE), Subject.Selection)),
            ),
        )
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 2, value = 64u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 3, value = 32u))
        assertEquals(
            listOf<RecordedCall>(
                RecordedCall.WriteLevel(hex1, "rgbColour", 100u, ColourAxis.BRIGHTNESS),
                RecordedCall.WriteLevel(wash, "rgbColour", 64u, ColourAxis.SATURATION),
                RecordedCall.WriteLevel(Subject.Selection, "rgbColour", 32u, ColourAxis.HUE_FINE),
            ),
            actions.calls.toList(),
        )
    }

    @Test
    fun `an EncoderPage press sets the attribute and the axis, and a strip encoder follows both`() {
        val actions = RecordingSurfaceActions()
        val encoderBank = EncoderBankState()
        val router = buildRouter(
            actions,
            listOf(
                binding(1, "strip-1", Binding(Does.Strip, wash)),
                binding(2, "btn-2", Binding(Does.EncoderPage("rgbColour", ColourAxis.SATURATION))),
            ),
            encoderBankState = encoderBank,
        )

        // btn-2 is note 17; enc-1 is CC 10.
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        assertEquals(EncoderBankSelection("rgbColour", ColourAxis.SATURATION), encoderBank.selectionFor(deviceTypeKey))

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 40u))
        assertEquals(
            listOf<RecordedCall>(RecordedCall.WriteLevel(wash, "rgbColour", 40u, ColourAxis.SATURATION)),
            actions.calls.toList(),
            "the strip encoder writes the bank's axis",
        )

        // An axis-only change of bank is a change the encoder follows too.
        encoderBank.set(deviceTypeKey, EncoderBankSelection("rgbColour", ColourAxis.HUE_FINE))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 10, value = 64u))
        assertEquals(RecordedCall.WriteLevel(wash, "rgbColour", 64u, ColourAxis.HUE_FINE), actions.calls.last())
    }

    @Test
    fun `a strip binding off a strip slot does nothing`() {
        val actions = RecordingSurfaceActions()
        // btn-2 belongs to no strip, so the Strip binding sits on it underived.
        val router = buildRouter(actions, listOf(binding(1, "btn-2", Binding(Does.Strip, wash))))

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 2, value = 50u))

        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `a short select press toggles and never replaces`() = runTest {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(
            actions,
            listOf(binding(1, "btn-1", Binding(Does.Select, wash))),
            scope = this,
            selectHoldMs = 500L,
        )

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        advanceTimeBy(100L)
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 16, velocity = 0u))
        advanceTimeBy(5_000L)

        assertEquals(
            listOf<RecordedCall>(RecordedCall.SelectTarget(washTarget, replace = false)),
            actions.calls.toList(),
        )
        router.stop()
    }

    @Test
    fun `holding a select button past the threshold also replaces the selection`() = runTest {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(
            actions,
            listOf(binding(1, "btn-1", Binding(Does.Select, wash))),
            scope = this,
            selectHoldMs = 500L,
        )

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        advanceTimeBy(600L)
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 16, velocity = 0u))
        advanceTimeBy(5_000L)

        assertEquals(
            listOf(
                RecordedCall.SelectTarget(washTarget, replace = false),
                RecordedCall.SelectTarget(washTarget, replace = true),
            ),
            actions.calls.toList(),
        )
        router.stop()
    }

    @Test
    fun `two devices of one type each keep their own select hold`() = runTest {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(
            actions,
            listOf(binding(1, "btn-1", Binding(Does.Select, wash))),
            scope = this,
            selectHoldMs = 500L,
        )

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u), displayKey = "x-touch-left")
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u), displayKey = "x-touch-right")
        advanceTimeBy(100L)
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 16, velocity = 0u), displayKey = "x-touch-left")
        advanceTimeBy(5_000L)

        assertEquals(
            listOf(
                RecordedCall.SelectTarget(washTarget, replace = false),
                RecordedCall.SelectTarget(washTarget, replace = false),
                RecordedCall.SelectTarget(washTarget, replace = true),
            ),
            actions.calls.toList(),
            "the left release cancels only the left hold; the right one, still down, replaces",
        )
        router.stop()
    }

    @Test
    fun `a REPLACE select binding acts on press and arms no hold`() = runTest {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(
            actions,
            listOf(binding(1, "btn-1", Binding(Does.Select, wash, How.REPLACE))),
            scope = this,
            selectHoldMs = 500L,
        )

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        advanceTimeBy(5_000L)

        assertEquals(
            listOf<RecordedCall>(RecordedCall.SelectTarget(washTarget, replace = true)),
            actions.calls.toList(),
        )
        router.stop()
    }

    @Test
    fun `a release cancels the pending replace even when the binding went dead meanwhile`() = runTest {
        val actions = RecordingSurfaceActions()
        val bindingService = ControlSurfaceBindingService(FakeDatabase.instance)
        bindingService.seedCacheForTest(projectId, listOf(binding(1, "btn-1", Binding(Does.Select, wash))))
        val router = SurfaceInputRouter(
            deviceMatcher = DeviceMatcher(MidiDeviceRegistry(FakeMidiAccess(), pollIntervalMs = 60_000L, autoOpen = false)),
            controllerLookup = { null },
            bindingService = bindingService,
            bankState = ActiveBankState(),
            encoderBankState = EncoderBankState(),
            holds = SurfaceHolds(),
            projectIdProvider = { projectId },
            actions = actions,
            selectHoldMs = 500L,
        )
        router.start(this)

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        // The binding goes dead under the operator's finger.
        bindingService.seedCacheForTest(
            projectId,
            listOf(binding(1, "btn-1", Binding(Does.Select, wash), health = AssignmentHealth.MissingGroup("front-wash"))),
        )
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 16, velocity = 0u))
        advanceTimeBy(5_000L)

        assertEquals(
            listOf<RecordedCall>(RecordedCall.SelectTarget(washTarget, replace = false)),
            actions.calls.toList(),
        )
        router.stop()
    }

    @Test
    fun `cue and stack presses carry the binding's uuid through to the actions`() {
        val cue = UUID.fromString("7d444840-9dc0-11d1-b245-5ffdce74fad2")
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(
            binding(1, "btn-1", Binding(Does.Cue, Subject.Record(RecordKind.CUE, cue))),
            binding(2, "btn-2", Binding(Does.Go, Subject.Record(RecordKind.STACK, stack))),
        ))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        assertEquals(
            listOf(RecordedCall.FireCue(cue), RecordedCall.CueStackGo(stack)),
            actions.calls.toList(),
        )
    }

    // ─── Records on buttons (midi-surface plan D6) ─────────────────────
    //
    // The router's job for these is a pure hand-off — every rule about *what* a press does
    // lives in `DefaultSurfaceActions` and `BuskPressService`, which is the point: a hardware press
    // and a screen press of one pad go through one implementation. What is worth pinning here is
    // that each function reaches its own action with its own uuid, since they are structurally
    // identical and a copy-paste slip between them would be invisible.

    @Test
    fun `a record button dispatches to its own action with its own uuid`() {
        val lookUuid = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val paletteUuid = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val padUuid = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(
            binding(1, "btn-1", Binding(Does.Look, Subject.Record(RecordKind.LOOK, lookUuid))),
            binding(2, "btn-2", Binding(Does.Palette(paletteUuid), Subject.Selection)),
            binding(3, "btn-3", Binding(Does.Pad, Subject.Record(RecordKind.PAD, padUuid))),
        ))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 18, velocity = 127u))
        assertEquals(
            listOf(
                RecordedCall.ApplyLook(lookUuid),
                RecordedCall.PressPalette(paletteUuid),
                RecordedCall.PressPad(padUuid),
            ),
            actions.calls,
        )
    }

    @Test
    fun `busk page next and prev step in opposite directions`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(
            binding(1, "btn-1", Binding(Does.BuskPage(1))),
            binding(2, "btn-2", Binding(Does.BuskPage(-1))),
        ))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        assertEquals(listOf<RecordedCall>(RecordedCall.BuskPageStep(1), RecordedCall.BuskPageStep(-1)), actions.calls)
    }

    @Test
    fun `a dead record binding never reaches the actions`() {
        val lookUuid = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(
            // A Look deleted since it was bound: the press must be dropped rather than reaching a
            // `toggle` that has nothing to put on.
            binding(
                1, "btn-1", Binding(Does.Look, Subject.Record(RecordKind.LOOK, lookUuid)),
                health = AssignmentHealth.MissingLook(lookUuid.toString()),
            ),
        ))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `a record binding on a fader is ignored rather than dispatched`() {
        // The router half of `FU-MIDI-BIND-CONTROL-KIND`: `refuseWrongKind` stops such a row being
        // written, but an older row or an imported archive can still hold one, and continuous
        // dispatch must drop it rather than find some arm for it.
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(
            binding(1, "fader-1", Binding(Does.Palette(UUID.fromString("22222222-2222-4222-8222-222222222222")), Subject.Selection)),
        ))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        assertTrue(actions.calls.isEmpty())
    }

    // ─── The hand (multi-screen plan §3.5) ─────────────────────────────

    @Test
    fun `the hand's three buttons dispatch to their own actions with their own uuids`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(
            binding(1, "btn-1", Binding(Does.PickUp, Subject.Record(RecordKind.PAD, padUuid))),
            binding(2, "btn-2", Binding(Does.Place, Subject.Record(RecordKind.BANK, bankUuid))),
            binding(3, "btn-3", Binding(Does.Drop)),
        ))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 18, velocity = 127u))
        assertEquals(
            listOf(
                RecordedCall.PickUpPad(padUuid),
                RecordedCall.HandPlaceInBank(bankUuid),
                RecordedCall.HandDrop,
            ),
            actions.calls,
        )
    }

    @Test
    fun `a dead hand binding never reaches the actions`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(
            binding(
                1, "btn-1", Binding(Does.PickUp, Subject.Record(RecordKind.PAD, padUuid)),
                health = AssignmentHealth.MissingPad(padUuid.toString()),
            ),
            binding(
                2, "btn-2", Binding(Does.Place, Subject.Record(RecordKind.BANK, bankUuid)),
                health = AssignmentHealth.MissingBank(bankUuid.toString()),
            ),
        ))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u))
        assertTrue(actions.calls.isEmpty())
    }

    // ─── History (undo plan §4.8) ──────────────────────────────────────

    @Test
    fun `undo, redo and programmer undo act as the surface's own source`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(
            binding(1, "btn-1", Binding(Does.Undo(UndoKind.UNDO))),
            binding(2, "btn-2", Binding(Does.Undo(UndoKind.REDO))),
            binding(3, "btn-3", Binding(Does.Undo(UndoKind.PROGRAMMER))),
        ))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u), displayKey = "x-touch-left")
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 17, velocity = 127u), displayKey = "x-touch-left")
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 18, velocity = 127u), displayKey = "x-touch-left")
        assertEquals(
            listOf<RecordedCall>(
                RecordedCall.HistoryMove("surface:x-touch-left", undo = true, programmerOnly = false),
                RecordedCall.HistoryMove("surface:x-touch-left", undo = false, programmerOnly = false),
                RecordedCall.HistoryMove("surface:x-touch-left", undo = true, programmerOnly = true),
            ),
            actions.calls,
        )
    }

    @Test
    fun `the history functions are buttons a fader never dispatches`() {
        for (kind in UndoKind.entries) {
            val does = Does.Undo(kind)
            assertEquals(setOf(ControlKind.BUTTON), does.spec.inputs, "$does")
            assertTrue(fader.inputFor(ControlPart.MAIN) !in does.spec.inputs, "$does")
        }
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(binding(1, "fader-1", Binding(Does.Undo()))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        assertTrue(actions.calls.isEmpty())
    }

    /**
     * The three take only a button, so a fader can never dispatch one — and **the server is what
     * says so**. `refuseWrongKind` stops the row being written (`ControlSurfaceBindingBatchTest`);
     * this pins the spec it reads, and the router's own drop for a row that predates it. The client
     * mirror in `lib/surfaceDrop.ts` only dims the drop target; it is not the guard.
     */
    @Test
    fun `the hand's three functions are buttons, and a fader neither takes nor dispatches one`() {
        val bindings = listOf(
            Binding(Does.PickUp, Subject.Record(RecordKind.PAD, padUuid)),
            Binding(Does.Place, Subject.Record(RecordKind.BANK, bankUuid)),
            Binding(Does.Drop),
        )
        for (bound in bindings) {
            assertEquals(setOf(ControlKind.BUTTON), bound.does.spec.inputs, "$bound")
            assertTrue(
                fader.inputFor(ControlPart.MAIN) !in bound.does.spec.inputs,
                "$bound is what refuseWrongKind refuses on '${fader.controlId}'",
            )
        }

        // And the router half: a row written before the rule existed, or imported from an archive,
        // is dropped by continuous dispatch rather than finding some arm for it.
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(binding(1, "fader-1", bindings.first())))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 1, value = 100u))
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `an Unknown binding is dead and never reaches the actions`() {
        val actions = RecordingSurfaceActions()
        val router = buildRouter(actions, listOf(
            binding(
                1, "btn-1", Binding(Does.Unknown("fromTheFuture", "{}")),
                health = AssignmentHealth.UnknownTarget("fromTheFuture"),
            ),
        ))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u))
        assertTrue(actions.calls.isEmpty())
    }
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
