import { describe, expect, it } from 'vitest'
import { lookIsApplied, lookPresence, paletteLayerPresence } from './lookPresence'
import type { AppliedTarget, ProgrammerAppliedSource } from '@/store/programmer'
import type { CueTarget } from '@/api/cuesApi'

/** The desk's answer for one palette: "Amber Breathe" applied to whatever [targets] say. */
function paletteApplied(
  targets: AppliedTarget[],
  overrides: Partial<ProgrammerAppliedSource> = {},
): ProgrammerAppliedSource {
  return {
    source: { kind: 'PALETTE', id: 4, uuid: 'ut4', name: 'Amber Breathe' },
    targets,
    ...overrides,
  }
}

/** The desk's answer for a Look with a layer on: it names no targets (absolute-looks plan P4). */
function lookApplied(id = 7): ProgrammerAppliedSource {
  return { source: { kind: 'LOOK', id, uuid: `u${id}`, name: 'Warm Wash' }, targets: [] }
}

const FRONT: CueTarget = { type: 'group', key: 'front-wash' }
const BACK: CueTarget = { type: 'group', key: 'back-wash' }

/** A group the desk reports as wholly covered, plus the heads that make it so. */
const FRONT_ALL: AppliedTarget[] = [
  { type: 'group', key: 'front-wash', state: 'all' },
  { type: 'fixture', key: 'hex-1', state: 'all' },
  { type: 'fixture', key: 'hex-2', state: 'all' },
]

describe('lookPresence', () => {
  it('lights while a layer of the Look is on, whatever is selected', () => {
    // A Look lands whole, so its ring reads the layer, never coverage of the selection.
    expect(lookPresence([lookApplied()], 7)).toBe('all')
    expect(lookIsApplied([lookApplied()], 7)).toBe(true)
  })

  it('reads none when the desk reports no layer for this Look', () => {
    expect(lookPresence([lookApplied(8)], 7)).toBe('none')
    expect(lookPresence([], 7)).toBe('none')
    expect(lookIsApplied([], 7)).toBe(false)
  })

  it('does not answer for a palette carrying the same int PK', () => {
    // Two tables, two id spaces.
    expect(lookPresence([paletteApplied(FRONT_ALL, { source: { kind: 'PALETTE', id: 7, uuid: 't7', name: 'Amber Key' } })], 7)).toBe('none')
  })
})

describe('paletteLayerPresence', () => {
  it('lights the ring from the applied state, whatever the palette holds', () => {
    // The rule that matters since a palette can hold an **FX**: presence is read from the layer,
    // never from the running instance. Matching on the instance would light for an FX palette and
    // leave every value palette's pad dark — the worst of both answers.
    expect(paletteLayerPresence([paletteApplied(FRONT_ALL)], [FRONT], 4)).toBe('all')
  })

  it('does not confuse a Look and a palette sharing an int PK', () => {
    // The Look carries the *palette's* id, or the assertion would pass on `7 !== 4` and never reach
    // the kind check it exists to pin.
    expect(paletteLayerPresence([lookApplied(4)], [FRONT], 4)).toBe('none')
  })

  it('reads some when only part of the selection is covered', () => {
    expect(paletteLayerPresence([paletteApplied(FRONT_ALL)], [FRONT, BACK], 4)).toBe('some')
  })

  it('passes a partly-covered group straight through as some', () => {
    // Half the wash's heads: `some` is the desk's word, and the whole of the client's job is not
    // to lose it.
    const half: AppliedTarget[] = [
      { type: 'group', key: 'front-wash', state: 'some' },
      { type: 'fixture', key: 'hex-1', state: 'all' },
    ]
    expect(paletteLayerPresence([paletteApplied(half)], [FRONT], 4)).toBe('some')
    expect(paletteLayerPresence([paletteApplied(half)], [{ type: 'fixture', key: 'hex-1' }], 4)).toBe('all')
  })

  it('reads a covered head of a named group as lit', () => {
    // A group is its fixtures — expanded server-side, so a head of a lit wash arrives lit.
    expect(paletteLayerPresence([paletteApplied(FRONT_ALL)], [{ type: 'fixture', key: 'hex-2' }], 4)).toBe('all')
  })

  it('distinguishes a fixture from a group of the same name', () => {
    expect(paletteLayerPresence([paletteApplied(FRONT_ALL)], [{ type: 'fixture', key: 'front-wash' }], 4)).toBe('none')
  })

  it('reads none with nothing selected, or with no record applying it', () => {
    expect(paletteLayerPresence([paletteApplied(FRONT_ALL)], [], 4)).toBe('none')
    expect(paletteLayerPresence([], [FRONT], 4)).toBe('none')
    expect(paletteLayerPresence([paletteApplied(FRONT_ALL)], [FRONT], 5)).toBe('none')
  })
})

describe('cells (busk-further plan D11)', () => {
  // A cell is `{type: 'fixture', key: <elementKey>}` and the desk's `TargetCoverage` owns the
  // parent↔cell rule. Nothing here computes that — the ring reads the extents the desk resolved.
  const bar = { type: 'fixture', key: 'bar-1' } as const
  const cells = [0, 1, 2].map((i) => ({ type: 'fixture', key: `bar-1.pixel-${i}` }) as const)

  it('lights a cell selection only from the desk’s resolved extents, never from its parent here', () => {
    const parentOnly = [paletteApplied([{ ...bar, state: 'all' }])]
    expect(paletteLayerPresence(parentOnly, cells, 4)).toBe('none')
    const resolved = [paletteApplied(cells.map((c) => ({ ...c, state: 'all' as const })))]
    expect(paletteLayerPresence(resolved, cells, 4)).toBe('all')
    expect(paletteLayerPresence(resolved, [cells[0]!, { type: 'fixture', key: 'bar-1.pixel-9' }], 4)).toBe('some')
  })

  it('does not read a parent as covered by its cells — that direction is the desk’s to refuse too', () => {
    const cellsOnly = [paletteApplied(cells.map((c) => ({ ...c, state: 'all' as const })))]
    expect(paletteLayerPresence(cellsOnly, [bar], 4)).toBe('none')
  })
})
