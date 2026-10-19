import { useCallback } from 'react'
import { usePauseFxMutation, useRemoveFxMutation, useResumeFxMutation } from '@/store/fixtureFx'
import type { ActiveEffect } from '@/store/fixtureFx'
import { usePauseGroupFxMutation, useRemoveGroupFxMutation, useResumeGroupFxMutation } from '@/store/groups'

/**
 * Pause or resume one running effect — a group's through the group routes, anything else by its
 * target key. The live editor and the applied tables both press it, so a row and the editor beside
 * it cannot send two different requests. A refusal is reported by `errorToastMiddleware`; the
 * `.catch` only stops the unhandled rejection.
 */
export function useToggleFxPause(): (effect: ActiveEffect) => void {
  const [pauseFx] = usePauseFxMutation()
  const [resumeFx] = useResumeFxMutation()
  const [pauseGroupFx] = usePauseGroupFxMutation()
  const [resumeGroupFx] = useResumeGroupFxMutation()
  return useCallback(
    (effect: ActiveEffect) => {
      const request = effect.isGroupTarget
        ? (effect.isRunning ? pauseGroupFx : resumeGroupFx)({ id: effect.id, groupName: effect.targetKey })
        : (effect.isRunning ? pauseFx : resumeFx)({ id: effect.id, fixtureKey: effect.targetKey })
      request.unwrap().catch(() => {})
    },
    [pauseFx, pauseGroupFx, resumeFx, resumeGroupFx],
  )
}

/** Stop one running effect — the FX table's *Stop*, as `useToggleFxPause` is its pause. */
export function useStopEffect(): (effect: ActiveEffect) => void {
  const [removeFx] = useRemoveFxMutation()
  const [removeGroupFx] = useRemoveGroupFxMutation()
  return useCallback(
    (effect: ActiveEffect) => {
      const request = effect.isGroupTarget
        ? removeGroupFx({ id: effect.id, groupName: effect.targetKey })
        : removeFx({ id: effect.id, fixtureKey: effect.targetKey })
      request.unwrap().catch(() => {})
    },
    [removeFx, removeGroupFx],
  )
}
