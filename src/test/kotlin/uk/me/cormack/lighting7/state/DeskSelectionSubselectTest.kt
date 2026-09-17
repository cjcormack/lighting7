package uk.me.cormack.lighting7.state

import org.junit.Test
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.testsupport.BuskRigFixture
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `DeskSelection.subselect` (busk-further plan D12, §3.5): every mode, with and without cells, *Next*
 * wrapping — pinned against `src/test/resources/busk/subselect.fixture.json`, the file the client's
 * `lib/cellsSubSelection.ts` mirror is pinned against for an unlinked window.
 */
class DeskSelectionSubselectTest {

    private val file = BuskRigFixture.subselectFile()
    private val fixtures = BuskRigFixture.fixtures(file.fixtures, file.groups)

    private fun selection(rig: String?): DeskSelection {
        val order = rig?.let { BuskRigOrder(fixtures, BuskRigFixture.rig(file.rigs.getValue(it))) }
        return DeskSelection(rigOrder = { order }) { fixtures }
    }

    @Test
    fun `every fixture case rewrites the targets as pinned`() {
        assertTrue(file.cases.isNotEmpty())
        for (case in file.cases) {
            val s = selection(case.rig)
            s.set(case.selection)
            s.subselect(SubselectMode.byName(case.mode)!!)
            assertEquals(case.expected, s.state.value.targets, case.note)
        }
    }

    @Test
    fun `a rewrite keeps the mask and stamps the mover`() {
        val s = selection("built")
        val bar = CueTargetDto("fixture", "bar-1")
        s.set(listOf(bar), setOf(PropertyMaskGroup.COLOUR), SelectionSource.window("Screen 1"))
        s.subselect(SubselectMode.ODD, SelectionSource.SURFACE)
        assertEquals(setOf(PropertyMaskGroup.COLOUR), s.state.value.families)
        assertEquals(SelectionSource.SURFACE, s.state.value.source)
    }

    @Test
    fun `a rewrite that changes nothing emits nothing and keeps the old mover`() {
        val s = selection("built")
        s.set(listOf(CueTargetDto("fixture", "hex-1")), source = SelectionSource.window("Screen 1"))
        val before = s.state.value
        s.subselect(SubselectMode.MASTERS, SelectionSource.SURFACE)
        assertSame(before, s.state.value)
    }

    @Test
    fun `NEXT and PREV are no-ops without a rig order, the other modes still act`() {
        val s = selection(null)
        s.set(listOf(CueTargetDto("fixture", "bar-1.pixel-0"), CueTargetDto("fixture", "hex-1")))
        s.subselect(SubselectMode.NEXT)
        assertEquals(listOf("bar-1.pixel-0", "hex-1"), s.state.value.targets.map { it.key })
        s.subselect(SubselectMode.MASTERS)
        assertEquals(listOf("hex-1"), s.state.value.targets.map { it.key })
        assertNull(s.state.value.source)
    }

    @Test
    fun `every mode has a name the socket can parse`() {
        for (mode in SubselectMode.entries) assertEquals(mode, SubselectMode.byName(mode.name.lowercase()))
        assertNull(SubselectMode.byName("sideways"))
    }
}
