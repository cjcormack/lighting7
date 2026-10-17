import { useSyncExternalStore } from 'react'
import type { ActiveEffect } from '@/store/fixtureFx'
import type { ProgrammerLayer } from '@/api/programmerWsApi'
import { createSyncStore, sessionStorageArea } from './syncStore'

/**
 * **The applied tables** (venue-visit plan S14): the programmer's running effects and its layer
 * stack drawn full width, one route with a tab per table — `/programmer/applied?table=fx`. This is
 * the model, with no React beyond the store's hook: which table a window shows, the view each table
 * keeps (sort, filter, partition chip) and how both ride the query string, and the row rules the
 * three tables share with the rail.
 *
 * **Which table is a per-window fact** (`applied.table`, `sessionStorage`), as the busk view's
 * focus is: the Screens sheet sets it on another window through `windows.viewOptions {table}`, the
 * announce carries it, and the route mirrors it into `?table=` with `replace`. A `?table=` arriving
 * (a ⌘K deep link, a copied link) wins and is remembered.
 *
 * **A table's view rides the query string and is remembered per window** — `?sort=`, `?dir=`, `?q=`
 * and the table's chip (`?home=` on Applied FX, `?family=` on Applied Palettes). A change is
 * remembered and mirrored with `replace`; arriving with any of them is the view, and arriving with
 * none takes the remembered one. Scroll is remembered beside it and kept out of the URL, so a
 * copied link does not land half way down someone else's list.
 */

export type AppliedTable = 'fx' | 'looks' | 'palettes'
export const APPLIED_TABLES: readonly AppliedTable[] = ['fx', 'looks', 'palettes']

export const APPLIED_TABLE_LABELS: Readonly<Record<AppliedTable, string>> = {
  fx: 'Applied FX',
  looks: 'Applied Looks',
  palettes: 'Applied Palettes',
}

/** The tab's own word, with *Applied* on the header's breadcrumb. */
export const APPLIED_TABLE_TABS: Readonly<Record<AppliedTable, string>> = {
  fx: 'FX',
  looks: 'Looks',
  palettes: 'Palettes',
}

/** The `viewOptions` key the announce carries and `windows.viewOptions` sets. */
export const VIEW_OPTION_APPLIED_TABLE = 'table'

export function parseAppliedTable(raw: unknown): AppliedTable | null {
  return raw === 'fx' || raw === 'looks' || raw === 'palettes' ? raw : null
}

const tableStore = createSyncStore<AppliedTable>({
  key: 'applied.table',
  fallback: 'fx',
  parse: (parsed) => parseAppliedTable(parsed) ?? 'fx',
  storage: sessionStorageArea,
})

export function useAppliedTable(): AppliedTable {
  return useSyncExternalStore(tableStore.subscribe, tableStore.getSnapshot, tableStore.getServerSnapshot)
}

export function getAppliedTable(): AppliedTable {
  return tableStore.getSnapshot()
}

export function setAppliedTable(table: AppliedTable): void {
  tableStore.set(table)
}

/** A `windows.viewOptions` frame aimed at this window's applied view: `{table}`, ignored when unknown. */
export function applyAppliedViewOptions(options: Readonly<Record<string, string>>): void {
  const table = parseAppliedTable(options[VIEW_OPTION_APPLIED_TABLE])
  if (table != null) setAppliedTable(table)
}

/** Test seam. */
export function resetAppliedTableStore(): void {
  tableStore.reset()
}

// ── The view ────────────────────────────────────────────────────────────────────────────────

export type SortDir = 'asc' | 'desc'

export interface AppliedView {
  /** A column key, or null for the rail's own order. */
  sort: string | null
  dir: SortDir
  /** The filter field. */
  q: string
  /** The partition chip — an FX home, a palette family — or null for *All*. */
  chip: string | null
}

export const DEFAULT_APPLIED_VIEW: AppliedView = { sort: null, dir: 'asc', q: '', chip: null }

/** The query-string name of each table's chip, or null for a table that has none. */
export const CHIP_PARAM: Readonly<Record<AppliedTable, string | null>> = {
  fx: 'home',
  looks: null,
  palettes: 'family',
}

