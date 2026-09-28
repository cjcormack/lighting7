import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Navigate, useNavigate } from 'react-router'
import { toast } from 'sonner'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { Grid2x2, Loader2, Move, Pencil, Plus, RotateCw } from 'lucide-react'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
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
  writePatchPlacement,
  writeRegionPlacement,
  writeRiggingPlacement,
  type PatchPlacementValues,
  type RegionPlacementValues,
  type RiggingPlacementValues,
} from '../store/stagePlacement'
import { formatError } from '../lib/formatError'
import { isEditableTarget } from '../lib/domUtils'
import {
  Stage3D,
  type GizmoMode,
  type PatchPlacementUpdate,
  type PlacementPoint,
  type RegionPositionUpdate,
  type RiggingPositionUpdate,
  type Selection,
} from '../components/stage3d/Stage3D'
import { DEFAULT_STAGE_DIMS } from '../hooks/useProjectedPatches'
import { DEFAULT_RIGGING_LENGTH_M } from '../components/stage3d/RiggingMeshes'
import { StageViewMenu } from '../components/stage3d/StageViewMenu'
import { useStageView } from '../components/stage3d/useStageView'
import { setVisSource, useVisSource } from '../hooks/useVisSource'
import { useNextGoStatus } from '../hooks/useNextGoPreview'
import { StageChannelSourceProvider } from '../hooks/useChannelSource'
import { useModifierHeld } from '../components/stage3d/useShiftHeld'
import { useSnapGrid, SNAP_STEPS_M, type SnapStep } from '../components/stage2d/useSnapGrid'
import { Stage2DView } from '../components/stage2d/Stage2DView'
import {
  useStageSelection,
  type SelectIntent,
  type SelectionRef,
} from '../components/stage3d/useStageSelection'
import { useStageNudge } from '../components/stage2d/useStageNudge'
import { StageBulkPanel } from '../components/stage2d/StageBulkPanel'
import { UnplacedTray } from '../components/stage2d/UnplacedTray'
import { StageShortcutsPopover } from '../components/stage2d/StageShortcutsPopover'
import { useUnplacedPatches } from '../hooks/useUnplacedPatches'
import { resolveBulkTargets, unplaceTargets } from '../lib/stageBulkOps'
import { commitPlacements, type PlacementChange } from '../store/stagePlacement'
import {
  STAGE_PROJECTIONS,
  isProjectionId,
  type ProjectionId,
} from '../lib/stageProjection'
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
import type { FixturePatch } from '../api/patchApi'
import type { StageRegionDto } from '../api/stageRegionApi'
import type { RiggingDto } from '../api/riggingApi'
import { useMediaQuery, SM_BREAKPOINT } from '../hooks/useMediaQuery'

/** The 3D scene, or one of the three orthographic projections. */
type Mode = '3d' | ProjectionId

const STORAGE_KEY = 'stageViewMode'

function isMode(v: unknown): v is Mode {
  return v === '3d' || isProjectionId(v)
}

function loadMode(): Mode {
  if (typeof window === 'undefined') return '3d'
  const v = window.localStorage.getItem(STORAGE_KEY)
  // '2d' is the pre-projection value: the old toggle only had 3D and a top-down
  // 2D. Migrate it to 'plan' rather than resetting the user's preference to 3D.
  if (v === '2d') return 'plan'
  return isMode(v) ? v : '3d'
}

// Raw string rather than JSON, so this can't use usePersistentState — the stored
// value predates that helper and migrating the key would lose the preference.
function useStageViewMode(): [Mode, (m: Mode) => void] {
  const [mode, setModeState] = useState<Mode>(loadMode)
  const setMode = (m: Mode) => {
    setModeState(m)
    try {
      window.localStorage.setItem(STORAGE_KEY, m)
    } catch {
      // ignore quota / private mode failures
    }
  }
  return [mode, setMode]
}

const REGION_DEFAULT_SIZE_M = 2
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

