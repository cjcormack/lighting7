import { useCallback, useEffect, useMemo, useRef } from 'react'
import { useDispatch, useSelector } from 'react-redux'
import type { CueStack } from '../api/cueStacksApi'
import {
  useAdvanceCueStackMutation,
  useActivateCueStackMutation,
  useAdvanceProgramMutation,
  useSetCueStackStandbyMutation,
} from '../store/cueStacks'
import {
  go as goAction,
  back as backAction,
  resetStack,
  setStandby as setStandbyAction,
  selectStackRunner,
  runnerSlice,
} from '../store/runnerSlice'
import type { RunnerAnimSpan } from '../store/runnerSlice'
import { useRunnerAnimation } from './useRunnerAnimation'
import { useAnimatedProgress } from './useAnimatedProgress'

interface UseShowTransportArgs {
  projectId: number
  /** The project's live stack (the show playhead), or null when the show is not running. */
  activeStackId: number | null
  stacks: CueStack[] | undefined
  /** Extra gate ANDed into `goDisabled`. Prompt Book passes `canEdit`. Default true. */
  canOperate?: boolean
  /** Runs at the top of `go()` before any dispatch. Prompt Book passes `noteGo` (relock-on-GO). */
  onBeforeGo?: () => void
  /**
   * Pass false when the host consumes none of `fadeProgress`/`fadeRemainMs`/`autoProgress` — they
   * come back null and, more to the point, the per-rAF re-render that computes them never runs, so
   * the host stops paying frame rate for a fade it doesn't draw. The Programmer page opts out: it
   * mounts this hook only for the ShowBar's props, and the bar animates from the write-once `fade`
   * descriptor (unaffected by this flag) instead. Default true.
   */
  frameRateProgress?: boolean
}

export interface ShowTransport {
  activeStackId: number | null
  activeStack: CueStack | undefined
  /** Effective active cue: the optimistic runner cursor during a fade, else the server's active cue. */
  activeCueId: number | null
  /**
   * The live cue as the *server* reports it — what is actually on stage.
   *
   * Both cursors are returned because a cue row needs both and they answer different questions:
   * this one places the stable marker (it must not jitter to the incoming cue mid-fade), while
   * `activeCueId` above says which row owns the fade chrome. Kept as two values rather than one
   * chosen by a mode, so identical data never renders differently depending on ambient state.
   */
  serverActiveCueId: number | null
  standbyCueId: number | null
  /** Cues this session has run on this stack — the "done" tick. */
  completedCueIds: number[]
  /** 0..1 while an auto-advance timer runs, else null. */
  autoProgress: number | null
  /** 0..1 while the live cue fades in, else null. */
  fadeProgress: number | null
  fadeRemainMs: number | null
  /**
   * The live cue's fade *descriptor* — written once per transition, gated to the current cursor.
   * This is the frame-rate-free way to hand a fade onwards: the `ShowBar` takes this (stable
   * identity for the whole fade, so its memo holds) and animates the countdown itself, where
   * `fadeProgress`/`fadeRemainMs` above change on every render of this hook's host.
   */
  fade: RunnerAnimSpan | null
  /** `!isShowActive || !canOperate` — disables the transport. */
  goDisabled: boolean
  go: () => void
  back: () => void
  setStandby: (cueId: number) => void
  cancelAnimations: () => void
}

