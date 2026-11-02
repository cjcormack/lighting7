import { useSyncExternalStore } from 'react'
import { createSyncStore, sessionStorageArea } from '../../../lib/syncStore'

/**
 * **Moves in dark · On | Off** (lighting7 move-in-dark plan D11, session 5): whether this window's
 * Stage view draws what the desk is moving in the dark — a dashed aim line from each dark head, a
 * ring where its move lands and a badge naming the cue it is for (`MoveInDarkMarker.tsx`). On by
 * default; drawn only in the Output sources, never in a `render_view` capture.
 *
 * **Per window and announced**, as the bodies are: `sessionStorage`, riding `viewOptions` as
 * `movesInDark` beside the source.
 *
 * Free of three.js for `workLights.ts`'s reason: `lib/stageViewpoint.ts` imports it on the app
 * shell's path.
 */
export const MOVES_IN_DARK = ['on', 'off'] as const
export type MovesInDark = (typeof MOVES_IN_DARK)[number]

export const DEFAULT_MOVES_IN_DARK: MovesInDark = 'on'

export const MOVES_IN_DARK_KEY = 'stage.movesInDark'

/** The key it rides under in a Stage window's `viewOptions`. */
export const VIEW_OPTION_MOVES_IN_DARK = 'movesInDark'

export const MOVES_IN_DARK_LABELS: Record<MovesInDark, string> = { on: 'On', off: 'Off' }

export function isMovesInDark(value: unknown): value is MovesInDark {
  return typeof value === 'string' && (MOVES_IN_DARK as readonly string[]).includes(value)
}

const store = createSyncStore<MovesInDark>({
  key: MOVES_IN_DARK_KEY,
  fallback: DEFAULT_MOVES_IN_DARK,
  parse: (parsed) => (isMovesInDark(parsed) ? parsed : DEFAULT_MOVES_IN_DARK),
  storage: sessionStorageArea,
})

export function useMovesInDark(): MovesInDark {
  return useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)
}

/** This window's switch, read outside React. */
export function movesInDark(): MovesInDark {
  return store.getSnapshot()
}

/** Switch this window's overlay. A value outside the vocabulary is ignored. */
export function setMovesInDark(next: MovesInDark): void {
  if (isMovesInDark(next)) store.set(next)
}

/** Test seam: the store back to on, with no listeners. */
export function resetMovesInDarkStore(): void {
  store.reset()
}
