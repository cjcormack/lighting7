package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.jdbc.Database
import uk.me.cormack.lighting7.sync.LegacyNames
import java.time.Instant
import javax.sql.DataSource

/** `PRAGMA user_version` once [FxConsolidationMigration] has run, the step after [ATTRIBUTE_NAMES_USER_VERSION]. */
internal const val FX_CONSOLIDATED_USER_VERSION = 7

/**
 * Database step 7 (fx-settings plan session 2): every stored Look, cue and palette FX on one of the
 * level waves Wave replaced — Sine Wave, the ramps, Square Wave, Pulse, Dimmer Strobe and the four
 * beam waves — rewritten onto Wave by [LegacyNames.consolidateFx], which the importer shares, and
 * each named in the log by its owner. A beam FX that named no attribute is given the one it drove.
 * What each head does is unchanged — `LegacyEquivalenceTest` runs every old script beside Wave — but
 * for one case the log names: such an FX on a group where not every head has its attribute now runs
 * on the heads that do, where before it ran on none of that group (the plan's recorded exception).
 *
 * Runs after step 6. A database holding none of the old ids (a fresh one among them) is only
 * marked; one that holds some is backed up first to `<db>.pre-fx-consolidation-<utc>.db`, then
 * rewritten and marked in one transaction. Never twice: below [FX_CONSOLIDATED_USER_VERSION] is the
 * only way in, and the rewrite reads no Wave besides.
 *
 * A script naming an old id is left alone: `effect("SineWave", …)` still runs, as Wave. One that
 * names one any other way — `fxRegistry.getRegistration("Pulse")`, the way an FX definition builds on
 * a built-in — finds nothing now, so each stored script and FX definition that does is named in a
 * warning, never rewritten (as step 6 names an old centre). So is a project FX definition with the id
 * `Wave`, which would take the place of the FX its level waves now run as; none can be made today.
 */
internal object FxConsolidationMigration {
    private val step = FxRewriteStep(
        loggerName = "FxConsolidationMigration",
        label = "FX consolidation",
        version = FX_CONSOLIDATED_USER_VERSION,
        after = ATTRIBUTE_NAMES_USER_VERSION,
        backupStem = "pre-fx-consolidation",
        onto = "Wave",
        rewrite = LegacyNames::consolidateFx,
        oldIds = LegacyNames.waveIds,
        newId = LegacyNames.WAVE,
    )

    /** Run the step if the database is below [FX_CONSOLIDATED_USER_VERSION]; see [FxRewriteStep.runIfNeeded]. */
    fun runIfNeeded(dataSource: DataSource, database: Database, dbPath: String?, now: Instant = Instant.now()): FxRewriteStep.Outcome? =
        step.runIfNeeded(dataSource, database, dbPath, now)
}
