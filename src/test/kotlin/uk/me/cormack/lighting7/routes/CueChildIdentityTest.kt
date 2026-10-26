package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import kotlinx.serialization.json.jsonObject
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.fx.CycleLength
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxTargetRef
import uk.me.cormack.lighting7.fx.FxTiming
import uk.me.cormack.lighting7.fx.SliderTarget
import uk.me.cormack.lighting7.models.CueAdHocFxDto
import uk.me.cormack.lighting7.models.CueLayerDto
import uk.me.cormack.lighting7.models.CueAttributeAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.CueTriggerDto
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.scripts.ScriptType
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.sync.ProjectImporter
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.SineSlider
import uk.me.cormack.lighting7.testsupport.assertExportsEqual
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.readExportFiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A cue write keeps the uuid of every layer, effect, row and hook it keeps (show-cue-editing plan
 * P3, §3.4), so a one-value edit reads in the sync diff as one changed record rather than every
 * child replaced. The Look's twin is `LookRowIdentityTest`.
 */
class CueChildIdentityTest : RouteIntegrationTest() {

    private fun base() = "/api/rest/projects/$projectId/cues"

    private val pulse = CueAdHocFxDto(
        targetType = "fixture", targetKey = "hex-1", fxType = "Wave", family = "intensity",
        attribute = "dimmer", cycle = 0.5, blendMode = "OVERRIDE", order = "NORMAL",
    )

    private fun rows() = listOf(
        CueAttributeAssignmentDto("fixture", "hex-1", "dimmer", "100", sortOrder = 0),
        CueAttributeAssignmentDto("fixture", "hex-1", "uv", "10", sortOrder = 1),
        CueAttributeAssignmentDto("fixture", "hex-2", "rgbColour", "#00ff00", sortOrder = 2),
    )

    @Test
    fun `a one-value PATCH keeps every other child's uuid`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val cue = client.richCue()
        val before = children(cue.id)

        // The value grid's save: the whole row list with one value changed.
        val edited = rows().map { if (it.targetKey == "hex-2") it.copy(value = "#0000ff") else it }
        val response = client.patch("${base()}/${cue.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("attributeAssignments", Json.encodeToJsonElement(edited)) })
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

        assertEquals(before, children(cue.id), "every child keeps its uuid, the edited row included")
        assertEquals("#0000ff", response.body<CueDetails>().attributeAssignments.single { it.targetKey == "hex-2" }.value)
    }

    @Test
    fun `a whole-cue PUT keeps the uuid of every child it keeps, and swaps the one it replaces`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val cue = client.richCue()
        val before = children(cue.id)

        // A client's inline edit sends the whole cue back: one effect retuned, one row's property
        // spelled the other way, one row dropped and one added, everything else as it was.
        val input = cue.asInput().let { input ->
            input.copy(
                adHocFx = input.adHocFx.map { it.copy(cycle = 2.0, parameters = mapOf("low" to "40")) },
                attributeAssignments = input.attributeAssignments
                    .filterNot { it.attribute == "uv" }
                    .map { if (it.targetKey == "hex-2") it.copy(attribute = "colour") else it } +
                    CueAttributeAssignmentDto("fixture", "hex-2", "dimmer", "50", sortOrder = 9),
            )
        }
        val response = client.put("${base()}/${cue.id}") {
            contentType(ContentType.Application.Json)
            setBody(input)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

        val after = children(cue.id)
        assertEquals(before.layers, after.layers)
        assertEquals(before.effects, after.effects, "a parameter and timing edit keeps the effect's uuid")
        assertEquals(before.hooks, after.hooks)
        assertEquals(before.rows.getValue(Row("hex-1", "dimmer")), after.rows.getValue(Row("hex-1", "dimmer")))
        assertEquals(
            before.rows.getValue(Row("hex-2", "rgbColour")), after.rows.getValue(Row("hex-2", "colour")),
            "a property's other spelling is the same key",
        )
        assertFalse(Row("hex-1", "uv") in after.rows, "dropped")
        assertFalse(after.rows.getValue(Row("hex-2", "dimmer")) in before.rows.values, "added with a fresh uuid")

        val effect = response.body<CueDetails>().adHocFx.single()
        assertEquals(2.0, effect.cycle)
        assertEquals(before.effects.single().toString(), effect.uuid, "the answer names the effect it kept")

        // Another effect type on the same property is a new effect, not this one edited.
        val swapped = client.put("${base()}/${cue.id}") {
            contentType(ContentType.Application.Json)
            setBody(input.copy(adHocFx = listOf(pulse.copy(fxType = "Flicker"))))
        }
        assertEquals(HttpStatusCode.OK, swapped.status, swapped.bodyAsText())
        assertNotEquals(before.effects, children(cue.id).effects)
    }

