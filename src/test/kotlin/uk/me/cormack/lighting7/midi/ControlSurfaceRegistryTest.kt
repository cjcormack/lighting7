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
    fun `x-touch fader 1 is motorised with touch cc 101 and cc 1`() {
        val info = ControlSurfaceRegistry.allTypes.single { it.typeKey == "x-touch-compact-standard" }
        val fader = info.controls.filterIsInstance<FaderDescriptor>().single { it.controlId == "fader-1" }
        assertTrue(fader.hasMotor)
        assertEquals(1, fader.cc)
        assertEquals(1, fader.motorCc)
        assertNull(fader.touchNote)
        assertEquals(101, fader.touchCc)
    }

    @Test
    fun `x-touch encoder 1 and 9 match Behringer Layer A defaults`() {
        val info = ControlSurfaceRegistry.allTypes.single { it.typeKey == "x-touch-compact-standard" }
        val enc1 = info.controls.filterIsInstance<EncoderDescriptor>().single { it.controlId == "enc-1" }
        // Top-row encoder 1: Turn CC 10, push Note 0. Ring RX echoes the turn CC on Layer A.
        assertEquals(10, enc1.cc)
        assertEquals(10, enc1.ringCc)
        assertEquals(0, enc1.pushNote)

        val enc9 = info.controls.filterIsInstance<EncoderDescriptor>().single { it.controlId == "enc-9" }
        // Right-block encoder 9: Turn CC 18, push Note 8. Ring RX echoes the turn CC.
        assertEquals(18, enc9.cc)
        assertEquals(18, enc9.ringCc)
        assertEquals(8, enc9.pushNote)
    }

    @Test
    fun `x-touch buttons span Layer A TX notes 16 to 54`() {
        val info = ControlSurfaceRegistry.allTypes.single { it.typeKey == "x-touch-compact-standard" }
        val buttons = info.controls.filterIsInstance<ButtonDescriptor>().sortedBy { it.note }
        assertEquals(16, buttons.first().note)
        assertEquals(16 + 38, buttons.last().note)
    }

    @Test
    fun `x-touch bank buttons use program change 0 and 1`() {
        val info = ControlSurfaceRegistry.allTypes.single { it.typeKey == "x-touch-compact-standard" }
        val banks = info.controls.filterIsInstance<BankButtonDescriptor>().sortedBy { it.programChange }
        assertEquals(listOf("layer-a", "layer-b"), banks.map { it.bankId })
        assertEquals(listOf(0, 1), banks.map { it.programChange })
        assertTrue(banks.all { it.note == null })
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
    fun `x-touch declares eight strips plus a master, mapped to real controls`() {
        val info = ControlSurfaceRegistry.allTypes.single { it.typeKey == "x-touch-compact-standard" }
        assertEquals(9, info.strips.size)

        val strip1 = info.strips.single { it.id == "strip-1" }
        assertEquals("fader-1", strip1.fader)
        assertEquals("btn-25", strip1.select)
        assertEquals("enc-1", strip1.encoder)
        assertEquals("btn-1", strip1.flash)

        val master = info.strips.single { it.id == "strip-master" }
        assertEquals("fader-9", master.fader)
        assertEquals("btn-33", master.select)
        assertNull(master.encoder)

        assertEquals(strip1, info.stripFor("enc-1"))
        assertNull(info.stripFor("enc-9"))
    }

    @Test
    fun `x-touch layout places every declared control exactly once`() {
        val info = ControlSurfaceRegistry.allTypes.single { it.typeKey == "x-touch-compact-standard" }
        val layout = assertNotNull(info.layout)
        assertEquals(listOf("strips", "right", "master"), layout.regions.map { it.name })

        val placed = layout.regions.flatMap { it.cells }.map { it.controlId }
        assertEquals(info.controls.size, placed.size)
        assertEquals(info.controls.map { it.controlId }.toSet(), placed.toSet())

        // The strip column reads top to bottom exactly as the panel does.
        val strips = layout.regions.single { it.name == "strips" }
        assertEquals(
            listOf("enc-1", "btn-1", "btn-9", "btn-17", "fader-1", "btn-25"),
            strips.cells.filter { it.col == 0 }.sortedBy { it.row }.map { it.controlId },
        )
    }

    @Test
    fun `buildFromClasses fails fast on a strip naming an undeclared control`() {
        val exc = assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(listOf(StripMissingControlDevice::class))
        }
        assertTrue(exc.message!!.contains("'strip-1'"), exc.message!!)
        assertTrue(exc.message!!.contains("'enc-9'"), exc.message!!)
        assertTrue(exc.message!!.contains("StripMissingControlDevice"), exc.message!!)
    }

    @Test
    fun `buildFromClasses fails fast on a strip role of the wrong control kind`() {
        val exc = assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(listOf(StripWrongKindDevice::class))
        }
        assertTrue(exc.message!!.contains("'btn-1'"), exc.message!!)
        assertTrue(exc.message!!.contains("fader"), exc.message!!)
    }

    @Test
    fun `buildFromClasses fails fast on a control claimed by two strips`() {
        val exc = assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(listOf(SharedControlStripDevice::class))
        }
        assertTrue(exc.message!!.contains("'fader-1'"), exc.message!!)
        assertTrue(exc.message!!.contains("'strip-2'"), exc.message!!)
    }

    @Test
    fun `buildFromClasses fails fast on a strip id colliding with a control id`() {
        val exc = assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(listOf(StripIdCollisionDevice::class))
        }
        assertTrue(exc.message!!.contains("'btn-1'"), exc.message!!)
        assertTrue(exc.message!!.contains("collides"), exc.message!!)
    }

    @Test
    fun `buildFromClasses fails fast on a duplicate strip id`() {
        val exc = assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(listOf(DuplicateStripIdDevice::class))
        }
        assertTrue(exc.message!!.contains("Duplicate strip id 'strip-1'"), exc.message!!)
    }

    @Test
    fun `buildFromClasses fails fast on a layout that leaves a control unplaced`() {
        val exc = assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(listOf(IncompleteLayoutDevice::class))
        }
        assertTrue(exc.message!!.contains("'btn-1'"), exc.message!!)
        assertTrue(exc.message!!.contains("IncompleteLayoutDevice"), exc.message!!)
    }

    @Test
    fun `buildFromClasses fails fast on a layout cell naming an undeclared control`() {
        val exc = assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(listOf(UnknownLayoutCellDevice::class))
        }
        assertTrue(exc.message!!.contains("'btn-99'"), exc.message!!)
    }

    @Test
    fun `buildFromClasses fails fast on a control placed in two layout cells`() {
        val exc = assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(listOf(DoublePlacedLayoutDevice::class))
        }
        assertTrue(exc.message!!.contains("'fader-1'"), exc.message!!)
        assertTrue(exc.message!!.contains("two layout cells"), exc.message!!)
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

