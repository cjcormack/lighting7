// @vitest-environment jsdom
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { FxChange } from '@/api/fxApi'
import type { SpeedMasterLiveState } from '@/api/speedMastersWsApi'
import type { FxPreviewAnswer } from '@/store/fxPreview'

/**
 * The FX editor's preview (FX editor plan §3.3): `POST /fx/preview` asked again on every trigger —
 * the selection, `fxChanged` for an FX in the list, a tempo move on a master in the answer's clock,
 * a move on a row's key — the latest ask winning.
 */
const wire = vi.hoisted(() => ({
  asks: [] as { body: unknown; resolve: (answer: unknown) => void }[],
  changes: new Set<(change: FxChange) => void>(),
  tempo: new Set<(bank: SpeedMasterLiveState[]) => void>(),
  keys: new Map<string, Set<() => void>>(),
  bank: [] as SpeedMasterLiveState[],
}))

vi.mock('@/store/fxPreview', () => ({
  usePreviewFxMutation: () => [
    (body: unknown) => {
      let resolve: (answer: unknown) => void = () => {}
      const promise = new Promise((r) => {
        resolve = r
      })
      wire.asks.push({ body, resolve })
      return { unwrap: () => promise }
    },
  ],
}))
vi.mock('@/api/lightingApi', () => ({
  lightingApi: {
    fx: {
      subscribeToChanges: (fn: (change: FxChange) => void) => {
        wire.changes.add(fn)
        return { unsubscribe: () => wire.changes.delete(fn) }
      },
    },
    speedMasters: {
      getState: () => wire.bank,
      subscribe: (fn: (bank: SpeedMasterLiveState[]) => void) => {
        wire.tempo.add(fn)
        return { unsubscribe: () => wire.tempo.delete(fn) }
      },
    },
    programmer: {
      subscribeToKey: (key: string, property: string, fn: () => void) => {
        const id = `${key}|${property}`
        const set = wire.keys.get(id) ?? new Set()
        set.add(fn)
        wire.keys.set(id, set)
        return { unsubscribe: () => set.delete(fn) }
      },
    },
  },
}))

const { useFxPreview } = await import('./useFxPreview')

function answer(rowKey: string, fxType = 'Pulse'): FxPreviewAnswer {
  const series = { planes: { level: 'AA==' } }
  return {
    window: { beats: 8, seconds: 4, samples: 96, sampleMs: 41 },
    clock: [{ speedMasterUuid: 'm2', tickAtStart: 0, msPerBeat: 500 }],
    rows: [{ key: rowKey, fixtureKey: rowKey, label: rowKey, rigIndex: 0 }],
    lanes: [{ family: 'INTENSITY', attributes: [{ attribute: 'dimmer', kind: 'SLIDER', rows: [{ row: 0, underneath: series, result: series, fx: [] }] }] }],
    fx: [
      {
        fxId: 7,
        fxType,
        family: 'INTENSITY',
        attribute: 'dimmer',
        running: true,
        offsets: [0],
        starts: [[0]],
        holding: [null],
        slots: 1,
        ladder: { order: { ranks: [], counts: [] }, segments: { ranks: [], counts: [] }, wings: { ranks: [], counts: [] }, parts: { ranks: [], counts: [] }, spread: [] },
      },
    ],
  }
}

const master = (bpm: number): SpeedMasterLiveState => ({ uuid: 'm2', index: 2, name: 'Wash', bpm, isRunning: true, source: 'MANUAL' })
const flush = () => act(() => Promise.resolve())

beforeEach(() => {
  wire.asks = []
  wire.changes.clear()
  wire.tempo.clear()
  wire.keys.clear()
  wire.bank = [master(120)]
})

const SELECTION = [{ type: 'group' as const, key: 'front' }]

