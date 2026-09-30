import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import { useProjectedPatches, type DrawnPoint } from '../../../hooks/useProjectedPatches'
import { placementFromWorldLighting, worldPositionLighting } from '../../../lib/stageCoords'
import { DEFAULT_RIGGING_LENGTH_M, deriveFromEndpoints, localXAlongBar, worldEndpointsFor } from '../../../lib/stageGeometry'
import {
  STAGE_PROJECTIONS,
  project,
  unproject,
  type LightingPoint,
  type ScreenPoint,
} from '../../../lib/stageProjection'
import { dropOntoRigging, riggingUnderPoint, type BulkTarget } from '../../../lib/stageBulkOps'
import { buildGuideCandidates, snapWithGuides, type GuideCandidates, type GuideSource } from '../../../lib/stageSnapping'
import type { OrthoCamera } from '../../../lib/stageViewpoint'
import type { FixturePatch } from '../../../api/patchApi'
import type { StageRegionDto } from '../../../api/stageRegionApi'
import type { RiggingDto } from '../../../api/riggingApi'
import type { StageElementDto } from '../../../api/stageElementApi'
import type {
  ElementPositionUpdate,
  PatchPlacementUpdate,
  PlacementPoint,
  RegionPositionUpdate,
  RiggingPositionUpdate,
  Selection,
} from '../../stage/stageEditing'
import type { StageViewFlags } from '../useStageView'
import { selectionIntentFor, selectionKey, type SelectIntent, type SelectionRef } from '../useStageSelection'
import { elementFlies, elementStates } from '../scene/sceneParts'
import {
  elementAnchor,
  elementOutline,
  hitAt,
  marqueeHits,
  projectRigging,
  regionOutline,
  type SectionHit,
  type SectionScene,
} from './sectionHits'
import { metresPerPixel, offsetToSection, visibleSection, type SectionControls, type SectionViewStore } from './sectionView'
import { useSectionDrag, useSectionPress, type SectionDragOptions } from './useSectionDrag'
import { RegionSectionHandles, RiggingSectionHandles } from './SectionEditHandles'
import { SectionGrid } from './SectionGrid'
import { SectionHud } from './SectionHud'
import { AlignmentGuides } from './AlignmentGuides'
import type { SnapGrid } from './useSnapGrid'

/** Pointer travel that promotes a background press into a pan. */
const PAN_THRESHOLD_PX = 4
/** How close, in pixels, a drag must come to another object's row/column to snap. */
const GUIDE_TOLERANCE_PX = 6
/** How close a dragged fixture must come to a bar before it bolts onto it. */
const PARENT_SNAP_PX = 12
/** One wheel notch, or one press of the HUD's zoom buttons. */
const ZOOM_PER_WHEEL_NOTCH = 1.15

export interface SectionEditLayerProps {
  projectId: number
  camera: OrthoCamera
  viewStore: SectionViewStore
  /** The section camera's pan and zoom, while it is mounted. */
  controls: () => SectionControls | null
  selection: Selection
  selectedKeys?: ReadonlySet<string>
  view: StageViewFlags
  snap: SnapGrid
  riggings: readonly RiggingDto[]
  regions: readonly StageRegionDto[]
  /** The scene elements the canvas is drawing, in the order it draws them. */
  elements: readonly StageElementDto[]
  /** Something is armed to be placed: a background click places it rather than clearing. */
  placing: boolean
  /** Supplies whichever axis this section can't learn from a click — Z in plan, Y in front, X in side. */
  placementDefault: LightingPoint
  onSelectionChange: (s: Selection, intent?: SelectIntent) => void
  onMarqueeSelect?: (refs: SelectionRef[], intent: SelectIntent) => void
  onPlacementClick?: (p: PlacementPoint) => void
  onPatchPlacementChange?: (patch: FixturePatch, next: PatchPlacementUpdate, settled: boolean) => void
  onRegionPositionChange?: (region: StageRegionDto, next: RegionPositionUpdate, settled: boolean) => void
  onRiggingPositionChange?: (rig: RiggingDto, next: RiggingPositionUpdate, settled: boolean) => void
  onElementPositionChange?: (element: StageElementDto, next: ElementPositionUpdate, settled: boolean) => void
}

