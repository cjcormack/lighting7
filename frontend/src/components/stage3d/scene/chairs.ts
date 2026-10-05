import { BoxGeometry, BufferGeometry, CylinderGeometry, ExtrudeGeometry, Shape, TorusGeometry } from 'three'
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js'
import type { ChairStyle } from '../../../lib/stageSeats'

/**
 * The chairs a seating's seats are drawn as, each in its own three.js frame: x across, y up, the
 * seat's base at the origin and the chair facing the stage (−z, lighting +y). A chair is two
 * geometries — its **pads** (the upholstery, drawn in the element's finish) and its **frame** —
 * so a banquet chair's gold frame and navy pads are two instanced meshes sharing one instance list.
 */
export interface ChairGeometry {
  pads: BufferGeometry
  frame: BufferGeometry
}

/** A banquet chair's frame when the seating names none: the gold of a hired stacking chair. */
export const BANQUET_FRAME_COLOUR = '#c9a44c'

/** A banquet chair across its frame: a real stacking chair's, kept unless the pitch is tighter. */
const BANQUET_WIDTH_M = 0.44
/** A banquet chair's seat rail: where its legs end, its cushion sits and its back rises from. */
const BANQUET_RAIL_M = 0.42
const CUSHION_M = 0.06
const TUBE_M = 0.011
/** How far the back leans from upright. */
const BACK_LEAN_RAD = 0.1

function merge(parts: BufferGeometry[]): BufferGeometry {
  const flat = parts.map((g) => (g.index ? g.toNonIndexed() : g))
  const merged = mergeGeometries(flat) ?? new BoxGeometry(0.4, 0.9, 0.4)
  for (const g of new Set([...parts, ...flat])) g.dispose()
  return merged
}

function tube(length: number): CylinderGeometry {
  return new CylinderGeometry(TUBE_M, TUBE_M, length, 6)
}

/** A fixed auditorium seat on a post, sized to the pitch. */
function theatreChair(seatPitchM: number): ChairGeometry {
  const w = Math.min(0.5, Math.max(0.3, seatPitchM * 0.9))
  return {
    pads: merge([
      new BoxGeometry(w, 0.08, 0.45).translate(0, 0.44, 0),
      new BoxGeometry(w, 0.52, 0.06).translate(0, 0.72, 0.21),
    ]),
    frame: merge([new BoxGeometry(0.12, 0.4, 0.12).translate(0, 0.2, 0)]),
  }
}

/**
 * A stacking banquet chair: a cushion on a seat rail 0.42 m up (0.48 m to its top), a round-topped
 * padded back leaning a little from upright to about 0.93 m, on four tube legs whose rear pair runs
 * on up round the back.
 */
function banquetChair(seatPitchM: number): ChairGeometry {
  const half = 0.205
  const rail = BANQUET_RAIL_M

  // The back, built upright from the top of the rear legs, then leaned and set on them.
  const padHalf = 0.185
  const outline = new Shape()
  outline.moveTo(-padHalf, 0.1)
  outline.lineTo(padHalf, 0.1)
  outline.lineTo(padHalf, 0.3)
  outline.absarc(0, 0.3, padHalf, 0, Math.PI, false)
  outline.lineTo(-padHalf, 0.1)
  const backPad = new ExtrudeGeometry(outline, { depth: 0.045, bevelEnabled: false, curveSegments: 10 }).translate(0, 0, -0.03)
  const backFrame = [
    tube(0.3).translate(-half, 0.15, 0),
    tube(0.3).translate(half, 0.15, 0),
    new TorusGeometry(half, TUBE_M, 6, 14, Math.PI).translate(0, 0.3, 0),
  ]
  const setBack = (g: BufferGeometry) => g.rotateX(BACK_LEAN_RAD).translate(0, rail, 0.205)

  const chair: ChairGeometry = {
    pads: merge([new BoxGeometry(0.42, CUSHION_M, 0.42).translate(0, rail + CUSHION_M / 2, -0.01), setBack(backPad)]),
    frame: merge([
      new BoxGeometry(BANQUET_WIDTH_M, 0.025, 0.44).translate(0, rail - 0.0125, 0),
      ...[-half, half].flatMap((x) => [tube(rail).translate(x, rail / 2, -0.2), tube(rail).translate(x, rail / 2, 0.205)]),
      ...backFrame.map(setBack),
    ]),
  }
  // Chairs set closer than they are wide are drawn narrower rather than through each other.
  const squeeze = Math.min(1, (seatPitchM * 0.96) / BANQUET_WIDTH_M)
  if (squeeze < 1) {
    chair.pads.scale(squeeze, 1, 1)
    chair.frame.scale(squeeze, 1, 1)
  }
  return chair
}

export function chairGeometry(style: ChairStyle, seatPitchM: number): ChairGeometry {
  return style === 'BANQUET' ? banquetChair(seatPitchM) : theatreChair(seatPitchM)
}
