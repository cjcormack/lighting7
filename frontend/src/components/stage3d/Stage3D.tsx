import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Canvas, useFrame, useThree } from '@react-three/fiber'
import { RotateCcw } from 'lucide-react'
import { Edges, Text, TransformControls } from '@react-three/drei'
import { Euler, MathUtils, NoToneMapping, Object3D, Plane, Raycaster, Vector2, Vector3 } from 'three'
import { useProjectQuery } from '../../store/projects'
import { Bloom } from './Bloom'
import { CaptureCanvas, type StageCapture } from './CaptureCanvas'
import { StageRegionMeshes } from './StageRegionMeshes'
import { RiggingMeshes } from './RiggingMeshes'
import { FixtureModel } from './FixtureModel'
import { RegionEditHandles } from './RegionEditHandles'
import { RiggingEndpointHandles } from './RiggingEndpointHandles'
import { SNAP_ANGLE_DEG } from './useShiftHeld'
import type { SnapGrid } from './edit/useSnapGrid'
import { SectionEditLayer } from './edit/SectionEditLayer'
import { createSectionViewStore } from './edit/sectionView'
import { selectionKey, type SelectIntent, type SelectionRef } from './useStageSelection'
import { notifyTransformDragStart } from './useBodyDrag'
import { DEFAULT_VIEW_FLAGS, type StageViewFlags } from './useStageView'
import { useStageData } from './useStageData'
import { MAX_BEAM_REGIONS, StageEmitters, computeRegionGeometry } from './StageEmitters'
import { SurfaceLightingProvider, useSurfaceMaterial } from './scene/SurfaceLighting'
import { StageSceneElements, type SeatPicking } from './scene/StageSceneElements'
import { DEFAULT_SCENE_LAYERS, type SceneLayers } from './scene/sceneView'
import { DEFAULT_LIGHT_BUDGET } from './scene/lightTable'
import { HAZE_TIERS, HazeGovernor, type HazeQuality } from './scene/hazeGovernor'
import {
  beamClipFor,
  drawsRoom,
  sceneBuilds,
  sceneColliders,
  sceneElementBounds,
  type SceneBuildCache,
} from './scene/stageSurfaces'
import { buildEmitterLayout } from './emitterLayout'
import { bodySpecOf, emitterNeedsForSpec } from './emitterNeeds'
import { StageBodies, buildBodyLayout } from './bodies/StageBodies'
import { mountFor } from './bodies/mount'
import { StageLabelContext, StageLabelDriver } from './StageLabel'
import { StageLabelStore } from './stageLabels'
import { StageInvalidateProvider } from './stageInvalidate'
import {
  StageCameraRig,
  type StageCameraControls,
  type StageCameraHandle,
  type StageCameraLanding,
} from './StageCameraRig'
import { defaultOrbitPose, sceneBoundsLighting } from './stageCameras'
import { isOrthoCamera, type StageCamera } from '../../lib/stageViewpoint'
import { useStageElementListQuery } from '../../store/stageElements'
import type { LightingPoint } from '../../lib/stageProjection'
// drei's `Text` fetches its default font from jsdelivr at runtime, which an offline desk cannot
// reach. This is the Liberation Sans that react-pdf's pinned pdf.js ships; importing it as an
// asset URL bundles it with the app (the precedent is ScriptViewer's pdf.js worker).
import stageTextFont from 'pdfjs-dist/standard_fonts/LiberationSans-Regular.ttf?url'
import { Button } from '../ui/button'
import type { RiggingDto } from '../../api/riggingApi'
import type { FixturePatch } from '../../api/patchApi'
import { lanternsFor } from './lanterns'
import type { StageRegionDto } from '../../api/stageRegionApi'
import type { StageElementDto } from '../../api/stageElementApi'
import {
  fromThree,
  normaliseSignedDeg,
  patchPlacementFromWorld,
  rigEuler,
  worldPositionLighting,
} from '../../lib/stageCoords'
import type {
  ElementPositionUpdate,
  PatchPlacementUpdate,
  PlacementPoint,
  RegionPositionUpdate,
  RiggingPositionUpdate,
  Selection,
} from '../stage/stageEditing'
import { formatTriple } from '../../lib/utils'
import { NO_RAYCAST } from './raycast'

const EMPTY_RIGGINGS: RiggingDto[] = []
/** Behind the scene: the on-screen canvas's CSS background, and a render's clear colour. */
const STAGE_BACKGROUND = '#0b0e14'
/** The snap state of a view that edits nothing. */
const NO_SNAP: SnapGrid = {
  step: 0.25,
  setStep: () => {},
  snapOn: false,
  setSnapOn: () => {},
  active: false,
  activeRef: { current: false },
  snapValue: (v) => v,
}
const EMPTY_PATCHES: FixturePatch[] = []
const EMPTY_REGIONS: StageRegionDto[] = []
const EMPTY_ELEMENTS: StageElementDto[] = []
const PLACEMENT_ORIGIN: PlacementPoint = { x: 0, y: 0, z: 0 }

// TC translate handles named with >1 letter combine multiple axes (XY/XZ/YZ
// plane drags, XYZ centre free-drag). We strip them at mount so the only
// reachable interaction is a single-axis drag.
const PLANE_HANDLE_NAMES = new Set(['XY', 'XZ', 'YZ', 'XYZ'])

// The view-agnostic editing types live in ../stage/stageEditing so a module that
// only names a selection can do so without importing this one (and with it,
// three.js). Re-exported here so existing import sites are unaffected.
export type {
  Selection,
  ElementPositionUpdate,
  PatchPlacementUpdate,
  RegionPositionUpdate,
  RiggingPositionUpdate,
  PlacementPoint,
} from '../stage/stageEditing'

export type GizmoMode = 'translate' | 'rotate'

/**
 * The 3D view's recovery handle, for the View menu's *Test recovery*: drops the WebGL context the
 * way Safari does under memory pressure, so the paused state and *Restore* can be exercised on
 * purpose. A no-op where the browser offers no `WEBGL_lose_context`.
 */
export interface StageRecovery {
  testContextLoss(): void
}

/**
 * The route's handle on the camera: *Frame the selection* (F). Points are lighting coordinates;
 * whichever camera is current moves in its own way — the orbit pulls in, the eye turns its head, a
 * section slides within its plane.
 */
export interface StageFraming {
  frame(points: readonly LightingPoint[]): void
}

