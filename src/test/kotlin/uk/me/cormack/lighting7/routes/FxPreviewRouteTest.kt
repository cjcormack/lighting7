package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.Fx
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxSampleClock
import uk.me.cormack.lighting7.fx.FxSampleRequest
import uk.me.cormack.lighting7.fx.FxSampleSeries
import uk.me.cormack.lighting7.fx.FxSampleWindow
import uk.me.cormack.lighting7.fx.FxSamples
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.createFxWithPalettes
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The FX preview route (FX editor plan §3.3, session 1b): what it answers is the sampler's own
 * samples for the same FX, window and clock — the route adds no maths — and asking writes nothing.
 * The sampler's agreement with the stage is `FxSamplerParityTest`'s.
 *
 * The parity cases drive [previewFx] on a clock held still at tick 0, 20 ms a tick (125 BPM), so the
 * sampler can be asked the same question; the wire's cases go through HTTP.
 */
class FxPreviewRouteTest : RouteIntegrationTest() {

    private val engine get() = state.show.fxEngine

    private val clock = FxSampleClock(ticks = { 0L }, msPerTicks = { 20.0 }, rateScales = { 1.0 })

    private fun seedHexes(count: Int, group: String = "quad"): List<String> {
        val keys = (0 until count).map { "hex-$it" }
        for ((i, key) in keys.withIndex()) LocateTestSupport.seedHex(state, projectId, key, 1 + 12 * i)
        LocateTestSupport.seedGroup(state, projectId, group, *keys.toTypedArray())
        return keys
    }

    private fun group(name: String) = listOf(CueTargetDto(TargetRef.Group.TYPE, name))

    private fun fixture(key: String) = listOf(CueTargetDto(TargetRef.Fixture.TYPE, key))

    private fun fresh(instance: FxInstance): Fx =
        state.show.fxRegistry.createFxWithPalettes(state.show.paletteRegistry, instance.fxTypeId, instance.effect.parameters)

    private fun running(request: AddFxRequest): FxInstance = buildFxInstance(state, request).also { engine.addFx(it) }

    private fun sine(targets: List<CueTargetDto>, parameters: Map<String, String> = emptyMap(), programmerOwned: Boolean = false) =
        AddFxRequest(fxType = "SineWave", propertyName = "dimmer", targets = targets, parameters = parameters, programmerOwned = programmerOwned)

    private fun draft(vararg fields: Pair<String, Any>): JsonObject = buildJsonObject {
        for ((name, value) in fields) {
            when (value) {
                is String -> put(name, value)
                is Number -> put(name, JsonPrimitive(value))
                is Boolean -> put(name, value)
                is Map<*, *> -> putJsonObject(name) { value.forEach { (k, v) -> put(k as String, v as String) } }
                is GroupTargets -> putJsonArray(name) { addJsonObject { put("type", "group"); put("key", value.name) } }
                else -> error("no encoding for $value")
            }
        }
    }

    /** A draft's `targets`: one group. */
    private class GroupTargets(val name: String)

    private fun preview(request: FxPreviewRequest): FxPreviewResponse =
        assertIs<FxPreviewOutcome.Done>(previewFx(state, request, clock), "the preview is answered").response

    private fun sampled(fx: List<FxInstance>, include: FxSampleRequest.Include, rows: List<String>, window: FxSampleWindow = FxSampleWindow.Cycles(2.0)): FxSamples =
        engine.sampler(::fresh).sample(FxSampleRequest(fx, include, rows, window), clock)

    private fun bytes(base64: String?): ByteArray = Base64.getDecoder().decode(assertNotNull(base64))

