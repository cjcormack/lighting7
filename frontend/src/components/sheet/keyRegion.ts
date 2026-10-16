import { useEffect, useSyncExternalStore } from 'react'

/**
 * Which **key region** the keyboard belongs to (show-cue-editing plan §4.5).
 *
 * Two grids can share a page — two open cue cards, or the cue sheet and its bottom pane — and each
 * has a window-level keyboard listener. A region is an element marked `data-key-region` (the cue
 * editor's root, the pane's wrapper); the **active** region is the one that holds focus or took the
 * last pointer press, and null means the page's own grid has the keys. A region's grid claims a key
 * only while it is the active one, and every other sheet's listener yields while any region is.
 *
 * One module-level fact for the whole window, fed by two capture-phase listeners on the window —
 * `pointerdown` and `focusin` — attached while anything that asks is mounted (refcounted, so the
 * programmer's page, which mounts no region and asks nothing new, pays nothing).
 *
 * **A press or a focus inside an overlay leaves the region where it was.** A cell editor, a Select's
 * list, a menu and a toast are portalled to `body`, outside every region in the DOM, and a press on
 * a slider in the cue grid's own editor must not hand the keys back to the cue sheet behind it. An
 * overlay that *contains* a region — the right-hand sheet the cue editor opens in from outside the
 * Show view — is not one of these: the region inside it is found first.
 */

const REGION_SELECTOR = '[data-key-region]'
const OVERLAY_SELECTOR = [
  '[data-radix-popper-content-wrapper]',
  '[data-cell-editor-surface]',
  '[role="dialog"]',
  '[role="alertdialog"]',
  '[role="menu"]',
  '[role="listbox"]',
  '[data-sonner-toaster]',
].join(', ')

let active: Element | null = null
const listeners = new Set<() => void>()
let installed = 0

function emit(): void {
  for (const listener of listeners) listener()
}

function setActive(next: Element | null): void {
  if (next === active) return
  active = next
  emit()
}

/** What an event at [target] says about the active region: a region, none, or "leave it". */
function regionOf(target: EventTarget | null): Element | null | 'keep' {
  if (!(target instanceof Element)) return 'keep'
  const region = target.closest(REGION_SELECTOR)
  if (region != null) return region
  if (target.closest(OVERLAY_SELECTOR) != null) return 'keep'
  return null
}

function onEvent(e: Event): void {
  const region = regionOf(e.target)
  if (region === 'keep') return
  setActive(region)
}

function install(): () => void {
  if (installed === 0 && typeof window !== 'undefined') {
    window.addEventListener('pointerdown', onEvent, true)
    window.addEventListener('focusin', onEvent, true)
  }
  installed += 1
  return () => {
    installed -= 1
    if (installed === 0 && typeof window !== 'undefined') {
      window.removeEventListener('pointerdown', onEvent, true)
      window.removeEventListener('focusin', onEvent, true)
      active = null
    }
  }
}

/** The active region, or null where the page's own grid has the keys. A region gone from the DOM is none. */
export function activeKeyRegion(): Element | null {
  if (active != null && !active.isConnected) active = null
  return active
}

/** Keep the two listeners attached while the caller is mounted. */
export function useKeyRegionTracking(): void {
  useEffect(() => install(), [])
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  const uninstall = install()
  return () => {
    listeners.delete(listener)
    uninstall()
  }
}

/** The active region, as a subscription — for a grid that dims its selection while another holds the keys. */
export function useActiveKeyRegion(): Element | null {
  return useSyncExternalStore(subscribe, activeKeyRegion, () => null)
}

/** For a test: forget the active region. */
export function resetKeyRegion(): void {
  active = null
}