interface Stage3DProps {
  projectId: number
  editMode: boolean
  selection: Selection
  /** Every selected object's key (`useStageSelection`), so a multi-selection lights as one. */
  selectedKeys?: ReadonlySet<string>
  /**
   * What a click on the stage will place, as the hint names it (`region`, `flat`, `3 fixtures`),
   * or null. While set, a click places rather than selects.
   */
  placing?: string | null
  view?: StageViewFlags
  /** Lighting-Z (up) height of the plane the placement click should land on.
   *  Matches the new object's anchor height so the click projects WYSIWYG. */
  placementZ?: number
  /**
   * On a section, the axis a click cannot learn — Z in plan, Y in front, X in side — for the point a
   * placement click lands on (`PlacementPoint`).
   */
  placementDefault?: PlacementPoint
  /** Which TransformControls mode the fixture gizmo runs in. The parent owns
   *  this so it can drive both a manual toggle button and a held-Alt key. */
  gizmoMode?: GizmoMode
  /** Grid-snap state, owned by the route and shared by every camera's editing. Absent for a view
   *  that cannot edit (the Positions panel's plan), which snaps nothing. */
  snap?: SnapGrid
  /** Suppress the bottom-center patch info overlay — set when the parent shows
   *  the right-hand fixture control card, so the name isn't shown twice. */
  hidePatchSelectionInfo?: boolean
  onSelectionChange: (s: Selection, intent?: SelectIntent) => void
  /** A marquee on a section while editing: the fixtures inside it, and how they join the selection. */
  onMarqueeSelect?: (refs: SelectionRef[], intent: SelectIntent) => void
  onPlacementClick?: (p: PlacementPoint) => void
  onPatchPlacementChange?: (patch: FixturePatch, next: PatchPlacementUpdate, settled: boolean) => void
  onRegionPositionChange?: (region: StageRegionDto, next: RegionPositionUpdate, settled: boolean) => void
  onRiggingPositionChange?: (rig: RiggingDto, next: RiggingPositionUpdate, settled: boolean) => void
  /** A scene element dragged on a section while editing. */
  onElementPositionChange?: (element: StageElementDto, next: ElementPositionUpdate, settled: boolean) => void
  /** Filled while the canvas is mounted; see [StageRecovery]. */
  recoveryRef?: React.RefObject<StageRecovery | null>
  /** Which camera draws the scene (`lib/stageViewpoint.ts`). */
  camera?: StageCamera
  /** A saved view for the camera to land on (session 2); see `StageCameraRig`. */
  landing?: StageCameraLanding | null
  /**
   * Keep this window's camera poses in `sessionStorage`, so a remount — a route change, *Restore* —
   * lands where the camera was left. The Stage route's canvas only; an embedded view (the Positions
   * panel's plan) is a second camera and must not move the Stage view's.
   */
  persistCamera?: boolean
  /** The viewpoint's name and how to drive it, drawn over the canvas's top right; none when absent. */
  caption?: { name: string; note: string } | null
  /**
   * Read the scene document and draw its elements (session 3's builders). The Stage route's canvas
   * only: the Positions panel's plan is the rig's, and must not subscribe to the scene.
   */
  showScene?: boolean
  /** Which of the scene this window draws, and whether the air shows the beams (`scene/sceneView.ts`). */
  layers?: SceneLayers
  /** How many lights the surfaces take (`scene/lightTable.ts`). */
  lightBudget?: number
  /** *Sit in a seat…* while it is armed: the seats take the pointer and answer a click. */
  seatPicking?: SeatPicking | null
  /** Filled while the canvas is mounted; see [StageFraming]. */
  framingRef?: React.RefObject<StageFraming | null>
  /**
   * Draw offscreen for `render_view` (stage-view plan session 4) instead of on screen: a detached
   * canvas of this size, drawn on request (`CaptureCanvas`), and a camera that lands [landing]
   * without recording anything for this window. Every other prop means what it does on screen.
   */
  capture?: StageCapture | null
}