/**
 * The "follow-server" show transport shared by the Program (Edit) and Prompt Book views: the
 * active stack is the project playhead (`activeStackId`, passed in from `projectProgramState`),
 * GO advances across stack boundaries via `advanceProgram`, and the optimistic runner slice
 * drives the fade animation. This is a hook-ified lift of the block that previously lived inline
 * in PromptBookPage.
 *
 * **Every surface uses it**, reached through `useShowBarProps`. This docblock used to say the Run
 * view "deliberately does NOT use this — its manual stack-tab browsing model is different code, not
 * duplication", which was true only because browsing and the playhead were one variable there.
 * Session 2b split them, and the difference went with it: Run had been restating this block for
 * block, and its version carried two defects this one does not — it keyed `resetStack` on the stack
 * id alone, so its own "Fix Order" never recomputed the done/next cursors, and its reset payload
 * omitted `serverNextCueId`, which the backend owns.
 *
 * ── Cursor ownership ──
 *
 * One `cueRunStateChanged` frame lands in two stores, so it matters which copy is the fact and
 * which is derived:
 *
 * - **The RTK cache owns the server's run facts**: `stack.activeCueId` (the cue on stage) and
 *   `stack.nextCueId` (the effective next — armed standby, else positional), patched by the
 *   subscriber in `store/cueStacks.ts` so a refetch can't flap back.
 * - **The runner slice owns what is genuinely local**: the animating cursor (`activeCueId`), the
 *   optimistic next (`standbyCueId` — seeded from the server's `nextCueId`, moved *ahead* of the
 *   server by go/back/setStandby, confirmed by the next frame), this session's done ticks, and the
 *   write-once fade/auto descriptors. Its `serverActiveCueId` / `serverAutoAdvance*` fields are not
 *   a competing copy of the cache but the slice's memory of the last frame it adopted — kept
 *   because a reducer cannot read the cache, and read only by the slice's own transition logic.
 *
 * Who reads which cursor, and why:
 *
 * - The stable "on stage" marker (`ShowView`'s green row, cue expansion, `OffPlayheadBanner`)
 *   reads `serverActiveCueId` — it must hold on the outgoing cue mid-fade.
 * - The fade chrome reads `activeCueId` — it must move the instant GO is pressed, before the
 *   server answers.
 * - The NEXT pill and boundary GO read `standbyCueId` — the effective next, optimistically moved.
 * - The Prompt Book's `statusOf` deliberately reads the optimistic `activeCueId`: the reader
 *   follows the fade, not the stage. The stage's Next-GO preview reads the cache's `nextCueId`
 *   directly (`useNextGoPreview`) — it wants only the server's opinion.
 */
