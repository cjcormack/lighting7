package uk.me.cormack.lighting7.midi

import org.junit.Test
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ControlSurfaceBindingService]'s writes against a real database (the pure resolver test's
 * `FakeDatabase` never opens a connection): [ControlSurfaceBindingService.replace]'s one-transaction
 * delete-and-create, the job writes and their refusals, and the control-kind rule.
 */
class ControlSurfaceBindingBatchTest : RouteIntegrationTest() {

    private val deviceTypeKey = "x-touch-compact-standard"
    private val wash = Subject.Group("front-wash")

    private fun service() = state.controlSurfaceBindingService

    private fun newBinding(controlId: String, binding: Binding) =
        ControlSurfaceBindingService.NewBinding(
            deviceTypeKey = deviceTypeKey,
            controlId = controlId,
            bank = null,
            binding = binding,
        )

    @Test
    fun `replace deletes and creates in one call and answers the created rows`() {
        val service = service()
        val old = service.create(
            projectId = projectId,
            deviceTypeKey = deviceTypeKey,
            controlId = "fader-2",
            bank = null,
            binding = Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")),
            takeoverPolicy = BindingTakeoverPolicy.PICKUP,
        )

        val created = service.replace(
            projectId = projectId,
            deleteIds = listOf(old.id),
            creates = listOf(
                newBinding("fader-1", Binding(Does.Level("dimmer"), wash)).copy(takeoverPolicy = BindingTakeoverPolicy.PICKUP),
                newBinding("btn-25", Binding(Does.Select, wash)),
                newBinding("enc-1", Binding(Does.PageLevel, wash)),
                newBinding("btn-1", Binding(Does.Flash("dimmer"), wash)),
            ),
        )

        assertEquals(4, created.size)
        assertNull(service.get(projectId, old.id))
        assertEquals(
            setOf("fader-1", "btn-25", "enc-1", "btn-1"),
            service.list(projectId).map { it.controlId }.toSet(),
        )
        assertEquals(BindingTakeoverPolicy.PICKUP, created.single { it.controlId == "fader-1" }.takeoverPolicy)

        // Survives a cache drop, so the rows really landed in the DB.
        service.invalidate(projectId)
        assertEquals(4, service.list(projectId).size)
    }

    @Test
    fun `a slot clash refuses before anything is deleted`() {
        val service = service()
        val old = service.create(
            projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "fader-2",
            bank = null, binding = Binding(Does.Level("dimmer"), wash),
        )
        val occupier = service.create(
            projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "btn-25",
            bank = null, binding = Binding(Does.Blackout),
        )

        assertFailsWith<IllegalStateException> {
            service.replace(
                projectId = projectId,
                deleteIds = listOf(old.id),
                creates = listOf(
                    newBinding("fader-1", Binding(Does.Level("dimmer"), wash)),
                    newBinding("btn-25", Binding(Does.Select, wash)),
                ),
            )
        }

        // Nothing moved: the old row is still there, so is the occupier, and no partial row landed.
        assertNotNull(service.get(projectId, old.id))
        assertNotNull(service.get(projectId, occupier.id))
        assertEquals(setOf("fader-2", "btn-25"), service.list(projectId).map { it.controlId }.toSet())

        service.invalidate(projectId)
        assertEquals(2, service.list(projectId).size)
    }

    @Test
    fun `a slot freed by the same call is not a clash`() {
        val service = service()
        val existing = service.create(
            projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "fader-1",
            bank = null, binding = Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")),
        )

        val created = service.replace(
            projectId = projectId,
            deleteIds = listOf(existing.id),
            creates = listOf(newBinding("fader-1", Binding(Does.Level("dimmer"), wash))),
        )

        assertEquals(1, created.size)
        assertIs<Subject.Group>(service.list(projectId).single().binding.on)
    }

