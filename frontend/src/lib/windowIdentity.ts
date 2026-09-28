import { useSyncExternalStore } from 'react'

/**
 * This tab's **identity**: a client-minted `windowId` and a `name` (multi-screen plan D9, D10).
 *
 * A window is a socket carrying a client-minted identity. The desk's windows registry
 * (`api/windowsApi.ts`) keys its rows by *socket* — the row's `id` is minted server-side and comes
 * back on `windows.state` — and this tab announces the two facts held here on every connect and
 * every change, so the registry can recognise the same tab across a reload and a reconnect.
 *
 * Both live in **`sessionStorage`**, never `localStorage`: the two desk screens are two windows of
 * one browser profile, and `localStorage` is one value per origin per profile — an identity kept
 * there would be one identity for both screens (D8's reason, applied to D9). `sessionStorage` is
 * per tab and survives a reload of that tab, which is exactly the lifetime a window identity wants.
 *
 * - **`windowId`** is a uuid minted into storage, and **`?window=` at boot mints a fresh one**
 *   rather than keeping whatever storage held. That is the whole of session 2.5. `sessionStorage`
 *   is *cloned* into a top-level context created from an existing one — a `window.open` without
 *   `noopener`, a `target=_blank` link — so the second desk screen can wake up holding the first's
 *   id, and since a window is never told its own row (`windows.state` keys by socket) and infers
 *   it by matching `windowId`, both screens would match both rows and the desk chip would read
 *   *Desk* — "I moved it" — when the twin moved it. The parameter means "a deliberately-named new
 *   window", which is exactly the signal that this context is **not** a continuation of the
 *   storage it woke up with.
 *
 *   The invariant on the other side, and the regression to watch for: the parameter is **stripped
 *   at boot**, so a reload carries none and **keeps its id**. Minting there would churn a registry
 *   row on every refresh. Presence of the *key* is the signal, blank value included — a blank
 *   names nothing, so the name falls back, but the fail-safe direction for an identity is a fresh
 *   one rather than a shared one.
 *
 *   The name deliberately does **not** follow: a stored name still beats the parameter (see below),
 *   so a `?window=` boot over cloned storage is a fresh id under the inherited name. Two rows
 *   sharing a *name* is what D9 already says happens to a duplicated tab and is only cosmetic;
 *   two rows sharing an *id* is the misattribution above. What is still not fixed here is
 *   right-click **Duplicate Tab**, which clones storage on a URL whose parameter was already
 *   stripped: two rows, one id, and the client cannot tell them apart (D9 accepts that;
 *   `FU-WINDOWS-OWN-ROW-ID` in lighting7 is the exact fix).
 * - **`name`** comes, in order, from `sessionStorage`; from **`?window=` on the launch URL**, read
 *   once at boot before the router is created (`main.tsx`) and stripped, which is what lets a
 *   shortcut, a Dock app, a home-screen icon or a copied link name its window durably without a
 *   per-browser store; else *Window* plus a short suffix for a tab opened by hand. It can be renamed
 *   for the life of the tab — from this window or, through `windows.rename`, from any other.
 *
 * The name is a subscribable, so the desk chip, the user menu and the announce all move on a
 * rename; the id is static for the tab, so it is a plain read.
 *
 * **Both lazy reads go through `consumeLaunchParam`**, which reads and strips `?window=` once per
 * boot and remembers *whether it was there* as well as what it said. `main.tsx` calls only
 * `windowName()`, so the "was the parameter present" fact cannot live in `windowId()`'s own path —
 * by the time anything asks for the id the URL has long been rewritten. Memoising it in one place
 * is what makes the two functions order-independent: whichever is called first consumes the
 * parameter, and the other reads the same answer.
 *
 * **`?immersive=` rides the same read** (busk-chrome plan D9). It is a launch parameter of the
 * same kind — a statement about *this* window, made once by whoever minted the link — so it is
 * consumed and stripped in the one `replaceState` with `?window=`, and `lib/immersive.ts` asks
 * [launchImmersive] for what it said. One read rather than two because the URL is rewritten by
 * whichever read runs first: a second module doing its own `searchParams.get` after this one had
 * already replaced the URL would find nothing, and in which order the two ran would depend on
 * import order. It is not part of the identity and is not remembered past the boot read.
 */

export const WINDOW_ID_KEY = 'desk.windowId'
export const WINDOW_NAME_KEY = 'desk.windowName'
export const WINDOW_NAME_PARAM = 'window'
/** `?immersive=on` — consumed here beside `?window=`, applied by `lib/immersive.ts`. */
export const IMMERSIVE_PARAM = 'immersive'

/**
 * Every boot-time value this module memoises, in **one object reset by a single reassignment** —
 * so a fourth cannot be added without appearing in [resetWindowIdentity], which is how a stale
 * value leaks from one test into the next.
 */
interface IdentityCache {
  /** The minted or stored `windowId`. */
  id: string | null
  /** The stored, launched or minted name. */
  name: string | null
  /** `?window=` as this boot found it, consumed once — see [consumeLaunchParam]. */
  launch: LaunchParam | null
}

const EMPTY_CACHE: IdentityCache = { id: null, name: null, launch: null }

let cache: IdentityCache = { ...EMPTY_CACHE }
const nameListeners = new Set<() => void>()

