// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { chan, colourProp, element, makeFixture, makePixelBar, settingProp } from '../test/fixtureFactories'
import { hasColourWheel, reachesColourWheels, wheelChaseParameters, wheelStartOf, wheelStartParameters } from './wheelChase'

const WHEEL = settingProp('colourWheel', 'colour', chan(5), [
  { name: 'OPEN', level: 0, displayName: 'Open', colourPreview: '#ffffff', continuousLevel: 1 },
  { name: 'RED', level: 156, displayName: 'Red', colourPreview: '#FF0000', continuousLevel: 12 },
  { name: 'YELLOW', level: 160, displayName: 'Yellow', colourPreview: '#ffff00', continuousLevel: 23 },
  { name: 'RED_TOO', level: 164, displayName: 'Red again', colourPreview: '#ff0000' },
  { name: 'SCROLL', level: 200, displayName: 'Scroll', noColour: true },
])
const PLAIN_WHEEL = settingProp('colourWheel', 'colour', chan(5), [
  { name: 'OPEN', level: 0, displayName: 'Open', colourPreview: '#ffffff' },
  { name: 'BLUE', level: 20, displayName: 'Blue', colourPreview: '#0000ff' },
])
const mac = (key: string) => makeFixture(key, [WHEEL], { typeKey: 'mac-250', model: 'MAC 250' })
const ring = (key: string) => makeFixture(key, [PLAIN_WHEEL], { typeKey: 'beam-ring', model: 'LEDBEAMRING' })
const par = (key: string) => makeFixture(key, [colourProp('rgbColour', chan(1), chan(2), chan(3))], { typeKey: 'par' })

describe('wheelStartOf (wheel-colour-chase plan D7)', () => {
  it('starts one model’s wheel on its colours in wheel order, open and no-colour bands left out, each once', () => {
    expect(wheelStartOf([mac('a'), mac('b')])).toEqual({
      model: 'MAC 250',
      typeKey: 'mac-250',
      colours: ['#ff0000', '#ffff00'],
      splits: true,
    })
  })

  it('starts on Split where the wheel splits, and on Cut where it does not', () => {
    expect(wheelChaseParameters(wheelStartOf([mac('a')])!)).toEqual({ colours: '#ff0000,#ffff00', between: 'SPLIT' })
    expect(wheelChaseParameters(wheelStartOf([ring('r')])!)).toEqual({ colours: '#0000ff', between: 'CUT' })
  })

  it('offers no start over two models, or where a head mixes its colour', () => {
    expect(wheelStartOf([mac('a'), ring('r')])).toBeNull()
    expect(wheelStartOf([mac('a'), par('p')])).toBeNull()
    expect(wheelStartOf([par('p')])).toBeNull()
  })

  it('reads a head whose colour is on its elements as one that mixes, not as colourless', () => {
    expect(wheelStartOf([mac('a'), makePixelBar('shehds', 2, [], { typeKey: 'shehds' })])).toBeNull()
  })

  it('leaves what a preset names to the preset', () => {
    expect(wheelStartParameters(wheelStartOf([mac('a')])!, { between: 'SPLIT', fade: '0.5' })).toEqual({ colours: '#ff0000,#ffff00' })
  })
})

describe('a stepping colour FX reaches a wheel head (D1)', () => {
  it('finds a wheel head among the targets', () => {
    expect(hasColourWheel([par('p'), ring('r')])).toBe(true)
    expect(hasColourWheel([par('p')])).toBe(false)
    const zoned = makeFixture('z', [], { elements: [element(0, 'z.0', [PLAIN_WHEEL])] })
    expect(hasColourWheel([par('p'), zoned])).toBe(true)
  })

  it('is a colour FX that steps and names rgbColour, never Rainbow Cycle', () => {
    expect(reachesColourWheels({ family: 'colour', stepsFrom: 'colours', compatibleAttributes: ['rgbColour'] })).toBe(true)
    expect(reachesColourWheels({ family: 'colour', compatibleAttributes: ['rgbColour'] })).toBe(false)
  })
})
