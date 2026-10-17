import { describe, expect, it } from 'vitest'
import type { ProjectSummary } from '../api/projectApi'
import type { AttachRename } from '../api/venueApi'
import {
  attachAnswersFor,
  attachRefusalOf,
  attachVenuesOf,
  attachVersionsOf,
  inheritedLineOf,
  matchingLineOf,
  NO_ORIGIN_LINE,
  reachLineOf,
  staleAnswerIdOf,
} from './venueAttach'

const venue = (id: number, name: string, version: number | null): ProjectSummary => ({
  id,
  name,
  description: null,
  isCurrent: false,
  kind: 'VENUE',
  venueSummary: { version },
})
const ORIGIN = { venueUuid: 'u', version: 7, venueId: 16, venueName: 'TCH venue' }

describe('attachVenuesOf', () => {
  it('puts the origin venue first, then by name, and leaves shows out', () => {
    const list = [venue(3, 'Zed hall', 1), venue(16, 'TCH venue', 8), venue(4, 'Alpha', null), { ...venue(9, 'A show', null), kind: 'SHOW' as const }]
    expect(attachVenuesOf(list, ORIGIN).map((v) => [v.name, v.origin])).toEqual([
      ['TCH venue', true],
      ['Alpha', false],
      ['Zed hall', false],
    ])
    expect(attachVenuesOf(list, null).map((v) => v.name)).toEqual(['Alpha', 'TCH venue', 'Zed hall'])
  })
})

describe('attachVersionsOf', () => {
  const tch = { id: 16, name: 'TCH venue', installed: 8, origin: true }
  it('offers the installed version, and the origin’s when it is older and here', () => {
    expect(attachVersionsOf(tch, ORIGIN, true)).toEqual([
      { number: 8, marks: ['installed'] },
      { number: 7, marks: ['origin'] },
    ])
    expect(attachVersionsOf(tch, ORIGIN, false)).toEqual([{ number: 8, marks: ['installed'] }])
    expect(attachVersionsOf({ ...tch, origin: false }, ORIGIN, true)).toEqual([{ number: 8, marks: ['installed'] }])
    expect(attachVersionsOf({ ...tch, installed: 7 }, ORIGIN, true)).toEqual([{ number: 7, marks: ['installed', 'origin'] }])
    expect(attachVersionsOf({ ...tch, installed: null }, ORIGIN, true)).toEqual([])
  })
})

describe('matchingLineOf', () => {
  it('reads three-way, no origin, or the desk’s reason', () => {
    expect(matchingLineOf('TCH Test', null, undefined)).toBe(NO_ORIGIN_LINE)
    expect(matchingLineOf('TCH Test', ORIGIN, undefined)).toBeNull()
    expect(matchingLineOf('TCH Test', ORIGIN, { origin: { venueUuid: 'u', version: 7, status: 'USED', detail: 'x' } })).toBe(
      'Using v7, the version TCH Test left.',
    )
    expect(matchingLineOf('TCH Test', ORIGIN, { origin: { venueUuid: 'u', version: 7, status: 'MISSING', detail: 'Not here' } })).toBe('Not here.')
  })
})

describe('reachLineOf', () => {
  const rename = (usedBy: AttachRename['usedBy']): AttachRename => ({
    kind: 'PATCH',
    from: 'hex-7',
    to: 'hex-07',
    show: { uuid: 's', name: 'Hex 7' },
    venue: { uuid: 'v', name: 'Hex 7' },
    usedBy,
  })
  it('counts who names the key, and says scripts apart', () => {
    expect(
      reachLineOf(
        rename([
          { kind: 'CUE', id: 1, name: 'Q1' },
          { kind: 'LOOK', id: 2, name: 'Warm' },
          { kind: 'LOOK', id: 3, name: 'Cold' },
          { kind: 'SCRIPT', id: 4, name: 'chase' },
        ]),
      ),
    ).toBe('renames hex-7 → hex-07 in 1 cue, 2 Looks; 1 script still names hex-7')
    expect(reachLineOf(rename([]))).toBe('renames hex-7 → hex-07; nothing names hex-7')
  })
})

describe('inheritedLineOf', () => {
  it('lists the kinds in the patch list’s order, and none as null', () => {
    expect(inheritedLineOf({ RIGGING: 7, UNIVERSE: 4, PATCH: 54 })).toBe('54 fixtures, 4 universes, 7 riggings')
    expect(inheritedLineOf({ PATCH: 1 })).toBe('1 fixture')
    expect(inheritedLineOf({})).toBeNull()
  })
})

describe('answers', () => {
  const answers = { 'overlap:b': { answer: 'READDRESS_MINE' as const }, 'identity:a': { answer: 'TAKE_VENUES' as const } }
  const review = { decisions: [{ id: 'identity:a' }], overlaps: [{ id: 'overlap:b', suggestedStartChannel: 450 }] } as never

  it('sends them in id order; a dry run leaves Re-address mine’s start to the desk', () => {
    expect(attachAnswersFor(answers)).toEqual([
      { id: 'identity:a', answer: 'TAKE_VENUES' },
      { id: 'overlap:b', answer: 'READDRESS_MINE' },
    ])
  })

  it('the attach sends only what the review asks, Re-address mine with the start it suggested', () => {
    expect(attachAnswersFor(answers, review)).toEqual([
      { id: 'identity:a', answer: 'TAKE_VENUES' },
      { id: 'overlap:b', answer: 'READDRESS_MINE', startChannel: 450 },
    ])
    expect(attachAnswersFor(answers, { decisions: [], overlaps: [] })).toEqual([])
  })

  it('names the answer a dry run refused as one the desk no longer asks', () => {
    expect(staleAnswerIdOf({ status: 400, data: { error: "No decision 'group:x' in this attach", code: 'ATTACH_INVALID' } })).toBe('group:x')
    expect(staleAnswerIdOf({ status: 400, data: { error: "'READDRESS_MINE' does not answer 'overlap:a:b': it takes KEEP_BOTH | REMOVE_MINE", code: 'ATTACH_INVALID' } })).toBe('overlap:a:b')
    expect(staleAnswerIdOf({ status: 400, data: { error: 'venue is required: the venue to attach to', code: 'ATTACH_INVALID' } })).toBeNull()
    expect(staleAnswerIdOf({ status: 409, data: { error: 'x', code: 'ATTACH_BLOCKED' } })).toBeNull()
  })

  it('refuses the attach on a blocker or an unanswered overlap', () => {
    expect(attachRefusalOf(undefined, {})).toBe('Reading the review…')
    expect(attachRefusalOf({ blockers: [{ kind: 'PATCH', detail: 'x' }], overlaps: [] }, {})).toBe("Change the show first: a clash can't be kept.")
    const overlaps = [{ id: 'o' }, { id: 'p' }] as never
    expect(attachRefusalOf({ overlaps }, {})).toBe('2 address overlaps still need a decision.')
    expect(attachRefusalOf({ overlaps }, { o: { answer: 'KEEP_BOTH' }, p: { answer: 'KEEP_BOTH' } })).toBeNull()
  })
})
