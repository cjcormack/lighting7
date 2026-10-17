package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.inStack
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `POST /projects/{id}/cues/copy-from` (cue model plan §3.8, D9, D18): cues of any project copied
 * into the current one, every reference mapped through the one cue copier.
 */
class CueCopyFromTest : RouteIntegrationTest() {

    private suspend fun HttpClient.copyFrom(request: CopyCuesFromRequest): HttpResponse =
        post("/api/rest/projects/$projectId/cues/copy-from") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private fun cueNamed(project: Int, name: String): Int = transaction(state.database) {
        DaoCue.find { (DaoCues.project eq project) and (DaoCues.name eq name) }.single().id.value
    }

    /** A standalone project, not current, with its default masters. */
    private fun standalone(name: String): Int = transaction(state.database) {
        val project = DaoProject.new { this.name = name; description = "copy source"; isCurrent = false }
        DaoUniverseConfig.new { this.project = project; subnet = 0; universe = 0; controllerType = "MOCK"; address = null }
        ensureDefaultSpeedMasters(project)
        project.id.value
    }

    private fun universeOf(project: Int): DaoUniverseConfig =
        DaoUniverseConfig.find { DaoUniverseConfigs.project eq project }.first()

    private fun cannon(
        project: Int,
        key: String,
        type: String = TWIN_SHOT,
        universe: DaoUniverseConfig = universeOf(project),
    ): DaoFixturePatch = DaoFixturePatch.new {
        this.project = DaoProject[project]
        universeConfig = universe
        fixtureTypeKey = type
        this.key = key
        displayName = key
        startChannel = 400
        sortOrder = 50
    }

    private fun drape(project: Int, name: String): DaoStageElement = DaoStageElement.new {
        this.project = DaoProject[project]
        this.name = name
        kind = StageElementKind.DRAPE.name
        layer = StageElementLayer.SET.name
        widthM = 4.0; depthM = 0.1; heightM = 3.0
    }

    /**
     * The rich show on Commemoration Hall v1, the hall at v3 — its cannon re-keyed and its tabs
     * renamed in v3, so only a uuid can find them — and the current project pinned to v3.
     */
    private fun twoShowsOnOneVenue(): Int {
        val f = seedShowOnVenue(state)
        transaction(state.database) {
            DaoFixturePatch.find { (DaoFixturePatches.project eq f.venueId) and (DaoFixturePatches.key eq "cannon-1") }
                .single().key = "cannon-hall"
            DaoStageElement.find { (DaoStageElements.project eq f.venueId) and (DaoStageElements.name eq "House tabs") }
                .single().name = "Main tabs"
        }
        assertEquals(3, runBlocking { state.venueVersions.publish(f.venueId, "Re-keyed the cannon", "Test") }.number)
        transaction(state.database) {
            DaoProject[projectId].apply {
                venueUuid = DaoProject[f.venueId].uuid
                venueVersion = 3
            }
        }
        return f.showId
    }

    @Test
    fun `two shows on one venue at different versions resolve a venue patch's event and a venue element's scenery by uuid`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val showId = twoShowsOnOneVenue()
        val open = cueNamed(showId, "open")

        val response = client.copyFrom(CopyCuesFromRequest(fromProjectId = showId, cueIds = listOf(open)))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val copy = response.body<CopyCuesFromResponse>().copied.single()
        assertEquals(open, copy.fromCueId)
        assertEquals(null, copy.entryId, "no stack: the copy is loose")

