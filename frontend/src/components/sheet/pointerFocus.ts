import { useEffect } from 'react'

/**
 * Whether the focused element got its focus from a pointer press.
 *
 * The grids' keys stand aside from a focused control, so that Tab-then-Enter presses what the
 * operator tabbed to. But a click leaves its button focused too — a palette chip, a toolbar toggle,
 * a menu trigger Radix hands focus back to when its menu closes — and there the operator's eye is
 * on the selection, not on the button: Enter should open the cell's editor, not press the button
 * again. So a control focused by a press is not the keyboard's target; one reached by Tab is.
 *
 * The rule is the focus-visible heuristic: a `pointerdown` arms it, any `keydown` disarms it, and
 * each `focusin` records whether it arrived armed. Two refinements: a press on the element that
 * already has the focus records it (no `focusin` fires), and a `focusin` on the element already
 * recorded keeps the record (the window regaining focus re-fires it, after a ⌘Tab whose keydown
 * disarmed) — unless Tab brought the focus back there. Three capture-phase listeners on the window, attached once for the app's life:
 * `main.tsx` installs them at boot, so the click that navigated to a grid is seen too.
 *
 * Two things are never pointer-focused here: a control a modal hides (Radix marks everything
 * outside an open modal `aria-hidden`, and a sheet that keeps focus on the button that opened it
 * must not let the grid behind it take keys), and one marked `data-own-keys` — the Show view's
 * transport and lock, whose keys stay theirs after a click.
 */

let armed = false
let tabbed = false
let pointerFocused: Element | null = null
let installed = false

const onPointerDown = (e: PointerEvent): void => {
  armed = true
  const active = document.activeElement
  if (active != null && active !== document.body && e.target instanceof Node && active.contains(e.target)) {
    pointerFocused = active
  }
}
const onKeyDown = (e: KeyboardEvent): void => {
  armed = false
  // ⌘Tab, Alt+Tab and Ctrl+Tab leave the window or the tab; only a plain Tab moves the focus.
  tabbed = e.key === 'Tab' && !e.metaKey && !e.altKey && !e.ctrlKey
}
const onFocusIn = (e: FocusEvent): void => {
  if (e.target === pointerFocused && !tabbed) return
  pointerFocused = armed && e.target instanceof Element ? e.target : null
}

/** Attach the three listeners, once. */
export function installPointerFocusTracking(): void {
  if (installed || typeof window === 'undefined') return
  installed = true
  window.addEventListener('pointerdown', onPointerDown, true)
  window.addEventListener('keydown', onKeyDown, true)
  window.addEventListener('focusin', onFocusIn, true)
}

/** Is [target] the focused element, and did a pointer press put the focus there? */
export function focusedByPointer(target: EventTarget | null): boolean {
  return (
    target instanceof Element &&
    target === pointerFocused &&
    typeof document !== 'undefined' &&
    target === document.activeElement &&
    target.closest('[aria-hidden="true"], [data-own-keys]') == null
  )
}

/** Make sure the listeners are attached — `main.tsx` has done it already outside a test. */
export function usePointerFocusTracking(): void {
  useEffect(() => installPointerFocusTracking(), [])
}

/** For the test setup: forget the last pointer press and focus between tests. */
export function resetPointerFocus(): void {
  armed = false
  tabbed = false
  pointerFocused = null
}
