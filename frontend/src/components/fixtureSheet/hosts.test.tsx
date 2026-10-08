// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

const sheets = vi.hoisted(() => ({ props: [] as { host: string; aim?: ReactNode; focus?: ReactNode }[] }))
vi.mock('./FixtureSheet', () => ({
  FixtureSheet: (props: { host: string; aim?: ReactNode; focus?: ReactNode }) => {
    sheets.props.push(props)
    return <div data-testid="sheet">{props.aim}</div>
  },
}))

import { chan, makeFixture, sliderProp } from '@/test/fixtureFactories'

const MOVER = makeFixture('spot-3', [
  sliderProp('pan', 'pan', chan(1), { axis: 'PAN', degMin: 0, degMax: 540 }),
  sliderProp('tilt', 'tilt', chan(2), { axis: 'TILT', degMin: 0, degMax: 270 }),
])

vi.mock('../../store/fixtures', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../store/fixtures')>()),
  useFixtureListQuery: () => ({ data: [MOVER] }),
}))
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({ fixtureByKey: new Map([['spot-3', MOVER]]), typeByKey: new Map() }),
}))
vi.mock('@/store/patches', () => ({ useVisiblePatchListQuery: () => ({ data: [] }) }))
vi.mock('@/hooks/useLanternIndex', () => ({ useLanternIndex: () => new Map() }))
vi.mock('../stage3d/StageAimControls', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../stage3d/StageAimControls')>()),
  StageAimControls: () => <div data-testid="aim-body" />,
}))
vi.mock('../stage3d/StageFocusPanel', () => ({ StageFocusPanel: () => null }))

import { FixtureDetailModal } from '../groups/FixtureDetailModal'
import { StageFixtureControlPanel } from '../stage3d/StageFixtureControlPanel'

/** The two hosts session 2 moves onto the sheet (§3.3, §4): neither draws an Edit toggle any more. */
describe('fixture sheet hosts', () => {
  it('the pop-up mounts the sheet as a popup, with no Edit toggle', () => {
    sheets.props = []
    render(<FixtureDetailModal fixtureKey="spot-3" onClose={() => {}} />)
    expect(screen.getByTestId('sheet')).toBeTruthy()
    expect(sheets.props.at(-1)?.host).toBe('popup')
    expect(screen.queryByRole('button', { name: /^(edit|done)$/i })).toBeNull()
  })

  it('the Stage panel mounts it as stage, with Aim moved into the sheet rather than pinned under it', () => {
    sheets.props = []
    render(<StageFixtureControlPanel patchKey="spot-3" projectId={1} canAim onClose={() => {}} />)
    expect(sheets.props.at(-1)?.host).toBe('stage')
    expect(sheets.props.at(-1)?.aim).toBeTruthy()
    // The aim body is inside the sheet (its Position row's popover), not a block of the panel's.
    expect(screen.getByTestId('sheet').contains(screen.getByTestId('aim-body'))).toBe(true)
    expect(screen.queryByRole('button', { name: /^(edit|done)$/i })).toBeNull()
  })

  it('offers no Aim off the live project', () => {
    sheets.props = []
    render(<StageFixtureControlPanel patchKey="spot-3" projectId={1} canAim={false} onClose={() => {}} />)
    expect(sheets.props.at(-1)?.aim).toBeUndefined()
  })
})
