import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { createChangeSignalApi, createWsSubscribable } from './wsSubscriptionFactory'

/** What `templatePressed` carries: which template, and the stamp the **server** wrote. */
export interface TemplatePressedEvent {
  templateId: number
  lastPressedAt: string
}

export interface TemplatesWsApi {
  subscribe(fn: () => void): Subscription
  /**
   * `templatePressed` — a template was applied, from any of its four doors, and now carries
   * `lastPressedAt`.
   *
   * A **second** subscribe rather than a reuse of the change signal above, because the two cost
   * different things. That one is payload-free and its bridge drops the `TemplateList`, `Cue` and
   * `CueList` caches; a press happens at busking rate, and this frame carries the whole of what
   * moved, so the bridge patches the cached list and makes no request at all.
   *
   * The stamp is the server's, deliberately: this frame and the next refetch must agree, and two
   * clients ordering a burst of presses by their own clocks would not. The acting client's
   * optimistic patch in `store/templates.ts` is the **one** reading of a local clock, and a
   * deliberate one — a provisional value for the tab that made the press, overwritten by this frame
   * within the same breath. It is what every client keeps; the local reading never leaves that tab.
   */
  subscribePressed(fn: (event: TemplatePressedEvent) => void): Subscription
}

interface TemplatePressedMessage {
  type: 'templatePressed'
  templateId?: number
  lastPressedAt?: string
}

/**
 * Template **CRUD** notifications — created, renamed, copied, deleted.
 *
 * Its own frame rather than a reuse of `lookListChanged`, mirroring the backend's two signals, and
 * for the same reason: the two invalidate different caches, so one message would make every Look
 * edit re-read the template library and vice versa.
 *
 * Deliberately **not** fired when a template's contents change — the same rule `looksWsApi` states.
 * A retune republishes every live consumer directly (`republishForTemplateEdit`) and publishes
 * `provenanceState`, which the programmer API already turns into a debounced state re-read; that is
 * what moves resolved values on screen. Firing this per keystroke of a colour drag would be an
 * invalidation storm behind an open editor.
 */
export function createTemplatesWsApi(conn: InternalApiConnection): TemplatesWsApi {
  const changed = createChangeSignalApi(conn, 'templateListChanged')
  const pressed = createWsSubscribable<TemplatePressedEvent>()

  // Hand-written, per `createChangeSignalApi`'s own rule: the moment a bridge needs the body, that
  // factory is the wrong tool. A frame missing either field is dropped rather than notified with a
  // `NaN` id — this is the one place that can tell.
  conn.subscribe((evType, _ev, frame) => {
    if (evType !== InternalEventType.message) return
    const message = frame as TemplatePressedMessage | null
    if (message?.type !== 'templatePressed') return
    if (typeof message.templateId !== 'number' || typeof message.lastPressedAt !== 'string') return
    pressed.notify({ templateId: message.templateId, lastPressedAt: message.lastPressedAt })
  })

  return { subscribe: changed.subscribe, subscribePressed: pressed.api.subscribe }
}
