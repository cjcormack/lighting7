import { describe, expect, it } from 'vitest'
import gelsJson from '../../../src/main/resources/gels.json'
import type { SettingPropertyDescriptor } from '../store/fixtures'
import { indexGels, type Gel } from './gels'
import {
  filterColour,
  fittedProperties,
  hasFittedMedia,
  loadableSettings,
  mediaEqual,
  mediaFilters,
  mediaOver,
  normaliseMedia,
  OPEN_WHITE,
  type FittedMedia,
} from './fittedMedia'

/**
 * A unit's fitted media (fixture optics plan session 3): a slot resolves to the placement's, else
 * the patch's, else the type's stock — the order `FittedMedia.kt`'s `colourOf` resolves too.
 */
const gels = indexGels(gelsJson as Gel[])

const SCROLLER: SettingPropertyDescriptor = {
  type: 'setting',
  name: 'gelScroller',
  displayName: 'Gel scroller',
  category: 'colour',
  channel: { universe: 1, channelNo: 13 },
  media: 'GEL',
  options: [
    { name: 'OPEN_LEADER', level: 0, displayName: 'Open Leader', colourPreview: '#FFFFFF', loadable: true },
    { name: 'R25_ORANGE_RED', level: 128, displayName: 'R25 Orange Red', colourPreview: '#e85b2b', loadable: true },
    { name: 'L201_FULL_CT_BLUE', level: 165, displayName: 'L201 Full Ct Blue', colourPreview: '#9bbede', loadable: true },
  ],
}
const WHEEL: SettingPropertyDescriptor = {
  type: 'setting',
  name: 'fbWheelPos',
  displayName: 'Front wheel position',
  category: 'gobo',
  channel: { universe: 1, channelNo: 16 },
  media: 'GOBO_OR_GEL',
  options: [
    { name: 'OPEN', level: 0, displayName: 'Open', loadable: false },
    { name: 'SLOT_1', level: 14, displayName: 'Slot 1', loadable: true },
  ],
}
const FRAME: SettingPropertyDescriptor = {
  type: 'setting',
  name: 'mediaFrame',
  displayName: 'Media frame',
  category: 'setting',
  channel: { universe: 1, channelNo: 6 },
  media: 'GEL',
  options: [
    { name: 'OUT', level: 0, displayName: 'Out', loadable: false },
    { name: 'IN', level: 128, displayName: 'In', loadable: true },
  ],
}
const PLAIN: SettingPropertyDescriptor = { ...WHEEL, name: 'fbWheelFunc', media: undefined, options: [] }

const optionOf = (props: SettingPropertyDescriptor[] | undefined, setting: string, option: string) =>
  props?.find((p) => p.name === setting)?.options.find((o) => o.name === option)

describe('per-unit resolution', () => {
  const patch: FittedMedia = {
    slots: { gelScroller: { L201_FULL_CT_BLUE: { gel: 'R26' }, R25_ORANGE_RED: { gel: 'R80' } } },
  }
  const placement: FittedMedia = { slots: { gelScroller: { L201_FULL_CT_BLUE: { gel: 'L106' } } } }

  it('takes the placement\'s slot, else the patch\'s, else the stock', () => {
    const unit = fittedProperties([SCROLLER], mediaOver(placement, patch), gels)
    expect(optionOf(unit, 'gelScroller', 'L201_FULL_CT_BLUE')?.colourPreview).toBe(gels.byCode.get('L106')!.color)
    expect(optionOf(unit, 'gelScroller', 'R25_ORANGE_RED')?.colourPreview).toBe(gels.byCode.get('R80')!.color)
    expect(optionOf(unit, 'gelScroller', 'OPEN_LEADER')?.colourPreview).toBe('#FFFFFF')
  })

  it('leaves the patch\'s own when a placement fits nothing, and the stock when neither does', () => {
    expect(mediaOver(null, patch)).toBe(patch)
    expect(mediaOver({ slots: {} }, null)).toBeNull()
    const stock = [SCROLLER]
    expect(fittedProperties(stock, null, gels)).toBe(stock)
  })

  it('reads an empty slot as open, a gobo as a pattern with no colour, and an unknown gel as the stock', () => {
    const media: FittedMedia = {
      slots: {
        gelScroller: { L201_FULL_CT_BLUE: {}, R25_ORANGE_RED: { gel: 'R-NEWER' } },
        fbWheelPos: { SLOT_1: { gobo: 'breakup' } },
      },
    }
    const unit = fittedProperties([SCROLLER, WHEEL], media, gels)
    expect(optionOf(unit, 'gelScroller', 'L201_FULL_CT_BLUE')).toMatchObject({ colourPreview: OPEN_WHITE, gobo: undefined })
    expect(optionOf(unit, 'gelScroller', 'R25_ORANGE_RED')?.colourPreview).toBe('#e85b2b')
    expect(optionOf(unit, 'fbWheelPos', 'SLOT_1')).toMatchObject({ gobo: 'breakup', colourPreview: undefined })
  })

  it('never overlays a setting that is not loadable', () => {
    const props = [PLAIN]
    expect(fittedProperties(props, { slots: { fbWheelFunc: { INDEX: { gel: 'R26' } } } }, gels)).toBe(props)
  })
})

describe('filters and the stored shape', () => {
  it('finds every other gel-taking loadable setting as a filter, not the colour source', () => {
    expect(loadableSettings([SCROLLER, WHEEL, FRAME, PLAIN]).map((s) => s.name)).toEqual(['gelScroller', 'fbWheelPos', 'mediaFrame'])
    expect(mediaFilters([SCROLLER, WHEEL, FRAME, PLAIN], 'gelScroller').map((s) => s.name)).toEqual(['fbWheelPos', 'mediaFrame'])
  })

  it('multiplies filters in series and passes through a filter with no colour', () => {
    expect(filterColour('#ffffff', ['#ee5b5e'])).toBe('#ee5b5e')
    expect(filterColour('#ff8000', ['#808080'])).toBe('#804000')
    expect(filterColour('#9bbede', [undefined])).toBe('#9bbede')
    expect(filterColour('rgb(1, 2, 3)', ['#000000'])).toBe('rgb(1, 2, 3)')
  })

  it('compares media as the desk stores them', () => {
    expect(hasFittedMedia({ slots: { gelScroller: {} } })).toBe(false)
    expect(normaliseMedia({ slots: { gelScroller: {} } })).toBeNull()
    expect(mediaEqual(null, { slots: {} })).toBe(true)
    expect(mediaEqual({ slots: { a: { X: { gel: 'R26', gobo: null } } } }, { slots: { a: { X: { gel: 'R26' } } } })).toBe(true)
    expect(mediaEqual({ slots: { a: { X: {} } } }, null)).toBe(false)
    expect(mediaEqual({ slots: { a: { X: { gel: 'R26' } } } }, { slots: { a: { X: { gel: 'R80' } } } })).toBe(false)
  })
})
