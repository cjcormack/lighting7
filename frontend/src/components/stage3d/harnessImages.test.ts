import { describe, expect, it } from 'vitest'
import {
  DAYNIGHT_SUN,
  DAYNIGHT_WINDOWS,
  FOLIAGE_SOLID_SHARE,
  HARNESS_IMAGE_SIZE,
  harnessImageKinds,
  harnessImagePixels,
  type HarnessPixels,
} from './harnessImages'
import { MASK_HOLE_BELOW } from './scene/sceneMasks'

const pixel = (img: HarnessPixels, x: number, y: number) => {
  const i = (y * img.width + x) * 4
  return Array.from(img.data.slice(i, i + 4))
}

/** FNV-1a over the pixels: a deep-equal of a 2 MB array takes vitest seconds. */
function checksum(img: HarnessPixels): number {
  let h = 0x811c9dc5
  for (let i = 0; i < img.data.length; i++) h = Math.imul(h ^ img.data[i], 0x01000193)
  return h >>> 0
}

/** The share of a band of rows whose alpha the mask reads as a hole. */
function holeShare(img: HarnessPixels, y0: number, y1: number): number {
  let holes = 0
  for (let y = y0; y < y1; y++) for (let x = 0; x < img.width; x++) if (img.data[(y * img.width + x) * 4 + 3] < MASK_HOLE_BELOW) holes++
  return holes / ((y1 - y0) * img.width)
}

describe('the cloth scenes’ paint (scrim plan session 6)', () => {
  it('draws each image at its cloth’s size, the same every time', () => {
    for (const kind of ['foliage', 'dayFront', 'nightBack'] as const) {
      const img = harnessImagePixels(kind)
      expect([img.width, img.height]).toEqual([HARNESS_IMAGE_SIZE[kind].width, HARNESS_IMAGE_SIZE[kind].height])
      expect(checksum(img)).toBe(checksum(harnessImagePixels(kind)))
    }
    expect(harnessImageKinds('cutcloth')).toEqual(['foliage'])
    expect(harnessImageKinds('daynight')).toEqual(['dayFront', 'nightBack'])
    expect(harnessImageKinds('scrim')).toEqual([])
    expect(harnessImageKinds(null)).toEqual([])
  })

  it('leaves the foliage solid under its batten and cuts more holes the lower it goes', () => {
    const img = harnessImagePixels('foliage')
    const solid = Math.round(img.height * FOLIAGE_SOLID_SHARE)
    expect(holeShare(img, 0, solid)).toBe(0)
    const upper = holeShare(img, solid, Math.round(img.height * 0.5))
    const lower = holeShare(img, Math.round(img.height * 0.6), img.height)
    expect(upper).toBeLessThan(lower)
    // Enough cloth and enough holes in the lower half for both a shadow and a shaft to read.
    expect(lower).toBeGreaterThan(0.25)
    expect(lower).toBeLessThan(0.9)
  })

  it('paints the day opaque, and the night black but for openings mirrored to the day’s windows and sun', () => {
    const day = harnessImagePixels('dayFront')
    const night = harnessImagePixels('nightBack')
    let opaque = true
    for (let i = 3; i < day.data.length; i += 4) if (day.data[i] !== 255) opaque = false
    expect(opaque).toBe(true)
    const [x0, y0, x1, y1] = DAYNIGHT_WINDOWS[0]
    const cx = Math.floor((x0 + x1) / 2)
    const cy = Math.floor((y0 + y1) / 2)
    // Seen from behind, read at 1 − u: the window's opening is at the mirror of the front's.
    expect(pixel(night, night.width - 1 - cx, cy)).toEqual([255, 255, 255, 255])
    expect(pixel(night, cx, cy)).toEqual([0, 0, 0, 255])
    // The moon's lit edge where the sun is; most of the cloth black.
    expect(pixel(night, night.width - 1 - (DAYNIGHT_SUN.x - DAYNIGHT_SUN.r + 4), DAYNIGHT_SUN.y)).toEqual([255, 255, 255, 255])
    let black = 0
    for (let i = 0; i < night.data.length; i += 4) if (night.data[i] === 0) black++
    expect(black / (night.width * night.height)).toBeGreaterThan(0.95)
  })
})
