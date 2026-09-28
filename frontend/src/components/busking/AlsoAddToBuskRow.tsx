import { useEffect, useState } from 'react'
import { BuskPagePicker } from './BuskPagePicker'
import { useAddToBuskPage } from './useAddToBuskPage'
import { buskAddChoices, type BuskAddTarget } from '@/lib/buskAdd'

interface AlsoAddToBuskRowProps {
  projectId: number
  target: BuskAddTarget | null
  onChange: (target: BuskAddTarget | null) => void
}

/**
 * *Also add to \<bank\>* on the sheets that **create** a template mid-show.
 *
 * The same page → bank picker the saved-record surfaces use, one control rather than a bespoke
 * checkbox, so a bank chosen here and a bank chosen there teach each other through the same
 * remembered target. What differs is timing: the record does not exist yet, so the choice is held
 * and the pad is appended by the sheet after its create call answers with an id.
 *
 * It offers the remembered bank pre-selected, because the whole point of the control is that a
 * thing made mid-show lands on the page without a trip to edit mode — and the bank is named on the
 * button, so a pre-selection is never a surprise. *Don’t add a pad* is in the menu for the times it
 * is wrong.
 *
 * With no busk pages at all there is nothing to offer and the row renders nothing: a disabled
 * control in a create sheet is noise on the way to the thing the operator actually came for.
 */
export function AlsoAddToBuskRow({ projectId, target, onChange }: AlsoAddToBuskRowProps) {
  // Eager here and *only* here: this row is inside `SheetContent`, which Radix unmounts while the
  // sheet is shut, so the page document is read when the operator opens the sheet rather than on
  // every render of the page that mounts it.
  const { targets, isLoading, defaultTarget } = useAddToBuskPage(projectId, null, true)
  const [seeded, setSeeded] = useState(false)

  // Every dependency is here and honest: `onChange` is the parent's `setState`, and `defaultTarget`
  // is a `useCallback` over `[projectId, targets]`. It settles because `seeded` latches — a later
  // `targets` change re-runs it and it returns immediately, which is the point: re-seeding would
  // undo a deliberate *Don't add a pad*.
  const choices = buskAddChoices(targets)

  useEffect(() => {
    // Gated on there being a real *choice*, not on there being a page: a page with no banks is a
    // legal page, and seeding from one would report "nothing chosen" and latch `seeded` against a
    // later arrival.
    if (seeded || choices.length === 0) return
    setSeeded(true)
    onChange(defaultTarget())
  }, [choices, seeded, onChange, defaultTarget])

  // `buskAddChoices`, not `targets.every(...)`: the latter is vacuously true on an empty array, so
  // it read the same for "no pages" and "pages with no banks" only by accident.
  if (!isLoading && choices.length === 0) return null

  return (
    <div className="flex items-center gap-2">
      <span className="flex-1 text-[11px] text-muted-foreground">
        {target == null ? 'Not added to a busk page.' : 'Also add a pad to'}
      </span>
      <BuskPagePicker
        label={target == null ? 'Add to busk page' : `${target.bankLabel} · ${target.pageName}`}
        pages={targets}
        isLoading={isLoading}
        onPick={onChange}
        onClear={() => onChange(null)}
      />
    </div>
  )
}
