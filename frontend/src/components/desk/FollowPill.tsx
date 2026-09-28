import { Link2, Unlink2 } from 'lucide-react'
import { cn } from '@/lib/utils'

/**
 * The **follow/local pill** — the chrome both desk chips are drawn from.
 *
 * There are two of them: `DeskChip` for the selection and `components/busking/BuskPageChip` for the
 * busk page. They must read as one system, because on the busk view they sit a row apart and say
 * the same two words; and the thing they must *not* share is their state, since following the
 * desk's selection while holding a page of your own is the whole point of the pair existing.
 *
 * So the split is: this owns the shape — height, pill, the link / unlink glyph, the solid-following
 * and dashed-local treatments, the optional muted subject and the optional *· from* suffix — and
 * each chip owns its own flag, its own label and its own click. The visual promise is then kept by
 * the type system rather than by two developers remembering to edit both files; the two flags stay
 * as independent as they were.
 *
 * **A pill is drawn only while its window is unlinked** (busk-chrome plan D18) — *Desk* is the
 * resting state and a pill saying so all night is noise — so what this draws today is the dashed
 * form; while linked each chip draws `LinkBadge` instead (desk-follow plan D7, D8), a glyph rather
 * than a pill. The solid form and the suffix are kept on the primitive: a chip that comes back to
 * say *Desk* would draw them, and the parts' folds below are written for both.
 *
 * `subject` is a word like *Targets* or *Page*, drawn muted before the value, where the pair is on
 * screen together and omitted where a chip is alone (the programmer's row C). `from` is the mover's
 * name, drawn muted after it. **Each part takes a class of its own** (D19): a chip gives up the
 * suffix first, then its subject, then the value truncates — each at a rung the *host* measures
 * for the row the chip sits on, which is why the classes come in rather than being chosen here.
 * The width may hide a part; the accessible name may not change with it, so the button carries the
 * whole reading as `aria-label` — `Targets: This window`, `Page: Own` — and a
 * test or a screen reader gets the same name at every width. The pill's base is `shrink-0`; a host
 * that wants the value to truncate hands `min-w-0 shrink`, both words, since `twMerge` keeps
 * `shrink-0` beside a bare `min-w-0`.
 */
export function FollowPill({
  following,
  subject,
  subjectClass,
  from,
  fromClass,
  label,
  title,
  onClick,
  className,
}: {
  following: boolean
  subject?: string
  /** The subject's fold — the host's rung. Drawn always when absent. */
  subjectClass?: string
  from?: string
  /** The suffix's fold — the host's rung, and the first part to go. Drawn always when absent. */
  fromClass?: string
  /** The value: *This window* for the selection, *Own* for the page. */
  label: string
  title: string
  onClick: () => void
  className?: string
}) {
  const name = `${subject != null ? `${subject}: ` : ''}${label}${from != null ? ` · from ${from}` : ''}`
  return (
    <button
      type="button"
      aria-pressed={following}
      aria-label={name}
      title={title}
      onClick={onClick}
      className={cn(
        'inline-flex h-5 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full border px-2 text-[10px] font-medium leading-none',
        following
          ? 'border-primary/40 bg-primary/10 text-primary hover:bg-primary/15'
          : 'border-dashed border-muted-foreground/50 text-muted-foreground hover:bg-accent/50',
        className,
      )}
    >
      {following ? <Link2 className="size-[11px]" /> : <Unlink2 className="size-[11px]" />}
      {subject != null && (
        <span data-pill-subject className={cn('text-muted-foreground', subjectClass)}>
          {subject}:
        </span>
      )}
      <span data-pill-label className="min-w-0 truncate">{label}</span>
      {from != null && (
        <span data-pill-from className={cn('min-w-0 truncate text-muted-foreground', fromClass)}>
          · from {from}
        </span>
      )}
    </button>
  )
}
