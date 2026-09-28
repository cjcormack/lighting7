import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  failWith,
  installRecordingFetch,
  installRelativeUrlRequest,
  patchesWs,
} from '@/test/backendMock'

// lightingApi opens a real WebSocket at import; mock it (and capture the
// patchListChanged subscription store/patches.ts registers at module load).
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

// commitPlacements toasts on failure; assert on it rather than letting it warn.
const toastError = vi.fn()
const toastWarning = vi.fn()
vi.mock('sonner', () => ({
  toast: {
    error: (...a: unknown[]) => toastError(...a),
    warning: (...a: unknown[]) => toastWarning(...a),
  },
}))

import { store } from './index'
import { restApi } from './restApi'
import { patchesApi } from './patches'
import { commitPlacements, placementUnchanged } from './stagePlacement'
import type { FixturePatch } from '@/api/patchApi'

function patch(id: number, overrides: Partial<FixturePatch> = {}): FixturePatch {
  return {
    id,
    key: `p${id}`,
    displayName: `P${id}`,
    fixtureTypeKey: 'tk',
    startChannel: id,
    channelCount: 1,
    manufacturer: null,
    model: null,
    modeName: null,
    universe: 1,
    subnet: 0,
    sortOrder: id,
    groups: [],
    stageX: 0,
    stageY: 0,
    stageZ: 0,
    baseYawDeg: null,
    basePitchDeg: null,
    riggingUuid: null,
    beamAngleDeg: null,
    gelCode: null,
    kindOverride: null,
    stageHidden: false,
    ...overrides,
  }
}

const PROJECT = 1

function cached(): FixturePatch[] {
  return patchesApi.endpoints.patchList.select(PROJECT)(store.getState()).data ?? []
}

function positionOf(id: number) {
  const p = cached().find((x) => x.id === id)
  return p ? { stageX: p.stageX, stageY: p.stageY } : null
}

describe('placementUnchanged', () => {
  it('ignores keys absent from the candidate', () => {
    expect(placementUnchanged({ stageX: 1 }, { stageX: 1, stageY: 9 })).toBe(true)
  })

  it('treats an explicitly undefined value as "not being set"', () => {
    expect(placementUnchanged({ stageX: 1, widthM: undefined }, { stageX: 1, widthM: 5 })).toBe(true)
  })

  it('detects a changed value', () => {
    expect(placementUnchanged({ stageX: 2 }, { stageX: 1 })).toBe(false)
  })

  it('distinguishes null from a number, so clearing a field counts as a change', () => {
    expect(placementUnchanged({ stageZ: null }, { stageZ: 0 })).toBe(false)
    expect(placementUnchanged({ stageZ: 0 }, { stageZ: null })).toBe(false)
  })

  it('reports no change for an identical rotate-mode settle', () => {
    // The bare-click case: TransformControls fires dragging-changed:false on
    // every mouseup, so this has to be recognised as a no-op or every click
    // would PUT.
    const origin = { baseYawDeg: 30, basePitchDeg: -5, stageX: 1, stageY: 2, stageZ: 3 }
    expect(placementUnchanged({ ...origin }, origin)).toBe(true)
  })

  it('reports a change for a rotate-mode settle that actually rotated', () => {
    // The regression this guard exists for: diffing against the live cache value
    // (which the per-frame writes had already advanced to the final orientation)
    // made this compare equal, so rotate drags never persisted.
    const origin = { baseYawDeg: 30, basePitchDeg: 0 }
    expect(placementUnchanged({ baseYawDeg: 47, basePitchDeg: 0 }, origin)).toBe(false)
  })
})

