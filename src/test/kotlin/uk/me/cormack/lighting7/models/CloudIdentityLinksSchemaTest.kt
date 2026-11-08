package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.state.tablesOutOfStep
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A desk adopted before cloud-p1 session 6 has a `cloud_identity_links` unique by user only. The
 * startup reconciliation must give it `invite_id` and the unique index on `cloud_account_id` the
 * links now lean on, since this is no migration of its own.
 */
class CloudIdentityLinksSchemaTest {
    @Test
    fun `reconciling an older table adds the account index and the invite column`() {
        val file = Files.createTempFile("links", ".db").also { it.toFile().deleteOnExit() }
        val database = Database.connect("jdbc:sqlite:$file", driver = "org.sqlite.JDBC")
        transaction(database) {
            SchemaUtils.create(DaoUsers)
            exec(
                "CREATE TABLE cloud_identity_links (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INT NOT NULL, " +
                    "cloud_account_id TEXT NOT NULL, email TEXT NOT NULL, linked_at TEXT NOT NULL, linked_via VARCHAR(16) NOT NULL)",
            )
            exec("CREATE UNIQUE INDEX cloud_identity_links_user_id_unique ON cloud_identity_links (user_id)")
        }
        transaction(database) {
            val outOfStep = tablesOutOfStep(listOf(DaoUsers, DaoCloudIdentityLinks))
            assertEquals(listOf<Table>(DaoCloudIdentityLinks), outOfStep)
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(*outOfStep.toTypedArray())
        }
        val (columns, indexes) = transaction(database) {
            val conn = TransactionManager.current().connection.connection as java.sql.Connection
            val cols = conn.createStatement().executeQuery("PRAGMA table_info(cloud_identity_links)").use { rs ->
                generateSequence { if (rs.next()) rs.getString("name") else null }.toList()
            }
            val idx = conn.createStatement().executeQuery("PRAGMA index_list(cloud_identity_links)").use { rs ->
                generateSequence { if (rs.next()) rs.getString("name") to rs.getInt("unique") else null }.toList()
            }
            cols to idx
        }
        assertTrue("invite_id" in columns, columns.toString())
        assertTrue(indexes.any { (name, unique) -> "cloud_account_id" in name && unique == 1 }, indexes.toString())
    }
}
