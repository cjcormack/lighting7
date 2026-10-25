package uk.me.cormack.lighting7.fx

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Holds `docs/fx-engineering.md`'s FX tables to the built-in FX's frontmatter: every built-in has
 * exactly one row, under the `###` heading of its family, listing exactly its parameters in the
 * frontmatter's order. A table is one whose header starts `| FX |` and has a Parameters column.
 *
 * Metadata only, as in [GlossaryVocabularyTest]: frontmatter is parsed, never compiled.
 */
class FxTablesDocTest {

    private data class DocRow(val id: String, val heading: String, val parameters: List<String>, val line: Int)

    private val builtIns: List<FxFileMetadata> by lazy {
        val loader = FxFileLoader::class.java.classLoader
        loader.getResource("fx/index.txt")!!.readText().lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { path -> FxFileLoader.parseFxFile(loader.getResource("fx/$path")!!.readText()).first }
    }

    private val rows: List<DocRow> by lazy { parseTables(File("docs/fx-engineering.md").readLines()) }

    @Test
    fun `every built-in FX has exactly one row and every row is a built-in`() {
        val ids = builtIns.map { it.id }
        val counts = rows.groupingBy { it.id }.eachCount()
        val missing = ids.filter { it !in counts }
        val twice = counts.filterValues { it > 1 }.keys
        val unknown = counts.keys - ids.toSet()
        assertTrue(missing.isEmpty(), "built-in FX with no row in fx-engineering.md's FX tables: $missing")
        assertTrue(twice.isEmpty(), "FX with more than one row: $twice")
        assertTrue(unknown.isEmpty(), "rows naming no built-in FX: $unknown")
    }

    @Test
    fun `each row sits under its family's heading`() {
        val families = builtIns.associate { it.id to it.family }
        for (row in rows) {
            val family = families[row.id] ?: continue
            val word = FAMILY_HEADINGS[family] ?: fail("no heading word for family '$family'; add one to FAMILY_HEADINGS")
            assertTrue(
                row.heading.contains(word, ignoreCase = true),
                "${row.id} (line ${row.line}) is under \"${row.heading}\", expected a heading saying $word",
            )
        }
    }

    @Test
    fun `each row lists exactly its FX's parameters, in order`() {
        val parameters = builtIns.associate { it.id to it.parameters.map(FxFileParameter::name) }
        for (row in rows) {
            val expected = parameters[row.id] ?: continue
            assertEquals(expected, row.parameters, "${row.id} (line ${row.line}): the Parameters cell")
        }
    }

    private companion object {
        val FAMILY_HEADINGS = mapOf(
            "intensity" to "Intensity",
            "colour" to "Colour",
            "position" to "Position",
            "beam" to "Beam",
            "steps" to "Step",
        )

        val CODE = Regex("`([^`]+)`")

        fun cells(line: String): List<String> =
            line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

        fun parseTables(lines: List<String>): List<DocRow> {
            val rows = mutableListOf<DocRow>()
            var heading = ""
            var parameterColumn = -1
            for ((index, line) in lines.withIndex()) {
                if (line.startsWith("#")) heading = line.trimStart('#').trim()
                if (!line.startsWith("|")) {
                    parameterColumn = -1
                    continue
                }
                val cells = cells(line)
                if (parameterColumn < 0) {
                    if (cells.firstOrNull() == "FX") parameterColumn = cells.indexOf("Parameters")
                    continue
                }
                if (cells.all { it.matches(Regex("-+")) }) continue
                val id = CODE.find(cells[0])?.groupValues?.get(1) ?: fail("line ${index + 1}: an FX row names no `id`")
                val parameters = CODE.findAll(cells.getOrElse(parameterColumn) { "" }).map { it.groupValues[1] }.toList()
                rows += DocRow(id, heading, parameters, index + 1)
            }
            return rows
        }
    }
}
