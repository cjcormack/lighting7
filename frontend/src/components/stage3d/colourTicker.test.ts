import { describe, expect, it, vi } from 'vitest'
import { createColourTicker } from './colourTicker'

describe('the colour ticker', () => {
  it('asks for a frame each frame while — and only while — an animated band is registered', () => {
    const invalidate = vi.fn()
    const ticker = createColourTicker(invalidate)

    // Nothing live: frames ask for nothing.
    ticker.frame(0.1)
    ticker.frame(0.2)
    expect(invalidate).not.toHaveBeenCalled()

    const apply = vi.fn()
    const stop = ticker.onFrame(apply)
    // Registering starts the loop without waiting for something else to draw.
    expect(invalidate).toHaveBeenCalledTimes(1)

    ticker.frame(0.3)
    ticker.frame(0.4)
    expect(apply).toHaveBeenCalledTimes(2)
    expect(invalidate).toHaveBeenCalledTimes(3)
    expect(ticker.now()).toBe(0.4)

    stop()
    ticker.frame(0.5)
    expect(apply).toHaveBeenCalledTimes(2)
    expect(invalidate).toHaveBeenCalledTimes(3)
    expect(ticker.listening).toBe(0)
  })

  it('runs every listener once a frame, then asks once', () => {
    const invalidate = vi.fn()
    const ticker = createColourTicker(invalidate)
    const a = vi.fn()
    const b = vi.fn()
    ticker.onFrame(a)
    ticker.onFrame(b)
    invalidate.mockClear()
    ticker.frame(1)
    expect(a).toHaveBeenCalledTimes(1)
    expect(b).toHaveBeenCalledTimes(1)
    expect(invalidate).toHaveBeenCalledTimes(1)
  })
})
