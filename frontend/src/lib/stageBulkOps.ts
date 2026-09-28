// Bulk placement operations — align, distribute, array-along-truss, mirror.
//
// Pure functions over DTOs, deliberately free of React and RTK so they can be
// unit-tested against hand-computed expectations. The component layer only has to
// gather a selection and hand the result to `commitPlacements`.
//
// **Every op follows the same three steps, and none may short-circuit them:**
//
//   1. lift each fixture to its **world** lighting position
//   2. compute the new world position (in screen-metre space, via the projection)
//   3. lower each result back into *that fixture's own* frame
//
// Step 3 is per-fixture because a selection routinely mixes free fixtures with
// fixtures on different trusses. Writing a computed world coordinate straight into
// `stageX/Y/Z` would be correct for the free ones and silently wrong for the
// mounted ones, whose stage* are offsets in a rotated frame.

import type { FixturePatch } from '../api/patchApi'
import type { RiggingDto } from '../api/riggingApi'
import type { PlacementChange } from '../store/stagePlacement'
import { placementFromWorldLighting, worldPositionLighting } from './stageCoords'
import { DEFAULT_RIGGING_LENGTH_M, worldEndpointsFor } from './stageGeometry'
import {
  project,
  unproject,
  type LightingPoint,
  type ScreenPoint,
  type StageProjection,
} from './stageProjection'

/** Default gap left at each end of a truss when spacing fixtures evenly. */
const DEFAULT_ARRAY_INSET_M = 0.25
/** Default drop below a truss for a newly hung fixture, in metres. */
export const DEFAULT_DROP_M = 0.3

export interface BulkTarget {
  patch: FixturePatch
  /**
   * The fixture's mounting frame, or null when it hangs free.
   *
   * Also null when `patch.riggingUuid` *dangles* — a rig deleted by another
   * operator. That matches `worldPositionLighting`, which falls through to
   * treating stage* as world coords in that case; resolving it any other way
   * would make the lift and the lower disagree.
   */
  rig: RiggingDto | null
  /** Composed world position in lighting metres. */
  world: LightingPoint
}

/**
 * Pairs each patch with its mounting frame and world position.
 *
 * Patches with no resolvable position are dropped: `worldPositionLighting`
 * returns null when `stageX` or `stageY` is null, and an op that has no starting
 * position for a fixture has nothing to align it *from*. Those are exactly the
 * fixtures the unplaced tray exists to place first.
 */
export function resolveBulkTargets(
  patches: FixturePatch[],
  riggings: RiggingDto[],
): BulkTarget[] {
  const out: BulkTarget[] = []
  for (const patch of patches) {
    const world = worldPositionLighting(patch, riggings)
    if (!world) continue
    const rig = patch.riggingUuid
      ? riggings.find((r) => r.uuid === patch.riggingUuid) ?? null
      : null
    out.push({ patch, rig, world })
  }
  return out
}

/**
 * Lowers a computed world position back into a target's own frame.
 *
 * **Never emits `riggingUuid`.** Only `arrayAlongRigging`, `setMountTargets` and
 * an explicit drag-to-parent may change what a fixture is bolted to; an align or
 * a nudge must not re-parent anything as a side effect.
 */
export function lowerToPlacement(t: BulkTarget, world: LightingPoint): PlacementChange {
  return { patchId: t.patch.id, ...placementFromWorldLighting(world, t.rig) }
}

// — align ————————————————————————————————————————————————————————————

export type AlignEdge = 'min-h' | 'max-h' | 'centre-h' | 'min-v' | 'max-v' | 'centre-v'

export interface ArrangeResult {
  changes: PlacementChange[]
  /** Patch ids left alone because they're bolted to a rigging. */
  skipped: number[]
  warnings: string[]
}

/**
 * Rig-mounted fixtures are excluded from free-space arrangement.
 *
 * A fixture on a truss has a **one-dimensional** position — somewhere along the
 * bar. Moving it to an arbitrary world coordinate and lowering that back into the
 * rig's frame produces a non-zero local Y and Z, which physically means the
 * fixture has left the bar and is floating beside it. Nothing downstream catches
 * that: the server's only bound is ±500 m.
 *
 * So align, distribute and set-height operate on free fixtures, and
 * `arrayAlongRigging` is the on-bar equivalent. The warning says so, because
 * "nothing happened to 8 of my 12 fixtures" needs an explanation.
 */
