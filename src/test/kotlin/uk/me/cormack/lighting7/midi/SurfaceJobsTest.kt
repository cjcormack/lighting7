package uk.me.cormack.lighting7.midi

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The job kinds (surface-setup plan D11, §3 "Jobs"): which X-Touch regions each fits, and what each
 * slot and role derives — pure, over a [JobContext].
 */
class SurfaceJobsTest {
    private val xtouch = ControlSurfaceRegistry.typeFor("x-touch-compact-standard")!!
    private val stack = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val master = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val palette = UUID.fromString("44444444-4444-4444-8444-444444444444")
    private val look = UUID.fromString("66666666-6666-4666-8666-666666666666")
    private val page = EncoderBankSelection("rgbColour", ColourAxis.SATURATION)

    private fun ctx(items: List<JobItem?> = emptyList(), live: JobLive = JobLive.EMPTY) = JobContext(items, live, page)

    @Test
    fun `each kind fits the X-Touch regions of its shape`() {
        val fits = xtouch.jobRegions.associate { region -> region.id to JobKind.entries.filter { it.fits(region) }.map { it.id } }
        assertEquals(listOf("groupStrips", "fixtureStrips", "headStrips", "stackStrips", "masterStrips"), fits["strips"])
        assertEquals(listOf("wheels", "pagedWheels"), fits["encoders"])
        val buttons = listOf("palettes", "buskPage", "gos", "looks", "encoderPages", "transport", "programmer")
        assertEquals(buttons, fits["row-2"])
        assertEquals(buttons, fits["row-3"])
        assertEquals(buttons, fits["keys"])
        assertEquals(listOf("groupStrips", "fixtureStrips", "headStrips", "stackStrips", "masterStrips", "grandMaster"), fits["master"])
        for (kind in JobKind.entries) assertTrue(fits.values.any { kind.id in it }, "${kind.id} fits some X-Touch region")
    }

    @Test
    fun `a channel strip derives the level, select, encoder page and flash of its item`() {
        val on = Subject.Group("front")
        val c = ctx(listOf(JobItem.Group("front")))
        assertEquals(Binding(Does.Level("dimmer"), on), JobKind.GROUPS.derive(SlotRole.FADER, 0, c))
        assertEquals(Binding(Does.Select, on), JobKind.GROUPS.derive(SlotRole.SELECT, 0, c))
        assertEquals(Binding(Does.Level("rgbColour", ColourAxis.SATURATION), on), JobKind.GROUPS.derive(SlotRole.ENCODER, 0, c))
        assertEquals(Binding(Does.Flash("dimmer"), on), JobKind.GROUPS.derive(SlotRole.FLASH, 0, c))
        assertNull(JobKind.GROUPS.derive(SlotRole.PUSH, 0, c), "D4: a push does nothing until bound")
        assertNull(JobKind.GROUPS.derive(SlotRole.FADER, 1, c), "an empty slot derives nothing")
        assertEquals(
            Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")),
            JobKind.FIXTURES.derive(SlotRole.FADER, 0, ctx(listOf(JobItem.Fixture("hex-1")))),
        )
    }

    @Test
    fun `the selection's heads, one a strip, each select narrowing to its head`() {
        val c = ctx(live = JobLive(heads = listOf("hex-1", "bar.2")))
        assertEquals(Binding(Does.Level("dimmer"), Subject.Fixture("bar.2")), JobKind.HEADS.derive(SlotRole.FADER, 1, c))
        assertEquals(Binding(Does.Select, Subject.Fixture("hex-1"), How.REPLACE), JobKind.HEADS.derive(SlotRole.SELECT, 0, c))
        assertNull(JobKind.HEADS.derive(SlotRole.FADER, 2, c))
        assertEquals(
            Binding(Does.Level("rgbColour", ColourAxis.SATURATION), Subject.Fixture("hex-1")),
            JobKind.PAGED_WHEELS.derive(SlotRole.ENCODER, 0, c),
        )
        assertNull(JobKind.PAGED_WHEELS.derive(SlotRole.PUSH, 0, c))
    }

