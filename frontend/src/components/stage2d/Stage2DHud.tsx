import { Maximize2, Minus, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { ScreenPoint, StageProjection } from '../../lib/stageProjection'

interface Stage2DHudProps {
  projection: StageProjection
  /** Pointer position in screen-metres, or null when the pointer is outside. */
  cursor: ScreenPoint | null
  /** Snap step in metres, or null when snapping is off. */
  snapStepM: number | null
  onFit: () => void
  onZoomIn: () => void
  onZoomOut: () => void
  /** Shown when the selected object can't be adjusted in this projection. */
  notice?: string | null
}

/**
 * Chrome over the canvas: the axis legend, a live cursor readout in metres, the
 * snap step, and the zoom controls.
 *
 * DOM rather than SVG, so text isn't subject to the metre viewBox and needs no
 * inverse scaling.
 *
 * The axis legend is not decoration. The side elevation looks along −X, and
 * whether "upstage" runs left or right in a section is exactly the sort of thing
 * that's ambiguous in prose but obvious once labelled on the axis.
 */
export function Stage2DHud({
  projection,
  cursor,
  snapStepM,
  onFit,
  onZoomIn,
  onZoomOut,
  notice,
}: Stage2DHudProps) {
  return (
    <>
      <div className="pointer-events-none absolute left-2 top-2 flex flex-col gap-1">
        <div className="rounded bg-background/80 px-2 py-1 font-mono text-[10px] leading-relaxed text-muted-foreground backdrop-blur-sm">
          <div>→ {projection.hAxisLabel}</div>
          <div>↑ {projection.vAxisLabel}</div>
        </div>
        {notice && (
          <div className="max-w-56 rounded bg-amber-500/15 px-2 py-1 text-[10px] text-amber-600 backdrop-blur-sm dark:text-amber-400">
            {notice}
          </div>
        )}
      </div>

      <div className="pointer-events-none absolute bottom-2 left-2 rounded bg-background/80 px-2 py-1 font-mono text-[10px] text-muted-foreground backdrop-blur-sm">
        {cursor
          ? // v is screen-down; negate so the readout reads in lighting terms.
            `${formatM(projection.h.sign * cursor.h)}, ${formatM(projection.v.sign * cursor.v)} m`
          : '—'}
        <span className="ml-2 border-l pl-2">
          {snapStepM != null ? `snap ${snapStepM} m` : 'snap off'}
        </span>
      </div>

      <div className="absolute bottom-2 right-2 flex gap-1">
        <Button size="icon" variant="outline" className="size-7" onClick={onZoomOut} aria-label="Zoom out">
          <Minus className="size-3.5" />
        </Button>
        <Button size="icon" variant="outline" className="size-7" onClick={onZoomIn} aria-label="Zoom in">
          <Plus className="size-3.5" />
        </Button>
        <Button size="icon" variant="outline" className="size-7" onClick={onFit} aria-label="Fit to stage">
          <Maximize2 className="size-3.5" />
        </Button>
      </div>
    </>
  )
}

function formatM(v: number): string {
  // Avoid "-0.00" for values that are zero to within display precision.
  const rounded = Math.abs(v) < 0.005 ? 0 : v
  return rounded.toFixed(2)
}
