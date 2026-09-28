// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { StageRegionDto } from '@/api/stageRegionApi'
import type { AimRequest, AimResponse } from '@/store/programmerOps'
import { chan, makeFixture, sliderProp } from '@/test/fixtureFactories'

/**
 * The Stage view's "Aim at point": the point it sends, the desk's answer it reads back (what was
 * aimed, what was skipped and why), and which heads it is offered for at all.
 */
const aim = vi.hoisted(() => ({
  calls: [] as AimRequest[],
  answer: {} as AimResponse,
}))
vi.mock('@/store/programmerOps', () => ({
  useAimMutation: () => [
    (request: AimRequest) => {
      aim.calls.push(request)
      return { unwrap: () => Promise.resolve(aim.answer) }
    },
    { isLoading: false },
  ],
}))
vi.mock('@/store/stageRegions', () => ({ useStageRegionListQuery: () => ({ data: [] }) }))

const SPOT = makeFixture('spot-1', [
  sliderProp('pan', 'pan', chan(1), { axis: 'PAN', degMin: 0, degMax: 540 }),
  sliderProp('tilt', 'tilt', chan(2), { axis: 'TILT', degMin: 0, degMax: 270 }),
], { name: 'Spot 1' })
const UNRANGED = makeFixture('scan-1', [
  sliderProp('pan', 'pan', chan(11), { axis: 'PAN' }),
  sliderProp('tilt', 'tilt', chan(12), { axis: 'TILT' }),
])
const PAR = makeFixture('par-1', [sliderProp('dimmer', 'dimmer', chan(21))], { name: 'Par 1' })

vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({ fixtureByKey: new Map([[SPOT.key, SPOT], [PAR.key, PAR]]) }),
}))

const { StageAimControls, isAimable, regionAimPoint, HEAD_HEIGHT_M } = await import('./StageAimControls')

beforeEach(() => {
  aim.calls = []
  aim.answer = {}
})
afterEach(cleanup)

function type(label: RegExp | string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}

describe('StageAimControls', () => {
  it('sends the typed point for every fixture, with z defaulting to head height', async () => {
    aim.answer = {
      written: [{ target: { type: 'fixture', key: 'spot-1' }, value: '127,127', panDeg: 270, tiltDeg: 135 }],
      skipped: [{ target: { type: 'fixture', key: 'par-1' }, reason: 'fixed head — no pan or tilt' }],
    }
    render(<StageAimControls projectId={7} fixtureKeys={['spot-1', 'par-1']} />)
    const button = screen.getByRole('button', { name: /aim 2 fixtures/i })
    expect(button).toBeDisabled()

    type('X', '1.5')
    type('Y', '-2')
    expect(button).toBeEnabled()
    fireEvent.click(button)

    await waitFor(() => expect(aim.calls).toHaveLength(1))
    expect(aim.calls[0]).toEqual({
      projectId: 7,
      targets: [
        { type: 'fixture', key: 'spot-1' },
        { type: 'fixture', key: 'par-1' },
      ],
      x: 1.5,
      y: -2,
      z: HEAD_HEIGHT_M,
    })
    // The desk's answer, by name: what it aimed and why it skipped the rest.
    expect(await screen.findByText('Aimed Spot 1: pan 270°, tilt 135°')).toBeTruthy()
    expect(screen.getByText('Par 1 — fixed head — no pan or tilt')).toBeTruthy()
  })

  it('is not sent without a whole point', () => {
    render(<StageAimControls projectId={7} fixtureKeys={['spot-1']} />)
    type('X', '1')
    type('Y', '2')
    type('Z', '')
    const button = screen.getByRole('button', { name: /^aim$/i })
    expect(button).toBeDisabled()
    fireEvent.click(button)
    expect(aim.calls).toHaveLength(0)
  })
})

describe('regionAimPoint', () => {
  const region = (over: Partial<StageRegionDto>): StageRegionDto => ({
    id: 1, uuid: 'r', name: 'DSC', centerX: 0, centerY: 1, centerZ: null,
    widthM: 2, depthM: 2, heightM: null, yawDeg: null, sortOrder: 0, ...over,
  })

  it('is the centre of the region at head height above its top surface', () => {
    expect(regionAimPoint(region({}))).toEqual({ x: 0, y: 1, z: 1.7 })
    expect(regionAimPoint(region({ centerX: -3, centerY: 4, centerZ: 0.6 }))).toEqual({ x: -3, y: 4, z: 2.3 })
  })

  it('is null for a region that is not on the stage', () => {
    expect(regionAimPoint(region({ centerX: null }))).toBeNull()
  })
})

describe('isAimable', () => {
  it('needs a degree range on both pan and tilt', () => {
    expect(isAimable(SPOT)).toBe(true)
    expect(isAimable(UNRANGED)).toBe(false)
    expect(isAimable(PAR)).toBe(false)
    expect(isAimable(undefined)).toBe(false)
  })
})
