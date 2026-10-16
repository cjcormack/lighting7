// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate, type NavigateFunction } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * Navigation plan D4, §3.2: a view that needs the rig draws `NotOnRig` for a project that is not
 * on it, naming the show that is, and its *Make current* goes through the one switch confirmation.
 */

type Project = {
  id: number
  name: string
  description: null
  isCurrent: boolean
  kind?: 'SHOW' | 'VENUE'
  venue?: { uuid: string; id?: number | null; name?: string | null; version: number } | null
}
let projects: Project[] | undefined = []
let listError = false
const setCurrent = vi.fn(() => ({ unwrap: () => Promise.resolve() }))
const refetch = vi.fn()

vi.mock('@/store/projects', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/store/projects')>()),
  useProjectListQuery: () => ({ data: projects, isLoading: false, isFetching: false, isError: listError, refetch }),
  useSetCurrentProjectMutation: () => [setCurrent, { isLoading: false }],
}))

// The confirmation reads rig safety for its lamp-run line.
vi.mock('@/store/rigSafety', async () => {
  const { NOTHING_SAFE } = await import('@/api/rigSafetyApi')
  return { useRigSafety: () => NOTHING_SAFE }
})

const { RigGate } = await import('./NotOnRig')
const { setSheetUnsaved, resetUnsavedSheets } = await import('@/lib/unsavedSheets')

let go: NavigateFunction = () => {}

function Where() {
  go = useNavigate()
  const location = useLocation()
  return <div data-testid="where">{location.pathname + location.search}</div>
}

function renderAt(path: string, navId = 'program') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="/projects/:projectId/show/stacks/:stackId"
          element={
            <RigGate navId={navId}>
              <div>the view</div>
            </RigGate>
          }
        />
        <Route
          path="/projects/:projectId/stage"
          element={
            <RigGate navId="stage-view">
              <div>the view</div>
            </RigGate>
          }
        />
        <Route
          path="/projects/:projectId/fixtures/list"
          element={
            <RigGate navId="fixtures" to={(p, { search }) => `/projects/${p}/fixtures/list${search}`}>
              <div>the view</div>
            </RigGate>
          }
        />
        <Route path="*" element={null} />
      </Routes>
      <Where />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  projects = [
    { id: 1, name: 'Sleeping Beauty', description: null, isCurrent: true, kind: 'SHOW' },
    { id: 2, name: 'TCH venue', description: null, isCurrent: false, kind: 'VENUE' },
    { id: 3, name: 'TCH Test', description: null, isCurrent: false, kind: 'SHOW' },
  ]
  listError = false
  setCurrent.mockClear()
  resetUnsavedSheets()
})
afterEach(cleanup)

