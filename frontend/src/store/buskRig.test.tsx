// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { Provider } from 'react-redux'
import { type ReactNode } from 'react'
import { buskRigWs, installRelativeUrlRequest } from '@/test/backendMock'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())
vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

import { toast } from 'sonner'
import { store } from './index'
import { restApi } from './restApi'
import { buskApi, resetBuskCommitState, rigWriteFailureMessage, useBuskRigCommit } from './busk'
import type { BuskRig, BuskRigRequest } from '@/api/buskRigApi'
import type { RigIds } from '@/lib/buskRig'

/**
 * The rig's save loop and its bridge — `busk.test.tsx`'s shape over one document instead of pages.
 *
 * Two things here are the wire's, not the queue's, and each is a quiet failure: an **empty rig
 * arrives as `{}`** (lighting7 omits a defaulted empty list) and must read as `rows: []`; and a
 * **group tile is named by an id the GET never embeds**, resolved through the patch list at send
 * time — a group with no id is refused before the PUT, by name.
 */

function makeBackend() {
  let nextId = 500
  let served: Record<string, unknown> = {}
  const bodies: BuskRigRequest[] = []
  let failNextWith: string | null = null

  function write(body: BuskRigRequest): BuskRig {
    bodies.push(body)
    return {
      rows: body.rows.map((row) => ({
        id: row.rowId ?? nextId++,
        uuid: `r${row.rowId ?? nextId}`,
        name: row.name,
        tiles: row.tiles.map((tile) => ({
          id: tile.tileId ?? nextId++,
          uuid: `t${tile.tileId ?? nextId}`,
          kind: tile.groupId != null ? ('GROUP' as const) : ('FIXTURE' as const),
          group: tile.groupId != null ? ({ name: 'Wash', memberCount: 2 } as never) : null,
          patch: tile.patchId != null ? { id: tile.patchId, key: `p${tile.patchId}`, name: `P ${tile.patchId}` } : null,
          cellMode: tile.cellMode,
        })),
      })),
    }
  }

  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const request = input as Request
    if (request.url.includes('/busk/rig') && request.method === 'PUT') {
      if (failNextWith != null) {
        const code = failNextWith
        failNextWith = null
        return new Response(JSON.stringify({ error: 'nope', code }), { status: 400, headers: { 'Content-Type': 'application/json' } })
      }
      const answer = write((await request.json()) as BuskRigRequest)
      served = answer as unknown as Record<string, unknown>
      return new Response(JSON.stringify(answer), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    return new Response(JSON.stringify(served), { status: 200, headers: { 'Content-Type': 'application/json' } })
  })

  return {
    fetchMock,
    bodies,
    failNextWith: (code: string) => (failNextWith = code),
    rigReads: () => fetchMock.mock.calls.filter((call) => (call[0] as Request).method === 'GET').length,
  }
}

function wrapper({ children }: { children: ReactNode }) {
  return <Provider store={store}>{children}</Provider>
}

const ids: RigIds = { groupIdByName: new Map([['Wash', 7]]), patchIdByKey: new Map() }

function cachedRig(): BuskRig | undefined {
  return buskApi.endpoints.buskRig.select(1)(store.getState()).data
}

const addGroupRow = (name: string) => (rig: BuskRig) => ({
  rows: [...(rig.rows ?? []), { localKey: name, name, tiles: [{ localKey: `${name}-t`, kind: 'GROUP' as const, group: { name: 'Wash' } as never, cellMode: 'PIPS' as const }] }],
})