        transaction(state.database) {
            val snapshot = RigScope.of(DaoProject[projectId]).venue!!.snapshotId
            val sourceEvents = cueEventsOf(DaoCue[open].id)
            val events = cueEventsOf(DaoCue[copy.cueId].id)
            assertEquals(sourceEvents.map { it.trigger to it.patch.uuid }, events.map { it.trigger to it.patch.uuid })
            assertTrue(events.all { it.patch.project.id == snapshot && it.patch.key == "cannon-hall" }, "the v3 cannon")
            val tabs = cueSceneryOf(DaoCue[copy.cueId].id).single { it.element.name == "Main tabs" }
            assertEquals(snapshot, tabs.element.project.id)
            assertEquals(
                cueSceneryOf(DaoCue[open].id).single { it.element.name == "House tabs" }.element.uuid,
                tabs.element.uuid,
            )
            assertTrue(DaoPromptBookAnchor.all().none { it.entry.cue?.id?.value == copy.cueId }, "no anchor came with it")
        }
        val unresolved = response.body<CopyCuesFromResponse>().unresolved
        // The thrust deck is the show's own set, which the target's scene has nothing of.
        assertTrue(unresolved.any { "'Thrust deck'" in it.problem && "cue 'open'" in it.records }, "$unresolved")
    }

    @Test
    fun `a venue patch re-typed under its uuid is no match, and its event is dropped and reported`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val showId = twoShowsOnOneVenue()
        val venueId = transaction(state.database) { DaoProject.find { DaoProjects.kind eq ProjectKind.VENUE }.single().id.value }
        transaction(state.database) {
            DaoFixturePatch.find { (DaoFixturePatches.project eq venueId) and (DaoFixturePatches.key eq "cannon-hall") }
                .single().fixtureTypeKey = "hex"
        }
        runBlocking { state.venueVersions.publish(venueId, "The cannon is a hex now", "Test") }
        transaction(state.database) { DaoProject[projectId].venueVersion = 4 }

        val response = client.copyFrom(CopyCuesFromRequest(fromProjectId = showId, cueIds = listOf(cueNamed(showId, "open"))))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val body = response.body<CopyCuesFromResponse>()
        transaction(state.database) { assertTrue(cueEventsOf(DaoCue[body.copied.single().cueId].id).isEmpty()) }
        assertTrue(body.unresolved.any { "of type $TWIN_SHOT; its output1 event is left out" in it.problem }, "${body.unresolved}")
    }

    @Test
    fun `a show's own patch resolves by key and fixture type`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val showId = twoShowsOnOneVenue()
        val (cueId, ownUuid) = transaction(state.database) {
            // The show's own units, on a universe of its venue's.
            val venueUniverse = RigScope.of(DaoProject[showId]).universeConfigs().first()
            val ownCannon = cannon(showId, "cannon-panto", universe = venueUniverse)
            val wrongType = cannon(showId, "strobe-panto", universe = venueUniverse)
            cannon(projectId, "cannon-panto")
            cannon(projectId, "strobe-panto", type = "hex")
            val cue = DaoCue.new { project = DaoProject[showId]; name = "bang" }
            DaoCueEvent.new { this.cue = cue; patch = ownCannon; trigger = "output1"; offset = java.time.Duration.ZERO }
            DaoCueEvent.new { this.cue = cue; patch = wrongType; trigger = "output2"; offset = java.time.Duration.ZERO; sortOrder = 1 }
            cue.id.value to ownCannon.uuid
        }

        val response = client.copyFrom(CopyCuesFromRequest(fromProjectId = showId, cueIds = listOf(cueId)))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val body = response.body<CopyCuesFromResponse>()
        transaction(state.database) {
            val event = cueEventsOf(DaoCue[body.copied.single().cueId].id).single()
            assertEquals("output1", event.trigger)
            assertEquals(projectId, event.patch.project.id.value)
            assertEquals("cannon-panto", event.patch.key)
            assertTrue(event.patch.uuid != ownUuid, "the target's own patch, found by key")
        }
        assertTrue(
            body.unresolved.any { "'strobe-panto' of type $TWIN_SHOT" in it.problem && "output2" in it.problem },
            "a patch of another type is no match: ${body.unresolved}",
        )
    }

    @Test
    fun `a standalone source resolves by key and name, and what does not resolve is dropped and reported`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val source = standalone("Panto")
        val cueId = transaction(state.database) {
            val bang = cannon(source, "cannon-1")
            val lost = cannon(source, "cannon-lost")
            val tabs = drape(source, "Main tabs")
            val gauze = drape(source, "Forest gauze")
            cannon(projectId, "cannon-1")
            drape(projectId, "Main tabs")
            val cue = DaoCue.new { project = DaoProject[source]; name = "storm" }
            DaoCueEvent.new { this.cue = cue; patch = bang; trigger = "output1"; offset = java.time.Duration.ZERO }
            DaoCueEvent.new { this.cue = cue; patch = lost; trigger = "output2"; offset = java.time.Duration.ZERO; sortOrder = 1 }
            DaoCueSceneryRow.new { this.cue = cue; element = tabs; stateJson = encodeSceneryState(ElementStates(open = 0.0)) }
            DaoCueSceneryRow.new { this.cue = cue; element = gauze; stateJson = encodeSceneryState(ElementStates(visible = false)); sortOrder = 1 }
            cue.id.value
        }

        val response = client.copyFrom(CopyCuesFromRequest(fromProjectId = source, cueIds = listOf(cueId)))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val body = response.body<CopyCuesFromResponse>()
        transaction(state.database) {
            val copy = DaoCue[body.copied.single().cueId]
            val event = cueEventsOf(copy.id).single()
            assertEquals(projectId to "cannon-1", event.patch.project.id.value to event.patch.key)
            val scenery = cueSceneryOf(copy.id).single()
            assertEquals(projectId to "Main tabs", scenery.element.project.id.value to scenery.element.name)
        }
        val problems = body.unresolved.map { it.problem }
        assertTrue(problems.any { "'cannon-lost'" in it && "output2 event is left out" in it }, "$problems")
        assertTrue(problems.any { "'Forest gauze'" in it && "scenery change is left out" in it }, "$problems")
    }

    @Test
    fun `an unresolved fixture or group target is kept and reads as missing`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val source = standalone("Panto")
        val cueId = transaction(state.database) {
            val cue = DaoCue.new { project = DaoProject[source]; name = "ghosts" }
            DaoCuePropertyAssignment.new {
                this.cue = cue; targetType = "fixture"; targetKey = "ghost-1"; propertyName = "dimmer"; value = "200"
            }
            DaoCueAdHocEffect.new {
                this.cue = cue; targetType = "group"; targetKey = "phantoms"
                effectType = "Pulse"; category = "dimmer"; beatDivision = 1.0
                blendMode = "OVERRIDE"; distribution = "LINEAR"; parameters = emptyMap(); propertyName = "dimmer"
            }
            cue.id.value
        }

        val body = client.copyFrom(CopyCuesFromRequest(fromProjectId = source, cueIds = listOf(cueId))).body<CopyCuesFromResponse>()
        val copyId = body.copied.single().cueId
        val problems = body.unresolved.map { it.problem }
        assertTrue(problems.any { "no fixture 'ghost-1'" in it }, "$problems")
        assertTrue(problems.any { "no group 'phantoms'" in it }, "$problems")

        val details = client.get("/api/rest/projects/$projectId/cues/$copyId").body<CueDetails>()
        val row = details.propertyAssignments.single()
        assertEquals("ghost-1", row.targetKey)
        assertEquals(AssignmentHealth.MissingFixture("ghost-1"), row.health)
        assertEquals("phantoms", details.adHocEffects.single().targetKey)
    }

    @Test
    fun `Looks and palettes link by name else are copied, tmpl references follow, masters map by index`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val source = standalone("Panto")
        val (cueId, uuids) = transaction(state.database) {
            ensureDefaultSpeedMasters(DaoProject[projectId])
            val project = DaoProject[source]
            val warm = DaoLook.new { this.project = project; name = "Warm" }
            val cool = DaoLook.new { this.project = project; name = "Cool"; notes = "blue wash" }
            val amber = DaoTemplate.new { this.project = project; name = "Amber" }
            val blue = DaoTemplate.new { this.project = project; name = "Blue" }
            DaoTemplateRow.new { template = blue; targetType = DEFERRED_TARGET_TYPE; targetKey = ""; propertyName = "rgbColour"; value = "#0000ff" }
            DaoLook.new { this.project = DaoProject[projectId]; name = "Warm" }
            DaoTemplate.new { this.project = DaoProject[projectId]; name = "Amber" }
            val master3 = speedMastersOf(project).single { it.masterIndex == 3 }.uuid
            val cue = DaoCue.new {
                this.project = project; name = "busk"
                speedMasters = listOf(CueSpeedMasterDto(master3.toString(), 140.0))
            }
            for ((i, layer) in listOf<Pair<DaoLook?, DaoTemplate?>>(warm to null, cool to null, null to amber, null to blue).withIndex()) {
                DaoCueLayer.new {
                    this.cue = cue; look = layer.first; template = layer.second; targets = emptyList(); sortOrder = i
                    speedMasterUuid = master3
                }
            }
            DaoCueAdHocEffect.new {
                this.cue = cue; targetType = "fixture"; targetKey = "hex-1"
                effectType = "PaletteSteps"; category = "colour"; beatDivision = 1.0
                blendMode = "OVERRIDE"; distribution = "LINEAR"; propertyName = "rgbColour"
                parameters = mapOf("palettes" to "tmpl:${amber.uuid}, tmpl:${blue.uuid}", "colour" to "tmpl:${blue.uuid}", "depth" to "0.5")
            }
            cue.id.value to mapOf("amber" to amber.uuid, "blue" to blue.uuid)
        }

        val response = client.copyFrom(CopyCuesFromRequest(fromProjectId = source, cueIds = listOf(cueId)))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val body = response.body<CopyCuesFromResponse>()
        assertEquals(listOf("Cool"), body.looksCopied)
        assertEquals(listOf("Warm"), body.looksLinked)
        assertEquals(listOf("Blue"), body.palettesCopied)
        assertEquals(listOf("Amber"), body.palettesLinked)
        transaction(state.database) {
            val target = DaoProject[projectId]
            val looks = DaoLook.find { DaoLooks.project eq target.id }.associateBy { it.name }
            val templates = DaoTemplate.find { DaoTemplates.project eq target.id }.associateBy { it.name }
            assertEquals(setOf("Warm", "Cool"), looks.keys)
            assertEquals("blue wash", looks.getValue("Cool").notes)
            assertEquals("#0000ff", templates.getValue("Blue").rows.single().value)
            val copy = DaoCue[body.copied.single().cueId]
            val layers = copy.layers.sortedBy { it.sortOrder }
            assertEquals(
                listOf(looks.getValue("Warm").id, looks.getValue("Cool").id, null, null),
                layers.map { it.look?.id },
            )
            assertEquals(
                listOf(null, null, templates.getValue("Amber").id, templates.getValue("Blue").id),
                layers.map { it.template?.id },
            )
            val master3 = speedMastersOf(target).single { it.masterIndex == 3 }.uuid
            assertTrue(layers.all { it.speedMasterUuid == master3 })
            assertEquals(listOf(master3.toString() to 140.0), copy.speedMasters.map { it.speedMasterUuid to it.bpm })
            val params = copy.adHocEffects.single().parameters
            val amber = templates.getValue("Amber").uuid
            val blue = templates.getValue("Blue").uuid
            assertEquals("tmpl:$amber,tmpl:$blue", params["palettes"])
            assertEquals("tmpl:$blue", params["colour"])
            assertEquals("0.5", params["depth"])
            assertTrue(uuids.values.none { it == amber || it == blue }, "every reference names the target's palette")
        }
        assertTrue(body.unresolved.none { "speed master" in it.problem || "palette" in it.problem }, "${body.unresolved}")
    }

    @Test
    fun `a dry run answers the copy and writes nothing`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val source = standalone("Panto")
        val (cueId, stackId) = transaction(state.database) {
            val look = DaoLook.new { project = DaoProject[source]; name = "Cool" }
            val cue = DaoCue.new { project = DaoProject[source]; name = "dusk" }
            DaoCueLayer.new { this.cue = cue; this.look = look; targets = emptyList(); sortOrder = 0 }
            val stack = DaoCueStack.new {
                project = DaoProject[projectId]; name = "Act 1"; loop = false; type = CueStackType.STACK.name; sortOrder = 0
            }
            cue.id.value to stack.id.value
        }
        fun counts() = transaction(state.database) {
            listOf(
                DaoCue.find { DaoCues.project eq projectId }.count(),
                DaoLook.find { DaoLooks.project eq projectId }.count(),
                DaoCueStackEntry.all().count(),
            )
        }
        val before = counts()

        val response = client.copyFrom(CopyCuesFromRequest(fromProjectId = source, cueIds = listOf(cueId), stackId = stackId, dryRun = true))
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.body<CopyCuesFromResponse>()
        assertTrue(body.dryRun)
        assertEquals(listOf(cueId), body.copied.map { it.fromCueId })
        assertNotNull(body.copied.single().entryId, "the answer says where it would go")
        assertEquals(listOf("Cool"), body.looksCopied)
        assertEquals(before, counts(), "nothing written")
    }

    @Test
    fun `copies go into a stack after an entry, in order, or loose`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val source = standalone("Panto")
        val (cues, stackId, first) = transaction(state.database) {
            val cues = listOf("a", "b").map { DaoCue.new { project = DaoProject[source]; name = it }.id.value }
            val stack = DaoCueStack.new {
                project = DaoProject[projectId]; name = "Act 1"; loop = false; type = CueStackType.STACK.name; sortOrder = 0
            }
            val q1 = DaoCue.new { project = DaoProject[projectId]; name = "Q1" }.inStack(stack, sortOrder = 0)
            DaoCue.new { project = DaoProject[projectId]; name = "Q2" }.inStack(stack, sortOrder = 1)
            Triple(cues, stack.id.value, entriesOf(stack).single { it.cue?.id == q1.id }.id.value)
        }

        val placed = client.copyFrom(CopyCuesFromRequest(fromProjectId = source, cueIds = cues, stackId = stackId, after = first))
        assertEquals(HttpStatusCode.Created, placed.status, placed.bodyAsText())
        val body = placed.body<CopyCuesFromResponse>()
        assertEquals(stackId, body.stackId)
        transaction(state.database) {
            val entries = entriesOf(DaoCueStack[stackId])
            assertEquals(listOf("Q1", "a", "b", "Q2"), entries.map { it.cue!!.name })
            assertEquals(body.copied.map { it.entryId }, entries.drop(1).take(2).map { it.id.value })
            assertTrue(entries.drop(1).take(2).all { it.cueNumberAuto || it.cueNumber == null }, "unnumbered")
        }

        val loose = client.copyFrom(CopyCuesFromRequest(fromProjectId = source, cueIds = cues.take(1)))
        val copy = loose.body<CopyCuesFromResponse>().copied.single()
        assertEquals(null, copy.entryId)
        transaction(state.database) { assertTrue(entriesOfCue(DaoCue[copy.cueId]).isEmpty(), "loose") }
    }

    @Test
    fun `refusals name what is wrong and write nothing`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val source = standalone("Panto")
        val (cueId, separator) = transaction(state.database) {
            DaoCue.new { project = DaoProject[source]; name = "a" }.id.value to DaoCueStack.new {
                project = DaoProject[projectId]; name = "—"; loop = false; type = CueStackType.SEPARATOR.name; sortOrder = 0
            }.id.value
        }
        val cases = listOf(
            CopyCuesFromRequest(fromProjectId = source, cueIds = emptyList()) to HttpStatusCode.BadRequest,
            CopyCuesFromRequest(fromProjectId = projectId, cueIds = listOf(cueId)) to HttpStatusCode.BadRequest,
            CopyCuesFromRequest(fromProjectId = 9999, cueIds = listOf(cueId)) to HttpStatusCode.NotFound,
            CopyCuesFromRequest(fromProjectId = source, cueIds = listOf(cueId, 9999)) to HttpStatusCode.NotFound,
            CopyCuesFromRequest(fromProjectId = source, cueIds = listOf(cueId), stackId = separator) to HttpStatusCode.BadRequest,
            CopyCuesFromRequest(fromProjectId = source, cueIds = listOf(cueId), after = 1) to HttpStatusCode.BadRequest,
        )
        for ((request, status) in cases) {
            val response = client.copyFrom(request)
            assertEquals(status, response.status, "$request: ${response.bodyAsText()}")
        }
        transaction(state.database) { assertEquals(0, DaoCue.find { DaoCues.project eq projectId }.count()) }
    }

    @Test
    fun `the old per-cue copy route is gone`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val cueId = transaction(state.database) { DaoCue.new { project = DaoProject[projectId]; name = "a" }.id.value }
        val response = client.post("/api/rest/projects/$projectId/cues/$cueId/copy") {
            contentType(ContentType.Application.Json)
            setBody("""{"targetProjectId":$projectId}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
    }

    private companion object {
        const val TWIN_SHOT = "equinox-twin-shot-mkii"
    }
}