export function Stage3D({
  projectId,
  editMode,
  selection,
  selectedKeys,
  placing,
  view = DEFAULT_VIEW_FLAGS,
  placementZ,
  placementDefault = PLACEMENT_ORIGIN,
  gizmoMode = 'translate',
  hidePatchSelectionInfo = false,
  snap = NO_SNAP,
  onSelectionChange,
  onMarqueeSelect,
  onPlacementClick,
  onPatchPlacementChange,
  onRegionPositionChange,
  onRiggingPositionChange,
  onElementPositionChange,
  recoveryRef,
  camera = 'orbit',
  landing = null,
  persistCamera = false,
  caption = null,
  showScene = false,
  layers = DEFAULT_SCENE_LAYERS,
  lightBudget = DEFAULT_LIGHT_BUDGET,
  seatPicking = null,
  framingRef,
  capture = null,
}: Stage3DProps) {
  const { data: project } = useProjectQuery(projectId)
  const stageW = project?.stageWidthM ?? 10
  const stageD = project?.stageDepthM ?? 8
  const stageH = project?.stageHeightM ?? 6
  const { patches, regions, riggings, fixtureByKey, typeByKey, lanterns: lanternLibrary } = useStageData(
    projectId,
    stageW,
    stageD,
    stageH,
  )

  const { data: sceneElements } = useStageElementListQuery(projectId, { skip: !showScene })

  const gridSize = Math.max(stageW, stageD) * 1.6
  // Stable identity: StageEmitters rebuilds its instance buffers when this
  // changes, so it must not be a fresh object every render.
  const stageDims = useMemo(
    () => ({ width: stageW, height: stageH, depth: stageD }),
    [stageW, stageH, stageD],
  )
  const safeRiggings = riggings ?? EMPTY_RIGGINGS
  const safeRegions = regions ?? EMPTY_REGIONS
  const safePatches = patches ?? EMPTY_PATCHES
  // Clamped to the emitters' region capacity HERE, not just inside
  // StageEmitters: the per-fixture culls index visibility buffers by position
  // in this array, so an unclamped list past MAX_BEAM_REGIONS would write into
  // the next lobe's block. Regions beyond the cap still render as boxes; they
  // just don't receive light or cast beam shadows.
  const regionGeometry = useMemo(
    () => computeRegionGeometry(safeRegions).slice(0, MAX_BEAM_REGIONS),
    [safeRegions],
  )

  // The scene document, built per kind (`scene/builders/`) for the layers this window draws. A
  // platform linked to a region the view draws is that region's deck (D5), so the regions drawn are
  // part of the build.
  const drawnRegionUuids = useMemo(
    () => new Set(view.regions ? safeRegions.map((r) => r.uuid) : []),
    [view.regions, safeRegions],
  )
  const buildContext = useMemo(() => ({ drawnRegionUuids }), [drawnRegionUuids])
  const [buildCache] = useState<SceneBuildCache>(() => new WeakMap())
  const builds = useMemo(
    () =>
      showScene && sceneElements != null
        ? sceneBuilds(sceneElements, layers, buildContext, buildCache)
        : [],
    [showScene, sceneElements, layers, buildContext, buildCache],
  )
  const roomDrawn = useMemo(() => drawsRoom(builds), [builds])
  // What the section edit layer can press: the elements drawn, in draw order.
  const drawnElements = useMemo(
    () => (builds.length === 0 ? EMPTY_ELEMENTS : builds.map((b) => b.element)),
    [builds],
  )
  // Every surface a beam stops at — the axial reach (`scene/beamReach.ts`). Regions stop a beam
  // only while they are drawn; the beam shaders' own region shadows still test all of them.
  const colliders = useMemo(
    () =>
      sceneColliders({
        stage: stageDims,
        regions: view.regions ? regionGeometry : [],
        builds,
        catchSizeM: gridSize,
      }),
    [stageDims, view.regions, regionGeometry, builds, gridSize],
  )
  const beamClip = useMemo(() => beamClipFor(stageDims, builds), [stageDims, builds])
  const venueBounds = useMemo(() => sceneElementBounds(builds), [builds])
  // Haze degrades before frame rate (`scene/hazeGovernor.ts`): the governor in the canvas steps it.
  const [hazeQuality, setHazeQuality] = useState<HazeQuality>(HAZE_TIERS[0])

  // The canvas's container, which the emitters stamp with the light table's counts (`data-lights`).
  const containerRef = useRef<HTMLDivElement | null>(null)

  // *Sit in a seat…*: the seat under the pointer, named in the hint while the pick is armed.
  const [hoverSeat, setHoverSeat] = useState<string | null>(null)
  const picking = useMemo<SeatPicking | null>(
    () =>
      seatPicking == null
        ? null
        : {
            onPick: seatPicking.onPick,
            onHover: (seat) => {
              setHoverSeat(seat?.seatId ?? null)
              seatPicking.onHover?.(seat)
            },
          },
    [seatPicking],
  )
  useEffect(() => {
    if (seatPicking == null) setHoverSeat(null)
  }, [seatPicking])

  // `stageHidden` patches are omitted from the scene entirely — they're real
  // DMX but not stage objects (a dimmer on hard power). The exception is the
  // selected one: the picker panel can select a hidden patch, and drawing it
  // is the only way the operator can see what they're about to un-hide.
  const selectedPatchKey = selection?.kind === 'patch' ? selection.patchKey : null
  const visiblePatches = useMemo(
    () => safePatches.filter((p) => !p.stageHidden || p.key === selectedPatchKey),
    [safePatches, selectedPatchKey],
  )

  // patchTarget feeds TransformControls for the patch translate gizmo. Region
  // and rigging never use this — their interactions are entirely handle-based.
  const [patchTarget, setPatchTarget] = useState<Object3D | null>(null)
  // The current camera's controls — orbit, eye or section — which a drag switches off while it
  // holds the pointer. Lent by `StageCameraRig`.
  const orbitRef = useRef<StageCameraControls | null>(null)
  const cameraHandleRef = useRef<StageCameraHandle | null>(null)
  // Snapping state is owned by the route and shared by every camera's editing, so
  // one header control governs them all and Shift means the same thing in each.
  const snapActive = snap.active
  const snapActiveRef = snap.activeRef

  // Compare pointerdown vs pointerup positions so an orbit-drag that releases
  // over empty space doesn't get treated as a click-to-clear. A click on nothing
  // clears in view mode too: the fixture card and the aim panel follow the
  // selection, and there was no way to put them away from the canvas.
  const pointerDownRef = useRef<{ x: number; y: number } | null>(null)
  const handlePointerMissed = useCallback(
    (e: MouseEvent) => {
      if (placing) return
      if (e.button !== 0) return
      const d = pointerDownRef.current
      if (d && (Math.abs(e.clientX - d.x) > DRAG_PX_THRESHOLD || Math.abs(e.clientY - d.y) > DRAG_PX_THRESHOLD)) return
      onSelectionChange(null)
    },
    [placing, onSelectionChange],
  )

  const disableOrbit = useCallback(() => {
    if (orbitRef.current) orbitRef.current.enabled = false
  }, [])
  const enableOrbit = useCallback(() => {
    if (orbitRef.current) orbitRef.current.enabled = true
  }, [])

  const handleRegionClick = useCallback(
    (region: StageRegionDto) => {
      onSelectionChange({ kind: 'region', uuid: region.uuid })
    },
    [onSelectionChange],
  )
  const handleRiggingClick = useCallback(
    (rig: RiggingDto) => {
      onSelectionChange({ kind: 'rigging', uuid: rig.uuid })
    },
    [onSelectionChange],
  )
  const handleFixtureClick = useCallback(
    (patch: FixturePatch) => {
      onSelectionChange({ kind: 'patch', patchKey: patch.key })
    },
    [onSelectionChange],
  )
  const handleFixtureEditFocus = useCallback((group: Object3D) => {
    setPatchTarget(group)
  }, [])

  // Drop the patch gizmo target whenever edit mode turns off or selection
  // clears (or moves away from a patch).
  useEffect(() => {
    if (!editMode || selection?.kind !== 'patch') setPatchTarget(null)
  }, [editMode, selection])

  // Editing on a section (stage-view plan session 5): the edit layer over the canvas owns the
  // pointer — it hit-tests, drags, marquees, pans and zooms in the section's own metres — so the
  // scene's own gizmo, handles and placement plane stand down. Never in a render, which edits
  // nothing, and not while a seat pick is armed, which needs the seats to take the pointer.
  const sectionEditing = editMode && isOrthoCamera(camera) && capture == null && seatPicking == null
  const [sectionViewStore] = useState(createSectionViewStore)
  const sectionControls = useCallback(() => cameraHandleRef.current?.section ?? null, [])
  // A view reported by one section is not another's: leaving section editing, or changing
  // section, forgets it, so the layer waits for the new camera's first report rather than
  // hit-testing against the old one.
  useLayoutEffect(() => {
    sectionViewStore.clear()
  }, [sectionEditing, camera, sectionViewStore])

  // Hover/click is disabled during placement so the PlacementPlane catches the
  // click. Otherwise meshes are clickable in both edit and view modes — view
  // mode uses the click for "select to inspect" (info overlay); edit mode also
  // opens the side panel.
  const interactable = !placing
  const canEdit = editMode && interactable && !sectionEditing
  // A multi-selection lights as one: every selected object, not just the anchor.
  const isSelected = useCallback(
    (ref: SelectionRef) =>
      selectedKeys?.has(selectionKey(ref)) ??
      (selection != null && selectionKey(selection) === selectionKey(ref)),
    [selectedKeys, selection],
  )
  const selectedRegionUuids = useMemo(
    () => new Set(safeRegions.filter((r) => isSelected({ kind: 'region', uuid: r.uuid })).map((r) => r.uuid)),
    [safeRegions, isSelected],
  )
  const selectedRiggingUuids = useMemo(
    () => new Set(safeRiggings.filter((r) => isSelected({ kind: 'rigging', uuid: r.uuid })).map((r) => r.uuid)),
    [safeRiggings, isSelected],
  )
  const selectedRegion = useMemo(
    () => (selection?.kind === 'region' ? safeRegions.find((r) => r.uuid === selection.uuid) ?? null : null),
    [selection, safeRegions],
  )
  const selectedRigging = useMemo(
    () => (selection?.kind === 'rigging' ? safeRiggings.find((r) => r.uuid === selection.uuid) ?? null : null),
    [selection, safeRiggings],
  )

  // A paired dimmer's other lanterns: the same patch with the placement's geometry laid over its
  // own, so `FixtureModel` composes it through the placement's rigging and lights it from the
  // patch's channels. Memoised so each lantern's patch object is stable across renders.
  const lanterns = useMemo(() => lanternsFor(visiblePatches), [visiblePatches])

  // The whole scene as one box, for the orthographic sections' planes and fit. Keyed on the lists,
  // never on channels, so a section the operator has not moved refits when the rig changes and not
  // when a fader does.
  const sceneBounds = useMemo(() => {
    const points: LightingPoint[] = []
    for (const patch of [...visiblePatches, ...lanterns.map((l) => l.patch)]) {
      const at = worldPositionLighting(patch, safeRiggings)
      if (at) points.push(at)
    }
    return sceneBoundsLighting(stageDims, safeRiggings, safeRegions, points)
  }, [visiblePatches, lanterns, safeRiggings, safeRegions, stageDims])
  // Where the orbit camera stands until it is moved; read once, when the orbit rig mounts.
  const defaultOrbit = useMemo(() => defaultOrbitPose(stageDims), [stageDims])

  useEffect(() => {
    if (!framingRef) return
    framingRef.current = {
      frame: (points) =>
        cameraHandleRef.current?.frame(points.map(({ x, y, z }) => [x, z, -y] as const)),
    }
    return () => {
      framingRef.current = null
    }
  }, [framingRef])

  // Each slot's body — fixtures first, then lanterns, in the slot order below — and what it needs
  // from the emitters. Fresh layouts every render are fine: `StageBodies` and `StageEmitters` each
  // rebuild only when their signature changes.
  const bodySlots = useMemo(
    () =>
      [...visiblePatches, ...lanterns.map((l) => l.patch)].map((patch) => {
        const fixture = fixtureByKey.get(patch.key)
        const fixtureType = fixture ? typeByKey.get(fixture.typeKey) : undefined
        const spec = bodySpecOf(patch, fixture, fixtureType, lanternLibrary)
        const rigging = patch.riggingUuid ? safeRiggings.find((r) => r.uuid === patch.riggingUuid) : undefined
        return { spec, mount: mountFor(rigging), needs: emitterNeedsForSpec(spec, fixture) }
      }),
    [visiblePatches, lanterns, fixtureByKey, typeByKey, safeRiggings, lanternLibrary],
  )
  const bodyLayout = useMemo(() => buildBodyLayout(bodySlots), [bodySlots])
  const emitterLayout = useMemo(() => buildEmitterLayout(bodySlots.map((b) => b.needs)), [bodySlots])

  // The label layer: one store per Stage3D, one DOM layer over the canvas (see stageLabels.ts).
  const [labelStore] = useState(() => new StageLabelStore())
  // A stable ref callback: an inline one is called with null and then the element on every
  // render, which would detach every label and throw away its measured box each time.
  const labelContainerRef = useCallback(
    (el: HTMLDivElement | null) => labelStore.setContainer(el),
    [labelStore],
  )
  useEffect(() => {
    labelStore.setMode(view.labels)
  }, [labelStore, view.labels])

  // Context loss. `canvasKey` remounts the canvas — a fresh renderer and a fresh context — which
  // is *Restore*: a context the browser took back to save memory may never be offered again, so
  // waiting on `webglcontextrestored` alone could leave the view paused for good.
  const [canvasKey, setCanvasKey] = useState(0)
  const [contextLost, setContextLost] = useState(false)
  const loseContextRef = useRef<WEBGL_lose_context | null>(null)
  const restore = useCallback(() => {
    setContextLost(false)
    setCanvasKey((k) => k + 1)
  }, [])
  useEffect(() => {
    if (!recoveryRef) return
    recoveryRef.current = {
      testContextLoss: () => loseContextRef.current?.loseContext(),
    }
    return () => {
      recoveryRef.current = null
    }
  }, [recoveryRef])

  const fixtureNodes = visiblePatches.map((patch, slot) => {
    const fixture = fixtureByKey.get(patch.key)
    const fixtureType = fixture ? typeByKey.get(fixture.typeKey) : undefined
    return (
      <FixtureModel
        key={patch.id}
        patch={patch}
        fixture={fixture}
        fixtureType={fixtureType}
        lanterns={lanternLibrary}
        riggings={safeRiggings}
        regionGeometry={regionGeometry}
        slot={slot}
        selected={isSelected({ kind: 'patch', patchKey: patch.key })}
        editMode={interactable}
        onClick={interactable ? () => handleFixtureClick(patch) : undefined}
        onEditFocus={editMode && !sectionEditing ? handleFixtureEditFocus : undefined}
      />
    )
  })
  // Emitter slots follow the fixtures', so every lantern has a beam of its own. No `onEditFocus`:
  // the translate gizmo binds to the fixture's own placement only — a lantern is moved in the
  // patch form, and a gizmo on one would write its position to the fixture's.
  const lanternNodes = lanterns.map(({ id, source, patch }, i) => {
    const fixture = fixtureByKey.get(patch.key)
    const fixtureType = fixture ? typeByKey.get(fixture.typeKey) : undefined
    return (
      <FixtureModel
        key={id}
        patch={patch}
        fixture={fixture}
        fixtureType={fixtureType}
        lanterns={lanternLibrary}
        riggings={safeRiggings}
        regionGeometry={regionGeometry}
        slot={visiblePatches.length + i}
        selected={isSelected({ kind: 'patch', patchKey: patch.key })}
        editMode={interactable}
        onClick={interactable ? () => handleFixtureClick(source) : undefined}
      />
    )
  })
  const allFixtureNodes = lanternNodes.length === 0 ? fixtureNodes : [...fixtureNodes, ...lanternNodes]

  // The scene, drawn by the on-screen canvas or, for a render, by the capture canvas: one set of
  // children for both, so a render is exactly what the view shows.
  const scene = (
    <>
      <ContextLossWatcher
        loseContextRef={loseContextRef}
        onLost={() => {
          setContextLost(true)
          // A render has no Restore to offer: it ends, and says why.
          capture?.onError('the graphics context was lost')
        }}
        onRestored={() => setContextLost(false)}
      />
      <StageLabelDriver store={labelStore} paused={contextLost} />
      <HazeGovernorProbe onChange={setHazeQuality} />
      <StageInvalidateProvider>
      <StageLabelContext.Provider value={labelStore}>
      <SurfaceLightingProvider>
      <ambientLight intensity={0.5} />
      {/* A modelled room is the floor the operator reads by; the grid floats at deck height over
          the stalls. It stays while editing, where it is a measure. */}
      {/* On a section while editing the edit layer draws the snap grid instead. */}
      {(!roomDrawn || editMode) && !sectionEditing && <gridHelper args={[gridSize, 20, '#4a5a6a', '#2a3540']} />}
      <StageFloor width={stageW} depth={stageD} />
      {!roomDrawn && <CatchFloor size={gridSize} />}
      {!roomDrawn && <StageBackWall width={stageW} depth={stageD} height={stageH} />}
      <StageBoxOutline width={stageW} depth={stageD} height={stageH} />
      <OriginMarkers depth={stageD} />
      {placing && onPlacementClick && !sectionEditing && (
        <PlacementClickCatcher targetZ={placementZ ?? 0} onClick={onPlacementClick} />
      )}
      {view.regions && (
        <StageRegionMeshes
          regions={safeRegions}
          selectedUuids={selectedRegionUuids}
          editMode={interactable}
          onClick={interactable ? handleRegionClick : undefined}
          onMove={canEdit && onRegionPositionChange ? onRegionPositionChange : undefined}
          snapActiveRef={snapActiveRef}
          onDragStart={disableOrbit}
          onDragEnd={enableOrbit}
        />
      )}
      {view.riggings && (
        <RiggingMeshes
          riggings={safeRiggings}
          selectedUuids={selectedRiggingUuids}
          editMode={interactable}
          onClick={interactable ? handleRiggingClick : undefined}
          onMove={canEdit && onRiggingPositionChange ? onRiggingPositionChange : undefined}
          snapActiveRef={snapActiveRef}
          onDragStart={disableOrbit}
          onDragEnd={enableOrbit}
        />
      )}
      {view.fixtures && (
        <StageBodies layout={bodyLayout}>
          {view.beamCones ? (
            <StageEmitters
              layout={emitterLayout}
              regionGeometry={regionGeometry}
              colliders={colliders}
              clip={beamClip}
              lightBudget={lightBudget}
              haze={layers.haze}
              hazeQuality={hazeQuality}
              statsRef={containerRef}
            >
              {allFixtureNodes}
            </StageEmitters>
          ) : allFixtureNodes}
        </StageBodies>
      )}
      {canEdit && selectedRegion && onRegionPositionChange && (
        <RegionEditHandles
          region={selectedRegion}
          snapActiveRef={snapActiveRef}
          onChange={(next, settled) => onRegionPositionChange(selectedRegion, next, settled)}
          onDragStart={disableOrbit}
          onDragEnd={enableOrbit}
        />
      )}
      {canEdit && selectedRigging && onRiggingPositionChange && (
        <RiggingEndpointHandles
          rig={selectedRigging}
          snapActiveRef={snapActiveRef}
          onChange={(next, settled) => onRiggingPositionChange(selectedRigging, next, settled)}
          onDragStart={disableOrbit}
          onDragEnd={enableOrbit}
        />
      )}
      {builds.length > 0 && <StageSceneElements builds={builds} seatPicking={picking} />}
      <StageCameraRig
        camera={camera}
        landing={landing}
        defaultOrbit={defaultOrbit}
        bounds={sceneBounds}
        beyond={venueBounds}
        persist={persistCamera && capture == null}
        oneShot={capture != null}
        controlsRef={orbitRef}
        handleRef={cameraHandleRef}
        onSectionView={sectionEditing ? sectionViewStore.set : undefined}
      />
      <Controls
        orbitRef={orbitRef}
        patchTarget={editMode && !sectionEditing ? patchTarget : null}
        selection={selection}
        patches={patches ?? null}
        riggings={safeRiggings}
        snapActive={snapActive}
        snapStepM={snap.step}
        gizmoMode={gizmoMode}
        onPatchPlacementChange={onPatchPlacementChange}
      />
      <Bloom />
      </SurfaceLightingProvider>
      </StageLabelContext.Provider>
      </StageInvalidateProvider>
    </>
  )

  return (
    <div
      ref={containerRef}
      className={`relative h-full w-full ${placing || seatPicking ? 'cursor-crosshair' : ''}`}
      data-section-editing={sectionEditing || undefined}
      data-haze-tier={hazeQuality.tier}
      onPointerDown={(e) => { pointerDownRef.current = { x: e.clientX, y: e.clientY } }}
    >
      {/* `dpr` capped at 1.5: at 2 a Retina full-window canvas and every target behind it hold
          four times the pixels of 1× (the stage-view plan's memory finding), and 1.5 is where the
          beams stop looking soft. `frameloop="demand"`: the canvas renders while something moves —
          a channel the scene reads, the camera, a drag, a spinning gobo — and not at all when the
          stage is still. What asks for a frame is spelled out in stage-vis-engineering.md. */}
      {capture != null ? (
        <CaptureCanvas capture={capture} background={STAGE_BACKGROUND}>
          {scene}
        </CaptureCanvas>
      ) : (
        <Canvas
          key={canvasKey}
          flat
          dpr={[1, 1.5]}
          frameloop="demand"
          gl={{ toneMapping: NoToneMapping, antialias: true }}
          style={{ background: STAGE_BACKGROUND }}
          onPointerMissed={handlePointerMissed}
        >
          {scene}
        </Canvas>
      )}
      {/* The label layer: every label's <div>, owned and positioned by the store. */}
      <div
        ref={labelContainerRef}
        aria-hidden
        className={`pointer-events-none absolute inset-0 overflow-hidden ${contextLost ? 'hidden' : ''}`}
      />
      {sectionEditing && isOrthoCamera(camera) && !contextLost && (
        <SectionEditLayer
          projectId={projectId}
          camera={camera}
          viewStore={sectionViewStore}
          controls={sectionControls}
          selection={selection}
          selectedKeys={selectedKeys}
          view={view}
          snap={snap}
          riggings={safeRiggings}
          regions={safeRegions}
          elements={drawnElements}
          placing={placing != null}
          placementDefault={placementDefault}
          onSelectionChange={onSelectionChange}
          onMarqueeSelect={onMarqueeSelect}
          onPlacementClick={onPlacementClick}
          onPatchPlacementChange={onPatchPlacementChange}
          onRegionPositionChange={onRegionPositionChange}
          onRiggingPositionChange={onRiggingPositionChange}
          onElementPositionChange={onElementPositionChange}
        />
      )}
      {caption != null && !contextLost && <ViewpointCaption name={caption.name} note={caption.note} />}
      {contextLost && <ContextLostOverlay onRestore={restore} />}
      {placing && (
        <div className="pointer-events-none absolute left-1/2 top-3 -translate-x-1/2 rounded-md bg-background/85 px-3 py-1.5 text-xs shadow-md backdrop-blur">
          Click on the stage to place {placing} · Esc to cancel
        </div>
      )}
      {picking != null && !contextLost && (
        <div
          role="status"
          className="pointer-events-none absolute left-1/2 top-3 -translate-x-1/2 rounded-md bg-background/85 px-3 py-1.5 text-xs shadow-md backdrop-blur"
        >
          {hoverSeat == null ? 'Click a seat to sit in it' : `Sit in ${seatName(hoverSeat)}`} · Esc to cancel
        </div>
      )}
      {!editMode && !placing && !(hidePatchSelectionInfo && selection?.kind === 'patch') && (
        <SelectionInfo selection={selection} patches={patches} regions={safeRegions} riggings={safeRiggings} />
      )}
    </div>
  )
}

