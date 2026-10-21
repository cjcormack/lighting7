import { describe, expect, it } from 'vitest'
import type { StackTimeline, TimelineEffect, TimelineEntry, TimelineLayer } from '@/api/stackTimelineApi'
import {
  applyColumnOrder,
  cellAt,
  cells,
  columns,
  filter,
  kindCounts,
  layerGlyphs,
  markerMarkAt,
  moveColumn,
  runMarkAt,
  runs,
  type TimelineNames,
} from './index'

const names: TimelineNames = {
  target: (t) => ({ 'hex-1': 'Hex 1', 'hex-2': 'Hex 2', wash: 'Front wash' })[t.key] ?? t.key,
  element: (uuid) => (uuid === 'tabs' ? { name: 'House tabs', kind: 'drape' } : undefined),
}

let nextId = 1

function layer(source: { kind: 'LOOK' | 'TEMPLATE'; id: number; name: string; families?: string[] }, over: Partial<TimelineLayer> = {}): TimelineLayer {
  return {
    id: nextId++,
    source: { uuid: `u${source.id}`, families: source.families ?? [], ...source },
    targets: [],
    heads: [],
    sortOrder: 0,
    enabled: true,
    amount: 1,
    blendMode: 'OVERRIDE',
    propertyMask: null,
    stomp: false,
    timed: false,
    speedMasterUuid: null,
    rateSpeedMasterUuid: null,
    ...over,
  }
}

function pulse(runUuid: string | null, settings: Record<string, unknown> = { beatDivision: 1 }, over: Partial<TimelineEffect> = {}): TimelineEffect {
  return {
    id: nextId++,
    uuid: `fx${nextId}`,
    runUuid,
    effectType: 'Pulse',
    propertyName: 'dimmer',
    targetType: 'group',
    targetKey: 'wash',
    family: 'INTENSITY',
    heads: ['hex-1', 'hex-2'],
    settings,
    ...over,
  }
}

function cue(entryId: number, over: Partial<TimelineEntry> = {}): TimelineEntry {
  return { entryId, kind: 'CUE', cueId: entryId * 10, layers: [], effects: [], scenery: [], trackedScenery: [], ...over }
}

function marker(entryId: number, label: string): TimelineEntry {
  return { entryId, kind: 'MARKER', label, layers: [], effects: [], scenery: [], trackedScenery: [] }
}

const WARM = { kind: 'LOOK' as const, id: 1, name: 'Warm', families: ['INTENSITY', 'COLOUR'] }
const LAVENDER = { kind: 'TEMPLATE' as const, id: 7, name: 'Lavender', families: ['COLOUR'] }
const AMBER = { kind: 'TEMPLATE' as const, id: 8, name: 'Amber', families: ['COLOUR'] }
const hex1 = { type: 'fixture' as const, key: 'hex-1' }
const hex2 = { type: 'fixture' as const, key: 'hex-2' }

/** Q1 · Q2 · [marker] · Q3 · Q4 · Q5, as the board draws Act 1 in miniature. */
function act(): StackTimeline {
  return {
    stackId: 1,
    entries: [
      cue(1, {
        layers: [
          layer(WARM, { heads: ['hex-1'], amount: 0.6, stomp: true }),
          layer(LAVENDER, { targets: [hex1, hex2], heads: ['hex-1', 'hex-2'], propertyMask: 'COLOUR' }),
        ],
        effects: [pulse('run-a')],
        scenery: [{ elementUuid: 'tabs', state: { open: 1 }, transitionMs: 4000 }],
      }),
      cue(2, {
        layers: [layer(LAVENDER, { targets: [hex1], heads: ['hex-1'] })],
        effects: [pulse('run-a')],
        trackedScenery: [{ elementUuid: 'tabs', state: { open: 1 }, fromCueId: 10 }],
      }),
      marker(3, 'Scene 2'),
      cue(4, {
        layers: [layer(LAVENDER, { targets: [hex1], heads: ['hex-1'] }), layer(AMBER, { targets: [hex2], heads: ['hex-2'], timed: true })],
        effects: [pulse('run-a', { beatDivision: 2 })],
        trackedScenery: [{ elementUuid: 'tabs', state: { open: 1 }, fromCueId: 10 }],
      }),
      cue(5, { effects: [pulse('run-b', { beatDivision: 2 })], scenery: [{ elementUuid: 'tabs', state: { open: 0 }, transitionMs: null }] }),
      cue(6, {
        effects: [pulse(null, { beatDivision: 2 }), pulse(null, {}, { effectType: 'SineWave', targetType: 'fixture', targetKey: 'hex-1', heads: ['hex-1'] })],
        trackedScenery: [{ elementUuid: 'tabs', state: { open: 0 }, fromCueId: 50 }],
      }),
    ],
  }
}

