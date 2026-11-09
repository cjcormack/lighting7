package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for the in-memory resolver inside [ControlSurfaceBindingService]. Uses the
 * [ControlSurfaceBindingService.seedCacheForTest] seam to populate the cache directly
 * without requiring a database.
 */
class ControlSurfaceBindingResolverTest {

    private val group = Subject.Group("front-wash")

    private var live = JobLive.EMPTY

    /** The X-Touch's own regions: strips 1–8, the right-hand encoders, the button rows, the master strip. */
    private fun service(): ControlSurfaceBindingService =
        ControlSurfaceBindingService(database = FakeDatabase.instance, jobLive = { live })

    private var jobId = 100

    private fun job(region: String, kind: JobKind, items: List<JobItem?> = emptyList(), bank: String? = null) =
        ControlSurfaceBindingService.StoredJob(
            id = ++jobId, uuid = java.util.UUID.randomUUID(), projectId = 1, deviceTypeKey = KEY, region = region, bank = bank,
            kindId = kind.id, kind = kind, rawItems = encodeJobItems(items), items = items,
        )

    private fun resolved(
        id: Int,
        deviceTypeKey: String = "x-touch-compact-standard",
        controlId: String = "fader-1",
        bank: String? = null,
        binding: Binding = Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")),
        projectId: Int = 1,
        health: AssignmentHealth = AssignmentHealth.Ok,
        part: ControlPart = ControlPart.MAIN,
    ) = ControlSurfaceBindingService.ResolvedBinding(
        id = id,
        projectId = projectId,
        deviceTypeKey = deviceTypeKey,
        controlId = controlId,
        bank = bank,
        binding = binding,
        takeoverPolicy = BindingTakeoverPolicy.IMMEDIATE,
        sortOrder = 0,
        health = health,
        part = part,
    )

    @Test
    fun `resolve returns null when nothing is cached`() {
        val svc = service()
        svc.seedCacheForTest(projectId = 1, bindings = emptyList())
        assertNull(svc.resolve(1, "x-touch-compact-standard", "fader-1", activeBank = null, encoderBank = EncoderBankSelection("dimmer")))
    }

    @Test
    fun `bank-specific binding wins over bank-agnostic when active bank matches`() {
        val svc = service()
        val global = resolved(id = 1, bank = null)
        val bankA = resolved(id = 2, bank = "layer-a")
        svc.seedCacheForTest(projectId = 1, bindings = listOf(global, bankA))

        val hit = svc.resolve(1, "x-touch-compact-standard", "fader-1", activeBank = "layer-a", encoderBank = EncoderBankSelection("dimmer"))
        assertNotNull(hit)
        assertEquals(2, hit.id)
    }

    @Test
    fun `bank-agnostic binding is returned when no bank-specific match exists`() {
        val svc = service()
        val global = resolved(id = 1, bank = null)
        val bankB = resolved(id = 2, bank = "layer-b")
        svc.seedCacheForTest(projectId = 1, bindings = listOf(global, bankB))

        val hit = svc.resolve(1, "x-touch-compact-standard", "fader-1", activeBank = "layer-a", encoderBank = EncoderBankSelection("dimmer"))
        assertNotNull(hit)
        assertEquals(1, hit.id)
    }

    @Test
    fun `bank-scoped binding does not resolve when the other bank is active`() {
        val svc = service()
        val bankA = resolved(id = 1, bank = "layer-a")
        svc.seedCacheForTest(projectId = 1, bindings = listOf(bankA))
        assertNull(svc.resolve(1, "x-touch-compact-standard", "fader-1", activeBank = "layer-b", encoderBank = EncoderBankSelection("dimmer")))
    }

    @Test
    fun `list returns bindings in insertion order`() {
        val svc = service()
        val first = resolved(id = 3, bank = null)
        val second = resolved(id = 7, bank = "layer-a")
        svc.seedCacheForTest(projectId = 1, bindings = listOf(first, second))

        val list = svc.list(1)
        assertEquals(listOf(3, 7), list.map { it.id })
    }

    @Test
    fun `get returns null for unknown id and the ResolvedBinding for known id`() {
        val svc = service()
        svc.seedCacheForTest(projectId = 1, bindings = listOf(resolved(id = 5)))
        assertNull(svc.get(projectId = 1, bindingId = 99))
        assertNotNull(svc.get(projectId = 1, bindingId = 5))
    }

    // ─── Parts ─────────────────────────────────────────────────────────────

    @Test
    fun `an encoder's push resolves its own row, never the turn's`() {
        val svc = service()
        svc.seedCacheForTest(
            projectId = 1,
            bindings = listOf(
                resolved(id = 1, controlId = "enc-9", binding = Binding(Does.Level("dimmer"), Subject.Selection)),
                resolved(id = 2, controlId = "enc-9", binding = Binding(Does.Blackout), part = ControlPart.PUSH),
            ),
        )

        assertEquals(1, svc.resolve(1, KEY, "enc-9", activeBank = null, encoderBank = EncoderBankSelection("dimmer"))?.id)
        assertEquals(
            2,
            svc.resolve(1, KEY, "enc-9", activeBank = null, encoderBank = EncoderBankSelection("dimmer"), part = ControlPart.PUSH)?.id,
        )
        assertNull(svc.resolve(1, KEY, "enc-10", activeBank = null, encoderBank = EncoderBankSelection("dimmer"), part = ControlPart.PUSH))
    }

