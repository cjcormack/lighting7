import { describe, it, expect } from 'vitest'
import { MAX_PRISM_LOBES } from './emitterLayout'
import {
  FOCUS_ALWAYS_SHARP,
  FOCUS_NEAR_FRAC,
  GOBO_SLOT_COUNT,
  PRISM_FACETS,
  computeBeamGeom,
  evalLedMacro,
  evalMovementMacro,
  makeBeamGeom,
  prismSpinFromSlider,
  resolveFocusDistance,
  resolveFocusParam,
  resolveGoboSlot,
  resolveGoboSpin,
  resolveMacroIndex,
  resolvePrismFacets,
  resolvePrismSpin,
  settingBand,
} from './beamOptics'
import { goboLayerFor } from './goboPatterns'
import type {
  SettingOption,
  SettingPropertyDescriptor,
  SliderPropertyDescriptor,
} from '../../store/fixtures'

function opts(pairs: [string, number, Partial<SettingOption>?][]): SettingOption[] {
  return pairs.map(([name, level, over]) => ({ name, level, displayName: name, ...over }))
}

function setting(
  options: SettingOption[],
  category: SettingPropertyDescriptor['category'] = 'gobo',
): SettingPropertyDescriptor {
  return {
    type: 'setting',
    name: 'x',
    displayName: 'X',
    category,
    channel: { universe: 1, channelNo: 1 },
    options,
  }
}

function slider(
  over: Partial<SliderPropertyDescriptor> = {},
): SliderPropertyDescriptor {
  return {
    type: 'slider',
    name: 'x',
    displayName: 'X',
    category: 'focus',
    channel: { universe: 1, channelNo: 1 },
    min: 0,
    max: 255,
    ...over,
  }
}

// The real Equinox Fusion 100 Spot MKII enums.
const FUSION_GOBO = opts([
  ['OPEN_WHITE', 0],
  ['GOBO_1', 9],
  ['GOBO_2', 34],
  ['GOBO_3', 59],
  ['GOBO_4', 84],
  ['GOBO_5', 109],
  ['RAINBOW_EFFECT', 134],
  ['REVERSE_RAINBOW_EFFECT', 195],
])
const FUSION_GOBO_ROT = opts([
  ['ROTATION_STOP', 0],
  ['FORWARD_ROTATION_FAST', 10],
  ['FORWARD_ROTATION_SLOW', 129],
  ['ROTATION_STOP_2', 130],
  ['REVERSE_ROTATION_SLOW', 135],
  ['REVERSE_ROTATION_FAST', 255],
])

describe('settingBand', () => {
  it('derives band ends from the next option start', () => {
    expect(settingBand(FUSION_GOBO, 0)).toMatchObject({ start: 0, end: 8, index: 0 })
    expect(settingBand(FUSION_GOBO, 9)).toMatchObject({ start: 9, end: 33, index: 1 })
    expect(settingBand(FUSION_GOBO, 20)).toMatchObject({ start: 9, end: 33, index: 1 })
    expect(settingBand(FUSION_GOBO, 33)).toMatchObject({ start: 9, end: 33, index: 1 })
    expect(settingBand(FUSION_GOBO, 34)).toMatchObject({ start: 34, index: 2 })
  })

  it('runs the last band to 255', () => {
    expect(settingBand(FUSION_GOBO, 255)).toMatchObject({ start: 195, end: 255, index: 7 })
  })

  it('handles a level below the first option and an empty list', () => {
    expect(settingBand(opts([['A', 10]]), 0)).toMatchObject({ start: 10, end: 255, index: 0 })
    expect(settingBand([], 100)).toMatchObject({ index: -1 })
  })

  // The frontend rule is "last option at or below", which is correct band
  // semantics. Pinned because the backend's valueForLevel does the opposite.
  it('picks the band a value sits inside, not the next one up', () => {
    expect(settingBand(FUSION_GOBO, 20).index).toBe(1) // GOBO_1, not GOBO_2
  })
})