@ControlSurfaceType(typeKey = "strip-missing", vendor = "Test", product = "StripMissing")
class StripMissingControlDevice : ControlSurfaceDevice() {
    init {
        fader(id = "fader-1", cc = 1)
        button(id = "btn-1", note = 20)
        strip(id = "strip-1", fader = "fader-1", select = "btn-1", encoder = "enc-9")
    }
}

@ControlSurfaceType(typeKey = "strip-wrong-kind", vendor = "Test", product = "StripWrongKind")
class StripWrongKindDevice : ControlSurfaceDevice() {
    init {
        button(id = "btn-1", note = 20)
        button(id = "btn-2", note = 21)
        strip(id = "strip-1", fader = "btn-1", select = "btn-2")
    }
}

@ControlSurfaceType(typeKey = "strip-shared", vendor = "Test", product = "StripShared")
class SharedControlStripDevice : ControlSurfaceDevice() {
    init {
        fader(id = "fader-1", cc = 1)
        button(id = "btn-1", note = 20)
        button(id = "btn-2", note = 21)
        strip(id = "strip-1", fader = "fader-1", select = "btn-1")
        strip(id = "strip-2", fader = "fader-1", select = "btn-2")
    }
}

@ControlSurfaceType(typeKey = "strip-collision", vendor = "Test", product = "StripCollision")
class StripIdCollisionDevice : ControlSurfaceDevice() {
    init {
        fader(id = "fader-1", cc = 1)
        button(id = "btn-1", note = 20)
        strip(id = "btn-1", fader = "fader-1", select = "btn-1")
    }
}

@ControlSurfaceType(typeKey = "strip-dup", vendor = "Test", product = "StripDup")
class DuplicateStripIdDevice : ControlSurfaceDevice() {
    init {
        fader(id = "fader-1", cc = 1)
        fader(id = "fader-2", cc = 2)
        button(id = "btn-1", note = 20)
        button(id = "btn-2", note = 21)
        strip(id = "strip-1", fader = "fader-1", select = "btn-1")
        strip(id = "strip-1", fader = "fader-2", select = "btn-2")
    }
}

@ControlSurfaceType(typeKey = "layout-incomplete", vendor = "Test", product = "LayoutIncomplete")
class IncompleteLayoutDevice : ControlSurfaceDevice() {
    init {
        fader(id = "fader-1", cc = 1)
        button(id = "btn-1", note = 20)
        layout { region("main", columns = 1) { column(0) { cell("fader-1") } } }
    }
}

@ControlSurfaceType(typeKey = "layout-unknown-cell", vendor = "Test", product = "LayoutUnknownCell")
class UnknownLayoutCellDevice : ControlSurfaceDevice() {
    init {
        fader(id = "fader-1", cc = 1)
        layout { region("main", columns = 1) { column(0) { cell("fader-1"); cell("btn-99") } } }
    }
}

@ControlSurfaceType(typeKey = "layout-double", vendor = "Test", product = "LayoutDouble")
class DoublePlacedLayoutDevice : ControlSurfaceDevice() {
    init {
        fader(id = "fader-1", cc = 1)
        layout {
            region("main", columns = 1) { column(0) { cell("fader-1") } }
            region("other", columns = 1) { column(0) { cell("fader-1") } }
        }
    }
}
