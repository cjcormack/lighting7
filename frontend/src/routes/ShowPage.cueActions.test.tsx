// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { Provider } from 'react-redux'
import type { CueStack, CueStackCueRow } from '@/api/cueStacksApi'
import type { Cue } from '@/api/cuesApi'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

/**
 * Venue-visit plan S3, through the gestures an operator makes: the real view switcher there and
 * back (`ShowPage.test.tsx` covers the page's URL rules with the cue list stubbed), and the real
 * *Duplicate* and *Edit in Programmer* buttons on an expanded cue card. `ShowView`, `StackDetail`
 * and `CueCardEditor` are mounted for real; the network, the card's read surface and the transport
 * are not.
 */

// ── Controllable data layer ──
const stacksState = {
  data: undefined as CueStack[] | undefined,
  isLoading: false,
  isFetching: false,
}
const programState = {
  data: { activeStackId: null as number | null, canEdit: true },
}
const mutation = () => [vi.fn(() => ({ unwrap: () => Promise.resolve({}) }))]
vi.mock('../store/cueStacks', () => ({
  useProjectCueStackListQuery: () => stacksState,
  useProjectProgramStateQuery: () => programState,
  useGoToStackMutation: () => mutation(),
  useDeactivateCueStackMutation: () => mutation(),
  useReorderCueStackCuesMutation: () => mutation(),
  useSortCueStackByCueNumberMutation: () => mutation(),
  useCreateMarkerEntryMutation: () => mutation(),
  useRenameMarkerEntryMutation: () => mutation(),
  useDeleteMarkerEntryMutation: () => mutation(),
}))
const createCue = vi.fn((_input: Record<string, unknown>) => ({ unwrap: () => Promise.resolve({ id: 77 }) }))
vi.mock('../store/cues', () => ({
  useCreateProjectCueMutation: () => [createCue],
  useDeleteProjectCueMutation: () => mutation(),
  usePatchProjectCueMutation: () => mutation(),
  useProjectCueQuery: ({ cueId }: { cueId: number }) => ({ data: fullCue(cueId), isFetching: false }),
  useProjectCueListQuery: () => ({ data: [] }),
}))
vi.mock('../store/promptBooks', () => ({
  useProjectCueLocationsQuery: () => ({ data: [] }),
  useProjectPromptBookQuery: () => ({ data: undefined }),
}))
vi.mock('../store/projects', () => ({
  useCurrentProjectQuery: () => ({ data: { id: 1 }, isLoading: false }),
  useProjectQuery: () => ({ data: { id: 1, name: 'Hamlet' }, isLoading: false }),
}))
vi.mock('../hooks/useContainerBand', () => ({ useNarrowContainer: () => [vi.fn(), false] }))
vi.mock('../hooks/useShowBarProps', () => ({
  useShowBarProps: () => ({
    showBarProps: { goDisabled: true, stackName: null, fade: null },
    showHeaderProps: { isShowActive: false, canStart: false, onStart: vi.fn(), onStop: vi.fn() },
    transport: {
      standbyCueId: null,
      completedCueIds: [],
      activeCueId: null,
      serverActiveCueId: null,
      go: vi.fn(),
      back: vi.fn(),
      setStandby: vi.fn(),
      cancelAnimations: vi.fn(),
    },
    nextStack: null,
    activeCue: null,
    standbyCue: null,
  }),
}))
const include = vi.fn((_cueId: number) => Promise.resolve<object | null>({ fixtureKeys: [], groupKeys: [], warnings: [] }))
vi.mock('../components/programmer/useInclude', () => ({
  useInclude: () => ({ includeCue: include, isLoading: false }),
}))
// The header is the real view switcher and the page's actions; its own chrome is not under test.
vi.mock('../components/ShowHeader', async () => {
  const { ViewSwitcher } = await import('../components/ViewSwitcher')
  return {
    ShowHeader: (p: { projectId: number; actions?: React.ReactNode }) => (
      <div data-testid="header">
        <ViewSwitcher current="show" projectId={p.projectId} />
        {p.actions}
      </div>
    ),
  }
})
vi.mock('../components/ShowBar', () => ({ ShowBar: () => null }))
vi.mock('../components/programmer/RecordSheet', () => ({ RecordSheet: () => null }))
vi.mock('../components/runner/StackTabStrip', () => ({ StackTabStrip: () => null }))
vi.mock('../components/runner/ShowOverview', () => ({ ShowOverview: () => <div data-testid="stack-list" /> }))
vi.mock('../components/runner/ShowLockControl', () => ({ ShowLockControl: () => null }))
// The card's read surface, the hand's strip and the fade — store-touching, and not what is pressed.
vi.mock('@/components/cues/CueDetailContent', () => ({ CueDetailContent: () => <div data-testid="detail" /> }))
vi.mock('@/components/cues/CuePropertiesSheet', () => ({ CuePropertiesSheet: () => null }))
vi.mock('@/components/hand/HandLayerTargets', () => ({ HandCueLayerStrip: () => null }))
vi.mock('@/components/SaveStatusIndicator', () => ({ SaveStatusIndicator: () => null }))
vi.mock('@/components/stage/OwnerEditor', () => ({ OwnerEditor: () => null }))
vi.mock('@/hooks/useCueFade', () => ({ useCueFade: () => ({ fadeProgress: null, fadeRemainMs: null }) }))

