// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SCREENSHOT_BUDGET, SCREENSHOT_MAX_SIDE, fitScreenshot, fitScreenshots } from './sentryScreenshot'

const png = (size: number) => ({ filename: 'screenshot.png', contentType: 'image/png', data: new Uint8Array(size) })

describe('fitScreenshot', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('sends a screenshot that already fits as it is', async () => {
    const small = png(SCREENSHOT_BUDGET)
    expect(await fitScreenshot(small)).toBe(small)
  })

  it('redraws a large one as a JPEG no wider than the cap, at the first quality that fits', async () => {
    vi.stubGlobal('createImageBitmap', async () => ({ width: 3840, height: 2160, close: () => {} }))
    const drawImage = vi.fn()
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage, fillRect: vi.fn() } as unknown as CanvasRenderingContext2D)
    const qualities: number[] = []
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(function (this: HTMLCanvasElement, done, type, quality) {
      qualities.push(quality as number)
      expect(type).toBe('image/jpeg')
      done(new Blob([new Uint8Array(quality === 0.85 ? SCREENSHOT_BUDGET + 1 : 1000)]))
    })

    const fitted = await fitScreenshot(png(3_000_000))
    expect(qualities).toEqual([0.85, 0.7])
    expect(drawImage).toHaveBeenCalledWith(expect.anything(), 0, 0, SCREENSHOT_MAX_SIDE, 1080)
    expect(fitted).toMatchObject({ filename: 'screenshot.jpg', contentType: 'image/jpeg' })
    expect((fitted?.data as Uint8Array).byteLength).toBe(1000)
  })

  it('leaves out a screenshot it cannot fit, and keeps the rest of the feedback', async () => {
    vi.stubGlobal('createImageBitmap', async () => {
      throw new Error('undecodable')
    })
    const hint = { attachments: [png(3_000_000), png(10)] }
    await fitScreenshots(hint)
    expect(hint.attachments.map((a) => a.data.byteLength)).toEqual([10])
  })
})
