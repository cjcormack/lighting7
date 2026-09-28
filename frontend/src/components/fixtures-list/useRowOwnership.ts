import type { LayerSource } from '@/api/cuesApi'
import { lightingApi } from '../../api/lightingApi'
import { parseProgrammerValue } from '../../lib/programmerValue'
import { useProgrammerRowSnapshot } from './useProgrammerRowSnapshot'
import type { ProgrammerKeyState, ProvenanceSource } from '../../api/programmerWsApi'
import type { CellPropertyKey, RowCell } from './useRowValues'
import type { ColumnKey } from './columns'

/**
 * Which layer owns a cell's value, plus the programmer facts the sheet colours by.
 *
 * `source` collapses the backend's per-property provenance across everything the cell covers
 * (all members of a group row, all heads of a collapsed multi-head fixture). A cell with
 * nothing above baseline reads `baseline`.
 */
export type CellOwnershipSource = 'parked' | 'programmer' | 'effect' | 'cue' | 'baseline'

export interface CellOwnership {
  source: CellOwnershipSource
  /** True when at least one covered property carries an operator edit (not a hand-down). */
  touched: boolean
  /** False when the covered properties disagree about who owns them. */
  isUniform: boolean
  /** Distinct programmer owners across the cell: `web`, `locate`, `preset:3`, … */
  owners: string[]
  /** Group name when the programmer entry came through a group control. */
  sourceGroup?: string
  /**
   * The programmer's staged value while blind is engaged, as a level/colour/position — the
   * wire still shows the layers underneath, so this is the only way to see what you are
   * building. Undefined whenever blind is off (the wire value already *is* the programmer
   * value) or the cell's covered properties disagree.
   */
  staged?: StagedValue
  /**
   * Set when a **Look layer** produced this cell's winning value — in a cue or in the programmer.
   *
   * The answer to "why is this fixture this colour?", which `source` alone can only answer with
   * *a cue*. This is the survivor of a pair: `paletteRef` named a value-level `ref:` on the
   * operator's own entry, and retired with that grammar in session 4.
   */
  layer?: CellLayer
}

/**
 * The Look layer that won a cell.
 *
 * One object for the whole cell: a cell can cover twelve properties (every member of a group row,
 * both axes of a pan/tilt pair, every head of a collapsed multi-head fixture) and the operator needs
 * one answer, not twelve. `mixed` is the honest form of "they don't agree" —
 * naming one layer over a cell where half the heads came from another would be a confident lie.
 */
export interface CellLayer {
  /** Undefined when the covered properties were won by *different* layers — see `mixed`. */
  layerId?: number
  /** What that layer applies — a Look or a template. Undefined when `mixed`. */
  source?: LayerSource
  name?: string
  mixed: boolean
}

// `CellPaletteRef` and its `resolvedLiteralOf` helper stood here until session 4. They described
// the named palette a cell's entries referenced, as one verdict for the whole cell, and
// `resolvedLiteralOf` existed because on a POSITION palette every head of a group row held the
// identical `ref:{uuid}` string while resolving to a different pan/tilt — so comparing `value`
// reported the cell uniform and painted one head's crosshair for all twelve. With the `ref:` grammar
// gone an entry's `value` *is* its literal, and [CellLayer] answers "where did this come from".

export type StagedValue =
  | { kind: 'level'; value: number }
  | { kind: 'colour'; r: number; g: number; b: number; w: number; a: number; uv: number }
  | { kind: 'position'; pan: number; tilt: number }

export type RowOwnership = Partial<Record<ColumnKey, CellOwnership>>

/**
 * Most-significant-first. A cell covering a parked property and a cue-owned one reads
 * `parked`: park is the thing the operator most needs to know about, because it is the one
 * state where the rig ignores everything they do.
 */
const SOURCE_RANK: Record<CellOwnershipSource, number> = {
  parked: 4,
  programmer: 3,
  effect: 2,
  cue: 1,
  baseline: 0,
}

const PROVENANCE_TO_SOURCE: Record<ProvenanceSource, CellOwnershipSource> = {
  PARKED: 'parked',
  PROGRAMMER: 'programmer',
  EFFECT: 'effect',
  CUE: 'cue',
}

const EMPTY_OWNERSHIP: RowOwnership = {}