import { store } from '../store'
import { unlockRequested } from '../store/editLockSlice'
import { resetShowPositionStore } from '../lib/showPosition'
import { ViewSwitcher } from '../components/ViewSwitcher'
import { ShowPage } from './ShowPage'

function entry(id: number, sortOrder: number, cueNumber: string): CueStackCueRow {
  return {
    id,
    entryId: id + 100,
    name: `Q${cueNumber}`,
    sortOrder,
    layerCount: 0,
    adHocEffectCount: 0,
    autoAdvance: false,
    autoAdvanceDelayMs: null,
    fadeDurationMs: 2000,
    fadeCurve: 'LINEAR',
    cueNumber,
    cueNumberAuto: true,
    notes: null,
    cueType: 'STANDARD',
  }
}

const CUES = [entry(41, 0, '1'), entry(42, 1, '2'), entry(43, 2, '3')]

function fullCue(id: number): Cue | undefined {
  const e = CUES.find((c) => c.id === id)
  if (e == null) return undefined
  return {
    ...e,
    cueStackId: 10,
    layers: [],
    adHocEffects: [],
    propertyAssignments: [],
    triggers: [],
    stomp: false,
  } as unknown as Cue
}

const mkStack = (over: Partial<CueStack> = {}): CueStack => ({
  id: 10,
  name: 'Act 1',
  loop: false,
  sortOrder: 0,
  type: 'STACK',
  label: null,
  cues: CUES,
  activeCueId: null,
  nextCueId: null,
  activeEntryId: null,
  nextEntryId: null,
  standbyEntryId: null,
  canEdit: true,
  canDelete: true,
  ...over,
})

function Where() {
  const l = useLocation()
  return <div data-testid="where">{l.pathname + l.search}</div>
}

const where = () => screen.getByTestId('where').textContent

function draw(entries: string[]) {
  return render(
    <Provider store={store}>
      <MemoryRouter initialEntries={entries}>
        <Where />
        <Routes>
          <Route path="/projects/:projectId/show" element={<ShowPage />} />
          <Route path="/projects/:projectId/show/stacks/:stackId" element={<ShowPage />} />
          <Route
            path="/projects/:projectId/programmer"
            element={<div data-testid="programmer"><ViewSwitcher current="programmer" projectId={1} /></div>}
          />
          <Route
            path="/projects/2/prompt-book"
            element={<div data-testid="book"><ViewSwitcher current="prompt-book" projectId={2} /></div>}
          />
        </Routes>
      </MemoryRouter>
    </Provider>,
  )
}

/** Click a pill of the real view switcher, by its label. */
function pill(label: string) {
  fireEvent.click(screen.getByRole('link', { name: label }))
}

