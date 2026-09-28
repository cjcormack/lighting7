import { useCallback, useMemo, useRef, useSyncExternalStore } from 'react'
import {
  channelKey,
  getChannelValue,
  resolveSettingOption,
  subscribeToChannels,
} from '../../hooks/usePropertyValues'
import { computeCombinedCss } from '../../lib/colourMath'
import { resolutionChannels, resolutionPropertyNames } from './columns'
import { resolveTargetCells, rowWriteTargets } from './rowModel'
import type { CellResolution, ColumnKey } from './columns'
import type { Row } from './rowModel'
import type { ChannelRef, SettingOption } from '../../store/fixtures'

/**
 * Aggregated live value for one cell. For a fixture row there's exactly one
 * backing resolution and `isUniform` is always true; for a group row the value
 * aggregates every member that has the property.
 *
 * This is also what the group cards and the stage views display —
 * `useGroupPropertyValues.ts` projects its group descriptors into
 * [CellResolution]s and reads through [aggregateCellValue]. There used to be a
 * second implementation there, and the two had already diverged over extended
 * emitters (see [aggregateCellValue]).
 */
export type CellValue =
  | { kind: 'slider'; min: number; max: number; isUniform: boolean }
  | {
      kind: 'colour'
      isUniform: boolean
      r: number
      g: number
      b: number
      w?: number
      a?: number
      uv?: number
      combinedCss: string
    }
  | {
      kind: 'position'
      isUniform: boolean
      pan: number
      tilt: number
      panNormalized: number
      tiltNormalized: number
    }
  | {
      kind: 'setting'
      isUniform: boolean
      level: number
      option?: SettingOption
    }

/**
 * One programmer/provenance lookup key behind a cell. Not parallel to `resolutions` — a
 * position cell built from separate pan/tilt sliders is a single resolution backed by two
 * independent properties.
 */
export interface CellPropertyKey {
  targetKey: string
  propertyName: string
}

export interface RowCell {
  col: ColumnKey
  /** Non-null resolutions: one for a plain fixture or element row, one per
   *  element for a multi-head fixture row whose parent lacks the property,
   *  one per member-with-the-property for a group row. */
  resolutions: NonNullable<CellResolution>[]
  /**
   * **Index-parallel to [resolutions]**: the target each one resolved against.
   *
   * Not derivable from [keys], which is flat and deliberately so — one resolution can
   * contribute two keys (a position paired from pan/tilt sliders), so for a twelve-member group
   * row the two arrays have neither the same length nor any index correspondence. Ownership
   * never noticed, because it collapses every key to a single verdict; a value built from
   * programmer entries or Look rows *does* care, because it needs one value per resolution to
   * compute uniformity and the colour average.
   */
  targetKeys: string[]
  /** Every (target, property) the cell covers — the programmer's key space. */
  keys: CellPropertyKey[]
}

export type RowValues = Partial<Record<ColumnKey, CellValue>>

/**
 * Resolve a row's cells for the visible columns, through the same
 * rowWriteTargets + resolveTargetCells pair the write path uses — what a cell
 * displays is by construction what an edit on it writes (parent-first
 * precedence, element fallback, group members expanded). A column no target
 * supports resolves to no cell (renders empty).
 */
export function buildRowCells(row: Row, visibleColumns: readonly ColumnKey[]): RowCell[] {
  const targets = rowWriteTargets(row)
  const cells: RowCell[] = []
  for (const col of visibleColumns) {
    const resolutions: NonNullable<CellResolution>[] = []
    const targetKeys: string[] = []
    const keys: CellPropertyKey[] = []
    for (const target of targets) {
      for (const { target: resolved, resolution } of resolveTargetCells(target, col)) {
        resolutions.push(resolution)
        // `resolved` is the element for a multi-head fallback, the target itself otherwise —
        // exactly the key the backend stores the programmer entry under.
        targetKeys.push(resolved.key)
        for (const propertyName of resolutionPropertyNames(resolution)) {
          keys.push({ targetKey: resolved.key, propertyName })
        }
      }
    }
    if (resolutions.length > 0) cells.push({ col, resolutions, targetKeys, keys })
  }
  return cells
}

/**
 * Aggregate one cell's resolutions against the live channel store. When a
 * group's members back the column with different descriptor kinds (say a
 * colour property on some, a colour wheel on others), only members matching
 * the *first* member's kind participate — a single cell can't meaningfully
 * blend RGB values with wheel positions.
 *
 * [readChannel] exists so the *same* aggregation — min/max, component averaging, uniformity,
 * the combined CSS swatch, the normalised pad axes, the resolved wheel option — serves values
 * that never came off the wire at all. `scopedCellValue.ts` feeds it a lookup built from
 * programmer entries or a Look's stored rows; nothing about the maths differs, and duplicating
 * it for each scope is how the four kinds drift apart. It also lets the group cards and stage
 * views pass their `ChannelSource` selection in (`useGroupPropertyValues.ts`).
 *
 * **The extended-emitter averaging rule, stated once because it is the thing the two former
 * implementations disagreed about**: each of white/amber/UV is averaged over the members that
 * actually *have* that emitter, not over every member. A head with no white channel has no
 * opinion about white — it is not a vote for white=0. So two RGBW heads at W=255 beside two
 * RGB heads read W=255, and the swatch shows the white the rig is actually throwing; dividing
 * by four would report a half-white nothing is emitting, and would make the same group's
 * reading depend on how many colour-only heads happened to be patched next to it. R/G/B are
 * present on every colour member by construction, so the two rules coincide there.
 */
