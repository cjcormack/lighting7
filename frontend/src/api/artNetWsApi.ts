import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'

/** Mirrors `Reachability` in lighting7's `dmx/ArtNetPoller.kt`. */
export type ControllerReachabilityState = 'REACHABLE' | 'UNREACHABLE' | 'UNKNOWN'

export interface ControllerReachability {
  /** The universe's address as the project names it. */
  address: string
  state: ControllerReachabilityState
  /** When it last answered ArtPoll; absent when it never has. */
  lastReplyAt?: string | null
}

export interface ArtNetReachability {
  /** False while nothing reads replies (`dmx.mock`, or another program holds 6454): every controller is unknown. */
  polling: boolean
  controllers: ControllerReachability[]
}

/**
 * Whether the live project's Art-Net controllers answer ArtPoll: the machine-scoped
 * `artNet.reachability` frame (`plugins/MachineSocket.kt`), sent to every socket as a snapshot on
 * connect and on every change, so the latest frame is kept.
 */
export interface ArtNetWsApi {
  subscribe(fn: (state: ArtNetReachability) => void): Subscription
  /** The last frame, or null before the first. */
  current(): ArtNetReachability | null
}

type ArtNetInMessage = { type: 'artNet.reachability' } & ArtNetReachability

export function createArtNetWsApi(conn: InternalApiConnection): ArtNetWsApi {
  const changed = createWsSubscribable<ArtNetReachability>()
  let latest: ArtNetReachability | null = null

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== 'message') return
    const message = frame as ArtNetInMessage | null
    if (message == null || message.type !== 'artNet.reachability' || !Array.isArray(message.controllers)) return
    latest = { polling: message.polling, controllers: message.controllers }
    changed.notify(latest)
  })

  return {
    subscribe: changed.api.subscribe,
    current: () => latest,
  }
}
