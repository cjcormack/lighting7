import { describe, expect, it } from 'vitest'
import { makeActiveFx } from '@/test/fixtureFactories'
import type { ProgrammerEntry, ProgrammerKeyState, ProvenanceEntry } from '@/api/programmerWsApi'
import { fxReaching, mergeRowSources, rowSourceOf, type RowSource, type RowSourceInput } from './rowSource'

const entry = (over: Partial<ProgrammerEntry> = {}): ProgrammerEntry => ({
  targetKey: 'spot-3',
  attribute: 'dimmer',
  value: '204',
  owner: 'web',
  touched: true,
  owners: ['web'],
  ...over,
})
const prov = (over: Partial<ProvenanceEntry>): ProvenanceEntry => ({
  targetKey: 'spot-3',
  attribute: 'dimmer',
  source: 'PROGRAMMER',
  ...over,
})

function source(state: ProgrammerKeyState, over: Partial<RowSourceInput> = {}) {
  return rowSourceOf({
    headKey: 'spot-3',
    states: [{ key: 'dimmer', state }],
    effects: [],
    blind: false,
    cueLabel: (id) => (id === 12 ? 'Q12' : undefined),
    fxDetail: () => '¼ · M2',
    ...over,
  })
}

describe('rowSourceOf — one chip per source kind (D4)', () => {
  it('names the programmer and draws the × while it holds the key', () => {
    expect(source({ entry: entry(), provenance: prov({}) })).toMatchObject({
      kind: 'programmer',
      label: 'Programmer',
      holds: true,
      touched: true,
    })
  })

  it('names a pressed Look by name, and gives no × for a slot only its layer holds', () => {
    const s = source({
      entry: entry({ owner: 'layers', owners: ['layers'], touched: false }),
      provenance: prov({ layerSource: { kind: 'LOOK', id: 4, name: 'Night' } as never }),
    })
    expect(s).toMatchObject({ kind: 'programmer', label: 'Night · pressed', holds: false })
  })

  it('names the effect on stage with its speed and master', () => {
    const pulse = makeActiveFx({ id: 7, fxType: 'Pulse', targetKey: 'spot-3', programmerOwned: false })
    expect(source({ provenance: prov({ source: 'FX', fxId: 7 }) }, { effects: [pulse] })).toMatchObject({
      kind: 'fx',
      label: 'Pulse · ¼ · M2',
      effect: pulse,
    })
  })

  it('names the cue and the Look layer that won it', () => {
    const s = source({
      provenance: prov({ source: 'CUE', cueId: 12, layerSource: { kind: 'LOOK', id: 3, name: 'Night' } as never }),
    })
    expect(s).toMatchObject({ kind: 'cue', label: 'Q12 · Night', holds: false })
  })

  it('names the cue a stack moves the head for in the dark, not a bare Cue', () => {
    const s = source({
      provenance: prov({ source: 'CUE', cueStackId: 7, moveInDark: { stackId: 7, targetEntryId: 112, targetCueId: 12, label: 'Q12' } }),
    })
    expect(s).toMatchObject({ kind: 'cue', label: 'Move in dark · Q12' })
    // An older desk's empty label falls back to the cue's own.
    expect(
      source({ provenance: prov({ source: 'CUE', moveInDark: { stackId: 7, targetEntryId: 112, targetCueId: 12, label: '' } }) }).label,
    ).toBe('Move in dark · Q12')
  })

  it('says Parked, and Base where nothing asserts the key', () => {
    expect(source({ provenance: prov({ source: 'PARKED' }) }).label).toBe('Parked')
    expect(source({})).toMatchObject({ kind: 'base', label: 'Base', holds: false })
  })

  it('takes the strongest source over several keys — a parked axis first', () => {
    const s = rowSourceOf({
      headKey: 'spot-3',
      states: [
        { key: 'position', state: { entry: entry({ attribute: 'position' }), provenance: prov({ attribute: 'position' }) } },
        { key: 'tilt', state: { provenance: prov({ attribute: 'tilt', source: 'PARKED' }) } },
      ],
      effects: [],
      blind: false,
      cueLabel: () => undefined,
      fxDetail: () => '',
    })
    expect(s.kind).toBe('parked')
    expect(s.holds).toBe(true)
  })
})

