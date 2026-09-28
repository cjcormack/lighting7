import { describe, expect, it } from 'vitest'
import { lookIsApplied, lookLayerPresence, templateLayerPresence } from './lookPresence'
import type { AppliedTarget, ProgrammerAppliedSource } from '@/store/programmer'
import type { CueTarget } from '@/api/cuesApi'

/** The desk's answer for one record: "Warm Wash" applied to whatever [targets] say. */
function applied(
  targets: AppliedTarget[],
  overrides: Partial<ProgrammerAppliedSource> = {},
): ProgrammerAppliedSource {
  return {
    source: { kind: 'LOOK', id: 7, uuid: 'u7', name: 'Warm Wash' },
    targets,
    ...overrides,
  }
}

const FRONT: CueTarget = { type: 'group', key: 'front-wash' }
const BACK: CueTarget = { type: 'group', key: 'back-wash' }

/** A group the desk reports as wholly covered, plus the heads that make it so. */
const FRONT_ALL: AppliedTarget[] = [
  { type: 'group', key: 'front-wash', state: 'all' },
  { type: 'fixture', key: 'hex-1', state: 'all' },
  { type: 'fixture', key: 'hex-2', state: 'all' },
]

describe('lookLayerPresence', () => {
  it('lights the ring for a Look made only of static rows', () => {
    // The regression this replaces: the old match read the effect list, so a rows-only Look could
    // never light its ring however plainly it was on stage. A layer exists either way, and the
    // desk resolves the layer.
    expect(lookLayerPresence([applied(FRONT_ALL)], [FRONT], 7)).toBe('all')
  })

  it('reads none when the desk reports no layer for this Look', () => {
    const other = applied(FRONT_ALL, { source: { kind: 'LOOK', id: 8, uuid: 'u8', name: 'Cool' } })
    expect(lookLayerPresence([other], [FRONT], 7)).toBe('none')
    expect(lookLayerPresence([], [FRONT], 7)).toBe('none')
  })

  it('reads some when only part of the selection is covered', () => {
    expect(lookLayerPresence([applied(FRONT_ALL)], [FRONT, BACK], 7)).toBe('some')
  })

  it('passes a partly-covered group straight through as some', () => {
    // Half the wash's heads: `some` is the desk's word, and the whole of the client's job is not
    // to lose it. Working out which half is covered was the rule that used to live here.
    const half: AppliedTarget[] = [
      { type: 'group', key: 'front-wash', state: 'some' },
      { type: 'fixture', key: 'hex-1', state: 'all' },
    ]
    expect(lookLayerPresence([applied(half)], [FRONT], 7)).toBe('some')
    expect(lookLayerPresence([applied(half)], [{ type: 'fixture', key: 'hex-1' }], 7)).toBe('all')
  })

  it('lights for a member of a group the layer names', () => {
    // A group is its fixtures — expanded server-side, so a head of a lit wash arrives lit. Before
    // the desk answered this, the ring went dark the moment the operator picked one head out of
    // the group they had just lit.
    expect(lookLayerPresence([applied(FRONT_ALL)], [{ type: 'fixture', key: 'hex-2' }], 7)).toBe('all')
  })

  it('distinguishes a fixture from a group of the same name', () => {
    // A pad must never answer for the other kind of target: the two share a namespace on the wire.
    expect(lookLayerPresence([applied(FRONT_ALL)], [{ type: 'fixture', key: 'front-wash' }], 7)).toBe('none')
  })

  it('reads none with nothing selected', () => {
    expect(lookLayerPresence([applied(FRONT_ALL)], [], 7)).toBe('none')
  })

  it('reads none for a record the desk lists with no targets', () => {
    // What a layer with empty targets resolves to: its source's own bound rows decide where it
    // lands, which is the cook's answer to give, so the desk reports it as covering nothing.
    expect(lookLayerPresence([applied([])], [FRONT], 7)).toBe('none')
  })
})

const templateApplied = (targets: AppliedTarget[]) =>
  applied(targets, { source: { kind: 'TEMPLATE', id: 4, uuid: 'ut4', name: 'Amber Breathe' } })

