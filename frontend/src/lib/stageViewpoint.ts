import { useSyncExternalStore } from 'react'
import { createSyncStore, sessionStorageArea } from './syncStore'
import { writeEyePose } from './stageCameraPoses'
import { isVisSource, setVisSource, type VisSource } from '../hooks/useVisSource'
import {
  isWorkLights,
  setWorkLights,
  VIEW_OPTION_WORK_LIGHTS,
  type WorkLights,
} from '../components/stage3d/scene/workLights'

/**
 * Where this window's Stage view looks from (stage-view plan sessions 1 and 2; `Stage.dc.html` is
 * the layout authority). A viewpoint is one of two things, spelt in one string:
 *
 * - **A camera** — the five on the one scene:
 *   - **Orbit** — the turntable: drag to circle the stage, right-drag to pan, scroll to dolly.
 *   - **Eye** — a person standing somewhere: drag turns the head, scroll zooms the lens. Entered
 *     from another camera it starts where the orbit camera stands, looking where it looks.
 *   - **Plan · Front · Side** — orthographic sections: pan and zoom, no rotation.
 * - **A saved view** — a `stage_viewpoints` row, by its **uuid** (session 2). An `ORBIT` row lands
 *   the orbit camera on its pose; an `EYE` or `SEAT` row lands the eye (`savedViewpoints.ts` in
 *   `components/stage3d/` resolves a row to its camera and pose). *Frame the selection* is neither:
 *   it moves whichever camera is current and is never announced.
 * - **A seat not yet saved** — `seat:<seating uuid>:<seat id>`, what *Sit in a seat…* picks
 *   (session 3). It lands the eye at the seat's seated eye, as a saved `SEAT` row with no target
 *   does, and *Save this view…* from it saves that row. It rides the same `viewpoint` key — so a
 *   window sitting in an unsaved seat announces it, keeps it across a reload and can be put there by
 *   another window, with **no new key on the wire** (the announce's key set is pinned) and no row
 *   written until the operator asks for one.
 *
 * Three rules, each with a reason:
 *
 * - **Per tab, in `sessionStorage`** — `lib/immersive.ts`'s reason: the two desk screens are two
 *   windows of one profile, and "the hall screen sits on Row F all night" is one window's fact.
 * - **It rides `viewOptions` under the Stage view; nothing new on the wire.** The desk's Json is
 *   bare, so a top-level announce key would drop the frame; `lib/windowViews.ts` says which keys go
 *   out under which view, and a `windows.viewOptions {viewpoint}` aimed at a Stage window is
 *   applied here ([applyStageViewOptions]). A saved view rides the same key as its uuid.
 * - **A value outside the vocabulary is ignored**, never read as Orbit: a frame or a link from a
 *   later build must not move the camera somewhere it did not mean. A uuid is in the vocabulary
 *   whether or not this window has the row yet — rows load after a reload, and a view another
 *   window names may be one this window has not fetched.
 */

export type StageCamera = 'orbit' | 'eye' | 'plan' | 'front' | 'side'
export const STAGE_CAMERAS: readonly StageCamera[] = ['orbit', 'eye', 'plan', 'front', 'side']
export type OrthoCamera = Extract<StageCamera, 'plan' | 'front' | 'side'>
/** The cameras a saved view can land: an `ORBIT` row the orbit, an `EYE` or `SEAT` row the eye. */
export type SavedViewCamera = Extract<StageCamera, 'orbit' | 'eye'>

/** A saved view, by its row's uuid. */
export type SavedViewpointRef = `${string}-${string}-${string}-${string}-${string}`
/** A seat picked but not saved: its seating element's uuid and its id (`F6`). */
export type SeatViewpointRef = `seat:${string}:${string}`
/** What a camera lands on: a saved view or a picked seat. */
export type LandingRef = SavedViewpointRef | SeatViewpointRef
export type StageViewpoint = StageCamera | LandingRef

export const STAGE_CAMERA_LABELS: Readonly<Record<StageCamera, string>> = {
  orbit: 'Orbit',
  eye: 'Eye',
  plan: 'Plan',
  front: 'Front',
  side: 'Side',
}

/** The caption the canvas draws for each camera, after its name. */
export const STAGE_CAMERA_NOTES: Readonly<Record<StageCamera, string>> = {
  orbit: 'turntable · drag to orbit, right-drag to pan',
  eye: 'look around · drag to turn, scroll to zoom',
  plan: 'orthographic · looking down, upstage at the top',
  front: 'orthographic · from the house, looking upstage',
  side: 'orthographic · from stage left, upstage to the right',
}

