import type { LucideIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { ROW_VERB_WORD_CLASS } from './toolbarFolds'

/**
 * One **row verb** on a library sheet's selection bar (library-sheets plan D11) — Include, Pick up,
 * Duplicate, Copy to…, Delete — after Set · Clear · Spread and before Deselect. The per-row `…`
 * menus these replaced are gone: a verb over one row is a verb over a selection of one.
 *
 * The button is the bar's own outline size, its word under `ROW_VERB_WORD_CLASS` — folding to the
 * icon below 1100px of bar, before Set and Clear do, since the row verbs are what overflow an iPad's
 * bar. A disabled verb **says why on its title** — the reason, not the verb — which is how a
 * one-record verb over several rows, and every value verb in another project's library (D12), tell
 * the operator what to do instead.
 */
export function LibraryVerb({
  icon: Icon,
  label,
  title,
  disabledReason,
  destructive = false,
  onClick,
}: {
  icon: LucideIcon
  label: string
  /** The enabled title — what the press will do. */
  title: string
  /** Why the verb is refused here, or null where it is live. */
  disabledReason?: string | null
  destructive?: boolean
  onClick: () => void
}) {
  const disabled = disabledReason != null
  return (
    <Button
      variant="outline"
      size="sm"
      className={cn(destructive && 'text-destructive hover:text-destructive')}
      disabled={disabled}
      onClick={onClick}
      title={disabledReason ?? title}
      aria-label={label}
    >
      <Icon className="size-3.5" />
      <span className={ROW_VERB_WORD_CLASS}>{label}</span>
    </Button>
  )
}

/**
 * Why a verb that acts on **one** record (Include, Pick up) is refused over this selection, or null
 * when exactly one is selected. D11: disabled over several, with the reason.
 */
export function oneRecordReason(verb: string, noun: string, count: number): string | null {
  return count === 1 ? null : `${verb} takes one ${noun} — select just one`
}