describe('resolveGoboSlot', () => {
  it('is 0 for the open position and for a missing property', () => {
    expect(resolveGoboSlot(setting(FUSION_GOBO), 0)).toBe(0)
    expect(resolveGoboSlot(undefined, 200)).toBe(0)
  })

  it('gives each wheel position a distinct non-zero slot', () => {
    const slots = [9, 34, 59, 84, 109].map((l) => resolveGoboSlot(setting(FUSION_GOBO), l))
    expect(new Set(slots).size).toBe(slots.length)
    for (const s of slots) {
      expect(s).toBeGreaterThan(0)
      expect(s).toBeLessThan(GOBO_SLOT_COUNT)
    }
  })

  // The annotated MAC 250 wheel as the backend now serialises it.
  const MAC_GOBO = opts([
    ['OPEN', 0],
    ['CONE', 10, { gobo: 'cone' }],
    ['BAR', 20, { gobo: 'bars' }],
    ['FIBROID', 60, { gobo: 'fibroid' }],
    ['CONE_SHAKE', 195, { gobo: 'cone' }],
    ['SCROLL_CW', 210],
  ])

  it('resolves a backend pattern name through the registry', () => {
    expect(resolveGoboSlot(setting(MAC_GOBO), 10)).toBe(goboLayerFor('cone'))
    expect(resolveGoboSlot(setting(MAC_GOBO), 60)).toBe(goboLayerFor('fibroid'))
    // Shake variant shares the base slot's artwork.
    expect(resolveGoboSlot(setting(MAC_GOBO), 195)).toBe(resolveGoboSlot(setting(MAC_GOBO), 10))
  })

  it('renders unnamed positions on an annotated wheel as open, not index-hashed', () => {
    // OPEN and the moving scroll band carry no pattern on purpose.
    expect(resolveGoboSlot(setting(MAC_GOBO), 0)).toBe(0)
    expect(resolveGoboSlot(setting(MAC_GOBO), 220)).toBe(0)
  })

  it('renders a pattern name this build does not know as open', () => {
    const future = setting(opts([
      ['OPEN', 0],
      ['NEW_HOTNESS', 10, { gobo: 'hyperspace_vortex' }],
    ]))
    expect(resolveGoboSlot(future, 10)).toBe(0)
  })

  it('treats descriptive Martin-style names as ordinary positions', () => {
    const martin = opts([
      ['OPEN', 0],
      ['FIBROID', 20],
      ['DEC_BEAM', 40],
      ['CONE_SHAKE', 60],
    ])
    expect(resolveGoboSlot(setting(martin), 0)).toBe(0)
    expect(resolveGoboSlot(setting(martin), 20)).toBeGreaterThan(0)
    expect(resolveGoboSlot(setting(martin), 40)).not.toBe(
      resolveGoboSlot(setting(martin), 20),
    )
  })
})

describe('resolveGoboSpin', () => {
  it('is zero when stopped or absent', () => {
    expect(resolveGoboSpin(setting(FUSION_GOBO_ROT, 'gobo_rotation'), 0)).toBe(0)
    expect(resolveGoboSpin(setting(FUSION_GOBO_ROT, 'gobo_rotation'), 130)).toBe(0)
    expect(resolveGoboSpin(undefined, 200)).toBe(0)
  })

  it('signs forward positive and reverse negative', () => {
    const p = setting(FUSION_GOBO_ROT, 'gobo_rotation')
    expect(resolveGoboSpin(p, 50)).toBeGreaterThan(0) // FORWARD_ROTATION_FAST
    expect(resolveGoboSpin(p, 129)).toBeGreaterThan(0) // FORWARD_ROTATION_SLOW
    expect(resolveGoboSpin(p, 140)).toBeLessThan(0) // REVERSE_ROTATION_SLOW
    expect(resolveGoboSpin(p, 255)).toBeLessThan(0) // REVERSE_ROTATION_FAST
  })

  // The Fusion lists FAST before SLOW, so direction and speed must come from the
  // band's name rather than its position in the list.
  it('reads speed from the band name, not its ordinal', () => {
    const p = setting(FUSION_GOBO_ROT, 'gobo_rotation')
    const fast = resolveGoboSpin(p, 120) // deep into FORWARD_ROTATION_FAST
    const slow = resolveGoboSpin(p, 129) // FORWARD_ROTATION_SLOW
    expect(fast).toBeGreaterThan(slow)
  })

  it('uses the 0-stop / 1-127 forward / 128-255 reverse slider convention', () => {
    const p = slider({ category: 'gobo_rotation' })
    expect(resolveGoboSpin(p, 0)).toBe(0)
    expect(resolveGoboSpin(p, 127)).toBeGreaterThan(0)
    expect(resolveGoboSpin(p, 64)).toBeLessThan(resolveGoboSpin(p, 127))
    expect(resolveGoboSpin(p, 255)).toBeLessThan(0)
    expect(Math.abs(resolveGoboSpin(p, 255))).toBeGreaterThan(
      Math.abs(resolveGoboSpin(p, 190)),
    )
  })
})

