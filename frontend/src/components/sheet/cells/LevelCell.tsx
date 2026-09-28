import { memo, useCallback, type ReactNode } from 'react'
import { Slider } from '@/components/ui/slider'
import { EditorSurface } from '../../editor/EditorSurface'
import { EditorField } from '../../editor/EditorField'
import { EditorLabel } from '../../editor/EditorLabel'
import { EditorLabelLine } from '../../editor/EditorLabelLine'
import { EditorReadout } from '../../editor/EditorReadout'
import { numericSeed, useEditorKeyboard } from '../../editor/useEditorKeyboard'
import { useEditorOpen } from '../../editor/useEditorOpen'
import type { SheetCellProps } from '../sheetModel'

export interface LevelCellProps extends SheetCellProps<number> {
  /** What the cell shows — the DMX sheet's three lines, a fill bar. */
  face: ReactNode
  min?: number
  max?: number
}

/**
 * A level cell — one number in a range, a slider and a box, committing **as it is edited**
 * (CLAUDE.md §Sheet kit). The DMX sheet's raw 0–255 value.
 *
 * The same editor `SliderCell` draws for a fixture's dimmer, without the fixture: `SliderCell`
 * takes a `CellResolution` for its range and a `CellValue` for its face, and this takes a number
 * and a face the column draws. Committing live is the ChannelSlider convention — the value drives
 * a channel the operator judges against the rig.
 *
 * **The field stays in bytes** (editor-kit plan D13): the DMX sheet reads *204*, so its editor
 * types 204, where the programmer's dimmer cell reads *80%* and types 80. One anatomy, the host
 * names the unit; the percent is this editor's read-out. The popover is 288px (D17): `w-72`,
 * measured in the app at 288px on 2026-09-22 (the popover's `getBoundingClientRect`).
 */
export const LevelCell = memo(function LevelCell({
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
  face,
  min = 0,
  max = 255,
}: LevelCellProps) {
  // The field parses (`EditorField`); the clamp stays here.
  const commit = useCallback(
    (raw: number) => onCommit(Math.max(min, Math.min(max, Math.round(raw)))),
    [max, min, onCommit],
  )
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
        <div className="space-y-1">
          <EditorLabel>{label}</EditorLabel>
          <div className="flex items-center gap-2.5">
            <Slider
              min={min}
              max={max}
              step={1}
              value={[value]}
              onValueChange={([next]) => commit(next)}
              className="flex-1"
            />
            <EditorField
              label={label}
              min={min}
              max={max}
              value={value}
              onCommit={commit}
              seed={numericSeed(keyboardOpen)}
              className="w-[72px] shrink-0"
            />
          </div>
        </div>
        <EditorReadout>
          <span>
            {min}–{max} · {Math.round(((value - min) / Math.max(1, max - min)) * 100)}%
          </span>
          {skipped && <span data-editor-skipped>{skipped}</span>}
        </EditorReadout>
      </div>
    </EditorSurface>
  )
})
