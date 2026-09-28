import { useCallback } from 'react'
import { formatError } from '@/lib/formatError'
import { BatchDeleteDialog } from '@/components/sheet/BatchDeleteDialog'
import { useBatchDelete, type DeleteOutcome } from '@/components/sheet/useBatchDelete'
import { useDeleteProjectScriptMutation } from '@/store/projects'
import type { ProjectScriptDetail } from '@/api/projectApi'

/** What the dialog says about a script it held back: the effects it registers, by name. */
export interface ScriptInUse {
  registers: readonly string[]
}

/**
 * The script delete on `useBatchDelete` (library-sheets plan D13) — one hook for the Scripts
 * sheet's batch **and** `ScriptForm`'s Delete, so the two ask one question in one dialog.
 *
 * **The desk has no in-use refusal for a script** — `DELETE …/scripts/{id}` takes any script of the
 * running project — so the one use this side can name is held back **without sending**, the way a
 * Look's busk pads are: an `FX_DEFINITION` script whose effects are in the library
 * ([registeredBy], the Scripts sheet's Used by). Deleting it does not unregister them; they stay in
 * the library until the desk restarts, and then vanish from under whatever names them. *Delete
 * anyway* sends it plain — there is no `force` to send. Every other script deletes on the plain
 * pass, as an unused Look does. What an `FX_APPLICATION` script's cue hooks are is not on any list
 * the client reads (`FU-SCRIPT-USED-BY`), so they cannot be named here yet.
 *
 * Every refusal is toasted by the hook; `deleteProjectScript` is in `SILENT_ENDPOINTS` (D14).
 */
export function useScriptDelete({
  projectId,
  registeredBy,
  onDeleted,
  onKept,
}: {
  projectId: number
  /** The effects a script registers — names, empty for anything but a live `FX_DEFINITION`. */
  registeredBy: (script: ProjectScriptDetail) => readonly string[]
  onDeleted?: (scripts: ProjectScriptDetail[]) => void
  onKept?: (scripts: ProjectScriptDetail[]) => void
}) {
  const [deleteScript] = useDeleteProjectScriptMutation()
  const remove = useCallback(
    async (script: ProjectScriptDetail, force: boolean): Promise<DeleteOutcome<ScriptInUse>> => {
      const registers = registeredBy(script)
      if (!force && registers.length > 0) return { kind: 'inUse', summary: { registers } }
      try {
        await deleteScript({ projectId, scriptId: script.id }).unwrap()
        return { kind: 'ok' }
      } catch (err) {
        return { kind: 'refused', reason: formatError(err) }
      }
    },
    [deleteScript, projectId, registeredBy],
  )
  const batch = useBatchDelete<ProjectScriptDetail, ScriptInUse>({
    remove,
    name: scriptName,
    noun: 'script',
    onDeleted,
    onKept,
    toastKey: 'scripts',
  })
  const dialog = (
    <BatchDeleteDialog<ProjectScriptDetail, ScriptInUse>
      state={batch.state}
      noun="script"
      name={scriptName}
      describe={(_script, summary) => `registers ${summary.registers.join(', ')}`}
      consequence="deleting them anyway leaves the effects they registered in the library only until the desk restarts, and then anything naming one stops resolving:"
      busy={batch.busy}
      onForce={batch.force}
      onKeep={batch.keep}
    />
  )
  return { run: batch.run, busy: batch.busy, dialog }
}

function scriptName(script: ProjectScriptDetail): string {
  return script.name
}
