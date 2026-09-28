import { describe, expect, it } from 'vitest'
import { controlLabel, describeTarget, matchesBindingTarget } from './targetUtils'
import type { ControlSurfaceType } from '@/store/surfaces'

describe('describeTarget', () => {
  it('describes the existing target kinds', () => {
    expect(describeTarget({ type: 'fixtureProperty', fixtureKey: 'hex-1', propertyName: 'dimmer' }))
      .toBe('hex-1.dimmer')
    expect(describeTarget({ type: 'blackout' })).toBe('Blackout')
    expect(describeTarget({ type: 'setBank', deviceTypeKey: 'xtc', bank: 'layer-b' }))
      .toBe('Bank layer-b (xtc)')
  })

  it('describes a tempo binding with its configured range', () => {
    // The range is the part an operator needs to see: two bindings on the same master with
    // different windows behave very differently under the same finger.
    expect(
      describeTarget({
        type: 'speedMasterBpm',
        masterUuid: 'aaaa-2',
        minBpm: 90,
        maxBpm: 150,
      }),
    ).toBe('Speed master BPM · 90–150')

    expect(describeTarget({ type: 'speedMasterTap', masterUuid: 'aaaa-2' }))
      .toBe('Speed master tap')
  })

  it('marks an unkeyed tempo binding as master 1', () => {
    // null is master 1 everywhere in this feature; spelling it out beats an unexplained gap.
    expect(
      describeTarget({ type: 'speedMasterBpm', masterUuid: null, minBpm: 60, maxBpm: 180 }),
    ).toBe('Speed master BPM · 60–180 · M1')
    expect(describeTarget({ type: 'speedMasterTap', masterUuid: null }))
      .toBe('Speed master tap · M1')
  })

  it('unwraps a flash target recursively', () => {
    expect(
      describeTarget({
        type: 'flash',
        target: { type: 'groupProperty', groupName: 'front', propertyName: 'dimmer' },
      }),
    ).toBe('Flash front.dimmer')
  })
})

describe('describeTarget — the busk-further plan’s five (D14)', () => {
  it('names the window by its registry name and the focus, and the sub-selection in the chip’s words', () => {
    expect(describeTarget({ type: 'buskFocusSet', windowName: 'Screen 2', focus: 'pads' })).toBe('Busk focus · Pads · Screen 2')
    expect(describeTarget({ type: 'buskSheetToggle', windowName: 'Screen 2' })).toBe('Busk sheet · Screen 2')
    expect(describeTarget({ type: 'selectionNext' })).toBe('Selection · next')
    expect(describeTarget({ type: 'selectionPrev' })).toBe('Selection · prev')
    expect(describeTarget({ type: 'selectionCells', mode: 'MASTERS' })).toBe('Cells · Masters only')
    expect(describeTarget({ type: 'selectionCells', mode: 'FIRST_HALF' })).toBe('Cells · 1st half')
  })
})

describe('describeTarget — the selection-relative arms', () => {
  it('names the selection rather than a target', () => {
    expect(describeTarget({ type: 'selectionProperty', propertyName: 'pan' })).toBe('Sel · pan')
    expect(describeTarget({ type: 'clearSelection' })).toBe('Clear selection')
    expect(describeTarget({ type: 'locateSelection' })).toBe('Locate selection')
  })

  it('distinguishes a toggling select button from a replacing one', () => {
    const target = { type: 'group', key: 'front-wash' } as const
    expect(describeTarget({ type: 'selectTarget', target, mode: 'toggle' }))
      .toBe('Select front-wash')
    expect(describeTarget({ type: 'selectTarget', target, mode: 'replace' }))
      .toBe('Select only front-wash')
  })

  it('spells out a colour axis other than hue on the four property kinds', () => {
    // Hue is the default and carries no field, so a hue binding reads exactly as it always did —
    // whether the field is absent, null, or spelled `'hue'`.
    expect(describeTarget({ type: 'fixtureProperty', fixtureKey: 'hex-1', propertyName: 'rgbColour' }))
      .toBe('hex-1.rgbColour')
    expect(describeTarget({ type: 'fixtureProperty', fixtureKey: 'hex-1', propertyName: 'rgbColour', colourAxis: null }))
      .toBe('hex-1.rgbColour')
    expect(describeTarget({ type: 'fixtureProperty', fixtureKey: 'hex-1', propertyName: 'rgbColour', colourAxis: 'hue' }))
      .toBe('hex-1.rgbColour')
    expect(describeTarget({ type: 'fixtureProperty', fixtureKey: 'hex-1', propertyName: 'rgbColour', colourAxis: 'saturation' }))
      .toBe('hex-1.rgbColour · sat')
    expect(describeTarget({ type: 'groupProperty', groupName: 'front', propertyName: 'rgbColour', colourAxis: 'hueFine' }))
      .toBe('front.rgbColour · hue fine')
    expect(describeTarget({ type: 'selectionProperty', propertyName: 'rgbColour', colourAxis: 'brightness' }))
      .toBe('Sel · rgbColour · bright')
    expect(describeTarget({ type: 'encoderBankSet', propertyName: 'rgbColour', colourAxis: 'saturation' }))
      .toBe('Encoder bank · rgbColour · sat')
    expect(
      describeTarget({
        type: 'flash',
        target: { type: 'groupProperty', groupName: 'front', propertyName: 'rgbColour', colourAxis: 'saturation' },
      }),
    ).toBe('Flash front.rgbColour · sat')
  })

  it('describes a strip row and an encoder bank button', () => {
    expect(
      describeTarget({ type: 'strip', target: { type: 'group', key: 'front-wash' } }),
    ).toBe('Strip · front-wash')
    expect(describeTarget({ type: 'encoderBankSet', propertyName: 'colour' }))
      .toBe('Encoder bank · colour')
  })

  it('names the discriminator of a row it cannot decode', () => {
    expect(describeTarget({ type: 'unknown', targetType: 'fromTheFuture', rawPayload: '{}' }))
      .toBe('Unknown target (fromTheFuture)')
  })
})

