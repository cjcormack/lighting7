package uk.me.cormack.lighting7.fx

import org.junit.Test
import uk.me.cormack.lighting7.fixture.AttributeCategory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * *Over the show* in the resolver (busk-stack plan D7, D8; §3 "Replaces"): a replacing stack's rows are
 * the floor of the keys they set, blended in by their fade weight over whatever composes under them,
 * and the FX under them weighed by what they leave.
 *
 * Worked through Carabossy's entrance (the page's §3 table): the main stack's Q14 holds the front
 * wash at 80 % and the cyc deep blue; Carabossy's Q1 sets the wash to 0 and the cyc green.
 */
class CueAssignmentResolverReplacesTest {
    private val resolver = CueAssignmentResolver()

    private val show = StackRanks.Place(StackRanks.Band.SHOW, 1)
    private val showLater = StackRanks.Place(StackRanks.Band.SHOW, 2)
    private val busk = StackRanks.Place(StackRanks.Band.BUSK, 1)
    private val buskLater = StackRanks.Place(StackRanks.Band.BUSK, 2)
    private val buskTop = StackRanks.Place(StackRanks.Band.BUSK, 3)

    private fun dimmer(cueId: Int, priority: Int, level: Int, weight: Double = 1.0, group: Int? = null, key: String = "wash") =
        CueAssignmentResolver.Assignment(
            cueId = cueId, priority = priority, fadeWeight = weight, targetKey = key, targetIsGroup = false,
            attribute = "dimmer", category = AttributeCategory.DIMMER,
            value = CueAssignmentResolver.AttributeValue.Slider(level.toUByte()), replaceGroup = group,
        )

    private fun pan(cueId: Int, priority: Int, value: Int, weight: Double = 1.0, group: Int? = null) =
        CueAssignmentResolver.Assignment(
            cueId = cueId, priority = priority, fadeWeight = weight, targetKey = "cyc", targetIsGroup = false,
            attribute = "pan", category = AttributeCategory.PAN,
            value = CueAssignmentResolver.AttributeValue.Slider(value.toUByte()), replaceGroup = group,
        )

    /** What `StackRanks.stackAt` would answer here: group 9 is the busk band's rank-1 stack, group 11 its rank 3. */
    private val stackOf: (Int) -> Int? = { p ->
        when (p / STACK_PRIORITY_BAND) {
            stackPriority(busk, 0) / STACK_PRIORITY_BAND -> 9
            stackPriority(buskTop, 0) / STACK_PRIORITY_BAND -> 11
            else -> null
        }
    }

    private fun composed(rows: List<CueAssignmentResolver.Assignment>) =
        resolver.composeFull(resolver.cook(rows), CueAssignmentResolver.FadeWeights.NONE)

    private fun level(rows: List<CueAssignmentResolver.Assignment>, key: String = "wash", attribute: String = "dimmer"): Int =
        (composed(rows).index.getValue(key).getValue(attribute) as CueAssignmentResolver.AttributeValue.Slider).value.toInt()

    // ─── The page's table, Adds and Replaces ─────────────────────────────────────────────────

    @Test
    fun `Adds leaves intensity highest-takes-precedence, so a busk stack's 0 never darkens the wash`() {
        val rows = listOf(dimmer(14, stackPriority(show, 0), 204), dimmer(1, stackPriority(busk, 0), 0))
        assertEquals(204, level(rows))
        assertTrue(composed(rows).replaced.isEmpty())
    }

    @Test
    fun `Replaces takes the wash to the 0 its cue sets`() {
        assertEquals(0, level(listOf(dimmer(14, stackPriority(show, 0), 204), dimmer(1, stackPriority(busk, 0), 0, group = 9))))
    }

    @Test
    fun `a key the replacing cue does not set keeps the show's value`() {
        val rows = listOf(
            dimmer(14, stackPriority(show, 0), 204),
            pan(14, stackPriority(show, 0), 40),
            dimmer(1, stackPriority(busk, 0), 0, group = 9),
        )
        assertEquals(40, level(rows, key = "cyc", attribute = "pan"))
        assertEquals(null, composed(rows).replaced["cyc"], "nothing replaces the cyc")
    }

    @Test
    fun `on an LTP key both win over the show, whichever stack was made first`() {
        assertEquals(200, level(listOf(pan(14, stackPriority(show, 0), 40), pan(1, stackPriority(busk, 0), 200)), "cyc", "pan"))
        assertEquals(200, level(listOf(pan(14, stackPriority(show, 0), 40), pan(1, stackPriority(busk, 0), 200, group = 9)), "cyc", "pan"))
    }

    @Test
    fun `a release fades back from 0 to the show's value with no jump`() {
        val show14 = dimmer(14, stackPriority(show, 0), 200)
        val levels = listOf(1.0, 0.75, 0.5, 0.25, 0.0).map { w -> level(listOf(show14, dimmer(1, stackPriority(busk, 0), 0, w, group = 9))) }
        assertEquals(listOf(0, 50, 100, 150, 200), levels)
        assertEquals(200, level(listOf(show14)), "and the release's end, its rows gone, is where the fade arrived")
    }

    @Test
    fun `a follow between two replacing cues that both set 0 stays at 0`() {
        for (step in 0..4) {
            val e = step / 4.0
            val rows = listOf(
                dimmer(14, stackPriority(show, 0), 204),
                dimmer(1, stackPriority(busk, 0), 0, 1 - e, group = 9),
                dimmer(2, stackPriority(busk, 1), 0, e, group = 9),
            )
            assertEquals(0, level(rows), "at $e through the follow")
        }
    }