describe('columns', () => {
  it('groups by kind in first use down the stack, a column per record and target', () => {
    const cols = columns(act(), names)
    expect(cols.map((c) => c.id)).toEqual([
      'look:1:*',
      'palette:7:fixture:hex-1',
      'palette:7:fixture:hex-2',
      'palette:8:fixture:hex-2',
      'effect:Pulse:dimmer:group:wash',
      'effect:SineWave:dimmer:fixture:hex-1',
      'scenery:tabs',
    ])
    const [look, lavender1, lavender2] = cols
    expect(look).toMatchObject({ kind: 'look', name: 'Warm', sub: 'own fixtures', families: ['INTENSITY', 'COLOUR'], heads: ['hex-1'] })
    // One layer over two targets is two columns, each reaching its own head.
    expect(lavender1).toMatchObject({ name: 'Lavender', sub: 'Hex 1', heads: ['hex-1'] })
    expect(lavender2).toMatchObject({ sub: 'Hex 2', heads: ['hex-2'] })
    expect(cols[4]).toMatchObject({ name: 'Pulse · Dimmer', sub: 'Front wash', families: ['INTENSITY'] })
    expect(cols[6]).toMatchObject({ kind: 'scenery', name: 'House tabs', sub: 'drape' })
  })

  it("applies a window's order within each kind, keeping new columns after it", () => {
    const cols = columns(act(), names)
    const order = moveColumn(cols, 'palette:8:fixture:hex-2', -1)
    expect(order.slice(1, 4)).toEqual(['palette:7:fixture:hex-1', 'palette:8:fixture:hex-2', 'palette:7:fixture:hex-2'])
    // A move across a kind's edge changes nothing.
    expect(moveColumn(cols, 'look:1:*', 1)).toEqual(cols.map((c) => c.id))
    const ordered = columns(act(), names, order)
    expect(ordered.map((c) => c.id).slice(1, 4)).toEqual(order.slice(1, 4))
    expect(applyColumnOrder(cols.slice(1, 3), ['gone', 'palette:7:fixture:hex-2']).map((c) => c.id)).toEqual([
      'palette:7:fixture:hex-2',
      'palette:7:fixture:hex-1',
    ])
  })
})

describe('cells', () => {
  it('fills a layer cell per target with its rank and glyphs, an effect by signature and scenery by element', () => {
    const t = act()
    const cols = columns(t, names)
    const grid = cells(t, cols)
    const warm = cellAt(grid, 1, 'look:1:*').filled
    expect(warm).toMatchObject({ kind: 'layer', rank: 1, glyphs: ['60%', 'S'] })
    const l1 = cellAt(grid, 1, 'palette:7:fixture:hex-1').filled
    const l2 = cellAt(grid, 1, 'palette:7:fixture:hex-2').filled
    expect(l1).toMatchObject({ kind: 'layer', rank: 2, glyphs: ['C'] })
    expect(l2).toMatchObject({ rank: 2 })
    expect(cellAt(grid, 4, 'palette:8:fixture:hex-2').filled).toMatchObject({ rank: 2, glyphs: ['⏱'] })
    expect(cellAt(grid, 2, 'look:1:*').filled).toBeNull()
    expect(cellAt(grid, 1, 'scenery:tabs').filled).toMatchObject({ kind: 'scenery', transitionMs: 4000 })
    expect(cellAt(grid, 2, 'scenery:tabs')).toEqual({ filled: null, tracked: { open: 1 } })
    expect(cellAt(grid, 6, 'effect:SineWave:dimmer:fixture:hex-1').filled).toMatchObject({ kind: 'effect' })
  })
})