/**
 * The view a query string states for [table], or null when it states none — no `sort`, `q` or chip —
 * which is what tells an arrival that should take the remembered view from one that brings its own.
 */
export function viewFromSearch(params: URLSearchParams, table: AppliedTable): AppliedView | null {
  const chipParam = CHIP_PARAM[table]
  const states =
    params.has('sort') || params.has('dir') || params.has('q') || (chipParam != null && params.has(chipParam))
  if (!states) return null
  const sort = params.get('sort')
  return {
    sort: sort != null && sort !== '' ? sort : null,
    dir: params.get('dir') === 'desc' ? 'desc' : 'asc',
    q: params.get('q') ?? '',
    chip: chipParam != null ? (params.get(chipParam) || null) : null,
  }
}

/** The query string for [table] showing [view]: `table`, then only what differs from the default. */
export function appliedSearch(table: AppliedTable, view: AppliedView): string {
  const params = new URLSearchParams()
  params.set('table', table)
  if (view.sort != null) {
    params.set('sort', view.sort)
    if (view.dir === 'desc') params.set('dir', 'desc')
  }
  if (view.q !== '') params.set('q', view.q)
  const chipParam = CHIP_PARAM[table]
  if (chipParam != null && view.chip != null) params.set(chipParam, view.chip)
  return `?${params.toString()}`
}

/** A header press: ascending, then descending, then back to the rail's order. */
export function nextSort<V extends { sort: string | null; dir: SortDir }>(view: V, key: string): V {
  if (view.sort !== key) return { ...view, sort: key, dir: 'asc' }
  if (view.dir === 'asc') return { ...view, dir: 'desc' }
  return { ...view, sort: null, dir: 'asc' }
}

function readSession(key: string): unknown {
  try {
    const raw = window.sessionStorage.getItem(key)
    return raw == null ? null : JSON.parse(raw)
  } catch {
    return null
  }
}

function writeSession(key: string, value: unknown): void {
  try {
    window.sessionStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Storage unavailable — the view still works, it just is not remembered.
  }
}

/** The view this window last left [table] on, or the default. */
export function rememberedView(table: AppliedTable): AppliedView {
  const stored = readSession(`applied.view.${table}`)
  if (stored == null || typeof stored !== 'object') return DEFAULT_APPLIED_VIEW
  const v = stored as Partial<AppliedView>
  return {
    sort: typeof v.sort === 'string' && v.sort !== '' ? v.sort : null,
    dir: v.dir === 'desc' ? 'desc' : 'asc',
    q: typeof v.q === 'string' ? v.q : '',
    chip: typeof v.chip === 'string' && v.chip !== '' && CHIP_PARAM[table] != null ? v.chip : null,
  }
}

export function rememberView(table: AppliedTable, view: AppliedView): void {
  writeSession(`applied.view.${table}`, view)
}

export function rememberedScroll(table: AppliedTable): number {
  const stored = readSession(`applied.scroll.${table}`)
  return typeof stored === 'number' && Number.isFinite(stored) && stored > 0 ? stored : 0
}

export function rememberScroll(table: AppliedTable, top: number): void {
  writeSession(`applied.scroll.${table}`, Math.max(0, Math.round(top)))
}

/** The address of [table] on [projectId] with this window's remembered view for it. */
export function appliedTablePath(projectId: number, table: AppliedTable, view: AppliedView = rememberedView(table)): string {
  return `/projects/${projectId}/programmer/applied${appliedSearch(table, view)}`
}

// ── Sorting and filtering ───────────────────────────────────────────────────────────────────

export type SortValue = string | number | null

/**
 * [rows] sorted by [valueOf] in [dir], stable, nulls last whichever way; the rows as given when
 * [sort] is null. Strings compare without case, numbers as numbers.
 */
