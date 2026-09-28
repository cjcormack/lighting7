import { memo, useCallback, useMemo, useState } from 'react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { EditorSurface } from '../../editor/EditorSurface'
import { EditorField } from '../../editor/EditorField'
import { EditorFooter } from '../../editor/EditorFooter'
import { EditorLabel } from '../../editor/EditorLabel'
import { EditorLabelLine } from '../../editor/EditorLabelLine'
import { EditorReadout } from '../../editor/EditorReadout'
import { useEditorKeyboard } from '../../editor/useEditorKeyboard'
import { useEditorOpen } from '../../editor/useEditorOpen'
import type { SheetCellProps, SheetRow } from '../sheetModel'

/** A patched address as the cell holds it. */
export interface CellAddress {
  universe: number
  channel: number
  /** How many channels the head occupies from `channel`. */
  footprint: number
}

/** What the editor says a typed start would do to the batch. */
export interface AddressLanding {
  /** `Front PAR 1 → 1-007`, one per head that moves — drawn one to a line. */
  lines: string[]
  /** A collision or an overflow, named; null when every head lands clear. */
  error: string | null
}

export interface AddressCellProps extends SheetCellProps<CellAddress> {
  /**
   * The landing for a typed start over the batch — computed by the column, which knows the other
   * heads on the universe. Called with the batch rows in visible order.
   */
  landing: (rows: readonly SheetRow[], startChannel: number) => AddressLanding
  /** Draws the ring and the info glyph: this head overlaps another, named. */
  clash?: string | null
}

export function formatAddress(universe: number, channel: number): string {
  return `${universe}-${String(channel).padStart(3, '0')}`
}

/**
 * The patch list's Address cell: `universe-channel`, edited as a start channel
 * (CLAUDE.md §Sheet kit).
 *
 * **Consecutive is the batch default.** Set over N addresses lands them consecutively by
 * footprint from the typed one, in visible-row order — every desk surveyed patches a range that
 * way (Eos, Hog, MA3's Edit Patch) and setting four fixtures to one address is never what was
 * meant. The editor says where each head lands and names a collision **before Apply**, and
 * refuses to apply over one: the patch PUT does not check overlap today (only the POST does), so
 * the refusal here is the one there is.
 *
 * The universe is on the field's label and not editable: the PUT cannot move a head to another
 * universe, and a field that took a number it could not send would be a lie. It commits on Apply
 * or Enter, never as it is typed — an address write rebuilds the fixture registry — which is why
 * it is one of the two editors with an `EditorFooter` (editor-kit plan D8); the landing lines are
 * the read-out's multi-line arm, and a collision disables Apply. The start channel is held here
 * as the number the field last gave (`EditorField` parses and this cell ranges it); an emptied box
 * is read through the field's `onDraft` and refuses Apply, as the old string draft did. The popover is 288px
 * (D17). `w-72`, measured in the app at 288px on 2026-09-22 (the popover's `getBoundingClientRect`).
 */
export const AddressCell = memo(function AddressCell({
  value,
  label,
  batchCount,
  batchLabel,
  skipped,
  batchRows,
  disabled,
  autoOpen,
  autoClose,
  anchorAtButton,
  keyboardSeed,
  selectionEmpty,
  editorAnchorRef,
  onBeginEdit,
  onCommit,
  landing,
  clash,
}: AddressCellProps) {
  const [start, setStart] = useState(value.channel)
  // The box has been emptied. `EditorField` commits nothing for an empty box, so `start` alone
  // would keep the last number and Apply would land it — a number the operator deleted precisely
  // to abandon. Read through `onDraft`, which also says when the draft is dropped on blur.
  const [blank, setBlank] = useState(false)
  const reset = useCallback(() => {
    setStart(value.channel)
    setBlank(false)
  }, [value.channel])
  const { isOpen, setOpen, keyboardOpen, atButton } = useEditorOpen({
    autoOpen,
    autoClose,
    anchorAtButton,
    keyboardSeed,
    disabled,
    selectionEmpty,
    onOpen: reset,
  })

  const validStart = !blank && Number.isInteger(start) && start >= 1 && start <= 512
  const plan = useMemo(
    () => (isOpen && validStart ? landing(batchRows(), start) : null),
    [batchRows, isOpen, landing, start, validStart],
  )
  const error = blank ? 'Start channel cannot be empty' : !validStart ? 'Start channel is 1–512' : (plan?.error ?? null)

  const commit = useCallback(() => {
    if (error) return false
    onCommit({ ...value, channel: start })
    return true
  }, [error, onCommit, start, value])

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
          <span className={cn('mx-1.5 truncate font-mono text-xs font-medium tabular-nums', clash && 'text-destructive')}>
            {formatAddress(value.universe, value.channel)}
          </span>
        </button>
      }
    >
      <div ref={contentRef} onKeyDown={onKeyDown} className="space-y-2">
        <EditorLabelLine subject={batchCount > 1 ? `${batchLabel} · visible order` : batchLabel} column={label} />
        <div className="space-y-1">
          <EditorLabel>
            Start channel ·{' '}
            <span className="font-mono normal-case tracking-normal">u{value.universe}</span>
          </EditorLabel>
          <EditorField
            label="Start channel"
            min={1}
            max={512}
            value={start}
            onCommit={(next) => setStart(Math.round(next))}
            onDraft={(raw) => setBlank(raw != null && raw.trim() === '')}
            // The character that opened the editor, a digit only — a `.` is no start channel.
            seed={keyboardOpen && /^[0-9]$/.test(keyboardOpen) ? keyboardOpen : null}
            className="w-24"
          />
        </div>
        <EditorReadout lines={plan?.lines} error={error}>
          <span>
            Channels {validStart ? `${start}–${Math.min(512, start + value.footprint - 1)}` : '…'} on universe{' '}
            {value.universe}
          </span>
          {skipped && <span data-editor-skipped>{skipped}</span>}
        </EditorReadout>
        <EditorFooter
          note={batchCount > 1 ? 'Consecutive by footprint across the selection' : undefined}
        >
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