function partitionFree(targets: BulkTarget[], action: string): {
  free: BulkTarget[]
  skipped: number[]
  warnings: string[]
} {
  const free = targets.filter((t) => t.rig == null)
  const skipped = targets.filter((t) => t.rig != null).map((t) => t.patch.id)
  const warnings =
    skipped.length > 0
      ? [
          `${skipped.length} of ${targets.length} are on rigging and were not ${action} — use “space evenly along truss” for those.`,
        ]
      : []
  return { free, skipped, warnings }
}

/**
 * Aligns every free target onto a common screen row or column.
 *
 * Centre is the **bounding-box midpoint**, not the mean of the positions. That's
 * what "centre" means in every drawing tool, and the mean drifts toward whichever
 * end happens to have more fixtures clustered on it.
 */
export function alignTargets(
  targets: BulkTarget[],
  proj: StageProjection,
  edge: AlignEdge,
): ArrangeResult {
  const { free, skipped, warnings } = partitionFree(targets, 'aligned')
  if (free.length < 2) return { changes: [], skipped, warnings }

  const onH = edge.endsWith('-h')
  const screens = free.map((t) => project(t.world, proj))
  const values = screens.map((s) => (onH ? s.h : s.v))
  const lo = Math.min(...values)
  const hi = Math.max(...values)
  const target = edge.startsWith('min') ? lo : edge.startsWith('max') ? hi : (lo + hi) / 2

  const changes = free.map((t, i) => {
    const s = onH ? { h: target, v: screens[i].v } : { h: screens[i].h, v: target }
    return lowerToPlacement(t, unproject(s, proj, t.world))
  })
  return { changes, skipped, warnings }
}

// — distribute ————————————————————————————————————————————————————————

/**
 * Spaces targets evenly between the two extremes, which stay put.
 *
 * Equal **centre** spacing, not equal gaps: the DTO carries no per-fixture width,
 * and centre spacing is what a lighting plot wants anyway.
 *
 * Returns [] below three targets — with two there is nothing between the pinned
 * ends to move, so the caller should disable the control rather than show a
 * no-op.
 */
export function distributeTargets(
  targets: BulkTarget[],
  proj: StageProjection,
  axis: 'h' | 'v',
): ArrangeResult {
  const { free, skipped, warnings } = partitionFree(targets, 'distributed')
  if (free.length < 3) return { changes: [], skipped, warnings }

  const screens = free.map((t, i) => ({ i, s: project(t.world, proj) }))
  const key = (s: { h: number; v: number }) => (axis === 'h' ? s.h : s.v)
  screens.sort((a, b) => key(a.s) - key(b.s))

  const first = key(screens[0].s)
  const last = key(screens[screens.length - 1].s)
  const step = (last - first) / (screens.length - 1)

  const changes: PlacementChange[] = []
  for (let k = 1; k < screens.length - 1; k++) {
    const { i, s } = screens[k]
    const t = free[i]
    const value = first + k * step
    const next = axis === 'h' ? { h: value, v: s.v } : { h: s.h, v: value }
    changes.push(lowerToPlacement(t, unproject(next, proj, t.world)))
  }
  return { changes, skipped, warnings }
}

// — array along a rigging ——————————————————————————————————————————————

/**
 * What `arrayAlongRigging` needs of a fixture: an id, and nothing else.
 *
 * Deliberately looser than [BulkTarget]. Every other op transforms an existing
 * world position and therefore requires one, but this op *assigns* position
 * outright — so demanding a resolvable starting point would exclude exactly the
 * fixtures the operation exists for. An unplaced patch has no world position by
 * definition, and "hang these forty un-positioned fixtures on LX1" is the whole
 * point of the tray. `BulkTarget` still satisfies this shape, so a mixed
 * selection of placed and unplaced fixtures works unchanged.
 */
export interface ArrayTarget {
  patch: { id: number }
}

export interface ArrayAlongRiggingOptions {
  rig: RiggingDto
  /** Even spacing across the usable length, or a fixed centre-to-centre pitch. */
  spacing:
    | { mode: 'even'; insetM?: number }
    | { mode: 'pitch'; pitchM: number; anchor?: 'centre' | 'start' }
  /** Offset out from the bar, in the rig's local Y. */
  offsetYM?: number
  /** How far BELOW the bar to hang, in metres. Positive means down. */
  dropM?: number
}

export interface ArrayAlongRiggingResult {
  changes: PlacementChange[]
  warnings: string[]
}

