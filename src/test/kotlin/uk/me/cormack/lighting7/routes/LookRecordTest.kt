package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST /api/rest/programmer/record-look`, and Mode A Update writing back into a Look.
 *
 * Together these are the gesture the Look library was missing: **creating a bound Look**, and then
 * editing one on stage. Both used to be impossible — the only record destination was the retired
 * palette tables, and a `LOOK` include target was refused outright with `INCLUDE_TARGET_READ_ONLY`.
 */
class LookRecordTest : RouteIntegrationTest() {

    @Test
    fun `record-look CREATE turns the programmer into a bound Look`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        setProgrammer("hex-1", "dimmer", "200")
        setProgrammer("hex-2", "dimmer", "120")

        val response: ProgrammerRecordLookResponse = client.recordLook(
            ProgrammerRecordLookRequest(
                projectId = projectId.toString(), mode = "CREATE", name = "Warm Wash",
            )
        ).body()

        assertTrue(response.created)
        assertEquals(2, response.rowsWritten)
        assertEquals("Warm Wash", response.look.name)
        assertEquals(mapOf("hex-1" to "200", "hex-2" to "120"), response.look.rowsByTarget())
        // Bound, not a template: every row names its own fixture, which is what distinguishes a
        // recorded Look from one authored against a synthetic fixture.
        assertTrue(
            response.look.rows.all { it.targetType == TargetRef.Fixture.TYPE },
            "a recorded Look is bound",
        )
        assertTrue(
            response.look.families.contains("INTENSITY"),
            "families are derived from the rows rather than declared",
        )
    }

    @Test
    fun `record-look points the include target at the Look it wrote, so Update writes back there`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            seedHex("hex-1", 1)
            val other = client.createLook("Other", LookRowDto("fixture", "hex-1", "dimmer", "10"))
            client.includeLook(other)
            val before = assertNotNull(state.show.programmerStore.lastIncludedTarget).baseline
            setProgrammer("hex-1", "dimmer", "200")

            val recorded: ProgrammerRecordLookResponse = client.recordLook(
                ProgrammerRecordLookRequest(projectId = projectId.toString(), mode = "CREATE", name = "Warm"),
            ).body()
            val target = assertNotNull(state.show.programmerStore.lastIncludedTarget)
            assertEquals(recorded.look.id, target.lookId)
            assertTrue(target.baseline > before, "the client re-takes its change count from here")

            setProgrammer("hex-1", "dimmer", "255")
            val response: ProgrammerUpdateResponse = client.update().body()
            assertEquals(recorded.look.id, assertNotNull(response.lookResult).lookId)
            assertEquals("255", client.lookRows(recorded.look.id).getValue("hex-1" to "dimmer"))
            assertEquals("10", client.lookRows(other).getValue("hex-1" to "dimmer"), "the earlier Look is left alone")
        }

    @Test
    fun `record-look with retarget false leaves the include target where it was`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            seedHex("hex-1", 1)
            val other = client.createLook("Other", LookRowDto("fixture", "hex-1", "dimmer", "10"))
            client.includeLook(other)
            setProgrammer("hex-1", "dimmer", "200")

            client.recordLook(
                ProgrammerRecordLookRequest(projectId = projectId.toString(), mode = "CREATE", name = "Warm", retarget = false),
            )
            assertEquals(other, state.show.programmerStore.lastIncludedTarget?.lookId)
        }

    @Test
    fun `after a scoped record-look, Update writes what changed and what the Record left out`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            seedHex("hex-1", 1)
            seedHex("hex-2", 13)
            setProgrammer("hex-1", "dimmer", "200")
            setProgrammer("hex-2", "dimmer", "120")

            val recorded: ProgrammerRecordLookResponse = client.recordLook(
                ProgrammerRecordLookRequest(
                    projectId = projectId.toString(), mode = "CREATE", name = "Warm",
                    targets = listOf(CueTargetDto("fixture", "hex-1")),
                ),
            ).body()
            setProgrammer("hex-1", "dimmer", "255")
            client.update()

            assertEquals(
                mapOf(("hex-1" to "dimmer") to "255", ("hex-2" to "dimmer") to "120"),
                client.lookRows(recorded.look.id),
                "the Look lacked hex-2",
            )
            assertEquals(0, heldOutsideSource(state), "the Look now stores both")
        }

    @Test
    fun `record-look CREATE refuses a name already in use`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        setProgrammer("hex-1", "dimmer", "200")
        client.recordLook(ProgrammerRecordLookRequest(projectId.toString(), "CREATE", name = "Warm"))

        val second = client.recordLook(
            ProgrammerRecordLookRequest(projectId.toString(), "CREATE", name = "Warm")
        )
        // `DaoLooks` carries uniqueIndex(project, name), so this must be caught and reported
        // rather than surfacing as a constraint violation 500.
        assertEquals(HttpStatusCode.BadRequest, second.status)
    }

    @Test
    fun `record-look rejects an unknown mask rather than recording more than was asked`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            seedHex("hex-1", 1)
            setProgrammer("hex-1", "dimmer", "200")

            val response = client.recordLook(
                ProgrammerRecordLookRequest(
                    projectId.toString(), "CREATE", name = "Warm", mask = listOf("BRIGHTNESS"),
                )
            )
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `record-look masks by an explicit mask, because a Look has no type to imply one`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            seedHex("hex-1", 1)
            setProgrammer("hex-1", "dimmer", "200")
            setProgrammer("hex-1", "rgbColour", "#ff0000")

            val response: ProgrammerRecordLookResponse = client.recordLook(
                ProgrammerRecordLookRequest(
                    projectId.toString(), "CREATE", name = "Just Colour", mask = listOf("COLOUR"),
                )
            ).body()

            assertEquals(listOf("rgbColour"), response.look.rows.map { it.attribute })
        }

    @Test
    fun `UPDATE_EXISTING replaces only rows inside the mask and the target scope`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            seedHex("hex-1", 1)
            seedHex("hex-2", 13)
            val lookId = client.createLook(
                "Warm",
                LookRowDto("fixture", "hex-1", "dimmer", "200"),
                LookRowDto("fixture", "hex-1", "rgbColour", "#ff0000"),
                LookRowDto("fixture", "hex-2", "dimmer", "50"),
            )

            setProgrammer("hex-1", "dimmer", "255")
            val response: ProgrammerRecordLookResponse = client.recordLook(
                ProgrammerRecordLookRequest(
                    projectId.toString(), "UPDATE_EXISTING", lookId = lookId,
                    mask = listOf("INTENSITY"),
                    targets = listOf(uk.me.cormack.lighting7.models.CueTargetDto("fixture", "hex-1")),
                )
            ).body()

            assertFalse(response.created)
            val rows = client.lookRows(lookId)
            assertEquals("255", rows.getValue("hex-1" to "dimmer"), "in mask and in scope: replaced")
            // These two are the whole reason a Look takes the *cue* write rule rather than the
            // palette's replace-outright: neither was named by the operator's mask or selection.
            assertEquals(
                "#ff0000", rows.getValue("hex-1" to "rgbColour"),
                "out of mask — a COLOUR row survives an INTENSITY re-record",
            )
            assertEquals(
                "50", rows.getValue("hex-2" to "dimmer"),
                "out of scope — an unselected fixture survives",
            )
        }

    @Test
    fun `UPDATE_EXISTING writes over an in-remit row it records again, keeping its uuid`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            seedHex("hex-1", 1)
            seedHex("hex-2", 13)
            val lookId = client.createLook(
                "Warm",
                LookRowDto("fixture", "hex-1", "dimmer", "200"),
                LookRowDto("fixture", "hex-2", "dimmer", "50"),
            )
            val before = rowUuids(lookId)

            setProgrammer("hex-1", "dimmer", "255")
            val response: ProgrammerRecordLookResponse = client.recordLook(
                ProgrammerRecordLookRequest(
                    projectId.toString(), "UPDATE_EXISTING", lookId = lookId,
                    targets = listOf(
                        uk.me.cormack.lighting7.models.CueTargetDto("fixture", "hex-1"),
                        uk.me.cormack.lighting7.models.CueTargetDto("fixture", "hex-2"),
                    ),
                )
            ).body()

            assertEquals(2, response.rowsRemoved, "both in-remit rows are still replaced")
            assertEquals(mapOf(("hex-1" to "dimmer") to "255"), client.lookRows(lookId))
            assertEquals(before.getValue("hex-1"), rowUuids(lookId).getValue("hex-1"))
        }

    @Test
    fun `MERGE upserts the recorded rows and leaves the rest alone`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        val lookId = client.createLook(
            "Warm",
            LookRowDto("fixture", "hex-1", "dimmer", "200"),
            LookRowDto("fixture", "hex-2", "dimmer", "50"),
        )

        setProgrammer("hex-1", "dimmer", "10")
        client.recordLook(
            ProgrammerRecordLookRequest(projectId.toString(), "MERGE", lookId = lookId)
        )

        val rows = client.lookRows(lookId)
        assertEquals("10", rows.getValue("hex-1" to "dimmer"))
        assertEquals("50", rows.getValue("hex-2" to "dimmer"))
    }

    @Test
    fun `REMOVE deletes the rows the recording names`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        val lookId = client.createLook(
            "Warm",
            LookRowDto("fixture", "hex-1", "dimmer", "200"),
            LookRowDto("fixture", "hex-2", "dimmer", "50"),
        )

        setProgrammer("hex-1", "dimmer", "200")
        val response: ProgrammerRecordLookResponse = client.recordLook(
            ProgrammerRecordLookRequest(projectId.toString(), "REMOVE", lookId = lookId)
        ).body()

        assertEquals(1, response.rowsRemoved)
        assertEquals(setOf("hex-2" to "dimmer"), client.lookRows(lookId).keys)
    }

    // ── Mode A Update, writing back into a Look ─────────────────────────────

    @Test
    fun `Include a Look, edit on stage, Update writes it back`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val lookId = client.createLook("Warm", LookRowDto("fixture", "hex-1", "dimmer", "200"))

        client.includeLook(lookId)
        setProgrammer("hex-1", "dimmer", "255")

        val response: ProgrammerUpdateResponse = client.update().body()
        assertTrue(response.applied)
        assertEquals("A", response.mode)
        val result = assertNotNull(response.lookResult, "a Look target reports through lookResult")
        assertEquals(lookId, result.lookId)
        assertEquals(1, result.rowsWritten)

        assertEquals("255", client.lookRows(lookId).getValue("hex-1" to "dimmer"))
    }

    @Test
    fun `Update into a Look writes only what changed since Include`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        val lookId = client.createLook(
            "Warm",
            LookRowDto("fixture", "hex-1", "dimmer", "200"),
            LookRowDto("fixture", "hex-2", "dimmer", "120"),
        )

        client.includeLook(lookId)
        setProgrammer("hex-1", "dimmer", "255")

        val response: ProgrammerUpdateResponse = client.update().body()
        assertEquals(1, assertNotNull(response.lookResult).rowsWritten, "only the changed row")
        val rows = client.lookRows(lookId)
        assertEquals("255", rows.getValue("hex-1" to "dimmer"))
        assertEquals("120", rows.getValue("hex-2" to "dimmer"), "untouched rows are left alone")
    }

    @Test
    fun `Update into a Look deletes the row of a value cleared since Include`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        val lookId = client.createLook(
            "Warm",
            LookRowDto("fixture", "hex-1", "dimmer", "200"),
            LookRowDto("fixture", "hex-2", "dimmer", "120"),
        )

        client.includeLook(lookId)
        // A single-entry clear, not `clear-all`: clearing everything also forgets the include
        // target (pinned by ProgrammerIncludeRouteTest), which would send this through Mode B.
        clearProgrammer("hex-2", "dimmer")

        val response: ProgrammerUpdateResponse = client.update().body()
        assertTrue(response.applied, "a removal alone is a write")
        val result = assertNotNull(response.lookResult)
        assertEquals(0, result.rowsWritten)
        assertEquals(1, result.rowsRemoved)
        assertEquals(mapOf(("hex-1" to "dimmer") to "200"), client.lookRows(lookId))

        // Including it again brings back only what the Look now holds.
        client.includeLook(lookId)
        assertNull(state.show.programmerStore.slotsFor("hex-2", "dimmer").firstOrNull())
    }

    @Test
    fun `a mover's cleared position comes out of the Look and stays out`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedFixture(state, projectId, "martin-mac-250-mode-4", "mac-1", 240)
        val lookId = client.createLook(
            "Set — Market",
            LookRowDto("fixture", "mac-1", "dimmer", "255"),
            LookRowDto("fixture", "mac-1", "position", "108,168"),
        )

        client.includeLook(lookId)
        clearProgrammer("mac-1", "position")
        assertEquals(1, assertNotNull(client.update().body<ProgrammerUpdateResponse>().lookResult).rowsRemoved)

        client.includeLook(lookId)
        assertEquals(mapOf(("mac-1" to "dimmer") to "255"), client.lookRows(lookId))
        assertTrue(state.show.programmerStore.slotsFor("mac-1", "position").isEmpty())
    }

    @Test
    fun `clearing a position takes its fine halves out of the Look too`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedFixture(state, projectId, "shehds-led19-rgbw-24ch", "spot-1", 1)
        val lookId = client.createLook(
            "Set — Market",
            LookRowDto("fixture", "spot-1", "position", "108,168"),
            LookRowDto("fixture", "spot-1", "panFine", "53"),
            LookRowDto("fixture", "spot-1", "tiltFine", "207"),
        )

        client.includeLook(lookId)
        clearProgrammer("spot-1", "position")
        val response: ProgrammerUpdateResponse = client.update().body()

        assertEquals(3, assertNotNull(response.lookResult).rowsRemoved)
        assertEquals(emptyMap(), client.lookRows(lookId))
    }

    @Test
    fun `an Update moves the include target's baseline`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val lookId = client.createLook("Warm", LookRowDto("fixture", "hex-1", "dimmer", "200"))
        client.includeLook(lookId)
        val before = assertNotNull(state.show.programmerStore.lastIncludedTarget).baseline

        setProgrammer("hex-1", "dimmer", "30")
        client.update()
        val after = assertNotNull(state.show.programmerStore.lastIncludedTarget)
        assertEquals(lookId, after.lookId)
        assertTrue(after.baseline > before, "a client re-takes its baseline from the next state frame")
    }

    @Test
    fun `an Update with nothing changed or cleared writes nothing and says so`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val lookId = client.createLook("Warm", LookRowDto("fixture", "hex-1", "dimmer", "200"))

        client.includeLook(lookId)
        val response: ProgrammerUpdateResponse = client.update().body()
        assertFalse(response.applied)
        assertEquals("A", response.mode)
        assertEquals(0, assertNotNull(response.lookResult).rowsRemoved)
    }

    @Test
    fun `clearing one member splits the group row, so the rest keep their value`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        LocateTestSupport.seedGroup(state, projectId, "front", "hex-1", "hex-2")
        val lookId = client.createLook("Warm", LookRowDto("group", "front", "dimmer", "200"))

        client.includeLook(lookId)
        clearProgrammer("hex-1", "dimmer")
        val result = assertNotNull(client.update().body<ProgrammerUpdateResponse>().lookResult)
        assertEquals(1, result.rowsRemoved)
        assertEquals(1, result.rowsWritten)
        assertEquals(mapOf(("hex-2" to "dimmer") to "200"), client.lookRows(lookId))
    }

    @Test
    fun `a member with its own row loses both that row and the group's hold on it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        LocateTestSupport.seedGroup(state, projectId, "front", "hex-1", "hex-2")
        val lookId = client.createLook(
            "Warm",
            LookRowDto("group", "front", "dimmer", "200"),
            LookRowDto("fixture", "hex-1", "dimmer", "255"),
        )

        client.includeLook(lookId)
        clearProgrammer("hex-1", "dimmer")
        client.update()
        assertEquals(mapOf(("hex-2" to "dimmer") to "200"), client.lookRows(lookId))
    }

    @Test
    fun `a row added back after an Update is not deleted by the next one`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        val lookId = client.createLook(
            "Warm",
            LookRowDto("fixture", "hex-1", "dimmer", "200"),
            LookRowDto("fixture", "hex-2", "dimmer", "120"),
        )
        client.includeLook(lookId)
        clearProgrammer("hex-2", "dimmer")
        client.update()

        transaction(state.database) {
            uk.me.cormack.lighting7.models.DaoLookRow.new {
                look = DaoLook[lookId]
                targetType = "fixture"
                targetKey = "hex-2"
                attribute = "dimmer"
                value = "80"
                sortOrder = 9
            }
        }
        setProgrammer("hex-1", "dimmer", "30")
        client.update()
        assertEquals("80", client.lookRows(lookId).getValue("hex-2" to "dimmer"))
    }

    @Test
    fun `a row Include could not stage is never read as cleared`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        // A hex has no pan or tilt, so Include stages nothing for this row.
        val lookId = client.createLook(
            "Warm",
            LookRowDto("fixture", "hex-1", "dimmer", "200"),
            LookRowDto("fixture", "hex-1", "position", "108,168"),
        )
        client.includeLook(lookId)
        setProgrammer("hex-1", "dimmer", "30")
        client.update()
        assertEquals("108,168", client.lookRows(lookId).getValue("hex-1" to "position"))
    }

    @Test
    fun `a Clear drops the Include baseline`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val lookId = client.createLook("Warm", LookRowDto("fixture", "hex-1", "dimmer", "200"))
        client.includeLook(lookId)
        assertNotNull(state.show.programmerStore.includedKeys)
        clearProgrammerCompletely(state)
        assertNull(state.show.programmerStore.includedKeys)
    }

    @Test
    fun `Mode A against a deleted Look reports the target is gone and forgets it`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            seedHex("hex-1", 1)
            val lookId = client.createLook("Warm", LookRowDto("fixture", "hex-1", "dimmer", "200"))
            client.includeLook(lookId)
            setProgrammer("hex-1", "dimmer", "255")

            // Deleted via the DAO so the delete route's own clearIncludeTargetForLook doesn't
            // fire — this pins Update's own lazy re-validation, which is the real guarantee.
            transaction(state.database) {
                val look = DaoLook.findById(lookId)!!
                look.rows.forEach { it.delete() }
                look.effects.forEach { it.delete() }
                look.delete()
            }

            val response = client.update()
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("INCLUDE_TARGET_GONE", response.body<ProgrammerConflictResponse>().code)
            assertNull(state.show.programmerStore.lastIncludedTarget)
        }

    @Test
    fun `a Look include is no longer read-only`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val lookId = client.createLook("Warm", LookRowDto("fixture", "hex-1", "dimmer", "200"))
        client.includeLook(lookId)
        setProgrammer("hex-1", "dimmer", "255")

        // The regression this guards: a `LOOK` target used to 409 with INCLUDE_TARGET_READ_ONLY,
        // and the desk disabled its own Update button to match.
        assertEquals(HttpStatusCode.OK, client.update().status)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private suspend fun HttpClient.recordLook(request: ProgrammerRecordLookRequest) =
        post("/api/rest/programmer/record-look") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.includeLook(lookId: Int) =
        post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerIncludeRequest(projectId = projectId.toString(), lookId = lookId))
        }

    private suspend fun HttpClient.update() =
        post("/api/rest/programmer/update") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerUpdateRequest(projectId = projectId.toString()))
        }

    private suspend fun HttpClient.createLook(name: String, vararg rows: LookRowDto): Int =
        post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = name, rows = rows.toList()))
        }.body<LookDetails>().id

    /** The Look's rows as `(targetKey, attribute) → value`. */
    private suspend fun HttpClient.lookRows(lookId: Int): Map<Pair<String, String>, String> =
        get("/api/rest/projects/$projectId/looks/$lookId")
            .body<LookDetails>().rows.associate { (it.targetKey to it.attribute) to it.value }

    private fun rowUuids(lookId: Int) = transaction(state.database) {
        DaoLook[lookId].rows.associate { it.targetKey to it.uuid }
    }

    private fun LookDetails.rowsByTarget(): Map<String, String> =
        rows.associate { it.targetKey to it.value }

    private fun clearProgrammer(fixtureKey: String, attribute: String) {
        ProgrammerHandler.clearEntry(state, TargetRef.Fixture(fixtureKey), attribute, 0)
    }

    private fun setProgrammer(fixtureKey: String, attribute: String, value: String) {
        ProgrammerHandler.set(state, TargetRef.Fixture(fixtureKey), attribute, value, 0)
    }

    private fun seedHex(key: String, startChannel: Int) =
        LocateTestSupport.seedHex(state, projectId, key, startChannel)
}