describe('rowSourceOf — the held-back dot', () => {
  const cuePulse = makeActiveFx({ id: 9, targetKey: 'spot-3', attribute: 'dimmer', programmerOwned: false, cueId: 12 })

  it('marks a row whose programmer value holds a cue effect back', () => {
    expect(source({ entry: entry(), provenance: prov({}) }, { effects: [cuePulse] }).heldBack).toBe(true)
  })

  it('does not mark it for a programmer effect, while blind, or without an entry', () => {
    const band = { ...cuePulse, programmerOwned: true }
    expect(source({ entry: entry(), provenance: prov({}) }, { effects: [band] }).heldBack).toBe(false)
    expect(source({ entry: entry(), provenance: prov({}) }, { effects: [cuePulse], blind: true }).heldBack).toBe(false)
    expect(source({ provenance: prov({ source: 'FX', fxId: 9 }) }, { effects: [cuePulse] }).heldBack).toBe(false)
  })

  it('says the value is staged while blind', () => {
    expect(source({ entry: entry() }, { blind: true }).staged).toBe(true)
  })
})

describe('fxReaching', () => {
  it('takes effects on the head and on groups the fixture is in', () => {
    const own = makeActiveFx({ id: 1, targetKey: 'spot-3' })
    const group = makeActiveFx({ id: 2, targetKey: 'Front wash', isGroupTarget: true })
    const other = makeActiveFx({ id: 3, targetKey: 'spot-4' })
    const otherGroup = makeActiveFx({ id: 4, targetKey: 'Back wash', isGroupTarget: true })
    expect(fxReaching([own, group, other, otherGroup], ['spot-3'], ['Front wash']).map((e) => e.id)).toEqual([1, 2])
  })

  it('takes an effect over a selection that names the head or one of its groups', () => {
    const names = makeActiveFx({
      id: 6, targetKey: '[…]', targetType: 'selection',
      targets: [{ type: 'fixture', key: 'spot-9' }, { type: 'fixture', key: 'spot-3' }],
    })
    const viaGroup = makeActiveFx({
      id: 7, targetKey: '[…]', targetType: 'selection',
      targets: [{ type: 'group', key: 'Front wash' }, { type: 'fixture', key: 'spot-9' }],
    })
    const neither = makeActiveFx({
      id: 8, targetKey: '[…]', targetType: 'selection', targets: [{ type: 'fixture', key: 'spot-9' }],
    })
    expect(fxReaching([names, viaGroup, neither], ['spot-3'], ['Front wash']).map((e) => e.id)).toEqual([6, 7])
  })

  it("lets a head's row see an effect on its fixture, which the desk paints onto the heads", () => {
    const onBar = makeActiveFx({ id: 5, targetKey: 'bar-1', attribute: 'rgbColour', programmerOwned: false })
    const reaching = fxReaching([onBar], ['bar-1.cell-2', 'bar-1'], [])
    expect(reaching.map((e) => e.id)).toEqual([5])
    // …and the head's own entry holds it back there.
    const s = rowSourceOf({
      headKey: 'bar-1.cell-2',
      states: [{ key: 'rgbColour', state: { entry: entry({ targetKey: 'bar-1.cell-2', attribute: 'rgbColour' }) } }],
      effects: reaching,
      blind: false,
      cueLabel: () => undefined,
      fxDetail: () => '',
    })
    expect(s.heldBack).toBe(true)
  })
})

describe('mergeRowSources — a row over a pick of heads (D13)', () => {
  const programmer: RowSource = { kind: 'programmer', label: 'Programmer', holds: true, touched: true, heldBack: false, staged: false }
  const cue: RowSource = { kind: 'cue', label: 'Q8', holds: false, touched: false, heldBack: true, staged: false }
  const parked: RowSource = { kind: 'parked', label: 'Parked', holds: false, touched: false, heldBack: false, staged: false }

  it('shows the strongest source with how many heads it holds, dashed where they differ', () => {
    const merged = mergeRowSources([
      { key: 'h1', source: programmer },
      { key: 'h2', source: programmer },
      { key: 'h3', source: cue },
      { key: 'h4', source: cue },
    ])
    expect(merged).toMatchObject({ kind: 'programmer', label: 'Programmer', count: 2, of: 4, mixed: true, holds: true, heldBack: true })
  })

  it('is not mixed when every head says the same', () => {
    expect(mergeRowSources([{ key: 'h1', source: cue }, { key: 'h2', source: cue }])).toMatchObject({ count: 2, of: 2, mixed: false })
  })

  it('puts a park first, as the grid’s collapse does', () => {
    expect(mergeRowSources([{ key: 'h1', source: programmer }, { key: 'h2', source: parked }])).toMatchObject({ kind: 'parked', count: 1 })
  })
})
