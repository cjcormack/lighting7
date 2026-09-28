import { memo, useCallback, useMemo, useState } from 'react'
import type { TemplateTarget } from '@/api/templatesApi'
import { NewTemplateFromSelectionSheet } from '@/components/programmer/NewTemplateFromSelectionSheet'
import { useRailTabClaim } from '@/components/programmer/railTab'
import { ColourPickerPopover } from '../../fixtures/ColourPickerPopover'
import type { CellResolution } from '../columns'
import type { CellBatch, CellCommit, WriteTarget } from '../rowModel'
import type { CellValue } from '../useRowValues'
import type { ColourRecentSource } from '../../editor/ColourEditor'
import { EditorLabelLine } from '../../editor/EditorLabelLine'
import { useEditorCramped, type CellClickBehaviour } from '../../editor/EditorSurface'
import { headsLine } from '../../editor/editorCopy'
import { UNSET_CELL_TITLE, UnsetCellMark } from '../../editor/UnsetCellMark'
import { useEditorOpen } from '../../editor/useEditorOpen'

interface ColourCellOwnProps {
  value: Extract<CellValue, { kind: 'colour' }>
  resolutions: NonNullable<CellResolution>[]
  /** The column's name, titling the editor where it is a bottom sheet and naming it on the label line. See `SliderCell`. */
  label?: string
  /**
   * What a commit from this cell lands on — the count for the label line, the targets for the
   * emitter union, the hidden appearance leaves and the template targets Save and Recent land on.
   * See `CellBatch`. Absent where the cell is mounted read-only (`CueValueGrid`), and then this
   * row alone.
   */
  batch?: CellBatch
  /** *Local*, or the focused Look's name — the label line's scope. */
  scopeLabel?: string
  /**
   * The project the marquee's heads belong to — what the editor's leaves read the patch list of,
   * what Recent lists templates from and what *Save as template…* records into. The container
   * passes it for the **programmer alone**: the two plain lists deliberately draw no template
   * strip (§List shell), and Save records from the programmer, so their cells get a disabled Save,
   * no Recent and no leaves — as does a read-only mount or a test, which pass none.
   */
  projectId?: number
  /**
   * The editor's *Spread…*: hand this colour's RGB to the selection bar's Spread panel as *From*
   * (the busk `SpreadSeed` — RGB only, no emitter travels) and close this editor, since the panel
   * opens at the bar. Absent, the button is drawn inert — a read-only mount, a test.
   */
  onSpread?: (from: { r: number; g: number; b: number }) => void
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
   * The auto-open came from a character typed at the grid, which lands in the R box as its first
   * keystroke. Focus is not its business — R is focused however the editor was opened. See
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

type ColourCellProps = ColourCellOwnProps & CellClickBehaviour

/** The Recent chips are the programmer's row C strip on a desk; only the bottom sheet, which has folded that strip away, draws them here (editor-kit plan D11). */
const RECENT_FORMS: ColourRecentSource['forms'] = ['bottom-sheet']
const COLOUR_FAMILY = ['COLOUR'] as const

/**
 * Where a template press or record lands for these targets: the fixtures they stand for, deduped
 * — an element target folded onto its parent, since the template route resolves keys against the
 * patch and would drop an element key silently (`templateTargetsFor`'s rule, over the batch's
 * targets rather than the rows, which a cell never sees).
 */
export function templateTargetsOf(targets: readonly WriteTarget[]): TemplateTarget[] {
  const seen = new Set<string>()
  const out: TemplateTarget[] = []
  for (const target of targets) {
    const key = target.fixtureKey ?? target.key
    if (seen.has(key)) continue
    seen.add(key)
    out.push({ type: 'fixture', key })
  }
  return out
}

const NO_TARGETS: readonly WriteTarget[] = []

const hasColourDescriptor = (properties: readonly { type: string }[]) => properties.some((p) => p.type === 'colour')

/**
 * The batch's targets as a **colour commit** lands on them, which is what the editor counts,
 * reads and mounts leaves for: a head with its own colour descriptor as itself, a fixture whose
 * colour lives on its cells as one target per cell (named by its parent and position, the shape
 * `rowWriteTargets` stamps on an element row), and a head with no RGB colour dropped. That is
 * `resolveTargetCells`' fan-out narrowed to what `commitMatchesResolution` accepts: a dimmer-only
 * par swept up by a geometric marquee resolves nothing and is not counted among the heads that
 * "take RGB only", and a **colour-wheel** head — which the column resolves to a `colour-setting`
 * cell the batch counts, and a colour commit refuses — is not counted either. So this list's
 * length, not `CellBatch.count`, is what the label line says too: both lines count the heads the
 * commit reaches.
 */
export function colourTargetsOf(targets: readonly WriteTarget[]): WriteTarget[] {
  const out: WriteTarget[] = []
  for (const target of targets) {
    if (hasColourDescriptor(target.properties)) {
      out.push(target)
      continue
    }
    ;(target.elements ?? []).forEach((element, index) => {
      if (hasColourDescriptor(element.properties)) {
        out.push({ key: element.key, properties: element.properties, fixtureKey: target.key, cellIndex: index })
      }
    })
  }
  return out
}

/**
 * Swatch + RGB readout; a non-uniform group shows a "Mixed" badge over the
 * averaged swatch. Editing reuses ColourPickerPopover (pure props), through the shared
 * cell-editor surface — every change commits immediately, which is the app's live-edit
 * convention.
 *
 * **The editor is `ColourEditor` over the marquee's column** (editor-kit plan D10, D12): the label
 * line (*4 heads · Local*, the column on the right) in the popover form only, the emitter rows as
 * the batch's **union** read off its colour descriptors (a head without the emitter takes no byte —
 * `useCellWriters` folds an undeliverable white into RGB), the read-out counting them, Pick over the
 * batch's heads through the editor's own hidden leaves, *Save as template…* opening
 * `NewTemplateFromSelectionSheet` with Colour answered over the marquee's fixtures, and Recent in
 * the bottom sheet alone, and *Spread…* handing the colour to row C's Spread panel as *From*.
 * The targets the editor gets are the batch's as a colour commit lands on them
 * (`colourTargetsOf`): one per RGB colour cell, and the label line counts that list rather than
 * `batch.count`, which also counts a colour-wheel head the commit refuses.
 *
 * The save sheet is mounted only once Save has been pressed on this cell, and stays mounted from
 * then on: mounting it on every cell would put a dialog's hooks under thirty swatches, and mounting
 * it only while open would cut its slide-out. It sits outside the popover, because a press inside
 * the sheet is an outside press to the popover — which closes, as it should, the moment the sheet
 * takes focus.
 */
export const ColourCell = memo(function ColourCell({
  value,
  resolutions,
  label = 'Colour',
  batch,
  scopeLabel = 'Local',
  projectId,
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
}: ColourCellProps) {
  // Driven from here so the container's request (Enter over a selection, or the bar's Set) can
  // open it: the picker keeps its own state when no `open` is passed, and the other two call
  // sites still leave it to.
  const { isOpen, setOpen, keyboardOpen, atButton } = useEditorOpen({
    autoOpen,
    autoClose,
    anchorAtButton,
    keyboardSeed,
    disabled,
    selectionEmpty,
  })
  // The colour editor is the only one of the four tall enough to run out of room, so it alone has
  // a compact layout. Keyed on the viewport's **height** rather than on which form it is in: a
  // short desktop window is still a popover, and there the full layout clips off the top of the
  // screen with nothing said. Asked here rather than inside the picker, because the picker has two
  // other callers that are not cell editors at all. It reads the same shared store
  // `EditorSurface` uses, so this is a Set entry and not a second `matchMedia`.
  const compact = useEditorCramped()

  // **The rail's Colour tab claims this column's double click** (editor-kit plan session 4, call
  // 9), as it claims Enter, Set and a typed character in the container: while it is open it is this
  // column's editor, so the gesture lands there — R focused — rather than opening a second colour
  // editor over the same marquee. Only where a click selects: that is the programmer's grid, the one
  // surface with a rail; `CueValueGrid` opens on a click and has none.
  const railClaim = useRailTabClaim()
  const claimOpen = clickSelects && railClaim?.tab === 'colour' ? railClaim.focusColour : null
  // Stable while the claim and the opener are: the picker threads it into its keyboard hook's
  // `onDone`, which would otherwise be rebuilt on every render of this memoised cell.
  const onOpenChange = useCallback(
    (next: boolean) => {
      if (next && claimOpen != null) {
        claimOpen('')
        return
      }
      setOpen(next)
    },
    [claimOpen, setOpen],
  )

  // The batch's union: a head in the marquee "has" an emitter when any of its colour descriptors
  // does — the picker then offers the row, and heads without the channel skip it at write time.
  // With no batch (a read-only mount) the row's own resolutions stand in.
  const heads = batch ?? { count: 1, skipped: 0, resolutions }
  const targets = useMemo(() => colourTargetsOf(heads.targets ?? NO_TARGETS), [heads.targets])
  const hasWhite = heads.resolutions.some((r) => r.kind === 'colour' && r.property.whiteChannel != null)
  const hasAmber = heads.resolutions.some((r) => r.kind === 'colour' && r.property.amberChannel != null)
  const hasUv = heads.resolutions.some((r) => r.kind === 'colour' && r.property.uvChannel != null)

  const templateTargets = useMemo(() => templateTargetsOf(targets), [targets])
  const recent = useMemo<ColourRecentSource | undefined>(
    () => (projectId == null ? undefined : { projectId, targets: templateTargets, localFamilies: COLOUR_FAMILY, forms: RECENT_FORMS }),
    [projectId, templateTargets],
  )

  const [saveMounted, setSaveMounted] = useState(false)
  const [saving, setSaving] = useState(false)
  const onSave =
    projectId == null
      ? undefined
      : () => {
          setSaveMounted(true)
          setSaving(true)
        }

  return (
    <>
      <ColourPickerPopover
        open={isOpen}
        onOpenChange={onOpenChange}
        r={value.r}
        g={value.g}
        b={value.b}
        w={value.w}
        a={value.a}
        uv={value.uv}
        combinedCss={value.combinedCss}
        hasWhiteChannel={hasWhite}
        hasAmberChannel={hasAmber}
        hasUvChannel={hasUv}
        targets={targets}
        projectId={projectId}
        recent={recent}
        // The heads a colour commit reaches — `targets`, not `batch.count`, which also counts a
        // colour-wheel head the commit refuses; the read-out counts the same list.
        labelLine={<EditorLabelLine subject={headsLine(batch == null ? 1 : targets.length, scopeLabel)} column={label} />}
        onSave={onSave}
        // *Spread…* opens the row C panel with this colour as From — the busk hand-over, RGB only —
        // and closes this editor, because the panel opens at the bar and not here.
        onSpread={
          onSpread == null
            ? undefined
            : (channels) => {
                setOpen(false)
                onSpread({ r: channels.r, g: channels.g, b: channels.b })
              }
        }
        // The footer's verbs act on a batch. A cell mounted with none — the cue grid's read-only
        // colour, whose trigger is still tabbable — has nothing for Pick to read or Save to record,
        // so it draws no footer rather than an enabled Pick that toasts.
        footer={batch != null}
        // The grid cell's only editor, so this is where the typed R/G/B and emitter boxes belong
        // (`PD-COLOUR-EDITOR-INPUTS`). The two property visualizers leave it off: they draw their own
        // channel bank beside the swatch already.
        channelFields
        // Same reasoning, same two exempt callers: a grid cell's editor has nowhere good to float on
        // a portrait phone, and the two property visualizers do. See `sheetWhenNarrow`.
        sheetWhenNarrow
        title={label}
        compact={compact}
        // R is focused on open and Enter closes; a character typed at the grid arrives there as its
        // first keystroke, which is all this carries. See `useEditorKeyboard`.
        keyboardOpen={keyboardOpen}
        // This cell's button already carried the click, so unlike the other three there is nothing
        // to add here beyond telling the surface to stop opening on it. See `CellClickBehaviour`.
        triggerOpens={!clickSelects}
        // Only where the press was made — the bar's Set; see the other three cells.
        editorAnchorRef={atButton ? editorAnchorRef : undefined}
        onColourChange={(r, g, b, w, a, uv) => onCommit({ kind: 'colour', r, g, b, w, a, uv })}
      >
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
          {/* A `Link2` glyph used to sit on top of this swatch when the cell's entries held a
              `ref:{uuid}`, and the palette's *name* replaced the RGB readout below when the row
              agreed on one colour. Both went with the `ref:` grammar in session 4; a cell lit by a
              Look layer is marked by the `Layers` corner glyph in `FixturesTable` instead, which is
              about composition rather than about the operator's own entry. */}
          {placeholder ? (
            <UnsetCellMark />
          ) : (
            <>
              <span
                className="ml-1.5 size-4 shrink-0 overflow-hidden rounded-sm border border-border"
                style={{ backgroundColor: value.combinedCss }}
              />
              <span className="mr-1.5 truncate text-xs tabular-nums text-muted-foreground">
                {value.isUniform ? `${value.r},${value.g},${value.b}` : 'Mixed'}
              </span>
            </>
          )}
        </button>
      </ColourPickerPopover>
      {saveMounted && projectId != null && (
        <NewTemplateFromSelectionSheet
          open={saving}
          onOpenChange={setSaving}
          projectId={projectId}
          families={COLOUR_FAMILY}
          targets={templateTargets}
        />
      )}
    </>
  )
})
