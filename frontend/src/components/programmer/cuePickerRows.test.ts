import { describe, expect, it } from 'vitest'
import type { IncludedTarget } from '@/api/programmerWsApi'
import {
  cuePickerSections,
  filterCuePickerRows,
  filterCuePickerSections,
  preselectedRecordCue,
  type CuePickerSource,
  type CuePickerStack,
} from './cuePickerRows'

/**
 * The "An existing cue…" picker's rows: every cue in show order under its stack's divider (D15),
 * labelled with every entry naming it (cue model plan session 5) — and a cue with none, loose, is
 * still a cue.
 */
const ACT_1: CuePickerStack = { id: 1, name: 'Act 1', type: 'STACK', inRunningOrder: true }
const ACT_2: CuePickerStack = { id: 2, name: 'Act 2', type: 'STACK', inRunningOrder: true }
const STORM: CuePickerStack = { id: 3, name: 'Storm pads', type: 'STACK', inRunningOrder: false }
const STACKS = [STORM, ACT_1, ACT_2]

type Place = NonNullable<CuePickerSource['entries']>[number]
const at = (stack: CuePickerStack, sortOrder: number, cueNumber: string | null = null, cueNumberAuto = false): Place => ({
  stackId: stack.id,
  stackName: stack.name,
  sortOrder,
  cueNumber,
  cueNumberAuto,
})
const cue = (over: Partial<CuePickerSource> & { id: number; name: string }): CuePickerSource => ({
  entries: [at(ACT_1, 0)],
  cueType: 'STANDARD',
  ...over,
})
const outline = (cues: CuePickerSource[], stacks: readonly CuePickerStack[] | null = STACKS) =>
  cuePickerSections(cues, stacks ?? undefined).map((s) => [s.label, s.busk, s.rows.map((row) => row.name)])

describe('cuePickerSections', () => {
  it('lists the cues in show order under each stack, the busk stacks after, loose last by name', () => {
    expect(
      outline([
        cue({ id: 1, name: 'Bows', entries: [at(ACT_2, 4, '30')] }),
        cue({ id: 2, name: 'Thunder', entries: [at(STORM, 0)] }),
        cue({ id: 3, name: 'Scene 10', entries: [] }),
        cue({ id: 4, name: 'Preset', entries: [at(ACT_1, 0, '1')] }),
        cue({ id: 5, name: 'Scene 2', entries: undefined }),
        cue({ id: 6, name: 'Interval', entries: [at(ACT_2, 0, '20')] }),
      ]),
    ).toEqual([
      ['Act 1', false, ['Preset']],
      ['Act 2', false, ['Interval', 'Bows']],
      ['Storm pads', true, ['Thunder']],
      ['Loose', false, ['Scene 2', 'Scene 10']],
    ])
  })

  it('reads by name while the stacks load', () => {
    expect(outline([cue({ id: 1, name: 'b' }), cue({ id: 2, name: 'a' })], null)).toEqual([['Loose', false, ['a', 'b']]])
  })

  it('lists a shared cue once, at its first place, naming every place in show order', () => {
    const sections = cuePickerSections(
      [cue({ id: 1, name: 'Storm', entries: [at(STORM, 0), at(ACT_2, 3, '14', true), at(ACT_1, 6, '7')] })],
      STACKS,
    )
    expect(sections.map((s) => s.label)).toEqual(['Act 1'])
    expect(sections[0].rows[0].places).toEqual([
      { number: 'Q7', numberAuto: false, stackName: 'Act 1' },
      { number: 'Q14', numberAuto: true, stackName: 'Act 2' },
      { number: null, numberAuto: false, stackName: 'Storm pads' },
    ])
  })

  it('leaves out a marker', () => {
    expect(cuePickerSections([cue({ id: 1, name: 'Interval', cueType: 'MARKER' })], STACKS)).toEqual([])
  })
})

describe('filtering', () => {
  const sections = cuePickerSections(
    [
      cue({ id: 1, name: 'Storm', entries: [at(ACT_1, 0, '7')] }),
      cue({ id: 2, name: 'Bows', entries: [at(ACT_1, 1, '29'), at(ACT_2, 0, '30')] }),
      cue({ id: 3, name: 'Loose wash', entries: [] }),
    ],
    STACKS,
  )
  const rows = sections.flatMap((s) => s.rows)

  it('matches the name, and any place’s number or stack, ignoring case', () => {
    expect(filterCuePickerRows(rows, 'storm').map((r) => r.id)).toEqual([1])
    expect(filterCuePickerRows(rows, 'q30').map((r) => r.id)).toEqual([2])
    expect(filterCuePickerRows(rows, 'ACT 2').map((r) => r.id)).toEqual([2])
    expect(filterCuePickerRows(rows, 'act 1').map((r) => r.id)).toEqual([1, 2])
    expect(filterCuePickerRows(rows, 'wash').map((r) => r.id)).toEqual([3])
  })

  it('keeps a divider only over a match, and every one for a blank filter', () => {
    expect(filterCuePickerSections(sections, 'wash').map((s) => s.label)).toEqual(['Loose'])
    expect(filterCuePickerSections(sections, '  ').map((s) => s.label)).toEqual(['Act 1', 'Loose'])
  })
})

describe('preselectedRecordCue (D16)', () => {
  const stacks = [
    { id: 1, activeCueId: 11 },
    { id: 2, activeCueId: null },
  ]
  const included: IncludedTarget = { kind: 'CUE', cueId: 42, cueName: 'Storm', cueNumber: '7' } as IncludedTarget

  it('is the included cue, whatever is playing', () => {
    expect(preselectedRecordCue(included, 1, stacks)).toBe(42)
  })

  it('is the playhead stack’s live cue with no cue included', () => {
    expect(preselectedRecordCue(null, 1, stacks)).toBe(11)
    expect(preselectedRecordCue({ kind: 'LOOK', lookId: 3 } as IncludedTarget, 1, stacks)).toBe(11)
  })

  it('is nothing with the show stopped, or its stack on no cue', () => {
    expect(preselectedRecordCue(null, null, stacks)).toBeNull()
    expect(preselectedRecordCue(null, 2, stacks)).toBeNull()
  })
})
