import { Component, Suspense, lazy, useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { lightingApi } from '../../api/lightingApi'
import type { StageRenderRequest } from '../../api/stageRenderApi'
import { useFailStageRenderMutation, useUploadStageRenderMutation } from '../../store/stageRenders'
import type { StageRenderOutcome } from '../stage3d/render/StageRenderJob'

// The render itself pulls in three.js and the whole Stage scene, so it loads only when the desk
// first asks this window to draw — never on the app shell's first load.
const StageRenderJob = lazy(() => import('../stage3d/render/StageRenderJob'))

/**
 * Where `render_view`'s requests land (stage-view plan session 4): mounted once in `Layout`, so a
 * desk window on **any** route can be asked, not only one on the Stage view. It draws nothing of its
 * own; while a request is in hand it mounts the lazily loaded `StageRenderJob`, which renders
 * offscreen, and sends the outcome back — the PNG, or why not — then unmounts it, which disposes its
 * renderer and loses its WebGL context on purpose (a second context on an iPad is expensive).
 *
 * **One render at a time.** The desk asks one at a time already; a request that arrives while one
 * is still here is answered *busy* at once rather than queued, since the desk is waiting on it.
 *
 * **Silent on this screen.** An answer the desk refuses — it gave up waiting, the window closed
 * the request — is the model's to hear through the tool's named error, so it is logged here and
 * never toasted: the operator at this window may be mid-show and asked for none of it.
 */
export function StageRenderHost() {
  const [request, setRequest] = useState<StageRenderRequest | null>(null)
  const busy = useRef<string | null>(null)
  const [upload] = useUploadStageRenderMutation()
  const [fail] = useFailStageRenderMutation()

  const answer = useCallback(
    async (r: StageRenderRequest, outcome: StageRenderOutcome) => {
      try {
        // The bytes rather than the Blob: every fetch takes an ArrayBuffer body the same way.
        if ('png' in outcome) {
          await upload({ requestId: r.requestId, token: r.token, png: await outcome.png.arrayBuffer() }).unwrap()
        }
        else await fail({ requestId: r.requestId, token: r.token, reason: outcome.reason }).unwrap()
      } catch (e) {
        console.warn('render_view: the desk did not take this window\'s answer', e)
      }
    },
    [upload, fail],
  )

  useEffect(() => {
    const subscription = lightingApi.stageRender.subscribe((r) => {
      if (busy.current != null) {
        void answer(r, { reason: 'this window was still finishing another render; try again' })
        return
      }
      busy.current = r.requestId
      setRequest(r)
    })
    return () => subscription.unsubscribe()
  }, [answer])

  const onDone = useCallback(
    (r: StageRenderRequest, outcome: StageRenderOutcome) => {
      // Unmount first — the renderer goes before the upload does — then answer.
      if (busy.current === r.requestId) busy.current = null
      setRequest((current) => (current?.requestId === r.requestId ? null : current))
      void answer(r, outcome)
    },
    [answer],
  )

  if (request == null) return null
  return (
    <RenderBoundary key={request.requestId} onError={(reason) => onDone(request, { reason })}>
      <Suspense fallback={null}>
        <StageRenderJob request={request} onDone={(outcome) => onDone(request, outcome)} />
      </Suspense>
    </RenderBoundary>
  )
}

/**
 * A render that throws — its chunk would not load, or the scene threw while mounting — ends that
 * render with the reason, never the window's page.
 */
class RenderBoundary extends Component<{ onError: (reason: string) => void; children: ReactNode }, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  componentDidCatch(error: unknown) {
    this.props.onError(`the render threw: ${error instanceof Error ? error.message : String(error)}`)
  }

  render() {
    return this.state.failed ? null : this.props.children
  }
}
