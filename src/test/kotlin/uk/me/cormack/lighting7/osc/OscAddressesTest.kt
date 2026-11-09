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
import uk.me.cormack.lighting7.history.HistorySource
import uk.me.cormack.lighting7.midi.ActionOutcome
import uk.me.cormack.lighting7.midi.ActiveBankState
import uk.me.cormack.lighting7.midi.Binding
import uk.me.cormack.lighting7.midi.Does
import uk.me.cormack.lighting7.midi.EncoderBankState
import uk.me.cormack.lighting7.midi.RecordKind
import uk.me.cormack.lighting7.midi.Subject
import uk.me.cormack.lighting7.midi.SurfaceDispatch
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
import uk.me.cormack.lighting7.testsupport.RecordedCall
import uk.me.cormack.lighting7.testsupport.RecordingSurfaceActions
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [OscAddresses] over a real project (OSC plan D3, P2): every address, resolved and dispatched as
 * the handler does, makes the call it names; names are slugs matched case-insensitively, a slug two
 * records share is ambiguous, a separator is never a stack, a cue number matches by value, and a
 * deleted record is simply not found.
 */
class OscAddressesTest : RouteIntegrationTest() {

    private val actions = RecordingSurfaceActions()
    private val dispatch = SurfaceDispatch(actions, ActiveBankState(), EncoderBankState())
    private val origin = SurfaceDispatch.Origin(OscHandler.DEVICE_TYPE, HistorySource.osc(1, "QLab"))

    private fun resolve(address: String, vararg args: OscArg): OscResolution =
        transaction(state.database) { OscAddresses.resolve(address, args.toList(), DbOscLookup(projectId)) }

    private fun press(address: String): OscResolution.Press = assertIs<OscResolution.Press>(resolve(address), address)

    /** [address] sent with [args], resolved and dispatched as [OscHandler] does: every call it made. */
    private fun send(address: String, args: List<OscArg>): List<RecordedCall> {
        actions.calls.clear()
        val outcome = when (val resolution = resolve(address, *args.toTypedArray())) {
            is OscResolution.Press -> dispatch.dispatch(resolution.binding, SurfaceDispatch.Input.Press, origin)
            is OscResolution.Level -> dispatch.dispatch(resolution.binding, SurfaceDispatch.Input.Set(resolution.value), origin)
            else -> fail("$address $args reached nothing: $resolution")
        }
        assertIs<ActionOutcome.Done>(outcome, "$address $args")
        return actions.calls.toList()
    }

    /** One address with the arguments QLab sends, and the one call it makes. */
    private class Row(val address: String, val call: RecordedCall, vararg sent: OscArg) {
        val args = sent.toList()
    }

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

    private fun stackUuid(id: Int) = transaction(state.database) { DaoCueStack[id].uuid }
    private fun cueUuid(id: Int) = transaction(state.database) { DaoCue[id].uuid }
    private fun lookUuid(id: Int) = transaction(state.database) { DaoLook[id].uuid }
    private fun entryUuid(cueId: Int) = transaction(state.database) {
        DaoCueStackEntry.find { DaoCueStackEntries.cue eq cueId }.single().uuid
    }
    private fun masterUuid(index: Int) = transaction(state.database) {
        DbOscLookup(projectId).masters().single { it.index == index }.uuid
    }

    @Test
    fun `every address makes the call it names, with the arguments sent`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        val act2 = http.stack("Act 2")
        val storm = http.cue("Storm builds", act2, "31")
        val haze = http.look("Haze fill")
        val other = http.look("Rain")
        val loose = http.cue("Thunder")
        val page = http.page("Storm", haze, other, loose)
        val s = stackUuid(act2)
        val entry = entryUuid(storm)
        val thunder = cueUuid(loose)
        val h = lookUuid(haze)
        val pads = page.rows.flatMap { it.columns }.flatMap { it.banks }.flatMap { it.pads }
        val padUuids = transaction(state.database) { pads.map { DaoBuskPad[it.id].uuid } }
        val pageUuid = transaction(state.database) { DaoBuskPage[page.id].uuid }
        val m1 = masterUuid(1)
        val m2 = masterUuid(2)

