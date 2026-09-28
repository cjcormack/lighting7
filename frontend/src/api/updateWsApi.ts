import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'
import type { UpdateAvailability, UpdatePhase } from '../store/updates'

/**
 * Update state and download progress.
 *
 * Modelled on `cloudSyncWsApi` rather than `installWsApi` because this one **carries a
 * payload**. Machine-scoped frames are normally a bare "something changed, refetch", but for a
 * several-hundred-megabyte installer download the frame *is* the progress: a payload-free
 * discriminator at 2 Hz would mean an HTTP round-trip per tick, which is the exact traffic the
 * socket exists to avoid. See the header note in `plugins/MachineSocket.kt`.
 */
export interface UpdateWsApi {
  subscribe(fn: (event: UpdateStateChangedEvent) => void): Subscription
}

export interface UpdateStateChangedEvent {
  phase: UpdatePhase
  availability: UpdateAvailability
  latestVersion: string | null
  downloadedBytes: number
  totalBytes: number | null
}

type UpdateInMessage = {
  type: 'updateStateChanged'
} & UpdateStateChangedEvent

export function createUpdateWsApi(conn: InternalApiConnection): UpdateWsApi {
  const updateChanged = createWsSubscribable<UpdateStateChangedEvent>()

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== 'message') return
    const message = frame as UpdateInMessage | null
    if (message == null || message.type !== 'updateStateChanged') return
    updateChanged.notify({
      phase: message.phase,
      availability: message.availability,
      latestVersion: message.latestVersion ?? null,
      downloadedBytes: message.downloadedBytes ?? 0,
      totalBytes: message.totalBytes ?? null,
    })
  })

  return updateChanged.api
}
