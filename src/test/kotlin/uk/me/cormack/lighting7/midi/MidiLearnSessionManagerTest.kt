package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MidiLearnSessionManagerTest {

    /**
     * Manual clock — lets us drive [MidiLearnSessionManager.expireDueSessions] without
     * actually sleeping.
     */
    private class ManualClock(private var epochMs: Long = 1_700_000_000_000L) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(epochMs)
        override fun millis(): Long = epochMs
        fun advance(millis: Long) { epochMs += millis }
    }

    private fun newManager(clock: Clock = ManualClock()): MidiLearnSessionManager {
        val registry = MidiDeviceRegistry(FakeMidiAccess(), autoOpen = false)
        val matcher = DeviceMatcher(registry)
        return MidiLearnSessionManager(
            deviceMatcher = matcher,
            controllerLookup = { null },
            clock = clock,
        )
    }

    @Test
    fun `begin emits a Started event with a Pending session and a deadline`() {
        val clock = ManualClock()
        val mgr = newManager(clock)
        val session = mgr.begin(projectId = 42)

        assertEquals(MidiLearnSessionManager.SessionState.Pending, session.state)
        assertEquals(42, session.projectId)
        assertTrue(session.deadlineMs > clock.millis())
        assertEquals(session, mgr.get(session.sessionId))
    }

    @Test
    fun `inbound fader move captures the control and transitions to Captured`() = runBlocking {
        val mgr = newManager()
        val session = mgr.begin(projectId = 1, filter = MidiLearnSessionManager.LearnFilter(
            deviceTypeKey = "x-touch-compact-standard",
        ))

        mgr.offerInput(
            "x-touch-compact-standard",
            MidiInputEvent.ControlChange(channel = 0, cc = 1, value = 64u),
        )

        val after = mgr.get(session.sessionId)
        assertNotNull(after)
        assertEquals(MidiLearnSessionManager.SessionState.Captured, after.state)
        val captured = after.captured
        assertNotNull(captured)
        assertEquals("fader-1", captured.controlId)
        assertEquals("x-touch-compact-standard", captured.deviceTypeKey)
    }

    @Test
    fun `fader at rest zero does not capture`() = runBlocking {
        val mgr = newManager()
        val session = mgr.begin(projectId = 1, filter = MidiLearnSessionManager.LearnFilter(
            deviceTypeKey = "x-touch-compact-standard",
        ))

        mgr.offerInput(
            "x-touch-compact-standard",
            MidiInputEvent.ControlChange(channel = 0, cc = 1, value = 0u),
        )

        val after = mgr.get(session.sessionId)
        assertNotNull(after)
        assertEquals(MidiLearnSessionManager.SessionState.Pending, after.state)
    }

    @Test
    fun `button press captures its controlId`() = runBlocking {
        val mgr = newManager()
        val session = mgr.begin(projectId = 1, filter = MidiLearnSessionManager.LearnFilter(
            deviceTypeKey = "x-touch-compact-standard",
        ))

        // Button 1 is note 8 per XTouchCompactStandard profile.
        mgr.offerInput(
            "x-touch-compact-standard",
            MidiInputEvent.NoteOn(channel = 0, note = 8, velocity = 127u),
        )

        val after = mgr.get(session.sessionId)
        assertNotNull(after?.captured)
        assertEquals("btn-1", after.captured.controlId)
    }

    @Test
    fun `bank buttons are not captureable`() = runBlocking {
        val mgr = newManager()
        mgr.begin(projectId = 1, filter = MidiLearnSessionManager.LearnFilter(
            deviceTypeKey = "x-touch-compact-standard",
        ))

        // Layer A bank button = note 84
        mgr.offerInput(
            "x-touch-compact-standard",
            MidiInputEvent.NoteOn(channel = 0, note = 84, velocity = 127u),
        )

        val pending = mgr.sessions.value.values.single()
        assertEquals(MidiLearnSessionManager.SessionState.Pending, pending.state)
    }

    @Test
    fun `filter with null deviceTypeKey captures from any attached device`() = runBlocking {
        val mgr = newManager()
        val session = mgr.begin(projectId = 1, filter = MidiLearnSessionManager.LearnFilter())
        mgr.offerInput(
            "x-touch-compact-standard",
            MidiInputEvent.ControlChange(channel = 0, cc = 2, value = 100u),
        )

        val after = mgr.get(session.sessionId)
        assertEquals(MidiLearnSessionManager.SessionState.Captured, after?.state)
        assertEquals("fader-2", after?.captured?.controlId)
    }

    @Test
    fun `filter rejects events from non-matching deviceTypeKey`() = runBlocking {
        val mgr = newManager()
        val session = mgr.begin(projectId = 1, filter = MidiLearnSessionManager.LearnFilter(
            deviceTypeKey = "some-other-device",
        ))

        mgr.offerInput(
            "x-touch-compact-standard",
            MidiInputEvent.ControlChange(channel = 0, cc = 1, value = 64u),
        )

        assertEquals(MidiLearnSessionManager.SessionState.Pending, mgr.get(session.sessionId)?.state)
    }

    @Test
    fun `cancel transitions Pending session to Cancelled`() {
        val mgr = newManager()
        val session = mgr.begin(projectId = 1)
        val cancelled = mgr.cancel(session.sessionId)
        assertNotNull(cancelled)
        assertEquals(MidiLearnSessionManager.SessionState.Cancelled, cancelled.state)
    }

    @Test
    fun `commit only succeeds once a session has been captured`() = runBlocking {
        val mgr = newManager()
        val session = mgr.begin(projectId = 1, filter = MidiLearnSessionManager.LearnFilter(
            deviceTypeKey = "x-touch-compact-standard",
        ))
        assertNull(mgr.commit(session.sessionId)) // still Pending

        mgr.offerInput(
            "x-touch-compact-standard",
            MidiInputEvent.ControlChange(channel = 0, cc = 1, value = 64u),
        )

        val committed = mgr.commit(session.sessionId)
        assertNotNull(committed)
        assertEquals(MidiLearnSessionManager.SessionState.Committed, committed.state)
    }

    @Test
    fun `timed-out sessions can be observed via expireDueSessions`() {
        val clock = ManualClock()
        val mgr = newManager(clock)
        val session = mgr.begin(projectId = 1)
        clock.advance(MidiLearnSessionManager.DEFAULT_TIMEOUT_MS + 1)
        mgr.expireDueSessions(clock.millis())
        val after = mgr.get(session.sessionId)
        assertNotNull(after)
        assertEquals(MidiLearnSessionManager.SessionState.TimedOut, after.state)
    }

}
