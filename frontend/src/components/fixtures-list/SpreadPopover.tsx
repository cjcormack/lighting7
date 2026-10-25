import { useCallback, useMemo, type ReactNode } from 'react'
import { Layers } from 'lucide-react'
import { toast } from 'sonner'
import { ColourEditor } from '../editor/ColourEditor'
import { EditorLabelLine } from '../editor/EditorLabelLine'
import { headsLine } from '../editor/editorCopy'
import { SpreadPanel, type IntentSpreadPlan, type RawSpreadPlan, type SpreadPlan, type SpreadSeed } from '../editor/SpreadPanel'
import { useFocusedPaletteLayer } from '../programmer/FocusedPaletteLayer'
import { useLookRowStore } from '../programmer/LookRowStore'
import { landValuesInLook } from '../programmer/landValuesInLook'
import { useProgrammerScope } from '../programmer/ProgrammerScope'
import { useEditorContext } from '../programmer/EditorContext'
import { useCueRowStore } from '../cues/editor/CueRowStore'
import { getProgrammerFadeMs } from '../../lib/programmerFade'
import { parseProgrammerValue } from '../../lib/programmerValue'
import { usePressFamilies } from '../../store/selection'
import { useSpreadMutation } from '../../store/programmerOps'
import { usePaletteListQuery } from '../../store/palettes'
import { isSpreadColourPalette } from '../fx/FxColourPalettes'
import { COLUMN_DEFS, cellFamilies, columnFamily, resolveCell } from './columns'
import {
  clampCommitToResolution,
  commitMatchesResolution,
  spreadCellCount,
  spreadAttributesOffered,
  spreadAttributeForColumn,
  spreadTargetsOf,
  targetFamilies,
  type WriteTarget,
} from './rowModel'
import { applyPlannedWrite, lookPositionOf, useCellWriters } from './useCellWriters'
import type { ColumnKey } from './columns'
import type { AttributeFamily } from '../../lib/attributeFamily'
import type { LocateTarget } from '../../store/locate'
import type { SpreadBase } from '../../store/programmerOps'

/** The rows' own positions, as a fan's starting points: each head the row holds a `pan,tilt` for. */
function fanBasesFrom(heads: readonly LocateTarget[], valueOf: (key: string) => string | undefined): SpreadBase[] {
  return heads.flatMap((head) => {
    const value = valueOf(head.key)
    return value != null && parseProgrammerValue(value)?.kind === 'position' ? [{ target: { type: 'fixture', key: head.key }, value }] : []
  })
}

/** Columns a spread can drive: the template vocabulary's, plus Speed as raw bytes (D15). Gobo and Prism build no plan. */
const SPREAD_COLUMNS: ColumnKey[] = ['dimmer', 'colour', 'position', 'zoom', 'focus', 'iris', 'strobe', 'speed']

/** The title's list of them, in the words the button has always used. */
const DRIVABLE_HINT = 'dimmer, colour, position, zoom, focus, iris, strobe or speed'

const INTENT_TOAST = 'spread-popover-intent-answer'

/**
 * Whether a desk answered the interpolated **intent** rather than a literal — an older desk, plan
 * §6. Asked by shape, not by the literal parser alone: `parseProgrammerValue` splits on `;` and
 * tests only the head, so `#FF0000;policy=extract` would parse as a colour and land the intent in
 * a Look row. `pct:` / `pos:` / `deg:` / `dmx:` / `pal:` are the other five spellings the intent
 * grammar has.
 */
export function isIntentString(value: string): boolean {
  return /^(pct|pos|deg|dmx|pal):/i.test(value.trim()) || /;\s*policy=/i.test(value)
}

/**
 * One selected column and the heads its cells stand for, in **visible row order**. That order is
 * what the **raw** arm walks (Speed); a desk-resolved spread is re-sorted by the desk into **rig
 * order** (`state/BuskRigOrder.kt` — the rig's rows and tiles, a group in member order, cells in
 * element order), which is the order every spread on the busk tab already runs in, and *Order:
 * Reverse* covers the other direction on both. A column that is one whole group (`group`) is sent
 * as the group, which the desk runs in the group's own member order instead (venue-visit plan S7).
 */
export interface SpreadColumn {
  col: ColumnKey
  targets: readonly WriteTarget[]
  /** The column's heads are one whole group's (`wholeGroupOf`): a desk spread names the group, so it
   *  runs in the group's member order (venue-visit plan S7). */
  group?: string
}

/**
 * Every spreadable column over one target list — the shape a **row** selection spreads by, on the
 * two plain list routes. They select cells too now, and a cell selection's Spread reads the marquee
 * like the programmer's; this is the *rows-only* arm those two keep and the programmer does not,
 * where the column is still the operator's to choose in the panel.
 */
