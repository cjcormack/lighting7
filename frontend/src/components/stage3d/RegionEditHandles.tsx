// Resize + rotate overlay for the selected region. The body-drag move
// affordance lives on the region mesh itself (see StageRegionMeshes).
import { useMemo, useState } from 'react'
import { type ThreeEvent, useThree } from '@react-three/fiber'
import { MathUtils, Plane, Vector3 } from 'three'
import type { StageRegionDto } from '../../api/stageRegionApi'
import type { RegionPositionUpdate } from './Stage3D'
import { toThree, fromThree } from '../../lib/stageCoords'
import {
  deriveFromDraggedCorner,
  localEdgeMidpoints,
  localRotationOffsets,
  pinnedIndexFor,
  rotateXY,
  worldCornersFor,
} from '../../lib/stageGeometry'
import { useHandleDrag, verticalPlaneThroughR3F } from './useHandleDrag'
import { snap, SNAP_DISTANCE_M, SNAP_ANGLE_DEG } from './useShiftHeld'

interface RegionEditHandlesProps {
  region: StageRegionDto
  /** Live update during drag; final settled call also fired on pointerup. */
  onChange: (next: RegionPositionUpdate, settled: boolean) => void
  /** When .current is true, drag positions snap to the grid. Snapping is on by
   *  default and Shift suspends it — see useSnapGrid. */
  snapActiveRef?: React.RefObject<boolean>
  onDragStart?: () => void
  onDragEnd?: () => void
}

const PLANE_NORMAL_UP = new Vector3(0, 1, 0)
const CORNER_SIZE = 0.18
const HEIGHT_HANDLE_WIDE = 0.5
const HEIGHT_HANDLE_VERT = 0.14
const HEIGHT_HANDLE_THIN = 0.22
// Edges 0/2 (front/back) run along the region's local X, edges 1/3 (right/
// left) along local Y — so the per-edge tile rotation adds 90° for the
// Y-aligned edges on top of the region's yawDeg.
const EDGE_YAW_OFFSETS_RAD: number[] = [0, Math.PI / 2, 0, Math.PI / 2]
const ROTATION_OFFSET_M = 0.4
const ROTATION_TORUS_RADIUS = 0.22
const ROTATION_TORUS_TUBE = 0.055
const MIN_HEIGHT_M = 0.05

type HeightTier = 'top' | 'floor'

type DraggingId =
  | { kind: 'corner'; idx: number }
  | { kind: 'height'; tier: HeightTier; idx: number }
  | { kind: 'rotation'; idx: number }
  | null

