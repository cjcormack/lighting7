import type { CueTarget } from '@/api/cuesApi'
import type { TimelineCellRef, TimelineEditOp, TimelineEntry, TimelineLayer, TimelineSourceRef } from '@/api/stackTimelineApi'
import { cellAt, type TimelineCells } from './cells'
import type { TimelineColumn, TimelineFilled } from './model'

/**
 * The Timeline's gestures as op lists (stack-timeline plan §3.6, §4.4): pure, one list per gesture,
 * which the grid sends as **one** request. A Look or palette cell turned on is the press with the
 * settings of the nearest filled cell in its column (D6); an FX cell turned on copies the nearest
 * filled cell's row, joining its run (D7); a scenery cell set copies the nearest change.
 */

/** A cue's place in the stack, in list order: the rows a column runs down. */
export interface TimelineCueRef {
  entryId: number
  cueId: number
}

/** One selected cell. */
export interface TimelineCellAddress {
  entryId: number
  columnId: string
}

/** The record a Look or palette column names, as an op names it. */
export function sourceOf(column: TimelineColumn): TimelineSourceRef | null {
  if (column.sourceId == null) return null
  if (column.kind === 'look') return { kind: 'LOOK', id: column.sourceId }
  if (column.kind === 'palette') return { kind: 'PALETTE', id: column.sourceId }
  return null
}

/** The target a column's ops name: a palette column's, and none for a Look's, which lands whole. */
export function cellTargetOf(column: TimelineColumn): CueTarget | null {
  return column.kind === 'look' ? null : column.target
}

/** A column the gestures edit: every kind. */
export function editsColumn(column: TimelineColumn): boolean {
  return column.kind === 'look' || column.kind === 'palette' || column.kind === 'fx' || column.kind === 'scenery'
}

/** What fills [column] at [entryId], of the column's own kind, or null. */
export function filledOf(cells: TimelineCells, entryId: number, column: TimelineColumn): TimelineFilled | null {
  const filled = cellAt(cells, entryId, column.id).filled
  if (filled == null) return null
  if (column.kind === 'scenery') return filled.kind === 'scenery' ? filled : null
  if (column.kind === 'fx') return filled.kind === 'fx' ? filled : null
  return filled.kind === 'layer' ? filled : null
}

/**
 * The filled cell a new cell in [column] at [entryId] copies (D6): the nearest above, else the
 * nearest below, else none — a fresh press's defaults.
 */
export function nearestFilled(
  order: readonly TimelineCueRef[],
  cells: TimelineCells,
  column: TimelineColumn,
  entryId: number,
): TimelineFilled | null {
  const at = order.findIndex((c) => c.entryId === entryId)
  for (let i = at - 1; i >= 0; i--) {
    const filled = filledOf(cells, order[i].entryId, column)
    if (filled) return filled
  }
  for (let i = at + 1; i < order.length; i++) {
    const filled = filledOf(cells, order[i].entryId, column)
    if (filled) return filled
  }
  return null
}

/** Turn [cue]'s cell in [column] on, copying [from] — null for an FX or scenery cell with nothing to copy. */
export function onOp(column: TimelineColumn, cueId: number, from: TimelineFilled | null): TimelineEditOp | null {
  const source = sourceOf(column)
  if (source != null) {
    return { op: 'layerOn', cueId, source, target: cellTargetOf(column), settingsFrom: from?.kind === 'layer' ? from.layer.id : null }
  }
  if (column.kind === 'fx') return from?.kind === 'fx' ? { op: 'fxOn', cueId, fromFxUuid: from.effect.uuid } : null
  if (column.kind === 'scenery' && column.elementUuid && from?.kind === 'scenery') {
    return { op: 'scenerySet', cueId, elementUuid: column.elementUuid, state: from.state, transitionMs: from.transitionMs }
  }
  return null
}

/**
 * Turn [cue]'s cell in [column] off — the off press, the FX row [here] deleted, or a scenery change
 * cleared back to tracking.
 */
export function offOp(column: TimelineColumn, cueId: number, here: TimelineFilled | null = null): TimelineEditOp | null {
  const source = sourceOf(column)
  if (source != null) return { op: 'layerOff', cueId, source, target: cellTargetOf(column) }
  if (column.kind === 'fx') return here?.kind === 'fx' ? { op: 'fxOff', cueId, fxUuid: here.effect.uuid } : null
  if (column.kind === 'scenery' && column.elementUuid) return { op: 'sceneryClear', cueId, elementUuid: column.elementUuid }
  return null
}

/** One op per cue and column, whichever place of the cue was named first. */
class OpList {
  readonly ops: TimelineEditOp[] = []
  private readonly seen = new Set<string>()
  add(columnId: string, cueId: number, op: TimelineEditOp | null) {
    const key = `${columnId}|${cueId}`
    if (op == null || this.seen.has(key)) return
    this.seen.add(key)
    this.ops.push(op)
  }
}

