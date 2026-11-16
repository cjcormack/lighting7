import { useSyncExternalStore } from 'react'
import { lightingApi } from '../api/lightingApi'
import type { ArtNetReachability, ControllerReachability } from '../api/artNetWsApi'

const subscribe = (notify: () => void) => {
  const subscription = lightingApi.artNet.subscribe(() => notify())
  return () => subscription.unsubscribe()
}

/** The live `artNet.reachability`; null until the first frame. */
export function useArtNetReachability(): ArtNetReachability | null {
  return useSyncExternalStore(subscribe, lightingApi.artNet.current, lightingApi.artNet.current)
}

/** One universe's controller: unknown for an address the desk is not polling, or none. */
export function reachabilityOf(
  reachability: ArtNetReachability | null,
  address: string | null | undefined,
): ControllerReachability['state'] {
  const key = address?.trim()
  if (!reachability?.polling || !key) return 'UNKNOWN'
  return reachability.controllers.find((c) => c.address === key)?.state ?? 'UNKNOWN'
}