describe('the rig save loop', () => {
  let backend: ReturnType<typeof makeBackend>

  beforeEach(async () => {
    installRelativeUrlRequest()
    backend = makeBackend()
    vi.stubGlobal('fetch', backend.fetchMock)
    resetBuskCommitState()
    vi.mocked(toast.error).mockClear()
    await store.dispatch(buskApi.endpoints.buskRig.initiate(1))
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    resetBuskCommitState()
    vi.unstubAllGlobals()
  })

  it('reads the wire’s `{}` as an empty rig', () => {
    expect(cachedRig()).toEqual({ rows: [] })
  })

  it('sends the whole rig with the group’s id from the patch list, and patches the cache from the response', async () => {
    const { result } = renderHook(() => useBuskRigCommit(1, ids), { wrapper })
    result.current(addGroupRow('Wash'))
    await waitFor(() => expect(backend.bodies).toHaveLength(1))
    expect(backend.bodies[0]).toEqual({ rows: [{ name: 'Wash', tiles: [{ groupId: 7, cellMode: 'PIPS' }] }] })
    await waitFor(() => expect(cachedRig()!.rows![0].id).toBeDefined())
  })

  it('builds the second gesture on the first response, so a minted row is never recreated', async () => {
    const { result } = renderHook(() => useBuskRigCommit(1, ids), { wrapper })
    result.current(addGroupRow('One'))
    result.current(addGroupRow('Two'))
    await waitFor(() => expect(backend.bodies).toHaveLength(2))
    expect(backend.bodies[1].rows[0].rowId).toBe(backend.bodies[0].rows[0].rowId ?? 500)
    expect(backend.bodies[1].rows[0].tiles[0].tileId).toBeDefined()
    expect(backend.bodies[1].rows[1]).not.toHaveProperty('rowId')
  })

  it('refuses a group the desk has no id for before any PUT, by name', async () => {
    const { result } = renderHook(() => useBuskRigCommit(1, { groupIdByName: new Map(), patchIdByKey: new Map() }), { wrapper })
    result.current(addGroupRow('Wash'))
    await waitFor(() => expect(toast.error).toHaveBeenCalled())
    expect(vi.mocked(toast.error).mock.calls[0][0]).toMatch(/“Wash” has no patched member/)
    expect(backend.bodies).toHaveLength(0)
    expect(cachedRig()).toEqual({ rows: [] })
  })

  it('restores the last confirmed rig, re-reads it, and says which refusal it was', async () => {
    const { result } = renderHook(() => useBuskRigCommit(1, ids), { wrapper })
    backend.failNextWith('BUSK_RIG_REF')
    const before = backend.rigReads()
    result.current(addGroupRow('Wash'))
    await waitFor(() => expect(toast.error).toHaveBeenCalled())
    expect(vi.mocked(toast.error).mock.calls[0][0]).toMatch(/is gone: nope/)
    await waitFor(() => expect(cachedRig()).toEqual({ rows: [] }))
    await waitFor(() => expect(backend.rigReads()).toBeGreaterThan(before))
  })

  it('names each refusal by code', () => {
    const err = (code: string) => ({ status: 400, data: { error: 'why', code } })
    expect(rigWriteFailureMessage(err('BUSK_RIG_INVALID'))).toBe('The rig was refused: why')
    expect(rigWriteFailureMessage(err('BUSK_RIG_IDENTITY'))).toMatch(/does not have: why/)
    expect(rigWriteFailureMessage(err('BUSK_RIG_REF'))).toMatch(/is gone: why/)
    expect(rigWriteFailureMessage(err('SOMETHING_ELSE'))).toBe('why')
  })
})

describe('the busk.rigChanged bridge', () => {
  let backend: ReturnType<typeof makeBackend>

  beforeEach(async () => {
    installRelativeUrlRequest()
    backend = makeBackend()
    vi.stubGlobal('fetch', backend.fetchMock)
    resetBuskCommitState()
    await store.dispatch(buskApi.endpoints.buskRig.initiate(1))
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    resetBuskCommitState()
    vi.unstubAllGlobals()
  })

  it('re-reads the rig another client changed', async () => {
    const before = backend.rigReads()
    buskRigWs.callback!()
    await waitFor(() => expect(backend.rigReads()).toBe(before + 1))
  })

  it('swallows the echo of our own write', async () => {
    const { result } = renderHook(() => useBuskRigCommit(1, ids), { wrapper })
    result.current(addGroupRow('Wash'))
    const before = backend.rigReads()
    buskRigWs.callback!()
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(backend.rigReads()).toBe(before)
  })
})
