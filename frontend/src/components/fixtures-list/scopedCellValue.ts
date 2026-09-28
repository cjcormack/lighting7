import { channelKey } from '../../hooks/usePropertyValues'
import { aggregateCellValue } from './useRowValues'
import type { CellResolution, ColumnKey } from './columns'
import type { ChannelRef } from '../../store/fixtures'
import type { ProgrammerKeyState } from '../../api/programmerWsApi'
import type { CellValue } from './useRowValues'
import type { StagedValue } from './useRowOwnership'

/**
 * Building cell values for a scope the wire cannot answer for.
 *
 * The programmer grid reads three scopes — Output, Local, one layer — and only Output is a
 * channel read. The other two are keyed by `(targetKey, propertyName)`: Local asks "did the
 * *operator* set this?", a layer asks "does this Look hold a row for it?". Both answer with a
 * canonical assignment string, which `parseProgrammerValue` turns into a [StagedValue].
 *
 * Everything here is pure and has no store dependency, which is deliberate — this is where the
 * scope logic is actually testable, so the hooks above it stay thin subscription wrappers.
 *
 * **Why not a `ChannelSource`.** There is a perfectly good channel-source abstraction next door
 * (`api/channelSource.ts`) and it is the wrong tool three times over:
 *
 * - it cannot express *unset*. Everything a derived source does not hold reads 0, and `holds()`
 *   lives on `DerivedChannelSource` rather than on `ChannelSource`, so it never reaches
 *   `getChannelValue`. "Held at 0" versus "nobody set this" is the entire point of Local scope —
 *   a zero there would answer "what will Record take?" with a confident lie;
 * - a Look row can name a **group**, which has no universe or channel at all;
 * - `createProgrammerChannelSource` fans a property entry out into channels, so re-aggregating
 *   six channel presences back into one cell verdict re-derives what the entry stated atomically.
 *
 * What it does instead is feed [aggregateCellValue] a lookup built from those entries, so the
 * min/max, the component averaging, the uniformity test, the combined CSS swatch, the normalised
 * pad axes and the wheel-option resolution are all the *same code* the live path runs.
 */

/** How a cell renders and behaves in the current scope. */
export interface CellState {
  /**
   * Absent means **nobody set this in this scope** — render an em-dash, never a zero. Output
   * always has a value (the wire always has an opinion); Local and Layer frequently do not, and
   * that absence is the information.
   */
  value?: CellValue
  /** False for Output (a read of the cook), and for inert or untargeted cells in Layer scope. */
  editable: boolean
  /**
   * Why a cell is not editable, when it isn't:
   * - `inert` — the column's family is outside the layer's `propertyMask`;
   * - `untargeted` — the row's fixture is outside the layer's `targets`.
   */
  tone?: 'inert' | 'untargeted'
}

export type RowCellStates = Partial<Record<ColumnKey, CellState>>

/**
 * One resolution's worth of scoped value.
 *
 * [complete] is false when the value had to be assembled from more than one property and only
 * some of them were set — a position paired from separate pan and tilt sliders where the Look
 * holds a row for `pan` alone. The axis that is missing reads 0, and the cell is reported
 * non-uniform, so the crosshair is drawn but never claims to be a single trustworthy value.
 */
export interface StagedPart {
  staged: StagedValue
  complete: boolean
}

/**
 * Is this key one the **operator** set, rather than one a Look layer put there?
 *
 * `owner` is the winning slot's owner, and `layers` is the one the layer stack writes under, so
 * this is the discriminator Local scope turns on.
 *
 * Provenance (`source === 'PROGRAMMER' && layerId == null`) says the same thing most of the time
 * and **must not** be the primary test, for two reasons that both bite in normal use: while
 * blind, provenance reports whatever is *underneath* the programmer (the same trap
 * `useRowOwnership.sourceFor` documents), and a parked property reports `PARKED` while still
 * holding the operator's entry that Record would take.
 */
export function isLocalEntry(state: ProgrammerKeyState | undefined): boolean {
  return state?.entry != null && state.entry.owner !== 'layers'
}