describe('cells (busk-further plan D11)', () => {
  // A cell is `{type: 'fixture', key: <elementKey>}` and the desk's `TargetCoverage` owns the
  // parent↔cell rule: a layer on the whole bar covers a press on its cells, a layer on four cells
  // does not cover the bar. Nothing here computes that — the ring reads the extents the desk
  // resolved, so a cell selection is lit by what `applied` names and by nothing else.
  const bar = { type: 'fixture', key: 'bar-1' } as const
  const cells = [0, 1, 2].map((i) => ({ type: 'fixture', key: `bar-1.pixel-${i}` }) as const)

  it('lights a cell selection only from the desk’s resolved extents, never from its parent here', () => {
    // The desk names the parent and not the cells: this side does not widen it to them.
    const parentOnly = [{ source: { kind: 'LOOK', id: 5, name: 'Wash' }, targets: [{ ...bar, state: 'all' }] }]
    expect(lookLayerPresence(parentOnly as never, cells, 5)).toBe('none')
    // The desk resolved the cells (`appliedState`'s extents fold the parent onto them): lit.
    const resolved = [{ source: { kind: 'LOOK', id: 5, name: 'Wash' }, targets: cells.map((c) => ({ ...c, state: 'all' })) }]
    expect(lookLayerPresence(resolved as never, cells, 5)).toBe('all')
    expect(lookLayerPresence(resolved as never, [cells[0]!, { type: 'fixture', key: 'bar-1.pixel-9' }], 5)).toBe('some')
  })

  it('does not read a parent as covered by its cells — that direction is the desk’s to refuse too', () => {
    const cellsOnly = [{ source: { kind: 'TEMPLATE', id: 2, name: 'Amber' }, targets: cells.map((c) => ({ ...c, state: 'all' })) }]
    expect(templateLayerPresence(cellsOnly as never, [bar], 2)).toBe('none')
  })
})

describe('templateLayerPresence', () => {
  it('lights the ring from the applied state, whatever the template holds', () => {
    // The rule that matters since a template can hold an **effect**: presence is read from the
    // layer, never from the running instance. Matching on the instance would light for an effect
    // template and leave every value template's pad dark — the worst of both answers.
    expect(templateLayerPresence([templateApplied(FRONT_ALL)], [FRONT], 4)).toBe('all')
  })

  it('does not confuse a Look and a template sharing an int PK', () => {
    // Two tables, two id spaces — so the Look here carries the *template's* id, or the assertion
    // would pass on `7 !== 4` and never reach the kind check it exists to pin.
    const lookWithTemplateId = applied(FRONT_ALL, {
      source: { kind: 'LOOK', id: 4, uuid: 'u4', name: 'Warm Wash' },
    })
    expect(templateLayerPresence([lookWithTemplateId], [FRONT], 4)).toBe('none')
    expect(lookLayerPresence([templateApplied(FRONT_ALL)], [FRONT], 4)).toBe('none')
  })

  it('reads some when only part of the selection is covered', () => {
    expect(templateLayerPresence([templateApplied(FRONT_ALL)], [FRONT, BACK], 4)).toBe('some')
  })

  it('reads a covered head of a named group like a Look pad does', () => {
    expect(templateLayerPresence([templateApplied(FRONT_ALL)], [{ type: 'fixture', key: 'hex-2' }], 4)).toBe('all')
  })

  it('reads none with nothing selected, or with no record applying it', () => {
    expect(templateLayerPresence([templateApplied(FRONT_ALL)], [], 4)).toBe('none')
    expect(templateLayerPresence([], [FRONT], 4)).toBe('none')
    expect(templateLayerPresence([templateApplied(FRONT_ALL)], [FRONT], 5)).toBe('none')
  })
})

describe('lookIsApplied', () => {
  it('answers for a surface with no selection, where the target-scoped fold cannot', () => {
    // The whole reason the helper exists. An FX cue slot has no selection, so reading its tile
    // through `lookLayerPresence` would leave every Look dark however plainly it is on stage.
    const on = [applied(FRONT_ALL)]
    expect(lookLayerPresence(on, [], 7)).toBe('none')
    expect(lookIsApplied(on, 7)).toBe(true)
  })

  it('does not answer for a template carrying the same int PK', () => {
    // Two tables, two id spaces — the collision `lookLayerPresence` guards, asked of a surface
    // that has no targets to disambiguate with.
    const template = applied(FRONT_ALL, {
      source: { kind: 'TEMPLATE', id: 7, uuid: 't7', name: 'Amber Key' },
    })
    expect(lookIsApplied([template], 7)).toBe(false)
  })

  it('reads false for an empty feed, another Look, or a layer covering nothing', () => {
    expect(lookIsApplied([], 7)).toBe(false)
    expect(lookIsApplied([applied(FRONT_ALL, { source: { kind: 'LOOK', id: 8, uuid: 'u8', name: 'Cool' } })], 7)).toBe(false)
    expect(lookIsApplied([applied([])], 7)).toBe(false)
  })
})
