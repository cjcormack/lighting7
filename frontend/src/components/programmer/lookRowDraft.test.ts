import { describe, expect, it, vi } from 'vitest'
import { LookRowDraft } from './lookRowDraft'
import { lookRowKey } from './lookRowKey'
import type { LookRow } from '@/api/looksApi'

const row = (over: Partial<LookRow>): LookRow => ({
  targetType: 'fixture',
  targetKey: 'a',
  propertyName: 'dimmer',
  value: '128',
  ...over,
})

describe('LookRowDraft', () => {
  it('overlays a pending value and reports it', () => {
    const draft = new LookRowDraft()
    draft.set('a', 'dimmer', '200')
    expect(draft.get('a', 'dimmer')).toBe('200')
    expect(draft.size).toBe(1)
  })

  it('wakes only the listeners for the key that moved', () => {
    // The reason this is per-key rather than one version counter: a cell drag commits at ~30 Hz,
    // and every visible row repainting on each tick is the cost `useRowOwnership` splits its own
    // subscriptions to avoid.
    const draft = new LookRowDraft()
    const dimmer = vi.fn()
    const colour = vi.fn()
    draft.subscribe([lookRowKey('a', 'dimmer')], dimmer)
    draft.subscribe([lookRowKey('a', 'rgbColour')], colour)

    draft.set('a', 'dimmer', '200')
    expect(dimmer).toHaveBeenCalledTimes(1)
    expect(colour).not.toHaveBeenCalled()
  })

  it('does not wake anyone for a write that changes nothing', () => {
    const draft = new LookRowDraft()
    const listener = vi.fn()
    draft.subscribe([lookRowKey('a', 'dimmer')], listener)
    draft.set('a', 'dimmer', '200')
    draft.set('a', 'dimmer', '200')
    expect(listener).toHaveBeenCalledTimes(1)
  })

  it('stops overlaying once the server states the same value', () => {
    // Retired on agreement rather than cleared on a successful save: between the PUT resolving and
    // the refetch landing the cache still holds the old rows, and dropping the overlay in that
    // window would flash the previous value.
    const draft = new LookRowDraft()
    draft.set('a', 'dimmer', '200')
    draft.reconcile(new Map([[lookRowKey('a', 'dimmer'), '199']]))
    expect(draft.get('a', 'dimmer')).toBe('200')

    draft.reconcile(new Map([[lookRowKey('a', 'dimmer'), '200']]))
    expect(draft.get('a', 'dimmer')).toBeUndefined()
    expect(draft.size).toBe(0)
  })

  it('lets another desk through once our own edit has landed', () => {
    // The corollary of the above, and the reason a permanently-sticky overlay is wrong: after
    // reconciling, a value someone else wrote is what the grid must show.
    const draft = new LookRowDraft()
    draft.set('a', 'dimmer', '200')
    draft.reconcile(new Map([[lookRowKey('a', 'dimmer'), '200']]))
    draft.reconcile(new Map([[lookRowKey('a', 'dimmer'), '10']]))
    expect(draft.get('a', 'dimmer')).toBeUndefined()
  })

  describe('applyTo', () => {
    it('replaces a matching fixture row in place, keeping its other fields', () => {
      const draft = new LookRowDraft()
      draft.set('a', 'dimmer', '200')
      const out = draft.applyTo([row({ fadeDurationMs: 1500, sortOrder: 3 })])
      expect(out).toEqual([
        { targetType: 'fixture', targetKey: 'a', propertyName: 'dimmer', value: '200', fadeDurationMs: 1500, sortOrder: 3 },
      ])
    })

    it('adds a fixture row where the Look had none', () => {
      const draft = new LookRowDraft()
      draft.set('b', 'dimmer', '64')
      const out = draft.applyTo([row({})])
      expect(out).toHaveLength(2)
      expect(out[1]).toEqual({
        targetType: 'fixture',
        targetKey: 'b',
        propertyName: 'dimmer',
        value: '64',
      })
    })

    it('adds a fixture row beside a group row rather than editing the group', () => {
      // The specificity the display already applies: the cell the operator was looking at showed
      // the group's value, and the row this writes is the one that will win for that head. The
      // group row stays, because the layer's *other* members still take it.
      const draft = new LookRowDraft()
      draft.set('a', 'dimmer', '200')
      const out = draft.applyTo([row({ targetType: 'group', targetKey: 'Washes' })])
      expect(out).toHaveLength(2)
      expect(out[0]).toMatchObject({ targetType: 'group', targetKey: 'Washes', value: '128' })
      expect(out[1]).toMatchObject({ targetType: 'fixture', targetKey: 'a', value: '200' })
    })

    it('leaves element rows alone', () => {
      // Element rows compose nowhere (`FU-LOOK-ELEMENT-ROWS`) and the grid renders them inert, so
      // a save must carry them through untouched rather than quietly rewriting one.
      const draft = new LookRowDraft()
      draft.set('a', 'dimmer', '200')
      const out = draft.applyTo([row({ elementKey: 'pixel-0' })])
      expect(out).toHaveLength(2)
      expect(out[0]).toMatchObject({ elementKey: 'pixel-0', value: '128' })
    })

    it('handles a fixture key containing the separator character gracefully', () => {
      // Keys are NUL-joined precisely so a head named "Front Left" round-trips.
      const draft = new LookRowDraft()
      draft.set('Front Left', 'dimmer', '77')
      const out = draft.applyTo([])
      expect(out).toEqual([
        { targetType: 'fixture', targetKey: 'Front Left', propertyName: 'dimmer', value: '77' },
      ])
    })

    it('builds from the rows it is given, not a snapshot', () => {
      // `PUT /looks/{id}` replaces the whole array, so a base captured when the drag began would
      // resend another operator's rows as we last happened to see them.
      const draft = new LookRowDraft()
      draft.set('a', 'dimmer', '200')
      const withTheirRow = draft.applyTo([row({}), row({ targetKey: 'theirs' })])
      expect(withTheirRow.map((r) => r.targetKey)).toEqual(['a', 'theirs'])
    })
  })
})
