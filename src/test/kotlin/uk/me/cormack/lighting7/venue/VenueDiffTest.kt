package uk.me.cormack.lighting7.venue

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.TestJson
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The diff rule over each kind of change (venue plan §3.1): what changes output — a universe or a
 * patch added, removed or re-addressed, a patch re-typed — and what only changes the drawing.
 */
class VenueDiffTest {

    private val u1 = "00000000-0000-0000-0000-000000000001"
    private val u2 = "00000000-0000-0000-0000-000000000002"
    private val lx1 = "00000000-0000-0000-0000-0000000000a1"
    private val lx2 = "00000000-0000-0000-0000-0000000000a2"
    private val face = "00000000-0000-0000-0000-0000000000f1"

    private fun obj(text: String): JsonObject = TestJson.parseToJsonElement(text).jsonObject

    private fun universe(uuid: String, n: Int, subnet: Int = 0) =
        obj("""{"uuid":"$uuid","subnet":$subnet,"universe":$n,"controllerType":"ARTNET"}""")

    private fun patch(
        channel: Int = 5,
        universe: String = u1,
        type: String = "generic-dimmer",
        extra: String = "",
    ) = obj(
        """{"uuid":"$face","universeConfigUuid":"$universe","fixtureTypeKey":"$type","key":"sr-face",
           "displayName":"SR FACE","startChannel":$channel,"sortOrder":0$extra}""",
    )

    private fun records(vararg entries: Pair<VenueDiff.Key, JsonObject>, project: JsonObject = obj("""{"uuid":"p","name":"Hall"}""")) =
        VenueDiff.Records(mapOf(VenueDiff.Key("project", "project") to project) + entries)

    private fun universes(vararg u: JsonObject) = u.map { VenueDiff.Key("universeConfigs", it["uuid"].toString().trim('"')) to it }.toTypedArray()
    private fun patches(vararg p: JsonObject) = p.map { VenueDiff.Key("fixturePatches", it["uuid"].toString().trim('"')) to it }.toTypedArray()

    private val base = records(*universes(universe(u1, 1)), *patches(patch()))

    private fun diffTo(after: VenueDiff.Records) = VenueDiff.compare(base, after)

    @Test
    fun `nothing changed is no change, and order alone is none`() {
        assertEquals(0, diffTo(base).size)
        assertEquals(0, diffTo(records(*universes(universe(u1, 1)), *patches(patch(extra = ""","sortOrder":9""")))).size)
    }

    @Test
    fun `a patch added, removed, re-addressed or re-typed changes output`() {
        val added = VenueDiff.compare(records(*universes(universe(u1, 1))), base)
        assertEquals(VenueChangeType.ADDED, added.output.single().change)
        assertEquals("1-005", added.output.single().address)

        val removed = VenueDiff.compare(base, records(*universes(universe(u1, 1))))
        assertEquals(VenueChangeType.REMOVED, removed.output.single().change)
        assertEquals("SR FACE", removed.output.single().name)

        val moved = diffTo(records(*universes(universe(u1, 1)), *patches(patch(channel = 12))))
        assertEquals(listOf(VenueFacet.ADDRESS), moved.output.single().facets)
        assertEquals(VenueFieldChangeDto("address", "1-005", "1-012"), moved.output.single().fields.single())

        val otherUniverse = diffTo(records(*universes(universe(u1, 1), universe(u2, 3)), *patches(patch(universe = u2))))
        assertEquals("3-005", otherUniverse.output.single { it.kind == VenueChangeKind.PATCH }.address)

        val retyped = diffTo(records(*universes(universe(u1, 1)), *patches(patch(type = "hex"))))
        assertEquals(listOf(VenueFacet.TYPE), retyped.output.single().facets)
    }

    @Test
    fun `a universe added, removed or re-addressed changes output`() {
        val added = diffTo(records(*universes(universe(u1, 1), universe(u2, 3)), *patches(patch())))
        assertEquals(VenueChangeKind.UNIVERSE, added.output.single().kind)
        assertEquals("Universe 3", added.output.single().name)

        val readdressed = diffTo(records(*universes(universe(u1, 1, subnet = 2)), *patches(patch())))
        assertEquals(listOf("subnet"), readdressed.output.single().fields.map { it.field })
        assertTrue(readdressed.drawing.isEmpty())

        // A renumbered universe moves every patch on it, which says so on its own row.
        val renumbered = diffTo(records(*universes(universe(u1, 4)), *patches(patch())))
        assertEquals(setOf(VenueChangeKind.UNIVERSE, VenueChangeKind.PATCH), renumbered.output.map { it.kind }.toSet())
    }

