// Type definitions for cue stacks API
import type { PreviewScenery, SceneryChange } from './sceneryApi'

export type CueType = 'STANDARD' | 'MARKER'

/**
 * A row in the project's ordered stack list. `STACK` is a runnable cue stack; `SEPARATOR` is a
 * label-only divider between stacks (no cues, not activatable) — it replaces the old show-level
 * marker entries now that the show is simply the project's ordered stacks.
 */
export type StackType = 'STACK' | 'SEPARATOR'

/**
 * One row of a stack's list — an **entry** (lighting7 cue model plan §3.1). A cue entry places a
 * cue: `id` is the cue's, and the entry carries the cue's content summary beside its own place in
 * the stack (position, number, follow). A marker entry is a labelled divider and not a cue (D12):
 * `id` is null, `cueType` is `MARKER` and `name` is its label. Key a row on `entryId`, never on
 * `id` — a marker has none, and the two id spaces overlap.
 */
interface CueStackEntryBase {
  /** The entry's own id: what reorder, standby and go-to name. */
  entryId: number
  name: string
  /** The entry's position in its stack's list, from 0, markers counted. */
  sortOrder: number
  /**
   * How many Look layers the cue carries. This was spelled `presetCount` here long after the
   * server renamed it, so the field the wire actually sends was invisible and the declared one was
   * always `undefined`. Still unconsumed, but it is the count a collapsed cue row wants: a cue
   * built entirely from layers otherwise reads as empty in the Run list.
   */
  layerCount: number
  adHocFxCount: number
  autoAdvance: boolean
  autoAdvanceDelayMs: number | null
  fadeDurationMs: number | null
  fadeCurve: string
  cueNumber: string | null
  /**
   * True when `cueNumber` was derived from the entry's position rather than typed by the operator.
   * Auto numbers move with the entry and are rendered dimmed to mark them as provisional.
   */
  cueNumberAuto: boolean
  notes: string | null
  /**
   * The cue's own scenery changes — what it moves on GO, each on its clock (scenery-programmer plan
   * D13, D14): the cue table's Scenery column, the Prompt Book's margin glyph and its rail cards.
   * Never what the cue only tracks (`CueDetails.trackedScenery`). Optional: an older desk does not
   * send it, and a marker's is always empty.
   */
  scenery?: SceneryChange[]
}

/** A cue entry: the row places cue `id`. */
export interface CueStackCueRow extends CueStackEntryBase {
  id: number
  cueType: 'STANDARD'
}

/** A marker entry: a labelled divider, no cue. */
export interface CueStackMarkerRow extends CueStackEntryBase {
  id: null
  cueType: 'MARKER'
}

export type CueStackCueEntry = CueStackCueRow | CueStackMarkerRow

/** Whether a row of a stack's list places a cue (rather than being a marker). */
export const isCueRow = (entry: CueStackCueEntry): entry is CueStackCueRow => entry.cueType !== 'MARKER'

/** The row of [cues] that places cue [cueId] — never a marker, whose `id` is null. */
export function cueRowById(cues: readonly CueStackCueEntry[] | undefined, cueId: number | null | undefined): CueStackCueRow | null {
  if (cueId == null || cues == null) return null
  for (const c of cues) if (isCueRow(c) && c.id === cueId) return c
  return null
}

/**
 * The entry of [stack] that places [cueId] — [preferred] when it is one of them, else the first. The
 * runner's cursors are cue ids; this names which place of a shared cue they mean.
 */
export function entryIdOfCue(
  stack: CueStack | null | undefined,
  cueId: number | null | undefined,
  preferred: number | null | undefined,
): number | null {
  if (stack == null || cueId == null) return null
  if (preferred != null && stack.cues.some((c) => c.entryId === preferred && c.id === cueId)) return preferred
  return cueRowById(stack.cues, cueId)?.entryId ?? null
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
  /**
   * The entries behind `activeCueId` and `nextCueId`, and the armed one (lighting7 cue model plan
   * P2): the entry is the playback key, and a cue twice in one stack is told apart only by these.
   */
  activeEntryId: number | null
  nextEntryId: number | null
  standbyEntryId: number | null
  canEdit: boolean
  canDelete: boolean
  /** The stack's *set* (stage-view plan session 8): held while it is live, under its cues. Omitted when empty. */
  scenery?: SceneryChange[]
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

/** The stack's entries in their new order — markers included. */
export interface ReorderCuesRequest {
  entryIds: number[]
}

/** A marker entry's label, and where a new one goes (a position in the list; absent appends). */
export interface MarkerEntryRequest {
  label: string
  sortOrder?: number
}

/**
 * `PATCH cue-stacks/{sid}/entries/{entryId}` — an entry's number and follow. Only the fields present
 * are written; a null or empty `cueNumber` hands the entry back to auto-numbering.
 */
export interface CueEntryPatch {
  cueNumber?: string | null
  autoAdvance?: boolean
  autoAdvanceDelayMs?: number | null
}

/** `DELETE cue-stacks/{sid}`: the cues it left in no stack, or — with `deleteLooseCues` — deleted. */
export interface DeleteCueStackResponse {
  looseCueIds: number[]
  deletedCueIds: number[]
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

/** The entry to start at (cue model plan P2); absent starts at the armed standby, else the first cue. */
export interface ActivateCueStackRequest {
  entryId?: number
}

export interface AdvanceCueStackRequest {
  direction: 'FORWARD' | 'BACKWARD'
}

export interface CueStackActivateResponse {
  stackId: number
  entryId: number
  cueId: number
  cueName: string
  fxCount: number
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
  /** The entries behind `activeCueId` and `nextCueId`, and the armed one (cue model plan P2). */
  activeEntryId: number | null
  nextEntryId: number | null
  standbyEntryId: number | null
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
  activeEntryId: number | null
  standbyEntryId: number | null
  nextEntryId: number | null
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
  /**
   * The scenery the GO would land, whole (an element absent shows its base), each with the move from
   * where it is now and how long the GO would take over it. Omitted when no change names anything.
   */
  scenery?: PreviewScenery[]
}
