import type { Cue } from '@/api/cuesApi'
import { matchesFilter, sortRows, type SortDir, type SortValue } from './programmerTables'

/**
 * **The Cues table's model** (lighting7 cue model plan §3.7, D13): every cue once, read off the
 * project's cue list, which carries each cue's entries and what else names it (*Used by*). The
 * route is `/projects/:id/cues`; this is its view, with no React.
 *
 * **The view is the address** (the applied tables' rule, venue-visit plan S14): `?q=` the filter,
 * `?stack=` one stack's cues, `?show=` *loose*, *shared* or *pad*, `?cue=` one cue (the busk pad's
 * "open cue" on a loose cue), and `?sort=` / `?dir=`. Each is mirrored with `replace`.
 */

/** The partition chips: a cue in no stack, one in two places or more, one a busk pad presses. */
export type CuesShow = 'loose' | 'shared' | 'pad'
export const CUES_SHOWS: readonly CuesShow[] = ['loose', 'shared', 'pad']
export const CUES_SHOW_LABELS: Readonly<Record<CuesShow, string>> = {
  loose: 'Loose',
  shared: 'Shared',
  pad: 'On a pad',
}

export interface CuesView {
  q: string
  /** Only the cues with an entry in this stack. */
  stack: number | null
  show: CuesShow | null
  /** Only this cue. */
  cue: number | null
  /** A column key, or null for the list's own order (by name). */
  sort: string | null
  dir: SortDir
}

export const DEFAULT_CUES_VIEW: CuesView = { q: '', stack: null, show: null, cue: null, sort: null, dir: 'asc' }

