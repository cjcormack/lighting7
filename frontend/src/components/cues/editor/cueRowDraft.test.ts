import { describe, expect, it, vi } from 'vitest'

// `parseProgrammerValue` reaches colourUtils, which reaches the api barrel.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { CueRowDraft, applyCueRowEntries, cueRowKey, restoreCueRows, sameCueValue } from './cueRowDraft'
import type { CueAttributeAssignment } from '@/api/cuesApi'

const STORED: CueAttributeAssignment[] = [
  { targetType: 'fixture', targetKey: 'a', attribute: 'colour', value: '#ff0000', fadeDurationMs: 1200, sortOrder: 2 },
  { targetType: 'group', targetKey: 'Front', attribute: 'dimmer', value: '100', sortOrder: 0 },
  { targetType: 'fixture', targetKey: 'a', attribute: 'position', value: '10,20', moveInDark: true, sortOrder: 5, health: { type: 'ok' } },
]

describe('applyCueRowEntries', () => {
  it('rewrites a row in place, keeping its fade, move-in-dark and order, and drops read-only fields', () => {
    const draft = new CueRowDraft()
    // The grid writes `rgbColour`; the row is stored as `colour` — one row, not two.
    draft.set('fixture', 'a', 'rgbColour', '#00FF00')
    expect(applyCueRowEntries(STORED, draft.snapshot())).toEqual([
      { targetType: 'fixture', targetKey: 'a', attribute: 'colour', value: '#00FF00', fadeDurationMs: 1200, sortOrder: 2 },
      { targetType: 'group', targetKey: 'Front', attribute: 'dimmer', value: '100', sortOrder: 0 },
      { targetType: 'fixture', targetKey: 'a', attribute: 'position', value: '10,20', moveInDark: true, sortOrder: 5 },
    ])
  })

  it('takes a removed row away and appends a new one after the last sort order', () => {
    const draft = new CueRowDraft()
    draft.remove('group', 'Front', 'dimmer')
    draft.set('fixture', 'b', 'dimmer', '200')
    const out = applyCueRowEntries(STORED, draft.snapshot())
    expect(out.find((r) => r.targetType === 'group')).toBeUndefined()
    expect(out.at(-1)).toEqual({ targetType: 'fixture', targetKey: 'b', attribute: 'dimmer', value: '200', sortOrder: 6 })
  })

  it('keeps a group row and a fixture row of one name apart', () => {
    expect(cueRowKey('group', 'x', 'dimmer')).not.toBe(cueRowKey('fixture', 'x', 'dimmer'))
  })
})

describe('CueRowDraft.reconcile', () => {
  it('retires an entry only once the cue agrees and a cook read after its save has landed', () => {
    const draft = new CueRowDraft()
    draft.set('fixture', 'a', 'dimmer', '204')
    const stored: CueAttributeAssignment[] = [{ targetType: 'fixture', targetKey: 'a', attribute: 'dimmer', value: '204' }]
    // Unsaved: the cue agreeing is another desk's edit, not ours landing — keep it.
    draft.reconcile(stored, 100)
    expect(draft.size).toBe(1)
    draft.markSaved(draft.snapshot(), 50)
    // A cook read before the save is stale.
    draft.reconcile(stored, 40)
    expect(draft.size).toBe(1)
    draft.reconcile(stored, 60)
    expect(draft.size).toBe(0)
  })

  it('retires a removal once the row is gone', () => {
    const draft = new CueRowDraft()
    draft.remove('fixture', 'a', 'dimmer')
    draft.markSaved(draft.snapshot(), 10)
    draft.reconcile([{ targetType: 'fixture', targetKey: 'a', attribute: 'dimmer', value: '1' }], 20)
    expect(draft.size).toBe(1)
    draft.reconcile([], 20)
    expect(draft.size).toBe(0)
  })

  it('reads two spellings of one value as one', () => {
    expect(sameCueValue('#ff8800', '#FF8800')).toBe(true)
    expect(sameCueValue('128', '129')).toBe(false)
  })
})

describe('restoreCueRows', () => {
  it('puts the changed rows back in their order and keeps every other row as it stands', () => {
    const now: CueAttributeAssignment[] = [
      { targetType: 'fixture', targetKey: 'a', attribute: 'dimmer', value: '1', sortOrder: 0 },
      { targetType: 'fixture', targetKey: 'b', attribute: 'dimmer', value: '99', sortOrder: 4 },
      { targetType: 'fixture', targetKey: 'c', attribute: 'dimmer', value: '7', sortOrder: 9 },
    ]
    const key = cueRowKey('fixture', 'b', 'dimmer')
    const added = cueRowKey('fixture', 'd', 'dimmer')
    const base = [{ targetType: 'fixture' as const, targetKey: 'b', attribute: 'dimmer', value: '20', fadeDurationMs: 300, sortOrder: 4 }]
    // b put back to 20 with its fade; d, which the save added, gone; a and c untouched.
    expect(restoreCueRows([...now, { targetType: 'fixture', targetKey: 'd', attribute: 'dimmer', value: '5', sortOrder: 10 }], [key, added], base)).toEqual([
      now[0],
      base[0],
      now[2],
    ])
  })
})