function cueOf(order: readonly TimelineCueRef[], entryId: number): TimelineCueRef | undefined {
  return order.find((c) => c.entryId === entryId)
}

/** ⏎, or a second click: a filled cell off, an empty one on from its nearest neighbour. */
export function toggleOps(
  order: readonly TimelineCueRef[],
  cells: TimelineCells,
  column: TimelineColumn,
  entryId: number,
): TimelineEditOp[] {
  const cue = cueOf(order, entryId)
  if (cue == null || !editsColumn(column)) return []
  const here = filledOf(cells, entryId, column)
  if (here) return [offOp(column, cue.cueId, here)].filter((op): op is TimelineEditOp => op != null)
  const op = onOp(column, cue.cueId, nearestFilled(order, cells, column, entryId))
  return op ? [op] : []
}

/** *On*: every empty selected cell filled, each from its column's nearest filled cell. */
export function onOps(
  order: readonly TimelineCueRef[],
  cells: TimelineCells,
  columns: ReadonlyMap<string, TimelineColumn>,
  selected: readonly TimelineCellAddress[],
): TimelineEditOp[] {
  const list = new OpList()
  for (const { entryId, columnId } of inOrder(order, selected)) {
    const column = columns.get(columnId)
    const cue = cueOf(order, entryId)
    if (column == null || cue == null || !editsColumn(column) || filledOf(cells, entryId, column)) continue
    list.add(columnId, cue.cueId, onOp(column, cue.cueId, nearestFilled(order, cells, column, entryId)))
  }
  return list.ops
}

/** *Off* (⌫): every filled selected cell off. */
export function offOps(
  order: readonly TimelineCueRef[],
  cells: TimelineCells,
  columns: ReadonlyMap<string, TimelineColumn>,
  selected: readonly TimelineCellAddress[],
): TimelineEditOp[] {
  const list = new OpList()
  for (const { entryId, columnId } of inOrder(order, selected)) {
    const column = columns.get(columnId)
    const cue = cueOf(order, entryId)
    const here = column == null ? null : filledOf(cells, entryId, column)
    if (column == null || cue == null || !editsColumn(column) || here == null) continue
    list.add(columnId, cue.cueId, offOp(column, cue.cueId, here))
  }
  return list.ops
}

/**
 * *Fill ↓* (⌘D): each column's top selected row copied down its selection — a filled top fills the
 * empty cells below with its settings, an empty one empties them.
 */
export function fillDownOps(
  order: readonly TimelineCueRef[],
  cells: TimelineCells,
  columns: ReadonlyMap<string, TimelineColumn>,
  selected: readonly TimelineCellAddress[],
): TimelineEditOp[] {
  const list = new OpList()
  const byColumn = new Map<string, TimelineCellAddress[]>()
  for (const address of inOrder(order, selected)) {
    const group = byColumn.get(address.columnId) ?? []
    group.push(address)
    byColumn.set(address.columnId, group)
  }
  for (const [columnId, group] of byColumn) {
    const column = columns.get(columnId)
    if (column == null || !editsColumn(column) || group.length < 2) continue
    const top = filledOf(cells, group[0].entryId, column)
    const topCue = cueOf(order, group[0].entryId)
    for (const { entryId } of group.slice(1)) {
      const cue = cueOf(order, entryId)
      if (cue == null || cue.cueId === topCue?.cueId) continue
      const here = filledOf(cells, entryId, column)
      if (top != null && here == null) list.add(columnId, cue.cueId, onOp(column, cue.cueId, top))
      else if (top == null && here != null) list.add(columnId, cue.cueId, offOp(column, cue.cueId, here))
    }
  }
  return list.ops
}

/**
 * A run's end dragged from [fromEntryId] to [toEntryId]: down, each empty cell crossed is filled with
 * the dragged cell's settings; up, the cells crossed are emptied, the one under the pointer left as
 * the run's new end. Each crossed cell's fate, for the ghosts drawn before release.
 */
export function dragPlan(
  order: readonly TimelineCueRef[],
  cells: TimelineCells,
  column: TimelineColumn,
  fromEntryId: number,
  toEntryId: number,
): Map<number, 'fill' | 'empty'> {
  const plan = new Map<number, 'fill' | 'empty'>()
  const from = order.findIndex((c) => c.entryId === fromEntryId)
  const to = order.findIndex((c) => c.entryId === toEntryId)
  if (from < 0 || to < 0 || from === to) return plan
  if (to > from) {
    for (let i = from + 1; i <= to; i++) if (!filledOf(cells, order[i].entryId, column)) plan.set(order[i].entryId, 'fill')
  } else {
    for (let i = to + 1; i <= from; i++) if (filledOf(cells, order[i].entryId, column)) plan.set(order[i].entryId, 'empty')
  }
  return plan
}

