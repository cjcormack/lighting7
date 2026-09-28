import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * A panel's static footer: the save first, a note, a spacer, then the verbs (editor-kit plan D9).
 *
 * The shape the busk Colour and Spread tabs' footers already have — *Save as template… · Pick ·
 * Spread…*, *Save as Look… · Live · Apply* — generalised. A footer is drawn where a panel has
 * **verbs beside the value**: Apply on the text and address editors, where a half-typed name is not
 * a value anyone wants written; the colour editor's Save, Pick and Spread…, which are gestures
 * about the batch and not writes of it (that editor writes as it goes, and its footer writes
 * nothing). Never the level, position or setting editors, which write as they go and carry no verb.
 *
 * [save] is a slot rather than a button so a host can say it has none: the programmer's panels
 * carry no save, because Record is one row up and is how every programmer state is kept (D11).
 * [note] is the short muted sentence beside it — the address editor's *consecutive by footprint*.
 * The rule and the top padding are the footer's; a panel that docks it full-bleed says so at its
 * own import — the busk Spread tab with its `px-3 py-2`, and `ColourEditor` for its docked host.
 */
export function EditorFooter({
  save,
  note,
  children,
  className,
}: {
  save?: ReactNode
  note?: ReactNode
  /** The verbs, right-aligned: Apply, or Live and Apply. */
  children?: ReactNode
  className?: string
}) {
  return (
    <div data-editor-footer className={cn('flex items-center gap-1.5 border-t pt-2', className)}>
      {save}
      {note != null && <span className="min-w-0 truncate text-[10px] text-muted-foreground">{note}</span>}
      <span className="flex-1" />
      {children}
    </div>
  )
}
