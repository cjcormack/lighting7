import { describe, expect, it } from 'vitest'
import { recentPalettes, stripPalettes } from './paletteRecents'
import type { PaletteSummary } from '@/api/palettesApi'

/**
 * The row's ordering rule, which is not obvious in two places.
 *
 * The stamps are ISO strings from the server, and `Instant.toString()` drops the fractional part on
 * an exact second — so a lexicographic sort puts a press half a second *older* first about once in
 * a thousand. And the fallback is all-or-nothing: one recent draws one chip rather than one recent
 * padded out by name, because a padded row moves its own contents under the operator's hand.
 */
function template(over: Partial<PaletteSummary> = {}): PaletteSummary {
  return {
    id: 1,
    uuid: 'u1',
    name: 'Amber Key',
    notes: null,
    fadeDurationMs: null,
    family: 'COLOUR',
    isGeneric: true,
    kind: 'value',
    requiredEmitters: [],
    lastPressedAt: null,
    rows: [],
    fx: null,
    layerCount: 0,
    buskPageCount: 0,
    ...over,
  }
}

const names = (list: PaletteSummary[]) => list.map((t) => t.name)

describe('recentPalettes', () => {
  it('keeps only what has been pressed, most recent first', () => {
    expect(
      names(
        recentPalettes([
          template({ id: 1, name: 'Never' }),
          template({ id: 2, name: 'Older', lastPressedAt: '2026-09-14T10:00:00.000Z' }),
          template({ id: 3, name: 'Newer', lastPressedAt: '2026-09-14T10:00:02.000Z' }),
        ]),
      ),
    ).toEqual(['Newer', 'Older'])
  })

  it('orders by the instant and not by the text', () => {
    // `…:02Z` against `…:01.500Z`: the first is newer, and sorts *earlier* as a string because `Z`
    // is above `.`. This is the case a lexicographic sort gets backwards.
    expect(
      names(
        recentPalettes([
          template({ id: 1, name: 'Half past', lastPressedAt: '2026-09-14T10:00:01.500Z' }),
          template({ id: 2, name: 'On the second', lastPressedAt: '2026-09-14T10:00:02Z' }),
        ]),
      ),
    ).toEqual(['On the second', 'Half past'])
  })

  it('leaves a tie in the input order, which is the library’s own', () => {
    const tie = '2026-09-14T10:00:00.000Z'
    expect(
      names(
        recentPalettes([
          template({ id: 1, name: 'Amber', lastPressedAt: tie }),
          template({ id: 2, name: 'Blue', lastPressedAt: tie }),
        ]),
      ),
    ).toEqual(['Amber', 'Blue'])
  })

  it('reads an absent, null or unparseable stamp as never pressed', () => {
    // `undefined` is what a desk mid-upgrade serves: lighting7 hot-swaps handler bodies but not new
    // response fields, so a row can arrive without the field at all.
    const rows = [
      template({ id: 1, name: 'Null' }),
      template({ id: 2, name: 'Absent', lastPressedAt: undefined }),
      template({ id: 3, name: 'Rubbish', lastPressedAt: 'not a date' }),
    ]
    expect(recentPalettes(rows)).toEqual([])
    expect(names(stripPalettes(rows))).toEqual(['Null', 'Absent', 'Rubbish'])
  })

  it('caps at the limit', () => {
    const rows = Array.from({ length: 12 }, (_, i) =>
      template({
        id: i + 1,
        name: `T${i + 1}`,
        lastPressedAt: new Date(Date.UTC(2026, 8, 14, 10, 0, i)).toISOString(),
      }),
    )
    expect(recentPalettes(rows)).toHaveLength(8)
    expect(names(recentPalettes(rows, 3))).toEqual(['T12', 'T11', 'T10'])
    expect(recentPalettes(rows, 0)).toEqual([])
  })

  it('does not reorder the caller’s array', () => {
    const rows = [
      template({ id: 1, name: 'Older', lastPressedAt: '2026-09-14T10:00:00.000Z' }),
      template({ id: 2, name: 'Newer', lastPressedAt: '2026-09-14T10:00:01.000Z' }),
    ]
    recentPalettes(rows)
    expect(names(rows)).toEqual(['Older', 'Newer'])
  })
})

describe('stripPalettes', () => {
  it('draws the first few by name when nothing has been pressed', () => {
    const rows = Array.from({ length: 9 }, (_, i) => template({ id: i + 1, name: `T${i + 1}` }))
    expect(names(stripPalettes(rows))).toEqual(['T1', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'T8'])
  })

  it('is all or nothing: one recent draws one chip, not one plus seven by name', () => {
    const rows = [
      template({ id: 1, name: 'Never pressed' }),
      template({ id: 2, name: 'Pressed', lastPressedAt: '2026-09-14T10:00:00.000Z' }),
      template({ id: 3, name: 'Also never' }),
    ]
    expect(names(stripPalettes(rows))).toEqual(['Pressed'])
  })

  it('is empty for an empty library', () => {
    expect(stripPalettes([])).toEqual([])
  })
})
