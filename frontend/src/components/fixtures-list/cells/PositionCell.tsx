import { memo, useCallback, useRef } from 'react'
import { annotatesDegrees, dmxToDegrees } from '@/lib/axisDegrees'
import type { CellResolution } from '../columns'
import type { CellBatch, CellCommit } from '../rowModel'
import type { CellValue } from '../useRowValues'
import { EditorSurface, type CellClickBehaviour } from '../../editor/EditorSurface'
import { EditorLabelLine } from '../../editor/EditorLabelLine'
import { EditorReadout } from '../../editor/EditorReadout'
import { headsLine, skippedLine } from '../../editor/editorCopy'
import { UNSET_CELL_TITLE, UnsetCellMark } from '../../editor/UnsetCellMark'
import { numericSeed, useEditorKeyboard } from '../../editor/useEditorKeyboard'
import { useEditorOpen } from '../../editor/useEditorOpen'
import { ValueFieldRow } from '../../editor/ValueFieldRow'

interface PositionCellOwnProps {
  value: Extract<CellValue, { kind: 'position' }>
  resolutions: NonNullable<CellResolution>[]
  /** The column's name, titling the editor where it is a bottom sheet. See `SliderCell`. */
  label?: string
  /** What a commit lands on — see `SliderCell` and `CellBatch`. */
  batch?: CellBatch
  /** *Local*, or the focused Look's name — the label line's scope. */
  scopeLabel?: string
  /** No value in the current scope — see `UnsetCellMark`. */
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
   * The auto-open came from a character typed at the grid, which lands in Pan as its first
   * keystroke. Focus is not its business — Pan is focused however the editor was opened. See
   * `useEditorKeyboard`.
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

type PositionCellProps = PositionCellOwnProps & CellClickBehaviour

type PositionResolution = Extract<NonNullable<CellResolution>, { kind: 'position' }>

/** The XY pad's side, in px — the cell's 16px thumbnail at a size a finger can use (D14). */
const PAD_SIZE = 120

/**
 * Mini crosshair pad + pan/tilt readout; edit via an **XY pad**, pan/tilt sliders **and typed
 * fields** in the shared editor surface, committing continuously. Writes drive the coarse channels
 * only (fine channels fold into the column and are left untouched).
 *
 * The two fields are what `pan,tilt` used to be: the deleted typed-value popover could take a
 * position as one line of text, and this editor could not take one at all — only a drag. So the
 * pair came here, as the *gesture* rather than the grammar: Pan is focused when the editor is
 * opened from the keyboard, comma steps to Tilt, Enter is done.
 *
 * **Degrees where the head annotates, bytes where it does not** (editor-kit plan D14). The pan
 * and tilt sliders of a mover carry `degMin` / `degMax` (10 of the 28 fixture models), and where
 * **every** head the edit lands on carries both, the fields and the sliders are in travel degrees
 * — the Spread endpoints' unit, so a spread and a set type the same thing — and the commit carries
 * `panDeg` / `tiltDeg` for `clampCommitToResolution` to resolve to **each head's own byte**, since
 * 270° is byte 128 on a 540° mover and 109 on a 630° one. A batch mixing annotated and silent
 * heads keeps bytes for all of them rather than two units in one editor; a head with no
 * annotation keeps bytes; the cell itself keeps its byte read-out either way.
 *
 * The popover is 288px (D17). `w-72`, measured in the app at 288px on 2026-09-22 (the popover's `getBoundingClientRect`).
 */
export const PositionCell = memo(function PositionCell({
  value,
  resolutions,
  label = 'Position',
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
}: PositionCellProps) {
  const first = resolutions[0]
  const ranges =
    first.kind === 'position'
      ? { panMin: first.panMin, panMax: first.panMax, tiltMin: first.tiltMin, tiltMax: first.tiltMax }
      : { panMin: 0, panMax: 255, tiltMin: 0, tiltMax: 255 }

  // The heads this edit lands on, and whether every one of them annotates both axes.
  const heads = batch ?? { count: 1, skipped: 0, resolutions }
  const positions = heads.resolutions.filter((r): r is PositionResolution => r.kind === 'position')
  const degrees =
    positions.length > 0 &&
    positions.every((r) => annotatesDegrees(r.panProperty) && annotatesDegrees(r.tiltProperty))
  const lead = positions[0]

  // Per-axis, exactly as the sliders are: sending the row's aggregate for the axis that did not
  // move would overwrite every batch target's value for it with one number.
  const commitPan = useCallback(
    (raw: number) =>
      onCommit({
        kind: 'position',
        pan: Math.max(ranges.panMin, Math.min(ranges.panMax, Math.round(raw))),
      }),
    [onCommit, ranges.panMin, ranges.panMax],
  )
  const commitTilt = useCallback(
    (raw: number) =>
      onCommit({
        kind: 'position',
        tilt: Math.max(ranges.tiltMin, Math.min(ranges.tiltMax, Math.round(raw))),
      }),
    [onCommit, ranges.tiltMin, ranges.tiltMax],
  )

  // The degree arms: the field's range is the lead head's annotation (a batch of movers may
  // declare 540° and 630°, and the editor shows one), the commit is the degree itself, and each
  // head's byte is resolved where the target descriptor is in hand. The clamp here is the field's
  // caller-clamp rule against the range it draws.
  const panDeg = degrees && lead ? axisDegrees(lead, 'pan') : null
  const tiltDeg = degrees && lead ? axisDegrees(lead, 'tilt') : null
  const commitPanDeg = useCallback(
    (raw: number) => {
      if (!panDeg) return
      onCommit({ kind: 'position', panDeg: Math.max(panDeg.min, Math.min(panDeg.max, Math.round(raw))) })
    },
    [onCommit, panDeg],
  )
  const commitTiltDeg = useCallback(
    (raw: number) => {
      if (!tiltDeg) return
      onCommit({ kind: 'position', tiltDeg: Math.max(tiltDeg.min, Math.min(tiltDeg.max, Math.round(raw))) })
    },
    [onCommit, tiltDeg],
  )
  // The pad writes both axes in one commit, in whichever unit the fields are in.
  const commitPad = useCallback(
    (nx: number, ny: number) => {
      if (panDeg && tiltDeg) {
        onCommit({
          kind: 'position',
          panDeg: Math.round(panDeg.min + nx * (panDeg.max - panDeg.min)),
          tiltDeg: Math.round(tiltDeg.min + ny * (tiltDeg.max - tiltDeg.min)),
        })
        return
      }
      onCommit({
        kind: 'position',
        pan: Math.round(ranges.panMin + nx * (ranges.panMax - ranges.panMin)),
        tilt: Math.round(ranges.tiltMin + ny * (ranges.tiltMax - ranges.tiltMin)),
      })
    },
    [onCommit, panDeg, tiltDeg, ranges.panMin, ranges.panMax, ranges.tiltMin, ranges.tiltMax],
  )

  // Controlled, because the container has to be able to open this from outside — Enter over a
  // selection, or the bar's Set — which an uncontrolled Radix popover offers no door for. The
  // fields' drafts live inside the content and go with it on close, so the open resets nothing.
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

  // The fields' values, in the fields' unit.
  const panShown = panDeg && lead?.panProperty ? Math.round(dmxToDegrees(value.pan, lead.panProperty) ?? value.pan) : value.pan
  const tiltShown =
    tiltDeg && lead?.tiltProperty ? Math.round(dmxToDegrees(value.tilt, lead.tiltProperty) ?? value.tilt) : value.tilt

  // The read-out: the bytes the rig holds, and the ranges they sit in.
  const sameRanges = positions.every(
    (r) => r.panMin === ranges.panMin && r.panMax === ranges.panMax && r.tiltMin === ranges.tiltMin && r.tiltMax === ranges.tiltMax,
  )
  // Degrees: the bytes the rig holds and the degree range they sit in, both bounds stated, so the
  // read-out names the same quantities the fields do. Bytes: the bytes and their ranges.
  const bytesLine = degrees
    ? `${value.pan} · ${value.tilt} of 255 · ${panDeg?.min}–${panDeg?.max}° · ${tiltDeg?.min}–${tiltDeg?.max}°`
    : `${value.pan} · ${value.tilt} · ${ranges.panMin}–${ranges.panMax} · ${ranges.tiltMin}–${ranges.tiltMax}`
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
              <span className="relative ml-1.5 size-4 shrink-0 rounded-sm border border-border bg-muted/50">
                <span
                  className="absolute size-1 -translate-x-1/2 -translate-y-1/2 rounded-full bg-primary"
                  style={{
                    left: `${value.panNormalized * 100}%`,
                    top: `${(1 - value.tiltNormalized) * 100}%`,
                  }}
                />
              </span>
              <span className="mr-1.5 truncate text-xs tabular-nums text-muted-foreground">
                {value.isUniform ? `${value.pan},${value.tilt}` : 'Mixed'}
              </span>
            </>
          )}
        </button>
      }
    >
      {/* The wrapper is the editor's keyboard: Enter closes, comma steps Pan → Tilt, and a
          keyboard-opened editor focuses Pan. See `useEditorKeyboard`. */}
      <div ref={contentRef} onKeyDown={onKeyDown} className="space-y-2">
        <EditorLabelLine subject={headsLine(heads.count, scopeLabel)} column={label} />
        <div className="flex items-start gap-3">
          {/* The thumb sits where the pad's drag would put it: on the degree scale in degree mode,
              since an annotated axis may run `inverted` and the byte-normalised position would
              then read the other way from the degree the drag writes. */}
          <XyPad
            x={panDeg ? (panShown - panDeg.min) / (panDeg.max - panDeg.min) : value.panNormalized}
            y={tiltDeg ? (tiltShown - tiltDeg.min) / (tiltDeg.max - tiltDeg.min) : value.tiltNormalized}
            onChange={commitPad}
          />
          <div className="min-w-0 flex-1 space-y-2">
            {panDeg ? (
              <ValueFieldRow
                label="Pan"
                unit="°"
                min={panDeg.min}
                max={panDeg.max}
                value={panShown}
                onChange={commitPanDeg}
                seed={numericSeed(keyboardOpen)}
              />
            ) : (
              <ValueFieldRow
                label="Pan"
                min={ranges.panMin}
                max={ranges.panMax}
                value={value.pan}
                onChange={commitPan}
                seed={numericSeed(keyboardOpen)}
              />
            )}
            {tiltDeg ? (
              <ValueFieldRow label="Tilt" unit="°" min={tiltDeg.min} max={tiltDeg.max} value={tiltShown} onChange={commitTiltDeg} />
            ) : (
              <ValueFieldRow label="Tilt" min={ranges.tiltMin} max={ranges.tiltMax} value={value.tilt} onChange={commitTilt} />
            )}
          </div>
        </div>
        <EditorReadout>
          <span>{bytesLine}</span>
          {heads.count > 1 && !sameRanges && <span>ranges differ</span>}
          {!value.isUniform && <span className="ml-auto">heads differ</span>}
          {skipped && <span>{skipped}</span>}
        </EditorReadout>
      </div>
    </EditorSurface>
  )
})

