import type { StackMoveInDarkPlan } from '@/api/cueStacksApi'

/**
 * The Timeline's **Dark** column (lighting7 move-in-dark plan D11, design §5.6): one span per move
 * the stack's static plan makes, from the entry it happens in to the entry it is for, drawn in the
 * run-bar idiom down the rows — markers included, which a span crosses. The desk owns the plan
 * (P2); this lays its spans out and nothing more. Pure.
 */

/** One move: the heads moved in [fromEntryId] for [toEntryId]'s cue, in the lane it is drawn in. */
export interface DarkSpan {
  fromEntryId: number
  toEntryId: number
  toCueId: number
  heads: number
  lane: number
}

/** What a row draws of one span: a segment, and whether the span starts or ends on it. */
export interface DarkSpanMark {
  span: DarkSpan
  start: boolean
  end: boolean
}

export interface DarkColumn {
  spans: DarkSpan[]
  /** How many lanes side by side the column needs: spans that share a row cannot share a lane. */
  lanes: number
  /** Each entry's segments — cue rows and markers alike — in lane order. */
  byEntry: ReadonlyMap<number, readonly DarkSpanMark[]>
}

export const NO_DARK_COLUMN: DarkColumn = { spans: [], lanes: 0, byEntry: new Map() }

/**
 * The column for [plan] over the stack's list, [order] being every entry id in list order, markers
 * included. A move whose entry comes after its target in the list (a loop round the end) is drawn
 * from it to the last row and from the first row to its target. A move naming an entry the list
 * lacks — a plan a moment older than the list — is left out.
 */
export function darkColumn(plan: StackMoveInDarkPlan | undefined, order: readonly number[]): DarkColumn {
  if (plan == null || !plan.rigLoaded) return NO_DARK_COLUMN
  const index = new Map(order.map((id, i) => [id, i]))
  const spans: (DarkSpan & { rows: number[] })[] = []
  for (const entry of plan.entries) {
    const to = index.get(entry.entryId)
    if (to == null) continue
    const counts = new Map<number, number>()
    for (const head of entry.heads) counts.set(head.fromEntryId, (counts.get(head.fromEntryId) ?? 0) + 1)
    for (const [fromEntryId, heads] of counts) {
      const from = index.get(fromEntryId)
      if (from == null) continue
      spans.push({ fromEntryId, toEntryId: entry.entryId, toCueId: entry.cueId, heads, lane: 0, rows: rowsOf(from, to, order.length) })
    }
  }
  // Earliest first, then the shorter: each takes the first lane none of its rows is taken in.
  spans.sort((a, b) => a.rows[0] - b.rows[0] || a.rows.length - b.rows.length || a.toEntryId - b.toEntryId)
  const taken: Set<number>[] = []
  for (const span of spans) {
    let lane = taken.findIndex((rows) => span.rows.every((r) => !rows.has(r)))
    if (lane < 0) {
      lane = taken.length
      taken.push(new Set())
    }
    for (const r of span.rows) taken[lane].add(r)
    span.lane = lane
  }
  const byEntry = new Map<number, DarkSpanMark[]>()
  const out: DarkSpan[] = []
  for (const { rows, ...span } of spans) {
    out.push(span)
    const first = index.get(span.fromEntryId)!
    const last = index.get(span.toEntryId)!
    for (const r of rows) {
      const marks = byEntry.get(order[r]) ?? []
      marks.push({ span, start: r === first, end: r === last })
      byEntry.set(order[r], marks)
    }
  }
  for (const marks of byEntry.values()) marks.sort((a, b) => a.span.lane - b.span.lane)
  return { spans: out, lanes: taken.length, byEntry }
}

/** The row indices a move covers, from [from] to [to], round the end of a list of [count] when it loops. */
function rowsOf(from: number, to: number, count: number): number[] {
  const rows: number[] = []
  if (from <= to) for (let r = from; r <= to; r++) rows.push(r)
  else {
    for (let r = from; r < count; r++) rows.push(r)
    for (let r = 0; r <= to; r++) rows.push(r)
  }
  return rows
}

/** The words at a span's start: `☾ 4 → Q12`. */
export function darkSpanStartWords(span: DarkSpan, targetLabel: string): string {
  return `☾ ${span.heads} → ${targetLabel}`
}
