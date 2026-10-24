package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoControlSurfaceBindings
import uk.me.cormack.lighting7.models.DaoCueAdHocFx
import uk.me.cormack.lighting7.models.DaoFxDefinitions
import uk.me.cormack.lighting7.models.DaoLookFx
import uk.me.cormack.lighting7.models.DaoPaletteFx
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoScripts
import uk.me.cormack.lighting7.sync.LegacyNames
import java.nio.file.Path
import java.time.Instant
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("AttributeNamesMigration")

/** `PRAGMA user_version` once [AttributeNamesMigration] has run, the step after [TERMINOLOGY_RENAMED_USER_VERSION]. */
internal const val ATTRIBUTE_NAMES_USER_VERSION = 6

/**
 * Database step 6 (terminology plan session 6): the two stored names session 5 left behind take the
 * glossary's words, through the maps [LegacyNames] holds for the archive.
 *
 *  - A surface binding's attribute targets: `fixtureProperty`, `groupProperty` and
 *    `selectionProperty` are `fixtureAttribute`, `groupAttribute` and `selectionAttribute`, and
 *    their `propertyName` — and `encoderBankSet`'s, and a flash's nested target's — is `attribute`.
 *  - Circle's, Figure 8's and Random Position's `panCenter` and `tiltCenter` parameters are
 *    `panCentre` and `tiltCentre`, in every Look, cue and palette FX (D20).
 *
 * Runs after `State.initDatabase`'s transaction has recorded step 5, so it meets the renamed
 * schema. A database holding none of the old names (a fresh one among them) is only marked; one
 * that holds some is backed up first to `<db>.pre-attribute-<utc>.db`, then rewritten and marked in
 * one transaction. Never twice: below [ATTRIBUTE_NAMES_USER_VERSION] is the only way in, and the
 * maps are idempotent besides.
 *
 * Stored scripts and project FX definitions are never rewritten: one whose source names a centre by
 * its old name, or a definition declaring one (a fork of Circle made before this step), is named in a
 * warning so the operator can edit or re-fork it.
 */
internal object AttributeNamesMigration {

    /** What one run did, for the log line and the tests. */
    data class Outcome(val backup: Path?, val bindings: Int, val fx: Map<String, Int>, val stale: List<String>)

    /** One FX home: its table and the two columns the centre rename reads. */
    private class FxHome(val name: String, val table: IntIdTable, val fxType: Column<String>, val parameters: Column<Map<String, String>>)

    private val FX_HOMES = listOf(
        FxHome("Look FX", DaoLookFx, DaoLookFx.fxType, DaoLookFx.parameters),
        FxHome("cue FX", DaoCueAdHocFx, DaoCueAdHocFx.fxType, DaoCueAdHocFx.parameters),
        FxHome("palette FX", DaoPaletteFx, DaoPaletteFx.fxType, DaoPaletteFx.parameters),
    )

    /**
     * Run the step if the database is below [ATTRIBUTE_NAMES_USER_VERSION]. [dbPath] is the database
     * file, the backup's directory and stem; null skips the backup (an in-memory database has no file
     * to copy). Answers null when the database was already marked.
     */
    fun runIfNeeded(dataSource: DataSource, database: Database, dbPath: String?, now: Instant = Instant.now()): Outcome? {
        val (pending, stale) = transaction(database) {
            val version = userVersion()
            if (version >= ATTRIBUTE_NAMES_USER_VERSION) return@transaction null
            check(version >= TERMINOLOGY_RENAMED_USER_VERSION) { "Attribute names: step 5 has not run (user_version $version)" }
            (bindingRewrites().isNotEmpty() || FX_HOMES.any { fxRewrites(it).isNotEmpty() }) to staleCentres()
        } ?: return null
        for (owner in stale) {
            logger.warn("Attribute names: {} names panCenter or tiltCenter, which are panCentre and tiltCentre now; left as it is", owner)
        }
        if (!pending) {
            transaction(database) { mark() }
            return Outcome(backup = null, bindings = 0, fx = emptyMap(), stale = stale)
        }
        val backup = dbPath?.let { backUpDatabase(dataSource, it, "pre-attribute", now) }
        val outcome = transaction(database) {
            val bindings = bindingRewrites()
            for ((id, rewrite) in bindings) {
                DaoControlSurfaceBindings.update({ DaoControlSurfaceBindings.id eq id }) {
                    it[DaoControlSurfaceBindings.targetType] = rewrite.first
                    it[DaoControlSurfaceBindings.targetPayload] = rewrite.second
                }
            }
            val fx = FX_HOMES.associate { home ->
                val rewrites = fxRewrites(home)
                for ((id, parameters) in rewrites) home.table.update({ home.table.id eq id }) { it[home.parameters] = parameters }
                home.name to rewrites.size
            }
            mark()
            Outcome(backup, bindings.size, fx, stale)
        }
        logger.info(
            "Attribute names: {} surface binding(s) and {} on today's names; backup {}",
            outcome.bindings, outcome.fx.entries.joinToString { "${it.value} ${it.key}" }, backup ?: "skipped",
        )
        return outcome
    }

    /** Every binding holding an old name, by id: its type and payload at today's names. */
    private fun JdbcTransaction.bindingRewrites(): Map<Int, Pair<String, String>> =
        DaoControlSurfaceBindings.selectAll().mapNotNull { row ->
            val type = row[DaoControlSurfaceBindings.targetType]
            val payload = row[DaoControlSurfaceBindings.targetPayload]
            val renamed = LegacyNames.bindingType(type) to LegacyNames.bindingPayload(payload)
            if (renamed == type to payload) null else row[DaoControlSurfaceBindings.id].value to renamed
        }.toMap()

    /** Every FX of [home] whose centre parameters hold an old name, by id: its parameters at today's. */
    private fun JdbcTransaction.fxRewrites(home: FxHome): Map<Int, Map<String, String>> =
        home.table.selectAll().mapNotNull { row ->
            val parameters = row[home.parameters]
            val renamed = LegacyNames.centreParameters(row[home.fxType], parameters)
            if (renamed == parameters) null else row[home.table.id].value to renamed
        }.toMap()

    /** Every stored script and FX definition still saying an old centre, as the warning names it. */
    private fun JdbcTransaction.staleCentres(): List<String> {
        fun says(text: String) = LegacyNames.CENTRE_PARAMETERS.keys.any { it in text }
        val scripts = DaoScripts.join(DaoProjects, JoinType.INNER, DaoScripts.project, DaoProjects.id).selectAll()
            .filter { says(it[DaoScripts.script]) }
            .map { "script '${it[DaoScripts.name]}' in '${it[DaoProjects.name]}'" }
        val definitions = DaoFxDefinitions.join(DaoProjects, JoinType.INNER, DaoFxDefinitions.project, DaoProjects.id).selectAll()
            .filter { row -> row[DaoFxDefinitions.parameters].any { it.name in LegacyNames.CENTRE_PARAMETERS } || says(row[DaoFxDefinitions.script]) }
            .map { "FX definition '${it[DaoFxDefinitions.name]}' in '${it[DaoProjects.name]}'" }
        return scripts + definitions
    }

    private fun JdbcTransaction.mark() {
        exec("PRAGMA user_version = $ATTRIBUTE_NAMES_USER_VERSION", explicitStatementType = StatementType.OTHER)
    }
}
