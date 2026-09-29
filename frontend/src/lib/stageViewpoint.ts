import { useSyncExternalStore } from 'react'
import { createSyncStore, sessionStorageArea } from './syncStore'
import { writeEyePose } from './stageCameraPoses'

/**
 * Which camera this window's Stage view looks through (stage-view plan session 1; `Stage.dc.html`
 * is the layout authority). Five cameras on the one scene:
 *
 * - **Orbit** — the turntable: drag to circle the stage, right-drag to pan, scroll to dolly.
 * - **Eye** — a person standing somewhere: drag turns the head, scroll zooms the lens. It starts
 *   where the orbit camera stands, looking where it looks, so *orbit there, then look around* is
 *   the gesture until session 2 gives it saved points and seats.
 * - **Plan · Front · Side** — orthographic sections: pan and zoom, no rotation.
 *
 * Three rules, each with a reason:
 *
 * - **Per tab, in `sessionStorage`** — `lib/immersive.ts`'s reason: the two desk screens are two
 *   windows of one profile, and "the hall screen sits on Front all night" is one window's fact.
 *   It replaced the `stageViewMode` key in `localStorage`, which was one value per profile.
 * - **It rides `viewOptions` under the Stage view; nothing new on the wire.** The desk's Json is
 *   bare, so a top-level announce key would drop the frame; `lib/windowViews.ts` says which keys go
 *   out under which view, and a `windows.viewOptions {viewpoint}` aimed at a Stage window is
 *   applied here ([applyStageViewOptions]).
 * - **The vocabulary is the five cameras, not saved views.** Saved views and seats are session 2's
 *   `stage_viewpoints` rows; this key will then carry one of those as well, and the Screens row's
 *   segment becomes a picker. *Frame the selection* is not a viewpoint — it moves whichever camera
 *   is current and is never announced.
 */

export type StageViewpoint = 'orbit' | 'eye' | 'plan' | 'front' | 'side'
export const STAGE_VIEWPOINTS: readonly StageViewpoint[] = ['orbit', 'eye', 'plan', 'front', 'side']
export type OrthoViewpoint = Extract<StageViewpoint, 'plan' | 'front' | 'side'>

export const STAGE_VIEWPOINT_LABELS: Readonly<Record<StageViewpoint, string>> = {
  orbit: 'Orbit',
  eye: 'Eye',
  plan: 'Plan',
  front: 'Front',
  side: 'Side',
}

/** The caption the canvas draws for each camera, after its name. */
export const STAGE_VIEWPOINT_NOTES: Readonly<Record<StageViewpoint, string>> = {
  orbit: 'turntable · drag to orbit, right-drag to pan',
  eye: 'look around · drag to turn, scroll to zoom',
  plan: 'orthographic · looking down, upstage at the top',
  front: 'orthographic · from the house, looking upstage',
  side: 'orthographic · from stage left, upstage to the right',
}

export const STAGE_VIEWPOINT_KEY = 'stage.viewpoint'
/** The `viewOptions` key the announce carries and `windows.viewOptions` sets. */
export const VIEW_OPTION_VIEWPOINT = 'viewpoint'

export function isStageViewpoint(value: unknown): value is StageViewpoint {
  return typeof value === 'string' && (STAGE_VIEWPOINTS as readonly string[]).includes(value)
}

export function isOrthoViewpoint(value: StageViewpoint): value is OrthoViewpoint {
  return value === 'plan' || value === 'front' || value === 'side'
}

const store = createSyncStore<StageViewpoint>({
  key: STAGE_VIEWPOINT_KEY,
  fallback: 'orbit',
  parse: (parsed) => (isStageViewpoint(parsed) ? parsed : 'orbit'),
  storage: sessionStorageArea,
})

/** This window's Stage camera. Re-renders every reader when it moves. */
export function useStageViewpoint(): StageViewpoint {
  return useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)
}

/** For a reader that only needs the answer at call time. */
export function stageViewpoint(): StageViewpoint {
  return store.getSnapshot()
}

/**
 * Move this window's camera. Moving *into* Eye from another camera forgets the eye's stored pose,
 * so the eye is seeded afresh from wherever the orbit camera stands; a remount while already on Eye
 * (a reload, *Restore*) keeps the pose it had.
 */
export function setStageViewpoint(next: StageViewpoint): void {
  if (next === 'eye' && store.getSnapshot() !== 'eye') writeEyePose(null)
  store.set(next)
}

/**
 * A `windows.viewOptions` frame's Stage keys, applied to this tab. A value outside the vocabulary is
 * ignored rather than read as Orbit: a frame from a later build must not move the camera somewhere
 * it did not mean. Returns what was applied.
 */
export function applyStageViewOptions(options: Readonly<Record<string, string>>): StageViewpoint | undefined {
  const value = options[VIEW_OPTION_VIEWPOINT]
  if (!isStageViewpoint(value)) return undefined
  setStageViewpoint(value)
  return value
}

/**
 * `?viewpoint=` on arrival — a Screens row's *Copy link* carries the camera: apply a value in the
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

/** Test seam: the store back to Orbit with no listeners. */
export function resetStageViewpointStore(): void {
  store.reset()
}
