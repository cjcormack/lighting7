import { Link2 } from 'lucide-react'
import { cn } from '@/lib/utils'

/**
 * The **link badge** — a window following the desk always says so (desk-follow plan D7, D8;
 * `Selection.dc.html`, `Pages.dc.html`). D18 had hidden both desk chips while following, on the
 * reasoning that a pill saying *Desk* all night is noise; the review kept the reasoning and changed
 * the answer: a glyph is not a pill, and a window that says nothing about which selection or page
 * it is on leaves the operator to remember. So while linked a host draws this, and while local the
 * dashed chip (`FollowPill`) takes its place and presses back.
 *
 * **One component for both uses so they cannot drift**: `DeskChip` draws it beside the family pill
 * for the selection, glyph only at every width, and the page's (D7) beside the tabs, naming
 * any other window paged with this one. [names] is that list — the first is drawn after the glyph,
 * capped at 96px, the rest counted as `+N`, every one listed by the host in [title] — and [namesClass] is the row's
 * rung for folding them to the glyph alone (D19's convention: the host measures, the part takes a
 * class). With no names it is the glyph and nothing else.
 *
 * **It is the toggle's linked face** (desk-follow D11, 2026-09-23, revising D7 and D8's "a mark,
 * never a control"): with [onUnlink] it is a button whose press takes the window off the desk, and
 * the dashed chip that replaces it presses back — so the one control on the row flips the two modes
 * both ways, as the operator reaches for it first. The Screens row and ⌘K stay, for a window other
 * than this one. Without [onUnlink] it is a mark (`role="img"`): where the window cannot leave —
 * the selection in Rig and Pads focus (D2) — a press would have nothing to do, and a control that
 * does nothing reads as broken, so the host says why in [title] instead. [label] is the accessible
 * name either way — the state, whole at every width — and [title] the hover, which says what a
 * press does. `aria-pressed` is true, the pill's convention: pressed means linked. `shrink-0`, and
 * the same 20px height as the pills beside it: a badge that gave way would move the controls it
 * exists to sit quietly beside, and the button form is the mark's box to the pixel, so no ladder
 * moved for it.
 */
export function LinkBadge({
  label,
  title,
  names,
  namesClass,
  onUnlink,
  className,
}: {
  /** The accessible name — the state: *Following the desk selection*. */
  label: string
  /** The hover: the state, and what a press does (or why it cannot). Defaults to [label]. */
  title?: string
  /** Other windows linked with this one, drawn after the glyph. Absent or empty draws the glyph alone. */
  names?: readonly string[]
  /** The names' fold — the host's rung. Drawn always when absent. */
  namesClass?: string
  /** Take this window off the desk. Absent draws a mark rather than a button. */
  onUnlink?: () => void
  className?: string
}) {
  const shown = names != null && names.length > 0 ? names : null
  const body = (
    <>
      <Link2 className="size-[11px] shrink-0" aria-hidden />
      {shown != null && (
        <span data-link-badge-names className={namesClass}>
          {/* Capped, so a long window name has a ceiling a row's ladder can be measured against;
              the whole list is on the hover. The count is outside the cap and never truncates. */}
          <span className="inline-block max-w-24 truncate align-bottom">{shown[0]}</span>
          {shown.length > 1 && ` +${shown.length - 1}`}
        </span>
      )}
    </>
  )
  const base = cn(
    'inline-flex h-5 min-w-5 shrink-0 items-center justify-center gap-1 whitespace-nowrap rounded-md border px-1 text-[10px] font-semibold leading-none text-muted-foreground',
    className,
  )
  if (onUnlink == null) {
    return (
      <span role="img" aria-label={label} title={title ?? label} data-link-badge className={base}>
        {body}
      </span>
    )
  }
  return (
    <button
      type="button"
      aria-label={label}
      aria-pressed
      title={title ?? label}
      onClick={onUnlink}
      data-link-badge
      className={cn(base, 'hover:bg-accent/50 hover:text-foreground')}
    >
      {body}
    </button>
  )
}
