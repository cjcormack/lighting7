package uk.me.cormack.lighting7.history

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import uk.me.cormack.lighting7.fx.IncludedTarget
import uk.me.cormack.lighting7.fx.ProgrammerStore
import uk.me.cormack.lighting7.show.LocateManager
import uk.me.cormack.lighting7.state.ProgrammerScenery
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ProgrammerHistory] on its own, over holders that are one counter: when a step is taken, when a
 * gesture folds into the last one, and what undo and redo restore.
 */
class ProgrammerHistoryTest {

    private var now = 1_000L
    private val frames = MutableStateFlow(ProgrammerHistory.Frame.EMPTY)

    /** The programmer, as one number: each snapshot names it as the include target's id. */
    private var value = 0
    private val restored = mutableListOf<Int>()

    private fun snapshotOf(n: Int) = ProgrammerSnapshot(
        store = ProgrammerStore().snapshot().copy(lastIncludedTarget = if (n == 0) null else IncludedTarget.cue(n, null)),
        localFx = emptyList(),
        locate = LocateManager.Snapshot(emptyMap()),
        scenery = ProgrammerScenery.Snapshot(null, emptyMap()),
    )

    private fun valueOf(snapshot: ProgrammerSnapshot): Int = snapshot.store.lastIncludedTarget?.targetId ?: 0

    private val holders = object : ProgrammerHolders {
        override fun snapshot() = snapshotOf(value)
        override fun restore(snapshot: ProgrammerSnapshot, fadeMs: Long) {
            value = valueOf(snapshot)
            restored += value
        }
    }

    private fun history(capacity: Int = ProgrammerHistory.DEFAULT_CAPACITY) =
        ProgrammerHistory(capacity) { now }.also { it.bind(1, holders, frames) }

    /** One gesture under [origin]: two writes, each asking for a step first. */
    private fun ProgrammerHistory.gesture(origin: WriteOrigin?, to: Int) = WriteOrigin.with(origin) {
        beforeMutation()
        value = to
        beforeMutation()
    }

    private val chris = HistorySource.account(java.util.UUID(0, 1), "Chris")

    @Test
    fun `each gesture is one step however many writes it makes, and none without an origin`() {
        val h = history()
        h.gesture(WriteOrigin(chris), 1)
        h.gesture(WriteOrigin(chris), 2)
        h.gesture(null, 3)
        h.gesture(WriteOrigin.untracked(), 4)
        assertEquals(2, h.frame().undo.size)
        assertEquals(2, frames.value.undo.size, "and the frame says so")
    }

    @Test
    fun `a drag folds into one step within a second, per source and gesture`() {
        val h = history()
        fun drag(source: HistorySource, key: String, to: Int) =
            h.gesture(WriteOrigin(source).apply { coalesceKey = key }, to)
        drag(chris, "set|hex-1|dimmer", 1)
        now += 400
        drag(chris, "set|hex-1|dimmer", 2)
        now += 900
        drag(chris, "set|hex-1|dimmer", 3)
        assertEquals(1, h.frame().undo.size, "a second since the last frame, not since the first")
        now += 1_001
        drag(chris, "set|hex-1|dimmer", 4)
        assertEquals(2, h.frame().undo.size, "a pause starts a new one")
        drag(chris, "set|hex-1|uv", 5)
        drag(HistorySource.surface("x-touch"), "set|hex-1|uv", 6)
        assertEquals(4, h.frame().undo.size, "another attribute, or another source, is its own")

        h.undo()
        h.undo()
        h.undo()
        assertEquals(3, value, "the drag's step undoes to where the pause found it")
    }

    @Test
    fun `undo and redo restore under their own kind, so they take no step`() {
        val h = history()
        h.gesture(WriteOrigin(chris), 1)
        h.gesture(WriteOrigin(chris), 2)
        val kinds = mutableListOf<WriteOrigin.Kind?>()
        val watching = object : ProgrammerHolders by holders {
            override fun restore(snapshot: ProgrammerSnapshot, fadeMs: Long) {
                kinds += WriteOrigin.current()?.kind
                h.beforeMutation()
                holders.restore(snapshot, fadeMs)
            }
        }
        h.bind(1, watching, frames)
        assertEquals("p-2", h.undo()!!.single().id)
        assertEquals(1, value)
        assertEquals("p-2", h.redo()!!.single().id)
        assertEquals(2, value)
        assertEquals(listOf<WriteOrigin.Kind?>(WriteOrigin.Kind.UNDO, WriteOrigin.Kind.REDO), kinds)
        assertEquals(2, h.frame().undo.size)
    }

    @Test
    fun `undo through a step goes back to before it, and redo comes forward one at a time`() {
        val h = history()
        for (n in 1..4) h.gesture(WriteOrigin(chris), n)
        val second = h.frame().undo[2].id
        assertEquals(listOf("p-4", "p-3", "p-2"), h.undo(throughId = second)!!.map { it.id })
        assertEquals(1, value)
        assertEquals(listOf(1), restored, "one restore, straight to before it")
        h.redo()
        assertEquals(2, value)
        h.redo()
        assertEquals(3, value)
        h.redo()
        assertEquals(4, value)
        assertNull(h.redo())
    }

    @Test
    fun `the ring keeps the newest steps, and a new step drops redo`() {
        val h = history(capacity = 3)
        for (n in 1..5) h.gesture(WriteOrigin(chris), n)
        assertEquals(listOf("p-5", "p-4", "p-3"), h.frame().undo.map { it.id })
        h.undo()
        assertEquals(1, h.frame().redo.size)
        h.gesture(WriteOrigin(chris), 9)
        assertTrue(h.frame().redo.isEmpty())
        assertNull(h.undo(throughId = "p-1"), "a step past the ring is gone")
    }

    @Test
    fun `a request that writes again after another request's write is still one step`() {
        val h = history()
        val claude = WriteOrigin(HistorySource.mcp("cloud-grant:7"))
        val drag = WriteOrigin(chris).apply { coalesceKey = "set|hex-1|dimmer" }
        WriteOrigin.with(claude) { h.beforeMutation() }
        WriteOrigin.with(drag) { h.beforeMutation() }
        WriteOrigin.with(claude) { h.beforeMutation() }
        now += 100
        WriteOrigin.with(WriteOrigin(chris).apply { coalesceKey = "set|hex-1|dimmer" }) { h.beforeMutation() }
        assertEquals(2, h.frame().undo.size, "Claude's call is one step, and the drag still folds")
    }

    @Test
    fun `a step taken while an undo restores drops redo, and a restore that throws keeps its steps`() {
        val h = history()
        h.gesture(WriteOrigin(chris), 1)
        h.gesture(WriteOrigin(chris), 2)
        val racing = object : ProgrammerHolders by holders {
            override fun restore(snapshot: ProgrammerSnapshot, fadeMs: Long) {
                WriteOrigin.with(WriteOrigin(HistorySource.surface("x-touch"))) { h.beforeMutation() }
                holders.restore(snapshot, fadeMs)
            }
        }
        h.bind(1, racing, frames)
        h.undo()
        assertTrue(h.frame().redo.isEmpty(), "the surface's step dropped redo")
        assertEquals(2, h.frame().undo.size)

        val failing = object : ProgrammerHolders by holders {
            override fun restore(snapshot: ProgrammerSnapshot, fadeMs: Long) = error("publish failed")
        }
        h.bind(1, failing, frames)
        val before = h.frame().undo.map { it.id }
        kotlin.test.assertFails { h.undo() }
        assertEquals(before, h.frame().undo.map { it.id }, "nothing lost")
    }
}
