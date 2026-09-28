import { useCallback, useMemo, useState } from 'react'
import {
  useActivateProgramMutation,
  useDeactivateProgramMutation,
  useProjectCueStackListQuery,
  useProjectProgramStateQuery,
} from '../store/cueStacks'
import { useShowTransport } from './useShowTransport'
import { ignoreReportedError } from '../store/errorToastMiddleware'

/**
 * Everything `ShowBar` needs, derived from a project id.
 *
 * **Both live views that have a bar — Show and the Prompt Book — mount it from here**, overriding
 * only `showShortcuts` — which advertises keys, so only the host that binds them can answer it.
 * That uniformity is the point: the bar takes a dozen props, half of them derived by the same
 * three lines of cue lookup, and every host that wired it by hand drifted. The Prompt Book's copy
 * had no Blind tile and derived the stack name its own way; Show suppressed the stack name beside
 * the tab strip. The Prompt Book also called `useShowTransport` directly, so adopting this hook
 * there collapsed that page from two transport instances to one. The busk view was the third host
 * until the busk-chrome plan's session A: it still calls this hook, for the transport and the
 * `dbo` pair, and hands the result to its side sheet's Show tab (`components/busking/ShowTab.tsx`)
 * instead of drawing a bar. The programmer never had one.
 *
 * `canOperate` and `onBeforeGo` are parameters for the two things that genuinely are per-host:
 * book-level permission, and re-locking on GO.
 *
 * `isShowActive` is returned rather than applied here because the *caller* decides what an inactive
 * show looks like. No current host hides the bar on it — blackout and the speed masters both mean
 * something with the show down — but that is their call, not the hook's.
 *
 * **Blind is not supplied from here, for any host.** It was, from session 2b until
 * `PD-BLIND-ON-PROGRAMMER`: the bar drew a BLIND tile beside blackout on every view that had a bar,
 * and the programmer — the view without one since the space plan's session 5 — had no press at all.
 * Blind is a *programmer* fact (`ProgrammerSummary.blind`, written by `programmerSetBlind`, faded by
 * the programmer's own fade), so the toggle now lives in the programmer's action bar and the other
 * three views only *report* it, through the `ProgrammerIndicator` the bar already mounts. Uniformity
 * still holds: no host gets a tile, rather than some. Do not bring `onBlind` back for one host —
 * `ShowBar` draws the tile under `{onBlind && …}`, so a per-host arm here would put the control in
 * two places again, which is the split this hook was written to end.
 *
 * `showHeaderProps` comes along because Start/Stop is the same derivation from the same state, and
 * every host that needs the bar needs that too.
 */
export function useShowBarProps(
  projectId: number,
  {
    canOperate,
    onBeforeGo,
    frameRateProgress,
  }: {
    /** Extra gate ANDed into `goDisabled`. The Prompt Book passes its book-level `canEdit`. */
    canOperate?: boolean
    /** Runs at the top of `go()`. Surfaces with an edit lock pass `noteGo` to re-lock on GO. */
    onBeforeGo?: () => void
    /**
     * Pass false when the host reads nothing frame-rate from `transport` (see
     * `useShowTransport`) — the bar itself needs only the write-once `fade` descriptor, so a host
     * that mounts this hook purely for the bar stops re-rendering per frame during fades.
     */
    frameRateProgress?: boolean
  } = {},
) {
  const { data: stacks } = useProjectCueStackListQuery(projectId)
  const { data: programState } = useProjectProgramStateQuery(projectId)
  const activeStackId = programState?.activeStackId ?? null

  const transport = useShowTransport({
    projectId,
    activeStackId,
    stacks,
    canOperate,
    onBeforeGo,
    frameRateProgress,
  })
  const [dbo, setDbo] = useState(false)
  // Stable so the memoized `ShowBar` can bail on the frame-rate re-renders this hook's host takes
  // during a fade — a per-render arrow here would defeat that memo on every frame.
  const onDbo = useCallback(() => setDbo((d) => !d), [])

  const activeCue = transport.activeStack?.cues.find((c) => c.id === transport.activeCueId) ?? null
  const standbyCue =
    transport.activeStack?.cues.find((c) => c.id === transport.standbyCueId) ?? null

  /**
   * The stack a boundary GO will move to, when this one has nothing left on deck. Lifted out of
   * Run when the transport was unified: "next" going blank at the end of every stack reads as the
   * show having ended, when in fact GO is about to cross into the next act — and that was the one
   * piece of the bar's wiring only Run had.
   */
  const nextStack = useMemo(() => {
    if (transport.standbyCueId != null || activeStackId == null || !stacks) return null
    const runnable = stacks.filter((s) => s.type === 'STACK')
    const i = runnable.findIndex((s) => s.id === activeStackId)
    return i >= 0 ? (runnable[i + 1] ?? null) : null
  }, [transport.standbyCueId, stacks, activeStackId])

  const [activateShow] = useActivateProgramMutation()
  const [deactivateShow] = useDeactivateProgramMutation()
  const runnableStackCount = stacks?.filter((s) => s.type === 'STACK').length ?? 0

  // Both `.catch()` blocks swallow deliberately: `errorToastMiddleware` reports the failure, and
  // this is only here to stop the unhandled rejection.
  const onStart = useCallback(() => {
    activateShow({ projectId }).unwrap().catch(ignoreReportedError)
  }, [activateShow, projectId])
  const onStop = useCallback(async () => {
    await deactivateShow({ projectId }).unwrap().catch(ignoreReportedError)
  }, [deactivateShow, projectId])

  return {
    isShowActive: activeStackId != null,
    /** The stack a boundary GO crosses into, or null when this one still has cues on deck. */
    nextStack,
    /** The live and armed cue entries — already resolved here, so callers don't look them up twice. */
    activeCue,
    standbyCue,
    showHeaderProps: {
      isShowActive: activeStackId != null,
      canStart: activeStackId == null && runnableStackCount > 0,
      onStart,
      onStop,
    },
    transport,
    showBarProps: {
      stackName: transport.activeStack?.name ?? null,
      dbo,
      onDbo,
      activeNumber: activeCue?.cueNumber ? `Q${activeCue.cueNumber}` : null,
      activeName: activeCue?.name ?? null,
      standbyNumber: standbyCue?.cueNumber ? `Q${standbyCue.cueNumber}` : null,
      standbyName: standbyCue?.name ?? (nextStack ? `→ ${nextStack.name}` : null),
      // The *descriptor*, not the remaining ms: stable for the whole fade, so the bar's memo holds
      // while this hook's host re-renders at frame rate, and the bar counts down on its own clock.
      fade: transport.fade,
      onGo: transport.go,
      onBack: transport.back,
      goDisabled: transport.goDisabled,
    },
  }
}

/** What one call answers — the shape the busk view threads to its Show tab, and `useRunnerDisplay` reads. */
export type ShowBarState = ReturnType<typeof useShowBarProps>
