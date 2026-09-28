import { useCallback, useState } from 'react'
import { toast } from 'sonner'
import { formatError } from '@/lib/formatError'
import { useIncludeIntoProgrammerMutation } from '@/store/programmerOps'
import type { IncludeResponse, PropertyMaskGroup } from '@/store/programmerOps'
import { publishIncludeSelection } from '@/store/includeSelection'

export interface UseIncludeOptions {
  /**
   * Raise a toast when the Include fails. Callers that render the returned `error` inline
   * (the picker sheet) pass false; callers with nowhere to put it (the Show view's
   * per-cue button) leave it on, or a failed Include is completely silent.
   */
  toastErrors?: boolean
}

/**
 * What to load into the programmer. A discriminated union rather than optional ids: the backend
 * accepts exactly one of `cueId` / `lookId` and 400s otherwise, and
 * `ProgrammerStore.lastIncludedTarget` is single-valued, so "both" has no meaning to express.
 */
export type IncludeTargetRequest =
  | { kind: 'CUE'; cueId: number }
  | { kind: 'LOOK'; lookId: number }

/**
 * Include a cue *or a Look* into the programmer and hand its fixtures to the sheet's selection.
 *
 * Shared by the action bar's picker and the Show view's per-cue Include, so the auto-select and
 * the warning surfacing can't drift between the entry points.
 *
 * The two kinds differ in what they stage, and the difference is the point: including a **cue**
 * writes reference slots for its reference rows, so an untouched reference survives the Update
 * round trip; including a **Look** writes plain literals, because you are looking at that Look's
 * own contents and a slot referencing the thing it describes would mean nothing. Both of those are
 * the backend's call — this hook just names the target.
 *
 * A Look include is **not** one-way, though it was: `updateIncludedLook` MERGEs whatever changed
 * since Include into the Look's own rows, so Include → edit → Update is a round trip for a Look
 * exactly as it is for a cue. It was disabled while the only write-back path led into the retired
 * palette tables.
 */
export function useInclude(projectId: number, options: UseIncludeOptions = {}) {
  const { toastErrors = true } = options
  const [includeIntoProgrammer, { isLoading, error, reset }] = useIncludeIntoProgrammerMutation()
  const [result, setResult] = useState<IncludeResponse | null>(null)

  const include = useCallback(
    async (target: IncludeTargetRequest, mask?: PropertyMaskGroup[]) => {
      try {
        const response = await includeIntoProgrammer({
          projectId,
          ...(target.kind === 'CUE' ? { cueId: target.cueId } : { lookId: target.lookId }),
          mask: mask && mask.length > 0 ? mask : undefined,
        }).unwrap()
        setResult(response)
        // "Select Heads on Include": the operator's next gesture is almost always to edit
        // exactly these fixtures.
        publishIncludeSelection(response.fixtureKeys, response.groupKeys)
        // Warnings here are advisory (an open cue-edit session, an empty cue) rather than
        // failures, so they toast instead of blocking the sheet.
        for (const warning of response.warnings) toast.warning(warning)
        return response
      } catch (err) {
        // The mutation's `error` state carries this for callers that render it inline;
        // swallowing the rejection here keeps it out of the console as unhandled.
        if (toastErrors) toast.error(formatError(err))
        return null
      }
    },
    [includeIntoProgrammer, projectId, toastErrors],
  )

  const includeCue = useCallback(
    (cueId: number, mask?: PropertyMaskGroup[]) => include({ kind: 'CUE', cueId }, mask),
    [include],
  )

  const resetInclude = useCallback(() => {
    setResult(null)
    reset()
  }, [reset])

  return { include, includeCue, isLoading, error, result, resetInclude }
}
