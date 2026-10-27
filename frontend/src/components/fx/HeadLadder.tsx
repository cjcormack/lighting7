import { useState } from 'react'
import { cn } from '@/lib/utils'
import type { FxPreviewFx, FxPreviewRow } from '@/store/fxPreview'
import { EditorReadout } from '../editor/EditorReadout'
import { startNumbers } from './acrossHeads'
import { useHoveredRow, type HoveredRowStore } from './fxGraphLink'

/** One head on the ladder: its key, its name and when it starts, in cycles. */
export interface LadderHead {
  key: string
  label: string
  start: number
}

/** The heads the ladder draws before the rest fold into *+n*. */
const MAX_HEADS = 16

/** A colour a start, so the heads that start together read as one. */
const START_COLOURS = [
  'bg-violet-400',
  'bg-sky-400',
  'bg-amber-400',
  'bg-green-300',
  'bg-pink-400',
  'bg-red-300',
  'bg-yellow-200',
  'bg-violet-300',
]

/**
 * The preview's heads as the ladder draws them (P2): each row the FX paints, at the offset the desk's
 * `ladder.spread` gives it, in rig order — the answer's rows come in the FX's Order, which would read
 * every pattern as a chase.
 */
export function previewLadderHeads(fx: FxPreviewFx, rows: readonly FxPreviewRow[]): LadderHead[] {
  return rows
    .flatMap((row, r) => {
      const start = fx.ladder.spread[r]
      return start == null ? [] : [{ head: { key: row.key, label: row.label, start }, rigIndex: row.rigIndex }]
    })
    .sort((a, b) => a.rigIndex - b.rigIndex)
    .map(({ head }) => head)
}

/**
 * **The head ladder** (fx-settings plan D10): the selection's heads in rig order, each numbered by
 * when it starts — 1 first, heads that start together sharing a number and a colour. In the FX
 * editor's inspector they are the desk's preview ([previewLadderHeads]) and hovering one links it to
 * its graph row; elsewhere they come from `lib/spreadShape.ts`. Null [heads] is a preview not yet
 * answered.
 */
export function HeadLadder({
  heads,
  caption,
  hoveredRow = null,
  onHoverRow,
}: {
  heads: readonly LadderHead[] | null
  /** Under the heads — whose they are, where that is not the selection's own. */
  caption?: string
  hoveredRow?: HoveredRowStore | null
  onHoverRow?: (key: string | null) => void
}) {
  const fromGraph = useHoveredRow(hoveredRow)
  const [own, setOwn] = useState<string | null>(null)
  const hovered = own ?? fromGraph
  const hover = (key: string | null) => {
    setOwn(key)
    onHoverRow?.(key)
  }
  if (heads == null) return <EditorReadout>Asking the desk for the starts…</EditorReadout>
  const numbers = startNumbers(heads.map((h) => h.start))
  const shown = heads.slice(0, MAX_HEADS)
  const more = heads.length - shown.length
  return (
    <div className="flex flex-col gap-1">
      <div
        role="list"
        aria-label="Head ladder"
        data-head-ladder
        className="flex flex-wrap items-start gap-1"
      >
        {shown.map((head, i) => {
          const n = numbers[i] ?? 1
          return (
            <div
              key={head.key}
              role="listitem"
              aria-label={`${head.label} starts ${ordinal(n)}`}
              data-ladder-head={head.key}
              data-start={n}
              title={`${head.label}: starts ${ordinal(n)}, ${Math.round(head.start * 100)} % of a cycle in`}
              onPointerEnter={onHoverRow == null ? undefined : () => hover(head.key)}
              onPointerLeave={onHoverRow == null ? undefined : () => hover(null)}
              className="flex w-[26px] flex-col items-center gap-0.5"
            >
              <span
                className={cn(
                  'flex size-[20px] items-center justify-center rounded-full font-mono text-[10px] font-semibold text-neutral-950',
                  START_COLOURS[(n - 1) % START_COLOURS.length],
                  hovered === head.key && 'ring-2 ring-foreground',
                )}
              >
                {n}
              </span>
              <span className="max-w-full truncate font-mono text-[9px] text-muted-foreground">{head.label}</span>
            </div>
          )
        })}
        {more > 0 && <span className="self-center text-[11px] tabular-nums text-muted-foreground">+{more}</span>}
      </div>
      {caption != null && <EditorReadout>{caption}</EditorReadout>}
    </div>
  )
}

function ordinal(n: number): string {
  const tens = n % 100
  if (tens >= 11 && tens <= 13) return `${n}th`
  return `${n}${['th', 'st', 'nd', 'rd'][n % 10] ?? 'th'}`
}
