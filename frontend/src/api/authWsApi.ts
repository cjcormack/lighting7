import { InternalApiConnection, InternalEventType, UNAUTHENTICATED_CLOSE_CODE } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'

// The socket's half of the auth story. The backend accepts the `/api` upgrade and
// then closes it with code 4401 when there is no valid session, so a dead session
// shows up here as a close code rather than as a failed request.
//
// This is deliberately its own namespace rather than a widening of `StatusApi`:
// statusApi exposes the transport state (CONNECTING/OPEN/CLOSING/CLOSED) and is
// consumed broadly — folding an application-level auth signal into that enum would
// push a case onto every consumer that has no interest in it.
// The other half of that story is a *change* rather than a loss: another admin renamed or
// re-roled us. The backend sends `ownAccountChanged` only to sockets belonging to the affected
// account, never as a broadcast — a broadcast would have every connected client refetch
// `auth/status` on any admin edit. It lives here rather than in `usersWsApi` for the same reason
// the 4401 signal does: it invalidates `Auth`, which is this module's tag, and `store/users.ts`
// keeps users and auth as separate tag families on purpose.
export interface AuthWsApi {
  /** Fires when the server rejected our session on the socket. */
  subscribeUnauthenticated(fn: () => void): Subscription

  /** Fires when *our own* account was changed by someone else — a rename or a re-role. */
  subscribeOwnAccountChanged(fn: () => void): Subscription
}

type AuthInMessage = {
  type: 'ownAccountChanged'
}

export function createAuthWsApi(conn: InternalApiConnection): AuthWsApi {
  const unauthenticated = createWsSubscribable<void>()
  const ownAccountChanged = createWsSubscribable<void>()

  // Whether we have seen the socket open at least once. The *first* open is the page's own
  // initial connect, when AuthGate is already fetching `auth/status` unconditionally — firing
  // then just races a second identical request onto the critical path. Every open after that is
  // a genuine re-open (a dropped connection, or the deliberate re-handshake after
  // login/setup/logout), which is the case worth re-checking. This subscriber is registered in
  // the same synchronous module evaluation that creates the socket, and `onopen` cannot fire
  // before the next macrotask, so the first open is never missed.
  let seenOpen = false

  conn.subscribe((evType, ev, frame) => {
    if (
      evType === InternalEventType.close &&
      ev instanceof CloseEvent &&
      ev.code === UNAUTHENTICATED_CLOSE_CODE
    ) {
      unauthenticated.notify()
    } else if (evType === InternalEventType.open) {
      // Catch-up on *re*connect: the only thing that notices a rename or re-role that landed
      // while the socket was down. Safe to fire because an invalidation-driven refetch keeps the
      // stale `data` (see the comment in AuthGate), so nothing blanks out.
      if (seenOpen) ownAccountChanged.notify()
      seenOpen = true
    } else if (evType === InternalEventType.message) {
      const message = frame as AuthInMessage | null
      if (message == null) return
      if (message.type === 'ownAccountChanged') {
        ownAccountChanged.notify()
      }
    }
  })

  return {
    subscribeUnauthenticated: unauthenticated.api.subscribe,
    subscribeOwnAccountChanged: ownAccountChanged.api.subscribe,
  }
}
