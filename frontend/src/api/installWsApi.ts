import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createChangeSignalApi } from './wsSubscriptionFactory'

// The install row — currently just the desk's friendly name — changed on another client.
// Machine-scoped like the user list, so it arrives from `State.machineEventsFlow` rather than
// the per-project fixtures bus.
export interface InstallWsApi {
  subscribe(fn: () => void): Subscription
}

export function createInstallWsApi(conn: InternalApiConnection): InstallWsApi {
  return createChangeSignalApi(conn, 'installChanged')
}
