import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useDispatch } from 'react-redux'
import { Stage3D } from '../Stage3D'
import type { StageCapture, StageCaptureHandle } from '../CaptureCanvas'
import { resolveViewpoint } from '../savedViewpoints'
import { DEFAULT_VIEW_FLAGS, type StageViewFlags } from '../useStageView'
import { DEFAULT_SCENE_LAYERS, useLightBudget } from '../scene/sceneView'
import { StageChannelSourceProvider } from '../../../hooks/useChannelSource'
import { projectsApi } from '../../../store/projects'
import { stageViewpointsApi } from '../../../store/stageViewpoints'
import { stageElementsApi } from '../../../store/stageElements'
import { patchesApi } from '../../../store/patches'
import { stageRegionsApi } from '../../../store/stageRegions'
import { riggingsApi } from '../../../store/riggings'
import { fixturesApi } from '../../../store/fixtures'
import type { store } from '../../../store'
import type { StageElementDto } from '../../../api/stageElementApi'
import type { StageViewpointDto } from '../../../api/stageViewpointApi'
import type { StageRenderRequest } from '../../../api/stageRenderApi'

/** What a render comes to: the picture, or why there is none — both go back to the desk. */
export type StageRenderOutcome = { png: Blob } | { reason: string }

/**
 * What a fresh Stage window shows, minus the DOM label layer: labels are drawn over the canvas, not
 * in it, so a frame read off the canvas never had them.
 */
const RENDER_VIEW_FLAGS: StageViewFlags = { ...DEFAULT_VIEW_FLAGS, labels: 'none' }

/**
 * Frames drawn before the one read. The first lays out the emitters and packs the light table; the
 * bloom composer is rebuilt after the camera swap's frame and needs another (`Bloom`'s note);
 * the rest are margin.
 */
const SETTLE_FRAMES = 4

/** The window gives up this long before the desk does, so the model hears why rather than a bare timeout. */
const GIVE_UP_EARLY_MS = 3000

/**
 * Read afresh for the render, whatever the cache holds: a `set_scene` a moment ago must be in the
 * picture, not the cache before it — and an entry that errored earlier must not fail this render
 * before its own fetch has run. Subscribed, so the entries live as long as the render does.
 */
const FRESH = { forceRefetch: true } as const

/** Every read the scene draws from, fetched for this render; its rows, or what could not be read. */
type Reads = { views: StageViewpointDto[]; elements: StageElementDto[] } | { failed: string }

const noop = () => {}

/**
 * One `render_view` render (stage-view plan session 4): the Stage view's own `Stage3D`, drawn
 * offscreen by a capture canvas at the size the desk asked for, from the source it named, through
 * the camera and landing its viewpoint resolves to. Loaded lazily by `StageRenderHost` when a
 * request arrives, so three.js stays out of the app shell.
 *
 * **Read-only in every sense.** It reads the rows and the live channels and writes nothing: no DMX,
 * no programmer, and none of this window's own facts — the viewpoint, the camera poses, the landed
 * marker (the rig's `oneShot`), the layers (the defaults, not this window's) and the source (the
 * request's, not this window's). The one it reads, the light budget, is the machine's and is only
 * read. The container sits offscreen and the canvas is never attached, so nothing on screen moves.
 *
 * **It draws once everything is in**: every row read afresh, the viewpoint resolved, the scene
 * mounted (its lazy font included), and a derived source holding what it will hold — then a few
 * frames, then the read. Unmounting it (the host does, on the outcome) disposes the renderer and
 * loses its context on purpose.
 */
