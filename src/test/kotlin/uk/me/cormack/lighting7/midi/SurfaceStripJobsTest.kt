package uk.me.cormack.lighting7.midi

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The strip-to-job translate, pure: what database step 17 and the
 * archive importer both do to a project's channel strips.
 */
class SurfaceStripJobsTest {
    private val device = "x-touch-compact-standard"

    private fun uuid(n: Int) = UUID(0x5717L, n.toLong())

    private fun strip(n: Int, controlId: String, on: Subject, bank: String? = null) =
        SurfaceStripJobs.StripRow(uuid(n), device, controlId, bank, BindingJson.encodeToString(StripPayload.serializer(), StripPayload(mapOf("type" to "strip"), on)))

    @kotlinx.serialization.Serializable
    private data class StripPayload(val does: Map<String, String>, val on: Subject)

    @Test
    fun `strips naming groups become one groups job a layer, empty strips its gaps`() {
        val plan = SurfaceStripJobs.plan(
            listOf(
                strip(1, "strip-1", Subject.Group("front")),
                strip(2, "strip-2", Subject.Group("back")),
                strip(3, "strip-4", Subject.Group("sides")),
                strip(4, "strip-1", Subject.Group("movers"), bank = "layer-a"),
            ),
            own = emptySet(),
        )
        val both = plan.jobs.single { it.bank == null }
        assertEquals("strips", both.region)
        assertEquals(JobKind.GROUPS, both.kind)
        assertEquals(listOf(JobItem.Group("front"), JobItem.Group("back"), null, JobItem.Group("sides")), both.items)
        assertEquals(listOf(JobItem.Group("movers")), plan.jobs.single { it.bank == "layer-a" }.items)
        assertEquals((1..4).map(::uuid), plan.removed)
        assertTrue(plan.bindings.isEmpty())
    }

    @Test
    fun `the master strip is the master region's job, fixtures make a fixtures job`() {
        val plan = SurfaceStripJobs.plan(
            listOf(strip(1, "strip-master", Subject.Group("front")), strip(2, "strip-3", Subject.Fixture("hex-1"))),
            own = emptySet(),
        )
        assertEquals(listOf(JobItem.Group("front")), plan.jobs.single { it.region == "master" }.items)
        val strips = plan.jobs.single { it.region == "strips" }
        assertEquals(JobKind.FIXTURES, strips.kind)
        assertEquals(listOf(null, null, JobItem.Fixture("hex-1")), strips.items)
    }

    @Test
    fun `a strip of the other kind becomes its controls' own bindings, where nothing of their own beat it`() {
        val plan = SurfaceStripJobs.plan(
            listOf(
                strip(1, "strip-1", Subject.Group("front")),
                strip(2, "strip-2", Subject.Group("back")),
                strip(3, "strip-5", Subject.Fixture("hex-1")),
                strip(4, "strip-6", Subject.Fixture("hex-2"), bank = "layer-a"),
                strip(5, "strip-1", Subject.Group("movers"), bank = "layer-a"),
            ),
            own = setOf(
                SurfaceStripJobs.Slot(device, "btn-29", ControlPart.MAIN, null),
                SurfaceStripJobs.Slot(device, "fader-6", ControlPart.MAIN, null),
            ),
        )
        assertEquals(listOf(JobItem.Group("front"), JobItem.Group("back")), plan.jobs.single { it.bank == null }.items)
        val hex1 = Subject.Fixture("hex-1")
        assertEquals(
            mapOf(
                SurfaceStripJobs.Slot(device, "fader-5", ControlPart.MAIN, null) to Binding(Does.Level("dimmer"), hex1),
                SurfaceStripJobs.Slot(device, "enc-5", ControlPart.MAIN, null) to Binding(Does.PageLevel, hex1),
                SurfaceStripJobs.Slot(device, "btn-5", ControlPart.MAIN, null) to Binding(Does.Flash("dimmer"), hex1),
                // btn-29 has a binding of its own on both layers: it beat the strip, so it still does.
                // On layer A, fader-6's own binding on both layers beat the strip too.
                SurfaceStripJobs.Slot(device, "enc-6", ControlPart.MAIN, "layer-a") to Binding(Does.PageLevel, Subject.Fixture("hex-2")),
                SurfaceStripJobs.Slot(device, "btn-30", ControlPart.MAIN, "layer-a") to Binding(Does.Select, Subject.Fixture("hex-2")),
                SurfaceStripJobs.Slot(device, "btn-6", ControlPart.MAIN, "layer-a") to Binding(Does.Flash("dimmer"), Subject.Fixture("hex-2")),
            ),
            plan.bindings.associate { it.slot to it.binding },
        )
        assertEquals(JobKind.GROUPS, plan.jobs.single { it.bank == "layer-a" }.kind, "a tie of one each goes to the groups")
    }

    @Test
    fun `the union's old strip payload reads as the grammar's does, and an unreadable strip is dropped`() {
        val old = SurfaceStripJobs.StripRow(uuid(1), device, "strip-2", null, """{"type":"strip","target":{"type":"group","key":"front"}}""")
        val unreadable = SurfaceStripJobs.StripRow(uuid(2), device, "strip-3", null, """{"does":{"type":"future"}}""")
        val plan = SurfaceStripJobs.plan(listOf(old, unreadable), own = emptySet())
        assertEquals(listOf(null, JobItem.Group("front")), plan.jobs.single().items)
        assertEquals(listOf(unreadable), plan.unreadable)
        assertEquals(listOf(uuid(1), uuid(2)), plan.removed, "both rows go")
    }

    @Test
    fun `the same strips make the same records on any desk`() {
        val strips = listOf(strip(1, "strip-1", Subject.Group("front")), strip(2, "strip-2", Subject.Fixture("hex-1")))
        val a = SurfaceStripJobs.plan(strips, own = emptySet())
        val b = SurfaceStripJobs.plan(strips.reversed(), own = emptySet())
        assertEquals(a.jobs, b.jobs)
        assertEquals(a.bindings.toSet(), b.bindings.toSet())
    }

    @Test
    fun `only the X-Touch's strip ids are strips`() {
        assertTrue(SurfaceStripJobs.isStrip(device, "strip-8"))
        assertTrue(SurfaceStripJobs.isStrip(device, "strip-master"))
        assertTrue(!SurfaceStripJobs.isStrip(device, "fader-1"))
        assertTrue(!SurfaceStripJobs.isStrip("xtouch-mini", "strip-1"), "a profile this desk does not have declared no strips")
    }
}
