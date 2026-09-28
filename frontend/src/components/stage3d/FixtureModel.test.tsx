// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'
import { Color } from 'three'
import { ColourSync } from './FixtureModel'
import {
  DEFAULT_FIXTURE_COLOUR,
  PLACEHOLDER_FIXTURE_COLOUR,
  PLACEHOLDER_FIXTURE_INTENSITY,
} from '../fixtures/fixtureAppearance'
import { ChannelSourceProvider } from '../../hooks/useChannelSource'
import type { ChannelSource } from '../../api/channelSource'
import { chan, sliderProp } from '../../test/fixtureFactories'

// usePropertyValues imports lightingApi for its writers, and the real module opens a WebSocket
// at import time. The reads all go through the injected ChannelSource, not the mock.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

// Cone and pool opacity are the linear intensity times these fixed scales — see applyColour.
const CONE_SCALE = 0.32
const POOL_SCALE = 0.55

interface Rendered {
  colour: string
  coneOpacity: number
  poolOpacity: number
  subscribed: number
}

/**
 * Render one arm of the dispatch and report what it wrote into the shared colour state.
 *
 * `lensRef` stays null: the lens is a child of the body mesh, which only exists inside an R3F
 * canvas, and `applyColour` already tolerates its absence (it does on the first render of a real
 * fixture too). The beam half — `colorStateRef` — is the half the bug was about.
 */
function renderArm(
  props: Omit<Parameters<typeof ColourSync>[0], 'lensRef' | 'colorStateRef' | 'pixelColorsRef'>,
  values: Record<string, number> = {},
): Rendered {
  const map = new Map(Object.entries(values))
  let subscribed = 0
  const source: ChannelSource = {
    get: (universe, channelNo) => map.get(`${universe}:${channelNo}`) ?? 0,
    getByKey: (key) => map.get(key) ?? 0,
    subscribeToChannel: () => {
      subscribed += 1
      return { unsubscribe: () => {} }
    },
  }
  const colorStateRef = {
    current: { color: new Color('#000000'), coneOpacity: -1, poolOpacity: -1 },
  }
  render(
    <ChannelSourceProvider source={source}>
      <ColourSync
        {...props}
        lensRef={{ current: null }}
        colorStateRef={colorStateRef}
        pixelColorsRef={{ current: null }}
      />
    </ChannelSourceProvider>,
  )
  return {
    colour: `#${colorStateRef.current.color.getHexString()}`,
    coneOpacity: colorStateRef.current.coneOpacity,
    poolOpacity: colorStateRef.current.poolOpacity,
    subscribed,
  }
}

describe('ColourSync', () => {
  const DIMMER = sliderProp('dimmer', 'dimmer', chan(1))

  it('draws a patch with no fixture record as the shared dim placeholder, not a lit lamp', () => {
    const out = renderArm({
      hasFixture: false,
      colourSource: undefined,
      groupColour: undefined,
      gel: null,
      dimmerProp: undefined,
    })
    expect(out.colour).toBe(`#${new Color(PLACEHOLDER_FIXTURE_COLOUR).getHexString()}`)
    expect(out.coneOpacity).toBeCloseTo(CONE_SCALE * PLACEHOLDER_FIXTURE_INTENSITY, 10)
    expect(out.poolOpacity).toBeCloseTo(POOL_SCALE * PLACEHOLDER_FIXTURE_INTENSITY, 10)
  })

  it('subscribes to nothing on the placeholder arm, even where a dimmer descriptor survives', () => {
    const out = renderArm({
      hasFixture: false,
      colourSource: undefined,
      groupColour: undefined,
      gel: null,
      dimmerProp: DIMMER,
    })
    expect(out.subscribed).toBe(0)
    expect(out.coneOpacity).toBeCloseTo(CONE_SCALE * PLACEHOLDER_FIXTURE_INTENSITY, 10)
  })

  it('still beams warm white for a real fixture with no colour source and no gel', () => {
    const out = renderArm({
      hasFixture: true,
      colourSource: undefined,
      groupColour: undefined,
      gel: null,
      dimmerProp: DIMMER,
    })
    expect(out.colour).toBe(`#${new Color(DEFAULT_FIXTURE_COLOUR).getHexString()}`)
    // Dimmer at full: the placeholder's dimness must come from the arm, not from a dark frame.
    expect(out.coneOpacity).toBeCloseTo(0, 10)
    expect(out.subscribed).toBe(1)
  })

  it('takes the gel colour ahead of the warm-white default for a real fixture', () => {
    const out = renderArm(
      {
        hasFixture: true,
        colourSource: undefined,
        groupColour: undefined,
        gel: { color: '#ff0000' },
        dimmerProp: DIMMER,
      },
      { '0:1': 255 },
    )
    expect(out.colour).toBe('#ff0000')
    expect(out.coneOpacity).toBeCloseTo(CONE_SCALE, 10)
  })
})
