package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.models.SPEED_MASTER_FAMILIES
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Holds the backend's words to `docs/glossary.md` (terminology plan D17): every `done` or `screen`
 * row's `fx:`, `family:`, `masterFamily:` and `doc:` locators must give the row's word, and every
 * built-in FX must have a row. The frontend's locators are `glossary.test.ts`'s.
 *
 * It also pins the speed-master family vocabulary (busking-view plan D7): a master's `family` is one
 * of the FX library's `family` strings, so apply-time routing (`master.family == fx.family`)
 * compares like with like. `family` is a free string in script frontmatter, so a typo'd or new
 * one would otherwise route nowhere without anyone deciding that.
 *
 * Metadata only: each `.fx.kts` frontmatter is parsed with [FxFileLoader.parseFxFile], never
 * compiled. `tasks.test` declares the glossary as an input, so editing it alone re-runs this.
 */
class GlossaryVocabularyTest {

    private data class Row(val section: String, val idea: String, val word: String, val status: String, val keys: List<String>) {
        override fun toString() = "glossary row \"$idea\" ($word) under $section"
    }

    private val builtIns: List<FxFileMetadata> by lazy {
        val loader = FxFileLoader::class.java.classLoader
        val index = loader.getResource("fx/index.txt")!!.readText().lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(index.size >= 28, "fx/index.txt should list at least 28 effects, got ${index.size}")

        index.map { path -> FxFileLoader.parseFxFile(loader.getResource("fx/$path")!!.readText()).first }
    }

    private fun builtInFamilies(): Set<String> = builtIns.map { it.family }.toSet()

    private val rows: List<Row> by lazy { parseGlossary(File("docs/glossary.md").readText()) }

    @Test
    fun `every row has a known status, and a key exactly when it is done or on screen`() {
        assertTrue(rows.size > 50, "parsed only ${rows.size} glossary rows; has the table layout changed?")
        for (row in rows) {
            assertTrue(row.status in STATUSES, "$row: status '${row.status}' is none of $STATUSES")
            if (row.status in KEYED_STATUSES) {
                assertTrue(row.keys.isNotEmpty(), "$row is ${row.status} but names no key")
            } else {
                assertTrue(row.keys.isEmpty(), "$row is planned but has keys ${row.keys}")
            }
            for (key in row.keys) {
                val prefix = locatorPrefix(key)
                assertTrue(
                    prefix in BACKEND_PREFIXES || prefix in FRONTEND_PREFIXES,
                    "$row: key '$key' has a prefix neither glossary test reads",
                )
            }
        }
    }

    @Test
    fun `every done or screen row's backend keys give its word`() {
        val names = builtIns.associate { it.id to it.name }
        val families = builtInFamilies()
        for (row in rows.filter { it.status in KEYED_STATUSES }) {
            for (key in row.keys) {
                val target = key.substringAfter(':')
                when (locatorPrefix(key)) {
                    "fx" -> {
                        val name = names[target] ?: fail("$row: $key names no built-in FX")
                        assertEquals(row.word, name, "$row: $key is called '$name', expected '${row.word}'")
                    }
                    "family" -> {
                        assertTrue(target in families, "$row: $key is no family the built-in FX declare")
                        assertTrue(target.equals(row.word, ignoreCase = true), "$row: $key is '$target', expected '${row.word}'")
                    }
                    "masterFamily" -> {
                        assertTrue(target in SPEED_MASTER_FAMILIES, "$row: $key is not in SPEED_MASTER_FAMILIES")
                        assertTrue(target.equals(row.word, ignoreCase = true), "$row: $key is '$target', expected '${row.word}'")
                    }
                    "doc" -> {
                        val doc = File(target)
                        assertTrue(doc.isFile, "$row: $key names no file")
                        val word = Regex("\\b${Regex.escape(row.word)}\\b", RegexOption.IGNORE_CASE)
                        assertTrue(word.containsMatchIn(doc.readText()), "$row: $key never says '${row.word}'")
                    }
                }
            }
        }
    }

    @Test
    fun `every built-in FX has a glossary row`() {
        val text = File("docs/glossary.md").readText()
        val missing = builtIns.map { it.id }.filter { "fx:$it`" !in text && "`$it`" !in text }
        assertTrue(missing.isEmpty(), "built-in FX $missing appear in no glossary row, as `fx:<id>` or as `<id>` under Was")
    }

