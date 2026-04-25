package uk.me.cormack.lighting7.perf

import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import uk.me.cormack.lighting7.plugins.CueEditBeginEditInMessage
import uk.me.cormack.lighting7.plugins.CueEditEndEditInMessage
import uk.me.cormack.lighting7.plugins.CueEditSessionEndedOutMessage
import uk.me.cormack.lighting7.plugins.CueEditSessionStartedOutMessage
import uk.me.cormack.lighting7.plugins.CueEditSetPropertyInMessage
import uk.me.cormack.lighting7.plugins.InMessage
import uk.me.cormack.lighting7.plugins.OutMessage
import uk.me.cormack.lighting7.routes.CreatePatchRequest
import uk.me.cormack.lighting7.routes.CueDetails
import uk.me.cormack.lighting7.routes.NewCue
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals

/**
 * Profile harness for the cueEdit set-property hot path. Drives a flood of
 * `cueEdit.setProperty` WS messages and reads `lastSessionEnded` from
 * `GET /api/rest/perf/cueedit-histogram` to extract p50/p95/p99 latency.
 *
 * Three sub-profiles run back-to-back inside one session — slider ramp,
 * slider wiggle (worst case for the upsert delta filter), colour ramp
 * (exercises the `ExtendedColour.toSerializedString` allocation path).
 *
 * Skipped by default; opt in via `-Dcueedit.profile=true`. Output is
 * stdout-only — no fail-on-threshold; this is a measurement harness, not a
 * regression gate.
 */
class CueEditProfileTest : RouteIntegrationTest() {

    @get:Rule
    override val testTimeout: Timeout = Timeout.seconds(300)

    @Test
    fun `profile cueEdit setProperty flood`() {
        Assume.assumeTrue(
            "Set -D$PROFILE_FLAG=true to run the cueEdit profile harness",
            System.getProperty(PROFILE_FLAG) == "true",
        )

        testApplication {
            mountTestApp(state)
            val client = createWsClient()

            val patchResp = client.post("/api/rest/project/$projectId/patches") {
                contentType(ContentType.Application.Json)
                setBody(
                    CreatePatchRequest(
                        universe = 0,
                        fixtureTypeKey = "hex",
                        key = "hex-1",
                        name = "Hex 1",
                        startChannel = 1,
                    ),
                )
            }
            assertEquals(HttpStatusCode.Created, patchResp.status, "patches body: ${patchResp.bodyAsText()}")

            val cueResp = client.post("/api/rest/project/$projectId/cues") {
                contentType(ContentType.Application.Json)
                setBody(NewCue(name = "profile-cue"))
            }
            assertEquals(HttpStatusCode.Created, cueResp.status, "cue body: ${cueResp.bodyAsText()}")
            val cueId = cueResp.body<CueDetails>().id

            client.webSocket("/api") {
                sendSerialized<InMessage>(CueEditBeginEditInMessage(cueId, "LIVE"))
                awaitOfType<CueEditSessionStartedOutMessage>()

                // Drain inbound acks concurrently — without it the server's outbound queue
                // backpressures the WS write side mid-flood. Signal sessionEnded via a
                // deferred so the post-flood handshake doesn't race the in-flight backlog.
                val sessionEnded = CompletableDeferred<Unit>()
                val drainJob = launch {
                    try {
                        while (!sessionEnded.isCompleted) {
                            val msg = receiveDeserialized<OutMessage>()
                            if (msg is CueEditSessionEndedOutMessage) {
                                sessionEnded.complete(Unit)
                                break
                            }
                        }
                    } catch (e: Exception) {
                        sessionEnded.completeExceptionally(e)
                    }
                }

                runProfile("ramp", RAMP_EVENTS) { i ->
                    setDimmer(cueId, value = (i / 16) % 256)
                }
                runProfile("wiggle", WIGGLE_EVENTS) { i ->
                    // Alternating endpoints — worst case for the per-channel delta filter
                    // downstream and exercises tight-loop SQL upsert with constant churn.
                    setDimmer(cueId, value = if (i and 1 == 0) 32 else 224)
                }
                runProfile("colour-ramp", COLOUR_EVENTS) { i ->
                    setColour(cueId, hue = (i % 360))
                }

                sendSerialized<InMessage>(CueEditEndEditInMessage(cueId))
                withTimeout(DRAIN_TIMEOUT_MS) { sessionEnded.await() }
                drainJob.join()
            }

            val histResp = client.get("/api/rest/perf/cueedit-histogram")
            assertEquals(HttpStatusCode.OK, histResp.status)
            val snapshot = histResp.body<CueEditHistogramSnapshot>()
            val ended = snapshot.lastSessionEnded
                ?: error("expected lastSessionEnded to be populated after endEdit")

            println("[cueedit/all] $TOTAL_EVENTS events sent across ramp + wiggle + colour-ramp")
            println(
                "[cueedit/all] count=${ended.count} " +
                    "p50=${ended.p50Nanos / 1_000}µs p95=${ended.p95Nanos / 1_000}µs " +
                    "p99=${ended.p99Nanos / 1_000}µs max=${ended.maxNanos / 1_000}µs " +
                    "mean=${ended.meanNanos / 1_000}µs",
            )
            for (b in ended.buckets.filter { it.count > 0 }) {
                println("[cueedit/all]   <=${b.upperBoundNanos / 1_000}µs : ${b.count}")
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.runProfile(
        label: String,
        events: Int,
        send: suspend (Int) -> Unit,
    ) {
        val start = System.nanoTime()
        repeat(events) { i -> send(i) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        println("[cueedit/$label] events=$events wallClockMs=$elapsedMs")
    }

    private suspend fun DefaultClientWebSocketSession.setDimmer(cueId: Int, value: Int) {
        sendSerialized<InMessage>(
            CueEditSetPropertyInMessage(
                cueId = cueId,
                targetType = "fixture",
                targetKey = "hex-1",
                propertyName = "dimmer",
                value = value.toString(),
            ),
        )
    }

    private suspend fun DefaultClientWebSocketSession.setColour(cueId: Int, hue: Int) {
        val (r, g, b) = hsvToRgb(hue)
        val hex = "#%02x%02x%02x".format(r, g, b)
        sendSerialized<InMessage>(
            CueEditSetPropertyInMessage(
                cueId = cueId,
                targetType = "fixture",
                targetKey = "hex-1",
                propertyName = "rgbColour",
                value = hex,
            ),
        )
    }

    private companion object {
        const val PROFILE_FLAG = "cueedit.profile"
        const val RAMP_EVENTS = 2_000
        const val WIGGLE_EVENTS = 2_000
        const val COLOUR_EVENTS = 1_000
        const val TOTAL_EVENTS = RAMP_EVENTS + WIGGLE_EVENTS + COLOUR_EVENTS
        const val DRAIN_TIMEOUT_MS = 180_000L

        private fun hsvToRgb(hue: Int): Triple<Int, Int, Int> {
            val h = (hue.mod(360)) / 60
            val f = ((hue.mod(360)) % 60) * 255 / 60
            val p = 0
            val q = 255 - f
            val t = f
            return when (h) {
                0 -> Triple(255, t, p)
                1 -> Triple(q, 255, p)
                2 -> Triple(p, 255, t)
                3 -> Triple(p, q, 255)
                4 -> Triple(t, p, 255)
                else -> Triple(255, p, q)
            }
        }
    }
}
