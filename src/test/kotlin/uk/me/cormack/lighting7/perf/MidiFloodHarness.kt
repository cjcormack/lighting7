package uk.me.cormack.lighting7.perf

import dev.atsushieno.ktmidi.LibreMidiAccess
import dev.atsushieno.ktmidi.MidiOutput
import dev.atsushieno.ktmidi.MidiTransportProtocol
import dev.atsushieno.ktmidi.PortCreatorContext
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import java.io.BufferedWriter
import java.io.FileWriter
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test

/**
 * MIDI flood harness — drives the backend's `cueEdit` fader hot path under realistic
 * sustained load.
 *
 * Phase B item 4 of the follow-up drain plan; companion to `EffectStormHarness`.
 * Referenced by `FU-PERF-COALESCE-WRITES` (the headline target — 100 Hz fader CC traffic
 * runs through `CueEditSessionHandler.setPropertyForSession` per event, with a Hikari
 * borrow + Exposed transaction + Layer 3 republish each time) and by `FU-MANUAL-SUSPEND-PATH`.
 *
 * Opens a libremidi virtual output port with the same shape as
 * `src/main/kotlin/uk/me/cormack/lighting7/midi/KtmidiAccessSource.kt`, so the running
 * backend's `MidiDeviceRegistry` enumeration polls pick it up and auto-open it as a normal
 * input device. Once paired with a control surface in the backend's UI, every CC emitted
 * here drives the bound property. The harness is pure client — backend-side cost lives in
 * the histograms wired up by Phase B items 1 and 2 (not built here; flagged in the run
 * summary).
 *
 * # Run-book
 *
 * 1. Boot the backend (`./gradlew run`) in a separate process.
 * 2. In the React frontend, open a cue for edit (Live or Blind), bind a fader on a learned
 *    control surface to a fixture's `dimmer` (or `uv` — anything that round-trips through
 *    `setPropertyForSession`).
 * 3. Launch the harness — it registers a virtual port named `lighting7-flood` (override with
 *    `--port-name`). The backend's poll loop (~1 s interval) discovers it; you'll need to
 *    re-bind the fader to the harness device or re-route the binding through the virtual
 *    surface in the UI. The harness emits on channel 1, CC 1 by default — matching fader 1
 *    of the X-Touch Compact Standard profile.
 * 4. Watch the backend's `setPropertyForSession` histograms (Phase B item 1 — not yet built;
 *    until then, JFR or `jstack` mid-run). The CSV at `--out` exists only for log-correlation
 *    after the run; CC values + emit timestamps, no client-side latency measurement (the
 *    interesting numbers are all backend-side).
 *
 * # Invocation
 *
 * Via the test runner (gated by `-Dmidi.flood=true` so it doesn't fire on a normal `gradlew
 * test` run):
 *
 * ```
 * ./gradlew test --tests "uk.me.cormack.lighting7.perf.MidiFloodHarness" \
 *     -Dmidi.flood=true \
 *     -Dmidi.flood.args="--profile=ramp --duration-sec=30"
 * ```
 *
 * Or as a standalone JVM against the test classpath (the application plugin's `run` task is
 * pinned to `ApplicationKt`, and adding a second `run`-style task would touch `build.gradle.kts`
 * — out of scope for this harness; use the test runner or a hand-rolled `JavaExec`):
 *
 * ```
 * ./gradlew testClasses
 * java -cp "$(./gradlew -q :testRuntimeClasspath 2>/dev/null || \
 *     find build/classes -type d | tr '\n' ':')" \
 *     uk.me.cormack.lighting7.perf.MidiFloodHarnessKt \
 *     --profile=wiggle --rate=100 --duration-sec=60
 * ```
 *
 * `--help` prints CLI usage and exits 0 without touching the MIDI subsystem (so it works on
 * a CI host without libremidi natives).
 */
class MidiFloodHarness {

    private companion object {
        const val FLAG = "midi.flood"
        const val ARGS_FLAG = "midi.flood.args"
    }

    @Test
    fun `flood virtual midi port`() {
        Assume.assumeTrue(
            "Set -D$FLAG=true to run the MIDI flood harness",
            System.getProperty(FLAG) == "true",
        )
        val rawArgs = System.getProperty(ARGS_FLAG, "")
        val args = if (rawArgs.isBlank()) emptyArray() else rawArgs.trim().split(Regex("\\s+")).toTypedArray()
        runMidiFloodHarness(args)
    }
}

fun main(args: Array<String>) {
    runMidiFloodHarness(args)
}

internal fun runMidiFloodHarness(args: Array<String>) {
    val cli = parseMidiFloodArgs(args) ?: return
    println(
        "[midi-flood] profile=${cli.profile} rate=${cli.rateHz} Hz channel=${cli.channel} " +
            "cc=${cli.cc} duration=${cli.durationSec} s port-name=${cli.portName} out=${cli.outPath}",
    )

    val access = LibreMidiAccess.create(MidiTransportProtocol.MIDI1)
    val ctx = PortCreatorContext(
        applicationName = "lighting7-perf",
        portName = cli.portName,
        manufacturer = "lighting7",
        version = "0.0.1",
        midiProtocol = MidiTransportProtocol.MIDI1,
    )
    if (!access.canCreateVirtualPort(ctx)) {
        System.err.println("[midi-flood] platform does not support virtual MIDI ports")
        return
    }

    val output: MidiOutput = runBlocking { access.createVirtualInputSender(ctx) }

    BufferedWriter(FileWriter(cli.outPath.toFile())).use { writer ->
        writer.write("timestamp_ns,cc_value\n")
        writer.flush()
        runFloodLoop(output, writer, cli)
    }

    runCatching { output.close() }
    println("[midi-flood] done — wrote ${cli.outPath}")
}