export const STAGE_VIEWPOINT_KEY = 'stage.viewpoint'
export const STAGE_LANDED_KEY = 'stage.landedViewpoint'
/** The `viewOptions` key the announce carries and `windows.viewOptions` sets. */
export const VIEW_OPTION_VIEWPOINT = 'viewpoint'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const SEAT_REF = /^seat:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}):([A-Z][1-9][0-9]{0,2})$/i

/** The viewpoint for sitting in seat [seatId] of seating [elementUuid], unsaved. */
export function seatViewpointRef(elementUuid: string, seatId: string): SeatViewpointRef {
  return `seat:${elementUuid}:${seatId.trim().toUpperCase()}`
}

/** A picked seat's seating and seat id, or null for anything else. */
export function parseSeatViewpointRef(value: unknown): { elementUuid: string; seatId: string } | null {
  if (typeof value !== 'string') return null
  const m = SEAT_REF.exec(value)
  return m == null ? null : { elementUuid: m[1], seatId: m[2].toUpperCase() }
}

export function isSeatViewpointRef(value: unknown): value is SeatViewpointRef {
  return parseSeatViewpointRef(value) != null
}

export function isStageCamera(value: unknown): value is StageCamera {
  return typeof value === 'string' && (STAGE_CAMERAS as readonly string[]).includes(value)
}

export function isSavedViewpointRef(value: unknown): value is SavedViewpointRef {
  return typeof value === 'string' && UUID.test(value)
}

/** Whether [value] lands a camera: a saved view's uuid or a picked seat. */
export function isLandingRef(value: unknown): value is LandingRef {
  return isSavedViewpointRef(value) || isSeatViewpointRef(value)
}

/** Whether [value] is in the vocabulary: a camera, a saved view's uuid, or a picked seat. */
export function isStageViewpoint(value: unknown): value is StageViewpoint {
  return isStageCamera(value) || isLandingRef(value)
}

export function isOrthoCamera(value: StageCamera): value is OrthoCamera {
  return value === 'plan' || value === 'front' || value === 'side'
}

const store = createSyncStore<StageViewpoint>({
  key: STAGE_VIEWPOINT_KEY,
  fallback: 'orbit',
  parse: (parsed) => (isStageViewpoint(parsed) ? parsed : 'orbit'),
  storage: sessionStorageArea,
})

/**
 * The saved view this window's camera last **landed** on, and the camera that took it — so a
 * remount (a route change, *Restore*, a reload) keeps wherever the operator has looked since
 * rather than snapping back to the row's pose. Picking a saved view clears it, which is what lands
 * the camera again even when the view picked is the one already current. The rig marks it once it
 * has landed (`StageCameraRig`).
 */
export interface LandedViewpoint {
  ref: LandingRef
  camera: SavedViewCamera
}

const landedStore = createSyncStore<LandedViewpoint | null>({
  key: STAGE_LANDED_KEY,
  fallback: null,
  parse: (parsed) => {
    const p = parsed as Partial<LandedViewpoint> | null
    return p != null && isLandingRef(p.ref) && (p.camera === 'orbit' || p.camera === 'eye')
      ? { ref: p.ref, camera: p.camera }
      : null
  },
  storage: sessionStorageArea,
})

/**
 * The camera each saved view this window knows of lands, noted by the Stage view as its rows load.
 * In memory: after a reload the landed marker answers for the one view that matters until the rows
 * arrive.
 */
const savedCameras = new Map<string, SavedViewCamera>()

export function noteSavedViewpointCameras(cameras: ReadonlyMap<string, SavedViewCamera>): void {
  savedCameras.clear()
  for (const [ref, camera] of cameras) savedCameras.set(ref, camera)
}

/** The camera [viewpoint] draws through, or null for a saved view this window cannot place yet. */
export function cameraOfViewpoint(viewpoint: StageViewpoint): StageCamera | null {
  if (isStageCamera(viewpoint)) return viewpoint
  // A seat is always sat in with the eye.
  if (isSeatViewpointRef(viewpoint)) return 'eye'
  const known = savedCameras.get(viewpoint)
  if (known != null) return known
  const landed = landedStore.getSnapshot()
  return landed?.ref === viewpoint ? landed.camera : null
}

/** This window's Stage viewpoint. Re-renders every reader when it moves. */
export function useStageViewpoint(): StageViewpoint {
  return useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)
}

/** For a reader that only needs the answer at call time. */
export function stageViewpoint(): StageViewpoint {
  return store.getSnapshot()
}

