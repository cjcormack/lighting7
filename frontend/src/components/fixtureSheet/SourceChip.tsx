import { useState } from 'react'
import { AudioWaveform, Lock, Palette } from 'lucide-react'
import { cn } from '@/lib/utils'
import type { PickSource, RowSource, RowSourceKind } from './rowSource'
import { LayerStack } from './LayerStack'

/**
 * The row's 3px edge, in the programmer grid's colours (`fixtures-list/ownership.ts`): accent for
 * the programmer, violet for an effect, sky for a cue, amber for park, none for the base.
 */
export const SOURCE_EDGE_CLASS: Record<RowSourceKind, string> = {
  programmer: 'before:bg-primary',
  fx: 'before:bg-violet-500',
  cue: 'before:bg-sky-500',
  parked: 'before:bg-amber-500',
  base: 'before:bg-transparent',
}

/** A palette reference's edge (palette-references plan D18), in place of its owner's. */
export const REFERENCE_EDGE_CLASS = 'before:bg-teal-400'
export const REFERENCE_MIXED_EDGE_CLASS =
  'before:bg-[repeating-linear-gradient(to_bottom,var(--color-teal-400)_0_4px,transparent_4px_7px)]'

/** A row's 3px edge: its source's colour, a reference's teal, dashed over a pick whose heads disagree. */
export function sourceEdgeClass(source: RowSource, mixed = false): string {
  if (source.reference) return mixed ? REFERENCE_MIXED_EDGE_CLASS : REFERENCE_EDGE_CLASS
  return mixed ? MIXED_EDGE_CLASS[source.kind] : SOURCE_EDGE_CLASS[source.kind]
}

function chipClass(source: RowSource): string {
  if (source.reference) return 'border-teal-400/60 bg-teal-400/15 text-teal-700 dark:text-teal-300'
  switch (source.kind) {
    case 'programmer':
      // Filled once touched; an outline while it is only what Include brought in (Layers board).
      return source.touched
        ? 'border-primary/55 bg-primary/15 text-primary'
        : 'border-primary/55 text-primary'
    case 'fx':
      return 'border-violet-500/60 bg-violet-500/15 text-violet-600 dark:text-violet-300'
    case 'cue':
      return 'border-sky-500/50 bg-sky-500/10 text-sky-700 dark:text-sky-300'
    case 'parked':
      return 'border-amber-500/55 bg-amber-500/10 text-amber-700 dark:text-amber-400'
    case 'base':
      return 'border-dashed border-border text-muted-foreground'
  }
}

/**
 * The row's 3px edge over a pick whose heads disagree on their source (D13): the shown source's
 * colour, dashed — a repeating gradient in place of the solid fill.
 */
export const MIXED_EDGE_CLASS: Record<RowSourceKind, string> = {
  programmer: 'before:bg-[repeating-linear-gradient(to_bottom,var(--color-primary)_0_4px,transparent_4px_7px)]',
  fx: 'before:bg-[repeating-linear-gradient(to_bottom,var(--color-violet-500)_0_4px,transparent_4px_7px)]',
  cue: 'before:bg-[repeating-linear-gradient(to_bottom,var(--color-sky-500)_0_4px,transparent_4px_7px)]',
  parked: 'before:bg-[repeating-linear-gradient(to_bottom,var(--color-amber-500)_0_4px,transparent_4px_7px)]',
  base: 'before:bg-[repeating-linear-gradient(to_bottom,var(--color-border)_0_4px,transparent_4px_7px)]',
}

/**
 * The row's source chip (D4) — and its door to the property's stack (D5): a press opens
 * `LayerStack` through `EditorSurface`. An amber dot rides it while something underneath is held
 * back; a dashed amber *Staged* mark beside it while the programmer is blind and holds the row (the
 * chip names what is on stage, the row's control shows what leaving Blind would land).
 */
export function SourceChip({
  source,
  heads,
  group,
  attribute,
  label,
  cueLabel,
  connected,
}: {
  /** The row's source; over a pick, with how many heads it holds (D13). */
  source: RowSource | PickSource
  /** The heads the row writes — the stack is asked of them. One, for a row on one head. */
  heads: readonly { key: string; name: string }[]
  /** A group's *All*: the stack is asked of the group. */
  group?: string
  /** The row's first key: the property the stack is asked about. */
  attribute: string
  /** The row's name, for the stack's title. */
  label: string
  /** Outside a fixture sheet (the grid's cell editor), what its context would carry — see `LayerStack`. */
  cueLabel?: (cueId: number) => string | undefined
  connected?: boolean
}) {
  const [open, setOpen] = useState(false)
  // *Programmer · 2 of 4* — how many of the picked heads the shown source holds (note 4).
  const count = 'mixed' in source && source.mixed ? `${source.count} of ${source.of}` : null
  const words = count ? `${source.label} · ${count}` : source.label
  // Whose the reference is, as the grid's hover says it (D18): *Yours: DSR Focus*.
  const named = source.reference
    ? `${source.kind === 'programmer' ? 'Yours' : 'Cue'}: ${words}${source.reference.unresolved ? ` · ${source.reference.unresolved}` : ''}`
    : words
  return (
    <span className="flex min-w-0 items-center gap-1">
      <LayerStack
        open={open}
        onOpenChange={setOpen}
        heads={heads}
        group={group}
        attribute={attribute}
        label={label}
        cueLabel={cueLabel}
        connected={connected}
        trigger={
          <button
            type="button"
            data-source={source.kind}
            data-held-back={source.heldBack || undefined}
            data-mixed={count != null || undefined}
            data-reference={source.reference != null || undefined}
            title={source.heldBack ? `${named} — something underneath is held back` : `${named} — show what is underneath`}
            className={cn(
              'relative inline-flex h-[18px] min-w-0 max-w-full items-center gap-1 rounded-full border px-[7px] text-[10px] font-medium whitespace-nowrap',
              chipClass(source),
              (count != null || source.reference?.unresolved != null) && 'border-dashed',
            )}
          >
            {source.reference && <Palette className="size-3 shrink-0" aria-hidden />}
            {source.kind === 'fx' && <AudioWaveform className="size-3 shrink-0" aria-hidden />}
            {source.kind === 'parked' && <Lock className="size-3 shrink-0" aria-hidden />}
            <span className="truncate">{words}</span>
            {source.heldBack && (
              <span
                data-testid="held-back-dot"
                aria-hidden
                className="absolute -top-0.5 -right-0.5 size-[7px] rounded-full bg-amber-500 ring-2 ring-background"
              />
            )}
          </button>
        }
      />
      {source.staged && (
        <span
          title="Blind: your value is held but not on stage"
          className="inline-flex h-[18px] shrink-0 items-center rounded-full border border-dashed border-amber-500/55 px-[7px] text-[10px] font-medium text-amber-600 dark:text-amber-400"
        >
          Staged
        </span>
      )}
    </span>
  )
}
