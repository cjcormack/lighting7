// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import type { RecordLookResponse, RecordResponse, UpdateResponse } from '@/store/programmerOps'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { includeNote, recordLookNote, recordNote, updateNote } from './writeToasts'

/**
 * What the four programmer writes say once their sheet has closed: the counts as the title, the
 * details under it, amber when the desk skipped or warned.
 */
const record = (over: Partial<RecordResponse> = {}): RecordResponse =>
  ({
    cue: { name: 'Storm' }, created: true, assignmentsWritten: 3, assignmentsRemoved: 0, groupRowsEmitted: 0,
    fxWritten: 0, skipped: [], preserved: {}, warnings: [], republishedLive: false, sceneryWritten: 0,
    ...over,
  }) as unknown as RecordResponse

describe('recordNote', () => {
  it('names the cue and the count, with nothing under it for a plain record', () => {
    expect(recordNote(record())).toEqual({ title: 'Created “Storm” — 3 values written.', description: undefined, warn: false })
  })

  it('puts the details under the title and turns amber on a skip or a warning', () => {
    const note = recordNote(
      record({ created: false, assignmentsRemoved: 1, fxWritten: 2, republishedLive: true, warnings: ['Cue 9 is live elsewhere'] }),
    )
    expect(note.title).toBe('Updated “Storm” — 3 values written, 1 removed.')
    expect(note.description).toBe('2 FX · the live cue was republished — Cue 9 is live elsewhere')
    expect(note.warn).toBe(true)
    expect(recordNote(record({ skipped: [{ reason: 'MASKED_OUT' }] as RecordResponse['skipped'] })).warn).toBe(true)
  })
})

describe('recordLookNote', () => {
  it('counts rows and the effects moved in', () => {
    const note = recordLookNote({
      look: { name: 'Night' }, created: true, rowsWritten: 1, rowsRemoved: 0, effectsWritten: 2, groupRowsEmitted: 0,
      skipped: [], programmerKeysRefreshed: 0, cuesRepublished: [], sceneryWritten: 0,
    } as unknown as RecordLookResponse)
    expect(note).toEqual({ title: 'Created “Night” — 1 row written, 2 FX moved in.', description: undefined, warn: false })
  })
})

describe('includeNote', () => {
  it('turns amber when it left timed effects or entries out', () => {
    const note = includeNote({ name: 'Storm', entriesWritten: 2, fxSpawned: 0, fxAlreadyRunning: 0, fxTimedSkipped: 1, skipped: [] } as never)
    expect(note).toEqual({ title: 'Included “Storm” — 2 values.', description: '1 timed FX not included.', warn: true })
    expect(includeNote({ name: 'Storm', entriesWritten: 2, fxSpawned: 0, fxAlreadyRunning: 0, skipped: [{ reason: 'MASKED_OUT' }] } as never).warn).toBe(true)
  })

  it('says how many effects were already running, under the count', () => {
    expect(includeNote({ name: 'Storm', entriesWritten: 2, fxSpawned: 1, fxAlreadyRunning: 1 } as never)).toEqual({
      title: 'Included “Storm” — 2 values, 1 FX.',
      description: '1 FX already running on stage.',
      warn: false,
    })
  })
})

describe('updateNote', () => {
  const cue = (cueId: number, assignmentsWritten: number) => ({ cueId, cueName: `Cue ${cueId}`, assignmentsWritten, republishedLive: false })

  it('is the one line for one cue, and a count over the lines for several', () => {
    expect(updateNote({ applied: true, results: [cue(3, 1)], warnings: [] } as unknown as UpdateResponse).title).toBe(
      'Updated “Cue 3” — 1 value written.',
    )
    const several = updateNote({ applied: true, results: [cue(3, 1), cue(4, 2)], warnings: [] } as unknown as UpdateResponse)
    expect(several.title).toBe('Updated 2 cues.')
    expect(several.description).toBe('Updated “Cue 3” — 1 value written. Updated “Cue 4” — 2 values written.')
  })

  it('turns amber when the desk skipped entries', () => {
    const note = updateNote({ applied: true, results: [cue(3, 1)], skipped: [{ reason: 'MASKED_OUT' }], warnings: [] } as unknown as UpdateResponse)
    expect(note.warn).toBe(true)
    expect(note.description).toBe('Skipped: 1 entries outside the attribute mask.')
  })

  it('says how many live cues a Look update moved', () => {
    const note = updateNote({
      applied: true, results: [], warnings: [],
      lookResult: { lookName: 'Night', rowsWritten: 4, effectsWritten: 0, sceneryWritten: 1, cuesRepublished: [7, 8] },
    } as unknown as UpdateResponse)
    expect(note.title).toBe('Updated “Night” — 4 rows written, 1 scenery change, 2 live cue(s) moved with it.')
  })

  it('counts the rows a cleared value took out of the Look or cue', () => {
    const look = updateNote({
      applied: true, mode: 'A', results: [], warnings: [],
      lookResult: { lookName: 'Set — Market', rowsWritten: 0, rowsRemoved: 2, cuesRepublished: [] },
    } as unknown as UpdateResponse)
    expect(look.title).toBe('Updated “Set — Market” — 0 rows written, 2 removed.')
    const cueNote = updateNote({
      applied: true, mode: 'A', results: [{ ...cue(3, 1), assignmentsRemoved: 1 }], warnings: [],
    } as unknown as UpdateResponse)
    expect(cueNote.title).toBe('Updated “Cue 3” — 1 value written, 1 removed.')
  })

  it('says nothing was written, and claims nothing about why', () => {
    const note = updateNote({
      applied: false, mode: 'A', results: [], warnings: [],
      lookResult: { lookName: 'Night', rowsWritten: 0, rowsRemoved: 0, cuesRepublished: [] },
    } as unknown as UpdateResponse)
    expect(note.title).toBe('Nothing written to “Night”.')
    expect(note.warn).toBe(false)
  })
})
