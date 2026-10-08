import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { useEditorForm } from './EditorSurface'

/**
 * The popover's first line: what the edit lands on, and which column it is (editor-kit plan D8).
 *
 * *4 heads · Local* on the left — the batch and the scope, or the Look's name in layer scope, which
 * no editor said before — and the column on the right. It replaced *Applying to N targets*, which
 * was drawn only for a batch, said nothing about where the value would land, and left the popover
 * nameless: a popover has no header, so this is the one place its column is written.
 *
 * **Drawn in the popover form only.** Both sheet forms keep their title row — a Radix dialog needs
 * a title, and it already names the column — so a label line there would say the column twice and
 * the count where the band above the grid already says it. Read off the same shared media store
 * `EditorSurface` decides the form from, so the line and the surface cannot disagree; a
 * subscription per *open* editor, since this mounts inside the content and not per cell.
 */
export function EditorLabelLine({
  subject,
  column,
  className,
  docked = false,
  title,
  chip,
}: {
  subject: string
  column: string
  /** The line's own inset where the host's content is not padded — the two list editors. It goes
   *  with the line, so a sheet form draws no empty band where the line is not drawn. */
  className?: string
  /**
   * A docked host — the programmer rail's Colour and Spread tabs (editor-kit plan session 4, call
   * 11) — draws the line whatever form the viewport would give a cell editor: the tab is a column
   * away from row C and the scope band, where a popover sits on the cell it edits. The busk tabs
   * draw none, since their band is one row up.
   */
  docked?: boolean
  /** The subject's hover — the refusal's full sentence where the subject is a scope's short word. */
  title?: string
  /**
   * The programmer grid's **source chip** (fixture-fx-sheets plan D19, `CellSourceChip`): on the
   * line's right side, after the column, in the popover form — and, since both sheet forms draw no
   * line, as a row of its own under the sheet's title there, so a phone reaches the stack too.
   */
  chip?: ReactNode
}) {
  const form = useEditorForm()
  if (!docked && form !== 'popover') {
    return chip == null ? null : (
      <div data-editor-source-row className={cn('flex min-w-0 items-center gap-2', className)}>
        {chip}
      </div>
    )
  }
  return (
    <div
      data-editor-label-line
      className={cn('flex items-baseline justify-between gap-2 text-[10px] text-muted-foreground', className)}
    >
      <span className="min-w-0 truncate" title={title}>
        {subject}
      </span>
      <span className="flex shrink-0 items-center gap-1.5">
        {column}
        {chip}
      </span>
    </div>
  )
}