function SelectionInfo({
  selection,
  patches,
  regions,
  riggings,
}: {
  selection: Selection
  patches: FixturePatch[] | undefined
  regions: StageRegionDto[]
  riggings: RiggingDto[]
}) {
  if (!selection) return null
  let label = ''
  let detail = ''
  if (selection.kind === 'patch') {
    const p = patches?.find((x) => x.key === selection.patchKey)
    if (!p) return null
    label = p.displayName
    const ch = p.channelCount ?? 1
    detail = `${[p.manufacturer, p.model].filter(Boolean).join(' ') || 'Fixture'} · ${p.startChannel}–${p.startChannel + ch - 1} on U${p.universe}`
  } else if (selection.kind === 'region') {
    const r = regions.find((x) => x.uuid === selection.uuid)
    if (!r) return null
    label = r.name
    detail = `Region · ${formatTriple(r.widthM, r.depthM, r.heightM, ' × ')} m`
  } else if (selection.kind === 'rigging') {
    const r = riggings.find((x) => x.uuid === selection.uuid)
    if (!r) return null
    label = r.name
    detail = `${r.kind ?? 'Rigging'} · ${r.lengthM == null ? '—' : r.lengthM.toFixed(1)} m`
  } else {
    // A scene element is selected only while editing, where this card is not drawn.
    return null
  }
  return (
    <div className="pointer-events-none absolute bottom-3 left-1/2 -translate-x-1/2 rounded-md bg-background/85 px-3 py-1.5 text-xs shadow-md backdrop-blur">
      <span className="font-semibold">{label}</span>
      <span className="ml-2 text-muted-foreground">{detail}</span>
    </div>
  )
}

