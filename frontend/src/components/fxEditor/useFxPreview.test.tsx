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

  it('sends the window and solo the graph chose, and asks again when either moves', async () => {
    const { rerender } = renderHook(({ cycles, solo }) => useFxPreview({ projectId: 1, targets: SELECTION, fxIds: [7, 8], window: { cycles }, solo }), {
      initialProps: { cycles: 2, solo: undefined as number | undefined },
    })
    expect(wire.asks[0].body).toMatchObject({ fx: [{ fxId: 7 }, { fxId: 8 }], window: { cycles: 2 } })
    expect(wire.asks[0].body).not.toHaveProperty('solo')
    await act(async () => wire.asks[0].resolve(answer('hex-1')))
    rerender({ cycles: 1, solo: 1 })
    expect(wire.asks).toHaveLength(2)
    expect(wire.asks[1].body).toMatchObject({ window: { cycles: 1 }, solo: 1 })
  })

  it('sends the inspector’s unsent move as the picked FX’s draft, the graph following a drag before the floor sends it', async () => {
    const { rerender } = renderHook(({ draft }) => useFxPreview({ projectId: 1, targets: SELECTION, fxIds: [7, 8], draft }), {
      initialProps: { draft: undefined as { cycle: number; parameters: Record<string, string>; blendMode: string; phaseOffset: number } | undefined },
    })
    expect(wire.asks[0].body).toMatchObject({ fx: [{ fxId: 7 }, { fxId: 8 }] })
    await act(async () => wire.asks[0].resolve(answer('hex-1')))
    const move = { cycle: 8, parameters: {}, blendMode: 'OVERRIDE', phaseOffset: 0 }
    rerender({ draft: move })
    expect(wire.asks).toHaveLength(2)
    expect(wire.asks[1].body).toMatchObject({ fx: [{ fxId: 7, draft: move }, { fxId: 8 }] })
    // The desk has it: the ask goes back to the running FX.
    await act(async () => wire.asks[1].resolve(answer('hex-1')))
    rerender({ draft: undefined })
    expect(wire.asks).toHaveLength(3)
    expect(wire.asks[2].body).toMatchObject({ fx: [{ fxId: 7 }, { fxId: 8 }] })
    expect((wire.asks[2].body as { fx: object[] }).fx[0]).not.toHaveProperty('draft')
  })

  it('draws an answer to an earlier move of the drag, the ask behind it bringing the latest', async () => {
    const move = (cycle: number) => ({ cycle, parameters: {}, blendMode: 'OVERRIDE', phaseOffset: 0 })
    const { result, rerender } = renderHook(({ draft }) => useFxPreview({ projectId: 1, targets: SELECTION, fxIds: [7], draft }), {
      initialProps: { draft: move(4) },
    })
    // The finger moves on before the desk answers the first move.
    rerender({ draft: move(5) })
    rerender({ draft: move(6) })
    expect(wire.asks).toHaveLength(1)
    await act(async () => wire.asks[0].resolve(answer('hex-1', 'first')))
    expect(result.current.answer?.fx[0].fxType).toBe('first')
    expect(wire.asks).toHaveLength(2)
    expect(wire.asks[1].body).toMatchObject({ fx: [{ fxId: 7, draft: { cycle: 6 } }] })
    await act(async () => wire.asks[1].resolve(answer('hex-1', 'second')))
    expect(result.current.answer?.fx[0].fxType).toBe('second')
  })

  it('keeps hearing its triggers across a drag’s moves', async () => {
    const { rerender } = renderHook(({ draft }) => useFxPreview({ projectId: 1, targets: SELECTION, fxIds: [7], draft }), {
      initialProps: { draft: { cycle: 4, parameters: {}, blendMode: 'OVERRIDE', phaseOffset: 0 } },
    })
    await act(async () => wire.asks[0].resolve(answer('hex-1')))
    const listeners = wire.changes.size
    rerender({ draft: { cycle: 8, parameters: {}, blendMode: 'OVERRIDE', phaseOffset: 0 } })
    await act(async () => wire.asks[1].resolve(answer('hex-1')))
    expect(wire.changes.size).toBe(listeners)
    act(() => wire.changes.forEach((fn) => fn({ fxId: 7, changeType: 'updated' } as FxChange)))
    await flush()
    expect(wire.asks).toHaveLength(3)
  })

  it('sends no solo past the FX asked for', () => {
    renderHook(() => useFxPreview({ projectId: 1, targets: SELECTION, fxIds: [7], solo: 1 }))
    expect(wire.asks[0].body).not.toHaveProperty('solo')
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

  describe('the strip’s asks (FX editor plan §4.6)', () => {
    const DRAFT = { fxType: 'Circle', attribute: 'position', cycle: 4 }

    it('asks a draft over the selection, include ONLY, with no running FX named', () => {
      renderHook(() => useFxPreview({ projectId: 1, targets: SELECTION, fxIds: [], drafts: [DRAFT], include: 'ONLY' }))
      expect(wire.asks.map((a) => a.body)).toEqual([
        { projectId: 1, targets: [{ type: 'group', key: 'front' }], fx: [{ draft: DRAFT }], include: 'ONLY' },
      ])
    })

    it('asks a draft on notional heads with no targets, and hears no row’s key, which the desk does not hold', async () => {
      renderHook(() => useFxPreview({ projectId: 1, targets: SELECTION, fxIds: [], drafts: [DRAFT], include: 'ONLY', notional: 8 }))
      expect(wire.asks[0].body).toEqual({ projectId: 1, targets: [], fx: [{ draft: DRAFT }], include: 'ONLY', notional: 8 })
      await act(async () => wire.asks[0].resolve(answer('notional-1')))
      await flush()
      expect(wire.keys.size).toBe(0)
    })

    it('asks again when a draft changes, and not for a re-render with the same one', async () => {
      const { rerender } = renderHook(({ cycle }) => useFxPreview({ projectId: 1, targets: SELECTION, fxIds: [], drafts: [{ ...DRAFT, cycle }], include: 'ONLY' }), {
        initialProps: { cycle: 4 },
      })
      await act(async () => wire.asks[0].resolve(answer('hex-1')))
      rerender({ cycle: 4 })
      expect(wire.asks).toHaveLength(1)
      rerender({ cycle: 8 })
      expect(wire.asks).toHaveLength(2)
      expect(wire.asks[1].body).toMatchObject({ fx: [{ draft: { cycle: 8 } }] })
    })

    it('draws an answer to an earlier move of a draft mid-drag, and drops one to another FX’s', async () => {
      const { result, rerender } = renderHook(
        ({ fxType, cycle }) => useFxPreview({ projectId: 1, targets: SELECTION, fxIds: [], drafts: [{ fxType, attribute: 'dimmer', cycle }], include: 'ONLY' }),
        { initialProps: { fxType: 'Pulse', cycle: 4 } },
      )
      rerender({ fxType: 'Pulse', cycle: 5 })
      await act(async () => wire.asks[0].resolve(answer('hex-1', 'first')))
      expect(result.current.answer?.fx[0].fxType).toBe('first')
      expect(wire.asks[1].body).toMatchObject({ fx: [{ draft: { cycle: 5 } }] })
      rerender({ fxType: 'SineWave', cycle: 5 })
      await act(async () => wire.asks[1].resolve(answer('hex-1', 'second')))
      expect(result.current.answer?.fx[0].fxType).toBe('first')
      expect(wire.asks[2].body).toMatchObject({ fx: [{ draft: { fxType: 'SineWave' } }] })
    })

    it('asks a running FX on its own heads with no targets only when told to', () => {
      renderHook(() => useFxPreview({ projectId: 1, targets: [], fxIds: [7], ownHeads: true }))
      expect(wire.asks.map((a) => a.body)).toEqual([{ projectId: 1, targets: [], fx: [{ fxId: 7 }], include: 'STACK' }])
    })

    it('asks nothing for a draft with neither targets nor notional heads', () => {
      renderHook(() => useFxPreview({ projectId: 1, targets: [], fxIds: [], drafts: [DRAFT], include: 'ONLY' }))
      expect(wire.asks).toEqual([])
    })
  })
})