    @Test
    fun `a key held twice pairs in sortOrder order`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (warm, _) = client.twoLooks()
        // Each key twice, the copy written first sorting *last*, so sortOrder order and insertion
        // (id) order disagree. A body's FX are stored in list order, so the FX are swapped below.
        val created = client.createCue(
            NewCue(
                name = "twice",
                cueStackId = ProgrammerRouteTestSupport.createStack(client, projectId, "s"),
                attributeAssignments = listOf(
                    CueAttributeAssignmentDto("fixture", "hex-1", "dimmer", "10", sortOrder = 5),
                    CueAttributeAssignmentDto("fixture", "hex-1", "dimmer", "20", sortOrder = 1),
                ),
                adHocFx = listOf(pulse.copy(sortOrder = 4), pulse.copy(sortOrder = 2)),
                layers = listOf(
                    CueLayerDto(lookId = warm, sortOrder = 3, delayMs = 1_000L),
                    CueLayerDto(lookId = warm, sortOrder = 0),
                ),
            ),
        )
        transaction(state.database) {
            val (first, second) = DaoCue.findById(created.id)!!.adHocFx.sortedBy { it.id.value }
            first.sortOrder = 4
            second.sortOrder = 2
        }
        val before = orderedChildren(created.id)

        val response = client.put("${base()}/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                NewCue(
                    name = "twice",
                    attributeAssignments = listOf(CueAttributeAssignmentDto("fixture", "hex-1", "dimmer", "30", sortOrder = 0)),
                    adHocFx = listOf(pulse.copy(sortOrder = 0)),
                    layers = listOf(CueLayerDto(lookId = warm, sortOrder = 0)),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

        // The one incoming item of each key takes the uuid of the stored one sorting first.
        val after = orderedChildren(created.id)
        assertEquals(listOf(before.rows.first()), after.rows)
        assertEquals(listOf(before.effects.first()), after.effects)
        assertEquals(listOf(before.layers.first()), after.layers)
        assertEquals("30", response.body<CueDetails>().attributeAssignments.single().value)
    }

    @Test
    fun `a reorder that restates every sortOrder keeps each layer's and effect's uuid`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (warm, cool) = client.twoLooks()
        val dynamics = pulse.copy(seconds = true, cycle = 2.0, amount = 0.5, direction = "BOUNCE", runs = 3)
        val chase = pulse.copy(targetKey = "hex-2", fxType = "Flicker", sortOrder = 1)
        val created = client.createCue(
            NewCue(
                name = "order",
                cueStackId = ProgrammerRouteTestSupport.createStack(client, projectId, "s"),
                adHocFx = listOf(dynamics.copy(sortOrder = 0), chase),
                layers = listOf(CueLayerDto(lookId = warm, sortOrder = 0), CueLayerDto(lookId = cool, sortOrder = 1)),
            ),
        )
        val before = orderedChildren(created.id)

