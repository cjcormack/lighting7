import { describe, expect, it } from 'vitest'
import { inGroupOrder, placeInOrder } from './memberOrder'

const id = (s: string) => s

describe('placeInOrder', () => {
  it('fills the named members’ places in the order given and leaves the rest where they are', () => {
    expect(placeInOrder(['a', 'b', 'c', 'd'], ['d', 'b'], id)).toEqual(['a', 'd', 'c', 'b'])
  })

  it('ignores a key that is not a member, and a repeat', () => {
    expect(placeInOrder(['a', 'b', 'c'], ['c', 'x', 'c', 'a'], id)).toEqual(['c', 'b', 'a'])
  })

  it('reorders a whole list', () => {
    expect(placeInOrder(['a', 'b', 'c'], ['b', 'c', 'a'], id)).toEqual(['b', 'c', 'a'])
  })

  it('keeps the items themselves, keyed', () => {
    const items = [{ k: 'a', n: 1 }, { k: 'b', n: 2 }]
    expect(placeInOrder(items, ['b', 'a'], (i) => i.k)).toEqual([{ k: 'b', n: 2 }, { k: 'a', n: 1 }])
  })
})

describe('inGroupOrder', () => {
  it('orders the keys by the group’s own order, an unnamed key after', () => {
    expect(inGroupOrder(['a', 'b', 'c', 'x'], ['c', 'a', 'b'])).toEqual(['c', 'a', 'b', 'x'])
  })

  it('leaves the keys as they are with no order', () => {
    expect(inGroupOrder(['b', 'a'], undefined)).toEqual(['b', 'a'])
    expect(inGroupOrder(['b', 'a'], [])).toEqual(['b', 'a'])
  })
})