function sourceFor(state: ProgrammerKeyState): CellOwnershipSource {
  const provenance = state.provenance
  if (provenance) return PROVENANCE_TO_SOURCE[provenance.source] ?? 'baseline'
  // While blind, the programmer is gated out of the merge, so provenance reports whatever is
  // underneath — but the entry is still what the operator is editing, and the sheet must
  // show it as theirs.
  return state.entry ? 'programmer' : 'baseline'
}

/** Aggregate one cell's covered properties into a single ownership verdict. */
export function aggregateCellOwnership(
  keys: readonly CellPropertyKey[],
  blind: boolean,
  lookup: (targetKey: string, propertyName: string) => ProgrammerKeyState,
): CellOwnership | undefined {
  if (keys.length === 0) return undefined

  let source: CellOwnershipSource = 'baseline'
  let uniform = true
  let touched = false
  let sourceGroup: string | undefined
  const owners: string[] = []
  let stagedValue: string | undefined
  let stagedUniform = true

  // The winning layer, tracked separately from ownership because the two disagree routinely: it is
  // a property of provenance rather than of the operator's entry.
  let layerId: number | undefined
  let layerEntry: { layerSource?: LayerSource } | undefined
  let layerCount = 0
  let layerMixed = false
  // What the layer verdict is taken over: every covered key, plus every bundled emitter a colour's
  // W / A / UV came from — each is a contributor to what the cell shows, so a colour whose white
  // came from another layer is not wholly its RGB layer's.
  let observations = keys.length

  const observeLayer = (observed: { layerId?: number; layerSource?: LayerSource }) => {
    if (observed.layerId == null) return
    layerCount += 1
    if (layerId === undefined) {
      layerId = observed.layerId
      layerEntry = observed
    } else if (layerId !== observed.layerId) {
      layerMixed = true
    }
  }

  keys.forEach((key, index) => {
    const state = lookup(key.targetKey, key.propertyName)
    const keySource = sourceFor(state)
    if (index === 0) source = keySource
    else if (keySource !== source) {
      uniform = false
      if (SOURCE_RANK[keySource] > SOURCE_RANK[source]) source = keySource
    }

    const layer = state.provenance
    if (layer) {
      observeLayer(layer)
      for (const emitter of layer.bundled ?? []) {
        observations += 1
        observeLayer(emitter)
      }
    }

    if (state.entry) {
      if (state.entry.touched) touched = true
      sourceGroup ??= state.entry.sourceGroup
      for (const owner of state.entry.owners) {
        if (!owners.includes(owner)) owners.push(owner)
      }
      const literal = state.entry.value
      if (index === 0) stagedValue = literal
      else if (literal !== stagedValue) stagedUniform = false
    } else if (index === 0) {
      stagedValue = undefined
    } else {
      stagedUniform = false
    }
  })

  const staged =
    blind && stagedUniform && stagedValue !== undefined
      ? (parseProgrammerValue(stagedValue) ?? undefined)
      : undefined

  const cellLayer: CellLayer | undefined =
    layerCount === 0
      ? undefined
      : {
          layerId: layerMixed ? undefined : layerId,
          source: layerMixed ? undefined : layerEntry?.layerSource,
          name: layerMixed ? undefined : layerEntry?.layerSource?.name,
          mixed: layerMixed || layerCount !== observations,
        }

  return {
    source,
    touched,
    isUniform: uniform,
    owners,
    sourceGroup,
    staged,
    layer: cellLayer,
  }
}

/**
 * Ownership for one row's cells — the sibling of `useRowValues`, and the same discipline:
 * one subscription set per row (not per cell) and a cached snapshot identity, both owned by
 * `useProgrammerRowSnapshot`. This hook is just the aggregation layered on top.
 */
export function useRowOwnership(cells: readonly RowCell[]): RowOwnership {
  return useProgrammerRowSnapshot(cells, EMPTY_OWNERSHIP, computeRowOwnership)
}

function computeRowOwnership(cells: readonly RowCell[]): RowOwnership {
  const blind = lightingApi.programmer.isBlind()
  const lookup = (targetKey: string, propertyName: string) =>
    lightingApi.programmer.getKeyState(targetKey, propertyName)

  const ownership: RowOwnership = {}
  for (const cell of cells) {
    const value = aggregateCellOwnership(cell.keys, blind, lookup)
    if (value) ownership[cell.col] = value
  }
  return ownership
}