/**
 * Hangs an ordered set of fixtures along a truss.
 *
 * The bar is **centred on the rig origin** — `RiggingMeshes` builds it as
 * `boxGeometry(length, …)` with no positional offset — so local X spans
 * `[-L/2, +L/2]` rather than `[0, L]`.
 *
 * This is the one op that **discards world position**: it writes local
 * coordinates directly and takes whatever world position falls out. Free
 * fixtures will visibly jump onto the bar, so the caller must say so before
 * running it.
 *
 * `dropM` is negated on the way in. Local +Z is up (it maps to R3F +Y), even
 * though the patch form labels the field "drop" — so hanging 0.3 m below the bar
 * means writing `stageZ = -0.3`. Getting that backwards puts every fixture in the
 * array *above* the truss, which is invisible in a plan view.
 *
 * Overflow is reported, never silently clamped: the only server-side bound on a
 * stage coordinate is ±500 m, so a fixture two metres past the end of its truss
 * is accepted and then drawn floating in mid-air.
 *
 * Takes [ArrayTarget], not [BulkTarget]: unplaced fixtures must be hangable.
 */
export function arrayAlongRigging(
  targets: ArrayTarget[],
  options: ArrayAlongRiggingOptions,
): ArrayAlongRiggingResult {
  const { rig, spacing, offsetYM = 0, dropM = DEFAULT_DROP_M } = options
  const n = targets.length
  if (n === 0) return { changes: [], warnings: [] }

  const lengthM = rig.lengthM ?? DEFAULT_RIGGING_LENGTH_M
  const half = lengthM / 2
  const xs: number[] = []

  if (spacing.mode === 'even') {
    const inset = Math.min(spacing.insetM ?? DEFAULT_ARRAY_INSET_M, half)
    if (n === 1) {
      xs.push(0)
    } else {
      const usable = lengthM - 2 * inset
      for (let k = 0; k < n; k++) xs.push(-half + inset + (k * usable) / (n - 1))
    }
  } else {
    const pitch = spacing.pitchM
    const anchor = spacing.anchor ?? 'centre'
    for (let k = 0; k < n; k++) {
      xs.push(anchor === 'centre' ? (k - (n - 1) / 2) * pitch : -half + k * pitch)
    }
  }

  const warnings: string[] = []
  const overflow = xs.filter((x) => Math.abs(x) > half + 1e-9).length
  if (overflow > 0) {
    warnings.push(
      `${overflow} of ${n} fixture${overflow === 1 ? '' : 's'} fall past the end of ${rig.name} (${lengthM.toFixed(2)} m).`,
    )
  }

  const changes = targets.map((t, k) => ({
    patchId: t.patch.id,
    riggingUuid: rig.uuid,
    stageX: xs[k],
    stageY: offsetYM,
    stageZ: -dropM,
  }))
  return { changes, warnings }
}

// — mirror ————————————————————————————————————————————————————————————

export interface MirrorOptions {
  axis?: 'h' | 'v'
  /** Screen-metre coordinate to reflect about. Defaults to 0 — the stage centre line. */
  aboutM?: number
  /** Negate `baseYawDeg` too. Off by default: see the note below. */
  alsoFlipYaw?: boolean
  /** Mirror rig-mounted fixtures by un-parenting them rather than skipping. */
  unparentMounted?: boolean
}

export interface MirrorResult {
  changes: PlacementChange[]
  /** Patch ids left untouched because they're mounted and `unparentMounted` is off. */
  skipped: number[]
  warnings: string[]
}

/**
 * Reflects targets about a line — by default the stage centre line, X = 0.
 *
 * **Rig-mounted fixtures are skipped unless `unparentMounted` is set.** Reflecting
 * a fixture about the world centre line and then lowering it back into the *same*
 * truss's frame usually lands it well past the end of that truss: the reflected
 * world point simply isn't on the bar any more. Nothing downstream would catch
 * it, so the honest options are to leave it alone or to cut it free, and the
 * caller has to choose.
 *
 * `alsoFlipYaw` defaults off because `baseYawDeg` feeds `dmxToDegrees` as an
 * additive base for live pan; negating it can point a moving head into the back
 * wall rather than mirroring its coverage.
 */
export function mirrorTargets(
  targets: BulkTarget[],
  proj: StageProjection,
  options: MirrorOptions = {},
): MirrorResult {
  const { axis = 'h', aboutM = 0, alsoFlipYaw = false, unparentMounted = false } = options
  const changes: PlacementChange[] = []
  const skipped: number[] = []
  const warnings: string[] = []

  for (const t of targets) {
    if (t.rig && !unparentMounted) {
      skipped.push(t.patch.id)
      continue
    }
    const s = project(t.world, proj)
    const reflected =
      axis === 'h' ? { h: 2 * aboutM - s.h, v: s.v } : { h: s.h, v: 2 * aboutM - s.v }
    const world = unproject(reflected, proj, t.world)

    // When cutting a mounted fixture free, lower into world space (rig = null)
    // and say so explicitly, rather than reinterpreting the reflected point in
    // the old rig's rotated frame.
    const frame: BulkTarget = t.rig && unparentMounted ? { ...t, rig: null } : t
    const change: PlacementChange = lowerToPlacement(frame, world)
    if (t.rig && unparentMounted) change.riggingUuid = null
    if (alsoFlipYaw && t.patch.baseYawDeg != null) change.baseYawDeg = -t.patch.baseYawDeg
    changes.push(change)
  }

  if (skipped.length > 0) {
    warnings.push(
      `${skipped.length} of ${targets.length} are hung on rigging and were not mirrored.`,
    )
  }
  return { changes, skipped, warnings }
}