describe('resolvePrismFacets', () => {
  const fusionPrism = setting(opts([['OPEN', 0], ['PRISM', 8]]), 'prism')

  it('is out at the open position and in above it (legacy heuristic)', () => {
    expect(resolvePrismFacets(fusionPrism, 0)).toBe(0)
    expect(resolvePrismFacets(fusionPrism, 7)).toBe(0)
    expect(resolvePrismFacets(fusionPrism, 8)).toBe(PRISM_FACETS)
    expect(resolvePrismFacets(fusionPrism, 255)).toBe(PRISM_FACETS)
  })

  it('is out when the fixture has no prism', () => {
    expect(resolvePrismFacets(undefined, 255)).toBe(0)
  })

  // The annotated MAC 250 wheel: OFF bands carry no facets, engaged bands do.
  const MAC_PRISM = setting(opts([
    ['PRISM_OFF', 0],
    ['ROT_CCW', 20, { prismFacets: 3 }],
    ['NO_ROT', 80, { prismFacets: 3 }],
    ['ROT_CW', 90, { prismFacets: 3 }],
    ['PRISM_OFF_2', 150],
    ['MACRO_1', 216, { prismFacets: 3 }],
  ]), 'prism')

  it('prefers declared facets and treats unannotated bands as prism-out', () => {
    expect(resolvePrismFacets(MAC_PRISM, 0)).toBe(0)
    expect(resolvePrismFacets(MAC_PRISM, 30)).toBe(3)
    expect(resolvePrismFacets(MAC_PRISM, 85)).toBe(3)
    // PRISM_OFF_2 has no facets on an annotated wheel: prism out, no heuristic.
    expect(resolvePrismFacets(MAC_PRISM, 160)).toBe(0)
    expect(resolvePrismFacets(MAC_PRISM, 220)).toBe(3)
  })

  it('clamps declared facets to the renderer lobe budget', () => {
    const exotic = setting(opts([['MEGA', 0, { prismFacets: 12 }]]), 'prism')
    expect(resolvePrismFacets(exotic, 0)).toBe(MAX_PRISM_LOBES)
  })
})

