package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import org.junit.Test
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.assertEquals

/**
 * The startup backfill exists because `renumberAutoCues` otherwise only fires on a mutation, so
 * cues that predate auto-numbering would sit blank until something happened to touch their stack.
 */
class BackfillAutoCueNumbersTest : RouteIntegrationTest() {

    private fun seedStack(name: String, cues: List<Triple<String, String?, String>>): Int =
        transaction(state.database) {
            val stack = DaoCueStack.new {
                this.project = DaoProject.findById(projectId)!!
                this.name = name
                palette = emptyList()
                loop = false
                type = CueStackType.STACK.name
                sortOrder = 0
            }
            cues.forEachIndexed { index, (cueName, number, type) ->
                DaoCue.new {
                    this.name = cueName
                    this.project = DaoProject.findById(projectId)!!
                    palette = emptyList()
                    cueStack = stack
                    this.sortOrder = index
                    cueNumber = number
                    cueType = type
                    // Deliberately false even where a number is set — this is what pre-feature
                    // rows look like: every number is an operator's, none are derived.
                    cueNumberAuto = false
                }
            }
            stack.id.value
        }

    private fun numbersIn(stackId: Int): List<Triple<String, String?, Boolean>> =
        transaction(state.database) {
            DaoCue.find { DaoCues.cueStack eq stackId }
                .orderBy(DaoCues.sortOrder to SortOrder.ASC)
                .map { Triple(it.name, it.cueNumber, it.cueNumberAuto) }
        }

    // Owns its own transactions (one per stack), so it is called outside any enclosing block.
    private fun runBackfill() = backfillAutoCueNumbers(state.database)

    @Test
    fun `blank cues are numbered from position, explicit numbers untouched`() {
        val stackId = seedStack(
            "Act 1",
            listOf(
                Triple("Opening", "S1-1", CueType.STANDARD.name),
                Triple("Ballroom", "S1-2", CueType.STANDARD.name),
                Triple("Glow", null, CueType.STANDARD.name),
                Triple("Glow red", null, CueType.STANDARD.name),
            ),
        )

        runBackfill()

        assertEquals(
            listOf(
                Triple("Opening", "S1-1", false),
                Triple("Ballroom", "S1-2", false),
                Triple("Glow", "S1-3", true),
                Triple("Glow red", "S1-4", true),
            ),
            numbersIn(stackId),
        )
    }

    @Test
    fun `markers are left unnumbered and do not break the run`() {
        val stackId = seedStack(
            "With separator",
            listOf(
                Triple("Opening", "1", CueType.STANDARD.name),
                Triple("Interval", null, CueType.MARKER.name),
                Triple("After", null, CueType.STANDARD.name),
            ),
        )

        runBackfill()

        assertEquals(
            listOf(
                Triple("Opening", "1", false),
                Triple("Interval", null, false),
                Triple("After", "2", true),
            ),
            numbersIn(stackId),
        )
    }

    @Test
    fun `an entirely unnumbered stack counts from one`() {
        val stackId = seedStack(
            "Unsorted",
            listOf(
                Triple("Haze", null, CueType.STANDARD.name),
                Triple("Lasers", null, CueType.STANDARD.name),
                Triple("Mirror ball", null, CueType.STANDARD.name),
            ),
        )

        runBackfill()

        assertEquals(
            listOf(
                Triple("Haze", "1", true),
                Triple("Lasers", "2", true),
                Triple("Mirror ball", "3", true),
            ),
            numbersIn(stackId),
        )
    }

    @Test
    fun `running twice changes nothing`() {
        val stackId = seedStack(
            "Idempotent",
            listOf(
                Triple("Opening", "S1-1", CueType.STANDARD.name),
                Triple("Glow", null, CueType.STANDARD.name),
            ),
        )

        runBackfill()
        val afterFirst = numbersIn(stackId)
        runBackfill()

        assertEquals(afterFirst, numbersIn(stackId))
        assertEquals(
            listOf(Triple("Opening", "S1-1", false), Triple("Glow", "S1-2", true)),
            afterFirst,
        )
    }

    @Test
    fun `an empty-string number is treated as blank, not as a claimed value`() {
        val stackId = seedStack(
            "Empty string",
            listOf(
                Triple("Opening", "4", CueType.STANDARD.name),
                Triple("Blankish", "", CueType.STANDARD.name),
            ),
        )

        runBackfill()

        assertEquals(
            listOf(Triple("Opening", "4", false), Triple("Blankish", "5", true)),
            numbersIn(stackId),
        )
    }
}
