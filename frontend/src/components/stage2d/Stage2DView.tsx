import { useCallback, useMemo, useRef, useState } from 'react'
import { useStageRegionListQuery } from '../../store/stageRegions'
import { useRiggingListQuery } from '../../store/riggings'
import { useProjectedPatches } from '../../hooks/useProjectedPatches'
import { useFixtureLookup } from '../../hooks/useFixtureLookup'
import { placementFromWorldLighting, worldPositionLighting } from '../../lib/stageCoords'
import {
  DEFAULT_RIGGING_LENGTH_M,
  deriveFromEndpoints,
  localXAlongBar,
  worldEndpointsFor,
} from '../../lib/stageGeometry'
import {
  project,
  projectionExtent,
  unproject,
  type LightingPoint,
  type ScreenPoint,
  type StageProjection,
} from '../../lib/stageProjection'
import { DEFAULT_VIEW_FLAGS, type StageViewFlags } from '../stage3d/useStageView'
import type {
  PatchPlacementUpdate,
  PlacementPoint,
  RegionPositionUpdate,
  RiggingPositionUpdate,
  Selection,
} from '../stage/stageEditing'
import {
  selectionIntentFor,
  type SelectIntent,
  type SelectionRef,
} from '../stage3d/useStageSelection'
import type { FixturePatch, PatchPlacement } from '../../api/patchApi'
import type { StageRegionDto } from '../../api/stageRegionApi'
import type { RiggingDto } from '../../api/riggingApi'
import {
  dropOntoRigging,
  riggingUnderPoint,
  type BulkTarget,
} from '../../lib/stageBulkOps'
import {
  buildGuideCandidates,
  snapWithGuides,
  type GuideCandidates,
  type GuideSource,
} from '../../lib/stageSnapping'
import { FixtureShapes, RegionShapes, RiggingShapes, projectRigging } from './Stage2DShapes'
import { StageGrid } from './StageGrid'
import { Stage2DHud } from './Stage2DHud'
import { AlignmentGuides } from './AlignmentGuides'
import { RegionEditHandles2D, RiggingEndpointHandles2D } from './EditHandles2D'
import { useSvgMetres, ZOOM_PER_WHEEL_NOTCH } from './useSvgMetres'
import { useBodyDrag2D, usePlaneDrag, type PlaneDragOptions } from './usePlaneDrag'
import type { SnapGrid } from './useSnapGrid'

/** Pointer travel that promotes a background press into a pan. */
const PAN_THRESHOLD_PX = 4
/** How close, in pixels, a drag must come to another object's row/column to snap. */
const GUIDE_TOLERANCE_PX = 6
/** How close a dragged fixture must come to a bar before it bolts onto it. */
const PARENT_SNAP_PX = 12
/** Stable default so an omitted `placementDefault` doesn't change identity. */
const ORIGIN: LightingPoint = { x: 0, y: 0, z: 0 }

export interface Stage2DViewProps {
  projectId: number
  /** Which plane to draw. The component holds no view-mode state of its own, so
   *  a split-pane shell showing two projections at once stays additive. */
  projection: StageProjection
  selection: Selection
  /** Keys of every selected object, for multi-select highlighting. */
  selectedKeys?: ReadonlySet<string>
  /** Marquee result. Absent disables the marquee. */
  onMarqueeSelect?: (refs: SelectionRef[], intent: SelectIntent) => void
  editMode?: boolean
  view?: StageViewFlags
  snap: SnapGrid
  /** Armed placement kind, or null. While set, a background click places. */
  placing?: 'region' | 'rigging' | null
  /**
   * Supplies whichever axis this projection can't learn from a click — Z in plan,
   * Y in the front elevation, X in the side elevation.
   */
  placementDefault?: LightingPoint
  onSelectionChange: (s: Selection, intent?: SelectIntent) => void
  onPlacementClick?: (p: PlacementPoint) => void
  onPatchPlacementChange?: (patch: FixturePatch, next: PatchPlacementUpdate, settled: boolean) => void
  onRegionPositionChange?: (region: StageRegionDto, next: RegionPositionUpdate, settled: boolean) => void
  onRiggingPositionChange?: (rig: RiggingDto, next: RiggingPositionUpdate, settled: boolean) => void
}

