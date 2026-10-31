package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.KamLiteobar252Fixture
import uk.me.cormack.lighting7.fixture.dmx.LightstripRgbFixture
import uk.me.cormack.lighting7.fx.CueAssignmentResolver.AttributeValue
import uk.me.cormack.lighting7.models.MoveInDarkSettings
import uk.me.cormack.lighting7.models.MoveInDarkWhen
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a dark head goes (move-in-dark plan D5): the first entry ahead whose cue lights it, through
 * cues that keep it dark; only what that cue sets, in the families the stack moves.
 *
 * Entries 10, 11, 12, 13 hold cues 1, 2, 3, 4 (entry *n*'s cue is *n* − 9); a marker is never an
 * entry here, as `orderedCueEntries` leaves markers out. `hex` has a dimmer, `strip` none.
 */
class MoveInDarkPlannerTest {

    private val heads: Map<String, HeadIntensity> = run {
        val fixtures = Fixtures()
        val universe = Universe(0, 0)
        fixtures.register(removeUnused = true) {
            addController(MockDmxController(universe))
            addFixture(HexFixture(universe, "hex", "Hex", firstChannel = 1))
            addFixture(LightstripRgbFixture(universe, "strip", "Strip", firstChannel = 21))
            // Three RGB cells and no dimmer anywhere: the bar is lit only through them.
            addFixture(KamLiteobar252Fixture(universe, "bar", "Bar", firstChannel = 31))
        }
        HeadIntensity.classify(fixtures)
    }

    private val entries = (10..13).map { CueEntryRef(it, it - 9) }

    private fun row(target: String, attribute: String, category: AttributeCategory, value: AttributeValue) =
        CueAssignmentResolver.Assignment(
            cueId = 0, priority = 0, fadeWeight = 1.0, targetKey = target, targetIsGroup = false,
            attribute = attribute, category = category, value = value,
        )

    private fun dimmer(target: String, level: Int) = row(target, "dimmer", AttributeCategory.DIMMER, AttributeValue.Slider(level.toUByte()))
    private fun position(target: String, pan: Int, tilt: Int) =
        row(target, "position", AttributeCategory.PAN, AttributeValue.Position(pan.toUByte(), tilt.toUByte()))
    private fun colour(target: String, colour: Color) =
        row(target, "rgbColour", AttributeCategory.COLOUR, AttributeValue.Colour(ExtendedColour(colour)))
    private fun zoom(target: String, level: Int) = row(target, "zoom", AttributeCategory.ZOOM, AttributeValue.Slider(level.toUByte()))

    private fun plan(
        cues: Map<Int, MoveInDarkPlanner.CueContent>,
        live: Int = 10,
        next: Int? = live + 1,
        loop: Boolean = false,
        settings: MoveInDarkSettings = MoveInDarkSettings.NEW_STACK,
    ) = MoveInDarkPlanner.plan(
        MoveInDarkPlanner.Input(
            settings = settings,
            ordered = entries,
            liveEntryId = live,
            next = next?.let { n -> entries.first { it.entryId == n } },
            loop = loop,
            heads = heads,
            content = { cueId -> cues[cueId] ?: MoveInDarkPlanner.CueContent(emptyList()) },
        ),
    )

    private fun content(vararg rows: CueAssignmentResolver.Assignment) = MoveInDarkPlanner.CueContent(rows.toList())

    @Test
    fun `Early goes through two dark cues to the one that lights the head`() {
        val plan = plan(
            mapOf(
                2 to content(dimmer("hex", 0), position("hex", 1, 1)),
                3 to content(position("hex", 2, 2)),
                4 to content(dimmer("hex", 255), position("hex", 200, 100)),
            ),
        )
        val move = plan.getValue("hex")
        assertEquals(13, move.targetEntryId)
        assertEquals(4, move.targetCueId)
        assertEquals(listOf(AttributeValue.Position(200u, 100u)), move.rows.map { it.value })
        assertEquals(MoveInDarkSettings.DEFAULT_DELAY, move.delay)
        assertEquals(MoveInDarkSettings.DEFAULT_TIME, move.time)
    }

    @Test
    fun `Late looks at the next GO alone`() {
        val cues = mapOf(4 to content(dimmer("hex", 255), position("hex", 200, 100)))
        val late = MoveInDarkSettings.NEW_STACK.copy(whenMode = MoveInDarkWhen.LATE)
        assertTrue(plan(cues, settings = late).isEmpty(), "the next GO does not light it")
        assertEquals(13, plan(cues, next = 13, settings = late).getValue("hex").targetEntryId)
    }

    @Test
    fun `a looping stack wraps once, never back onto the live entry`() {
        val cues = mapOf(1 to content(dimmer("hex", 255), position("hex", 9, 9)))
        assertTrue(plan(cues, live = 12, next = 13).isEmpty(), "no wrap without a loop")
        assertEquals(10, plan(cues, live = 12, next = 13, loop = true).getValue("hex").targetEntryId)
        val own = mapOf(3 to content(dimmer("hex", 255), position("hex", 9, 9)))
        assertTrue(plan(own, live = 12, next = 13, loop = true).isEmpty(), "the live cue is not ahead of itself")
    }

    @Test
    fun `an armed earlier standby is where the walk starts`() {
        val cues = mapOf(
            1 to content(dimmer("hex", 255), position("hex", 1, 1)),
            4 to content(dimmer("hex", 255), position("hex", 4, 4)),
        )
        assertEquals(13, plan(cues, live = 12, next = 13).getValue("hex").targetEntryId)
        assertEquals(10, plan(cues, live = 12, next = 10).getValue("hex").targetEntryId, "Q10 armed while Q12 is live")
    }

    @Test
    fun `only what the target sets moves, in the stack's families`() {
        val cues = mapOf(2 to content(dimmer("hex", 255), position("hex", 5, 6), colour("hex", Color.RED), zoom("hex", 70)))
        val all = plan(cues).getValue("hex").rows.map { it.attribute }.toSet()
        assertEquals(setOf("position", "rgbColour", "zoom"), all, "intensity never moves")

        val positionOnly = MoveInDarkSettings.NEW_STACK.copy(families = setOf(AttributeFamily.POSITION))
        assertEquals(listOf("position"), plan(cues, settings = positionOnly).getValue("hex").rows.map { it.attribute })

        val nothing = mapOf(2 to content(dimmer("hex", 255)))
        assertNull(plan(nothing)["hex"], "a target that sets nothing movable plans no move")
    }

    @Test
    fun `nothing that lights a head goes in the dark`() {
        val ring = row("hex", "ring", AttributeCategory.RING, AttributeValue.Setting(40u))
        val macro = row("hex", "ledMacro", AttributeCategory.LED_MACRO, AttributeValue.Setting(40u))
        val uvColour = row("hex", "rgbColour", AttributeCategory.COLOUR, AttributeValue.Colour(ExtendedColour(Color.RED, uv = 200u)))
        val cues = mapOf(2 to content(dimmer("hex", 255), position("hex", 5, 6), ring, macro, uvColour))
        assertEquals(listOf("position"), plan(cues).getValue("hex").rows.map { it.attribute })
    }

    @Test
    fun `the live cue's lit heads are named beside the plan`() {
        val lit = MoveInDarkPlanner.litHeads(content(dimmer("hex", 255), colour("strip", Color.RED)), heads)
        assertEquals(setOf("hex", "strip"), lit)
    }

    @Test
    fun `a dimmerless head keeps its colour, which is its intensity`() {
        val cues = mapOf(2 to content(colour("strip", Color.BLUE)))
        assertNull(plan(cues)["strip"], "its colour lights it and never moves")
    }

    @Test
    fun `an Intensity FX lights a head its rows leave dark`() {
        val cues = mapOf(
            2 to MoveInDarkPlanner.CueContent(listOf(position("hex", 3, 3)), intensityFxHeads = setOf("hex")),
            3 to content(dimmer("hex", 255), position("hex", 7, 7)),
        )
        assertEquals(11, plan(cues).getValue("hex").targetEntryId, "the FX's cue is the one that lights it")
    }

    @Test
    fun `a parent lit only through its cells is lit by a cell's row`() {
        val cell = heads.getValue("bar").elementKeys.first()
        val cues = mapOf(3 to content(colour(cell, Color.GREEN), zoom("bar", 40)))
        val move = plan(cues).getValue("bar")
        assertEquals(12, move.targetEntryId)
        assertEquals(listOf("zoom"), move.rows.map { it.attribute }, "the bar's own rows move; the cell's colour is its intensity")
        assertNull(plan(cues)[cell])
    }

    @Test
    fun `a stack that is off plans nothing`() {
        val cues = mapOf(2 to content(dimmer("hex", 255), position("hex", 5, 6)))
        assertTrue(plan(cues, settings = MoveInDarkSettings.OFF).isEmpty())
    }
}