describe('matchesBindingTarget — a strip-bound target', () => {
  const strip = {
    type: 'strip',
    target: { type: 'group', key: 'front-wash' },
  } as const

  // The regression this guards: before strips, a group's badge matched only `groupProperty`, so a
  // group dropped on a strip — the shape the whole view is built around — reported no binding at
  // all on the groups page.
  it('reports the dimmer, which its fader and flash always drive', () => {
    expect(
      matchesBindingTarget(strip, {
        type: 'groupProperty',
        groupName: 'front-wash',
        propertyName: 'dimmer',
      }),
    ).toBe(true)
  })

  it('does not claim the encoder’s property, which moves with the bank', () => {
    expect(
      matchesBindingTarget(strip, {
        type: 'groupProperty',
        groupName: 'front-wash',
        propertyName: 'colour',
      }),
    ).toBe(false)
  })

  it('does not match another group, or a fixture of the same key', () => {
    expect(
      matchesBindingTarget(strip, {
        type: 'groupProperty',
        groupName: 'movers',
        propertyName: 'dimmer',
      }),
    ).toBe(false)
    expect(
      matchesBindingTarget(strip, {
        type: 'fixtureProperty',
        fixtureKey: 'front-wash',
        propertyName: 'dimmer',
      }),
    ).toBe(false)
  })
})

describe('matchesBindingTarget — the colour axis', () => {
  it('ignores the axis: a fine-hue binding still drives that property', () => {
    // The fixtures-page badge asks "is this property bound", not how.
    expect(
      matchesBindingTarget(
        { type: 'fixtureProperty', fixtureKey: 'hex-1', propertyName: 'rgbColour', colourAxis: 'hueFine' },
        { type: 'fixtureProperty', fixtureKey: 'hex-1', propertyName: 'rgbColour' },
      ),
    ).toBe(true)
    expect(
      matchesBindingTarget(
        { type: 'groupProperty', groupName: 'front-wash', propertyName: 'rgbColour', colourAxis: 'saturation' },
        { type: 'groupProperty', groupName: 'front-wash', propertyName: 'rgbColour' },
      ),
    ).toBe(true)
  })
})

describe('controlLabel', () => {
  const profile = {
    typeKey: 'xtc',
    vendor: null,
    product: null,
    portPattern: null,
    className: 'X',
    banks: [],
    layout: null,
    controls: [
      {
        type: 'fader',
        controlId: 'fader-1',
        label: 'Fader 1',
        cc: 1,
        channel: 1,
        hasMotor: true,
        motorCc: null,
        touchNote: null,
        touchCc: null,
        resolution: 'SEVEN_BIT',
      },
    ],
    strips: [{ id: 'strip-1', fader: 'fader-1', select: 'btn-25', encoder: 'enc-1', flash: 'btn-1' }],
  } satisfies ControlSurfaceType

  it('names a control by its own label', () => {
    expect(controlLabel(profile, 'fader-1')).toBe('Fader 1')
  })

  // A strip id shares the `controlId` column with control ids but is not in `controls`, so
  // without the strip arm every strip-bound badge read its raw id.
  it('names a strip by the fader the operator can put a hand on', () => {
    expect(controlLabel(profile, 'strip-1')).toBe('Strip · Fader 1')
  })

  it('falls back to the raw id for anything it does not know', () => {
    expect(controlLabel(profile, 'btn-99')).toBe('btn-99')
  })
})