/**
 * Orthographic stage view — plan, front elevation, or side elevation.
 *
 * Drawn in an SVG whose viewBox is in **metres**, with
 * `preserveAspectRatio="xMidYMid meet"` doing the fitting. That single decision
 * buys the isotropic scale, pan and zoom, and hit-testing in metre space; the
 * DOM-percentage map this supersedes had a fixed pixel height against a fluid
 * width, so a metre along X and a metre along Y were different lengths on screen.
 *
 * Paint order is hit-test order in SVG, so the R3F raycast-passthrough machinery
 * (`notifyTransformDragStart`, descendant checks) has no analogue here and is
 * deliberately not ported.
 *
 * **Every drag follows one pipeline**, and none may short-circuit it:
 *
 *   world  = worldPositionLighting(patch, riggings)
 *   screen = project(world, projection)          → snap / constrain
 *   world' = unproject(screen', projection, world)   ← preserves the world
 *                                                      out-of-plane coordinate
 *   next   = placementFromWorldLighting(world', rig) → rig-local stage*
 *
 * `unproject` takes the *world* point rather than the stored `stage*` triple
 * because the out-of-plane axis has to be preserved in world space: dragging a
 * truss-mounted fixture in the front elevation must leave its world Y untouched,
 * and on a yawed or rolled truss that is not the same as leaving its local Y
 * untouched.
 */
