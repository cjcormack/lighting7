/**
 * Keeps keys typed into the Sentry feedback form from the desk's shortcuts. The form lives in a shadow
 * root on `<body>`, so outside it a key typed in its textarea is retargeted to the shadow host — a
 * `<div>` that no shortcut's field or dialog guard recognises.
 *
 * One window capture listener, installed before every shortcut, stops a keydown or keypress there, so
 * no later listener hears it. A key's default action, the character typed, does not depend on
 * propagation, and the form has no key listeners of its own. A keyup goes through: the window's keyup
 * listeners only track held keys, and a modifier released in the form must still read as released.
 */
export const FEEDBACK_HOST_ID = 'sentry-feedback'

const KEY_EVENTS = ['keydown', 'keypress'] as const

let installed = false

const stopAtHost = (e: Event): void => {
  if (e.target instanceof Element && e.target.id === FEEDBACK_HOST_ID) e.stopImmediatePropagation()
}

/** Attach the listeners, once. Must run before any shortcut listens for keys on the window. */
export function isolateFeedbackKeys(): void {
  if (installed || typeof window === 'undefined') return
  installed = true
  for (const type of KEY_EVENTS) window.addEventListener(type, stopAtHost, true)
}
