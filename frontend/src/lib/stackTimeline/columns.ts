import type { CueTarget } from '@/api/cuesApi'
import type { StackTimeline, TimelineFx, TimelineLayer } from '@/api/stackTimelineApi'
import { TIMELINE_KINDS, type TimelineColumn, type TimelineKind } from './model'
import type { StoredExtraColumn } from './columnOrder'

/** What the columns are called — the rig's and the scene's names, which the timeline does not carry. */
export interface TimelineNames {
  /** A fixture's or a group's name. */
  target: (target: CueTarget) => string
  /** A scenery element's name and the words for its kind, or undefined for one the scene lacks. */
  element: (uuid: string) => { name: string; kind: string } | undefined
  /** A group's member keys, where the rig knows it. */
  members?: (group: string) => readonly string[] | undefined
  /** A cell's parent fixture, where the rig knows it. */
  parentOf?: (key: string) => string | undefined
}

function targetId(target: CueTarget | null): string {
  return target == null ? '*' : `${target.type}:${target.key}`
}

/** The column a Look or palette layer fills for [target] — null for the layer's own fixtures. */
export function layerColumnId(layer: TimelineLayer, target: CueTarget | null): string {
  return `${layer.source.kind === 'LOOK' ? 'look' : 'palette'}:${layer.source.id}:${targetId(target)}`
}

/** Every column one layer fills: one per target it names, or one for its own fixtures (D3). */
export function layerTargets(layer: TimelineLayer): (CueTarget | null)[] {
  return layer.targets.length === 0 ? [null] : layer.targets
}

export function fxColumnId(effect: TimelineFx): string {
  return `fx:${effect.fxType}:${effect.attribute ?? ''}:${effect.targetType}:${effect.targetKey}`
}

export function sceneryColumnId(elementUuid: string): string {
  return `scenery:${elementUuid}`
}

/**
 * The heads one target of a layer reaches. The desk answers a layer's heads as a whole, so a target
 * of a layer that names several keeps the layer's heads it covers: itself and its cells for a
 * fixture, a group's members and theirs — or every one of the layer's, for a group the rig does not
 * know, which can only over-reach.
 */
function targetHeads(layer: TimelineLayer, target: CueTarget | null, names: TimelineNames): string[] {
  if (target == null || layer.targets.length <= 1) return layer.heads
  const members = target.type === 'group' ? names.members?.(target.key) : [target.key]
  if (members == null) return layer.heads
  const covered = new Set(members)
  return layer.heads.filter((head) => covered.has(head) || covered.has(names.parentOf?.(head) ?? ''))
}

function fxLabel(effect: TimelineFx): string {
  const attribute = effect.attribute
  return attribute ? `${effect.fxType} · ${attribute.charAt(0).toUpperCase()}${attribute.slice(1)}` : effect.fxType
}

/** A selection target's key as the timeline names it: each item's key, joined. */
function fxSub(effect: TimelineFx, names: TimelineNames): string {
  if (effect.targetType === 'selection') {
    try {
      const items = JSON.parse(effect.targetKey) as CueTarget[]
      return items.map((t) => names.target(t)).join(', ')
    } catch {
      return effect.targetKey
    }
  }
  return names.target({ type: effect.targetType as CueTarget['type'], key: effect.targetKey })
}

/**
 * The timeline's columns (§3.7): grouped Looks, Palettes, Effects, Scenery, each group in order of
 * first use down the stack (C7) — down the rows, then a cue's layers bottom first, its effects and
 * its scenery in their stored order — with [order], this window's arrangement, applied over it.
 * A scenery element only a stack's set or an earlier stack moves is a column too: it tracks.
 */
