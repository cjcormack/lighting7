import type { StackTimeline, TimelineLayer } from '@/api/stackTimelineApi'
import { effectColumnId, layerColumnId, layerTargets, sceneryColumnId } from './columns'
import type { TimelineCell, TimelineColumn } from './model'

/** A mask family as one letter: `C` a Colour-only layer, `IP` Intensity and Position. */
const MASK_LETTER: Record<string, string> = { INTENSITY: 'I', COLOUR: 'C', POSITION: 'P', BEAM: 'B' }

const BLEND_GLYPH: Record<string, string> = { ADDITIVE: 'ADD', MULTIPLY: 'MUL', MAX: 'MAX', MIN: 'MIN' }

/**
 * A layer's non-default settings in the order a cell draws them: its amount, blend, mask, stomp,
 * a timer, a master of its own, and *off* for a disabled layer.
 */
export function layerGlyphs(layer: TimelineLayer): string[] {
  const out: string[] = []
  if (Math.abs(layer.amount - 1) > 1e-9) out.push(`${Math.round(layer.amount * 100)}%`)
  // As written, since the desk reads both case-blind.
  const blend = BLEND_GLYPH[layer.blendMode.toUpperCase()]
  if (blend) out.push(blend)
  if (layer.propertyMask) {
    const letters = layer.propertyMask
      .split(',')
      .map((f) => MASK_LETTER[f.trim().toUpperCase()] ?? '')
      .join('')
    if (letters) out.push(letters)
  }
  if (layer.stomp) out.push('S')
  if (layer.timed) out.push('⏱')
  if (layer.speedMasterUuid != null || layer.rateSpeedMasterUuid != null) out.push('M')
  if (!layer.enabled) out.push('off')
  return out
}

/** Every cue entry's cells, by entry id then column id. A column a cue leaves empty is absent. */
export type TimelineCells = Map<number, Map<string, TimelineCell>>

const EMPTY: TimelineCell = { filled: null, tracked: null }

/** The cell at [entryId] × [columnId], empty where nothing fills it. */
export function cellAt(cells: TimelineCells, entryId: number, columnId: string): TimelineCell {
  return cells.get(entryId)?.get(columnId) ?? EMPTY
}

/**
 * Each cue's cells (§3.7): a layer fills its source's column on each of its targets (two targets,
 * two cells, one rank), an ad-hoc effect its signature's, a scenery change its element's, and a
 * state tracked into the cue marks the element's cell even where the cue changes nothing. Where one
 * cue layers a record twice on one target, the upper layer is the one drawn. Only [columns] are
 * filled, so a filtered list costs nothing for what it hides.
 */
export function cells(timeline: StackTimeline, columns: readonly TimelineColumn[]): TimelineCells {
  const wanted = new Set(columns.map((c) => c.id))
  const out: TimelineCells = new Map()
  for (const entry of timeline.entries) {
    if (entry.kind !== 'CUE') continue
    const row = new Map<string, TimelineCell>()
    entry.layers.forEach((layer, index) => {
      for (const target of layerTargets(layer)) {
        const id = layerColumnId(layer, target)
        if (!wanted.has(id)) continue
        row.set(id, { filled: { kind: 'layer', layer, rank: index + 1, glyphs: layerGlyphs(layer) }, tracked: null })
      }
    })
    for (const effect of entry.effects) {
      const id = effectColumnId(effect)
      if (wanted.has(id) && !row.has(id)) row.set(id, { filled: { kind: 'effect', effect }, tracked: null })
    }
    for (const change of entry.scenery) {
      const id = sceneryColumnId(change.elementUuid)
      if (wanted.has(id)) row.set(id, { filled: { kind: 'scenery', state: change.state, transitionMs: change.transitionMs }, tracked: null })
    }
    for (const tracked of entry.trackedScenery) {
      const id = sceneryColumnId(tracked.elementUuid)
      if (!wanted.has(id)) continue
      row.set(id, { filled: row.get(id)?.filled ?? null, tracked: tracked.state })
    }
    out.set(entry.entryId, row)
  }
  return out
}
