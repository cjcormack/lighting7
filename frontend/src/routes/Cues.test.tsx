// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { Provider } from 'react-redux'
import { configureStore } from '@reduxjs/toolkit'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Cue, CueEntryPlace, CueUses } from '@/api/cuesApi'
import { editLockSlice } from '@/store/editLockSlice'

/**
 * The Cues table (cue model plan §3.7, session 5): every cue once with its places and what else
 * names it, the filters and chips as the address, the cue action set over several rows — Duplicate,
 * Add to stack…, Delete… through the D8 confirm — and the show-editing lock while the show runs.
 */
const landed = (value: unknown = undefined) => ({ unwrap: () => Promise.resolve(value) })
const duplicateCue = vi.fn()
const deleteCues = vi.fn()
const addToStack = vi.fn()
const patchCue = vi.fn()
let cues: Cue[] = []
let preview: { uses: CueUses[] } | undefined
let running = false
let current = { id: 1, name: 'Hamlet' }

vi.mock('@/store/projects', () => ({
  useCurrentProjectQuery: () => ({ data: current, isLoading: false }),
  useProjectQuery: (id: number) => ({ data: { id, name: id === 1 ? 'Hamlet' : 'Panto' }, isLoading: false }),
}))
vi.mock('@/store/cues', () => ({
  useProjectCueListQuery: () => ({ data: cues, isLoading: false }),
  usePatchProjectCueMutation: () => [patchCue],
  useDuplicateCueMutation: () => [duplicateCue],
  useDeleteCuesMutation: () => [deleteCues, { isLoading: false }],
  useCueDeletePreviewQuery: (_arg: unknown, opts?: { skip?: boolean }) => ({
    data: opts?.skip ? undefined : preview,
    isFetching: false,
    isError: false,
  }),
}))
vi.mock('@/store/cueStacks', () => ({
  useAddCueToStackMutation: () => [addToStack],
  useMoveCueStackEntryMutation: () => [vi.fn()],
  useRemoveCueStackEntryMutation: () => [vi.fn()],
  useProjectProgramStateQuery: () => ({ data: { activeStackId: running ? 7 : null, canEdit: true } }),
  useProjectCueStackListQuery: () => ({
    data: [
      { id: 7, name: 'Act 1', type: 'STACK', cues: [] },
      { id: 8, name: 'Act 2', type: 'STACK', cues: [] },
    ],
  }),
}))
vi.mock('@/store/cueHolds', () => ({ useCueRings: () => new Map([[3, 'loose']]), useCueEdited: () => false }))
vi.mock('@/store/programmer', () => ({
  programmerBlindOn: vi.fn(),
  useProgrammerSummaryQuery: () => ({ data: { blind: false } }),
}))
vi.mock('@/components/programmer/useInclude', () => ({ useInclude: () => ({ includeCue: vi.fn(), isLoading: false }) }))
vi.mock('@/components/programmer/RecordSheet', () => ({ RecordSheet: () => null }))
vi.mock('@/components/stage/OwnerEditor', () => ({
  CueEditorSheet: ({ cueId, locked }: { cueId: number | null; locked: boolean }) =>
    cueId == null ? null : <div data-testid="cue-sheet" data-locked={locked}>{cueId}</div>,
}))
vi.mock('@/components/Breadcrumbs', () => ({ Breadcrumbs: () => null }))
vi.mock('@/components/cues/CopyFromShowSheet', () => ({
  CopyFromShowSheet: ({ projectId }: { projectId: number }) => <div data-testid="copy-sheet">{projectId}</div>,
}))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() } }))
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () =>
      Array.from({ length: count }, (_, index) => ({ index, key: index, start: index * estimateSize(), size: estimateSize() })),
    scrollToIndex: () => {},
  }),
}))

const { ProjectCues } = await import('./Cues')

const place = (stackId: number, stackName: string, entryId: number, cueNumber: string | null): CueEntryPlace => ({
  entryId,
  stackId,
  stackName,
  sortOrder: 0,
  cueNumber,
  cueNumberAuto: false,
  autoAdvance: false,
  autoAdvanceDelayMs: null,
})

function cue(id: number, name: string, over: Partial<Cue> = {}): Cue {
  return {
    id,
    name,
    layers: [],
    adHocEffects: [],
    propertyAssignments: [],
    triggers: [],
    cueStackId: null,
    cueStackName: null,
    sortOrder: 0,
    autoAdvance: false,
    autoAdvanceDelayMs: null,
    fadeDurationMs: null,
    fadeCurve: 'LINEAR',
    cueNumber: null,
    cueNumberAuto: false,
    entries: [],
    notes: null,
    cueType: 'STANDARD',
    canEdit: true,
    canDelete: true,
    ...over,
  }
}

function Where() {
  const location = useLocation()
  return <div data-testid="where">{location.pathname + location.search}</div>
}