describe('runs', () => {
  const t = act()
  const cols = columns(t, names)
  const grid = cells(t, cols)
  const marks = runs(t, cols, grid)

  it('joins layer cells across a marker, which the run passes through', () => {
    const col = 'palette:7:fixture:hex-1'
    expect(runMarkAt(marks, 1, col)).toMatchObject({ start: true, end: false })
    expect(runMarkAt(marks, 2, col)).toMatchObject({ start: false, end: false })
    expect(markerMarkAt(marks, 3, col).through).toBe(true)
    expect(runMarkAt(marks, 4, col)).toMatchObject({ start: false, end: true })
    // Hex 2's Lavender is only in Q1: nothing passes the marker there.
    expect(markerMarkAt(marks, 3, 'palette:7:fixture:hex-2').through).toBe(false)
  })

  it('joins effect cells on one run key, marks a change in place ◆ and a restart ↺', () => {
    const col = 'effect:Pulse:dimmer:group:wash'
    expect(runMarkAt(marks, 1, col)).toMatchObject({ start: true, restart: false, changed: false })
    expect(runMarkAt(marks, 2, col)).toMatchObject({ start: false, changed: false })
    expect(markerMarkAt(marks, 3, col).through).toBe(true)
    // Q4's beat division differs inside run-a: an edit in place, not a break.
    expect(runMarkAt(marks, 4, col)).toMatchObject({ start: false, end: true, changed: true, restart: false })
    // run-b meets run-a at Q5: two keys meet.
    expect(runMarkAt(marks, 5, col)).toMatchObject({ start: true, end: true, restart: true, changed: false })
    // Two unkeyed rows never join, so an effect stored before the key draws as a broken run.
    expect(runMarkAt(marks, 6, col)).toMatchObject({ start: true, restart: true })
  })

  it('never joins scenery, and carries the tracked line down to the next change and through a marker', () => {
    const col = 'scenery:tabs'
    expect(runMarkAt(marks, 1, col)).toMatchObject({ start: true, end: true, trackedLine: false })
    expect(runMarkAt(marks, 2, col)).toMatchObject({ start: false, trackedLine: true })
    expect(markerMarkAt(marks, 3, col).trackedLine).toBe(true)
    expect(runMarkAt(marks, 4, col).trackedLine).toBe(true)
    // Q5's own change sits on the line that reaches it, and its state is tracked on into Q6.
    expect(runMarkAt(marks, 5, col)).toMatchObject({ start: true, trackedLine: true })
    expect(runMarkAt(marks, 6, col)).toMatchObject({ start: false, trackedLine: true })
  })

  it('draws a run that ends in one cue as a single bar', () => {
    expect(runMarkAt(marks, 1, 'look:1:*')).toMatchObject({ start: true, end: true })
  })
})

describe('a run broken by an N-shot', () => {
  it('never joins a keyed effect to an N-shot, which a GO fires afresh', () => {
    const t: StackTimeline = {
      stackId: 1,
      entries: [
        cue(1, { effects: [pulse('run-a')] }),
        cue(2, { effects: [pulse('run-a', { beatDivision: 1, nShot: 2 })] }),
        cue(3, { effects: [pulse('run-a')] }),
      ],
    }
    const cols = columns(t, names)
    const marks = runs(t, cols, cells(t, cols))
    const col = 'effect:Pulse:dimmer:group:wash'
    expect(runMarkAt(marks, 1, col)).toMatchObject({ start: true, end: true })
    expect(runMarkAt(marks, 2, col)).toMatchObject({ start: true, end: true, restart: true, changed: false })
    expect(runMarkAt(marks, 3, col)).toMatchObject({ start: true, restart: true })
  })
})

