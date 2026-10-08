import { describe, expect, it, vi } from 'vitest'

// `store/fixtures` builds the live API on import; the finders read none of it.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { chan, colourProp, positionProp, settingProp, sliderProp } from '@/test/fixtureFactories'
import type { PropertyDescriptor } from '@/store/fixtures'
import { buildSheetRows, sheetRowKeys } from './sheetRows'

const ids = (properties: PropertyDescriptor[]) =>
  buildSheetRows(properties).map((g) => `${g.family}: ${g.rows.map((r) => r.id).join(', ')}`)

describe('buildSheetRows', () => {
  it('groups rows by family in D3 order, whatever order the desk lists them in', () => {
    const spot: PropertyDescriptor[] = [
      settingProp('gobo', 'gobo', chan(9)),
      sliderProp('zoom', 'zoom', chan(8)),
      settingProp('macro', 'led_macro', chan(10)),
      sliderProp('tilt', 'tilt', chan(4), { axis: 'TILT' }),
      settingProp('colourWheel', 'colour', chan(7)),
      sliderProp('pan', 'pan', chan(3), { axis: 'PAN' }),
      sliderProp('strobe', 'strobe', chan(2)),
      sliderProp('dimmer', 'dimmer', chan(1)),
    ]
    expect(ids(spot)).toEqual([
      'INTENSITY: strobe, dimmer',
      'COLOUR: colourWheel',
      'POSITION: position',
      'BEAM: gobo, zoom',
      'CONTROLS: macro',
    ])
  })

  it('folds pan and tilt into one Position row keyed `position` first, and drops the fine halves', () => {
    const groups = buildSheetRows([
      sliderProp('pan', 'pan', chan(1), { axis: 'PAN', degMin: 0, degMax: 540 }),
      sliderProp('panFine', 'pan_fine', chan(2)),
      sliderProp('tilt', 'tilt', chan(3), { axis: 'TILT', degMin: 0, degMax: 270 }),
      sliderProp('tiltFine', 'tilt_fine', chan(4)),
      sliderProp('focusFine', 'focus', chan(6), { fineOf: 'focus' }),
      sliderProp('focus', 'focus', chan(5)),
    ])
    expect(groups.map((g) => g.family)).toEqual(['POSITION', 'BEAM'])
    const position = groups[0].rows[0]
    expect(position.kind).toBe('position')
    expect(position.keys).toEqual(['position', 'pan', 'tilt'])
    expect(position.kind === 'position' && position.degrees).toBe(true)
    expect(groups[1].rows.map((r) => r.id)).toEqual(['focus'])
  })

  it('reads a real position descriptor in bytes', () => {
    const [group] = buildSheetRows([positionProp('position', chan(1), chan(2))])
    const row = group.rows[0]
    expect(row.kind === 'position' && row.degrees).toBe(false)
    expect(row.keys).toEqual(['position'])
  })

  it('reads a position descriptor in degrees where its axes annotate travel', () => {
    const [group] = buildSheetRows([
      positionProp('position', chan(1), chan(3)),
      sliderProp('pan', 'pan', chan(1), { axis: 'PAN', degMin: 0, degMax: 530 }),
      sliderProp('panFine', 'pan_fine', chan(2)),
      sliderProp('tilt', 'tilt', chan(3), { axis: 'TILT', degMin: 0, degMax: 280 }),
      sliderProp('tiltFine', 'tilt_fine', chan(4)),
    ])
    expect(group.rows.map((r) => r.id)).toEqual(['position'])
    const row = group.rows[0]
    expect(row.kind === 'position' && row.degrees).toBe(true)
    expect(row.keys).toEqual(['position', 'pan', 'tilt'])
  })

  it('gives a colour head with no dimmer an Intensity row over its colour, without a badge', () => {
    const groups = buildSheetRows([colourProp('rgbColour', chan(1), chan(2), chan(3))])
    expect(groups.map((g) => g.family)).toEqual(['INTENSITY', 'COLOUR'])
    expect(groups[0].rows[0]).toMatchObject({ kind: 'virtual-dimmer', label: 'Dimmer', keys: ['rgbColour'] })
  })

  it('gives no virtual Dimmer row where an all-heads dimmer drives the colour', () => {
    const groups = buildSheetRows([colourProp('rgbColour', chan(1), chan(2), chan(3))], { dimmerElsewhere: true })
    expect(groups.map((g) => g.family)).toEqual(['COLOUR'])
  })

  it("dims a colour row's swatch by the set's dimmer, else by the fixture's for a head", () => {
    const dimmer = sliderProp('dimmer', 'dimmer', chan(9))
    const own = buildSheetRows([dimmer, colourProp('rgbColour', chan(1), chan(2), chan(3))])
    expect(own.find((g) => g.family === 'COLOUR')?.rows[0]).toMatchObject({ kind: 'colour', dimmer })
    const head = buildSheetRows([colourProp('rgbColour', chan(1), chan(2), chan(3))], { fallbackDimmer: dimmer })
    expect(head.find((g) => g.family === 'COLOUR')?.rows[0]).toMatchObject({ kind: 'colour', dimmer })
  })

  it('leaves triggers and commands out — they are not controls', () => {
    const trigger = { type: 'trigger', name: 'output1', displayName: 'Tube A', category: 'trigger', label: 'A', channel: chan(1), armChannel: chan(2), armName: 'master' } as PropertyDescriptor
    const command = {
      type: 'command',
      name: 'reset',
      displayName: 'Reset',
      category: 'command',
      description: '',
      holdMs: 5000,
      confirm: true,
      channel: chan(3),
      dedicated: true,
    } as unknown as PropertyDescriptor
    expect(buildSheetRows([trigger, command, sliderProp('dimmer', 'dimmer', chan(4))]).flatMap((g) => g.rows.map((r) => r.id))).toEqual([
      'dimmer',
    ])
  })

  it('files a timing channel under Controls', () => {
    expect(ids([sliderProp('colourTime', 'speed', chan(1), { timing: 'COLOUR' })])).toEqual(['CONTROLS: colourTime'])
  })

  it('lists every key the rows read, once', () => {
    const groups = buildSheetRows([colourProp('rgbColour', chan(1), chan(2), chan(3)), sliderProp('uv', 'uv', chan(4))])
    expect(sheetRowKeys(groups)).toEqual(['rgbColour', 'uv'])
  })
})
