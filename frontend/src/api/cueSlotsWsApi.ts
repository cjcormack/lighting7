import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createChangeSignalApi } from './wsSubscriptionFactory'

export interface CueSlotsWsApi {
  subscribe(fn: () => void): Subscription
}

export function createCueSlotsWsApi(conn: InternalApiConnection): CueSlotsWsApi {
  return createChangeSignalApi(conn, 'cueSlotListChanged')
}
