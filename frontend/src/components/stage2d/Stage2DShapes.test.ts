import { describe, expect, it, vi } from 'vitest'

// store/fixtures imports lightingApi, which opens a WebSocket at import time. Same guard as
// useRowOwnership.test.ts — nothing here touches the api itself.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { stripGeometry } from './Stage2DShapes'
import { chan, colourProp, makeFixture, makePixelBar, sliderProp } from '../../test/fixtureFactories'
import { findGroupColourSource, type Fixture } from '../../store/fixtures'
import type { GroupColourPropertyDescriptor } from '../../api/groupsApi'

const SCREEN = { h: 10, v: 20 }
/** 1 metre per pixel keeps the arithmetic readable: geometry comes back in px-equivalents. */
const M_PER_PX = 1
const R = 7 * M_PER_PX

function pixelBar(heads: number): Fixture {
  const bar = makePixelBar('bar-1', heads)
  const memberColourChannels = (bar.elements ?? []).map((el, i) => {
    const colour = el.properties[0] as ReturnType<typeof colourProp>
    return {
      fixtureKey: el.key,
      redChannel: colour.redChannel,
      greenChannel: colour.greenChannel,
      blueChannel: colour.blueChannel,
      index: i,
    }
  })
  const groupColour = {
    type: 'colour',
    name: 'rgbColour',
    displayName: 'Colour',
    category: 'colour',
    memberColourChannels,
  } as unknown as GroupColourPropertyDescriptor
  return { ...bar, elementGroupProperties: [groupColour] }
}

describe('stripGeometry', () => {
  it('is null for a plain fixture, which therefore draws as a dot', () => {
    const fixture = makeFixture('fx-1', [
      sliderProp('dimmer', 'dimmer', chan(1)),
      colourProp('rgbColour', chan(2), chan(3), chan(4)),
    ])
    expect(stripGeometry(fixture, SCREEN, R, M_PER_PX)).toBeNull()
  })

  it('is null for a missing fixture', () => {
    expect(stripGeometry(undefined, SCREEN, R, M_PER_PX)).toBeNull()
  })

  it('is null for a single-element group — one pixel is not a bar', () => {
    expect(stripGeometry(pixelBar(1), SCREEN, R, M_PER_PX)).toBeNull()
  })

  it('centres a strip on the fixture and widens it with the pixel count', () => {
    const geometry = stripGeometry(pixelBar(12), SCREEN, R, M_PER_PX)
    expect(geometry).not.toBeNull()
    const { left, top, width, height, count } = geometry!
    expect(count).toBe(12)
    // 12 pixels × 4px each beats the 2r floor, so the count drives the width.
    expect(width).toBe(48)
    expect(height).toBeCloseTo(2 * R * 0.55, 5)
    expect(left + width / 2).toBe(SCREEN.h)
    expect(top + height / 2).toBe(SCREEN.v)
  })

  it('never narrows below the dot it replaces', () => {
    // Two pixels would be 8px on the count alone; the 2r floor keeps it dot-sized.
    expect(stripGeometry(pixelBar(2), SCREEN, R, M_PER_PX)?.width).toBe(2 * R)
  })

  it('scales with mPerPx so the strip holds its screen size under zoom', () => {
    const zoomed = stripGeometry(pixelBar(12), SCREEN, 7 * 0.5, 0.5)
    expect(zoomed?.width).toBe(24)
  })

  it('gates on the same condition FixtureAppearanceSource uses for segments', () => {
    // These two must agree: FixtureAppearanceSource emits `segments` only when a group colour
    // has more than one member, and this decides the strip's shape and hit target. If they
    // diverge, a bar gets a dot's hit target or a dot gets a strip-shaped one.
    for (const heads of [1, 2, 8]) {
      const fixture = pixelBar(heads)
      const emitsSegments = (findGroupColourSource(fixture)?.memberColourChannels.length ?? 0) > 1
      expect(stripGeometry(fixture, SCREEN, R, M_PER_PX) !== null).toBe(emitsSegments)
    }
  })
})
