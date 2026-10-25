import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { installRecordingFetch, installRelativeUrlRequest, palettesWs } from '@/test/backendMock'

// lightingApi opens a real WebSocket at import; mock it. This slice's bridges are **not** started
// on import (see `startPalettesBridge`), so each test starts them itself.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { store } from './index'
import { restApi } from './restApi'
import { startPalettesBridge, palettesApi } from './palettes'
import type { PaletteSummary } from '@/api/palettesApi'

/**
 * The press log's client half: `palettePressed` **patches** the cached library rather than
 * invalidating it.
 *
 * That distinction is the whole reason the frame is keyed. A press happens at busking rate, and the
 * sibling `paletteListChanged` bridge drops three caches — so reusing it would mean a refetch of
 * the whole library per chip press, behind an open picker.
 */
function row(over: Partial<PaletteSummary> = {}): PaletteSummary {
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

describe('the palettePressed bridge', () => {
  let fetchMock: ReturnType<typeof installRecordingFetch>

  beforeEach(() => {
    installRelativeUrlRequest()
    fetchMock = installRecordingFetch({
      'projects/1/palettes/1/apply': { written: 2, skipped: [] },
      'projects/1/palettes/2/apply': { written: 0, skipped: [{ fixtureKey: 'p', attribute: 'c', reason: 'x' }] },
      'projects/1/palettes/1/toggle': { action: 'applied', fxCount: 0, attributeMask: 'COLOUR' },
      'projects/1/palettes/2/toggle': { action: 'removed', fxCount: 0, attributeMask: 'COLOUR' },
      'projects/1/palettes?family=COLOUR': [row(), row({ id: 2, uuid: 'u2', name: 'Blue Wash' })],
      'projects/1/palettes': [row(), row({ id: 2, uuid: 'u2', name: 'Blue Wash' })],
    })
    startPalettesBridge()
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
    palettesWs.pressed = null
    palettesWs.changed = null
  })

  function countRequestsTo(fragment: string): number {
    return fetchMock.mock.calls.filter((call) => (call[0] as Request).url.includes(fragment)).length
  }

  function cached(family?: 'COLOUR') {
    return palettesApi.endpoints.paletteList.select({ projectId: 1, family })(store.getState())
      .data
  }

  it('writes the stamp into every cached list, whatever family each was filtered to', async () => {
    // `?family=` is a query argument, so the programmer's unfiltered list and `/palettes?family=`
    // are two cache entries of one endpoint. A press seen through one has to move the other, or
    // the two disagree about the order of the same library.
    const all = store.dispatch(palettesApi.endpoints.paletteList.initiate({ projectId: 1 }))
    const colour = store.dispatch(
      palettesApi.endpoints.paletteList.initiate({ projectId: 1, family: 'COLOUR' }),
    )
    await all
    await colour
    const before = countRequestsTo('projects/1/palettes')

    palettesWs.pressed?.({ paletteId: 2, lastPressedAt: '2026-09-14T10:00:00.000Z' })

    expect(cached()?.find((t) => t.id === 2)?.lastPressedAt).toBe('2026-09-14T10:00:00.000Z')
    expect(cached('COLOUR')?.find((t) => t.id === 2)?.lastPressedAt).toBe('2026-09-14T10:00:00.000Z')
    // The point of the keyed frame: no network at all.
    expect(countRequestsTo('projects/1/palettes')).toBe(before)

    all.unsubscribe()
    colour.unsubscribe()
  })

  it('leaves a palette the entry does not hold alone rather than inserting it', async () => {
    const all = store.dispatch(palettesApi.endpoints.paletteList.initiate({ projectId: 1 }))
    await all

    palettesWs.pressed?.({ paletteId: 99, lastPressedAt: '2026-09-14T10:00:00.000Z' })

    expect(cached()).toHaveLength(2)
    all.unsubscribe()
  })

  it('patches the acting tab from a click that wrote something', async () => {
    const all = store.dispatch(palettesApi.endpoints.paletteList.initiate({ projectId: 1 }))
    await all

    await store.dispatch(
      palettesApi.endpoints.applyPalette.initiate({ projectId: 1, paletteId: 1, targets: [] }),
    )
    expect(cached()?.find((t) => t.id === 1)?.lastPressedAt).not.toBeNull()

    all.unsubscribe()
  })

  it('does not patch from a click that reached no head', async () => {
    // The server applies the same rule, and the two must agree or the row shows a template the
    // desk has no record of having been pressed.
    const all = store.dispatch(palettesApi.endpoints.paletteList.initiate({ projectId: 1 }))
    await all

    await store.dispatch(
      palettesApi.endpoints.applyPalette.initiate({ projectId: 1, paletteId: 2, targets: [] }),
    )
    expect(cached()?.find((t) => t.id === 2)?.lastPressedAt).toBeNull()

    all.unsubscribe()
  })

  it('patches a toggle on the way on and not on the way off', async () => {
    const all = store.dispatch(palettesApi.endpoints.paletteList.initiate({ projectId: 1 }))
    await all

    await store.dispatch(
      palettesApi.endpoints.togglePalette.initiate({ projectId: 1, paletteId: 1, targets: [] }),
    )
    expect(cached()?.find((t) => t.id === 1)?.lastPressedAt).not.toBeNull()

    await store.dispatch(
      palettesApi.endpoints.togglePalette.initiate({ projectId: 1, paletteId: 2, targets: [] }),
    )
    expect(cached()?.find((t) => t.id === 2)?.lastPressedAt).toBeNull()

    all.unsubscribe()
  })
})
