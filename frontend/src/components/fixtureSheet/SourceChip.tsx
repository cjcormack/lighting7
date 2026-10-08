import { useState } from 'react'
import { AudioWaveform, Lock } from 'lucide-react'
import { cn } from '@/lib/utils'
import type { RowSource, RowSourceKind } from './rowSource'
import { LayerStack } from './LayerStack'

/**
 * The row's 3px edge, in the programmer grid's colours (`fixtures-list/ownership.ts`): accent for
 * the programmer, violet for an effect, sky for a cue, amber for park, none for the base.
 */
export const SOURCE_EDGE_CLASS: Record<RowSourceKind, string> = {
  programmer: 'before:bg-primary',
  effect: 'before:bg-violet-500',
  cue: 'before:bg-sky-500',
  parked: 'before:bg-amber-500',
  base: 'before:bg-transparent',
}

function chipClass(source: RowSource): string {
  switch (source.kind) {
    case 'programmer':
      // Filled once touched; an outline while it is only what Include brought in (Layers board).
      return source.touched
        ? 'border-primary/55 bg-primary/15 text-primary'
        : 'border-primary/55 text-primary'
    case 'effect':
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
 * The row's source chip (D4) — and its door to the property's stack (D5): a press opens
 * `LayerStack` through `EditorSurface`. An amber dot rides it while something underneath is held
 * back; a dashed amber *Staged* mark beside it while the programmer is blind and holds the row (the
 * chip names what is on stage, the row's control shows what leaving Blind would land).
 */
export function SourceChip({
  source,
  headKey,
  propertyName,
  label,
}: {
  source: RowSource
  /** The head the row writes — the stack is asked of it. */
  headKey: string
  /** The row's first key: the property the stack is asked about. */
  propertyName: string
  /** The row's name, for the stack's title. */
  label: string
}) {
  const [open, setOpen] = useState(false)
  return (
    <span className="flex min-w-0 items-center gap-1">
      <LayerStack
        open={open}
        onOpenChange={setOpen}
        headKey={headKey}
        propertyName={propertyName}
        label={label}
        trigger={
          <button
            type="button"
            data-source={source.kind}
            data-held-back={source.heldBack || undefined}
            title={source.heldBack ? `${source.label} — something underneath is held back` : `${source.label} — show what is underneath`}
            className={cn(
              'relative inline-flex h-[18px] min-w-0 max-w-full items-center gap-1 rounded-full border px-[7px] text-[10px] font-medium whitespace-nowrap',
              chipClass(source),
            )}
          >
            {source.kind === 'effect' && <AudioWaveform className="size-3 shrink-0" aria-hidden />}
            {source.kind === 'parked' && <Lock className="size-3 shrink-0" aria-hidden />}
            <span className="truncate">{source.label}</span>
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
