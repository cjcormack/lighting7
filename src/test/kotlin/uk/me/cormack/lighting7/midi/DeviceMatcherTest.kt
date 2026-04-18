package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import uk.me.cormack.lighting7.midi.devices.XTouchCompactStandard
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeviceMatcherTest {

    private val xTouchHandle = MidiDeviceHandle(
        displayKey = "x-touch-compact",
        displayName = "X-Touch Compact",
        inputPort = MidiDevicePort("in-1", "X-Touch Compact", "Behringer", PortDirection.INPUT),
        outputPort = MidiDevicePort("out-1", "X-Touch Compact", "Behringer", PortDirection.OUTPUT),
    )

    private val unknownHandle = MidiDeviceHandle(
        displayKey = "unknown-device",
        displayName = "Generic MIDI Thing",
        inputPort = MidiDevicePort("in-2", "Generic MIDI Thing", "Acme", PortDirection.INPUT),
        outputPort = null,
    )

    private fun fakeMatcher(): Pair<DeviceMatcher, StubRegistry> {
        val stub = StubRegistry()
        val matcher = DeviceMatcher(
            registry = stub.registry,
            types = { handle -> ControlSurfaceRegistry.matchFor(handle) },
            instantiate = { typeKey -> ControlSurfaceRegistry.instantiate(typeKey) },
        )
        return matcher to stub
    }

    private fun withEventCollector(
        matcher: DeviceMatcher,
        block: suspend (MutableList<DeviceMatcher.SurfaceEvent>) -> Unit,
    ) {
        val collected = CopyOnWriteArrayList<DeviceMatcher.SurfaceEvent>()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val job: Job = scope.launch { matcher.events.collect { collected.add(it) } }
        try {
            runBlocking { block(collected) }
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `matching connect emits DeviceAttached and updates attached state`() {
        val (matcher, _) = fakeMatcher()
        withEventCollector(matcher) { collected ->
            matcher.handle(MidiDeviceRegistry.DeviceEvent.Connected(xTouchHandle))

            assertEquals(1, collected.size)
            val event = collected.single()
            assertIs<DeviceMatcher.SurfaceEvent.DeviceAttached>(event)
            assertEquals("x-touch-compact-standard", event.typeKey)
            assertIs<XTouchCompactStandard>(event.instance)

            val attached = matcher.attached.value["x-touch-compact"]
            assertNotNull(attached)
            assertEquals("x-touch-compact-standard", attached.typeKey)
        }
    }

    @Test
    fun `unmatched connect emits UnmatchedDeviceConnected and does not attach`() {
        val (matcher, _) = fakeMatcher()
        withEventCollector(matcher) { collected ->
            matcher.handle(MidiDeviceRegistry.DeviceEvent.Connected(unknownHandle))

            assertEquals(1, collected.size)
            assertIs<DeviceMatcher.SurfaceEvent.UnmatchedDeviceConnected>(collected.single())
            assertTrue(matcher.attached.value.isEmpty())
        }
    }

    @Test
    fun `disconnecting a previously attached device emits DeviceDetached`() {
        val (matcher, _) = fakeMatcher()
        withEventCollector(matcher) { collected ->
            matcher.handle(MidiDeviceRegistry.DeviceEvent.Connected(xTouchHandle))
            matcher.handle(MidiDeviceRegistry.DeviceEvent.Disconnected(xTouchHandle))

            assertEquals(2, collected.size)
            assertIs<DeviceMatcher.SurfaceEvent.DeviceAttached>(collected[0])
            assertIs<DeviceMatcher.SurfaceEvent.DeviceDetached>(collected[1])
            assertTrue(matcher.attached.value.isEmpty())
        }
    }

    @Test
    fun `disconnecting a never-attached device is silent`() {
        val (matcher, _) = fakeMatcher()
        withEventCollector(matcher) { collected ->
            matcher.handle(MidiDeviceRegistry.DeviceEvent.Disconnected(unknownHandle))
            assertTrue(collected.isEmpty())
        }
    }

    @Test
    fun `start subscribes to registry events and processes a live connect`() {
        val stub = StubRegistry()
        val matcher = DeviceMatcher(stub.registry)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val collected = CopyOnWriteArrayList<DeviceMatcher.SurfaceEvent>()
            scope.launch { matcher.events.collect { collected.add(it) } }
            matcher.start(scope)

            runBlocking {
                stub.fakeAccess.inputs += MidiDevicePort("in-1", "X-Touch Compact", "Behringer", PortDirection.INPUT)
                stub.fakeAccess.outputs += MidiDevicePort("out-1", "X-Touch Compact", "Behringer", PortDirection.OUTPUT)
                stub.registry.tick()
            }

            assertEquals(1, collected.size)
            assertIs<DeviceMatcher.SurfaceEvent.DeviceAttached>(collected.single())
        } finally {
            matcher.stop()
            scope.cancel()
        }
    }

    /** Minimal real-registry wrapper around a fake access source — used for end-to-end tests. */
    private class StubRegistry {
        val fakeAccess = FakeMidiAccess()
        val registry = MidiDeviceRegistry(fakeAccess, autoOpen = false)
    }

    private class FakeMidiAccess : MidiAccessSource {
        override val name = "Fake"
        val inputs = CopyOnWriteArrayList<MidiDevicePort>()
        val outputs = CopyOnWriteArrayList<MidiDevicePort>()

        override fun enumerateInputs(): List<MidiDevicePort> = inputs.toList()
        override fun enumerateOutputs(): List<MidiDevicePort> = outputs.toList()

        override suspend fun openInput(portId: String): MidiInputSource = object : MidiInputSource {
            override fun setListener(listener: (ByteArray, Int, Int) -> Unit) {}
            override fun close() {}
        }

        override suspend fun openOutput(portId: String): MidiSendTarget = MidiSendTarget { }
    }
}
