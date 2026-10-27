import { useState } from 'react'
import { cn } from '@/lib/utils'
import type { FxPreviewFx, FxPreviewRow, FxPreviewRung } from '@/store/fxPreview'
import { EditorLabel } from '../editor/EditorLabel'
import { EditorReadout } from '../editor/EditorReadout'
import { useHoveredRow, type HoveredRowStore } from './fxGraphLink'
import type { SpreadUnit } from '@/lib/spreadShape'

/** The ladder's five rows, one per step of the spread, in the order the desk takes them (§3.3). */
const STEPS = [
  { step: 'order', label: 'Order' },
  { step: 'segments', label: 'Segments' },
  { step: 'wings', label: 'Wings' },
  { step: 'parts', label: 'Parts' },
  { step: 'spread', label: 'Spread' },
] as const

type LadderStep = (typeof STEPS)[number]['step']

export interface LadderDot {
  key: string
  label: string
  /** 0 to 1 across the row. */
  at: number
  title: string
}

/**
 * The offset ladder's dots for one step, read from the desk's answer (P2): an element at its rank
 * over the step's rank count — at its offset for Spread, over the largest offset or one cycle,
 * whichever is longer. A row the FX paints no rung on (a split-off copy, which carries no ranks) has
 * no dot on the first four.
 */
export function ladderDots(fx: FxPreviewFx, rows: readonly FxPreviewRow[], step: LadderStep): LadderDot[] {
  const painted = rows.flatMap((row, r) => (fx.offsets[r] == null ? [] : [{ row, r }]))
  if (step === 'spread') {
    const longest = Math.max(1, ...painted.map(({ r }) => fx.ladder.spread[r] ?? 0))
    return painted.flatMap(({ row, r }) => {
      const offset = fx.ladder.spread[r]
      return offset == null
        ? []
        : [
            {
              key: row.key,
              label: row.label,
              at: offset / longest,
              title: `${row.label}: ${Math.round(offset * 100)} % of a cycle`,
            },
          ]
    })
  }
  const rung: FxPreviewRung = fx.ladder[step]
  return painted.flatMap(({ row, r }) => {
    const rank = rung.ranks[r]
    const count = rung.counts[r]
    if (rank == null || count == null) return []
    return [
      {
        key: row.key,
        label: row.label,
        at: count > 1 ? rank / count : 0,
        title: `${row.label}: rank ${Math.round(rank * 100) / 100 + 1} of ${count}`,
      },
    ]
  })
}

/** The step's rank count, as the first row the FX paints says it. */
function countOf(fx: FxPreviewFx, rows: readonly FxPreviewRow[], step: Exclude<LadderStep, 'spread'>): number | null {
  const r = rows.findIndex((_, i) => fx.offsets[i] != null && fx.ladder[step].counts[i] != null)
  return r < 0 ? null : fx.ladder[step].counts[r]
}

/**
 * **The offset ladder** (FX editor plan D9, §4.5): five dot rows, one per step of the spread — Order,
 * Segments, Wings, Parts, then Spread — each element a dot where that step leaves it, so what
 * Segments, Wings and Parts do can be read before they are run. Drawn from the preview's `ladder`,
 * never computed. Hovering a dot links it to its row on the graph, and the graph's hovered row lights
 * its dots.
 */
export function OffsetLadder({
  ladder,
  spread,
  spreadUnit = 'CYCLE',
  hoveredRow,
  onHoverRow,
}: {
  ladder: { fx: FxPreviewFx; rows: readonly FxPreviewRow[] } | null
  spread: number
  /** What [spread] counts (fx-settings plan D6): its last rung reads in steps under `STEP`. */
  spreadUnit?: SpreadUnit
  /** The graph's hovered row, read here alone so the pointer crossing rows redraws only the dots. */
  hoveredRow: HoveredRowStore | null
  onHoverRow: (key: string | null) => void
}) {
  const fromGraph = useHoveredRow(hoveredRow)
  const [own, setOwn] = useState<string | null>(null)
  const hovered = own ?? fromGraph
  const hover = (key: string | null) => {
    setOwn(key)
    onHoverRow(key)
  }
  return (
    <div data-offset-ladder className="flex flex-col gap-1">
      <div className="flex items-center gap-2">
        <EditorLabel>Offset ladder</EditorLabel>
        <span className="flex-1" />
        <span className="text-[10.5px] text-muted-foreground">each element after each step</span>
      </div>
      {ladder == null ? (
        <EditorReadout>Asking the desk for the offsets…</EditorReadout>
      ) : (
        <div className="grid grid-cols-[58px_minmax(0,1fr)_38px] items-center gap-x-2 gap-y-1 text-[10.5px] text-muted-foreground">
          {STEPS.map(({ step, label }) => {
            const dots = ladderDots(ladder.fx, ladder.rows, step)
            const count =
              step !== 'spread'
                ? countOf(ladder.fx, ladder.rows, step)
                : spreadUnit === 'STEP'
                  ? `${Math.round(spread * 100) / 100} ${spread === 1 ? 'step' : 'steps'}`
                  : `${Math.round(spread * 100)} %`
            return [
              <span key={`${step}-label`}>{label}</span>,
              <div key={`${step}-track`} data-ladder-step={step} className="relative h-3 border-b">
                {dots.map((dot) => (
                  <i
                    key={dot.key}
                    data-ladder-dot={dot.key}
                    data-at={Math.round(dot.at * 1000) / 1000}
                    title={dot.title}
                    onPointerEnter={() => hover(dot.key)}
                    onPointerLeave={() => hover(null)}
                    style={{ left: `calc(4px + ${dot.at} * (100% - 8px))` }}
                    className={cn(
                      'absolute top-0.5 -ml-[3.5px] size-[7px] rounded-full bg-violet-500/80',
                      hovered === dot.key && 'bg-background ring-2 ring-violet-500',
                    )}
                  />
                ))}
              </div>,
              <span key={`${step}-count`} className="text-right tabular-nums">
                {count ?? '—'}
              </span>,
            ]
          })}
        </div>
      )}
    </div>
  )
}
