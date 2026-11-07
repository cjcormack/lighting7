// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { makeFixture, makePixelBar } from '@/test/fixtureFactories'
import { buildRows } from './rowModel'
import { anyClosed, applyExpandAll, heldOpen, shownExpandable } from './expandAll'

const BAR = makePixelBar('bar', 2, [], { name: 'Bar', groups: ['Front'] })
const SPOT = makeFixture('spot', [], { name: 'Spot', groups: ['Front'] })
const LONE = makePixelBar('lone', 2, [], { name: 'Lone' })
const GROUPS = [{ name: 'Front', memberCount: 2, memberKeys: ['bar', 'spot'] }] as Parameters<typeof buildRows>[0]['groups']

const rowsOf = (groups: Set<string>, fixtures: Set<string>, textFilter = '') =>
  buildRows({ fixtures: [BAR, SPOT, LONE], groups: GROUPS, expandedGroups: groups, expandedFixtures: fixtures, textFilter })

describe('expandAll', () => {
  it('counts a closed group’s multi-head members as shown, so one press opens them too', () => {
    const shown = shownExpandable(rowsOf(new Set(), new Set()))
    expect(shown).toEqual({ groups: ['Front'], fixtures: ['bar', 'lone'] })
    const next = applyExpandAll({ groups: new Set(), fixtures: new Set() }, shown, { groups: [], fixtures: [] }, 'all', true)
    expect([...next.groups]).toEqual(['Front'])
    expect([...next.fixtures]).toEqual(['bar', 'lone'])
    expect(anyClosed(shown, (g) => next.groups.has(g), (f) => next.fixtures.has(f))).toBe(false)
  })

  it('is bounded by the filter', () => {
    expect(shownExpandable(rowsOf(new Set(), new Set(), 'Lone'))).toEqual({ groups: [], fixtures: ['lone'] })
  })

  it('keeps open what holds the selection on a collapse', () => {
    const rows = rowsOf(new Set(['Front']), new Set(['bar', 'lone']))
    const held = heldOpen(rows, new Set(['element:member:Front:bar:bar.pixel-0']))
    expect(held).toEqual({ groups: ['Front'], fixtures: ['bar'] })
    const next = applyExpandAll({ groups: new Set(['Front']), fixtures: new Set(['bar', 'lone']) }, shownExpandable(rows), held, 'all', false)
    expect([...next.groups]).toEqual(['Front'])
    expect([...next.fixtures]).toEqual(['bar'])
  })

  it('moves one level alone, and answers a set nothing changes as it was', () => {
    const current = { groups: new Set<string>(), fixtures: new Set<string>(['bar', 'lone']) }
    const next = applyExpandAll(current, shownExpandable(rowsOf(current.groups, current.fixtures)), { groups: [], fixtures: [] }, 'groups', true)
    expect([...next.groups]).toEqual(['Front'])
    expect(next.fixtures).toBe(current.fixtures)
  })
})
