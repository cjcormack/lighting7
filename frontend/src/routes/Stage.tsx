import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Navigate, useNavigate, useSearchParams } from 'react-router'
import { toast } from 'sonner'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { ChevronDown, Grid2x2, Loader2, Move, Pencil, Plus, RotateCw } from 'lucide-react'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useViewedProject } from '../ProjectSwitcher'
import { useCurrentProjectQuery, useProjectQuery } from '../store/projects'
import { useUpdatePatchMutation, usePatchListQuery, useVisiblePatchListQuery } from '../store/patches'
import {
  useUpdateStageRegionMutation,
  useStageRegionListQuery,
  useCreateStageRegionMutation,
} from '../store/stageRegions'
import {
  useUpdateRiggingMutation,
  useRiggingListQuery,
  useCreateRiggingMutation,
} from '../store/riggings'
import {
  placementUnchanged,
  useDragOrigin,
  writeElementPlacement,
  writePatchPlacement,
  writeRegionPlacement,
  writeRiggingPlacement,
  type ElementPlacementValues,
  type PatchPlacementValues,
  type RegionPlacementValues,
  type RiggingPlacementValues,
} from '../store/stagePlacement'
import { formatError } from '../lib/formatError'
import { isEditableTarget } from '../lib/domUtils'
import {
  Stage3D,
  type ElementPositionUpdate,
  type GizmoMode,
  type PatchPlacementUpdate,
  type PlacementPoint,
  type RegionPositionUpdate,
  type RiggingPositionUpdate,
  type Selection,
  type StageFraming,
  type StageRecovery,
} from '../components/stage3d/Stage3D'
import { StageViewpointPicker } from '../components/stage3d/StageViewpointPicker'
import { SaveViewpointSheet } from '../components/stage3d/SaveViewpointSheet'
import {
  resolveSavedViewpoint,
  resolveSeatViewpoint,
  savedViewCamera,
  savedViewCameras,
  savedViewCaption,
  seatViewpointCaption,
  seatViewpointName,
  viewpointFromCamera,
} from '../components/stage3d/savedViewpoints'
import {
  setLightBudget,
  setSceneLayer,
  useLightBudget,
  useSceneLayers,
} from '../components/stage3d/scene/sceneView'
import type { SeatPicking } from '../components/stage3d/scene/StageSceneElements'
import { isElementShown } from '../components/stage3d/scene/sceneParts'
import { defaultOrbitPose } from '../components/stage3d/stageCameras'
import { deskSelectionPoints, stageSelectionPoints } from '../components/stage3d/framingPoints'
import {
  STAGE_CAMERAS,
  STAGE_CAMERA_LABELS,
  STAGE_CAMERA_NOTES,
  cameraOfViewpoint,
  consumeLaunchStageOptions,
  isOrthoCamera,
  isStageCamera,
  noteSavedViewpointCameras,
  markViewpointLanded,
  parseSeatViewpointRef,
  seatViewpointRef,
  setStageViewpoint,
  useStageViewpoint,
  type SavedViewpointRef,
  type SeatViewpointRef,
} from '../lib/stageViewpoint'
import { readEyePose, readOrbitPose } from '../lib/stageCameraPoses'
import { seatingParams } from '../lib/stageSeats'
import { useStageViewpointListQuery } from '../store/stageViewpoints'
import {
  useCreateStageElementMutation,
  useStageElementListQuery,
  useUpdateStageElementMutation,
} from '../store/stageElements'
import type { CreateStageViewpointRequest } from '../api/stageViewpointApi'
import { useDeskSelection } from '../store/selection'
import { DEFAULT_STAGE_DIMS } from '../hooks/useProjectedPatches'
import { DEFAULT_RIGGING_LENGTH_M } from '../components/stage3d/RiggingMeshes'
import { StageViewMenu } from '../components/stage3d/StageViewMenu'
import { useStageView } from '../components/stage3d/useStageView'
import { setVisSource, useVisSource } from '../hooks/useVisSource'
import { useNextGoStatus } from '../hooks/useNextGoPreview'
import { StageChannelSourceProvider } from '../hooks/useChannelSource'
import { useModifierHeld } from '../components/stage3d/useShiftHeld'
import { useSnapGrid, SNAP_STEPS_M, type SnapStep } from '../components/stage3d/edit/useSnapGrid'
import {
  useStageSelection,
  type SelectIntent,
  type SelectionRef,
} from '../components/stage3d/useStageSelection'
import { useStageNudge } from '../components/stage3d/edit/useStageNudge'
import { StageBulkPanel } from '../components/stage3d/edit/StageBulkPanel'
import { UnplacedTray } from '../components/stage3d/edit/UnplacedTray'
import { StageShortcutsPopover } from '../components/stage3d/edit/StageShortcutsPopover'
import {
  SCENERY_PRESETS,
  sceneryNoun,
  sceneryPreset,
  sceneryRequest,
  type SceneryPreset,
} from '../components/stage3d/edit/sceneryKinds'
import { useUnplacedPatches } from '../hooks/useUnplacedPatches'
import { resolveBulkTargets, unplaceTargets } from '../lib/stageBulkOps'
import { commitPlacements, type PlacementChange } from '../store/stagePlacement'
import { STAGE_PROJECTIONS } from '../lib/stageProjection'
import {
  StageEditorPanel,
  StageEditorPanelStub,
  type StageEditorTarget,
} from '../components/stage3d/StageEditorPanel'
import { StageEditorPickerPanel } from '../components/stage3d/StageEditorPickerPanel'
import { StageFixtureControlPanel } from '../components/stage3d/StageFixtureControlPanel'
import { StageAimPanel, isAimable } from '../components/stage3d/StageAimControls'
import { useFixtureLookup } from '../hooks/useFixtureLookup'
import type { EditPatchFormHandle } from '../components/patches/EditPatchForm'
import type { EditStageRegionFormHandle } from '../components/stage/EditStageRegionForm'
import type { EditRiggingFormHandle } from '../components/rigging/EditRiggingForm'
import type { EditSceneElementFormHandle } from '../components/stage/EditSceneElementForm'
import type { FixturePatch } from '../api/patchApi'
import type { StageRegionDto } from '../api/stageRegionApi'
import type { RiggingDto } from '../api/riggingApi'
import type { StageElementDto } from '../api/stageElementApi'
import { useMediaQuery, SM_BREAKPOINT } from '../hooks/useMediaQuery'

const REGION_DEFAULT_SIZE_M = 2

/** What a click on the stage will place: a region, a rigging, or a piece of scenery (`+ Scenery`). */
type Placing = { kind: 'region' } | { kind: 'rigging' } | { kind: 'scenery'; preset: SceneryPreset }

function placingNoun(placing: Placing): string {
  return placing.kind === 'scenery' ? sceneryNoun(placing.preset) : placing.kind
}
// Fallback truss height when the project doesn't declare a stage height.
const FALLBACK_TRUSS_HEIGHT_M = 4.5

function findByUuid<T extends { uuid: string }>(list: T[] | undefined, uuid: string | null | undefined): T | null {
  if (uuid == null) return null
  return list?.find((x) => x.uuid === uuid) ?? null
}

