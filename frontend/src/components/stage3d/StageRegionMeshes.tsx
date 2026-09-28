import { useMemo, useState } from 'react'
import { Edges, useCursor } from '@react-three/drei'
import { type ThreeEvent } from '@react-three/fiber'
import { Color, MathUtils, Plane, Vector3, type Object3D } from 'three'
import type { StageRegionDto } from '../../api/stageRegionApi'
import type { RegionPositionUpdate } from './Stage3D'
import { toThree, fromThree } from '../../lib/stageCoords'
import { useBodyDrag } from './useBodyDrag'
import { snap, SNAP_DISTANCE_M } from './useShiftHeld'
import { StageLabel } from './StageLabel'

interface StageRegionMeshesProps {
  regions: StageRegionDto[]
  selectedUuid?: string | null
  editMode?: boolean
  showLabel?: boolean
  onClick?: (region: StageRegionDto, mesh: Object3D) => void
  /** Body drag emits a horizontal move (centerX/Y change, everything else
   *  unchanged). Only present in edit mode; absent disables body drag. */
  onMove?: (region: StageRegionDto, next: RegionPositionUpdate, settled: boolean) => void
  /** Read-only Shift-held ref shared with handle drags for grid-snap parity. */
  snapActiveRef?: React.RefObject<boolean>
  /** Called on drag promotion / settle so the parent can toggle OrbitControls. */
  onDragStart?: () => void
  onDragEnd?: () => void
}

export function StageRegionMeshes({
  regions,
  selectedUuid,
  editMode,
  showLabel,
  onClick,
  onMove,
  snapActiveRef,
  onDragStart,
  onDragEnd,
}: StageRegionMeshesProps) {
  return (
    <>
      {regions.map((region) => (
        <RegionMesh
          key={region.uuid}
          region={region}
          selected={region.uuid === selectedUuid}
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

interface RegionMeshProps {
  region: StageRegionDto
  selected: boolean
  editMode?: boolean
  showLabel?: boolean
  onClick?: (region: StageRegionDto, mesh: Object3D) => void
  onMove?: (region: StageRegionDto, next: RegionPositionUpdate, settled: boolean) => void
  snapActiveRef?: React.RefObject<boolean>
  onDragStart?: () => void
  onDragEnd?: () => void
}

// Base slate-blue rotated ±20° in hue by a stable hash of the uuid so multiple
// regions are visually distinguishable without straying from the muted palette.
const BASE_REGION_HSL = { h: 210 / 360, s: 0.22, l: 0.29 }
const BASE_EDGE_HSL = { h: 205 / 360, s: 0.30, l: 0.65 }
const BASE_REGION_HSL_SELECTED = { h: 210 / 360, s: 0.30, l: 0.45 }
const BASE_EDGE_HSL_SELECTED = { h: 205 / 360, s: 0.40, l: 0.78 }

const TMP_COLOR = new Color()
const PLANE_NORMAL_UP = new Vector3(0, 1, 0)

function hueShiftForUuid(uuid: string): number {
  let h = 0
  for (let i = 0; i < uuid.length; i++) h = (h * 31 + uuid.charCodeAt(i)) | 0
  // Range: roughly ±0.055 of hue space (~ ±20°)
  return ((Math.abs(h) % 1000) / 1000 - 0.5) * 0.11
}

function colorWithHueShift(base: { h: number; s: number; l: number }, shift: number): string {
  TMP_COLOR.setHSL((base.h + shift + 1) % 1, base.s, base.l)
  return `#${TMP_COLOR.getHexString()}`
}

function RegionMesh({
  region,
  selected,
  editMode,
  showLabel,
  onClick,
  onMove,
  snapActiveRef,
  onDragStart,
  onDragEnd,
}: RegionMeshProps) {
  const [hovered, setHovered] = useState(false)
  useCursor(!!editMode && hovered)
  const startBodyDrag = useBodyDrag()

  const cx = region.centerX ?? 0
  const cy = region.centerY ?? 0
  const cz = region.centerZ ?? 0
  const w = region.widthM ?? 1
  const d = region.depthM ?? 1
  const h = region.heightM ?? 1
  const yawDeg = region.yawDeg ?? 0

  // toThree swizzles lighting (X, Y, Z) → R3F (X, Z, -Y); region centre
  // is the floor of the box so we lift the box up by half its height.
  const pos = toThree(cx, cy, cz + h / 2)

  const shift = useMemo(() => hueShiftForUuid(region.uuid), [region.uuid])
  const active = selected || (!!editMode && hovered)
  const fillColor = active
    ? colorWithHueShift(BASE_REGION_HSL_SELECTED, shift)
    : colorWithHueShift(BASE_REGION_HSL, shift)
  const edgeColor = active
    ? colorWithHueShift(BASE_EDGE_HSL_SELECTED, shift)
    : colorWithHueShift(BASE_EDGE_HSL, shift)

  const onPointerDown = (e: ThreeEvent<PointerEvent>) => {
    const mesh = e.eventObject
    startBodyDrag(e, {
      onClick: onClick ? () => onClick(region, mesh) : undefined,
      onDragStart,
      onDragEnd,
      buildDrag: onMove && selected
        ? () => {
            const handleWorld = toThree(cx, cy, cz)
            const plane = new Plane(PLANE_NORMAL_UP, -handleWorld.y)
            const emit = (p: Vector3, settled: boolean) => {
              const { x, y } = fromThree(p)
              const sx = snapActiveRef?.current ? snap(x, SNAP_DISTANCE_M) : x
              const sy = snapActiveRef?.current ? snap(y, SNAP_DISTANCE_M) : y
              onMove(region, { centerX: sx, centerY: sy, centerZ: cz, yawDeg }, settled)
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
      rotation={[0, MathUtils.degToRad(yawDeg), 0]}
      onPointerDown={onClick || onMove ? onPointerDown : undefined}
      onPointerOver={editMode ? (e) => { e.stopPropagation(); setHovered(true) } : undefined}
      onPointerOut={editMode ? () => setHovered(false) : undefined}
    >
      <boxGeometry args={[w, h, d]} />
      <meshStandardMaterial color={fillColor} transparent opacity={active ? 0.38 : 0.28} />
      <Edges color={edgeColor} />
      {showLabel && (
        <StageLabel position={[0, h / 2 + 0.05, 0]}>{region.name}</StageLabel>
      )}
    </mesh>
  )
}
