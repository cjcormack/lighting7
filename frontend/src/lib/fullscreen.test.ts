// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  FULLSCREEN_FLAG_KEY,
  canFullscreen,
  canLockKeyboard,
  dismissReturnToFullscreen,
  enterFullscreen,
  exitFullscreen,
  getFullscreenState,
  requestReturnToFullscreen,
  resetFullscreenState,
  subscribeFullscreen,
} from './fullscreen'

/**
 * Full screen (multi-screen plan §3.6): the request from a gesture, Keyboard Lock only where the
 * browser has it, the `fullscreenchange` listener as the truth, and the *Return to full screen*
 * ask for a reload and for a `windows.fullscreen {on:true}`.
 */

let fullscreenElement: Element | null = null

beforeEach(() => {
  fullscreenElement = null
  Object.defineProperty(document, 'fullscreenElement', { configurable: true, get: () => fullscreenElement })
  Object.defineProperty(document, 'fullscreenEnabled', { configurable: true, get: () => true })
  document.documentElement.requestFullscreen = vi.fn(async () => {
    fullscreenElement = document.documentElement
    document.dispatchEvent(new Event('fullscreenchange'))
  })
  document.exitFullscreen = vi.fn(async () => {
    fullscreenElement = null
    document.dispatchEvent(new Event('fullscreenchange'))
  })
})

afterEach(() => {
  window.sessionStorage.clear()
  resetFullscreenState()
  delete (navigator as { keyboard?: unknown }).keyboard
})

describe('feature detection', () => {
  it('has the Fullscreen API here and no Keyboard Lock until one is installed', () => {
    expect(canFullscreen()).toBe(true)
    expect(canLockKeyboard()).toBe(false)
    ;(navigator as { keyboard?: unknown }).keyboard = { lock: async () => {} }
    expect(canLockKeyboard()).toBe(true)
  })

  it('reports no Fullscreen API where the document has none (Safari on iPhone)', () => {
    Object.defineProperty(document, 'fullscreenEnabled', { configurable: true, get: () => false })
    expect(canFullscreen()).toBe(false)
  })
})

describe('enterFullscreen', () => {
  it('requests on the document element and locks Escape only where the lock exists', async () => {
    subscribeFullscreen(() => {})
    expect(await enterFullscreen()).toBe(true)
    expect(document.documentElement.requestFullscreen).toHaveBeenCalledTimes(1)
    expect(getFullscreenState()).toEqual({ active: true, wanted: false })
    expect(window.sessionStorage.getItem(FULLSCREEN_FLAG_KEY)).toBe('true')
  })

  it('calls navigator.keyboard.lock(["Escape"]) after the request when the browser has it', async () => {
    const lock = vi.fn(async () => {})
    ;(navigator as { keyboard?: unknown }).keyboard = { lock, unlock: vi.fn() }
    await enterFullscreen()
    expect(lock).toHaveBeenCalledWith(['Escape'])
  })

  it('reports a refused request rather than throwing — every caller is an event handler', async () => {
    document.documentElement.requestFullscreen = vi.fn(async () => {
      throw new TypeError('not from a gesture')
    })
    expect(await enterFullscreen()).toBe(false)
    expect(getFullscreenState().active).toBe(false)
  })
})

describe('exitFullscreen', () => {
  it('exits with no gesture and unlocks the keyboard where it was locked', async () => {
    const unlock = vi.fn()
    ;(navigator as { keyboard?: unknown }).keyboard = { lock: vi.fn(async () => {}), unlock }
    subscribeFullscreen(() => {})
    await enterFullscreen()
    await exitFullscreen()
    expect(document.exitFullscreen).toHaveBeenCalledTimes(1)
    expect(unlock).toHaveBeenCalledTimes(1)
    expect(getFullscreenState().active).toBe(false)
    expect(window.sessionStorage.getItem(FULLSCREEN_FLAG_KEY)).toBe('false')
  })

  it('is a no-op outside full screen', async () => {
    await exitFullscreen()
    expect(document.exitFullscreen).not.toHaveBeenCalled()
  })
})

describe('the return banner', () => {
  it('is wanted after a reload of a window that was full screen, and not otherwise', () => {
    window.sessionStorage.setItem(FULLSCREEN_FLAG_KEY, 'true')
    expect(getFullscreenState()).toEqual({ active: false, wanted: true })
    resetFullscreenState()
    window.sessionStorage.setItem(FULLSCREEN_FLAG_KEY, 'false')
    expect(getFullscreenState()).toEqual({ active: false, wanted: false })
  })

  it('is raised by a request that cannot enter itself, cleared by entering, and by dismissal', async () => {
    const listener = vi.fn()
    subscribeFullscreen(listener)
    requestReturnToFullscreen()
    expect(getFullscreenState()).toEqual({ active: false, wanted: true })
    expect(listener).toHaveBeenCalledTimes(1)

    dismissReturnToFullscreen()
    expect(getFullscreenState().wanted).toBe(false)
    // "Stops asking" outlives the document: a reload must not raise it again.
    expect(window.sessionStorage.getItem(FULLSCREEN_FLAG_KEY)).toBe('false')

    requestReturnToFullscreen()
    await enterFullscreen()
    expect(getFullscreenState()).toEqual({ active: true, wanted: false })
  })

  it('is not raised by leaving full screen — Esc was the operator’s own choice', async () => {
    subscribeFullscreen(() => {})
    await enterFullscreen()
    await exitFullscreen()
    expect(getFullscreenState()).toEqual({ active: false, wanted: false })
  })

  it('is a no-op while already full screen', async () => {
    subscribeFullscreen(() => {})
    await enterFullscreen()
    requestReturnToFullscreen()
    expect(getFullscreenState().wanted).toBe(false)
  })
})
