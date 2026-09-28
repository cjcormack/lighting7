import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import type { CueRunStateEvent, ProgramStateChangedEvent } from './cueStacksApi'
import { createWsSubscribable } from './wsSubscriptionFactory'

export interface CueStacksWsApi {
  /** The stack collection changed (create/rename/delete/reorder/separator/order). */
  subscribe(fn: () => void): Subscription
  /** The project playhead moved (activate/deactivate/advance/go-to). */
  subscribeToProgramState(fn: (event: ProgramStateChangedEvent) => void): Subscription
  /**
   * A stack's run state changed — live cue, armed next, or a fade starting. Fires for
   * transitions this session didn't cause: another browser's GO, the MIDI surface, the
   * backend's auto-advance timer.
   */
  subscribeToRunState(fn: (event: CueRunStateEvent) => void): Subscription
}

type CueStackInMessage =
  | { type: 'cueStackListChanged' }
  | { type: 'showChanged'; projectId: number; activeStackId: number | null; activeStackName: string | null }
  | ({ type: 'cueRunStateChanged' } & CueRunStateEvent)

export function createCueStacksWsApi(conn: InternalApiConnection): CueStacksWsApi {
  const cueStacksChanged = createWsSubscribable<void>()
  const programStateChanged = createWsSubscribable<ProgramStateChangedEvent>()
  const runStateChanged = createWsSubscribable<CueRunStateEvent>()

  conn.subscribe((evType, _ev, frame) => {
    if (evType === 'message') {
      const message = frame as CueStackInMessage | null
      if (message == null) return
      if (message.type === 'cueStackListChanged') {
        cueStacksChanged.notify()
      } else if (message.type === 'showChanged') {
        programStateChanged.notify({
          projectId: message.projectId,
          activeStackId: message.activeStackId,
          activeStackName: message.activeStackName,
        })
      } else if (message.type === 'cueRunStateChanged') {
        const { type: _type, ...event } = message
        runStateChanged.notify(event)
      }
    }
  })

  return {
    subscribe: cueStacksChanged.api.subscribe,
    subscribeToProgramState: programStateChanged.api.subscribe,
    subscribeToRunState: runStateChanged.api.subscribe,
  }
}