export function Stage2DView({
  projectId,
  projection,
  selection,
  selectedKeys,
  onMarqueeSelect,
  editMode = false,
  view = DEFAULT_VIEW_FLAGS,
  snap,
  placing = null,
  placementDefault = ORIGIN,
  onSelectionChange,
  onPlacementClick,
  onPatchPlacementChange,
  onRegionPositionChange,
  onRiggingPositionChange,
}: Stage2DViewProps) {
  const selectedPatchKey = selection?.kind === 'patch' ? selection.patchKey : null
  const { points, extraPoints, dims } = useProjectedPatches(projectId, {
    projection,
    includeKey: selectedPatchKey,
  })
  // Everything drawn: each fixture's own placement, then a paired dimmer's other lanterns. The
  // lanterns select their fixture and are aligned to, but are never dragged — `points` stays
  // the list a drag reads, so moving a fixture never moves it to where one of its lanterns hangs.
  const drawnPoints = useMemo(
    () => (extraPoints.length === 0 ? points : [...points, ...extraPoints]),
    [points, extraPoints],
  )
  const { data: regions } = useStageRegionListQuery(projectId)
  const { data: riggings } = useRiggingListQuery(projectId)

  const fitTo = useMemo(() => projectionExtent(projection, dims), [projection, dims])
  const svg = useSvgMetres(fitTo)
  const [cursor, setCursor] = useState<ScreenPoint | null>(null)
  const bodyDrag = useBodyDrag2D(svg.ref, svg.toMetres)

  // Pan tracks CLIENT PIXELS plus the metres-per-pixel scale captured at
  // pointerdown — never re-read metre positions mid-pan. Panning shifts the
  // viewBox, so a metre reading taken after a pan is in a different frame from
  // the one before it; differencing across that shift made the view pan on
  // alternate moves only (d = −m, 0, −m, 0 …), i.e. at half the cursor speed and
  // visibly stuttering. Pixel deltas are frame-independent.
  const panRef = useRef<{
    pointerId: number
    startClientX: number
    startClientY: number
    lastClientX: number
    lastClientY: number
    mPerPx: number
    panned: boolean
  } | null>(null)

  // Live colour and intensity, rather than the gel-only `colourFor` this used to pass down:
  // each FixtureShape now resolves its own colour source through FixtureAppearanceSource, the
  // same dispatch the 3D scene and the overview marker use.
  const { fixtureByKey, typeByKey } = useFixtureLookup()

  const snapPoint = useCallback(
    (p: ScreenPoint): ScreenPoint => ({ h: snap.snapValue(p.h), v: snap.snapValue(p.v) }),
    [snap],
  )

  // — alignment guides ————————————————————————————————————————————————
  //
  // Candidates are built once per gesture (see buildGuideCandidates) and the
  // active hits live in state purely so the dashed lines can render.

  const [guideHit, setGuideHit] = useState<{ h: number | null; v: number | null }>({
    h: null,
    v: null,
  })

  // Marquee state: the ref drives the gesture, the state drives the rubber band.
  const marqueeRef = useRef<{
    pointerId: number
    start: ScreenPoint
    intent: SelectIntent
  } | null>(null)
  const [marquee, setMarquee] = useState<{ start: ScreenPoint; end: ScreenPoint } | null>(null)
  /** Bar a dragged fixture is currently over, highlighted as the drop target. */
  const [hoverRigUuid, setHoverRigUuid] = useState<string | null>(null)

  /** Everything a drag can align to, projected. Excludes the dragged object. */
  const guideSourcesFor = useCallback(
    (excludeId: string): GuideCandidates => {
      const sources: GuideSource[] = []
      for (const { patch, screen } of points) {
        sources.push({ id: `patch:${patch.key}`, points: [screen] })
      }
      // A fixture lines up with its own pair across the stage, which is the usual reason to hang one.
      for (const { placement, screen } of extraPoints) {
        sources.push({ id: `placement:${placement.uuid}`, points: [screen] })
      }
      for (const rig of riggings ?? []) {
        const pr = projectRigging(rig, projection)
        sources.push({ id: `rigging:${rig.uuid}`, points: [pr.a, pr.b] })
      }
      for (const region of regions ?? []) {
        const centre = project(
          { x: region.centerX ?? 0, y: region.centerY ?? 0, z: region.centerZ ?? 0 },
          projection,
        )
        sources.push({ id: `region:${region.uuid}`, points: [centre] })
      }
      return buildGuideCandidates(sources, excludeId)
    },
    [points, extraPoints, riggings, regions, projection],
  )

  /**
   * Guide-then-grid snapping for one gesture.
   *
   * Tolerance is a pixel threshold scaled to metres, so it feels the same at
   * every zoom — a fixed metre tolerance is sticky when zoomed out and
   * unreachable when zoomed in.
   */
  const makeGuidedSnap = useCallback(
    (excludeId: string) => {
      const candidates = guideSourcesFor(excludeId)
      const toleranceM = GUIDE_TOLERANCE_PX * svg.mPerPx
      return (p: ScreenPoint): ScreenPoint => {
        const r = snapWithGuides(p, candidates, toleranceM, snap.snapValue)
        setGuideHit({ h: r.hitH, v: r.hitV })
        return r.p
      }
    },
    [guideSourcesFor, svg.mPerPx, snap],
  )

  const clearGuides = useCallback(() => setGuideHit({ h: null, v: null }), [])

  // Handles grab on first press, so they start a plane drag directly rather than
  // going through the click-vs-drag discriminator that bodies use.
  const startPlaneDrag = usePlaneDrag(svg.ref, svg.toMetres)
  const startHandleDrag = useCallback(
    (opts: PlaneDragOptions, e: React.PointerEvent) => {
      if (e.button !== 0) return
      // Without this the press also reaches the SVG's background pan handler.
      e.stopPropagation()
      startPlaneDrag(opts, {
        clientX: e.clientX,
        clientY: e.clientY,
        pointerId: e.pointerId,
      })
    },
    [startPlaneDrag],
  )

  // — background pan ————————————————————————————————————————————————

  const onPointerDown = (e: React.PointerEvent<SVGSVGElement>) => {
    if (e.button !== 0 && e.button !== 1) return
    const el = svg.ref.current
    if (!el) return

    // A modified press on empty space starts a marquee rather than a pan — the
    // same modifiers that extend a click-selection.
    const intent = selectionIntentFor(e.nativeEvent)
    if (e.button === 0 && onMarqueeSelect && editMode && intent !== 'replace') {
      const start = svg.toMetres(e.clientX, e.clientY)
      marqueeRef.current = { pointerId: e.pointerId, start, intent }
      setMarquee({ start, end: start })
      try {
        el.setPointerCapture(e.pointerId)
      } catch {
        // Non-fatal: the marquee just stops at the canvas edge.
      }
      return
    }

    panRef.current = {
      pointerId: e.pointerId,
      startClientX: e.clientX,
      startClientY: e.clientY,
      lastClientX: e.clientX,
      lastClientY: e.clientY,
      mPerPx: svg.mPerPx,
      panned: false,
    }
    // Keeps pointermove flowing to this element while the cursor is outside it.
    // Wrapped because a synthetic or already-released pointer throws.
    try {
      el.setPointerCapture(e.pointerId)
    } catch {
      // Non-fatal: without capture the pan just stops at the canvas edge.
    }
  }

  const onPointerMove = (e: React.PointerEvent<SVGSVGElement>) => {
    const here = svg.toMetres(e.clientX, e.clientY)
    setCursor(here)

    const mq = marqueeRef.current
    if (mq && mq.pointerId === e.pointerId) {
      setMarquee({ start: mq.start, end: here })
      return
    }

    const pan = panRef.current
    if (!pan || pan.pointerId !== e.pointerId) return
    if (!pan.panned) {
      const travel = Math.hypot(e.clientX - pan.startClientX, e.clientY - pan.startClientY)
      if (travel < PAN_THRESHOLD_PX) return
      pan.panned = true
    }
    // Drag the content under the cursor: the viewBox moves opposite the pointer.
    svg.panBy(
      (pan.lastClientX - e.clientX) * pan.mPerPx,
      (pan.lastClientY - e.clientY) * pan.mPerPx,
    )
    pan.lastClientX = e.clientX
    pan.lastClientY = e.clientY
  }

  const endPan = (e: React.PointerEvent<SVGSVGElement>) => {
    const mq = marqueeRef.current
    if (mq && mq.pointerId === e.pointerId) {
      marqueeRef.current = null
      setMarquee(null)
      try {
        svg.ref.current?.releasePointerCapture(e.pointerId)
      } catch {
        // Already released.
      }
      const end = svg.toMetres(e.clientX, e.clientY)
      const hMin = Math.min(mq.start.h, end.h)
      const hMax = Math.max(mq.start.h, end.h)
      const vMin = Math.min(mq.start.v, end.v)
      const vMax = Math.max(mq.start.v, end.v)
      // Fixtures only: a marquee over a plot means "these lights", and including
      // whatever regions happen to overlap would make align/distribute operate on
      // a mixed set the user didn't intend.
      // A lantern inside the band selects its fixture; a fixture caught twice is one hit.
      const hitKeys = new Set(
        drawnPoints
          .filter(
            ({ screen }) =>
              screen.h >= hMin && screen.h <= hMax && screen.v >= vMin && screen.v <= vMax,
          )
          .map(({ patch }) => patch.key),
      )
      const hits = [...hitKeys].map((patchKey): SelectionRef => ({ kind: 'patch', patchKey }))
      if (hits.length > 0) onMarqueeSelect?.(hits, mq.intent === 'toggle' ? 'add' : mq.intent)
      return
    }

    const pan = panRef.current
    if (!pan || pan.pointerId !== e.pointerId) return
    panRef.current = null
    try {
      svg.ref.current?.releasePointerCapture(e.pointerId)
    } catch {
      // Already released — e.g. after pointercancel. Must not stop the click
      // below from clearing the selection.
    }
    if (pan.panned) return

    // A click that never became a pan. While placing, it positions the new
    // object; otherwise it clears the selection.
    if (placing && onPlacementClick) {
      const screen = svg.toMetres(e.clientX, e.clientY)
      // The out-of-plane axis comes from placementDefault — see PlacementPoint on
      // why each view resolves that itself rather than emitting a partial point.
      onPlacementClick(unproject(snapPoint(screen), projection, placementDefault))
      return
    }
    onSelectionChange(null)
  }

  const onWheel = (e: React.WheelEvent<SVGSVGElement>) => {
    e.preventDefault()
    const at = svg.toMetres(e.clientX, e.clientY)
    svg.zoomBy(e.deltaY < 0 ? ZOOM_PER_WHEEL_NOTCH : 1 / ZOOM_PER_WHEEL_NOTCH, at)
  }

  // — fixtures ————————————————————————————————————————————————————————

  const rigFor = useCallback(
    (uuid: string | null) => (uuid ? riggings?.find((r) => r.uuid === uuid) ?? null : null),
    [riggings],
  )

  const onFixturePointerDown = (
    patch: FixturePatch,
    e: React.PointerEvent,
    placement?: PatchPlacement,
  ) => {
    // Drag stays bound to the anchor, but any selected fixture is draggable —
    // otherwise extending a selection would make the earlier ones immovable.
    const selected =
      patch.key === selectedPatchKey ||
      (selectedKeys?.has(`patch:${patch.key}`) ?? false)
    const intent = selectionIntentFor(e.nativeEvent)
    bodyDrag(e, {
      onClick: () => onSelectionChange({ kind: 'patch', patchKey: patch.key }, intent),
      // Drag only once selected — same rule as the 3D bodies. A paired dimmer's other lantern
      // selects its fixture and never drags: it is moved in the patch form's "Also hung at".
      buildDrag:
        !editMode || !selected || !onPatchPlacementChange || placement
          ? undefined
          : () => {
              const rig = rigFor(patch.riggingUuid)
              const world = worldPositionLighting(patch, riggings ?? []) ?? { x: 0, y: 0, z: 0 }
              const anchor = project(world, projection)

              // A mounted fixture slides ALONG its bar — its position is a
              // one-parameter family, not a free point in the plane.
              //
              // This deliberately does not preserve the out-of-plane axis the way
              // a free-fixture drag does. On a yawed truss the bar changes depth
              // along its length, so holding world Y fixed while moving in the
              // front elevation would walk the fixture off the bar it is bolted
              // to. Being on the truss is the stronger truth, so the constraint
              // wins and the depth follows the bar.
              //
              // Local Y and Z are carried through untouched, which is what keeps
              // the fixture's drop below the bar.
              if (rig) {
                const pr = projectRigging(rig, projection)
                // Edge-on: the bar projects to a point, so there's no direction to
                // slide along. buildDrag is already suppressed for the rigging
                // itself in this case; for a fixture, refuse the drag too.
                if (pr.degenerate) return undefined
                const lengthM = rig.lengthM ?? DEFAULT_RIGGING_LENGTH_M
                const halfLen = lengthM / 2

                const emitAlongBar = (p: ScreenPoint, settled: boolean) => {
                  const raw = localXAlongBar(p, pr.a, pr.b, lengthM)
                  // Snap in bar-local metres, so fixtures land on even intervals
                  // *along the truss* rather than on the world grid — then re-clamp,
                  // since snapping outward from the last interval could overshoot
                  // the end of the bar.
                  const stageX = Math.max(-halfLen, Math.min(halfLen, snap.snapValue(raw)))
                  onPatchPlacementChange(
                    patch,
                    {
                      riggingUuid: patch.riggingUuid,
                      stageX,
                      stageY: patch.stageY,
                      stageZ: patch.stageZ,
                    },
                    settled,
                  )
                }
                return {
                  anchor,
                  onDrag: (p) => emitAlongBar(p, false),
                  onSettle: (last) => emitAlongBar(last ?? anchor, true),
                }
              }

              // Free fixture: a plain in-plane move, preserving the world
              // coordinate on the axis this view can't show — unless it's dropped
              // onto a truss, in which case it gets bolted on.
              const target: BulkTarget = { patch, rig: null, world }
              const emit = (p: ScreenPoint, settled: boolean) => {
                const nextWorld = unproject(p, projection, world)
                const overRig = riggingUnderPoint(
                  p,
                  riggings ?? [],
                  projection,
                  PARENT_SNAP_PX * svg.mPerPx,
                )
                setHoverRigUuid(overRig?.uuid ?? null)

                if (overRig) {
                  const { change } = dropOntoRigging(target, nextWorld, overRig)
                  onPatchPlacementChange(
                    patch,
                    {
                      riggingUuid: change.riggingUuid ?? null,
                      stageX: change.stageX ?? null,
                      stageY: change.stageY ?? null,
                      stageZ: change.stageZ ?? null,
                    },
                    settled,
                  )
                  return
                }
                onPatchPlacementChange(
                  patch,
                  { riggingUuid: null, ...placementFromWorldLighting(nextWorld, null) },
                  settled,
                )
              }
              return {
                anchor,
                snap: makeGuidedSnap(`patch:${patch.key}`),
                onDrag: (p) => emit(p, false),
                onSettle: (last) => {
                  clearGuides()
                  // Clear the drop-target highlight AFTER the final emit, not
                  // before: `emit` sets it from riggingUnderPoint, so clearing
                  // first just let the settle frame set it straight back and the
                  // bar stayed lit green until the next drag began.
                  emit(last ?? anchor, true)
                  setHoverRigUuid(null)
                },
              }
            },
    })
  }

  // — regions ————————————————————————————————————————————————————————

  const onRegionPointerDown = (region: StageRegionDto, e: React.PointerEvent) => {
    const selected = selection?.kind === 'region' && selection.uuid === region.uuid
    bodyDrag(e, {
      onClick: () =>
        onSelectionChange(
          { kind: 'region', uuid: region.uuid },
          selectionIntentFor(e.nativeEvent),
        ),
      buildDrag:
        !editMode || !selected || !onRegionPositionChange
          ? undefined
          : () => {
              const centre: LightingPoint = {
                x: region.centerX ?? 0,
                y: region.centerY ?? 0,
                z: region.centerZ ?? 0,
              }
              const anchor = project(centre, projection)
              const emit = (p: ScreenPoint, settled: boolean) => {
                const next = unproject(p, projection, centre)
                onRegionPositionChange(
                  region,
                  {
                    centerX: next.x,
                    centerY: next.y,
                    centerZ: next.z,
                    yawDeg: region.yawDeg,
                  },
                  settled,
                )
              }
              return {
                anchor,
                snap: makeGuidedSnap(`region:${region.uuid}`),
                onDrag: (p) => emit(p, false),
                onSettle: (last) => {
                  clearGuides()
                  emit(last ?? anchor, true)
                },
              }
            },
    })
  }

  // — riggings ————————————————————————————————————————————————————————

  const onRiggingPointerDown = (rig: RiggingDto, e: React.PointerEvent) => {
    const selected = selection?.kind === 'rigging' && selection.uuid === rig.uuid
    const pr = projectRigging(rig, projection)
    bodyDrag(e, {
      onClick: () =>
        onSelectionChange(
          { kind: 'rigging', uuid: rig.uuid },
          selectionIntentFor(e.nativeEvent),
        ),
      buildDrag:
        !editMode || !selected || !onRiggingPositionChange || pr.degenerate
          ? undefined
          : () => {
              const origin: LightingPoint = {
                x: rig.positionX ?? 0,
                y: rig.positionY ?? 0,
                z: rig.positionZ ?? 0,
              }
              const anchor = project(origin, projection)
              // Move the whole bar rigidly: shift both endpoints by the same world
              // delta and re-derive, so length and heading come out unchanged
              // rather than being recomputed from a moved single end.
              const [ea, eb] = worldEndpointsFor(rig)
              const emit = (p: ScreenPoint, settled: boolean) => {
                const nextOrigin = unproject(p, projection, origin)
                const d = {
                  x: nextOrigin.x - origin.x,
                  y: nextOrigin.y - origin.y,
                  z: nextOrigin.z - origin.z,
                }
                const derived = deriveFromEndpoints(
                  { x: ea.x + d.x, y: ea.y + d.y, z: ea.z + d.z },
                  { x: eb.x + d.x, y: eb.y + d.y, z: eb.z + d.z },
                )
                onRiggingPositionChange(
                  rig,
                  {
                    positionX: derived.positionX,
                    positionY: derived.positionY,
                    positionZ: derived.positionZ,
                    // deriveFromEndpoints cannot recover pitch (it's a twist about
                    // the bar's own axis), so keep the stored value rather than
                    // letting a pure translation zero it out.
                    yawDeg: derived.yawDeg,
                    pitchDeg: rig.pitchDeg,
                    rollDeg: derived.rollDeg,
                    lengthM: derived.lengthM,
                  },
                  settled,
                )
              }
              return {
                anchor,
                snap: makeGuidedSnap(`rigging:${rig.uuid}`),
                onDrag: (p) => emit(p, false),
                onSettle: (last) => {
                  clearGuides()
                  emit(last ?? anchor, true)
                },
              }
            },
    })
  }

  const envelope = fitTo
  const selectedRegion =
    selection?.kind === 'region' ? regions?.find((r) => r.uuid === selection.uuid) ?? null : null
  const selectedRigging =
    selection?.kind === 'rigging' ? riggings?.find((r) => r.uuid === selection.uuid) ?? null : null
  const degenerateNotice =
    selection?.kind === 'rigging' && editMode
      ? riggings?.some(
          (r) => r.uuid === selection.uuid && projectRigging(r, projection).degenerate,
        )
        ? 'This rigging is end-on in this view — switch to Plan to move it.'
        : null
      : null

  return (
    <div className="relative h-full w-full overflow-hidden">
      <svg
        ref={svg.ref}
        // touch-none is mandatory: without it the browser's own pan/pinch
        // consumes every gesture, and tablets are where editing is enabled.
        className="h-full w-full touch-none select-none"
        viewBox={svg.viewBox}
        preserveAspectRatio="xMidYMid meet"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endPan}
        onPointerCancel={endPan}
        onPointerLeave={() => setCursor(null)}
        onWheel={onWheel}
      >
        {/* Nothing is drawn until the element has been measured: every
            constant-pixel dimension is derived from mPerPx, which needs the box
            size. The layout-effect measure lands before paint, so this guard
            costs no visible frame — it just makes the dependency explicit.

            Grid and envelope cover the *visible* extent, not the viewBox: under
            `meet` the visible region is larger on the non-constraining axis, and
            drawing only the viewBox leaves the grid stopping mid-canvas. */}
        {svg.measured && (
          <StageGrid extent={svg.visible} stepM={snap.step} mPerPx={svg.mPerPx} />
        )}

        <g pointerEvents="none">
          <rect
            x={envelope.hMin}
            y={envelope.vMin}
            width={envelope.hMax - envelope.hMin}
            height={envelope.vMax - envelope.vMin}
            fill="none"
            className="stroke-foreground/25"
            strokeWidth={1.5}
            vectorEffect="non-scaling-stroke"
          />
        </g>

        {svg.measured && view.regions && regions && (
          <RegionShapes
            regions={regions}
            projection={projection}
            selectedUuid={selection?.kind === 'region' ? selection.uuid : null}
            interactive
            editMode={editMode}
            onPick={onRegionPointerDown}
          />
        )}

        {svg.measured && view.riggings && riggings && (
          <RiggingShapes
            riggings={riggings}
            projection={projection}
            selectedUuid={selection?.kind === 'rigging' ? selection.uuid : null}
            dropTargetUuid={hoverRigUuid}
            interactive
            editMode={editMode}
            showLabels={view.labels}
            mPerPx={svg.mPerPx}
            onPick={onRiggingPointerDown}
          />
        )}

        {svg.measured && view.fixtures && (
          <FixtureShapes
            // `drawnPoints` is memoised (and is `points` itself when nothing is
            // paired), not a mapped copy: a fresh array on every render would
            // defeat FixtureShapes' memo and re-run the O(n²) label declutter on
            // every drag frame. ProjectedPatch is a structural superset of
            // ProjectedFixture, so the extra fields are ignored.
            fixtures={drawnPoints}
            selectedKey={selectedPatchKey}
            selectedKeys={selectedKeys}
            showLabels={view.labels}
            mPerPx={svg.mPerPx}
            interactive
            editMode={editMode}
            fixtureByKey={fixtureByKey}
            typeByKey={typeByKey}
            onPick={onFixturePointerDown}
          />
        )}

        {/* Handles paint last so they hit-test above the bodies they belong to —
            the SVG equivalent of the 3D view's raycast-priority machinery. */}
        {svg.measured && editMode && selectedRegion && onRegionPositionChange && (
          <RegionEditHandles2D
            region={selectedRegion}
            projection={projection}
            mPerPx={svg.mPerPx}
            snap={snap}
            startDrag={startHandleDrag}
            onChange={(next, settled) => onRegionPositionChange(selectedRegion, next, settled)}
          />
        )}
        {svg.measured &&
          editMode &&
          selectedRigging &&
          onRiggingPositionChange &&
          !projectRigging(selectedRigging, projection).degenerate && (
            <RiggingEndpointHandles2D
              rig={selectedRigging}
              projection={projection}
              mPerPx={svg.mPerPx}
              snap={snap}
              startDrag={startHandleDrag}
              onChange={(next, settled) =>
                onRiggingPositionChange(selectedRigging, next, settled)
              }
            />
          )}

        <AlignmentGuides hitH={guideHit.h} hitV={guideHit.v} extent={svg.visible} />

        {marquee && (
          <rect
            x={Math.min(marquee.start.h, marquee.end.h)}
            y={Math.min(marquee.start.v, marquee.end.v)}
            width={Math.abs(marquee.end.h - marquee.start.h)}
            height={Math.abs(marquee.end.v - marquee.start.v)}
            className="fill-primary/10 stroke-primary"
            strokeWidth={1}
            strokeDasharray="4 3"
            vectorEffect="non-scaling-stroke"
            pointerEvents="none"
          />
        )}
      </svg>

      <Stage2DHud
        projection={projection}
        cursor={cursor}
        snapStepM={snap.active ? snap.step : null}
        onFit={svg.fit}
        onZoomIn={() => svg.zoomBy(ZOOM_PER_WHEEL_NOTCH)}
        onZoomOut={() => svg.zoomBy(1 / ZOOM_PER_WHEEL_NOTCH)}
        notice={degenerateNotice}
      />
    </div>
  )
}
