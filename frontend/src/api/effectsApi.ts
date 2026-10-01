import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'

/**
 * One-shot effects — the desk's **arm**, every **fire** and the **spent tubes** (stage-view plan
 * session 9, D15, D16; lighting7 `state/EffectsService.kt` and `plugins/EffectsSocket.kt` are the
 * wire contract).
 *
 * A confetti cannon's tubes are **one-shot triggers**: they spend something physical, so they are
 * never a value — no Look, template, cue row, effect or programmer value can hold one. A tube fires
 * as an event — a cue's Events on GO, the cannon's hold-to-fire button, a MIDI `FireTrigger` — and
 * only while the desk is **armed**. Arming is desk-wide, lapses (60 s by default), and drops on a
 * stack stop or a project switch.
 *
 * Three outbound frames and no inbound (P2): arm, fire and reload are REST (`store/effects.ts`).
 * - `effects.armed` — a `StateFlow`, so the subscription is the connect snapshot: the arm, how
 *   long it has left **when the frame was sent** (`remainingMs`, anchored here to this browser's
 *   clock, as a cue's fade is), whether the programmer is blind (every fire rehearsed), and every
 *   spent tube on this machine.
 * - `effects.fired` — one tube fired, or **rehearsed** (blind, or a window whose vis source is the
 *   programmer): every window draws the burst; a rehearsal sent nothing.
 * - `effects.skipped` — fires that did not happen, said so: a cue's events on an unarmed desk, a
 *   spent tube, an arm that dropped before an event's offset. Never queued.
 */

export interface SpentTube {
  fixture: string
  trigger: string
  spentAt: string
}

/** The desk's arm, anchored to **this** browser's clock (`performance.now()`). */
export interface EffectsArmState {
  armed: boolean
  /** On `performance.now()`'s timeline; null when disarmed. */
  armedUntilMs: number | null
  /** The programmer is blind: every fire is rehearsed, and the cannons' arm channels are held down. */
  rehearsal: boolean
  spent: SpentTube[]
  projectId: number | null
}

export const DISARMED: EffectsArmState = { armed: false, armedUntilMs: null, rehearsal: false, spent: [], projectId: null }

export interface EffectsFired {
  fixture: string
  fixtureName: string
  trigger: string
  label: string
  at: string
  rehearsed: boolean
  source: 'cue' | 'panel' | 'surface'
  cueId: number | null
}

export interface EffectsSkipped {
  reason: 'UNARMED' | 'SPENT' | 'ARM_DROPPED' | 'UNKNOWN_TRIGGER' | string
  message: string
  tubes: { fixture: string; trigger: string }[]
  source: string
  cueId: number | null
  cueLabel: string | null
}

/** An `effects.armed` frame, its countdown anchored at [receivedAtMs] (`performance.now()`). */
export function parseArmedFrame(raw: unknown, receivedAtMs: number): EffectsArmState {
  if (raw == null || typeof raw !== 'object') return DISARMED
  const r = raw as Record<string, unknown>
  // The desk's Json drops defaults, so a missing field is its zero.
  const armed = r.armed === true
  const remaining = typeof r.remainingMs === 'number' ? Math.max(0, r.remainingMs) : null
  const spent: SpentTube[] = []
  for (const item of Array.isArray(r.spent) ? r.spent : []) {
    if (item == null || typeof item !== 'object') continue
    const t = item as Record<string, unknown>
    if (typeof t.fixture === 'string' && typeof t.trigger === 'string') {
      spent.push({ fixture: t.fixture, trigger: t.trigger, spentAt: typeof t.spentAt === 'string' ? t.spentAt : '' })
    }
  }
  return {
    armed,
    armedUntilMs: armed && remaining != null ? receivedAtMs + remaining : null,
    rehearsal: r.rehearsal === true,
    spent,
    projectId: typeof r.projectId === 'number' ? r.projectId : null,
  }
}

export function parseFiredFrame(raw: unknown): EffectsFired | null {
  if (raw == null || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  if (typeof r.fixture !== 'string' || typeof r.trigger !== 'string') return null
  return {
    fixture: r.fixture,
    fixtureName: typeof r.fixtureName === 'string' ? r.fixtureName : r.fixture,
    trigger: r.trigger,
    label: typeof r.label === 'string' ? r.label : r.trigger,
    at: typeof r.at === 'string' ? r.at : '',
    rehearsed: r.rehearsed === true,
    source: r.source === 'cue' || r.source === 'surface' ? r.source : 'panel',
    cueId: typeof r.cueId === 'number' ? r.cueId : null,
  }
}

export function parseSkippedFrame(raw: unknown): EffectsSkipped | null {
  if (raw == null || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  if (typeof r.message !== 'string') return null
  const tubes = (Array.isArray(r.tubes) ? r.tubes : []).flatMap((t) => {
    if (t == null || typeof t !== 'object') return []
    const o = t as Record<string, unknown>
    return typeof o.fixture === 'string' && typeof o.trigger === 'string' ? [{ fixture: o.fixture, trigger: o.trigger }] : []
  })
  return {
    reason: typeof r.reason === 'string' ? r.reason : 'UNARMED',
    message: r.message,
    tubes,
    source: typeof r.source === 'string' ? r.source : 'cue',
    cueId: typeof r.cueId === 'number' ? r.cueId : null,
    cueLabel: typeof r.cueLabel === 'string' ? r.cueLabel : null,
  }
}

/** Whether [fixture]'s [trigger] is spent, per the last `effects.armed` frame. */
export function spentAt(state: EffectsArmState, fixture: string, trigger: string): string | null {
  return state.spent.find((t) => t.fixture === fixture && t.trigger === trigger)?.spentAt ?? null
}

export interface EffectsWsApi {
  /** The arm on every change. The desk pushes it on connect, so a late subscriber gets the last one. */
  subscribeArmed(fn: (state: EffectsArmState) => void): Subscription
  /** The last arm frame, or [DISARMED] before the first. */
  getArmed(): EffectsArmState
  /** Every fire, real or rehearsed. Not replayed: a window that connects after a fire has nothing to draw. */
  subscribeFired(fn: (fired: EffectsFired) => void): Subscription
  /** Every announced skip. Not replayed. */
  subscribeSkipped(fn: (skipped: EffectsSkipped) => void): Subscription
}

export function createEffectsWsApi(conn: InternalApiConnection): EffectsWsApi {
  const armed = createWsSubscribable<EffectsArmState>()
  const fired = createWsSubscribable<EffectsFired>()
  const skipped = createWsSubscribable<EffectsSkipped>()
  let last: EffectsArmState = DISARMED
  let seen = false

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== InternalEventType.message) return
    const message = frame as { type?: string } | null
    switch (message?.type) {
      case 'effects.armed':
        last = parseArmedFrame(message, performance.now())
        seen = true
        armed.notify(last)
        break
      case 'effects.fired': {
        const f = parseFiredFrame(message)
        if (f != null) fired.notify(f)
        break
      }
      case 'effects.skipped': {
        const s = parseSkippedFrame(message)
        if (s != null) skipped.notify(s)
        break
      }
    }
  })

  return {
    subscribeArmed: (fn) => {
      const sub = armed.api.subscribe(fn)
      if (seen) fn(last)
      return sub
    },
    getArmed: () => last,
    subscribeFired: (fn) => fired.api.subscribe(fn),
    subscribeSkipped: (fn) => skipped.api.subscribe(fn),
  }
}
