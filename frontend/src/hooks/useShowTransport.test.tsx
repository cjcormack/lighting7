// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { Provider } from 'react-redux'
import type { ReactNode } from 'react'
import type { CueStack, CueStackCueEntry } from '@/api/cueStacksApi'
import { installRecordingFetch, installRelativeUrlRequest } from '@/test/backendMock'

// lightingApi opens a real WebSocket at import; mock it before the store pulls it in.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

// The rAF-driven fade/auto-advance driver is not what this suite is about, and mocking it also
// hands us `cancelAnimations` as a spy — which `back()` and boundary GO are both meant to call.
const cancelAnimations = vi.fn()
vi.mock('@/hooks/useRunnerAnimation', () => ({
  useRunnerAnimation: () => ({ cancelAnimations }),
}))

import { store } from '../store'
import { restApi } from '../store/restApi'
import { runnerSlice, selectStackRunner } from '../store/runnerSlice'
import { useShowTransport } from './useShowTransport'

/**
 * Characterisation suite written **before** session 2b folds Run's hand-rolled transport into this
 * hook (`desk-simplification-plan.md` §Session 2b, phase 0). Run restates this hook block for
 * block — effective active cue, the two server-reconcile effects, fireGo, the no-op auto-advance
 * completion, isFadingActive/fadeRemainMs, boundary GO, back, setStandby — so every behaviour
 * pinned here is a behaviour the merge must preserve for BOTH callers.
 *
 * Two of these assertions are expected to change in phase 2 and say so at the point of assertion:
 * the mid-fade `stackCueSig` reset, and what a merged view derives from `activeCueId`.
 */

const cue = (id: number, over: Partial<CueStackCueEntry> = {}): CueStackCueEntry => ({
  id,
  name: `Q${id}`,
  sortOrder: id,
  layerCount: 0,
  adHocEffectCount: 0,
  autoAdvance: false,
  autoAdvanceDelayMs: null,
  fadeDurationMs: 2000,
  fadeCurve: 'LINEAR',
  cueNumber: String(id),
  cueNumberAuto: false,
  notes: null,
  cueType: 'STANDARD',
  ...over,
})

const mkStack = (over: Partial<CueStack> = {}): CueStack => ({
  id: 10,
  name: 'Act 1',
  loop: false,
  sortOrder: 0,
  type: 'STACK',
  label: null,
  cues: [cue(1), cue(2), cue(3)],
  activeCueId: null,
  nextCueId: null,
  canEdit: true,
  canDelete: true,
  ...over,
})

function wrapper({ children }: { children: ReactNode }) {
  return <Provider store={store}>{children}</Provider>
}

interface Args {
  activeStackId: number | null
  stacks: CueStack[] | undefined
  canOperate?: boolean
  onBeforeGo?: () => void
  frameRateProgress?: boolean
}

function draw(args: Args) {
  return renderHook((p: Args) => useShowTransport({ projectId: 1, ...p }), {
    wrapper,
    initialProps: args,
  })
}

function runnerFor(stackId: number) {
  return selectStackRunner(store.getState() as never, stackId)
}

