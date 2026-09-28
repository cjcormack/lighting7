import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'
import type { SyncErrorCode, SyncLogEntry, SyncOutcome } from '../store/cloudSync'

/**
 * Cloud-sync lifecycle messages. The backend emits exactly one Started → one
 * Done-or-Failed-or-ConflictsPending per `POST /sync/run` invocation, and Started → Done
 * per `POST /sync/apply`. The page uses these to trigger toast + cache-invalidation;
 * we don't render a streaming progress bar yet (single done/fail is enough for the
 * data volumes we support — revisit if syncs ever feel slow).
 *
 * **`cloudSyncStarted` is deliberately not surfaced.** The frame is emitted, but the run is
 * started *by* this client through `POST /sync/run`, so its own spinner is already up by the time
 * the echo arrives and there is nothing for a subscriber to do with it. A `subscribeStarted` sat
 * here with no caller; the progress bar the KDoc above defers is where it would earn its keep.
 */
export interface CloudSyncWsApi {
  subscribeDone(fn: (event: CloudSyncDoneEvent) => void): Subscription
  subscribeFailed(fn: (event: CloudSyncFailedEvent) => void): Subscription
  /**
   * Phase 5: same-record edits surfaced as a conflict-resolution session. The frontend
   * reacts by fetching `/sync/conflicts` so the new `<ConflictPanel>` shows up. Distinct
   * from `subscribeDone` because a conflicting run never emits Done — it stops at
   * CONFLICTS_PENDING and waits for resolve+apply.
   */
  subscribeConflictsPending(fn: (event: CloudSyncConflictsPendingEvent) => void): Subscription
  /**
   * GitHub OAuth identity changed (connected, disconnected, or refreshed). The sync
   * configuration page invalidates the identity cache so the "Connected as @login"
   * row updates without polling.
   */
  subscribeOAuthIdentityChanged(fn: (event: OAuthIdentityChangedEvent) => void): Subscription
  /**
   * Phase 8: a single activity-log row was just persisted. The activity feed appends
   * the entry without round-tripping `/sync/activity`.
   */
  subscribeLogAppended(fn: (event: CloudSyncLogAppendedEvent) => void): Subscription
  /**
   * A peer (or this same install in another tab) just imported a remote repo as a new
   * local project. Subscribers refresh `ProjectList` + `CloudSyncConfig` so the hub
   * picks up the new row without a full reload.
   */
  subscribeProjectImported(fn: (event: CloudSyncProjectImportedEvent) => void): Subscription
}

export interface CloudSyncDoneEvent {
  projectId: number
  outcome: SyncOutcome
  headSha: string
  pushed: number
  pulled: number
  replaced: number
  message: string
}

export interface CloudSyncFailedEvent {
  projectId: number
  errorCode: SyncErrorCode
  message: string
}

export interface CloudSyncConflictsPendingEvent {
  projectId: number
  sessionId: number
  conflictCount: number
}

export interface CloudSyncLogAppendedEvent {
  projectId: number
  entry: SyncLogEntry
}

export interface CloudSyncProjectImportedEvent {
  projectId: number
  projectUuid: string
  name: string
}

export interface OAuthIdentityChangedEvent {
  provider: string
  connected: boolean
  login?: string | null
  accessExpiresAt?: string | null
  refreshExpiresAt?: string | null
  /**
   * GitHub has rejected the identity. Carried so a listener can react to *becoming* broken,
   * but the frame is only a nudge — the detail (reason, since when) comes from re-reading
   * the identity endpoint.
   */
  reauthRequired?: boolean
}

type CloudSyncInMessage =
  | { type: 'cloudSyncDone' } & CloudSyncDoneEvent
  | { type: 'cloudSyncFailed' } & CloudSyncFailedEvent
  | { type: 'cloudSyncConflictsPending' } & CloudSyncConflictsPendingEvent
  | { type: 'cloudSyncLogAppended' } & CloudSyncLogAppendedEvent
  | { type: 'cloudSyncProjectImported' } & CloudSyncProjectImportedEvent
  | { type: 'oauthIdentityChanged' } & OAuthIdentityChangedEvent

export function createCloudSyncWsApi(conn: InternalApiConnection): CloudSyncWsApi {
  const done = createWsSubscribable<CloudSyncDoneEvent>()
  const failed = createWsSubscribable<CloudSyncFailedEvent>()
  const conflictsPending = createWsSubscribable<CloudSyncConflictsPendingEvent>()
  const logAppended = createWsSubscribable<CloudSyncLogAppendedEvent>()
  const projectImported = createWsSubscribable<CloudSyncProjectImportedEvent>()
  const oauthIdentityChanged = createWsSubscribable<OAuthIdentityChangedEvent>()

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== 'message') return
    const message = frame as CloudSyncInMessage | null
    if (message == null) return
    switch (message.type) {
      case 'cloudSyncDone':
        done.notify(message)
        break
      case 'cloudSyncFailed':
        failed.notify(message)
        break
      case 'cloudSyncConflictsPending':
        conflictsPending.notify(message)
        break
      case 'cloudSyncLogAppended':
        logAppended.notify(message)
        break
      case 'cloudSyncProjectImported':
        projectImported.notify(message)
        break
      case 'oauthIdentityChanged':
        oauthIdentityChanged.notify(message)
        break
    }
  })

  return {
    subscribeDone: done.api.subscribe,
    subscribeFailed: failed.api.subscribe,
    subscribeConflictsPending: conflictsPending.api.subscribe,
    subscribeLogAppended: logAppended.api.subscribe,
    subscribeProjectImported: projectImported.api.subscribe,
    subscribeOAuthIdentityChanged: oauthIdentityChanged.api.subscribe,
  }
}