/**
 * Assemble one resolution's [StagedPart] from a `(targetKey, propertyName)` lookup.
 *
 * This is where the position-pairing knowledge lives, so callers can walk resolutions without
 * knowing that a fixture with separate pan and tilt sliders has two properties behind one cell.
 * A staged value whose kind doesn't match the resolution's is treated as **unset** rather than
 * coerced — the same "a mismatch falls through" contract `applyStagedValue` keeps.
 */
export function stagedPartFor(
  res: NonNullable<CellResolution>,
  targetKey: string,
  lookup: (targetKey: string, propertyName: string) => StagedValue | undefined,
): StagedPart | undefined {
  switch (res.kind) {
    case 'slider':
    case 'setting':
    case 'colour-setting': {
      const staged = lookup(targetKey, res.property.name)
      return staged?.kind === 'level' ? { staged, complete: true } : undefined
    }
    case 'colour': {
      const staged = lookup(targetKey, res.property.name)
      return staged?.kind === 'colour' ? { staged, complete: true } : undefined
    }
    case 'position': {
      if (res.property) {
        const staged = lookup(targetKey, res.property.name)
        return staged?.kind === 'position' ? { staged, complete: true } : undefined
      }
      const pan = res.panProperty ? lookup(targetKey, res.panProperty.name) : undefined
      const tilt = res.tiltProperty ? lookup(targetKey, res.tiltProperty.name) : undefined
      const panLevel = pan?.kind === 'level' ? pan.value : undefined
      const tiltLevel = tilt?.kind === 'level' ? tilt.value : undefined
      if (panLevel === undefined && tiltLevel === undefined) return undefined
      return {
        staged: { kind: 'position', pan: panLevel ?? 0, tilt: tiltLevel ?? 0 },
        complete: panLevel !== undefined && tiltLevel !== undefined,
      }
    }
  }
}

/**
 * Write a staged value onto the channels its resolution occupies, so the aggregation below can
 * read it exactly as it reads the wire.
 *
 * Channels are the intermediate representation only because [aggregateCellValue] already speaks
 * it; nothing here goes near DMX.
 */
function stageOntoChannels(
  res: NonNullable<CellResolution>,
  staged: StagedValue,
  into: Map<string, number>,
): void {
  const put = (ref: ChannelRef | undefined, value: number) => {
    if (ref) into.set(channelKey(ref), value)
  }
  switch (res.kind) {
    case 'slider':
    case 'setting':
    case 'colour-setting':
      if (staged.kind === 'level') put(res.property.channel, staged.value)
      return
    case 'colour':
      if (staged.kind !== 'colour') return
      put(res.property.redChannel, staged.r)
      put(res.property.greenChannel, staged.g)
      put(res.property.blueChannel, staged.b)
      put(res.property.whiteChannel, staged.w)
      put(res.property.amberChannel, staged.a)
      put(res.property.uvChannel, staged.uv)
      return
    case 'position':
      if (staged.kind !== 'position') return
      put(res.pan, staged.pan)
      put(res.tilt, staged.tilt)
      return
  }
}

/**
 * Aggregate a cell from per-resolution staged parts, index-parallel to [resolutions].
 *
 * Resolutions with no part are **dropped** rather than read as zero: a group row where three of
 * twelve heads carry a value aggregates those three, and is reported non-uniform so the cell
 * cannot pass itself off as one settled value for the group.
 */
export function cellValueFromParts(
  resolutions: readonly NonNullable<CellResolution>[],
  parts: readonly (StagedPart | undefined)[],
): CellValue | undefined {
  const setResolutions: NonNullable<CellResolution>[] = []
  const channels = new Map<string, number>()
  let anyIncomplete = false
  for (let i = 0; i < resolutions.length; i++) {
    const part = parts[i]
    if (!part) continue
    setResolutions.push(resolutions[i])
    stageOntoChannels(resolutions[i], part.staged, channels)
    if (!part.complete) anyIncomplete = true
  }
  if (setResolutions.length === 0) return undefined

  const value = aggregateCellValue(setResolutions, (ref) => channels.get(channelKey(ref)) ?? 0)
  if (!value) return undefined
  const partial = anyIncomplete || setResolutions.length !== resolutions.length
  return partial && value.isUniform ? { ...value, isUniform: false } : value
}
