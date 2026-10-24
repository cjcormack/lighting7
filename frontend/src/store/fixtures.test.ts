// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'

// fixtures.ts subscribes to lightingApi at import, which opens a real WebSocket.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import {
  findFineAttribute,
  findGoboRotationModeAttribute,
  findGoboRotationAttribute,
  findShutterAttributes,
  findZoomAttribute,
  type AttributeDescriptor,
  type SliderAttributeDescriptor,
} from './fixtures'
import { revolutionShutterProps } from '@/test/fixtureFactories'

function slider(over: Partial<SliderAttributeDescriptor>): SliderAttributeDescriptor {
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
    const attributes: AttributeDescriptor[] = [ROT_FINE, ROT]
    expect(findGoboRotationAttribute(attributes)).toBe(ROT)
    expect(findGoboRotationAttribute([ROT_FINE])).toBeUndefined()
    expect(findZoomAttribute([slider({ category: 'zoom', fineOf: 'zoom' })])).toBeUndefined()
  })

  it('finds the fine half by the coarse property it names', () => {
    const attributes: AttributeDescriptor[] = [ROT_FINE, ROT]
    expect(findFineAttribute(attributes, ROT)).toBe(ROT_FINE)
    expect(findFineAttribute(attributes, undefined)).toBeUndefined()
    expect(findFineAttribute([ROT], ROT)).toBeUndefined()
  })

  it('finds the wheel function channel by its own category', () => {
    const mode: AttributeDescriptor = {
      type: 'setting',
      name: 'fbWheelFunc',
      displayName: 'Front wheel function',
      category: 'gobo_rotation_mode',
      channel: { universe: 1, channelNo: 17 },
      options: [{ name: 'INDEX', level: 0, displayName: 'Index' }],
    }
    expect(findGoboRotationModeAttribute([ROT, mode])).toBe(mode)
    expect(findGoboRotationModeAttribute([ROT])).toBeUndefined()
  })
})

describe('findShutterAttributes', () => {
  it("files each frame under its blade, in the wire's order, whatever the descriptor order", () => {
    const props = revolutionShutterProps()
    const byName = new Map(props.map((p) => [p.name, p]))
    // Reversed, as reflection order guarantees nothing.
    const found = findShutterAttributes([...props].reverse())
    expect(found?.depth).toEqual(['frame1Pos', 'frame2Pos', 'frame3Pos', 'frame4Pos'].map((n) => byName.get(n)))
    expect(found?.rotation).toEqual(['frame1Rot', 'frame2Rot', 'frame3Rot', 'frame4Rot'].map((n) => byName.get(n)))
  })

  it('finds nothing on a fixture that drives no blade, or one whose blades declare no scale', () => {
    expect(findShutterAttributes(undefined)).toBeUndefined()
    expect(findShutterAttributes([slider({ category: 'zoom', degMin: 35, degMax: 15 })])).toBeUndefined()
    expect(findShutterAttributes([slider({ category: 'shutter', blade: 'TOP' })])).toBeUndefined()
    expect(findShutterAttributes([slider({ category: 'shutter_rotation', blade: 'TOP', degMin: -45 })])).toBeUndefined()
    expect(findShutterAttributes([slider({ category: 'shutter', depthMax: 0.5 })])).toBeUndefined()
  })

  it('keeps a blade with only one of its channels', () => {
    const left = slider({ name: 'leftIn', category: 'shutter', blade: 'LEFT', depthMax: 0.5 })
    const found = findShutterAttributes([left])
    expect(found?.depth).toEqual([undefined, undefined, left, undefined])
    expect(found?.rotation).toEqual([undefined, undefined, undefined, undefined])
  })
})
