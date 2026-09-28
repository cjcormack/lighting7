// Endpoint handles drag freely in 3D against a camera-facing vertical plane.
// With pitchDeg pinned to 0, yawDeg is the bar's horizontal heading and
// rollDeg is its elevation above the floor — see deriveFromEndpoints for the
// YXZ Euler inverse.
import { useMemo, useState } from 'react'
import { type ThreeEvent, useThree } from '@react-three/fiber'
import { Vector3 } from 'three'
import type { RiggingDto } from '../../api/riggingApi'
import type { RiggingPositionUpdate } from './Stage3D'
import { toThree, fromThree } from '../../lib/stageCoords'
import { deriveFromEndpoints, worldEndpointsFor } from '../../lib/stageGeometry'
import { useHandleDrag, verticalPlaneThroughR3F } from './useHandleDrag'
import { snap, SNAP_DISTANCE_M } from './useShiftHeld'

type EndpointIndex = 0 | 1

interface RiggingEndpointHandlesProps {
  rig: RiggingDto
  onChange: (next: RiggingPositionUpdate, settled: boolean) => void
  /** When .current is true, drag positions snap to the grid. Snapping is on by
   *  default and Shift suspends it — see useSnapGrid. */
  snapActiveRef?: React.RefObject<boolean>
  onDragStart?: () => void
  onDragEnd?: () => void
}

const HANDLE_SIZE = 0.16

export function RiggingEndpointHandles({ rig, onChange, snapActiveRef, onDragStart, onDragEnd }: RiggingEndpointHandlesProps) {
  const startDrag = useHandleDrag()
  const { camera } = useThree()
  const [dragging, setDragging] = useState<EndpointIndex | null>(null)

  const endpoints = useMemo(() => worldEndpointsFor(rig), [rig])
  const r3fEnds = useMemo(
    () => endpoints.map((p) => toThree(p.x, p.y, p.z)),
    [endpoints],
  )

  const onPointerDown = (idx: EndpointIndex, e: ThreeEvent<PointerEvent>) => {
    setDragging(idx)
    onDragStart?.()
    // Capture pinned-endpoint coords at drag-start; the source array re-allocates
    // when the optimistic store update fires mid-drag, but we want stable refs.
    const { x: pinnedX, y: pinnedY, z: pinnedZ } = endpoints[idx === 0 ? 1 : 0]
    // Camera-facing vertical plane through the endpoint — gives the user a
    // stable "screen-aligned" plane to drag against, including vertical motion.
    const handleR3F = r3fEnds[idx]
    const plane = verticalPlaneThroughR3F(handleR3F, camera.position)

    const updateFromHit = (p: Vector3, settled: boolean) => {
      const { x, y, z } = fromThree(p)
      const dragged = snapActiveRef?.current
        ? { x: snap(x, SNAP_DISTANCE_M), y: snap(y, SNAP_DISTANCE_M), z: snap(z, SNAP_DISTANCE_M) }
        : { x, y, z }
      const pinnedPt = { x: pinnedX, y: pinnedY, z: pinnedZ }
      // Pass endpoints in canonical (A=index-0, B=index-1) order so yaw/roll
      // reflect the bar's "+X forward" direction consistently.
      const d =
        idx === 0
          ? deriveFromEndpoints(dragged, pinnedPt)
          : deriveFromEndpoints(pinnedPt, dragged)
      onChange(
        {
          positionX: d.positionX,
          positionY: d.positionY,
          positionZ: d.positionZ,
          yawDeg: d.yawDeg,
          pitchDeg: d.pitchDeg,
          rollDeg: d.rollDeg,
          lengthM: d.lengthM,
        },
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

  return (
    <>
      {r3fEnds.map((pos, i) => (
        <mesh
          key={i}
          position={pos}
          onPointerDown={(e) => onPointerDown(i as EndpointIndex, e)}
        >
          <sphereGeometry args={[HANDLE_SIZE, 16, 12]} />
          <meshStandardMaterial
            color={dragging === i ? '#ffe082' : '#c8d3e2'}
            emissive={dragging === i ? '#ffae42' : '#3a4a5a'}
            emissiveIntensity={dragging === i ? 0.6 : 0.3}
          />
        </mesh>
      ))}
    </>
  )
}
