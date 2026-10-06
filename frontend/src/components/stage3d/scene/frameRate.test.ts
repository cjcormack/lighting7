import { describe, expect, it } from 'vitest'
import { FrameRate, formatFrameRate, isSlowReading } from './frameRate'

/** [count] frames [gap] ms apart from [from]. */
function run(rate: FrameRate, from: number, gap: number, count: number): number {
  let t = from
  for (let i = 0; i < count; i++) {
    t = from + i * gap
    rate.frame(t)
  }
  return t
}

describe('FrameRate', () => {
  it('counts the frames drawn in the trailing second', () => {
    const rate = new FrameRate()
    const last = run(rate, 0, 20, 100) // 0 … 1980
    // The frames in (980, 1980]: 1000 … 1980.
    expect(rate.fps(last)).toBe(50)
    // Half a second later the window holds the last half of them.
    expect(rate.fps(last + 500)).toBe(25)
    expect(rate.fps(last + 1000)).toBe(0)
  })

  it('takes the median gap, not the mean, between frames under a second apart', () => {
    const rate = new FrameRate()
    for (const t of [0, 16, 32, 48, 200, 216, 232]) rate.frame(t)
    // Gaps 16, 16, 16, 152, 16, 16: one hitch does not move the median.
    expect(rate.msPerFrame()).toBe(16)

    const even = new FrameRate()
    for (const t of [0, 10, 30, 60, 100]) even.frame(t)
    // Gaps 10, 20, 30, 40: the median of an even count is the mean of the middle two.
    expect(even.msPerFrame()).toBe(25)
  })

  it('has no gap to report from a single frame', () => {
    const rate = new FrameRate()
    expect(rate.msPerFrame()).toBeNull()
    rate.frame(100)
    expect(rate.msPerFrame()).toBeNull()
    expect(rate.reading(100)).toEqual({ fps: 1, ms: null })
  })

  it('goes idle a second after the last frame', () => {
    const rate = new FrameRate()
    expect(rate.idle(0)).toBe(true)
    expect(rate.reading(0)).toBeNull()
    rate.frame(500)
    expect(rate.idle(1499)).toBe(false)
    expect(rate.idle(1500)).toBe(true)
    expect(rate.reading(1500)).toBeNull()
  })

  it('judges a run after idle on its own frames', () => {
    const rate = new FrameRate()
    run(rate, 0, 16, 40) // a 16 ms run, ending at 624
    expect(rate.idle(2000)).toBe(true)
    // A slower run starts at 5000; the gap across the idle spell and the old run's gaps count for nothing.
    const last = run(rate, 5000, 40, 10)
    expect(rate.msPerFrame()).toBe(40)
    expect(rate.fps(last)).toBe(10)
    expect(rate.reading(last)).toEqual({ fps: 10, ms: 40 })
  })

  it('counts no more frames than its ring holds', () => {
    const rate = new FrameRate()
    const last = run(rate, 0, 2, 1000)
    // 500 fps for two seconds: the trailing second is the last 500 frames, more than the ring holds.
    expect(rate.fps(last)).toBe(256)
    expect(rate.msPerFrame()).toBe(2)
  })
})

describe('the readout text', () => {
  it('reads fps and ms, fps alone, or idle', () => {
    expect(formatFrameRate({ fps: 58, ms: 17.24 })).toBe('58 fps · 17.2 ms')
    expect(formatFrameRate({ fps: 1, ms: null })).toBe('1 fps')
    expect(formatFrameRate(null)).toBe('idle')
  })

  it('is slow only past 28 ms', () => {
    expect(isSlowReading({ fps: 36, ms: 28 })).toBe(false)
    expect(isSlowReading({ fps: 35, ms: 28.1 })).toBe(true)
    expect(isSlowReading({ fps: 1, ms: null })).toBe(false)
    expect(isSlowReading(null)).toBe(false)
  })
})
