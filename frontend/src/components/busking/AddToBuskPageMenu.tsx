import { BuskPagePicker } from './BuskPagePicker'
import { useAddToBuskPage } from './useAddToBuskPage'
import type { BuskAddRecord, BuskAddTarget } from '@/lib/buskAdd'

interface AddToBuskPageMenuProps {
  projectId: number
  /** Null while the surface has no saved record to place — the control renders nothing. */
  record: BuskAddRecord | null
}

/**
 * *Add to busk page* for a record that already exists: pick a page and a bank, and the pad is there.
 *
 * The one route onto a page that does not go through the busk view's edit mode. It appends — the
 * operator is looking at a cue or a template, not at a layout, so the most they can mean is
 * "somewhere on that page", and a bank is the smallest place that is.
 *
 * Errors are left to the error-toast middleware: this control has no inline place to put one, and a
 * failure here is always about the page (a bank deleted underneath, a project no longer current)
 * rather than about anything the operator typed.
 */
export function AddToBuskPageMenu({ projectId, record }: AddToBuskPageMenuProps) {
  const { targets, isLoading, arm, place } = useAddToBuskPage(projectId, record)

  if (record == null) return null

  return (
    // Armed on the way *in* — `pointerdown` is what opens the menu, and hover/focus get there
    // earlier still — so the page document is already being read by the time the menu is on screen.
    <span onPointerEnter={arm} onFocus={arm} onPointerDown={arm}>
      <BuskPagePicker
        label="Add to busk page"
        pages={targets}
        isLoading={isLoading}
        onPick={(target: BuskAddTarget) => void place(target).catch(() => {})}
      />
    </span>
  )
}
