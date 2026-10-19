import { describe, expect, it } from 'vitest'
import { colourFromGroupLabel, colourFromGroupNote, colourFromGroupRefusal, planColourFromGroup } from './colourFromGroup'
import { filterPatches } from './patchFilter'
import type { FixturePatch } from '@/api/patchApi'

const groups = new Map([
  [1, { name: 'Front', colour: 'red' as const }],
  [2, { name: 'Side', colour: 'cyan' as const }],
  [3, { name: 'Plain', colour: null }],
  [4, { name: 'Also red', colour: 'red' as const }],
])

const head = (patchId: number, colour: 'red' | 'pink' | null, groupIds: number[]) => ({
  patchId,
  name: `H${patchId}`,
  colour,
  groupIds,
})

describe('Colour from group (D9)', () => {
  it('copies onto the heads with none, naming the rest', () => {
    const plan = planColourFromGroup(
      [head(1, null, [1]), head(2, 'pink', [1]), head(3, null, [1, 2]), head(4, null, [3]), head(5, null, [1, 4])],
      groups,
      false,
    )
    expect(plan.writes).toEqual([
      { patchId: 1, colour: 'red' },
      { patchId: 5, colour: 'red' },
    ])
    expect(plan).toMatchObject({ kept: ['H2'], ambiguous: ['H3'], none: ['H4'] })
    expect(colourFromGroupNote(plan)).toBe(
      'H2 already had a colour — ⌥ for all of them · H3 in groups of different colours · H4 in no coloured group',
    )
    expect(colourFromGroupLabel(plan)).toBe('From group')
  })

  it('onto all of them with ⌥, writing only a colour that moves', () => {
    const plan = planColourFromGroup([head(1, 'red', [1]), head(2, 'pink', [1])], groups, true)
    expect(plan.writes).toEqual([{ patchId: 2, colour: 'red' }])
    expect(colourFromGroupLabel(plan)).toBe('From group · Front')
    expect(colourFromGroupNote(plan)).toBeNull()
  })

  it('leaves a head already in its group\'s colour alone, and does not offer ⌥ for it', () => {
    const plan = planColourFromGroup([head(1, 'red', [1]), head(2, 'red', [1, 4])], groups, false)
    expect(plan).toMatchObject({ writes: [], kept: [], same: ['H1', 'H2'] })
    expect(colourFromGroupNote(plan)).toBeNull()
    expect(colourFromGroupRefusal(plan)).toBe('They already have their group’s colour')
  })

  it('says why there is nothing to copy', () => {
    expect(colourFromGroupRefusal(planColourFromGroup([head(1, null, [3])], groups, false))).toBe(
      'None of them is in a coloured group',
    )
    expect(colourFromGroupRefusal(planColourFromGroup([head(1, null, [1, 2])], groups, false))).toBe(
      'Their groups have different colours',
    )
    // A head that has a colour is still offered: ⌥ would copy onto it.
    expect(colourFromGroupRefusal(planColourFromGroup([head(1, 'pink', [1])], groups, false))).toBeNull()
  })
})

describe('the patch list filter', () => {
  const p = (id: number, name: string, colour: string | null) =>
    ({ id, key: name.toLowerCase(), displayName: name, universe: 1, startChannel: id, colour }) as unknown as FixturePatch
  const patches = [p(1, 'Hex 1', 'red'), p(2, 'Hex 2', null), p(3, 'Spot 1', 'red'), p(4, 'Spot 2', 'brownish')]

  it('takes colour:red beside the needle it always had', () => {
    expect(filterPatches(patches, 'all', 'colour:red').map((x) => x.id)).toEqual([1, 3])
    expect(filterPatches(patches, 'all', 'colour:red spot').map((x) => x.id)).toEqual([3])
    expect(filterPatches(patches, 'all', 'hex 1').map((x) => x.id)).toEqual([1])
    // A key the desk does not know reads as none.
    expect(filterPatches(patches, 'all', 'colour:brown').map((x) => x.id)).toEqual([])
    // A bare `colour:`, half-typed, filters nothing rather than matching nothing.
    expect(filterPatches(patches, 'all', 'colour:').map((x) => x.id)).toEqual([1, 2, 3, 4])
    expect(filterPatches(patches, 'all', 'spot colour:').map((x) => x.id)).toEqual([3, 4])
  })
})
