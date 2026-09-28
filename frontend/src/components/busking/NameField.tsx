import { useEffect, useRef, useState } from 'react'
import { cn } from '@/lib/utils'
import { Input } from '@/components/ui/input'

/**
 * A name on a document the operator is editing, committed when they leave the field rather than
 * per keystroke.
 *
 * Every gesture on the busk page and on the rig saves the **whole document**, so a per-keystroke
 * write would be one full PUT and one broadcast per character. A rename is a gesture that ends when
 * you stop typing, so it commits on blur and on Enter; Escape puts the stored name back; and a blank
 * is reverted rather than sent, because both servers refuse one (`BUSK_LAYOUT_INVALID`,
 * `BUSK_RIG_INVALID`) and the field putting the stored name back is a better answer than a toast
 * after the optimistic patch rolled back.
 *
 * **Escape's revert is held in a ref, not read from state.** Escape puts the stored name back and
 * blurs, and the blur is dispatched *synchronously* inside the keydown — before React has applied
 * `setDraft(value)` — so a blur handler reading `draft` sees the typed name and saves the rename
 * the operator just cancelled. It went unnoticed while no `NameField` was ever focused in a test
 * (a `change` event focuses nothing, so `.blur()` dispatched nothing); the rig tile's rename is
 * focused on mount, and its test presses Escape.
 *
 * It was `BankNameField`, private to `BuskBank.tsx`, until the rig's rows needed the same field.
 */
export function NameField({
  value,
  label,
  placeholder,
  onSave,
  onDone,
  autoFocus = false,
  className,
}: {
  value: string
  /** The `aria-label`: `Bank name`, `Row name`, `Tile name`. */
  label: string
  placeholder: string
  /** Called with a non-blank name that differs from [value]. */
  onSave: (name: string) => void
  /**
   * Called once the field is left, after any save — for a host that draws the field only while a
   * rename is in progress (the rig tile's *Rename tile…*) and needs to know the gesture ended,
   * whether or not it changed anything.
   */
  onDone?: () => void
  /** Focus the field on mount: the rename a menu item opened is already the operator's intent. */
  autoFocus?: boolean
  className?: string
}) {
  const [draft, setDraft] = useState(value)
  /** Set by Escape for the blur it dispatches: that blur reverts and must not save. */
  const reverting = useRef(false)

  // Another client, or an undone save, can move the stored name under us.
  useEffect(() => setDraft(value), [value])

  function save() {
    if (reverting.current) return
    if (draft.trim() === '') {
      setDraft(value)
      return
    }
    if (draft === value) return
    onSave(draft)
  }

  return (
    <Input
      value={draft}
      aria-label={label}
      placeholder={placeholder}
      onChange={(e) => setDraft(e.target.value)}
      autoFocus={autoFocus}
      onBlur={() => {
        save()
        reverting.current = false
        onDone?.()
      }}
      onKeyDown={(e) => {
        if (e.key === 'Enter') e.currentTarget.blur()
        if (e.key === 'Escape') {
          reverting.current = true
          setDraft(value)
          e.currentTarget.blur()
          // An unfocused field dispatches no blur, so the flag would otherwise outlive the key.
          reverting.current = false
        }
      }}
      className={cn('h-7 text-[13px]', className)}
    />
  )
}