export function useShowTransport({
  projectId,
  activeStackId,
  stacks,
  canOperate,
  onBeforeGo,
  frameRateProgress = true,
}: UseShowTransportArgs): ShowTransport {
  const dispatch = useDispatch()

  const [advanceCueStack] = useAdvanceCueStackMutation()
  const [activateCueStack] = useActivateCueStackMutation()
  const [advanceProgram] = useAdvanceProgramMutation()
  const [setCueStackStandby] = useSetCueStackStandbyMutation()

  const isShowActive = activeStackId != null

  const activeStack = useMemo(
    () => (activeStackId != null ? stacks?.find((s) => s.id === activeStackId) : undefined),
    [stacks, activeStackId],
  )

  // Consult the shared runner slice so a standby cue armed here (or on the Run page) is treated
  // as the "next" cue — the same source fireGo advances to.
  const runner = useSelector((state: { runner: ReturnType<typeof runnerSlice.getInitialState> }) =>
    selectStackRunner(state, activeStackId ?? 0),
  )

  // The server's live cue, straight off the cache — the "on stage" cursor (see the docblock).
  const serverActiveCueId = activeStack?.activeCueId ?? null

  // Live cue: the optimistic runner cursor while a fade animates, else the server's active cue.
  const activeCueId = runner.activeCueId ?? serverActiveCueId

  // ── Runner ↔ server reconciliation. Re-init when the stack (or its cues) first load and on
  // stack switch, on a cue reorder/add/remove (sig changes), AND when the cache's live cue moves
  // while nothing is fading here — a settled snapshot frame, a refetch revealing a missed
  // transition, or an optimistic patch from a local BACK. NOT on an unrelated refetch or
  // mid-fade re-render, so a user-armed standby and an in-flight fade are preserved. ──
  // Memoized on the stack object: this hook re-renders at frame rate through a fade, and the
  // signature only ever changes when the cache hands back a new stack.
  const stackCueSig = useMemo(
    () => (activeStack ? activeStack.cues.map((c) => c.id).join(',') : ''),
    [activeStack],
  )
  /**
   * What the runner was last initialised against. These two refs are what let the effect depend on
   * `runner.activeCueId` honestly rather than carrying a disabled lint rule: "is there anything to
   * do?" becomes a comparison instead of an inference from the dependency list.
   *
   * They also fix a real defect. A cue reorder that lands *mid-fade* changes the signature, and
   * resetting then nulls the optimistic cursor — so the fade the operator is watching stops dead.
   * (`sortByCueNumber` from the out-of-order banner is a one-press way to cause exactly that.) The
   * reset is therefore deferred while a fade is in flight and applied when it completes; the effect
   * re-runs at that point because the cursor it depends on has gone back to null.
   *
   * A *stack switch* mid-fade still resets immediately — that fade belongs to the stack being left.
   *
   * `appliedServerCueRef` is the same idea for the cache's live cue, and it is *not* a duplicate
   * of the slice's `serverActiveCueId`: that field is the store's memory of the last WS frame it
   * adopted (kept because a reducer cannot read the cache), while this ref is what *this effect*
   * last saw of the cache — and the two genuinely differ, both ways. The optimistic mutation
   * patches (`advanceCueStack`, `activateCueStack`, …) move the cache with no frame at all, and a
   * connect-time snapshot frame moves both stores at once while the done ticks it implies (a jump
   * of several cues heard as one frame) still need the positional recompute a reset does. So the
   * trigger is "did the cache cursor change since this effect last looked", never "do the two
   * stores disagree". Unlike the other two refs it advances even when the reset is skipped
   * mid-fade: a live-cue move heard during a fade is this session's own GO echoing back (the
   * local cursors already moved), and replaying it as a reset when the fade ends would clobber a
   * standby armed during the fade.
   */
  const appliedStackRef = useRef<number | null>(null)
  const appliedSigRef = useRef<string | null>(null)
  const appliedServerCueRef = useRef<number | null>(null)
  useEffect(() => {
    if (activeStackId == null || !activeStack) return
    const sameStack = appliedStackRef.current === activeStackId
    const serverMoved = sameStack && appliedServerCueRef.current !== serverActiveCueId
    appliedServerCueRef.current = serverActiveCueId
    if (activeStack.cues.length === 0) return
    if (sameStack && appliedSigRef.current === stackCueSig && !serverMoved) return
    if (sameStack && runner.activeCueId != null) return
    dispatch(
      resetStack({
        stackId: activeStackId,
        cues: activeStack.cues,
        serverActiveCueId,
        serverNextCueId: activeStack.nextCueId,
        loop: activeStack.loop,
      }),
    )
    appliedStackRef.current = activeStackId
    appliedSigRef.current = stackCueSig
    // `activeStack` is read for its cues/next/loop only when a reset is due (the refs say so),
    // so it is deliberately not a dependency — a new object with the same ids and cursors must
    // not re-run this. Its live cue participates as the derived primitive above, so a patch or
    // refetch that *moves* it does.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeStackId, stackCueSig, serverActiveCueId, runner.activeCueId, dispatch])

  // ── Fade / auto-advance animation. Keyed on runner.activeCueId, which the optimistic go()
  // below sets the instant GO is pressed. ──
  const animCue = useMemo(
    () =>
      runner.activeCueId != null
        ? activeStack?.cues.find((c) => c.id === runner.activeCueId)
        : undefined,
    [activeStack, runner.activeCueId],
  )

  // Server call to move the backend cursor. No standby branch any more: the backend owns the
  // armed cue, so `advance` FORWARD fires whatever is on deck. A stopped stack still needs
  // `activate`, which starts on the armed cue too.
  const fireGoServer = useCallback(() => {
    if (activeStackId == null || !activeStack) return
    if (activeStack.activeCueId == null) {
      activateCueStack({ projectId, stackId: activeStackId })
    } else {
      advanceCueStack({ projectId, stackId: activeStackId, direction: 'FORWARD' })
    }
  }, [activeStackId, activeStack, activateCueStack, advanceCueStack, projectId])

  // The *display* of auto-advance completing. The backend runs its own auto-advance timer and
  // fires the next cue itself, then broadcasts — so calling the server here as well would step
  // the stack once per open session.
  const handleAutoAdvanceComplete = useCallback(() => {}, [])

  const { cancelAnimations } = useRunnerAnimation({
    stackId: activeStackId ?? 0,
    activeCueId: runner.activeCueId,
    fadeDurationMs: animCue?.fadeDurationMs ?? null,
    // The server's view, not the cue's flag: a paused timer (cue-edit Live session, surface
    // Pause) leaves a cue configured for auto-advance that will not advance. Falls back to the
    // cue while no frame has arrived yet.
    autoAdvance: runner.serverAutoAdvance ?? animCue?.autoAdvance ?? false,
    autoAdvanceDelayMs: runner.serverAutoAdvanceDelayMs ?? animCue?.autoAdvanceDelayMs ?? null,
    startElapsedMs: runner.fadeStartElapsedMs,
    transitionSeq: runner.serverTransition,
    onAutoAdvanceComplete: handleAutoAdvanceComplete,
  })

  // The store only carries the fade/auto *descriptors* (written once per transition); the
  // frame-rate progress is computed here, locally, so only this hook's host re-renders per frame
  // rather than every selectStackRunner subscriber. A live cue with no descriptor yet (GO landed,
  // the animation effect hasn't) reads as progress 0, as the old reset-to-0-on-go did.
  const fadeAnim =
    runner.activeCueId != null && runner.fade?.cueId === runner.activeCueId ? runner.fade : null
  // A null span subscribes to nothing, so an opted-out host takes no per-frame re-render here.
  const animatedFade = useAnimatedProgress(frameRateProgress ? fadeAnim : null)
  const rawFadeProgress =
    !frameRateProgress || runner.activeCueId == null ? null : (animatedFade ?? 0)
  const isFadingActive = rawFadeProgress != null && rawFadeProgress < 1
  const fadeProgress = isFadingActive ? rawFadeProgress : null
  const fadeRemainMs = (() => {
    if (!isFadingActive || !animCue) return null
    const dur = animCue.fadeDurationMs ?? 0
    if (dur <= 0) return null
    return Math.max(0, dur * (1 - rawFadeProgress))
  })()

  const autoProgress = useAnimatedProgress(frameRateProgress ? runner.auto : null)

  const go = useCallback(() => {
    onBeforeGo?.()
    // Boundary GO: nothing on deck → advance to the next runnable stack in show order.
    if (runner.standbyCueId == null) {
      if (activeStackId == null || !stacks) return
      const runnable = stacks.filter((s) => s.type === 'STACK')
      const curIdx = runnable.findIndex((s) => s.id === activeStackId)
      const nextStack = curIdx >= 0 ? runnable[curIdx + 1] : undefined
      if (nextStack) {
        advanceProgram({ projectId, direction: 'FORWARD' })
        cancelAnimations()
      }
      return
    }
    if (activeStackId == null || !activeStack) return
    // Optimistic go() sets runner.activeCueId → fade animates immediately; the server is told
    // in lock-step via fireGoServer.
    dispatch(goAction({ stackId: activeStackId, cues: activeStack.cues, loop: activeStack.loop }))
    fireGoServer()
  }, [onBeforeGo, runner.standbyCueId, stacks, activeStackId, activeStack, advanceProgram, projectId, cancelAnimations, dispatch, fireGoServer])

  const back = useCallback(() => {
    if (activeStackId == null || !activeStack) return
    cancelAnimations()
    dispatch(backAction({ stackId: activeStackId, cues: activeStack.cues }))
    if (activeStack.activeCueId != null) {
      advanceCueStack({ projectId, stackId: activeStackId, direction: 'BACKWARD' })
    }
  }, [activeStackId, activeStack, cancelAnimations, dispatch, advanceCueStack, projectId])

  const setStandby = useCallback(
    (cueId: number) => {
      if (activeStackId == null || cueId === activeCueId) return
      // Local dispatch for an instant highlight; the POST is what makes the desk, the tablet and
      // the MIDI surface agree on what GO will fire.
      dispatch(setStandbyAction({ stackId: activeStackId, cueId }))
      setCueStackStandby({ projectId, stackId: activeStackId, cueId })
    },
    [activeStackId, activeCueId, dispatch, setCueStackStandby, projectId],
  )

  const goDisabled = !isShowActive || !(canOperate ?? true)

  return {
    activeStackId,
    activeStack,
    activeCueId,
    serverActiveCueId,
    standbyCueId: runner.standbyCueId,
    completedCueIds: runner.completedCueIds,
    autoProgress,
    fadeProgress,
    fadeRemainMs,
    fade: fadeAnim,
    goDisabled,
    go,
    back,
    setStandby,
    cancelAnimations,
  }
}