    @Test
    fun `the shipped FX library's families are exactly the known set`() {
        assertEquals(
            setOf("intensity", "colour", "position", "beam", STEPS_CATEGORY),
            builtInFamilies(),
            "the built-in family vocabulary moved. If you added a family, decide whether it " +
                "routes to a family-tagged speed master (add it to SPEED_MASTER_FAMILIES and this " +
                "set) or lands on master 1 like controls (add it to this set only) — " +
                "see D7 in docs/plans/completed/busking-view-plan.md",
        )
    }

    @Test
    fun `every routable master family names a real FX family`() {
        val families = builtInFamilies()
        assertTrue(
            SPEED_MASTER_FAMILIES.all { it in families },
            "SPEED_MASTER_FAMILIES ${SPEED_MASTER_FAMILIES - families} name no shipped FX " +
                "family — routing on them could never match an apply",
        )
    }

    @Test
    fun `controls is not a routable master family`() {
        // `controls` is the synthetic family for from-state settings effects (FxSpawner,
        // categoryFromAttributeName), and a settings slider has no tempo. It routes to master 1 via
        // a null `speedMasterUuid`, which is what an unmatched family is defined to do.
        assertTrue("controls" !in SPEED_MASTER_FAMILIES)
    }

    @Test
    fun `the Steps FX route by what they step, so their registry family is no master family`() {
        // Palette Steps and Slot Steps register under the Steps family, which names no attribute
        // family: a stored one carries the family it steps (effects plan §3.6) — Slot Steps' Beam,
        // Palette Steps' its palettes' — and that family, a master family, is what the picker and the
        // MCP tools route its master by.
        assertTrue(STEPS_CATEGORY !in SPEED_MASTER_FAMILIES)
    }

    private companion object {
        val GLOSSARY_COLUMNS = listOf("Idea", "Word", "Status", "Key")

        /** `screen`: the screen says the word, the code still says *Was*. Both name keys. */
        val KEYED_STATUSES = setOf("done", "screen")
        val STATUSES = KEYED_STATUSES + "planned"

        val BACKEND_PREFIXES = setOf("fx", "family", "masterFamily", "doc")

        /** `glossary.test.ts` reads these; both tests know every prefix so a typo fails both. */
        val FRONTEND_PREFIXES = setOf("src", "ORDER_LABELS", "FX_FAMILY_INFO", "BLEND_MODE_OPTIONS", "ELEMENT_MODE_OPTIONS")

        fun locatorPrefix(key: String): String = key.substringBefore(':').substringBefore('.')

        /**
         * Every row of every table under a `##` section. Such a table must have Idea, Word, Status
         * and Key columns, so a mistyped header fails rather than dropping its rows unchecked.
         */
        fun parseGlossary(text: String): List<Row> {
            val rows = mutableListOf<Row>()
            var section = ""
            var columns: List<String>? = null
            for (line in text.lines()) {
                if (line.startsWith("## ")) section = line.removePrefix("## ").trim()
                if (!line.startsWith("|")) {
                    columns = null
                    continue
                }
                val cells = splitRow(line)
                if (columns == null) {
                    val glossaryTable = GLOSSARY_COLUMNS.all(cells::contains)
                    check(glossaryTable || section.isEmpty()) {
                        "the table under \"$section\" has columns $cells; a glossary table needs $GLOSSARY_COLUMNS"
                    }
                    columns = if (glossaryTable) cells else emptyList()
                    continue
                }
                val header = columns
                if (header.isEmpty() || cells.all { it.matches(Regex("-+")) }) continue
                val cell = { name: String -> cells.getOrElse(header.indexOf(name)) { "" } }
                val keys = cell("Key").split(Regex("[\\s,]+")).map { it.trim('`') }.filter { it.isNotEmpty() }
                rows += Row(section, cell("Idea"), cell("Word"), cell("Status"), keys)
            }
            return rows
        }

        /** A table row's cells, split on pipes a backslash does not escape. */
        fun splitRow(line: String): List<String> =
            line.trim().removePrefix("|").removeSuffix("|")
                .split(Regex("(?<!\\\\)\\|"))
                .map { it.trim().replace("\\|", "|") }
    }
}
