package uk.me.cormack.lighting7.perf

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assume
import uk.me.cormack.lighting7.fx.BeatDivision
import uk.me.cormack.lighting7.fx.BlendMode
import uk.me.cormack.lighting7.fx.FxOutputType
import uk.me.cormack.lighting7.routes.AddEffectRequest
import uk.me.cormack.lighting7.routes.AddEffectResponse
import java.io.BufferedWriter
import java.io.FileWriter
import java.nio.file.Path
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlin.test.Test

/**
 * Effect-storm harness — drives the backend's FX add/remove hot path via REST.
 *
 * Phase B item 4 of the follow-up drain plan; companion to `MidiFloodHarness`.
 * Referenced by `FU-PERF-FRAME-TXN-UNIFY` (sustained add/remove churn raises the per-universe
 * ArtNet packet rate; the trigger condition for that follow-up is "> ~40 pkts/sec per
 * universe under effect load") and by `FU-MANUAL-SUSPEND-PATH` (Phase 8 sanity check —
 * 100 effects/sec while a fader runs the same property).
 *
 * Operationally: sustains 100 add/remove ops/sec by default. Each iteration randomises an
 * effect type from `/api/rest/fx/library` (filtered to dimmer/colour effects) and a target
 * fixture from a pre-configured pool, POSTs `/api/rest/fx/add`, waits for the response,
 * sleeps a random 0–200 ms, then DELETEs the effect by ID. A small in-flight pool keeps the
 * sustained rate independent of HTTP roundtrip jitter.
 *
 * Client-side latency *is* recorded here (unlike the MIDI harness): `/fx/add`'s HTTP
 * roundtrip is the operator-facing metric when scripts drive effect lifecycle, so the
 * emit-time-vs-ack-time delta is meaningful.
 *
 * # Run-book
 *
 * 1. Boot the backend (`./gradlew run`) in a separate process.
 * 2. Patch a project with at least 4 fixtures (8+ recommended) — DMX, any type that exposes
 *    a `dimmer` slider works. Either pass the keys via `--fixtures=key1,key2,...`, or omit
 *    the flag and the harness will GET `/api/rest/fixture/list` and pick the first 8.
 * 3. Launch the harness against `http://localhost:8413` (override with `--url`). Defaults:
 *    100 ops/sec for 60 s.
 * 4. The CSV at `--out` lands one row per HTTP call: emit timestamp, op (add/remove),
 *    effect id (or empty on add-failure), client-side latency in ms, HTTP status. Use it
 *    to build histograms / spot tail latencies after the run.
 *
 * # Invocation
 *
 * Via the test runner (gated by `-Dfx.storm=true`):
 *
 * ```
 * ./gradlew test --tests "uk.me.cormack.lighting7.perf.EffectStormHarness" \
 *     -Dfx.storm=true \
 *     -Dfx.storm.args="--duration-sec=30 --rate=100 --fixtures=hex-1,hex-2,hex-3,hex-4"
 * ```
 *
 * Or as a standalone JVM against the test classpath (the application plugin's `run` task is
 * pinned to `ApplicationKt`; adding a second `run`-style task would touch `build.gradle.kts`
 * and is out of scope here — use the test runner or a hand-rolled `JavaExec`):
 *
 * ```
 * ./gradlew testClasses
 * java -cp "$(find build/classes -type d | tr '\n' ':')" \
 *     uk.me.cormack.lighting7.perf.EffectStormHarnessKt \
 *     --url=http://localhost:8413 --duration-sec=60 --rate=100
 * ```
 *
 * `--help` prints CLI usage and exits 0 without touching the network (so it works on a CI
 * host without a backend).
 */
class EffectStormHarness {

    private companion object {
        const val FLAG = "fx.storm"
        const val ARGS_FLAG = "fx.storm.args"
    }

    @Test
    fun `storm fx add and remove`() {
        Assume.assumeTrue(
            "Set -D$FLAG=true to run the FX storm harness",
            System.getProperty(FLAG) == "true",
        )
        val rawArgs = System.getProperty(ARGS_FLAG, "")
        val args = if (rawArgs.isBlank()) emptyArray() else rawArgs.trim().split(Regex("\\s+")).toTypedArray()
        runEffectStormHarness(args)
    }
}

fun main(args: Array<String>) {
    runEffectStormHarness(args)
}

