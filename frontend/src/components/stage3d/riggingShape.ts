import { STANDING_RIGGING_KINDS } from './bodies/mount'

/**
 * What a rigging position looks like in the Stage view, by its kind: a **tube** (a bar, pipe or
 * boom — and a position of no kind, which hangs like one), a **truss** of four chords, or **none**:
 * a ledge or floor stand is what its units stand on and an `OTHER` mount (a pros ring's) carries
 * nothing that reads as a bar, so the units on it are the picture.
 */
export type RiggingShape = 'tube' | 'truss' | 'none'

/** A 48 mm scaffold tube. */
export const TUBE_RADIUS_M = 0.024
/** A 290 mm box truss, chord centre to chord centre. */
export const TRUSS_SECTION_M = 0.29

export function riggingShape(kind: string | null | undefined): RiggingShape {
  const k = kind?.toUpperCase() ?? null
  if (k === 'TRUSS') return 'truss'
  if (k === 'OTHER' || (k != null && STANDING_RIGGING_KINDS.has(k))) return 'none'
  return 'tube'
}
