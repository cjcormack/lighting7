import { useMemo, useState } from 'react'
import { Edges, useCursor } from '@react-three/drei'
import { type ThreeEvent } from '@react-three/fiber'
import { MathUtils, type Object3D } from 'three'
import type { StageRegionDto } from '../../api/stageRegionApi'
import { toThree } from '../../lib/stageCoords'
import { StageLabel } from './StageLabel'
import { useSurfaceMaterial } from './scene/SurfaceLighting'
import { NO_RAYCAST } from './raycast'
import { isClick } from './dragThreshold'

interface StageRegionMeshesProps {
  regions: StageRegionDto[]
  /** Every selected one — a multi-selection lights as one. */
  selectedUuids?: ReadonlySet<string>
  /** The regions a drawn platform stands for: the platform is their deck, so they draw no surface. */
  linkedUuids: ReadonlySet<string>
  /** Editing: a region shows its outline and name, and a click selects it. */
  editMode?: boolean
  onClick?: (region: StageRegionDto, mesh: Object3D) => void
}

/**
 * The stage's regions. Outside Edit a region is a plain lit deck, drawn only where no platform links
 * to it; in Edit it adds a faint outline and its name and takes a click. It has no body drag:
 * `RegionEditHandles` moves it, so a drag that starts on a region turns the camera.
 */
export function StageRegionMeshes({ regions, selectedUuids, linkedUuids, editMode, onClick }: StageRegionMeshesProps) {
  return (
    <>
      {regions.map((region) => (
        <RegionMesh
          key={region.uuid}
          region={region}
          selected={selectedUuids?.has(region.uuid) ?? false}
          linked={linkedUuids.has(region.uuid)}
          editMode={editMode}
          onClick={onClick}
        />
      ))}
    </>
  )
}

interface RegionMeshProps {
  region: StageRegionDto
  selected: boolean
  linked: boolean
  editMode?: boolean
  onClick?: (region: StageRegionDto, mesh: Object3D) => void
}

/** A bare deck: what a region looks like with no platform modelled over it. */
const DECK_FINISH = { colour: '#34312d', pattern: 'PLAIN', emissive: false } as const
const EDGE_COLOUR = '#5b6472'
const EDGE_HOVER_COLOUR = '#8a93a2'
const EDGE_SELECTED_COLOUR = '#6b8cff'

function RegionMesh({ region, selected, linked, editMode, onClick }: RegionMeshProps) {
  const [hovered, setHovered] = useState(false)
  const pickable = !!editMode && onClick != null
  useCursor(pickable && hovered)

  const w = region.widthM ?? 1
  const d = region.depthM ?? 1
  const h = region.heightM ?? 1
  // toThree swizzles lighting (X, Y, Z) → R3F (X, Z, -Y). `centerZ` is the
  // platform's top surface (the backend's meaning, `worldCornersFor`'s), so the
  // box hangs *down* from it: its centre is half a thickness below.
  const pos = toThree(region.centerX ?? 0, region.centerY ?? 0, (region.centerZ ?? 0) - h / 2)
  // Just above the deck, in the box's own frame (its origin is the box centre).
  const labelOffset = useMemo<[number, number, number]>(() => [0, h / 2 + 0.05, 0], [h])
  // Lit by the light table like the venue's surfaces, and losing every depth tie to a coincident one.
  const deck = useSurfaceMaterial(DECK_FINISH, { behind: true })

  if (linked && !editMode) return null

  const onPick = (e: ThreeEvent<MouseEvent>) => {
    e.stopPropagation()
    if (isClick(e)) onClick?.(region, e.eventObject)
  }

  return (
    <mesh
      position={pos}
      rotation={[0, MathUtils.degToRad(region.yawDeg ?? 0), 0]}
      material={linked ? undefined : deck}
      raycast={pickable ? undefined : NO_RAYCAST}
      onClick={pickable ? onPick : undefined}
      onPointerOver={pickable ? (e) => { e.stopPropagation(); setHovered(true) } : undefined}
      onPointerOut={pickable ? () => setHovered(false) : undefined}
    >
      <boxGeometry args={[w, h, d]} />
      {/* A linked region is the platform's deck: only its outline is drawn over it. */}
      {linked && <meshBasicMaterial visible={false} />}
      {editMode && <Edges color={selected ? EDGE_SELECTED_COLOUR : hovered ? EDGE_HOVER_COLOUR : EDGE_COLOUR} />}
      {editMode && (
        <StageLabel position={labelOffset} kind="position" emphasised={selected || hovered}>
          {region.name}
        </StageLabel>
      )}
    </mesh>
  )
}