    @Test
    fun `a push's exact-bank row beats its bank-agnostic row`() {
        val svc = service()
        svc.seedCacheForTest(
            projectId = 1,
            bindings = listOf(
                resolved(id = 1, controlId = "enc-9", bank = null, binding = Binding(Does.Blackout), part = ControlPart.PUSH),
                resolved(id = 2, controlId = "enc-9", bank = "layer-a", binding = Binding(Does.Drop), part = ControlPart.PUSH),
            ),
        )
        fun push(bank: String?) =
            svc.resolve(1, KEY, "enc-9", activeBank = bank, encoderBank = EncoderBankSelection("dimmer"), part = ControlPart.PUSH)?.id
        assertEquals(2, push("layer-a"))
        assertEquals(1, push("layer-b"))
    }

    // ─── Jobs ──────────────────────────────────────────────────────────────

    @Test
    fun `a groups job resolves each control of a strip to its role, on that slot's group`() {
        val svc = service()
        val strips = job("strips", JobKind.GROUPS, listOf(JobItem.Group("front-wash"), null, JobItem.Group("movers")))
        svc.seedCacheForTest(1, emptyList(), listOf(strips))

        val fader = assertNotNull(svc.resolve(1, KEY, "fader-1", activeBank = null, encoderBank = EncoderBankSelection("dimmer")))
        assertEquals(Binding(Does.Level("dimmer"), group), fader.binding)
        assertEquals(strips.id, fader.id)
        assertEquals(ControlSurfaceBindingService.JobHit(strips.id, "strips", 0, SlotRole.FADER, JobKind.GROUPS), fader.job)
        assertEquals("fader-1", fader.controlId)
        assertNull(fader.takeoverPolicy, "a job's controls take their own default")

        assertEquals(Binding(Does.Select, group), svc.resolve(1, KEY, "btn-25", null, EncoderBankSelection("dimmer"))?.binding)
        assertIs<Does.Flash>(svc.resolve(1, KEY, "btn-1", null, EncoderBankSelection("dimmer"))?.binding?.does)
        // A gap derives nothing, and the item after it is still its own slot's.
        assertNull(svc.resolve(1, KEY, "fader-2", null, EncoderBankSelection("dimmer")))
        assertEquals(Binding(Does.Level("dimmer"), Subject.Group("movers")), svc.resolve(1, KEY, "fader-3", null, EncoderBankSelection("dimmer"))?.binding)
        // No item, no strip.
        assertNull(svc.resolve(1, KEY, "fader-4", null, EncoderBankSelection("dimmer")))
    }

    @Test
    fun `a strip's encoder follows the encoder page passed to resolve, and its push derives nothing`() {
        val svc = service()
        svc.seedCacheForTest(1, emptyList(), listOf(job("strips", JobKind.GROUPS, listOf(JobItem.Group("front-wash")))))

        assertEquals(Binding(Does.Level("dimmer"), group), svc.resolve(1, KEY, "enc-1", null, EncoderBankSelection("dimmer"))?.binding)
        assertEquals(
            Binding(Does.Level("rgbColour", ColourAxis.SATURATION), group),
            svc.resolve(1, KEY, "enc-1", null, EncoderBankSelection("rgbColour", ColourAxis.SATURATION))?.binding,
        )
        assertNull(svc.resolve(1, KEY, "enc-1", null, EncoderBankSelection("dimmer"), part = ControlPart.PUSH), "D4: a push does nothing until bound")
    }

    @Test
    fun `a control's own binding beats the job at each layer level`() {
        val svc = service()
        val both = job("strips", JobKind.GROUPS, listOf(JobItem.Group("front-wash")))
        val onA = job("strips", JobKind.GROUPS, listOf(JobItem.Group("movers")), bank = "layer-a")
        svc.seedCacheForTest(
            1,
            listOf(
                resolved(id = 10, controlId = "fader-1", bank = null, binding = Binding(Does.GrandMaster())),
                resolved(id = 11, controlId = "btn-25", bank = "layer-b", binding = Binding(Does.Blackout)),
            ),
            listOf(both, onA),
        )
        fun on(controlId: String, bank: String?) = svc.resolve(1, KEY, controlId, bank, EncoderBankSelection("dimmer"))

        // A direct row on both layers beats an exact-layer job, as it beat an exact-layer strip.
        assertEquals(10, on("fader-1", "layer-a")?.id)
        assertEquals(10, on("fader-1", null)?.id)
        // An exact-layer direct row beats the job on its layer only.
        assertEquals(11, on("btn-25", "layer-b")?.id)
        assertEquals(Binding(Does.Select, Subject.Group("movers")), on("btn-25", "layer-a")?.binding)
        assertEquals(Binding(Does.Select, group), on("btn-25", null)?.binding)
        // The exact-layer job beats the job on both layers.
        assertEquals(onA.id, on("enc-1", "layer-a")?.id)
        assertEquals(both.id, on("enc-1", "layer-b")?.id)
        // What the direct row beats is still asked for.
        assertEquals(both.id, svc.jobBindingAt(1, KEY, "fader-1", null, EncoderBankSelection("dimmer"))?.id)
    }