describe('resolvePrismSpin', () => {
  // The Robe curve: 0 stop, 1-127 CW fast→slow, 128-129 stop, 130-255 CCW slow→fast.
  it('pins the Robe slider curve', () => {
    expect(prismSpinFromSlider(0)).toBe(0)
    expect(prismSpinFromSlider(1)).toBeGreaterThan(0)
    expect(prismSpinFromSlider(1)).toBeGreaterThan(prismSpinFromSlider(64))
    expect(prismSpinFromSlider(127)).toBeCloseTo(0, 5)
    expect(prismSpinFromSlider(128)).toBe(0)
    expect(prismSpinFromSlider(129)).toBe(0)
    expect(prismSpinFromSlider(130)).toBeLessThanOrEqual(0)
    expect(prismSpinFromSlider(255)).toBeLessThan(prismSpinFromSlider(180))
  })

  it('routes a slider-backed dedicated channel through the Robe curve', () => {
    const rot = slider({ category: 'prism_rotation' })
    expect(resolvePrismSpin(rot, 1, undefined, 0)).toBe(prismSpinFromSlider(1))
    expect(resolvePrismSpin(rot, 200, undefined, 0)).toBeLessThan(0)
  })

  it('decodes a setting-backed dedicated channel by band name', () => {
    const rot = setting(opts([
      ['STOP', 0],
      ['FORWARD_SLOW', 10],
      ['REVERSE_FAST', 130],
    ]), 'prism_rotation')
    expect(resolvePrismSpin(rot, 0, undefined, 0)).toBe(0)
    expect(resolvePrismSpin(rot, 10, undefined, 0)).toBeGreaterThan(0)
    expect(resolvePrismSpin(rot, 200, undefined, 0)).toBeLessThan(0)
  })

  const MAC_PRISM = setting(opts([
    ['PRISM_OFF', 0],
    ['ROT_CCW', 20],
    ['NO_ROT', 80],
    ['ROT_CW', 90],
    ['PRISM_OFF_2', 150],
    ['MACRO_1', 216],
  ]), 'prism')

  it('falls back to rotation bands folded into the prism wheel', () => {
    expect(resolvePrismSpin(undefined, 0, MAC_PRISM, 30)).toBeLessThan(0) // ROT_CCW
    expect(resolvePrismSpin(undefined, 0, MAC_PRISM, 85)).toBe(0) // NO_ROT
    expect(resolvePrismSpin(undefined, 0, MAC_PRISM, 100)).toBeGreaterThan(0) // ROT_CW
    expect(resolvePrismSpin(undefined, 0, MAC_PRISM, 0)).toBe(0) // PRISM_OFF
    // Macros rotate on the real fixture but each is a different canned
    // program; a static split beats a wrong spin.
    expect(resolvePrismSpin(undefined, 0, MAC_PRISM, 220)).toBe(0)
  })

  it('prefers the dedicated channel over folded bands', () => {
    const rot = slider({ category: 'prism_rotation' })
    // Dedicated says stop; folded bands would say CW.
    expect(resolvePrismSpin(rot, 0, MAC_PRISM, 100)).toBe(0)
  })

  it('is zero with neither channel', () => {
    expect(resolvePrismSpin(undefined, 0, undefined, 0)).toBe(0)
  })
})

describe('resolveFocusParam', () => {
  it('normalises across the slider range', () => {
    expect(resolveFocusParam(slider(), 0)).toBe(0)
    expect(resolveFocusParam(slider(), 255)).toBe(1)
    expect(resolveFocusParam(slider(), 128)).toBeCloseTo(0.502, 2)
  })

  it('is null without a focus channel, so the type default stands', () => {
    expect(resolveFocusParam(undefined, 128)).toBeNull()
    expect(resolveFocusParam(slider({ min: 5, max: 5 }), 5)).toBeNull()
  })
})

describe('resolveFocusDistance', () => {
  const LEN = 8

  it('is the always-sharp sentinel without a focus channel', () => {
    expect(resolveFocusDistance(null, LEN)).toBe(FOCUS_ALWAYS_SHARP)
    expect(FOCUS_ALWAYS_SHARP).toBeLessThan(0)
  })

  it('pins the quadratic curve endpoints and midpoint', () => {
    expect(resolveFocusDistance(0, LEN)).toBeCloseTo(LEN * FOCUS_NEAR_FRAC, 9)
    expect(resolveFocusDistance(1, LEN)).toBeCloseTo(LEN, 9)
    expect(resolveFocusDistance(0.5, LEN)).toBeCloseTo(
      LEN * (FOCUS_NEAR_FRAC + (1 - FOCUS_NEAR_FRAC) * 0.25),
      9,
    )
  })

  it('grows monotonically and clamps out-of-range params', () => {
    let prev = -Infinity
    for (let p = 0; p <= 1; p += 0.1) {
      const d = resolveFocusDistance(p, LEN)
      expect(d).toBeGreaterThan(prev)
      prev = d
    }
    expect(resolveFocusDistance(-0.5, LEN)).toBe(resolveFocusDistance(0, LEN))
    expect(resolveFocusDistance(1.5, LEN)).toBe(resolveFocusDistance(1, LEN))
  })
})

