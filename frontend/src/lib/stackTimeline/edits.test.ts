import { describe, expect, it } from 'vitest'
import type { StackTimeline, TimelineEntry, TimelineLayer } from '@/api/stackTimelineApi'
import { cells, columns, type TimelineNames } from '.'
import { cueRangeWords, dragOps, dragPlan, fillDownOps, offOps, onOps, replacePlan, selectedLayers, sharedValue, toggleOps } from './edits'

/** The Timeline's gestures as op lists (stack-timeline plan §4.4): pure, one list per gesture. */

const NAMES: TimelineNames = { target: (t) => t.key, element: () => ({ name: 'Tabs', kind: 'drape' }) }

function layer(id: number, over: Partial<TimelineLayer> = {}): TimelineLayer {
  return {
    id,
    source: { kind: 'TEMPLATE', id: 7, uuid: 'p7', name: 'Lavender', families: ['COLOUR'] },
    targets: [{ type: 'fixture', key: 'hex-1' }],
    heads: ['hex-1'],
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

function entry(entryId: number, over: Partial<TimelineEntry> = {}): TimelineEntry {
  return { entryId, kind: 'CUE', cueId: entryId - 100, layers: [], effects: [], scenery: [], trackedScenery: [], ...over }
}

const TIMELINE: StackTimeline = {
  stackId: 1,
  entries: [
    entry(101, { layers: [layer(11, { amount: 0.6 })], scenery: [{ elementUuid: 'tabs', state: { open: 0 }, transitionMs: 4000 }] }),
    entry(102),
    entry(103, { layers: [layer(13)] }),
    entry(104),
  ],
}
const ORDER = TIMELINE.entries.map((e) => ({ entryId: e.entryId, cueId: e.cueId! }))
const COLUMNS = columns(TIMELINE, NAMES)
const BY_ID = new Map(COLUMNS.map((c) => [c.id, c]))
const GRID = cells(TIMELINE, COLUMNS)
const PALETTE = 'palette:7:fixture:hex-1'
const SCENERY = 'scenery:tabs'
const palette = BY_ID.get(PALETTE)!
const scenery = BY_ID.get(SCENERY)!

describe('toggleOps', () => {
  it('turns an empty cell on with the settings of the nearest filled cell above', () => {
    expect(toggleOps(ORDER, GRID, palette, 102)).toEqual([
      { op: 'layerOn', cueId: 2, source: { kind: 'TEMPLATE', id: 7 }, target: { type: 'fixture', key: 'hex-1' }, settingsFrom: 11 },
    ])
  })

  it('takes a filled cell off', () => {
    expect(toggleOps(ORDER, GRID, palette, 103)).toEqual([
      { op: 'layerOff', cueId: 3, source: { kind: 'TEMPLATE', id: 7 }, target: { type: 'fixture', key: 'hex-1' } },
    ])
  })

  it('copies a scenery change into an empty cell, and clears a filled one', () => {
    expect(toggleOps(ORDER, GRID, scenery, 102)).toEqual([{ op: 'scenerySet', cueId: 2, elementUuid: 'tabs', state: { open: 0 }, transitionMs: 4000 }])
    expect(toggleOps(ORDER, GRID, scenery, 101)).toEqual([{ op: 'sceneryClear', cueId: 1, elementUuid: 'tabs' }])
  })
})

describe('the range verbs', () => {
  const range = [101, 102, 103, 104].map((entryId) => ({ entryId, columnId: PALETTE }))

  it('On fills each empty cell from its nearest filled cell, Off takes the filled ones off', () => {
    expect(onOps(ORDER, GRID, BY_ID, range).map((op) => [op.op, 'cueId' in op ? op.cueId : 0, op.op === 'layerOn' ? op.settingsFrom : null])).toEqual([
      ['layerOn', 2, 11],
      ['layerOn', 4, 13],
    ])
    expect(offOps(ORDER, GRID, BY_ID, range).map((op) => ('cueId' in op ? op.cueId : 0))).toEqual([1, 3])
  })

  it('Fill ↓ copies the top row down: a filled top fills, an empty top empties', () => {
    expect(fillDownOps(ORDER, GRID, BY_ID, range).map((op) => [op.op, 'cueId' in op ? op.cueId : 0])).toEqual([
      ['layerOn', 2],
      ['layerOn', 4],
    ])
    const fromEmpty = [102, 103].map((entryId) => ({ entryId, columnId: PALETTE }))
    expect(fillDownOps(ORDER, GRID, BY_ID, fromEmpty).map((op) => op.op)).toEqual(['layerOff'])
  })

  it('answers nothing where nothing would change', () => {
    expect(onOps(ORDER, GRID, BY_ID, [{ entryId: 101, columnId: PALETTE }])).toEqual([])
    expect(offOps(ORDER, GRID, BY_ID, [{ entryId: 102, columnId: PALETTE }])).toEqual([])
    expect(fillDownOps(ORDER, GRID, BY_ID, [{ entryId: 101, columnId: PALETTE }])).toEqual([])
  })
})

describe('a run’s end dragged', () => {
  it('down fills each empty cell it crosses with the dragged cell’s settings', () => {
    expect([...dragPlan(ORDER, GRID, palette, 101, 102)]).toEqual([[102, 'fill']])
    expect(dragOps(ORDER, GRID, palette, 103, 104)).toEqual([
      { op: 'layerOn', cueId: 4, source: { kind: 'TEMPLATE', id: 7 }, target: { type: 'fixture', key: 'hex-1' }, settingsFrom: 13 },
    ])
  })

  it('up empties the cells it crosses, leaving the one under the pointer', () => {
    const run: StackTimeline = { stackId: 1, entries: [entry(101, { layers: [layer(1)] }), entry(102, { layers: [layer(2)] }), entry(103, { layers: [layer(3)] })] }
    const order = run.entries.map((e) => ({ entryId: e.entryId, cueId: e.cueId! }))
    const grid = cells(run, columns(run, NAMES))
    expect([...dragPlan(order, grid, palette, 103, 101)]).toEqual([
      [102, 'empty'],
      [103, 'empty'],
    ])
  })
})

it('replacePlan swaps where the cue lacks the replacement and takes the old one off where it holds it', () => {
  const from = { source: { kind: 'TEMPLATE' as const, id: 7 }, target: { type: 'fixture' as const, key: 'hex-1' } }
  const to = { source: { kind: 'TEMPLATE' as const, id: 8 }, target: { type: 'fixture' as const, key: 'hex-1' } }
  const entries = [
    { cueId: 1, layers: [layer(1)] },
    { cueId: 2, layers: [layer(2), layer(3, { source: { kind: 'TEMPLATE', id: 8, uuid: 'p8', name: 'Amber', families: ['COLOUR'] } })] },
    { cueId: 3, layers: [] },
  ]
  expect(replacePlan(entries, [1, 2, 3], from, to)).toEqual({ swaps: [1], holds: [2] })
})

it('selectedLayers names each layer once, and sharedValue says mixed where they disagree', () => {
  const layers = selectedLayers(ORDER, GRID, BY_ID, [101, 103].map((entryId) => ({ entryId, columnId: PALETTE })))
  expect(layers.map((l) => l.layer.id)).toEqual([11, 13])
  expect(sharedValue(layers.map((l) => l.layer.amount))).toBe('mixed')
  expect(sharedValue(layers.map((l) => l.layer.blendMode))).toBe('OVERRIDE')
})

it('cueRangeWords names a block as a range and anything else as a list', () => {
  const label = (id: number) => `Q${id}`
  expect(cueRangeWords(ORDER, [3], label)).toBe('Q3')
  expect(cueRangeWords(ORDER, [2, 3, 4], label)).toBe('Q2 to Q4')
  expect(cueRangeWords(ORDER, [1, 4], label)).toBe('Q1 and Q4')
})
