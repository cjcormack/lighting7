import { beforeEach, describe, expect, it, vi } from 'vitest'
import { chan, makeFixture, sliderProp } from '@/test/fixtureFactories'

/**
 * *All*'s row press selects an FX's heads in this window (FX editor plan §4.1): the desk's
 * selection while following, its mask kept; else the programmer list's, through Include's request.
 */
const mocks = vi.hoisted(() => ({ following: true, families: ['COLOUR'] as string[] | null, set: vi.fn(), publish: vi.fn() }))
vi.mock('@/lib/deskFollow', () => ({ isFollowingDesk: () => mocks.following }))
vi.mock('@/api/lightingApi', () => ({ lightingApi: { selection: { getState: () => ({ targets: [], families: mocks.families }) } } }))
vi.mock('@/store/selection', () => ({ setDeskSelection: (...a: unknown[]) => mocks.set(...a) }))
vi.mock('@/store/includeSelection', () => ({ publishIncludeSelection: (...a: unknown[]) => mocks.publish(...a) }))

const { selectWindowHeads } = await import('./programmerSelectRequest')

const RIG = [
  makeFixture('hex-1', [sliderProp('dimmer', 'dimmer', chan(1))], { groups: ['front'] }),
  makeFixture('hex-2', [sliderProp('dimmer', 'dimmer', chan(2))], { groups: ['front'] }),
  makeFixture('par-1', [sliderProp('dimmer', 'dimmer', chan(3))]),
]

beforeEach(() => {
  mocks.set.mockClear()
  mocks.publish.mockClear()
})

describe('selectWindowHeads', () => {
  it('sets the desk’s selection while following, keeping its mask', () => {
    mocks.following = true
    selectWindowHeads([{ type: 'group', key: 'front' }], RIG)
    expect(mocks.set.mock.calls).toEqual([[[{ type: 'group', key: 'front' }], ['COLOUR']]])
    expect(mocks.publish).not.toHaveBeenCalled()
  })

  it('asks the programmer list through Include’s request otherwise, a group as its row and its members', () => {
    mocks.following = false
    selectWindowHeads([{ type: 'group', key: 'front' }, { type: 'fixture', key: 'par-1' }], RIG)
    expect(mocks.publish.mock.calls).toEqual([[['par-1', 'hex-1', 'hex-2'], ['front']]])
    expect(mocks.set).not.toHaveBeenCalled()
  })
})