function renderAt(url: string) {
  const store = configureStore({ reducer: { editLock: editLockSlice.reducer } })
  return render(
    <Provider store={store}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/projects/:projectId/cues" element={<ProjectCues />} />
          <Route path="*" element={null} />
        </Routes>
        <Where />
      </MemoryRouter>
    </Provider>,
  )
}

const where = () => screen.getByTestId('where').textContent
const row = (id: number) => document.querySelector(`[data-row-id="cue:${id}"]`) as HTMLElement
const names = () => [...document.querySelectorAll('[data-row-id^="cue:"] [data-first-column]')].map((el) => el.textContent ?? '')

function selectRows(...ids: number[]) {
  ids.forEach((id, i) => fireEvent.click(row(id).querySelector('[data-first-column]')!, { metaKey: i > 0 }))
}

/** Open a Radix dropdown from its trigger — jsdom has no pointer for it. */
function openMenu(trigger: HTMLElement) {
  fireEvent.keyDown(trigger, { key: 'Enter' })
}

beforeEach(() => {
  running = false
  preview = undefined
  current = { id: 1, name: 'Hamlet' }
  cues = [
    cue(1, 'Opener', { entries: [place(7, 'Act 1', 101, '1')] }),
    cue(2, 'Storm', { entries: [place(7, 'Act 1', 102, '7'), place(8, 'Act 2', 201, '14')] }),
    cue(3, 'Wash', {
      usedBy: { pads: [{ padId: 1, pageId: 1, pageName: 'Colours' }], slots: [{ slotId: 2, label: 'Page 1, slot 3' }] },
    }),
  ]
  duplicateCue.mockImplementation(({ cueId, stackId }) => landed({ cueId: cueId + 50, name: 'copy', stackId, entryId: null }))
  deleteCues.mockReturnValue(landed({ deleted: [], uses: [] }))
  addToStack.mockReturnValue(landed())
  patchCue.mockReturnValue(landed())
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
      unobserve() {}
    },
  )
})
afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

