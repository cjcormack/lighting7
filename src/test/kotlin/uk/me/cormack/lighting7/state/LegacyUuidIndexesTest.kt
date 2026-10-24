package uk.me.cormack.lighting7.state

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.ALL_TABLES
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyUuidIndexesTest {

    @Test
    fun `the legacy global uuid indexes are dropped and nothing else is`() {
        val path = Files.createTempFile("lighting7-legacy-", ".db").also { Files.delete(it) }
        val ds = HikariDataSource(HikariConfig().apply {
            driverClassName = "org.sqlite.JDBC"
            jdbcUrl = "jdbc:sqlite:$path"
            maximumPoolSize = 1
            isAutoCommit = false
        })
        try {
            val database = Database.connect(ds)
            val before = transaction(database) {
                SchemaUtils.create(*ALL_TABLES.toTypedArray())
                val declared = indexNames()
                exec("CREATE UNIQUE INDEX uq_projects_uuid ON projects (uuid)")
                LEGACY_UUID_INDEXES.forEach { name ->
                    val table = name.removePrefix("uq_").removeSuffix("_uuid")
                    exec("CREATE UNIQUE INDEX $name ON ${RENAMED[table] ?: table} (uuid)")
                }
                declared
            }

            val after = transaction(database) {
                dropLegacyUuidIndexes()
                indexNames()
            }

            assertEquals(before + "uq_projects_uuid", after)
        } finally {
            ds.close()
            Files.deleteIfExists(path)
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.indexNames(): Set<String> {
        val names = sortedSetOf<String>()
        exec("SELECT name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%'") { rs ->
            while (rs.next()) names += rs.getString("name")
        }
        return names
    }

    /** Tables database step 5 renamed: an old index keeps its name on the table it moved to. */
    private val RENAMED = mapOf(
        "cue_ad_hoc_effects" to "cue_ad_hoc_fx",
        "cue_property_assignments" to "cue_attribute_assignments",
    )
}
