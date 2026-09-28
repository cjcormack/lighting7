import { relinkToDesk, useDeskFollow } from '@/lib/deskFollow'
import { unlinkFromDeskNow } from '@/store/selection'
import { FollowPill } from './FollowPill'
import { LinkBadge } from './LinkBadge'

/** The badge's accessible name while this window follows the desk selection. */
export const FOLLOWING_DESK_SELECTION = 'Following the desk selection'

/**
 * The **desk chip** — which selection this window is on, the desk's or its own (multi-screen plan
 * §4; busk-chrome plan D18, revisited by desk-follow plan D8).
 *
 * **Following, it is the link badge; unlinked, the dashed *This window*.** D18 drew nothing while
 * following, on the reasoning that a pill saying *Desk* all night is noise; the desk-follow review
 * kept the reasoning and changed the answer (Chris, 2026-09-23: "always show when we're linked,
 * even if it is just a small badge"). So while following it is `LinkBadge` — glyph only at every
 * width, hover *Following the desk selection*, whose press unlinks (below) — and unlinked it is
 * the dashed pill, whose press is `relinkToDesk`. The window always says which one it is on.
 *
 * **The badge is the toggle's other face** (desk-follow D11, revising D8's "a mark"): a press
 * takes this window's own copy of the desk's selection, as ⌘K's *Stop following the desk selection
 * in this window* does (`unlinkFromDeskNow`), and the dashed pill that replaces it presses back.
 * The Selection segment on a window's row of the Screens sheet (`windows.follow`, D4) and ⌘K stay,
 * for setting *another* window. A selection of its own is offered only where it means something
 * (D1): busk Split and the Programmer. **Rig and Pads focus always follow** (`followIsForced`,
 * D2) — the Screens segment is disabled there with its reason, ⌘K withholds the item, and entering
 * either while local relinks and toasts (D3, `BuskingView`) — so a host in one of those focuses
 * passes [forcedBy], and the badge is a mark whose hover says why rather than a press that would be
 * undone at once.
 *
 * It is a fact about the one selection rather than about this window's chrome: unlinked, the
 * selection is this tab's own and a press from here lands on it, not on the desk's (D8). Sitting on
 * the programmer's row C, the busk rig row (Split and Rig), the pad row (Pads, on the desk board),
 * the compact boards' rig strip and the short board's merged row, and nowhere else — the plain
 * lists never bridge to the desk (D1), so they have nothing to say — and on the busk view it has a
 * sibling, `BuskPageChip`, the same pill for the *page*, which is why the band's copy says
 * *Targets:* (`showSubject`) and row C's, alone and budgeted to the pixel, stays bare.
 *
 * The pill itself is `FollowPill`'s, shared with the page chip so the two cannot drift apart
 * visually, and the badge `LinkBadge`'s, which the page's link takes too (D7); the flags stay entirely
 * separate. `subjectClass` is the host's rung for the subject's fold (D19) — the accessible name is
 * whole whatever it hides. [className] is the pill's alone: a host hands the pill `min-w-0 shrink`
 * so its value truncates, and the badge must never shrink.
 */
export function DeskChip({
  showSubject,
  subjectClass,
  forcedBy,
  className,
}: {
  showSubject?: boolean
  subjectClass?: string
  /** The busk focus this host is drawn in, where it forces following (D2): the badge is then a mark. */
  forcedBy?: 'rig' | 'pads'
  className?: string
}) {
  const following = useDeskFollow()
  if (following) {
    if (forcedBy != null) {
      return (
        <LinkBadge
          label={FOLLOWING_DESK_SELECTION}
          title={`${FOLLOWING_DESK_SELECTION} — ${forcedBy === 'rig' ? 'Rig' : 'Pads'} focus always follows`}
        />
      )
    }
    return (
      <LinkBadge
        label={FOLLOWING_DESK_SELECTION}
        title={`${FOLLOWING_DESK_SELECTION} — click to give this window a selection of its own`}
        onUnlink={unlinkFromDeskNow}
      />
    )
  }
  return (
    <FollowPill
      following={false}
      subject={showSubject ? 'Targets' : undefined}
      subjectClass={subjectClass}
      label="This window"
      title="This window has its own selection — click to follow the desk again"
      onClick={relinkToDesk}
      className={className}
    />
  )
}