export function Stage() {
  const project = useViewedProject()
  const projectId = project?.id
  const [mode, setMode] = useStageViewMode()
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
  const [placing, setPlacing] = useState<'region' | 'rigging' | null>(null)
  const [panelCollapsed, setPanelCollapsed] = useState(false)
  const [gizmoModeManual, setGizmoModeManual] = useState<GizmoMode>('translate')
  const { flags: viewFlags, setFlag: setViewFlag } = useStageView()
  const visSource = useVisSource()
  // Reads the same cached queries the Next GO source does, so it costs no extra request.
  const nextGoStatus = useNextGoStatus(visSource === 'nextGo')
  const isTabletOrLarger = useMediaQuery(SM_BREAKPOINT)
  // One snap preference for both views — see useSnapGrid on why Shift now
  // *disables* snapping rather than enabling it.
  const snap = useSnapGrid(editMode && isTabletOrLarger)

  const patchFormRef = useRef<EditPatchFormHandle>(null)
  const regionFormRef = useRef<EditStageRegionFormHandle>(null)
  const riggingFormRef = useRef<EditRiggingFormHandle>(null)

  // Pre-drag state per object, so a settle can tell a real move from a bare
  // click and a rejected write can be rolled back to where the drag started.
  const patchOrigin = useDragOrigin<PatchPlacementValues>()
  const regionOrigin = useDragOrigin<RegionPlacementValues>()
  const riggingOrigin = useDragOrigin<RiggingPlacementValues>()

  const { data: projectData } = useProjectQuery(projectId ?? 0, { skip: projectId == null })
  const { data: patches } = usePatchListQuery(projectId ?? 0, { skip: projectId == null })
  // What the picker offers: every patch but infrastructure, which the Stage never shows — the same
  // cached query, filtered. The raw list stays for everything that resolves a key or checks a new
  // one against the whole patch.
  const { data: stagePatches } = useVisiblePatchListQuery(projectId ?? 0, { skip: projectId == null })
  const { data: regions } = useStageRegionListQuery(projectId ?? 0, { skip: projectId == null })
  const { data: riggings } = useRiggingListQuery(projectId ?? 0, { skip: projectId == null })

  const { unplaced } = useUnplacedPatches(projectId)
  // Patches armed in the tray, waiting for a click on the canvas to place them.
  const [armedKeys, setArmedKeys] = useState<ReadonlySet<string>>(() => new Set())

  const [updatePatch] = useUpdatePatchMutation()
  const [updateRegion] = useUpdateStageRegionMutation()
  const [updateRigging] = useUpdateRiggingMutation()
  const [createRegion] = useCreateStageRegionMutation()
  const [createRigging] = useCreateRiggingMutation()

  // Editing works in every view now — only the tablet+ width gate remains, since
  // the gizmos and side panels need the room.
  const showEditToggle = isTabletOrLarger
  const editingActive = editMode && isTabletOrLarger

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
    }
    // Keys off editingActive rather than [mode, editMode], so it now also clears
    // when the viewport drops below the tablet breakpoint — which is correct,
    // given the effect below turns editMode off in that case anyway.
    // Narrowed to the stable callbacks rather than the whole `sel` object, which
    // is a fresh literal each render and would re-run this on every selection
    // change.
  }, [editingActive, clearSelection, patchOrigin, regionOrigin, riggingOrigin])

  useEffect(() => {
    if (!isTabletOrLarger && editMode) setEditMode(false)
  }, [isTabletOrLarger, editMode])

  // Drop selection entries whose object has gone. The lists refetch on every
  // WebSocket change — including other operators' deletes — so without this a
  // multi-selection would either strand a dead ref or have to be thrown away
  // wholesale on any change.
  useEffect(() => {
    if (patches == null && regions == null && riggings == null) return
    reconcileSelection((ref) => {
      switch (ref.kind) {
        case 'patch':
          return (patches ?? []).some((p) => p.key === ref.patchKey)
        case 'region':
          return (regions ?? []).some((r) => r.uuid === ref.uuid)
        case 'rigging':
          return (riggings ?? []).some((r) => r.uuid === ref.uuid)
      }
    })
  }, [patches, regions, riggings, reconcileSelection])

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
    projection: mode === '3d' ? STAGE_PROJECTIONS.plan : STAGE_PROJECTIONS[mode],
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

  // Hold Alt/Option to flip the fixture gizmo to the *other* mode while held.
  const { held: altHeld } = useModifierHeld('altKey', editingActive)
  const flippedGizmoMode: GizmoMode = gizmoModeManual === 'translate' ? 'rotate' : 'translate'
  const gizmoMode: GizmoMode = altHeld ? flippedGizmoMode : gizmoModeManual

  // ⌘D / Ctrl+D duplicates the selected region or rigging, offset by 1m on X.
  // Live data is read through refs so the listener doesn't re-bind on every
  // optimistic store update (which would mean add/remove per drag frame).
  const selectionRef = useRef(selection)
  selectionRef.current = selection
  const regionsRef = useRef(regions)
  regionsRef.current = regions
  const riggingsRef = useRef(riggings)
  riggingsRef.current = riggings
  useEffect(() => {
    if (!editingActive || projectId == null) return
    const onKey = async (e: KeyboardEvent) => {
      const isDuplicateShortcut = e.key.toLowerCase() === 'd' && (e.metaKey || e.ctrlKey)
      if (!isDuplicateShortcut) return
      const target = selectionRef.current
      if (target?.kind !== 'region' && target?.kind !== 'rigging') return
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
  }, [editingActive, projectId, createRegion, createRigging, selectOne])

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
        } else if (!rotateUpdate && mode !== '3d') {
          // A 2D translate MUST write per frame: the SVG draws fixtures straight
          // from the RTK cache, so without this the dot and its label sit frozen
          // at the pre-drag position for the whole gesture and teleport on
          // pointerup — while the guides and the truss drop-target highlight
          // track the cursor, so the feedback actively contradicts itself.
          //
          // 3D translate is deliberately excluded: Stage3D mirrors its drag proxy
          // onto the body imperatively, so a store write per frame would re-render
          // the whole scene to move the mesh somewhere it already is.
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
    [projectId, updatePatch, patchOrigin, mode],
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

  if (projectId == null) {
    return (
      <Card className="m-4 p-4 flex items-center justify-center">
        <Loader2 className="size-6 animate-spin" />
      </Card>
    )
  }

  // Patch key may point at a stale id during list refetches — drop the target
  // until the new row arrives so the form doesn't render against missing data.
  const panelTarget = resolvePanelTarget(selection, patches, regions, riggings)

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

  const togglePlacing = (kind: 'region' | 'rigging') => {
    setPlacing((prev) => (prev === kind ? null : kind))
    clearSelection()
  }

  // Hang truss 1m below stage top (clamped to floor for very short stages),
  // or a typical truss height if the project doesn't declare one.
  const stageH = projectData?.stageHeightM
  const trussZ = stageH != null ? Math.max(0, stageH - 1) : FALLBACK_TRUSS_HEIGHT_M

  // Match the placement-click raycast plane to the height the new object lives
  // at, so the user sees the new object exactly where they clicked.
  const placementZ = placing === 'rigging' ? trussZ : 0

  // Fallback for whichever axis the active view can't learn from a click. Each
  // view fills its own out-of-plane coordinate from this, so the point arriving
  // at `handlePlacementClick` is always complete:
  //   plan       → learns X and Y, takes Z from here
  //   front      → learns X and Z, takes Y from here (mid-stage)
  //   side       → learns Y and Z, takes X from here (centre line)
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
    const kind = placing
    setPlacing(null)
    try {
      if (kind === 'region') {
        const created = await createRegion({
          projectId,
          name: nextDefaultName('Region', regions),
          centerX: p.x,
          centerY: p.y,
          // centerZ is a region's FLOOR, so a click in an elevation places the
          // floor at the clicked height rather than burying half the box.
          centerZ: p.z,
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
        <header className="flex items-center gap-2 border-b px-4 py-2">
          <h1 className="text-sm font-semibold">Stage</h1>
          <div className="flex-1" />
          {editingActive && (
            <>
              <Button
                size="sm"
                variant={placing === 'region' ? 'default' : 'outline'}
                onClick={() => togglePlacing('region')}
                aria-pressed={placing === 'region'}
              >
                <Plus className="size-3.5 mr-1" />
                Region
              </Button>
              <Button
                size="sm"
                variant={placing === 'rigging' ? 'default' : 'outline'}
                onClick={() => togglePlacing('rigging')}
                aria-pressed={placing === 'rigging'}
              >
                <Plus className="size-3.5 mr-1" />
                Rigging
              </Button>
            </>
          )}
          {/* 3D only: this drives drei's TransformControls, which has no 2D
              analogue — a 2D fixture drag is always a move. */}
          {editingActive && mode === '3d' && selection?.kind === 'patch' && (
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
          {/* Shown in every view now — the 2D projections honour the same flags.
              Beam cones are 3D-only, so that entry drops out there. */}
          <StageViewMenu
            flags={viewFlags}
            setFlag={setViewFlag}
            hide={mode === '3d' ? undefined : ['beamCones']}
            visSource={visSource}
            setVisSource={setVisSource}
            sourceStatus={{ nextGo: nextGoStatus }}
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
          <ToggleGroup
            type="single"
            size="sm"
            value={mode}
            onValueChange={(v) => {
              if (isMode(v)) setMode(v)
            }}
          >
            <ToggleGroupItem value="3d">3D</ToggleGroupItem>
            <ToggleGroupItem value="plan">Plan</ToggleGroupItem>
            <ToggleGroupItem value="front">Front</ToggleGroupItem>
            <ToggleGroupItem value="side">Side</ToggleGroupItem>
          </ToggleGroup>
        </header>
        <main className="flex flex-1 min-h-0 overflow-hidden">
          <div className="flex flex-1 min-w-0 flex-col">
            {/* Only the canvas goes inside the vis-source provider. The docked
                StageFixtureControlPanel below is a live editing surface and has to keep
                reading real output whatever the selector is previewing. */}
            <StageChannelSourceProvider>
              {mode === '3d' ? (
                <Stage3D
                  projectId={projectId}
                  editMode={editingActive}
                  selection={selection}
                  placing={placing}
                  placementZ={placementZ}
                  view={viewFlags}
                  gizmoMode={gizmoMode}
                  snap={snap}
                  hidePatchSelectionInfo={showControlPanel}
                  onSelectionChange={handleSelectionChange}
                  onPlacementClick={handlePlacementClick}
                  onPatchPlacementChange={handlePatchPlacementChange}
                  onRegionPositionChange={handleRegionPositionChange}
                  onRiggingPositionChange={handleRiggingPositionChange}
                />
              ) : (
                <Stage2DView
                  projectId={projectId}
                  projection={STAGE_PROJECTIONS[mode]}
                  selection={selection}
                  selectedKeys={sel.selectedKeys}
                  editMode={editingActive}
                  view={viewFlags}
                  snap={snap}
                  // A canvas click also lands tray-armed fixtures, so the view must
                  // treat it as a placement click even when no create is armed.
                  placing={placing ?? (armedKeys.size > 0 ? 'region' : null)}
                  placementDefault={placementDefault}
                  onSelectionChange={handleSelectionChange}
                  onMarqueeSelect={(refs, intent) => sel.selectMany(refs, intent)}
                  onPlacementClick={handlePlacementClick}
                  onPatchPlacementChange={handlePatchPlacementChange}
                  onRegionPositionChange={handleRegionPositionChange}
                  onRiggingPositionChange={handleRiggingPositionChange}
                />
              )}
            </StageChannelSourceProvider>
            {editingActive && mode !== '3d' && (
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
              projection={mode === '3d' ? STAGE_PROJECTIONS.plan : STAGE_PROJECTIONS[mode]}
              regionCount={sel.refs.filter((r) => r.kind === 'region').length}
              riggingCount={sel.refs.filter((r) => r.kind === 'rigging').length}
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
            />
          )}
          {showPanelStub && <StageEditorPanelStub onExpand={() => setPanelCollapsed(false)} />}
          {showPicker && (
            <StageEditorPickerPanel
              patches={stagePatches ?? []}
              regions={regions ?? []}
              riggings={riggings ?? []}
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
    </TooltipProvider>
  )
}

function resolvePanelTarget(
  selection: Selection,
  patches: FixturePatch[] | undefined,
  regions: StageRegionDto[] | undefined,
  riggings: RiggingDto[] | undefined,
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
