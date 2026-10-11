import { describe, expect, it } from 'vitest'
import type { VenueChange } from '../api/venueApi'
import { changeLine, changeSign, changesPhrase, draftChipLabel, draftMarker, fieldChangeLine, nextVersion } from './venue'

const patch = (over: Partial<VenueChange>): VenueChange => ({
  kind: 'PATCH',
  table: 'fixturePatches',
  uuid: 'u',
  name: 'HAZER',
  change: 'CHANGED',
  output: false,
  ...over,
})

describe('venue mode copy', () => {
  it('names the draft against the installed version, or none', () => {
    expect(draftChipLabel({ installed: 5, draft: { changes: 6, outputChanges: 4 } })).toBe('Draft · 6 changes since v5')
    expect(draftChipLabel({ installed: 1, draft: { changes: 1, outputChanges: 0 } })).toBe('Draft · 1 change since v1')
    expect(draftChipLabel({ installed: null, draft: { changes: 3, outputChanges: 3 } })).toBe('Draft · 3 changes, unpublished')
    expect(changesPhrase(0)).toBe('no changes')
    expect(nextVersion({ installed: 5 })).toBe(6)
    expect(nextVersion(undefined)).toBe(1)
  })

  it('marks a patch row as the board does', () => {
    expect(draftMarker(patch({ change: 'ADDED' }))).toBe('Added')
    expect(draftMarker(patch({ change: 'REMOVED' }))).toBe('Removed')
    expect(draftMarker(patch({ facets: ['ADDRESS'], fields: [{ field: 'address', from: '1-500', to: '1-512' }] })))
      .toBe('Address · 1-500 → 1-512')
    expect(draftMarker(patch({ facets: ['MEDIA'], fields: [{ field: 'gelCode', from: 'L201', to: 'L202' }] })))
      .toBe('Media · L201 → L202')
    expect(draftMarker(patch({ facets: ['POSITION'], fields: [{ field: 'stageX', from: '-1.2', to: '-0.9' }] })))
      .toBe('Position')
    expect(draftMarker(patch({ facets: ['POSITION', 'FOCUS'], fields: [{ field: 'stageX' }, { field: 'zoomDeg' }] })))
      .toBe('Position · Focus')
  })

  it('writes a change as a title and a detail', () => {
    expect(changeSign(patch({ change: 'ADDED' }))).toBe('+')
    expect(changeSign(patch({ change: 'REMOVED' }))).toBe('−')
    expect(changeLine(patch({ change: 'ADDED', name: 'LX3 BATTEN 1', address: '3-001' })))
      .toEqual({ title: 'LX3 BATTEN 1 · 3-001', detail: null })
    expect(changeLine(patch({ facets: ['ADDRESS'], fields: [{ field: 'address', from: '1-500', to: '1-512' }] })))
      .toEqual({ title: 'HAZER · 1-500 → 1-512', detail: null })
    expect(changeLine(patch({ name: 'LX2 FRES', facets: ['POSITION'], fields: [{ field: 'stageX', from: '-1.2', to: '-0.9' }] })))
      .toEqual({ title: 'LX2 FRES position', detail: 'x -1.2 → -0.9' })
    expect(changeLine({ kind: 'RIGGING', table: 'riggings', name: 'LX1', change: 'ADDED', output: false }).title)
      .toBe('LX1 (rigging)')
    expect(fieldChangeLine({ field: 'params', from: null, to: 'x'.repeat(40) })).toBe(`params — → ${'x'.repeat(23)}…`)
    expect(changeLine({ kind: 'OTHER', table: 'looks', name: 'Focus check', change: 'ADDED', output: false }).title)
      .toBe('Focus check (looks)')
  })
})
