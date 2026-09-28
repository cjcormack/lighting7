import { useEffect, useRef, useState, type ReactNode } from 'react'
import { cn } from '@/lib/utils'

interface InlineEditFieldProps {
  /** The committed text. Also what the input starts from when editing opens. */
  value: string
  /**
   * Commit the edited text. Return `false` to reject it — Enter then keeps the field
   * open and flags it invalid, blur reverts to `value`.
   */
  onCommit: (next: string) => boolean | void
  /** Describes the field for assistive tech, e.g. "cue name". */
  ariaLabel: string
  /**
   * Renders the committed text for display — use it when the display form differs from
   * the editable form (cue number "12" shown as "Q12"). Defaults to the text itself.
   */
  formatDisplay?: (value: string) => ReactNode
  placeholder?: string
  /** Renders as plain text with no edit affordance. */
  disabled?: boolean
  /**
   * Which pointer gesture opens the field. `'click'` is the default and the original behaviour.
   *
   * `'doubleClick'` is for a field that sits **on a sheet's row-select column**, where a single
   * click already means "select this row" (CLAUDE.md §The cell editor's three forms: a single
   * click says what to edit, a second gesture edits it). It renders a plain `<span>` rather than a
   * `<button>`: a button would swallow the press the row selection and the row marquee are both
   * listening for, and nothing here stops propagation until the field is actually open. The
   * keyboard route to the same edit is the row's own Edit affordance.
   */
  openOn?: 'click' | 'doubleClick'
  /**
   * Applied to both the display element and the input, so the row can't jump on open.
   * Pass `truncate` when the host cell needs one clipped line — the field wraps like a
   * plain `<span>` otherwise, matching whatever the read-only markup did.
   */
  className?: string
  displayClassName?: string
  inputClassName?: string
  title?: string
  /**
   * Edit in a `<textarea>` instead of an `<input>`. Enter then inserts a newline and
   * Cmd/Ctrl+Enter commits — cue notes are prose and run to several lines, so the single-line
   * convention of "Enter saves" would make a line break impossible to type.
   */
  multiline?: boolean
  /** Rows for the `multiline` textarea. */
  rows?: number
}

/**
 * Click-to-edit text cell for row/table layouts: shows the value, swaps to an input on
 * click, commits on Enter or blur, cancels on Escape. Pass `multiline` for prose (cue notes),
 * where Enter inserts a newline and Cmd/Ctrl+Enter commits instead.
 *
 * Every pointer/key event is stopped from propagating so the field works inside rows that
 * are themselves click targets (expand a cue, drill into a stack) and alongside the
 * window-level show shortcuts.
 *
 * Because commits are auto-saving PATCHes whose result arrives back via a refetch, the
 * just-committed text is held as the display value until `value` catches up — otherwise
 * every edit would flash the old value for a round trip.
 */