export function spreadColumnsForTargets(targets: readonly WriteTarget[], group?: string): SpreadColumn[] {
  return SPREAD_COLUMNS.map((col) => ({ col, targets, group }))
}

interface SpreadPopoverProps {
  /**
   * The marquee, grouped by column. Empty when no cells are selected, which is the one case the
   * button is offered disabled for a reason other than the scope's.
   */
  columns: readonly SpreadColumn[]
  /** The project the heads belong to — what `POST /programmer/fan` is addressed to. Absent, no desk-resolved plan is built. */
  projectId?: number
  /**
   * This surface follows the desk selection — the programmer, whose bridge publishes the marquee
   * and whose row C pill reads the press pair. The request then carries `usePressFamilies`'s mask
   * (the desk's while following, the marquee's own when unlinked). The two plain lists never
   * bridge (§One selection, two shapes, D1) and send the marquee's own families, as their pill
   * shows — sending the desk's there meant a Dimmer spread on `/fixtures/list` refused under a
   * Colour marquee left on the programmer (session 3 review, finding 7).
   */
  desk?: boolean
  /** *Local*, or the focused Look's name — the label line's scope. */
  scopeLabel?: string
  /** A colour editor's *Spread…*: opens the panel on Colour with *From* seeded. See `SpreadSeed`. */
  seed?: SpreadSeed | null
  onSeedConsumed?: () => void
  /** For the trigger button — the programmer's selection bar folds it away at phone widths. */
  className?: string
}

/** What `useMarqueeSpreadPlans` is asked, and answers. */
export interface MarqueeSpreadInput {
  columns: readonly SpreadColumn[]
  projectId?: number
  desk?: boolean
  scopeLabel?: string
}

export interface MarqueeSpreadPlans {
  plans: SpreadPlan[]
  /** The scope's refusal — Output, or a focused template layer — in the words Set and Clear use. Null where a spread can land. */
  disabledReason: string | null
  labelLine: ReactNode
  footerNote: ReactNode
}

/**
 * The plans a marquee spreads by, and the words around them. Everything the component docblock
 * below says about the plans is this hook's; the component is the popover host around it.
 *
 * It reads the scope, the focused layers and `useCellWriters`, so a host must sit inside the same
 * `EditorContext` the grid provides.
 */
