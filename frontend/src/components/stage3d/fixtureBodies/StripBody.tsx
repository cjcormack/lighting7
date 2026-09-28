import { BODY_LENS_COLOR, housingColor } from './palette'
import { PixelStrip } from './PixelStrip'
import type { FixtureBodyProps } from './types'

// Design fallback dimensions when the backend sends no real size. Exported so
// the wash director can lay out per-pixel origins identically to PixelStrip.
export const STRIP_LEN = 0.6
export const STRIP_DEPTH = 0.06
export const STRIP_HEIGHT = 0.05

// Strip body sized per-axis from the fixture's real dimensions (lengthM is the
// long horizontal axis → local X). A multi-element strip renders one lens
// segment per element (PixelStrip); otherwise a single lens.
export function StripBody({
  active,
  headRef,
  lensRef,
  dims,
  pixelCount,
  pixelColorsRef,
}: FixtureBodyProps) {
  const L = dims?.lengthM ?? STRIP_LEN
  const H = dims?.heightM ?? STRIP_HEIGHT
  const D = dims?.widthM ?? STRIP_DEPTH

  if (pixelCount && pixelCount > 1 && pixelColorsRef) {
    return (
      <PixelStrip
        active={active}
        pixelCount={pixelCount}
        lengthM={L}
        heightM={H}
        depthM={D}
        headRef={headRef}
        colorsRef={pixelColorsRef}
      />
    )
  }

  return (
    <group ref={headRef}>
      <mesh>
        <boxGeometry args={[L, H, D]} />
        <meshStandardMaterial color={housingColor(active)} />
      </mesh>
      <mesh ref={lensRef} position={[0, -H / 2 - 0.001, 0]}>
        <boxGeometry args={[L * 0.95, 0.01, D * 0.85]} />
        <meshBasicMaterial color={BODY_LENS_COLOR} />
      </mesh>
    </group>
  )
}
