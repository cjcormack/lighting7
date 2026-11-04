package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `src/test/resources/promptBook/followHeads.fixture.json` is read here and by
 * `frontend/src/lib/promptBook/followers.test.ts`, so the Run view and the book agree who follows whom.
 */
class FollowHeadsTest {

    private val lenient = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Row(val id: Int, val marker: Boolean = false, val autoAdvance: Boolean = false, val delayMs: Long? = null)

    @Serializable
    private data class Case(val name: String, val rows: List<Row>, val heads: Map<Int, Int>)

    @Serializable
    private data class Vector(val cases: List<Case>)

    @Test
    fun `the shared vector`() {
        val text = checkNotNull(javaClass.getResource("/promptBook/followHeads.fixture.json")) { "the shared vector is missing" }.readText()
        for (case in lenient.decodeFromString<Vector>(text).cases) {
            val rows = case.rows.map { FollowRow(it.id, it.marker, it.autoAdvance && it.delayMs != null) }
            assertEquals(case.heads, followHeadsIn(rows), case.name)
        }
    }
}
