package uk.me.cormack.lighting7.state

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.ALL_TABLES
import uk.me.cormack.lighting7.models.DaoLookFx
import uk.me.cormack.lighting7.models.DaoOscSettingsTable
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoScripts
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [tablesOutOfStep], which decides the tables `initDatabase` reconciles. Leaving out a table the full
 * reconcile would have changed is the failure that matters, so the last test boots the oldest frozen
 * database and checks a full `createMissingTablesAndColumns` afterwards finds nothing to do.
 */
class SchemaDriftTest {

    private val temps = mutableListOf<Path>()
    private val sources = mutableListOf<HikariDataSource>()
    private var state: State? = null

    @AfterTest
    fun tearDown() {
        state?.let { runCatching { it.shutdown() } }
        sources.forEach { runCatching { it.close() } }
        temps.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun builtDatabase(): Database {
        val path = Files.createTempFile("lighting7-drift-", ".db").also { Files.delete(it) }
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
        return Database.connect(ds).also { transaction(it) { SchemaUtils.create(*ALL_TABLES.toTypedArray()) } }
    }

    private fun Database.outOfStep(): Set<String> =
        transaction(this) { tablesOutOfStep(ALL_TABLES).map { it.tableName }.toSet() }

    private fun Database.schema(): List<String> = transaction(this) {
        val out = ArrayList<String>()
        exec("SELECT type, name, sql FROM sqlite_master ORDER BY type, name") { rs ->
            while (rs.next()) out += "${rs.getString("type")} ${rs.getString("name")}: ${rs.getString("sql")}"
        }
        out
    }

    @Test
    fun `a schema the model built has nothing out of step`() {
        assertEquals(emptySet(), builtDatabase().outOfStep())
    }

    @Test
    fun `a missing table, column or index puts its table out of step, and a renamed index does not`() {
        val database = builtDatabase()
        val scriptsIndex = DaoScripts.indices.single()
        transaction(database) {
            // `create` names the index differently from the model, so drop it by its live name.
            val live = ArrayList<String>()
            exec("SELECT name FROM pragma_index_list('${DaoScripts.tableName}') WHERE name NOT LIKE 'sqlite_%'") { rs ->
                while (rs.next()) live += rs.getString("name")
            }
            exec("DROP TABLE ${DaoLookFx.tableName}")
            exec("ALTER TABLE ${DaoProjects.tableName} DROP COLUMN ${DaoProjects.description.name}")
            exec("DROP INDEX ${live.single()}")
        }
        assertEquals(setOf(DaoLookFx.tableName, DaoProjects.tableName, DaoScripts.tableName), database.outOfStep())

        transaction(database) {
            exec("CREATE UNIQUE INDEX scripts_renamed ON ${DaoScripts.tableName} (${scriptsIndex.columns.joinToString { it.name }})")
        }
        assertEquals(setOf(DaoLookFx.tableName, DaoProjects.tableName), database.outOfStep())
    }

    @Test
    fun `an old database booted by the desk leaves a full reconcile nothing to do`() {
        IntegrationTestDb.reset()
        val sql = javaClass.getResourceAsStream("/db/pre-cue-model.sql")!!.readBytes().toString(Charsets.UTF_8)
        assertFalse(DaoOscSettingsTable.tableName in sql)
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                sql.split(";\n")
                    .map { chunk -> chunk.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { statement.execute(it) }
            }
            connection.commit()
        }
        val database = State(testAppConfig()).also { state = it }.database

        val booted = database.schema()
        transaction(database) {
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(*ALL_TABLES.toTypedArray())
        }
        assertEquals(booted, database.schema())
        assertEquals(emptySet(), database.outOfStep())
        assertTrue(booted.any { it.startsWith("table ${DaoOscSettingsTable.tableName}:") }, "the boot reconciled nothing")
    }
}