export default function StageRenderJob({
  request,
  onDone,
}: {
  request: StageRenderRequest
  onDone: (outcome: StageRenderOutcome) => void
}) {
  const { projectId, width, height } = request
  const dispatch = useDispatch<typeof store.dispatch>()
  const lightBudget = useLightBudget()

  // The same cache entries `Stage3D` reads, fetched now; it mounts only once they are in.
  const [reads, setReads] = useState<Reads | null>(null)
  useEffect(() => {
    const pending = [
      ['the project', dispatch(projectsApi.endpoints.project.initiate(projectId, FRESH))],
      ['the saved viewpoints', dispatch(stageViewpointsApi.endpoints.stageViewpointList.initiate(projectId, FRESH))],
      ['the scene', dispatch(stageElementsApi.endpoints.stageElementList.initiate(projectId, FRESH))],
      ['the patch', dispatch(patchesApi.endpoints.patchList.initiate(projectId, FRESH))],
      ['the stage regions', dispatch(stageRegionsApi.endpoints.stageRegionList.initiate(projectId, FRESH))],
      ['the riggings', dispatch(riggingsApi.endpoints.riggingList.initiate(projectId, FRESH))],
      ['the fixtures', dispatch(fixturesApi.endpoints.fixtureList.initiate(undefined, FRESH))],
      ['the fixture types', dispatch(fixturesApi.endpoints.fixtureTypeList.initiate(undefined, FRESH))],
      // A generic dimmer is drawn as its lantern (session 7): without the library in, every one
      // would be its kind's default, with no cut and no oval.
      ['the lantern library', dispatch(fixturesApi.endpoints.lanternList.initiate(undefined, FRESH))],
    ] as const
    let cancelled = false
    void Promise.all(pending.map(([, p]) => p)).then((results) => {
      if (cancelled) return
      const failedAt = results.findIndex((r) => r.isError)
      const [, views, elements] = results
      setReads(
        failedAt >= 0 || views.data == null || elements.data == null
          ? { failed: pending[Math.max(failedAt, 0)][0] }
          : { views: views.data as StageViewpointDto[], elements: elements.data as StageElementDto[] },
      )
    })
    return () => {
      cancelled = true
      pending.forEach(([, p]) => p.unsubscribe())
    }
  }, [dispatch, projectId])

  // Resolved from the rows fetched for this render, once: a refetch mid-render (another operator's
  // edit invalidating the patch) moves the cache `Stage3D` draws from, never the camera, and never
  // unmounts the canvas it is drawing on. `undefined` is "not yet", `null` is "cannot be drawn here".
  const camera = useMemo(
    () => (reads == null || 'failed' in reads ? undefined : resolveViewpoint(request.viewpoint, reads.views, reads.elements)),
    [reads, request.viewpoint],
  )
  const failed = reads != null && 'failed' in reads ? reads.failed : null

  // One `finish` for the render's whole life: the host hands a fresh `onDone` on every render of
  // its own, and a `finish` that changed with it would re-arm the give-up timer (sliding the
  // deadline past the desk's) and restart the draw under a draw still running.
  const onDoneRef = useRef(onDone)
  onDoneRef.current = onDone
  const doneRef = useRef(false)
  const finish = useCallback((outcome: StageRenderOutcome) => {
    if (doneRef.current) return
    doneRef.current = true
    onDoneRef.current(outcome)
  }, [])

  const [sourceSettled, setSourceSettled] = useState(false)
  const [handle, setHandle] = useState<StageCaptureHandle | null>(null)
  const capture = useMemo<StageCapture>(
    () => ({ width, height, onReady: setHandle, onError: (reason) => finish({ reason }) }),
    [width, height, finish],
  )

  useEffect(() => {
    if (failed != null) finish({ reason: `it could not read ${failed}` })
  }, [failed, finish])
  useEffect(() => {
    // The desk checked the viewpoint against these rows; it can only fail here if they changed since.
    if (camera === null) finish({ reason: 'the viewpoint no longer resolves in this project — it changed since the desk checked it' })
  }, [camera, finish])

  useEffect(() => {
    if (handle == null || !sourceSettled) return
    let cancelled = false
    void (async () => {
      try {
        await handle.draw(SETTLE_FRAMES)
        const png = await handle.toPng()
        if (!cancelled) finish({ png })
      } catch (e) {
        if (!cancelled) finish({ reason: `the frame could not be drawn: ${e instanceof Error ? e.message : String(e)}` })
      }
    })()
    return () => {
      cancelled = true
    }
  }, [handle, sourceSettled, finish])

  // Give up a little before the desk would, naming what never arrived.
  const waiting =
    reads == null
      ? 'waiting for the scene, the patch and the fixtures to load'
      : handle == null
        ? 'waiting for the scene to mount'
        : !sourceSettled
          ? `waiting for the ${request.source} source`
          : 'while drawing'
  const waitingRef = useRef(waiting)
  waitingRef.current = waiting
  useEffect(() => {
    const ms = Math.max(1000, request.timeoutMs - GIVE_UP_EARLY_MS)
    const timer = window.setTimeout(
      () => finish({ reason: `it gave up after ${Math.round(ms / 1000)} s, ${waitingRef.current}` }),
      ms,
    )
    return () => window.clearTimeout(timer)
  }, [request.timeoutMs, finish])

  return (
    <div
      aria-hidden
      inert
      data-stage-render={request.requestId}
      style={{ position: 'fixed', left: -100_000, top: 0, width, height, overflow: 'hidden', pointerEvents: 'none' }}
    >
      <StageChannelSourceProvider source={request.source} onSettled={setSourceSettled}>
        {camera != null && (
          <Stage3D
            projectId={projectId}
            editMode={false}
            selection={null}
            onSelectionChange={noop}
            camera={camera.camera}
            landing={camera.landing}
            showScene
            view={RENDER_VIEW_FLAGS}
            layers={DEFAULT_SCENE_LAYERS}
            lightBudget={lightBudget}
            capture={capture}
          />
        )}
      </StageChannelSourceProvider>
    </div>
  )
}
