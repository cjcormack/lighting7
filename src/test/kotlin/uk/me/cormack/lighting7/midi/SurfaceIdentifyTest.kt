package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.testsupport.RecordedCall
import uk.me.cormack.lighting7.testsupport.RecordingSurfaceActions
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Identify mode (surface-setup plan D14): the touch a window waits for is captured and never dispatched. */
class SurfaceIdentifyTest {
    private val projectId = 42
    private val deviceTypeKey = "x-touch-compact-standard"
    private val stack = UUID.fromString("11111111-2222-3333-4444-555555555555")

    private class ManualClock(var ms: Long = 1_700_000_000_000L) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(ms)
        override fun millis(): Long = ms
    }

    private fun router(actions: RecordingSurfaceActions, identify: SurfaceIdentify): SurfaceInputRouter {
        val service = ControlSurfaceBindingService(FakeDatabase.instance)
        service.seedCacheForTest(
            projectId,
            listOf(
                ControlSurfaceBindingService.ResolvedBinding(
                    id = 1, projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "btn-1", bank = null,
                    binding = Binding(Does.Go, Subject.Record(RecordKind.STACK, stack)),
                    takeoverPolicy = BindingTakeoverPolicy.IMMEDIATE, sortOrder = 0,
                ),
            ),
        )
        return SurfaceInputRouter(
            deviceMatcher = DeviceMatcher(MidiDeviceRegistry(FakeMidiAccess(), pollIntervalMs = 60_000L, autoOpen = false)),
            controllerLookup = { null },
            bindingService = service,
            bankState = ActiveBankState(),
            encoderBankState = EncoderBankState(),
            holds = SurfaceHolds(),
            projectIdProvider = { projectId },
            actions = actions,
            identify = identify,
        )
    }

    private val goPress = MidiInputEvent.NoteOn(0, note = 16, velocity = 127u)
    private val goRelease = MidiInputEvent.NoteOff(0, note = 16, velocity = 0u)

    @Test
    fun `a bound GO touched while a window waits is captured and does not fire`() = runBlocking {
        val clock = ManualClock()
        val identify = SurfaceIdentify(clock)
        val actions = RecordingSurfaceActions()
        val router = router(actions, identify)
        val session = identify.begin()

        val captured = launch {
            val event = withTimeout(2_000) { identify.events.first() }
            val capture = assertIs<SurfaceIdentify.Event.Captured>(event).capture
            assertEquals(session.sessionId, event.sessionId)
            assertEquals("btn-1", capture.controlId)
            assertEquals(ControlPart.MAIN, capture.part)
            assertEquals(deviceTypeKey, capture.displayKey)
        }
        kotlinx.coroutines.yield()
        router.offerInputForTest(deviceTypeKey, goPress)
        router.offerInputForTest(deviceTypeKey, goRelease)
        captured.join()

        assertTrue(actions.calls.isEmpty(), "nothing dispatched: ${actions.calls}")
        assertFalse(identify.isWaiting(session.sessionId), "one touch per wait")

        // Once the gesture is over, the next press fires as normal.
        clock.ms += SurfaceIdentify.QUIET_MS + 1
        router.offerInputForTest(deviceTypeKey, goPress)
        assertEquals(listOf<RecordedCall>(RecordedCall.CueStackGo(stack)), actions.calls.toList())
    }

    @Test
    fun `an encoder's push is captured as its push part`() {
        val identify = SurfaceIdentify(ManualClock())
        identify.begin()
        val profile = ControlSurfaceRegistry.typeFor(deviceTypeKey)!!
        val encoder = profile.controls.filterIsInstance<EncoderDescriptor>().first { it.pushNote != null }
        val input = assertIs<ControlInputIndex.Input.Press>(profile.inputs.match(MidiInputEvent.NoteOn(encoder.channel, encoder.pushNote!!, 127u)))
        assertTrue(identify.offer("x-touch", deviceTypeKey, input))
    }

    @Test
    fun `a wait not answered in thirty seconds ends, and the press after it fires`() {
        val clock = ManualClock()
        val identify = SurfaceIdentify(clock)
        val actions = RecordingSurfaceActions()
        val router = router(actions, identify)
        val session = identify.begin()

        clock.ms += SurfaceIdentify.TIMEOUT_MS
        identify.expire(clock.millis())
        assertFalse(identify.isWaiting(session.sessionId))
        router.offerInputForTest(deviceTypeKey, goPress)
        assertEquals(listOf<RecordedCall>(RecordedCall.CueStackGo(stack)), actions.calls.toList())
    }

    @Test
    fun `with two windows waiting, the touch goes to the one that asked last, and the other still waits`() {
        val clock = ManualClock()
        val identify = SurfaceIdentify(clock)
        val a = identify.begin()
        clock.ms += 5
        val b = identify.begin()
        val press = ControlSurfaceRegistry.typeFor(deviceTypeKey)!!.inputs.match(goPress)!!
        assertTrue(identify.offer("x-touch", deviceTypeKey, press))
        assertFalse(identify.isWaiting(b.sessionId))
        assertTrue(identify.isWaiting(a.sessionId))
    }

    @Test
    fun `a wait for one device type ignores another's`() {
        val identify = SurfaceIdentify(ManualClock())
        identify.begin(deviceTypeKey = "some-other-surface")
        val press = ControlSurfaceRegistry.typeFor(deviceTypeKey)!!.inputs.match(goPress)!!
        assertFalse(identify.offer("x-touch", deviceTypeKey, press))
    }

    private fun input(event: MidiInputEvent) = ControlSurfaceRegistry.typeFor(deviceTypeKey)!!.inputs.match(event)!!

    @Test
    fun `a fader's touch is never swallowed, so its touch-up after a capture still reaches the router`() {
        val identify = SurfaceIdentify(ManualClock())
        identify.begin()
        assertFalse(identify.offer("x-touch", deviceTypeKey, input(MidiInputEvent.ControlChange(0, cc = 101, value = 127u))))
        assertTrue(identify.offer("x-touch", deviceTypeKey, input(MidiInputEvent.ControlChange(0, cc = 1, value = 64u))))
        assertTrue(identify.offer("x-touch", deviceTypeKey, input(MidiInputEvent.ControlChange(0, cc = 1, value = 70u))), "the gesture is swallowed")
        assertFalse(identify.offer("x-touch", deviceTypeKey, input(MidiInputEvent.ControlChange(0, cc = 101, value = 0u))), "the touch-up is not")
    }

    @Test
    fun `a fader at 0 is neither captured nor swallowed`() {
        val identify = SurfaceIdentify(ManualClock())
        val session = identify.begin()
        assertFalse(identify.offer("x-touch", deviceTypeKey, input(MidiInputEvent.ControlChange(0, cc = 1, value = 0u))))
        assertTrue(identify.isWaiting(session.sessionId), "a fader at rest is not a touch")
        assertTrue(identify.offer("x-touch", deviceTypeKey, input(MidiInputEvent.ControlChange(0, cc = 1, value = 40u))))
        assertFalse(identify.offer("x-touch", deviceTypeKey, input(MidiInputEvent.ControlChange(0, cc = 1, value = 0u))), "pulled down to 0, it dispatches")
    }
}
