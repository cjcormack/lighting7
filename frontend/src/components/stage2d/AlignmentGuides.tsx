import { memo } from 'react'
import type { Extent } from '../../lib/stageProjection'

interface AlignmentGuidesProps {
  /** Screen-metre h value currently snapped to, if any. */
  hitH: number | null
  hitV: number | null
  /** The visible region, so a guide spans the whole canvas. */
  extent: Extent
}

/**
 * The dashed lines that appear while a drag is aligned with another object.
 *
 * Purely feedback — the snapping itself has already happened in
 * `lib/stageSnapping`. Without the line the snap is invisible and reads as the
 * drag sticking for no reason.
 */
export const AlignmentGuides = memo(function AlignmentGuides({
  hitH,
  hitV,
  extent,
}: AlignmentGuidesProps) {
  if (hitH == null && hitV == null) return null
  return (
    <g pointerEvents="none" stroke="#ffe082" strokeWidth={1} strokeDasharray="4 3">
      {hitH != null && (
        <line
          x1={hitH}
          x2={hitH}
          y1={extent.vMin}
          y2={extent.vMax}
          vectorEffect="non-scaling-stroke"
        />
      )}
      {hitV != null && (
        <line
          x1={extent.hMin}
          x2={extent.hMax}
          y1={hitV}
          y2={hitV}
          vectorEffect="non-scaling-stroke"
        />
      )}
    </g>
  )
})
