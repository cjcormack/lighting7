package uk.me.cormack.lighting7.state

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.ALL_TABLES
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the empty-database fast path in [State]'s `initDatabase`.
 *
 * On a brand-new database `initDatabase` uses `SchemaUtils.create` instead of
 * `createMissingTablesAndColumns`, because the latter reconciles the model against the live
 * schema through JDBC metadata and sqlite-jdbc answers that by re-parsing every table's DDL —
 * ~1220 ms for these tables, paid whether or not anything is missing. `create` does the same job
 * on an empty database in ~6 ms. That one branch took the test suite from ~13.5 min to ~1 min,
 * so it is worth a test that says *why it is safe*: the two paths must produce the same schema.
 *
 * This compares the resulting `sqlite_master` DDL rather than trusting that they agree. Index
 * *names* are excluded deliberately — Exposed's two paths name the same unique indices
 * differently (`projects_name` vs `projects_name_unique`), which it logs about on every
 * reconciliation and which has no effect on behaviour. Everything else must match exactly.
 */
class FreshSchemaEquivalenceTest {

    private val temps = mutableListOf<Path>()
    private val sources = mutableListOf<HikariDataSource>()

    @AfterTest
    fun tearDown() {
        sources.forEach { runCatching { it.close() } }
        temps.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun freshDatabase(): Database {
        val path = Files.createTempFile("lighting7-schema-", ".db").also { Files.delete(it) }
        temps.add(path)
        val ds = HikariDataSource(HikariConfig().apply {
            driverClassName = "org.sqlite.JDBC"
            jdbcUrl = "jdbc:sqlite:$path"
            maximumPoolSize = 1
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_SERIALIZABLE"
            validate()
        })
        sources.add(ds)
        return Database.connect(ds)
    }

    /** Every table and index in the database, as normalised DDL keyed by object name. */
    private fun schemaOf(database: Database): Map<String, String> {
        val out = sortedMapOf<String, String>()
        transaction(database) {
            exec(
                "SELECT type, name, tbl_name, sql FROM sqlite_master " +
                    "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
            ) { rs ->
                while (rs.next()) {
                    val type = rs.getString("type")
                    val sql = rs.getString("sql") ?: continue
                    // Key indices by their table + definition rather than their generated name,
                    // so the two paths' differing unique-index names don't register as a diff.
                    val key = if (type == "index") {
                        "index:${rs.getString("tbl_name")}:${sql.substringAfter(" ON ")}"
                    } else {
                        "$type:${rs.getString("name")}"
                    }
                    out[key] = sql.replace(Regex("\\s+"), " ").trim()
                }
            }
        }
        return out
    }

    @Test
    fun `create and createMissingTablesAndColumns build the same schema on a fresh database`() {
        val viaCreate = freshDatabase()
        transaction(viaCreate) { SchemaUtils.create(*ALL_TABLES.toTypedArray()) }

        val viaReconcile = freshDatabase()
        transaction(viaReconcile) {
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(*ALL_TABLES.toTypedArray())
        }

        val createdSchema = schemaOf(viaCreate)
        val reconciledSchema = schemaOf(viaReconcile)

        // Compare the key sets first: a missing table or index is the failure that matters, and
        // naming it is far more useful than a whole-map diff.
        assertEquals(
            reconciledSchema.keys,
            createdSchema.keys,
            "SchemaUtils.create produced a different set of schema objects than " +
                "createMissingTablesAndColumns — the fast path in State.initDatabase is no " +
                "longer safe for a fresh database",
        )
        for ((key, reconciledDdl) in reconciledSchema) {
            assertEquals(reconciledDdl, createdSchema[key], "DDL differs for $key")
        }
    }

    @Test
    fun `the fast path builds every table the schema declares`() {
        val database = freshDatabase()
        transaction(database) { SchemaUtils.create(*ALL_TABLES.toTypedArray()) }

        val built = schemaOf(database).keys.filter { it.startsWith("table:") }
            .map { it.removePrefix("table:") }
            .toSet()
        val declared = ALL_TABLES.map { it.nameInDatabaseCase().trim('"') }.toSet()

        assertEquals(declared, built, "SchemaUtils.create did not build every table in ALL_TABLES")
        assertTrue(declared.isNotEmpty(), "ALL_TABLES is empty — the comparison proves nothing")
    }
}
