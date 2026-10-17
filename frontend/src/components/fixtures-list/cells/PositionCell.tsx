import { memo, useCallback, useRef } from 'react'
import { CENTRE_MAX, CENTRE_MIN, clampCentre, dmxToCentre, formatCentre } from '@/lib/positionScale'
import type { CellResolution } from '../columns'
import type { CellBatch, CellCommit } from '../rowModel'
import type { CellValue } from '../useRowValues'
import { EditorSurface, type CellClickBehaviour } from '../../editor/EditorSurface'
import { EditorLabelLine } from '../../editor/EditorLabelLine'
import { CellSourceChip, type CellKey } from '../../fixtureSheet/CellSourceChip'
import { EditorReadout } from '../../editor/EditorReadout'
import { PaletteBands, usePaletteSave } from '../../editor/PaletteBands'
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
  /** The project, for the popup's Palettes band and *Save as palette…* — see `SliderCell`. */
  projectId?: number
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
  /**
   * The cell's `(head, property)` keys, for the source chip on the editor's label line
   * (fixture-fx-sheets plan D19). Given by the programmer grid where it draws ownership; absent,
   * no chip — the plain lists, a focused Look layer, `CueValueGrid`.
   */
  sourceKeys?: readonly CellKey[]
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
 * **Centre-relative, on every head** (venue-visit plan D3): the fields, the sliders and the pad
 * are `-128..127` with 0 at the centre of travel (`lib/positionScale.ts`), and the commit carries
 * `panCentre` / `tiltCentre` for `clampCommitToResolution` to resolve to **each head's own byte** —
 * the same fraction of its own travel, which is `128 + value` on every 0–255 axis this rig has. It
 * needs no degree annotation, so a batch never splits into two units; the read-out keeps the DMX
 * bytes underneath. It was degrees where every head annotated (editor-kit plan D14) until D3.
 *
 * The popover is 288px (D17). `w-72`, measured in the app at 288px on 2026-09-22 (the popover's `getBoundingClientRect`).
 */
export const PositionCell = memo(function PositionCell({
  value,
  resolutions,
  label = 'Position',
  batch,
  scopeLabel = 'Local',
  projectId,
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
  sourceKeys,
}: PositionCellProps) {
  const first = resolutions[0]
  const ranges =
    first.kind === 'position'
      ? { panMin: first.panMin, panMax: first.panMax, tiltMin: first.tiltMin, tiltMax: first.tiltMax }
      : { panMin: 0, panMax: 255, tiltMin: 0, tiltMax: 255 }

  // The heads this edit lands on.
  const heads = batch ?? { count: 1, skipped: 0, resolutions }
  const positions = heads.resolutions.filter((r): r is PositionResolution => r.kind === 'position')
  const panRange = { min: ranges.panMin, max: ranges.panMax }
  const tiltRange = { min: ranges.tiltMin, max: ranges.tiltMax }

  // Per-axis, exactly as the sliders are: sending the row's aggregate for the axis that did not
  // move would overwrite every batch target's value for it with one number. The value is clamped
  // to the scale here (the field's caller-clamp rule) and each head's byte is resolved where the
  // target descriptor is in hand.
  const commitPan = useCallback(
    (raw: number) => onCommit({ kind: 'position', panCentre: clampCentre(raw) }),
    [onCommit],
  )
  const commitTilt = useCallback(
    (raw: number) => onCommit({ kind: 'position', tiltCentre: clampCentre(raw) }),
    [onCommit],
  )
  // The pad writes both axes in one commit.
  const commitPad = useCallback(
    (nx: number, ny: number) =>
      onCommit({
        kind: 'position',
        panCentre: clampCentre(CENTRE_MIN + nx * (CENTRE_MAX - CENTRE_MIN)),
        tiltCentre: clampCentre(CENTRE_MIN + ny * (CENTRE_MAX - CENTRE_MIN)),
      }),
    [onCommit],
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

  // The fields' values, on the scale.
  const panShown = dmxToCentre(value.pan, panRange)
  const tiltShown = dmxToCentre(value.tilt, tiltRange)

  // The read-out: the DMX bytes underneath the scale, and the ranges they sit in.
  const sameRanges = positions.every(
    (r) => r.panMin === ranges.panMin && r.panMax === ranges.panMax && r.tiltMin === ranges.tiltMin && r.tiltMax === ranges.tiltMax,
  )
  const bytesLine =
    ranges.panMin === 0 && ranges.panMax === 255 && ranges.tiltMin === 0 && ranges.tiltMax === 255
      ? `DMX ${value.pan} · ${value.tilt}`
      : `DMX ${value.pan} · ${value.tilt} · ${ranges.panMin}–${ranges.panMax} · ${ranges.tiltMin}–${ranges.tiltMax}`
  const skipped = skippedLine(heads.skipped, label.toLowerCase())
  const bandTargets = heads.targets ?? NO_TARGETS
  const save = usePaletteSave(projectId, 'POSITION', bandTargets)

  return (
    <>
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
                {value.isUniform ? `${formatCentre(panShown)},${formatCentre(tiltShown)}` : 'Mixed'}
              </span>
            </>
          )}
        </button>
      }
    >
      {/* The wrapper is the editor's keyboard: Enter closes, comma steps Pan → Tilt, and a
          keyboard-opened editor focuses Pan. See `useEditorKeyboard`. */}
      <div ref={contentRef} onKeyDown={onKeyDown} className="space-y-2">
        <EditorLabelLine
          subject={headsLine(heads.count, scopeLabel)}
          column={label}
          chip={sourceKeys != null ? <CellSourceChip keys={sourceKeys} label={label} /> : undefined}
        />
        <PaletteBands projectId={projectId} family="POSITION" property="position" targets={bandTargets} onSave={save.onSave}>
          <div className="flex items-start gap-3">
            <XyPad
              x={(panShown - CENTRE_MIN) / (CENTRE_MAX - CENTRE_MIN)}
              y={(tiltShown - CENTRE_MIN) / (CENTRE_MAX - CENTRE_MIN)}
              onChange={commitPad}
            />
            <div className="min-w-0 flex-1 space-y-2">
              <ValueFieldRow
                label="Pan"
                min={CENTRE_MIN}
                max={CENTRE_MAX}
                value={panShown}
                onChange={commitPan}
                seed={numericSeed(keyboardOpen)}
              />
              <ValueFieldRow label="Tilt" min={CENTRE_MIN} max={CENTRE_MAX} value={tiltShown} onChange={commitTilt} />
            </div>
          </div>
          <EditorReadout>
            <span>{bytesLine}</span>
            {heads.count > 1 && !sameRanges && <span>ranges differ</span>}
            {!value.isUniform && <span className="ml-auto">heads differ</span>}
            {skipped && <span>{skipped}</span>}
          </EditorReadout>
        </PaletteBands>
      </div>
    </EditorSurface>
    {save.sheet}
    </>
  )
})

const NO_TARGETS: NonNullable<CellBatch['targets']> = []

/**
 * The 120px pan/tilt pad: a drag writes both axes, as normalised fractions the cell maps into
 * the scale its fields are in. The pointer is captured so a drag that runs off the pad still
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