internal fun runEffectStormHarness(args: Array<String>) {
    val cli = parseEffectStormArgs(args) ?: return
    println(
        "[fx-storm] url=${cli.baseUrl} rate=${cli.rateOpsPerSec} ops/s duration=${cli.durationSec} s " +
            "concurrency=${cli.concurrency} out=${cli.outPath}",
    )

    runBlocking {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        try {
            val library = fetchLibrary(client, cli.baseUrl)
            val sliderEffects = library.filter {
                it.outputType == FxOutputType.SLIDER.name && "dimmer" in it.compatibleProperties
            }
            require(sliderEffects.isNotEmpty()) {
                "No ${FxOutputType.SLIDER.name} effects with dimmer compatibility found in /fx/library"
            }
            val fixtures = cli.fixtures.ifEmpty { fetchFixtures(client, cli.baseUrl).take(8) }
            require(fixtures.isNotEmpty()) {
                "No fixtures available — pass --fixtures or patch some on the backend first"
            }
            println(
                "[fx-storm] catalog=${sliderEffects.size} dimmer effects, " +
                    "pool=${fixtures.size} fixtures (${fixtures.take(4).joinToString()}…)",
            )

            BufferedWriter(FileWriter(cli.outPath.toFile())).use { writer ->
                writer.write("timestamp_ns,op,effect_id,http_latency_ms,http_status\n")
                writer.flush()
                runStormLoop(client, cli, sliderEffects, fixtures, writer)
            }
        } finally {
            client.close()
        }
    }
    println("[fx-storm] done — wrote ${cli.outPath}")
}

private suspend fun runStormLoop(
    client: HttpClient,
    cli: EffectStormArgs,
    effects: List<EffectLibraryEntry>,
    fixtures: List<String>,
    writer: BufferedWriter,
) = coroutineScope {
    val periodNs = 1_000_000_000L / cli.rateOpsPerSec
    val startNs = System.nanoTime()
    val endNs = startNs + cli.durationSec.toLong() * 1_000_000_000L
    val rng = Random(System.nanoTime())

    // Bounded in-flight pool keeps add+remove pairs decoupled from the issue rate, so an
    // unlucky HTTP stall doesn't collapse throughput.
    val workSlots = Channel<Unit>(capacity = cli.concurrency)
    repeat(cli.concurrency) { workSlots.send(Unit) }

    val writeLock = Any()
    fun writeRow(row: String) = synchronized(writeLock) {
        writer.write(row)
        writer.flush()
    }

    val job = coroutineContext[Job]!!
    val hook = Thread { job.cancel(CancellationException("Shutdown signal")) }
    Runtime.getRuntime().addShutdownHook(hook)
    try {
        var n = 0L
        while (System.nanoTime() < endNs) {
            workSlots.receive()
            val effect = effects.random(rng)
            val fixture = fixtures.random(rng)
            launch(Dispatchers.IO) {
                try {
                    runOneAddRemove(client, cli.baseUrl, effect, fixture, rng, ::writeRow)
                } finally {
                    workSlots.send(Unit)
                }
            }
            n++
            val nextNs = startNs + n * periodNs
            val sleepNs = nextNs - System.nanoTime()
            if (sleepNs > 0) delay(sleepNs / 1_000_000)
        }
        println("[fx-storm] issued $n add ops; draining in-flight…")
        repeat(cli.concurrency) { workSlots.receive() }
    } finally {
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }
}

private suspend fun runOneAddRemove(
    client: HttpClient,
    baseUrl: String,
    effect: EffectLibraryEntry,
    fixtureKey: String,
    rng: Random,
    writeRow: (String) -> Unit,
) {
    val addEmitNs = System.nanoTime()
    var effectId: Long? = null
    var addStatus = 0
    val addLatencyMs = measureNanoTime {
        try {
            val resp = client.post("$baseUrl/api/rest/fx/add") {
                contentType(ContentType.Application.Json)
                setBody(
                    AddEffectRequest(
                        effectType = effect.name,
                        fixtureKey = fixtureKey,
                        propertyName = "dimmer",
                        beatDivision = BeatDivision.EIGHTH,
                        blendMode = BlendMode.OVERRIDE.name,
                    ),
                )
            }
            addStatus = resp.status.value
            if (resp.status.isSuccess()) {
                effectId = resp.body<AddEffectResponse>().effectId
            } else {
                // CIO needs the body consumed so the connection returns to the pool.
                runCatching { resp.bodyAsText() }
            }
        } catch (t: Throwable) {
            addStatus = -1
            System.err.println("[fx-storm] add failed: ${t.message}")
        }
    } / 1_000_000
    writeRow("$addEmitNs,add,${effectId ?: ""},$addLatencyMs,$addStatus\n")

    val id = effectId ?: return
    delay(rng.nextLong(50, 200))

    val rmEmitNs = System.nanoTime()
    var rmStatus = 0
    val rmLatencyMs = measureNanoTime {
        try {
            val resp = client.delete("$baseUrl/api/rest/fx/$id")
            rmStatus = resp.status.value
            runCatching { resp.bodyAsText() }
        } catch (t: Throwable) {
            rmStatus = -1
            System.err.println("[fx-storm] remove failed: ${t.message}")
        }
    } / 1_000_000
    writeRow("$rmEmitNs,remove,$id,$rmLatencyMs,$rmStatus\n")
}

