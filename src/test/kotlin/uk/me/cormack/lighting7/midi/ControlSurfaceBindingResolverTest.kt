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

    /** One strip covering fader-1 / btn-25 / enc-1 / btn-1, plus a master with no encoder. */
    private val strips = listOf(
        StripDescriptor("strip-1", fader = "fader-1", select = "btn-25", encoder = "enc-1", flash = "btn-1"),
        StripDescriptor("strip-master", fader = "fader-9", select = "btn-33"),
    )

    private val group = Subject.Group("front-wash")

    private fun service(): ControlSurfaceBindingService =
        ControlSurfaceBindingService(database = FakeDatabase.instance, stripsFor = { strips })

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

    // ─── Strips ────────────────────────────────────────────────────────────

    @Test
    fun `a strip binding resolves each of its controls to that control's role`() {
        val svc = service()
        svc.seedCacheForTest(
            projectId = 1,
            bindings = listOf(resolved(id = 9, controlId = "strip-1", binding = Binding(Does.Strip, group))),
        )

        val fader = assertNotNull(svc.resolve(1, KEY, "fader-1", activeBank = null, encoderBank = EncoderBankSelection("dimmer")))
        assertEquals(Binding(Does.Level("dimmer"), group), fader.binding)
        // The strip row's identity, policy and health carry to every control it covers.
        assertEquals(9, fader.id)
        assertEquals("fader-1", fader.controlId)

        val select = assertNotNull(svc.resolve(1, KEY, "btn-25", activeBank = null, encoderBank = EncoderBankSelection("dimmer")))
        assertEquals(Binding(Does.Select, group), select.binding)

        val flash = assertNotNull(svc.resolve(1, KEY, "btn-1", activeBank = null, encoderBank = EncoderBankSelection("dimmer")))
        assertIs<Does.Flash>(flash.binding.does)
    }

    @Test
    fun `a strip encoder follows the encoder bank passed to resolve`() {
        val svc = service()
        svc.seedCacheForTest(
            projectId = 1,
            bindings = listOf(resolved(id = 9, controlId = "strip-1", binding = Binding(Does.Strip, group))),
        )

        assertEquals(
            Binding(Does.Level("dimmer"), group),
            svc.resolve(1, KEY, "enc-1", activeBank = null, encoderBank = EncoderBankSelection("dimmer"))?.binding,
        )
        assertEquals(
            Binding(Does.Level("colour"), group),
            svc.resolve(1, KEY, "enc-1", activeBank = null, encoderBank = EncoderBankSelection("colour"))?.binding,
        )
    }

    @Test
    fun `a strip encoder's push resolves nothing, and its own push row still does`() {
        // A strip derives main parts only (D4): the push of an encoder it covers is unbound until
        // it is bound on its own.
        val svc = service()
        svc.seedCacheForTest(
            projectId = 1,
            bindings = listOf(resolved(id = 9, controlId = "strip-1", binding = Binding(Does.Strip, group))),
        )
        assertNull(svc.resolve(1, KEY, "enc-1", activeBank = null, encoderBank = EncoderBankSelection("dimmer"), part = ControlPart.PUSH))

        svc.seedCacheForTest(
            projectId = 1,
            bindings = listOf(
                resolved(id = 9, controlId = "strip-1", binding = Binding(Does.Strip, group)),
                resolved(id = 10, controlId = "enc-1", binding = Binding(Does.Blackout), part = ControlPart.PUSH),
            ),
        )
        assertEquals(
            10,
            svc.resolve(1, KEY, "enc-1", activeBank = null, encoderBank = EncoderBankSelection("dimmer"), part = ControlPart.PUSH)?.id,
        )
        assertEquals(9, svc.resolve(1, KEY, "enc-1", activeBank = null, encoderBank = EncoderBankSelection("dimmer"))?.id)
    }

    @Test
    fun `a control's own binding beats its strip`() {
        val svc = service()
        svc.seedCacheForTest(
            projectId = 1,
            bindings = listOf(
                resolved(id = 9, controlId = "strip-1", binding = Binding(Does.Strip, group)),
                resolved(id = 10, controlId = "fader-1", binding = Binding(Does.GrandMaster())),
            ),
        )

        val hit = assertNotNull(svc.resolve(1, KEY, "fader-1", activeBank = null, encoderBank = EncoderBankSelection("dimmer")))
        assertEquals(10, hit.id)
        // Its siblings still come from the strip.
        assertEquals(9, svc.resolve(1, KEY, "enc-1", activeBank = null, encoderBank = EncoderBankSelection("dimmer"))?.id)
    }

    @Test
    fun `a bank-agnostic direct binding beats an exact-bank strip`() {
        val svc = service()
        svc.seedCacheForTest(
            projectId = 1,
            bindings = listOf(
                resolved(id = 9, controlId = "strip-1", bank = "layer-a", binding = Binding(Does.Strip, group)),
                resolved(id = 10, controlId = "fader-1", bank = null, binding = Binding(Does.GrandMaster())),
            ),
        )

        // Direct-first is applied across *both* bank levels before the strip is consulted, so a
        // global single binding wins even against a strip bound to the active bank.
        val hit = assertNotNull(svc.resolve(1, KEY, "fader-1", activeBank = "layer-a", encoderBank = EncoderBankSelection("dimmer")))
        assertEquals(10, hit.id)
    }

    @Test
    fun `an exact-bank strip beats a bank-agnostic strip`() {
        val svc = service()
        svc.seedCacheForTest(
            projectId = 1,
            bindings = listOf(
                resolved(id = 9, controlId = "strip-1", bank = null, binding = Binding(Does.Strip, group)),
                resolved(
                    id = 11, controlId = "strip-1", bank = "layer-a",
                    binding = Binding(Does.Strip, Subject.Group("movers")),
                ),
            ),
        )

        assertEquals(
            Binding(Does.Level("dimmer"), Subject.Group("movers")),
            svc.resolve(1, KEY, "fader-1", activeBank = "layer-a", encoderBank = EncoderBankSelection("dimmer"))?.binding,
        )
        assertEquals(
            Binding(Does.Level("dimmer"), group),
            svc.resolve(1, KEY, "fader-1", activeBank = "layer-b", encoderBank = EncoderBankSelection("dimmer"))?.binding,
        )
    }

    @Test
    fun `a strip binding carries its health to every derived control`() {
        val svc = service()
        svc.seedCacheForTest(
            projectId = 1,
            bindings = listOf(
                resolved(
                    id = 9, controlId = "strip-1", binding = Binding(Does.Strip, group),
                    health = AssignmentHealth.MissingGroup("front-wash"),
                ),
            ),
        )
        assertEquals(
            AssignmentHealth.MissingGroup("front-wash"),
            svc.resolve(1, KEY, "enc-1", activeBank = null, encoderBank = EncoderBankSelection("dimmer"))?.health,
        )
    }

    @Test
    fun `a master strip's absent encoder resolves to nothing`() {
        val svc = service()
        svc.seedCacheForTest(
            projectId = 1,
            bindings = listOf(resolved(id = 9, controlId = "strip-master", binding = Binding(Does.Strip, group))),
        )
        assertNotNull(svc.resolve(1, KEY, "fader-9", activeBank = null, encoderBank = EncoderBankSelection("dimmer")))
        // enc-9 belongs to no strip on this profile, so nothing derives it.
        assertNull(svc.resolve(1, KEY, "enc-9", activeBank = null, encoderBank = EncoderBankSelection("dimmer")))
    }

    @Test
    fun `a strip slot holding something other than a strip still names the control that fired`() {
        // Only reachable for a row this build cannot decode (the tolerant decode keeps it) — but
        // the id has to be the physical control, or the dead-binding warning names the strip and
        // the operator cannot tell which fader went quiet.
        val svc = service()
        svc.seedCacheForTest(
            projectId = 1,
            bindings = listOf(
                resolved(
                    id = 9, controlId = "strip-1",
                    binding = Binding(Does.Unknown("fromTheFuture", "{}")),
                    health = AssignmentHealth.UnknownTarget("fromTheFuture"),
                ),
            ),
        )

        val hit = assertNotNull(svc.resolve(1, KEY, "fader-1", activeBank = null, encoderBank = EncoderBankSelection("dimmer")))
        assertEquals("fader-1", hit.controlId)
        assertEquals(AssignmentHealth.UnknownTarget("fromTheFuture"), hit.health)
    }

    private companion object {
        const val KEY = "x-touch-compact-standard"
    }
}
