import { useCallback } from 'react'
import { formatError } from '@/lib/formatError'
import { BatchDeleteDialog } from '@/components/sheet/BatchDeleteDialog'
import { useBatchDelete, type DeleteOutcome } from '@/components/sheet/useBatchDelete'
import { useDeleteTemplateMutation } from '@/store/templates'
import type { TemplateInUseError, TemplateSummary } from '@/api/templatesApi'

/**
 * What the batch-delete dialog knows about one template it held back: the desk's `TEMPLATE_IN_USE`
 * body, or nothing but its pads (`body: null`) — `useLookDelete`'s rule, for its reason.
 */
export interface TemplateInUse {
  body: TemplateInUseError | null
  buskPageCount: number
}

/**
 * What uses a template, in the dialog's one line — the **three** kinds the desk counts, each stated
 * because each fails differently under a forced delete, plus the pads:
 *
 * - **cue layers** are removed with it — those cues fire without its contribution;
 * - **effect parameters** naming it (`fxReferenceCount`) are *not* rewritten, so they point at
 *   nothing, and an unresolved colour runs as **white** — the one consequence worth repointing first;
 * - **programmer layers** applying it right now (`runningCount`) stop on the rig at once — the only
 *   usage that is not stored, and without it a template used only live would list no consequence.
 */
export function describeTemplateInUse(summary: TemplateInUse): string {
  const parts: string[] = []
  const body = summary.body
  if (body != null) {
    if (body.layerCount > 0) {
      parts.push(
        `${body.layerCount} cue layer${body.layerCount === 1 ? '' : 's'}${body.cueNames.length > 0 ? ` — ${body.cueNames.join(', ')}` : ''}`,
      )
    }
    const fx = body.fxReferenceCount ?? 0
    if (fx > 0) parts.push(`named by ${fx} effect parameter${fx === 1 ? '' : 's'}, which would run as white`)
    const running = body.runningCount ?? 0
    if (running > 0) parts.push(`${running} programmer layer${running === 1 ? '' : 's'} applying it now, stopped at once`)
  }
  if (summary.buskPageCount > 0) {
    parts.push(`on ${summary.buskPageCount} busk page${summary.buskPageCount === 1 ? '' : 's'}`)
  }
  return parts.join(' · ')
}

/**
 * The template delete on `useBatchDelete` (library-sheets plan D13) — the sheet's batch and
 * `TemplateEditor`'s Delete, one question in one dialog. It replaced the route's confirm +
 * `TEMPLATE_IN_USE` guard pair.
 *
 * A template with **busk pads** is held for the question without being sent, as a Look is, and its
 * *Delete anyway* is sent plain, forcing only once the desk has named its uses (`useLookDelete`
 * says why). For a template that matters most: a force would also leave effect parameters naming
 * it running as white and stop the programmer layers applying it.
 * Every other refusal is toasted by the hook; `deleteTemplate` stays in `SILENT_ENDPOINTS` (D14).
 */
export function useTemplateDelete({
  projectId,
  onDeleted,
  onKept,
}: {
  projectId: number
  onDeleted?: (templates: TemplateSummary[]) => void
  onKept?: (templates: TemplateSummary[]) => void
}) {
  const [deleteTemplate] = useDeleteTemplateMutation()
  const remove = useCallback(
    async (template: TemplateSummary, force: boolean, previous?: TemplateInUse): Promise<DeleteOutcome<TemplateInUse>> => {
      // Pads alone are held back unsent on the plain pass — the desk would take the record and the
      // pads would go unannounced. On *Delete anyway* a record held that way is sent **plain**,
      // never forced: pads were the only use the dialog could name, and forcing now would take
      // any cue layer (or effect reference, or running layer) with it unsaid. If the desk answers
      // in use, the dialog asks again with its answer, and only that answer's *Delete anyway*
      // forces.
      if (!force && template.buskPageCount > 0) {
        return { kind: 'inUse', summary: { body: null, buskPageCount: template.buskPageCount } }
      }
      // Forced only when the desk has already said what uses it.
      const sendForced = force && previous?.body != null
      try {
        await deleteTemplate({ projectId, templateId: template.id, force: sendForced }).unwrap()
        return { kind: 'ok' }
      } catch (err) {
        const body = (err as { data?: TemplateInUseError } | null)?.data
        if (body?.code === 'TEMPLATE_IN_USE') return { kind: 'inUse', summary: { body, buskPageCount: template.buskPageCount } }
        return { kind: 'refused', reason: formatError(err) }
      }
    },
    [deleteTemplate, projectId],
  )
  const batch = useBatchDelete<TemplateSummary, TemplateInUse>({
    remove,
    name: templateName,
    noun: 'template',
    onDeleted,
    onKept,
    toastKey: 'templates',
  })
  const dialog = (
    <BatchDeleteDialog<TemplateSummary, TemplateInUse>
      state={batch.state}
      noun="template"
      name={templateName}
      describe={(_template, summary) => describeTemplateInUse(summary)}
      consequence="deleting them anyway removes what uses them — a layer applying one goes, the programmer stops it, an effect naming it points at nothing (repoint that first), a pad leaves its busk page:"
      busy={batch.busy}
      onForce={batch.force}
      onKeep={batch.keep}
    />
  )
  return { run: batch.run, busy: batch.busy, dialog }
}

function templateName(template: TemplateSummary): string {
  return template.name
}
