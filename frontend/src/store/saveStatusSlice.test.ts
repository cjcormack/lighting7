import { describe, expect, it } from 'vitest'

// lightingApi opens a real WebSocket at import; the store modules the endpoint-name check pulls
// in register WS subscriptions at module load, so it has to be stubbed first.
import { vi } from 'vitest'
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import {
  NON_SAVE_ENDPOINTS,
  saveStatusSlice,
  type SaveStatusState,
} from './saveStatusSlice'

const reducer = saveStatusSlice.reducer

/** An endpoint lifecycle action shaped the way RTK Query dispatches one. */
function lifecycle(
  phase: 'pending' | 'fulfilled' | 'rejected',
  opts: {
    endpointName: string
    type?: 'query' | 'mutation'
    condition?: boolean
    requestId?: string
    /** Goes into `originalArgs`, the way a trigger's argument reaches the action. */
    cueId?: number
  },
) {
  return {
    type: `restApi/executeMutation/${phase}`,
    payload: undefined,
    meta: {
      arg: {
        type: opts.type ?? 'mutation',
        endpointName: opts.endpointName,
        originalArgs: opts.cueId === undefined ? undefined : { projectId: 1, cueId: opts.cueId },
      },
      condition: opts.condition,
      requestId: opts.requestId ?? 'req-1',
      requestStatus: phase,
    },
  }
}

/** Fold a run of actions over the reducer from the initial state. */
function run(...actions: unknown[]): SaveStatusState {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return actions.reduce<SaveStatusState>((s, a) => reducer(s, a as any), undefined as never)
}

describe('saveStatusSlice', () => {
  it('counts a save as pending, then reports it saved', () => {
    const pending = run(lifecycle('pending', { endpointName: 'patchProjectCue' }))
    expect(pending).toEqual({ pending: 1, savedTick: 0, byCue: {} })

    const done = reducer(pending, lifecycle('fulfilled', { endpointName: 'patchProjectCue' }))
    expect(done).toEqual({ pending: 0, savedTick: 1, byCue: {} })
  })

  it('does not claim a save when the mutation fails', () => {
    // errorToastMiddleware already reports the failure; a "Saved" beside that toast would lie.
    const state = run(
      lifecycle('pending', { endpointName: 'patchProjectCue' }),
      lifecycle('rejected', { endpointName: 'patchProjectCue' }),
    )
    expect(state).toEqual({ pending: 0, savedTick: 0, byCue: {} })
  })

  it('tracks concurrent saves as one pending run', () => {
    // Tabbing through the props pane fires several PATCHes; the pill must stay on "Saving…"
    // until the last one lands, not flicker per response.
    let state = run(
      lifecycle('pending', { endpointName: 'patchProjectCue', requestId: 'a' }),
      lifecycle('pending', { endpointName: 'createProjectCue', requestId: 'b' }),
    )
    expect(state.pending).toBe(2)

    state = reducer(state, lifecycle('fulfilled', { endpointName: 'patchProjectCue', requestId: 'a' }))
    expect(state.pending).toBe(1)

    state = reducer(state, lifecycle('fulfilled', { endpointName: 'createProjectCue', requestId: 'b' }))
    expect(state).toEqual({ pending: 0, savedTick: 2, byCue: {} })
  })

  it('ignores a request RTK skipped, which never pends', () => {
    // A skipped request rejects with `condition` and no preceding `pending`. Counting it down
    // would strand the counter at zero while a real save was still in flight.
    const state = run(
      lifecycle('pending', { endpointName: 'patchProjectCue', requestId: 'real' }),
      lifecycle('rejected', { endpointName: 'patchProjectCue', condition: true, requestId: 'skip' }),
    )
    expect(state.pending).toBe(1)
  })

  it('ignores queries', () => {
    // Queries refetch on focus and poll; they are not the operator saving anything.
    const state = run(
      lifecycle('pending', { endpointName: 'projectCueList', type: 'query' }),
      lifecycle('fulfilled', { endpointName: 'projectCueList', type: 'query' }),
    )
    expect(state).toEqual({ pending: 0, savedTick: 0, byCue: {} })
  })

  it('ignores transport commands', () => {
    // A GO during a performance must not flash "Saved" — nothing was persisted.
    const state = run(
      lifecycle('pending', { endpointName: 'advanceCueStack' }),
      lifecycle('fulfilled', { endpointName: 'advanceCueStack' }),
    )
    expect(state).toEqual({ pending: 0, savedTick: 0, byCue: {} })
  })

  it('ignores live output changes', () => {
    const state = run(
      lifecycle('pending', { endpointName: 'updateChannel' }),
      lifecycle('fulfilled', { endpointName: 'updateChannel' }),
    )
    expect(state).toEqual({ pending: 0, savedTick: 0, byCue: {} })
  })

  it('ignores unrelated actions', () => {
    expect(run({ type: 'runner/setThing', payload: 1 })).toEqual({
      pending: 0,
      savedTick: 0,
      byCue: {},
    })
  })
})

