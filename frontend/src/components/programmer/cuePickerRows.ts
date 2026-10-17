import type { CueType } from '@/api/cueStacksApi'

/**
 * What the picker reads off one row of the project's cue list (`GET /projects/{id}/cues`), and
 * nothing more.
 *
 * **A cue's places are its entries.** A cue is a project record a stack only names, so it can be in
 * no stack (*loose*), in one, or in several, and the list carries every entry naming it (cue model
 * plan session 5) — the same list the Cues table reads. This picker is the "An existing cue…"
 * destination for all of them: a row with no entry is a cue all the same, named by its name, and a
 * shared cue is one row because it is one cue, labelled with every place it sits.
 */
export interface CuePickerSource {
  id: number
  name: string
  entries?: readonly { stackName: string; cueNumber: string | null; cueNumberAuto?: boolean }[]
  cueType?: CueType
}

/** One place a picker row names: `Q12 · Act 1`, or the stack alone for an unnumbered entry. */
export interface CuePickerPlace {
  /** `Q12`, or null for an entry with no number. */
  number: string | null
  numberAuto: boolean
  stackName: string
}

export interface CuePickerRow {
  id: number
  name: string
  /** Every place the cue sits, in show order; empty for a loose cue. */
  places: CuePickerPlace[]
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
      places: (cue.entries ?? []).map((e) => ({
        number: e.cueNumber ? `Q${e.cueNumber}` : null,
        numberAuto: e.cueNumberAuto === true,
        stackName: e.stackName,
      })),
    }))
    .sort(
      (a, b) =>
        a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }) ||
        a.id - b.id,
    )
}

/** Rows matching a filter on name, or any place's number or stack, case-insensitive; empty keeps all. */
export function filterCuePickerRows(rows: readonly CuePickerRow[], filter: string): CuePickerRow[] {
  const needle = filter.trim().toLowerCase()
  if (needle === '') return [...rows]
  return rows.filter(
    (row) =>
      row.name.toLowerCase().includes(needle) ||
      row.places.some(
        (place) => (place.number ?? '').toLowerCase().includes(needle) || place.stackName.toLowerCase().includes(needle),
      ),
  )
}
