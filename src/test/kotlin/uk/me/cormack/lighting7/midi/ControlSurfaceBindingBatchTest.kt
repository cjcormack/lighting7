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

/**
 * [ControlSurfaceBindingService.replace] — the one-transaction delete-and-create behind the
 * inspector's *Fader only…*. Needs a real database (the pure resolver test's `FakeDatabase`
 * never opens a connection), so it lives here.
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
        val strip = service.create(
            projectId = projectId,
            deviceTypeKey = deviceTypeKey,
            controlId = "strip-1",
            bank = null,
            binding = Binding(Does.Strip, wash),
            takeoverPolicy = BindingTakeoverPolicy.PICKUP,
        )

        val created = service.replace(
            projectId = projectId,
            deleteIds = listOf(strip.id),
            creates = deriveStripBindings(
                strip = StripDescriptor("strip-1", "fader-1", "btn-25", "enc-1", "btn-1"),
                on = wash,
                encoderBank = EncoderBankSelection("colour"),
            ).map { (controlId, binding) ->
                newBinding(controlId, binding).copy(takeoverPolicy = strip.takeoverPolicy)
            },
        )

        assertEquals(4, created.size)
        assertNull(service.get(projectId, strip.id))
        assertEquals(
            setOf("fader-1", "btn-25", "enc-1", "btn-1"),
            service.list(projectId).map { it.controlId }.toSet(),
        )
        // The four rows resolve to exactly what the strip was deriving, and keep its policy.
        assertEquals(
            Binding(Does.Level("colour"), wash),
            service.resolve(projectId, deviceTypeKey, "enc-1", activeBank = null, encoderBank = EncoderBankSelection("colour"))?.binding,
        )
        assertEquals(BindingTakeoverPolicy.PICKUP, created.single { it.controlId == "fader-1" }.takeoverPolicy)

        // Survives a cache drop, so the rows really landed in the DB.
        service.invalidate(projectId)
        assertEquals(4, service.list(projectId).size)
    }

    @Test
    fun `a slot clash refuses before anything is deleted`() {
        val service = service()
        val strip = service.create(
            projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "strip-1",
            bank = null, binding = Binding(Does.Strip, wash),
        )
        val occupier = service.create(
            projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "btn-25",
            bank = null, binding = Binding(Does.Blackout),
        )

        assertFailsWith<IllegalStateException> {
            service.replace(
                projectId = projectId,
                deleteIds = listOf(strip.id),
                creates = listOf(
                    newBinding("fader-1", Binding(Does.Level("dimmer"), wash)),
                    newBinding("btn-25", Binding(Does.Select, wash)),
                ),
            )
        }

        // Nothing moved: the strip is still there, so is the occupier, and no partial row landed.
        assertNotNull(service.get(projectId, strip.id))
        assertNotNull(service.get(projectId, occupier.id))
        assertEquals(setOf("strip-1", "btn-25"), service.list(projectId).map { it.controlId }.toSet())

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

    // The slot guard lives on the service, not only on the REST routes, because MIDI Learn's
    // commit writes through `create` with no route validation of its own.

    @Test
    fun `create refuses a strip binding on an ordinary control`() {
        val service = service()
        assertFailsWith<IllegalArgumentException> {
            service.create(
                projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "fader-1",
                bank = null, binding = Binding(Does.Strip, wash),
            )
        }
        assertEquals(emptyList(), service.list(projectId))
    }

    @Test
    fun `create refuses a non-strip binding on a strip slot`() {
        val service = service()
        assertFailsWith<IllegalArgumentException> {
            service.create(
                projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "strip-1",
                bank = null, binding = Binding(Does.Blackout),
            )
        }
        assertEquals(emptyList(), service.list(projectId))
    }

    @Test
    fun `update cannot move a strip binding onto an ordinary control`() {
        val service = service()
        val strip = service.create(
            projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "strip-1",
            bank = null, binding = Binding(Does.Strip, wash),
        )
        assertFailsWith<IllegalArgumentException> {
            service.update(projectId = projectId, bindingId = strip.id, controlId = "fader-1")
        }
        assertEquals("strip-1", service.get(projectId, strip.id)?.controlId)
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
    fun `a strip slot is exempt from the kind rule`() {
        // A strip id names no descriptor, and its binding reaches a control by derivation rather
        // than dispatch — so the kind rule must skip it and leave `refuseWrongSlot` to judge it.
        val service = service()
        val strip = service.create(
            projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "strip-1",
            bank = null, binding = Binding(Does.Strip, wash),
        )
        assertNotNull(service.get(projectId, strip.id))
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
                creates = listOf(newBinding("btn-2", Binding(Does.Strip, wash))),
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