// — set height / depth ————————————————————————————————————————————————

/**
 * Puts every target at one common height (or, in plan view, one common depth).
 *
 * Rig-mounted fixtures are excluded, for the same reason as align: on a rolled
 * bar a common world height isn't reachable without leaving the bar. Levelling
 * the truss itself is the operation for that.
 *
 * `valueM` is in lighting terms for the projection's vertical axis (metres up for
 * an elevation, metres upstage for the plan), not screen-down.
 */
export function setDepthTargets(
  targets: BulkTarget[],
  proj: StageProjection,
  valueM: number,
): ArrangeResult {
  // Same exclusion as align: forcing a common height on a fixture bolted to a
  // rolled bar would lift it off the bar. Use the truss's own roll for that.
  const { free, skipped, warnings } = partitionFree(targets, 'moved')
  const changes = free.map((t) => {
    const world: LightingPoint = { ...t.world, [proj.v.axis]: valueM }
    return lowerToPlacement(t, world)
  })
  return { changes, skipped, warnings }
}

// — re-parent ————————————————————————————————————————————————————————

/**
 * Moves fixtures onto (or off) a rigging **without moving them in space**.
 *
 * Lifts with the old frame and lowers with the new, so the world position is
 * unchanged and only the representation differs.
 *
 * The single-patch Mounting dropdown does not currently do this — it swaps
 * `riggingUuid` and leaves `stage*` alone, so the old world coordinates get
 * reinterpreted as offsets in the new rig's rotated frame and the fixture
 * teleports. This helper is the fix for both paths.
 */
export function setMountTargets(
  targets: BulkTarget[],
  rig: RiggingDto | null,
): PlacementChange[] {
  return targets.map((t) => ({
    patchId: t.patch.id,
    riggingUuid: rig?.uuid ?? null,
    ...placementFromWorldLighting(t.world, rig),
  }))
}

/**
 * Bolts a fixture onto a rigging at the point it was dropped.
 *
 * `stageY` is forced to zero: the off-axis component of a plan-view pointer is
 * noise, and the intent of dropping onto a bar is "hang it on that bar", not
 * "hang it 4 cm to one side of that bar". `stageX` is clamped to the bar, and the
 * existing drop is kept if the fixture was already mounted so re-hanging it on a
 * different truss doesn't silently change its trim height.
 *
 * Uses `placementFromWorldLighting` with the destination rig named explicitly.
 * Reaching for `patchPlacementFromWorld` here would be a serious bug: it reads
 * the frame off `patch.riggingUuid`, so it would compute offsets in the fixture's
 * **old** rig's frame and store them against the new rig's uuid. The numbers stay
 * inside the server's ±500 m bound, the write succeeds, and the fixture is drawn
 * somewhere plausible but wrong.
 */
export function dropOntoRigging(
  target: BulkTarget,
  worldDrop: LightingPoint,
  rig: RiggingDto,
  options: { defaultDropM?: number } = {},
): { change: PlacementChange; clampedX: boolean } {
  const local = placementFromWorldLighting(worldDrop, rig)
  const half = (rig.lengthM ?? DEFAULT_RIGGING_LENGTH_M) / 2
  const stageX = Math.max(-half, Math.min(half, local.stageX))
  const wasMounted = target.rig != null
  return {
    change: {
      patchId: target.patch.id,
      riggingUuid: rig.uuid,
      stageX,
      stageY: 0,
      stageZ: wasMounted
        ? target.patch.stageZ ?? -(options.defaultDropM ?? DEFAULT_DROP_M)
        : -(options.defaultDropM ?? DEFAULT_DROP_M),
    },
    clampedX: Math.abs(stageX - local.stageX) > 1e-9,
  }
}

/** Cuts a fixture free from its rigging **without moving it** in world space. */
export function unparentPreservingWorld(target: BulkTarget): PlacementChange {
  return {
    patchId: target.patch.id,
    riggingUuid: null,
    stageX: target.world.x,
    stageY: target.world.y,
    stageZ: target.world.z,
  }
}

