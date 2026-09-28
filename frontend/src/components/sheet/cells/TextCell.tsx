import { memo, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { EditorSurface } from '../../editor/EditorSurface'
import { EditorFooter } from '../../editor/EditorFooter'
import { EditorLabel } from '../../editor/EditorLabel'
import { EditorLabelLine } from '../../editor/EditorLabelLine'
import { EditorReadout } from '../../editor/EditorReadout'
import { useEditorKeyboard } from '../../editor/useEditorKeyboard'
import { useEditorOpen } from '../../editor/useEditorOpen'
import type { SheetCellProps, SheetRow } from '../sheetModel'

/** What a typed draft would do to the whole batch — [AddressCell]'s `AddressLanding` in text form. */
export interface TextLanding {
  /** `Front PAR → par-1`, one per row — drawn one to a line; empty where there is nothing to show. */
  lines: string[]
  /** The first problem with it, named; null when it lands clear. */
  error: string | null
}

export interface TextCellProps extends SheetCellProps<string> {
  /** What the cell shows. Defaults to the value, or an em-dash for an empty one. */
  face?: ReactNode
  placeholder?: string
  /** Draw the value in the mono face — keys, fade times. */
  mono?: boolean
  /**
   * Refuse a draft, with the reason shown under the field. Enter and Apply are withheld while it
   * answers; the editor stays open so the operator can fix the text rather than lose it.
   */
  validate?: (draft: string) => string | null
  /**
   * What a draft would do to the **batch**, computed by the column, which knows the rest of the
   * rig — the patch list's Key column fans one typed key over the selection and names a collision
   * before Apply, exactly as `AddressCell` does for a start channel. Called with the batch rows in
   * visible order, and only while the editor is open.
   */
  plan?: (rows: readonly SheetRow[], draft: string) => TextLanding | null
  /** The commit for an emptied field. Absent means an empty draft is refused. */
  allowEmpty?: boolean
}

/**
 * A text cell — name, key, notes, a fade time: an input, commit on Enter, Escape reverts
 * (CLAUDE.md §Sheet kit).
 *
 * It commits on Enter rather than as it is typed, unlike the level and colour editors: those
 * drive a live channel the operator judges by eye, and a half-typed name is not a value anyone
 * wants written. The draft is seeded from the value on every open and from the character typed
 * at the grid when the keyboard opened it; Escape closes the surface and the draft dies with it.
 *
 * Because it writes on Apply, it is one of the two editors that draw an `EditorFooter`
 * (editor-kit plan D8, D9); the landing lines are the read-out's multi-line arm. The popover is
 * 288px (D17). `w-72`, measured in the app at 288px on 2026-09-22 (the popover's `getBoundingClientRect`).
 */
export const TextCell = memo(function TextCell({
  value,
  label,
  batchLabel,
  batchRows,
  skipped,
  disabled,
  autoOpen,
  autoClose,
  anchorAtButton,
  keyboardSeed,
  selectionEmpty,
  editorAnchorRef,
  onBeginEdit,
  onCommit,
  face,
  placeholder,
  mono,
  validate,
  plan,
  allowEmpty = false,
}: TextCellProps) {
  const [draft, setDraft] = useState(value)
  const valueRef = useRef(value)
  valueRef.current = value
  const reset = useCallback(() => setDraft(valueRef.current), [])

  const { isOpen, setOpen, keyboardOpen, atButton } = useEditorOpen({
    autoOpen,
    autoClose,
    anchorAtButton,
    keyboardSeed,
    disabled,
    selectionEmpty,
    onOpen: reset,
  })
  // The character that opened the editor replaces the draft: typing at the grid is the operator
  // starting a new value, not appending to the old one.
  useEffect(() => {
    if (keyboardOpen) setDraft(keyboardOpen)
  }, [keyboardOpen])

  const trimmed = draft.trim()
  const empty = trimmed === '' && !allowEmpty
  // Only while open, and never for an empty draft. Open, because the closure is re-made on every
  // render of the row (the props object hands a fresh `batchRows` arrow), so a plan computed when
  // the editor is shut would run per frame of a marquee drag for every visible cell. Non-empty,
  // because a plan asked what `''` would do answers with whatever its own arithmetic makes of an
  // empty string — the key scheme fans it to `-1`, `-2` — and the operator would read a preview of
  // keys beside the "cannot be empty" that says nothing will be written at all.
  const landing = useMemo(
    () => (isOpen && plan && !empty ? plan(batchRows(), trimmed) : null),
    [batchRows, empty, isOpen, plan, trimmed],
  )
  const error = empty ? `${label} cannot be empty` : (landing?.error ?? validate?.(draft) ?? null)
  const commit = useCallback(() => {
    if (error) return false
    onCommit(trimmed)
    return true
  }, [error, onCommit, trimmed])

  const { contentRef, onKeyDown, onOpenAutoFocus } = useEditorKeyboard({
    onDone: () => {
      if (commit()) setOpen(false)
    },
  })

  return (
    <EditorSurface
      open={isOpen}
      onOpenChange={setOpen}
      title={label}
      contentClassName="w-72"
      onOpenAutoFocus={onOpenAutoFocus}
      triggerOpens={false}
      anchorRef={atButton ? editorAnchorRef : undefined}
      trigger={
        <button
          type="button"
          disabled={disabled}
          onClick={onBeginEdit}
          className="flex h-full w-full items-center rounded text-left hover:bg-accent/50"
        >
          {face ?? (
            <span className={cn('mx-1.5 truncate text-xs', mono && 'font-mono tabular-nums', value === '' && 'text-muted-foreground/60')}>
              {value === '' ? '—' : value}
            </span>
          )}
        </button>
      }
    >
      <div ref={contentRef} onKeyDown={onKeyDown} className="space-y-2">
        <EditorLabelLine subject={batchLabel} column={label} />
        <div className="space-y-1">
          <EditorLabel>{label}</EditorLabel>
          <Input
            type="text"
            aria-label={label}
            placeholder={placeholder}
            spellCheck={false}
            autoComplete="off"
            className={cn('h-7 px-2 text-xs', mono && 'font-mono tabular-nums')}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
          />
        </div>
        <EditorReadout lines={landing?.lines} error={error}>
          {skipped ? <span data-editor-skipped>{skipped}</span> : undefined}
        </EditorReadout>
        <EditorFooter>
          <Button
            size="sm"
            className="h-7 text-xs"
            disabled={error != null}
            onClick={() => {
              if (commit()) setOpen(false)
            }}
          >
            Apply
          </Button>
        </EditorFooter>
      </div>
    </EditorSurface>
  )
})
