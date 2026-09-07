package uk.me.cormack.lighting7.midi

import org.slf4j.LoggerFactory
import uk.co.xfactorylibrarians.coremidi4j.CoreMidiDeviceProvider
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sound.midi.MidiSystem

/**
 * Owns the one thread CoreMIDI will ever talk to this process on.
 *
 * CoreMIDI delivers `MIDIClientCreate`'s notification callback — and processes the setup changes
 * behind it, which is what makes `MIDIGetNumberOfSources()` move — on **the run loop that was
 * current when the process first called `MIDIClientCreate`**. Apple states the first half of that
 * in the `MIDIClientCreate` documentation; the second half is why a command-line tool that merely
 * sleeps sees the same device list forever. A headless JVM never pumps a `CFRunLoop` on any
 * thread: there is no AWT here, and the thread that boots the show goes on to do other things. So
 * every CoreMIDI-backed reading of the environment in this process was frozen at boot — libremidi's
 * observer, CoreMIDI4J's device map and `javax.sound.midi`'s own provider alike — and the
 * notification that `State` registered for could never be delivered. That is
 * `FU-MIDI-HOTPLUG-UNDETECTED`: a control surface unplugged after boot was still listed thirty
 * seconds later, and a replug attached nothing.
 *
 * [ensureStarted] fixes both halves at once, and must run **before anything else in the process
 * touches CoreMIDI** — `State.midiRegistry`'s initialiser calls it before it creates
 * `LibreMidiAccess`, whose observer is the other `MIDIClientCreate` this process makes. It starts a
 * daemon thread that (1) constructs a [CoreMidiDeviceProvider], which is what creates CoreMIDI4J's
 * client and so becomes the process's first `MIDIClientCreate`, then (2) pumps that thread's run
 * loop for the life of the JVM. Everything CoreMIDI has to say now has a loop to say it on:
 * CoreMIDI4J's `deliverCallbackToListeners` fires, `State`'s listener rescans, and the poll loop's
 * enumerations read the current setup rather than the boot-time one.
 *
 * Two things this is deliberately not:
 *
 * - **Not a timer that rebuilds `LibreMidiAccess`.** Each rebuild leaks an observer into libremidi's
 *   shared `Arena`, and a periodic one eventually stopped input on open controllers (see
 *   `State.midiRegistry`). Rebuilds stay proportional to real plug events.
 * - **Not tied to a `State`.** One process has one first `MIDIClientCreate`, so the thread is a
 *   process singleton and is never stopped; the test suite builds many `State`s per JVM and they
 *   all share it. The per-`State` listener that CoreMIDI4J holds statically is still removed in
 *   `State.shutdown()`, for the reason documented there.
 *
 * Constructing the provider on this thread also removes a silent dependency: CoreMIDI4J's
 * `addNotificationListener` never creates a client on macOS, so before this the client existed only
 * because the poll loop's *debug* branch happened to call `MidiSystem.getMidiDeviceInfo()`.
 *
 * The status this resolves to is logged once at INFO/WARN — the old code returned silently when
 * the native library was missing, which left nothing anywhere saying hot-plug had no working path.
 */
object CoreMidiHotPlug {

    /** The thread the process's first CoreMIDI client was created on, and which pumps its run loop. */
    const val THREAD_NAME = "coremidi-runloop"

    private const val START_TIMEOUT_MS = 5_000L

    /** `CFRunLoopRunInMode` returned `kCFRunLoopRunFinished`: no sources yet. Don't spin. */
    private const val IDLE_BACKOFF_MS = 250L
    private const val CF_RUN_LOOP_RUN_FINISHED = 1

    private val logger = LoggerFactory.getLogger(CoreMidiHotPlug::class.java)

    sealed class Status {
        /**
         * CoreMIDI notifications are expected: the process's first client is created on
         * [threadName], which then pumps its run loop, and `State` registers its rescan listener.
         * [confirmed] is false when client creation had not finished within [START_TIMEOUT_MS] —
         * the thread carries on regardless and logs when it completes, and the listener is still
         * registered, because a late client delivers to it just the same. Caching a poll-only
         * verdict there would have left a working notification path talking to nobody.
         */
        data class Notifications(val threadName: String, val confirmed: Boolean = true) : Status()

