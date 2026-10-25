// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CueTarget } from '@/api/cuesApi'
import type { GroupSummary } from '@/api/groupsApi'
import { chan, makeFixture, sliderProp } from '@/test/fixtureFactories'

/**
 * The tray's *Open in FX editor* (⤢, FX editor plan §4.6): the programmer on the sheet's fixture or
 * group, its FX tab on *Selection* — the selection the desk's while the window follows it, else
 * Include's request, which the programmer list takes up as it mounts.
 */
const wire = vi.hoisted(() => ({ following: false, desk: [] as { targets: CueTarget[]; families: unknown }[] }))

vi.mock('@/store/fixtureFx', () => ({
  useActiveFxQuery: () => ({ data: [] }),
  usePauseFxMutation: () => [vi.fn()],
  useResumeFxMutation: () => [vi.fn()],
  useRemoveFxMutation: () => [vi.fn()],
}))
vi.mock('@/store/groups', () => ({
  usePauseGroupFxMutation: () => [vi.fn()],
  useResumeGroupFxMutation: () => [vi.fn()],
  useRemoveGroupFxMutation: () => [vi.fn()],
}))
vi.mock('@/store/projects', () => ({ useCurrentProjectQuery: () => ({ data: { id: 7 } }) }))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => true }))
vi.mock('@/hooks/useProgrammerBlind', () => ({ useProgrammerBlind: () => false }))
vi.mock('./fxLabels', () => ({ useFxDetail: () => () => '', useCueLabel: () => () => '' }))
vi.mock('../fx/LookTogglePicker', () => ({ LookTogglePicker: () => null }))
vi.mock('../fx/FxPicker', () => ({ FxPicker: () => null }))
vi.mock('../fx/FxEditor', () => ({ FxEditor: () => null }))
vi.mock('../stage/OwnerEditor', () => ({ OwnerEditor: () => null }))
vi.mock('@/lib/deskFollow', () => ({ isFollowingDesk: () => wire.following }))
vi.mock('@/store/selection', () => ({
  setDeskSelection: (targets: CueTarget[], families: unknown) => wire.desk.push({ targets, families }),
}))
vi.mock('@/api/lightingApi', () => ({ lightingApi: { selection: { getState: () => ({ families: ['COLOUR'] }) } } }))

const HEX_1 = makeFixture('hex-1', [sliderProp('dimmer', 'dimmer', chan(1))], { name: 'Hex 1', groups: ['front'] })
const HEX_2 = makeFixture('hex-2', [sliderProp('dimmer', 'dimmer', chan(1))], { name: 'Hex 2', groups: ['front'] })
const SPOT = makeFixture('spot-1', [sliderProp('dimmer', 'dimmer', chan(1))], { name: 'Spot 1' })
vi.mock('@/hooks/useFixtureLookup', () => ({ useFixtureLookup: () => ({ fixtures: [HEX_1, HEX_2, SPOT] }) }))

const { FxTray } = await import('./FxTray')
const { resetFxEditorViewStores, setFxTabView, useFxTabView } = await import('@/lib/fxEditorView')
const { useIncludeSelectionRequest } = await import('@/store/includeSelection')
const { SheetCloseContext } = await import('./sheetContext')

const FRONT = { name: 'front', memberCount: 2 } as GroupSummary

/** What the programmer route sees on arriving: its address, the FX tab's view, Include's request. */
function Programmer() {
  const location = useLocation()
  const view = useFxTabView()
  const include = useIncludeSelectionRequest()
  return (
    <div data-testid="programmer" data-path={`${location.pathname}${location.search}`} data-view={view}>
      {JSON.stringify({ fixtureKeys: include.fixtureKeys, groupKeys: include.groupKeys })}
    </div>
  )
}

function draw(target: Parameters<typeof FxTray>[0]['target'], close: (() => void) | null = null) {
  render(
    <MemoryRouter initialEntries={['/projects/7/stage']}>
      <Routes>
        <Route
          path="/projects/:projectId/stage"
          element={
            <SheetCloseContext.Provider value={close}>
              <FxTray target={target} />
            </SheetCloseContext.Provider>
          }
        />
        <Route path="/projects/:projectId/programmer" element={<Programmer />} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  sessionStorage.clear()
  resetFxEditorViewStores()
  setFxTabView('all')
  wire.following = false
  wire.desk = []
})
afterEach(cleanup)

describe('Open in FX editor', () => {
  it('lands a group on the programmer’s FX tab on Selection, selected through Include’s request', () => {
    draw({ type: 'group', group: FRONT })
    fireEvent.click(screen.getByRole('button', { name: 'Open front in the FX editor' }))
    const programmer = screen.getByTestId('programmer')
    expect(programmer).toHaveAttribute('data-path', '/projects/7/programmer?table=fx')
    expect(programmer).toHaveAttribute('data-view', 'selection')
    expect(JSON.parse(programmer.textContent!)).toEqual({ fixtureKeys: ['hex-1', 'hex-2'], groupKeys: ['front'] })
    expect(wire.desk).toEqual([])
  })

  it('sets the desk’s selection while the window follows it, its mask kept', () => {
    wire.following = true
    draw({ type: 'fixture', fixture: SPOT })
    fireEvent.click(screen.getByRole('button', { name: 'Open Spot 1 in the FX editor' }))
    expect(screen.getByTestId('programmer')).toHaveAttribute('data-view', 'selection')
    expect(wire.desk).toEqual([{ targets: [{ type: 'fixture', key: 'spot-1' }], families: ['COLOUR'] }])
  })

  it('closes a modal host first, which would otherwise stay over the FX editor', () => {
    const close = vi.fn()
    draw({ type: 'fixture', fixture: SPOT }, close)
    fireEvent.click(screen.getByRole('button', { name: 'Open Spot 1 in the FX editor' }))
    expect(close).toHaveBeenCalledTimes(1)
    expect(screen.getByTestId('programmer')).toHaveAttribute('data-view', 'selection')
  })
})
