import { cn } from '@/lib/utils'

/**
 * The desk's one section label — the design canvases' `.lbl`, and the editor kit's label over
 * every control (editor-kit plan D9).
 *
 * 9px, bold, uppercase, wide-tracked, muted, and **no icon**. It was `BuskLabel`, the busk view's
 * region label: every region on that surface is titled this way, and that uniformity is the point
 * rather than an economy — the pools and the cue column used to draw a larger icon-bearing heading
 * while the target band and the speed rail drew this one, which made three regions of one
 * instrument read as three surfaces. The editor kit takes it as the label over a field, a slider
 * or a segmented row, so a cell editor on the programmer and a tab on the busk sheet cannot title
 * a control two ways.
 *
 * A `<div>` on purpose, so a test can reach a region's body by walking up from its label; a
 * `<span>` would make such a walk skip the region root.
 */
export function EditorLabel({
  className,
  children,
}: {
  className?: string
  children: React.ReactNode
}) {
  return <div className={cn(EDITOR_LABEL_CLASS, className)}>{children}</div>
}

/**
 * The same classes as a bare string, for the places that need them on an element they already
 * compose — the speed rail's card title, which wraps a `BeatIndicator` and a usage badge in one row.
 */
export const EDITOR_LABEL_CLASS =
  'text-[9px] font-bold uppercase tracking-[0.08em] text-muted-foreground'
