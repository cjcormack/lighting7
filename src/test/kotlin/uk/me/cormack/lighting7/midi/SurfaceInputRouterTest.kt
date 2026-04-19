package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
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
        flashTracker: FlashStateTracker = FlashStateTracker(),
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
            flashTracker = flashTracker,
            projectIdProvider = { projectId },
            actions = actions,
        )
    }

    private fun binding(
        id: Int,
        controlId: String,
        target: BindingTarget,
        bank: String? = null,
    ) = ControlSurfaceBindingService.ResolvedBinding(
        id = id,
        projectId = projectId,
        deviceTypeKey = deviceTypeKey,
        controlId = controlId,
        bank = bank,
        target = target,
        takeoverPolicy = BindingTakeoverPolicy.IMMEDIATE,
        sortOrder = 0,
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
        // btn-1 = note 8 on X-Touch Compact Standard.
        val router = buildRouter(actions, listOf(binding(1, "btn-1", BindingTarget.CueStackGo(stackId = 7))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 8, velocity = 127u))
        assertEquals(listOf<RecordedCall>(RecordedCall.CueStackGo(7)), actions.calls.toList())
    }

    @Test
    fun `button release on non-Flash is a no-op`() {
        val actions = RecordingActions()
        val router = buildRouter(actions, listOf(binding(1, "btn-1", BindingTarget.CueStackGo(7))))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 8, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 8, velocity = 0u))
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

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 9, velocity = 127u))
        assertTrue(flashTracker.isActive(1))
        assertEquals(
            listOf<RecordedCall>(RecordedCall.FlashFixturePress("hex-1", "dimmer", 255u)),
            actions.calls.toList(),
        )

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 9, velocity = 0u))
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

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 9, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 9, velocity = 127u))
        assertEquals(1, actions.calls.size)
    }

    @Test
    fun `bank button press updates ActiveBankState without binding resolution`() {
        val bankState = ActiveBankState()
        val actions = RecordingActions()
        val router = buildRouter(actions, emptyList(), bankState)

        // X-Touch layer-a button = note 84.
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 84, velocity = 127u))
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
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 10, velocity = 127u))
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
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 11, velocity = 127u))
        assertEquals("layer-a", bankState.bankFor(deviceTypeKey))
    }

    @Test
    fun `encoder CC dispatches same as fader for FixtureProperty`() {
        val actions = RecordingActions()
        // enc-1 = CC 16 on X-Touch.
        val router = buildRouter(
            actions,
            listOf(binding(1, "enc-1", BindingTarget.FixtureProperty("hex-1", "dimmer"))),
        )
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.ControlChange(0, cc = 16, value = 80u))
        assertEquals(listOf<RecordedCall>(RecordedCall.WriteFixture("hex-1", "dimmer", 80u)), actions.calls.toList())
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
            flashTracker = FlashStateTracker(),
            projectIdProvider = { projectId },
            actions = actions,
            feedbackHooks = feedback,
        )
        // fader-1 touch note is 101 on X-Touch Standard.
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 101, velocity = 127u), displayKey = "dev-a")
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOff(0, note = 101, velocity = 0u), displayKey = "dev-a")
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
    fun `NoteOn with velocity 0 is treated as release`() {
        val actions = RecordingActions()
        val flashTracker = FlashStateTracker()
        val flash = BindingTarget.Flash(BindingTarget.FixtureProperty("hex-1", "dimmer"), max = 255)
        val router = buildRouter(actions, listOf(binding(1, "btn-2", flash)), flashTracker = flashTracker)

        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 9, velocity = 127u))
        router.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 9, velocity = 0u))
        assertEquals(2, actions.calls.size)
        assertEquals(RecordedCall.FlashFixtureRelease("hex-1", "dimmer"), actions.calls.last())
        assertFalse(flashTracker.isActive(1))
    }
}

/** Recording fake of [SurfaceActions] for tests. Every call appends to [calls]. */
private class RecordingActions : SurfaceActions {
    val calls = mutableListOf<RecordedCall>()
    override fun writeFixtureProperty(fixtureKey: String, propertyName: String, midiValue7Bit: UByte) {
        calls += RecordedCall.WriteFixture(fixtureKey, propertyName, midiValue7Bit)
    }
    override fun writeGroupProperty(groupName: String, propertyName: String, midiValue7Bit: UByte) {
        calls += RecordedCall.WriteGroup(groupName, propertyName, midiValue7Bit)
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
    override fun cueStackGo(stackId: Int) { calls += RecordedCall.CueStackGo(stackId) }
    override fun cueStackBack(stackId: Int) { calls += RecordedCall.CueStackBack(stackId) }
    override fun cueStackPause(stackId: Int) { calls += RecordedCall.CueStackPause(stackId) }
    override fun fireCue(cueId: Int) { calls += RecordedCall.FireCue(cueId) }
    override fun toggleBlackout(): Boolean { calls += RecordedCall.ToggleBlackout; return true }
    override fun toggleGrandMaster(): Boolean { calls += RecordedCall.ToggleGrandMaster; return true }
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
    data class WriteFixture(val fixtureKey: String, val prop: String, val value: UByte) : RecordedCall()
    data class WriteGroup(val groupName: String, val prop: String, val value: UByte) : RecordedCall()
    data class FlashFixturePress(val fixtureKey: String, val prop: String, val max: UByte) : RecordedCall()
    data class FlashGroupPress(val groupName: String, val prop: String, val max: UByte) : RecordedCall()
    data class FlashFixtureRelease(val fixtureKey: String, val prop: String) : RecordedCall()
    data class FlashGroupRelease(val groupName: String, val prop: String) : RecordedCall()
    data class CueStackGo(val stackId: Int) : RecordedCall()
    data class CueStackBack(val stackId: Int) : RecordedCall()
    data class CueStackPause(val stackId: Int) : RecordedCall()
    data class FireCue(val cueId: Int) : RecordedCall()
    data object ToggleBlackout : RecordedCall()
    data object ToggleGrandMaster : RecordedCall()
}
