import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'
import type { CloudState } from '../store/cloud'

/** Mirrors `PendingRemoteSession` in lighting7's `cloud/RemoteBroker.kt`. */
export interface PendingRemoteSession {
  sid: string
  email: string
  displayName: string
  /** The desk user the cloud account links to; absent for an unlinked one. */
  linkedUser?: string | null
  /** When the desk denies it unless an admin answers. */
  expiresAt: string
}

/**
 * Lighting7 Cloud's machine-scoped frames (`plugins/MachineSocket.kt`), sent to admin sockets only:
 * `cloud.state`, which carries the whole state as `tunnel.state` does, and `remoteSession.pending`,
 * the remote sessions waiting for an admin at the desk — sent only on the desk's own listener, a
 * whole list each time, empty once none waits.
 */
export interface CloudWsApi {
  subscribe(fn: (state: CloudState) => void): Subscription
  subscribePending(fn: (sessions: PendingRemoteSession[]) => void): Subscription
  /** The last `remoteSession.pending` this socket was sent, for a reader that mounts after it. */
  pending(): PendingRemoteSession[]
}

type CloudInMessage =
  | { type: 'cloud.state'; state: CloudState }
  | { type: 'remoteSession.pending'; sessions: PendingRemoteSession[] }

export function createCloudWsApi(conn: InternalApiConnection): CloudWsApi {
  const changed = createWsSubscribable<CloudState>()
  const pendingChanged = createWsSubscribable<PendingRemoteSession[]>()
  let latestPending: PendingRemoteSession[] = []

  conn.subscribe((evType, _ev, frame) => {
    // Nothing can be allowed while the socket is down, and the next connection's snapshot says
    // again what still waits.
    if (evType === InternalEventType.close) {
      if (latestPending.length > 0) {
        latestPending = []
        pendingChanged.notify(latestPending)
      }
      return
    }
    if (evType !== InternalEventType.message) return
    const message = frame as CloudInMessage | null
    if (message == null) return
    if (message.type === 'cloud.state' && message.state != null) {
      changed.notify(message.state)
    } else if (message.type === 'remoteSession.pending' && Array.isArray(message.sessions)) {
      latestPending = message.sessions
      pendingChanged.notify(latestPending)
    }
  })

  return {
    subscribe: changed.api.subscribe,
    subscribePending: pendingChanged.api.subscribe,
    pending: () => latestPending,
  }
}