export function useMarqueeSpreadPlans({ columns, projectId, desk = false, scopeLabel = 'Local' }: MarqueeSpreadInput): MarqueeSpreadPlans {
  const writers = useCellWriters()
  const scope = useProgrammerScope()
  const focusedPalette = useFocusedPaletteLayer()
  const lookStore = useLookRowStore()
  const [spread] = useSpreadMutation()
  const { data: templates } = usePaletteListQuery({ projectId: projectId ?? 0 }, { skip: projectId == null })
  const colourPalettes = useMemo(() => (templates ?? []).filter(isSpreadColourPalette), [templates])
  // The cue editor's Values grid (show-cue-editing plan §3.3): the desk resolves with `write: false`
  // and each head's literal lands in the cue's own rows, as a focused Look layer's land in its draft.
  const editorContext = useEditorContext()
  const cueStoreRaw = useCueRowStore()
  const cueStore = editorContext.kind === 'cue' ? cueStoreRaw : null
  const disabledReason =
    editorContext.kind === 'cue' && cueStoreRaw?.readOnly
      ? 'This cue is read-only here — unlock the show to edit its values'
      : scope?.kind === 'output'
      ? 'Output is a read of the cook — switch to Local to fan values onto these heads'
      : focusedPalette != null
        ? 'This layer applies a palette — switch to Local to fan values onto these heads'
        : null

  const intentColumns = useMemo(
    () => columns.filter(({ col }) => spreadAttributeForColumn(col) != null),
    [columns],
  )
  const marqueeFamilies = useMemo(() => cellFamilies(intentColumns), [intentColumns])
  const pressFamilies = usePressFamilies(marqueeFamilies)
  const mask = desk ? pressFamilies : marqueeFamilies
  const layerStore = scope?.kind === 'layer' ? lookStore : null
  const setLookValue = layerStore?.setValue
  const lookName = layerStore?.lookName

  const landInCue = useCallback(
    (answer: { written?: { target: { key: string }; attribute: string; value: string }[] }) => {
      if (cueStore == null) return
      const written = answer.written ?? []
      const intent = written.find((write) => isIntentString(write.value) || parseProgrammerValue(write.value) == null)
      if (intent != null) {
        toast.error(`The desk answered “${intent.value}” rather than a value — update lighting7 to fan into a cue`, { id: INTENT_TOAST })
        return
      }
      for (const write of written) cueStore.setValue('fixture', write.target.key, write.attribute, write.value)
    },
    [cueStore],
  )

  const landInLayer = useCallback(
    (answer: { written?: { target: { key: string }; attribute: string; value: string }[] }) => {
      if (layerStore == null) return
      const written = answer.written ?? []
      const intent = written.find((write) => isIntentString(write.value) || parseProgrammerValue(write.value) == null)
      if (intent != null) {
        toast.error(`The desk answered “${intent.value}” rather than a value — update lighting7 to fan into a Look layer`, { id: INTENT_TOAST })
        return
      }
      landValuesInLook(layerStore, written)
    },
    [layerStore],
  )

  const plans = useMemo<SpreadPlan[]>(() => {
    const labels = new Map(COLUMN_DEFS.map((d) => [d.key, d.label]))
    const out: SpreadPlan[] = []

    if (intentColumns.length > 0 && projectId != null) {
      // One plan over every intent column: the family segment is the chooser between them.
      const seen = new Set<string>()
      const heads: WriteTarget[] = []
      for (const { targets } of intentColumns) {
        for (const target of targets) {
          if (seen.has(target.key)) continue
          seen.add(target.key)
          heads.push(target)
        }
      }
      const first = intentColumns[0]
      // One whole group in every column is a spread over that group, which the desk runs in its
      // member order; otherwise each head by its own key, in rig order.
      const group = first.group
      const each = spreadTargetsOf(heads)
      const targets: LocateTarget[] =
        group != null && intentColumns.every((c) => c.group === group) ? [{ type: 'group', key: group }] : each
      const plan: IntentSpreadPlan = {
        kind: 'intent',
        col: 'intent',
        label: intentColumns.map(({ col }) => labels.get(col) ?? col).join(' · '),
        targets,
        // The heads it stands for, the group's members included — what Spread needs two of.
        count: each.length,
        cellCount: spreadCellCount(heads),
        families: targetFamilies(heads),
        offered: marqueeFamilies,
        familyRefusal: 'Not in the selection — select cells in that column to fan it',
        attributesFor: (family: AttributeFamily) => spreadAttributesOffered(heads, family),
        initial: { family: columnFamily(first.col), attribute: spreadAttributeForColumn(first.col) ?? undefined },
        mask,
        colourPalettes,
        send: (body) => spread({ ...body, projectId, fadeMs: getProgrammerFadeMs() }).unwrap(),
      }
      // A fan there starts from the row being edited, not the stage (venue-visit plan S12).
      if (cueStore) {
        plan.write = false
        plan.onAnswer = landInCue
        plan.fanBases = () => fanBasesFrom(each, (key) => cueStore.composedValue('fixture', key, 'position'))
        plan.fanRowsLabel = `${cueStore.cueLabel}’s values`
        plan.fanScope = `cue:${cueStore.cueId}`
      } else if (setLookValue) {
        plan.write = false
        plan.onAnswer = landInLayer
        plan.fanBases = () => fanBasesFrom(each, (key) => lookPositionOf(layerStore, key))
        plan.fanRowsLabel = 'the layer’s rows'
        plan.fanScope = `layer:${layerStore.layerId}`
      }
      out.push(plan)
    }

    const speed = columns.find(({ col }) => col === 'speed')
    if (speed != null) {
      // The raw arm: one head per target that resolves the column, its cells as its steps over Cells.
      const probe = { kind: 'slider' as const, value: 0 }
      const headsOfWrites = speed.targets
        .map((outer) => {
          const own = resolveCell(outer.attributes, 'speed')
          if (own && commitMatchesResolution(probe, own)) return [{ target: outer, resolution: own }]
          return (outer.elements ?? []).flatMap((element) => {
            const resolution = resolveCell(element.attributes, 'speed')
            return resolution && commitMatchesResolution(probe, resolution) ? [{ target: element, resolution }] : []
          })
        })
        .filter((cells) => cells.length > 0)
      if (headsOfWrites.length > 0) {
        const cellCount = headsOfWrites.some((cells) => cells.length > 1) ? headsOfWrites.reduce((n, cells) => n + cells.length, 0) : 0
        const plan: RawSpreadPlan = {
          kind: 'raw',
          col: 'speed',
          label: labels.get('speed') ?? 'Speed',
          count: headsOfWrites.length,
          cellCount,
          apply: (values, over) => {
            const write = (cell: { target: WriteTarget; resolution: NonNullable<ReturnType<typeof resolveCell>> }, value: number) =>
              applyPlannedWrite(writers, { ...cell, commit: clampCommitToResolution({ kind: 'slider', value }, cell.resolution) })
            if (over === 'CELLS' && cellCount > 0) {
              headsOfWrites.flat().forEach((cell, i) => write(cell, values[i] ?? values[values.length - 1]))
            } else {
              headsOfWrites.forEach((cells, i) => cells.forEach((cell) => write(cell, values[i] ?? values[values.length - 1])))
            }
          },
        }
        out.push(plan)
      }
    }
    return out
  }, [columns, intentColumns, marqueeFamilies, mask, projectId, colourPalettes, spread, setLookValue, landInLayer, layerStore, cueStore, landInCue, writers])

  const intent = plans.find((plan): plan is IntentSpreadPlan => plan.kind === 'intent')
  const labelLine: ReactNode =
    intent == null ? null : lookName != null ? (
      <EditorLabelLine
        subject={`into ${lookName} · ${intent.count} ${intent.count === 1 ? 'head' : 'heads'}`}
        column={`${intent.label} marquee`}
      />
    ) : (
      <EditorLabelLine subject={headsLine(intent.count, scopeLabel)} column={`${intent.label} marquee`} />
    )
  const footerNote: ReactNode =
    cueStore != null ? (
      <span className="inline-flex items-center gap-1">resolved on the desk, written to {cueStore.cueLabel}’s own rows</span>
    ) : setLookValue == null ? undefined : (
      <span className="inline-flex items-center gap-1">
        <Layers className="size-3" aria-hidden /> resolved on the desk, written to the layer’s draft
      </span>
    )

  return { plans, disabledReason, labelLine, footerNote }
}

