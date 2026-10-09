package uk.me.cormack.lighting7.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scene document's per-kind params and seat maths (stage-view plan session 2, D2): parsed and
 * checked at the write boundary, written canonically, and read back. The seat numbers are pinned
 * here and again in the frontend's `lib/stageSeats.test.ts`, which must agree.
 */
class StageSceneTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun parse(kind: StageElementKind, text: String, width: Double = 5.0, height: Double = 3.0): Pair<ElementParams?, List<String>> {
        val problems = mutableListOf<String>()
        return parseElementParams(kind, obj(text), width, height, "params", problems) to problems
    }

    @Test
    fun `params parse case-insensitively and encode canonically with defaults omitted`() {
        val (params, problems) = parse(
            StageElementKind.ROOM,
            """{"omit":["upstage","stage left"],"floor":{"colour":"#2B2724","pattern":"boards"}}""",
        )
        assertEquals(emptyList(), problems)
        val room = assertIs<RoomParams>(params)
        assertEquals(listOf(StageSide.UPSTAGE, StageSide.STAGE_LEFT), room.omit)
        assertEquals(
            """{"floor":{"colour":"#2b2724","pattern":"BOARDS"},"omit":["UPSTAGE","STAGE_LEFT"]}""",
            encodeElementParams(StageElementKind.ROOM, room),
        )
        assertEquals(room, readElementParams(StageElementKind.ROOM, encodeElementParams(StageElementKind.ROOM, room)))
    }

    @Test
    fun `every problem in a params document is reported at once`() {
        val (params, problems) = parse(
            StageElementKind.FLAT,
            """{"openings":[{"kind":"porthole","fromM":4.5,"widthM":1,"heightM":2},{"fromM":"one"}],"colour":"red"}""",
        )
        assertNull(params)
        assertTrue(problems.any { "openings[0].kind must be one of" in it }, problems.toString())
        assertTrue(problems.any { "openings[0] runs past the flat's end" in it }, problems.toString())
        assertTrue(problems.any { "openings[1].fromM must be a number" in it }, problems.toString())
        assertTrue(problems.any { "openings[1].kind is required" in it }, problems.toString())
        assertTrue(problems.any { "unknown field 'colour'" in it }, problems.toString())
    }

    @Test
    fun `a proscenium's opening must fit its wall`() {
        val (_, problems) = parse(
            StageElementKind.PROSCENIUM,
            """{"openingWidthM":6,"openingHeightM":2.5,"openingSillM":1}""",
            width = 5.0,
            height = 3.0,
        )
        assertTrue(problems.any { "wider than the proscenium" in it }, problems.toString())
        assertTrue(problems.any { "above the proscenium" in it }, problems.toString())
        assertTrue(parse(StageElementKind.PROSCENIUM, "{}").second.any { "openingWidthM is required" in it })
        // An exact fit is a fit, whatever a double makes of the sum: 1.1 + 2.2 is 3.3000000000000003.
        assertEquals(
            emptyList(),
            parse(StageElementKind.PROSCENIUM, """{"openingWidthM":5,"openingHeightM":2.2,"openingSillM":1.1}""", width = 5.0, height = 3.3).second,
        )
        assertEquals(
            emptyList(),
            parse(StageElementKind.FLAT, """{"openings":[{"kind":"door","fromM":0.1,"widthM":0.2,"heightM":2}]}""", width = 0.3).second,
        )
    }

    @Test
    fun `a state belongs to the kind that can have it`() {
        assertTrue(parse(StageElementKind.DRAPE, """{"role":"tabs","operation":"draw","states":{"open":0.5}}""").second.isEmpty())
        assertTrue(parse(StageElementKind.DRAPE, """{"role":"leg","states":{"open":0.5}}""").second.any { "drawn drape" in it })
        assertTrue(parse(StageElementKind.OBJECT, """{"shape":"disc","flies":true,"states":{"trimM":5.2}}""").second.isEmpty())
        assertTrue(parse(StageElementKind.OBJECT, """{"states":{"trimM":5.2}}""").second.any { "flown piece" in it })
        assertTrue(parse(StageElementKind.OBJECT, """{"states":{"visible":false}}""").second.isEmpty())
    }

    @Test
    fun `a platform's rail comes with its edge, and seating's rows stay inside the alphabet`() {
        assertTrue(parse(StageElementKind.PLATFORM, """{"railHeightM":1}""").second.any { "go together" in it })
        assertTrue(parse(StageElementKind.PLATFORM, """{"railHeightM":1,"railEdge":"upstage"}""").second.isEmpty())
        val seating = """{"rows":12,"seatsPerRow":12,"rowPitchM":0.95,"seatPitchM":0.52,"firstRow":"R"}"""
        assertTrue(parse(StageElementKind.SEATING, seating).second.any { "run past row Z" in it })
    }

    @Test
    fun `an element is checked whole — sizes by kind, and a platform's region by the project's`() {
        val base = StageElementFields(
            name = "Stalls", kind = StageElementKind.SEATING, layer = StageElementLayer.VENUE,
            positionX = 0.0, positionY = -2.4, positionZ = -0.95, yawDeg = 0.0,
            widthM = 8.0, depthM = 0.0, heightM = 0.0,
            finishColour = null, finishPattern = null, emissive = false,
            params = obj("""{"rows":12,"seatsPerRow":12,"rowPitchM":0.95,"seatPitchM":0.52}"""),
            hidden = false,
        )
        val problems = mutableListOf<String>()
        assertNull(validateStageElement(base, emptySet(), "", problems))
        assertTrue(problems.single().contains("size comes from its rows"), problems.toString())

        problems.clear()
        assertNotNull(validateStageElement(base.copy(widthM = 0.0), emptySet(), "", problems))

        problems.clear()
        val deck = base.copy(
            name = "Main stage", kind = StageElementKind.PLATFORM, widthM = 6.3, depthM = 11.0, heightM = 0.95,
            params = obj("""{"regionUuid":"4b6e1f1c-58ab-4e5e-9a54-0e2b5d0c9f11"}"""),
        )
        assertNull(validateStageElement(deck, emptySet(), "", problems))
        assertTrue(problems.single().contains("names no stage region"), problems.toString())
        problems.clear()
        assertNotNull(validateStageElement(deck, setOf("4b6e1f1c-58ab-4e5e-9a54-0e2b5d0c9f11"), "", problems))
        problems.clear()
        assertNull(validateStageElement(deck.copy(depthM = 0.0), emptySet(), "", problems))
        assertTrue(problems.any { "depthM must be greater than 0" in it }, problems.toString())
    }

    /**
     * The prototype's stalls (`stage-view-design/prototype.html`): 12 × 12 from the element's
     * origin, row A nearest the stage. `lib/stageSeats.test.ts` pins the same seat.
     */
    @Test
    fun `seats run away from the stage from row A, seat 1 at the stage-right end`() {
        val stalls = SeatingParams(rows = 12, seatsPerRow = 12, rowPitchM = 0.95, seatPitchM = 0.52)
        val pose = ElementPose(0.0, -2.4, -0.95, 0.0)
        val a1 = stalls.seat(pose, "A1")!!
        assertEquals(-2.86, a1.base.x, 1e-9)
        assertEquals(-2.4, a1.base.y, 1e-9)
        val f6 = stalls.seat(pose, "f6")!!
        assertEquals("F6", f6.id)
        assertEquals(-0.26, f6.base.x, 1e-9)
        assertEquals(-2.4 - 5 * 0.95, f6.base.y, 1e-9)
        val eye = f6.eye(pose)
        assertEquals(f6.base.y - 0.05, eye.y, 1e-9)
        assertEquals(-0.95 + SEATED_EYE_HEIGHT_M, eye.z, 1e-9)
        assertNull(stalls.seat(pose, "M1"), "past the last row")
        assertNull(stalls.seat(pose, "A13"), "past the last seat")
        assertNull(stalls.seat(pose, "6F"), "not a seat id")
    }

    /**
     * A flat-floor hall of banquet chairs, six a side of a centre aisle. The aisle moves seats 7–12
     * over and renumbers nothing; the row, aisle included, stays centred on the origin.
     * `lib/stageSeats.test.ts` pins the same seats.
     */
    @Test
    fun `an aisle opens a gap in every row without renumbering a seat`() {
        val stalls = SeatingParams(
            rows = 17, seatsPerRow = 12, rowPitchM = 0.85, seatPitchM = 0.5,
            aisles = listOf(SeatingAisle(afterSeat = 6, widthM = 1.1)), chair = ChairStyle.BANQUET,
        )
        val pose = ElementPose(0.0, -2.65, -0.95, 0.0)
        assertEquals(-3.3, stalls.seat(pose, "A1")!!.base.x, 1e-9)
        assertEquals(-0.8, stalls.seat(pose, "A6")!!.base.x, 1e-9)
        assertEquals(0.8, stalls.seat(pose, "A7")!!.base.x, 1e-9)
        assertEquals(3.3, stalls.seat(pose, "Q12")!!.base.x, 1e-9)
        assertEquals(-2.65 - 16 * 0.85, stalls.seat(pose, "Q12")!!.base.y, 1e-9)
    }

    @Test
    fun `a seating's chair, frame and aisles parse, sort and encode canonically`() {
        val (params, problems) = parse(
            StageElementKind.SEATING,
            """{"rows":17,"seatsPerRow":12,"rowPitchM":0.85,"seatPitchM":0.5,"chair":"banquet","frameColour":"#C9A44C",
               "aisles":[{"afterSeat":9,"widthM":0.6},{"afterSeat":3,"widthM":0.6}]}""",
        )
        assertEquals(emptyList(), problems)
        val seating = assertIs<SeatingParams>(params)
        assertEquals(ChairStyle.BANQUET, seating.chair)
        assertEquals(listOf(3, 9), seating.aisles.map { it.afterSeat })
        val text = encodeElementParams(StageElementKind.SEATING, seating)
        assertEquals(
            """{"aisles":[{"afterSeat":3,"widthM":0.6},{"afterSeat":9,"widthM":0.6}],"chair":"BANQUET","frameColour":"#c9a44c",""" +
                """"rowPitchM":0.85,"rows":17,"seatPitchM":0.5,"seatsPerRow":12}""",
            text,
        )
        assertEquals(seating, readElementParams(StageElementKind.SEATING, text))
        // The default chair and no aisles are omitted, so a seating written before them reads the same.
        assertEquals(
            """{"rowPitchM":0.85,"rows":2,"seatPitchM":0.5,"seatsPerRow":4}""",
            encodeElementParams(StageElementKind.SEATING, SeatingParams(2, 4, 0.85, 0.5)),
        )

        val bad = parse(
            StageElementKind.SEATING,
            """{"rows":2,"seatsPerRow":12,"rowPitchM":0.85,"seatPitchM":0.5,"chair":"pew","frameColour":"gold",
               "aisles":[{"afterSeat":12,"widthM":1},{"afterSeat":6,"widthM":1},{"afterSeat":6,"widthM":1},{"afterSeat":4,"widthM":0.05,"side":"x"}]}""",
        ).second
        assertTrue(bad.any { "chair must be one of THEATRE, BANQUET" in it }, bad.toString())
        assertTrue(bad.any { "frameColour must be a colour" in it }, bad.toString())
        assertTrue(bad.any { "aisles[0].afterSeat (12) must be before the row's last seat (12)" in it }, bad.toString())
        assertTrue(bad.any { "two aisles after the same seat" in it }, bad.toString())
        assertTrue(bad.any { "aisles[3].widthM must be between" in it }, bad.toString())
        assertTrue(bad.any { "aisles[3]: unknown field 'side'" in it }, bad.toString())
    }

    @Test
    fun `a turned seating turns its seats about its origin, and a rake lifts each row`() {
        val raked = SeatingParams(rows = 3, seatsPerRow = 1, rowPitchM = 1.0, seatPitchM = 0.5, rakeM = 0.2)
        val pose = ElementPose(1.0, -2.0, 0.0, 90.0)
        val c1 = raked.seat(pose, "C1")!!
        // Row C is two rows back: local (0, −2) turned 90° anticlockwise is (+2, 0).
        assertEquals(3.0, c1.base.x, 1e-9)
        assertEquals(-2.0, c1.base.y, 1e-9)
        assertEquals(0.4, c1.base.z, 1e-9)
    }

    @Test
    fun `a viewpoint is checked by its kind`() {
        val seating = SeatingElement("Stalls", ElementPose(0.0, -2.4, -0.95, 0.0), SeatingParams(12, 12, 0.95, 0.52))
        val uuid = java.util.UUID.randomUUID()
        fun check(fields: StageViewpointFields): List<String> =
            mutableListOf<String>().also { validateStageViewpoint(fields, { if (it == uuid) seating else null }, "", it) }
        val eye = StageViewpointFields("Desk", StageViewpointKind.EYE, StagePoint(1.25, -17.0, 2.6), StagePoint(0.0, 2.6, 0.8), 50.0, null, null)
        assertEquals(emptyList(), check(eye))
        assertTrue(check(eye.copy(kind = StageViewpointKind.ORBIT)).any { "orbit camera's lens" in it })
        assertTrue(check(eye.copy(target = null)).any { "needs a target" in it })
        assertTrue(check(eye.copy(fovDeg = 120.0)).any { "fovDeg must be between" in it })

        val seat = StageViewpointFields("Row F", StageViewpointKind.SEAT, null, null, null, uuid, "F6")
        assertEquals(emptyList(), check(seat))
        assertTrue(check(seat.copy(seatId = "Q1")).single().contains("has no seat 'Q1' (rows A–L, seats 1–12)"))
        assertTrue(check(seat.copy(seatElementUuid = java.util.UUID.randomUUID())).any { "not a seating element" in it })
        assertTrue(check(seat.copy(eye = StagePoint(0.0, 0.0, 1.0))).any { "leave eye out" in it })
    }

    /** `travelS` (scenery-programmer plan D6): a moving piece's only, refused by name elsewhere. */
    @Test
    fun `travelS is a drawn or flown piece's, and refused by name on anything that does not travel`() {
        val (draw, drawProblems) = parse(StageElementKind.DRAPE, """{"role":"TABS","operation":"DRAW","travelS":4}""")
        assertEquals(emptyList(), drawProblems)
        assertEquals(4.0, assertIs<DrapeParams>(draw).travelS)
        assertEquals(4.0, elementTravelS(draw))
        assertEquals("""{"operation":"DRAW","role":"TABS","travelS":4.0}""", encodeElementParams(StageElementKind.DRAPE, draw))

        val (fly, flyProblems) = parse(StageElementKind.DRAPE, """{"role":"BACKCLOTH","operation":"FLY","travelS":12.5}""")
        assertEquals(emptyList(), flyProblems)
        assertEquals(12.5, elementTravelS(fly))
        val (moon, moonProblems) = parse(StageElementKind.OBJECT, """{"shape":"DISC","flies":true,"travelS":8}""")
        assertEquals(emptyList(), moonProblems)
        assertEquals(8.0, assertIs<ObjectParams>(moon).travelS)

        val (dead, deadProblems) = parse(StageElementKind.DRAPE, """{"role":"LEG","operation":"DEAD","travelS":4}""")
        assertNull(dead)
        assertTrue(deadProblems.single().startsWith("params.travelS is a moving piece's"), deadProblems.toString())
        assertTrue("this DRAPE with operation DEAD does not travel" in deadProblems.single(), deadProblems.toString())
        val (_, unset) = parse(StageElementKind.DRAPE, """{"role":"LEG","travelS":4}""")
        assertTrue("operation DEAD does not travel" in unset.single(), unset.toString())

        val (flat, flatProblems) = parse(StageElementKind.FLAT, """{"travelS":4}""")
        assertNull(flat)
        assertTrue("this FLAT does not travel" in flatProblems.single(), flatProblems.toString())
        assertTrue(flatProblems.none { "unknown field" in it }, "named, not an unknown field: $flatProblems")
        val (_, grounded) = parse(StageElementKind.OBJECT, """{"shape":"BOX","travelS":4}""")
        assertTrue("this OBJECT does not travel" in grounded.single(), grounded.toString())

        // Every problem at once: the range and the rest of the document together.
        val (_, many) = parse(StageElementKind.DRAPE, """{"role":"TABS","operation":"DRAW","travelS":0.05,"states":{"trimM":2}}""")
        assertTrue(many.any { "travelS must be between 0.1 and 600.0 seconds" in it }, many.toString())
        assertTrue(many.any { "states.trimM is a flown piece's" in it }, many.toString())

        // An older document without it reads as no travel, and one with it reads back whole.
        assertNull(elementTravelS(readElementParams(StageElementKind.DRAPE, """{"operation":"DRAW","role":"TABS"}""")))
        assertEquals(draw, readElementParams(StageElementKind.DRAPE, encodeElementParams(StageElementKind.DRAPE, draw)))
        // A stored travel the piece no longer has (switched to DEAD) is ignored, not obeyed.
        assertNull(elementTravelS(DrapeParams(DrapeRole.LEG, DrapeOperation.DEAD, travelS = 4.0)))
    }

    // ─── Fabric and paint (scrim plan session 1, D1, D4) ────────────────────────────────────────

    private val front = "a".repeat(64)
    private val back = "b".repeat(64)
    private val held = setOf(front, back)

    private fun parsePainted(kind: StageElementKind, text: String): Pair<ElementParams?, List<String>> {
        val problems = mutableListOf<String>()
        return parseElementParams(kind, obj(text), 12.0, 6.0, "params", problems, imageStored = { it in held }) to problems
    }

    @Test
    fun `a drape's fabric and paint round-trip, canonical and case-insensitive`() {
        val (params, problems) = parsePainted(
            StageElementKind.DRAPE,
            """{"role":"backcloth","operation":"fly","fabric":"sharkstooth","paint":{"front":"${front.uppercase()}","back":"$back"}}""",
        )
        assertEquals(emptyList(), problems)
        val drape = assertIs<DrapeParams>(params)
        assertEquals(DrapeFabric.SHARKSTOOTH, drape.fabric)
        assertEquals(ScenePaint(front = front, back = back), drape.paint)
        val text = encodeElementParams(StageElementKind.DRAPE, drape)
        assertEquals(
            """{"fabric":"SHARKSTOOTH","operation":"FLY","paint":{"back":"$back","front":"$front"},"role":"BACKCLOTH"}""",
            text,
        )
        assertEquals(drape, readElementParams(StageElementKind.DRAPE, text))
        assertEquals(setOf(front, back), paintHashesOf(text))

        // Velour is the absence of a fabric: nothing is written for it.
        val (velour, _) = parsePainted(StageElementKind.DRAPE, """{"role":"LEG"}""")
        assertEquals("""{"role":"LEG"}""", encodeElementParams(StageElementKind.DRAPE, velour!!))
    }

    @Test
    fun `a flat takes paint, its openings still beside it`() {
        val (params, problems) = parsePainted(
            StageElementKind.FLAT,
            """{"openings":[{"kind":"DOOR","fromM":1,"widthM":1,"heightM":2}],"paint":{"back":"$back"}}""",
        )
        assertEquals(emptyList(), problems)
        val flat = assertIs<FlatParams>(params)
        assertEquals(ScenePaint(back = back), flat.paint)
        assertEquals(1, flat.openings.size)
    }

    @Test
    fun `an empty paint is written as absent`() {
        for (text in listOf("""{"role":"CYC","paint":{}}""", """{"role":"CYC","paint":{"front":null,"back":null}}""", """{"role":"CYC","paint":null}""")) {
            val (params, problems) = parsePainted(StageElementKind.DRAPE, text)
            assertEquals(emptyList(), problems, text)
            assertNull((params as DrapeParams).paint, text)
            assertEquals("""{"role":"CYC"}""", encodeElementParams(StageElementKind.DRAPE, params), text)
        }
    }

    @Test
    fun `fabric and paint refusals are named, every one at once`() {
        val unknown = "c".repeat(64)
        val (params, problems) = parsePainted(
            StageElementKind.DRAPE,
            """{"role":"BACKCLOTH","fabric":"lace","paint":{"front":"$unknown","back":"nope","side":"x"}}""",
        )
        assertNull(params)
        assertTrue("params.fabric must be one of CANVAS, MUSLIN, SHARKSTOOTH, BOBBINET" in problems, problems.toString())
        assertTrue("params.paint.front names no stored image" in problems, problems.toString())
        assertTrue("params.paint.back must be an image's SHA-256: 64 hex characters" in problems, problems.toString())
        assertTrue(problems.any { "params.paint: unknown field 'side'" in it }, problems.toString())

        val (_, notString) = parsePainted(StageElementKind.DRAPE, """{"role":"LEG","paint":{"front":7},"fabric":3}""")
        assertTrue("params.paint.front must be a string" in notString, notString.toString())
        assertTrue("params.fabric must be a string" in notString, notString.toString())
        val (_, notObject) = parsePainted(StageElementKind.FLAT, """{"paint":"$front"}""")
        assertEquals(listOf("params.paint must be an object"), notObject)

        // A kind that takes neither refuses both by name, not as unknown fields.
        val (obj, wrongKind) = parsePainted(StageElementKind.OBJECT, """{"fabric":"canvas","paint":{"front":"$front"}}""")
        assertNull(obj)
        assertTrue("params.fabric is a drape's (a DRAPE); this OBJECT has none" in wrongKind, wrongKind.toString())
        assertTrue("params.paint is a drape's or a flat's (a DRAPE or a FLAT); this OBJECT takes none" in wrongKind, wrongKind.toString())
        val (_, flatFabric) = parsePainted(StageElementKind.FLAT, """{"fabric":"canvas"}""")
        assertEquals(listOf("params.fabric is a drape's (a DRAPE); this FLAT has none"), flatFabric)
    }

    @Test
    fun `an element's stored paint is let stand when this machine lacks the image`() {
        val fields = StageElementFields(
            name = "Forest", kind = StageElementKind.DRAPE, layer = StageElementLayer.SET,
            positionX = 0.0, positionY = 5.0, positionZ = 0.0, yawDeg = 0.0,
            widthM = 12.0, depthM = 0.05, heightM = 6.0,
            finishColour = null, finishPattern = null, emissive = false,
            params = obj("""{"role":"BACKCLOTH","paint":{"front":"$front"}}"""), hidden = false,
        )
        val refused = mutableListOf<String>()
        assertNull(validateStageElement(fields, emptySet(), "", refused, imageStored = { false }))
        assertEquals(listOf("params.paint.front names no stored image"), refused)
        val kept = mutableListOf<String>()
        assertNotNull(validateStageElement(fields, emptySet(), "", kept, imageStored = { false }, storedPaint = setOf(front)))
        assertEquals(emptyList(), kept)
        // Through set_scene's spelling the message carries the row.
        val row = mutableListOf<String>()
        validateStageElement(fields, emptySet(), "elements[0] ('Forest')", row)
        assertEquals(listOf("elements[0] ('Forest').params.paint.front names no stored image"), row)
    }

    @Test
    fun `paint hashes are read leniently from a stored document`() {
        assertEquals(setOf(front), paintHashesOf("""{"paint":{"front":"$front","back":"short"}}"""))
        assertEquals(emptySet(), paintHashesOf("not json"))
        assertEquals(emptySet(), paintHashesOf("""{"paint":"$front"}"""))
    }
}
