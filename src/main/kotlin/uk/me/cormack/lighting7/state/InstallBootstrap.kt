package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoInstall

private val logger = LoggerFactory.getLogger("InstallBootstrap")

/**
 * Creates the singleton install-identity row on a database that has none.
 *
 * Not a migration — every database needs this, including one created a moment ago. The install
 * uuid is the machine's identity for cloud sync (it keys `machine_overrides` and derives the
 * file credential store's encryption key), so it has to exist before anything reads it.
 *
 * ### On the migrations that used to live beside this
 *
 * This file was `StateMigrations.kt` and carried four data migrations — positional colour lists,
 * universe addresses into `machine_overrides`, the two-layer show model collapsing into cue
 * stacks, and presets/palettes becoming Looks — plus `backfillAutoCueNumbers` and a set of
 * legacy `DROP INDEX` statements in `State.initDatabase`.
 *
 * They were removed on 2026-08-24. Every one had already run on the only database in existence
 * (the dev desk), verified before deletion: no legacy tables held unconverted rows, no legacy
 * indexes remained, and every source row had a counterpart at its destination. On any other
 * database they were no-ops, because there are no other databases.
 *
 * **Recover them from git history if that changes.** The moment a second install exists — the
 * Windows MSI ships an upgrade path, see `docs/windows-updates.md` — a schema change that is not
 * additive needs a migration again, and this is where it goes: called from
 * `State.initDatabase`'s transaction, after the schema is created and before anything reads it.
 * Note the ordering constraint that bit last time: a migration that drops a `NOT NULL` column
 * must run before any migration that inserts through the DAO, or the insert omits the column and
 * fails.
 */
internal fun JdbcTransaction.ensureInstallRow() {
    if (DaoInstall.all().firstOrNull() != null) return
    val hostname = runCatching { java.net.InetAddress.getLocalHost().hostName }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: "lighting7"
    DaoInstall.new {
        friendlyName = hostname
        createdAtMs = System.currentTimeMillis()
    }
    logger.info("Created install identity row with friendlyName='{}'", hostname)
}