export function InlineEditField({
  value,
  onCommit,
  ariaLabel,
  formatDisplay,
  placeholder,
  disabled = false,
  openOn = 'click',
  className,
  displayClassName,
  inputClassName,
  title,
  multiline = false,
  rows = 3,
}: InlineEditFieldProps) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(value)
  const [invalid, setInvalid] = useState(false)
  const [pending, setPending] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement | HTMLTextAreaElement>(null)
  const pendingTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  // The committed value moved (our own save landed, or another client changed it) — the
  // optimistic text has served its purpose. The draft is deliberately left alone: it is
  // re-seeded when the field opens, so a refetch can't clobber in-flight typing.
  useEffect(() => {
    setPending(null)
  }, [value])

  useEffect(() => {
    return () => {
      if (pendingTimer.current) clearTimeout(pendingTimer.current)
    }
  }, [])

  // Safety valve: a save the server normalises to the value we already had never changes
  // `value`, so nothing would clear the optimistic text.
  const holdPending = (next: string) => {
    setPending(next)
    if (pendingTimer.current) clearTimeout(pendingTimer.current)
    pendingTimer.current = setTimeout(() => setPending(null), 5000)
  }

  const open = () => {
    if (disabled) return
    setDraft(pending ?? value)
    setInvalid(false)
    setEditing(true)
  }

  const cancel = () => {
    setDraft(value)
    setInvalid(false)
    setEditing(false)
  }

  const commit = (via: 'enter' | 'blur') => {
    if (draft === (pending ?? value)) {
      setEditing(false)
      return
    }
    if (onCommit(draft) === false) {
      // Enter keeps the operator in the field to fix it; a blur can't, so it reverts.
      if (via === 'blur') {
        cancel()
      } else {
        setInvalid(true)
        inputRef.current?.select()
      }
      return
    }
    holdPending(draft)
    setInvalid(false)
    setEditing(false)
  }

  if (editing) {
    const editorProps = {
      // Safe to autofocus: the field only mounts on an explicit click.
      autoFocus: true,
      value: draft,
      placeholder,
      'aria-label': ariaLabel,
      'aria-invalid': invalid || undefined,
      onChange: (e: { target: { value: string } }) => {
        setDraft(e.target.value)
        setInvalid(false)
      },
      onClick: (e: { stopPropagation: () => void }) => e.stopPropagation(),
      onPointerDown: (e: { stopPropagation: () => void }) => e.stopPropagation(),
      onBlur: () => commit('blur'),
      className: cn(
        'min-w-0 rounded border border-input bg-background px-1 outline-none',
        'focus:border-ring focus:ring-1 focus:ring-ring/50',
        'aria-invalid:border-destructive aria-invalid:ring-1 aria-invalid:ring-destructive/50',
        className,
        inputClassName,
      ),
    }

    if (multiline) {
      return (
        <textarea
          {...editorProps}
          ref={inputRef as React.RefObject<HTMLTextAreaElement>}
          rows={rows}
          // No select-on-focus: the caret lands where the operator clicked, because editing a
          // note usually means amending it, not replacing it.
          onKeyDown={(e) => {
            e.stopPropagation()
            // Enter is a newline here, so committing needs the modifier. Blur still saves.
            if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
              e.preventDefault()
              commit('enter')
            } else if (e.key === 'Escape') {
              e.preventDefault()
              cancel()
            }
          }}
        />
      )
    }

    return (
      <input
        {...editorProps}
        ref={inputRef as React.RefObject<HTMLInputElement>}
        onFocus={(e) => e.currentTarget.select()}
        onKeyDown={(e) => {
          e.stopPropagation()
          if (e.key === 'Enter') {
            e.preventDefault()
            commit('enter')
          } else if (e.key === 'Escape') {
            e.preventDefault()
            cancel()
          }
        }}
      />
    )
  }

  const text = pending ?? value
  const display = formatDisplay ? formatDisplay(text) : text
  // A transparent border matches the input's box so opening the field can't reflow the row.
  //
  // **`inline-block`, not the default inline.** A `<span>` whose child is a block (`TruncateStart`
  // renders three) is broken into anonymous block boxes, and the line box it leaves behind measures
  // far taller than the glyphs — 59px for a 20px cue number. Harmless where the host just centres
  // it; on a sheet the host is a grid item, so that phantom height became the row's *track* and
  // every cell in a locked cue sheet was laid out 12px below the 36px row, spilling across the
  // border into the next one. The editing branch is an `<input>` and the enabled display branch a
  // `<button>`, both already inline-block; this makes the read-only span agree with them.
  const boxClass = cn(
    'inline-block min-w-0 rounded border border-transparent px-1 text-left',
    // Multi-line values are prose — keep the operator's own line breaks, and let the text wrap
    // rather than running off the side as a single-line cell would.
    multiline && 'block whitespace-pre-wrap',
    className,
    displayClassName,
  )

  // Read-only: a plain span, not a disabled <button>. A disabled button dispatches no
  // click at all, which would turn the cell into a dead zone instead of letting the click
  // reach the row underneath (drill into a stack, expand a cue).
  if (disabled) {
    return (
      <span title={title} className={boxClass}>
        {display}
      </span>
    )
  }

  // A sheet's row-select column: a single click belongs to the row underneath, so the display is a
  // span and only the second click opens the field. `stopPropagation` on the double click alone —
  // the single clicks that make it up must reach the row, and so must the press that starts a
  // marquee.
  if (openOn === 'doubleClick') {
    return (
      <span
        title={title}
        aria-label={`${ariaLabel}: ${text || 'empty'}. Double-click to edit.`}
        onDoubleClick={(e) => {
          e.stopPropagation()
          open()
        }}
        className={cn(boxClass, 'cursor-text hover:border-input hover:bg-foreground/[0.06]')}
      >
        {display}
      </span>
    )
  }

  return (
    <button
      type="button"
      title={title}
      // Name the value, not just the action — an aria-label of "Edit cue name" alone would
      // hide every cell's text from a screen reader walking the table.
      aria-label={`${ariaLabel}: ${text || 'empty'}. Click to edit.`}
      onClick={(e) => {
        e.stopPropagation()
        open()
      }}
      onPointerDown={(e) => e.stopPropagation()}
      onKeyDown={(e) => e.stopPropagation()}
      className={cn(boxClass, 'cursor-text hover:border-input hover:bg-foreground/[0.06]')}
    >
      {display}
    </button>
  )
}
