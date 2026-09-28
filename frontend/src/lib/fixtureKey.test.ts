import { describe, expect, it } from 'vitest'
import { checkKeyLanding, spreadKeys, parseKeySuffix, planKeyWrites } from './fixtureKey'

/**
 * The patch list's key scheme (CLAUDE.md §Sheet kit) — the arithmetic behind "re-key the selection
 * from this one", and the order the PUTs have to go out in.
 */
describe('parseKeySuffix', () => {
  it('reads the trailing number, its separator and its padding', () => {
    expect(parseKeySuffix('par-1')).toEqual({ base: 'par', separator: '-', number: 1, width: 1 })
    expect(parseKeySuffix('par_07')).toEqual({ base: 'par', separator: '_', number: 7, width: 2 })
    expect(parseKeySuffix('par 12')).toEqual({ base: 'par', separator: ' ', number: 12, width: 2 })
    expect(parseKeySuffix('par1')).toEqual({ base: 'par', separator: '', number: 1, width: 1 })
  })

  it('takes the LAST run of digits, so an interior number is part of the base', () => {
    expect(parseKeySuffix('foh-2b-10')).toEqual({ base: 'foh-2b', separator: '-', number: 10, width: 2 })
    expect(parseKeySuffix('led-lightbar-12-pixel')).toBeNull()
  })
})

describe('spreadKeys', () => {
  it('leaves one head with exactly the typed key — re-keying one fixture is a rename', () => {
    expect(spreadKeys('house', 1)).toEqual(['house'])
    expect(spreadKeys('par-4', 1)).toEqual(['par-4'])
  })

  it('continues the typed number, keeping its separator and its padding', () => {
    expect(spreadKeys('par-3', 4)).toEqual(['par-3', 'par-4', 'par-5', 'par-6'])
    expect(spreadKeys('par_08', 3)).toEqual(['par_08', 'par_09', 'par_10'])
    expect(spreadKeys('par1', 3)).toEqual(['par1', 'par2', 'par3'])
  })

  it('appends `-1`, `-2`, … to a key with no number of its own', () => {
    expect(spreadKeys('foh', 3)).toEqual(['foh-1', 'foh-2', 'foh-3'])
  })

  it('outgrows the padding rather than wrapping inside it', () => {
    expect(spreadKeys('par-09', 3)).toEqual(['par-09', 'par-10', 'par-11'])
  })
})

const head = (id: number, key: string, name = `Head ${id}`) => ({ id, key, name })
const rig = (...heads: { id: number; key: string; name: string }[]) =>
  heads.map((h) => ({ id: h.id, key: h.key, displayName: h.name }))

describe('checkKeyLanding', () => {
  const all = rig(head(1, 'par-1'), head(2, 'par-2'), head(3, 'spot-1', 'Spot 1'))

  it('names a key another head holds, and says whose', () => {
    const batch = [head(1, 'par-1'), head(2, 'par-2')]
    const landing = checkKeyLanding(batch, spreadKeys('spot-1', 2), all)
    expect(landing.error).toBe("“spot-1” is already Spot 1's key")
    expect(landing.lines).toEqual(['Head 1 → spot-1', 'Head 2 → spot-2'])
  })

  it('allows a key a member of the batch itself holds — that is an ordering problem, not a clash', () => {
    const batch = [head(1, 'par-1'), head(2, 'par-2')]
    const landing = checkKeyLanding(batch, spreadKeys('par-2', 2), all)
    expect(landing.error).toBeNull()
    // It carries the writes it had to plan to answer, so the caller never re-plans them.
    expect(landing.writes).toEqual([
      { id: 2, key: 'par-3' },
      { id: 1, key: 'par-2' },
    ])
  })

  it('carries no writes whenever it carries an error', () => {
    const batch = [head(1, 'par-1'), head(2, 'par-2')]
    expect(checkKeyLanding(batch, spreadKeys('spot-1', 2), all).writes).toBeNull()
    expect(checkKeyLanding([head(1, 'par-1')], [''], all).writes).toBeNull()
    const swap = [head(1, 'par-2'), head(2, 'par-1')]
    expect(checkKeyLanding(swap, ['par-1', 'par-2'], rig(head(1, 'par-2'), head(2, 'par-1'))).writes).toBeNull()
  })

  it('refuses an empty key and says nothing about a single head', () => {
    expect(checkKeyLanding([head(1, 'par-1')], [''], all).error).toBe('A key cannot be empty')
    expect(checkKeyLanding([head(1, 'par-1')], ['par-9'], all).lines).toEqual([])
  })
})

describe('planKeyWrites', () => {
  it('writes in an order where every target is free when it is written', () => {
    const batch = [head(1, 'par-1'), head(2, 'par-2'), head(3, 'par-3')]
    const steps = planKeyWrites(batch, spreadKeys('par-2', 3), ['par-1', 'par-2', 'par-3'])
    // Walking up would put `par-2` on head 1 while head 2 still holds it, and the PUT refuses a
    // duplicate — so it walks down instead.
    expect(steps).toEqual([
      { id: 3, key: 'par-4' },
      { id: 2, key: 'par-3' },
      { id: 1, key: 'par-2' },
    ])
  })

  it('skips a head whose key is already what it should be', () => {
    const batch = [head(1, 'par-1'), head(2, 'other')]
    expect(planKeyWrites(batch, ['par-1', 'par-2'], ['par-1', 'other'])).toEqual([
      { id: 2, key: 'par-2' },
    ])
  })

  it('refuses a swap rather than parking a head on a synthetic key', () => {
    // The visible order is not the key order, so the two targets are each other's current keys and
    // no order of independent PUTs can land them.
    const batch = [head(1, 'par-2'), head(2, 'par-1')]
    expect(planKeyWrites(batch, ['par-1', 'par-2'], ['par-1', 'par-2'])).toBeNull()
    // And the operator is told before Apply, rather than after a half-applied batch.
    const all = rig(head(1, 'par-2'), head(2, 'par-1'))
    expect(checkKeyLanding(batch, ['par-1', 'par-2'], all).error).toMatch(/would swap keys/)
  })

  it('every step it does return writes a key that is free at that moment', () => {
    const batch = [head(1, 'par-1'), head(2, 'par-2'), head(3, 'par-3')]
    const steps = planKeyWrites(batch, spreadKeys('par-2', 3), ['par-1', 'par-2', 'par-3'])!
    const taken = new Set(['par-1', 'par-2', 'par-3'])
    const holds = new Map(batch.map((h) => [h.id, h.key]))
    for (const step of steps) {
      expect(taken.has(step.key)).toBe(false)
      taken.delete(holds.get(step.id)!)
      taken.add(step.key)
      holds.set(step.id, step.key)
    }
  })
})