/** One annotated axis's degree range, low to high whichever way the descriptor wrote it. */
function axisDegrees(res: PositionResolution, axis: 'pan' | 'tilt'): { min: number; max: number } | null {
  const slider = axis === 'pan' ? res.panProperty : res.tiltProperty
  if (!annotatesDegrees(slider)) return null
  return { min: Math.min(slider.degMin, slider.degMax), max: Math.max(slider.degMin, slider.degMax) }
}

/**
 * The 120px pan/tilt pad: a drag writes both axes, as normalised fractions the cell maps into
 * whichever unit its fields are in. The pointer is captured so a drag that runs off the pad still
 * follows and still releases; `touch-none` keeps a finger from panning the sheet instead.
 *
 * Not a keyboard control — the two fields beside it are, and a slider apiece under them — so it
 * carries no role a screen reader would try to operate.
 */
function XyPad({ x, y, onChange }: { x: number; y: number; onChange: (nx: number, ny: number) => void }) {
  const ref = useRef<HTMLDivElement>(null)
  const dragging = useRef(false)
  const read = (e: React.PointerEvent<HTMLDivElement>) => {
    const rect = ref.current?.getBoundingClientRect()
    if (!rect) return
    const nx = clamp01((e.clientX - rect.left) / (rect.width || PAD_SIZE))
    const ny = clamp01(1 - (e.clientY - rect.top) / (rect.height || PAD_SIZE))
    onChange(nx, ny)
  }
  return (
    <div
      ref={ref}
      data-editor-xy-pad
      aria-hidden
      className="relative shrink-0 cursor-crosshair touch-none rounded-lg border border-border bg-muted/30 select-none"
      style={{
        width: PAD_SIZE,
        height: PAD_SIZE,
        backgroundImage:
          'linear-gradient(var(--border) 1px, transparent 1px), linear-gradient(90deg, var(--border) 1px, transparent 1px)',
        backgroundSize: '25% 25%',
      }}
      onPointerDown={(e) => {
        e.preventDefault()
        dragging.current = true
        e.currentTarget.setPointerCapture?.(e.pointerId)
        read(e)
      }}
      onPointerMove={(e) => {
        if (dragging.current) read(e)
      }}
      onPointerUp={() => {
        dragging.current = false
      }}
      onPointerCancel={() => {
        dragging.current = false
      }}
    >
      <span
        className="absolute size-3.5 -translate-x-1/2 -translate-y-1/2 rounded-full bg-primary shadow-[0_0_0_2px_var(--background)]"
        style={{ left: `${x * 100}%`, top: `${(1 - y) * 100}%` }}
      />
    </div>
  )
}

function clamp01(n: number): number {
  return Math.max(0, Math.min(1, n))
}
