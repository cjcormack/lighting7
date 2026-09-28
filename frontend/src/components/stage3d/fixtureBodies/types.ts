import type { Color, Group, Mesh } from 'three'

/** Physical bounding size of a fixture in metres; `lengthM` is the long axis. */
export interface FixtureBodyDims {
  lengthM: number
  widthM: number
  heightM: number
}

export interface FixtureBodyProps {
  active: boolean
  /** Tilt node — rotated about its local X by the signed tilt angle. */
  headRef: React.RefObject<Group | null>
  /** Pan node: the yoke, carrying the arms *and* the head. Bodies that model a
   *  real yoke publish this so the arms swing with the head instead of the head
   *  swinging out from between them. Bodies without one leave it unset and take
   *  the combined pan+tilt quaternion on `headRef` instead. */
  yokeRef?: React.RefObject<Group | null>
  lensRef: React.RefObject<Mesh | null>
  /** Which way the head emits along its own Y. `1` for a tilting head, whose
   *  rest pose is up the body axis away from the base; `-1` for a rigid body
   *  whose lens simply faces down. Bodies mirror their housing taper and lens
   *  position to match. Chosen by FixtureModel from the presence of a tilt
   *  descriptor — never from `kind`, because a Source 4 Revolution is a PROFILE
   *  that tilts and a Scantastic 4 is a SCANNER that does. */
  emitAxis?: 1 | -1
  /** Real fixture size. Undefined ⇒ the body keeps its hard-coded design size
   *  (e.g. an older backend that doesn't send dimensions). */
  dims?: FixtureBodyDims
  /** Number of independently-coloured elements (pixels/sections). >1 selects a
   *  per-pixel body where the kind supports it (STRIP). Others ignore it. */
  pixelCount?: number
  /** Imperative sink the colour sync uses to drive per-pixel lens materials. */
  pixelColorsRef?: React.RefObject<PixelColorWriter | null>
}

/**
 * Imperative per-pixel colour sink published by a multi-pixel body (PixelStrip).
 * Drives both the body lens face and the small per-head glow from one call.
 * The colour sync calls `reset()` then `setPixel()` per live element each update
 * so a pixel dropping to zero is explicitly driven dark — imperative material
 * mutation gets no React default-prop behaviour.
 */
export interface PixelColorWriter {
  count: number
  /** `intensity` is the head's effective brightness 0..1 (dimmer × colour). */
  setPixel(i: number, color: Color, intensity: number): void
  /** Drive every head dark (off lens + invisible glow). */
  reset(): void
}

/**
 * Uniform scale sizing a body's design geometry (its largest design extent
 * `designSizeM`) to the fixture's real bounding extent. Proportion-preserving —
 * used by the roughly-isotropic bodies (everything but the strip, which maps
 * its axes explicitly). Undefined dims ⇒ 1 (keep design size).
 */
export function bodyScale(dims: FixtureBodyDims | undefined, designSizeM: number): number {
  if (!dims) return 1
  return Math.max(dims.lengthM, dims.widthM, dims.heightM) / designSizeM
}