    @Test
    fun `two creates claiming one slot are refused`() {
        val service = service()
        assertFailsWith<IllegalStateException> {
            service.replace(
                projectId = projectId,
                deleteIds = emptyList(),
                creates = listOf(
                    // Continuous functions: a fader takes no button function, and this test is
                    // about the slot clash rather than the kind.
                    newBinding("fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1"))),
                    newBinding("fader-1", Binding(Does.Level("dimmer"), wash)),
                ),
            )
        }
        assertEquals(emptyList(), service.list(projectId))
    }

    // ─── Jobs ──────────────────────────────────────────────────────────────

    @Test
    fun `a job is stored, answered, replaced in place and taken off`() {
        val service = service()
        val items = listOf(JobItem.Group("front-wash"), null, JobItem.Group("back-wash"))
        val job = service.putJob(projectId, deviceTypeKey, "strips", null, JobKind.GROUPS.id, items + null)
        assertEquals(items, job.items, "a trailing gap is no item")

        service.invalidate(projectId)
        assertEquals(listOf(job.uuid), service.jobs(projectId).map { it.uuid })
        assertEquals(
            Binding(Does.Level("dimmer"), Subject.Group("back-wash")),
            service.resolve(projectId, deviceTypeKey, "fader-3", null, EncoderBankSelection("dimmer"))?.binding,
        )

        val replaced = service.putJob(projectId, deviceTypeKey, "strips", null, JobKind.HEADS.id, emptyList())
        assertEquals(job.id, replaced.id, "one job a region and layer: the write replaces it")
        assertEquals(JobKind.HEADS, service.jobAt(projectId, deviceTypeKey, "strips", null)?.kind)
        assertNull(service.jobAt(projectId, deviceTypeKey, "strips", "layer-a"))

        val onA = service.putJob(projectId, deviceTypeKey, "strips", "layer-a", JobKind.FIXTURES.id, listOf(JobItem.Fixture("hex-1")))
        assertEquals(2, service.jobs(projectId).size)
        assertEquals(true, service.deleteJob(projectId, deviceTypeKey, "strips", null))
        assertEquals(false, service.deleteJob(projectId, deviceTypeKey, "strips", null))
        assertEquals(listOf(onA.id), service.jobs(projectId).map { it.id })
    }

    @Test
    fun `trailing gaps are trimmed before the items are counted`() {
        val stack = JobItem.Record(RecordKind.STACK, UUID.randomUUID())
        val job = service().putJob(projectId, deviceTypeKey, "keys", null, JobKind.TRANSPORT.id, listOf(stack, null))
        assertEquals(listOf(stack), job.items)
    }

    @Test
    fun `a job write is refused by name`() {
        val service = service()
        fun refused(region: String, kind: String, items: List<JobItem?> = emptyList(), bank: String? = null): String {
            val e = assertFailsWith<BindingRefused> { service.putJob(projectId, deviceTypeKey, region, bank, kind, items) }
            assertEquals(CODE_SURFACE_JOB_INVALID, e.code)
            return e.message!!
        }
        assertTrue("no job region" in refused("right", JobKind.GROUPS.id))
        assertTrue("Unknown job kind" in refused("strips", "fromTheFuture"))
        assertTrue("Unknown layer" in refused("strips", JobKind.GROUPS.id, bank = "layer-c"))
        assertTrue("does not fit" in refused("row-2", JobKind.GROUPS.id))
        assertTrue("does not fit" in refused("strips", JobKind.GRAND_MASTER.id))
        assertTrue("at most 8" in refused("strips", JobKind.GROUPS.id, List(9) { JobItem.Group("g$it") }))
        assertTrue("is not a group" in refused("strips", JobKind.GROUPS.id, listOf(JobItem.Fixture("hex-1"))))
        assertTrue("holds no items" in refused("strips", JobKind.HEADS.id, listOf(JobItem.Group("g"))))
        assertTrue("at most 1" in refused("keys", JobKind.TRANSPORT.id, List(2) { JobItem.Record(RecordKind.STACK, UUID.randomUUID()) }))
        // A wheel is a continuous function on something it acts on.
        assertTrue("does not take a continuous" in refused("encoders", JobKind.WHEELS.id, listOf(JobItem.Function(Does.Blackout))))
        assertTrue("does not act on none" in refused("encoders", JobKind.WHEELS.id, listOf(JobItem.Function(Does.Level("dimmer")))))
        assertTrue("is an encoder page" in refused("row-3", JobKind.ENCODER_PAGES.id, listOf(JobItem.Function(Does.Blackout))))
        assertEquals(emptyList(), service.jobs(projectId))
    }

    // ─── The control-kind rule (`FU-MIDI-BIND-CONTROL-KIND`) ──────────
    //
    // Nothing refused these before, and the failure was total silence: the row saved, health read
    // `Ok`, and the control did nothing, because the router's dispatch has no arm for it. Like the
    // slot rule these live on the service rather than only in the routes — MIDI Learn's commit
    // writes through `create` with no route validation of its own.

    @Test
    fun `a button function on a fader is refused`() {
        val service = service()
        val e = assertFailsWith<BindingRefused> {
            service.create(
                projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "fader-1",
                bank = null, binding = Binding(Does.Cue, Subject.Record(RecordKind.CUE, UUID.randomUUID())),
            )
        }
        assertEquals(CODE_BINDING_WRONG_CONTROL_KIND, e.code)
        assertEquals(emptyList(), service.list(projectId))
    }

    @Test
    fun `a continuous function on a plain button is refused`() {
        val service = service()
        val e = assertFailsWith<BindingRefused> {
            service.create(
                projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "btn-1",
                bank = null, binding = Binding(Does.Level("dimmer"), wash),
            )
        }
        assertEquals(CODE_BINDING_WRONG_CONTROL_KIND, e.code)
        assertEquals(emptyList(), service.list(projectId))
    }

    @Test
    fun `an encoder with a push note takes a turn function and a push function`() {
        // An encoder's CC is its turn and its note its push, two parts of one control id (D4): a
        // level lands on the turn and a button function on the push.
        val service = service()
        val turn = service.create(
            projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "enc-1",
            bank = null, binding = Binding(Does.Level("dimmer"), Subject.Selection),
        )
        val push = service.create(
            projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "enc-2",
            bank = null, binding = Binding(Does.Blackout),
        )
        assertEquals(ControlPart.MAIN, turn.part)
        assertEquals(ControlPart.PUSH, push.part)
        assertEquals(2, service.list(projectId).size)
    }

    /**
     * The hand's three, at the write boundary (multi-screen plan §3.5). They are `BUTTON`, so a
     * fader cannot hold one — and the refusal has to be **here**, whichever door the write came
     * through: `lib/surfaceDrop.ts` in lighting-react only dims the drop target, and MIDI Learn's
     * commit, a hand-rolled REST call and a script all reach this service without passing it.
     */
    @Test
    fun `the hand's three functions are refused on a fader`() {
        val service = service()
        val bindings = listOf(
            Binding(Does.PickUp, Subject.Record(RecordKind.PAD, UUID.fromString("44444444-4444-4444-8444-444444444444"))),
            Binding(Does.Place, Subject.Record(RecordKind.BANK, UUID.fromString("55555555-5555-4555-8555-555555555555"))),
            Binding(Does.Drop),
        )
        for (binding in bindings) {
            val e = assertFailsWith<BindingRefused>("$binding should be refused on a fader") {
                service.create(
                    projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "fader-1",
                    bank = null, binding = binding,
                )
            }
            assertEquals(CODE_BINDING_WRONG_CONTROL_KIND, e.code)
        }
        assertEquals(0, service.list(projectId).size, "nothing was written")
    }

    @Test
    fun `the hand's three functions are accepted on a button`() {
        val service = service()
        service.create(
            projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "btn-1",
            bank = null,
            binding = Binding(Does.PickUp, Subject.Record(RecordKind.PAD, UUID.fromString("44444444-4444-4444-8444-444444444444"))),
        )
        service.create(
            projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "btn-2",
            bank = null,
            binding = Binding(Does.Place, Subject.Record(RecordKind.BANK, UUID.fromString("55555555-5555-4555-8555-555555555555"))),
        )
        service.create(
            projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "btn-3",
            bank = null, binding = Binding(Does.Drop),
        )
        assertEquals(3, service.list(projectId).size)
    }

    @Test
    fun `a bank button takes nothing at all`() {
        // `route` answers `ResolvedInput.BankButton` and switches the bank *before* resolving a
        // binding, so a row on one can never fire whatever it holds.
        val service = service()
        val bankButton = ControlSurfaceRegistry.typeFor(deviceTypeKey)!!
            .controls.filterIsInstance<BankButtonDescriptor>().first()
        val e = assertFailsWith<BindingRefused> {
            service.create(
                projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = bankButton.controlId,
                bank = null, binding = Binding(Does.Blackout),
            )
        }
        assertEquals(CODE_BINDING_WRONG_CONTROL_KIND, e.code)
    }

    @Test
    fun `update is guarded too`() {
        val service = service()
        val fader = service.create(
            projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "fader-1",
            bank = null, binding = Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")),
        )
        assertFailsWith<BindingRefused> {
            service.update(projectId = projectId, bindingId = fader.id, binding = Binding(Does.Blackout))
        }
        assertIs<Does.Level>(service.get(projectId, fader.id)?.binding?.does)
    }

    @Test
    fun `replace refuses a create in the wrong kind of slot`() {
        val service = service()
        assertFailsWith<IllegalArgumentException> {
            service.replace(
                projectId = projectId,
                deleteIds = emptyList(),
                creates = listOf(newBinding("btn-2", Binding(Does.Level("dimmer"), wash))),
            )
        }
        assertEquals(emptyList(), service.list(projectId))
    }

    @Test
    fun `deleting an id from another project is refused`() {
        val service = service()
        assertFailsWith<IllegalArgumentException> {
            service.replace(projectId = projectId, deleteIds = listOf(9999), creates = emptyList())
        }
    }
}