    @Test
    fun `position, focus, media and identity are drawing only, each its facet`() {
        fun facetsOf(extra: String) =
            diffTo(records(*universes(universe(u1, 1)), *patches(patch(extra = extra)))).let {
                assertTrue(it.output.isEmpty(), "$extra changes no output")
                it.drawing.single().facets
            }
        assertEquals(listOf(VenueFacet.POSITION), facetsOf(""","stageX":1.5"""))
        assertEquals(listOf(VenueFacet.POSITION), facetsOf(""","riggingUuid":"$lx1""""))
        assertEquals(listOf(VenueFacet.FOCUS), facetsOf(""","zoomDeg":24"""))
        // A stored locate (venue-visit plan D6) is Focus, and drawing only: it is sent only while an
        // operator holds Locate, never by the show on its own, so it never holds a show.
        assertEquals(listOf(VenueFacet.FOCUS), facetsOf(""","locate":[{"pan":40,"tilt":200}]"""))
        assertEquals(listOf(VenueFacet.MEDIA), facetsOf(""","gelCode":"L201""""))
        assertEquals(listOf(VenueFacet.IDENTITY), facetsOf(""","headNumber":7"""))
        assertEquals(listOf(VenueFacet.POSITION), facetsOf(""","extraPlacements":[{"uuid":"$lx2","stageX":2.0}]"""))
    }

    @Test
    fun `a patch's or a group's colour is drawing only`() {
        val recoloured = diffTo(records(*universes(universe(u1, 1)), *patches(patch(extra = ""","colour":"red""""))))
        assertTrue(recoloured.output.isEmpty(), "a colour never changes output")
        assertEquals(listOf(VenueFacet.OTHER), recoloured.drawing.single().facets)

        val group = { colour: String -> VenueDiff.Key("fixtureGroups", "g") to obj("""{"uuid":"g","name":"Front"$colour}""") }
        val groupDiff = VenueDiff.compare(
            records(*universes(universe(u1, 1)), *patches(patch()), group("")),
            records(*universes(universe(u1, 1)), *patches(patch()), group(""","colour":"lime"""")),
        )
        assertTrue(groupDiff.output.isEmpty())
        assertEquals(VenueChangeKind.GROUP, groupDiff.drawing.single().kind)
    }

    @Test
    fun `any other table a venue holds is counted, drawing only, and its speed masters never are`() {
        // What a publish freezes and a discard undoes is counted, so a draft never differs uncounted.
        val look = VenueDiff.Key("looks", "l") to obj("""{"uuid":"l","name":"Focus check","rows":[]}""")
        val master = VenueDiff.Key("speedMasters", "s") to obj("""{"uuid":"s","name":"Master 1","bpm":120.0}""")
        val diff = VenueDiff.compare(base, records(*universes(universe(u1, 1)), *patches(patch()), look, master))
        assertTrue(diff.output.isEmpty())
        val change = diff.drawing.single()
        assertEquals(VenueChangeKind.OTHER, change.kind)
        assertEquals("looks", change.table)
        assertEquals("Focus check", change.name)
    }

    @Test
    fun `the rest of the venue is drawing only`() {
        val rigging = VenueDiff.Key("riggings", lx1) to obj("""{"uuid":"$lx1","name":"LX1","positionZ":6.0}""")
        val element = VenueDiff.Key("stageElements", lx2) to obj("""{"uuid":"$lx2","name":"Tabs","kind":"DRAPE"}""")
        val group = VenueDiff.Key("fixtureGroups", "g") to obj("""{"uuid":"g","name":"Front","members":[]}""")
        val before = records(*universes(universe(u1, 1)), *patches(patch()), rigging, element, group)
        val after = records(
            *universes(universe(u1, 1)), *patches(patch()),
            VenueDiff.Key("riggings", lx1) to obj("""{"uuid":"$lx1","name":"LX1","positionZ":6.5}"""),
            VenueDiff.Key("fixtureGroups", "g") to obj("""{"uuid":"g","name":"Front","members":[{"uuid":"m","fixturePatchUuid":"$face"}]}"""),
            VenueDiff.Key("stageViewpoints", "v") to obj("""{"uuid":"v","name":"Balcony","kind":"SEAT"}"""),
            project = obj("""{"uuid":"p","name":"Renamed","stageWidthM":9.0}"""),
        )
        val diff = VenueDiff.compare(before, after)
        assertTrue(diff.output.isEmpty())
        val byKind = diff.drawing.associateBy { it.kind }
        assertEquals(VenueChangeType.CHANGED, byKind.getValue(VenueChangeKind.RIGGING).change)
        assertEquals(VenueChangeType.REMOVED, byKind.getValue(VenueChangeKind.ELEMENT).change)
        assertEquals(VenueChangeType.ADDED, byKind.getValue(VenueChangeKind.VIEWPOINT).change)
        assertEquals(VenueFieldChangeDto("members", "0 members", "1 member"), byKind.getValue(VenueChangeKind.GROUP).fields.single())
        // The stage's size is versioned; the venue's name is its own, not the version's.
        assertEquals(listOf("stageWidthM"), byKind.getValue(VenueChangeKind.STAGE).fields.map { it.field })
    }
}
