import type { CueType } from '@/api/cueStacksApi'

/**
 * What the picker reads off one row of the project's cue list (`GET /projects/{id}/cues`), and
 * nothing more.
 *
 * **The stack and number are optional.** A cue is a project record a stack only names, so a cue
 * can be in no stack (*loose*) or in several, and the list row then carries a null `cueStackName`
 * and `cueNumber`; this picker is the "An existing cue…" destination for all of them. It draws
 * whatever stack the row carries and assumes nothing beyond it — a row with no stack is a cue all
 * the same, named by its name, and a shared cue is one row because it is one cue.
 */
export interface CuePickerSource {
  id: number
  name: string
  cueNumber?: string | null
  cueNumberAuto?: boolean
  cueStackName?: string | null
  cueType?: CueType
}

export interface CuePickerRow {
  id: number
  name: string
  /** `Q12`, or null for a cue with no number. */
  number: string | null
  numberAuto: boolean
  /** The stack the list row names, or null for one that names none. */
  stackName: string | null
}

/**
 * The picker's rows: every recordable cue, **flat and by name** (venue-visit plan S10, asked
 * 2026-10-15), whatever stack it is in — name, then id, so two cues of one name keep a stable order.
 * A MARKER is left out: it is a cue-list label with nothing to record into (and after R1.1 an
 * entry, not a cue, so it leaves this list by itself).
 */
export function cuePickerRows(cues: readonly CuePickerSource[]): CuePickerRow[] {
  return cues
    .filter((cue) => cue.cueType !== 'MARKER')
    .map((cue) => ({
      id: cue.id,
      name: cue.name,
      number: cue.cueNumber ? `Q${cue.cueNumber}` : null,
      numberAuto: cue.cueNumberAuto === true,
      stackName: cue.cueStackName ?? null,
    }))
    .sort(
      (a, b) =>
        a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }) ||
        a.id - b.id,
    )
}

/** Rows matching a filter on name, number or stack, case-insensitive; an empty filter keeps all. */
export function filterCuePickerRows(rows: readonly CuePickerRow[], filter: string): CuePickerRow[] {
  const needle = filter.trim().toLowerCase()
  if (needle === '') return [...rows]
  return rows.filter(
    (row) =>
      row.name.toLowerCase().includes(needle) ||
      (row.number ?? '').toLowerCase().includes(needle) ||
      (row.stackName ?? '').toLowerCase().includes(needle),
  )
}
