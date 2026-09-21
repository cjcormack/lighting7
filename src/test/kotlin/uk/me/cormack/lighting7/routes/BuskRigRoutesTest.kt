package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoBuskRigRow
import uk.me.cormack.lighting7.models.DaoBuskRigTile
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.plugins.BuskRigChangedOutMessage
import uk.me.cormack.lighting7.plugins.ChannelMappingStateOutMessage
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The busk rig at route level (busk-further plan §3.2): the whole-document write and its three
 * refusals, the nested read with its embedded group and patch summaries, dense renumbering with the
 * ids the write minted, and the hand-rolled sweeps — a group delete and a patch delete taking their
 * tiles with them (the importer's is in `ProjectRoundTripTest`).
 */
class BuskRigRoutesTest : RouteIntegrationTest() {

    private fun rig() = "/api/rest/projects/$projectId/busk/rig"

    private fun seedRig() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        LocateTestSupport.seedFixture(state, projectId, "led-lightbar-12-pixel-48ch", "bar-1", 100)
        LocateTestSupport.seedGroup(state, projectId, "front-wash", "hex-1", "hex-2")
        state.show.fixtures.patchListChanged()
    }

    private fun groupId(name: String) = transaction(state.database) {
        DaoFixtureGroup.find { DaoFixtureGroups.name eq name }.first().id.value
    }

    private fun patchId(key: String) = transaction(state.database) {
        DaoFixturePatch.find { DaoFixturePatches.key eq key }.first().id.value
    }

    private suspend fun HttpClient.putRig(request: BuskRigRequest): HttpResponse =
        put(rig()) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.writeRig(request: BuskRigRequest): BuskRigDto {
        val resp = putRig(request)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.readRig(): BuskRigDto = get(rig()).body()

    private fun row(name: String, vararg tiles: BuskRigTileInput, rowId: Int? = null, flow: String = "SCROLL", width: Int = 12) =
        BuskRigRowInput(rowId = rowId, name = name, flow = flow, width = width, tiles = tiles.toList())

    private fun group(id: Int, tileId: Int? = null, label: String? = null) =
        BuskRigTileInput(tileId = tileId, groupId = id, label = label)

    private fun fixture(id: Int, cellMode: String = "PIPS", cellSplit: Int? = null, elementKey: String? = null, tileId: Int? = null, label: String? = null) =
        BuskRigTileInput(tileId = tileId, patchId = id, elementKey = elementKey, cellMode = cellMode, cellSplit = cellSplit, label = label)

    private suspend fun HttpResponse.code(): String? = body<ErrorResponse>().code

    // ─── Shape and read ─────────────────────────────────────────────────

    @Test
    fun `an empty rig reads as no rows and is stored as such`() = testApplication {
        seedRig()
        mountTestApp(state)
        val client = jsonClient()
        assertEquals(BuskRigDto(), client.readRig(), "the show-all fallback is the client's, not the server's")
        assertEquals(BuskRigDto(), client.writeRig(BuskRigRequest()))
    }

    @Test
    fun `the write answers the document with ids minted and every summary embedded`() = testApplication {
        seedRig()
        mountTestApp(state)
        val client = jsonClient()
        val bar = patchId("bar-1")
        val written = client.writeRig(
            BuskRigRequest(
                listOf(
                    row("Wash", group(groupId("front-wash"), label = "Front"), fixture(patchId("hex-2"), cellMode = "WHOLE")),
                    row("Bars", fixture(bar, cellMode = "HALVES", cellSplit = 5), fixture(bar, cellMode = "PER_CELL"), fixture(bar, elementKey = "bar-1.pixel-3", label = "Pixel 4")),
                ),
            ),
        )
        assertEquals(listOf("Wash", "Bars"), written.rows.map { it.name })
        val wash = written.rows[0]
        assertEquals(listOf("GROUP", "FIXTURE"), wash.tiles.map { it.kind })
        val groupTile = wash.tiles[0]
        assertEquals("front-wash", groupTile.group?.name)
        assertEquals(2, groupTile.group?.memberCount, "the live group's summary is embedded")
        assertEquals("Front", groupTile.label)
        assertNull(groupTile.patch)
        assertEquals("WHOLE", wash.tiles[1].cellMode)
        assertEquals("hex-2", wash.tiles[1].patch?.key)
        assertEquals(emptyList(), wash.tiles[1].patch?.elements, "a single-head fixture has no cells")

        val bars = written.rows[1]
        assertEquals(listOf("HALVES", "PER_CELL", "PIPS"), bars.tiles.map { it.cellMode })
        assertEquals(5, bars.tiles[0].cellSplit)
        assertNull(bars.tiles[1].cellSplit)
        assertEquals(12, bars.tiles[0].patch?.elements?.size, "the patch's cells are embedded, keys and names")
        assertEquals("bar-1.pixel-0", bars.tiles[0].patch?.elements?.first()?.key)
        assertEquals("bar-1.pixel-3", bars.tiles[2].elementKey)
        assertEquals("Pixel 4", bars.tiles[2].label)
        assertTrue(written.rows.flatMap { it.tiles }.all { it.id > 0 && it.uuid.isNotBlank() })

        assertEquals(written, client.readRig(), "the write answers exactly what a read answers")
    }

    /**
     * The row's layout (2026-09-21): a flow and a width share, the bank's two facts, written, read
     * back, and **stated explicitly on the REST frame at their defaults too** — the REST converter
     * encodes defaults, unlike the socket's and sync's — so a client that also reads an *absent*
     * flow as `SCROLL` and an absent width as 12 (for a desk that predates the fields) agrees with
     * this desk either way.
     */
    @Test
    fun `a row keeps its flow and width, and states the defaults on the frame`() = testApplication {
        seedRig()
        mountTestApp(state)
        val client = jsonClient()
        val hex = patchId("hex-1")
        val written = client.writeRig(
            BuskRigRequest(
                listOf(
                    row("Left", fixture(hex), flow = "WRAP", width = 6),
                    row("Right", fixture(hex), flow = "COLUMN", width = 6),
                    row("Whole", fixture(hex)),
                ),
            ),
        )
        assertEquals(listOf("WRAP", "COLUMN", "SCROLL"), written.rows.map { it.flow })
        assertEquals(listOf(6, 6, 12), written.rows.map { it.width })
        assertEquals(written, client.readRig())
        val text = client.get(rig()).bodyAsText().replace(" ", "")
        assertTrue(text.contains("\"flow\":\"WRAP\"") && text.contains("\"width\":6"), text)
        assertTrue(text.contains("\"flow\":\"SCROLL\"") && text.contains("\"width\":12"), "the defaults are stated, not omitted: $text")
    }

    @Test
    fun `a write fires busk rigChanged`() = testApplication {
        seedRig()
        mountTestApp(state)
        val ws = createWsClient()
        val client = jsonClient()
        ws.webSocket("/api") {
            awaitOfType<ChannelMappingStateOutMessage>()
            client.writeRig(BuskRigRequest(listOf(row("Wash", group(groupId("front-wash"))))))
            awaitOfType<BuskRigChangedOutMessage>()
        }
    }

    // ─── Renumbering and identity ───────────────────────────────────────

    @Test
    fun `a second write moves by id, renumbers densely and deletes what is absent`() = testApplication {
        seedRig()
        mountTestApp(state)
        val client = jsonClient()
        val hex1 = patchId("hex-1")
        val hex2 = patchId("hex-2")
        val first = client.writeRig(
            BuskRigRequest(listOf(row("A", fixture(hex1), fixture(hex2)), row("B", group(groupId("front-wash"))))),
        )
        val rowA = first.rows[0]
        val rowB = first.rows[1]
        val tileHex1 = rowA.tiles[0]
        val tileHex2 = rowA.tiles[1]

        // Row B first, hex-2 moved onto it, hex-1 kept, the group tile dropped.
        val second = client.writeRig(
            BuskRigRequest(
                listOf(
                    row("B renamed", fixture(hex2, tileId = tileHex2.id), rowId = rowB.id),
                    row("A", fixture(hex1, tileId = tileHex1.id), rowId = rowA.id),
                ),
            ),
        )
        assertEquals(listOf(rowB.id, rowA.id), second.rows.map { it.id }, "ids survive a move")
        assertEquals(listOf("B renamed", "A"), second.rows.map { it.name })
        assertEquals(tileHex2.id, second.rows[0].tiles.single().id)
        assertEquals(tileHex1.id, second.rows[1].tiles.single().id)
        transaction(state.database) {
            assertEquals(listOf(0, 1), DaoBuskRigRow.all().sortedBy { it.sortOrder }.map { it.sortOrder })
            assertEquals(2, DaoBuskRigTile.all().count(), "the absent tile is gone")
            assertTrue(DaoBuskRigTile.all().all { it.sortOrder == 0 })
        }
    }

    @Test
    fun `an id not on this rig or named twice is refused by identity`() = testApplication {
        seedRig()
        mountTestApp(state)
        val client = jsonClient()
        val written = client.writeRig(BuskRigRequest(listOf(row("A", fixture(patchId("hex-1"))))))
        val tile = written.rows[0].tiles[0]

        val foreign = client.putRig(BuskRigRequest(listOf(row("A", fixture(patchId("hex-1")), rowId = 9999))))
        assertEquals(HttpStatusCode.BadRequest, foreign.status)
        assertEquals(CODE_BUSK_RIG_IDENTITY, foreign.code())

        val twice = client.putRig(
            BuskRigRequest(listOf(row("A", fixture(patchId("hex-1"), tileId = tile.id), fixture(patchId("hex-2"), tileId = tile.id), rowId = written.rows[0].id))),
        )
        assertEquals(HttpStatusCode.BadRequest, twice.status)
        assertEquals(CODE_BUSK_RIG_IDENTITY, twice.code())
        assertEquals(written, client.readRig(), "a refused document touches no row")
    }

    // ─── Invalid shapes ─────────────────────────────────────────────────

    @Test
    fun `malformed documents are refused as invalid before any row is touched`() = testApplication {
        seedRig()
        mountTestApp(state)
        val client = jsonClient()
        val bar = patchId("bar-1")
        val hex = patchId("hex-1")
        val before = client.writeRig(BuskRigRequest(listOf(row("Keep", fixture(hex)))))

        val cases = listOf(
            "empty row" to row("Empty"),
            "blank name" to row("   ", fixture(hex)),
            "bad flow" to row("A", fixture(hex), flow = "GRID"),
            "bad width" to row("A", fixture(hex), width = 5),
            "bad cell mode" to row("A", fixture(hex, cellMode = "SPLIT")),
            "both arms" to row("A", BuskRigTileInput(groupId = groupId("front-wash"), patchId = hex)),
            "no arm" to row("A", BuskRigTileInput()),
            "group with element" to row("A", BuskRigTileInput(groupId = groupId("front-wash"), elementKey = "x")),
            "halves without split" to row("A", fixture(bar, cellMode = "HALVES")),
            "halves below 2" to row("A", fixture(bar, cellMode = "HALVES", cellSplit = 1)),
            "halves above the cell count" to row("A", fixture(bar, cellMode = "HALVES", cellSplit = 13)),
            "halves on a single head" to row("A", fixture(hex, cellMode = "HALVES", cellSplit = 2)),
            "per cell on a single head" to row("A", fixture(hex, cellMode = "PER_CELL")),
            "a cell tile split" to row("A", fixture(bar, cellMode = "PER_CELL", elementKey = "bar-1.pixel-1")),
        )
        for ((what, bad) in cases) {
            val resp = client.putRig(BuskRigRequest(listOf(bad)))
            assertEquals(HttpStatusCode.BadRequest, resp.status, "$what: ${resp.bodyAsText()}")
            assertEquals(CODE_BUSK_RIG_INVALID, resp.code(), what)
        }
        assertEquals(before, client.readRig(), "nothing moved under twelve refusals")
    }

    @Test
    fun `a dangling group or patch, or a cell the patch lacks, is refused by reference`() = testApplication {
        seedRig()
        mountTestApp(state)
        val client = jsonClient()
        val cases = listOf(
            "group" to row("A", group(9999)),
            "patch" to row("A", fixture(9999)),
            "cell of a bar" to row("A", fixture(patchId("bar-1"), elementKey = "bar-1.pixel-99")),
            "cell of a hex" to row("A", fixture(patchId("hex-1"), elementKey = "hex-1.pixel-0")),
        )
        for ((what, bad) in cases) {
            val resp = client.putRig(BuskRigRequest(listOf(bad)))
            assertEquals(HttpStatusCode.BadRequest, resp.status, "$what: ${resp.bodyAsText()}")
            assertEquals(CODE_BUSK_RIG_REF, resp.code(), what)
        }
        assertEquals(BuskRigDto(), client.readRig())
    }

    // ─── Sweeps ─────────────────────────────────────────────────────────

    @Test
    fun `deleting a group takes its tiles off the rig and says so`() = testApplication {
        seedRig()
        mountTestApp(state)
        val ws = createWsClient()
        val client = jsonClient()
        val wash = groupId("front-wash")
        client.writeRig(BuskRigRequest(listOf(row("Groups", group(wash)), row("Mixed", group(wash), fixture(patchId("hex-1"))))))

        ws.webSocket("/api") {
            awaitOfType<ChannelMappingStateOutMessage>()
            val resp = client.delete("/api/rest/projects/$projectId/patch-groups/$wash")
            assertEquals(HttpStatusCode.NoContent, resp.status, resp.bodyAsText())
            awaitOfType<BuskRigChangedOutMessage>()
        }
        val after = client.readRig()
        assertEquals(listOf("Mixed"), after.rows.map { it.name }, "the row left with no tiles went with them")
        assertEquals(listOf("hex-1"), after.rows.single().tiles.map { it.patch?.key })
    }

    @Test
    fun `deleting a patch takes its tiles off the rig, cell tiles included`() = testApplication {
        seedRig()
        mountTestApp(state)
        val client = jsonClient()
        val bar = patchId("bar-1")
        client.writeRig(
            BuskRigRequest(listOf(row("Bars", fixture(bar, cellMode = "HALVES", cellSplit = 3), fixture(bar, elementKey = "bar-1.pixel-2"), group(groupId("front-wash"))))),
        )
        val resp = client.delete("/api/rest/projects/$projectId/patches/$bar")
        assertEquals(HttpStatusCode.NoContent, resp.status, resp.bodyAsText())
        val after = client.readRig()
        assertEquals(listOf("GROUP"), after.rows.single().tiles.map { it.kind })
        assertNotNull(after.rows.single().tiles.single().group)
    }

    @Test
    fun `deleting a universe config sweeps the tiles of every patch it cascades`() = testApplication {
        seedRig()
        mountTestApp(state)
        val ws = createWsClient()
        val client = jsonClient()
        client.writeRig(BuskRigRequest(listOf(row("Bars", fixture(patchId("bar-1"), cellMode = "PER_CELL"), fixture(patchId("hex-1"))), row("Groups", group(groupId("front-wash"))))))
        val configId = transaction(state.database) { uk.me.cormack.lighting7.models.DaoUniverseConfig.all().first().id.value }

        ws.webSocket("/api") {
            awaitOfType<ChannelMappingStateOutMessage>()
            val resp = client.delete("/api/rest/projects/$projectId/universe-configs/$configId")
            assertEquals(HttpStatusCode.NoContent, resp.status, resp.bodyAsText())
            awaitOfType<BuskRigChangedOutMessage>()
        }
        // Every patch went with the universe, so every fixture tile went too and the read still answers.
        val after = client.readRig()
        assertEquals(listOf("Groups"), after.rows.map { it.name })
        assertEquals(listOf("GROUP"), after.rows.single().tiles.map { it.kind })
    }

    @Test
    fun `renaming a patch sweeps its cell tiles and keeps its fixture tiles`() = testApplication {
        seedRig()
        mountTestApp(state)
        val ws = createWsClient()
        val client = jsonClient()
        val bar = patchId("bar-1")
        client.writeRig(
            BuskRigRequest(listOf(row("Bars", fixture(bar, cellMode = "HALVES", cellSplit = 3), fixture(bar, elementKey = "bar-1.pixel-2")), row("Cells", fixture(bar, elementKey = "bar-1.pixel-7")))),
        )
        ws.webSocket("/api") {
            awaitOfType<ChannelMappingStateOutMessage>()
            val resp = client.put("/api/rest/projects/$projectId/patches/$bar") {
                contentType(ContentType.Application.Json)
                setBody("""{"key":"bar-renamed"}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
            awaitOfType<BuskRigChangedOutMessage>()
        }
        val after = client.readRig()
        assertEquals(listOf("Bars"), after.rows.map { it.name }, "the row that held only a cell tile went with it")
        val tile = after.rows.single().tiles.single()
        assertEquals("HALVES", tile.cellMode)
        assertEquals("bar-renamed", tile.patch?.key, "a fixture tile names the patch by id and reads its new key")
        assertEquals("bar-renamed.pixel-0", tile.patch?.elements?.first()?.key)
        // The document reads back and writes back: no stale cell key is left to refuse the next save.
        val resaved = client.writeRig(
            BuskRigRequest(listOf(row("Bars", fixture(bar, cellMode = "HALVES", cellSplit = 3, tileId = tile.id), rowId = after.rows.single().id))),
        )
        assertEquals(1, resaved.rows.single().tiles.size)
    }

    @Test
    fun `a delete that touched no tile fires no rigChanged`() = testApplication {
        seedRig()
        mountTestApp(state)
        val client = jsonClient()
        client.writeRig(BuskRigRequest(listOf(row("Wash", group(groupId("front-wash"))))))
        val resp = client.delete("/api/rest/projects/$projectId/patches/${patchId("hex-1")}")
        assertEquals(HttpStatusCode.NoContent, resp.status)
        assertEquals(1, client.readRig().rows.single().tiles.size)
    }
}