/**
 * The fixtures list's **Spread** — the editor kit's panel with plans built from this list's columns
 * (editor-kit plan D4, D6; `Spread.dc.html`).
 *
 * **The marquee answers the panel's first two questions.** The heads are `spreadTargetsOf` over the
 * marquee's write targets — a group row already expanded to its *visible* members, an element row
 * kept as a cell — and the family is the marquee's column, so the panel opens one row further down
 * than the busk tab does: the segment drawn checked, the other families the heads can take offered
 * disabled with the reason, the Property row from what the heads can take in that family. A
 * marquee spanning two families (Dimmer + Colour) offers both live, which is the chooser Fan drew
 * for the same case. `families` on the request is the pair the press sends (`usePressFamilies`):
 * the desk's while this tab follows it, the marquee's own when unlinked.
 *
 * **The desk resolves** every column in the template vocabulary (D3): the request goes through
 * `useSpreadMutation` and one literal per head lands in Local. Speed is outside the vocabulary and
 * keeps a client byte walk as a `raw` plan (D15), built through `resolveTargetCells` so a bar's
 * cells are its steps over Cells; Gobo and Prism build no plan, as they never did.
 *
 * **The scope arm is one flag on the route** (D6). Local sends no `write` key. A focused **Look**
 * layer sends `write: false`: the desk resolves exactly as it does for Local and answers without
 * writing, and each `written[].value` — the head's literal, in the Look row grammar — is landed in
 * the layer's draft through `LookRowStore.setValue`, which coalesces and PUTs as every layer-scope
 * edit does (400 ms, 2 s ceiling; flush cadence is stage cadence). A desk that still answers the
 * intent (mid-upgrade, plan §6) is told apart by shape (`isIntentString`: a `pct:` / `pos:` /
 * `deg:` / `dmx:` / `pal:` prefix or a `;policy=` tag — the literal parser alone would take a colour
 * intent as a colour) and refused with a toast naming the desk rather than landing an intent in
 * a Look row. In practice such a desk 400s the `write: false` request first, since its Json
 * refuses the unknown key; the guard is for the answer, should one ever arrive.
 * Output is a read of the cook and a focused *template* layer is a read of a template: neither's
 * cells are editable and `useCellWriters` has no arm for either, so both refuse with the words Set
 * and Clear use, disabled with the reason rather than hidden.
 */
export function SpreadPopover({ columns, projectId, desk = false, scopeLabel = 'Local', seed, onSeedConsumed, className }: SpreadPopoverProps) {
  const { plans, disabledReason, labelLine, footerNote } = useMarqueeSpreadPlans({ columns, projectId, desk, scopeLabel })

  return (
    <SpreadPanel
      host="popover"
      plans={plans}
      colourEditor={ColourEditor}
      labelLine={labelLine}
      footerNote={footerNote}
      seed={seed}
      onSeedConsumed={onSeedConsumed}
      disabledReason={disabledReason}
      drivableHint={DRIVABLE_HINT}
      // The plans above are already filtered to columns Spread can drive, so a Setting marquee
      // hands in none — and must still read as "cells in a column it cannot drive", not as no selection.
      noSelection={columns.length === 0}
      className={className}
    />
  )
}
