import type { CueStack, CueType } from '@/api/cueStacksApi'
import type { IncludedTarget } from '@/api/programmerWsApi'
import { cuesInShowOrder, isBuskStack, showOrderStacks } from '@/lib/runningOrder'

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
  entries?: readonly {
    stackId: number
    /** Position in its stack's list, from 0. */
    sortOrder: number
    stackName: string
    cueNumber: string | null
    cueNumberAuto?: boolean
  }[]
  cueType?: CueType
}

/** What the picker reads of a stack: its place in the show. */
export type CuePickerStack = Pick<CueStack, 'id' | 'name' | 'type' | 'inRunningOrder'>

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

/** One divider of the picker and the cues under it. */
export interface CuePickerSection {
  /** `stack:{id}`, or `loose`. */
  key: string
  /** The stack's name, or *Loose*. */
  label: string
  /** A busk stack, listed after the running order. */
  busk: boolean
  rows: CuePickerRow[]
}

/**
 * The picker's rows in **show order** (venue-rig-fixes plan D15), under a divider per stack: the
 * running order's stacks, then the busk stacks, each cue once at its first place in that order
 * (`cuesInShowOrder`), then the loose cues by name. A row still names every place, in the same order.
 * A MARKER is left out: it is a cue-list label with nothing to record into (and after R1.1 an entry,
 * not a cue, so it leaves this list by itself).
 */
export function cuePickerSections(
  cues: readonly CuePickerSource[],
  stacks: readonly CuePickerStack[] | undefined,
): CuePickerSection[] {
  const ordered = showOrderStacks(stacks)
  const rank = new Map(ordered.map((stack, i) => [stack.id, i]))
  const placeOrder = (a: { stackId: number; sortOrder: number }, b: { stackId: number; sortOrder: number }) =>
    (rank.get(a.stackId) ?? ordered.length) - (rank.get(b.stackId) ?? ordered.length) || a.sortOrder - b.sortOrder
  const toRow = (cue: CuePickerSource): CuePickerRow => ({
    id: cue.id,
    name: cue.name,
    places: [...(cue.entries ?? [])].sort(placeOrder).map((e) => ({
      number: e.cueNumber ? `Q${e.cueNumber}` : null,
      numberAuto: e.cueNumberAuto === true,
      stackName: e.stackName,
    })),
  })
  return cuesInShowOrder(
    cues.filter((cue) => cue.cueType !== 'MARKER'),
    stacks,
  ).map(({ stack, cues: placed }) => ({
    key: stack == null ? 'loose' : `stack:${stack.id}`,
    label: stack?.name ?? 'Loose',
    busk: stack != null && isBuskStack(stack),
    rows: placed.map(toRow),
  }))
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

/** [filterCuePickerRows] over each section, keeping a divider only over a match. */
export function filterCuePickerSections(sections: readonly CuePickerSection[], filter: string): CuePickerSection[] {
  return sections
    .map((section) => ({ ...section, rows: filterCuePickerRows(section.rows, filter) }))
    .filter((section) => section.rows.length > 0)
}

/**
 * The cue Merge opens on with nothing named (D16): the included cue when Include last loaded one,
 * else the live cue of the stack the show's playhead is on; else none.
 */
export function preselectedRecordCue(
  included: IncludedTarget | null | undefined,
  playheadStackId: number | null | undefined,
  stacks: readonly Pick<CueStack, 'id' | 'activeCueId'>[] | undefined,
): number | null {
  if (included?.kind === 'CUE') return included.cueId
  if (playheadStackId == null) return null
  return stacks?.find((stack) => stack.id === playheadStackId)?.activeCueId ?? null
}
