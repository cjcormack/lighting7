package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.models.DaoPromptBook
import uk.me.cormack.lighting7.models.DaoPromptBooks
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.promptbook.ScriptTextStore
import uk.me.cormack.lighting7.testsupport.HeldExecutor
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Claude reading and quoting the script (prompt-book navigation plan D6): `search_prompt_book`,
 * `get_prompt_book`'s text fields, and an `at` given as `text`, over `typed-script.pdf` (see
 * `ScriptTextExtractorTest` for its pages), in each state the index can be in.
 */
class McpPromptBookTextTest : RouteIntegrationTest() {

    private val storeRoot: Path = Files.createTempDirectory("mcp-prompt-scripts-")
    override val extraConfig = listOf("promptBooks.scriptStoreRoot" to storeRoot.toString())

    private val builds = HeldExecutor()
    private val tools by lazy { SetupTools(state, AiTools(state)) }
    private val hash = "f".repeat(64)

    @After
    fun removeStore() {
        storeRoot.toFile().deleteRecursively()
    }

    private fun call(name: String, arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool(name, Json.parseToJsonElement(arguments).jsonObject) }

    private fun ToolExecutionResult.json(): JsonObject = Json.parseToJsonElement(result).jsonObject

    private fun ToolExecutionResult.problems(): List<String> =
        json()["problems"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    /** A three-page book; with [pdf] its script is in the store, and with [read] its text too. */
    private fun book(pdf: Boolean = true, read: Boolean = true) {
        state.scriptText = ScriptTextStore(state::promptScriptPath, builds)
        val uuid = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            DaoPromptBook.new {
                this.project = project
                scriptHash = hash
                scriptFileName = "hamlet.pdf"
                pageCount = 3
            }
            project.uuid.toString()
        }
        if (pdf) {
            val path = state.promptScriptPath(uuid, hash)
            Files.createDirectories(path.parent)
            Files.copy(checkNotNull(javaClass.getResourceAsStream("/promptBook/typed-script.pdf")), path)
        }
        if (pdf && read) {
            state.scriptText.request(uuid, hash)
            builds.runAll()
        }
    }

    private fun anchorsAndNotes(): Pair<Int, Int> = transaction(state.database) {
        val book = DaoPromptBook.find { DaoPromptBooks.project eq projectId }.single()
        book.anchors.count().toInt() to book.annotations.count().toInt()
    }

