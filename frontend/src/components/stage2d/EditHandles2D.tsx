// Resize / rotate / endpoint overlays for the selected object in a 2D view.
//
// The 3D counterparts (RegionEditHandles, RiggingEndpointHandles) drive the same
// pure derivations from lib/stageGeometry; only the way a pointer position is
// obtained differs. Drawn last so they paint — and therefore hit-test — above the
// bodies they belong to.
//
// **Handle sets differ per projection, deliberately:**
//   plan       corner resize + yaw rotation   (height is out-of-plane)
//   front/side height (top face and floor)    (yaw is unobservable side-on)
// That asymmetry is a property of an orthographic view, not an oversight.

import type { StageRegionDto } from '../../api/stageRegionApi'
import type { RiggingDto } from '../../api/riggingApi'
import type { RegionPositionUpdate, RiggingPositionUpdate } from '../stage/stageEditing'
import {
  deriveFromDraggedCorner,
  deriveFromEndpoints,
  localRotationOffsets,
  pinnedIndexFor,
  rotateXY,
  worldCornersFor,
  worldEndpointsFor,
  type LightingPoint,
} from '../../lib/stageGeometry'
import {
  project,
  unproject,
  type ScreenPoint,
  type StageProjection,
} from '../../lib/stageProjection'
import { SNAP_ANGLE_DEG } from '../stage3d/useShiftHeld'
import type { PlaneDragOptions } from './usePlaneDrag'
import type { SnapGrid } from './useSnapGrid'

const HANDLE_PX = 7
const ROTATION_HANDLE_PX = 6
const ROTATION_OFFSET_M = 0.4
/** Matches MIN_HEIGHT_M in RegionEditHandles. */
const MIN_HEIGHT_M = 0.05

/**
 * Starts a handle drag from a React pointer event.
 *
 * Handles begin dragging on the *first* press — unlike bodies, which need a
 * click to select first — because a visible handle already means "grab me". The
 * implementation must therefore stop propagation, or the press also reaches the
 * background pan handler.
 */
export type StartHandleDrag = (opts: PlaneDragOptions, e: React.PointerEvent) => void

interface CommonProps {
  projection: StageProjection
  mPerPx: number
  snap: SnapGrid
  /** Begins a handle drag; the caller wires this to usePlaneDrag. */
  startDrag: StartHandleDrag
}

// — riggings ————————————————————————————————————————————————————————

interface RiggingHandlesProps extends CommonProps {
  rig: RiggingDto
  onChange: (next: RiggingPositionUpdate, settled: boolean) => void
}

/**
 * The two bar ends. Dragging one moves that end and leaves the other pinned, so
 * the bar's length and heading are re-derived from the pair.
 *
 * The dragged endpoint keeps its own out-of-plane coordinate — moving an end in
 * the front elevation changes its X and height but not how far upstage it is.
 * `deriveFromEndpoints` cannot recover pitch (a twist about the bar's own axis
 * leaves both endpoints where they are), so it reports 0, matching the 3D handles.
 */
export function RiggingEndpointHandles2D({
  rig,
  projection,
  mPerPx,
  snap,
  startDrag,
  onChange,
}: RiggingHandlesProps) {
  const ends = worldEndpointsFor(rig)
  const screens = ends.map((p) => project(p, projection))
  const r = HANDLE_PX * mPerPx

  const onDown = (idx: 0 | 1) => (e: React.PointerEvent) => {
    // Capture both endpoints at drag start: the source array is rebuilt when the
    // optimistic cache write lands mid-drag, and the pinned end must not follow.
    const dragged = ends[idx]
    const pinned = ends[idx === 0 ? 1 : 0]
    const anchor = project(dragged, projection)

    const emit = (p: ScreenPoint, settled: boolean) => {
      const snapped = { h: snap.snapValue(p.h), v: snap.snapValue(p.v) }
      const world = unproject(snapped, projection, dragged)
      // Canonical (A = index 0, B = index 1) order, so yaw and roll describe the
      // bar's +X direction consistently regardless of which end was grabbed.
      const d =
        idx === 0 ? deriveFromEndpoints(world, pinned) : deriveFromEndpoints(pinned, world)
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
        anchor,
        onDrag: (p) => emit(p, false),
        onSettle: (last) => emit(last ?? anchor, true),
      },
      e,
    )
  }

  return (
    <g>
      {screens.map((s, i) => (
        <circle
          key={i}
          cx={s.h}
          cy={s.v}
          r={r}
          fill="#ffe082"
          stroke="#7a5c10"
          strokeWidth={1}
          vectorEffect="non-scaling-stroke"
          style={{ cursor: 'grab' }}
          pointerEvents="all"
          onPointerDown={onDown(i as 0 | 1)}
        />
      ))}
    </g>
  )
}

