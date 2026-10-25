import type { TimelineEditOp, TimelineFx } from '@/api/stackTimelineApi'
import { getCycleLabel, secondsLabel } from '@/components/fx/fxConstants'
import type { TimelineCells } from './cells'
import { filledOf, type TimelineCueRef } from './edits'
import type { TimelineColumn } from './model'
import { runMarkAt, type TimelineRuns } from './runs'

/** One cell of an FX run, as the run popover lists it (§4.4). */
export interface FxRunCell {
  entryId: number
  cueId: number
  effect: TimelineFx
  /** Its settings differ from the cell above in the run: an edit in place at its GO (◆). */
  changed: boolean
}

function fxOf(cells: TimelineCells, column: TimelineColumn, entryId: number): TimelineFx | null {
  const filled = filledOf(cells, entryId, column)
  return filled?.kind === 'fx' ? filled.effect : null
}

/** The run [entryId]'s FX cell is in, top first: every cell joined to it above and below. Empty for an empty cell. */
export function fxRunAt(
  order: readonly TimelineCueRef[],
  cells: TimelineCells,
  marks: TimelineRuns,
  column: TimelineColumn,
  entryId: number,
): FxRunCell[] {
  const at = order.findIndex((c) => c.entryId === entryId)
  if (at < 0 || fxOf(cells, column, entryId) == null) return []
  const mark = (i: number) => runMarkAt(marks, order[i].entryId, column.id)
  let top = at
  while (top > 0 && !mark(top).start) top--
  let foot = at
  while (foot < order.length - 1 && !mark(foot).end) foot++
  return order.slice(top, foot + 1).flatMap((cue, i) => {
    const effect = fxOf(cells, column, cue.entryId)
    return effect == null ? [] : [{ entryId: cue.entryId, cueId: cue.cueId, effect, changed: mark(top + i).changed }]
  })
}

/** An FX verb on one cell: the op it sends, or why it is not offered. */
export interface FxRunVerb {
  op: TimelineEditOp | null
  reason: string | null
}

/** *Break run at Qn*: offered on a cell joined to the one above it. */
export function breakRunVerb(
  order: readonly TimelineCueRef[],
  cells: TimelineCells,
  marks: TimelineRuns,
  column: TimelineColumn,
  entryId: number,
): FxRunVerb {
  const cue = order.find((c) => c.entryId === entryId)
  const effect = fxOf(cells, column, entryId)
  if (cue == null || effect == null) return { op: null, reason: 'No FX in this cell' }
  if (runMarkAt(marks, entryId, column.id).start) return { op: null, reason: 'This cell starts its run already' }
  return { op: { op: 'breakRun', cueId: cue.cueId, fxUuid: effect.uuid, entryId }, reason: null }
}

/**
 * *Join run above*: offered on a cell not joined to the one above, where the cell directly above it
 * (the cue entry above, markers passed over) is filled — a run joins neighbours only — and neither
 * has a Runs count, since a GO fires an N-shot afresh and a run through one would never play as drawn.
 */
export function joinRunVerb(
  order: readonly TimelineCueRef[],
  cells: TimelineCells,
  marks: TimelineRuns,
  column: TimelineColumn,
  entryId: number,
): FxRunVerb {
  const at = order.findIndex((c) => c.entryId === entryId)
  const effect = fxOf(cells, column, entryId)
  if (at < 0 || effect == null) return { op: null, reason: 'No FX in this cell' }
  if (!runMarkAt(marks, entryId, column.id).start) return { op: null, reason: 'Joined to the cell above already' }
  const above = at > 0 ? fxOf(cells, column, order[at - 1].entryId) : null
  if (above == null) return { op: null, reason: 'The cue above has no FX in this column to join' }
  if (above.settings.runs != null || effect.settings.runs != null) {
    return { op: null, reason: 'An FX with a Runs count starts afresh at every GO' }
  }
  return { op: { op: 'joinRun', cueId: order[at].cueId, fxUuid: effect.uuid, entryId }, reason: null }
}

/** An FX's cycle as its settings time it: in beats on a master, in seconds on *Seconds* or the wall clock. */
export function fxCycleWords(settings: Record<string, unknown>): string | null {
  const cycle = settings.cycle
  if (typeof cycle !== 'number') return null
  // A beat FX's settings name its speed master; one on Seconds, or a wall-clock one, names none.
  const beats = settings.seconds !== true && settings.speedMasterUuid != null
  return beats ? getCycleLabel(cycle) : secondsLabel(cycle)
}

/** What changed inside an FX run, as the ◆ says it — the first setting that moved. */
export function settingsChangeLabel(before: Record<string, unknown>, after: Record<string, unknown>): string | null {
  for (const key of Object.keys(after)) {
    const a = JSON.stringify(before[key])
    const b = JSON.stringify(after[key])
    if (a === b) continue
    const value = after[key]
    if (key === 'cycle') return fxCycleWords(after)
    if (key === 'amount' && typeof value === 'number') return `${Math.round(value * 100)}%`
    if (key === 'parameters') return 'params'
    if (key.endsWith('Uuid')) return 'master'
    return typeof value === 'string' || typeof value === 'number' ? `${key} ${value}` : key
  }
  return null
}
