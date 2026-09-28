import type { ReactNode } from 'react'
import { Search } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { cn } from '@/lib/utils'
import { SheetPage } from './SheetPage'

/**
 * **Row B for a library** (library-sheets plan D2): the filter · the partition chips · a spacer ·
 * the create verb, on the list shell's 40px chrome row. The create verb leaves the page header for
 * it, as the patch list's *+ Patch* did.
 *
 * Every slot but the filter is optional: Speed Masters and Looks partition nothing and draw no chips
 * (D3), and a library read from another project has no create verb.
 */
export function LibraryRow({
  filter,
  onFilterChange,
  filterLabel = 'Filter by name',
  chips,
  create,
  className,
}: {
  filter: string
  onFilterChange: (next: string) => void
  /** The field's accessible name and tooltip — what the filter matches. */
  filterLabel?: string
  /** A `PartitionChips`, where the library partitions exactly. */
  chips?: ReactNode
  /** The create verb — `+ New master`, `+ New template`. */
  create?: ReactNode
  className?: string
}) {
  return (
    <SheetPage.Row className={className}>
      <div className="relative min-w-0 max-w-[340px] flex-[999_1_0%]">
        <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          placeholder="Filter…"
          title={filterLabel}
          aria-label={filterLabel}
          value={filter}
          onChange={(e) => onFilterChange(e.target.value)}
          className="h-8 pl-9"
        />
      </div>
      {/* With chips, their own container is the row's slack — the spacer; without, a spacer. */}
      {chips ?? <div className="flex-1" />}
      {create}
    </SheetPage.Row>
  )
}

/** One partition a library divides into — a script type, an effect category, a template family. */
export interface PartitionOption<V extends string> {
  value: V
  label: string
  /** How many records are in it — drawn beside the label. */
  count?: number
  icon?: ReactNode
}

/**
 * The partition chips (library-sheets plan D3): *All* plus one per partition, each with its count —
 * what `LookFamilyFilterBar` was, generalised (it is gone — `/templates` and `TemplatePicker` both
 * mount these), and **controlled** as that was: a value and the counts in, a change out. The `?param=` and the remembered value are the **route's** (as `routes/Templates.tsx`
 * owns `looks.family` through `get/setStoredLookFamily`), so the same chips mount with local state
 * inside `TemplatePicker`'s portalled popover.
 *
 * **Below [fold] px of its own container the chips fold into a select.** The chips' own container,
 * not the viewport and not the row, because the popover has no row: a container query measures the
 * nearest `@container` ancestor, and this component brings its own. On a library row the container
 * takes the row's slack (it is the spacer), so the fold is the width left after the filter and the
 * create verb. **Measured, not guessed**, in the app with `offsetWidth` on the chips' `nav`:
 *
 * - **400**, the default — the template family's five chips with their counts are 361–367px, so the
 *   first cut's 600 folded them into a select on the 1180×820 iPad frame, where the row leaves them
 *   ~430 and they fit (session 2, 2026-09-24).
 * - **560** — the FX Library's six chips (*All* and five categories) are 483px (513 with a three-digit *All*), and the
 *   Scripts chips with all six types present 542 in their short labels (session 4, 2026-09-24).
 *   Either set would overflow its row between 400 and its own width, so they fold earlier — and
 *   the templates keep 400 rather than folding on an iPad where they fit, which is why this is a
 *   choice of two numbers and not one number moved up.
 *
 * Two literal class pairs rather than an interpolated width: Tailwind emits only classes it can
 * read whole in the source, so a `@[${fold}px]` would match nothing.
 */
export function PartitionChips<V extends string>({
  options,
  value,
  onChange,
  allLabel = 'All',
  allCount,
  label,
  fold = 400,
  className,
}: {
  options: readonly PartitionOption<V>[]
  /** The partition shown, or `'ALL'`. */
  value: V | 'ALL'
  onChange: (next: V | 'ALL') => void
  allLabel?: string
  /** The whole library's count, beside *All*. */
  allCount?: number
  /** What the chips partition by — the select's accessible name. */
  label: string
  /** The container width below which the chips fold into a select — measured per chip set. */
  fold?: PartitionFold
  className?: string
}) {
  const classes = FOLD_CLASSES[fold]
  const all: PartitionOption<V | 'ALL'> = { value: 'ALL', label: allLabel, count: allCount }
  const items: PartitionOption<V | 'ALL'>[] = [all, ...options]
  return (
    <div className={cn('@container min-w-0 flex-1', className)}>
      <nav
        aria-label={label}
        className={cn('hidden items-center gap-0.5 rounded-lg border bg-card p-0.5', classes.chips)}
      >
        {items.map((item) => {
          const active = item.value === value
          return (
            <button
              key={item.value}
              type="button"
              aria-pressed={active}
              onClick={() => onChange(item.value)}
              className={cn(
                'inline-flex h-7 items-center gap-1.5 rounded-md px-2.5 text-xs font-semibold',
                active ? 'bg-muted text-foreground' : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {item.icon}
              <span>{item.label}</span>
              {item.count != null && (
                <span className="font-mono text-[10px] font-normal tabular-nums text-muted-foreground">
                  {item.count}
                </span>
              )}
            </button>
          )
        })}
      </nav>
      <div className={classes.select}>
        <Select value={value} onValueChange={(next) => onChange(next as V | 'ALL')}>
          <SelectTrigger size="sm" aria-label={label} className="h-8 w-auto min-w-32 text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {items.map((item) => (
              <SelectItem key={item.value} value={item.value}>
                {item.label}
                {item.count != null ? ` · ${item.count}` : ''}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
    </div>
  )
}

/** The two measured folds — see [PartitionChips]. */
export type PartitionFold = 400 | 560

const FOLD_CLASSES: Record<PartitionFold, { chips: string; select: string }> = {
  400: { chips: '@[400px]:inline-flex', select: '@[400px]:hidden' },
  560: { chips: '@[560px]:inline-flex', select: '@[560px]:hidden' },
}
