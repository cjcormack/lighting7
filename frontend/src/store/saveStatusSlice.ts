import { createSlice, isFulfilled, isPending, isRejected } from '@reduxjs/toolkit'
import type { AnyAction } from '@reduxjs/toolkit'

/**
 * Endpoints that are **not** saves.
 *
 * Every mutation goes through the same pipeline, so the save indicator has to be told which ones
 * actually persist an edit. The cut is "did the operator change the show, or drive it?" — a GO, a
 * fader move, an FX toggle and a compile all mutate *live* state that is gone at the next
 * restart, and flashing "Saved" on every GO during a performance would be both wrong and
 * maddening.
 *
 * This is a deny-list, mirroring `SILENT_ENDPOINTS` in [errorToastMiddleware]: a new endpoint
 * counts as a save unless it is listed here, so a genuinely new *save* can never be silently
 * missed. The trade is that a new *transport* command has to be added — `saveStatusSlice.test.ts`
 * asserts every name here still exists, which catches renames but not omissions.
 */
export const NON_SAVE_ENDPOINTS: ReadonlySet<string> = new Set([
  // ── Transport: running the show, not editing it ──
  'activateCueStack',
  'deactivateCueStack',
  'advanceCueStack',
  'setCueStackStandby',
  'goToStack',
  'activateProgram',
  'deactivateProgram',
  'advanceProgram',
  'applyCue',
  'stopCue',
  // The programmer's authoring loop. Each reports itself in its own sheet/dialog, and
  // Record/Update also report counts and skips there — a save-status flash would say less.
  'recordProgrammer',
  'recordLook',
  'includeIntoProgrammer',
  'updateProgrammer',
  // Make Hard stood here, at programmer level, because it wrote only live programmer state and
  // a "Saved" flash would have been a lie. Both it and the cue-level one are gone now, retired
  // with the value-level references they resolved.

  // ── Live output: DMX and effects state, none of it persisted ──
  'updateChannel',
  'parkChannel',
  'unparkChannel',
  'addFixtureFx',
  'updateFx',
  'removeFx',
  'pauseFx',
  'resumeFx',
  'applyGroupFx',
  'updateGroupFx',
  'removeGroupFx',
  'pauseGroupFx',
  'resumeGroupFx',
  // `previewLook` / `clearLookPreview` stood here. They drove the Look editor's rig preview,
  // which went with the editor in session 3; the routes themselves were deleted server-side
  // afterwards, so there is nothing left to classify.
  'toggleLook',
  // The template gestures, for the same reason `toggleLook` is here: they land on the rig and the
  // strip reports what happened itself, so a save-status spinner would be describing a press.
  'applyTemplate',
  'toggleTemplate',
  // A busk pad's press, for the same reason: whatever it holds, it drives the rig rather than
  // saving anything. The layout write behind the *page* is a save and stays out of this set, which
  // is what lets the page strip's "Saved" fall out of the show-wide counters with no state of its own.
  'pressBuskPad',
  'resolveTemplate',

  // ── Compile / run / chat: long-running, and each already reports its own outcome ──
  'compileProjectScript',
  'runProjectScript',
  'aiChat',

  // ── Session and connection state, not project data ──
  'setCurrentProject',
  'reconnect',
  'resetMidiLatency',

  // ── Cloud sync: drives its own progress UI, and the pollers would strobe the pill ──
  'cloudSyncRun',
  'cloudSyncSnapshot',
  'cloudSyncImport',
  'cloudSyncResolve',
  'cloudSyncApply',
  'cloudSyncAbort',
  'cloudSyncReconnect',
  'cloudSyncDisconnect',
  'startGithubDeviceFlow',
  'pollGithubDeviceFlow',
])

export interface CueSaveStatus {
  /** Saves currently in flight. */
  pending: number
  /**
   * Bumped once per successful save. A counter rather than a timestamp so the reducer stays
   * pure — the indicator starts its own "Saved" timer off the change.
   */
  savedTick: number
}

export interface SaveStatusState extends CueSaveStatus {
  /**
   * The same two counters per cue, so a cue card can report its own saves instead of every
   * card lighting up whenever anything in the show is written. Keyed by cue id; only cues
   * actually saved this session get an entry, and they are kept (not pruned on idle) because
   * dropping one would reset its `savedTick` and swallow the "Saved" that follows.
   */
  byCue: Record<number, CueSaveStatus>
}