beforeEach(() => {
  sessionStorage.clear()
  resetShowPositionStore()
  stacksState.data = [mkStack(), mkStack({ id: 11, name: 'Act 2', sortOrder: 1, cues: [] })]
  programState.data = { activeStackId: null, canEdit: true }
  // A stopped show is editable anyway; unlocked keeps the card's action row whatever the lock says.
  store.dispatch(unlockRequested())
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('the Show position survives a round trip through the switcher (S3)', () => {
  it('a fresh window goes to bare /show — nothing stored', () => {
    draw(['/projects/1/programmer'])
    expect(screen.getByRole('link', { name: 'Show' }).getAttribute('href')).toBe('/projects/1/show')
  })

  it('Show → Programmer → Show returns to the stack and the open cue', () => {
    draw(['/projects/1/show/stacks/10?cue=42'])
    pill('Programmer')
    expect(screen.getByTestId('programmer')).toBeTruthy()
    pill('Show')
    expect(where()).toBe('/projects/1/show/stacks/10?cue=42')
  })

  it('follows the card the operator opened last, and a closed card returns to the stack alone', () => {
    draw(['/projects/1/show/stacks/10?cue=42'])
    fireEvent.click(screen.getAllByLabelText('Collapse cue')[0])
    expect(where()).toBe('/projects/1/show/stacks/10')
    pill('Programmer')
    pill('Show')
    expect(where()).toBe('/projects/1/show/stacks/10')
  })

  it('a stale stored stack lands on the stack list, and is forgotten', () => {
    sessionStorage.setItem('show.position', JSON.stringify({ '1': { stackId: 99, cueId: 7 } }))
    resetShowPositionStore()
    draw(['/projects/1/programmer'])
    pill('Show')
    expect(where()).toBe('/projects/1/show')
    pill('Programmer')
    expect(screen.getByRole('link', { name: 'Show' }).getAttribute('href')).toBe('/projects/1/show')
  })

  it('a stale stored cue lands on its stack with no card open, and is not remembered', () => {
    sessionStorage.setItem('show.position', JSON.stringify({ '1': { stackId: 10, cueId: 999 } }))
    resetShowPositionStore()
    draw(['/projects/1/programmer'])
    pill('Show')
    expect(where()).toBe('/projects/1/show/stacks/10?cue=999')
    expect(screen.queryAllByLabelText('Collapse cue')).toHaveLength(0)
    pill('Programmer')
    expect(screen.getByRole('link', { name: 'Show' }).getAttribute('href')).toBe('/projects/1/show/stacks/10')
  })

  it('is kept per project: another project’s switcher goes to its own bare /show', () => {
    sessionStorage.setItem('show.position', JSON.stringify({ '1': { stackId: 10, cueId: 42 } }))
    resetShowPositionStore()
    draw(['/projects/2/prompt-book'])
    expect(screen.getByRole('link', { name: 'Show' }).getAttribute('href')).toBe('/projects/2/show')
  })

  it('the stack list forgets the stack', () => {
    draw(['/projects/1/show/stacks/10?cue=42'])
    fireEvent.click(screen.getByRole('button', { name: 'Back to stacks' }))
    expect(where()).toBe('/projects/1/show')
    pill('Programmer')
    expect(screen.getByRole('link', { name: 'Show' }).getAttribute('href')).toBe('/projects/1/show')
  })
})

describe('the cue card’s actions (S3)', () => {
  it('Duplicate appends the copy to the end of the stack and opens it', async () => {
    draw(['/projects/1/show/stacks/10?cue=42'])
    fireEvent.click(screen.getByRole('button', { name: 'Duplicate' }))
    await waitFor(() => expect(createCue).toHaveBeenCalledTimes(1))
    const input = createCue.mock.calls[0][0]
    // No sortOrder: the desk appends at max + 1. The original's (1) put it beside the source.
    expect(input.sortOrder).toBeUndefined()
    expect(input.cueNumber).toBeNull()
    expect(input.cueStackId).toBe(10)
    expect(input.name).toBe('Q2 (copy)')
    await waitFor(() => expect(where()).toBe('/projects/1/show/stacks/10?cue=77'))
  })

  it('Edit in Programmer includes the cue and then opens the programmer', async () => {
    draw(['/projects/1/show/stacks/10?cue=42'])
    fireEvent.click(screen.getByRole('button', { name: 'Edit in Programmer' }))
    expect(include).toHaveBeenCalledWith(42)
    await waitFor(() => expect(where()).toBe('/projects/1/programmer'))
    // And the switcher brings the operator back to the cue they were editing.
    pill('Show')
    expect(where()).toBe('/projects/1/show/stacks/10?cue=42')
  })

  it('Edit in Programmer stays on Show when the Include is refused', async () => {
    include.mockResolvedValueOnce(null)
    draw(['/projects/1/show/stacks/10?cue=42'])
    fireEvent.click(screen.getByRole('button', { name: 'Edit in Programmer' }))
    await waitFor(() => expect(include).toHaveBeenCalledTimes(1))
    await act(async () => {})
    expect(where()).toBe('/projects/1/show/stacks/10?cue=42')
  })
})
