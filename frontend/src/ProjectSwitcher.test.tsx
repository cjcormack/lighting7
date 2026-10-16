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
