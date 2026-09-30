import { memo, useMemo } from 'react'
import { Edges } from '@react-three/drei'
import { MathUtils } from 'three'
import type { StageElementDto } from '../../api/stageElementApi'
import { seatingExtent, seatingParams } from '../../lib/stageSeats'
import { toThree } from '../../lib/stageCoords'

/**
 * The scene document's elements, drawn as boxes (stage-view plan session 2): enough to see that the
 * hall `set_scene` built stands where it should, and nothing more — session 3's builders draw each
 * kind properly and light it. Unlit, see-through, and deaf to the pointer, so they never take a
 * click meant for a fixture, a region or a rigging, and never hide the stage from the orbit camera.
 *
 * Two readings of Z, the document's: an element's base, except a platform's, which is its top
 * surface with the deck hanging below — as a region's is (`StageRegionMeshes`). A flown piece
 * with a `trimM` stands at its trim. A seating block's box is its footprint from row A back, its
 * rake's rise plus a seat's height tall.
 *
 * Ortho section planes stay rig-derived this session (`sceneBoundsLighting` ignores elements), so a
 * room larger than the rig may be cut by a section — session 3 teaches the sections the venue.
 */

const KIND_COLOUR: Record<string, string> = {
  ROOM: '#8a8f99',
  PROSCENIUM: '#b08968',
  FLAT: '#c9b99a',
  DRAPE: '#7a2230',
  PLATFORM: '#6b6258',
  SEATING: '#8c3a48',
  OBJECT: '#9aa5b1',
}

/** A seat's height above its base, for the seating block's box. */
const SEAT_HEIGHT_M = 0.9

interface Box {
  uuid: string
  /** Box centre, lighting metres. */
  x: number
  y: number
  z: number
  w: number
  d: number
  h: number
  yawDeg: number
  colour: string
  /** A room is drawn as its edges alone, or it would hide the stage it holds. */
  edgesOnly: boolean
}

function num(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

/** The element's box, or null for one hidden, switched off by its state, or with nothing to draw. */
export function sceneElementBox(e: StageElementDto): Box | null {
  if (e.hidden) return null
  const states = (e.params.states ?? null) as Record<string, unknown> | null
  if (states?.visible === false) return null
  const colour = e.finishColour ?? KIND_COLOUR[e.kind] ?? '#9aa5b1'
  const yaw = (e.yawDeg * Math.PI) / 180
  if (e.kind === 'SEATING') {
    const params = seatingParams(e)
    if (params == null) return null
    const { widthM, depthM, riseM } = seatingExtent(params)
    // Row A's centre is the origin; the block runs back from half a row in front of it. It spans
    // from the lower of row A and the last row up to a seat's height over the higher, so a bank
    // that steps down holds its seats as one that rakes up does.
    const back = -(depthM / 2 - params.rowPitchM / 2)
    const bottom = e.positionZ + Math.min(0, riseM)
    const h = Math.abs(riseM) + SEAT_HEIGHT_M
    return {
      uuid: e.uuid,
      x: e.positionX - back * Math.sin(yaw),
      y: e.positionY + back * Math.cos(yaw),
      z: bottom + h / 2,
      w: widthM,
      d: depthM,
      h,
      yawDeg: e.yawDeg,
      colour,
      edgesOnly: false,
    }
  }
  if (!(e.widthM > 0 && e.depthM > 0 && e.heightM > 0)) return null
  const trim = num(states?.trimM)
  const base = e.kind === 'PLATFORM' ? e.positionZ - e.heightM : (trim ?? e.positionZ)
  return {
    uuid: e.uuid,
    x: e.positionX,
    y: e.positionY,
    z: base + e.heightM / 2,
    w: e.widthM,
    d: e.depthM,
    h: e.heightM,
    yawDeg: e.yawDeg,
    colour,
    edgesOnly: e.kind === 'ROOM',
  }
}

const noRaycast = () => null

export const StageSceneBoxes = memo(function StageSceneBoxes({ elements }: { elements: readonly StageElementDto[] }) {
  const boxes = useMemo(() => elements.map(sceneElementBox).filter((b): b is Box => b != null), [elements])
  return (
    <group>
      {boxes.map((b) => (
        <mesh
          key={b.uuid}
          position={toThree(b.x, b.y, b.z)}
          rotation={[0, MathUtils.degToRad(b.yawDeg), 0]}
          raycast={noRaycast}
          renderOrder={-1}
        >
          <boxGeometry args={[b.w, b.h, b.d]} />
          <meshBasicMaterial
            color={b.colour}
            transparent
            opacity={b.edgesOnly ? 0 : 0.12}
            depthWrite={false}
          />
          <Edges color={b.colour} />
        </mesh>
      ))}
    </group>
  )
})
