import { describe, expect, it } from 'vitest'
import type { ProgrammerEntry } from '@/api/programmerWsApi'
import { diffAgainstBaseline, snapshotEntries, withSceneryChanges } from './includeBaseline'

const snap = (o: Record<string, string>) => new Map(Object.entries(o))

describe('diffAgainstBaseline', () => {
  it('counts added, removed and changed values', () => {
    const before = snap({ a: '1', b: '2', gone: '3' })
    const after = snap({ a: '1', b: '9', added: '4' })
    // b changed, `added` added, `gone` removed. `a` is untouched.
    expect(diffAgainstBaseline(before, after)).toBe(3)
  })

  it('is 0 when nothing moved', () => {
    const before = snap({ a: '1' })
    expect(diffAgainstBaseline(before, snap({ a: '1' }))).toBe(0)
  })

  it('is null — NOT 0 — with no baseline', () => {
    // The distinction is the load-bearing one: `0` licenses an "in sync" badge, `null` must not.
    // A tab that reloaded mid-edit, or opened after the Include, has no baseline and cannot know.
    expect(diffAgainstBaseline(null, snap({ a: '1' }))).toBeNull()
    expect(diffAgainstBaseline(null, snap({}))).toBeNull()
  })
})

describe('snapshotEntries', () => {
  it('keeps only the value, keyed as the programmer keys it', () => {
    const entry = (value: string): ProgrammerEntry => ({
      targetKey: 'f:1',
      propertyName: 'dimmer',
      value,
      owner: 'web',
      touched: true,
      owners: ['web'],
    })
    const result = snapshotEntries(new Map([['f:1|dimmer', entry('255')]]))
    expect([...result]).toEqual([['f:1|dimmer', '255']])
  })
})

describe('withSceneryChanges', () => {
  it('adds the held scenery Update would write, so moving the tabs after Include un-greys Update', () => {
    expect(withSceneryChanges(0, 1)).toBe(1)
    expect(withSceneryChanges(3, 2)).toBe(5)
  })

  it('adds nothing for an absent count, and never turns an unknown value count into a number', () => {
    expect(withSceneryChanges(0, undefined)).toBe(0)
    expect(withSceneryChanges(null, 2)).toBeNull()
  })
})
