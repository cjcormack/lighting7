import { useCallback, useMemo, useRef, useSyncExternalStore } from 'react'
import { lightingApi } from '../../api/lightingApi'
import { familyForCategory, parsePropertyMask } from '../../lib/attributeFamily'
import { columnFamily, resolutionPropertyNames } from './columns'
import { cellValueFromParts, isLocalEntry, stagedPartFor } from './scopedCellValue'
import { parseProgrammerEntryValue, parseProgrammerValue } from '../../lib/programmerValue'
import { useProgrammerScope } from '../programmer/ProgrammerScope'
import { lookRowKey, useLookRowStore } from '../programmer/LookRowStore'
import { useFocusedTemplateLayer } from '../programmer/FocusedTemplateLayer'
import { useProgrammerRowSnapshot } from './useProgrammerRowSnapshot'
import { useRowValues } from './useRowValues'
import type { AttributeFamily } from '../../lib/attributeFamily'
import type { CellState, RowCellStates, StagedPart } from './scopedCellValue'
import type { RowCell } from './useRowValues'
import type { LookRowStoreValue } from '../programmer/LookRowStore'
import type { FocusedTemplateLayerValue } from '../programmer/FocusedTemplateLayer'
import type { StagedValue } from './useRowOwnership'

/**
 * One row's cells, read through whatever the programmer's grid is currently pointed at.
 *
 * Three sources, and all three hooks are called on **every** row unconditionally — the two that
 * are off are handed an empty cell list, which the existing "empty cells = off" contract turns
 * into no subscriptions and a frozen empty result. Three cheap no-ops per row beats one
 * polymorphic value-source interface: each scope stays independently testable, and there is no
 * per-row `deps` memo to get wrong and churn subscriptions with.
 *
 * `null` scope — the plain `/fixtures` and `/groups` lists — is Output, exactly as before.
 */
export function useScopedRowValues(
  cells: readonly RowCell[],
  /**
   * The row's live wire read, which the caller already holds — it is what the cell editor opens at
   * whatever the scope. Passed in rather than read again here: Output *is* that read, and a second
   * `useRowValues(cells)` would register a duplicate channel subscription per cell on the one scope
   * where the grid is at its widest.
   */
  live: ReturnType<typeof useRowValues>,
): RowCellStates {
  const scope = useProgrammerScope()
  const store = useLookRowStore()
  const focusedTemplate = useFocusedTemplateLayer()

  // **`null` is not `output`.** No scope at all means the plain `/fixtures` and `/groups` lists,
  // which have no scope concept and must behave exactly as they did before this existed: live
  // values, editable cells, no em-dashes. Only an *explicit* Output scope is a read of the cook.
  const kind = scope?.kind
  const localCells = kind === 'local' ? cells : EMPTY_CELLS
  const layerCells = kind === 'layer' ? cells : EMPTY_CELLS

  const local = useLocalRowValues(localCells)
  const layer = useLayerRowValues(layerCells, store)
  const template = useTemplateRowValues(layerCells, focusedTemplate, live)

  return useMemo(() => {
    if (kind === undefined) return EMPTY_STATES
    if (kind === 'local') return local
    // A focused template layer is a **read**, and neither store answers for it: `LookRowStore`
    // engages only for a LOOK, so before this arm existed the layer scope fell through to no states
    // at all — which the grid renders as live, editable cells writing straight to Local while the
    // band overhead says "One layer". `LayerRowNotices` had been claiming the opposite since it
    // shipped.
    if (kind === 'layer') return focusedTemplate != null ? template : layer
    // Output is a read of the cook: every cell has a value and none of them is editable here.
    // Editing "the output" would have to pick a destination, and the point of the scope switcher
    // is that the operator says which.
    const states: RowCellStates = {}
    for (const cell of cells) {
      const value = live[cell.col]
      if (value) states[cell.col] = { value, editable: false }
    }
    return states
  }, [kind, cells, live, local, layer, template, focusedTemplate])
}

const EMPTY_CELLS: readonly RowCell[] = []
const EMPTY_STATES: RowCellStates = {}
/** Joins a *list* of row keys, which are themselves NUL-joined — so this must be something else. */
const KEY_LIST_SEP = '\u0001'


