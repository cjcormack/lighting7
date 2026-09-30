import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'
import { isStageViewpoint, type StageViewpoint } from '../lib/stageViewpoint'
import { isVisSource, type VisSource } from '../hooks/useVisSource'

/**
 * `render_view`'s request to this window (stage-view plan session 4; lighting7
 * `plugins/StageRenderSocket.kt`): render [viewpoint] offscreen at [width] × [height] from [source]
 * and upload the PNG. **Outbound only from the desk, and to one socket**: unlike the five
 * `windows.*` commands, nothing is rebroadcast and nothing names a target — the desk sends it only
 * to the window it chose, so a frame that arrives here is this window's job. The answer goes back
 * over REST (`store/stageRenders.ts`), bound to [requestId], [token] and this session; the socket
 * gains no inbound message.
 *
 * [viewpoint] is already the Stage view's own vocabulary — a camera, a saved view's uuid, or
 * `seat:<uuid>:<id>` — resolved and checked by the desk against the project's rows.
 */
export interface StageRenderRequest {
  requestId: string
  /** The secret the upload must carry; sent to this socket and no other. */
  token: string
  projectId: number
  viewpoint: StageViewpoint
  width: number
  height: number
  source: VisSource
  /** How long the desk waits for the answer. */
  timeoutMs: number
}

export interface StageRenderWsApi {
  /** Every request the desk sends this window. */
  subscribe(fn: (request: StageRenderRequest) => void): Subscription
}

/** The desk's frame, or null for anything that is not a well-formed one. */
export function parseStageRenderRequest(raw: unknown): StageRenderRequest | null {
  if (raw == null || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  if (
    r.type !== 'stageRender.request' ||
    typeof r.requestId !== 'string' ||
    typeof r.token !== 'string' ||
    typeof r.projectId !== 'number' ||
    !isStageViewpoint(r.viewpoint) ||
    !isPositiveInt(r.width) ||
    !isPositiveInt(r.height) ||
    !isVisSource(r.source) ||
    typeof r.timeoutMs !== 'number'
  ) {
    return null
  }
  return {
    requestId: r.requestId,
    token: r.token,
    projectId: r.projectId,
    viewpoint: r.viewpoint,
    width: r.width,
    height: r.height,
    source: r.source,
    timeoutMs: r.timeoutMs,
  }
}

function isPositiveInt(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value > 0
}

export function createStageRenderWsApi(conn: InternalApiConnection): StageRenderWsApi {
  const requests = createWsSubscribable<StageRenderRequest>()
  conn.subscribe((evType, _ev, frame) => {
    if (evType !== InternalEventType.message) return
    const request = parseStageRenderRequest(frame)
    if (request != null) requests.notify(request)
  })
  return { subscribe: requests.api.subscribe }
}