function positiveId(raw: string | null): number | null {
  if (raw == null) return null
  const id = Number(raw)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

export function cuesViewFromSearch(params: URLSearchParams): CuesView {
  const show = params.get('show')
  const sort = params.get('sort')
  return {
    q: params.get('q') ?? '',
    stack: positiveId(params.get('stack')),
    show: CUES_SHOWS.includes(show as CuesShow) ? (show as CuesShow) : null,
    cue: positiveId(params.get('cue')),
    sort: sort != null && sort !== '' ? sort : null,
    dir: params.get('dir') === 'desc' ? 'desc' : 'asc',
  }
}

/** The query string showing [view]: only what differs from the default, `''` for none. */
export function cuesSearch(view: CuesView): string {
  const params = new URLSearchParams()
  if (view.q !== '') params.set('q', view.q)
  if (view.stack != null) params.set('stack', String(view.stack))
  if (view.show != null) params.set('show', view.show)
  if (view.cue != null) params.set('cue', String(view.cue))
  if (view.sort != null) {
    params.set('sort', view.sort)
    if (view.dir === 'desc') params.set('dir', 'desc')
  }
  const text = params.toString()
  return text === '' ? '' : `?${text}`
}

/** The table filtered to one cue — where a loose cue's busk pad opens. */
export function cuesTablePath(projectId: number, cueId?: number): string {
  return `/projects/${projectId}/cues${cueId != null ? cuesSearch({ ...DEFAULT_CUES_VIEW, cue: cueId }) : ''}`
}

/**
 * Where a cue opens from outside the Show view — a busk pad's "open cue" (cue model plan §4): its
 * first entry's card, else, loose, the Cues table filtered to it.
 */
export function cueOpenPath(
  projectId: number,
  cue: { id: number; entries?: readonly { stackId: number; entryId: number }[] },
): string {
  const first = cue.entries?.[0]
  return first != null
    ? `/projects/${projectId}/show/stacks/${first.stackId}?cue=${cue.id}&entry=${first.entryId}`
    : cuesTablePath(projectId, cue.id)
}

export function isLoose(cue: Cue): boolean {
  return (cue.entries?.length ?? 0) === 0
}

/** In two places or more — two stacks, or twice in one, as the shared chip counts it. */
export function isShared(cue: Cue): boolean {
  return (cue.entries?.length ?? 0) >= 2
}

export function padCount(cue: Cue): number {
  return cue.usedBy?.pads?.length ?? 0
}

export function matchesShow(cue: Cue, show: CuesShow): boolean {
  switch (show) {
    case 'loose':
      return isLoose(cue)
    case 'shared':
      return isShared(cue)
    case 'pad':
      return padCount(cue) > 0
  }
}

/** How many cues each chip would show, over [cues] as narrowed by everything but the chip. */
export function showCounts(cues: readonly Cue[]): Record<CuesShow, number> {
  const counts: Record<CuesShow, number> = { loose: 0, shared: 0, pad: 0 }
  for (const cue of cues) for (const show of CUES_SHOWS) if (matchesShow(cue, show)) counts[show]++
  return counts
}

/** The filter field: a cue's name, notes, or a place's stack or number (`Q14` or `14`). */
export function matchesQuery(cue: Cue, q: string): boolean {
  return matchesFilter(q, [
    cue.name,
    cue.notes,
    ...(cue.entries ?? []).flatMap((e) => [e.stackName, e.cueNumber != null ? `Q${e.cueNumber}` : null]),
  ])
}

/** [cues] narrowed by every filter but the chip — what the chips count over. */
export function filterBeforeShow(cues: readonly Cue[], view: CuesView): Cue[] {
  return cues.filter(
    (cue) =>
      (view.cue == null || cue.id === view.cue) &&
      (view.stack == null || (cue.entries ?? []).some((e) => e.stackId === view.stack)) &&
      matchesQuery(cue, view.q),
  )
}

/** [cues] as the table draws them: filtered by [view], then sorted by its column. */
export function cuesShown(cues: readonly Cue[], view: CuesView): Cue[] {
  const filtered = filterBeforeShow(cues, view).filter((cue) => view.show == null || matchesShow(cue, view.show))
  return sortRows(filtered, view.sort, view.dir, cueSortValue)
}

/** Every *Used by* reference, counted. */
export function usedByCount(cue: Cue): number {
  return padCount(cue) + (cue.usedBy?.slots?.length ?? 0) + (cue.usedBy?.bindings?.length ?? 0)
}

/** *2 pads · 1 slot*, or null when nothing but its entries names the cue. */
export function describeUsedBy(cue: Cue): string | null {
  const parts: string[] = []
  const count = (n: number, noun: string) => n > 0 && parts.push(`${n} ${noun}${n === 1 ? '' : 's'}`)
  count(padCount(cue), 'pad')
  count(cue.usedBy?.slots?.length ?? 0, 'slot')
  count(cue.usedBy?.bindings?.length ?? 0, 'binding')
  return parts.length > 0 ? parts.join(' · ') : null
}

/** The hover over *Used by*: each pad's page, each slot and each binding by name. */
export function usedByTitle(cue: Cue): string | undefined {
  const lines = [
    ...(cue.usedBy?.pads ?? []).map((p) => `Pad on ${p.pageName}`),
    ...(cue.usedBy?.slots ?? []).map((s) => `Cue slot · ${s.label}`),
    ...(cue.usedBy?.bindings ?? []).map((b) => `Binding · ${b.label}`),
  ]
  return lines.length > 0 ? lines.join('\n') : undefined
}

/** The sortable columns' values; the name is the list's own order. */
export function cueSortValue(cue: Cue, key: string): SortValue {
  switch (key) {
    case 'name':
      return cue.name
    case 'in': {
      const first = cue.entries?.[0]
      return first == null ? null : `${first.stackName} ${first.cueNumber ?? ''}`
    }
    case 'fade':
      return cue.fadeDurationMs ?? 0
    case 'layers':
      return cue.layers.length
    case 'fx':
      return cue.adHocEffects.length
    case 'scenery':
      return cue.scenery?.length ?? 0
    case 'events':
      return cue.events?.length ?? 0
    case 'usedBy':
      return usedByCount(cue)
    case 'notes':
      return cue.notes || null
    default:
      return null
  }
}

export const CUES_SORT_KEYS: ReadonlySet<string> = new Set(['name', 'in', 'fade', 'layers', 'fx', 'scenery', 'events', 'usedBy', 'notes'])