    @Test
    fun `an exact-layer job that derives nothing for a part falls through to the job on both`() {
        val svc = service()
        val both = job("strips", JobKind.GROUPS, listOf(JobItem.Group("front-wash"), JobItem.Group("movers")))
        val onA = job("strips", JobKind.STACKS, listOf(JobItem.Record(RecordKind.STACK, STACK)), bank = "layer-a")
        svc.seedCacheForTest(1, emptyList(), listOf(both, onA))
        fun on(controlId: String) = svc.resolve(1, KEY, controlId, "layer-a", EncoderBankSelection("dimmer"))

        assertEquals(Binding(Does.FxAmount, Subject.Record(RecordKind.STACK, STACK)), on("fader-1")?.binding)
        // A stack's strip has no encoder: the both-layers job's still answers.
        assertEquals(Binding(Does.Level("dimmer"), group), on("enc-1")?.binding)
        // Its slot 2 is empty: so is slot 2 on layer A, and the job on both answers.
        assertEquals(Binding(Does.Level("dimmer"), Subject.Group("movers")), on("fader-2")?.binding)
    }

    @Test
    fun `a job's slot health is the health of what it derives`() {
        val svc = service()
        val strips = job("strips", JobKind.GROUPS, listOf(JobItem.Group("front-wash"))).copy(health = listOf(AssignmentHealth.MissingGroup("front-wash")))
        svc.seedCacheForTest(1, emptyList(), listOf(strips))
        assertEquals(AssignmentHealth.MissingGroup("front-wash"), svc.resolve(1, KEY, "enc-1", null, EncoderBankSelection("dimmer"))?.health)
    }

    @Test
    fun `a heads job follows the selection's heads, read on every resolve`() {
        val svc = service()
        svc.seedCacheForTest(1, emptyList(), listOf(job("strips", JobKind.HEADS)))
        assertNull(svc.resolve(1, KEY, "fader-1", null, EncoderBankSelection("dimmer")), "nothing selected, nothing on the strip")

        live = JobLive(heads = listOf("hex-1", "hex-2.1"))
        assertEquals(Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")), svc.resolve(1, KEY, "fader-1", null, EncoderBankSelection("dimmer"))?.binding)
        assertEquals(Binding(Does.Select, Subject.Fixture("hex-2.1"), How.REPLACE), svc.resolve(1, KEY, "btn-26", null, EncoderBankSelection("dimmer"))?.binding)
        assertNull(svc.resolve(1, KEY, "fader-3", null, EncoderBankSelection("dimmer")))

        live = JobLive(heads = listOf("hex-2.1"))
        assertEquals(Binding(Does.Level("dimmer"), Subject.Fixture("hex-2.1")), svc.resolve(1, KEY, "fader-1", null, EncoderBankSelection("dimmer"))?.binding)
        assertEquals(true, svc.hasLiveJob(1, JobSignal.SELECTION))
        assertEquals(false, svc.hasLiveJob(1, JobSignal.BUSK_PAGE))
    }

    @Test
    fun `a busk page job presses the pads of the page showing, in order`() {
        val svc = service()
        svc.seedCacheForTest(1, emptyList(), listOf(job("row-2", JobKind.BUSK_PAGE)))
        val pad = java.util.UUID.fromString("33333333-3333-4333-8333-333333333333")
        live = JobLive(pads = listOf(pad))
        assertEquals(Binding(Does.Pad, Subject.Record(RecordKind.PAD, pad)), svc.resolve(1, KEY, "btn-9", null, EncoderBankSelection("dimmer"))?.binding)
        assertNull(svc.resolve(1, KEY, "btn-10", null, EncoderBankSelection("dimmer")))
    }

    @Test
    fun `a job this build cannot read derives nothing`() {
        val svc = service()
        val unknown = job("strips", JobKind.GROUPS).copy(kindId = "fromTheFuture", kind = null, items = null, rawItems = "[{\"type\":\"future\"}]")
        svc.seedCacheForTest(1, emptyList(), listOf(unknown))
        assertNull(svc.resolve(1, KEY, "fader-1", null, EncoderBankSelection("dimmer")))
        assertEquals(listOf(unknown), svc.jobs(1))
    }

    private companion object {
        const val KEY = "x-touch-compact-standard"
        val STACK: java.util.UUID = java.util.UUID.fromString("11111111-1111-4111-8111-111111111111")
    }
}
