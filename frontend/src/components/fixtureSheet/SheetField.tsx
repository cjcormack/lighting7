import { useRef } from 'react'
import { cn } from '@/lib/utils'
import { EditorField } from '../editor/EditorField'
import { FINGER_FIELD_CLASS, useFingerSized } from './sheetContext'

/** The field's width: the Main board's 76px, room for `100` and its unit. */
const FIELD_CLASS = 'w-[76px]'

/**
 * A typed field that commits on **Enter** rather than per keystroke — the kit's `EditorField` in the
 * caller's unit (Main board, note 5). A sheet value takes the programmer fade, and `8`, `80` written
 * on the way to `80` would be two fades; a live effect edit is a write the rig plays at once, and the
 * same `8` would be a beat of the wrong size (Fx board, note 1). The field shows what the caller holds
 * again after the commit (the blur drops the draft); leaving the field without Enter writes nothing.
 *
 * The fixture sheet's rows and `FxEditor` share it, so a typed value lands one way on both. On the
 * phone host it is 36px tall, a finger's size (§4).
 */
export function SheetField({
  label,
  unit,
  value,
  mixed,
  min,
  max,
  step,
  disabled,
  onEnter,
  className,
}: {
  label: string
  unit?: string
  value: number
  /** The heads hold different values: the range, as the empty box's placeholder (`EditorField`). */
  mixed?: string
  min: number
  max: number
  step?: number
  disabled: boolean
  onEnter: (n: number) => void
  /** Replaces the 76px width. */
  className?: string
}) {
  const pending = useRef<number | null>(null)
  const finger = useFingerSized()
  return (
    <div
      className={cn('shrink-0', className ?? FIELD_CLASS)}
      onKeyDown={(e) => {
        if (e.key !== 'Enter') return
        e.preventDefault()
        const n = pending.current
        pending.current = null
        if (n != null) onEnter(n)
        ;(e.target as HTMLElement).blur?.()
      }}
    >
      <EditorField
        label={label}
        unit={unit}
        value={value}
        mixed={mixed}
        min={min}
        max={max}
        step={step}
        disabled={disabled}
        fieldClassName={finger ? FINGER_FIELD_CLASS : undefined}
        onCommit={(n) => {
          pending.current = n
        }}
        onDraft={(raw) => {
          if (raw == null || raw.trim() === '') pending.current = null
        }}
      />
    </div>
  )
}
