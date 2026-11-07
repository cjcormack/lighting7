package uk.me.cormack.lighting7.osc

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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.midi.BindingTarget
import uk.me.cormack.lighting7.models.DaoBuskPad
import uk.me.cormack.lighting7.models.DaoBuskPage
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntries
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoLook
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [OscAddresses] over a real project (OSC plan D3, P2): every session-1 row reaches its target,
 * names are slugs matched case-insensitively, a slug two records share is ambiguous, a separator is
 * never a stack, a cue number matches by value, and a deleted record is simply not found.
 */
class OscAddressesTest : RouteIntegrationTest() {

    private fun resolve(address: String, vararg args: OscArg): OscResolution =
        transaction(state.database) { OscAddresses.resolve(address, args.toList(), DbOscLookup(projectId)) }

    private fun press(address: String): OscResolution.Press = assertIs<OscResolution.Press>(resolve(address), address)

    private suspend fun HttpClient.stack(name: String, type: String? = null): Int {
        val resp = post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name, type = type, label = if (type != null) name else null))
        }
        assertTrue(resp.status.value in 200..299, resp.bodyAsText())
        return resp.body<CueStackDetails>().id
    }

    private suspend fun HttpClient.cue(name: String, stackId: Int? = null, number: String? = null): Int {
        val resp = post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = name, cueStackId = stackId, cueNumber = number))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<CueDetails>().id
    }

    private suspend fun HttpClient.look(name: String): Int {
        val resp = post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = name))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<LookDetails>().id
    }

    /** A page of two columns: the first with banks `A` (two Look pads) and `B`, the second with `C` (one cue pad). */
    private suspend fun HttpClient.page(name: String, lookA: Int, lookB: Int, cue: Int): BuskPageDto {
        val page = post("/api/rest/projects/$projectId/busk/pages") {
            contentType(ContentType.Application.Json)
            setBody(CreateBuskPageRequest(name))
        }.body<BuskPageDto>()
        val resp = put("/api/rest/projects/$projectId/busk/pages/${page.id}/layout") {
            contentType(ContentType.Application.Json)
            setBody(
                BuskLayoutRequest(
                    listOf(
                        BuskLayoutRow(
                            listOf(
                                BuskLayoutColumn(
                                    width = 6,
                                    banks = listOf(
                                        BuskLayoutBank(name = "A", solo = false, pads = listOf(BuskLayoutPad(lookId = lookA), BuskLayoutPad(lookId = lookB))),
                                        BuskLayoutBank(name = "B", solo = false, pads = emptyList()),
                                    ),
                                ),
                                BuskLayoutColumn(width = 6, banks = listOf(BuskLayoutBank(name = "C", solo = false, pads = listOf(BuskLayoutPad(cueId = cue))))),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private fun stackUuid(id: Int) = transaction(state.database) { DaoCueStack[id].uuid.toString() }
    private fun cueUuid(id: Int) = transaction(state.database) { DaoCue[id].uuid.toString() }
    private fun lookUuid(id: Int) = transaction(state.database) { DaoLook[id].uuid.toString() }
    private fun entryUuid(cueId: Int) = transaction(state.database) {
        DaoCueStackEntry.find { DaoCueStackEntries.cue eq cueId }.single().uuid.toString()
    }

    @Test
    fun `every session-1 row reaches its target, naming the record`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        val act2 = http.stack("Act 2")
        val storm = http.cue("Storm builds", act2, "31")
        val haze = http.look("Haze fill")
        val other = http.look("Rain")
        val loose = http.cue("Thunder")
        val page = http.page("Storm", haze, other, loose)
        val s = stackUuid(act2)

        val goTo = press("/lighting7/stack/act-2/cue/31/go")
        assertEquals(BindingTarget.CueStackGoTo(s, entryUuid(storm)), goTo.target)
        assertEquals("GO TO 31 Storm builds in Act 2", goTo.reached)
        assertEquals(BindingTarget.CueStackStandby(s, entryUuid(storm)), press("/lighting7/stack/act-2/cue/31/standby").target)
        assertEquals(BindingTarget.CueStackGo(act2, s), press("/lighting7/stack/act-2/go").target)
        assertEquals(BindingTarget.CueStackBack(act2, s), press("/lighting7/stack/act-2/back").target)
        assertEquals(BindingTarget.CueStackPause(act2, s), press("/lighting7/stack/act-2/pause").target)
        assertEquals(BindingTarget.CueStackStop(s), press("/lighting7/stack/act-2/stop").target)

        val level = assertIs<OscResolution.Level>(resolve("/lighting7/stack/act-2/fx-amount", OscArg.Float32(0.25f)))
        assertEquals(BindingTarget.StackFxAmount(act2, s), level.target)
        assertEquals(0.25, level.value)
        // QLab sends a whole number as `i` (D9).
        assertEquals(1.0, assertIs<OscResolution.Level>(resolve("/lighting7/stack/act-2/fx-amount", OscArg.Int32(1))).value)
        assertIs<OscResolution.Bad>(resolve("/lighting7/stack/act-2/fx-amount"))
        assertIs<OscResolution.Bad>(resolve("/lighting7/stack/act-2/fx-amount", OscArg.Str("half")))

        val thunder = cueUuid(loose)
        assertEquals(BindingTarget.LooseCueSet(thunder, on = true), press("/lighting7/cue/thunder/on").target)
        assertEquals(BindingTarget.LooseCueSet(thunder, on = false), press("/lighting7/cue/thunder/off").target)
        assertEquals(BindingTarget.FireCue(loose, thunder), press("/lighting7/cue/thunder/press").target)

        val h = lookUuid(haze)
        assertEquals(BindingTarget.LookSet(h, on = true), press("/lighting7/look/haze-fill/on").target)
        assertEquals(BindingTarget.LookSet(h, on = false), press("/lighting7/look/haze-fill/off").target)
        assertEquals(BindingTarget.ApplyLook(h), press("/lighting7/look/haze-fill/press").target)

        val pads = page.rows.flatMap { it.columns }.flatMap { it.banks }.flatMap { it.pads }
        val padUuids = transaction(state.database) { pads.map { DaoBuskPad[it.id].uuid.toString() } }
        val second = press("/lighting7/busk/storm/1/2/press")
        assertEquals(BindingTarget.PressPad(padUuids[1]), second.target)
        assertEquals("Press Rain (pad 2 of A on Storm)", second.reached)
        // Banks count down a column, then across: C is the third.
        assertEquals(BindingTarget.PressPad(padUuids[2]), press("/lighting7/busk/storm/3/1/press").target)
        assertTrue("no pad 1" in assertIs<OscResolution.NotFound>(resolve("/lighting7/busk/storm/2/1/press")).message)
        assertTrue("no bank 4" in assertIs<OscResolution.NotFound>(resolve("/lighting7/busk/storm/4/1/press")).message)
        assertIs<OscResolution.Bad>(resolve("/lighting7/busk/storm/one/1/press"))
        val pageUuid = transaction(state.database) { DaoBuskPage[page.id].uuid.toString() }
        assertEquals(BindingTarget.BuskPageSet(pageUuid), press("/lighting7/busk/storm/show").target)

        // Every session-1 row of the table was exercised above.
        assertEquals(15, OscAddresses.entries.count { it.session == 1 })
    }

    @Test
    fun `names match by slug whatever their case, and an address two records answer to is ambiguous`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        http.stack("Act 1")
        http.cue("Storm!")
        http.cue("storm")
        http.look("Big Wash")

        assertEquals("GO Act 1", press("/lighting7/STACK/Act-1/GO").reached)
        assertEquals("Look Big Wash on", press("/lighting7/look/BIG-WASH/on").reached)
        val ambiguous = assertIs<OscResolution.Ambiguous>(resolve("/lighting7/cue/storm/on"))
        assertTrue("Storm!" in ambiguous.message && "storm" in ambiguous.message, ambiguous.message)
        // The literal name is no address: OSC forbids the space, and the slug is the only form (D3).
        assertIs<OscResolution.NotFound>(resolve("/lighting7/look/big wash/on"))
    }

    @Test
    fun `a separator sharing a stack's name is never matched`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        val act = http.stack("Act 1")
        http.stack("Act 1", type = "SEPARATOR")
        assertEquals(BindingTarget.CueStackGo(act, stackUuid(act)), press("/lighting7/stack/act-1/go").target)
    }

    @Test
    fun `a name that slugs to nothing has no address`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        http.stack("???")
        http.look("★")
        assertIs<OscResolution.Bad>(resolve("/lighting7/stack//go"))
        assertIs<OscResolution.NotFound>(resolve("/lighting7/look/-/on"))
    }

    @Test
    fun `a cue number matches by value, and a number not in the stack is not found`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        val act = http.stack("Act 1")
        val q = http.cue("Half", act, "12.5")
        val expected = BindingTarget.CueStackGoTo(stackUuid(act), entryUuid(q))
        assertEquals(expected, press("/lighting7/stack/act-1/cue/12.5/go").target)
        assertEquals(expected, press("/lighting7/stack/act-1/cue/12.50/go").target)
        assertEquals("No cue 13 in Act 1", assertIs<OscResolution.NotFound>(resolve("/lighting7/stack/act-1/cue/13/go")).message)
        assertIs<OscResolution.Bad>(resolve("/lighting7/stack/act-1/cue/12a/go"))
    }

    @Test
    fun `a deleted stack is not found, and an unknown address is a bad message`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        val act = http.stack("Act 3")
        press("/lighting7/stack/act-3/go")
        val gone = http.delete("/api/rest/projects/$projectId/cue-stacks/$act")
        assertTrue(gone.status.isSuccess(), gone.bodyAsText())
        assertEquals("No stack `act-3` in this show", assertIs<OscResolution.NotFound>(resolve("/lighting7/stack/act-3/go")).message)
        assertIs<OscResolution.Bad>(resolve("/lighting7/stack/act-3/jump"))
        assertIs<OscResolution.Bad>(resolve("/eos/key/go_0"))
        assertIs<OscResolution.Bad>(resolve("/lighting7"))
    }

    private fun HttpStatusCode.isSuccess() = value in 200..299
}
