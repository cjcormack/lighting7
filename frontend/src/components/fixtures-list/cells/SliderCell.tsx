import { memo, useCallback } from 'react'
import { Slider } from '@/components/ui/slider'
import type { CellResolution } from '../columns'
import type { CellBatch, CellCommit } from '../rowModel'
import type { CellValue } from '../useRowValues'
import { EditorSurface, type CellClickBehaviour } from '../../editor/EditorSurface'
import { EditorField } from '../../editor/EditorField'
import { EditorLabel } from '../../editor/EditorLabel'
import { EditorLabelLine } from '../../editor/EditorLabelLine'
import { EditorReadout } from '../../editor/EditorReadout'
import { headsLine, skippedLine } from '../../editor/editorCopy'
import { UNSET_CELL_TITLE, UnsetCellMark } from '../../editor/UnsetCellMark'
import { numericSeed, useEditorKeyboard } from '../../editor/useEditorKeyboard'
import { useEditorOpen } from '../../editor/useEditorOpen'

interface SliderCellOwnProps {
  value: Extract<CellValue, { kind: 'slider' }>
  resolutions: NonNullable<CellResolution>[]
  /**
   * The column's name — "Dimmer", "Zoom" — which titles the editor where it is a bottom sheet
   * and names the column on the popover's label line. See `EditorSurface`. Defaulted rather than
   * required because the value's `kind` cannot supply it (one `slider` cell is a dimmer and the
   * next is an iris) and two callers mount these components read-only, where the editor never
   * opens.
   */
  label?: string
  /**
   * What a commit from this cell lands on — the count for the label line, the skipped heads and
   * the ranges for the read-out. See `CellBatch`. Absent where the cell is mounted read-only
   * (`CueValueGrid`), and then this row alone.
   */
  batch?: CellBatch
  /** *Local*, or the focused Look's name — the label line's scope. */
  scopeLabel?: string
  /**
   * The current scope holds no value here: draw an em-dash instead of the fill bar, but keep
   * `value` as the editor's starting point so a busk begins where the rig is. See `UnsetCellMark`.
   */
  placeholder?: boolean
  /**
   * The cell cannot take an edit — the desk is unreachable, so the write would go nowhere.
   * A real `disabled` rather than the wrapper's `pointer-events-none` alone: that stops the
   * mouse and not the keyboard, and this trigger is tabbable.
   */
  disabled?: boolean
  /**
   * A released single-column marquee named this cell: open the editor without a click.
   * See `useEditorOpen`.
   */
  autoOpen?: boolean
  /** The container asked this editor to close — Set pressed again. See `useEditorOpen`. */
  autoClose?: boolean
  /** That open came from the bar's Set, so the editor is anchored there. See `useEditorOpen`. */
  anchorAtButton?: boolean
  /**
   * The auto-open came from a character typed at the grid, which lands in the number field as its
   * first keystroke. Focus is not its business — the field is focused however the editor was
   * opened. See `useEditorKeyboard`.
   */
  keyboardSeed?: string | null
  /**
   * Nothing is selected any more, so this editor's targets are gone with it — close.
   * See `useEditorOpen`.
   */
  selectionEmpty?: boolean
  onCommit: (commit: CellCommit) => void
  onBeginEdit: () => void
}

type SliderCellProps = SliderCellOwnProps & CellClickBehaviour

/** The cell's unit: a byte as the percent it reads on the grid. */
export function toPct(value: number): number {
  return Math.round((value / 255) * 100)
}

/** The field's unit back to the rig's: a percent as a byte, before the resolution's clamp. */
export function fromPct(pct: number): number {
  return Math.round((pct / 100) * 255)
}

/**
 * Display: compact fill bar + percentage; a group with mixed values renders a
 * min–max range bar and "lo–hi%". Edit: a slider + a **percent** field in the shared editor
 * surface, committing continuously while dragging (the ChannelSlider convention).
 *
 * **The field is in the cell's unit** (editor-kit plan D13): the grid reads *80%*, so the box
 * says 80 and the read-out says the byte — *204 of 255 · 0–255 on every head*. The DMX sheet's
 * `LevelCell` reads bytes and keeps them; one field, the host names the unit. The slider stays in
 * bytes, since that is the resolution the rig has and a drag wants every step of it.
 *
 * The popover is 288px (D17): `w-72`, measured in the app at 288px on 2026-09-22 (the popover's
 * `getBoundingClientRect`).
 */