export function aggregateCellValue(
  resolutions: readonly NonNullable<CellResolution>[],
  readChannel: (ref: ChannelRef) => number = getChannelValue,
): CellValue | undefined {
  const first = resolutions[0]
  if (!first) return undefined

  switch (first.kind) {
    case 'slider': {
      const values = resolutions
        .filter((r) => r.kind === 'slider')
        .map((r) => readChannel(r.property.channel))
      const min = Math.min(...values)
      const max = Math.max(...values)
      return { kind: 'slider', min, max, isUniform: min === max }
    }
    case 'colour': {
      const members = resolutions
        .filter((r) => r.kind === 'colour')
        .map((r) => ({
          r: readChannel(r.property.redChannel),
          g: readChannel(r.property.greenChannel),
          b: readChannel(r.property.blueChannel),
          w: r.property.whiteChannel ? readChannel(r.property.whiteChannel) : undefined,
          a: r.property.amberChannel ? readChannel(r.property.amberChannel) : undefined,
          uv: r.property.uvChannel ? readChannel(r.property.uvChannel) : undefined,
        }))
      const head = members[0]
      const isUniform = members.every(
        (m) =>
          m.r === head.r &&
          m.g === head.g &&
          m.b === head.b &&
          m.w === head.w &&
          m.a === head.a &&
          m.uv === head.uv,
      )
      const avg = (pick: (m: (typeof members)[number]) => number | undefined) => {
        const defined = members.map(pick).filter((v): v is number => v !== undefined)
        if (defined.length === 0) return undefined
        return Math.round(defined.reduce((sum, v) => sum + v, 0) / defined.length)
      }
      const r = avg((m) => m.r) ?? 0
      const g = avg((m) => m.g) ?? 0
      const b = avg((m) => m.b) ?? 0
      const w = avg((m) => m.w)
      const a = avg((m) => m.a)
      const uv = avg((m) => m.uv)
      return {
        kind: 'colour',
        isUniform,
        r,
        g,
        b,
        w,
        a,
        uv,
        combinedCss: computeCombinedCss(r, g, b, w, a, uv),
      }
    }
    case 'position': {
      const members = resolutions
        .filter((r) => r.kind === 'position')
        .map((r) => ({ pan: readChannel(r.pan), tilt: readChannel(r.tilt) }))
      const pan = Math.round(members.reduce((sum, m) => sum + m.pan, 0) / members.length)
      const tilt = Math.round(members.reduce((sum, m) => sum + m.tilt, 0) / members.length)
      const isUniform = members.every((m) => m.pan === members[0].pan && m.tilt === members[0].tilt)
      const panRange = first.panMax - first.panMin
      const tiltRange = first.tiltMax - first.tiltMin
      return {
        kind: 'position',
        isUniform,
        pan,
        tilt,
        panNormalized: panRange > 0 ? (pan - first.panMin) / panRange : 0.5,
        tiltNormalized: tiltRange > 0 ? (tilt - first.tiltMin) / tiltRange : 0.5,
      }
    }
    case 'setting':
    case 'colour-setting': {
      const levels = resolutions
        .filter((r) => r.kind === 'setting' || r.kind === 'colour-setting')
        .map((r) => readChannel(r.property.channel))
      const isUniform = levels.every((level) => level === levels[0])
      return {
        kind: 'setting',
        isUniform,
        level: levels[0],
        option: isUniform ? resolveSettingOption(first.property.options, levels[0]) : undefined,
      }
    }
  }
}

function signaturesEqual(a: readonly number[], b: readonly number[]): boolean {
  if (a.length !== b.length) return false
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) return false
  }
  return true
}

/**
 * Live values for one row: a single subscription set covering every channel
 * the row's visible cells reference, snapshotted as `ColumnKey → CellValue`.
 * The snapshot keeps its object identity while none of the row's channels
 * change, so unrelated channel traffic never re-renders the row. This is the
 * per-row alternative to per-cell value hooks, which at table scale would open
 * thousands of subscriptions.
 */
export function useRowValues(cells: readonly RowCell[]): RowValues {
  // `cells` gets a fresh identity whenever the row list is rebuilt (buildRows
  // mints new Row objects), but the underlying channel set rarely changes.
  // Key the subscription on the channel-key string so a filter keystroke or
  // lit-set change doesn't tear down and re-register every channel
  // subscription for every mounted row.
  const channelsKey = useMemo(
    () =>
      cells
        .flatMap((cell) => cell.resolutions.flatMap(resolutionChannels))
        .map(channelKey)
        .join('|'),
    [cells],
  )

  const channels = useMemo<ChannelRef[]>(
    () =>
      channelsKey === ''
        ? []
        : channelsKey.split('|').map((key) => {
            const [universe, channelNo] = key.split(':')
            return { universe: Number(universe), channelNo: Number(channelNo) }
          }),
    [channelsKey],
  )

  const cachedRef = useRef<{
    cells: readonly RowCell[]
    signature: number[]
    values: RowValues
  } | null>(null)

  const subscribe = useCallback(
    (callback: () => void) => subscribeToChannels(channels, callback),
    [channels],
  )

  const getSnapshot = useCallback((): RowValues => {
    // Every cell value is a pure function of these channel reads AND the
    // resolutions that produced them, so the cache is valid only while both
    // are unchanged — a descriptor change with identical channel values (a
    // repatch while idle) must not serve values computed from old options.
    // Wrapped rather than passed point-free: `getChannelValue` takes an optional channel
    // source second, and `map` would hand it the array index.
    const signature = channels.map((ch) => getChannelValue(ch))
    const cached = cachedRef.current
    if (cached && cached.cells === cells && signaturesEqual(cached.signature, signature)) {
      return cached.values
    }
    const values: RowValues = {}
    for (const cell of cells) {
      const value = aggregateCellValue(cell.resolutions)
      if (value) values[cell.col] = value
    }
    cachedRef.current = { cells, signature, values }
    return values
  }, [cells, channels])

  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
}
