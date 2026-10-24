package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two stacked FX of one type on one head, written into a cue that already holds them, or one of
 * them: each lands on its own child, and Remove deletes its own. Record matches an FX Include
 * brought back to the child it came from, and any other FX to the first child of its key no other
 * FX took. A Sine Wave Replacing at 200 under a Sine Wave Within at half reads 100 on Hex 1's
 * dimmer; the other way round, 200.
 */
class SameTypeFxWriteBackTest : RouteIntegrationTest() {

    private val fx get() = state.show.fxEngine

    private val hex = listOf(CueTargetDto(TargetRef.Fixture.TYPE, "hex-1"))

    private val wash = listOf(CueTargetDto(TargetRef.Group.TYPE, "wash"))

    private fun seedRig() = LocateTestSupport.seedHex(state, projectId, "hex-1", 1)

    private fun seedWash() {
        seedRig()
        LocateTestSupport.seedGroup(state, projectId, "wash", "hex-1")
    }

    private fun dimmer(): Int = (state.show.fixtures.controllers.first().currentValues[1] ?: 0u).toInt()

    /** The compose pass runs on its own clock; wait for it to commit [expected]. */
    private fun awaitDimmer(expected: Int, why: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (dimmer() != expected && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(expected, dimmer(), why)
    }

    private fun types(cueId: Int): List<Pair<String, String>> = transaction(state.database) {
        DaoCue.findById(cueId)!!.adHocEffectsInOrder().map { it.uuid.toString() to it.fxType }
    }

    /** One stored child as the test reads it: which row, its blend and its level. */
    private data class Child(val uuid: String, val blendMode: String, val level: String?)

    private fun children(cueId: Int): List<Child> = transaction(state.database) {
        DaoCue.findById(cueId)!!.adHocEffectsInOrder().map { Child(it.uuid.toString(), it.blendMode, it.parameters["max"]) }
    }

    /** A Sine Wave on Hex 1's dimmer whose min and max agree: one level whatever the phase. */
    private suspend fun HttpClient.pressSine(level: Int, blendMode: String, stack: Boolean): FxInstance =
        pressLevel("SineWave", hex, level, blendMode, stack)

    /** [effectType] on [targets]' dimmer at one level whatever the phase. */
    private suspend fun HttpClient.pressLevel(
        effectType: String,
        targets: List<CueTargetDto>,
        level: Int,
        blendMode: String,
        stack: Boolean,
    ): FxInstance {
        val response = post("/api/rest/projects/$projectId/programmer/effects") {
            contentType(ContentType.Application.Json)
            setBody(
                ProgrammerEffectRequest(
                    effectType = effectType, propertyName = "dimmer", targets = targets,
                    parameters = mapOf("min" to "$level", "max" to "$level"), blendMode = blendMode, stack = stack,
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return assertNotNull(fx.getEffect(response.body<ProgrammerEffectResponse>().effectId))
    }

    /** A Replace at [replace] with a Within at [within] stacked on it, so on top. */
    private suspend fun HttpClient.stackPair(replace: Int = 200, within: Int = 128) {
        pressSine(replace, "OVERRIDE", stack = false)
        pressSine(within, "MULTIPLY", stack = true)
        awaitDimmer(replace * within / 255, "the Within on top")
    }

    private suspend fun HttpClient.record(mode: String, cueId: Int? = null): ProgrammerRecordResponse {
        val response = post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerRecordRequest(projectId = projectId.toString(), mode = mode, cueId = cueId, name = "Stacked".takeIf { cueId == null }))
        }
        assertEquals(if (mode == "CREATE") HttpStatusCode.Created else HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun HttpClient.include(cueId: Int) {
        val response = post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerIncludeRequest(projectId = projectId.toString(), cueId = cueId))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun HttpClient.update(): ProgrammerUpdateResult {
        val response = post("/api/rest/programmer/update") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerUpdateRequest(projectId = projectId.toString()))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body<ProgrammerUpdateResponse>().results.single()
    }

    private suspend fun HttpClient.applyCue(cueId: Int) {
        val response = post("/api/rest/projects/$projectId/cues/$cueId/apply")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private fun clearStage() {
        fx.clearAllEffects()
        awaitDimmer(0, "the stage let go")
    }

    /** Record the stacked pair into a new cue, clear, and Include it back. */
    private suspend fun HttpClient.includedPair(): Pair<Int, List<Child>> {
        stackPair()
        val cueId = record("CREATE").cue.id
        val stored = children(cueId)
        assertEquals(listOf("OVERRIDE" to "200", "MULTIPLY" to "128"), stored.map { it.blendMode to it.level })
        clearStage()
        include(cueId)
        awaitDimmer(100, "Include runs both, the Within on top")
        return cueId to stored
    }

    private fun included(blendMode: String): FxInstance =
        fx.getActiveEffects().single { it.isLocalEffect && it.blendMode.name == blendMode }

    @Test
    fun `an Update that changed nothing keeps both stacked children as they were`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val (cueId, stored) = client.includedPair()

        assertEquals(0, client.update().fxWritten)
        assertEquals(stored, children(cueId))

        clearStage()
        client.applyCue(cueId)
        awaitDimmer(100, "the cue still plays the Within over the Replace")
    }

    @Test
    fun `an edit to one of the pair writes only its own child`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val (cueId, stored) = client.includedPair()

        val edited = client.put("/api/rest/fx/${included("MULTIPLY").id}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateEffectRequest(parameters = mapOf("min" to "64", "max" to "64")))
        }
        assertEquals(HttpStatusCode.OK, edited.status, edited.bodyAsText())
        awaitDimmer(50, "the edited Within")

        assertEquals(1, client.update().fxWritten)
        assertEquals(listOf(stored[0], stored[1].copy(level = "64")), children(cueId))

        clearStage()
        client.applyCue(cueId)
        awaitDimmer(50, "the cue plays the edit")
    }

    @Test
    fun `a move then Update moves the children themselves`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val (cueId, stored) = client.includedPair()

        assertTrue(fx.moveLocalEffect(included("MULTIPLY").id, aboveId = null) is FxEngine.LocalMoveOutcome.Moved)
        awaitDimmer(200, "the Replace on top")

        assertEquals(2, client.update().fxWritten)
        assertEquals(stored.reversed(), children(cueId), "each uuid with its own FX, in the moved order")

        clearStage()
        client.applyCue(cueId)
        awaitDimmer(200, "the cue plays the moved order")
    }

    @Test
    fun `Record Merge of a fresh pair into a cue holding the pair writes each onto its own child`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        client.stackPair()
        val cueId = client.record("CREATE").cue.id
        val stored = children(cueId)
        clearStage()

        client.stackPair(replace = 180, within = 64)
        assertEquals(2, client.record("MERGE", cueId).fxWritten)
        assertEquals(
            listOf(stored[0].copy(level = "180"), stored[1].copy(level = "64")),
            children(cueId),
            "the same two children, each with its own FX's settings",
        )

        clearStage()
        client.applyCue(cueId)
        awaitDimmer(180 * 64 / 255, "the Within at 64 over the Replace at 180")
    }

