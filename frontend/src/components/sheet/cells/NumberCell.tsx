import { memo, useCallback, useRef, useState, type ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { EditorSurface } from '../../editor/EditorSurface'
import { EditorField } from '../../editor/EditorField'
import { EditorFooter } from '../../editor/EditorFooter'
import { EditorLabel } from '../../editor/EditorLabel'
import { EditorLabelLine } from '../../editor/EditorLabelLine'
import { EditorReadout } from '../../editor/EditorReadout'
import { numericSeed, useEditorKeyboard } from '../../editor/useEditorKeyboard'
import { useEditorOpen } from '../../editor/useEditorOpen'
import type { SheetCellProps } from '../sheetModel'

export interface NumberCellProps extends SheetCellProps<number> {
  /** The unit drawn inside the field — `bpm`, `s`. */
  unit?: string
  /** The range a commit must fall in. Outside it Apply is withheld with the range named. */
  min?: number
  max?: number
  step?: number
  /** What the cell shows. Defaults to the value, formatted by [format], with the unit. */
  face?: ReactNode
  /** How the face and the read-out print a number — `128.0`, `2.5`. Defaults to `String`. */
  format?: (value: number) => string
  /** A line under the field — what the column writes to, where that is worth saying. */
  note?: ReactNode
}

/**
 * A number cell — a speed master's BPM and Start, later a template's Fade: `TextCell`'s shape with
 * the editor kit's `EditorField` inside (library-sheets plan §3.1).
 *
 * **It commits on Enter and Apply, not as it is typed**, like `TextCell` and unlike the level and
 * colour editors: the values it holds are set, not judged by eye as they move, and a BPM written
 * on every keystroke would retune a live clock through `1`, `12` and `128` on the way to 128. The
 * field's own draft rule still holds — an emptied box commits nothing — and the cell keeps the last
 * number the field parsed as its draft, cleared by an emptied box so Apply cannot land a number the
 * operator deleted.
 *
 * The range is **refused, not clamped**: a tempo of 400 typed into a 20–300 field is a mistake to
 * name, not a 300 to write. The popover is `TextCell`'s 288px.
 */
export const NumberCell = memo(function NumberCell({
  value,
  label,
  batchLabel,
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
  unit,
  min,
  max,
  step = 1,
  face,
  format = String,
  note,
}: NumberCellProps) {
  const [draft, setDraft] = useState<number | null>(value)
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

  const outOfRange = draft != null && ((min != null && draft < min) || (max != null && draft > max))
  const error =
    draft == null
      ? `Type a ${label.toLowerCase()}`
      : outOfRange
        ? `${label} is ${min ?? '…'}–${max ?? '…'}${unit ? ` ${unit}` : ''}`
        : null

  const commit = useCallback(() => {
    if (error != null || draft == null) return false
    onCommit(draft)
    return true
  }, [draft, error, onCommit])

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
            <span className="mx-1.5 truncate font-mono text-xs tabular-nums">
              {format(value)}
              {unit ? <span className="ml-1 text-muted-foreground">{unit}</span> : null}
            </span>
          )}
        </button>
      }
    >
      <div ref={contentRef} onKeyDown={onKeyDown} className="space-y-2">
        <EditorLabelLine subject={batchLabel} column={label} />
        <div className="space-y-1">
          <EditorLabel>{label}</EditorLabel>
          <EditorField
            label={label}
            unit={unit}
            min={min}
            max={max}
            step={step}
            value={value}
            onCommit={setDraft}
            onDraft={(raw) => {
              // An emptied box is no number: Apply must not land the one that was deleted.
              if (raw != null && raw.trim() === '') setDraft(null)
            }}
            seed={numericSeed(keyboardOpen)}
          />
        </div>
        <EditorReadout error={error}>
          {note != null ? <span>{note}</span> : null}
          {skipped ? <span data-editor-skipped>{skipped}</span> : null}
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
