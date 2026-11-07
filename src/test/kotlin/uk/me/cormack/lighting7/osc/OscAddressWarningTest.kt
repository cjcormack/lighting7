package uk.me.cormack.lighting7.osc

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import uk.me.cormack.lighting7.midi.DefaultSurfaceActions
import uk.me.cormack.lighting7.midi.SurfaceDispatch
import uk.me.cormack.lighting7.perf.DeskMetrics
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
import uk.me.cormack.lighting7.routes.SpeedMasterDto
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.entryIdOf
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The moved-address warning (OSC plan §4): each of the eight changes that can move an address — a
 * stack, loose cue, Look or busk page renamed, a cue renumbered, an insert or delete relabelling auto
 * numbers, a pad moved, a speed master deleted — warns about an address received since the desk
 * started, saying what it reached and what it reaches now, through the show's own change hooks.
 */
class OscAddressWarningTest : RouteIntegrationTest() {

    private val qlab = OscSender(1, "QLab", "127.0.0.1")
    private var service: OscService? = null

    @After
    fun closeService() {
        service?.close()
    }

    private fun service(): OscService {
        val handler = OscHandler(
            database = state.database,
            currentProjectId = { projectId },
            dispatch = SurfaceDispatch(DefaultSurfaceActions(state), state.activeBankState, state.encoderBankState, state.flashStateTracker),
        )
        return OscService(state.database, handler, DeskMetrics()).also {
            it.start()
            it.attach(state.show.fixtures)
            service = it
        }
    }

    private fun <T> waitFor(what: String, read: () -> T?): T = runBlocking {
        try {
            withTimeout(5_000) {
                var value = read()
                while (value == null) {
                    delay(20)
                    value = read()
                }
                value
            }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; state is ${service?.state?.value}", e)
        }
    }

    /** [address] received and resolved, as QLab sending it would leave it. */
    private fun OscService.receive(address: String, vararg args: OscArg) {
        val before = state.value.recent.size
        offer(OscInbound(Instant.now(), qlab, OscPacket.Item.Message(OscMessage(address, args.toList()))))
        val row = waitFor("$address in the log") { state.value.recent.takeIf { it.size > before }?.first() }
        assertEquals(OscOutcome.DONE, row.outcome, "$address: ${row.detail}")
    }

    /** The change [change] made, warned for [address]. */
    private fun OscService.warned(address: String, change: () -> Unit): OscAddressChangeDto {
        val seen = state.value.warnings.firstOrNull()?.id ?: 0
        change()
        return waitFor("a warning for $address") {
            state.value.warnings.filter { it.id > seen }.flatMap { it.changes }.firstOrNull { it.address == address }
        }
    }

    private suspend fun HttpResponse.ok(): HttpResponse = also { assertTrue(status.value in 200..299, "$status ${bodyAsText()}") }

    private suspend fun HttpClient.stack(name: String): Int =
        post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.ok().body<CueStackDetails>().id

