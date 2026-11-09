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
    fun `x-touch declares its six job regions, every bindable control in one`() {
        val info = ControlSurfaceRegistry.allTypes.single { it.typeKey == "x-touch-compact-standard" }
        assertEquals(
            listOf("Strips 1–8", "Encoders 9–16", "Buttons 9–16", "Buttons 17–24", "Buttons 34–39", "Master strip"),
            info.jobRegions.map { it.name },
        )
        val strips = assertNotNull(info.jobRegion("strips"))
        assertEquals(8, strips.slots.size)
        assertEquals(
            mapOf(
                SlotRole.FADER to "fader-1", SlotRole.SELECT to "btn-25", SlotRole.ENCODER to "enc-1",
                SlotRole.FLASH to "btn-1", SlotRole.PUSH to "enc-1",
            ),
            strips.slots.first().controls,
        )
        assertEquals(JobPlace("strips", 2, SlotRole.FADER), info.placeOf("fader-3", ControlPart.MAIN))
        assertEquals(JobPlace("strips", 0, SlotRole.PUSH), info.placeOf("enc-1", ControlPart.PUSH))
        assertEquals(JobPlace("encoders", 0, SlotRole.ENCODER), info.placeOf("enc-9", ControlPart.MAIN))
        assertEquals(JobPlace("master", 0, SlotRole.SELECT), info.placeOf("btn-33", ControlPart.MAIN))
        assertEquals(6, info.jobRegion("keys")!!.slots.size)

        // Every control but the layer keys is in exactly one region.
        val claimed = info.jobRegions.flatMap { it.controlIds }
        assertEquals(claimed.size, claimed.toSet().size)
        assertEquals(info.controls.filterNot { it is BankButtonDescriptor }.map { it.controlId }.toSet(), claimed.toSet())
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
    fun `buildFromClasses fails fast on a job region naming an undeclared control`() {
        val exc = assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(listOf(RegionMissingControlDevice::class))
        }
        assertTrue(exc.message!!.contains("'strips'"), exc.message!!)
        assertTrue(exc.message!!.contains("'enc-9'"), exc.message!!)
        assertTrue(exc.message!!.contains("RegionMissingControlDevice"), exc.message!!)
    }

    @Test
    fun `buildFromClasses fails fast on a slot role of the wrong control kind`() {
        val exc = assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(listOf(RegionWrongKindDevice::class))
        }
        assertTrue(exc.message!!.contains("'btn-1'"), exc.message!!)
        assertTrue(exc.message!!.contains("fader"), exc.message!!)
    }

    @Test
    fun `buildFromClasses fails fast on a push that is no encoder's push`() {
        val exc = assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(listOf(RegionPushlessDevice::class))
        }
        assertTrue(exc.message!!.contains("'enc-1'"), exc.message!!)
        assertTrue(exc.message!!.contains("no push"), exc.message!!)
    }

    @Test
    fun `buildFromClasses fails fast on a control in two job regions`() {
        val exc = assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(listOf(SharedControlRegionDevice::class))
        }
        assertTrue(exc.message!!.contains("'fader-1'"), exc.message!!)
        assertTrue(exc.message!!.contains("'second'"), exc.message!!)
    }

    @Test
    fun `buildFromClasses fails fast on a control part in two slots of one region`() {
        val exc = assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(listOf(DoubledSlotDevice::class))
        }
        assertTrue(exc.message!!.contains("'btn-1'"), exc.message!!)
    }

    @Test
    fun `buildFromClasses fails fast on a duplicate job region id`() {
        val exc = assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(listOf(DuplicateRegionIdDevice::class))
        }
        assertTrue(exc.message!!.contains("Duplicate job region id 'strips'"), exc.message!!)
    }

    @Test
    fun `buildFromClasses fails fast on an empty job region`() {
        val exc = assertFailsWith<IllegalStateException> {
            ControlSurfaceRegistry.buildFromClasses(listOf(EmptyRegionDevice::class))
        }
        assertTrue(exc.message!!.contains("has no slots"), exc.message!!)
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

@ControlSurfaceType(typeKey = "region-missing", vendor = "Test", product = "RegionMissing")
class RegionMissingControlDevice : ControlSurfaceDevice() {
    init {
        fader(id = "fader-1", cc = 1)
        button(id = "btn-1", note = 20)
        jobRegion("strips", "Strips") { slot(fader = "fader-1", select = "btn-1", encoder = "enc-9") }
    }
}

@ControlSurfaceType(typeKey = "region-wrong-kind", vendor = "Test", product = "RegionWrongKind")
class RegionWrongKindDevice : ControlSurfaceDevice() {
    init {
        button(id = "btn-1", note = 20)
        button(id = "btn-2", note = 21)
        jobRegion("strips", "Strips") { slot(fader = "btn-1", select = "btn-2") }
    }
}

@ControlSurfaceType(typeKey = "region-pushless", vendor = "Test", product = "RegionPushless")
class RegionPushlessDevice : ControlSurfaceDevice() {
    init {
        encoder(id = "enc-1", cc = 1)
        jobRegion("wheels", "Wheels") { slot(encoder = "enc-1", push = "enc-1") }
    }
}

@ControlSurfaceType(typeKey = "region-shared", vendor = "Test", product = "RegionShared")
class SharedControlRegionDevice : ControlSurfaceDevice() {
    init {
        fader(id = "fader-1", cc = 1)
        button(id = "btn-1", note = 20)
        button(id = "btn-2", note = 21)
        jobRegion("first", "First") { slot(fader = "fader-1", select = "btn-1") }
        jobRegion("second", "Second") { slot(fader = "fader-1", select = "btn-2") }
    }
}

@ControlSurfaceType(typeKey = "region-doubled", vendor = "Test", product = "RegionDoubled")
class DoubledSlotDevice : ControlSurfaceDevice() {
    init {
        button(id = "btn-1", note = 20)
        jobRegion("row", "Row") {
            slot(button = "btn-1")
            slot(button = "btn-1")
        }
    }
}

@ControlSurfaceType(typeKey = "region-dup", vendor = "Test", product = "RegionDup")
class DuplicateRegionIdDevice : ControlSurfaceDevice() {
    init {
        fader(id = "fader-1", cc = 1)
        fader(id = "fader-2", cc = 2)
        jobRegion("strips", "Strips") { slot(fader = "fader-1") }
        jobRegion("strips", "Strips again") { slot(fader = "fader-2") }
    }
}

@ControlSurfaceType(typeKey = "region-empty", vendor = "Test", product = "RegionEmpty")
class EmptyRegionDevice : ControlSurfaceDevice() {
    init {
        fader(id = "fader-1", cc = 1)
        jobRegion("strips", "Strips") { }
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
