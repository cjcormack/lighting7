import { memo, type ReactNode } from 'react'
import { EditorSurface } from '@/components/editor/EditorSurface'
import { EditorLabelLine } from '@/components/editor/EditorLabelLine'
import { numericSeed, useEditorKeyboard } from '@/components/editor/useEditorKeyboard'
import { useEditorOpen } from '@/components/editor/useEditorOpen'
import { TextCell } from '@/components/sheet/cells/TextCell'
import type { SheetCellProps, SheetRow } from '@/components/sheet/sheetModel'
import { cueNumberProblem } from '@/lib/cueNumber'
import { RenumberForm } from './RenumberCuesButton'

/** A renumber over several Cue # cells, as the column's `write` receives it. */
export interface CueRenumberCommit {
  renumber: { start: number; step: number }
}

/** What the Cue # column commits: a typed number over one cell, a renumber over several. */
export type CueNumberCommit = string | CueRenumberCommit

export function isRenumberCommit(value: unknown): value is CueRenumberCommit {
  return typeof value === 'object' && value != null && 'renumber' in value
}

export interface CueNumberCellProps extends SheetCellProps<CueNumberCommit> {
  value: string
  face: ReactNode
  /** The batch's default *First*: the first cue's whole number, read off the batch rows. */
  defaultStart: (rows: readonly SheetRow[]) => string
}

/**
 * The cue sheet's Cue # cell. Over one cell it is the number field, a `TextCell`; over several it is
 * the renumber form, *First* seeded with what was typed at the grid, else the first cue's whole
 * number, committed as one renumber of the batch in stack order.
 */
export const CueNumberCell = memo(function CueNumberCell(props: CueNumberCellProps) {
  if (props.batchCount > 1) return <RenumberCell {...props} />
  const { defaultStart: _defaultStart, onCommit, ...text } = props
  return (
    <TextCell
      {...text}
      onCommit={onCommit}
      mono
      allowEmpty
      placeholder="14.5"
      validate={cueNumberProblem}
    />
  )
})

function RenumberCell({
  label,
  batchCount,
  batchLabel,
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
  face,
  defaultStart,
}: CueNumberCellProps) {
  const { isOpen, setOpen, keyboardOpen, atButton } = useEditorOpen({
    autoOpen,
    autoClose,
    anchorAtButton,
    keyboardSeed,
    disabled,
    selectionEmpty,
  })
  // The kit's Enter prevents the browser's own submit, so it submits the form itself.
  const { contentRef, onKeyDown, onOpenAutoFocus } = useEditorKeyboard({
    onDone: () => contentRef.current?.querySelector('form')?.requestSubmit(),
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
          {face}
        </button>
      }
    >
      <div ref={contentRef} onKeyDown={onKeyDown} className="space-y-2">
        <EditorLabelLine subject={batchLabel} column={label} />
        {isOpen && (
          <RenumberForm
            count={batchCount}
            initialStart={numericSeed(keyboardOpen) ?? defaultStart(batchRows())}
            onCancel={() => setOpen(false)}
            onSubmit={(start, step) => {
              onCommit({ renumber: { start, step } })
              setOpen(false)
            }}
          />
        )}
      </div>
    </EditorSurface>
  )
}
