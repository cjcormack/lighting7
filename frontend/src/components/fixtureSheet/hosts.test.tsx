// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

type SheetProps = { host: string; aim?: ReactNode; focus?: ReactNode; group?: { name: string }; onOpenMember?: unknown }
const sheets = vi.hoisted(() => ({ props: [] as SheetProps[] }))
vi.mock('./FixtureSheet', () => ({
  FixtureSheet: (props: SheetProps) => {
    sheets.props.push(props)
    return <div data-testid="sheet">{props.aim}</div>
  },
}))

import { chan, groupSummary, makeFixture, sliderProp } from '@/test/fixtureFactories'

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
vi.mock('../../store/groups', () => ({
  useGroupListQuery: () => ({ data: [groupSummary('front', 2)] }),
  useGroupQuery: () => ({ data: { members: [] } }),
}))
vi.mock('../fx/FxBadge', () => ({ FxBadge: () => null }))
vi.mock('../fixtures/LocateButton', () => ({ LocateButton: () => <button type="button">Locate</button> }))

import { FixtureDetailModal } from '../groups/FixtureDetailModal'
import { StageFixtureControlPanel } from '../stage3d/StageFixtureControlPanel'
import { GroupDetailModal } from '../fixtures/GroupDetailModal'
import { GroupCard } from '../groups/GroupCard'

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

/** Session 4's two group hosts: the group sheet is the fixture sheet's body on a group (D1). */
describe('group sheet hosts', () => {
  it('the group sheet mounts the sheet on the group as a popup, its members opening their own', () => {
    sheets.props = []
    render(<GroupDetailModal groupName="front" onClose={() => {}} />)
    expect(screen.getByTestId('sheet')).toBeTruthy()
    expect(sheets.props.at(-1)).toMatchObject({ host: 'popup', group: { name: 'front' } })
    expect(typeof sheets.props.at(-1)?.onOpenMember).toBe('function')
    expect(screen.queryByRole('button', { name: /^(edit|done)$/i })).toBeNull()
  })

  it('a group card mounts the card host, with no Edit toggle', () => {
    sheets.props = []
    render(<GroupCard group={groupSummary('front', 2)} onFixtureClick={() => {}} />)
    expect(sheets.props.at(-1)).toMatchObject({ host: 'card', group: { name: 'front' } })
    expect(screen.queryByRole('button', { name: /^(edit|done)$/i })).toBeNull()
  })
})
