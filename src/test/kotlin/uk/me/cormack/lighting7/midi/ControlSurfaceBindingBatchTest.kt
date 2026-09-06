package uk.me.cormack.lighting7.midi

import org.junit.Test
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
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
    private val wash = CueTargetDto("group", "front-wash")

    private fun service() = state.controlSurfaceBindingService

    private fun newBinding(controlId: String, target: BindingTarget) =
        ControlSurfaceBindingService.NewBinding(
            deviceTypeKey = deviceTypeKey,
            controlId = controlId,
            bank = null,
            target = target,
        )

    @Test
    fun `replace deletes and creates in one call and answers the created rows`() {
        val service = service()
        val strip = service.create(
            projectId = projectId,
            deviceTypeKey = deviceTypeKey,
            controlId = "strip-1",
            bank = null,
            target = BindingTarget.Strip(wash),
            takeoverPolicy = BindingTakeoverPolicy.PICKUP,
        )

        val created = service.replace(
            projectId = projectId,
            deleteIds = listOf(strip.id),
            creates = deriveStripTargets(
                strip = StripDescriptor("strip-1", "fader-1", "btn-25", "enc-1", "btn-1"),
                target = wash,
                encoderBankProperty = "colour",
            ).map { (controlId, target) ->
                newBinding(controlId, target).copy(takeoverPolicy = strip.takeoverPolicy)
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
            BindingTarget.GroupProperty("front-wash", "colour"),
            service.resolve(projectId, deviceTypeKey, "enc-1", activeBank = null, encoderBankProperty = "colour")?.target,
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
            bank = null, target = BindingTarget.Strip(wash),
        )
        val occupier = service.create(
            projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "btn-25",
            bank = null, target = BindingTarget.Blackout,
        )

        assertFailsWith<IllegalStateException> {
            service.replace(
                projectId = projectId,
                deleteIds = listOf(strip.id),
                creates = listOf(
                    newBinding("fader-1", BindingTarget.GroupProperty("front-wash", "dimmer")),
                    newBinding("btn-25", BindingTarget.SelectTarget(wash)),
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
            bank = null, target = BindingTarget.Blackout,
        )

        val created = service.replace(
            projectId = projectId,
            deleteIds = listOf(existing.id),
            creates = listOf(newBinding("fader-1", BindingTarget.GroupProperty("front-wash", "dimmer"))),
        )

        assertEquals(1, created.size)
        assertIs<BindingTarget.GroupProperty>(service.list(projectId).single().target)
    }

    @Test
    fun `two creates claiming one slot are refused`() {
        val service = service()
        assertFailsWith<IllegalStateException> {
            service.replace(
                projectId = projectId,
                deleteIds = emptyList(),
                creates = listOf(
                    newBinding("fader-1", BindingTarget.Blackout),
                    newBinding("fader-1", BindingTarget.GrandMasterToggle),
                ),
            )
        }
        assertEquals(emptyList(), service.list(projectId))
    }

    // The slot guard lives on the service, not only on the REST routes, because MIDI Learn's
    // commit writes through `create` with no route validation of its own.

    @Test
    fun `create refuses a strip target on an ordinary control`() {
        val service = service()
        assertFailsWith<IllegalArgumentException> {
            service.create(
                projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "fader-1",
                bank = null, target = BindingTarget.Strip(wash),
            )
        }
        assertEquals(emptyList(), service.list(projectId))
    }

    @Test
    fun `create refuses a non-strip target on a strip slot`() {
        val service = service()
        assertFailsWith<IllegalArgumentException> {
            service.create(
                projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "strip-1",
                bank = null, target = BindingTarget.Blackout,
            )
        }
        assertEquals(emptyList(), service.list(projectId))
    }

    @Test
    fun `update cannot move a strip binding onto an ordinary control`() {
        val service = service()
        val strip = service.create(
            projectId = projectId, deviceTypeKey = deviceTypeKey, controlId = "strip-1",
            bank = null, target = BindingTarget.Strip(wash),
        )
        assertFailsWith<IllegalArgumentException> {
            service.update(projectId = projectId, bindingId = strip.id, controlId = "fader-1")
        }
        assertEquals("strip-1", service.get(projectId, strip.id)?.controlId)
    }

    @Test
    fun `replace refuses a create in the wrong kind of slot`() {
        val service = service()
        assertFailsWith<IllegalArgumentException> {
            service.replace(
                projectId = projectId,
                deleteIds = emptyList(),
                creates = listOf(newBinding("btn-2", BindingTarget.Strip(wash))),
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
