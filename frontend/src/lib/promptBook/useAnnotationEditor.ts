import { useCallback, useState } from 'react'
import type { AnnotationDto, AnnotationKind, NoteTone, Region } from '../../api/promptBooksApi'
import {
  useCreateAnnotationMutation,
  useUpdateAnnotationMutation,
  useDeleteAnnotationMutation,
} from '../../store/promptBooks'

type AnnotationDialogState =
  | { mode: 'create'; kind: AnnotationKind; region: Region }
  | { mode: 'edit'; annotation: AnnotationDto }

/**
 * The note / freetext / cut editor: one dialog state plus the three mutations behind it.
 *
 * The one rule worth stating is the fast path. A **strikethrough has nothing to edit** — the region
 * *is* the annotation — so creating one skips the form entirely and writes straight through, while
 * clicking an existing one opens a delete confirmation rather than a form. That is why the same
 * `annotationDialog` slot drives two different surfaces (`sheetOpen` for the form, `cutConfirmOpen`
 * for the confirmation) and why both flags are derived here rather than re-derived in the JSX.
 */
export function useAnnotationEditor(projectId: number) {
  const [createAnnotation] = useCreateAnnotationMutation()
  const [updateAnnotation] = useUpdateAnnotationMutation()
  const [deleteAnnotation] = useDeleteAnnotationMutation()

  const [dialog, setDialog] = useState<AnnotationDialogState | null>(null)
  const [text, setText] = useState('')
  const [tone, setTone] = useState<NoteTone>('NOTE')

  const create = useCallback(
    (kind: AnnotationKind, region: Region) => {
      if (kind === 'STRIKETHROUGH') {
        createAnnotation({ projectId, kind, region })
        return
      }
      setText('')
      setTone('NOTE')
      setDialog({ mode: 'create', kind, region })
    },
    [createAnnotation, projectId],
  )

  const open = useCallback((annotation: AnnotationDto) => {
    setText(annotation.text ?? '')
    setTone(annotation.tone ?? 'NOTE')
    setDialog({ mode: 'edit', annotation })
  }, [])

  const close = useCallback(() => setDialog(null), [])

  const commit = useCallback(() => {
    if (!dialog) return
    if (dialog.mode === 'create') {
      createAnnotation({
        projectId,
        kind: dialog.kind,
        region: dialog.region,
        text: text || undefined,
        tone: dialog.kind === 'NOTE' ? tone : undefined,
      })
    } else {
      const { annotation } = dialog
      updateAnnotation({
        projectId,
        annotationId: annotation.id,
        kind: annotation.kind,
        region: annotation.region,
        text: text || undefined,
        color: annotation.color ?? undefined,
        tone: annotation.kind === 'NOTE' ? tone : undefined,
      })
    }
    setDialog(null)
  }, [dialog, text, tone, createAnnotation, updateAnnotation, projectId])

  const remove = useCallback(() => {
    if (dialog?.mode !== 'edit') return
    deleteAnnotation({ projectId, annotationId: dialog.annotation.id })
    setDialog(null)
  }, [dialog, deleteAnnotation, projectId])

  const kind =
    dialog == null ? null : dialog.mode === 'create' ? dialog.kind : dialog.annotation.kind
  const cutConfirmOpen = kind === 'STRIKETHROUGH'

  return {
    mode: dialog?.mode ?? null,
    kind,
    kindLabel: kind === 'NOTE' ? 'note' : 'freetext',
    sheetOpen: dialog != null && !cutConfirmOpen,
    cutConfirmOpen,
    text,
    setText,
    tone,
    setTone,
    create,
    open,
    close,
    commit,
    remove,
  }
}