function nextDefaultName(prefix: string, existing: { name: string }[] | undefined): string {
  // Pick (max trailing-number of "Prefix N" entries) + 1, or "Prefix 1" if none.
  const re = new RegExp(`^${prefix}\\s+(\\d+)$`)
  let max = 0
  for (const item of existing ?? []) {
    const m = re.exec(item.name)
    if (m) max = Math.max(max, Number(m[1]))
  }
  return `${prefix} ${max + 1}`
}

/** `Hall copy`, then `Hall copy 2`… — an element's name is unique in its project. */
function nextCopyName(name: string, existing: { name: string }[] | undefined): string {
  const taken = new Set((existing ?? []).map((e) => e.name))
  const first = `${name} copy`
  if (!taken.has(first)) return first
  for (let n = 2; ; n++) {
    const candidate = `${name} copy ${n}`
    if (!taken.has(candidate)) return candidate
  }
}

export function Stage() {
  const project = useViewedProject()
  const projectId = project?.id
  // This window's camera (`lib/stageViewpoint.ts`): per tab, announced to the Screens sheet, and
  // settable from another window. Plan, Front and Side are sections of the 3D scene, and editing on
  // one is the edit layer over it (stage-view plan D1, session 5).
  const viewpoint = useStageViewpoint()
  // Multi-object selection. `Selection` itself stays single-valued — a dozen
  // consumers read it structurally — so everything that wants one target gets
  // `sel.primary`, and only the bulk panel and the ops look at the full set.
  const sel = useStageSelection()
  const selection = sel.primary
  // Destructured because these are stable useCallbacks while `sel` itself is a
  // fresh literal each render — depending on the object would re-run every
  // selection-keyed effect on every selection change.
  const {
    clear: clearSelection,
    select: selectOne,
    reconcile: reconcileSelection,
  } = sel
  const [editMode, setEditMode] = useState(false)
  const [placing, setPlacing] = useState<Placing | null>(null)
  const [panelCollapsed, setPanelCollapsed] = useState(false)
  const [gizmoModeManual, setGizmoModeManual] = useState<GizmoMode>('translate')
  const { flags: viewFlags, setFlag: setViewFlag, setLabelMode } = useStageView()
  // Filled by the 3D canvas while it is mounted; the View menu's *Test recovery* calls it.
  const recoveryRef = useRef<StageRecovery | null>(null)
  // Filled by the 3D canvas while it is mounted; F and the picker's *Frame the selection* call it.
  const framingRef = useRef<StageFraming | null>(null)
  const deskSelection = useDeskSelection()
  const visSource = useVisSource()
  // Reads the same cached queries the Next GO source does, so it costs no extra request.
  const nextGoStatus = useNextGoStatus(visSource === 'nextGo')
  const isTabletOrLarger = useMediaQuery(SM_BREAKPOINT)
  // One snap preference for every camera — see useSnapGrid on why Shift
  // *disables* snapping rather than enabling it.
  const snap = useSnapGrid(editMode && isTabletOrLarger)

  const patchFormRef = useRef<EditPatchFormHandle>(null)
  const regionFormRef = useRef<EditStageRegionFormHandle>(null)
  const riggingFormRef = useRef<EditRiggingFormHandle>(null)
  const elementFormRef = useRef<EditSceneElementFormHandle>(null)

  // Pre-drag state per object, so a settle can tell a real move from a bare
  // click and a rejected write can be rolled back to where the drag started.
  const patchOrigin = useDragOrigin<PatchPlacementValues>()
  const regionOrigin = useDragOrigin<RegionPlacementValues>()
  const riggingOrigin = useDragOrigin<RiggingPlacementValues>()
  const elementOrigin = useDragOrigin<ElementPlacementValues>()

  const { data: projectData } = useProjectQuery(projectId ?? 0, { skip: projectId == null })
  const { data: patches } = usePatchListQuery(projectId ?? 0, { skip: projectId == null })
  // What the picker offers: every patch but infrastructure, which the Stage never shows — the same
  // cached query, filtered. The raw list stays for everything that resolves a key or checks a new
  // one against the whole patch.
  const { data: stagePatches } = useVisiblePatchListQuery(projectId ?? 0, { skip: projectId == null })
  const { data: regions } = useStageRegionListQuery(projectId ?? 0, { skip: projectId == null })
  const { data: riggings } = useRiggingListQuery(projectId ?? 0, { skip: projectId == null })
  // The scene document (session 2): saved views and seats for the picker, and the elements a seat
  // view's eye is read off.
  const { data: savedViews, isFetching: savedViewsFetching } = useStageViewpointListQuery(projectId ?? 0, {
    skip: projectId == null,
  })
  const { data: sceneElements, isFetching: sceneElementsFetching } = useStageElementListQuery(projectId ?? 0, {
    skip: projectId == null,
  })
  // What of the scene this window draws, and how many lights its surfaces take (session 3).
  const sceneLayers = useSceneLayers()
  const lightBudget = useLightBudget()

  // Which camera the viewpoint draws through, and — for a saved view — where it lands. A saved view
  // whose rows have not arrived yet takes the camera it last landed with (a reload), else Orbit.
  useEffect(() => {
    noteSavedViewpointCameras(savedViewCameras(savedViews ?? []))
  }, [savedViews])
  // A seat picked with *Sit in a seat…* and not saved (session 3): the same key, `seat:<uuid>:<id>`.
  const pickedSeat: SeatViewpointRef | null = parseSeatViewpointRef(viewpoint) != null
    ? (viewpoint as SeatViewpointRef)
    : null
  const savedRow = useMemo(
    () =>
      isStageCamera(viewpoint) || pickedSeat != null
        ? null
        : (savedViews ?? []).find((row) => row.uuid === viewpoint) ?? null,
    [viewpoint, pickedSeat, savedViews],
  )
  const landing = useMemo(
    () =>
      pickedSeat != null
        ? resolveSeatViewpoint(pickedSeat, sceneElements ?? [])
        : savedRow == null
          ? null
          : resolveSavedViewpoint(savedRow, sceneElements ?? []),
    [pickedSeat, savedRow, sceneElements],
  )
  const camera = isStageCamera(viewpoint)
    ? viewpoint
    : savedRow != null
      ? savedViewCamera(savedRow)
      : (cameraOfViewpoint(viewpoint) ?? 'orbit')
  const isOrtho = isOrthoCamera(camera)
  const caption = useMemo(
    () =>
      pickedSeat != null
        ? { name: seatViewpointName(pickedSeat), note: seatViewpointCaption(pickedSeat) }
        : savedRow != null
          ? { name: savedRow.name, note: savedViewCaption(savedRow) }
          : { name: STAGE_CAMERA_LABELS[camera], note: STAGE_CAMERA_NOTES[camera] },
    [pickedSeat, savedRow, camera],
  )
  // A saved view this project does not have — the window was on another project's, or the row was
  // deleted — is let go once the list has settled, back to the camera it was drawing through, so the
  // window neither names a view that resolves to nothing nor announces one. A picked seat whose
  // seating has gone, or no longer has the seat, is let go the same way once the elements settle.
  useEffect(() => {
    if (isStageCamera(viewpoint)) return
    if (pickedSeat != null) {
      if (sceneElements == null || sceneElementsFetching) return
      if (resolveSeatViewpoint(pickedSeat, sceneElements) == null) setStageViewpoint('eye')
      return
    }
    if (savedViews == null || savedViewsFetching) return
    if (savedViews.some((row) => row.uuid === viewpoint)) return
    setStageViewpoint(cameraOfViewpoint(viewpoint) ?? 'orbit')
  }, [savedViews, savedViewsFetching, viewpoint, pickedSeat, sceneElements, sceneElementsFetching])
  const landable = useCallback(
    (row: Parameters<typeof resolveSavedViewpoint>[0]) => resolveSavedViewpoint(row, sceneElements ?? []) != null,
    [sceneElements],
  )

  // *Save this view…*: the request is built when the sheet opens, from where the camera is then.
  const [saveRequest, setSaveRequest] = useState<Omit<CreateStageViewpointRequest, 'name'> | null>(null)
  const [saveOpen, setSaveOpen] = useState(false)

  const { unplaced } = useUnplacedPatches(projectId)
  // Patches armed in the tray, waiting for a click on the canvas to place them.
  const [armedKeys, setArmedKeys] = useState<ReadonlySet<string>>(() => new Set())

  const [updatePatch] = useUpdatePatchMutation()
  const [updateRegion] = useUpdateStageRegionMutation()
  const [updateRigging] = useUpdateRiggingMutation()
  const [createRegion] = useCreateStageRegionMutation()
  const [createRigging] = useCreateRiggingMutation()
  const [createElement] = useCreateStageElementMutation()
  const [updateElement] = useUpdateStageElementMutation()

  // Editing works in every view now — only the tablet+ width gate remains, since
  // the gizmos and side panels need the room.
  const showEditToggle = isTabletOrLarger
  const editingActive = editMode && isTabletOrLarger
  // Editing on a section: the edit layer over the canvas takes the pointer — marquee, snap, guides,
  // handles, the tray's placements — and draws in the section's metres (D1, session 5).
  const sectionEditing = editingActive && isOrtho
  const projection = isOrthoCamera(camera) ? STAGE_PROJECTIONS[camera] : STAGE_PROJECTIONS.plan

  // `?viewpoint=` and `?source=` — a Screens row's *Copy link* carries both — applied once on arrival
  // and stripped, so a reload keeps whatever the window has moved to since.
  const [searchParams, setSearchParams] = useSearchParams()
  useEffect(() => {
    const next = consumeLaunchStageOptions(searchParams)
    if (next != null) setSearchParams(next, { replace: true })
  }, [searchParams, setSearchParams])

  useEffect(() => {
    if (!editingActive) {
      clearSelection()
      setPlacing(null)
      // Drop any pre-drag snapshots too. `take()` only runs on the settle path, so
      // a gesture abandoned before settling (project switched mid-drag, say)
      // leaves its entry behind — and `remember()` won't overwrite it, so the
      // *next* drag of that object would diff and roll back against a baseline
      // from the abandoned one.
      patchOrigin.clear()
      regionOrigin.clear()
      riggingOrigin.clear()
      elementOrigin.clear()
    }
    // Keys off editingActive rather than [mode, editMode], so it now also clears
    // when the viewport drops below the tablet breakpoint — which is correct,
    // given the effect below turns editMode off in that case anyway.
    // Narrowed to the stable callbacks rather than the whole `sel` object, which
    // is a fresh literal each render and would re-run this on every selection
    // change.
  }, [editingActive, clearSelection, patchOrigin, regionOrigin, riggingOrigin, elementOrigin])

  useEffect(() => {
    if (!isTabletOrLarger && editMode) setEditMode(false)
  }, [isTabletOrLarger, editMode])

  // The tray is a section's (a click there lands exactly where it says): leaving the section, or
  // editing, puts back whatever was armed rather than leaving it armed out of sight.
  useEffect(() => {
    if (!sectionEditing) setArmedKeys((prev) => (prev.size === 0 ? prev : new Set()))
  }, [sectionEditing])

  // Drop selection entries whose object has gone. The lists refetch on every
  // WebSocket change — including other operators' deletes — so without this a
  // multi-selection would either strand a dead ref or have to be thrown away
  // wholesale on any change.
  useEffect(() => {
    if (patches == null && regions == null && riggings == null && sceneElements == null) return
    reconcileSelection((ref) => {
      switch (ref.kind) {
        case 'patch':
          return (patches ?? []).some((p) => p.key === ref.patchKey)
        case 'region':
          return (regions ?? []).some((r) => r.uuid === ref.uuid)
        case 'rigging':
          return (riggings ?? []).some((r) => r.uuid === ref.uuid)
        case 'element':
          return (sceneElements ?? []).some((e) => e.uuid === ref.uuid)
      }
    })
  }, [patches, regions, riggings, sceneElements, reconcileSelection])

  // — bulk operations ————————————————————————————————————————————————

  /** Patches in the current selection, in selection order. */
  const selectedPatches = useMemo(() => {
    const byKey = new Map((patches ?? []).map((p) => [p.key, p]))
    const out: FixturePatch[] = []
    for (const ref of sel.refs) {
      if (ref.kind !== 'patch') continue
      const patch = byKey.get(ref.patchKey)
      if (patch) out.push(patch)
    }
    return out
  }, [patches, sel.refs])
  const { fixtureByKey } = useFixtureLookup()
  // A multi-selection in view mode has no fixture panel; the moving heads in it can still be aimed
  // together. Aiming writes the programmer, so only on the live project.
  const aimableKeys = useMemo(
    () =>
      project?.isCurrent
        ? selectedPatches.filter((p) => isAimable(fixtureByKey.get(p.key))).map((p) => p.key)
        : [],
    [project?.isCurrent, selectedPatches, fixtureByKey],
  )

  const applyBulk = useCallback(
    (changes: PlacementChange[], label: string, warnings?: string[]) => {
      if (projectId == null) return
      for (const warning of warnings ?? []) toast.warning(warning)
      if (changes.length === 0) return
      void commitPlacements({ projectId, changes, label })
    },
    [projectId],
  )

  // Arrow keys nudge the selection by the grid step (Shift for ten times that).
  useStageNudge({
    enabled: editingActive && selectedPatches.length > 0,
    projection,
    stepM: snap.step,
    targets: () => resolveBulkTargets(selectedPatches, riggings ?? []),
    commit: applyBulk,
  })

  // Delete/Backspace. For a patch this **unplaces** rather than destroying: a
  // patch is real DMX with channel assignments, group membership and cue
  // references, so removing it because someone pressed Backspace on a stage plot
  // would be catastrophic and irreversible. Clearing its position loses nothing —
  // it reappears in the tray.
  const selectedPatchesRef = useRef(selectedPatches)
  selectedPatchesRef.current = selectedPatches
  useEffect(() => {
    if (!editingActive || projectId == null) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Delete' && e.key !== 'Backspace') return
      if (isEditableTarget(document.activeElement)) return
      const ids = selectedPatchesRef.current.map((p) => p.id)
      if (ids.length === 0) return
      e.preventDefault()
      applyBulk(unplaceTargets(ids), 'Remove from stage')
      clearSelection()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
    // Selection is read through a ref, so this binds once per enable rather than
    // on every selection change.
  }, [editingActive, projectId, applyBulk, clearSelection])

  // Escape cancels placement mode.
  useEffect(() => {
    if (!placing) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setPlacing(null)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [placing])

  // *Frame the selection*: the Stage's own selection, else the desk's — a chip pressed in Positions,
  // the busk band or the programmer — so F frames what the operator just picked wherever they
  // picked it. Read through a ref by the key listener, which binds once.
  const framePointsRef = useRef<() => ReturnType<typeof stageSelectionPoints>>(() => [])
  framePointsRef.current = () => {
    const own = stageSelectionPoints(sel.refs, patches ?? [], riggings ?? [], regions ?? [], sceneElements ?? [])
    return own.length > 0 ? own : deskSelectionPoints(deskSelection, patches ?? [], riggings ?? [])
  }
  const canFrame = sel.count > 0 || deskSelection.length > 0
  const frameSelection = useCallback(() => {
    const points = framePointsRef.current()
    if (points.length > 0) framingRef.current?.frame(points)
  }, [])

  const openSaveSheet = useCallback(() => {
    if (camera === 'orbit') {
      const pose = readOrbitPose() ?? defaultOrbitPose({
        width: projectData?.stageWidthM ?? 10,
        depth: projectData?.stageDepthM ?? 8,
        height: projectData?.stageHeightM ?? 6,
      })
      setSaveRequest(viewpointFromCamera('', { kind: 'orbit', pose }))
    } else if (camera === 'eye') {
      const pose = readEyePose()
      if (pose == null) return
      // Sitting in a seat — saved or only picked — saves a `SEAT` view of that seat.
      const picked = parseSeatViewpointRef(pickedSeat)
      const seat = picked != null
        ? { kind: 'SEAT' as const, seatElementUuid: picked.elementUuid, seatId: picked.seatId }
        : savedRow
      setSaveRequest(viewpointFromCamera('', { kind: 'eye', pose, seat }))
    } else {
      return
    }
    setSaveOpen(true)
  }, [camera, savedRow, pickedSeat, projectData])

  // *Sit in a seat…* (session 3): armed, the seats take the pointer and a click sits in the one
  // under it — the eye lands at its seated eye, as a saved seat view's does. Only on the 3D scene,
  // and only where there are seats to sit in; arming it shows the seating if this window had hidden it.
  const [sitting, setSitting] = useState(false)
  // A seating the scene would draw — not hidden, not switched off by its `visible` state, and with
  // params that make seats — or the pick would arm over nothing to click.
  const hasSeats = useMemo(
    () => (sceneElements ?? []).some((e) => e.kind === 'SEATING' && isElementShown(e) && seatingParams(e) != null),
    [sceneElements],
  )
  // Not while editing on a section: the edit layer holds the pointer there, and a pick would hand
  // it back to the scene mid-edit — the route and the canvas must agree on which one has it.
  const canSit = hasSeats && !sectionEditing
  const startSitting = useCallback(() => {
    if (!canSit) return
    if (!sceneLayers.seating) setSceneLayer('seating', true)
    setSitting(true)
  }, [canSit, sceneLayers.seating])
  useEffect(() => {
    if (!canSit) setSitting(false)
  }, [canSit])
  const seatPicking = useMemo<SeatPicking | null>(
    () =>
      sitting
        ? {
            onPick: (elementUuid, seatId) => {
              setSitting(false)
              setStageViewpoint(seatViewpointRef(elementUuid, seatId))
            },
          }
        : null,
    [sitting],
  )

  // O goes back to the orbit camera, F frames the selection and S sits in a seat (`Stage.dc.html`'s
  // picker); Escape stands up from the pick. Bare keys only — ⇧F is full screen
  // (`useWindowsBridge`) — and never from a field.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      // An Escape a menu or sheet already took (Radix prevents the ones it closes on) is not this
      // one's: closing the View menu must not stand the operator up from a pick they armed.
      if (e.defaultPrevented) return
      if (e.key === 'Escape' && sitting) {
        e.preventDefault()
        setSitting(false)
        return
      }
      if (e.metaKey || e.ctrlKey || e.altKey || e.shiftKey) return
      if (isEditableTarget(document.activeElement)) return
      const key = e.key.toLowerCase()
      if (key === 'o') {
        e.preventDefault()
        setStageViewpoint('orbit')
      } else if (key === 'f') {
        e.preventDefault()
        frameSelection()
      } else if (key === 's' && canSit) {
        e.preventDefault()
        if (sitting) setSitting(false)
        else startSitting()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [frameSelection, sitting, canSit, startSitting])

  // Hold Alt/Option to flip the fixture gizmo to the *other* mode while held.
  const { held: altHeld } = useModifierHeld('altKey', editingActive)
  const flippedGizmoMode: GizmoMode = gizmoModeManual === 'translate' ? 'rotate' : 'translate'
  const gizmoMode: GizmoMode = altHeld ? flippedGizmoMode : gizmoModeManual

  // ⌘D / Ctrl+D duplicates the selected region, rigging or piece of scenery, offset by 1m on X.
  // Live data is read through refs so the listener doesn't re-bind on every
  // optimistic store update (which would mean add/remove per drag frame).
  const selectionRef = useRef(selection)
  selectionRef.current = selection
  const regionsRef = useRef(regions)
  regionsRef.current = regions
  const riggingsRef = useRef(riggings)
  riggingsRef.current = riggings
  const elementsRef = useRef(sceneElements)
  elementsRef.current = sceneElements
  useEffect(() => {
    if (!editingActive || projectId == null) return
    const onKey = async (e: KeyboardEvent) => {
      const isDuplicateShortcut = e.key.toLowerCase() === 'd' && (e.metaKey || e.ctrlKey)
      if (!isDuplicateShortcut) return
      const target = selectionRef.current
      if (target?.kind !== 'region' && target?.kind !== 'rigging' && target?.kind !== 'element') return
      // Don't hijack when the user is typing into a form field.
      if (isEditableTarget(document.activeElement)) return
      e.preventDefault()
      try {
        if (target.kind === 'region') {
          const source = regionsRef.current?.find((r) => r.uuid === target.uuid)
          if (!source) return
          const created = await createRegion({
            projectId,
            name: `${source.name} copy`,
            centerX: (source.centerX ?? 0) + 1,
            centerY: source.centerY,
            centerZ: source.centerZ,
            widthM: source.widthM,
            depthM: source.depthM,
            heightM: source.heightM,
            yawDeg: source.yawDeg,
          }).unwrap()
          selectOne({ kind: 'region', uuid: created.uuid })
        } else if (target.kind === 'element') {
          const source = elementsRef.current?.find((e) => e.uuid === target.uuid)
          if (!source) return
          const created = await createElement({
            projectId,
            name: nextCopyName(source.name, elementsRef.current),
            kind: source.kind,
            layer: source.layer,
            positionX: source.positionX + 1,
            positionY: source.positionY,
            positionZ: source.positionZ,
            yawDeg: source.yawDeg,
            widthM: source.widthM,
            depthM: source.depthM,
            heightM: source.heightM,
            finishColour: source.finishColour,
            finishPattern: source.finishPattern,
            emissive: source.emissive,
            params: source.params,
            hidden: source.hidden,
          }).unwrap()
          selectOne({ kind: 'element', uuid: created.uuid })
        } else {
          const source = riggingsRef.current?.find((r) => r.uuid === target.uuid)
          if (!source) return
          const created = await createRigging({
            projectId,
            name: `${source.name} copy`,
            kind: source.kind,
            positionX: (source.positionX ?? 0) + 1,
            positionY: source.positionY,
            positionZ: source.positionZ,
            yawDeg: source.yawDeg,
            pitchDeg: source.pitchDeg,
            rollDeg: source.rollDeg,
            lengthM: source.lengthM,
          }).unwrap()
          selectOne({ kind: 'rigging', uuid: created.uuid })
        }
      } catch (err) {
        toast.error(`Failed to duplicate: ${formatError(err)}`)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [editingActive, projectId, createRegion, createRigging, createElement, selectOne])

  // — drag persistence ————————————————————————————————————————————————
  //
  // Shared by both views, so they're lifted out of the JSX rather than written
  // inline. All three follow the same shape:
  //
  //   1. snapshot the object's pre-drag state (idempotent per gesture)
  //   2. mirror the live value into the open edit form
  //   3. write the cache every frame so the object follows the cursor
  //   4. on settle, diff against the SNAPSHOT and PUT, rolling the cache back
  //      to the snapshot if the server rejects it
  //
  // Step 4 diffs against the snapshot rather than the DTO argument because the
  // per-frame writes have already moved the DTO — see the note in
  // store/stagePlacement.ts. Diffing against the live value made rotate-mode
  // drags compare the final orientation against itself, so they never persisted.

  const handlePatchPlacementChange = useCallback(
    (patch: FixturePatch, next: PatchPlacementUpdate, settled: boolean) => {
      if (projectId == null) return
      const rotateUpdate = next.baseYawDeg !== undefined || next.basePitchDeg !== undefined
      const nextBaseYaw = rotateUpdate ? next.baseYawDeg ?? null : patch.baseYawDeg
      const nextBasePitch = rotateUpdate ? next.basePitchDeg ?? null : patch.basePitchDeg

      const snapshot: PatchPlacementValues = {
        riggingUuid: patch.riggingUuid,
        stageX: patch.stageX,
        stageY: patch.stageY,
        stageZ: patch.stageZ,
        baseYawDeg: patch.baseYawDeg,
        basePitchDeg: patch.basePitchDeg,
      }
      // Before the early return: a 3D translate drag reports every frame without
      // writing anything, so this is its only chance to record where the gesture
      // began.
      patchOrigin.remember(patch.id, snapshot)

      patchFormRef.current?.setPlacement({
        riggingUuid: next.riggingUuid,
        stageX: next.stageX,
        stageY: next.stageY,
        stageZ: next.stageZ,
        baseYawDeg: nextBaseYaw,
        basePitchDeg: nextBasePitch,
      })

      const values: PatchPlacementValues = {
        riggingUuid: next.riggingUuid,
        stageX: next.stageX,
        stageY: next.stageY,
        stageZ: next.stageZ,
        ...(rotateUpdate ? { baseYawDeg: nextBaseYaw, basePitchDeg: nextBasePitch } : {}),
      }

      if (!settled) {
        if (
          rotateUpdate &&
          (nextBaseYaw !== patch.baseYawDeg || nextBasePitch !== patch.basePitchDeg)
        ) {
          // Rotate-mode writes every frame so the head follows the gizmo live.
          writePatchPlacement(projectId, patch.id, {
            baseYawDeg: nextBaseYaw,
            basePitchDeg: nextBasePitch,
          })
        } else if (!rotateUpdate && sectionEditing) {
          // A drag on a section MUST write per frame: the fixture is drawn from
          // the RTK cache, so without this the body and its label sit frozen at
          // the pre-drag position for the whole gesture and teleport on
          // pointerup — while the guides and the truss drop-target highlight
          // track the pointer, so the feedback actively contradicts itself.
          //
          // The orbit camera's gizmo is deliberately excluded: Stage3D mirrors
          // its drag proxy onto the body imperatively, so a store write per frame
          // would re-render the whole scene to move the mesh somewhere it
          // already is.
          writePatchPlacement(projectId, patch.id, values)
        }
        return
      }

      const origin = patchOrigin.take(patch.id) ?? snapshot

      // TransformControls fires `dragging-changed: false` on every mouseup,
      // including click-without-drag — don't PUT (and invalidate) for nothing.
      if (placementUnchanged(values, origin)) {
        writePatchPlacement(projectId, patch.id, origin)
        return
      }

      writePatchPlacement(projectId, patch.id, values)
      // updatePatch isn't in SILENT_ENDPOINTS, so the error middleware toasts;
      // this only needs to undo the optimistic write.
      updatePatch({ projectId, patchId: patch.id, ...values })
        .unwrap()
        .catch(() => writePatchPlacement(projectId, patch.id, origin))
    },
    [projectId, updatePatch, patchOrigin, sectionEditing],
  )

  const handleRegionPositionChange = useCallback(
    (region: StageRegionDto, next: RegionPositionUpdate, settled: boolean) => {
      if (projectId == null) return
      const snapshot: RegionPlacementValues = {
        centerX: region.centerX,
        centerY: region.centerY,
        centerZ: region.centerZ,
        yawDeg: region.yawDeg,
        widthM: region.widthM,
        depthM: region.depthM,
        heightM: region.heightM,
      }
      regionOrigin.remember(region.id, snapshot)

      const values: RegionPlacementValues = {
        centerX: next.centerX,
        centerY: next.centerY,
        centerZ: next.centerZ,
        yawDeg: next.yawDeg,
        ...(next.widthM !== undefined ? { widthM: next.widthM } : {}),
        ...(next.depthM !== undefined ? { depthM: next.depthM } : {}),
        ...(next.heightM !== undefined ? { heightM: next.heightM } : {}),
      }

      // `values` rather than a fixed-shape object: the form's setPosition spreads
      // whatever it's given over its state, so a key present with an `undefined`
      // value *erases* that field. A body move legitimately reports no size, and
      // passing `{ widthM: undefined, … }` would blank the Width/Depth/Height
      // inputs while the user was only sliding the box around.
      regionFormRef.current?.setPosition(values)

      // Every frame, so the box and its handles follow the cursor live.
      writeRegionPlacement(projectId, region.id, values)
      if (!settled) return

      const origin = regionOrigin.take(region.id) ?? snapshot
      if (placementUnchanged(values, origin)) {
        writeRegionPlacement(projectId, region.id, origin)
        return
      }

      // updateStageRegion is in SILENT_ENDPOINTS (the edit form reports its own
      // failures), so this call site has to raise its own.
      updateRegion({ projectId, regionId: region.id, ...values })
        .unwrap()
        .catch((err) => {
          writeRegionPlacement(projectId, region.id, origin)
          toast.error(`Failed to move ${region.name}: ${formatError(err)}`)
        })
    },
    [projectId, updateRegion, regionOrigin],
  )

  const handleRiggingPositionChange = useCallback(
    (rig: RiggingDto, next: RiggingPositionUpdate, settled: boolean) => {
      if (projectId == null) return
      const snapshot: RiggingPlacementValues = {
        positionX: rig.positionX,
        positionY: rig.positionY,
        positionZ: rig.positionZ,
        yawDeg: rig.yawDeg,
        pitchDeg: rig.pitchDeg,
        rollDeg: rig.rollDeg,
        lengthM: rig.lengthM,
      }
      riggingOrigin.remember(rig.id, snapshot)

      const values: RiggingPlacementValues = {
        positionX: next.positionX,
        positionY: next.positionY,
        positionZ: next.positionZ,
        yawDeg: next.yawDeg,
        pitchDeg: next.pitchDeg,
        rollDeg: next.rollDeg,
        ...(next.lengthM !== undefined ? { lengthM: next.lengthM } : {}),
      }

      // See the note on the region path: passing an always-present `lengthM: undefined`
      // would blank the Length field during a plain move.
      riggingFormRef.current?.setPosition(values)

      // Every frame, so the bar and its endpoint handles follow the cursor live.
      writeRiggingPlacement(projectId, rig.id, values)
      if (!settled) return

      const origin = riggingOrigin.take(rig.id) ?? snapshot
      if (placementUnchanged(values, origin)) {
        writeRiggingPlacement(projectId, rig.id, origin)
        return
      }

      // updateRigging is in SILENT_ENDPOINTS (the edit form reports its own
      // failures), so this call site has to raise its own.
      updateRigging({ projectId, riggingId: rig.id, ...values })
        .unwrap()
        .catch((err) => {
          writeRiggingPlacement(projectId, rig.id, origin)
          toast.error(`Failed to move ${rig.name}: ${formatError(err)}`)
        })
    },
    [projectId, updateRigging, riggingOrigin],
  )

  const handleElementPositionChange = useCallback(
    (element: StageElementDto, next: ElementPositionUpdate, settled: boolean) => {
      if (projectId == null) return
      const snapshot: ElementPlacementValues = {
        positionX: element.positionX,
        positionY: element.positionY,
        positionZ: element.positionZ,
      }
      elementOrigin.remember(element.id, snapshot)
      const values: ElementPlacementValues = { ...next }
      elementFormRef.current?.setPosition(values)
      // Every frame, so the piece follows the pointer: the scene builds it from the cache.
      writeElementPlacement(projectId, element.id, values)
      if (!settled) return

      const origin = elementOrigin.take(element.id) ?? snapshot
      if (placementUnchanged(values, origin)) {
        writeElementPlacement(projectId, element.id, origin)
        return
      }
      // updateStageElement is in SILENT_ENDPOINTS (the element form draws its refusals beside
      // its fields), so this call site has to raise its own.
      updateElement({ projectId, elementId: element.id, ...values })
        .unwrap()
        .catch((err) => {
          writeElementPlacement(projectId, element.id, origin)
          toast.error(`Failed to move ${element.name}: ${formatError(err)}`)
        })
    },
    [projectId, updateElement, elementOrigin],
  )

  if (projectId == null) {
    return (
      <Card className="m-4 p-4 flex items-center justify-center">
        <Loader2 className="size-6 animate-spin" />
      </Card>
    )
  }

  // Patch key may point at a stale id during list refetches — drop the target
  // until the new row arrives so the form doesn't render against missing data.
  const panelTarget = resolvePanelTarget(selection, patches, regions, riggings, sceneElements)

  // Multi-selection takes the rail: the single-target edit form has no meaning
  // for several objects at once, and mixed-value fields aren't what makes rigging
  // slow (see StageBulkPanel).
  const showBulkPanel = editingActive && sel.count > 1
  const showPanel = editingActive && !showBulkPanel && panelTarget != null && !panelCollapsed
  const showPanelStub = editingActive && !showBulkPanel && panelTarget != null && panelCollapsed
  const showPicker =
    editingActive && !showBulkPanel && panelTarget == null && placing == null
  // View-mode (non-editing) fixture control card. Shares the selection state, so
  // it works in both 3D and the 2D overview; gated to tablet+ like the edit panels.
  // Requires exactly one selected object — live controls for "5 fixtures" would be
  // ambiguous about which one they were driving.
  const showControlPanel =
    !editingActive && sel.count === 1 && selection?.kind === 'patch' && isTabletOrLarger
  const showAimPanel = !editingActive && sel.count > 1 && isTabletOrLarger && aimableKeys.length > 0

  const handleSelectionChange = (s: Selection, intent: SelectIntent = 'replace') => {
    selectOne(s, intent)
    if (!editingActive || s == null) return
    setPanelCollapsed(false)
  }

  const togglePlacing = (next: Placing) => {
    setPlacing((prev) => (prev != null && placingNoun(prev) === placingNoun(next) ? null : next))
    clearSelection()
  }

  // Hang truss 1m below stage top (clamped to floor for very short stages),
  // or a typical truss height if the project doesn't declare one.
  const stageH = projectData?.stageHeightM
  const trussZ = stageH != null ? Math.max(0, stageH - 1) : FALLBACK_TRUSS_HEIGHT_M

  // Match the placement-click raycast plane to the height the new object lives
  // at, so the user sees the new object exactly where they clicked.
  const placementZ =
    placing?.kind === 'rigging' ? trussZ : placing?.kind === 'scenery' ? sceneryPreset(placing.preset).placementZ : 0

  // Fallback for whichever axis the active view can't learn from a click. Each
  // view fills its own out-of-plane coordinate from this, so the point arriving
  // at `handlePlacementClick` is always complete:
  //   plan       → learns X and Y, takes Z from here
  //   front      → learns X and Z, takes Y from here (mid-stage)
  //   side       → learns Y and Z, takes X from here (centre line)
  // What the canvas's hint says a click will place: the armed create, or the tray's fixtures.
  const placingLabel =
    placing != null
      ? placingNoun(placing)
      : sectionEditing && armedKeys.size > 0
        ? armedKeys.size === 1
          ? 'the fixture'
          : `${armedKeys.size} fixtures`
        : null

  const placementDefault = {
    x: 0,
    y: (projectData?.stageDepthM ?? DEFAULT_STAGE_DIMS.depthM) / 2,
    z: placementZ,
  }

  /**
   * Places every tray-armed fixture at the clicked point.
   *
   * More than one armed fixture fans out along X at the grid step rather than
   * stacking them all on the same coordinate, so a multi-select drop produces a
   * usable row that align/distribute can then tidy.
   */
  const placeArmedFixtures = (p: PlacementPoint) => {
    if (projectId == null || armedKeys.size === 0) return
    const armed = unplaced.filter((patch) => armedKeys.has(patch.key))
    if (armed.length === 0) return
    const spread = snap.step
    const startX = p.x - ((armed.length - 1) * spread) / 2
    const changes: PlacementChange[] = armed.map((patch, i) => ({
      patchId: patch.id,
      riggingUuid: null,
      stageX: startX + i * spread,
      stageY: p.y,
      stageZ: p.z,
    }))
    setArmedKeys(new Set())
    applyBulk(changes, armed.length === 1 ? 'Place fixture' : `Place ${armed.length} fixtures`)
  }

  const handlePlacementClick = async (p: PlacementPoint) => {
    // Tray placement takes precedence: if the user armed fixtures, a canvas click
    // means "put them here", not "create a region".
    if (armedKeys.size > 0) {
      placeArmedFixtures(p)
      return
    }
    if (placing == null || projectId == null) return
    // Clear placing eagerly so a quick second click during the in-flight create
    // doesn't fire a duplicate placement.
    const armed = placing
    setPlacing(null)
    try {
      if (armed.kind === 'scenery') {
        const created = await createElement({
          projectId,
          ...sceneryRequest(armed.preset, p, nextDefaultName(sceneryPreset(armed.preset).label, sceneElements)),
        }).unwrap()
        selectOne({ kind: 'element', uuid: created.uuid })
      } else if (armed.kind === 'region') {
        const created = await createRegion({
          projectId,
          name: nextDefaultName('Region', regions),
          centerX: p.x,
          centerY: p.y,
          // centerZ is a region's top surface and the box hangs below it, so the
          // new box's floor goes at the clicked height — a click on the deck
          // stands it on the deck rather than sinking it into a pit.
          centerZ: p.z + REGION_DEFAULT_SIZE_M,
          widthM: REGION_DEFAULT_SIZE_M,
          depthM: REGION_DEFAULT_SIZE_M,
          heightM: REGION_DEFAULT_SIZE_M,
          yawDeg: 0,
        }).unwrap()
        selectOne({ kind: 'region', uuid: created.uuid })
      } else {
        const created = await createRigging({
          projectId,
          name: nextDefaultName('Rigging', riggings),
          kind: 'TRUSS',
          positionX: p.x,
          positionY: p.y,
          positionZ: p.z,
          yawDeg: 0,
          pitchDeg: 0,
          rollDeg: 0,
          lengthM: DEFAULT_RIGGING_LENGTH_M,
        }).unwrap()
        selectOne({ kind: 'rigging', uuid: created.uuid })
      }
    } catch (err) {
      toast.error(`Failed to place: ${formatError(err)}`)
    }
  }

  // Form signalled it's done (Save/Cancel/Delete). Clear selection too, not
  // just the panel, so the highlight clears in 3D.
  const dismissPanel = () => {
    clearSelection()
  }


  return (
    <TooltipProvider>
      <div className="flex flex-col h-full min-h-0">
        <header className="flex flex-wrap items-center gap-2 border-b px-4 py-2">
          <h1 className="text-sm font-semibold">Stage</h1>
          <StageViewpointPicker
            viewpoint={viewpoint}
            camera={camera}
            saved={savedViews ?? []}
            landable={landable}
            onPick={setStageViewpoint}
            onFrame={frameSelection}
            canFrame={canFrame}
            onSave={openSaveSheet}
            canSave={!isOrtho && projectId != null}
            onSit={startSitting}
            canSit={canSit}
            sitting={sitting}
          />
          {/* The camera — Orbit, Eye and the three sections of the one scene (D1). */}
          <ToggleGroup
            type="single"
            size="sm"
            value={camera}
            aria-label="Camera"
            onValueChange={(v) => {
              if (isStageCamera(v)) setStageViewpoint(v)
            }}
          >
            {STAGE_CAMERAS.map((v) => (
              <ToggleGroupItem key={v} value={v} className="px-2">
                {STAGE_CAMERA_LABELS[v]}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
          <div className="flex-1" />
          {editingActive && (
            <>
              <Button
                size="sm"
                variant={placing?.kind === 'region' ? 'default' : 'outline'}
                onClick={() => togglePlacing({ kind: 'region' })}
                aria-pressed={placing?.kind === 'region'}
              >
                <Plus className="size-3.5 mr-1" />
                Region
              </Button>
              <Button
                size="sm"
                variant={placing?.kind === 'rigging' ? 'default' : 'outline'}
                onClick={() => togglePlacing({ kind: 'rigging' })}
                aria-pressed={placing?.kind === 'rigging'}
              >
                <Plus className="size-3.5 mr-1" />
                Rigging
              </Button>
              {/* `Edit.dc.html` §1: the seven kinds, tabs and a flown piece, each armed to be placed
                  with a click on the stage like a region, then edited in the element form. */}
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button
                    size="sm"
                    variant={placing?.kind === 'scenery' ? 'default' : 'outline'}
                    aria-pressed={placing?.kind === 'scenery'}
                  >
                    <Plus className="size-3.5 mr-1" />
                    {placing?.kind === 'scenery' ? sceneryPreset(placing.preset).label : 'Scenery'}
                    <ChevronDown className="size-3.5 ml-1 opacity-70" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="start" className="w-60">
                  <DropdownMenuLabel className="text-xs text-muted-foreground">Add to the scene</DropdownMenuLabel>
                  {SCENERY_PRESETS.map((preset) => (
                    <DropdownMenuItem
                      key={preset.id}
                      onSelect={() => togglePlacing({ kind: 'scenery', preset: preset.id })}
                      className="flex items-baseline justify-between gap-3"
                    >
                      <span>{preset.label}</span>
                      <span className="text-xs text-muted-foreground">{preset.hint}</span>
                    </DropdownMenuItem>
                  ))}
                </DropdownMenuContent>
              </DropdownMenu>
            </>
          )}
          {/* The orbit and eye cameras only: this drives drei's TransformControls,
              which a section does not use — a fixture drag on a section is always a move. */}
          {editingActive && !sectionEditing && selection?.kind === 'patch' && (
            <Tooltip>
              <TooltipTrigger asChild>
                <ToggleGroup
                  type="single"
                  size="sm"
                  value={gizmoMode}
                  onValueChange={(v) => {
                    if (v === 'translate' || v === 'rotate') setGizmoModeManual(v)
                  }}
                >
                  <ToggleGroupItem value="translate" aria-label="Move fixture">
                    <Move className="size-3.5" />
                  </ToggleGroupItem>
                  <ToggleGroupItem value="rotate" aria-label="Rotate fixture">
                    <RotateCw className="size-3.5" />
                  </ToggleGroupItem>
                </ToggleGroup>
              </TooltipTrigger>
              <TooltipContent>Hold ⌥ Option to flip temporarily</TooltipContent>
            </Tooltip>
          )}
          {editingActive && (
            <Tooltip>
              <TooltipTrigger asChild>
                <div className="flex items-center gap-1">
                  <Button
                    size="sm"
                    variant={snap.snapOn ? 'default' : 'outline'}
                    onClick={() => snap.setSnapOn(!snap.snapOn)}
                    aria-pressed={snap.snapOn}
                  >
                    <Grid2x2 className="size-3.5 mr-1" />
                    Snap
                  </Button>
                  {snap.snapOn && (
                    <Select
                      value={String(snap.step)}
                      onValueChange={(v) => snap.setStep(Number(v) as SnapStep)}
                    >
                      <SelectTrigger size="sm" className="w-[5.5rem]" aria-label="Grid step">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {SNAP_STEPS_M.map((s) => (
                          <SelectItem key={s} value={String(s)}>
                            {s} m
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  )}
                </div>
              </TooltipTrigger>
              <TooltipContent>Hold ⇧ Shift to place off-grid</TooltipContent>
            </Tooltip>
          )}
          {editingActive && <StageShortcutsPopover />}
          <StageViewMenu
            flags={viewFlags}
            setFlag={setViewFlag}
            setLabelMode={setLabelMode}
            onTestRecovery={() => recoveryRef.current?.testContextLoss()}
            visSource={visSource}
            setVisSource={setVisSource}
            sourceStatus={{ nextGo: nextGoStatus }}
            layers={sceneLayers}
            setLayer={setSceneLayer}
            lightBudget={lightBudget}
            setLightBudget={setLightBudget}
          />
          {showEditToggle && (
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  size="sm"
                  variant={editMode ? 'default' : 'outline'}
                  onClick={() => setEditMode((v) => !v)}
                  aria-pressed={editMode}
                >
                  <Pencil className="size-3.5 mr-1" />
                  Edit
                </Button>
              </TooltipTrigger>
              <TooltipContent>
                {editMode ? 'Click an object to edit' : 'Enable visual editing'}
              </TooltipContent>
            </Tooltip>
          )}
        </header>
        <main className="flex flex-1 min-h-0 overflow-hidden">
          <div className="flex flex-1 min-w-0 flex-col">
            {/* Only the canvas goes inside the vis-source provider. The docked
                StageFixtureControlPanel below is a live editing surface and has to keep
                reading real output whatever the selector is previewing. */}
            <StageChannelSourceProvider>
              <Stage3D
                projectId={projectId}
                camera={camera}
                landing={landing}
                persistCamera
                caption={caption}
                showScene
                layers={sceneLayers}
                lightBudget={lightBudget}
                seatPicking={seatPicking}
                framingRef={framingRef}
                editMode={editingActive}
                selection={selection}
                selectedKeys={sel.selectedKeys}
                // A click on the stage also lands tray-armed fixtures, so the canvas must treat it as
                // a placement even when no create is armed.
                placing={placingLabel}
                placementZ={placementZ}
                placementDefault={placementDefault}
                view={viewFlags}
                gizmoMode={gizmoMode}
                snap={snap}
                hidePatchSelectionInfo={showControlPanel}
                onSelectionChange={handleSelectionChange}
                onMarqueeSelect={(refs, intent) => sel.selectMany(refs, intent)}
                onPlacementClick={handlePlacementClick}
                onPatchPlacementChange={handlePatchPlacementChange}
                onRegionPositionChange={handleRegionPositionChange}
                onRiggingPositionChange={handleRiggingPositionChange}
                onElementPositionChange={handleElementPositionChange}
                recoveryRef={recoveryRef}
              />
            </StageChannelSourceProvider>
            {/* On a section, where a click lands exactly where it says: the tray arms fixtures and
                the next click on the stage places them. */}
            {sectionEditing && (
              <UnplacedTray
                unplaced={unplaced}
                armedKeys={armedKeys}
                canHang={(riggings ?? []).length > 0}
                onToggle={(patch, extend) =>
                  setArmedKeys((prev) => {
                    const next = new Set(extend ? prev : [])
                    if (prev.has(patch.key) && (extend || prev.size === 1)) next.delete(patch.key)
                    else next.add(patch.key)
                    return next
                  })
                }
                onSelectAll={() => setArmedKeys(new Set(unplaced.map((p) => p.key)))}
                onHangAll={() => {
                  // Route the whole tray through the bulk panel's truss picker by
                  // selecting them — they have no position, so the panel shows the
                  // "place them first" note and only the hang action applies.
                  sel.selectMany(
                    unplaced.map((p): SelectionRef => ({ kind: 'patch', patchKey: p.key })),
                  )
                  setArmedKeys(new Set())
                }}
              />
            )}
          </div>
          {showBulkPanel && (
            <StageBulkPanel
              patches={selectedPatches}
              riggings={riggings ?? []}
              projection={projection}
              regionCount={sel.refs.filter((r) => r.kind === 'region').length}
              riggingCount={sel.refs.filter((r) => r.kind === 'rigging').length}
              elementCount={sel.refs.filter((r) => r.kind === 'element').length}
              onApply={applyBulk}
              onDismiss={() => clearSelection()}
            />
          )}
          {showPanel && panelTarget && (
            <StageEditorPanel
              target={panelTarget}
              projectId={projectId}
              existingPatches={patches ?? []}
              onCollapse={() => setPanelCollapsed(true)}
              onDismiss={dismissPanel}
              patchRef={patchFormRef}
              regionRef={regionFormRef}
              riggingRef={riggingFormRef}
              elementRef={elementFormRef}
            />
          )}
          {showPanelStub && <StageEditorPanelStub onExpand={() => setPanelCollapsed(false)} />}
          {showPicker && (
            <StageEditorPickerPanel
              patches={stagePatches ?? []}
              regions={regions ?? []}
              riggings={riggings ?? []}
              elements={sceneElements ?? []}
              onSelect={handleSelectionChange}
            />
          )}
          {showControlPanel && selection?.kind === 'patch' && (
            <StageFixtureControlPanel
              patchKey={selection.patchKey}
              projectId={projectId}
              canAim={project?.isCurrent ?? false}
              onClose={() => clearSelection()}
            />
          )}
          {showAimPanel && (
            <StageAimPanel
              projectId={projectId}
              fixtureKeys={aimableKeys}
              onClose={() => clearSelection()}
            />
          )}
        </main>
      </div>
      {projectId != null && (
        <SaveViewpointSheet
          open={saveOpen}
          onOpenChange={setSaveOpen}
          projectId={projectId}
          request={saveRequest}
          seatingName={
            (sceneElements ?? []).find((e) => e.uuid === saveRequest?.seatElementUuid)?.name ?? null
          }
          onSaved={(row) => {
            // The camera already stands where the view was saved from: move onto it and mark it
            // landed, so nothing re-lands or swaps rig while the list catches up.
            const ref = row.uuid as SavedViewpointRef
            setStageViewpoint(ref)
            markViewpointLanded({ ref, camera: savedViewCamera(row) })
          }}
        />
      )}
    </TooltipProvider>
  )
}

function resolvePanelTarget(
  selection: Selection,
  patches: FixturePatch[] | undefined,
  regions: StageRegionDto[] | undefined,
  riggings: RiggingDto[] | undefined,
  elements: StageElementDto[] | undefined,
): StageEditorTarget | null {
  if (selection?.kind === 'patch') {
    const p = patches?.find((x) => x.key === selection.patchKey)
    return p ? { kind: 'patch', patch: p } : null
  }
  if (selection?.kind === 'region') {
    return { kind: 'region', region: findByUuid(regions, selection.uuid) }
  }
  if (selection?.kind === 'rigging') {
    return { kind: 'rigging', rigging: findByUuid(riggings, selection.uuid) }
  }
  if (selection?.kind === 'element') {
    // Like a patch: no form against a row that has gone mid-refetch.
    const element = findByUuid(elements, selection.uuid)
    return element ? { kind: 'element', element } : null
  }
  return null
}

// Bare /stage redirect — follow current project, mirror FixturesRedirect.
export function StageRedirect() {
  const { data: currentProject, isLoading } = useCurrentProjectQuery()
  const navigate = useNavigate()

  useEffect(() => {
    if (!isLoading && currentProject) {
      navigate(`/projects/${currentProject.id}/stage`, { replace: true })
    }
  }, [currentProject, isLoading, navigate])

  if (isLoading) {
    return (
      <Card className="m-4 p-4 flex items-center justify-center">
        <Loader2 className="size-6 animate-spin" />
      </Card>
    )
  }

  return <Navigate to="/projects" replace />
}