describe('useShowTransport', () => {
  let fetchMock: ReturnType<typeof installRecordingFetch>
  let dispatchSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    installRelativeUrlRequest()
    fetchMock = installRecordingFetch()
    // A clean runner for every stack these tests touch — the store is a module singleton.
    for (const id of [10, 11]) {
      store.dispatch(runnerSlice.actions.resetStack({ stackId: id, cues: [] }))
    }
    dispatchSpy = vi.spyOn(store, 'dispatch')
  })

  afterEach(() => {
    dispatchSpy.mockRestore()
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
    vi.clearAllMocks()
  })

  /**
   * RTK Query dispatches the request and *then* fetches, and `installRecordingFetch` resolves on a
   * 1ms timer. Every assertion about what did or did not reach the network has to flush first —
   * including the negative ones, which would otherwise pass against a hook that never fires.
   */
  const flush = () => act(async () => { await new Promise((r) => setTimeout(r, 10)) })

  function posts(fragment: string) {
    return fetchMock.mock.calls
      .map((c) => c[0] as Request)
      .filter((r) => r.url.includes(fragment))
  }

  function resetStackDispatches() {
    const calls = dispatchSpy.mock.calls as unknown as Array<[{ type?: string }]>
    return calls.map((c) => c[0]).filter((a) => a?.type === 'runner/resetStack')
  }

  // ── The effective active cue ─────────────────────────────────────────────────────────────────

  it('reports the server active cue while idle', () => {
    const { result } = draw({ activeStackId: 10, stacks: [mkStack({ activeCueId: 2 })] })
    expect(result.current.activeCueId).toBe(2)
    expect(result.current.fadeProgress).toBeNull()
  })

  it('keeps the frame-rate values null for an opted-out host, but still hands over the descriptor', () => {
    // The Programmer page opts out: it must not pay a per-rAF re-render for a fade it does not
    // draw. It used to mount the transport for the ShowBar's props; since the space plan's session
    // 5 it draws no bar at all and reads only `showHeaderProps`, so it consumes nothing frame-rate
    // whatsoever — which makes the opt-out more clearly right there, not less. Busk and the Prompt
    // Book are the other two opted-out hosts, and for them the bar's countdown still comes from
    // `fade`, which stays live regardless.
    const { result } = draw({
      activeStackId: 10,
      stacks: [mkStack({ activeCueId: 1, nextCueId: 2 })],
      frameRateProgress: false,
    })
    act(() => result.current.go())
    act(() => {
      store.dispatch(
        runnerSlice.actions.startFade({
          stackId: 10,
          cueId: 2,
          startMs: performance.now(),
          durationMs: 2000,
        }),
      )
    })
    expect(result.current.fade).toMatchObject({ cueId: 2, durationMs: 2000 })
    expect(result.current.fadeProgress).toBeNull()
    expect(result.current.fadeRemainMs).toBeNull()
    expect(result.current.autoProgress).toBeNull()
  })

  it('prefers the optimistic runner cursor once GO has fired', () => {
    // The whole point of the optimistic cursor: the fade animates from the instant GO is pressed,
    // without waiting for the server. Session 2b keeps BOTH readings — this one drives the fade
    // chrome, `activeStack.activeCueId` drives the stable marker — so it must not collapse to one.
    const { result } = draw({
      activeStackId: 10,
      stacks: [mkStack({ activeCueId: 1, nextCueId: 2 })],
    })
    expect(result.current.activeCueId).toBe(1)

    act(() => result.current.go())

    expect(runnerFor(10).activeCueId).toBe(2)
    expect(result.current.activeCueId).toBe(2)
  })

  // ── resetStack keying ────────────────────────────────────────────────────────────────────────

  it('resets the runner on mount and on a stack switch', () => {
    const a = mkStack({ id: 10, activeCueId: 2, nextCueId: 3 })
    const b = mkStack({ id: 11, name: 'Act 2', activeCueId: null })
    const { rerender } = draw({ activeStackId: 10, stacks: [a, b] })
    expect(resetStackDispatches()).toHaveLength(1)

    rerender({ activeStackId: 11, stacks: [a, b] })
    expect(resetStackDispatches()).toHaveLength(2)
  })

  it('resets when the cue order changes, so a re-sort recomputes done/next', () => {
    // This is the keying Run does NOT have: Run keys only on `activeStackId`, so after its own
    // "Fix Order" (`sortByCueNumber`) the completed/standby cursors stay computed against the old
    // order for ever. The merge adopts this hook's `stackCueSig` keying, which fixes it.
    const stack = mkStack({ activeCueId: 1, nextCueId: 2 })
    const { rerender } = draw({ activeStackId: 10, stacks: [stack] })
    expect(resetStackDispatches()).toHaveLength(1)

    const resorted = mkStack({ cues: [cue(3), cue(1), cue(2)], activeCueId: 1, nextCueId: 2 })
    rerender({ activeStackId: 10, stacks: [resorted] })
    expect(resetStackDispatches()).toHaveLength(2)
  })

  it('does not reset for a new stack object carrying the same cue ids', () => {
    // An unrelated refetch hands back a fresh object every time. Resetting on that would discard
    // an armed standby and an in-flight fade on every poll.
    const { rerender } = draw({ activeStackId: 10, stacks: [mkStack({ activeCueId: 1 })] })
    expect(resetStackDispatches()).toHaveLength(1)

    rerender({ activeStackId: 10, stacks: [mkStack({ activeCueId: 1 })] })
    expect(resetStackDispatches()).toHaveLength(1)
  })

  it('does not reset mid-fade when the cue order changes', () => {
    // Was a defect, fixed in phase 2. `go()` sets the optimistic cursor; a signature change used
    // to re-run the reconcile effect, which had no mid-fade guard, and the fade stopped dead —
    // reachable in one press via the out-of-order banner's "Fix Order".
    const { result, rerender } = draw({
      activeStackId: 10,
      stacks: [mkStack({ activeCueId: 1, nextCueId: 2 })],
    })
    act(() => result.current.go())
    expect(runnerFor(10).activeCueId).toBe(2)

    rerender({
      activeStackId: 10,
      stacks: [mkStack({ cues: [cue(3), cue(1), cue(2)], activeCueId: 1, nextCueId: 2 })],
    })
    expect(runnerFor(10).activeCueId).toBe(2)
  })

  it('applies a deferred reorder once the fade finishes', () => {
    // The other half: deferring must not mean dropping. When the cursor clears, the effect re-runs
    // and the reorder is finally reconciled — otherwise "done" and "next" stay computed against an
    // order that no longer exists.
    const { result, rerender } = draw({
      activeStackId: 10,
      stacks: [mkStack({ activeCueId: 1, nextCueId: 2 })],
    })
    act(() => result.current.go())
    const before = resetStackDispatches().length

    rerender({
      activeStackId: 10,
      stacks: [mkStack({ cues: [cue(3), cue(1), cue(2)], activeCueId: 1, nextCueId: 2 })],
    })
    expect(resetStackDispatches()).toHaveLength(before)

    // The fade completing is what releases it.
    act(() => {
      store.dispatch(runnerSlice.actions.markDone({ stackId: 10, cueId: 2 }))
    })
    expect(resetStackDispatches()).toHaveLength(before + 1)
  })

  it('resets immediately when the stack switches mid-fade', () => {
    // Deferral is only right for a reorder of the stack you are on. A switch means the fade
    // belongs to the stack being left, so the incoming stack must initialise at once.
    const a = mkStack({ id: 10, activeCueId: 1, nextCueId: 2 })
    const b = mkStack({ id: 11, name: 'Act 2' })
    const { result, rerender } = draw({ activeStackId: 10, stacks: [a, b] })
    act(() => result.current.go())
    const before = resetStackDispatches().length

    rerender({ activeStackId: 11, stacks: [a, b] })
    expect(resetStackDispatches()).toHaveLength(before + 1)
  })

  it('reports the server cursor separately from the fade cursor', () => {
    // Two cursors, two fields, no mode. The marker reads `serverActiveCueId` so it holds on the
    // outgoing cue; the fade chrome reads `activeCueId` so it moves at once.
    const { result } = draw({
      activeStackId: 10,
      stacks: [mkStack({ activeCueId: 1, nextCueId: 2 })],
    })
    expect(result.current.serverActiveCueId).toBe(1)

    act(() => result.current.go())

    expect(result.current.activeCueId).toBe(2)
    expect(result.current.serverActiveCueId).toBe(1)
  })

  it('reports the cues this session has run', () => {
    const { result } = draw({
      activeStackId: 10,
      stacks: [mkStack({ activeCueId: 1, nextCueId: 2 })],
    })
    expect(result.current.completedCueIds).toEqual([1])
    expect(result.current.autoProgress).toBeNull()
  })

  it('re-inits when the server moves the live cue while nothing is fading', () => {
    const { rerender } = draw({ activeStackId: 10, stacks: [mkStack({ activeCueId: 1 })] })
    expect(resetStackDispatches()).toHaveLength(1)

    // Same cue ids, so `stackCueSig` is unchanged — only the second effect can react.
    rerender({ activeStackId: 10, stacks: [mkStack({ activeCueId: 2 })] })
    expect(resetStackDispatches()).toHaveLength(2)
    expect(runnerFor(10).serverActiveCueId).toBe(2)
  })

  it('ignores a server live-cue move while a fade is in flight', () => {
    // The guard at the top of the second reconcile effect. Without it, hearing our own GO back
    // from the server would restart the fade we are already drawing.
    const { result, rerender } = draw({
      activeStackId: 10,
      stacks: [mkStack({ activeCueId: 1, nextCueId: 2 })],
    })
    act(() => result.current.go())
    const before = resetStackDispatches().length

    rerender({ activeStackId: 10, stacks: [mkStack({ activeCueId: 2, nextCueId: 3 })] })
    expect(resetStackDispatches()).toHaveLength(before)
    expect(runnerFor(10).activeCueId).toBe(2)
  })

  // ── GO ───────────────────────────────────────────────────────────────────────────────────────

  it('advances within the stack when a cue is on deck', async () => {
    const { result } = draw({
      activeStackId: 10,
      stacks: [mkStack({ activeCueId: 1, nextCueId: 2 })],
    })
    act(() => result.current.go())

    await flush()
    expect(posts('cue-stacks/10/advance')).toHaveLength(1)
    expect(posts('cue-stacks/10/activate')).toHaveLength(0)
    expect(posts('show/advance')).toHaveLength(0)
  })

  it('activates rather than advances when the stack is stopped', async () => {
    // A stopped stack still has an armed cue; `activate` starts on it. Advancing would skip it.
    const { result } = draw({
      activeStackId: 10,
      stacks: [mkStack({ activeCueId: null, nextCueId: 1 })],
    })
    act(() => result.current.go())

    await flush()
    expect(posts('cue-stacks/10/activate')).toHaveLength(1)
    expect(posts('cue-stacks/10/advance')).toHaveLength(0)
  })

  it('crosses to the next stack when nothing is on deck', async () => {
    // End of the stack: `nextCueId` null and the last cue live, so the runner has no standby.
    const a = mkStack({ id: 10, activeCueId: 3, nextCueId: null })
    const b = mkStack({ id: 11, name: 'Act 2', sortOrder: 1 })
    const { result } = draw({ activeStackId: 10, stacks: [a, b] })
    expect(runnerFor(10).standbyCueId).toBeNull()

    act(() => result.current.go())

    await flush()
    expect(posts('show/advance')).toHaveLength(1)
    expect(posts('cue-stacks/10/advance')).toHaveLength(0)
    expect(cancelAnimations).toHaveBeenCalled()
  })

  it('does nothing at the end of the last stack', async () => {
    const { result } = draw({
      activeStackId: 10,
      stacks: [mkStack({ activeCueId: 3, nextCueId: null })],
    })
    act(() => result.current.go())

    await flush()
    expect(posts('show/advance')).toHaveLength(0)
    expect(posts('cue-stacks/10/advance')).toHaveLength(0)
  })

  it('skips SEPARATOR rows when crossing a stack boundary', async () => {
    const a = mkStack({ id: 10, activeCueId: 3, nextCueId: null })
    const sep: CueStack = mkStack({ id: 99, type: 'SEPARATOR', label: 'Interval', cues: [] })
    const b = mkStack({ id: 11, sortOrder: 2 })
    const { result } = draw({ activeStackId: 10, stacks: [a, sep, b] })
    act(() => result.current.go())
    await flush()
    expect(posts('show/advance')).toHaveLength(1)
  })

  it('runs onBeforeGo first, even when GO turns out to be a no-op', async () => {
    // The Prompt Book passes `noteGo` here to re-lock on GO, and session 2b's merged Show does the
    // same. It must fire on the operator's intent, not only on a successful advance.
    const onBeforeGo = vi.fn()
    const { result } = draw({
      activeStackId: 10,
      stacks: [mkStack({ activeCueId: 3, nextCueId: null })],
      onBeforeGo,
    })
    act(() => result.current.go())
    expect(onBeforeGo).toHaveBeenCalledTimes(1)
    await flush()
    expect(posts('show/advance')).toHaveLength(0)
  })

  // ── BACK ─────────────────────────────────────────────────────────────────────────────────────

  it('steps back on the server when a cue is live', async () => {
    const { result } = draw({ activeStackId: 10, stacks: [mkStack({ activeCueId: 2 })] })
    act(() => result.current.back())

    expect(cancelAnimations).toHaveBeenCalled()
    await flush()
    const req = posts('cue-stacks/10/advance')
    expect(req).toHaveLength(1)
    expect(req[0].method).toBe('POST')
  })

  it('moves only the local cursor when nothing is live', async () => {
    const { result } = draw({ activeStackId: 10, stacks: [mkStack({ activeCueId: null })] })
    act(() => result.current.back())

    await flush()
    expect(posts('cue-stacks/10/advance')).toHaveLength(0)
    expect(cancelAnimations).toHaveBeenCalled()
  })

  // ── Standby ──────────────────────────────────────────────────────────────────────────────────

  it('arms a cue locally and on the server', async () => {
    const { result } = draw({ activeStackId: 10, stacks: [mkStack({ activeCueId: 1 })] })
    act(() => result.current.setStandby(3))

    expect(runnerFor(10).standbyCueId).toBe(3)
    await flush()
    expect(posts('cue-stacks/10/standby')).toHaveLength(1)
  })

  it('refuses to arm the cue that is already live', async () => {
    const { result } = draw({ activeStackId: 10, stacks: [mkStack({ activeCueId: 2 })] })
    act(() => result.current.setStandby(2))
    await flush()
    expect(posts('cue-stacks/10/standby')).toHaveLength(0)
  })

  // ── goDisabled ───────────────────────────────────────────────────────────────────────────────

  it('disables the transport when the show is stopped', () => {
    const { result } = draw({ activeStackId: null, stacks: [mkStack()] })
    expect(result.current.goDisabled).toBe(true)
  })

  it('disables the transport when the caller cannot operate', () => {
    // NOTE for 2b: this gate is NOT the edit lock. Locked is the normal running-show state and GO
    // must work in it; the merged view passes the project's `canEdit` here, never `locked`.
    const { result } = draw({ activeStackId: 10, stacks: [mkStack()], canOperate: false })
    expect(result.current.goDisabled).toBe(true)
  })

  it('enables the transport by default while the show runs', () => {
    const { result } = draw({ activeStackId: 10, stacks: [mkStack()] })
    expect(result.current.goDisabled).toBe(false)
  })
})
