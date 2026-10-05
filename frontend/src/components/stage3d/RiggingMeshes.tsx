import { useState } from 'react'
import { useCursor } from '@react-three/drei'
import { type ThreeEvent } from '@react-three/fiber'
import { MathUtils, type Object3D } from 'three'
import type { RiggingDto } from '../../api/riggingApi'
import { toThree } from '../../lib/stageCoords'
import { DEFAULT_RIGGING_LENGTH_M } from '../../lib/stageGeometry'
import { StageLabel } from './StageLabel'
import { isClick } from './dragThreshold'
import { riggingShape, TRUSS_SECTION_M, TUBE_RADIUS_M } from './riggingShape'

interface RiggingMeshesProps {
  riggings: RiggingDto[]
  /** Every selected one — a multi-selection lights as one. */
  selectedUuids?: ReadonlySet<string>
  /** Editing: a hover lights a bar, and a position with nothing to draw shows as a guide. */
  editMode?: boolean
  onClick?: (rig: RiggingDto, mesh: Object3D) => void
}

// Each rigging is drawn along its local X axis by its kind (`riggingShape`). It has no body drag:
// `RiggingEndpointHandles` moves it, so a drag that starts on a bar turns the camera.
// lengthM defaults to 3 m for un-set DTOs. Yaw/pitch/roll come from the DTO; rotation order 'YXZ'
// matches the convention used by panTiltToDir in stageCoords.
// Single-sourced in lib/stageGeometry (the pure module the section editing shares);
// re-exported here because this is where consumers already import it from.
export { DEFAULT_RIGGING_LENGTH_M }
const TUBE_COLOUR = '#50565e'
const SELECTED_COLOUR = '#4262d0'
const HOVER_COLOUR = '#7d8590'
/** A thin tube is hard to hit: it is picked through a box this wide round it. */
const PICK_SECTION_M = 0.09
const LABEL_OFFSET: [number, number, number] = [0, 0.1, 0]
const TRUSS_CHORDS: ReadonlyArray<[number, number]> = [
  [1, 1],
  [1, -1],
  [-1, 1],
  [-1, -1],
]

export function RiggingMeshes({ riggings, selectedUuids, editMode, onClick }: RiggingMeshesProps) {
  return (
    <>
      {riggings.map((rig) => (
        <RiggingMesh
          key={rig.uuid}
          rig={rig}
          selected={selectedUuids?.has(rig.uuid) ?? false}
          editMode={editMode}
          onClick={onClick}
        />
      ))}
    </>
  )
}

interface RiggingMeshProps {
  rig: RiggingDto
  selected: boolean
  editMode?: boolean
  onClick?: (rig: RiggingDto, mesh: Object3D) => void
}

function RiggingMesh({ rig, selected, editMode, onClick }: RiggingMeshProps) {
  const [hovered, setHovered] = useState(false)
  const shape = riggingShape(rig.kind)
  // A mount with nothing of its own to draw is a guide while editing, so it can still be picked.
  const guide = shape === 'none'
  const pickable = onClick != null && (!guide || !!editMode)
  useCursor(pickable && hovered)

  const length = rig.lengthM ?? DEFAULT_RIGGING_LENGTH_M
  const pos = toThree(rig.positionX ?? 0, rig.positionY ?? 0, rig.positionZ ?? 0)
  const colour = selected ? SELECTED_COLOUR : editMode && hovered ? HOVER_COLOUR : TUBE_COLOUR

  const onPick = (e: ThreeEvent<MouseEvent>) => {
    e.stopPropagation()
    if (isClick(e)) onClick?.(rig, e.eventObject)
  }

  const tube = (key: string, y: number, z: number, faint: boolean) => (
    <mesh key={key} position={[0, y, z]} rotation={[0, 0, Math.PI / 2]} raycast={NO_PICK}>
      <cylinderGeometry args={[TUBE_RADIUS_M, TUBE_RADIUS_M, length, 10]} />
      <meshBasicMaterial color={colour} transparent={faint} opacity={faint ? 0.35 : 1} depthWrite={!faint} />
    </mesh>
  )

  return (
    <group
      position={pos}
      rotation={[
        MathUtils.degToRad(rig.pitchDeg ?? 0),
        MathUtils.degToRad(rig.yawDeg ?? 0),
        MathUtils.degToRad(rig.rollDeg ?? 0),
        'YXZ',
      ]}
    >
      {shape === 'tube' && tube('tube', 0, 0, false)}
      {shape === 'truss' &&
        TRUSS_CHORDS.map(([y, z]) => tube(`${y}${z}`, (y * TRUSS_SECTION_M) / 2, (z * TRUSS_SECTION_M) / 2, false))}
      {guide && editMode && tube('guide', 0, 0, true)}
      {pickable && (
        <mesh
          onClick={onPick}
          // Hover is claimed only while editing, so a bar never hides the fixtures hung under it.
          onPointerOver={editMode ? (e) => { e.stopPropagation(); setHovered(true) } : undefined}
          onPointerOut={editMode ? () => setHovered(false) : undefined}
        >
          <boxGeometry args={[length, shape === 'truss' ? TRUSS_SECTION_M + PICK_SECTION_M / 2 : PICK_SECTION_M, shape === 'truss' ? TRUSS_SECTION_M + PICK_SECTION_M / 2 : PICK_SECTION_M]} />
          <meshBasicMaterial visible={false} />
        </mesh>
      )}
      <StageLabel position={LABEL_OFFSET} kind="position" emphasised={selected || (!!editMode && hovered)}>
        {rig.name}
      </StageLabel>
    </group>
  )
}

const NO_PICK = () => {}