export function sortRows<T>(rows: readonly T[], sort: string | null, dir: SortDir, valueOf: (row: T, key: string) => SortValue): T[] {
  if (sort == null) return [...rows]
  const sign = dir === 'desc' ? -1 : 1
  return rows
    .map((row, index) => ({ row, index, value: valueOf(row, sort) }))
    .sort((a, b) => {
      if (a.value == null && b.value == null) return a.index - b.index
      if (a.value == null) return 1
      if (b.value == null) return -1
      const cmp =
        typeof a.value === 'number' && typeof b.value === 'number'
          ? a.value - b.value
          : String(a.value).localeCompare(String(b.value), undefined, { sensitivity: 'base', numeric: true })
      return cmp === 0 ? a.index - b.index : cmp * sign
    })
    .map((entry) => entry.row)
}

/** Whether any of [fields] holds the filter's text, without case. */
export function matchesFilter(q: string, fields: readonly (string | null | undefined)[]): boolean {
  const needle = q.trim().toLowerCase()
  if (needle === '') return true
  return fields.some((field) => field != null && field.toLowerCase().includes(needle))
}

// ── Applied FX ──────────────────────────────────────────────────────────────────────────────

/** Where a running effect lives — the rail's home badge, as a partition. */
export type FxHome = 'yours' | 'layer' | 'cue' | 'base'
export const FX_HOMES: readonly FxHome[] = ['yours', 'layer', 'cue', 'base']
export const FX_HOME_LABELS: Readonly<Record<FxHome, string>> = {
  yours: 'Yours',
  layer: 'Layers',
  cue: 'Cue',
  base: 'Base',
}

/** The rail's `homeOf`, as a partition: a layer's, the cue's, the programmer band's, or the show's. */
export function fxHomeOf(effect: Pick<ActiveEffect, 'programmerLayerId' | 'cueId' | 'programmerOwned'>): FxHome {
  if (effect.programmerLayerId != null) return 'layer'
  if (effect.cueId != null) return 'cue'
  if (effect.programmerOwned) return 'yours'
  return 'base'
}

export function parseFxHome(raw: string | null): FxHome | null {
  return raw === 'yours' || raw === 'layer' || raw === 'cue' || raw === 'base' ? raw : null
}

/** What an effect is doing, as the desk reports it — the State column and its sort. */
export type FxState = 'running' | 'paused' | 'held' | 'spent' | 'leaving'

export function fxStateOf(effect: Pick<ActiveEffect, 'isRunning' | 'spent' | 'leaving'>, heldByBlind: boolean): FxState {
  if (effect.spent === true) return 'spent'
  if (effect.leaving === true) return 'leaving'
  if (heldByBlind) return 'held'
  return effect.isRunning ? 'running' : 'paused'
}

export const FX_STATE_LABELS: Readonly<Record<FxState, string>> = {
  running: 'Running',
  paused: 'Paused',
  held: 'Held · Blind',
  spent: 'Spent',
  leaving: 'Leaving',
}

// ── Applied Looks and Palettes ──────────────────────────────────────────────────────────────

/** One layer of the programmer's stack, with its place in it — the rail's order badge. */
export interface AppliedLayer {
  layer: ProgrammerLayer
  /** 1-based, `sortOrder` ascending — the number the rail's row draws. */
  position: number
}

/**
 * The layers of one kind, **top wins** — the order the rail draws them in (`LookStack`'s dense
 * density reverses the array), each with the position the rail numbers it by.
 */
export function layersOfKind(layers: readonly ProgrammerLayer[], kind: 'LOOK' | 'TEMPLATE'): AppliedLayer[] {
  return layers
    .map((layer, index) => ({ layer, position: index + 1 }))
    .filter((entry) => entry.layer.source?.kind === kind)
    .reverse()
}

/**
 * What the effects a layer spawned are doing: `spent` once every one of them has run its count (the
 * desk keeps a spent N-shot on its layer, painting nothing), `running` while any still runs, null for
 * a layer that spawned none.
 */
export function layerEffectState(
  layerId: number,
  effects: readonly Pick<ActiveEffect, 'programmerLayerId' | 'spent'>[],
): 'running' | 'spent' | null {
  const own = effects.filter((effect) => effect.programmerLayerId === layerId)
  if (own.length === 0) return null
  return own.every((effect) => effect.spent === true) ? 'spent' : 'running'
}
