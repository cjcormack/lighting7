import { useEffect, useRef, type ReactNode } from 'react'
import { Input } from '@/components/ui/input'
import { useNumberFieldDraft } from '@/hooks/useNumberFieldDraft'
import { cn } from '@/lib/utils'

/**
 * The editor kit's one number field — 28px, the unit drawn inside as a trailing muted glyph, and
 * the draft rule built in (editor-kit plan D9).
 *
 * It replaces three fields that had each answered the same questions their own way:
 * `ValueFieldRow`'s bare `Input`, the busk Spread tab's private `NumberField` and the colour
 * editor's `ChannelNumberInput` (deleted in session 2: `ColourEditor` mounts this directly and
 * clamps the byte itself). What they share, and what lives here once:
 *
 * - **Mid-retype must not commit.** `useNumberFieldDraft` owns that rule — `Number('')` is 0, and
 *   a field that committed on its way to being retyped would black a channel out between the last
 *   digit deleted and the first one typed. The operator's text stays on screen while it is being
 *   edited, only what parses is committed, and the text is dropped on blur so the field shows what
 *   the desk holds. A lone `-` is what a browser reports as `''`, so a negative degree can be
 *   typed on the one editor whose range runs both ways.
 * - **The caller still clamps.** This parses; the caller decides what the number may be, because a
 *   channel byte is 0–255 while a slider cell's bounds come from its resolution and a percent is
 *   0–100. A clamp here would be a second copy of every caller's range.
 * - **The unit is inside the box**, trailing and muted — the busk tabs' `28px fields with the unit
 *   inside`. The native spinner is hidden so the glyph has the right edge; the arrow keys still
 *   step the value, since the input stays `type="number"`.
 *
 * [seed] is the character typed at the grid that opened the editor, landing in the field as though
 * it had been typed here — which means it commits, the way every keystroke here does. Only an
 * editor's **first** field is ever given one (the keyboard opens on the first field; see
 * `useEditorKeyboard`).
 */
export function EditorField({
  label,
  prefix,
  unit,
  value,
  onCommit,
  min,
  max,
  step = 1,
  seed,
  onDraft,
  disabled,
  className,
  fieldClassName,
}: {
  /** The field's accessible name. */
  label: string
  /**
   * A visible leading label — the colour editor's `R` / `G` / `B`. Absent where the row or an
   * `EditorLabel` above already names the field.
   */
  prefix?: ReactNode
  /** The unit glyph drawn inside the box — `%`, `°`. Absent for a bare number. */
  unit?: string
  /** What the desk holds. The field shows it whenever the operator has no draft. */
  value: number
  /** A parsed number the operator typed. Clamp it here: the field does not. */
  onCommit: (next: number) => void
  min?: number
  max?: number
  step?: number
  /** See the docblock. */
  seed?: string | null
  /**
   * The operator's raw text as it is typed, and `null` when the draft is dropped on blur — for a
   * host that has to know the box is **empty** rather than merely unchanged. `onCommit` is silent
   * for an emptied box by design, so a host that writes on Apply (the address editor) would
   * otherwise apply the last number the operator deleted precisely to abandon it.
   */
  onDraft?: (raw: string | null) => void
  disabled?: boolean
  /** On the wrapping `<label>`. */
  className?: string
  /** On the input itself — a width, most often. */
  fieldClassName?: string
}) {
  const draft = useNumberFieldDraft(String(value), onCommit)

  // Through a ref so the effect depends on the seed alone: `draft` is rebuilt on every render, and
  // depending on it would re-seed the field on the operator's next keystroke.
  const draftRef = useRef(draft)
  draftRef.current = draft
  useEffect(() => {
    if (seed) draftRef.current.onChange(seed)
  }, [seed])

  return (
    <label className={cn('flex min-w-0 items-center gap-1.5', className)}>
      {prefix != null && (
        <span className="w-5 shrink-0 text-[11px] font-medium tabular-nums text-muted-foreground">
          {prefix}
        </span>
      )}
      <span className="relative flex min-w-0 flex-1 items-center">
        <Input
          type="number"
          min={min}
          max={max}
          step={step}
          disabled={disabled}
          aria-label={label}
          className={cn(
            'h-7 min-w-0 px-2 text-xs tabular-nums [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none',
            unit != null && 'pr-6',
            fieldClassName,
          )}
          value={draft.value}
          onChange={(e) => {
            draft.onChange(e.target.value)
            onDraft?.(e.target.value)
          }}
          onBlur={() => {
            draft.onBlur()
            onDraft?.(null)
          }}
        />
        {unit != null && (
          <span
            aria-hidden
            data-editor-unit
            className="pointer-events-none absolute right-2 text-[10px] text-muted-foreground"
          >
            {unit}
          </span>
        )}
      </span>
    </label>
  )
}