describe('a column of one target of several', () => {
  it("keeps the layer's heads that target covers: a fixture and its cells, a group's members", () => {
    const rig: TimelineNames = {
      ...names,
      members: (group) => (group === 'wash' ? ['hex-1', 'hex-2'] : undefined),
      parentOf: (key) => (key.startsWith('bar.') ? 'bar' : undefined),
    }
    const t: StackTimeline = {
      stackId: 1,
      entries: [
        cue(1, {
          layers: [
            layer(WARM, {
              targets: [{ type: 'fixture', key: 'bar' }, { type: 'group', key: 'wash' }, { type: 'fixture', key: 'hex-3' }],
              heads: ['bar.1', 'bar.2', 'hex-2'],
            }),
          ],
        }),
      ],
    }
    const byId = new Map(columns(t, rig).map((c) => [c.id, c.heads]))
    expect(byId.get('look:1:fixture:bar')).toEqual(['bar.1', 'bar.2'])
    expect(byId.get('look:1:group:wash')).toEqual(['hex-2'])
    // A target the Look's rows never reach is a column that reaches nothing.
    expect(byId.get('look:1:fixture:hex-3')).toEqual([])
  })
})

describe('moving a column under a filter', () => {
  it('trades places with the neighbour on screen, leaving a hidden column where it was', () => {
    const cols = columns(act(), names)
    const palettes = cols.filter((c) => c.kind === 'palette')
    // Lavender on Hex 2 hidden between Lavender on Hex 1 and Amber.
    const visible = palettes.filter((c) => c.id !== 'palette:7:fixture:hex-2')
    const order = moveColumn(cols, 'palette:8:fixture:hex-2', -1, visible)
    expect(order.filter((id) => id.startsWith('palette'))).toEqual(['palette:8:fixture:hex-2', 'palette:7:fixture:hex-2', 'palette:7:fixture:hex-1'])
  })
})

describe('layer glyphs', () => {
  it('reads a blend and a mask written in any case, as the desk does', () => {
    expect(layerGlyphs(layer(WARM, { blendMode: 'additive', propertyMask: 'colour,position' }))).toEqual(['ADD', 'CP'])
  })
})

describe('filter', () => {
  const cols = columns(act(), names)
  const all = new Set(['look', 'palette', 'effect', 'scenery'] as const)

  it('hides a kind by its chip', () => {
    const kept = filter(cols, { kinds: new Set(['look', 'scenery'] as const), family: null, selectionHeads: null })
    expect(kept.map((c) => c.kind)).toEqual(['look', 'scenery'])
  })

  it('keeps the columns touching a family, and scenery always', () => {
    const kept = filter(cols, { kinds: all, family: 'COLOUR', selectionHeads: null })
    expect(kept.map((c) => c.id)).toEqual(['look:1:*', 'palette:7:fixture:hex-1', 'palette:7:fixture:hex-2', 'palette:8:fixture:hex-2', 'scenery:tabs'])
    expect(filter(cols, { kinds: all, family: 'POSITION', selectionHeads: null }).map((c) => c.kind)).toEqual(['scenery'])
  })

  it('keeps the columns whose heads meet the selection', () => {
    const kept = filter(cols, { kinds: all, family: null, selectionHeads: new Set(['hex-2']) })
    expect(kept.map((c) => c.id)).toEqual([
      'palette:7:fixture:hex-2',
      'palette:8:fixture:hex-2',
      'effect:Pulse:dimmer:group:wash',
      'scenery:tabs',
    ])
  })

  it('counts each kind under the other two filters', () => {
    expect(kindCounts(cols, { family: 'COLOUR', selectionHeads: null })).toEqual({ look: 1, palette: 3, effect: 0, scenery: 1 })
    expect(kindCounts(cols, { family: null, selectionHeads: null })).toEqual({ look: 1, palette: 3, effect: 2, scenery: 1 })
  })
})
