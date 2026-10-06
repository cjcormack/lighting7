package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.runBlocking
import org.junit.Test
import uk.me.cormack.lighting7.fx.CueRunState
import kotlin.test.assertEquals

/**
 * The orderings a socket's run-state frames can reach it in. Each frame is its own launch and the
 * connect snapshot is read after the listener is registered, so every case here happens in
 * production; the gate is what decides which of them the client sees.
 */
class CueRunStateGateTest {

    private fun frame(seq: Long, stackId: Int = 4, activeCueId: Int = 11, transition: Boolean = false) =
        CueRunState(
            projectId = 1,
            stackId = stackId,
            activeCueId = activeCueId,
            nextCueId = null,
            nextIsArmed = false,
            transition = transition,
            fadeDurationMs = null,
            fadeElapsedMs = null,
            autoAdvance = false,
            autoAdvanceDelayMs = null,
            seq = seq,
        )

    private fun gate(): Pair<CueRunStateGate, List<CueRunStateChangedOutMessage>> {
        val sent = mutableListOf<CueRunStateChangedOutMessage>()
        return CueRunStateGate { sent += it as CueRunStateChangedOutMessage } to sent
    }

    @Test
    fun `a snapshot that ties a GO already sent is dropped`() = runBlocking {
        // A snapshot read after the GO describes the same moment, minus the transition.
        val (gate, sent) = gate()
        gate.change(frame(seq = 5, transition = true))
        gate.snapshot(frame(seq = 5))
        assertEquals(listOf(true), sent.map { it.transition })
    }

    @Test
    fun `a snapshot read before a GO but sent after it is dropped`() = runBlocking {
        val (gate, sent) = gate()
        gate.change(frame(seq = 5, activeCueId = 12, transition = true))
        gate.snapshot(frame(seq = 4, activeCueId = 11))
        assertEquals(listOf(12), sent.map { it.activeCueId }, "the old cue must not be put back live")
    }

    @Test
    fun `a GO that ties a snapshot already sent still goes, for its transition`() = runBlocking {
        val (gate, sent) = gate()
        gate.snapshot(frame(seq = 5))
        gate.change(frame(seq = 5, transition = true))
        assertEquals(listOf(false, true), sent.map { it.transition })
    }

    @Test
    fun `a change overtaken by a later one is dropped`() = runBlocking {
        val (gate, sent) = gate()
        gate.change(frame(seq = 6, activeCueId = 13))
        gate.change(frame(seq = 5, activeCueId = 12))
        assertEquals(listOf(13), sent.map { it.activeCueId })
    }

    @Test
    fun `stacks are ordered independently`() = runBlocking {
        val (gate, sent) = gate()
        gate.change(frame(seq = 9, stackId = 4))
        gate.snapshot(frame(seq = 3, stackId = 5))
        gate.change(frame(seq = 10, stackId = 5))
        assertEquals(listOf(4, 5, 5), sent.map { it.stackId })
    }
}
