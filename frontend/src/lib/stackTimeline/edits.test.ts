import { describe, expect, it } from 'vitest'
import type { StackTimeline, TimelineEntry, TimelineFx, TimelineLayer } from '@/api/stackTimelineApi'
import { breakRunVerb, cells, columns, fxCycleWords, fxRunAt, joinRunVerb, runs, settingsChangeLabel, type TimelineNames } from '.'
import { cueRangeWords, dragOps, dragPlan, fillDownOps, offOps, onOps, replacePlan, selectedLayers, sharedValue, toggleOps } from './edits'

/** The Timeline's gestures as op lists (stack-timeline plan §4.4): pure, one list per gesture. */

const NAMES: TimelineNames = { target: (t) => t.key, element: () => ({ name: 'Tabs', kind: 'drape' }) }

function layer(id: number, over: Partial<TimelineLayer> = {}): TimelineLayer {
  return {
    id,
    source: { kind: 'PALETTE', id: 7, uuid: 'p7', name: 'Lavender', families: ['COLOUR'] },
    targets: [{ type: 'fixture', key: 'hex-1' }],
    heads: ['hex-1'],
    sortOrder: 0,
    enabled: true,
    amount: 1,
    blendMode: 'OVERRIDE',
    attributeMask: null,
    stomp: false,
    timed: false,
    speedMasterUuid: null,
    rateSpeedMasterUuid: null,
    ...over,
  }
}

