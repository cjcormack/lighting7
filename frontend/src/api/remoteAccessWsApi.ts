import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'
import type { TunnelState } from '../store/remoteAccess'

/**
 * The remote-access tunnel's live status — the machine-scoped `tunnel.state` frame, sent to admin
 * sockets only (`plugins/MachineSocket.kt`). Like `updateStateChanged` it **carries its payload**:
 * the state *is* the news, and the first-enable download streams its progress through it.
 */
export interface RemoteAccessWsApi {
  subscribe(fn: (state: TunnelState) => void): Subscription
}

type TunnelInMessage = { type: 'tunnel.state'; state: TunnelState }

export function createRemoteAccessWsApi(conn: InternalApiConnection): RemoteAccessWsApi {
  const changed = createWsSubscribable<TunnelState>()

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== 'message') return
    const message = frame as TunnelInMessage | null
    if (message == null || message.type !== 'tunnel.state' || message.state == null) return
    changed.notify(message.state)
  })

  return changed.api
}