export function RegionEditHandles({ region, onChange, snapActiveRef, onDragStart, onDragEnd }: RegionEditHandlesProps) {
  const startDrag = useHandleDrag()
  const { camera } = useThree()
  const [dragging, setDragging] = useState<DraggingId>(null)

  const cx = region.centerX ?? 0
  const cy = region.centerY ?? 0
  const cz = region.centerZ ?? 0
  const h = region.heightM ?? 1
  const w = region.widthM ?? 1
  const d = region.depthM ?? 1
  const yawDeg = region.yawDeg ?? 0
  const yawRad = MathUtils.degToRad(yawDeg)

  const worldCorners = useMemo(() => worldCornersFor(region), [region])
  const r3fCorners = useMemo(
    () => worldCorners.map(([x, y, z]) => toThree(x, y, z)),
    [worldCorners],
  )

  const edgeXY = useMemo(
    () => localEdgeMidpoints(w, d).map(([lx, ly]) => rotateXY(lx, ly, yawRad)),
    [w, d, yawRad],
  )
  const r3fFloorEdges = useMemo(
    () => edgeXY.map(([rx, ry]) => toThree(cx + rx, cy + ry, cz)),
    [edgeXY, cx, cy, cz],
  )
  const r3fTopEdges = useMemo(
    () => edgeXY.map(([rx, ry]) => toThree(cx + rx, cy + ry, cz + h)),
    [edgeXY, cx, cy, cz, h],
  )
  const r3fRotations = useMemo(
    () =>
      localRotationOffsets(w, d, ROTATION_OFFSET_M)
        .map(([lx, ly]) => rotateXY(lx, ly, yawRad))
        .map(([rx, ry]) => toThree(cx + rx, cy + ry, cz)),
    [w, d, yawRad, cx, cy, cz],
  )

  const onCornerDown = (idx: number, e: ThreeEvent<PointerEvent>) => {
    setDragging({ kind: 'corner', idx })
    onDragStart?.()
    const pinnedIdx = pinnedIndexFor(idx)
    const pinnedX = worldCorners[pinnedIdx][0]
    const pinnedY = worldCorners[pinnedIdx][1]
    const lockedYawDeg = yawDeg
    const lockedCz = cz
    const dragPlaneZ = worldCorners[idx][2]
    const plane = new Plane(PLANE_NORMAL_UP, -dragPlaneZ)

    const updateFromHit = (p: Vector3, settled: boolean) => {
      const { x, y } = fromThree(p)
      const snapped = snapActiveRef?.current
        ? { x: snap(x, SNAP_DISTANCE_M), y: snap(y, SNAP_DISTANCE_M) }
        : { x, y }
      const result = deriveFromDraggedCorner(snapped.x, snapped.y, pinnedX, pinnedY, lockedYawDeg)
      onChange(
        {
          centerX: result.centerX,
          centerY: result.centerY,
          centerZ: lockedCz,
          yawDeg: lockedYawDeg,
          widthM: result.widthM,
          depthM: result.depthM,
        },
        settled,
      )
    }

    startDrag(
      {
        plane,
        handleWorld: r3fCorners[idx],
        onDrag: (p) => updateFromHit(p, false),
        onSettle: (lastPoint) => {
          setDragging(null)
          onDragEnd?.()
          if (lastPoint) updateFromHit(lastPoint, true)
        },
      },
      e,
    )
  }

  const onHeightDown = (tier: HeightTier, idx: number, e: ThreeEvent<PointerEvent>) => {
    setDragging({ kind: 'height', tier, idx })
    onDragStart?.()
    const handleWorld = tier === 'top' ? r3fTopEdges[idx] : r3fFloorEdges[idx]
    const plane = verticalPlaneThroughR3F(handleWorld, camera.position)
    const lockedCx = cx
    const lockedCy = cy
    const lockedYaw = yawDeg

    let updateFromHit: (p: Vector3, settled: boolean) => void
    if (tier === 'top') {
      const lockedCz = cz
      updateFromHit = (p, settled) => {
        const newTopZ = snapActiveRef?.current ? snap(p.y, SNAP_DISTANCE_M) : p.y
        const newHeight = Math.max(MIN_HEIGHT_M, newTopZ - lockedCz)
        onChange(
          { centerX: lockedCx, centerY: lockedCy, centerZ: lockedCz, yawDeg: lockedYaw, heightM: newHeight },
          settled,
        )
      }
    } else {
      // Floor handle: drag raises/lowers centerZ; heightM compensates so the
      // top face stays put. Clamp so heightM stays above MIN_HEIGHT_M.
      const lockedTopZ = cz + h
      updateFromHit = (p, settled) => {
        const raw = snapActiveRef?.current ? snap(p.y, SNAP_DISTANCE_M) : p.y
        let newCz = raw
        let newHeight = lockedTopZ - newCz
        if (newHeight < MIN_HEIGHT_M) {
          newHeight = MIN_HEIGHT_M
          newCz = lockedTopZ - MIN_HEIGHT_M
        }
        onChange(
          { centerX: lockedCx, centerY: lockedCy, centerZ: newCz, yawDeg: lockedYaw, heightM: newHeight },
          settled,
        )
      }
    }

    startDrag(
      {
        plane,
        handleWorld,
        lockAxis: 'vertical',
        onDrag: (p) => updateFromHit(p, false),
        onSettle: (lastPoint) => {
          setDragging(null)
          onDragEnd?.()
          if (lastPoint) updateFromHit(lastPoint, true)
        },
      },
      e,
    )
  }

  const onRotationDown = (idx: number, e: ThreeEvent<PointerEvent>) => {
    setDragging({ kind: 'rotation', idx })
    onDragStart?.()
    const lockedCx = cx
    const lockedCy = cy
    const lockedCz = cz
    const startYawDeg = yawDeg
    const handleR3F = r3fRotations[idx]
    const { x: handleLx, y: handleLy } = fromThree(handleR3F)
    const startAngle = Math.atan2(handleLy - lockedCy, handleLx - lockedCx)
    const plane = new Plane(PLANE_NORMAL_UP, -lockedCz)

    const updateFromHit = (p: Vector3, settled: boolean) => {
      const { x, y } = fromThree(p)
      const currentAngle = Math.atan2(y - lockedCy, x - lockedCx)
      let newYawDeg = startYawDeg + MathUtils.radToDeg(currentAngle - startAngle)
      if (snapActiveRef?.current) newYawDeg = snap(newYawDeg, SNAP_ANGLE_DEG)
      onChange(
        { centerX: lockedCx, centerY: lockedCy, centerZ: lockedCz, yawDeg: newYawDeg },
        settled,
      )
    }

    startDrag(
      {
        plane,
        handleWorld: handleR3F,
        onDrag: (p) => updateFromHit(p, false),
        onSettle: (lastPoint) => {
          setDragging(null)
          onDragEnd?.()
          if (lastPoint) updateFromHit(lastPoint, true)
        },
      },
      e,
    )
  }

  const cornerActive = (i: number) => dragging?.kind === 'corner' && dragging.idx === i
  const heightActive = (tier: HeightTier, i: number) =>
    dragging?.kind === 'height' && dragging.tier === tier && dragging.idx === i
  const rotationActive = (i: number) => dragging?.kind === 'rotation' && dragging.idx === i

  return (
    <>
      {r3fCorners.map((pos, i) => (
        <mesh key={`c-${i}`} position={pos} onPointerDown={(e) => onCornerDown(i, e)}>
          <sphereGeometry args={[CORNER_SIZE, 16, 12]} />
          <meshStandardMaterial
            color={cornerActive(i) ? '#ffe082' : '#9fc1d8'}
            emissive={cornerActive(i) ? '#ffae42' : '#3a4a5a'}
            emissiveIntensity={cornerActive(i) ? 0.6 : 0.3}
          />
        </mesh>
      ))}
      {r3fTopEdges.map((pos, i) => (
        <mesh
          key={`ht-${i}`}
          position={pos}
          rotation={[0, yawRad + EDGE_YAW_OFFSETS_RAD[i], 0]}
          onPointerDown={(e) => onHeightDown('top', i, e)}
        >
          <boxGeometry args={[HEIGHT_HANDLE_WIDE, HEIGHT_HANDLE_VERT, HEIGHT_HANDLE_THIN]} />
          <meshStandardMaterial
            color={heightActive('top', i) ? '#ffe082' : '#a0e0c0'}
            emissive={heightActive('top', i) ? '#ffae42' : '#2a4a3a'}
            emissiveIntensity={heightActive('top', i) ? 0.6 : 0.3}
          />
        </mesh>
      ))}
      {r3fFloorEdges.map((pos, i) => (
        <mesh
          key={`hf-${i}`}
          position={pos}
          rotation={[0, yawRad + EDGE_YAW_OFFSETS_RAD[i], 0]}
          onPointerDown={(e) => onHeightDown('floor', i, e)}
        >
          <boxGeometry args={[HEIGHT_HANDLE_WIDE, HEIGHT_HANDLE_VERT, HEIGHT_HANDLE_THIN]} />
          <meshStandardMaterial
            color={heightActive('floor', i) ? '#ffe082' : '#a0e0c0'}
            emissive={heightActive('floor', i) ? '#ffae42' : '#2a4a3a'}
            emissiveIntensity={heightActive('floor', i) ? 0.6 : 0.3}
          />
        </mesh>
      ))}
      {r3fRotations.map((pos, i) => (
        <mesh
          key={`r-${i}`}
          position={pos}
          rotation={[Math.PI / 2, 0, 0]}
          onPointerDown={(e) => onRotationDown(i, e)}
        >
          <torusGeometry args={[ROTATION_TORUS_RADIUS, ROTATION_TORUS_TUBE, 12, 32]} />
          <meshStandardMaterial
            color={rotationActive(i) ? '#ffe082' : '#d8b89f'}
            emissive={rotationActive(i) ? '#ffae42' : '#4a3a2a'}
            emissiveIntensity={rotationActive(i) ? 0.6 : 0.3}
          />
        </mesh>
      ))}
    </>
  )
}