function entry(entryId: number, over: Partial<TimelineEntry> = {}): TimelineEntry {
  return { entryId, kind: 'CUE', cueId: entryId - 100, layers: [], fx: [], scenery: [], trackedScenery: [], ...over }
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
      { op: 'layerOn', cueId: 2, source: { kind: 'PALETTE', id: 7 }, target: { type: 'fixture', key: 'hex-1' }, settingsFrom: 11 },
    ])
  })

  it('takes a filled cell off', () => {
    expect(toggleOps(ORDER, GRID, palette, 103)).toEqual([
      { op: 'layerOff', cueId: 3, source: { kind: 'PALETTE', id: 7 }, target: { type: 'fixture', key: 'hex-1' } },
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
      { op: 'layerOn', cueId: 4, source: { kind: 'PALETTE', id: 7 }, target: { type: 'fixture', key: 'hex-1' }, settingsFrom: 13 },
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
  const from = { source: { kind: 'PALETTE' as const, id: 7 }, target: { type: 'fixture' as const, key: 'hex-1' } }
  const to = { source: { kind: 'PALETTE' as const, id: 8 }, target: { type: 'fixture' as const, key: 'hex-1' } }
  const entries = [
    { cueId: 1, layers: [layer(1)] },
    { cueId: 2, layers: [layer(2), layer(3, { source: { kind: 'PALETTE', id: 8, uuid: 'p8', name: 'Amber', families: ['COLOUR'] } })] },
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

describe('FX cells and runs', () => {
  function pulse(uuid: string, runUuid: string | null, settings: Record<string, unknown> = { cycle: 1 }): TimelineFx {
    return {
      id: Number(uuid.slice(1)),
      uuid,
      runUuid,
      fxType: 'Pulse',
      attribute: 'dimmer',
      targetType: 'fixture',
      targetKey: 'hex-1',
      family: 'INTENSITY',
      heads: ['hex-1'],
      settings,
    }
  }
  const FX_TIMELINE: StackTimeline = {
    stackId: 1,
    entries: [
      entry(101, { fx: [pulse('u1', 'run-a')] }),
      entry(102, { fx: [pulse('u2', 'run-a', { cycle: 2 })] }),
      { entryId: 150, kind: 'MARKER', label: 'Scene 2', layers: [], fx: [], scenery: [], trackedScenery: [] },
      entry(103, { fx: [pulse('u3', null)] }),
      entry(104),
      entry(105, { fx: [pulse('u5', null, { cycle: 1, runs: 2 })] }),
    ],
  }
  const order = FX_TIMELINE.entries.filter((e) => e.kind === 'CUE').map((e) => ({ entryId: e.entryId, cueId: e.cueId! }))
  const fxColumns = columns(FX_TIMELINE, NAMES)
  const fxColumn = fxColumns.find((c) => c.kind === 'fx')!
  const grid = cells(FX_TIMELINE, fxColumns)
  const marks = runs(FX_TIMELINE, fxColumns, grid)

  it('turns an empty cell on as a copy of the nearest filled row, and a filled one off by its row', () => {
    expect(toggleOps(order, grid, fxColumn, 104)).toEqual([{ op: 'fxOn', cueId: 4, fromFxUuid: 'u3' }])
    expect(toggleOps(order, grid, fxColumn, 103)).toEqual([{ op: 'fxOff', cueId: 3, fxUuid: 'u3' }])
  })

  it('a drag down copies the dragged row into the cells it crosses, so they join its run; up deletes them', () => {
    expect(dragOps(order, grid, fxColumn, 103, 104)).toEqual([{ op: 'fxOn', cueId: 4, fromFxUuid: 'u3' }])
    expect(dragOps(order, grid, fxColumn, 103, 101)).toEqual([
      { op: 'fxOff', cueId: 2, fxUuid: 'u2' },
      { op: 'fxOff', cueId: 3, fxUuid: 'u3' },
    ])
  })

  it('words a cycle in beats on a master and in seconds otherwise', () => {
    expect(fxCycleWords({ cycle: 4, speedMasterUuid: 'm1' })).toBe('1 bar')
    expect(fxCycleWords({ cycle: 4, speedMasterUuid: null, seconds: true })).toBe('4 s')
    expect(settingsChangeLabel({ cycle: 2, seconds: true }, { cycle: 4, seconds: true })).toBe('4 s')
  })

  it('finds a run across a marker, marking a change in place', () => {
    expect(fxRunAt(order, grid, marks, fxColumn, 102).map((c) => [c.cueId, c.changed])).toEqual([
      [1, false],
      [2, true],
    ])
    expect(fxRunAt(order, grid, marks, fxColumn, 103).map((c) => c.cueId)).toEqual([3])
    expect(fxRunAt(order, grid, marks, fxColumn, 104)).toEqual([])
  })

  it('offers Break run only inside a run, and Join run only where a cell above can be joined', () => {
    expect(breakRunVerb(order, grid, marks, fxColumn, 102).op).toEqual({ op: 'breakRun', cueId: 2, fxUuid: 'u2', entryId: 102 })
    expect(breakRunVerb(order, grid, marks, fxColumn, 101)).toEqual({ op: null, reason: 'This cell starts its run already' })
    expect(joinRunVerb(order, grid, marks, fxColumn, 103).op).toEqual({ op: 'joinRun', cueId: 3, fxUuid: 'u3', entryId: 103 })
    expect(joinRunVerb(order, grid, marks, fxColumn, 102).reason).toBe('Joined to the cell above already')
    expect(joinRunVerb(order, grid, marks, fxColumn, 101).reason).toBe('The cue above has no FX in this column to join')
    expect(joinRunVerb(order, grid, marks, fxColumn, 105).reason).toBe('The cue above has no FX in this column to join')
  })

  it('offers no Join run to or from an N-shot', () => {
    const timeline: StackTimeline = {
      stackId: 1,
      entries: [entry(101, { fx: [pulse('u1', 'run-a')] }), entry(102, { fx: [pulse('u2', null, { cycle: 1, runs: 2 })] })],
    }
    const cols = columns(timeline, NAMES)
    const g = cells(timeline, cols)
    const fx = cols.find((c) => c.kind === 'fx')!
    const o = timeline.entries.map((e) => ({ entryId: e.entryId, cueId: e.cueId! }))
    expect(joinRunVerb(o, g, runs(timeline, cols, g), fx, 102).reason).toBe('An FX with a Runs count starts afresh at every GO')
  })
})

describe('a Look column', () => {
  // A Look lands whole (absolute-looks plan D1): its column is one per Look, and no op names a target.
  const WARM = { kind: 'LOOK' as const, id: 1, uuid: 'l1', name: 'Warm', families: ['INTENSITY'] }
  const looks: StackTimeline = { stackId: 1, entries: [entry(101, { layers: [layer(21, { source: WARM, targets: [] })] }), entry(102)] }
  const lookOrder = looks.entries.map((e) => ({ entryId: e.entryId, cueId: e.cueId! }))
  const lookColumns = columns(looks, NAMES)
  const look = lookColumns[0]!

  it('turns a cell on and off naming no target, even where a stored column names one', () => {
    const grid = cells(looks, lookColumns)
    expect(toggleOps(lookOrder, grid, look, 102)).toEqual([
      { op: 'layerOn', cueId: 2, source: { kind: 'LOOK', id: 1 }, target: null, settingsFrom: 21 },
    ])
    const stale = { ...look, target: { type: 'fixture' as const, key: 'hex-1' } }
    expect(toggleOps(lookOrder, grid, stale, 101)).toEqual([{ op: 'layerOff', cueId: 1, source: { kind: 'LOOK', id: 1 }, target: null }])
  })

  it('holds a cue with any layer of the Look, whatever an older one of it names', () => {
    const entries = [entry(101, { layers: [layer(21, { source: WARM, targets: [{ type: 'fixture', key: 'hex-1' }] })] })]
    const plan = replacePlan(entries, [1], { source: { kind: 'LOOK', id: 1 }, target: null }, { source: { kind: 'PALETTE', id: 7 }, target: null })
    expect(plan).toEqual({ swaps: [1], holds: [] })
  })
})