        /**
         * No notification will ever arrive here; the 1 Hz poll is the only detector. [reason] says
         * why, and [fallbackWanted] whether the registry should cross-check `javax.sound.midi` —
         * not on Windows, where the poll already *is* `javax.sound.midi`.
         */
        data class PollOnly(val reason: String, val fallbackWanted: Boolean) : Status()
    }

    @Volatile
    private var status: Status? = null

    /** Set when [ensureStarted] gave up waiting, so the thread can say so when it does finish. */
    @Volatile
    private var startTimedOut = false

    /**
     * Resolve the hot-plug detection path once per process and start the run-loop thread if there
     * is one to start. Idempotent; every caller after the first gets the stored answer.
     */
    @Synchronized
    fun ensureStarted(): Status {
        status?.let { return it }
        val resolved = start()
        status = resolved
        when (resolved) {
            is Status.Notifications -> if (resolved.confirmed) {
                logger.info(
                    "MIDI hot-plug detection: CoreMIDI notifications, delivered on '{}' — that thread created the " +
                        "process's first CoreMIDI client and pumps its run loop. The 1 Hz poll cross-checks " +
                        "javax.sound.midi and rebuilds only if a change goes unannounced.",
                    resolved.threadName,
                )
            } else {
                logger.warn(
                    "MIDI hot-plug detection: CoreMIDI client creation on '{}' has not completed after {} ms. " +
                        "The notification listener is registered anyway; a later line from CoreMidiHotPlug says " +
                        "whether the client came up. Until it does, plug and unplug are not noticed.",
                    resolved.threadName, START_TIMEOUT_MS,
                )
            }
            is Status.PollOnly -> if (isMacOs()) {
                logger.warn(
                    "MIDI hot-plug detection: CoreMIDI notifications unavailable — {}. Only the 1 Hz poll is " +
                        "left, and on macOS it cannot see a plug or unplug without a pumped run loop: a control " +
                        "surface attached or removed after boot will NOT be noticed.",
                    resolved.reason,
                )
            } else {
                logger.info("MIDI hot-plug detection: 1 Hz poll only — {}", resolved.reason)
            }
        }
        return resolved
    }

    private fun isWindows(): Boolean = System.getProperty("os.name")?.lowercase().orEmpty().contains("windows")

    /** True on the platform whose MIDI stack needs the run-loop thread. */
    fun isMacOs(): Boolean {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        return os.contains("mac") || os.contains("darwin")
    }

    /**
     * A cheap second reading of the MIDI environment, independent of libremidi: every
     * `javax.sound.midi` device the JDK and CoreMIDI4J enumerate, as one sortable string. The JDK's
     * own macOS provider re-reads `MIDIGetNumberOfSources()` on every call, so with the run loop
     * pumped this moves the moment a surface is plugged or pulled — which is what lets
     * [MidiDeviceRegistry]'s [HotPlugFallback] notice a change that no notification announced.
     *
     * It is an *identity* reading, not a count: the name, vendor, description and version of every
     * device go into it, so swapping one surface for another between two polls does move it. What
     * it cannot see is a change that has reverted by the time the next poll reads it — an unplug
     * and replug inside two poll intervals reads the same as no change at all, and is left to the
     * notification path.
     */
    fun javaxFingerprint(): String = MidiSystem.getMidiDeviceInfo()
        .map { "${it.name}|${it.vendor}|${it.description}|${it.version}" }
        .sorted()
        .joinToString(";")

