import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createChangeSignalApi } from './wsSubscriptionFactory'

export interface PromptBooksWsApi {
  subscribe(fn: () => void): Subscription
}

export function createPromptBooksWsApi(conn: InternalApiConnection): PromptBooksWsApi {
  return createChangeSignalApi(conn, 'promptBookChanged')
}
