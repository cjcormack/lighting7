// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LookSummary } from '@/api/looksApi'

/**
 * The Look library route around its sheet: the library row's filter and create verb, the pointer
 * across to templates, the footer, and the empty and not-found states. The sheet's own columns and
 * verbs are `LookSheet.test.tsx`'s.
 */
let looks: LookSummary[] = []
let current = { id: 1, name: 'Hamlet' }
let project: { id: number; name: string; isCurrent: boolean } | undefined = { id: 1, name: 'Hamlet', isCurrent: true }

vi.mock('@/store/looks', () => ({
  useLookListQuery: () => ({ data: looks, isLoading: false }),
  useCopyLookMutation: () => [vi.fn()],
  useSaveLookMutation: () => [vi.fn()],
  useDeleteLookMutation: () => [vi.fn()],
}))
vi.mock('@/store/projects', () => ({
  useCurrentProjectQuery: () => ({ data: current, isLoading: false }),
  useProjectQuery: () => ({ data: project, isLoading: false }),
  useProjectListQuery: () => ({ data: [] }),
}))
vi.mock('@/store/programmer', () => ({ useProgrammerSummaryQuery: () => ({ data: { entryCount: 3 } }) }))
vi.mock('@/store/hand', () => ({ handPickUp: () => true }))
vi.mock('@/components/programmer/useInclude', () => ({ useInclude: () => ({ include: vi.fn(), isLoading: false }) }))
vi.mock('@/components/programmer/RecordLookSheet', () => ({
  RecordLookSheet: ({ open }: { open: boolean }) => (open ? <div data-testid="record" /> : null),
}))
vi.mock('@/components/looks/LookDetailSheet', () => ({ LookDetailSheet: () => null }))
vi.mock('@/components/Breadcrumbs', () => ({ Breadcrumbs: () => null }))
vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () =>
      Array.from({ length: count }, (_, index) => ({ index, key: index, start: index * estimateSize(), size: estimateSize() })),
    scrollToIndex: () => {},
  }),
}))

const { ProjectLooks, filterLooks } = await import('./Looks')

function look(id: number, over: Partial<LookSummary> = {}): LookSummary {
  return {
    id,
    uuid: `l${id}`,
    name: `L${id}`,
    notes: null,
    families: ['COLOUR'],
    rowCount: 1,
    effectCount: 0,
    targetCount: 1,
    hasDeferredEffects: false,
    preview: [],
    layerCount: 0,
    buskPageCount: 0,
    ...over,
  }
}

function renderAt(url: string) {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/projects/:projectId/looks" element={<ProjectLooks />} />
      </Routes>
    </MemoryRouter>,
  )
}

function names(): string[] {
  return [...document.querySelectorAll('[data-row-id^="look:"] [data-first-column]')].map((el) => el.textContent ?? '')
}

beforeEach(() => {
  looks = [look(1, { name: 'Warm Wash', notes: 'Act 1 base', layerCount: 5 }), look(2, { name: 'Cool Fill', layerCount: 3 })]
  current = { id: 1, name: 'Hamlet' }
  project = { id: 1, name: 'Hamlet', isCurrent: true }
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
  vi.unstubAllGlobals()
})

describe('ProjectLooks', () => {
  it('draws the sheet, the pointer across to templates, and the footer’s counts', () => {
    renderAt('/projects/1/looks')
    expect(names()).toEqual(['Warm Wash', 'Cool Fill'])
    expect(screen.getByRole('link', { name: /Templates/ })).toHaveAttribute('href', '/projects/1/templates')
    expect(screen.getByText('2 looks · 8 cue layers')).toBeInTheDocument()
  })

  it('narrows by name or notes from the library row', () => {
    renderAt('/projects/1/looks')
    fireEvent.change(screen.getByLabelText('Filter by name or notes'), { target: { value: 'act 1' } })
    expect(names()).toEqual(['Warm Wash'])
    fireEvent.change(screen.getByLabelText('Filter by name or notes'), { target: { value: 'nothing' } })
    expect(screen.getByText('No looks match your filter.')).toBeInTheDocument()
  })

  it('creates by recording — the one way a Look is made — and only on the running project', () => {
    const { unmount } = renderAt('/projects/1/looks')
    fireEvent.click(screen.getByRole('button', { name: /Record from programmer/ }))
    expect(screen.getByTestId('record')).toBeInTheDocument()
    unmount()
    project = { id: 2, name: 'Rehearsal Room', isCurrent: false }
    renderAt('/projects/2/looks')
    expect(screen.queryByRole('button', { name: /Record from programmer/ })).not.toBeInTheDocument()
  })

  it('opens the record sheet from ?action=record, for the command palette', () => {
    renderAt('/projects/1/looks?action=record')
    expect(screen.getByTestId('record')).toBeInTheDocument()
  })

  it('says so when there are no looks, and when the project is missing', () => {
    looks = []
    const { unmount } = renderAt('/projects/1/looks')
    expect(screen.getByText(/No looks yet/)).toBeInTheDocument()
    unmount()
    project = undefined
    renderAt('/projects/9/looks')
    expect(screen.getByText('Project not found')).toBeInTheDocument()
  })
})

describe('filterLooks', () => {
  it('matches a name or notes, case-insensitively, and an empty filter keeps everything', () => {
    const all = [look(1, { name: 'Sunset' }), look(2, { name: 'Wash', notes: 'sunset fill' })]
    expect(filterLooks(all, '').map((l) => l.id)).toEqual([1, 2])
    expect(filterLooks(all, 'SUNSET').map((l) => l.id)).toEqual([1, 2])
    expect(filterLooks(all, 'wash').map((l) => l.id)).toEqual([2])
  })
})