const initialState: SaveStatusState = { pending: 0, savedTick: 0, byCue: {} }

/** Shape of the `meta` RTK Query attaches to an endpoint lifecycle action. */
interface EndpointMeta {
  arg?: { type?: string; endpointName?: string; originalArgs?: unknown }
  condition?: boolean
}

function savingEndpoint(action: AnyAction): boolean {
  const meta = (action as { meta?: EndpointMeta }).meta
  const endpointName = meta?.arg?.endpointName
  if (meta?.arg?.type !== 'mutation' || endpointName === undefined) return false
  return !NON_SAVE_ENDPOINTS.has(endpointName)
}

/**
 * The cue a save belongs to, read off the arguments the trigger was called with.
 *
 * Every cue-scoped endpoint takes `{ projectId, cueId, ... }`, so this needs no per-endpoint
 * knowledge; a mutation that names no cue (creating one, reordering a stack) simply reports to
 * the show-wide counters only.
 */
function cueIdOf(action: AnyAction): number | undefined {
  const args = (action as { meta?: EndpointMeta }).meta?.arg?.originalArgs
  if (args === null || typeof args !== 'object') return undefined
  const id = (args as { cueId?: unknown }).cueId
  return typeof id === 'number' ? id : undefined
}

/** The cue's counters, created on first use. */
function cueEntry(state: SaveStatusState, cueId: number): CueSaveStatus {
  return (state.byCue[cueId] ??= { pending: 0, savedTick: 0 })
}

/**
 * Tracks whether anything is being saved, so the show header can say so.
 *
 * Implemented as matchers on RTK Query's own lifecycle actions rather than as middleware: a
 * mutation's `pending`/`fulfilled`/`rejected` actions already carry everything needed, so there
 * is nothing to dispatch and no ordering to get wrong.
 */
export function selectSaveStatus(state: { saveStatus: SaveStatusState }): SaveStatusState {
  return state.saveStatus
}

/** Stable empty result, so a cue with no saves yet doesn't hand `useSelector` a new object. */
const NO_CUE_SAVES: CueSaveStatus = { pending: 0, savedTick: 0 }

export function selectCueSaveStatus(
  state: { saveStatus: SaveStatusState },
  cueId: number,
): CueSaveStatus {
  return state.saveStatus.byCue[cueId] ?? NO_CUE_SAVES
}

export const saveStatusSlice = createSlice({
  name: 'saveStatus',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addMatcher(
        (action) => isPending(action) && savingEndpoint(action),
        (state, action) => {
          state.pending += 1
          const cueId = cueIdOf(action)
          if (cueId !== undefined) cueEntry(state, cueId).pending += 1
        },
      )
      .addMatcher(
        (action) => isFulfilled(action) && savingEndpoint(action),
        (state, action) => {
          // Clamped because a request RTK skips (`condition`) rejects without ever having
          // pended; the guard below covers that, and this covers anything else that slips past.
          state.pending = Math.max(0, state.pending - 1)
          state.savedTick += 1
          const cueId = cueIdOf(action)
          if (cueId !== undefined) {
            const cue = cueEntry(state, cueId)
            cue.pending = Math.max(0, cue.pending - 1)
            cue.savedTick += 1
          }
        },
      )
      .addMatcher(
        (action) =>
          isRejected(action) &&
          // A skipped or deduped request never dispatched a `pending`, so there is nothing to
          // balance — decrementing here would strand the counter below zero and, once clamped,
          // make a later real save look like it had already finished.
          !(action as { meta?: EndpointMeta }).meta?.condition &&
          savingEndpoint(action),
        (state, action) => {
          // No `savedTick`: the failure is already reported by `errorToastMiddleware`, and
          // claiming "Saved" here would contradict the toast next to it.
          state.pending = Math.max(0, state.pending - 1)
          const cueId = cueIdOf(action)
          if (cueId !== undefined) {
            const cue = cueEntry(state, cueId)
            cue.pending = Math.max(0, cue.pending - 1)
          }
        },
      )
  },
})
