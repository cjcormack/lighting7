package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.models.LayerSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import uk.me.cormack.lighting7.models.CueLayerDto
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.plugins.UpdateChannelInMessage
import uk.me.cormack.lighting7.plugins.handleUpdateChannel
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `POST /api/rest/programmer/update` — Mode A (include target) and Mode B (checklist). */
class ProgrammerUpdateRouteTest : RouteIntegrationTest() {

    @Test
    fun `Mode A writes back only what the operator changed`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        val cueId = createCue(
            client, "look",
            rows = listOf(
                assignment("fixture", "hex-1", "dimmer", "200"),
                assignment("fixture", "hex-2", "dimmer", "120"),
            ),
        )

        client.include(cueId)
        setProgrammer("hex-1", "dimmer", "255")

        val response: ProgrammerUpdateResponse = client.update(ProgrammerUpdateRequest(
            projectId = projectId.toString(),
        )).body()
        assertTrue(response.applied)
        assertEquals("A", response.mode)
        assertEquals(1, response.results.single().assignmentsWritten, "only the changed row")

        val rows = client.cueRows(cueId)
        assertEquals("255", rows.getValue("hex-1"))
        assertEquals("120", rows.getValue("hex-2"), "untouched rows are left exactly as they were")
    }

    @Test
    fun `Mode A leaves an untouched row in the exact form it was written`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        // A *named* colour is the probe, because Include resolves it to a `Color` and Update would
        // serialise that back as `#ff0000`. So the row surviving as `red` is proof that Update
        // writes back only what changed rather than re-serialising every entry it included.
        //
        // This used to test the same property with `P1`, a positional palette reference. That
        // grammar is gone; the property it was protecting is not, and it matters more now — a cue
        // row is the one place a value must stay exactly as authored.
        val cueId = createCue(
            client, "named-look",
            rows = listOf(
                assignment("fixture", "hex-1", "rgbColour", "red"),
                assignment("fixture", "hex-1", "dimmer", "100"),
            ),
        )

        client.include(cueId)
        setProgrammer("hex-1", "dimmer", "255")
        client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))

        val rows = client.cueRowsByProperty(cueId)
        assertEquals("red", rows.getValue("rgbColour"), "an untouched row must survive Update verbatim")
        assertEquals("255", rows.getValue("dimmer"))
    }

    @Test
    fun `Mode A adds a fixture that was not in the cue`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        val cueId = createCue(
            client, "look", rows = listOf(assignment("fixture", "hex-1", "dimmer", "200")),
        )

        client.include(cueId)
        // No INCLUDE slot behind it — new since the include, so it is always written.
        setProgrammer("hex-2", "dimmer", "60")
        client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))

        assertEquals("60", client.cueRows(cueId).getValue("hex-2"))
    }

    @Test
    fun `Mode A deletes the row of a value cleared since Include`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        val cueId = createCue(
            client, "look",
            rows = listOf(
                assignment("fixture", "hex-1", "dimmer", "200"),
                assignment("fixture", "hex-2", "dimmer", "120"),
            ),
        )

        client.include(cueId)
        ProgrammerHandler.clearEntry(state, TargetRef.Fixture("hex-2"), "dimmer", 0)
        setProgrammer("hex-1", "dimmer", "255")
        val response: ProgrammerUpdateResponse = client.update(ProgrammerUpdateRequest(projectId = projectId.toString())).body()

        assertEquals(1, response.results.single().assignmentsRemoved)
        assertEquals(mapOf("hex-1" to "255"), client.cueRows(cueId))
        // Written back, it is no longer a removal: a second Update deletes nothing more.
        val again: ProgrammerUpdateResponse = client.update(ProgrammerUpdateRequest(projectId = projectId.toString())).body()
        assertEquals(0, again.results.single().assignmentsRemoved)
    }

    @Test
    fun `Mode A splits a cue's group row around a cleared member`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        LocateTestSupport.seedGroup(state, projectId, "front", "hex-1", "hex-2")
        val cueId = createCue(client, "look", rows = listOf(assignment("group", "front", "dimmer", "200")))

        client.include(cueId)
        ProgrammerHandler.clearEntry(state, TargetRef.Fixture("hex-1"), "dimmer", 0)
        val response: ProgrammerUpdateResponse = client.update(ProgrammerUpdateRequest(projectId = projectId.toString())).body()

        assertEquals(1, response.results.single().assignmentsRemoved)
        assertEquals(mapOf("hex-2" to "200"), client.cueRows(cueId))
        assertTrue(response.warnings.isEmpty())
    }

    @Test
    fun `Mode A never deletes a row Include did not stage`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val cueId = createCue(
            client, "look",
            rows = listOf(
                assignment("fixture", "hex-1", "dimmer", "200"),
                assignment("fixture", "hex-1", "rgbColour", "#ff0000"),
            ),
        )

        // Included under Colour, so the programmer never held the dimmer: an Update under every
        // attribute must not read its absence as a clear.
        client.post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerIncludeRequest(projectId = projectId.toString(), cueId = cueId, mask = listOf("COLOUR")))
        }
        setProgrammer("hex-1", "rgbColour", "#00ff00")
        client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))

        val rows = client.cueRowsByProperty(cueId)
        assertEquals("200", rows.getValue("dimmer"))
        assertEquals(2, rows.size)
    }

    @Test
    fun `an Update under a mask leaves a cleared value outside it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val cueId = createCue(
            client, "look",
            rows = listOf(
                assignment("fixture", "hex-1", "dimmer", "200"),
                assignment("fixture", "hex-1", "rgbColour", "#ff0000"),
            ),
        )

        client.include(cueId)
        ProgrammerHandler.clearEntry(state, TargetRef.Fixture("hex-1"), "dimmer", 0)
        client.update(ProgrammerUpdateRequest(projectId = projectId.toString(), mask = listOf("COLOUR")))
        assertEquals("200", client.cueRowsByProperty(cueId).getValue("dimmer"))

        client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))
        assertNull(client.cueRowsByProperty(cueId)["dimmer"])
    }

    @Test
    fun `Mode A writes a nudged head back to its cell-target row`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedBar()
        // Head 3 (pixel-2) white is channel 12; head 4 (pixel-3) white is 16.
        val cueId = createCue(
            client, "cells",
            rows = listOf(
                assignment("fixture", "bar.pixel-2", "white", "100"),
                assignment("fixture", "bar.pixel-3", "white", "50"),
            ),
        )

        client.include(cueId)
        busk(12, 255u)

        val response: ProgrammerUpdateResponse = client.update(ProgrammerUpdateRequest(
            projectId = projectId.toString(),
        )).body()
        assertEquals("A", response.mode)
        assertTrue(response.skipped.isEmpty(), "a head is not skipped: ${response.skipped}")
        assertEquals(1, response.results.single().assignmentsWritten, "only the nudged head")

        val rows = client.cueRows(cueId)
        assertEquals("255", rows.getValue("bar.pixel-2"))
        assertEquals("50", rows.getValue("bar.pixel-3"), "the untouched head is left as it was")
    }

    @Test
    fun `Mode B attributes a busked head to the cue under it and writes it back`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedBar()
        val stack = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-a")
        val cueId = createCue(
            client, "cells",
            rows = listOf(assignment("fixture", "bar.pixel-2", "white", "100")),
            stackId = stack,
        )
        client.post("/api/rest/projects/$projectId/cues/$cueId/apply")
        busk(12, 255u)

        val checklist: ProgrammerUpdateResponse = client.update(ProgrammerUpdateRequest(
            projectId = projectId.toString(),
        )).body()
        assertEquals("CHECKLIST", checklist.mode)
        assertEquals(
            listOf(cueId),
            assertNotNull(checklist.checklist).stacks.flatMap { it.cues }.map { it.cueId },
            "the head is an override of the cue underneath, not unattributed",
        )

        client.update(ProgrammerUpdateRequest(projectId = projectId.toString(), targets = listOf(cueId)))
        assertEquals("255", client.cueRows(cueId).getValue("bar.pixel-2"))
    }

    @Test
    fun `with nothing included the checklist names the overridden cues and their stacks`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            seedHex("hex-1", 1)
            seedHex("hex-2", 13)

            val stackA = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-a")
            val stackB = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-b")
            val cueA = createCue(
                client, "cue-a",
                rows = listOf(assignment("fixture", "hex-1", "dimmer", "100")),
                stackId = stackA,
            )
            val cueB = createCue(
                client, "cue-b",
                rows = listOf(assignment("fixture", "hex-2", "dimmer", "100")),
                stackId = stackB,
            )
            // Each GO'd in its stack: the stack that holds a cue is what the checklist groups it by.
            client.post("/api/rest/projects/$projectId/cue-stacks/$stackA/activate")
            client.post("/api/rest/projects/$projectId/cue-stacks/$stackB/activate")

            // Busk over both, plus one property no cue drives at all.
            setProgrammer("hex-1", "dimmer", "255")
            setProgrammer("hex-2", "dimmer", "10")
            setProgrammer("hex-1", "strobe", "200")

            val response: ProgrammerUpdateResponse = client.update(ProgrammerUpdateRequest(
                projectId = projectId.toString(),
            )).body()

            assertEquals("CHECKLIST", response.mode)
            assertTrue(!response.applied)
            val checklist = assertNotNull(response.checklist)

            // Grouping by stack is what the cueStackId provenance fix bought.
            assertEquals(
                setOf(stackA, stackB),
                checklist.stacks.mapNotNull { it.cueStackId }.toSet(),
            )
            assertEquals(
                setOf(cueA, cueB),
                checklist.stacks.flatMap { it.cues }.map { it.cueId }.toSet(),
            )
            assertTrue(checklist.stacks.flatMap { it.cues }.all { it.isActive })
            assertEquals(
                listOf("strobe"),
                checklist.unattributed.map { it.propertyName },
                "a property no cue drives is programmer-over-baseline, not an override",
            )
        }

    @Test
    fun `a checklist commit writes only the named cue's own keys`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)

        val stackA = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-a")
        val stackB = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-b")
        val cueA = createCue(
            client, "cue-a",
            rows = listOf(assignment("fixture", "hex-1", "dimmer", "100")),
            stackId = stackA,
        )
        val cueB = createCue(
            client, "cue-b",
            rows = listOf(assignment("fixture", "hex-2", "dimmer", "100")),
            stackId = stackB,
        )
        client.post("/api/rest/projects/$projectId/cues/$cueA/apply")
        client.post("/api/rest/projects/$projectId/cues/$cueB/apply")
        setProgrammer("hex-1", "dimmer", "255")
        setProgrammer("hex-2", "dimmer", "10")

        val response: ProgrammerUpdateResponse = client.update(ProgrammerUpdateRequest(
            projectId = projectId.toString(), targets = listOf(cueA),
        )).body()

        assertEquals("B", response.mode)
        assertEquals(listOf(cueA), response.results.map { it.cueId })
        assertEquals("255", client.cueRows(cueA).getValue("hex-1"))
        assertNull(client.cueRows(cueA)["hex-2"], "cue A must not absorb cue B's override")
        assertEquals("100", client.cueRows(cueB).getValue("hex-2"), "cue B was not named")
    }

    @Test
    fun `preview returns the checklist without writing, even with an include target`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            seedHex("hex-1", 1)
            val cueId = createCue(
                client, "look", rows = listOf(assignment("fixture", "hex-1", "dimmer", "200")),
            )
            client.include(cueId)
            setProgrammer("hex-1", "dimmer", "255")

            val response: ProgrammerUpdateResponse = client.update(ProgrammerUpdateRequest(
                projectId = projectId.toString(), preview = true,
            )).body()

            assertEquals("CHECKLIST", response.mode)
            assertTrue(!response.applied)
            assertEquals("200", client.cueRows(cueId).getValue("hex-1"), "nothing was written")
        }

    @Test
    fun `Mode A against a deleted cue reports the target is gone and forgets it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val cueId = createCue(
            client, "look", rows = listOf(assignment("fixture", "hex-1", "dimmer", "200")),
        )
        client.include(cueId)
        setProgrammer("hex-1", "dimmer", "255")

        // Delete via the DAO so the route's own clearIncludeTargetForCue doesn't fire — this
        // pins Update's own lazy re-validation, which is the actual correctness guarantee.
        org.jetbrains.exposed.v1.jdbc.transactions.transaction(state.database) {
            val cue = uk.me.cormack.lighting7.models.DaoCue.findById(cueId)!!
            deleteCueChildren(cue)
            cue.delete()
        }

        val response = client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("INCLUDE_TARGET_GONE", response.body<ProgrammerConflictResponse>().code)
        assertNull(state.show.programmerStore.lastIncludedTarget)
    }

    @Test
    fun `an empty targets list is rejected rather than read as the checklist`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val response = client.update(
            ProgrammerUpdateRequest(projectId = projectId.toString(), targets = emptyList()),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }


    // ─── The layer stack ────────────────────────────────────────────────

    @Test
    fun `Mode A writes back a layer added in the programmer`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val warm = ProgrammerRouteTestSupport.createLookBoundTo(
            client, projectId, "Warm", mapOf("dimmer" to "200"),
        )
        val cueId = createCue(client, "c", rows = listOf(assignment("fixture", "hex-1", "dimmer", "10")))

        client.include(cueId)
        state.show.programmerLayerStack.add(
            source = LayerSource.look(warm.id, java.util.UUID.fromString(warm.uuid), warm.name),
            targets = listOf(CueTargetDto("fixture", "hex-1")),
        )
        client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))

        val layers = client.cueLayers(cueId)
        assertEquals(1, layers.size)
        assertEquals(warm.id, layers.single().lookId)
    }

    @Test
    fun `a layer added after Include is counted, and an Update writes it once and resets the count`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            seedHex("hex-1", 1)
            val warm = ProgrammerRouteTestSupport.createLookBoundTo(
                client, projectId, "Warm", mapOf("dimmer" to "200"),
            )
            val cueId = createCue(client, "c", rows = listOf(assignment("fixture", "hex-1", "dimmer", "10")))
            val store = state.show.programmerStore

            client.include(cueId)
            assertEquals(0, store.layerChangesSinceInclude())
            state.show.programmerLayerStack.add(
                source = LayerSource.look(warm.id, java.util.UUID.fromString(warm.uuid), warm.name),
                targets = listOf(CueTargetDto("fixture", "hex-1")),
            )
            assertEquals(1, store.layerChangesSinceInclude(), "a layer added since Include is a change")

            client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))
            assertEquals(0, store.layerChangesSinceInclude())
            client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))
            assertEquals(1, client.cueLayers(cueId).size, "a second Update does not add the layer again")

            val layerId = store.layers.single().layerId
            state.show.programmerLayerStack.patch(layerId, amount = 0.5)
            assertEquals(1, store.layerChangesSinceInclude())
            client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))
            assertEquals(0.5, client.cueLayers(cueId).single().amount, "the written layer is now retuned in place")
        }

    @Test
    fun `an Update after a Record into the cue does not add the recorded layers again`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val warm = ProgrammerRouteTestSupport.createLookBoundTo(
            client, projectId, "Warm", mapOf("dimmer" to "200"),
        )
        val cueId = createCue(client, "c", rows = listOf(assignment("fixture", "hex-1", "dimmer", "10")))
        state.show.programmerLayerStack.add(
            source = LayerSource.look(warm.id, java.util.UUID.fromString(warm.uuid), warm.name),
            targets = listOf(CueTargetDto("fixture", "hex-1")),
        )

        client.post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "MERGE", cueId = cueId))
        }
        assertEquals(cueId, state.show.programmerStore.lastIncludedTarget?.cueId)
        assertEquals(0, state.show.programmerStore.layerChangesSinceInclude())

        client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))
        assertEquals(1, client.cueLayers(cueId).size)
    }

    @Test
    fun `after a Record into a cue, Update writes only what changed since, not what the Record left out`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            seedHex("hex-1", 1)
            seedHex("hex-2", 13)
            val cueId = createCue(client, "c", rows = listOf(assignment("fixture", "hex-1", "dimmer", "10")))
            setProgrammer("hex-1", "dimmer", "200")
            setProgrammer("hex-2", "dimmer", "120")

            client.post("/api/rest/programmer/record") {
                contentType(ContentType.Application.Json)
                setBody(ProgrammerRecordRequest(
                    projectId = projectId.toString(), mode = "MERGE", cueId = cueId,
                    targets = listOf(CueTargetDto("fixture", "hex-1")),
                ))
            }
            setProgrammer("hex-1", "dimmer", "255")
            client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))

            assertEquals(mapOf("hex-1" to "255"), client.cueRows(cueId), "hex-2 was outside the Record")
        }

    @Test
    fun `moving one layer is one change`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val looks = (1..4).map { ProgrammerRouteTestSupport.createLookBoundTo(client, projectId, "L$it", mapOf("dimmer" to "${it * 10}")) }
        val targets = listOf(CueTargetDto("fixture", "hex-1"))
        val cueId = createCue(
            client, "c",
            layers = looks.mapIndexed { i, look -> CueLayerDto(lookId = look.id, sortOrder = i, targets = targets) },
        )
        client.include(cueId)
        val store = state.show.programmerStore
        state.show.programmerLayerStack.move(store.layers.first().layerId, 3)
        assertEquals(1, store.layerChangesSinceInclude())
    }

    @Test
    fun `a Look of scenery alone pressed over an included cue is a change, written once`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val cueId = createCue(client, "c", rows = listOf(assignment("fixture", "hex-1", "dimmer", "10")))
        val moon = client.post("/api/rest/projects/$projectId/stage-elements") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("name", "Moon"); put("kind", "OBJECT"); put("layer", "SET")
                put("positionZ", 3.0); put("widthM", 1.0); put("depthM", 0.05); put("heightM", 1.0)
                putJsonObject("params") { put("shape", "DISC"); put("flies", true); putJsonObject("states") { put("trimM", 7.0) } }
            })
        }.body<StageElementDto>().uuid
        val look: LookDetails = client.post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = "Moon in"))
        }.body()
        client.put("/api/rest/projects/$projectId/looks/${look.id}/scenery") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                putJsonArray("scenery") {
                    add(buildJsonObject { put("elementUuid", moon); putJsonObject("state") { put("trimM", 3.0) } })
                }
            })
        }
        val store = state.show.programmerStore

        client.include(cueId)
        val pressed = client.post("/api/rest/projects/$projectId/looks/${look.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleLookRequest())
        }
        assertEquals(HttpStatusCode.OK, pressed.status)
        assertTrue(store.layers.single().targets.isEmpty(), "a layer of no fixture, only scenery")
        assertEquals(1, store.layerChangesSinceInclude())

        client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))
        assertEquals(0, store.layerChangesSinceInclude())
        client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))
        val layers = client.cueLayers(cueId)
        assertEquals(1, layers.size)
        assertEquals(look.id, layers.single().lookId)
    }

    @Test
    fun `a Record that flattens the stack into rows leaves Update nothing to add`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val warm = ProgrammerRouteTestSupport.createLookBoundTo(client, projectId, "Warm", mapOf("dimmer" to "200"))
        val cueId = createCue(client, "c", rows = listOf(assignment("fixture", "hex-1", "dimmer", "10")))
        val store = state.show.programmerStore

        client.include(cueId)
        state.show.programmerLayerStack.add(
            source = LayerSource.look(warm.id, java.util.UUID.fromString(warm.uuid), warm.name),
            targets = listOf(CueTargetDto("fixture", "hex-1")),
        )
        client.post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "MERGE", cueId = cueId, source = "ALL"))
        }
        assertEquals(0, store.layerChangesSinceInclude(), "the stack is in the cue, as rows")

        client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))
        assertTrue(client.cueLayers(cueId).isEmpty(), "no layer over the rows that hold its values")
    }

    @Test
    fun `an Update after a Record moves the Record's baseline, so a value put back is written back`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            seedHex("hex-1", 1)
            val cueId = createCue(client, "c", rows = listOf(assignment("fixture", "hex-1", "dimmer", "10")))
            setProgrammer("hex-1", "dimmer", "200")
            client.post("/api/rest/programmer/record") {
                contentType(ContentType.Application.Json)
                setBody(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "MERGE", cueId = cueId))
            }

            setProgrammer("hex-1", "dimmer", "255")
            client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))
            setProgrammer("hex-1", "dimmer", "200")
            client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))
            assertEquals("200", client.cueRows(cueId).getValue("hex-1"))
        }

    @Test
    fun `a layer pressed after the writer read the stack stays a change after the rebase`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val warm = ProgrammerRouteTestSupport.createLookBoundTo(client, projectId, "Warm", mapOf("dimmer" to "200"))
        val cueId = createCue(client, "c", rows = listOf(assignment("fixture", "hex-1", "dimmer", "10")))
        val store = state.show.programmerStore
        client.include(cueId)
        state.show.programmerLayerStack.add(
            source = LayerSource.look(warm.id, java.util.UUID.fromString(warm.uuid), warm.name),
            targets = listOf(CueTargetDto("fixture", "hex-1")),
        )

        store.rebaseLayers(seen = emptySet()) { null }
        assertEquals(1, store.layerChangesSinceInclude(), "unseen, so still new to the cue")
        store.rebaseLayers(seen = setOf(store.layers.single().layerId)) { null }
        assertEquals(0, store.layerChangesSinceInclude(), "seen and given no row: flattened")
    }

    @Test
    fun `the layer count is the cue's alone`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val warm = ProgrammerRouteTestSupport.createLookBoundTo(
            client, projectId, "Warm", mapOf("dimmer" to "200"),
        )
        client.post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerIncludeRequest(projectId = projectId.toString(), lookId = warm.id))
        }
        state.show.programmerLayerStack.add(
            source = LayerSource.look(warm.id, java.util.UUID.fromString(warm.uuid), warm.name),
            targets = listOf(CueTargetDto("fixture", "hex-1")),
        )
        assertEquals(0, state.show.programmerStore.layerChangesSinceInclude(), "Update into a Look writes no layer")
    }

    @Test
    fun `Mode A deletes a layer the operator removed`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val warm = ProgrammerRouteTestSupport.createLookBoundTo(
            client, projectId, "Warm", mapOf("dimmer" to "200"),
        )
        val cueId = createCue(
            client, "c",
            layers = listOf(CueLayerDto(lookId = warm.id, targets = listOf(CueTargetDto("fixture", "hex-1")))),
        )

        client.include(cueId)
        val layerId = state.show.programmerStore.layers.single().layerId
        state.show.programmerLayerStack.remove(layerId)
        client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))

        assertTrue(client.cueLayers(cueId).isEmpty())
    }

    @Test
    fun `Mode A writes back a reorder, densely renumbered`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val a = ProgrammerRouteTestSupport.createLookBoundTo(client, projectId, "A", mapOf("dimmer" to "200"))
        val b = ProgrammerRouteTestSupport.createLookBoundTo(client, projectId, "B", mapOf("dimmer" to "40"))
        val targets = listOf(CueTargetDto("fixture", "hex-1"))
        val cueId = createCue(
            client, "c",
            layers = listOf(
                CueLayerDto(lookId = a.id, sortOrder = 0, targets = targets),
                CueLayerDto(lookId = b.id, sortOrder = 1, targets = targets),
            ),
        )

        client.include(cueId)
        val first = state.show.programmerStore.layers.first().layerId
        state.show.programmerLayerStack.move(first, 1)
        client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))

        val layers = client.cueLayers(cueId).sortedBy { it.sortOrder }
        assertEquals(listOf(b.id, a.id), layers.map { it.source!!.id }, "the new order persisted")
        assertEquals(listOf(0, 1), layers.map { it.sortOrder }, "densely renumbered, so no ties")
    }

    @Test
    fun `Mode A writes back a retuned amount without touching identity`() = testApplication {
        // Field-by-field, not data-class equality: `layerId` and `sortOrder` differ by construction,
        // so `!=` on the whole object would report every layer as changed on every Update.
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val warm = ProgrammerRouteTestSupport.createLookBoundTo(
            client, projectId, "Warm", mapOf("dimmer" to "200"),
        )
        val cueId = createCue(
            client, "c",
            layers = listOf(CueLayerDto(lookId = warm.id, targets = listOf(CueTargetDto("fixture", "hex-1")))),
        )

        client.include(cueId)
        val layerId = state.show.programmerStore.layers.single().layerId
        state.show.programmerLayerStack.patch(layerId, amount = 0.5)
        client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))

        val layer = client.cueLayers(cueId).single()
        assertEquals(0.5, layer.amount)
        assertEquals(warm.id, layer.source!!.id, "still the same Look — identity was matched, not rewritten")
    }

    @Test
    fun `a timed layer survives an Update it was never included into`() = testApplication {
        // Include drops timed layers because the programmer cannot fire them. Update must therefore
        // not read their absence from the stack as a deletion — otherwise "Include, tweak, Update"
        // would quietly strip every delayed layer from a chase.
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val warm = ProgrammerRouteTestSupport.createLookBoundTo(
            client, projectId, "Warm", mapOf("dimmer" to "200"),
        )
        val targets = listOf(CueTargetDto("fixture", "hex-1"))
        val cueId = createCue(
            client, "c",
            layers = listOf(
                CueLayerDto(lookId = warm.id, sortOrder = 0, targets = targets),
                CueLayerDto(lookId = warm.id, sortOrder = 1, targets = targets, delayMs = 2_000L),
            ),
        )

        client.include(cueId)
        assertEquals(1, state.show.programmerStore.layers.size, "only the immediate one arrived")
        setProgrammer("hex-1", "dimmer", "55")
        client.update(ProgrammerUpdateRequest(projectId = projectId.toString()))

        val layers = client.cueLayers(cueId)
        assertEquals(2, layers.size, "the timed layer is still there")
        assertNotNull(layers.firstOrNull { it.delayMs == 2_000L })
    }

    @Test
    fun `clearing the programmer forgets the layer baseline too`() = testApplication {
        // A baseline outliving the stack it describes would make the next Update diff against
        // layers that no longer exist — and report every one of them as deleted.
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val warm = ProgrammerRouteTestSupport.createLookBoundTo(
            client, projectId, "Warm", mapOf("dimmer" to "200"),
        )
        val cueId = createCue(
            client, "c",
            layers = listOf(CueLayerDto(lookId = warm.id, targets = listOf(CueTargetDto("fixture", "hex-1")))),
        )
        client.include(cueId)
        assertEquals(1, state.show.programmerStore.includedLayerSnapshot.size)

        state.show.programmerStore.clearAll()

        assertTrue(state.show.programmerStore.includedLayerSnapshot.isEmpty())
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private suspend fun HttpClient.include(cueId: Int) =
        post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerIncludeRequest(projectId = projectId.toString(), cueId = cueId))
        }

    private suspend fun HttpClient.update(request: ProgrammerUpdateRequest) =
        post("/api/rest/programmer/update") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    /** The cue's assignments as `targetKey → value`. */
    private suspend fun HttpClient.cueRows(cueId: Int): Map<String, String> =
        get("/api/rest/projects/$projectId/cues/$cueId")
            .body<CueDetails>().propertyAssignments.associate { it.targetKey to it.value }

    private suspend fun HttpClient.cueRowsByProperty(cueId: Int): Map<String, String> =
        get("/api/rest/projects/$projectId/cues/$cueId")
            .body<CueDetails>().propertyAssignments.associate { it.propertyName to it.value }

    private fun assignment(type: String, key: String, property: String, value: String) =
        CuePropertyAssignmentDto(
            targetType = type, targetKey = key, propertyName = property, value = value,
        )

    private suspend fun createCue(
        client: HttpClient,
        name: String,
        rows: List<CuePropertyAssignmentDto> = emptyList(),
        stackId: Int? = null,
        layers: List<CueLayerDto> = emptyList(),
    ): Int = ProgrammerRouteTestSupport.createCue(
        client, projectId, name, rows, stackId = stackId, layers = layers,
    )

    private suspend fun HttpClient.cueLayers(cueId: Int): List<CueLayerDto> =
        get("/api/rest/projects/$projectId/cues/$cueId").body<CueDetails>().layers

    private fun setProgrammer(fixtureKey: String, property: String, value: String) {
        ProgrammerHandler.set(state, TargetRef.Fixture(fixtureKey), property, value, 0)
    }

    private fun seedHex(key: String, startChannel: Int) =
        LocateTestSupport.seedHex(state, projectId, key, startChannel)

    /** A `led-lightbar-12-pixel-48ch` keyed `bar` at channel 1 — twelve heads, four channels each. */
    private fun seedBar() =
        LocateTestSupport.seedFixture(state, projectId, "led-lightbar-12-pixel-48ch", "bar", 1)

    /** A raw channel write, the DMX sheet's Set. */
    private fun busk(channel: Int, level: UByte) =
        handleUpdateChannel(state, UpdateChannelInMessage(0, channel, level, fadeTime = 0))
}