    private fun start(): Status {
        if (isWindows()) {
            // KtmidiAccessSource routes Windows to JvmMidiAccess, which *is* javax.sound.midi and
            // re-reads the device list on every poll. Nothing to cross-check it against.
            return Status.PollOnly(reason = "javax.sound.midi enumerates live on every poll (Windows)", fallbackWanted = false)
        }
        if (!isMacOs()) {
            return Status.PollOnly(
                reason = "not macOS: libremidi's enumeration is re-read on every poll and the javax.sound.midi " +
                    "cross-check is installed; hot-plug on this platform is untested",
                fallbackWanted = true,
            )
        }
        // Resolve class initialisation (which loads the native library) on the caller's thread,
        // before the run-loop thread exists — FU-TEST-COREMIDI-INIT-DEADLOCK is what happens when
        // two threads want CoreMidiDeviceProvider's class lock during that load.
        val loaded = try {
            CoreMidiDeviceProvider.isLibraryLoaded()
        } catch (t: Throwable) {
            return Status.PollOnly("CoreMIDI4J native library failed to load: ${t.message}", fallbackWanted = true)
        }
        if (!loaded) return Status.PollOnly("CoreMIDI4J native library not loaded", fallbackWanted = true)

        val runLoop = try {
            bindRunLoop()
        } catch (t: Throwable) {
            return Status.PollOnly("CoreFoundation run loop not bindable: ${t.message}", fallbackWanted = true)
        }

        val ready = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val thread = Thread({
            val startedAt = System.nanoTime()
            try {
                // The process's first MIDIClientCreate. Everything CoreMIDI notifies about from
                // here on lands on this thread's run loop, which the loop below keeps pumping.
                CoreMidiDeviceProvider()
            } catch (t: Throwable) {
                failure.set(t)
                ready.countDown()
                if (startTimedOut) logger.warn("CoreMIDI client creation failed after the start timeout — hot-plug is down to the poll: {}", t.message)
                return@Thread
            }
            ready.countDown()
            if (startTimedOut) {
                logger.info(
                    "CoreMIDI client created on '{}' after {} ms, past the start timeout — notifications are live from here",
                    THREAD_NAME, (System.nanoTime() - startedAt) / 1_000_000,
                )
            }
            pump(runLoop)
        }, THREAD_NAME).apply { isDaemon = true }
        thread.start()

        if (!ready.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            startTimedOut = true
            return Status.Notifications(THREAD_NAME, confirmed = false)
        }
        failure.get()?.let {
            return Status.PollOnly("CoreMIDI client creation failed: ${it.message}", fallbackWanted = true)
        }
        return Status.Notifications(THREAD_NAME)
    }

    private fun pump(runLoop: RunLoop) {
        while (true) {
            val result = try {
                runLoop.runOnce(seconds = 1.0)
            } catch (t: Throwable) {
                logger.error("CoreMIDI run-loop thread failed; hot-plug notifications stop here", t)
                return
            }
            if (result == CF_RUN_LOOP_RUN_FINISHED) Thread.sleep(IDLE_BACKOFF_MS)
        }
    }

    /** `CFRunLoopRunInMode(kCFRunLoopDefaultMode, seconds, false)` for the calling thread's loop. */
    private class RunLoop(private val runInMode: MethodHandle, private val defaultMode: MemorySegment) {
        fun runOnce(seconds: Double): Int = runInMode.invokeWithArguments(defaultMode, seconds, 0.toByte()) as Int
    }

    private fun bindRunLoop(): RunLoop {
        val coreFoundation = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
            Arena.global(),
        )
        val runInMode = Linker.nativeLinker().downcallHandle(
            coreFoundation.find("CFRunLoopRunInMode").orElseThrow { IllegalStateException("CFRunLoopRunInMode not exported") },
            // SInt32 CFRunLoopRunInMode(CFRunLoopMode mode, CFTimeInterval seconds, Boolean returnAfterSourceHandled)
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_BYTE),
        )
        // kCFRunLoopDefaultMode is an exported `const CFStringRef`: the symbol is the variable, and
        // the value we pass is the pointer it holds.
        val defaultMode = coreFoundation.find("kCFRunLoopDefaultMode")
            .orElseThrow { IllegalStateException("kCFRunLoopDefaultMode not exported") }
            .reinterpret(ValueLayout.ADDRESS.byteSize())
            .get(ValueLayout.ADDRESS, 0)
        return RunLoop(runInMode, defaultMode)
    }
}