/** A seat id as the hint says it: `row F, seat 6`. */
function seatName(seatId: string): string {
  const m = /^([A-Z])(\d+)$/.exec(seatId)
  return m == null ? `seat ${seatId}` : `row ${m[1]}, seat ${m[2]}`
}

/** The viewpoint's name and how to drive it, over the canvas's top-right corner (`Stage.dc.html`). */
function ViewpointCaption({ name, note }: { name: string; note: string }) {
  return (
    <div className="pointer-events-none absolute right-3 top-3 max-w-[calc(100%-1.5rem)] truncate rounded-md bg-background/70 px-2 py-1 text-xs backdrop-blur">
      <span className="font-semibold">{name}</span>
      <span className="ml-1.5 text-muted-foreground">{note}</span>
    </div>
  )
}

interface ControlsProps {
  orbitRef: React.RefObject<StageCameraControls | null>
  patchTarget: Object3D | null
  selection: Selection
  patches: FixturePatch[] | null
  riggings: RiggingDto[]
  snapActive: boolean
  snapStepM: number
  gizmoMode: GizmoMode
  onPatchPlacementChange?: (patch: FixturePatch, next: PatchPlacementUpdate, settled: boolean) => void
}

// TC binds to a proxy Object3D rather than the fixture group itself so we can
// set the gizmo's local frame independently — rig-aligned for translate-on-
// bar, base-orientation-aligned for rotate.
function Controls({
  orbitRef,
  patchTarget,
  selection,
  patches,
  riggings,
  snapActive,
  snapStepM,
  gizmoMode,
  onPatchPlacementChange,
}: ControlsProps) {
  const tcRef = useRef<React.ComponentRef<typeof TransformControls>>(null!)
  const [proxy, setProxy] = useState<Object3D | null>(null)
  const draggingRef = useRef(false)

  const selectedPatch = useMemo(
    () => (selection?.kind === 'patch' ? patches?.find((p) => p.key === selection.patchKey) ?? null : null),
    [selection, patches],
  )

  const rig =
    selectedPatch?.riggingUuid != null
      ? riggings.find((r) => r.uuid === selectedPatch.riggingUuid) ?? null
      : null
  const rigMounted = rig != null
  // In rotate mode the gizmo edits the patch's base orientation in world axes,
  // independent of any rig pose. In translate mode the gizmo aligns with the
  // rig (if mounted) so the visible X arrow matches the bar's direction.
  const rotateMode = gizmoMode === 'rotate'
  // Rotate uses space="local"; the proxy carries only yaw so the Y ring stays
  // around the world up axis and the X ring is the horizontal "pitch axis"
  // (alt-az mount). pitch is recovered as `dragStartPitch + Δx` at flush.
  const useLocalSpace = rotateMode ? true : rigMounted
  const dragStartPitchRef = useRef(0)

  // Skipped mid-drag — TC's in-progress quaternion delta would be clobbered.
  useEffect(() => {
    if (!proxy || draggingRef.current) return
    if (rotateMode) {
      proxy.rotation.set(0, MathUtils.degToRad(selectedPatch?.baseYawDeg ?? 0), 0, 'YXZ')
    } else if (rig) {
      rigEuler(rig, proxy.rotation)
    } else {
      proxy.rotation.set(0, 0, 0)
    }
    proxy.updateMatrixWorld(true)
    // rigEuler reads only pitchDeg/yawDeg/rollDeg, so the listed rig fields
    // cover every value this effect actually consumes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    proxy,
    rotateMode,
    rig?.uuid,
    rig?.yawDeg,
    rig?.pitchDeg,
    rig?.rollDeg,
    selectedPatch?.id,
    selectedPatch?.baseYawDeg,
  ])

  // Keep proxy.position glued to the fixture group while the user isn't
  // dragging — mid-drag TC is authoritative and we mirror back in
  // onObjectChange.
  useFrame(() => {
    if (!proxy || !patchTarget || draggingRef.current) return
    patchTarget.updateMatrixWorld()
    patchTarget.getWorldPosition(SCRATCH_VEC1)
    if (proxy.position.equals(SCRATCH_VEC1)) return
    proxy.position.copy(SCRATCH_VEC1)
  })

  const flush = useCallback(
    (settled: boolean) => {
      if (!proxy || !selectedPatch) return
      proxy.updateMatrixWorld(true)
      if (rotateMode) {
        SCRATCH_EULER.setFromQuaternion(proxy.quaternion, 'YXZ')
        const next: PatchPlacementUpdate = {
          riggingUuid: selectedPatch.riggingUuid,
          stageX: selectedPatch.stageX,
          stageY: selectedPatch.stageY,
          stageZ: selectedPatch.stageZ,
          // Both normalised: the backend validates these to ±180, and a hung
          // mover now sits at basePitch 180, so without this the very next
          // nudge of the gizmo would push it out of range and 400.
          baseYawDeg: normaliseSignedDeg(MathUtils.radToDeg(SCRATCH_EULER.y)),
          basePitchDeg: normaliseSignedDeg(
            dragStartPitchRef.current + MathUtils.radToDeg(SCRATCH_EULER.x),
          ),
        }
        onPatchPlacementChange?.(selectedPatch, next, settled)
        return
      }
      proxy.getWorldPosition(SCRATCH_VEC1)
      const next = patchPlacementFromWorld(selectedPatch, SCRATCH_VEC1, riggings)
      onPatchPlacementChange?.(selectedPatch, next, settled)
    },
    [proxy, selectedPatch, riggings, onPatchPlacementChange, rotateMode],
  )

  // Rotate-mode skips the mirror — base orientation drives the head via
  // DMX-decoded pan/tilt, not the body group's transform.
  const handleObjectChange = useCallback(() => {
    if (!rotateMode && patchTarget && proxy) {
      proxy.getWorldPosition(SCRATCH_VEC1)
      patchTarget.position.copy(SCRATCH_VEC1)
      patchTarget.updateMatrixWorld()
    }
    flush(false)
  }, [patchTarget, proxy, flush, rotateMode])

  // TC's plane handles (XY/XZ/YZ) and centre (XYZ) are visible whenever any
  // pair of showX/Y/Z is true — `showX/Y/Z` can't hide them individually since
  // the names share letters. Strip them once the gizmo mounts so the user can
  // only drag along a single rig-local axis.
  useEffect(() => {
    if (!patchTarget) return
    type GizmoInternals = { _gizmo?: { gizmo: Record<string, Object3D>; picker: Record<string, Object3D> } }
    const root = (tcRef.current as unknown as GizmoInternals | null)?._gizmo
    if (!root) return
    for (const set of ['gizmo', 'picker'] as const) {
      const group = root[set]?.translate
      if (!group) continue
      for (const handle of [...group.children]) {
        if (PLANE_HANDLE_NAMES.has(handle.name)) group.remove(handle)
      }
    }
  }, [patchTarget])

  // OrbitControls and TransformControls fight for pointer events; we listen to
  // TC's underlying THREE 'dragging-changed' event to disable Orbit during drag
  // and fire a single settled flush on release.
  useEffect(() => {
    if (!patchTarget) return
    const tc = tcRef.current as unknown as { addEventListener: (t: string, l: (e: { value: boolean }) => void) => void; removeEventListener: (t: string, l: (e: { value: boolean }) => void) => void } | null
    if (!tc) return
    const onDrag = (e: { value: boolean }) => {
      if (orbitRef.current) orbitRef.current.enabled = !e.value
      draggingRef.current = e.value
      // TC's gizmo meshes have no R3F handlers, so R3F passes the pointerdown
      // through to whichever body sits behind. Notify any pending body-drag
      // discriminators so they bail before they can promote or fire onClick.
      if (e.value) {
        notifyTransformDragStart()
        // Snapshot the patch's pitch at drag start; the alt-az proxy carries
        // only yaw, so the extracted Euler X is the *delta* from this anchor.
        dragStartPitchRef.current = selectedPatch?.basePitchDeg ?? 0
      }
      if (!e.value) flush(true)
    }
    tc.addEventListener('dragging-changed', onDrag)
    return () => tc.removeEventListener('dragging-changed', onDrag)
  }, [patchTarget, flush, orbitRef, selectedPatch?.basePitchDeg])

  return (
    <>
      <group ref={setProxy} />
      {patchTarget && proxy && (
        <TransformControls
          ref={tcRef as never}
          object={proxy}
          mode={gizmoMode}
          space={useLocalSpace ? 'local' : 'world'}
          showX
          showY
          showZ={!rotateMode}
          translationSnap={!rotateMode && snapActive ? snapStepM : null}
          rotationSnap={rotateMode && snapActive ? MathUtils.degToRad(SNAP_ANGLE_DEG) : null}
          onObjectChange={handleObjectChange}
        />
      )}
    </>
  )
}

