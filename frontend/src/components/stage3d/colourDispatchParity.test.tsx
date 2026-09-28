// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'
import { Color } from 'three'
import { ColourSync } from './FixtureModel'
import {
  FixtureAppearanceSource,
  type FixtureAppearance,
} from '../fixtures/fixtureAppearance'
import { ChannelSourceProvider } from '../../hooks/useChannelSource'
import type { ChannelSource } from '../../api/channelSource'
import {
  findColourSource,
  findDimmerProperty,
  findGroupColourSource,
  type Fixture,
  type FixtureTypeInfo,
} from '../../store/fixtures'
import type { FixturePatch } from '../../api/patchApi'
import { findGel } from '../../data/gels'
import { chan, colourProp, makeFixture, settingProp, sliderProp } from '../../test/fixtureFactories'

// usePropertyValues imports lightingApi for its writers, and the real module opens a WebSocket
// at import time. The reads all go through the injected ChannelSource, not the mock.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

/**
 * The 2D and 3D colour dispatches must agree, arm for arm.
 *
 * `FixtureAppearanceSource` (React, render prop, feeding the SVG plot and the DOM markers) and
 * `FixtureModel`'s `ColourSync` (imperative, writing the scene from the channel callback) are two
 * copies of one dispatch on purpose — see `docs/stage-vis-engineering.md`, "Why a render prop":
 * R3F is a separate reconciler root and store-driven re-renders drop beat-rate changes, so the 3D
 * path cannot go through hooks. The doc says changing the dispatch means changing both files. This
 * pins that: the same fixture must resolve to the same colour and the same level on both surfaces.
 *
 * It caught nothing when written — the 3D path had simply been *missing* the placeholder arm the
 * 2D path had, which is the divergence this exists to make loud next time.
 */

// Cone opacity is the linear intensity times this fixed scale — see applyColour in FixtureModel.
const CONE_SCALE = 0.32

interface Resolved {
  colour: string
  intensity: number
}

function sourceOf(values: Record<string, number>): ChannelSource {
  const map = new Map(Object.entries(values))
  return {
    get: (universe, channelNo) => map.get(`${universe}:${channelNo}`) ?? 0,
    getByKey: (key) => map.get(key) ?? 0,
    subscribeToChannel: () => ({ unsubscribe: () => {} }),
  }
}

/** Normalise both surfaces' colours — one yields a CSS string, the other a three Color. */
function hex(css: string): string {
  return `#${new Color(css).getHexString()}`
}

function resolve2D(scenario: Scenario): Resolved {
  let captured: FixtureAppearance | undefined
  render(
    <ChannelSourceProvider source={sourceOf(scenario.values)}>
      <FixtureAppearanceSource
        patch={scenario.patch}
        fixture={scenario.fixture}
        fixtureType={scenario.fixtureType}
      >
        {(appearance) => {
          captured = appearance
          return null
        }}
      </FixtureAppearanceSource>
    </ChannelSourceProvider>,
  )
  if (!captured) throw new Error('render prop never ran')
  return { colour: hex(captured.color), intensity: captured.intensity }
}

function resolve3D(scenario: Scenario): Resolved {
  // The derivation FixtureModel performs before handing ColourSync its props. Duplicated here
  // rather than exported, because the seam under test is the *dispatch*, not the lookups.
  const { patch, fixture, fixtureType } = scenario
  const colourSource = fixture?.properties ? findColourSource(fixture.properties) : undefined
  const groupColour = findGroupColourSource(fixture)
  const pixelCount = groupColour ? groupColour.memberColourChannels.length : 0
  const gel = !colourSource && fixtureType?.acceptsGel && patch.gelCode ? findGel(patch.gelCode) : null

  const colorStateRef = {
    current: { color: new Color('#000000'), coneOpacity: -1, poolOpacity: -1 },
  }
  render(
    <ChannelSourceProvider source={sourceOf(scenario.values)}>
      <ColourSync
        hasFixture={!!fixture}
        colourSource={colourSource}
        groupColour={pixelCount > 1 ? groupColour : undefined}
        gel={gel}
        dimmerProp={findDimmerProperty(fixture?.properties)}
        lensRef={{ current: null }}
        colorStateRef={colorStateRef}
        pixelColorsRef={{ current: null }}
      />
    </ChannelSourceProvider>,
  )
  return {
    colour: `#${colorStateRef.current.color.getHexString()}`,
    intensity: colorStateRef.current.coneOpacity / CONE_SCALE,
  }
}