    @Test
    fun `a release of a stack caught mid-crossfade goes from 0 to the show's value`() {
        // Q1 → Q2 at 0.4, both setting 0, then released: both fade from where they stood, together.
        val levels = listOf(1.0, 0.5, 0.0).map { r ->
            level(
                listOf(
                    dimmer(14, stackPriority(show, 0), 200),
                    dimmer(1, stackPriority(busk, 0), 0, 0.6 * r, group = 9),
                    dimmer(2, stackPriority(busk, 1), 0, 0.4 * r, group = 9),
                ),
            )
        }
        assertEquals(listOf(0, 100, 200), levels)
    }

    @Test
    fun `two show stacks under one replaced dimmer fade back to their HTP value`() {
        val under = listOf(dimmer(14, stackPriority(show, 0), 160), dimmer(30, stackPriority(showLater, 0), 200))
        assertEquals(0, level(under + dimmer(1, stackPriority(busk, 0), 0, 1.0, group = 9)))
        assertEquals(100, level(under + dimmer(1, stackPriority(busk, 0), 0, 0.5, group = 9)), "half way from 0 to their 200")
        assertEquals(200, level(under), "and their own HTP at the end")
    }

    @Test
    fun `a show cue's fixture row does not drop a replacing stack's group row`() {
        val rows = listOf(
            dimmer(14, stackPriority(show, 0), 204),
            dimmer(1, stackPriority(busk, 0), 0, group = 9).copy(targetIsGroup = true),
        )
        assertEquals(0, level(rows), "Carabossy's group row is the floor of the wash")
        assertTrue(composed(rows).replaced.isNotEmpty())
    }

    // ─── What sits above ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a loose press on the replacing cue keeps the wash at 0`() {
        val loose = CueHolds.loosePriority(1)
        assertEquals(0, level(listOf(dimmer(14, stackPriority(show, 0), 204), dimmer(1, loose, 0, group = ownReplaceGroup(1)))))
    }

    @Test
    fun `a loose press above a replacing cue mid-fade composes over the floor by HTP`() {
        val rows = listOf(
            dimmer(14, stackPriority(show, 0), 200),
            dimmer(1, stackPriority(busk, 0), 0, 0.5, group = 9),
            dimmer(50, CueHolds.loosePriority(1), 60),
        )
        assertEquals(100, level(rows), "the floor at 100 beats the press's 60")
        assertEquals(150, level(rows.dropLast(1) + dimmer(50, CueHolds.loosePriority(1), 150)), "and a press above it lifts it")
    }

    @Test
    fun `an Adds busk stack GO'd after a replacing one composes over it`() {
        val rows = listOf(
            dimmer(14, stackPriority(show, 0), 204),
            dimmer(1, stackPriority(busk, 0), 0, group = 9),
            dimmer(60, stackPriority(buskLater, 0), 120),
        )
        assertEquals(120, level(rows), "the song lifts the wash Carabossy took to 0")
    }

    @Test
    fun `a non-replacing stack between two replacing ones shows over the lower and fades out with the higher`() {
        val a = dimmer(1, stackPriority(busk, 0), 0, group = 9)
        val b = dimmer(2, stackPriority(buskLater, 0), 100)
        val show14 = dimmer(14, stackPriority(show, 0), 204)
        // C fully in: 0. C fading out with A at full: B over A's 0, blended back as C goes.
        assertEquals(0, level(listOf(show14, a, b, dimmer(3, stackPriority(buskTop, 0), 0, 1.0, group = 11))))
        assertEquals(50, level(listOf(show14, a, b, dimmer(3, stackPriority(buskTop, 0), 0, 0.5, group = 11))))
        assertEquals(100, level(listOf(show14, a, b)), "B over A once C is gone")

        // B's FX, under C alone: held back by C's weight, and A does not hold it.
        val groups = composed(listOf(show14, a, b, dimmer(3, stackPriority(buskTop, 0), 0, 0.5, group = 11))).replaced
            .getValue("wash").getValue("dimmer")
        assertEquals(0.5, CueAssignmentResolver.replacedWeight(groups, stackPriority(buskLater, 0), 2, stackOf), 1e-9)
        assertEquals(0.0, CueAssignmentResolver.replacedWeight(groups, stackPriority(show, 0), 14, stackOf), 1e-9, "the show's FX under both")
    }

    // ─── FX held back ────────────────────────────────────────────────────────────────────────

    @Test
    fun `an FX under a replacing stack is held back by its fade weight, and a programmer FX is not`() {
        fun groups(w: Double) = composed(listOf(dimmer(14, stackPriority(show, 0), 204), dimmer(1, stackPriority(busk, 0), 0, w, group = 9)))
            .replaced.getValue("wash").getValue("dimmer")
        assertEquals(0.0, CueAssignmentResolver.replacedWeight(groups(1.0), stackPriority(show, 0), 14, stackOf), "the show's chase, fully held")
        assertEquals(0.75, CueAssignmentResolver.replacedWeight(groups(0.25), stackPriority(show, 0), 14, stackOf), 1e-9, "handing back as it fades")
        assertEquals(0.0, CueAssignmentResolver.replacedWeight(groups(1.0), 0, null, stackOf), "a manual FX at priority 0 too")
        assertEquals(1.0, CueAssignmentResolver.replacedWeight(groups(1.0), FxEngine.PROGRAMMER_FX_PRIORITY_BASE + 5, null, stackOf), "the programmer's runs")
        assertEquals(1.0, CueAssignmentResolver.replacedWeight(groups(1.0), stackPriority(busk, 0), 1, stackOf), "and so do its own")
        assertEquals(1.0, CueAssignmentResolver.replacedWeight(groups(1.0), stackPriority(busk, 2), 7, stackOf), "and its stack's, a leaving cue's")
        assertEquals(1.0, CueAssignmentResolver.replacedWeight(groups(1.0), CueHolds.loosePriority(3), 40, stackOf), "and a press above it")
    }
}
