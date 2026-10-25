import { describe, expect, it } from 'vitest'
import { chan, makeActiveFx, makeFixture, sliderProp } from '@/test/fixtureFactories'
import { fxPaintedHeads } from '@/components/fixtures-list/cellFx'
import { fxReaches, splitScopeOf } from './fxSplit'

/**
 * The split editor's reading of which heads an edit is for (apply-model plan §3.4, D5): only a Local
 * effect, and only a strict subset of its heads, offers a split; everything else is edited in place.
 */
const HEX = [1, 2, 3, 4].map((n) =>
  makeFixture(`hex-${n}`, [sliderProp('dimmer', 'dimmer', chan(n))], { name: `Hex ${n}`, groups: ['front'] }),
)
const FRONT = makeActiveFx({ id: 7, targetKey: 'front', isGroupTarget: true, targetType: 'group' })
const fixture = (key: string) => ({ type: 'fixture' as const, key })

describe('splitScopeOf', () => {
  it('offers Hex 1–2 of Hex 1–4 for a Local group effect with two of its heads selected', () => {
    const scope = splitScopeOf(FRONT, [fixture('hex-1'), fixture('hex-2')], HEX)
    expect(scope.covered).toEqual(['hex-1', 'hex-2'])
    expect(scope.coveredLabel).toBe('Hex 1–2')
    expect(scope.totalLabel).toBe('Hex 1–4')
  })

  it('edits in place with every head selected, with none, or with the group itself', () => {
    expect(splitScopeOf(FRONT, HEX.map((f) => fixture(f.key)), HEX).covered).toBeNull()
    expect(splitScopeOf(FRONT, [fixture('par-9')], HEX).covered).toBeNull()
    expect(splitScopeOf(FRONT, [{ type: 'group', key: 'front' }], HEX).covered).toBeNull()
    expect(splitScopeOf(FRONT, [], HEX).covered).toBeNull()
  })

  it('offers no split for a palette layer’s effect, a cue’s, or a manual one', () => {
    const subset = [fixture('hex-1')]
    expect(splitScopeOf({ ...FRONT, paletteId: 3, programmerLayerId: 2 }, subset, HEX).covered).toBeNull()
    expect(splitScopeOf({ ...FRONT, programmerOwned: false, cueId: 12 }, subset, HEX).covered).toBeNull()
    expect(splitScopeOf({ ...FRONT, programmerOwned: false }, subset, HEX).covered).toBeNull()
  })

  it('reads a selection effect’s heads in its order, and a fixture’s cells where its property lives on them', () => {
    const bar = makeFixture('bar', [], {
      name: 'Bar',
      elements: [1, 2, 3, 4].map((n) => ({
        key: `bar.${n}`,
        displayName: `Cell ${n}`,
        index: n - 1,
        attributes: [sliderProp('dimmer', 'dimmer', chan(20 + n))],
      })) as never,
    })
    const onBar = makeActiveFx({ targetKey: 'bar', elementFilter: 'ODD' })
    expect(fxPaintedHeads(onBar, [bar])).toEqual(['bar.1', 'bar.3'])
    const selection = makeActiveFx({
      targetType: 'selection',
      targetKey: '[…]',
      targets: [fixture('hex-3'), fixture('hex-1')],
    })
    expect(fxPaintedHeads(selection, HEX)).toEqual(['hex-3', 'hex-1'])
    expect(splitScopeOf(onBar, [fixture('bar.1')], [bar]).covered).toEqual(['bar.1'])
  })

  it('reads a group as the desk does: FLAT filters across the strip, and a member carrying nothing paints nothing', () => {
    const bar = (n: number) =>
      makeFixture(`bar${n}`, [], {
        name: `Bar ${n}`,
        groups: ['bars'],
        elements: [1, 2, 3].map((c) => ({
          key: `bar${n}.${c}`,
          displayName: `Cell ${c}`,
          index: c - 1,
          attributes: [sliderProp('dimmer', 'dimmer', chan(40 + n * 3 + c))],
        })) as never,
      })
    const bars = [bar(1), bar(2)]
    const onBars = makeActiveFx({ targetKey: 'bars', isGroupTarget: true, targetType: 'group', elementFilter: 'ODD' })
    expect(fxPaintedHeads({ ...onBars, elementMode: 'FLAT' }, bars)).toEqual(['bar1.1', 'bar1.3', 'bar2.2'])
    expect(fxPaintedHeads({ ...onBars, elementMode: 'PER_FIXTURE' }, bars)).toEqual(['bar1.1', 'bar1.3', 'bar2.1', 'bar2.3'])
    const dark = makeFixture('dark', [], { name: 'Dark', groups: ['front'] })
    expect(fxPaintedHeads(FRONT, [...HEX, dark])).toEqual(['hex-1', 'hex-2', 'hex-3', 'hex-4'])
  })

  it('says an effect reaches the heads a selection covers', () => {
    expect(fxReaches(FRONT, [fixture('hex-2')], HEX)).toBe(true)
    expect(fxReaches(FRONT, [fixture('par-9')], HEX)).toBe(false)
  })
})