/**
 * What the **operator** set, and nothing else.
 *
 * Keyed by `(target, property)` and not by channel, so the subscription mechanics —
 * per-key subscriptions, a version counter in place of a numeric channel signature, the
 * blind-transition filter — are `useProgrammerRowSnapshot`'s, shared with `useRowOwnership`.
 *
 * The values themselves come from the programmer's own entries rather than from the wire. That is
 * not an optimisation — under blind the programmer is gated out of the merge, so the wire shows
 * what is *underneath* while the entries are still what the operator is editing and what Record
 * will take.
 */
function useLocalRowValues(cells: readonly RowCell[]): RowCellStates {
  return useProgrammerRowSnapshot(cells, EMPTY_STATES, computeLocalRowStates)
}

function computeLocalRowStates(cells: readonly RowCell[]): RowCellStates {
  const lookup = (targetKey: string, propertyName: string): StagedValue | undefined => {
    const state = lightingApi.programmer.getKeyState(targetKey, propertyName)
    if (!isLocalEntry(state)) return undefined
    return parseProgrammerEntryValue(state.entry!) ?? undefined
  }

  const states: RowCellStates = {}
  for (const cell of cells) {
    const parts = cell.resolutions.map((res, i) =>
      stagedPartFor(res, cell.targetKeys[i], lookup),
    )
    // Every cell is editable in Local, set or not — an em-dash is where you *start* a value.
    states[cell.col] = { value: cellValueFromParts(cell.resolutions, parts), editable: true }
  }
  return states
}

/**
 * One layer's Look, read as its stored rows with any pending edit overlaid.
 *
 * Subscribed per key to the draft rather than re-derived from a version counter: a cell drag
 * commits at ~30 Hz, and waking every visible row on each tick is the cost `useRowOwnership`
 * splits its own subscriptions to avoid. Committed rows arrive as a plain context value instead,
 * because they only change when someone saves — and then every row genuinely should re-render.
 */
function useLayerRowValues(
  cells: readonly RowCell[],
  store: LookRowStoreValue | null,
): RowCellStates {
  const draft = store?.draft
  // Signature first, array derived from it — the same shape `useRowValues` uses for its channel
  // list, and for the same reason: `buildRows` mints fresh Row objects on every filter keystroke,
  // so keying the subscription on the *identity* of a per-render array would re-register every
  // listener for every mounted row and make typing in the filter box quadratic. Going through the
  // string makes `draftKeys` genuinely stable, so it can be an honest dependency below.
  const draftKeysSignature = useMemo(
    () =>
      cells
        .flatMap((cell) =>
          cell.resolutions.flatMap((res, i) =>
            resolutionPropertyNames(res).map((name) => lookRowKey(cell.targetKeys[i], name)),
          ),
        )
        .join(KEY_LIST_SEP),
    [cells],
  )
  const draftKeys = useMemo(
    () => (draftKeysSignature === '' ? [] : draftKeysSignature.split(KEY_LIST_SEP)),
    [draftKeysSignature],
  )

  const versionRef = useRef(0)
  const subscribe = useCallback(
    (callback: () => void) => {
      if (!draft || draftKeys.length === 0) return () => {}
      // The counter moves on *notification*, not on read: `useSyncExternalStore` compares
      // snapshots, and a draft value is not a number it can compare. Same `bump` shape as
      // `useRowOwnership`.
      return draft.subscribe(draftKeys, () => {
        versionRef.current += 1
        callback()
      })
    },
    [draft, draftKeys],
  )
  const getVersion = useCallback(() => versionRef.current, [])
  const draftVersion = useSyncExternalStore(subscribe, getVersion, getVersion)

  return useMemo(() => {
    // A dependency, not a read: the counter is what re-derives the cells after a draft write.
    void draftVersion
    if (cells.length === 0 || !store) return EMPTY_STATES
    const mask = parsePropertyMask(store.propertyMask)
    const lookup = (targetKey: string, propertyName: string): StagedValue | undefined => {
      const pending = store.draft.get(targetKey, propertyName)
      if (pending !== undefined) return parseProgrammerValue(pending) ?? undefined
      return store.serverRows.get(lookRowKey(targetKey, propertyName))
    }

    const states: RowCellStates = {}
    for (const cell of cells) {
      const tone = cellTone(cell, mask, store.targetedKeys)
      const parts: (StagedPart | undefined)[] = cell.resolutions.map((res, i) =>
        stagedPartFor(res, cell.targetKeys[i], lookup),
      )
      const state: CellState = {
        value: cellValueFromParts(cell.resolutions, parts),
        // A cell an untargeted fixture would receive is shown but not editable, and a masked-out
        // column is inert. Widening a layer's targets is always the explicit affordance on the
        // row — never a side effect of dragging a marquee across the grid.
        editable: tone === undefined,
      }
      if (tone) state.tone = tone
      states[cell.col] = state
    }
    return states
  }, [cells, store, draftVersion])
}

