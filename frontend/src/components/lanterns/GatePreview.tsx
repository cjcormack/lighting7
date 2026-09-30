import { useId } from 'react'
import { bladeLine } from '@/components/stage3d/beamMask'
import type { ShutterBlade } from '@/lib/lanterns'

interface GatePreviewProps {
  /** The four blades, top · bottom · left · right, or null for all out. */
  blades: readonly ShutterBlade[] | null
  /** The frame's turn about the beam — the gate's, or a PAR lamp's — degrees. */
  turnDeg: number
  /** The iris's open fraction, 1 open. */
  iris: number
  /** An oval's narrow axis over its wide one; null for a round field. */
  ovalRatio: number | null
  className?: string
}

/**
 * The beam's cross-section, live: the field, the iris, the blades — drawn from `beamMask.ts`'s own
 * `bladeLine`, in the frame the pool and the haze are cut in, so the picture and the stage cannot
 * disagree about where a blade sits. Seen from behind the lantern looking along its beam: the top of
 * the beam up, its left on the left (the frame's `u` is the viewer's left, so screen x is −u).
 */
export function GatePreview({ blades, turnDeg, iris, ovalRatio, className }: GatePreviewProps) {
  const clipId = useId()
  const t = (turnDeg * Math.PI) / 180
  const c = Math.cos(t)
  const s = Math.sin(t)
  // The frame turned by the gate, then into the picture: x = −u, y = −v (SVG's y runs down).
  const screen = (u: number, v: number): [number, number] => [-(c * u - s * v), -(s * u + c * v)]
  const pt = (u: number, v: number) => screen(u, v).join(',')
  const bladePolys = (blades ?? [])
    .map((b, i) => {
      if (!(b.depth > 0)) return null
      const { px, py, nx, ny } = bladeLine(i, b.depth, b.angleDeg)
      // The half-plane past the blade's edge, as a big quad: along the edge both ways, then out.
      const tx = -ny
      const ty = nx
      const far = 3
      return [
        pt(px + tx * far, py + ty * far),
        pt(px - tx * far, py - ty * far),
        pt(px - tx * far + nx * far, py - ty * far + ny * far),
        pt(px + tx * far + nx * far, py + ty * far + ny * far),
      ].join(' ')
    })
    .filter((p): p is string => p != null)
  const ry = ovalRatio ?? 1
  // The oval's wide axis is the turned frame's u, which lands on screen along (−cos t, −sin t):
  // SVG's rotate(t + 180) — and an ellipse is its own half-turn, so rotate(t).
  const turnScreenDeg = turnDeg
  return (
    <svg
      viewBox="-1.3 -1.35 2.6 2.6"
      role="img"
      aria-label="The beam's cross-section, seen from behind the lantern"
      className={className}
    >
      <defs>
        <clipPath id={clipId}>
          <ellipse rx={1} ry={ry} transform={`rotate(${turnScreenDeg})`} />
        </clipPath>
      </defs>
      <ellipse rx={1} ry={ry} transform={`rotate(${turnScreenDeg})`} className="fill-amber-300/25" />
      <g clipPath={`url(#${clipId})`}>
        <circle r={Math.max(0.02, Math.min(1, iris))} className="fill-amber-300/50" />
        {bladePolys.map((points, i) => (
          <polygon key={i} points={points} className="fill-neutral-800 dark:fill-neutral-900" />
        ))}
      </g>
      <ellipse
        rx={1}
        ry={ry}
        transform={`rotate(${turnScreenDeg})`}
        className="fill-none stroke-muted-foreground"
        strokeWidth={0.02}
      />
      <text y={-1.16} fontSize={0.13} textAnchor="middle" className="fill-muted-foreground">
        top of the beam
      </text>
    </svg>
  )
}
