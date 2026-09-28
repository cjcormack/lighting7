import { useSyncExternalStore } from 'react'
import { createSyncStore } from './syncStore'

/**
 * The programmer's fade time, in ms, held as the string the picker shows.
 *
 * Shared because more than one surface acts on it: the programmer's action bar owns the picker and
 * subscribes for Clear and Blind, and the marquee's Backspace (`useCellWriters.clearValue`) reads it
 * at press time. It became a store when Blind moved into the `ShowBar` in session 2b and the value
 * had to be addressable rather than local; Blind is back beside the picker now
 * (`PD-BLIND-ON-PROGRAMMER`), but the marquee still reads from here and the store stays.
 *
 * A `createSyncStore` singleton rather than `usePersistentState`, because the readers are mounted at
 * once on `/programmer`: as two instances of one key they held two mount-time snapshots and drifted
 * apart the moment the picker wrote, so Blind snapped for the rest of the visit — which made the
 * sharing above false in practice.
 */
export const PROGRAMMER_FADE_KEY = 'programmer.fadeMs'

/** No fade — snap. The picker's first option, and what junk in storage reads back as. */
export const DEFAULT_PROGRAMMER_FADE = '0'

const store = createSyncStore<string>({
  key: PROGRAMMER_FADE_KEY,
  fallback: DEFAULT_PROGRAMMER_FADE,
  // Stored JSON-encoded, because `usePersistentState<string>` wrote it that way and existing
  // installs still hold those values. A non-string would reach the picker's `Select` as a value it
  // has no option for.
  parse: (parsed) => (typeof parsed === 'string' ? parsed : DEFAULT_PROGRAMMER_FADE),
})

/** The picker's current value, re-rendering every reader when it changes. */
export function useProgrammerFade(): string {
  return useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)
}

export const setProgrammerFade = store.set

/**
 * The fade in ms, for a surface that only needs it when the operator presses something.
 *
 * Read at press time rather than subscribed, so a surface far from the picker (the marquee's
 * Backspace) picks up a fade chosen a moment ago without re-rendering every time the picker moves.
 * `|| 0` keeps answering 0 for junk.
 */
export function getProgrammerFadeMs(): number {
  return Number(store.getSnapshot()) || 0
}

/** Test seam: drop the cached value and any listeners so each test starts clean. */
export const resetProgrammerFadeStore = store.reset
