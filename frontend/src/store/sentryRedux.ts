import { createReduxEnhancer } from '@sentry/react'
import { Status } from '../api/statusApi'

/**
 * Redux in Sentry events (Sentry plan §4.1): one breadcrumb per action carrying its type and, for
 * RTK Query, its endpoint — never a payload, which for a query is the whole response — and as the
 * `state` context only the current project, the connection and the boot phase. The full store is
 * megabytes of patch and cue data. Bookkeeping actions that fire per WS frame or per mount are left
 * out so they don't push the useful breadcrumbs off the end.
 */

interface ActionLike {
  type: string
  meta?: { arg?: { endpointName?: unknown } }
}

const NOISE = /^restApi\/(?:queries\/queryResultPatched|internalSubscriptions|subscriptions|config)\b/

function actionTransformer(action: ActionLike): { type: string; endpoint?: string } | null {
  if (NOISE.test(action.type)) return null
  const endpoint = action.meta?.arg?.endpointName
  return typeof endpoint === 'string' ? { type: action.type, endpoint } : { type: action.type }
}

interface CacheEntry {
  data?: unknown
}

interface StateLike {
  restApi?: { queries?: Record<string, CacheEntry | undefined> }
}

function cached<T>(state: StateLike, key: string): T | undefined {
  return state.restApi?.queries?.[key]?.data as T | undefined
}

function stateTransformer(state: StateLike) {
  const project = cached<{ id?: number; kind?: string }>(state, 'currentProject(undefined)')
  const boot = cached<{ phase?: string; ready?: boolean }>(state, 'bootStatus(undefined)')
  const connection = cached<Status>(state, 'status(undefined)')
  return {
    project: project == null ? null : { id: project.id, kind: project.kind },
    connection: connection == null ? null : Status[connection],
    boot: boot == null ? null : { phase: boot.phase, ready: boot.ready },
  }
}

export const sentryReduxEnhancer = createReduxEnhancer({
  attachReduxState: false,
  actionTransformer,
  stateTransformer,
})
