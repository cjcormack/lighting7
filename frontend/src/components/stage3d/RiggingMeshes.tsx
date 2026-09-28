import { useState } from 'react'
import { useCursor } from '@react-three/drei'
import { type ThreeEvent } from '@react-three/fiber'
import { MathUtils, Plane, Vector3, type Object3D } from 'three'
import type { RiggingDto } from '../../api/riggingApi'
import type { RiggingPositionUpdate } from './Stage3D'
import { toThree, fromThree } from '../../lib/stageCoords'
import { DEFAULT_RIGGING_LENGTH_M } from '../../lib/stageGeometry'
import { useBodyDrag } from './useBodyDrag'
import { snap, SNAP_DISTANCE_M } from './useShiftHeld'
import { StageLabel } from './StageLabel'

interface RiggingMeshesProps {
  riggings: RiggingDto[]
  selectedUuid?: string | null
  editMode?: boolean
  showLabel?: boolean
  onClick?: (rig: RiggingDto, mesh: Object3D) => void
  /** Body drag emits a horizontal move (positionX/Y change). Absent in
   *  view/placement mode disables body drag. */
  onMove?: (rig: RiggingDto, next: RiggingPositionUpdate, settled: boolean) => void
  snapActiveRef?: React.RefObject<boolean>
  onDragStart?: () => void
  onDragEnd?: () => void
}

// Each rigging renders as a bar along its local X axis. lengthM defaults to 3 m
// for un-set DTOs. Yaw/pitch/roll come from the DTO; rotation order 'YXZ' matches
// the convention used by panTiltToDir in stageCoords.
// Single-sourced in lib/stageGeometry (the pure module the 2D editor shares);
// re-exported here because this is where consumers already import it from.
export { DEFAULT_RIGGING_LENGTH_M }
const RIGGING_THICKNESS_M = 0.18
const PLANE_NORMAL_UP = new Vector3(0, 1, 0)

export function RiggingMeshes({
  riggings,
  selectedUuid,
  editMode,
  showLabel,
  onClick,
  onMove,
  snapActiveRef,
  onDragStart,
  onDragEnd,
}: RiggingMeshesProps) {
  return (
    <>
      {riggings.map((rig) => (
        <RiggingMesh
          key={rig.uuid}
          rig={rig}
          selected={rig.uuid === selectedUuid}
          editMode={editMode}
          showLabel={showLabel}
          onClick={onClick}
          onMove={onMove}
          snapActiveRef={snapActiveRef}
          onDragStart={onDragStart}
          onDragEnd={onDragEnd}
        />
      ))}
    </>
  )
}

interface RiggingMeshProps {
  rig: RiggingDto
  selected: boolean
  editMode?: boolean
  showLabel?: boolean
  onClick?: (rig: RiggingDto, mesh: Object3D) => void
  onMove?: (rig: RiggingDto, next: RiggingPositionUpdate, settled: boolean) => void
  snapActiveRef?: React.RefObject<boolean>
  onDragStart?: () => void
  onDragEnd?: () => void
}

function RiggingMesh({
  rig,
  selected,
  editMode,
  showLabel,
  onClick,
  onMove,
  snapActiveRef,
  onDragStart,
  onDragEnd,
}: RiggingMeshProps) {
  const [hovered, setHovered] = useState(false)
  useCursor(!!editMode && hovered)
  const startBodyDrag = useBodyDrag()

  const px = rig.positionX ?? 0
  const py = rig.positionY ?? 0
  const pz = rig.positionZ ?? 0
  const yawDeg = rig.yawDeg ?? 0
  const pitchDeg = rig.pitchDeg ?? 0
  const rollDeg = rig.rollDeg ?? 0
  const length = rig.lengthM ?? DEFAULT_RIGGING_LENGTH_M

  const pos = toThree(px, py, pz)
  const active = selected || (!!editMode && hovered)

  const onPointerDown = (e: ThreeEvent<PointerEvent>) => {
    const mesh = e.eventObject
    startBodyDrag(e, {
      onClick: onClick ? () => onClick(rig, mesh) : undefined,
      onDragStart,
      onDragEnd,
      buildDrag: onMove && selected
        ? () => {
            const handleWorld = toThree(px, py, pz)
            const plane = new Plane(PLANE_NORMAL_UP, -handleWorld.y)
            const emit = (p: Vector3, settled: boolean) => {
              const { x, y } = fromThree(p)
              const sx = snapActiveRef?.current ? snap(x, SNAP_DISTANCE_M) : x
              const sy = snapActiveRef?.current ? snap(y, SNAP_DISTANCE_M) : y
              onMove(
                rig,
                {
                  positionX: sx,
                  positionY: sy,
                  positionZ: pz,
                  yawDeg,
                  pitchDeg,
                  rollDeg,
                  lengthM: length,
                },
                settled,
              )
            }
            return {
              plane,
              handleWorld,
              onDrag: (p) => emit(p, false),
              onSettle: (last) => {
                if (last) emit(last, true)
              },
            }
          }
        : undefined,
    })
  }

  return (
    <mesh
      position={pos}
      rotation={[
        MathUtils.degToRad(pitchDeg),
        MathUtils.degToRad(yawDeg),
        MathUtils.degToRad(rollDeg),
        'YXZ',
      ]}
      onPointerDown={onClick || onMove ? onPointerDown : undefined}
      onPointerOver={editMode ? (e) => { e.stopPropagation(); setHovered(true) } : undefined}
      onPointerOut={editMode ? () => setHovered(false) : undefined}
    >
      <boxGeometry args={[length, RIGGING_THICKNESS_M, RIGGING_THICKNESS_M]} />
      <meshStandardMaterial
        color={active ? '#c8d3e2' : '#9aa5b4'}
        metalness={0.4}
        roughness={0.55}
        emissive={selected ? '#3a4a5a' : '#000'}
        emissiveIntensity={selected ? 0.4 : 0}
      />
      {showLabel && (
        <StageLabel position={[0, RIGGING_THICKNESS_M / 2 + 0.05, 0]}>
          {rig.name}
        </StageLabel>
      )}
    </mesh>
  )
}