    /** [series] as the answer carries it, plane by plane. */
    private fun assertSeries(expected: FxSampleSeries, actual: FxPreviewSeries, why: String) {
        when (expected) {
            is FxSampleSeries.Bytes -> assertContentEquals(expected.values, bytes(actual.planes["level"]), why)
            is FxSampleSeries.Colour -> {
                assertContentEquals(expected.red, bytes(actual.planes["red"]), "$why red")
                assertContentEquals(expected.green, bytes(actual.planes["green"]), "$why green")
                assertContentEquals(expected.blue, bytes(actual.planes["blue"]), "$why blue")
            }
            is FxSampleSeries.Position -> {
                assertContentEquals(expected.pan, bytes(actual.planes["pan"]), "$why pan")
                assertContentEquals(expected.tilt, bytes(actual.planes["tilt"]), "$why tilt")
            }
            is FxSampleSeries.Steps -> {
                assertEquals(expected.indices.toList(), actual.steps, "$why steps")
                assertEquals(expected.labels, actual.stepLabels, "$why labels")
            }
        }
    }

    /** Every row's result and every FX's own output in [answer] are [samples]', byte for byte. */
    private fun assertAnswers(samples: FxSamples, answer: FxPreviewResponse) {
        assertEquals(samples.rows, answer.rows.map { it.key }, "the rows")
        assertEquals(samples.offsetsMs.size, answer.window.samples)
        assertEquals(samples.sampleMs, answer.window.sampleMs)
        val attributes = answer.lanes.flatMap { it.attributes }.associateBy { it.attribute }
        assertEquals(samples.attributes.map { it.attribute }.toSet(), attributes.keys)
        for (attribute in samples.attributes) {
            val answered = attributes.getValue(attribute.attribute)
            assertEquals(attribute.rows.size, answered.rows.size)
            for ((row, answeredRow) in attribute.rows.zip(answered.rows)) {
                val why = "${row.key}.${attribute.attribute}"
                assertEquals(row.key, answer.rows[answeredRow.row].key)
                assertSeries(row.result, answeredRow.result, "$why result")
                assertEquals(row.fx.size, answeredRow.fx.size)
                for ((output, answeredOutput) in row.fx.zip(answeredRow.fx)) {
                    assertSeries(output.output, answeredOutput.output, "$why output")
                    assertEquals(output.heldBack?.name, answeredOutput.heldBackReason)
                }
            }
        }
    }

    private fun dimmer(channel: Int = 1): Int = (state.show.fixtures.controllers.first().currentValues[channel] ?: 0u).toInt()

