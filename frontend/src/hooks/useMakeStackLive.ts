import { useCallback } from 'react'
import { useDispatch } from 'react-redux'
import type { CueStack } from '../api/cueStacksApi'
import { useDeactivateCueStackMutation, useGoToStackMutation } from '../store/cueStacks'
import { resetStack } from '../store/runnerSlice'
import type { ShowBarState } from './useShowBarProps'

/**
 * Move the playhead to a stack the operator is reading — `OffPlayheadBanner`'s *Make live*.
 *
 * Lifted out of `ShowPage` when the busk view's Show tab (`components/busking/ShowTab.tsx`) gained
 * the phone runner's stack picker, so that the two surfaces cannot arm a stack two different ways.
 * Until session 2b this was what a tab click did silently, which meant one unconfirmed press took
 * the live cue off stage and repositioned every other client. Three things to know:
 *
 *  - **No client-side deactivate of the stack being left.** `POST /show/go-to` already calls
 *    `deactivateStack(previous)` server-side (`routes/projectShow.kt`).
 *  - **`go-to` fires the target's first cue** (`activateAtFirstCue`), so the desk darkens it again
 *    to arrive armed rather than playing — a real, brief blip, which is why `OffPlayheadBanner`
 *    confirms first when something is live.
 *  - **The runner is reset explicitly.** Between `go-to` resolving and the deactivate landing the
 *    server reports the freshly-activated first cue, and a reset reading that would mark cue 1 as
 *    already run.
 */
export function useMakeStackLive(
  projectId: number,
  activeStackId: number | null,
  transport: Pick<ShowBarState['transport'], 'cancelAnimations'>,
): (target: CueStack) => void {
  const dispatch = useDispatch()
  const [goToStack] = useGoToStackMutation()
  const [deactivateCueStack] = useDeactivateCueStackMutation()
  const { cancelAnimations } = transport
  return useCallback(
    (target: CueStack) => {
      if (target.type !== 'STACK' || target.id === activeStackId) return
      cancelAnimations()
      goToStack({ projectId, stackId: target.id })
        .unwrap()
        .then(() => {
          deactivateCueStack({ projectId, stackId: target.id })
          dispatch(
            resetStack({
              stackId: target.id,
              cues: target.cues,
              serverActiveCueId: null,
              serverNextCueId: null,
              loop: target.loop,
            }),
          )
        })
        .catch(() => {
          // Reported by errorToastMiddleware; caught here only to stop the unhandled rejection.
        })
    },
    [activeStackId, projectId, cancelAnimations, goToStack, deactivateCueStack, dispatch],
  )
}
