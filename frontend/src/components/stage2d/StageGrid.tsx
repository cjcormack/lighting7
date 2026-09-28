import { memo, useMemo } from 'react'
import type { Extent } from '../../lib/stageProjection'

interface StageGridProps {
  /** The region to cover — the *visible* extent, not the viewBox. */
  extent: Extent
  /** Grid step in metres. Matches the snap step, so what you see is what you get. */
  stepM: number
  mPerPx: number
}

/** Below this on-screen spacing, minor lines are dropped as unreadable mush. */
const MIN_MINOR_PX = 6
/** Every Nth line is emphasised. */
const MAJOR_EVERY = 5

function linesBetween(min: number, max: number, step: number): number[] {
  const out: number[] = []
  const first = Math.ceil(min / step) * step
  for (let v = first; v <= max; v += step) out.push(v)
  return out
}

/**
 * The metre grid.
 *
 * Unlike the decorative 24-pixel CSS gradient this replaces, these lines are at
 * real multiples of the live snap step — so the grid the user sees is the grid
 * their drags land on.
 *
 * Minor lines are dropped when they'd be closer than a few pixels apart: a
 * 0.1 m grid across a 10 m stage at fit-zoom is ~100 lines per axis of grey
 * mush, and the decimation is also what bounds the element count as the user
 * zooms out.
 */
export const StageGrid = memo(function StageGrid({ extent, stepM, mPerPx }: StageGridProps) {
  const majorStep = stepM * MAJOR_EVERY
  const showMinor = stepM / mPerPx >= MIN_MINOR_PX

  const { minorH, minorV, majorH, majorV } = useMemo(() => {
    const isMajor = (v: number) => Math.abs(v / majorStep - Math.round(v / majorStep)) < 1e-9
    const allH = showMinor ? linesBetween(extent.hMin, extent.hMax, stepM) : []
    const allV = showMinor ? linesBetween(extent.vMin, extent.vMax, stepM) : []
    return {
      minorH: allH.filter((v) => !isMajor(v)),
      minorV: allV.filter((v) => !isMajor(v)),
      majorH: linesBetween(extent.hMin, extent.hMax, majorStep),
      majorV: linesBetween(extent.vMin, extent.vMax, majorStep),
    }
  }, [extent, stepM, majorStep, showMinor])

  // `vector-effect` is NOT an inherited SVG property (unlike `stroke-width`), so
  // it has to sit on every line. Setting it on a wrapping <g> silently leaves the
  // children scaling their strokes with the viewBox — with a metre viewBox that
  // renders `strokeWidth={1}` as a one-METRE-wide band, which looks like a grey
  // wash over the whole canvas rather than a grid.
  const hairline = { strokeWidth: 1, vectorEffect: 'non-scaling-stroke' } as const

  return (
    <g pointerEvents="none">
      <g className="stroke-foreground/[0.07]">
        {minorH.map((h) => (
          <line key={`mnh${h}`} x1={h} x2={h} y1={extent.vMin} y2={extent.vMax} {...hairline} />
        ))}
        {minorV.map((v) => (
          <line key={`mnv${v}`} x1={extent.hMin} x2={extent.hMax} y1={v} y2={v} {...hairline} />
        ))}
      </g>
      <g className="stroke-foreground/[0.16]">
        {majorH.map((h) => (
          <line key={`mjh${h}`} x1={h} x2={h} y1={extent.vMin} y2={extent.vMax} {...hairline} />
        ))}
        {majorV.map((v) => (
          <line key={`mjv${v}`} x1={extent.hMin} x2={extent.hMax} y1={v} y2={v} {...hairline} />
        ))}
      </g>
      {/* Datum axes: h = 0 is the stage centre line, v = 0 the downstage edge
          (plan) or the deck (elevations). */}
      <g className="stroke-foreground/30">
        <line x1={0} x2={0} y1={extent.vMin} y2={extent.vMax} {...hairline} />
        <line x1={extent.hMin} x2={extent.hMax} y1={0} y2={0} {...hairline} />
      </g>
    </g>
  )
})
