package uk.me.cormack.lighting7.promptbook

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `src/test/resources/promptBook/textSearch.fixture.json` is read here and by the Prompt Book's find
 * (prompt-book navigation plan P1), so the desk and the view find the same places.
 */
class ScriptTextMatcherTest {

    private val lenient = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Fold(val `in`: String, val out: String)

    @Serializable
    private data class Span(val line: Int, val start: Int, val end: Int)

    @Serializable
    private data class Hit(val page: Int, val spans: List<Span>)

    @Serializable
    private data class Case(val name: String, val pages: List<List<String>>, val query: String, val page: Int? = null, val hits: List<Hit>)

    @Serializable
    private data class Vector(val fold: List<Fold>, val search: List<Case>)

    private val vector by lazy {
        val text = checkNotNull(javaClass.getResource("/promptBook/textSearch.fixture.json")) { "the shared vector is missing" }.readText()
        lenient.decodeFromString<Vector>(text)
    }

    @Test
    fun `the shared vector's folds`() {
        for (case in vector.fold) assertEquals(case.out, ScriptTextMatcher.fold(case.`in`), case.`in`)
    }

    @Test
    fun `the shared vector's searches`() {
        for (case in vector.search) {
            val found = ScriptTextMatcher.search(case.pages, case.query, case.page).map { hit ->
                Hit(hit.page, hit.spans.map { Span(it.line, it.start, it.end) })
            }
            assertEquals(case.hits, found, case.name)
        }
    }

    @Test
    fun `a hit is boxed by its own characters on each line it touches`() {
        val index = ScriptTextIndex(
            version = ScriptTextIndex.VERSION,
            scriptHash = "a".repeat(64),
            pageCount = 1,
            pages = listOf(
                ScriptTextPage(
                    page = 0,
                    scanned = false,
                    lines = listOf(
                        ScriptTextLine("that is the", 0.1, 0.2, 0.33, 0.02, (0..11).map { 0.1 + it * 0.03 }),
                        ScriptTextLine("question.", 0.1, 0.23, 0.27, 0.02, (0..9).map { 0.1 + it * 0.03 }),
                    ),
                ),
            ),
        )
        val hit = ScriptTextMatcher.search(index, "the question").single()
        val rects = ScriptTextMatcher.rects(index, hit)
        assertEquals(2, rects.size)
        assertEquals(0.1 + 8 * 0.03, rects[0].x, 1e-9)
        assertEquals(3 * 0.03, rects[0].w, 1e-9)
        assertEquals(0.2, rects[0].y)
        assertEquals(0.1, rects[1].x, 1e-9)
        assertEquals(8 * 0.03, rects[1].w, 1e-9)
        assertEquals(0.23, rects[1].y)
    }
}
