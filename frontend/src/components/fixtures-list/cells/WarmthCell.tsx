import { memo, useCallback, useState } from 'react'
import { Save, Waves } from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { PaletteTarget } from '@/api/palettesApi'
import { NewPaletteFromSelectionSheet } from '@/components/programmer/NewPaletteFromSelectionSheet'
import type { WhiteMixPropertyDescriptor } from '../../../store/fixtures'
import type { Rgb } from '../../../lib/whiteMix'
import type { CellResolution } from '../columns'
import type { CellBatch, CellCommit } from '../rowModel'
import type { CellValue } from '../useRowValues'
import { EditorSurface, type CellClickBehaviour } from '../../editor/EditorSurface'
import { EditorFooter } from '../../editor/EditorFooter'
import { EditorLabelLine } from '../../editor/EditorLabelLine'
import { WarmthEditor, warmthReading } from '../../editor/WarmthEditor'
import { CellSourceChip, type CellKey } from '../../fixtureSheet/CellSourceChip'
import { headsLine } from '../../editor/editorCopy'
import { UNSET_CELL_TITLE, UnsetCellMark } from '../../editor/UnsetCellMark'
import { useEditorKeyboard } from '../../editor/useEditorKeyboard'
import { useEditorOpen } from '../../editor/useEditorOpen'

export interface WarmthCellProps extends CellClickBehaviour {
  /** The colour the heads' whites make — a white mix reads as any colour head does. */
  value: Extract<CellValue, { kind: 'colour' }>
  resolutions: NonNullable<CellResolution>[]
  label?: string
  batch?: CellBatch
  scopeLabel?: string
  /** The programmer's project, for *Save as palette…*; absent, Save is drawn inert. */
  projectId?: number
  /** Where *Save as palette…* records: the batch's heads, as `ColourCell` names them. */
  paletteTargets?: readonly PaletteTarget[]
  /** *Spread…*, handed the colour the mix makes; absent, the button is drawn inert. */
  onSpread?: (from: { r: number; g: number; b: number }) => void
  placeholder?: boolean
  disabled?: boolean
  autoOpen?: boolean
  autoClose?: boolean
  anchorAtButton?: boolean
  keyboardSeed?: string | null
  selectionEmpty?: boolean
  onCommit: (commit: CellCommit) => void
  onBeginEdit: () => void
  sourceKeys?: readonly CellKey[]
}

/** True when every head this colour cell writes to is a warm/cold white pair. */
export function isWarmthOnly(resolutions: readonly NonNullable<CellResolution>[]): boolean {
  return resolutions.length > 0 && resolutions.every((r) => r.kind === 'white-mix')
}

/** The first warm/cold white pair among [resolutions], the scale a warmth is read and set on. */
export function firstWhiteMix(resolutions: readonly NonNullable<CellResolution>[]): WhiteMixPropertyDescriptor | undefined {
  for (const r of resolutions) if (r.kind === 'white-mix') return r.property
  return undefined
}

const COLOUR_FAMILY = ['COLOUR'] as const
const NO_PALETTE_TARGETS: readonly PaletteTarget[] = []

/**
 * The Colour column's editor for heads whose colour is a warm/cold white pair (venue-visit plan
 * D8) — the blinder's cells: `WarmthEditor`'s warmth and level rather than an RGB picker those
 * heads cannot follow. It commits a colour, the one the chosen mix makes, so the write, the Look row
 * and the readback are the colour column's own; the desk lands it as that mix again. The swatch is
 * the colour the whites make. *Save as palette…* records that colour, and *Spread…* hands it to the
 * Spread panel as *From*, as the RGB cell's footer does.
 *
 * Against several heads the first one's pair is the scale; a head with other whites lands on its
 * own nearest mix.
 */
export const WarmthCell = memo(function WarmthCell({
  value,
  resolutions,
  label = 'Colour',
  batch,
  scopeLabel = 'Local',
  projectId,
  paletteTargets = NO_PALETTE_TARGETS,
  onSpread,
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
}: WarmthCellProps) {
  const heads = batch ?? { count: 1, skipped: 0, resolutions }
  const mix = (firstWhiteMix(heads.resolutions) ?? firstWhiteMix(resolutions))!
  const colour: Rgb = [value.r, value.g, value.b]

  const onColourChange = useCallback(([r, g, b]: Rgb) => onCommit({ kind: 'colour', r, g, b }), [onCommit])

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

  const [saveMounted, setSaveMounted] = useState(false)
  const [saving, setSaving] = useState(false)

  return (
    <>
      <EditorSurface
        open={isOpen}
        onOpenChange={setOpen}
        title={label}
        contentClassName="w-72"
        onOpenAutoFocus={onOpenAutoFocus}
        triggerOpens={!clickSelects}
        anchorRef={atButton ? editorAnchorRef : undefined}
        trigger={
          <button
            type="button"
            disabled={disabled}
            onClick={onBeginEdit}
            className="flex h-full w-full items-center gap-1.5 rounded text-left hover:bg-accent/50"
            title={placeholder ? UNSET_CELL_TITLE : undefined}
          >
            {placeholder ? (
              <UnsetCellMark />
            ) : (
              <>
                <span
                  className="ml-1.5 size-4 shrink-0 overflow-hidden rounded-sm border border-border"
                  style={{ backgroundColor: value.combinedCss }}
                />
                <span className="mr-1.5 truncate text-xs tabular-nums text-muted-foreground">
                  {value.isUniform ? warmthReading(mix, colour) : 'Mixed'}
                </span>
              </>
            )}
          </button>
        }
      >
        <div ref={contentRef} onKeyDown={onKeyDown} className="space-y-2">
          <EditorLabelLine
            subject={headsLine(heads.count, scopeLabel)}
            column={label}
            chip={sourceKeys != null ? <CellSourceChip keys={sourceKeys} label={label} /> : undefined}
          />
          <WarmthEditor mix={mix} colour={colour} onColourChange={onColourChange} keyboardOpen={keyboardOpen} />
          {batch != null && (
            <EditorFooter
              save={
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="h-7 min-w-0 text-xs"
                  disabled={projectId == null || paletteTargets.length === 0}
                  title="Record the selection’s colour as a palette — the route to something a layer can track"
                  onClick={() => {
                    setSaveMounted(true)
                    setSaving(true)
                  }}
                >
                  <Save className="size-3.5" /> <span className="truncate">Save as palette…</span>
                </Button>
              }
            >
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="h-7 text-xs"
                disabled={onSpread == null}
                aria-label="Fan to a second colour…"
                title={onSpread == null ? 'No Fan panel to open from here' : 'Open Fan with this colour as From'}
                onClick={() => {
                  setOpen(false)
                  onSpread?.({ r: value.r, g: value.g, b: value.b })
                }}
              >
                <Waves className="size-3.5" /> Fan…
              </Button>
            </EditorFooter>
          )}
        </div>
      </EditorSurface>
      {saveMounted && projectId != null && (
        <NewPaletteFromSelectionSheet
          open={saving}
          onOpenChange={setSaving}
          projectId={projectId}
          families={COLOUR_FAMILY}
          targets={[...paletteTargets]}
        />
      )}
    </>
  )
})
