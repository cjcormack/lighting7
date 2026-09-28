import { useCallback } from 'react'
import { formatError } from '@/lib/formatError'
import { useBatchDelete, type DeleteOutcome } from '@/components/sheet/useBatchDelete'
import { useDeleteFxDefinitionMutation, type FxDefinition } from '@/store/fxDefinitions'
import type { FxSource } from './fxLibraryModel'

/** One FX Library row as the delete sees it — the definition only a custom row has. */
export interface FxDeleteItem {
  name: string
  source: FxSource
  definition?: FxDefinition
}

/**
 * Why a row is not sent at all (library-sheets plan D13): **only a custom definition deletes here.**
 * A built-in is the desk's, and a script-registered effect is its script's — deleting it means
 * editing or deleting that script, which is the Scripts page's.
 */
export function fxDeleteSkipReason(item: FxDeleteItem): string | null {
  if (item.source === 'builtIn') return 'built-in effects cannot be deleted'
  if (item.source === 'script') return 'a script registers it — edit or delete the script'
  if (item.definition == null) return 'its definition is not loaded yet'
  return null
}

/**
 * The FX definition delete on `useBatchDelete` (D13) — one hook for the sheet's batch **and**
 * `EditFxDefinitionSheet`'s Delete, so the two report one way. Built-ins and script-registered rows
 * are skipped by name before anything is sent ([fxDeleteSkipReason]).
 *
 * **No dialog.** The desk keeps no record of what uses a definition — a template's effect, a cue
 * effect, a running instance all name it by `effectId` — so `DELETE fx/definitions/{id}` has no
 * in-use refusal to gather, and every other refusal is toasted by the hook. `deleteFxDefinition` is
 * in `SILENT_ENDPOINTS` for that reason (D14): the middleware would say it a second time.
 */
export function useFxDefinitionDelete({
  onDeleted,
}: {
  onDeleted?: (items: FxDeleteItem[]) => void
}) {
  const [deleteDefinition] = useDeleteFxDefinitionMutation()
  const remove = useCallback(
    async (item: FxDeleteItem): Promise<DeleteOutcome<never>> => {
      // `skip` has already dropped anything without a definition; this is the type's guard.
      if (item.definition == null) return { kind: 'refused', reason: 'not a custom definition' }
      try {
        await deleteDefinition(item.definition.id).unwrap()
        return { kind: 'ok' }
      } catch (err) {
        return { kind: 'refused', reason: formatError(err) }
      }
    },
    [deleteDefinition],
  )
  const batch = useBatchDelete<FxDeleteItem, never>({
    remove,
    name: fxItemName,
    skip: fxDeleteSkipReason,
    noun: 'effect',
    onDeleted,
    toastKey: 'fx-library',
  })
  return { run: batch.run, busy: batch.busy }
}

function fxItemName(item: FxDeleteItem): string {
  return item.name
}