describe('useFxPreview', () => {
  it('asks over the selection with the picked FX, every FX the desk folds beside it', () => {
    renderHook(() => useFxPreview({ projectId: 1, targets: SELECTION, fxIds: [7], listed: [7, 8] }))
    expect(wire.asks.map((a) => a.body)).toEqual([
      { projectId: 1, targets: [{ type: 'group', key: 'front' }], fx: [{ fxId: 7 }], include: 'STACK' },
    ])
  })

  it('asks nothing with no FX or no selection', () => {
    renderHook(() => useFxPreview({ projectId: 1, targets: [], fxIds: [7] }))
    renderHook(() => useFxPreview({ projectId: 1, targets: SELECTION, fxIds: [] }))
    renderHook(() => useFxPreview(null))
    expect(wire.asks).toEqual([])
  })

  it('asks again when the selection changes, and not for a re-render with the same one', async () => {
    const { rerender } = renderHook(({ key }) => useFxPreview({ projectId: 1, targets: [{ type: 'fixture', key }], fxIds: [7] }), {
      initialProps: { key: 'hex-1' },
    })
    await act(async () => wire.asks[0].resolve(answer('hex-1')))
    rerender({ key: 'hex-1' })
    expect(wire.asks).toHaveLength(1)
    rerender({ key: 'hex-2' })
    expect(wire.asks).toHaveLength(2)
    expect(wire.asks[1].body).toMatchObject({ targets: [{ type: 'fixture', key: 'hex-2' }] })
  })

  it('drops a stale answer, and asks once more behind the ask in flight with what is asked by then', async () => {
    const { result, rerender } = renderHook(({ key }) => useFxPreview({ projectId: 1, targets: [{ type: 'fixture', key }], fxIds: [7] }), {
      initialProps: { key: 'hex-1' },
    })
    rerender({ key: 'hex-2' })
    rerender({ key: 'hex-3' })
    // One in flight: the moves wait behind it as one ask.
    expect(wire.asks).toHaveLength(1)
    await act(async () => wire.asks[0].resolve(answer('hex-1')))
    expect(result.current.answer).toBeNull()
    expect(wire.asks).toHaveLength(2)
    expect(wire.asks[1].body).toMatchObject({ targets: [{ type: 'fixture', key: 'hex-3' }] })
    await act(async () => wire.asks[1].resolve(answer('hex-3')))
    expect(result.current.answer?.rows[0].key).toBe('hex-3')
  })

  it('asks again when an FX joins the list, the asked FX unchanged', async () => {
    const { rerender } = renderHook(({ listed }) => useFxPreview({ projectId: 1, targets: SELECTION, fxIds: [7], listed }), {
      initialProps: { listed: [7] },
    })
    await act(async () => wire.asks[0].resolve(answer('hex-1')))
    rerender({ listed: [7, 9] })
    expect(wire.asks).toHaveLength(2)
  })

  it('leaves a removal of a listed FX to the list, rather than asking with a stopped FX', async () => {
    renderHook(() => useFxPreview({ projectId: 1, targets: SELECTION, fxIds: [7], listed: [7] }))
    await act(async () => wire.asks[0].resolve(answer('hex-1')))
    wire.changes.forEach((fn) => fn({ changeType: 'removed', fxId: 7 }))
    await flush()
    expect(wire.asks).toHaveLength(1)
  })

  it('asks again on fxChanged for an FX in the list, an answered updateFx among them, and not for another', async () => {
    renderHook(() => useFxPreview({ projectId: 1, targets: SELECTION, fxIds: [7], listed: [7, 8] }))
    await act(async () => wire.asks[0].resolve(answer('hex-1')))
    wire.changes.forEach((fn) => fn({ changeType: 'updated', fxId: 99 }))
    await flush()
    expect(wire.asks).toHaveLength(1)
    wire.changes.forEach((fn) => fn({ changeType: 'updated', fxId: 8 }))
    wire.changes.forEach((fn) => fn({ changeType: 'updated', fxId: 7 }))
    await flush()
    // Two changes in one tick are one ask.
    expect(wire.asks).toHaveLength(2)
  })

  it('asks again on a tempo move on a master in the answer’s clock, and not on a frame that moved none', async () => {
    renderHook(() => useFxPreview({ projectId: 1, targets: SELECTION, fxIds: [7] }))
    await act(async () => wire.asks[0].resolve(answer('hex-1')))
    wire.tempo.forEach((fn) => fn([master(120)]))
    await flush()
    expect(wire.asks).toHaveLength(1)
    wire.tempo.forEach((fn) => fn([master(128)]))
    await flush()
    expect(wire.asks).toHaveLength(2)
  })

  it('asks again when what is underneath a row moves', async () => {
    renderHook(() => useFxPreview({ projectId: 1, targets: SELECTION, fxIds: [7] }))
    await act(async () => wire.asks[0].resolve(answer('hex-1')))
    await waitFor(() => expect(wire.keys.get('hex-1|dimmer')?.size).toBe(1))
    wire.keys.get('hex-1|dimmer')!.forEach((fn) => fn())
    await flush()
    expect(wire.asks).toHaveLength(2)
  })
})
