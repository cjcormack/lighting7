package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/**
 * Manual integration harness for Phase 0 of the control-surface plan.
 *
 * Runs as `./gradlew test --tests ConsoleEchoListenerKt` is *not* how this is driven — instead,
 * invoke [main] directly from the IDE (right-click → Run) with a MIDI device (e.g. a
 * Behringer X-Touch Compact in Standard mode) plugged in.
 *
 * Behaviour:
 * - Creates a [MidiDeviceRegistry] on the platform default access source, starts polling.
 * - Logs every `Connected` / `Disconnected` event.
 * - For each new controller, subscribes to its [MidiController.input] and prints every event.
 * - Echoes every inbound `NoteOn` / `ControlChange` back as the matching feedback message,
 *   which drives the X-Touch's LEDs from the controller's current value (validates that the
 *   outbound conflation + delta-tracking path works end-to-end).
 *
 * Exit with Ctrl-C.
 */
fun main(): Unit = runBlocking {
    val registry = MidiDeviceRegistry(createPlatformKtmidiAccessSource())
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    registry.start(scope)

    val subscriptions = HashMap<String, Job>()

    Runtime.getRuntime().addShutdownHook(Thread {
        println("Shutting down...")
        subscriptions.values.forEach { it.cancel() }
        scope.cancel()
        registry.close()
    })

    println("ConsoleEchoListener: polling for MIDI devices. Ctrl-C to exit.")

    registry.events.collect { event ->
        when (event) {
            is MidiDeviceRegistry.DeviceEvent.Connected -> {
                println("Connected: ${event.handle.displayKey} (${event.handle.displayName})")
                val controller = registry.controllerFor(event.handle.displayKey) ?: return@collect
                subscriptions[event.handle.displayKey] = scope.launch {
                    controller.input.collect { midiEvent ->
                        println("[${event.handle.displayKey}] $midiEvent")
                        when (midiEvent) {
                            is MidiInputEvent.NoteOn -> controller.sendFeedback(
                                MidiFeedbackMessage.NoteOnFeedback(midiEvent.channel, midiEvent.note, midiEvent.velocity),
                            )
                            is MidiInputEvent.NoteOff -> controller.sendFeedback(
                                MidiFeedbackMessage.NoteOffFeedback(midiEvent.channel, midiEvent.note),
                            )
                            is MidiInputEvent.ControlChange -> controller.sendFeedback(
                                MidiFeedbackMessage.ControlChangeFeedback(midiEvent.channel, midiEvent.cc, midiEvent.value),
                            )
                            else -> {}
                        }
                    }
                }
            }
            is MidiDeviceRegistry.DeviceEvent.Disconnected -> {
                println("Disconnected: ${event.handle.displayKey}")
                subscriptions.remove(event.handle.displayKey)?.cancel()
            }
        }
    }
}
