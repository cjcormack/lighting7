// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { StageRegionDto } from '@/api/stageRegionApi'
import type { AimRequest, AimResponse } from '@/store/programmerOps'
import { chan, makeFixture, sliderProp } from '@/test/fixtureFactories'

/**
 * The Stage view's "Aim at point": the point it sends, the desk's answer it reads back (what was
 * aimed, what was skipped and why), and which heads it is offered for at all.
 */
const aim = vi.hoisted(() => {
  const state = {
    calls: [] as AimRequest[],
    answer: {} as AimResponse,
    reply: (): Promise<AimResponse> => Promise.resolve(state.answer),
  }
  return state
})
vi.mock('@/store/programmerOps', () => ({
  useAimMutation: () => [
    (request: AimRequest) => {
      aim.calls.push(request)
      return { unwrap: () => aim.reply() }
    },
    { isLoading: false },
  ],
}))
const regions = vi.hoisted(() => ({ list: [] as unknown[] }))
vi.mock('@/store/stageRegions', () => ({ useStageRegionListQuery: () => ({ data: regions.list }) }))

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
const { createPlanAimClicks } = await import('./planAim')

beforeEach(() => {
  aim.calls = []
  aim.answer = {}
  regions.list = []
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

  it('says the desk’s skips one line per reason', async () => {
    aim.answer = {
      skipped: [
        { target: { type: 'fixture', key: 'par-1' }, reason: 'fixed head — no pan or tilt' },
        { target: { type: 'fixture', key: 'scan-1' }, reason: 'no degree range on its pan' },
        { target: { type: 'fixture', key: 'spot-1' }, reason: 'fixed head — no pan or tilt' },
      ],
    }
    render(<StageAimControls projectId={7} fixtureKeys={['spot-1', 'par-1', 'scan-1']} />)
    type('X', '0')
    type('Y', '0')
    fireEvent.click(screen.getByRole('button', { name: /aim 3 fixtures/i }))
    await screen.findByText('Par 1, Spot 1 — fixed head — no pan or tilt')
    expect(screen.getByText('scan-1 — no degree range on its pan')).toBeTruthy()
    expect(document.querySelectorAll('[data-aim-skip]')).toHaveLength(2)
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

describe('StageAimControls on the plan (venue-visit plan S13)', () => {
  const ROSTRUM: StageRegionDto = {
    id: 2, uuid: 'ros', name: 'Rostrum', centerX: 2, centerY: 3, centerZ: 0.6,
    widthM: 2, depthM: 2, heightM: 0.6, yawDeg: null, sortOrder: 0,
  }

  it('offers the plan where the host has one, and arms it with what it holds', () => {
    const onArm = vi.fn()
    render(<StageAimControls projectId={7} fixtureKeys={['spot-1']} plan={{ armed: false, onArm }} />)
    type('Z', '3')
    fireEvent.click(screen.getByRole('button', { name: 'Aim on the plan' }))
    expect(onArm).toHaveBeenCalledWith({ x: null, y: null, z: 3, region: '', height: 'z' })
  })

  it('starts from a seed, so the docked panel takes over where the popover left off', async () => {
    const clicks = createPlanAimClicks()
    render(
      <StageAimControls
        projectId={7}
        fixtureKeys={['spot-1']}
        plan={{ armed: true, onArm: vi.fn(), clicks }}
        seed={{ x: null, y: null, z: 3, region: '', height: 'z' }}
      />,
    )
    expect(screen.getByRole('radio', { name: 'At Z' })).toHaveAttribute('aria-checked', 'true')
    act(() => clicks.emit({ x: 1, y: 1 }))
    await waitFor(() => expect(aim.calls).toHaveLength(1))
    expect(aim.calls[0]).toMatchObject({ x: 1, y: 1, z: 3 })
  })

  it('aims on Enter in a field', async () => {
    render(<StageAimControls projectId={7} fixtureKeys={['spot-1']} />)
    type('X', '1')
    type('Y', '2')
    fireEvent.submit(screen.getByLabelText('Y').closest('form')!)
    await waitFor(() => expect(aim.calls).toHaveLength(1))
  })

  it('sends one aim at a time, the newest click next, and shows only the newest answer', async () => {
    const replies: ((answer: AimResponse) => void)[] = []
    const queue = vi.spyOn(aim, 'reply').mockImplementation(() => new Promise<AimResponse>((resolve) => replies.push(resolve)))
    const clicks = createPlanAimClicks()
    render(<StageAimControls projectId={7} fixtureKeys={['spot-1']} plan={{ armed: true, onArm: vi.fn(), clicks }} />)
    act(() => clicks.emit({ x: 1, y: 0 }))
    act(() => clicks.emit({ x: 2, y: 0 }))
    act(() => clicks.emit({ x: 3, y: 0 }))
    // The first is in flight; the second was overtaken by the third before it left.
    expect(aim.calls.map((c) => c.x)).toEqual([1])
    await act(async () => replies[0]({ written: [{ target: { type: 'fixture', key: 'spot-1' }, value: '1,1', panDeg: 1, tiltDeg: 1 }] }))
    await waitFor(() => expect(aim.calls.map((c) => c.x)).toEqual([1, 3]))
    expect(screen.queryByText(/pan 1°/)).toBeNull()
    await act(async () => replies[1]({ written: [{ target: { type: 'fixture', key: 'spot-1' }, value: '3,3', panDeg: 3, tiltDeg: 3 }] }))
    expect(await screen.findByText('Aimed Spot 1: pan 3°, tilt 3°')).toBeTruthy()
    queue.mockRestore()
  })

  it('takes the floor off every region from the host — the house floor off the stage', async () => {
    const clicks = createPlanAimClicks()
    render(
      <StageAimControls
        projectId={7}
        fixtureKeys={['spot-1']}
        plan={{ armed: true, onArm: vi.fn(), clicks, floorAt: () => -1 }}
      />,
    )
    act(() => clicks.emit({ x: 0, y: -12 }))
    await waitFor(() => expect(aim.calls).toHaveLength(1))
    expect(aim.calls[0].z).toBeCloseTo(0.7, 9)
  })

  it('aims every selected head at a plan click, at head height on the floor', async () => {
    const clicks = createPlanAimClicks()
    const onAimed = vi.fn()
    render(
      <StageAimControls
        projectId={7}
        fixtureKeys={['spot-1', 'par-1']}
        plan={{ armed: true, onArm: vi.fn(), onDisarm: vi.fn(), clicks, onAimed }}
      />,
    )
    act(() => clicks.emit({ x: -3.2, y: -12.5 }))
    await waitFor(() => expect(aim.calls).toHaveLength(1))
    expect(aim.calls[0]).toEqual({
      projectId: 7,
      targets: [
        { type: 'fixture', key: 'spot-1' },
        { type: 'fixture', key: 'par-1' },
      ],
      x: -3.2,
      y: -12.5,
      z: HEAD_HEIGHT_M,
    })
    expect(onAimed).toHaveBeenCalledWith({ x: -3.2, y: -12.5, z: HEAD_HEIGHT_M })
    // The point stays in the fields, the numeric entry for the next one.
    expect((screen.getByLabelText('X') as HTMLInputElement).value).toBe('-3.2')
    expect(screen.getByText('Head height on the floor')).toBeTruthy()
  })

  it('takes its height from the region under the click, or the typed Z on At Z', async () => {
    regions.list = [ROSTRUM]
    const clicks = createPlanAimClicks()
    render(<StageAimControls projectId={7} fixtureKeys={['spot-1']} plan={{ armed: true, onArm: vi.fn(), clicks }} />)
    act(() => clicks.emit({ x: 2.5, y: 3.5 }))
    await waitFor(() => expect(aim.calls).toHaveLength(1))
    expect(aim.calls[0].z).toBeCloseTo(2.3, 9)
    expect(screen.getByText('Head height on Rostrum')).toBeTruthy()

    // Typing a height makes it the height a click takes.
    type('Z', '4')
    expect(screen.getByRole('radio', { name: 'At Z' })).toHaveAttribute('aria-checked', 'true')
    act(() => clicks.emit({ x: 2.5, y: 3.5 }))
    await waitFor(() => expect(aim.calls).toHaveLength(2))
    expect(aim.calls[1]).toMatchObject({ x: 2.5, y: 3.5, z: 4 })

    fireEvent.click(screen.getByRole('radio', { name: 'Head height' }))
    act(() => clicks.emit({ x: 8, y: 3.5 }))
    await waitFor(() => expect(aim.calls).toHaveLength(3))
    expect(aim.calls[2]).toMatchObject({ x: 8, y: 3.5, z: HEAD_HEIGHT_M })
  })

  it('hears no click while disarmed, and Done disarms', () => {
    const clicks = createPlanAimClicks()
    const onDisarm = vi.fn()
    const { rerender } = render(
      <StageAimControls projectId={7} fixtureKeys={['spot-1']} plan={{ armed: true, onArm: vi.fn(), onDisarm, clicks }} />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Done' }))
    expect(onDisarm).toHaveBeenCalledTimes(1)
    rerender(<StageAimControls projectId={7} fixtureKeys={['spot-1']} plan={{ armed: false, onArm: vi.fn(), onDisarm, clicks }} />)
    act(() => clicks.emit({ x: 1, y: 1 }))
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
