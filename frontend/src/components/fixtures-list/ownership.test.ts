import { describe, expect, it, vi } from 'vitest'

// ownership.ts reaches the lighting API transitively, which opens a WebSocket at import time.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import {
  cellTitle,
  cueSourceCellClass,
  cueSourceTitle,
  lookLayerCellClass,
  referenceCellClass,
  referencePillText,
  referenceTitle,
} from './ownership'
import { aggregateCellOwnership, type CellReference } from './useRowOwnership'
import { cueSourceOf } from './useScopedRowValues'
import { referenceAcross } from './cellReference'
import type { ProgrammerEntry, ProgrammerKeyState } from '@/api/programmerWsApi'
import type { PaletteReference } from '@/api/paletteReference'

const DSR: PaletteReference = { paletteId: 7, paletteUuid: 'dsr', name: 'DSR Focus' }
const NIGHT: PaletteReference = { paletteId: 8, paletteUuid: 'night', name: 'Night' }

function yours(targetKey: string, reference?: PaletteReference): ProgrammerKeyState {
  const entry: ProgrammerEntry = {
    targetKey,
    attribute: 'position',
    value: '140,150',
    owner: 'web',
    touched: true,
    owners: ['web'],
    ...(reference ? { reference } : {}),
  }
  return { entry, provenance: { targetKey, attribute: 'position', source: 'PROGRAMMER', ...(reference ? { reference: reference.paletteUuid } : {}) } }
}

const keys = (...heads: string[]) => heads.map((targetKey) => ({ targetKey, attribute: 'position' }))
const lookup = (states: Record<string, ProgrammerKeyState>) => (head: string) => states[head] ?? {}

describe('a reference cell (palette-references plan D18)', () => {
  it('names the palette and is whole when every head references it', () => {
    const owned = aggregateCellOwnership(keys('spot-3'), false, lookup({ 'spot-3': yours('spot-3', DSR) }))
    expect(owned?.source).toBe('programmer')
    expect(owned?.reference).toEqual({ paletteUuid: 'dsr', name: 'DSR Focus', count: 1, of: 1, severalPalettes: false, unresolved: undefined })
    expect(referenceCellClass(owned?.reference)).toContain('inset_3px_0_0_var(--color-teal-400)')
    expect(referencePillText(owned!.reference!, owned!.reference!.name)).toBe('DSR Focus')
  })

  it('says whose it is and which row on hover', () => {
    const owned = aggregateCellOwnership(keys('spot-3'), false, lookup({ 'spot-3': yours('spot-3', DSR) }))
    expect(cellTitle(owned, undefined, 'Spot 3')).toBe('Yours: DSR Focus, Spot 3’s row')
  })

  it('a literal of yours has no reference', () => {
    const owned = aggregateCellOwnership(keys('spot-3'), false, lookup({ 'spot-3': yours('spot-3') }))
    expect(owned?.reference).toBeUndefined()
    expect(referenceCellClass(owned?.reference)).toBe('')
  })

  it('names a cue’s reference in Output from its provenance, by uuid alone', () => {
    const state: ProgrammerKeyState = { provenance: { targetKey: 'spot-3', attribute: 'position', source: 'CUE', cueId: 4, reference: 'dsr' } }
    const owned = aggregateCellOwnership(keys('spot-3'), false, lookup({ 'spot-3': state }))
    expect(owned?.reference).toMatchObject({ paletteUuid: 'dsr', name: undefined, count: 1, of: 1 })
    expect(cellTitle(owned, 'DSR Focus', 'Spot 3')).toBe('Cue: DSR Focus, Spot 3’s row')
  })

  it('an unresolved reference keeps its pill dashed and says why on hover (D3)', () => {
    const stale = { ...DSR, unresolved: "'DSR Focus' has no row for spot-3" }
    const owned = aggregateCellOwnership(keys('spot-3'), false, lookup({ 'spot-3': yours('spot-3', stale) }))
    expect(owned?.reference?.unresolved).toBe("'DSR Focus' has no row for spot-3")
    expect(cellTitle(owned, undefined, 'Spot 3')).toBe(
      "Yours: DSR Focus, Spot 3’s row · 'DSR Focus' has no row for spot-3 — keeps its last value",
    )
  })
})