describe('ProjectCues', () => {
  it('lists every cue once with its places, Loose for none, what else uses it, and the live dot', () => {
    renderAt('/projects/1/cues')
    expect(names()).toEqual(['Opener', 'Storm', 'Wash'])
    expect([...row(2).querySelectorAll('[data-cue-place]')].map((el) => el.textContent)).toEqual(['Act 1· Q7', 'Act 2· Q14'])
    expect(row(3).querySelector('[data-cue-loose]')).not.toBeNull()
    expect(row(3).textContent).toContain('1 pad · 1 slot')
    expect(row(3).querySelector('[data-cue-live="loose"]')).not.toBeNull()
    expect(screen.getByText('3 cues · 1 loose')).toBeInTheDocument()
  })

  it('filters by the chips and the field, each into the address', () => {
    renderAt('/projects/1/cues')
    fireEvent.click(screen.getByRole('button', { name: /^Loose/ }))
    expect(names()).toEqual(['Wash'])
    expect(where()).toBe('/projects/1/cues?show=loose')
    fireEvent.click(screen.getByRole('button', { name: /^Shared/ }))
    expect(names()).toEqual(['Storm'])
    fireEvent.click(screen.getByRole('button', { name: /^On a pad/ }))
    expect(names()).toEqual(['Wash'])
    fireEvent.click(screen.getByRole('button', { name: /^All/ }))
    fireEvent.change(screen.getByLabelText('Filter by name, notes, stack or number'), { target: { value: 'q14' } })
    expect(names()).toEqual(['Storm'])
    expect(where()).toBe('/projects/1/cues?q=q14')
  })

  it('keeps one stack’s cues from `?stack=`', () => {
    renderAt('/projects/1/cues?stack=8')
    expect(names()).toEqual(['Storm'])
  })

  it('lands filtered to one cue from `?cue=`, and lets it go', () => {
    renderAt('/projects/1/cues?cue=3')
    expect(names()).toEqual(['Wash'])
    fireEvent.click(screen.getByRole('button', { name: /Only Wash/ }))
    expect(names()).toEqual(['Opener', 'Storm', 'Wash'])
    expect(where()).toBe('/projects/1/cues')
  })

  it('keeps a way out of `?cue=` naming a cue that is gone', () => {
    renderAt('/projects/1/cues?cue=99')
    expect(screen.getByText('No cues match the filter.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /A cue no longer here/ }))
    expect(names()).toEqual(['Opener', 'Storm', 'Wash'])
  })

  it('opens a placed cue at its first entry, and a loose one in the sheet', () => {
    renderAt('/projects/1/cues')
    selectRows(3)
    fireEvent.click(row(3).querySelector('[data-row-open]')!)
    expect(screen.getByTestId('cue-sheet').textContent).toBe('3')
    selectRows(2)
    fireEvent.click(row(2).querySelector('[data-row-open]')!)
    expect(where()).toBe('/projects/1/show/stacks/7?cue=2&entry=102')
  })
})

describe('the cue action set over several rows', () => {
  it('duplicates each cue: into its one stack, or in no stack when it has none or several', async () => {
    renderAt('/projects/1/cues')
    selectRows(1, 2, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Duplicate' }))
    await waitFor(() => expect(duplicateCue).toHaveBeenCalledTimes(3))
    expect(duplicateCue.mock.calls.map(([arg]) => arg)).toEqual([
      { projectId: 1, cueId: 1, stackId: 7 },
      { projectId: 1, cueId: 2, stackId: null },
      { projectId: 1, cueId: 3, stackId: null },
    ])
    // The table lists the copies itself.
    expect(where()).toBe('/projects/1/cues')
  })

  it('adds each cue to the stack chosen', async () => {
    renderAt('/projects/1/cues')
    selectRows(1, 3)
    openMenu(screen.getByRole('button', { name: 'More cue actions' }))
    const sub = await screen.findByRole('menuitem', { name: /Add to stack/ })
    fireEvent.keyDown(sub, { key: 'ArrowRight' })
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Act 2' }))
    await waitFor(() => expect(addToStack).toHaveBeenCalledTimes(2))
    expect(addToStack.mock.calls.map(([arg]) => arg)).toEqual([
      { projectId: 1, stackId: 8, cueId: 1 },
      { projectId: 1, stackId: 8, cueId: 3 },
    ])
  })

  it('says why Remove from stack is off for a cue in several places, even beside one it could remove', () => {
    renderAt('/projects/1/cues')
    selectRows(2)
    expect(screen.getByRole('button', { name: 'Remove from stack' }).getAttribute('title')).toMatch(/several places/)
    selectRows(1, 2)
    const remove = screen.getByRole('button', { name: 'Remove from stack' })
    expect(remove).toHaveProperty('disabled', true)
    expect(remove.getAttribute('title')).toMatch(/several places/)
  })

  it('Delete… lists each cue’s places before deleting with force', async () => {
    preview = {
      uses: [
        {
          cueId: 2,
          name: 'Storm',
          entries: [
            { stackId: 7, stackName: 'Act 1', entryId: 102, cueNumber: '7' },
            { stackId: 8, stackName: 'Act 2', entryId: 201, cueNumber: '14' },
          ],
          pads: [],
          slots: [],
          anchors: [],
          bindings: [],
        },
        {
          cueId: 3,
          name: 'Wash',
          entries: [],
          pads: [{ padId: 1, pageId: 1, pageName: 'Colours' }],
          slots: [],
          anchors: [],
          bindings: [],
        },
      ],
    }
    renderAt('/projects/1/cues')
    selectRows(2, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Delete…' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Delete 2 cues?')).toBeTruthy()
    const lines = [...dialog.querySelectorAll('[data-cue-use]')].map((el) => el.textContent)
    expect(lines).toEqual(['In Act 1 as Q7 · Act 2 as Q14', 'A busk pad on Colours'])
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    expect(deleteCues).toHaveBeenCalledWith({ projectId: 1, cueIds: [2, 3] })
  })
})

describe('the show-editing lock while the show runs', () => {
  it('asks to unlock before an action, and the cells and the loose cue’s editor read only', async () => {
    running = true
    renderAt('/projects/1/cues')
    expect(screen.getByRole('button', { name: 'Unlock for editing' })).toBeInTheDocument()
    selectRows(1, 3)
    fireEvent.click(screen.getByRole('button', { name: 'Duplicate' }))
    const ask = await screen.findByRole('alertdialog')
    expect(within(ask).getByText('Unlock the show to edit?')).toBeTruthy()
    expect(duplicateCue).not.toHaveBeenCalled()

    fireEvent.click(within(ask).getByRole('button', { name: /Unlock/ }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Lock the show' })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Duplicate' }))
    await waitFor(() => expect(duplicateCue).toHaveBeenCalledTimes(2))
  })

  it('asks to unlock before Copy from another show…, then opens it', async () => {
    running = true
    renderAt('/projects/1/cues')
    fireEvent.click(screen.getByRole('button', { name: 'Copy from another show…' }))
    const ask = await screen.findByRole('alertdialog')
    expect(screen.queryByTestId('copy-sheet')).toBeNull()
    fireEvent.click(within(ask).getByRole('button', { name: /Unlock/ }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Lock the show' })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Copy from another show…' }))
    expect(screen.getByTestId('copy-sheet').textContent).toBe('1')
  })

  it('opens a loose cue’s editor locked', () => {
    running = true
    renderAt('/projects/1/cues')
    selectRows(3)
    fireEvent.click(row(3).querySelector('[data-row-open]')!)
    expect(screen.getByTestId('cue-sheet').getAttribute('data-locked')).toBe('true')
  })

  it('reads another project’s cues with no actions at all', () => {
    renderAt('/projects/2/cues')
    selectRows(1)
    expect(screen.queryByRole('button', { name: 'Duplicate' })).toBeNull()
    expect(screen.getByText(/Panto’s cues — make it the current project to edit/)).toBeInTheDocument()
    expect(row(1).querySelector('[data-row-open]')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Copy from another show…' })).toBeNull()
  })
})
