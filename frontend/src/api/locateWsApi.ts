import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'

/** One located fixture or group, as `store/locate.ts`'s `LocateTarget`. */
export interface LocateTarget {
  type: 'fixture' | 'group'
  key: string
}

/**
 * The live show's located targets (lighting7 `plugins/LocateSocket.kt` is the wire contract):
 * `locate.state {projectId, targets}`, a `StateFlow`, so the subscription is the connect snapshot.
 * Outbound only — the toggle is REST. It is what tells every window that a Locate ended without a
 * toggle: an edit to a located fixture makes its Locate the operator's values (lighting7
 * `show/LocateCommit.kt`).
 */
export interface LocateWsApi {
  /** The located targets on every change; a late subscriber gets the last frame at once. */
  subscribe(fn: (targets: LocateTarget[]) => void): Subscription
  /** The last frame's targets, or none before the first. */
  get(): LocateTarget[]
  /** How many frames have arrived, so a caller can tell whether one landed while it waited. */
  frames(): number
}

/** A `locate.state` frame's targets; anything malformed is dropped. */
export function parseLocateFrame(raw: unknown): LocateTarget[] {
  if (raw == null || typeof raw !== 'object') return []
  const targets = (raw as Record<string, unknown>).targets
  if (!Array.isArray(targets)) return []
  return targets.filter(
    (t): t is LocateTarget =>
      t != null &&
      typeof t === 'object' &&
      ((t as LocateTarget).type === 'fixture' || (t as LocateTarget).type === 'group') &&
      typeof (t as LocateTarget).key === 'string',
  )
}

export function createLocateWsApi(conn: InternalApiConnection): LocateWsApi {
  const located = createWsSubscribable<LocateTarget[]>()
  let last: LocateTarget[] = []
  let seen = false
  let frames = 0

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== InternalEventType.message) return
    const message = frame as { type?: string } | null
    if (message?.type !== 'locate.state') return
    last = parseLocateFrame(message)
    seen = true
    frames += 1
    located.notify(last)
  })

  return {
    subscribe: (fn) => {
      const sub = located.api.subscribe(fn)
      if (seen) fn(last)
      return sub
    },
    get: () => last,
    frames: () => frames,
  }
}
