import { useCallback } from 'react'
import { toast } from 'sonner'
import { useCurrentProjectQuery } from '@/store/projects'
import { useClearLocateMutation, useStoreLocateMutation, type LocateTarget } from '@/store/locate'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { storedLocateMessage } from '@/lib/storedLocate'

/** One toast id for both gestures, so a second press replaces the first's answer. */
const TOAST_ID = 'stored-locate'

/**
 * *Set locate from current* and *Clear locate* (venue-visit plan D6) for the programmer and the
 * patch sheet: one REST call each on the **current** project, whose live output the store reads.
 * The answer is toasted here — how many fixtures it changed and every unit it skipped, by the desk's
 * reason; a refusal is the error middleware's. `projectId` is the current project's, or undefined
 * while it loads — the gestures do nothing then.
 */
export function useStoredLocateActions() {
  const { data: current } = useCurrentProjectQuery()
  const projectId = current?.id
  const [store, storeState] = useStoreLocateMutation()
  const [clear, clearState] = useClearLocateMutation()

  const storeFromCurrent = useCallback(
    (targets: readonly LocateTarget[]) => {
      if (projectId == null || targets.length === 0) return
      store({ projectId, targets: [...targets] })
        .unwrap()
        .then((response) => {
          const { ok, message } = storedLocateMessage(response, 'stored')
          if (ok) toast.success(message, { id: TOAST_ID })
          else toast.warning(message, { id: TOAST_ID })
        })
        .catch(ignoreReportedError)
    },
    [projectId, store],
  )

  const clearLocate = useCallback(
    (targets: readonly LocateTarget[]) => {
      if (projectId == null || targets.length === 0) return
      clear({ projectId, targets: [...targets] })
        .unwrap()
        .then((response) => {
          const { ok, message } = storedLocateMessage(response, 'cleared')
          if (ok) toast.success(message, { id: TOAST_ID })
          else toast.warning(message, { id: TOAST_ID })
        })
        .catch(ignoreReportedError)
    },
    [projectId, clear],
  )

  return {
    projectId,
    storeFromCurrent,
    clearLocate,
    busy: storeState.isLoading || clearState.isLoading,
  }
}