    @Test
    fun `get_prompt_book answers the text, the scans, the outline and a page's lines`() {
        book()
        val read = call("get_prompt_book", """{"pages":[2]}""").json()
        assertEquals("READY", read["textState"]!!.jsonPrimitive.content)
        assertTrue(read["hasText"]!!.jsonPrimitive.boolean)
        assertEquals(0, read["scannedPages"]!!.jsonArray.size)
        val outline = read["pdfOutline"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Act One", "Scene 2", "Scene 3"), outline.map { it["title"]!!.jsonPrimitive.content })
        assertEquals(listOf(1, 2, 3), outline.map { it["pdfPage"]!!.jsonPrimitive.int }, "pages count from 1")
        val page = read["pages"]!!.jsonArray.single().jsonObject
        assertEquals(2, page["pdfPage"]!!.jsonPrimitive.int)
        val lines = page["lines"]!!.jsonArray.map { it.jsonObject }
        assertEquals("HAMLET: To be, or not to be, that is the", lines[1]["text"]!!.jsonPrimitive.content)
        assertTrue(lines.zipWithNext().all { (a, b) -> a["y"]!!.jsonPrimitive.double < b["y"]!!.jsonPrimitive.double })

        assertFalse(call("get_prompt_book", """{"pages":[4]}""").success)
    }

    @Test
    fun `search_prompt_book finds a line, across a break, on one page or all`() {
        book()
        val all = call("search_prompt_book", """{"query":"to be, or NOT to be"}""").json()
        assertEquals(2, all["total"]!!.jsonPrimitive.int)
        val hits = all["hits"]!!.jsonArray.map { it.jsonObject }
        assertTrue(hits.all { it["pdfPage"]!!.jsonPrimitive.int == 2 })

        val across = call("search_prompt_book", """{"query":"that is the question"}""").json()["hits"]!!.jsonArray.single().jsonObject
        assertEquals(2, across["lines"]!!.jsonArray.size, "one box per line it touches")

        val onPage = call("search_prompt_book", """{"query":"ophelia","pdfPage":3}""").json()
        assertEquals(2, onPage["total"]!!.jsonPrimitive.int)
        assertEquals(1, call("search_prompt_book", """{"query":"ophelia","limit":1}""").json()["hits"]!!.jsonArray.size)

        val none = call("search_prompt_book", """{"query":"something is rotten"}""").json()
        assertEquals(0, none["total"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a quoted at lands on its line's real place`() {
        book()
        val built = call("build_cue_stack", """{"stackName":"Show","cues":[{"number":"1","name":"Guards","at":{"text":"Who’s there?"}}]}""")
        assertTrue(built.success, built.result)
        val marked = call(
            "mark_up_prompt_book",
            """{"notes":[{"kind":"STRIKETHROUGH","at":{"text":"that is the question"}},{"text":"Standby","at":{"text":"to be, or not to be","occurrence":2}}]}""",
        )
        assertTrue(marked.success, marked.result)

        transaction(state.database) {
            val book = DaoPromptBook.find { DaoPromptBooks.project eq projectId }.single()
            val anchor = book.anchors.single().region.single()
            assertEquals(0, anchor.page)
            // Page 1's third line has its baseline 40 pt below the first's, at 680 pt of 792.
            assertEquals((792.0 - 680) / 792, anchor.y, 0.02)
            assertTrue(anchor.x > 72.0 / 612, "the quote starts after 'BERNARDO: '")
            val notes = book.annotations.toList()
            assertEquals(2, notes.single { it.kind == "STRIKETHROUGH" }.region.size, "a quote across a break marks both lines")
            val standby = notes.single { it.kind == "NOTE" }.region.single()
            assertEquals(1, standby.page)
            // The second 'to be' is page 2's last line, its baseline at 400 pt.
            assertEquals((792.0 - 400) / 792, standby.y, 0.02)
        }
    }

    @Test
    fun `a quote found nowhere, or in several places, is refused naming where, and nothing is written`() {
        book()
        val missing = call("mark_up_prompt_book", """{"notes":[{"text":"x","at":{"text":"something is rotten"}}]}""")
        assertFalse(missing.success)
        assertTrue(missing.problems().single().contains("is not in the script's text"), missing.result)

        val ambiguous = call("mark_up_prompt_book", """{"notes":[{"text":"x","at":{"text":"to be, or not to be"}}]}""")
        val twice = ambiguous.problems().single()
        assertTrue("found 2 times" in twice && twice.split("pdfPage 2 y").size == 3, twice)
        assertTrue("give pdfPage or occurrence" in twice, twice)

        val onPage = call("mark_up_prompt_book", """{"notes":[{"text":"x","at":{"text":"OPHELIA","pdfPage":3}}]}""")
        val onPageProblem = onPage.problems().single()
        assertTrue("found 2 times on pdfPage 3" in onPageProblem && "give occurrence" in onPageProblem, onPageProblem)

        val chosen = call("mark_up_prompt_book", """{"notes":[{"text":"x","at":{"text":"OPHELIA","pdfPage":3,"occurrence":2}}]}""")
        assertTrue(chosen.success, chosen.result)
        assertEquals(0 to 1, anchorsAndNotes())

        val tooFar = call("mark_up_prompt_book", """{"notes":[{"text":"x","at":{"text":"OPHELIA","pdfPage":3,"occurrence":3}}]}""")
        assertTrue(tooFar.problems().single().contains("no occurrence 3"), tooFar.result)

        val both = call("mark_up_prompt_book", """{"notes":[{"text":"x","at":{"text":"OPHELIA","y":0.3}}]}""")
        assertTrue(both.problems().single().contains("not both"), both.result)
        assertEquals(0 to 1, anchorsAndNotes(), "no refused call wrote anything")
    }

    @Test
    fun `while the text is being read, or the PDF is missing, a quote is refused and a search gives no count`() {
        book(read = false)
        val building = call("get_prompt_book", "{}").json()
        assertEquals("BUILDING", building["textState"]!!.jsonPrimitive.content)
        assertNull(building["hasText"], "never answered as no text")
        val search = call("search_prompt_book", """{"query":"to be"}""").json()
        assertEquals("BUILDING", search["textState"]!!.jsonPrimitive.content)
        assertNull(search["total"])
        val quoted = call("build_cue_stack", """{"stackName":"Show","cues":[{"number":"1","name":"Q1","at":{"text":"Who's there?"}}]}""")
        assertTrue(quoted.problems().single().contains("still reading the script's text"), quoted.result)
        assertEquals(0 to 0, anchorsAndNotes())
        // A place by page and y does not need the text.
        assertTrue(call("mark_up_prompt_book", """{"notes":[{"text":"x","at":{"pdfPage":1,"y":0.3}}]}""").success)

        builds.runAll()
        assertTrue(call("search_prompt_book", """{"query":"to be"}""").json()["total"]!!.jsonPrimitive.int > 0)
    }

    @Test
    fun `a book whose PDF is not on this desk says so`() {
        book(pdf = false)
        assertEquals("SCRIPT_MISSING", call("get_prompt_book", "{}").json()["textState"]!!.jsonPrimitive.content)
        val search = call("search_prompt_book", """{"query":"to be"}""").json()
        assertEquals("SCRIPT_MISSING", search["textState"]!!.jsonPrimitive.content)
        assertNull(search["total"])
        val quoted = call("mark_up_prompt_book", """{"notes":[{"text":"x","at":{"text":"to be"}}]}""")
        assertTrue(quoted.problems().single().contains("not on this desk"), quoted.result)
    }
}