    @Test
    fun `a fresh FX of the same type beside the pair takes no child the pair came from`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val (cueId, stored) = client.includedPair()

        // At the bottom, so it is the recording's first FX and would take the Replace's child by key.
        val fresh = client.pressSine(150, "OVERRIDE", stack = true)
        assertTrue(fx.moveLocalEffect(fresh.id, aboveId = null) is FxEngine.LocalMoveOutcome.Moved)
        awaitDimmer(100, "the pair still applies over it")

        client.update()
        val after = children(cueId)
        assertEquals(3, after.size)
        assertEquals(stored, after.filter { child -> stored.any { it.uuid == child.uuid } }, "the pair's children kept as they were")
        assertEquals("150", after.single { child -> stored.none { it.uuid == child.uuid } }.level, "the fresh FX is a child of its own")
    }

    @Test
    fun `an Included FX whose type was swapped is written onto the child it came from`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val (cueId, stored) = client.includedPair()

        val swapped = client.put("/api/rest/fx/${included("MULTIPLY").id}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateEffectRequest(effectType = "SquareWave", parameters = mapOf("min" to "128", "max" to "128")))
        }
        assertEquals(HttpStatusCode.OK, swapped.status, swapped.bodyAsText())

        assertEquals(1, client.update().fxWritten)
        assertEquals(listOf(stored[0].uuid to "SineWave", stored[1].uuid to "SquareWave"), types(cueId), "the same two children")

        clearStage()
        client.applyCue(cueId)
        awaitDimmer(100, "the Square Wave Within over the Replace, nothing doubled")
    }

    @Test
    fun `a second Include runs each child once, and Update writes nothing`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val (cueId, stored) = client.includedPair()

        client.include(cueId)
        assertEquals(2, fx.getActiveEffects().count { it.isLocalEffect }, "no child run twice")
        awaitDimmer(100, "the stage as one Include left it")

        assertEquals(0, client.update().fxWritten)
        assertEquals(stored, children(cueId))
    }

    @Test
    fun `Record Remove of one Included FX of the pair deletes its own child`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val (cueId, stored) = client.includedPair()

        val stopped = client.delete("/api/rest/fx/${included("OVERRIDE").id}")
        assertEquals(HttpStatusCode.NoContent, stopped.status, stopped.bodyAsText())
        awaitDimmer(0, "the Within alone, over nothing")

        assertEquals(1, client.record("REMOVE", cueId).assignmentsRemoved)
        assertEquals(listOf(stored[0]), children(cueId), "the Within's child gone, the Replace's kept")

        clearStage()
        client.applyCue(cueId)
        awaitDimmer(200, "the cue plays the Replace alone")
    }

    /** A Pulse on the wash at [level], Replacing unless [blendMode] says otherwise. */
    private suspend fun HttpClient.pressWash(level: Int, blendMode: String = "OVERRIDE", stack: Boolean = false) =
        pressLevel("Pulse", wash, level, blendMode, stack)

    /** A cue holding one Pulse on the wash, Replacing at 200; the stage cleared after. */
    private suspend fun HttpClient.cueWithOnePulse(): Pair<Int, Child> {
        pressWash(200)
        awaitDimmer(200, "the one Pulse")
        val cueId = record("CREATE").cue.id
        val stored = children(cueId).single()
        assertEquals("OVERRIDE" to "200", stored.blendMode to stored.level)
        clearStage()
        return cueId to stored
    }

    @Test
    fun `Record Merge of two stacked Pulses on a group into a cue holding one keeps both`() = testApplication {
        mountTestApp(state)
        seedWash()
        val client = jsonClient()
        val (cueId, stored) = client.cueWithOnePulse()

        client.pressWash(180)
        client.pressWash(64, "MULTIPLY", stack = true)
        awaitDimmer(180 * 64 / 255, "the Within over the Replace")

        assertEquals(2, client.record("MERGE", cueId).fxWritten)
        val after = children(cueId)
        assertEquals(2, after.size, "one child per Pulse")
        assertEquals(stored.copy(level = "180"), after[0], "the Replace written onto the child the cue held")
        assertEquals("MULTIPLY" to "64", after[1].blendMode to after[1].level, "the Within a child of its own")

        clearStage()
        client.applyCue(cueId)
        awaitDimmer(180 * 64 / 255, "the cue plays both")
    }

    @Test
    fun `a Pulse stacked on the one a cue's Include brought back is Updated as a child of its own`() = testApplication {
        mountTestApp(state)
        seedWash()
        val client = jsonClient()
        val (cueId, stored) = client.cueWithOnePulse()
        client.include(cueId)
        awaitDimmer(200, "Include runs the cue's Pulse")

        client.pressWash(64, "MULTIPLY", stack = true)
        awaitDimmer(200 * 64 / 255, "the stacked Within over the included Replace")
        assertEquals(2, fx.getActiveEffects().count { it.isLocalEffect })

        assertEquals(1, client.update().fxWritten)
        val after = children(cueId)
        assertEquals(2, after.size, "one child per Pulse")
        assertEquals(stored, after[0], "the included Pulse's child as it was")
        assertEquals("MULTIPLY" to "64", after[1].blendMode to after[1].level)

        clearStage()
        client.applyCue(cueId)
        awaitDimmer(200 * 64 / 255, "the cue plays both")
    }

    @Test
    fun `two Pulses pressed over a cue's Included one are Updated as two children`() = testApplication {
        mountTestApp(state)
        seedWash()
        val client = jsonClient()
        val (cueId, stored) = client.cueWithOnePulse()
        client.include(cueId)
        awaitDimmer(200, "Include runs the cue's Pulse")

        client.pressWash(180)
        client.pressWash(64, "MULTIPLY", stack = true)
        awaitDimmer(180 * 64 / 255, "the Within over the Replace that took the included one's place")
        assertEquals(2, fx.getActiveEffects().count { it.isLocalEffect }, "the first press stopped the included Pulse")

        assertEquals(2, client.update().fxWritten)
        val after = children(cueId)
        assertEquals(2, after.size, "one child per Pulse")
        assertEquals(stored.copy(level = "180"), after[0])
        assertEquals("MULTIPLY" to "64", after[1].blendMode to after[1].level)

        clearStage()
        client.applyCue(cueId)
        awaitDimmer(180 * 64 / 255, "the cue plays both")
    }
}
