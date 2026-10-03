// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'

// fixtures.ts subscribes to lightingApi at import, which opens a real WebSocket.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import {
  findFineProperty,
  findGoboRotationModeProperty,
  findGoboRotationProperty,
  findZoomProperty,
  type PropertyDescriptor,
  type SliderPropertyDescriptor,
} from './fixtures'

function slider(over: Partial<SliderPropertyDescriptor>): SliderPropertyDescriptor {
  return {
    type: 'slider',
    name: 'x',
    displayName: 'X',
    category: 'other',
    channel: { universe: 1, channelNo: 1 },
    min: 0,
    max: 255,
    ...over,
  }
}

// The ETC Source Four Revolution's front wheel index/rotation: a 16-bit pair whose low byte names
// its coarse half with `fineOf`, both in the gobo_rotation category.
const ROT = slider({ name: 'fbWheelRot', category: 'gobo_rotation', channel: { universe: 1, channelNo: 18 } })
const ROT_FINE = slider({
  name: 'fbWheelRotFine',
  category: 'gobo_rotation',
  fineOf: 'fbWheelRot',
  channel: { universe: 1, channelNo: 19 },
})

describe('fineOf', () => {
  it('a finder never takes the fine half as the property of its category', () => {
    // Fine first, so a finder that matched on category alone would take the low byte.
    const properties: PropertyDescriptor[] = [ROT_FINE, ROT]
    expect(findGoboRotationProperty(properties)).toBe(ROT)
    expect(findGoboRotationProperty([ROT_FINE])).toBeUndefined()
    expect(findZoomProperty([slider({ category: 'zoom', fineOf: 'zoom' })])).toBeUndefined()
  })

  it('finds the fine half by the coarse property it names', () => {
    const properties: PropertyDescriptor[] = [ROT_FINE, ROT]
    expect(findFineProperty(properties, ROT)).toBe(ROT_FINE)
    expect(findFineProperty(properties, undefined)).toBeUndefined()
    expect(findFineProperty([ROT], ROT)).toBeUndefined()
  })

  it('finds the wheel function channel by its own category', () => {
    const mode: PropertyDescriptor = {
      type: 'setting',
      name: 'fbWheelFunc',
      displayName: 'Front wheel function',
      category: 'gobo_rotation_mode',
      channel: { universe: 1, channelNo: 17 },
      options: [{ name: 'INDEX', level: 0, displayName: 'Index' }],
    }
    expect(findGoboRotationModeProperty([ROT, mode])).toBe(mode)
    expect(findGoboRotationModeProperty([ROT])).toBeUndefined()
  })
})
