import { useSyncExternalStore } from 'react'
import { createSyncStore, sessionStorageArea } from './syncStore'
import { writeEyePose } from './stageCameraPoses'

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
export type StageViewpoint = StageCamera | SavedViewpointRef

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

export function isStageCamera(value: unknown): value is StageCamera {
  return typeof value === 'string' && (STAGE_CAMERAS as readonly string[]).includes(value)
}

export function isSavedViewpointRef(value: unknown): value is SavedViewpointRef {
  return typeof value === 'string' && UUID.test(value)
}

/** Whether [value] is in the vocabulary: a camera, or a saved view's uuid. */
export function isStageViewpoint(value: unknown): value is StageViewpoint {
  return isStageCamera(value) || isSavedViewpointRef(value)
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
  ref: SavedViewpointRef
  camera: SavedViewCamera
}

const landedStore = createSyncStore<LandedViewpoint | null>({
  key: STAGE_LANDED_KEY,
  fallback: null,
  parse: (parsed) => {
    const p = parsed as Partial<LandedViewpoint> | null
    return p != null && isSavedViewpointRef(p.ref) && (p.camera === 'orbit' || p.camera === 'eye')
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
 * - Picking a saved view clears the landed marker, so the camera lands on it — again, if it is the
 *   view already current and the operator has looked around since.
 */
export function setStageViewpoint(next: StageViewpoint): void {
  if (next === 'eye' && cameraOfViewpoint(store.getSnapshot()) !== 'eye') writeEyePose(null)
  if (isSavedViewpointRef(next)) landedStore.set(null)
  store.set(next)
}

/**
 * A `windows.viewOptions` frame's Stage keys, applied to this tab. A value outside the vocabulary is
 * ignored rather than read as Orbit. Returns what was applied.
 */
export function applyStageViewOptions(options: Readonly<Record<string, string>>): StageViewpoint | undefined {
  const value = options[VIEW_OPTION_VIEWPOINT]
  if (!isStageViewpoint(value)) return undefined
  setStageViewpoint(value)
  return value
}

/**
 * `?viewpoint=` on arrival — a Screens row's *Copy link* carries it: apply a value in the
 * vocabulary, and answer the search with the parameter stripped, so a reload keeps whatever the
 * window has moved to since. Null when there is no parameter, so the caller writes nothing. A value
 * outside the vocabulary is stripped and not applied, as a `windows.viewOptions` frame's is.
 */
export function consumeLaunchViewpoint(search: URLSearchParams): URLSearchParams | null {
  const value = search.get(VIEW_OPTION_VIEWPOINT)
  if (value == null) return null
  if (isStageViewpoint(value)) setStageViewpoint(value)
  const next = new URLSearchParams(search)
  next.delete(VIEW_OPTION_VIEWPOINT)
  return next
}

/** What the Stage view announces as its `viewOptions`. */
export function stageViewOptions(viewpoint: StageViewpoint): Record<string, string> {
  return { [VIEW_OPTION_VIEWPOINT]: viewpoint }
}

/** Test seam: the stores back to Orbit and nothing landed, with no listeners. */
export function resetStageViewpointStore(): void {
  store.reset()
  landedStore.reset()
  savedCameras.clear()
}
