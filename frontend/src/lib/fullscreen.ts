import { useSyncExternalStore } from 'react'

/**
 * Full screen, from MDN (multi-screen plan §3.6) — the Fullscreen API, Chrome's Keyboard Lock, and
 * the one thing a page can do about the gesture rule.
 *
 * - **`requestFullscreen` needs a gesture**, everywhere. So it is called from the user menu's item,
 *   the ⌘K command and the Screens sheet's button — and never from a `windows.fullscreen {on:true}`
 *   frame or on load. Those two raise the **Return to full screen** banner instead, one tap that is
 *   the gesture. The banner is also what a reload gets: a window that was full screen last time
 *   (the `sessionStorage` flag) and is not now.
 * - **Keyboard Lock is Chrome's and a secure-context feature** (`navigator.keyboard.lock`), so it
 *   is feature-detected and its absence is quiet (D13). With it, Esc reaches the sheet's
 *   clear-selection and the dialogs' close rather than the browser; without it — Safari, or the
 *   desk opened at its `.local` name — Esc leaves full screen first. That is why the desk screens
 *   run *installed* (`docs/desk-screens.md`), where there is no Esc trap to lock against.
 * - **`fullscreenchange` is the truth**, not the request's promise: the browser leaves full screen
 *   on Esc, a tab switch or another app coming forward, and none of those tells the requester.
 *   The listener updates the store every reader subscribes to and the announce follows.
 *
 * `canFullscreen()` is the feature detection the menu item and the command hide behind: Safari on
 * iPhone has no `requestFullscreen` at all (`§3.6`), and a control that cannot work is not drawn.
 */

export const FULLSCREEN_FLAG_KEY = 'desk.fullscreen'

export interface FullscreenState {
  /** Is the document full screen now. */
  active: boolean
  /**
   * Should the *Return to full screen* banner be showing: the window was full screen and is not, or
   * another window asked this one to be and only a gesture here can do it.
   */
  wanted: boolean
}

let state: FullscreenState | null = null
const listeners = new Set<() => void>()
let tracking = false

/** Feature detection: does this browser have the Fullscreen API on the document at all. */
export function canFullscreen(): boolean {
  if (typeof document === 'undefined') return false
  return typeof document.documentElement?.requestFullscreen === 'function' && document.fullscreenEnabled !== false
}

/** Feature detection: Chrome's Keyboard Lock, secure context only. */
export function canLockKeyboard(): boolean {
  if (typeof navigator === 'undefined') return false
  const keyboard = (navigator as Navigator & { keyboard?: { lock?: unknown } }).keyboard
  return typeof keyboard?.lock === 'function'
}

export function isFullscreen(): boolean {
  if (typeof document === 'undefined') return false
  return document.fullscreenElement != null
}

/**
 * Enter full screen from a gesture, then lock Esc where the browser allows it. Resolves to
 * whether the document is full screen afterwards; a refusal (no gesture, a permission policy) is
 * reported rather than thrown, since every caller is an event handler.
 */
export async function enterFullscreen(): Promise<boolean> {
  if (!canFullscreen()) return false
  if (!isFullscreen()) {
    try {
      await document.documentElement.requestFullscreen()
    } catch {
      return false
    }
  }
  await lockEscape()
  // `fullscreenchange` fires before the promise resolves, so the store has moved by now; this is
  // for the case where the listener was never attached (a caller before any reader mounted).
  publish({ active: true, wanted: false })
  return true
}

/** Leave full screen. Needs no gesture, so a `windows.fullscreen {on:false}` can call it. */
export async function exitFullscreen(): Promise<void> {
  await unlockEscape()
  if (typeof document === 'undefined' || !isFullscreen()) return
  try {
    await document.exitFullscreen()
  } catch {
    // Already out, or the browser refused; the change listener has the truth either way.
  }
}

/**
 * Raise the *Return to full screen* banner without entering — for `windows.fullscreen {on:true}`,
 * which arrives with no gesture to spend. A no-op while already full screen.
 */
export function requestReturnToFullscreen(): void {
  const current = snapshot()
  if (current.active) return
  publish({ ...current, wanted: true })
}

/** The banner's × — this window stays as it is, and stops asking. */
export function dismissReturnToFullscreen(): void {
  const current = snapshot()
  if (!current.wanted) return
  // The flag too, or the next reload asks again: "stops asking" has to outlive the document.
  writeFlag(false)
  publish({ ...current, wanted: false })
}

export function getFullscreenState(): FullscreenState {
  return snapshot()
}

/** `useSyncExternalStore`'s subscribe; attaches the one `fullscreenchange` listener on first use. */
export function subscribeFullscreen(fn: () => void): () => void {
  ensureTracking()
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}

export function useFullscreenState(): FullscreenState {
  return useSyncExternalStore(subscribeFullscreen, snapshot, serverSnapshot)
}

/** Test seam: drop the cached state and every listener. The document listener stays attached. */
export function resetFullscreenState(): void {
  state = null
  listeners.clear()
}

const SERVER: FullscreenState = { active: false, wanted: false }
function serverSnapshot(): FullscreenState {
  return SERVER
}

/**
 * First read: the flag from the last visit says whether to ask for the way back. It is read once,
 * here, rather than on every snapshot — after that the change listener owns `active` and the two
 * request functions own `wanted`.
 */
function snapshot(): FullscreenState {
  if (state == null) {
    const active = isFullscreen()
    state = { active, wanted: !active && readFlag() }
  }
  return state
}

function publish(next: FullscreenState): void {
  const current = snapshot()
  if (current.active === next.active && current.wanted === next.wanted) return
  state = next
  for (const fn of [...listeners]) fn()
}

function ensureTracking(): void {
  if (tracking || typeof document === 'undefined') return
  tracking = true
  document.addEventListener('fullscreenchange', () => {
    const active = isFullscreen()
    writeFlag(active)
    // Entering clears the ask; leaving does not raise one — Esc was the operator's own choice, and
    // a banner that reappeared on every exit would be nagging rather than remembering.
    publish({ active, wanted: active ? false : snapshot().wanted })
  })
}

async function lockEscape(): Promise<void> {
  if (!canLockKeyboard()) return
  try {
    await (navigator as Navigator & { keyboard: { lock: (keys: string[]) => Promise<void> } }).keyboard.lock(['Escape'])
  } catch {
    // Not granted (a non-secure context, a policy): Esc leaves full screen, as on Safari.
  }
}

async function unlockEscape(): Promise<void> {
  if (!canLockKeyboard()) return
  const keyboard = (navigator as Navigator & { keyboard: { unlock?: () => void } }).keyboard
  try {
    keyboard.unlock?.()
  } catch {
    // Nothing to undo.
  }
}

function readFlag(): boolean {
  if (typeof window === 'undefined') return false
  try {
    return window.sessionStorage.getItem(FULLSCREEN_FLAG_KEY) === 'true'
  } catch {
    return false
  }
}

function writeFlag(active: boolean): void {
  if (typeof window === 'undefined') return
  try {
    window.sessionStorage.setItem(FULLSCREEN_FLAG_KEY, active ? 'true' : 'false')
  } catch {
    // Storage unavailable — the banner simply will not remember across a reload.
  }
}