export function dragOps(
  order: readonly TimelineCueRef[],
  cells: TimelineCells,
  column: TimelineColumn,
  fromEntryId: number,
  toEntryId: number,
): TimelineEditOp[] {
  const dragged = filledOf(cells, fromEntryId, column)
  const list = new OpList()
  for (const [entryId, fate] of dragPlan(order, cells, column, fromEntryId, toEntryId)) {
    const cue = cueOf(order, entryId)
    if (cue == null) continue
    list.add(
      column.id,
      cue.cueId,
      fate === 'fill' ? onOp(column, cue.cueId, dragged) : offOp(column, cue.cueId, filledOf(cells, entryId, column)),
    )
  }
  return list.ops
}

/**
 * Whether [entry] holds [ref]'s cell: any layer of a Look, which lands whole; a palette's naming its
 * target, or naming none for its own heads (§3.2).
 */
export function entryHolds(entry: Pick<TimelineEntry, 'layers'>, ref: TimelineCellRef): boolean {
  return entry.layers.some(
    (layer) => layerIsOf(layer, ref.source) && (ref.source.kind === 'LOOK' || fillsTarget(layer, ref.target ?? null)),
  )
}

function layerIsOf(layer: TimelineLayer, source: TimelineSourceRef): boolean {
  return layer.source.kind === source.kind && layer.source.id === source.id
}

function fillsTarget(layer: TimelineLayer, target: CueTarget | null): boolean {
  if (target == null) return layer.targets.length === 0
  return layer.targets.some((t) => t.type === target.type && t.key === target.key)
}

/** What *Replace with…* would do in each selected cue: swap its layer in place, or take it off where the cue holds the replacement already. */
export function replacePlan(
  entries: readonly Pick<TimelineEntry, 'cueId' | 'layers'>[],
  cueIds: readonly number[],
  from: TimelineCellRef,
  to: TimelineCellRef,
): { swaps: number[]; holds: number[] } {
  const swaps: number[] = []
  const holds: number[] = []
  for (const cueId of new Set(cueIds)) {
    const entry = entries.find((e) => e.cueId === cueId)
    if (entry == null || !entryHolds(entry, from)) continue
    if (entryHolds(entry, to)) holds.push(cueId)
    else swaps.push(cueId)
  }
  return { swaps, holds }
}

/** The filled layer cells of a selection, each layer once, with its cue. */
export function selectedLayers(
  order: readonly TimelineCueRef[],
  cells: TimelineCells,
  columns: ReadonlyMap<string, TimelineColumn>,
  selected: readonly TimelineCellAddress[],
): { cueId: number; layer: TimelineLayer }[] {
  const out: { cueId: number; layer: TimelineLayer }[] = []
  const seen = new Set<number>()
  for (const { entryId, columnId } of inOrder(order, selected)) {
    const column = columns.get(columnId)
    const cue = cueOf(order, entryId)
    if (column == null || cue == null || (column.kind !== 'look' && column.kind !== 'palette')) continue
    const filled = filledOf(cells, entryId, column)
    if (filled?.kind !== 'layer' || seen.has(filled.layer.id)) continue
    seen.add(filled.layer.id)
    out.push({ cueId: cue.cueId, layer: filled.layer })
  }
  return out
}

/** One field's value across several layers, or `mixed` where they disagree (§4.4). */
export function sharedValue<T>(values: readonly T[]): T | 'mixed' | undefined {
  if (values.length === 0) return undefined
  const first = JSON.stringify(values[0])
  return values.every((v) => JSON.stringify(v) === first) ? values[0] : 'mixed'
}

/** [selected] in the stack's row order, then the columns' order as given. */
function inOrder(order: readonly TimelineCueRef[], selected: readonly TimelineCellAddress[]): TimelineCellAddress[] {
  const rank = new Map(order.map((c, i) => [c.entryId, i]))
  return [...selected].sort((a, b) => (rank.get(a.entryId) ?? 0) - (rank.get(b.entryId) ?? 0))
}

/** *Q5*, *Q4 to Q6* for a block, *Q2, Q5 and Q7* otherwise — the cues a gesture touched, as a toast names them. */
export function cueRangeWords(order: readonly TimelineCueRef[], cueIds: readonly number[], label: (cueId: number) => string): string {
  const ids = [...new Set(cueIds)]
  const places = ids.map((id) => order.findIndex((c) => c.cueId === id)).sort((a, b) => a - b)
  const labels = places.filter((i) => i >= 0).map((i) => label(order[i].cueId))
  if (labels.length === 0) return ''
  if (labels.length === 1) return labels[0]
  const block = places.every((p, i) => i === 0 || p === places[i - 1] + 1)
  if (block) return `${labels[0]} to ${labels[labels.length - 1]}`
  return `${labels.slice(0, -1).join(', ')} and ${labels[labels.length - 1]}`
}
