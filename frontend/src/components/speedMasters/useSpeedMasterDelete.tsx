import { useCallback } from 'react'
import { formatError } from '@/lib/formatError'
import { BatchDeleteDialog } from '@/components/sheet/BatchDeleteDialog'
import { useBatchDelete, type DeleteOutcome } from '@/components/sheet/useBatchDelete'
import {
  CODE_SPEED_MASTER_IN_USE,
  type SpeedMaster,
  type SpeedMasterInUseResponse,
} from '../../api/speedMastersApi'
import { useDeleteSpeedMasterMutation } from '../../store/speedMasters'

/** Why master 1 is never sent — the global tempo every unassigned effect resolves to. */
export const MASTER_1_DELETE_REFUSAL = 'Master 1 is the global tempo — every unassigned effect resolves to it'

/**
 * What uses a master, for the in-use dialog — the counts the desk answered and, since the plan's
 * session 1, **the masters that follow it, by name** (`followerNames`): the one reference an
 * operator can act on directly, and the one a forced delete changes most visibly.
 */
export function describeSpeedMasterInUse(summary: SpeedMasterInUseResponse): string {
  const parts: string[] = []
  const counted = summary.referenceCount - (summary.followerNames?.length ?? 0)
  if (summary.lookEffectCount > 0) parts.push(`${summary.lookEffectCount} look effect${summary.lookEffectCount === 1 ? '' : 's'}`)
  if (summary.cueAdHocEffectCount > 0) {
    parts.push(`${summary.cueAdHocEffectCount} cue effect${summary.cueAdHocEffectCount === 1 ? '' : 's'}`)
  }
  if (summary.cueLayerCount > 0) parts.push(`${summary.cueLayerCount} cue layer${summary.cueLayerCount === 1 ? '' : 's'}`)
  if (parts.length === 0 && counted > 0) parts.push(`${counted} reference${counted === 1 ? '' : 's'}`)
  let text = parts.join(' · ')
  if (summary.cueIds.length > 0) text += ` (cues ${summary.cueIds.join(', ')})`
  const followers = summary.followerNames ?? []
  if (followers.length > 0) text += `${text ? ' · ' : ''}followed by ${followers.join(', ')}`
  return text
}

/**
 * The speed-master delete on `useBatchDelete` (library-sheets plan D13) — one hook for the sheet's
 * batch **and** `SpeedMasterDetailSheet`'s single delete, so the two ask the same question in the
 * same dialog. Master 1 is skipped by name before anything is sent; `SPEED_MASTER_IN_USE` is the
 * in-use refusal the dialog asks about; everything else (a stale client reaching master 1's
 * `SPEED_MASTER_PROTECTED`) is toasted by the hook. The endpoint stays in `SILENT_ENDPOINTS`.
 */
export function useSpeedMasterDelete({
  projectId,
  onDeleted,
  onKept,
}: {
  projectId: number
  onDeleted?: (masters: SpeedMaster[]) => void
  onKept?: (masters: SpeedMaster[]) => void
}) {
  const [deleteMaster] = useDeleteSpeedMasterMutation()
  const remove = useCallback(
    async (master: SpeedMaster, force: boolean): Promise<DeleteOutcome<SpeedMasterInUseResponse>> => {
      try {
        await deleteMaster({ projectId, masterId: master.id, force }).unwrap()
        return { kind: 'ok' }
      } catch (err) {
        const body = (err as { data?: SpeedMasterInUseResponse } | null)?.data
        if (body?.code === CODE_SPEED_MASTER_IN_USE) return { kind: 'inUse', summary: body }
        return { kind: 'refused', reason: formatError(err) }
      }
    },
    [deleteMaster, projectId],
  )
  const batch = useBatchDelete<SpeedMaster, SpeedMasterInUseResponse>({
    remove,
    name: masterLabel,
    skip: (master) => (master.masterIndex === 1 ? MASTER_1_DELETE_REFUSAL : null),
    noun: 'master',
    onDeleted,
    onKept,
    toastKey: 'speed-masters',
  })
  const dialog = (
    <BatchDeleteDialog<SpeedMaster, SpeedMasterInUseResponse>
      state={batch.state}
      noun="master"
      name={masterLabel}
      describe={(_master, summary) => describeSpeedMasterInUse(summary)}
      consequence="deleting them anyway leaves what uses them on master 1, the global tempo, and unlinks their followers, which then run manually:"
      busy={batch.busy}
      onForce={batch.force}
      onKeep={batch.keep}
    />
  )
  return { run: batch.run, busy: batch.busy, dialog }
}

/** `M2 · Colour chase` — a master in a toast or the dialog, by the name the sheet shows. */
export function masterLabel(master: SpeedMaster): string {
  return `M${master.masterIndex} · ${master.name}`
}
