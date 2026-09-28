// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { handWs } from '@/test/backendMock'

// lightingApi opens a real WebSocket at import time (jsdom has none). The mock's `hand` namespace
// remembers the subscriber and every pick-up and drop, with the drop's argument.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

const toasts: [string, Record<string, unknown>][] = []
vi.mock('sonner', () => ({
  toast: Object.assign((...a: unknown[]) => toasts.push(a as never), {
    success: (...a: unknown[]) => toasts.push(a as never),
    error: (...a: unknown[]) => toasts.push(a as never),
    warning: (...a: unknown[]) => toasts.push(a as never),
  }),
}))

import type { HeldRecord } from '@/api/handApi'
import { HAND_UNDO_MS, handDrop, handPickUp, heldName, useHandPlace } from './hand'

/**
 * The desk's hand, from the placing window's side (multi-screen plan §3.5, D12).
 *
 * The load-bearing assertion in this file is that a place sends `hand.drop` **naming the record it
 * placed**. A place is two independent round-trips — this window's mutation, then the drop — and
 * another window may have picked something up in the gap, so a bare drop would clear an item this
 * window never touched. It is invisible in one window, which is why a test that accepted a bare
 * drop after a place would pass while leaving the two-screen bug in.
 */
const held = (over: Partial<HeldRecord> = {}): HeldRecord => ({
  kind: 'LOOK',
  id: 4,
  uuid: 'look-uuid-4',
  template: null,
  look: { name: 'Warm Wash' } as HeldRecord['look'],
  cue: null,
  pickedUpOn: null,
  holdId: 1,
  pickedUpAtMs: 0,
  expiresAtMs: 0,
  ...over,
})

beforeEach(() => {
  handWs.reset()
  toasts.length = 0
})
afterEach(() => vi.restoreAllMocks())

describe('pick up and let go', () => {
  it('sends hand.pickUp with the kind and the record id', () => {
    handPickUp('TEMPLATE', 12)
    expect(handWs.pickedUp).toEqual([{ kind: 'TEMPLATE', id: 12 }])
  })

  it('sends a BARE hand.drop for the chip and Escape — "whatever is there" is what they mean', () => {
    handDrop()
    expect(handWs.dropped).toEqual([undefined])
  })
})

describe('a place is the window’s own mutation, then a guarded drop', () => {
  it('runs the mutation first, then drops NAMING the placed record', async () => {
    const order: string[] = []
    const { result } = renderHook(() => useHandPlace())
    const record = held()

    await act(async () => {
      await result.current(record, {
        where: 'Colours',
        run: async () => {
          order.push('mutation')
          return { id: 9 }
        },
      })
    })

    order.push(...handWs.dropped.map(() => 'drop'))
    expect(order).toEqual(['mutation', 'drop'])
    // The whole point of the frame's optional field: the uuid, not `undefined`.
    expect(handWs.dropped).toEqual(['look-uuid-4'])
  })

  it('does NOT drop when the mutation rejects — the operator still has the item', async () => {
    const { result } = renderHook(() => useHandPlace())
    await act(async () => {
      await result.current(held(), {
        where: 'Colours',
        run: () => Promise.reject(new Error('400')),
      })
    })
    expect(handWs.dropped).toEqual([])
    expect(toasts).toHaveLength(0)
  })

  it('does not drop when the mutation answers null — nothing landed', async () => {
    const { result } = renderHook(() => useHandPlace())
    await act(async () => {
      await result.current(held(), { where: 'Colours', run: async () => null })
    })
    expect(handWs.dropped).toEqual([])
  })

  it('offers Undo for ten seconds, and running it calls the inverse with the mutation’s result', async () => {
    const undo = vi.fn()
    const { result } = renderHook(() => useHandPlace())

    await act(async () => {
      await result.current(held(), {
        where: 'Colours',
        run: async () => ({ padId: 31 }),
        undo,
      })
    })

    expect(toasts).toHaveLength(1)
    const [message, options] = toasts[0]!
    expect(message).toBe('“Warm Wash” placed in Colours')
    expect(options.duration).toBe(HAND_UNDO_MS)
    const action = options.action as { label: string; onClick: () => void }
    expect(action.label).toBe('Undo')

    action.onClick()
    // The *result* of the place, so an inverse can address what the mutation just made.
    expect(undo).toHaveBeenCalledWith({ padId: 31 })
  })

  it('raises no Undo action where the window cannot address what it made', async () => {
    const { result } = renderHook(() => useHandPlace())
    await act(async () => {
      await result.current(held(), { where: 'the programmer', run: async () => true })
    })
    expect(toasts).toHaveLength(1)
    expect(toasts[0]![1].action).toBeUndefined()
    // The drop still names the record: the guard is about the *place*, not about Undo.
    expect(handWs.dropped).toEqual(['look-uuid-4'])
  })

  it('does not put the record back in the hand when Undo runs', async () => {
    const { result } = renderHook(() => useHandPlace())
    await act(async () => {
      await result.current(held(), { where: 'Colours', run: async () => 1, undo: () => {} })
    })
    ;(toasts[0]![1].action as { onClick: () => void }).onClick()
    expect(handWs.pickedUp).toEqual([])
  })
})

describe('heldName', () => {
  it('reads the summary the frame carries, per kind', () => {
    expect(heldName(held())).toBe('Warm Wash')
    expect(
      heldName(held({ kind: 'TEMPLATE', look: null, template: { name: 'Amber' } as never })),
    ).toBe('Amber')
    expect(heldName(held({ kind: 'CUE', look: null, cue: { name: 'Blackout' } as never }))).toBe(
      'Blackout',
    )
  })

  it('degrades rather than throwing when the summary is missing for its kind', () => {
    expect(heldName(held({ look: null }))).toBe('this Look')
  })
})
