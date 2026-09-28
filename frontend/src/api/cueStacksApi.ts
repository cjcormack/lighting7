// Type definitions for cue stacks API

export type CueType = 'STANDARD' | 'MARKER'

/**
 * A row in the project's ordered stack list. `STACK` is a runnable cue stack; `SEPARATOR` is a
 * label-only divider between stacks (no cues, not activatable) — it replaces the old show-level
 * marker entries now that the show is simply the project's ordered stacks.
 */
export type StackType = 'STACK' | 'SEPARATOR'

export interface CueStackCueEntry {
  id: number
  name: string
  sortOrder: number
  /**
   * How many Look layers the cue carries. This was spelled `presetCount` here long after the
   * server renamed it, so the field the wire actually sends was invisible and the declared one was
   * always `undefined`. Still unconsumed, but it is the count a collapsed cue row wants: a cue
   * built entirely from layers otherwise reads as empty in the Run list.
   */
  layerCount: number
  adHocEffectCount: number
  autoAdvance: boolean
  autoAdvanceDelayMs: number | null
  fadeDurationMs: number | null
  fadeCurve: string
  cueNumber: string | null
  /**
   * True when `cueNumber` was derived from the cue's position rather than typed by the operator.
   * Auto numbers move with the cue and are rendered dimmed to mark them as provisional.
   */
  cueNumberAuto: boolean
  notes: string | null
  cueType: CueType
}

export interface CueStack {
  id: number
  name: string
  loop: boolean
  /** Position within the project's ordered stack list (the show order). */
  sortOrder: number
  type: StackType
  /** Display text for a `SEPARATOR`; null for a runnable `STACK`. */
  label: string | null
  cues: CueStackCueEntry[]
  activeCueId: number | null
  /**
   * The cue the next GO fires: an operator-armed standby when one is set, else the positional
   * next. Computed by the backend so every session — desk, tablet, MIDI surface — agrees on what
   * is on deck. The wire also carries the armed-only cursor (`standbyCueId` here,
   * `nextIsArmed` on `CueRunStateEvent`), but no surface renders armed differently from
   * positional, so this type deliberately mirrors only the effective next.
   */
  nextCueId: number | null
  canEdit: boolean
  canDelete: boolean
}

export interface CueStackInput {
  name: string
  loop: boolean
  /** `SEPARATOR` to create a divider row; omit/`STACK` for a runnable stack. */
  type?: StackType
  label?: string | null
  /** Explicit position; when omitted the row is appended to the end of the project's order. */
  sortOrder?: number
}

export interface ReorderCuesRequest {
  cueIds: number[]
}

/** Result of `POST /cue-stacks/{stackId}/sort-by-cue-number`. */
export interface SortByCueNumberResponse {
  updatedCues: CueStackCueEntry[]
  /** Cue numbers with nothing to order by (e.g. "intro"), left in place. */
  pinnedCount: number
  nullNumberCount: number
}

export interface ReorderCueStacksRequest {
  stackIds: number[]
}

export interface ActivateCueStackRequest {
  cueId?: number
}

export interface AdvanceCueStackRequest {
  direction: 'FORWARD' | 'BACKWARD'
}

export interface CueStackActivateResponse {
  stackId: number
  cueId: number
  cueName: string
  effectCount: number
}

export interface CueStackDeactivateResponse {
  stackId: number
  removedCount: number
}

// ─── Program transport (project-level playhead over the ordered stacks) ──────
// The "show" is just the project's ordered stacks; these types drive which stack is live.

export interface ProgramState {
  projectId: number
  /** The currently-active (live) stack, or null when the show is not running. */
  activeStackId: number | null
  canEdit: boolean
}

export interface AdvanceProgramRequest {
  direction: 'FORWARD' | 'BACKWARD'
  deactivatePrevious?: boolean
}

/**
 * Make a stack live, landing on its first cue.
 *
 * There is deliberately no `cueId`: it existed for the busk view's pinned-cue pads, and a busk pad
 * now applies a cue through the cue-stack manager without moving the playhead at all. Jumping
 * within a stack is `goToCueInStack`'s job — see the note where that endpoint was removed.
 */
export interface GoToStackRequest {
  stackId: number
}

export interface ProgramActivateResponse {
  projectId: number
  activeStackId: number | null
  activatedStackName: string | null
}

/** WebSocket `showChanged` payload — the project playhead moved (activate/deactivate/advance/go-to). */
export interface ProgramStateChangedEvent {
  projectId: number
  activeStackId: number | null
  activeStackName: string | null
}

/**
 * WebSocket `cueRunStateChanged` payload — a stack's live cue, armed next, or fade progress
 * moved. One frame per transition, from whichever surface caused it; the fade animates locally
 * from `fadeElapsedMs` + `fadeDurationMs` rather than being streamed.
 */
export interface CueRunStateEvent {
  projectId: number
  stackId: number
  activeCueId: number | null
  nextCueId: number | null
  /** True when `nextCueId` is an operator-armed standby rather than the positional next. */
  nextIsArmed: boolean
  /**
   * True when this frame *is* a GO — false for a standby change, a stack stopping, and the
   * snapshot sent on connect. Only the server can say: a snapshot of a cue that fired an hour
   * ago is indistinguishable from the cue firing now, so guessing means replaying dead fades.
   */
  transition: boolean
  fadeDurationMs: number | null
  /**
   * Null when no fade is running — which is also how a standby-only change is told apart from a
   * cue transition, so an armed cue doesn't restart someone else's fade. Otherwise how far into
   * the fade the desk was when the frame was sent: a session joining mid-fade starts there.
   */
  fadeElapsedMs: number | null
  autoAdvance: boolean
  autoAdvanceDelayMs: number | null
}

/** Response of `POST /cue-stacks/{stackId}/standby`. */
export interface CueStackRunState {
  stackId: number
  activeCueId: number | null
  standbyCueId: number | null
  nextCueId: number | null
}

/** One channel of a previewed look — `POST /cue-stacks/{stackId}/preview`. */
export interface PreviewChannel {
  universe: number
  channel: number
  value: number
}

/**
 * What a cue *would* look like, composed by the backend's own resolver.
 *
 * Layer 4 only: cue-band effects and timed presets aren't previewed, and channels no cue
 * asserts are absent rather than 0 — fall back to the live output for those. See
 * `lighting7/docs/cue-stacks-engineering.md` §"Preview compose" — the **backend** repo. This one
 * has a `docs/` of its own, so the unqualified path reads as a local file that does not exist.
 */
export interface PreviewCueResponse {
  cueId: number
  channels: PreviewChannel[]
  /** `fixtureKey.property` rows that couldn't be resolved to channels. Advisory. */
  skipped: string[]
}