    /** The compose pass runs on its own clock; wait for it to commit [expected]. */
    private fun awaitDimmer(expected: Int, why: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (dimmer() != expected && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(expected, dimmer(), why)
    }

    // ── The route adds no maths ─────────────────────────────────────────────

    @Test
    fun `a running FX answers the sampler's samples`() {
        seedHexes(4)
        val sine = running(sine(group("quad")))
        val answer = preview(FxPreviewRequest(targets = group("quad"), fx = listOf(FxPreviewEntry(fxId = sine.id)), include = "ONLY"))

        assertAnswers(sampled(listOf(sine), FxSampleRequest.Include.ONLY, answer.rows.map { it.key }), answer)
        assertEquals(listOf("hex-0", "hex-1", "hex-2", "hex-3"), answer.rows.map { it.key })
        assertEquals(sine.id, answer.fx.single().fxId)
        assertEquals(0, answer.fx.single().entry)
        assertEquals("INTENSITY", answer.lanes.single().family)
        assertEquals(96, answer.window.samples, "two cycles, 48 to a cycle")
        assertEquals(8.0, answer.window.beats, "two cycles of one bar")
        assertEquals(0L, answer.clock.single().tickAtStart)
        assertEquals(480.0, answer.clock.single().msPerBeat)
    }

    @Test
    fun `a draft answers the sampler's samples for the FX adding it would build, and is never added`() {
        seedHexes(4)
        val answer = preview(
            FxPreviewRequest(
                targets = group("quad"),
                fx = listOf(FxPreviewEntry(draft = draft("fxType" to "SineWave", "propertyName" to "dimmer", "order" to "REVERSE"))),
                include = "ONLY",
            ),
        )
        val built = buildFxInstance(state, sine(group("quad")).copy(order = "REVERSE"))
        assertAnswers(sampled(listOf(built), FxSampleRequest.Include.ONLY, answer.rows.map { it.key }), answer)
        assertNull(answer.fx.single().fxId, "a draft has no id")
        assertEquals(0, answer.fx.single().entry)
        assertEquals(listOf("hex-3", "hex-2", "hex-1", "hex-0"), answer.rows.map { it.key }, "rows in its Order")
        assertEquals(listOf(3, 2, 1, 0), answer.rows.map { it.rigIndex }, "each with its place in the rig")
        assertTrue(engine.getActiveFx().isEmpty(), "nothing was added")
    }

    @Test
    fun `a running FX with a draft move answers its edited copy's samples`() {
        seedHexes(4)
        val sine = running(sine(group("quad")))
        val move = UpdateFxRequest(spread = 0.5, segments = 2, phaseOffset = 0.25)
        val answer = preview(
            FxPreviewRequest(
                targets = group("quad"),
                fx = listOf(FxPreviewEntry(fxId = sine.id, draft = draft("spread" to 0.5, "segments" to 2, "phaseOffset" to 0.25))),
                include = "ONLY",
            ),
        )
        val edit = assertIs<FxUpdateParse.Parsed>(parseFxUpdate(state, sine, move)).edit
        assertAnswers(sampled(listOf(engine.editedCopy(sine, edit)), FxSampleRequest.Include.ONLY, answer.rows.map { it.key }), answer)
        assertEquals(sine.id, answer.fx.single().fxId, "the edited copy stands for the running FX")
        assertEquals(1, answer.fx.size, "and the running FX is not sampled beside it")
    }

    @Test
    fun `a draft move previews the moved settings while the running FX and the stage stay as they were`() {
        seedHexes(1)
        val held = running(sine(fixture("hex-0"), mapOf("min" to "200", "max" to "200"), programmerOwned = true))
        awaitDimmer(200, "the running FX holds 200")
        val dynamicsBefore = held.dynamics

        val answer = preview(
            FxPreviewRequest(
                targets = fixture("hex-0"),
                fx = listOf(
                    FxPreviewEntry(fxId = held.id, draft = draft("parameters" to mapOf("min" to "50", "max" to "50"), "segments" to 2)),
                ),
            ),
        )
        val result = answer.lanes.single().attributes.single().rows.single().result
        assertTrue(bytes(result.planes["level"]).all { it.toInt() and 0xFF == 50 }, "the preview draws the moved level")

        Thread.sleep(100)
        assertEquals(200, dimmer(), "the stage still holds the running FX's level")
        val live = assertNotNull(engine.getFx(held.id))
        assertSame(held, live, "the running instance was not swapped")
        assertEquals(mapOf("min" to "200", "max" to "200"), live.effect.parameters)
        assertEquals(dynamicsBefore, live.dynamics, "its dynamics did not move")
    }

    // ── The desk check (§10) ────────────────────────────────────────────────

    @Test
    fun `a Sine Wave over a group with Spread 50 percent and Segments 2 starts its neighbours in pairs, and nothing on stage moves`() {
        val hexes = seedHexes(8, group = "eight")
        val answer = preview(
            FxPreviewRequest(
                targets = group("eight"),
                fx = listOf(
                    FxPreviewEntry(
                        draft = draft("fxType" to "SineWave", "propertyName" to "dimmer", "spread" to 0.5, "segments" to 2, "programmerOwned" to true),
                    ),
                ),
            ),
        )
        val fx = answer.fx.single()
        assertEquals(hexes, answer.rows.map { it.key })
        assertEquals(listOf(0.0, 0.0, 0.125, 0.125, 0.25, 0.25, 0.375, 0.375), fx.offsets, "Segments 2 pairs them, Spread 50 % halves the chase")
        val starts = fx.starts.map { assertNotNull(it) }
        for (pair in 0 until 4) {
            assertEquals(starts[2 * pair], starts[2 * pair + 1], "hex-${2 * pair} and its neighbour start together")
            if (pair > 0) assertNotEquals(starts[2 * pair - 1], starts[2 * pair], "each pair after the last")
        }
        assertEquals(listOf(0, 48), starts[0], "a cycle starts at the window's start and a cycle later")
        assertEquals(listOf(6, 54), starts[2], "an eighth of a cycle behind")
        assertEquals(listOf(0.0, 0.0, 1.0, 1.0, 2.0, 2.0, 3.0, 3.0), fx.ladder.segments.ranks, "the ladder's Segments step")
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0), fx.ladder.order.ranks, "the ladder's Order step")
        assertEquals(4, fx.slots)

