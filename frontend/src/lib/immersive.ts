import { useSyncExternalStore } from 'react'
import { createSyncStore, sessionStorageArea } from './syncStore'
import { launchImmersive } from './windowIdentity'

/**
 * **Immersive** — this window drawn without the app around it (busk-chrome plan D7–D10;
 * `Immersive.dc.html` is the layout authority).
 *
 * One per-window fact, `desk.immersive`, `off` | `on`, default `off` on every surface — nothing
 * about a screen's size says the app should vanish. While it is on and the window is on one of
 * the four live views (`lib/liveViews.ts`'s predicate), `Layout` skips the sidebar (hides, not
 * collapses), the app header and the four overview panels; on every other route the app is drawn
 * whatever this says, because the fixtures list is navigated *from* the sidebar. The `ShowHeader`
 * stays and carries the way back (D11): the same glyph, inverted.
 *
 * Three rules, each with a reason:
 *
 * - **Per tab, in `sessionStorage`, never `localStorage` and never the desk's** — `lib/deskFollow.ts`'s
 *   reason: the two desk screens are two windows of one profile, and the flow this exists for is a
 *   busk window with no app around it beside a programmer window with the app drawn. A
 *   `createSyncStore` singleton rather than a `useState`, because `Layout`, the header glyph, ⌘K
 *   and the announce all read it and must move together.
 * - **It is not full screen, and the two compose** (D8). A windowed browser can be immersive and a
 *   full-screen one can show the app. Nothing here touches `lib/fullscreen.ts` and nothing there
 *   touches this.
 * - **It rides `viewOptions` under every live view; nothing new on the wire** (D9). The desk's
 *   Json is bare, so a top-level announce key would drop the frame. `?immersive=on` is consumed
 *   once at boot beside `?window=` — the same memoised read, `lib/windowIdentity.ts`'s
 *   `consumeLaunchParam` — and stripped; it is **not** mirrored back into the address the way the
 *   busk view mirrors `?focus=`, because it is a window's fact and not a view's.
 */

export const IMMERSIVE_KEY = 'desk.immersive'
/** The `viewOptions` key the announce carries and `windows.viewOptions` sets. */
export const VIEW_OPTION_IMMERSIVE = 'immersive'

export type Immersive = 'off' | 'on'
export const IMMERSIVE_VALUES: readonly Immersive[] = ['off', 'on']

export function isImmersiveValue(value: unknown): value is Immersive {
  return value === 'off' || value === 'on'
}

const store = createSyncStore<Immersive>({
  key: IMMERSIVE_KEY,
  fallback: 'off',
  parse: (parsed) => (isImmersiveValue(parsed) ? parsed : 'off'),
  storage: sessionStorageArea,
})

/** Is this window immersive? Re-renders every reader when it flips. */
export function useImmersive(): boolean {
  return useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot) === 'on'
}

/** For a reader that only needs the answer at press time. */
export function isImmersive(): boolean {
  return store.getSnapshot() === 'on'
}

/** The fact as the announce spells it. */
export function immersiveValue(): Immersive {
  return store.getSnapshot()
}

export function setImmersive(on: boolean): void {
  store.set(on ? 'on' : 'off')
}

export function toggleImmersive(): void {
  setImmersive(!isImmersive())
}

/**
 * The boot read: `?immersive=on` (or `off`) on the launch URL sets the fact for this tab, once.
 * Called from `main.tsx` beside `windowName()`, before the router is created, so the first paint
 * of a live view is already the shape the link asked for. The parameter is stripped by the read,
 * so a reload finds none and keeps whatever the tab holds — the "a reload keeps the fact" half.
 * Any other value is ignored rather than read as `off`: a mistyped link should not undo a shape
 * the window already had.
 */
export function applyImmersiveLaunch(): void {
  const raw = launchImmersive()
  if (isImmersiveValue(raw)) store.set(raw)
}

/**
 * A `windows.viewOptions` frame's `immersive`, applied to this tab (D9). Any other key in the
 * frame is not this module's; a value outside the vocabulary is ignored. Returns what changed.
 */
export function applyImmersiveViewOption(options: Readonly<Record<string, string>>): Immersive | undefined {
  const value = options[VIEW_OPTION_IMMERSIVE]
  if (!isImmersiveValue(value)) return undefined
  store.set(value)
  return value
}

/** Test seam: the store back to `off` with no listeners. */
export function resetImmersiveStore(): void {
  store.reset()
}