        // The Looks and FX tabs' write: each whole list, every sortOrder restated, one layer off.
        val response = client.patch("${base()}/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put(
                        "layers",
                        Json.encodeToJsonElement(
                            listOf(
                                CueLayerDto(lookId = cool, sortOrder = 0),
                                CueLayerDto(lookId = warm, sortOrder = 1, enabled = false),
                            ),
                        ),
                    )
                    put("adHocFx", Json.encodeToJsonElement(listOf(chase.copy(sortOrder = 0), dynamics.copy(sortOrder = 1))))
                },
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

        val after = orderedChildren(created.id)
        assertEquals(before.layers.reversed(), after.layers, "each layer keeps its uuid in its new place")
        assertEquals(before.effects.reversed(), after.effects, "each effect keeps its uuid in its new place")
        val details = response.body<CueDetails>()
        assertEquals(listOf(true, false), details.layers.map { it.enabled }, "the disabled layer is stored")
        val moved = details.adHocFx.single { it.fxType == "Wave" }
        assertEquals(listOf(true, 0.5, "BOUNCE", 3), listOf(moved.seconds, moved.amount, moved.direction, moved.runs))
    }

    @Test
    fun `the sync export of a one-value edit changes one record`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val cue = client.richCue()

        val before = exported()
        val edited = rows().map { if (it.targetKey == "hex-2") it.copy(value = "#0000ff") else it }
        client.patch("${base()}/${cue.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("attributeAssignments", Json.encodeToJsonElement(edited)) })
        }
        val after = exported()

        assertEquals(before.keys, after.keys, "no record added or removed")
        val changed = before.keys.filter { before[it] != after[it] }
        val row = children(cue.id).rows.getValue(Row("hex-2", "rgbColour"))
        assertEquals(listOf("cueAttributeAssignments/$row.json"), changed)
    }

    @Test
    fun `the round trip keeps each child's uuid, and a write after it still does`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val cue = client.richCue()
        val cueUuid = transaction(state.database) { DaoCue[cue.id].uuid }
        val edited = cue.asInput().let { input ->
            input.copy(adHocFx = input.adHocFx.map { it.copy(cycle = 2.0) })
        }
        client.put("${base()}/${cue.id}") {
            contentType(ContentType.Application.Json)
            setBody(edited)
        }
        val before = children(cue.id)

        val dirA = Files.createTempDirectory("cue-identity-a-")
        val dirB = Files.createTempDirectory("cue-identity-b-")
        try {
            ProjectExporter(state).export(projectId, dirA)
            ProjectImporter(state).replaceFromWorkingTree(projectId, dirA)
            ProjectExporter(state).export(projectId, dirB)
            assertExportsEqual(dirA, dirB)
        } finally {
            dirA.toFile().deleteRecursively()
            dirB.toFile().deleteRecursively()
        }

        // The import made new rows, so the cue has a new id, but every child has the uuid it had.
        val imported = transaction(state.database) { DaoCue.all().single { it.uuid == cueUuid }.id.value }
        assertEquals(before, children(imported))

        val response = client.put("${base()}/$imported") {
            contentType(ContentType.Application.Json)
            setBody(
                client.get("${base()}/$imported").body<CueDetails>().asInput().let { input ->
                    input.copy(attributeAssignments = input.attributeAssignments.map { it.copy(value = "1") })
                },
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(before, children(imported))
    }

    @Test
    fun `Record's UPDATE_EXISTING writes over a row and an effect it records again, keeping their uuids`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
            LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
            val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-a")

            ProgrammerHandler.set(state, TargetRef.Fixture("hex-1"), "dimmer", "200", 0)
            ProgrammerHandler.set(state, TargetRef.Fixture("hex-2"), "dimmer", "100", 0)
            state.show.fxEngine.addFx(programmerBandSine("hex-1"))
            val created = client.record(
                ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId),
            ).body<ProgrammerRecordResponse>()
            val before = children(created.cue.id)

            // Re-record hex-1 alone, its dimmer and its effect retuned.
            clearProgrammerCompletely(state)
            state.show.fxEngine.getActiveFx().forEach { state.show.fxEngine.removeFx(it.id) }
            ProgrammerHandler.set(state, TargetRef.Fixture("hex-1"), "dimmer", "255", 0)
            state.show.fxEngine.addFx(programmerBandSine("hex-1", CycleLength.TWO_BEATS))
            val updated = client.record(
                ProgrammerRecordRequest(
                    projectId = projectId.toString(), mode = "UPDATE_EXISTING", cueId = created.cue.id,
                    targets = listOf(CueTargetDto(type = "fixture", key = "hex-1")),
                ),
            )
            assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())

            val after = children(created.cue.id)
            assertEquals(before, after, "the re-recorded row and effect are written over in place")
            val cue = updated.body<ProgrammerRecordResponse>().cue
            assertEquals("255", cue.attributeAssignments.single { it.targetKey == "hex-1" }.value)
            assertEquals("100", cue.attributeAssignments.single { it.targetKey == "hex-2" }.value, "outside the scope")
            assertEquals(CycleLength.TWO_BEATS, cue.adHocFx.single().cycle)
        }

    @Test
    fun `a scoped re-record shadowed by a surviving group effect writes nothing, and that effect keeps its uuid`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
            LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
            LocateTestSupport.seedGroup(state, projectId, "front-wash", "hex-1", "hex-2")
            val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-a")

            ProgrammerHandler.set(state, TargetRef.Group("front-wash"), "dimmer", "150", 0)
            state.show.fxEngine.addFx(programmerBandSine(FxTargetRef.group("front-wash")))
            val created = client.record(
                ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId),
            ).body<ProgrammerRecordResponse>()
            val before = children(created.cue.id)

            // The group effect is outside a hex-1 scope and survives, so the same effect recorded on
            // hex-1 is shadowed: refused with a warning, not paired with anything.
            clearProgrammerCompletely(state)
            state.show.fxEngine.getActiveFx().forEach { state.show.fxEngine.removeFx(it.id) }
            ProgrammerHandler.set(state, TargetRef.Fixture("hex-1"), "dimmer", "255", 0)
            state.show.fxEngine.addFx(programmerBandSine(FxTargetRef.fixture("hex-1")))
            val updated = client.record(
                ProgrammerRecordRequest(
                    projectId = projectId.toString(), mode = "UPDATE_EXISTING", cueId = created.cue.id,
                    targets = listOf(CueTargetDto(type = "fixture", key = "hex-1")),
                ),
            ).body<ProgrammerRecordResponse>()

            assertEquals(before.effects, children(created.cue.id).effects)
            assertEquals(TargetRef.Group.TYPE, updated.cue.adHocFx.single().targetType)
            assertTrue(updated.warnings.any { "already covers hex-1" in it }, updated.warnings.toString())
        }

    @Test
    fun `Record's UPDATE_EXISTING writes over a layer it records again, keeping its uuid`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val warm = ProgrammerRouteTestSupport.createLookBoundTo(client, projectId, "Warm", mapOf("dimmer" to "200"))
        state.show.programmerLayerStack.add(
            source = LayerSource.look(warm.id, UUID.fromString(warm.uuid), warm.name),
            targets = listOf(CueTargetDto("fixture", "hex-1")),
        )
        val created = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE",
                cueStackId = ProgrammerRouteTestSupport.createStack(client, projectId, "s"),
            ),
        ).body<ProgrammerRecordResponse>()
        val before = children(created.cue.id).layers.single()

        // The same Look again, at half amount.
        state.show.programmerLayerStack.patch(state.show.programmerStore.layers.single().layerId, amount = 0.5)
        val updated = client.record(
            ProgrammerRecordRequest(projectId = projectId.toString(), mode = "UPDATE_EXISTING", cueId = created.cue.id),
        )
        assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())

        assertEquals(before, children(created.cue.id).layers.single())
        assertEquals(0.5, updated.body<ProgrammerRecordResponse>().cue.layers.single().amount)
    }

    // ── Run keys (stack-timeline plan P2, P4) ──────────────────────────────

    @Test
    fun `a PATCH keeps a matched effect's run key, ignores one in the body, and gives a new effect none`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val cue = client.richCue()
        val key = keyFx(cue.id)

        val echoed = UUID.randomUUID().toString()
        val response = client.patch("${base()}/${cue.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put(
                        "adHocFx",
                        Json.encodeToJsonElement(
                            listOf(
                                cue.adHocFx.single().copy(cycle = 2.0, runUuid = echoed),
                                pulse.copy(targetKey = "hex-2", sortOrder = 1, runUuid = echoed),
                            ),
                        ),
                    )
                },
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

        assertEquals(mapOf("hex-1" to key, "hex-2" to null), runKeys(cue.id), "the body's key is no write's to make")
        val answered = response.body<CueDetails>().adHocFx.associate { it.targetKey to it.runUuid }
        assertEquals(mapOf("hex-1" to key.toString(), "hex-2" to null), answered, "read back on the DTO")
    }

    @Test
    fun `Record's UPDATE_EXISTING keeps a re-recorded effect's run key and gives a new effect none`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-a")

        state.show.fxEngine.addFx(programmerBandSine("hex-1"))
        val created = client.record(
            ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId),
        ).body<ProgrammerRecordResponse>()
        val key = keyFx(created.cue.id)

        state.show.fxEngine.getActiveFx().forEach { state.show.fxEngine.removeFx(it.id) }
        state.show.fxEngine.addFx(programmerBandSine("hex-1", CycleLength.TWO_BEATS))
        state.show.fxEngine.addFx(programmerBandSine("hex-2"))
        val updated = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "UPDATE_EXISTING", cueId = created.cue.id,
                targets = listOf(CueTargetDto(type = "fixture", key = "hex-1"), CueTargetDto(type = "fixture", key = "hex-2")),
            ),
        )
        assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())

        assertEquals(mapOf("hex-1" to key, "hex-2" to null), runKeys(created.cue.id))
        assertEquals(CycleLength.TWO_BEATS, updated.body<ProgrammerRecordResponse>().cue.adHocFx.single { it.targetKey == "hex-1" }.cycle)
    }

    @Test
    fun `create_cue gives its effects no run key, and refuses one a call names`() = testApplication {
        mountTestApp(state)
        val effect = """{"targetType":"fixture","targetKey":"hex-1","fxType":"Wave","family":"intensity","attribute":"dimmer",""" +
            """"cycle":0.5,"blendMode":"OVERRIDE"}"""
        val keyed = AiTools(state).executeTool(
            "create_cue",
            Json.parseToJsonElement("""{"name":"Q0","adHocFx":[${effect.dropLast(1)},"runUuid":"${UUID.randomUUID()}"}]}""").jsonObject,
        )
        assertFalse(keyed.success, keyed.result)
        assertTrue("'adHocFx[0].runUuid' is not an argument" in keyed.result, keyed.result)

        val created = AiTools(state).executeTool(
            "create_cue",
            Json.parseToJsonElement("""{"name":"Q1","adHocFx":[$effect]}""").jsonObject,
        )
        assertTrue(created.success, created.result)
        val cueId = Json.parseToJsonElement(created.result).jsonObject["cueId"].toString().toInt()
        assertEquals(mapOf("hex-1" to null), runKeys(cueId))
    }

    @Test
    fun `a run key is not stage content, so a key change alone leaves a live cue unedited`() = testApplication {
        mountTestApp(state)
        val cue = jsonClient().richCue()
        transaction(state.database) {
            val stored = DaoCue[cue.id]
            val before = stored.stageContent()
            stored.adHocFx.single().runUuid = UUID.randomUUID()
            assertEquals(before, DaoCue[cue.id].stageContent())
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Give the cue's one effect a run key, as the timeline would; answers it. */
    private fun keyFx(cueId: Int): UUID = transaction(state.database) {
        UUID.randomUUID().also { DaoCue[cueId].adHocFx.single().runUuid = it }
    }

    private fun runKeys(cueId: Int): Map<String, UUID?> = transaction(state.database) {
        DaoCue[cueId].adHocFx.associate { it.targetKey to it.runUuid }
    }

    private data class Row(val targetKey: String, val attribute: String)

    private data class Children(val rows: Map<Row, UUID>, val layers: Set<UUID>, val effects: Set<UUID>, val hooks: Set<UUID>)

    private data class Ordered(val rows: List<UUID>, val layers: List<UUID>, val effects: List<UUID>)

    private fun children(cueId: Int): Children = transaction(state.database) {
        val cue = DaoCue[cueId]
        Children(
            rows = cue.attributeAssignments.associate { Row(it.targetKey, it.attribute) to it.uuid },
            layers = cue.layers.mapTo(HashSet()) { it.uuid },
            effects = cue.adHocFx.mapTo(HashSet()) { it.uuid },
            hooks = cue.triggers.mapTo(HashSet()) { it.uuid },
        )
    }

    private fun orderedChildren(cueId: Int): Ordered = transaction(state.database) {
        val cue = DaoCue[cueId]
        Ordered(
            rows = cue.attributeAssignmentsInOrder().map { it.uuid },
            layers = cue.layersInOrder().map { it.uuid },
            effects = cue.adHocFxInOrder().map { it.uuid },
        )
    }

    private fun exported(): Map<String, String> {
        val dir: Path = Files.createTempDirectory("cue-identity-")
        try {
            ProjectExporter(state).export(projectId, dir)
            return readExportFiles(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private suspend fun HttpClient.twoLooks(): Pair<Int, Int> =
        ProgrammerRouteTestSupport.createLookBoundTo(this, projectId, "Warm", mapOf("dimmer" to "200")).id to
            ProgrammerRouteTestSupport.createLookBoundTo(this, projectId, "Cool", mapOf("dimmer" to "100")).id

    /** A cue with three rows, two layers, an effect and a script hook. */
    private suspend fun HttpClient.richCue(): CueDetails {
        val (warm, cool) = twoLooks()
        val scriptId = transaction(state.database) {
            DaoScript.new {
                project = DaoProject[projectId]
                name = "hook"
                script = "// no-op"
                scriptType = ScriptType.FX_APPLICATION
            }.id.value
        }
        return createCue(
            NewCue(
                name = "rich",
                cueStackId = ProgrammerRouteTestSupport.createStack(this, projectId, "s"),
                attributeAssignments = rows(),
                adHocFx = listOf(pulse),
                layers = listOf(CueLayerDto(lookId = warm, sortOrder = 0), CueLayerDto(lookId = cool, sortOrder = 1)),
                triggers = listOf(CueTriggerDto(triggerType = "ACTIVATION", scriptId = scriptId)),
            ),
        )
    }

    private suspend fun HttpClient.createCue(cue: NewCue): CueDetails {
        val response = post(base()) {
            contentType(ContentType.Application.Json)
            setBody(cue)
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }

    /** What a client's inline edit sends: the cue as read, back whole. */
    private fun CueDetails.asInput() = NewCue(
        name = name,
        layers = layers,
        adHocFx = adHocFx,
        attributeAssignments = attributeAssignments,
        triggers = triggers.map {
            CueTriggerDto(it.triggerType, it.delayMs, it.intervalMs, it.randomWindowMs, it.scriptId, it.sortOrder)
        },
        cueStackId = cueStackId,
        fadeDurationMs = fadeDurationMs,
        fadeCurve = fadeCurve,
        notes = notes,
    ).also { assertTrue(adHocFx.all { it.uuid != null }, "a stored effect is read with its uuid") }

    private suspend fun HttpClient.record(request: ProgrammerRecordRequest) =
        post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private fun programmerBandSine(fixtureKey: String, division: Double = CycleLength.ONE_BEAT) =
        programmerBandSine(FxTargetRef.fixture(fixtureKey), division)

    private fun programmerBandSine(target: FxTargetRef, division: Double = CycleLength.ONE_BEAT) = FxInstance(
        effect = SineSlider(),
        target = SliderTarget(target, "dimmer"),
        timing = FxTiming(cycle = division),
    ).apply { priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE }
}