describe('per-cue tracking', () => {
  it('credits a save to the cue it names', () => {
    const state = run(
      lifecycle('pending', { endpointName: 'patchProjectCue', cueId: 7 }),
      lifecycle('fulfilled', { endpointName: 'patchProjectCue', cueId: 7 }),
    )
    expect(state.byCue[7]).toEqual({ pending: 0, savedTick: 1 })
  })

  it('keeps one cue’s saves off another cue’s card', () => {
    // The whole point of the per-cue counters: editing Q1 must not make Q2's card say "Saved".
    const state = run(
      lifecycle('pending', { endpointName: 'patchProjectCue', cueId: 7, requestId: 'a' }),
      lifecycle('fulfilled', { endpointName: 'patchProjectCue', cueId: 7, requestId: 'a' }),
    )
    expect(state.byCue[8]).toBeUndefined()
    expect(state.savedTick).toBe(1)
  })

  it('does not credit a cue when its save fails', () => {
    const state = run(
      lifecycle('pending', { endpointName: 'patchProjectCue', cueId: 7 }),
      lifecycle('rejected', { endpointName: 'patchProjectCue', cueId: 7 }),
    )
    expect(state.byCue[7]).toEqual({ pending: 0, savedTick: 0 })
  })

  it('still counts a cue-less mutation show-wide', () => {
    // Creating a cue or reordering a stack names no cue; the header pill must still move.
    const state = run(
      lifecycle('pending', { endpointName: 'reorderCueStackCues' }),
      lifecycle('fulfilled', { endpointName: 'reorderCueStackCues' }),
    )
    expect(state).toEqual({ pending: 0, savedTick: 1, byCue: {} })
  })

  it('tracks concurrent saves to the same cue as one pending run', () => {
    // Two fields autosaving at once must not clear the card's spinner on the first response.
    let state = run(
      lifecycle('pending', { endpointName: 'patchProjectCue', cueId: 7, requestId: 'a' }),
      lifecycle('pending', { endpointName: 'patchProjectCue', cueId: 7, requestId: 'b' }),
    )
    expect(state.byCue[7].pending).toBe(2)

    state = reducer(state, lifecycle('fulfilled', { endpointName: 'patchProjectCue', cueId: 7 }))
    expect(state.byCue[7].pending).toBe(1)
  })
})

describe('NON_SAVE_ENDPOINTS', () => {
  it('only names endpoints that actually exist', async () => {
    // A renamed or deleted endpoint would leave a stale entry here — harmless-looking, but it
    // means the *real* endpoint of that name silently starts counting as a save, so a GO would
    // begin flashing "Saved" mid-show.
    const { restApi } = await import('./restApi')
    await Promise.all([
      import('./cues'),
      import('./cueStacks'),
      import('./channels'),
      import('./park'),
      import('./fixtureFx'),
      import('./groups'),
      import('./looks'),
      import('./templates'),
      import('./busk'),
      import('./fxDefinitions'),
      import('./scripts'),
      import('./projects'),
      import('./cloudSync'),
      import('./oauthGithub'),
      import('./ai'),
      import('./programmerOps'),
      import('./perf'),
      import('./status'),
    ])

    const known = new Set(Object.keys(restApi.endpoints))
    const unknown = [...NON_SAVE_ENDPOINTS].filter((name) => !known.has(name))
    expect(unknown).toEqual([])
  })
})