        Thread.sleep(100)
        assertTrue(engine.getActiveFx().isEmpty(), "no FX was added")
        assertTrue((1..96).all { dimmer(it) == 0 }, "the stage did not move")
    }

    // ── What is folded ──────────────────────────────────────────────────────

    @Test
    fun `STACK folds every running FX reaching the rows and ONLY just the ones asked for`() {
        seedHexes(4)
        val under = running(sine(group("quad")))
        val elsewhere = running(sine(fixture("hex-0")).copy(propertyName = "uv", fxType = "SineWave"))
        val pulse = draft("fxType" to "Pulse", "propertyName" to "dimmer", "blendMode" to "MULTIPLY", "programmerOwned" to true)

        val stack = preview(FxPreviewRequest(targets = fixture("hex-1"), fx = listOf(FxPreviewEntry(draft = pulse))))
        assertEquals(listOf(under.id, null), stack.fx.map { it.fxId }, "the running FX under the draft, in apply order")
        assertEquals(listOf(null, 0), stack.fx.map { it.entry })
        assertTrue(stack.fx.none { it.fxId == elsewhere.id }, "an FX on a head not drawn is not folded")
        assertEquals(listOf("hex-1"), stack.rows.map { it.key }, "the rows are the selection's")

        val only = preview(FxPreviewRequest(targets = fixture("hex-1"), fx = listOf(FxPreviewEntry(draft = pulse)), include = "ONLY"))
        assertEquals(listOf(null), only.fx.map { it.fxId })
    }

    @Test
    fun `STACK draws every selected head anything paints, so an FX reaching only another selected head is folded`() {
        seedHexes(2)
        val other = running(sine(fixture("hex-1")))
        val onHex0 = draft("fxType" to "Pulse", "propertyName" to "dimmer", "fixtureKey" to "hex-0")
        val targets = fixture("hex-0") + fixture("hex-1")

        val stack = preview(FxPreviewRequest(targets = targets, fx = listOf(FxPreviewEntry(draft = onHex0))))
        assertEquals(listOf("hex-0", "hex-1"), stack.rows.map { it.key })
        assertTrue(stack.fx.any { it.fxId == other.id }, "the running FX on hex-1 is folded")

        val only = preview(FxPreviewRequest(targets = targets, fx = listOf(FxPreviewEntry(draft = onHex0)), include = "ONLY"))
        assertEquals(listOf("hex-0"), only.rows.map { it.key }, "ONLY draws the asked-for FX's heads")
    }

    @Test
    fun `the row cap cuts at a fixture's end`() {
        for (b in 0 until 9) LocateTestSupport.seedFixture(state, projectId, "led-lightbar-12-pixel-48ch", "bar-$b", 1 + 48 * b)
        LocateTestSupport.seedGroup(state, projectId, "bars", *(0 until 9).map { "bar-$it" }.toTypedArray())
        val hexes = (0 until 4).map { "hex-$it" }
        for ((i, key) in hexes.withIndex()) LocateTestSupport.seedHex(state, projectId, key, 433 + 12 * i)
        LocateTestSupport.seedGroup(state, projectId, "quad", *hexes.toTypedArray())
        val answer = preview(
            FxPreviewRequest(
                targets = group("quad") + group("bars"),
                fx = listOf(
                    FxPreviewEntry(draft = draft("fxType" to "RainbowCycle", "propertyName" to "rgbColour", "targets" to GroupTargets("quad"))),
                    FxPreviewEntry(draft = draft("fxType" to "RainbowCycle", "propertyName" to "rgbColour", "targets" to GroupTargets("bars"))),
                ),
                include = "ONLY",
            ),
        )
        assertEquals(hexes + (0 until 7).flatMap { b -> List(12) { "bar-$b" } }, answer.rows.map { it.fixtureKey }, "4 hexes and 7 whole bars: 88 rows, not 96")
        assertEquals(listOf("ROWS"), answer.truncated)
    }

    @Test
    fun `a held-back FX is reported with its reason and left out of the result`() {
        seedHexes(4)
        state.show.programmerStore.put(ProgrammerOwner.WEB, "hex-0", "dimmer", CueAssignmentResolver.PropertyValue.Slider(100u))
        val sine = running(sine(group("quad")))
        val answer = preview(FxPreviewRequest(targets = group("quad"), fx = listOf(FxPreviewEntry(fxId = sine.id))))

        val rows = answer.lanes.single().attributes.single().rows
        val held = rows.single { answer.rows[it.row].key == "hex-0" }
        assertEquals("PROGRAMMER", held.fx.single().heldBackReason)
        assertTrue(bytes(held.result.planes["level"]).all { it.toInt() and 0xFF == 100 }, "the programmer's value stands")
        assertTrue(bytes(held.fx.single().output.planes["level"]).toSet().size > 1, "while the FX's own output moves")
        assertNull(rows.single { answer.rows[it.row].key == "hex-1" }.fx.single().heldBackReason)
    }

    @Test
    fun `solo draws the lane as the picked FX alone over what is underneath`() {
        seedHexes(1)
        running(sine(fixture("hex-0"), mapOf("min" to "200", "max" to "200")))
        val pulse = draft("fxType" to "SineWave", "propertyName" to "dimmer", "parameters" to mapOf("min" to "50", "max" to "50"), "blendMode" to "MAX")
        fun result(solo: Int?) = bytes(
            preview(FxPreviewRequest(targets = fixture("hex-0"), fx = listOf(FxPreviewEntry(draft = pulse)), solo = solo))
                .lanes.single().attributes.single().rows.single().result.planes["level"],
        ).map { it.toInt() and 0xFF }.toSet()
        assertEquals(setOf(200), result(solo = null), "MAX over the running 200")
        assertEquals(setOf(50), result(solo = 0), "alone over nothing")
    }

    @Test
    fun `solo draws the rest of its family's lane as what is underneath`() {
        seedHexes(1)
        running(sine(fixture("hex-0"), mapOf("min" to "200", "max" to "200")).copy(propertyName = "uv"))
        val rainbow = draft("fxType" to "RainbowCycle", "propertyName" to "rgbColour")
        fun uv(solo: Int?): Set<Int> {
            val answer = preview(FxPreviewRequest(targets = fixture("hex-0"), fx = listOf(FxPreviewEntry(draft = rainbow)), solo = solo))
            val lane = answer.lanes.single { it.family == "COLOUR" }
            return bytes(lane.attributes.single { it.attribute == "uv" }.rows.single().result.planes["level"]).map { it.toInt() and 0xFF }.toSet()
        }
        assertEquals(setOf(200), uv(solo = null))
        assertEquals(setOf(0), uv(solo = 0), "the uv FX is not the solo FX, so its attribute shows what is underneath")
    }

    @Test
    fun `a beats window counts a wall-clock FX's beats where the answer reports them`() {
        seedHexes(1)
        val candle = draft("fxType" to "CandleFlicker", "propertyName" to "dimmer")
        val answer = preview(FxPreviewRequest(targets = fixture("hex-0"), fx = listOf(FxPreviewEntry(draft = candle)), window = FxPreviewWindow(beats = 4.0)))
        val sampleBeats = answer.window.sampleMs / 480.0
        assertTrue(answer.window.beats >= 4.0 && answer.window.beats < 4.0 + sampleBeats, "4 beats of master 1, not ${answer.window.beats}")
        assertEquals(true, answer.clock.single().seconds, "and the FX runs on its own seconds")
    }

    @Test
    fun `a split-off copy answers its carried offset and no ladder`() {
        seedHexes(4)
        val local = running(sine(group("quad"), programmerOwned = true))
        val copies = assertNotNull(splitIntoHeads(state, local.id))
        val third = copies.single { it.target.targetKey == "hex-2" }
        val fx = preview(FxPreviewRequest(targets = fixture("hex-2"), fx = listOf(FxPreviewEntry(fxId = third.id)), include = "ONLY")).fx.single()
        assertEquals(listOf(0.5), fx.offsets, "where it ran in the group")
        assertEquals(listOf(null), fx.ladder.order.ranks, "the ranks it came from are not carried")
    }

    @Test
    fun `an operator may preview on a venue`() {
        assertNull(projectWriteRefusal(ProjectKind.VENUE, isAdmin = false, rest = listOf("fx", "preview")))
        assertNotNull(projectWriteRefusal(ProjectKind.VENUE, isAdmin = false, rest = listOf("fx", "other")))
    }

    // ── Caps ────────────────────────────────────────────────────────────────

    @Test
    fun `the caps truncate and say which`() {
        for (b in 0 until 9) LocateTestSupport.seedFixture(state, projectId, "led-lightbar-12-pixel-48ch", "bar-$b", 1 + 48 * b)
        LocateTestSupport.seedGroup(state, projectId, "bars", *(0 until 9).map { "bar-$it" }.toTypedArray())
        val rainbow = draft("fxType" to "RainbowCycle", "propertyName" to "rgbColour", "fixtureKey" to "bar-0")
        val overBars = draft("fxType" to "RainbowCycle", "propertyName" to "rgbColour")

        val rows = preview(FxPreviewRequest(targets = group("bars"), fx = listOf(FxPreviewEntry(draft = overBars)), include = "ONLY"))
        assertEquals(96, rows.rows.size, "108 cells, cut at 96 rows")
        assertEquals(listOf("ROWS"), rows.truncated)
        assertEquals(
            (0 until 8).flatMap { b -> List(12) { "bar-$b" } },
            rows.rows.map { it.fixtureKey },
            "each bar's cells together, so the cap drops the last bar whole",
        )
        assertEquals((0 until 12).toList(), rows.rows.take(12).map { it.element })

        val samples = preview(
            FxPreviewRequest(targets = group("bars"), fx = listOf(FxPreviewEntry(draft = overBars)), include = "ONLY", window = FxPreviewWindow(cycles = 32.0)),
        )
        assertEquals(1024, samples.window.samples, "32 cycles would be 1536 samples")
        assertEquals(listOf("ROWS", "SAMPLES"), samples.truncated)

        val onBar1 = draft("fxType" to "RainbowCycle", "propertyName" to "rgbColour", "fixtureKey" to "bar-1")
        val nine = List(8) { FxPreviewEntry(draft = rainbow) } + FxPreviewEntry(draft = onBar1)
        val fx = preview(FxPreviewRequest(targets = group("bars"), fx = nine, include = "ONLY"))
        assertEquals(8, fx.fx.size)
        assertEquals(listOf("FX"), fx.truncated)
        assertTrue(fx.rows.none { it.fixtureKey == "bar-1" }, "the FX cut at the cap brings no rows")

        val soloPastCap = assertIs<FxPreviewOutcome.Invalid>(previewFx(state, FxPreviewRequest(targets = group("bars"), fx = nine, solo = 8), clock))
        assertTrue(soloPastCap.problems.single().startsWith("solo"), "a solo past the cap names an FX the answer would not carry")
    }

    // ── The wire ────────────────────────────────────────────────────────────

    @Test
    fun `over HTTP the answer carries the samples as base64 planes`() = testApplication {
        mountTestApp(state)
        seedHexes(4)
        val sine = running(sine(group("quad")))
        val response = jsonClient().post("/api/rest/projects/$projectId/fx/preview") {
            contentType(ContentType.Application.Json)
            setBody(FxPreviewRequest(targets = group("quad"), fx = listOf(FxPreviewEntry(fxId = sine.id)), window = FxPreviewWindow(beats = 4.0)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val answer = response.body<FxPreviewResponse>()
        assertEquals(48, answer.window.samples, "four beats of a one-bar cycle")
        val row = answer.lanes.single().attributes.single().rows.first()
        assertEquals(48, bytes(row.result.planes["level"]).size)
        assertEquals(1, bytes(row.underneath.planes["level"]).size, "underneath is one sample, held")
        assertTrue(engine.getFx(sine.id) === sine, "the running FX is untouched")
    }

    @Test
    fun `every problem is named at once`() = testApplication {
        mountTestApp(state)
        seedHexes(4)
        val sine = running(sine(group("quad")))
        val body = buildJsonObject {
            putJsonArray("targets") {
                addJsonObject { put("type", "group"); put("key", "quad") }
                addJsonObject { put("type", "group"); put("key", "frnt") }
            }
            putJsonArray("fx") {
                addJsonObject { put("fxId", 99_999) }
                addJsonObject {
                    putJsonObject("draft") {
                        put("fxType", "SineWave"); put("propertyName", "dimmer"); put("fixtureKey", "hex-0"); put("amount", 2.0)
                    }
                }
                addJsonObject { put("fxId", sine.id); putJsonObject("draft") { put("spread", 5.0) } }
                addJsonObject { putJsonObject("draft") { put("fxType", "NoSuchFx"); put("propertyName", "dimmer"); put("fixtureKey", "hex-0") } }
            }
            putJsonObject("window") { put("cycles", 1000) }
        }
        val response = jsonClient().post("/api/rest/projects/$projectId/fx/preview") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val refusal = response.body<FxPreviewRefusal>()
        assertEquals(CODE_FX_PREVIEW_INVALID, refusal.code)
        assertEquals(6, refusal.problems.size, refusal.problems.joinToString("\n"))
        assertTrue(refusal.problems.any { it.startsWith("targets[1]") && "frnt" in it })
        assertTrue(refusal.problems.any { it.startsWith("window.cycles") && "cap" in it })
        assertTrue(refusal.problems.any { it.startsWith("fx[0]") && "99999" in it })
        assertTrue(refusal.problems.any { it.startsWith("fx[1]") && "amount" in it.lowercase() })
        assertTrue(refusal.problems.any { it.startsWith("fx[2]") && "spread" in it.lowercase() })
        assertTrue(refusal.problems.any { it.startsWith("fx[3]") })
        assertEquals(1, engine.getActiveFx().size, "nothing was added")
        assertEquals(1.0, sine.dynamics.spread, "and nothing moved")
    }

    @Test
    fun `off the current project it is 409`() = testApplication {
        mountTestApp(state)
        val other = transaction(state.database) { DaoProject.new { name = "other"; isCurrent = false }.id.value }
        val response = jsonClient().post("/api/rest/projects/$other/fx/preview") {
            contentType(ContentType.Application.Json)
            setBody(FxPreviewRequest(fx = listOf(FxPreviewEntry(fxId = 1))))
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }
}