export const SliderCell = memo(function SliderCell({
  value,
  resolutions,
  label = 'Value',
  batch,
  scopeLabel = 'Local',
  placeholder,
  disabled = false,
  autoOpen,
  autoClose,
  anchorAtButton,
  keyboardSeed,
  selectionEmpty,
  clickSelects,
  editorAnchorRef,
  onCommit,
  onBeginEdit,
}: SliderCellProps) {
  const first = resolutions[0]
  const range = first.kind === 'slider' ? { min: first.property.min, max: first.property.max } : { min: 0, max: 255 }
  const current = value.max

  const commit = useCallback(
    (raw: number) => {
      const clamped = Math.max(range.min, Math.min(range.max, Math.round(raw)))
      onCommit({ kind: 'slider', value: clamped })
    },
    [onCommit, range.min, range.max],
  )
  // The field parses; this cell clamps, because its bounds come from its resolution rather than
  // being a flat byte — and it converts, because the field is a percent (D13).
  const commitPct = useCallback((pct: number) => commit(fromPct(pct)), [commit])

  // Controlled, because the container has to be able to open this from outside — Enter over a
  // selection, or the bar's Set — which an uncontrolled Radix popover offers no door for. The
  // field's draft lives inside the content and is dropped with it on close, so there is nothing
  // for the open to reset.
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

  const display = value.isUniform ? `${toPct(value.min)}%` : `${toPct(value.min)}–${toPct(value.max)}%`

  // The read-out: the byte, and whether every head in the batch takes the same range of them.
  const heads = batch ?? { count: 1, skipped: 0, resolutions }
  const sliderHeads = heads.resolutions.filter((r) => r.kind === 'slider')
  const sameRange = sliderHeads.every((r) => r.property.min === range.min && r.property.max === range.max)
  const rangeLine =
    `${current} of 255 · ${range.min}–${range.max}` +
    (heads.count > 1 ? (sameRange ? ' on every head' : ' on the first head · ranges differ') : '')
  const skipped = skippedLine(heads.skipped, label.toLowerCase())

  return (
    <EditorSurface
      open={isOpen}
      onOpenChange={setOpen}
      title={label}
      contentClassName="w-72"
      onOpenAutoFocus={onOpenAutoFocus}
      triggerOpens={!clickSelects}
      // Only where the press was made — the bar's Set. Enter and a typed character are gestures
      // made at the selection, so their editor opens beside the cell.
      anchorRef={atButton ? editorAnchorRef : undefined}
      trigger={
        <button
          type="button"
          disabled={disabled}
          // **The whole of what a click does, in every mode.** Where a click selects, the trigger
          // is only an anchor and `onOpenChange` never sees a `true`; where it opens the editor
          // (`CueValueGrid`) it fires alongside that open, which is where this used to
          // live. Unconditional, and identical in all four cells, because the alternative was two
          // mechanisms for one contract — `ColourCell` already did it this way, and a fifth cell
          // modelled on either half could have double-fired or missed. See `CellClickBehaviour`.
          onClick={onBeginEdit}
          className="flex h-full w-full items-center gap-1.5 rounded text-left hover:bg-accent/50"
          title={placeholder ? UNSET_CELL_TITLE : undefined}
        >
          {placeholder ? (
            <UnsetCellMark />
          ) : (
            <>
              <span className="relative ml-1.5 h-1.5 min-w-6 flex-1 overflow-hidden rounded-full bg-muted">
                <span
                  className="absolute inset-y-0 rounded-full bg-primary"
                  style={
                    value.isUniform
                      ? { left: 0, width: `${toPct(value.min)}%` }
                      : { left: `${toPct(value.min)}%`, width: `${Math.max(toPct(value.max) - toPct(value.min), 2)}%` }
                  }
                />
              </span>
              <span className="mr-1.5 w-12 shrink-0 text-right text-xs tabular-nums text-muted-foreground">
                {display}
              </span>
            </>
          )}
        </button>
      }
    >
      {/* The wrapper is the editor's keyboard: Enter closes, comma steps between fields, and a
          keyboard-opened editor focuses the first of them. See `useEditorKeyboard`. */}
      <div ref={contentRef} onKeyDown={onKeyDown} className="space-y-2">
        <EditorLabelLine subject={headsLine(heads.count, scopeLabel)} column={label} />
        <div className="space-y-1">
          {/* The column's name, as `LevelCell` does: a Zoom editor's control is Zoom, not Level. */}
          <EditorLabel>{label}</EditorLabel>
          <div className="flex items-center gap-2.5">
            <Slider
              min={range.min}
              max={range.max}
              step={1}
              value={[current]}
              onValueChange={([next]) => commit(next)}
              className="flex-1"
            />
            <EditorField
              label={label}
              unit="%"
              min={0}
              max={100}
              value={toPct(current)}
              onCommit={commitPct}
              seed={numericSeed(keyboardOpen)}
              className="w-[72px] shrink-0"
            />
          </div>
        </div>
        <EditorReadout>
          <span>{rangeLine}</span>
          {skipped && <span>{skipped}</span>}
        </EditorReadout>
      </div>
    </EditorSurface>
  )
})