    private suspend fun HttpClient.cue(name: String, stackId: Int? = null, number: String? = null): Int =
        post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = name, cueStackId = stackId, cueNumber = number))
        }.ok().body<CueDetails>().id

    private suspend fun HttpClient.look(name: String): Int =
        post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = name))
        }.ok().body<LookDetails>().id

    private fun layout(vararg lookIds: Int) = BuskLayoutRequest(
        listOf(BuskLayoutRow(listOf(BuskLayoutColumn(width = 12, banks = listOf(
            BuskLayoutBank(name = "A", solo = false, pads = lookIds.map { BuskLayoutPad(lookId = it) }),
        ))))),
    )

    private suspend fun HttpClient.page(name: String, vararg lookIds: Int): Int {
        val page = post("/api/rest/projects/$projectId/busk/pages") {
            contentType(ContentType.Application.Json)
            setBody(CreateBuskPageRequest(name))
        }.ok().body<BuskPageDto>()
        put("/api/rest/projects/$projectId/busk/pages/${page.id}/layout") {
            contentType(ContentType.Application.Json)
            setBody(layout(*lookIds))
        }.ok()
        return page.id
    }

    private fun ApplicationTestBuilder.http() = run { mountTestApp(state); jsonClient() }

    @Test
    fun `renaming a stack warns that its addresses stop working`() = testApplication {
        val http = http()
        val act = http.stack("Act 1")
        val osc = service()
        osc.receive("/lighting7/stack/act-1/pause")
        val change = osc.warned("/lighting7/stack/act-1/pause") {
            runBlocking {
                http.put("/api/rest/projects/$projectId/cue-stacks/$act") {
                    contentType(ContentType.Application.Json)
                    setBody(NewCueStack(name = "Act One"))
                }.ok()
            }
        }
        assertEquals("Pause Act 1's follow", change.was)
        assertNull(change.now)
        assertEquals("No stack `act-1` in this show", change.reason)
    }

    @Test
    fun `renaming a loose cue, a Look or a busk page warns`() = testApplication {
        val http = http()
        val thunder = http.cue("Thunder")
        val haze = http.look("Haze fill")
        val page = http.page("Storm", haze)
        val osc = service()
        osc.receive("/lighting7/cue/thunder/on")
        osc.receive("/lighting7/look/haze-fill/on")
        osc.receive("/lighting7/busk/storm/show")

        osc.warned("/lighting7/cue/thunder/on") {
            runBlocking {
                http.patch("/api/rest/projects/$projectId/cues/$thunder") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"Rumble"}""")
                }.ok()
            }
        }
        osc.warned("/lighting7/look/haze-fill/on") {
            runBlocking {
                http.put("/api/rest/projects/$projectId/looks/$haze") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"Haze"}""")
                }.ok()
            }
        }
        osc.warned("/lighting7/busk/storm/show") {
            runBlocking {
                http.put("/api/rest/projects/$projectId/busk/pages/$page") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"Calm"}""")
                }.ok()
            }
        }
    }

    @Test
    fun `renumbering a cue, or a delete that relabels auto numbers, warns that a number reaches another cue or none`() = testApplication {
        val http = http()
        val act = http.stack("Act 1")
        val first = http.cue("Opening", act)
        http.cue("Storm", act)
        val named = http.cue("Rain", act, "10")
        val osc = service()
        osc.receive("/lighting7/stack/act-1/cue/10/standby")
        osc.receive("/lighting7/stack/act-1/cue/2/standby")

        val renumbered = osc.warned("/lighting7/stack/act-1/cue/10/standby") {
            runBlocking {
                http.patch("/api/rest/projects/$projectId/cue-stacks/$act/entries/${state.entryIdOf(named)}") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"cueNumber":"11"}""")
                }.ok()
            }
        }
        assertEquals("Standby 10 Rain in Act 1", renumbered.was)
        assertNull(renumbered.now)

        val relabelled = osc.warned("/lighting7/stack/act-1/cue/2/standby") {
            runBlocking { http.delete("/api/rest/projects/$projectId/cue-stacks/$act/entries/${state.entryIdOf(first)}").ok() }
        }
        assertEquals("Standby 2 Storm in Act 1", relabelled.was)
        assertTrue(relabelled.now != "Standby 2 Storm in Act 1", relabelled.toString())
    }

    @Test
    fun `moving a pad warns that its address reaches another`() = testApplication {
        val http = http()
        val haze = http.look("Haze fill")
        val rain = http.look("Rain")
        val page = http.page("Storm", haze, rain)
        val osc = service()
        osc.receive("/lighting7/busk/storm/1/1/press")

        val change = osc.warned("/lighting7/busk/storm/1/1/press") {
            runBlocking {
                // The whole page again, the two pads swapped: the pads keep their ids, the places move.
                val current = http.get("/api/rest/projects/$projectId/busk/pages/$page").ok().body<BuskPageDto>()
                val pads = current.rows.single().columns.single().banks.single().pads
                http.put("/api/rest/projects/$projectId/busk/pages/$page/layout") {
                    contentType(ContentType.Application.Json)
                    setBody(BuskLayoutRequest(listOf(BuskLayoutRow(listOf(BuskLayoutColumn(
                        columnId = current.rows.single().columns.single().id,
                        width = 12,
                        banks = listOf(BuskLayoutBank(
                            bankId = current.rows.single().columns.single().banks.single().id,
                            name = "A", solo = false,
                            pads = listOf(BuskLayoutPad(padId = pads[1].id, lookId = rain), BuskLayoutPad(padId = pads[0].id, lookId = haze)),
                        )),
                    ))))))
                }.ok()
            }
        }
        assertEquals("Press Haze fill (pad 1 of A on Storm)", change.was)
        assertEquals("Press Rain (pad 1 of A on Storm)", change.now)
    }

    @Test
    fun `deleting a speed master warns that its index stops working`() = testApplication {
        val http = http()
        val masters = http.get("/api/rest/projects/$projectId/speed-masters").ok().body<List<SpeedMasterDto>>()
        val last = masters.maxBy { it.masterIndex }
        val osc = service()
        osc.receive("/lighting7/tempo/${last.masterIndex}", OscArg.Int32(110))

        val change = osc.warned("/lighting7/tempo/${last.masterIndex}") {
            runBlocking { http.delete("/api/rest/projects/$projectId/speed-masters/${last.id}?force=true").ok() }
        }
        assertEquals("Tempo of M${last.masterIndex} ${last.name}", change.was)
        assertEquals("No speed master M${last.masterIndex} in this show", change.reason)
    }

    @Test
    fun `a change that moves nothing a sender used warns nothing`() = testApplication {
        val http = http()
        val act = http.stack("Act 1")
        http.stack("Act 2")
        val osc = service()
        osc.receive("/lighting7/stack/act-2/pause")
        // An address naming no record cannot move, whatever value it carried.
        osc.receive("/lighting7/blackout", OscArg.Int32(1))
        osc.receive("/lighting7/blackout", OscArg.Int32(0))
        osc.receive("/lighting7/grandmaster", OscArg.Float32(1f))
        runBlocking {
            http.put("/api/rest/projects/$projectId/cue-stacks/$act") {
                contentType(ContentType.Application.Json)
                setBody(NewCueStack(name = "Prologue"))
            }.ok()
            delay(OscService.CHECK_DEBOUNCE_MS * 3)
        }
        osc.checkReceived()
        assertTrue(osc.state.value.warnings.isEmpty(), osc.state.value.warnings.toString())
    }

    @Test
    fun `a show attached with its changes already made is checked`() = testApplication {
        val http = http()
        val act = http.stack("Act 1")
        val osc = service()
        osc.receive("/lighting7/stack/act-1/pause")
        // As a sync pull's reload does: the rename lands, and only then is the show attached.
        osc.attach(uk.me.cormack.lighting7.show.Fixtures())
        osc.warned("/lighting7/stack/act-1/pause") {
            org.jetbrains.exposed.v1.jdbc.transactions.transaction(state.database) {
                uk.me.cormack.lighting7.models.DaoCueStack[act].name = "Act One"
            }
            osc.attach(state.show.fixtures)
        }
    }
}