private fun runFloodLoop(output: MidiOutput, writer: BufferedWriter, cli: MidiFloodArgs) {
    val statusByte = (0xB0 or (cli.channel - 1)).toByte()
    val ccByte = cli.cc.toByte()
    val msg = byteArrayOf(statusByte, ccByte, 0)
    val periodNs = 1_000_000_000L / cli.rateHz
    val startNs = System.nanoTime()
    val endNs = startNs + cli.durationSec.toLong() * 1_000_000_000L

    var lastEmitted = -1
    val shutdown = Thread.currentThread()
    val hook = Thread { shutdown.interrupt() }
    Runtime.getRuntime().addShutdownHook(hook)
    try {
        var n = 0L
        while (System.nanoTime() < endNs) {
            val tNs = System.nanoTime()
            val phase = (tNs - startNs).toDouble() / 1_000_000_000.0
            val value = profileValue(cli.profile, phase).coerceIn(0, 127)
            if (value != lastEmitted) {
                msg[2] = value.toByte()
                output.send(msg, 0, msg.size, 0L)
                writer.write("$tNs,$value\n")
                writer.flush()
                lastEmitted = value
            }
            n++
            val nextNs = startNs + n * periodNs
            val sleepNs = nextNs - System.nanoTime()
            if (sleepNs > 0) {
                try {
                    val ms = sleepNs / 1_000_000
                    val rem = (sleepNs % 1_000_000).toInt()
                    Thread.sleep(ms, rem)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            } else if (Thread.interrupted()) {
                break
            }
        }
        println("[midi-flood] emitted $n schedule slots over ${cli.durationSec} s")
    } finally {
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }
}

private fun profileValue(profile: FloodProfile, phaseSec: Double): Int = when (profile) {
    FloodProfile.RAMP -> {
        val cyclic = (phaseSec % 2.0) / 2.0
        val v = if (cyclic < 0.5) cyclic * 2.0 else (1.0 - cyclic) * 2.0
        (v * 127.0).roundToInt()
    }
    FloodProfile.WIGGLE -> (64.0 + 20.0 * sin(2.0 * PI * phaseSec)).roundToInt()
    FloodProfile.BURST -> {
        val pos = phaseSec % 1.5
        if (pos < 1.0) ((pos % 0.2) / 0.2 * 127.0).roundToInt() else 0
    }
}

internal enum class FloodProfile { RAMP, WIGGLE, BURST }

internal data class MidiFloodArgs(
    val profile: FloodProfile,
    val rateHz: Int,
    val channel: Int,
    val cc: Int,
    val durationSec: Int,
    val portName: String,
    val outPath: Path,
)

private fun printMidiFloodUsage() {
    println(
        """
        |Usage: MidiFloodHarness [OPTIONS]
        |
        |Drives the backend's cueEdit fader hot path via a libremidi virtual MIDI port.
        |Boot the backend separately, bind a fader to a property in the UI, then launch.
        |
        |Options:
        |  --profile=<ramp|wiggle|burst>  Traffic shape (default: ramp)
        |  --rate=<int>                   CC events per second (default: 100)
        |  --channel=<1-16>               MIDI channel, 1-indexed (default: 1)
        |  --cc=<0-127>                   CC number (default: 1, X-Touch fader 1)
        |  --duration-sec=<int>           Run length in seconds (default: 60)
        |  --port-name=<string>           Virtual port name shown to the OS (default: lighting7-flood)
        |  --out=<path>                   CSV output path (default: /tmp/midi-flood.csv)
        |  --help                         Show this message and exit 0
        |
        |Backend-side measurement is out of scope here. Phase B items 1 and 2
        |(setPropertyForSession histograms, per-universe ArtNet packet counters) provide
        |the matching server-side numbers; this CSV exists only to correlate emit times
        |with backend logs.
        """.trimMargin(),
    )
}

internal fun parseMidiFloodArgs(args: Array<String>): MidiFloodArgs? {
    var profile = FloodProfile.RAMP
    var rate = 100
    var channel = 1
    var cc = 1
    var duration = 60
    var portName = "lighting7-flood"
    var out = Path.of("/tmp/midi-flood.csv")

    for (arg in args) {
        when {
            arg == "--help" || arg == "-h" -> {
                printMidiFloodUsage()
                return null
            }
            arg.startsWith("--profile=") -> profile = FloodProfile.valueOf(arg.substringAfter("=").uppercase())
            arg.startsWith("--rate=") -> rate = arg.substringAfter("=").toInt()
            arg.startsWith("--channel=") -> channel = arg.substringAfter("=").toInt()
            arg.startsWith("--cc=") -> cc = arg.substringAfter("=").toInt()
            arg.startsWith("--duration-sec=") -> duration = arg.substringAfter("=").toInt()
            arg.startsWith("--port-name=") -> portName = arg.substringAfter("=")
            arg.startsWith("--out=") -> out = Path.of(arg.substringAfter("="))
            else -> {
                System.err.println("Unknown argument: $arg")
                printMidiFloodUsage()
                return null
            }
        }
    }
    require(channel in 1..16) { "channel must be 1..16, was $channel" }
    require(cc in 0..127) { "cc must be 0..127, was $cc" }
    require(rate in 1..1000) { "rate must be 1..1000, was $rate" }
    require(duration > 0) { "duration must be > 0, was $duration" }
    return MidiFloodArgs(profile, rate, channel, cc, duration, portName, out)
}