describe('commitPlacements', () => {
  let fetchMock: ReturnType<typeof installRecordingFetch>
  let subscription: { unsubscribe: () => void } | undefined

  /**
   * Seeds the patch list through a real subscription rather than
   * `upsertQueryData`. That matters: `commitPlacements` ends with
   * `invalidateTags`, and RTK Query *evicts* an invalidated entry that has no
   * subscribers instead of refetching it — so an upserted entry would simply
   * vanish and every cache assertion would read undefined. A live subscriber
   * mirrors the real app, where the Stage route holds one.
   *
   * `patches` therefore doubles as the server's state: the trailing refetch
   * returns it.
   *
   * `patches/placements` is listed FIRST and always has an entry, because the mock
   * matches routes by URL substring in insertion order and `projects/1/patches` is
   * a prefix of `projects/1/patches/placements`. Without an explicit entry the bulk
   * PUT would be answered with the patch *array*, which has no `.failed` and would
   * look like a transport error. `overrides` can replace it to simulate a failure.
   */
  async function seed(patches: FixturePatch[], overrides: Record<string, unknown> = {}) {
    fetchMock = installRecordingFetch({
      'patches/placements': { updated: [], failed: [], warnings: [] },
      ...overrides,
      'projects/1/patches': patches,
    })
    subscription = store.dispatch(patchesApi.endpoints.patchList.initiate(PROJECT))
    await vi.waitFor(() => expect(cached()).toHaveLength(patches.length))
  }

  beforeEach(() => {
    installRelativeUrlRequest()
    toastError.mockClear()
    toastWarning.mockClear()
    fetchMock = installRecordingFetch({})
  })

  afterEach(() => {
    subscription?.unsubscribe()
    subscription = undefined
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  function putsTo(fragment: string): Request[] {
    return fetchMock.mock.calls
      .map((c) => c[0] as Request)
      .filter((r) => r.url.includes(fragment) && r.method === 'PUT')
  }

  it('does nothing for an empty change set', async () => {
    const result = await commitPlacements({ projectId: PROJECT, changes: [], label: 'Align' })
    expect(result).toEqual({ ok: 0, failed: [] })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('sends the whole batch as ONE request and applies it to the cache', async () => {
    await seed([patch(1), patch(2), patch(3)])

    const result = await commitPlacements({
      projectId: PROJECT,
      changes: [
        { patchId: 1, stageX: 1 },
        { patchId: 2, stageX: 2 },
        { patchId: 3, stageX: 3 },
      ],
      label: 'Distribute',
    })

    expect(result.ok).toBe(3)
    expect(result.failed).toEqual([])
    // One request for three fixtures — the whole point of the bulk route.
    expect(putsTo('patches/placements')).toHaveLength(1)
    // Asserted synchronously: the trailing refetch is on a timer and hasn't
    // landed yet, so this is the optimistic write, applied in one draft.
    expect(positionOf(1)?.stageX).toBe(1)
    expect(positionOf(2)?.stageX).toBe(2)
    expect(positionOf(3)?.stageX).toBe(3)
  })

  it('sends patchId plus only the placement keys it was given', async () => {
    await seed([patch(1)])
    await commitPlacements({
      projectId: PROJECT,
      changes: [{ patchId: 1, stageX: 4, stageY: 5 }],
      label: 'Move',
    })
    const body = await putsTo('patches/placements')[0].clone().json()
    expect(body.atomic).toBe(true)
    // Nothing outside the backend's placement key set may appear, or the route
    // 400s and drops out of its metadata-only fast path.
    expect(body.updates).toEqual([{ patchId: 1, stageX: 4, stageY: 5 }])
  })

  it('rolls the whole batch back when the request is rejected', async () => {
    // The route is atomic, so a rejected batch wrote nothing server-side and the
    // optimistic write must be undone in full.
    await seed([patch(1, { stageX: 10 }), patch(2, { stageX: 20 })], {
      'patches/placements': failWith(400, { message: 'out of range' }),
    })

    const result = await commitPlacements({
      projectId: PROJECT,
      changes: [
        { patchId: 1, stageX: 111 },
        { patchId: 2, stageX: 222 },
      ],
      label: 'Align left',
    })

    expect(result.ok).toBe(0)
    expect(result.failed.map((f) => f.patchId).sort()).toEqual([1, 2])
    expect(positionOf(1)?.stageX).toBe(10)
    expect(positionOf(2)?.stageX).toBe(20)
  })

  it('reports per-entry failures the server returned', async () => {
    await seed([patch(1)], {
      'patches/placements': { updated: [], failed: [{ patchId: 1, error: 'Patch not found' }], warnings: [] },
    })
    const result = await commitPlacements({
      projectId: PROJECT,
      changes: [{ patchId: 1, stageX: 1 }],
      label: 'Align',
    })
    expect(result.failed).toEqual([{ patchId: 1, error: 'Patch not found' }])
  })

  it('surfaces server warnings without treating them as failures', async () => {
    // Only the server knows a truss's length at write time, so an
    // off-the-end-of-the-bar placement can only be reported from there.
    await seed([patch(1)], {
      'patches/placements': {
        updated: [],
        failed: [],
        warnings: ['dim-1: 7.20 m is past the end of Truss 1 (±6.00 m)'],
      },
    })
    const result = await commitPlacements({
      projectId: PROJECT,
      changes: [{ patchId: 1, stageX: 7.2 }],
      label: 'Hang on Truss 1',
    })
    expect(result.failed).toEqual([])
    expect(result.ok).toBe(1)
    expect(toastWarning).toHaveBeenCalledTimes(1)
    expect(toastWarning.mock.calls[0][0]).toContain('past the end of Truss 1')
    // A warning is not an error, so the optimistic value stands.
    expect(positionOf(1)?.stageX).toBe(7.2)
  })

  it('reports the failure count and the label in one toast', async () => {
    await seed([patch(1), patch(2)], { 'patches/placements': failWith(500) })

    await commitPlacements({
      projectId: PROJECT,
      changes: [
        { patchId: 1, stageX: 1 },
        { patchId: 2, stageX: 2 },
      ],
      label: 'Align left',
    })

    // Exactly one — updatePatchPlacement is deny-listed in the error middleware
    // precisely so a 20-fixture failure doesn't produce 20 toasts.
    expect(toastError).toHaveBeenCalledTimes(1)
    expect(toastError.mock.calls[0][0]).toContain('Align left')
    expect(toastError.mock.calls[0][0]).toContain('2 of 2')
  })

  it('restores only the fields it overwrote, leaving the rest alone', async () => {
    await seed([patch(1, { stageX: 1, stageY: 2, stageZ: 3, gelCode: 'L201' })], {
      'patches/placements': failWith(400),
    })

    await commitPlacements({
      projectId: PROJECT,
      changes: [{ patchId: 1, stageX: 99 }],
      label: 'Nudge',
    })

    // Synchronous, so this is the rollback rather than the refetch.
    const p = cached().find((x) => x.id === 1)!
    expect(p.stageX).toBe(1)
    expect(p.stageY).toBe(2)
    expect(p.stageZ).toBe(3)
    expect(p.gelCode).toBe('L201')
  })

  it('still sends a change for a patch missing from the cache — the server is the authority', async () => {
    await seed([patch(1)])
    await commitPlacements({
      projectId: PROJECT,
      changes: [
        { patchId: 1, stageX: 1 },
        { patchId: 404, stageX: 2 },
      ],
      label: 'Align',
    })
    const body = await putsTo('patches/placements')[0].clone().json()
    expect(body.updates).toHaveLength(2)
    // The missing one contributes no snapshot and no local cache write.
    expect(positionOf(1)?.stageX).toBe(1)
  })

  it('collapses WebSocket patchListChanged storms into one list refetch', async () => {
    await seed([patch(1), patch(2), patch(3), patch(4)])
    const listGets = () =>
      fetchMock.mock.calls
        .map((c) => c[0] as Request)
        .filter((r) => r.method === 'GET' && r.url.endsWith('projects/1/patches')).length
    const before = listGets()

    // The backend broadcasts patchListChanged after each PUT. Unsuspended, four
    // broadcasts would mean four full list refetches while the batch is still
    // running — that storm is the whole reason for the suspension.
    const inFlight = commitPlacements({
      projectId: PROJECT,
      changes: [
        { patchId: 1, stageX: 1 },
        { patchId: 2, stageX: 2 },
        { patchId: 3, stageX: 3 },
        { patchId: 4, stageX: 4 },
      ],
      label: 'Align',
    })
    patchesWs.callback?.()
    patchesWs.callback?.()
    patchesWs.callback?.()
    patchesWs.callback?.()
    await inFlight
    await vi.waitFor(() => expect(listGets()).toBeGreaterThan(before))

    expect(listGets() - before).toBe(1)
  })

  it('resumes normal invalidation once the batch is done', async () => {
    await seed([patch(1)])
    await commitPlacements({
      projectId: PROJECT,
      changes: [{ patchId: 1, stageX: 1 }],
      label: 'Align',
    })

    const before = fetchMock.mock.calls.length
    patchesWs.callback?.()
    // A broadcast after the batch must refetch the list again.
    await vi.waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThan(before))
  })
})
