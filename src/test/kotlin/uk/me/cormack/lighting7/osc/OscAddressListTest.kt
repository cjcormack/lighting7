package uk.me.cormack.lighting7.osc

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.midi.Does
import uk.me.cormack.lighting7.routes.BuskLayoutBank
import uk.me.cormack.lighting7.routes.BuskLayoutColumn
import uk.me.cormack.lighting7.routes.BuskLayoutPad
import uk.me.cormack.lighting7.routes.BuskLayoutRequest
import uk.me.cormack.lighting7.routes.BuskLayoutRow
import uk.me.cormack.lighting7.routes.BuskPageDto
import uk.me.cormack.lighting7.routes.CreateBuskPageRequest
import uk.me.cormack.lighting7.routes.CreateLookRequest
import uk.me.cormack.lighting7.routes.CueDetails
import uk.me.cormack.lighting7.routes.CueStackDetails
import uk.me.cormack.lighting7.routes.LookDetails
import uk.me.cormack.lighting7.routes.NewCue
import uk.me.cormack.lighting7.routes.NewCueStack
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `GET /projects/{id}/osc/addresses` (OSC plan session 2, P2): made from the table and answered by
 * its resolver, so every address listed resolves as listed, every row of the table appears, and what
 * no address can name is said.
 */
class OscAddressListTest : RouteIntegrationTest() {

    private suspend fun HttpClient.stack(name: String): Int =
        post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    private suspend fun HttpClient.cue(name: String, stackId: Int? = null, number: String? = null): Int {
        val resp = post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = name, cueStackId = stackId, cueNumber = number))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<CueDetails>().id
    }

    private suspend fun HttpClient.look(name: String): Int =
        post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = name))
        }.body<LookDetails>().id

    private suspend fun HttpClient.page(name: String, lookId: Int): BuskPageDto {
        val page = post("/api/rest/projects/$projectId/busk/pages") {
            contentType(ContentType.Application.Json)
            setBody(CreateBuskPageRequest(name))
        }.body<BuskPageDto>()
        return put("/api/rest/projects/$projectId/busk/pages/${page.id}/layout") {
            contentType(ContentType.Application.Json)
            setBody(
                BuskLayoutRequest(
                    listOf(BuskLayoutRow(listOf(BuskLayoutColumn(width = 12, banks = listOf(
                        BuskLayoutBank(name = "A", solo = false, pads = listOf(BuskLayoutPad(lookId = lookId))),
                    ))))),
                ),
            )
        }.body()
    }

    @Test
    fun `every address listed resolves as listed, and every row of the table is reached`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        val act = http.stack("Act 1")
        http.cue("Opening", act, "1")
        http.cue("Storm", act, "12.5")
        http.cue("Thunder")
        val haze = http.look("Haze fill")
        http.page("Storm", haze)

        val resp = http.get("/api/rest/projects/$projectId/osc/addresses")
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val list = resp.body<OscAddressListDto>()
        assertEquals("/lighting7", list.prefix)
        assertEquals(OscAddresses.entries.map { "/lighting7" + it.pattern }, list.patterns.map { it.pattern })

        val byAddress = list.addresses.associateBy { it.address }
        assertTrue("/lighting7/stack/act-1/cue/12.5/go" in byAddress, byAddress.keys.toString())
        assertEquals("GO TO 12.5 Storm in Act 1", byAddress.getValue("/lighting7/stack/act-1/cue/12.5/go").reaches)
        assertTrue("/lighting7/cue/thunder/on" in byAddress)
        assertTrue("/lighting7/look/haze-fill/on" in byAddress)
        assertEquals(OscRecordKind.PAD, byAddress.getValue("/lighting7/busk/storm/1/1/press").record?.kind)
        assertTrue("/lighting7/busk/storm/show" in byAddress)
        assertTrue("/lighting7/blackout" in byAddress)
        assertTrue("/lighting7/grandmaster" in byAddress)
        assertTrue("/lighting7/tempo/1" in byAddress && "/lighting7/tempo/4/tap" in byAddress)

        // P2: the resolver is the listing's only answer, so each row says what a message would do.
        val lookup = DbOscLookup(projectId)
        for (row in list.addresses) {
            val args = when (row.args) {
                OscAddresses.Args.LEVEL.label -> listOf(OscArg.Float32(1f))
                OscAddresses.Args.BPM.label -> listOf(OscArg.Float32(120f))
                else -> emptyList()
            }
            val resolved = transaction(state.database) { OscAddresses.resolve(row.address, args, lookup) }
            val binding = when (resolved) {
                is OscResolution.Press -> resolved.binding
                is OscResolution.Level -> resolved.binding
                else -> null
            }
            assertEquals(row.binding, binding, row.address)
        }
        // Every pattern of the table has at least one address in a show holding one of each record.
        val reached = list.addresses.map { it.pattern }.toSet()
        assertEquals(list.patterns.map { it.pattern }.toSet(), reached)
    }

    @Test
    fun `a shared slug is listed as ambiguous, and a name with no letter or digit or an entry with no number has no address`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        http.cue("Storm!")
        http.cue("storm")
        http.stack("???")
        val act = http.stack("Act 1")
        val unnumbered = http.cue("Interval", act)
        // A cue added to a stack with no number is auto-numbered; clear it to leave the entry unnumbered.
        val entryId = transaction(state.database) {
            uk.me.cormack.lighting7.models.DaoCueStackEntry.find {
                uk.me.cormack.lighting7.models.DaoCueStackEntries.cue eq unnumbered
            }.single().also { it.cueNumber = null; it.cueNumberAuto = false }.id.value
        }

        val list = http.get("/api/rest/projects/$projectId/osc/addresses").body<OscAddressListDto>()
        val storm = list.ambiguous.single { "storm" in it.message }
        assertEquals(2, storm.records.size)
        assertEquals(listOf("/lighting7/cue/storm/on", "/lighting7/cue/storm/off", "/lighting7/cue/storm/press"), storm.addresses)
        assertTrue(list.addresses.none { it.address.startsWith("/lighting7/cue/storm/") })

        val noSlug = list.unaddressed.single { it.kind == OscRecordKind.STACK }
        assertEquals("???", noSlug.name)
        val noNumber = assertNotNull(list.unaddressed.singleOrNull { it.kind == OscRecordKind.ENTRY && it.id == entryId }, list.unaddressed.toString())
        assertEquals("It has no cue number", noNumber.reason)
        // The listing carries each address's binding, which the client matches on.
        val goTo = list.addresses.first { it.binding.does == Does.Go }
        assertEquals(OscRecordKind.STACK, goTo.record?.kind)
    }
}