describe('a group row that is partly references (D20)', () => {
  it('counts the heads and draws a dashed edge', () => {
    const states = { a: yours('a', DSR), b: yours('b', DSR), c: yours('c'), d: yours('d') }
    const owned = aggregateCellOwnership(keys('a', 'b', 'c', 'd'), false, lookup(states))
    const reference = owned!.reference!
    expect(reference).toMatchObject({ count: 2, of: 4, severalPalettes: false })
    expect(referencePillText(reference, reference.name)).toBe('DSR Focus · 2 of 4')
    expect(referenceCellClass(reference)).toContain('border-dashed')
    expect(referenceTitle('Yours', reference, reference.name)).toBe('Yours: DSR Focus on 2 of 4 heads')
  })

  it('counts a head once however many of its keys reference', () => {
    const both = (head: string) => ({ entry: { ...yours(head, DSR).entry! }, provenance: yours(head, DSR).provenance })
    const owned = aggregateCellOwnership(
      [
        { targetKey: 'a', attribute: 'pan' },
        { targetKey: 'a', attribute: 'tilt' },
      ],
      false,
      () => both('a'),
    )
    expect(owned?.reference).toMatchObject({ count: 1, of: 1 })
  })

  it('names no one palette across two', () => {
    const owned = aggregateCellOwnership(keys('a', 'b'), false, lookup({ a: yours('a', DSR), b: yours('b', NIGHT) }))
    const reference = owned!.reference!
    expect(reference).toMatchObject({ count: 2, of: 2, severalPalettes: true, paletteUuid: undefined, name: undefined })
    expect(referencePillText(reference, undefined)).toBe('Palettes · 2 of 2')
    expect(referenceCellClass(reference)).toContain('border-dashed')
  })
})

describe('a reference while Blind', () => {
  it('names the staged entry’s palette, never the cue’s underneath', () => {
    const state: ProgrammerKeyState = {
      entry: yours('spot-3', DSR).entry,
      provenance: { targetKey: 'spot-3', attribute: 'position', source: 'CUE', cueId: 4, reference: 'night' },
    }
    expect(aggregateCellOwnership(keys('spot-3'), true, () => state)?.reference).toMatchObject({ paletteUuid: 'dsr', name: 'DSR Focus' })
  })

  it('marks no reference over a staged literal, whatever the cue underneath holds', () => {
    const state: ProgrammerKeyState = {
      entry: yours('spot-3').entry,
      provenance: { targetKey: 'spot-3', attribute: 'position', source: 'CUE', cueId: 4, reference: 'night' },
    }
    expect(aggregateCellOwnership(keys('spot-3'), true, () => state)?.reference).toBeUndefined()
  })
})

describe('a Look layer’s mark (D19)', () => {
  it('is a violet edge where one Look won the cell, nothing where several did', () => {
    expect(lookLayerCellClass({ layerId: 1, mixed: false, name: 'Act 1 Spots' })).toContain('var(--color-violet-400)')
    expect(lookLayerCellClass({ mixed: true })).toBe('')
    expect(lookLayerCellClass(undefined)).toBe('')
  })
})

describe('the cue editor’s Values grid (D18)', () => {
  const own = (reference?: PaletteReference) =>
    ({ kind: 'own', targetType: 'fixture', targetKey: 'x', ...(reference ? { reference } : {}) }) as const

  it('keeps the cue’s ring and takes the reference’s teal edge', () => {
    const source = cueSourceOf([{ head: 'spot-3', source: own(DSR) }], ['spot-3'])
    expect(source.kind).toBe('reference')
    const cls = cueSourceCellClass(source)
    expect(cls).toContain('ring-blue-400/60')
    expect(cls).toContain('inset_3px_0_0_var(--color-teal-400)')
    expect(cueSourceTitle(source, 'Spot 3')).toBe('Own: DSR Focus, Spot 3’s row')
  })

  it('names a group’s own reference row as the group’s, and the Look it overrides', () => {
    const group = (head: string) => ({
      head,
      source: { kind: 'own', targetType: 'group', targetKey: 'Front', overrides: 'Scrim', reference: DSR } as const,
    })
    const source = cueSourceOf([group('a'), group('b')], ['a', 'b'])
    expect(source).toMatchObject({ kind: 'reference', group: 'Front', overrides: 'Scrim' })
    expect(cueSourceTitle(source)).toBe('Own: DSR Focus, Front’s row · overrides Scrim')
  })

  it('edges a Look-won cell violet, never the reference’s teal', () => {
    const cls = cueSourceCellClass({ kind: 'layer', name: 'Scrim' })
    expect(cls).toContain('var(--color-violet-400)')
    expect(cls).not.toContain('teal')
  })

  it('is a plain own row where no head references', () => {
    expect(cueSourceOf([{ head: 'spot-3', source: own() }], ['spot-3']).kind).toBe('own')
  })

  it('counts a group row’s referencing heads', () => {
    const source = cueSourceOf(
      [
        { head: 'a', source: own(DSR) },
        { head: 'b', source: own() },
      ],
      ['a', 'b'],
    )
    expect(source).toEqual({ kind: 'reference', reference: expect.objectContaining({ count: 1, of: 2 }) })
    expect(cueSourceCellClass(source)).toContain('border-dashed')
  })
})

describe('referenceAcross', () => {
  it('is null where nothing references', () => {
    expect(referenceAcross([{ head: 'a' }], ['a'])).toBeNull()
  })

  it('reads the first unresolved sentence', () => {
    const reference = referenceAcross(
      [
        { head: 'a', reference: DSR },
        { head: 'b', reference: { ...DSR, unresolved: 'gone' } },
      ],
      ['a', 'b'],
    ) as CellReference
    expect(reference).toMatchObject({ count: 2, of: 2, unresolved: 'gone', name: 'DSR Focus' })
  })
})
