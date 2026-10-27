package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.jdbc.Database
import uk.me.cormack.lighting7.sync.LegacyNames
import java.time.Instant
import javax.sql.DataSource

/** `PRAGMA user_version` once [FxSiblingsMigration] has run, the step after [FX_CONSOLIDATED_USER_VERSION]. */
internal const val FX_SIBLINGS_USER_VERSION = 8

/**
 * Database step 8 (fx-settings plan session 3): every stored Look, cue and palette FX on one of the
 * movements Path replaced — Circle, Figure 8, Random Position and the three sweeps — rewritten onto
 * Path, a Colour Cycle's `fadeRatio` as `between` and `fade`, and the Flicker family's, Lightning
 * Strike's and Colour Strobe's parameters at their new names, all by [LegacyNames.absorbFx], which
 * the importer shares, and each named in the log by its owner. What each head does is unchanged:
 * `LegacyEquivalenceTest` runs every old script beside its new form.
 *
 * Runs after step 7. A database holding none of them (a fresh one among them) is only marked; one
 * that holds some is backed up first to `<db>.pre-fx-siblings-<utc>.db`, then rewritten and marked in
 * one transaction. Never twice: below [FX_SIBLINGS_USER_VERSION] is the only way in.
 *
 * A script naming a movement's old id is left alone: `effect("Circle", …)` still runs, as Path. One
 * that names one any other way — `fxRegistry.getRegistration("Sweep")` — finds nothing now, so each
 * stored script and FX definition that does is named in a warning, never rewritten, as step 7 names
 * the level waves'. So is one quoting a renamed parameter's distinctive old name (`baseLevel`,
 * `onRatio`, …, or `fadeRatio` beside a Colour Cycle), which a built-in it builds through the registry
 * no longer reads, and a project FX definition with the id `Path`.
 */
internal object FxSiblingsMigration {
    /** A renamed parameter's old name quoted in a script; Flicker's `min` and `max` are too common to look for. */
    private val OLD_PARAMETER = Regex(
        "\"(" + LegacyNames.distinctiveOldParameters.joinToString("|") { Regex.escape(it) } + ")\"",
    )

    private val COLOUR_CYCLE = Regex("\"Colour ?Cycle\"", RegexOption.IGNORE_CASE)

    private val step = FxRewriteStep(
        loggerName = "FxSiblingsMigration",
        label = "FX siblings",
        version = FX_SIBLINGS_USER_VERSION,
        after = FX_CONSOLIDATED_USER_VERSION,
        backupStem = "pre-fx-siblings",
        onto = "Path, Colour Cycle's between and the renamed parameters",
        rewrite = { fx, definitions -> LegacyNames.absorbFx(fx, definitions = definitions) },
        oldIds = LegacyNames.pathIds,
        newId = LegacyNames.PATH,
        stale = { text ->
            val old = OLD_PARAMETER.findAll(text).map { it.groupValues[1] }.toSortedSet()
            // Palette Steps still reads a `fadeRatio`; only one beside Colour Cycle is the old crossfade.
            if (COLOUR_CYCLE.containsMatchIn(text) && "\"${LegacyNames.COLOUR_CYCLE_FADE_RATIO}\"" in text) old += LegacyNames.COLOUR_CYCLE_FADE_RATIO
            old.takeIf { it.isNotEmpty() }?.let {
                listOf(
                    "names ${it.joinToString()}, parameters renamed in fx-settings plan session 3 " +
                        "(effect() still maps them; a registry lookup or createFx reads them no more) — edit it",
                )
            }.orEmpty()
        },
    )

    /** Run the step if the database is below [FX_SIBLINGS_USER_VERSION]; see [FxRewriteStep.runIfNeeded]. */
    fun runIfNeeded(dataSource: DataSource, database: Database, dbPath: String?, now: Instant = Instant.now()): FxRewriteStep.Outcome? =
        step.runIfNeeded(dataSource, database, dbPath, now)
}
