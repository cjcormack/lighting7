// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest'
import type { ProgrammerLayer } from '@/api/programmerWsApi'
import {
  DEFAULT_TABLE_VIEW,
  applyProgrammerViewOptions,
  fxHomeOf,
  fxStateOf,
  getProgrammerTable,
  layerFxState,
  layerMoveOf,
  matchesFilter,
  nextSort,
  programmerSearch,
  programmerTablePath,
  rememberView,
  rememberedView,
  resetProgrammerTableStore,
  sortRows,
  stackLayers,
  viewFromSearch,
} from './programmerTables'
import { windowSetupUrl } from './screens'

beforeEach(() => {
  sessionStorage.clear()
  resetProgrammerTableStore()
})

describe('the view in the query string', () => {
  it('round-trips sort, direction, filter and each table’s own chip', () => {
    const view = { sort: 'home', dir: 'desc' as const, q: 'wash', chip: 'layer' }
    const search = programmerSearch('fx', view)
    expect(search).toBe('?table=fx&sort=home&dir=desc&q=wash&home=layer')
    expect(viewFromSearch(new URLSearchParams(search), 'fx')).toEqual(view)

    const stack = { sort: null, dir: 'asc' as const, q: '', chip: 'palettes' }
    expect(programmerSearch('stack', stack)).toBe('?table=stack&kind=palettes')
    expect(viewFromSearch(new URLSearchParams('?table=stack&kind=palettes'), 'stack')).toEqual(stack)
  })

  it('states no view without one of its own keys — and another table’s chip is not one', () => {
    expect(viewFromSearch(new URLSearchParams('?table=fx'), 'fx')).toBeNull()
    expect(viewFromSearch(new URLSearchParams('?table=scenery&kind=looks'), 'scenery')).toBeNull()
    expect(programmerSearch('scenery', { ...DEFAULT_TABLE_VIEW, chip: 'looks' })).toBe('?table=scenery')
  })

  it('keeps no view for Values, whose filter is the grid’s', () => {
    expect(viewFromSearch(new URLSearchParams('?table=values&q=hex&sort=name'), 'values')).toBeNull()
    expect(programmerSearch('values', { sort: 'name', dir: 'desc', q: 'hex', chip: 'x' })).toBe('?table=values')
    rememberView('values', { sort: 'name', dir: 'asc', q: 'hex', chip: null })
    expect(rememberedView('values')).toEqual(DEFAULT_TABLE_VIEW)
  })

  it('cycles a header press: ascending, descending, then the desk’s order', () => {
    const asc = nextSort(DEFAULT_TABLE_VIEW, 'name')
    expect(asc).toMatchObject({ sort: 'name', dir: 'asc' })
    const desc = nextSort(asc, 'name')
    expect(desc).toMatchObject({ sort: 'name', dir: 'desc' })
    expect(nextSort(desc, 'name')).toMatchObject({ sort: null, dir: 'asc' })
    expect(nextSort(desc, 'home')).toMatchObject({ sort: 'home', dir: 'asc' })
  })

  it('remembers a view per table, and a tab lands on it', () => {
    rememberView('fx', { sort: 'speed', dir: 'asc', q: '', chip: 'yours' })
    expect(rememberedView('fx')).toEqual({ sort: 'speed', dir: 'asc', q: '', chip: 'yours' })
    expect(rememberedView('stack')).toEqual(DEFAULT_TABLE_VIEW)
    expect(programmerTablePath(13, 'fx')).toBe('/projects/13/programmer?table=fx&sort=speed&home=yours')
  })

  it('carries the table on a window’s Copy link', () => {
    expect(windowSetupUrl('Hall', '/projects/13/programmer', { table: 'stack' }, 'http://localhost:8413')).toBe(
      'http://localhost:8413/projects/13/programmer?window=Hall&table=stack',
    )
  })
})

describe('the window’s table', () => {
  it('takes a known table from a viewOptions frame and ignores anything else', () => {
    expect(getProgrammerTable()).toBe('values')
    applyProgrammerViewOptions({ table: 'scenery' })
    expect(getProgrammerTable()).toBe('scenery')
    applyProgrammerViewOptions({ table: 'palettes', focus: 'rig' })
    expect(getProgrammerTable()).toBe('scenery')
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
  const layer = (layerId: number, kind: 'LOOK' | 'PALETTE'): ProgrammerLayer => ({
    layerId,
    source: { kind, id: layerId, uuid: `u${layerId}`, name: `L${layerId}` },
    sortOrder: layerId,
    enabled: true,
    targets: [],
    blendMode: 'OVERRIDE',
    amount: 1,
    stomp: false,
  })

  it('lists every layer top wins, numbered by its place in the stack', () => {
    const stack = [layer(1, 'LOOK'), layer(2, 'PALETTE'), layer(3, 'LOOK'), layer(4, 'PALETTE')]
    expect(stackLayers(stack).map((e) => [e.layer.layerId, e.position])).toEqual([
      [4, 4],
      [3, 3],
      [2, 2],
      [1, 1],
    ])
  })

  it('reads a drag in the table’s top-first order as the desk’s bottom-first move', () => {
    const stack = [layer(1, 'LOOK'), layer(2, 'PALETTE'), layer(3, 'LOOK'), layer(4, 'PALETTE')]
    const id = (l: ProgrammerLayer) => `layer:${l.layerId}`
    // Drawn: local, 4, 3, 2, 1. The bottom layer dragged to the top goes to the end of the array.
    expect(layerMoveOf(stack, ['local', 'layer:1', 'layer:4', 'layer:3', 'layer:2'], id)).toEqual({ layerId: 1, toIndex: 3 })
    // Dropped above the pinned row is the top too.
    expect(layerMoveOf(stack, ['layer:2', 'local', 'layer:4', 'layer:3', 'layer:1'], id)).toEqual({ layerId: 2, toIndex: 3 })
    // The top one down a place.
    expect(layerMoveOf(stack, ['local', 'layer:3', 'layer:4', 'layer:2', 'layer:1'], id)).toEqual({ layerId: 4, toIndex: 2 })
    // The top one to the bottom.
    expect(layerMoveOf(stack, ['local', 'layer:3', 'layer:2', 'layer:1', 'layer:4'], id)).toEqual({ layerId: 4, toIndex: 0 })
    // Nothing moved, or a set that is not this stack.
    expect(layerMoveOf(stack, ['local', 'layer:4', 'layer:3', 'layer:2', 'layer:1'], id)).toBeNull()
    expect(layerMoveOf(stack, ['local', 'layer:4', 'layer:3'], id)).toBeNull()
  })

  it('reads a layer’s effect as spent only once every instance it spawned is', () => {
    expect(layerFxState(4, [{ programmerLayerId: 4, spent: true }])).toBe('spent')
    expect(layerFxState(4, [{ programmerLayerId: 4, spent: true }, { programmerLayerId: 4 }])).toBe('running')
    expect(layerFxState(4, [{ programmerLayerId: 5, spent: true }])).toBeNull()
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