// — math helpers ———————————————————————————————————————————————————

const SCRATCH_VEC1 = new Vector3()
const SCRATCH_EULER = new Euler()

// Wireframe box marking the stage boundary. Stage occupies lighting
// (X∈[-w/2,w/2], Y∈[0,d], Z∈[0,h]) → R3F (x∈[-w/2,w/2], y∈[0,h], z∈[-d,0]);
// box centre is therefore (0, h/2, -d/2).
function StageBoxOutline({
  width,
  depth,
  height,
}: {
  width: number
  depth: number
  height: number
}) {
  return (
    <mesh position={[0, height / 2, -depth / 2]} raycast={NO_RAYCAST}>
      <boxGeometry args={[width, height, depth]} />
      <meshBasicMaterial visible={false} />
      <Edges color="#7a8a9e" />
    </mesh>
  )
}

// Captures any canvas click while placement mode is active. Raycasts from the
// camera against the y=targetY plane so a click anywhere on screen projects
// onto the height the new object will live at — WYSIWYG even for raised
// objects like trusses. Skips clicks that turn into orbit drags.
const PLACEMENT_NORMAL = new Vector3(0, 1, 0)
const DRAG_PX_THRESHOLD = 4

// Raycasts pointer clicks onto a horizontal plane at lighting height `targetZ`.
// A ground-plane hit fixes X and Y; Z is the plane's own height, so the emitted
// point is complete and the caller needs no fallback.
function PlacementClickCatcher({
  targetZ,
  onClick,
}: {
  targetZ: number
  onClick: (p: PlacementPoint) => void
}) {
  const { camera, gl } = useThree()
  useEffect(() => {
    const el = gl.domElement
    const raycaster = new Raycaster()
    const ndc = new Vector2()
    const hit = new Vector3()
    // Plane equation n·X + d = 0 with n=(0,1,0) means y + d = 0, so d=-targetZ.
    // R3F +Y is lighting +Z, so the plane sits at lighting height targetZ.
    const plane = new Plane(PLACEMENT_NORMAL, -targetZ)
    let downX = 0
    let downY = 0
    const onDown = (e: PointerEvent) => { downX = e.clientX; downY = e.clientY }
    const onUp = (e: PointerEvent) => {
      if (e.button !== 0) return
      if (Math.abs(e.clientX - downX) > DRAG_PX_THRESHOLD || Math.abs(e.clientY - downY) > DRAG_PX_THRESHOLD) return
      const rect = el.getBoundingClientRect()
      ndc.set(
        ((e.clientX - rect.left) / rect.width) * 2 - 1,
        -((e.clientY - rect.top) / rect.height) * 2 + 1,
      )
      raycaster.setFromCamera(ndc, camera)
      if (!raycaster.ray.intersectPlane(plane, hit)) return
      const { x, y } = fromThree(hit)
      onClick({ x, y, z: targetZ })
    }
    el.addEventListener('pointerdown', onDown)
    el.addEventListener('pointerup', onUp)
    return () => {
      el.removeEventListener('pointerdown', onDown)
      el.removeEventListener('pointerup', onUp)
    }
  }, [camera, gl, onClick, targetZ])
  return null
}

