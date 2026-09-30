import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useThree, type ThreeEvent } from '@react-three/fiber'
import {
  BoxGeometry,
  BufferGeometry,
  Color,
  CylinderGeometry,
  InstancedMesh,
  MathUtils,
  Matrix4,
  PlaneGeometry,
  Quaternion,
  Vector3,
} from 'three'
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js'
import type { StageElementDto } from '../../../api/stageElementApi'
import { toThree } from '../../../lib/stageCoords'
import { NO_RAYCAST } from '../raycast'
import { useSurfaceMaterial } from './SurfaceLighting'
import { elementBaseZ, elementFinish, type ElementBuild, type PartGeometry, type ScenePart } from './sceneParts'

/** One element's build, beside the element it was built from — what the scene draws and casts beams at. */
export interface SceneBuild {
  element: StageElementDto
  build: ElementBuild
}

/** *Sit in a seat…* while it is armed: seats take the pointer, and a click answers the seat. */
export interface SeatPicking {
  onPick: (elementUuid: string, seatId: string) => void
  onHover?: (seat: { elementUuid: string; seatId: string } | null) => void
}

/**
 * The scene document drawn (stage-view plan session 3): each element's parts, placed at its origin
 * and turned by its yaw, every surface on the light-array receiver (`surfaceShader.ts`). Deaf to the
 * pointer — a click meant for a fixture, a region or a rigging must never land on a wall in front of
 * it — except the seats while *Sit in a seat…* is armed.
 *
 * Nothing here moves per frame, so nothing here asks for one: a new element list, a layer toggled
 * or a tab drawn is a new prop, and R3F draws it.
 */
export const StageSceneElements = memo(function StageSceneElements({
  builds,
  seatPicking,
}: {
  builds: readonly SceneBuild[]
  seatPicking?: SeatPicking | null
}) {
  return (
    <group>
      {builds.map(({ element, build }) =>
        build.parts.length === 0 && build.seats.length === 0 ? null : (
          <group
            key={element.uuid}
            position={toThree(element.positionX, element.positionY, elementBaseZ(element))}
            rotation={[0, MathUtils.degToRad(element.yawDeg), 0]}
          >
            {build.parts.map((part) => (
              <ScenePartMesh key={part.key} part={part} />
            ))}
          </group>
        ),
      )}
      {builds.map(({ element, build }) =>
        build.seats.length === 0 ? null : (
          <SeatingMesh key={`${element.uuid}:seats`} element={element} build={build} picking={seatPicking ?? null} />
        ),
      )}
    </group>
  )
})

/** Pleats a metre: a cloth's fullness drawn as a ripple across its width. */
const PLEATS_PER_M = 7
const PLEAT_DEPTH_M = 0.05

/**
 * A part's geometry in its element's three.js frame (x across, y up, z towards the house), centred
 * on the origin: the part's `at` places it.
 */
export function partGeometry(geometry: PartGeometry): BufferGeometry {
  switch (geometry.shape) {
    case 'box':
      return new BoxGeometry(geometry.w, geometry.h, geometry.d)
    case 'cylinder':
      return new CylinderGeometry(geometry.rTop, geometry.rBottom, geometry.h, 24)
    case 'disc': {
      const g = new CylinderGeometry(geometry.r, geometry.r, geometry.d, 40)
      g.rotateX(Math.PI / 2)
      return g
    }
    case 'pleat': {
      const segments = Math.max(8, Math.round(geometry.w * PLEATS_PER_M * 4))
      const g = new PlaneGeometry(geometry.w, geometry.h, segments, 1)
      const pos = g.attributes.position
      for (let i = 0; i < pos.count; i++) {
        pos.setZ(i, Math.sin(pos.getX(i) * PLEATS_PER_M * Math.PI * 2) * (PLEAT_DEPTH_M / 2))
      }
      g.computeVertexNormals()
      return g
    }
    case 'quad': {
      // A PlaneGeometry faces +z (three) — downstage, lighting −y — before it is turned.
      const g = new PlaneGeometry(geometry.w, geometry.h)
      switch (geometry.facing) {
        case 'downstage':
          break
        case 'upstage':
          g.rotateY(Math.PI)
          break
        case 'up':
          g.rotateX(-Math.PI / 2)
          break
        case 'down':
          g.rotateX(Math.PI / 2)
          break
        case 'left':
          g.rotateY(Math.PI / 2)
          break
        case 'right':
          g.rotateY(-Math.PI / 2)
          break
      }
      return g
    }
  }
}

function geometryKey(g: PartGeometry): string {
  return JSON.stringify(g)
}

function ScenePartMesh({ part }: { part: ScenePart }) {
  const key = geometryKey(part.geometry)
  // Keyed on the shape's numbers, not the part object, which every build makes afresh.
  // eslint-disable-next-line react-hooks/exhaustive-deps -- `key` is `part.geometry` serialised whole
  const geometry = useMemo(() => partGeometry(part.geometry), [key])
  useEffect(() => () => geometry.dispose(), [geometry])
  const material = useSurfaceMaterial(part.finish, { doubleSided: part.geometry.shape === 'pleat' })
  return (
    <mesh
      geometry={geometry}
      material={material}
      position={[part.at.x, part.at.z, -part.at.y]}
      raycast={NO_RAYCAST}
    />
  )
}

