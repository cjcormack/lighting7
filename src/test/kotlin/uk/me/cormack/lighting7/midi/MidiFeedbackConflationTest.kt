package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MidiFeedbackConflationTest {

    private class RecordingSendTarget : MidiSendTarget {
        val sent = CopyOnWriteArrayList<ByteArray>()
        override fun send(bytes: ByteArray) {
            sent.add(bytes.copyOf())
        }
    }

    private fun makeHandle(): MidiDeviceHandle = MidiDeviceHandle(
        displayKey = "test-dev",
        displayName = "Test Device",
        inputPort = null,
        outputPort = MidiDevicePort("out-0", "Test Out", "Test", PortDirection.OUTPUT),
    )

    @OptIn(DelicateCoroutinesApi::class)
    private fun makeController(target: MidiSendTarget): KtMidiController = KtMidiController(
        handle = makeHandle(),
        sendTarget = target,
        inputSource = null,
        // A very long interval pushes the background loop's first tick well past the test's
        // lifetime; tests drive the drain explicitly via flushForTest().
        transmitIntervalMs = 3_600_000L,
        parentScope = GlobalScope,
    )

    @Test
    fun `rapid updates to same control coalesce into a single send`() {
        val target = RecordingSendTarget()
        val controller = makeController(target)
        try {
            repeat(1000) { i ->
                controller.sendFeedback(MidiFeedbackMessage.ControlChangeFeedback(0, 7, (i % 128).toUByte()))
            }
            controller.flushForTest()
            assertEquals(1, target.sent.size, "expected 1000 rapid CC writes to coalesce into 1 send")
            val expected = MidiFeedbackMessage.ControlChangeFeedback(0, 7, ((1000 - 1) % 128).toUByte()).encode()
            assertTrue(target.sent[0].contentEquals(expected))
        } finally {
            controller.close()
        }
    }

    @Test
    fun `different controls each produce their own send`() {
        val target = RecordingSendTarget()
        val controller = makeController(target)
        try {
            controller.sendFeedback(MidiFeedbackMessage.ControlChangeFeedback(0, 1, 10u))
            controller.sendFeedback(MidiFeedbackMessage.ControlChangeFeedback(0, 2, 20u))
            controller.sendFeedback(MidiFeedbackMessage.NoteOnFeedback(0, 60, 127u))
            controller.flushForTest()
            assertEquals(3, target.sent.size)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `delta suppression skips redundant sends`() {
        val target = RecordingSendTarget()
        val controller = makeController(target)
        try {
            controller.sendFeedback(MidiFeedbackMessage.ControlChangeFeedback(0, 7, 64u))
            controller.flushForTest()
            assertEquals(1, target.sent.size)

            // Same value queued a second time; delta suppression skips it.
            controller.sendFeedback(MidiFeedbackMessage.ControlChangeFeedback(0, 7, 64u))
            controller.flushForTest()
            assertEquals(1, target.sent.size)

            // Different value — goes through.
            controller.sendFeedback(MidiFeedbackMessage.ControlChangeFeedback(0, 7, 65u))
            controller.flushForTest()
            assertEquals(2, target.sent.size)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `pitch bend encodes LSB then MSB`() {
        val target = RecordingSendTarget()
        val controller = makeController(target)
        try {
            controller.sendFeedback(MidiFeedbackMessage.PitchBendFeedback(3, 4160u))
            controller.flushForTest()
            assertEquals(1, target.sent.size)
            val expected = byteArrayOf(0xE3.toByte(), 0x40, 0x20)
            assertTrue(target.sent[0].contentEquals(expected), "got ${target.sent[0].toList()}")
        } finally {
            controller.close()
        }
    }

    @Test
    fun `note-on and note-off for same note share one conflation key`() {
        val target = RecordingSendTarget()
        val controller = makeController(target)
        try {
            // Queueing OFF right after ON on the same note results in only the OFF being sent.
            controller.sendFeedback(MidiFeedbackMessage.NoteOnFeedback(0, 60, 127u))
            controller.sendFeedback(MidiFeedbackMessage.NoteOffFeedback(0, 60))
            controller.flushForTest()
            assertEquals(1, target.sent.size)
            val expected = MidiFeedbackMessage.NoteOffFeedback(0, 60).encode()
            assertTrue(target.sent[0].contentEquals(expected))
        } finally {
            controller.close()
        }
    }

    @Test
    fun `close stops accepting feedback`() {
        val target = RecordingSendTarget()
        val controller = makeController(target)
        controller.close()
        controller.sendFeedback(MidiFeedbackMessage.ControlChangeFeedback(0, 7, 1u))
        controller.flushForTest()
        assertEquals(0, target.sent.size)
    }
}