export function columns(timeline: StackTimeline, names: TimelineNames, order: readonly string[] | null = null): TimelineColumn[] {
  const byId = new Map<string, TimelineColumn>()
  const seen = new Map<TimelineKind, TimelineColumn[]>(TIMELINE_KINDS.map((k) => [k, []]))
  const add = (column: Omit<TimelineColumn, 'heads' | 'families'>, heads: readonly string[], families: readonly string[]) => {
    let existing = byId.get(column.id)
    if (existing == null) {
      existing = { ...column, heads: [], families: [] }
      byId.set(column.id, existing)
      seen.get(column.kind)!.push(existing)
    }
    for (const head of heads) if (!existing.heads.includes(head)) existing.heads.push(head)
    for (const family of families) if (!existing.families.includes(family)) existing.families.push(family)
  }

  for (const entry of timeline.entries) {
    if (entry.kind !== 'CUE') continue
    for (const layer of entry.layers) {
      const kind: TimelineKind = layer.source.kind === 'LOOK' ? 'look' : 'palette'
      for (const target of layerTargets(layer)) {
        add(
          {
            id: layerColumnId(layer, target),
            kind,
            name: layer.source.name,
            sub: target == null ? (kind === 'look' ? 'own fixtures' : 'own heads') : names.target(target),
            sourceId: layer.source.id,
            target,
          },
          targetHeads(layer, target, names),
          layer.source.families,
        )
      }
    }
    for (const effect of entry.fx) {
      add(
        {
          id: fxColumnId(effect),
          kind: 'fx',
          name: fxLabel(effect),
          sub: fxSub(effect, names),
          sourceId: null,
          target: null,
          effect: { fxType: effect.fxType, attribute: effect.attribute, targetType: effect.targetType, targetKey: effect.targetKey },
        },
        effect.heads,
        effect.family ? [effect.family] : [],
      )
    }
    for (const change of [...entry.scenery, ...entry.trackedScenery]) {
      const element = names.element(change.elementUuid)
      add(
        {
          id: sceneryColumnId(change.elementUuid),
          kind: 'scenery',
          name: element?.name ?? 'Scenery',
          sub: element?.kind ?? '',
          sourceId: null,
          target: null,
          elementUuid: change.elementUuid,
        },
        [],
        [],
      )
    }
  }
  return TIMELINE_KINDS.flatMap((kind) => applyColumnOrder(seen.get(kind)!, order))
}

/**
 * [group] in this window's [order] (C7): the columns it names first, in its order, then the rest in
 * first use. An id it names that no longer exists is skipped, so an order outlives a column.
 */
export function applyColumnOrder(group: readonly TimelineColumn[], order: readonly string[] | null): TimelineColumn[] {
  if (order == null || order.length === 0) return [...group]
  const rank = new Map(order.map((id, i) => [id, i]))
  const named = group.filter((c) => rank.has(c.id)).sort((a, b) => rank.get(a.id)! - rank.get(b.id)!)
  return [...named, ...group.filter((c) => !rank.has(c.id))]
}

/**
 * The order after moving column [id] one place left (-1) or right (+1) past its neighbour in
 * [visible] — the columns the filters leave, [all] by default — within its kind, as the ids of
 * every column, hidden ones included: what a window stores. The two trade places, so a column the
 * filters hide stays where it was. Unchanged at the group's edge.
 */
export function moveColumn(
  all: readonly TimelineColumn[],
  id: string,
  direction: -1 | 1,
  visible: readonly TimelineColumn[] = all,
): string[] {
  const ids = all.map((c) => c.id)
  const shownAt = visible.findIndex((c) => c.id === id)
  const neighbour = visible[shownAt + direction]
  if (shownAt < 0 || neighbour == null || neighbour.kind !== visible[shownAt].kind) return ids
  const at = ids.indexOf(id)
  const to = ids.indexOf(neighbour.id)
  if (at < 0 || to < 0) return ids
  ;[ids[at], ids[to]] = [ids[to], ids[at]]
  return ids
}

/** The id a Look or palette column on [target] has — what a derived column of the same cell gets. */
export function recordColumnId(kind: 'look' | 'palette', sourceId: number, target: CueTarget | null): string {
  return `${kind}:${sourceId}:${targetId(target)}`
}

/**
 * [columns] with this window's added [extras] (§4.4), each kind in this window's [order] with its
 * extras after its own until the order names them — an extra the stack now fills is derived
 * already, so it is left out. An extra's heads are its target's: a fixture or cell itself, a
 * group's members; on its own fixtures it reaches nothing until a cell is filled.
 */
export function withExtraColumns(
  columns: readonly TimelineColumn[],
  extras: readonly StoredExtraColumn[],
  names: TimelineNames,
  order: readonly string[] | null = null,
): TimelineColumn[] {
  const ids = new Set(columns.map((c) => c.id))
  const added = extras
    .map((extra): TimelineColumn => ({
      id: recordColumnId(extra.kind, extra.sourceId, extra.target),
      kind: extra.kind,
      name: extra.name,
      sub: extra.target == null ? (extra.kind === 'look' ? 'own fixtures' : 'own heads') : names.target(extra.target),
      families: extra.families,
      heads: extra.target == null ? [] : extra.target.type === 'group' ? [...(names.members?.(extra.target.key) ?? [])] : [extra.target.key],
      sourceId: extra.sourceId,
      target: extra.target,
    }))
    .filter((c) => !ids.has(c.id))
  return TIMELINE_KINDS.flatMap((kind) =>
    applyColumnOrder([...columns.filter((c) => c.kind === kind), ...added.filter((c) => c.kind === kind)], order),
  )
}
