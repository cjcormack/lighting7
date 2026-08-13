package uk.me.cormack.lighting7.fx

import java.util.UUID

/**
 * The named-palette reference grammar for stored assignment values.
 *
 * A cue property assignment, an FX preset property assignment or a programmer entry normally
 * holds a literal in the canonical [Layer3Resolver.PropertyValue.serialize] grammar. It may
 * instead hold `ref:{paletteUuid}`, which resolves per fixture against the named palette — see
 * [uk.me.cormack.lighting7.models.DaoPalettes].
 *
 * **This is a different thing from the positional palette refs `P1` / `P2` / `P*`** handled by
 * [isPaletteRef] / [resolveColour] in `Effect.kt`. Those index into an ordered colour list held
 * per cue / stack / globally; these name a palette record. The two grammars cannot collide —
 * `^P(\d+)$` never matches a `ref:` string — and both remain supported.
 *
 * The reference is the palette's **uuid**, not its int id, and that is load-bearing rather than
 * stylistic. Int primary keys never appear in the sync export (every cross-record reference in
 * the layout is a `{table}Uuid` string) and are re-minted by the DB on import, so `ref:12` would
 * dangle after any import or clone. A uuid survives both: a plain import writes uuids back
 * verbatim, and a clone rewrites them *consistently* — [uk.me.cormack.lighting7.sync.ExportUuidRemapper]
 * substitutes uuids across the whole JSON text, explicitly including ones embedded in free-text
 * columns, which is exactly this case.
 */
const val PALETTE_REF_PREFIX = "ref:"

/** The stored value form for a reference to the palette identified by [paletteUuid]. */
fun paletteRefValue(paletteUuid: UUID): String = "$PALETTE_REF_PREFIX$paletteUuid"

/**
 * The referenced palette uuid, or null when [value] is not a named-palette reference.
 *
 * Tolerant of surrounding whitespace and of the prefix's case, strict about the uuid: a
 * malformed one answers null rather than throwing, so a corrupt stored row degrades to "this
 * doesn't resolve" (reported as a skip with health) rather than failing a whole cue apply.
 */
fun parsePaletteRef(value: String): UUID? {
    val trimmed = value.trim()
    if (!trimmed.startsWith(PALETTE_REF_PREFIX, ignoreCase = true)) return null
    val rest = trimmed.substring(PALETTE_REF_PREFIX.length).trim()
    return runCatching { UUID.fromString(rest) }.getOrNull()
}

/** True when [value] is a named-palette reference. */
fun isPaletteRefValue(value: String): Boolean = parsePaletteRef(value) != null
