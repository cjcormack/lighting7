import type { CueTarget } from '@/api/cuesApi'
import type { SceneryState } from '@/api/sceneryApi'
import type { TimelineFx, TimelineLayer } from '@/api/stackTimelineApi'

/** The four column groups, in the order the grid draws them (C7). */
export type TimelineKind = 'look' | 'palette' | 'fx' | 'scenery'

export const TIMELINE_KINDS: readonly TimelineKind[] = ['look', 'palette', 'fx', 'scenery']

/**
 * One column (stack-timeline plan §3.2): a Look or a palette on one target — or on none, its own
 * fixtures — an effect by type, property and target, or a scenery element. Nothing stores it; it is
 * derived from the stored rows down the stack.
 */
export interface TimelineColumn {
  /** `look:12:fixture:hex-1`, `palette:3:*`, `effect:Pulse:dimmer:group:wash`, `scenery:<uuid>`. */
  id: string
  kind: TimelineKind
  /** The record's name — the Look's, the palette's, the effect's, the element's. */
  name: string
  /** What it is on — the target, *own fixtures*, a scenery element's kind. */
  sub: string
  /** Attribute families (`PropertyMaskGroup` names) it touches; empty for scenery. */
  families: string[]
  /** Every fixture key any of its cells reaches. */
  heads: string[]
  /** A Look's or a palette's source id; an effect's type; a scenery element's uuid. */
  sourceId: number | null
  /** The one target a Look or palette column is on, or null for its own fixtures. */
  target: CueTarget | null
  effect?: { fxType: string; propertyName: string | null; targetType: string; targetKey: string }
  elementUuid?: string
}

/** A filled layer cell's marks — each a non-default setting of its layer. */
export interface TimelineLayerCell {
  kind: 'layer'
  layer: TimelineLayer
  /** 1 at the bottom of the cue's layer order; a layer's two targets share it. */
  rank: number
  /** Non-default settings as the grid draws them: `60%`, `ADD`, `C`, `S`, `⏱`, `M`, `off`. */
  glyphs: string[]
}

export interface TimelineFxCell {
  kind: 'fx'
  effect: TimelineFx
}

export interface TimelineSceneryCell {
  kind: 'scenery'
  state: SceneryState
  transitionMs: number | null
}

export type TimelineFilled = TimelineLayerCell | TimelineFxCell | TimelineSceneryCell

/**
 * One column in one cue: [filled] by that cue's own layer, row or change, or null. A scenery cell
 * that changes nothing may still carry [tracked], the state that reaches the cue from above.
 */
export interface TimelineCell {
  filled: TimelineFilled | null
  tracked: SceneryState | null
}

/** How one cell draws against its neighbours down the column (§3.7 `runs`). */
export interface TimelineRunMark {
  /** Filled and not joined to the cell above: the bar's rounded top. */
  start: boolean
  /** Filled and not joined to the cell below: the bar's rounded foot. */
  end: boolean
  /** An effect cell whose run key differs from a filled cell above it: two keys meet (↺). */
  restart: boolean
  /** An effect cell joined to the one above whose settings differ: an edit in place (◆). */
  changed: boolean
  /** A scenery cell carrying the tracked line: the state reaches it from above. */
  trackedLine: boolean
}

/** A marker row's mark in one column: the run, or the tracked line, passing through it. */
export interface TimelineMarkerMark {
  through: boolean
  trackedLine: boolean
}