interface Scenario {
  patch: FixturePatch
  fixture: Fixture | undefined
  fixtureType: FixtureTypeInfo | undefined
  values: Record<string, number>
}

const PATCH = { id: 1, key: 'fx-1', displayName: 'Fixture 1' } as FixturePatch
const DIMMER = sliderProp('dimmer', 'dimmer', chan(1))
const RGB = colourProp('rgb', chan(2), chan(3), chan(4))
const WHEEL = settingProp('colourWheel', 'colour', chan(5), [
  { name: 'open', level: 0, displayName: 'Open' },
  { name: 'red', level: 10, displayName: 'Red', colourPreview: '#ff0000' },
])

function scenario(over: Partial<Scenario>): Scenario {
  return { patch: PATCH, fixture: undefined, fixtureType: undefined, values: {}, ...over }
}

const SCENARIOS: Array<{ name: string; scenario: Scenario }> = [
  {
    name: 'a patch whose fixture record has not resolved',
    scenario: scenario({ fixture: undefined }),
  },
  {
    name: 'a dimmer-only fixture, half up',
    scenario: scenario({
      fixture: makeFixture('fx-1', [DIMMER]),
      values: { '0:1': 128 },
    }),
  },
  {
    name: 'a gelled fixture on a type that accepts gel',
    scenario: scenario({
      patch: { ...PATCH, gelCode: 'L106' } as FixturePatch,
      fixture: makeFixture('fx-1', [DIMMER]),
      fixtureType: { typeKey: 't', acceptsGel: true } as FixtureTypeInfo,
      values: { '0:1': 255 },
    }),
  },
  {
    name: 'a gel code on a type that does not accept gel (stale data)',
    scenario: scenario({
      patch: { ...PATCH, gelCode: 'L106' } as FixturePatch,
      fixture: makeFixture('fx-1', [DIMMER]),
      fixtureType: { typeKey: 't', acceptsGel: false } as FixtureTypeInfo,
      values: { '0:1': 255 },
    }),
  },
  {
    name: 'an RGB fixture beating out its gel code',
    scenario: scenario({
      patch: { ...PATCH, gelCode: 'L106' } as FixturePatch,
      fixture: makeFixture('fx-1', [DIMMER, RGB]),
      fixtureType: { typeKey: 't', acceptsGel: true } as FixtureTypeInfo,
      values: { '0:1': 200, '0:2': 255, '0:3': 40, '0:4': 0 },
    }),
  },
  {
    name: 'a colour-wheel fixture on a selected preset',
    scenario: scenario({
      fixture: makeFixture('fx-1', [DIMMER, WHEEL]),
      values: { '0:1': 255, '0:5': 10 },
    }),
  },
  {
    name: 'a colour-wheel fixture parked at open',
    scenario: scenario({
      fixture: makeFixture('fx-1', [DIMMER, WHEEL]),
      values: { '0:1': 255, '0:5': 0 },
    }),
  },
]

describe('2D and 3D colour dispatch parity', () => {
  it.each(SCENARIOS)('resolves $name identically on both surfaces', ({ scenario: s }) => {
    const twoD = resolve2D(s)
    const threeD = resolve3D(s)
    expect(threeD.colour).toBe(twoD.colour)
    expect(threeD.intensity).toBeCloseTo(twoD.intensity, 10)
  })
})