// — regions ————————————————————————————————————————————————————————

interface RegionHandlesProps extends CommonProps {
  region: StageRegionDto
  onChange: (next: RegionPositionUpdate, settled: boolean) => void
}

export function RegionEditHandles2D(props: RegionHandlesProps) {
  return props.projection.id === 'plan' ? (
    <RegionPlanHandles {...props} />
  ) : (
    <RegionHeightHandles {...props} />
  )
}

/** Corner resize plus yaw rotation — both only meaningful looking straight down. */
function RegionPlanHandles({
  region,
  projection,
  mPerPx,
  snap,
  startDrag,
  onChange,
}: RegionHandlesProps) {
  const corners = worldCornersFor(region)
  const cx = region.centerX ?? 0
  const cy = region.centerY ?? 0
  const cz = region.centerZ ?? 0
  const yawDeg = region.yawDeg ?? 0
  const w = region.widthM ?? 1
  const d = region.depthM ?? 1
  const r = HANDLE_PX * mPerPx

  // Floor corners only: in plan the top four project onto them exactly.
  const floor = corners.slice(0, 4)

  const onCornerDown = (idx: number) => (e: React.PointerEvent) => {
    const pinnedIdx = pinnedIndexFor(idx)
    const pinnedX = corners[pinnedIdx][0]
    const pinnedY = corners[pinnedIdx][1]
    const lockedYaw = yawDeg
    const lockedCz = cz
    const start: LightingPoint = { x: floor[idx][0], y: floor[idx][1], z: floor[idx][2] }
    const anchor = project(start, projection)

    const emit = (p: ScreenPoint, settled: boolean) => {
      const snapped = { h: snap.snapValue(p.h), v: snap.snapValue(p.v) }
      const world = unproject(snapped, projection, start)
      const next = deriveFromDraggedCorner(world.x, world.y, pinnedX, pinnedY, lockedYaw)
      onChange(
        {
          centerX: next.centerX,
          centerY: next.centerY,
          centerZ: lockedCz,
          yawDeg: lockedYaw,
          widthM: next.widthM,
          depthM: next.depthM,
        },
        settled,
      )
    }

    startDrag(
      { anchor, onDrag: (p) => emit(p, false), onSettle: (last) => emit(last ?? anchor, true) },
      e,
    )
  }

  const yawRad = (yawDeg * Math.PI) / 180
  const rotations = localRotationOffsets(w, d, ROTATION_OFFSET_M)
    .map(([lx, ly]) => rotateXY(lx, ly, yawRad))
    .map(([rx, ry]) => ({ x: cx + rx, y: cy + ry, z: cz }))

  const onRotateDown = (idx: number) => (e: React.PointerEvent) => {
    const start = rotations[idx]
    const anchor = project(start, projection)
    const startAngle = Math.atan2(start.y - cy, start.x - cx)
    const startYaw = yawDeg

    const emit = (p: ScreenPoint, settled: boolean) => {
      // Rotation is an angle about the centre, so the pointer is used raw — grid
      // snapping the position first would quantise the angle unevenly.
      const world = unproject(p, projection, start)
      const angle = Math.atan2(world.y - cy, world.x - cx)
      let next = startYaw + ((angle - startAngle) * 180) / Math.PI
      if (snap.activeRef.current) next = Math.round(next / SNAP_ANGLE_DEG) * SNAP_ANGLE_DEG
      onChange({ centerX: cx, centerY: cy, centerZ: cz, yawDeg: next }, settled)
    }

    startDrag(
      { anchor, onDrag: (p) => emit(p, false), onSettle: (last) => emit(last ?? anchor, true) },
      e,
    )
  }

  return (
    <g>
      {floor.map(([x, y, z], i) => {
        const s = project({ x, y, z }, projection)
        return (
          <rect
            key={`c${i}`}
            x={s.h - r}
            y={s.v - r}
            width={r * 2}
            height={r * 2}
            fill="#9fc1d8"
            stroke="#2b3d4a"
            strokeWidth={1}
            vectorEffect="non-scaling-stroke"
            style={{ cursor: 'nwse-resize' }}
            pointerEvents="all"
            onPointerDown={onCornerDown(i)}
          />
        )
      })}
      {rotations.map((pt, i) => {
        const s = project(pt, projection)
        return (
          <circle
            key={`r${i}`}
            cx={s.h}
            cy={s.v}
            r={ROTATION_HANDLE_PX * mPerPx}
            fill="#d8b89f"
            stroke="#4a3a2a"
            strokeWidth={1}
            vectorEffect="non-scaling-stroke"
            style={{ cursor: 'grab' }}
            pointerEvents="all"
            onPointerDown={onRotateDown(i)}
          />
        )
      })}
    </g>
  )
}

