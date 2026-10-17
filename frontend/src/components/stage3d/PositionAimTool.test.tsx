// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { WriteTarget } from '@/components/fixtures-list/rowModel'
import type { AimRequest } from '@/store/programmerOps'
import { chan, makeFixture, sliderProp } from '@/test/fixtureFactories'

/**
 * *Aim…* in the position cell's popup footer (venue-visit plan S13): the Stage view's aim controls
 * over the cell batch's heads, refused with the reason where aiming would not write Local on the
 * live show.
 */

const aim = vi.hoisted(() => ({ calls: [] as AimRequest[] }))
vi.mock('@/store/programmerOps', () => ({
  useAimMutation: () => [
    (request: AimRequest) => {
      aim.calls.push(request)
      return { unwrap: () => Promise.resolve({}) }
    },
    { isLoading: false },
  ],
}))
vi.mock('@/store/stageRegions', () => ({ useStageRegionListQuery: () => ({ data: [] }) }))
const current = vi.hoisted(() => ({ id: 7 as number | undefined }))
vi.mock('@/store/projects', () => ({ useCurrentProjectQuery: () => ({ data: current.id == null ? undefined : { id: current.id } }) }))
const scope = vi.hoisted(() => ({ value: null as unknown }))
vi.mock('@/components/programmer/ProgrammerScope', () => ({ useProgrammerScope: () => scope.value }))

const SPOT = (key: string) =>
  makeFixture(key, [
    sliderProp('pan', 'pan', chan(1), { axis: 'PAN', degMin: 0, degMax: 540 }),
    sliderProp('tilt', 'tilt', chan(2), { axis: 'TILT', degMin: 0, degMax: 270 }),
  ], { name: key })
const PAR = makeFixture('par-1', [sliderProp('dimmer', 'dimmer', chan(21))])
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({
    fixtureByKey: new Map([['spot-1', SPOT('spot-1')], ['spot-2', SPOT('spot-2')], ['par-1', PAR]]),
  }),
}))

const { PositionAimTool } = await import('./PositionAimTool')

const heads = (...keys: string[]): WriteTarget[] => keys.map((key) => ({ key, properties: [] }))

beforeEach(() => {
  aim.calls = []
  current.id = 7
  scope.value = { kind: 'local' }
})
afterEach(cleanup)

describe('PositionAimTool', () => {
  it('aims the cell batch’s heads at a typed point', async () => {
    render(<PositionAimTool projectId={7} targets={heads('spot-1', 'spot-2')} />)
    fireEvent.click(screen.getByRole('button', { name: 'Aim…' }))
    expect(await screen.findByTestId('position-aim-popover')).toBeTruthy()
    fireEvent.change(screen.getByLabelText('X'), { target: { value: '1' } })
    fireEvent.change(screen.getByLabelText('Y'), { target: { value: '-6' } })
    fireEvent.click(screen.getByRole('button', { name: 'Aim 2 fixtures' }))
    await waitFor(() => expect(aim.calls).toHaveLength(1))
    expect(aim.calls[0]).toEqual({
      projectId: 7,
      targets: [
        { type: 'fixture', key: 'spot-1' },
        { type: 'fixture', key: 'spot-2' },
      ],
      x: 1,
      y: -6,
      z: 1.7,
    })
  })

  it('names each head once — a cell under its selected fixture goes with the fixture', async () => {
    render(<PositionAimTool projectId={7} targets={[...heads('spot-1'), { key: 'spot-1.head-1', properties: [], fixtureKey: 'spot-1' }]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Aim…' }))
    expect(await screen.findByRole('button', { name: /^Aim$/ })).toBeTruthy()
  })

  it('aims only the heads it can, and keeps Enter from the cell editor round it', async () => {
    const outer = vi.fn()
    render(
      <div onKeyDown={outer}>
        <PositionAimTool projectId={7} targets={heads('spot-1', 'par-1', 'spot-2')} />
      </div>,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Aim…' }))
    await screen.findByTestId('position-aim-popover')
    expect(screen.getByRole('button', { name: 'Aim 2 fixtures' })).toBeTruthy()
    fireEvent.change(screen.getByLabelText('X'), { target: { value: '0' } })
    fireEvent.change(screen.getByLabelText('Y'), { target: { value: '0' } })
    fireEvent.keyDown(screen.getByLabelText('Y'), { key: 'Enter' })
    expect(outer).not.toHaveBeenCalled()
    fireEvent.submit(screen.getByLabelText('Y').closest('form')!)
    await waitFor(() => expect(aim.calls).toHaveLength(1))
    expect(aim.calls[0].targets.map((t) => t.key)).toEqual(['spot-1', 'spot-2'])
  })

  it.each([
    ['another project', () => { current.id = 3 }, 'Aim writes the live show, and this project is not on the rig'],
    ['a focused layer', () => { scope.value = { kind: 'layer', layerId: 4 } }, 'Aim writes Local — switch to Local to aim these heads'],
    ['Output', () => { scope.value = { kind: 'output' } }, 'Output is a read of the cook — switch to Local to aim these heads'],
  ])('is refused, with the reason, on %s', (_name, arrange, reason) => {
    arrange()
    render(<PositionAimTool projectId={7} targets={heads('spot-1')} />)
    const button = screen.getByRole('button', { name: 'Aim…' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('title', reason)
  })

  it('is refused where no head annotates its pan and tilt in degrees', () => {
    render(<PositionAimTool projectId={7} targets={heads('par-1')} />)
    expect(screen.getByRole('button', { name: 'Aim…' })).toHaveAttribute('title', 'No head here annotates its pan and tilt in degrees')
  })
})
