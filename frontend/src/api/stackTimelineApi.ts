import type { CueTarget } from './cuesApi'
import type { SceneryState } from './sceneryApi'

/**
 * `GET /projects/{id}/cue-stacks/{sid}/timeline` (lighting7 stack-timeline plan §3.5): every entry
 * of a stack in list order, markers included, with each cue's stored layers, ad-hoc effects,
 * scenery and tracked scenery. The grid is derived from it on the client (`lib/stackTimeline/`);
 * names, fade, follow, numbers and live state are the stack list's and the run frames'.
 */
export interface StackTimeline {
  stackId: number
  entries: TimelineEntry[]
}

export interface TimelineEntry {
  entryId: number
  kind: 'CUE' | 'MARKER'
  /** A marker's label. */
  label?: string | null
  cueId?: number | null
  /** In layer order, bottom first: a layer's rank is its place here, from 1. */
  layers: TimelineLayer[]
  effects: TimelineEffect[]
  scenery: TimelineScenery[]
  trackedScenery: TimelineTrackedScenery[]
}

export interface TimelineLayer {
  id: number
  source: { kind: 'LOOK' | 'TEMPLATE'; id: number; uuid: string; name: string; families: string[] }
  targets: CueTarget[]
  /** The fixture keys the layer reaches: its targets with groups expanded, else its source's own. */
  heads: string[]
  sortOrder: number
  enabled: boolean
  amount: number
  blendMode: string
  propertyMask: string | null
  stomp: boolean
  /** Fires on a delay, an interval or a random window rather than at the GO. */
  timed: boolean
  speedMasterUuid: string | null
  rateSpeedMasterUuid: string | null
}

export interface TimelineEffect {
  id: number
  uuid: string
  /** The run key: rows of neighbouring cues that share one draw as one run (D7). */
  runUuid: string | null
  effectType: string
  propertyName: string | null
  targetType: string
  targetKey: string
  family: string | null
  heads: string[]
  /** What a GO compares to hand a run on unchanged; a change inside a run is an edit in place (◆). */
  settings: Record<string, unknown>
}

export interface TimelineScenery {
  elementUuid: string
  state: SceneryState
  transitionMs: number | null
}

/** A state the cue shows without setting it; `fromCueId` null tracks from the stack's set. */
export interface TimelineTrackedScenery {
  elementUuid: string
  state: SceneryState
  fromCueId: number | null
}