/**
 * Top-face and floor handles, for the elevations where height is in-plane.
 *
 * Dragging the top changes `heightM`; dragging the floor moves `centerZ` and
 * compensates `heightM` so the top face stays put — the same split as the 3D
 * handles, and the reason `centerZ` is documented as the box's floor.
 */
function RegionHeightHandles({
  region,
  projection,
  mPerPx,
  snap,
  startDrag,
  onChange,
}: RegionHandlesProps) {
  const cx = region.centerX ?? 0
  const cy = region.centerY ?? 0
  const cz = region.centerZ ?? 0
  const h = region.heightM ?? 1
  const yawDeg = region.yawDeg ?? 0
  const r = HANDLE_PX * mPerPx

  const onHeightDown = (tier: 'top' | 'floor') => (e: React.PointerEvent) => {
    const start: LightingPoint = { x: cx, y: cy, z: tier === 'top' ? cz + h : cz }
    const anchor = project(start, projection)
    const lockedCz = cz
    const lockedTopZ = cz + h

    const emit = (p: ScreenPoint, settled: boolean) => {
      // Only the vertical axis matters; lock h to the anchor so the box can't
      // slide sideways while the user is setting a height.
      const world = unproject({ h: anchor.h, v: snap.snapValue(p.v) }, projection, start)
      if (tier === 'top') {
        const heightM = Math.max(MIN_HEIGHT_M, world.z - lockedCz)
        onChange({ centerX: cx, centerY: cy, centerZ: lockedCz, yawDeg, heightM }, settled)
      } else {
        let centerZ = world.z
        let heightM = lockedTopZ - centerZ
        if (heightM < MIN_HEIGHT_M) {
          heightM = MIN_HEIGHT_M
          centerZ = lockedTopZ - MIN_HEIGHT_M
        }
        onChange({ centerX: cx, centerY: cy, centerZ, yawDeg, heightM }, settled)
      }
    }

    startDrag(
      {
        anchor,
        lockAxis: 'v',
        onDrag: (p) => emit(p, false),
        onSettle: (last) => emit(last ?? anchor, true),
      },
      e,
    )
  }

  const tiers: Array<{ tier: 'top' | 'floor'; z: number }> = [
    { tier: 'top', z: cz + h },
    { tier: 'floor', z: cz },
  ]

  return (
    <g>
      {tiers.map(({ tier, z }) => {
        const s = project({ x: cx, y: cy, z }, projection)
        return (
          <rect
            key={tier}
            x={s.h - r * 2}
            y={s.v - r / 2}
            width={r * 4}
            height={r}
            fill="#a0e0c0"
            stroke="#20402f"
            strokeWidth={1}
            vectorEffect="non-scaling-stroke"
            style={{ cursor: 'ns-resize' }}
            pointerEvents="all"
            onPointerDown={onHeightDown(tier)}
          />
        )
      })}
    </g>
  )
}
