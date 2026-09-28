import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  cueRunStateWs,
  installRecordingFetch,
  installRelativeUrlRequest,
} from '@/test/backendMock'

// lightingApi opens a real WebSocket at import; mock it (also stubs the cues /
// cueStacks WS subscriptions the store registers at module load).
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { store } from './index'
import { restApi } from './restApi'
import { cueStacksApi } from './cueStacks'
import { selectStackRunner } from './runnerSlice'

/**
 * Wiring tests for the program-transport endpoints that replaced the old show API. They prove the
 * URL/method contract with the backend (`/show/activate`, `/show/reorder`→`/cue-stacks/reorder`,
 * GET `/show`) and the optimistic playhead patch.
 */
describe('cueStacks program transport', () => {
  let fetchMock: ReturnType<typeof installRecordingFetch>

  beforeEach(() => {
    installRelativeUrlRequest()
    // Keys are matched by substring, most-specific first.
    fetchMock = installRecordingFetch({
      'projects/1/show/activate': { projectId: 1, activeStackId: 5, activatedStackName: 'Act 1' },
      'projects/1/show': { projectId: 1, activeStackId: null, canEdit: true },
      'projects/1/cue-stacks/reorder': {},
    })
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  function lastRequestTo(fragment: string): Request | undefined {
    for (let i = fetchMock.mock.calls.length - 1; i >= 0; i--) {
      const input = fetchMock.mock.calls[i][0] as Request
      if (input.url.includes(fragment)) return input
    }
    return undefined
  }

  it('GET projectProgramState hits /show', async () => {
    await store.dispatch(cueStacksApi.endpoints.projectProgramState.initiate(1))
    await vi.waitFor(() => {
      const req = lastRequestTo('projects/1/show')
      expect(req).toBeDefined()
      expect(req!.method).toBe('GET')
    })
  })

  it('activateProgram POSTs /show/activate and patches the playhead', async () => {
    await store.dispatch(cueStacksApi.endpoints.projectProgramState.initiate(1))
    await store.dispatch(cueStacksApi.endpoints.activateProgram.initiate({ projectId: 1 }))

    const req = lastRequestTo('projects/1/show/activate')
    expect(req).toBeDefined()
    expect(req!.method).toBe('POST')

    await vi.waitFor(() => {
      const state = cueStacksApi.endpoints.projectProgramState.select(1)(store.getState())
      expect(state.data?.activeStackId).toBe(5)
    })
  })

  it('reorderCueStacks POSTs /cue-stacks/reorder with the stack ids', async () => {
    await store.dispatch(
      cueStacksApi.endpoints.reorderCueStacks.initiate({ projectId: 1, stackIds: [3, 1, 2] }),
    )
    const req = lastRequestTo('cue-stacks/reorder')
    expect(req).toBeDefined()
    expect(req!.method).toBe('POST')
    const body = await req!.clone().text()
    expect(JSON.parse(body)).toEqual({ stackIds: [3, 1, 2] })
  })
})

/**
 * The Program cue table renders `stack.cues` out of `projectCueStackList`, so that is the cache
 * the cue reorder has to patch optimistically — an earlier version patched `projectCueList`,
 * which nothing in Program reads, and the dragged row visibly sprang back until the refetch.
 */
describe('reorderCueStackCues optimistic patch', () => {
  const cue = (id: number, sortOrder: number) => ({
    id,
    name: `cue-${id}`,
    sortOrder,
    layerCount: 0,
    adHocEffectCount: 0,
    autoAdvance: false,
    autoAdvanceDelayMs: null,
    fadeDurationMs: null,
    fadeCurve: 'LINEAR',
    cueNumber: String(id),
    cueNumberAuto: false,
    notes: null,
    cueType: 'STANDARD' as const,
  })

  beforeEach(() => {
    installRelativeUrlRequest()
    installRecordingFetch({
      'projects/1/cue-stacks/7/reorder': {},
      'projects/1/cue-stacks': [
        {
          id: 7,
          name: 'Act 1',
          loop: false,
          sortOrder: 0,
          type: 'STACK',
          label: null,
          activeCueId: null,
          canEdit: true,
          canDelete: true,
          cues: [cue(10, 0), cue(11, 1), cue(12, 2)],
        },
      ],
    })
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  const cueIdsInCache = () =>
    cueStacksApi.endpoints.projectCueStackList
      .select(1)(store.getState())
      .data?.find((s) => s.id === 7)
      ?.cues.map((c) => c.id)

  it('reorders the drilled stack in projectCueStackList', async () => {
    await store.dispatch(cueStacksApi.endpoints.projectCueStackList.initiate(1))
    await vi.waitFor(() => expect(cueIdsInCache()).toEqual([10, 11, 12]))

    const promise = store.dispatch(
      cueStacksApi.endpoints.reorderCueStackCues.initiate({
        projectId: 1,
        stackId: 7,
        cueIds: [12, 10, 11],
      }),
    )

    // Patched synchronously, before the request settles — that's what stops the snap-back.
    expect(cueIdsInCache()).toEqual([12, 10, 11])
    const patched = cueStacksApi.endpoints.projectCueStackList
      .select(1)(store.getState())
      .data?.find((s) => s.id === 7)
    expect(patched?.cues.map((c) => c.sortOrder)).toEqual([0, 1, 2])

    await promise
  })

  it('keeps cues the request did not name at the end', async () => {
    await store.dispatch(cueStacksApi.endpoints.projectCueStackList.initiate(1))
    await vi.waitFor(() => expect(cueIdsInCache()).toEqual([10, 11, 12]))

    await store.dispatch(
      cueStacksApi.endpoints.reorderCueStackCues.initiate({
        projectId: 1,
        stackId: 7,
        cueIds: [12, 10],
      }),
    )

    // The server only rewrites sortOrder for the ids it was given, so 11 lands after them.
    expect(cueIdsInCache()).toEqual([12, 10, 11])
  })

  it('leaves other stacks alone when the id is unknown', async () => {
    await store.dispatch(cueStacksApi.endpoints.projectCueStackList.initiate(1))
    await vi.waitFor(() => expect(cueIdsInCache()).toEqual([10, 11, 12]))

    await store.dispatch(
      cueStacksApi.endpoints.reorderCueStackCues.initiate({
        projectId: 1,
        stackId: 999,
        cueIds: [12, 10, 11],
      }),
    )

    expect(cueIdsInCache()).toEqual([10, 11, 12])
  })
})

/**
 * Run state is server-owned: a `cueRunStateChanged` frame has to move a session that never
 * pressed GO. Before this, standby and the fade animation lived only in the browser that fired
 * the cue, so a prompt book on a tablet showed a different NEXT and no fade at all.
 */
describe('cueRunStateChanged', () => {
  const stackFixture = {
    id: 7,
    name: 'Act 1',
    loop: false,
    sortOrder: 0,
    type: 'STACK',
    label: null,
    activeCueId: 10,
    standbyCueId: null,
    nextCueId: 11,
    canEdit: true,
    canDelete: true,
    cues: [],
  }

  beforeEach(() => {
    installRelativeUrlRequest()
    installRecordingFetch({
      'projects/1/cue-stacks/7/standby': { stackId: 7, activeCueId: 10, standbyCueId: 12, nextCueId: 12 },
      'projects/1/cue-stacks': [stackFixture],
    })
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  const frame = (over: Record<string, unknown> = {}) => ({
    projectId: 1,
    stackId: 7,
    activeCueId: 11,
    nextCueId: 12,
    nextIsArmed: false,
    transition: true,
    fadeDurationMs: 2000,
    fadeElapsedMs: 0,
    autoAdvance: false,
    autoAdvanceDelayMs: null,
    ...over,
  })

  it('moves the runner slice and starts the fade for a session that did not press GO', () => {
    cueRunStateWs.callback!(frame())

    const runner = selectStackRunner(store.getState() as never, 7)
    expect(runner.serverActiveCueId).toBe(11)
    expect(runner.activeCueId).toBe(11)
    expect(runner.standbyCueId).toBe(12)
    expect(runner.serverTransition).toBeGreaterThan(0)
  })

  it('starts a mid-fade join part-way through', () => {
    cueRunStateWs.callback!(frame({ activeCueId: 21, fadeElapsedMs: 750 }))

    expect(selectStackRunner(store.getState() as never, 7).fadeStartElapsedMs).toBe(750)
  })

  it('leaves the animation alone for a standby-only change', () => {
    cueRunStateWs.callback!(frame({ activeCueId: 31, fadeElapsedMs: 400 }))
    const before = selectStackRunner(store.getState() as never, 7).serverTransition

    // Same live cue, no fade running: somebody armed a different cue.
    cueRunStateWs.callback!(
      frame({ activeCueId: 31, nextCueId: 44, nextIsArmed: true, transition: false, fadeElapsedMs: null }),
    )

    const after = selectStackRunner(store.getState() as never, 7)
    expect(after.standbyCueId).toBe(44)
    expect(after.serverTransition).toBe(before)
  })

  it('does not replay a settled fade from the connect-time snapshot', () => {
    const before = selectStackRunner(store.getState() as never, 7).serverTransition

    // Not a transition and no fade running — the cue fired long before we connected.
    cueRunStateWs.callback!(frame({ activeCueId: 51, transition: false, fadeElapsedMs: null }))

    const runner = selectStackRunner(store.getState() as never, 7)
    expect(runner.serverActiveCueId).toBe(51)
    expect(runner.activeCueId).toBeNull()
    expect(runner.serverTransition).toBe(before)
  })

  it('patches the cached stack so a refetch does not flap back', async () => {
    await store.dispatch(cueStacksApi.endpoints.projectCueStackList.initiate(1))
    cueRunStateWs.callback!(frame({ activeCueId: 11, nextCueId: 12, nextIsArmed: true }))

    await vi.waitFor(() => {
      const stacks = cueStacksApi.endpoints.projectCueStackList.select(1)(store.getState()).data
      expect(stacks?.[0].activeCueId).toBe(11)
      expect(stacks?.[0].nextCueId).toBe(12)
    })
  })

  it('setCueStackStandby POSTs the armed cue', async () => {
    await store.dispatch(
      cueStacksApi.endpoints.setCueStackStandby.initiate({ projectId: 1, stackId: 7, cueId: 12 }),
    )
    const req = lastStandbyRequest()
    expect(req).toBeDefined()
    expect(req!.method).toBe('POST')
    expect(JSON.parse(await req!.clone().text())).toEqual({ cueId: 12 })
  })

  function lastStandbyRequest(): Request | undefined {
    const calls = (globalThis.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls
    for (let i = calls.length - 1; i >= 0; i--) {
      const input = calls[i][0] as Request
      if (input.url.includes('cue-stacks/7/standby')) return input
    }
    return undefined
  }
})
