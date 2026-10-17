// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest'
import type { ProgrammerLayer } from '@/api/programmerWsApi'
import {
  DEFAULT_APPLIED_VIEW,
  appliedSearch,
  appliedTablePath,
  applyAppliedViewOptions,
  fxHomeOf,
  fxStateOf,
  getAppliedTable,
  layerEffectState,
  layersOfKind,
  matchesFilter,
  nextSort,
  rememberView,
  rememberedView,
  resetAppliedTableStore,
  sortRows,
  viewFromSearch,
} from './appliedTables'
import { windowSetupUrl } from './screens'

beforeEach(() => {
  sessionStorage.clear()
  resetAppliedTableStore()
})

describe('the view in the query string', () => {
  it('round-trips sort, direction, filter and each table’s own chip', () => {
    const view = { sort: 'home', dir: 'desc' as const, q: 'wash', chip: 'layer' }
    const search = appliedSearch('fx', view)
    expect(search).toBe('?table=fx&sort=home&dir=desc&q=wash&home=layer')
    expect(viewFromSearch(new URLSearchParams(search), 'fx')).toEqual(view)

    const palettes = { sort: null, dir: 'asc' as const, q: '', chip: 'colour' }
    expect(appliedSearch('palettes', palettes)).toBe('?table=palettes&family=colour')
    expect(viewFromSearch(new URLSearchParams('?table=palettes&family=colour'), 'palettes')).toEqual(palettes)
  })

  it('states no view without one of its own keys — and another table’s chip is not one', () => {
    expect(viewFromSearch(new URLSearchParams('?table=fx'), 'fx')).toBeNull()
    expect(viewFromSearch(new URLSearchParams('?table=looks&family=colour'), 'looks')).toBeNull()
    expect(appliedSearch('looks', { ...DEFAULT_APPLIED_VIEW, chip: 'colour' })).toBe('?table=looks')
  })

  it('cycles a header press: ascending, descending, then the rail’s order', () => {
    const asc = nextSort(DEFAULT_APPLIED_VIEW, 'name')
    expect(asc).toMatchObject({ sort: 'name', dir: 'asc' })
    const desc = nextSort(asc, 'name')
    expect(desc).toMatchObject({ sort: 'name', dir: 'desc' })
    expect(nextSort(desc, 'name')).toMatchObject({ sort: null, dir: 'asc' })
    expect(nextSort(desc, 'home')).toMatchObject({ sort: 'home', dir: 'asc' })
  })

  it('remembers a view per table, and a tab lands on it', () => {
    rememberView('fx', { sort: 'speed', dir: 'asc', q: '', chip: 'yours' })
    expect(rememberedView('fx')).toEqual({ sort: 'speed', dir: 'asc', q: '', chip: 'yours' })
    expect(rememberedView('looks')).toEqual(DEFAULT_APPLIED_VIEW)
    expect(appliedTablePath(13, 'fx')).toBe('/projects/13/programmer/applied?table=fx&sort=speed&home=yours')
  })

  it('carries the table on a window’s Copy link', () => {
    expect(windowSetupUrl('Hall', '/projects/13/programmer/applied', { table: 'looks' }, 'http://localhost:8413')).toBe(
      'http://localhost:8413/projects/13/programmer/applied?window=Hall&table=looks',
    )
  })
})

describe('the window’s table', () => {
  it('takes a known table from a viewOptions frame and ignores anything else', () => {
    applyAppliedViewOptions({ table: 'palettes' })
    expect(getAppliedTable()).toBe('palettes')
    applyAppliedViewOptions({ table: 'cues', focus: 'rig' })
    expect(getAppliedTable()).toBe('palettes')
  })
})

describe('sorting and filtering', () => {
  it('sorts stably, without case, numbers as numbers and nulls last both ways', () => {
    const rows = [
      { n: 'beta', v: 2 },
      { n: 'Alpha', v: null },
      { n: 'gamma', v: 10 },
      { n: 'alpha', v: 2 },
    ]
    const valueOf = (r: (typeof rows)[number], key: string) => (key === 'n' ? r.n : r.v)
    expect(sortRows(rows, 'n', 'asc', valueOf).map((r) => r.n)).toEqual(['Alpha', 'alpha', 'beta', 'gamma'])
    expect(sortRows(rows, 'v', 'asc', valueOf).map((r) => r.n)).toEqual(['beta', 'alpha', 'gamma', 'Alpha'])
    expect(sortRows(rows, 'v', 'desc', valueOf).map((r) => r.n)).toEqual(['gamma', 'beta', 'alpha', 'Alpha'])
    expect(sortRows(rows, null, 'asc', valueOf)).toEqual(rows)
  })

  it('filters on any field, without case', () => {
    expect(matchesFilter('WASH', ['Front wash', null])).toBe(true)
    expect(matchesFilter('  ', ['anything'])).toBe(true)
    expect(matchesFilter('amber', ['Front wash', undefined])).toBe(false)
  })
})

describe('the rows the rail shows', () => {
  const layer = (layerId: number, kind: 'LOOK' | 'TEMPLATE'): ProgrammerLayer => ({
    layerId,
    source: { kind, id: layerId, uuid: `u${layerId}`, name: `L${layerId}` },
    sortOrder: layerId,
    enabled: true,
    targets: [],
    blendMode: 'OVERRIDE',
    amount: 1,
    stomp: false,
  })

  it('takes each kind top wins, numbered by its place in the whole stack', () => {
    const stack = [layer(1, 'LOOK'), layer(2, 'TEMPLATE'), layer(3, 'LOOK'), layer(4, 'TEMPLATE')]
    expect(layersOfKind(stack, 'LOOK').map((e) => [e.layer.layerId, e.position])).toEqual([
      [3, 3],
      [1, 1],
    ])
    expect(layersOfKind(stack, 'TEMPLATE').map((e) => e.position)).toEqual([4, 2])
  })

  it('reads a layer’s effect as spent only once every instance it spawned is', () => {
    expect(layerEffectState(4, [{ programmerLayerId: 4, spent: true }])).toBe('spent')
    expect(layerEffectState(4, [{ programmerLayerId: 4, spent: true }, { programmerLayerId: 4 }])).toBe('running')
    expect(layerEffectState(4, [{ programmerLayerId: 5, spent: true }])).toBeNull()
  })

  it('files an effect where the rail’s home badge does', () => {
    expect(fxHomeOf({ programmerLayerId: 3, cueId: null, programmerOwned: false })).toBe('layer')
    expect(fxHomeOf({ programmerLayerId: null, cueId: 9, programmerOwned: false })).toBe('cue')
    expect(fxHomeOf({ programmerLayerId: null, cueId: null, programmerOwned: true })).toBe('yours')
    expect(fxHomeOf({ programmerLayerId: null, cueId: null, programmerOwned: false })).toBe('base')
  })

  it('reads the state the desk reports: spent over leaving over held over paused', () => {
    expect(fxStateOf({ isRunning: true, spent: true, leaving: true }, true)).toBe('spent')
    expect(fxStateOf({ isRunning: true, leaving: true }, true)).toBe('leaving')
    expect(fxStateOf({ isRunning: false }, true)).toBe('held')
    expect(fxStateOf({ isRunning: false }, false)).toBe('paused')
    expect(fxStateOf({ isRunning: true }, false)).toBe('running')
  })
})
