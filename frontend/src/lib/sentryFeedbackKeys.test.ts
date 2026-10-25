// @vitest-environment jsdom
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { FEEDBACK_HOST_ID, isolateFeedbackKeys } from './sentryFeedbackKeys'

const MAIN = import.meta.glob<string>('/src/main.tsx', { query: '?raw', import: 'default', eager: true })

/** The form as the SDK builds it: a `<div>` on `<body>` with an open shadow root holding the field. */
function mountFeedbackForm(): HTMLTextAreaElement {
  const host = document.createElement('div')
  host.id = FEEDBACK_HOST_ID
  document.body.appendChild(host)
  const textarea = document.createElement('textarea')
  host.attachShadow({ mode: 'open' }).appendChild(textarea)
  return textarea
}

function press(target: EventTarget, type: string, key: string): KeyboardEvent {
  const event = new KeyboardEvent(type, { key, bubbles: true, cancelable: true, composed: true })
  target.dispatchEvent(event)
  return event
}

describe('isolateFeedbackKeys', () => {
  beforeAll(() => isolateFeedbackKeys())

  const removers: (() => void)[] = []
  afterEach(() => {
    removers.splice(0).forEach((remove) => remove())
    document.body.replaceChildren()
  })

  /** A desk shortcut registered after the guard, as every one is, in both phases. */
  function listen(target: Window | Document, type: string): ReturnType<typeof vi.fn> {
    const heard = vi.fn()
    for (const capture of [true, false]) {
      target.addEventListener(type, heard, capture)
      removers.push(() => target.removeEventListener(type, heard, capture))
    }
    return heard
  }

  it('keeps a key typed into the form from the window and document listeners', () => {
    const textarea = mountFeedbackForm()
    const heard = ['keydown', 'keypress'].flatMap((type) => [listen(window, type), listen(document, type)])
    for (const type of ['keydown', 'keypress']) {
      const event = press(textarea, type, 'a')
      expect(event.defaultPrevented).toBe(false)
    }
    press(textarea, 'keydown', 'Backspace')
    for (const listener of heard) expect(listener).not.toHaveBeenCalled()
  })

  it('lets a keyup out, so a modifier released in the form reads as released', () => {
    const textarea = mountFeedbackForm()
    const heard = listen(window, 'keyup')
    press(textarea, 'keyup', 'Shift')
    expect(heard).toHaveBeenCalledTimes(2)
  })

  it('leaves every other key to the desk', () => {
    mountFeedbackForm()
    const field = document.body.appendChild(document.createElement('input'))
    const heard = listen(window, 'keydown')
    press(field, 'keydown', 'a')
    press(document.body, 'keydown', 'Backspace')
    expect(heard).toHaveBeenCalledTimes(4)
  })

  it('does nothing while the form has never been opened', () => {
    const heard = listen(document, 'keydown')
    press(document.body, 'keydown', 'a')
    expect(heard).toHaveBeenCalledTimes(2)
  })

  it('is installed by main.tsx after the pointer-focus tracker, which must still see a Tab out of the form', () => {
    const main = MAIN['/src/main.tsx']
    const tracker = main.indexOf('\ninstallPointerFocusTracking()')
    const sentry = main.indexOf('\nstartSentry()')
    expect(tracker).toBeGreaterThan(0)
    expect(sentry).toBeGreaterThan(tracker)
  })
})