/**
 * A focused **template** layer's cells: a read, never an edit.
 *
 * Two shapes under one rule, because a template's two kinds hide different things (fx-templates D1):
 *
 * - a **value** template resolves one intent per head at cook, so there is nothing per-fixture to
 *   show and every cell is unset. Projecting its generic row onto each targeted row would convert it
 *   to a per-fixture template on the first edit — a change to what the template *is*, made by
 *   someone adjusting a value. `LayerRowNotices` says so and links to the editor.
 * - an **effect** template drives every head the layer names with one rule, and what is worth
 *   watching is what it is producing *now*. So the cells it drives carry the live value, which
 *   `layerCellClass` then rings, and `FixturesTable` marks with the wave and the division.
 *
 * Neither is editable. The edit goes through the template, and until this arm existed a template
 * layer's cells silently took one and wrote it to Local.
 *
 * Pure — no subscription of its own. `live` is the row's existing channel read, already the reason
 * this row re-renders.
 */
function useTemplateRowValues(
  cells: readonly RowCell[],
  focused: FocusedTemplateLayerValue | null,
  live: ReturnType<typeof useRowValues>,
): RowCellStates {
  return useMemo(() => {
    if (cells.length === 0 || focused == null) return EMPTY_STATES
    const states: RowCellStates = {}
    for (const cell of cells) {
      const tone = cellTone(cell, focused.mask, focused.targetedKeys)
      const state: CellState = { editable: false }
      if (tone) state.tone = tone
      // `kind` is undefined until the library lands, and that is deliberately not treated as
      // "value": showing an empty grid and then filling it would be the wrong flash of the two.
      if (focused.kind === 'effect' && tone === undefined) {
        const value = live[cell.col]
        if (value) state.value = value
      }
      states[cell.col] = state
    }
    return states
  }, [cells, focused, live])
}

/**
 * Why a cell in layer scope is not editable, if it isn't.
 *
 * `inert` wins over `untargeted`: a column the layer does not assert at all is the more
 * fundamental fact, and saying "outside this layer's targets" about a property it would never
 * write either way sends the operator to fix the wrong thing.
 *
 * Takes the mask and targets rather than a store, because both layer arms ask it: a Look layer's
 * come from `LookRowStore` and a template layer's from `FocusedTemplateLayer`, and the two must not
 * disagree about which cells a layer reaches.
 */
function cellTone(
  cell: RowCell,
  mask: readonly AttributeFamily[],
  targeted: ReadonlySet<string> | null,
): 'inert' | 'untargeted' | undefined {
  if (mask.length > 0 && !mask.includes(cellFamily(cell))) return 'inert'
  if (targeted && cell.targetKeys.some((key) => !targeted.has(key))) return 'untargeted'
  return undefined
}

/**
 * The family a cell belongs to.
 *
 * Read from the resolution's own descriptor where there is one, so a column carrying different
 * property kinds across fixture types still classifies per fixture; the column's declared family
 * is the fallback for a cell with nothing to ask.
 */
function cellFamily(cell: RowCell): AttributeFamily {
  const first = cell.resolutions[0]
  if (first) {
    if (first.kind === 'position') return 'POSITION'
    return familyForCategory(first.property.category)
  }
  return columnFamily(cell.col)
}

