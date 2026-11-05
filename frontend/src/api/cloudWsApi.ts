import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'
import type { CloudState } from '../store/cloud'

/**
 * Lighting7 Cloud's state — the machine-scoped `cloud.state` frame, sent to admin sockets only
 * (`plugins/MachineSocket.kt`). It carries the whole state, as `tunnel.state` does.
 */
export interface CloudWsApi {
  subscribe(fn: (state: CloudState) => void): Subscription
}

type CloudInMessage = { type: 'cloud.state'; state: CloudState }

export function createCloudWsApi(conn: InternalApiConnection): CloudWsApi {
  const changed = createWsSubscribable<CloudState>()

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== 'message') return
    const message = frame as CloudInMessage | null
    if (message == null || message.type !== 'cloud.state' || message.state == null) return
    changed.notify(message.state)
  })

  return changed.api
}
