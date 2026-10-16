package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
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
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.TemplateRowDto
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `POST /projects/{id}/programmer/spread` (busk-further plan D9, §3.5): a group spreads in member
 * order and rig order, a head lacking the property is skipped by name, `tmpl:` endpoints, the mask
 * answering `skippedFamilies`, `over = CELLS`, and every write landing as a programmer entry.
 *
 * Since the editor-kit plan's session 3 (§3.2): `written[].value` is each head's **literal** in the
 * Look row grammar, not the intent, and `write = false` answers every head without writing one.
 */
class ProgrammerSpreadRouteTest : RouteIntegrationTest() {

    private fun spread() = "/api/rest/projects/$projectId/programmer/spread"

    private fun seed() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        LocateTestSupport.seedHex(state, projectId, "hex-3", 25)
        LocateTestSupport.seedFixture(state, projectId, "led-lightbar-12-pixel-48ch", "bar-1", 100)
        // Two movers with a colour wheel (7 slots + open, previews annotated) and annotated pan/tilt.
        LocateTestSupport.seedFixture(state, projectId, "fusion-100-spot-mkii-15ch", "spot-1", 200)
        LocateTestSupport.seedFixture(state, projectId, "fusion-100-spot-mkii-15ch", "spot-2", 220)
        // A MAC 250, whose wheel was once a property named `colour` the alias rewrote away.
        LocateTestSupport.seedFixture(state, projectId, "martin-mac-250-mode-4", "mac-1", 240)
        LocateTestSupport.seedGroup(state, projectId, "front-wash", "hex-1", "hex-2")
        state.show.fixtures.patchListChanged()
    }

    private fun fixture(key: String) = CueTargetDto("fixture", key)
    private fun group(name: String) = CueTargetDto("group", name)

    private suspend fun HttpClient.post(request: SpreadRequest): HttpResponse =
        post(spread()) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.run(request: SpreadRequest): SpreadResponse {
        val resp = post(request)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.createTemplate(name: String, hex: String): String {
        val resp = post("/api/rest/projects/$projectId/palettes") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = name, rows = listOf(TemplateRowDto(DEFERRED_TARGET_TYPE, "", "rgbColour", "$hex;policy=extract"))))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<TemplateDto>().uuid
    }

    private fun entries() = state.show.programmerStore.entries().map { it.fixtureKey to it.propertyName }.toSet()

    @Test
    fun `a group spreads in member order and every head lands as a programmer entry`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        val out = client.run(
            SpreadRequest(targets = listOf(group("front-wash"), fixture("hex-3")), property = "dimmer", from = "pct:0", to = "pct:100"),
        )
        assertEquals(listOf("hex-1", "hex-2", "hex-3"), out.written.map { it.target.key })
        // The literal each head got — a byte on a 0–255 dimmer — never the `pct:` intent.
        assertEquals(listOf("0", "128", "255"), out.written.map { it.value })
        assertEquals(listOf("dimmer", "dimmer", "dimmer"), out.written.map { it.propertyName })
        assertTrue(out.skipped.isEmpty())
        assertEquals(setOf("hex-1" to "dimmer", "hex-2" to "dimmer", "hex-3" to "dimmer"), entries())
    }

    @Test
    fun `LINEAR is rig order, so a rig that reverses the heads reverses the fan`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        val ids = transaction(state.database) {
            listOf("hex-3", "hex-2", "hex-1").map { key -> DaoFixturePatch.find { DaoFixturePatches.key eq key }.first().id.value }
        }
        val rig = client.put("/api/rest/projects/$projectId/busk/rig") {
            contentType(ContentType.Application.Json)
            setBody(BuskRigRequest(listOf(BuskRigRowInput(name = "Back to front", tiles = ids.map { BuskRigTileInput(patchId = it) }))))
        }
        assertEquals(HttpStatusCode.OK, rig.status, rig.bodyAsText())

        val out = client.run(
            SpreadRequest(targets = listOf(fixture("hex-1"), fixture("hex-2"), fixture("hex-3")), property = "dimmer", from = "pct:0", to = "pct:100"),
        )
        assertEquals(listOf("hex-3", "hex-2", "hex-1"), out.written.map { it.target.key })
        assertEquals(listOf("0", "128", "255"), out.written.map { it.value })

        val reversed = client.run(
            SpreadRequest(targets = listOf(fixture("hex-1"), fixture("hex-2"), fixture("hex-3")), property = "dimmer", from = "pct:0", to = "pct:100", order = "REVERSE"),
        )
        assertEquals(listOf("255", "128", "0"), reversed.written.map { it.value })
    }

    @Test
    fun `a spread over one group keeps the group's member order where rig order would not`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        // The rig reaches hex-2 before hex-1, so rig order would put front-wash back to front.
        val ids = transaction(state.database) {
            listOf("hex-2", "hex-1", "hex-3").map { key -> DaoFixturePatch.find { DaoFixturePatches.key eq key }.first().id.value }
        }
        val rig = client.put("/api/rest/projects/$projectId/busk/rig") {
            contentType(ContentType.Application.Json)
            setBody(BuskRigRequest(listOf(BuskRigRowInput(name = "Mixed", tiles = ids.map { BuskRigTileInput(patchId = it) }))))
        }
        assertEquals(HttpStatusCode.OK, rig.status, rig.bodyAsText())

        val one = client.run(SpreadRequest(targets = listOf(group("front-wash")), property = "dimmer", from = "pct:0", to = "pct:100"))
        assertEquals(listOf("hex-1", "hex-2"), one.written.map { it.target.key })
        assertEquals(listOf("0", "255"), one.written.map { it.value })

        // Anything more than the one group is rig order again.
        val more = client.run(
            SpreadRequest(targets = listOf(group("front-wash"), fixture("hex-3")), property = "dimmer", from = "pct:0", to = "pct:100"),
        )
        assertEquals(listOf("hex-2", "hex-1", "hex-3"), more.written.map { it.target.key })
    }

    @Test
    fun `a head lacking the property is skipped by name and the rest land`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        val out = client.run(
            SpreadRequest(targets = listOf(fixture("hex-1"), fixture("hex-2")), property = "position", from = "deg:0,0", to = "deg:90,45"),
        )
        assertTrue(out.written.isEmpty())
        assertEquals(listOf("hex-1", "hex-2"), out.skipped.map { it.target.key })
        assertTrue(out.skipped.all { it.reason.isNotBlank() })
        val gone = client.run(SpreadRequest(targets = listOf(fixture("nope")), property = "dimmer", from = "pct:0", to = "pct:100"))
        assertEquals(listOf("not patched"), gone.skipped.map { it.reason })
    }

    @Test
    fun `a skipped head consumes no position on the curve — the spread runs over the heads that can take the property`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        // Two pars among two movers: a Position spread lands *from* on the first mover and *to* on
        // the second, exactly as it would over the two movers alone. The programmer's marquee is
        // geometric and sweeps the pars up with the rest, so without this the movers sat at 1/3 and 1.
        val mixed = client.run(
            SpreadRequest(
                targets = listOf(fixture("hex-1"), fixture("spot-1"), fixture("hex-2"), fixture("spot-2")),
                property = "position", from = "pos:-128,-128", to = "pos:127,127",
            ),
        )
        val alone = client.run(
            SpreadRequest(targets = listOf(fixture("spot-1"), fixture("spot-2")), property = "position", from = "pos:-128,-128", to = "pos:127,127"),
        )
        assertEquals(listOf("spot-1", "spot-2"), mixed.written.map { it.target.key })
        assertEquals(alone.written.map { it.value }, mixed.written.map { it.value })
        assertEquals(listOf("0,0", "255,255"), mixed.written.map { it.value })
        assertEquals(listOf("hex-1", "hex-2"), mixed.skipped.map { it.target.key })
    }

    @Test
    fun `colour interpolates in Lab and tmpl endpoints resolve the template's generic colour`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        val red = client.createTemplate("red", "#ff0000")
        val blue = client.createTemplate("blue", "#0000ff")
        val out = client.run(
            SpreadRequest(targets = listOf(fixture("hex-1"), fixture("hex-2"), fixture("hex-3")), property = "rgbColour", from = "tmpl:$red", to = "tmpl:$blue"),
        )
        assertEquals(3, out.written.size)
        // Literals in the Look row grammar: pure red and pure blue have no neutral to extract, so
        // a hex head's white stays 0 and is elided.
        assertEquals("#ff0000", out.written.first().value)
        assertEquals("#0000ff", out.written.last().value)
        val middle = out.written[1].value
        assertNotEquals(out.written.first().value, middle)
        assertNotEquals("#800080", middle, "the midpoint is a Lab mix, not a channel-space average")
        assertEquals(setOf("hex-1" to "rgbColour", "hex-2" to "rgbColour", "hex-3" to "rgbColour"), entries())

        // A bare hex reads as RGB_ONLY, as every other reader reads it: under EXTRACT the 0x44
        // neutral would have moved to the white emitter and the literal read `#bb4400;w68`.
        val literal = client.run(SpreadRequest(targets = listOf(fixture("hex-1")), property = "rgbColour", from = "#ff8844", to = "#ff8844"))
        assertEquals("#ff8844", literal.written.single().value)
    }

    @Test
    fun `written values are literals the Look row grammar parses, on every shape`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        // A colour with a neutral, so the hex head's extracted white rides the literal as `;wNN`.
        val colour = client.run(
            SpreadRequest(targets = listOf(fixture("hex-1"), fixture("hex-2")), property = "rgbColour", from = "#ff8844;policy=extract", to = "#ff8844;policy=extract"),
        )
        assertEquals(listOf("#bb4400;w68", "#bb4400;w68"), colour.written.map { it.value })
        for (write in colour.written) {
            // The client's `parseProgrammerValue` grammar for a colour: `#rrggbb` then `;w` / `;a` / `;uv` tags.
            assertTrue(Regex("^#[0-9a-f]{6}(;(w|a|uv)\\d{1,3})*$").matches(write.value), write.value)
            val parsed = CueAssignmentResolver.parseAssignmentValue(PropertyCategory.COLOUR, write.propertyName, write.value)
            assertNotNull(parsed, write.value)
            assertEquals(write.value, parsed.serialize(), "round-trips through the parser Look rows use")
        }
        val level = client.run(SpreadRequest(targets = listOf(fixture("hex-1")), property = "white", from = "dmx:40", to = "dmx:40"))
        assertEquals("40", level.written.single().value)
        assertNotNull(CueAssignmentResolver.parseAssignmentValue(PropertyCategory.WHITE, "white", "40"))
        // A position is `pan,tilt`, each a byte, under the `position` name the parser keys on.
        val position = client.run(
            SpreadRequest(targets = listOf(fixture("spot-1"), fixture("spot-2")), property = "position", from = "deg:270,105", to = "deg:540,210"),
        )
        assertEquals(listOf("128,128", "255,255"), position.written.map { it.value })
        for (write in position.written) {
            val parsed = CueAssignmentResolver.parseAssignmentValue(PropertyCategory.PAN, write.propertyName, write.value)
            assertNotNull(parsed, write.value)
            assertEquals(write.value, parsed.serialize())
        }
        // Never an intent: nothing answered parses as one.
        for (value in colour.written.map { it.value } + level.written.map { it.value } + position.written.map { it.value }) {
            assertTrue(!value.startsWith("pct:") && !value.startsWith("deg:") && !value.contains("policy="), value)
        }
        // A colour-wheel head resolves a colour to a wheel *slot*, under its own `colourWheel`. The
        // write arm writes the typed slot and answers its byte; the answer-only arm answers the
        // same byte, which a Look row holds and the cook reads back as that slot
        // (`Fixture.Property.settingBacked`) — not as the hex shorthand (`"14"` → white).
        val wheel = client.run(SpreadRequest(targets = listOf(fixture("spot-1")), property = "rgbColour", from = "#ff0000", to = "#ff0000"))
        assertEquals(listOf("14"), wheel.written.map { it.value }, "the RED slot's level, written as a typed Setting")
        assertTrue(entries().contains("spot-1" to "colourWheel"), "the slot landed as a typed programmer entry")
        val before = entries()
        val wheelDraft = client.run(
            SpreadRequest(targets = listOf(fixture("spot-1"), fixture("mac-1"), fixture("hex-1")), property = "rgbColour", from = "#ff0000", to = "#ff0000", write = false),
        )
        assertTrue(wheelDraft.skipped.isEmpty(), "no wheel head is skipped: ${wheelDraft.skipped}")
        assertEquals(
            mapOf("spot-1" to ("colourWheel" to "14"), "mac-1" to ("colourWheel" to "176"), "hex-1" to ("rgbColour" to "#ff0000")),
            wheelDraft.written.associate { it.target.key to (it.propertyName to it.value) },
            "the MAC 250's RED slot is 176 on its wheel",
        )
        for (write in wheelDraft.written.filter { it.propertyName == "colourWheel" }) {
            val parsed = CueAssignmentResolver.parseAssignmentValue(PropertyCategory.COLOUR, write.propertyName, write.value, settingBacked = true)
            assertEquals(CueAssignmentResolver.PropertyValue.Setting(write.value.toUByte()), parsed, "a Look row reads it back as the slot")
        }
        assertEquals(before, entries(), "write = false lands nothing")
    }

    @Test
    fun `write false resolves and answers every head and writes nothing`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        val out = client.run(
            SpreadRequest(targets = listOf(group("front-wash"), fixture("hex-3")), property = "dimmer", from = "pct:0", to = "pct:100", write = false),
        )
        assertEquals(listOf("hex-1", "hex-2", "hex-3"), out.written.map { it.target.key })
        assertEquals(listOf("0", "128", "255"), out.written.map { it.value })
        assertTrue(out.skipped.isEmpty())
        assertTrue(entries().isEmpty(), "nothing lands in the programmer under write = false")
        // The skips are the same walk: a head without the property is still named.
        val skipped = client.run(
            SpreadRequest(targets = listOf(fixture("hex-1")), property = "position", from = "deg:0,0", to = "deg:90,45", write = false),
        )
        assertEquals(listOf("hex-1"), skipped.skipped.map { it.target.key })
        assertTrue(entries().isEmpty())
        // And the default — the busk tab's request, which never names the field — still writes.
        client.run(SpreadRequest(targets = listOf(fixture("hex-3")), property = "dimmer", from = "pct:0", to = "pct:100"))
        assertEquals(setOf("hex-3" to "dimmer"), entries())
    }

    @Test
    fun `the selection's mask keeps a property outside it from landing`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        val out = client.run(
            SpreadRequest(targets = listOf(fixture("hex-1")), families = listOf("POSITION"), property = "dimmer", from = "pct:0", to = "pct:100"),
        )
        assertTrue(out.written.isEmpty())
        assertEquals(listOf("INTENSITY"), out.skippedFamilies)
        assertTrue(entries().isEmpty())
        val inside = client.run(
            SpreadRequest(targets = listOf(fixture("hex-1")), families = listOf("INTENSITY", "COLOUR"), property = "dimmer", from = "pct:0", to = "pct:100"),
        )
        assertEquals(1, inside.written.size)
        assertTrue(inside.skippedFamilies.isEmpty())
    }

    @Test
    fun `over CELLS fans across every cell of a bar, over HEADS treats it as one step`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        val cells = client.run(
            SpreadRequest(targets = listOf(fixture("bar-1")), property = "rgbColour", from = "#ff0000", to = "#0000ff", over = "CELLS"),
        )
        assertEquals((0 until 12).map { "bar-1.pixel-$it" }, cells.written.map { it.target.key })
        assertEquals(12, entries().count { it.first.startsWith("bar-1.pixel-") })

        val heads = client.run(
            SpreadRequest(targets = listOf(fixture("bar-1"), fixture("hex-1")), property = "rgbColour", from = "#ff0000", to = "#0000ff", over = "HEADS"),
        )
        // The bar is **one step** over HEADS. It has no colour of its own in pixel mode, so the step
        // lands on every cell that carries it — all twelve at the step's one value — where it used to
        // be skipped by name (venue-visit plan S1). Rig order is patch order, so the hex is the first
        // step and takes *from*, the bar the last and takes *to*.
        assertEquals(
            listOf("hex-1") + (0 until 12).map { "bar-1.pixel-$it" },
            heads.written.map { it.target.key },
            "rig order: every fixture in patch order, a bar's cells in element order",
        )
        assertTrue(heads.skipped.isEmpty(), heads.skipped.toString())
        val barValues = heads.written.filter { it.target.key.startsWith("bar-1.") }.map { it.value }.toSet()
        assertEquals(1, barValues.size, "one step, one value on every cell: $barValues")
        assertNotEquals(barValues.single(), heads.written.first().value, "the hex is the other end of the curve")
    }

    @Test
    fun `a centre-relative position spread lands the same steps on heads whose degree ranges differ`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        // D3: a pos: spread is a fraction of each head's own travel, so two movers take the same
        // bytes — one unit a DMX step about 128 — whatever their annotated degrees.
        val out = client.run(
            SpreadRequest(targets = listOf(fixture("spot-1"), fixture("spot-2")), property = "position", from = "pos:-20,0", to = "pos:20,10"),
        )
        assertEquals(listOf("108,128", "148,138"), out.written.map { it.value })
    }

    @Test
    fun `malformed requests are refused by code`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        val cases = listOf(
            SpreadRequest(targets = listOf(fixture("hex-1")), property = "gobo", from = "pct:0", to = "pct:1"),
            SpreadRequest(targets = listOf(fixture("hex-1")), property = "dimmer", from = "#ff0000", to = "pct:1"),
            SpreadRequest(targets = listOf(fixture("hex-1")), property = "dimmer", from = "pct:0", to = "pct:1", curve = "ZIGZAG"),
            SpreadRequest(targets = listOf(fixture("hex-1")), property = "dimmer", from = "pct:0", to = "pct:1", order = "SIDEWAYS"),
            SpreadRequest(targets = listOf(fixture("hex-1")), property = "dimmer", from = "pct:0", to = "pct:1", over = "PIXELS"),
            SpreadRequest(targets = listOf(fixture("hex-1")), property = "dimmer", from = "pct:0", to = "pct:1", parts = 0),
            SpreadRequest(targets = listOf(fixture("hex-1")), property = "rgbColour", from = "tmpl:00000000-0000-0000-0000-000000000000", to = "#000000"),
            // One position unit per spread: a lerp from a step to a degree is a number in neither.
            SpreadRequest(targets = listOf(fixture("spot-1")), property = "position", from = "pos:0,0", to = "deg:540,210"),
        )
        for (bad in cases) {
            val resp = client.post(bad)
            assertEquals(HttpStatusCode.BadRequest, resp.status, resp.bodyAsText())
            assertEquals(CODE_SPREAD_INVALID, resp.body<ErrorResponse>().code, bad.toString())
        }
        val empty = client.post(SpreadRequest(property = "dimmer", from = "pct:0", to = "pct:1"))
        assertEquals(HttpStatusCode.BadRequest, empty.status)
        assertEquals(CODE_SPREAD_NEEDS_SELECTION, empty.body<ErrorResponse>().code)
        val mask = client.post(SpreadRequest(targets = listOf(fixture("hex-1")), families = listOf("WIBBLE"), property = "dimmer", from = "pct:0", to = "pct:1"))
        assertEquals(HttpStatusCode.BadRequest, mask.status)
    }

    // ─── A fixture whose colour lives on its cells (venue-visit plan S1) ─

    private fun seedShehds() {
        // 24-channel mode: pan/tilt, dimmer, strobe and zoom on the parent, RGBW on three zones.
        LocateTestSupport.seedFixture(state, projectId, "shehds-led19-rgbw-24ch", "shehds-1", 300)
        LocateTestSupport.seedFixture(state, projectId, "shehds-led19-rgbw-24ch", "shehds-2", 330)
        LocateTestSupport.seedGroup(state, projectId, "washes", "shehds-1", "shehds-2")
        state.show.fixtures.patchListChanged()
    }

    private fun zones(key: String) = (1..3).map { "$key.zone-$it" }

    /** Each written cell's literal, by cell key — the value its step landed on it. */
    private fun SpreadResponse.byKey() = written.associate { it.target.key to it.value }

    @Test
    fun `over HEADS a Shehds is one step whose colour lands on its three zones`() = testApplication {
        seedShehds()
        mountTestApp(state)
        val client = jsonClient()

        val out = client.run(
            SpreadRequest(
                targets = listOf(fixture("shehds-1"), fixture("shehds-2")),
                property = "rgbColour", from = "#ff0000", to = "#0000ff",
            ),
        )

        assertTrue(out.skipped.isEmpty(), out.skipped.toString())
        assertEquals(zones("shehds-1") + zones("shehds-2"), out.written.map { it.target.key })
        val values = out.byKey()
        assertEquals(1, zones("shehds-1").map { values.getValue(it) }.toSet().size, "one step, one value")
        assertEquals(1, zones("shehds-2").map { values.getValue(it) }.toSet().size, "one step, one value")
        assertNotEquals(values.getValue("shehds-1.zone-1"), values.getValue("shehds-2.zone-1"), "two steps, two ends")
        // Landed as programmer entries on the cells, never on the parent, which has no colour.
        assertTrue(zones("shehds-1").all { (it to "rgbColour") in entries() })
        assertTrue(("shehds-1" to "rgbColour") !in entries())
    }

    @Test
    fun `a group of Shehds spreads in member order onto the zones, carrying the group`() = testApplication {
        seedShehds()
        mountTestApp(state)
        val client = jsonClient()

        val out = client.run(
            SpreadRequest(targets = listOf(group("washes")), property = "rgbColour", from = "#ff0000", to = "#0000ff"),
        )

        assertEquals(zones("shehds-1") + zones("shehds-2"), out.written.map { it.target.key })
        assertTrue(out.skipped.isEmpty(), out.skipped.toString())
        assertEquals(
            "washes",
            state.show.programmerStore.get("shehds-2.zone-3", "rgbColour")?.sourceGroup,
            "a fanned cell takes the group its parent arrived through",
        )
    }

    @Test
    fun `over HEADS a Shehds' cells named one by one are a step each`() = testApplication {
        seedShehds()
        mountTestApp(state)
        val client = jsonClient()

        val out = client.run(
            SpreadRequest(
                targets = listOf(fixture("shehds-1.zone-1"), fixture("shehds-1.zone-3")),
                property = "rgbColour", from = "#ff0000", to = "#0000ff",
            ),
        )

        assertEquals(listOf("shehds-1.zone-1", "shehds-1.zone-3"), out.written.map { it.target.key })
        assertNotEquals(out.written[0].value, out.written[1].value)
        assertTrue(("shehds-1.zone-2" to "rgbColour") !in entries(), "a cell the selection does not name")
    }

    @Test
    fun `a property the Shehds carries itself stays on the parent over HEADS`() = testApplication {
        seedShehds()
        mountTestApp(state)
        val client = jsonClient()

        val out = client.run(
            SpreadRequest(
                targets = listOf(fixture("shehds-1"), fixture("shehds-2")),
                property = "dimmer", from = "pct:0", to = "pct:100",
            ),
        )

        assertEquals(listOf("shehds-1", "shehds-2"), out.written.map { it.target.key })
    }

    @Test
    fun `a Shehds named beside one of its zones is one step, writing each zone once`() = testApplication {
        seedShehds()
        mountTestApp(state)
        val client = jsonClient()

        val out = client.run(
            SpreadRequest(
                targets = listOf(fixture("shehds-1.zone-2"), fixture("shehds-1"), fixture("shehds-2")),
                property = "rgbColour", from = "#ff0000", to = "#0000ff",
            ),
        )

        // The parent covers the zone named beside it, so the zone is not a step of its own: two
        // steps, six writes, no cell written twice with two values.
        assertEquals(zones("shehds-1") + zones("shehds-2"), out.written.map { it.target.key })
        assertEquals(1, zones("shehds-1").map { out.byKey().getValue(it) }.toSet().size)
    }
}
