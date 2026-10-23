import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'
import { sendGesture } from './wsGesture'

/**
 * Each cue stack's **effect size** (lighting7 effects plan D10; `plugins/StackEffectSizeSocket.kt`
 * is the wire contract): a runtime weight, 0–1, on the effects that stack's cues run, which the
 * desk applies after the blend — 50 % puts a stack's chase halfway over what is underneath. 100 %
 * at load, dropped on a project switch, kept by a reload; never stored.
 *
 * - `stackEffectSize.state` `{projectId, stacks: [{stackId, size}]}` — every stack below full; a
 *   stack absent is at 100 %. A `StateFlow`, so the subscription is the connect snapshot and every
 *   change is a whole frame.
 * - `stackEffectSize.set` `{stackId, size}` — the *FX amount* slider, at frame rate.
 *
 * REST reads and sets the same value (`GET/PUT projects/{id}/cue-stacks/{sid}/effect-size`), and a
 * MIDI `stackEffectSize` binding moves it too, so this frame is what keeps every slider honest.
 */
export interface StackEffectSizeState {
  projectId: number | null
  /** Stack id → size, for every stack below full. */
  sizes: Record<number, number>
}

export const STACK_EFFECT_SIZE_FULL = 1

export const ALL_FULL: StackEffectSizeState = { projectId: null, sizes: {} }

/** A `stackEffectSize.state` frame; a missing list is every stack at full. */
export function parseStackEffectSizeFrame(raw: unknown): StackEffectSizeState {
  if (raw == null || typeof raw !== 'object') return ALL_FULL
  const r = raw as Record<string, unknown>
  const sizes: Record<number, number> = {}
  if (Array.isArray(r.stacks)) {
    for (const entry of r.stacks) {
      const e = entry as Record<string, unknown> | null
      if (e != null && typeof e.stackId === 'number' && typeof e.size === 'number') sizes[e.stackId] = e.size
    }
  }
  return { projectId: typeof r.projectId === 'number' ? r.projectId : null, sizes }
}

/** [stackId]'s size in [state], full when it is not listed. */
export function stackEffectSizeOf(state: StackEffectSizeState, stackId: number): number {
  return state.sizes[stackId] ?? STACK_EFFECT_SIZE_FULL
}

export interface StackEffectSizeWsApi {
  /** The sizes on every change; a late subscriber gets the last frame at once. */
  subscribe(fn: (state: StackEffectSizeState) => void): Subscription
  /** The last frame, or [ALL_FULL] before the first. */
  get(): StackEffectSizeState
  /** Set one of the live project's stacks' size, 0–1. Toasts when the desk is unreachable. */
  set(stackId: number, size: number): void
}

export function createStackEffectSizeWsApi(conn: InternalApiConnection): StackEffectSizeWsApi {
  const state = createWsSubscribable<StackEffectSizeState>()
  let last: StackEffectSizeState = ALL_FULL
  let seen = false

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== InternalEventType.message) return
    const message = frame as { type?: string } | null
    if (message?.type !== 'stackEffectSize.state') return
    last = parseStackEffectSizeFrame(message)
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
    set: (stackId, size) => {
      sendGesture(conn, { type: 'stackEffectSize.set', stackId, size })
    },
  }
}