/**
 * Editing on an orthographic section — Plan, Front, Side — over the 3D scene (stage-view plan
 * session 5, D1). The scene is drawn by the one renderer; this layer is DOM over the canvas, as the
 * label layer is, and owns the pointer while the view is editing on a section. It hit-tests in the
 * section's screen metres ([hitAt]), draws the edit chrome — the snap grid, the handles, the
 * alignment guides, the drop-target bar, the marquee — in an SVG whose viewBox follows the section
 * camera ([SectionViewStore]), and pans and zooms that camera through [SectionControls].
 *
 * Every gesture is the SVG plot's, and every drag follows its pipeline:
 *
 *   world  = worldPositionLighting(patch, riggings)
 *   screen = project(world, projection)          → snap / constrain
 *   world' = unproject(screen', projection, world)   ← preserves the world out-of-plane coordinate
 *   next   = placementFromWorldLighting(world', rig) → rig-local stage*
 *
 * The layer is never mounted by a render for `render_view`: it is Stage3D's DOM, outside the
 * canvas, and only on screen while editing.
 */
export function SectionEditLayer({
  projectId,
  camera,
  viewStore,
  controls,
  selection,
  selectedKeys,
  view,
  snap,
  riggings,
  regions,
  elements,
  placing,
  placementDefault,
  onSelectionChange,
  onMarqueeSelect,
  onPlacementClick,
  onPatchPlacementChange,
  onRegionPositionChange,
  onRiggingPositionChange,
  onElementPositionChange,
}: SectionEditLayerProps) {
  const projection = STAGE_PROJECTIONS[camera]
  const sectionView = useSyncExternalStore(viewStore.subscribe, viewStore.get)
  const svgRef = useRef<SVGSVGElement | null>(null)
  const selectedPatchKey = selection?.kind === 'patch' ? selection.patchKey : null

  const { points, extraPoints } = useProjectedPatches(projectId, { projection, includeKey: selectedPatchKey })
  // Everything drawn: each fixture's own placement, then a paired dimmer's other lanterns. The
  // lanterns select their fixture and are aligned to, but are never dragged — `points` stays the
  // list a drag reads, so moving a fixture never moves it to where one of its lanterns hangs.
  const drawnPoints = useMemo<DrawnPoint[]>(
    () => (extraPoints.length === 0 ? points : [...points, ...extraPoints]),
    [points, extraPoints],
  )
  // What a press can land on: only what the view is drawing.
  const scene = useMemo<SectionScene>(
    () => ({
      projection,
      points: view.fixtures ? drawnPoints : [],
      riggings: view.riggings ? riggings : [],
      regions: view.regions ? regions : [],
      elements,
    }),
    [projection, view.fixtures, view.riggings, view.regions, drawnPoints, riggings, regions, elements],
  )

  const ready = sectionView != null
  const mPerPx = sectionView ? metresPerPixel(sectionView) : 1
  // Read through a ref by every handler: a press resolves positions against the section as it is
  // at that moment, not as it was when the handler was built.
  const viewRef = useRef(sectionView)
  viewRef.current = sectionView
  const toSection = useCallback((clientX: number, clientY: number): ScreenPoint => {
    const el = svgRef.current
    const v = viewRef.current
    if (!el || !v) return { h: 0, v: 0 }
    const rect = el.getBoundingClientRect()
    return offsetToSection(v, clientX - (rect.left + rect.width / 2), clientY - (rect.top + rect.height / 2))
  }, [])

  const [cursor, setCursor] = useState<ScreenPoint | null>(null)
  const [hoverCursor, setHoverCursor] = useState<'default' | 'pointer' | 'grab'>('default')
  const startDrag = useSectionDrag(toSection)
  const press = useSectionPress(toSection)

  const snapPoint = useCallback(
    (p: ScreenPoint): ScreenPoint => ({ h: snap.snapValue(p.h), v: snap.snapValue(p.v) }),
    [snap],
  )

  // — alignment guides ————————————————————————————————————————————————
  //
  // Candidates are built once per gesture, and the active hits live in state only so the dashed
  // lines can render.

  const [guideHit, setGuideHit] = useState<{ h: number | null; v: number | null }>({ h: null, v: null })

  // Pan and marquee: the ref drives the gesture, the state draws the rubber band.
  const panRef = useRef<{ pointerId: number; startX: number; startY: number; lastX: number; lastY: number; panned: boolean } | null>(null)
  const marqueeRef = useRef<{ pointerId: number; start: ScreenPoint; intent: SelectIntent } | null>(null)
  const [marquee, setMarquee] = useState<{ start: ScreenPoint; end: ScreenPoint } | null>(null)
  /** Bar a dragged fixture is currently over, highlighted as the drop target. */
  const [hoverRigUuid, setHoverRigUuid] = useState<string | null>(null)

  /** Everything a drag can align to, projected. Excludes the dragged object. */
  const guideSourcesFor = useCallback(
    (excludeId: string): GuideCandidates => {
      const sources: GuideSource[] = []
      for (const { patch, screen } of points) sources.push({ id: `patch:${patch.key}`, points: [screen] })
      // A fixture lines up with its own pair across the stage, which is the usual reason to hang one.
      for (const { placement, screen } of extraPoints) sources.push({ id: `placement:${placement.uuid}`, points: [screen] })
      for (const rig of riggings) {
        const pr = projectRigging(rig, projection)
        sources.push({ id: `rigging:${rig.uuid}`, points: [pr.a, pr.b] })
      }
      for (const region of regions) {
        const centre = project({ x: region.centerX ?? 0, y: region.centerY ?? 0, z: region.centerZ ?? 0 }, projection)
        sources.push({ id: `region:${region.uuid}`, points: [centre] })
      }
      for (const element of elements) {
        if (element.kind === 'ROOM') continue
        sources.push({ id: `element:${element.uuid}`, points: [elementAnchor(element, projection)] })
      }
      return buildGuideCandidates(sources, excludeId)
    },
    [points, extraPoints, riggings, regions, elements, projection],
  )

  /**
   * Guide-then-grid snapping for one gesture. The tolerance is a pixel threshold scaled to metres,
   * so it feels the same at every zoom.
   */
  const makeGuidedSnap = useCallback(
    (excludeId: string) => {
      const candidates = guideSourcesFor(excludeId)
      return (p: ScreenPoint): ScreenPoint => {
        const v = viewRef.current
        const toleranceM = GUIDE_TOLERANCE_PX * (v ? metresPerPixel(v) : 1)
        const r = snapWithGuides(p, candidates, toleranceM, snap.snapValue)
        setGuideHit({ h: r.hitH, v: r.hitV })
        return r.p
      }
    },
    [guideSourcesFor, snap],
  )
  const clearGuides = useCallback(() => setGuideHit({ h: null, v: null }), [])

  // Handles grab on first press, so they start a drag directly rather than going through the
  // click-vs-drag discriminator bodies use.
  const startHandleDrag = useCallback(
    (opts: SectionDragOptions, e: React.PointerEvent) => {
      if (e.button !== 0) return
      // Without this the press also reaches the layer's own press handler.
      e.stopPropagation()
      startDrag(opts, { clientX: e.clientX, clientY: e.clientY, pointerId: e.pointerId })
    },
    [startDrag],
  )

  const rigFor = useCallback(
    (uuid: string | null) => (uuid ? riggings.find((r) => r.uuid === uuid) ?? null : null),
    [riggings],
  )
  const isSelected = useCallback(
    (ref: SelectionRef) =>
      selectedKeys?.has(selectionKey(ref)) ??
      (selection != null && selectionKey(selection) === selectionKey(ref)),
    [selectedKeys, selection],
  )

  // — what a press on each kind builds ————————————————————————————————————————

  const fixtureDrag = (patch: FixturePatch): SectionDragOptions | undefined => {
    if (!onPatchPlacementChange) return undefined
    const rig = rigFor(patch.riggingUuid)
    const world = worldPositionLighting(patch, riggings as RiggingDto[]) ?? { x: 0, y: 0, z: 0 }
    const anchor = project(world, projection)

    // A mounted fixture slides ALONG its bar — its position is a one-parameter family, not a free
    // point in the plane. On a yawed truss the bar changes depth along its length, so the depth
    // follows the bar rather than holding world Y; local Y and Z pass through untouched.
    if (rig) {
      const pr = projectRigging(rig, projection)
      // Edge-on: the bar projects to a point, so there's no direction to slide along.
      if (pr.degenerate) return undefined
      const lengthM = rig.lengthM ?? DEFAULT_RIGGING_LENGTH_M
      const halfLen = lengthM / 2
      const emitAlongBar = (p: ScreenPoint, settled: boolean) => {
        const raw = localXAlongBar(p, pr.a, pr.b, lengthM)
        // Snap in bar-local metres, so fixtures land on even intervals *along the truss*, then
        // re-clamp, since snapping outward from the last interval could overshoot the end.
        const stageX = Math.max(-halfLen, Math.min(halfLen, snap.snapValue(raw)))
        onPatchPlacementChange(
          patch,
          { riggingUuid: patch.riggingUuid, stageX, stageY: patch.stageY, stageZ: patch.stageZ },
          settled,
        )
      }
      return {
        anchor,
        onDrag: (p) => emitAlongBar(p, false),
        onSettle: (last) => emitAlongBar(last ?? anchor, true),
      }
    }

    // Free fixture: a plain in-plane move, preserving the world coordinate on the axis this
    // section can't show — unless it's dropped onto a bar, in which case it gets bolted on.
    const target: BulkTarget = { patch, rig: null, world }
    const emit = (p: ScreenPoint, settled: boolean) => {
      const nextWorld = unproject(p, projection, world)
      const v = viewRef.current
      const overRig = riggingUnderPoint(p, riggings as RiggingDto[], projection, PARENT_SNAP_PX * (v ? metresPerPixel(v) : 1))
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
      onPatchPlacementChange(patch, { riggingUuid: null, ...placementFromWorldLighting(nextWorld, null) }, settled)
    }
    return {
      anchor,
      snap: makeGuidedSnap(`patch:${patch.key}`),
      onDrag: (p) => emit(p, false),
      onSettle: (last) => {
        clearGuides()
        // Clear the drop-target highlight AFTER the final emit: `emit` sets it, so clearing first
        // let the settle frame set it straight back and the bar stayed lit until the next drag.
        emit(last ?? anchor, true)
        setHoverRigUuid(null)
      },
    }
  }

  const regionDrag = (region: StageRegionDto): SectionDragOptions | undefined => {
    if (!onRegionPositionChange) return undefined
    const centre: LightingPoint = { x: region.centerX ?? 0, y: region.centerY ?? 0, z: region.centerZ ?? 0 }
    const anchor = project(centre, projection)
    const emit = (p: ScreenPoint, settled: boolean) => {
      const next = unproject(p, projection, centre)
      onRegionPositionChange(region, { centerX: next.x, centerY: next.y, centerZ: next.z, yawDeg: region.yawDeg }, settled)
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
  }

  const riggingDrag = (rig: RiggingDto): SectionDragOptions | undefined => {
    if (!onRiggingPositionChange || projectRigging(rig, projection).degenerate) return undefined
    const origin: LightingPoint = { x: rig.positionX ?? 0, y: rig.positionY ?? 0, z: rig.positionZ ?? 0 }
    const anchor = project(origin, projection)
    // Move the whole bar rigidly: shift both endpoints by the same world delta and re-derive, so
    // length and heading come out unchanged rather than being recomputed from a moved single end.
    const [ea, eb] = worldEndpointsFor(rig)
    const emit = (p: ScreenPoint, settled: boolean) => {
      const nextOrigin = unproject(p, projection, origin)
      const d = { x: nextOrigin.x - origin.x, y: nextOrigin.y - origin.y, z: nextOrigin.z - origin.z }
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
          yawDeg: derived.yawDeg,
          // deriveFromEndpoints cannot recover pitch (a twist about the bar's own axis), so keep
          // the stored value rather than letting a pure translation zero it out.
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
  }

  const elementDrag = (element: StageElementDto): SectionDragOptions | undefined => {
    if (!onElementPositionChange) return undefined
    const origin: LightingPoint = { x: element.positionX, y: element.positionY, z: element.positionZ }
    const anchor = project(origin, projection)
    // A flown piece stands at its trim while one is set, not at its Z, so moving its Z in an
    // elevation would move nothing on screen: there it slides across only.
    const trimmed = elementFlies(element) && elementStates(element).trimM != null
    const lockAxis = trimmed && projection.v.axis === 'z' ? ('h' as const) : undefined
    const emit = (p: ScreenPoint, settled: boolean) => {
      const next = unproject(p, projection, origin)
      onElementPositionChange(element, { positionX: next.x, positionY: next.y, positionZ: next.z }, settled)
    }
    return {
      anchor,
      lockAxis,
      snap: makeGuidedSnap(`element:${element.uuid}`),
      onDrag: (p) => emit(p, false),
      onSettle: (last) => {
        clearGuides()
        emit(last ?? anchor, true)
      },
    }
  }

  /** The selection a hit stands for: a lantern selects its fixture. */
  const refFor = (hit: SectionHit): SelectionRef => {
    switch (hit.kind) {
      case 'patch':
        return { kind: 'patch', patchKey: hit.point.patch.key }
      case 'rigging':
        return { kind: 'rigging', uuid: hit.rig.uuid }
      case 'region':
        return { kind: 'region', uuid: hit.region.uuid }
      case 'element':
        return { kind: 'element', uuid: hit.element.uuid }
    }
  }

  /** The drag a press on [hit] would start — only once the object is selected, as in 3D. */
  const dragFor = (hit: SectionHit): (() => SectionDragOptions | undefined) | undefined => {
    if (!isSelected(refFor(hit))) return undefined
    switch (hit.kind) {
      case 'patch':
        // A paired dimmer's other lantern selects its fixture and never drags: it is moved in the
        // patch form's "Also hung at".
        return hit.point.placement ? undefined : () => fixtureDrag(hit.point.patch)
      case 'rigging':
        return () => riggingDrag(hit.rig)
      case 'region':
        return () => regionDrag(hit.region)
      case 'element':
        return () => elementDrag(hit.element)
    }
  }

  // — the layer's own press: a body, a marquee, a pan, or a click on nothing —————————————————

  const onPointerDown = (e: React.PointerEvent<SVGSVGElement>) => {
    if (e.button !== 0 && e.button !== 1) return
    const el = svgRef.current
    if (!el || !viewRef.current) return
    const at = toSection(e.clientX, e.clientY)
    const intent = selectionIntentFor(e.nativeEvent)
    const origin = { clientX: e.clientX, clientY: e.clientY, pointerId: e.pointerId }

    // Placing takes every press: a click anywhere lands the armed object, as the plot's did.
    const hit = e.button === 0 && !placing ? hitAt(scene, at, mPerPx) : null
    if (hit) {
      const ref = refFor(hit)
      press(origin, { onClick: () => onSelectionChange(ref, intent), buildDrag: dragFor(hit) })
      return
    }

    // A modified press on empty space starts a marquee rather than a pan — the same modifiers
    // that extend a click-selection.
    if (e.button === 0 && onMarqueeSelect && !placing && intent !== 'replace') {
      marqueeRef.current = { pointerId: e.pointerId, start: at, intent }
      setMarquee({ start: at, end: at })
      capture(el, e.pointerId)
      return
    }

    panRef.current = { pointerId: e.pointerId, startX: e.clientX, startY: e.clientY, lastX: e.clientX, lastY: e.clientY, panned: false }
    capture(el, e.pointerId)
  }

  const onPointerMove = (e: React.PointerEvent<SVGSVGElement>) => {
    const here = toSection(e.clientX, e.clientY)
    setCursor(here)

    const mq = marqueeRef.current
    if (mq && mq.pointerId === e.pointerId) {
      setMarquee({ start: mq.start, end: here })
      return
    }

    const pan = panRef.current
    if (pan && pan.pointerId === e.pointerId) {
      if (!pan.panned) {
        if (Math.hypot(e.clientX - pan.startX, e.clientY - pan.startY) < PAN_THRESHOLD_PX) return
        pan.panned = true
      }
      // Drag the content under the pointer: the section moves opposite it.
      controls()?.panBy(e.clientX - pan.lastX, e.clientY - pan.lastY)
      pan.lastX = e.clientX
      pan.lastY = e.clientY
      return
    }

    // Hover: say what a press here would do, as the plot's cursors did.
    if (e.buttons !== 0) return
    const hit = placing ? null : hitAt(scene, here, mPerPx)
    const next = hit == null ? 'default' : dragFor(hit) ? 'grab' : 'pointer'
    if (next !== hoverCursor) setHoverCursor(next)
  }

  const endGesture = (e: React.PointerEvent<SVGSVGElement>) => {
    const mq = marqueeRef.current
    if (mq && mq.pointerId === e.pointerId) {
      marqueeRef.current = null
      setMarquee(null)
      release(svgRef.current, e.pointerId)
      const hits = marqueeHits(scene.points, mq.start, toSection(e.clientX, e.clientY))
      if (hits.length > 0) onMarqueeSelect?.(hits, mq.intent === 'toggle' ? 'add' : mq.intent)
      return
    }

    const pan = panRef.current
    if (!pan || pan.pointerId !== e.pointerId) return
    panRef.current = null
    release(svgRef.current, e.pointerId)
    if (pan.panned || e.type === 'pointercancel') return

    // A click that never became a pan. While placing, it positions the new object; otherwise it
    // clears the selection.
    if (placing && onPlacementClick) {
      const screen = toSection(e.clientX, e.clientY)
      // The out-of-plane axis comes from placementDefault — see PlacementPoint on why each view
      // resolves that itself rather than emitting a partial point.
      onPlacementClick(unproject(snapPoint(screen), projection, placementDefault))
      return
    }
    onSelectionChange(null)
  }

  // The wheel zooms about the pointer. A native listener, not React's `onWheel`: React's is passive,
  // so it cannot stop the browser's own zoom on a trackpad pinch (a ctrl-wheel).
  useEffect(() => {
    const el = svgRef.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      const rect = el.getBoundingClientRect()
      controls()?.zoomAt(
        e.deltaY < 0 ? ZOOM_PER_WHEEL_NOTCH : 1 / ZOOM_PER_WHEEL_NOTCH,
        e.clientX - rect.left,
        e.clientY - rect.top,
      )
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
    // `ready`, because the SVG is only mounted once the section has reported where it is.
  }, [controls, ready])

  // Anything this layer drew for a gesture goes with the section it was drawn on.
  useEffect(() => {
    clearGuides()
    setHoverRigUuid(null)
    setMarquee(null)
    marqueeRef.current = null
    panRef.current = null
  }, [camera, clearGuides])

  if (sectionView == null) return null

  const visible = visibleSection(sectionView)
  const selectedRegion = selection?.kind === 'region' ? regions.find((r) => r.uuid === selection.uuid) ?? null : null
  const selectedRigging = selection?.kind === 'rigging' ? riggings.find((r) => r.uuid === selection.uuid) ?? null : null
  const selectedElements = elements.filter((e) => isSelected({ kind: 'element', uuid: e.uuid }))
  const dropRig = hoverRigUuid ? riggings.find((r) => r.uuid === hoverRigUuid) ?? null : null
  const degenerateNotice =
    selectedRigging && projectRigging(selectedRigging, projection).degenerate
      ? 'This rigging is end-on in this view — switch to Plan to move it.'
      : null
  const zoomBy = (factor: number) => {
    const rect = svgRef.current?.getBoundingClientRect()
    controls()?.zoomAt(factor, (rect?.width ?? sectionView.width) / 2, (rect?.height ?? sectionView.height) / 2)
  }

  return (
    <div className="absolute inset-0" data-section-edit={camera}>
      <svg
        ref={svgRef}
        // touch-none is mandatory: without it the browser's own pan and pinch consume every
        // gesture, and tablets are where editing is enabled.
        className="absolute inset-0 h-full w-full touch-none select-none"
        viewBox={`${visible.hMin} ${visible.vMin} ${visible.hMax - visible.hMin} ${visible.vMax - visible.vMin}`}
        // `meet`, centred: the camera keeps its centre and zoom as the canvas grows, and so does a
        // viewBox fitted this way, so the chrome stays on the scene in the frame or two before the
        // camera reports the canvas's new size.
        preserveAspectRatio="xMidYMid meet"
        style={{ cursor: placing ? 'crosshair' : hoverCursor }}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endGesture}
        onPointerCancel={endGesture}
        onPointerLeave={() => setCursor(null)}
      >
        <SectionGrid extent={visible} stepM={snap.step} mPerPx={mPerPx} />

        {/* A scene element has no highlight in the scene, so the layer draws the selected ones. */}
        <g pointerEvents="none">
          {selectedElements.map((element) => {
            const outline = elementOutline(element, projection)
            if (!outline) return null
            return (
              <polygon
                key={element.uuid}
                points={outline.map((p) => `${p.h},${p.v}`).join(' ')}
                fill="none"
                stroke="#ffe082"
                strokeWidth={1.5}
                strokeDasharray="5 3"
                vectorEffect="non-scaling-stroke"
              />
            )
          })}
          {selectedRegion && (
            <polygon
              points={regionOutline(selectedRegion, projection).map((p) => `${p.h},${p.v}`).join(' ')}
              fill="none"
              stroke="#ffe082"
              strokeWidth={1}
              strokeDasharray="5 3"
              vectorEffect="non-scaling-stroke"
            />
          )}
          {dropRig && (() => {
            // Green rather than the selection yellow: "release here to hang on this bar".
            const pr = projectRigging(dropRig, projection)
            return (
              <line
                x1={pr.a.h}
                y1={pr.a.v}
                x2={pr.b.h}
                y2={pr.b.v}
                stroke="#7fe0a0"
                strokeWidth={5}
                strokeLinecap="round"
                vectorEffect="non-scaling-stroke"
                data-drop-target={dropRig.uuid}
              />
            )
          })()}
        </g>

        {/* Handles paint last so they hit-test above everything else in the layer. */}
        {selectedRegion && onRegionPositionChange && (
          <RegionSectionHandles
            region={selectedRegion}
            projection={projection}
            mPerPx={mPerPx}
            snap={snap}
            startDrag={startHandleDrag}
            onChange={(next, settled) => onRegionPositionChange(selectedRegion, next, settled)}
          />
        )}
        {selectedRigging && onRiggingPositionChange && !projectRigging(selectedRigging, projection).degenerate && (
          <RiggingSectionHandles
            rig={selectedRigging}
            projection={projection}
            mPerPx={mPerPx}
            snap={snap}
            startDrag={startHandleDrag}
            onChange={(next, settled) => onRiggingPositionChange(selectedRigging, next, settled)}
          />
        )}

        <AlignmentGuides hitH={guideHit.h} hitV={guideHit.v} extent={visible} />

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
            data-marquee
          />
        )}
      </svg>

      <SectionHud
        projection={projection}
        cursor={cursor}
        snapStepM={snap.active ? snap.step : null}
        onFit={() => controls()?.fit()}
        onZoomIn={() => zoomBy(ZOOM_PER_WHEEL_NOTCH)}
        onZoomOut={() => zoomBy(1 / ZOOM_PER_WHEEL_NOTCH)}
        notice={degenerateNotice}
      />
    </div>
  )
}

/** Keeps pointermove flowing to the layer while the pointer is outside it. */
function capture(el: Element, pointerId: number) {
  try {
    el.setPointerCapture(pointerId)
  } catch {
    // Non-fatal — a synthetic or already-released pointer throws; the gesture stops at the edge.
  }
}

function release(el: Element | null, pointerId: number) {
  try {
    el?.releasePointerCapture(pointerId)
  } catch {
    // Already released — e.g. after pointercancel. Must not stop the click from clearing.
  }
}
