import { describe, expect, it } from 'vitest'
import type { ProgrammerAppliedSource } from '@/api/programmerWsApi'
import { appliedSignature, describeHeads, replacementNotes } from './replacementNotes'

const RED = { kind: 'TEMPLATE' as const, id: 3, uuid: 'u3', name: 'Red' }
const AMBER = { kind: 'TEMPLATE' as const, id: 4, uuid: 'u4', name: 'Amber' }
const BLUE = { kind: 'TEMPLATE' as const, id: 5, uuid: 'u5', name: 'Blue' }

function on(source: typeof RED, ...keys: string[]): ProgrammerAppliedSource {
  return { source, targets: keys.map((key) => ({ type: 'fixture', key, state: 'all' as const })) }
}
const name = (key: string) => key.replace('hex-', 'Hex ')

describe('replacementNotes', () => {
  it('names the heads a record lost and the record that took them', () => {
    const notes = replacementNotes(
      [on(RED, 'hex-1', 'hex-2', 'hex-3', 'hex-4')],
      [on(RED, 'hex-1', 'hex-2'), on(AMBER, 'hex-3', 'hex-4')],
      name,
    )
    expect(notes.get('TEMPLATE:3')).toBe('lost Hex 3–4 to Amber')
    expect(notes.has('TEMPLATE:4')).toBe(false)
  })

  it('names each taker where two took heads in one frame', () => {
    const notes = replacementNotes(
      [on(RED, 'hex-1', 'hex-2'), on(BLUE)],
      [on(AMBER, 'hex-1'), on(BLUE, 'hex-2')],
      name,
    )
    expect(notes.get('TEMPLATE:3')).toBe('lost Hex 1 to Amber, Hex 2 to Blue')
  })

  it('says nothing for heads nobody gained — an off press, a removal', () => {
    expect(replacementNotes([on(RED, 'hex-1', 'hex-2')], [on(RED, 'hex-1')], name).size).toBe(0)
  })

  it('says nothing for a widening', () => {
    expect(replacementNotes([on(RED, 'hex-1')], [on(RED, 'hex-1', 'hex-2')], name).size).toBe(0)
  })

  it('ignores group-level and partial entries — heads are compared whole', () => {
    const before: ProgrammerAppliedSource[] = [
      { source: RED, targets: [{ type: 'group', key: 'wash', state: 'all' }, { type: 'fixture', key: 'hex-1', state: 'all' }] },
    ]
    const after: ProgrammerAppliedSource[] = [
      { source: RED, targets: [{ type: 'group', key: 'wash', state: 'some' }] },
      on(AMBER, 'hex-1'),
    ]
    expect(replacementNotes(before, after, name).get('TEMPLATE:3')).toBe('lost Hex 1 to Amber')
  })
})

describe('replacementNotes over a multi-head fixture', () => {
  it('names a bar lost whole once, not the bar and every cell', () => {
    const cells = (key: string) => (key.startsWith('bar-') && !key.includes(':') ? [`${key}:c1`, `${key}:c2`] : [])
    const bar = (source: typeof RED, ...bars: string[]) =>
      on(source, ...bars.flatMap((b) => [b, `${b}:c1`, `${b}:c2`]))
    const notes = replacementNotes([bar(RED, 'bar-1', 'bar-2')], [on(RED), bar(BLUE, 'bar-1', 'bar-2')], (k) => k.replace('bar-', 'Bar '), cells)
    expect(notes.get('TEMPLATE:3')).toBe('lost Bar 1–2 to Blue')
  })
})

describe('appliedSignature', () => {
  it('is the same for two frames that cover the same heads, whatever their order', () => {
    expect(appliedSignature([on(RED, 'hex-2', 'hex-1'), on(AMBER)])).toBe(
      appliedSignature([on(AMBER), on(RED, 'hex-1', 'hex-2')]),
    )
    expect(appliedSignature([on(RED, 'hex-1')])).not.toBe(appliedSignature([on(RED, 'hex-2')]))
  })
})

describe('describeHeads', () => {
  it('folds numbered runs and keeps the rest by name', () => {
    expect(describeHeads(['Hex 3', 'Hex 4', 'Hex 6'])).toBe('Hex 3–4, 6')
    expect(describeHeads(['Bar · Cell 2', 'Bar · Cell 1', 'Bar · Cell 3'])).toBe('Bar · Cell 1–3')
    expect(describeHeads(['Hex 1', 'Spot'])).toBe('Hex 1, Spot')
  })

  it('keeps the numbers as they are written', () => {
    expect(describeHeads(['Par 01', 'Par 02', 'Par 04'])).toBe('Par 01–02, 04')
  })

  it('counts what is past three parts', () => {
    expect(describeHeads(['A 1', 'B 1', 'C 1', 'D 1', 'E 1'])).toBe('A 1, B 1, C 1 +2')
  })
})
