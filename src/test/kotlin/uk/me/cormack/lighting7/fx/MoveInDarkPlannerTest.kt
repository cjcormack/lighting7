package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.KamLiteobar252Fixture
import uk.me.cormack.lighting7.fixture.dmx.LightstripRgbFixture
import uk.me.cormack.lighting7.fx.CueAssignmentResolver.AttributeValue
import uk.me.cormack.lighting7.fx.MoveInDarkPlanner.LiveReason
import uk.me.cormack.lighting7.models.CueMoveInDarkMode
import uk.me.cormack.lighting7.models.CueMoveInDarkSettings
import uk.me.cormack.lighting7.models.MoveInDarkSettings
import uk.me.cormack.lighting7.models.MoveInDarkWhen
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color
import java.time.Duration
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

    // ─── The cue's own setting (D3) ─────────────────────────────────────────────────────────

    private fun content(setting: CueMoveInDarkSettings, vararg rows: CueAssignmentResolver.Assignment) =
        MoveInDarkPlanner.CueContent(rows.toList(), moveInDark = setting)

    private fun cue(mode: CueMoveInDarkMode, delayMs: Long? = null, timeMs: Long? = null, live: List<String> = emptyList()) =
        CueMoveInDarkSettings(mode, delayMs?.let(Duration::ofMillis), timeMs?.let(Duration::ofMillis), live)

    private val lit = arrayOf(dimmer("hex", 255), position("hex", 200, 100))

    @Test
    fun `a cue set Off moves nothing, and the head is not walked past it`() {
        val cues = mapOf(
            2 to content(cue(CueMoveInDarkMode.OFF), *lit),
            3 to content(dimmer("hex", 255), position("hex", 9, 9)),
        )
        assertTrue(plan(cues).isEmpty(), "Q2 lights it first and moves it in its fade, lit")
    }

    @Test
    fun `a cue's Early or Late applies on a stack that is off`() {
        val early = mapOf(3 to content(cue(CueMoveInDarkMode.EARLY), *lit))
        assertEquals(12, plan(early, settings = MoveInDarkSettings.OFF).getValue("hex").targetEntryId)
        val late = mapOf(3 to content(cue(CueMoveInDarkMode.LATE), *lit))
        assertTrue(plan(late, settings = MoveInDarkSettings.OFF).isEmpty(), "Late: Q3 is not the next GO")
        assertEquals(12, plan(late, next = 12, settings = MoveInDarkSettings.OFF).getValue("hex").targetEntryId)
        val stack = mapOf(3 to content(*lit))
        assertTrue(plan(stack, next = 12, settings = MoveInDarkSettings.OFF).isEmpty(), "Stack follows the stack, which is off")
    }

    @Test
    fun `a cue's Early reaches past the next GO on a Late stack`() {
        val late = MoveInDarkSettings.NEW_STACK.copy(whenMode = MoveInDarkWhen.LATE)
        assertTrue(plan(mapOf(4 to content(*lit)), settings = late).isEmpty())
        assertEquals(13, plan(mapOf(4 to content(cue(CueMoveInDarkMode.EARLY), *lit)), settings = late).getValue("hex").targetEntryId)
    }

    @Test
    fun `a cue's own delay and move time are its target's, else the stack's`() {
        val own = plan(mapOf(2 to content(cue(CueMoveInDarkMode.STACK, delayMs = 3_000), *lit))).getValue("hex")
        assertEquals(Duration.ofMillis(3_000), own.delay)
        assertEquals(MoveInDarkSettings.DEFAULT_TIME, own.time)
        val stack = MoveInDarkSettings.NEW_STACK.copy(time = Duration.ofMillis(700))
        assertEquals(Duration.ofMillis(700), plan(mapOf(2 to content(*lit)), settings = stack).getValue("hex").time)
    }

    @Test
    fun `a head the target keeps live is not moved, and a fixture kept live covers its cells`() {
        val cell = heads.getValue("bar").elementKeys.first()
        val cues = mapOf(
            2 to content(
                cue(CueMoveInDarkMode.STACK, live = listOf("hex", "bar")),
                *lit,
                dimmer("strip", 255), position("strip", 1, 1),
                colour(cell, Color.RED), zoom(cell, 4), zoom("bar", 40),
            ),
        )
        assertEquals(setOf("strip"), plan(cues).keys, "only the head not kept live moves")
    }

    // ─── The static plan (§3) ───────────────────────────────────────────────────────────────

    private fun static(
        cues: Map<Int, MoveInDarkPlanner.CueContent>,
        loop: Boolean = false,
        settings: MoveInDarkSettings = MoveInDarkSettings.NEW_STACK,
        follows: Map<Int, Long> = emptyMap(),
        fades: Map<Int, Long> = emptyMap(),
    ) = MoveInDarkPlanner.staticPlan(
        MoveInDarkPlanner.StaticInput(
            settings = settings,
            ordered = entries,
            loop = loop,
            heads = heads,
            content = { cueId -> cues[cueId] ?: MoveInDarkPlanner.CueContent(emptyList()) },
            followMs = { follows[it] },
            fadeMs = { fades[it] ?: 0L },
        ),
    ).associateBy { it.entryId }

    private val q1Lit = 1 to content(dimmer("hex", 255), position("hex", 1, 1))
    private val q2Out = 2 to content(dimmer("hex", 0))

    @Test
    fun `a head moves in the first entry it is dark in, for the cue that lights it`() {
        val plan = static(mapOf(q1Lit, q2Out, 4 to content(*lit)))
        assertEquals(listOf(MoveInDarkPlanner.StaticMove("hex", 11, setOf(AttributeFamily.POSITION))), plan.getValue(13).moves)
        assertTrue(plan.getValue(11).moves.isEmpty() && plan.getValue(12).moves.isEmpty())
        val late = static(mapOf(q1Lit, q2Out, 4 to content(cue(CueMoveInDarkMode.LATE), *lit)))
        assertEquals(12, late.getValue(13).moves.single().fromEntryId, "Late moves it in the entry before")
    }

    @Test
    fun `a head lit by the entry before moves lit, unless it stays where it is`() {
        val plan = static(mapOf(q1Lit, 2 to content(*lit)))
        assertEquals(listOf(MoveInDarkPlanner.StaticLive("hex", LiveReason.LIT_BEFORE, setOf(AttributeFamily.POSITION))), plan.getValue(11).live)
        val same = static(mapOf(q1Lit, 2 to content(dimmer("hex", 128), position("hex", 1, 1))))
        assertTrue(same.getValue(11).live.isEmpty() && same.getValue(11).moves.isEmpty(), "nothing to move")
    }

    @Test
    fun `a cue set Off or keeping a head live lists it, and a stack off with cues on Stack lists nothing`() {
        val off = static(mapOf(q1Lit, q2Out, 3 to content(cue(CueMoveInDarkMode.OFF), *lit)))
        assertEquals(listOf(MoveInDarkPlanner.StaticLive("hex", LiveReason.CUE_OFF, setOf(AttributeFamily.POSITION))), off.getValue(12).live)
        val kept = static(mapOf(q1Lit, q2Out, 3 to content(cue(CueMoveInDarkMode.STACK, live = listOf("hex")), *lit)))
        assertEquals(listOf(MoveInDarkPlanner.StaticLive("hex", LiveReason.KEPT_LIVE, setOf(AttributeFamily.POSITION))), kept.getValue(12).live)
        val stackOff = static(mapOf(q1Lit, q2Out, 3 to content(*lit)), settings = MoveInDarkSettings.OFF)
        assertTrue(stackOff.values.all { it.moves.isEmpty() && it.live.isEmpty() })
    }

    @Test
    fun `followed in sooner than the delay and the move time is too quick, and exactly that is not`() {
        // Q2 takes the head out over 500 ms; Q2 follows into Q3. Delay 1 s + move 2 s = 3 s.
        val cues = mapOf(q1Lit, q2Out, 3 to content(*lit))
        val fades = mapOf(2 to 500L)
        val quick = static(cues, follows = mapOf(11 to 3_499L), fades = fades)
        assertEquals(listOf(MoveInDarkPlanner.StaticLive("hex", LiveReason.TOO_QUICK, setOf(AttributeFamily.POSITION))), quick.getValue(12).live)
        val enough = static(cues, follows = mapOf(11 to 3_500L), fades = fades)
        assertEquals(11, enough.getValue(12).moves.single().fromEntryId)
        assertEquals(1, static(cues).getValue(12).moves.size, "a manual GO is never too quick")
    }

    @Test
    fun `the dark time sums every follow from the first dark entry, and a manual GO leaves it open`() {
        val cues = mapOf(q1Lit, q2Out, 4 to content(*lit))
        assertEquals(LiveReason.TOO_QUICK, static(cues, follows = mapOf(11 to 1_000L, 12 to 1_999L)).getValue(13).live.single().reason)
        assertEquals(11, static(cues, follows = mapOf(11 to 1_000L, 12 to 2_000L)).getValue(13).moves.single().fromEntryId)
        assertEquals(1, static(cues, follows = mapOf(12 to 100L)).getValue(13).moves.size)
    }

    @Test
    fun `the first entry of a list that does not loop moves nothing, and a looping one walks back round`() {
        val cues = mapOf(1 to content(*lit), 3 to content(dimmer("hex", 255), position("hex", 3, 3)), 4 to content(dimmer("hex", 0)))
        assertTrue(static(cues).getValue(10).let { it.moves.isEmpty() && it.live.isEmpty() })
        assertEquals(13, static(cues, loop = true).getValue(10).moves.single().fromEntryId)
    }

    @Test
    fun `on a loop the walk back round to the target takes the first dark entry's fade off`() {
        // Q1 lights it; Q2 takes it out over 500 ms; Q2 to Q4 follow 1 s each back round to Q1.
        val cues = mapOf(1 to content(*lit), q2Out, 3 to content(), 4 to content())
        val follows = mapOf(11 to 1_000L, 12 to 1_000L, 13 to 1_000L)
        val plan = static(cues, loop = true, follows = follows, fades = mapOf(2 to 500L))
        assertEquals(listOf(MoveInDarkPlanner.StaticLive("hex", LiveReason.TOO_QUICK, setOf(AttributeFamily.POSITION))), plan.getValue(10).live, "3 s less the 500 ms fade")
        val enough = static(cues, loop = true, follows = follows + (13 to 1_500L), fades = mapOf(2 to 500L))
        assertEquals(11, enough.getValue(10).moves.single().fromEntryId)
    }
}

