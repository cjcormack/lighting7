import type { StackTimeline } from '@/api/stackTimelineApi'
import { cellAt, type TimelineCells } from './cells'
import type { TimelineCell, TimelineColumn, TimelineMarkerMark, TimelineRunMark } from './model'

/**
 * Whether two vertically neighbouring cells of [column] draw as one bar (§3.1): any two filled
 * layer or palette cells; effect cells only on one shared run key (D7), so two unkeyed rows are two
 * runs, and never where either is an N-shot, which a GO fires afresh rather than hands on
 * (`CueHolds.handsRunTo`, `mayContinue`); scenery cells never — each change is its own chip, and
 * the tracked state a line.
 */
export function joins(column: TimelineColumn, above: TimelineCell, below: TimelineCell): boolean {
  const a = above.filled
  const b = below.filled
  if (a == null || b == null || column.kind === 'scenery') return false
  if (a.kind === 'effect' && b.kind === 'effect') {
    return a.effect.runUuid != null && a.effect.runUuid === b.effect.runUuid && !isNShot(a.effect) && !isNShot(b.effect)
  }
  return true
}

function isNShot(effect: { settings: Record<string, unknown> }): boolean {
  return effect.settings.nShot != null
}

/** Whether [cell]'s scenery state carries on below it: a change of its own, or one tracked into it. */
function carries(cell: TimelineCell): boolean {
  return cell.tracked != null || cell.filled?.kind === 'scenery'
}

/** Key order and values alike: two settings objects as the desk compares them. */
export function sameSettings(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (typeof a !== 'object' || typeof b !== 'object' || a == null || b == null) return false
  if (Array.isArray(a) !== Array.isArray(b)) return false
  const ak = Object.keys(a as object)
  const bk = Object.keys(b as object)
  if (ak.length !== bk.length) return false
  return ak.every((k) => Object.prototype.hasOwnProperty.call(b, k) && sameSettings((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k]))
}

export interface TimelineRuns {
  /** Each cue entry's marks, by entry id then column id. */
  cue: Map<number, Map<string, TimelineRunMark>>
  /** Each marker entry's marks, by entry id then column id. */
  marker: Map<number, Map<string, TimelineMarkerMark>>
}

const NO_MARK: TimelineRunMark = { start: false, end: false, restart: false, changed: false, trackedLine: false }
const NO_MARKER_MARK: TimelineMarkerMark = { through: false, trackedLine: false }

export function runMarkAt(runs: TimelineRuns, entryId: number, columnId: string): TimelineRunMark {
  return runs.cue.get(entryId)?.get(columnId) ?? NO_MARK
}

export function markerMarkAt(runs: TimelineRuns, entryId: number, columnId: string): TimelineMarkerMark {
  return runs.marker.get(entryId)?.get(columnId) ?? NO_MARKER_MARK
}

/**
 * How every cell draws against its neighbours (§3.7): where a run starts and ends, a restart (↺)
 * where two run keys meet, a change in place (◆) where a run's settings move, and the tracked line
 * of a scenery element. Neighbours are the cues above and below, **across markers**: a marker is a
 * divider row the run passes through, never a break in it.
 */
export function runs(timeline: StackTimeline, columns: readonly TimelineColumn[], cells: TimelineCells): TimelineRuns {
  const cueIds = timeline.entries.filter((e) => e.kind === 'CUE').map((e) => e.entryId)
  const out: TimelineRuns = { cue: new Map(), marker: new Map() }

  cueIds.forEach((entryId, i) => {
    const marks = new Map<string, TimelineRunMark>()
    for (const column of columns) {
      const cell = cellAt(cells, entryId, column.id)
      const above = i > 0 ? cellAt(cells, cueIds[i - 1], column.id) : null
      const below = i < cueIds.length - 1 ? cellAt(cells, cueIds[i + 1], column.id) : null
      if (column.kind === 'scenery') {
        const trackedLine = cell.tracked != null || (cell.filled != null && above != null && carries(above))
        if (cell.filled != null || trackedLine) marks.set(column.id, { ...NO_MARK, start: cell.filled != null, end: cell.filled != null, trackedLine })
        continue
      }
      if (cell.filled == null) continue
      const joinedAbove = above != null && joins(column, above, cell)
      const joinedBelow = below != null && joins(column, cell, below)
      const effect = cell.filled.kind === 'effect' ? cell.filled.effect : null
      const aboveEffect = above?.filled?.kind === 'effect' ? above.filled.effect : null
      marks.set(column.id, {
        start: !joinedAbove,
        end: !joinedBelow,
        restart: effect != null && aboveEffect != null && !joinedAbove,
        changed: effect != null && aboveEffect != null && joinedAbove && !sameSettings(aboveEffect.settings, effect.settings),
        trackedLine: false,
      })
    }
    out.cue.set(entryId, marks)
  })

  timeline.entries.forEach((entry, index) => {
    if (entry.kind !== 'MARKER') return
    const before = [...timeline.entries.slice(0, index)].reverse().find((e) => e.kind === 'CUE')
    const after = timeline.entries.slice(index + 1).find((e) => e.kind === 'CUE')
    const marks = new Map<string, TimelineMarkerMark>()
    for (const column of columns) {
      const above = before ? cellAt(cells, before.entryId, column.id) : null
      const below = after ? cellAt(cells, after.entryId, column.id) : null
      if (column.kind === 'scenery') {
        // From the cue above, or — with none above — from the stack's set, which the cue below tracks.
        const trackedLine = above != null ? carries(above) : below?.tracked != null
        if (trackedLine) marks.set(column.id, { through: false, trackedLine })
      } else if (above != null && below != null && joins(column, above, below)) {
        marks.set(column.id, { through: true, trackedLine: false })
      }
    }
    out.marker.set(entry.entryId, marks)
  })
  return out
}