// The stage's own floor across its footprint, lit like every other surface (the retired floor
// cookies drew pools on it). A few millimetres below the deck, so a region or a platform whose top is
// at 0 draws over it rather than fighting it for the same depth.
const STAGE_FLOOR_FINISH = { colour: '#1c2330', pattern: 'PLAIN', emissive: false } as const
function StageFloor({ width, depth }: { width: number; depth: number }) {
  const material = useSurfaceMaterial(STAGE_FLOOR_FINISH)
  return (
    <mesh position={[0, -0.004, -depth / 2]} rotation={[-Math.PI / 2, 0, 0]} raycast={NO_RAYCAST} material={material}>
      <planeGeometry args={[width, depth]} />
    </mesh>
  )
}

// Round a stage with no room modelled, the grid's square catches light the way the retired floor
// cookies did anywhere on the plane: the light alone, added over the canvas, no surface of its own.
const CATCH_FINISH = { colour: '#ffffff', pattern: 'PLAIN', emissive: false } as const
function CatchFloor({ size }: { size: number }) {
  const material = useSurfaceMaterial(CATCH_FINISH, { catchOnly: true })
  return (
    <mesh position={[0, -0.006, 0]} rotation={[-Math.PI / 2, 0, 0]} raycast={NO_RAYCAST} material={material}>
      <planeGeometry args={[size, size]} />
    </mesh>
  )
}

