// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TooltipProvider } from '@/components/ui/tooltip'

/**
 * The sidebar draws the registry's sections (navigation plan D3): a heading over each, expanded,
 * and a rule between them on the rail; a section with no visible row draws neither.
 */

type Kind = 'SHOW' | 'VENUE'
let projects: { id: number; name: string; isCurrent: boolean; kind: Kind }[] = []

vi.mock('./store/projects', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./store/projects')>()),
  useProjectListQuery: () => ({ data: projects, isLoading: false }),
}))
vi.mock('./store/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./store/auth')>()),
  useAuthStatusQuery: () => ({ data: { user: { role: 'ADMIN' } } }),
}))
vi.mock('./store/oauthGithub', () => ({ useOAuthReauthState: () => ({ reauthRequired: false }) }))

const { default: ProjectSwitcher } = await import('./ProjectSwitcher')

function renderAt(path: string, collapsed = false) {
  return render(
    <TooltipProvider>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/projects" element={<ProjectSwitcher collapsed={collapsed} />} />
          <Route path="/projects/:projectId/*" element={<ProjectSwitcher collapsed={collapsed} />} />
        </Routes>
      </MemoryRouter>
    </TooltipProvider>,
  )
}

const headings = (container: HTMLElement) =>
  [...container.querySelectorAll('[data-nav-section]')].map((el) => el.textContent)

beforeEach(() => {
  projects = [
    { id: 1, name: 'Sleeping Beauty', isCurrent: true, kind: 'SHOW' },
    { id: 2, name: 'TCH venue', isCurrent: false, kind: 'VENUE' },
  ]
})
afterEach(cleanup)

describe('the sidebar sections', () => {
  it('heads each section, Run first', () => {
    const { container } = renderAt('/projects/1/programmer')
    expect(headings(container)).toEqual(['Run', 'Rig', 'Library', 'Project', 'Desk'])
    const rows = [...container.querySelectorAll('button')].map((b) => b.textContent)
    expect(rows.indexOf('Programmer')).toBeLessThan(rows.indexOf('Patch List'))
    expect(rows.indexOf('Stage')).toBeLessThan(rows.indexOf('Patch List'))
    expect(rows.indexOf('Regions')).toBeLessThan(rows.indexOf('Channels'))
    expect(rows).toContain('Effects')
  })

  it('drops the Library heading in a venue', () => {
    projects = projects.map((p) => ({ ...p, isCurrent: p.id === 2 }))
    const { container } = renderAt('/projects/2/patches')
    expect(headings(container)).toEqual(['Run', 'Rig', 'Project', 'Desk'])
  })

  it('lights Projects on the projects list', () => {
    const lit = (name: string) => screen.getByRole('button', { name }).className.split(/\s+/).includes('bg-accent')
    renderAt('/projects')
    expect(lit('Projects')).toBe(true)
    cleanup()
    renderAt('/projects/1/looks')
    expect(lit('Projects')).toBe(false)
    expect(lit('Looks')).toBe(true)
  })

  it('draws a rule between sections on the rail, and no heading', () => {
    const { container } = renderAt('/projects/1/programmer', true)
    expect(headings(container)).toEqual([])
    expect(container.querySelectorAll('[data-slot="separator"]').length).toBe(4)
  })
})

/** Navigation plan D4: off the rig every row stays, and the nine that need it carry a hollow dot. */
describe('off the rig', () => {
  const offRigRows = (container: HTMLElement) =>
    [...container.querySelectorAll('[data-off-rig]')].map((el) => el.textContent)

  beforeEach(() => {
    projects = [
      { id: 1, name: 'Sleeping Beauty', isCurrent: true, kind: 'SHOW' },
      { id: 3, name: 'TCH Test', isCurrent: false, kind: 'SHOW' },
    ]
  })

  it('draws every row on a project that is not current, and dots the nine that need the rig', () => {
    const { container: on } = renderAt('/projects/1/patches')
    const onRows = [...on.querySelectorAll('button')].map((b) => b.textContent)
    cleanup()
    const { container } = renderAt('/projects/3/patches')
    expect([...container.querySelectorAll('button')].map((b) => b.textContent).slice(1)).toEqual(onRows.slice(1))
    expect(offRigRows(container)).toEqual([
      'Programmer', 'Show', 'Prompt Book', 'Busk', 'Stage', 'Fixtures', 'Groups', 'Channels', 'Surfaces',
    ])
    for (const row of container.querySelectorAll('[data-off-rig]')) {
      expect(row.querySelector('[data-off-rig-dot]')).not.toBeNull()
      expect(row.className).toContain('text-muted-foreground')
    }
  })

  it('draws no dot on the current project', () => {
    const { container } = renderAt('/projects/1/patches')
    expect(offRigRows(container)).toEqual([])
    expect(container.querySelector('[data-off-rig-dot]')).toBeNull()
  })

  it('says Not on the rig in the header, never Inactive', () => {
    renderAt('/projects/3/patches')
    expect(screen.getByText('Not on the rig')).toBeTruthy()
    expect(screen.queryByText('Inactive')).toBeNull()
    cleanup()
    renderAt('/projects/1/patches')
    expect(screen.queryByText('Not on the rig')).toBeNull()
  })

  it('puts the dot on the rail too', () => {
    const { container } = renderAt('/projects/3/patches', true)
    expect(container.querySelectorAll('[data-off-rig] [data-off-rig-dot]').length).toBe(9)
  })
})
