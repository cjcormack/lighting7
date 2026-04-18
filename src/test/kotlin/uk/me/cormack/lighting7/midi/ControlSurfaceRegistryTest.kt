package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.midi.devices.XTouchCompactStandard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlSurfaceRegistryTest {

    @Test
    fun `allTypes contains x-touch-compact-standard with expected vendor and product`() {
        val info = ControlSurfaceRegistry.allTypes.find { it.typeKey == "x-touch-compact-standard" }
        assertNotNull(info)
        assertEquals("Behringer", info.vendor)
        assertEquals("X-Touch Compact", info.product)
    }

    @Test
    fun `x-touch profile has 9 faders, 16 encoders, 39 buttons, 2 bank buttons`() {
        val info = ControlSurfaceRegistry.allTypes.single { it.typeKey == "x-touch-compact-standard" }
        val faders = info.controls.filterIsInstance<FaderDescriptor>()
        val encoders = info.controls.filterIsInstance<EncoderDescriptor>()
        val buttons = info.controls.filterIsInstance<ButtonDescriptor>()
        val bankButtons = info.controls.filterIsInstance<BankButtonDescriptor>()

        assertEquals(9, faders.size)
        assertEquals(16, encoders.size)
        assertEquals(39, buttons.size)
        assertEquals(2, bankButtons.size)
        assertEquals(9 + 16 + 39 + 2, info.controls.size)
        assertEquals(2, info.banks.size)
    }

    @Test
    fun `x-touch fader 1 is motorised with touch note 101 and cc 1`() {
        val info = ControlSurfaceRegistry.allTypes.single { it.typeKey == "x-touch-compact-standard" }
        val fader = info.controls.filterIsInstance<FaderDescriptor>().single { it.controlId == "fader-1" }
        assertTrue(fader.hasMotor)
        assertEquals(1, fader.cc)
        assertEquals(1, fader.motorCc)
        assertEquals(101, fader.touchNote)
    }

    @Test
    fun `x-touch encoder 1 has ring cc 48, push note 32`() {
        val info = ControlSurfaceRegistry.allTypes.single { it.typeKey == "x-touch-compact-standard" }
        val enc = info.controls.filterIsInstance<EncoderDescriptor>().single { it.controlId == "enc-1" }
        assertEquals(16, enc.cc)
        assertEquals(48, enc.ringCc)
        assertEquals(32, enc.pushNote)
    }

    @Test
    fun `x-touch bank buttons target layer-a and layer-b`() {
        val info = ControlSurfaceRegistry.allTypes.single { it.typeKey == "x-touch-compact-standard" }
        val banks = info.controls.filterIsInstance<BankButtonDescriptor>().sortedBy { it.note }
        assertEquals(listOf("layer-a", "layer-b"), banks.map { it.bankId })
        assertEquals(listOf(84, 85), banks.map { it.note })
    }

    @Test
    fun `instantiate returns a fresh XTouchCompactStandard instance for its typeKey`() {
        val instance = ControlSurfaceRegistry.instantiate("x-touch-compact-standard")
        assertIs<XTouchCompactStandard>(instance)
    }

    @Test
    fun `instantiate throws for unknown typeKey`() {
        assertFailsWith<IllegalArgumentException> {
            ControlSurfaceRegistry.instantiate("does-not-exist")
        }
    }

    @Test
    fun `matchFor matches by manufacturer and product`() {
        val handle = MidiDeviceHandle(
            displayKey = "x-touch-compact",
            displayName = "X-Touch Compact",
            inputPort = MidiDevicePort("in-1", "X-Touch Compact", "Behringer", PortDirection.INPUT),
            outputPort = MidiDevicePort("out-1", "X-Touch Compact", "Behringer", PortDirection.OUTPUT),
        )
        val match = ControlSurfaceRegistry.matchFor(handle)
        assertNotNull(match)
        assertEquals("x-touch-compact-standard", match.typeKey)
    }

    @Test
    fun `matchFor falls back to portPattern regex when manufacturer missing`() {
        val handle = MidiDeviceHandle(
            displayKey = "x-touch-compact",
            displayName = "X_Touch_Compact Mk1",
            inputPort = MidiDevicePort("in-1", "X_Touch_Compact Mk1", null, PortDirection.INPUT),
            outputPort = null,
        )
        // portPattern regex should match regardless of separator or case
        val match = ControlSurfaceRegistry.matchFor(handle)
        assertNotNull(match)
        assertEquals("x-touch-compact-standard", match.typeKey)
    }

    @Test
    fun `matchFor returns null for unknown device`() {
        val handle = MidiDeviceHandle(
            displayKey = "random-thing",
            displayName = "Random Synth",
            inputPort = MidiDevicePort("in-1", "Random Synth", "Acme", PortDirection.INPUT),
            outputPort = null,
        )
        assertNull(ControlSurfaceRegistry.matchFor(handle))
    }

    @Test
    fun `buildFromClasses fails fast on duplicate typeKey`() {
        val exc = assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(
                listOf(DeviceA::class, DeviceAAlias::class),
            )
        }
        assertTrue(exc.message!!.contains("Duplicate control surface typeKey 'dev-a'"))
        assertTrue(exc.message!!.contains("DeviceAAlias"))
    }

    @Test
    fun `buildFromClasses fails fast on duplicate controlId within a device`() {
        val exc = assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(listOf(DuplicateControlIdDevice::class))
        }
        assertTrue(exc.message!!.contains("Duplicate controlId 'btn-1'"))
        assertTrue(exc.message!!.contains("DuplicateControlIdDevice"))
    }

    @Test
    fun `buildFromClasses fails fast when annotation is missing`() {
        assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(listOf(UnannotatedDevice::class))
        }
    }

    @Test
    fun `buildFromClasses returns a single entry for a valid class`() {
        val built = ControlSurfaceRegistry.buildFromClasses(listOf(DeviceA::class))
        assertEquals(1, built.size)
        assertEquals("dev-a", built.single().typeKey)
        assertEquals(2, built.single().controls.size)
    }
}

// --- Test fixture classes (must have no-arg constructors and @ControlSurfaceType annotations) ---

@ControlSurfaceType(typeKey = "dev-a", vendor = "Test", product = "DevA")
class DeviceA : ControlSurfaceDevice() {
    init {
        fader(id = "fader-1", cc = 10)
        button(id = "btn-1", note = 20)
    }
}

@ControlSurfaceType(typeKey = "dev-a", vendor = "Test", product = "DevA clone")
class DeviceAAlias : ControlSurfaceDevice() {
    init {
        fader(id = "fader-1", cc = 11)
    }
}

@ControlSurfaceType(typeKey = "dup-control", vendor = "Test", product = "Dup")
class DuplicateControlIdDevice : ControlSurfaceDevice() {
    init {
        button(id = "btn-1", note = 30)
        button(id = "btn-1", note = 31)
    }
}

class UnannotatedDevice : ControlSurfaceDevice() {
    init {
        fader(id = "fader-1", cc = 10)
    }
}