    @Test
    fun `stacks, masters and the grand master on a strip`() {
        val onStack = Subject.Record(RecordKind.STACK, stack)
        val s = ctx(listOf(JobItem.Record(RecordKind.STACK, stack)))
        assertEquals(Binding(Does.FxAmount, onStack), JobKind.STACKS.derive(SlotRole.FADER, 0, s))
        assertEquals(Binding(Does.Go, onStack), JobKind.STACKS.derive(SlotRole.FLASH, 0, s))
        assertEquals(Binding(Does.Back, onStack), JobKind.STACKS.derive(SlotRole.SELECT, 0, s))
        assertNull(JobKind.STACKS.derive(SlotRole.ENCODER, 0, s))

        val onMaster = Subject.Record(RecordKind.MASTER, master)
        val m = ctx(listOf(JobItem.Record(RecordKind.MASTER, master)))
        assertEquals(Binding(Does.Tempo(), onMaster), JobKind.MASTERS.derive(SlotRole.FADER, 0, m))
        assertEquals(Binding(Does.Tap, onMaster), JobKind.MASTERS.derive(SlotRole.FLASH, 0, m))

        assertEquals(Binding(Does.GrandMaster()), JobKind.GRAND_MASTER.derive(SlotRole.FADER, 0, ctx()))
        assertEquals(Binding(Does.Blackout), JobKind.GRAND_MASTER.derive(SlotRole.SELECT, 0, ctx()))
    }

    @Test
    fun `wheels and encoder pages are their items' functions`() {
        val wheel = JobItem.Function(Does.Fan(FanAxis.TILT), Subject.Selection)
        assertEquals(Binding(Does.Fan(FanAxis.TILT), Subject.Selection), JobKind.WHEELS.derive(SlotRole.ENCODER, 0, ctx(listOf(wheel))))
        assertNull(JobKind.WHEELS.derive(SlotRole.PUSH, 0, ctx(listOf(wheel))))
        val pageItem = JobItem.Function(Does.EncoderPage("pan"))
        assertEquals(Binding(Does.EncoderPage("pan")), JobKind.ENCODER_PAGES.derive(SlotRole.BUTTON, 0, ctx(listOf(pageItem))))
    }

    @Test
    fun `button rows press palettes, pads, GOs and Looks`() {
        assertEquals(
            Binding(Does.Palette(palette), Subject.Selection),
            JobKind.PALETTES.derive(SlotRole.BUTTON, 0, ctx(listOf(JobItem.Record(RecordKind.PALETTE, palette)))),
        )
        val pad = UUID.fromString("33333333-3333-4333-8333-333333333333")
        assertEquals(Binding(Does.Pad, Subject.Record(RecordKind.PAD, pad)), JobKind.BUSK_PAGE.derive(SlotRole.BUTTON, 0, ctx(live = JobLive(pads = listOf(pad)))))
        assertNull(JobKind.BUSK_PAGE.derive(SlotRole.BUTTON, 1, ctx(live = JobLive(pads = listOf(pad)))))
        assertEquals(
            Binding(Does.Go, Subject.Record(RecordKind.STACK, stack)),
            JobKind.GOS.derive(SlotRole.BUTTON, 0, ctx(listOf(JobItem.Record(RecordKind.STACK, stack)))),
        )
        assertEquals(
            Binding(Does.Look, Subject.Record(RecordKind.LOOK, look)),
            JobKind.LOOKS.derive(SlotRole.BUTTON, 0, ctx(listOf(JobItem.Record(RecordKind.LOOK, look)))),
        )
    }

    @Test
    fun `transport and the programmer keys are positional`() {
        val onStack = Subject.Record(RecordKind.STACK, stack)
        val t = ctx(listOf(JobItem.Record(RecordKind.STACK, stack)))
        assertEquals(
            listOf(Binding(Does.Go, onStack), Binding(Does.Back, onStack), Binding(Does.Stop, onStack), Binding(Does.Pause, onStack), Binding(Does.Tap), Binding(Does.Clear())),
            (0..5).map { JobKind.TRANSPORT.derive(SlotRole.BUTTON, it, t) },
        )
        assertNull(JobKind.TRANSPORT.derive(SlotRole.BUTTON, 6, t))
        assertNull(JobKind.TRANSPORT.derive(SlotRole.BUTTON, 0, ctx()), "no stack, no GO")
        assertEquals(Binding(Does.Tap), JobKind.TRANSPORT.derive(SlotRole.BUTTON, 4, ctx()), "Tap is master 1's, stack or none")
        assertEquals(
            listOf(
                Binding(Does.Clear()), Binding(Does.Blind()), Binding(Does.Undo()), Binding(Does.Release(), Subject.Selection),
                Binding(Does.Locate, Subject.Selection), Binding(Does.SelectionStep(SelectionMove.CLEAR)),
            ),
            (0..5).map { JobKind.PROGRAMMER.derive(SlotRole.BUTTON, it, ctx()) },
        )
    }

