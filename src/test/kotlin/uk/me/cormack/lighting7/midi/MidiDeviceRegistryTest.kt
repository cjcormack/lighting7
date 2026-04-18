package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [MidiDeviceRegistry]. We drive the diff loop manually via `tick()` instead
 * of waiting for the 1 Hz poll, which keeps the tests fast and deterministic.
 */
class MidiDeviceRegistryTest {

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

    private fun withEventCollector(
        registry: MidiDeviceRegistry,
        block: suspend (MutableList<MidiDeviceRegistry.DeviceEvent>) -> Unit,
    ) {
        val collected = CopyOnWriteArrayList<MidiDeviceRegistry.DeviceEvent>()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val job: Job = scope.launch {
            registry.events.collect { collected.add(it) }
        }
        try {
            runBlocking { block(collected) }
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `connecting a device emits Connected and populates devices`() {
        val access = FakeMidiAccess()
        val registry = MidiDeviceRegistry(access, autoOpen = false)
        withEventCollector(registry) { collected ->
            access.inputs += MidiDevicePort("in-1", "X-Touch Compact", "Behringer", PortDirection.INPUT)
            access.outputs += MidiDevicePort("out-1", "X-Touch Compact", "Behringer", PortDirection.OUTPUT)
            registry.tick()

            assertEquals(1, collected.size)
            assertEquals("x-touch-compact", collected[0].handle.displayKey)
            assertEquals(1, registry.devices.value.size)
        }
    }

    @Test
    fun `disconnecting a device emits Disconnected and clears devices`() {
        val access = FakeMidiAccess()
        val registry = MidiDeviceRegistry(access, autoOpen = false)
        withEventCollector(registry) { collected ->
            access.inputs += MidiDevicePort("in-1", "Nano Kontrol", "Korg", PortDirection.INPUT)
            registry.tick()
            assertEquals(1, collected.size)

            access.inputs.clear()
            registry.tick()
            assertEquals(2, collected.size)
            assertIs<MidiDeviceRegistry.DeviceEvent.Disconnected>(collected[1])
            assertEquals(0, registry.devices.value.size)
        }
    }

    @Test
    fun `input and output ports with the same name pair into one handle`() = runBlocking<Unit> {
        val access = FakeMidiAccess()
        access.inputs += MidiDevicePort("in-1", "X-Touch Compact", "Behringer", PortDirection.INPUT)
        access.outputs += MidiDevicePort("out-1", "X-Touch Compact", "Behringer", PortDirection.OUTPUT)
        val registry = MidiDeviceRegistry(access, autoOpen = false)
        registry.tick()

        assertEquals(1, registry.devices.value.size)
        val handle = registry.devices.value.single()
        assertNotNull(handle.inputPort)
        assertNotNull(handle.outputPort)
    }

    @Test
    fun `input-only device becomes a single-direction handle`() = runBlocking<Unit> {
        val access = FakeMidiAccess()
        access.inputs += MidiDevicePort("in-1", "Foot Pedal", "Vendor", PortDirection.INPUT)
        val registry = MidiDeviceRegistry(access, autoOpen = false)
        registry.tick()

        val handle = registry.devices.value.single()
        assertNotNull(handle.inputPort)
        assertNull(handle.outputPort)
    }

    @Test
    fun `autoOpen opens a controller for every connected device`() = runBlocking<Unit> {
        val access = FakeMidiAccess()
        val registry = MidiDeviceRegistry(access, autoOpen = true)
        try {
            access.inputs += MidiDevicePort("in-1", "X-Touch", "Behringer", PortDirection.INPUT)
            access.outputs += MidiDevicePort("out-1", "X-Touch", "Behringer", PortDirection.OUTPUT)
            registry.tick()

            val controller = registry.controllerFor("x-touch")
            assertNotNull(controller)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `tick with no changes does not re-emit events`() {
        val access = FakeMidiAccess()
        val registry = MidiDeviceRegistry(access, autoOpen = false)
        withEventCollector(registry) { collected ->
            access.inputs += MidiDevicePort("in-1", "Dev", "V", PortDirection.INPUT)
            registry.tick()
            registry.tick()
            registry.tick()

            assertEquals(1, collected.size, "only one Connected expected despite three ticks")
        }
    }
}