describe('RigGate', () => {
  it('draws the view on the project on the rig', () => {
    renderAt('/projects/1/show/stacks/10?cue=4')
    expect(screen.getByText('the view')).toBeTruthy()
    expect(document.querySelector('[data-not-on-rig]')).toBeNull()
  })

  it('draws NotOnRig in its place on another project, naming the show that is on the rig', () => {
    renderAt('/projects/2/stage')
    expect(screen.queryByText('the view')).toBeNull()
    expect(screen.getByRole('heading').textContent).toBe('TCH venue isn’t on the rig.')
    expect(document.querySelector('[data-not-on-rig] p')?.textContent).toBe(
      "The Stage view draws the rig that's live, and Sleeping Beauty is on it now.",
    )
    expect(screen.getByRole('button', { name: 'Open Sleeping Beauty’s Stage' })).toBeTruthy()
  })

  it('never says active', () => {
    renderAt('/projects/3/show/stacks/10')
    expect(document.body.textContent).not.toMatch(/\bactive\b/i)
  })

  it('leaves the deep link alone, and draws it once the project is made current', () => {
    const view = renderAt('/projects/3/show/stacks/10?cue=4')
    expect(screen.getByTestId('where').textContent).toBe('/projects/3/show/stacks/10?cue=4')

    fireEvent.click(screen.getByRole('button', { name: 'Make TCH Test current' }))
    const dialog = screen.getByRole('dialog')
    expect(dialog.textContent).toContain('Sleeping Beauty')
    expect(dialog.textContent).toContain('TCH Test')
    expect(setCurrent).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Put TCH Test on the rig' }))
    expect(setCurrent).toHaveBeenCalledWith(3)

    // The desk answers and the project list follows: the view draws on the address asked for.
    projects = projects!.map((p) => ({ ...p, isCurrent: p.id === 3 }))
    act(() => {
      view.rerender(
        <MemoryRouter initialEntries={['/projects/3/show/stacks/10?cue=4']}>
          <Routes>
            <Route
              path="/projects/:projectId/show/stacks/:stackId"
              element={
                <RigGate navId="program">
                  <div>the view</div>
                </RigGate>
              }
            />
          </Routes>
          <Where />
        </MemoryRouter>,
      )
    })
    expect(screen.getByText('the view')).toBeTruthy()
    expect(screen.getByTestId('where').textContent).toBe('/projects/3/show/stacks/10?cue=4')
  })

  it('cancelling the confirmation switches nothing', () => {
    renderAt('/projects/3/show/stacks/10')
    fireEvent.click(screen.getByRole('button', { name: 'Make TCH Test current' }))
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(setCurrent).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('opens the same view on the show on the rig', () => {
    renderAt('/projects/2/stage')
    fireEvent.click(screen.getByRole('button', { name: 'Open Sleeping Beauty’s Stage' }))
    expect(screen.getByTestId('where').textContent).toBe('/projects/1/stage')
  })

  it('offers no way to a view the project on the rig does not have', () => {
    projects = projects!.map((p) => ({ ...p, isCurrent: p.id === 2 }))
    renderAt('/projects/3/show/stacks/10')
    expect(screen.getByText(/TCH venue/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: /^Open / })).toBeNull()
  })

  it('offers no Make current for a show whose venue is not on this desk', () => {
    projects = projects!.map((p) => (p.id === 3 ? { ...p, venue: { uuid: 'v', id: null, name: 'Hall', version: 4 } } : p))
    renderAt('/projects/3/show/stacks/10')
    expect(screen.queryByRole('button', { name: /current/ })).toBeNull()
    expect(screen.getByText(/Hall v4/)).toBeTruthy()
  })

  it('never draws the view for a project the list does not hold, such as a venue version', () => {
    renderAt('/projects/99/stage')
    expect(screen.queryByText('the view')).toBeNull()
    expect(screen.getByText('Project not found')).toBeTruthy()
  })

  it('never draws the view when the project list cannot be read', () => {
    projects = undefined
    listError = true
    renderAt('/projects/1/stage')
    expect(screen.queryByText('the view')).toBeNull()
    expect(screen.getByText(/Couldn.t load the projects/)).toBeTruthy()
  })

  it('opens the route’s own address on the show on the rig, deep link kept', () => {
    renderAt('/projects/3/fixtures/list?select=fixture%3Ahex-1')
    fireEvent.click(screen.getByRole('button', { name: 'Open Sleeping Beauty’s Fixtures' }))
    expect(screen.getByTestId('where').textContent).toBe('/projects/1/fixtures/list?select=fixture%3Ahex-1')
  })
})

/** A desk screen showing the project on the rig follows a switch made elsewhere (Chris's call). */
describe('RigGate following a switch', () => {
  // The list is a mock, not a subscription, so the switch re-renders with a fresh element.
  const switchTo = (id: number, view: ReturnType<typeof renderLive>) => {
    projects = projects!.map((p) => ({ ...p, isCurrent: p.id === id }))
    view.rerender(view.ui())
  }

  function renderLive(path: string) {
    const ui = () => (
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route
            path="/projects/:projectId/stage"
            element={
              <RigGate navId="stage-view">
                <div>the view</div>
              </RigGate>
            }
          />
          <Route
            path="/projects/:projectId/show/stacks/:stackId"
            element={
              <RigGate navId="program">
                <div>the view</div>
              </RigGate>
            }
          />
          <Route path="*" element={null} />
        </Routes>
        <Where />
      </MemoryRouter>
    )
    return { ...render(ui()), ui }
  }

  it('moves a view that was on the rig onto the show that replaced it', () => {
    const view = renderLive('/projects/1/stage')
    act(() => switchTo(3, view))
    expect(screen.getByTestId('where').textContent).toBe('/projects/3/stage')
    expect(screen.getByText('the view')).toBeTruthy()
  })

  it('waits while a sheet holds unsaved edits, then follows', () => {
    const view = renderLive('/projects/1/stage')
    const sheet = Symbol('sheet')
    act(() => setSheetUnsaved(sheet, true))
    act(() => switchTo(3, view))
    expect(screen.getByTestId('where').textContent).toBe('/projects/1/stage')
    expect(screen.getByText('the view')).toBeTruthy()
    act(() => setSheetUnsaved(sheet, false))
    expect(screen.getByTestId('where').textContent).toBe('/projects/3/stage')
  })

  it('draws NotOnRig where the new project has no such view', () => {
    const view = renderLive('/projects/1/show/stacks/10')
    act(() => switchTo(2, view))
    expect(screen.getByTestId('where').textContent).toBe('/projects/1/show/stacks/10')
    expect(screen.getByRole('heading').textContent).toBe('Sleeping Beauty isn’t on the rig.')
  })

  it('forgets the view it drew once the window moves to a project off the rig', () => {
    const view = renderLive('/projects/1/stage')
    act(() => go('/projects/2/stage'))
    expect(document.querySelector('[data-not-on-rig]')).not.toBeNull()
    act(() => switchTo(3, view))
    act(() => go('/projects/1/stage'))
    expect(screen.getByTestId('where').textContent).toBe('/projects/1/stage')
    expect(screen.getByRole('heading').textContent).toBe('Sleeping Beauty isn’t on the rig.')
  })

  it('never moves a view opened on a project that was not on the rig', () => {
    const view = renderLive('/projects/3/stage')
    act(() => switchTo(2, view))
    expect(screen.getByTestId('where').textContent).toBe('/projects/3/stage')
    expect(document.querySelector('[data-not-on-rig]')).not.toBeNull()
  })
})