    @Test
    fun `every binding a kind derives on the X-Touch is one its control part could hold`() {
        val items = mapOf(
            JobItemKind.GROUP to JobItem.Group("front"),
            JobItemKind.FIXTURE to JobItem.Fixture("hex-1"),
            JobItemKind.STACK to JobItem.Record(RecordKind.STACK, stack),
            JobItemKind.MASTER to JobItem.Record(RecordKind.MASTER, master),
            JobItemKind.PALETTE to JobItem.Record(RecordKind.PALETTE, palette),
            JobItemKind.LOOK to JobItem.Record(RecordKind.LOOK, look),
        )
        val live = JobLive(heads = List(8) { "head-$it" }, pads = List(8) { UUID.randomUUID() })
        for (region in xtouch.jobRegions) for (kind in JobKind.entries.filter { it.fits(region) }) {
            val item = when (kind) {
                JobKind.WHEELS -> JobItem.Function(Does.Level("dimmer"), Subject.Selection)
                JobKind.ENCODER_PAGES -> JobItem.Function(Does.EncoderPage("dimmer"))
                else -> kind.item?.let(items::getValue)
            }
            val c = JobContext(region.slots.map { item }.take(kind.maxItems ?: region.slots.size), live, page)
            region.slots.forEachIndexed { index, slot ->
                for ((role, controlId) in slot.controls) {
                    val binding = kind.derive(role, index, c) ?: continue
                    val input = xtouch.control(controlId)!!.inputFor(role.part)
                    assertTrue(input in binding.does.spec.inputs, "${kind.id} on $controlId ${role.part}: ${binding.functionId}")
                    assertTrue(binding.on.subjectKind in binding.does.spec.on, "${kind.id} on $controlId: ${binding.on}")
                }
            }
        }
    }

    @Test
    fun `items survive their own codec, and a kind this build cannot read keeps nothing it can`() {
        val items = listOf(
            JobItem.Group("front"), null, JobItem.Record(RecordKind.STACK, stack),
            JobItem.Function(Does.Level("rgbColour", ColourAxis.HUE_FINE), Subject.Selection),
        )
        assertEquals(items, decodeJobItems(encodeJobItems(items)))
        assertNull(decodeJobItems("""[{"type":"fromTheFuture"}]"""))
        assertNull(decodeJobItems("""[{"type":"record","kind":"STACK","uuid":"not-a-uuid"}]"""))
    }

    @Test
    fun `the live state announces only the signals whose answer moved, and reads none a job does not follow`() {
        var heads = listOf("hex-1")
        var pads = emptyList<UUID>()
        var followed = setOf(JobSignal.SELECTION)
        val live = SurfaceJobLive(follows = { it in followed }, heads = { heads }, pads = { pads })
        assertEquals(setOf(JobSignal.SELECTION), live.refresh())
        assertEquals(emptySet(), live.refresh(), "nothing moved")
        pads = listOf(UUID.randomUUID())
        assertEquals(emptySet(), live.refresh(), "no job follows the busk page, so it is not read")
        followed = JobSignal.entries.toSet()
        heads = listOf("hex-2")
        assertEquals(JobSignal.entries.toSet(), live.refresh())
        assertEquals(JobLive(listOf("hex-2"), pads), live.state)
    }

    @Test
    fun `two refreshes at once keep both answers`() {
        val padsRead = CountDownLatch(1)
        val pad = UUID.randomUUID()
        val live = SurfaceJobLive(
            follows = { true },
            heads = { padsRead.await(200, TimeUnit.MILLISECONDS); listOf("hex-1") },
            pads = { padsRead.countDown(); listOf(pad) },
        )
        val selection = thread { live.refresh(setOf(JobSignal.SELECTION)) }
        Thread.sleep(50)
        val busk = thread { live.refresh(setOf(JobSignal.BUSK_PAGE)) }
        selection.join()
        busk.join()
        assertEquals(JobLive(listOf("hex-1"), listOf(pad)), live.state)
    }
}