/**
 * The rigging nearest a screen point, within `toleranceM`, or null.
 *
 * Ties break on height — you drop onto the bar in front. Edge-on bars are
 * excluded: they project to a point, so there's no along-the-bar position to
 * derive and `localXAlongBar` would have no direction to work with.
 */
export function riggingUnderPoint(
  point: ScreenPoint,
  riggings: RiggingDto[],
  proj: StageProjection,
  toleranceM: number,
): RiggingDto | null {
  let best: { rig: RiggingDto; distance: number; height: number } | null = null
  for (const rig of riggings) {
    const [wa, wb] = worldEndpointsFor(rig)
    const a = project(wa, proj)
    const b = project(wb, proj)
    const dh = b.h - a.h
    const dv = b.v - a.v
    const lenSq = dh * dh + dv * dv
    if (lenSq < 1e-12) continue
    const t = Math.max(0, Math.min(1, ((point.h - a.h) * dh + (point.v - a.v) * dv) / lenSq))
    const distance = Math.hypot(point.h - (a.h + dh * t), point.v - (a.v + dv * t))
    if (distance > toleranceM) continue
    const height = Math.max(wa.z, wb.z)
    if (best == null || distance < best.distance || (distance === best.distance && height > best.height)) {
      best = { rig, distance, height }
    }
  }
  return best?.rig ?? null
}

/** Clears a fixture's placement so it returns to the unplaced tray. */
export function unplaceTargets(patchIds: number[]): PlacementChange[] {
  return patchIds.map((patchId) => ({
    patchId,
    riggingUuid: null,
    stageX: null,
    stageY: null,
    stageZ: null,
  }))
}

// — nudge ————————————————————————————————————————————————————————————

/**
 * Shifts targets by a screen-space delta, in metres.
 *
 * Used by the arrow keys. A free fixture moves in the plane, preserving the world
 * coordinate on the axis this view can't show.
 *
 * A **rig-mounted** fixture slides along its bar instead — the same rule the drag
 * path follows via `localXAlongBar`. Lowering an arbitrary nudged world point into
 * the rig's frame puts a non-zero offset on the bar's local Y, which physically
 * means the fixture has left the bar it is bolted to. That is exactly what
 * `partitionFree` refuses to do for align and distribute, nothing downstream
 * catches it (the server's only bound is ±500 m), and it accumulates: on a bar
 * yawed 9°, every 0.25 m press drifts the fixture ~4 cm off the truss.
 *
 * So the delta is projected onto the bar's own direction, and local Y and Z pass
 * through untouched. A nudge perpendicular to a bar is therefore a no-op — the
 * honest answer, since a fixture on a truss has a one-dimensional position.
 */
export function nudgeTargets(
  targets: BulkTarget[],
  proj: StageProjection,
  deltaH: number,
  deltaV: number,
): PlacementChange[] {
  return targets.map((t) => {
    if (t.rig) return nudgeAlongBar(t, t.rig, proj, deltaH, deltaV)
    const s = project(t.world, proj)
    const world = unproject({ h: s.h + deltaH, v: s.v + deltaV }, proj, t.world)
    return lowerToPlacement(t, world)
  })
}

function nudgeAlongBar(
  t: BulkTarget,
  rig: RiggingDto,
  proj: StageProjection,
  deltaH: number,
  deltaV: number,
): PlacementChange {
  const lengthM = rig.lengthM ?? DEFAULT_RIGGING_LENGTH_M
  const half = lengthM / 2
  const stageX = t.patch.stageX ?? 0
  const unchanged: PlacementChange = {
    patchId: t.patch.id,
    stageX,
    stageY: t.patch.stageY,
    stageZ: t.patch.stageZ,
  }

  const [wa, wb] = worldEndpointsFor(rig)
  const a = project(wa, proj)
  const b = project(wb, proj)
  const dh = b.h - a.h
  const dv = b.v - a.v
  const lenSq = dh * dh + dv * dv
  // Edge-on in this projection: the bar is a point, so there's no direction to
  // slide along. The same refusal the drag path makes.
  if (lenSq === 0) return unchanged

  // Component of the screen delta along the bar, as a fraction of its *projected*
  // length — which is also what converts it back to bar metres, so a foreshortened
  // bar still moves the fixture the distance the user asked for along the truss.
  const fraction = (deltaH * dh + deltaV * dv) / lenSq
  return {
    ...unchanged,
    stageX: Math.max(-half, Math.min(half, stageX + fraction * lengthM)),
  }
}