describe('resolveMacroIndex', () => {
  const ledMacro = setting(
    opts([
      ['NO_FUNCTION', 0],
      ['LED_MACRO_1', 8],
      ['LED_MACRO_2', 48],
      ['SOUND_ACTIVE', 248],
    ]),
    'led_macro',
  )

  it('is 0 for the no-function band', () => {
    expect(resolveMacroIndex(ledMacro, 0)).toBe(0)
    expect(resolveMacroIndex(ledMacro, 7)).toBe(0)
    expect(resolveMacroIndex(undefined, 200)).toBe(0)
  })

  it('numbers the running programs', () => {
    expect(resolveMacroIndex(ledMacro, 8)).toBe(1)
    expect(resolveMacroIndex(ledMacro, 48)).toBe(2)
    // No audio input exists, so sound-active is treated as just another program
    // rather than silently doing nothing.
    expect(resolveMacroIndex(ledMacro, 248)).toBeGreaterThan(0)
  })
})

describe('macro evaluators', () => {
  it('are inert at index 0', () => {
    const m = evalMovementMacro(0, 3.2, { panDeg: 9, tiltDeg: 9 })
    expect(m).toEqual({ panDeg: 0, tiltDeg: 0 })
    const c = evalLedMacro(0, 3.2, { hueShift: 9, intensityScale: 9 })
    expect(c).toEqual({ hueShift: 0, intensityScale: 1 })
  })

  it('are deterministic in (index, t)', () => {
    const a = evalMovementMacro(2, 1.75, { panDeg: 0, tiltDeg: 0 })
    const b = evalMovementMacro(2, 1.75, { panDeg: 0, tiltDeg: 0 })
    expect(a).toEqual(b)
  })

  it('close over their period', () => {
    for (const idx of [1, 2, 3]) {
      const at0 = evalMovementMacro(idx, 0, { panDeg: 0, tiltDeg: 0 })
      const at6 = evalMovementMacro(idx, 6, { panDeg: 0, tiltDeg: 0 })
      expect(at6.panDeg).toBeCloseTo(at0.panDeg, 9)
      expect(at6.tiltDeg).toBeCloseTo(at0.tiltDeg, 9)
    }
  })

  it('keeps macro intensity within a sane range', () => {
    for (let t = 0; t < 6; t += 0.25) {
      for (const idx of [1, 2, 3]) {
        const c = evalLedMacro(idx, t, { hueShift: 0, intensityScale: 0 })
        expect(c.intensityScale).toBeGreaterThan(0)
        expect(c.intensityScale).toBeLessThanOrEqual(1)
        expect(c.hueShift).toBeGreaterThanOrEqual(0)
        expect(c.hueShift).toBeLessThan(1)
      }
    }
  })

  it('writes into the caller-provided struct', () => {
    const out = { panDeg: 0, tiltDeg: 0 }
    expect(evalMovementMacro(1, 1, out)).toBe(out)
  })
})

describe('computeBeamGeom', () => {
  const LEN = 8
  const SLACK = (3 * Math.PI) / 180

  it('matches the closed form for a known angle', () => {
    const g = computeBeamGeom(30, LEN, SLACK, makeBeamGeom())
    expect(g.beamRadius).toBeCloseTo(LEN * Math.tan(Math.PI / 12), 9)
    expect(g.cosHalfBeam).toBeCloseTo(Math.cos(Math.PI / 12), 9)
    expect(g.floorSide).toBeCloseTo(2 * LEN * Math.sin(Math.PI / 12 + SLACK), 9)
  })

  it('widens monotonically with the beam angle', () => {
    let prev = -Infinity
    for (const deg of [5, 15, 30, 60, 90]) {
      const g = computeBeamGeom(deg, LEN, SLACK, makeBeamGeom())
      expect(g.beamRadius).toBeGreaterThan(prev)
      prev = g.beamRadius
    }
  })

  it('reuses the struct so the frame loop allocates nothing', () => {
    const g = makeBeamGeom()
    expect(computeBeamGeom(30, LEN, SLACK, g)).toBe(g)
    expect(g.beamDeg).toBe(30)
  })

  it('starts dirty so the first frame always computes', () => {
    expect(makeBeamGeom().beamDeg).toBeNaN()
  })
})
