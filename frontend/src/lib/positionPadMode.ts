import { useSyncExternalStore } from 'react'
import { createSyncStore } from './syncStore'

/**
 * **What a position pad's drag means: pan and tilt, or a move across the stage.**
 *
 * *Pan/Tilt* is the pad as it always was, a point on it a pan and a tilt. *Stage* makes it a jog:
 * a drag towards SR, SL, upstage or downstage moves every head's landing point that far across the
 * stage, each head solved for its own pan and tilt on the desk (`POST /programmer/aim`, `mode: JOG`).
 *
 * One fact for every position pad — the grid's, the fixture sheet's, the busk Position tab's — kept
 * per viewer in `localStorage`: it is how this operator likes to drive a mover, not how a window is
 * arranged, so a second screen of the same desk starts in the same mode.
 */
export type PositionPadMode = 'PAN_TILT' | 'STAGE'

const store = createSyncStore<PositionPadMode>({
  key: 'desk.positionPad.mode',
  fallback: 'PAN_TILT',
  parse: (parsed) => (parsed === 'STAGE' ? 'STAGE' : 'PAN_TILT'),
})

export function usePositionPadMode(): PositionPadMode {
  return useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)
}

export function getPositionPadMode(): PositionPadMode {
  return store.getSnapshot()
}

export function setPositionPadMode(mode: PositionPadMode): void {
  store.set(mode)
}

/** Test seam — a module-level cache outlives `localStorage.clear()`. */
export function resetPositionPadModeStore(): void {
  store.reset()
}