/** What `?window=` said at boot, and — separately — whether it was there at all. */
interface LaunchParam {
  /** The parameter key was on the launch URL, blank value included. */
  present: boolean
  /** Its trimmed value, or null for a blank one. */
  name: string | null
  /** `?immersive=`'s raw value, or null when absent. Read and stripped in the same pass. */
  immersive: string | null
}

const NO_LAUNCH: LaunchParam = { present: false, name: null, immersive: null }

/**
 * The tab's client-minted identity: a fresh uuid when this boot carried `?window=`, else the
 * stored one, else a fresh one. Stable for the life of the tab once minted.
 */
export function windowId(): string {
  if (cache.id != null) return cache.id
  const stored = consumeLaunchParam().present ? null : readStored(WINDOW_ID_KEY)
  cache.id = stored ?? mintUuid()
  writeStored(WINDOW_ID_KEY, cache.id)
  return cache.id
}

/** The tab's name, minted on first call and stable until [renameWindow]. */
export function windowName(): string {
  if (cache.name != null) return cache.name
  // The launch parameter is consumed whether or not it wins: a tab that already has a name and is
  // sent to a `?window=` URL again (the desktop shortcut clicked with the tab open) keeps its name
  // and still loses the parameter, or the URL would carry it for the life of the tab. That tab
  // does take a fresh `windowId` — see the module comment for why the two answer differently.
  const launch = consumeLaunchParam()
  cache.name = readStored(WINDOW_NAME_KEY) ?? launch.name ?? `Window ${shortSuffix()}`
  writeStored(WINDOW_NAME_KEY, cache.name)
  return cache.name
}

/**
 * Rename this tab. A blank name is refused rather than stored — the registry would show an
 * unlabelled row and the chip an empty `from`. Returns whether anything changed, so a caller can
 * skip a re-announce for a no-op.
 */
export function renameWindow(next: string): boolean {
  const name = next.trim()
  if (name === '' || name === windowName()) return false
  cache.name = name
  writeStored(WINDOW_NAME_KEY, name)
  for (const fn of [...nameListeners]) fn()
  return true
}

/** `useSyncExternalStore`'s subscribe for the name. */
export function subscribeWindowName(fn: () => void): () => void {
  nameListeners.add(fn)
  return () => {
    nameListeners.delete(fn)
  }
}

/** A React reader of the name, re-rendering on a rename. */
export function useWindowName(): string {
  return useSyncExternalStore(subscribeWindowName, windowName, windowName)
}

/**
 * What `?immersive=` said on the launch URL, raw and untrimmed-of-meaning: `lib/immersive.ts`
 * decides what counts as a value. Consuming it here strips it with `?window=`, so a reload — of
 * the rewritten URL — answers null, which is what makes the boot read a one-time arrival rather
 * than a fact the address restates on every refresh.
 */
export function launchImmersive(): string | null {
  return consumeLaunchParam().immersive
}

/**
 * Test seam: forget every cached value — the id, the name and the consumed launch parameter — so
 * each test starts from storage and the URL, which is also how a reload is simulated.
 */
export function resetWindowIdentity(): void {
  cache = { ...EMPTY_CACHE }
  nameListeners.clear()
}

/**
 * `?window=`, read and stripped **once per boot**, by whichever of [windowId] and [windowName]
 * asks first. Both the value and its mere presence are remembered: the id needs the second and the
 * URL is rewritten by the first read, so nothing can ask again.
 */
function consumeLaunchParam(): LaunchParam {
  cache.launch ??= readLaunchParam()
  return cache.launch
}

function readStored(key: string): string | null {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.sessionStorage.getItem(key)
    return raw != null && raw.trim() !== '' ? raw : null
  } catch {
    return null
  }
}

function writeStored(key: string, value: string): void {
  if (typeof window === 'undefined') return
  try {
    window.sessionStorage.setItem(key, value)
  } catch {
    // Storage unavailable — the in-memory value still serves this tab.
  }
}

/**
 * `?window=Screen%202` and `?immersive=on`, consumed: read, then stripped from the URL with one
 * `replaceState`. Either may be present without the other — a plain `/busk?immersive=on` is a
 * legal arrival and is not a `?window=` boot, so it mints no fresh id.
 */
function readLaunchParam(): LaunchParam {
  if (typeof window === 'undefined') return NO_LAUNCH
  try {
    const url = new URL(window.location.href)
    const raw = url.searchParams.get(WINDOW_NAME_PARAM)
    const immersive = url.searchParams.get(IMMERSIVE_PARAM)
    if (raw == null && immersive == null) return NO_LAUNCH
    url.searchParams.delete(WINDOW_NAME_PARAM)
    url.searchParams.delete(IMMERSIVE_PARAM)
    window.history.replaceState(window.history.state, '', url)
    const name = raw?.trim() ?? ''
    return { present: raw != null, name: name === '' ? null : name, immersive }
  } catch {
    // An unreadable URL is not a launch: keep the stored id rather than churning a registry row.
    return NO_LAUNCH
  }
}

function mintUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // A non-secure context has no `randomUUID`, and the desk over the LAN is one (D13): the same
  // 122 random bits, spelled by hand.
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function shortSuffix(): string {
  const bytes = new Uint8Array(3)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, (b) => b.toString(36).padStart(2, '0')).join('').slice(0, 4)
}