// The upstage back wall — a real surface, lit, so a gobo'd cyc wash lands on something visible.
// Drawn only while no room is modelled; a room's stage house has a back wall of its own.
const BACK_WALL_FINISH = { colour: '#161c26', pattern: 'PLAIN', emissive: false } as const
function StageBackWall({ width, depth, height }: { width: number; depth: number; height: number }) {
  const material = useSurfaceMaterial(BACK_WALL_FINISH)
  return (
    <mesh position={[0, height / 2, -depth - 0.002]} raycast={NO_RAYCAST} material={material}>
      <planeGeometry args={[width, height]} />
    </mesh>
  )
}

// Lighting → R3F: X right (R3F +X) red, Y upstage (R3F −Z) green, Z up (R3F +Y) blue.
function OriginMarkers({ depth }: { depth: number }) {
  const len = 0.6
  return (
    <group>
      <arrowHelper args={[AXIS_X, ORIGIN, len, 0xd45757, 0.12, 0.08]} />
      <arrowHelper args={[AXIS_UPSTAGE, ORIGIN, len, 0x6cc36c, 0.12, 0.08]} />
      <arrowHelper args={[AXIS_UP, ORIGIN, len, 0x6ba8e8, 0.12, 0.08]} />
      <Text
        font={stageTextFont}
        position={[0, 0.002, 0.3]}
        rotation={[-Math.PI / 2, 0, 0]}
        fontSize={0.35}
        color="#a3b6c9"
        anchorX="center"
        anchorY="middle"
        raycast={NO_RAYCAST}
      >
        FOH
      </Text>
      <Text
        font={stageTextFont}
        position={[0, 0.002, -depth - 0.3]}
        rotation={[-Math.PI / 2, 0, 0]}
        fontSize={0.28}
        color="#6a7d92"
        anchorX="center"
        anchorY="middle"
        raycast={NO_RAYCAST}
      >
        upstage
      </Text>
    </group>
  )
}

const ORIGIN = new Vector3(0, 0, 0)
const AXIS_X = new Vector3(1, 0, 0)
const AXIS_UPSTAGE = new Vector3(0, 0, -1)
const AXIS_UP = new Vector3(0, 1, 0)

/**
 * Feeds the haze governor (`scene/hazeGovernor.ts`) from the frame loop: after the frame has
 * rendered (priority 2, past the composer's 1), the gap since the previous frame counts only when
 * that frame asked for this one from inside the loop — R3F's `internal.frames` is 2 when a
 * `useFrame` invalidated — so an idle canvas, or one redrawn by a fader, never reads as slow.
 */
function HazeGovernorProbe({ onChange }: { onChange: (quality: HazeQuality) => void }) {
  const [governor] = useState(() => new HazeGovernor())
  const last = useRef<{ at: number; continuing: boolean } | null>(null)
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange
  useFrame((state) => {
    const now = performance.now()
    const previous = last.current
    if (previous?.continuing) {
      const next = governor.sample(now - previous.at, now)
      if (next != null) onChangeRef.current(next)
    } else {
      governor.endRun()
    }
    last.current = { at: now, continuing: state.internal.frames > 1 }
  }, 2)
  return null
}

/**
 * Watches the canvas for `webglcontextlost` / `webglcontextrestored`. On loss it cancels the
 * default (without which the browser never offers the context back), and the parent draws the
 * paused state instead of the blank canvas WebKit otherwise leaves. It also hands up
 * `WEBGL_lose_context` for *Test recovery*.
 */
function ContextLossWatcher({
  loseContextRef,
  onLost,
  onRestored,
}: {
  loseContextRef: React.RefObject<WEBGL_lose_context | null>
  onLost: () => void
  onRestored: () => void
}) {
  const gl = useThree((s) => s.gl)
  const invalidate = useThree((s) => s.invalidate)
  const onLostRef = useRef(onLost)
  onLostRef.current = onLost
  const onRestoredRef = useRef(onRestored)
  onRestoredRef.current = onRestored
  useEffect(() => {
    const canvas = gl.domElement
    loseContextRef.current = gl.getContext().getExtension('WEBGL_lose_context')
    const lost = (e: Event) => {
      e.preventDefault()
      onLostRef.current()
    }
    const restored = () => {
      onRestoredRef.current()
      invalidate()
    }
    canvas.addEventListener('webglcontextlost', lost)
    canvas.addEventListener('webglcontextrestored', restored)
    return () => {
      canvas.removeEventListener('webglcontextlost', lost)
      canvas.removeEventListener('webglcontextrestored', restored)
      loseContextRef.current = null
    }
  }, [gl, invalidate, loseContextRef])
  return null
}

/** What the view shows while it has no context: never a blank canvas. */
function ContextLostOverlay({ onRestore }: { onRestore: () => void }) {
  return (
    <div className="absolute inset-0 flex items-center justify-center bg-background/80">
      <div role="status" className="max-w-xs space-y-2 rounded-md border bg-background p-4 text-center shadow-md">
        <p className="text-sm font-semibold">3D paused</p>
        <p className="text-xs text-muted-foreground">
          The browser took the graphics context back to save memory. Everything else on the desk
          carries on.
        </p>
        <Button size="sm" onClick={onRestore}>
          <RotateCcw className="mr-1 size-3.5" />
          Restore 3D
        </Button>
      </div>
    </div>
  )
}