private suspend fun fetchLibrary(client: HttpClient, baseUrl: String): List<EffectLibraryEntry> {
    val resp = client.get("$baseUrl/api/rest/fx/library")
    require(resp.status.isSuccess()) { "GET /fx/library returned ${resp.status}" }
    return resp.body()
}

private suspend fun fetchFixtures(client: HttpClient, baseUrl: String): List<String> {
    val resp = client.get("$baseUrl/api/rest/fixture/list")
    require(resp.status.isSuccess()) { "GET /fixture/list returned ${resp.status}" }
    val items: List<FixtureSummary> = resp.body()
    return items.map { it.key }
}

@Serializable
internal data class EffectLibraryEntry(
    val name: String,
    val category: String,
    val outputType: String,
    val compatibleProperties: List<String> = emptyList(),
)

@Serializable
internal data class FixtureSummary(val key: String)

internal data class EffectStormArgs(
    val baseUrl: String,
    val rateOpsPerSec: Int,
    val durationSec: Int,
    val concurrency: Int,
    val fixtures: List<String>,
    val outPath: Path,
)

private fun printEffectStormUsage() {
    println(
        """
        |Usage: EffectStormHarness [OPTIONS]
        |
        |Drives the backend's FX add/remove REST endpoints to sustain effect-lifecycle load.
        |Boot the backend separately and patch some fixtures, then launch.
        |
        |Options:
        |  --url=<base-url>             Backend base URL (default: http://localhost:8413)
        |  --rate=<int>                 add+remove ops issued per second (default: 100)
        |  --duration-sec=<int>         Run length in seconds (default: 60)
        |  --concurrency=<int>          Max in-flight HTTP calls (default: 16)
        |  --fixtures=<key1,key2,...>   Fixture pool. Omit to GET /fixture/list and take 8.
        |  --out=<path>                 CSV output path (default: /tmp/effect-storm.csv)
        |  --help                       Show this message and exit 0
        |
        |Backend-side packet/transaction counters (Phase B items 1 and 2) are out of scope
        |here — the CSV records client-side HTTP roundtrip latency, which is the operator-
        |facing metric when scripts drive effect lifecycle.
        """.trimMargin(),
    )
}

internal fun parseEffectStormArgs(args: Array<String>): EffectStormArgs? {
    var baseUrl = "http://localhost:8413"
    var rate = 100
    var duration = 60
    var concurrency = 16
    var fixtures = emptyList<String>()
    var out = Path.of("/tmp/effect-storm.csv")

    for (arg in args) {
        when {
            arg == "--help" || arg == "-h" -> {
                printEffectStormUsage()
                return null
            }
            arg.startsWith("--url=") -> baseUrl = arg.substringAfter("=").trimEnd('/')
            arg.startsWith("--rate=") -> rate = arg.substringAfter("=").toInt()
            arg.startsWith("--duration-sec=") -> duration = arg.substringAfter("=").toInt()
            arg.startsWith("--concurrency=") -> concurrency = arg.substringAfter("=").toInt()
            arg.startsWith("--fixtures=") -> fixtures = arg.substringAfter("=").split(",").filter { it.isNotBlank() }
            arg.startsWith("--out=") -> out = Path.of(arg.substringAfter("="))
            else -> {
                System.err.println("Unknown argument: $arg")
                printEffectStormUsage()
                return null
            }
        }
    }
    require(rate in 1..1000) { "rate must be 1..1000, was $rate" }
    require(duration > 0) { "duration must be > 0, was $duration" }
    require(concurrency in 1..256) { "concurrency must be 1..256, was $concurrency" }
    return EffectStormArgs(baseUrl, rate, duration, concurrency, fixtures, out)
}
