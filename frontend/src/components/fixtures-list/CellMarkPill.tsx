import { Layers, Palette } from 'lucide-react'
import { cn } from '@/lib/utils'
import { referencePillText } from './ownership'
import type { CellReference } from './useRowOwnership'

/** The pill's shape, shared by both kinds so neither reads louder than the other. */
const PILL_CLASS =
  'pointer-events-none inline-flex h-5 min-w-0 max-w-[60%] shrink items-center gap-1 rounded-full px-[7px] text-[10px] font-semibold whitespace-nowrap'

/**
 * A palette reference's pill (palette-references plan D18, D20): the `Palette` glyph and the
 * palette's name, never a short code; over part of a row how many heads (*DSR Focus · 2 of 4*);
 * dashed while a reference no longer resolves (D3). The name comes from the frame where it says one,
 * else from the project's library by uuid ([nameOf]).
 */
export function ReferencePill({
  reference,
  nameOf,
}: {
  reference: CellReference
  nameOf: (uuid: string) => string | undefined
}) {
  const name = reference.name ?? (reference.paletteUuid != null ? nameOf(reference.paletteUuid) : undefined)
  const text = referencePillText(reference, name)
  return (
    <span
      data-reference-pill
      data-unresolved={reference.unresolved != null || undefined}
      className={cn(
        PILL_CLASS,
        'bg-teal-400/15 text-teal-700 dark:text-teal-300',
        reference.unresolved != null && 'border border-dashed border-teal-400/70',
      )}
    >
      <Palette aria-hidden className="size-[11px] shrink-0" />
      <span className="truncate">{text}</span>
    </span>
  )
}

/** A Look layer's pill (D19): the `Layers` glyph and the Look's name, in the layer's own slate. */
export function LookLayerPill({ name }: { name: string }) {
  return (
    <span data-look-layer-pill className={cn(PILL_CLASS, 'bg-slate-400/15 text-slate-600 dark:text-slate-300')}>
      <Layers aria-hidden className="size-[11px] shrink-0" />
      <span className="truncate">{name}</span>
    </span>
  )
}
