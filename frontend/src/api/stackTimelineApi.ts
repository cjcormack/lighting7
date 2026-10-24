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
  /** In layer order, bottom first; each layer carries its rank, which counts a layer whose source is gone. */
  layers: TimelineLayer[]
  fx: TimelineFx[]
  scenery: TimelineScenery[]
  trackedScenery: TimelineTrackedScenery[]
}

export interface TimelineLayer {
  id: number
  source: { kind: 'LOOK' | 'PALETTE'; id: number; uuid: string; name: string; families: string[] }
  targets: CueTarget[]
  /** The fixture keys the layer reaches: its targets with groups expanded, else its source's own. */
  heads: string[]
  sortOrder: number
  /** Its place in the cue's layer order, from 1 at the bottom; absent from an older desk, which ranks by place in `layers`. */
  rank?: number
  enabled: boolean
  amount: number
  blendMode: string
  propertyMask: string | null
  stomp: boolean
  /** Fires on a delay, an interval or a random window rather than at the GO. */
  timed: boolean
  delayMs?: number | null
  intervalMs?: number | null
  randomWindowMs?: number | null
  speedMasterUuid: string | null
  rateSpeedMasterUuid: string | null
}

export interface TimelineFx {
  id: number
  uuid: string
  /** The run key: rows of neighbouring cues that share one draw as one run (D7). */
  runUuid: string | null
  fxType: string
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

/** A Look or a palette, as a timeline edit names one. */
export interface TimelineSourceRef {
  kind: 'LOOK' | 'PALETTE'
  id: number
}

/** A Look or palette column's cell: its record, on one target, or on none for its own heads. */
export interface TimelineCellRef {
  source: TimelineSourceRef
  target?: CueTarget | null
}

/** A layer's fields as the cue editor's Looks tab writes them; a field left out is kept. */
export interface TimelineLayerFields {
  enabled?: boolean
  amount?: number
  blendMode?: string
  /** `PropertyMaskGroup` names joined by commas, or null for every attribute. */
  propertyMask?: string | null
  stomp?: boolean
  delayMs?: number | null
  intervalMs?: number | null
  randomWindowMs?: number | null
  speedMasterUuid?: string | null
  rateSpeedMasterUuid?: string | null
}

/** A cue's layers, effects and scenery as the desk answered them, sent back verbatim to undo. */
export interface TimelineCueSnapshot {
  cueId: number
  layers: Record<string, unknown>[]
  adHocFx: Record<string, unknown>[]
  scenery: Record<string, unknown>[]
}

/**
 * One op of `POST …/cue-stacks/{sid}/timeline/edit` (lighting7 stack-timeline plan §3.6). A batch
 * runs in one transaction, each op over the state the one before left; nothing reaches the stage.
 */
export type TimelineEditOp =
  | { op: 'layerOn'; cueId: number; source: TimelineSourceRef; target?: CueTarget | null; settingsFrom?: number | null }
  | { op: 'layerOff'; cueId: number; source: TimelineSourceRef; target?: CueTarget | null }
  | { op: 'layerSettings'; cueId: number; layerId: number; fields: TimelineLayerFields }
  | { op: 'replace'; cueIds: number[]; from: TimelineCellRef; to: TimelineCellRef; keepSettings: boolean }
  | { op: 'scenerySet'; cueId: number; elementUuid: string; state: SceneryState; transitionMs?: number | null }
  | { op: 'sceneryClear'; cueId: number; elementUuid: string }
  | ({ op: 'restore' } & TimelineCueSnapshot)

export interface TimelineEditResponse {
  /** The cues the batch changed. */
  changed: number[]
  /** Each press's knock-on: another record taken off the pressed targets in that cue (D5). */
  released: { cueId: number; source: { kind: string; id: number; uuid: string; name: string }; targets: CueTarget[] }[]
  /** Each changed cue as the batch found it — what Undo sends back as `restore` ops. */
  before: TimelineCueSnapshot[]
  /** The live cues marked edited since GO. */
  edited: number[]
}
