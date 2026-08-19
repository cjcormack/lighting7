package uk.me.cormack.lighting7.fx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ProgrammerStore.lastIncludedTarget] — the "what does a bare Update write back to?" state.
 *
 * Its lifecycle is mostly owned by the route layer; the one rule that lives in the store is
 * that Clear drops it, because Clear releases everything Include staged.
 */
class ProgrammerIncludeTargetTest {

    @Test
    fun `starts empty and round-trips`() {
        val store = ProgrammerStore()
        assertNull(store.lastIncludedTarget)

        store.lastIncludedTarget = IncludedTarget.cue(cueId = 12, cueStackId = 3)
        val target = store.lastIncludedTarget
        assertEquals(IncludedTarget.Kind.CUE, target?.kind)
        assertEquals(12, target?.cueId)
        assertEquals(3, target?.cueStackId)
    }

    @Test
    fun `clearAll drops the target`() {
        // Nothing is staged after a Clear, so a surviving target would offer an Update that
        // silently wrote nothing.
        val store = ProgrammerStore()
        store.lastIncludedTarget = IncludedTarget.cue(12, 3)
        store.clearAll()
        assertNull(store.lastIncludedTarget)
    }

    @Test
    fun `clearIncludeTargetForCue is id-selective`() {
        val store = ProgrammerStore()
        store.lastIncludedTarget = IncludedTarget.cue(12, 3)

        store.clearIncludeTargetForCue(99)
        assertEquals(12, store.lastIncludedTarget?.cueId, "a different cue's deletion is irrelevant")

        store.clearIncludeTargetForCue(12)
        assertNull(store.lastIncludedTarget)
    }

    @Test
    fun `the flow replays the current target to a late subscriber`() {
        // This is what lets a tab opened mid-show render the right Update label without polling.
        val store = ProgrammerStore()
        store.lastIncludedTarget = IncludedTarget.cue(7, null)
        assertEquals(7, store.lastIncludedTargetFlow.value?.cueId)
    }

    @Test
    fun `clearing entries does not clear the target`() {
        // A partial release is not "done editing" — Update is still meaningful.
        val store = ProgrammerStore()
        store.put(ProgrammerOwner.INCLUDE, "hex-1", "dimmer", CueAssignmentResolver.PropertyValue.Slider(10u))
        store.lastIncludedTarget = IncludedTarget.cue(12, 3)

        store.clear(ProgrammerOwner.INCLUDE, "hex-1", "dimmer")

        assertEquals(0, store.size)
        assertEquals(12, store.lastIncludedTarget?.cueId)
    }

    @Test
    fun `an INCLUDE slot survives underneath a later operator write`() {
        // The whole basis of Update's "did the operator change this?" test: the include
        // baseline is still readable after a WEB write stacks on top.
        val store = ProgrammerStore()
        store.put(ProgrammerOwner.INCLUDE, "hex-1", "dimmer", CueAssignmentResolver.PropertyValue.Slider(100u))
        store.put(ProgrammerOwner.WEB, "hex-1", "dimmer", CueAssignmentResolver.PropertyValue.Slider(255u))

        assertEquals(
            CueAssignmentResolver.PropertyValue.Slider(255u),
            store.get("hex-1", "dimmer")?.value?.resolved,
        )
        assertEquals(
            CueAssignmentResolver.PropertyValue.Slider(100u),
            store.valueFor(ProgrammerOwner.INCLUDE, "hex-1", "dimmer")?.resolved,
        )
    }
}