// — seats ————————————————————————————————————————————————————————————————————————

/** A seat's shape in its own three.js frame, facing the stage (−z, lighting +y), scaled to the pitch. */
function seatGeometry(seatPitchM: number): BufferGeometry {
  const w = Math.min(0.5, Math.max(0.3, seatPitchM * 0.9))
  const parts = [
    new BoxGeometry(w, 0.08, 0.45).translate(0, 0.44, 0),
    new BoxGeometry(w, 0.52, 0.06).translate(0, 0.72, 0.21),
    new BoxGeometry(0.12, 0.4, 0.12).translate(0, 0.2, 0),
  ].map((g) => g.toNonIndexed())
  const merged = mergeGeometries(parts) ?? new BoxGeometry(w, 0.9, 0.45)
  for (const g of parts) g.dispose()
  return merged
}

const WHITE = new Color(1, 1, 1)
/** The seat under the pointer while picking: lit up, so the operator can see which one a click takes. */
const HOVER_TINT = new Color(2.2, 2.2, 1.4)
const SCRATCH_MATRIX = new Matrix4()
const SCRATCH_POS = new Vector3()
const SCRATCH_QUAT = new Quaternion()
const UNIT_SCALE = new Vector3(1, 1, 1)
const UP = new Vector3(0, 1, 0)
/** An instanced mesh's own raycast, put back while picking — never `undefined`, which R3F would assign. */
const INSTANCED_RAYCAST = InstancedMesh.prototype.raycast

/**
 * A seating block's seats, instanced: one draw call however many rows. Each instance is a seat of
 * `lib/stageSeats.ts`'s list at its base, turned with the block; the instance index is the seat's
 * place in that list, which is what a pick reads back.
 */
function SeatingMesh({
  element,
  build,
  picking,
}: {
  element: StageElementDto
  build: ElementBuild
  picking: SeatPicking | null
}) {
  const seatPitch = typeof element.params.seatPitchM === 'number' ? element.params.seatPitchM : 0.5
  const geometry = useMemo(() => seatGeometry(seatPitch), [seatPitch])
  useEffect(() => () => geometry.dispose(), [geometry])
  const material = useSurfaceMaterial(elementFinish(element))
  const count = build.seats.length
  const [mesh, setMesh] = useState<InstancedMesh | null>(null)
  const invalidate = useThree((s) => s.invalidate)
  const hovered = useRef(-1)

  useLayoutEffect(() => {
    if (mesh == null) return
    SCRATCH_QUAT.setFromAxisAngle(UP, MathUtils.degToRad(element.yawDeg))
    build.seats.forEach((seat, i) => {
      toThree(seat.base.x, seat.base.y, seat.base.z, SCRATCH_POS)
      SCRATCH_MATRIX.compose(SCRATCH_POS, SCRATCH_QUAT, UNIT_SCALE)
      mesh.setMatrixAt(i, SCRATCH_MATRIX)
      mesh.setColorAt(i, WHITE)
    })
    mesh.instanceMatrix.needsUpdate = true
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true
    mesh.computeBoundingSphere()
    hovered.current = -1
    invalidate()
  }, [mesh, build, element.yawDeg, invalidate])

  const setHover = (index: number) => {
    if (mesh == null || index === hovered.current) return
    if (hovered.current >= 0 && hovered.current < count) mesh.setColorAt(hovered.current, WHITE)
    if (index >= 0) mesh.setColorAt(index, HOVER_TINT)
    hovered.current = index
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true
    invalidate()
    picking?.onHover?.(index >= 0 ? { elementUuid: element.uuid, seatId: build.seats[index].id } : null)
  }
  // Picking switched off with a seat lit: put it back.
  useEffect(() => {
    if (picking == null && mesh != null && hovered.current >= 0) {
      if (hovered.current < count) mesh.setColorAt(hovered.current, WHITE)
      hovered.current = -1
      if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true
      invalidate()
    }
  }, [picking, mesh, count, invalidate])

  const onMove = (e: ThreeEvent<PointerEvent>) => {
    e.stopPropagation()
    setHover(e.instanceId ?? -1)
  }
  const onOut = () => setHover(-1)
  const onClick = (e: ThreeEvent<MouseEvent>) => {
    e.stopPropagation()
    const seat = e.instanceId != null ? build.seats[e.instanceId] : undefined
    if (seat != null) picking?.onPick(element.uuid, seat.id)
  }

  return (
    <instancedMesh
      // A new count is a new mesh: an InstancedMesh's buffers are sized once.
      key={count}
      ref={setMesh}
      args={[geometry, material, count]}
      frustumCulled={false}
      raycast={picking ? INSTANCED_RAYCAST : NO_RAYCAST}
      onPointerMove={picking ? onMove : undefined}
      onPointerOut={picking ? onOut : undefined}
      onClick={picking ? onClick : undefined}
    />
  )
}
