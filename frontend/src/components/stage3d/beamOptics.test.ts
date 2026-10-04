import { describe, it, expect } from 'vitest'
import { MAX_PRISM_LOBES } from './emitterLayout'
// The desk's own test vector, read straight from the backend's tree — the one FixtureFocusTest
// pins the desk's inverse (*Focus here*, aim_fixtures' focus, Locate) against.
import focusInverse from '../../../../src/test/resources/stage/focusInverse.fixture.json'
import {
  FOCUS_ALWAYS_SHARP,
  FOCUS_NEAR_FRAC,
  GOBO_SLOT_COUNT,
  PRISM_FACETS,
  combineFinePair,
  computeBeamGeom,
  evalLedMacro,
  evalMovementMacro,
  makeBeamGeom,
  makeBladeStates,
  makeGoboRotation,
  prismSpinFromSlider,
  resolveBladeAngleDeg,
  resolveBladeDepth,
  resolveDmxBlades,
  resolveDeclaredFocusDistance,
  resolveFocusDistance,
  resolveFocusParam,
  resolveGoboRotation,
  resolveGoboRotationMode,
  resolveGoboSlot,
  resolveGoboSpin,
  resolveMacroIndex,
  resolvePrismFacets,
  resolvePrismSpin,
  resolveZoomDeg,
  settingBand,
} from './beamOptics'
import { goboLayerFor } from './goboPatterns'
import { beamMask, packBlade, packBlades, unpackBlade } from './beamMask'
import { revolutionShutterProps } from '../../test/fixtureFactories'
import type {
  SettingOption,
  SettingPropertyDescriptor,
  ShutterProperties,
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

  // A loadable wheel (the Revolution's module wheel, fixture optics session 3): its options carry
  // `loadable`, and a slot's content is the unit's fitted media over the type's empty stock.
  it('never index-guesses a loadable wheel: an empty or dichroic slot is open, a fitted gobo draws', () => {
    const stock = opts([
      ['OPEN', 0, { loadable: false }],
      ['SLOT_1', 14, { loadable: true }],
      ['SLOT_2', 27, { loadable: true }],
    ])
    expect(resolveGoboSlot(setting(stock), 14)).toBe(0)
    expect(resolveGoboSlot(setting(stock), 27)).toBe(0)
    const fitted = opts([
      ['OPEN', 0, { loadable: false }],
      ['SLOT_1', 14, { loadable: true, gobo: 'breakup' }],
      ['SLOT_2', 27, { loadable: true, colourPreview: '#ee5b5e' }],
    ])
    expect(resolveGoboSlot(setting(fitted), 14)).toBe(goboLayerFor('breakup'))
    expect(resolveGoboSlot(setting(fitted), 27)).toBe(0)
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

describe("the desk's focus inverse, against the shared vector", () => {
  // `src/test/resources/stage/focusInverse.fixture.json`: for each declared range, the DMX level the
  // desk solves a distance to (`show/FixtureFocus.kt`). The view must draw that level sharp at that
  // distance, or *Focus here* lands a focus the Stage view shows soft. Change the rule, the vector
  // and both pins in one commit.
  for (const c of focusInverse.cases) {
    const prop = slider({ focusNearM: c.focusNearM, focusFarM: c.focusFarM, inverted: c.inverted, min: c.min, max: c.max })
    const at = (level: number) => resolveDeclaredFocusDistance(prop, resolveFocusParam(prop, level))!

    it(`draws each solved level at its distance — ${c.name}`, () => {
      for (const p of c.points) {
        // The exact level is the inverse's answer: the forward direction takes it back to the distance.
        expect(at(p.exactLevel)).toBeCloseTo(p.distanceM, 6)
        // The byte the desk writes rounds it, and lands within half a DMX step's distance of it.
        const lower = at(Math.max(c.min, p.level - 0.5))
        const upper = at(Math.min(c.max, p.level + 0.5))
        expect(p.distanceM).toBeGreaterThanOrEqual(Math.min(lower, upper) - 1e-9)
        expect(p.distanceM).toBeLessThanOrEqual(Math.max(lower, upper) + 1e-9)
      }
    })

    it(`puts the range's ends at the slider's ends, and Locate's middle at its middle distance — ${c.name}`, () => {
      expect(at(c.inverted ? c.max : c.min)).toBeCloseTo(c.focusNearM, 9)
      expect(at(c.inverted ? c.min : c.max)).toBeCloseTo(c.focusFarM, 9)
      for (const d of c.outside) expect(d < c.focusNearM || d > c.focusFarM).toBe(true)
      const lower = at(Math.max(c.min, c.middleLevel - 0.5))
      const upper = at(Math.min(c.max, c.middleLevel + 0.5))
      expect(c.middleM).toBeGreaterThanOrEqual(Math.min(lower, upper))
      expect(c.middleM).toBeLessThanOrEqual(Math.max(lower, upper))
    })
  }
})

describe('resolveDeclaredFocusDistance', () => {
  const declared = (over: Partial<SliderPropertyDescriptor> = {}) =>
    slider({ focusNearM: 2, focusFarM: 40, ...over })

  it('runs near → far across DMX min → max', () => {
    expect(resolveDeclaredFocusDistance(declared(), 0)).toBeCloseTo(2, 9)
    expect(resolveDeclaredFocusDistance(declared(), 1)).toBeCloseTo(40, 9)
  })

  it('is linear in 1 / distance, as a lens is', () => {
    // Half the travel is half way between 1/2 and 1/40, not half way between 2 and 40.
    expect(resolveDeclaredFocusDistance(declared(), 0.5)).toBeCloseTo(1 / ((1 / 2 + 1 / 40) / 2), 9)
    expect(resolveDeclaredFocusDistance(declared(), 0.5)).toBeLessThan(4)
    let prevInv = Infinity
    let prevStep = -Infinity
    for (let i = 0; i <= 10; i++) {
      const inv = 1 / resolveDeclaredFocusDistance(declared(), i / 10)!
      if (i > 0) {
        const step = prevInv - inv
        if (i > 1) expect(step).toBeCloseTo(prevStep, 9)
        prevStep = step
      }
      prevInv = inv
    }
  })

  it('runs far → near when inverted (a MAC 250: DMX 0 is infinity)', () => {
    const mac = declared({ inverted: true })
    expect(resolveDeclaredFocusDistance(mac, 0)).toBeCloseTo(40, 9)
    expect(resolveDeclaredFocusDistance(mac, 1)).toBeCloseTo(2, 9)
    expect(resolveDeclaredFocusDistance(mac, 0.3)).toBeCloseTo(resolveDeclaredFocusDistance(declared(), 0.7)!, 9)
  })

  it('clamps out-of-range params', () => {
    expect(resolveDeclaredFocusDistance(declared(), -1)).toBe(resolveDeclaredFocusDistance(declared(), 0))
    expect(resolveDeclaredFocusDistance(declared(), 2)).toBe(resolveDeclaredFocusDistance(declared(), 1))
  })

  it('is null where nothing is declared, so the focus racks over the throw', () => {
    expect(resolveDeclaredFocusDistance(slider(), 0.5)).toBeNull()
    expect(resolveDeclaredFocusDistance(slider({ focusNearM: 2 }), 0.5)).toBeNull()
    expect(resolveDeclaredFocusDistance(slider({ focusFarM: 40 }), 0.5)).toBeNull()
    expect(resolveDeclaredFocusDistance(undefined, 0.5)).toBeNull()
    expect(resolveDeclaredFocusDistance(declared(), null)).toBeNull()
  })

  it('is null for a range no lens has', () => {
    expect(resolveDeclaredFocusDistance(declared({ focusNearM: 0 }), 0.5)).toBeNull()
    expect(resolveDeclaredFocusDistance(declared({ focusNearM: 40, focusFarM: 2 }), 0.5)).toBeNull()
    expect(resolveDeclaredFocusDistance(declared({ focusFarM: 2 }), 0.5)).toBeNull()
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
    expect(g.cosCull).toBeCloseTo(Math.cos(Math.PI / 12 + SLACK), 9)
    expect(g.sinCull).toBeCloseTo(Math.sin(Math.PI / 12 + SLACK), 9)
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

// The ETC Source Four Revolution's front wheel, as its descriptor carries it: the function
// channel's bands (manual p24) and the 16-bit index/rotation's coarse half.
const REV_WHEEL_FUNCTION = setting(
  opts([
    ['INDEX', 0],
    ['ROTATE_FWD', 14],
    ['ROTATE_REV', 27],
    ['RESERVED', 40],
  ]),
  'gobo_rotation_mode',
)
const REV_WHEEL_ROT = slider({
  name: 'fbWheelRot',
  category: 'gobo_rotation',
  rpmMax: 30,
  indexDegMax: 360,
})

describe('fineOf pairs', () => {
  it('combines a coarse and fine byte into one value in coarse units', () => {
    // The 16-bit value over 65535, in coarse units: full scale is exactly 255, and half of the
    // 16-bit range is half the coarse span.
    expect(combineFinePair(0, 0)).toBe(0)
    expect(combineFinePair(255, 255)).toBe(255)
    expect(combineFinePair(128, 0) / 255).toBeCloseTo(32768 / 65535, 12)
    expect(combineFinePair(128, 128)).toBe(128)
    expect(combineFinePair(64, 1)).toBeGreaterThan(combineFinePair(64, 0))
    // A type with no fine channel reads the coarse byte as it always did.
    expect(combineFinePair(77, null)).toBe(77)
  })
})

describe('resolveGoboRotation', () => {
  const out = makeGoboRotation()

  it('reads the mode from the function channel\'s band names', () => {
    expect(resolveGoboRotationMode(REV_WHEEL_FUNCTION, 0)).toBe('INDEX')
    expect(resolveGoboRotationMode(REV_WHEEL_FUNCTION, 13)).toBe('INDEX')
    expect(resolveGoboRotationMode(REV_WHEEL_FUNCTION, 14)).toBe('ROTATE_FWD')
    expect(resolveGoboRotationMode(REV_WHEEL_FUNCTION, 26)).toBe('ROTATE_FWD')
    expect(resolveGoboRotationMode(REV_WHEEL_FUNCTION, 27)).toBe('ROTATE_REV')
    expect(resolveGoboRotationMode(REV_WHEEL_FUNCTION, 39)).toBe('ROTATE_REV')
    expect(resolveGoboRotationMode(REV_WHEEL_FUNCTION, 40)).toBeNull()
    expect(resolveGoboRotationMode(undefined, 0)).toBeNull()
  })

  it('indexes to an angle over indexDegMax, the fine byte included', () => {
    resolveGoboRotation(REV_WHEEL_ROT, combineFinePair(0, 0), REV_WHEEL_FUNCTION, 5, out)
    expect(out.indexRad).toBe(0)
    expect(out.spinRevPerSec).toBe(0)

    // Half way through the 16-bit range is half of 360°.
    resolveGoboRotation(REV_WHEEL_ROT, combineFinePair(128, 0), REV_WHEEL_FUNCTION, 5, out)
    expect(out.indexRad).toBeCloseTo(((32768 / 65535) * 360 * Math.PI) / 180, 12)

    // The fine byte moves the angle between two coarse steps.
    resolveGoboRotation(REV_WHEEL_ROT, combineFinePair(64, 0), REV_WHEEL_FUNCTION, 5, out)
    const coarseOnly = out.indexRad!
    resolveGoboRotation(REV_WHEEL_ROT, combineFinePair(64, 128), REV_WHEEL_FUNCTION, 5, out)
    expect(out.indexRad!).toBeGreaterThan(coarseOnly)

    resolveGoboRotation(REV_WHEEL_ROT, combineFinePair(255, 255), REV_WHEEL_FUNCTION, 5, out)
    expect(out.indexRad).toBeCloseTo(2 * Math.PI, 12)
  })

  it('spins forward in ROTATE_FWD and backward in ROTATE_REV, up to rpmMax', () => {
    resolveGoboRotation(REV_WHEEL_ROT, combineFinePair(255, 255), REV_WHEEL_FUNCTION, 20, out)
    expect(out.indexRad).toBeNull()
    expect(out.spinRevPerSec).toBeCloseTo(30 / 60, 6)

    resolveGoboRotation(REV_WHEEL_ROT, combineFinePair(255, 255), REV_WHEEL_FUNCTION, 30, out)
    expect(out.indexRad).toBeNull()
    expect(out.spinRevPerSec).toBeCloseTo(-30 / 60, 6)

    // Linear in DMX: half the range is half the speed.
    resolveGoboRotation(REV_WHEEL_ROT, combineFinePair(128, 0), REV_WHEEL_FUNCTION, 30, out)
    expect(out.spinRevPerSec).toBeCloseTo(-(32768 / 65535) * 0.5, 12)

    // DMX 0 in a rotate band is stopped.
    resolveGoboRotation(REV_WHEEL_ROT, 0, REV_WHEEL_FUNCTION, 14, out)
    expect(out.spinRevPerSec).toBe(0)
    expect(out.indexRad).toBeNull()
  })

  it('holds the wheel still in a reserved band', () => {
    resolveGoboRotation(REV_WHEEL_ROT, 200, REV_WHEEL_FUNCTION, 100, out)
    expect(out.indexRad).toBeNull()
    expect(out.spinRevPerSec).toBe(0)
  })

  it('without a mode channel, decodes as the spin channel always has', () => {
    for (const level of [0, 64, 127, 128, 200, 255]) {
      resolveGoboRotation(REV_WHEEL_ROT, level, undefined, 0, out)
      expect(out.indexRad).toBeNull()
      expect(out.spinRevPerSec).toBe(resolveGoboSpin(REV_WHEEL_ROT, level))
      // A folded fine byte never moves the coarse byte the bands are read from.
      for (const fine of [0, 255]) {
        resolveGoboRotation(REV_WHEEL_ROT, combineFinePair(level, fine), undefined, 0, out)
        expect(out.spinRevPerSec).toBe(resolveGoboSpin(REV_WHEEL_ROT, level))
      }
    }
  })
})

describe('resolveZoomDeg', () => {
  it('reads the Source Four Revolution\'s zoom wide at DMX 0 and narrow at 255', () => {
    const zoom = slider({ name: 'zoom', category: 'zoom', degMin: 35, degMax: 15 })
    expect(resolveZoomDeg(zoom, 0)).toBe(35)
    expect(resolveZoomDeg(zoom, 255)).toBe(15)
    expect(resolveZoomDeg(zoom, 127.5)).toBeCloseTo(25, 6)
  })

  it('answers null for a zoom that declares no angles, and for no zoom', () => {
    expect(resolveZoomDeg(slider({ category: 'zoom' }), 100)).toBeNull()
    expect(resolveZoomDeg(undefined, 100)).toBeNull()
  })
})

describe('framing shutters from DMX', () => {
  // The Revolution's, as its descriptors carry them, in the wire's order (top, bottom, left, right).
  const props = revolutionShutterProps()
  const shutters: ShutterProperties = {
    depth: props.filter((p) => p.category === 'shutter'),
    rotation: props.filter((p) => p.category === 'shutter_rotation'),
  }
  const [frame1Pos, frame1Rot] = props

  it('takes a blade in over 0..depthMax, linearly, out at DMX min', () => {
    expect(resolveBladeDepth(frame1Pos, 0)).toBe(0)
    expect(resolveBladeDepth(frame1Pos, 255)).toBe(0.5)
    expect(resolveBladeDepth(frame1Pos, 51)).toBeCloseTo(0.1, 9)
    // Inverted: DMX max is out, DMX min in to depthMax.
    const inverted = { ...frame1Pos, inverted: true }
    expect(resolveBladeDepth(inverted, 255)).toBe(0)
    expect(resolveBladeDepth(inverted, 0)).toBe(0.5)
    // Without a declared depth the blade is out, whatever the channel says.
    expect(resolveBladeDepth({ ...frame1Pos, depthMax: undefined }, 255)).toBe(0)
    expect(resolveBladeDepth(undefined, 255)).toBe(0)
  })

  it('turns a blade over degMin..degMax, square between them', () => {
    expect(resolveBladeAngleDeg(frame1Rot, 0)).toBe(-45)
    expect(resolveBladeAngleDeg(frame1Rot, 255)).toBe(45)
    expect(resolveBladeAngleDeg(frame1Rot, 127.5)).toBeCloseTo(0, 9)
    // 128, where Locate squares it, is within one 1.5° step of square.
    expect(Math.abs(resolveBladeAngleDeg(frame1Rot, 128))).toBeLessThan(0.75)
    const inverted = { ...frame1Rot, inverted: true }
    expect(resolveBladeAngleDeg(inverted, 0)).toBe(45)
    expect(resolveBladeAngleDeg(inverted, 255)).toBe(-45)
    expect(resolveBladeAngleDeg({ ...frame1Rot, degMin: undefined }, 255)).toBe(0)
    expect(resolveBladeAngleDeg(undefined, 255)).toBe(0)
  })

  it('reads each blade from its own two channels, in the wire order', () => {
    const out = makeBladeStates()
    resolveDmxBlades(shutters, [0, 255, 0, 51], [127.5, 0, 255, 127.5], out)
    expect(out[0]).toEqual({ depth: 0, angleDeg: expect.closeTo(0, 9) })
    expect(out[1]).toEqual({ depth: 0.5, angleDeg: -45 })
    expect(out[2]).toEqual({ depth: 0, angleDeg: 45 })
    expect(out[3].depth).toBeCloseTo(0.1, 9)
    // A blade with no channel of either kind is out and square.
    resolveDmxBlades({ depth: [undefined, undefined, undefined, undefined], rotation: shutters.rotation }, [255, 255, 255, 255], [0, 0, 0, 0], out)
    expect(out.every((b) => b.depth === 0)).toBe(true)
  })

  it('cuts to the centre with a blade at DMX max', () => {
    const blades = resolveDmxBlades(shutters, [255, 0, 0, 0], [127.5, 127.5, 127.5, 127.5], makeBladeStates())
    const [a, b] = packBlades(blades)
    // The top blade at depth 0.5 packs to exactly half way, so its edge is the field's centre: just
    // above it is dark, just below it lit, and the bottom of the field untouched.
    expect(unpackBlade(packBlade(0.5, 0))?.depth).toBe(0.5)
    expect(beamMask(0, 0.01, 0, 1, 0, a, b)).toBe(0)
    expect(beamMask(0, -0.02, 0, 1, 0, a, b)).toBeGreaterThan(0.99)
    expect(beamMask(0, -0.9, 0, 1, 0, a, b)).toBeGreaterThan(0.99)
  })
})
