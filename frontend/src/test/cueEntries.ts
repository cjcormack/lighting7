import type { CueStackCueRow, CueStackMarkerRow } from '@/api/cueStacksApi'

// Rows of a stack's list for tests. Type-only imports, so this never touches the store.

/**
 * A cue entry placing cue `id`. Its `entryId` defaults to `id + 100`, so a test that sends or
 * reads the wrong id of the two fails rather than passing by coincidence.
 */
export function cueRow(id: number, over: Partial<CueStackCueRow> = {}): CueStackCueRow {
  return {
    id,
    entryId: id + 100,
    name: `Cue ${id}`,
    sortOrder: id,
    layerCount: 0,
    adHocEffectCount: 0,
    autoAdvance: false,
    autoAdvanceDelayMs: null,
    fadeDurationMs: 1000,
    fadeCurve: 'LINEAR',
    cueNumber: String(id),
    cueNumberAuto: false,
    notes: null,
    cueType: 'STANDARD',
    ...over,
  }
}

/** A marker entry — a labelled divider with no cue, keyed by its entry id alone. */
export function markerRow(entryId: number, name = 'Marker', over: Partial<CueStackMarkerRow> = {}): CueStackMarkerRow {
  return {
    id: null,
    entryId,
    name,
    sortOrder: 0,
    layerCount: 0,
    adHocEffectCount: 0,
    autoAdvance: false,
    autoAdvanceDelayMs: null,
    fadeDurationMs: null,
    fadeCurve: 'LINEAR',
    cueNumber: null,
    cueNumberAuto: false,
    notes: null,
    cueType: 'MARKER',
    ...over,
  }
}
