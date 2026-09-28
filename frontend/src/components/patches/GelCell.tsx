import { memo, useCallback } from 'react'
import { findGel } from '@/data/gels'
import { EditorSurface } from '@/components/editor/EditorSurface'
import { EditorLabelLine } from '@/components/editor/EditorLabelLine'
import { EditorReadout } from '@/components/editor/EditorReadout'
import { useEditorKeyboard } from '@/components/editor/useEditorKeyboard'
import { useEditorOpen } from '@/components/editor/useEditorOpen'
import type { SheetCellProps } from '@/components/sheet/sheetModel'
import { GelPicker } from './GelPicker'

/**
 * The patch list's Gel cell — the patch editor's gel picker (`GelPicker`, shared with
 * `GelPickerField`) in the sheet kit's editor surface, so it takes the three forms, the double
 * click, the label line and the keyboard every other cell does (CLAUDE.md §Sheet kit). It was a
 * `TextCell` that took a typed code and refused anything not in the library, which asked the
 * operator to know Lee and Rosco's numbers by heart.
 *
 * The value is the gel's code, `''` for open white — the column's `write` reads either. A pick
 * commits and closes, as `OptionCell`'s does; a character typed at the grid seeds the search.
 * `w-72`, the patch editor's own popover floor (`min-w-72`).
 */
export const GelCell = memo(function GelCell({
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
}: SheetCellProps<string>) {
  const { isOpen, setOpen, keyboardOpen, atButton } = useEditorOpen({
    autoOpen,
    autoClose,
    anchorAtButton,
    keyboardSeed,
    disabled,
    selectionEmpty,
  })
  const { contentRef, onKeyDown, onOpenAutoFocus } = useEditorKeyboard({
    onDone: () => setOpen(false),
  })
  const pick = useCallback(
    (code: string | null) => {
      onCommit(code ?? '')
      setOpen(false)
    },
    [onCommit, setOpen],
  )
  const gel = findGel(value)

  return (
    <EditorSurface
      open={isOpen}
      onOpenChange={setOpen}
      title={label}
      contentClassName="w-72 p-0 overflow-hidden"
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
          {value ? (
            <span className="mx-1.5 flex min-w-0 items-center gap-1.5">
              <span
                className="size-3 shrink-0 rounded-sm border border-border/60"
                style={{ background: gel?.color ?? 'transparent' }}
                aria-hidden
              />
              <span className="truncate font-mono text-xs">{value}</span>
            </span>
          ) : (
            <span className="mx-1.5 text-xs text-muted-foreground/60">—</span>
          )}
        </button>
      }
    >
      <div ref={contentRef} onKeyDown={onKeyDown}>
        <EditorLabelLine subject={batchLabel} column={label} className="px-3 pt-2 pb-1.5" />
        <GelPicker value={value || null} onPick={pick} initialQuery={keyboardOpen ?? ''} />
        {skipped && (
          <EditorReadout className="border-t px-3 pt-1.5 pb-2">
            <span data-editor-skipped>{skipped}</span>
          </EditorReadout>
        )}
      </div>
    </EditorSurface>
  )
})