        val table = listOf(
            Row("/lighting7/stack/act-2/cue/31/go", RecordedCall.CueStackGoTo(entry)),
            Row("/lighting7/stack/act-2/cue/31/standby", RecordedCall.CueStackStandby(entry)),
            Row("/lighting7/stack/act-2/go", RecordedCall.CueStackGo(s)),
            Row("/lighting7/stack/act-2/back", RecordedCall.CueStackBack(s)),
            Row("/lighting7/stack/act-2/pause", RecordedCall.CueStackPause(s)),
            Row("/lighting7/stack/act-2/stop", RecordedCall.CueStackStop(s)),
            Row("/lighting7/stack/act-2/fx-amount", RecordedCall.SetStackFxAmount(s, 0.25), OscArg.Float32(0.25f)),
            // QLab sends a whole number as `i` (D9).
            Row("/lighting7/stack/act-2/fx-amount", RecordedCall.SetStackFxAmount(s, 1.0), OscArg.Int32(1)),
            Row("/lighting7/cue/thunder/on", RecordedCall.LooseCueSet(thunder, true)),
            Row("/lighting7/cue/thunder/off", RecordedCall.LooseCueSet(thunder, false)),
            Row("/lighting7/cue/thunder/press", RecordedCall.FireCue(thunder)),
            Row("/lighting7/look/haze-fill/on", RecordedCall.LookSet(h, true)),
            Row("/lighting7/look/haze-fill/off", RecordedCall.LookSet(h, false)),
            Row("/lighting7/look/haze-fill/press", RecordedCall.ApplyLook(h)),
            Row("/lighting7/busk/storm/1/2/press", RecordedCall.PressPad(padUuids[1])),
            // Banks count down a column, then across: C is the third.
            Row("/lighting7/busk/storm/3/1/press", RecordedCall.PressPad(padUuids[2])),
            Row("/lighting7/busk/storm/show", RecordedCall.BuskPageSet(pageUuid)),
            Row("/lighting7/blackout", RecordedCall.ToggleBlackout),
            Row("/lighting7/blackout", RecordedCall.SetBlackout(true), OscArg.Int32(1)),
            Row("/lighting7/blackout", RecordedCall.SetBlackout(true), OscArg.Bool(true)),
            Row("/lighting7/blackout", RecordedCall.SetBlackout(false), OscArg.Float32(0f)),
            // A level taken whole as a percentage, clamped to 0–1 (D10).
            Row("/lighting7/grandmaster", RecordedCall.SetGrandMasterLevel(50.0), OscArg.Float32(0.5f)),
            Row("/lighting7/grandmaster", RecordedCall.SetGrandMasterLevel(100.0), OscArg.Float32(2f)),
            Row("/lighting7/tempo/2", RecordedCall.SetSpeedMasterBpm(m2, 128.0), OscArg.Int32(128)),
            Row("/lighting7/tempo/1", RecordedCall.SetSpeedMasterBpm(m1, 96.5), OscArg.Float32(96.5f)),
            Row("/lighting7/tempo/2/tap", RecordedCall.TapSpeedMaster(m2)),
        )
        for (row in table) {
            assertEquals(listOf(row.call), send(row.address, row.args), "${row.address} ${row.args}")
        }
        assertEquals(
            OscAddresses.entries.map { OscAddresses.PREFIX + it.pattern }.toSet(),
            table.mapNotNull { OscAddresses.patternOf(it.address) }.toSet(),
            "every row of the address table is in the behaviour table",
        )

        assertEquals("GO TO 31 Storm builds in Act 2", press("/lighting7/stack/act-2/cue/31/go").reached)
        assertEquals("Press Rain (pad 2 of A on Storm)", press("/lighting7/busk/storm/1/2/press").reached)
        assertIs<OscResolution.Bad>(resolve("/lighting7/stack/act-2/fx-amount"))
        assertIs<OscResolution.Bad>(resolve("/lighting7/stack/act-2/fx-amount", OscArg.Str("half")))
        assertTrue("no pad 1" in assertIs<OscResolution.NotFound>(resolve("/lighting7/busk/storm/2/1/press")).message)
        assertTrue("no bank 4" in assertIs<OscResolution.NotFound>(resolve("/lighting7/busk/storm/4/1/press")).message)
        assertIs<OscResolution.Bad>(resolve("/lighting7/busk/storm/one/1/press"))
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
        assertEquals(Binding(Does.Go, Subject.Record(RecordKind.STACK, stackUuid(act))), press("/lighting7/stack/act-1/go").binding)
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
        val expected = Binding(Does.GoTo, Subject.Record(RecordKind.ENTRY, entryUuid(q)))
        assertEquals(expected, press("/lighting7/stack/act-1/cue/12.5/go").binding)
        assertEquals(expected, press("/lighting7/stack/act-1/cue/12.50/go").binding)
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
