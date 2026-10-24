import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'
import { sendGesture } from './wsGesture'

/**
 * Each cue stack's **effect amount** (lighting7 effects plan D10; `plugins/StackFxAmountSocket.kt`
 * is the wire contract): a runtime weight, 0–1, on the effects that stack's cues run, which the
 * desk applies after the blend — 50 % puts a stack's chase halfway over what is underneath. 100 %
 * at load, dropped on a project switch, kept by a reload; never stored.
 *
 * - `stackFxAmount.state` `{projectId, stacks: [{stackId, amount}]}` — every stack below full; a
 *   stack absent is at 100 %. A `StateFlow`, so the subscription is the connect snapshot and every
 *   change is a whole frame.
 * - `stackFxAmount.set` `{stackId, amount}` — the *FX amount* slider, at frame rate.
 *
 * REST reads and sets the same value (`GET/PUT projects/{id}/cue-stacks/{sid}/fx-amount`), and a
 * MIDI `stackFxAmount` binding moves it too, so this frame is what keeps every slider honest.
 */
export interface StackFxAmountState {
  projectId: number | null
  /** Stack id → amount, for every stack below full. */
  amounts: Record<number, number>
}

export const STACK_FX_AMOUNT_FULL = 1

export const ALL_FULL: StackFxAmountState = { projectId: null, amounts: {} }

/** A `stackFxAmount.state` frame; a missing list is every stack at full. */
export function parseStackFxAmountFrame(raw: unknown): StackFxAmountState {
  if (raw == null || typeof raw !== 'object') return ALL_FULL
  const r = raw as Record<string, unknown>
  const amounts: Record<number, number> = {}
  if (Array.isArray(r.stacks)) {
    for (const entry of r.stacks) {
      const e = entry as Record<string, unknown> | null
      if (e != null && typeof e.stackId === 'number' && typeof e.amount === 'number') amounts[e.stackId] = e.amount
    }
  }
  return { projectId: typeof r.projectId === 'number' ? r.projectId : null, amounts }
}

/** [stackId]'s amount in [state], full when it is not listed. */
export function stackFxAmountOf(state: StackFxAmountState, stackId: number): number {
  return state.amounts[stackId] ?? STACK_FX_AMOUNT_FULL
}

export interface StackFxAmountWsApi {
  /** The amounts on every change; a late subscriber gets the last frame at once. */
  subscribe(fn: (state: StackFxAmountState) => void): Subscription
  /** The last frame, or [ALL_FULL] before the first. */
  get(): StackFxAmountState
  /** Set one of the live project's stacks' amount, 0–1. Toasts when the desk is unreachable. */
  set(stackId: number, amount: number): void
}

export function createStackFxAmountWsApi(conn: InternalApiConnection): StackFxAmountWsApi {
  const state = createWsSubscribable<StackFxAmountState>()
  let last: StackFxAmountState = ALL_FULL
  let seen = false

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== InternalEventType.message) return
    const message = frame as { type?: string } | null
    if (message?.type !== 'stackFxAmount.state') return
    last = parseStackFxAmountFrame(message)
    seen = true
    state.notify(last)
  })

  return {
    subscribe: (fn) => {
      const sub = state.api.subscribe(fn)
      if (seen) fn(last)
      return sub
    },
    get: () => last,
    set: (stackId, amount) => {
      sendGesture(conn, { type: 'stackFxAmount.set', stackId, amount })
    },
  }
}