export function useLandedViewpoint(): LandedViewpoint | null {
  return useSyncExternalStore(landedStore.subscribe, landedStore.getSnapshot, landedStore.getServerSnapshot)
}

export function markViewpointLanded(landed: LandedViewpoint): void {
  landedStore.set(landed)
}

/**
 * Move this window's viewpoint. Two side effects keep the rules above:
 *
 * - Moving *into* the Eye camera from a camera that is not the eye forgets the eye's stored pose, so
 *   the eye is seeded afresh from wherever the orbit camera stands. From a saved eye or seat view it
 *   keeps it — you are already standing there — and a remount already on Eye keeps it too.
 * - Picking a saved view or a seat clears the landed marker, so the camera lands on it — again, if
 *   it is the view already current and the operator has looked around since.
 */
export function setStageViewpoint(next: StageViewpoint): void {
  if (next === 'eye' && cameraOfViewpoint(store.getSnapshot()) !== 'eye') writeEyePose(null)
  if (isLandingRef(next)) landedStore.set(null)
  store.set(next)
}

/**
 * The Stage view's other per-window fact: which layer of the lighting cascade it draws — **Source**,
 * Output · Output + Programmer · Programmer only · Next GO (`hooks/useVisSource.ts`). It rides
 * `viewOptions` beside the viewpoint (stage-view plan §3.2, session 3), so a hall screen can sit on
 * Row F showing what the next GO will look like.
 */
export const VIEW_OPTION_SOURCE = 'source'

/** What a Stage window's options set, when a frame or an arrival sets anything. */
export interface AppliedStageOptions {
  viewpoint?: StageViewpoint
  source?: VisSource
  workLights?: WorkLights
}

/**
 * A `windows.viewOptions` frame's Stage keys, applied to this tab: the viewpoint, the source and
 * the work lights (stage-view menu plan D8, `scene/workLights.ts`), each only when its value is in
 * its vocabulary — a value outside it is ignored rather than read as Orbit, as Output or as off, so
 * a frame from a later build cannot move the window somewhere it did not mean. Returns what was
 * applied.
 */
export function applyStageViewOptions(options: Readonly<Record<string, string>>): AppliedStageOptions {
  const applied: AppliedStageOptions = {}
  const viewpoint = options[VIEW_OPTION_VIEWPOINT]
  if (isStageViewpoint(viewpoint)) {
    setStageViewpoint(viewpoint)
    applied.viewpoint = viewpoint
  }
  const source = options[VIEW_OPTION_SOURCE]
  if (isVisSource(source)) {
    setVisSource(source)
    applied.source = source
  }
  const workLights = options[VIEW_OPTION_WORK_LIGHTS]
  if (isWorkLights(workLights)) {
    setWorkLights(workLights)
    applied.workLights = workLights
  }
  return applied
}

/**
 * `?viewpoint=`, `?source=` and `?workLights=` on arrival — a Screens row's *Copy link* carries all
 * three: apply the values in their vocabularies, and answer the search with the parameters
 * stripped, so a reload keeps whatever the window has moved to since. Null when there is none, so
 * the caller writes nothing. A value outside its vocabulary is stripped and not applied, as a
 * frame's is.
 */
export function consumeLaunchStageOptions(search: URLSearchParams): URLSearchParams | null {
  const viewpoint = search.get(VIEW_OPTION_VIEWPOINT)
  const source = search.get(VIEW_OPTION_SOURCE)
  const workLights = search.get(VIEW_OPTION_WORK_LIGHTS)
  if (viewpoint == null && source == null && workLights == null) return null
  if (isStageViewpoint(viewpoint)) setStageViewpoint(viewpoint)
  if (isVisSource(source)) setVisSource(source)
  if (isWorkLights(workLights)) setWorkLights(workLights)
  const next = new URLSearchParams(search)
  next.delete(VIEW_OPTION_VIEWPOINT)
  next.delete(VIEW_OPTION_SOURCE)
  next.delete(VIEW_OPTION_WORK_LIGHTS)
  return next
}

/** What the Stage view announces as its `viewOptions`: the viewpoint, the source and the work lights. */
export function stageViewOptions(viewpoint: StageViewpoint, source: VisSource, workLights: WorkLights): Record<string, string> {
  return { [VIEW_OPTION_VIEWPOINT]: viewpoint, [VIEW_OPTION_SOURCE]: source, [VIEW_OPTION_WORK_LIGHTS]: workLights }
}

/** Test seam: the stores back to Orbit and nothing landed, with no listeners. */
export function resetStageViewpointStore(): void {
  store.reset()
  landedStore.reset()
  savedCameras.clear()
}
