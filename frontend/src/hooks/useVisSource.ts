import { useSyncExternalStore } from 'react'
import { createSyncStore, sessionStorageArea } from '../lib/syncStore'

/**
 * Which layer of the lighting cascade the stage views should draw.
 *
 * A `createSyncStore` singleton rather than `usePersistentState`, because two surfaces read it: the
 * Stage route's view menu and the globally-mounted Positions panel. `usePersistentState` reads
 * its key once in a `useState` initialiser and never listens for changes, so two components sharing
 * a key would drift apart the moment one of them wrote.
 *
 * **Per window since stage-view plan session 3**, in `sessionStorage`: the source rides the Stage
 * view's `viewOptions` beside its viewpoint (`lib/stageViewpoint.ts`), so a hall screen on Next GO
 * and a desk screen on Output are two facts, and another window's Screens row can set one. It was
 * one value per browser profile (`localStorage`); a window with nothing of its own yet starts from
 * whatever that value was, so a desk keeps the source it had on the first load after the change.
 */
export type VisSource = 'output' | 'outputProgrammer' | 'programmer' | 'nextGo'

export const VIS_SOURCES: readonly VisSource[] = [
  'output',
  'outputProgrammer',
  'programmer',
  'nextGo',
]

export const DEFAULT_VIS_SOURCE: VisSource = 'output'

export const VIS_SOURCE_LABELS: Record<VisSource, string> = {
  output: 'Output',
  outputProgrammer: 'Output + Programmer',
  programmer: 'Programmer only',
  nextGo: 'Next GO',
}

export const VIS_SOURCE_HINTS: Record<VisSource, string> = {
  output: 'Final merged DMX — what the desk is transmitting.',
  // Worth spelling out: outside blind the programmer is already part of the merge, so this
  // setting looks broken unless the operator knows when it bites.
  outputProgrammer: 'Output with the programmer laid over it. Same as Output unless Blind is on.',
  programmer: 'Only what the programmer holds. Everything else reads zero.',
  // "Cue values only" is the caveat an operator would otherwise read as a bug: a cue whose look
  // is carried by an effect previews as nothing. The other one — nothing is previewed at all
  // unless the show is running — is live state, so it comes from `useNextGoStatus` instead.
  nextGo: 'What the next GO would look like, over live output. Cue values only.',
}

const STORAGE_KEY = 'stage.source'
/** The profile-wide key the source lived under before it was per window; read once, as a seed. */
const LEGACY_STORAGE_KEY = 'stageVisSource'

export function isVisSource(value: unknown): value is VisSource {
  return typeof value === 'string' && (VIS_SOURCES as readonly string[]).includes(value)
}

/** The profile-wide source a desk had before it was per window, or the default. */
function legacySource(): VisSource {
  try {
    const raw = window.localStorage.getItem(LEGACY_STORAGE_KEY)
    const parsed: unknown = raw == null ? null : JSON.parse(raw)
    return isVisSource(parsed) ? parsed : DEFAULT_VIS_SOURCE
  } catch {
    return DEFAULT_VIS_SOURCE
  }
}

function makeStore() {
  return createSyncStore<VisSource>({
    key: STORAGE_KEY,
    fallback: typeof window === 'undefined' ? DEFAULT_VIS_SOURCE : legacySource(),
    // Narrowed rather than cast: a value written by a later build (or junk) must not become a
    // `VisSource` the consuming switches have no case for.
    parse: (parsed) => (isVisSource(parsed) ? parsed : DEFAULT_VIS_SOURCE),
    storage: sessionStorageArea,
  })
}

let store = makeStore()

/** The current vis source, re-rendering every reader when it changes. */
export function useVisSource(): VisSource {
  return useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)
}

/** The current vis source outside React — for a handler reading it at call time, or a test. */
export function visSource(): VisSource {
  return store.getSnapshot()
}

export function setVisSource(next: VisSource): void {
  store.set(next)
}

/**
 * Test seam: drop the cached value and any listeners so each test starts clean — and re-read the
 * legacy seed, which a test may have just written.
 */
export function resetVisSourceStore(): void {
  store.reset()
  store = makeStore()
}
